/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms;

import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortestPathAlgorithmTest {

    @Test
    void bfsReturnsPathInDirectedGraph() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "D", 1.0)
                .addEdge("A", "D", 1.0)  // direct shortcut
                .build();
        ShortestPathAlgorithm.PathResult r = ShortestPathAlgorithm.bfs(view, "A", "D");
        assertTrue(r.found());
        assertEquals(List.of("A", "D"), r.path());
        assertEquals(1.0, r.length());
    }

    @Test
    void bfsSelfLoopIsZeroLength() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        ShortestPathAlgorithm.PathResult r = ShortestPathAlgorithm.bfs(view, "A", "A");
        assertTrue(r.found());
        assertEquals(0.0, r.length());
    }

    @Test
    void bfsUnreachableReturnsNotFound() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addNode("X")
                .build();
        ShortestPathAlgorithm.PathResult r = ShortestPathAlgorithm.bfs(view, "A", "X");
        assertFalse(r.found());
    }

    @Test
    void bfsMissingEndpointReturnsNotFound() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        assertFalse(ShortestPathAlgorithm.bfs(view, "A", "Z").found());
        assertFalse(ShortestPathAlgorithm.bfs(view, "Z", "A").found());
    }

    @Test
    void dijkstraPrefersHigherWeightPath() {
        // A->B->C low weight (cost 1+1=2)
        // A->C direct, weight 0.1 -> cost 10. Should pick A->B->C.
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("A", "C", 0.1)
                .build();
        ShortestPathAlgorithm.PathResult r = ShortestPathAlgorithm.dijkstra(view, "A", "C");
        assertTrue(r.found());
        assertEquals(List.of("A", "B", "C"), r.path());
    }

    @Test
    void dijkstraUnreachableReturnsNotFound() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addNode("Z")
                .build();
        assertFalse(ShortestPathAlgorithm.dijkstra(view, "A", "Z").found());
    }
}
