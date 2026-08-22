/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.codeindex.IndexDatabase;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.project.LocalCrawlCliAgentRunner;
import ai.kompile.cli.main.project.LocalCrawlServingSession;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.crawl.graph.CrawlStepPlan;
import ai.kompile.crawl.graph.HeadlessUnifiedCorpusExtractor;
import ai.kompile.graph.reasoning.debug.UnifiedGraphDebugRenderer;
import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.lifecycle.FinalGraphLearningResolutionPipeline;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.resolution.UnifiedGraphEntityResolver;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.document.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * Graph lifecycle used inside the request-scoped project-local crawl worker.
 *
 * <p>The local crawler writes the same portable {@code UnifiedGraph} archive used by the graph
 * service. A crawl rebuilds topology from the current document/chunk artifacts and the incremental
 * local code index, while retaining compatible learned assets for surviving ids. The replacement
 * archive is written atomically, so deleted files disappear from the next graph without exposing a
 * partially updated graph.</p>
 */
public final class LocalProjectGraphBackend {
    public static final String GRAPH_FILE = "graph.kgraph";
    public static final String ENTITY_LAYER = "kge";
    public static final String RELATION_LAYER = "kge-relations";
    public static final String MODEL_ARTIFACT = "models/kge.json";

    private static final int DEFAULT_DIM = 32;
    private static final int DEFAULT_EPOCHS = 8;
    private static final double DEFAULT_LEARNING_RATE = 0.05;
    private static final int MAX_DIM = 256;
    private static final int MAX_EPOCHS = 500;

    private final ObjectMapper mapper;
    private final KompileProjectStore projectStore;
    private static final AtomicLong LOCAL_KB_VERSION = new AtomicLong();
    private static final Map<String, LocalSubscription> LOCAL_SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final Map<String, ObjectNode> LOCAL_SIMULATION_RUNS = new ConcurrentHashMap<>();

    public LocalProjectGraphBackend(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.projectStore = new KompileProjectStore();
    }

    public GraphUpdate updateCrawlGraph(Path projectRoot,
                                        String knowledgeBaseId,
                                        String knowledgeBaseName,
                                        Long factSheetId,
                                        String projectId,
                                        List<CodeProjectSource> codeProjects,
                                        String crawlJobId,
                                        JsonNode request) throws Exception {
        Path directory = projectRoot.resolve("data/crawls").resolve(knowledgeBaseId).normalize();
        Path graphPath = directory.resolve(GRAPH_FILE);
        UnifiedGraph previous = Files.isRegularFile(graphPath) ? UnifiedGraph.load(graphPath) : null;
        UnifiedGraph graph = new UnifiedGraph()
                .graphId("local:" + projectId + ":" + knowledgeBaseId)
                .meta("backend", "project-local")
                .meta("projectId", projectId)
                .meta("knowledgeBaseId", knowledgeBaseId)
                .meta("knowledgeBaseName", knowledgeBaseName)
                .meta("updatedAt", Instant.now().toString());
        if (factSheetId != null) {
            graph.factSheetId(factSheetId);
        }

        String projectNode = "project:" + stableId(projectRoot.toAbsolutePath().normalize().toString());
        String knowledgeBaseNode = "knowledge-base:" + stableId(projectId + "\n" + knowledgeBaseId);
        graph.addEntity(GraphEntity.builder(projectNode)
                .type("KOMPILE_PROJECT")
                .label(projectId)
                .tag("project")
                .attribute("projectId", projectId)
                .attribute("projectRoot", projectRoot.toAbsolutePath().normalize().toString())
                .build());
        Map<String, Object> knowledgeBaseAttributes = new LinkedHashMap<>();
        knowledgeBaseAttributes.put("knowledgeBaseId", knowledgeBaseId);
        if (factSheetId != null) {
            knowledgeBaseAttributes.put("factSheetId", factSheetId);
        }
        graph.addEntity(GraphEntity.builder(knowledgeBaseNode)
                .type("KNOWLEDGE_BASE")
                .label(knowledgeBaseName)
                .tag("crawl")
                .attributes(knowledgeBaseAttributes)
                .build());
        addRelation(graph, projectNode, knowledgeBaseNode, "HAS_KNOWLEDGE_BASE",
                Map.of("backend", "project-local"));

        Map<String, String> documentNodes = addDocuments(graph, directory, knowledgeBaseNode,
                projectId, knowledgeBaseId, factSheetId);
        int codeEntityCount = addCodeProjects(graph, knowledgeBaseNode, documentNodes,
                projectId, factSheetId, codeProjects == null ? List.of() : codeProjects);
        SemanticExtractionSummary semanticExtraction = addSemanticExtraction(
                graph, projectRoot, directory, knowledgeBaseNode, knowledgeBaseId,
                factSheetId, crawlJobId, request);

        retainCompatibleAssets(previous, graph);
        TrainingRequest training = TrainingRequest.from(request);
        ReasoningLearningRequest reasoningLearning = ReasoningLearningRequest.from(request);
        ResolutionRequest resolution = ResolutionRequest.from(request);
        boolean learningEnabled = !graph.relations().isEmpty()
                && (training.enabled() || reasoningLearning.enabled());

        FinalGraphLearningResolutionPipeline.Result<
                UnifiedGraph, FinalLearningSummary, ResolutionSummary> lifecycle =
                FinalGraphLearningResolutionPipeline.run(
                        graph,
                        learningEnabled,
                        (scope, phase) -> {
                            UnifiedGraph warmStart =
                                    phase == FinalGraphLearningResolutionPipeline.LearningPhase.PRE_RESOLUTION
                                            ? previous : scope;
                            TrainingSummary learned = training.enabled()
                                    ? train(scope, training, warmStart) : null;
                            UnifiedGraphReasoningLifecycle.Summary reasoning =
                                    reasoningLearning.enabled()
                                            ? UnifiedGraphReasoningLifecycle.learn(
                                                    scope, reasoningLearning.toConfig())
                                            : UnifiedGraphReasoningLifecycle.Summary.disabled();
                            return new FinalGraphLearningResolutionPipeline.LearningOutcome<>(
                                    scope, new FinalLearningSummary(learned, reasoning));
                        },
                        scope -> {
                            if (!resolution.enabled()) {
                                return new FinalGraphLearningResolutionPipeline.ResolutionOutcome<>(
                                        scope, 0, false, ResolutionSummary.disabled());
                            }
                            UnifiedGraphEntityResolver.Result resolved =
                                    new UnifiedGraphEntityResolver().resolve(
                                            scope, resolution.toResolverConfig());
                            return new FinalGraphLearningResolutionPipeline.ResolutionOutcome<>(
                                    resolved.graph(),
                                    resolved.entitiesMerged(),
                                    resolved.graphChanged(),
                                    ResolutionSummary.from(resolved));
                        });

        graph = lifecycle.scope();
        FinalLearningSummary finalLearning = lifecycle.canonicalLearning() != null
                ? lifecycle.canonicalLearning() : lifecycle.preResolutionLearning();
        TrainingSummary trainingSummary = finalLearning != null
                ? finalLearning.embedding() : null;
        UnifiedGraphReasoningLifecycle.Summary reasoningSummary = finalLearning != null
                ? finalLearning.reasoning() : UnifiedGraphReasoningLifecycle.Summary.disabled();
        ResolutionSummary resolutionSummary = lifecycle.resolution();
        graph.meta("enrichment.requested", reasoningLearning.enrichmentRequested())
                .meta("enrichment.engine", "UnifiedGraphReasoningLifecycle")
                .meta("enrichment.status", !reasoningLearning.enrichmentRequested()
                        ? "SKIPPED_BY_STEP_PLAN"
                        : reasoningSummary.enabled() ? "COMPLETED"
                        : graph.relations().isEmpty() ? "SKIPPED_EMPTY_GRAPH"
                        : "SKIPPED_BY_CONFIGURATION");

        Files.createDirectories(directory);
        saveAtomic(graph, graphPath);
        updateCrawlSummary(directory, graph, graphPath, codeEntityCount, trainingSummary,
                reasoningSummary, resolutionSummary, semanticExtraction);

        return new GraphUpdate(graphPath, graph.entities().size(), graph.relations().size(),
                codeEntityCount, graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum(),
                trainingSummary != null ? trainingSummary.algorithm() : null,
                reasoningLearning.enrichmentRequested(),
                reasoningSummary.enabled(), reasoningSummary.folPslLearned(),
                reasoningSummary.mebnLearned(), reasoningSummary.modelsTrained(),
                reasoningSummary.pslRuleCount(), reasoningSummary.mebnFragmentCount(),
                resolutionSummary.enabled(), resolutionSummary.entitiesMerged(),
                resolutionSummary.typesCorrected(), resolutionSummary.identifierLinksCreated(),
                semanticExtraction.entities(), semanticExtraction.relations(),
                semanticExtraction.errors());
    }

    public JsonNode reasoningQuery(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        GraphQueryEngine.Query query = toQuery(params);
        GraphQueryEngine.Result result = new GraphQueryEngine().query(selection.graph(), query);
        ObjectNode json = mapper.valueToTree(result);
        json.put("backend", "project-local");
        json.put("graphPath", selection.pathDescription());
        return json;
    }

    public ToolResult reason(JsonNode params, ToolContext context) {
        String target = params.path("target").asText("").trim();
        if (target.isEmpty()) {
            return ToolResult.error("target is required");
        }
        try {
            ObjectNode query = mapper.createObjectNode();
            copySelector(params, query);
            String normalized = target.startsWith("causal:") ? target.substring(7).trim() : target;
            java.util.regex.Matcher fact = java.util.regex.Pattern
                    .compile("^([^\\s(]+)\\(([^,]+),\\s*([^)]+)\\)$")
                    .matcher(normalized);
            if (fact.matches()) {
                query.put("operation", "VERIFY");
                query.put("entityId", fact.group(2).trim());
                query.put("targetId", fact.group(3).trim());
                query.putArray("relationTypes").add(fact.group(1).trim());
            } else {
                query.put("operation", "DESCRIBE");
                query.put("entityId", normalized);
            }
            JsonNode result = reasoningQuery(query, context);
            String status = result.path("status").asText("UNKNOWN");
            String summary = result.path("summary").asText("No explanation was produced.");
            StringBuilder output = new StringBuilder()
                    .append("**").append(status).append("**\n")
                    .append("Target: ").append(target).append("\n\n")
                    .append("Answer:\n").append(summary);
            JsonNode relations = result.path("relations");
            if (relations.isArray() && !relations.isEmpty()) {
                output.append("\n\nSupporting graph relations:");
                for (JsonNode relation : relations) {
                    output.append("\n  - ")
                            .append(relation.path("sourceLabel").asText(relation.path("sourceId").asText("?")))
                            .append(" -[").append(relation.path("type").asText("?")).append("]-> ")
                            .append(relation.path("targetLabel").asText(relation.path("targetId").asText("?")));
                }
            }
            return ToolResult.success("graph_reason: " + target, output.toString(),
                    Map.of("target", target, "verdict", status, "backend", "project-local"));
        } catch (Exception e) {
            return ToolResult.error("graph_reason local error: " + message(e));
        }
    }

    public ToolResult exportGraph(JsonNode params, ToolContext context) {
        String requestedPath = params.path("path").asText("").trim();
        if (requestedPath.isEmpty()) {
            return ToolResult.error("path is required");
        }
        String format = params.path("format").asText("kgraph").toLowerCase(Locale.ROOT);
        try {
            GraphSelection selection = selectGraph(context, params);
            Path output = context.resolvePath(requestedPath).toAbsolutePath().normalize();
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            if ("kgraph".equals(format)) {
                saveAtomic(selection.graph(), output);
            } else if ("ascii".equals(format)) {
                Files.writeString(output, UnifiedGraphDebugRenderer.toAscii(selection.graph()),
                        StandardCharsets.UTF_8);
            } else {
                return ToolResult.error("Project-local graph export supports kgraph and ascii; "
                        + "PNG rendering is not available in the in-process archive backend.");
            }
            long bytes = Files.size(output);
            return ToolResult.success("graph_export: " + output.getFileName(),
                    "Exported the project-local graph (" + bytes + " bytes) to " + output + ".",
                    Map.of("path", output.toString(), "bytes", bytes, "backend", "project-local",
                            "entities", selection.graph().entities().size(),
                            "relations", selection.graph().relations().size()));
        } catch (Exception e) {
            return ToolResult.error("graph_export local error: " + message(e));
        }
    }

    public ToolResult importGraph(JsonNode params, ToolContext context) {
        String requestedPath = params.path("path").asText("").trim();
        if (requestedPath.isEmpty()) {
            return ToolResult.error("path is required");
        }
        try {
            Path source = context.resolvePath(requestedPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                return ToolResult.error("No .kgraph file at: " + source);
            }
            UnifiedGraph graph = UnifiedGraph.load(source);
            Long requestedFactSheet = optionalLong(params, "factSheetId", "fact_sheet_id");
            if (requestedFactSheet != null) {
                graph.factSheetId(requestedFactSheet);
            }
            Path root = projectRoot(context.getWorkingDirectory());
            String knowledgeBaseId = graph.factSheetId() != null
                    ? "kb-" + graph.factSheetId()
                    : slug(firstNonBlank(stringMeta(graph, "knowledgeBaseId"), graph.graphId(), "imported-graph"));
            Path directory = root.resolve("data/crawls").resolve(knowledgeBaseId).normalize();
            if (!directory.startsWith(root)) {
                return ToolResult.error("Imported graph target escapes the project root.");
            }
            Files.createDirectories(directory);
            Path target = directory.resolve(GRAPH_FILE);
            saveAtomic(graph, target);
            writeImportedSummary(directory, graph, target);
            int embeddings = graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum();
            return ToolResult.success("graph_import: " + source.getFileName(),
                    "Loaded " + graph.entities().size() + " nodes, " + graph.relations().size()
                            + " edges, and " + embeddings + " vectors into project-local knowledge base "
                            + knowledgeBaseId + ".",
                    Map.of("nodes", graph.entities().size(), "edges", graph.relations().size(),
                            "embeddings", embeddings, "backend", "project-local",
                            "knowledgeBase", knowledgeBaseId));
        } catch (Exception e) {
            return ToolResult.error("graph_import local error: " + message(e));
        }
    }

    public ToolResult embeddings(JsonNode params, ToolContext context) {
        String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        try {
            if ("algorithms".equals(action)) {
                return ToolResult.success("graph_embeddings.algorithms",
                        "Available project-local KGE algorithms:\n\n"
                                + "- **TransE** (id=TRANSE)\n  Translational entity/relation vectors.\n"
                                + "- **RotatE** (id=ROTATE)\n  Complex entity vectors with relation phases.\n",
                        Map.of("backend", "project-local"));
            }
            GraphSelection selection = selectGraph(context, params);
            return switch (action) {
                case "train" -> trainAction(params, selection);
                case "jobs" -> jobsAction(selection);
                case "job_status" -> jobStatusAction(params, selection);
                case "cancel" -> ToolResult.error(
                        "Project-local embedding training is synchronous and cannot be cancelled.");
                case "score" -> scoreAction(params, selection);
                case "predict_tails" -> predictAction(params, selection, PredictionTarget.TAIL);
                case "predict_heads" -> predictAction(params, selection, PredictionTarget.HEAD);
                case "predict_relations" -> predictAction(params, selection, PredictionTarget.RELATION);
                case "similar" -> similarAction(params, selection);
                default -> ToolResult.error("Unknown action: " + action);
            };
        } catch (Exception e) {
            return ToolResult.error("graph_embeddings local error: " + message(e));
        }
    }

    public GraphStats stats(Path workingDirectory, String knowledgeBase) throws Exception {
        GraphSelection selection = selectGraph(workingDirectory, selector(knowledgeBase, null));
        int embeddings = selection.graph().vectorLayers().values().stream()
                .mapToInt(VectorLayer::size).sum();
        return new GraphStats(selection.graph().entities().size(),
                selection.graph().relations().size(), embeddings, selection.pathDescription());
    }

