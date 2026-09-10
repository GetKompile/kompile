/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.matrix.store;

import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMatrixGraphGenerationTest {

    @Test
    void hiddenGenerationSwitchesAtomicallyAndRollsBack() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        String logical = "factsheet_42";
        store.createGraph(logical, 42L);
        store.addNode(logical, node("old"));

        var generation = store.beginGeneration(42L, logical, "g1");
        store.addNode(generation.physicalGraphId(), node("new"));

        assertTrue(store.getNode(logical, "old").isPresent());
        assertTrue(store.getNode(logical, "new").isEmpty());
        assertEquals(1, store.validateGeneration(generation).nodeCount());
        assertEquals(java.util.List.of(logical), store.listGraphs());

        var activated = store.activateGeneration(generation);
        assertTrue(store.getNode(logical, "new").isPresent());
        assertTrue(store.getNode(logical, "old").isEmpty());

        var rolledBack = store.rollbackGeneration(42L, logical, activated.revision());
        assertTrue(store.getNode(logical, "old").isPresent());
        assertEquals(activated.revision() + 1, rolledBack.revision());
    }

    @Test
    void activationUsesRevisionCompareAndSwapAndAbortKeepsActiveGraph() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        String logical = "factsheet_7";
        store.createGraph(logical, 7L);
        store.addNode(logical, node("active"));
        var first = store.beginGeneration(7L, logical, "first");
        var stale = store.beginGeneration(7L, logical, "stale");
        store.addNode(first.physicalGraphId(), node("first-node"));
        store.addNode(stale.physicalGraphId(), node("stale-node"));

        store.activateGeneration(first);
        assertThrows(IllegalStateException.class, () -> store.activateGeneration(stale));
        store.abortGeneration(stale);

        assertTrue(store.getNode(logical, "first-node").isPresent());
        assertFalse(store.listGraphs().contains(stale.physicalGraphId()));
    }

    @Test
    void deletingLogicalGraphRemovesActivePreviousAndOrphanGenerations() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        String logical = "factsheet_9";
        store.createGraph(logical, 9L);
        var active = store.beginGeneration(9L, logical, "active");
        var orphan = store.beginGeneration(9L, logical, "orphan");
        store.addNode(active.physicalGraphId(), node("active-node"));
        store.addNode(orphan.physicalGraphId(), node("orphan-node"));
        store.activateGeneration(active);

        assertTrue(store.deleteGraph(logical));
        assertTrue(store.loadGraph(logical).isEmpty());
        assertTrue(store.loadGraph(active.physicalGraphId()).isEmpty());
        assertTrue(store.loadGraph(orphan.physicalGraphId()).isEmpty());
        assertTrue(store.listGraphs().isEmpty());
    }

    @Test
    void generationReferenceRejectsNonCanonicalPhysicalId() {
        assertThrows(IllegalArgumentException.class, () -> new GraphGeneration.Ref(
                9L, "factsheet_9", "unscoped", "g1", "factsheet_9", 0L));
    }

    @Test
    void rollbackTargetCannotBeAbortedOrDeletedAndIncidentEdgesAreRemoved() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        String logical = "factsheet_12";
        store.createGraph(logical, 12L);
        var first = store.beginGeneration(12L, logical, "first");
        store.addNode(first.physicalGraphId(), node("a"));
        store.addNode(first.physicalGraphId(), node("b"));
        store.addEdge(first.physicalGraphId(), "a", "b", 1.0, "RELATED", false);
        store.removeNode(first.physicalGraphId(), "b");
        assertTrue(store.validateGeneration(first).valid());
        assertEquals(0, store.validateGeneration(first).edgeCount());
        store.activateGeneration(first);

        var second = store.beginGeneration(12L, logical, "second");
        store.addNode(second.physicalGraphId(), node("c"));
        var secondActivation = store.activateGeneration(second);

        assertThrows(IllegalStateException.class, () -> store.abortGeneration(first));
        assertFalse(store.deleteGraph(first.physicalGraphId()));
        store.rollbackGeneration(12L, logical, secondActivation.revision());
        assertTrue(store.getNode(logical, "a").isPresent());
    }

    private static MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("ENTITY").title(id).build();
    }
}
