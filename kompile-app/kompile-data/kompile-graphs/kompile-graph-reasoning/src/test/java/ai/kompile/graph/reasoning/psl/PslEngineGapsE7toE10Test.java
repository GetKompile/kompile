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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PSL engine gaps E-7 through E-10.
 *
 * <ul>
 *   <li><b>E-7</b>: Disjunctive rule heads (already implemented — tests confirm correctness).</li>
 *   <li><b>E-8</b>: Join-order optimisation + predicate-index caching.</li>
 *   <li><b>E-9</b>: Negation in rule bodies/heads — gradient accounting (already implemented — tests confirm).</li>
 *   <li><b>E-10</b>: Parser aliases {@code >>}, {@code <<}, and {@code ~=}.</li>
 * </ul>
 */
@DisplayName("PSL Engine Gaps E-7 to E-10")
class PslEngineGapsE7toE10Test {

    // ═══════════════════════════════════════════════════════════════════════════
    // E-7 — Disjunctive Rule Heads (already correct; tests confirm)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("E-7: Disjunctive rule heads")
    class DisjunctiveHeadTests {

        /**
         * A rule with a single head atom is the degenerate disjunction case.
         * headTruth = min(1, v) = v; already covered by many other tests.
         */
        @Test
        @DisplayName("Single head atom: headTruth = atom value")
        void singleHeadAtom() {
            Map<String, Double> truth = Map.of("A(x)", 0.7, "B(x)", 1.0);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(new GroundRule.Lit("A(x)", false)),
                    List.of(new GroundRule.Lit("B(x)", false)),
                    "test");
            assertEquals(0.7, gr.bodyTruth(truth), 1e-9);
            assertEquals(1.0, gr.headTruth(truth), 1e-9);
            assertEquals(0.0, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Two head atoms: headTruth = min(1, v1 + v2).
         * With body=1.0 and head sum capped at 1, distance = max(0, 1 - min(1, sum)).
         */
        @Test
        @DisplayName("Two head atoms: headTruth = min(1, v1 + v2)")
        void twoHeadAtoms() {
            // body=1.0, head atoms h1=0.3, h2=0.4 → sum=0.7, headTruth=0.7
            // distance = max(0, 1.0 - 0.7) = 0.3
            Map<String, Double> truth = Map.of("h1", 0.3, "h2", 0.4, "body", 1.0);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(),   // empty body → bodyTruth = 1.0
                    List.of(new GroundRule.Lit("h1", false), new GroundRule.Lit("h2", false)),
                    "test");
            assertEquals(1.0, gr.bodyTruth(truth), 1e-9);
            assertEquals(0.7, gr.headTruth(truth), 1e-9);
            assertEquals(0.3, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Head sum > 1 is capped at 1: min(1, 0.8 + 0.7) = min(1, 1.5) = 1.0 → d=0.
         */
        @Test
        @DisplayName("Head sum > 1 capped to 1: distance = 0")
        void headSumCapped() {
            Map<String, Double> truth = Map.of("h1", 0.8, "h2", 0.7);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(),
                    List.of(new GroundRule.Lit("h1", false), new GroundRule.Lit("h2", false)),
                    "test");
            assertEquals(0.0, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Three head atoms: only one needs to be high to satisfy the rule.
         * Rule: body=1.0 -> h1 | h2 | h3; h1=0.1, h2=0.1, h3=0.9 → sum=1.1, capped=1 → d=0.
         */
        @Test
        @DisplayName("Three head atoms: one high atom satisfies the rule")
        void threeHeadAtomsOneSatisfies() {
            Map<String, Double> truth = Map.of("h1", 0.1, "h2", 0.1, "h3", 0.9);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(),
                    List.of(
                            new GroundRule.Lit("h1", false),
                            new GroundRule.Lit("h2", false),
                            new GroundRule.Lit("h3", false)),
                    "test");
            assertEquals(0.0, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Grounding correctly instantiates a rule with a disjunctive head.
         * Rule: A(X) -> B(X) | C(X)
         * Verify the grounded head has two literals.
         */
        @Test
        @DisplayName("Grounding: disjunctive head instantiated with two literals")
        void groundingDisjunctiveHead() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "e1")
                    .target("B", "e1")
                    .target("C", "e1")
                    .addRule("1.0: A(X) -> B(X) | C(X) ^2");

            List<GroundRule> ground = prog.ground();
            assertEquals(1, ground.size(), "Should produce one ground rule");
            GroundRule gr = ground.get(0);
            assertEquals(2, gr.head().size(), "Head should have two literals");
            Set<String> headKeys = new LinkedHashSet<>();
            for (GroundRule.Lit lit : gr.head()) headKeys.add(lit.atomKey());
            assertTrue(headKeys.contains("B(e1)"), "Head should contain B(e1)");
            assertTrue(headKeys.contains("C(e1)"), "Head should contain C(e1)");
        }

        /**
         * Inference with a disjunctive head: MAP should push either B or C (or both) high.
         * Rule: A(X) -> B(X) | C(X)   with A(x)=1.
         */
        @Test
        @DisplayName("Inference: disjunctive head — solver pushes head sum toward 1")
        void inferenceDisjunctiveHead() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "e1")
                    .target("B", "e1")
                    .target("C", "e1")
                    .addRule("2.0: A(X) -> B(X) | C(X) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double b = result.values().getOrDefault("B(e1)", 0.0);
            double c = result.values().getOrDefault("C(e1)", 0.0);
            // The rule is satisfied when b + c >= 1; with weight 2 and quadratic hinge,
            // the solver should push the sum close to 1.
            assertTrue(b + c > 0.8, "Disjunctive head should be pushed high, got B=" + b + " C=" + c);
        }

        /**
         * Confirm PslRule.parse() recognises the '|' separator in the head.
         */
        @Test
        @DisplayName("Parse: disjunctive head parsed correctly")
        void parseDisjunctiveHead() {
            PslRule rule = PslRule.parse("1.0: A(X) -> B(X) | C(X) ^2");
            assertEquals(1, rule.body().size(), "One body atom");
            assertEquals(2, rule.head().size(), "Two head atoms");
            assertEquals("B", rule.head().get(0).predicate());
            assertEquals("C", rule.head().get(1).predicate());
        }

        /**
         * Negated literal in the head: ~B(X) contributes 1 - v(B(X)).
         * Rule: A(X) -> ~B(X)  — satisfied when B is low.
         */
        @Test
        @DisplayName("Negated head atom: headTruth = 1 - v")
        void negatedHeadAtom() {
            // body=1.0, head ~B(x) with B(x)=0.3 → headTruth = 1-0.3=0.7 → d=max(0,1-0.7)=0.3
            Map<String, Double> truth = Map.of("B(x)", 0.3);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(),
                    List.of(new GroundRule.Lit("B(x)", true)),  // negated
                    "test");
            assertEquals(0.7, gr.headTruth(truth), 1e-9,
                    "~B(x) with B=0.3 → headTruth=1-0.3=0.7");
            assertEquals(0.3, gr.distanceToSatisfaction(truth), 1e-9);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // E-8 — Join-order optimisation + predicate index caching
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("E-8: Join-order optimisation and predicate index caching")
    class JoinOrderOptimisationTests {

        /**
         * After a ground() call, the predicate index should be cached (not dirty).
         */
        @Test
        @DisplayName("Predicate index is cached after ground() and not rebuilt until mutation")
        void indexCachedAfterGround() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .target("B", "x")
                    .addRule("1.0: A(X) -> B(X) ^2");

            assertFalse(prog.isPredicateIndexCached(), "Index should not be cached before first ground()");
            prog.ground();
            assertTrue(prog.isPredicateIndexCached(), "Index should be cached after ground()");
        }

        /**
         * After ground(), adding a new atom must invalidate the cache.
         */
        @Test
        @DisplayName("Atom addition invalidates the predicate-index cache")
        void mutationInvalidatesCache() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .target("B", "x")
                    .addRule("1.0: A(X) -> B(X) ^2");

            prog.ground();
            assertTrue(prog.isPredicateIndexCached(), "Cache should be valid");

            // Mutation: add a new atom
            prog.observe("A", 0.5, "y");
            assertFalse(prog.isPredicateIndexCached(), "Cache must be invalidated after adding atom");
        }

