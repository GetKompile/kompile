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
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Degree centrality (raw counts). Delegates to {@link MatrixGraphAlgorithms#degrees}
 * so the counting logic is shared with the matrix-graph subsystem.
 */
public final class DegreeCentrality {

    public enum Type { IN, OUT, TOTAL }

    private DegreeCentrality() {}

    public static Map<String, Double> compute(AdjacencyView view, Type type) {
        List<String> nodeIds = view.nodeIds();
        int n = nodeIds.size();
        if (n == 0) return Map.of();

        MatrixGraphAlgorithms.DegreeType matrixType = switch (type) {
            case IN -> MatrixGraphAlgorithms.DegreeType.IN;
            case OUT -> MatrixGraphAlgorithms.DegreeType.OUT;
            case TOTAL -> MatrixGraphAlgorithms.DegreeType.TOTAL;
        };
        INDArray degrees = MatrixGraphAlgorithms.degrees(view.toAdjacencyMatrix(), matrixType);

        Map<String, Double> unsorted = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            unsorted.put(nodeIds.get(i), degrees.getDouble(i));
        }
        return unsorted.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                         (acc, e) -> acc.put(e.getKey(), e.getValue()),
                         LinkedHashMap::putAll);
    }
}
