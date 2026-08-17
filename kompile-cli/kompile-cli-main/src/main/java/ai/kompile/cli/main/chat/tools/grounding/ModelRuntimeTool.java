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
import ai.kompile.cli.main.project.LocalModelPipelineRunner;
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
    private static final Set<String> ACTIONS = Set.of("status", "bootstrap", "import");
    private static final String[] OPTION_FIELDS = {
            "autoBootstrap", "forceBootstrap", "localPath", "source", "repository",
            "revision", "format", "type", "stagingExecutable", "stagingJar",
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
        return "Inspect, bootstrap, or import a model into the current folder's Kompile project. "
                + "Native Kompile runs model staging as a request-scoped native child; JVM development "
                + "may use the equivalent executable-JAR ABI. "
                + "artifacts are registered under the folder's data/models tree for later crawl serving. "
                + "Model artifact readiness is reported separately from VLM execution readiness: a downloaded "
                + "VLM model still needs a resolved document-model worker. No kompile-app-main process or centralized service is required.";
    }

    @Override
    public String compactHint() {
        return "Folder model lifecycle: action=status|bootstrap|import; status includes artifact readiness and the separate VLM worker/execution readiness; artifacts stay under data/models.";
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
        properties.putObject("autoBootstrap").put("type", "boolean").put("default", true);
        properties.putObject("forceBootstrap").put("type", "boolean").put("default", false);
        properties.putObject("localPath").put("type", "string")
                .put("description", "Local model file or directory to import through model staging.");
        properties.putObject("source").put("type", "string")
                .put("description", "Configured remote source supported by model staging.");
        properties.putObject("repository").put("type", "string");
        properties.putObject("revision").put("type", "string");
        properties.putObject("format").put("type", "string");
        properties.putObject("type").put("type", "string")
                .put("description", "Registry model type such as llm_ggml, encoder, or vlm_pipeline.");

        properties.putObject("stagingExecutable").put("type", "string")
                .put("description", "Optional standalone native kompile-model-staging binary.");
        properties.putObject("stagingJar").put("type", "string")
                .put("description", "Optional packaged executable model-staging JAR for JVM-mode development; rejected by the native CLI.");
        properties.putObject("servingExecutable").put("type", "string")
                .put("description", "Optional standalone native kompile-model-serving binary used by crawls.");
        properties.putObject("servingJar").put("type", "string")
                .put("description", "Optional packaged executable model-serving JAR for JVM-mode development; rejected by the native CLI.");
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
                    .put("mode", nativeChildren ? "native-only" : "jvm-development")
                    .put("preferred", nativeChildren ? "native executable" : "executable JAR or native executable")
                    .put("fallback", nativeChildren ? "disabled: native parent requires native children" : "packaged executable JAR")
                    .put("developmentClasspath", false)
                    .put("centralizedService", false)
                    .put("lifecycle", "request-scoped subprocess with guaranteed teardown");

            LocalModelPipelineRunner.DocumentModelWorkerStatus worker =
                    LocalModelPipelineRunner.documentModelWorkerStatus(projectRoot, params);
            ObjectNode workerStatus = response.putObject("documentModelWorker");
            workerStatus.put("available", worker.available())
                    .put("source", worker.source())
                    .put("unifiedExecutable", worker.unifiedExecutable());
            if (worker.executable() != null && !worker.executable().isBlank()) {
                workerStatus.put("executable", worker.executable());
            }
            response.put("vlmExecutionReady", worker.available());
            response.put("readinessNote", worker.available()
                    ? "VLM execution can be requested when a compatible local model artifact is ready."
                    : "Model artifacts and VLM execution are separate: install/resolve a document-model worker before using the crawl VLM/OCR compatibility adapters.");

            if ("status".equals(action)) {
                response.set("models", mapper.valueToTree(LocalProjectModelBootstrap.inventory(projectRoot)));
                return ToolResult.success("model_runtime status", response.toPrettyString(),
                        Map.of("action", action, "projectRoot", projectRoot.toString()));
            }

            Map<String, Object> options = runtimeOptions(params);
            if ("import".equals(action)) {
                if (firstNonBlank(params, "localPath", "source", "repository") == null) {
                    return ToolResult.error("action=import requires localPath, source, or repository");
                }
                options.put("forceBootstrap", true);
                options.put("autoBootstrap", true);
            }

            String modelId = text(params, "modelId");
            LocalProjectModelBootstrap.ResolvedProjectModel resolved =
                    LocalProjectModelBootstrap.ensure(projectRoot, modelId, options);
            ObjectNode model = response.putObject("model");
            model.put("modelId", resolved.modelId());
            model.put("modelPath", resolved.modelPath().toString());
            if (resolved.tokenizerPath() != null) {
                model.put("tokenizerPath", resolved.tokenizerPath().toString());
            }
            if (resolved.stagingRuntime() != null) {
                model.put("stagingRuntime", resolved.stagingRuntime().toString());
            }
            model.put("bootstrapped", resolved.bootstrapped());
            model.put("disposition", resolved.disposition());
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
