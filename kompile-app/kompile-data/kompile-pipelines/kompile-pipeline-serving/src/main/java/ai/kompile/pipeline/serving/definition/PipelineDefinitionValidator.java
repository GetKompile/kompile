/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.definition;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** Structural validation and authoring catalog for the canonical pipeline contract. */
public final class PipelineDefinitionValidator {
    private static final ObjectMapper MAPPER = ObjectMappers.getJsonMapper();
    private static final Set<String> CHAT_FIELDS = Set.of(
            "type", "provider", "modelId", "modelSource", "thinking", "prompt", "systemPrompt", "outputFormat",
            "operation", "jsonSchema", "maxInputChars", "maxResponseChars", "maxImageBytes",
            "pdfRenderDpi", "pageBatchSize", "maxPages", "pageRange", "timeoutMinutes");
    private static final Set<String> CHAT_OPERATIONS = Set.of(
            "text", "graph_extraction", "image", "pdf", "json_schema");
    private static final Set<String> SECRET_FIELDS = Set.of(
            "apikey", "accesstoken", "refreshtoken", "authtoken", "authorization", "credentials",
            "password", "secret", "clientsecret", "token", "headers", "cookie", "cookies");

    private PipelineDefinitionValidator() {
    }

    public static Validation validate(UnifiedPipelineDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (definition == null) {
            errors.add("definition is required");
            return new Validation(false, errors, warnings);
        }
        if (definition.getSchemaVersion() != UnifiedPipelineDefinition.CURRENT_SCHEMA_VERSION) {
            errors.add("Unsupported schemaVersion " + definition.getSchemaVersion());
        }
        if (definition.getPipelineId() == null || definition.getPipelineId().isBlank()) {
            errors.add("pipelineId is required");
        }
        if (definition.getKind() == null) errors.add("kind is required");
        if (definition.getTopology() == null) errors.add("topology is required");
        if (ChatPipelineComposition.containsChat(definition)) {
            validateChatContracts(definition.getInputs(), Set.of("text", "filePath", "path"), "inputs", errors);
            try {
                ChatPipelineComposition.plan(definition);
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
            return new Validation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
        }
        if (definition.getProcessor() != null) {
            if (!isChatModel(definition)) {
                errors.add("Unknown processor type; only CHAT_MODEL is supported as a host processor");
            } else {
                validateChatModel(definition, errors);
            }
            return new Validation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
        }
        if (definition.getKind() == UnifiedPipelineDefinition.PipelineKind.TRANSLATION) {
            errors.add("TRANSLATION requires a standalone CHAT_MODEL processor with operation=translation");
            return new Validation(false, List.copyOf(errors), List.copyOf(warnings));
        }
        if (definition.getPipelineSpec() == null || definition.getPipelineSpec().isEmpty()) {
            errors.add("pipelineSpec is required");
        } else if (definition.getPipelineSpec().get("@class") == null) {
            errors.add("pipelineSpec.@class is required");
        } else {
            try {
                Pipeline pipeline = MAPPER.convertValue(definition.getPipelineSpec(), Pipeline.class);
                pipeline.validate();
            } catch (Exception e) {
                errors.add("pipelineSpec is invalid: " + rootMessage(e));
            }
        }
        if (definition.getModelBindings() != null) {
            definition.getModelBindings().forEach((role, reference) -> {
                if (role == null || role.isBlank() || reference == null || reference.isBlank()) {
                    errors.add("modelBindings must map non-empty roles to non-empty model ids");
                }
            });
        }
        validateLocalModelSources(definition, errors);
        return new Validation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    public static boolean isChatModel(UnifiedPipelineDefinition definition) {
        return definition != null && ((definition.getProcessor() != null
                && "CHAT_MODEL".equals(definition.getProcessor().get("type")))
                || ChatPipelineComposition.containsChat(definition));
    }

    /** Also used after project/request bindings are merged, before any local artifact is loaded. */
    public static void validateLocalModelSources(UnifiedPipelineDefinition definition, List<String> errors) {
        if (definition.getModelDefinitions() == null) return;
        Set<String> references = new java.util.LinkedHashSet<>();
        if (definition.getModelBindings() != null) references.addAll(definition.getModelBindings().values());
        if (definition.getModelSetId() != null) references.add(definition.getModelSetId());
        for (String reference : references) {
            Map<String, Object> model = definition.getModelDefinitions().get(reference);
            if (model == null) continue;
            String source = String.valueOf(model.getOrDefault("source", "")).toLowerCase(Locale.ROOT);
            if (Set.of("chat", "remote", "provider").contains(source)) {
                errors.add("Local tensor pipelines cannot use remote chat model bindings; use a standalone CHAT_MODEL stage");
            }
        }
    }

    private static void validateChatModel(UnifiedPipelineDefinition definition, List<String> errors) {
        Map<String, Object> processor = definition.getProcessor();
        boolean translation = TranslationPipelineOptions.isTranslation(processor);
        var expectedKind = translation ? UnifiedPipelineDefinition.PipelineKind.TRANSLATION
                : UnifiedPipelineDefinition.PipelineKind.LLM;
        if (definition.getKind() != expectedKind) {
            errors.add("CHAT_MODEL operation=" + processor.getOrDefault("operation", "text") + " requires kind=" + expectedKind);
        }
        if (definition.getTopology() != UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE) {
            errors.add("CHAT_MODEL requires topology=SEQUENCE");
        }
        if (definition.getPipelineSpec() != null) {
            errors.add("CHAT_MODEL is one host stage: pipelineSpec, tensor steps, and graph composition are unsupported");
        }
        Set<String> supportedFields = translation ? TranslationPipelineOptions.PROCESSOR_FIELDS : CHAT_FIELDS;
        for (String field : processor.keySet()) {
            if (field == null || !supportedFields.contains(field)) errors.add("Unsupported CHAT_MODEL processor field: " + field);
        }
        for (String field : List.of("provider", "modelId", "modelSource", "thinking", "prompt", "systemPrompt", "outputFormat", "operation")) {
            Object value = processor.get(field);
            if (value != null && (!(value instanceof String text) || text.isBlank())) {
                errors.add("CHAT_MODEL processor." + field + " must be a non-empty string");
            }
        }
        if (processor.containsKey("modelSource") && !"chat".equals(processor.get("modelSource"))) {
            errors.add("CHAT_MODEL modelSource must be chat");
        }
        if (!translation && processor.containsKey("operation") && (processor.get("operation") == null
                || !CHAT_OPERATIONS.contains(processor.get("operation")))) {
            errors.add("Unsupported CHAT_MODEL operation; chat does not support tools, embeddings, tensors, or learning");
        }
        if (processor.containsKey("jsonSchema") && !(processor.get("jsonSchema") instanceof Map<?, ?>)) {
            errors.add("CHAT_MODEL jsonSchema must be an object");
        }
        if ("json_schema".equals(processor.get("operation")) && !processor.containsKey("jsonSchema")) {
            errors.add("CHAT_MODEL operation=json_schema requires jsonSchema");
        }
        boundedInteger(processor, "maxInputChars", 1_000, 10_000_000, errors);
        boundedInteger(processor, "maxResponseChars", 1_000, 20_000_000, errors);
        boundedInteger(processor, "maxImageBytes", 65_536, 33_554_432, errors);
        boundedInteger(processor, "pdfRenderDpi", 72, 300, errors);
        boundedInteger(processor, "pageBatchSize", 1, 20, errors);
        boundedInteger(processor, "maxPages", 1, Integer.MAX_VALUE, errors);
        validatePageRange(processor, errors);
        boundedInteger(processor, "timeoutMinutes", 1, 1_440, errors);
        if (translation) {
            try {
                TranslationPipelineOptions.toRequest(processor, "", null, null, null);
            } catch (IllegalArgumentException invalid) {
                errors.add(invalid.getMessage());
            }
        }
        validateChatContracts(definition.getInputs(), translation ? Set.of("text") : Set.of("text", "filePath", "path"), "inputs", errors);
        validateChatContracts(definition.getOutputs(), Set.of("text"), "outputs", errors);
        if (definition.getModelBindings() != null) {
            if (definition.getModelBindings().size() > 1) errors.add("CHAT_MODEL accepts only one model binding");
            definition.getModelBindings().forEach((role, reference) -> {
                if (role == null || !Set.of("default", "generator").contains(role)
                        || reference == null || reference.isBlank()) {
                    errors.add("CHAT_MODEL modelBindings accepts only default or generator mapped to a non-empty model id");
                }
            });
        }
        if (definition.getModelDefinitions() != null) {
            definition.getModelDefinitions().forEach((id, model) -> {
                if (id == null || id.isBlank() || model == null
                        || !"chat".equalsIgnoreCase(String.valueOf(model.get("source")))) {
                    errors.add("CHAT_MODEL modelDefinitions must use non-empty ids and source=chat");
                    return;
                }
                for (String field : model.keySet()) {
                    if (field == null || !Set.of("id", "modelId", "provider", "source", "role", "displayName", "description").contains(field)) {
                        errors.add("Unsupported CHAT_MODEL model definition field: " + field);
                    }
                }
                for (String field : List.of("modelId", "provider")) {
                    Object value = model.get(field);
                    if (value != null && (!(value instanceof String text) || text.isBlank())) {
                        errors.add("CHAT_MODEL model definition " + field + " must be a non-empty string");
                    }
                }
                Object role = model.get("role");
                if (role != null && !Set.of("default", "generator", "llm", "chat_model")
                        .contains(String.valueOf(role).toLowerCase(Locale.ROOT))) {
                    errors.add("CHAT_MODEL model definition role is not a text generator");
                }
            });
        }
        if (definition.getResolvedModels() != null || definition.getServing() != null
                || definition.getRuntimeRequirements() != null || definition.getLlmConfig() != null
                || definition.getRagConfig() != null || definition.getExtractionTypes() != null) {
            errors.add("CHAT_MODEL does not support local artifacts, runtime requirements, or domain overlays; use processor options");
        }
        rejectSecrets(MAPPER.convertValue(definition, Map.class), errors);
    }

    private static void validateChatContracts(Map<String, UnifiedPipelineDefinition.DataContract> contracts,
                                             Set<String> supported, String field, List<String> errors) {
        if (contracts == null) return;
        contracts.forEach((name, contract) -> {
            if (name == null || !supported.contains(name) || contract == null || !"string".equals(contract.getType())
                    || (contract.getConstraints() != null && !contract.getConstraints().isEmpty())) {
                errors.add("CHAT_MODEL " + field + " supports only unconstrained string fields " + supported);
            }
        });
    }

    private static void boundedInteger(Map<String, Object> options, String key, long min, long max,
                                       List<String> errors) {
        if (!options.containsKey(key)) return;
        try {
            long value = Long.parseLong(String.valueOf(options.get(key)));
            if (value < min || value > max) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            errors.add("CHAT_MODEL " + key + " must be an integer between " + min + " and " + max);
        }
    }

    private static void validatePageRange(Map<String, Object> processor, List<String> errors) {
        if (!processor.containsKey("pageRange")) return;
        Object configured = processor.get("pageRange");
        if (!(configured instanceof String value) || value.isBlank()) {
            errors.add("CHAT_MODEL pageRange must be a non-empty string");
            return;
        }
        for (String rawSegment : value.split(",", -1)) {
            String segment = rawSegment.trim();
            String[] bounds = segment.split("-", -1);
            if (segment.isEmpty() || bounds.length > 2
                    || bounds[0].trim().isEmpty()
                    || !isPositivePageNumber(bounds[0].trim())
                    || bounds.length == 2 && !isPositivePageNumber(bounds[1].trim())) {
                errors.add("CHAT_MODEL pageRange must use positive page numbers or inclusive ranges such as 7-9,11");
                return;
            }
            if (bounds.length == 2 && Long.parseLong(bounds[0].trim()) > Long.parseLong(bounds[1].trim())) {
                errors.add("CHAT_MODEL pageRange contains a reversed range: " + segment);
                return;
            }
        }
    }

    private static boolean isPositivePageNumber(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void rejectSecrets(Object value, List<String> errors) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                String normalized = String.valueOf(key).replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
                if (SECRET_FIELDS.contains(normalized)) errors.add("Credentials must not be stored in pipeline definitions");
                // JSON schema property names describe output data, not provider configuration.
                if (!"jsonSchema".equals(key)) rejectSecrets(item, errors);
            });
        } else if (value instanceof Iterable<?> items) {
            items.forEach(item -> rejectSecrets(item, errors));
        }
    }

    public static List<Map<String, Object>> availableSteps() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PipelineStepRunnerFactory factory : ServiceLoader.load(PipelineStepRunnerFactory.class)) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("type", factory.stepTypeName());
            step.put("runnerClassName", factory.getRunnerType());
            step.put("schema", factory.getSchema());
            result.add(Map.copyOf(step));
        }
        result.sort(java.util.Comparator.comparing(value -> String.valueOf(value.get("type"))));
        return result;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    public record Validation(boolean valid, List<String> errors, List<String> warnings) {
    }
}
