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

package ai.kompile.knowledgegraph.embedding.service;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingModel;
import ai.kompile.core.kgembedding.Triple;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KGEmbeddingStorageService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KGEmbeddingStorageServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KGEmbeddingModel mockModel;

    private KGEmbeddingStorageService service;

    private static final Long FACT_SHEET_ID = 1L;
    private static final Long EMBEDDING_VERSION = 1000L;

    @BeforeEach
    void setUp() {
        service = new KGEmbeddingStorageService(nodeRepository, edgeRepository);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractTriples
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void extractTriples_withNoEdges_returnsEmptyList() {
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());

        List<Triple> triples = service.extractTriples(FACT_SHEET_ID);
        assertTrue(triples.isEmpty());
    }

    @Test
    void extractTriples_withEdges_returnsCorrectTriples() {
        GraphNode source = buildNode("Alice", NodeLevel.ENTITY);
        GraphNode target = buildNode("Bob", NodeLevel.ENTITY);
        GraphEdge edge = buildEdge(source, target, EdgeType.HIERARCHICAL);

        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(edge));

        List<Triple> triples = service.extractTriples(FACT_SHEET_ID);
        assertEquals(1, triples.size());
        assertEquals("Alice", triples.get(0).head());
        assertEquals("HIERARCHICAL", triples.get(0).relation());
        assertEquals("Bob", triples.get(0).tail());
    }

    @Test
    void extractTriples_edgeWithNullSource_isSkipped() {
        GraphNode target = buildNode("Bob", NodeLevel.ENTITY);
        GraphEdge edge = buildEdge(null, target, EdgeType.HIERARCHICAL);

        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(edge));

        List<Triple> triples = service.extractTriples(FACT_SHEET_ID);
        assertTrue(triples.isEmpty());
    }

    @Test
    void extractTriples_edgeWithNullTarget_isSkipped() {
        GraphNode source = buildNode("Alice", NodeLevel.ENTITY);
        GraphEdge edge = buildEdge(source, null, EdgeType.HIERARCHICAL);

        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(edge));

        List<Triple> triples = service.extractTriples(FACT_SHEET_ID);
        assertTrue(triples.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // storeEmbeddings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void storeEmbeddings_withMatchingNodes_updatesNodeEmbeddings() {
        GraphNode node = buildNode("Alice", NodeLevel.ENTITY);
        INDArray emb = Nd4j.rand(1, 10);

        Map<String, INDArray> entityEmbeddings = Map.of("Alice", emb);
        Map<String, INDArray> relEmbeddings = new HashMap<>();

        when(mockModel.getAllEntityEmbeddings()).thenReturn(entityEmbeddings);
        when(mockModel.getAllRelationEmbeddings()).thenReturn(relEmbeddings);
        when(mockModel.getAlgorithm()).thenReturn(KGEmbeddingAlgorithm.TRANSE);
        when(nodeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(node));
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(nodeRepository.saveAll(any())).thenReturn(Collections.emptyList());
        when(edgeRepository.saveAll(any())).thenReturn(Collections.emptyList());

        int result = service.storeEmbeddings(mockModel, FACT_SHEET_ID, EMBEDDING_VERSION);
        assertTrue(result >= 0);
        verify(nodeRepository).saveAll(anyList());
    }

    @Test
    void storeEmbeddings_withNoMatchingNodes_returnsZero() {
        when(mockModel.getAllEntityEmbeddings()).thenReturn(Map.of("Unknown", Nd4j.rand(1, 10)));
        when(mockModel.getAllRelationEmbeddings()).thenReturn(new HashMap<>());
        when(mockModel.getAlgorithm()).thenReturn(KGEmbeddingAlgorithm.TRANSE);
        when(nodeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(nodeRepository.saveAll(any())).thenReturn(Collections.emptyList());
        when(edgeRepository.saveAll(any())).thenReturn(Collections.emptyList());

        int result = service.storeEmbeddings(mockModel, FACT_SHEET_ID, EMBEDDING_VERSION);
        assertEquals(0, result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // loadEmbeddings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void loadEmbeddings_withNoNodesHavingEmbeddings_returnsFalse() {
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        boolean result = service.loadEmbeddings(mockModel, FACT_SHEET_ID);
        assertFalse(result);
    }

    @Test
    void loadEmbeddings_withNodesHavingEmbeddings_returnsTrue() {
        INDArray emb = Nd4j.rand(1, 10);
        GraphNode node = buildNodeWithEmbedding("Alice", emb, KGEmbeddingAlgorithm.TRANSE);

        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(List.of(node));
        when(edgeRepository.findByFactSheetIdAndKgRelationEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        boolean result = service.loadEmbeddings(mockModel, FACT_SHEET_ID);
        assertTrue(result);
        verify(mockModel).importEntityEmbeddings(any());
        verify(mockModel).importRelationEmbeddings(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStoredAlgorithm
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStoredAlgorithm_withNoEmbeddings_returnsNull() {
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        KGEmbeddingAlgorithm algo = service.getStoredAlgorithm(FACT_SHEET_ID);
        assertNull(algo);
    }

    @Test
    void getStoredAlgorithm_withTransENodes_returnsTransE() {
        GraphNode node = buildNodeWithEmbedding("Alice", Nd4j.rand(1, 10), KGEmbeddingAlgorithm.TRANSE);
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(List.of(node));

        KGEmbeddingAlgorithm algo = service.getStoredAlgorithm(FACT_SHEET_ID);
        assertEquals(KGEmbeddingAlgorithm.TRANSE, algo);
    }

    @Test
    void getStoredAlgorithm_withRotatENodes_returnsRotatE() {
        GraphNode node = buildNodeWithEmbedding("Bob", Nd4j.rand(1, 16), KGEmbeddingAlgorithm.ROTATE);
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(List.of(node));

        KGEmbeddingAlgorithm algo = service.getStoredAlgorithm(FACT_SHEET_ID);
        assertEquals(KGEmbeddingAlgorithm.ROTATE, algo);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStats
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStats_withEmptyFactSheet_returnsZeroCounts() {
        when(nodeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(nodeRepository.countByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID)).thenReturn(0L);
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(edgeRepository.countByFactSheetIdAndKgRelationEmbeddingNotNull(FACT_SHEET_ID)).thenReturn(0L);
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        KGEmbeddingStorageService.EmbeddingStats stats = service.getStats(FACT_SHEET_ID);

        assertEquals(0, stats.totalNodes());
        assertEquals(0, stats.nodesWithEmbeddings());
        assertEquals(0, stats.totalEdges());
        assertEquals(0, stats.edgesWithEmbeddings());
        assertNull(stats.latestVersion());
        assertFalse(stats.hasEmbeddings());
    }

    @Test
    void getStats_withEmbeddedNodes_hasEmbeddingsReturnsTrue() {
        GraphNode node = buildNodeWithEmbedding("Alice", Nd4j.rand(1, 10), KGEmbeddingAlgorithm.TRANSE);
        node.setKgEmbeddingVersion(EMBEDDING_VERSION);

        when(nodeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(node));
        when(nodeRepository.countByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID)).thenReturn(1L);
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(edgeRepository.countByFactSheetIdAndKgRelationEmbeddingNotNull(FACT_SHEET_ID)).thenReturn(0L);
        when(nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(FACT_SHEET_ID))
                .thenReturn(List.of(node));

        KGEmbeddingStorageService.EmbeddingStats stats = service.getStats(FACT_SHEET_ID);

        assertTrue(stats.hasEmbeddings());
        assertEquals(1, stats.nodesWithEmbeddings());
        assertEquals(EMBEDDING_VERSION, stats.latestVersion());
    }

    @Test
    void embeddingStats_nodeEmbeddingCoverage_calculatesCorrectly() {
        KGEmbeddingStorageService.EmbeddingStats stats =
                new KGEmbeddingStorageService.EmbeddingStats(10, 7, 5, 3, 100L);

        assertEquals(70.0, stats.nodeEmbeddingCoverage(), 1e-5);
        assertEquals(60.0, stats.edgeEmbeddingCoverage(), 1e-5);
    }

    @Test
    void embeddingStats_withZeroTotals_coverageIsZero() {
        KGEmbeddingStorageService.EmbeddingStats stats =
                new KGEmbeddingStorageService.EmbeddingStats(0, 0, 0, 0, null);

        assertEquals(0.0, stats.nodeEmbeddingCoverage(), 1e-5);
        assertEquals(0.0, stats.edgeEmbeddingCoverage(), 1e-5);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // clearEmbeddings
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void clearEmbeddings_setsNullOnAllNodes() {
        GraphNode node = buildNodeWithEmbedding("Alice", Nd4j.rand(1, 10), KGEmbeddingAlgorithm.TRANSE);
        when(nodeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(List.of(node));
        when(edgeRepository.findByFactSheetId(FACT_SHEET_ID)).thenReturn(Collections.emptyList());
        when(nodeRepository.saveAll(any())).thenReturn(Collections.emptyList());
        when(edgeRepository.saveAll(any())).thenReturn(Collections.emptyList());

        service.clearEmbeddings(FACT_SHEET_ID);

        assertNull(node.getKgEmbedding());
        assertNull(node.getKgEmbeddingAlgorithm());
        assertNull(node.getKgEmbeddingVersion());
        verify(nodeRepository).saveAll(anyList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode buildNode(String title, NodeLevel level) {
        return GraphNode.builder()
                .nodeId(UUID.randomUUID().toString())
                .title(title)
                .nodeType(level)
                .externalId(title.toLowerCase())
                .factSheetId(FACT_SHEET_ID)
                .build();
    }

    private GraphNode buildNodeWithEmbedding(String title, INDArray embedding, KGEmbeddingAlgorithm algo) {
        GraphNode node = buildNode(title, NodeLevel.ENTITY);
        node.setKgEmbedding(embedding);
        node.setKgEmbeddingAlgorithm(algo);
        return node;
    }

    private GraphEdge buildEdge(GraphNode source, GraphNode target, EdgeType type) {
        GraphEdge edge = new GraphEdge();
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        edge.setEdgeType(type);
        return edge;
    }
}
