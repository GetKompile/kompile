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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * PageRank via power iteration. Delegates to {@link MatrixGraphAlgorithms#pageRank}
 * so the ND4J-based implementation is shared with the matrix-graph subsystem.
 * For large graphs ({@code n > }{@link AdjacencyView#DENSE_NODE_CAP}) uses a pure
 * adjacency-list power iteration instead of materializing the dense {@code [n×n]} matrix.
 */
public final class PageRankAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(PageRankAlgorithm.class);

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
        Map<String, Double> raw;
        if (view.isLarge()) {
            // Sparse adjacency-list PPR with uniform restart — identical result, no dense [n×n].
            int n = view.size();
            double u = 1.0 / n;
            Map<String, Double> uniform = new HashMap<>(n * 2);
            for (String id : view.nodeIds()) {
                uniform.put(id, u);
            }
            raw = pageRankSparseView(view, uniform, damping, tolerance, maxIterations);
        } else {
            raw = MatrixGraphAlgorithms.pageRank(
                    view.toAdjacencyMatrix(), view.nodeIds(), damping, tolerance, maxIterations);
        }
        return raw.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                         (acc, e) -> acc.put(e.getKey(), e.getValue()),
                         LinkedHashMap::putAll);
    }

    /**
     * Adjacency-list PageRank power iteration for large {@link AdjacencyView} instances.
     * No dense {@code [n×n]} matrix — pure Java HashMap iteration, O(E) per step, O(V+E) memory.
     *
     * @param restart pre-normalized restart distribution (sum = 1)
     */
    static Map<String, Double> pageRankSparseView(AdjacencyView view,
                                                   Map<String, Double> restart,
                                                   double dampingFactor,
                                                   double convergence,
                                                   int maxIterations) {
        List<String> nodeIds = view.nodeIds();
        int n = nodeIds.size();
        if (n == 0) return new HashMap<>();

        // Pre-compute weighted out-degree per node (once).
        Map<String, Double> outDeg = new HashMap<>(n * 2);
        for (String u : nodeIds) {
            double sum = 0.0;
            for (String v : view.outNeighbors(u)) {
                sum += view.weight(u, v);
            }
            outDeg.put(u, sum);
        }

        Map<String, Double> pr = new HashMap<>(restart);
        for (String id : nodeIds) {
            pr.putIfAbsent(id, 0.0);
        }

        for (int iter = 0; iter < maxIterations; iter++) {
            Map<String, Double> flow = new HashMap<>(n * 2);
            double danglingMass = 0.0;
            for (String u : nodeIds) {
                double deg = outDeg.getOrDefault(u, 0.0);
                double pu = pr.getOrDefault(u, 0.0);
                if (deg <= 0.0) {
                    danglingMass += pu;
                } else {
                    double share = pu / deg;
                    for (String v : view.outNeighbors(u)) {
                        flow.merge(v, share * view.weight(u, v), Double::sum);
                    }
                }
            }
            double restartScale = (1.0 - dampingFactor) + dampingFactor * danglingMass;
            double diff = 0.0;
            Map<String, Double> prNew = new HashMap<>(n * 2);
            for (String id : nodeIds) {
                double teleport = restart.getOrDefault(id, 0.0) * restartScale;
                double f = flow.getOrDefault(id, 0.0) * dampingFactor;
                double val = teleport + f;
                prNew.put(id, val);
                diff += Math.abs(val - pr.getOrDefault(id, 0.0));
            }
            pr = prNew;
            if (diff < convergence) {
                log.debug("Sparse PageRank (AdjacencyView) converged after {} iterations", iter + 1);
                break;
            }
        }
        return pr;
    }
}
