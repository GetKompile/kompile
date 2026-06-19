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

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphLabelToolTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;

    private GraphLabelTool tool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new GraphLabelTool(graphService, objectMapper);
    }

    @Test
    void addNodeLabels_missingNodeId_returnsError() {
        var result = tool.addNodeLabels(new GraphLabelTool.AddNodeLabelsInput("", List.of("tag")));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void addNodeLabels_noLabels_returnsError() {
        var result = tool.addNodeLabels(new GraphLabelTool.AddNodeLabelsInput("n1", List.of()));
        assertEquals("At least one label is required", result.get("error"));
    }

    @Test
    void addNodeLabels_nodeNotFound_returnsError() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());
        var result = tool.addNodeLabels(new GraphLabelTool.AddNodeLabelsInput("missing", List.of("tag")));
        assertEquals("Node not found: missing", result.get("error"));
    }

    @Test
    void addNodeLabels_addsLabelsToMetadata() throws Exception {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Node", NodeLevel.ENTITY);
        node.setMetadataJson("{}");
        when(nodeRepository.findByNodeId("n1")).thenReturn(Optional.of(node));
        when(nodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = tool.addNodeLabels(
                new GraphLabelTool.AddNodeLabelsInput("n1", List.of("reviewed", "priority-high")));

        assertEquals("n1", result.get("nodeId"));
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("labels");
        assertTrue(labels.contains("reviewed"));
        assertTrue(labels.contains("priority-high"));
        verify(nodeRepository).save(any(GraphNode.class));
    }

    @Test
    void addNodeLabels_deduplicates() throws Exception {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Node", NodeLevel.ENTITY);
        node.setMetadataJson("{\"labels\":[\"existing\"]}");
        when(nodeRepository.findByNodeId("n1")).thenReturn(Optional.of(node));
        when(nodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = tool.addNodeLabels(
                new GraphLabelTool.AddNodeLabelsInput("n1", List.of("existing", "new-tag")));

        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("labels");
        assertEquals(2, labels.size());
        assertTrue(labels.contains("existing"));
        assertTrue(labels.contains("new-tag"));
    }

    @Test
    void removeNodeLabels_removesCorrectly() throws Exception {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Node", NodeLevel.ENTITY);
        node.setMetadataJson("{\"labels\":[\"keep\",\"remove-me\"]}");
        when(nodeRepository.findByNodeId("n1")).thenReturn(Optional.of(node));
        when(nodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = tool.removeNodeLabels(
                new GraphLabelTool.RemoveNodeLabelsInput("n1", List.of("remove-me")));

        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("labels");
        assertEquals(1, labels.size());
        assertTrue(labels.contains("keep"));
        assertFalse(labels.contains("remove-me"));
    }

    @Test
    void getNodeLabels_returnsLabels() {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Node", NodeLevel.ENTITY);
        node.setMetadataJson("{\"labels\":[\"a\",\"b\"]}");
        when(nodeRepository.findByNodeId("n1")).thenReturn(Optional.of(node));

        var result = tool.getNodeLabels(new GraphLabelTool.GetNodeLabelsInput("n1"));

        assertEquals("n1", result.get("nodeId"));
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("labels");
        assertEquals(2, labels.size());
    }

    @Test
    void getNodeLabels_emptyMetadata_returnsEmptyLabels() {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Node", NodeLevel.ENTITY);
        when(nodeRepository.findByNodeId("n1")).thenReturn(Optional.of(node));

        var result = tool.getNodeLabels(new GraphLabelTool.GetNodeLabelsInput("n1"));

        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("labels");
        assertTrue(labels.isEmpty());
    }

    @Test
    void parseMetadata_handlesNullAndEmpty() {
        assertTrue(tool.parseMetadata(null).isEmpty());
        assertTrue(tool.parseMetadata("").isEmpty());
        assertTrue(tool.parseMetadata("not json").isEmpty());
    }

    @Test
    void getLabelsFromMetadata_handlesNoLabelsKey() {
        assertTrue(tool.getLabelsFromMetadata(Map.of()).isEmpty());
        assertTrue(tool.getLabelsFromMetadata(Map.of("other", "value")).isEmpty());
    }

    @Test
    void bulkLabelNodes_missingInputs_returnsErrors() {
        var r1 = tool.bulkLabelNodes(new GraphLabelTool.BulkLabelNodesInput(List.of(), List.of("t")));
        assertEquals("nodeIds is required", r1.get("error"));

        var r2 = tool.bulkLabelNodes(new GraphLabelTool.BulkLabelNodesInput(List.of("n1"), List.of()));
        assertEquals("At least one label is required", r2.get("error"));
    }
}
