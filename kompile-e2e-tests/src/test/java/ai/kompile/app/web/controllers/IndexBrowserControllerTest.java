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

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexBrowserControllerTest {

    @Mock
    private IndexerService indexerService;

    @Mock
    private DocumentRetriever documentRetriever;

    @Mock
    private VectorStore vectorStore;

    private IndexBrowserController controller;

    @BeforeEach
    void setUp() {
        // Use single-element lists so the controller picks the only element
        controller = new IndexBrowserController(
                List.of(indexerService),
                List.of(documentRetriever),
                List.of(vectorStore),
                null, // stagingClientService
                null, // stagingConfigService
                null  // embeddingModel
        );
    }

    // ─── getIndexBrowserStatus ────────────────────────────────────────────────

    @Test
    void getIndexBrowserStatus_returnsOk() throws Exception {
        when(indexerService.isIndexAvailable()).thenReturn(true);
        when(indexerService.getApproxTotalDocCount(any())).thenReturn(5L);
        when(indexerService.getIndexPath()).thenReturn("/tmp/index");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getVectorStorePath()).thenReturn("/tmp/vectors");
        when(vectorStore.isUsingFallbackIndex()).thenReturn(false);
        when(vectorStore.getApproxVectorCount()).thenReturn(10L);

        ResponseEntity<?> resp = controller.getIndexBrowserStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("indexAvailable");
        assertThat(body.get("indexAvailable")).isEqualTo(true);
        assertThat(body).containsKey("vectorStoreAvailable");
    }

    @Test
    void getIndexBrowserStatus_exceptionInIndexer_returnsInternalServerError() throws Exception {
        when(indexerService.isIndexAvailable()).thenThrow(new RuntimeException("index broken"));

        ResponseEntity<?> resp = controller.getIndexBrowserStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── listIndexedDocuments ─────────────────────────────────────────────────

    @Test
    void listIndexedDocuments_success_returnsList() throws IOException {
        when(indexerService.listIndexedDocuments(0, 10)).thenReturn(
                List.of(Map.of("id", "doc1", "content", "hello")));

        ResponseEntity<?> resp = controller.listIndexedDocuments(0, 10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> body = (List<?>) resp.getBody();
        assertThat(body).hasSize(1);
    }

    @Test
    void listIndexedDocuments_ioException_returnsInternalServerError() throws IOException {
        when(indexerService.listIndexedDocuments(anyInt(), anyInt()))
                .thenThrow(new IOException("index missing"));

        ResponseEntity<?> resp = controller.listIndexedDocuments(0, 10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── getIndexedDocument ───────────────────────────────────────────────────

    @Test
    void getIndexedDocument_found_returnsOk() throws IOException {
        when(indexerService.getIndexedDocument("doc1")).thenReturn(Map.of("id", "doc1"));

        ResponseEntity<?> resp = controller.getIndexedDocument("doc1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getIndexedDocument_notFound_returnsNotFound() throws IOException {
        when(indexerService.getIndexedDocument("ghost")).thenReturn(null);

        ResponseEntity<?> resp = controller.getIndexedDocument("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getIndexedDocument_ioException_returnsInternalServerError() throws IOException {
        when(indexerService.getIndexedDocument("bad")).thenThrow(new IOException("read error"));

        ResponseEntity<?> resp = controller.getIndexedDocument("bad");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── updateIndexedDocument ────────────────────────────────────────────────

    @Test
    void updateIndexedDocument_nullRequest_returnsBadRequest() {
        ResponseEntity<?> resp = controller.updateIndexedDocument("doc1", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateIndexedDocument_nullContent_returnsBadRequest() {
        IndexBrowserController.UpdateDocRequest req = new IndexBrowserController.UpdateDocRequest(null);

        ResponseEntity<?> resp = controller.updateIndexedDocument("doc1", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateIndexedDocument_success_returnsOk() throws IOException {
        when(indexerService.updateIndexedDocumentContent("doc1", "new content")).thenReturn(true);
        IndexBrowserController.UpdateDocRequest req = new IndexBrowserController.UpdateDocRequest("new content");

        ResponseEntity<?> resp = controller.updateIndexedDocument("doc1", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateIndexedDocument_failure_returnsInternalServerError() throws IOException {
        when(indexerService.updateIndexedDocumentContent("doc1", "new content")).thenReturn(false);
        IndexBrowserController.UpdateDocRequest req = new IndexBrowserController.UpdateDocRequest("new content");

        ResponseEntity<?> resp = controller.updateIndexedDocument("doc1", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── searchIndexedDocuments ───────────────────────────────────────────────

    @Test
    void searchIndexedDocuments_emptyQuery_returnsBadRequest() {
        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("", 10, null);

        ResponseEntity<?> resp = controller.searchIndexedDocuments(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchIndexedDocuments_maxResultsTooHigh_returnsBadRequest() {
        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("query", 200, null);

        ResponseEntity<?> resp = controller.searchIndexedDocuments(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchIndexedDocuments_success_returnsResults() throws Exception {
        RetrievedDoc doc = new RetrievedDoc("d1", "some content", Map.of(), 0.9);
        when(documentRetriever.retrieveWithDetails("AI query", 10)).thenReturn(List.of(doc));
        when(indexerService.getApproxTotalDocCount(any())).thenReturn(5L);

        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("AI query", 10, null);
        ResponseEntity<?> resp = controller.searchIndexedDocuments(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("query")).isEqualTo("AI query");
        assertThat(body.get("totalResults")).isEqualTo(1);
    }

    // ─── listVectorStoreDocuments ─────────────────────────────────────────────

    @Test
    void listVectorStoreDocuments_success_returnsList() {
        when(vectorStore.listVectorDocuments(0, 10)).thenReturn(
                List.of(Map.of("id", "v1")));

        ResponseEntity<?> resp = controller.listVectorStoreDocuments(0, 10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> body = (List<?>) resp.getBody();
        assertThat(body).hasSize(1);
    }

    @Test
    void listVectorStoreDocuments_exception_returnsInternalServerError() {
        when(vectorStore.listVectorDocuments(anyInt(), anyInt()))
                .thenThrow(new RuntimeException("vector error"));

        ResponseEntity<?> resp = controller.listVectorStoreDocuments(0, 10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── searchVectorStore ────────────────────────────────────────────────────

    @Test
    void searchVectorStore_emptyQuery_returnsBadRequest() {
        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("   ", 10, null);

        ResponseEntity<?> resp = controller.searchVectorStore(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchVectorStore_invalidMaxResults_returnsBadRequest() {
        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("query", 0, null);

        ResponseEntity<?> resp = controller.searchVectorStore(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchVectorStore_success_returnsResults() {
        when(vectorStore.similaritySearch("query", 10, 0.0)).thenReturn(Collections.emptyList());
        when(vectorStore.getApproxVectorCount()).thenReturn(5L);

        IndexBrowserController.SearchRequest req = new IndexBrowserController.SearchRequest("query", 10, 0.0);
        ResponseEntity<?> resp = controller.searchVectorStore(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("searchType")).isEqualTo("vector");
        assertThat(body).containsKey("totalResults");
    }
}
