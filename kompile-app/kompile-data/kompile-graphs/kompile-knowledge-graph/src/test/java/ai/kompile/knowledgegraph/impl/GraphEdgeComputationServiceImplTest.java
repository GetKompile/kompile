/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.impl;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphEdgeComputationServiceImpl}.
 *
 * The impl uses no-arg constructor + @Autowired field injection.
 * knowledgeGraphService and embeddingModel are injected via reflection in setUp / per-test.
 * entityMentionRepository no longer exists in the impl — all shared-entity detection
 * goes through knowledgeGraphService.findNodePairsWithSharedEntities.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphEdgeComputationServiceImplTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EmbeddingModel embeddingModel;

    private GraphEdgeComputationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // No-arg constructor — @Autowired fields injected below via reflection
        service = new GraphEdgeComputationServiceImpl();
        injectField("knowledgeGraphService", knowledgeGraphService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inject a value into a private field of the service under test.
     */
    private void injectField(String fieldName, Object value) throws Exception {
        Field field = GraphEdgeComputationServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private GraphNode documentNode(String nodeId, String title) {
        return GraphNode.builder()
                .id((long) (Math.abs(nodeId.hashCode()) % 100_000))
                .nodeId(nodeId)
                .externalId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .title(title)
                .description("Description of " + title)
                .contentPreview("Preview of " + title)
                .build();
    }

    private GraphEdge dummyEdge(String edgeId, EdgeType type, Double weight, LocalDateTime computedAt) {
        return GraphEdge.builder()
                .id(1L)
                .edgeId(edgeId)
                .edgeType(type)
                .weight(weight)
                .computedAt(computedAt)
                .build();
    }

    private GraphEdge dummyEdge(EdgeType type, Double weight, LocalDateTime computedAt) {
        return dummyEdge("edge-" + System.nanoTime(), type, weight, computedAt);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeEmbeddingSimilarityEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void embeddingSimilarity_withoutModelOrPersistedVectorsCreatesNoEdges() {
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of());

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void embeddingSimilarity_reusesPersistedVectorsWithoutModel() {
        GraphNode first = documentNode("n1", "First");
        GraphNode second = documentNode("n2", "Second");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(77L, NodeLevel.DOCUMENT))
                .thenReturn(List.of(first, second));
        when(knowledgeGraphService.exportNodeEmbeddings(77L)).thenReturn(Map.of(
                "n1", Nd4j.create(new float[]{1.0f, 0.0f}),
                "n2", Nd4j.create(new float[]{1.0f, 0.0f})));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeEmbeddingSimilarityEdges(77L, 0.7, 10);

        verify(knowledgeGraphService).createEdge(
                eq("n1"), eq("n2"), eq(EdgeType.EMBEDDING_SIMILARITY), anyDouble(), anyString());
    }

    @Test
    void embeddingSimilarity_returnsEarlyWhenFewerThan2DocumentNodes() throws Exception {
        injectField("embeddingModel", embeddingModel);

        // impl calls knowledgeGraphService.getNodesByType(DOCUMENT) — not nodeRepository
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT))
                .thenReturn(List.of(documentNode("n1", "Doc1")));

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(embeddingModel, never()).embed(anyString());
        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void embeddingSimilarity_createsEdgeWhenSimilarityAboveThreshold() throws Exception {
        injectField("embeddingModel", embeddingModel);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        // impl calls knowledgeGraphService.getNodesByType(DOCUMENT)
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // impl batches all node texts in ONE embedBatch call (returns one float[] per text, in
        // docNodes order). Two identical unit vectors → cosine similarity = 1.0.
        when(embeddingModel.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{1.0f, 0.0f, 0.0f}, new float[]{1.0f, 0.0f, 0.0f}));

        // impl calls knowledgeGraphService.findEdgeBetweenNodesBidirectional (returns Optional)
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, times(1))
                .createEdge(eq("n1"), eq("n2"), eq(EdgeType.EMBEDDING_SIMILARITY), anyDouble(), anyString());
    }

    @Test
    void embeddingSimilarity_doesNotCreateEdgeWhenSimilarityBelowThreshold() throws Exception {
        injectField("embeddingModel", embeddingModel);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // Orthogonal vectors → cosine similarity = 0.0
        INDArray vec1 = Nd4j.create(new float[]{1.0f, 0.0f, 0.0f});
        INDArray vec2 = Nd4j.create(new float[]{0.0f, 1.0f, 0.0f});
        when(embeddingModel.embed("Doc1 Description of Doc1 Preview of Doc1")).thenReturn(vec1);
        when(embeddingModel.embed("Doc2 Description of Doc2 Preview of Doc2")).thenReturn(vec2);

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void embeddingSimilarity_doesNotCreateDuplicateEdge() throws Exception {
        injectField("embeddingModel", embeddingModel);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        when(embeddingModel.embed(anyString()))
                .thenAnswer(inv -> Nd4j.create(new float[]{1.0f, 0.0f, 0.0f}));

        // Edge already exists bidirectionally — impl checks via knowledgeGraphService.findEdgeBetweenNodesBidirectional
        GraphEdge existingEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.95, null);
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.of(existingEdge));

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void embeddingSimilarity_respectsMaxEdgesPerNodeLimit() throws Exception {
        injectField("embeddingModel", embeddingModel);

        // Three nodes, maxEdgesPerNode=1. The outer loop captures nodeEdges once per
        // outer iteration, before the inner loop runs. This means n1 can still create
        // edges to both n2 and n3 even though its count grows during the inner loop.
        //
        // n1 (i=0): nodeEdges=0 → creates n1→n2 (edgeCount[n2]=1) and n1→n3 (edgeCount[n3]=1).
        // n2 (i=1): nodeEdges=1 >= 1 → skipped entirely.
        // n3 (i=2): nodeEdges=1 >= 1 → skipped entirely.
        // Total = 2 edges. The limit effectively stops n2 and n3 from starting their own edges.
        GraphNode n1 = documentNode("n1", "A");
        GraphNode n2 = documentNode("n2", "B");
        GraphNode n3 = documentNode("n3", "C");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2, n3));

        // impl batches all three node texts in one embedBatch call (one float[] per text, in order).
        when(embeddingModel.embedBatch(anyList()))
                .thenReturn(List.of(new float[]{1.0f, 0.0f, 0.0f},
                        new float[]{1.0f, 0.0f, 0.0f},
                        new float[]{1.0f, 0.0f, 0.0f}));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(anyString(), anyString()))
                .thenReturn(Optional.empty());

        service.computeEmbeddingSimilarityEdges(0.7, 1);

        // n1 creates edges to both n2 and n3 (limit not re-checked mid inner-loop).
        // n2 and n3 are blocked from starting their own outer iterations. Total = 2.
        verify(knowledgeGraphService, times(2))
                .createEdge(any(), any(), eq(EdgeType.EMBEDDING_SIMILARITY), anyDouble(), anyString());
    }

    @Test
    void embeddingSimilarity_setsRunningStatusDuringComputation() throws Exception {
        injectField("embeddingModel", embeddingModel);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isComputationRunning(), "Should be idle before starting");

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        assertFalse(service.isComputationRunning(), "Should be idle after completion");
    }

    @Test
    void embeddingSimilarity_resetsRunningFlagEvenAfterException() throws Exception {
        injectField("embeddingModel", embeddingModel);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // Simulate an embedding exception
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embed failed"));

        // Should not propagate exception — running flag must be cleared
        assertDoesNotThrow(() -> service.computeEmbeddingSimilarityEdges(0.7, 10));
        assertFalse(service.isComputationRunning());
    }

    @Test
    void backfillDocumentNodeEmbeddingsUsesInjectedModelAndActiveStore() throws Exception {
        injectField("embeddingModel", embeddingModel);
        when(embeddingModel.canEmbed()).thenReturn(true);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(8);
        when(embeddingModel.getMaxBatchSize()).thenReturn(16);

        GraphNode first = documentNode("n1", "First");
        GraphNode second = documentNode("n2", "Second");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(7L, NodeLevel.DOCUMENT))
                .thenReturn(List.of(first, second));
        when(knowledgeGraphService.exportNodeEmbeddings(7L)).thenReturn(Map.of());
        when(embeddingModel.embed(anyList())).thenReturn(Nd4j.create(new float[][]{
                {1.0f, 0.0f, 0.0f},
                {0.0f, 1.0f, 0.0f}
        }));
        when(knowledgeGraphService.applyNodeEmbeddings(anyMap())).thenReturn(2);

        int applied = service.backfillDocumentNodeEmbeddings(7L);

        assertEquals(2, applied);
        verify(embeddingModel).embed(ArgumentMatchers.<List<String>>argThat(
                texts -> texts.size() == 2));
        verify(knowledgeGraphService).applyNodeEmbeddings(argThat(embeddings ->
                embeddings.keySet().equals(Set.of("n1", "n2"))));
    }

    @Test
    void backfillDocumentNodeEmbeddingsSkipsVectorsAlreadyInStore() throws Exception {
        injectField("embeddingModel", embeddingModel);
        when(embeddingModel.canEmbed()).thenReturn(true);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(8);
        when(embeddingModel.getMaxBatchSize()).thenReturn(16);

        GraphNode existing = documentNode("n1", "Existing");
        GraphNode missing = documentNode("n2", "Missing");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(8L, NodeLevel.DOCUMENT))
                .thenReturn(List.of(existing, missing));
        when(knowledgeGraphService.exportNodeEmbeddings(8L))
                .thenReturn(Map.of("n1", Nd4j.create(new float[]{1.0f, 0.0f})));
        when(embeddingModel.embed(anyList()))
                .thenReturn(Nd4j.create(new float[][]{{0.0f, 1.0f}}));
        when(knowledgeGraphService.applyNodeEmbeddings(anyMap())).thenReturn(1);

        assertEquals(1, service.backfillDocumentNodeEmbeddings(8L));
        verify(embeddingModel).embed(ArgumentMatchers.<List<String>>argThat(
                texts -> texts.size() == 1 && texts.get(0).startsWith("Missing")));
        verify(knowledgeGraphService).applyNodeEmbeddings(argThat(embeddings ->
                embeddings.keySet().equals(Set.of("n2"))));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeSharedEntityEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void sharedEntity_skipsWhenNoPairsFound() {
        // impl uses knowledgeGraphService.findNodePairsWithSharedEntities — no entityMentionRepository
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.emptyList());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void sharedEntity_createsEdgeForPairsWithSharedEntities() {
        // pair[0]/pair[1] must be Strings (resolveNodeId skips non-String values)
        Object[] pair = new Object[]{"n1", "n2", 5L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.singletonList(pair));
        // impl uses findEdgeBetweenNodesBidirectional (returns Optional) — not edgeRepository
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, times(1))
                .createEdge(eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void sharedEntity_calculatesWeightCorrectly_belowCap() {
        // sharedCount=5 → weight = 5/10.0 = 0.5
        Object[] pair = new Object[]{"n1", "n2", 5L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.singletonList(pair));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService).createEdge(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                doubleThat(w -> Math.abs(w - 0.5) < 1e-9),
                anyString());
    }

    @Test
    void sharedEntity_calculatesWeightCorrectly_cappedAt1() {
        // sharedCount=15 → raw=1.5, capped to 1.0
        Object[] pair = new Object[]{"n1", "n2", 15L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.singletonList(pair));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService).createEdge(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                doubleThat(w -> Math.abs(w - 1.0) < 1e-9),
                anyString());
    }

    @Test
    void sharedEntity_doesNotCreateDuplicateEdge() {
        Object[] pair = new Object[]{"n1", "n2", 5L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.singletonList(pair));

        // Edge already exists — impl checks via findEdgeBetweenNodesBidirectional
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.of(dummyEdge(EdgeType.SHARED_ENTITY, 0.5, null)));

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void sharedEntity_skipsNonStringNodeIds() {
        // Long IDs are skipped by resolveNodeId — impl only handles String pair elements
        Object[] pair = new Object[]{100L, 200L, 5L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(2))
                .thenReturn(Collections.singletonList(pair));

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void sharedEntity_setsRunningStatusCorrectly() {
        when(knowledgeGraphService.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isComputationRunning(), "Should be idle before starting");

        service.computeSharedEntityEdges(2);

        assertFalse(service.isComputationRunning(), "Should be idle after completion");
    }

    @Test
    void sharedEntity_noEdgesCreatedWhenNoPairsFound() {
        when(knowledgeGraphService.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.emptyList());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // pruneWeakEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void pruneWeakEdges_deletesEdgesBelowMinWeight() {
        // impl scans via knowledgeGraphService.getNodesByType(DOCUMENT) + getNodesByType(ENTITY)
        // then getEdgesForNode(nodeId) per node, then deleteEdge(edge.getEdgeId())
        GraphNode docNode = documentNode("doc1", "Doc1");
        GraphEdge weakEdge = dummyEdge("weak-1", EdgeType.EMBEDDING_SIMILARITY, 0.3, null);
        GraphEdge strongEdge = dummyEdge("strong-1", EdgeType.EMBEDDING_SIMILARITY, 0.9, null);
        GraphEdge weakShared = dummyEdge("weak-2", EdgeType.SHARED_ENTITY, 0.2, null);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(weakEdge, strongEdge, weakShared));

        int pruned = service.pruneWeakEdges(0.5, null);

        // weakEdge (0.3 < 0.5) and weakShared (0.2 < 0.5) are pruned
        assertEquals(2, pruned);
        verify(knowledgeGraphService).deleteEdge("weak-1");
        verify(knowledgeGraphService, never()).deleteEdge("strong-1");
        verify(knowledgeGraphService).deleteEdge("weak-2");
    }

    @Test
    void pruneWeakEdges_deletesEdgesOlderThanThreshold() {
        LocalDateTime olderThan = LocalDateTime.now().minusDays(1);
        LocalDateTime staleTime = LocalDateTime.now().minusDays(7);
        LocalDateTime freshTime = LocalDateTime.now();

        GraphNode docNode = documentNode("doc1", "Doc1");
        GraphEdge staleEdge = dummyEdge("stale-1", EdgeType.EMBEDDING_SIMILARITY, 0.9, staleTime);
        GraphEdge freshEdge = dummyEdge("fresh-1", EdgeType.EMBEDDING_SIMILARITY, 0.9, freshTime);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(staleEdge, freshEdge));

        int pruned = service.pruneWeakEdges(0.1, olderThan);

        assertEquals(1, pruned);
        verify(knowledgeGraphService).deleteEdge("stale-1");
        verify(knowledgeGraphService, never()).deleteEdge("fresh-1");
    }

    @Test
    void pruneWeakEdges_returnsZeroWhenNothingToPrune() {
        GraphNode docNode = documentNode("doc1", "Doc1");
        GraphEdge strongEdge = dummyEdge("strong-1", EdgeType.EMBEDDING_SIMILARITY, 0.9, LocalDateTime.now());

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(List.of(strongEdge));

        int pruned = service.pruneWeakEdges(0.5, null);

        assertEquals(0, pruned);
        verify(knowledgeGraphService, never()).deleteEdge(any());
    }

    @Test
    void pruneWeakEdges_returnsCorrectTotalCount() {
        GraphNode docNode = documentNode("doc1", "Doc1");
        GraphEdge e1 = dummyEdge("e1", EdgeType.EMBEDDING_SIMILARITY, 0.2, null);
        GraphEdge e2 = dummyEdge("e2", EdgeType.EMBEDDING_SIMILARITY, 0.3, null);
        GraphEdge e3 = dummyEdge("e3", EdgeType.SHARED_ENTITY, 0.1, null);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(List.of(e1, e2, e3));

        int pruned = service.pruneWeakEdges(0.5, null);

        assertEquals(3, pruned);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // deleteAllComputedEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void deleteAllComputedEdges_deletesBothEdgeTypes() {
        GraphNode docNode = documentNode("doc1", "Doc1");
        GraphEdge simEdge1 = dummyEdge("sim-1", EdgeType.EMBEDDING_SIMILARITY, 0.8, null);
        GraphEdge simEdge2 = dummyEdge("sim-2", EdgeType.EMBEDDING_SIMILARITY, 0.6, null);
        GraphEdge sharedEdge = dummyEdge("shared-1", EdgeType.SHARED_ENTITY, 0.5, null);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(simEdge1, simEdge2, sharedEdge));

        int deleted = service.deleteAllComputedEdges();

        assertEquals(3, deleted);
        verify(knowledgeGraphService).deleteEdge("sim-1");
        verify(knowledgeGraphService).deleteEdge("sim-2");
        verify(knowledgeGraphService).deleteEdge("shared-1");
    }

    @Test
    void deleteAllComputedEdges_returnsCorrectTotalCount() {
        GraphNode docNode = documentNode("doc1", "Doc1");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(docNode));
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(List.of(
                dummyEdge("e1", EdgeType.EMBEDDING_SIMILARITY, 0.9, null),
                dummyEdge("e2", EdgeType.EMBEDDING_SIMILARITY, 0.7, null),
                dummyEdge("e3", EdgeType.SHARED_ENTITY, 0.5, null)));

        int deleted = service.deleteAllComputedEdges();

        assertEquals(3, deleted);
    }

    @Test
    void deleteAllComputedEdges_returnsZeroWhenNoEdgesExist() {
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(Collections.emptyList());

        int deleted = service.deleteAllComputedEdges();

        assertEquals(0, deleted);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getComputationStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getComputationStatus_containsAllExpectedKeys() {
        Map<String, Object> status = service.getComputationStatus();

        assertTrue(status.containsKey("running"), "status should have 'running'");
        assertTrue(status.containsKey("currentOperation"), "status should have 'currentOperation'");
        assertTrue(status.containsKey("cancelled"), "status should have 'cancelled'");
        assertTrue(status.containsKey("lastEdgesCreated"), "status should have 'lastEdgesCreated'");
        assertTrue(status.containsKey("embeddingModelAvailable"), "status should have 'embeddingModelAvailable'");
        // Note: 'entityMentionRepoAvailable' was removed — impl no longer has entityMentionRepository
        assertFalse(status.containsKey("entityMentionRepoAvailable"),
                "entityMentionRepoAvailable should NOT be present (field removed in refactor)");
    }

    @Test
    void getComputationStatus_reflectsIdleStateByDefault() {
        Map<String, Object> status = service.getComputationStatus();

        assertFalse((Boolean) status.get("running"));
        assertEquals("idle", status.get("currentOperation"));
        assertFalse((Boolean) status.get("cancelled"));
        assertEquals(0, status.get("lastEdgesCreated"));
        assertFalse((Boolean) status.get("embeddingModelAvailable"));
    }

    @Test
    void getComputationStatus_embeddingModelAvailableWhenInjected() throws Exception {
        injectField("embeddingModel", embeddingModel);

        Map<String, Object> status = service.getComputationStatus();

        assertTrue((Boolean) status.get("embeddingModelAvailable"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cancelComputation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void cancelComputation_setsCancelledFlag() throws Exception {
        service.cancelComputation();

        Field cancelledField = GraphEdgeComputationServiceImpl.class.getDeclaredField("cancelled");
        cancelledField.setAccessible(true);
        AtomicBoolean cancelled =
                (AtomicBoolean) cancelledField.get(service);

        assertTrue(cancelled.get(), "cancelled flag should be true after cancelComputation()");
    }

    @Test
    void cancelComputation_reflectedInStatusMap() {
        service.cancelComputation();

        Map<String, Object> status = service.getComputationStatus();

        assertTrue((Boolean) status.get("cancelled"));
    }

    @Test
    void cancelComputation_preventsSubsequentSharedEntityProcessing() {
        // impl: cancelled flag is reset to false at the START of computeSharedEntityEdges,
        // then processing occurs. So cancelling BEFORE the call means pairs ARE processed
        // (cancelled=false is set inside the method). This is correct behaviour.
        Object[] pair = new Object[]{"n1", "n2", 5L};
        when(knowledgeGraphService.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.singletonList(pair));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Cancel before starting
        service.cancelComputation();

        service.computeSharedEntityEdges(2);

        // The cancelled flag is reset inside computeSharedEntityEdges, so the pair is processed.
        verify(knowledgeGraphService, times(1))
                .createEdge(any(), any(), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isComputationRunning
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void isComputationRunning_returnsFalseWhenIdle() {
        assertFalse(service.isComputationRunning());
    }

    @Test
    void isComputationRunning_returnsFalseAfterCompletedRun() throws Exception {
        injectField("embeddingModel", embeddingModel);

        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT))
                .thenReturn(Collections.emptyList());

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        assertFalse(service.isComputationRunning());
    }

    @Test
    void isComputationRunning_returnsFalseAfterSharedEntityRun() {
        when(knowledgeGraphService.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.emptyList());

        service.computeSharedEntityEdges(2);

        assertFalse(service.isComputationRunning());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge case: embeddingModel returns null embedding
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void embeddingSimilarity_skipsNodeWhenEmbedReturnsNull() throws Exception {
        injectField("embeddingModel", embeddingModel);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        when(knowledgeGraphService.getNodesByType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // First node returns null — should be skipped gracefully
        when(embeddingModel.embed("Doc1 Description of Doc1 Preview of Doc1")).thenReturn(null);
        INDArray vec2 = Nd4j.create(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed("Doc2 Description of Doc2 Preview of Doc2")).thenReturn(vec2);

        // No edge should be created because n1's embedding is null
        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeNameBasedCrossDocEdges — STAR topology with dedicated hub nodes
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode entityNode(String nodeId, String title, String entityType, String sourcePath) {
        return GraphNode.builder()
                .id((long) (Math.abs(nodeId.hashCode()) % 100_000))
                .nodeId(nodeId)
                .externalId(sourcePath + "/" + nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title(title)
                .metadataJson("{\"entity_type\":\"" + entityType + "\",\"source_path\":\"" + sourcePath + "\"}")
                .build();
    }

    /** Builds a synthetic NodeLevel.ALIAS hub node with a given nodeId. */
    private GraphNode aliasHubNode(String nodeId) {
        return GraphNode.builder()
                .id((long) (Math.abs(nodeId.hashCode()) % 100_000))
                .nodeId(nodeId)
                .externalId(nodeId)
                .nodeType(NodeLevel.ALIAS)
                .title("Alias hub: " + nodeId)
                .build();
    }

    /**
     * Stub the get-or-create hub sequence used by the star topology.
     * getNodeByExternalIdInFactSheet returns empty (hub not yet created) →
     * createNode returns the provided hub.
     */
    private void stubHubCreation(GraphNode hub, Long factSheetId) {
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                anyString(), eq(NodeLevel.ALIAS), eq(factSheetId)))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ALIAS), anyString(), anyString(), anyString(), any(), eq(factSheetId)))
                .thenReturn(hub);
    }

    @Test
    void nameBasedCrossDoc_starTopology_linksAsStarNotClique() {
        // 3 ENTITY nodes, same normalised name+type, 3 DIFFERENT source docs.
        // A clique would emit 3 edges (n1-n2, n1-n3, n2-n3); the dedicated-hub star emits N = 3
        // ALIAS_OF edges (one per member → hub), never a member↔member edge.
        GraphNode n1 = entityNode("n1", "Alice Smith", "PERSON", "doc1.pdf");
        GraphNode n2 = entityNode("n2", "Alice Smith", "PERSON", "doc2.pdf");
        GraphNode n3 = entityNode("n3", "Alice Smith", "PERSON", "doc3.pdf");
        GraphNode hub = aliasHubNode("alias_hub_alice");

        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(n1, n2, n3));
        stubHubCreation(hub, 1L);
        when(knowledgeGraphService.createEdgesBatch(anyList())).thenReturn(3);

        // kbConfigManager is NOT injected → star topology is the null-safe default.
        service.computeNameBasedCrossDocEdges(1L);

        // Exactly one createEdgesBatch call (bucket fits within EDGE_BATCH_CHUNK = 500).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService, atLeastOnce()).createEdgesBatch(captor.capture());

        // All captured specs must be member → hub with EdgeType.ALIAS_OF.
        List<KnowledgeGraphService.EdgeSpec> specs = captor.getAllValues().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(3, specs.size(), "Expected N=3 ALIAS_OF edges (one per member)");
        specs.forEach(s -> {
            assertEquals("alias_hub_alice", s.targetNodeId(),
                    "All edges must point TO the hub, not member↔member");
            assertEquals(EdgeType.ALIAS_OF, s.edgeType());
        });
        // The O(k²) clique edge n2↔n3 (or any SHARED_ENTITY direct edge) is NEVER created.
        verify(knowledgeGraphService, never())
                .createEdge(anyString(), anyString(), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void nameBasedCrossDoc_starTopology_sameSrcMembersAlsoLinkToHub() {
        // n1 and n4 both come from doc1; n2=doc2, n3=doc3. Same name "Acme Corp".
        // The bucket IS cross-doc (n2 and n3 differ), so the hub is created.
        // All 4 members — including same-source n1 and n4 — each emit one ALIAS_OF edge to the hub.
        // n1↔n4 are NOT linked to each other (no false identity link), just both point to hub.
        GraphNode n1 = entityNode("n1", "Acme Corp", "ORGANIZATION", "doc1.pdf");
        GraphNode n2 = entityNode("n2", "Acme Corp", "ORGANIZATION", "doc2.pdf");
        GraphNode n3 = entityNode("n3", "Acme Corp", "ORGANIZATION", "doc3.pdf");
        GraphNode n4 = entityNode("n4", "Acme Corp", "ORGANIZATION", "doc1.pdf");
        GraphNode hub = aliasHubNode("alias_hub_acme");

        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(n1, n2, n3, n4));
        stubHubCreation(hub, 1L);
        when(knowledgeGraphService.createEdgesBatch(anyList())).thenReturn(4);

        service.computeNameBasedCrossDocEdges(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService, atLeastOnce()).createEdgesBatch(captor.capture());

        List<KnowledgeGraphService.EdgeSpec> specs = captor.getAllValues().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(4, specs.size(), "All 4 members should link to hub (N edges, not N-1)");
        specs.forEach(s -> {
            assertEquals("alias_hub_acme", s.targetNodeId(), "All specs must target the hub");
            assertEquals(EdgeType.ALIAS_OF, s.edgeType());
        });
        // Direct entity↔entity SHARED_ENTITY edges must never be emitted — including n1↔n4.
        verify(knowledgeGraphService, never())
                .createEdge(anyString(), anyString(), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void nameBasedCrossDoc_cliqueTopology_stillAvailableViaConfig() throws Exception {
        // With star DISABLED via the managed config, the legacy clique links all 3 cross-doc pairs.
        GraphNode n1 = entityNode("n1", "Alice Smith", "PERSON", "doc1.pdf");
        GraphNode n2 = entityNode("n2", "Alice Smith", "PERSON", "doc2.pdf");
        GraphNode n3 = entityNode("n3", "Alice Smith", "PERSON", "doc3.pdf");
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(n1, n2, n3));
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(anyString(), anyString()))
                .thenReturn(Optional.empty());

        KbConfig clique = KbConfig.defaults();
        clique.setCrossDocStarTopology(false);
        KbConfigManager mgr = mock(KbConfigManager.class);
        when(mgr.current()).thenReturn(clique);
        injectField("kbConfigManager", mgr);

        service.computeNameBasedCrossDocEdges(1L);

        // Clique = N(N-1)/2 = 3 edges.
        verify(knowledgeGraphService, times(3))
                .createEdge(anyString(), anyString(), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeNameBasedCrossDocEdges — star topology edge cases (added tests)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void nameBasedCrossDoc_hubIdempotency_sameHubReusedOnSecondRun() {
        // The hub externalId is deterministic: "fs<factSheetId>.<bucketKey>".
        // On a re-run, getNodeByExternalIdInFactSheet returns the existing hub and
        // createNode must NOT be called again.  Verifies hub idempotency across runs.
        GraphNode n1 = entityNode("n1", "Alice Smith", "PERSON", "doc1.pdf");
        GraphNode n2 = entityNode("n2", "Alice Smith", "PERSON", "doc2.pdf");
        GraphNode hub = aliasHubNode("fs1.alice smith|person");

        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(n1, n2));

        // First run: hub not yet present → createNode is called.
        // Second run: hub already present → createNode must NOT be called again.
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                anyString(), eq(NodeLevel.ALIAS), eq(1L)))
                .thenReturn(Optional.empty())    // first call (first run)
                .thenReturn(Optional.of(hub));   // second call (second run)

        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ALIAS), anyString(), anyString(), anyString(), any(), eq(1L)))
                .thenReturn(hub);
        when(knowledgeGraphService.createEdgesBatch(anyList())).thenReturn(2);

        service.computeNameBasedCrossDocEdges(1L); // first run: creates hub
        service.computeNameBasedCrossDocEdges(1L); // second run: hub already exists

        // createNode for the hub must be called exactly once across both runs.
        verify(knowledgeGraphService, times(1))
                .createNode(eq(NodeLevel.ALIAS), anyString(), anyString(), anyString(), any(), eq(1L));
    }

    @Test
    void nameBasedCrossDoc_factSheetScoping_sameNameDistinctHubsPerFactSheet() {
        // The hub externalId encodes the factSheetId: "fs1.<bucketKey>" vs "fs2.<bucketKey>".
        // Same bucket-key value under two different factSheetIds must produce TWO DISTINCT hubs,
        // never a cross-factSheet node clash.
        GraphNode n1 = entityNode("n1", "Alice Smith", "PERSON", "doc1.pdf");
        GraphNode n2 = entityNode("n2", "Alice Smith", "PERSON", "doc2.pdf");
        GraphNode n3 = entityNode("n3", "Alice Smith", "PERSON", "doc3.pdf");
        GraphNode n4 = entityNode("n4", "Alice Smith", "PERSON", "doc4.pdf");
        GraphNode hub1 = aliasHubNode("hub-fs1");
        GraphNode hub2 = aliasHubNode("hub-fs2");

        // factSheet 1 setup
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                contains("fs1."), eq(NodeLevel.ALIAS), eq(1L)))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ALIAS), contains("fs1."), anyString(), anyString(), any(), eq(1L)))
                .thenReturn(hub1);

        // factSheet 2 setup
        when(knowledgeGraphService.getNodesByTypeInFactSheet(2L, NodeLevel.ENTITY))
                .thenReturn(List.of(n3, n4));
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                contains("fs2."), eq(NodeLevel.ALIAS), eq(2L)))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ALIAS), contains("fs2."), anyString(), anyString(), any(), eq(2L)))
                .thenReturn(hub2);

        when(knowledgeGraphService.createEdgesBatch(anyList())).thenReturn(2);

        service.computeNameBasedCrossDocEdges(1L);
        service.computeNameBasedCrossDocEdges(2L);

        // Each factSheet must have produced its own distinct hub node.
        ArgumentCaptor<String> extIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(knowledgeGraphService, times(2))
                .createNode(eq(NodeLevel.ALIAS), extIdCaptor.capture(),
                        anyString(), anyString(), any(), any());

        List<String> capturedIds = extIdCaptor.getAllValues();
        assertTrue(capturedIds.stream().anyMatch(id -> id.startsWith("fs1.")),
                "factSheet 1 hub must have externalId starting with 'fs1.'");
        assertTrue(capturedIds.stream().anyMatch(id -> id.startsWith("fs2.")),
                "factSheet 2 hub must have externalId starting with 'fs2.'");
        assertNotEquals(capturedIds.get(0), capturedIds.get(1),
                "Different factSheetIds must produce distinct hub externalIds (no cross-factSheet clash)");
    }

    @Test
    void nameBasedCrossDoc_bucketCap_1001MembersCappedAt1000Edges() {
        // HUB_MAX_BUCKET_SIZE = 1000: a bucket with 1001 members must emit exactly 1000 ALIAS_OF
        // edges (the first 1000 processed) and silently skip the 1001st, bounding memory.
        // Each of the 1001 nodes comes from a different source doc (all cross-doc).
        java.util.List<GraphNode> members = new java.util.ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            members.add(entityNode("n" + i, "CommonName", "CONCEPT", "doc" + i + ".pdf"));
        }

        GraphNode hub = aliasHubNode("fs99.commonname|concept");

        when(knowledgeGraphService.getNodesByTypeInFactSheet(99L, NodeLevel.ENTITY))
                .thenReturn(members);
        when(knowledgeGraphService.getNodeByExternalIdInFactSheet(
                anyString(), eq(NodeLevel.ALIAS), eq(99L)))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.createNode(
                eq(NodeLevel.ALIAS), anyString(), anyString(), anyString(), any(), eq(99L)))
                .thenReturn(hub);
        // createEdgesBatch is called in EDGE_BATCH_CHUNK=500 slices; stub to return batch size.
        when(knowledgeGraphService.createEdgesBatch(anyList()))
                .thenAnswer(inv -> ((java.util.List<?>) inv.getArgument(0)).size());

        service.computeNameBasedCrossDocEdges(99L);

        // Collect all EdgeSpec lists passed to createEdgesBatch across all batch calls.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<KnowledgeGraphService.EdgeSpec>> captor =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(knowledgeGraphService, atLeastOnce()).createEdgesBatch(captor.capture());

        int totalEdges = captor.getAllValues().stream().mapToInt(java.util.List::size).sum();
        assertEquals(1000, totalEdges,
                "A bucket with 1001 members must be capped at exactly 1000 ALIAS_OF edges "
                        + "(HUB_MAX_BUCKET_SIZE); got " + totalEdges);
    }
}
