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

import ai.kompile.event.attribution.domain.AttributionChain;
import ai.kompile.event.attribution.domain.CausalEdgeType;
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
class CausalTraversalTest {

    @Mock
    private KnowledgeGraphService graphService;

    private GraphNode nodeA;
    private GraphNode nodeB;
    private GraphNode nodeC;

    @BeforeEach
    void setUp() {
        nodeA = GraphNode.builder()
                .id(1L).nodeId("node-a").title("Root Cause Event")
                .nodeType(NodeLevel.ENTITY).externalId("ext-a")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        nodeB = GraphNode.builder()
                .id(2L).nodeId("node-b").title("Intermediate Event")
                .nodeType(NodeLevel.ENTITY).externalId("ext-b")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        nodeC = GraphNode.builder()
                .id(3L).nodeId("node-c").title("Target Event")
                .nodeType(NodeLevel.ENTITY).externalId("ext-c")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void traverseBackward_singleHopChain() {
        // A -> C (A causes C)
        GraphEdge edgeAC = GraphEdge.builder()
                .id(1L).edgeId("edge-ac")
                .sourceNode(nodeA).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.8)
                .bidirectional(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(graphService.getNode("node-c")).thenReturn(Optional.of(nodeC));
        when(graphService.getNode("node-a")).thenReturn(Optional.of(nodeA));
        when(graphService.getEdgesForNode("node-c")).thenReturn(List.of(edgeAC));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of(edgeAC));

        CausalTraversal.TraversalResult result = CausalTraversal.traverseBackward(
                graphService, "node-c", 5, 5, 0.01);

        assertFalse(result.chains().isEmpty(), "Should find at least one chain");
        AttributionChain chain = result.chains().get(0);
        assertEquals("node-c", chain.getTargetEventNodeId());
        assertEquals("node-a", chain.getRootCauseNodeId());
        assertEquals(1, chain.getDepth());
        assertTrue(chain.getOverallConfidence() > 0);
    }

    @Test
    void traverseBackward_multiHopChain() {
        // A -> B -> C
        GraphEdge edgeAB = GraphEdge.builder()
                .id(1L).edgeId("edge-ab")
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();
        GraphEdge edgeBC = GraphEdge.builder()
                .id(2L).edgeId("edge-bc")
                .sourceNode(nodeB).targetNode(nodeC)
                .edgeType(EdgeType.CITATION).weight(0.7).confidence(0.8)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();

        when(graphService.getNode("node-c")).thenReturn(Optional.of(nodeC));
        when(graphService.getNode("node-b")).thenReturn(Optional.of(nodeB));
        when(graphService.getNode("node-a")).thenReturn(Optional.of(nodeA));
        when(graphService.getEdgesForNode("node-c")).thenReturn(List.of(edgeBC));
        when(graphService.getEdgesForNode("node-b")).thenReturn(List.of(edgeAB));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of());

        CausalTraversal.TraversalResult result = CausalTraversal.traverseBackward(
                graphService, "node-c", 5, 5, 0.01);

