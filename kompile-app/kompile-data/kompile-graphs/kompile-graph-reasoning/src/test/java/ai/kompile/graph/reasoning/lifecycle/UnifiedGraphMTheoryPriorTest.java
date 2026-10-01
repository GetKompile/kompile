/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.graph.reasoning.lifecycle;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnifiedGraphMTheoryPriorTest {

    @Test
    void relationActivationIsTheMeanRecordedConfidence() {
        UnifiedGraph graph = new UnifiedGraph()
                .graphId("prior")
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("bob", "PERSON", "Bob")
                .addEntity("acme", "COMPANY", "Acme")
                .addRelation(relation("r1", "alice", 0.6))
                .addRelation(relation("r2", "bob", 0.8));

        MTheory theory = UnifiedGraphReasoningLifecycle.buildMTheory(graph, 8);

        // Producers set weight equal to confidence; weight × confidence would give (0.36 + 0.64) / 2.
        assertEquals(0.7, theory.getMFrag("WORKS_AT").getEdgeStrength("isRelevant", "WORKS_AT"), 1e-9);
    }

    private static SimpleGraphRelation relation(String id, String sourceId, double confidence) {
        return new SimpleGraphRelation(id, sourceId, "acme", "WORKS_AT", confidence, confidence,
                true, Set.of(), null, null, Map.of());
    }
}
