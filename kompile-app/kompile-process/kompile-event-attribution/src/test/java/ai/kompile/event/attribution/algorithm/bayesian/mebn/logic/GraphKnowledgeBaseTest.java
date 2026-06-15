/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian.mebn.logic;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GraphKnowledgeBase, covering autoPopulate, entity type caching,
 * entity existence, edge queries, metadata extraction, and property sharing.
 */
class GraphKnowledgeBaseTest {

    private KnowledgeGraphService graphService;
    private GraphKnowledgeBase kb;

    @BeforeEach
    void setUp() {
        graphService = mock(KnowledgeGraphService.class);
        kb = new GraphKnowledgeBase(graphService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-POPULATE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void autoPopulate_groupsNodesByType() {
        GraphNode entity1 = GraphNode.builder()
                .nodeId("e1").nodeType(NodeLevel.ENTITY).title("Entity 1").build();
        GraphNode entity2 = GraphNode.builder()
                .nodeId("e2").nodeType(NodeLevel.ENTITY).title("Entity 2").build();
        GraphNode doc = GraphNode.builder()
                .nodeId("d1").nodeType(NodeLevel.DOCUMENT).title("Document 1").build();

        when(graphService.getNode("e1")).thenReturn(Optional.of(entity1));
        when(graphService.getNode("e2")).thenReturn(Optional.of(entity2));
        when(graphService.getNode("d1")).thenReturn(Optional.of(doc));

        kb.autoPopulate(Set.of("e1", "e2", "d1"));

        Set<String> entities = kb.getEntitiesOfType("ENTITY");
        assertEquals(2, entities.size());
        assertTrue(entities.contains("e1"));
        assertTrue(entities.contains("e2"));

        Set<String> documents = kb.getEntitiesOfType("DOCUMENT");
        assertEquals(1, documents.size());
        assertTrue(documents.contains("d1"));
    }

    @Test
    void autoPopulate_mergesWithExistingRegistrations() {
        // Pre-register an entity
        kb.registerEntityType("ENTITY", Set.of("existing"));

        GraphNode entity = GraphNode.builder()
                .nodeId("new1").nodeType(NodeLevel.ENTITY).title("New Entity").build();
        when(graphService.getNode("new1")).thenReturn(Optional.of(entity));

        kb.autoPopulate(Set.of("new1"));

        Set<String> entities = kb.getEntitiesOfType("ENTITY");
        assertEquals(2, entities.size());
        assertTrue(entities.contains("existing"));
        assertTrue(entities.contains("new1"));
    }

    @Test
    void autoPopulate_skipsUnknownNodes() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());

        kb.autoPopulate(Set.of("missing"));

        // Should not create any type entries
        assertTrue(kb.getEntitiesOfType("ENTITY").isEmpty());
        assertTrue(kb.getEntitiesOfType("DOCUMENT").isEmpty());
    }

    @Test
    void autoPopulate_emptySet_doesNothing() {
        kb.autoPopulate(Set.of());
        // No calls should be made
        verify(graphService, never()).getNode(anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY TYPE CACHE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void registerEntityType_overridesPreviousEntries() {
        kb.registerEntityType("ENTITY", Set.of("a", "b"));
        assertEquals(2, kb.getEntitiesOfType("ENTITY").size());

        kb.registerEntityType("ENTITY", Set.of("c"));
        assertEquals(1, kb.getEntitiesOfType("ENTITY").size());
        assertTrue(kb.getEntitiesOfType("ENTITY").contains("c"));
    }

    @Test
    void getEntitiesOfType_unknownType_returnsEmptySet() {
        assertTrue(kb.getEntitiesOfType("NONEXISTENT").isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY EXISTENCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void entityExists_true_whenNodeFound() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).title("Test").build();
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));

        assertTrue(kb.entityExists("n1"));
    }

