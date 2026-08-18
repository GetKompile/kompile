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
import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
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
                + "All definitions use UnifiedPipelineDefinition and all execution uses MCP-owned "
                + "reusable stdio runtimes; callers never configure processes or executable paths.";
    }

    @Override
    public String compactHint() {
        return "action=capabilities|list|get|versions|create|update|validate|test|diff|promote|rollback|run|status|cancel|delete";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode actions = properties.putObject("action").put("type", "string").putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);
        properties.putObject("pipelineId").put("type", "string");
        properties.putObject("version").put("type", "integer");
        properties.putObject("fromVersion").put("type", "integer");
        properties.putObject("toVersion").put("type", "integer");
        properties.putObject("expectedActiveVersion").put("type", "integer");
        properties.putObject("definition").put("type", "object");
        properties.putObject("input").put("type", "object");
        properties.putObject("modelRuntime").put("type", "object");
        properties.putObject("timeoutMinutes").put("type", "integer").put("default", 30);
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
        Path projectRoot = context.getWorkingDirectory().toAbsolutePath().normalize();
        PipelineDefinitionStore store = new PipelineDefinitionStore(
                projectRoot.resolve("data/pipelines"), mapper);
        String actor = first(params.path("actor").asText(null), context.getSessionId(), "mcp-agent");
        try {
            Object result = switch (action) {
                case "capabilities" -> capabilities();
                case "list" -> store.listActive();
                case "get" -> get(store, params);
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
        } catch (Exception e) {
            return ToolResult.error("Pipeline " + action + " failed: " + rootMessage(e));
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
        result.put("stepCatalog", PipelineDefinitionValidator.availableSteps());
        result.put("builtinDocumentPipeline",
                LocalCrawlCapabilities.builtinModelProcessor("VLM").get("pipelineDefinition"));
        return result;
    }

    private Object get(PipelineDefinitionStore store, JsonNode params) throws Exception {
        String id = requiredId(params);
        Long version = optionalLong(params, "version");
        return (version == null ? store.active(id) : store.version(id, version))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown pipeline " + id + (version == null ? "" : "@" + version)));
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
        UnifiedPipelineDefinition prepared = prepare(root, definition, runtime);
        Map<String, Object> output = PipelineRuntimeSupervisor.execute(prepared, input, timeout);
        return Map.of("valid", true, "output", output,
                "contentDigest", prepared.getContentDigest());
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
        UnifiedPipelineDefinition definition = (UnifiedPipelineDefinition) get(store, params);
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
                UnifiedPipelineDefinition prepared = prepare(root, definition, runtime);
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
                    state.error = rootMessage(failure);
                    state.status = "FAILED";
                }
            } finally {
                state.execution = null;
            }
        }, RUNS);
        if (params.path("wait").asBoolean(false)) state.future.join();
        return state.snapshot();
    }

    private UnifiedPipelineDefinition prepare(Path root,
                                              UnifiedPipelineDefinition definition,
                                              Map<String, Object> runtime) throws Exception {
        UnifiedPipelineDefinition prepared = mapper.convertValue(
                mapper.valueToTree(definition), UnifiedPipelineDefinition.class);
        Map<String, Object> options = new LinkedHashMap<>();
        if (prepared.getModelSetId() != null) options.put("modelSetId", prepared.getModelSetId());
        if (prepared.getModelBindings() != null) options.put("modelBindings", prepared.getModelBindings());
        Map<String, Object> processor = new LinkedHashMap<>();
        if (!runtime.isEmpty()) processor.put("modelRuntime", runtime);
        LocalCrawlCapabilities.ResolvedPipeline synthetic =
                new LocalCrawlCapabilities.ResolvedPipeline(
                        prepared.getPipelineId(), String.valueOf(prepared.getKind()),
                        "auto", "no-op", 0, 0, Map.copyOf(options), Map.copyOf(processor));
        LocalModelPipelineRunner.ResolvedModelContext models =
                LocalModelPipelineRunner.resolveBoundModels(root, synthetic, prepared);
        if (!models.bindings().isEmpty()) {
            prepared.setModelBindings(models.bindings());
            prepared.setResolvedModels(models.resolvedModels());
        }
        return prepared;
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
        PipelineRuntimeSupervisor.RunningExecution execution = state.execution;
        boolean cancelled = execution == null || execution.cancel(Duration.ofSeconds(5));
        if (cancelled) state.status = "CANCELLED";
        return Map.of("runId", runId, "cancelled", cancelled);
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static final class RunState {
        private final String runId;
        private final String pipelineId;
        private final long version;
        private volatile String status = "QUEUED";
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        private volatile Map<String, Object> output;
        private volatile String error;
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
            if (output != null) result.put("output", output);
            if (error != null) result.put("error", error);
            return Map.copyOf(result);
        }
    }
}
