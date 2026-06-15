/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.service;

import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.llm.AttributionLlmService;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class EventAttributionServiceTest {

    @Mock
    private KnowledgeGraphService graphService;

    @Mock
    private AttributionLlmService llmService;

    private EventAttributionService service;

    private GraphNode rootNode, midNode, targetNode;

    @BeforeEach
    void setUp() {
        service = new EventAttributionService(graphService, llmService);

        LocalDateTime now = LocalDateTime.now();
        rootNode = GraphNode.builder()
                .id(1L).nodeId("root").title("Server Overloaded")
                .nodeType(NodeLevel.ENTITY).externalId("root")
                .createdAt(now.minusHours(2)).updatedAt(now).build();
        midNode = GraphNode.builder()
                .id(2L).nodeId("mid").title("Response Latency Spike")
                .nodeType(NodeLevel.ENTITY).externalId("mid")
                .createdAt(now.minusHours(1)).updatedAt(now).build();
        targetNode = GraphNode.builder()
                .id(3L).nodeId("target").title("User-Facing Outage")
                .nodeType(NodeLevel.ENTITY).externalId("target")
                .createdAt(now).updatedAt(now).build();
    }

    @Test
    void explain_findsChainAndProducesResult() {
        // root -> mid -> target
        GraphEdge edgeRM = GraphEdge.builder()
                .edgeId("e1").sourceNode(rootNode).targetNode(midNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.8)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();
        GraphEdge edgeMT = GraphEdge.builder()
                .edgeId("e2").sourceNode(midNode).targetNode(targetNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.85).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();

        when(graphService.getNode("target")).thenReturn(Optional.of(targetNode));
        when(graphService.getNode("mid")).thenReturn(Optional.of(midNode));
        when(graphService.getNode("root")).thenReturn(Optional.of(rootNode));
        when(graphService.getEdgesForNode("target")).thenReturn(List.of(edgeMT));
        when(graphService.getEdgesForNode("mid")).thenReturn(List.of(edgeRM, edgeMT));
        when(graphService.getEdgesForNode("root")).thenReturn(List.of(edgeRM));

        // No LLM
        when(llmService.isAvailable()).thenReturn(false);

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId("target")
                .maxDepth(5)
                .maxChains(5)
                .minConfidence(0.01)
                .useLlm(false)
                .build();

        AttributionResult result = service.explain(query);

        assertNotNull(result);
        assertEquals("target", result.getTargetNodeId());
        assertEquals("User-Facing Outage", result.getTargetTitle());
        assertFalse(result.getChains().isEmpty(), "Should find at least one chain");
        assertTrue(result.getComputationTimeMs() >= 0);
        assertFalse(result.isLlmUsed());
        assertNotNull(result.getInfluenceScores());
    }

    @Test
    void explain_missingTargetNode_returnsEmptyResult() {
        when(graphService.getNode("nonexistent")).thenReturn(Optional.empty());

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId("nonexistent")
                .build();

        AttributionResult result = service.explain(query);

        assertNotNull(result);
        assertTrue(result.getChains().isEmpty());
    }

    @Test
    void predict_findsForwardPaths() {
        // root -> mid -> target
        GraphEdge edgeRM = GraphEdge.builder()
                .edgeId("e1").sourceNode(rootNode).targetNode(midNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();
        GraphEdge edgeMT = GraphEdge.builder()
                .edgeId("e2").sourceNode(midNode).targetNode(targetNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.7).confidence(0.8)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();

        when(graphService.getNode("root")).thenReturn(Optional.of(rootNode));
        when(graphService.getNode("mid")).thenReturn(Optional.of(midNode));
        when(graphService.getNode("target")).thenReturn(Optional.of(targetNode));
        when(graphService.getEdgesForNode("root")).thenReturn(List.of(edgeRM));
        when(graphService.getEdgesForNode("mid")).thenReturn(List.of(edgeMT));
        when(graphService.getEdgesForNode("target")).thenReturn(List.of());
        when(llmService.isAvailable()).thenReturn(false);

        PredictionQuery query = PredictionQuery.builder()
                .sourceNodeId("root")
                .maxDepth(5)
                .maxPredictions(10)
                .useLlm(false)
                .build();

        PredictionResult result = service.predict(query);

        assertNotNull(result);
        assertEquals("root", result.getSourceNodeId());
        assertFalse(result.getPredictions().isEmpty(), "Should predict downstream events");
        assertFalse(result.isLlmUsed());
    }

    @Test
    void explain_withLlm_generatesNarrative() {
        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1").sourceNode(rootNode).targetNode(targetNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();

        when(graphService.getNode("target")).thenReturn(Optional.of(targetNode));
        when(graphService.getNode("root")).thenReturn(Optional.of(rootNode));
        when(graphService.getEdgesForNode("target")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("root")).thenReturn(List.of(edge));

        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.narrateChain(any())).thenReturn("The server overload caused the outage.");
        when(llmService.rankChainsByPlausibility(any())).thenAnswer(inv -> {
            List<AttributionChain> chains = inv.getArgument(0);
            return chains.stream().map(AttributionChain::getChainId).toList();
        });
        when(llmService.synthesizeExplanation(anyString(), any(), any()))
                .thenReturn("The outage was caused by server overload.");

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId("target")
                .naturalLanguageQuery("Why did the outage happen?")
                .maxDepth(5)
                .maxChains(5)
                .minConfidence(0.01)
                .useLlm(true)
                .build();

        AttributionResult result = service.explain(query);

        assertTrue(result.isLlmUsed());
        assertNotNull(result.getSynthesizedExplanation());
        assertFalse(result.getChains().isEmpty());
        assertNotNull(result.getChains().get(0).getNarrative());
    }

    @Test
    void explain_withCounterfactuals_producesResults() {
        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1").sourceNode(rootNode).targetNode(targetNode)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now()).build();

        when(graphService.getNode("target")).thenReturn(Optional.of(targetNode));
        when(graphService.getNode("root")).thenReturn(Optional.of(rootNode));
        when(graphService.getEdgesForNode("target")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("root")).thenReturn(List.of(edge));
        when(llmService.isAvailable()).thenReturn(false);

        AttributionQuery query = AttributionQuery.builder()
                .targetNodeId("target")
                .maxDepth(5)
                .maxChains(5)
                .minConfidence(0.01)
                .useLlm(false)
                .includeCounterfactuals(true)
                .build();

        AttributionResult result = service.explain(query);

        assertNotNull(result.getCounterfactuals());
        // Should test removing the root cause
        if (!result.getCounterfactuals().isEmpty()) {
            CounterfactualResult cf = result.getCounterfactuals().get(0);
            assertNotNull(cf.getRemovedNodeId());
        }
    }
}
