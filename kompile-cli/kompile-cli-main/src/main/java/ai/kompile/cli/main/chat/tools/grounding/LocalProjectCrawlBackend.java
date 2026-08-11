/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalCrawlCapabilities;
import ai.kompile.cli.main.project.LocalCrawlSubprocessRunner;
import ai.kompile.cli.main.project.LocalModelPipelineRunner;
import ai.kompile.cli.main.project.ProjectCrawlCommand;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Project-local implementation of the crawl MCP contract.
 *
 * <p>The distributed crawl manager and this class deliberately share the agent-facing tools.
 * When no manager URL is configured, selected files are indexed synchronously into the existing
 * {@code data/crawls/<knowledge-base>} artifact format. Repeated calls merge source roots, so
 * {@code crawl_documents} has additive "put these documents in this KB" semantics.</p>
 */
public final class LocalProjectCrawlBackend {
    private static final List<String> DEFAULT_CODE_EXCLUDES = List.of(
            "**/.git/**", "**/.kompile/**", "**/target/**", "**/build/**",
            "**/.gradle/**", "**/.idea/**", "**/node_modules/**",
            "**/data/crawls/**", "**/data/markdown/**");
    private static final ConcurrentHashMap<String, ReentrantLock> CRAWL_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final KompileProjectStore store;
    private final LocalProjectGraphBackend graphBackend;

    public LocalProjectCrawlBackend(ObjectMapper mapper) {
        this.mapper = mapper;
        this.store = new KompileProjectStore();
        this.graphBackend = new LocalProjectGraphBackend(mapper);
    }

