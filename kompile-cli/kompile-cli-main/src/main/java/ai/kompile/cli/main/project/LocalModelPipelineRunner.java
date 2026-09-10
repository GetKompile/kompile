/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.definition.PipelineDefinitionValidator;
import ai.kompile.pipeline.serving.registry.PipelineDefinitionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Resolves and executes model-backed processors for a project-local crawl.
 * Artifact-backed definitions use {@link PipelineRuntimeSupervisor}; remote chat processors stay
 * in the MCP host so provider credentials never cross a subprocess or persistence boundary.
 */
public final class LocalModelPipelineRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private LocalModelPipelineRunner() {
    }

    /**
     * Validate the executable part of any composed unified pipeline before a crawl starts.
     *
     * <p>Every model-backed pipeline requires a serialized Pipeline under {@code pipelineSpec}
     * with a concrete {@code @class}. Keeping this check beside runtime resolution ensures dry-run
     * and real local crawls enforce exactly the same contract.</p>
     */
    public static String validatePipelineDefinitions(Path projectRoot, JsonNode request) {
        if (request == null || request.isNull()) return null;
        Map<String, JsonNode> definitions = new LinkedHashMap<>();
        collectPipelineDefinitions(definitions, request.get("pipelines"));
        collectPipelineDefinitions(definitions, request.get("registeredPipelines"));
        JsonNode registry = request.get("pipelineRegistry");
        if (registry != null && registry.isObject()) {
            collectPipelineDefinitions(definitions, registry.get("definitions"));
            collectPipelineDefinitions(definitions, registry.get("defaults"));
        }

        Set<String> selected = new java.util.LinkedHashSet<>();
        String defaultId = textValue(request.get("defaultPipelineId"));
        if (defaultId != null) selected.add(defaultId);
        JsonNode documents = request.get("documents");
        if (documents != null && documents.isArray()) {
            for (JsonNode document : documents) {
                String id = textValue(document == null ? null : document.get("pipelineId"));
                if (id != null) selected.add(id);
            }
        }
        JsonNode routes = request.get("routeRules");
        if (routes != null && routes.isArray()) {
            for (JsonNode route : routes) {
                String id = textValue(route == null ? null : route.get("pipelineId"));
                if (id != null) selected.add(id);
            }
        }

        // Explicit request definitions are always checked. Registered defaults are checked when
        // the request selects them; this keeps an unrelated optional project pipeline from making
        // a normal text crawl fail validation.
        Set<JsonNode> toCheck = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        addDefinitionNodes(toCheck, request.get("pipelines"));
        addDefinitionNodes(toCheck, request.get("registeredPipelines"));
        if (registry != null && registry.isObject()) {
            addDefinitionNodes(toCheck, registry.get("definitions"));
        }
        for (String id : selected) {
            JsonNode selectedDefinition = definitions.get(id);
            if (selectedDefinition != null) toCheck.add(selectedDefinition);
        }
        for (JsonNode definition : toCheck) {
            String error = validatePipelineDefinition(projectRoot, definition, definitions, new java.util.HashSet<>());
            if (error != null) return error;
        }
        return null;
    }

    private static void collectPipelineDefinitions(Map<String, JsonNode> target, JsonNode value) {
        if (value == null || !value.isArray()) return;
        for (JsonNode definition : value) {
            String id = textValue(definition == null ? null : definition.get("pipelineId"));
            if (id != null && definition != null && definition.isObject()) target.putIfAbsent(id, definition);
        }
    }

    private static void addDefinitionNodes(Set<JsonNode> target, JsonNode value) {
        if (value == null || !value.isArray()) return;
        for (JsonNode definition : value) if (definition != null && definition.isObject()) target.add(definition);
    }

    private static String validatePipelineDefinition(Path projectRoot,
                                                     JsonNode pipeline,
                                                     Map<String, JsonNode> definitions,
                                                     Set<String> visiting) {
        if (pipeline == null || !pipeline.isObject()) return null;
        JsonNode processor = pipeline.path("processor");
        String type = textValue(processor.get("type"));
        if (type == null) type = textValue(pipeline.get("type"));
        boolean hasDefinition = processor.has("pipelineDefinition")
                || processor.has("pipelineDefinitionPath") || processor.has("pipelineDefinitionId")
                || pipeline.has("pipelineDefinition") || pipeline.has("pipelineDefinitionPath")
                || pipeline.has("pipelineDefinitionId");
        if (!"UNIFIED_PIPELINE".equalsIgnoreCase(type) && !hasDefinition) return null;

        JsonNode inline = firstNode(processor, pipeline, "pipelineDefinition");
        if (inline != null && !inline.isNull()) {
            JsonNode definition = inline;
            try {
                if (inline.isTextual()) definition = MAPPER.readTree(inline.asText());
            } catch (Exception e) {
                return "Unified pipeline '" + pipelineId(pipeline) + "' has invalid pipelineDefinition JSON: " + e.getMessage();
            }
            return validateSerializedDefinition(definition, pipelineId(pipeline));
        }

        String pathValue = firstText(processor, pipeline, "pipelineDefinitionPath");
        if (pathValue != null) {
            try {
                Path path = resolvePath(projectRoot, pathValue);
                if (!Files.isRegularFile(path)) {
                    return "Unified pipeline '" + pipelineId(pipeline)
                            + "' definition path does not exist: " + path
                            + ". Every model-backed definition must provide an executable "
                            + "pipelineSpec.@class (GraphPipeline or SequencePipeline).";
                }
                return validateSerializedDefinition(MAPPER.readTree(path.toFile()), pipelineId(pipeline));
            } catch (Exception e) {
                return "Unified pipeline '" + pipelineId(pipeline) + "' definition could not be read: " + e.getMessage();
            }
        }

        String definitionId = firstText(processor, pipeline, "pipelineDefinitionId");
        if (definitionId != null) {
            if (!visiting.add(definitionId)) return "Unified pipeline definition cycle detected at '" + definitionId + "'.";
            JsonNode registered = definitions.get(definitionId);
            if (registered == null) {
                return "Unified pipeline '" + pipelineId(pipeline) + "' references unknown pipelineDefinitionId '" + definitionId + "'.";
            }
            return validatePipelineDefinition(projectRoot, registered, definitions, visiting);
        }
        if ("UNIFIED_PIPELINE".equalsIgnoreCase(type)) {
            return "Unified pipeline '" + pipelineId(pipeline)
                    + "' has no pipelineDefinition. Provide an inline UnifiedPipelineDefinition or pipelineDefinitionPath/Id.";
        }
        return null;
    }

    private static String validateSerializedDefinition(JsonNode definition, String id) {
        if (definition == null || !definition.isObject()) {
            return "Unified pipeline '" + id + "' definition must be a JSON object.";
        }
        try {
            UnifiedPipelineDefinition parsed = MAPPER.convertValue(definition, UnifiedPipelineDefinition.class);
            if (PipelineDefinitionValidator.isChatModel(parsed)) {
                var validation = PipelineDefinitionValidator.validate(parsed);
                return validation.valid() ? null : String.join("; ", validation.errors());
            }
        } catch (IllegalArgumentException e) {
            return "Invalid unified definition '" + id + "': " + e.getMessage();
        }
        JsonNode spec = definition.get("pipelineSpec");
        if (spec == null || !spec.isObject() || spec.isEmpty()) {
            return "Unified pipeline '" + id + "' has no executable pipelineSpec. "
                    + "Provide pipelineSpec.@class (GraphPipeline or SequencePipeline) with typed steps.";
        }
        String className = textValue(spec.get("@class"));
        if (className == null) {
            return "Unified pipeline '" + id + "' pipelineSpec is missing @class. "
                    + "Use a concrete serialized Pipeline such as GraphPipeline or SequencePipeline.";
        }
        return null;
    }

    private static JsonNode firstNode(JsonNode first, JsonNode second, String field) {
        JsonNode value = first == null ? null : first.get(field);
        return value != null && !value.isNull() ? value : second == null ? null : second.get(field);
    }

    private static String firstText(JsonNode first, JsonNode second, String field) {
        return textValue(firstNode(first, second, field));
    }

    private static String pipelineId(JsonNode pipeline) {
        String id = textValue(pipeline == null ? null : pipeline.get("pipelineId"));
        return id == null ? "<unnamed>" : id;
    }

    private static String textValue(JsonNode value) {
        if (value == null || !value.isValueNode()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * Runs the processor selected by a resolved pipeline.
     */
    public static String extract(Path projectRoot,
                                 Path file,
                                 LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                 String loadedText) throws Exception {
        return extract(projectRoot, file, pipeline, loadedText, null);
    }

    public static String extract(Path projectRoot,
                                 Path file,
                                 LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                 String loadedText,
                                 Consumer<Map<String, Object>> progress) throws Exception {
        Map<String, Object> processor = new LinkedHashMap<>(pipeline.processor());
        Object definition = firstObject(
                processor.get("pipelineDefinition"),
                pipeline.chunkerOptions().get("pipelineDefinition"));
        Object definitionPath = firstObject(
                processor.get("pipelineDefinitionPath"),
                pipeline.chunkerOptions().get("pipelineDefinitionPath"));
        Object definitionId = firstObject(
                processor.get("pipelineDefinitionId"),
                pipeline.chunkerOptions().get("pipelineDefinitionId"));
        String type = first(
                stringValue(processor.get("type")),
                definition != null || definitionPath != null || definitionId != null
                        ? "UNIFIED_PIPELINE" : null);

        if (ChatModelPipelineRunner.PROCESSOR_TYPE.equalsIgnoreCase(type)) {
            return ChatModelPipelineRunner.extract(
                    projectRoot, file, pipeline, loadedText, progress);
        }
        if ("UNIFIED_PIPELINE".equalsIgnoreCase(type)) {
            return runUnified(
                    projectRoot, file, pipeline, loadedText, definition, definitionPath, definitionId,
                    progress);
        }
        throw new IllegalArgumentException(
                "Pipeline '" + pipeline.pipelineId()
                        + "' must use the UNIFIED_PIPELINE or CHAT_MODEL execution contract; got: " + type);
    }

    private static String runUnified(Path projectRoot,
                                     Path file,
                                     LocalCrawlCapabilities.ResolvedPipeline pipeline,
                                     String loadedText,
                                     Object inlineDefinition,
                                     Object definitionPath,
                                     Object definitionId,
                                     Consumer<Map<String, Object>> progress) throws Exception {
        UnifiedPipelineDefinition definition;
        if (inlineDefinition != null) {
            definition = inlineDefinition instanceof String json
                    ? MAPPER.readValue(json, UnifiedPipelineDefinition.class)
                    : MAPPER.convertValue(inlineDefinition, UnifiedPipelineDefinition.class);
        } else {
            if (definitionPath != null) {
                Path path = resolvePath(projectRoot, String.valueOf(definitionPath));
                definition = MAPPER.readValue(path.toFile(), UnifiedPipelineDefinition.class);
            } else {
                definition = new PipelineDefinitionStore(projectRoot.resolve("data/pipelines"), MAPPER)
                        .active(String.valueOf(definitionId))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No active UnifiedPipelineDefinition named '" + definitionId
                                        + "' was found in the project pipeline store."));
            }
        }

        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            definition.setPipelineId(pipeline.pipelineId());
        }
        if (PipelineDefinitionValidator.isChatModel(definition)) {
            Map<String, String> bindings = new LinkedHashMap<>();
            if (definition.getModelBindings() != null) bindings.putAll(definition.getModelBindings());
            Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
            mergeModelDefinitions(definitions, pipeline.processor().get("registeredModelDefinitions"));
            if (definition.getModelDefinitions() != null) definitions.putAll(definition.getModelDefinitions());
            for (Map<String, Object> options : List.of(pipeline.chunkerOptions(), pipeline.processor())) {
                NativeChatModels.rejectInlineCredentials(options);
                for (String unsupported : List.of("modelRuntime", "resolvedModels", "modelId", "modelSetId", "vlmModel", "modelRefs",
                        "provider", "prompt", "systemPrompt", "operation", "jsonSchema")) {
                    if (options.containsKey(unsupported)) throw new IllegalArgumentException("Host unified pipelines require per-stage options/bindings, not " + unsupported);
                }
                if (options.get("modelBindings") != null) {
                    if (!(options.get("modelBindings") instanceof Map<?, ?> values)) throw new IllegalArgumentException("modelBindings must be an object");
                    for (var entry : values.entrySet()) {
                        if (!(entry.getKey() instanceof String role) || !(entry.getValue() instanceof String reference))
                            throw new IllegalArgumentException("modelBindings must contain strings");
                        bindings.put(role, reference);
                    }
                }
                Object models = options.get("modelDefinitions");
                if (models != null) {
                    if (!(models instanceof Map<?, ?> values)) throw new IllegalArgumentException("modelDefinitions must be an object");
                    for (var entry : values.entrySet()) {
                        if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Map<?, ?>))
                            throw new IllegalArgumentException("Invalid modelDefinitions entry");
                    }
                    mergeModelDefinitions(definitions, models);
                }
            }
            definition.setModelBindings(bindings);
            definition.setModelDefinitions(definitions);
            return ChatModelPipelineRunner.executeDefinition(projectRoot, definition,
                    ChatModelPipelineRunner.crawlInput(file, pipeline, definition, loadedText), progress);
        }
        if (definition.getPipelineSpec() == null || definition.getPipelineSpec().isEmpty()) {
            throw new IllegalArgumentException(
                    "Unified pipeline " + definition.getPipelineId()
                            + " has no executable pipelineSpec.");
        }

        ResolvedModelContext modelContext = resolveBoundModels(projectRoot, pipeline, definition);
        if (!modelContext.bindings().isEmpty()) {
            definition.setModelBindings(modelContext.bindings());
            definition.setResolvedModels(modelContext.resolvedModels());
            definition.setPipelineSpec(materializeModelRoles(
                    definition.getPipelineSpec(), modelContext.resolvedModels()));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filePath", file.toAbsolutePath().normalize().toString());
        input.put("path", file.toAbsolutePath().normalize().toString());
        input.put("source", file.toUri().toString());
        input.put("pipelineType", pipeline.pipelineType());
        input.put("text", loadedText == null ? "" : loadedText);
        input.put("optionsJson", MAPPER.writeValueAsString(pipeline.chunkerOptions()));
        if (!modelContext.bindings().isEmpty()) {
            input.put("modelBindings", modelContext.bindings());
            input.put("resolvedModels", modelContext.resolvedModels());
        }
        pipeline.chunkerOptions().forEach((key, value) -> {
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                input.put("option." + key, value);
            }
        });

        long timeoutMinutes = Math.max(1L,
                longValue(pipeline.chunkerOptions().get("timeoutMinutes"), 30L));
        Map<String, Object> result = PipelineRuntimeSupervisor.execute(
                definition, input, java.time.Duration.ofMinutes(timeoutMinutes),
                progress == null ? null : message -> progress.accept(message.payload()));

        String text = textualOutput(result);
        if (text == null || text.isBlank()) {
            throw new IOException(
                    "Unified pipeline " + definition.getPipelineId()
                            + " completed without a text or markdown output.");
        }
        return text;
    }

    private static Path resolvePath(Path projectRoot, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            Path base = projectRoot == null ? Path.of("").toAbsolutePath() : projectRoot;
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Replace provider-neutral modelRole/tokenizerRole parameters with the resolved local artifact
     * paths. Definitions remain portable and immutable in the registry; only the per-run copy is
     * materialized. Explicit modelUri/tokenizerPath values in a custom definition always win.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> materializeModelRoles(
            Map<String, Object> pipelineSpec,
            Map<String, Map<String, Object>> resolvedModels) {
        if (pipelineSpec == null || pipelineSpec.isEmpty()) return pipelineSpec;
        Object materialized = materializeModelRolesValue(pipelineSpec, resolvedModels);
        return materialized instanceof Map<?, ?> map
                ? (Map<String, Object>) map : pipelineSpec;
    }

    private static Object materializeModelRolesValue(
            Object value,
            Map<String, Map<String, Object>> resolvedModels) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, item) -> copy.put(String.valueOf(key),
                    materializeModelRolesValue(item, resolvedModels)));

            String stepClassName = stringValue(copy.get("@class"));
            boolean uriBackedModelConfig = stepClassName != null
                    && stepClassName.endsWith("LLMStepConfig");
            String modelRole = stringValue(copy.get("modelRole"));
            if (modelRole != null) {
                Map<String, Object> descriptor = modelDescriptor(modelRole, resolvedModels);
                Object modelPath = descriptor.get("modelPath");
                if (!copy.containsKey("modelUri") && modelPath != null) {
                    copy.put("modelUri", uriBackedModelConfig
                            ? fileUri(modelPath) : modelPath);
                }
                if (!copy.containsKey("modelPath") && modelPath != null) {
                    copy.put("modelPath", modelPath);
                }
            }

            String tokenizerRole = stringValue(copy.get("tokenizerRole"));
            if (tokenizerRole != null) {
                Map<String, Object> descriptor = modelDescriptor(tokenizerRole, resolvedModels);
                Object tokenizerPath = descriptor.get("tokenizerPath");
                if (tokenizerPath != null) {
                    copy.putIfAbsent("tokenizerPath", tokenizerPath);
                    copy.putIfAbsent("tokenizerUri", uriBackedModelConfig
                            ? fileUri(tokenizerPath) : tokenizerPath);
                }
            }
            return copy;
        }
        if (value instanceof List<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object item : values) copy.add(materializeModelRolesValue(item, resolvedModels));
            return copy;
        }
        return value;
    }

    private static String fileUri(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value);
        try {
            return Path.of(raw).toAbsolutePath().normalize().toUri().toString();
        } catch (RuntimeException ignored) {
            return raw;
        }
    }

    private static Map<String, Object> modelDescriptor(
            String role,
            Map<String, Map<String, Object>> resolvedModels) {
        Map<String, Object> descriptor = resolvedModels.get(role);
        if (descriptor == null) {
            descriptor = resolvedModels.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(role))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "Pipeline step requires model role '" + role
                            + "', but no matching model binding was supplied.");
        }
        return descriptor;
    }

    private static String pagesText(JsonNode completion) {
        if (completion == null || !completion.path("pages").isArray()) {
            return "";
        }
        List<String> pages = new ArrayList<>();
        for (JsonNode page : completion.path("pages")) {
            String text = page.path("text").asText("").strip();
            if (!text.isBlank()) {
                pages.add(text);
            }
        }
        return String.join("\n\n", pages);
    }

    private static String textualOutput(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("markdown", "text", "content", "output", "response", "llm_response")) {
                String text = textualOutput(map.get(key));
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            Object pages = map.get("pages");
            if (pages != null) {
                return pagesText(MAPPER.valueToTree(Map.of("pages", pages)));
            }
        }
        if (value instanceof List<?> values) {
            List<String> parts = new ArrayList<>();
            for (Object item : values) {
                String text = textualOutput(item);
                if (text != null && !text.isBlank()) {
                    parts.add(text);
                }
            }
            return parts.isEmpty() ? null : String.join("\n\n", parts);
        }
        return null;
    }

    public static ResolvedModelContext resolveBoundModels(
            Path projectRoot,
            LocalCrawlCapabilities.ResolvedPipeline pipeline,
            UnifiedPipelineDefinition definition) throws IOException, InterruptedException {
        return resolveBoundModels(projectRoot, pipeline, definition, false);
    }

    public static ResolvedModelContext resolveBoundModels(
            Path projectRoot,
            LocalCrawlCapabilities.ResolvedPipeline pipeline,
            UnifiedPipelineDefinition definition,
            boolean allowProjectMutation) throws IOException, InterruptedException {
        if (pipeline != null && ChatModelPipelineRunner.PROCESSOR_TYPE.equalsIgnoreCase(
                stringValue(pipeline.processor().get("type")))) {
            // Remote chat model identity and credentials belong to ChatConfig. They must never be
            // interpreted as local artifact references or sent through model_runtime staging.
            return ResolvedModelContext.empty();
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        if (definition != null) {
            mergeBindings(bindings, definition.getModelBindings());
        }
        mergeBindings(bindings, pipeline.chunkerOptions().get("modelBindings"));
        mergeBindings(bindings, pipeline.processor().get("modelBindings"));
        addProjectModelRefs(projectRoot, bindings,
                pipeline.chunkerOptions().get("modelRefs"),
                pipeline.processor().get("modelRefs"));

        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        mergeModelDefinitions(definitions, pipeline.processor().get("registeredModelDefinitions"));
        mergeModelDefinitions(definitions, pipeline.chunkerOptions().get("modelDefinitions"));
        mergeModelDefinitions(definitions, pipeline.processor().get("modelDefinitions"));
        if (definition != null) {
            mergeModelDefinitions(definitions, definition.getModelDefinitions());
        }

        Object defaultRuntime = firstObject(
                pipeline.processor().get("modelRuntime"),
                pipeline.chunkerOptions().get("modelRuntime"));
        String modelSetId = first(
                stringValue(pipeline.chunkerOptions().get("modelSetId")),
                definition == null ? null : definition.getModelSetId());
        String modelId = stringValue(pipeline.chunkerOptions().get("modelId"));
        String vlmModel = stringValue(pipeline.chunkerOptions().get("vlmModel"));
        if (modelId != null && vlmModel != null && !modelId.equals(vlmModel)) {
            throw new IllegalArgumentException(
                    "Conflicting model selectors: modelId='" + modelId
                            + "' and vlmModel='" + vlmModel
                            + "'. Use modelBindings.default for an authoritative selection.");
        }
        String legacyModelId = first(modelId, vlmModel, modelSetId);
        if (bindings.isEmpty() && legacyModelId != null) {
            bindings.put("default", legacyModelId);
        }
        if (bindings.isEmpty()) {
            if (defaultRuntime != null) {
                throw new IllegalArgumentException(
                        "modelRuntime requires a model binding, modelSetId, modelId, or vlmModel.");
            }
            return ResolvedModelContext.empty();
        }

        Map<String, Map<String, Object>> resolvedModels = new LinkedHashMap<>();
        Map<String, LocalProjectModelBootstrap.ResolvedProjectModel> resolvedByReference =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            String role = binding.getKey();
            String reference = binding.getValue();
            Map<String, Object> modelDefinition = definitions.get(reference);
            String selection = first(
                    modelDefinition == null ? null : stringValue(modelDefinition.get("modelId")),
                    modelDefinition == null ? null : stringValue(modelDefinition.get("id")),
                    reference);
            Map<String, Object> runtimeOptions = modelRuntimeOptions(defaultRuntime, modelDefinition);
            LocalProjectModelBootstrap.ResolvedProjectModel resolved = resolvedByReference.get(reference);
            if (resolved == null) {
                resolved = LocalProjectModelBootstrap.ensure(
                        projectRoot, selection, runtimeOptions, allowProjectMutation);
                resolvedByReference.put(reference, resolved);
            }

            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("binding", role);
            descriptor.put("reference", reference);
            descriptor.put("modelId", resolved.modelId());
            descriptor.put("modelPath", resolved.modelPath().toString());
            putIfNonNull(descriptor, "tokenizerPath", resolved.tokenizerPath());
            descriptor.put("disposition", resolved.disposition());
            descriptor.put("role", first(
                    modelDefinition == null ? null : stringValue(modelDefinition.get("role")), role));
            resolvedModels.put(role, Map.copyOf(descriptor));
        }
        return new ResolvedModelContext(Map.copyOf(bindings), Map.copyOf(resolvedModels));
    }

    private static void mergeBindings(Map<String, String> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String role = entry.getKey() == null ? null : String.valueOf(entry.getKey()).trim();
            String model = entry.getValue() == null ? null : String.valueOf(entry.getValue()).trim();
            if (role == null || role.isBlank() || model == null || model.isBlank()) {
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
            if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> definition)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            definition.forEach((key, value) -> {
                if (key != null && value != null) normalized.put(String.valueOf(key), value);
            });
            target.put(String.valueOf(entry.getKey()), Map.copyOf(normalized));
        }
    }

    private static void addProjectModelRefs(
            Path projectRoot,
            Map<String, String> bindings,
            Object firstRefs,
            Object secondRefs) {
        List<String> refs = new ArrayList<>(stringList(firstRefs));
        for (String ref : stringList(secondRefs)) {
            if (!refs.contains(ref)) refs.add(ref);
        }
        if (refs.isEmpty()) return;
        List<Map<String, Object>> inventory = LocalProjectModelBootstrap.inventory(projectRoot);
        for (String ref : refs) {
            if (bindings.containsValue(ref)) continue;
            Map<String, Object> model = inventory.stream()
                    .filter(item -> matchesModelReference(item, ref))
                    .findFirst().orElse(null);
            String role = model == null ? null : normalizeRole(stringValue(model.get("role")));
            if (role == null && refs.size() == 1) role = "default";
            if (role == null) {
                throw new IllegalArgumentException(
                        "Project pipeline modelRefs contains '" + ref
                                + "' without a resolvable role; use explicit modelBindings.");
            }
            String previous = bindings.putIfAbsent(role, ref);
            if (previous != null && !previous.equals(ref)) {
                throw new IllegalArgumentException(
                        "Project pipeline modelRefs assigns multiple models to role '" + role
                                + "'; use explicit modelBindings.");
            }
        }
    }

    private static boolean matchesModelReference(Map<String, Object> model, String reference) {
        return reference.equals(stringValue(model.get("id")))
                || reference.equals(stringValue(model.get("modelId")))
                || reference.equals(stringValue(model.get("registryModelId")));
    }

    private static String normalizeRole(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-");
        return normalized.isBlank() ? null : normalized;
    }

    private static Map<String, Object> modelRuntimeOptions(
            Object defaults, Map<String, Object> definition) {
        Map<String, Object> options = new LinkedHashMap<>();
        mergeObject(options, defaults);
        if (definition != null) {
            definition.forEach((key, value) -> {
                if (value != null && !Set.of("id", "modelId", "role", "runtime").contains(key)) {
                    options.put(key, value);
                }
            });
            mergeObject(options, definition.get("runtime"));
            copyAlias(options, "sourceRepository", "repository");
            copyAlias(options, "sourceRevision", "revision");
            copyAlias(options, "path", "localPath");
            copyAlias(options, "registryType", "type");
        }
        return options;
    }

    private static void mergeObject(Map<String, Object> target, Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
    }

    private static void copyAlias(Map<String, Object> values, String source, String target) {
        if (!values.containsKey(target) && values.containsKey(source)) {
            values.put(target, values.get(source));
        }
    }

    private static void putIfNonNull(Map<String, Object> target, String key, Path value) {
        if (value != null) target.put(key, value.toString());
    }

    private static long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Object firstObject(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ResolvedModelContext(
            Map<String, String> bindings,
            Map<String, Map<String, Object>> resolvedModels) {
        static ResolvedModelContext empty() {
            return new ResolvedModelContext(Map.of(), Map.of());
        }
    }
}
