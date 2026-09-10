/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.fol.grounding.ConcurrentFactStore;
import ai.kompile.graph.reasoning.fol.materialization.LogicBasedReducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactStoreMultiSourceTest {

    @Test
    void effectiveViewUsesAssertionOrderAndSourceRetractionRevealsFallback() {
        FactStore store = new FactStore();
        Fact user = Fact.soft("State(alice)", 0.6, "user", Instant.parse("2025-01-02T00:00:00Z"));
        Fact graph = Fact.soft("State(alice)", 0.9, "graph", Instant.parse("2025-01-03T00:00:00Z"));
        Fact userRevision = Fact.soft(
                "State(alice)", 0.7, "user", Instant.parse("2025-01-01T00:00:00Z"));

        store.assertFact(user);
        store.assertFact(graph);
        store.assertFact(userRevision);

        assertEquals(userRevision, store.factFor("State(alice)").orElseThrow());
        assertEquals(1, store.size());
        assertEquals(1, store.allFacts().size());
        assertEquals(2, store.allSourceFacts().size());
        assertEquals(1, store.retractBySource("user"));
        assertEquals(graph, store.factFor("State(alice)").orElseThrow());
        assertEquals(1, store.size());
    }

    @Test
    void atomRetractionRemovesEverySourceSlot() {
        FactStore store = new FactStore();
        store.assertFact(Fact.observed("State(alice)", "user"));
        Fact effective = Fact.soft("State(alice)", 0.8, "graph");
        store.assertFact(effective);

        assertEquals(effective, store.retract("State(alice)").orElseThrow());
        assertTrue(store.isEmpty());
        assertTrue(store.allSourceFacts().isEmpty());
    }

    @Test
    void concurrentCopyPreservesHiddenSourcesAndMvccConflictSemantics() {
        ConcurrentFactStore concurrent = new ConcurrentFactStore();
        long first = concurrent.assertFact(Fact.observed("State(alice)", "user"));
        long second = concurrent.assertFact(Fact.soft("State(alice)", 0.8, "graph"), first);
        assertTrue(second > first);
        assertEquals(ConcurrentFactStore.CONFLICT,
                concurrent.assertFact(Fact.observed("Other(x)", "late"), first));

        FactStore copied = new FactStore();
        concurrent.applyTo(copied);
        assertEquals("graph", copied.factFor("State(alice)").orElseThrow().sourceId());
        assertEquals(1, copied.retractBySource("graph"));
        assertEquals("user", copied.factFor("State(alice)").orElseThrow().sourceId());
        assertFalse(concurrent.snapshot().facts().isEmpty());
        assertEquals(second, concurrent.snapshot().version());
    }

    @Test
    void reducerRestorationReassertsEveryHiddenSource() {
        Fact user = Fact.soft("Related(a, b)", 0.6, "user");
        Fact graph = Fact.soft("Related(a, b)", 0.9, "graph");
        LogicBasedReducer.RemovedFact removed = new LogicBasedReducer.RemovedFact(
                graph, LogicBasedReducer.REDUNDANCY_RULE, "test", java.util.List.of(user, graph));
        FactStore restored = new FactStore();

        new LogicBasedReducer.ReductionResult(new FactStore(), java.util.List.of(removed))
                .restore(restored);

        assertEquals(graph, restored.factFor("Related(a, b)").orElseThrow());
        restored.retractBySource("graph");
        assertEquals(user, restored.factFor("Related(a, b)").orElseThrow());
    }
}
