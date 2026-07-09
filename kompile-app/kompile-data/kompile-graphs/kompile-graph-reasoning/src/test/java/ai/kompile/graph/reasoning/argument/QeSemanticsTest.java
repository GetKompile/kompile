/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Postulate and property tests for {@link QeSemantics} (Potyka 2018 quadratic-energy gradual
 * semantics).  Mirrors the {@code DfQuadPostulateTest} suite so the two implementations
 * can be validated against the same structural expectations while differing in numeric values.
 */
@DisplayName("QE semantics postulates")
class QeSemanticsTest {

    private static final double EPS = 1e-7;

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static Qbaf isolatedClaim(double prior) {
        return Qbaf.builder()
                .argument(Argument.claim("c", "claim", prior))
                .build();
    }

    private static Qbaf withSupporter(double prior, double supportBase) {
        return Qbaf.builder()
                .argument(Argument.claim("c", "claim", prior))
                .argument(Argument.pro("s", "supporter", supportBase))
                .support("s", "c")
                .build();
    }

    private static Qbaf withAttacker(double prior, double attackBase) {
        return Qbaf.builder()
                .argument(Argument.claim("c", "claim", prior))
                .argument(Argument.con("a", "attacker", attackBase))
                .attack("a", "c")
                .build();
    }

    // ── h function ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("h(0) = 0")
    void hAtZero() {
        assertEquals(0.0, QeSemantics.h(0.0), EPS);
    }

    @Test
    @DisplayName("h(-5) = 0 (non-positive input)")
    void hNonPositive() {
        assertEquals(0.0, QeSemantics.h(-5.0), EPS);
        assertEquals(0.0, QeSemantics.h(-1.0), EPS);
    }

    @Test
    @DisplayName("h(1) = 0.5 (1²/(1+1²) = 1/2)")
    void hAtOne() {
        assertEquals(0.5, QeSemantics.h(1.0), EPS);
    }

    @Test
    @DisplayName("h(x) is strictly increasing for x > 0")
    void hIncreasing() {
        double prev = 0.0;
        for (double x : new double[]{0.01, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0}) {
            double curr = QeSemantics.h(x);
            assertTrue(curr > prev, "h not increasing at x=" + x + ": prev=" + prev + " curr=" + curr);
            prev = curr;
        }
    }

    @Test
    @DisplayName("h(x) < 1 for moderate x, saturates to ≤ 1 at extreme x")
    void hBoundedBelow1() {
        // h(x) = r²/(1+r²) is mathematically strictly < 1, but in IEEE-754 the "+1" is lost
        // once r² exceeds ~2^53 (e.g. r=1e9 → r²=1e18, 1.0+1e18 rounds back to 1e18), so h
        // saturates to exactly 1.0 at extreme x. Contract: strictly < 1 for moderate x; never > 1.
        assertTrue(QeSemantics.h(1000.0) < 1.0);
        assertTrue(QeSemantics.h(1e6) < 1.0);
        // At extreme x the "+1" is absorbed (h→1.0) or r² overflows to Inf (h→NaN); the real
        // contract is that h NEVER exceeds 1 — NaN > 1.0 is false, so assertFalse is NaN-safe.
        assertFalse(QeSemantics.h(1e9) > 1.0);
        assertFalse(QeSemantics.h(Double.MAX_VALUE) > 1.0);
    }

    // ── Stability: isolated argument σ = β ───────────────────────────────────────

    @Test
    @DisplayName("Stability: isolated claim σ = β")
    void stabilityIsolatedClaim() {
        for (double beta : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            Qbaf qbaf = isolatedClaim(beta);
            double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
            assertEquals(beta, sigma, EPS, "isolated claim: sigma should equal beta=" + beta);
        }
    }

    @Test
    @DisplayName("Stability: balanced attack and support → σ = β")
    void stabilityBalanced() {
        // Symmetric attack and support of equal strength → E=0 → σ=β
        Qbaf qbaf = Qbaf.builder()
                .argument(Argument.claim("c", "claim", 0.6))
                .argument(Argument.pro("s", "sup", 0.5))
                .argument(Argument.con("a", "att", 0.5))
                .support("s", "c")
                .attack ("a", "c")
                .build();
        QeSemantics.Result res = QeSemantics.evaluate(qbaf);
        // E(c) = σ(s) - σ(a) = 0.5 - 0.5 = 0  → h(0)=h(0)=0 → σ=β=0.6
        double sigma = res.claimStrength(qbaf);
        assertEquals(0.6, sigma, EPS, "balanced E=0 → sigma=beta=0.6");
    }

    // ── Weakening: net attack → σ < β ───────────────────────────────────────────

