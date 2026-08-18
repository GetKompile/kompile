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
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only discovery surface for building a valid unified-crawl request.
 */
public final class CrawlDiscoveryTool implements CliTool {
    private static final Set<String> SECTIONS =
            Set.of("all", "sources", "pipelines", "runtime", "models", "knowledge_bases", "code_projects");

    private final GroundingBackendClient client;
    private final ObjectMapper mapper;
    private final LocalProjectCrawlBackend localBackend;

    public CrawlDiscoveryTool(String baseUrl, ObjectMapper mapper) {
        this(new GroundingBackendClient(baseUrl), mapper);
    }

    CrawlDiscoveryTool(GroundingBackendClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
        this.localBackend = new LocalProjectCrawlBackend(mapper);
    }

    @Override
    public String id() {
        return "crawl_discover";
    }

    @Override
    public String description() {
        return "Discover the crawl capabilities available to the current agent before calling "
                + "crawl_documents or crawl_source. Reports project-local pipelines when no crawl manager "
                + "is configured, otherwise the live distributed surface. Returns source types, pipeline step dependencies, "
                + "standard pipeline kinds, installed loaders and chunkers, processing routes/backends, "
                + "capacity, runtime settings, folder-registered models, knowledge bases, registered Kompile code projects, "
                + "and a concise request-shape guide. "
                + "Use section to limit the response.";
    }

    @Override
    public String compactHint() {
        return "Discover crawl options. section=all|sources|pipelines|runtime|models|knowledge_bases|code_projects; "
                + "use the result to configure crawl_documents.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode section = schema.putObject("properties").putObject("section");
        section.put("type", "string");
        section.put("default", "all");
        section.put("description", "Capability group to return.");
        ArrayNode values = section.putArray("enum");
        SECTIONS.stream().sorted().forEach(values::add);
        return schema;
    }

    @Override
    public String permissionKey() {
        return "crawl_discover";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.READ_ONLY;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Discover crawl capabilities");

        String section = params.path("section").asText("all")
                .trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!SECTIONS.contains(section)) {
            return ToolResult.error("Unknown section '" + section + "'. Valid sections: " + SECTIONS);
        }
        // Pipeline discovery must use the folder-local resolver even when an app server is
        // reachable; only that path can report the actual worker and project pipeline contracts.
        if (!client.isAvailable() || "models".equals(section) || "pipelines".equals(section)) {
            return localBackend.discover(section, context.getWorkingDirectory());
        }

        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("section", section);
        int[] counts = new int[2];

        if (matches(section, "sources")) {
            fetch(catalog, "sourceTypes", "/api/unified-crawl/source-types", counts);
        }
        if (matches(section, "pipelines")) {
            catalog.set("pipelineTypes", pipelineTypes());
            catalog.set("requestShape", requestShape());
            fetch(catalog, "steps", "/api/unified-crawl/steps", counts);
            fetch(catalog, "loaders", "/api/documents/loaders", counts);
            fetch(catalog, "chunkers", "/api/documents/chunkers", counts);
            fetch(catalog, "processingRoute", "/api/unified-crawl/processing-route", counts);
            fetch(catalog, "pdfRoutingModes", "/api/unified-crawl/pdf-routing-modes", counts);
            fetch(catalog, "processingBackendTypes",
                    "/api/unified-crawl/processing-backend-types", counts);
        }
        if (matches(section, "runtime")) {
            fetch(catalog, "processingCapacity", "/api/unified-crawl/processing-capacity", counts);
            fetch(catalog, "runtimeConfig", "/api/unified-crawl/runtime-config", counts);
        }
        if (matches(section, "models")) {
            catalog.set("models", mapper.valueToTree(
                    LocalProjectModelBootstrap.inventory(context.getWorkingDirectory())));
        }
        if (matches(section, "knowledge_bases")) {
            fetch(catalog, "knowledgeBases", "/api/fact-sheets", counts);
        }
        if (matches(section, "code_projects")) {
            fetch(catalog, "codeProjects", "/api/projects/current/code-projects", counts);
        }

        if (counts[0] == 0) {
            return localBackend.discover(section, context.getWorkingDirectory());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("section", section);
        metadata.put("endpointsSucceeded", counts[0]);
        metadata.put("endpointsFailed", counts[1]);
        String title = counts[1] == 0 ? "crawl_discover" : "crawl_discover (partial)";
        return ToolResult.success(title, catalog.toPrettyString(), metadata);
    }

