/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.CliProcessLauncher;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalProjectModelAcquisition;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folder-local model lifecycle surface for stdio MCP agents.
 */
public final class ModelRuntimeTool implements CliTool {
    private static final Set<String> ACTIONS = Set.of("status", "bootstrap", "import", "convert", "optimize");
    private static final String[] OPTION_FIELDS = {
            "forceBootstrap", "localPath", "source", "repository",
            "revision", "format", "type",
            "onnxImporterExecutable", "onnxImporterJar",
            "modelExecutable", "modelJar", "outputPath", "profile", "maxIterations",
            "quantizationType", "weightDtype", "force", "createBackup", "dryRun", "selectedPasses",
            "servingExecutable", "servingJar", "javaExecutable", "heapSize",
            "timeoutMinutes"
    };

    private final ObjectMapper mapper;

    public ModelRuntimeTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "model_runtime";
    }

    @Override
    public String description() {
        return "Inspect, bootstrap, import, convert, or optimize a model in the current folder's Kompile project. "
                + "bootstrap is the configured remote acquisition/download operation; import forces provisioning "
                + "from localPath or a configured remote source. Folder-local acquisition is executed directly "
                + "from a pinned managed component manifest and does not invoke the scale-out staging service. "
                + "Acquired artifacts are stored under the folder's data/models tree for automatic use by "
                + "MCP-owned reusable pipeline runtimes; an existing localPath can be consumed in place. "
                + "convert invokes the standalone kompile-model convert command for local ONNX, TensorFlow/Keras, "
                + "GGUF/GGML, or SafeTensors files; optimize invokes its provider-neutral GraphOptimizer "
                + "with configurable passes/profiles for local or catalog SameDiff artifacts. No application server "
                + "or caller-managed process is required.";
    }

    @Override
    public String compactHint() {
        return "Folder model lifecycle: status=inventory, bootstrap=remote acquisition/download, import=forced provisioning, "
                + "convert=local format conversion, optimize=explicit GraphOptimizer execution through kompile-model; "
                + "bind returned artifacts/model ids in pipeline definitions.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("default", "status");
        ArrayNode actions = action.putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);

        properties.putObject("modelId").put("type", "string")
                .put("description", "Manifest, catalog, or registry model id; omitted selects the project's LLM default.");
        properties.putObject("forceBootstrap").put("type", "boolean").put("default", false);
        properties.putObject("localPath").put("type", "string")
                .put("description", "Existing local model file or directory. With import, this is consumed as the "
                        + "local artifact and does not require a download; pipeline modelDefinitions can use it directly "
                        + "for a local-only run.");
        properties.putObject("source").put("type", "string")
                .put("description", "Configured remote source used by bootstrap or import acquisition.");
        properties.putObject("repository").put("type", "string");
        properties.putObject("revision").put("type", "string");
        properties.putObject("format").put("type", "string");
        properties.putObject("outputPath").put("type", "string")
                .put("description", "Destination artifact path for action=convert or optimize; optimize may omit it to update in place.");
        properties.putObject("profile").put("type", "string").put("default", "BASIC")
                .put("description", "Optimizer profile used when selectedPasses is omitted: FULL, BASIC, TRANSFORMER, GPU, or NONE.");
        ObjectNode selectedPasses = properties.putObject("selectedPasses");
        selectedPasses.put("type", "array");
        selectedPasses.set("items", mapper.createObjectNode().put("type", "string"));
        selectedPasses.put("description", "Explicit GraphOptimizer pass ids; overrides profile when supplied.");
        properties.putObject("maxIterations").put("type", "integer").put("default", 3);
        properties.putObject("quantizationType").put("type", "string");
        properties.putObject("weightDtype").put("type", "string")
                .put("description", "GGUF conversion weight storage dtype for action=convert on gguf/ggml inputs: "
                        + "fp32, fp16, bf16, fp8, fp8_e5m2, int8, int4. Default fp16 dense; "
                        + "int4/int8 keep GGUF-packed weights for runtime-quantized matmul.");
        properties.putObject("force").put("type", "boolean").put("default", false);
        properties.putObject("createBackup").put("type", "boolean").put("default", true);
        properties.putObject("dryRun").put("type", "boolean").put("default", false);
        properties.putObject("type").put("type", "string")
                .put("description", "Registry model type such as llm_ggml, encoder, or vlm_pipeline.");

        properties.putObject("onnxImporterExecutable").put("type", "string")
                .put("description", "Standalone native ONNX-to-SameDiff importer override.");
        properties.putObject("onnxImporterJar").put("type", "string")
                .put("description", "Standalone ONNX-to-SameDiff importer CLI JAR override for JVM mode.");
        properties.putObject("modelExecutable").put("type", "string")
                .put("description", "Optional standalone native kompile-model CLI binary used by action=convert or optimize.");
        properties.putObject("modelJar").put("type", "string")
                .put("description", "Optional packaged executable kompile-model JAR used by action=convert or optimize in the JAR distribution/JVM mode.");
        properties.putObject("servingExecutable").put("type", "string")
                .put("description", "Optional standalone native kompile-model-serving binary used by crawls.");
        properties.putObject("servingJar").put("type", "string")
                .put("description", "Optional packaged executable model-serving JAR for the JAR distribution/JVM mode; rejected by a native parent.");
        properties.putObject("javaExecutable").put("type", "string")
                .put("description", "Optional Java runtime for executable-JAR tiers; native tiers ignore it.");
        properties.putObject("heapSize").put("type", "string");
        properties.putObject("timeoutMinutes").put("type", "integer").put("default", 60);
        properties.putObject("environment").put("type", "object")
                .put("description", "Environment overrides applied only to the request-scoped child process.");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "model_runtime";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.NETWORK;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Manage the current folder's model runtime");
        String action = params.path("action").asText("status").trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return ToolResult.error("Unknown action '" + action + "'. Valid actions: " + ACTIONS);
        }

        Path projectRoot = context.getWorkingDirectory().toAbsolutePath().normalize();
        try {
            ObjectNode response = mapper.createObjectNode();
            response.put("action", action);
            response.put("projectRoot", projectRoot.toString());
            response.put("storage", projectRoot.resolve("data/models").toString());
            boolean nativeChildren = CliProcessLauncher.requiresNativeChildren();
            response.putObject("runtimeContract")
                    .put("mode", nativeChildren ? "native-image" : "jar-or-native")
                    .put("preferred", nativeChildren ? "native executable" : "native executable or packaged executable JAR")
                    .put("fallback", nativeChildren
                            ? "disabled: JAR children are incompatible with a native-image parent"
                            : "native executable or packaged executable JAR")
                    .put("developmentClasspath", false)
                    .put("centralizedService", false)
                    .put("lifecycle", "request-scoped subprocess with guaranteed teardown");

            response.putObject("pipelineRuntime")
                    .put("managedBy", "stdio-mcp")
                    .put("startup", "on-demand")
                    .put("reuse", "bounded pooled sessions")
                    .put("callerConfigurationRequired", false);
            response.putObject("readinessSemantics")
                    .put("artifactReady", "A supported local artifact was found.")
                    .put("runtimeStatus", "NOT_PROBED until pipeline initialization is attempted.")
                    .put("ready", "Legacy alias for artifactReady; not a runtime-health guarantee.")
                    .put("diagnostics", "pipeline test/run reports failureStage, exception chain, and stack trace.");

            if ("status".equals(action)) {
                response.set("models", mapper.valueToTree(LocalProjectModelBootstrap.inventory(projectRoot)));
                return ToolResult.success("model_runtime status", response.toPrettyString(),
                        Map.of("action", action, "projectRoot", projectRoot.toString()));
            }

            Map<String, Object> options = runtimeOptions(params);
            if ("convert".equals(action)) {
                String inputPath = text(params, "localPath");
                String outputPath = text(params, "outputPath");
                String modelId = text(params, "modelId");
                if (inputPath == null || outputPath == null) {
                    return ToolResult.error("action=convert requires localPath (input) and outputPath (.sdz destination)");
                }
                if (modelId != null) {
                    options.put("modelId", modelId);
                }
                Map<String, Object> conversion = LocalProjectModelBootstrap.convert(
                        projectRoot,
                        projectRoot.resolve(inputPath).normalize(),
                        projectRoot.resolve(outputPath).normalize(),
                        text(params, "format"),
                        options);
                response.set("conversion", mapper.valueToTree(conversion));
                if (modelId != null && !params.path("dryRun").asBoolean(false)) {
                    response.set("registeredModel", mapper.valueToTree(
                            LocalProjectModelAcquisition.registerConverted(
                                    projectRoot, modelId,
                                    projectRoot.resolve(outputPath).normalize())));
                }
                return ToolResult.success("model_runtime convert", response.toPrettyString(),
                        Map.of("action", action, "projectRoot", projectRoot.toString(),
                                "inputPath", inputPath, "outputPath", outputPath,
                                "modelId", modelId == null ? "" : modelId));
            }
            if ("optimize".equals(action)) {
                String inputPath = text(params, "localPath");
                String outputPath = text(params, "outputPath");
                String modelId = text(params, "modelId");
                if (inputPath == null && modelId == null) {
                    return ToolResult.error("action=optimize requires localPath or modelId");
                }
                Map<String, Object> optimization = LocalProjectModelBootstrap.optimize(
                        projectRoot,
                        inputPath == null ? null : projectRoot.resolve(inputPath).normalize(),
                        outputPath == null ? null : projectRoot.resolve(outputPath).normalize(),
                        modelId,
                        options);
                response.set("optimization", mapper.valueToTree(optimization));
                return ToolResult.success("model_runtime optimize", response.toPrettyString(),
                        Map.of("action", action, "projectRoot", projectRoot.toString(),
                                "inputPath", inputPath == null ? "" : inputPath,
                                "outputPath", outputPath == null ? "" : outputPath));
            }
            boolean forceAcquisition = params.path("forceBootstrap").asBoolean(false);
            if ("import".equals(action)) {
                if (firstNonBlank(params, "localPath", "source", "repository") == null) {
                    return ToolResult.error("action=import requires localPath, source, or repository");
                }
                forceAcquisition = true;
            }

            String modelId = text(params, "modelId");
            LocalProjectModelAcquisition.Result resolved = LocalProjectModelAcquisition.acquire(
                    projectRoot, modelId, options, forceAcquisition,
                    params.path("dryRun").asBoolean(false));
            ObjectNode model = response.putObject("model");
            model.put("modelId", resolved.modelId());
            model.put("modelPath", resolved.modelPath().toString());
            if (resolved.tokenizerPath() != null) {
                model.put("tokenizerPath", resolved.tokenizerPath().toString());
            }
            model.put("downloaded", resolved.downloaded());
            model.put("dryRun", resolved.dryRun());
            model.put("disposition", resolved.disposition());
            if (resolved.definition() != null) {
                model.put("repository", resolved.definition().repository());
                model.put("revision", resolved.definition().revision());
                model.put("format", resolved.definition().format());
            }
            response.set("models", mapper.valueToTree(LocalProjectModelBootstrap.inventory(projectRoot)));

            return ToolResult.success("model_runtime " + action, response.toPrettyString(),
                    Map.of("action", action, "projectRoot", projectRoot.toString(),
                            "modelId", resolved.modelId()));
        } catch (Exception e) {
            return ToolResult.error("Folder-local model " + action + " failed: " + message(e));
        }
    }

    private Map<String, Object> runtimeOptions(JsonNode params) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String field : OPTION_FIELDS) {
            JsonNode value = params.get(field);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            if (value.isBoolean()) {
                options.put(field, value.booleanValue());
            } else if (value.isIntegralNumber()) {
                options.put(field, value.longValue());
            } else if (value.isArray()) {
                options.put(field, mapper.convertValue(value, new TypeReference<java.util.List<String>>() { }));
            } else {
                options.put(field, value.asText());
            }
        }
        JsonNode environment = params.get("environment");
        if (environment != null && environment.isObject()) {
            options.put("environment", mapper.convertValue(
                    environment, new TypeReference<Map<String, Object>>() { }));
        }
        return options;
    }

    private String firstNonBlank(JsonNode params, String... fields) {
        for (String field : fields) {
            String value = text(params, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode params, String field) {
        JsonNode value = params.get(field);
        if (value == null || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName() : value;
    }
}
