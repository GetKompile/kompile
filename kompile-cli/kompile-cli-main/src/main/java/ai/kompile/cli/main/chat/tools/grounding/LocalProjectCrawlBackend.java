/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalCrawlCapabilities;
import ai.kompile.cli.main.project.LocalCrawlSubprocessRunner;
import ai.kompile.cli.main.project.LocalModelPipelineRunner;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import ai.kompile.cli.main.project.ProjectAutoDetection;
import ai.kompile.cli.main.project.ProjectCrawlCommand;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
    private static final int MAX_REMOTE_SOURCE_BYTES = 25 * 1024 * 1024;
    private static final List<String> DEFAULT_CODE_EXCLUDES = List.of(
            "**/.git/**", "**/.kompile/**", "**/target/**", "**/build/**",
            "**/.gradle/**", "**/.idea/**", "**/node_modules/**",
            "**/data/crawls/**", "**/data/markdown/**");
    private static final ConcurrentHashMap<String, ReentrantLock> CRAWL_LOCKS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final KompileProjectStore store;
    private final LocalProjectGraphBackend graphBackend;

    public LocalProjectCrawlBackend(ObjectMapper mapper) {
        this(mapper, new LocalProjectGraphBackend(mapper));
    }

    LocalProjectCrawlBackend(ObjectMapper mapper, LocalProjectGraphBackend graphBackend) {
        this.mapper = mapper;
        this.store = new KompileProjectStore();
        this.graphBackend = graphBackend;
    }

    public ToolResult crawlDocuments(JsonNode params, ToolContext context) {
        List<Path> temporarySources = new ArrayList<>();
        try {
            ProjectState project = dryRun(params)
                    ? project(context.getWorkingDirectory())
                    : ensureDirectoryProject(context.getWorkingDirectory());
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
                    Path resolved;
                    if (url != null) {
                        String label = firstNonBlank(text(document, "label"), url);
                        resolved = materializeRemoteSource(
                                url, label, project, knowledgeBase, dryRun(params));
                        if (dryRun(params)) {
                            temporarySources.add(resolved);
                        }
                    } else {
                        resolved = context.resolvePath(path).toAbsolutePath().normalize();
                    }
                    if (!Files.exists(resolved)) {
                        return ToolResult.error("Local crawl source does not exist: " + resolved);
                    }
                    sources.add(resolved.toString());
                    ObjectNode sourceConfig = mapper.createObjectNode().put("path", resolved.toString());
                    copyDocumentOptions(document, sourceConfig);
                    if (url != null) {
                        JsonNode configuredProperties = sourceConfig.get("properties");
                        ObjectNode properties = configuredProperties != null && configuredProperties.isObject()
                                ? (ObjectNode) configuredProperties
                                : mapper.createObjectNode();
                        properties.put("sourceUrl", url);
                        sourceConfig.set("properties", properties);
                    }
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

            JsonNode codeProjectSelectors = params.get("codeProjects");
            boolean noDocuments = documents == null || documents.isNull() || documents.isEmpty();
            boolean noCodeProjects = codeProjectSelectors == null
                    || codeProjectSelectors.isNull() || codeProjectSelectors.isEmpty();
            boolean folderBootstrap = noDocuments && noCodeProjects;
            if (folderBootstrap) {
                String folder = project.root().toString();
                sources.add(folder);
                sourceConfigs.putIfAbsent(folder, mapper.createObjectNode().put("path", folder));
                unrestrictedSource = true;
                excludePatterns.addAll(DEFAULT_CODE_EXCLUDES);
                codeProjectSelectors = mapper.createArrayNode().add(project.id());
            }
            CodeProjectSelection selectedProjects = selectCodeProjects(
                    codeProjectSelectors, project);
            if (selectedProjects.error() != null) {
                return ToolResult.error(selectedProjects.error());
            }
            for (SelectedCodeProject selected : selectedProjects.projects()) {
                if (!Files.exists(selected.root())) {
                    return ToolResult.error("Registered code project source does not exist: "
                            + selected.root());
                }
                sources.add(selected.root().toString());
                ObjectNode sourceConfig = sourceConfigs.computeIfAbsent(selected.root().toString(),
                        ignored -> mapper.createObjectNode().put("path", selected.root().toString()));
                if (!folderBootstrap) {
                    sourceConfig.put("pipelineId", LocalCrawlCapabilities.CODE_PIPELINE);
                }
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
            String projectPipelineError = registerProjectPipelines(executionRequest, project);
            if (projectPipelineError != null) {
                return ToolResult.error(projectPipelineError);
            }
            String validationError = LocalCrawlCapabilities.validationError(executionRequest);
            if (validationError != null) {
                return ToolResult.error(validationError);
            }
            String workerValidationError =
                    LocalModelPipelineRunner.validateWorkerConfiguration(project.root(), executionRequest);
            if (workerValidationError != null) {
                return ToolResult.error(workerValidationError);
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
                List<LocalProjectGraphBackend.CodeProjectSource> graphCodeProjects =
                        selectedProjects.projects().stream()
                                .map(selected -> new LocalProjectGraphBackend.CodeProjectSource(
                                        selected.root(), selected.codeProjectId(), selected.name(),
                                        selected.includePatterns(), selected.excludePatterns()))
                                .toList();
                LocalCrawlSubprocessRunner.GraphContext graphContext =
                        new LocalCrawlSubprocessRunner.GraphContext(
                                knowledgeBase.id(), knowledgeBase.name(), knowledgeBase.factSheetId(),
                                project.id(), graphCodeProjects);
                LocalCrawlSubprocessRunner.ExecutionResult lifecycle =
                        LocalCrawlSubprocessRunner.execute(
                                profile, project.root(), dryRun, executionRequest,
                                graphContext, mapper);
                ProjectCrawlCommand.LocalCrawlExecution execution = lifecycle.crawlExecution();
                LocalProjectGraphBackend.GraphUpdate graphUpdate = lifecycle.graphUpdate();
                if (!dryRun) {
                    persistRequest(execution.outputDirectory(), executionRequest, project, knowledgeBase);
                    if (graphUpdate != null) {
                        persistProjectGraphProfile(project, profile, knowledgeBase,
                                selectedProjects.projects(), graphUpdate);
                    }
                }
                ObjectNode summary = dryRun
                        ? dryRunSummary(profile, execution)
                        : readSummary(project.root(), knowledgeBase.id());
                String effectiveStatus = execution.status();
                if (graphUpdate != null && !graphUpdate.semanticExtractionErrors().isEmpty()
                        && !"FAILED".equals(effectiveStatus)) {
                    effectiveStatus = "COMPLETED_WITH_ERRORS";
                }
                summary.put("backend", "project-local");
                summary.put("distributed", false);
                summary.put("projectRoot", project.root().toString());
                summary.put("codeProjectId", project.id());
                summary.put("projectManifest", project.root().resolve("kompile.project.json").toString());
                summary.put("jobId", knowledgeBase.id());
                summary.put("status", effectiveStatus);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("jobId", knowledgeBase.id());
                metadata.put("status", effectiveStatus);
                metadata.put("backend", "project-local");
                metadata.put("distributed", false);
                metadata.put("executionMode", LocalCrawlSubprocessRunner.executionMode());
                metadata.put("projectRoot", project.root().toString());
                metadata.put("codeProjectId", project.id());
                metadata.put("projectManifest", project.root().resolve("kompile.project.json").toString());
                KompileCodingProject directoryProject =
                        findDirectoryCodingProject(project.manifest(), project.root());
                if (directoryProject != null && firstNonBlank(directoryProject.getMetadataPath()) != null) {
                    metadata.put("projectMetadata", project.root()
                            .resolve(directoryProject.getMetadataPath()).normalize().toString());
                }
                metadata.put("knowledgeBase", knowledgeBase.id());
                metadata.put("sourceCount", sources.size());
                metadata.put("addedDocumentSelectors", explicitDocuments);
                metadata.put("codeProjectCount", selectedProjects.projects().size());
                metadata.put("documentCount", execution.documentCount());
                metadata.put("chunkCount", execution.chunkCount());
                metadata.put("failedDocumentCount", execution.documentFailures().size());
                metadata.put("documentFailures", execution.documentFailures());
                if (knowledgeBase.factSheetId() != null) {
                    metadata.put("factSheetId", knowledgeBase.factSheetId());
                }
                if (graphUpdate != null) {
                    metadata.put("graphPath", graphUpdate.graphPath().toString());
                    metadata.put("graphEntityCount", graphUpdate.entities());
                    metadata.put("graphRelationCount", graphUpdate.relations());
                    metadata.put("codeEntityCount", graphUpdate.codeEntities());
                    metadata.put("semanticEntityCount", graphUpdate.semanticEntities());
                    metadata.put("semanticRelationCount", graphUpdate.semanticRelations());
                    metadata.put("semanticExtractionErrors", graphUpdate.semanticExtractionErrors());
                    metadata.put("entityResolutionEnabled", graphUpdate.entityResolutionEnabled());
                    metadata.put("entityResolutionMergedCount", graphUpdate.entitiesMerged());
                    metadata.put("entityResolutionTypeCorrectionCount",
                            graphUpdate.entityTypesCorrected());
                    metadata.put("entityResolutionIdentifierLinkCount",
                            graphUpdate.identifierLinksCreated());
                    metadata.put("embeddingVectorCount", graphUpdate.embeddingVectors());
                    if (graphUpdate.embeddingAlgorithm() != null) {
                    metadata.put("embeddingAlgorithm", graphUpdate.embeddingAlgorithm());
                    }
                    metadata.put("enrichmentRequested", graphUpdate.enrichmentRequested());
                    metadata.put("reasoningLearningEnabled",
                            graphUpdate.reasoningLearningEnabled());
                    metadata.put("folPslLearned", graphUpdate.folPslLearned());
                    metadata.put("mebnLearned", graphUpdate.mebnLearned());
                    metadata.put("reasoningModelsTrained",
                            graphUpdate.reasoningModelsTrained());
                    metadata.put("pslRuleCount", graphUpdate.pslRuleCount());
                    metadata.put("mebnFragmentCount", graphUpdate.mebnFragmentCount());
                }
                metadata.put("warnings", warnings);
                metadata.put("nextTools", List.of(
                        "knowledge_search", "knowledge_status", "crawl_control", "crawl_result",
                        "graph_reasoning_query", "graph_reason", "ask_graph_mebn", "graph_embeddings",
                        "graph_export", "graph_import", "memory"));
                CrawlResultHandle.from(summary, "project-local", knowledgeBase.id(),
                        knowledgeBase.id()).attachTo(metadata);

                StringBuilder output = new StringBuilder();
                output.append(dryRun ? "Project-local crawl preview complete."
                                : "FAILED".equals(effectiveStatus)
                                ? "Project-local crawl failed."
                                : "COMPLETED_WITH_ERRORS".equals(effectiveStatus)
                                ? "Project-local knowledge base updated with extraction errors."
                                : "Project-local knowledge base updated.")
                        .append(" Knowledge base: ").append(knowledgeBase.id()).append(". ")
                        .append("Sources: ").append(sources.size()).append(". ");
                if (!dryRun) {
                    output.append("Documents: ").append(execution.documentCount()).append(". ")
                            .append("Chunks: ").append(execution.chunkCount()).append(". ");
                    if (graphUpdate != null) {
                        output.append("Graph entities: ").append(graphUpdate.entities()).append(". ")
                                .append("Relations: ").append(graphUpdate.relations()).append(". ")
                                .append("Semantic entities: ").append(graphUpdate.semanticEntities()).append(". ")
                                .append("Semantic relations: ").append(graphUpdate.semanticRelations()).append(". ")
                                .append("Entity merges: ").append(graphUpdate.entitiesMerged()).append(". ")
                                .append("Entity type corrections: ")
                                .append(graphUpdate.entityTypesCorrected()).append(". ")
                                .append("Identifier links: ")
                                .append(graphUpdate.identifierLinksCreated()).append(". ")
                                .append("Embedding vectors: ").append(graphUpdate.embeddingVectors()).append(". ")
                                .append("FOL/PSL learned: ").append(graphUpdate.folPslLearned()).append(". ")
                                .append("MEBN learned: ").append(graphUpdate.mebnLearned()).append(". ");
                    }
                }
                if (!execution.documentFailures().isEmpty()) {
                    output.append(" Failed documents: ").append(execution.documentFailures().size()).append(".");
                    for (ProjectCrawlCommand.LocalCrawlFailure failure : execution.documentFailures()) {
                        output.append("\n- ")
                                .append(firstNonBlank(failure.relativePath(), failure.source(), failure.documentId()))
                                .append(": ").append(firstNonBlank(failure.message(), "Document extraction failed"));
                    }
                }
                if (graphUpdate != null && !graphUpdate.semanticExtractionErrors().isEmpty()) {
                    output.append(" Semantic extraction errors: ")
                            .append(graphUpdate.semanticExtractionErrors().size()).append(".");
                    for (String error : graphUpdate.semanticExtractionErrors()) {
                        output.append("\n- ").append(error);
                    }
                }
                output.append(" Backend: synchronous project-local MCP worker ("
                        + LocalCrawlSubprocessRunner.executionMode() + ").");
                if (!warnings.isEmpty()) {
                    output.append(" Warnings: ").append(String.join(" ", warnings));
                }
                output.append("\n").append(summary.toPrettyString());
                return new ToolResult("crawl_documents", output.toString(), metadata,
                        "FAILED".equals(effectiveStatus));
            } finally {
                lock.unlock();
                if (!lock.hasQueuedThreads()) {
                    CRAWL_LOCKS.remove(lockKey, lock);
                }
            }
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl failed: " + message(e));
        } finally {
            for (Path temporarySource : temporarySources) {
                try {
                    Files.deleteIfExists(temporarySource);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a dry-run download.
                }
            }
        }
    }

    public ToolResult crawlSource(JsonNode params, ToolContext context) {
        String path = text(params, "path");
        String url = text(params, "url");
        String inline = text(params, "text");
        boolean dryRun = params.path("dryRun").asBoolean(false);

        try {
            ObjectNode request = params.deepCopy();
            request.remove("path");
            request.remove("url");
            request.remove("text");
            request.remove("title");
            request.remove("factSheetId");
            request.put("dryRun", dryRun);
            String title = firstNonBlank(text(params, "title"), path, url, "inline knowledge");
            if (params.hasNonNull("factSheetId")) {
                request.putObject("knowledgeBase").put("id", params.path("factSheetId").asInt());
            }
            if (path != null) {
                request.putArray("documents").addObject().put("path", path).put("label", title);
                return crawlDocuments(request, context);
            }
            if (url != null) {
                request.putArray("documents").addObject().put("url", url).put("label", title);
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

            ProjectState project = ensureDirectoryProject(context.getWorkingDirectory());
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
            LocalModelPipelineRunner.DocumentModelWorkerStatus documentWorker =
                    LocalModelPipelineRunner.documentModelWorkerStatus(project.root(), null);
            ObjectNode catalog = mapper.createObjectNode();
            catalog.put("section", section);
            ObjectNode backend = catalog.putObject("backend")
                    .put("mode", "project-local")
                    .put("projectRoot", project.root().toString())
                    .put("codeProjectId", project.id())
                    .put("projectManifest", project.root().resolve("kompile.project.json").toString())
                    .put("distributed", false)
                    .put("coordination", "synchronous MCP-controlled crawl subprocess")
                    .put("executionMode", LocalCrawlSubprocessRunner.executionMode())
                    .put("autoConfigureDirectoryMetadataOnWrite", true);
            KompileCodingProject directoryProject =
                    findDirectoryCodingProject(project.manifest(), project.root());
            backend.put("directoryMetadataConfigured", directoryProject != null
                    && !requiresDirectoryProjectMetadata(project.root(), directoryProject));

            if (matches(section, "sources")) {
                ArrayNode types = catalog.putArray("sourceTypes");
                sourceType(types, "FILE", true, "One explicit local file.");
                sourceType(types, "DIRECTORY", true, "A local directory tree.");
                sourceType(types, "CODE_PROJECT", true,
                        "A code project registered in kompile.project.json, or the current directory.");
                sourceType(types, "URL", true,
                        "Fetched directly by the in-process MCP worker over HTTP or HTTPS.");
                sourceType(types, "INLINE_TEXT", true, "Use crawl_source text=... .");
            }
            if (matches(section, "pipelines")) {
                ObjectNode capabilities = LocalCrawlCapabilities.catalog(
                        mapper, LocalCrawlSubprocessRunner.executionMode(), documentWorker.available());
                ArrayNode pipelines = catalog.putArray("pipelineTypes");
                JsonNode builtinTypes = capabilities.path("pipelineRegistry").path("builtinPipelineTypes");
                if (builtinTypes.isArray()) {
                    for (JsonNode type : builtinTypes) {
                        localPipeline(pipelines, type.asText(), true, "Registered built-in crawl preset.");
                    }
                }
                localPipeline(pipelines, "*", true,
                        "Arbitrary portable pipeline type; execution is selected by its registered processor.");
                ArrayNode projectPipelines = projectPipelineDefaults(project);
                catalog.set("projectRegisteredPipelines", projectPipelines);
                for (JsonNode registered : projectPipelines) {
                    String type = registered.path("pipelineType").asText("CUSTOM");
                    boolean alreadyListed = false;
                    for (JsonNode existing : pipelines) {
                        if (type.equalsIgnoreCase(existing.path("id").asText())) {
                            alreadyListed = true;
                            break;
                        }
                    }
                    if (!alreadyListed) localPipeline(pipelines, type, true, "Project-registered crawl pipeline.");
                }
                ArrayNode steps = (ArrayNode) capabilities.remove("steps");
                steps.addObject().put("id", "GRAPH_EXTRACTION").put("available", true)
                        .put("backend", "project-local")
                        .put("engine", "GraphExtractionOrchestrator")
                        .put("configuration", "graphExtraction + processingRoute")
                        .put("modelExecution", "request-scoped CLI_AGENT or API_AGENT")
                        .put("artifact", "data/crawls/<knowledge-base>/graph.kgraph");
                for (String id : List.of("VECTOR_INDEXING", "ENTITY_RESOLUTION", "LEARNING")) {
                    steps.addObject().put("id", id).put("available", true)
                            .put("backend", "project-local")
                            .put("artifact", "data/crawls/<knowledge-base>/graph.kgraph");
                }
                steps.addObject().put("id", "ENRICHMENT").put("available", true)
                        .put("local", true)
                        .put("engine", "UnifiedGraphReasoningLifecycle")
                        .put("orchestration", "FinalGraphLearningResolutionPipeline")
                        .put("configuration", "steps + hydration + reasoningLearning + runtimeConfig")
                        .put("artifact", "data/crawls/<knowledge-base>/graph.kgraph");
                catalog.set("steps", steps);
                catalog.set("pipelineTemplates", capabilities.remove("pipelineTemplates"));
                catalog.set("loaders", capabilities.remove("loaders"));
                catalog.set("chunkers", capabilities.remove("chunkers"));
                catalog.set("routing", capabilities.remove("routing"));
                catalog.set("modelProcessing", capabilities.remove("modelProcessing"));
                catalog.put("executionMode", LocalCrawlSubprocessRunner.executionMode());
                catalog.set("requestShape", localRequestShape());
            }
            if (matches(section, "runtime")) {
                catalog.putObject("processingCapacity")
                        .put("backend", "project-local")
                        .put("synchronous", true)
                        .put("distributed", false)
                        .put("workers", 1)
                        .put("builtinDocumentProcessor", documentWorker.available())
                        .put("builtinDocumentProcessorSource", documentWorker.source())
                        .put("builtinDocumentProcessorExecutable",
                                firstNonBlank(documentWorker.executable(), "not configured"))
                        .put("registeredProjectPipelines", projectPipelineDefaults(project).size())
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
            if (matches(section, "models")) {
                catalog.set("models", mapper.valueToTree(
                        LocalProjectModelBootstrap.inventory(project.root())));
                boolean nativeChildren = CliProcessLauncher.requiresNativeChildren();
                catalog.putObject("modelRuntime")
                        .put("tool", "model_runtime")
                        .put("artifactMode", nativeChildren ? "native-only" : "jvm-development")
                        .put("artifactTiers", nativeChildren
                                ? "native child executables required"
                                : "native executable or packaged executable JAR")
                        .put("developmentClasspath", false)
                        .put("centralizedService", false)
                        .put("storage", "data/models")
                        .put("lifecycle", "request-scoped staging and serving subprocesses");
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

    public ToolResult result(String jobId, ToolContext context) {
        if (jobId == null || jobId.isBlank()) {
            return ToolResult.error("crawl_result requires jobId");
        }
        try {
            ProjectState project = project(context.getWorkingDirectory());
            return localJobResult("result", project.root(), jobId);
        } catch (Exception e) {
            return ToolResult.error("Project-local crawl_result failed: " + message(e));
        }
    }

    public ToolResult search(String query, String knowledgeBase, int limit, ToolContext context) {
        String selected = knowledgeBase;
        if (selected == null || selected.isBlank()) {
            selected = defaultKnowledgeBaseId(context.getWorkingDirectory());
            ToolResult bootstrap = ensureFolderKnowledgeBase(context);
            if (bootstrap.isError()) {
                return bootstrap;
            }
        }
        return search(query, selected, limit, context.getWorkingDirectory());
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

    public ToolResult status(String knowledgeBase, ToolContext context) {
        String selected = knowledgeBase;
        if (selected == null || selected.isBlank()) {
            selected = defaultKnowledgeBaseId(context.getWorkingDirectory());
            ToolResult bootstrap = ensureFolderKnowledgeBase(context);
            if (bootstrap.isError()) {
                return bootstrap;
            }
        }
        return status(selected, context.getWorkingDirectory());
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
        String normalizedJobId = slug(stripLocalPrefix(jobId));
        summary.put("jobId", normalizedJobId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("backend", "project-local");
        metadata.put("jobId", normalizedJobId);
        CrawlResultHandle.from(summary, "project-local", normalizedJobId,
                normalizedJobId).attachTo(metadata);
        return ToolResult.success("crawl_" + operation, summary.toPrettyString(), metadata);
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
                "executorId", "processor", "pipelineDefinitionId", "pipelineDefinitionPath",
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
        for (String field : List.of("distribution",
                "preprocessing", "archivedSteps")) {
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
                if (!LocalCrawlCapabilities.supportedSteps().contains(id)) {
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
            // Read-only discovery can describe an uninitialized directory without mutating it.
        }
        return projectState(root, manifest);
    }

    private ProjectState ensureDirectoryProject(Path workingDirectory) {
        ProjectState existing = project(workingDirectory);
        Path root = existing.root();
        KompileProjectManifest manifest = existing.manifest();
        if (manifest == null) {
            KompileProjectInitRequest request = new KompileProjectInitRequest();
            String name = root.getFileName() != null ? root.getFileName().toString() : "project";
            request.setName(name);
            request.setIncludeStandardComponents(false);
            request.setTags(List.of("auto-detected", "code", "directory-project", "local-stdio"));
            request.getCodingProjects().add(ProjectAutoDetection.buildDirectoryCodingProject(root));
            manifest = store.init(root, request);
        } else {
            KompileCodingProject directoryProject = findDirectoryCodingProject(manifest, root);
            if (directoryProject == null) {
                manifest = store.registerCodingProject(
                        root, ProjectAutoDetection.buildDirectoryCodingProject(root));
            } else if (requiresDirectoryProjectMetadata(root, directoryProject)) {
                manifest = store.registerCodingProject(root, directoryProject);
            }
        }
        return projectState(root, manifest);
    }

    private ProjectState projectState(Path root, KompileProjectManifest manifest) {
        String fallback = root.getFileName() != null ? root.getFileName().toString() : "project";
        KompileCodingProject directoryProject = findDirectoryCodingProject(manifest, root);
        String id = directoryProject == null
                ? fallback
                : firstNonBlank(directoryProject.getCodeProjectId(), directoryProject.getId(), fallback);
        String name = manifest == null
                ? id : firstNonBlank(manifest.getName(),
                directoryProject == null ? null : directoryProject.getName(), id);
        return new ProjectState(root, manifest, id, name);
    }

    private KompileCodingProject findDirectoryCodingProject(
            KompileProjectManifest manifest, Path projectRoot) {
        if (manifest == null || manifest.getCodingProjects() == null) return null;
        for (KompileCodingProject candidate : manifest.getCodingProjects()) {
            if (candidate == null || candidate.getLifecycle() != null
                    && !"ACTIVE".equalsIgnoreCase(candidate.getLifecycle().name())) {
                continue;
            }
            String configuredRoot = firstNonBlank(candidate.getRootPath());
            if (configuredRoot == null) continue;
            try {
                Path candidateRoot = Path.of(configuredRoot);
                if (!candidateRoot.isAbsolute()) candidateRoot = projectRoot.resolve(candidateRoot);
                if (projectRoot.equals(candidateRoot.toAbsolutePath().normalize())) return candidate;
            } catch (Exception ignored) {
                // Invalid external registrations do not replace the current directory identity.
            }
        }
        return null;
    }

    private boolean requiresDirectoryProjectMetadata(Path root, KompileCodingProject project) {
        if (firstNonBlank(project.getContextPath()) == null
                || firstNonBlank(project.getAgentsMdPath()) == null
                || firstNonBlank(project.getChatsPath()) == null
                || firstNonBlank(project.getMetadataPath()) == null
                || firstNonBlank(project.getIndexPath()) == null) {
            return true;
        }
        try {
            Path metadata = Path.of(project.getMetadataPath());
            if (!metadata.isAbsolute()) metadata = root.resolve(metadata);
            metadata = metadata.toAbsolutePath().normalize();
            return !metadata.startsWith(root)
                    || !Files.isRegularFile(metadata.resolve("project.json"))
                    || !Files.isRegularFile(metadata.resolve("index-plan.json"));
        } catch (Exception ignored) {
            return true;
        }
    }

    String defaultKnowledgeBaseId(Path workingDirectory) {
        ProjectState project = project(workingDirectory);
        return knowledgeBase(null, project).id();
    }

    ToolResult ensureFolderKnowledgeBase(ToolContext context) {
        ProjectState project = ensureDirectoryProject(context.getWorkingDirectory());
        String id = knowledgeBase(null, project).id();
        Path graph = project.root().resolve("data/crawls").resolve(id)
                .resolve(LocalProjectGraphBackend.GRAPH_FILE);
        if (Files.isRegularFile(graph)) {
            return ToolResult.success("knowledge_bootstrap",
                    "Using folder knowledge base " + id + ".",
                    Map.of("backend", "project-local", "knowledgeBase", id,
                            "projectRoot", project.root().toString(),
                            "graphPath", graph.toString(), "bootstrapped", false));
        }
        return crawlDocuments(mapper.createObjectNode(), context);
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
        metadata.put("entityResolutionMergedCount",
                Integer.toString(graphUpdate.entitiesMerged()));
        metadata.put("entityResolutionTypeCorrectionCount",
                Integer.toString(graphUpdate.entityTypesCorrected()));
        metadata.put("entityResolutionIdentifierLinkCount",
                Integer.toString(graphUpdate.identifierLinksCreated()));
        metadata.put("embeddingVectorCount", Integer.toString(graphUpdate.embeddingVectors()));
        metadata.put("reasoningLearningEnabled",
                Boolean.toString(graphUpdate.reasoningLearningEnabled()));
        metadata.put("folPslLearned", Boolean.toString(graphUpdate.folPslLearned()));
        metadata.put("mebnLearned", Boolean.toString(graphUpdate.mebnLearned()));
        metadata.put("reasoningModelsTrained",
                Integer.toString(graphUpdate.reasoningModelsTrained()));
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

    private String registerProjectPipelines(ObjectNode request, ProjectState project) {
        ArrayNode projectDefaults = projectPipelineDefaults(project);
        if (projectDefaults.isEmpty()) return null;
        JsonNode existing = request.get("pipelineRegistry");
        ObjectNode registry;
        if (existing == null || existing.isNull()) {
            registry = request.putObject("pipelineRegistry");
        } else if (existing.isObject()) {
            registry = (ObjectNode) existing;
        } else {
            return "pipelineRegistry must be an object.";
        }
        JsonNode defaultsValue = registry.get("defaults");
        ArrayNode defaults;
        if (defaultsValue == null || defaultsValue.isNull()) {
            defaults = registry.putArray("defaults");
        } else if (defaultsValue.isArray()) {
            defaults = (ArrayNode) defaultsValue;
        } else {
            return "pipelineRegistry.defaults must be an array.";
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode definition : defaults) {
            String id = text(definition, "pipelineId");
            if (id != null) ids.add(id);
        }
        JsonNode requestPipelines = request.get("pipelines");
        if (requestPipelines != null && requestPipelines.isArray()) {
            for (JsonNode definition : requestPipelines) {
                String id = text(definition, "pipelineId");
                if (id != null) ids.add(id);
            }
        }
        for (JsonNode definition : projectDefaults) {
            String id = text(definition, "pipelineId");
            if (id != null && ids.add(id)) defaults.add(definition.deepCopy());
        }
        return null;
    }

    private ArrayNode projectPipelineDefaults(ProjectState project) {
        ArrayNode result = mapper.createArrayNode();
        if (project == null || project.manifest() == null || project.manifest().getPipelines() == null) {
            return result;
        }
        for (KompileProjectPipeline pipeline : project.manifest().getPipelines()) {
            if (pipeline == null || !pipeline.isActive()) continue;
            String id = firstNonBlank(pipeline.getPipelineId(), pipeline.getId());
            if (id == null) continue;
            ObjectNode registered = result.addObject();
            registered.put("pipelineId", id);
            registered.put("displayName", firstNonBlank(pipeline.getName(), id));
            String pipelineType = pipeline.getMetadata() == null ? null
                    : firstNonBlank(pipeline.getMetadata().get("pipelineType"),
                    pipeline.getMetadata().get("type"));
            registered.put("pipelineType", firstNonBlank(pipelineType, "CUSTOM"));
            ObjectNode options = registered.putObject("options");
            options.put("projectRegistered", true);
            if (pipeline.getRole() != null) options.put("role", pipeline.getRole());
            if (pipeline.getVersion() != null) options.put("version", pipeline.getVersion());
            if (pipeline.getRegistryPath() != null) options.put("registryPath", pipeline.getRegistryPath());
            if (pipeline.getModelRefs() != null && !pipeline.getModelRefs().isEmpty()) {
                ArrayNode refs = options.putArray("modelRefs");
                pipeline.getModelRefs().forEach(refs::add);
            }
            if (pipeline.getMetadata() != null) {
                pipeline.getMetadata().forEach(options::put);
                copyMetadataField(pipeline, registered, "loaderName");
                copyMetadataField(pipeline, registered, "chunkerName");
            }
            ObjectNode processor = registered.putObject("processor");
            processor.put("type", "UNIFIED_PIPELINE");
            if (firstNonBlank(pipeline.getDefinitionPath()) != null) {
                processor.put("pipelineDefinitionPath", pipeline.getDefinitionPath());
            } else if (firstNonBlank(pipeline.getRegistryPath()) != null
                    && pipeline.getRegistryPath().endsWith(".json")) {
                processor.put("pipelineDefinitionPath", pipeline.getRegistryPath());
            } else {
                processor.put("pipelineDefinitionId", id);
            }
            processor.put("registeredBy", "kompile.project.json");
        }
        return result;
    }

    private void copyMetadataField(KompileProjectPipeline pipeline, ObjectNode target, String field) {
        String value = pipeline.getMetadata().get(field);
        if (value != null && !value.isBlank()) target.put(field, value);
    }

    private ObjectNode localRequestShape() {
        ObjectNode shape = mapper.createObjectNode();
        shape.put("startTool", "crawl_documents");
        shape.put("documents", "documents=[{path|url, pipelineId?, loaderName?, chunkerName?, chunkSize?, chunkOverlap?, chunkerOptions?, includePatterns?, excludePatterns?}]");
        shape.put("pipelines", "pipelines=[{pipelineId,pipelineType:any-portable-id,registeredPipelineId?,executorId?,processor?,loaderName?,chunkerName?,options?}]");
        shape.put("pipelineRegistry", "pipelineRegistry={defaults:[ingest pipeline defaults], definitions:[UnifiedPipelineDefinition], executors:[{executorId,type:UNIFIED_PIPELINE|KOMPILE_SUBPROCESS|EXECUTABLE,...}]} ; active kompile.project.json pipelines are registered automatically");
        shape.put("pipelineExecution", "processor definitions select request-scoped one-shot unified pipeline serving, a Kompile --subprocess mode, or another executable; VLM/OCR are compatibility presets, not privileged executor types");
        shape.put("runtimeConfig", "generic crawl/runtime tuning; legacy documentModelExecutable aliases remain accepted for the registered vlm-test preset");
        shape.put("modelRuntime", "modelRuntime={autoBootstrap?,localPath?,source?,repository?,revision?,format?,type?,stagingExecutable?,stagingJar?,servingExecutable?,servingJar?,javaExecutable?,heapSize?,timeoutMinutes?,environment?}; native parents require native staging/serving children; executable JARs are JVM-development-only");
        shape.put("routing", "document.pipelineId > routeRules > defaultPipelineId > automatic file routing");
        shape.put("codeProjects", "omit to use and auto-configure the current directory code project; codeProjects=[id|name|*] is an explicit opt-in for additional manifest registrations");
        shape.put("knowledgeBase", "knowledgeBase={name:<string>} or {id:<number>}; repeated calls add sources");
        shape.put("execution", "synchronous local subprocess; result status is COMPLETED, COMPLETED_WITH_ERRORS, or FAILED");
        shape.put("graph", "portable incremental snapshot at data/crawls/<knowledge-base>/graph.kgraph");
        shape.put("embeddingTraining", "embeddingTraining={enabled?,algorithm:TRANSE|ROTATE,embeddingDim?,epochs?}");
        shape.put("reasoningLearning", "reasoningLearning={enabled?,pslSteps?,mebnEpochs?,consensusRounds?,consensusWeight?,maxRelationTypes?}; FOL/PSL/MEBN artifacts are stored in graph.kgraph");
        shape.put("retrieval", "knowledge_search query=... knowledgeBase=<name-or-id>");
        shape.put("reasoning", "graph_reasoning_query, graph_reason, and ask_graph_mebn query the folder .kgraph directly when no manager URL is configured");
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

    private Path materializeRemoteSource(String rawUrl,
                                         String label,
                                         ProjectState project,
                                         KnowledgeBaseRef knowledgeBase,
                                         boolean temporary) throws Exception {
        URI uri = URI.create(rawUrl);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Project-local URL crawl supports only http and https: " + rawUrl);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "text/html,application/xhtml+xml,application/pdf,text/plain,text/markdown,application/json,*/*;q=0.5")
                .header("User-Agent", "Kompile-MCP/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response = RemoteHttpClientHolder.CLIENT.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("URL crawl returned HTTP " + response.statusCode() + " for " + rawUrl);
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declaredLength > MAX_REMOTE_SOURCE_BYTES) {
            response.body().close();
            throw new IOException("URL crawl source exceeds the 25 MiB in-process limit: " + rawUrl);
        }

        byte[] content;
        try (InputStream input = response.body()) {
            content = input.readNBytes(MAX_REMOTE_SOURCE_BYTES + 1);
        }
        if (content.length > MAX_REMOTE_SOURCE_BYTES) {
            throw new IOException("URL crawl source exceeds the 25 MiB in-process limit: " + rawUrl);
        }
        if (content.length == 0) {
            throw new IOException("URL crawl returned an empty response: " + rawUrl);
        }

        String suffix = remoteSuffix(uri, response.headers().firstValue("Content-Type").orElse(""));
        Path destination;
        if (temporary) {
            destination = Files.createTempFile("kompile-url-crawl-", suffix);
        } else {
            Path sourceDirectory = project.root().resolve("data/knowledge-sources")
                    .resolve(knowledgeBase.id()).normalize();
            if (!sourceDirectory.startsWith(project.root())) {
                throw new IOException("Remote knowledge source escapes the project root.");
            }
            Files.createDirectories(sourceDirectory);
            String stem = slug(firstNonBlank(label, uri.getHost(), "remote-source"));
            if (stem.length() > 80) {
                stem = stem.substring(0, 80);
            }
            destination = sourceDirectory.resolve(stem + "-"
                    + Integer.toUnsignedString(rawUrl.hashCode(), 16) + suffix);
        }
        Files.write(destination, content);
        return destination.toAbsolutePath().normalize();
    }

    private String remoteSuffix(URI uri, String contentType) {
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String suffix = name.substring(dot).toLowerCase(Locale.ROOT);
                if (suffix.matches("\\.[a-z0-9]{1,8}")) {
                    return suffix;
                }
            }
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("html")) return ".html";
        if (normalized.contains("pdf")) return ".pdf";
        if (normalized.contains("markdown")) return ".md";
        if (normalized.contains("json")) return ".json";
        return ".txt";
    }

    private boolean dryRun(JsonNode params) {
        return params.path("dryRun").asBoolean(false);
    }

    private static final class RemoteHttpClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
