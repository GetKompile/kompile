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

/**
 * Dempster-Shafer combination rules on the binary frame Ω = {T, F}.
 *
 * <h2>Binary frame power-set intersections</h2>
 * <pre>
 *   T  ∩ T  = T
 *   T  ∩ F  = ∅   (conflict)
 *   T  ∩ TF = T
 *   F  ∩ F  = F
 *   F  ∩ T  = ∅   (conflict)
 *   F  ∩ TF = F
 *   TF ∩ X  = X   (for any X)
 * </pre>
 *
 * <h2>Conflict mass</h2>
 * <pre>
 *   K = m_a(T)·m_b(F) + m_a(F)·m_b(T)
 * </pre>
 *
 * <h2>Conjunctive combination (before redistribution / normalization)</h2>
 * <pre>
 *   m12(T)  = m_a(T)·m_b(T) + m_a(T)·m_b(TF) + m_a(TF)·m_b(T)
 *   m12(F)  = m_a(F)·m_b(F) + m_a(F)·m_b(TF) + m_a(TF)·m_b(F)
 *   m12(TF) = m_a(TF)·m_b(TF)
 *   K       = m_a(T)·m_b(F) + m_a(F)·m_b(T)   [maps to ∅]
 * </pre>
 */
public final class Combination {

    private Combination() {}

    // ─── Conflict ──────────────────────────────────────────────────────────────

    /**
     * Conflict mass K between two BBAs.
     * {@code K = m_a(T)·m_b(F) + m_a(F)·m_b(T)}.
     */
    public static double conflictK(MassFunction a, MassFunction b) {
        return a.mT() * b.mF() + a.mF() * b.mT();
    }

    // ─── Conjunctive core (shared by all rules) ────────────────────────────────

    private static double[] conjunctiveCore(MassFunction a, MassFunction b) {
        // returns [m12T, m12F, m12TF, K]
        double m12T  = a.mT()  * b.mT()  + a.mT()  * b.mTF() + a.mTF() * b.mT();
        double m12F  = a.mF()  * b.mF()  + a.mF()  * b.mTF() + a.mTF() * b.mF();
        double m12TF = a.mTF() * b.mTF();
        double K     = a.mT()  * b.mF()  + a.mF()  * b.mT();
        return new double[]{m12T, m12F, m12TF, K};
    }

    // ─── Dempster's rule ──────────────────────────────────────────────────────

    /**
     * Dempster's rule: conjunctive combination normalized by {@code (1 − K)}.
     *
     * <p>Redistributes the conflict mass K onto the remaining hypotheses proportionally.
     * This is the classical DS combination rule and is the standard choice when sources
     * are reliable and conflict is low.</p>
     *
     * @throws IllegalStateException when {@code K ≥ 1 − 1e-12} (complete conflict).
     *         In that case, call {@link #tbmConjunctive} to handle the open-world signal,
     *         or use {@link #yager} / {@link #pcr5} which do not normalize.
     */
    public static MassFunction dempster(MassFunction a, MassFunction b) {
        double[] core = conjunctiveCore(a, b);
        double m12T = core[0], m12F = core[1], m12TF = core[2], K = core[3];
        double oneMinusK = 1.0 - K;
        if (oneMinusK < 1e-12)
            throw new IllegalStateException(
                    "Dempster's rule undefined: complete conflict K=" + K
                    + ". Use tbmConjunctive() or pcr5() instead.");
        return MassFunction.of(
                clamp(m12T  / oneMinusK),
                clamp(m12F  / oneMinusK),
                clamp(m12TF / oneMinusK));
    }

    // ─── Yager's rule ──────────────────────────────────────────────────────────

    /**
     * Yager's rule: conflict mass K is transferred to {@code m(TF)} (total ignorance).
     *
     * <p>No normalization step; no division-by-zero risk under high conflict.
     * Use when you prefer to "withhold judgment" on conflicting sources rather than
     * redistribute conflict mass onto winning hypotheses.</p>
     *
     * <pre>
     *   m_Y(T)  = m12(T)
     *   m_Y(F)  = m12(F)
     *   m_Y(TF) = m12(TF) + K
     *   m_Y(∅)  = 0        [conflict goes to ignorance, not empty set]
     * </pre>
     */
    public static MassFunction yager(MassFunction a, MassFunction b) {
        double[] core = conjunctiveCore(a, b);
        double m12T = core[0], m12F = core[1], m12TF = core[2], K = core[3];
        double newTF = clamp(m12TF + K);
        double newT  = clamp(m12T);
        double newF  = clamp(m12F);
        // Renormalize to ensure exact sum=1 (floating-point cleanup)
        double sum = newT + newF + newTF;
        if (sum < 1e-15) return MassFunction.of(0.0, 0.0, 1.0);
        return MassFunction.of(newT / sum, newF / sum, newTF / sum);
    }

