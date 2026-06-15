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

/**
 * Result of a counterfactual analysis: "What if node X had not occurred?"
 *
 * <p>Counterfactual analysis removes a node from the causal graph (Pearl's do-calculus
 * intervention) and re-evaluates whether the target event would still be reachable.
 * This helps distinguish true root causes from mere correlates.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CounterfactualResult {

    /**
     * The node that was hypothetically removed.
     */
    private String removedNodeId;

    /**
     * Title of the removed node.
     */
    private String removedNodeTitle;

    /**
     * Whether the target event is still reachable after removing the node.
     */
    private boolean targetStillReachable;

    /**
     * How many causal chains survive after removing this node.
     */
    private int survivingChainCount;

    /**
     * Change in overall attribution confidence after removal.
     * Negative means removing this node weakens the explanation.
     */
    private double confidenceDelta;

    /**
     * LLM-generated explanation of the counterfactual scenario.
     */
    private String explanation;

    /**
     * Whether this node is a "necessary cause" (removing it disconnects all chains).
     */
    public boolean isNecessaryCause() {
        return !targetStillReachable || survivingChainCount == 0;
    }
}
