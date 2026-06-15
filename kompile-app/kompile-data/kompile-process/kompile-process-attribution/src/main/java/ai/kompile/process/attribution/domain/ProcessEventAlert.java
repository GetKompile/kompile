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
import ai.kompile.event.attribution.domain.PredictedEvent;
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
 * An alert generated when causal analysis detects a potential event
 * or explains why something happened within a process context.
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ProcessEventAlert {

    private String alertId;

    /** The workflow run this alert pertains to (null for process-definition-level alerts). */
    private String workflowRunId;

    /** The process definition ID. */
    private String processDefinitionId;

    /** The step that triggered or is affected by this alert (may be null). */
    private String stepId;

    /** The KG node ID at the center of the causal analysis. */
    private String targetNodeId;

    private AlertSeverity severity;

    /** One of: ROOT_CAUSE, PREDICTION, RISK_ASSESSMENT, CONTROL_FAILURE_EXPLANATION */
    private String alertType;

    /** Short human-readable title. */
    private String title;

    /** Detailed explanation (may include LLM-generated narrative). */
    private String explanation;

    /** The causal chains that led to this alert. */
    @Builder.Default
    private List<AttributionChain> causalChains = new ArrayList<>();

    /** Predicted downstream events if this alert is a prediction. */
    @Builder.Default
    private List<PredictedEvent> predictions = new ArrayList<>();

    /** Confidence score (0.0 to 1.0). */
    private double confidence;

    /** Whether the LLM was used to produce this alert's explanation. */
    private boolean llmUsed;

    /** When this alert was created. */
    private Instant createdAt;

    /** Bayesian posterior probabilities for nodes involved in this alert. */
    @Builder.Default
    private Map<String, Double> bayesianPosteriors = new LinkedHashMap<>();

    /** Bayesian prior probabilities for nodes involved in this alert. */
    @Builder.Default
    private Map<String, Double> bayesianPriors = new LinkedHashMap<>();

    /** Whether the alert has been acknowledged by a human. */
    private boolean acknowledged;

    /** Who acknowledged it. */
    private String acknowledgedBy;

    /** When it was acknowledged. */
    private Instant acknowledgedAt;
}
