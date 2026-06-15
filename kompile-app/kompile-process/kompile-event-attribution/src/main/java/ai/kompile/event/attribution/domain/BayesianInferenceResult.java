/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a Bayesian network inference query.
 * Contains posterior probabilities for queried variables given evidence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BayesianInferenceResult {

    /**
     * Posterior probabilities for each variable: variable name → P(variable = TRUE | evidence).
     */
    @Builder.Default
    private Map<String, Double> posteriors = new LinkedHashMap<>();

    /**
     * The evidence that was provided for this query.
     * Maps variable name → observed state index.
     */
    @Builder.Default
    private Map<String, Integer> evidence = new LinkedHashMap<>();

    /**
     * Network statistics at inference time.
     */
    @Builder.Default
    private Map<String, Object> networkStats = new LinkedHashMap<>();

    /**
     * Map from BN variable names back to KG node IDs.
     */
    @Builder.Default
    private Map<String, String> variableToNodeId = new LinkedHashMap<>();

    /**
     * Map from BN variable names to human-readable titles.
     */
    @Builder.Default
    private Map<String, String> variableToTitle = new LinkedHashMap<>();

    /**
     * Step-by-step inference trace showing how each factor elimination
     * contributed to the final posterior. Enables explainability.
     */
    @Builder.Default
    private List<InferenceStep> inferenceTrace = new ArrayList<>();

    /**
     * Prior probabilities before evidence was incorporated.
     * Maps variable name → P(variable = TRUE) with no evidence.
     */
    @Builder.Default
    private Map<String, Double> priors = new LinkedHashMap<>();

    /**
     * Per-variable MEBN metadata: variable name → metadata map.
     * Each entry contains: mfragName, nodeRole (RESIDENT/INPUT/CONTEXT),
     * entityType, entityId, rvName (the ungrounded random variable name).
     * Populated only when inference is done via MEBN path.
     */
    @Builder.Default
    private Map<String, Map<String, String>> variableToMebnMeta = new LinkedHashMap<>();

    /**
     * When the inference was computed.
     */
    private Instant computedAt;

    /**
     * Computation time in milliseconds.
     */
    private long computationTimeMs;
}
