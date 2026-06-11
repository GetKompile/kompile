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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KnowledgeGraphServiceImpl, covering node creation,
 * document/table creation, edge management, and statistics features.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeGraphServiceImplTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    private KnowledgeGraphServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Track all saved nodes so findByNodeId can return them by dynamically generated UUIDs.
     */
    private final Map<String, GraphNode> savedNodes = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        service = new KnowledgeGraphServiceImpl(nodeRepository, edgeRepository, objectMapper);
        savedNodes.clear();

        // Default: save captures the node and returns it with an ID assigned
        when(nodeRepository.save(any(GraphNode.class))).thenAnswer(inv -> {
            GraphNode n = inv.getArgument(0);
            if (n.getId() == null) n.setId((long) (Math.random() * 100000));
            if (n.getNodeId() == null) n.setNodeId(UUID.randomUUID().toString());
            savedNodes.put(n.getNodeId(), n);
            if (n.getExternalId() != null) {
                savedNodes.put(n.getExternalId(), n);
            }
            return n;
        });

        // Default: findByNodeId looks up in our saved map, then falls through to empty
        when(nodeRepository.findByNodeId(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.ofNullable(savedNodes.get(id));
        });

        // Default: edge stubs
        when(edgeRepository.findEdgeBetweenNodes(any(), any())).thenReturn(Optional.empty());
        when(edgeRepository.save(any(GraphEdge.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void addDocumentCreatesSourceAndDocumentNodes() {
        // addDocument was removed; replicate via createOrUpdateSourceNode + createDocumentNode
        when(nodeRepository.findByExternalIdAndNodeType(anyString(), eq(NodeLevel.SOURCE)))
            .thenReturn(Optional.empty());
        when(nodeRepository.findByExternalIdAndNodeType(anyString(), eq(NodeLevel.DOCUMENT)))
            .thenReturn(Optional.empty());
        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        GraphNode source = service.createOrUpdateSourceNode("inbox-1", "User Inbox", "EMAIL", null, null);
        GraphNode doc = service.createDocumentNode(source, "doc-1", "Q4 Report Email",
            Map.of("from", "user@example.com"));

        assertEquals(NodeLevel.DOCUMENT, doc.getNodeType());
        assertEquals("Q4 Report Email", doc.getTitle());
        verify(nodeRepository, atLeast(2)).save(any());
    }

    @Test
    void addDocumentSetsContentPreview() {
        // addDocument was removed; replicate via createOrUpdateSourceNode + createSnippetNode
        when(nodeRepository.findByExternalIdAndNodeType(any(), eq(NodeLevel.SOURCE)))
            .thenReturn(Optional.empty());
        when(nodeRepository.findByExternalIdAndNodeType(any(), eq(NodeLevel.DOCUMENT)))
            .thenReturn(Optional.empty());
        when(nodeRepository.findByExternalIdAndNodeType(any(), eq(NodeLevel.SNIPPET)))
            .thenReturn(Optional.empty());
        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        GraphNode source = service.createOrUpdateSourceNode("s1", "Source", "FILE", null, null);
        GraphNode document = service.createDocumentNode(source, "d1", "Doc", null);
        String longContent = "A".repeat(600);
        service.createSnippetNode(document, "snip-d1", longContent, 0);

        // After createSnippetNode, the snippet node should have a truncated preview
        boolean foundPreview = savedNodes.values().stream()
            .anyMatch(n -> n.getContentPreview() != null && n.getContentPreview().endsWith("..."));
        assertTrue(foundPreview, "Long content should be truncated to 500 chars with '...'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TABLE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void createTableNodeSetsTypeAndMetadata() {
        GraphNode parent = createAndRegisterNode("doc-1", NodeLevel.DOCUMENT, "document.xlsx");
        parent.setId(1L);
        when(nodeRepository.findByExternalIdAndNodeType("sheet-1", NodeLevel.TABLE))
            .thenReturn(Optional.empty());

        GraphNode table = service.createTableNode(
            "doc-1", "sheet-1", "Revenue Summary",
            100, 5,
            List.of("Quarter", "Revenue", "Costs", "Profit", "Growth"),
            "Q1,1000,800,200,5%\nQ2,1200,900,300,10%",
            Map.of("sheetIndex", 0)
        );

        assertEquals(NodeLevel.TABLE, table.getNodeType());
        assertEquals("Revenue Summary", table.getTitle());
        assertNotNull(table.getMetadataJson());
        assertTrue(table.getMetadataJson().contains("rowCount"));
        assertTrue(table.getMetadataJson().contains("columnCount"));
        assertTrue(table.getMetadataJson().contains("headers"));
    }

    @Test
    void createTableNodeTruncatesLongContent() {
        GraphNode parent = createAndRegisterNode("doc-1", NodeLevel.DOCUMENT, "big.xlsx");
        parent.setId(1L);
        when(nodeRepository.findByExternalIdAndNodeType("tab-1", NodeLevel.TABLE))
            .thenReturn(Optional.empty());

        String longContent = "X".repeat(600);
        service.createTableNode("doc-1", "tab-1", "Big Table",
            1000, 20, null, longContent, null);

        GraphNode tableNode = savedNodes.values().stream()
            .filter(n -> n.getNodeType() == NodeLevel.TABLE)
            .findFirst()
            .orElseThrow();
        // Production createTableNode stores truncated content in description (via createNode), not contentPreview
        assertEquals(503, tableNode.getDescription().length());
        assertTrue(tableNode.getDescription().endsWith("..."));
    }

    @Test
    void createTableNodeCreatesContainsEdge() {
        GraphNode parent = createAndRegisterNode("doc-1", NodeLevel.DOCUMENT, "file.xlsx");
        parent.setId(1L);
        when(nodeRepository.findByExternalIdAndNodeType("t1", NodeLevel.TABLE))
            .thenReturn(Optional.empty());

        service.createTableNode("doc-1", "t1", "Sheet", 10, 3, null, null, null);

        // Production createTableNode uses EdgeType.HIERARCHICAL for the parent→table edge
        verify(edgeRepository, atLeastOnce()).save(argThat(edge ->
            edge.getEdgeType() == EdgeType.HIERARCHICAL &&
            edge.getDescription() != null &&
            edge.getDescription().toLowerCase().contains("table")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getGraphStatisticsIncludesNodeTypeCounts() {
        when(nodeRepository.count()).thenReturn(100L);
        when(nodeRepository.countByNodeType(NodeLevel.SOURCE)).thenReturn(5L);
        when(nodeRepository.countByNodeType(NodeLevel.DOCUMENT)).thenReturn(20L);
        when(nodeRepository.countByNodeType(NodeLevel.SNIPPET)).thenReturn(50L);
        when(nodeRepository.countByNodeType(NodeLevel.ENTITY)).thenReturn(15L);
        when(nodeRepository.countByNodeType(NodeLevel.CUSTOM)).thenReturn(0L);
        when(edgeRepository.count()).thenReturn(200L);
        when(edgeRepository.countByEdgeType(any())).thenReturn(0L);

        Map<String, Object> stats = service.getGraphStatistics();

        assertEquals(100L, stats.get("totalNodes"));
        assertEquals(5L, stats.get("sourceCount"));
        assertEquals(20L, stats.get("documentCount"));
        assertEquals(50L, stats.get("snippetCount"));
        assertEquals(15L, stats.get("entityCount"));
        assertEquals(200L, stats.get("totalEdges"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void createNodeSetsTypeAndTitle() {
        when(nodeRepository.findByExternalIdAndNodeType("entity-1", NodeLevel.ENTITY))
            .thenReturn(Optional.empty());
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("entity-1", NodeLevel.ENTITY, null))
            .thenReturn(Optional.empty());

        GraphNode entity = service.createNode(NodeLevel.ENTITY, "entity-1", "Test Entity",
            "A description", Map.of("key", "value"));

        assertEquals(NodeLevel.ENTITY, entity.getNodeType());
        assertEquals("Test Entity", entity.getTitle());
        assertEquals("A description", entity.getDescription());
    }

    @Test
    void createNodeUpdatesExistingNode() {
        GraphNode existing = createNode("entity-existing", NodeLevel.ENTITY, "Old Title");
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("entity-existing", NodeLevel.ENTITY, null))
            .thenReturn(Optional.of(existing));
        when(nodeRepository.findByExternalIdAndNodeType("entity-existing", NodeLevel.ENTITY))
            .thenReturn(Optional.of(existing));

        GraphNode updated = service.createNode(NodeLevel.ENTITY, "entity-existing", "New Title",
            "new desc", null);

        assertEquals("New Title", updated.getTitle());
    }

    @Test
    void createSourceNodeSetsCorrectType() {
        when(nodeRepository.findByExternalIdAndNodeType("src-ext-1", NodeLevel.SOURCE))
            .thenReturn(Optional.empty());

        GraphNode source = service.createOrUpdateSourceNode("src-ext-1", "My Source", "FILE",
            "/path/to/file", null);

        assertEquals(NodeLevel.SOURCE, source.getNodeType());
        assertEquals("My Source", source.getTitle());
        assertEquals("FILE", source.getSourceType());
    }

    @Test
    void createDocumentNodeSetsParentAndType() {
        GraphNode source = createAndRegisterNode("src-1", NodeLevel.SOURCE, "Source");
        source.setId(1L);
        when(nodeRepository.findByExternalIdAndNodeType("doc-ext-1", NodeLevel.DOCUMENT))
            .thenReturn(Optional.empty());
        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        GraphNode doc = service.createDocumentNode(source, "doc-ext-1", "My Document",
            Map.of("author", "Alice"));

        assertEquals(NodeLevel.DOCUMENT, doc.getNodeType());
        assertEquals("My Document", doc.getTitle());
        assertEquals(source, doc.getParent());
    }

    @Test
    void createSnippetNodeSetsPreviewAndParent() {
        GraphNode document = createAndRegisterNode("doc-1", NodeLevel.DOCUMENT, "Document");
        document.setId(2L);
        when(nodeRepository.findByExternalIdAndNodeType("snip-1", NodeLevel.SNIPPET))
            .thenReturn(Optional.empty());
        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        String content = "This is a test snippet with some content for testing.";
        GraphNode snippet = service.createSnippetNode(document, "snip-1", content, 0);

        assertEquals(NodeLevel.SNIPPET, snippet.getNodeType());
        assertEquals(content, snippet.getContentPreview());
    }

    @Test
    void createSnippetNodeTruncatesLongContent() {
        GraphNode document = createAndRegisterNode("doc-long", NodeLevel.DOCUMENT, "Long Doc");
        document.setId(3L);
        when(nodeRepository.findByExternalIdAndNodeType("snip-long", NodeLevel.SNIPPET))
            .thenReturn(Optional.empty());
        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        String longContent = "Z".repeat(600);
        GraphNode snippet = service.createSnippetNode(document, "snip-long", longContent, 1);

        assertEquals(503, snippet.getContentPreview().length());
        assertTrue(snippet.getContentPreview().endsWith("..."));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void createEdgeBetweenNodes() {
        GraphNode source = createAndRegisterNode("edge-src", NodeLevel.SOURCE, "Source");
        source.setId(10L);
        GraphNode target = createAndRegisterNode("edge-tgt", NodeLevel.DOCUMENT, "Target");
        target.setId(11L);

        when(edgeRepository.findAllEdgesForNodeId(anyString())).thenReturn(Collections.emptyList());

        GraphEdge edge = service.createEdge(source.getNodeId(), target.getNodeId(),
            EdgeType.HIERARCHICAL, 1.0, "Source contains target");

        assertNotNull(edge);
        assertEquals(EdgeType.HIERARCHICAL, edge.getEdgeType());
        verify(edgeRepository, atLeastOnce()).save(any(GraphEdge.class));
    }

    @Test
    void getEdgesForNodeReturnsCorrectEdges() {
        GraphNode node = createAndRegisterNode("n1", NodeLevel.ENTITY, "N1");
        GraphEdge mockEdge = GraphEdge.builder()
            .edgeId("e1")
            .sourceNode(node)
            .edgeType(EdgeType.USER_DEFINED)
            .build();

        when(edgeRepository.findAllEdgesForNodeId(node.getNodeId())).thenReturn(List.of(mockEdge));

        List<GraphEdge> edges = service.getEdgesForNode(node.getNodeId());
        assertEquals(1, edges.size());
        assertEquals("e1", edges.get(0).getEdgeId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTED NODES & RELATED
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getConnectedNodesReturnsNeighbors() {
        GraphNode root = createAndRegisterNode("root-cn", NodeLevel.SOURCE, "Root");
        GraphNode child = createAndRegisterNode("child-cn", NodeLevel.DOCUMENT, "Child");

        GraphEdge edge = GraphEdge.builder()
            .edgeId("e-cn-1")
            .sourceNode(root)
            .targetNode(child)
            .edgeType(EdgeType.HIERARCHICAL)
            .build();

        when(edgeRepository.findOutgoingEdges(root)).thenReturn(List.of(edge));
        when(edgeRepository.findOutgoingEdges(child)).thenReturn(Collections.emptyList());

        List<GraphNode> connected = service.getConnectedNodes(root.getNodeId(), 1);
        assertEquals(1, connected.size());
        assertEquals("child-cn", connected.get(0).getNodeId());
    }

    @Test
    void findRelatedNodesExcludesHierarchicalEdges() {
        GraphNode node = createAndRegisterNode("rel-node", NodeLevel.ENTITY, "Entity");
        GraphNode relatedViaHierarchical = createAndRegisterNode("hier-node", NodeLevel.DOCUMENT, "Doc");
        GraphNode relatedViaUserDefined = createAndRegisterNode("user-node", NodeLevel.ENTITY, "Related");

        GraphEdge hierarchicalEdge = GraphEdge.builder()
            .edgeId("e-hier")
            .sourceNode(node)
            .targetNode(relatedViaHierarchical)
            .edgeType(EdgeType.HIERARCHICAL)
            .weight(1.0)
            .build();
        GraphEdge userEdge = GraphEdge.builder()
            .edgeId("e-user")
            .sourceNode(node)
            .targetNode(relatedViaUserDefined)
            .edgeType(EdgeType.USER_DEFINED)
            .weight(0.9)
            .build();

        when(edgeRepository.findAllEdgesForNode(node)).thenReturn(List.of(hierarchicalEdge, userEdge));

        List<GraphNode> related = service.findRelatedNodes(node.getNodeId(), 10);
        assertEquals(1, related.size());
        assertEquals("user-node", related.get(0).getNodeId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH STATISTICS & VISUALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getVisualizationDataWithRootNodeReturnsCorrectStructure() {
        GraphNode root = createAndRegisterNode("viz-root", NodeLevel.SOURCE, "Root");

        when(edgeRepository.findAll()).thenReturn(Collections.emptyList());
        when(edgeRepository.findOutgoingEdges(any())).thenReturn(Collections.emptyList());

        Map<String, Object> vizData = service.getVisualizationData(root.getNodeId(), 2, 100);

        assertTrue(vizData.containsKey("nodes"));
        assertTrue(vizData.containsKey("edges"));
        assertTrue(vizData.containsKey("metadata"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) vizData.get("nodes");
        assertFalse(nodes.isEmpty());
    }

    @Test
    void getGraphStatisticsReturnsTotalNodesAndEdges() {
        when(nodeRepository.count()).thenReturn(50L);
        when(nodeRepository.countByNodeType(any())).thenReturn(0L);
        when(edgeRepository.count()).thenReturn(30L);
        when(edgeRepository.countByEdgeType(any())).thenReturn(0L);

        Map<String, Object> stats = service.getGraphStatistics();

        assertEquals(50L, stats.get("totalNodes"));
        assertEquals(30L, stats.get("totalEdges"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE AND DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void updateNodeChangesTitle() {
        GraphNode existing = createAndRegisterNode("upd-1", NodeLevel.ENTITY, "Old Title");
        existing.setId(100L);

        GraphNode updated = service.updateNode(existing.getNodeId(), "New Title", "new desc", null);

        assertEquals("New Title", updated.getTitle());
        assertEquals("new desc", updated.getDescription());
    }

    @Test
    void updateNodeThrowsForUnknownNode() {
        assertThrows(IllegalArgumentException.class, () ->
            service.updateNode("nonexistent-node", "Title", null, null));
    }

    @Test
    void deleteNodeInvokesRepositoryDelete() {
        GraphNode node = createAndRegisterNode("del-1", NodeLevel.ENTITY, "To Delete");
        node.setId(200L);
        when(edgeRepository.deleteAllEdgesForNode(node)).thenReturn(0);

        service.deleteNode(node.getNodeId());

        verify(nodeRepository).delete(node);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void searchNodesWithQueryReturnsMatchingNodes() {
        GraphNode n1 = createNode("s1", NodeLevel.ENTITY, "Machine Learning");
        org.springframework.data.domain.Page<GraphNode> page =
            new org.springframework.data.domain.PageImpl<>(List.of(n1));
        when(nodeRepository.searchByTitleOrDescription(eq("machine"), any())).thenReturn(page);

        List<GraphNode> results = service.searchNodes("machine", null, 10);
        assertEquals(1, results.size());
        assertEquals("Machine Learning", results.get(0).getTitle());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode createNode(String nodeId, NodeLevel type, String title) {
        return GraphNode.builder()
            .nodeId(nodeId)
            .externalId(nodeId)
            .nodeType(type)
            .title(title)
            .build();
    }

    /**
     * Create a node and register it in the savedNodes map so findByNodeId will return it.
     */
    private GraphNode createAndRegisterNode(String nodeId, NodeLevel type, String title) {
        GraphNode node = createNode(nodeId, type, title);
        savedNodes.put(nodeId, node);
        return node;
    }
}