    public ToolResult crawlDocuments(JsonNode params, ToolContext context) {
        try {
            ProjectState project = project(context.getWorkingDirectory());
            KnowledgeBaseRef knowledgeBase = knowledgeBase(params.get("knowledgeBase"), project);
            if (knowledgeBase.error() != null) {
                return ToolResult.error(knowledgeBase.error());
            }

            LinkedHashSet<String> sources = new LinkedHashSet<>();
            LinkedHashSet<String> includePatterns = new LinkedHashSet<>();
            LinkedHashSet<String> excludePatterns = new LinkedHashSet<>();
            List<String> warnings = new ArrayList<>();
            ObjectNode previous = readSummary(project.root(), knowledgeBase.id());
            mergePrevious(previous, sources, includePatterns, excludePatterns, warnings);
            LinkedHashMap<String, ObjectNode> sourceConfigs = previousSourceConfigs(
                    project.root(), knowledgeBase.id(), previous, sources);

            JsonNode documents = params.get("documents");
            int explicitDocuments = 0;
            boolean unrestrictedSource = false;
            if (documents != null && documents.isArray()) {
                for (int i = 0; i < documents.size(); i++) {
                    JsonNode document = documents.get(i);
                    if (!document.isObject()) {
                        return ToolResult.error("documents[" + i + "] must be an object.");
                    }
                    String path = text(document, "path");
                    String url = text(document, "url");
                    if ((path == null) == (url == null)) {
                        return ToolResult.error(
                                "documents[" + i + "] must provide exactly one of path or url.");
                    }
                    if (url != null) {
                        return ToolResult.error("The project-local crawl backend accepts files and directories. "
                                + "URL source '" + url + "' requires a configured distributed crawl manager.");
                    }
                    Path resolved = context.resolvePath(path).toAbsolutePath().normalize();
                    if (!Files.exists(resolved)) {
                        return ToolResult.error("Local crawl source does not exist: " + resolved);
                    }
                    sources.add(resolved.toString());
                    ObjectNode sourceConfig = mapper.createObjectNode().put("path", resolved.toString());
                    copyDocumentOptions(document, sourceConfig);
                    sourceConfigs.put(resolved.toString(), sourceConfig);
                    explicitDocuments++;
                    List<String> selectedIncludes = strings(document.get("includePatterns"));
                    if (selectedIncludes.isEmpty()) {
                        unrestrictedSource = true;
                    } else {
                        includePatterns.addAll(selectedIncludes);
                    }
                    excludePatterns.addAll(strings(document.get("excludePatterns")));
                }
            }

            CodeProjectSelection selectedProjects = selectCodeProjects(
                    params.get("codeProjects"), project);
            if (selectedProjects.error() != null) {
                return ToolResult.error(selectedProjects.error());
            }
            knowledgeBase = alignKnowledgeBase(
                    knowledgeBase, selectedProjects, params.hasNonNull("knowledgeBase"));
            if (knowledgeBase.error() != null) {
                return ToolResult.error(knowledgeBase.error());
            }
            for (SelectedCodeProject selected : selectedProjects.projects()) {
                if (!Files.exists(selected.root())) {
                    return ToolResult.error("Registered code project source does not exist: "
                            + selected.root());
                }
                sources.add(selected.root().toString());
                sourceConfigs.computeIfAbsent(selected.root().toString(), ignored -> mapper.createObjectNode()
                                .put("path", selected.root().toString()))
                        .put("pipelineId", LocalCrawlCapabilities.CODE_PIPELINE);
                includePatterns.addAll(selected.includePatterns());
                excludePatterns.addAll(DEFAULT_CODE_EXCLUDES);
                excludePatterns.addAll(selected.excludePatterns());
            }

            if (sources.isEmpty()) {
                return ToolResult.error("Provide at least one local document or codeProjects selector.");
            }
            if (unrestrictedSource) {
                includePatterns.clear();
            }

            for (String source : sources) {
                sourceConfigs.computeIfAbsent(source,
                        ignored -> mapper.createObjectNode().put("path", source));
            }
            ObjectNode executionRequest = params.deepCopy();
            ArrayNode normalizedDocuments = executionRequest.putArray("documents");
            sourceConfigs.values().forEach(normalizedDocuments::add);
            String validationError = LocalCrawlCapabilities.validationError(executionRequest);
            if (validationError != null) {
                return ToolResult.error(validationError);
            }
            addUnsupportedWarnings(executionRequest, warnings);

            KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
            profile.setId(knowledgeBase.id());
            profile.setName(knowledgeBase.name());
            profile.setDescription("Project-local MCP knowledge base");
            profile.setSources(new ArrayList<>(sources));
            profile.setMaxDepth(64);
            profile.setMaxDocuments(0);
            profile.setIncludePatterns(new ArrayList<>(includePatterns));
            profile.setExcludePatterns(new ArrayList<>(excludePatterns));
            profile.setLoader("auto");
            profile.setChunker("recursive-character");
            profile.setCollection(knowledgeBase.name());
            profile.setSourceType("FILE");
            profile.setFactSheetName(knowledgeBase.name());
            profile.setGraphLocal(true);
            profile.setGraphAutoStart(true);
            profile.setMetadata(new LinkedHashMap<>(Map.of(
                    "backend", "project-local",
                    "graphFile", LocalProjectGraphBackend.GRAPH_FILE)));

            boolean dryRun = params.path("dryRun").asBoolean(false);
            String lockKey = project.root() + "\n" + knowledgeBase.id();
            ReentrantLock lock = CRAWL_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
            lock.lock();
            try {
                ProjectCrawlCommand.LocalCrawlExecution execution =
                        LocalCrawlSubprocessRunner.execute(
                                profile, project.root(), dryRun, executionRequest, mapper);
                LocalProjectGraphBackend.GraphUpdate graphUpdate = null;
                if (!dryRun) {
                    List<LocalProjectGraphBackend.CodeProjectSource> graphCodeProjects =
                            selectedProjects.projects().stream()
                                    .map(selected -> new LocalProjectGraphBackend.CodeProjectSource(
                                            selected.root(), selected.codeProjectId(), selected.name(),
                                            selected.includePatterns(), selected.excludePatterns()))
                                    .toList();
                    graphUpdate = graphBackend.updateCrawlGraph(
                            project.root(), knowledgeBase.id(), knowledgeBase.name(),
                            knowledgeBase.factSheetId(), project.id(), graphCodeProjects, executionRequest);
                    persistRequest(execution.outputDirectory(), executionRequest, project, knowledgeBase);
                    persistProjectGraphProfile(project, profile, knowledgeBase,
                            selectedProjects.projects(), graphUpdate);
                }
                ObjectNode summary = dryRun
                        ? dryRunSummary(profile, execution)
                        : readSummary(project.root(), knowledgeBase.id());
                summary.put("backend", "project-local");
                summary.put("distributed", false);
                summary.put("jobId", knowledgeBase.id());

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("jobId", knowledgeBase.id());
                metadata.put("status", dryRun ? "DRY_RUN" : "COMPLETED");
                metadata.put("backend", "project-local");
                metadata.put("distributed", false);
                metadata.put("executionMode", LocalCrawlSubprocessRunner.executionMode());
                metadata.put("knowledgeBase", knowledgeBase.id());
                metadata.put("sourceCount", sources.size());
                metadata.put("addedDocumentSelectors", explicitDocuments);
                metadata.put("codeProjectCount", selectedProjects.projects().size());
                metadata.put("documentCount", execution.documentCount());
                metadata.put("chunkCount", execution.chunkCount());
                if (knowledgeBase.factSheetId() != null) {
                    metadata.put("factSheetId", knowledgeBase.factSheetId());
                }
                if (graphUpdate != null) {
                    metadata.put("graphPath", graphUpdate.graphPath().toString());
                    metadata.put("graphEntityCount", graphUpdate.entities());
                    metadata.put("graphRelationCount", graphUpdate.relations());
                    metadata.put("codeEntityCount", graphUpdate.codeEntities());
                    metadata.put("embeddingVectorCount", graphUpdate.embeddingVectors());
                    if (graphUpdate.embeddingAlgorithm() != null) {
                        metadata.put("embeddingAlgorithm", graphUpdate.embeddingAlgorithm());
                    }
                }
                metadata.put("warnings", warnings);
                metadata.put("nextTools", List.of(
                        "knowledge_search", "knowledge_status", "crawl_control",
                        "graph_reasoning_query", "graph_reason", "graph_embeddings",
                        "graph_export", "graph_import", "memory"));

                StringBuilder output = new StringBuilder();
                output.append(dryRun ? "Project-local crawl preview complete." :
                                "Project-local knowledge base updated.")
                        .append(" Knowledge base: ").append(knowledgeBase.id()).append(". ")
                        .append("Sources: ").append(sources.size()).append(". ");
                if (!dryRun) {
                    output.append("Documents: ").append(execution.documentCount()).append(". ")
                            .append("Chunks: ").append(execution.chunkCount()).append(". ");
                    if (graphUpdate != null) {
                        output.append("Graph entities: ").append(graphUpdate.entities()).append(". ")
                                .append("Relations: ").append(graphUpdate.relations()).append(". ")
                                .append("Embedding vectors: ").append(graphUpdate.embeddingVectors()).append(". ");
                    }
                }
                output.append("Backend: synchronous project-local MCP worker ("
                        + LocalCrawlSubprocessRunner.executionMode() + ").");
                if (!warnings.isEmpty()) {
                    output.append(" Warnings: ").append(String.join(" ", warnings));
                }
                output.append("\n").append(summary.toPrettyString());
                return ToolResult.success("crawl_documents", output.toString(), metadata);
            } finally {
                lock.unlock();
                if (!lock.hasQueuedThreads()) {
                    CRAWL_LOCKS.remove(lockKey, lock);
                }
            }
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl failed: " + message(e));
        }
    }