    @Test
    @DisplayName("Weakening: net attack reduces claim strength below β")
    void weakeningNetAttack() {
        double beta = 0.5;
        Qbaf qbaf = withAttacker(beta, 0.8);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        assertTrue(sigma < beta,
                "Net attack must weaken: sigma=" + sigma + " beta=" + beta);
    }

    @Test
    @DisplayName("Weakening at beta=0: sigma stays 0")
    void weakeningAtZeroBeta() {
        // β=0: σ = 0 + 1·h(E) - 0·h(-E) = h(E). With E<0, h(E)=0 → σ=0.
        Qbaf qbaf = withAttacker(0.0, 0.8);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        assertEquals(0.0, sigma, EPS, "beta=0: net attack → sigma=0");
    }

    // ── Strengthening: net support → σ > β ──────────────────────────────────────

    @Test
    @DisplayName("Strengthening: net support raises claim strength above β")
    void strengtheningNetSupport() {
        double beta = 0.5;
        Qbaf qbaf = withSupporter(beta, 0.8);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        assertTrue(sigma > beta,
                "Net support must strengthen: sigma=" + sigma + " beta=" + beta);
    }

    @Test
    @DisplayName("Strengthening at beta=1: sigma stays 1 (ceiling)")
    void strengtheningAtOneBeta() {
        // β=1: σ = 1 + (1-1)·h(E) - 1·h(-E) = 1 + 0 - 0 = 1 for net support (E>0 → h(-E)=0)
        Qbaf qbaf = withSupporter(1.0, 0.9);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        assertEquals(1.0, sigma, EPS, "beta=1: net support → sigma stays 1");
    }

    // ── Directional monotonicity ─────────────────────────────────────────────────

    @Test
    @DisplayName("Monotonicity: adding a supporter never lowers sigma")
    void monotonicityAdditionalSupporter() {
        double beta = 0.4;
        Qbaf base = withSupporter(beta, 0.6);
        double sigmaBefore = QeSemantics.evaluate(base).claimStrength(base);

        Qbaf augmented = Qbaf.builder()
                .argument(Argument.claim("c", "claim", beta))
                .argument(Argument.pro("s1", "s1", 0.6))
                .argument(Argument.pro("s2", "s2", 0.7))
                .support("s1", "c").support("s2", "c")
                .build();
        double sigmaAfter = QeSemantics.evaluate(augmented).claimStrength(augmented);
        assertTrue(sigmaAfter >= sigmaBefore - EPS,
                "Adding supporter must not lower sigma: before=" + sigmaBefore + " after=" + sigmaAfter);
    }

    @Test
    @DisplayName("Monotonicity: adding an attacker never raises sigma")
    void monotonicityAdditionalAttacker() {
        double beta = 0.7;
        Qbaf base = withAttacker(beta, 0.5);
        double sigmaBefore = QeSemantics.evaluate(base).claimStrength(base);

        Qbaf augmented = Qbaf.builder()
                .argument(Argument.claim("c", "claim", beta))
                .argument(Argument.con("a1", "a1", 0.5))
                .argument(Argument.con("a2", "a2", 0.6))
                .attack("a1", "c").attack("a2", "c")
                .build();
        double sigmaAfter = QeSemantics.evaluate(augmented).claimStrength(augmented);
        assertTrue(sigmaAfter <= sigmaBefore + EPS,
                "Adding attacker must not raise sigma: before=" + sigmaBefore + " after=" + sigmaAfter);
    }

    // ── β ∈ {0, 1} boundary behavior ─────────────────────────────────────────────

    @Test
    @DisplayName("β=0, net support: σ = h(E) > 0")
    void betaZeroNetSupport() {
        Qbaf qbaf = withSupporter(0.0, 0.8);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        // σ = 0 + 1·h(0.8) - 0 = h(0.8) = 0.64/1.64 ≈ 0.3902
        double expected = QeSemantics.h(0.8);
        assertEquals(expected, sigma, EPS, "beta=0, net support → sigma=h(E)");
        assertTrue(sigma > 0.0, "sigma must be positive");
    }

    @Test
    @DisplayName("β=1, net attack: σ = 1 - h(-E) < 1")
    void betaOneNetAttack() {
        Qbaf qbaf = withAttacker(1.0, 0.6);
        double sigma = QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        // σ = 1 + 0·h(-0.6) - 1·h(0.6) = 1 - h(0.6) = 1 - 0.36/1.36 ≈ 0.7353
        double expected = 1.0 - QeSemantics.h(0.6);
        assertEquals(expected, sigma, EPS, "beta=1, net attack → sigma = 1 - h(0.6)");
        assertTrue(sigma < 1.0, "sigma must be strictly less than 1");
    }

