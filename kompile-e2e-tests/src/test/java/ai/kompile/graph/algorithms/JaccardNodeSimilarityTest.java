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
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaccardNodeSimilarityTest {

    @Test
    void identicalNeighborhoodsScoreOne() {
        // A and B both connect to {X, Y}.
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "X", 1.0)
                .addEdge("A", "Y", 1.0)
                .addEdge("B", "X", 1.0)
                .addEdge("B", "Y", 1.0)
                .build();
        assertEquals(1.0, JaccardNodeSimilarity.similarity(view, "A", "B"), 1e-9);
    }

    @Test
    void disjointNeighborhoodsScoreZero() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "X", 1.0)
                .addEdge("B", "Y", 1.0)
                .build();
        assertEquals(0.0, JaccardNodeSimilarity.similarity(view, "A", "B"), 1e-9);
    }

    @Test
    void partialOverlapMatchesFormula() {
        // A: {X, Y}, B: {Y, Z}. Intersection={Y}=1, Union={X,Y,Z}=3 → 1/3.
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "X", 1.0)
                .addEdge("A", "Y", 1.0)
                .addEdge("B", "Y", 1.0)
                .addEdge("B", "Z", 1.0)
                .build();
        assertEquals(1.0 / 3.0, JaccardNodeSimilarity.similarity(view, "A", "B"), 1e-9);
    }

    @Test
    void selfSimilarityIsOne() {
        AdjacencyView view = AdjacencyView.builder().addNode("A").build();
        assertEquals(1.0, JaccardNodeSimilarity.similarity(view, "A", "A"), 1e-9);
    }

    @Test
    void topKSortsByScoreDescending() {
        AdjacencyView view = AdjacencyView.builder()
                .addEdge("A", "X", 1.0)
                .addEdge("A", "Y", 1.0)
                .addEdge("B", "X", 1.0)
                .addEdge("B", "Y", 1.0)
                .addEdge("C", "X", 1.0)
                .build();
        List<JaccardNodeSimilarity.SimilarityPair> top = JaccardNodeSimilarity.topK(view, 5, 0.0);
        assertTrue(top.get(0).score() >= top.get(top.size() - 1).score());
    }
}
