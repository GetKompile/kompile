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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageRankAlgorithmTest {

    @Test
    void emptyGraphReturnsEmptyMap() {
        Map<String, Double> rank = PageRankAlgorithm.compute(AdjacencyView.builder().build());
        assertTrue(rank.isEmpty());
    }

    @Test
    void singleNodeRanksOne() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        Map<String, Double> rank = PageRankAlgorithm.compute(view);
        assertEquals(1.0, rank.get("A"), 1e-9);
    }

    @Test
    void hubAttractsHigherRank() {
        // A, B, C all link to H; H is the obvious hub.
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "H", 1.0)
                .addEdge("B", "H", 1.0)
                .addEdge("C", "H", 1.0)
                .build();
        Map<String, Double> rank = PageRankAlgorithm.compute(view);
        assertEquals("H", rank.entrySet().iterator().next().getKey(),
                "Hub should be top-ranked, got " + rank);
        assertTrue(rank.get("H") > rank.get("A"));
    }

    @Test
    void rankSumApproximatelyOne() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "A", 1.0)
                .addEdge("C", "D", 1.0)
                .build();
        Map<String, Double> rank = PageRankAlgorithm.compute(view);
        double sum = rank.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-3, "PageRank sum should be ~1.0, got " + sum);
    }

    @Test
    void resultIsSortedDescending() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "H", 1.0)
                .addEdge("B", "H", 1.0)
                .addEdge("C", "H", 1.0)
                .addEdge("H", "T", 1.0)
                .build();
        Map<String, Double> rank = PageRankAlgorithm.compute(view);
        double prev = Double.POSITIVE_INFINITY;
        for (double v : rank.values()) {
            assertTrue(v <= prev, "Ranks should be non-increasing");
            prev = v;
        }
    }
}
