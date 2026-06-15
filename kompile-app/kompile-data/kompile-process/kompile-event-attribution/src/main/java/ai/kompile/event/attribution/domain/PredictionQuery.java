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
 * Encapsulates parameters for an event prediction query:
 * "Given the current state of the graph, what is likely to happen next?"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionQuery {

    /**
     * Starting node ID — predict what events might follow from this node.
     */
    private String sourceNodeId;

    /**
     * Optional natural language context for the prediction.
     */
    private String naturalLanguageContext;

    /**
     * Fact sheet scope.
     */
    private Long factSheetId;

    /**
     * Maximum forward traversal depth.
     */
    @Builder.Default
    private int maxDepth = 3;

    /**
     * Maximum number of predicted events to return.
     */
    @Builder.Default
    private int maxPredictions = 10;

    /**
     * Minimum probability threshold for including a prediction.
     */
    @Builder.Default
    private double minProbability = 0.1;

    /**
     * Whether to use the LLM to rank and explain predictions.
     */
    @Builder.Default
    private boolean useLlm = true;
}