    // ── Worked example (E10 shape, QE numbers) ───────────────────────────────────

    /**
     * Same QBAF structure as the DF-QuAD E10 worked example in {@code DfQuadPostulateTest}:
     *   claim β=0.5, PRO p1 β=0.9, CON c1 β=0.7 attacks p1, CON c2 β=0.665 attacks claim.
     *
     * <p>QE analytical derivation (fixed point reached in 2 sweeps for this acyclic graph):
     *
     * <p>c1 and c2 have no incoming edges → σ(c1)=0.7, σ(c2)=0.665 at fixed point.
     *
     * <p>p1 (attacked by c1 only):
     * <pre>
     *   E(p1) = -σ(c1) = -0.7
     *   h(E)  = h(-0.7) = 0                (negative input → 0)
     *   h(-E) = h(0.7)  = 0.49 / 1.49 ≈ 0.3288590...
     *   σ(p1) = 0.9 + (1-0.9)·0 - 0.9·0.3288590 = 0.9 - 0.2959731 ≈ 0.6040268...
     * </pre>
     *
     * <p>claim (supported by p1, attacked by c2):
     * <pre>
     *   E(claim) = σ(p1) - σ(c2) = 0.6040268 - 0.665 = -0.0609731...
     *   E is negative:
     *   h(E)  = h(-0.0609731) = 0
     *   h(-E) = h(0.0609731) = 0.0609731² / (1 + 0.0609731²)
     *         = 0.003717... / 1.003717... ≈ 0.003703...
     *   σ(claim) = 0.5 + (1-0.5)·0 - 0.5·0.003703 = 0.5 - 0.001851... ≈ 0.498148...
     * </pre>
     *
     * <p>QE result: σ(claim) ≈ 0.4981 → falls in the UNKNOWN band (0.35, 0.65).
     */
    @Test
    @DisplayName("Worked example (QE): PRO attacked by CON chain → UNKNOWN band, σ(claim) ≈ 0.4981")
    void workedExampleQeE10() {
        Qbaf qbaf = Qbaf.builder()
                .argument(Argument.claim("claim", "Is-X", 0.5))
                .argument(Argument.pro("p1", "evidence-A", 0.9))
                .argument(Argument.con("c1", "counter-A", 0.7))
                .argument(Argument.con("c2", "counter-B", 0.665))
                .support("p1", "claim")
                .attack ("c1", "p1")     // CON attacks the PRO
                .attack ("c2", "claim")
                .build();

        QeSemantics.Result res = QeSemantics.evaluate(qbaf);
        assertTrue(res.converged(), "Acyclic QBAF must converge");

        // Analytical values derived above
        double hOf07 = 0.49 / 1.49;   // h(0.7)
        double expectedSigmaP1 = 0.9 - 0.9 * hOf07;   // ≈ 0.6040268

        double sigmaP1 = res.strengths().get("p1");
        assertEquals(expectedSigmaP1, sigmaP1, 1e-6, "σ(p1) analytical value");

        // E(claim) = sigmaP1 - 0.665
        double eNeg       = 0.665 - sigmaP1;             // -E(claim) > 0
        double hOfNegE    = (eNeg * eNeg) / (1.0 + eNeg * eNeg);
        double expectedSigmaClaim = 0.5 - 0.5 * hOfNegE;    // ≈ 0.498148

        double sigmaClaim = res.claimStrength(qbaf);
        assertEquals(expectedSigmaClaim, sigmaClaim, 1e-6, "σ(claim) QE analytical value");

        // Must fall in UNKNOWN band
        assertTrue(sigmaClaim > 0.35 && sigmaClaim < 0.65,
                "QE claim strength " + sigmaClaim + " should be in UNKNOWN band (0.35, 0.65)");
    }

    // ── Smoothness / continuity ───────────────────────────────────────────────────

