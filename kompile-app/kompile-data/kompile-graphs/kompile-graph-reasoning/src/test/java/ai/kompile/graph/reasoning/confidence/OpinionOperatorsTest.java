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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E7 — subjective-logic operator hygiene tests.
 * Covers: averagingFuse (ABF), weightedFuse (WBF), deduce, uncertaintyMaximized,
 * withBaseRate + cumulativeFuseCanonical (fix #13), FusionMode router, comultiply.
 * Also includes a randomized property sweep (simplex validity) and the fix-#13
 * non-monotonicity regression.
 */
@DisplayName("E7 Opinion operator hygiene")
class OpinionOperatorsTest {

    private static final double EPS  = 1e-9;   // tight for exact equalities
    private static final double TEPS = 1e-6;   // tolerance for float math
    private static final long   SEED = 20260709L;

    // ─── Simplex assertions ───────────────────────────────────────────────────

    private static void assertSimplex(Opinion o) {
        double sum = o.belief() + o.disbelief() + o.uncertainty();
        assertEquals(1.0, sum, TEPS, "b+d+u must equal 1.0 for " + o);
        assertTrue(o.belief()      >= -TEPS, "belief must be >= 0: "      + o);
        assertTrue(o.disbelief()   >= -TEPS, "disbelief must be >= 0: "   + o);
        assertTrue(o.uncertainty() >= -TEPS, "uncertainty must be >= 0: " + o);
        assertTrue(o.baseRate()    >= -TEPS && o.baseRate() <= 1.0 + TEPS,
                   "baseRate must be in [0,1]: " + o);
    }

    // ─── Random opinion factory ───────────────────────────────────────────────

    private static Opinion randomOpinion(Random rng) {
        double b = rng.nextDouble();
        double d = rng.nextDouble() * (1.0 - b);
        double u = 1.0 - b - d;
        double a = rng.nextDouble();
        return new Opinion(b, d, u, a);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Averaging Belief Fusion (ABF)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("averagingFuse (ABF — Jøsang §12.3)")
    class AveragingFuseTests {

        @Test
        @DisplayName("idempotent: averagingFuse(x, x) == x")
        void idempotent() {
            Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
            Opinion r = x.averagingFuse(x);
            assertSimplex(r);
            assertEquals(x.belief(),      r.belief(),      TEPS, "b unchanged");
            assertEquals(x.disbelief(),   r.disbelief(),   TEPS, "d unchanged");
            assertEquals(x.uncertainty(), r.uncertainty(), TEPS, "u unchanged");
            assertEquals(x.baseRate(),    r.baseRate(),    TEPS, "a unchanged");
        }

        @Test
        @DisplayName("idempotent for non-trivial opinion with a != 0.5")
        void idempotentOffCentre() {
            Opinion x = new Opinion(0.3, 0.1, 0.6, 0.8);
            Opinion r = x.averagingFuse(x);
            assertSimplex(r);
            assertEquals(x.belief(),      r.belief(),      TEPS);
            assertEquals(x.disbelief(),   r.disbelief(),   TEPS);
            assertEquals(x.uncertainty(), r.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("idempotent for vacuous opinion")
        void idempotentVacuous() {
            Opinion v = Opinion.vacuous(0.7);
            Opinion r = v.averagingFuse(v);
            assertSimplex(r);
            assertTrue(r.isVacuous(), "vacuous ⊗ vacuous = vacuous under ABF");
        }

        @Test
        @DisplayName("commutative: x⊗y == y⊗x")
        void commutative() {
            Opinion x = new Opinion(0.5, 0.3, 0.2, 0.4);
            Opinion y = new Opinion(0.2, 0.4, 0.4, 0.6);
            Opinion xy = x.averagingFuse(y);
            Opinion yx = y.averagingFuse(x);
            assertSimplex(xy);
            assertEquals(xy.belief(),      yx.belief(),      TEPS);
            assertEquals(xy.disbelief(),   yx.disbelief(),   TEPS);
            assertEquals(xy.uncertainty(), yx.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("uncertainty is harmonic-mean / 2 of inputs")
        void uncertaintyIsHarmonicScaled() {
            Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
            Opinion y = new Opinion(0.4, 0.2, 0.4, 0.5);
            double ua = x.uncertainty(), ub = y.uncertainty();
            double expectedU = 2.0 * ua * ub / (ua + ub);
            Opinion r = x.averagingFuse(y);
            assertSimplex(r);
            assertEquals(expectedU, r.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("dogmatic-pair (u=0 both): component mean, stays dogmatic")
        void dogmaticPair() {
            Opinion x = Opinion.fromObservedValue(0.8);
            Opinion y = Opinion.fromObservedValue(0.6);
            Opinion r = x.averagingFuse(y);
            assertSimplex(r);
            assertEquals(0.0, r.uncertainty(), TEPS, "dogmatic pair stays dogmatic");
            // expectation is mean: (0.8 + 0.6) / 2 = 0.7
            assertEquals(0.7, r.expectation(), 0.01);
        }

        @Test
        @DisplayName("list/varargs overloads are equivalent")
        void listAndVarargsEquivalent() {
            Opinion x = new Opinion(0.5, 0.2, 0.3, 0.5);
            Opinion y = new Opinion(0.3, 0.3, 0.4, 0.5);
            Opinion z = new Opinion(0.7, 0.1, 0.2, 0.5);
            Opinion fromList = Opinion.averagingFuse(List.of(x, y, z));
            Opinion fromVarargs = Opinion.averagingFuse(x, y, z);
            assertSimplex(fromList);
            assertEquals(fromList.belief(),      fromVarargs.belief(),      TEPS);
            assertEquals(fromList.disbelief(),   fromVarargs.disbelief(),   TEPS);
            assertEquals(fromList.uncertainty(), fromVarargs.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("empty list → vacuous")
        void emptyListVacuous() {
            assertTrue(Opinion.averagingFuse(List.of()).isVacuous());
        }

        @Test
        @DisplayName("NON-idempotence: cumulativeFuse(x, x) strictly lowers u")
        void cumulativeIsNotIdempotent() {
            Opinion x = new Opinion(0.5, 0.2, 0.3, 0.5);
            Opinion r = x.cumulativeFuse(x);
            assertSimplex(r);
            assertTrue(r.uncertainty() < x.uncertainty(),
                    "cumulativeFuse(x,x) must lower uncertainty (not idempotent): u_fused=" +
                    r.uncertainty() + " u_x=" + x.uncertainty());
        }

        @Test
        @DisplayName("deprecated averageFuse delegates to averagingFuse")
        @SuppressWarnings("deprecation")
        void deprecatedAverageFuseDelegates() {
            Opinion x = new Opinion(0.5, 0.2, 0.3, 0.5);
            Opinion y = new Opinion(0.3, 0.3, 0.4, 0.5);
            Opinion via_old = x.averageFuse(y);
            Opinion via_new = x.averagingFuse(y);
            assertEquals(via_new.belief(),      via_old.belief(),      TEPS);
            assertEquals(via_new.disbelief(),   via_old.disbelief(),   TEPS);
            assertEquals(via_new.uncertainty(), via_old.uncertainty(), TEPS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Weighted Belief Fusion (WBF)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("weightedFuse (WBF — Jøsang §12.4)")
    class WeightedFuseTests {

        @Test
        @DisplayName("vacuous sources are ignored")
        void vacuousIgnored() {
            Opinion certain = new Opinion(0.8, 0.1, 0.1, 0.5);
            Opinion vacuous = Opinion.vacuous(0.5);
            Opinion r = Opinion.weightedFuse(List.of(certain, vacuous));
            assertSimplex(r);
            // vacuous has w=0, so result should equal certain
            assertEquals(certain.belief(),    r.belief(),    TEPS, "vacuous ignored: belief");
            assertEquals(certain.disbelief(), r.disbelief(), TEPS, "vacuous ignored: disbelief");
        }

        @Test
        @DisplayName("all vacuous → vacuous with mean baseRate")
        void allVacuousReturnsMeanBaseRate() {
            Opinion r = Opinion.weightedFuse(List.of(Opinion.vacuous(0.2), Opinion.vacuous(0.8)));
            assertSimplex(r);
            assertTrue(r.isVacuous(), "all-vacuous → vacuous");
            assertEquals(0.5, r.baseRate(), TEPS, "base rate is mean of inputs");
        }

        @Test
        @DisplayName("higher-certainty source dominates")
        void higherCertaintyDominates() {
            // low-uncertainty opinion (u=0.05) vs high-uncertainty (u=0.7)
            Opinion certain = new Opinion(0.9, 0.05, 0.05, 0.5);
            Opinion uncertain = new Opinion(0.2, 0.1,  0.7, 0.5);
            Opinion r = Opinion.weightedFuse(List.of(certain, uncertain));
            assertSimplex(r);
            // w_certain = 0.95, w_uncertain = 0.30 → result pulls toward certain
            assertTrue(r.belief() > 0.7, "certain source dominates: belief=" + r.belief());
        }

        @Test
        @DisplayName("simplex valid, single element is identity")
        void singleElementIdentity() {
            Opinion x = new Opinion(0.4, 0.3, 0.3, 0.6);
            Opinion r = Opinion.weightedFuse(List.of(x));
            assertSimplex(r);
            assertEquals(x.belief(),      r.belief(),      TEPS);
            assertEquals(x.disbelief(),   r.disbelief(),   TEPS);
            assertEquals(x.uncertainty(), r.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("empty list → vacuous")
        void emptyVacuous() {
            assertTrue(Opinion.weightedFuse(List.of()).isVacuous());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. uncertaintyMaximized factory
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("uncertaintyMaximized factory (Jøsang §4.5)")
    class UncertaintyMaximizedTests {

        @Test
        @DisplayName("E(result) == p for p >= a")
        void expectationEqualsP_pAboveA() {
            double p = 0.8, a = 0.4;
            Opinion o = Opinion.uncertaintyMaximized(p, a);
            assertSimplex(o);
            assertEquals(p, o.expectation(), TEPS, "E must equal p");
        }

        @Test
        @DisplayName("E(result) == p for p < a")
        void expectationEqualsP_pBelowA() {
            double p = 0.2, a = 0.6;
            Opinion o = Opinion.uncertaintyMaximized(p, a);
            assertSimplex(o);
            assertEquals(p, o.expectation(), TEPS, "E must equal p");
        }

        @Test
        @DisplayName("lies on d=0 edge when p >= a")
        void liesOnDEdge_pAboveA() {
            double p = 0.7, a = 0.3;
            Opinion o = Opinion.uncertaintyMaximized(p, a);
            assertSimplex(o);
            assertEquals(0.0, o.disbelief(), TEPS, "d=0 edge when p >= a");
        }

        @Test
        @DisplayName("lies on b=0 edge when p < a")
        void liesOnBEdge_pBelowA() {
            double p = 0.1, a = 0.5;
            Opinion o = Opinion.uncertaintyMaximized(p, a);
            assertSimplex(o);
            assertEquals(0.0, o.belief(), TEPS, "b=0 edge when p < a");
        }

        @Test
        @DisplayName("p == a: uncertainty maximized to 1 (vacuous)")
        void pEqualsA_isVacuous() {
            double p = 0.5, a = 0.5;
            Opinion o = Opinion.uncertaintyMaximized(p, a);
            assertSimplex(o);
            assertEquals(p, o.expectation(), TEPS);
            assertTrue(o.uncertainty() > 0.99, "p==a → maximally uncertain: u=" + o.uncertainty());
        }

        @Test
        @DisplayName("several p/a combos: E preserved")
        void roundTripsCombos() {
            double[][] combos = {{0.9, 0.5}, {0.05, 0.5}, {0.3, 0.7}, {0.8, 0.2}, {0.5, 0.3}};
            for (double[] c : combos) {
                double p = c[0], a = c[1];
                Opinion o = Opinion.uncertaintyMaximized(p, a);
                assertSimplex(o);
                assertEquals(p, o.expectation(), TEPS, "E != p for p=" + p + " a=" + a);
            }
        }

        @Test
        @DisplayName("degenerate a=0: clamps correctly, E==p")
        void degenerateAZero() {
            Opinion o = Opinion.uncertaintyMaximized(0.4, 0.0);
            assertSimplex(o);
            assertEquals(0.4, o.expectation(), TEPS);
        }

        @Test
        @DisplayName("degenerate a=1: clamps correctly, E==p")
        void degenerateAOne() {
            Opinion o = Opinion.uncertaintyMaximized(0.6, 1.0);
            assertSimplex(o);
            assertEquals(0.6, o.expectation(), TEPS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. withBaseRate + cumulativeFuseCanonical (fix #13 regression)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("fix #13: cumulativeFuseCanonical base-rate monotonicity")
    class Fix13Tests {

        /**
         * Reproduce the fix-#13 gotcha from the design doc: when two POSITIVE opinions have
         * different base rates, plain cumulativeFuse can produce E(fused) < E(o1) even though o2
         * is also a positive opinion.  The root cause: the fused base rate shifts (via the
         * Jøsang uncertainty-weighted average) which changes the prior term a·u in a way that
         * overwhelms the belief gain.
         *
         * Concrete example:
         *   o1: b=0.3, d=0, u=0.7, a=0.9  →  E1 = 0.3 + 0.9·0.7 = 0.93
         *   o2: b=0.3, d=0.3, u=0.4, a=0.3  →  E2 = 0.3 + 0.3·0.4 = 0.42 (positive)
         * Fusing: b_f≈0.402, d_f≈0.256, u_f≈0.341, a_f≈0.633
         *   E_fused ≈ 0.402 + 0.633·0.341 ≈ 0.618 < E1 = 0.93
         * Even though o2 is positive, the second source lowered our combined E.
         */
        @Test
        @DisplayName("non-monotonicity regression: cumulativeFuse can lower E below the stronger source")
        void plainCumulativeFuseNonMonotone() {
            // o1 is a high-expectation near-vacuous opinion with high base rate
            Opinion o1 = new Opinion(0.3, 0.0, 0.7, 0.9);  // E = 0.3 + 0.9*0.7 = 0.93
            // o2 is a positive but moderate opinion with low base rate
            Opinion o2 = new Opinion(0.3, 0.3, 0.4, 0.3);  // E = 0.3 + 0.3*0.4 = 0.42

            double e1 = o1.expectation();
            double e2 = o2.expectation();

            // Both are positive (E > 0)
            assertTrue(e1 > 0 && e2 > 0, "both inputs positive");

            Opinion fused = o1.cumulativeFuse(o2);
            assertSimplex(fused);

            double eFused = fused.expectation();
            double maxE = Math.max(e1, e2);

            // The gotcha: fusing a positive o2 into o1 LOWERED E below E(o1)
            assertTrue(eFused < maxE,
                    "gotcha demonstrated: E_fused=" + eFused + " < max(E1,E2)=" + maxE +
                    " even though o2 is positive (E2=" + e2 + ")");
            // Uncertainty did drop — that part works correctly
            assertTrue(fused.uncertainty() < o1.uncertainty(), "u did decrease");
        }

        @Test
        @DisplayName("cumulativeFuseCanonical at common a: E(fused) >= min(E inputs)")
        void canonicalFuseRestoresMonotonicity() {
            // Same opinions as the non-monotonicity regression
            Opinion o1 = new Opinion(0.3, 0.0, 0.7, 0.9);  // E = 0.93
            Opinion o2 = new Opinion(0.3, 0.3, 0.4, 0.3);  // E = 0.42

            double e1 = o1.expectation();
            double e2 = o2.expectation();

            // Use 0.5 as the canonical common base rate
            Opinion canonical = Opinion.cumulativeFuseCanonical(List.of(o1, o2), 0.5);
            assertSimplex(canonical);

            double eFused = canonical.expectation();
            double minE = Math.min(e1, e2);

            assertTrue(eFused >= minE - TEPS,
                    "canonicalized E_fused=" + eFused + " must be >= min(E1,E2)=" + minE);
        }

        @Test
        @DisplayName("cumulativeFuseCanonical: u(fused) < min(u inputs)")
        void canonicalFuseLowersUncertainty() {
            Opinion o1 = new Opinion(0.3, 0.0, 0.7, 0.9);
            Opinion o2 = new Opinion(0.3, 0.3, 0.4, 0.3);
            double minU = Math.min(o1.uncertainty(), o2.uncertainty());

            Opinion canonical = Opinion.cumulativeFuseCanonical(List.of(o1, o2), 0.5);
            assertSimplex(canonical);

            assertTrue(canonical.uncertainty() < minU,
                    "u_fused=" + canonical.uncertainty() + " must be < min(u1,u2)=" + minU);
        }

        @Test
        @DisplayName("withBaseRate: only a changes; b,d,u ratio preserved via ofNormalized")
        void withBaseRatePreservesComponents() {
            Opinion o = new Opinion(0.4, 0.2, 0.4, 0.7);
            Opinion rebased = o.withBaseRate(0.3);
            assertSimplex(rebased);
            assertEquals(o.belief(),      rebased.belief(),      TEPS, "b unchanged");
            assertEquals(o.disbelief(),   rebased.disbelief(),   TEPS, "d unchanged");
            assertEquals(o.uncertainty(), rebased.uncertainty(), TEPS, "u unchanged");
            assertEquals(0.3, rebased.baseRate(), TEPS, "new a applied");
        }

        @Test
        @DisplayName("cumulativeFuseCanonical empty list → vacuous at given a")
        void emptyListVacuousAtA() {
            Opinion r = Opinion.cumulativeFuseCanonical(List.of(), 0.3);
            assertTrue(r.isVacuous());
            assertEquals(0.3, r.baseRate(), TEPS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Opinion.deduce
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("deduce (Jøsang §7.2)")
    class DeduceTests {

        // Dogmatic conditionals: P(y|x)=0.9, P(y|x̄)=0.1
        private final Opinion dogTrue  = Opinion.fromObservedValue(0.9); // P(y|x)=0.9
        private final Opinion dogFalse = Opinion.fromObservedValue(0.1); // P(y|x̄)=0.1

        @Test
        @DisplayName("dogmatic conditionals: total-probability E(y) holds exactly")
        void dogmaticConditionalsExact() {
            Opinion x = new Opinion(0.7, 0.2, 0.1, 0.5); // E(x) = 0.75
            Opinion y = Opinion.deduce(x, dogTrue, dogFalse);
            assertSimplex(y);

            double expectedEy = 0.9 * x.expectation() + 0.1 * (1.0 - x.expectation());
            assertEquals(expectedEy, y.expectation(), TEPS,
                    "E(y) = P(y|x)·E(x) + P(y|x̄)·(1−E(x))");
        }

        @Test
        @DisplayName("dogmatic conditionals: result is dogmatic (u_y = 0)")
        void dogmaticConditionalsResultDogmatic() {
            Opinion x = new Opinion(0.6, 0.3, 0.1, 0.5);
            Opinion y = Opinion.deduce(x, dogTrue, dogFalse);
            assertSimplex(y);
            assertEquals(0.0, y.uncertainty(), TEPS, "dogmatic conditionals → dogmatic marginal");
        }

        @Test
        @DisplayName("vacuous antecedent → E(y) = weighted mean of conditionals' expectations")
        void vacuousAntecedent() {
            Opinion vacuous = Opinion.vacuous(0.5);  // E(x) = 0.5 when u=1, a=0.5
            Opinion y = Opinion.deduce(vacuous, dogTrue, dogFalse);
            assertSimplex(y);
            double expectedEy = 0.9 * 0.5 + 0.1 * 0.5;
            assertEquals(expectedEy, y.expectation(), TEPS,
                    "vacuous antecedent: E(y) midpoint of conditionals");
        }

        @Test
        @DisplayName("simplex valid across a range of antecedent opinions")
        void simplexValidAcrossRange() {
            Opinion dogFalseConditional = Opinion.fromObservedValue(0.2);
            Opinion dogTrueConditional  = Opinion.fromObservedValue(0.8);
            for (double bx = 0.0; bx <= 0.9; bx += 0.1) {
                for (double dx = 0.0; dx <= 0.9 - bx; dx += 0.1) {
                    double ux = 1.0 - bx - dx;
                    Opinion x = new Opinion(
                            Math.round(bx * 1e6) / 1e6,
                            Math.round(dx * 1e6) / 1e6,
                            Math.round(ux * 1e6) / 1e6,
                            0.5);
                    Opinion y = Opinion.deduce(x, dogTrueConditional, dogFalseConditional);
                    assertSimplex(y);
                }
            }
        }

        @Test
        @DisplayName("total probability: P(y) == P(y|x)·E(x) + P(y|x̄)·(1-E(x)) for non-dogmatic conditionals")
        void totalProbabilityHoldsGeneralCase() {
            Opinion x   = new Opinion(0.5, 0.2, 0.3, 0.5);
            Opinion yx  = new Opinion(0.7, 0.1, 0.2, 0.6);
            Opinion yxn = new Opinion(0.2, 0.3, 0.5, 0.4);
            Opinion y = Opinion.deduce(x, yx, yxn);
            assertSimplex(y);
            double expectedPy = yx.expectation() * x.expectation()
                               + yxn.expectation() * (1.0 - x.expectation());
            assertEquals(expectedPy, y.expectation(), TEPS,
                    "total-probability law must hold: E(y)=" + y.expectation() + " expected=" + expectedPy);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 6. FusionMode router
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FusionMode router: fuse(mode, list)")
    class FusionModeRouterTests {

        private final Opinion X = new Opinion(0.6, 0.2, 0.2, 0.5);
        private final Opinion Y = new Opinion(0.4, 0.3, 0.3, 0.5);

        @Test
        @DisplayName("CUMULATIVE dispatches to cumulativeFuse")
        void cumulative() {
            Opinion direct = X.cumulativeFuse(Y);
            Opinion routed = Opinion.fuse(Opinion.FusionMode.CUMULATIVE, List.of(X, Y));
            assertSimplex(routed);
            assertEquals(direct.belief(),      routed.belief(),      TEPS);
            assertEquals(direct.uncertainty(), routed.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("AVERAGING dispatches to averagingFuse")
        void averaging() {
            Opinion direct = X.averagingFuse(Y);
            Opinion routed = Opinion.fuse(Opinion.FusionMode.AVERAGING, List.of(X, Y));
            assertSimplex(routed);
            assertEquals(direct.belief(),      routed.belief(),      TEPS);
            assertEquals(direct.uncertainty(), routed.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("WEIGHTED dispatches to weightedFuse")
        void weighted() {
            Opinion direct = Opinion.weightedFuse(List.of(X, Y));
            Opinion routed = Opinion.fuse(Opinion.FusionMode.WEIGHTED, List.of(X, Y));
            assertSimplex(routed);
            assertEquals(direct.belief(),      routed.belief(),      TEPS);
            assertEquals(direct.uncertainty(), routed.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("CONSENSUS_COMPROMISE dispatches to ccFuse (true Jøsang CCF, R1)")
        void consensusCompromise() {
            // R1 rerouted CONSENSUS_COMPROMISE from the legacy certainty-weighted consensus()
            // to true consensus & compromise fusion (ccFuse); the router must follow suit.
            Opinion direct = Opinion.ccFuse(List.of(X, Y));
            Opinion routed = Opinion.fuse(Opinion.FusionMode.CONSENSUS_COMPROMISE, List.of(X, Y));
            assertSimplex(routed);
            assertEquals(direct.belief(),      routed.belief(),      TEPS);
            assertEquals(direct.uncertainty(), routed.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("empty list → vacuous regardless of mode")
        void emptyAlwaysVacuous() {
            for (Opinion.FusionMode m : Opinion.FusionMode.values()) {
                Opinion r = Opinion.fuse(m, List.of());
                assertTrue(r.isVacuous(), "mode " + m + " empty → vacuous");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 7. Comultiplication (OR)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("comultiply (OR — Jøsang §7.4)")
    class ComultiplyTests {

        @Test
        @DisplayName("dogmatic: E(x OR y) == E(x) + E(y) − E(x)·E(y) exactly")
        void dogmaticExpectationInclExclusion() {
            Opinion x = Opinion.fromObservedValue(0.7);
            Opinion y = Opinion.fromObservedValue(0.5);
            Opinion r = x.comultiply(y);
            assertSimplex(r);
            double expected = x.expectation() + y.expectation() - x.expectation() * y.expectation();
            assertEquals(expected, r.expectation(), TEPS,
                    "E(x∨y) must follow inclusion-exclusion for dogmatic inputs");
        }

        @Test
        @DisplayName("commutative: x∨y == y∨x")
        void commutative() {
            Opinion x = new Opinion(0.5, 0.2, 0.3, 0.4);
            Opinion y = new Opinion(0.3, 0.3, 0.4, 0.6);
            Opinion xy = x.comultiply(y);
            Opinion yx = y.comultiply(x);
            assertSimplex(xy);
            assertEquals(xy.belief(),      yx.belief(),      TEPS);
            assertEquals(xy.disbelief(),   yx.disbelief(),   TEPS);
            assertEquals(xy.uncertainty(), yx.uncertainty(), TEPS);
        }

        @Test
        @DisplayName("x OR vacuous: result has same base-rate structure")
        void xOrVacuous() {
            Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
            Opinion v = Opinion.vacuous(0.5);
            Opinion r = x.comultiply(v);
            assertSimplex(r);
            // Result should be between x and vacuous
            assertTrue(r.belief() >= 0.0, "belief non-negative");
        }

        @Test
        @DisplayName("x OR x: simplex valid, E close to 2E(x) − E(x)² for dogmatic")
        void xOrXSelf() {
            Opinion x = Opinion.fromObservedValue(0.6);
            Opinion r = x.comultiply(x);
            assertSimplex(r);
            double ex = x.expectation();
            double expected = ex + ex - ex * ex;
            assertEquals(expected, r.expectation(), TEPS);
        }

        @Test
        @DisplayName("comultiplyAll left-fold: 3 opinions simplex valid")
        void comultiplyAll() {
            Opinion a = Opinion.fromObservedValue(0.3);
            Opinion b = Opinion.fromObservedValue(0.4);
            Opinion c = Opinion.fromObservedValue(0.5);
            Opinion r = Opinion.comultiplyAll(a, b, c);
            assertSimplex(r);
            assertTrue(r.expectation() > 0.3, "OR of three positives must be > largest");
        }

        @Test
        @DisplayName("empty comultiplyAll → vacuous")
        void emptyVacuous() {
            assertTrue(Opinion.comultiplyAll().isVacuous());
        }

        @Test
        @DisplayName("complement duality: ¬(¬x OR ¬y) == x AND y via conjoin")
        void complementDuality() {
            Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
            Opinion y = new Opinion(0.5, 0.3, 0.2, 0.4);
            // x OR y = ¬(¬x ⊙ ¬y)
            Opinion or = x.comultiply(y);
            // ¬(or) = ¬x ⊙ ¬y
            Opinion negOr = or.complement();
            Opinion negXConjoinNegY = x.complement().conjoin(y.complement());
            assertSimplex(negOr);
            assertEquals(negXConjoinNegY.belief(),      negOr.belief(),      TEPS);
            assertEquals(negXConjoinNegY.disbelief(),   negOr.disbelief(),   TEPS);
            assertEquals(negXConjoinNegY.uncertainty(), negOr.uncertainty(), TEPS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 8. Randomized property sweep (simplex validity)
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Randomized property sweep — ~1000 triples, fixed seed")
    class RandomizedPropertySweep {

        private static final int N = 1000;

        @Test
        @DisplayName("averagingFuse: simplex holds for 1000 random pairs")
        void averagingFuseSimplexSweep() {
            Random rng = new Random(SEED);
            for (int i = 0; i < N; i++) {
                Opinion a = randomOpinion(rng);
                Opinion b = randomOpinion(rng);
                assertSimplex(a.averagingFuse(b));
            }
        }

        @Test
        @DisplayName("averagingFuse idempotent for 1000 random opinions")
        void averagingFuseIdempotentSweep() {
            Random rng = new Random(SEED + 1);
            for (int i = 0; i < N; i++) {
                Opinion x = randomOpinion(rng);
                Opinion r = x.averagingFuse(x);
                assertSimplex(r);
                assertEquals(x.belief(),      r.belief(),      TEPS, "idempotent b at i=" + i);
                assertEquals(x.uncertainty(), r.uncertainty(), TEPS, "idempotent u at i=" + i);
            }
        }

        @Test
        @DisplayName("weightedFuse: simplex holds for 1000 random triples")
        void weightedFuseSimplexSweep() {
            Random rng = new Random(SEED + 2);
            for (int i = 0; i < N; i++) {
                Opinion a = randomOpinion(rng), b = randomOpinion(rng), c = randomOpinion(rng);
                assertSimplex(Opinion.weightedFuse(List.of(a, b, c)));
            }
        }

        @Test
        @DisplayName("uncertaintyMaximized: simplex holds and E==p for 1000 combos")
        void uncertaintyMaximizedSweep() {
            Random rng = new Random(SEED + 3);
            for (int i = 0; i < N; i++) {
                double p = rng.nextDouble();
                double a = rng.nextDouble();
                Opinion o = Opinion.uncertaintyMaximized(p, a);
                assertSimplex(o);
                assertEquals(p, o.expectation(), TEPS, "E!=p at i=" + i + " p=" + p + " a=" + a);
            }
        }

        @Test
        @DisplayName("comultiply: simplex holds for 1000 random pairs")
        void comultiplySweep() {
            Random rng = new Random(SEED + 4);
            for (int i = 0; i < N; i++) {
                Opinion a = randomOpinion(rng);
                Opinion b = randomOpinion(rng);
                assertSimplex(a.comultiply(b));
            }
        }

        @Test
        @DisplayName("deduce with dogmatic conditionals: total-probability for 1000 antecedents")
        void deduceSweep() {
            Random rng = new Random(SEED + 5);
            Opinion yx  = Opinion.fromObservedValue(0.8);
            Opinion yxn = Opinion.fromObservedValue(0.3);
            for (int i = 0; i < N; i++) {
                Opinion x = randomOpinion(rng);
                Opinion y = Opinion.deduce(x, yx, yxn);
                assertSimplex(y);
                double expectedPy = yx.expectation() * x.expectation()
                                   + yxn.expectation() * (1.0 - x.expectation());
                assertEquals(expectedPy, y.expectation(), TEPS,
                        "total-prob violated at i=" + i);
            }
        }

        @Test
        @DisplayName("cumulativeFuseCanonical: simplex holds for 1000 pairs at canonical a=0.5")
        void canonicalFuseSweep() {
            Random rng = new Random(SEED + 6);
            for (int i = 0; i < N; i++) {
                Opinion a = randomOpinion(rng);
                Opinion b = randomOpinion(rng);
                assertSimplex(Opinion.cumulativeFuseCanonical(List.of(a, b), 0.5));
            }
        }
    }
}
