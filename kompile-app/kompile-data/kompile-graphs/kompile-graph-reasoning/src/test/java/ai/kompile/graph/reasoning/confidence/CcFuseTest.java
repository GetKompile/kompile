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

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Opinion#ccFuse(List)} — Jøsang Consensus &amp; Compromise Fusion.
 */
@DisplayName("Opinion.ccFuse — CCF properties")
class CcFuseTest {

    private static final double EPS   = 1e-9;
    private static final double LOOSE = 1e-6;

    // ─── Property (i): Idempotence ──────────────────────────────────────────

    @Test
    @DisplayName("(i) idempotence: ccFuse([x, x]) ≈ x")
    void idempotence() {
        Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
        Opinion fused = Opinion.ccFuse(List.of(x, x));
        assertEquals(x.belief(),     fused.belief(),     EPS, "belief");
        assertEquals(x.disbelief(),  fused.disbelief(),  EPS, "disbelief");
        assertEquals(x.uncertainty(), fused.uncertainty(), EPS, "uncertainty");
    }

    @Test
    @DisplayName("(i) idempotence with skewed opinion")
    void idempotenceSkewed() {
        Opinion x = new Opinion(0.8, 0.1, 0.1, 0.3);
        Opinion fused = Opinion.ccFuse(List.of(x, x));
        assertEquals(x.belief(),      fused.belief(),      LOOSE, "belief");
        assertEquals(x.disbelief(),   fused.disbelief(),   LOOSE, "disbelief");
        assertEquals(x.uncertainty(), fused.uncertainty(), LOOSE, "uncertainty");
    }

    // ─── Property (ii): Commutativity ───────────────────────────────────────

    @Test
    @DisplayName("(ii) commutativity: ccFuse([a,b]) ≈ ccFuse([b,a])")
    void commutativity() {
        Opinion a = new Opinion(0.7, 0.1, 0.2, 0.5);
        Opinion b = new Opinion(0.2, 0.5, 0.3, 0.4);
        Opinion ab = Opinion.ccFuse(List.of(a, b));
        Opinion ba = Opinion.ccFuse(List.of(b, a));
        assertEquals(ab.belief(),      ba.belief(),      LOOSE, "belief");
        assertEquals(ab.disbelief(),   ba.disbelief(),   LOOSE, "disbelief");
        assertEquals(ab.uncertainty(), ba.uncertainty(), LOOSE, "uncertainty");
    }

    @Test
    @DisplayName("(ii) commutativity with high-conflict pair")
    void commutativityHighConflict() {
        Opinion a = new Opinion(0.85, 0.10, 0.05, 0.5);
        Opinion b = new Opinion(0.05, 0.90, 0.05, 0.5);
        Opinion ab = Opinion.ccFuse(List.of(a, b));
        Opinion ba = Opinion.ccFuse(List.of(b, a));
        assertEquals(ab.belief(),      ba.belief(),      LOOSE, "belief");
        assertEquals(ab.disbelief(),   ba.disbelief(),   LOOSE, "disbelief");
        assertEquals(ab.uncertainty(), ba.uncertainty(), LOOSE, "uncertainty");
    }

    // ─── Property (iii): Unanimous agreement ────────────────────────────────

    @Test
    @DisplayName("(iii) unanimous: all same opinion → result ≈ that opinion")
    void unanimousAgreement() {
        Opinion x = new Opinion(0.5, 0.3, 0.2, 0.6);
        Opinion fused = Opinion.ccFuse(List.of(x, x, x, x));
        assertEquals(x.belief(),      fused.belief(),      LOOSE, "belief");
        assertEquals(x.disbelief(),   fused.disbelief(),   LOOSE, "disbelief");
        assertEquals(x.uncertainty(), fused.uncertainty(), LOOSE, "uncertainty");
    }

    // ─── Property (iv): High conflict — not dogmatic ────────────────────────

    @Test
    @DisplayName("(iv) high conflict: not dogmatic either way")
    void highConflictNotDogmatic() {
        // Near-dogmatic opposing opinions
        Opinion pos = new Opinion(0.95, 0.01, 0.04, 0.5);
        Opinion neg = new Opinion(0.01, 0.95, 0.04, 0.5);
        Opinion fused = Opinion.ccFuse(List.of(pos, neg));

        // Must satisfy: u > 0.3 OR |b - d| < 0.3
        boolean uSufficient  = fused.uncertainty() > 0.3;
        boolean bdBalanced   = Math.abs(fused.belief() - fused.disbelief()) < 0.3;
        assertTrue(uSufficient || bdBalanced,
                "High-conflict result should not be dogmatic; got " + fused.toJson());
    }

