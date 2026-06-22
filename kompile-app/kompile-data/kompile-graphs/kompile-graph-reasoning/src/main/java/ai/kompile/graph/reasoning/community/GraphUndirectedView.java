/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.community;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A precomputed, undirected, weighted adjacency view of a {@link ReasoningGraph} for use
 * by community-detection algorithms.
 *
 * <p>Each directed relation {@code (u, v, weight)} contributes to {@code adj[u][v]} AND
 * {@code adj[v][u]} symmetrically. Multiple relations between the same pair of nodes are
 * aggregated by summing their effective weights.</p>
 *
 * <p>Effective weight of a single relation is {@code weight * confidence} (element-wise product).
 * For unit-confidence graphs this reduces to bare {@code weight}. For graphs where no explicit
 * weight is set ({@code weight == 0.0}), a unit weight {@code 1.0} is substituted so that
 * presence of a relation always contributes positive affinity.</p>
 *
 * <p>Package-private: only community-detection implementations within this package use it.</p>
 */
final class GraphUndirectedView {

    /** Stable list of node ids (insertion-order of ReasoningGraph.entities()). */
    final List<String> nodes;

    /** Index: node id → position in {@link #nodes}. */
    final Map<String, Integer> index;

    /**
     * Adjacency as a symmetric matrix. {@code adj[i][j]} holds the aggregated edge weight
     * between nodes {@code i} and {@code j}. Self-loops are ignored (adj[i][i] == 0).
     */
    final double[][] adj;

    /** Total weight of all edges (each undirected edge counted once: W = sum_{i<j} adj[i][j]). */
    final double totalWeight;

    /** Per-node weighted degree (sum of all edge weights incident to node i). */
    final double[] degree;

    /**
     * Build the undirected view from a {@link ReasoningGraph}.
     *
     * @param graph the source graph; must be non-null
     */
    GraphUndirectedView(ReasoningGraph graph) {
        int n = graph.entityCount();
        this.nodes = new ArrayList<>(n);
        this.index = new LinkedHashMap<>(n * 2);
        int idx = 0;
        for (var entity : graph.entities()) {
            this.nodes.add(entity.id());
            this.index.put(entity.id(), idx++);
        }

        this.adj    = new double[n][n];
        this.degree = new double[n];

        for (GraphRelation rel : graph.relations()) {
            Integer srcIdx = this.index.get(rel.sourceId());
            Integer tgtIdx = this.index.get(rel.targetId());
            if (srcIdx == null || tgtIdx == null) {
                // Dangling endpoint — skip
                continue;
            }
            if (srcIdx.equals(tgtIdx)) {
                // Self-loop — not counted in the null model
                continue;
            }
            // Effective weight: use weight*confidence; substitute 1.0 when weight is zero
            double w = rel.weight();
            if (w <= 0.0) w = 1.0;
            double eff = w * rel.confidence();
            if (eff <= 0.0) eff = 1.0;

            this.adj[srcIdx][tgtIdx] += eff;
            this.adj[tgtIdx][srcIdx] += eff;
        }

        this.totalWeight = computeTotalWeight();
        computeDegrees();
    }

    /**
     * Build the undirected view directly from a precomputed adjacency matrix.
     * Used internally by {@link LouvainDetector} for the phase-2 super-node graph.
     *
     * @param nodeIds ordered list of node ids
     * @param adjIn   symmetric adjacency matrix [n][n]
     * @param n       number of nodes (must equal nodeIds.size())
     */
    GraphUndirectedView(List<String> nodeIds, double[][] adjIn, int n) {
        this.nodes = new ArrayList<>(nodeIds);
        this.index = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            this.index.put(nodeIds.get(i), i);
        }

        // Deep copy to keep this view independent
        this.adj = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(adjIn[i], 0, this.adj[i], 0, n);
        }

        this.degree = new double[n];
        this.totalWeight = computeTotalWeight();
        computeDegrees();
    }

    /** Compute totalWeight from the current adj matrix (must be called after adj is filled). */
    private double computeTotalWeight() {
        int n = this.adj.length;
        double degSum = 0.0;
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                degSum += this.adj[u][v];
            }
        }
        // Each undirected edge is counted twice in the degree sum
        return degSum / 2.0;
    }

    /** Compute per-node degrees from the current adj matrix. */
    private void computeDegrees() {
        int n = this.adj.length;
        for (int u = 0; u < n; u++) {
            double d = 0.0;
            for (int v = 0; v < n; v++) {
                d += this.adj[u][v];
            }
            this.degree[u] = d;
        }
    }

    /** Returns the list of neighbour indices (with non-zero weight) of node {@code u}. */
    List<Integer> neighbours(int u) {
        List<Integer> nbrs = new ArrayList<>();
        for (int v = 0; v < nodes.size(); v++) {
            if (adj[u][v] > 0.0) nbrs.add(v);
        }
        return Collections.unmodifiableList(nbrs);
    }
}
