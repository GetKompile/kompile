/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms.adjacency;

import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight in-memory adjacency representation of a knowledge subgraph.
 *
 * <p>Algorithms in this module operate on this view rather than directly on JPA entities,
 * so they remain pure and testable.
 */
public final class AdjacencyView {

    private final List<String> nodeIds;
    private final Map<String, Integer> indexById;
    private final Map<String, List<String>> outNeighbors;
    private final Map<String, List<String>> inNeighbors;
    private final Map<String, Map<String, Double>> outWeights;

    private AdjacencyView(List<String> nodeIds,
                          Map<String, List<String>> outNeighbors,
                          Map<String, List<String>> inNeighbors,
                          Map<String, Map<String, Double>> outWeights) {
        this.nodeIds = List.copyOf(nodeIds);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            idx.put(nodeIds.get(i), i);
        }
        this.indexById = Map.copyOf(idx);
        this.outNeighbors = outNeighbors;
        this.inNeighbors = inNeighbors;
        this.outWeights = outWeights;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> nodeIds() {
        return nodeIds;
    }

    public int size() {
        return nodeIds.size();
    }

    public int indexOf(String nodeId) {
        Integer i = indexById.get(nodeId);
        return i == null ? -1 : i;
    }

    public String nodeAt(int index) {
        return nodeIds.get(index);
    }

    public List<String> outNeighbors(String nodeId) {
        return outNeighbors.getOrDefault(nodeId, Collections.emptyList());
    }

    public List<String> inNeighbors(String nodeId) {
        return inNeighbors.getOrDefault(nodeId, Collections.emptyList());
    }

    public double weight(String from, String to) {
        Map<String, Double> w = outWeights.get(from);
        if (w == null) return 0.0;
        return w.getOrDefault(to, 0.0);
    }

    /**
     * Returns the set of all undirected neighbors (in ∪ out) of a node.
     */
    public Set<String> neighbors(String nodeId) {
        Set<String> all = new HashSet<>(outNeighbors(nodeId));
        all.addAll(inNeighbors(nodeId));
        return all;
    }

    /**
     * Total number of directed edges in the view.
     */
    public long edgeCount() {
        long total = 0;
        for (List<String> neighbors : outNeighbors.values()) {
            total += neighbors.size();
        }
        return total;
    }

    /**
     * Materializes the view as a dense [n x n] float adjacency matrix where
     * {@code M[i, j] = weight(nodeIds[i], nodeIds[j])}.
     *
     * <p>Used by algorithms that benefit from vectorized BLAS ops (PageRank via mmul,
     * degree via row/col sums, weakly connected components via A + A<sup>T</sup>).
     */
    public INDArray toAdjacencyMatrix() {
        int n = nodeIds.size();
        if (n == 0) {
            return Nd4j.zeros(DataType.FLOAT, 0, 0);
        }
        INDArray adj = Nd4j.zeros(DataType.FLOAT, n, n);
        for (Map.Entry<String, Map<String, Double>> row : outWeights.entrySet()) {
            Integer i = indexById.get(row.getKey());
            if (i == null) continue;
            for (Map.Entry<String, Double> e : row.getValue().entrySet()) {
                Integer j = indexById.get(e.getKey());
                if (j == null) continue;
                adj.putScalar(i, j, e.getValue());
            }
        }
        return adj;
    }

    public static final class Builder {
        private final List<String> nodes = new ArrayList<>();
        private final Set<String> nodeSet = new HashSet<>();
        private final Map<String, List<String>> out = new HashMap<>();
        private final Map<String, List<String>> in = new HashMap<>();
        private final Map<String, Map<String, Double>> weights = new HashMap<>();

        public Builder addNode(String nodeId) {
            if (nodeSet.add(nodeId)) {
                nodes.add(nodeId);
            }
            return this;
        }

        public Builder addEdge(String from, String to, double weight) {
            addNode(from);
            addNode(to);
            out.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            in.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            weights.computeIfAbsent(from, k -> new HashMap<>()).put(to, weight);
            return this;
        }

        public AdjacencyView build() {
            return new AdjacencyView(nodes, out, in, weights);
        }
    }
}
