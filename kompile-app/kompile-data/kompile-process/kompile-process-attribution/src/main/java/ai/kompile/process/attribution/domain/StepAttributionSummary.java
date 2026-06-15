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

import ai.kompile.event.attribution.domain.AttributionChain;
import ai.kompile.event.attribution.domain.AttributionConfidence;
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
 * Complete attribution summary for a single process step, preserving the full
 * causal chain analysis, influence scores, and Bayesian posteriors.
 * This is the unit of interpretability — every field answers "why?" with evidence.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StepAttributionSummary {

    /** The step ID this summary pertains to. */
    private String stepId;

    /** The step name for display. */
    private String stepName;

    /** Graph node IDs that this step is bound to. */
    @Builder.Default
    private List<String> graphNodeIds = new ArrayList<>();

    /** Full causal chains explaining this step's risk or failure. */
    @Builder.Default
    private List<AttributionChain> causalChains = new ArrayList<>();

    /** Influence propagation scores: nodeId → influence on this step. */
    @Builder.Default
    private Map<String, Double> influenceScores = new LinkedHashMap<>();

    /** Bayesian posterior probabilities: nodeId → P(node=true | evidence). */
    @Builder.Default
    private Map<String, Double> bayesianPosteriors = new LinkedHashMap<>();

    /** Bayesian prior probabilities: nodeId → P(node=true) before evidence. */
    @Builder.Default
    private Map<String, Double> bayesianPriors = new LinkedHashMap<>();

    /** Overall risk score for this step (0.0 to 1.0). */
    private double riskScore;

    /** Confidence band derived from evidence strength. */
    private AttributionConfidence confidenceBand;

    /** LLM-generated narrative explaining this step's attribution. */
    private String narrative;

    /** Whether the Bayesian inference was available for this step. */
    private boolean bayesianInferenceAvailable;

    /** When this summary was computed. */
    private Instant computedAt;
}
