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

/**
 * Qualitative confidence bands for attribution results.
 * Each band maps to a numeric threshold range.
 */
public enum AttributionConfidence {

    /**
     * Deterministic provenance — the causal link is a factual record
     * (e.g. W3C PROV lineage, explicit "causes" edge in source text).
     * Score range: [0.9, 1.0]
     */
    DEFINITIVE(0.9),

    /**
     * High confidence — multiple independent evidence paths converge,
     * or the LLM assigns high plausibility with corroborating graph structure.
     * Score range: [0.7, 0.9)
     */
    HIGH(0.7),

    /**
     * Moderate confidence — some structural or temporal evidence exists,
     * but the link is partly inferred.
     * Score range: [0.4, 0.7)
     */
    MODERATE(0.4),

    /**
     * Low confidence — speculative inference based on weak correlation,
     * embedding similarity, or single-source evidence.
     * Score range: [0.1, 0.4)
     */
    LOW(0.1),

    /**
     * Insufficient evidence — the system could not establish a meaningful link.
     * Score range: [0.0, 0.1)
     */
    INSUFFICIENT(0.0);

    private final double threshold;

    AttributionConfidence(double threshold) {
        this.threshold = threshold;
    }

    public double getThreshold() {
        return threshold;
    }

    /**
     * Map a numeric score to a qualitative confidence band.
     */
    public static AttributionConfidence fromScore(double score) {
        if (score >= DEFINITIVE.threshold) return DEFINITIVE;
        if (score >= HIGH.threshold) return HIGH;
        if (score >= MODERATE.threshold) return MODERATE;
        if (score >= LOW.threshold) return LOW;
        return INSUFFICIENT;
    }
}
