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
package ai.kompile.graph.reasoning.embedding.learn;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;

/**
 * Generates random walks over a {@link ReasoningGraph} using the node2vec biased-walk strategy.
 *
 * <h3>Walk convention (undirected treatment)</h3>
 * <p>The graph is treated as <em>undirected</em> for adjacency purposes: for any entity, both
 * {@link ReasoningGraph#outgoing(String) outgoing} and
 * {@link ReasoningGraph#incoming(String) incoming} relations contribute neighbors. This follows the
 * standard DeepWalk / node2vec approach for knowledge graphs where directionality is a relation
 * property but structural proximity is bidirectional.</p>
 *
 * <h3>node2vec transition probabilities</h3>
 * <p>At each step the walk is at node {@code v} and came from node {@code t}. The unnormalised
 * transition weight to neighbor {@code x} of {@code v} is:</p>
 * <pre>
 *   α(t, x) = 1/p   if x == t           (return to previous node)
 *             1      if d(t,x) = 1       (x is a common neighbor of t and v)
 *             1/q   if d(t,x) = 2       (x is farther from t)
 * </pre>
 * <p>where distance {@code d(t,x)} is 0 (x == t), 1 (x adjacent to t), or 2 (otherwise).
 * When {@code p = q = 1.0}, all weights are equal — this recovers DeepWalk's uniform walk.
 * To avoid constructing per-edge alias tables (expensive for large graphs), each step builds
 * a small weight array over the current node's neighbors and samples via {@link AliasTable};
 * this is O(degree) per step, which is perfectly acceptable for graphs up to tens of thousands
 * of nodes.</p>
 *
 * <p>Reference:
 * Grover, Leskovec (2016). node2vec: Scalable Feature Learning for Networks. KDD 2016.
 * https://arxiv.org/abs/1607.00653</p>
 */
public final class Node2VecWalk {

    private final ReasoningGraph graph;
    private final Map<String, List<String>> adjacency;   // undirected neighbor lists
    private final double p;
    private final double q;

    /**
     * @param graph     the graph to walk
     * @param adjacency precomputed undirected adjacency (entityId → neighbor ids); built once by
     *                  {@link Node2VecLearner} and shared across all walk instances for efficiency
     * @param p         return parameter
     * @param q         in-out parameter
     */
    Node2VecWalk(ReasoningGraph graph, Map<String, List<String>> adjacency, double p, double q) {
        this.graph     = graph;
        this.adjacency = adjacency;
        this.p         = p;
        this.q         = q;
    }

    /**
     * Generate one walk of length {@code walkLength} starting at {@code startId}.
     *
     * @param startId    id of the start node
     * @param walkLength total walk length (number of nodes, including the start)
     * @param rng        seeded RNG (caller threads the same RNG through all walks for reproducibility)
     * @return the walk as an ordered list of entity ids (length ≤ {@code walkLength} if the start
     *         node is isolated or the graph has fewer reachable nodes)
     */
    public List<String> walk(String startId, int walkLength, Random rng) {
        List<String> walk = new ArrayList<>(walkLength);
        walk.add(startId);

        if (walkLength <= 1) {
            return walk;
        }

        List<String> startNeighbors = adjacency.getOrDefault(startId, List.of());
        if (startNeighbors.isEmpty()) {
            return walk;
        }

        // First step: uniform (no previous node)
        String current  = startNeighbors.get(rng.nextInt(startNeighbors.size()));
        walk.add(current);
        String previous = startId;

        // Subsequent steps: node2vec biased (or uniform if p=q=1)
        for (int step = 2; step < walkLength; step++) {
            List<String> neighbors = adjacency.getOrDefault(current, List.of());
            if (neighbors.isEmpty()) {
                break;
            }

            String next = isUniform()
                    ? neighbors.get(rng.nextInt(neighbors.size()))
                    : biasedSample(previous, neighbors, rng);

            walk.add(next);
            previous = current;
            current  = next;
        }

        return walk;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    /** True when p = q = 1.0, i.e. the walk reduces to uniform DeepWalk. */
    private boolean isUniform() {
        return p == 1.0 && q == 1.0;
    }

    /**
     * Draw the next node from {@code neighbors} of {@code current} using node2vec α(t, x) weights.
     *
     * @param previous  the node visited before {@code current}
     * @param neighbors undirected neighbors of {@code current}
     * @param rng       seeded RNG
     * @return the chosen next node
     */
    private String biasedSample(String previous, List<String> neighbors, Random rng) {
        // Build the neighbor set of 'previous' for O(1) membership checks
        Set<String> prevNeighbors = new HashSet<>(adjacency.getOrDefault(previous, List.of()));

        double[] weights = new double[neighbors.size()];
        for (int i = 0; i < neighbors.size(); i++) {
            String x = neighbors.get(i);
            if (x.equals(previous)) {
                weights[i] = 1.0 / p;       // return to previous node
            } else if (prevNeighbors.contains(x)) {
                weights[i] = 1.0;           // common neighbor of previous and current
            } else {
                weights[i] = 1.0 / q;       // farther from previous
            }
        }

        int chosen = AliasTable.build(weights).sample(rng);
        return neighbors.get(chosen);
    }
}