    @Test
    void entityExists_false_whenNodeNotFound() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());

        assertFalse(kb.entityExists("missing"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void edgeExists_delegatesToGraphService() {
        when(graphService.edgeExists("a", "b")).thenReturn(true);
        assertTrue(kb.edgeExists("a", "b"));

        when(graphService.edgeExists("x", "y")).thenReturn(false);
        assertFalse(kb.edgeExists("x", "y"));
    }

    @Test
    void edgeExistsOfType_matchesEdgeType() {
        GraphNode src = GraphNode.builder().nodeId("s").nodeType(NodeLevel.ENTITY).title("Source").build();
        GraphNode tgt = GraphNode.builder().nodeId("t").nodeType(NodeLevel.ENTITY).title("Target").build();

        GraphEdge edge = GraphEdge.builder()
                .sourceNode(src).targetNode(tgt)
                .edgeType(EdgeType.HIERARCHICAL)
                .weight(0.9).build();

        when(graphService.getEdgesForNode("s")).thenReturn(List.of(edge));

        assertTrue(kb.edgeExistsOfType("s", "t", EdgeType.HIERARCHICAL));
        assertFalse(kb.edgeExistsOfType("s", "t", EdgeType.CITATION));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // METADATA EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getMetadata_extractsFromJson() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).title("Test")
                .metadataJson("{\"category\":\"finance\",\"priority\":\"high\"}")
                .build();
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));

        assertEquals(Optional.of("finance"), kb.getMetadata("n1", "category"));
        assertEquals(Optional.of("high"), kb.getMetadata("n1", "priority"));
        assertEquals(Optional.empty(), kb.getMetadata("n1", "nonexistent"));
    }

    @Test
    void getMetadata_nullJson_returnsEmpty() {
        GraphNode node = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).title("Test")
                .build();
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));

        assertEquals(Optional.empty(), kb.getMetadata("n1", "anything"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROPERTY SHARING
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void shareProperty_true_whenSameValue() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).title("A")
                .metadataJson("{\"dept\":\"engineering\"}").build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).title("B")
                .metadataJson("{\"dept\":\"engineering\"}").build();

        when(graphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("n2")).thenReturn(Optional.of(n2));

        assertTrue(kb.shareProperty("n1", "n2", "dept"));
    }

    @Test
    void shareProperty_false_whenDifferentValues() {
        GraphNode n1 = GraphNode.builder()
                .nodeId("n1").nodeType(NodeLevel.ENTITY).title("A")
                .metadataJson("{\"dept\":\"engineering\"}").build();
        GraphNode n2 = GraphNode.builder()
                .nodeId("n2").nodeType(NodeLevel.ENTITY).title("B")
                .metadataJson("{\"dept\":\"marketing\"}").build();

        when(graphService.getNode("n1")).thenReturn(Optional.of(n1));
        when(graphService.getNode("n2")).thenReturn(Optional.of(n2));

        assertFalse(kb.shareProperty("n1", "n2", "dept"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTED ENTITIES
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getConnectedEntities_returnsBothDirections() {
        GraphNode center = GraphNode.builder().nodeId("c").nodeType(NodeLevel.ENTITY).title("Center").build();
        GraphNode outgoing = GraphNode.builder().nodeId("out").nodeType(NodeLevel.ENTITY).title("Outgoing").build();
        GraphNode incoming = GraphNode.builder().nodeId("in").nodeType(NodeLevel.ENTITY).title("Incoming").build();

        GraphEdge edgeOut = GraphEdge.builder()
                .sourceNode(center).targetNode(outgoing)
                .edgeType(EdgeType.HIERARCHICAL).build();
        GraphEdge edgeIn = GraphEdge.builder()
                .sourceNode(incoming).targetNode(center)
                .edgeType(EdgeType.CONTAINS).build();

        when(graphService.getEdgesForNode("c")).thenReturn(List.of(edgeOut, edgeIn));

        Set<String> connected = kb.getConnectedEntities("c");
        assertEquals(2, connected.size());
        assertTrue(connected.contains("out"));
        assertTrue(connected.contains("in"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH SERVICE ACCESSOR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getGraphService_returnsInjectedInstance() {
        assertSame(graphService, kb.getGraphService());
    }
}
