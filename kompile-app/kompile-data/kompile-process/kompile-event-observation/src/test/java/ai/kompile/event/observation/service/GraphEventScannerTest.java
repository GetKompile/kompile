/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.observation.service;

import ai.kompile.event.observation.config.EventObservationConfigService;
import ai.kompile.event.observation.domain.EventKeys;
import ai.kompile.event.observation.domain.EventSource;
import ai.kompile.event.observation.support.InMemoryObservedEventStore;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphEventScannerTest {

    @TempDir
    Path tmp;

    private InMemoryObservedEventStore store;
    private KnowledgeGraphService graph;
    private GraphEventScanner scanner;

    private GraphNode node(String id, NodeLevel level, int edgeCount) {
        GraphNode n = mock(GraphNode.class);
        lenient().when(n.getNodeId()).thenReturn(id);
        lenient().when(n.getNodeType()).thenReturn(level);
        lenient().when(n.getEdgeCount()).thenReturn(edgeCount);
        return n;
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryObservedEventStore();
        EventObservationConfigService config = new EventObservationConfigService(tmp.toString());
        config.init();
        EventPriorService priorService = new EventPriorService(store, config);
        EventObservationService observationService = new EventObservationService(priorService, config);
        graph = mock(KnowledgeGraphService.class);
        scanner = new GraphEventScanner(graph, observationService, config);
    }

    @Test
    void scanEmitsEntityAndConnectionEvents() {
        GraphNode doc = node("doc1", NodeLevel.DOCUMENT, 2);
        GraphNode e1 = node("e1", NodeLevel.ENTITY, 4);
        GraphNode e2 = node("e2", NodeLevel.ENTITY, 3);

        when(graph.getNodesInFactSheet(7L)).thenReturn(List.of(doc, e1, e2));
        when(graph.getEntityMentionsForNode(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        GraphEdge edge = mock(GraphEdge.class);
        when(edge.getEdgeType()).thenReturn(EdgeType.SHARED_ENTITY);
        when(edge.getSourceNode()).thenReturn(e1);
        when(edge.getTargetNode()).thenReturn(e2);
        when(edge.getWeight()).thenReturn(0.8);
        when(edge.getConfidence()).thenReturn(0.9);

        when(graph.getEdgesForNode("e1")).thenReturn(List.of(edge));
        when(graph.getEdgesForNode("e2")).thenReturn(List.of(edge));
        when(graph.getEdgesForNode("doc1")).thenReturn(List.of());

        ScanResult result = scanner.scan(7L, EventSource.CRAWL, "job-7");

        assertTrue(result.ran());
        assertTrue(result.entitiesObserved() >= 2, "expected entity events for e1 and e2");
        assertTrue(result.connectionsObserved() >= 1, "expected a connection event for the SHARED_ENTITY edge");
        assertTrue(store.findByKey(EventKeys.entity("e1")).isPresent());
        assertTrue(store.findByKey(EventKeys.connection("e1", "SHARED_ENTITY", "e2")).isPresent());
    }
}
