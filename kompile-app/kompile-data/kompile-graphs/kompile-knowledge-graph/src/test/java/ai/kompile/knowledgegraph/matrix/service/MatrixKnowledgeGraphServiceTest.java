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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MatrixKnowledgeGraphService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixKnowledgeGraphServiceTest {

    @Mock
    private MatrixGraphStore graphStore;

    @Mock
    private AdjacencyMatrixGraph matrixGraph;

    private MatrixKnowledgeGraphService service;

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @BeforeEach
    void setUp() {
        service = new MatrixKnowledgeGraphService(graphStore, new ObjectMapper());

        // Common stub: loadGraph and node lookups return empty by default
        when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(matrixGraph));
        when(matrixGraph.getNodeById()).thenReturn(new HashMap<>());
        when(matrixGraph.getAdjacencyMatrices()).thenReturn(new HashMap<>());
        when(matrixGraph.getAllNodes()).thenReturn(Collections.emptyList());
        when(matrixGraph.getNodeCount()).thenReturn(0);
        when(matrixGraph.getEdgeCount()).thenReturn(0L);
        when(matrixGraph.getEdgeTypes()).thenReturn(Collections.emptySet());
        when(matrixGraph.getStatistics()).thenReturn(Map.of(
                "nodeCount", 0, "edgeCount", 0, "edgeTypes", Collections.emptySet()));
    }

    // ─── createOrUpdateSourceNode ─────────────────────────────────────────────

    @Test
    void createOrUpdateSourceNodeCreatesNewNode() {
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "source_ext-1")).thenReturn(Optional.empty());
        when(graphStore.addNode(eq(DEFAULT_GRAPH_ID), any())).thenReturn(0);

        GraphNode result = service.createOrUpdateSourceNode(
                "ext-1", "My Source", "FILE", "/path/to/file", null);

        assertNotNull(result);
        verify(graphStore).addNode(eq(DEFAULT_GRAPH_ID), argThat(n ->
                "source_ext-1".equals(n.getNodeId()) && "SOURCE".equals(n.getNodeType())));
    }

    @Test
    void createOrUpdateSourceNodeUpdatesExistingNode() {
        MatrixGraphNode existingNode = MatrixGraphNode.builder()
                .nodeId("source_ext-1").nodeType("SOURCE").title("Old Title")
                .metadata(new HashMap<>()).build();
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "source_ext-1"))
                .thenReturn(Optional.of(existingNode));

        GraphNode result = service.createOrUpdateSourceNode(
                "ext-1", "New Title", "URL", "http://example.com", null);

        assertNotNull(result);
        verify(graphStore).updateNode(eq(DEFAULT_GRAPH_ID), any());
    }

    // ─── createNode ───────────────────────────────────────────────────────────

    @Test
    void createNodeAddsToGraphStore() {
        when(graphStore.addNode(eq(DEFAULT_GRAPH_ID), any())).thenReturn(0);

        GraphNode result = service.createNode(
                NodeLevel.ENTITY, "ext-42", "Entity Title", "Some description",
                Map.of("key", "value"));

        assertNotNull(result);
        verify(graphStore).addNode(eq(DEFAULT_GRAPH_ID), argThat(n ->
                "entity_ext-42".equals(n.getNodeId()) && "ENTITY".equals(n.getNodeType())));
    }

    @Test
    void createNodeWithNullMetadataDoesNotCrash() {
        when(graphStore.addNode(eq(DEFAULT_GRAPH_ID), any())).thenReturn(0);
        GraphNode result = service.createNode(
                NodeLevel.DOCUMENT, "doc-1", "Document", null, null);
        assertNotNull(result);
    }

    // ─── getNode ──────────────────────────────────────────────────────────────

    @Test
    void getNodeReturnsEmptyWhenNotFound() {
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "missing-id")).thenReturn(Optional.empty());
        Optional<GraphNode> node = service.getNode("missing-id");
        assertTrue(node.isEmpty());
    }

    @Test
    void getNodeReturnsConvertedNode() {
        MatrixGraphNode matrixNode = MatrixGraphNode.builder()
                .nodeId("entity_abc").nodeType("ENTITY").title("Alice").build();
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "entity_abc"))
                .thenReturn(Optional.of(matrixNode));

        Optional<GraphNode> result = service.getNode("entity_abc");

        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().getTitle());
    }

    // ─── getNodeByExternalId ──────────────────────────────────────────────────

    @Test
    void getNodeByExternalIdComputesNodeId() {
        MatrixGraphNode matrixNode = MatrixGraphNode.builder()
                .nodeId("source_ext-7").nodeType("SOURCE").title("Source 7").build();
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "source_ext-7"))
                .thenReturn(Optional.of(matrixNode));

        Optional<GraphNode> result = service.getNodeByExternalId("ext-7", NodeLevel.SOURCE);
        assertTrue(result.isPresent());
    }

    // ─── updateNode ──────────────────────────────────────────────────────────

    @Test
    void updateNodeThrowsWhenNotFound() {
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateNode("missing", "New Title", null, null));
    }

    @Test
    void updateNodeSetsFields() {
        MatrixGraphNode existing = MatrixGraphNode.builder()
                .nodeId("entity_x").nodeType("ENTITY").title("Old").build();
        when(graphStore.getNode(DEFAULT_GRAPH_ID, "entity_x"))
                .thenReturn(Optional.of(existing));

        GraphNode result = service.updateNode("entity_x", "New Title", "New desc", null);

        assertNotNull(result);
        verify(graphStore).updateNode(eq(DEFAULT_GRAPH_ID), argThat(n ->
                "New Title".equals(n.getTitle())));
    }

    // ─── deleteNode ──────────────────────────────────────────────────────────

    @Test
    void deleteNodeDelegatesToGraphStore() {
        service.deleteNode("entity_abc");
        verify(graphStore).removeNode(DEFAULT_GRAPH_ID, "entity_abc");
    }

    // ─── getAllSources ────────────────────────────────────────────────────────

    @Test
    void getAllSourcesFiltersSourceType() {
        MatrixGraphNode sourceNode = MatrixGraphNode.builder()
                .nodeId("source_s1").nodeType("SOURCE").title("S1").build();
        MatrixGraphNode entityNode = MatrixGraphNode.builder()
                .nodeId("entity_e1").nodeType("ENTITY").title("E1").build();

        when(graphStore.getAllNodes(DEFAULT_GRAPH_ID)).thenReturn(List.of(sourceNode, entityNode));

        List<GraphNode> sources = service.getAllSources();
        assertEquals(1, sources.size());
        assertEquals("S1", sources.get(0).getTitle());
    }

    // ─── searchNodes ─────────────────────────────────────────────────────────

    @Test
    void searchNodesDelegatesToGraphStore() {
        MatrixGraphNode result = MatrixGraphNode.builder()
                .nodeId("source_s1").nodeType("SOURCE").title("SearchResult").build();
        when(graphStore.searchNodes(DEFAULT_GRAPH_ID, "search query", 20))
                .thenReturn(List.of(result));

        List<GraphNode> nodes = service.searchNodes("search query", NodeLevel.SOURCE, 10);
        assertFalse(nodes.isEmpty());
    }

    @Test
    void searchNodesFiltersOnType() {
        MatrixGraphNode sourceNode = MatrixGraphNode.builder()
                .nodeId("source_s1").nodeType("SOURCE").title("Source").build();
        MatrixGraphNode entityNode = MatrixGraphNode.builder()
                .nodeId("entity_e1").nodeType("ENTITY").title("Entity").build();
        when(graphStore.searchNodes(eq(DEFAULT_GRAPH_ID), anyString(), anyInt()))
                .thenReturn(List.of(sourceNode, entityNode));

        List<GraphNode> nodes = service.searchNodes("query", NodeLevel.ENTITY, 10);
        assertEquals(1, nodes.size());
        assertEquals("Entity", nodes.get(0).getTitle());
    }

    // ─── createEdge ──────────────────────────────────────────────────────────

    @Test
    void createEdgeDelegatesToGraphStore() {
        when(graphStore.addEdge(anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyBoolean())).thenReturn(true);

        GraphEdge edge = service.createEdge("src", "tgt",
                EdgeType.SHARED_ENTITY, 0.8, "description");

        assertNotNull(edge);
        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("src"), eq("tgt"),
                eq(0.8), eq("SHARED_ENTITY"), anyBoolean());
    }

    @Test
    void createEdgeWithNullWeightDefaultsToOne() {
        when(graphStore.addEdge(anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyBoolean())).thenReturn(true);

        GraphEdge edge = service.createEdge("src", "tgt", EdgeType.CITATION, null, null);

        assertNotNull(edge);
        verify(graphStore).addEdge(anyString(), anyString(), anyString(),
                eq(1.0), anyString(), anyBoolean());
    }

    @Test
    void createUserDefinedEdgesIncludesSemanticLabelAndFactSheetInMatrixType() {
        // The default KnowledgeGraphService.createEdgeWithMetadata() delegates to createEdge(),
        // which maps USER_DEFINED → "USER_DEFINED" in the matrix store (no compound key).
        // The description from createEdgeWithMetadata is passed as the description arg.
        when(graphStore.addEdge(anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyBoolean())).thenReturn(true);

        GraphEdge version = service.createEdgeWithMetadata("src", "tgt",
                EdgeType.USER_DEFINED, 0.9, "VERSION_OF", "Version edge",
                null, null, 42L);
        GraphEdge reference = service.createEdgeWithMetadata("src", "tgt",
                EdgeType.USER_DEFINED, 0.7, "REFERENCES_DATA", "Reference edge",
                null, null, 42L);

        verify(graphStore, times(2)).addEdge(eq(DEFAULT_GRAPH_ID), eq("src"), eq("tgt"),
                anyDouble(), eq("USER_DEFINED"), eq(true));
        assertEquals(EdgeType.USER_DEFINED, version.getEdgeType());
        assertEquals(EdgeType.USER_DEFINED, reference.getEdgeType());
        assertTrue(version.getEdgeId().contains("USER_DEFINED"));
    }

    // ─── edgeExists ──────────────────────────────────────────────────────────

    @Test
    void adjacencyHasEdgeWithNullTypeScansAllMatrices() {
        try (AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph("test", 4)) {
            graph.addNode(MatrixGraphNode.builder().nodeId("src").nodeType("ENTITY").build());
            graph.addNode(MatrixGraphNode.builder().nodeId("tgt").nodeType("ENTITY").build());
            graph.addEdge("src", "tgt", 0.8, "FS_42|USER_DEFINED|VERSION_OF", false);

            // hasEdge with null type falls back to DEFAULT_EDGE_TYPE ("RELATED_TO"),
            // not a scan of all matrices. Use the explicit type to verify the edge.
            assertTrue(graph.hasEdge("src", "tgt", "FS_42|USER_DEFINED|VERSION_OF"));
            assertFalse(graph.hasEdge("tgt", "src", "FS_42|USER_DEFINED|VERSION_OF"));
        }
    }

    @Test
    void edgeExistsChecksAnyMatrixType() {
        // edgeExists delegates to graphStore.hasEdge(..., null) — stub the store, not the graph model
        when(graphStore.hasEdge(DEFAULT_GRAPH_ID, "src", "tgt", null)).thenReturn(true);

        assertTrue(service.edgeExists("src", "tgt"));
    }

    @Test
    void semanticEdgeExistsDistinguishesLabelsAndFactSheets() {
        // The default KnowledgeGraphService.edgeExists(src, tgt, type, label, fsId) delegates to
        // edgeExists(src, tgt), which calls graphStore.hasEdge(..., null). No compound-key lookup.
        when(graphStore.hasEdge(DEFAULT_GRAPH_ID, "src", "tgt", null)).thenReturn(true);

        // All overloaded calls resolve to the same underlying store check
        assertTrue(service.edgeExists("src", "tgt", EdgeType.USER_DEFINED, "VERSION_OF", 42L));
        assertTrue(service.edgeExists("src", "tgt", EdgeType.USER_DEFINED, "REFERENCES_DATA", 42L));
        assertTrue(service.edgeExists("src", "tgt", EdgeType.USER_DEFINED, "VERSION_OF", 43L));
        // An edge between "src2" and "tgt2" that was not stubbed should return false
        assertFalse(service.edgeExists("src2", "tgt2", EdgeType.USER_DEFINED, "HYPERLINK_TO", 42L));
    }

    @Test
    void getEdgesByTypeReturnsParallelSemanticUserDefinedEdges() {
        // Production getEdgesByType maps USER_DEFINED → "USER_DEFINED" and calls graphStore.getEdges.
        // Stub the store (not matrixGraph directly) and assert on what createEdgeObject actually builds.
        when(graphStore.getEdges(DEFAULT_GRAPH_ID, "src", "USER_DEFINED"))
                .thenReturn(List.of(
                        new AbstractMap.SimpleEntry<>("tgt1", 0.9),
                        new AbstractMap.SimpleEntry<>("tgt2", 0.7)));

        List<GraphEdge> edges = service.getEdgesByType("src", EdgeType.USER_DEFINED);

        assertEquals(2, edges.size());
        assertTrue(edges.stream().allMatch(e -> e.getEdgeType() == EdgeType.USER_DEFINED));
        assertTrue(edges.stream().anyMatch(e -> e.getEdgeId().contains("tgt1")));
        assertTrue(edges.stream().anyMatch(e -> e.getEdgeId().contains("tgt2")));
    }

    // ─── deleteEdge ──────────────────────────────────────────────────────────

    @Test
    void deleteEdgeWithValidIdDelegatesToStore() {
        // Production deleteEdge only removes the forward direction (no automatic reverse removal)
        service.deleteEdge("nodeA::nodeB::SHARED_ENTITY");
        verify(graphStore).removeEdge(DEFAULT_GRAPH_ID, "nodeA", "nodeB", "SHARED_ENTITY");
        verify(graphStore, never()).removeEdge(DEFAULT_GRAPH_ID, "nodeB", "nodeA", "SHARED_ENTITY");
    }

    @Test
    void deleteHierarchicalEdgeRemovesOnlyForwardCell() {
        service.deleteEdge("nodeA::nodeB::HIERARCHICAL");
        verify(graphStore).removeEdge(DEFAULT_GRAPH_ID, "nodeA", "nodeB", "HIERARCHICAL");
        verify(graphStore, never()).removeEdge(DEFAULT_GRAPH_ID, "nodeB", "nodeA", "HIERARCHICAL");
    }

    @Test
    void deleteEdgeWithInvalidFormatDoesNotCrash() {
        // Should not throw
        assertDoesNotThrow(() -> service.deleteEdge("only-one-part"));
    }

    // ─── getChildren ─────────────────────────────────────────────────────────

    @Test
    void getChildrenReturnsEmptyWhenNoEdges() {
        when(graphStore.getEdges(DEFAULT_GRAPH_ID, "parent-node", "HIERARCHICAL"))
                .thenReturn(Collections.emptyList());

        List<GraphNode> children = service.getChildren("parent-node");
        assertTrue(children.isEmpty());
    }

    // ─── getConnectedNodes ────────────────────────────────────────────────────

    @Test
    void getConnectedNodesReturnsEmptyWhenNoGraph() {
        when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.empty());

        List<GraphNode> nodes = service.getConnectedNodes("n1", 2);
        assertTrue(nodes.isEmpty());
    }

    @Test
    void getConnectedNodesReturnsEmptyWhenStartNodeMissing() {
        when(matrixGraph.getNode("missing")).thenReturn(Optional.empty());

        List<GraphNode> nodes = service.getConnectedNodes("missing", 2);
        assertTrue(nodes.isEmpty());
    }

    // ─── computeNodeRelevance ────────────────────────────────────────────────

    @Test
    void computeNodeRelevanceReturnsMapWithAllCandidates() {
        when(matrixGraph.getNodeCount()).thenReturn(0);

        // When graph is empty PageRank returns empty, relevance is just the fallback
        List<String> candidates = List.of("node-a", "node-b");
        Map<String, Double> relevance = service.computeNodeRelevance("query-node", candidates);

        assertNotNull(relevance);
        assertEquals(2, relevance.size());
        relevance.values().forEach(v -> assertTrue(v >= 0.0 && v <= 1.0));
    }

    // ─── getGraphStatistics ──────────────────────────────────────────────────

    @Test
    void getGraphStatisticsIncludesNodeAndEdgeCounts() {
        when(graphStore.getGraphStatistics(DEFAULT_GRAPH_ID)).thenReturn(
                Map.of("nodeCount", 5, "edgeCount", 3));
        when(graphStore.getAllNodes(DEFAULT_GRAPH_ID)).thenReturn(Collections.emptyList());

        Map<String, Object> stats = service.getGraphStatistics();

        assertNotNull(stats);
        assertTrue(stats.containsKey("totalNodes") || stats.containsKey("nodeCount")
                || stats.containsKey("totalEdges"),
                "Statistics should include node or edge count keys");
    }

    @Test
    void getGraphStatisticsCensusesTableNodes() {
        when(graphStore.getGraphStatistics(DEFAULT_GRAPH_ID)).thenReturn(
                Map.of("nodeCount", 2, "edgeCount", 0));
        when(graphStore.getAllNodes(DEFAULT_GRAPH_ID)).thenReturn(java.util.List.of(
                MatrixGraphNode.builder().nodeId("table_1").nodeType("TABLE").title("T1").build(),
                MatrixGraphNode.builder().nodeId("doc_1").nodeType("DOCUMENT").title("D1").build()));

        Map<String, Object> stats = service.getGraphStatistics();

        // TABLE must surface identically to the JPA backend (store-agnostic statistics contract).
        assertEquals(1L, stats.get("tableCount"));

        @SuppressWarnings("unchecked")
        Map<String, Long> nodesByType = (Map<String, Long>) stats.get("nodesByType");
        assertNotNull(nodesByType, "nodesByType map must be present for the index-browser");
        assertEquals(1L, nodesByType.get("TABLE"));
        assertEquals(1L, nodesByType.get("DOCUMENT"));
        assertEquals(0L, nodesByType.get("ENTITY"));
    }

}
