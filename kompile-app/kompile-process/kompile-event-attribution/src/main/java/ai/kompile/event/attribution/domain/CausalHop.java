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
 * A single hop in a causal chain: one directed edge from a cause node to an effect node,
 * annotated with the causal relationship type, strength, and supporting evidence.
 *
 * <p>The chain {@code A -> B -> C} is represented as two hops:
 * {@code [CausalHop(A, B, ...), CausalHop(B, C, ...)]}</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CausalHop {

    /**
     * Node ID of the cause (upstream) node.
     */
    private String causeNodeId;

    /**
     * Display title of the cause node.
     */
    private String causeTitle;

    /**
     * Node ID of the effect (downstream) node.
     */
    private String effectNodeId;

    /**
     * Display title of the effect node.
     */
    private String effectTitle;

    /**
     * Semantic type of the causal link.
     */
    private CausalEdgeType causalType;

    /**
     * Combined causal strength for this hop (0.0 to 1.0), aggregated from evidence.
     */
    private double strength;

    /**
     * Timestamp of the cause event, if known.
     */
    private Instant causeTimestamp;

    /**
     * Timestamp of the effect event, if known.
     */
    private Instant effectTimestamp;

    /**
     * Evidence items supporting this particular hop.
     */
    @Builder.Default
    private List<AttributionEvidence> evidence = new ArrayList<>();

    /**
     * LLM-generated natural-language explanation for this specific hop.
     * Null if no LLM pass was performed.
     */
    private String llmExplanation;
}