    public ToolResult crawlSource(JsonNode params, ToolContext context) {
        String path = text(params, "path");
        String url = text(params, "url");
        String inline = text(params, "text");
        boolean dryRun = params.path("dryRun").asBoolean(false);
        if (url != null) {
            return ToolResult.error("URL crawling requires a configured distributed crawl manager. "
                    + "The project-local MCP worker accepts path or text.");
        }

        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("dryRun", dryRun);
            String title = firstNonBlank(text(params, "title"), "inline knowledge");
            if (params.hasNonNull("factSheetId")) {
                request.putObject("knowledgeBase").put("id", params.path("factSheetId").asInt());
            }
            if (path != null) {
                request.putArray("documents").addObject().put("path", path).put("label", title);
                return crawlDocuments(request, context);
            }
            if (inline == null) {
                return ToolResult.error("Provide exactly one of: path, url, or text.");
            }
            if (dryRun) {
                return ToolResult.success("crawl_source",
                        "Project-local inline crawl preview complete; no source or index artifacts were written.",
                        Map.of("backend", "project-local", "status", "DRY_RUN", "persisted", false));
            }

            ProjectState project = project(context.getWorkingDirectory());
            KnowledgeBaseRef kb = knowledgeBase(request.get("knowledgeBase"), project);
            if (kb.error() != null) {
                return ToolResult.error(kb.error());
            }
            Path sourceDirectory = project.root().resolve("data/knowledge-sources")
                    .resolve(kb.id()).normalize();
            if (!sourceDirectory.startsWith(project.root())) {
                return ToolResult.error("Inline knowledge source escapes the project root.");
            }
            Files.createDirectories(sourceDirectory);
            String fileName = slug(title) + "-" + Integer.toUnsignedString(inline.hashCode(), 16) + ".md";
            Path source = sourceDirectory.resolve(fileName);
            String markdown = "# " + title + "\n\n" + inline.strip() + "\n";
            Files.writeString(source, markdown, StandardCharsets.UTF_8);
            request.putArray("documents").addObject()
                    .put("path", source.toString())
                    .put("label", title);
            return crawlDocuments(request, context);
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl_source failed: " + message(e));
        }
    }

    public ToolResult discover(String section, Path workingDirectory) {
        try {
            ProjectState project = project(workingDirectory);
            ObjectNode catalog = mapper.createObjectNode();
            catalog.put("section", section);
            catalog.putObject("backend")
                    .put("mode", "project-local")
                    .put("projectRoot", project.root().toString())
                    .put("distributed", false)
                    .put("coordination", "synchronous MCP-controlled crawl subprocess")
                    .put("executionMode", LocalCrawlSubprocessRunner.executionMode());

            if (matches(section, "sources")) {
                ArrayNode types = catalog.putArray("sourceTypes");
                sourceType(types, "FILE", true, "One explicit local file.");
                sourceType(types, "DIRECTORY", true, "A local directory tree.");
                sourceType(types, "CODE_PROJECT", true,
                        "A code project registered in kompile.project.json, or the current directory.");
                sourceType(types, "URL", false,
                        "Requires the distributed crawl manager and its network loader.");
                sourceType(types, "INLINE_TEXT", true, "Use crawl_source text=... .");
            }
            if (matches(section, "pipelines")) {
                ObjectNode capabilities = LocalCrawlCapabilities.catalog(
                        mapper, LocalCrawlSubprocessRunner.executionMode());
                ArrayNode pipelines = catalog.putArray("pipelineTypes");
                localPipeline(pipelines, "STANDARD_TEXT", true,
                        "Format-aware loading, configurable chunking, and lexical indexing.");
                localPipeline(pipelines, "CODE", true,
                        "Code loading, routing, configurable chunking, and project filters.");
                localPipeline(pipelines, "CUSTOM", true,
                        "Discovered loaders/chunkers or a caller-supplied unified pipeline definition.");
                localPipeline(pipelines, "OCR", true,
                        "Traditional or VLM-backed OCR in the isolated document-model subprocess.");
                localPipeline(pipelines, "VLM", true,
                        "Model-backed PDF extraction in the isolated document-model subprocess.");
                localPipeline(pipelines, "TABLE_AWARE", true,
                        "Local table preservation with optional model-backed extraction.");
                localPipeline(pipelines, "KEYWORD_ONLY", true,
                        "Lexical indexing without embeddings.");
                ArrayNode steps = (ArrayNode) capabilities.remove("steps");
                for (String id : List.of("GRAPH_EXTRACTION", "VECTOR_INDEXING",
                        "ENTITY_RESOLUTION", "LEARNING")) {
                    steps.addObject().put("id", id).put("available", true)
                            .put("backend", "project-local")
                            .put("artifact", "data/crawls/<knowledge-base>/graph.kgraph");
                }
                steps.addObject().put("id", "ENRICHMENT").put("available", false)
                        .put("requires", "model-backed distributed crawl manager");
                catalog.set("steps", steps);
                catalog.set("pipelineTemplates", capabilities.remove("pipelineTemplates"));
                catalog.set("loaders", capabilities.remove("loaders"));
                catalog.set("chunkers", capabilities.remove("chunkers"));
                catalog.set("routing", capabilities.remove("routing"));
                catalog.put("executionMode", LocalCrawlSubprocessRunner.executionMode());
                catalog.set("requestShape", localRequestShape());
            }
            if (matches(section, "runtime")) {
                catalog.putObject("processingCapacity")
                        .put("backend", "project-local")
                        .put("synchronous", true)
                        .put("distributed", false)
                        .put("workers", 1)
                        .put("documentModelWorker", LocalModelPipelineRunner.documentModelWorkerAvailable())
                        .put("executionMode", LocalCrawlSubprocessRunner.executionMode());
                catalog.putObject("runtimeConfig")
                        .put("incrementalSources", true)
                        .put("incrementalGraph", true)
                        .put("storage", "data/crawls/<knowledge-base>")
                        .put("graphStorage", "data/crawls/<knowledge-base>/graph.kgraph")
                        .put("reasoning", "in-process UnifiedGraph query engine")
                        .put("embeddingTraining", "in-process TRANSE or ROTATE with portable model artifacts")
                        .put("memory", ".kompile/memory via memory and semantic_memory MCP tools");
            }
            if (matches(section, "knowledge_bases")) {
                catalog.set("knowledgeBases", listKnowledgeBases(project.root()));
            }
            if (matches(section, "code_projects")) {
                catalog.set("codeProjects", codeProjects(project));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("section", section);
            metadata.put("backend", "project-local");
            metadata.put("distributed", false);
            metadata.put("endpointsSucceeded", 0);
            metadata.put("endpointsFailed", 0);
            return ToolResult.success("crawl_discover (project-local)",
                    catalog.toPrettyString(), metadata);
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl discovery failed: " + message(e));
        }
    }

    public ToolResult control(JsonNode params, ToolContext context) {
        String operation = params.path("operation").asText("").trim()
                .toLowerCase(Locale.ROOT);
        if (operation.isBlank()) {
            return ToolResult.error("operation is required");
        }
        try {
            ProjectState project = project(context.getWorkingDirectory());
            String jobId = text(params, "jobId");
            switch (operation) {
                case "preflight":
                    return discover("all", context.getWorkingDirectory());
                case "start":
                    return startControlRequest(params, context);
                case "status":
                    if (jobId == null) {
                        ObjectNode active = mapper.createObjectNode();
                        active.put("backend", "project-local");
                        active.put("activeJobs", 0);
                        active.put("reason", "Local crawls execute synchronously.");
                        return ToolResult.success("crawl_status", active.toPrettyString());
                    }
                    return localJobResult("status", project.root(), jobId);
                case "list": {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("backend", "project-local");
                    result.put("activeJobs", 0);
                    result.set("jobs", listKnowledgeBases(project.root()));
                    return ToolResult.success("crawl_list", result.toPrettyString());
                }
                case "transcript":
                    if (jobId == null) {
                        return ToolResult.error("transcript requires jobId");
                    }
                    return localTranscript(project.root(), jobId);
                case "source_types":
                    return discover("sources", context.getWorkingDirectory());
                case "runtime_config":
                    return discover("runtime", context.getWorkingDirectory());
                case "graph_stats": {
                    LocalProjectGraphBackend.GraphStats stats =
                            graphBackend.stats(context.getWorkingDirectory(), jobId);
                    ObjectNode graph = mapper.createObjectNode();
                    graph.put("backend", "project-local");
                    graph.put("available", true);
                    graph.put("entities", stats.entities());
                    graph.put("relations", stats.relations());
                    graph.put("embeddingVectors", stats.embeddingVectors());
                    graph.put("graphPath", stats.graphPath());
                    return ToolResult.success("crawl_graph_stats", graph.toPrettyString());
                }
                case "cancel":
                case "retry":
                case "run_step":
                case "archive_step":
                case "clear_graph":
                    return ToolResult.error("crawl_control " + operation
                            + " is unavailable for synchronous project-local crawls. "
                            + "Configure a crawl manager for distributed lifecycle control.");
                default:
                    return ToolResult.error("Unknown operation: " + operation);
            }
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl_control " + operation
                    + " failed: " + message(e));
        }
    }

    public ToolResult search(String query, String knowledgeBase, int limit, Path workingDirectory) {
        try {
            ProjectState project = project(workingDirectory);
            ArrayNode bases = listKnowledgeBases(project.root());
            List<Path> selected = new ArrayList<>();
            for (JsonNode base : bases) {
                if (knowledgeBase == null || knowledgeBase.isBlank()
                        || matchesKnowledgeBase(base, knowledgeBase)) {
                    selected.add(Path.of(base.path("path").asText()));
                }
            }
            if (selected.isEmpty()) {
                return ToolResult.error("No project-local knowledge base matches '" + knowledgeBase
                        + "'. Call crawl_discover section=knowledge_bases.");
            }

            List<String> terms = queryTerms(query);
            List<SearchHit> hits = new ArrayList<>();
            for (Path directory : selected) {
                ObjectNode summary = readJson(directory.resolve("crawl-result.json"));
                Map<String, String> sources = documentSources(directory.resolve("documents.jsonl"));
                Path chunks = directory.resolve("chunks.jsonl");
                if (!Files.isRegularFile(chunks)) {
                    continue;
                }
                try (Stream<String> lines = Files.lines(chunks, StandardCharsets.UTF_8)) {
                    lines.filter(line -> !line.isBlank()).forEach(line -> {
                        try {
                            JsonNode chunk = mapper.readTree(line);
                            String content = chunk.path("text").asText("");
                            int score = lexicalScore(content, query, terms);
                            if (score > 0) {
                                String documentId = chunk.path("documentId").asText("");
                                hits.add(new SearchHit(
                                        summary.path("profileId").asText(directory.getFileName().toString()),
                                        sources.getOrDefault(documentId, documentId),
                                        documentId,
                                        chunk.path("chunkId").asText(""),
                                        content,
                                        score));
                            }
                        } catch (Exception ignored) {
                            // A malformed line does not make the rest of the KB unusable.
                        }
                    });
                }
            }

            hits.sort(Comparator.comparingInt(SearchHit::score).reversed()
                    .thenComparing(SearchHit::source)
                    .thenComparing(SearchHit::chunkId));
            int boundedLimit = Math.min(50, Math.max(1, limit));
            List<SearchHit> limitedHits = hits.size() > boundedLimit
                    ? new ArrayList<>(hits.subList(0, boundedLimit))
                    : hits;
            if (limitedHits.isEmpty()) {
                return ToolResult.success("knowledge_search: " + query,
                        "No project-local chunks matched. Knowledge bases searched: "
                                + selected.size() + ".",
                        Map.of("query", query, "resultCount", 0,
                                "backend", "project-local"));
            }

            int maxScore = Math.max(1, limitedHits.get(0).score());
            StringBuilder output = new StringBuilder("Project-local knowledge results\n\n");
            int index = 0;
            for (SearchHit hit : limitedHits) {
                index++;
                output.append("### ").append(index).append(". ").append(hit.source())
                        .append(" [").append(hit.knowledgeBase()).append("] (")
                        .append(String.format(Locale.ROOT, "%.2f",
                                (double) hit.score() / maxScore))
                        .append(")\n")
                        .append(hit.content().strip()).append("\n\n");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("query", query);
            metadata.put("resultCount", limitedHits.size());
            metadata.put("backend", "project-local");
            metadata.put("knowledgeBases", selected.stream()
                    .map(path -> path.getFileName().toString()).toList());
            return ToolResult.success("knowledge_search: " + query,
                    output.toString().strip(), metadata);
        } catch (Exception e) {
            return ToolResult.error("Project-local knowledge search failed: " + message(e));
        }
    }

    public ToolResult status(String knowledgeBase, Path workingDirectory) {
        try {
            ProjectState project = project(workingDirectory);
            ArrayNode bases = listKnowledgeBases(project.root());
            ArrayNode selected = mapper.createArrayNode();
            long sources = 0;
            long documents = 0;
            long chunks = 0;
            long graphEntities = 0;
            long graphRelations = 0;
            long embeddingVectors = 0;
            for (JsonNode base : bases) {
                if (knowledgeBase != null && !knowledgeBase.isBlank()
                        && !matchesKnowledgeBase(base, knowledgeBase)) {
                    continue;
                }
                selected.add(base);
                sources += base.path("sourceCount").asLong();
                documents += base.path("documentCount").asLong();
                chunks += base.path("chunkCount").asLong();
                graphEntities += base.path("graphEntityCount").asLong();
                graphRelations += base.path("graphRelationCount").asLong();
                embeddingVectors += base.path("embeddingVectorCount").asLong();
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("available", !selected.isEmpty());
            result.putArray("backends").add("project-local-text").add("project-local-kgraph");
            result.put("projectRoot", project.root().toString());
            result.put("knowledge_base_count", selected.size());
            result.put("sources_count", sources);
            result.put("documents_indexed", documents);
            result.put("chunks_indexed", chunks);
            result.put("graph_entities", graphEntities);
            result.put("graph_relations", graphRelations);
            result.put("embedding_vectors", embeddingVectors);
            result.set("knowledgeBases", selected);
            return ToolResult.success("knowledge_status", result.toPrettyString(),
                    Map.of("available", !selected.isEmpty(), "backend", "project-local",
                            "knowledgeBaseCount", selected.size(),
                            "documentsIndexed", documents, "chunksIndexed", chunks,
                            "graphEntities", graphEntities, "graphRelations", graphRelations,
                            "embeddingVectors", embeddingVectors));
        } catch (Exception e) {
            return ToolResult.error("Project-local knowledge status failed: " + message(e));
        }
    }

    private ToolResult startControlRequest(JsonNode params, ToolContext context) {
        JsonNode body = params.path("body");
        if (!body.isObject()) {
            body = params.path("request");
        }
        if (!body.isObject()) {
            return ToolResult.error("start requires body containing a UnifiedCrawlRequest.");
        }
        ObjectNode request = mapper.createObjectNode();
        if (body.hasNonNull("name")) {
            request.set("name", body.get("name"));
        }
        if (body.hasNonNull("factSheetName")) {
            request.putObject("knowledgeBase").put("name", body.path("factSheetName").asText());
        } else if (body.hasNonNull("factSheetId")) {
            request.putObject("knowledgeBase").put("id", body.path("factSheetId").asInt());
        }
        ArrayNode documents = request.putArray("documents");
        JsonNode sources = body.path("sources");
        if (sources.isArray()) {
            for (JsonNode source : sources) {
                String value = firstNonBlank(text(source, "pathOrUrl"), text(source, "path"));
                if (value == null) {
                    continue;
                }
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    return ToolResult.error("The local start backend cannot load URL source: " + value);
                }
                ObjectNode document = documents.addObject().put("path", value);
                copy(source, document, "label");
                copy(source, document, "includePatterns");
                copy(source, document, "excludePatterns");
                copy(source, document, "pipelineId");
                copy(source, document, "loaderName");
                copy(source, document, "chunkerName");
                copy(source, document, "chunkSize");
                copy(source, document, "chunkOverlap");
                copy(source, document, "chunkerOptions");
                copy(source, document, "allowedContentTypes");
            }
        }
        for (String field : List.of("steps", "pipelines", "routeRules", "runtimeConfig",
                "graphExtraction", "vectorIndex", "distribution", "deriveOntology",
                "defaultPipelineId", "strictSteps")) {
            copy(body, request, field);
        }
        return crawlDocuments(request, context);
    }

    private ToolResult localJobResult(String operation, Path root, String jobId) throws IOException {
        ObjectNode summary = readSummary(root, slug(stripLocalPrefix(jobId)));
        if (summary.isEmpty()) {
            return ToolResult.error("Unknown project-local crawl/knowledge base: " + jobId);
        }
        summary.put("backend", "project-local");
        summary.put("distributed", false);
        summary.put("jobId", slug(stripLocalPrefix(jobId)));
        return ToolResult.success("crawl_" + operation, summary.toPrettyString(),
                Map.of("backend", "project-local", "jobId", slug(stripLocalPrefix(jobId))));
    }

    private ToolResult localTranscript(Path root, String jobId) throws IOException {
        String id = slug(stripLocalPrefix(jobId));
        Path directory = root.resolve("data/crawls").resolve(id);
        ObjectNode result = mapper.createObjectNode();
        result.put("backend", "project-local");
        result.put("jobId", id);
        ObjectNode summary = readSummary(root, id);
        if (summary.isEmpty()) {
            return ToolResult.error("Unknown project-local crawl/knowledge base: " + jobId);
        }
        result.set("result", summary);
        Path request = directory.resolve("mcp-request.json");
        if (Files.isRegularFile(request)) {
            result.set("request", readJson(request));
        }
        result.put("message", "Local crawls are synchronous; request and result replace a worker transcript.");
        return ToolResult.success("crawl_transcript", result.toPrettyString());
    }

    private CodeProjectSelection selectCodeProjects(JsonNode selectors, ProjectState project) {
        if (selectors == null || selectors.isNull() || selectors.isEmpty()) {
            return new CodeProjectSelection(List.of(), null);
        }
        if (!selectors.isArray()) {
            return new CodeProjectSelection(List.of(), "codeProjects must be an array.");
        }
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (int i = 0; i < selectors.size(); i++) {
            JsonNode selector = selectors.get(i);
            if (!selector.isTextual() || selector.asText().isBlank()) {
                return new CodeProjectSelection(List.of(),
                        "codeProjects[" + i + "] must be a non-blank id or name.");
            }
            requested.add(selector.asText().trim().toLowerCase(Locale.ROOT));
        }
        boolean all = requested.remove("*");
        List<SelectedCodeProject> available = availableCodeProjects(project);
        List<SelectedCodeProject> selected = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        for (SelectedCodeProject candidate : available) {
            boolean match = all || candidate.aliases().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(requested::contains);
            if (match) {
                selected.add(candidate);
                candidate.aliases().stream().map(value -> value.toLowerCase(Locale.ROOT))
                        .filter(requested::contains).forEach(matched::add);
            }
        }
        requested.removeAll(matched);
        if (!requested.isEmpty()) {
            return new CodeProjectSelection(selected,
                    "Unknown or inactive Kompile codeProjects selectors: " + requested);
        }
        if (selected.isEmpty()) {
            return new CodeProjectSelection(List.of(),
                    "No active Kompile code projects matched codeProjects.");
        }
        return new CodeProjectSelection(selected, null);
    }

    private List<SelectedCodeProject> availableCodeProjects(ProjectState project) {
        List<SelectedCodeProject> projects = new ArrayList<>();
        if (project.manifest() != null && project.manifest().getCodingProjects() != null) {
            for (KompileCodingProject candidate : project.manifest().getCodingProjects()) {
                if (candidate.getLifecycle() != null
                        && !"ACTIVE".equalsIgnoreCase(candidate.getLifecycle().name())) {
                    continue;
                }
                String rootPath = firstNonBlank(candidate.getRootPath(), project.root().toString());
                Path root = Path.of(rootPath);
                if (!root.isAbsolute()) {
                    root = project.root().resolve(root);
                }
                List<String> aliases = new ArrayList<>();
                for (String alias : List.of(
                        firstNonBlank(candidate.getId(), ""),
                        firstNonBlank(candidate.getCodeProjectId(), ""),
                        firstNonBlank(candidate.getName(), ""))) {
                    if (!alias.isBlank()) {
                        aliases.add(alias);
                    }
                }
                if (aliases.isEmpty()) {
                    aliases.add(root.getFileName() != null
                            ? root.getFileName().toString() : "project");
                }
                projects.add(new SelectedCodeProject(root.toAbsolutePath().normalize(), aliases,
                        csv(candidate.getIncludePatterns()), csv(candidate.getExcludePatterns()),
                        firstNonBlank(candidate.getName(), aliases.get(0)),
                        firstNonBlank(candidate.getCodeProjectId(), candidate.getId(), aliases.get(0)),
                        candidate.getFactSheetId(), candidate));
            }
        }
        if (projects.isEmpty()) {
            String implicit = firstNonBlank(project.id(), project.name(),
                    project.root().getFileName() != null
                            ? project.root().getFileName().toString() : "project");
            projects.add(new SelectedCodeProject(project.root(), List.of(implicit, project.name()),
                    List.of(), DEFAULT_CODE_EXCLUDES, project.name(), implicit, null, null));
        }
        return projects;
    }

    private KnowledgeBaseRef alignKnowledgeBase(KnowledgeBaseRef knowledgeBase,
                                                CodeProjectSelection selection,
                                                boolean explicitlySelected) {
        LinkedHashSet<Long> bindings = new LinkedHashSet<>();
        selection.projects().stream().map(SelectedCodeProject::factSheetId)
                .filter(java.util.Objects::nonNull).forEach(bindings::add);
        if (bindings.size() > 1) {
            return new KnowledgeBaseRef(null, null, null,
                    "Selected code projects are bound to different fact sheets: " + bindings + ".");
        }
        Long bound = bindings.isEmpty() ? null : bindings.iterator().next();
        if (bound == null) {
            return knowledgeBase;
        }
        if (knowledgeBase.factSheetId() != null && !bound.equals(knowledgeBase.factSheetId())) {
            return new KnowledgeBaseRef(null, null, null,
                    "knowledgeBase.id " + knowledgeBase.factSheetId()
                            + " conflicts with code project factSheetId " + bound + ".");
        }
        if (!explicitlySelected) {
            return new KnowledgeBaseRef("kb-" + bound, "Knowledge base " + bound, bound, null);
        }
        return new KnowledgeBaseRef(knowledgeBase.id(), knowledgeBase.name(), bound, null);
    }

    private ArrayNode codeProjects(ProjectState project) {
        ArrayNode result = mapper.createArrayNode();
        for (SelectedCodeProject selected : availableCodeProjects(project)) {
            ObjectNode node = result.addObject();
            node.put("id", selected.aliases().get(0));
            node.put("name", selected.name());
            node.put("rootPath", selected.root().toString());
            node.put("lifecycle", "ACTIVE");
            node.put("backend", "project-local");
            node.put("codeProjectId", selected.codeProjectId());
            if (selected.factSheetId() != null) {
                node.put("factSheetId", selected.factSheetId());
            }
            ArrayNode aliases = node.putArray("aliases");
            selected.aliases().forEach(aliases::add);
            ArrayNode includes = node.putArray("includePatterns");
            selected.includePatterns().forEach(includes::add);
            ArrayNode excludes = node.putArray("excludePatterns");
            selected.excludePatterns().forEach(excludes::add);
        }
        return result;
    }

    private LinkedHashMap<String, ObjectNode> previousSourceConfigs(
            Path projectRoot,
            String knowledgeBaseId,
            ObjectNode previousSummary,
            Set<String> previousSources) throws IOException {
        LinkedHashMap<String, ObjectNode> result = new LinkedHashMap<>();
        Path persistedRequest = projectRoot.resolve("data/crawls")
                .resolve(knowledgeBaseId).resolve("mcp-request.json");
        ObjectNode previousRequest = Files.isRegularFile(persistedRequest)
                ? readJson(persistedRequest) : mapper.createObjectNode();
        JsonNode requestBody = previousRequest.path("request");
        JsonNode documents = requestBody.isObject() ? requestBody.get("documents") : null;
        if (documents != null && documents.isArray()) {
            for (JsonNode document : documents) {
                String value = text(document, "path");
                if (value == null) continue;
                try {
                    Path path = Path.of(value);
                    if (!path.isAbsolute()) path = projectRoot.resolve(path);
                    path = path.toAbsolutePath().normalize();
                    if (!previousSources.contains(path.toString()) || !Files.exists(path)) continue;
                    ObjectNode copy = mapper.createObjectNode().put("path", path.toString());
                    copyDocumentOptions(document, copy);
                    result.put(path.toString(), copy);
                } catch (Exception ignored) {
                    // mergePrevious already reports invalid or missing persisted sources.
                }
            }
        }
        for (String source : previousSources) {
            ObjectNode fallback = result.computeIfAbsent(source,
                    ignored -> mapper.createObjectNode().put("path", source));
            if (!fallback.hasNonNull("loaderName") && previousSummary.hasNonNull("loader")) {
                fallback.set("loaderName", previousSummary.get("loader").deepCopy());
            }
            if (!fallback.hasNonNull("chunkerName") && previousSummary.hasNonNull("chunker")) {
                fallback.set("chunkerName", previousSummary.get("chunker").deepCopy());
            }
        }
        return result;
    }

    private void copyDocumentOptions(JsonNode source, ObjectNode target) {
        for (String field : List.of("label", "sourceType", "pipelineId", "loaderName",
                "chunkerName", "chunkSize", "chunkOverlap", "chunkerOptions",
                "allowedContentTypes", "properties", "includePatterns", "excludePatterns")) {
            copy(source, target, field);
        }
    }

    private void mergePrevious(ObjectNode previous,
                               Set<String> sources,
                               Set<String> includePatterns,
                               Set<String> excludePatterns,
                               List<String> warnings) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        for (String source : strings(previous.get("sources"))) {
            try {
                if (Files.exists(Path.of(source))) {
                    sources.add(source);
                } else {
                    warnings.add("Dropped missing previous source " + source + ".");
                }
            } catch (Exception e) {
                warnings.add("Dropped invalid previous source " + source + ".");
            }
        }
        includePatterns.addAll(strings(previous.get("includePatterns")));
        excludePatterns.addAll(strings(previous.get("excludePatterns")));
    }

    private void addUnsupportedWarnings(JsonNode params, List<String> warnings) {
        List<String> configured = new ArrayList<>();
        for (String field : List.of("distribution", "hydration",
                "processingRoute", "preprocessing", "archivedSteps")) {
            if (params.hasNonNull(field)) {
                configured.add(field);
            }
        }
        if (!configured.isEmpty()) {
            warnings.add("The project-local backend ignored distributed-only configuration: "
                    + configured + ".");
        }
        JsonNode steps = params.get("steps");
        if (steps != null && steps.isArray()) {
            List<String> unsupported = new ArrayList<>();
            for (JsonNode step : steps) {
                String id = step.asText("").toUpperCase(Locale.ROOT);
                if (!LocalCrawlCapabilities.supportedSteps().contains(id)
                        && !Set.of("GRAPH_EXTRACTION", "VECTOR_INDEXING",
                        "ENTITY_RESOLUTION", "LEARNING").contains(id)) {
                    unsupported.add(id);
                }
            }
            if (!unsupported.isEmpty()) {
                warnings.add("Distributed-only steps were not run locally: " + unsupported + ".");
            }
        }
    }

    private ArrayNode listKnowledgeBases(Path root) throws IOException {
        ArrayNode result = mapper.createArrayNode();
        Path crawls = root.resolve("data/crawls");
        if (!Files.isDirectory(crawls)) {
            return result;
        }
        try (Stream<Path> directories = Files.list(crawls)) {
            List<Path> sorted = directories.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path directory : sorted) {
                Path summaryPath = directory.resolve("crawl-result.json");
                if (!Files.isRegularFile(summaryPath)) {
                    continue;
                }
                ObjectNode summary = readJson(summaryPath);
                ObjectNode item = result.addObject();
                item.put("id", directory.getFileName().toString());
                item.put("name", firstNonBlank(text(summary, "name"),
                        directory.getFileName().toString()));
                item.put("collection", firstNonBlank(text(summary, "collection"),
                        directory.getFileName().toString()));
                item.put("status", summary.path("status").asText("UNKNOWN"));
                item.put("finishedAt", summary.path("finishedAt").asText(""));
                item.put("sourceCount", summary.path("sources").size());
                item.put("documentCount", summary.path("documentCount").asInt());
                item.put("chunkCount", summary.path("chunkCount").asInt());
                item.put("graphEntityCount", summary.path("graphEntityCount").asInt());
                item.put("graphRelationCount", summary.path("graphRelationCount").asInt());
                item.put("codeEntityCount", summary.path("codeEntityCount").asInt());
                item.put("embeddingVectorCount", summary.path("embeddingVectorCount").asInt());
                item.put("embeddingAlgorithm", summary.path("embeddingAlgorithm").asText(""));
                if (summary.hasNonNull("factSheetId")) {
                    item.put("factSheetId", summary.path("factSheetId").asLong());
                }
                Path graphPath = directory.resolve(LocalProjectGraphBackend.GRAPH_FILE);
                if (Files.isRegularFile(graphPath)) {
                    item.put("graphPath", graphPath.toAbsolutePath().normalize().toString());
                }
                item.put("path", directory.toAbsolutePath().normalize().toString());
                item.put("backend", "project-local");
                item.put("distributed", false);
            }
        }
        return result;
    }

    private ProjectState project(Path workingDirectory) {
        Path working = workingDirectory.toAbsolutePath().normalize();
        Path root = store.findProjectRoot(working).orElse(working);
        KompileProjectManifest manifest = null;
        try {
            manifest = store.load(root);
        } catch (Exception ignored) {
            // Any code directory can act as an implicit project in offline mode.
        }
        String fallback = root.getFileName() != null ? root.getFileName().toString() : "project";
        String id = manifest != null
                ? firstNonBlank(manifest.getProjectId(), fallback) : fallback;
        String name = manifest != null
                ? firstNonBlank(manifest.getName(), id) : id;
        return new ProjectState(root, manifest, id, name);
    }

    private KnowledgeBaseRef knowledgeBase(JsonNode selected, ProjectState project) {
        if (selected != null && !selected.isNull() && !selected.isObject()) {
            return new KnowledgeBaseRef(null, null, null, "knowledgeBase must be an object.");
        }
        if (selected != null && selected.isObject()) {
            boolean hasId = selected.hasNonNull("id");
            String name = text(selected, "name");
            boolean hasName = name != null;
            if (hasId == hasName) {
                return new KnowledgeBaseRef(null, null, null,
                        "knowledgeBase must provide exactly one of id or name.");
            }
            if (hasName) {
                return new KnowledgeBaseRef(slug(name), name, null, null);
            }
            long id = selected.path("id").asLong(-1);
            if (id < 0) {
                return new KnowledgeBaseRef(null, null, null,
                        "knowledgeBase.id must be a non-negative integer.");
            }
            return new KnowledgeBaseRef("kb-" + id, "Knowledge base " + id, id, null);
        }
        String id = slug(firstNonBlank(project.id(), project.name(), "project")) + "-knowledge";
        return new KnowledgeBaseRef(id, project.name() + " knowledge", null, null);
    }

    private ObjectNode readSummary(Path root, String id) throws IOException {
        Path summary = root.resolve("data/crawls").resolve(slug(id))
                .resolve("crawl-result.json").normalize();
        if (!summary.startsWith(root) || !Files.isRegularFile(summary)) {
            return mapper.createObjectNode();
        }
        return readJson(summary);
    }

    private ObjectNode readJson(Path path) throws IOException {
        JsonNode value = mapper.readTree(path.toFile());
        return value != null && value.isObject()
                ? (ObjectNode) value : mapper.createObjectNode();
    }

    private void persistProjectGraphProfile(
            ProjectState project,
            KompileProjectCrawlProfile profile,
            KnowledgeBaseRef knowledgeBase,
            List<SelectedCodeProject> codeProjects,
            LocalProjectGraphBackend.GraphUpdate graphUpdate) throws IOException {
        if (project.manifest() == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (profile.getMetadata() != null) {
            metadata.putAll(profile.getMetadata());
        }
        metadata.put("graphPath", project.root().relativize(graphUpdate.graphPath()).toString());
        metadata.put("graphEntityCount", Integer.toString(graphUpdate.entities()));
        metadata.put("graphRelationCount", Integer.toString(graphUpdate.relations()));
        metadata.put("embeddingVectorCount", Integer.toString(graphUpdate.embeddingVectors()));
        if (knowledgeBase.factSheetId() != null) {
            metadata.put("factSheetId", Long.toString(knowledgeBase.factSheetId()));
        }
        profile.setMetadata(metadata);

        List<KompileProjectCrawlProfile> profiles = new ArrayList<>();
        if (project.manifest().getCrawlProfiles() != null) {
            profiles.addAll(project.manifest().getCrawlProfiles());
        }
        profiles.removeIf(candidate -> profile.getId().equals(candidate.getId()));
        profiles.add(profile);
        project.manifest().setCrawlProfiles(profiles);

        if (knowledgeBase.factSheetId() != null) {
            for (SelectedCodeProject selected : codeProjects) {
                if (selected.definition() != null) {
                    selected.definition().setFactSheetId(knowledgeBase.factSheetId());
                }
            }
        }
        store.save(project.root(), project.manifest());
    }

    private void persistRequest(Path outputDirectory, JsonNode params,
                                ProjectState project, KnowledgeBaseRef knowledgeBase) throws IOException {
        ObjectNode request = mapper.createObjectNode();
        request.put("backend", "project-local");
        request.put("distributed", false);
        request.put("executedAt", Instant.now().toString());
        request.put("projectRoot", project.root().toString());
        request.put("knowledgeBase", knowledgeBase.id());
        if (knowledgeBase.factSheetId() != null) {
            request.put("factSheetId", knowledgeBase.factSheetId());
        }
        request.set("request", params.deepCopy());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outputDirectory.resolve("mcp-request.json").toFile(), request);
    }

    private ObjectNode dryRunSummary(KompileProjectCrawlProfile profile,
                                     ProjectCrawlCommand.LocalCrawlExecution execution) {
        ObjectNode result = mapper.createObjectNode();
        result.put("profileId", profile.getId());
        result.put("name", profile.getName());
        result.put("status", "DRY_RUN");
        result.set("sources", mapper.valueToTree(profile.getSources()));
        result.put("collection", profile.getCollection());
        result.put("outputPath", execution.outputDirectory().toString());
        result.put("markdownPath", execution.markdownDirectory().toString());
        return result;
    }

    private Map<String, String> documentSources(Path path) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return result;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode document = mapper.readTree(line);
                    result.put(document.path("documentId").asText(""),
                            firstNonBlank(text(document, "relativePath"),
                                    text(document, "source"), "Unknown"));
                } catch (Exception ignored) {
                }
            });
        }
        return result;
    }

    private int lexicalScore(String content, String query, List<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        String phrase = query.toLowerCase(Locale.ROOT).strip();
        if (phrase.length() > 2 && normalized.contains(phrase)) {
            score += 8;
        }
        for (String term : terms) {
            int from = 0;
            while ((from = normalized.indexOf(term, from)) >= 0) {
                score++;
                from += term.length();
            }
        }
        return score;
    }

    private List<String> queryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (term.length() > 1) {
                terms.add(term);
            }
        }
        return new ArrayList<>(terms);
    }

    private boolean matchesKnowledgeBase(JsonNode candidate, String selector) {
        String normalized = selector.trim().toLowerCase(Locale.ROOT);
        for (String field : List.of("id", "name", "collection")) {
            String value = text(candidate, field);
            if (value != null && value.toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        String candidateId = candidate.path("id").asText();
        return slug(selector).equals(candidateId)
                || ("kb-" + normalized).equals(candidateId);
    }

    private ObjectNode localRequestShape() {
        ObjectNode shape = mapper.createObjectNode();
        shape.put("startTool", "crawl_documents");
        shape.put("documents", "documents=[{path, pipelineId?, loaderName?, chunkerName?, chunkSize?, chunkOverlap?, chunkerOptions?, includePatterns?, excludePatterns?}]");
        shape.put("pipelines", "pipelines=[{pipelineId,pipelineType,loaderName?,chunkerName?,chunkSize?,chunkOverlap?,options?,pipelineDefinition?,pipelineDefinitionPath?}]");
        shape.put("modelPipelineOptions", "vlmModel/modelId/modelSetId, outputFormat, generation/runtime limits, OCR model ids, table/layout flags, or an executable UnifiedPipelineDefinition");
        shape.put("routing", "document.pipelineId > routeRules > defaultPipelineId > automatic file routing");
        shape.put("codeProjects", "codeProjects=[id|name|*]");
        shape.put("knowledgeBase", "knowledgeBase={name:<string>} or {id:<number>}; repeated calls add sources");
        shape.put("execution", "synchronous subprocess; result status is COMPLETED");
        shape.put("graph", "portable incremental snapshot at data/crawls/<knowledge-base>/graph.kgraph");
        shape.put("embeddingTraining", "embeddingTraining={enabled?,algorithm:TRANSE|ROTATE,embeddingDim?,epochs?}");
        shape.put("retrieval", "knowledge_search query=... knowledgeBase=<name-or-id>");
        shape.put("reasoning", "graph_reasoning_query and graph_reason run in-process when no manager URL is configured");
        shape.put("portability", "graph_export and graph_import read/write .kgraph locally");
        shape.put("memory", "memory and semantic_memory remain available in the same stdio MCP session");
        return shape;
    }

    private void sourceType(ArrayNode target, String type, boolean available, String useWhen) {
        target.addObject().put("type", type).put("available", available).put("useWhen", useWhen);
    }

    private void localPipeline(ArrayNode target, String id, boolean available, String useWhen) {
        target.addObject().put("id", id).put("available", available).put("useWhen", useWhen);
    }

    private boolean matches(String requested, String candidate) {
        return "all".equals(requested) || candidate.equals(requested);
    }

    private void copy(JsonNode source, ObjectNode target, String field) {
        if (source.has(field) && !source.get(field).isNull()) {
            target.set(field, source.get(field).deepCopy());
        }
    }

    private List<String> strings(JsonNode value) {
        List<String> result = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode item : value) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText().trim());
                }
            }
        } else if (value != null && value.isTextual()) {
            result.addAll(csv(value.asText()));
        }
        return result;
    }

    private List<String> csv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private String stripLocalPrefix(String value) {
        return value != null && value.startsWith("local:") ? value.substring(6) : value;
    }

    private String slug(String value) {
        String result = firstNonBlank(value, "knowledge")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return result.isBlank() ? "knowledge" : result;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String message(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private record ProjectState(Path root, KompileProjectManifest manifest, String id, String name) {
    }

    private record KnowledgeBaseRef(String id, String name, Long factSheetId, String error) {
    }

    private record SelectedCodeProject(Path root,
                                       List<String> aliases,
                                       List<String> includePatterns,
                                       List<String> excludePatterns,
                                       String name,
                                       String codeProjectId,
                                       Long factSheetId,
                                       KompileCodingProject definition) {
    }

    private record CodeProjectSelection(List<SelectedCodeProject> projects, String error) {
    }

    private record SearchHit(String knowledgeBase,
                             String source,
                             String documentId,
                             String chunkId,
                             String content,
                             int score) {
    }
}
