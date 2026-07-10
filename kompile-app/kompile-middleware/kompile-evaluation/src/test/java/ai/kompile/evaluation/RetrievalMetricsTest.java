/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retrieval-quality metrics (grounded-RAG rec 5): recall@k, precision@k, hit@k, MRR, nDCG@k, and their
 * corpus averages, verified against hand-computed values.
 */
class RetrievalMetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void recallAtK() {
        assertEquals(1.0 / 3.0, RetrievalMetrics.recallAtK(List.of("a", "b", "c", "d"), Set.of("a", "c", "e"), 2), EPS);
        assertEquals(1.0, RetrievalMetrics.recallAtK(List.of("a", "b", "c", "d"), Set.of("a", "c"), 3), EPS);
        assertEquals(0.0, RetrievalMetrics.recallAtK(List.of("a", "b"), Set.of(), 2), EPS); // no relevant
    }

    @Test
    void precisionAtK() {
        assertEquals(0.5, RetrievalMetrics.precisionAtK(List.of("a", "b", "c", "d"), Set.of("a", "c"), 2), EPS);
        assertEquals(0.0, RetrievalMetrics.precisionAtK(List.of("a", "b"), Set.of("a"), 0), EPS);
    }

    @Test
    void hitAtK() {
        assertEquals(1.0, RetrievalMetrics.hitAtK(List.of("a", "b"), Set.of("c", "a"), 1), EPS);
        assertEquals(0.0, RetrievalMetrics.hitAtK(List.of("a", "b"), Set.of("c"), 2), EPS);
    }

    @Test
    void reciprocalRank() {
        assertEquals(0.5, RetrievalMetrics.reciprocalRank(List.of("b", "a", "c"), Set.of("a")), EPS); // rank 2
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(List.of("b", "c"), Set.of("a")), EPS);       // not found
    }

    @Test
    void ndcgAtK() {
        // relevant at rank 1 → perfect
        assertEquals(1.0, RetrievalMetrics.ndcgAtK(List.of("a", "b"), Set.of("a"), 2), EPS);
        // relevant at rank 2 → 1/log2(3) discounted vs ideal 1.0
        assertEquals(1.0 / (Math.log(3) / Math.log(2)),
                RetrievalMetrics.ndcgAtK(List.of("b", "a"), Set.of("a"), 2), EPS);
    }

    @Test
    void corpusAverages() {
        List<RetrievalMetrics.RankedResult> corpus = List.of(
                new RetrievalMetrics.RankedResult(List.of("b", "a"), Set.of("a")),
                new RetrievalMetrics.RankedResult(List.of("a"), Set.of("a")));
        assertEquals(0.75, RetrievalMetrics.meanReciprocalRank(corpus), EPS); // (0.5 + 1.0)/2

        List<RetrievalMetrics.RankedResult> corpus2 = List.of(
                new RetrievalMetrics.RankedResult(List.of("a", "b"), Set.of("a", "c")),
                new RetrievalMetrics.RankedResult(List.of("c", "d"), Set.of("c")));
        assertEquals(0.75, RetrievalMetrics.meanRecallAtK(corpus2, 2), EPS); // (0.5 + 1.0)/2

        assertEquals(0.0, RetrievalMetrics.meanReciprocalRank(List.of()), EPS); // empty corpus
    }
}
