/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A typed, executable calculation rule projected from graph entities and relations.
 *
 * <p>The engine id is intentionally free-form. Host distributions provide executors; this library
 * does not select or package a numeric, tensor, spreadsheet, or optimization backend.</p>
 */
public record QuantitativeRule(
        String id,
        String sourceEntityId,
        String kind,
        String outputEntityId,
        List<Input> inputs,
        String expression,
        String engineId,
        String unit,
        Map<String, String> dimensions,
        double confidence,
        double validationScore,
        Map<String, Object> attributes) {

    public QuantitativeRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceEntityId, "sourceEntityId");
        Objects.requireNonNull(outputEntityId, "outputEntityId");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        expression = expression == null ? "" : expression.trim();
        engineId = engineId == null || engineId.isBlank() ? "expression" : engineId;
        kind = kind == null || kind.isBlank() ? "CALCULATION" : kind;
        dimensions = dimensions == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(dimensions));
        attributes = attributes == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        confidence = clamp01(confidence);
        validationScore = clamp01(validationScore);
    }

    public record Input(String alias, String entityId, boolean required) {
        public Input {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(entityId, "entityId");
        }
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