    /**
     * Key property for learning: a small base-score perturbation must produce a small strength change.
     * Tests that |σ(β+ε) - σ(β-ε)| is small for ε=1e-6 on a 3-node graph.
     */
    @Test
    @DisplayName("Smoothness: small β perturbation → small σ change (no jumps)")
    void smoothnessSmallPerturbation() {
        double eps = 1e-6;
        double prior = 0.5;
        double perturbedPriorUp   = prior + eps;
        double perturbedPriorDown = prior - eps;

        // 3-node: claim β=0.5 ± ε, PRO β=0.7, CON β=0.3
        Qbaf qbafNominal = Qbaf.builder()
                .argument(Argument.claim("c", "claim", prior))
                .argument(Argument.pro("s", "sup", 0.7))
                .argument(Argument.con("a", "att", 0.3))
                .support("s", "c")
                .attack ("a", "c")
                .build();

        Qbaf qbafUp = Qbaf.builder()
                .argument(Argument.claim("c", "claim", perturbedPriorUp))
                .argument(Argument.pro("s", "sup", 0.7))
                .argument(Argument.con("a", "att", 0.3))
                .support("s", "c")
                .attack ("a", "c")
                .build();

        Qbaf qbafDown = Qbaf.builder()
                .argument(Argument.claim("c", "claim", perturbedPriorDown))
                .argument(Argument.pro("s", "sup", 0.7))
                .argument(Argument.con("a", "att", 0.3))
                .support("s", "c")
                .attack ("a", "c")
                .build();

        double sigmaNom  = QeSemantics.evaluate(qbafNominal).claimStrength(qbafNominal);
        double sigmaUp   = QeSemantics.evaluate(qbafUp  ).claimStrength(qbafUp  );
        double sigmaDown = QeSemantics.evaluate(qbafDown).claimStrength(qbafDown);

        double diffUpDown = Math.abs(sigmaUp - sigmaDown);
        // Must be tiny — bounded by Lipschitz constant × 2ε; empirically << 1e-4
        assertTrue(diffUpDown < 1e-4,
                "Smoothness: |σ(β+ε) - σ(β-ε)| = " + diffUpDown + " should be < 1e-4 for ε=1e-6");

        // Also ensure the nominal value is between the perturbed ones (monotone in β for net support)
        double netE = 0.7 - 0.3;  // positive → σ increases with β
        if (netE > 0) {
            assertTrue(sigmaDown <= sigmaNom + 1e-9 && sigmaNom <= sigmaUp + 1e-9,
                    "σ should be monotone in β for net support: down=" + sigmaDown
                            + " nom=" + sigmaNom + " up=" + sigmaUp);
        }
    }

    // ── Convergence ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Convergence: acyclic chain converges quickly")
    void acyclicChainConverges() {
        Qbaf qbaf = Qbaf.builder()
                .argument(Argument.claim("c", "c", 0.5))
                .argument(Argument.pro("s", "s", 0.7))
                .argument(Argument.con("a", "a", 0.4))
                .support("s", "c")
                .attack ("a", "c")
                .build();
        QeSemantics.Result res = QeSemantics.evaluate(qbaf);
        assertTrue(res.converged(), "Acyclic QBAF must converge");
        assertTrue(res.iterations() <= 10, "Depth-2 QBAF should converge within 10 sweeps, got " + res.iterations());
    }

    @Test
    @DisplayName("Convergence: 2-cycle with damping converges")
    void twoCycleWithDampingConverges() {
        // c1 attacks c2, c2 attacks c1 → mutual destruction cycle
        // Using a claim that observes one of them to satisfy QBAF's CLAIM constraint
        Qbaf qbaf = Qbaf.builder()
                .argument(Argument.claim("c", "claim", 0.5))
                .argument(Argument.pro("p1", "p1", 0.7))
                .argument(Argument.con("p2", "p2", 0.6))
                .support("p1", "c")
                .attack ("p2", "c")
                .attack ("p1", "p2")   // p1 attacks p2 and is supported by p2 (cycle on non-claim)
                .attack ("p2", "p1")
                .build();

        // Without damping: may or may not converge (depends on cycle strength)
        QeSemantics qeNoDamp = new QeSemantics(1000, 1e-9, 1.0);
        QeSemantics.Result resNoDamp = qeNoDamp.evaluateQbaf(qbaf);
        // convergence is best-effort here; we just require no crash and a valid result
        assertNotNull(resNoDamp);

        // With strong damping: must converge
        QeSemantics qeDamped = new QeSemantics(2000, 1e-9, 0.5);
        QeSemantics.Result resDamped = qeDamped.evaluateQbaf(qbaf);
        assertTrue(resDamped.converged(),
                "2-cycle with damping=0.5 should converge; got " + resDamped.iterations() + " iterations");
        // All strengths in [0,1]
        for (double s : resDamped.strengths().values()) {
            assertTrue(s >= 0.0 && s <= 1.0, "Strength out of range: " + s);
        }
    }

    // ── Knob validation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("QeSemantics rejects dampingFactor <= 0 or > 1")
    void invalidDampingRejected() {
        assertThrows(IllegalArgumentException.class, () -> new QeSemantics(100, 1e-9, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new QeSemantics(100, 1e-9, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new QeSemantics(100, 1e-9, -0.1));
    }

    @Test
    @DisplayName("QeSemantics rejects maxIterations < 1")
    void invalidMaxIterationsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new QeSemantics(0, 1e-9, 0.5));
    }
}
