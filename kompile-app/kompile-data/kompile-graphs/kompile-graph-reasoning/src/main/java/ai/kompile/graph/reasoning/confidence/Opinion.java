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

import java.util.Collection;
import java.util.List;

/**
 * Subjective Logic opinion: first-class epistemic primitive.
 * b + d + u = 1.0 (simplex constraint, epsilon-tolerant).
 * expectation() = belief + baseRate * uncertainty.
 * projectStatus() → VerifyStatusProjection (SUPPORTED/REFUTED/UNKNOWN)
 * projectBand() → StrengthBand
 */
public record Opinion(double belief, double disbelief, double uncertainty, double baseRate) {

    private static final double SIMPLEX_EPSILON = 1e-9;

    public Opinion {
        if (belief < 0 || disbelief < 0 || uncertainty < 0)
            throw new IllegalArgumentException("All opinion components must be >= 0; got b=" + belief + " d=" + disbelief + " u=" + uncertainty);
        double sum = belief + disbelief + uncertainty;
        if (Math.abs(sum - 1.0) > SIMPLEX_EPSILON)
            throw new IllegalArgumentException("belief + disbelief + uncertainty must equal 1.0; got " + sum);
        if (baseRate < 0 || baseRate > 1)
            throw new IllegalArgumentException("baseRate must be in [0,1]; got " + baseRate);
    }

    /** Projected probability: belief + baseRate * uncertainty. */
    public double expectation() { return belief + baseRate * uncertainty; }

    /** How much of the simplex is undecided. */
    public double ignorance() { return uncertainty; }

    /** True when no evidence has been accumulated (u ~= 1.0). */
    public boolean isVacuous() { return Math.abs(uncertainty - 1.0) < SIMPLEX_EPSILON; }

