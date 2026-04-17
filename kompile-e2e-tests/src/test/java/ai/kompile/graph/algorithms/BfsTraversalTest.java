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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BfsTraversalTest {

    @Test
    void groupsNodesByDepthLevel() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("A", "C", 1.0)
                .addEdge("B", "D", 1.0)
                .addEdge("C", "E", 1.0)
                .build();
        Map<Integer, List<String>> levels = BfsTraversal.traverse(view, "A", 3);
        assertEquals(List.of("A"), levels.get(0));
        assertEquals(2, levels.get(1).size());
        assertEquals(2, levels.get(2).size());
        assertTrue(levels.get(2).containsAll(List.of("D", "E")));
    }

    @Test
    void respectsMaxDepth() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "B", 1.0)
                .addEdge("B", "C", 1.0)
                .addEdge("C", "D", 1.0)
                .build();
        Map<Integer, List<String>> levels = BfsTraversal.traverse(view, "A", 1);
        assertEquals(2, levels.size());
        assertTrue(levels.containsKey(0));
        assertTrue(levels.containsKey(1));
    }

    @Test
    void unknownStartReturnsEmpty() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        assertTrue(BfsTraversal.traverse(view, "Z", 5).isEmpty());
    }

    @Test
    void negativeDepthReturnsEmpty() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        assertTrue(BfsTraversal.traverse(view, "A", -1).isEmpty());
    }
}
