/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.app.core.chunking.NoOpChunker;
import ai.kompile.app.core.chunking.RecursiveCharacterTextChunker;
import ai.kompile.app.core.chunking.SentenceTextChunker;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.project.KompileProjectCrawlProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Executable capability registry for project-local crawls.
 *
 * <p>The managed crawl service and the local MCP backend accept the same pipeline vocabulary. This
 * registry is the local implementation of that vocabulary: discovery is generated from the same
 * loader/chunker definitions used during execution, and request validation happens before any crawl
 * artifacts are written.</p>
 */
public final class LocalCrawlCapabilities {
    public static final String STANDARD_TEXT_PIPELINE = "standard-text";
    /** Provider-neutral text -> local model -> text composition template. */
    public static final String TEXT_MODEL_PIPELINE = "text-model-text";
    public static final String TEXT_TO_TEXT_PIPELINE = TEXT_MODEL_PIPELINE;
    /** Text/image/PDF document extraction through the configured Kompile chat provider. */
    public static final String CHAT_MODEL_PIPELINE = "chat-model-document";
    public static final String CODE_PIPELINE = "code";
    public static final String VLM_PIPELINE = "vlm-document";
    /** Composed image preprocessing -> vision models -> text composition template. */
    public static final String VISION_PIPELINE = "vision-multimodel";
    public static final String VISION_COMPOSED_PIPELINE = VISION_PIPELINE;
    public static final String OCR_PIPELINE = "ocr-document";
    public static final String TABLE_AWARE_PIPELINE = "table-aware";
    public static final String KEYWORD_ONLY_PIPELINE = "keyword-only";

