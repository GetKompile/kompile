/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Step 2 — ADMM MAP inference (Gap E-1).
 *
 * <p>Covers:
 * <ul>
 *   <li>ADMM reaches the same optimum as {@link ScalarHlMrfInference} within tolerance</li>
 *   <li>Hard constraint violation reporting via {@link HlMrfMapInference.Result#hardViolations()}</li>
 *   <li>ADMM handles arithmetic ground rules correctly</li>
 *   <li>Convergence flag, hard-weight handling, and basic sanity checks</li>
 * </ul>
 */
@DisplayName("Step 2 — ADMM MAP Inference")
class AdmmHlMrfInferenceTest {

    /**
     * Tolerance for comparing ADMM vs Scalar objectives.
     * Both solvers converge to the global optimum of the convex HL-MRF energy;
     * we verify the objective (energy) values match within tolerance, not the atom
     * values themselves (which can differ near the optimum when the energy landscape
     * is flat, i.e., multiple atom assignments have similar energy).
     */
    private static final double OBJECTIVE_TOLERANCE = 1e-2;

    // ─── Equivalence to ScalarHlMrfInference ─────────────────────────────────

    @Nested
    @DisplayName("ADMM vs Scalar solver equivalence")
    class EquivalenceTests {

        @Test
        @DisplayName("Simple propagation rule: ADMM reaches same energy as scalar")
        void simplePropagation() {
            PslProgram prog = makePropagationProgram();
            List<GroundRule> ground = prog.ground();

            ScalarHlMrfInference scalar = new ScalarHlMrfInference();
            AdmmHlMrfInference admm = new AdmmHlMrfInference();

            HlMrfMapInference.Result scalarResult = scalar.solve(prog, ground);
            HlMrfMapInference.Result admmResult = admm.solve(prog, ground);

            // Both should achieve nearly the same minimum energy (convex problem → unique optimum)
            assertEquals(scalarResult.objective(), admmResult.objective(), OBJECTIVE_TOLERANCE,
                    "ADMM and scalar should reach the same minimum energy");

            // Also verify atom values are in a reasonable neighbourhood (looser tolerance)
            for (String key : prog.targetKeys()) {
                double scalarVal = scalarResult.values().getOrDefault(key, 0.0);
                double admmVal = admmResult.values().getOrDefault(key, 0.0);
                assertEquals(scalarVal, admmVal, 0.1,
                        "ADMM and scalar atom values should be in same neighbourhood: " + key);
            }
        }

        @Test
        @DisplayName("Two overlapping rules: ADMM objective matches scalar within tolerance")
        void overlappingRules() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "e1")
                    .observe("B", 1.0, "e1")
                    .target("C", "e1")
                    .target("D", "e1")
                    .addRule("2.0: A(X) & B(X) -> C(X) ^2")
                    .addRule("1.0: C(X) -> D(X) ^2");

            List<GroundRule> ground = prog.ground();
            ScalarHlMrfInference scalar = new ScalarHlMrfInference();
            AdmmHlMrfInference admm = new AdmmHlMrfInference();

            HlMrfMapInference.Result scalarResult = scalar.solve(prog, ground);
            HlMrfMapInference.Result admmResult = admm.solve(prog, ground);

            // Both converge to the global optimum: objectives should be within OBJECTIVE_TOLERANCE
            assertEquals(scalarResult.objective(), admmResult.objective(), OBJECTIVE_TOLERANCE,
                    "ADMM and scalar should reach the same minimum energy");

            // Directional sanity: both should push C(e1) high (strong rule 2.0: A&B->C)
            double cAdmm = admmResult.values().getOrDefault("C(e1)", 0.0);
            assertTrue(cAdmm > 0.9, "Strong rule should push C(e1) > 0.9, got " + cAdmm);
        }

        @Test
        @DisplayName("Squared vs linear hinge: ADMM handles both")
        void squaredAndLinearHinge() {
            PslProgram prog = new PslProgram()
                    .observe("Cause", 0.9, "a")
                    .target("Effect", "b")
                    .addRule("1.0: Cause(X) -> Effect(Y) ^2")    // squared
                    .addRule("1.0: Effect(Y)");                    // linear prior

            List<GroundRule> ground = prog.ground();
            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            HlMrfMapInference.Result result = admm.solve(prog, ground);
            assertNotNull(result.values());
            assertFalse(result.values().isEmpty());
        }

        @Test
        @DisplayName("Negative prior pushes target toward 0")
        void negativePrior() {
            PslProgram prog = new PslProgram()
                    .target("Risky", "n1")
                    .addRule("1.0: ~Risky(N) ^2"); // push toward false

            List<GroundRule> ground = prog.ground();
            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            HlMrfMapInference.Result result = admm.solve(prog, ground);

            double v = result.values().getOrDefault("Risky(n1)", 1.0);
            assertTrue(v < 0.5, "Negative prior should push Risky(n1) below 0.5, got " + v);
        }
    }

    // ─── Hard constraint violation reporting ─────────────────────────────────

    @Nested
    @DisplayName("Hard-violation reporting")
    class HardViolationTests {

        @Test
        @DisplayName("hardViolations() returns empty when all hard rules satisfied")
        void noViolationsWhenSatisfied() {
            PslProgram prog = makePropagationProgram();
            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            // Default rules have no hard constraints
            assertTrue(result.hardViolations().isEmpty());
        }

        @Test
        @DisplayName("hardViolations() returns rules that remain violated after inference")
        void violationsReported() {
            // Hard constraint: A(x) & B(x) -> C(x). If A and B are observed=1 but C is observed=0
            // the rule is violated by construction even after inference (C is fixed observed).
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("B", 1.0, "x")
                    .observe("C", 0.0, "x") // deliberately false
                    .addRule("A(X) & B(X) -> C(X) .");  // hard constraint

            List<GroundRule> ground = prog.ground();
            assertEquals(1, ground.size());
            assertTrue(ground.get(0).hard(), "Rule should be hard");

            HlMrfMapInference.Result result = new AdmmHlMrfInference()
                    .solve(prog, ground);
            // C is observed=0 and cannot move, so the constraint is violated
            List<GroundRule> violations = result.hardViolations();
            assertFalse(violations.isEmpty(),
                    "Should report at least one hard violation when C is pinned to 0");
        }

        @Test
        @DisplayName("hardViolations() tolerance: near-zero distance not reported")
        void nearZeroDistanceNotViolation() {
            // A=1, C=1: hard rule A(x)->C(x) is satisfied (d=0)
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("C", 1.0, "x")
                    .addRule("A(X) -> C(X) .");

            HlMrfMapInference.Result result = new ScalarHlMrfInference()
                    .solve(prog, prog.ground());
            assertTrue(result.hardViolations().isEmpty(),
                    "No violation when hard rule is satisfied");
        }
    }

    // ─── ADMM with arithmetic rules ───────────────────────────────────────────

    @Nested
    @DisplayName("ADMM with arithmetic constraints")
    class AdmmArithmeticTests {

        @Test
        @DisplayName("ADMM solves mutual-exclusion arithmetic constraint")
        void admmMutualExclusion() {
            PslProgram prog = new PslProgram()
                    .target("TypeA", "e1")
                    .target("TypeB", "e1")
                    .addArithmeticRule("1.0: TypeA(X) + TypeB(X) <= 1 ^2");

            List<GroundRule> logical = prog.ground();
            List<ArithmeticGroundRule> arith = prog.groundArithmetic();
            assertEquals(1, arith.size());

            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            HlMrfMapInference.Result result = admm.solve(prog, logical, arith, 10000, 1e-6, 1e6);

            double a = result.values().getOrDefault("TypeA(e1)", 0.0);
            double b = result.values().getOrDefault("TypeB(e1)", 0.0);
            // After optimisation, sum should be <= 1
            assertTrue(a + b <= 1.01, "ME violated: " + a + " + " + b);
        }

        @Test
        @DisplayName("ADMM result values are all in [0,1]")
        void resultValuesInRange() {
            PslProgram prog = makePropagationProgram();
            AdmmHlMrfInference admm = new AdmmHlMrfInference();
            HlMrfMapInference.Result result = admm.solve(prog, prog.ground());
            for (Map.Entry<String, Double> e : result.values().entrySet()) {
                assertTrue(e.getValue() >= -1e-9 && e.getValue() <= 1.0 + 1e-9,
                        "Value out of [0,1]: " + e.getKey() + "=" + e.getValue());
            }
        }
    }

    // ─── HlMrfMapInference router ─────────────────────────────────────────────

    @Nested
    @DisplayName("HlMrfMapInference.chooseSolver routing")
    class RouterTests {

        @Test
        @DisplayName("Very small programs use ScalarHlMrfInference (< scalar threshold)")
        void smallProgramsUseScalar() {
            HlMrfSolver solver = HlMrfMapInference.chooseSolver(10, 20);
            assertInstanceOf(ScalarHlMrfInference.class, solver);
        }

        @Test
        @DisplayName("Mid-scale programs use AdmmHlMrfInference (scalar threshold–4000 rules)")
        void midScaleUsesAdmm() {
            HlMrfSolver solver = HlMrfMapInference.chooseSolver(
                    HlMrfMapInference.DEFAULT_SCALAR_THRESHOLD + 100, 100);
            assertInstanceOf(AdmmHlMrfInference.class, solver);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private PslProgram makePropagationProgram() {
        return new PslProgram()
                .observe("Knows", 1.0, "alice", "bob")
                .observe("Knows", 0.8, "bob", "carol")
                .target("Knows", "alice", "carol")
                .target("Likes", "alice", "carol")
                .addRule("1.0: Knows(A, B) & Knows(B, C) -> Knows(A, C) ^2")
                .addRule("0.5: Knows(A, B) -> Likes(A, B) ^2");
    }
}
