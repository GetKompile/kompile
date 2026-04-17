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

class DegreeCentralityTest {

    @Test
    void inDegreeCountsIncomingEdges() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "C", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("D", "C", 1.0)
                .addEdge("C", "E", 1.0)
                .build();
        Map<String, Double> in = DegreeCentrality.compute(view, DegreeCentrality.Type.IN);
        assertEquals(3.0, in.get("C"));
        assertEquals(0.0, in.get("A"));
        assertEquals(1.0, in.get("E"));
    }

    @Test
    void outDegreeCountsOutgoingEdges() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("A", "C", 1.0)
                .addEdge("A", "D", 1.0)
                .build();
        Map<String, Double> out = DegreeCentrality.compute(view, DegreeCentrality.Type.OUT);
        assertEquals(3.0, out.get("A"));
        assertEquals(0.0, out.get("B"));
    }

    @Test
    void totalDegreeReturnsUndirectedCount() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("C", "A", 1.0)
                .build();
        Map<String, Double> total = DegreeCentrality.compute(view, DegreeCentrality.Type.TOTAL);
        assertEquals(2.0, total.get("A"));
    }

    @Test
    void resultsAreSortedDescending() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "C", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("D", "C", 1.0)
                .build();
        Map<String, Double> in = DegreeCentrality.compute(view, DegreeCentrality.Type.IN);
        assertEquals("C", in.entrySet().iterator().next().getKey());
    }
}
