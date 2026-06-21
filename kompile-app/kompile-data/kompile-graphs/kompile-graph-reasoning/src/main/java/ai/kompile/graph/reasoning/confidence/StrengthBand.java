/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence;

import ai.kompile.graph.reasoning.domain.AttributionConfidence;

/**
 * Unified epistemic strength band derived from Opinion.projectBand().
 * Supersedes both AttributionConfidence (5-band) and the 4-band StrengthLayer scheme.
 *
 * Migration map:
 *   AttributionConfidence.DEFINITIVE  → ESTABLISHED
 *   AttributionConfidence.HIGH        → HIGH
 *   AttributionConfidence.MODERATE    → PROBABLE
 *   AttributionConfidence.LOW         → SPECULATIVE
 *   AttributionConfidence.INSUFFICIENT → SUPPRESSED
 */
public enum StrengthBand {
    /** e>=0.85 and u<0.15 */
    ESTABLISHED,
    /** e>=0.70 and u<0.30 */
    HIGH,
    /** e>=0.40 and u<0.60 */
    PROBABLE,
    /** e>=0.10 */
    SPECULATIVE,
    /** e<0.10 */
    SUPPRESSED;

    public static StrengthBand from(Opinion opinion) {
        return opinion.projectBand();
    }

    public static StrengthBand fromScalar(double confidence) {
        // zero uncertainty: pure scalar projection
        return Opinion.fromObservedValue(confidence).projectBand();
    }

    /** Bridge back to AttributionConfidence for callers not yet migrated. */
    public AttributionConfidence toAttributionConfidence() {
        switch (this) {
            case ESTABLISHED: return AttributionConfidence.DEFINITIVE;
            case HIGH:        return AttributionConfidence.HIGH;
            case PROBABLE:    return AttributionConfidence.MODERATE;
            case SPECULATIVE: return AttributionConfidence.LOW;
            case SUPPRESSED:  return AttributionConfidence.INSUFFICIENT;
            default: throw new IllegalStateException("Unknown band: " + this);
        }
    }

    /** Bridge from AttributionConfidence. */
    public static StrengthBand fromAttributionConfidence(AttributionConfidence ac) {
        switch (ac) {
            case DEFINITIVE:    return ESTABLISHED;
            case HIGH:          return HIGH;
            case MODERATE:      return PROBABLE;
            case LOW:           return SPECULATIVE;
            case INSUFFICIENT:  return SUPPRESSED;
            default: throw new IllegalStateException("Unknown confidence: " + ac);
        }
    }
}
