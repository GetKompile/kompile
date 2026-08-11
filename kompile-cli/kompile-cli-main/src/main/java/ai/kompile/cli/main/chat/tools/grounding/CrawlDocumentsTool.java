/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.web.client.ResourceAccessException;

/**
 * Agent-facing launcher for selected-document unified crawls.
 *
 * <p>The tool deliberately exposes document selection as a first-class field while forwarding the
 * server-owned {@code UnifiedCrawlRequest} configuration. This keeps agents out of raw controller
 * payloads without hiding advanced pipeline, routing, graph, vector, runtime, or distribution
 * options.</p>
 */
public final class CrawlDocumentsTool implements CliTool {
    private static final String START_PATH = "/api/unified-crawl/start";
    private static final String CODE_PROJECTS_PATH = "/api/projects/current/code-projects";
    private static final String CODE_PIPELINE_ID = "kompile-code-project";
    private static final List<String> CODE_EXTENSIONS = List.of(
            ".java", ".kt", ".kts", ".groovy", ".scala",
            ".py", ".js", ".jsx", ".ts", ".tsx",
            ".go", ".rs", ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp",
            ".cu", ".cuh", ".cs", ".fs", ".rb", ".php", ".swift",
            ".m", ".mm", ".sh", ".bash", ".zsh", ".sql", ".proto");
    private static final List<String> DEFAULT_CODE_EXCLUDES = List.of(
            "**/.git/**", "**/.kompile/**", "**/target/**", "**/build/**",
            "**/.gradle/**", "**/.idea/**", "**/node_modules/**", "**/data/**");

    private final GroundingBackendClient client;
    private final ObjectMapper mapper;
    private final LocalProjectCrawlBackend localBackend;

    public CrawlDocumentsTool(String baseUrl, ObjectMapper mapper) {
        this(new GroundingBackendClient(baseUrl), mapper);
    }

    CrawlDocumentsTool(GroundingBackendClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
        this.localBackend = new LocalProjectCrawlBackend(mapper);
    }

    @Override
    public String id() {
        return "crawl_documents";
    }

    @Override
    public String description() {
        return "Add explicit documents and/or registered Kompile code projects to a knowledge base. "
                + "With a configured crawl manager this schedules an asynchronous distributed crawl; "
                + "otherwise the same MCP call updates a synchronous project-local knowledge base. Each document may select a named pipeline, "
                + "loader, chunker, limits, filters, and properties. The request can also configure custom "
                + "pipelines, routing rules, graph extraction, chunking, vector indexing, runtime, "
                + "hydration, distribution, enabled/archived steps, and ontology derivation. Call "
                + "crawl_discover first to inspect live source types, steps, loaders, chunkers, "
                + "pipeline kinds, backends, runtime settings, knowledge bases, and code projects.";
    }

