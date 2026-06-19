/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-contained unit tests for the hand-rolled PSL / HL-MRF engine: rule parsing,
 * Łukasiewicz distance-to-satisfaction, grounding, and MAP inference. No Spring context
 * and no knowledge graph — pure logic and arithmetic.
 */
class PslEngineTest {

    private static final double TOL = 0.02;

    // ─── Parsing ───────────────────────────────────────────────────────────────

    @Test
    void parseAtom_handlesNegationAndArguments() {
        PslAtom a = PslAtom.parse("~State(X)");
        assertEquals("State", a.predicate());
        assertTrue(a.negated());
        assertEquals(1, a.args().size());
        assertTrue(a.args().get(0).variable(), "X should be a variable");
        assertEquals("State(X)", a.key());

        PslAtom g = PslAtom.parse("Link(a, b)");
        assertFalse(g.negated());
        assertTrue(g.isGround(), "lowercase args are constants ⇒ ground");
        assertEquals("Link(a, b)", g.key());
    }

    @Test
    void parseRule_weightedImplicationWithExponent() {
        PslRule r = PslRule.parse("2.5: State(X) & Link(X, Y) -> State(Y) ^2");
        assertEquals(2.5, r.weight(), 1e-9);
        assertFalse(r.hard());
        assertTrue(r.squared());
        assertEquals(2, r.body().size());
        assertEquals(1, r.head().size());
        assertEquals("State", r.head().get(0).predicate());
    }

    @Test
    void parseRule_hardConstraintAndNegativePrior() {
        PslRule hard = PslRule.parse("State(X) -> State(Y) .");
        assertTrue(hard.hard());

        PslRule prior = PslRule.parse("1.0: !Risky(N) ^2");
        assertTrue(prior.body().isEmpty(), "negative prior has empty body");
        assertEquals(1, prior.head().size());
        assertTrue(prior.head().get(0).negated());
    }

    @Test
    void parseRule_inequalityGuardBecomesDistinctConstraint() {
        PslRule r = PslRule.parse("3: Knows(A, B) & Knows(B, C) & (A != C) -> Knows(A, C) ^2");
        assertEquals(2, r.body().size(), "the (A != C) guard is not a body atom");
        assertEquals(1, r.distinct().size());
        assertArrayEquals(new String[]{"A", "C"}, r.distinct().get(0));
    }

    // ─── Łukasiewicz distance to satisfaction ───────────────────────────────────

    @Test
    void distance_simpleImplication() {
        GroundRule rule = new GroundRule(1.0, false, true,
                List.of(new GroundRule.Lit("A", false)),
                List.of(new GroundRule.Lit("B", false)), "A -> B");

        assertEquals(1.0, rule.distanceToSatisfaction(Map.of("A", 1.0, "B", 0.0)), 1e-9);
        assertEquals(0.0, rule.distanceToSatisfaction(Map.of("A", 1.0, "B", 1.0)), 1e-9);
        assertEquals(0.5, rule.distanceToSatisfaction(Map.of("A", 0.5, "B", 0.0)), 1e-9);
        assertEquals(0.0, rule.distanceToSatisfaction(Map.of("A", 0.3, "B", 0.7)), 1e-9);
    }

    @Test
    void distance_conjunctiveBodyUsesLukasiewiczTNorm() {
        // body = A & B = max(0, A + B - 1); head = C
        GroundRule rule = new GroundRule(1.0, false, true,
                List.of(new GroundRule.Lit("A", false), new GroundRule.Lit("B", false)),
                List.of(new GroundRule.Lit("C", false)), "A & B -> C");

        assertEquals(1.0, rule.distanceToSatisfaction(Map.of("A", 1.0, "B", 1.0, "C", 0.0)), 1e-9);
        assertEquals(0.5, rule.distanceToSatisfaction(Map.of("A", 1.0, "B", 0.5, "C", 0.0)), 1e-9);
        assertEquals(0.0, rule.distanceToSatisfaction(Map.of("A", 0.4, "B", 0.4, "C", 0.0)), 1e-9);
    }

    @Test
    void distance_negatedLiteralIsOneMinusValue() {
        GroundRule rule = new GroundRule(1.0, false, true,
                List.of(new GroundRule.Lit("A", true)),   // ~A
                List.of(new GroundRule.Lit("B", false)), "~A -> B");
        // body = 1 - A = 0.7; head = 0 ⇒ distance 0.7
        assertEquals(0.7, rule.distanceToSatisfaction(Map.of("A", 0.3, "B", 0.0)), 1e-9);
    }

