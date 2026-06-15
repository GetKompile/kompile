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

import java.util.ArrayList;
import java.util.List;

/**
 * A single predicted future event: a node in the knowledge graph that is likely
 * to be activated/affected given the current state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PredictedEvent {

    /**
     * Node ID of the predicted event.
     */
    private String nodeId;

    /**
     * Title of the predicted event.
     */
    private String title;

    /**
     * Probability score (0.0 to 1.0).
     */
    private double probability;

    /**
     * Number of hops from the source to this predicted event.
     */
    private int hopsFromSource;

    /**
     * The path from source to this predicted event (node IDs).
     */
    @Builder.Default
    private List<String> pathFromSource = new ArrayList<>();

    /**
     * The causal edge types along the path.
     */
    @Builder.Default
    private List<CausalEdgeType> pathEdgeTypes = new ArrayList<>();

    /**
     * LLM-generated explanation of why this event is predicted.
     */
    private String explanation;

    /**
     * Supporting evidence for this prediction.
     */
    @Builder.Default
    private List<AttributionEvidence> evidence = new ArrayList<>();
}
