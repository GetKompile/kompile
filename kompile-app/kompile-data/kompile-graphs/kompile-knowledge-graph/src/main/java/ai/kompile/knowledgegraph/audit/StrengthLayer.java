/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.audit;

/**
 * Confidence band for an {@link ai.kompile.graph.reasoning.fol.InferredFact}.
 *
 * <p>Facts are banded by confidence into four tiers. The default cutoffs are:
 * <ul>
 *   <li>ESTABLISHED: [0.85, 1.0]</li>
 *   <li>PROBABLE:    [0.50, 0.85)</li>
 *   <li>SPECULATIVE: [0.20, 0.50)</li>
 *   <li>SUPPRESSED:  [0.0,  0.20)</li>
 * </ul>
 * Ordinal ordering is from lowest (SUPPRESSED) to highest (ESTABLISHED),
 * so {@code layer.ordinal() >= minLayer.ordinal()} means "at or above minLayer".
 */
public enum StrengthLayer {
    SUPPRESSED,
    SPECULATIVE,
    PROBABLE,
    ESTABLISHED;

    /** Default confidence cutoffs (lower bound inclusive). */
    private static final double ESTABLISHED_CUTOFF  = 0.85;
    private static final double PROBABLE_CUTOFF     = 0.50;
    private static final double SPECULATIVE_CUTOFF  = 0.20;

    /** Resolve a confidence value to its band using the default cutoffs. */
    public static StrengthLayer of(double confidence) {
        if (confidence >= ESTABLISHED_CUTOFF)  return ESTABLISHED;
        if (confidence >= PROBABLE_CUTOFF)     return PROBABLE;
        if (confidence >= SPECULATIVE_CUTOFF)  return SPECULATIVE;
        return SUPPRESSED;
    }

    /** True if this layer is at or above the given minimum layer. */
    public boolean isAtLeast(StrengthLayer minLayer) {
        return this.ordinal() >= minLayer.ordinal();
    }
}
