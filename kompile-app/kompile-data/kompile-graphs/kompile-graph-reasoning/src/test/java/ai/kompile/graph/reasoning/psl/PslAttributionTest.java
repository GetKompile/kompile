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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for E2: {@link HlMrfMapInference.Result#groundRulesFor(String)} (reverse index)
 * and {@link HlMrfMapInference.Result#atomAttribution(String, double)} (per-atom attribution).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Reverse index returns rules from both head AND body for a shared atom</li>
 *   <li>Direction is +1 for head-positive (rule pushes atom up) and -1 for body-positive (pushes down)</li>
 *   <li>Attribution is sorted by weighted potential descending</li>
 *   <li>Observed contradictions retain positive distance/potential, while ADMM exposes non-zero
 *       dualForce only for free targets at constrained optima</li>
 * </ul>
 */
@DisplayName("E2 — PSL per-atom attribution (groundRulesFor + atomAttribution)")
class PslAttributionTest {

    // ─── Minimal 2-rule program sharing a target atom ────────────────────────

    /**
     * Build a program with 2 rules that share the target atom {@code C(alice)}:
     * <ul>
     *   <li>Rule 0 (weight 3.0, squared): {@code A(alice) & B(alice) -> C(alice) ^2}
     *       — C appears as a positive head literal → direction +1 (pushes C up)</li>
     *   <li>Rule 1 (weight 1.0, linear): {@code C(alice) -> D(alice)}
     *       — C appears as a positive body literal → direction -1 (C is consumed to justify D)</li>
     * </ul>
     */
    private static HlMrfMapInference.Result buildTwoRuleResult() {
        PslProgram prog = new PslProgram()
                .observe("A", 1.0, "alice")
                .observe("B", 0.8, "alice")
                .target("C", "alice")
                .target("D", "alice")
                .addRule("3.0: A(X) & B(X) -> C(X) ^2")
                .addRule("1.0: C(X) -> D(X)");

        // Use scalar solver (< 500 ground rules)
        return HlMrfMapInference.solve(prog);
    }

    @Nested
    @DisplayName("groundRulesFor — reverse index")
    class ReverseIndexTests {

        @Test
        @DisplayName("Both head-rule and body-rule appear for the shared atom")
        void returnsRulesFromHeadAndBody() {
            HlMrfMapInference.Result result = buildTwoRuleResult();

            List<GroundRule> rulesForC = result.groundRulesFor("C(alice)");

            // C(alice) appears in:
            //   rule 0 head: A(alice) & B(alice) -> C(alice)
            //   rule 1 body: C(alice) -> D(alice)
            assertEquals(2, rulesForC.size(),
                    "Should find both rules that reference C(alice); found: " + rulesForC);
        }

        @Test
        @DisplayName("Atom not in any rule returns empty list")
        void unknownAtomReturnsEmpty() {
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<GroundRule> rules = result.groundRulesFor("Z(nobody)");
            assertTrue(rules.isEmpty(), "No rules should reference Z(nobody)");
        }

        @Test
        @DisplayName("Null atomKey returns empty list without throwing")
        void nullAtomKeyReturnsEmpty() {
            HlMrfMapInference.Result result = buildTwoRuleResult();
            assertDoesNotThrow(() -> {
                List<GroundRule> rules = result.groundRulesFor(null);
                assertTrue(rules.isEmpty());
            });
        }

        @Test
        @DisplayName("Atom only in head returns the head-rule")
        void atomOnlyInHeadReturnsHeadRule() {
            // D(alice) only appears as a head (in rule 1)
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<GroundRule> rulesForD = result.groundRulesFor("D(alice)");
            assertEquals(1, rulesForD.size(),
                    "D(alice) only appears as head in rule 1; found: " + rulesForD);
        }

        @Test
        @DisplayName("Atom only in body returns the body-rule")
        void atomOnlyInBodyReturnBodyRule() {
            // A(alice) is observed, appears only in body of rule 0
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<GroundRule> rulesForA = result.groundRulesFor("A(alice)");
            assertEquals(1, rulesForA.size(),
                    "A(alice) only appears in the body of rule 0; found: " + rulesForA);
        }
    }

    @Nested
    @DisplayName("atomAttribution — direction and sorting")
    class AttributionTests {

        @Test
        @DisplayName("Head-positive literal yields direction +1 (pushes atom up)")
        void headPositiveDirection() {
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("C(alice)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertFalse(attribs.isEmpty(), "Should have attributions for C(alice)");

            // Find the attribution from rule 0 (the rule that has C in the head)
            HlMrfMapInference.AtomAttribution headRuleAttrib = attribs.stream()
                    .filter(a -> a.rule().head().stream().anyMatch(l -> l.atomKey().equals("C(alice)")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected an attribution from head rule"));

            assertEquals(+1, headRuleAttrib.direction(),
                    "Head-positive literal should yield direction +1 (pushes up)");
        }

        @Test
        @DisplayName("Body-positive literal yields direction -1 (pushes atom down)")
        void bodyPositiveDirection() {
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("C(alice)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            // Find the attribution from rule 1 (the rule that has C in the body)
            HlMrfMapInference.AtomAttribution bodyRuleAttrib = attribs.stream()
                    .filter(a -> a.rule().body().stream().anyMatch(l -> l.atomKey().equals("C(alice)")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected an attribution from body rule"));

            assertEquals(-1, bodyRuleAttrib.direction(),
                    "Body-positive literal should yield direction -1 (pushes down)");
        }

        @Test
        @DisplayName("Attribution is sorted by weightedPotential descending")
        void sortedByPotentialDescending() {
            // Build a program where one rule is clearly violated (high potential) and the other is satisfied
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .observe("C", 0.0, "x")  // pin C=0 → rule0 violated
                    .observe("D", 1.0, "x")  // D=1 → rule1 (C->D) satisfied
                    .addRule("5.0: A(X) -> C(X) ^2")   // high weight, violated
                    .addRule("1.0: C(X) -> D(X) ^2");  // low weight, satisfied when C=0 (body=0)

            // Use scalar solver to force atom assignment without optimizer moving C
            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new ScalarHlMrfInference().solve(prog, ground);

            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("C(x)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertFalse(attribs.isEmpty(), "Should have attributions for C(x)");

            // Verify sorted descending
            for (int i = 1; i < attribs.size(); i++) {
                assertTrue(attribs.get(i - 1).weightedPotential() >= attribs.get(i).weightedPotential(),
                        "Attribution should be sorted by weightedPotential desc at index " + i);
            }
        }

        @Test
        @DisplayName("Each attribution has valid fields: d in [0,1], dualForce >= 0, direction in {-1,0,+1}")
        void validFields() {
            HlMrfMapInference.Result result = buildTwoRuleResult();
            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("C(alice)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertFalse(attribs.isEmpty());
            for (HlMrfMapInference.AtomAttribution a : attribs) {
                assertTrue(a.distanceToSatisfaction() >= 0.0 && a.distanceToSatisfaction() <= 1.0,
                        "d must be in [0,1]: " + a.distanceToSatisfaction());
                assertTrue(a.weightedPotential() >= 0.0,
                        "potential must be >= 0: " + a.weightedPotential());
                assertTrue(a.dualForce() >= 0.0,
                        "dualForce must be >= 0: " + a.dualForce());
                int dir = a.direction();
                assertTrue(dir == -1 || dir == 0 || dir == +1,
                        "direction must be in {-1, 0, +1}: " + dir);
            }
        }
    }

    @Nested
    @DisplayName("ADMM duals and observed-evidence attribution")
    class AdmmDualTests {

        @Test
        @DisplayName("Observed contradiction reports positive evidence potential, not an ADMM dual")
        void observedContradictionReportsPotentialWithoutDual() {
            // B=0 is fixed evidence, so A→B remains contradicted after inference.  The
            // contradiction belongs to the post-hoc rule attribution (distance/potential),
            // not to a consensus dual for B, because observed atoms are not ADMM variables.
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "n0")
                    .observe("B", 0.0, "n0")
                    .addRule("2.0: A(X) -> B(X) ^2");

            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new AdmmHlMrfInference().solve(prog, ground);
            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("B(n0)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);

            assertFalse(attribs.isEmpty(), "The contradicted rule must be attributable to B(n0)");
            HlMrfMapInference.AtomAttribution attribution = attribs.get(0);
            assertTrue(attribution.distanceToSatisfaction() > 0.0,
                    "Observed contradiction must retain positive distance");
            assertTrue(attribution.weightedPotential() > 0.0,
                    "Observed contradiction must retain positive potential");
            assertEquals(0.0, attribution.dualForce(), 1e-15,
                    "Observed evidence is pinned and must not fabricate an ADMM dual");
        }

        @Test
        @DisplayName("ADMM surfaces non-zero dualForce for a target at a constrained optimum")
        void admmDualForceNonZeroForConstrainedTarget() {
            // C is free but is pulled in opposite directions by two observed facts:
            //   2(A→C²) and 1(C→B²), with A=1 and B=0.  The analytic optimum is C=2/3;
            // the two rule-local copies need non-zero, opposing consensus duals there.
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "n0")
                    .observe("B", 0.0, "n0")
                    .target("C", "n0")
                    .addRule("2.0: A(X) -> C(X) ^2")
                    .addRule("1.0: C(X) -> B(X) ^2");

            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new AdmmHlMrfInference().solve(prog, ground);

            assertTrue(result.converged(), "ADMM should converge on the analytic constrained fixture");
            assertEquals(2.0 / 3.0, result.values().get("C(n0)"), 1e-3,
                    "Target must reach the analytic constrained optimum");

            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("C(n0)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);
            assertEquals(2, attribs.size(), "Both rules must contribute to C(n0)");
            assertTrue(attribs.stream().anyMatch(a -> a.dualForce() > 1e-3),
                    "A free target's constrained optimum must expose a non-zero consensus dual; "
                            + "found attributions: " + attribs);
        }

        @Test
        @DisplayName("Scalar solver leaves dualForce=0 (no ADMM duals)")
        void scalarSolverHasZeroDualForce() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "n0")
                    .target("B", "n0")
                    .addRule("1.0: A(X) -> B(X) ^2");

            List<GroundRule> ground = prog.ground();
            HlMrfMapInference.Result result = new ScalarHlMrfInference().solve(prog, ground);

            // admmDuals should be empty for the scalar solver
            assertTrue(result.admmDuals().isEmpty(),
                    "Scalar solver should not populate admmDuals");

            List<HlMrfMapInference.AtomAttribution> attribs =
                    result.atomAttribution("B(n0)", HlMrfMapInference.DEFAULT_HARD_WEIGHT);
            for (HlMrfMapInference.AtomAttribution a : attribs) {
                assertEquals(0.0, a.dualForce(), 1e-15,
                        "Scalar solver attributions should have dualForce=0.0");
            }
        }
    }
}
