/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.attribution.domain;

import ai.kompile.event.attribution.domain.AttributionResult;
import ai.kompile.event.attribution.domain.PredictionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attribution and prediction results for a single process step,
 * scoped to the step's bound graph nodes.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StepAttributionResult {

    /** The step ID within the process definition. */
    private String stepId;

    /** The step name. */
    private String stepName;

    /** Attribution result: why did this step's graph nodes reach their current state? */
    private AttributionResult attribution;

    /** Prediction result: what downstream events are likely from this step's graph nodes? */
    private PredictionResult prediction;

    /** Derived risk score for this step based on attribution + prediction analysis. */
    private double riskScore;

    /** Whether this step has graph node bindings that could be analyzed. */
    private boolean hasGraphBindings;

    /** Bayesian posterior probabilities: nodeId → P(node=true | evidence). */
    @Builder.Default
    private Map<String, Double> bayesianPosteriors = new LinkedHashMap<>();

    /** Bayesian prior probabilities: nodeId → P(node=true) before evidence. */
    @Builder.Default
    private Map<String, Double> bayesianPriors = new LinkedHashMap<>();
}
