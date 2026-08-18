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
import ai.kompile.modelmanager.vlm.dynamic.VlmCustomModelSet;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP CRUD and validation for provider-neutral VLM model definitions.
 *
 * <p>Definitions are stored in the shared VLM model-set registry. Pipeline requests
 * reference a definition by id; the pipeline runtime hydrates the provider, artifact,
 * component, and runtime fields immediately before model resolution.</p>
 */
public final class VlmModelDefinitionTool implements CliTool {
    private static final Set<String> ACTIONS = Set.of(
            "list", "get", "create", "update", "delete", "validate");

    private final ObjectMapper mapper;
    private final VlmPipelineRegistry registry;

    public VlmModelDefinitionTool(ObjectMapper mapper) {
        this(mapper, VlmPipelineRegistry.getInstance());
    }

    VlmModelDefinitionTool(ObjectMapper mapper, VlmPipelineRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    @Override
    public String id() {
        return "vlm_model_definition";
    }

    @Override
    public String description() {
        return "Create, update, validate, list, and remove provider-neutral VLM model definitions. "
                + "Definitions may use any provider, repository, local path, component URLs, and free-form "
                + "runtime metadata; pipelines reference them by model id. Definitions configure metadata; "
                + "use model_runtime bootstrap/import to acquire remote artifacts.";
    }

    @Override
    public String compactHint() {
        return "action=list|get|create|update|delete|validate; create/update/validate use definition; "
                + "get/update/delete use modelId; provider and runtime fields are free-form; "
                + "definition lifecycle is separate from model_runtime acquisition.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode actions = properties.putObject("action")
                .put("type", "string")
                .put("description", "Model-definition lifecycle action.")
                .putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);

        properties.putObject("modelId")
                .put("type", "string")
                .put("description", "Registry id used by a VLM pipeline modelSetId or modelBindings entry.");
        ObjectNode definition = properties.putObject("definition")
                .put("type", "object")
                .put("description", "Provider-neutral VLM definition. Provider-specific fields are preserved under metadata/runtime.");
        ObjectNode fields = definition.putObject("properties");
        fields.putObject("setId").put("type", "string");
        fields.putObject("displayName").put("type", "string");
        fields.putObject("description").put("type", "string");
        fields.putObject("provider").put("type", "string")
                .put("description", "Provider adapter id; not restricted to a built-in provider.");
        fields.putObject("source").put("type", "string")
                .put("description", "Legacy source enum: HUGGINGFACE, LOCAL, or CUSTOM_URL.");
        fields.putObject("repository").put("type", "string");
        fields.putObject("revision").put("type", "string");
        fields.putObject("format").put("type", "string");
        fields.putObject("modelType").put("type", "string")
                .put("description", "Runtime registry type, for example a provider-specific VLM type.");
        fields.putObject("huggingFaceRepo").put("type", "string");
        fields.putObject("localPath").put("type", "string")
                .put("description", "Existing local model file or directory for local-only pipeline execution; "
                        + "does not itself download or stage an artifact.");
        ObjectNode components = fields.putObject("components");
        components.put("type", "array");
        ObjectNode componentItems = components.putObject("items");
        componentItems.put("type", "object");
        ObjectNode componentFields = componentItems.putObject("properties");
        componentFields.putObject("componentKey").put("type", "string");
        componentFields.putObject("fileName").put("type", "string");
        componentFields.putObject("downloadUrl").put("type", "string");
        componentFields.putObject("checksum").put("type", "string")
                .put("description", "Optional SHA-256 checksum for the component artifact.");
        componentFields.putObject("pipelineStage").put("type", "string");
        componentFields.putObject("description").put("type", "string");
        componentFields.putObject("inputShape").put("type", "string");
        componentFields.putObject("outputShape").put("type", "string");
        componentFields.putObject("estimatedSizeBytes").put("type", "integer").put("default", 0);
        componentItems.put("additionalProperties", true);

        fields.putObject("pipelineConfig").put("type", "object")
                .put("additionalProperties", true)
                .put("description", "Provider/pipeline-specific configuration passed through unchanged.");
        fields.putObject("runtime").put("type", "object")
                .put("additionalProperties", true)
                .put("description", "Runtime parameters and provider overrides; sane runtime defaults are applied when omitted.");
        fields.putObject("metadata").put("type", "object")
                .put("additionalProperties", true)
                .put("description", "Arbitrary provider metadata preserved with the definition.");
        definition.put("additionalProperties", true);

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "vlm_model_definition";
    }

    @Override
    public McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Create and configure VLM model definitions");
        String action = params.path("action").asText("").trim().toLowerCase();
        if (!ACTIONS.contains(action)) {
            return ToolResult.error("Unknown VLM model-definition action: " + action);
        }

        try {
            Object result = switch (action) {
                case "list" -> registry.getAllModelSets();
                case "get" -> get(requiredModelId(params));
                case "create" -> create(definition(params));
                case "update" -> update(requiredModelId(params), definition(params));
                case "delete" -> delete(requiredModelId(params));
                case "validate" -> validate(definition(params));
                default -> throw new IllegalStateException("Unhandled action " + action);
            };
            return ToolResult.success(
                    "vlm model definition " + action,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                    Map.of("action", action, "registry", "vlm-model-sets.json"));
        } catch (IllegalArgumentException failure) {
            return ToolResult.error(failure.getMessage());
        } catch (Exception failure) {
            return ToolResult.error("VLM model-definition " + action + " failed: " + failure.getMessage());
        }
    }

    private Object get(String modelId) {
        return registry.getModelSet(modelId)
                .orElseThrow(() -> new IllegalArgumentException("Model definition not found: " + modelId));
    }

    private Map<String, Object> create(VlmCustomModelSet modelSet) {
        List<String> validationErrors = modelSet.validate();
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", validationErrors));
        }
        String modelId = modelSet.getSetId();
        if (registry.getModelSet(modelId).isPresent()) {
            throw new IllegalArgumentException("Model definition already exists: " + modelId);
        }
        modelSet.setBuiltin(false);
        List<String> errors = registry.registerModelSet(modelSet);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return Map.of("created", true, "modelId", modelId, "definition", modelSet.toDefinitionMap());
    }

    private Map<String, Object> update(String modelId, VlmCustomModelSet modelSet) {
        modelSet.setSetId(modelId);
        modelSet.setBuiltin(false);
        List<String> errors = registry.updateModelSet(modelId, modelSet);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return Map.of("updated", true, "modelId", modelId, "definition", modelSet.toDefinitionMap());
    }

    private Map<String, Object> delete(String modelId) {
        if (!registry.deleteModelSet(modelId)) {
            throw new IllegalArgumentException("Model definition cannot be deleted (missing or builtin): " + modelId);
        }
        return Map.of("deleted", true, "modelId", modelId);
    }

    private Map<String, Object> validate(VlmCustomModelSet modelSet) {
        List<String> errors = modelSet.validate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("modelId", modelSet.getSetId());
        result.put("definition", modelSet.toDefinitionMap());
        return result;
    }

    private VlmCustomModelSet definition(JsonNode params) {
        JsonNode value = params.get("definition");
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("definition is required");
        }
        VlmCustomModelSet modelSet = mapper.convertValue(value, VlmCustomModelSet.class);
        String modelId = params.path("modelId").asText(null);
        if (modelId != null && !modelId.isBlank()) {
            modelSet.setSetId(modelId);
        }
        return modelSet;
    }

    private String requiredModelId(JsonNode params) {
        String modelId = params.path("modelId").asText(null);
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        return modelId;
    }
}