    @Test
    void distance_disjunctiveHeadUsesLukasiewiczTConorm() {
        GroundRule rule = new GroundRule(1.0, false, true,
                List.of(new GroundRule.Lit("A", false)),
                List.of(new GroundRule.Lit("C", false), new GroundRule.Lit("D", false)), "A -> C | D");
        // head = min(1, C + D) = 0.6; body = 1 ⇒ distance 0.4
        assertEquals(0.4, rule.distanceToSatisfaction(Map.of("A", 1.0, "C", 0.3, "D", 0.3)), 1e-9);
    }

    // ─── MAP inference ───────────────────────────────────────────────────────────

    @Test
    void inference_propagationSatisfiesImplication() {
        PslProgram program = new PslProgram();
        program.observe("State", 1.0, "a");
        program.observe("Link", 0.8, "a", "b");
        program.target("State", "b");
        program.addRule("10: State(a) & Link(a, b) -> State(b) ^2");

        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);

        assertTrue(result.converged());
        // Body truth is max(0, 1 + 0.8 - 1) = 0.8, so MAP drives the (otherwise
        // unconstrained) effect up into the satisfying region [0.8, 1] (energy 0).
        double sb = result.values().get("State(b)");
        assertTrue(sb >= 0.8 - TOL && sb <= 1.0, "State(b) should satisfy the rule: " + sb);
        assertEquals(0.0, result.objective(), TOL);
    }

    @Test
    void inference_isMonotoneInLinkStrength() {
        // A negative prior makes the effect uniquely determined, so it tracks the link.
        double weak = inferEffect(0.3);
        double strong = inferEffect(0.9);
        assertTrue(strong > weak + 0.3,
                "stronger link ⇒ higher inferred effect (" + strong + " vs " + weak + ")");
    }

    private double inferEffect(double linkStrength) {
        PslProgram program = new PslProgram();
        program.observe("State", 1.0, "a");
        program.observe("Link", linkStrength, "a", "b");
        program.target("State", "b");
        program.addRule("10: State(a) & Link(a, b) -> State(b) ^2");
        program.addRule("1: !State(b) ^2");
        return HlMrfMapInference.solve(program).values().get("State(b)");
    }

    @Test
    void inference_softEqualityAnchorsStateToPrior() {
        // Prior(a) <-> State(a) soft equality: with no other force, State settles at the prior.
        PslProgram program = new PslProgram();
        program.observe("Prior", 0.3, "a");
        program.target("State", "a");
        program.addRule("2: Prior(a) -> State(a) ^2");
        program.addRule("2: State(a) -> Prior(a) ^2");

        assertEquals(0.3, HlMrfMapInference.solve(program).values().get("State(a)"), TOL);
    }

    @Test
    void grounding_firstOrderRuleGroundsPerMatchingEdge() {
        // a -> b -> c with a single first-order propagation rule grounds to exactly two rules.
        PslProgram program = new PslProgram();
        program.observe("State", 1.0, "a");
        program.observe("Link", 0.9, "a", "b");
        program.observe("Link", 0.9, "b", "c");
        program.target("State", "b");
        program.target("State", "c");
        program.addRule("10: State(X) & Link(X, Y) -> State(Y) ^2");

        assertEquals(2, program.ground().size());

        HlMrfMapInference.Result result = HlMrfMapInference.solve(program);
        assertTrue(result.converged());
        result.values().values().forEach(v -> assertTrue(v >= 0.0 && v <= 1.0, "truth in [0,1]: " + v));
    }

    @Test
    void inference_negativePriorPushesTowardZero() {
        PslProgram program = new PslProgram();
        program.target("State", "x");
        program.addRule("5: !State(x) ^2");
        assertEquals(0.0, HlMrfMapInference.solve(program).values().get("State(x)"), TOL);
    }

    @Test
    void inference_hardConstraintIsEnforced() {
        PslProgram program = new PslProgram();
        program.observe("State", 1.0, "a");
        program.target("State", "b");
        program.addRule("State(a) -> State(b) .");
        assertEquals(1.0, HlMrfMapInference.solve(program).values().get("State(b)"), TOL);
    }

    @Test
    void grounding_distinctGuardSkipsSelfPairs() {
        PslProgram program = new PslProgram();
        program.observe("Link", 1.0, "a", "a"); // self loop
        program.observe("Link", 1.0, "a", "b");
        program.target("State", "a");
        program.target("State", "b");
        program.addRule("1: Link(X, Y) & (X != Y) -> State(Y) ^2");

        // Only the (a,b) grounding survives; the (a,a) self-pair is filtered.
        assertEquals(1, program.ground().size());
    }
}
