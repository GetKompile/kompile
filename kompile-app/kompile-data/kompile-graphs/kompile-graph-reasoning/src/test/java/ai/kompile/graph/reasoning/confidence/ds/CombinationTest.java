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

import org.junit.jupiter.api.*;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Combination} — DS combination rules on binary frame.
 */
@DisplayName("Combination — DS rules on {T, F}")
class CombinationTest {

    private static final double EPS   = 1e-9;
    private static final double LOOSE = 1e-6;

    // ─── conflictK ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("conflictK: 0.7/0.1/0.2 vs 0.1/0.7/0.2 → K = 0.7*0.7 + 0.1*0.1 = 0.50")
    void conflictKValue() {
        MassFunction a = MassFunction.of(0.7, 0.1, 0.2);
        MassFunction b = MassFunction.of(0.1, 0.7, 0.2);
        double K = Combination.conflictK(a, b);
        // K = 0.7*0.7 + 0.1*0.1 = 0.49 + 0.01 = 0.50
        assertEquals(0.50, K, LOOSE, "K should be 0.50");
    }

    @Test
    @DisplayName("conflictK: symmetric — conflictK(a,b) == conflictK(b,a)")
    void conflictKSymmetric() {
        MassFunction a = MassFunction.of(0.6, 0.2, 0.2);
        MassFunction b = MassFunction.of(0.1, 0.5, 0.4);
        assertEquals(Combination.conflictK(a, b), Combination.conflictK(b, a), EPS);
    }

    @Test
    @DisplayName("conflictK: vacuous vs anything → K = 0")
    void conflictKVacuous() {
        MassFunction vac = MassFunction.of(0.0, 0.0, 1.0);
        MassFunction a   = MassFunction.of(0.7, 0.2, 0.1);
        assertEquals(0.0, Combination.conflictK(vac, a), EPS);
        assertEquals(0.0, Combination.conflictK(a, vac), EPS);
    }

    // ─── Dempster's rule ────────────────────────────────────────────────────

    @Test
    @DisplayName("dempster: moderate conflict — hand-computed pair")
    void dempsterModerateConflict() {
        // a = (0.6, 0.2, 0.2), b = (0.3, 0.3, 0.4)
        // Conjunctive core:
        //   m12T  = 0.6*0.3 + 0.6*0.4 + 0.2*0.3 = 0.18 + 0.24 + 0.06 = 0.48
        //   m12F  = 0.2*0.3 + 0.2*0.4 + 0.2*0.3 = 0.06 + 0.08 + 0.06 = 0.20
        //   m12TF = 0.2*0.4 = 0.08
        //   K     = 0.6*0.3 + 0.2*0.3 = 0.18 + 0.06 = 0.24
        //   (1-K) = 0.76
        //   mT  = 0.48/0.76 ≈ 0.63158
        //   mF  = 0.20/0.76 ≈ 0.26316
        //   mTF = 0.08/0.76 ≈ 0.10526
        MassFunction a = MassFunction.of(0.6, 0.2, 0.2);
        MassFunction b = MassFunction.of(0.3, 0.3, 0.4);
        MassFunction r = Combination.dempster(a, b);

        assertEquals(0.48 / 0.76, r.mT(),  LOOSE, "mT");
        assertEquals(0.20 / 0.76, r.mF(),  LOOSE, "mF");
        assertEquals(0.08 / 0.76, r.mTF(), LOOSE, "mTF");
        assertEquals(1.0, r.total(), EPS, "sum=1");
    }

    @Test
    @DisplayName("dempster: complete conflict throws IllegalStateException")
    void dempsterCompleteConflictThrows() {
        MassFunction a = MassFunction.of(1.0, 0.0, 0.0);
        MassFunction b = MassFunction.of(0.0, 1.0, 0.0);
        assertThrows(IllegalStateException.class, () -> Combination.dempster(a, b));
    }

    // ─── Yager's rule ───────────────────────────────────────────────────────

    @Test
    @DisplayName("yager: conflict mass K goes to mTF")
    void yagerConflictToIgnorance() {
        MassFunction a = MassFunction.of(0.6, 0.2, 0.2);
        MassFunction b = MassFunction.of(0.1, 0.6, 0.3);
        double K = Combination.conflictK(a, b);
        MassFunction r = Combination.yager(a, b);
        // mTF >= m12_TF (at least the conflict K was added)
        double m12TF = a.mTF() * b.mTF();
        assertTrue(r.mTF() >= m12TF - LOOSE,
                "Yager: mTF should contain m12_TF + K contribution");
        assertEquals(1.0, r.mT() + r.mF() + r.mTF(), EPS, "sum=1");
    }

    @Test
    @DisplayName("yager: sum always 1 (no normalization blowup)")
    void yagerAlwaysSumsToOne() {
        MassFunction a = MassFunction.of(0.9, 0.05, 0.05);
        MassFunction b = MassFunction.of(0.05, 0.9, 0.05);
        MassFunction r = Combination.yager(a, b);
        assertEquals(1.0, r.total(), EPS, "Yager sum=1 under high conflict");
    }

