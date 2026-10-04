/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
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
        KnowledgeGraphService storage = boundedStore();
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
        KnowledgeGraphService storage = boundedStore();
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
    void aStoreThatAnswersTheWholeNeighborhoodIsAskedOnce() {
        KnowledgeGraphService storage = mock(KnowledgeGraphService.class,
                withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
        BoundedKnowledgeGraphReader boundedStorage = (BoundedKnowledgeGraphReader) storage;
        GraphNode a = entityNode("a");
        GraphNode b = entityNode("b");
        when(boundedStorage.getNeighborhood(7L, List.of("a"), List.of("a"), 1, 10,
                BoundedKnowledgeGraphReader.Direction.BOTH, 100))
                .thenReturn(new BoundedKnowledgeGraphReader.Neighborhood(
                        List.of(a, b), List.of(edge("a-b", a, b, "CALLS")), true));

        UnifiedGraph bounded = new UnifiedGraphBridge(storage).exportNeighborhood(7L, List.of("a"), 1, 10);

        assertEquals(2, bounded.entityCount());
        assertEquals(1, bounded.relationCount());
        assertEquals(true, bounded.meta().get("truncated"));
        verify(boundedStorage, never()).getNodeInScope(anyString(), any());
        verify(boundedStorage, never()).getIncidentEdges(anyString(), any(), any(), anyInt());
    }

    @Test
    void aStoreWithoutBoundedReadsYieldsOnlyTheSeedsMarkedTruncated() {
        when(graphService.getNode("a")).thenReturn(Optional.of(entityNode("a")));
        when(graphService.getNode("b")).thenReturn(Optional.of(
                GraphNode.builder().nodeId("b").nodeType(NodeLevel.ENTITY).factSheetId(8L).build()));

        UnifiedGraph bounded = bridge.exportNeighborhood(7L, List.of("a", "b"), 1, 10);

        assertEquals(1, bounded.entityCount(), "a node from another fact sheet is out of scope");
        assertEquals(0, bounded.relationCount());
        assertEquals(true, bounded.meta().get("truncated"),
                "without bounded adjacency reads the neighborhood is never presented as complete");
        verify(graphService, never()).getEdgesInFactSheet(anyLong());
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
        bridge.artifactContributors = List.of(contributor);

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
        bridge.importTargetValidators = List.of(targetValidator);

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));
        verify(graphService, never()).deleteByFactSheetId(anyLong());

        bridge.importTargetValidators = List.of();
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
        bridge.artifactImporters = List.of(importer);

        assertThrows(IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));
        assertFalse(applied.get());
        verify(graphService, never()).deleteByFactSheetId(anyLong());
    }

    @Test
    void unclaimedManagedArtifactFailsBeforeMutation() {
        for (String artifact : List.of("reasoning/psl-weights.json",
                "reasoning/graph-psl-weights.v1.json", "reasoning/consensus-targets.v1.json",
                "reasoning/fol-psl-program.v1.json")) {
            UnifiedGraph imported = new UnifiedGraph().factSheetId(7L).putArtifactText(artifact, "{}");
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> bridge.importGraph(imported, 7L));
            assertTrue(failure.getMessage().contains("reserved .kgraph artifact"));
            verify(graphService, never()).deleteByFactSheetId(anyLong());
        }
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
        bridge.artifactImporters = List.of(first, second);

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
        bridge.artifactImporters = List.of(importer);
        bridge.eventPublisher = publisher;

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
        bridge.importTargetValidators = List.of((id, graph) -> {
            if (id == 8L) throw new IllegalArgumentException("missing destination");
        });

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
        bridge.importJournal = journal;

        bridge.importGraph(graph, 7L);

        var order = inOrder(journal);
        order.verify(journal).assertAvailable(java.util.Set.of(7L));
        order.verify(journal).start(anyString(), anyList());
        order.verify(journal).markApplying(anyString());
        order.verify(journal).complete(anyString(), eq(UnifiedGraphImportJournal.Phase.COMMITTED));
    }

    @Test
    void boundedNeighborhoodCarriesStoredOpinionsForMaterializedIdsOnly() {
        KnowledgeGraphService storage = boundedStore();
        BoundedKnowledgeGraphReader boundedStorage = (BoundedKnowledgeGraphReader) storage;
        UnifiedGraphBridge boundedBridge = new UnifiedGraphBridge(storage);
        UnifiedGraphAnalysisAssetStore store = UnifiedGraphAnalysisAssetStore.inMemory();
        boundedBridge.analysisAssets = store;
        GraphNode a = entityNode("a");
        GraphNode b = entityNode("b");
        when(boundedStorage.getNodeInScope("a", 7L)).thenReturn(Optional.of(a));
        when(boundedStorage.getNodeInScope("b", 7L)).thenReturn(Optional.of(b));
        when(boundedStorage.getIncidentEdges("a", 7L,
                BoundedKnowledgeGraphReader.Direction.BOTH, 100))
                .thenReturn(new BoundedKnowledgeGraphReader.IncidentEdges(
                        List.of(edge("a-b", a, b, "CALLS")), false));
        Opinion relationOpinion = Opinion.fromSoftTruth(0.2);
        Opinion entityOpinion = Opinion.fromSoftTruth(0.9);
        store.put(7L, new UnifiedGraph()
                .putRelationOpinion("a-b", relationOpinion)
                .putRelationOpinion("c-d", Opinion.fromSoftTruth(0.7))
                .putEntityOpinion("a", entityOpinion)
                .meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, true));

        UnifiedGraph bounded = boundedBridge.exportNeighborhood(7L, List.of("a"), 1, 10);

        assertEquals(Map.of("a-b", relationOpinion), bounded.relationOpinions(),
                "opinions for relations outside the neighborhood stay in the store");
        assertEquals(entityOpinion, bounded.entityOpinion("a"));
        assertNull(bounded.entityOpinion("b"));
        assertEquals(true, bounded.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META));
        verify(storage, never()).getEdgesInFactSheet(anyLong());
    }

    @Test
    void publishOverlaysLearnedOpinionsOnACopyKeyedLikeTheExport() {
        UnifiedGraphAnalysisAssetStore store = UnifiedGraphAnalysisAssetStore.inMemory();
        GraphToFactStoreProjector projector = mock(GraphToFactStoreProjector.class);
        bridge.analysisAssets = store;
        bridge.factStoreProjector = projector;
        GraphNode a = entityNode("a");
        GraphNode b = entityNode("b");
        GraphEdge learned = edge("a-b", a, b, "CALLS");
        GraphEdge unlearned = edge("b-a", b, a, "USES");
        GraphEdge withoutId = edge(null, a, b, "OWNS");
        when(graphService.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY)).thenReturn(List.of(a, b));
        when(graphService.getEdgesInFactSheet(7L)).thenReturn(List.of(learned, unlearned, withoutId));
        when(projector.learnedPosterior(eq(7L), same(learned))).thenReturn(OptionalDouble.of(0.25));
        when(projector.learnedPosterior(eq(7L), same(unlearned))).thenReturn(OptionalDouble.empty());
        when(projector.learnedPosterior(eq(7L), same(withoutId))).thenReturn(OptionalDouble.of(1.7));
        double[] embedding = {0.1, 0.2, 0.3};
        Opinion carried = Opinion.fromSoftTruth(0.8);
        UnifiedGraph previous = new UnifiedGraph()
                .addEntity(GraphEntity.builder("a").type("ENTITY").label("A").embedding(embedding).build())
                .addEntity(GraphEntity.builder("b").type("ENTITY").label("B").build())
                .addRelation(GraphRelation.builder("b-a", "b", "a").type("USES").build())
                .putRelationOpinion("b-a", carried)
                .putWeightMap("pslWeights", Map.of("rule", 0.5))
                .meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, true);
        store.put(7L, previous);

        assertEquals(2, bridge.publishLearnedRelationOpinions(7L));

        UnifiedGraph stored = store.get(7L).orElseThrow();
        assertNotSame(previous, stored, "readers hold the stored graph without locks, so it is replaced, not mutated");
        assertEquals(Map.of("b-a", carried), previous.relationOpinions());
        assertEquals(true, previous.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META));
        assertEquals(Opinion.fromSoftTruth(0.25), stored.relationOpinion("a-b"));
        assertEquals(carried, stored.relationOpinion("b-a"), "an edge without a posterior keeps its opinion");
        assertEquals(false, stored.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META));
        assertEquals(Map.of("rule", 0.5), stored.weightMap("pslWeights"));

        UnifiedGraph exported = bridge.export(7L);
        assertArrayEquals(embedding, exported.entity("a").orElseThrow().embedding(),
                "the full export still falls back to the stored embedding");
        assertEquals(Opinion.fromSoftTruth(0.25), exported.relationOpinion("a-b"));
        GraphRelation owns = exported.relations().stream()
                .filter(relation -> "OWNS".equals(relation.type())).findFirst().orElseThrow();
        assertEquals(Opinion.fromSoftTruth(1.0), exported.relationOpinion(owns.id()),
                "an edge without a stored id is keyed by the export's stable id, posterior clamped to [0,1]");
    }

    @Test
    void publishStartsAScopedGraphAndNeverReplacesTheStoreWhenNothingWasLearned() {
        UnifiedGraphAnalysisAssetStore store = UnifiedGraphAnalysisAssetStore.inMemory();
        GraphToFactStoreProjector projector = mock(GraphToFactStoreProjector.class);
        bridge.analysisAssets = store;
        bridge.factStoreProjector = projector;
        GraphEdge learned = edge("a-b", entityNode("a"), entityNode("b"), "CALLS");
        when(graphService.getEdgesInFactSheet(7L)).thenReturn(List.of(learned));
        when(projector.learnedPosterior(eq(7L), same(learned)))
                .thenReturn(OptionalDouble.empty(), OptionalDouble.of(0.6), OptionalDouble.empty());

        assertEquals(0, bridge.publishLearnedRelationOpinions(7L));
        assertTrue(store.get(7L).isEmpty());

        assertEquals(1, bridge.publishLearnedRelationOpinions(7L));
        UnifiedGraph stored = store.get(7L).orElseThrow();
        assertEquals("factsheet_7", stored.graphId());
        assertEquals(Long.valueOf(7L), stored.factSheetId());
        assertEquals(Opinion.fromSoftTruth(0.6), stored.relationOpinion("a-b"));
        assertEquals(false, stored.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META));

        assertEquals(0, bridge.publishLearnedRelationOpinions(7L));
        assertSame(stored, store.get(7L).orElseThrow(), "a derivation with no posteriors leaves the store as it was");
    }

    @Test
    void opinionsTheCrawlProcessPublishesReachAnotherProcessOnTheNextRead(@TempDir Path assets) {
        GraphToFactStoreProjector projector = mock(GraphToFactStoreProjector.class);
        bridge.analysisAssets = new UnifiedGraphAnalysisAssetStore(assets);
        bridge.factStoreProjector = projector;
        GraphNode a = entityNode("a");
        GraphNode b = entityNode("b");
        GraphEdge learned = edge("a-b", a, b, "CALLS");
        when(graphService.getEdgesInFactSheet(7L)).thenReturn(List.of(learned));
        when(projector.learnedPosterior(eq(7L), same(learned)))
                .thenReturn(OptionalDouble.of(0.25), OptionalDouble.of(0.9));

        KnowledgeGraphService chatStorage = boundedStore();
        BoundedKnowledgeGraphReader boundedChatStorage = (BoundedKnowledgeGraphReader) chatStorage;
        UnifiedGraphBridge chatBridge = new UnifiedGraphBridge(chatStorage);
        chatBridge.analysisAssets = new UnifiedGraphAnalysisAssetStore(assets);
        when(boundedChatStorage.getNodeInScope("a", 7L)).thenReturn(Optional.of(a));
        when(boundedChatStorage.getNodeInScope("b", 7L)).thenReturn(Optional.of(b));
        when(boundedChatStorage.getIncidentEdges("a", 7L,
                BoundedKnowledgeGraphReader.Direction.BOTH, 100))
                .thenReturn(new BoundedKnowledgeGraphReader.IncidentEdges(List.of(learned), false));
        assertTrue(chatBridge.exportNeighborhood(7L, List.of("a"), 1, 10).relationOpinions().isEmpty());

        assertEquals(1, bridge.publishLearnedRelationOpinions(7L));
        UnifiedGraph afterFirst = chatBridge.exportNeighborhood(7L, List.of("a"), 1, 10);
        assertEquals(Opinion.fromSoftTruth(0.25), afterFirst.relationOpinion("a-b"));
        assertEquals(false, afterFirst.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META));

        assertEquals(1, bridge.publishLearnedRelationOpinions(7L));
        assertEquals(Opinion.fromSoftTruth(0.9),
                chatBridge.exportNeighborhood(7L, List.of("a"), 1, 10).relationOpinion("a-b"),
                "a republish replaces what the other process already loaded");
    }

    @Test
    void exactSeedIdInScopeIsKeptWithoutSearch() {
        KnowledgeGraphService storage = mock(KnowledgeGraphService.class,
                withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
        BoundedKnowledgeGraphReader boundedStorage = (BoundedKnowledgeGraphReader) storage;
        when(boundedStorage.getNodeInScope("person-1", 7L)).thenReturn(Optional.of(entityNode("person-1")));

        assertEquals(List.of("person-1"), new UnifiedGraphBridge(storage).resolveSeedIds(7L, "person-1"));
        verify(storage, never()).searchNodesInFactSheet(any(), any(), anyInt());
        verify(storage, never()).searchNodes(any(), any(), anyInt());
    }

    @Test
    void seedNameExpandsToTheFactSheetSearchHits() {
        when(graphService.searchNodesInFactSheet(7L, "Jordan Lee", 5))
                .thenReturn(List.of(entityNode("person-1"), entityNode("org-2")));

        assertEquals(List.of("person-1", "org-2"), bridge.resolveSeedIds(7L, "Jordan Lee"));
        verify(graphService).getNode("Jordan Lee");
        verify(graphService, never()).searchNodes(any(), any(), anyInt());
    }

    @Test
    void seedResolutionHonoursTheCandidateLimit() {
        when(graphService.searchNodesInFactSheet(7L, "Jordan", 5)).thenReturn(List.of(
                entityNode("n1"), entityNode("n1"), entityNode("n2"), entityNode("n3"),
                entityNode("n4"), entityNode("n5"), entityNode("n6")));

        assertEquals(List.of("n1", "n2", "n3", "n4", "n5"), bridge.resolveSeedIds(7L, "Jordan"),
                "a store that over-returns is still capped, and duplicates take no slot");
    }

    @Test
    void blankSeedInputResolvesToNothing() {
        assertEquals(List.of(), bridge.resolveSeedIds(7L, null));
        assertEquals(List.of(), bridge.resolveSeedIds(7L, "   "));
        verifyNoInteractions(graphService);
    }

    @Test
    void seedNameWithoutFactSheetSearchesTheGlobalScope() {
        when(graphService.searchNodes("Jordan Lee", null, 5)).thenReturn(List.of(entityNode("person-1")));

        assertEquals(List.of("person-1"), bridge.resolveSeedIds(null, "Jordan Lee"));
        verify(graphService, never()).searchNodesInFactSheet(any(), any(), anyInt());
    }

    @Test
    void exportArtifactsReadsOnlyTheRequestedArtifactsAndRunsOnlyTheirOwners() {
        UnifiedGraphAnalysisAssetStore store = UnifiedGraphAnalysisAssetStore.inMemory();
        bridge.analysisAssets = store;
        store.put(7L, new UnifiedGraph()
                .putArtifactText("reasoning/traces.json", "stored traces")
                .putArtifactText("process/reasoning-traces/v1/a.json", "stored process trace")
                .putArtifactText("schema/other.json", "stored schema"));
        List<Long> ownerCalls = new ArrayList<>();
        ArtifactOwner owner = new ArtifactOwner(Set.of("reasoning/traces.json"), (factSheetId, graph) -> {
            ownerCalls.add(factSheetId);
            graph.putArtifactText("reasoning/traces.json", "live traces");
            graph.putArtifactText("reasoning/unrequested.json", "unrequested");
        });
        ArtifactOwner unrelated = new ArtifactOwner(Set.of("schema/"), (factSheetId, graph) -> {
            throw new AssertionError("an owner of no requested artifact must not run");
        });
        UnifiedGraphArtifactContributor undeclared = (factSheetId, graph) -> {
            throw new AssertionError("a contributor that declares no artifacts must not run");
        };
        bridge.artifactContributors = List.of(owner, unrelated, undeclared);

        Map<String, byte[]> artifacts = bridge.exportArtifacts(7L,
                List.of("reasoning/traces.json", "process/reasoning-traces/"));

        assertEquals(List.of("process/reasoning-traces/v1/a.json", "reasoning/traces.json"),
                List.copyOf(artifacts.keySet()), "only the requested names, in name order");
        assertEquals("live traces", text(artifacts.get("reasoning/traces.json")),
                "what the owner contributes overrides the stored copy");
        assertEquals("stored process trace", text(artifacts.get("process/reasoning-traces/v1/a.json")));
        assertEquals(List.of(7L), ownerCalls);
        assertTrue(bridge.exportArtifacts(7L, List.of()).isEmpty());
        assertEquals(List.of(7L), ownerCalls, "nothing requested runs no contributor");
        verifyNoInteractions(graphService);
    }

    @Test
    void exportArtifactsSurfacesAFailingOwner() {
        ArtifactOwner owner = new ArtifactOwner(Set.of("reasoning/"), (factSheetId, graph) -> {
            throw new IllegalArgumentException("broken trace store");
        });
        bridge.artifactContributors = List.of(owner);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.exportArtifacts(7L, List.of("reasoning/traces.json")));

        assertTrue(failure.getMessage().contains(ArtifactOwner.class.getName()));
        assertEquals("broken trace store", failure.getCause().getMessage());
    }

    private static String text(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    /** A contributor that declares, as an importer, the artifacts it owns. */
    private record ArtifactOwner(Set<String> managedArtifactPrefixes, UnifiedGraphArtifactContributor body)
            implements UnifiedGraphArtifactContributor, UnifiedGraphArtifactImporter {

        @Override
        public void contribute(Long factSheetId, UnifiedGraph graph) {
            body.contribute(factSheetId, graph);
        }

        @Override
        public int importArtifacts(Long factSheetId, UnifiedGraph graph) {
            return 0;
        }
    }

    /** A bounded-store mock whose traversal is the interface default, so the point and incident stubs drive it. */
    private static KnowledgeGraphService boundedStore() {
        KnowledgeGraphService storage = mock(KnowledgeGraphService.class,
                withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
        lenient().when(((BoundedKnowledgeGraphReader) storage).getNeighborhood(
                        any(), any(), any(), anyInt(), anyInt(), any(), anyInt()))
                .thenCallRealMethod();
        return storage;
    }

    private static GraphNode entityNode(String id) {
        return GraphNode.builder().nodeId(id).externalId(id)
                .nodeType(NodeLevel.ENTITY).title(id.toUpperCase()).factSheetId(7L).build();
    }

    private static GraphEdge edge(String id, GraphNode source, GraphNode target, String relationType) {
        return GraphEdge.builder().edgeId(id).sourceNode(source).targetNode(target)
                .sourceNodeId(source.getNodeId()).targetNodeId(target.getNodeId())
                .edgeType(EdgeType.USER_DEFINED).relationType(relationType).weight(1.0).build();
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
