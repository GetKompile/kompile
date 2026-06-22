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

import ai.kompile.graph.reasoning.model.ReasoningGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Louvain modularity-maximising community detector (Blondel et al., 2008).
 *
 * <p>Implements <em>Phase 1</em> of the Louvain algorithm: greedy single-pass local modularity
 * optimisation. Phase 2 (super-node aggregation and recursion) is applied when
 * {@code multilevel = true} (default), which iterates Phase 1 on successively coarser graphs
 * until no improvement is found — recovering the full multi-level Louvain.</p>
 *
 * <h3>Algorithm sketch (single level)</h3>
 * <ol>
 *   <li>Place every node in its own singleton community.</li>
 *   <li>Visit nodes in seeded-random order; for each node try moving it to every neighbour's
 *       community and accept the move that yields the largest positive ΔQ (ties broken by
 *       lowest community id for determinism).</li>
 *   <li>Repeat until a full pass yields no improvement or {@code maxIterations} is reached.</li>
 * </ol>
 *
 * <p>ΔQ is computed using the incremental Louvain formula with an optional resolution parameter
 * γ that scales the null-model term:
 * {@code ΔQ ∝ k_{i,in} - γ * k_i * Σ_tot / (2W)}</p>
 *
 * <h3>Tunables (constructor parameters)</h3>
 * <ul>
 *   <li>{@code resolution} — resolution parameter γ. Larger → more smaller communities.
 *       Default: {@value #DEFAULT_RESOLUTION}. Range: (0.0, ∞), typical [0.5, 2.0].</li>
 *   <li>{@code maxIterations} — maximum passes per level. Default: {@value #DEFAULT_MAX_ITERATIONS}.
 *       Range: [1, ∞). Beyond 50 rarely improves results.</li>
 *   <li>{@code multilevel} — if {@code true} (default), aggregate communities into super-nodes
 *       and repeat, yielding the full Louvain hierarchy. {@code false} → single-level greedy
 *       moves only (faster on small graphs).</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * Same graph (same insertion order) + same seed → identical result. The node-visit order within
 * each pass is shuffled with a {@link Random} seeded from the caller-supplied seed; each
 * subsequent level uses a derived seed for independent ordering.
 *
 * <h3>Suggested KbConfig entries</h3>
 * <ul>
 *   <li>{@code community.louvain.resolution = 1.0} (range 0.1–5.0)</li>
 *   <li>{@code community.louvain.maxIterations = 50} (range 1–200)</li>
 *   <li>{@code community.louvain.multilevel = true}</li>
 * </ul>
 */
public final class LouvainDetector implements CommunityDetector {

    /** Default resolution parameter (standard Louvain, γ = 1.0). */
    public static final double DEFAULT_RESOLUTION = 1.0;

    /** Default maximum number of passes per level. */
    public static final int DEFAULT_MAX_ITERATIONS = 50;

    private final double resolution;
    private final int maxIterations;
    private final boolean multilevel;

    /**
     * Construct with default tunables (resolution=1.0, maxIterations=50, multilevel=true).
     */
    public LouvainDetector() {
        this(DEFAULT_RESOLUTION, DEFAULT_MAX_ITERATIONS, true);
    }

    /**
     * Construct with explicit tunables.
     *
     * @param resolution    resolution parameter γ; must be {@code > 0}
     * @param maxIterations maximum passes per level; must be {@code >= 1}
     * @param multilevel    whether to apply the multi-level phase-2 aggregation
     * @throws IllegalArgumentException on invalid parameter values
     */
    public LouvainDetector(double resolution, int maxIterations, boolean multilevel) {
        if (resolution <= 0.0) {
            throw new IllegalArgumentException("resolution must be > 0, got " + resolution);
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
        }
        this.resolution    = resolution;
        this.maxIterations = maxIterations;
        this.multilevel    = multilevel;
    }

    @Override
    public long defaultSeed() {
        return 42L;
    }

    @Override
    public CommunityAssignment detect(ReasoningGraph graph, long seed) {
        if (graph == null) throw new IllegalArgumentException("graph must not be null");
        if (graph.isEmpty()) {
            return new CommunityAssignment(Map.of(), 0.0);
        }

        GraphUndirectedView view = new GraphUndirectedView(graph);
        int n = view.nodes.size();

        if (n == 1) {
            Map<String, Integer> result = new HashMap<>();
            result.put(view.nodes.get(0), 0);
            return new CommunityAssignment(result, 0.0);
        }

        int[] finalLabels = runLouvain(view, seed);

        double modularity = ModularityCalculator.compute(view, finalLabels);

        Map<String, Integer> assignment = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            assignment.put(view.nodes.get(i), finalLabels[i]);
        }

        return new CommunityAssignment(assignment, modularity);
    }

    /**
     * Orchestrate the Louvain levels. Returns dense community labels for the original nodes.
     */
    private int[] runLouvain(GraphUndirectedView view, long seed) {
        int n = view.nodes.size();
        int[] labels = runPhase1(view, seed);

        if (!multilevel) {
            return densify(labels, n);
        }

        // Count distinct communities
        Set<Integer> distinct = distinctSet(labels);
        if (distinct.size() <= 1 || distinct.size() >= n) {
            // No further reduction possible
            return densify(labels, n);
        }

        // Build super-node graph and recurse
        SuperNodeResult superResult = buildSuperNodeGraph(view, labels, distinct);
        GraphUndirectedView superView = superResult.view;
        int numSuper = superView.nodes.size();

        if (numSuper >= n) {
            return densify(labels, n);
        }

        // Run phase 1 on the super-node graph with a derived seed
        long derivedSeed = seed ^ 0x9e3779b97f4a7c15L;
        int[] superLabels = runPhase1(superView, derivedSeed);

        // Check if super-graph further split
        Set<Integer> superDistinct = distinctSet(superLabels);
        if (superDistinct.size() <= 1) {
            // Super-graph stayed whole — use phase-1 result from original
            return densify(labels, n);
        }

        // Compose: original label → super-node index → super-node community
        int[] composed = new int[n];
        for (int i = 0; i < n; i++) {
            int superNodeIdx = superResult.labelToSuperNode[labels[i]];
            composed[i] = superLabels[superNodeIdx];
        }
        return densify(composed, n);
    }

    /**
     * Phase 1: greedy local modularity moves.
     * Returns a raw (non-dense) label array of length {@code view.nodes.size()}.
     */
    private int[] runPhase1(GraphUndirectedView view, long seed) {
        int n = view.nodes.size();
        double twoW = 2.0 * view.totalWeight;
        if (twoW == 0.0) {
            // Edgeless: each node is its own community
            int[] labels = new int[n];
            for (int i = 0; i < n; i++) labels[i] = i;
            return labels;
        }
        // Community tracking arrays indexed by community id (initially = node index)
        double[] sigmaTot = new double[n]; // sum of node degrees in community c
        double[] sigmaIn  = new double[n]; // 2 * sum of internal edge weights in community c

        int[] labels = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i]  = i;
            sigmaTot[i] = view.degree[i];
            sigmaIn[i]  = 0.0;
        }

        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Random rng = new Random(seed);

        for (int iter = 0; iter < maxIterations; iter++) {
            shuffleArray(order, rng);
            boolean anyImproved = false;

            for (int pos = 0; pos < n; pos++) {
                int u     = order[pos];
                int cu    = labels[u];
                double ku = view.degree[u];

                // Collect neighbour community → sum of weights from u to that community
                Map<Integer, Double> neighCommWeight = new HashMap<>();
                for (int v = 0; v < n; v++) {
                    if (view.adj[u][v] > 0.0) {
                        int cv = labels[v];
                        neighCommWeight.merge(cv, view.adj[u][v], Double::sum);
                    }
                }

                // Temporarily remove u from community cu
                double kuInCu = neighCommWeight.getOrDefault(cu, 0.0);
                sigmaTot[cu] -= ku;
                sigmaIn[cu]  -= 2.0 * kuInCu;

                // Evaluate each candidate community (current + all neighbour communities)
                Set<Integer> candidates = new HashSet<>(neighCommWeight.keySet());
                candidates.add(cu);

                int    bestComm   = cu;
                double bestDeltaQ = 0.0; // must strictly improve to move

                for (int cx : candidates) {
                    double kuInCx = neighCommWeight.getOrDefault(cx, 0.0);
                    // ΔQ (unnormalized by 2W, relative): 2*kuInCx - resolution*ku*sigmaTot[cx]/W
                    // Dividing by 2W gives the true modularity gain; we compare relatively.
                    double dq = kuInCx - resolution * ku * sigmaTot[cx] / (2.0 * twoW);
                    if (dq > bestDeltaQ || (dq == bestDeltaQ && cx < bestComm)) {
                        bestDeltaQ = dq;
                        bestComm   = cx;
                    }
                }

                // Place u in bestComm
                double kuInBest = neighCommWeight.getOrDefault(bestComm, 0.0);
                sigmaTot[bestComm] += ku;
                sigmaIn[bestComm]  += 2.0 * kuInBest;
                labels[u] = bestComm;

                if (bestComm != cu) {
                    anyImproved = true;
                }
            }

            if (!anyImproved) break;
        }

        return labels;
    }

    /**
     * Build a super-node graph from the current partition.
     * Each community becomes a super-node; edges between super-nodes aggregate the underlying weights.
     */
    private SuperNodeResult buildSuperNodeGraph(GraphUndirectedView view,
                                                int[] labels,
                                                Set<Integer> distinct) {
        // Dense remap of community ids → super-node indices
        List<Integer> communityList = new ArrayList<>(distinct);
        communityList.sort(Integer::compareTo);
        int numSuperNodes = communityList.size();

        Map<Integer, Integer> commToSuper = new HashMap<>(numSuperNodes * 2);
        for (int i = 0; i < numSuperNodes; i++) {
            commToSuper.put(communityList.get(i), i);
        }

        // Map from original label (raw community id ∈ [0,n)) → super-node index
        // labels[i] can be any value in [0,n); we only need entries for label values that exist
        int n = view.nodes.size();
        // max raw label value
        int maxLabel = 0;
        for (int l : labels) if (l > maxLabel) maxLabel = l;
        int[] labelToSuperNode = new int[maxLabel + 1];
        for (Map.Entry<Integer, Integer> e : commToSuper.entrySet()) {
            labelToSuperNode[e.getKey()] = e.getValue();
        }

        // Build super-node adjacency matrix
        double[][] superAdj = new double[numSuperNodes][numSuperNodes];
        for (int u = 0; u < n; u++) {
            int su = commToSuper.getOrDefault(labels[u], 0);
            for (int v = 0; v < n; v++) {
                if (view.adj[u][v] > 0.0) {
                    int sv = commToSuper.getOrDefault(labels[v], 0);
                    superAdj[su][sv] += view.adj[u][v];
                }
            }
        }

        List<String> superNodeIds = new ArrayList<>(numSuperNodes);
        for (int i = 0; i < numSuperNodes; i++) superNodeIds.add("_super_" + i);

        GraphUndirectedView superView = new GraphUndirectedView(superNodeIds, superAdj, numSuperNodes);
        return new SuperNodeResult(superView, labelToSuperNode);
    }

    /** Holder for phase-2 super-node graph data. */
    private static final class SuperNodeResult {
        final GraphUndirectedView view;
        /**
         * labelToSuperNode[rawLabel] = super-node index.
         * Length = maxRawLabel + 1 (sized to cover all raw label values in [0, n)).
         */
        final int[] labelToSuperNode;

        SuperNodeResult(GraphUndirectedView view, int[] labelToSuperNode) {
            this.view             = view;
            this.labelToSuperNode = labelToSuperNode;
        }
    }

    /** Fisher-Yates in-place shuffle. */
    private static void shuffleArray(int[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
    }

    /** Collect distinct label values from the array. */
    private static Set<Integer> distinctSet(int[] labels) {
        Set<Integer> s = new HashSet<>();
        for (int l : labels) s.add(l);
        return s;
    }

    /** Re-map raw labels to dense ids 0, 1, 2, … in order of first appearance. */
    private static int[] densify(int[] labels, int n) {
        Map<Integer, Integer> remap = new HashMap<>();
        int[] dense = new int[n];
        int next = 0;
        for (int i = 0; i < n; i++) {
            Integer mapped = remap.get(labels[i]);
            if (mapped == null) {
                mapped = next++;
                remap.put(labels[i], mapped);
            }
            dense[i] = mapped;
        }
        return dense;
    }
}
