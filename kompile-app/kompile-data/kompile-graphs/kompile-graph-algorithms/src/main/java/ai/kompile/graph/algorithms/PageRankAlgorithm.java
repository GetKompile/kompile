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
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PageRank via power iteration. Delegates to {@link MatrixGraphAlgorithms#pageRank}
 * so the ND4J-based implementation is shared with the matrix-graph subsystem.
 */
public final class PageRankAlgorithm {

    public static final double DEFAULT_DAMPING = 0.85;
    public static final int DEFAULT_MAX_ITERATIONS = 100;
    public static final double DEFAULT_TOLERANCE = 1e-6;

    private PageRankAlgorithm() {}

    public static Map<String, Double> compute(AdjacencyView view) {
        return compute(view, DEFAULT_DAMPING, DEFAULT_MAX_ITERATIONS, DEFAULT_TOLERANCE);
    }

    public static Map<String, Double> compute(AdjacencyView view,
                                                double damping,
                                                int maxIterations,
                                                double tolerance) {
        Map<String, Double> raw = MatrixGraphAlgorithms.pageRank(
                view.toAdjacencyMatrix(), view.nodeIds(), damping, tolerance, maxIterations);
        return raw.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                         (acc, e) -> acc.put(e.getKey(), e.getValue()),
                         LinkedHashMap::putAll);
    }
}