    @Override
    public String compactHint() {
        return "Crawl documents=[{path|url,...}] and/or codeProjects=[id|name|*] into knowledgeBase={id|name}. "
                + "Call crawl_discover first; local runs complete synchronously.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ArrayNode anyOf = schema.putArray("anyOf");
        anyOf.addObject().putArray("required").add("documents");
        anyOf.addObject().putArray("required").add("codeProjects");
        ObjectNode props = schema.putObject("properties");

        props.putObject("name")
                .put("type", "string")
                .put("description", "Optional human-readable crawl job name.");

        ObjectNode documents = props.putObject("documents");
        documents.put("type", "array");
        documents.put("minItems", 1);
        documents.put("description", "Exact files, directories, or URLs to load.");
        ObjectNode document = documents.putObject("items");
        document.put("type", "object");
        ObjectNode documentProps = document.putObject("properties");
        documentProps.putObject("path").put("type", "string")
                .put("description", "Server-visible file or directory path.");
        documentProps.putObject("url").put("type", "string")
                .put("description", "HTTP/HTTPS document or crawl seed URL.");
        documentProps.putObject("label").put("type", "string");
        documentProps.putObject("sourceType").put("type", "string")
                .put("description", "Optional source type override such as FILE, DIRECTORY, URL, WEB_CRAWL, or S3.");
        documentProps.putObject("maxDepth").put("type", "integer").put("minimum", 0);
        documentProps.putObject("maxDocuments").put("type", "integer").put("minimum", 0);
        addStringArray(documentProps, "includePatterns", "URL/path patterns to include.");
        addStringArray(documentProps, "excludePatterns", "URL/path patterns to exclude.");
        addStringArray(documentProps, "allowedContentTypes", "Accepted MIME types.");
        documentProps.putObject("pipelineId").put("type", "string")
                .put("description", "Optional named pipeline override for this document.");
        documentProps.putObject("loaderName").put("type", "string");
        documentProps.putObject("chunkerName").put("type", "string");
        documentProps.putObject("chunkSize").put("type", "integer").put("minimum", 1);
        documentProps.putObject("chunkOverlap").put("type", "integer").put("minimum", 0);
        documentProps.putObject("properties").put("type", "object");
        documentProps.putObject("chunkerOptions").put("type", "object");
        ArrayNode oneOf = document.putArray("oneOf");
        oneOf.addObject().putArray("required").add("path");
        oneOf.addObject().putArray("required").add("url");

        ObjectNode codeProjects = props.putObject("codeProjects");
        codeProjects.put("type", "array");
        codeProjects.put("minItems", 1);
        codeProjects.putObject("items").put("type", "string");
        codeProjects.put("description", "Registered Kompile code-project ids or names to crawl. "
                + "Use '*' to include every ACTIVE project discovered from kompile.project.json.");

        ObjectNode knowledgeBase = props.putObject("knowledgeBase");
        knowledgeBase.put("type", "object");
        knowledgeBase.put("description",
                "Target knowledge base/fact sheet. Supply exactly one of id or name; omit to use the active default.");
        ObjectNode kbProps = knowledgeBase.putObject("properties");
        kbProps.putObject("id").put("type", "integer").put("minimum", 0);
        kbProps.putObject("name").put("type", "string");

        ObjectNode embeddingTraining = props.putObject("embeddingTraining");
        embeddingTraining.put("type", "object");
        embeddingTraining.put("description",
                "Optional post-enrichment KGE training. Persisted models and vectors are included in .kgraph exports.");
        ObjectNode embeddingProps = embeddingTraining.putObject("properties");
        embeddingProps.putObject("enabled").put("type", "boolean");
        embeddingProps.putObject("algorithm").put("type", "string")
                .putArray("enum").add("TRANSE").add("ROTATE");
        embeddingProps.putObject("embeddingDim").put("type", "integer").put("minimum", 1);
        embeddingProps.putObject("epochs").put("type", "integer").put("minimum", 1);
        embeddingProps.putObject("batchSize").put("type", "integer").put("minimum", 1);
        embeddingProps.putObject("warmStartEpochs").put("type", "integer").put("minimum", 0);

        addStringArray(props, "steps",
                "Pipeline steps to run. Dependencies are resolved by the server; omit to run all.");
        addStringArray(props, "archivedSteps",
                "Steps to archive for deferred execution.");
        props.putObject("strictSteps").put("type", "boolean")
                .put("description", "Honor selected steps without force-adding the legacy graph spine.");
        props.putObject("deriveOntology").put("type", "boolean");
        props.putObject("defaultPipelineId").put("type", "string");
        props.putObject("maxValidationRetries").put("type", "integer").put("minimum", 0);

        addObjectArray(props, "pipelines",
                "Named ingest pipeline definitions. Types: STANDARD_TEXT, VLM, OCR, CODE, "
                        + "TABLE_AWARE, KEYWORD_ONLY, CUSTOM. Model-backed definitions accept modelId/vlmModel, "
                        + "generation/OCR/table options, or pipelineDefinition/pipelineDefinitionPath for an "
                        + "executable UnifiedPipelineDefinition.");
        addObjectArray(props, "routeRules",
                "Content routing rules that select a pipeline for matching documents.");

        for (String field : List.of(
                "graphExtraction", "chunking", "vectorIndex", "processingRoute",
                "runtimeConfig", "preprocessing", "hydration", "distribution")) {
            props.putObject(field).put("type", "object")
                    .put("description", "UnifiedCrawlRequest." + field + " configuration.");
        }
        props.putObject("config").put("type", "object")
                .put("description", "Advanced pass-through UnifiedCrawlRequest fields. documents always "
                        + "replace config.sources; explicit top-level fields and knowledgeBase override config.");

        return schema;
    }

