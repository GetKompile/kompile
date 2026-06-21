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
package ai.kompile.knowledgegraph.matrix.algorithms;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MatrixGraphAlgorithms} using real (small) ND4J matrices.
 *
 * <p>All tests use tiny graphs (3–5 nodes) so ND4J operations remain fast and
 * do not require GPU memory. After each test the graph is closed to free native
 * memory allocated by ND4J.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixGraphAlgorithmsTest {

    private AdjacencyMatrixGraph graph;

    @BeforeEach
    void setUp() {
        // Small initial capacity — forces growth if needed
        graph = new AdjacencyMatrixGraph("test-graph", 16);
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder()
                .nodeId(id)
                .nodeType("CONCEPT")
                .title(id)
                .build();
    }

    private void addLinearChain(String... nodeIds) {
        for (String id : nodeIds) {
            graph.addNode(node(id));
        }
        for (int i = 0; i < nodeIds.length - 1; i++) {
            graph.addEdge(nodeIds[i], nodeIds[i + 1], 1.0, "RELATED_TO", false);
        }
    }

    // ─── PageRank ────────────────────────────────────────────────────────────

    @Test
    void pageRankOnEmptyGraphReturnsEmptyMap() {
        Map<String, Double> pr = MatrixGraphAlgorithms.pageRank(graph);
        assertTrue(pr.isEmpty());
    }

    @Test
    void pageRankOnSingleNodeReturnsOne() {
        graph.addNode(node("n1"));
        Map<String, Double> pr = MatrixGraphAlgorithms.pageRank(graph);
        assertEquals(1, pr.size());
        assertEquals("n1", pr.keySet().iterator().next());
        // Dangling node: rank should be non-zero
        assertTrue(pr.get("n1") > 0);
    }

    @Test
    void pageRankSumsToApproximatelyOne() {
        addLinearChain("a", "b", "c");
        Map<String, Double> pr = MatrixGraphAlgorithms.pageRank(graph);
        double sum = pr.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 0.01, "PageRank values should sum to approximately 1");
    }

    @Test
    void pageRankHigherForWellConnectedNode() {
        // Star graph: "hub" points to a, b, c
        graph.addNode(node("hub"));
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        graph.addNode(node("c"));
        // All leaf nodes point to hub (hub receives incoming links)
        graph.addEdge("a", "hub", 1.0, "RELATED_TO", false);
        graph.addEdge("b", "hub", 1.0, "RELATED_TO", false);
        graph.addEdge("c", "hub", 1.0, "RELATED_TO", false);

        Map<String, Double> pr = MatrixGraphAlgorithms.pageRank(graph);
        double hubRank = pr.get("hub");
        double aRank = pr.get("a");
        assertTrue(hubRank > aRank, "Hub node should have higher PageRank than leaf nodes");
    }

    @Test
    void pageRankWithCustomDamping() {
        addLinearChain("a", "b", "c");
        Map<String, Double> pr1 = MatrixGraphAlgorithms.pageRank(graph, 0.85, 1e-6, 100);
        Map<String, Double> pr2 = MatrixGraphAlgorithms.pageRank(graph, 0.5, 1e-6, 100);

        // Both should return valid maps of same size
        assertEquals(pr1.size(), pr2.size());
        pr1.values().forEach(v -> assertTrue(v >= 0));
        pr2.values().forEach(v -> assertTrue(v >= 0));
    }

    // ─── Personalized PageRank ────────────────────────────────────────────────

    @Test
    void personalizedPageRankOnEmptyGraphReturnsEmptyMap() {
        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("x", 1.0));
        assertTrue(ppr.isEmpty());
    }

    @Test
    void personalizedPageRankWithNoValidSeedsReturnsEmptyMap() {
        addLinearChain("a", "b", "c");
        // Seeds reference nodes that do not exist in the graph → no positive seed mass.
        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("zzz", 1.0));
        assertTrue(ppr.isEmpty());
    }

    @Test
    void personalizedPageRankConcentratesMassNearSeed() {
        // Chain a→b→c→d, plus an isolated node e unreachable from the seed.
        addLinearChain("a", "b", "c", "d");
        graph.addNode(node("e"));

        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("a", 1.0));

        // Mass decreases with distance from the seed along the chain.
        assertTrue(ppr.get("a") > ppr.get("b"), "seed should outrank its 1-hop neighbor");
        assertTrue(ppr.get("b") > ppr.get("c"), "closer nodes should outrank farther ones");
        assertTrue(ppr.get("c") > ppr.get("d"), "closer nodes should outrank farther ones");
        // The isolated node, unreachable from the seed, gets essentially no mass — the key
        // difference from global PageRank, where a dangling node would receive a uniform share.
        assertTrue(ppr.get("e") < 1e-4, "node unreachable from the seed should get ~no PPR mass");
        assertTrue(ppr.get("e") < ppr.get("d"));
    }

    @Test
    void personalizedPageRankWithUniformSeedsApproximatesStandardPageRank() {
        addLinearChain("a", "b", "c");
        Map<String, Double> standard = MatrixGraphAlgorithms.pageRank(graph);
        // Equal weight on every node == the uniform restart distribution == standard PageRank.
        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(
                graph, Map.of("a", 1.0, "b", 1.0, "c", 1.0));

        assertEquals(standard.size(), ppr.size());
        for (String id : standard.keySet()) {
            assertEquals(standard.get(id), ppr.get(id), 1e-3,
                    "uniform-seed PPR should match standard PageRank for node " + id);
        }
    }

    @Test
    void personalizedPageRankSumsToApproximatelyOne() {
        addLinearChain("a", "b", "c");
        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("a", 1.0));
        double sum = ppr.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 0.01, "PPR values should sum to approximately 1");
    }

    @Test
    void sparsePersonalizedPageRankMatchesDense() {
        addLinearChain("a", "b", "c", "d");
        graph.addNode(node("e")); // isolated node

        Map<String, Double> dense = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("a", 1.0));
        Map<String, Double> sparse = MatrixGraphAlgorithms.personalizedPageRankSparse(
                graph, MatrixGraphAlgorithms.nodeIdsOf(graph), Map.of("a", 1.0), 0.85, 1e-6, 100);

        assertEquals(dense.keySet(), sparse.keySet());
        for (String id : dense.keySet()) {
            assertEquals(dense.get(id), sparse.get(id), 1e-4, "sparse PPR should match dense for node " + id);
        }
    }

    @Test
    void personalizedPageRankUsesSparsePathForLargeGraphsAndSumsToOne() {
        // > DENSE_PPR_NODE_CAP nodes → dispatches to the sparse implementation (no dense [n x n] matrix).
        int n = MatrixGraphAlgorithms.DENSE_PPR_NODE_CAP + 100;
        for (int i = 0; i < n; i++) {
            graph.addNode(node("n" + i));
        }
        for (int i = 0; i < n - 1; i++) {
            graph.addEdge("n" + i, "n" + (i + 1), 1.0, "RELATED_TO", false);
        }

        Map<String, Double> ppr = MatrixGraphAlgorithms.personalizedPageRank(graph, Map.of("n0", 1.0));

        assertEquals(n, ppr.size());
        double sum = ppr.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 0.01, "PPR values should sum to approximately 1");
        assertTrue(ppr.get("n0") > ppr.get("n100"), "the seed should outrank far-away nodes");
    }

    // ─── Path finding (PathRAG) ────────────────────────────────────────────────

    @Test
    void findPathsReturnsSinglePathAlongChain() {
        addLinearChain("a", "b", "c", "d");
        List<List<String>> paths = MatrixGraphAlgorithms.findPaths(graph, "a", "d", 5, 10);
        assertEquals(1, paths.size());
        assertEquals(List.of("a", "b", "c", "d"), paths.get(0));
    }

    @Test
    void findPathsRespectsHopLimit() {
        addLinearChain("a", "b", "c", "d");
        // a→d needs 3 hops; cap at 2 → no path.
        assertTrue(MatrixGraphAlgorithms.findPaths(graph, "a", "d", 2, 10).isEmpty());
    }

    @Test
    void findPathsFindsMultipleDistinctPaths() {
        // Diamond: a→b→d and a→c→d
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        graph.addNode(node("c"));
        graph.addNode(node("d"));
        graph.addEdge("a", "b", 1.0, "RELATED_TO", false);
        graph.addEdge("a", "c", 1.0, "RELATED_TO", false);
        graph.addEdge("b", "d", 1.0, "RELATED_TO", false);
        graph.addEdge("c", "d", 1.0, "RELATED_TO", false);

        List<List<String>> paths = MatrixGraphAlgorithms.findPaths(graph, "a", "d", 4, 10);
        assertEquals(2, paths.size());
        Set<String> rendered = paths.stream()
                .map(p -> String.join(">", p))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(rendered.contains("a>b>d"));
        assertTrue(rendered.contains("a>c>d"));
    }

    @Test
    void findPathsSameSourceTargetReturnsEmpty() {
        addLinearChain("a", "b");
        assertTrue(MatrixGraphAlgorithms.findPaths(graph, "a", "a", 5, 10).isEmpty());
    }

    // ─── Connected Components ─────────────────────────────────────────────────

    @Test
    void findConnectedComponentsOnEmptyGraphReturnsEmpty() {
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);
        assertTrue(components.isEmpty());
    }

    @Test
    void findConnectedComponentsSingleNode() {
        graph.addNode(node("n1"));
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);
        assertEquals(1, components.size());
        assertTrue(components.get(0).contains("n1"));
    }

    @Test
    void findConnectedComponentsTwoDisconnectedNodes() {
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        // No edge between them
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);
        assertEquals(2, components.size());
    }

    @Test
    void findConnectedComponentsLinearChainIsOneComponent() {
        addLinearChain("a", "b", "c");
        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);
        // Even though edges are directed a→b→c, the BFS symmetrizes (uses A + A^T)
        assertEquals(1, components.size());
        Set<String> comp = components.get(0);
        assertTrue(comp.containsAll(List.of("a", "b", "c")));
    }

    @Test
    void findConnectedComponentsTwoSeparateClusters() {
        // Cluster 1: a—b
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        graph.addEdge("a", "b", 1.0, "RELATED_TO", true);

        // Cluster 2: c—d
        graph.addNode(node("c"));
        graph.addNode(node("d"));
        graph.addEdge("c", "d", 1.0, "RELATED_TO", true);

        List<Set<String>> components = MatrixGraphAlgorithms.findConnectedComponents(graph);
        assertEquals(2, components.size());
    }

    // ─── Shortest Path ────────────────────────────────────────────────────────

    @Test
    void shortestPathOnEmptyGraphReturnsEmpty() {
        Map<String, Integer> distances = MatrixGraphAlgorithms.shortestPathDistances(graph, "missing");
        assertTrue(distances.isEmpty());
    }

    @Test
    void shortestPathFromNodeToItselfIsZero() {
        graph.addNode(node("a"));
        Map<String, Integer> distances = MatrixGraphAlgorithms.shortestPathDistances(graph, "a");
        assertEquals(0, distances.get("a"));
    }

    @Test
    void shortestPathLinearChain() {
        addLinearChain("a", "b", "c", "d");
        Map<String, Integer> distances = MatrixGraphAlgorithms.shortestPathDistances(graph, "a");

        assertEquals(0, distances.get("a"));
        assertEquals(1, distances.get("b"));
        assertEquals(2, distances.get("c"));
        assertEquals(3, distances.get("d"));
    }

    @Test
    void shortestPathUnreachableNodeIsNegativeOne() {
        graph.addNode(node("a"));
        graph.addNode(node("b")); // not connected to a
        Map<String, Integer> distances = MatrixGraphAlgorithms.shortestPathDistances(graph, "a");
        assertEquals(-1, distances.get("b"), "Unreachable node should have distance -1");
    }

    // ─── Degree Centrality ────────────────────────────────────────────────────

    @Test
    void degreeCentralityOnEmptyGraphReturnsEmpty() {
        Map<String, Double> centrality = MatrixGraphAlgorithms.degreeCentrality(graph);
        assertTrue(centrality.isEmpty());
    }

    @Test
    void degreeCentralityNormalizedBetweenZeroAndOne() {
        addLinearChain("a", "b", "c");
        Map<String, Double> centrality = MatrixGraphAlgorithms.degreeCentrality(graph);

        centrality.values().forEach(v ->
                assertTrue(v >= 0.0 && v <= 1.0,
                        "Degree centrality should be normalized to [0, 1]"));
    }

    @Test
    void degreeCentralityHigherForHubNode() {
        // Hub: a ← b, a ← c, a ← d
        graph.addNode(node("hub"));
        graph.addNode(node("b"));
        graph.addNode(node("c"));
        graph.addNode(node("d"));
        graph.addEdge("b", "hub", 1.0, "RELATED_TO", false);
        graph.addEdge("c", "hub", 1.0, "RELATED_TO", false);
        graph.addEdge("d", "hub", 1.0, "RELATED_TO", false);

        Map<String, Double> centrality = MatrixGraphAlgorithms.degreeCentrality(graph);
        double hubCentrality = centrality.get("hub");
        double bCentrality = centrality.get("b");
        assertTrue(hubCentrality >= bCentrality,
                "Hub node should have >= degree centrality than leaf nodes");
    }

    // ─── Spectral Clustering (community detection) ────────────────────────────

    @Test
    void spectralClusteringOnEmptyGraphReturnsEmpty() {
        Map<String, Integer> communities = MatrixGraphAlgorithms.spectralClustering(graph, 2);
        assertTrue(communities.isEmpty());
    }

    @Test
    void spectralClusteringAssignsCommunityToEachNode() {
        addLinearChain("a", "b", "c");
        Map<String, Integer> communities = MatrixGraphAlgorithms.spectralClustering(graph, 2);

        assertEquals(3, communities.size());
        assertTrue(communities.containsKey("a"));
        assertTrue(communities.containsKey("b"));
        assertTrue(communities.containsKey("c"));
    }

    @Test
    void spectralClusteringWithMoreCommunitiesThanNodesAssignsUniqueCommunities() {
        graph.addNode(node("a"));
        graph.addNode(node("b"));
        // k >= n: each node gets its own community
        Map<String, Integer> communities = MatrixGraphAlgorithms.spectralClustering(graph, 10);
        assertEquals(2, communities.size());
        assertNotEquals(communities.get("a"), communities.get("b"),
                "Each node should be in its own community when k >= n");
    }

    @Test
    void spectralClusteringZeroCommunitiesReturnsEmpty() {
        graph.addNode(node("a"));
        Map<String, Integer> communities = MatrixGraphAlgorithms.spectralClustering(graph, 0);
        assertTrue(communities.isEmpty());
    }

    // ─── Random Walk ─────────────────────────────────────────────────────────

    @Test
    void randomWalkOnMissingNodeReturnsEmpty() {
        List<String> walk = MatrixGraphAlgorithms.randomWalk(graph, "nonexistent", 5);
        assertTrue(walk.isEmpty());
    }

    @Test
    void randomWalkStartsAtGivenNode() {
        addLinearChain("a", "b", "c");
        List<String> walk = MatrixGraphAlgorithms.randomWalk(graph, "a", 2);
        assertFalse(walk.isEmpty());
        assertEquals("a", walk.get(0));
    }

    @Test
    void randomWalkStopsAtDanglingNode() {
        graph.addNode(node("isolated"));
        // No outgoing edges from "isolated"
        List<String> walk = MatrixGraphAlgorithms.randomWalk(graph, "isolated", 10);
        // Walk should stop after the first step (no neighbors)
        assertEquals(1, walk.size());
        assertEquals("isolated", walk.get(0));
    }

    @Test
    void randomWalkLengthIsBoundedBySteps() {
        addLinearChain("a", "b", "c", "d", "e");
        List<String> walk = MatrixGraphAlgorithms.randomWalk(graph, "a", 3);
        assertTrue(walk.size() <= 4, "Walk should not exceed steps+1 in length");
    }

    // ─── Betweenness Centrality ───────────────────────────────────────────────

    @Test
    void betweennessCentralityOnEmptyGraphReturnsEmpty() {
        Map<String, Double> bc = MatrixGraphAlgorithms.betweennessCentrality(graph, 5);
        assertTrue(bc.isEmpty());
    }

    @Test
    void betweennessCentralityValuesAreNonNegative() {
        addLinearChain("a", "b", "c");
        Map<String, Double> bc = MatrixGraphAlgorithms.betweennessCentrality(graph, 3);
        bc.values().forEach(v -> assertTrue(v >= 0.0, "Betweenness centrality must be non-negative"));
    }

    @Test
    void betweennessCentralityMiddleNodeHigherInChain() {
        // For a—b—c chain, "b" is on the shortest path between a and c
        addLinearChain("a", "b", "c");
        graph.addEdge("c", "a", 1.0, "RELATED_TO", false); // make it directed: a→b→c→(no return from c)
        Map<String, Double> bc = MatrixGraphAlgorithms.betweennessCentrality(graph, 10);

        // All nodes should have non-negative betweenness
        assertNotNull(bc.get("a"));
        assertNotNull(bc.get("b"));
        assertNotNull(bc.get("c"));
    }
}
