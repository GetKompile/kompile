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
 * A comprehensive risk assessment for a workflow run or process definition.
 * Aggregates multiple alerts and provides overall risk scoring.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProcessRiskAssessment {

    private String assessmentId;

    /** The workflow run being assessed (null for definition-level assessment). */
    private String workflowRunId;

    /** The process definition ID. */
    private String processDefinitionId;

    /** Overall risk score (0.0 = no risk, 1.0 = critical). */
    private double overallRiskScore;

    /** Risk level derived from the score. */
    private AlertSeverity riskLevel;

    /** Generated alerts ordered by severity then confidence. */
    @Builder.Default
    private List<ProcessEventAlert> alerts = new ArrayList<>();

    /**
     * Per-step risk scores. Key = stepId, value = risk score.
     * Steps with graph node bindings get attributed risk from the KG;
     * steps without bindings get risk propagated from upstream dependencies.
     */
    @Builder.Default
    private Map<String, Double> stepRiskScores = new LinkedHashMap<>();

    /**
     * Steps identified as high-risk that should be reviewed before proceeding.
     */
    @Builder.Default
    private List<String> highRiskStepIds = new ArrayList<>();

    /**
     * Full attribution analysis per step. Key = stepId.
     * Contains causal chains, influence scores, and Bayesian posteriors
     * so that the complete reasoning is auditable.
     */
    @Builder.Default
    private Map<String, StepAttributionSummary> stepAttributionResults = new LinkedHashMap<>();

    /** Narrative summary of the overall risk (LLM-generated if available). */
    private String summary;

    /** Whether the LLM was used for any part of this assessment. */
    private boolean llmUsed;

    private Instant computedAt;
    private long computationTimeMs;
}
