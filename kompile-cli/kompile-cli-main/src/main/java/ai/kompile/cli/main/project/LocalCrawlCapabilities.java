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
    public static final String CODE_PIPELINE = "code";
    public static final String VLM_PIPELINE = "vlm-document";
    public static final String OCR_PIPELINE = "ocr-document";
    public static final String TABLE_AWARE_PIPELINE = "table-aware";
    public static final String KEYWORD_ONLY_PIPELINE = "keyword-only";

    private static final Set<String> BUILTIN_PIPELINE_TYPES = Set.of(
            "STANDARD_TEXT", "VLM", "OCR", "CODE", "TABLE_AWARE", "KEYWORD_ONLY", "CUSTOM");
    private static final Set<String> EXECUTOR_TYPES = Set.of(
            "UNIFIED_PIPELINE", "KOMPILE_SUBPROCESS", "EXECUTABLE");
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
        return catalog(mapper, executionMode, LocalModelPipelineRunner.documentModelWorkerAvailable());
    }

    /** Build discovery data using the worker availability resolved for the active project folder. */
    public static ObjectNode catalog(ObjectMapper mapper, String executionMode,
                                     boolean documentModelWorkerAvailable) {
        ObjectNode catalog = mapper.createObjectNode();
        ArrayNode templates = catalog.putArray("pipelineTemplates");
        pipelineTemplate(templates, STANDARD_TEXT_PIPELINE, "STANDARD_TEXT", "auto",
                "recursive-character", 2_000, 200,
                "General filesystem documents with format-aware loading and recursive chunking.");
        pipelineTemplate(templates, CODE_PIPELINE, "CODE", "code",
                "recursive-character", 1_800, 180,
                "Source code and project files with code validation and boundary-aware chunking.");
        pipelineTemplate(templates, VLM_PIPELINE, "VLM", "pdf",
                "recursive-character", 2_000, 200, documentModelWorkerAvailable,
                "Model-backed PDF extraction in the isolated document-model subprocess.");
        pipelineTemplate(templates, OCR_PIPELINE, "OCR", "pdf",
                "recursive-character", 2_000, 200, documentModelWorkerAvailable,
                "Traditional or VLM-backed OCR in the isolated document-model subprocess.");
        pipelineTemplate(templates, TABLE_AWARE_PIPELINE, "TABLE_AWARE", "table",
                "recursive-character", 2_000, 200,
                "Table-preserving HTML/PDF extraction; set options.modelBacked=true for VLM extraction.");
        pipelineTemplate(templates, KEYWORD_ONLY_PIPELINE, "KEYWORD_ONLY", "auto",
                "recursive-character", 2_000, 200,
                "Local lexical indexing without an embedding stage.");

        ArrayNode loaders = catalog.putArray("loaders");
        loader(loaders, "auto", List.of("local-text", "local-knowledge"),
                List.of("file/*"), "Select pdf, html, markdown, code, or text from each file.");
        loader(loaders, "text", List.of("plain-text"),
                List.of("text/*", "application/json", "application/xml"),
                "UTF-8 text loader with normalized whitespace.");
        loader(loaders, "markdown", List.of("md"), List.of("text/markdown"),
                "Markdown-preserving UTF-8 loader.");
        loader(loaders, "html", List.of("web-html"), List.of("text/html"),
                "HTML-to-Markdown loader with table and heading preservation.");
        loader(loaders, "pdf", List.of("pdfbox"), List.of("application/pdf"),
                "PDFBox loader with pdftotext fallback in native mode.");
        loader(loaders, "code", List.of("source-code"), List.of("text/x-source"),
                "UTF-8 source-code loader restricted to known code and build formats.");
        loader(loaders, "table", List.of("table-aware"),
                List.of("text/csv", "text/tab-separated-values", "text/html", "application/pdf"),
                "Table-preserving CSV/TSV/HTML loader with layout-preserving PDF text fallback.");

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
        ObjectNode registry = catalog.putObject("pipelineRegistry");
        registry.put("requestField", "pipelineRegistry")
                .put("projectDefaults", "active kompile.project.json pipeline registrations")
                .put("definitionFormat", "UnifiedPipelineDefinition")
                .put("folderDefinitions", ".kompile/pipelines/unified/*.json")
                .put("globalDefinitions", "${kompile.data.dir:-~/.kompile}/pipelines/unified/*.json")
                .put("arbitraryPipelineTypes", true);
        ArrayNode executorTypes = registry.putArray("executorTypes");
        EXECUTOR_TYPES.stream().sorted().forEach(executorTypes::add);
        ArrayNode pipelineTypes = registry.putArray("builtinPipelineTypes");
        BUILTIN_PIPELINE_TYPES.stream().sorted().forEach(pipelineTypes::add);

        catalog.putObject("modelProcessing")
                .put("available", documentModelWorkerAvailable)
                .put("documentModelWorker", documentModelWorkerAvailable)
                .put("builtinDocumentProcessor", documentModelWorkerAvailable)
                .put("callerDefinedUnifiedPipelines", true)
                .put("callerDefinedExecutors", true)
                .put("execution", "request-scoped Kompile subprocesses; no MCP/application server required")
                .put("semanticServing", "processingRoute LOCAL_MODEL/serving or graphExtraction.llmProvider=serving")
                .put("finalReasoningLearning", "portable FOL/PSL/MEBN hybrid-consensus models stored in the folder .kgraph")
                .put("lifecycle", "children stop before the MCP command returns")
                .put("configuration", "pipelineRegistry defaults/definitions/executors, project pipeline "
                        + "registrations, or pipeline processor overrides");
        return catalog;
    }

    /** Return a user-facing validation error, or {@code null} when the request is executable. */
    public static String validationError(JsonNode request) {
        if (request == null || request.isNull()) {
            return null;
        }
        try {
            JsonNode runtimeConfig = request.get("runtimeConfig");
            if (runtimeConfig != null && !runtimeConfig.isNull()) {
                if (!runtimeConfig.isObject()) return "runtimeConfig must be an object.";
                JsonNode executable = runtimeConfig.get("documentModelExecutable");
                if (executable != null && !executable.isNull()
                        && (!executable.isTextual() || executable.asText().isBlank())) {
                    return "runtimeConfig.documentModelExecutable must be a non-empty path.";
                }
                String executableMode = text(runtimeConfig, "documentModelExecutableMode");
                if (executableMode != null && !Set.of("DEDICATED", "UNIFIED")
                        .contains(executableMode.toUpperCase(Locale.ROOT))) {
                    return "runtimeConfig.documentModelExecutableMode must be DEDICATED or UNIFIED.";
                }
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
        String profileLoader = profile == null ? null : profile.getLoader();
        String profileChunker = profile == null ? null : profile.getChunker();
        String loader = explicitlySelectedPipeline
                ? firstNonBlank(text(document, "loaderName"), pipeline.loaderName(), profileLoader, "auto")
                : firstNonBlank(text(document, "loaderName"), profileLoader, pipeline.loaderName(), "auto");
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
                "pipelineDefinitionId", "modelId", "vlmModel", "processingMode")) {
            copyOption(options, document, field);
        }
        Map<String, Object> processor = new LinkedHashMap<>(pipeline.processor());
        mergeOptions(processor, document == null ? null : document.get("processor"));
        applyExecutorReference(request, document, processor);
        applyDefinitionReference(request, document, processor);
        JsonNode runtimeConfig = request == null ? null : request.get("runtimeConfig");
        // Backward-compatible aliases are copied only into the built-in registered processor.
        copyOption(processor, runtimeConfig, "documentModelExecutable");
        copyOption(processor, runtimeConfig, "documentModelExecutableMode");
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

    /** Whether extraction must cross a second process boundary for this document. */
    public static boolean usesProcessingSubprocess(ResolvedPipeline pipeline) {
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

    private static Map<String, PipelineDefinition> pipelineDefinitions(JsonNode request) {
        Map<String, PipelineDefinition> pipelines = new LinkedHashMap<>();
        pipelines.put(STANDARD_TEXT_PIPELINE, new PipelineDefinition(STANDARD_TEXT_PIPELINE,
                "STANDARD_TEXT", "auto", "recursive-character", 2_000, 200, Map.of(), Map.of()));
        pipelines.put(CODE_PIPELINE, new PipelineDefinition(CODE_PIPELINE,
                "CODE", "code", "recursive-character", 1_800, 180,
                Map.of("separators", List.of("\n\n", "\n", " ")), Map.of()));
        pipelines.put(VLM_PIPELINE, new PipelineDefinition(VLM_PIPELINE,
                "VLM", "pdf", "recursive-character", 2_000, 200, Map.of(),
                builtinDocumentProcessor("VLM")));
        pipelines.put(OCR_PIPELINE, new PipelineDefinition(OCR_PIPELINE,
                "OCR", "pdf", "recursive-character", 2_000, 200, Map.of(),
                builtinDocumentProcessor("OCR")));
        pipelines.put(TABLE_AWARE_PIPELINE, new PipelineDefinition(TABLE_AWARE_PIPELINE,
                "TABLE_AWARE", "table", "recursive-character", 2_000, 200,
                Map.of("preserveTables", true), Map.of()));
        pipelines.put(KEYWORD_ONLY_PIPELINE, new PipelineDefinition(KEYWORD_ONLY_PIPELINE,
                "KEYWORD_ONLY", "auto", "recursive-character", 2_000, 200,
                Map.of("keywordOnly", true), Map.of()));
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        Map<String, Map<String, Object>> executors = executorDefinitions(registry);
        Map<String, Object> definitionsById = unifiedDefinitions(registry);
        appendPipelineDefinitions(pipelines, registry == null ? null : registry.get("defaults"),
                executors, definitionsById);
        appendPipelineDefinitions(pipelines, request == null ? null : request.get("registeredPipelines"),
                executors, definitionsById);
        JsonNode definitions = request == null ? null : request.get("pipelines");
        appendPipelineDefinitions(pipelines, definitions, executors, definitionsById);
        return pipelines;
    }

    private static void appendPipelineDefinitions(Map<String, PipelineDefinition> pipelines,
                                                  JsonNode definitions,
                                                  Map<String, Map<String, Object>> executors,
                                                  Map<String, Object> definitionsById) {
        if (definitions == null || !definitions.isArray()) return;
        for (JsonNode definition : definitions) {
            if (!definition.isObject()) continue;
            String id = text(definition, "pipelineId");
            if (id == null) continue;
            String registeredPipelineId = text(definition, "registeredPipelineId");
            PipelineDefinition inherited = pipelines.get(registeredPipelineId);
            if (registeredPipelineId != null && inherited == null) {
                throw new IllegalArgumentException("Unknown registeredPipelineId: " + registeredPipelineId);
            }
            String type = firstNonBlank(text(definition, "pipelineType"),
                            inherited == null ? null : inherited.pipelineType(), "CUSTOM")
                    .toUpperCase(Locale.ROOT);
            if (inherited == null) {
                if ("VLM".equals(type)) {
                    inherited = pipelines.get(VLM_PIPELINE);
                } else if ("OCR".equals(type)) {
                    inherited = pipelines.get(OCR_PIPELINE);
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
            copyOption(options, definition, "processingMode");
            Map<String, Object> processor = new LinkedHashMap<>(
                    inherited == null ? Map.of() : inherited.processor());
            String executorId = text(definition, "executorId");
            if (executorId != null) {
                Map<String, Object> executor = executors.get(executorId);
                if (executor == null) {
                    throw new IllegalArgumentException("Unknown pipeline executorId: " + executorId);
                }
                processor.putAll(executor);
                processor.put("executorId", executorId);
            }
            mergeOptions(processor, definition.get("processor"));
            applyDefinitionReference(definition, definitionsById, processor);
            if (processor.isEmpty() && (options.containsKey("pipelineDefinition")
                    || options.containsKey("pipelineDefinitionPath"))) {
                processor.put("type", "UNIFIED_PIPELINE");
                copyMapValue(processor, options, "pipelineDefinition");
                copyMapValue(processor, options, "pipelineDefinitionPath");
            }
            pipelines.put(id, new PipelineDefinition(id, type,
                    firstNonBlank(text(definition, "loaderName"), defaultLoader),
                    firstNonBlank(text(definition, "chunkerName"),
                            inherited == null ? null : inherited.chunkerName(), "recursive-character"),
                    positiveInt(definition, "chunkSize", defaultSize),
                    nonNegativeInt(definition, "chunkOverlap", defaultOverlap),
                    Map.copyOf(options), Map.copyOf(processor)));
        }
    }

    private static String registryError(JsonNode request) {
        JsonNode registry = request == null ? null : request.get("pipelineRegistry");
        if (registry == null || registry.isNull()) return null;
        if (!registry.isObject()) return "pipelineRegistry must be an object.";
        for (String field : List.of("defaults", "definitions", "executors")) {
            JsonNode value = registry.get(field);
            if (value != null && !value.isNull() && !value.isArray()) {
                return "pipelineRegistry." + field + " must be an array.";
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
        Map<String, Object> local = new LinkedHashMap<>(processor);
        processor.clear();
        processor.putAll(registered);
        processor.putAll(local);
        processor.put("executorId", executorId);
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

    private static Map<String, Object> builtinDocumentProcessor(String pipelineType) {
        Map<String, Object> processor = new LinkedHashMap<>();
        processor.put("type", "KOMPILE_SUBPROCESS");
        processor.put("adapter", "vlm-test");
        processor.put("subprocessMode", "vlm-test");
        processor.put("componentId", "kompile-vlm-test");
        processor.put("pipelineType", pipelineType);
        return Map.copyOf(processor);
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
        if (isCodeFile(file)) return "code";
        return "text";
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
        aliases(aliases, "pdf", "pdf", "pdfbox");
        aliases(aliases, "code", "code", "source-code");
        aliases(aliases, "table", "table", "table-aware");
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

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.path(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static void pipelineTemplate(ArrayNode target, String id, String type, String loader,
                                         String chunker, int size, int overlap, String description) {
        pipelineTemplate(target, id, type, loader, chunker, size, overlap, true, description);
    }

    private static void pipelineTemplate(ArrayNode target, String id, String type, String loader,
                                         String chunker, int size, int overlap, boolean available,
                                         String description) {
        target.addObject().put("pipelineId", id).put("pipelineType", type)
                .put("loaderName", loader).put("chunkerName", chunker)
                .put("chunkSize", size).put("chunkOverlap", overlap)
                .put("available", available).put("description", description);
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
                                      Map<String, Object> processor) {
    }
}
