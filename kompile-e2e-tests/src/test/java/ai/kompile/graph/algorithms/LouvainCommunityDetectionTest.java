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

class LouvainCommunityDetectionTest {

    @Test
    void emptyGraphReturnsEmpty() {
        assertTrue(LouvainCommunityDetection.compute(AdjacencyView.builder().build()).isEmpty());
    }

    @Test
    void twoBlobsAreSeparated() {
        // Two dense triangles connected by a single weak edge.
        AdjacencyView view = AdjacencyView.builder()
                // blob 1
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "A", 1.0)
                // blob 2
                .addEdge("X", "Y", 1.0)
                .addEdge("Y", "Z", 1.0)
                .addEdge("Z", "X", 1.0)
                // weak bridge
                .addEdge("C", "X", 0.01)
                .build();
        Map<String, Integer> assignments = LouvainCommunityDetection.compute(view);
        Set<Integer> all = new HashSet<>(assignments.values());
        assertTrue(all.size() >= 2, "Expected at least 2 communities, got " + all.size());
        // A,B,C should all share a community; X,Y,Z should all share another
        assertEquals(assignments.get("A"), assignments.get("B"));
        assertEquals(assignments.get("A"), assignments.get("C"));
        assertEquals(assignments.get("X"), assignments.get("Y"));
        assertEquals(assignments.get("X"), assignments.get("Z"));
    }

    @Test
    void singleNodeAssignedToCommunityZero() {
        AdjacencyView view = AdjacencyView.builder().addNode("solo").build();
        Map<String, Integer> assignments = LouvainCommunityDetection.compute(view);
        assertEquals(1, assignments.size());
        assertEquals(0, assignments.get("solo"));
    }

    @Test
    void communityIdsAreDenseFromZero() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("X", "Y", 1.0)
                .build();
        Map<String, Integer> assignments = LouvainCommunityDetection.compute(view);
        Set<Integer> ids = new HashSet<>(assignments.values());
        int max = ids.stream().max(Integer::compareTo).orElse(-1);
        assertEquals(ids.size() - 1, max, "Community IDs should be 0..k-1");
    }
}
