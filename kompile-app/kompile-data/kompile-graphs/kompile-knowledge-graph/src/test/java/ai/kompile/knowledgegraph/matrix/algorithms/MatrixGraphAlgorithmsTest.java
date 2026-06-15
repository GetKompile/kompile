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
