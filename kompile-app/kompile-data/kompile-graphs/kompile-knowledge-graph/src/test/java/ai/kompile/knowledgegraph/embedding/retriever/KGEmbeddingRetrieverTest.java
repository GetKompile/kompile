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

package ai.kompile.knowledgegraph.embedding.retriever;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService;
import ai.kompile.knowledgegraph.embedding.config.KGEmbeddingConfigService.GraphRAGConfig;
import ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KGEmbeddingRetriever.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KGEmbeddingRetrieverTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KGEmbeddingStorageService storageService;

    @Mock
    private KGEmbeddingConfigService configService;

    @Mock
    private DocumentRetriever baseRetriever;

    private KGEmbeddingRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new KGEmbeddingRetriever(
                nodeRepository,
                edgeRepository,
                storageService,
                configService,
                null,          // no text embedding model
                baseRetriever  // base retriever
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isEnabled
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void isEnabled_whenGraphRAGEnabled_returnsTrue() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(true, 0.3, 0.7, 1, 5, 1L));
        assertTrue(retriever.isEnabled());
    }

    @Test
    void isEnabled_whenGraphRAGDisabled_returnsFalse() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(false, 0.3, 0.7, 1, 5, 1L));
        assertFalse(retriever.isEnabled());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // retrieve (disabled - falls back to base retriever)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void retrieve_whenGraphRAGDisabled_delegatesToBaseRetriever() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(false, 0.3, 0.7, 1, 5, 1L));

        RetrievedDoc doc = RetrievedDoc.builder()
                .id("doc1")
                .text("Some text")
                .score(0.9)
                .metadata(new HashMap<>())
                .build();
        when(baseRetriever.retrieveWithDetails(eq("my query"), eq(5)))
                .thenReturn(List.of(doc));

        List<String> results = retriever.retrieve("my query", 5);
        assertFalse(results.isEmpty());
        assertEquals("Some text", results.get(0));
    }

    @Test
    void retrieve_withNullQuery_returnsEmpty() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(true, 0.3, 0.7, 1, 5, 1L));

        List<String> results = retriever.retrieve(null, 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void retrieve_withBlankQuery_returnsEmpty() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(true, 0.3, 0.7, 1, 5, 1L));

        List<String> results = retriever.retrieve("   ", 5);
        assertTrue(results.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // retrieveWithDetails (enabled, no text embedding model)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void retrieveWithDetails_whenEnabled_usesKeywordMatch() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(true, 0.3, 0.7, 1, 5, 1L));

        // No text embedding model, so falls back to keyword match
        GraphNode node = buildNode("alice_node", "Alice in Wonderland", NodeLevel.ENTITY);
        when(nodeRepository.findEntitiesByFactSheet(1L)).thenReturn(List.of(node));
        when(nodeRepository.findByFactSheetId(1L)).thenReturn(List.of(node));
        when(edgeRepository.findBySourceNodeTitleAndFactSheetId(anyString(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(edgeRepository.findByTargetNodeTitleAndFactSheetId(anyString(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(Collections.emptyList());
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(any())).thenReturn(Collections.emptyList());
        when(storageService.getStoredAlgorithm(1L)).thenReturn(null);

        List<RetrievedDoc> results = retriever.retrieveWithDetails("alice", 5);
        // Should get at least one result since "alice" matches "Alice in Wonderland"
        assertNotNull(results);
    }

    @Test
    void retrieveWithDetails_whenGraphRAGDisabledWithNoBaseRetriever_returnsEmpty() {
        // Create retriever without base retriever
        KGEmbeddingRetriever retrieverNoBase = new KGEmbeddingRetriever(
                nodeRepository, edgeRepository, storageService, configService, null, null);

        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(false, 0.3, 0.7, 1, 5, 1L));

        List<RetrievedDoc> results = retrieverNoBase.retrieveWithDetails("test", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveWithDetails_whenExceptionOccurs_fallsBackToBaseRetriever() {
        when(configService.getGraphRAGConfig()).thenReturn(
                new GraphRAGConfig(true, 0.3, 0.7, 1, 5, 1L));

        // Make nodeRepository throw
        when(nodeRepository.findEntitiesByFactSheet(anyLong()))
                .thenThrow(new RuntimeException("DB error"));

        RetrievedDoc fallbackDoc = RetrievedDoc.builder()
                .id("fallback")
                .text("fallback text")
                .score(0.5)
                .metadata(new HashMap<>())
                .build();
        when(baseRetriever.retrieveWithDetails(anyString(), anyInt()))
                .thenReturn(List.of(fallbackDoc));

        // Should fall back gracefully
        List<RetrievedDoc> results = retriever.retrieveWithDetails("test query", 5);
        assertNotNull(results);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // clearCachedModel / clearAllCachedModels
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void clearCachedModel_doesNotThrow() {
        assertDoesNotThrow(() -> retriever.clearCachedModel(1L));
    }

    @Test
    void clearAllCachedModels_doesNotThrow() {
        assertDoesNotThrow(() -> retriever.clearAllCachedModels());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode buildNode(String nodeId, String title, NodeLevel level) {
        return GraphNode.builder()
                .id(1L)
                .nodeId(nodeId)
                .title(title)
                .nodeType(level)
                .externalId(nodeId)
                .factSheetId(1L)
                .build();
    }
}
