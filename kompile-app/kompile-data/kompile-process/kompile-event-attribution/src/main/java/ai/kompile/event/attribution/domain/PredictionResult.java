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

/**
 * Complete result of a prediction query: ranked list of predicted events
 * that are likely to follow from the given source state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictionResult {

    /**
     * The query that produced this result.
     */
    private PredictionQuery query;

    /**
     * Source node ID.
     */
    private String sourceNodeId;

    /**
     * Source node title.
     */
    private String sourceTitle;

    /**
     * Ranked list of predicted events.
     */
    @Builder.Default
    private List<PredictedEvent> predictions = new ArrayList<>();

    /**
     * LLM-synthesized narrative summarizing the predictions.
     */
    private String synthesizedForecast;

    /**
     * When the prediction was computed.
     */
    private Instant computedAt;

    /**
     * Computation time in milliseconds.
     */
    private long computationTimeMs;

    /**
     * Number of nodes visited during forward traversal.
     */
    private int nodesVisited;

    /**
     * Whether the LLM was used.
     */
    private boolean llmUsed;
}
