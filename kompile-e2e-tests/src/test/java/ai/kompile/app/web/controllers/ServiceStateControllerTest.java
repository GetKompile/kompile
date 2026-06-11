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

import ai.kompile.app.services.IndexStatusService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.reranking.RerankerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ServiceStateController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceStateControllerTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private DocumentRetriever documentRetriever;

    @Mock
    private RerankerService rerankerService;

    @Mock
    private IndexStatusService indexStatusService;

    private ServiceStateController buildController(
            List<EmbeddingModel> models,
            List<VectorStore> stores,
            List<DocumentRetriever> retrievers,
            RerankerService reranker,
            IndexStatusService indexStatus) {
        return new ServiceStateController(models, stores, retrievers, reranker, indexStatus);
    }

    @Test
    void getServiceState_withAllServices_returnsOk() {
        ServiceStateController controller = buildController(
                List.of(embeddingModel), List.of(vectorStore),
                List.of(documentRetriever), rerankerService, indexStatusService);

        ResponseEntity<Map<String, Object>> response = controller.getServiceState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("timestamp"));
        assertTrue(response.getBody().containsKey("embeddingModel"));
        assertTrue(response.getBody().containsKey("vectorStore"));
    }

    @Test
    void getServiceState_withNoServices_returnsOkWithEmptyState() {
        ServiceStateController controller = buildController(null, null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getServiceState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getEmbeddingModelState_returnsOk() {
        ServiceStateController controller = buildController(
                List.of(embeddingModel), null, null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getEmbeddingState();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getVectorStoreState_returnsOk() {
        ServiceStateController controller = buildController(
                null, List.of(vectorStore), null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.getVectorStoreStateEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getRerankerState_withService_returnsOk() {
        ServiceStateController controller = buildController(null, null, null, rerankerService, null);

        ResponseEntity<Map<String, Object>> response = controller.getRerankerStateEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getDocumentRetrieverState_returnsOk() {
        ServiceStateController controller = buildController(
                null, null, List.of(documentRetriever), null, null);

        ResponseEntity<Map<String, Object>> response = controller.getDocumentRetrieverStateEndpoint();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getIndexStatus_withService_returnsOk() {
        IndexStatusService.IndexStatus status = IndexStatusService.IndexStatus.builder()
                .vectorStorePath("/some/path")
                .vectorStoreAvailable(false)
                .vectorStoreNoOp(true)
                .vectorDocumentCount(0)
                .vectorIndexLoaded(false)
                .vectorIndexEmpty(true)
                .keywordIndexPath("/some/keyword/path")
                .keywordIndexAvailable(false)
                .indexerServiceNoOp(true)
                .keywordDocumentCount(0)
                .keywordIndexLoaded(false)
                .keywordIndexEmpty(true)
                .availableVectorIndices(Collections.emptyList())
                .availableKarchFiles(Collections.emptyList())
                .anyIndexLoaded(false)
                .build();
        when(indexStatusService.getStatus()).thenReturn(status);

        ServiceStateController controller = buildController(null, null, null, null, indexStatusService);

        ResponseEntity<IndexStatusService.IndexStatus> response = controller.getIndexStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void getIndexStatus_withoutService_returnsFallback() {
        ServiceStateController controller = buildController(null, null, null, null, null);

        ResponseEntity<IndexStatusService.IndexStatus> response = controller.getIndexStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }
}
