/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InfluencePropagationTest {

    @Mock
    private KnowledgeGraphService graphService;

    private GraphNode nodeA, nodeB, nodeC;

    @BeforeEach
    void setUp() {
        nodeA = GraphNode.builder()
                .id(1L).nodeId("a").title("A").nodeType(NodeLevel.ENTITY)
                .externalId("a").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        nodeB = GraphNode.builder()
                .id(2L).nodeId("b").title("B").nodeType(NodeLevel.ENTITY)
                .externalId("b").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        nodeC = GraphNode.builder()
                .id(3L).nodeId("c").title("Target").nodeType(NodeLevel.ENTITY)
                .externalId("c").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void computeInfluenceScores_singleAncestor() {
        // A -> C
        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1").sourceNode(nodeA).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).bidirectional(false)
                .createdAt(LocalDateTime.now()).build();

        when(graphService.getEdgesForNode("c")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edge));

        Map<String, Double> scores = InfluencePropagation.computeInfluenceScores(
                graphService, "c", 5, 0.85, 0.001);

        assertTrue(scores.containsKey("a"), "Ancestor should have an influence score");
        assertTrue(scores.get("a") > 0, "Influence score should be positive");
        assertFalse(scores.containsKey("c"), "Target should be excluded from scores");
    }

    @Test
    void computeInfluenceScores_multipleAncestors_higherWeightWins() {
        // A (weight 0.9) -> C, B (weight 0.3) -> C
        GraphEdge edgeAC = GraphEdge.builder()
                .edgeId("e1").sourceNode(nodeA).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).bidirectional(false)
                .createdAt(LocalDateTime.now()).build();
        GraphEdge edgeBC = GraphEdge.builder()
                .edgeId("e2").sourceNode(nodeB).targetNode(nodeC)
                .edgeType(EdgeType.SHARED_ENTITY).weight(0.3).bidirectional(false)
                .createdAt(LocalDateTime.now()).build();

        when(graphService.getEdgesForNode("c")).thenReturn(List.of(edgeAC, edgeBC));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edgeAC));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(edgeBC));

        Map<String, Double> scores = InfluencePropagation.computeInfluenceScores(
                graphService, "c", 5, 0.85, 0.001);

        assertTrue(scores.get("a") > scores.get("b"),
                "Higher-weight ancestor should have higher influence score");
    }

    @Test
    void computeInfluenceScores_noAncestors() {
        when(graphService.getEdgesForNode("c")).thenReturn(List.of());

        Map<String, Double> scores = InfluencePropagation.computeInfluenceScores(
                graphService, "c", 5, 0.85, 0.001);

        assertTrue(scores.isEmpty(), "No ancestors should produce empty scores");
    }

    @Test
    void counterfactualScores_removingNodeChangesScores() {
        // A -> B -> C (chain), remove B
        GraphEdge edgeAB = GraphEdge.builder()
                .edgeId("e1").sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).bidirectional(false)
                .createdAt(LocalDateTime.now()).build();
        GraphEdge edgeBC = GraphEdge.builder()
                .edgeId("e2").sourceNode(nodeB).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).bidirectional(false)
                .createdAt(LocalDateTime.now()).build();

        when(graphService.getEdgesForNode("c")).thenReturn(List.of(edgeBC));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(edgeAB, edgeBC));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edgeAB));

        // Normal scores
        Map<String, Double> normal = InfluencePropagation.computeInfluenceScores(
                graphService, "c", 5, 0.85, 0.001);

        // Counterfactual: remove B
        Map<String, Double> counterfactual = InfluencePropagation.counterfactualScores(
                graphService, "c", "b", 5, 0.85, 0.001);

        // With B removed, A should not be reachable from C (since B is the only path)
        assertFalse(counterfactual.containsKey("b"), "Removed node should be excluded");
        // A might still appear if propagation found it through other paths (it shouldn't here)
        double normalAScore = normal.getOrDefault("a", 0.0);
        double cfAScore = counterfactual.getOrDefault("a", 0.0);
        assertTrue(cfAScore <= normalAScore,
                "Removing intermediate node should reduce or eliminate ancestor's score");
    }
}