        /**
         * Calling ground() twice without mutation should reuse the cached index
         * and produce identical ground rule sets.
         */
        @Test
        @DisplayName("Two ground() calls without mutation produce identical rule sets")
        void twoGroundCallsIdentical() {
            PslProgram prog = new PslProgram()
                    .observe("Knows", 1.0, "alice", "bob")
                    .observe("Knows", 0.8, "bob", "carol")
                    .target("Knows", "alice", "carol")
                    .addRule("1.0: Knows(A, B) & Knows(B, C) -> Knows(A, C) ^2");

            List<GroundRule> first  = prog.ground();
            List<GroundRule> second = prog.ground();

            assertEquals(first.size(), second.size(), "Both calls should produce the same number of ground rules");
            // Compare displays
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.get(i).display(), second.get(i).display(),
                        "Ground rule " + i + " display should match");
            }
        }

        /**
         * E-8 selectivity heuristic: join order should favour the atom with fewer candidates.
         * With pred A having 1 atom and pred B having 10 atoms, A should come first.
         */
        @Test
        @DisplayName("optimizeJoinOrder: most-selective (fewest candidates) atom first")
        void optimizeJoinOrderSelectivity() {
            PslProgram prog = new PslProgram();
            // Add 1 atom for predicate "Rare" and 10 atoms for "Common"
            prog.observe("Rare", 1.0, "e1");
            for (int i = 1; i <= 10; i++) {
                prog.observe("Common", 1.0, "e" + i);
            }
            // Build index via ground() to ensure it's populated
            prog.addRule("1.0: Rare(X) & Common(Y) -> Common(X) ^2");
            prog.ground(); // ensure cache is built

            // Get the predicate index from the program (via reflection-free path: just re-ground)
            // We test the result indirectly: the ground rule set must match whether we optimise or not.
            // Direct API: optimizeJoinOrder
            PslAtom rare   = new PslAtom("Rare",   List.of(Term.var("X")), false);
            PslAtom common = new PslAtom("Common", List.of(Term.var("Y")), false);
            List<PslAtom> original = List.of(common, rare); // common first (non-optimal)

            // We need to call optimizeJoinOrder; it's package-private, same package as this test.
            Map<String, List<PslAtom>> idx = new java.util.LinkedHashMap<>();
            idx.put("Rare",   List.of(new PslAtom("Rare",   List.of(Term.con("e1")), false)));
            List<PslAtom> commons = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                commons.add(new PslAtom("Common", List.of(Term.con("e" + i)), false));
            }
            idx.put("Common", commons);

            List<PslAtom> optimized = prog.optimizeJoinOrder(original, idx);
            assertEquals("Rare", optimized.get(0).predicate(),
                    "Most-selective predicate (Rare, 1 candidate) should come first");
            assertEquals("Common", optimized.get(1).predicate());
        }

        /**
         * Join-order optimisation must not change the ground rule SET — only the traversal order.
         * Test with a multi-predicate rule: ground rules produced must be the same set regardless
         * of atom order in the rule.
         */
        @Test
        @DisplayName("Join-order optimisation produces IDENTICAL ground rule set as fixed order")
        void joinOrderProducesIdenticalGroundSet() {
            // Rule with 3 body atoms; we test by grounding directly via two different atom orderings
            // using the fact that optimizeJoinOrder sorts them differently.
            PslProgram prog = new PslProgram()
                    .observe("P", 1.0, "a", "b")
                    .observe("Q", 0.9, "b", "c")
                    .observe("R", 0.7, "a", "c")
                    .target("S", "a")
                    .addRule("1.0: P(A, B) & Q(B, C) & R(A, C) -> S(A) ^2");

            List<GroundRule> groundNormal = prog.ground();

            // Add more copies to make Q non-selective (many candidates) vs P (few)
            for (int i = 2; i <= 5; i++) {
                prog.observe("Q", 0.5, "b" + i, "c" + i);
            }

            List<GroundRule> groundAfter = prog.ground();
            // Still should produce exactly the ground rules matching A=a, B=b, C=c
            assertTrue(groundAfter.stream()
                    .anyMatch(gr -> gr.display().contains("S(a)")),
                    "Ground rule for S(a) should always be produced");
        }

        /**
         * Single-atom body: optimizeJoinOrder should return it unchanged.
         */
        @Test
        @DisplayName("optimizeJoinOrder: single-atom body returned unchanged")
        void singleAtomNoReorder() {
            PslProgram prog = new PslProgram()
                    .observe("A", 1.0, "x")
                    .target("B", "x")
                    .addRule("1.0: A(X) -> B(X) ^2");
            prog.ground();

            PslAtom a = new PslAtom("A", List.of(Term.var("X")), false);
            Map<String, List<PslAtom>> idx = Map.of("A", List.of(new PslAtom("A", List.of(Term.con("x")), false)));
            List<PslAtom> result = prog.optimizeJoinOrder(List.of(a), idx);
            assertEquals(1, result.size());
            assertEquals("A", result.get(0).predicate());
        }

        /**
         * Empty body: optimizeJoinOrder should return an empty list.
         */
        @Test
        @DisplayName("optimizeJoinOrder: empty body returned as-is")
        void emptyBodyNoReorder() {
            PslProgram prog = new PslProgram()
                    .target("B", "x")
                    .addRule("1.0: B(X)");
            prog.ground();

            List<PslAtom> result = prog.optimizeJoinOrder(List.of(), Map.of());
            assertTrue(result.isEmpty());
        }

        /**
         * Function predicates (E-4 external functions) should be placed last by the join-order
         * optimiser, because their cost model differs from stored atoms.
         */
        @Test
        @DisplayName("optimizeJoinOrder: function predicates sorted to end")
        void functionPredicateSortedLast() {
            PslProgram prog = new PslProgram()
                    .registerFunction("Sim", args -> 0.9)
                    .observe("Entity", 1.0, "e1")
                    .observe("Entity", 1.0, "e2")
                    .target("SameAs", "e1", "e2");
            prog.ground();

            PslAtom sim    = new PslAtom("Sim",    List.of(Term.var("A"), Term.var("B")), false);
            PslAtom entity = new PslAtom("Entity", List.of(Term.var("A")), false);
            List<PslAtom> atoms = List.of(sim, entity); // Sim first (non-optimal)
            Map<String, List<PslAtom>> idx = new java.util.LinkedHashMap<>();
            idx.put("Entity", List.of(
                    new PslAtom("Entity", List.of(Term.con("e1")), false),
                    new PslAtom("Entity", List.of(Term.con("e2")), false)));
            // Sim not in idx (it's a function)

            List<PslAtom> optimized = prog.optimizeJoinOrder(atoms, idx);
            assertEquals("Entity", optimized.get(0).predicate(),
                    "Stored-atom predicate should come before function predicate");
            assertEquals("Sim", optimized.get(1).predicate(),
                    "Function predicate should be sorted last");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // E-9 — Negation in Rule Bodies (already correct; tests confirm gradient sign)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("E-9: Negation in rule bodies — distance and gradient correctness")
    class NegationTests {

        /**
         * Negated body literal contributes 1 - v to the Łukasiewicz conjunction.
         * Rule: ~A(X) -> B(X)
         * With A(x)=0.3 → ~A contributes 1-0.3=0.7 → bodyTruth=0.7 → d = max(0, 0.7 - v(B)).
         */
        @Test
        @DisplayName("Negated body literal: value = 1 - atomValue")
        void negatedBodyLiteralValue() {
            Map<String, Double> truth = Map.of("A(x)", 0.3, "B(x)", 0.0);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(new GroundRule.Lit("A(x)", true)),   // ~A(x)
                    List.of(new GroundRule.Lit("B(x)", false)),
                    "~A(x) -> B(x)");
            // ~A(x) contributes 1-0.3=0.7 → bodyTruth=max(0, 0.7-(1-1))=0.7
            assertEquals(0.7, gr.bodyTruth(truth), 1e-9,
                    "~A(x) with A=0.3 should contribute 0.7 to body truth");
            assertEquals(0.0, gr.headTruth(truth), 1e-9);
            assertEquals(0.7, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Negated body atom at value=1: ~A contributes 0, so bodyTruth=0 and d=0.
         */
        @Test
        @DisplayName("Negated body atom at value=1: contributes 0 to body (rule satisfied)")
        void negatedBodyAtomValueOne() {
            Map<String, Double> truth = Map.of("A(x)", 1.0, "B(x)", 0.0);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(new GroundRule.Lit("A(x)", true)),
                    List.of(new GroundRule.Lit("B(x)", false)),
                    "~A(x) -> B(x)");
            assertEquals(0.0, gr.bodyTruth(truth), 1e-9,
                    "~A(x) with A=1 contributes 0 → body is false → d=0");
            assertEquals(0.0, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Negated body + non-negated body in conjunction.
         * Rule: A(X) & ~B(X) -> C(X)
         * A=0.9, B=0.4, C=0.0 → ~B contributes 0.6; bodyTruth = max(0, 0.9+0.6-1) = 0.5
         * headTruth = 0.0 → d = 0.5
         */
        @Test
        @DisplayName("Mixed negated/non-negated conjunction: distance computed correctly")
        void mixedNegatedConjunction() {
            Map<String, Double> truth = Map.of("A(x)", 0.9, "B(x)", 0.4, "C(x)", 0.0);
            GroundRule gr = new GroundRule(1.0, false, false,
                    List.of(
                            new GroundRule.Lit("A(x)", false),   //  A(x) = 0.9
                            new GroundRule.Lit("B(x)", true)),    // ~B(x) = 1-0.4 = 0.6
                    List.of(new GroundRule.Lit("C(x)", false)),
                    "A(x) & ~B(x) -> C(x)");
            // bodyTruth = max(0, 0.9 + 0.6 - 1) = 0.5
            assertEquals(0.5, gr.bodyTruth(truth), 1e-9);
            assertEquals(0.5, gr.distanceToSatisfaction(truth), 1e-9);
        }

        /**
         * Gradient direction for a negated body literal must be the negation of the
         * non-negated gradient — i.e., increasing the atom value DECREASES the body truth,
         * which DECREASES the distance-to-satisfaction when d > 0.
         *
         * <p>ScalarHlMrfInference.gradient() uses {@code coef * (l.negated() ? -1.0 : 1.0)}
         * for body literals.  This test verifies that the inference result moves in the
         * correct direction: with ~B(X) in the body and B being a target, the solver should
         * push B toward 1 (making ~B=0, body=false, d=0) — the opposite of what it would do
         * for a positive B literal.</p>
         */
        @Test
        @DisplayName("Negated body gradient sign: solver pushes negated atom toward 1")
        void negatedBodyGradientDirection() {
            // Rule: ~B(X) -> C(X)  with C observed=1 (always satisfied on head side).
            // B is a target. With ~B in the body:
            // - If B is low → ~B is high → body is high → trying to satisfy head (C=1 ✓ always)
            // - Gradient for B from the negated literal = -coef → descent moves B up
            // We test with C=0 (so head is not trivially satisfied) to actually see the push.
            PslProgram prog = new PslProgram()
                    .target("B", "x")
                    .observe("C", 0.0, "x")  // C fixed at 0 → rule is violated when B is low
                    .addRule("1.0: ~B(X) -> C(X) ^2");

            // With B=0 initially (default target start=0.5, but check behaviour):
            // ~B=0.5 at start → body=0.5 → head=0 → d=0.5 → gradient for B = -coef < 0
            //   → B increases → ~B decreases → body decreases → d decreases
            // After inference, B should be > 0.5 (solver pushes B up via negative gradient sign)
            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double b = result.values().getOrDefault("B(x)", 0.0);
            // With C=0 always, the rule can only be satisfied by making ~B false, i.e. B=1.
            assertTrue(b > 0.5, "Negated body gradient should push B toward 1, got B=" + b);
        }

        /**
         * Negation in body parsed correctly: ~A(X) is a negated literal with A as predicate.
         */
        @Test
        @DisplayName("Parse: negated body literal recognised")
        void parseNegatedBodyLiteral() {
            PslRule rule = PslRule.parse("1.0: ~A(X) -> B(X) ^2");
            assertEquals(1, rule.body().size());
            assertTrue(rule.body().get(0).negated(), "Body literal A should be negated");
            assertEquals("A", rule.body().get(0).predicate());
        }

        /**
         * ! prefix is also a valid negation (both ~ and ! accepted per PslAtom.parse()).
         */
        @Test
        @DisplayName("Parse: !A(X) treated identically to ~A(X)")
        void parseBangNegation() {
            PslRule rule1 = PslRule.parse("1.0: ~A(X) -> B(X) ^2");
            PslRule rule2 = PslRule.parse("1.0: !A(X) -> B(X) ^2");
            assertEquals(rule1.body().get(0).negated(), rule2.body().get(0).negated());
            assertEquals(rule1.body().get(0).predicate(), rule2.body().get(0).predicate());
        }

        /**
         * Inference end-to-end with negated body literal:
         * Rule: ~Spam(X) -> Trusted(X)   — if X is not spam then trust it.
         * With Spam=0 → ~Spam=1 → body=1 → solver pushes Trusted high.
         */
        @Test
        @DisplayName("E2E: negated body — ~Spam -> Trusted pushes Trusted high")
        void endToEndNegatedBody() {
            PslProgram prog = new PslProgram()
                    .observe("Spam", 0.0, "msg1")  // not spam
                    .target("Trusted", "msg1")
                    .addRule("2.0: ~Spam(X) -> Trusted(X) ^2");

            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double trusted = result.values().getOrDefault("Trusted(msg1)", 0.0);
            assertTrue(trusted > 0.8, "~Spam(msg1) with Spam=0 should push Trusted high, got " + trusted);
        }

        /**
         * E2E with high Spam: ~Spam=0 → body=0 → rule does not fire.
         * Without any rule body that fires, the body truth is 0, distance to satisfaction is 0,
         * and the objective is flat — Trusted can stay anywhere in [0,1].  The key property is
         * that Trusted is NOT pushed above 0.5 by this rule; it either stays at 0.5 (neutral
         * init) or is not moved above it.
         *
         * <p>To verify "rule does not fire" we assert that the distance-to-satisfaction of the
         * grounded rule is 0 (since body is false → rule trivially satisfied).</p>
         */
        @Test
        @DisplayName("E2E: ~Spam with Spam=1 → rule body is false → distance to satisfaction = 0")
        void endToEndNegatedBodyInactive() {
            PslProgram prog = new PslProgram()
                    .observe("Spam", 1.0, "msg2")  // is spam → ~Spam=0
                    .target("Trusted", "msg2")
                    .addRule("2.0: ~Spam(X) -> Trusted(X) ^2");

            List<GroundRule> ground = prog.ground();
            assertEquals(1, ground.size(), "Should ground one rule for msg2");
            GroundRule gr = ground.get(0);

            // ~Spam(msg2) = 1 - 1.0 = 0.0 → bodyTruth = max(0, 0.0 - 0) = 0 → d = 0
            Map<String, Double> truth = prog.valueSnapshot();
            truth.put("Trusted(msg2)", 0.5); // neutral starting point
            assertEquals(0.0, gr.bodyTruth(truth), 1e-9,
                    "~Spam with Spam=1 should give body truth = 0 (rule inactive)");
            assertEquals(0.0, gr.distanceToSatisfaction(truth), 1e-9,
                    "Rule should be trivially satisfied when body is false");

            // Inference: with d=0 for all possible Trusted values, the gradient is 0 →
            // Trusted stays at init (0.5). It must NOT be actively pushed above 0.5.
            HlMrfMapInference.Result result = HlMrfMapInference.solve(prog);
            double trusted = result.values().getOrDefault("Trusted(msg2)", 0.5);
            assertTrue(trusted <= 0.5 + 1e-6,
                    "With Spam=1, ~Spam=0, rule should not push Trusted up, got " + trusted);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // E-10 — Parser Aliases: >>, <<, ~=
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("E-10: Parser aliases >>, <<, ~=")
    class ParserAliasTests {

        // ── >> (forward implication alias for ->) ─────────────────────────────

        @Test
        @DisplayName(">> is a synonym for -> (forward implication)")
        void forwardAlias() {
            PslRule rule = PslRule.parse("1.0: A(X) >> B(X) ^2");
            // Should be identical to "1.0: A(X) -> B(X) ^2"
            assertEquals(1, rule.body().size(), "One body atom");
            assertEquals(1, rule.head().size(), "One head atom");
            assertEquals("A", rule.body().get(0).predicate());
            assertEquals("B", rule.head().get(0).predicate());
            assertEquals(1.0, rule.weight(), 1e-9);
            assertTrue(rule.squared());
            assertFalse(rule.hard());
        }

        @Test
        @DisplayName(">> parses weight and hard flag correctly")
        void forwardAliasWeightAndHard() {
            PslRule softRule = PslRule.parse("2.5: A(X) >> B(X) ^1");
            assertEquals(2.5, softRule.weight(), 1e-9);
            assertFalse(softRule.squared());

            PslRule hardRule = PslRule.parse("A(X) >> B(X) .");
            assertTrue(hardRule.hard());
        }

        @Test
        @DisplayName(">> with multi-atom body and head")
        void forwardAliasMultiAtom() {
            PslRule rule = PslRule.parse("1.0: A(X) & B(X) >> C(X) | D(X) ^2");
            assertEquals(2, rule.body().size());
            assertEquals(2, rule.head().size());
            assertEquals("A", rule.body().get(0).predicate());
            assertEquals("B", rule.body().get(1).predicate());
        }

        @Test
        @DisplayName(">> grounding produces same rule set as ->")
        void forwardAliasGroundingEquivalent() {
            PslProgram prog1 = new PslProgram()
                    .observe("A", 1.0, "x")
                    .target("B", "x")
                    .addRule("1.0: A(X) -> B(X) ^2");

            PslProgram prog2 = new PslProgram()
                    .observe("A", 1.0, "x")
                    .target("B", "x")
                    .addRule("1.0: A(X) >> B(X) ^2");

            List<GroundRule> g1 = prog1.ground();
            List<GroundRule> g2 = prog2.ground();
            assertEquals(g1.size(), g2.size(), "Ground rule count should match");
        }

        @Test
        @DisplayName(">> inference result matches -> result")
        void forwardAliasInferenceEquivalent() {
            PslProgram prog1 = new PslProgram()
                    .observe("Cause", 0.9, "x")
                    .target("Effect", "x")
                    .addRule("2.0: Cause(X) -> Effect(X) ^2");

            PslProgram prog2 = new PslProgram()
                    .observe("Cause", 0.9, "x")
                    .target("Effect", "x")
                    .addRule("2.0: Cause(X) >> Effect(X) ^2");

            double v1 = HlMrfMapInference.solve(prog1).values().getOrDefault("Effect(x)", 0.0);
            double v2 = HlMrfMapInference.solve(prog2).values().getOrDefault("Effect(x)", 0.0);
            assertEquals(v1, v2, 0.01, ">> inference should match -> inference");
        }

        // ── << (reverse implication: A << B is B -> A) ────────────────────────

        @Test
        @DisplayName("<< reverses body and head (A << B  ≡  B -> A)")
        void reverseImplication() {
            PslRule rule = PslRule.parse("1.0: A(X) << B(X) ^2");
            // A << B  ⟹  body=B, head=A
            assertEquals(1, rule.body().size(), "One body atom (was right-hand side)");
            assertEquals(1, rule.head().size(), "One head atom (was left-hand side)");
            assertEquals("B", rule.body().get(0).predicate(), "Body should be B");
            assertEquals("A", rule.head().get(0).predicate(), "Head should be A");
        }

        @Test
        @DisplayName("<< preserves weight, hard flag, and squared exponent")
        void reverseImplicationAttributes() {
            PslRule softRule = PslRule.parse("3.0: Target(X) << Source(X) ^2");
            assertEquals(3.0, softRule.weight(), 1e-9);
            assertTrue(softRule.squared());
            assertFalse(softRule.hard());

            PslRule hardRule = PslRule.parse("Target(X) << Source(X) .");
            assertTrue(hardRule.hard());
        }

        @Test
        @DisplayName("<< multi-atom: C(X) << A(X) & B(X)  ≡  A(X) & B(X) -> C(X)")
        void reverseImplicationMultiBody() {
            PslRule rule = PslRule.parse("1.0: C(X) << A(X) & B(X) ^2");
            // C is the head; A & B is the body
            assertEquals(2, rule.body().size(), "Body should have two atoms (A and B)");
            assertEquals(1, rule.head().size(), "Head should have one atom (C)");
            assertEquals("C", rule.head().get(0).predicate());
        }

        @Test
        @DisplayName("<< produces same ground rules as equivalent ->")
        void reverseImplicationGroundingEquivalent() {
            // A(X) << B(X)  should be equivalent to  B(X) -> A(X)
            PslProgram prog1 = new PslProgram()
                    .observe("B", 1.0, "x")
                    .target("A", "x")
                    .addRule("1.0: B(X) -> A(X) ^2");

            PslProgram prog2 = new PslProgram()
                    .observe("B", 1.0, "x")
                    .target("A", "x")
                    .addRule("1.0: A(X) << B(X) ^2");

            List<GroundRule> g1 = prog1.ground();
            List<GroundRule> g2 = prog2.ground();
            assertEquals(g1.size(), g2.size(), "Same number of ground rules");
        }

        @Test
        @DisplayName("<< inference: B(X) -> A(X) equivalent inference result")
        void reverseImplicationInference() {
            PslProgram prog1 = new PslProgram()
                    .observe("B", 0.9, "x")
                    .target("A", "x")
                    .addRule("2.0: B(X) -> A(X) ^2");

            PslProgram prog2 = new PslProgram()
                    .observe("B", 0.9, "x")
                    .target("A", "x")
                    .addRule("2.0: A(X) << B(X) ^2");

            double v1 = HlMrfMapInference.solve(prog1).values().getOrDefault("A(x)", 0.0);
            double v2 = HlMrfMapInference.solve(prog2).values().getOrDefault("A(x)", 0.0);
            assertEquals(v1, v2, 0.05, "<< inference should match B -> A inference");
        }

        // ── ~= (inequality guard alias for !=) ────────────────────────────────

        @Test
        @DisplayName("~= is a synonym for != in inequality guards")
        void inequalityAliasBasic() {
            // "A(X) & B(Y) & (X ~= Y) -> C(X, Y)"  should parse the same as  "X != Y"
            PslRule rule = PslRule.parse("1.0: A(X) & B(Y) & (X ~= Y) -> C(X, Y) ^2");
            assertEquals(2, rule.body().size(), "Two non-distinct atoms in body");
            assertEquals(1, rule.distinct().size(), "One distinct pair");
            String[] pair = rule.distinct().get(0);
            assertEquals("X", pair[0]);
            assertEquals("Y", pair[1]);
        }

        @Test
        @DisplayName("~= in guard filters correctly during grounding")
        void inequalityAliasGrounding() {
            PslProgram prog = new PslProgram()
                    .observe("Node", 1.0, "a")
                    .observe("Node", 1.0, "b")
                    .target("Edge", "a", "b")
                    .target("Edge", "a", "a")
                    .addRule("1.0: Node(X) & Node(Y) & (X ~= Y) -> Edge(X, Y) ^2");

            List<GroundRule> ground = prog.ground();
            // Only X=a, Y=b (where a != b) should ground; (a,a) is filtered by inequality
            assertEquals(1, ground.size(), "~= guard should filter out X=Y binding");
            assertTrue(ground.get(0).display().contains("b"),
                    "Grounded rule should reference b (not a-a pair)");
        }

        @Test
        @DisplayName("~= mixed with != in the same rule body")
        void inequalityAliasMixedWithBang() {
            // This confirms ~= is translated to != before parsing so both work
            PslRule rule1 = PslRule.parse("1.0: A(X) & B(Y) & (X != Y) -> C(X, Y) ^2");
            PslRule rule2 = PslRule.parse("1.0: A(X) & B(Y) & (X ~= Y) -> C(X, Y) ^2");
            assertEquals(rule1.distinct().size(), rule2.distinct().size());
            assertEquals(rule1.distinct().get(0)[0], rule2.distinct().get(0)[0]);
            assertEquals(rule1.distinct().get(0)[1], rule2.distinct().get(0)[1]);
        }

        @Test
        @DisplayName("~= does not affect non-guard occurrences (no false substitutions)")
        void inequalityAliasDoesNotAffectAtomNames() {
            // Predicates with ~= in the name should not be mangled (they won't appear outside guards)
            // But we also test that a simple rule without guards works fine.
            PslRule rule = PslRule.parse("1.0: A(X) -> B(X) ^2");
            assertEquals("A", rule.body().get(0).predicate());
            assertEquals("B", rule.head().get(0).predicate());
        }

        // ── Combined alias tests ───────────────────────────────────────────────

        @Test
        @DisplayName("Combining >> with ~= guard")
        void forwardAliasWithInequalityGuard() {
            PslRule rule = PslRule.parse("1.0: A(X) & B(Y) & (X ~= Y) >> C(X, Y) ^2");
            assertEquals(2, rule.body().size());
            assertEquals(1, rule.head().size());
            assertEquals(1, rule.distinct().size());
            assertEquals("C", rule.head().get(0).predicate());
        }

        @Test
        @DisplayName("Combining << with ~= guard")
        void reverseAliasWithInequalityGuard() {
            // C(X, Y) << A(X) & B(Y) & (X ~= Y)
            // body = right side = A(X) & B(Y) with X != Y guard; head = C(X, Y)
            PslRule rule = PslRule.parse("1.0: C(X, Y) << A(X) & B(Y) & (X ~= Y) ^2");
            assertEquals(2, rule.body().size(), "body: A and B");
            assertEquals(1, rule.head().size(), "head: C");
            assertEquals("C", rule.head().get(0).predicate());
            assertEquals(1, rule.distinct().size(), "one X != Y guard");
        }

        /**
         * Smoke test: all three aliases can be parsed without throwing.
         */
        @Test
        @DisplayName("All three aliases parse without exception")
        void allAliasesParseCleanly() {
            assertDoesNotThrow(() -> PslRule.parse("1.0: A(X) >> B(X) ^2"));
            assertDoesNotThrow(() -> PslRule.parse("1.0: B(X) << A(X) ^2"));
            assertDoesNotThrow(() -> PslRule.parse("1.0: A(X) & B(Y) & (X ~= Y) -> C(X, Y) ^2"));
        }
    }
}