    @Override
    public String permissionKey() {
        return "crawl_documents";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Start a selected-document crawl");

        JsonNode documents = params.get("documents");
        if (documents != null && !documents.isNull() && !documents.isArray()) {
            return ToolResult.error("documents must be an array.");
        }
        JsonNode codeProjects = params.get("codeProjects");
        if (codeProjects != null && !codeProjects.isNull() && !codeProjects.isArray()) {
            return ToolResult.error("codeProjects must be an array.");
        }
        boolean hasDocuments = documents != null && documents.isArray() && !documents.isEmpty();
        boolean hasCodeProjects = codeProjects != null && codeProjects.isArray() && !codeProjects.isEmpty();
        if (!hasDocuments && !hasCodeProjects) {
            return ToolResult.error("Provide at least one document or registered codeProjects selector.");
        }
        JsonNode config = params.get("config");
        if (config != null && !config.isNull() && !config.isObject()) {
            return ToolResult.error("config must be an object.");
        }
        if (!client.isAvailable()) {
            return localBackend.crawlDocuments(params, context);
        }

        try {
            ObjectNode request = config != null && config.isObject()
                    ? (ObjectNode) config.deepCopy()
                    : mapper.createObjectNode();
            request.remove("sources");

            String name = text(params, "name");
            if (name != null) {
                request.put("name", name);
            } else if (!request.hasNonNull("name") || request.path("name").asText("").isBlank()) {
                request.put("name", "Agent document crawl");
            }

            String validationError = applyKnowledgeBase(params.get("knowledgeBase"), request);
            if (validationError != null) {
                return ToolResult.error(validationError);
            }

            ArrayNode sources = mapper.createArrayNode();
            for (int i = 0; hasDocuments && i < documents.size(); i++) {
                JsonNode selected = documents.get(i);
                if (!selected.isObject()) {
                    return ToolResult.error("documents[" + i + "] must be an object.");
                }
                String path = text(selected, "path");
                String url = text(selected, "url");
                if ((path == null) == (url == null)) {
                    return ToolResult.error("documents[" + i + "] must provide exactly one of path or url.");
                }
                ObjectNode source = sources.addObject();
                String pathOrUrl = path != null ? path : url;
                String sourceType = text(selected, "sourceType");
                if (sourceType == null) {
                    sourceType = url != null ? "URL" : "FILE";
                } else {
                    sourceType = sourceType.toUpperCase(Locale.ROOT).replace('-', '_');
                }
                source.put("pathOrUrl", pathOrUrl);
                source.put("sourceType", sourceType);
                source.put("label", text(selected, "label") != null ? text(selected, "label") : pathOrUrl);
                source.put("maxDepth", selected.has("maxDepth")
                        ? selected.path("maxDepth").asInt()
                        : ("DIRECTORY".equals(sourceType) ? 3 : 0));
                source.put("maxDocuments", selected.has("maxDocuments")
                        ? selected.path("maxDocuments").asInt()
                        : ("DIRECTORY".equals(sourceType) ? 0 : 1));
                copyIfPresent(selected, source, "includePatterns");
                copyIfPresent(selected, source, "excludePatterns");
                copyIfPresent(selected, source, "allowedContentTypes");
                copyIfPresent(selected, source, "properties");
                copyIfPresent(selected, source, "pipelineId");
                copyIfPresent(selected, source, "loaderName");
                copyIfPresent(selected, source, "chunkerName");
                copyIfPresent(selected, source, "chunkSize");
                copyIfPresent(selected, source, "chunkOverlap");
                copyIfPresent(selected, source, "chunkerOptions");
            }
            CodeProjectResolution codeProjectResolution = resolveCodeProjects(codeProjects, sources);
            if (codeProjectResolution.error() != null) {
                return ToolResult.error(codeProjectResolution.error());
            }
            String bindingError = alignCodeProjectFactSheet(request, sources);
            if (bindingError != null) {
                return ToolResult.error(bindingError);
            }
            request.set("sources", sources);

            String arrayError = copyArrayOverride(params, request, "steps", "enabledSteps");
            if (arrayError != null) {
                return ToolResult.error(arrayError);
            }
            arrayError = copyArrayOverride(params, request, "archivedSteps", "archivedSteps");
            if (arrayError != null) {
                return ToolResult.error(arrayError);
            }
            arrayError = copyArrayOverride(params, request, "pipelines", "pipelines");
            if (arrayError != null) {
                return ToolResult.error(arrayError);
            }
            arrayError = copyArrayOverride(params, request, "routeRules", "routeRules");
            if (arrayError != null) {
                return ToolResult.error(arrayError);
            }

            for (String field : List.of(
                    "strictSteps", "deriveOntology", "defaultPipelineId", "maxValidationRetries",
                    "graphExtraction", "chunking", "vectorIndex", "processingRoute",
                    "runtimeConfig", "preprocessing", "hydration", "distribution")) {
                copyIfPresent(params, request, field);
            }
            String embeddingError = applyEmbeddingTraining(params.get("embeddingTraining"), request);
            if (embeddingError != null) {
                return ToolResult.error(embeddingError);
            }
            if (codeProjectResolution.added() > 0) {
                String defaultsError = applyCodeProjectDefaults(request);
                if (defaultsError != null) {
                    return ToolResult.error(defaultsError);
                }
            }

            GroundingBackendClient.GroundingResponse response = client.post(
                    START_PATH, mapper.writeValueAsString(request), Duration.ofSeconds(60));
            if (response.statusCode() >= 400) {
                return ToolResult.error("crawl_documents failed (HTTP " + response.statusCode()
                        + "): " + extractError(response.body()));
            }

            JsonNode body = mapper.readTree(response.body());
            String jobId = body.path("jobId").asText("");
            String status = body.path("status").asText("UNKNOWN");
            long factSheetId = body.path("factSheetId").asLong(0);
            int sourceCount = body.path("sourceCount").asInt(sources.size());
            List<String> bindingWarnings = body.hasNonNull("factSheetId")
                    ? bindCodeProjectsToFactSheet(sources, factSheetId)
                    : List.of();

            StringBuilder output = new StringBuilder("Selected-document crawl ")
                    .append(status.toLowerCase(Locale.ROOT)).append(".");
            if (!jobId.isBlank()) {
                output.append(" Job: ").append(jobId)
                        .append(". Use crawl_control operation=status with this jobId to monitor it.");
            }
            output.append(" Sources: ").append(sourceCount).append(".");
            if (body.hasNonNull("factSheetId")) {
                output.append(" Knowledge base/fact sheet: ").append(factSheetId).append(".");
            }
            if (codeProjectResolution.added() > 0) {
                output.append(" Kompile code projects: ").append(codeProjectResolution.added())
                        .append(" (incremental CODE pipeline).");
            }
            if (body.hasNonNull("message")) {
                output.append(" ").append(body.path("message").asText());
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("jobId", jobId);
            metadata.put("status", status);
            metadata.put("sourceCount", sourceCount);
            metadata.put("codeProjectCount", codeProjectResolution.added());
            metadata.put("factSheetId", factSheetId);
            metadata.put("codeProjectFactSheetBindings", bindingWarnings.isEmpty() ? "updated" : "partial");
            if (!bindingWarnings.isEmpty()) {
                metadata.put("bindingWarnings", bindingWarnings);
            }
            metadata.put("scheduled", body.path("scheduled").asBoolean(false));
            metadata.put("nextTools", List.of(
                    "crawl_control", "local_code_index", "code_graph", "knowledge_graph",
                    "graph_embeddings", "graph_reason", "graph_reasoning_query",
                    "graph_import", "graph_export"));
            return ToolResult.success("crawl_documents", output.toString(), metadata);
        } catch (ResourceAccessException e) {
            return localBackend.crawlDocuments(params, context);
        } catch (Exception e) {
            return ToolResult.error("crawl_documents error: " + e.getMessage());
        }
    }

    private CodeProjectResolution resolveCodeProjects(JsonNode selectors, ArrayNode sources) {
        if (selectors == null || selectors.isNull() || selectors.isEmpty()) {
            return new CodeProjectResolution(0, null);
        }

        Set<String> requested = new LinkedHashSet<>();
        for (int i = 0; i < selectors.size(); i++) {
            JsonNode selector = selectors.get(i);
            if (!selector.isTextual() || selector.asText().isBlank()) {
                return new CodeProjectResolution(0,
                        "codeProjects[" + i + "] must be a non-blank registered id or name.");
            }
            requested.add(selector.asText().trim());
        }
        boolean selectAll = requested.remove("*");

        try {
            GroundingBackendClient.GroundingResponse response = client.get(CODE_PROJECTS_PATH);
            if (response.statusCode() >= 400) {
                return new CodeProjectResolution(0,
                        "Could not resolve Kompile code projects (HTTP " + response.statusCode()
                                + "): " + extractError(response.body()));
            }
            JsonNode projects = mapper.readTree(response.body());
            if (projects.isObject() && projects.has("codingProjects")) {
                projects = projects.path("codingProjects");
            }
            if (!projects.isArray()) {
                return new CodeProjectResolution(0,
                        "Kompile code-project discovery returned a non-array response.");
            }

            Set<String> existingRoots = new LinkedHashSet<>();
            for (JsonNode source : sources) {
                String root = text(source, "pathOrUrl");
                if (root != null) {
                    existingRoots.add(root);
                }
            }
            Set<String> matched = new LinkedHashSet<>();
            int added = 0;
            for (JsonNode project : projects) {
                String lifecycle = text(project, "lifecycle");
                if (lifecycle != null && !"ACTIVE".equalsIgnoreCase(lifecycle)) {
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                for (String field : List.of("id", "codeProjectId", "name")) {
                    String alias = text(project, field);
                    if (alias != null) {
                        aliases.add(alias);
                    }
                }
                boolean selected = selectAll || aliases.stream().anyMatch(requested::contains);
                if (!selected) {
                    continue;
                }
                aliases.stream().filter(requested::contains).forEach(matched::add);

                String rootPath = text(project, "rootPath");
                if (rootPath == null) {
                    return new CodeProjectResolution(added,
                            "Registered code project " + aliases + " has no rootPath.");
                }
                added++;
                if (!existingRoots.add(rootPath)) {
                    continue;
                }
                appendCodeProjectSource(project, rootPath, sources);
            }

            requested.removeAll(matched);
            if (!requested.isEmpty()) {
                return new CodeProjectResolution(added,
                        "Unknown or inactive Kompile codeProjects selectors: " + requested);
            }
            if (added == 0) {
                return new CodeProjectResolution(0,
                        "No active Kompile code projects matched codeProjects.");
            }
            return new CodeProjectResolution(added, null);
        } catch (ResourceAccessException e) {
            throw e;
        } catch (Exception e) {
            return new CodeProjectResolution(0,
                    "Could not resolve Kompile code projects: " + e.getMessage());
        }
    }

    private void appendCodeProjectSource(JsonNode project, String rootPath, ArrayNode sources) {
        String projectId = text(project, "codeProjectId");
        if (projectId == null) {
            projectId = text(project, "id");
        }
        String projectName = text(project, "name");
        if (projectName == null) {
            projectName = projectId != null ? projectId : rootPath;
        }

        ObjectNode source = sources.addObject();
        source.put("pathOrUrl", rootPath);
        source.put("sourceType", "DIRECTORY");
        source.put("label", "Code project: " + projectName);
        source.put("maxDepth", 64);
        source.put("maxDocuments", 0);
        copyProjectPatterns(project, source, "includePatterns", List.of());
        copyProjectPatterns(project, source, "excludePatterns", DEFAULT_CODE_EXCLUDES);

        ObjectNode properties = source.putObject("properties");
        properties.put("kompileCodeProject", true);
        properties.put("projectManaged", true);
        properties.put("pipelineType", "CODE");
        properties.put("structuralIndex", "code_graph");
        if (projectId != null) {
            properties.put("codeProjectId", projectId);
        }
        properties.put("codeProjectName", projectName);
        if (project.hasNonNull("factSheetId") && project.path("factSheetId").canConvertToLong()) {
            properties.put("factSheetId", project.path("factSheetId").asLong());
        }
    }

    private String alignCodeProjectFactSheet(ObjectNode request, ArrayNode sources) {
        Set<Long> bindings = new LinkedHashSet<>();
        for (JsonNode source : sources) {
            JsonNode value = source.path("properties").get("factSheetId");
            if (value != null && value.canConvertToLong()) {
                bindings.add(value.asLong());
            }
        }
        if (bindings.size() > 1) {
            return "Selected Kompile code projects are bound to different fact sheets: " + bindings;
        }
        if (bindings.isEmpty()) {
            return null;
        }
        long boundId = bindings.iterator().next();
        if (request.hasNonNull("factSheetId") && request.path("factSheetId").asLong() != boundId) {
            return "knowledgeBase.id conflicts with the selected code project's factSheetId=" + boundId;
        }
        if (!request.hasNonNull("factSheetId") && !request.hasNonNull("factSheetName")) {
            request.put("factSheetId", boundId);
        }
        return null;
    }

    private String applyEmbeddingTraining(JsonNode input, ObjectNode request) {
        if (input == null || input.isNull()) {
            return null;
        }
        if (!input.isObject()) {
            return "embeddingTraining must be an object.";
        }
        String algorithm = text(input, "algorithm");
        if (algorithm != null && !"TRANSE".equalsIgnoreCase(algorithm)
                && !"ROTATE".equalsIgnoreCase(algorithm)) {
            return "embeddingTraining.algorithm must be TRANSE or ROTATE.";
        }
        for (String field : List.of("embeddingDim", "epochs", "batchSize")) {
            if (input.has(field) && (!input.path(field).canConvertToInt() || input.path(field).asInt() <= 0)) {
                return "embeddingTraining." + field + " must be a positive integer.";
            }
        }
        if (input.has("warmStartEpochs")
                && (!input.path("warmStartEpochs").canConvertToInt() || input.path("warmStartEpochs").asInt() < 0)) {
            return "embeddingTraining.warmStartEpochs must be zero or greater.";
        }
        JsonNode runtimeValue = request.get("runtimeConfig");
        ObjectNode runtime;
        if (runtimeValue == null || runtimeValue.isNull()) {
            runtime = request.putObject("runtimeConfig");
        } else if (runtimeValue.isObject()) {
            runtime = (ObjectNode) runtimeValue;
        } else {
            return "runtimeConfig must be an object when embeddingTraining is selected.";
        }
        if (input.has("enabled")) {
            runtime.put("trainEmbeddingsAfterEnrichment", input.path("enabled").asBoolean());
        }
        if (algorithm != null) {
            runtime.put("embeddingAlgorithm", algorithm.toUpperCase(Locale.ROOT));
        }
        if (input.has("embeddingDim")) {
            runtime.put("embeddingDim", input.path("embeddingDim").asInt());
        }
        if (input.has("epochs")) {
            runtime.put("embeddingEpochs", input.path("epochs").asInt());
        }
        if (input.has("batchSize")) {
            runtime.put("embeddingBatchSize", input.path("batchSize").asInt());
        }
        if (input.has("warmStartEpochs")) {
            runtime.put("embeddingWarmStartEpochs", input.path("warmStartEpochs").asInt());
        }
        return null;
    }

    private List<String> bindCodeProjectsToFactSheet(ArrayNode sources, long factSheetId) {
        Set<String> projectIds = new LinkedHashSet<>();
        for (JsonNode source : sources) {
            String projectId = text(source.path("properties"), "codeProjectId");
            if (projectId != null) {
                projectIds.add(projectId);
            }
        }
        List<String> warnings = new ArrayList<>();
        for (String projectId : projectIds) {
            try {
                ObjectNode binding = mapper.createObjectNode().put("factSheetId", factSheetId);
                GroundingBackendClient.GroundingResponse response = client.post(
                        CODE_PROJECTS_PATH + "/" + projectId + "/fact-sheet",
                        mapper.writeValueAsString(binding));
                if (response.statusCode() >= 400) {
                    warnings.add(projectId + ": HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                warnings.add(projectId + ": " + e.getMessage());
            }
        }
        return warnings;
    }

    private void copyProjectPatterns(JsonNode project,
                                     ObjectNode source,
                                     String field,
                                     List<String> defaults) {
        Set<String> patterns = new LinkedHashSet<>(defaults);
        JsonNode value = project.get(field);
        if (value != null && value.isArray()) {
            for (JsonNode item : value) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    patterns.add(item.asText().trim());
                }
            }
        } else {
            String csv = text(project, field);
            if (csv != null) {
                for (String pattern : csv.split(",")) {
                    if (!pattern.isBlank()) {
                        patterns.add(pattern.trim());
                    }
                }
            }
        }
        if (!patterns.isEmpty()) {
            ArrayNode target = source.putArray(field);
            patterns.forEach(target::add);
        }
    }

    private String applyCodeProjectDefaults(ObjectNode request) {
        JsonNode runtimeValue = request.get("runtimeConfig");
        ObjectNode runtime;
        if (runtimeValue == null || runtimeValue.isNull()) {
            runtime = mapper.createObjectNode();
            request.set("runtimeConfig", runtime);
        } else if (runtimeValue.isObject()) {
            runtime = (ObjectNode) runtimeValue;
        } else {
            return "runtimeConfig must be an object when codeProjects are selected.";
        }
        if (!runtime.has("incrementalByContentHash")) {
            runtime.put("incrementalByContentHash", true);
        }
        if (!runtime.has("forceFullRecrawl")) {
            runtime.put("forceFullRecrawl", false);
        }

        JsonNode pipelinesValue = request.get("pipelines");
        ArrayNode pipelines;
        if (pipelinesValue == null || pipelinesValue.isNull()) {
            pipelines = request.putArray("pipelines");
        } else if (pipelinesValue.isArray()) {
            pipelines = (ArrayNode) pipelinesValue;
        } else {
            return "pipelines must be an array when codeProjects are selected.";
        }
        if (pipelines.isEmpty()) {
            pipelines.addObject()
                    .put("pipelineId", "default")
                    .put("displayName", "Default project documents")
                    .put("pipelineType", "STANDARD_TEXT")
                    .put("enableGraphExtraction", true);
            if (!request.hasNonNull("defaultPipelineId")) {
                request.put("defaultPipelineId", "default");
            }
        }
        boolean hasCodePipeline = false;
        for (JsonNode pipeline : pipelines) {
            if (CODE_PIPELINE_ID.equals(pipeline.path("pipelineId").asText())) {
                hasCodePipeline = true;
                break;
            }
        }
        if (!hasCodePipeline) {
            pipelines.addObject()
                    .put("pipelineId", CODE_PIPELINE_ID)
                    .put("displayName", "Kompile code project")
                    .put("pipelineType", "CODE")
                    .put("enableGraphExtraction", true);
        }

        JsonNode routeRulesValue = request.get("routeRules");
        ArrayNode routeRules;
        if (routeRulesValue == null || routeRulesValue.isNull()) {
            routeRules = request.putArray("routeRules");
        } else if (routeRulesValue.isArray()) {
            routeRules = (ArrayNode) routeRulesValue;
        } else {
            return "routeRules must be an array when codeProjects are selected.";
        }
        boolean hasCodeRule = false;
        for (JsonNode rule : routeRules) {
            if (CODE_PIPELINE_ID.equals(rule.path("pipelineId").asText())) {
                hasCodeRule = true;
                break;
            }
        }
        if (!hasCodeRule) {
            ObjectNode codeRule = routeRules.addObject();
            codeRule.put("pipelineId", CODE_PIPELINE_ID);
            codeRule.put("priority", 5);
            ArrayNode extensions = codeRule.putArray("fileExtensions");
            CODE_EXTENSIONS.forEach(extensions::add);
        }
        return null;
    }

    private String applyKnowledgeBase(JsonNode knowledgeBase, ObjectNode request) {
        if (knowledgeBase == null || knowledgeBase.isNull() || knowledgeBase.isMissingNode()) {
            return null;
        }
        if (!knowledgeBase.isObject()) {
            return "knowledgeBase must be an object containing id or name.";
        }
        boolean hasId = knowledgeBase.hasNonNull("id");
        String kbName = text(knowledgeBase, "name");
        boolean hasName = kbName != null;
        if (hasId == hasName) {
            return "knowledgeBase must provide exactly one of id or name.";
        }
        request.remove(List.of("factSheetId", "factSheetName"));
        if (hasId) {
            long id = knowledgeBase.path("id").asLong(-1);
            if (id < 0) {
                return "knowledgeBase.id must be zero or greater.";
            }
            request.put("factSheetId", id);
        } else {
            request.put("factSheetName", kbName);
        }
        return null;
    }

    private String copyArrayOverride(JsonNode source, ObjectNode target, String sourceName, String targetName) {
        JsonNode value = source.get(sourceName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            return sourceName + " must be an array.";
        }
        target.set(targetName, value.deepCopy());
        return null;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            target.set(field, value.deepCopy());
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void addStringArray(ObjectNode properties, String name, String description) {
        ObjectNode value = properties.putObject(name);
        value.put("type", "array");
        value.putObject("items").put("type", "string");
        value.put("description", description);
    }

    private void addObjectArray(ObjectNode properties, String name, String description) {
        ObjectNode value = properties.putObject(name);
        value.put("type", "array");
        value.putObject("items").put("type", "object");
        value.put("description", description);
    }

    private String extractError(String body) {
        try {
            JsonNode json = mapper.readTree(body);
            String message = json.path("message").asText(null);
            if (message == null) {
                message = json.path("error").asText(null);
            }
            if (message != null) {
                return message;
            }
        } catch (Exception ignored) {
            // Use the bounded raw body below.
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private record CodeProjectResolution(int added, String error) {}
}
