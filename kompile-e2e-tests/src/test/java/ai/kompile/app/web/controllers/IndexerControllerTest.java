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
import ai.kompile.core.indexers.NoOpIndexerService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexerControllerTest {

    @Mock
    private IndexerService indexerService;

    @Mock
    private VectorStore vectorStore;

    private IndexerController controller;

    @BeforeEach
    void setUp() {
        // Build with a single non-NoOp indexer service and a vector store
        controller = new IndexerController(List.of(indexerService), List.of(vectorStore));
    }

    // ─── rebuildAllSourcesIndex ───────────────────────────────────────────────

    @Test
    void rebuildAllSourcesIndex_success_returnsOk() throws Exception {
        doNothing().when(indexerService).reprocessAndIndexAllSources();

        ResponseEntity<?> resp = controller.rebuildAllSourcesIndex();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> body = (Map<Object, Object>) resp.getBody();
        assertThat(body).containsKey("message");
    }

    @Test
    void rebuildAllSourcesIndex_exception_returnsInternalServerError() throws Exception {
        doThrow(new RuntimeException("disk full")).when(indexerService).reprocessAndIndexAllSources();

        ResponseEntity<?> resp = controller.rebuildAllSourcesIndex();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── startVectorIndexCreation ─────────────────────────────────────────────

    @Test
    void startVectorIndexCreation_started_returnsOk() {
        when(indexerService.startVectorIndexCreationAsync()).thenReturn(true);

        ResponseEntity<?> resp = controller.startVectorIndexCreation();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void startVectorIndexCreation_alreadyRunning_returnsConflict() {
        when(indexerService.startVectorIndexCreationAsync()).thenReturn(false);

        ResponseEntity<?> resp = controller.startVectorIndexCreation();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void startVectorIndexCreation_exception_returnsInternalServerError() {
        when(indexerService.startVectorIndexCreationAsync()).thenThrow(new RuntimeException("error"));

        ResponseEntity<?> resp = controller.startVectorIndexCreation();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ─── cancelVectorIndexCreation ────────────────────────────────────────────

    @Test
    void cancelVectorIndexCreation_success_returnsOk() {
        doNothing().when(indexerService).cancelCurrentJob();

        ResponseEntity<?> resp = controller.cancelVectorIndexCreation();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<Object, Object> cancelBody = (Map<Object, Object>) resp.getBody();
        assertThat(cancelBody).containsKey("message");
    }

    // ─── getVectorIndexJobStatus ──────────────────────────────────────────────

    @Test
    void getVectorIndexJobStatus_returnsJobStatus() {
        when(indexerService.getJobStatus()).thenReturn(IndexerService.JobStatus.idle());

        ResponseEntity<?> resp = controller.getVectorIndexJobStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── indexFromLucene (alias) ──────────────────────────────────────────────

    @Test
    void indexFromLucene_delegatesToStartVectorIndexCreation() {
        when(indexerService.startVectorIndexCreationAsync()).thenReturn(true);

        ResponseEntity<?> resp = controller.indexFromLucene();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(indexerService).startVectorIndexCreationAsync();
    }

    // ─── getIndexStatus ───────────────────────────────────────────────────────

    @Test
    void getIndexStatus_indexAvailable_returnsAvailable() {
        when(indexerService.isIndexAvailable()).thenReturn(true);
        when(indexerService.getIndexPath()).thenReturn("/tmp/index");
        when(vectorStore.getVectorStorePath()).thenReturn("/tmp/vectors");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.isUsingFallbackIndex()).thenReturn(false);
        when(vectorStore.getApproxVectorCount()).thenReturn(100L);

        ResponseEntity<?> resp = controller.getIndexStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("indexAvailable")).isEqualTo(true);
        assertThat(body.get("index_status")).isEqualTo("AVAILABLE");
    }

    @Test
    void getIndexStatus_indexNotAvailable_returnsNotAvailable() {
        when(indexerService.isIndexAvailable()).thenReturn(false);
        when(indexerService.getIndexPath()).thenReturn("/tmp/index");
        when(vectorStore.getVectorStorePath()).thenReturn("/tmp/vectors");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(false);
        when(vectorStore.isUsingFallbackIndex()).thenReturn(false);
        when(vectorStore.getApproxVectorCount()).thenReturn(0L);

        ResponseEntity<?> resp = controller.getIndexStatus();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("indexAvailable")).isEqualTo(false);
        assertThat(body.get("index_status")).isEqualTo("NOT_AVAILABLE_OR_INVALID");
    }
}