    // ─── PCR5 ──────────────────────────────────────────────────────────────────

    /**
     * PCR5 — Proportional Conflict Redistribution rule #5 (Smarandanche &amp; Dezert).
     *
     * <p>Each partial conflict term is redistributed proportionally back to the
     * conflicting hypotheses, preserving local proportionality.  This resolves
     * Zadeh's paradox: under high conflict, PCR5 produces intuitively correct results
     * where Dempster's rule fails.</p>
     *
     * <h3>Binary frame formula</h3>
     * Conjunctive core (same as Dempster / Yager, before redistribution):
     * <pre>
     *   m12(T)  = m_a(T)·m_b(T) + m_a(T)·m_b(TF) + m_a(TF)·m_b(T)
     *   m12(F)  = m_a(F)·m_b(F) + m_a(F)·m_b(TF) + m_a(TF)·m_b(F)
     *   m12(TF) = m_a(TF)·m_b(TF)
     *   c1 = m_a(T)·m_b(F),   c2 = m_a(F)·m_b(T)   [partial conflict terms]
     * </pre>
     * PCR5 redistribution:
     * <pre>
     *   T gets from c1: c1 · m_a(T) / (m_a(T) + m_b(F))   [if denom > 0]
     *   F gets from c1: c1 · m_b(F) / (m_a(T) + m_b(F))
     *   F gets from c2: c2 · m_a(F) / (m_a(F) + m_b(T))
     *   T gets from c2: c2 · m_b(T) / (m_a(F) + m_b(T))
     * </pre>
     *
     * <p><strong>Note:</strong> PCR5 is NOT associative for N &gt; 2 sources.
     * Multi-source fusion uses a sequential pairwise fold in input order.
     * The caller should establish a canonical ordering when order independence matters.</p>
     */
    public static MassFunction pcr5(MassFunction a, MassFunction b) {
        double[] core = conjunctiveCore(a, b);
        double m12T = core[0], m12F = core[1], m12TF = core[2];

        // Partial conflict terms
        double c1 = a.mT() * b.mF();   // T vs F conflict
        double c2 = a.mF() * b.mT();   // F vs T conflict

        // PCR5: proportional redistribution of each partial conflict term
        double tFromC1 = 0.0, fFromC1 = 0.0;
        double denom1 = a.mT() + b.mF();
        if (denom1 > 1e-15) {
            tFromC1 = c1 * a.mT() / denom1;
            fFromC1 = c1 * b.mF() / denom1;
        }

        double fFromC2 = 0.0, tFromC2 = 0.0;
        double denom2 = a.mF() + b.mT();
        if (denom2 > 1e-15) {
            fFromC2 = c2 * a.mF() / denom2;
            tFromC2 = c2 * b.mT() / denom2;
        }

        double newT  = clamp(m12T  + tFromC1 + tFromC2);
        double newF  = clamp(m12F  + fFromC1 + fFromC2);
        double newTF = clamp(m12TF);

        // Renormalize for floating-point precision
        double sum = newT + newF + newTF;
        if (sum < 1e-15) return MassFunction.of(0.0, 0.0, 1.0);
        return MassFunction.of(newT / sum, newF / sum, newTF / sum);
    }

    // ─── TBM (Smets) unnormalized conjunctive ─────────────────────────────────

    /**
     * TBM unnormalized conjunctive rule (Smets' Transferable Belief Model).
     *
     * <p>Keeps {@code m(∅) = K} as an open-world signal rather than normalizing it away.
     * The non-empty masses are the raw conjunctive combination (same as numerators in
     * Dempster's rule).  The result is a valid BBA in the open-world sense:
     * {@code m(T) + m(F) + m(TF) + m(∅) = 1}.</p>
     *
     * <p>Use when sources may have different frames of discernment — the resulting
     * {@code m(∅) > 0} quantifies how much the sources are "talking past each other".
     * To recover a closed-world BBA, call {@link MassFunction#closedWorld()} on the result.</p>
     */
    public static MassFunction tbmConjunctive(MassFunction a, MassFunction b) {
        double[] core = conjunctiveCore(a, b);
        double m12T = core[0], m12F = core[1], m12TF = core[2], K = core[3];
        return MassFunction.ofOpen(clamp(m12T), clamp(m12F), clamp(m12TF), clamp(K));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }
}