    private boolean matches(String requested, String candidate) {
        return "all".equals(requested) || candidate.equals(requested);
    }

    private void fetch(ObjectNode target, String name, String endpoint, int[] counts) {
        try {
            GroundingBackendClient.GroundingResponse response = client.get(endpoint);
            if (response.statusCode() >= 400) {
                target.putObject(name)
                        .put("status", response.statusCode())
                        .put("error", bounded(response.body()));
                counts[1]++;
                return;
            }
            try {
                target.set(name, mapper.readTree(response.body()));
            } catch (Exception e) {
                target.put(name, response.body());
            }
            counts[0]++;
        } catch (Exception e) {
            target.putObject(name).put("error", String.valueOf(e.getMessage()));
            counts[1]++;
        }
    }

    private ArrayNode pipelineTypes() {
        ArrayNode types = mapper.createArrayNode();
        addPipeline(types, "STANDARD_TEXT", "Normal extracted text, chunking, and embeddings.");
        types.addObject().put("id", "VLM")
                .put("useWhen", "Document and image understanding through a UnifiedPipelineDefinition.")
                .put("executionModel", "managed-unified-runtime")
                .put("supportedInputTypes", "application/pdf,image/*")
                .put("definition", "pipelineSpec with a VLM step runner");
        types.addObject().put("id", "OCR")
                .put("useWhen", "OCR and structured document extraction through a UnifiedPipelineDefinition.")
                .put("executionModel", "managed-unified-runtime")
                .put("supportedInputTypes", "application/pdf,image/*")
                .put("definition", "pipelineSpec with an OCR or document-understanding step runner");
        addPipeline(types, "CODE", "Code-aware loading and chunking.");
        addPipeline(types, "TABLE_AWARE", "Preserve tabular structure during extraction and chunking.");
        addPipeline(types, "KEYWORD_ONLY", "Keyword indexing without embeddings.");
        addPipeline(types, "CUSTOM", "Caller-defined components and options.");
        return types;
    }

    private void addPipeline(ArrayNode types, String id, String useWhen) {
        types.addObject().put("id", id).put("useWhen", useWhen);
    }

    private ObjectNode requestShape() {
        ObjectNode shape = mapper.createObjectNode();
        shape.put("startTool", "crawl_documents");
        shape.put("documentSelector", "documents[] requires exactly one of path or url");
        shape.put("codeProjectSelector",
                "codeProjects[] accepts registered Kompile code-project id/name values; '*' selects every active project");
        shape.put("knowledgeBase", "knowledgeBase={id:<number>} or {name:<string>}; omitted uses active default");
        ArrayNode documentFields = shape.putArray("documentFields");
        for (String field : new String[]{
                "label", "sourceType", "maxDepth", "maxDocuments", "includePatterns",
                "excludePatterns", "allowedContentTypes", "loaderName", "chunkerName",
                "chunkSize", "chunkOverlap", "properties", "chunkerOptions"}) {
            documentFields.add(field);
        }
        ArrayNode configurationFields = shape.putArray("configurationFields");
        for (String field : new String[]{
                "steps", "archivedSteps", "strictSteps", "pipelines", "routeRules",
                "defaultPipelineId", "graphExtraction", "chunking", "vectorIndex",
                "processingRoute", "runtimeConfig", "preprocessing", "hydration",
                "distribution", "deriveOntology", "maxValidationRetries", "modelRuntime", "dryRun", "config"}) {
            configurationFields.add(field);
        }
        shape.put("selectionAdvice",
                "Use per-document loaderName/chunkerName for exceptions; use pipelines plus routeRules "
                        + "for reusable content classes; use steps to limit crawl phases. Set dryRun=true on "
                        + "crawl_documents to validate the composed request without creating a knowledge base. "
                        + "All model-backed pipelines use UnifiedPipelineDefinition and an MCP-owned reusable stdio runtime.");
        ObjectNode typeGuide = shape.putObject("pipelineTypeGuide");
        typeGuide.put("VLM/OCR",
                "pipelineType + modelId/modelBindings compiled to the canonical unified definition.");
        typeGuide.put("STANDARD_TEXT/CODE/TABLE_AWARE/KEYWORD_ONLY",
                "pipelineType + loaderName/chunkerName/options; model steps use the same runtime contract.");
        typeGuide.put("CUSTOM",
                "UNIFIED_PIPELINE with pipelineDefinition/pipelineSpec.@class.");
        return shape;
    }

    private String bounded(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
