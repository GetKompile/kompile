/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalCrawlCapabilities;
import ai.kompile.cli.main.project.LocalModelPipelineRunner;
import ai.kompile.cli.main.project.PipelineRuntimeSupervisor;
import ai.kompile.modelmanager.vlm.dynamic.VlmCustomModelSet;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** MCP-native authoring, versioning, validation, and execution for every pipeline. */
public final class PipelineTool implements CliTool {
    private static final Set<String> ACTIONS = Set.of(
            "capabilities", "list", "get", "versions", "create", "update",
            "validate", "test", "diff", "promote", "rollback", "run",
            "status", "cancel", "delete");
    private static final ExecutorService RUNS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mcp-pipeline-run");
        thread.setDaemon(true);
        return thread;
    });
    private static final ConcurrentHashMap<String, RunState> ACTIVE_RUNS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;

    public PipelineTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "pipeline";
    }

    @Override
    public String description() {
        return "Build, validate, version, promote, roll back, run, and maintain project pipelines. "
                + "Use capabilities to discover the live step catalog and the Sequence/Graph wiring contract, "
                + "compose compatible steps, bind each model role to a model definition, validate, test, then run. "
                + "Project registration modelRefs and request input.modelBindings are merged before launch; explicit "
                + "request bindings win and the resolved model descriptors are injected into the runtime runner. "
                + "All execution uses MCP-owned reusable stdio runtimes; callers never configure processes or executable paths. "
                + "run is asynchronous by default; poll status with the returned runId until terminal=true. "
                + "FAILED status includes the page/step diagnostic and stops execution on the first VLM page error. "
                + "Use model_runtime status/bootstrap/import/convert to acquire or prepare artifacts, or use localPath for local-only models.";
    }

    @Override
    public String compactHint() {
        return "action=capabilities|list|get|versions|create|update|validate|test|diff|promote|rollback|run|status|cancel|delete; "
                + "discover stepCatalog/composition.graphWiring, bind model ids, validate/test, then run";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode actions = properties.putObject("action").put("type", "string")
                .put("description", "Lifecycle action. create/update/validate/test require definition; "
                        + "run/get/versions require pipelineId; status/cancel require runId.")
                .putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);
        properties.putObject("pipelineId").put("type", "string")
                .put("description", "Pipeline id in the managed version store or kompile.project.json.");
        properties.putObject("version").put("type", "integer").put("minimum", 1);
        properties.putObject("fromVersion").put("type", "integer").put("minimum", 1);
        properties.putObject("toVersion").put("type", "integer").put("minimum", 1);
        properties.putObject("expectedActiveVersion").put("type", "integer").put("minimum", 0);

        ObjectNode definition = properties.putObject("definition");
        definition.put("type", "object")
                .put("description", "Portable UnifiedPipelineDefinition. Start with pipeline action=capabilities, "
                        + "choose SequencePipeline or GraphPipeline, copy a compatible template or compose catalog "
                        + "steps, bind model roles, then validate before create/run. pipelineSpec.@class and executable "
                        + "steps/nodes are required for execution.");
        ObjectNode definitionProperties = definition.putObject("properties");
        definitionProperties.putObject("schemaVersion").put("type", "integer").put("default", 1);
        definitionProperties.putObject("pipelineId").put("type", "string");
        definitionProperties.putObject("displayName").put("type", "string");
        definitionProperties.putObject("description").put("type", "string");
        definitionProperties.putObject("kind").put("type", "string");
        definitionProperties.putObject("topology").put("type", "string");
        definitionProperties.putObject("modelSetId").put("type", "string")
                .put("description", "Legacy/default model selection; modelBindings.default is authoritative.");
        definitionProperties.putObject("modelBindings").put("type", "object")
                .put("description", "Role-to-model id map. Bind ids from model_runtime status/bootstrap/import "
                        + "or inline modelDefinitions; explicit bindings take precedence over legacy model selectors.")
                .putObject("additionalProperties").put("type", "string");
        definitionProperties.putObject("modelDefinitions").put("type", "object")
                .put("description", "Optional inline definitions keyed by model id. Missing definitions are hydrated from the model registry; inline values win.");
        ObjectNode pipelineSpec = definitionProperties.putObject("pipelineSpec");
        pipelineSpec.put("type", "object")
                .put("description", "Serialized SequencePipeline or GraphPipeline. Sequence steps run in listed order; "
                        + "Graph nodes run after all names in inputs complete.");
        ObjectNode specProperties = pipelineSpec.putObject("properties");
        specProperties.putObject("@class").put("type", "string")
                .put("description", "Use the concrete SequencePipeline or GraphPipeline class returned by capabilities.");
        specProperties.putObject("id").put("type", "string");
        specProperties.putObject("inputNodeName").put("type", "string")
                .put("description", "Graph-only logical input name. Nodes may list it in inputs; runtime resolves it to pipeline_input.");
        specProperties.putObject("outputNodeName").put("type", "string")
                .put("description", "Graph-only node whose Data becomes the pipeline result.");

        ObjectNode steps = specProperties.putObject("steps").put("type", "array");
        ObjectNode step = steps.putObject("items").put("type", "object");
        ObjectNode stepProperties = step.putObject("properties");
        stepProperties.putObject("@class").put("type", "string");
        stepProperties.putObject("runnerClassName").put("type", "string")
                .put("description", "Runner class from composition.stepCatalog; do not invent provider-specific classes.");
        ObjectNode stepParameters = stepProperties.putObject("parameters").put("type", "object");
        ObjectNode stepBindings = stepParameters.putObject("properties")
                .putObject("inputDataBindings").put("type", "object")
                .put("description", "Optional target-slot to source reference map. A source is node.outputKey, "
                        + "node for the whole Data record, or pipeline_input[.key]. Without bindings, a single "
                        + "predecessor is passed through and multiple predecessors are merged in inputs order.");
        stepBindings.putObject("additionalProperties").put("type", "string");

        ObjectNode nodes = specProperties.putObject("nodes").put("type", "array");
        ObjectNode node = nodes.putObject("items").put("type", "object");
        ObjectNode nodeProperties = node.putObject("properties");
        nodeProperties.putObject("@graphNodeType").put("type", "string")
                .put("description", "STANDARD, LOOP, or another graph node type advertised by capabilities.");
        nodeProperties.putObject("name").put("type", "string");
        nodeProperties.putObject("inputs").put("type", "array")
                .putObject("items").put("type", "string")
                .put("description", "Upstream node names. Use pipeline_input for the external request.");
        ObjectNode nodeStepConfig = nodeProperties.putObject("stepConfig").put("type", "object");
        ObjectNode nodeStepProperties = nodeStepConfig.putObject("properties");
        nodeStepProperties.putObject("@class").put("type", "string");
        nodeStepProperties.putObject("runnerClassName").put("type", "string");
        ObjectNode nodeParameters = nodeStepProperties.putObject("parameters").put("type", "object");
        ObjectNode nodeBindings = nodeParameters.putObject("properties")
                .putObject("inputDataBindings").put("type", "object")
                .put("description", "Explicit target-slot to source reference map; see composition.graphWiring.");
        nodeBindings.putObject("additionalProperties").put("type", "string");
        definition.putArray("required").add("pipelineId").add("pipelineSpec");

        ObjectNode input = properties.putObject("input");
        input.put("type", "object")
                .put("description", "Execution inputs. VLM_DOCUMENT accepts filePath/path for an application/pdf "
                        + "document. Composed graphs exchange named Data keys such as image, input_ids, "
                        + "attention_mask, and position_ids; raw text requires an explicit tokenizer/adapter step. "
                        + "Use capabilities for the selected pipeline's media contract.");
        ObjectNode inputProperties = input.putObject("properties");
        inputProperties.putObject("filePath").put("type", "string");
        inputProperties.putObject("path").put("type", "string");
        inputProperties.putObject("image").put("type", "object")
                .put("description", "Image payload consumed by image preprocessing steps; use the adapter's image contract.");
        inputProperties.putObject("text").put("type", "string")
                .put("description", "Optional source text; it is not tokenized implicitly.");
        inputProperties.putObject("input_ids").put("type", "object")
                .put("description", "Token-id tensor passed between tokenizer, embedding, fusion, and decoder steps.");
        inputProperties.putObject("attention_mask").put("type", "object");
        inputProperties.putObject("position_ids").put("type", "object");
        inputProperties.putObject("modelBindings").put("type", "object")
                .put("description", "Request-scoped role-to-model overrides. These are merged with the project "
                        + "registration and definition before model resolution; request values win.");
        inputProperties.putObject("modelRefs").put("type", "array")
                .putObject("items").put("type", "string")
                .put("description", "Optional request-scoped project model references merged with registered modelRefs.");
        inputProperties.putObject("resolvedModels").put("type", "object");

        ObjectNode modelRuntime = properties.putObject("modelRuntime");
        modelRuntime.put("type", "object")
                .put("description", "Model runtime overrides. model_runtime bootstrap acquires configured remote artifacts "
                        + "and import forces acquisition; status reports inventory. Set localPath to an existing model file "
                        + "or directory for direct local-only resolution, which skips staging and project-manifest mutation. "
                        + "Without localPath, pipeline run may auto-bootstrap and pipeline test remains read-only.");
        ObjectNode runtimeProperties = modelRuntime.putObject("properties");
        runtimeProperties.putObject("autoBootstrap").put("type", "boolean");
        runtimeProperties.putObject("forceBootstrap").put("type", "boolean");
        runtimeProperties.putObject("source").put("type", "string");
        runtimeProperties.putObject("repository").put("type", "string");
        runtimeProperties.putObject("revision").put("type", "string");
        runtimeProperties.putObject("type").put("type", "string");
        runtimeProperties.putObject("localPath").put("type", "string")
                .put("description", "Existing local model file or directory; bypasses staging and registry writes.");
        runtimeProperties.putObject("stagingExecutable").put("type", "string");
        runtimeProperties.putObject("stagingJar").put("type", "string");
        runtimeProperties.putObject("servingExecutable").put("type", "string");
        runtimeProperties.putObject("servingJar").put("type", "string");
        runtimeProperties.putObject("javaExecutable").put("type", "string");
        runtimeProperties.putObject("heapSize").put("type", "string");
        runtimeProperties.putObject("environment").put("type", "object")
                .putObject("additionalProperties").put("type", "string");

        properties.putObject("timeoutMinutes").put("type", "integer")
                .put("minimum", 1).put("default", 30);
        properties.putObject("wait").put("type", "boolean").put("default", false);
        properties.putObject("runId").put("type", "string");
        properties.putObject("actor").put("type", "string");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "pipeline";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage and execute project pipelines");
        String action = params.path("action").asText("").trim().toLowerCase();
        if (!ACTIONS.contains(action)) return ToolResult.error("Unknown pipeline action: " + action);
        Path workingDirectory = context.getWorkingDirectory().toAbsolutePath().normalize();
        Path projectRoot = new KompileProjectStore().findProjectRoot(workingDirectory)
                .orElse(workingDirectory);
        PipelineDefinitionStore store = new PipelineDefinitionStore(
                projectRoot.resolve("data/pipelines"), mapper);
        String actor = first(params.path("actor").asText(null), context.getSessionId(), "mcp-agent");
        try {
            Object result = switch (action) {
                case "capabilities" -> capabilities();
                case "list" -> list(projectRoot, store);
                case "get" -> get(projectRoot, store, params);
                case "versions" -> store.versions(requiredId(params));
                case "create" -> save(store, params, actor, true);
                case "update" -> save(store, params, actor, false);
                case "validate" -> validate(definition(params));
                case "test" -> test(projectRoot, definition(params), object(params, "input"),
                        object(params, "modelRuntime"), timeout(params));
                case "diff" -> diff(store, params);
                case "promote", "rollback" -> store.promote(requiredId(params),
                        requiredLong(params, "version"), optionalLong(params, "expectedActiveVersion"), actor);
                case "run" -> run(projectRoot, store, params);
                case "status" -> status(requiredText(params, "runId"));
                case "cancel" -> cancel(requiredText(params, "runId"));
                case "delete" -> Map.of("archived", store.archive(requiredId(params),
                        optionalLong(params, "expectedActiveVersion"), actor));
                default -> throw new IllegalStateException("Unhandled action " + action);
            };
            return ToolResult.success("pipeline " + action,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                    Map.of("action", action, "projectRoot", projectRoot.toString()));
        } catch (Throwable failure) {
            Map<String, Object> diagnostic = diagnostic(failure, "PIPELINE_" + action.toUpperCase());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("action", action);
            output.put("status", "FAILED");
            output.put("diagnostic", diagnostic);
            String rendered;
            try {
                rendered = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
            } catch (Exception serializationFailure) {
                rendered = output.toString();
            }
            return new ToolResult("pipeline " + action, rendered,
                    Map.of("action", action, "projectRoot", projectRoot.toString(),
                            "diagnostic", diagnostic), true);
        }
    }

    private Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", UnifiedPipelineDefinition.CURRENT_SCHEMA_VERSION);
        result.put("actions", ACTIONS.stream().sorted().toList());
        result.put("executionContract", "UNIFIED_PIPELINE");
        result.put("runtime", Map.of(
                "transport", "stdio",
                "startup", "on-demand",
                "reuse", "bounded lease pool",
                "callerManagedProcesses", false));
        result.put("stepCatalog", stepCatalog());
        result.put("builtinDocumentPipeline",
                LocalCrawlCapabilities.builtinModelProcessor("VLM").get("pipelineDefinition"));
        Map<String, Object> composition = new LinkedHashMap<>();
        composition.put("definitionField", "pipelineSpec");
        composition.put("topologies", List.of("SEQUENCE", "GRAPH"));
        composition.put("modelSpecificPipelineRequired", false);
        composition.put("builtinVlmStep",
                "Optional VLM_DOCUMENT convenience adapter; compose catalog steps directly when needed.");
        composition.put("defaultTemplates", Map.of(
                "textModelText", LocalCrawlCapabilities.builtinTextModelProcessor().get("pipelineDefinition"),
                "visionMultimodel", LocalCrawlCapabilities.builtinVisionProcessor().get("pipelineDefinition")));
        composition.put("modelRoleParameters", Map.of(
                "modelRole", "Resolve modelUri/modelPath from modelBindings before launch.",
                "tokenizerRole", "Resolve tokenizerPath/tokenizerUri from the same local model binding."));
        composition.put("customDefinitionSources", List.of(
                "inline pipelineDefinition", "pipelineDefinitionPath", "pipelineDefinitionId",
                "pipelineRegistry.definitions", "registeredPipelines"));
        composition.put("roleBindingWorkflow", List.of(
                "acquire or import each artifact with model_runtime (or define localPath for local-only use)",
                "bind returned model ids to pipeline roles",
                "validate the composed definition, test it with a representative input, then run it"));
        Map<String, Object> graphWiring = new LinkedHashMap<>();
        graphWiring.put("sequence", "steps execute in array order; each step receives the previous step's Data.");
        graphWiring.put("graphDependencies", "node.inputs names upstream nodes; use pipeline_input for the external request.");
        graphWiring.put("standardNodeHandoff", "one predecessor is passed through; multiple predecessors are merged in inputs order.");
        graphWiring.put("explicitBindings", "stepConfig.parameters.inputDataBindings maps target slots to node.outputKey, node, or pipeline_input[.key].");
        graphWiring.put("bindingPrecedence", "explicit inputDataBindings replaces the default whole-record/merge handoff for that node.");
        graphWiring.put("loopHandoff", "LOOP inputs are merged into loop state; feedbackKeys replace those keys on each iteration.");
        graphWiring.put("output", "Graph outputNodeName selects the final Data record; a sequence returns its final step.");
        graphWiring.put("dataContract", "Steps exchange named Data keys. Use the live stepCatalog schemas and model metadata to choose compatible keys.");
        graphWiring.put("example", Map.of(
                "nodeInputs", List.of("vision_encoder", "text_embedding", "pipeline_input"),
                "inputDataBindings", Map.of(
                        "image_features", "vision_encoder.image_features",
                        "text_embeddings", "text_embedding.text_embeddings",
                        "input_ids", "pipeline_input.input_ids")));
        composition.put("graphWiring", Map.copyOf(graphWiring));
        result.put("composition", Map.copyOf(composition));
        result.put("modelSelection", Map.of(
                "precedence", List.of("modelBindings.default", "modelId", "vlmModel", "modelSetId"),
                "conflicts", "Conflicting explicit selectors are rejected before provisioning.",
                "projectModels", "Resolved from kompile.project.json; test never mutates registrations.",
                "definitions", "Referenced ids are hydrated from the model registry; inline modelDefinitions override registry entries."));
        result.put("modelDefinitionLifecycle", Map.of(
                "tool", "vlm_model_definition",
                "actions", List.of("list", "get", "create", "update", "delete", "validate"),
                "registry", "~/.kompile/config/vlm-model-sets.json",
                "providerModel", "free-form provider/repository/component/runtime fields"));
        result.put("modelAcquisition", Map.of(
                "tool", "model_runtime",
                "actions", List.of("status", "bootstrap", "import", "convert", "optimize"),
                "bootstrap", "Acquire a configured remote artifact using source/repository/revision/format/type.",
                "import", "Force provisioning; localPath is consumed directly, while remote fields trigger acquisition.",
                "localOnly", "Use modelDefinitions.<id>.localPath to consume an existing artifact without staging or registry writes.",
                "convert", "Use model_runtime convert for supported local source formats before binding the resulting artifact.",
                "optimize", "Run the configured GraphOptimizer on a localPath or modelId; selectedPasses/profile and all optimizer controls are explicit.",
                "chain", "convert then optimize by passing conversion.outputPath as optimize.localPath; both actions use the distribution's configured native/JAR worker.",
                "resultFields", List.of("modelId", "modelPath", "tokenizerPath", "artifactReady", "runtimeStatus", "outputPath")));
        result.put("inputContracts", Map.of(
                "VLM_DOCUMENT", Map.of(
                        "supportedMediaTypes", List.of("application/pdf"),
                        "directRasterImages", false,
                        "inputFields", List.of("filePath", "path")),
                "VISION_MULTIMODEL", Map.of(
                        "supportedMediaTypes", List.of("image/*", "application/pdf"),
                        "directRasterImages", true,
                        "inputFields", List.of("image", "input_ids", "attention_mask", "position_ids",
                                "filePath", "path", "text"),
                        "text", "Raw text is descriptive only; a tokenizer/adapter step must materialize input_ids before TextEmbeddingStepRunner and VisionTextFusionStepRunner.")));
        result.put("testContract", Map.of(
                "persistentWrites", false,
                "modelProvisioning", "Use model_runtime before pipeline test.",
                "failureOutput", "Structured diagnostic with failureStage, exception chain, and stack trace."));
        return result;
    }

    private List<Map<String, Object>> stepCatalog() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> discovered : PipelineDefinitionValidator.availableSteps()) {
            Map<String, Object> step = new LinkedHashMap<>(discovered);
            step.put("availability", "HOST_CLASSPATH");
            Map<String, Object> ioContract = stepIoContract(String.valueOf(step.get("type")));
            if (!ioContract.isEmpty()) {
                step.put("ioContract", ioContract);
            }
            result.add(Map.copyOf(step));
        }
        boolean vlmPresent = result.stream().anyMatch(step ->
                "VLM_DOCUMENT".equals(String.valueOf(step.get("type"))));
        if (!vlmPresent) {
            Map<String, Object> processor = LocalCrawlCapabilities.builtinModelProcessor("VLM");
            Map<?, ?> definition = (Map<?, ?>) processor.get("pipelineDefinition");
            Map<?, ?> spec = (Map<?, ?>) definition.get("pipelineSpec");
            Map<?, ?> configuredStep = (Map<?, ?>) ((List<?>) spec.get("steps")).get(0);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("description", "Reusable end-to-end extraction for application/pdf documents; "
                    + "direct raster-image inputs are unsupported.");
            schema.put("supportedMediaTypes", List.of("application/pdf"));
            schema.put("parameters", Map.of(
                    "outputFormat", "DOCTAGS|MARKDOWN|JSON|TEXT (default DOCTAGS)",
                    "pdfRenderDpi", "integer (default 300)",
                    "pageBatchSize", "integer (default 1)",
                    "maxPages", "integer (default 0, all pages)",
                    "failFastOnPageError", "boolean (default true)",
                    "maxNewTokens", "integer",
                    "temperature", "number"));
            schema.put("defaultParameters", configuredStep.get("parameters"));
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("type", "VLM_DOCUMENT");
            step.put("runnerClassName", configuredStep.get("runnerClassName"));
            step.put("schema", Map.copyOf(schema));
            step.put("availability", "RUNTIME_BUILTIN");
            step.put("discovery", "Canonical built-in pipeline definition");
            result.add(Map.copyOf(step));
        }
        result.sort(java.util.Comparator.comparing(value -> String.valueOf(value.get("type"))));
        return List.copyOf(result);
    }

    private Map<String, Object> stepIoContract(String stepType) {
        return switch (stepType) {
            case "VLM_IMAGE_PREPROCESSING" -> Map.of(
                    "inputs", List.of("image"),
                    "outputs", List.of("pixel_values", "pixel_attention_mask"),
                    "description", "Normalize and tile an image for a vision encoder.");
            case "VLM_VISION_ENCODER" -> Map.of(
                    "inputs", List.of("pixel_values", "pixel_attention_mask"),
                    "outputs", List.of("image_features"),
                    "description", "Encode preprocessed pixels; outputNames may rename image_features.");
            case "VLM_TEXT_EMBEDDING" -> Map.of(
                    "inputs", List.of("input_ids", "attention_mask", "position_ids"),
                    "outputs", List.of("text_embeddings"),
                    "description", "Embed token tensors; the model determines which listed tensors are required.");
            case "VLM_VISION_TEXT_FUSION" -> Map.of(
                    "inputs", List.of("image_features", "text_embeddings", "input_ids"),
                    "optionalInputs", List.of("attention_mask", "position_ids"),
                    "outputs", List.of("inputs_embeds", "input_ids", "attention_mask", "position_ids"),
                    "description", "Replace image-token embeddings with vision features and preserve decoder control tensors.");
            case "VLM_DECODER" -> Map.of(
                    "inputs", List.of("inputs_embeds", "input_ids"),
                    "optionalInputs", List.of("attention_mask", "position_ids", "kv_cache"),
                    "outputs", List.of("next_token_id", "is_eos", "kv_cache", "input_ids", "attention_mask", "position_ids"),
                    "description", "Run one autoregressive decoder body iteration.");
            case "VLM_TOKEN_DECODING" -> Map.of(
                    "inputs", List.of("generated_tokens"),
                    "outputs", List.of("generated_text"),
                    "description", "Decode accumulated token ids using the bound tokenizer.");
            default -> Map.of();
        };
    }

    private JsonNode list(Path projectRoot, PipelineDefinitionStore store) throws Exception {
        Map<String, ObjectNode> catalog = new LinkedHashMap<>();
        for (UnifiedPipelineDefinition definition : store.listActive()) {
            ObjectNode entry = mapper.valueToTree(definition);
            entry.put("registrySource", "managed-version-store");
            entry.putArray("registrySources").add("managed-version-store");
            catalog.put(definition.getPipelineId(), entry);
        }
        for (JsonNode registration : projectPipelines(projectRoot)) {
            String id = registration.path("pipelineId").asText("");
            if (id.isBlank()) continue;
            ObjectNode entry = catalog.get(id);
            if (entry == null) {
                entry = registration.deepCopy();
                entry.put("registrySource", "kompile.project.json");
                entry.putArray("registrySources").add("kompile.project.json");
                catalog.put(id, entry);
            } else {
                entry.withArray("registrySources").add("kompile.project.json");
                entry.set("projectRegistration", registration.deepCopy());
            }
        }
        return mapper.valueToTree(catalog.values());
    }

    private Object get(Path projectRoot, PipelineDefinitionStore store, JsonNode params) throws Exception {
        String id = requiredId(params);
        Long version = optionalLong(params, "version");
        var managed = version == null ? store.active(id) : store.version(id, version);
        if (managed.isPresent()) {
            ObjectNode result = mapper.valueToTree(managed.get());
            result.put("registrySource", "managed-version-store");
            result.putArray("registrySources").add("managed-version-store");
            JsonNode projectRegistration = projectPipeline(projectRoot, id);
            if (projectRegistration != null) {
                result.withArray("registrySources").add("kompile.project.json");
                result.set("projectRegistration", projectRegistration.deepCopy());
            }
            return result;
        }
        if (version == null) {
            JsonNode registered = projectPipeline(projectRoot, id);
            if (registered != null) {
                ObjectNode result = registered.deepCopy();
                result.put("registrySource", "kompile.project.json");
                result.putArray("registrySources").add("kompile.project.json");
                return result;
            }
        }
        throw new IllegalArgumentException(
                "Unknown pipeline " + id + (version == null ? "" : "@" + version));
    }

    private ArrayNode projectPipelines(Path projectRoot) {
        return new LocalProjectCrawlBackend(mapper).projectPipelineDefaults(projectRoot);
    }

    private JsonNode projectPipeline(Path projectRoot, String id) {
        for (JsonNode candidate : projectPipelines(projectRoot)) {
            if (id.equals(candidate.path("pipelineId").asText())) return candidate;
        }
        return null;
    }

    private UnifiedPipelineDefinition save(PipelineDefinitionStore store, JsonNode params,
                                           String actor, boolean create) throws Exception {
        UnifiedPipelineDefinition definition = definition(params);
        PipelineDefinitionValidator.Validation validation =
                PipelineDefinitionValidator.validate(definition);
        if (!validation.valid()) throw new IllegalArgumentException(String.join("; ", validation.errors()));
        if (create && store.active(definition.getPipelineId()).isPresent()) {
            throw new IllegalArgumentException("Pipeline already exists: " + definition.getPipelineId());
        }
        Long expected = optionalLong(params, "expectedActiveVersion");
        if (create && expected == null) expected = 0L;
        return store.save(definition, expected, actor);
    }

    private Map<String, Object> validate(UnifiedPipelineDefinition definition) {
        PipelineDefinitionValidator.Validation validation =
                PipelineDefinitionValidator.validate(definition);
        return Map.of("valid", validation.valid(), "errors", validation.errors(),
                "warnings", validation.warnings());
    }

    private Map<String, Object> test(Path root, UnifiedPipelineDefinition definition,
                                     Map<String, Object> input,
                                     Map<String, Object> runtime,
                                     Duration timeout) throws Exception {
        PipelineDefinitionValidator.Validation validation =
                PipelineDefinitionValidator.validate(definition);
        if (!validation.valid()) return Map.of("valid", false, "errors", validation.errors());

        String runId = UUID.randomUUID().toString();
        UnifiedPipelineDefinition prepared = null;
        try {
            prepared = prepare(root, definition, input, runtime, null, false);
            Map<String, Object> output = PipelineRuntimeSupervisor.execute(prepared, input, timeout);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", true);
            result.put("status", "COMPLETED");
            result.put("runId", runId);
            result.put("pipelineId", prepared.getPipelineId());
            result.put("contentDigest", prepared.getContentDigest());
            result.put("resolvedDefinition", prepared);
            if (prepared.getModelBindings() != null) {
                result.put("modelBindings", prepared.getModelBindings());
            }
            if (prepared.getResolvedModels() != null) {
                result.put("resolvedModels", prepared.getResolvedModels());
            }
            result.put("output", output);
            return Map.copyOf(result);
        } catch (Throwable failure) {
            Map<String, Object> details = new LinkedHashMap<>(
                    PipelineRuntimeSupervisor.diagnostic(failure, "PIPELINE_TEST"));
            details.put("action", "test");
            details.put("runId", runId);
            details.put("pipelineId", definition.getPipelineId());
            details.put("requestedDefinition", definition);
            if (prepared != null) {
                details.put("contentDigest", prepared.getContentDigest());
                details.put("resolvedDefinition", prepared);
                if (prepared.getModelBindings() != null) {
                    details.put("modelBindings", prepared.getModelBindings());
                }
                if (prepared.getResolvedModels() != null) {
                    details.put("resolvedModels", prepared.getResolvedModels());
                }
            }
            throw new PipelineActionFailure(details, failure);
        }
    }

    private Map<String, Object> diff(PipelineDefinitionStore store, JsonNode params) throws Exception {
        String id = requiredId(params);
        long from = requiredLong(params, "fromVersion");
        long to = requiredLong(params, "toVersion");
        UnifiedPipelineDefinition left = store.version(id, from).orElseThrow();
        UnifiedPipelineDefinition right = store.version(id, to).orElseThrow();
        return Map.of("pipelineId", id, "fromVersion", from, "toVersion", to,
                "fromDigest", String.valueOf(left.getContentDigest()),
                "toDigest", String.valueOf(right.getContentDigest()),
                "changed", !String.valueOf(left.getContentDigest()).equals(String.valueOf(right.getContentDigest())),
                "from", left, "to", right);
    }

    private Object run(Path root, PipelineDefinitionStore store, JsonNode params) throws Exception {
        ExecutableDefinition executable = executableDefinition(root, store, params);
        UnifiedPipelineDefinition definition = executable.definition();
        Map<String, Object> input = object(params, "input");
        Map<String, Object> runtime = object(params, "modelRuntime");
        Duration timeout = timeout(params);
        String runId = UUID.randomUUID().toString();
        RunState state = new RunState(runId, definition.getPipelineId(), definition.getDefinitionVersion());
        ACTIVE_RUNS.put(runId, state);
        state.future = CompletableFuture.runAsync(() -> {
            if (state.cancelRequested.get()) {
                state.status = "CANCELLED";
                return;
            }
            state.status = "RUNNING";
            try {
                UnifiedPipelineDefinition prepared = prepare(
                        root, definition, input, runtime, executable.registration(), true);
                try (PipelineRuntimeSupervisor.Lease lease =
                             PipelineRuntimeSupervisor.acquire(prepared, timeout)) {
                    if (state.cancelRequested.get()) {
                        state.status = "CANCELLED";
                        return;
                    }
                    PipelineRuntimeSupervisor.RunningExecution execution = lease.start(input);
                    state.execution = execution;
                    if (state.cancelRequested.get()) {
                        execution.cancel(Duration.ofSeconds(5));
                    }
                    state.output = execution.await(timeout);
                    state.status = state.cancelRequested.get() ? "CANCELLED" : "COMPLETED";
                }
            } catch (CancellationException failure) {
                state.status = "CANCELLED";
            } catch (Throwable failure) {
                if (state.cancelRequested.get()) {
                    state.status = "CANCELLED";
                } else {
                    state.diagnostic = PipelineRuntimeSupervisor.diagnostic(failure, "PIPELINE_RUN");
                    state.error = String.valueOf(state.diagnostic.getOrDefault(
                            "summary", rootMessage(failure)));
                    state.status = "FAILED";
                }
            } finally {
                state.execution = null;
            }
        }, RUNS);
        if (params.path("wait").asBoolean(false)) state.future.join();
        return state.snapshot();
    }

    private ExecutableDefinition executableDefinition(
            Path root, PipelineDefinitionStore store, JsonNode params) throws Exception {
        String id = requiredId(params);
        Long version = optionalLong(params, "version");
        var managed = version == null ? store.active(id) : store.version(id, version);
        if (managed.isPresent()) return new ExecutableDefinition(managed.get(), null);
        if (version != null) {
            throw new IllegalArgumentException("Unknown managed pipeline " + id + "@" + version);
        }

        JsonNode registration = projectPipeline(root, id);
        if (registration == null) throw new IllegalArgumentException("Unknown pipeline " + id);
        JsonNode processor = registration.path("processor");
        JsonNode inline = processor.get("pipelineDefinition");
        UnifiedPipelineDefinition definition;
        if (inline != null && inline.isObject()) {
            definition = mapper.convertValue(inline, UnifiedPipelineDefinition.class);
        } else {
            String configuredPath = processor.path("pipelineDefinitionPath").asText(null);
            if (configuredPath != null && !configuredPath.isBlank()) {
                Path path = root.resolve(configuredPath).normalize().toAbsolutePath();
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (!path.startsWith(normalizedRoot) || !Files.isRegularFile(path)) {
                    throw new IllegalArgumentException(
                            "Project pipeline " + id + " references an unavailable definition: " + path);
                }
                definition = mapper.readValue(path.toFile(), UnifiedPipelineDefinition.class);
            } else {
                String definitionId = processor.path("pipelineDefinitionId").asText(null);
                if (definitionId == null || definitionId.isBlank()) {
                    throw new IllegalArgumentException(
                            "Project pipeline " + id + " has no executable UnifiedPipelineDefinition.");
                }
                definition = store.active(definitionId).orElseThrow(() -> new IllegalArgumentException(
                        "Project pipeline " + id + " references unknown managed definition "
                                + definitionId));
            }
        }
        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            definition.setPipelineId(id);
        }
        return new ExecutableDefinition(definition, registration);
    }

    UnifiedPipelineDefinition prepare(Path root,
                                      UnifiedPipelineDefinition definition,
                                      Map<String, Object> input,
                                      Map<String, Object> runtime,
                                      JsonNode registration,
                                      boolean allowProjectMutation) throws Exception {
        UnifiedPipelineDefinition prepared = mapper.convertValue(
                mapper.valueToTree(definition), UnifiedPipelineDefinition.class);

        Map<String, Object> registeredOptions = nodeObject(
                registration == null ? null : registration.get("options"));
        Map<String, Object> registeredProcessor = nodeObject(
                registration == null ? null : registration.get("processor"));

        // Project registrations are defaults around the portable definition. Merge them
        // before request-scoped values so an explicit run input remains authoritative.
        Map<String, String> bindings = new LinkedHashMap<>();
        mergeStringMap(bindings, registeredOptions.get("modelBindings"));
        mergeStringMap(bindings, registeredProcessor.get("modelBindings"));
        mergeStringMap(bindings, prepared.getModelBindings());
        mergeStringMap(bindings, input.get("modelBindings"));
        if (!bindings.isEmpty()) prepared.setModelBindings(bindings);

        Map<String, Map<String, Object>> modelDefinitions = new LinkedHashMap<>();
        mergeModelDefinitions(modelDefinitions, registeredOptions.get("modelDefinitions"));
        mergeModelDefinitions(modelDefinitions, registeredProcessor.get("modelDefinitions"));
        mergeModelDefinitions(modelDefinitions, prepared.getModelDefinitions());
        mergeModelDefinitions(modelDefinitions, input.get("modelDefinitions"));
        if (!modelDefinitions.isEmpty()) prepared.setModelDefinitions(modelDefinitions);

        String modelSetId = first(
                stringValue(input.get("modelSetId")),
                prepared.getModelSetId(),
                stringValue(registeredOptions.get("modelSetId")),
                stringValue(registeredProcessor.get("modelSetId")));
        if (modelSetId != null) prepared.setModelSetId(modelSetId);

        hydrateModelDefinitions(prepared);
        Map<String, Object> options = new LinkedHashMap<>(registeredOptions);
        Map<String, Object> processor = new LinkedHashMap<>(registeredProcessor);
        if (prepared.getModelSetId() != null) options.put("modelSetId", prepared.getModelSetId());
        if (!bindings.isEmpty()) options.put("modelBindings", bindings);
        if (!modelDefinitions.isEmpty()) options.put("modelDefinitions", modelDefinitions);
        if (input.get("modelRefs") != null) processor.put("modelRefs", input.get("modelRefs"));
        if (input.get("modelId") != null) options.put("modelId", input.get("modelId"));
        if (input.get("vlmModel") != null) options.put("vlmModel", input.get("vlmModel"));
        if (!runtime.isEmpty()) processor.put("modelRuntime", runtime);
        LocalCrawlCapabilities.ResolvedPipeline synthetic =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        prepared.getPipelineId(), String.valueOf(prepared.getKind()),
                        "auto", "no-op", 0, 0, options, processor);
        LocalModelPipelineRunner.ResolvedModelContext models =
                LocalModelPipelineRunner.resolveBoundModels(
                        root, synthetic, prepared, allowProjectMutation);
        if (!models.bindings().isEmpty()) {
            prepared.setModelBindings(models.bindings());
            prepared.setResolvedModels(models.resolvedModels());
        }
        return prepared;
    }

    private Map<String, Object> nodeObject(JsonNode node) {
        if (node == null || !node.isObject()) return new LinkedHashMap<>();
        return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private static void mergeStringMap(Map<String, String> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String role = stringValue(entry.getKey());
            String model = stringValue(entry.getValue());
            if (role == null || model == null) {
                throw new IllegalArgumentException(
                        "modelBindings must map non-empty roles to non-empty model ids.");
            }
            target.put(role, model);
        }
    }

    private static void mergeModelDefinitions(
            Map<String, Map<String, Object>> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> definition)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            definition.forEach((key, value) -> {
                if (key != null && value != null) normalized.put(String.valueOf(key), value);
            });
            target.put(String.valueOf(entry.getKey()), normalized);
        }
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private void hydrateModelDefinitions(UnifiedPipelineDefinition definition) {
        if (definition == null) {
            return;
        }
        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        if (definition.getModelDefinitions() != null) {
            definitions.putAll(definition.getModelDefinitions());
        }
        Set<String> references = new java.util.LinkedHashSet<>();
        if (definition.getModelSetId() != null && !definition.getModelSetId().isBlank()) {
            references.add(definition.getModelSetId());
        }
        if (definition.getModelBindings() != null) {
            definition.getModelBindings().values().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(references::add);
        }
        VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
        for (String reference : references) {
            registry.getModelSet(reference)
                    .map(VlmCustomModelSet::toDefinitionMap)
                    .ifPresent(value -> definitions.putIfAbsent(reference, value));
        }
        if (!definitions.isEmpty()) {
            definition.setModelDefinitions(definitions);
        }
    }

    private Map<String, Object> status(String runId) {
        RunState state = ACTIVE_RUNS.get(runId);
        if (state == null) return Map.of("runId", runId, "status", "NOT_FOUND");
        return state.snapshot();
    }

    private Map<String, Object> cancel(String runId) {
        RunState state = ACTIVE_RUNS.get(runId);
        if (state == null || Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state.status)) {
            return Map.of("runId", runId, "cancelled", false);
        }
        state.cancelRequested.set(true);
        state.status = "CANCELLING";
        PipelineRuntimeSupervisor.RunningExecution execution = state.execution;
        boolean cancelled = execution == null || execution.cancel(Duration.ofSeconds(5));
        if (cancelled) state.status = "CANCELLED";
        return Map.of("runId", runId, "cancelled", cancelled,
                "terminationConfirmed", cancelled, "status", state.status);
    }

    private UnifiedPipelineDefinition definition(JsonNode params) {
        JsonNode value = params.get("definition");
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("definition is required");
        }
        return mapper.convertValue(value, UnifiedPipelineDefinition.class);
    }

    private Map<String, Object> object(JsonNode params, String field) {
        JsonNode value = params.get(field);
        if (value == null || value.isNull()) return Map.of();
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private Duration timeout(JsonNode params) {
        return Duration.ofMinutes(Math.max(1L, params.path("timeoutMinutes").asLong(30L)));
    }

    private String requiredId(JsonNode params) {
        return requiredText(params, "pipelineId");
    }

    private String requiredText(JsonNode params, String field) {
        String value = params.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private long requiredLong(JsonNode params, String field) {
        if (!params.hasNonNull(field) || !params.get(field).canConvertToLong()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return params.get(field).asLong();
    }

    private Long optionalLong(JsonNode params, String field) {
        return params.hasNonNull(field) ? params.get(field).asLong() : null;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static Map<String, Object> diagnostic(Throwable failure, String fallbackStage) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PipelineActionFailure actionFailure) {
                return actionFailure.diagnostic();
            }
            if (current.getCause() == null || current.getCause() == current) break;
            current = current.getCause();
        }
        return PipelineRuntimeSupervisor.diagnostic(failure, fallbackStage);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record ExecutableDefinition(UnifiedPipelineDefinition definition, JsonNode registration) {
    }

    private static final class PipelineActionFailure extends Exception {
        private final Map<String, Object> diagnostic;

        private PipelineActionFailure(Map<String, Object> diagnostic, Throwable cause) {
            super(String.valueOf(diagnostic.getOrDefault("summary", rootMessage(cause))), cause);
            this.diagnostic = Map.copyOf(diagnostic);
        }

        private Map<String, Object> diagnostic() {
            return diagnostic;
        }
    }

    private static final class RunState {
        private final String runId;
        private final String pipelineId;
        private final long version;
        private volatile String status = "QUEUED";
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile Map<String, Object> output;
        private volatile String error;
        private volatile Map<String, Object> diagnostic;
        private volatile CompletableFuture<Void> future;
        private volatile PipelineRuntimeSupervisor.RunningExecution execution;

        private RunState(String runId, String pipelineId, long version) {
            this.runId = runId;
            this.pipelineId = pipelineId;
            this.version = version;
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("pipelineId", pipelineId);
            result.put("version", version);
            result.put("status", status);
            result.put("terminal", Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status));
            if (output != null) result.put("output", output);
            if (error != null) result.put("error", error);
            if (diagnostic != null) result.put("diagnostic", diagnostic);
            return Map.copyOf(result);
        }
    }
}
