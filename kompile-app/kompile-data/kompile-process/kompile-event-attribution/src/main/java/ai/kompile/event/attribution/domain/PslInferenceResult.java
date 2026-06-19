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
 * Result of a PSL (Probabilistic Soft Logic / HL-MRF) MAP inference over the knowledge graph.
 *
 * <p>The PSL counterpart of {@link BayesianInferenceResult}: instead of discrete posterior
 * probabilities it returns continuous soft-truth values in {@code [0, 1]} for the target
 * atoms, obtained by minimizing the total weighted rule-violation energy.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PslInferenceResult {

    /**
     * Inferred soft-truth values for target atoms: readable atom key (e.g.
     * {@code State(node_42)}) → truth in {@code [0, 1]}.
     */
    @Builder.Default
    private Map<String, Double> inferredTruth = new LinkedHashMap<>();

    /**
     * Observed atoms that were fixed during inference (evidence): readable atom key → value.
     */
    @Builder.Default
    private Map<String, Double> observedTruth = new LinkedHashMap<>();

    /**
     * Structural prior truth per node-state atom, for comparison with {@link #inferredTruth}.
     */
    @Builder.Default
    private Map<String, Double> priors = new LinkedHashMap<>();

    /** Map from a {@code State(...)} atom key to its knowledge-graph node id. */
    @Builder.Default
    private Map<String, String> atomToNodeId = new LinkedHashMap<>();

    /** Map from a {@code State(...)} atom key to a human-readable node title. */
    @Builder.Default
    private Map<String, String> atomToTitle = new LinkedHashMap<>();

    /** The weighted rules that defined the model (rendered form), for explainability. */
    @Builder.Default
    private List<String> rules = new ArrayList<>();

    /**
     * The most-violated ground rules at the inferred solution, each rendered with its
     * distance to satisfaction — the PSL analogue of an inference trace.
     */
    @Builder.Default
    private List<String> topViolations = new ArrayList<>();

    /**
     * Program/solver statistics: atom/target/ground-rule counts, iterations, final
     * objective (total energy), and whether the optimizer converged.
     */
    @Builder.Default
    private Map<String, Object> stats = new LinkedHashMap<>();

    /** When the inference was computed. */
    private Instant computedAt;

    /** Computation time in milliseconds. */
    private long computationTimeMs;
}