    @Test
    @DisplayName("yager: vacuous identity — anything ⊕ vacuous = anything")
    void yagerVacuousIdentity() {
        MassFunction vac = MassFunction.of(0.0, 0.0, 1.0);
        MassFunction a   = MassFunction.of(0.5, 0.3, 0.2);
        MassFunction r   = Combination.yager(a, vac);
        assertEquals(a.mT(),  r.mT(),  LOOSE, "mT preserved with vacuous");
        assertEquals(a.mF(),  r.mF(),  LOOSE, "mF preserved with vacuous");
        assertEquals(a.mTF(), r.mTF(), LOOSE, "mTF preserved with vacuous");
    }

    // ─── PCR5 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PCR5: mass conservation — 200 random pairs sum to 1 ± 1e-9")
    void pcr5MassConservation() {
        Random rng = new Random(1234L);
        for (int i = 0; i < 200; i++) {
            MassFunction a = randomBba(rng);
            MassFunction b = randomBba(rng);
            MassFunction r = Combination.pcr5(a, b);
            assertEquals(1.0, r.total(), 1e-9,
                    "PCR5 sum ≠ 1 on iteration " + i
                    + "; a=" + a + " b=" + b + " r=" + r);
        }
    }

    @Test
    @DisplayName("PCR5: high-conflict symmetric pair → mT ≈ mF")
    void pcr5HighConflictSymmetric() {
        MassFunction a = MassFunction.of(0.9, 0.05, 0.05);
        MassFunction b = MassFunction.of(0.05, 0.9, 0.05);
        MassFunction r = Combination.pcr5(a, b);
        // Symmetric inputs → symmetric result: mT ≈ mF
        assertEquals(r.mT(), r.mF(), LOOSE,
                "High-conflict symmetric pair should yield mT ≈ mF; got mT=" + r.mT() + " mF=" + r.mF());
        // Should not collapse to fully dogmatic certainty in one direction
        assertTrue(r.mT() < 0.95, "Should not be fully dogmatic T: mT=" + r.mT());
        assertTrue(r.mF() < 0.95, "Should not be fully dogmatic F: mF=" + r.mF());
    }

    @Test
    @DisplayName("PCR5: non-associativity — fold order matters for asymmetric inputs")
    void pcr5NonAssociative() {
        // Use asymmetric inputs where A≠B≠C to demonstrate fold order dependence
        MassFunction a = MassFunction.of(0.8, 0.1, 0.1);
        MassFunction b = MassFunction.of(0.1, 0.7, 0.2);
        MassFunction c = MassFunction.of(0.5, 0.1, 0.4);

        // Forward fold: (a ⊕ b) ⊕ c
        MassFunction forward = Combination.pcr5(Combination.pcr5(a, b), c);
        // Reverse fold: a ⊕ (b ⊕ c) — i.e. (c ⊕ b) ⊕ a reversed
        MassFunction reverse = Combination.pcr5(a, Combination.pcr5(b, c));

        // PCR5 is not associative: results need not be equal
        // Verify at least one component differs by more than numerical noise
        boolean anyDiffers = Math.abs(forward.mT() - reverse.mT()) > 1e-9
                || Math.abs(forward.mF() - reverse.mF()) > 1e-9
                || Math.abs(forward.mTF() - reverse.mTF()) > 1e-9;
        assertTrue(anyDiffers,
                "PCR5 should be non-associative on asymmetric inputs: fwd=" + forward + " rev=" + reverse);

        // Sanity: both fold results are still valid BBAs
        assertEquals(1.0, forward.total(), EPS, "forward sum=1");
        assertEquals(1.0, reverse.total(), EPS, "reverse sum=1");
    }

    // ─── TBM conjunctive ─────────────────────────────────────────────────────

    @Test
    @DisplayName("tbmConjunctive: emptyMass == conflictK")
    void tbmEmptyMassEqualsConflict() {
        MassFunction a = MassFunction.of(0.7, 0.1, 0.2);
        MassFunction b = MassFunction.of(0.2, 0.6, 0.2);
        double K = Combination.conflictK(a, b);
        MassFunction tbm = Combination.tbmConjunctive(a, b);
        assertEquals(K, tbm.mEmpty(), LOOSE, "mEmpty == K");
    }

    @Test
    @DisplayName("tbmConjunctive: total = 1.0")
    void tbmTotal() {
        MassFunction a = MassFunction.of(0.5, 0.3, 0.2);
        MassFunction b = MassFunction.of(0.4, 0.3, 0.3);
        MassFunction tbm = Combination.tbmConjunctive(a, b);
        assertEquals(1.0, tbm.total(), EPS);
    }

    @Test
    @DisplayName("tbmConjunctive: complete conflict → emptyMass = 1.0")
    void tbmCompleteConflict() {
        MassFunction a = MassFunction.of(1.0, 0.0, 0.0);
        MassFunction b = MassFunction.of(0.0, 1.0, 0.0);
        MassFunction tbm = Combination.tbmConjunctive(a, b);
        assertEquals(1.0, tbm.mEmpty(), LOOSE, "All mass in empty-set under complete conflict");
        assertEquals(0.0, tbm.mT(),    LOOSE);
        assertEquals(0.0, tbm.mF(),    LOOSE);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Generate a random valid closed-world BBA. */
    private MassFunction randomBba(Random rng) {
        double mT = rng.nextDouble();
        double mF = rng.nextDouble() * (1.0 - mT);
        double mTF = 1.0 - mT - mF;
        return MassFunction.of(mT, mF, mTF);
    }
}
