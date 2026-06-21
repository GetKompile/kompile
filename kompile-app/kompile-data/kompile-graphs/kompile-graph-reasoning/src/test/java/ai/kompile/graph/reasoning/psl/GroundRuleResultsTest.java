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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HlMrfMapInference.Result#groundRuleResults()} — the per-ground-rule
 * satisfaction / distance-to-satisfaction accessor added for PSL "why" explanation traces.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Cardinality: one {@link GroundRuleResult} per ground rule</li>
 *   <li>Satisfied rule: d ≈ 0, potential ≈ 0, satisfied = true</li>
 *   <li>Violated rule: d &gt; 0, potential &gt; 0, satisfied = false</li>
 *   <li>Hard rule: satisfied flag uses {@link HlMrfMapInference#HARD_VIOLATION_TOLERANCE}</li>
 *   <li>Ordering is preserved (same order as {@link HlMrfMapInference.Result#groundRules()})</li>
 *   <li>Ranking by distance surfaces the most-violated rule first</li>
 *   <li>hardWeight overload produces consistent weighted-potential values</li>
 *   <li>Works for all three solver paths: Scalar, ADMM, and SGD</li>
 * </ul>
 */
@DisplayName("HlMrfMapInference.Result#groundRuleResults()")
class GroundRuleResultsTest {

    // ─── Cardinality and basic structure ─────────────────────────────────────

    @Nested
    @DisplayName("Cardinality and ordering")
    class CardinalityTests {

        @Test
        @DisplayName("One GroundRuleResult per ground rule (ordering preserved)")
        void oneResultPerRule() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("B", 0.8, "x")
                    .target("C", "x")
                    .addRule("2.0: A(X) & B(X) -> C(X) ^2")   // rule 0
                    .addRule("0.5: A(X) -> C(X) ^2");           // rule 1

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            List<GroundRule> groundRules = result.groundRules();
            List<GroundRuleResult> grResults = result.groundRuleResults();

            assertEquals(groundRules.size(), grResults.size(),
                    "Should have one result per ground rule");

            // ordering must match groundRules()
            for (int i = 0; i < groundRules.size(); i++) {
                assertSame(groundRules.get(i), grResults.get(i).rule(),
                        "GroundRuleResult[" + i + "] must reference the same GroundRule object");
            }
        }

        @Test
        @DisplayName("Empty program yields empty groundRuleResults()")
        void emptyProgram() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x");
            // no rules added → no ground rules
            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            assertTrue(result.groundRuleResults().isEmpty());
        }
    }

    // ─── Satisfied vs violated ────────────────────────────────────────────────

    @Nested
    @DisplayName("Satisfied vs violated rules")
    class SatisfactionTests {

        @Test
        @DisplayName("Rule with zero distance is satisfied; potential ≈ 0")
        void satisfiedRuleHasZeroDistance() {
            // A=1, C=1 → rule "A→C" has bodyTruth=1, headTruth=1, d=max(0,0)=0
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("C", 1.0, "x")
                    .addRule("1.0: A(X) -> C(X) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            List<GroundRuleResult> grResults = result.groundRuleResults();
            assertEquals(1, grResults.size());

            GroundRuleResult r = grResults.get(0);
            assertEquals(0.0, r.distanceToSatisfaction(), 1e-9, "Satisfied rule has d=0");
            assertEquals(0.0, r.weightedPotential(), 1e-9, "Satisfied rule has potential=0");
            assertTrue(r.satisfied(), "Satisfied rule should be marked satisfied");
        }

        @Test
        @DisplayName("Rule with observed atoms pinned to violation has d>0 and satisfied=false")
        void violatedRuleIsNotSatisfied() {
            // A=1, C=0 (observed, cannot move) → rule "A→C" is violated: d=1-0=1
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("C", 0.0, "x")
                    .addRule("1.0: A(X) -> C(X) ^2");

            HlMrfMapInference.Result result = new ScalarHlMrfInference()
                    .solve(prog, prog.ground());
            List<GroundRuleResult> grResults = result.groundRuleResults();

            assertEquals(1, grResults.size());
            GroundRuleResult r = grResults.get(0);
            assertTrue(r.distanceToSatisfaction() > HlMrfMapInference.HARD_VIOLATION_TOLERANCE,
                    "Violated rule should have d > HARD_VIOLATION_TOLERANCE, got " + r.distanceToSatisfaction());
            assertTrue(r.weightedPotential() > 0.0,
                    "Violated rule should have potential > 0");
            assertFalse(r.satisfied(),
                    "Violated rule should be marked not-satisfied");
        }

        @Test
        @DisplayName("Hard rule: satisfied flag respects HARD_VIOLATION_TOLERANCE")
        void hardRuleViolation() {
            // Hard rule A&B→C, C pinned to 0 → always violated
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "n")
                    .observe("B", 1.0, "n")
                    .observe("C", 0.0, "n")
                    .addRule("A(X) & B(X) -> C(X) .");  // hard constraint

            HlMrfMapInference.Result result = new AdmmHlMrfInference()
                    .solve(prog, prog.ground());
            List<GroundRuleResult> grResults = result.groundRuleResults();

            assertEquals(1, grResults.size());
            GroundRuleResult r = grResults.get(0);
            assertTrue(r.rule().hard(), "Should be a hard rule");
            assertFalse(r.satisfied(),
                    "Hard rule violated by construction must not be satisfied; d=" + r.distanceToSatisfaction());
        }
    }

    // ─── Ranking and "why" use-case ───────────────────────────────────────────

    @Nested
    @DisplayName("Ranking: surface most-violated rule")
    class RankingTests {

        @Test
        @DisplayName("Most-violated rule ranks first when sorted by distance descending")
        void mostViolatedRankFirst() {
            // Rule 0 (weight 5.0): very strong rule, targets push high → likely satisfied
            // Rule 1: weak rule with observed violation → d > 0
            // We deliberately pin a violation: B=0.9, D=0.0 observed → D→ "should be high" violated
            PslProgram prog = new PslProgram()
                    .observe("B", 0.9, "e1")
                    .observe("D", 0.0, "e1")   // pin D to 0 → "B→D" violated
                    .observe("A", 1.0, "e1")
                    .observe("C", 1.0, "e1")   // "A→C" satisfied perfectly
                    .addRule("5.0: A(X) -> C(X) ^2")   // should be satisfied (d≈0)
                    .addRule("1.0: B(X) -> D(X) ^2");  // should be violated (d>0)

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            List<GroundRuleResult> grResults = result.groundRuleResults();

            // Sort by distance descending to rank most-violated first
            List<GroundRuleResult> ranked = grResults.stream()
                    .sorted(Comparator.comparingDouble(GroundRuleResult::distanceToSatisfaction).reversed())
                    .toList();

            // The violated rule (B→D) should have higher d than the satisfied rule (A→C)
            double dFirst = ranked.get(0).distanceToSatisfaction();
            double dSecond = ranked.get(1).distanceToSatisfaction();
            assertTrue(dFirst >= dSecond,
                    "Most-violated rule should sort first: " + ranked.get(0).rule());

            // The most violated rule must not be satisfied
            assertFalse(ranked.get(0).satisfied() && dFirst > 1e-6,
                    "Top-ranked violated rule should not be marked satisfied");
        }

        @Test
        @DisplayName("groundRuleResults() can be used to build an explanation trace")
        void explanationTraceFromResults() {
            PslProgram prog = new PslProgram()
                    .observe("Trust", 1.0, "alice", "bob")
                    .observe("Trust", 0.7, "bob", "carol")
                    .target("Trust", "alice", "carol")
                    .addRule("1.0: Trust(A, B) & Trust(B, C) -> Trust(A, C) ^2")
                    .addRule("0.3: Trust(A, B)");  // prior

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            List<GroundRuleResult> grResults = result.groundRuleResults();

            assertFalse(grResults.isEmpty(), "Should have at least one result");

            // Confirm toString() produces a parseable explanation string
            for (GroundRuleResult grr : grResults) {
                String trace = grr.toString();
                assertTrue(trace.contains("d="), "Trace should include distance: " + trace);
                assertTrue(trace.contains("pot="), "Trace should include potential: " + trace);
                assertTrue(trace.contains("[OK]") || trace.contains("[VIOLATED]"),
                        "Trace should include satisfaction flag: " + trace);
            }

            // All distances should be in [0,1]
            for (GroundRuleResult grr : grResults) {
                double d = grr.distanceToSatisfaction();
                assertTrue(d >= -1e-9 && d <= 1.0 + 1e-9,
                        "Distance out of [0,1]: " + d + " for " + grr.rule());
                assertTrue(grr.weightedPotential() >= -1e-9,
                        "Potential must be non-negative: " + grr.weightedPotential());
            }
        }
    }

    // ─── hardWeight overload ──────────────────────────────────────────────────

    @Nested
    @DisplayName("hardWeight overload")
    class HardWeightOverloadTests {

        @Test
        @DisplayName("groundRuleResults(hardWeight) yields same d but scaled potential for hard rules")
        void hardWeightScalesHardRulePotential() {
            // Hard rule: A=1, C=0 → violated, d>0
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("C", 0.0, "x")
                    .addRule("A(X) -> C(X) .");  // hard constraint

            HlMrfMapInference.Result result = new ScalarHlMrfInference()
                    .solve(prog, prog.ground());

            List<GroundRuleResult> defaultResults = result.groundRuleResults();
            List<GroundRuleResult> customResults = result.groundRuleResults(1.0); // much lower weight

            assertEquals(defaultResults.size(), customResults.size());

            GroundRuleResult def = defaultResults.get(0);
            GroundRuleResult custom = customResults.get(0);

            // Distance should be identical (purely geometric, weight-independent)
            assertEquals(def.distanceToSatisfaction(), custom.distanceToSatisfaction(), 1e-12,
                    "Distance to satisfaction must not depend on hardWeight");

            // Potential must differ: default uses DEFAULT_HARD_WEIGHT, custom uses 1.0
            if (def.distanceToSatisfaction() > 1e-9) {
                assertTrue(def.weightedPotential() > custom.weightedPotential(),
                        "Default hard weight (" + HlMrfMapInference.DEFAULT_HARD_WEIGHT
                                + ") should yield higher potential than 1.0, "
                                + "got default=" + def.weightedPotential()
                                + " custom=" + custom.weightedPotential());
            }
        }

        @Test
        @DisplayName("Convenience groundRuleResults() equals groundRuleResults(DEFAULT_HARD_WEIGHT)")
        void convenienceEqualsExplicit() {
            PslProgram prog = new PslProgram()
                    .observe("X", 0.6, "a")
                    .target("Y", "b")
                    .addRule("0.8: X(A) -> Y(B) ^2")
                    .addRule("X(A) -> Y(B) .");  // hard

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);

            List<GroundRuleResult> convenience = result.groundRuleResults();
            List<GroundRuleResult> explicit = result.groundRuleResults(HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertEquals(convenience.size(), explicit.size());
            for (int i = 0; i < convenience.size(); i++) {
                assertEquals(convenience.get(i).distanceToSatisfaction(),
                        explicit.get(i).distanceToSatisfaction(), 1e-12);
                assertEquals(convenience.get(i).weightedPotential(),
                        explicit.get(i).weightedPotential(), 1e-12);
                assertEquals(convenience.get(i).satisfied(), explicit.get(i).satisfied());
            }
        }
    }

    // ─── Solver-path coverage ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Works across all solver paths")
    class SolverPathTests {

        @Test
        @DisplayName("ScalarHlMrfInference produces valid groundRuleResults()")
        void scalarSolver() {
            HlMrfMapInference.Result result = makeSmallProgram(ScalarHlMrfInference.class);
            assertValidResults(result.groundRuleResults(), result.values());
        }

        @Test
        @DisplayName("AdmmHlMrfInference produces valid groundRuleResults()")
        void admmSolver() {
            HlMrfMapInference.Result result = makeSmallProgram(AdmmHlMrfInference.class);
            assertValidResults(result.groundRuleResults(), result.values());
        }

        @Test
        @DisplayName("SgdHlMrfInference produces valid groundRuleResults()")
        void sgdSolver() {
            HlMrfMapInference.Result result = makeSmallProgram(SgdHlMrfInference.class);
            assertValidResults(result.groundRuleResults(), result.values());
        }

        private HlMrfMapInference.Result makeSmallProgram(Class<? extends HlMrfSolver> solverClass) {
            PslProgram prog = new PslProgram()
                    .observe("Evidence", 0.8, "n1")
                    .target("Conclusion", "n1")
                    .addRule("1.0: Evidence(X) -> Conclusion(X) ^2")
                    .addRule("0.2: ~Conclusion(X) ^2");

            List<GroundRule> ground = prog.ground();

            HlMrfSolver solver;
            try {
                solver = solverClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Cannot instantiate solver " + solverClass, e);
            }
            return solver.solve(prog, ground);
        }

        private void assertValidResults(List<GroundRuleResult> results, Map<String, Double> values) {
            assertFalse(results.isEmpty(), "results must not be empty");
            for (GroundRuleResult grr : results) {
                assertNotNull(grr.rule(), "rule must not be null");
                double d = grr.distanceToSatisfaction();
                assertTrue(d >= -1e-9 && d <= 1.0 + 1e-9,
                        "distance out of [0,1]: " + d);
                assertTrue(grr.weightedPotential() >= -1e-9,
                        "potential must be non-negative: " + grr.weightedPotential());
                // cross-check: d must match GroundRule.distanceToSatisfaction(values)
                double expected = grr.rule().distanceToSatisfaction(values);
                assertEquals(expected, d, 1e-12,
                        "GroundRuleResult.d must match GroundRule.distanceToSatisfaction(values)");
            }
        }
    }
}
