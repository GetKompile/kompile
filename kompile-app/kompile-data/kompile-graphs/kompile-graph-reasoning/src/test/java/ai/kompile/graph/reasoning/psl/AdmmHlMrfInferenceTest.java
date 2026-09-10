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
        @DisplayName("ADMM residuals use stacked-copy reference counts")
        void dualResidualUsesStackedReferenceCount() {
            double[] zNew = {0.2, 0.5};
            double[] z = {0.0, 0.4};
            int[] refCount = {1, 3};

            // rho² * (1 * 0.2² + 3 * 0.1²) = 0.28.
            assertEquals(0.28, AdmmHlMrfInference.dualResidual2(
                    2.0, zNew, z, refCount), 1.0e-12);
        }

        @Test
        @DisplayName("Opposing local dual copies do not cancel in the stacked norm")
        void dualNormDoesNotAggregateByAtom() {
            double[][] u = {{1.0}, {-1.0}};
            int[][] ruleAtomIdx = {{0}, {0}};

            assertEquals(2.0, AdmmHlMrfInference.dualNorm2(
                    u, new double[0][], ruleAtomIdx, new int[0][]), 1.0e-12);
        }

        @Test
        @DisplayName("Positive caller tolerance is honored below configured epsilon")
        void callerToleranceControlsNormalizedAccuracy() {
            assertEquals(1.0e-8,
                    AdmmHlMrfInference.effectiveTolerance(1.0e-8, 1.0e-2), 0.0);
            assertEquals(1.0e-2,
                    AdmmHlMrfInference.effectiveTolerance(Double.NaN, 1.0e-2), 0.0);

            PslProgram prog = new PslProgram()
                    .observe("Prior", 0.81, "n1")
                    .target("State", "n1")
                    .addRule("1.0: Prior(N) -> State(N) ^2")
                    .addRule("1.0: State(N) -> Prior(N) ^2");
            HlMrfMapInference.Result result = new AdmmHlMrfInference(1.0, 1.0e-2, Double.MAX_VALUE)
                    .solve(prog, prog.ground(), 25_000, 1.0e-8, 1.0e6);

            assertTrue(result.converged(), "The per-coordinate accuracy gate must prevent epsRel from stopping early");
            assertEquals(0.81, result.values().get("State(n1)"), 1.0e-5);
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

        @Test
        @DisplayName("Consensus residual reaches the analytic prior optimum")
        void analyticPriorOptimum() {
            PslProgram prog = new PslProgram()
                    .observe("Prior", 0.81, "n1")
                    .target("State", "n1")
                    .addRule("1.0: Prior(N) -> State(N) ^2")
                    .addRule("1.0: State(N) -> Prior(N) ^2");

            HlMrfMapInference.Result result = new AdmmHlMrfInference()
                    .solve(prog, prog.ground());

            assertTrue(result.converged(), "ADMM should converge on the analytic two-rule fixture");
            assertEquals(0.81, result.values().get("State(n1)"), 1.0e-5,
                    "The target must equal the observed prior at the MAP optimum");
            assertEquals(0.0, result.objective(), 1.0e-10,
                    "The analytic prior fixture has zero hinge-loss at its optimum");
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
        @DisplayName("Hard LEQ uses the exact non-unit-norm box projection")
        void nonUnitNormProjection() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{2.0, -1.0}, new String[]{"A(e1)", "B(e1)"},
                    0.25, RelOp.LEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            double a = result.values().get("A(e1)");
            double b = result.values().get("B(e1)");

            // Projection of (.5,.5) onto 2a-b=.25 is (.4,.55).
            assertEquals(0.40, a, 1e-5);
            assertEquals(0.55, b, 1e-5);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("GEQ negates both the coefficients and positive RHS")
        void geqPositiveRhs() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    1.0, false, false,
                    new double[]{1.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    1.4, RelOp.GEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            assertEquals(1.4, result.values().get("A(e1)") + result.values().get("B(e1)"), 1e-5);
        }

        @Test
        @DisplayName("Soft equality solves both sides rather than choosing one halfspace")
        void equalityBothSides() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    1.0, false, true,
                    new double[]{2.0, -1.0}, new String[]{"A(e1)", "B(e1)"},
                    0.25, RelOp.EQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            // ADMM minimizes the global soft-equality energy after consensus; with no competing
            // rule, the zero-loss equality manifold is reached exactly.
            assertEquals(0.25, 2.0 * result.values().get("A(e1)")
                    - result.values().get("B(e1)"), 1e-5);
        }

        @Test
        @DisplayName("Soft squared LEQ solves the active hinge without hard projection")
        void softSquaredLeqActive() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    1.0, false, true,
                    new double[]{1.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    0.4, RelOp.LEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            // The first local proximal step is active, then consensus drives this single-rule
            // problem to its zero-loss boundary at the symmetric point (.2, .2).
            assertEquals(0.2, result.values().get("A(e1)"), 1e-5);
            assertEquals(0.2, result.values().get("B(e1)"), 1e-5);
            assertEquals(0.0, rule.distanceToSatisfaction(result.values()), 1e-8);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Observed coordinates remain fixed while free coordinates satisfy equality")
        void observedAndFreeCoordinates() {
            PslProgram prog = new PslProgram()
                    .observe("A", 0.9, "e1")
                    .target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    1.2, RelOp.EQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            assertEquals(0.9, result.values().get("A(e1)"), 0.0);
            assertEquals(0.3, result.values().get("B(e1)"), 1e-5);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Hard GEQ keeps observed non-unit coordinates fixed")
        void hardGeqWithObservedCoordinate() {
            PslProgram prog = new PslProgram()
                    .observe("A", 0.6, "e1")
                    .target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{2.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    1.7, RelOp.GEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            assertEquals(0.6, result.values().get("A(e1)"), 0.0);
            assertEquals(0.5, result.values().get("B(e1)"), 1e-5);
            assertEquals(0.0, rule.distanceToSatisfaction(result.values()), 1e-12);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Box-active projection solves the bounded problem, not projection then clipping")
        void boxActiveProjection() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{2.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    0.2, RelOp.LEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            assertEquals(0.0, result.values().get("A(e1)"), 1e-5);
            assertEquals(0.2, result.values().get("B(e1)"), 1e-5);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Hard equality near upper boundary projects endpoint from non-optimal start")
        void hardEqualityNearUpperBoundary() {
            PslProgram prog = new PslProgram().target("A", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0}, new String[]{"A(e1)"},
                    1.0 + 5.0e-13, RelOp.EQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);

            assertEquals(1.0, result.values().get("A(e1)"), 0.0);
            assertTrue(Double.isFinite(result.values().get("A(e1)")));
            assertTrue(rule.distanceToSatisfaction(result.values()) <= 1.0e-12);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Hard halfspace near lower boundary projects its feasible endpoint")
        void hardHalfspaceNearLowerBoundary() {
            PslProgram prog = new PslProgram().target("A", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0}, new String[]{"A(e1)"},
                    -5.0e-13, RelOp.LEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);

            assertEquals(0.0, result.values().get("A(e1)"), 0.0);
            assertTrue(Double.isFinite(result.values().get("A(e1)")));
            assertTrue(rule.distanceToSatisfaction(result.values()) <= 1.0e-12);
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Hard equality root terminates with a zero coefficient")
        void hardEqualityZeroCoefficient() {
            PslProgram prog = new PslProgram().target("A", "e1").target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0, 0.0}, new String[]{"A(e1)", "B(e1)"},
                    0.75, RelOp.EQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);

            assertEquals(0.75, result.values().get("A(e1)"), 1.0e-12);
            assertEquals(0.5, result.values().get("B(e1)"), 0.0);
            assertEquals(0.0, rule.distanceToSatisfaction(result.values()), 1.0e-12);
            assertTrue(result.values().values().stream().allMatch(Double::isFinite));
            assertTrue(result.converged());
        }

        @Test
        @DisplayName("Hard infeasibility is reported without moving observed evidence")
        void hardInfeasibleStatus() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "e1")
                    .target("B", "e1");
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0, 1.0}, new String[]{"A(e1)", "B(e1)"},
                    0.5, RelOp.LEQ);

            HlMrfMapInference.Result result = solveArithmetic(prog, rule);
            assertEquals(1.0, result.values().get("A(e1)"), 0.0);
            assertFalse(result.converged());
            assertTrue(result.objective() > 0.0);
        }

        @Test
        @DisplayName("Hard arithmetic rules are evaluated even when the program has no atoms")
        void emptyProgramHardArithmeticIsNotSilentlyConverged() {
            PslProgram prog = new PslProgram();
            ArithmeticGroundRule rule = new ArithmeticGroundRule(
                    Double.POSITIVE_INFINITY, true, true,
                    new double[]{1.0}, new String[]{"Missing(e1)"},
                    1.0, RelOp.GEQ);

            HlMrfMapInference.Result result = new AdmmHlMrfInference().solve(
                    prog, List.of(), List.of(rule), 10_000, 1e-8, 1e6);
            assertFalse(result.converged());
            assertTrue(result.objective() > 0.0);
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

        private HlMrfMapInference.Result solveArithmetic(PslProgram program,
                                                         ArithmeticGroundRule rule) {
            return new AdmmHlMrfInference().solve(
                    program, List.of(), List.of(rule), 10_000, 1e-8, 1e6);
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
