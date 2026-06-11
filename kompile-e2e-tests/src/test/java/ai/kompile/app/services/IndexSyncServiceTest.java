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

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexSyncServiceTest {

    @Mock
    private CrossIndexTrackingService trackingService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private IndexSyncService service;

    @BeforeEach
    void setUp() {
        service = new IndexSyncService(trackingService, eventPublisher, embeddingModel, vectorStore, knowledgeGraphService);
    }

    @Test
    void checkAndTriggerSync_withNullChunkIds_returnsAllPresent() {
        IndexSyncService.SyncCheckResult result = service.checkAndTriggerSync(null, 1L);
        assertThat(result.allPresent()).isTrue();
        assertThat(result.missingCount()).isZero();
        assertThat(result.syncTriggered()).isFalse();
    }

    @Test
    void checkAndTriggerSync_withEmptyChunkIds_returnsAllPresent() {
        IndexSyncService.SyncCheckResult result = service.checkAndTriggerSync(List.of(), 1L);
        assertThat(result.allPresent()).isTrue();
        assertThat(result.syncTriggered()).isFalse();
    }

    @Test
    void checkAndTriggerSync_whenSyncDisabled_returnsAllPresent() {
        service.setAutoSyncEnabled(1L, false);
        IndexSyncService.SyncCheckResult result = service.checkAndTriggerSync(List.of("chunk-1"), 1L);
        assertThat(result.allPresent()).isTrue();
        assertThat(result.syncTriggered()).isFalse();
    }

    @Test
    void getAutoSyncConfig_returnsDefaultConfig_forNewFactSheet() {
        IndexSyncService.AutoSyncConfig config = service.getAutoSyncConfig(999L);
        assertThat(config.enabled()).isTrue();
        assertThat(config.maxPassagesPerSync()).isEqualTo(100);
        assertThat(config.syncOnSearch()).isTrue();
        assertThat(config.syncOnIngest()).isTrue();
    }

    @Test
    void setAutoSyncEnabled_disablesForFactSheet() {
        service.setAutoSyncEnabled(1L, false);
        assertThat(service.isAutoSyncEnabled(1L)).isFalse();
    }

    @Test
    void setAutoSyncEnabled_enablesForFactSheet() {
        service.setAutoSyncEnabled(1L, false);
        service.setAutoSyncEnabled(1L, true);
        assertThat(service.isAutoSyncEnabled(1L)).isTrue();
    }

    @Test
    void updateAutoSyncConfig_persistsConfig() {
        IndexSyncService.AutoSyncConfig config = new IndexSyncService.AutoSyncConfig(
                false, 50, Duration.ofSeconds(60), false, false);
        service.updateAutoSyncConfig(42L, config);

        IndexSyncService.AutoSyncConfig retrieved = service.getAutoSyncConfig(42L);
        assertThat(retrieved.enabled()).isFalse();
        assertThat(retrieved.maxPassagesPerSync()).isEqualTo(50);
    }

    @Test
    void cancelJob_returnsFalseForNonExistentJob() {
        boolean cancelled = service.cancelJob("nonexistent-job-id");
        assertThat(cancelled).isFalse();
    }

    @Test
    void getJobStatus_returnsEmptyForNonExistentJob() {
        Optional<IndexSyncService.SyncJobStatus> status = service.getJobStatus("nonexistent");
        assertThat(status).isEmpty();
    }

    @Test
    void getActiveJobs_returnsEmptyWhenNoActiveJobs() {
        List<IndexSyncService.SyncJobStatus> jobs = service.getActiveJobs();
        assertThat(jobs).isEmpty();
    }

    @Test
    void syncToVectorStore_completesSuccessfully() throws Exception {
        org.mockito.Mockito.when(trackingService.findDocumentsNeedingSync(null))
                .thenReturn(List.of());

        IndexSyncService.SyncResult result = service.syncToVectorStore(null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.COMPLETED);
        assertThat(result.jobId()).isNotNull();
    }

    @Test
    void syncToKnowledgeGraph_completesSuccessfully() throws Exception {
        org.mockito.Mockito.when(trackingService.findPassagesMissingFromGraph(null))
                .thenReturn(List.of());

        IndexSyncService.SyncResult result = service.syncToKnowledgeGraph(null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.COMPLETED);
    }

    @Test
    void syncAll_completesSuccessfully() throws Exception {
        org.mockito.Mockito.when(trackingService.findDocumentsNeedingSync(null))
                .thenReturn(List.of());

        IndexSyncService.SyncResult result = service.syncAll(null).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.COMPLETED);
    }

    @Test
    void syncDocuments_emptyList_completesImmediately() throws Exception {
        IndexSyncService.SyncResult result = service.syncDocuments(List.of()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.COMPLETED);
        assertThat(result.documentsProcessed()).isZero();
    }

    @Test
    void syncPassages_emptyList_completesImmediately() throws Exception {
        IndexSyncService.SyncResult result = service.syncPassages(List.of()).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.COMPLETED);
    }

    @Test
    void awaitSync_returnsFailedForMissingJob() {
        IndexSyncService.SyncResult result = service.awaitSync("missing-job-id", Duration.ofMillis(100));
        assertThat(result.status()).isEqualTo(IndexSyncService.SyncStatus.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.contains("missing-job-id"));
    }

    @Test
    void syncStatus_enums_areComplete() {
        // Ensure all expected SyncStatus values exist
        assertThat(IndexSyncService.SyncStatus.values())
                .containsExactlyInAnyOrder(
                        IndexSyncService.SyncStatus.PENDING,
                        IndexSyncService.SyncStatus.RUNNING,
                        IndexSyncService.SyncStatus.COMPLETED,
                        IndexSyncService.SyncStatus.PARTIAL,
                        IndexSyncService.SyncStatus.FAILED,
                        IndexSyncService.SyncStatus.CANCELLED
                );
    }
}
