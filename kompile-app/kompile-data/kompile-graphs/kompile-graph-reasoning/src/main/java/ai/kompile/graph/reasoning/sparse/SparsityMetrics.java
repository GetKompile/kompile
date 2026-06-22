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
package ai.kompile.graph.reasoning.sparse;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Structural sparsity metrics computed directly from a {@link ReasoningGraph}.
 *
 * <p>Excel/CSV sources produce bipartite or near-bipartite graphs with a very low edge density.
 * This class quantifies how sparse a graph is and whether it exhibits bipartite structure —
 * two properties that fundamentally change how absent edges should be interpreted epistemically:
 * absent edges in a sparse graph are evidence of extraction incompleteness or schema optionality,
 * not evidence against the proposition (see {@link SparseEvidenceHelper}).</p>
 *
 * <p>This class is pure Java — no Spring, no {@code @Value}, no persistence dependencies.
 * It operates solely through the {@link ReasoningGraph} interface and is safe to use in any
 * context (tests, CLI, embedded reasoning engine).</p>
 *
 * <h2>Tunables</h2>
 * <ul>
 *   <li>{@link #SPARSE_THRESHOLD} (default 0.10) — density below which a graph is "likely sparse".
 *       Candidate {@code KbConfig} key: {@code kbSparsityThreshold}, range [0.001, 1.0].</li>
 *   <li>{@link #BIPARTITE_VIOLATION_TOLERANCE} (default 0.05) — fraction of edges that may violate
 *       2-coloring and still be called bipartite.
 *       Candidate {@code KbConfig} key: {@code kbBipartiteViolationTolerance}, range [0.0, 0.5].</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SparsityMetrics m = SparsityMetrics.compute(graph);
 * if (m.isLikelySparse()) {
 *     // use SparseEvidenceHelper.opinionForAbsentInSparse() for missing edges
 * }
 * }</pre>
 */
public final class SparsityMetrics {

    /**
     * Default density threshold below which a graph is classified as "likely sparse".
     * Candidate {@code KbConfig} key: {@code kbSparsityThreshold}, range [0.001, 1.0].
     */
    public static final double SPARSE_THRESHOLD = 0.10;

    /**
     * Default fraction of edges allowed to violate the 2-coloring constraint before a graph
     * is no longer classified as "likely bipartite".
     * Candidate {@code KbConfig} key: {@code kbBipartiteViolationTolerance}, range [0.0, 0.5].
     */
    public static final double BIPARTITE_VIOLATION_TOLERANCE = 0.05;

    /** Number of entities (nodes) in the graph. */
    public final int nodeCount;

    /** Number of relations (edges) in the graph. */
    public final int edgeCount;

    /**
     * Graph density: actual edge count divided by the maximum possible edges.
     *
     * <p>For a directed graph: {@code E / (N * (N - 1))}.
     * Returns {@code 0.0} when N &lt; 2 (degenerate).</p>
     */
    public final double density;

    /** Arithmetic mean of per-node degree (outgoing + incoming edges). */
    public final double meanDegree;

    /**
     * Median per-node degree (the 50th-percentile value over the sorted degree sequence).
     * In bipartite graphs the median is often far below the mean (high-degree hub nodes skew mean up).
     */
    public final double medianDegree;

    private final boolean likelySparse;
    private final boolean likelyBipartite;

    private SparsityMetrics(int nodeCount, int edgeCount, double density,
                            double meanDegree, double medianDegree,
                            boolean likelySparse, boolean likelyBipartite) {
        this.nodeCount = nodeCount;
        this.edgeCount = edgeCount;
        this.density = density;
        this.meanDegree = meanDegree;
        this.medianDegree = medianDegree;
        this.likelySparse = likelySparse;
        this.likelyBipartite = likelyBipartite;
    }

    /**
     * Compute sparsity metrics for {@code graph} using the default thresholds
     * ({@link #SPARSE_THRESHOLD} and {@link #BIPARTITE_VIOLATION_TOLERANCE}).
     *
     * <p>This is an O(N + E) computation: one pass over entities for degree stats,
     * one BFS for bipartite detection.</p>
     *
     * @param graph the reasoning graph to analyze; must not be null
     * @return a fully-populated {@code SparsityMetrics} instance
     */
    public static SparsityMetrics compute(ReasoningGraph graph) {
        return compute(graph, SPARSE_THRESHOLD, BIPARTITE_VIOLATION_TOLERANCE);
    }

    /**
     * Compute sparsity metrics using caller-supplied thresholds.
     *
     * @param graph                    the reasoning graph to analyze; must not be null
     * @param sparseThreshold          density cutoff for {@link #isLikelySparse()}; e.g. 0.10
     * @param bipartiteViolTolerance   max fraction of violating edges for {@link #isLikelyBipartite()}; e.g. 0.05
     * @return a fully-populated {@code SparsityMetrics} instance
     */
    public static SparsityMetrics compute(ReasoningGraph graph,
                                          double sparseThreshold,
                                          double bipartiteViolTolerance) {
        if (graph == null) throw new IllegalArgumentException("graph must not be null");

        Collection<GraphEntity> entities = graph.entities();
        Collection<GraphRelation> relations = graph.relations();

        int n = entities.size();
        int e = relations.size();

        // ── Density ─────────────────────────────────────────────────────────────
        double density;
        if (n < 2) {
            density = 0.0;
        } else {
            // Use directed formula: max possible = N*(N-1)
            density = (double) e / ((double) n * (n - 1));
        }

        // ── Degree distribution ──────────────────────────────────────────────────
        // degree[id] = outgoing.size() + incoming.size()
        Map<String, Integer> degreeMap = new HashMap<>(n * 2);
        for (GraphEntity entity : entities) {
            degreeMap.put(entity.id(), 0);
        }
        for (GraphRelation r : relations) {
            degreeMap.merge(r.sourceId(), 1, Integer::sum);
            degreeMap.merge(r.targetId(), 1, Integer::sum);
        }

        List<Integer> degrees = new ArrayList<>(degreeMap.values());
        degrees.sort(Integer::compareTo);

        double meanDegree = 0.0;
        double medianDegree = 0.0;
        if (!degrees.isEmpty()) {
            long sum = 0;
            for (int d : degrees) sum += d;
            meanDegree = (double) sum / degrees.size();
            int mid = degrees.size() / 2;
            if (degrees.size() % 2 == 0) {
                medianDegree = (degrees.get(mid - 1) + degrees.get(mid)) / 2.0;
            } else {
                medianDegree = degrees.get(mid);
            }
        }

        // ── Bipartite detection via BFS 2-coloring ───────────────────────────────
        // Build an undirected adjacency list (for coloring, direction doesn't matter)
        Map<String, List<String>> adj = new HashMap<>(n * 2);
        for (GraphEntity entity : entities) {
            adj.put(entity.id(), new ArrayList<>());
        }
        for (GraphRelation r : relations) {
            adj.computeIfAbsent(r.sourceId(), k -> new ArrayList<>()).add(r.targetId());
            adj.computeIfAbsent(r.targetId(), k -> new ArrayList<>()).add(r.sourceId());
        }

        Map<String, Integer> color = new HashMap<>(n * 2);
        int violations = 0;

        for (String startId : adj.keySet()) {
            if (color.containsKey(startId)) continue;
            // BFS from startId
            Queue<String> queue = new LinkedList<>();
            queue.add(startId);
            color.put(startId, 0);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                int currentColor = color.get(current);
                List<String> neighbors = adj.getOrDefault(current, List.of());
                for (String neighbor : neighbors) {
                    if (!color.containsKey(neighbor)) {
                        color.put(neighbor, 1 - currentColor);
                        queue.add(neighbor);
                    } else if (color.get(neighbor) == currentColor) {
                        // violation: same color on both endpoints of an edge
                        violations++;
                    }
                }
            }
        }
        // Each undirected edge is visited twice (once per direction) → halve violations
        violations = violations / 2;

        boolean likelyBipartite;
        if (e == 0) {
            // No edges: trivially 2-colorable (or uninteresting)
            likelyBipartite = (n > 1);
        } else {
            likelyBipartite = ((double) violations / e) <= bipartiteViolTolerance;
        }

        boolean likelySparse = density < sparseThreshold;

        return new SparsityMetrics(n, e, density, meanDegree, medianDegree,
                likelySparse, likelyBipartite);
    }

    /**
     * Returns {@code true} when the graph density is below {@link #SPARSE_THRESHOLD} (or the
     * caller-supplied threshold). Sparse graphs should use vacuous opinions for absent edges
     * rather than disbelief — see {@link SparseEvidenceHelper}.
     */
    public boolean isLikelySparse() {
        return likelySparse;
    }

    /**
     * Returns {@code true} when the graph's undirected adjacency admits a near-2-coloring within
     * the configured violation tolerance. Near-bipartite structure is characteristic of tabular
     * (row/column/value) and schema-driven graphs.
     */
    public boolean isLikelyBipartite() {
        return likelyBipartite;
    }

    @Override
    public String toString() {
        return String.format(
            "SparsityMetrics{nodes=%d, edges=%d, density=%.4f, meanDeg=%.2f, " +
            "medianDeg=%.1f, sparse=%b, bipartite=%b}",
            nodeCount, edgeCount, density, meanDegree, medianDegree,
            likelySparse, likelyBipartite);
    }
}
