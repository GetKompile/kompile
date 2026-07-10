/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A structured quantitative question over a reasoning graph.
 *
 * <p>Natural-language parsing belongs at the application boundary. The reasoning library receives
 * explicit targets, interventions, dimensions, and goal constraints so ambiguous units or operations
 * are never silently guessed.</p>
 */
public record QuantitativeQuery(
        Mode mode,
        MeasureSelector target,
        List<Intervention> interventions,
        Map<String, String> dimensions,
        Instant asOf,
        double[] queryEmbedding,
        int topK,
        Goal goal) {

    public QuantitativeQuery {
        mode = mode == null ? Mode.MODELS : mode;
        interventions = interventions == null ? List.of() : List.copyOf(interventions);
        dimensions = dimensions == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(dimensions));
        queryEmbedding = queryEmbedding == null ? null : queryEmbedding.clone();
        topK = topK <= 0 ? 10 : Math.min(topK, 100);
    }

    @Override
    public double[] queryEmbedding() {
        return queryEmbedding == null ? null : queryEmbedding.clone();
    }

    public static QuantitativeQuery models(String targetText) {
        return new QuantitativeQuery(Mode.MODELS, MeasureSelector.text(targetText),
                List.of(), Map.of(), null, null, 10, null);
    }

    public static QuantitativeQuery calculate(String targetText) {
        return new QuantitativeQuery(Mode.CALCULATE, MeasureSelector.text(targetText),
                List.of(), Map.of(), null, null, 10, null);
    }

    public static QuantitativeQuery scenario(
            MeasureSelector target,
            List<Intervention> interventions,
            Map<String, String> dimensions) {
        return new QuantitativeQuery(Mode.SCENARIO, target, interventions, dimensions,
                null, null, 10, null);
    }

    public static QuantitativeQuery solveTarget(
            MeasureSelector target,
            List<Intervention> interventions,
            Map<String, String> dimensions,
            Goal goal) {
        return new QuantitativeQuery(Mode.SOLVE_TARGET, target, interventions, dimensions,
                null, null, 10, Objects.requireNonNull(goal, "goal"));
    }

    public enum Mode {
        MODELS,
        CALCULATE,
        SCENARIO,
        SOLVE_TARGET
    }

    /**
     * Identifies a measure or observation. Any combination may be supplied; exact entity ids take
     * precedence, while text, type, unit, and dimensions contribute to ranked resolution.
     */
    public record MeasureSelector(
            String entityId,
            String text,
            String type,
            String unit,
            Map<String, String> dimensions) {

        public MeasureSelector {
            dimensions = dimensions == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(dimensions));
        }

        public static MeasureSelector text(String text) {
            return new MeasureSelector(null, text, null, null, Map.of());
        }

        public static MeasureSelector entity(String entityId) {
            return new MeasureSelector(entityId, null, null, null, Map.of());
        }

        public boolean specified() {
            return !blank(entityId) || !blank(text) || !blank(type);
        }
    }

    /**
     * SCALE is a fractional change: {@code -0.16} means decrease by 16%, {@code 0.25} means
     * increase by 25%.
     */
    public record Intervention(MeasureSelector target, Operation operation, double value) {
        public Intervention {
            Objects.requireNonNull(target, "target");
            operation = operation == null ? Operation.SET : operation;
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("intervention value must be finite");
            }
        }
    }

    public enum Operation {
        SET,
        ADD,
        SCALE
    }

    /** A bounded scalar goal-seek request. */
    public record Goal(
            MeasureSelector control,
            double targetValue,
            double minimum,
            double maximum,
            double tolerance,
            int maxIterations) {

        public Goal {
            Objects.requireNonNull(control, "control");
            if (!Double.isFinite(targetValue) || !Double.isFinite(minimum)
                    || !Double.isFinite(maximum) || minimum >= maximum) {
                throw new IllegalArgumentException("goal values and ordered bounds must be finite");
            }
            tolerance = tolerance > 0.0 && Double.isFinite(tolerance) ? tolerance : 1.0e-6;
            maxIterations = maxIterations > 0 ? Math.min(maxIterations, 1000) : 100;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
