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

import ai.kompile.graph.algorithms.ShortestPathAlgorithm;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphAlgorithmsToolTest {

    @Mock private GraphAlgorithmService algorithmService;
    @Mock private KnowledgeGraphService graphService;
    @Mock private UnifiedGraphBridge unifiedGraphBridge;

    private GraphAlgorithmsTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphAlgorithmsTool(algorithmService, graphService);
    }

    @Test
    void pageRank_returnsRankedNodes() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("n1", 0.9);
        scores.put("n2", 0.5);
        scores.put("n3", 0.1);
        when(algorithmService.pageRank(ArgumentMatchers.<Long>isNull(), eq(0.85), eq(100), eq(1e-6)))
                .thenReturn(scores);
        when(graphService.getNodesByIds(anyList()))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n1", "Top Node", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n2", "Mid Node", NodeLevel.DOCUMENT)
                ));

        var result = tool.pageRank(new GraphAlgorithmsTool.PageRankInput(null, null, null, null));

        assertEquals("pagerank", result.get("algorithm"));
        assertEquals(3, result.get("totalNodes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rankings = (List<Map<String, Object>>) result.get("rankings");
        assertEquals("n1", rankings.get(0).get("nodeId"));
        assertEquals("Top Node", rankings.get(0).get("title"));
    }

    @Test
    void pageRank_withFactSheetUsesUnifiedGraphWhenBridgeIsAvailable() {
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("n1", "ENTITY", "Unified Node")
                .addEntity("n2", "DOCUMENT", "Other Node")
                .addRelation("r1", "n2", "n1", "LINKS_TO", 1.0);
        when(unifiedGraphBridge.export(7L)).thenReturn(unified);
        when(algorithmService.pageRankGraph(same(unified), eq(0.85), eq(100), eq(1e-6)))
                .thenReturn(Map.of("n1", 0.9));

        GraphAlgorithmsTool unifiedTool = new GraphAlgorithmsTool(algorithmService, graphService, unifiedGraphBridge);
        var result = unifiedTool.pageRank(new GraphAlgorithmsTool.PageRankInput(7L, null, null, null));

        assertEquals("unified_graph", result.get("source"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rankings = (List<Map<String, Object>>) result.get("rankings");
        assertEquals("Unified Node", rankings.get(0).get("title"));
        verify(graphService, never()).getNodesByIds(anyList());
    }

    @Test
    void pageRank_respectsTopK() {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            scores.put("n" + i, (double) (30 - i));
        }
        when(algorithmService.pageRank(ArgumentMatchers.<Long>any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(scores);
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.pageRank(new GraphAlgorithmsTool.PageRankInput(null, null, null, 5));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rankings = (List<Map<String, Object>>) result.get("rankings");
        assertEquals(5, rankings.size());
    }

    @Test
    void degreeCentrality_parsesTypeCorrectly() {
        when(algorithmService.degreeCentrality(ArgumentMatchers.<Long>any(), any()))
                .thenReturn(Map.of("n1", 5.0));
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.degreeCentrality(
                new GraphAlgorithmsTool.DegreeCentralityInput(null, "in", 10));
        assertEquals("degree_centrality_in", result.get("algorithm"));

        result = tool.degreeCentrality(
                new GraphAlgorithmsTool.DegreeCentralityInput(null, "out", 10));
        assertEquals("degree_centrality_out", result.get("algorithm"));

        result = tool.degreeCentrality(
                new GraphAlgorithmsTool.DegreeCentralityInput(null, null, 10));
        assertEquals("degree_centrality_total", result.get("algorithm"));
    }

    @Test
    void betweennessCentrality_returnsRankedResult() {
        when(algorithmService.betweennessCentrality(ArgumentMatchers.<Long>isNull(), eq(100), eq(42L)))
                .thenReturn(Map.of("bridge", 0.8));
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.betweennessCentrality(
                new GraphAlgorithmsTool.BetweennessCentralityInput(null, null, null));

        assertEquals("betweenness_centrality", result.get("algorithm"));
        assertEquals(1, result.get("totalNodes"));
    }

    @Test
    void shortestPath_missingNodes_returnsError() {
        var result = tool.shortestPath(
                new GraphAlgorithmsTool.ShortestPathInput(null, "b", null, null));
        assertEquals("Both fromNodeId and toNodeId are required", result.get("error"));

        result = tool.shortestPath(
                new GraphAlgorithmsTool.ShortestPathInput("a", null, null, null));
        assertEquals("Both fromNodeId and toNodeId are required", result.get("error"));
    }

    @Test
    void shortestPath_noPathFound_returnsNotFound() {
        when(algorithmService.shortestPath(ArgumentMatchers.<Long>isNull(), eq("a"), eq("b"), eq(false)))
                .thenReturn(new ShortestPathAlgorithm.PathResult(List.of(), Double.POSITIVE_INFINITY, false));

        var result = tool.shortestPath(
                new GraphAlgorithmsTool.ShortestPathInput("a", "b", null, false));

        assertEquals(false, result.get("found"));
    }

    @Test
    void shortestPath_pathFound_returnsNodes() {
        when(algorithmService.shortestPath(ArgumentMatchers.<Long>isNull(), eq("a"), eq("c"), eq(false)))
                .thenReturn(new ShortestPathAlgorithm.PathResult(List.of("a", "b", "c"), 2.0, true));
        when(graphService.getNodesByIds(List.of("a", "b", "c")))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("a", "Start", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("b", "Middle", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("c", "End", NodeLevel.ENTITY)
                ));

        var result = tool.shortestPath(
                new GraphAlgorithmsTool.ShortestPathInput("a", "c", null, false));

        assertEquals(true, result.get("found"));
        assertEquals("bfs", result.get("algorithm"));
        assertEquals(2.0, result.get("distance"));
        assertEquals(2, result.get("hopCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> path = (List<Map<String, Object>>) result.get("path");
        assertEquals(3, path.size());
        assertEquals("Start", path.get(0).get("title"));
    }

    @Test
    void shortestPath_weighted_usesDijkstra() {
        when(algorithmService.shortestPath(ArgumentMatchers.<Long>isNull(), eq("a"), eq("b"), eq(true)))
                .thenReturn(new ShortestPathAlgorithm.PathResult(List.of("a", "b"), 0.5, true));
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.shortestPath(
                new GraphAlgorithmsTool.ShortestPathInput("a", "b", null, true));

        assertEquals("dijkstra", result.get("algorithm"));
    }

    @Test
    void nodeSimilarity_missingNodes_returnsError() {
        var result = tool.nodeSimilarity(
                new GraphAlgorithmsTool.NodeSimilarityInput(null, "b", null));
        assertEquals("Both nodeIdA and nodeIdB are required", result.get("error"));
    }

    @Test
    void nodeSimilarity_returnsScoreAndInterpretation() {
        when(algorithmService.jaccardSimilarity(ArgumentMatchers.<Long>isNull(), eq("a"), eq("b")))
                .thenReturn(0.75);
        when(graphService.getNodesByIds(List.of("a", "b")))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("a", "Node A", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("b", "Node B", NodeLevel.ENTITY)
                ));

        var result = tool.nodeSimilarity(
                new GraphAlgorithmsTool.NodeSimilarityInput("a", "b", null));

        assertEquals(0.75, result.get("jaccardSimilarity"));
        assertEquals("highly similar neighborhoods", result.get("interpretation"));
        assertEquals("Node A", result.get("titleA"));
        assertEquals("Node B", result.get("titleB"));
    }

    @Test
    void nodeSimilarity_lowScore_returnsLowInterpretation() {
        when(algorithmService.jaccardSimilarity(ArgumentMatchers.<Long>isNull(), eq("a"), eq("b")))
                .thenReturn(0.05);
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.nodeSimilarity(
                new GraphAlgorithmsTool.NodeSimilarityInput("a", "b", null));

        assertEquals("low similarity", result.get("interpretation"));
    }

    @Test
    void nodeSimilarity_moderateScore_returnsModerateInterpretation() {
        when(algorithmService.jaccardSimilarity(ArgumentMatchers.<Long>isNull(), eq("a"), eq("b")))
                .thenReturn(0.35);
        when(graphService.getNodesByIds(anyList())).thenReturn(List.of());

        var result = tool.nodeSimilarity(
                new GraphAlgorithmsTool.NodeSimilarityInput("a", "b", null));

        assertEquals("moderate overlap", result.get("interpretation"));
    }
}
