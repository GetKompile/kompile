/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence.ds;

import ai.kompile.graph.reasoning.confidence.Opinion;

import java.io.Serializable;

/**
 * Basic Belief Assignment (BBA) on the binary frame Ω = {T, F}.
 *
 * <p>Power set: ∅, {T}, {F}, {T,F} (the full frame / ignorance set).
 * In TBM (Transferable Belief Model) open-world mode, {@code m(∅) > 0} signals
 * that sources may be talking about different frames of discernment.
 *
 * <p>Normalization: {@code m(T) + m(F) + m(TF) + m(EMPTY) = 1.0}
 * (closed-world: {@code m(EMPTY) = 0}).
 *
 * <h2>Direct bijection with Subjective Logic</h2>
 * For the binary frame there is a one-to-one correspondence:
 * <pre>
 *   m(T)  = b  (belief)
 *   m(F)  = d  (disbelief)
 *   m(TF) = u  (uncertainty / ignorance)
 * </pre>
 * Use {@link #fromOpinion(Opinion)} / {@link #toOpinion(double)} to convert.
 */
public record MassFunction(double mT, double mF, double mTF, double mEmpty) implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final double SUM_EPSILON = 1e-9;

    public MassFunction {
        if (mT < 0 || mF < 0 || mTF < 0 || mEmpty < 0)
            throw new IllegalArgumentException(
                    "All mass values must be >= 0; got m(T)=" + mT + " m(F)=" + mF
                    + " m(TF)=" + mTF + " m(∅)=" + mEmpty);
        double total = mT + mF + mTF + mEmpty;
        if (Math.abs(total - 1.0) > SUM_EPSILON)
            throw new IllegalArgumentException(
                    "Masses must sum to 1.0; got " + total);
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    /**
     * Create a closed-world MassFunction (no open-world mass).
     * Validates that the three masses sum to approximately 1.0.
     */
    public static MassFunction of(double mT, double mF, double mTF) {
        double total = mT + mF + mTF;
        if (Math.abs(total - 1.0) > SUM_EPSILON)
            throw new IllegalArgumentException(
                    "Masses must sum to 1.0 for a closed-world BBA; got " + total);
        return new MassFunction(mT, mF, mTF, 0.0);
    }

    /**
     * Create a MassFunction including an open-world empty-world mass.
     * All four masses must sum to approximately 1.0.
     */
    public static MassFunction ofOpen(double mT, double mF, double mTF, double mEmpty) {
        return new MassFunction(mT, mF, mTF, mEmpty);
    }

    /**
     * Convert from Opinion using the direct Subjective Logic bijection:
     * {@code b→m(T), d→m(F), u→m(TF), mEmpty=0}.
     */
    public static MassFunction fromOpinion(Opinion op) {
        return new MassFunction(op.belief(), op.disbelief(), op.uncertainty(), 0.0);
    }

    /**
     * Convert back to Opinion.
     *
     * <p>First closes the open-world mass by distributing {@code m(∅)} proportionally
     * onto T, F, TF (see {@link #closedWorld()}), then applies the direct bijection:
     * {@code b = m(T), d = m(F), u = m(TF)}.
     *
     * @param baseRate the Opinion base rate (prior probability of T)
     * @return equivalent Opinion
     */
    public Opinion toOpinion(double baseRate) {
        MassFunction cw = closedWorld();
        double b = cw.mT();
        double d = cw.mF();
        double u = cw.mTF();
        // Renormalize to ensure exact simplex sum after floating-point rounding
        double sum = b + d + u;
        if (sum < 1e-15) return Opinion.vacuous(clamp(baseRate));
        return new Opinion(clamp(b / sum), clamp(d / sum), clamp(u / sum), clamp(baseRate));
    }

    // ─── Belief & Plausibility measures ──────────────────────────────────────

    /** Belief measure: {@code bel(T) = m(T)}. */
    public double belT() { return mT; }

    /** Plausibility: {@code pl(T) = m(T) + m(TF)}. */
    public double plT() { return mT + mTF; }

    /** Belief measure: {@code bel(F) = m(F)}. */
    public double belF() { return mF; }

    /** Plausibility: {@code pl(F) = m(F) + m(TF)}. */
    public double plF() { return mF + mTF; }

    // ─── Normalization ────────────────────────────────────────────────────────

    /**
     * Normalize closed-world: distribute {@code mEmpty} proportionally onto T, F, TF.
     * When {@code mEmpty == 0}, returns {@code this} unchanged.
     * When {@code mT + mF + mTF == 0} (completely empty BBA), returns equal distribution.
     */
    public MassFunction closedWorld() {
        if (mEmpty < 1e-15) return this;
        double rest = mT + mF + mTF;
        if (rest < 1e-15) {
            // Degenerate: all mass in empty-set; distribute equally
            return new MassFunction(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, 0.0);
        }
        double factor = (rest + mEmpty) / rest;
        double newT  = mT  * factor;
        double newF  = mF  * factor;
        double newTF = mTF * factor;
        // Ensure exact sum=1 by adjusting mTF for floating-point drift
        newTF = 1.0 - newT - newF;
        if (newTF < 0) newTF = 0.0;
        return new MassFunction(newT, newF, newTF, 0.0);
    }

    /** Sum of all masses — should be ≈ 1.0. */
    public double total() { return mT + mF + mTF + mEmpty; }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }
}
