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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaklyConnectedComponentsTest {

    @Test
    void singleConnectedGraphProducesOneComponent() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "D", 1.0)
                .build();
        Map<String, Integer> assignments = WeaklyConnectedComponents.compute(view);
        assertEquals(1, new HashSet<>(assignments.values()).size());
    }

    @Test
    void disjointSubgraphsProduceTwoComponents() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("X", "Y", 1.0)
                .addEdge("Y", "Z", 1.0)
                .build();
        Map<String, Integer> assignments = WeaklyConnectedComponents.compute(view);
        Set<Integer> components = new HashSet<>(assignments.values());
        assertEquals(2, components.size(), "Expected two components, got " + assignments);
        assertEquals(assignments.get("A"), assignments.get("C"));
        assertEquals(assignments.get("X"), assignments.get("Z"));
    }

    @Test
    void isolatedNodeFormsItsOwnComponent() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addNode("Z")
                .build();
        Map<String, Integer> assignments = WeaklyConnectedComponents.compute(view);
        assertEquals(2, new HashSet<>(assignments.values()).size());
        assertTrue(assignments.containsKey("Z"));
    }

    @Test
    void emptyGraphReturnsEmptyMap() {
        assertTrue(WeaklyConnectedComponents.compute(AdjacencyView.builder().build()).isEmpty());
    }
}
