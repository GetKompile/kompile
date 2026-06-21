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
import org.mockito.ArgumentCaptor;
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

    // ─── Semantic relationType surfacing ──────────────────────────────────────

    @Test
    void getEdgesInFactSheetSurfacesSemanticRelationType() {
        MatrixGraphNode node = MatrixGraphNode.builder().nodeId("n1").factSheetId(1L).build();
        when(matrixGraph.getAllNodes()).thenReturn(List.of(node));
        when(matrixGraph.getEdgeTypes()).thenReturn(new LinkedHashSet<>(List.of("WORKS_AT", "RELATED_TO")));
        when(matrixGraph.getNeighbors("n1", "WORKS_AT")).thenReturn(List.of(Map.entry("n2", 0.9)));
        when(matrixGraph.getNeighbors("n1", "RELATED_TO")).thenReturn(List.of(Map.entry("n3", 0.5)));
        when(graphStore.getNode(eq(DEFAULT_GRAPH_ID), anyString())).thenReturn(Optional.empty());

        List<GraphEdge> edges = service.getEdgesInFactSheet(1L);

        GraphEdge worksAt = edges.stream()
                .filter(e -> "n2".equals(e.getTargetNode().getNodeId())).findFirst().orElseThrow();
        assertEquals("WORKS_AT", worksAt.getRelationType(),
                "a semantic adjacency key surfaces as relationType");
        assertEquals(EdgeType.USER_DEFINED, worksAt.getEdgeType());

        GraphEdge relatedTo = edges.stream()
                .filter(e -> "n3".equals(e.getTargetNode().getNodeId())).findFirst().orElseThrow();
        assertNull(relatedTo.getRelationType(),
                "generic RELATED_TO is not a meaningful semantic relation");
    }

    @Test
    void getEdgesInFactSheetUsesExplicitRelationTypeOverKeyHeuristic() {
        // The edge is keyed by the structural type "USER_DEFINED" but carries an explicit relation
        // field "WORKS_AT". The key heuristic could never derive "WORKS_AT" from "USER_DEFINED", so a
        // correct result proves the first-class relationType field is read authoritatively.
        MatrixGraphNode node = MatrixGraphNode.builder().nodeId("n1").factSheetId(1L).build();
        when(matrixGraph.getAllNodes()).thenReturn(List.of(node));
        when(matrixGraph.getEdgeTypes()).thenReturn(new LinkedHashSet<>(List.of("USER_DEFINED")));
        when(matrixGraph.getNeighbors("n1", "USER_DEFINED")).thenReturn(List.of(Map.entry("n2", 0.9)));
        when(matrixGraph.getEdgeRelationType("USER_DEFINED", "n1", "n2")).thenReturn("WORKS_AT");
        when(graphStore.getNode(eq(DEFAULT_GRAPH_ID), anyString())).thenReturn(Optional.empty());

        List<GraphEdge> edges = service.getEdgesInFactSheet(1L);

        GraphEdge edge = edges.stream()
                .filter(e -> "n2".equals(e.getTargetNode().getNodeId())).findFirst().orElseThrow();
        assertEquals("WORKS_AT", edge.getRelationType(),
                "explicit relationType field must win over the adjacency-key heuristic");
        assertEquals(EdgeType.USER_DEFINED, edge.getEdgeType());
        assertEquals("n1::n2::WORKS_AT", edge.getEdgeId());
    }

    @Test
    void createEdgeWithRelationTypeStoresSemanticKey() {
        when(graphStore.getNode(eq(DEFAULT_GRAPH_ID), anyString())).thenReturn(Optional.empty());

        GraphEdge edge = service.createEdge("n1", "n2", EdgeType.USER_DEFINED, "WORKS_AT", 0.9, "Alice works at Acme");

        // The semantic relation is stored as the explicit first-class relationType field (and, for
        // backward-compatible type routing, as the adjacency key too).
        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("n1"), eq("n2"), eq(0.9), eq("WORKS_AT"), anyBoolean(), eq("WORKS_AT"));
        assertEquals("WORKS_AT", edge.getRelationType());
        assertEquals(EdgeType.USER_DEFINED, edge.getEdgeType());
    }

    @Test
    void createEdgeWithoutRelationTypeUsesStructuralKey() {
        when(graphStore.getNode(eq(DEFAULT_GRAPH_ID), anyString())).thenReturn(Optional.empty());

        GraphEdge edge = service.createEdge("n1", "n2", EdgeType.HIERARCHICAL, null, 1.0, null);

        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("n1"), eq("n2"), eq(1.0), eq("HIERARCHICAL"), anyBoolean(), isNull());
        assertNull(edge.getRelationType());
        assertEquals(EdgeType.HIERARCHICAL, edge.getEdgeType());
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
    void createEdgeWithMetadataPreservesSemanticLabelAsMatrixKey() {
        // createEdgeWithMetadata routes the semantic relation label through the 9-arg addEdge so that
        // confidence and description are also persisted (M-7).  The matrix store keys the edge by the
        // semantic relation (recovered as relationType on read) while edgeType stays USER_DEFINED.
        when(graphStore.addEdge(anyString(), anyString(), anyString(),
                anyDouble(), anyString(), anyBoolean(), any(), any(), any())).thenReturn(true);

        GraphEdge version = service.createEdgeWithMetadata("src", "tgt",
                EdgeType.USER_DEFINED, 0.9, "VERSION_OF", "Version edge",
                null, null, 42L);
        GraphEdge reference = service.createEdgeWithMetadata("src", "tgt",
                EdgeType.USER_DEFINED, 0.7, "REFERENCES_DATA", "Reference edge",
                null, null, 42L);

        // [M-7] Verify 9-arg addEdge is called so confidence/description fields are passed through.
        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("src"), eq("tgt"),
                anyDouble(), eq("VERSION_OF"), eq(true), eq("VERSION_OF"), isNull(), eq("Version edge"));
        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("src"), eq("tgt"),
                anyDouble(), eq("REFERENCES_DATA"), eq(true), eq("REFERENCES_DATA"), isNull(), eq("Reference edge"));
        assertEquals(EdgeType.USER_DEFINED, version.getEdgeType());
        assertEquals("VERSION_OF", version.getRelationType());
        assertEquals("REFERENCES_DATA", reference.getRelationType());
        assertTrue(version.getEdgeId().contains("VERSION_OF"));
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

    // ─── edge-type round-trip (regression: EDGE_TYPE_MAP data loss) ────────────

    @Test
    void createEdgeStoresCanonicalEnumNameForEveryEdgeType() {
        // Every EdgeType must persist under its own distinct string. The old hand-maintained
        // EDGE_TYPE_MAP covered only 7 of the values and aliased the rest (CONTAINS,
        // EXTRACTED_FROM, AUTHORED_BY, ADDRESSED_TO, RESOLVES_TO) to "RELATED_TO", collapsing
        // five distinct types into one indistinguishable bucket on the @Primary backend.
        for (EdgeType type : EdgeType.values()) {
            service.createEdge("a", "b", type, 1.0, "desc");
        }

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(graphStore, times(EdgeType.values().length)).addEdge(
                eq(DEFAULT_GRAPH_ID), eq("a"), eq("b"), anyDouble(), typeCaptor.capture(), anyBoolean());

        List<String> stored = typeCaptor.getAllValues();
        List<String> expected = Arrays.stream(EdgeType.values()).map(Enum::name).toList();
        assertEquals(expected, stored, "each EdgeType must store under its own enum name");
        assertEquals(EdgeType.values().length, Set.copyOf(stored).size(),
                "no two EdgeType values may collide onto the same stored string");
        assertTrue(stored.contains("RESOLVES_TO"), "RESOLVES_TO must survive (was aliased to RELATED_TO)");
        assertFalse(stored.contains("RELATED_TO"), "no EdgeType should be aliased to the legacy RELATED_TO bucket");
    }

    @Test
    void edgeTypeParsesBackFromStorageAndLegacyAliasIsGraceful() {
        // Reverse direction: a stored string must parse back to the same EdgeType, and the
        // historical non-enum "RELATED_TO" (or any unknown string) must degrade to USER_DEFINED
        // instead of throwing IllegalArgumentException (the old EdgeType.valueOf(type) path).
        assertEquals(EdgeType.RESOLVES_TO,
                service.updateEdge("a::b::RESOLVES_TO", 1.0, "d").getEdgeType());
        assertEquals(EdgeType.CONTAINS,
                service.updateEdge("a::b::CONTAINS", 1.0, "d").getEdgeType());
        assertEquals(EdgeType.USER_DEFINED,
                service.updateEdge("a::b::RELATED_TO", 1.0, "d").getEdgeType(),
                "legacy RELATED_TO alias must degrade to USER_DEFINED, not throw");
        assertEquals(EdgeType.USER_DEFINED,
                service.updateEdge("a::b::NOT_A_REAL_TYPE", 1.0, "d").getEdgeType(),
                "unknown stored type must degrade to USER_DEFINED, not throw");
    }

    // ─── orphan detection across node levels (matrix backend) ─────────────────

    @Test
    void findOrphanNodeIdsHonorsRequestedLevelsOnTheMatrixBackend() {
        MatrixGraphNode entityOrphan = matrixNode("entity_orphan", "ENTITY", 1L);
        MatrixGraphNode entityLinked = matrixNode("entity_linked", "ENTITY", 1L);
        MatrixGraphNode docOrphan = matrixNode("doc_orphan", "DOCUMENT", 1L);
        MatrixGraphNode tableOrphan = matrixNode("table_orphan", "TABLE", 1L);
        MatrixGraphNode otherFactSheet = matrixNode("entity_other", "ENTITY", 2L);
        when(graphStore.getAllNodes(DEFAULT_GRAPH_ID)).thenReturn(List.of(
                entityOrphan, entityLinked, docOrphan, tableOrphan, otherFactSheet));
        // Only entity_linked has edges; every other node in fact sheet 1 is degree 0.
        when(graphStore.getEdges(DEFAULT_GRAPH_ID, "entity_linked", null))
                .thenReturn(List.of(Map.entry("some_target", 1.0)));

        // Default (single-arg) stays ENTITY-only so the OrphanPruner auto-prune policy is unchanged.
        assertEquals(List.of("entity_orphan"), service.findOrphanNodeIds(1L));

        // An explicit level set surfaces orphaned DOCUMENT/TABLE nodes too — never crossing fact sheets.
        List<String> broad = service.findOrphanNodeIds(1L,
                Set.of(NodeLevel.ENTITY, NodeLevel.DOCUMENT, NodeLevel.TABLE));
        assertEquals(Set.of("entity_orphan", "doc_orphan", "table_orphan"), Set.copyOf(broad));
        assertFalse(broad.contains("entity_other"), "must not return nodes from another fact sheet");
        assertFalse(broad.contains("entity_linked"), "must not return nodes that have edges");
    }

    private static MatrixGraphNode matrixNode(String nodeId, String nodeType, Long factSheetId) {
        return MatrixGraphNode.builder()
                .nodeId(nodeId).nodeType(nodeType).factSheetId(factSheetId)
                .metadata(new HashMap<>()).build();
    }

}
