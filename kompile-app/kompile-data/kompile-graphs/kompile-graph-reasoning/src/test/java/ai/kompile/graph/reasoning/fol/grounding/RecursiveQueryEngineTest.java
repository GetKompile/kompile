/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import ai.kompile.graph.reasoning.fol.InferredFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RecursiveQueryEngine}: semi-naive fixpoint evaluation.
 *
 * <p>The tests verify:
 * <ol>
 *   <li>Full multi-hop transitive closure (NOT capped at 10k, NOT single-pass)</li>
 *   <li>Mutual recursion between two IDB predicates</li>
 *   <li>Stratified negation over an EDB predicate in a lower stratum</li>
 *   <li>That {@link JoinKernel} extraction leaves {@link ConjunctiveQueryEngine} behaviour
 *       identical (its existing tests remain green — verified by running in same suite)</li>
 * </ol>
 */
class RecursiveQueryEngineTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Build an EdbProvider backed by a plain map of predicate → list of ground tuples.
     */
    private static RecursiveQueryEngine.EdbProvider mapEdb(Map<String, List<List<String>>> map) {
        return pred -> map.getOrDefault(pred, List.of());
    }

    /**
     * Build a linear chain of {@code length} edges:
     * edge(n0, n1), edge(n1, n2), ..., edge(n(length-1), n(length)).
     * Returns the EDB map.
     */
    private static Map<String, List<List<String>>> buildChain(int length) {
        Map<String, List<List<String>>> edb = new HashMap<>();
        List<List<String>> edges = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            edges.add(List.of("n" + i, "n" + (i + 1)));
        }
        edb.put("edge", edges);
        return edb;
    }

    /**
     * Return the transitive closure rules:
     * <pre>
     *   path(?X, ?Y) :- edge(?X, ?Y)
     *   path(?X, ?Z) :- path(?X, ?Y), edge(?Y, ?Z)
     * </pre>
     */
    private static List<RecursiveQueryEngine.DatalogRule> pathRules() {
        return List.of(
                // Base: path(X, Y) :- edge(X, Y)
                new RecursiveQueryEngine.DatalogRule("path",
                        List.of("?X", "?Y"),
                        List.of(RecursiveQueryEngine.RuleAtom.pos("edge", "?X", "?Y"))),
                // Recursive: path(X, Z) :- path(X, Y), edge(Y, Z)
                new RecursiveQueryEngine.DatalogRule("path",
                        List.of("?X", "?Z"),
                        List.of(RecursiveQueryEngine.RuleAtom.pos("path", "?X", "?Y"),
                                RecursiveQueryEngine.RuleAtom.pos("edge", "?Y", "?Z")))
        );
    }

    // ─── Transitive closure tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Transitive closure — full multi-hop")
    class TransitiveClosure {

        @Test
        @DisplayName("4-node chain: path closes all hops a→b, a→c, a→d, b→c, b→d, c→d")
        void fourNodeChain() {
            // edges: a→b, b→c, c→d
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edge", List.of(
                    List.of("a", "b"),
                    List.of("b", "c"),
                    List.of("c", "d")));

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(pathRules(), mapEdb(edb));

            assertTrue(result.isComplete(), "Fixpoint must complete for a 4-node chain");
            Set<List<String>> paths = result.derivedFacts().get("path");
            assertNotNull(paths, "path predicate must be in derived facts");

            // Direct edges become paths
            assertTrue(paths.contains(List.of("a", "b")), "path(a,b) must be derived (direct edge)");
            assertTrue(paths.contains(List.of("b", "c")), "path(b,c) must be derived (direct edge)");
            assertTrue(paths.contains(List.of("c", "d")), "path(c,d) must be derived (direct edge)");

            // Multi-hop: must reach across 2 hops
            assertTrue(paths.contains(List.of("a", "c")),
                    "path(a,c) must be derived (2-hop: a→b→c)");
            assertTrue(paths.contains(List.of("b", "d")),
                    "path(b,d) must be derived (2-hop: b→c→d)");

            // Multi-hop: must reach across 3 hops — this is what proves it is NOT a single pass
            assertTrue(paths.contains(List.of("a", "d")),
                    "path(a,d) must be derived (3-hop: a→b→c→d). "
                            + "A single-pass engine cannot derive this. paths=" + paths);
        }

        @Test
        @DisplayName("10-node chain: full closure has 55 facts (n*(n+1)/2 for 10 nodes)")
        void tenNodeChain() {
            // n0→n1→n2→...→n10 (10 edges, 11 nodes, 55 reachable pairs)
            Map<String, List<List<String>>> edb = buildChain(10);
            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(pathRules(), mapEdb(edb));

            assertTrue(result.isComplete(), "Fixpoint must complete for a 10-node chain");
            Set<List<String>> paths = result.derivedFacts().get("path");
            assertNotNull(paths);

            // For a chain n0→n1→...→n10, path(ni, nj) for all 0 ≤ i < j ≤ 10
            // That is 11*10/2 = 55 pairs
            assertEquals(55, paths.size(),
                    "10-node chain should yield exactly 55 path facts. Got: " + paths.size()
                            + " facts. Sample: " + paths.stream().limit(5).toList());

            // Verify the longest path (0 to 10 = 10 hops)
            assertTrue(paths.contains(List.of("n0", "n10")),
                    "path(n0, n10) must be derived: this requires 10 hops of recursion. "
                            + "A 10k-capped or single-pass engine would miss this.");
        }

        @Test
        @DisplayName("Long chain (150 nodes): proves no single-pass cap (requires >10k pairs for N>100)")
        void longChainBeyondPairCap() {
            // For 150 nodes, N² = 22500 > 10000 — the pair-cap in FolInferenceService would truncate.
            // The recursive engine must still derive the full closure.
            int length = 150;
            Map<String, List<List<String>>> edb = buildChain(length);
            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(pathRules(), mapEdb(edb));

            assertTrue(result.isComplete(), "Fixpoint must complete even for 150-node chain");
            Set<List<String>> paths = result.derivedFacts().get("path");
            assertNotNull(paths);

            // n0 through n150 = 151 nodes.  path(ni, nj) for i < j = 151*150/2 = 11325 facts
            int expected = (length + 1) * length / 2;
            assertEquals(expected, paths.size(),
                    "150-node chain should yield exactly " + expected + " path facts "
                            + "(not capped at 10k). Got: " + paths.size());

            // The longest path requires 150 recursive steps
            assertTrue(paths.contains(List.of("n0", "n" + length)),
                    "path(n0, n150) must be derived (requires 150 hops of recursion)");
        }

        @Test
        @DisplayName("Branching graph: closure covers all reachable pairs")
        void branchingGraph() {
            // Graph:  a → b, a → c, b → d, c → d
            // Full paths: a→b, a→c, b→d, c→d, a→d (via b or c)
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edge", List.of(
                    List.of("a", "b"), List.of("a", "c"),
                    List.of("b", "d"), List.of("c", "d")));

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(pathRules(), mapEdb(edb));

            Set<List<String>> paths = result.derivedFacts().get("path");
            assertNotNull(paths);
            assertTrue(paths.contains(List.of("a", "d")),
                    "path(a,d) must be derived via 2 hops through b or c");
            assertTrue(paths.contains(List.of("a", "b")));
            assertTrue(paths.contains(List.of("a", "c")));
            assertTrue(paths.contains(List.of("b", "d")));
            assertTrue(paths.contains(List.of("c", "d")));
        }
    }

    // ─── Mutual recursion ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mutual recursion — two inter-dependent IDB predicates")
    class MutualRecursion {

        /**
         * Rules for bipartite reachability via two alternating edge types:
         * <pre>
         *   reachA(?X, ?Y) :- edgeA(?X, ?Y)
         *   reachB(?X, ?Y) :- edgeB(?X, ?Y)
         *   reachA(?X, ?Z) :- reachA(?X, ?Y), reachB(?Y, ?Z)
         *   reachB(?X, ?Z) :- reachB(?X, ?Y), reachA(?Y, ?Z)
         * </pre>
         * {@code reachA} and {@code reachB} depend on each other: mutual recursion.
         */
        private List<RecursiveQueryEngine.DatalogRule> mutualRules() {
            return List.of(
                    // Base rules
                    new RecursiveQueryEngine.DatalogRule("reachA",
                            List.of("?X", "?Y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("edgeA", "?X", "?Y"))),
                    new RecursiveQueryEngine.DatalogRule("reachB",
                            List.of("?X", "?Y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("edgeB", "?X", "?Y"))),
                    // Recursive cross-reference: reachA extended through reachB
                    new RecursiveQueryEngine.DatalogRule("reachA",
                            List.of("?X", "?Z"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("reachA", "?X", "?Y"),
                                    RecursiveQueryEngine.RuleAtom.pos("reachB", "?Y", "?Z"))),
                    // Recursive cross-reference: reachB extended through reachA
                    new RecursiveQueryEngine.DatalogRule("reachB",
                            List.of("?X", "?Z"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("reachB", "?X", "?Y"),
                                    RecursiveQueryEngine.RuleAtom.pos("reachA", "?Y", "?Z")))
            );
        }

        @Test
        @DisplayName("Alternating A/B edges: reachA and reachB close across alternating hops")
        void mutualRecursionConverges() {
            // edgeA: a→b, c→d ; edgeB: b→c, d→e
            // Expected: reachA covers paths that start with an A-type edge (possibly alternating)
            // reachB covers paths that start with a B-type edge
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edgeA", List.of(List.of("a", "b"), List.of("c", "d")));
            edb.put("edgeB", List.of(List.of("b", "c"), List.of("d", "e")));

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(mutualRules(), mapEdb(edb));

            assertTrue(result.isComplete(), "Mutual recursion must reach fixpoint");

            Set<List<String>> reachA = result.derivedFacts().get("reachA");
            Set<List<String>> reachB = result.derivedFacts().get("reachB");
            assertNotNull(reachA, "reachA must be in derived facts");
            assertNotNull(reachB, "reachB must be in derived facts");

            // Direct A edges
            assertTrue(reachA.contains(List.of("a", "b")), "reachA(a,b) from edgeA");
            assertTrue(reachA.contains(List.of("c", "d")), "reachA(c,d) from edgeA");

            // a→b (A), b→c (B) ⟹ reachA(a,c) via reachA(a,b)+reachB(b,c)
            assertTrue(reachA.contains(List.of("a", "c")),
                    "reachA(a,c) requires cross-recursion: reachA(a,b)+reachB(b,c)");

            // a→b→c→d: reachA(a,c) already, then reachA(c,d) from edgeA ⟹ reachB(c,d) too?
            // Actually reachB follows B edges: b→c, d→e are B edges
            assertTrue(reachB.contains(List.of("b", "c")), "reachB(b,c) from edgeB");
            assertTrue(reachB.contains(List.of("d", "e")), "reachB(d,e) from edgeB");

            // b→c (B), c→d (A) ⟹ reachB(b,d) via reachB(b,c)+reachA(c,d)
            assertTrue(reachB.contains(List.of("b", "d")),
                    "reachB(b,d) requires cross-recursion: reachB(b,c)+reachA(c,d)");

            // a→...→e across 4 hops (A,B,A,B): a→b(A), b→c(B), c→d(A), d→e(B)
            // reachA(a,d) = reachA(a,c)+reachB(c,d)?  No: reachB(c,d) is not seeded.
            // Let's check what's actually derivable: reachA(a,d) via reachA(a,b)+reachB(b,d)
            assertTrue(reachA.contains(List.of("a", "d")),
                    "reachA(a,d) via reachA(a,b)+reachB(b,d)");
        }

        @Test
        @DisplayName("Mutual recursion terminates (does not loop forever)")
        void mutualRecursionTerminates() {
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edgeA", List.of(List.of("x", "y")));
            edb.put("edgeB", List.of(List.of("y", "z")));

            // Must complete in far fewer than MAX_ROUNDS rounds
            long start = System.currentTimeMillis();
            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(mutualRules(), mapEdb(edb));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result.isComplete(), "Must reach fixpoint, not hit a guard");
            assertTrue(elapsed < 5_000, "Mutual recursion on tiny graph must terminate quickly, took: " + elapsed + "ms");
            // Rounds should be very small (bounded by derived facts count)
            assertTrue(result.roundsCompleted() <= 20,
                    "Should converge in very few rounds, not thousands: " + result.roundsCompleted());
        }
    }

    // ─── Stratified negation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stratified negation")
    class StratifiedNegation {

        @Test
        @DisplayName("blocked(?X) :- node(?X), !exempt(?X) — nodes not in exempt set are blocked")
        void negatedEdbPredicate() {
            // EDB: node(a), node(b), node(c), exempt(b)
            // Rule: blocked(?X) :- node(?X), !exempt(?X)
            // Expected: blocked(a), blocked(c) — NOT blocked(b)
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("node", List.of(List.of("a"), List.of("b"), List.of("c")));
            edb.put("exempt", List.of(List.of("b")));

            List<RecursiveQueryEngine.DatalogRule> rules = List.of(
                    new RecursiveQueryEngine.DatalogRule("blocked",
                            List.of("?X"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("node", "?X"),
                                    RecursiveQueryEngine.RuleAtom.neg("exempt", "?X")))
            );

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(rules, mapEdb(edb));

            assertTrue(result.isComplete(), "Stratified negation must reach fixpoint");
            Set<List<String>> blocked = result.derivedFacts().get("blocked");
            assertNotNull(blocked, "blocked must be in derived facts");

            assertTrue(blocked.contains(List.of("a")), "blocked(a): a is a node and not exempt");
            assertTrue(blocked.contains(List.of("c")), "blocked(c): c is a node and not exempt");
            assertFalse(blocked.contains(List.of("b")),
                    "blocked(b) must NOT be derived: b is exempt. Got: " + blocked);
        }

        @Test
        @DisplayName("Stratified: lower stratum fully evaluated before negation in upper stratum")
        void stratifiedTwoStrata() {
            // Stratum 0: reachable(?X, ?Y) :- edge(?X, ?Y)
            //            reachable(?X, ?Z) :- reachable(?X, ?Y), edge(?Y, ?Z)
            // Stratum 1: unreachable(?X, ?Y) :- node(?X), node(?Y), !reachable(?X, ?Y)
            // (unreachable uses negation of a fully-derived IDB predicate)
            //
            // Graph: a→b, b→c (reachable: a→b, b→c, a→c)
            // Nodes: a, b, c, d  (d is an isolated node)
            // unreachable pairs (X ≠ X): all pairs where path doesn't exist
            //   (b,a), (c,a), (c,b), (d,a), (d,b), (d,c), (a,d), (b,d), (c,d)
            //   — note: self-pairs like (a,a) are not reachable (no self-loop) so also unreachable

            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edge", List.of(List.of("a", "b"), List.of("b", "c")));
            edb.put("node", List.of(List.of("a"), List.of("b"), List.of("c"), List.of("d")));

            List<RecursiveQueryEngine.DatalogRule> rules = List.of(
                    // Base reachability
                    new RecursiveQueryEngine.DatalogRule("reachable",
                            List.of("?X", "?Y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("edge", "?X", "?Y"))),
                    // Recursive reachability
                    new RecursiveQueryEngine.DatalogRule("reachable",
                            List.of("?X", "?Z"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("reachable", "?X", "?Y"),
                                    RecursiveQueryEngine.RuleAtom.pos("edge", "?Y", "?Z"))),
                    // Upper stratum: negates fully-derived reachable
                    new RecursiveQueryEngine.DatalogRule("unreachable",
                            List.of("?X", "?Y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("node", "?X"),
                                    RecursiveQueryEngine.RuleAtom.pos("node", "?Y"),
                                    RecursiveQueryEngine.RuleAtom.neg("reachable", "?X", "?Y")))
            );

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(rules, mapEdb(edb));

            assertTrue(result.isComplete(), "Stratified two-strata program must complete");

            Set<List<String>> reachable = result.derivedFacts().get("reachable");
            Set<List<String>> unreachable = result.derivedFacts().get("unreachable");
            assertNotNull(reachable);
            assertNotNull(unreachable);

            // Reachable set is correct
            assertTrue(reachable.contains(List.of("a", "b")));
            assertTrue(reachable.contains(List.of("b", "c")));
            assertTrue(reachable.contains(List.of("a", "c")), "a→c via 2 hops");
            assertFalse(reachable.contains(List.of("c", "a")), "no reverse edge");

            // Unreachable includes (d, a) — d is isolated, can't reach anything
            assertTrue(unreachable.contains(List.of("d", "a")),
                    "d cannot reach a (no outgoing edges from d)");
            assertTrue(unreachable.contains(List.of("c", "a")),
                    "c cannot reach a (no reverse edge)");

            // Reachable pairs must NOT be in unreachable
            assertFalse(unreachable.contains(List.of("a", "b")),
                    "a→b is reachable, so must not be in unreachable");
            assertFalse(unreachable.contains(List.of("a", "c")),
                    "a→c is reachable (2 hops), so must not be in unreachable");
        }

        @Test
        @DisplayName("Safety check: rule with unbound variable in negated literal throws IllegalArgumentException")
        void unsafeNegationThrows() {
            // unsafe: blocked(?X) :- !edge(?X, ?Y)   — ?Y is unbound when negated literal is reached
            List<RecursiveQueryEngine.DatalogRule> rules = List.of(
                    new RecursiveQueryEngine.DatalogRule("blocked",
                            List.of("?X"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("node", "?X"),
                                    // ?Z is not bound anywhere positive — unsafe
                                    RecursiveQueryEngine.RuleAtom.neg("edge", "?X", "?Z")))
            );

            assertThrows(IllegalArgumentException.class,
                    () -> RecursiveQueryEngine.evaluate(rules, mapEdb(Map.of())),
                    "Unsafe negation (unbound ?Z) must throw IllegalArgumentException");
        }
    }

    // ─── JoinKernel extraction / ConjunctiveQueryEngine backward compatibility ───

    @Nested
    @DisplayName("JoinKernel extraction keeps ConjunctiveQueryEngine behaviour identical")
    class JoinKernelBackwardCompatibility {

        /**
         * This test runs the same assertion as the existing
         * {@link AgentGroundingPrimitivesTest} {@code twoAtomConjunctiveQuery} test to
         * confirm that delegating {@code backtrack} to {@link JoinKernel} does not
         * change observable results.  The same store and query are used.
         */
        @Test
        @DisplayName("ConjunctiveQueryEngine still finds Alice and Bob via JoinKernel delegation")
        void conjunctiveQueryStillWorks() {
            ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore store =
                    new ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore();
            java.time.Instant now = java.time.Instant.now();
            store.store(new InferredFact("hasSkill(Alice, AI)", 0.85, 0.85,
                    List.of(), List.of(), "run-q", 0, now));
            store.store(new InferredFact("hasSkill(Bob, AI)", 0.80, 0.80,
                    List.of(), List.of(), "run-q", 0, now));
            store.store(new InferredFact("isEmployedBy(Alice, Acme)", 0.92, 0.92,
                    List.of(), List.of(), "run-q", 0, now));
            store.store(new InferredFact("isEmployedBy(Bob, Acme)", 0.84, 0.84,
                    List.of(), List.of(), "run-q", 0, now));

            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("?X", "AI")),
                    new ConjunctiveQueryEngine.AtomPattern("isEmployedBy", List.of("?X", "Acme"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);

            assertFalse(results.isEmpty(), "Should still find results after JoinKernel extraction");
            List<String> persons = results.stream().map(b -> b.get("?X")).sorted().toList();
            assertTrue(persons.contains("Alice"), "Alice must appear");
            assertTrue(persons.contains("Bob"), "Bob must appear");
        }

        @Test
        @DisplayName("Minimum confidence is still computed correctly after JoinKernel delegation")
        void confidenceMinimumStillCorrect() {
            ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore store =
                    new ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore();
            java.time.Instant now = java.time.Instant.now();
            store.store(new InferredFact("hasSkill(Alice, AI)", 0.85, 0.85,
                    List.of(), List.of(), "run-q", 0, now));
            store.store(new InferredFact("isEmployedBy(Alice, Acme)", 0.92, 0.92,
                    List.of(), List.of(), "run-q", 0, now));

            List<ConjunctiveQueryEngine.AtomPattern> conjuncts = List.of(
                    new ConjunctiveQueryEngine.AtomPattern("hasSkill", List.of("Alice", "AI")),
                    new ConjunctiveQueryEngine.AtomPattern("isEmployedBy", List.of("Alice", "Acme"))
            );
            List<QueryBinding> results = ConjunctiveQueryEngine.query(conjuncts, store);
            assertFalse(results.isEmpty());
            assertEquals(0.85, results.get(0).confidence(), 1e-6,
                    "Confidence should be min(0.85, 0.92) = 0.85");
        }
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases and guards")
    class EdgeCases {

        @Test
        @DisplayName("Empty rule set returns empty result immediately")
        void emptyRuleSet() {
            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(List.of(), mapEdb(Map.of()));
            assertTrue(result.isComplete());
            assertTrue(result.derivedFacts().isEmpty());
            assertEquals(0, result.roundsCompleted());
        }

        @Test
        @DisplayName("Pure EDB rules (no IDB body atoms) derive facts in seed phase only")
        void pureEdbRules() {
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("parent", List.of(List.of("tom", "bob"), List.of("bob", "ann")));

            // ancestor(X, Y) :- parent(X, Y)  — non-recursive base case only
            List<RecursiveQueryEngine.DatalogRule> rules = List.of(
                    new RecursiveQueryEngine.DatalogRule("ancestor",
                            List.of("?X", "?Y"),
                            List.of(RecursiveQueryEngine.RuleAtom.pos("parent", "?X", "?Y")))
            );

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(rules, mapEdb(edb));
            assertTrue(result.isComplete());
            Set<List<String>> ancestors = result.derivedFacts().get("ancestor");
            assertNotNull(ancestors);
            assertTrue(ancestors.contains(List.of("tom", "bob")));
            assertTrue(ancestors.contains(List.of("bob", "ann")));
            // No 2-hop — no recursive rule
            assertFalse(ancestors.contains(List.of("tom", "ann")),
                    "Without a recursive rule, tom→ann must not be derived");
        }

        @Test
        @DisplayName("Fixpoint result can be materialised as InferredFacts with confidence=1.0")
        void materialisedInferredFacts() {
            Map<String, List<List<String>>> edb = new HashMap<>();
            edb.put("edge", List.of(List.of("a", "b"), List.of("b", "c")));

            RecursiveQueryEngine.FixpointResult result =
                    RecursiveQueryEngine.evaluate(pathRules(), mapEdb(edb));

            List<InferredFact> facts = result.toInferredFacts("run-test");
            assertFalse(facts.isEmpty(), "Should produce InferredFact objects");
            for (InferredFact f : facts) {
                assertEquals(1.0, f.confidence(), 1e-9,
                        "All derived facts must have confidence=1.0 (crisp Datalog)");
                assertEquals(1.0, f.value(), 1e-9);
                assertEquals("run-test", f.runId());
            }
            // Check that path(a,c) is materialised (2-hop)
            boolean hasAtoC = facts.stream().anyMatch(f -> f.atomKey().equals("path(a, c)"));
            assertTrue(hasAtoC, "InferredFact for path(a,c) must be present");
        }

        @Test
        @DisplayName("isRecursive() detects recursive vs non-recursive rules correctly")
        void isRecursiveDetection() {
            RecursiveQueryEngine.DatalogRule base = new RecursiveQueryEngine.DatalogRule("path",
                    List.of("?X", "?Y"),
                    List.of(RecursiveQueryEngine.RuleAtom.pos("edge", "?X", "?Y")));
            RecursiveQueryEngine.DatalogRule recursive = new RecursiveQueryEngine.DatalogRule("path",
                    List.of("?X", "?Z"),
                    List.of(RecursiveQueryEngine.RuleAtom.pos("path", "?X", "?Y"),
                            RecursiveQueryEngine.RuleAtom.pos("edge", "?Y", "?Z")));

            assertFalse(base.isRecursive(), "Base rule (no path in body) must not be recursive");
            assertTrue(recursive.isRecursive(), "Rule with path in body must be recursive");
        }
    }
}
