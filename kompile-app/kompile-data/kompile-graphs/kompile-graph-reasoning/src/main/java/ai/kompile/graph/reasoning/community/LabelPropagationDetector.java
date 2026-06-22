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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Label-Propagation community detector (Raghavan et al., 2007) adapted for weighted, undirected
 * graphs.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Assign each node a unique label (its index).</li>
 *   <li>Iterate up to {@code maxIterations} rounds in a seeded-random node order.</li>
 *   <li>In each round, each node adopts the label that maximises total incident edge weight
 *       among its neighbours (ties broken by choosing the numerically smallest label, for
 *       determinism).</li>
 *   <li>Stop when no labels changed in a full pass or {@code maxIterations} is reached.</li>
 *   <li>Re-label communities densely (0, 1, 2, …) sorted by minimum member index.</li>
 * </ol>
 *
 * <h3>Tunables (constructor parameters)</h3>
 * <ul>
 *   <li>{@code maxIterations} — maximum propagation rounds. Default: {@value #DEFAULT_MAX_ITERATIONS}.
 *       Range: [1, ∞). Larger values can improve quality on sparse graphs; beyond ~30 returns rarely
 *       change.</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * Same graph (same insertion order) + same seed → identical result.
 * The node-visit order within each round is shuffled with a {@link Random} initialised from the seed.
 *
 * <h3>Suggested KbConfig entry</h3>
 * {@code community.labelPropagation.maxIterations = 30} (range 1–200)
 */
public final class LabelPropagationDetector implements CommunityDetector {

    /** Default maximum number of propagation rounds. */
    public static final int DEFAULT_MAX_ITERATIONS = 30;

    private final int maxIterations;

    /**
     * Construct with default tunables ({@code maxIterations = 30}).
     */
    public LabelPropagationDetector() {
        this(DEFAULT_MAX_ITERATIONS);
    }

    /**
     * Construct with an explicit maximum iteration count.
     *
     * @param maxIterations maximum propagation rounds; must be {@code >= 1}
     * @throws IllegalArgumentException if {@code maxIterations < 1}
     */
    public LabelPropagationDetector(int maxIterations) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1, got " + maxIterations);
        }
        this.maxIterations = maxIterations;
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

        // Step 1: initialise each node with its own unique label
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) labels[i] = i;

        if (n == 1) {
            // Single node — trivially one community
            Map<String, Integer> result = new HashMap<>();
            result.put(view.nodes.get(0), 0);
            return new CommunityAssignment(result, 0.0);
        }

        // Build visit order array to shuffle in-place
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;

        Random rng = new Random(seed);

        // Step 2: iterative propagation
        for (int iter = 0; iter < maxIterations; iter++) {
            // Shuffle visit order
            shuffleArray(order, rng);
            boolean changed = false;

            for (int pos = 0; pos < n; pos++) {
                int u = order[pos];
                List<Integer> nbrs = view.neighbours(u);
                if (nbrs.isEmpty()) continue;

                // Tally weighted votes for each label among neighbours
                Map<Integer, Double> votes = new HashMap<>();
                for (int v : nbrs) {
                    int lbl = labels[v];
                    votes.merge(lbl, view.adj[u][v], Double::sum);
                }

                // Choose label with highest vote; break ties by smallest label id
                int bestLabel = labels[u];
                double bestVote = votes.getOrDefault(bestLabel, 0.0);
                for (Map.Entry<Integer, Double> entry : votes.entrySet()) {
                    double vote = entry.getValue();
                    int lbl = entry.getKey();
                    if (vote > bestVote || (vote == bestVote && lbl < bestLabel)) {
                        bestLabel = lbl;
                        bestVote = vote;
                    }
                }

                if (bestLabel != labels[u]) {
                    labels[u] = bestLabel;
                    changed = true;
                }
            }

            if (!changed) break;
        }

        // Step 3: re-label densely (0, 1, 2, …) in order of first occurrence
        int[] dense = densify(labels, n);

        // Step 4: compute modularity
        double modularity = ModularityCalculator.compute(view, dense);

        // Step 5: build result map
        Map<String, Integer> assignment = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            assignment.put(view.nodes.get(i), dense[i]);
        }

        return new CommunityAssignment(assignment, modularity);
    }

    /** Fisher-Yates shuffle using the provided RNG. */
    private static void shuffleArray(int[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }

    /**
     * Re-map raw labels to dense ids 0, 1, 2, … in order of first appearance.
     * This makes community ids independent of the internal propagation label space.
     */
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
