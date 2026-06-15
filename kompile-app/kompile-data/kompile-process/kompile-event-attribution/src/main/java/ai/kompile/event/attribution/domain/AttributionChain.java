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
 * A complete causal chain from a root cause to a target event.
 * Contains an ordered sequence of {@link CausalHop}s, an overall confidence score,
 * and an LLM-generated narrative explanation.
 *
 * <p>Multiple {@code AttributionChain}s can be returned for a single "why?" query,
 * representing alternative or complementary explanations ranked by confidence.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttributionChain {

    /**
     * Unique identifier for this chain.
     */
    private String chainId;

    /**
     * Node ID of the target event being explained ("why did THIS happen?").
     */
    private String targetEventNodeId;

    /**
     * Title of the target event.
     */
    private String targetEventTitle;

    /**
     * Node ID of the identified root cause (first node in the chain).
     */
    private String rootCauseNodeId;

    /**
     * Title of the root cause.
     */
    private String rootCauseTitle;

    /**
     * Ordered list of hops from root cause to target event.
     */
    @Builder.Default
    private List<CausalHop> hops = new ArrayList<>();

    /**
     * Overall confidence for this chain, computed as the product of individual
     * hop strengths (attenuated chain confidence).
     */
    private double overallConfidence;

    /**
     * Qualitative confidence band.
     */
    private AttributionConfidence confidenceBand;

    /**
     * LLM-generated narrative explanation of the entire chain.
     * This is a human-readable story connecting root cause to target event.
     */
    private String narrative;

    /**
     * When this chain was computed.
     */
    private Instant computedAt;

    /**
     * Number of hops from root cause to target.
     */
    public int getDepth() {
        return hops.size();
    }

    /**
     * All unique evidence types used across the chain.
     */
    public List<EvidenceType> getEvidenceTypes() {
        return hops.stream()
                .flatMap(h -> h.getEvidence().stream())
                .map(AttributionEvidence::getEvidenceType)
                .distinct()
                .toList();
    }
}
