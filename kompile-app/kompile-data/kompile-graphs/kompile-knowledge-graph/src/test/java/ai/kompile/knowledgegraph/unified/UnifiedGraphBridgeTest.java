/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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
        lenient().when(graphService.getNodesByTypeInFactSheet(anyLong(), any(NodeLevel.class)))
                .thenReturn(List.of());
        lenient().when(graphService.getEdgesInFactSheet(anyLong())).thenReturn(List.of());
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
    void boundedNeighborhoodUsesPointAndAdjacencyReadsWithoutWholeScopeEnumeration() {
        KnowledgeGraphService storage = mock(KnowledgeGraphService.class,
                withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
        BoundedKnowledgeGraphReader boundedStorage = (BoundedKnowledgeGraphReader) storage;
        UnifiedGraphBridge boundedBridge = new UnifiedGraphBridge(storage);
        GraphNode a = GraphNode.builder().nodeId("a").externalId("a")
                .nodeType(NodeLevel.ENTITY).title("A").factSheetId(7L).build();
        GraphNode b = GraphNode.builder().nodeId("b").externalId("b")
                .nodeType(NodeLevel.ENTITY).title("B").factSheetId(7L).build();
        GraphEdge edge = GraphEdge.builder().edgeId("a-b").sourceNode(a).targetNode(b)
                .sourceNodeId("a").targetNodeId("b")
                .edgeType(EdgeType.USER_DEFINED).relationType("CALLS").weight(1.0).build();
        when(boundedStorage.getNodeInScope("a", 7L)).thenReturn(Optional.of(a));
        when(boundedStorage.getNodeInScope("b", 7L)).thenReturn(Optional.of(b));
        when(boundedStorage.getIncidentEdges("a", 7L,
                BoundedKnowledgeGraphReader.Direction.BOTH, 100))
                .thenReturn(new BoundedKnowledgeGraphReader.IncidentEdges(List.of(edge), false));

        UnifiedGraph bounded = boundedBridge.exportNeighborhood(7L, List.of("a"), 1, 10);

        assertEquals(2, bounded.entityCount());
        assertEquals(1, bounded.relationCount());
        assertEquals(true, bounded.meta().get("boundedNeighborhood"));
        verify(storage, never()).getEdgesInFactSheet(anyLong());
        verify(storage, never()).getNodesByTypeInFactSheet(anyLong(), any());
    }

    @Test
    void boundedNeighborhoodUsesScopedIncomingReadsAndHardEdgeBudget() {
        KnowledgeGraphService storage = mock(KnowledgeGraphService.class,
                withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
        BoundedKnowledgeGraphReader boundedStorage = (BoundedKnowledgeGraphReader) storage;
        UnifiedGraphBridge boundedBridge = new UnifiedGraphBridge(storage);
        GraphNode a = GraphNode.builder().nodeId("a").externalId("a")
                .nodeType(NodeLevel.ENTITY).title("A").factSheetId(7L).build();
        GraphNode b = GraphNode.builder().nodeId("b").externalId("b")
                .nodeType(NodeLevel.ENTITY).title("B").factSheetId(7L).build();
        GraphEdge incoming = GraphEdge.builder().edgeId("a-b").sourceNode(a).targetNode(b)
                .sourceNodeId("a").targetNodeId("b")
                .edgeType(EdgeType.USER_DEFINED).relationType("CALLS").weight(1.0).build();
        when(boundedStorage.getNodeInScope("b", 7L)).thenReturn(Optional.of(b));
        when(boundedStorage.getNodeInScope("a", 7L)).thenReturn(Optional.of(a));
        when(boundedStorage.getIncidentEdges("b", 7L,
                BoundedKnowledgeGraphReader.Direction.INCOMING, 1))
                .thenReturn(new BoundedKnowledgeGraphReader.IncidentEdges(List.of(incoming), true));

        UnifiedGraph result = boundedBridge.exportNeighborhood(
                7L, List.of("b"), 1, 10, GraphQueryEngine.Direction.INCOMING, 1);

        assertEquals(2, result.entityCount());
        assertEquals(1, result.relationCount());
        assertEquals(true, result.meta().get("truncated"));
        verify(storage, never()).getNode(anyString());
        verify(storage, never()).getEdgesInFactSheet(anyLong());
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
        assertEquals("WORKS_AT", edgeSpec.label());
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
        UnifiedGraphArtifactContributor contributor = mock(UnifiedGraphArtifactContributor.class);
        ReflectionTestUtils.setField(bridge, "artifactContributors", List.of(contributor));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.importGraph(imported, 7L));

        assertTrue(failure.getMessage().contains("edge restore was incomplete"));
        verify(graphService, times(2)).deleteByFactSheetId(7L);
        verify(graphService, times(2)).createNodesBatch(anyList(), eq(7L));
        verify(contributor).contribute(eq(7L), any(UnifiedGraph.class));
    }

    @Test
    void targetAndArtifactPreflightFailuresCannotDeleteLiveGraph() {
        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L)
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());
        UnifiedGraphImportTargetValidator targetValidator = (id, graph) -> {
            throw new IllegalArgumentException("missing destination");
        };
        ReflectionTestUtils.setField(bridge, "importTargetValidators", List.of(targetValidator));

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));
        verify(graphService, never()).deleteByFactSheetId(anyLong());

        ReflectionTestUtils.setField(bridge, "importTargetValidators", List.of());
        AtomicBoolean applied = new AtomicBoolean();
        UnifiedGraphArtifactImporter importer = new UnifiedGraphArtifactImporter() {
            @Override
            public void validateArtifacts(Long factSheetId, UnifiedGraph graph) {
                throw new IllegalArgumentException("bad artifact");
            }

            @Override
            public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
                applied.set(true);
                return 0;
            }
        };
        ReflectionTestUtils.setField(bridge, "artifactImporters", List.of(importer));

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));
        assertFalse(applied.get());
        verify(graphService, never()).deleteByFactSheetId(anyLong());
    }

    @Test
    void unclaimedManagedArtifactFailsBeforeMutation() {
        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L)
                .putArtifactText("reasoning/psl-weights.json", "{}");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));

        assertTrue(failure.getMessage().contains("reserved .kgraph artifact"));
        verify(graphService, never()).deleteByFactSheetId(anyLong());
    }

    @Test
    void deterministicEdgeEncodingFailureOccursBeforeDeletion() {
        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L)
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build())
                .addEntity(GraphEntity.builder("b").type("ENTITY").label("B").build());
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("edgeType", "USER_DEFINED");
        store.put("metadata", Map.of("notSerializable", new Object()));
        imported.addRelation(GraphRelation.builder("e", "a", "b")
                .type("RELATED_TO").weight(1.0)
                .attributes(Map.of("kompile.store", store)).build());

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));

        verify(graphService, never()).deleteByFactSheetId(anyLong());
        verify(graphService, never()).createNodesBatch(anyList(), any());
    }

    @Test
    void unusableCreatedNodeFailsInsteadOfSilentlyDroppingEdges() {
        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L)
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(
                GraphNode.builder().externalId("a").nodeType(NodeLevel.ENTITY).title("A").build()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.importGraph(imported, 7L));

        assertTrue(failure.getMessage().contains("unusable node"));
        verify(graphService, never()).createEdgesBatch(anyList());
    }

    @Test
    void rejectsGlobalReplacementBeforeAnyMutation() {
        UnifiedGraph imported = new UnifiedGraph()
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, null));

        verify(graphService, never()).createNodesBatch(anyList(), any());
        verify(graphService, never()).createEdgesBatch(anyList());
    }

    @Test
    void artifactCommitFailureRollsBackAttemptedTokensInReverseOrder() {
        UnifiedGraph imported = new UnifiedGraph().factSheetId(7L)
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenAnswer(invocation -> {
            List<KnowledgeGraphService.NodeSpec> specs = invocation.getArgument(0);
            return specs.stream().map(spec -> GraphNode.builder()
                    .nodeId("node-" + spec.externalId()).externalId(spec.externalId())
                    .nodeType(spec.nodeType()).title(spec.title()).factSheetId(7L).build()).toList();
        });
        when(graphService.createEdgesBatch(anyList())).thenReturn(0);
        List<String> events = new java.util.ArrayList<>();
        UnifiedGraphArtifactImporter first = preparedImporter("a", events, false);
        UnifiedGraphArtifactImporter second = preparedImporter("b", events, true);
        ReflectionTestUtils.setField(bridge, "artifactImporters", List.of(first, second));

        assertThrows(IllegalStateException.class, () -> bridge.importGraph(imported, 7L));

        assertEquals(List.of("commit-a", "commit-b", "rollback-b", "rollback-a"), events);
    }

    @Test
    void batchFailureCompensatesEarlierScopesAndPublishesNoEvents() {
        UnifiedGraph firstGraph = new UnifiedGraph().factSheetId(7L)
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").build());
        UnifiedGraph secondGraph = new UnifiedGraph().factSheetId(8L)
                .addEntity(GraphEntity.builder("b").type("ENTITY").label("B").build());
        when(graphService.createNodesBatch(anyList(), anyLong())).thenAnswer(invocation -> {
            Long factSheetId = invocation.getArgument(1);
            List<KnowledgeGraphService.NodeSpec> specs = invocation.getArgument(0);
            return specs.stream().map(spec -> GraphNode.builder()
                    .nodeId("node-" + spec.externalId()).externalId(spec.externalId())
                    .nodeType(spec.nodeType()).title(spec.title()).factSheetId(factSheetId).build()).toList();
        });
        when(graphService.createEdgesBatch(anyList())).thenReturn(0);
        List<String> events = new java.util.ArrayList<>();
        UnifiedGraphArtifactImporter importer = new UnifiedGraphArtifactImporter() {
            @Override
            public String participantId() {
                return "batch-test";
            }

            @Override
            public boolean supportsExactRollback() {
                return true;
            }

            @Override
            public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
                throw new AssertionError("direct import should not be called");
            }

            @Override
            public PreparedImport prepareArtifacts(
                    Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
                return new PreparedImport() {
                    @Override
                    public int commit() {
                        events.add("commit-" + factSheetId);
                        if (factSheetId == 8L) throw new IllegalStateException("second scope failed");
                        return 0;
                    }

                    @Override
                    public void rollback() {
                        events.add("rollback-" + factSheetId);
                    }
                };
            }
        };
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ReflectionTestUtils.setField(bridge, "artifactImporters", List.of(importer));
        ReflectionTestUtils.setField(bridge, "eventPublisher", publisher);

        assertThrows(IllegalStateException.class, () -> bridge.importGraphs(List.of(
                new UnifiedGraphBridge.ImportScope(firstGraph, 7L),
                new UnifiedGraphBridge.ImportScope(secondGraph, 8L))));

        assertEquals(List.of("commit-7", "commit-8", "rollback-8", "rollback-7"), events);
        verify(graphService, times(2)).deleteByFactSheetId(7L);
        verify(graphService, times(2)).deleteByFactSheetId(8L);
        verifyNoInteractions(publisher);
    }

    @Test
    void batchPreflightsEveryScopeBeforeMutation() {
        UnifiedGraph first = new UnifiedGraph().factSheetId(7L);
        UnifiedGraph second = new UnifiedGraph().factSheetId(8L);
        ReflectionTestUtils.setField(bridge, "importTargetValidators",
                List.of((UnifiedGraphImportTargetValidator) (id, graph) -> {
                    if (id == 8L) throw new IllegalArgumentException("missing destination");
                }));

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraphs(List.of(
                new UnifiedGraphBridge.ImportScope(first, 7L),
                new UnifiedGraphBridge.ImportScope(second, 8L))));

        verify(graphService, never()).deleteByFactSheetId(anyLong());
    }

    @Test
    void successfulImportAdvancesDurableJournalToCommitted() {
        UnifiedGraph graph = new UnifiedGraph().factSheetId(7L);
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of());
        when(graphService.createEdgesBatch(anyList())).thenReturn(0);
        UnifiedGraphImportJournal journal = mock(UnifiedGraphImportJournal.class);
        ReflectionTestUtils.setField(bridge, "importJournal", journal);

        bridge.importGraph(graph, 7L);

        var order = inOrder(journal);
        order.verify(journal).assertAvailable(java.util.Set.of(7L));
        order.verify(journal).start(anyString(), anyList());
        order.verify(journal).markApplying(anyString());
        order.verify(journal).complete(anyString(), eq(UnifiedGraphImportJournal.Phase.COMMITTED));
    }

    private static UnifiedGraphArtifactImporter preparedImporter(
            String name, List<String> events, boolean failCommit) {
        return new UnifiedGraphArtifactImporter() {
            @Override
            public String participantId() {
                return name;
            }

            @Override
            public boolean supportsExactRollback() {
                return true;
            }

            @Override
            public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
                throw new AssertionError("direct import should not be called");
            }

            @Override
            public PreparedImport prepareArtifacts(
                    Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
                return new PreparedImport() {
                    @Override
                    public int commit() {
                        events.add("commit-" + name);
                        if (failCommit) throw new IllegalStateException("commit failed");
                        return 0;
                    }

                    @Override
                    public void rollback() {
                        events.add("rollback-" + name);
                    }
                };
            }
        };
    }
}
