/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.materialization;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ForwardChainingMaterializer} and {@link LogicBasedReducer}.
 *
 * <p>Covers:</p>
 * <ol>
 *   <li>Materialization derives the transitive closure of a small ancestor rule set to fixpoint.</li>
 *   <li>Materialization terminates on a cyclic rule set (mutual transitivity → same fixed closure).</li>
 *   <li>Reduction removes a transitively-entailed edge while keeping the non-redundant core.</li>
 *   <li>Round-trip: reduce then materialize restores the transitive closure.</li>
 *   <li>Rule-based redundancy: a fact derivable by rules is removed.</li>
 *   <li>Reduction is reversible: restore() re-asserts removed facts.</li>
 * </ol>
 */
class FolMaterializationAndReductionTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static FactStore ancestorStore() {
        // alice → bob, bob → carol  (two direct ancestor edges)
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("ancestor(alice, bob)", "test"));
        store.assertFact(Fact.observed("ancestor(bob, carol)", "test"));
        return store;
    }

    /** Transitivity rule: ancestor(?X, ?Z) :- ancestor(?X, ?Y), ancestor(?Y, ?Z) */
    private static List<DatalogRule> ancestorRules() {
        return List.of(FolDatalogAdapter.transitivityRule("ancestor"));
    }

    // ─── Test 1: transitive closure derives (alice, carol) ─────────────────────

    @Test
    void materialize_derivesTransitiveClosureToFixpoint() {
        FactStore store = ancestorStore();
        List<DatalogRule> rules = ancestorRules();

        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer(10, 1_000);
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();

        ForwardChainingMaterializer.MaterializationResult result =
                materializer.materialize(store, rules, sink);

        // Must have derived ancestor(alice, carol)
        Optional<InferredFact> derived = sink.latest("ancestor(alice, carol)");
        assertTrue(derived.isPresent(),
                "Expected ancestor(alice, carol) to be derived; sink=" + debugSink(sink));

        InferredFact fact = derived.get();
        assertEquals(1.0, fact.value(), 1e-9, "Deductive facts must have value=1.0");
        assertEquals(1.0, fact.confidence(), 1e-9, "Deductive facts must have confidence=1.0");
        assertTrue(fact.supportingRuleIds().contains(ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER),
                "Must carry DEDUCTIVE basis marker");

        // EDB facts must NOT be re-materialized into the sink (they already exist)
        assertTrue(sink.latest("ancestor(alice, bob)").isEmpty(),
                "EDB fact should not be redundantly stored in sink");
        assertTrue(sink.latest("ancestor(bob, carol)").isEmpty(),
                "EDB fact should not be redundantly stored in sink");

        assertTrue(result.reachedFixpoint(), "Must reach fixpoint on this small input");
        assertTrue(result.derivedFactCount() >= 1, "At least one fact derived");
    }

    // ─── Test 2: cyclic rules terminate ────────────────────────────────────────

    @Test
    void materialize_terminatesOnCyclicRules() {
        // Symmetric cycle: likes(a, b), likes(b, a)
        // Transitivity rule: likes(?X, ?Z) :- likes(?X, ?Y), likes(?Y, ?Z)
        // This produces likes(a, a) and likes(b, b) (self-loops from the cycle), then fixpoints.
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("likes(a, b)", "test"));
        store.assertFact(Fact.observed("likes(b, a)", "test"));

        List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("likes"));

        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer(20, 10_000);
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();

        // Must not throw or loop forever
        ForwardChainingMaterializer.MaterializationResult result =
                materializer.materialize(store, rules, sink);

        // Derived facts should include likes(a, a) and likes(b, b) (or likes(b, b) via a→b→a)
        // The exact derived set depends on fixpoint order; the important thing is it terminates.
        assertTrue(result.reachedFixpoint() || !result.terminationReason().isEmpty(),
                "Must either reach fixpoint or explain why it stopped: " + result.terminationReason());
        // Total derived facts must be finite and bounded
        assertTrue(sink.size() < 1000, "Should not produce more than a handful of facts from 2-node cycle");
    }

    // ─── Test 3: reduction removes transitively-entailed edge ──────────────────

    @Test
    void reduce_removesTransitivelyEntailedEdge_keepsCore() {
        // Three facts: ancestor(a, b), ancestor(b, c), ancestor(a, c)
        // The edge (a, c) is redundant: it is entailed by (a, b) + (b, c) under transitivity.
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("ancestor(a, b)", "test"));
        store.assertFact(Fact.observed("ancestor(b, c)", "test"));
        store.assertFact(Fact.observed("ancestor(a, c)", "test")); // redundant

        LogicBasedReducer reducer = new LogicBasedReducer();
        LogicBasedReducer.ReductionResult result =
                reducer.reduceTransitive(store, Set.of("ancestor"));

        // ancestor(a, c) must be removed
        assertEquals(1, result.removedCount(),
                "Exactly one redundant edge expected; removed=" + result.removedFacts());
        assertEquals("ancestor(a, c)", result.removedFacts().get(0).fact().atomKey(),
                "The redundant edge should be ancestor(a, c)");
        assertEquals(LogicBasedReducer.REDUNDANCY_TRANSITIVE,
                result.removedFacts().get(0).basisMarker());

        // The core must contain the two non-redundant facts
        FactStore core = result.coreStore();
        assertEquals(2, core.size(), "Core must have 2 facts");
        assertTrue(core.factFor("ancestor(a, b)").isPresent(), "Direct edge a→b must be in core");
        assertTrue(core.factFor("ancestor(b, c)").isPresent(), "Direct edge b→c must be in core");
        assertTrue(core.factFor("ancestor(a, c)").isEmpty(), "Redundant edge a→c must be absent from core");
    }

    // ─── Test 4: round-trip — reduce then materialize restores closure ─────────

    @Test
    void roundTrip_reduceThenMaterialize_restoresClosure() {
        // Full triangle: a→b, b→c, a→c (with a→c being redundant)
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("ancestor(a, b)", "test"));
        store.assertFact(Fact.observed("ancestor(b, c)", "test"));
        store.assertFact(Fact.observed("ancestor(a, c)", "test"));

        // Reduce: should drop ancestor(a, c)
        LogicBasedReducer reducer = new LogicBasedReducer();
        LogicBasedReducer.ReductionResult reduced =
                reducer.reduceTransitive(store, Set.of("ancestor"));

        assertEquals(1, reduced.removedCount(), "One redundant edge removed");

        // Materialize from the core
        List<DatalogRule> rules = ancestorRules();
        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer(10, 10_000);
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
        materializer.materialize(reduced.coreStore(), rules, sink);

        // After materialization, ancestor(a, c) is back as a derived fact
        assertTrue(sink.latest("ancestor(a, c)").isPresent(),
                "Transitive closure must regenerate ancestor(a, c)");

        // And it should be marked as deductive, not a direct observation
        InferredFact rederived = sink.latest("ancestor(a, c)").get();
        assertTrue(rederived.supportingRuleIds().contains(ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER));
    }

    // ─── Test 5: rule-based redundancy ─────────────────────────────────────────

    @Test
    void reduce_ruleBased_removesEntailedFact() {
        // Rule: sibling(?X, ?Z) :- child(?X, ?P), child(?Z, ?P)  (two children of the same parent)
        // Base: child(alice, bob), child(carol, bob)
        // Explicit: sibling(alice, carol)  ← redundant because the rule derives it
        DatalogRule siblingRule = new DatalogRule(
                "sibling",
                List.of("?X", "?Z"),
                List.of(
                        RuleAtom.pos("child", "?X", "?P"),
                        RuleAtom.pos("child", "?Z", "?P")
                )
        );

        FactStore store = new FactStore();
        store.assertFact(Fact.observed("child(alice, bob)", "test"));
        store.assertFact(Fact.observed("child(carol, bob)", "test"));
        store.assertFact(Fact.observed("sibling(alice, carol)", "test")); // explicit but redundant

        LogicBasedReducer reducer = new LogicBasedReducer(1000, 5);
        LogicBasedReducer.ReductionResult result =
                reducer.reduceByRulesOnly(store, List.of(siblingRule));

        // sibling(alice, carol) should be identified as rule-entailed
        boolean removed = result.removedFacts().stream()
                .anyMatch(rf -> rf.fact().atomKey().equals("sibling(alice, carol)"));
        assertTrue(removed,
                "sibling(alice, carol) should be removed as rule-entailed; removed=" + result.removedFacts());

        // Basis marker
        result.removedFacts().stream()
                .filter(rf -> rf.fact().atomKey().equals("sibling(alice, carol)"))
                .forEach(rf -> assertEquals(LogicBasedReducer.REDUNDANCY_RULE, rf.basisMarker()));
    }

    // ─── Test 6: reduction is reversible via restore() ─────────────────────────

    @Test
    void reduction_isReversible_restoreReassertsFacts() {
        // a→b, b→c, a→c — a→c is redundant
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("ancestor(a, b)", "test"));
        store.assertFact(Fact.observed("ancestor(b, c)", "test"));
        store.assertFact(Fact.observed("ancestor(a, c)", "test"));

        LogicBasedReducer reducer = new LogicBasedReducer();
        LogicBasedReducer.ReductionResult result =
                reducer.reduceTransitive(store, Set.of("ancestor"));

        FactStore core = result.coreStore();
        assertEquals(2, core.size());

        // Restore removed facts into the core
        result.restore(core);

        // Now the core has all 3 facts again
        assertEquals(3, core.size(), "After restore, all facts are back");
        assertTrue(core.factFor("ancestor(a, c)").isPresent(), "Restored fact must be present");
    }

    // ─── Test 7: deductive marker identity ─────────────────────────────────────

    @Test
    void materialize_deductiveBasisMarker_isCorrect() {
        FactStore store = ancestorStore();
        List<DatalogRule> rules = ancestorRules();

        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer();
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
        materializer.materialize(store, rules, sink);

        for (InferredFact fact : sink.allLatest()) {
            assertTrue(
                    fact.supportingRuleIds().contains(ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER),
                    "Every materialized fact must carry " + ForwardChainingMaterializer.DEDUCTIVE_BASIS_MARKER
                            + " but " + fact.atomKey() + " has: " + fact.supportingRuleIds()
            );
            assertEquals(1.0, fact.value(), 1e-9);
            assertEquals(1.0, fact.confidence(), 1e-9);
        }
    }

    // ─── Test 8: empty rules / empty store are safe ────────────────────────────

    @Test
    void materialize_emptyRules_producesNoFacts() {
        FactStore store = ancestorStore();
        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer();
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
        ForwardChainingMaterializer.MaterializationResult result =
                materializer.materialize(store, List.of(), sink);
        assertEquals(0, result.derivedFactCount());
        assertTrue(sink.isEmpty());
    }

    @Test
    void reduce_emptyStore_producesNoRemovedFacts() {
        FactStore store = new FactStore();
        LogicBasedReducer reducer = new LogicBasedReducer();
        LogicBasedReducer.ReductionResult result =
                reducer.reduce(store, List.of(), Set.of("ancestor"));
        assertEquals(0, result.removedCount());
        assertEquals(0, result.coreStore().size());
    }

    // ─── Test 9: longer chain – three-hop closure ──────────────────────────────

    @Test
    void materialize_threeHopChain_derivesAllTransitivePairs() {
        // a→b, b→c, c→d  → should derive a→c, a→d, b→d
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("anc(a, b)", "test"));
        store.assertFact(Fact.observed("anc(b, c)", "test"));
        store.assertFact(Fact.observed("anc(c, d)", "test"));

        List<DatalogRule> rules = List.of(FolDatalogAdapter.transitivityRule("anc"));
        ForwardChainingMaterializer materializer = new ForwardChainingMaterializer(10, 100_000);
        InMemoryInferredFactStore sink = new InMemoryInferredFactStore();
        materializer.materialize(store, rules, sink);

        // All three transitive pairs should be derived
        assertDerived(sink, "anc(a, c)");
        assertDerived(sink, "anc(a, d)");
        assertDerived(sink, "anc(b, d)");
    }

    // ─── Test 10: non-transitive predicates untouched by transitive reduction ──

    @Test
    void reduce_nonTransitivePredicate_isNotTouched() {
        // likes(a, b), likes(b, c), likes(a, c)  — but "likes" is NOT declared transitive
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("likes(a, b)", "test"));
        store.assertFact(Fact.observed("likes(b, c)", "test"));
        store.assertFact(Fact.observed("likes(a, c)", "test"));

        LogicBasedReducer reducer = new LogicBasedReducer();
        // Reduce with "ancestor" as transitive — "likes" is not listed
        LogicBasedReducer.ReductionResult result =
                reducer.reduceTransitive(store, Set.of("ancestor"));

        assertEquals(0, result.removedCount(),
                "Non-transitive predicate must not be reduced");
        assertEquals(3, result.coreStore().size());
    }

    // ─── Test 11: parseSubjectObject helper ────────────────────────────────────

    @Test
    void parseSubjectObject_handlesCanonicalAndCompactForms() {
        String[] spaced = LogicBasedReducer.parseSubjectObject("anc(alice, bob)");
        assertNotNull(spaced);
        assertEquals("alice", spaced[0]);
        assertEquals("bob", spaced[1]);

        String[] compact = LogicBasedReducer.parseSubjectObject("anc(alice,bob)");
        assertNotNull(compact);
        assertEquals("alice", compact[0]);
        assertEquals("bob", compact[1]);

        // Unary atom — should return null
        assertFalse(LogicBasedReducer.parseSubjectObject("State(alice)") != null
                        && LogicBasedReducer.parseSubjectObject("State(alice)").length == 2,
                "Unary atom must not parse as binary");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static void assertDerived(InMemoryInferredFactStore sink, String atomKey) {
        assertTrue(sink.latest(atomKey).isPresent(),
                "Expected derived fact '" + atomKey + "' but it was absent; sink=" + debugSink(sink));
    }

    private static String debugSink(InMemoryInferredFactStore sink) {
        StringBuilder sb = new StringBuilder("[");
        for (InferredFact f : sink.allLatest()) {
            sb.append(f.atomKey()).append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}
