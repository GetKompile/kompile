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

class BetweennessCentralityTest {

    @Test
    void emptyGraphReturnsEmpty() {
        assertTrue(BetweennessCentrality.compute(AdjacencyView.builder().build()).isEmpty());
    }

    @Test
    void starGraphCenterHasMaximumBetweenness() {
        // C is the center of a 4-node star: A-C, B-C, D-C
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "C", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("D", "C", 1.0)
                .build();
        Map<String, Double> bc = BetweennessCentrality.compute(view);
        // C should be top, leaves should be 0
        assertEquals("C", bc.entrySet().iterator().next().getKey());
        assertEquals(0.0, bc.get("A"), 1e-9);
        assertEquals(0.0, bc.get("B"), 1e-9);
        assertEquals(0.0, bc.get("D"), 1e-9);
        assertTrue(bc.get("C") > 0);
    }

    @Test
    void linearChainMiddleHasHighestBetweenness() {
        // A-B-C-D-E: C should have the highest betweenness
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "D", 1.0)
                .addEdge("D", "E", 1.0)
                .build();
        Map<String, Double> bc = BetweennessCentrality.compute(view);
        assertTrue(bc.get("C") >= bc.get("B"));
        assertTrue(bc.get("C") >= bc.get("D"));
        assertEquals(0.0, bc.get("A"), 1e-9);
        assertEquals(0.0, bc.get("E"), 1e-9);
    }

    @Test
    void samplingProducesNonZeroEstimates() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "D", 1.0)
                .addEdge("D", "E", 1.0)
                .addEdge("E", "F", 1.0)
                .build();
        Map<String, Double> sampled = BetweennessCentrality.compute(view, 3, 42L);
        assertEquals(6, sampled.size());
        assertTrue(sampled.values().stream().anyMatch(v -> v > 0));
    }
}
