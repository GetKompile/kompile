/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Baseline and scenario values produced by an executable quantitative plan. */
public record ScenarioResult(
        Status status,
        String targetEntityId,
        Double baselineValue,
        Double scenarioValue,
        Double delta,
        Map<String, Double> baselineValues,
        Map<String, Double> scenarioValues,
        List<String> warnings,
        ReasoningTrace trace) {

    public ScenarioResult {
        status = status == null ? Status.FAILED : status;
        baselineValues = baselineValues == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(baselineValues));
        scenarioValues = scenarioValues == null
                ? Map.of() : Map.copyOf(new LinkedHashMap<>(scenarioValues));
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public enum Status {
        COMPLETED,
        PARTIAL,
        FAILED,
        INFEASIBLE
    }
}
