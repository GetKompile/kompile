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
 * Result of a Most Probable Explanation (MPE) query.
 * Contains the most likely variable assignments plus priors,
 * inference trace, and variable metadata for full explainability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MpeResult {

    /**
     * Most probable state assignment for each variable.
     * Maps KG node ID → most likely state index (0 = FALSE, 1 = TRUE for binary).
     */
    @Builder.Default
    private Map<String, Integer> assignments = new LinkedHashMap<>();

    /**
     * Posterior probabilities for each variable given the evidence.
     */
    @Builder.Default
    private Map<String, Double> posteriors = new LinkedHashMap<>();

    /**
     * Prior probabilities (no evidence) for comparison.
     */
    @Builder.Default
    private Map<String, Double> priors = new LinkedHashMap<>();

    /**
     * The evidence that was provided for this query.
     */
    @Builder.Default
    private Map<String, Integer> evidence = new LinkedHashMap<>();

    /**
     * Step-by-step inference trace for explainability.
     */
    @Builder.Default
    private List<InferenceStep> inferenceTrace = new ArrayList<>();

    /**
     * Map from BN variable names to KG node IDs.
     */
    @Builder.Default
    private Map<String, String> variableToNodeId = new LinkedHashMap<>();

    /**
     * Map from BN variable names to human-readable titles.
     */
    @Builder.Default
    private Map<String, String> variableToTitle = new LinkedHashMap<>();

    /**
     * Network statistics at inference time.
     */
    @Builder.Default
    private Map<String, Object> networkStats = new LinkedHashMap<>();

    /**
     * When the inference was computed.
     */
    private Instant computedAt;

    /**
     * Computation time in milliseconds.
     */
    private long computationTimeMs;
}
