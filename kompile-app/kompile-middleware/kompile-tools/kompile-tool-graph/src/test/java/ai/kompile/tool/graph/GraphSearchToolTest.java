/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphSearchToolTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private UnifiedGraphBridge unifiedGraphBridge;

    private GraphSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphSearchTool(graphService);
    }

    @Test
    void searchNodes_emptyQuery_returnsError() {
        var result = tool.searchNodes(new GraphSearchTool.SearchNodesInput("", null, null, null));
        assertEquals("Search query is required", result.get("error"));
    }

    @Test
    void searchNodes_nullQuery_returnsError() {
        var result = tool.searchNodes(new GraphSearchTool.SearchNodesInput(null, null, null, null));
        assertEquals("Search query is required", result.get("error"));
    }

    @Test
    void searchNodes_validQuery_returnsResults() {
        GraphNode node = createNode("n1", "Test Node", NodeLevel.ENTITY);
        when(graphService.searchNodesGlobal(eq("test"), isNull(), anyInt()))
                .thenReturn(List.of(node));

        var result = tool.searchNodes(new GraphSearchTool.SearchNodesInput("test", null, null, 10));

        assertEquals(1, result.get("resultCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals("n1", results.get(0).get("nodeId"));
        assertEquals("Test Node", results.get(0).get("title"));
    }

    @Test
    void searchNodes_withTypeFilter_usesTypedQuery() {
        GraphNode node = createNode("n1", "Entity Node", NodeLevel.ENTITY);
        when(graphService.searchNodesGlobal(eq("test"), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(List.of(node));

        var result = tool.searchNodes(new GraphSearchTool.SearchNodesInput("test", "ENTITY", null, 10));

        assertEquals("ENTITY", result.get("nodeType"));
        assertEquals(1, result.get("resultCount"));
    }

    @Test
    void searchEdges_resolvesEndpointTitlesFromStore_whenEdgeEmbedsHollowNodes() {
        // Store-loaded edges carry ids only; getSourceNode() synthesizes a hollow title-less node.
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("a::b::RELATES");
        edge.setEdgeType(EdgeType.USER_DEFINED);
        edge.setSourceNodeId("a");
        edge.setTargetNodeId("b");
        when(graphService.searchEdges(eq("laptops"), isNull(), anyInt())).thenReturn(List.of(edge));
        when(graphService.getNode("a")).thenReturn(Optional.of(createNode("a", "Alice", NodeLevel.ENTITY)));
        when(graphService.getNode("b")).thenReturn(Optional.of(createNode("b", "Request", NodeLevel.ENTITY)));

        var result = tool.searchEdges(new GraphSearchTool.SearchEdgesInput("laptops", null, null, null, 10));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals("Alice", results.get(0).get("sourceTitle"));
        assertEquals("Request", results.get(0).get("targetTitle"));
    }

    @Test
    void getNodeDetail_missingId_returnsError() {
        var result = tool.getNodeDetail(new GraphSearchTool.GetNodeDetailInput(""));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void getNodeDetail_notFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        var result = tool.getNodeDetail(new GraphSearchTool.GetNodeDetailInput("missing"));
        assertEquals("Node not found: missing", result.get("error"));
    }

    @Test
    void getNodeDetail_found_returnsAllFields() {
        GraphNode node = createNode("n1", "Detail Node", NodeLevel.ENTITY);
        node.setDescription("A test entity");
        node.setConfidence(0.9);
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        var result = tool.getNodeDetail(new GraphSearchTool.GetNodeDetailInput("n1"));

        assertEquals("n1", result.get("nodeId"));
        assertEquals("Detail Node", result.get("title"));
        assertEquals("ENTITY", result.get("type"));
        assertEquals("A test entity", result.get("description"));
        assertEquals(0.9, result.get("confidence"));
    }

    @Test
    void getEdgeDetail_missingId_returnsError() {
        var result = tool.getEdgeDetail(new GraphSearchTool.GetEdgeDetailInput(""));
        assertEquals("edgeId is required", result.get("error"));
    }

    @Test
    void findEdgesBetween_missingNodes_returnsError() {
        var result = tool.findEdgesBetween(new GraphSearchTool.FindEdgesBetweenInput(null, "b", false));
        assertEquals("Both sourceNodeId and targetNodeId are required", result.get("error"));
    }

    @Test
    void searchEdges_noFilter_returnsError() {
        var result = tool.searchEdges(new GraphSearchTool.SearchEdgesInput(null, null, null, null, null));
        assertEquals("Provide at least a query, edgeType, or minWeight filter", result.get("error"));
    }

    @Test
    void searchByMetadata_missingKey_returnsError() {
        var result = tool.searchByMetadata(
                new GraphSearchTool.SearchByMetadataInput(null, null, null, null, null));
        assertEquals("metadataKey is required", result.get("error"));
    }

    @Test
    void searchNodes_scopedFactSheetUsesUnifiedGraph() {
        tool = new GraphSearchTool(graphService, unifiedGraphBridge);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("u1", "ENTITY", "Unified Alpha")
                .addEntity("u2", "DOCUMENT", "Other Document");
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);

        var result = tool.searchNodes(new GraphSearchTool.SearchNodesInput("alpha", null, 7L, 10));

        assertEquals("unified_graph", result.get("source"));
        assertEquals(1, result.get("resultCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals("u1", results.get(0).get("nodeId"));
        assertEquals("Unified Alpha", results.get(0).get("title"));
        verify(graphService, never()).searchNodesInFactSheetByType(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void searchEdges_scopedFactSheetUsesUnifiedGraph() {
        tool = new GraphSearchTool(graphService, unifiedGraphBridge);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("a", "ENTITY", "Alpha")
                .addEntity("b", "ENTITY", "Beta")
                .addRelation("r1", "a", "b", "SHARED_ENTITY", 0.8);
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);

        var result = tool.searchEdges(new GraphSearchTool.SearchEdgesInput(null, "SHARED_ENTITY", 0.5, 7L, 10));

        assertEquals("unified_graph", result.get("source"));
        assertEquals(1, result.get("resultCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals("r1", results.get(0).get("edgeId"));
        assertEquals("Alpha", results.get(0).get("sourceTitle"));
        verify(graphService, never()).getStrongEdgesByTypeInFactSheet(anyLong(), any(), anyDouble(), anyInt());
    }

    @Test
    void getNodeDetail_scopedFactSheetUsesUnifiedGraph() {
        tool = new GraphSearchTool(graphService, unifiedGraphBridge);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity(GraphEntity.builder("u1")
                        .type("ENTITY")
                        .label("Unified Detail")
                        .attribute("description", "Imported analysis node")
                        .attribute("department", "finance")
                        .build());
        when(graphService.getNode("u1")).thenReturn(Optional.empty());
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);

        var result = tool.getNodeDetail(new GraphSearchTool.GetNodeDetailInput("u1", 7L));

        assertEquals("unified_graph", result.get("source"));
        assertEquals("u1", result.get("nodeId"));
        assertEquals("Unified Detail", result.get("title"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertEquals("finance", metadata.get("department"));
    }

    @Test
    void findEdgesBetween_scopedFactSheetUsesUnifiedGraph() {
        tool = new GraphSearchTool(graphService, unifiedGraphBridge);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("a", "ENTITY", "Alpha")
                .addEntity("b", "ENTITY", "Beta")
                .addRelation("r1", "a", "b", "SHARED_ENTITY", 0.8);
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);

        var result = tool.findEdgesBetween(new GraphSearchTool.FindEdgesBetweenInput("a", "b", false, 7L));

        assertEquals("unified_graph", result.get("source"));
        assertTrue((boolean) result.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> edge = (Map<String, Object>) result.get("edge");
        assertEquals("r1", edge.get("edgeId"));
        verify(graphService, never()).findEdgeBetweenNodes(anyString(), anyString());
    }

    @Test
    void searchByMetadata_scopedFactSheetUsesUnifiedGraph() {
        tool = new GraphSearchTool(graphService, unifiedGraphBridge);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity(GraphEntity.builder("u1")
                        .type("ENTITY")
                        .label("Unified Detail")
                        .attribute("department", "finance")
                        .build());
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);

        var result = tool.searchByMetadata(
                new GraphSearchTool.SearchByMetadataInput("department", "finance", null, 7L, 10));

        assertEquals("unified_graph", result.get("source"));
        assertEquals(1, result.get("resultCount"));
        verify(graphService, never()).getNodesInFactSheet(anyLong());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void parseNodeLevel_validValues() {
        assertEquals(NodeLevel.ENTITY, GraphSearchTool.parseNodeLevel("ENTITY"));
        assertEquals(NodeLevel.ENTITY, GraphSearchTool.parseNodeLevel("entity"));
        assertEquals(NodeLevel.DOCUMENT, GraphSearchTool.parseNodeLevel("document"));
        assertNull(GraphSearchTool.parseNodeLevel(null));
        assertNull(GraphSearchTool.parseNodeLevel(""));
        assertNull(GraphSearchTool.parseNodeLevel("invalid"));
    }

    @Test
    void parseEdgeType_validValues() {
        assertEquals(EdgeType.USER_DEFINED, GraphSearchTool.parseEdgeType("USER_DEFINED"));
        assertEquals(EdgeType.HIERARCHICAL, GraphSearchTool.parseEdgeType("hierarchical"));
        assertNull(GraphSearchTool.parseEdgeType(null));
        assertNull(GraphSearchTool.parseEdgeType("bogus"));
    }

    @Test
    void clampLimit_clampsCorrectly() {
        assertEquals(10, GraphSearchTool.clampLimit(null, 30));
        assertEquals(10, GraphSearchTool.clampLimit(-1, 30));
        assertEquals(5, GraphSearchTool.clampLimit(5, 30));
        assertEquals(30, GraphSearchTool.clampLimit(50, 30));
    }

    @Test
    void truncate_handlesEdgeCases() {
        assertEquals("", GraphSearchTool.truncate(null, 10));
        assertEquals("hello", GraphSearchTool.truncate("hello", 10));
        assertEquals("1234567...", GraphSearchTool.truncate("1234567890ABC", 10));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    static GraphNode createNode(String nodeId, String title, NodeLevel type) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setTitle(title);
        node.setNodeType(type);
        node.setEdgeCount(0);
        node.setChildCount(0);
        return node;
    }
}