    private static final Set<String> BUILTIN_PIPELINE_TYPES = Set.of(
            "STANDARD_TEXT", "LLM", "CHAT_MODEL", "VLM", "OCR", "CODE", "TABLE_AWARE",
            "KEYWORD_ONLY", "CUSTOM");
    private static final Set<String> EXECUTOR_TYPES = Set.of("UNIFIED_PIPELINE", "CHAT_MODEL");
    private static final Pattern PIPELINE_TYPE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]*");
    private static final Set<String> SUPPORTED_STEPS = Set.of(
            "LOADING", "MARKDOWN_EXTRACTION", "CHUNKING", "LEXICAL_INDEX");
    private static final Set<String> LOCAL_GRAPH_STEPS = Set.of(
            "GRAPH_EXTRACTION", "VECTOR_INDEXING", "ENTITY_RESOLUTION", "ENRICHMENT", "LEARNING");
    private static final Map<String, String> LOADER_ALIASES = loaderAliases();
    private static final Map<String, String> CHUNKER_ALIASES = chunkerAliases();

    private LocalCrawlCapabilities() {
    }

    /** Build discovery data from the executable local registry. */
    public static ObjectNode catalog(ObjectMapper mapper, String executionMode) {
        ObjectNode catalog = mapper.createObjectNode();
        ArrayNode templates = catalog.putArray("pipelineTemplates");
        pipelineTemplate(templates, STANDARD_TEXT_PIPELINE, "STANDARD_TEXT", "auto",
                "recursive-character", 2_000, 200,
                "General filesystem documents with format-aware loading and recursive chunking.");
        ObjectNode textModelTemplate = pipelineTemplate(templates, TEXT_MODEL_PIPELINE, "LLM", "text",
                "sentence", 2_000, 200,
                "Provider-neutral text input to a caller-selected local language model and text output.");
        ObjectNode textModelConfiguration = textModelTemplate.putObject("configuration");
        textModelConfiguration.put("modelSelection", "caller-configured");
        textModelConfiguration.putArray("modelRoles").add("default");
        textModelConfiguration.putArray("pipelineOptionFields")
                .add("modelId").add("modelSetId").add("modelBindings")
                .add("modelDefinitions").add("modelRefs").add("maxNewTokens")
                .add("temperature").add("topK").add("timeoutMinutes");
        textModelTemplate.put("executionModel", "unified-pipeline-runtime")
                .put("definitionFormat", "UnifiedPipelineDefinition with a concrete pipelineSpec")
                .set("pipelineDefinition", mapper.valueToTree(builtinTextModelProcessor()
                        .get("pipelineDefinition")));
        ObjectNode chatModelTemplate = pipelineTemplate(
                templates, CHAT_MODEL_PIPELINE, "CHAT_MODEL", "auto",
                "sentence", 2_000, 200,
                "Text, image, or PDF extraction through the project/global Kompile chat provider.");
        ObjectNode chatModelConfiguration = chatModelTemplate.putObject("configuration");
        chatModelConfiguration.put("credentialSource", "project/global chat configuration")
                .put("modelSelection", "active chat model or caller modelId/modelBindings.default")
                .put("processorType", ChatModelPipelineRunner.PROCESSOR_TYPE)
                .putArray("supportedInputTypes")
                .add("text/*").add("application/pdf").add("image/png").add("image/jpeg")
                .add("image/gif").add("image/webp");
        chatModelConfiguration.putArray("pipelineOptionFields")
                .add("provider").add("modelId").add("modelBindings").add("modelDefinitions")
                .add("thinking").add("prompt").add("systemPrompt").add("outputFormat")
                .add("maxInputChars").add("maxResponseChars").add("maxImageBytes")
                .add("maxPages").add("pageRange").add("pageBatchSize").add("pdfRenderDpi");
        chatModelConfiguration.put("capabilityProbe", "pipeline action=capabilities provider/model/operation; probe=true sends one bounded synthetic request")
                .put("modelCapabilities", "UNKNOWN until operation-specific inference; wire image support is not model vision proof");
        chatModelTemplate.put("executionModel", "mcp-host-chat-provider")
                .put("credentialsPersistedInCrawl", false)
                .put("localModelArtifactRequired", false)
                .put("multimodal", true);
        pipelineTemplate(templates, CODE_PIPELINE, "CODE", "code",
                "recursive-character", 1_800, 180,
                "Source code and project files with code validation and boundary-aware chunking.");
        ObjectNode vlmTemplate = pipelineTemplate(templates, VLM_PIPELINE, "VLM", "pdf",
                "recursive-character", 2_000, 200,
                "Model-backed PDF extraction through the reusable unified pipeline runtime.");
        ObjectNode vlmConfiguration = vlmTemplate.putObject("configuration");
        vlmConfiguration.put("modelSelection", "caller-configured");
        vlmConfiguration.put("modelDefinitionTool", "vlm_model_definition");
        vlmConfiguration.putArray("pipelineOptionFields")
                .add("modelId").add("vlmModel").add("modelSetId")
                .add("outputFormat").add("maxPages").add("pageRange")
                .add("maxResponseBytes").add("maxNewTokens").add("maxKvLen")
                .add("adaptiveRegionFallbackEnabled")
                .add("pdfRenderDpi").add("pageBatchSize")
                .add("temperature").add("topP").add("beamSize").add("doSample")
                .add("kvCacheEnabled").add("kvCacheMaxEntries").add("timeoutMinutes");
        vlmTemplate.put("executionModel", "unified-pipeline-runtime")
                .put("supportedInputTypes", "application/pdf")
                .put("ocrSemantics", "VLM-based document extraction after PDF page rendering")
                .put("recommendedFor", "Scanned or image-heavy PDFs; prefer an active project pipeline such as vlm-ocr-pdf when available")
                .put("canonicalScannedPdf", true)
                .put("definitionFormat", "UnifiedPipelineDefinition with a concrete pipelineSpec");
        vlmConfiguration.put("modelLifecycle",
                "The MCP runtime resolves or bootstraps bound models and acquires a reusable isolated runtime automatically.");
        ObjectNode visionTemplate = pipelineTemplate(templates, VISION_PIPELINE, "VLM", "auto",
                "recursive-character", 2_000, 200,
                "Composable image preprocessing, vision encoder, text embedding, fusion, decoding, and text output.");
        ObjectNode visionConfiguration = visionTemplate.putObject("configuration");
        visionConfiguration.put("modelSelection", "caller-configured");
        visionConfiguration.putArray("modelRoles")
                .add("visionEncoder").add("textEmbedding").add("decoder");
        visionConfiguration.putArray("pipelineOptionFields")
                .add("modelBindings").add("modelDefinitions").add("modelRefs")
                .add("tileSize").add("maxTiles").add("imageTokenId")
                .add("eosTokenId").add("maxNewTokens").add("timeoutMinutes");
        visionTemplate.put("executionModel", "unified-pipeline-runtime")
                .put("inputContract", "image preprocessing consumes pipeline input image data; the composed text path consumes input_ids "
                        + "and optional attention_mask/position_ids (raw text needs a tokenizer/adapter step)")
                .put("definitionFormat", "UnifiedPipelineDefinition with a concrete GraphPipeline pipelineSpec")
                .set("pipelineDefinition", mapper.valueToTree(builtinVisionProcessor()
                        .get("pipelineDefinition")));
        ObjectNode ocrTemplate = pipelineTemplate(templates, OCR_PIPELINE, "OCR", "pdf",
                "recursive-character", 2_000, 200,
                "Generic PDF OCR pipeline contract through the reusable unified pipeline runtime.");
        ocrTemplate.put("executionModel", "unified-pipeline-runtime")
                .put("supportedInputTypes", "application/pdf")
                .put("ocrSemantics", "Executor-defined OCR; this template does not imply a bundled traditional OCR engine")
                .put("recommendedFor", "Projects that explicitly register an OCR executor or model; use VLM for the verified scanned-PDF path")
                .put("canonicalScannedPdf", false)
                .put("definitionFormat", "UnifiedPipelineDefinition with a concrete pipelineSpec");
        pipelineTemplate(templates, TABLE_AWARE_PIPELINE, "TABLE_AWARE", "table",
                "recursive-character", 2_000, 200,
                "Table-preserving HTML/PDF extraction; set options.modelBacked=true for VLM extraction.");
        pipelineTemplate(templates, KEYWORD_ONLY_PIPELINE, "KEYWORD_ONLY", "auto",
                "recursive-character", 2_000, 200,
                "Local lexical indexing without an embedding stage.");

        ArrayNode loaders = catalog.putArray("loaders");
        loader(loaders, "auto", List.of("local-text", "local-knowledge"),
                List.of("file/*"), "Select pdf, html, markdown, Excel, code, or text from each file.");
        loader(loaders, "text", List.of("plain-text"),
                List.of("text/*", "application/json", "application/xml"),
                "UTF-8 text loader with normalized whitespace.");
        loader(loaders, "markdown", List.of("md"), List.of("text/markdown"),
                "Markdown-preserving UTF-8 loader.");
        loader(loaders, "html", List.of("web-html"), List.of("text/html"),
                "HTML-to-Markdown loader with table and heading preservation.");
        loader(loaders, "excel", List.of("xlsx", "spreadsheet", "office-excel"),
                List.of("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.oasis.opendocument.spreadsheet"),
                "Apache POI Excel loader with sheet tables and formula dependency graph extraction.");
        loader(loaders, "pdf", List.of("pdfbox"), List.of("application/pdf"),
                "PDFBox loader with pdftotext fallback in native mode.");
        loader(loaders, "code", List.of("source-code"), List.of("text/x-source"),
                "UTF-8 source-code loader restricted to known code and build formats.");
        loader(loaders, "table", List.of("table-aware"),
                List.of("text/csv", "text/tab-separated-values", "text/html", "application/pdf"),
                "Table-preserving CSV/TSV/HTML loader with layout-preserving PDF text fallback.");
        loader(loaders, "external-materialized", List.of("external-source"), List.of("text/markdown"),
                "Reads sanitized source metadata and content materialized by a local external connector.");

        ArrayNode chunkers = catalog.putArray("chunkers");
        chunker(chunkers, "recursive-character", List.of("fixed", "local-fixed", "markdown-fixed"),
                2_000, 200, "Recursive separator-aware character chunking.");
        chunker(chunkers, "sentence", List.of("sentence-text"), 800, 100,
                "Sentence-boundary chunking with paragraph preservation.");
        chunker(chunkers, "no-op", List.of("none", "whole-document"), 0, 0,
                "One chunk per normalized document.");

        ArrayNode steps = catalog.putArray("steps");
        for (String step : SUPPORTED_STEPS) {
            steps.addObject().put("id", step).put("available", true);
        }
        catalog.putObject("routing")
                .put("precedence", "document.pipelineId > routeRules > defaultPipelineId > automatic")
                .putArray("supportedFields")
                .add("contentTypes").add("fileExtensions").add("sourceTypes")
                .add("minSizeBytes").add("maxSizeBytes").add("contentPatterns");
        catalog.put("executionMode", executionMode);
        catalog.put("distributed", false);
        catalog.putObject("asyncLifecycle")
                .put("default", true)
                .put("startTool", "crawl_documents or crawl_source")
                .put("statusTool", "crawl_control")
                .put("statusOperation", "status")
                .put("resultTool", "crawl_result")
                .put("cancelOperation", "cancel")
                .put("pollAfterMs", 1000)
                .put("progressFields", "stage, stageDetail, progressPercent, stageUpdatedAt")
                .put("terminalStatuses", "COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED")
                .put("retention", "bounded MCP-host registry; completed handles expire after configured retention")
                .put("agentRule", "Never repeat a start while terminal=false; poll the returned jobId and respect pollAfterMs.");
        ObjectNode systems = catalog.putObject("pipelineSystems");
        systems.putObject("unified")
                .put("selector", "Every model-backed pipeline")
                .put("definition", "UnifiedPipelineDefinition")
                .put("pipelineSpec", "Concrete serialized Pipeline with @class, such as SequencePipeline or GraphPipeline")
                .put("runtime", "MCP-owned pooled stdio runtime; no executable or process configuration is accepted from callers")
                .put("modelRoleResolution", "modelRole and tokenizerRole parameters are resolved from caller modelBindings before launch")
                .put("reuse", "Compatible definitions and resolved model artifacts share a bounded warm process");

        ObjectNode wiring = catalog.putObject("wiringRecipe");
        wiring.put("workflow",
                "1) crawl_discover section=pipelines; 2) model_runtime status/bootstrap/import; "
                        + "3) choose a pipelineId and bind a model; 4) crawl_documents dryRun=true; "
                        + "5) start asynchronously; 6) poll crawl_control operation=status using jobId and pollAfterMs; "
                        + "7) call crawl_result when terminal=true.");
        wiring.put("modelBinding",
                "Use pipeline.modelId/vlmModel for one model, modelBindings for role-to-model ids, "
                        + "or modelRefs for project manifest models.");
        ObjectNode unifiedRequest = wiring.putObject("unifiedVlm")
                .put("contract", "PDF + model binding + unified pipeline definition");
        ObjectNode minimumRequest = unifiedRequest.putObject("minimumRequest");
        minimumRequest.putArray("documents").addObject()
                .put("path", "docs/report.pdf").put("pipelineId", "pdf-vlm");
        minimumRequest.putArray("pipelines").addObject()
                .put("pipelineId", "pdf-vlm")
                .put("pipelineType", "VLM")
                .put("modelId", "<configured-model-id>");
        minimumRequest.put("modelDefinitionTool", "vlm_model_definition");
        minimumRequest.put("defaultPipelineId", "pdf-vlm").put("dryRun", true);
        minimumRequest.putObject("modelRuntime").put("autoBootstrap", true);
        wiring.putObject("scannedPdf")
                .put("canonicalPipelineType", "VLM")
                .put("preferredProjectPipelineId", "vlm-ocr-pdf")
                .put("fallbackTemplate", VLM_PIPELINE)
                .put("inputContract", "application/pdf")
                .put("guidance", "Use the project-registered VLM OCR pipeline for scanned or image-heavy PDFs; ocr-document is only for an explicitly configured OCR executor.");
        wiring.putObject("textModelText")
                .put("pipelineId", TEXT_MODEL_PIPELINE)
                .put("contract", "text input + modelBindings.default + local model resolution + text output");
        wiring.putObject("chatModelDocument")
                .put("pipelineId", CHAT_MODEL_PIPELINE)
                .put("contract", "text/image/PDF input + isolated configured chat call + Markdown output")
                .put("credentialSource", "ChatConfig/CredentialStore; never pipeline JSON")
                .put("modelOverride", "pipeline.modelId or a source=chat modelBindings.default reference")
                .put("localAlternative", "Use text-model-text, vlm-document, or another UNIFIED_PIPELINE definition");
        wiring.putObject("visionMultimodel")
                .put("pipelineId", VISION_PIPELINE)
                .put("contract", "image preprocessing + role-bound visionEncoder/textEmbedding/decoder models + text output");
        wiring.putObject("customDefinition")
                .put("sources", "pipelineDefinition inline, pipelineDefinitionPath, pipelineDefinitionId, pipelineRegistry.definitions, or registeredPipelines")
                .put("contract", "caller owns the concrete SequencePipeline or GraphPipeline composition; no model/provider is inferred");

        ObjectNode typeGuide = catalog.putObject("pipelineTypeGuide");
        typeGuide.put("VLM",
                "Canonical scanned/image-heavy PDF path. PDF pages are rendered and processed by a bound vision-language model; prefer the active vlm-ocr-pdf project pipeline when advertised.");
        typeGuide.put("OCR",
                "Generic OCR pipeline type for an explicitly registered OCR executor or model. It does not automatically select a traditional OCR engine.");
        typeGuide.put("LLM",
                "Use the text-model-text composition or provide a custom UnifiedPipelineDefinition with a caller-selected model binding.");
        typeGuide.put("CHAT_MODEL",
                "Use chat-model-document or processor.type=CHAT_MODEL for an isolated call through the configured direct chat provider. Text is sent directly; PDFs are rendered into bounded image batches.");
        typeGuide.put("COMPOSED_VISION",
                "Use vision-multimodel for image_preprocess plus role-bound visionEncoder, textEmbedding, and decoder steps; replace it with a custom graph when needed.");
        typeGuide.put("STANDARD_TEXT/CODE/TABLE_AWARE/KEYWORD_ONLY",
                "pipelineType + loaderName/chunkerName/options; model-backed steps still use the same unified runtime.");
        typeGuide.put("CUSTOM",
                "UNIFIED_PIPELINE with pipelineDefinition/pipelineSpec.@class; arbitrary executable branches are not supported.");

        ObjectNode registry = catalog.putObject("pipelineRegistry");
        registry.put("requestField", "pipelineRegistry")
                .put("projectDefaults", "active kompile.project.json pipeline registrations")
                .put("definitionFormat", "UnifiedPipelineDefinition")
                .put("modelDefinitions", "pipelineRegistry.models")
                .put("modelBindings", "pipeline.modelBindings role-to-model map")
                .put("folderDefinitions", ".kompile/pipelines/unified/*.json")
                .put("globalDefinitions", "${kompile.data.dir:-~/.kompile}/pipelines/unified/*.json")
                .put("customDefinitionSources", "inline pipelineDefinition, pipelineDefinitionPath, pipelineDefinitionId, pipelineRegistry.definitions, registeredPipelines")
                .put("arbitraryPipelineTypes", true);
        ArrayNode executorTypes = registry.putArray("executorTypes");
        EXECUTOR_TYPES.stream().sorted().forEach(executorTypes::add);
        ArrayNode pipelineTypes = registry.putArray("builtinPipelineTypes");
        BUILTIN_PIPELINE_TYPES.stream().sorted().forEach(pipelineTypes::add);

        ObjectNode modelProcessing = catalog.putObject("modelProcessing")
                .put("available", true)
                .put("callerDefinedUnifiedPipelines", true)
                .put("execution", "MCP-owned reusable stdio pipeline runtimes; no application server required")
                .put("remoteChatExecution", "CHAT_MODEL runs in the MCP host using project/global chat credentials without copying secrets into the pipeline")
                .put("semanticServing", "processingRoute LOCAL_MODEL/serving or graphExtraction.llmProvider=serving")
                .put("finalReasoningLearning", "portable FOL/PSL/MEBN hybrid-consensus models stored in the folder .kgraph")
                .put("lifecycle", "leases release after each call; compatible children remain warm for bounded reuse")
                .put("configuration", "pipelineRegistry models/defaults/definitions and project pipeline registrations");
        return catalog;
    }

    /** Return a user-facing validation error, or {@code null} when the request is executable. */
    public static String validationError(JsonNode request) {
        if (request == null || request.isNull()) {
            return null;
        }
        try {
            JsonNode runtimeConfig = request.get("runtimeConfig");
            if (runtimeConfig != null && !runtimeConfig.isNull() && !runtimeConfig.isObject()) {
                return "runtimeConfig must be an object.";
            }
            String registryError = registryError(request);
            if (registryError != null) return registryError;
            Map<String, PipelineDefinition> pipelines = pipelineDefinitions(request);
            JsonNode definitions = request.get("pipelines");
            if (definitions != null && !definitions.isNull() && !definitions.isArray()) {
                return "pipelines must be an array.";
            }
            if (definitions != null && definitions.isArray()) {
                Set<String> ids = new LinkedHashSet<>();
                for (int i = 0; i < definitions.size(); i++) {
                    JsonNode definition = definitions.get(i);
                    if (!definition.isObject()) {
                        return "pipelines[" + i + "] must be an object.";
                    }
                    String id = text(definition, "pipelineId");
                    if (id == null) {
                        return "pipelines[" + i + "].pipelineId is required.";
                    }
                    if (!ids.add(id)) {
                        return "Duplicate local pipelineId: " + id;
                    }
                    String type = firstNonBlank(text(definition, "pipelineType"), "CUSTOM");
                    if (!PIPELINE_TYPE_PATTERN.matcher(type).matches()) {
                        return "pipelines[" + i + "].pipelineType must be a portable identifier; got " + type;
                    }
                    String componentError = componentError(definition, "pipelines[" + i + "]");
                    if (componentError != null) return componentError;
                    String bindingError = modelBindingError(definition, "pipelines[" + i + "]");
                    if (bindingError != null) return bindingError;
                }
            }

            String defaultPipeline = text(request, "defaultPipelineId");
            if (defaultPipeline != null && !pipelines.containsKey(defaultPipeline)) {
                return "Unknown local defaultPipelineId: " + defaultPipeline;
            }
            JsonNode routes = request.get("routeRules");
            if (routes != null && !routes.isNull() && !routes.isArray()) {
                return "routeRules must be an array.";
            }
            if (routes != null && routes.isArray()) {
                for (int i = 0; i < routes.size(); i++) {
                    JsonNode route = routes.get(i);
                    if (!route.isObject()) return "routeRules[" + i + "] must be an object.";
                    String pipelineId = text(route, "pipelineId");
                    if (pipelineId == null || !pipelines.containsKey(pipelineId)) {
                        return "routeRules[" + i + "] references unknown local pipelineId: " + pipelineId;
                    }
                }
            }

            JsonNode documents = request.get("documents");
            if (documents != null && documents.isArray()) {
                for (int i = 0; i < documents.size(); i++) {
                    JsonNode document = documents.get(i);
                    if (!document.isObject()) continue;
                    String pipelineId = text(document, "pipelineId");
                    if (pipelineId != null && !pipelines.containsKey(pipelineId)) {
                        return "documents[" + i + "] references unknown local pipelineId: " + pipelineId;
                    }
                    String componentError = componentError(document, "documents[" + i + "]");
                    if (componentError != null) return componentError;
                    String bindingError = modelBindingError(document, "documents[" + i + "]");
                    if (bindingError != null) return bindingError;
                }
            }

            JsonNode steps = request.get("steps");
            if (steps != null && !steps.isNull() && !steps.isArray()) {
                return "steps must be an array.";
            }
            if (steps != null && steps.isArray() && request.path("strictSteps").asBoolean(false)) {
                for (JsonNode step : steps) {
                    String id = step.asText("").toUpperCase(Locale.ROOT);
                    if (!SUPPORTED_STEPS.contains(id) && !LOCAL_GRAPH_STEPS.contains(id)) {
                        return "Step " + id + " is unavailable in the project-local pipeline.";
                    }
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Resolve the effective pipeline for one concrete file. */
    public static ResolvedPipeline resolve(JsonNode request,
                                           KompileProjectCrawlProfile profile,
                                           Path sourceRoot,
                                           Path file) throws IOException {
        Map<String, PipelineDefinition> pipelines = pipelineDefinitions(request);
        JsonNode document = matchingDocument(request, sourceRoot, file);
        String pipelineId = text(document, "pipelineId");
        boolean explicitlySelectedPipeline = pipelineId != null;
        if (pipelineId == null) pipelineId = routedPipeline(request, file);
        explicitlySelectedPipeline |= pipelineId != null;
        if (pipelineId == null) pipelineId = text(request, "defaultPipelineId");
        explicitlySelectedPipeline |= pipelineId != null;
        if (pipelineId == null) pipelineId = isCodeFile(file) ? CODE_PIPELINE
                : profile != null && profile.isMultimodal() ? VLM_PIPELINE : STANDARD_TEXT_PIPELINE;

        PipelineDefinition pipeline = pipelines.get(pipelineId);
        if (pipeline == null) {
            throw new IllegalArgumentException("Unknown local pipelineId: " + pipelineId);
        }
        Map<String, Object> processor = new LinkedHashMap<>(pipeline.processor());
        applyExecutorReference(request, document, processor);
        mergeProcessorOverride(processor, document == null ? null : document.get("processor"));
        applyDefinitionReference(request, document, processor);
        // Preset VLM/OCR loaders describe the local artifact runner, not a remote
        // chat model's input contract. Explicit user loader restrictions still win.
        String pipelineLoader = "CHAT_MODEL".equals(processor.get("type")) && !pipeline.explicitLoader()
                ? "auto" : pipeline.loaderName();
        String profileLoader = profile == null ? null : profile.getLoader();
        String profileChunker = profile == null ? null : profile.getChunker();
        String documentLoader = text(document, "loaderName");
        String crawlerLoader = crawlerLoader(document);
        String loader = explicitlySelectedPipeline
                ? firstNonBlank(documentLoader, crawlerLoader, pipelineLoader, profileLoader, "auto")
                : firstNonBlank(documentLoader, crawlerLoader, profileLoader, pipelineLoader, "auto");
        String chunker = explicitlySelectedPipeline
                ? firstNonBlank(text(document, "chunkerName"), pipeline.chunkerName(), profileChunker,
                "recursive-character")
                : firstNonBlank(text(document, "chunkerName"), profileChunker, pipeline.chunkerName(),
                "recursive-character");
        loader = canonicalLoader(loader);
        chunker = canonicalChunker(chunker);
        if (loader == null) {
            throw new IllegalArgumentException("Unknown project-local loader: "
                    + firstNonBlank(text(document, "loaderName"), profileLoader, pipeline.loaderName()));
        }
        if (chunker == null) {
            throw new IllegalArgumentException("Unknown project-local chunker: "
                    + firstNonBlank(text(document, "chunkerName"), profileChunker, pipeline.chunkerName()));
        }
        if ("auto".equals(loader)) loader = automaticLoader(file);

        int chunkSize = positiveInt(document, "chunkSize", pipeline.chunkSize());
        int chunkOverlap = nonNegativeInt(document, "chunkOverlap", pipeline.chunkOverlap());
        if ("no-op".equals(chunker)) {
            chunkSize = 0;
            chunkOverlap = 0;
        } else if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize for " + file);
        }
        Map<String, Object> options = new LinkedHashMap<>(pipeline.options());
        if (profile != null && profile.getVlmModel() != null && !profile.getVlmModel().isBlank()) {
            options.putIfAbsent("vlmModel", profile.getVlmModel());
        }
        mergeOptions(options, document == null ? null : document.get("properties"));
        mergeOptions(options, document == null ? null : document.get("options"));
        mergeOptions(options, document == null ? null : document.get("chunkerOptions"));
        for (String field : List.of("pipelineDefinition", "pipelineDefinitionPath", "modelSetId",
                "pipelineDefinitionId", "modelId", "vlmModel", "modelBindings", "modelDefinitions",
                "modelRefs", "processingMode")) {
            copyOption(options, document, field);
        }
        JsonNode modelRuntime = request == null ? null : request.get("modelRuntime");
        // A request-level runtime is not a model pipeline by itself. Keep ordinary
        // STANDARD_TEXT ingestion on the local loader path unless the resolved
        // pipeline already has an executable processor definition.
        if (!processor.isEmpty() && modelRuntime != null && modelRuntime.isObject()) {
            processor.put("modelRuntime", jsonValue(modelRuntime));
        }
        validateAllowedContentTypes(document, file);
        return new ResolvedPipeline(pipelineId, pipeline.pipelineType(), loader, chunker,
                chunkSize, chunkOverlap, Map.copyOf(options), Map.copyOf(processor));
    }

    /** Execute one of the registered TextChunker implementations. */
    public static List<String> chunk(String documentId, String text, ResolvedPipeline pipeline) {
        if (text == null || text.isBlank()) return List.of();
        TextChunker chunker = switch (pipeline.chunkerName()) {
            case "sentence" -> new SentenceTextChunker();
            case "no-op" -> new NoOpChunker();
            default -> new RecursiveCharacterTextChunker();
        };
        Map<String, Object> options = new HashMap<>(pipeline.chunkerOptions());
        if (!"no-op".equals(pipeline.chunkerName())) {
            options.put("chunkSize", pipeline.chunkSize());
            options.put("overlap", pipeline.chunkOverlap());
        }
        options.put(TextChunker.OPTION_COLLECT_GARBAGE, false);
        RetrievedDoc input = new RetrievedDoc(documentId, text, Map.of("source", documentId));
        List<String> result = new ArrayList<>();
        for (RetrievedDoc chunk : chunker.chunk(input, options)) {
            if (chunk.getText() != null && !chunk.getText().isBlank()) result.add(chunk.getText().trim());
        }
        return result;
    }

    public static boolean loaderSupports(String loader, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (loader) {
            case "pdf" -> name.endsWith(".pdf");
            case "html" -> name.endsWith(".html") || name.endsWith(".htm");
            case "markdown" -> name.endsWith(".md") || name.endsWith(".markdown");
            case "code" -> isCodeFile(file);
            case "excel" -> isExcelFile(file);
            case "external-materialized" -> name.endsWith(".md");
            case "table" -> name.endsWith(".csv") || name.endsWith(".tsv")
                    || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
            case "text" -> !name.endsWith(".pdf");
            default -> true;
        };
    }

    public static Set<String> supportedSteps() {
        LinkedHashSet<String> supported = new LinkedHashSet<>(SUPPORTED_STEPS);
        supported.addAll(LOCAL_GRAPH_STEPS);
        return Set.copyOf(supported);
    }

    public static Set<String> supportedPipelineTypes() {
        return BUILTIN_PIPELINE_TYPES;
    }

    /** Whether this document selects a model-backed unified pipeline. */
    public static boolean usesModelPipeline(ResolvedPipeline pipeline) {
        if (pipeline == null) return false;
        return !pipeline.processor().isEmpty()
                || pipeline.chunkerOptions().containsKey("pipelineDefinition")
                || pipeline.chunkerOptions().containsKey("pipelineDefinitionPath");
    }

    private static String componentError(JsonNode node, String location) {
        String loader = text(node, "loaderName");
        if (loader != null && canonicalLoader(loader) == null) {
            return location + " selects unknown project-local loader '" + loader
                    + "'. Call crawl_discover section=pipelines.";
        }
        String chunker = text(node, "chunkerName");
        if (chunker != null && canonicalChunker(chunker) == null) {
            return location + " selects unknown project-local chunker '" + chunker
                    + "'. Call crawl_discover section=pipelines.";
        }
        int chunkSize = node.path("chunkSize").asInt(2_000);
        int overlap = node.path("chunkOverlap").asInt(0);
        if (chunkSize < 1) return location + ".chunkSize must be greater than zero.";
        if (overlap < 0) return location + ".chunkOverlap must not be negative.";
        if (overlap >= chunkSize && !"no-op".equals(canonicalChunker(chunker))) {
            return location + ".chunkOverlap must be smaller than chunkSize.";
        }
        return null;
    }

    private static String modelBindingError(JsonNode node, String location) {
        if (node == null || !node.isObject()) return null;
        JsonNode bindings = node.get("modelBindings");
        if ((bindings == null || bindings.isNull()) && node.path("options").isObject()) {
            bindings = node.path("options").get("modelBindings");
        }
        if (bindings != null && !bindings.isNull()) {
            if (!bindings.isObject()) return location + ".modelBindings must be an object.";
            var fields = bindings.fields();
            while (fields.hasNext()) {
                var binding = fields.next();
                if (binding.getKey().isBlank()) {
                    return location + ".modelBindings contains an empty role.";
                }
                if (!binding.getValue().isTextual() || binding.getValue().asText().isBlank()) {
                    return location + ".modelBindings." + binding.getKey()
                            + " must reference a non-empty model id.";
                }
            }
        }
        JsonNode refs = node.get("modelRefs");
        if ((refs == null || refs.isNull()) && node.path("options").isObject()) {
            refs = node.path("options").get("modelRefs");
        }
        if (refs != null && !refs.isNull()) {
            if (!refs.isArray()) return location + ".modelRefs must be an array.";
            for (JsonNode ref : refs) {
                if (!ref.isTextual() || ref.asText().isBlank()) {
                    return location + ".modelRefs must contain only non-empty model ids.";
                }
            }
        }
        return null;
    }

    private static Map<String, PipelineDefinition> pipelineDefinitions(JsonNode request) {
        Map<String, PipelineDefinition> pipelines = new LinkedHashMap<>();
        pipelines.put(STANDARD_TEXT_PIPELINE, new PipelineDefinition(STANDARD_TEXT_PIPELINE,
                "STANDARD_TEXT", "auto", "recursive-character", 2_000, 200, Map.of(), Map.of()));
        pipelines.put(TEXT_MODEL_PIPELINE, new PipelineDefinition(TEXT_MODEL_PIPELINE,
                "LLM", "text", "sentence", 2_000, 200, Map.of(), builtinTextModelProcessor()));
        pipelines.put(CHAT_MODEL_PIPELINE, new PipelineDefinition(CHAT_MODEL_PIPELINE,
                "CHAT_MODEL", "auto", "sentence", 2_000, 200,
                Map.of(), builtinChatModelProcessor()));
        pipelines.put(CODE_PIPELINE, new PipelineDefinition(CODE_PIPELINE,
                "CODE", "code", "recursive-character", 1_800, 180,
                Map.of("separators", List.of("\n\n", "\n", " ")), Map.of()));
        pipelines.put(VLM_PIPELINE, new PipelineDefinition(VLM_PIPELINE,
                "VLM", "pdf", "recursive-character", 2_000, 200, Map.of(),
                builtinModelProcessor("VLM")));
        pipelines.put(VISION_PIPELINE, new PipelineDefinition(VISION_PIPELINE,
                "VLM", "auto", "recursive-character", 2_000, 200, Map.of(), builtinVisionProcessor()));
        pipelines.put(OCR_PIPELINE, new PipelineDefinition(OCR_PIPELINE,
                "OCR", "pdf", "recursive-character", 2_000, 200, Map.of(),
                builtinModelProcessor("OCR")));
        pipelines.put(TABLE_AWARE_PIPELINE, new PipelineDefinition(TABLE_AWARE_PIPELINE,
                "TABLE_AWARE", "table", "recursive-character", 2_000, 200,
                Map.of("preserveTables", true), Map.of()));
        pipelines.put(KEYWORD_ONLY_PIPELINE, new PipelineDefinition(KEYWORD_ONLY_PIPELINE,
                "KEYWORD_ONLY", "auto", "recursive-character", 2_000, 200,
                Map.of("keywordOnly", true), Map.of()));
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        Map<String, Map<String, Object>> executors = executorDefinitions(registry);
        Map<String, Object> definitionsById = unifiedDefinitions(registry);
        Map<String, Object> modelsById = modelDefinitions(registry);
        appendPipelineDefinitions(pipelines, registry == null ? null : registry.get("defaults"),
                executors, definitionsById, modelsById);
        appendPipelineDefinitions(pipelines, request == null ? null : request.get("registeredPipelines"),
                executors, definitionsById, modelsById);
        JsonNode definitions = request == null ? null : request.get("pipelines");
        appendPipelineDefinitions(pipelines, definitions, executors, definitionsById, modelsById);
        return pipelines;
    }

    private static void appendPipelineDefinitions(Map<String, PipelineDefinition> pipelines,
                                                  JsonNode definitions,
                                                  Map<String, Map<String, Object>> executors,
                                                  Map<String, Object> definitionsById,
                                                  Map<String, Object> modelsById) {
        if (definitions == null || !definitions.isArray()) return;
        for (JsonNode definition : definitions) {
            if (!definition.isObject()) continue;
            String id = text(definition, "pipelineId");
            if (id == null) continue;
            String registeredPipelineId = text(definition, "registeredPipelineId");
            PipelineDefinition inherited = pipelines.get(registeredPipelineId);
            if (registeredPipelineId != null && inherited == null) {
                throw new IllegalArgumentException("Unknown registeredPipelineId: " + registeredPipelineId
                        + ". registeredPipelineId must name a reusable pipelineRegistry.defaults or "
                        + "registeredPipelines entry; select a project pipeline directly with pipelineId.");
            }
            String type = firstNonBlank(text(definition, "pipelineType"),
                            inherited == null ? null : inherited.pipelineType(), "CUSTOM")
                    .toUpperCase(Locale.ROOT);
            if (inherited == null) {
                if ("VLM".equals(type)) {
                    inherited = pipelines.get(VLM_PIPELINE);
                } else if ("OCR".equals(type)) {
                    inherited = pipelines.get(OCR_PIPELINE);
                } else if ("CHAT_MODEL".equals(type)) {
                    inherited = pipelines.get(CHAT_MODEL_PIPELINE);
                }
            }
            String defaultLoader = inherited != null ? inherited.loaderName() : "CODE".equals(type) ? "code"
                    : ("VLM".equals(type) || "OCR".equals(type) ? "pdf"
                    : "TABLE_AWARE".equals(type) ? "table" : "auto");
            int defaultSize = inherited != null ? inherited.chunkSize() : "CODE".equals(type) ? 1_800 : 2_000;
            int defaultOverlap = inherited != null ? inherited.chunkOverlap() : "CODE".equals(type) ? 180 : 200;
            Map<String, Object> options = new LinkedHashMap<>(
                    inherited == null ? Map.of() : inherited.options());
            mergeOptions(options, definition.get("options"));
            mergeOptions(options, definition.get("chunkerOptions"));
            copyOption(options, definition, "pipelineDefinition");
            copyOption(options, definition, "pipelineDefinitionPath");
            copyOption(options, definition, "pipelineDefinitionId");
            copyOption(options, definition, "modelSetId");
            copyOption(options, definition, "modelId");
            copyOption(options, definition, "vlmModel");
            copyOption(options, definition, "modelBindings");
            copyOption(options, definition, "modelDefinitions");
            copyOption(options, definition, "modelRefs");
            copyOption(options, definition, "processingMode");
            Map<String, Object> processor = new LinkedHashMap<>(
                    inherited == null ? Map.of() : inherited.processor());
            String executorId = text(definition, "executorId");
            if (executorId != null) {
                Map<String, Object> executor = executors.get(executorId);
                if (executor == null) {
                    throw new IllegalArgumentException("Unknown pipeline executorId: " + executorId);
                }
                clearForDifferentProcessorType(processor, stringValue(executor.get("type")));
                processor.putAll(executor);
                processor.put("executorId", executorId);
            }
            mergeProcessorOverride(processor, definition.get("processor"));
            if (!modelsById.isEmpty()) {
                processor.put("registeredModelDefinitions", Map.copyOf(modelsById));
            }
            copyOption(processor, definition, "modelBindings");
            copyOption(processor, definition, "modelDefinitions");
            copyOption(processor, definition, "modelRefs");
            applyDefinitionReference(definition, definitionsById, processor);
            if (processor.isEmpty() && (options.containsKey("pipelineDefinition")
                    || options.containsKey("pipelineDefinitionPath"))) {
                processor.put("type", "UNIFIED_PIPELINE");
                copyMapValue(processor, options, "pipelineDefinition");
                copyMapValue(processor, options, "pipelineDefinitionPath");
            }
            boolean explicitLoader = text(definition, "loaderName") != null
                    || (inherited != null && inherited.explicitLoader());
            if ("CHAT_MODEL".equals(processor.get("type")) && !explicitLoader) defaultLoader = "auto";
            pipelines.put(id, new PipelineDefinition(id, type,
                    firstNonBlank(text(definition, "loaderName"), defaultLoader),
                    firstNonBlank(text(definition, "chunkerName"),
                            inherited == null ? null : inherited.chunkerName(), "recursive-character"),
                    positiveInt(definition, "chunkSize", defaultSize),
                    nonNegativeInt(definition, "chunkOverlap", defaultOverlap),
                    Map.copyOf(options), Map.copyOf(processor), explicitLoader));
        }
    }

    private static String registryError(JsonNode request) {
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        if (registry == null || registry.isNull()) return null;
        if (!registry.isObject()) return "pipelineRegistry must be an object.";
        for (String field : List.of("models", "defaults", "definitions", "executors")) {
            JsonNode value = registry.get(field);
            if (value != null && !value.isNull() && !value.isArray()) {
                return "pipelineRegistry." + field + " must be an array.";
            }
        }
        JsonNode models = registry.get("models");
        if (models != null && models.isArray()) {
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < models.size(); i++) {
                JsonNode model = models.get(i);
                if (!model.isObject()) {
                    return "pipelineRegistry.models[" + i + "] must be an object.";
                }
                String id = firstNonBlank(text(model, "id"), text(model, "modelId"));
                if (id == null) {
                    return "pipelineRegistry.models[" + i + "].id is required.";
                }
                if (!ids.add(id)) return "Duplicate registered model id: " + id;
                JsonNode runtime = model.get("runtime");
                if (runtime != null && !runtime.isNull() && !runtime.isObject()) {
                    return "pipelineRegistry.models[" + i + "].runtime must be an object.";
                }
            }
        }
        JsonNode defaults = registry.get("defaults");
        if (defaults != null && defaults.isArray()) {
            for (int i = 0; i < defaults.size(); i++) {
                JsonNode definition = defaults.get(i);
                if (!definition.isObject()) {
                    return "pipelineRegistry.defaults[" + i + "] must be an object.";
                }
                String bindingError = modelBindingError(
                        definition, "pipelineRegistry.defaults[" + i + "]");
                if (bindingError != null) return bindingError;
            }
        }
        JsonNode executors = registry.get("executors");
        if (executors != null && executors.isArray()) {
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < executors.size(); i++) {
                JsonNode executor = executors.get(i);
                if (!executor.isObject()) return "pipelineRegistry.executors[" + i + "] must be an object.";
                String id = firstNonBlank(text(executor, "executorId"), text(executor, "id"));
                if (id == null) return "pipelineRegistry.executors[" + i + "].executorId is required.";
                if (!ids.add(id)) return "Duplicate pipeline executorId: " + id;
                String type = firstNonBlank(text(executor, "type"), "UNIFIED_PIPELINE")
                        .toUpperCase(Locale.ROOT);
                if (!EXECUTOR_TYPES.contains(type)) {
                    return "Unsupported pipeline executor type " + type + ". Supported execution contracts: "
                            + EXECUTOR_TYPES;
                }
            }
        }
        JsonNode definitions = registry.get("definitions");
        if (definitions != null && definitions.isArray()) {
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < definitions.size(); i++) {
                JsonNode definition = definitions.get(i);
                if (!definition.isObject()) {
                    return "pipelineRegistry.definitions[" + i + "] must be an object.";
                }
                String id = text(definition, "pipelineId");
                if (id == null) return "pipelineRegistry.definitions[" + i + "].pipelineId is required.";
                if (!ids.add(id)) return "Duplicate registered UnifiedPipelineDefinition: " + id;
                String bindingError = modelBindingError(
                        definition, "pipelineRegistry.definitions[" + i + "]");
                if (bindingError != null) return bindingError;
            }
        }
        return null;
    }

    private static Map<String, Map<String, Object>> executorDefinitions(JsonNode registry) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        JsonNode executors = registry == null ? null : registry.get("executors");
        if (executors == null || !executors.isArray()) return result;
        for (JsonNode executor : executors) {
            String id = firstNonBlank(text(executor, "executorId"), text(executor, "id"));
            if (id == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            mergeOptions(value, executor);
            value.put("executorId", id);
            value.put("type", firstNonBlank(text(executor, "type"), "UNIFIED_PIPELINE")
                    .toUpperCase(Locale.ROOT));
            result.put(id, Map.copyOf(value));
        }
        return result;
    }

    private static Map<String, Object> unifiedDefinitions(JsonNode registry) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode definitions = registry == null ? null : registry.get("definitions");
        if (definitions == null || !definitions.isArray()) return result;
        for (JsonNode definition : definitions) {
            String id = text(definition, "pipelineId");
            if (id != null) result.put(id, jsonValue(definition));
        }
        return result;
    }

    private static Map<String, Object> modelDefinitions(JsonNode registry) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode models = registry == null ? null : registry.get("models");
        if (models == null || !models.isArray()) return result;
        for (JsonNode model : models) {
            String id = firstNonBlank(text(model, "id"), text(model, "modelId"));
            if (id != null) result.put(id, jsonValue(model));
        }
        return result;
    }

    private static void applyExecutorReference(JsonNode request, JsonNode definition,
                                               Map<String, Object> processor) {
        if (definition == null) return;
        String executorId = text(definition, "executorId");
        if (executorId == null) return;
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        Map<String, Object> registered = executorDefinitions(registry).get(executorId);
        if (registered == null) {
            throw new IllegalArgumentException("Unknown registered pipeline executorId: " + executorId);
        }
        clearForDifferentProcessorType(processor, stringValue(registered.get("type")));
        processor.putAll(registered);
        processor.put("executorId", executorId);
    }

    private static void mergeProcessorOverride(Map<String, Object> processor, JsonNode configured) {
        if (configured == null || !configured.isObject()) return;
        clearForDifferentProcessorType(processor, text(configured, "type"));
        mergeOptions(processor, configured);
    }

    private static void clearForDifferentProcessorType(
            Map<String, Object> processor, String configuredType) {
        String currentType = stringValue(processor.get("type"));
        if (configuredType != null && currentType != null
                && !configuredType.equalsIgnoreCase(currentType)) {
            processor.clear();
        }
    }

    private static void applyDefinitionReference(JsonNode request, JsonNode definition,
                                                 Map<String, Object> processor) {
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        applyDefinitionReference(definition, unifiedDefinitions(registry), processor);
    }

    private static void applyDefinitionReference(JsonNode definition,
                                                 Map<String, Object> definitionsById,
                                                 Map<String, Object> processor) {
        if (definition == null) return;
        boolean explicitReference = definition.hasNonNull("pipelineDefinition")
                || definition.hasNonNull("pipelineDefinitionPath")
                || definition.hasNonNull("pipelineDefinitionId")
                || definition.hasNonNull("registeredDefinitionId");
        if (explicitReference) {
            processor.remove("pipelineDefinition");
            processor.remove("pipelineDefinitionPath");
            processor.remove("pipelineDefinitionId");
        }
        for (String field : List.of("pipelineDefinition", "pipelineDefinitionPath", "pipelineDefinitionId")) {
            copyOption(processor, definition, field);
        }
        String definitionId = text(definition, "pipelineDefinitionId");
        if (definitionId == null) definitionId = text(definition, "registeredDefinitionId");
        if (definitionId != null) {
            Object registered = definitionsById.get(definitionId);
            if (registered != null) processor.put("pipelineDefinition", registered);
            processor.put("pipelineDefinitionId", definitionId);
        }
        if (processor.containsKey("pipelineDefinition") || processor.containsKey("pipelineDefinitionPath")
                || processor.containsKey("pipelineDefinitionId")) {
            processor.putIfAbsent("type", "UNIFIED_PIPELINE");
        }
    }

    private static void copyMapValue(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    /** Canonical executable definition for built-in document model pipelines. */
    public static Map<String, Object> builtinModelProcessor(String pipelineType) {
        String normalized = pipelineType == null ? "VLM" : pipelineType.toUpperCase(Locale.ROOT);
        String id = "OCR".equals(normalized) ? OCR_PIPELINE : VLM_PIPELINE;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("outputFormat", "OCR".equals(normalized) ? "MARKDOWN" : "DOCTAGS");
        parameters.put("pdfRenderDpi", 300);
        parameters.put("pageBatchSize", 1);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("@class", "ai.kompile.pipelines.framework.core.config.GenericStepConfig");
        step.put("runnerClassName", "ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner");
        step.put("parameters", parameters);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline");
        spec.put("id", id);
        spec.put("steps", List.of(step));

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 1);
        definition.put("definitionVersion", 1);
        definition.put("pipelineId", id);
        definition.put("displayName", "OCR".equals(normalized)
                ? "OCR document extraction" : "VLM document extraction");
        definition.put("kind", "VLM");
        definition.put("topology", "SEQUENCE");
        definition.put("modelSelection", "caller-configured");
        definition.put("pipelineSpec", spec);
        definition.put("runtimeRequirements", Map.of(
                "capabilities", List.of("document-understanding", "pdf"),
                "runnerTypes", List.of("ai.kompile.pipelines.steps.vlm.VlmDocumentStepRunner")));

        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("type", "UNIFIED_PIPELINE");
        processor.put("pipelineDefinition", definition);
        return Map.copyOf(processor);
    }

    /** Host-side processor for the configured remote Kompile chat provider. */
    public static Map<String, Object> builtinChatModelProcessor() {
        return Map.of(
                "type", ChatModelPipelineRunner.PROCESSOR_TYPE,
                "modelSource", "chat");
    }

    /**
     * Canonical provider-neutral text-to-model-to-text composition. The model and tokenizer are
     * deliberately represented as roles; {@link LocalModelPipelineRunner} materializes their local
     * paths from the request's model bindings immediately before launch.
     */
    public static Map<String, Object> builtinTextModelProcessor() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("modelRole", "default");
        parameters.put("tokenizerRole", "default");
        parameters.put("promptInputName", "text");
        parameters.put("responseOutputName", "output");
        parameters.put("generationParameters", Map.of(
                "maxNewTokens", 1024,
                "temperature", 0.2));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("@class", "ai.kompile.pipelines.framework.api.llm.LLMStepConfig");
        step.put("name", "text_model");
        step.put("type", "SAMEDIFF_LANGUAGE_MODEL");
        step.put("runnerClassName", "SAMEDIFF_LANGUAGE_MODEL");
        step.putAll(parameters);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline");
        spec.put("id", TEXT_MODEL_PIPELINE);
        spec.put("steps", List.of(step));

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 1);
        definition.put("definitionVersion", 1);
        definition.put("pipelineId", TEXT_MODEL_PIPELINE);
        definition.put("displayName", "Text model text");
        definition.put("description", "Text input is passed to a caller-selected local model and returned as text.");
        definition.put("kind", "LLM");
        definition.put("topology", "SEQUENCE");
        definition.put("modelSelection", "caller-configured");
        definition.put("modelRoles", List.of("default"));
        definition.put("pipelineSpec", spec);
        definition.put("inputs", Map.of("text", Map.of(
                "type", "string", "required", true, "description", "Text prompt or document content")));
        definition.put("outputs", Map.of("output", Map.of(
                "type", "string", "required", true, "description", "Generated text")));
        definition.put("runtimeRequirements", Map.of(
                "capabilities", List.of("text-generation", "local-model"),
                "runnerTypes", List.of("SAMEDIFF_LANGUAGE_MODEL")));

        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("type", "UNIFIED_PIPELINE");
        processor.put("pipelineDefinition", definition);
        return Map.copyOf(processor);
    }

    /**
     * Canonical composed vision graph. It mirrors the framework VLM builder's topology while
     * leaving every model choice to role bindings supplied by the caller.
     */
    public static Map<String, Object> builtinVisionProcessor() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(graphNode("image_preprocess", List.of("pipeline_input"),
                "ai.kompile.pipelines.steps.vlm.ImagePreprocessingStepRunner", Map.of(
                        "tileSize", 364, "maxTiles", 5)));
        nodes.add(graphNode("vision_encoder", List.of("image_preprocess"),
                "ai.kompile.pipelines.steps.vlm.VisionEncoderStepRunner", Map.of(
                        "modelRole", "visionEncoder", "outputNames", List.of("image_features"))));
        nodes.add(graphNode("text_embedding", List.of("pipeline_input"),
                "ai.kompile.pipelines.steps.vlm.TextEmbeddingStepRunner", Map.of(
                        "modelRole", "textEmbedding", "outputNames", List.of("text_embeddings"))));
        Map<String, Object> fusionParameters = new LinkedHashMap<>();
        fusionParameters.put("imageTokenId", 49153);
        fusionParameters.put("inputDataBindings", Map.of(
                "image_features", "vision_encoder.image_features",
                "text_embeddings", "text_embedding.text_embeddings",
                "input_ids", "pipeline_input.input_ids",
                "attention_mask", "pipeline_input.attention_mask",
                "position_ids", "pipeline_input.position_ids"));
        nodes.add(graphNode("fusion", List.of("vision_encoder", "text_embedding", "pipeline_input"),
                "ai.kompile.pipelines.steps.vlm.VisionTextFusionStepRunner", fusionParameters));
        nodes.add(graphNode("decoder_body", List.of("fusion"),
                "ai.kompile.pipelines.steps.vlm.VLMDecoderStepRunner", Map.of(
                        "modelRole", "decoder", "numKvLayers", 0, "numHeads", 32,
                        "headDim", 96, "eosTokenId", 2)));
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("@graphNodeType", "LOOP");
        loop.put("name", "decoder_loop");
        loop.put("inputs", List.of("fusion"));
        loop.put("bodyStepName", "decoder_body");
        loop.put("feedbackKeys", List.of("kv_cache", "input_ids", "attention_mask", "position_ids"));
        loop.put("conditionClassName", "ai.kompile.pipelines.steps.vlm.AutoregressiveLoopCondition");
        loop.put("accumulatorKey", "generated_tokens");
        loop.put("accumulateFromKey", "next_token_id");
        nodes.add(loop);
        nodes.add(graphNode("token_decode", List.of("decoder_loop"),
                "ai.kompile.pipelines.steps.vlm.TokenDecodingStepRunner", Map.of(
                        "tokenizerRole", "decoder")));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("@class", "ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline");
        spec.put("id", VISION_PIPELINE);
        spec.put("nodes", nodes);
        spec.put("inputNodeName", "pipeline_input");
        spec.put("outputNodeName", "token_decode");

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 1);
        definition.put("definitionVersion", 1);
        definition.put("pipelineId", VISION_PIPELINE);
        definition.put("displayName", "Vision multimodel");
        definition.put("description", "Image preprocessing followed by independently bound vision, embedding, and decoder models.");
        definition.put("kind", "VLM");
        definition.put("topology", "GRAPH");
        definition.put("modelSelection", "caller-configured");
        definition.put("modelRoles", List.of("visionEncoder", "textEmbedding", "decoder"));
        definition.put("pipelineSpec", spec);
        definition.put("inputs", Map.of(
                "image", Map.of("type", "image", "required", true, "description", "Raster image or image tensor"),
                "text", Map.of("type", "string", "required", false,
                        "description", "Optional source prompt; an upstream tokenizer/adapter must materialize input_ids"),
                "input_ids", Map.of("type", "int64 tensor", "required", true,
                        "description", "Token ids consumed by text embedding, fusion, and decoder"),
                "attention_mask", Map.of("type", "int64 tensor", "required", false,
                        "description", "Attention mask forwarded to the decoder loop when supplied"),
                "position_ids", Map.of("type", "int64 tensor", "required", false,
                        "description", "Position ids forwarded to the decoder loop when supplied")));
        definition.put("outputs", Map.of("generated_text", Map.of(
                "type", "string", "required", true, "description", "Decoded model output")));
        definition.put("runtimeRequirements", Map.of(
                "capabilities", List.of("image-preprocessing", "vision-encoding", "text-embedding", "text-generation", "local-model"),
                "runnerTypes", List.of(
                        "ai.kompile.pipelines.steps.vlm.ImagePreprocessingStepRunner",
                        "ai.kompile.pipelines.steps.vlm.VisionEncoderStepRunner",
                        "ai.kompile.pipelines.steps.vlm.TextEmbeddingStepRunner",
                        "ai.kompile.pipelines.steps.vlm.VLMDecoderStepRunner",
                        "ai.kompile.pipelines.steps.vlm.TokenDecodingStepRunner")));

        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("type", "UNIFIED_PIPELINE");
        processor.put("pipelineDefinition", definition);
        return Map.copyOf(processor);
    }

    private static Map<String, Object> graphNode(String name, List<String> inputs,
                                                 String runnerClassName, Map<String, Object> parameters) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("@class", "ai.kompile.pipelines.framework.core.config.GenericStepConfig");
        step.put("runnerClassName", runnerClassName);
        step.put("parameters", parameters);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@graphNodeType", "STANDARD");
        node.put("name", name);
        node.put("inputs", inputs);
        node.put("stepConfig", step);
        return node;
    }

    private static JsonNode matchingDocument(JsonNode request, Path sourceRoot, Path file) {
        JsonNode documents = request == null ? null : request.get("documents");
        if (documents == null || !documents.isArray()) return null;
        Path normalizedFile = file.toAbsolutePath().normalize();
        JsonNode best = null;
        int bestLength = -1;
        for (JsonNode document : documents) {
            String value = text(document, "path");
            if (value == null) continue;
            try {
                Path candidate = Path.of(value);
                if (!candidate.isAbsolute()) candidate = sourceRoot.resolve(candidate);
                candidate = candidate.toAbsolutePath().normalize();
                if ((normalizedFile.equals(candidate) || normalizedFile.startsWith(candidate))
                        && candidate.getNameCount() > bestLength) {
                    best = document;
                    bestLength = candidate.getNameCount();
                }
            } catch (Exception ignored) {
                // Request validation/path handling reports malformed sources before execution.
            }
        }
        return best;
    }

    private static String routedPipeline(JsonNode request, Path file) throws IOException {
        JsonNode rules = request == null ? null : request.get("routeRules");
        if (rules == null || !rules.isArray()) return null;
        List<JsonNode> ordered = new ArrayList<>();
        rules.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(rule -> rule.path("priority").asInt(100)));
        for (JsonNode rule : ordered) {
            if (matchesRoute(rule, file)) return text(rule, "pipelineId");
        }
        return null;
    }

    private static boolean matchesRoute(JsonNode rule, Path file) throws IOException {
        String extension = extension(file);
        String contentType = Files.probeContentType(file);
        if (!matchesAny(rule.get("fileExtensions"), extension, false)) return false;
        if (!matchesAny(rule.get("contentTypes"), contentType, true)) return false;
        if (!matchesAny(rule.get("sourceTypes"), "FILE", false)) return false;
        long size = Files.size(file);
        if (rule.hasNonNull("minSizeBytes") && size < rule.path("minSizeBytes").asLong()) return false;
        if (rule.hasNonNull("maxSizeBytes") && size > rule.path("maxSizeBytes").asLong()) return false;
        JsonNode patterns = rule.get("contentPatterns");
        if (patterns != null && patterns.isArray() && !patterns.isEmpty()) {
            String content;
            try {
                content = Files.readString(file);
            } catch (Exception e) {
                return false;
            }
            boolean matched = false;
            for (JsonNode pattern : patterns) {
                try {
                    if (Pattern.compile(pattern.asText(), Pattern.MULTILINE).matcher(content).find()) {
                        matched = true;
                        break;
                    }
                } catch (PatternSyntaxException ignored) {
                    if (content.contains(pattern.asText())) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static boolean matchesAny(JsonNode values, String actual, boolean wildcard) {
        if (values == null || !values.isArray() || values.isEmpty()) return true;
        if (actual == null) return false;
        for (JsonNode value : values) {
            String expected = value.asText("");
            if (expected.equalsIgnoreCase(actual)) return true;
            if (wildcard && expected.endsWith("/*")
                    && actual.toLowerCase(Locale.ROOT).startsWith(
                    expected.substring(0, expected.length() - 1).toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static void validateAllowedContentTypes(JsonNode document, Path file) throws IOException {
        if (document == null) return;
        JsonNode allowed = document.get("allowedContentTypes");
        String detected = Files.probeContentType(file);
        if (allowed != null && allowed.isArray() && !allowed.isEmpty()
                && !matchesAny(allowed, detected, true)) {
            throw new IllegalArgumentException("Detected content type " + detected
                    + " is not allowed for " + file);
        }
    }

    private static String automaticLoader(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "html";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "markdown";
        if (isExcelFile(file)) return "excel";
        if (isCodeFile(file)) return "code";
        return "text";
    }

    private static boolean isExcelFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xls") || name.endsWith(".xlsx")
                || name.endsWith(".xlsm") || name.endsWith(".ods");
    }

    private static boolean isCodeFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = extension(file);
        return Set.of(".java", ".kt", ".kts", ".scala", ".groovy", ".clj", ".cljs",
                ".py", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".c", ".h",
                ".cc", ".cpp", ".cxx", ".hpp", ".cs", ".fs", ".fsx", ".rb", ".php",
                ".swift", ".m", ".mm", ".sh", ".bash", ".zsh", ".fish", ".sql", ".proto",
                ".graphql", ".gql", ".toml", ".gradle", ".vue", ".svelte", ".css", ".scss",
                ".sass", ".less").contains(ext)
                || name.equals("dockerfile") || name.equals("makefile");
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String canonicalLoader(String value) {
        if (value == null || value.isBlank()) return "auto";
        return LOADER_ALIASES.get(normalize(value));
    }

    private static String crawlerLoader(JsonNode document) {
        if (document == null || !document.isObject()) return null;
        JsonNode properties = document.get("properties");
        return firstNonBlank(text(properties, "crawlerId"), text(properties, "preferredCrawlerId"),
                text(document, "crawlerId"), text(document, "preferredCrawlerId"));
    }

    private static String canonicalChunker(String value) {
        if (value == null || value.isBlank()) return "recursive-character";
        return CHUNKER_ALIASES.get(normalize(value));
    }

    private static Map<String, String> loaderAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases(aliases, "auto", "auto", "local-text", "local-knowledge");
        aliases(aliases, "text", "text", "plain-text");
        aliases(aliases, "markdown", "markdown", "md");
        aliases(aliases, "html", "html", "web-html");
        aliases(aliases, "excel", "excel", "xlsx", "spreadsheet", "office-excel");
        aliases(aliases, "pdf", "pdf", "pdfbox");
        aliases(aliases, "code", "code", "source-code");
        aliases(aliases, "table", "table", "table-aware");
        aliases(aliases, "external-materialized", "external-materialized", "external-source");
        return Map.copyOf(aliases);
    }

    private static Map<String, String> chunkerAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases(aliases, "recursive-character", "recursive-character", "recursive", "fixed",
                "local-fixed", "markdown-fixed");
        aliases(aliases, "sentence", "sentence", "sentence-text");
        aliases(aliases, "no-op", "no-op", "noop", "none", "whole-document");
        return Map.copyOf(aliases);
    }

    private static void aliases(Map<String, String> target, String canonical, String... aliases) {
        for (String alias : aliases) target.put(normalize(alias), canonical);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
    }

    private static int positiveInt(JsonNode node, String field, int fallback) {
        if (node == null || !node.hasNonNull(field)) return fallback;
        return Math.max(1, node.path(field).asInt(fallback));
    }

    private static int nonNegativeInt(JsonNode node, String field, int fallback) {
        if (node == null || !node.hasNonNull(field)) return fallback;
        return Math.max(0, node.path(field).asInt(fallback));
    }

    private static void mergeOptions(Map<String, Object> target, JsonNode options) {
        if (options == null || !options.isObject()) return;
        options.fields().forEachRemaining(entry -> target.put(entry.getKey(), jsonValue(entry.getValue())));
    }

    private static void copyOption(Map<String, Object> target, JsonNode source, String field) {
        if (source != null && source.hasNonNull(field)) target.put(field, jsonValue(source.get(field)));
    }

    private static Object jsonValue(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isInt()) return value.asInt();
        if (value.isLong()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.asDouble();
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(item -> result.add(jsonValue(item)));
            return result;
        }
        if (value.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            value.fields().forEachRemaining(entry -> result.put(entry.getKey(), jsonValue(entry.getValue())));
            return result;
        }
        return null;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.path(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static ObjectNode pipelineTemplate(ArrayNode target, String id, String type, String loader,
                                               String chunker, int size, int overlap, String description) {
        return pipelineTemplate(target, id, type, loader, chunker, size, overlap, true, description);
    }

    private static ObjectNode pipelineTemplate(ArrayNode target, String id, String type, String loader,
                                               String chunker, int size, int overlap, boolean available,
                                               String description) {
        ObjectNode template = target.addObject().put("pipelineId", id).put("pipelineType", type)
                .put("loaderName", loader).put("chunkerName", chunker)
                .put("chunkSize", size).put("chunkOverlap", overlap)
                .put("available", available).put("description", description);
        if (!available) {
            template.put("unavailableReason",
                    "The configured model artifact may be present, but no crawl document-model worker is resolved.");
        }
        return template;
    }

    private static void loader(ArrayNode target, String name, List<String> aliases,
                               List<String> contentTypes, String description) {
        ObjectNode item = target.addObject().put("name", name).put("available", true)
                .put("execution", "project-local subprocess").put("description", description);
        aliases.forEach(item.putArray("aliases")::add);
        contentTypes.forEach(item.putArray("contentTypes")::add);
    }

    private static void chunker(ArrayNode target, String name, List<String> aliases,
                                int size, int overlap, String description) {
        ObjectNode item = target.addObject().put("name", name).put("available", true)
                .put("execution", "project-local subprocess").put("description", description);
        aliases.forEach(item.putArray("aliases")::add);
        ObjectNode defaults = item.putObject("defaultOptions");
        if (size > 0) defaults.put("chunkSize", size).put("chunkOverlap", overlap);
    }

    public record ResolvedPipeline(String pipelineId,
                                   String pipelineType,
                                   String loaderName,
                                   String chunkerName,
                                   int chunkSize,
                                   int chunkOverlap,
                                   Map<String, Object> chunkerOptions,
                                   Map<String, Object> processor) {
        public ResolvedPipeline(String pipelineId, String pipelineType, String loaderName,
                                String chunkerName, int chunkSize, int chunkOverlap,
                                Map<String, Object> chunkerOptions) {
            this(pipelineId, pipelineType, loaderName, chunkerName, chunkSize, chunkOverlap,
                    chunkerOptions, Map.of());
        }
    }

    private record PipelineDefinition(String pipelineId,
                                      String pipelineType,
                                      String loaderName,
                                      String chunkerName,
                                      int chunkSize,
                                      int chunkOverlap,
                                      Map<String, Object> options,
                                      Map<String, Object> processor,
                                      boolean explicitLoader) {
        private PipelineDefinition(String pipelineId, String pipelineType, String loaderName,
                                   String chunkerName, int chunkSize, int chunkOverlap,
                                   Map<String, Object> options, Map<String, Object> processor) {
            this(pipelineId, pipelineType, loaderName, chunkerName, chunkSize, chunkOverlap,
                    options, processor, false);
        }
    }
}
