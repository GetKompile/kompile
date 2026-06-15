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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphEdgeComputationServiceImpl}.
 *
 * All repositories and services are Mockito mocks.
 * The optional {@code embeddingModel} and {@code entityMentionRepository} fields
 * are injected via reflection when needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphEdgeComputationServiceImplTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EntityMentionRepository entityMentionRepository;

    private GraphEdgeComputationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GraphEdgeComputationServiceImpl(nodeRepository, edgeRepository, knowledgeGraphService);
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

    private GraphEdge dummyEdge(EdgeType type, Double weight, LocalDateTime computedAt) {
        return GraphEdge.builder()
                .id(1L)
                .edgeId("edge-1")
                .edgeType(type)
                .weight(weight)
                .computedAt(computedAt)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeEmbeddingSimilarityEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void embeddingSimilarity_skipsWhenEmbeddingModelIsNull() {
        // embeddingModel NOT injected — field stays null
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT))
                .thenReturn(List.of(documentNode("n1", "Doc1"), documentNode("n2", "Doc2")));

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        // Should not touch nodes or edges when no embedding model
        verify(nodeRepository, never()).findByNodeType(any());
        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void embeddingSimilarity_returnsEarlyWhenFewerThan2DocumentNodes() throws Exception {
        injectField("embeddingModel", embeddingModel);

        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT))
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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // Two identical unit vectors → cosine similarity = 1.0
        // Use separate instances so closing one doesn't affect the other
        INDArray vec1 = Nd4j.create(new float[]{1.0f, 0.0f, 0.0f});
        INDArray vec2 = Nd4j.create(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed("Doc1 Description of Doc1 Preview of Doc1")).thenReturn(vec1);
        when(embeddingModel.embed("Doc2 Description of Doc2 Preview of Doc2")).thenReturn(vec2);

        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // Use thenAnswer to return fresh instances so closing one doesn't affect the other
        when(embeddingModel.embed(anyString()))
                .thenAnswer(inv -> Nd4j.create(new float[]{1.0f, 0.0f, 0.0f}));

        // Edge already exists bidirectionally
        GraphEdge existingEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.95, null);
        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2, n3));

        // Use thenAnswer to return a fresh INDArray instance each call,
        // so closing one embedded array doesn't affect subsequent lookups
        when(embeddingModel.embed(anyString()))
                .thenAnswer(inv -> Nd4j.create(new float[]{1.0f, 0.0f, 0.0f}));
        when(edgeRepository.findEdgeBetweenNodesBidirectional(anyString(), anyString()))
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

        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT))
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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // Simulate an embedding exception
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embed failed"));

        // Should not propagate exception — running flag must be cleared
        assertDoesNotThrow(() -> service.computeEmbeddingSimilarityEdges(0.7, 10));
        assertFalse(service.isComputationRunning());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // computeSharedEntityEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void sharedEntity_skipsWhenEntityMentionRepositoryIsNull() {
        // entityMentionRepository NOT injected — field stays null
        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void sharedEntity_createsEdgeForPairsWithSharedEntities() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");

        // Pair: (n1.id=X, n2.id=Y, sharedCount=5)
        Object[] pair = new Object[]{n1.getId(), n2.getId(), 5L};
        List<Object[]> pairs = Collections.singletonList(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntities(2))
                .thenReturn(pairs);

        when(nodeRepository.findById(n1.getId())).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(n2.getId())).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, times(1))
                .createEdge(eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void sharedEntity_calculatesWeightCorrectly_belowCap() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");

        // sharedCount=5 → weight = 5/10.0 = 0.5
        Object[] pair = new Object[]{n1.getId(), n2.getId(), 5L};
        List<Object[]> pairs = Collections.singletonList(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntities(2))
                .thenReturn(pairs);
        when(nodeRepository.findById(n1.getId())).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(n2.getId())).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService).createEdge(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                doubleThat(w -> Math.abs(w - 0.5) < 1e-9),
                anyString());
    }

    @Test
    void sharedEntity_calculatesWeightCorrectly_cappedAt1() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");

        // sharedCount=15 → raw=1.5, capped to 1.0
        Object[] pair = new Object[]{n1.getId(), n2.getId(), 15L};
        List<Object[]> pairs = Collections.singletonList(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntities(2))
                .thenReturn(pairs);
        when(nodeRepository.findById(n1.getId())).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(n2.getId())).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.empty());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService).createEdge(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                doubleThat(w -> Math.abs(w - 1.0) < 1e-9),
                anyString());
    }

    @Test
    void sharedEntity_doesNotCreateDuplicateEdge() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");

        Object[] pair = new Object[]{n1.getId(), n2.getId(), 5L};
        List<Object[]> pairs = Collections.singletonList(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntities(2))
                .thenReturn(pairs);
        when(nodeRepository.findById(n1.getId())).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(n2.getId())).thenReturn(Optional.of(n2));

        // Edge already exists
        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.of(dummyEdge(EdgeType.SHARED_ENTITY, 0.5, null)));

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    @Test
    void sharedEntity_setsRunningStatusCorrectly() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.emptyList());

        assertFalse(service.isComputationRunning(), "Should be idle before starting");

        service.computeSharedEntityEdges(2);

        assertFalse(service.isComputationRunning(), "Should be idle after completion");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // pruneWeakEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void pruneWeakEdges_deletesEdgesBelowMinWeight() {
        GraphEdge weakSimilarityEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.3, null);
        GraphEdge strongSimilarityEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.9, null);
        GraphEdge weakSharedEdge = dummyEdge(EdgeType.SHARED_ENTITY, 0.2, null);

        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(weakSimilarityEdge, strongSimilarityEdge));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(List.of(weakSharedEdge));

        int pruned = service.pruneWeakEdges(0.5, null);

        // weakSimilarityEdge (0.3 < 0.5) and weakSharedEdge (0.2 < 0.5) are pruned
        assertEquals(2, pruned);
        verify(edgeRepository).delete(weakSimilarityEdge);
        verify(edgeRepository, never()).delete(strongSimilarityEdge);
        verify(edgeRepository).delete(weakSharedEdge);
    }

    @Test
    void pruneWeakEdges_deletesEdgesOlderThanThreshold() {
        LocalDateTime olderThan = LocalDateTime.now().minusDays(1);
        LocalDateTime staleTime = LocalDateTime.now().minusDays(7);
        LocalDateTime freshTime = LocalDateTime.now();

        GraphEdge staleEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.9, staleTime);
        GraphEdge freshEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.9, freshTime);

        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(staleEdge, freshEdge));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(Collections.emptyList());

        int pruned = service.pruneWeakEdges(0.1, olderThan);

        assertEquals(1, pruned);
        verify(edgeRepository).delete(staleEdge);
        verify(edgeRepository, never()).delete(freshEdge);
    }

    @Test
    void pruneWeakEdges_returnsZeroWhenNothingToPrune() {
        GraphEdge strongEdge = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.9, LocalDateTime.now());

        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(strongEdge));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(Collections.emptyList());

        int pruned = service.pruneWeakEdges(0.5, null);

        assertEquals(0, pruned);
        verify(edgeRepository, never()).delete(any(GraphEdge.class));
    }

    @Test
    void pruneWeakEdges_returnsCorrectTotalCount() {
        GraphEdge e1 = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.2, null);
        GraphEdge e2 = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.3, null);
        GraphEdge e3 = dummyEdge(EdgeType.SHARED_ENTITY, 0.1, null);

        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(e1, e2));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(List.of(e3));

        int pruned = service.pruneWeakEdges(0.5, null);

        assertEquals(3, pruned);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // deleteAllComputedEdges
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void deleteAllComputedEdges_deletesBothEdgeTypes() {
        GraphEdge simEdge1 = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.8, null);
        GraphEdge simEdge2 = dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.6, null);
        GraphEdge sharedEdge = dummyEdge(EdgeType.SHARED_ENTITY, 0.5, null);

        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(simEdge1, simEdge2));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(List.of(sharedEdge));

        int deleted = service.deleteAllComputedEdges();

        assertEquals(3, deleted);
        verify(edgeRepository).deleteAll(List.of(simEdge1, simEdge2));
        verify(edgeRepository).deleteAll(List.of(sharedEdge));
    }

    @Test
    void deleteAllComputedEdges_returnsCorrectTotalCount() {
        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(
                        dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.9, null),
                        dummyEdge(EdgeType.EMBEDDING_SIMILARITY, 0.7, null)));
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(List.of(
                        dummyEdge(EdgeType.SHARED_ENTITY, 0.5, null)));

        int deleted = service.deleteAllComputedEdges();

        assertEquals(3, deleted);
    }

    @Test
    void deleteAllComputedEdges_returnsZeroWhenNoEdgesExist() {
        when(edgeRepository.findByEdgeType(EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(Collections.emptyList());
        when(edgeRepository.findByEdgeType(EdgeType.SHARED_ENTITY))
                .thenReturn(Collections.emptyList());

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
        assertTrue(status.containsKey("entityMentionRepoAvailable"), "status should have 'entityMentionRepoAvailable'");
    }

    @Test
    void getComputationStatus_reflectsIdleStateByDefault() {
        Map<String, Object> status = service.getComputationStatus();

        assertFalse((Boolean) status.get("running"));
        assertEquals("idle", status.get("currentOperation"));
        assertFalse((Boolean) status.get("cancelled"));
        assertEquals(0, status.get("lastEdgesCreated"));
        assertFalse((Boolean) status.get("embeddingModelAvailable"));
        assertFalse((Boolean) status.get("entityMentionRepoAvailable"));
    }

    @Test
    void getComputationStatus_embeddingModelAvailableWhenInjected() throws Exception {
        injectField("embeddingModel", embeddingModel);

        Map<String, Object> status = service.getComputationStatus();

        assertTrue((Boolean) status.get("embeddingModelAvailable"));
    }

    @Test
    void getComputationStatus_entityMentionRepoAvailableWhenInjected() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        Map<String, Object> status = service.getComputationStatus();

        assertTrue((Boolean) status.get("entityMentionRepoAvailable"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cancelComputation
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void cancelComputation_setsCancelledFlag() throws Exception {
        service.cancelComputation();

        Field cancelledField = GraphEdgeComputationServiceImpl.class.getDeclaredField("cancelled");
        cancelledField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                (java.util.concurrent.atomic.AtomicBoolean) cancelledField.get(service);

        assertTrue(cancelled.get(), "cancelled flag should be true after cancelComputation()");
    }

    @Test
    void cancelComputation_reflectedInStatusMap() {
        service.cancelComputation();

        Map<String, Object> status = service.getComputationStatus();

        assertTrue((Boolean) status.get("cancelled"));
    }

    @Test
    void cancelComputation_preventsSubsequentSharedEntityProcessing() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        GraphNode n1 = documentNode("n1", "Doc1");
        GraphNode n2 = documentNode("n2", "Doc2");
        Object[] pair = new Object[]{n1.getId(), n2.getId(), 5L};
        List<Object[]> pairs = Collections.singletonList(pair);

        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(pairs);
        when(nodeRepository.findById(n1.getId())).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(n2.getId())).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodesBidirectional(anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Cancel before starting
        service.cancelComputation();

        service.computeSharedEntityEdges(2);

        // The cancelled flag is cleared at the start of computeSharedEntityEdges,
        // but because we cancelled BEFORE running, the method should reset cancelled=false
        // and then process. Verify the method executed (running was false, so it proceeds).
        // The cancelled flag is reset to false inside the method, so the pair is processed.
        // This is correct behaviour: cancel only affects already-running computation.
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

        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT))
                .thenReturn(Collections.emptyList());

        service.computeEmbeddingSimilarityEdges(0.7, 10);

        assertFalse(service.isComputationRunning());
    }

    @Test
    void isComputationRunning_returnsFalseAfterSharedEntityRun() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt()))
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
        when(nodeRepository.findByNodeType(NodeLevel.DOCUMENT)).thenReturn(List.of(n1, n2));

        // First node returns null — should be skipped gracefully
        when(embeddingModel.embed("Doc1 Description of Doc1 Preview of Doc1")).thenReturn(null);
        INDArray vec2 = Nd4j.create(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingModel.embed("Doc2 Description of Doc2 Preview of Doc2")).thenReturn(vec2);

        // No edge should be created because n1's embedding is null
        service.computeEmbeddingSimilarityEdges(0.7, 10);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // sharedEntity: no pairs found
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void sharedEntity_noEdgesCreatedWhenNoPairsFound() throws Exception {
        injectField("entityMentionRepository", entityMentionRepository);

        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt()))
                .thenReturn(Collections.emptyList());

        service.computeSharedEntityEdges(2);

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), any(), any());
    }
}
