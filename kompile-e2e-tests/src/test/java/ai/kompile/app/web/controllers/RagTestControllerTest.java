/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.web.controllers;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.reranking.RerankerType;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.modelmanager.KompileModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagTestControllerTest {

    @Mock
    private DocumentRetriever mockRetriever;

    @Mock
    private VectorStore mockVectorStore;

    @Mock
    private EmbeddingModel mockEmbeddingModel;

    @Mock
    private RerankerService rerankerService;

    @Mock
    private KompileModelManager modelManager;

    @Mock
    private GraphRagService graphRagService;

    // Controller with all real (non-NoOp) mocks injected
    private RagTestController controller;

    // Controller with only NoOp implementations
    private RagTestController noOpController;

    @BeforeEach
    void setUp() {
        when(rerankerService.getSupportedTypes()).thenReturn(List.of(RerankerType.RM3));
        when(rerankerService.isSupported(any())).thenReturn(true);

        controller = new RagTestController(
                List.of(mockRetriever),
                List.of(mockVectorStore),
                List.of(mockEmbeddingModel),
                rerankerService,
                modelManager,
                graphRagService
        );

        noOpController = new RagTestController(
                List.of(new NoOpDocumentRetrieverImpl()),
                List.of(new NoOpVectorStoreImpl()),
                List.of(new NoOpEmbeddingModelImpl()),
                null,
                null,
                null
        );
    }

    // ── constructor: NoOp filtering ───────────────────────────────────────

    @Test
    void constructor_picksNonNoOpRetriever() {
        NoOpDocumentRetrieverImpl noOp = new NoOpDocumentRetrieverImpl();
        RagTestController mixed = new RagTestController(
                List.of(noOp, mockRetriever),
                List.of(new NoOpVectorStoreImpl()),
                List.of(new NoOpEmbeddingModelImpl()),
                null, null, null
        );

        // Verify that when testQuery is called, the mock retriever (not noOp) is used
        when(mockRetriever.retrieveWithDetails(any(), anyInt())).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> resp = mixed.testQuery("hello", 5, 0.0, true, false);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(mockRetriever).retrieveWithDetails("hello", 5);
    }

    // ── getStatus ─────────────────────────────────────────────────────────

    @Test
    void getStatus_withAllComponents_returnsAvailableTrue() {
        ResponseEntity<Map<String, Object>> resp = controller.getStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> keywordStatus = (Map<?, ?>) resp.getBody().get("keywordRetriever");
        assertEquals(true, keywordStatus.get("available"));

        Map<?, ?> rerankerStatus = (Map<?, ?>) resp.getBody().get("reranker");
        assertEquals(true, rerankerStatus.get("available"));

        Map<?, ?> graphRagStatus = (Map<?, ?>) resp.getBody().get("graphRag");
        assertEquals(true, graphRagStatus.get("available"));
    }

    @Test
    void getStatus_withNoOpComponents_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> resp = noOpController.getStatus();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> keywordStatus = (Map<?, ?>) resp.getBody().get("keywordRetriever");
        assertEquals(false, keywordStatus.get("available"));

        Map<?, ?> vectorStatus = (Map<?, ?>) resp.getBody().get("vectorStore");
        assertEquals(false, vectorStatus.get("available"));

        Map<?, ?> rerankerStatus = (Map<?, ?>) resp.getBody().get("reranker");
        assertEquals(false, rerankerStatus.get("available"));

        Map<?, ?> graphRagStatus = (Map<?, ?>) resp.getBody().get("graphRag");
        assertEquals(false, graphRagStatus.get("available"));
    }

    // ── testEmbed ─────────────────────────────────────────────────────────

    @Test
    void testEmbed_withNoOp_returnsErrorMessage() {
        ResponseEntity<Map<String, Object>> resp = noOpController.testEmbed("hello world");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("error"));
    }

    // ── testQuery ─────────────────────────────────────────────────────────

    @Test
    void testQuery_keywordOnly_returnsResults() {
        RetrievedDoc doc = new RetrievedDoc("id1", "some content", Map.of(), 0.9);
        when(mockRetriever.retrieveWithDetails("query", 5)).thenReturn(List.of(doc));

        ResponseEntity<Map<String, Object>> resp = controller.testQuery("query", 5, 0.0, true, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("results"));
        assertEquals("query", resp.getBody().get("query"));
    }

    @Test
    void testQuery_withNoOpComponents_returnsEmptyResults() {
        ResponseEntity<Map<String, Object>> resp = noOpController.testQuery("query", 5, 0.0, true, true);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<?> results = (List<?>) resp.getBody().get("results");
        // No keyword or semantic results since both are NoOp
        assertEquals(0, results.size());
        assertEquals(0, resp.getBody().get("totalHits"));
    }

    @Test
    void testQuery_retrieverReturnsEmpty_zeroHits() {
        when(mockRetriever.retrieveWithDetails(any(), anyInt())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.testQuery("query", 5, 0.0, true, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("totalHits"));
    }

    // ── hybridSearch ──────────────────────────────────────────────────────

    @Test
    void hybridSearch_withNoOp_returnsEmptyHits() {
        ResponseEntity<Map<String, Object>> resp = noOpController.hybridSearch("query", 10, 0.0);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<?> hits = (List<?>) resp.getBody().get("hits");
        assertEquals(0, hits.size());
    }

    @Test
    void hybridSearch_withResults_mergesAndReturns() {
        RetrievedDoc doc = new RetrievedDoc("id1", "content text", Map.of(), 0.8);
        when(mockRetriever.retrieveWithDetails(any(), anyInt())).thenReturn(List.of(doc));
        when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.hybridSearch("query", 10, 0.0);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("hits"));
    }

    // ── getRerankers ──────────────────────────────────────────────────────

    @Test
    void getRerankers_withService_returnsAvailableTrue() {
        ResponseEntity<Map<String, Object>> resp = controller.getRerankers();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("available"));
        List<?> types = (List<?>) resp.getBody().get("types");
        assertFalse(types.isEmpty());
    }

    @Test
    void getRerankers_withoutService_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> resp = noOpController.getRerankers();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("available"));
        List<?> types = (List<?>) resp.getBody().get("types");
        assertFalse(types.isEmpty()); // types are always listed, just "supported=false"
    }

    // ── getCrossEncoderModels ─────────────────────────────────────────────

    @Test
    void getCrossEncoderModels_returns200WithModelsList() {
        when(modelManager.isCrossEncoderModelCached(any())).thenReturn(false);
        when(modelManager.getCrossEncoderModelPath(any())).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.getCrossEncoderModels();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("totalModels"));
        assertNotNull(resp.getBody().get("models"));
    }

    // ── downloadCrossEncoderModel ─────────────────────────────────────────

    @Test
    void downloadCrossEncoderModel_unknownModel_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.downloadCrossEncoderModel("unknown-model-xyz");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    // ── getCrossEncoderModelInfo ──────────────────────────────────────────

    @Test
    void getCrossEncoderModelInfo_unknownModel_returns404() {
        ResponseEntity<Map<String, Object>> resp = controller.getCrossEncoderModelInfo("no-such-model");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("found"));
    }

    // ── deleteCrossEncoderModel ───────────────────────────────────────────

    @Test
    void deleteCrossEncoderModel_unknownModel_returns404() {
        ResponseEntity<Map<String, Object>> resp = controller.deleteCrossEncoderModel("no-such-model");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("success"));
    }

    // ── testGraphRagQuery ─────────────────────────────────────────────────

    @Test
    void testGraphRagQuery_noService_returnsUnavailable() {
        ResponseEntity<Map<String, Object>> resp = noOpController.testGraphRagQuery(
                "What is X?", "LOCAL", 5, "conv-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("available"));
        assertNotNull(resp.getBody().get("error"));
    }

    @Test
    void testGraphRagQuery_withService_returnsAnswer() throws Exception {
        GraphRagResult graphResult = mock(GraphRagResult.class);
        when(graphResult.getAnswer()).thenReturn("The answer is 42.");
        when(graphResult.getFormattedContext()).thenReturn("context text");
        when(graphRagService.answerQuery(any(GraphRagQuery.class))).thenReturn(graphResult);

        ResponseEntity<Map<String, Object>> resp = controller.testGraphRagQuery(
                "What is X?", "LOCAL", 5, "conv-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("available"));
        assertEquals("The answer is 42.", resp.getBody().get("answer"));
    }

    @Test
    void testGraphRagQuery_invalidSearchType_defaultsToLocal() throws Exception {
        GraphRagResult graphResult = mock(GraphRagResult.class);
        when(graphResult.getAnswer()).thenReturn("answer");
        when(graphResult.getFormattedContext()).thenReturn("ctx");
        when(graphRagService.answerQuery(any())).thenReturn(graphResult);

        ResponseEntity<Map<String, Object>> resp = controller.testGraphRagQuery(
                "query", "INVALID_TYPE", 5, "conv-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("searchTypeWarning"));
    }

    // ── getGraphRagInfo ───────────────────────────────────────────────────

    @Test
    void getGraphRagInfo_withService_returnsAvailableTrue() {
        ResponseEntity<Map<String, Object>> resp = controller.getGraphRagInfo();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("available"));
        assertNotNull(resp.getBody().get("searchTypes"));
    }

    @Test
    void getGraphRagInfo_noService_returnsAvailableFalse() {
        ResponseEntity<Map<String, Object>> resp = noOpController.getGraphRagInfo();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("available"));
        List<?> searchTypes = (List<?>) resp.getBody().get("searchTypes");
        assertTrue(searchTypes.isEmpty());
    }
}
