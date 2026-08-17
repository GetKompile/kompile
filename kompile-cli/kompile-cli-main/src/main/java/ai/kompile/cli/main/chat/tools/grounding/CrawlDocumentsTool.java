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
        this(new GroundingBackendClient(baseUrl), mapper, new LocalProjectCrawlBackend(mapper));
    }

    /**
     * Create the agent-facing tool with a supplied project-local backend.
     *
     * <p>The normal constructor remains the production path. This overload lets JVM embedders run
     * the same tool contract with an explicitly composed local execution boundary.</p>
     */
    public CrawlDocumentsTool(
            String baseUrl, ObjectMapper mapper, LocalProjectCrawlBackend localBackend) {
        this(new GroundingBackendClient(baseUrl), mapper, localBackend);
    }

    CrawlDocumentsTool(GroundingBackendClient client, ObjectMapper mapper) {
        this(client, mapper, new LocalProjectCrawlBackend(mapper));
    }

    CrawlDocumentsTool(
            GroundingBackendClient client,
            ObjectMapper mapper,
            LocalProjectCrawlBackend localBackend) {
        this.client = client;
        this.mapper = mapper;
        this.localBackend = localBackend;
    }

    @Override
    public String id() {
        return "crawl_documents";
    }

    @Override
    public String description() {
        return "Initialize or update the knowledge base for the current MCP folder. "
                + "With no documents, codeProjects, or knowledgeBase selector, the local stdio backend "
                + "indexes the current project folder into a deterministic folder-scoped knowledge base. "
                + "Explicit documents and code projects can be added when narrower control is needed. "
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
        return "Use {} to auto-configure and bootstrap the current directory locally, or provide "
                + "documents=[{path|url,...}], explicit additional codeProjects=[id|name|*], and an optional "
                + "knowledgeBase={id|name}. For reusable pipelines, call crawl_discover section=pipelines and "
                + "follow its wiringRecipe; set dryRun=true to validate and preview the composed crawl "
                + "without creating or changing a knowledge base. Local runs complete synchronously.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("name")
                .put("type", "string")
                .put("description", "Optional human-readable crawl job name.");
        props.putObject("dryRun")
                .put("type", "boolean")
                .put("default", false)
                .put("description", "Validate and preview the complete composed crawl, including pipeline and worker resolution, without persisting a knowledge base, graph, or crawl artifacts.");

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
        documentProps.putObject("executorId").put("type", "string")
                .put("description", "Optional processor registered in pipelineRegistry.executors.");
        documentProps.putObject("processor").put("type", "object")
                .put("description", "Per-document registered processor override.");
        documentProps.putObject("modelBindings").put("type", "object")
                .put("description", "Per-document role-to-model overrides. Values reference pipelineRegistry.models or project model ids.")
                .putObject("additionalProperties").put("type", "string");
        documentProps.putObject("pipelineDefinitionId").put("type", "string");
        documentProps.putObject("pipelineDefinitionPath").put("type", "string");
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
        codeProjects.put("description", "Optional explicit Kompile code-project ids or names to add. "
                + "Omit for the normal local workflow: the current directory is auto-registered through the "
                + "project protocol and used as the folder identity. Use '*' only to opt into every ACTIVE "
                + "additional registration from kompile.project.json.");

        ObjectNode knowledgeBase = props.putObject("knowledgeBase");
        knowledgeBase.put("type", "object");
        knowledgeBase.put("description",
                "Optional explicit knowledge-base selector. Omit locally to use the deterministic knowledge base for the current folder. "
                        + "Numeric ids are retained for remote/legacy compatibility.");
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

        ObjectNode reasoningLearning = props.putObject("reasoningLearning");
        reasoningLearning.put("type", "object");
        reasoningLearning.put("description", "Final corpus-wide portable reasoning learning. "
                + "The request-scoped local crawl worker jointly learns grounded FOL/PSL and MEBN "
                + "against one hybrid consensus, persists the models in the folder .kgraph, and "
                + "relearns after entity canonicalization when identities changed.");
        ObjectNode reasoningProps = reasoningLearning.putObject("properties");
        reasoningProps.putObject("enabled").put("type", "boolean");
        reasoningProps.putObject("pslSteps").put("type", "integer").put("minimum", 1);
        reasoningProps.putObject("mebnEpochs").put("type", "integer").put("minimum", 1);
        reasoningProps.putObject("consensusRounds").put("type", "integer").put("minimum", 1);
        reasoningProps.putObject("consensusWeight").put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
        reasoningProps.putObject("maxRelationTypes").put("type", "integer").put("minimum", 1);

        addStringArray(props, "steps",
                "Pipeline steps to run. Dependencies are resolved by the server; omit to run all.");
        addStringArray(props, "archivedSteps",
                "Steps to archive for deferred execution.");
        props.putObject("strictSteps").put("type", "boolean")
                .put("description", "Honor selected steps without force-adding the legacy graph spine.");
        props.putObject("deriveOntology").put("type", "boolean");
        props.putObject("defaultPipelineId").put("type", "string");
        props.putObject("maxValidationRetries").put("type", "integer").put("minimum", 0);

        ObjectNode pipelines = props.putObject("pipelines");
        pipelines.put("type", "array");
        pipelines.put("description",
                "Named ingest pipeline definitions. Start with pipelineId and pipelineType; use "
                        + "registeredPipelineId to inherit a built-in/project default, then configure model "
                        + "bindings and processor execution. VLM/OCR select the PDF compatibility adapter; "
                        + "generic pipelines select UNIFIED_PIPELINE with a concrete pipelineDefinition.");
        ObjectNode pipeline = pipelines.putObject("items");
        pipeline.put("type", "object");
        ObjectNode pipelineProperties = pipeline.putObject("properties");
        pipelineProperties.putObject("pipelineId").put("type", "string")
                .put("description", "Stable id referenced by documents[].pipelineId, routes, or defaultPipelineId.");
        pipelineProperties.putObject("pipelineType").put("type", "string")
                .put("description", "Portable category. Built-ins include STANDARD_TEXT, CODE, TABLE_AWARE, "
                        + "KEYWORD_ONLY, VLM, and OCR; arbitrary categories are allowed when a processor is registered.");
        pipelineProperties.putObject("registeredPipelineId").put("type", "string")
                .put("description", "Optional built-in, project, or pipelineRegistry.defaults id to inherit.");
        pipelineProperties.putObject("executorId").put("type", "string")
                .put("description", "Optional pipelineRegistry.executors id; its processor contract is merged before execution.");
        pipelineProperties.putObject("loaderName").put("type", "string");
        pipelineProperties.putObject("chunkerName").put("type", "string");
        pipelineProperties.putObject("chunkSize").put("type", "integer").put("minimum", 1);
        pipelineProperties.putObject("chunkOverlap").put("type", "integer").put("minimum", 0);
        pipelineProperties.putObject("modelId").put("type", "string")
                .put("description", "Single model selection, commonly used by VLM/OCR or a generic model step.");
        pipelineProperties.putObject("vlmModel").put("type", "string");
        pipelineProperties.putObject("modelSetId").put("type", "string");
        pipelineProperties.putObject("modelBindings").put("type", "object")
                .put("description", "Role-to-model id map; values may reference pipelineRegistry.models or project models.")
                .putObject("additionalProperties").put("type", "string");
        pipelineProperties.putObject("modelRefs").put("type", "array")
                .putObject("items").put("type", "string");
        pipelineProperties.putObject("modelDefinitions").put("type", "object");
        ObjectNode inlineDefinition = pipelineProperties.putObject("pipelineDefinition");
        inlineDefinition.put("type", "object")
                .put("description", "Inline UnifiedPipelineDefinition; generic execution requires pipelineSpec.@class.");
        ObjectNode inlineDefinitionProperties = inlineDefinition.putObject("properties");
        inlineDefinitionProperties.putObject("pipelineId").put("type", "string");
        inlineDefinitionProperties.putObject("kind").put("type", "string");
        inlineDefinitionProperties.putObject("topology").put("type", "string");
        inlineDefinitionProperties.putObject("modelSetId").put("type", "string");
        inlineDefinitionProperties.putObject("modelBindings").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        inlineDefinitionProperties.putObject("modelDefinitions").put("type", "object");
        ObjectNode inlineSpec = inlineDefinitionProperties.putObject("pipelineSpec");
        inlineSpec.put("type", "object")
                .put("description", "Concrete SequencePipeline or GraphPipeline serialization.");
        inlineSpec.putObject("properties").putObject("@class").put("type", "string")
                .put("description", "Required concrete pipeline class discriminator.");
        pipelineProperties.putObject("pipelineDefinitionPath").put("type", "string");
        pipelineProperties.putObject("pipelineDefinitionId").put("type", "string");
        pipelineProperties.putObject("options").put("type", "object")
                .put("description", "Pipeline-specific options such as maxPages, pdfRenderDpi, outputFormat, or model bindings.");
        pipelineProperties.putObject("chunkerOptions").put("type", "object");
        ObjectNode processor = pipelineProperties.putObject("processor");
        processor.put("type", "object");
        processor.put("description",
                "Execution contract. VLM/OCR use adapter=vlm-test; generic pipelines use "
                        + "type=UNIFIED_PIPELINE plus a definition; custom subprocesses use KOMPILE_SUBPROCESS or EXECUTABLE.");
        ObjectNode processorProperties = processor.putObject("properties");
        processorProperties.putObject("type").put("type", "string");
        processorProperties.putObject("adapter").put("type", "string")
                .put("description", "Use vlm-test only for the built-in PDF VLM/OCR compatibility adapter.");
        processorProperties.putObject("pipelineDefinition").put("type", "object");
        processorProperties.putObject("pipelineDefinitionPath").put("type", "string");
        processorProperties.putObject("pipelineDefinitionId").put("type", "string");
        processorProperties.putObject("executable").put("type", "string");
        processorProperties.putObject("executableMode").put("type", "string")
                .putArray("enum").add("DEDICATED").add("UNIFIED");
        processorProperties.putObject("componentId").put("type", "string");
        processorProperties.putObject("arguments").put("type", "array")
                .putObject("items").put("type", "string");
        processorProperties.putObject("outputProtocol").put("type", "string");
        processorProperties.putObject("outputField").put("type", "string");
        processorProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        processorProperties.putObject("environment").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        pipeline.putArray("required").add("pipelineId");
        ObjectNode pipelineTypeGuide = schema.putObject("pipelineTypeGuide");
        pipelineTypeGuide.put("VLM/OCR",
                "pipelineType + modelId/modelBindings + optional processor.adapter=vlm-test; PDF compatibility worker.");
        pipelineTypeGuide.put("STANDARD_TEXT/CODE/TABLE_AWARE/KEYWORD_ONLY",
                "pipelineType + loaderName/chunkerName/options; no document-model worker required.");
        pipelineTypeGuide.put("CUSTOM",
                "processor.type=KOMPILE_SUBPROCESS|EXECUTABLE for a caller executable, or "
                        + "UNIFIED_PIPELINE with pipelineDefinition/pipelineSpec.@class.");
        ObjectNode pipelineRegistry = props.putObject("pipelineRegistry");
        pipelineRegistry.put("type", "object");
        pipelineRegistry.put("description", "Request-scoped pipeline registrations shared by local routing and "
                + "subprocess execution. Supplying this field selects the local folder backend so executor definitions "
                + "cannot be lost through a managed-server DTO. Active kompile.project.json pipelines are added automatically locally.");
        ObjectNode registryProperties = pipelineRegistry.putObject("properties");
        addObjectArray(registryProperties, "defaults",
                "Reusable ingest-pipeline defaults. A pipelines entry may inherit one with registeredPipelineId.");
        ObjectNode definitions = registryProperties.putObject("definitions");
        definitions.put("type", "array");
        definitions.put("description",
                "Inline UnifiedPipelineDefinition objects addressable by pipelineDefinitionId.");
        ObjectNode definition = definitions.putObject("items");
        definition.put("type", "object");
        ObjectNode definitionProperties = definition.putObject("properties");
        definitionProperties.putObject("pipelineId").put("type", "string");
        definitionProperties.putObject("displayName").put("type", "string");
        definitionProperties.putObject("description").put("type", "string");
        definitionProperties.putObject("kind").put("type", "string");
        definitionProperties.putObject("topology").put("type", "string");
        definitionProperties.putObject("modelSetId").put("type", "string");
        definitionProperties.putObject("modelBindings").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        definitionProperties.putObject("modelDefinitions").put("type", "object");
        ObjectNode definitionSpec = definitionProperties.putObject("pipelineSpec");
        definitionSpec.put("type", "object")
                .put("description", "Concrete serialized Pipeline; @class is required.");
        ObjectNode definitionSpecProperties = definitionSpec.putObject("properties");
        definitionSpecProperties.putObject("@class").put("type", "string")
                .put("description", "SequencePipeline or GraphPipeline concrete class.");
        definitionSpecProperties.putObject("id").put("type", "string");
        definitionSpecProperties.putObject("steps").put("type", "array")
                .putObject("items").put("type", "object");
        definitionSpecProperties.putObject("nodes").put("type", "array")
                .putObject("items").put("type", "object");
        definition.putArray("required").add("pipelineId").add("pipelineSpec");
        ObjectNode models = registryProperties.putObject("models");
        models.put("type", "array");
        models.put("description", "Request-scoped model definitions. Pipelines bind these ids to named roles with modelBindings.");
        ObjectNode model = models.putObject("items");
        model.put("type", "object");
        ObjectNode modelProperties = model.putObject("properties");
        modelProperties.putObject("id").put("type", "string");
        modelProperties.putObject("modelId").put("type", "string")
                .put("description", "Project/catalog model selection. Defaults to id.");
        modelProperties.putObject("role").put("type", "string");
        modelProperties.putObject("source").put("type", "string");
        modelProperties.putObject("repository").put("type", "string");
        modelProperties.putObject("revision").put("type", "string");
        modelProperties.putObject("localPath").put("type", "string");
        modelProperties.putObject("format").put("type", "string");
        modelProperties.putObject("type").put("type", "string");
        modelProperties.putObject("autoBootstrap").put("type", "boolean");
        modelProperties.putObject("runtime").put("type", "object")
                .put("description", "Per-model staging/runtime overrides; these take precedence over top-level modelRuntime defaults.");
        model.putArray("required").add("id");
        ObjectNode executors = registryProperties.putObject("executors");
        executors.put("type", "array");
        ObjectNode executor = executors.putObject("items");
        executor.put("type", "object");
        ObjectNode executorProperties = executor.putObject("properties");
        executorProperties.putObject("executorId").put("type", "string");
        executorProperties.putObject("type").put("type", "string")
                .putArray("enum").add("UNIFIED_PIPELINE").add("KOMPILE_SUBPROCESS").add("EXECUTABLE");
        executorProperties.putObject("pipelineDefinitionId").put("type", "string");
        executorProperties.putObject("pipelineDefinitionPath").put("type", "string");
        executorProperties.putObject("pipelineDefinition").put("type", "object");
        executorProperties.putObject("componentId").put("type", "string");
        executorProperties.putObject("executable").put("type", "string");
        executorProperties.putObject("executableMode").put("type", "string")
                .putArray("enum").add("DEDICATED").add("UNIFIED");
        executorProperties.putObject("subprocessMode").put("type", "string");
        executorProperties.putObject("arguments").put("type", "array")
                .putObject("items").put("type", "string");
        executorProperties.putObject("outputProtocol").put("type", "string")
                .putArray("enum").add("AUTO").add("TEXT").add("JSON").add("KOMPILE_MESSAGE");
        executorProperties.putObject("outputField").put("type", "string");
        executorProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        executorProperties.putObject("environment").put("type", "object")
                .putObject("additionalProperties").put("type", "string");
        executor.putArray("required").add("executorId").add("type");
        addObjectArray(props, "routeRules",
                "Content routing rules that select a pipeline for matching documents.");

        ObjectNode runtimeConfig = props.putObject("runtimeConfig");
        runtimeConfig.put("type", "object");
        runtimeConfig.put("description", "Folder-local execution settings. The local crawl engine "
                + "resolves these directly and does not require a running MCP or application server.");
        ObjectNode runtimeProperties = runtimeConfig.putObject("properties");
        runtimeProperties.putObject("documentModelExecutable")
                .put("type", "string")
                .put("description", "Legacy alias for the executable on the built-in vlm-test registration. "
                        + "New processors should use pipelineRegistry.executors[].executable.");
        runtimeProperties.putObject("documentModelExecutableMode")
                .put("type", "string")
                .put("description", "Legacy launch-mode alias for the built-in vlm-test registration.")
                .putArray("enum").add("DEDICATED").add("UNIFIED");
        runtimeProperties.putObject("graphExtractionParallelism")
                .put("type", "integer").put("minimum", 1).put("maximum", 32)
                .put("description", "Concurrent local extraction work items.");
        runtimeProperties.putObject("graphExtractionRemoteParallelism")
                .put("type", "integer").put("minimum", 1).put("maximum", 32)
                .put("description", "Concurrent request-scoped CLI/API model calls.");
        runtimeProperties.putObject("graphExtractionBatchSize")
                .put("type", "integer").put("minimum", 1);
        runtimeProperties.putObject("graphExtractionTargetCharsPerBatch")
                .put("type", "integer").put("minimum", 1);
        runtimeProperties.putObject("graphExtractionMaxItemsPerBatch")
                .put("type", "integer").put("minimum", 1);
        runtimeProperties.putObject("graphExtractionBatchTimeoutSeconds")
                .put("type", "integer").put("minimum", 1);
        runtimeProperties.putObject("llmCallTimeoutSeconds")
                .put("type", "integer").put("minimum", 10).put("maximum", 1800);
        runtimeProperties.putObject("runReasoningLearning").put("type", "boolean")
                .put("description", "Enable the final folder-local FOL/PSL/MEBN learning pass (default true).");
        runtimeProperties.putObject("costSortChunks").put("type", "boolean");

        ObjectNode modelRuntime = props.putObject("modelRuntime");
        modelRuntime.put("type", "object");
        modelRuntime.put("description", "Folder-local model lifecycle for LOCAL_MODEL routes. Native CLI runs "
                + "bootstrap and serve through standalone native children; JVM development may use the "
                + "same executable-JAR ABI. The child exists only for this MCP crawl. This object supplies "
                + "defaults; pipelineRegistry.models[].runtime can override them per bound model.");
        ObjectNode modelRuntimeProperties = modelRuntime.putObject("properties");
        modelRuntimeProperties.putObject("autoBootstrap").put("type", "boolean");
        modelRuntimeProperties.putObject("localPath").put("type", "string");
        modelRuntimeProperties.putObject("source").put("type", "string");
        modelRuntimeProperties.putObject("repository").put("type", "string");
        modelRuntimeProperties.putObject("revision").put("type", "string");
        modelRuntimeProperties.putObject("format").put("type", "string");
        modelRuntimeProperties.putObject("type").put("type", "string");
        modelRuntimeProperties.putObject("stagingExecutable").put("type", "string");
        modelRuntimeProperties.putObject("stagingJar").put("type", "string")
                .put("description", "JVM-development-only executable JAR; native CLI runs require stagingExecutable.");
        modelRuntimeProperties.putObject("servingExecutable").put("type", "string");
        modelRuntimeProperties.putObject("servingJar").put("type", "string")
                .put("description", "JVM-development-only executable JAR; native CLI runs require servingExecutable.");
        modelRuntimeProperties.putObject("javaExecutable").put("type", "string")
                .put("description", "Optional Java executable for executable-JAR artifacts; otherwise the "
                        + "distribution-aware JavaRuntimeLocator is used (including SDKMAN/Graal Java 17).");
        modelRuntimeProperties.putObject("heapSize").put("type", "string");
        modelRuntimeProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        modelRuntimeProperties.putObject("environment").put("type", "object")
                .putObject("additionalProperties").put("type", "string");

        ObjectNode graphExtraction = props.putObject("graphExtraction");
        graphExtraction.put("type", "object");
        graphExtraction.put("description", "Semantic graph configuration. Supplying this object "
                + "enables the same GraphExtractionOrchestrator used by the parallel batch crawl.");
        ObjectNode graphProperties = graphExtraction.putObject("properties");
        graphProperties.putObject("llmProvider").put("type", "string")
                .put("description", "Model runtime shorthand: serving/kompile-local launches Kompile's "
                        + "request-scoped serving subprocess; claude, codex/openai, gemini/google, "
                        + "opencode, qwen, pi, or an exact *-cli id launches that CLI agent. "
                        + "Use processingRoute for an explicit fallback chain or API endpoint.");
        graphProperties.putObject("modelName").put("type", "string")
                .put("description", "Request-scoped model override passed to the selected serving, CLI, or API backend.");
        graphProperties.putObject("temperature").put("type", "number");
        graphProperties.putObject("maxTokens").put("type", "integer").put("minimum", 1);
        graphProperties.putObject("minConfidence").put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
        graphProperties.putObject("entityResolution").put("type", "boolean")
                .put("description", "Run the shared final corpus entity-resolution lifecycle after extraction and learning.");
        graphProperties.putObject("entityResolutionSimilarityThreshold").put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
        graphProperties.putObject("entityResolutionUseEmbeddings").put("type", "boolean");
        graphProperties.putObject("entityResolutionEmbeddingThreshold").put("type", "number")
                .put("minimum", 0.0).put("maximum", 1.0);
        graphProperties.putObject("entityResolutionMaxCandidatePairs").put("type", "integer")
                .put("minimum", 1);
        graphProperties.putObject("extractionMode").put("type", "string")
                .putArray("enum").add("SINGLE_PASS").add("DECOMPOSED");
        addStringArray(graphProperties, "entityTypes", "Entity types to extract.");
        addStringArray(graphProperties, "relationshipTypes", "Relationship types to extract.");

        ObjectNode processingRoute = props.putObject("processingRoute");
        processingRoute.put("type", "object");
        processingRoute.put("description", "Request-scoped model execution chain. The local MCP path "
                + "launches Kompile's serving subprocess for LOCAL_MODEL, launches CLI_AGENT subprocesses, "
                + "or calls API_AGENT endpoints without a running app server. Every local subprocess is "
                + "stopped before the MCP command returns.");
        ObjectNode routeProperties = processingRoute.putObject("properties");
        routeProperties.putObject("fallbackEnabled").put("type", "boolean");
        routeProperties.putObject("servingLaneEnabled").put("type", "boolean");
        ObjectNode backends = routeProperties.putObject("backends");
        backends.put("type", "array").put("minItems", 1);
        ObjectNode backend = backends.putObject("items");
        backend.put("type", "object");
        ObjectNode backendProperties = backend.putObject("properties");
        backendProperties.putObject("id").put("type", "string");
        backendProperties.putObject("displayName").put("type", "string");
        backendProperties.putObject("type").put("type", "string")
                .putArray("enum").add("CLI_AGENT").add("API_AGENT").add("LOCAL_MODEL");
        backendProperties.putObject("agentName").put("type", "string")
                .put("description", "CLI registry id for CLI_AGENT; 'serving' for Kompile's request-scoped LOCAL_MODEL subprocess.");
        backendProperties.putObject("endpointUrl").put("type", "string")
                .put("description", "OpenAI-compatible base URL for API_AGENT (the dispatcher appends /chat/completions).");
        backendProperties.putObject("apiKey").put("type", "string");
        backendProperties.putObject("modelName").put("type", "string");
        backendProperties.putObject("priority").put("type", "integer");
        backendProperties.putObject("maxConcurrent").put("type", "integer").put("minimum", 0);
        backendProperties.putObject("requestsPerMinute").put("type", "integer").put("minimum", 0);
        addStringArray(backendProperties, "capabilities", "Backend capabilities such as llm.");
        backend.putArray("required").add("id").add("type");

        for (String field : List.of(
                "chunking", "vectorIndex", "preprocessing", "hydration", "distribution")) {
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
        JsonNode config = params.get("config");
        if (config != null && !config.isNull() && !config.isObject()) {
            return ToolResult.error("config must be an object.");
        }
        // A dry-run must use the local backend even when an application client is reachable:
        // the local path is the only one that guarantees no remote knowledge-base mutation and
        // validates the same request-scoped pipeline that a real local crawl would execute.
        if (params.path("dryRun").asBoolean(false)
                || !client.isAvailable() || requiresRequestScopedPipelineExecution(params)) {
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
                copyIfPresent(selected, source, "executorId");
                copyIfPresent(selected, source, "processor");
                copyIfPresent(selected, source, "modelBindings");
                copyIfPresent(selected, source, "pipelineDefinitionId");
                copyIfPresent(selected, source, "pipelineDefinitionPath");
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
                    "pipelineRegistry", "runtimeConfig", "preprocessing", "hydration", "distribution")) {
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
                    "crawl_control", "crawl_result", "knowledge_status", "knowledge_search",
                    "local_code_index", "code_graph", "knowledge_graph",
                    "graph_embeddings", "graph_reason", "graph_reasoning_query",
                    "graph_import", "graph_export"));
            CrawlResultHandle.from(body, "managed", jobId, null).attachTo(metadata);
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

    private static boolean requiresRequestScopedPipelineExecution(JsonNode params) {
        return hasRequestScopedPipelineContract(params)
                || hasRequestScopedPipelineContract(params == null ? null : params.get("config"));
    }

    private static boolean hasRequestScopedPipelineContract(JsonNode request) {
        if (request == null || !request.isObject()) {
            return false;
        }
        if (request.hasNonNull("pipelineRegistry") || request.hasNonNull("registeredPipelines")
                || request.hasNonNull("modelRuntime")) {
            return true;
        }
        return containsRequestScopedProcessor(request.get("pipelines"))
                || containsRequestScopedProcessor(request.get("documents"))
                || containsRequestScopedProcessor(request.get("sources"));
    }

    private static boolean containsRequestScopedProcessor(JsonNode values) {
        if (values == null || !values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if (value.isObject() && List.of(
                    "registeredPipelineId", "executorId", "processor", "pipelineDefinition",
                    "pipelineDefinitionId", "pipelineDefinitionPath").stream().anyMatch(value::hasNonNull)) {
                return true;
            }
        }
        return false;
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
