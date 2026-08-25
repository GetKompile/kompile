/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.graph.algorithms;

import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LouvainCommunityDetectionTest {

    @Test
    void sparseModularityMatchesTwoDisconnectedPairs() {
        AdjacencyView graph = AdjacencyView.builder()
                .addEdge("a", "b", 1.0)
                .addEdge("c", "d", 1.0)
                .build();

        Map<String, Integer> assignment = LouvainCommunityDetection.compute(graph);

        assertEquals(assignment.get("a"), assignment.get("b"));
        assertEquals(assignment.get("c"), assignment.get("d"));
        assertNotEquals(assignment.get("a"), assignment.get("c"));
        assertEquals(0.5, LouvainCommunityDetection.modularity(graph, assignment), 1.0e-9);
    }
}
