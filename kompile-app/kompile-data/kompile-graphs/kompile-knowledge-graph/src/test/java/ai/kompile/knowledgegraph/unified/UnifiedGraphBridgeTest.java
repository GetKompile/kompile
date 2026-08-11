/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UnifiedGraphBridgeTest {

    @Mock
    private KnowledgeGraphService graphService;

    private UnifiedGraphBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new UnifiedGraphBridge(graphService);
        when(graphService.getNodesByTypeInFactSheet(anyLong(), any(NodeLevel.class)))
                .thenReturn(List.of());
        when(graphService.getEdgesInFactSheet(anyLong())).thenReturn(List.of());
    }

    @Test
    void namedGraphExportKeepsOnlyNodesAndClosedEdgesInThatGraph() {
        GraphNode included = GraphNode.builder()
                .nodeId("n1").externalId("one").nodeType(NodeLevel.ENTITY)
                .title("One").namedGraphId("selected").factSheetId(7L).build();
        GraphNode excluded = GraphNode.builder()
                .nodeId("n2").externalId("two").nodeType(NodeLevel.ENTITY)
                .title("Two").namedGraphId("other").factSheetId(7L).build();
        when(graphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(List.of(included, excluded));
        when(graphService.getEdgesInFactSheet(7L)).thenReturn(List.of(
                GraphEdge.builder().edgeId("e1").sourceNode(included).targetNode(excluded)
                        .edgeType(EdgeType.USER_DEFINED).weight(1.0).build()));

        UnifiedGraph exported = bridge.export(7L, "selected");

        assertEquals(1, exported.entityCount());
        assertTrue(exported.entity("n1").isPresent());
        assertTrue(exported.entity("n2").isEmpty());
        assertEquals(0, exported.relationCount(),
                "named graph exports must not retain edges to excluded endpoints");
    }

    @Test
    @SuppressWarnings("unchecked")
    void importCarriesCompleteVersionedNodeAndEdgeStateToTheStoreAdapter() throws Exception {
        Map<String, Object> nodeState = Map.of(
                "nodeType", "ENTITY",
                "externalId", "alice",
                "contentPreview", "preview",
                "namedGraphId", "people",
                "userPinned", true,
                "metadata", Map.of("visible", "node"));
        Map<String, Object> edgeState = Map.ofEntries(
                Map.entry("edgeId", "edge-original"),
                Map.entry("edgeType", "USER_DEFINED"),
                Map.entry("relationType", "WORKS_AT"),
                Map.entry("confidence", 0.93),
                Map.entry("provenance", "document-1"),
                Map.entry("provenanceType", "EXTRACTED"),
                Map.entry("userPinned", true),
                Map.entry("metadata", Map.of("visible", "edge")));

        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L);
        imported.addEntity(GraphEntity.builder("alice-id").type("PERSON").label("Alice")
                .attributes(Map.of("kompile.store", nodeState)).build());
        imported.addEntity(GraphEntity.builder("acme-id").type("ORG").label("Acme").build());
        imported.addRelation(GraphRelation.builder("rel-1", "alice-id", "acme-id")
                .type("WORKS_AT").weight(0.8)
                .attributes(Map.of("kompile.store", edgeState)).build());

        when(graphService.createNodesBatch(anyList(), eq(7L))).thenAnswer(invocation -> {
            List<KnowledgeGraphService.NodeSpec> specs = invocation.getArgument(0);
            return specs.stream().map(spec -> GraphNode.builder()
                    .nodeId("restored-" + spec.externalId())
                    .externalId(spec.externalId()).nodeType(spec.nodeType())
                    .title(spec.title()).factSheetId(7L).build()).toList();
        });
        when(graphService.createEdgesBatch(anyList())).thenReturn(1);

        UnifiedGraphBridge.ImportSummary summary = bridge.importGraph(imported, 7L);

        assertEquals(2, summary.nodes());
        assertEquals(1, summary.edges());

        ArgumentCaptor<List<KnowledgeGraphService.NodeSpec>> nodes = ArgumentCaptor.forClass(List.class);
        verify(graphService).createNodesBatch(nodes.capture(), eq(7L));
        Map<String, Object> nodeRestore = (Map<String, Object>) nodes.getValue().get(0).metadata()
                .get(KnowledgeGraphService.NODE_RESTORE_STATE_KEY);
        assertEquals("preview", nodeRestore.get("contentPreview"));
        assertEquals("people", nodeRestore.get("namedGraphId"));

        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> edges = ArgumentCaptor.forClass(List.class);
        verify(graphService).createEdgesBatch(edges.capture());
        KnowledgeGraphService.EdgeSpec edgeSpec = edges.getValue().get(0);
        assertEquals(EdgeProvenance.EXTRACTED, edgeSpec.provenance());
        Map<String, Object> payload = new ObjectMapper().readValue(
                edgeSpec.metaJson(), new TypeReference<>() {});
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        Map<String, Object> edgeRestore = (Map<String, Object>) metadata
                .get(KnowledgeGraphService.EDGE_RESTORE_STATE_KEY);
        assertEquals("edge-original", edgeRestore.get("edgeId"));
        assertEquals(true, edgeRestore.get("userPinned"));
    }

    @Test
    void incompleteEdgeRestoreRollsBackThePreviousFactSheetGraph() {
        GraphNode backupNode = GraphNode.builder()
                .nodeId("backup-id").externalId("backup").nodeType(NodeLevel.ENTITY)
                .title("Backup").factSheetId(7L).build();
        when(graphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(List.of(backupNode));

        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L);
        imported.addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());
        imported.addEntity(GraphEntity.builder("b").type("ENTITY").label("B").build());
        imported.addRelation(GraphRelation.builder("e", "a", "b")
                .type("RELATED_TO").weight(1.0).build());

        when(graphService.createNodesBatch(anyList(), eq(7L))).thenAnswer(invocation -> {
            List<KnowledgeGraphService.NodeSpec> specs = invocation.getArgument(0);
            return specs.stream().map(spec -> GraphNode.builder()
                    .nodeId("created-" + spec.externalId())
                    .externalId(spec.externalId()).nodeType(spec.nodeType())
                    .title(spec.title()).factSheetId(7L).build()).toList();
        });
        when(graphService.createEdgesBatch(anyList())).thenReturn(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.importGraph(imported, 7L));

        assertTrue(failure.getMessage().contains("edge restore was incomplete"));
        verify(graphService, times(2)).deleteByFactSheetId(7L);
        verify(graphService, times(2)).createNodesBatch(anyList(), eq(7L));
    }
}
