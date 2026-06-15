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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a Bayesian sensitivity analysis.
 * Contains sensitivity deltas alongside baseline priors so the user can
 * compare each variable's influence relative to its prior probability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitivityResult {

    /**
     * Map of KG node ID → sensitivity delta (absolute change in query posterior
     * when this variable is observed as TRUE).
     */
    @Builder.Default
    private Map<String, Double> sensitivities = new LinkedHashMap<>();

    /**
     * Prior probabilities (P(TRUE) with no evidence) for all variables in the network.
     * Provides baseline context for interpreting sensitivity values.
     */
    @Builder.Default
    private Map<String, Double> priors = new LinkedHashMap<>();

    /**
     * Baseline posterior of the query variable under the current evidence
     * (before any sensitivity perturbation).
     */
    private double baselinePosterior;

    /**
     * Prior of the query variable (P(TRUE) with no evidence).
     */
    private double queryPrior;

    /**
     * The KG node ID of the query variable.
     */
    private String queryNodeId;

    /**
     * Time taken for the computation in milliseconds.
     */
    private long computationTimeMs;
}