    /** True when both belief and disbelief exceed the given threshold. */
    public boolean isConflicted(double threshold) {
        return belief > threshold && disbelief > threshold;
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    public static Opinion vacuous() { return vacuous(0.5); }

    public static Opinion vacuous(double baseRate) {
        return new Opinion(0.0, 0.0, 1.0, baseRate);
    }

    /**
     * From PSL soft-truth. supportCount rules fired → uncertainty = 1/(supportCount+1).
     */
    public static Opinion fromSoftTruth(double softTruth, long evidenceCount) {
        // evidenceCount drives uncertainty down
        double u = 1.0 / (evidenceCount + 1.0);
        double spread = 1.0 - u;
        double b = clamp(softTruth * spread);
        double d = clamp((1.0 - softTruth) * spread);
        // renormalize to ensure sum=1 exactly
        return ofNormalized(b, d, u, 0.5);
    }

    /**
     * From a scalar soft-truth with implicit zero-uncertainty (fully observed).
     */
    public static Opinion fromSoftTruth(double softTruth) {
        return fromSoftTruth(softTruth, 10); // default: treat as well-supported
    }

    /**
     * From an observed hard fact (value in [0,1]; uncertainty=0).
     */
    public static Opinion fromObserved() {
        return new Opinion(1.0, 0.0, 0.0, 1.0);
    }

    /**
     * From an observed scalar value (clamps b+d to 1 with u=0).
     */
    public static Opinion fromObservedValue(double value) {
        double b = clamp(value);
        double d = clamp(1.0 - value);
        // force u=0 by renormalizing
        double total = b + d;
        if (total < 1e-12) return new Opinion(0.5, 0.5, 0.0, value);
        return new Opinion(b / total, d / total, 0.0, clamp(value));
    }

    /**
     * From positive/negative evidence counts using Beta-distribution mapping.
     * k = prior strength (default 2 for non-informative).
     * b = pos/(pos+neg+k), d = neg/(pos+neg+k), u = k/(pos+neg+k)
     */
    public static Opinion fromBetaEvidence(double pos, double neg) {
        return fromBetaEvidence(pos, neg, 0.5, 2.0);
    }

    public static Opinion fromBetaEvidence(double pos, double neg, double baseRate, double k) {
        double total = pos + neg + k;
        return new Opinion(pos / total, neg / total, k / total, baseRate);
    }

    /**
     * From a Bayesian posterior (MEBN output). Information gain drives uncertainty down.
     */
    public static Opinion fromBayesianPosterior(double posterior, double baseRate) {
        double infGain = Math.abs(posterior - baseRate);
        double u = Math.max(0.0, 1.0 - 2.0 * infGain);
        double spread = 1.0 - u;
        return ofNormalized(posterior * spread, (1.0 - posterior) * spread, u, baseRate);
    }

    /**
     * From an embedding calibrated score with configurable uncertainty floor.
     */
    public static Opinion fromEmbeddingScore(double calibratedScore, double embeddingUncertainty) {
        double spread = 1.0 - embeddingUncertainty;
        return ofNormalized(calibratedScore * spread, (1.0 - calibratedScore) * spread, embeddingUncertainty, 0.5);
    }

    // ─── Fusion operators ────────────────────────────────────────────────────

    /**
     * Cumulative fusion: combine two independent opinions (Dempster-Shafer / Jøsang §12.2).
     * Returns lower-u opinion when sources agree; conflicted opinion when they disagree.
     */
    public Opinion cumulativeFuse(Opinion other) {
        double ua = this.uncertainty;
        double ub = other.uncertainty;
        double denom = ua + ub - ua * ub;
        if (denom < 1e-12) {
            // Both certain — average
            return new Opinion(
                clamp((this.belief + other.belief) / 2),
                clamp((this.disbelief + other.disbelief) / 2),
                0.0,
                clamp((this.baseRate + other.baseRate) / 2)
            );
        }
        double b = clamp((this.belief * ub + other.belief * ua) / denom);
        double d = clamp((this.disbelief * ub + other.disbelief * ua) / denom);
        double u = clamp(ua * ub / denom);
        double denomA = 2.0 - ua - ub;
        double a = denomA < 1e-12 ? clamp((this.baseRate + other.baseRate) / 2)
                                  : clamp((this.baseRate * ub + other.baseRate * ua) / denomA);
        return ofNormalized(b, d, u, a);
    }

    /** Static varargs entry point for cumulativeFuse of many independent sources. */
    public static Opinion cumulativeFuse(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        Opinion acc = opinions[0];
        for (int i = 1; i < opinions.length; i++) acc = acc.cumulativeFuse(opinions[i]);
        return acc;
    }

    /**
     * Averaging fusion: arithmetic mean. Use for dependent signals sharing common antecedents.
     */
    public Opinion averageFuse(Opinion other) {
        return ofNormalized(
            (this.belief + other.belief) / 2,
            (this.disbelief + other.disbelief) / 2,
            (this.uncertainty + other.uncertainty) / 2,
            (this.baseRate + other.baseRate) / 2
        );
    }

    /** Static varargs for averaging. */
    public static Opinion averageFuse(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        double sb = 0, sd = 0, su = 0, sa = 0;
        for (Opinion o : opinions) { sb += o.belief; sd += o.disbelief; su += o.uncertainty; sa += o.baseRate; }
        int n = opinions.length;
        return ofNormalized(sb/n, sd/n, su/n, sa/n);
    }

    /**
     * Consensus: certainty-weighted centroid (Jøsang §12.5).
     * Use for N sources of varying reliability.
     */
    public static Opinion consensus(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        if (opinions.size() == 1) return opinions.get(0);
        double totalWeight = 0, sumB = 0, sumD = 0, sumA = 0;
        for (Opinion o : opinions) {
            double w = 1.0 - o.uncertainty;
            totalWeight += w;
            sumB += w * o.belief;
            sumD += w * o.disbelief;
            sumA += w * o.baseRate;
        }
        if (totalWeight < 1e-12) return vacuous();
        double b = clamp(sumB / totalWeight);
        double d = clamp(sumD / totalWeight);
        double u = clamp(1.0 - b - d);
        double a = clamp(sumA / totalWeight);
        return ofNormalized(b, d, u, a);
    }

    public static Opinion consensus(Collection<Opinion> opinions) {
        return consensus(opinions instanceof List ? (List<Opinion>) opinions : List.copyOf(opinions));
    }

    // ─── Trichotomy projection ──────────────────────────────────────────────

    public enum VerifyStatusProjection { SUPPORTED, REFUTED, UNKNOWN }

    /**
     * Project to VerifyStatusProjection.
     * UNKNOWN when uncertainty >= unknownThreshold.
     * SUPPORTED when expectation >= supportThreshold.
     * REFUTED when expectation < refuteThreshold.
     */
    public VerifyStatusProjection projectStatus(double supportThreshold, double refuteThreshold, double unknownThreshold) {
        if (uncertainty >= unknownThreshold) return VerifyStatusProjection.UNKNOWN;
        double e = expectation();
        if (e >= supportThreshold) return VerifyStatusProjection.SUPPORTED;
        if (e < refuteThreshold) return VerifyStatusProjection.REFUTED;
        return VerifyStatusProjection.UNKNOWN;
    }

    /** Convenience with defaults: support>=0.5, refute<0.3, unknown>=0.6 */
    public VerifyStatusProjection projectStatus() {
        return projectStatus(0.5, 0.3, 0.6);
    }

    /** Project to a StrengthBand using both expectation and uncertainty. */
    public StrengthBand projectBand() {
        double e = expectation();
        if (e >= 0.85 && uncertainty < 0.15) return StrengthBand.ESTABLISHED;
        if (e >= 0.70 && uncertainty < 0.30) return StrengthBand.HIGH;
        if (e >= 0.40 && uncertainty < 0.60) return StrengthBand.PROBABLE;
        if (e >= 0.10) return StrengthBand.SPECULATIVE;
        return StrengthBand.SUPPRESSED;
    }

    // ─── JSON ────────────────────────────────────────────────────────────────

    public String toJson() {
        return String.format("{\"belief\":%.9f,\"disbelief\":%.9f,\"uncertainty\":%.9f,\"baseRate\":%.9f}",
            belief, disbelief, uncertainty, baseRate);
    }

    public static Opinion fromJson(String json) {
        double b = parseField(json, "belief");
        double d = parseField(json, "disbelief");
        double u = parseField(json, "uncertainty");
        double a = parseField(json, "baseRate");
        return new Opinion(b, d, u, a);
    }

    private static double parseField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) throw new IllegalArgumentException("Missing field: " + field + " in " + json);
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return Double.parseDouble(json.substring(start, end).trim());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }

    /** Normalize b,d,u so they sum to 1.0; keeps baseRate as-is. */
    private static Opinion ofNormalized(double b, double d, double u, double baseRate) {
        b = clamp(b); d = clamp(d); u = clamp(u);
        double sum = b + d + u;
        if (sum < 1e-12) return vacuous(clamp(baseRate));
        return new Opinion(b / sum, d / sum, u / sum, clamp(baseRate));
    }
}
