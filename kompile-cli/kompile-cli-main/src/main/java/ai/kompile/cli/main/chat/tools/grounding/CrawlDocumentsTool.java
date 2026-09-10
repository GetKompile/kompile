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
import ai.kompile.cli.main.project.LocalCrawlCapabilities;
import ai.kompile.cli.main.project.LocalExternalSourceLoaderRegistry;
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
                + "This tool returns an asynchronous job handle by default for both managed and project-local crawls; "
                + "poll crawl_control operation=status and then call crawl_result when terminal=true. Set async=false "
                + "only when an embedding explicitly needs the legacy blocking local behavior. Each document may select a named pipeline, "
                + "loader, chunker, limits, filters, and properties. The request can also configure custom "
                + "pipelines, routing rules, graph extraction, chunking, vector indexing, runtime, "
                + "hydration, distribution, enabled/archived steps, and ontology derivation. Call "
                + "crawl_discover first to inspect live source types, steps, loaders, chunkers, "
                + "pipeline kinds, backends, runtime settings, knowledge bases, and code projects.";
    }

    @Override
    public String compactHint() {
        return "Crawl documents or auto-configure this folder. Returns jobId; poll crawl_control "
                + "status using pollAfterMs, then crawl_result. dryRun previews; async=false blocks.";
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
                .put("description", "Validate and preview the complete composed crawl, including pipeline and worker resolution, without persisting a knowledge base, graph, or crawl artifacts. The result includes effectiveRequest, pipelineResolution, and per-document resolvedPipeline/modelResolution so inherited defaults are visible.");
        props.putObject("async")
                .put("type", "boolean")
                .put("default", true)
                .put("description", "Return immediately with a pollable jobId. Use crawl_control operation=status and respect pollAfterMs, then crawl_result when terminal=true. Set false only for explicit blocking compatibility.");
        props.putObject("waitForCompletion")
                .put("type", "boolean")
                .put("default", false)
                .put("description", "Blocking compatibility alias for async=false; ignored for dryRun previews.");

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
                        + "registeredPipelineId to inherit a built-in/project default, then explicitly override "
                        + "model selection and generation under modelId/modelBindings and options. Artifact-backed "
                        + "VLM/OCR/LLM pipelines use UNIFIED_PIPELINE with a concrete pipelineDefinition. "
                        + "CHAT_MODEL uses an isolated project/global Kompile chat call and never persists credentials.");
        ObjectNode pipeline = pipelines.putObject("items");
        pipeline.put("type", "object");
        ObjectNode pipelineProperties = pipeline.putObject("properties");
        pipelineProperties.putObject("pipelineId").put("type", "string")
                .put("description", "Stable id referenced by documents[].pipelineId, routes, or defaultPipelineId.");
        pipelineProperties.putObject("pipelineType").put("type", "string")
                .put("description", "Portable category. Built-ins include STANDARD_TEXT, CODE, TABLE_AWARE, "
                        + "KEYWORD_ONLY, LLM, CHAT_MODEL, VLM, and OCR; arbitrary categories are allowed when a processor is registered.");
        pipelineProperties.putObject("registeredPipelineId").put("type", "string")
                .put("description", "Optional built-in, project, or pipelineRegistry.defaults id to inherit. Inherited options are effective defaults (including VLM outputFormat and generation settings); use pipelines[].modelId/modelBindings and pipelines[].options to make model/output/generation choices explicit, then use dryRun=true to inspect the composed result.");
        pipelineProperties.putObject("executorId").put("type", "string")
                .put("description", "Optional pipelineRegistry.executors id; its processor contract is merged before execution.");
        pipelineProperties.putObject("loaderName").put("type", "string");
        pipelineProperties.putObject("chunkerName").put("type", "string");
        pipelineProperties.putObject("chunkSize").put("type", "integer").put("minimum", 1);
        pipelineProperties.putObject("chunkOverlap").put("type", "integer").put("minimum", 0);
        pipelineProperties.putObject("modelId").put("type", "string")
                .put("description", "Explicit default model shorthand. Used only when modelBindings is empty; "
                        + "must agree with vlmModel when both are supplied. For VLM/OCR, set this explicitly when inheriting a registered pipeline.");
        pipelineProperties.putObject("vlmModel").put("type", "string")
                .put("description", "Deprecated VLM-specific alias for modelId. Conflicting aliases are rejected.");
        pipelineProperties.putObject("modelSetId").put("type", "string")
                .put("description", "Legacy/default model selection after modelId and vlmModel; "
                        + "modelBindings remains authoritative.");
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
                .put("description", "Pipeline-specific options. Local VLM/OCR generation runs to model EOS within the declared context by default; use maxResponseBytes as the output safety limit and maxNewTokens only as an explicit diagnostic override. CHAT_MODEL options include provider, modelId, thinking, prompt, systemPrompt, outputFormat, maxInputChars, maxResponseChars, maxImageBytes, pdfRenderDpi, pageBatchSize, maxPages, and pageRange (for example 7-9). Omitted thinking inherits host chat policy; nonblank thinking is request-scoped. Other local options include temperature, topP, beamSize, and doSample.");
        pipelineProperties.putObject("chunkerOptions").put("type", "object");
        ObjectNode processor = pipelineProperties.putObject("processor");
        processor.put("type", "object");
        processor.put("description",
                "Model execution contract. UNIFIED_PIPELINE runs a concrete artifact-backed definition in the pooled local runtime. CHAT_MODEL invokes the configured direct Kompile chat provider in the MCP host so credentials never enter pipeline JSON.");
        ObjectNode processorProperties = processor.putObject("properties");
        processorProperties.putObject("type").put("type", "string")
                .putArray("enum").add("UNIFIED_PIPELINE").add("CHAT_MODEL");
        processorProperties.putObject("pipelineDefinition").put("type", "object");
        processorProperties.putObject("pipelineDefinitionPath").put("type", "string");
        processorProperties.putObject("pipelineDefinitionId").put("type", "string");
        processorProperties.putObject("modelSource").put("type", "string")
                .put("description", "For CHAT_MODEL use chat; provider credentials are resolved from project/global chat configuration.");
        processorProperties.putObject("provider").put("type", "string")
                .put("description", "Native CHAT_MODEL provider: codex, claude, or registered direct provider id; omitted uses configured chat. Does not change active chat.");
        processorProperties.putObject("modelId").put("type", "string");
        processorProperties.putObject("thinking").put("type", "string")
                .put("description", "Optional request-scoped provider-native thinking/effort value; omission inherits host chat policy.");
        processorProperties.putObject("operation").put("type", "string")
                .put("description", "CHAT_MODEL operation: text, image, pdf, graph_extraction, or json_schema. Probe the selected provider/model first.");
        processorProperties.putObject("jsonSchema").put("type", "object");
        processorProperties.putObject("prompt").put("type", "string");
        processorProperties.putObject("systemPrompt").put("type", "string");
        processorProperties.putObject("pageRange").put("type", "string")
                .put("description", "Optional inclusive PDF pages, for example 7-9 or 1-3,5; bounds are validated before inference.");
        processorProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        pipeline.putArray("required").add("pipelineId");
        ObjectNode modelSelection = schema.putObject("modelSelection");
        modelSelection.putArray("precedence")
                .add("modelBindings.default").add("modelId").add("vlmModel").add("modelSetId");
        modelSelection.put("conflicts",
                "Different modelId and vlmModel values are rejected; explicit modelBindings are authoritative.");
        modelSelection.put("resolution",
                "Bindings resolve through pipelineRegistry.models and then kompile.project.json.");
        ObjectNode pipelineTypeGuide = schema.putObject("pipelineTypeGuide");
        pipelineTypeGuide.put("VLM/OCR",
                "pipelineType + modelId/modelBindings compiled to UnifiedPipelineDefinition and PDF compatibility worker; "
                        + "the worker is launched inside the selected asynchronous crawl job.");
        pipelineTypeGuide.put("CHAT_MODEL",
                "Use pipelineId=chat-model-document or processor.type=CHAT_MODEL for text/image/PDF extraction through the configured direct chat provider. PDFs are rendered into bounded image batches; no local model artifact is resolved.");
        pipelineTypeGuide.put("STANDARD_TEXT/CODE/TABLE_AWARE/KEYWORD_ONLY",
                "pipelineType + loaderName/chunkerName/options; model steps use the same runtime contract.");
        pipelineTypeGuide.put("CUSTOM",
                "UNIFIED_PIPELINE with pipelineDefinition/pipelineSpec.@class.");
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
        modelProperties.putObject("source").put("type", "string")
                .put("description", "Artifact/catalog source for UNIFIED_PIPELINE; use chat for a CHAT_MODEL binding.");
        modelProperties.putObject("provider").put("type", "string")
                .put("description", "Native CHAT_MODEL provider selection (codex, claude, or registered direct provider id). Uses detached host credentials without changing active chat. No credentials/endpoints here.");
        modelProperties.putObject("repository").put("type", "string");
        modelProperties.putObject("revision").put("type", "string");
        modelProperties.putObject("localPath").put("type", "string");
        modelProperties.putObject("format").put("type", "string");
        modelProperties.putObject("type").put("type", "string");
        modelProperties.putObject("runtime").put("type", "object")
                .put("description", "Per-model read-only execution overrides; these take precedence over top-level modelRuntime defaults.");
        model.putArray("required").add("id");
        ObjectNode executors = registryProperties.putObject("executors");
        executors.put("type", "array");
        ObjectNode executor = executors.putObject("items");
        executor.put("type", "object");
        ObjectNode executorProperties = executor.putObject("properties");
        executorProperties.putObject("executorId").put("type", "string");
        executorProperties.putObject("type").put("type", "string")
                .putArray("enum").add("UNIFIED_PIPELINE").add("CHAT_MODEL");
        executorProperties.putObject("pipelineDefinitionId").put("type", "string");
        executorProperties.putObject("pipelineDefinitionPath").put("type", "string");
        executorProperties.putObject("pipelineDefinition").put("type", "object");
        executorProperties.putObject("provider").put("type", "string");
        executorProperties.putObject("modelId").put("type", "string");
        executorProperties.putObject("prompt").put("type", "string");
        executorProperties.putObject("systemPrompt").put("type", "string");
        executorProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        executor.putArray("required").add("executorId").add("type");
        addObjectArray(props, "routeRules",
                "Content routing rules that select a pipeline for matching documents.");

        ObjectNode runtimeConfig = props.putObject("runtimeConfig");
        runtimeConfig.put("type", "object");
        runtimeConfig.put("description", "Folder-local execution settings. The local crawl engine "
                + "resolves these directly and does not require a running MCP or application server.");
        ObjectNode runtimeProperties = runtimeConfig.putObject("properties");
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
        modelRuntime.put("description", "Read-only execution overrides for folder-local models already provisioned "
                + "by model_runtime. LOCAL_MODEL and encoder routes use MCP-owned pooled subprocesses; crawl "
                + "execution never invokes model staging or mutates the model inventory.");
        ObjectNode modelRuntimeProperties = modelRuntime.putObject("properties");
        modelRuntimeProperties.putObject("localPath").put("type", "string");
        modelRuntimeProperties.putObject("servingExecutable").put("type", "string");
        modelRuntimeProperties.putObject("servingJar").put("type", "string")
                .put("description", "JVM-development-only executable JAR; native CLI runs require servingExecutable.");
        modelRuntimeProperties.putObject("javaExecutable").put("type", "string")
                .put("description", "Optional Java executable for executable-JAR artifacts; otherwise the "
                        + "distribution-aware JavaRuntimeLocator is used (including SDKMAN/Graal Java 17).");
        modelRuntimeProperties.putObject("heapSize").put("type", "string");
        modelRuntimeProperties.putObject("timeoutMinutes").put("type", "integer").put("minimum", 1);
        com.fasterxml.jackson.databind.node.ObjectNode weightDtypeProp =
                modelRuntimeProperties.putObject("weightDtype");
        weightDtypeProp.put("type", "string");
        weightDtypeProp.putArray("enum")
                .add("auto").add("fp32").add("fp16").add("bf16").add("fp8")
                .add("fp8_e5m2").add("int8").add("int4");
        weightDtypeProp.put("description", "Weight storage dtype for the "
                + "staged SDZ created from a raw source artifact. auto (default) keeps weights "
                + "exactly as authored — GGUF packed quantization stays packed (runtime-quantized "
                + "matmul), non-quantized tensors stay dense. Explicit dtypes convert at stage "
                + "time. The staged filename records the dtype; changing it converts fresh.");
        modelRuntimeProperties.putObject("embeddingPlacement").put("type", "string")
                .put("description", "Device placement for the embedding subprocess used by the "
                        + "corpus topic-model pre-pass: cpu, gpu, gpu:<device>, gpu:<device>:<maxBytes> "
                        + "(per-device memory cap). Default inherit — launcher defaults apply. "
                        + "Delivered via ND4J backend priorities/default device, never CUDA_VISIBLE_DEVICES.");
        com.fasterxml.jackson.databind.node.ObjectNode conversionBackendProp =
                modelRuntimeProperties.putObject("conversionBackend");
        conversionBackendProp.put("type", "string");
        conversionBackendProp.putArray("enum").add("cpu").add("gpu").add("auto");
        conversionBackendProp.put("description", "ND4J backend for the one-shot staged conversion child "
                + "(raw artifact → optimized cached SDZ). Default cpu: conversion is I/O "
                + "and dequantize work; gpu opt-in. Both backends live on the serving jar "
                + "classpath; the child picks via org.nd4j.backend priority properties.");
        modelRuntimeProperties.putObject("nd4jConfigJson").put("type", "string")
                .put("description", "Explicit ND4J environment config JSON for the serving child. "
                        + "When omitted, the managed nd4j-environment-config.json (dist config dir, "
                        + "then ~/.kompile/config) is forwarded automatically.");
        modelRuntimeProperties.putObject("optimizerEnabled").put("type", "boolean")
                .put("description", "Explicit serving-child graph optimizer override; takes precedence over nd4jConfigJson.");
        modelRuntimeProperties.putObject("optimizerFp16").put("type", "boolean")
                .put("description", "Explicit serving-child FP16 optimizer override; takes precedence over nd4jConfigJson.");
        ObjectNode deviceLimits = modelRuntimeProperties.putObject("deviceMemoryLimitsBytes");
        deviceLimits.put("type", "array").put("minItems", 1)
                .put("description", "Optional serving-child memory ceilings in bytes, one positive integer per "
                        + "visible logical device in device order. Preserves tighter existing limits. Startup "
                        + "fails if a ceiling cannot be enforced; process and watchdog limits remain active.");
        deviceLimits.putObject("items").put("type", "integer").put("minimum", 1)
                .put("maximum", Long.MAX_VALUE);
        modelRuntimeProperties.putObject("chatTemplate").put("type", "string")
                .put("description", "Chat template override for the serving child; null = model-owned template.");
        modelRuntimeProperties.putObject("kvCacheType").put("type", "string")
                .put("description", "KV cache strategy for the serving child (e.g. STATIC, PAGED); "
                        + "null = model-owned default.");
        modelRuntimeProperties.putObject("maxKvCacheLength").put("type", "integer").put("minimum", 0)
                .put("description", "KV cache length ceiling; 0/null = model-owned default.");
        modelRuntimeProperties.putObject("maxPrefillLength").put("type", "integer").put("minimum", 0)
                .put("description", "Prefill length ceiling; 0/null = model-owned default.");
        modelRuntimeProperties.putObject("continuationEnabled").put("type", "boolean")
                .put("description", "Retained-KV continuation for long generations; null = model default "
                        + "(enabled for direct GGUF decoders).");
        modelRuntimeProperties.putObject("continuationChunkTokens").put("type", "integer").put("minimum", 1)
                .put("description", "Token chunk size for retained-KV continuation; null = default.");
        modelRuntimeProperties.putObject("prefixCacheEnabled").put("type", "boolean")
                .put("description", "Enable cross-request KV prefix reuse for local SameDiff serving. "
                        + "Requires STATIC KV cache; false/null preserves the model runtime default.");
        modelRuntimeProperties.putObject("prefixCacheMaxBytes").put("type", "integer").put("minimum", 0)
                .put("description", "Maximum device bytes retained by the local prefix block pool; "
                        + "0/null uses the bounded runtime heuristic.");
        modelRuntimeProperties.putObject("prefixCacheBlockSize").put("type", "integer").put("minimum", 0)
                .put("description", "Prefix matching granularity in tokens; 0/null uses the runtime default.");
        modelRuntimeProperties.putObject("environment").put("type", "object")
                .putObject("additionalProperties").put("type", "string");

        ObjectNode graphExtraction = props.putObject("graphExtraction");
        graphExtraction.put("type", "object");
        graphExtraction.put("description", "Semantic graph configuration. Supplying this object "
                + "enables the same GraphExtractionOrchestrator used by the parallel batch crawl.");
        ObjectNode graphProperties = graphExtraction.putObject("properties");
        graphProperties.putObject("llmProvider").put("type", "string")
                .put("description", "Native text chat: chat uses the configured provider; chat:<provider> selects it explicitly "
                        + "(e.g. chat:codex, chat:claude). No CLI subprocess/tools, no request credentials. "
                        + "Legacy runtime shorthand: serving/kompile-local launches Kompile's "
                        + "request-scoped serving subprocess; claude, codex/openai, gemini/google, "
                        + "opencode, qwen, pi, or an exact *-cli id launches that CLI agent. "
                        + "Use processingRoute for an explicit fallback chain or API endpoint.");
        graphProperties.putObject("modelName").put("type", "string")
                .put("description", "Request-scoped model override forwarded to the selected native chat, serving, CLI, or API backend.");
        graphProperties.putObject("thinking").put("type", "string")
                .put("description", "CHAT_MODEL only: optional request-scoped provider-native thinking/effort value; omission inherits host chat policy.");
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
        processingRoute.put("description", "Request-scoped semantic graph-extraction model chain (separate from document processor.type). The local MCP path "
                + "runs CHAT_MODEL via native chat with project/global credentials (text only, no tools). CHAT_MODEL stays host-local even with a managed URL. "
                + "launches Kompile's serving subprocess for LOCAL_MODEL, launches CLI_AGENT subprocesses, "
                + "or calls API_AGENT endpoints without a running app server. Every local subprocess is "
                + "stopped when the crawl job reaches a terminal state; poll instead of waiting in the tool call.");
        ObjectNode routeProperties = processingRoute.putObject("properties");
        routeProperties.putObject("fallbackEnabled").put("type", "boolean")
                .put("description", "False pins the first enabled text-capable backend by priority; no backup or default fallback.");
        routeProperties.putObject("servingLaneEnabled").put("type", "boolean");
        ObjectNode backends = routeProperties.putObject("backends");
        backends.put("type", "array").put("minItems", 1);
        ObjectNode backend = backends.putObject("items");
        backend.put("type", "object");
        ObjectNode backendProperties = backend.putObject("properties");
        backendProperties.putObject("id").put("type", "string");
        backendProperties.putObject("displayName").put("type", "string");
        backendProperties.putObject("type").put("type", "string")
                .putArray("enum").add("CLI_AGENT").add("API_AGENT").add("LOCAL_MODEL").add("CHAT_MODEL");
        backendProperties.putObject("provider").put("type", "string")
                .put("description", "CHAT_MODEL native provider (e.g. codex, claude, openai, custom); omitted uses configured provider. Not a CLI id.");
        backendProperties.putObject("agentName").put("type", "string")
                .put("description", "CLI registry id for CLI_AGENT; 'serving' for Kompile's request-scoped LOCAL_MODEL subprocess.");
        backendProperties.putObject("endpointUrl").put("type", "string")
                .put("description", "OpenAI-compatible base URL for API_AGENT (the dispatcher appends /chat/completions).");
        backendProperties.putObject("apiKey").put("type", "string")
                .put("description", "API_AGENT only. CHAT_MODEL rejects apiKey/endpointUrl; configure credentials/endpoints in ChatConfig.");
        backendProperties.putObject("modelName").put("type", "string");
        backendProperties.putObject("thinking").put("type", "string")
                .put("description", "CHAT_MODEL only: optional request-scoped provider-native thinking/effort value; omission inherits host chat policy.");
        backendProperties.putObject("priority").put("type", "integer");
        backendProperties.putObject("maxConcurrent").put("type", "integer").put("minimum", 0);
        backendProperties.putObject("requestsPerMinute").put("type", "integer").put("minimum", 0);
        addStringArray(backendProperties, "capabilities", "CHAT_MODEL accepts only llm/text (also when omitted); embedding, vlm, tools and required-choice claims are rejected.");
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
        if (requiresLocalExecution(params) || !client.isAvailable()) {
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
                        : ("DIRECTORY".equals(sourceType)
                        || LocalExternalSourceLoaderRegistry.supports(sourceType) ? 0 : 1));
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
            List<String> configurationWarnings = pipelineConfigurationWarnings(request);

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
            if (!configurationWarnings.isEmpty()) {
                output.append(" Configuration warnings: ")
                        .append(String.join(" ", configurationWarnings));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("jobId", jobId);
            metadata.put("status", status);
            metadata.put("sourceCount", sourceCount);
            metadata.put("codeProjectCount", codeProjectResolution.added());
            metadata.put("factSheetId", factSheetId);
            metadata.put("codeProjectFactSheetBindings",
                    codeProjectResolution.added() > 0 ? "managed-by-crawl" : "not-applicable");
            metadata.put("configurationWarnings", configurationWarnings);
            metadata.put("requestedConfiguration", LocalCrawlJobStore.redact(request));
            JsonNode effectiveConfiguration = firstConfigurationNode(body);
            if (effectiveConfiguration != null) {
                metadata.put("effectiveConfiguration", LocalCrawlJobStore.redact(effectiveConfiguration));
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
                    for (JsonNode existing : sources) {
                        if (rootPath.equals(text(existing, "pathOrUrl")) && existing instanceof ObjectNode object) {
                            applyCodeProjectMetadata(project, rootPath, object);
                            break;
                        }
                    }
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

        applyCodeProjectMetadata(project, rootPath, source);
    }

    private void applyCodeProjectMetadata(JsonNode project, String rootPath, ObjectNode source) {
        String projectId = firstNonBlank(text(project, "codeProjectId"), text(project, "id"));
        String projectName = firstNonBlank(text(project, "name"), projectId, rootPath);

        JsonNode configured = source.get("properties");
        ObjectNode properties = configured != null && configured.isObject()
                ? (ObjectNode) configured : source.putObject("properties");
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
        boolean explicitTarget = request.hasNonNull("factSheetId") || request.hasNonNull("factSheetName");
        if (bindings.size() > 1 && !explicitTarget) {
            return "Selected Kompile code projects are bound to different fact sheets: " + bindings;
        }
        if (bindings.isEmpty()) {
            return null;
        }
        long boundId = bindings.iterator().next();
        if (explicitTarget) {
            // Managed crawl owns rebinding. Remove stale source scope so only the request's resolved
            // fact sheet reaches the server-side structural projection barrier.
            for (JsonNode source : sources) {
                if (source.path("properties") instanceof ObjectNode properties) {
                    properties.remove("factSheetId");
                }
            }
            return null;
        }
        request.put("factSheetId", boundId);
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

    /** Host credentials and request-scoped processor contracts must never be lost through a managed DTO. */
    static boolean requiresLocalExecution(JsonNode params) {
        return params != null && params.isObject() && (params.path("dryRun").asBoolean(false)
                || params.path("config").path("dryRun").asBoolean(false)
                || LocalProjectGraphBackend.usesNativeChat(params)
                || requiresRequestScopedPipelineExecution(params) || requiresProjectLocalSource(params));
    }

    private static boolean requiresRequestScopedPipelineExecution(JsonNode params) {
        return hasRequestScopedPipelineContract(params)
                || hasRequestScopedPipelineContract(params == null ? null : params.get("config"));
    }

    private static boolean requiresProjectLocalSource(JsonNode params) {
        return containsSourceType(params == null ? null : params.get("documents"), "OBSIDIAN")
                || containsSourceType(params == null ? null : params.get("sources"), "OBSIDIAN")
                || containsSourceType(params == null ? null : params.path("config").get("documents"), "OBSIDIAN")
                || containsSourceType(params == null ? null : params.path("config").get("sources"), "OBSIDIAN");
    }

    private static boolean containsSourceType(JsonNode sources, String requiredType) {
        if (sources == null || !sources.isArray()) return false;
        for (JsonNode source : sources) {
            String sourceType = text(source, "sourceType");
            if (sourceType != null && requiredType.equalsIgnoreCase(
                    sourceType.replace('-', '_'))) return true;
        }
        return false;
    }

    private static boolean hasRequestScopedPipelineContract(JsonNode request) {
        if (request == null || !request.isObject()) {
            return false;
        }
        if (request.hasNonNull("pipelineRegistry") || request.hasNonNull("registeredPipelines")
                || request.hasNonNull("modelRuntime")) {
            return true;
        }
        if (LocalCrawlCapabilities.CHAT_MODEL_PIPELINE.equalsIgnoreCase(
                text(request, "defaultPipelineId"))) {
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
            if (value.isObject()) {
                if ("CHAT_MODEL".equalsIgnoreCase(text(value, "pipelineType"))
                        || LocalCrawlCapabilities.CHAT_MODEL_PIPELINE.equalsIgnoreCase(
                        text(value, "pipelineId"))) {
                    return true;
                }
                if (List.of(
                        "registeredPipelineId", "executorId", "processor", "pipelineDefinition",
                        "pipelineDefinitionId", "pipelineDefinitionPath", "modelBindings", "modelDefinitions", "modelRefs")
                        .stream().anyMatch(value::hasNonNull)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Explain the two easy-to-miss configuration hazards for inherited model pipelines.
     * This is intentionally a warning rather than a validation error: a registered pipeline may
     * legitimately provide these values, but the caller should be able to see that they were not
     * selected explicitly in this request.
     */
    static List<String> pipelineConfigurationWarnings(JsonNode request) {
        JsonNode pipelines = request == null ? null : request.get("pipelines");
        if (pipelines == null || !pipelines.isArray()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode pipeline : pipelines) {
            if (!pipeline.isObject()) {
                continue;
            }
            String registeredId = text(pipeline, "registeredPipelineId");
            if (registeredId == null) {
                continue;
            }
            String pipelineId = text(pipeline, "pipelineId");
            String type = text(pipeline, "pipelineType");
            String normalizedType = type == null ? "" : type.toUpperCase(Locale.ROOT);
            boolean modelPipeline = normalizedType.contains("VLM")
                    || normalizedType.contains("OCR")
                    || (pipelineId != null && (pipelineId.toLowerCase(Locale.ROOT).contains("vlm")
                    || pipelineId.toLowerCase(Locale.ROOT).contains("ocr")));
            if (!modelPipeline) {
                continue;
            }

            JsonNode options = pipeline.path("options");
            boolean explicitModel = List.of(
                    "modelId", "vlmModel", "modelSetId", "modelBindings", "modelRefs")
                    .stream().anyMatch(field -> pipeline.hasNonNull(field) || options.hasNonNull(field));
            boolean explicitOutputFormat = options.hasNonNull("outputFormat")
                    || pipeline.hasNonNull("outputFormat");
            if (!explicitModel || !explicitOutputFormat) {
                StringBuilder warning = new StringBuilder("Pipeline '")
                        .append(firstNonBlank(pipelineId, registeredId))
                        .append("' inherits registeredPipelineId '").append(registeredId)
                        .append("'. Effective model/output/generation values come from the inherited definition");
                if (!explicitModel) {
                    warning.append("; bind modelId/modelBindings explicitly");
                }
                if (!explicitOutputFormat) {
                    warning.append("; set options.outputFormat explicitly");
                }
                warning.append("; use dryRun=true to inspect the resolved configuration.");
                warnings.add(warning.toString());
            }
        }
        return warnings;
    }

    private static JsonNode firstConfigurationNode(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String field : List.of(
                "effectiveConfiguration", "effectiveRequest", "resolvedConfiguration", "pipelineResolution")) {
            JsonNode value = body.get(field);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
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
