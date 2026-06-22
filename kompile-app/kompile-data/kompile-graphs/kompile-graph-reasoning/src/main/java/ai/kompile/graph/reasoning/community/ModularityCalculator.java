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

/**
 * Computes Newman-Girvan modularity Q for a given partition stored as a community-label array.
 *
 * <p>Q = (1/2W) * sum_{ij}[ A_{ij} - k_i*k_j/(2W) ] * delta(c_i, c_j)
 * where W = total edge weight, A_{ij} = adjacency weight, k_i = degree of node i,
 * c_i = community label of node i.</p>
 *
 * <p>Package-private utility; both implementations use it.</p>
 */
final class ModularityCalculator {

    private ModularityCalculator() {}

    /**
     * Compute modularity for a partition represented as {@code labels[nodeIndex] = communityId}.
     *
     * @param view   the precomputed undirected adjacency view
     * @param labels community id per node index; length must equal {@code view.nodes.size()}
     * @return Newman-Girvan modularity Q
     */
    static double compute(GraphUndirectedView view, int[] labels) {
        int n = view.nodes.size();
        double twoW = 2.0 * view.totalWeight;
        if (twoW == 0.0) {
            // Edgeless graph — modularity undefined; return 0.0 by convention
            return 0.0;
        }
        double q = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (labels[i] == labels[j]) {
                    double expected = (view.degree[i] * view.degree[j]) / twoW;
                    q += view.adj[i][j] - expected;
                }
            }
        }
        return q / twoW;
    }
}