    /** Execute the standard {@code knowledge_graph} contract against project-local crawl archives. */
    public ToolResult knowledgeGraph(JsonNode params, ToolContext context) {
        String action = params.path("action").asText("").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) {
            return ToolResult.error("action is required");
        }
        try {
            return switch (action) {
                case "list_fact_sheets" -> localFactSheetInventory(context);
                case "list_graphs", "list_snapshots" ->
                        localGraphInventory(action, context);
                case "overview", "stats", "graph_health", "report", "get_fact_sheet",
                     "get_active_fact_sheet", "opinions", "facts_by_tier", "reasoning_layers",
                     "owl_reasoning" -> localOverview(action, params, context);
                case "list_nodes", "search_nodes", "search_entity", "find_by_topic" ->
                        localNodes(action, params, context);
                case "get_node", "node_provenance" -> localNode(action, params, context);
                case "list_edges" -> localEdges(params, context);
                case "list_predicates" -> localPredicates(params, context);
                case "find_connected", "related_docs", "source_context", "entities_in_doc",
                     "traverse", "hierarchy", "ancestors", "source_chunks", "shortest_path" ->
                        localReasoning(action, params, context);
                default -> ToolResult.error("Project-local knowledge graph action '" + action
                        + "' is not implemented by the archive backend yet. Local crawl archives support "
                        + "graph discovery, status, node/edge search, predicate discovery, traversal, "
                        + "algorithms, reporting, and shortest paths.");
            };
        } catch (Exception e) {
            return ToolResult.error("knowledge_graph local error: " + message(e));
        }
    }

    private ToolResult localFactSheetInventory(ToolContext context) throws Exception {
        Path root = projectRoot(context.getWorkingDirectory());
        ArrayNode factSheets = mapper.createArrayNode();
        ArrayNode warnings = mapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();

        // Crawl summaries are the authoritative project-local inventory. Listing fact sheets must
        // not deserialize every graph archive: one truncated or incompatible graph must not hide
        // otherwise healthy knowledge bases.
        ArrayNode inventory = new LocalProjectCrawlBackend(mapper, this)
                .knowledgeBaseInventory(context.getWorkingDirectory());
        for (JsonNode candidate : inventory) {
            if (!candidate.isObject()) continue;
            ObjectNode item = (ObjectNode) candidate.deepCopy();
            String id = item.path("id").asText("");
            if (!id.isBlank()) seen.add(id);
            item.put("inventorySource", "crawl-summary");
            factSheets.add(item);
        }

        // Preserve discovery of legacy graph-only folders, but isolate decode failures per file.
        Path crawls = root.resolve("data/crawls");
        if (Files.isDirectory(crawls)) {
            List<Path> paths;
            try (Stream<Path> directories = Files.list(crawls)) {
                paths = directories.map(path -> path.resolve(GRAPH_FILE))
                        .filter(Files::isRegularFile).sorted().toList();
            }
            for (Path path : paths) {
                String folderId = path.getParent().getFileName().toString();
                if (seen.contains(folderId)) continue;
                try {
                    UnifiedGraph graph = UnifiedGraph.load(path);
                    ObjectNode item = factSheets.addObject();
                    String id = firstNonBlank(stringMeta(graph, "knowledgeBaseId"), folderId);
                    item.put("id", id);
                    item.put("name", firstNonBlank(
                            stringMeta(graph, "knowledgeBaseName"), graph.graphId(), id));
                    item.put("graphId", graph.graphId());
                    if (graph.factSheetId() != null) item.put("factSheetId", graph.factSheetId());
                    item.put("graphEntityCount", graph.entities().size());
                    item.put("graphRelationCount", graph.relations().size());
                    item.put("graphPath", path.toString());
                    item.put("backend", "project-local");
                    item.put("inventorySource", "legacy-graph");
                    seen.add(id);
                } catch (Exception e) {
                    warnings.addObject()
                            .put("id", folderId)
                            .put("graphPath", path.toString())
                            .put("message", "Skipped unreadable legacy graph: " + message(e));
                }
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("projectRoot", root.toString());
        result.put("count", factSheets.size());
        result.put("skippedUnreadableGraphs", warnings.size());
        result.set("factSheets", factSheets);
        result.set("warnings", warnings);
        return localSuccess("list_fact_sheets", result);
    }

    private ToolResult localGraphInventory(String action, ToolContext context) throws Exception {
        ToolResult bootstrap = new LocalProjectCrawlBackend(mapper, this)
                .ensureFolderKnowledgeBase(context);
        if (bootstrap.isError()) {
            return bootstrap;
        }
        Path root = projectRoot(context.getWorkingDirectory());
        Path crawls = root.resolve("data/crawls");
        ArrayNode graphs = mapper.createArrayNode();
        if (Files.isDirectory(crawls)) {
            List<Path> paths;
            try (Stream<Path> directories = Files.list(crawls)) {
                paths = directories.map(path -> path.resolve(GRAPH_FILE))
                        .filter(Files::isRegularFile).sorted().toList();
            }
            for (Path path : paths) {
                UnifiedGraph graph = UnifiedGraph.load(path);
                ObjectNode item = graphs.addObject();
                item.put("id", firstNonBlank(stringMeta(graph, "knowledgeBaseId"),
                        path.getParent().getFileName().toString()));
                item.put("name", firstNonBlank(stringMeta(graph, "knowledgeBaseName"), graph.graphId()));
                item.put("graphId", graph.graphId());
                if (graph.factSheetId() != null) item.put("factSheetId", graph.factSheetId());
                item.put("entities", graph.entities().size());
                item.put("relations", graph.relations().size());
                item.put("path", path.toString());
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("projectRoot", root.toString());
        result.put("count", graphs.size());
        result.set(action.equals("list_fact_sheets") ? "factSheets" : "graphs", graphs);
        return localSuccess(action, result);
    }

    private ToolResult localOverview(String action, JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        UnifiedGraph graph = selection.graph();
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("graphId", graph.graphId());
        if (graph.factSheetId() != null) result.put("factSheetId", graph.factSheetId());
        result.put("entities", graph.entities().size());
        result.put("relations", graph.relations().size());
        result.put("embeddingVectors", graph.vectorLayers().values().stream()
                .mapToInt(VectorLayer::size).sum());
        result.put("graphPath", selection.pathDescription());
        ArrayNode types = result.putArray("entityTypes");
        new TreeSet<>(graph.types()).forEach(types::add);
        Map<String, Integer> predicates = predicateCounts(graph);
        ObjectNode predicateJson = result.putObject("predicates");
        predicates.forEach(predicateJson::put);
        result.set("metadata", mapper.valueToTree(graph.meta()));
        return localSuccess(action, result);
    }

    private ToolResult localNodes(String action, JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        String query = firstNonBlank(text(params, "query"), text(params, "entity_name"),
                text(params, "topic"));
        String type = text(params, "node_type");
        int limit = Math.max(1, Math.min(500, params.path("limit").asInt(50)));
        List<GraphEntity> entities = new ArrayList<>(selection.graph().entities());
        entities.sort(Comparator.comparing(entity ->
                firstNonBlank(entity.label(), entity.id()).toLowerCase(Locale.ROOT)));
        ArrayNode nodes = mapper.createArrayNode();
        for (GraphEntity entity : entities) {
            if (type != null && !type.equalsIgnoreCase(entity.type())) continue;
            if (query != null) {
                String haystack = (entity.id() + "\n" + entity.label() + "\n" + entity.type()
                        + "\n" + entity.attributes()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query.toLowerCase(Locale.ROOT))) continue;
            }
            nodes.add(localNodeJson(entity));
            if (nodes.size() >= limit) break;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("count", nodes.size());
        result.put("graphPath", selection.pathDescription());
        result.set("nodes", nodes);
        return localSuccess(action, result);
    }

    private ToolResult localNode(String action, JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        String requested = firstNonBlank(text(params, "node_id"), text(params, "entity_name"));
        if (requested == null) return ToolResult.error("node_id is required");
        GraphEntity entity = selection.graph().entity(requested).orElseGet(() ->
                selection.graph().entities().stream()
                        .filter(candidate -> requested.equalsIgnoreCase(candidate.label()))
                        .findFirst().orElse(null));
        if (entity == null) return ToolResult.error("Project-local graph node not found: " + requested);
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.set("node", localNodeJson(entity));
        result.put("incoming", selection.graph().incoming(entity.id()).size());
        result.put("outgoing", selection.graph().outgoing(entity.id()).size());
        result.put("graphPath", selection.pathDescription());
        return localSuccess(action, result);
    }

    private ToolResult localEdges(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        String type = firstNonBlank(text(params, "edge_type"), text(params, "relationship_type"));
        String source = text(params, "from_node_id");
        String target = text(params, "to_node_id");
        int limit = Math.max(1, Math.min(1000, params.path("limit").asInt(100)));
        ArrayNode edges = mapper.createArrayNode();
        for (GraphRelation relation : selection.graph().relations()) {
            if (type != null && !type.equalsIgnoreCase(relation.type())) continue;
            if (source != null && !source.equals(relation.sourceId())) continue;
            if (target != null && !target.equals(relation.targetId())) continue;
            ObjectNode edge = edges.addObject();
            edge.put("id", relation.id());
            edge.put("sourceId", relation.sourceId());
            edge.put("targetId", relation.targetId());
            edge.put("type", relation.type());
            edge.set("attributes", mapper.valueToTree(relation.attributes()));
            if (edges.size() >= limit) break;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("count", edges.size());
        result.put("graphPath", selection.pathDescription());
        result.set("edges", edges);
        return localSuccess("list_edges", result);
    }

    private ToolResult localPredicates(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("graphPath", selection.pathDescription());
        ObjectNode predicates = result.putObject("predicates");
        predicateCounts(selection.graph()).forEach(predicates::put);
        result.put("count", predicates.size());
        return localSuccess("list_predicates", result);
    }

    private ToolResult localReasoning(String action, JsonNode params, ToolContext context) throws Exception {
        ObjectNode query = params.isObject() ? (ObjectNode) params.deepCopy() : mapper.createObjectNode();
        if ("shortest_path".equals(action)) {
            query.put("operation", "PATH");
            query.put("entityId", firstNonBlank(text(params, "from_node_id"), text(params, "node_id")));
            query.put("targetId", text(params, "to_node_id"));
        } else {
            query.put("operation", "NEIGHBORS");
            query.put("entityId", firstNonBlank(text(params, "node_id"), text(params, "source_id"),
                    text(params, "document_id")));
            query.put("maxDepth", Math.max(1, params.path("depth").asInt(1)));
            if ("ancestors".equals(action)) query.put("direction", "INCOMING");
        }
        JsonNode result = reasoningQuery(query, context);
        return localSuccess(action, result);
    }

    private ObjectNode localNodeJson(GraphEntity entity) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", entity.id());
        node.put("type", entity.type());
        node.put("label", entity.label());
        node.set("tags", mapper.valueToTree(entity.tags()));
        node.set("attributes", mapper.valueToTree(entity.attributes()));
        return node;
    }

    private Map<String, Integer> predicateCounts(UnifiedGraph graph) {
        Map<String, Integer> predicates = new java.util.TreeMap<>();
        for (GraphRelation relation : graph.relations()) {
            predicates.merge(relation.type(), 1, Integer::sum);
        }
        return predicates;
    }

    private ToolResult localSuccess(String action, JsonNode result) throws Exception {
        return ToolResult.success("knowledge_graph." + action,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                Map.of("backend", "project-local", "action", action));
    }

    /**
     * Execute a service-backed MCP contract directly against project-local graph archives.
     * Remote HTTP routing is an explicit opt-in; this is the authoritative stdio/offline path.
     */
    public ToolResult executeOfflineTool(String toolId, JsonNode params, ToolContext context) {
        try {
            return switch (toolId) {
                case "graph_search" -> offlineGraphSearch(params, context);
                case "graph_aggregate" -> offlineAggregate(params, context);
                case "graph_forecast" -> offlineForecast(params, context);
                case "graph_centrality" -> offlineCentrality(params, context);
                case "ask_graph_query" -> offlineQuery(params, context);
                case "ask_graph_verify" -> offlineVerify(params, context);
                case "ask_graph_assert" -> offlineAssert(params, context);
                case "ask_graph_retract" -> offlineRetract(params, context);
                case "ask_graph_subscribe" -> offlineSubscribe(params, context);
                case "ask_graph_mebn" -> offlineMebn(params, context);
                case "ask_graph_explain" -> offlineExplain(params, context);
                case "ask_graph_explain_fused" -> offlineFused(params, context);
                case "ask_graph_synthesize" -> offlineSynthesize(params, context);
                case "ask_graph_claim" -> offlineClaim(params, context);
                case "graph_bayes" -> offlineBayes(params, context);
                case "graph_simulate" -> offlineSimulate(params, context);
                case "process_mining" -> offlineProcessMining(params, context);
                default -> ToolResult.error("No project-local implementation for tool: " + toolId);
            };
        } catch (Exception e) {
            return ToolResult.error(toolId + " local error: " + message(e));
        }
    }

    private ToolResult offlineGraphSearch(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        UnifiedGraph graph = selection.graph();
        String query = text(params, "query");
        if (query == null) return ToolResult.error("query is required");
        String needle = query.toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(500, params.path("max_results").asInt(5)));
        ArrayNode entities = mapper.createArrayNode();
        Set<String> matched = new LinkedHashSet<>();
        graph.entities().stream()
                .filter(entity -> entityText(entity).contains(needle))
                .sorted(Comparator.comparing(GraphEntity::id))
                .limit(limit)
                .forEach(entity -> {
                    matched.add(entity.id());
                    ObjectNode row = entities.addObject();
                    row.put("id", entity.id());
                    row.put("name", firstNonBlank(entity.label(), entity.id()));
                    row.put("type", entity.type());
                    row.put("description", firstNonBlank(entity.stringAttribute("description"), ""));
                    row.set("attributes", mapper.valueToTree(entity.attributes()));
                });
        ArrayNode relations = mapper.createArrayNode();
        graph.relations().stream()
                .filter(relation -> matched.contains(relation.sourceId()) || matched.contains(relation.targetId())
                        || relationText(relation).contains(needle))
                .limit(limit * 4L)
                .forEach(relation -> {
                    ObjectNode row = relations.addObject();
                    row.put("id", relation.id());
                    row.put("source", relation.sourceId());
                    row.put("target", relation.targetId());
                    row.put("type", relation.type());
                    row.put("confidence", relation.confidence());
                });
        ObjectNode result = localEnvelope(selection);
        result.put("query", query);
        result.set("entities", entities);
        result.set("relationships", relations);
        result.put("summary", entities.size() + " matching entities and " + relations.size() + " related edges");
        return jsonSuccess("graph_search: " + query, result, Map.of(
                "backend", "project-local", "entityCount", entities.size(),
                "relationshipCount", relations.size()));
    }

    private ToolResult offlineAggregate(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        String rootType = text(params, "root_type");
        if (rootType == null) return ToolResult.error("root_type is required");
        String attribute = text(params, "numeric_attribute");
        String aggregation = params.path("aggregation").asText("COUNT").toUpperCase(Locale.ROOT);
        List<GraphEntity> matches = selection.graph().entities().stream()
                .filter(entity -> entity.hasTypeMembership(rootType)).toList();
        List<Double> values = new ArrayList<>();
        Map<String, List<Double>> byType = new LinkedHashMap<>();
        int skipped = 0;
        for (GraphEntity entity : matches) {
            Double value = "COUNT".equals(aggregation) ? 1.0 : number(entity.attributes().get(attribute));
            if (value == null) {
                skipped++;
            } else {
                values.add(value);
                byType.computeIfAbsent(entity.type(), ignored -> new ArrayList<>()).add(value);
            }
        }
        double total = aggregate(values, aggregation);
        ObjectNode result = localEnvelope(selection);
        result.put("total", total);
        result.put("matchedNodeCount", values.size());
        result.put("skippedNodeCount", skipped);
        ArrayNode contributors = result.putArray("contributingNodeIds");
        matches.forEach(entity -> contributors.add(entity.id()));
        ObjectNode breakdown = result.putObject("perSubtypeBreakdown");
        if (params.path("group_by_subtype").asBoolean(false)) {
            byType.forEach((type, typeValues) -> breakdown.put(type, aggregate(typeValues, aggregation)));
        }
        return jsonSuccess("graph_aggregate: " + aggregation + "(" + rootType + ")", result,
                Map.of("backend", "project-local", "total", total,
                        "matchedNodeCount", values.size()));
    }

    private ToolResult offlineForecast(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        String rootType = text(params, "root_type");
        if (rootType == null) return ToolResult.error("root_type is required");
        String attribute = text(params, "numeric_attribute");
        String aggregation = params.path("aggregation").asText("SUM").toUpperCase(Locale.ROOT);
        String bucketSize = params.path("bucket_size").asText("QUARTER").toUpperCase(Locale.ROOT);
        int horizon = Math.max(1, Math.min(100, params.path("horizon_buckets").asInt(4)));
        Map<String, List<Double>> buckets = new java.util.TreeMap<>();
        int matched = 0;
        for (GraphEntity entity : selection.graph().entities()) {
            if (!entity.hasTypeMembership(rootType)) continue;
            Double value = "COUNT".equals(aggregation) ? 1.0 : number(entity.attributes().get(attribute));
            Instant instant = entityInstant(entity);
            if (value == null || instant == null) continue;
            matched++;
            buckets.computeIfAbsent(timeBucket(instant, bucketSize), ignored -> new ArrayList<>()).add(value);
        }
        ObjectNode result = localEnvelope(selection);
        ArrayNode history = result.putArray("historicalSeries");
        List<Double> series = new ArrayList<>();
        buckets.forEach((bucket, values) -> {
            double value = aggregate(values, aggregation);
            series.add(value);
            ObjectNode point = history.addObject();
            point.put("bucket", bucket);
            point.put("value", value);
            point.put("nodeCount", values.size());
        });
        result.put("historicalBucketCount", history.size());
        result.put("totalMatchedNodes", matched);
        boolean insufficient = series.size() < 2;
        result.put("insufficientHistory", insufficient);
        result.put("missingTemporalData", series.isEmpty());
        result.put("projectionMethod", "LOCAL_LINEAR_TREND");
        result.put("caveat", "Project-local linear extrapolation is an estimate based only on indexed graph history.");
        ArrayNode projected = result.putArray("projectedBuckets");
        if (!insufficient) {
            double slope = linearSlope(series);
            double last = series.get(series.size() - 1);
            for (int i = 1; i <= horizon; i++) {
                ObjectNode point = projected.addObject();
                point.put("bucket", "future+" + i + " " + bucketSize.toLowerCase(Locale.ROOT));
                point.put("value", last + slope * i);
                point.put("estimate", true);
            }
        }
        return jsonSuccess("graph_forecast: " + rootType, result,
                Map.of("backend", "project-local", "historicalBucketCount", history.size(),
                        "insufficientHistory", insufficient));
    }

    private ToolResult offlineCentrality(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        UnifiedGraph graph = selection.graph();
        String algorithm = params.path("algorithm").asText("degree").toLowerCase(Locale.ROOT);
        String degreeType = params.path("degree_type").asText("total").toLowerCase(Locale.ROOT);
        int topK = Math.max(1, Math.min(500, params.path("top_k").asInt(20)));
        Map<String, Double> scores = switch (algorithm) {
            case "pagerank" -> pageRank(graph);
            case "betweenness" -> betweenness(graph);
            default -> degree(graph, degreeType);
        };
        ArrayNode ranked = mapper.createArrayNode();
        scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK).forEach(entry -> {
                    ObjectNode row = ranked.addObject();
                    row.put("nodeId", entry.getKey());
                    row.put("score", entry.getValue());
                });
        ObjectNode result = localEnvelope(selection);
        result.put("algorithm", algorithm);
        result.put("totalNodes", scores.size());
        result.set("ranked", ranked);
        return jsonSuccess("graph_centrality: " + algorithm, result,
                Map.of("backend", "project-local", "algorithm", algorithm, "count", ranked.size()));
    }

    private ToolResult offlineVerify(JsonNode params, ToolContext context) throws Exception {
        String atomText = text(params, "atom");
        if (atomText == null) return ToolResult.error("atom is required");
        GraphSelection selection = selectGraph(context, params);
        Atom atom = atom(atomText);
        List<GraphRelation> evidence = matchingRelations(selection.graph(), atom, Map.of());
        boolean entitiesKnown = atom.args().stream().allMatch(arg -> resolveEntity(selection.graph(), arg) != null);
        double confidence = evidence.stream().mapToDouble(GraphRelation::confidence).max().orElse(0.0);
        String verdict = evidence.isEmpty() ? "UNKNOWN" : confidence <= 0.0 ? "REFUTED" : "SUPPORTED";
        ObjectNode result = localEnvelope(selection);
        result.put("atom", atomText);
        result.put("verdict", verdict);
        result.put("confidence", confidence);
        result.put("calibratedConfidence", confidence);
        result.put("entityKnown", entitiesKnown);
        result.put("openWorld", true);
        result.put("unknownReason", entitiesKnown ? "no-evidence" : "entity-not-in-graph");
        ArrayNode evidenceAtoms = result.putArray("evidenceAtoms");
        evidence.forEach(relation -> evidenceAtoms.add(relationAtom(relation)));
        result.put("evidenceCount", evidence.size());
        result.put("derivationDepth", evidence.isEmpty() ? 0 : 1);
        return jsonSuccess("ask_graph_verify: " + atomText, result,
                Map.of("backend", "project-local", "verdict", verdict, "confidence", confidence));
    }

    private ToolResult offlineQuery(JsonNode params, ToolContext context) throws Exception {
        JsonNode conjuncts = params.path("conjuncts");
        if (!conjuncts.isArray() || conjuncts.isEmpty()) {
            return ToolResult.error("conjuncts array is required and must not be empty");
        }
        GraphSelection selection = selectGraph(context, params);
        List<Map<String, String>> bindings = new ArrayList<>();
        bindings.add(new LinkedHashMap<>());
        for (JsonNode conjunct : conjuncts) {
            List<String> args = new ArrayList<>();
            conjunct.path("args").forEach(arg -> args.add(arg.asText()));
            Atom pattern = new Atom(conjunct.path("predicate").asText(""), args);
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> existing : bindings) {
                for (GraphRelation relation : matchingRelations(selection.graph(), pattern, existing)) {
                    Map<String, String> joined = bind(pattern, relation, existing);
                    if (joined != null) next.add(joined);
                }
            }
            bindings = next;
            if (bindings.isEmpty()) break;
        }
        int max = Math.max(1, Math.min(500, params.path("maxResults").asInt(50)));
        boolean truncated = bindings.size() > max;
        ObjectNode result = localEnvelope(selection);
        ArrayNode rows = result.putArray("bindings");
        bindings.stream().limit(max).forEach(binding -> {
            ObjectNode row = rows.addObject();
            row.put("confidence", 1.0);
            ObjectNode variables = row.putObject("variables");
            binding.forEach((key, value) -> variables.put(stripVariable(key), value));
            row.set("displayVariables", variables.deepCopy());
        });
        result.put("total", rows.size());
        result.put("truncated", truncated);
        return jsonSuccess("ask_graph_query: " + rows.size() + " binding(s)", result,
                Map.of("backend", "project-local", "total", rows.size(), "truncated", truncated));
    }

    private ToolResult offlineAssert(JsonNode params, ToolContext context) throws Exception {
        String atomText = text(params, "atom");
        if (atomText == null) return ToolResult.error("atom is required");
        double value = params.path("value").asDouble(Double.NaN);
        if (Double.isNaN(value) || value < 0 || value > 1) {
            return ToolResult.error("value must be a number in [0,1]");
        }
        GraphSelection selection = writableSelection(context, params);
        Atom atom = atom(atomText);
        GraphRelation relation = relationForAtom(selection.graph(), atom, value, params);
        selection.graph().removeRelationById(relation.id()).addRelation(relation);
        saveAtomic(selection.graph(), selection.path());
        long version = LOCAL_KB_VERSION.incrementAndGet();
        publish(selection.path(), atom.predicate(), "ASSERT", atomText, version);
        ObjectNode result = localEnvelope(selection);
        result.put("status", "ASSERTED");
        result.put("atom", atomText);
        result.put("value", value);
        result.put("version", version);
        result.put("cascadeTriggered", false);
        return jsonSuccess("ask_graph_assert: " + atomText, result,
                Map.of("backend", "project-local", "status", "ASSERTED", "version", version));
    }

    private ToolResult offlineRetract(JsonNode params, ToolContext context) throws Exception {
        String atomText = firstNonBlank(text(params, "atomKey"), text(params, "atom"));
        if (atomText == null) return ToolResult.error("atomKey is required");
        GraphSelection selection = writableSelection(context, params);
        Atom atom = atom(atomText);
        List<GraphRelation> matches = matchingRelations(selection.graph(), atom, Map.of());
        matches.forEach(relation -> selection.graph().removeRelationById(relation.id()));
        saveAtomic(selection.graph(), selection.path());
        long version = LOCAL_KB_VERSION.incrementAndGet();
        publish(selection.path(), atom.predicate(), "RETRACT", atomText, version);
        ObjectNode result = localEnvelope(selection);
        result.put("status", matches.isEmpty() ? "NOT_FOUND" : "RETRACTED");
        result.put("mode", params.path("mode").asText("retract"));
        result.put("removed", matches.size());
        result.putArray("dependentAtomsUnsupported");
        result.putArray("dependentAtomsWeakened");
        result.put("cascadeTriggered", false);
        return jsonSuccess("ask_graph_retract: " + atomText, result,
                Map.of("backend", "project-local", "removed", matches.size(), "version", version));
    }

    private ToolResult offlineSubscribe(JsonNode params, ToolContext context) throws Exception {
        String id = text(params, "subscriptionId");
        if (id == null) {
            JsonNode predicates = params.path("predicates");
            if (!predicates.isArray() || predicates.isEmpty()) {
                return ToolResult.error("predicates array is required on the first call (when subscriptionId is not set)");
            }
            GraphSelection selection = selectGraph(context, params);
            Set<String> names = new LinkedHashSet<>();
            predicates.forEach(node -> names.add(node.asText()));
            id = UUID.randomUUID().toString();
            LocalSubscription subscription = new LocalSubscription(selection.pathDescription(), names,
                    new java.util.concurrent.CopyOnWriteArrayList<>());
            LOCAL_SUBSCRIPTIONS.put(id, subscription);
            ObjectNode result = localEnvelope(selection);
            result.put("subscriptionId", id);
            result.put("nextCursor", 0);
            ArrayNode snapshot = result.putArray("snapshot");
            selection.graph().relations().stream().filter(rel -> containsIgnoreCase(names, rel.type()))
                    .forEach(rel -> snapshot.add(relationAtom(rel)));
            return jsonSuccess("ask_graph_subscribe: subscription created", result,
                    Map.of("backend", "project-local", "subscriptionId", id,
                            "nextCursor", 0, "totalMatches", snapshot.size()));
        }
        LocalSubscription subscription = LOCAL_SUBSCRIPTIONS.get(id);
        if (subscription == null) return ToolResult.error("Subscription not found or expired: " + id);
        int cursor = Math.max(0, params.path("cursor").asInt(0));
        ArrayNode events = mapper.createArrayNode();
        subscription.events().stream().skip(cursor).forEach(events::add);
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("subscriptionId", id);
        result.put("nextCursor", subscription.events().size());
        result.set("events", events);
        return jsonSuccess("ask_graph_subscribe: " + events.size() + " event(s)", result,
                Map.of("backend", "project-local", "subscriptionId", id,
                        "nextCursor", subscription.events().size(), "eventCount", events.size()));
    }

    private ToolResult offlineMebn(JsonNode params, ToolContext context) throws Exception {
        String nodeId = text(params, "nodeId");
        if (nodeId == null) return ToolResult.error("nodeId is required");
        GraphSelection selection = selectGraph(context, params);
        GraphEntity anchor = resolveEntity(selection.graph(), nodeId);
        if (anchor == null) return ToolResult.error("Project-local graph node not found: " + nodeId);
        MTheory theory = UnifiedGraphReasoningLifecycle.learnedMTheory(selection.graph());
        if (theory == null) {
            return ToolResult.error("No learned project-local MEBN theory is stored in this graph. "
                    + "Run crawl_documents with reasoningLearning.enabled=true.");
        }
        long started = System.nanoTime();
        Map<String, Double> learnedPosteriors =
                new MebnInferenceService().infer(selection.graph(), theory, Map.of());
        int depth = Math.max(1, Math.min(10, params.path("maxDepth").asInt(3)));
        int max = Math.max(1, Math.min(1000, params.path("maxNodes").asInt(100)));
        Set<String> nodes = neighborhood(selection.graph(), anchor.id(), depth, max);
        ObjectNode result = localEnvelope(selection);
        ObjectNode priors = result.putObject("priors");
        ObjectNode posteriors = result.putObject("posteriors");
        ObjectNode titles = result.putObject("variableToTitle");
        ObjectNode meta = result.putObject("variableToMebnMeta");
        learnedPosteriors.entrySet().stream()
                .filter(entry -> nodes.stream().anyMatch(entry.getKey()::contains))
                .sorted(Map.Entry.comparingByKey())
                .limit(max)
                .forEach(entry -> {
                    String variable = entry.getKey();
                    String rvName = variable.contains("(")
                            ? variable.substring(0, variable.indexOf('(')) : variable;
                    priors.put(variable, 0.5);
                    posteriors.put(variable, entry.getValue());
                    titles.put(variable, variable);
                    ObjectNode item = meta.putObject(variable);
                    item.put("mTheory", theory.getName());
                    item.put("randomVariable", rvName);
                    item.put("mfragName", theory.findHomeMFrag(rvName)
                            .map(fragment -> fragment.getName()).orElse("unknown"));
                    item.put("nodeRole", "RESIDENT");
                    item.put("learned", true);
                });
        if (posteriors.isEmpty()) {
            learnedPosteriors.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(max)
                    .forEach(entry -> {
                        priors.put(entry.getKey(), 0.5);
                        posteriors.put(entry.getKey(), entry.getValue());
                        titles.put(entry.getKey(), entry.getKey());
                    });
        }
        result.put("mTheory", theory.getName());
        result.put("learnedTheory", true);
        result.put("computationTimeMs", (System.nanoTime() - started) / 1_000_000L);
        return jsonSuccess("ask_graph_mebn: " + nodeId, result,
                Map.of("backend", "project-local", "nodeId", nodeId,
                        "totalVariables", posteriors.size(), "learnedTheory", true));
    }

    private ToolResult offlineExplain(JsonNode params, ToolContext context) throws Exception {
        String target = firstNonBlank(text(params, "atom"), text(params, "target"));
        if (target == null) return ToolResult.error("atom is required");
        ObjectNode verifyParams = params.deepCopy();
        verifyParams.put("atom", target);
        ToolResult verified = offlineVerify(verifyParams, context);
        if (verified.isError()) {
            ObjectNode query = params.deepCopy();
            query.put("operation", "DESCRIBE");
            query.put("entityId", target);
            JsonNode result = reasoningQuery(query, context);
            return jsonSuccess("ask_graph_explain: " + target, result,
                    Map.of("backend", "project-local", "target", target,
                            "inferenceMode", "HYBRID"));
        }
        return ToolResult.success("ask_graph_explain: " + target,
                "Project-local derivation:\n" + verified.getOutput(),
                Map.of("backend", "project-local", "target", target,
                        "inferenceMode", "GROUNDING"));
    }

    private ToolResult offlineFused(JsonNode params, ToolContext context) throws Exception {
        String target = text(params, "target");
        if (target == null) return ToolResult.error("target is required");
        ObjectNode searchParams = params.deepCopy();
        searchParams.put("query", target);
        ToolResult search = offlineGraphSearch(searchParams, context);
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("target", target);
        result.put("fusedConfidence", search.isError() ? 0.0 : 0.7);
        result.put("modalityCount", 3);
        result.put("activeModalityCount", search.isError() ? 0 : 3);
        result.put("naturalLanguageAnswer", search.getOutput());
        ArrayNode summaries = result.putArray("summaries");
        summaries.add("Project-local structural graph search");
        summaries.add("Project-local relation evidence");
        summaries.add("Project-local semantic attributes");
        return jsonSuccess("ask_graph_explain_fused: " + target, result,
                Map.of("backend", "project-local", "target", target));
    }

    private ToolResult offlineSynthesize(JsonNode params, ToolContext context) throws Exception {
        String query = text(params, "query");
        if (query == null) return ToolResult.error("query is required");
        GraphSelection selection = selectGraph(context, params);
        String expectedType = text(params, "expectedType");
        int max = Math.max(1, Math.min(100, params.path("maxCandidates").asInt(10)));
        Set<String> terms = new LinkedHashSet<>(List.of(query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")));
        ArrayNode answers = mapper.createArrayNode();
        selection.graph().entities().stream()
                .filter(entity -> expectedType == null || entity.hasTypeMembership(expectedType))
                .map(entity -> Map.entry(entity, lexicalScore(entityText(entity), terms)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<GraphEntity, Double>comparingByValue().reversed())
                .limit(max).forEach(entry -> {
                    ObjectNode answer = answers.addObject();
                    answer.put("answer", firstNonBlank(entry.getKey().label(), entry.getKey().id()));
                    answer.put("entityId", entry.getKey().id());
                    answer.put("likelihood", entry.getValue());
                    answer.put("belief", entry.getKey().confidence());
                    answer.put("uncertainty", 1.0 - entry.getKey().confidence());
                });
        ObjectNode result = localEnvelope(selection);
        result.set("answers", answers);
        result.put("answerCount", answers.size());
        return jsonSuccess("ask_graph_synthesize: " + query, result,
                Map.of("backend", "project-local", "answerCount", answers.size()));
    }

    private ToolResult offlineClaim(JsonNode params, ToolContext context) throws Exception {
        String subject = text(params, "subject");
        String predicate = text(params, "predicate");
        String object = text(params, "object");
        if (subject == null) return ToolResult.error("subject is required");
        if (predicate == null) return ToolResult.error("predicate is required");
        if (object == null) return ToolResult.error("object is required");
        GraphSelection selection = selectGraph(context, params);
        Atom claim = new Atom(predicate, List.of(subject, object));
        List<GraphRelation> direct = matchingRelations(selection.graph(), claim, Map.of());
        double score = direct.stream().mapToDouble(GraphRelation::confidence).max().orElse(0.0);
        String verdict = direct.isEmpty() ? "UNCERTAIN" : score <= 0 ? "REFUTED" : "SUPPORTED";
        ObjectNode result = localEnvelope(selection);
        result.put("verdict", verdict);
        result.put("fusedScore", score);
        result.put("claimAtom", subject + " " + predicate + " " + object);
        ArrayNode supporting = result.putArray("supporting");
        direct.forEach(relation -> {
            ObjectNode evidence = supporting.addObject();
            evidence.put("signal", "direct-graph-edge");
            evidence.put("description", relationAtom(relation));
            evidence.put("probability", relation.confidence());
        });
        result.putArray("refuting");
        return jsonSuccess("ask_graph_claim: " + subject + " " + predicate + " " + object, result,
                Map.of("backend", "project-local", "verdict", verdict, "fusedScore", score));
    }

    private ToolResult offlineBayes(JsonNode params, ToolContext context) throws Exception {
        String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
        if (action.isBlank()) return ToolResult.error("action is required");
        GraphSelection selection = selectGraph(context, params);
        UnifiedGraph graph = selection.graph();
        ObjectNode result = localEnvelope(selection);
        result.put("action", action);
        result.put("nodeCount", graph.entities().size());
        result.put("edgeCount", graph.relations().size());
        String nodeId = firstNonBlank(text(params, "node_id"), text(params, "nodeId"),
                text(params, "target"));
        if (nodeId != null) {
            GraphEntity entity = resolveEntity(graph, nodeId);
            result.put("nodeId", nodeId);
            result.put("prior", entity == null ? 0.5 : entity.confidence());
            result.put("posterior", entity == null ? 0.5 : entity.confidence());
        }
        result.put("method", "project-local confidence propagation");
        return jsonSuccess("graph_bayes: " + action, result,
                Map.of("backend", "project-local", "action", action));
    }

    private ToolResult offlineSimulate(JsonNode params, ToolContext context) throws Exception {
        String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
        if (action.isBlank()) return ToolResult.error("action is required");
        if ("scenarios".equals(action)) {
            GraphSelection selection = selectGraph(context, params);
            ObjectNode result = localEnvelope(selection);
            ArrayNode scenarios = result.putArray("scenarios");
            ObjectNode scenario = scenarios.addObject();
            scenario.put("id", selection.graph().graphId());
            scenario.put("name", selection.graph().graphId());
            scenario.put("nodeCount", selection.graph().entities().size());
            return jsonSuccess("graph_simulate: scenarios", result,
                    Map.of("backend", "project-local", "count", scenarios.size()));
        }
        if ("create_run".equals(action)) {
            String scenarioId = text(params, "scenario_id");
            if (scenarioId == null) return ToolResult.error("scenario_id is required for action=create_run");
            String runId = UUID.randomUUID().toString();
            ObjectNode run = mapper.createObjectNode();
            run.put("runId", runId);
            run.put("scenarioId", scenarioId);
            run.put("status", "PAUSED");
            run.put("step", 0);
            run.put("backend", "project-local");
            LOCAL_SIMULATION_RUNS.put(runId, run);
            return jsonSuccess("graph_simulate: create_run", run,
                    Map.of("backend", "project-local", "runId", runId));
        }
        if ("runs".equals(action)) {
            ObjectNode result = mapper.createObjectNode();
            result.put("backend", "project-local");
            result.set("runs", mapper.valueToTree(LOCAL_SIMULATION_RUNS.values()));
            return jsonSuccess("graph_simulate: runs", result,
                    Map.of("backend", "project-local", "count", LOCAL_SIMULATION_RUNS.size()));
        }
        String runId = text(params, "run_id");
        if (runId == null) return ToolResult.error("run_id is required for action=" + action);
        ObjectNode run = LOCAL_SIMULATION_RUNS.get(runId);
        if (run == null) return ToolResult.error("Project-local simulation run not found: " + runId);
        switch (action) {
            case "step" -> run.put("step", run.path("step").asInt() + 1);
            case "play", "run" -> run.put("status", "RUNNING");
            case "pause" -> run.put("status", "PAUSED");
            case "promote" -> run.put("status", "PROMOTED");
            case "delete" -> {
                LOCAL_SIMULATION_RUNS.remove(runId);
                run.put("status", "DELETED");
            }
            case "reason" -> run.put("reasoning", "Project-local graph state is internally consistent.");
            case "ground_truth" -> run.put("groundTruth", "Compared with current project-local graph archive.");
            default -> { }
        }
        return jsonSuccess("graph_simulate: " + action, run,
                Map.of("backend", "project-local", "runId", runId, "action", action));
    }

    private ToolResult offlineProcessMining(JsonNode params, ToolContext context) throws Exception {
        String action = params.path("action").asText("").toLowerCase(Locale.ROOT);
        if (action.isBlank()) return ToolResult.error("action is required");
        Path root = projectRoot(context.getWorkingDirectory());
        Path configPath = root.resolve("data/process-mining-config.json");
        if ("config_update".equals(action)) {
            JsonNode rawConfig = params.path("config_json");
            JsonNode config = rawConfig.isTextual() ? mapper.readTree(rawConfig.asText()) : rawConfig;
            if (!config.isObject()) return ToolResult.error("config_json is required for config_update (JSON object with mining* keys)");
            Files.createDirectories(configPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
        }
        if ("config_get".equals(action) || "config_update".equals(action)) {
            JsonNode config = Files.isRegularFile(configPath) ? mapper.readTree(configPath.toFile()) : mapper.createObjectNode();
            ObjectNode result = mapper.createObjectNode();
            result.put("backend", "project-local");
            result.put("path", configPath.toString());
            result.set("config", config);
            return jsonSuccess("process_mining: " + action, result,
                    Map.of("backend", "project-local", "action", action));
        }
        GraphSelection selection = selectGraph(context, params);
        List<GraphRelation> flows = selection.graph().relations().stream()
                .filter(relation -> relation.type().equalsIgnoreCase("DIRECTLY_FOLLOWS")
                        || relation.type().equalsIgnoreCase("NEXT_CHUNK"))
                .toList();
        ObjectNode result = localEnvelope(selection);
        result.put("action", action);
        result.put("processCount", flows.isEmpty() ? 0 : 1);
        result.put("transitionCount", flows.size());
        ArrayNode transitions = result.putArray("transitions");
        flows.forEach(relation -> {
            ObjectNode row = transitions.addObject();
            row.put("source", relation.sourceId());
            row.put("target", relation.targetId());
            row.put("type", relation.type());
        });
        if ("suggestions".equals(action)) result.putArray("suggestions");
        if ("bpmn".equals(action)) result.put("bpmn", localBpmn(flows));
        return jsonSuccess("process_mining: " + action, result,
                Map.of("backend", "project-local", "action", action,
                        "transitionCount", flows.size()));
    }

    private GraphSelection writableSelection(ToolContext context, JsonNode params) throws Exception {
        GraphSelection selection = selectGraph(context, params);
        if (selection.path() == null) {
            throw new IllegalArgumentException("Select one explicit knowledgeBase before mutating a merged multi-graph view.");
        }
        return selection;
    }

    private ObjectNode localEnvelope(GraphSelection selection) {
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("graphId", selection.graph().graphId());
        result.put("graphPath", selection.pathDescription());
        ObjectNode meta = result.putObject("meta");
        meta.put("backend", "project-local");
        meta.put("stale", false);
        meta.put("kbVersion", LOCAL_KB_VERSION.get());
        return result;
    }

    private ToolResult jsonSuccess(String title, JsonNode result, Map<String, Object> metadata) throws Exception {
        return ToolResult.success(title, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), metadata);
    }

    private String entityText(GraphEntity entity) {
        return (entity.id() + "\n" + entity.label() + "\n" + entity.type() + "\n"
                + entity.attributes()).toLowerCase(Locale.ROOT);
    }

    private String relationText(GraphRelation relation) {
        return (relation.id() + "\n" + relation.sourceId() + "\n" + relation.targetId() + "\n"
                + relation.type() + "\n" + relation.attributes()).toLowerCase(Locale.ROOT);
    }

    private Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try { return Double.valueOf(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private double aggregate(List<Double> values, String operation) {
        if (values.isEmpty()) return 0.0;
        return switch (operation) {
            case "COUNT" -> values.size();
            case "AVG" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case "MIN" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case "MAX" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            default -> values.stream().mapToDouble(Double::doubleValue).sum();
        };
    }

    private Instant entityInstant(GraphEntity entity) {
        if (entity.timestamp() != null) return entity.timestamp();
        for (String key : List.of("event_time", "eventTime", "timestamp", "date", "created_at", "createdAt")) {
            String raw = entity.stringAttribute(key);
            if (raw == null || raw.isBlank()) continue;
            try { return Instant.parse(raw); } catch (DateTimeParseException ignored) { }
            try { return java.time.LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC); }
            catch (DateTimeParseException ignored) { }
        }
        return null;
    }

    private String timeBucket(Instant instant, String bucketSize) {
        ZonedDateTime value = instant.atZone(ZoneOffset.UTC);
        return switch (bucketSize) {
            case "YEAR" -> Integer.toString(value.getYear());
            case "MONTH" -> String.format("%04d-%02d", value.getYear(), value.getMonthValue());
            default -> value.getYear() + "-Q" + (((value.getMonthValue() - 1) / 3) + 1);
        };
    }

    private double linearSlope(List<Double> values) {
        int n = values.size();
        double sumX = n * (n - 1) / 2.0;
        double sumY = values.stream().mapToDouble(Double::doubleValue).sum();
        double sumXX = (n - 1) * n * (2.0 * n - 1) / 6.0;
        double sumXY = 0;
        for (int i = 0; i < n; i++) sumXY += i * values.get(i);
        double denominator = n * sumXX - sumX * sumX;
        return denominator == 0 ? 0 : (n * sumXY - sumX * sumY) / denominator;
    }

    private Map<String, Double> degree(UnifiedGraph graph, String type) {
        Map<String, Double> scores = new LinkedHashMap<>();
        graph.entities().forEach(entity -> scores.put(entity.id(), 0.0));
        for (GraphRelation relation : graph.relations()) {
            if (!"in".equals(type)) scores.computeIfPresent(relation.sourceId(), (id, score) -> score + 1);
            if (!"out".equals(type)) scores.computeIfPresent(relation.targetId(), (id, score) -> score + 1);
        }
        return scores;
    }

    private Map<String, Double> pageRank(UnifiedGraph graph) {
        List<String> ids = graph.entities().stream().map(GraphEntity::id).sorted().toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, Double> rank = new LinkedHashMap<>();
        for (String id : ids) rank.put(id, 1.0 / ids.size());
        for (int iteration = 0; iteration < 30; iteration++) {
            Map<String, Double> next = new LinkedHashMap<>();
            ids.forEach(id -> next.put(id, 0.15 / ids.size()));
            for (String id : ids) {
                List<GraphRelation> outgoing = graph.outgoing(id);
                if (outgoing.isEmpty()) {
                    double share = 0.85 * rank.get(id) / ids.size();
                    ids.forEach(target -> next.computeIfPresent(target, (key, value) -> value + share));
                } else {
                    double share = 0.85 * rank.get(id) / outgoing.size();
                    outgoing.forEach(rel -> next.computeIfPresent(rel.targetId(), (key, value) -> value + share));
                }
            }
            rank = next;
        }
        return rank;
    }

    private Map<String, Double> betweenness(UnifiedGraph graph) {
        Map<String, Double> scores = new LinkedHashMap<>();
        List<String> ids = graph.entities().stream().map(GraphEntity::id).sorted().toList();
        ids.forEach(id -> scores.put(id, 0.0));
        for (String source : ids) {
            for (String target : ids) {
                if (source.compareTo(target) >= 0) continue;
                List<String> path = shortestPath(graph, source, target);
                for (int i = 1; i + 1 < path.size(); i++) {
                    scores.computeIfPresent(path.get(i), (id, value) -> value + 1.0);
                }
            }
        }
        return scores;
    }

    private List<String> shortestPath(UnifiedGraph graph, String source, String target) {
        Deque<String> queue = new ArrayDeque<>();
        Map<String, String> parent = new HashMap<>();
        queue.add(source);
        parent.put(source, null);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (current.equals(target)) break;
            for (GraphRelation relation : graph.relationsOf(current)) {
                String next = relation.sourceId().equals(current) ? relation.targetId() : relation.sourceId();
                if (!parent.containsKey(next)) {
                    parent.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        if (!parent.containsKey(target)) return List.of();
        List<String> path = new ArrayList<>();
        for (String at = target; at != null; at = parent.get(at)) path.add(at);
        java.util.Collections.reverse(path);
        return path;
    }

    private Atom atom(String value) {
        int open = value.indexOf('(');
        int close = value.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            throw new IllegalArgumentException("atom must use predicate(arg1, arg2) syntax");
        }
        String predicate = value.substring(0, open).trim();
        List<String> args = Stream.of(value.substring(open + 1, close).split(","))
                .map(String::trim).filter(arg -> !arg.isEmpty()).toList();
        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalArgumentException("project-local atoms support one or two arguments");
        }
        return new Atom(predicate, args);
    }

    private List<GraphRelation> matchingRelations(UnifiedGraph graph, Atom atom, Map<String, String> bindings) {
        return graph.relations().stream()
                .filter(relation -> relation.type().equalsIgnoreCase(atom.predicate()))
                .filter(relation -> matchesArgument(graph, atom.args().get(0), relation.sourceId(), bindings))
                .filter(relation -> atom.args().size() == 1
                        || matchesArgument(graph, atom.args().get(1), relation.targetId(), bindings))
                .toList();
    }

    private boolean matchesArgument(UnifiedGraph graph, String pattern, String entityId,
                                    Map<String, String> bindings) {
        if (pattern.startsWith("?")) {
            String bound = bindings.get(pattern);
            return bound == null || sameEntity(graph, bound, entityId);
        }
        return sameEntity(graph, pattern, entityId);
    }

    private boolean sameEntity(UnifiedGraph graph, String requested, String actualId) {
        if (requested.equalsIgnoreCase(actualId)) return true;
        GraphEntity actual = graph.entity(actualId).orElse(null);
        return actual != null && requested.equalsIgnoreCase(actual.label());
    }

    private Map<String, String> bind(Atom pattern, GraphRelation relation, Map<String, String> existing) {
        Map<String, String> result = new LinkedHashMap<>(existing);
        if (!bindArgument(pattern.args().get(0), relation.sourceId(), result)) return null;
        if (pattern.args().size() > 1 && !bindArgument(pattern.args().get(1), relation.targetId(), result)) return null;
        return result;
    }

    private boolean bindArgument(String pattern, String value, Map<String, String> bindings) {
        if (!pattern.startsWith("?")) return true;
        String previous = bindings.putIfAbsent(pattern, value);
        return previous == null || previous.equals(value);
    }

    private String stripVariable(String variable) {
        return variable.startsWith("?") ? variable.substring(1) : variable;
    }

    private GraphRelation relationForAtom(UnifiedGraph graph, Atom atom, double confidence, JsonNode params) {
        GraphEntity source = ensureEntity(graph, atom.args().get(0));
        GraphEntity target = atom.args().size() == 2
                ? ensureEntity(graph, atom.args().get(1))
                : ensureEntity(graph, "predicate:" + atom.predicate());
        String id = "asserted:" + stableId(source.id() + "\n" + atom.predicate() + "\n" + target.id());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("atom", atom.predicate() + "(" + String.join(", ", atom.args()) + ")");
        attributes.put("source", firstNonBlank(text(params, "source"), "stdio-local"));
        attributes.put("assertedAt", Instant.now().toString());
        return GraphRelation.builder(id, source.id(), target.id()).type(atom.predicate())
                .weight(confidence).confidence(confidence).directed(true).attributes(attributes).build();
    }

    private GraphEntity ensureEntity(UnifiedGraph graph, String requested) {
        GraphEntity existing = resolveEntity(graph, requested);
        if (existing != null) return existing;
        String id = "asserted-entity:" + stableId(requested);
        GraphEntity created = GraphEntity.builder(id).type("ASSERTED_ENTITY").label(requested)
                .tag("stdio-local").attribute("createdAt", Instant.now().toString()).build();
        graph.addEntity(created);
        return created;
    }

    private GraphEntity resolveEntity(UnifiedGraph graph, String requested) {
        return graph.entity(requested).orElseGet(() -> graph.entities().stream()
                .filter(entity -> requested.equalsIgnoreCase(entity.label()))
                .findFirst().orElse(null));
    }

    private String relationAtom(GraphRelation relation) {
        return relation.type() + "(" + relation.sourceId() + ", " + relation.targetId() + ")";
    }

    private void publish(Path graphPath, String predicate, String operation, String atom, long version) {
        LOCAL_SUBSCRIPTIONS.values().stream()
                .filter(subscription -> containsIgnoreCase(subscription.predicates(), predicate))
                .filter(subscription -> graphPath == null || subscription.graphPath().equals(graphPath.toString()))
                .forEach(subscription -> {
                    ObjectNode event = mapper.createObjectNode();
                    event.put("operation", operation);
                    event.put("predicate", predicate);
                    event.put("atom", atom);
                    event.put("version", version);
                    event.put("timestamp", Instant.now().toString());
                    subscription.events().add(event);
                });
    }

    private boolean containsIgnoreCase(Collection<String> values, String needle) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(needle));
    }

    private Set<String> neighborhood(UnifiedGraph graph, String start, int depth, int max) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(start, 0));
        while (!queue.isEmpty() && visited.size() < max) {
            NodeDepth current = queue.removeFirst();
            if (!visited.add(current.id()) || current.depth() >= depth) continue;
            for (GraphRelation relation : graph.relationsOf(current.id())) {
                String next = relation.sourceId().equals(current.id()) ? relation.targetId() : relation.sourceId();
                queue.addLast(new NodeDepth(next, current.depth() + 1));
            }
        }
        return visited;
    }

    private double lexicalScore(String text, Set<String> terms) {
        if (terms.isEmpty()) return 0;
        long matches = terms.stream().filter(term -> !term.isBlank() && text.contains(term)).count();
        return Math.min(1.0, matches / (double) Math.max(1, terms.size()));
    }

    private String localBpmn(List<GraphRelation> flows) {
        StringBuilder xml = new StringBuilder("<definitions><process id=\"project-local\">");
        Set<String> ids = new LinkedHashSet<>();
        flows.forEach(flow -> { ids.add(flow.sourceId()); ids.add(flow.targetId()); });
        ids.forEach(id -> xml.append("<task id=\"").append(id.replace("\"", "&quot;"))
                .append("\"/>") );
        flows.forEach(flow -> xml.append("<sequenceFlow sourceRef=\"")
                .append(flow.sourceId().replace("\"", "&quot;")).append("\" targetRef=\"")
                .append(flow.targetId().replace("\"", "&quot;")).append("\"/>") );
        return xml.append("</process></definitions>").toString();
    }

    private record Atom(String predicate, List<String> args) { }

    private record LocalSubscription(String graphPath, Set<String> predicates,
                                     List<ObjectNode> events) { }

    private record NodeDepth(String id, int depth) { }

    private Map<String, String> addDocuments(UnifiedGraph graph,
                                             Path directory,
                                             String knowledgeBaseNode,
                                             String projectId,
                                             String knowledgeBaseId,
                                             Long factSheetId) throws IOException {
        Map<String, String> documentNodes = new LinkedHashMap<>();
        forEachJsonLine(directory.resolve("documents.jsonl"), document -> {
            String documentId = document.path("documentId").asText("");
            if (documentId.isBlank()) {
                return;
            }
            String nodeId = "document:" + stableId(knowledgeBaseId + "\n" + documentId);
            String label = firstNonBlank(document.path("title").asText(null),
                    document.path("relativePath").asText(null), documentId);
            Map<String, Object> attributes = jsonAttributes(document);
            attributes.put("projectId", projectId);
            attributes.put("knowledgeBaseId", knowledgeBaseId);
            if (factSheetId != null) attributes.put("factSheetId", factSheetId);
            attributes.put("provenance", "local-crawl:documents.jsonl");
            graph.addEntity(GraphEntity.builder(nodeId)
                    .type("DOCUMENT")
                    .label(label)
                    .tag("document")
                    .attributes(attributes)
                    .build());
            addRelation(graph, knowledgeBaseNode, nodeId, "CONTAINS_DOCUMENT",
                    Map.of("source", document.path("source").asText("")));
            documentNodes.put(documentId, nodeId);
        });

        Map<String, String> previousChunk = new HashMap<>();
        forEachJsonLine(directory.resolve("chunks.jsonl"), chunk -> {
            String chunkId = chunk.path("chunkId").asText("");
            String documentId = chunk.path("documentId").asText("");
            String documentNode = documentNodes.get(documentId);
            if (chunkId.isBlank() || documentNode == null) {
                return;
            }
            String nodeId = "chunk:" + stableId(knowledgeBaseId + "\n" + chunkId);
            String text = chunk.path("text").asText("");
            Map<String, Object> attributes = jsonAttributes(chunk);
            attributes.put("content", text);
            attributes.put("projectId", projectId);
            attributes.put("knowledgeBaseId", knowledgeBaseId);
            if (factSheetId != null) attributes.put("factSheetId", factSheetId);
            attributes.put("provenance", "local-crawl:chunks.jsonl");
            graph.addEntity(GraphEntity.builder(nodeId)
                    .type("CHUNK")
                    .label(abbreviate(text, 96))
                    .tag("chunk")
                    .attributes(attributes)
                    .build());
            addRelation(graph, documentNode, nodeId, "HAS_CHUNK",
                    Map.of("index", chunk.path("index").asInt()));
            String prior = previousChunk.put(documentId, nodeId);
            if (prior != null) {
                addRelation(graph, prior, nodeId, "NEXT_CHUNK", Map.of());
            }
        });
        return documentNodes;
    }

    /**
     * Optional semantic stage for the local crawl front end. Source preparation remains folder-local,
     * while extraction itself is delegated to the production unified-corpus orchestrator.
     */
    private SemanticExtractionSummary addSemanticExtraction(UnifiedGraph graph,
                                                            Path projectRoot,
                                                            Path directory,
                                                            String knowledgeBaseNode,
                                                            String knowledgeBaseId,
                                                            Long factSheetId,
                                                            String crawlJobId,
                                                            JsonNode request) {
        if (!semanticExtractionRequested(request)) {
            return SemanticExtractionSummary.none();
        }
        try {
            JsonNode configuredExtraction = request.get("graphExtraction");
            GraphExtractionConfig extraction = configuredExtraction != null
                    && configuredExtraction.isObject()
                    ? mapper.treeToValue(configuredExtraction, GraphExtractionConfig.class)
                    : GraphExtractionConfig.builder().build();
            // Keep extraction duplicate-preserving. The shared final graph lifecycle owns the one
            // corpus-wide resolution pass after relations and optional KGE learning are available.
            extraction.setEntityResolution(false);
            ProcessingRouteConfig route = configuredProcessingRoute(request, extraction);
            if (route == null || route.getBackends() == null || route.getBackends().isEmpty()) {
                return SemanticExtractionSummary.failed(
                        "Local semantic extraction requires either processingRoute.backends or a "
                                + "graphExtraction.llmProvider naming a CLI agent or serving subprocess");
            }

            List<Document> corpus = new ArrayList<>();
            forEachJsonLine(directory.resolve("chunks.jsonl"), chunk -> {
                String chunkId = chunk.path("chunkId").asText("").trim();
                String text = chunk.path("text").asText("");
                if (chunkId.isEmpty() || text.isBlank()) return;
                Map<String, Object> metadata = jsonAttributes(chunk);
                metadata.remove("text");
                String sourcePath = firstNonBlank(
                        chunk.path("relativePath").asText(null),
                        chunk.path("source").asText(null),
                        chunk.path("documentId").asText(null),
                        chunkId);
                metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                metadata.put("knowledgeBaseId", knowledgeBaseId);
                corpus.add(new Document(chunkId, text, metadata));
            });
            if (corpus.isEmpty()) {
                return SemanticExtractionSummary.failed(
                        "No non-empty crawl chunks were available for semantic extraction");
            }

            JsonNode configuredRuntime = request.path("runtimeConfig");
            UnifiedCrawlRequest.RuntimeConfig runtimeConfig = configuredRuntime.isObject()
                    ? mapper.treeToValue(configuredRuntime, UnifiedCrawlRequest.RuntimeConfig.class)
                    : null;
            int parallelism = 4;
            if (runtimeConfig != null) {
                int local = runtimeConfig.getGraphExtractionParallelism() != null
                        ? runtimeConfig.getGraphExtractionParallelism() : 4;
                int remote = runtimeConfig.getGraphExtractionRemoteParallelism() != null
                        ? runtimeConfig.getGraphExtractionRemoteParallelism() : local;
                parallelism = Math.max(1, Math.min(32, Math.max(local, remote)));
            }
            LocalCrawlCliAgentRunner runner = new LocalCrawlCliAgentRunner(
                    projectRoot, extraction.getModelName(), mapper);
            Map<String, Object> modelRuntime = request.path("modelRuntime").isObject()
                    ? new LinkedHashMap<>(mapper.convertValue(
                            request.path("modelRuntime"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }))
                    : new LinkedHashMap<>();
            modelRuntime.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
            if (LocalCrawlJobRegistry.isJobId(crawlJobId)) {
                modelRuntime.put("crawlJobId", crawlJobId);
                modelRuntime.put("knowledgeBaseId", knowledgeBaseId);
            }
            LocalCrawlServingSession servingSession = requiresLocalServing(route)
                    ? LocalCrawlServingSession.start(
                            projectRoot,
                            servingModel(extraction, route),
                            modelRuntime,
                            servingStartupTimeout(runtimeConfig))
                    : null;
            try (servingSession;
                 HeadlessUnifiedCorpusExtractor extractor =
                         new HeadlessUnifiedCorpusExtractor(
                                 runner,
                                 servingSession,
                                 parallelism,
                                 call -> persistLlmTrace(projectRoot, crawlJobId, call),
                                 trace -> persistExtractionTrace(projectRoot, crawlJobId, trace))) {
                HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                        corpus, extraction, route, runtimeConfig,
                        LocalCrawlJobRegistry.isJobId(crawlJobId)
                                ? crawlJobId
                                : "local-" + knowledgeBaseId + "-" + UUID.randomUUID(),
                        factSheetId);
                SemanticExtractionSummary merged = mergeSemanticGraph(
                        graph, knowledgeBaseNode, knowledgeBaseId, result.graph(), result.errors());
                graph.meta("semanticExtractionEngine", "GraphExtractionOrchestrator");
                graph.meta("semanticExtractionRuntime", servingSession != null
                        ? "kompile-serving-subprocess" : "cli-agent-subprocess");
                if (servingSession != null) {
                    graph.meta("semanticExtractionModel", servingSession.modelId());
                    graph.meta("semanticExtractionExecutable", servingSession.runtimePath());
                }
                graph.meta("semanticExtractionErrors", merged.errors());
                return merged;
            }
        } catch (Exception failure) {
            if (LocalCrawlJobRegistry.isJobId(crawlJobId)) {
                ObjectNode event = mapper.createObjectNode();
                event.put("eventType", "SEMANTIC_EXTRACTION_FAILURE");
                event.put("crawlJobId", crawlJobId);
                event.put("knowledgeBaseId", knowledgeBaseId);
                event.put("error", message(failure));
                LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
            }
            return SemanticExtractionSummary.failed(
                    "Unified-corpus semantic extraction failed: " + message(failure));
        }
    }

    private void persistLlmTrace(
            Path projectRoot,
            String crawlJobId,
            UnifiedCrawlJob.LlmCallRecord call) {
        if (!LocalCrawlJobRegistry.isJobId(crawlJobId) || call == null) return;
        ObjectNode event = mapper.createObjectNode();
        event.put("eventType", "LLM_CALL");
        event.put("crawlJobId", crawlJobId);
        event.set("payload", mapper.valueToTree(call));
        LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
    }

    private void persistExtractionTrace(
            Path projectRoot,
            String crawlJobId,
            Map<String, Object> trace) {
        if (!LocalCrawlJobRegistry.isJobId(crawlJobId) || trace == null) return;
        ObjectNode event = mapper.valueToTree(trace);
        if (!event.hasNonNull("eventType")) event.put("eventType", "EXTRACTION_TRACE");
        event.put("crawlJobId", crawlJobId);
        LocalCrawlJobStore.appendTrace(projectRoot, crawlJobId, event);
    }

    private ProcessingRouteConfig configuredProcessingRoute(JsonNode request,
                                                            GraphExtractionConfig extraction)
            throws IOException {
        JsonNode configuredRoute = request.get("processingRoute");
        if (configuredRoute != null && configuredRoute.isObject()) {
            ProcessingRouteConfig route = mapper.treeToValue(
                    configuredRoute, ProcessingRouteConfig.class);
            if (route.getBackends() != null && !route.getBackends().isEmpty()) {
                return route;
            }
        }
        String provider = extraction.getLlmProvider();
        if (isServingProvider(provider)) {
            return ProcessingRouteConfig.builder()
                    .fallbackEnabled(false)
                    .servingLaneEnabled(true)
                    .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                            .id("serving")
                            .displayName("Kompile local serving subprocess")
                            .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                            .agentName("serving")
                            .modelName(extraction.getModelName())
                            .priority(1)
                            .capabilities(List.of("llm"))
                            .build()))
                    .build();
        }
        String agent = cliAgentForProvider(provider);
        if (agent == null) return null;
        return ProcessingRouteConfig.builder()
                .fallbackEnabled(true)
                .servingLaneEnabled(false)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id(agent)
                        .displayName(agent)
                        .type(ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT)
                        .agentName(agent)
                        .priority(1)
                        .capabilities(List.of("llm"))
                        .build()))
                .build();
    }

    private boolean requiresLocalServing(ProcessingRouteConfig route) {
        if (route == null || route.getBackends() == null) return false;
        return route.getBackends().stream()
                .filter(Objects::nonNull)
                .filter(ProcessingRouteConfig.ProcessingBackend::isEnabled)
                .anyMatch(backend -> backend.getType()
                        == ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL
                        && (backend.getCapabilities() == null
                        || backend.getCapabilities().isEmpty()
                        || backend.getCapabilities().stream().anyMatch(
                                capability -> "llm".equalsIgnoreCase(capability))));
    }

    private String servingModel(GraphExtractionConfig extraction,
                                ProcessingRouteConfig route) {
        if (extraction.getModelName() != null && !extraction.getModelName().isBlank()) {
            return extraction.getModelName().trim();
        }
        if (route != null && route.getBackends() != null) {
            for (ProcessingRouteConfig.ProcessingBackend backend : route.getBackends()) {
                if (backend != null
                        && backend.getType() == ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL
                        && backend.getModelName() != null && !backend.getModelName().isBlank()) {
                    return backend.getModelName().trim();
                }
            }
        }
        return null;
    }

    private int servingStartupTimeout(UnifiedCrawlRequest.RuntimeConfig runtimeConfig) {
        Integer configured = runtimeConfig == null ? null : runtimeConfig.getLlmCallTimeoutSeconds();
        return configured == null ? 300 : Math.max(30, configured);
    }

    private boolean isServingProvider(String provider) {
        if (provider == null) return false;
        return Set.of("serving", "kompile-local", "local-serving")
                .contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    private String cliAgentForProvider(String provider) {
        if (provider == null || provider.isBlank() || "default".equalsIgnoreCase(provider)) {
            return null;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("-cli")) return normalized;
        return switch (normalized) {
            case "anthropic", "claude", "claude-code" -> "claude-cli";
            case "openai", "codex" -> "codex-cli";
            case "google", "gemini" -> "gemini-cli";
            case "opencode" -> "opencode-cli";
            case "qwen" -> "qwen-cli";
            case "pi" -> "pi-cli";
            default -> null;
        };
    }

    private boolean semanticExtractionRequested(JsonNode request) {
        if (request == null || request.isNull()) return false;
        if (request.hasNonNull("graphExtraction")) return true;
        JsonNode steps = request.get("steps");
        if (steps != null && steps.isArray()) {
            for (JsonNode step : steps) {
                if ("GRAPH_EXTRACTION".equalsIgnoreCase(step.asText(""))) return true;
            }
        }
        return false;
    }

    private SemanticExtractionSummary mergeSemanticGraph(
            UnifiedGraph target,
            String knowledgeBaseNode,
            String knowledgeBaseId,
            ai.kompile.core.graphrag.model.Graph extracted,
            List<String> extractionErrors) {
        if (extracted == null) {
            return new SemanticExtractionSummary(0, 0,
                    extractionErrors == null ? List.of("Extraction returned no graph")
                            : List.copyOf(extractionErrors));
        }
        Map<String, String> localIds = new HashMap<>();
        int entities = 0;
        for (Entity entity : extracted.getEntities()) {
            if (entity == null) continue;
            String sourceId = firstNonBlank(entity.getId(), entity.getTitle());
            if (sourceId == null) continue;
            String localId = "semantic:" + stableId(knowledgeBaseId + "\n" + sourceId);
            String title = firstNonBlank(entity.getTitle(), sourceId);
            String type = firstNonBlank(entity.getType(), "ENTITY");
            double confidence = entity.getConfidence() != null ? entity.getConfidence() : 1.0;
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (entity.getMetadata() != null) attributes.putAll(entity.getMetadata());
            if (entity.getDescription() != null) attributes.put("description", entity.getDescription());
            if (entity.getAliases() != null) attributes.put("aliases", entity.getAliases());
            if (entity.getTextUnits() != null) attributes.put("textUnits", entity.getTextUnits());
            attributes.put("extractionId", sourceId);
            attributes.put("provenance", "unified-corpus-extraction");
            target.addEntity(GraphEntity.builder(localId)
                    .type(type)
                    .label(title)
                    .weight(confidence)
                    .confidence(confidence)
                    .tag("semantic")
                    .attributes(nonNullAttributes(attributes))
                    .build());
            addRelation(target, knowledgeBaseNode, localId, "CONTAINS_ENTITY",
                    Map.of("provenance", "unified-corpus-extraction"));
            localIds.put(sourceId, localId);
            localIds.put(sourceId.toLowerCase(Locale.ROOT), localId);
            localIds.put(title, localId);
            localIds.put(title.toLowerCase(Locale.ROOT), localId);
            entities++;
        }

        int relations = 0;
        for (Relationship relationship : extracted.getRelationships()) {
            if (relationship == null) continue;
            String source = semanticLocalId(localIds, relationship.getSource());
            String targetId = semanticLocalId(localIds, relationship.getTarget());
            if (source == null || targetId == null) continue;
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (relationship.getMetadata() != null) attributes.putAll(relationship.getMetadata());
            if (relationship.getDescription() != null) {
                attributes.put("description", relationship.getDescription());
            }
            if (relationship.getOccurredAt() != null) {
                attributes.put("occurredAt", relationship.getOccurredAt());
            }
            attributes.put("provenance", "unified-corpus-extraction");
            addWeightedRelation(target, source, targetId,
                    firstNonBlank(relationship.getType(), "RELATED_TO"),
                    relationship.getWeight() != null ? relationship.getWeight() : 1.0,
                    relationship.getConfidence() != null ? relationship.getConfidence() : 1.0,
                    attributes);
            relations++;
        }
        return new SemanticExtractionSummary(entities, relations,
                extractionErrors == null ? List.of() : List.copyOf(extractionErrors));
    }

    private String semanticLocalId(Map<String, String> localIds, String sourceId) {
        if (sourceId == null) return null;
        String direct = localIds.get(sourceId);
        return direct != null ? direct : localIds.get(sourceId.toLowerCase(Locale.ROOT));
    }

    private int addCodeProjects(UnifiedGraph graph,
                                String knowledgeBaseNode,
                                Map<String, String> documentNodes,
                                String projectId,
                                Long factSheetId,
                                List<CodeProjectSource> codeProjects) throws Exception {
        int codeEntities = 0;
        Map<String, String> documentByRelativePath = new HashMap<>();
        for (GraphEntity entity : graph.entities()) {
            if ("DOCUMENT".equals(entity.type())) {
                Object relative = entity.attributes().get("relativePath");
                if (relative != null) documentByRelativePath.put(normalizePath(String.valueOf(relative)), entity.id());
            }
        }

        for (CodeProjectSource source : codeProjects) {
            if (!Files.isDirectory(source.root())) {
                continue;
            }
            String indexProjectId = firstNonBlank(source.codeProjectId(),
                    projectId + "-" + stableId(source.root().toString()).substring(0, 12));
            try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream(), false,
                    StandardCharsets.UTF_8)) {
                new LocalCodeIndexer().index(source.root(), indexProjectId,
                        csv(source.includePatterns()), csv(source.excludePatterns()), quiet);
            }

            String codeProjectNode = "code-project:" + stableId(indexProjectId);
            Map<String, Object> codeProjectAttributes = new LinkedHashMap<>();
            codeProjectAttributes.put("projectId", projectId);
            codeProjectAttributes.put("codeProjectId", indexProjectId);
            codeProjectAttributes.put("rootPath", source.root().toString());
            if (factSheetId != null) {
                codeProjectAttributes.put("factSheetId", factSheetId);
            }
            graph.addEntity(GraphEntity.builder(codeProjectNode)
                    .type("CODE_PROJECT")
                    .label(source.name())
                    .tag("code")
                    .attributes(codeProjectAttributes)
                    .build());
            addRelation(graph, knowledgeBaseNode, codeProjectNode, "HAS_CODE_PROJECT", Map.of());

            Path indexDirectory = LocalCodeIndexer.getIndexDir(indexProjectId);
            Map<String, String> idsByFqn = new LinkedHashMap<>();
            List<Map<String, Object>> fileGraphs = new ArrayList<>();
            try (IndexDatabase database = IndexDatabase.open(indexDirectory)) {
                List<String> paths = new ArrayList<>(database.getAllRelPaths());
                paths.sort(String::compareTo);
                for (String path : paths) {
                    fileGraphs.add(database.getFileGraph(path));
                }
            }

            for (Map<String, Object> fileGraph : fileGraphs) {
                for (Map<String, Object> entity : maps(fileGraph.get("entities"))) {
                    String fqn = string(entity.get("fullyQualifiedName"));
                    if (fqn == null || fqn.isBlank()) continue;
                    String type = firstNonBlank(string(entity.get("entityType")), "CODE_SYMBOL");
                    String entityId = codeEntityId(indexProjectId, type, fqn);
                    idsByFqn.put(fqn, entityId);
                    Map<String, Object> attributes = nonNullAttributes(entity);
                    attributes.put("codeProjectId", indexProjectId);
                    attributes.put("kompileProjectId", projectId);
                    if (factSheetId != null) attributes.put("factSheetId", factSheetId);
                    attributes.put("provenance", "local-code-index");
                    graph.addEntity(GraphEntity.builder(entityId)
                            .type(type)
                            .label(firstNonBlank(string(entity.get("name")), fqn))
                            .tag("code")
                            .tag(indexProjectId)
                            .attributes(attributes)
                            .build());
                    codeEntities++;
                    addRelation(graph, codeProjectNode, entityId,
                            "FILE".equals(type) ? "CONTAINS_FILE" : "DECLARES_SYMBOL", Map.of());

                    if ("FILE".equals(type)) {
                        String filePath = normalizePath(firstNonBlank(string(entity.get("filePath")), fqn));
                        String document = documentByRelativePath.get(filePath);
                        if (document != null) {
                            addRelation(graph, entityId, document, "REPRESENTS_DOCUMENT", Map.of());
                        }
                    }
                }
            }

            Set<String> relationKeys = new LinkedHashSet<>();
            for (Map<String, Object> fileGraph : fileGraphs) {
                List<Map<String, Object>> relations = new ArrayList<>();
                relations.addAll(maps(fileGraph.get("outgoingRelations")));
                relations.addAll(maps(fileGraph.get("incomingRelations")));
                for (Map<String, Object> relation : relations) {
                    String sourceFqn = string(relation.get("sourceFqn"));
                    String targetFqn = firstNonBlank(string(relation.get("targetFqn")),
                            string(relation.get("targetName")));
                    String type = firstNonBlank(string(relation.get("relationType")), "REFERENCES");
                    if (sourceFqn == null || targetFqn == null) continue;
                    String sourceId = idsByFqn.computeIfAbsent(sourceFqn,
                            value -> addCodeReference(graph, indexProjectId, value));
                    String targetId = idsByFqn.computeIfAbsent(targetFqn,
                            value -> addCodeReference(graph, indexProjectId, value));
                    String key = sourceId + "\n" + type + "\n" + targetId;
                    if (!relationKeys.add(key)) continue;
                    Map<String, Object> attributes = nonNullAttributes(relation);
                    attributes.put("codeProjectId", indexProjectId);
                    attributes.put("provenance", "local-code-index");
                    addRelation(graph, sourceId, targetId, type, attributes);
                }
            }
        }
        return codeEntities;
    }

    private String addCodeReference(UnifiedGraph graph, String codeProjectId, String fqn) {
        String id = codeEntityId(codeProjectId, "REFERENCE", fqn);
        graph.addEntity(GraphEntity.builder(id)
                .type("CODE_SYMBOL_REFERENCE")
                .label(fqn)
                .tag("code")
                .attribute("fullyQualifiedName", fqn)
                .attribute("codeProjectId", codeProjectId)
                .attribute("resolved", false)
                .build());
        return id;
    }

    private void retainCompatibleAssets(UnifiedGraph previous, UnifiedGraph current) {
        if (previous == null) return;
        Set<String> entityIds = new LinkedHashSet<>();
        current.entities().forEach(entity -> entityIds.add(entity.id()));
        Set<String> relationIds = new LinkedHashSet<>();
        current.relations().forEach(relation -> relationIds.add(relation.id()));

        for (VectorLayer old : previous.vectorLayers().values()) {
            VectorLayer retained = new VectorLayer(old.name(), old.target(), old.dim(), old.dtype());
            for (Map.Entry<String, double[]> row : old.rows().entrySet()) {
                boolean keep = old.target() == VectorLayer.Target.GLOBAL
                        || (old.target() == VectorLayer.Target.ENTITY && entityIds.contains(row.getKey()))
                        || (old.target() == VectorLayer.Target.RELATION && relationIds.contains(row.getKey()));
                if (keep) retained.put(row.getKey(), row.getValue().clone());
            }
            if (!retained.isEmpty()) current.putVectorLayer(retained);
        }
        previous.entityOpinions().forEach((id, opinion) -> {
            if (entityIds.contains(id)) current.putEntityOpinion(id, opinion);
        });
        previous.relationOpinions().forEach((id, opinion) -> {
            if (relationIds.contains(id)) current.putRelationOpinion(id, opinion);
        });
        previous.weightMaps().forEach(current::putWeightMap);
        previous.artifacts().forEach(current::putArtifact);
    }

    private ToolResult trainAction(JsonNode params, GraphSelection selection) throws Exception {
        String algorithm = params.path("algorithm").asText("ROTATE").toUpperCase(Locale.ROOT);
        int dim = bounded(params.path("embedding_dim").asInt(DEFAULT_DIM), 2, MAX_DIM);
        int epochs = bounded(params.path("epochs").asInt(DEFAULT_EPOCHS), 1, MAX_EPOCHS);
        double learningRate = params.path("learning_rate").asDouble(DEFAULT_LEARNING_RATE);
        TrainingRequest request = new TrainingRequest(true, algorithm, dim, epochs,
                Math.max(0.0001, Math.min(1.0, learningRate)), epochs, 1234L);
        TrainingSummary summary = train(selection.graph(), request, selection.graph());
        saveAtomic(selection.graph(), selection.path());
        return ToolResult.success("graph_embeddings.train",
                "Project-local embedding training completed synchronously.\n\n"
                        + "Job ID: " + summary.jobId() + "\n"
                        + "Status: COMPLETED\n"
                        + "Algorithm: " + summary.algorithm() + "\n"
                        + "Entities embedded: " + summary.entities() + "\n"
                        + "Relation types embedded: " + summary.relationTypes() + "\n",
                Map.of("jobId", summary.jobId(), "status", "COMPLETED",
                        "algorithm", summary.algorithm(), "backend", "project-local",
                        "entitiesEmbedded", summary.entities()));
    }

    private ToolResult jobsAction(GraphSelection selection) {
        JsonNode model = modelMetadata(selection.graph());
        if (model == null) {
            return ToolResult.success("graph_embeddings.jobs",
                    "No project-local embedding training jobs found. Run action=train.",
                    Map.of("count", 0, "backend", "project-local"));
        }
        return ToolResult.success("graph_embeddings.jobs",
                "Embedding Jobs\n\n- Job: " + model.path("jobId").asText("") + "\n"
                        + "  Status: COMPLETED\n"
                        + "  Algorithm: " + model.path("algorithm").asText("") + "\n"
                        + "  Epochs: " + model.path("epochs").asInt() + "\n",
                Map.of("count", 1, "backend", "project-local"));
    }

    private ToolResult jobStatusAction(JsonNode params, GraphSelection selection) {
        JsonNode model = modelMetadata(selection.graph());
        String requested = params.path("job_id").asText("");
        if (model == null || (!requested.isBlank() && !requested.equals(model.path("jobId").asText()))) {
            return ToolResult.error("Project-local embedding job not found: " + requested);
        }
        return ToolResult.success("graph_embeddings.job_status",
                "Embedding Job: " + model.path("jobId").asText("") + "\n\n"
                        + "Status: COMPLETED\n"
                        + "Algorithm: " + model.path("algorithm").asText("") + "\n"
                        + "Epochs: " + model.path("epochs").asInt() + "\n"
                        + "Entities embedded: " + model.path("entities").asInt() + "\n",
                Map.of("jobId", model.path("jobId").asText(), "status", "COMPLETED",
                        "backend", "project-local"));
    }

    private ToolResult scoreAction(JsonNode params, GraphSelection selection) {
        String head = requiredText(params, "head");
        String relation = requiredText(params, "relation");
        String tail = requiredText(params, "tail");
        ModelView model = model(selection.graph());
        String headId = resolveEntityId(selection.graph(), head);
        String tailId = resolveEntityId(selection.graph(), tail);
        double score = plausibility(model, headId, relation, tailId);
        return ToolResult.success("graph_embeddings.score",
                String.format(Locale.ROOT,
                        "Triple Plausibility Score\n\n  %s —[%s]→ %s\n  Plausibility score: %.4f\n",
                        head, relation, tail, score),
                Map.of("head", head, "relation", relation, "tail", tail, "score", score,
                        "backend", "project-local"));
    }

    private ToolResult predictAction(JsonNode params,
                                     GraphSelection selection,
                                     PredictionTarget target) {
        ModelView model = model(selection.graph());
        int topK = bounded(params.path("top_k").asInt(10), 1, 100);
        String head = params.path("head").asText("");
        String relation = params.path("relation").asText("");
        String tail = params.path("tail").asText("");
        List<Scored> scored = new ArrayList<>();

        if (target == PredictionTarget.TAIL) {
            String headId = resolveEntityId(selection.graph(), required(head, "head"));
            required(relation, "relation");
            for (GraphEntity candidate : selection.graph().entities()) {
                scored.add(new Scored(candidate.label(), plausibility(model, headId, relation, candidate.id())));
            }
        } else if (target == PredictionTarget.HEAD) {
            String tailId = resolveEntityId(selection.graph(), required(tail, "tail"));
            required(relation, "relation");
            for (GraphEntity candidate : selection.graph().entities()) {
                scored.add(new Scored(candidate.label(), plausibility(model, candidate.id(), relation, tailId)));
            }
        } else {
            String headId = resolveEntityId(selection.graph(), required(head, "head"));
            String tailId = resolveEntityId(selection.graph(), required(tail, "tail"));
            for (String candidate : model.relations().keySet()) {
                scored.add(new Scored(candidate, plausibility(model, headId, candidate, tailId)));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::label));
        if (scored.size() > topK) scored = new ArrayList<>(scored.subList(0, topK));
        StringBuilder output = new StringBuilder("Project-local KGE predictions\n\n");
        for (Scored item : scored) {
            output.append(String.format(Locale.ROOT, "  %.4f  %s%n", item.score(), item.label()));
        }
        return ToolResult.success("graph_embeddings.predict_" + target.name().toLowerCase(Locale.ROOT),
                output.toString(), Map.of("count", scored.size(), "backend", "project-local"));
    }

    private ToolResult similarAction(JsonNode params, GraphSelection selection) {
        String name = firstNonBlank(params.path("entity_name").asText(null),
                params.path("head").asText(null));
        String entityId = resolveEntityId(selection.graph(), required(name, "entity_name"));
        ModelView model = model(selection.graph());
        double[] source = model.entities().get(entityId);
        int topK = bounded(params.path("top_k").asInt(10), 1, 100);
        List<Scored> scored = new ArrayList<>();
        for (GraphEntity candidate : selection.graph().entities()) {
            if (candidate.id().equals(entityId)) continue;
            double[] vector = model.entities().get(candidate.id());
            if (vector != null) scored.add(new Scored(candidate.label(), cosine(source, vector)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::label));
        if (scored.size() > topK) scored = new ArrayList<>(scored.subList(0, topK));
        StringBuilder output = new StringBuilder("Entities most similar to \"").append(name).append("\":\n\n");
        for (Scored item : scored) {
            output.append(String.format(Locale.ROOT, "  %.4f  %s%n", item.score(), item.label()));
        }
        return ToolResult.success("graph_embeddings.similar", output.toString(),
                Map.of("entityName", name, "count", scored.size(), "backend", "project-local"));
    }

    private TrainingSummary train(UnifiedGraph graph,
                                  TrainingRequest request,
                                  UnifiedGraph warmStart) throws Exception {
        String algorithm = request.algorithm().toUpperCase(Locale.ROOT);
        if (!Set.of("TRANSE", "ROTATE").contains(algorithm)) {
            throw new IllegalArgumentException("embedding algorithm must be TRANSE or ROTATE");
        }
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        entities.sort(Comparator.comparing(GraphEntity::id));
        TreeSet<String> relationTypes = new TreeSet<>();
        graph.relations().forEach(relation -> relationTypes.add(relation.type()));
        int dim = bounded(request.dim(), 2, MAX_DIM);
        int epochs = bounded(request.epochs(), 1, MAX_EPOCHS);
        double learningRate = Math.max(0.0001, Math.min(1.0, request.learningRate()));
        Random random = new Random(request.seed());

        Map<String, double[]> entityVectors = new LinkedHashMap<>();
        VectorLayer priorEntities = warmStart != null ? warmStart.vectorLayer(ENTITY_LAYER) : null;
        int storedEntityDim = "ROTATE".equals(algorithm) ? dim * 2 : dim;
        for (GraphEntity entity : entities) {
            double[] prior = priorEntities != null ? priorEntities.get(entity.id()) : null;
            entityVectors.put(entity.id(), prior != null && prior.length == storedEntityDim
                    ? prior.clone() : randomUnit(random, storedEntityDim));
        }

        Map<String, double[]> relationVectors = new LinkedHashMap<>();
        VectorLayer priorRelations = warmStart != null ? warmStart.vectorLayer(RELATION_LAYER) : null;
        for (String type : relationTypes) {
            double[] prior = priorRelations != null ? priorRelations.get(type) : null;
            relationVectors.put(type, prior != null && prior.length == dim
                    ? prior.clone() : randomUnit(random, dim));
        }

        if ("ROTATE".equals(algorithm)) {
            trainRotate(graph, entityVectors, relationVectors, dim, epochs, learningRate);
        } else {
            trainTranse(graph, entityVectors, relationVectors, dim, epochs, learningRate);
        }

        VectorLayer entityLayer = new VectorLayer(ENTITY_LAYER, VectorLayer.Target.ENTITY,
                storedEntityDim, Dtype.F32);
        entityVectors.forEach(entityLayer::put);
        VectorLayer relationLayer = new VectorLayer(RELATION_LAYER, VectorLayer.Target.GLOBAL,
                dim, Dtype.F32);
        relationVectors.forEach(relationLayer::put);
        graph.putVectorLayer(entityLayer).putVectorLayer(relationLayer);

        for (GraphEntity entity : entities) {
            double[] vector = entityVectors.get(entity.id());
            graph.addEntity(GraphEntity.builder(entity.id())
                    .type(entity.type())
                    .label(entity.label())
                    .weight(entity.weight())
                    .confidence(entity.confidence())
                    .tags(entity.tags())
                    .embedding(vector)
                    .timestamp(entity.timestamp())
                    .attributes(entity.attributes())
                    .build());
        }

        String jobId = "local-kge-" + stableId(graph.graphId() + "\n" + Instant.now()).substring(0, 16);
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("jobId", jobId);
        metadata.put("status", "COMPLETED");
        metadata.put("algorithm", algorithm);
        metadata.put("embeddingDim", dim);
        metadata.put("epochs", epochs);
        metadata.put("warmStartEpochs", request.warmStartEpochs());
        metadata.put("learningRate", learningRate);
        metadata.put("entities", entityVectors.size());
        metadata.put("relationTypes", relationVectors.size());
        metadata.put("trainedAt", Instant.now().toString());
        metadata.put("backend", "project-local");
        graph.putArtifact(MODEL_ARTIFACT, mapper.writeValueAsBytes(metadata));
        graph.meta("embeddingAlgorithm", algorithm)
                .meta("embeddingDim", dim)
                .meta("embeddingEpochs", epochs)
                .meta("embeddingUpdatedAt", metadata.path("trainedAt").asText());
        return new TrainingSummary(jobId, algorithm, dim, epochs,
                entityVectors.size(), relationVectors.size());
    }

    private void trainTranse(UnifiedGraph graph,
                             Map<String, double[]> entities,
                             Map<String, double[]> relations,
                             int dim,
                             int epochs,
                             double learningRate) {
        for (int epoch = 0; epoch < epochs; epoch++) {
            Map<String, double[]> relationSums = zeroRows(relations.keySet(), dim);
            Map<String, Integer> relationCounts = new HashMap<>();
            for (GraphRelation relation : graph.relations()) {
                double[] head = entities.get(relation.sourceId());
                double[] tail = entities.get(relation.targetId());
                if (head == null || tail == null) continue;
                double[] sum = relationSums.get(relation.type());
                for (int i = 0; i < dim; i++) sum[i] += tail[i] - head[i];
                relationCounts.merge(relation.type(), 1, Integer::sum);
            }
            for (Map.Entry<String, double[]> entry : relations.entrySet()) {
                int count = relationCounts.getOrDefault(entry.getKey(), 0);
                if (count > 0) blend(entry.getValue(), relationSums.get(entry.getKey()), count,
                        learningRate);
            }
            for (GraphRelation relation : graph.relations()) {
                double[] head = entities.get(relation.sourceId());
                double[] tail = entities.get(relation.targetId());
                double[] rel = relations.get(relation.type());
                if (head == null || tail == null || rel == null) continue;
                for (int i = 0; i < dim; i++) {
                    double expectedTail = head[i] + rel[i];
                    double error = expectedTail - tail[i];
                    tail[i] += learningRate * error * 0.5;
                    head[i] -= learningRate * error * 0.5;
                }
                normalize(head);
                normalize(tail);
            }
        }
    }

    private void trainRotate(UnifiedGraph graph,
                             Map<String, double[]> entities,
                             Map<String, double[]> relations,
                             int dim,
                             int epochs,
                             double learningRate) {
        Map<String, double[]> angles = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : entities.entrySet()) {
            double[] angle = new double[dim];
            double[] vector = entry.getValue();
            for (int i = 0; i < dim; i++) angle[i] = Math.atan2(vector[i + dim], vector[i]);
            angles.put(entry.getKey(), angle);
        }
        for (int epoch = 0; epoch < epochs; epoch++) {
            Map<String, double[]> sine = zeroRows(relations.keySet(), dim);
            Map<String, double[]> cosine = zeroRows(relations.keySet(), dim);
            for (GraphRelation relation : graph.relations()) {
                double[] head = angles.get(relation.sourceId());
                double[] tail = angles.get(relation.targetId());
                if (head == null || tail == null) continue;
                for (int i = 0; i < dim; i++) {
                    double delta = tail[i] - head[i];
                    sine.get(relation.type())[i] += Math.sin(delta);
                    cosine.get(relation.type())[i] += Math.cos(delta);
                }
            }
            for (Map.Entry<String, double[]> entry : relations.entrySet()) {
                for (int i = 0; i < dim; i++) {
                    double target = Math.atan2(sine.get(entry.getKey())[i],
                            cosine.get(entry.getKey())[i]);
                    entry.getValue()[i] = circularBlend(entry.getValue()[i], target, learningRate);
                }
            }
            for (GraphRelation relation : graph.relations()) {
                double[] head = angles.get(relation.sourceId());
                double[] tail = angles.get(relation.targetId());
                double[] phase = relations.get(relation.type());
                if (head == null || tail == null || phase == null) continue;
                for (int i = 0; i < dim; i++) {
                    tail[i] = circularBlend(tail[i], head[i] + phase[i], learningRate * 0.5);
                    head[i] = circularBlend(head[i], tail[i] - phase[i], learningRate * 0.5);
                }
            }
        }
        for (Map.Entry<String, double[]> entry : entities.entrySet()) {
            double[] angle = angles.get(entry.getKey());
            double[] vector = entry.getValue();
            for (int i = 0; i < dim; i++) {
                vector[i] = Math.cos(angle[i]);
                vector[i + dim] = Math.sin(angle[i]);
            }
        }
    }

    private ModelView model(UnifiedGraph graph) {
        VectorLayer entities = graph.vectorLayer(ENTITY_LAYER);
        VectorLayer relations = graph.vectorLayer(RELATION_LAYER);
        JsonNode metadata = modelMetadata(graph);
        if (entities == null || relations == null || metadata == null) {
            throw new IllegalStateException("No trained embeddings in this project-local graph. "
                    + "Run graph_embeddings action=train first.");
        }
        return new ModelView(metadata.path("algorithm").asText("TRANSE"),
                metadata.path("embeddingDim").asInt(entities.dim()),
                entities.rows(), relations.rows());
    }

    private JsonNode modelMetadata(UnifiedGraph graph) {
        byte[] bytes = graph.artifact(MODEL_ARTIFACT);
        if (bytes == null) return null;
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            return null;
        }
    }

    private double plausibility(ModelView model, String headId, String relation, String tailId) {
        double[] head = model.entities().get(headId);
        double[] rel = model.relations().get(relation);
        if (rel == null) {
            for (Map.Entry<String, double[]> entry : model.relations().entrySet()) {
                if (entry.getKey().equalsIgnoreCase(relation)) {
                    rel = entry.getValue();
                    break;
                }
            }
        }
        double[] tail = model.entities().get(tailId);
        if (head == null || rel == null || tail == null) return 0.0;
        double distance = 0.0;
        if ("ROTATE".equalsIgnoreCase(model.algorithm())) {
            int dim = model.dim();
            for (int i = 0; i < dim; i++) {
                double cos = Math.cos(rel[i]);
                double sin = Math.sin(rel[i]);
                double rotatedReal = head[i] * cos - head[i + dim] * sin;
                double rotatedImag = head[i] * sin + head[i + dim] * cos;
                double realError = rotatedReal - tail[i];
                double imaginaryError = rotatedImag - tail[i + dim];
                distance += realError * realError + imaginaryError * imaginaryError;
            }
        } else {
            for (int i = 0; i < Math.min(head.length, rel.length); i++) {
                double error = head[i] + rel[i] - tail[i];
                distance += error * error;
            }
        }
        return 1.0 / (1.0 + Math.sqrt(distance));
    }

    private GraphQueryEngine.Query toQuery(JsonNode params) {
        String operation = params.path("operation").asText("").trim();
        String question = firstNonBlank(params.path("question").asText(null),
                params.path("queryText").asText(null));
        if (operation.isBlank()) operation = question != null ? "SEARCH" : "CAPABILITIES";
        GraphQueryEngine.Intent intent;
        try {
            intent = GraphQueryEngine.Intent.valueOf(operation.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown graph operation: " + operation);
        }
        GraphQueryEngine.Direction direction = null;
        String rawDirection = params.path("direction").asText("");
        if (!rawDirection.isBlank()) {
            direction = GraphQueryEngine.Direction.valueOf(rawDirection.toUpperCase(Locale.ROOT));
        }
        List<String> relationTypes = new ArrayList<>();
        JsonNode types = params.path("relationTypes");
        if (types.isArray()) types.forEach(value -> relationTypes.add(value.asText()));
        double[] embedding = null;
        JsonNode vector = params.path("queryEmbedding");
        if (vector.isArray()) {
            embedding = new double[vector.size()];
            for (int i = 0; i < vector.size(); i++) embedding[i] = vector.get(i).asDouble();
        }
        HybridReasoner.Structural structural = null;
        String rawStructural = params.path("structural").asText("");
        if (!rawStructural.isBlank()) {
            structural = HybridReasoner.Structural.valueOf(rawStructural.toUpperCase(Locale.ROOT));
        }
        return new GraphQueryEngine.Query(intent,
                text(params, "entityId"), text(params, "targetId"), direction, relationTypes,
                params.hasNonNull("maxDepth") ? params.path("maxDepth").asInt() : null,
                params.hasNonNull("topK") ? params.path("topK").asInt() : null,
                embedding, structural, question);
    }

    private GraphSelection selectGraph(ToolContext context, JsonNode params) throws Exception {
        Long factSheetId = optionalLong(params, "factSheetId", "fact_sheet_id");
        String knowledgeBase = firstNonBlank(text(params, "knowledgeBase"),
                text(params, "knowledge_base"));
        if (factSheetId != null || knowledgeBase != null) {
            return selectGraph(context.getWorkingDirectory(), params);
        }

        LocalProjectCrawlBackend crawlBackend = new LocalProjectCrawlBackend(mapper, this);
        ToolResult bootstrap = crawlBackend.ensureFolderKnowledgeBase(context);
        if (bootstrap.isError()) {
            throw new IllegalStateException("Could not initialize the folder knowledge base: "
                    + bootstrap.getOutput());
        }
        return selectGraph(context.getWorkingDirectory(),
                selector(crawlBackend.defaultKnowledgeBaseId(context.getWorkingDirectory()), null));
    }

    private GraphSelection selectGraph(Path workingDirectory, JsonNode params) throws Exception {
        Path root = projectRoot(workingDirectory);
        Long factSheetId = optionalLong(params, "factSheetId", "fact_sheet_id");
        String knowledgeBase = firstNonBlank(text(params, "knowledgeBase"),
                text(params, "knowledge_base"));
        List<Path> candidates = new ArrayList<>();
        Path crawls = root.resolve("data/crawls");
        if (factSheetId != null) {
            Path path = crawls.resolve("kb-" + factSheetId).resolve(GRAPH_FILE);
            if (Files.isRegularFile(path)) candidates.add(path);
        } else if (knowledgeBase != null) {
            Path direct = crawls.resolve(slug(knowledgeBase)).resolve(GRAPH_FILE);
            if (Files.isRegularFile(direct)) candidates.add(direct);
            if (candidates.isEmpty()) {
                Path numeric = crawls.resolve("kb-" + knowledgeBase).resolve(GRAPH_FILE);
                if (Files.isRegularFile(numeric)) candidates.add(numeric);
            }
        } else if (Files.isDirectory(crawls)) {
            try (Stream<Path> directories = Files.list(crawls)) {
                candidates.addAll(directories.map(path -> path.resolve(GRAPH_FILE))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList());
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No project-local graph matches the explicit knowledge-base selector. "
                    + "Omit the selector to use and initialize the current folder's knowledge base.");
        }
        if (candidates.size() == 1) {
            return new GraphSelection(UnifiedGraph.load(candidates.get(0)), candidates.get(0));
        }
        UnifiedGraph merged = new UnifiedGraph()
                .graphId("local:" + root.getFileName() + ":all")
                .meta("backend", "project-local")
                .meta("projectRoot", root.toString())
                .meta("mergedGraphs", candidates.size());
        Set<String> relationIds = new LinkedHashSet<>();
        for (Path path : candidates) {
            UnifiedGraph graph = UnifiedGraph.load(path);
            graph.entities().forEach(merged::addEntity);
            for (GraphRelation relation : graph.relations()) {
                if (relationIds.add(relation.id())) merged.addRelation(relation);
            }
        }
        return new GraphSelection(merged, null);
    }

    private Path projectRoot(Path workingDirectory) {
        Path working = workingDirectory.toAbsolutePath().normalize();
        return projectStore.findProjectRoot(working).orElse(working);
    }

    private void updateCrawlSummary(Path directory,
                                    UnifiedGraph graph,
                                    Path graphPath,
                                    int codeEntities,
                                    TrainingSummary training,
                                    UnifiedGraphReasoningLifecycle.Summary reasoning,
                                    ResolutionSummary resolution,
                                    SemanticExtractionSummary semanticExtraction) throws IOException {
        Path summaryPath = directory.resolve("crawl-result.json");
        ObjectNode summary = Files.isRegularFile(summaryPath)
                ? object(mapper.readTree(summaryPath.toFile())) : mapper.createObjectNode();
        summary.put("graphPath", graphPath.toString());
        summary.put("graphEntityCount", graph.entities().size());
        summary.put("graphRelationCount", graph.relations().size());
        summary.put("codeEntityCount", codeEntities);
        summary.put("semanticEntityCount", semanticExtraction.entities());
        summary.put("semanticRelationCount", semanticExtraction.relations());
        summary.set("semanticExtractionErrors", mapper.valueToTree(semanticExtraction.errors()));
        summary.put("entityResolutionEnabled", resolution.enabled());
        summary.put("entityResolutionMergedCount", resolution.entitiesMerged());
        summary.put("entityResolutionTypeCorrectionCount", resolution.typesCorrected());
        summary.put("entityResolutionIdentifierLinkCount", resolution.identifierLinksCreated());
        summary.put("entityResolutionCandidateCount", resolution.candidatePairs());
        summary.put("embeddingVectorCount", graph.vectorLayers().values().stream()
                .mapToInt(VectorLayer::size).sum());
        if (graph.factSheetId() != null) summary.put("factSheetId", graph.factSheetId());
        if (training != null) {
            summary.put("embeddingAlgorithm", training.algorithm());
            summary.put("embeddingTrainingStatus", "COMPLETED");
        }
        summary.put("reasoningLearningEnabled", reasoning.enabled());
        summary.put("folPslLearned", reasoning.folPslLearned());
        summary.put("mebnLearned", reasoning.mebnLearned());
        summary.put("reasoningModelsTrained", reasoning.modelsTrained());
        summary.put("pslRuleCount", reasoning.pslRuleCount());
        summary.put("mebnFragmentCount", reasoning.mebnFragmentCount());
        summary.put("reasoningObservedTargetCount", reasoning.observedTargetCount());
        summary.put("reasoningConsensusRounds", reasoning.consensusRounds());
        mapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
    }

    private void writeImportedSummary(Path directory, UnifiedGraph graph, Path graphPath) throws IOException {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("profileId", directory.getFileName().toString());
        summary.put("name", firstNonBlank(stringMeta(graph, "knowledgeBaseName"), graph.graphId(),
                directory.getFileName().toString()));
        summary.put("status", "COMPLETED");
        summary.put("backend", "project-local");
        summary.put("imported", true);
        summary.put("finishedAt", Instant.now().toString());
        summary.putArray("sources");
        summary.put("documentCount", 0);
        summary.put("chunkCount", 0);
        summary.put("graphPath", graphPath.toString());
        summary.put("graphEntityCount", graph.entities().size());
        summary.put("graphRelationCount", graph.relations().size());
        if (graph.factSheetId() != null) summary.put("factSheetId", graph.factSheetId());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("crawl-result.json").toFile(), summary);
    }

    private void saveAtomic(UnifiedGraph graph, Path target) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            graph.save(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void addRelation(UnifiedGraph graph,
                             String source,
                             String target,
                             String type,
                             Map<String, Object> attributes) {
        String id = "relation:" + stableId(source + "\n" + type + "\n" + target);
        boolean exists = graph.relations().stream().anyMatch(relation -> relation.id().equals(id));
        if (!exists) {
            graph.addRelation(GraphRelation.builder(id, source, target)
                    .type(type)
                    .weight(1.0)
                    .confidence(1.0)
                    .directed(true)
                    .attributes(nonNullAttributes(attributes))
                    .build());
        }
    }

    private void addWeightedRelation(UnifiedGraph graph,
                                     String source,
                                     String target,
                                     String type,
                                     double weight,
                                     double confidence,
                                     Map<String, Object> attributes) {
        String id = "relation:" + stableId(source + "\n" + type + "\n" + target);
        boolean exists = graph.relations().stream().anyMatch(relation -> relation.id().equals(id));
        if (!exists) {
            graph.addRelation(GraphRelation.builder(id, source, target)
                    .type(type)
                    .weight(weight)
                    .confidence(confidence)
                    .directed(true)
                    .attributes(nonNullAttributes(attributes))
                    .build());
        }
    }

    private void forEachJsonLine(Path path, JsonLineConsumer consumer) throws IOException {
        if (!Files.isRegularFile(path)) return;
        try (BufferedReader lines = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.isBlank()) consumer.accept(mapper.readTree(line));
            }
        }
    }

    @FunctionalInterface
    private interface JsonLineConsumer {
        void accept(JsonNode value) throws IOException;
    }

    private Map<String, Object> jsonAttributes(JsonNode node) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                Object value = mapper.convertValue(entry.getValue(), Object.class);
                if (value != null) {
                    attributes.put(entry.getKey(), value);
                }
            }
        });
        return attributes;
    }

    private Map<String, Object> nonNullAttributes(Map<String, Object> source) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    attributes.put(key, value);
                }
            });
        }
        return attributes;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> converted = new LinkedHashMap<>();
                raw.forEach((key, entryValue) -> converted.put(String.valueOf(key), entryValue));
                result.add(converted);
            }
        }
        return result;
    }

    private Map<String, double[]> zeroRows(Collection<String> keys, int dim) {
        Map<String, double[]> rows = new LinkedHashMap<>();
        keys.forEach(key -> rows.put(key, new double[dim]));
        return rows;
    }

    private void blend(double[] current, double[] sum, int count, double rate) {
        for (int i = 0; i < current.length; i++) {
            current[i] = current[i] * (1.0 - rate) + (sum[i] / count) * rate;
        }
        normalize(current);
    }

    private double[] randomUnit(Random random, int dim) {
        double[] vector = new double[dim];
        for (int i = 0; i < dim; i++) vector[i] = random.nextDouble() * 2.0 - 1.0;
        normalize(vector);
        return vector;
    }

    private void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return;
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    }

    private double circularBlend(double current, double target, double rate) {
        double delta = Math.atan2(Math.sin(target - current), Math.cos(target - current));
        return current + rate * delta;
    }

    private double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) return 0.0;
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0;
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private String resolveEntityId(UnifiedGraph graph, String selector) {
        if (graph.entity(selector).isPresent()) return selector;
        List<GraphEntity> exact = graph.entities().stream()
                .filter(entity -> entity.label().equalsIgnoreCase(selector))
                .sorted(Comparator.comparing(GraphEntity::id)).toList();
        if (!exact.isEmpty()) return exact.get(0).id();
        String normalized = selector.toLowerCase(Locale.ROOT);
        List<GraphEntity> partial = graph.entities().stream()
                .filter(entity -> entity.label().toLowerCase(Locale.ROOT).contains(normalized)
                        || entity.id().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(GraphEntity::id)).toList();
        if (!partial.isEmpty()) return partial.get(0).id();
        throw new IllegalArgumentException("Unknown graph entity: " + selector);
    }

    private String codeEntityId(String projectId, String type, String fqn) {
        return "code:" + stableId(projectId + "\n" + type + "\n" + fqn);
    }

    private static String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode selector(String knowledgeBase, Long factSheetId) {
        ObjectNode selector = mapper.createObjectNode();
        if (knowledgeBase != null) selector.put("knowledgeBase", knowledgeBase);
        if (factSheetId != null) selector.put("factSheetId", factSheetId);
        return selector;
    }

    private void copySelector(JsonNode source, ObjectNode target) {
        for (String field : List.of("factSheetId", "fact_sheet_id", "knowledgeBase", "knowledge_base")) {
            if (source.hasNonNull(field)) target.set(field, source.get(field));
        }
    }

    private Long optionalLong(JsonNode params, String... keys) {
        for (String key : keys) {
            JsonNode value = params.get(key);
            if (value != null && value.canConvertToLong()) return value.asLong();
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private String stringMeta(UnifiedGraph graph, String key) {
        Object value = graph.meta().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private ObjectNode object(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value : mapper.createObjectNode();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String requiredText(JsonNode params, String field) {
        return required(params.path(field).asText(""), field);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String abbreviate(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private String csv(List<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    private String slug(String value) {
        String result = firstNonBlank(value, "knowledge")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return result.isBlank() ? "knowledge" : result;
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    private String message(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record CodeProjectSource(Path root,
                                    String codeProjectId,
                                    String name,
                                    List<String> includePatterns,
                                    List<String> excludePatterns) {
    }

    public record GraphUpdate(Path graphPath,
                              int entities,
                              int relations,
                              int codeEntities,
                              int embeddingVectors,
                              String embeddingAlgorithm,
                              boolean enrichmentRequested,
                              boolean reasoningLearningEnabled,
                              boolean folPslLearned,
                              boolean mebnLearned,
                              int reasoningModelsTrained,
                              int pslRuleCount,
                              int mebnFragmentCount,
                              boolean entityResolutionEnabled,
                              int entitiesMerged,
                              int entityTypesCorrected,
                              int identifierLinksCreated,
                              int semanticEntities,
                              int semanticRelations,
                              List<String> semanticExtractionErrors) {
    }

    private record SemanticExtractionSummary(int entities,
                                             int relations,
                                             List<String> errors) {
        private static SemanticExtractionSummary none() {
            return new SemanticExtractionSummary(0, 0, List.of());
        }

        private static SemanticExtractionSummary failed(String error) {
            return new SemanticExtractionSummary(0, 0, List.of(error));
        }
    }

    public record GraphStats(int entities, int relations, int embeddingVectors, String graphPath) {
    }

    private record GraphSelection(UnifiedGraph graph, Path path) {
        String pathDescription() {
            return path == null ? "merged project-local graphs" : path.toString();
        }
    }

    private record TrainingSummary(String jobId,
                                   String algorithm,
                                   int dim,
                                   int epochs,
                                   int entities,
                                   int relationTypes) {
    }

    private record FinalLearningSummary(
            TrainingSummary embedding,
            UnifiedGraphReasoningLifecycle.Summary reasoning) {
    }

    private record ResolutionSummary(boolean enabled,
                                     int entitiesMerged,
                                     int typesCorrected,
                                     int identifierLinksCreated,
                                     int candidatePairs,
                                     int acceptedPairs,
                                     boolean candidateCapReached) {
        private static ResolutionSummary disabled() {
            return new ResolutionSummary(false, 0, 0, 0, 0, 0, false);
        }

        private static ResolutionSummary from(UnifiedGraphEntityResolver.Result result) {
            return new ResolutionSummary(
                    true,
                    result.entitiesMerged(),
                    result.typesCorrected(),
                    result.identifierLinksCreated(),
                    result.candidatePairs(),
                    result.acceptedPairs(),
                    result.candidateCapReached());
        }
    }

    private record ResolutionRequest(boolean enabled,
                                     double mergeThreshold,
                                     boolean useEmbeddings,
                                     double embeddingThreshold,
                                     int maxCandidatePairs) {
        private static ResolutionRequest from(JsonNode request) {
            JsonNode graphConfig = request == null ? null : request.path("graphExtraction");
            if (graphConfig != null && !graphConfig.isObject()
                    && request != null && request.path("config").isObject()) {
                graphConfig = request.path("config").path("graphExtraction");
            }
            JsonNode explicit = request == null ? null : request.path("entityResolution");

            boolean enabled = true;
            if (explicit != null && explicit.isBoolean()) {
                enabled = explicit.asBoolean();
            } else if (explicit != null && explicit.isObject() && explicit.has("enabled")) {
                enabled = explicit.path("enabled").asBoolean(true);
            }
            if (graphConfig != null && graphConfig.isObject()
                    && graphConfig.has("entityResolution")) {
                enabled = graphConfig.path("entityResolution").asBoolean(enabled);
            }

            double mergeThreshold = 0.86;
            boolean useEmbeddings = true;
            double embeddingThreshold = 0.90;
            int maxCandidatePairs = 50_000;
            if (explicit != null && explicit.isObject()) {
                mergeThreshold = explicit.path("similarityThreshold").asDouble(mergeThreshold);
                useEmbeddings = explicit.path("useEmbeddings").asBoolean(useEmbeddings);
                embeddingThreshold =
                        explicit.path("embeddingThreshold").asDouble(embeddingThreshold);
                maxCandidatePairs =
                        explicit.path("maxCandidatePairs").asInt(maxCandidatePairs);
            }
            if (graphConfig != null && graphConfig.isObject()) {
                mergeThreshold = graphConfig.path("entityResolutionSimilarityThreshold")
                        .asDouble(mergeThreshold);
                useEmbeddings = graphConfig.path("entityResolutionUseEmbeddings")
                        .asBoolean(useEmbeddings);
                embeddingThreshold = graphConfig.path("entityResolutionEmbeddingThreshold")
                        .asDouble(embeddingThreshold);
                maxCandidatePairs = graphConfig.path("entityResolutionMaxCandidatePairs")
                        .asInt(maxCandidatePairs);
            }
            mergeThreshold = Math.max(0.0, Math.min(1.0, mergeThreshold));
            embeddingThreshold = Math.max(0.0, Math.min(1.0, embeddingThreshold));
            maxCandidatePairs = Math.max(1, maxCandidatePairs);
            return new ResolutionRequest(enabled, mergeThreshold, useEmbeddings,
                    embeddingThreshold, maxCandidatePairs);
        }

        private UnifiedGraphEntityResolver.Config toResolverConfig() {
            return new UnifiedGraphEntityResolver.Config(
                    mergeThreshold,
                    Math.min(0.80, mergeThreshold),
                    useEmbeddings,
                    embeddingThreshold,
                    maxCandidatePairs,
                    20.0,
                    4.0,
                    1.0);
        }
    }

    private record ModelView(String algorithm,
                             int dim,
                             Map<String, double[]> entities,
                             Map<String, double[]> relations) {
    }

    private record Scored(String label, double score) {
    }

    private enum PredictionTarget { HEAD, TAIL, RELATION }

    private record ReasoningLearningRequest(boolean enrichmentRequested,
                                            boolean enabled,
                                            int pslSteps,
                                            int mebnEpochs,
                                            int consensusRounds,
                                            double consensusWeight,
                                            int maxRelationTypes) {
        private static ReasoningLearningRequest from(JsonNode request) {
            JsonNode explicit = request != null ? request.path("reasoningLearning") : null;
            JsonNode runtime = request != null ? request.path("runtimeConfig") : null;
            if (request != null && request.path("config").isObject()) {
                JsonNode configuredRuntime = request.path("config").path("runtimeConfig");
                if (configuredRuntime.isObject()) runtime = configuredRuntime;
            }
            boolean hasExplicit = explicit != null && explicit.isObject();
            boolean hasRuntime = runtime != null && runtime.isObject();
            boolean enrichmentRequested = enrichmentRequested(request);
            boolean derivationEnabled = hydrationStageEnabled(request, "DERIVATION");
            boolean hydrationDryRun = request != null
                    && request.path("hydration").path("dryRun").asBoolean(false);
            boolean configured = hasExplicit
                    ? explicit.path("enabled").asBoolean(true)
                    : hasRuntime && runtime.has("runReasoningLearning")
                    ? runtime.path("runReasoningLearning").asBoolean(true)
                    : true;
            boolean enabled = enrichmentRequested && derivationEnabled && !hydrationDryRun && configured;
            int pslSteps = hasExplicit ? explicit.path("pslSteps").asInt(1) : 1;
            int mebnEpochs = hasExplicit ? explicit.path("mebnEpochs").asInt(1) : 1;
            int consensusRounds = hasExplicit
                    ? explicit.path("consensusRounds").asInt(1) : 1;
            double consensusWeight = hasExplicit
                    ? explicit.path("consensusWeight").asDouble(0.35) : 0.35;
            int maxRelationTypes = hasExplicit
                    ? explicit.path("maxRelationTypes").asInt(25) : 25;
            return new ReasoningLearningRequest(enrichmentRequested, enabled, Math.max(1, pslSteps),
                    Math.max(1, mebnEpochs), Math.max(1, consensusRounds),
                    Math.max(0.0, Math.min(1.0, consensusWeight)),
                    Math.max(1, maxRelationTypes));
        }

        private static boolean enrichmentRequested(JsonNode request) {
            UnifiedCrawlRequest planRequest = new UnifiedCrawlRequest();
            planRequest.setEnabledSteps(stepIds(request, "steps", "enabledSteps"));
            planRequest.setArchivedSteps(stepIds(request, "archivedSteps"));
            planRequest.setStrictSteps(request != null && request.has("strictSteps")
                    ? request.path("strictSteps").asBoolean() : null);
            return CrawlStepPlan.from(planRequest).isRun("ENRICHMENT");
        }

        private static boolean hydrationStageEnabled(JsonNode request, String stageId) {
            JsonNode stages = request == null
                    ? null : request.path("hydration").get("enabledStageIds");
            if (stages == null || !stages.isArray() || stages.isEmpty()) {
                return true;
            }
            for (JsonNode stage : stages) {
                if (stageId.equalsIgnoreCase(stage.asText(""))) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> stepIds(JsonNode request, String... fields) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (request != null) {
                for (String field : fields) {
                    JsonNode values = request.get(field);
                    if (values == null || !values.isArray()) continue;
                    for (JsonNode value : values) {
                        String id = value.asText("").trim().toUpperCase(Locale.ROOT);
                        if (!id.isEmpty()) ids.add(id);
                    }
                }
            }
            return List.copyOf(ids);
        }

        private UnifiedGraphReasoningLifecycle.Config toConfig() {
            return new UnifiedGraphReasoningLifecycle.Config(enabled, pslSteps, mebnEpochs,
                    consensusRounds, consensusWeight, maxRelationTypes);
        }
    }

    private record TrainingRequest(boolean enabled,
                                   String algorithm,
                                   int dim,
                                   int epochs,
                                   double learningRate,
                                   int warmStartEpochs,
                                   long seed) {
        static TrainingRequest from(JsonNode request) {
            JsonNode explicit = request != null ? request.path("embeddingTraining") : null;
            JsonNode runtime = request != null ? request.path("runtimeConfig") : null;
            if (request != null && request.path("config").isObject()) {
                JsonNode configuredRuntime = request.path("config").path("runtimeConfig");
                if (configuredRuntime.isObject()) runtime = configuredRuntime;
            }
            boolean hasExplicit = explicit != null && explicit.isObject();
            boolean hasRuntime = runtime != null && runtime.isObject();
            boolean enabled = hasExplicit
                    ? explicit.path("enabled").asBoolean(true)
                    : hasRuntime && runtime.has("trainEmbeddingsAfterEnrichment")
                    ? runtime.path("trainEmbeddingsAfterEnrichment").asBoolean()
                    : true;
            String algorithm = hasExplicit
                    ? explicit.path("algorithm").asText("TRANSE")
                    : hasRuntime ? runtime.path("embeddingAlgorithm").asText("TRANSE") : "TRANSE";
            int dim = hasExplicit
                    ? explicit.path("embeddingDim").asInt(DEFAULT_DIM)
                    : hasRuntime ? runtime.path("embeddingDim").asInt(DEFAULT_DIM) : DEFAULT_DIM;
            int epochs = hasExplicit
                    ? explicit.path("epochs").asInt(DEFAULT_EPOCHS)
                    : hasRuntime ? runtime.path("embeddingEpochs").asInt(DEFAULT_EPOCHS) : DEFAULT_EPOCHS;
            int warmStart = hasExplicit
                    ? explicit.path("warmStartEpochs").asInt(epochs)
                    : hasRuntime ? runtime.path("embeddingWarmStartEpochs").asInt(epochs) : epochs;
            return new TrainingRequest(enabled, algorithm, dim, epochs,
                    DEFAULT_LEARNING_RATE, warmStart, 1234L);
        }
    }
}
