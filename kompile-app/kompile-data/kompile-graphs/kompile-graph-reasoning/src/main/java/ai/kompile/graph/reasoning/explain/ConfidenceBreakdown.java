/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.explain;

/**
 * Structural + semantic confidence breakdown for a ReasoningTrail.
 * Fields default to Double.NaN when the mode did not produce them.
 */
public record ConfidenceBreakdown(
    double groundingConfidence,
    double pslSoftTruth,
    double mebnPosterior,
    double structuralScore,
    double semanticScore,
    double structuralWeight,
    double semanticWeight,
    double distanceToSatisfaction
) {
    public static ConfidenceBreakdown empty() {
        return new ConfidenceBreakdown(Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public static ConfidenceBreakdown ofGrounding(double groundingConfidence) {
        return new ConfidenceBreakdown(groundingConfidence, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public static ConfidenceBreakdown ofPsl(double pslSoftTruth, double distanceToSat) {
        return new ConfidenceBreakdown(Double.NaN, pslSoftTruth, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, distanceToSat);
    }

    public static ConfidenceBreakdown ofMebn(double mebnPosterior) {
        return new ConfidenceBreakdown(Double.NaN, Double.NaN, mebnPosterior,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public static ConfidenceBreakdown ofHybrid(double structural, double semantic, double sw, double ew) {
        return new ConfidenceBreakdown(Double.NaN, Double.NaN, Double.NaN, structural, semantic, sw, ew, Double.NaN);
    }
}
