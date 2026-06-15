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
import java.util.List;
import java.util.Map;

/**
 * Complete result of an event attribution query: all discovered causal chains,
 * a synthesized explanation, influence scores, and optional counterfactual analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionResult {

    /**
     * The query that produced this result.
     */
    private AttributionQuery query;

    /**
     * Target event node ID.
     */
    private String targetNodeId;

    /**
     * Target event title.
     */
    private String targetTitle;

    /**
     * Ranked list of causal chains, ordered by overall confidence descending.
     */
    @Builder.Default
    private List<AttributionChain> chains = new ArrayList<>();

    /**
     * Top-level LLM-synthesized narrative summarizing all chains into a
     * coherent explanation. This is the primary "answer" to the user's "why?" question.
     */
    private String synthesizedExplanation;

    /**
     * Influence scores for all nodes visited during attribution.
     * Keys are node IDs, values are influence scores (higher = more influential).
     */
    @Builder.Default
    private Map<String, Double> influenceScores = Map.of();

    /**
     * Optional counterfactual analyses.
     */
    @Builder.Default
    private List<CounterfactualResult> counterfactuals = new ArrayList<>();

    /**
     * Nodes that were visited but no causal path was found — useful for debugging.
     */
    @Builder.Default
    private List<String> deadEnds = new ArrayList<>();

    /**
     * When the attribution was computed.
     */
    private Instant computedAt;

    /**
     * Computation time in milliseconds.
     */
    private long computationTimeMs;

    /**
     * Number of graph nodes visited during traversal.
     */
    private int nodesVisited;

    /**
     * Number of graph edges examined.
     */
    private int edgesExamined;

    /**
     * Whether the LLM was used in this result.
     */
    private boolean llmUsed;
}
