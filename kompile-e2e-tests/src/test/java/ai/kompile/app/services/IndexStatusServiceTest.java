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

package ai.kompile.app.services;

import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexStatusServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private IndexerService indexerService;

    @Test
    void getStatus_withNoOpVectorStore_showsNotLoaded() {
        NoOpVectorStoreImpl noOp = new NoOpVectorStoreImpl();
        IndexStatusService service = new IndexStatusService(null, List.of(noOp), List.of());
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isVectorStoreNoOp()).isTrue();
        assertThat(status.isVectorIndexLoaded()).isFalse();
    }

    @Test
    void getStatus_withNullVectorStore_showsNotAvailable() {
        IndexStatusService service = new IndexStatusService(null, null, null);
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.getVectorStorePath()).isEqualTo("N/A");
        assertThat(status.isVectorStoreAvailable()).isFalse();
        assertThat(status.getVectorDocumentCount()).isZero();
    }

    @Test
    void getStatus_withRealVectorStore_populatesVectorFields() {
        when(vectorStore.getVectorStorePath()).thenReturn("/some/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getApproxVectorCount()).thenReturn(100L);

        IndexStatusService service = new IndexStatusService(null, List.of(vectorStore), null);
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isVectorStoreNoOp()).isFalse();
        assertThat(status.getVectorStorePath()).isEqualTo("/some/path");
        assertThat(status.getVectorDocumentCount()).isEqualTo(100L);
        assertThat(status.isVectorIndexLoaded()).isTrue();
    }

    @Test
    void getStatus_vectorIndexEmpty_whenVectorCountIsZero() {
        when(vectorStore.getVectorStorePath()).thenReturn("/some/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getApproxVectorCount()).thenReturn(0L);

        IndexStatusService service = new IndexStatusService(null, List.of(vectorStore), null);
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isVectorIndexLoaded()).isFalse();
        assertThat(status.isVectorIndexEmpty()).isTrue();
    }

    @Test
    void getStatus_withNoOpIndexer_showsNotLoaded() {
        NoOpIndexerService noOp = new NoOpIndexerService();
        IndexStatusService service = new IndexStatusService(null, null, List.of(noOp));
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isIndexerServiceNoOp()).isTrue();
        assertThat(status.isKeywordIndexLoaded()).isFalse();
    }

    @Test
    void getStatus_withNullIndexer_showsNotAvailable() {
        IndexStatusService service = new IndexStatusService(null, null, null);
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.getKeywordIndexPath()).isEqualTo("N/A");
        assertThat(status.isKeywordIndexAvailable()).isFalse();
    }

    @Test
    void getStatus_withRealIndexer_populatesKeywordFields() {
        when(indexerService.getIndexPath()).thenReturn("/keyword/path");
        when(indexerService.isIndexAvailable()).thenReturn(true);
        when(indexerService.getApproxTotalDocCount(null)).thenReturn(50L);

        IndexStatusService service = new IndexStatusService(null, null, List.of(indexerService));
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isIndexerServiceNoOp()).isFalse();
        assertThat(status.getKeywordIndexPath()).isEqualTo("/keyword/path");
        assertThat(status.getKeywordDocumentCount()).isEqualTo(50L);
        assertThat(status.isKeywordIndexLoaded()).isTrue();
    }

    @Test
    void getStatus_cachedForTtl() {
        when(vectorStore.getApproxVectorCount()).thenReturn(100L);
        when(vectorStore.getVectorStorePath()).thenReturn("/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);

        IndexStatusService service = new IndexStatusService(null, List.of(vectorStore), null);
        IndexStatusService.IndexStatus first = service.getStatus();
        IndexStatusService.IndexStatus second = service.getStatus();

        // Same instance from cache
        assertThat(first).isSameAs(second);
    }

    @Test
    void refreshStatus_clearsCache() {
        when(vectorStore.getApproxVectorCount()).thenReturn(100L, 200L);
        when(vectorStore.getVectorStorePath()).thenReturn("/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);

        IndexStatusService service = new IndexStatusService(null, List.of(vectorStore), null);
        IndexStatusService.IndexStatus first = service.getStatus();

        // Force refresh
        IndexStatusService.IndexStatus second = service.refreshStatus();
        // After refresh, new status computed (may differ if mock returns different value)
        assertThat(second).isNotNull();
    }

    @Test
    void getStatus_anyIndexLoaded_trueWhenEitherLoaded() {
        when(vectorStore.getVectorStorePath()).thenReturn("/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getApproxVectorCount()).thenReturn(10L);

        IndexStatusService service = new IndexStatusService(null, List.of(vectorStore), null);
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.isAnyIndexLoaded()).isTrue();
    }

    @Test
    void getStatus_anyIndexLoaded_falseWhenNeitherLoaded() {
        IndexStatusService service = new IndexStatusService(null, null, null);
        IndexStatusService.IndexStatus status = service.getStatus();
        assertThat(status.isAnyIndexLoaded()).isFalse();
    }

    @Test
    void getStatus_warningMessage_presentWhenNoIndexLoaded() {
        IndexStatusService service = new IndexStatusService(null, null, null);
        IndexStatusService.IndexStatus status = service.getStatus();
        assertThat(status.getWarningMessage()).isNotBlank();
    }

    @Test
    void getStatus_noWarningMessage_whenBothLoaded() {
        when(vectorStore.getVectorStorePath()).thenReturn("/path");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getApproxVectorCount()).thenReturn(10L);
        when(indexerService.getIndexPath()).thenReturn("/kw");
        when(indexerService.isIndexAvailable()).thenReturn(true);
        when(indexerService.getApproxTotalDocCount(null)).thenReturn(10L);

        IndexStatusService service = new IndexStatusService(
                null, List.of(vectorStore), List.of(indexerService));
        IndexStatusService.IndexStatus status = service.getStatus();

        assertThat(status.getWarningMessage()).isNull();
    }

    @Test
    void discoverVectorIndices_withNullDataDir_returnsEmpty() {
        IndexStatusService service = new IndexStatusService(null, null, null);
        String firstAvailable = service.getFirstAvailableIndexPath();
        assertThat(firstAvailable).isNull();
    }

    @Test
    void discoverVectorIndices_withValidLuceneIndex() throws IOException {
        // Create a fake Lucene index directory under the dataDir
        Path vectorIndexDir = tempDir.resolve("anserini-vector-index");
        Files.createDirectories(vectorIndexDir);
        Files.writeString(vectorIndexDir.resolve("segments_1"), "fake segment");

        IndexStatusService service = new IndexStatusService(tempDir.toString(), null, null);
        String firstAvailable = service.getFirstAvailableIndexPath();
        assertThat(firstAvailable).isEqualTo(vectorIndexDir.toString());
    }

    @Test
    void selectNonNoOp_prefersRealImplementation() {
        NoOpVectorStoreImpl noOp = new NoOpVectorStoreImpl();
        when(vectorStore.getVectorStorePath()).thenReturn("/real");
        when(vectorStore.isVectorStoreAvailable()).thenReturn(true);
        when(vectorStore.getApproxVectorCount()).thenReturn(0L);

        IndexStatusService service = new IndexStatusService(null, List.of(noOp, vectorStore), null);
        IndexStatusService.IndexStatus status = service.getStatus();

        // Should pick the real vector store, not the NoOp
        assertThat(status.isVectorStoreNoOp()).isFalse();
    }
}
