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

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
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
class GraphTraversalToolTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private GraphAlgorithmService algorithmService;
    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphRagService graphRagService;

    private GraphTraversalTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphTraversalTool(graphService, algorithmService, nodeRepository, graphRagService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BFS Traversal
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void bfsTraversal_missingStartNode_returnsError() {
        var result = tool.bfsTraversal(new GraphTraversalTool.BfsTraversalInput("", null, null));
        assertEquals("startNodeId is required", result.get("error"));
    }

    @Test
    void bfsTraversal_noAlgorithmService_returnsError() {
        var toolNoAlgo = new GraphTraversalTool(graphService, null, nodeRepository, graphRagService);
        var result = toolNoAlgo.bfsTraversal(new GraphTraversalTool.BfsTraversalInput("n1", null, null));
        assertEquals("Graph algorithm service not available", result.get("error"));
    }

    @Test
    void bfsTraversal_returnsLeveledResults() {
        Map<Integer, List<String>> levels = new LinkedHashMap<>();
        levels.put(0, List.of("n1"));
        levels.put(1, List.of("n2", "n3"));
        when(algorithmService.bfsTraversal(isNull(), eq("n1"), eq(3)))
                .thenReturn(levels);
        when(nodeRepository.findByNodeIdIn(anyList()))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n1", "Root", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n2", "Child 1", NodeLevel.DOCUMENT),
                        GraphSearchToolTest.createNode("n3", "Child 2", NodeLevel.SNIPPET)
                ));

        var result = tool.bfsTraversal(new GraphTraversalTool.BfsTraversalInput("n1", null, null));

        assertEquals("n1", result.get("startNodeId"));
        assertEquals(3, result.get("maxDepth"));
        assertEquals(2, result.get("levelsReached"));
        assertEquals(3, result.get("totalNodes"));
    }

    @Test
    void bfsTraversal_clampsMaxDepthTo5() {
        when(algorithmService.bfsTraversal(isNull(), eq("n1"), eq(5)))
                .thenReturn(Map.of());
        when(nodeRepository.findByNodeIdIn(anyList())).thenReturn(List.of());

        tool.bfsTraversal(new GraphTraversalTool.BfsTraversalInput("n1", null, 10));

        verify(algorithmService).bfsTraversal(isNull(), eq("n1"), eq(5));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ego Network
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void egoNetwork_missingNodeId_returnsError() {
        var result = tool.egoNetwork(new GraphTraversalTool.EgoNetworkInput("", null, null));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void egoNetwork_returnsNodesAndEdges() {
        GraphNode n2 = GraphSearchToolTest.createNode("n2", "Neighbor", NodeLevel.ENTITY);
        when(graphService.getConnectedNodes("center", 1))
                .thenReturn(List.of(n2));
        when(graphService.getEdgesForNode(anyString())).thenReturn(List.of());

        var result = tool.egoNetwork(new GraphTraversalTool.EgoNetworkInput("center", null, null));

        assertEquals("center", result.get("centerNodeId"));
        assertEquals(1, result.get("radius"));
        assertEquals(1, result.get("nodeCount"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Node Edges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void nodeEdges_missingNodeId_returnsError() {
        var result = tool.nodeEdges(new GraphTraversalTool.NodeEdgesInput("", null, null, null));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void nodeEdges_returnsEdgeList() {
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("e1");
        edge.setEdgeType(EdgeType.HIERARCHICAL);
        edge.setWeight(1.0);
        GraphNode src = GraphSearchToolTest.createNode("n1", "Source", NodeLevel.ENTITY);
        GraphNode tgt = GraphSearchToolTest.createNode("n2", "Target", NodeLevel.DOCUMENT);
        edge.setSourceNode(src);
        edge.setTargetNode(tgt);
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(edge));

        var result = tool.nodeEdges(
                new GraphTraversalTool.NodeEdgesInput("n1", null, null, null));

        assertEquals("n1", result.get("nodeId"));
        assertEquals("all", result.get("direction"));
        assertEquals(1, result.get("edgeCount"));
    }

    @Test
    void nodeEdges_filtersDirection() {
        GraphEdge outEdge = new GraphEdge();
        outEdge.setEdgeId("e1");
        outEdge.setEdgeType(EdgeType.HIERARCHICAL);
        outEdge.setWeight(1.0);
        GraphNode src = GraphSearchToolTest.createNode("n1", "Source", NodeLevel.ENTITY);
        GraphNode tgt = GraphSearchToolTest.createNode("n2", "Target", NodeLevel.ENTITY);
        outEdge.setSourceNode(src);
        outEdge.setTargetNode(tgt);

        GraphEdge inEdge = new GraphEdge();
        inEdge.setEdgeId("e2");
        inEdge.setEdgeType(EdgeType.CITATION);
        inEdge.setWeight(0.5);
        inEdge.setSourceNode(tgt);
        inEdge.setTargetNode(src);

        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(outEdge, inEdge));

        var result = tool.nodeEdges(
                new GraphTraversalTool.NodeEdgesInput("n1", "out", null, null));
        assertEquals(1, result.get("edgeCount"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Neighborhood
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void neighborhood_missingNodeId_returnsError() {
        var result = tool.neighborhood(
                new GraphTraversalTool.NeighborhoodInput("", null, null, null));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void neighborhood_returnsFlatNeighborList() {
        when(graphService.getConnectedNodes("n1", 2))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n1", "Self", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n2", "Neighbor 1", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n3", "Neighbor 2", NodeLevel.DOCUMENT)
                ));

        var result = tool.neighborhood(
                new GraphTraversalTool.NeighborhoodInput("n1", null, null, null));

        assertEquals("n1", result.get("nodeId"));
        assertEquals(2, result.get("hops"));
        assertEquals(2, result.get("neighborCount")); // excludes self
    }

    @Test
    void neighborhood_filtersbyNodeType() {
        when(graphService.getConnectedNodes("n1", 2))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n2", "Entity", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n3", "Doc", NodeLevel.DOCUMENT)
                ));

        var result = tool.neighborhood(
                new GraphTraversalTool.NeighborhoodInput("n1", null, "ENTITY", null));

        assertEquals(1, result.get("neighborCount"));
        assertEquals("ENTITY", result.get("filteredType"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Visualization Data
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void visualizationData_delegatesToService() {
        Map<String, Object> vizData = Map.of("nodes", List.of(), "edges", List.of());
        when(graphService.getVisualizationData("root", 3, 100))
                .thenReturn(vizData);

        var result = tool.getVisualizationData(
                new GraphTraversalTool.GraphVisualizationInput("root", null, null));

        assertEquals(vizData, result);
    }

    @Test
    void visualizationData_clampsMaxNodes() {
        when(graphService.getVisualizationData(isNull(), eq(5), eq(200)))
                .thenReturn(Map.of());

        tool.getVisualizationData(
                new GraphTraversalTool.GraphVisualizationInput(null, 10, 500));

        verify(graphService).getVisualizationData(isNull(), eq(5), eq(200));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hybrid Graph Search
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void hybridSearch_missingQuery_returnsError() {
        var result = tool.hybridGraphSearch(
                new GraphTraversalTool.HybridGraphSearchInput("", null, null, null, null, null, null, null));
        assertEquals("query is required", result.get("error"));
    }

    @Test
    void hybridSearch_noService_returnsError() {
        var toolNoRag = new GraphTraversalTool(graphService, algorithmService, nodeRepository, null);
        var result = toolNoRag.hybridGraphSearch(
                new GraphTraversalTool.HybridGraphSearchInput("test", null, null, null, null, null, null, null));
        assertEquals("Graph RAG service not available", result.get("error"));
    }

    @Test
    void hybridSearch_returnsFormattedResult() {
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("Test Entity");
        entity.setType("CONCEPT");
        entity.setDescription("A test concept");

        Relationship rel = new Relationship();
        rel.setSource("e1");
        rel.setTarget("e2");
        rel.setType("RELATED_TO");
        rel.setWeight(0.9);
        rel.setDescription("They are related");

        GraphRagResult ragResult = GraphRagResult.builder()
                .searchType(SearchType.HYBRID)
                .answer("This is the answer")
                .entities(List.of(entity))
                .relationships(List.of(rel))
                .hopsPerformed(2)
                .nodesVisited(5)
                .traversalPaths(Map.of("e1", List.of("e1", "e2")))
                .scoreBreakdown(Map.of("e1", Map.of("vectorScore", 0.8, "graphScore", 0.6, "combined", 0.7)))
                .build();
        when(graphRagService.answerQuery(any())).thenReturn(ragResult);

        var result = tool.hybridGraphSearch(
                new GraphTraversalTool.HybridGraphSearchInput("test query", "HYBRID", 10, 2, 0.5, 50, null, true));

        assertEquals("HYBRID", result.get("searchType"));
        assertEquals("This is the answer", result.get("answer"));
        assertEquals(1, result.get("entityCount"));
        assertEquals(1, result.get("relationshipCount"));
        assertEquals(2, result.get("hopsPerformed"));
        assertEquals(5, result.get("nodesVisited"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) result.get("entities");
        assertEquals("Test Entity", entities.get(0).get("title"));
        assertEquals("CONCEPT", entities.get(0).get("type"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rels = (List<Map<String, Object>>) result.get("relationships");
        assertEquals("e1", rels.get(0).get("source"));
        assertEquals("e2", rels.get(0).get("target"));
        assertEquals("RELATED_TO", rels.get(0).get("type"));
    }

    @Test
    void hybridSearch_invalidSearchType_defaultsToHybrid() {
        GraphRagResult ragResult = GraphRagResult.builder()
                .searchType(SearchType.HYBRID)
                .answer("answer")
                .build();
        when(graphRagService.answerQuery(any())).thenReturn(ragResult);

        var result = tool.hybridGraphSearch(
                new GraphTraversalTool.HybridGraphSearchInput("query", "INVALID_TYPE", null, null, null, null, null, null));

        assertEquals("HYBRID", result.get("searchType"));
    }

    @Test
    void hybridSearch_localSearchType_parsed() {
        GraphRagResult ragResult = GraphRagResult.builder()
                .searchType(SearchType.LOCAL)
                .answer("local answer")
                .build();
        when(graphRagService.answerQuery(any())).thenReturn(ragResult);

        var result = tool.hybridGraphSearch(
                new GraphTraversalTool.HybridGraphSearchInput("query", "LOCAL", null, null, null, null, null, null));

        assertEquals("LOCAL", result.get("searchType"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shortest Path (traversal-level)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shortestPath_missingFromNode_returnsError() {
        var result = tool.shortestPath(
                new GraphTraversalTool.ShortestPathInput("", "b", null));
        assertEquals("fromNodeId is required", result.get("error"));
    }

    @Test
    void shortestPath_missingToNode_returnsError() {
        var result = tool.shortestPath(
                new GraphTraversalTool.ShortestPathInput("a", "", null));
        assertEquals("toNodeId is required", result.get("error"));
    }

    @Test
    void shortestPath_noPathFound_returnsNotFound() {
        when(graphService.findShortestPath("a", "b", 5))
                .thenReturn(List.of());

        var result = tool.shortestPath(
                new GraphTraversalTool.ShortestPathInput("a", "b", null));

        assertEquals(false, result.get("found"));
        assertTrue(result.get("message").toString().contains("No path found"));
    }

    @Test
    void shortestPath_pathFound_returnsOrderedNodes() {
        when(graphService.findShortestPath("a", "c", 5))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("a", "Start", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("b", "Through", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("c", "End", NodeLevel.ENTITY)
                ));

        var result = tool.shortestPath(
                new GraphTraversalTool.ShortestPathInput("a", "c", null));

        assertEquals(true, result.get("found"));
        assertEquals(2, result.get("pathLength")); // 3 nodes = 2 hops
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> path = (List<Map<String, Object>>) result.get("path");
        assertEquals(3, path.size());
        assertEquals("Start", path.get(0).get("title"));
        assertEquals("End", path.get(2).get("title"));
    }

    @Test
    void shortestPath_clampsMaxDepthTo10() {
        when(graphService.findShortestPath("a", "b", 10))
                .thenReturn(List.of());

        tool.shortestPath(new GraphTraversalTool.ShortestPathInput("a", "b", 20));

        verify(graphService).findShortestPath("a", "b", 10);
    }
}
