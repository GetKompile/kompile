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
import ai.kompile.graph.reasoning.debug.UnifiedGraphDebugRenderer;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * In-process graph lifecycle for the project-local crawl backend.
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

        retainCompatibleAssets(previous, graph);
        TrainingRequest training = TrainingRequest.from(request);
        TrainingSummary trainingSummary = null;
        if (training.enabled() && !graph.relations().isEmpty()) {
            trainingSummary = train(graph, training, previous);
        }

        Files.createDirectories(directory);
        saveAtomic(graph, graphPath);
        updateCrawlSummary(directory, graph, graphPath, codeEntityCount, trainingSummary);

        return new GraphUpdate(graphPath, graph.entities().size(), graph.relations().size(),
                codeEntityCount, graph.vectorLayers().values().stream().mapToInt(VectorLayer::size).sum(),
                trainingSummary != null ? trainingSummary.algorithm() : null);
    }

    public JsonNode reasoningQuery(JsonNode params, ToolContext context) throws Exception {
        GraphSelection selection = selectGraph(context.getWorkingDirectory(), params);
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
            GraphSelection selection = selectGraph(context.getWorkingDirectory(), params);
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
                        + "PNG rendering requires kompile-graph-service.");
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
            GraphSelection selection = selectGraph(context.getWorkingDirectory(), params);
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

    private Map<String, String> addDocuments(UnifiedGraph graph,
                                             Path directory,
                                             String knowledgeBaseNode,
                                             String projectId,
                                             String knowledgeBaseId,
                                             Long factSheetId) throws IOException {
        Map<String, String> documentNodes = new LinkedHashMap<>();
        for (JsonNode document : readJsonLines(directory.resolve("documents.jsonl"))) {
            String documentId = document.path("documentId").asText("");
            if (documentId.isBlank()) {
                continue;
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
        }

        Map<String, String> previousChunk = new HashMap<>();
        for (JsonNode chunk : readJsonLines(directory.resolve("chunks.jsonl"))) {
            String chunkId = chunk.path("chunkId").asText("");
            String documentId = chunk.path("documentId").asText("");
            String documentNode = documentNodes.get(documentId);
            if (chunkId.isBlank() || documentNode == null) {
                continue;
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
        }
        return documentNodes;
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
        String headId = resolveEntity(selection.graph(), head);
        String tailId = resolveEntity(selection.graph(), tail);
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
            String headId = resolveEntity(selection.graph(), required(head, "head"));
            required(relation, "relation");
            for (GraphEntity candidate : selection.graph().entities()) {
                scored.add(new Scored(candidate.label(), plausibility(model, headId, relation, candidate.id())));
            }
        } else if (target == PredictionTarget.HEAD) {
            String tailId = resolveEntity(selection.graph(), required(tail, "tail"));
            required(relation, "relation");
            for (GraphEntity candidate : selection.graph().entities()) {
                scored.add(new Scored(candidate.label(), plausibility(model, candidate.id(), relation, tailId)));
            }
        } else {
            String headId = resolveEntity(selection.graph(), required(head, "head"));
            String tailId = resolveEntity(selection.graph(), required(tail, "tail"));
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
        String entityId = resolveEntity(selection.graph(), required(name, "entity_name"));
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
            throw new IllegalStateException("No project-local graph matches the requested knowledge base. "
                    + "Run crawl_documents first.");
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
                                    TrainingSummary training) throws IOException {
        Path summaryPath = directory.resolve("crawl-result.json");
        ObjectNode summary = Files.isRegularFile(summaryPath)
                ? object(mapper.readTree(summaryPath.toFile())) : mapper.createObjectNode();
        summary.put("graphPath", graphPath.toString());
        summary.put("graphEntityCount", graph.entities().size());
        summary.put("graphRelationCount", graph.relations().size());
        summary.put("codeEntityCount", codeEntities);
        summary.put("embeddingVectorCount", graph.vectorLayers().values().stream()
                .mapToInt(VectorLayer::size).sum());
        if (graph.factSheetId() != null) summary.put("factSheetId", graph.factSheetId());
        if (training != null) {
            summary.put("embeddingAlgorithm", training.algorithm());
            summary.put("embeddingTrainingStatus", "COMPLETED");
        }
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

    private List<JsonNode> readJsonLines(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        List<JsonNode> rows = new ArrayList<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                rows.add(mapper.readTree(line));
            }
        }
        return rows;
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

    private String resolveEntity(UnifiedGraph graph, String selector) {
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
                              String embeddingAlgorithm) {
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

    private record ModelView(String algorithm,
                             int dim,
                             Map<String, double[]> entities,
                             Map<String, double[]> relations) {
    }

    private record Scored(String label, double score) {
    }

    private enum PredictionTarget { HEAD, TAIL, RELATION }

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