        assertFalse(result.chains().isEmpty());
        AttributionChain chain = result.chains().get(0);
        assertEquals(2, chain.getDepth());
        assertEquals("node-a", chain.getRootCauseNodeId());
        assertEquals("node-c", chain.getTargetEventNodeId());
        // Chain confidence should be product of hop strengths (attenuated)
        assertTrue(chain.getOverallConfidence() > 0);
        assertTrue(chain.getOverallConfidence() < 1.0);
    }

    @Test
    void traverseBackward_emptyForMissingNode() {
        when(graphService.getNode("nonexistent")).thenReturn(Optional.empty());

        CausalTraversal.TraversalResult result = CausalTraversal.traverseBackward(
                graphService, "nonexistent", 5, 5, 0.1);

        assertTrue(result.chains().isEmpty());
    }

    @Test
    void traverseBackward_respectsMaxDepth() {
        // A -> B -> C, but maxDepth=1 should only find B->C
        GraphEdge edgeAB = GraphEdge.builder()
                .id(1L).edgeId("edge-ab")
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();
        GraphEdge edgeBC = GraphEdge.builder()
                .id(2L).edgeId("edge-bc")
                .sourceNode(nodeB).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.9).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();

        when(graphService.getNode("node-c")).thenReturn(Optional.of(nodeC));
        when(graphService.getNode("node-b")).thenReturn(Optional.of(nodeB));
        when(graphService.getEdgesForNode("node-c")).thenReturn(List.of(edgeBC));
        when(graphService.getEdgesForNode("node-b")).thenReturn(List.of(edgeAB));

        CausalTraversal.TraversalResult result = CausalTraversal.traverseBackward(
                graphService, "node-c", 1, 5, 0.01);

        // With maxDepth=1, should emit a chain at B (depth 1), not continue to A
        assertFalse(result.chains().isEmpty());
        for (AttributionChain chain : result.chains()) {
            assertTrue(chain.getDepth() <= 1, "Chain depth should respect maxDepth");
        }
    }

    @Test
    void classifyEdge_detectsCausalKeywords() {
        GraphEdge edge = GraphEdge.builder()
                .edgeType(EdgeType.USER_DEFINED)
                .label("causes")
                .description("X causes Y to fail")
                .build();
        assertEquals(CausalEdgeType.CAUSES, CausalTraversal.classifyEdge(edge));
    }

    @Test
    void classifyEdge_fallsBackToEdgeType() {
        GraphEdge edge = GraphEdge.builder()
                .edgeType(EdgeType.EMBEDDING_SIMILARITY)
                .build();
        assertEquals(CausalEdgeType.CORRELATES_WITH, CausalTraversal.classifyEdge(edge));
    }

    @Test
    void classifyEdge_handlesNullEdgeTypeWithoutThrowing() {
        // An edge with no edgeType and no causal keywords must not blow up the
        // EdgeType switch with a NullPointerException — it should classify as a weak
        // correlation. Guards the "Explain Why?" attribution pipeline against
        // transient/legacy edges that lack a persisted type.
        GraphEdge edge = GraphEdge.builder()
                .edgeId("edge-null-type")
                .build();
        assertNull(edge.getEdgeType(), "precondition: edgeType is null");
        assertDoesNotThrow(() -> CausalTraversal.classifyEdge(edge));
        assertEquals(CausalEdgeType.CORRELATES_WITH, CausalTraversal.classifyEdge(edge));
    }

    @Test
    void traverseBackward_survivesEdgeWithNullEdgeType() {
        // A -> C where the edge has a null edgeType (bad/legacy data). The backward
        // traversal behind the attribution "Explain Why?" endpoint must still produce
        // a chain rather than throwing an opaque 500.
        GraphEdge edgeAC = GraphEdge.builder()
                .id(1L).edgeId("edge-ac-null")
                .sourceNode(nodeA).targetNode(nodeC)
                .edgeType(null).weight(0.9).confidence(0.8)
                .bidirectional(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(graphService.getNode("node-c")).thenReturn(Optional.of(nodeC));
        when(graphService.getNode("node-a")).thenReturn(Optional.of(nodeA));
        when(graphService.getEdgesForNode("node-c")).thenReturn(List.of(edgeAC));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of(edgeAC));

        CausalTraversal.TraversalResult result = assertDoesNotThrow(() ->
                CausalTraversal.traverseBackward(graphService, "node-c", 5, 5, 0.01));

        assertFalse(result.chains().isEmpty(), "Should still find a chain despite null edgeType");
        assertEquals(CausalEdgeType.CORRELATES_WITH,
                result.chains().get(0).getHops().get(0).getCausalType());
    }

    @Test
    void computeHopStrength_directCausationStrongest() {
        GraphEdge edge = GraphEdge.builder()
                .weight(0.9).confidence(0.9).build();

        double causeStrength = CausalTraversal.computeHopStrength(edge, CausalEdgeType.CAUSES);
        double correlationStrength = CausalTraversal.computeHopStrength(edge, CausalEdgeType.CORRELATES_WITH);

        assertTrue(causeStrength > correlationStrength,
                "Direct causation should be stronger than correlation");
    }

    @Test
    void traverseForward_findsDescendants() {
        // A -> B -> C
        GraphEdge edgeAB = GraphEdge.builder()
                .id(1L).edgeId("edge-ab")
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).confidence(0.9)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();
        GraphEdge edgeBC = GraphEdge.builder()
                .id(2L).edgeId("edge-bc")
                .sourceNode(nodeB).targetNode(nodeC)
                .edgeType(EdgeType.TEMPORAL).weight(0.7).confidence(0.8)
                .bidirectional(false).createdAt(LocalDateTime.now())
                .build();

        when(graphService.getNode("node-a")).thenReturn(Optional.of(nodeA));
        when(graphService.getNode("node-b")).thenReturn(Optional.of(nodeB));
        when(graphService.getNode("node-c")).thenReturn(Optional.of(nodeC));
        when(graphService.getEdgesForNode("node-a")).thenReturn(List.of(edgeAB));
        when(graphService.getEdgesForNode("node-b")).thenReturn(List.of(edgeBC));
        when(graphService.getEdgesForNode("node-c")).thenReturn(List.of());

        CausalTraversal.TraversalResult result = CausalTraversal.traverseForward(
                graphService, "node-a", 5, 10, 0.01);

        assertFalse(result.chains().isEmpty());
    }
}