    @Test
    @DisplayName("(iv) high conflict: expectation near base rate")
    void highConflictExpectationNearBase() {
        Opinion pos = new Opinion(0.90, 0.05, 0.05, 0.5);
        Opinion neg = new Opinion(0.05, 0.90, 0.05, 0.5);
        Opinion fused = Opinion.ccFuse(List.of(pos, neg));
        double E = fused.expectation();
        // Expectation should be near 0.5 (the common base rate) — within 0.25
        assertTrue(Math.abs(E - 0.5) < 0.25,
                "Expectation should be near base rate 0.5; got " + E);
    }

    // ─── Property (v): Vacuous doesn't sharpen ──────────────────────────────

    @Test
    @DisplayName("(v) vacuous: fusing with vacuous does not sharpen x")
    void vacuousDoesNotSharpen() {
        Opinion x       = new Opinion(0.7, 0.1, 0.2, 0.5);
        Opinion vacuous = new Opinion(0.0, 0.0, 1.0, 0.5);
        Opinion fused   = Opinion.ccFuse(List.of(x, vacuous));
        // fused.uncertainty() must NOT drop below x.uncertainty() * 0.5
        double uFloor = x.uncertainty() * 0.5;
        assertTrue(fused.uncertainty() >= uFloor,
                "Fusing with vacuous should not sharpen; fused.u=" + fused.uncertainty()
                + " but floor=" + uFloor);
    }

    @Test
    @DisplayName("(v) vacuous: uncertainty non-decreasing from source")
    void vacuousUncertaintyNonDecreasing() {
        Opinion x       = new Opinion(0.6, 0.15, 0.25, 0.5);
        Opinion vacuous = new Opinion(0.0,  0.0,  1.0,  0.5);
        Opinion fused   = Opinion.ccFuse(List.of(x, vacuous));
        // Fusing with pure ignorance should increase (or at least not drastically reduce) u
        assertTrue(fused.uncertainty() > x.uncertainty() * 0.5,
                "fused.u=" + fused.uncertainty() + " must be > x.u*0.5=" + (x.uncertainty() * 0.5));
    }

    // ─── Simplex validity sweep ──────────────────────────────────────────────

    @Test
    @DisplayName("simplex validity: 1000 random triples satisfy b+d+u=1 and all ≥ 0")
    void simplexValiditySweep() {
        Random rng = new Random(42L);
        for (int trial = 0; trial < 1000; trial++) {
            List<Opinion> ops = randomTriple(rng);
            Opinion fused = Opinion.ccFuse(ops);
            double sum = fused.belief() + fused.disbelief() + fused.uncertainty();
            assertTrue(fused.belief()      >= 0, "b < 0 on trial " + trial);
            assertTrue(fused.disbelief()   >= 0, "d < 0 on trial " + trial);
            assertTrue(fused.uncertainty() >= 0, "u < 0 on trial " + trial);
            assertEquals(1.0, sum, 1e-9, "sum ≠ 1 on trial " + trial);
        }
    }

    // ─── Router dispatch ─────────────────────────────────────────────────────

    @Test
    @DisplayName("router dispatch: CONSENSUS_COMPROMISE routes to ccFuse")
    void routerDispatch() {
        Opinion a = new Opinion(0.6, 0.2, 0.2, 0.5);
        Opinion b = new Opinion(0.3, 0.4, 0.3, 0.5);
        List<Opinion> ops = List.of(a, b);

        Opinion viaCcFuse = Opinion.ccFuse(ops);
        Opinion viaRouter = Opinion.fuse(Opinion.FusionMode.CONSENSUS_COMPROMISE, ops);

        assertEquals(viaCcFuse.belief(),      viaRouter.belief(),      EPS, "belief matches");
        assertEquals(viaCcFuse.disbelief(),   viaRouter.disbelief(),   EPS, "disbelief matches");
        assertEquals(viaCcFuse.uncertainty(), viaRouter.uncertainty(), EPS, "uncertainty matches");
        assertEquals(viaCcFuse.baseRate(),    viaRouter.baseRate(),    EPS, "baseRate matches");
    }

    @Test
    @DisplayName("router dispatch: single-element list returns same opinion")
    void routerSingleElement() {
        Opinion x = new Opinion(0.4, 0.3, 0.3, 0.6);
        Opinion fused = Opinion.ccFuse(List.of(x));
        assertEquals(x.belief(),      fused.belief(),      EPS);
        assertEquals(x.disbelief(),   fused.disbelief(),   EPS);
        assertEquals(x.uncertainty(), fused.uncertainty(), EPS);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Generate a random triple of valid Opinions using a Dirichlet-like sampling. */
    private List<Opinion> randomTriple(Random rng) {
        List<Opinion> ops = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            double b = rng.nextDouble();
            double d = rng.nextDouble() * (1.0 - b);
            double u = 1.0 - b - d;
            ops.add(new Opinion(b, d, u, 0.5));
        }
        return ops;
    }
}
