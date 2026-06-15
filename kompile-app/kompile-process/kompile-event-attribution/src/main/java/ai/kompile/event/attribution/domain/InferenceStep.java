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

import java.util.List;

/**
 * Captures a single step in the variable elimination inference trace.
 * Used to answer "why is P(node_42) = 0.82?" by showing which factors
 * contributed and how each elimination changed the distribution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceStep {
    /** The variable being eliminated (or "RESULT" for the final normalization) */
    private String eliminatedVariable;

    /** Human-readable title of the eliminated variable's KG node */
    private String eliminatedTitle;

    /** Number of factors that mentioned this variable */
    private int factorsInvolved;

    /** Variable names in those factors */
    private List<String> factorVariables;

    /** The operation performed: REDUCE, MULTIPLY, MARGINALIZE, NORMALIZE */
    private String operation;

    /** Prior distribution before this step (for the query variable) */
    private Double priorValue;

    /** Posterior distribution after this step (for the query variable) */
    private Double posteriorValue;

    /** Contribution weight: how much this elimination shifted the posterior */
    private Double contributionWeight;
}
