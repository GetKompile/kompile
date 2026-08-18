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
import java.util.Map;
import java.util.ServiceLoader;

/** Structural validation and authoring catalog for the canonical pipeline contract. */
public final class PipelineDefinitionValidator {
    private static final ObjectMapper MAPPER = ObjectMappers.getJsonMapper();

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
        if (definition.getModelBindings() != null && definition.getModelDefinitions() != null) {
            definition.getModelBindings().forEach((role, reference) -> {
                if (role == null || role.isBlank() || reference == null || reference.isBlank()) {
                    errors.add("modelBindings must map non-empty roles to non-empty model ids");
                }
            });
        }
        return new Validation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
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
