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

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
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
class GraphMutationToolTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private GraphAlgorithmService algorithmService;

    private GraphMutationTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphMutationTool(graphService, algorithmService);
    }

    @Test
    void createNode_missingTitle_returnsError() {
        var result = tool.createNode(new GraphMutationTool.CreateNodeInput("", "ENTITY", null, null, null));
        assertEquals("title is required", result.get("error"));
    }

    @Test
    void createNode_validInput_createsAndInvalidatesCache() {
        GraphNode node = GraphSearchToolTest.createNode("new-id", "New Entity", NodeLevel.ENTITY);
        when(graphService.createNode(eq(NodeLevel.ENTITY), isNull(), eq("New Entity"),
                eq("A description"), eq(Map.of()), isNull()))
                .thenReturn(node);

        var result = tool.createNode(new GraphMutationTool.CreateNodeInput(
                "New Entity", "ENTITY", "A description", null, null));

        assertEquals("new-id", result.get("nodeId"));
        assertEquals("New Entity", result.get("title"));
        verify(algorithmService).invalidateCache((Long) null);
    }

    @Test
    void createNode_defaultsToCustomType() {
        GraphNode node = GraphSearchToolTest.createNode("id", "Node", NodeLevel.CUSTOM);
        when(graphService.createNode(eq(NodeLevel.CUSTOM), any(), any(), any(), any(), any()))
                .thenReturn(node);

        var result = tool.createNode(new GraphMutationTool.CreateNodeInput(
                "Node", "invalid_type", null, null, null));

        assertNull(result.get("error"));
    }

    @Test
    void updateNode_missingId_returnsError() {
        var result = tool.updateNode(new GraphMutationTool.UpdateNodeInput("", "t", "d"));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void deleteNode_notFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        var result = tool.deleteNode(new GraphMutationTool.DeleteNodeInput("missing"));
        assertEquals("Node not found: missing", result.get("error"));
    }

    @Test
    void deleteNode_found_deletesAndInvalidates() {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Delete Me", NodeLevel.ENTITY);
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));

        var result = tool.deleteNode(new GraphMutationTool.DeleteNodeInput("n1"));

        assertEquals(true, result.get("deleted"));
        assertEquals("n1", result.get("nodeId"));
        verify(graphService).deleteNode("n1");
        verify(algorithmService).invalidateCache((Long) null);
    }

    @Test
    void createEdge_missingNodes_returnsError() {
        var result = tool.createEdge(new GraphMutationTool.CreateEdgeInput(null, "b", null, null, null));
        assertEquals("Both sourceNodeId and targetNodeId are required", result.get("error"));
    }

    @Test
    void createEdge_defaultsToUserDefined() {
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("e1");
        edge.setEdgeType(EdgeType.USER_DEFINED);
        edge.setWeight(1.0);
        when(graphService.createEdge("a", "b", EdgeType.USER_DEFINED, 1.0, "test"))
                .thenReturn(edge);

        var result = tool.createEdge(new GraphMutationTool.CreateEdgeInput(
                "a", "b", null, null, "test"));

        assertEquals("e1", result.get("edgeId"));
        verify(algorithmService).invalidateCache();
    }

    @Test
    void updateEdge_missingId_returnsError() {
        var result = tool.updateEdge(new GraphMutationTool.UpdateEdgeInput("", null, null));
        assertEquals("edgeId is required", result.get("error"));
    }

    @Test
    void deleteEdge_notFound_returnsError() {
        when(graphService.getEdge("missing")).thenReturn(Optional.empty());
        var result = tool.deleteEdge(new GraphMutationTool.DeleteEdgeInput("missing"));
        assertEquals("Edge not found: missing", result.get("error"));
    }

    @Test
    void bulkCreateEdges_emptyList_returnsError() {
        var result = tool.bulkCreateEdges(new GraphMutationTool.BulkCreateEdgesInput(List.of()));
        assertEquals("At least one edge specification is required", result.get("error"));
    }

    @Test
    void mergeNodes_missingCanonical_returnsError() {
        var result = tool.mergeNodes(new GraphMutationTool.MergeNodesInput("", List.of("n2")));
        assertEquals("canonicalNodeId is required", result.get("error"));
    }

    @Test
    void mergeNodes_canonicalNotFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        var result = tool.mergeNodes(new GraphMutationTool.MergeNodesInput("missing", List.of("n2")));
        assertEquals("Canonical node not found: missing", result.get("error"));
    }
}
