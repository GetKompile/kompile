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
class TemporalChainExtractorTest {

    @Mock
    private KnowledgeGraphService graphService;

    @Test
    void extractPredecessors_findsPriorEvents() {
        LocalDateTime now = LocalDateTime.now();
        GraphNode target = GraphNode.builder()
                .id(1L).nodeId("target").title("Target Event")
                .nodeType(NodeLevel.ENTITY).externalId("t")
                .createdAt(now).updatedAt(now).build();
        GraphNode predecessor = GraphNode.builder()
                .id(2L).nodeId("pred").title("Prior Event")
                .nodeType(NodeLevel.ENTITY).externalId("p")
                .createdAt(now.minusHours(1)).updatedAt(now.minusHours(1)).build();

        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1").sourceNode(predecessor).targetNode(target)
                .edgeType(EdgeType.TEMPORAL).weight(0.8).bidirectional(false)
                .createdAt(now).build();

        when(graphService.getNode("target")).thenReturn(Optional.of(target));
        when(graphService.getNode("pred")).thenReturn(Optional.of(predecessor));
        when(graphService.getEdgesForNode("target")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("pred")).thenReturn(List.of(edge));

        List<TemporalChainExtractor.TemporalPredecessor> preds =
                TemporalChainExtractor.extractPredecessors(graphService, "target", 10, 3);

        assertFalse(preds.isEmpty(), "Should find the predecessor");
        assertEquals("pred", preds.get(0).nodeId());
        assertTrue(preds.get(0).hasTemporalEdge());
        assertTrue(preds.get(0).score() > 0);
    }

    @Test
    void extractPredecessors_emptyForMissingTarget() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());

        List<TemporalChainExtractor.TemporalPredecessor> preds =
                TemporalChainExtractor.extractPredecessors(graphService, "missing", 10, 3);

        assertTrue(preds.isEmpty());
    }

    @Test
    void extractTimestamp_fromCreatedAt() {
        LocalDateTime time = LocalDateTime.of(2025, 6, 12, 10, 0);
        GraphNode node = GraphNode.builder()
                .nodeId("n").nodeType(NodeLevel.ENTITY).externalId("e").title("T")
                .createdAt(time).updatedAt(time).build();

        assertNotNull(TemporalChainExtractor.extractTimestamp(node));
    }

    @Test
    void extractTimestamp_fromMetadataJson() {
        GraphNode node = GraphNode.builder()
                .nodeId("n").nodeType(NodeLevel.ENTITY).externalId("e").title("T")
                .metadataJson("{\"event_time\":\"2025-06-12T10:00:00Z\"}")
                .updatedAt(LocalDateTime.now()).build();

        // createdAt is null, so it should fall through to metadata
        assertNotNull(TemporalChainExtractor.extractTimestamp(node));
    }

    @Test
    void buildTemporalEvidence_producesEvidence() {
        var preds = List.of(
                new TemporalChainExtractor.TemporalPredecessor(
                        "p1", "Event 1", null, 0.7, 1, true, false),
                new TemporalChainExtractor.TemporalPredecessor(
                        "p2", "Event 2", null, 0.5, 2, false, true)
        );

        var evidence = TemporalChainExtractor.buildTemporalEvidence(preds);

        assertEquals(2, evidence.size());
        assertEquals("p1", evidence.get(0).getSourceNodeId());
        assertEquals(0.7, evidence.get(0).getStrength());
    }
}
