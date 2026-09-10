/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphKgeLifecycleTest {

    @Test
    void trainsAndWarmStartsEntityAndRelationVectors() {
        UnifiedGraph graph = graph()
                .putEntityVector("semantic", "alice", new double[]{0.25, 0.75})
                .putEntityOpinion("alice", Opinion.fromSoftTruth(0.8));
        UnifiedGraphKgeLifecycle.Config cold = new UnifiedGraphKgeLifecycle.Config(
                true, "TRANSE", 4, 3, 0.05, 1, 7L);

        UnifiedGraphKgeLifecycle.Summary first = UnifiedGraphKgeLifecycle.learn(graph, cold);
        assertTrue(first.enabled());
        assertFalse(first.warmStarted());
        assertEquals(3, first.epochs());
        assertEquals(4, graph.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER).dim());
        double[] priorEntity = graph.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER)
                .get("alice").clone();
        double[] priorRelation = graph.vectorLayer(UnifiedGraphKgeLifecycle.RELATION_LAYER)
                .get("WORKS_AT").clone();

        UnifiedGraphKgeLifecycle.Config warm = new UnifiedGraphKgeLifecycle.Config(
                true, "TRANSE", 4, 20, 0.05, 0, 999L);
        UnifiedGraphKgeLifecycle.Summary second = UnifiedGraphKgeLifecycle.learn(graph, warm);

        assertTrue(second.warmStarted());
        assertEquals(0, second.epochs());
        assertArrayEquals(priorEntity,
                graph.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER).get("alice"));
        assertArrayEquals(priorRelation,
                graph.vectorLayer(UnifiedGraphKgeLifecycle.RELATION_LAYER).get("WORKS_AT"));
        assertNotNull(graph.artifactText(UnifiedGraphKgeLifecycle.MODEL_ARTIFACT));
        assertArrayEquals(new double[]{0.25, 0.75},
                graph.vectorLayer("semantic").get("alice"));
        assertNotNull(graph.entityOpinion("alice"));
    }

    @Test
    void rotateUsesDoubleWidthEntitiesAndAlgorithmChangeColdStarts() {
        UnifiedGraph graph = graph();
        UnifiedGraphKgeLifecycle.learn(graph, new UnifiedGraphKgeLifecycle.Config(
                true, "TRANSE", 4, 1, 0.05, 1, 3L));

        UnifiedGraphKgeLifecycle.Summary rotate = UnifiedGraphKgeLifecycle.learn(graph,
                new UnifiedGraphKgeLifecycle.Config(true, "ROTATE", 4, 2, 0.05, 1, 3L));

        assertFalse(rotate.warmStarted());
        assertEquals(8, graph.vectorLayer(UnifiedGraphKgeLifecycle.ENTITY_LAYER).dim());
        assertEquals(4, graph.vectorLayer(UnifiedGraphKgeLifecycle.RELATION_LAYER).dim());
    }

    private UnifiedGraph graph() {
        return new UnifiedGraph()
                .graphId("test-graph")
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("acme", "COMPANY", "Acme")
                .addRelation("r1", "alice", "acme", "WORKS_AT", 0.9);
    }
}
