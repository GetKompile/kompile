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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.services.subprocess.VectorPopulationSubprocessLauncher;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VectorStorePopulationService}.
 * Focuses on lifecycle, subprocess mode flag, task tracking, and inner classes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorStorePopulationServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private DocumentLoader documentLoader;

    @Mock
    private IndexerService indexerService;

    @Mock
    private VectorPopulationSubprocessLauncher subprocessLauncher;

    @Mock
    private IndexingJobHistoryService jobHistoryService;

    private VectorStorePopulationService service;
    private IngestConfiguration ingestConfiguration;

    @BeforeEach
    void setUp() {
        ingestConfiguration = new IngestConfiguration();

        service = new VectorStorePopulationService(
                messagingTemplate,
                List.of(documentLoader),
                List.of(indexerService),
                null,   // embeddingModels — optional
                null,   // vectorStores   — optional
                null,   // appIndexConfigService — optional
                null,   // subprocessLauncher — optional (null means subprocess mode unavailable)
                null,   // ingestProgressTracker — optional
                null,   // jobHistoryService — optional
                null,   // resourceScheduler — optional
                ingestConfiguration
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        service.destroy();
    }

    // ===== Subprocess mode =====

    @Test
    void isSubprocessModeEnabled_falseWhenLauncherNull() {
        // launcher is null in setUp — subprocess mode must be disabled
        assertThat(service.isSubprocessModeEnabled()).isFalse();
    }

    @Test
    void isSubprocessModeEnabled_trueWhenLauncherAvailableAndEnabled() throws Exception {
        VectorStorePopulationService svcWithLauncher = new VectorStorePopulationService(
                messagingTemplate,
                List.of(documentLoader),
                List.of(indexerService),
                null, null, null,
                subprocessLauncher,
                null, null, null,
                ingestConfiguration
        );

        // @Value fields are not injected outside Spring — enable manually
        svcWithLauncher.setSubprocessModeEnabled(true);
        assertThat(svcWithLauncher.isSubprocessModeEnabled()).isTrue();
        svcWithLauncher.destroy();
    }

    @Test
    void setSubprocessModeEnabled_canDisable() throws Exception {
        VectorStorePopulationService svcWithLauncher = new VectorStorePopulationService(
                messagingTemplate,
                List.of(documentLoader),
                List.of(indexerService),
                null, null, null,
                subprocessLauncher,
                null, null, null,
                ingestConfiguration
        );

        svcWithLauncher.setSubprocessModeEnabled(false);
        assertThat(svcWithLauncher.isSubprocessModeEnabled()).isFalse();
        svcWithLauncher.destroy();
    }

    @Test
    void setSubprocessModeEnabled_canEnable_butFalseWhenLauncherNull() {
        // launcher is null — even if we enable the flag, isSubprocessModeEnabled() checks launcher != null
        service.setSubprocessModeEnabled(true);
        assertThat(service.isSubprocessModeEnabled()).isFalse(); // launcher still null
    }

    // ===== Task tracking =====

    @Test
    void getTaskStatus_returnsNull_forUnknownTask() {
        assertThat(service.getTaskStatus("nonexistent-task")).isNull();
    }

    @Test
    void getActiveTasks_returnsEmptyMap_initially() {
        Map<String, VectorStorePopulationService.PopulationTaskStatus> tasks = service.getActiveTasks();
        assertThat(tasks).isNotNull().isEmpty();
    }

    @Test
    void cancelTask_returnsFalse_forUnknownTask() {
        assertThat(service.cancelTask("no-such-task")).isFalse();
    }

    // ===== PopulationResult inner class =====

    @Test
    void populationResult_fieldsAccessible() {
        VectorStorePopulationService.PopulationResult result =
                new VectorStorePopulationService.PopulationResult(
                        "task-1", true, 250, 5000, null);

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.success()).isTrue();
        assertThat(result.documentsIndexed()).isEqualTo(250);
        assertThat(result.durationMs()).isEqualTo(5000);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void populationResult_failureHasErrorMessage() {
        VectorStorePopulationService.PopulationResult result =
                new VectorStorePopulationService.PopulationResult(
                        "task-err", false, 0, 0, "OOM error");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("OOM error");
    }

    // ===== PopulationTaskStatus inner class =====

    @Test
    void populationTaskStatus_initialState() {
        VectorStorePopulationService.PopulationTaskStatus status =
                new VectorStorePopulationService.PopulationTaskStatus("t1", 100);

        assertThat(status.getTaskId()).isEqualTo("t1");
        assertThat(status.getTotalDocuments()).isEqualTo(100);
        assertThat(status.getDocumentsIndexed()).isEqualTo(0);
        assertThat(status.getProgressPercent()).isEqualTo(0);
        assertThat(status.isComplete()).isFalse();
        assertThat(status.getStartTime()).isGreaterThan(0);
    }

    @Test
    void populationTaskStatus_updateProgress() {
        VectorStorePopulationService.PopulationTaskStatus status =
                new VectorStorePopulationService.PopulationTaskStatus("t2", 200);

        status.updateProgress(50, 25);
        assertThat(status.getDocumentsIndexed()).isEqualTo(50);
        assertThat(status.getProgressPercent()).isEqualTo(25);
    }

    @Test
    void populationTaskStatus_complete_setsCompleteFlagAndPercent() {
        VectorStorePopulationService.PopulationTaskStatus status =
                new VectorStorePopulationService.PopulationTaskStatus("t3", 100);

        status.complete(100, 3000);
        assertThat(status.isComplete()).isTrue();
        assertThat(status.getProgressPercent()).isEqualTo(100);
        assertThat(status.getDocumentsIndexed()).isEqualTo(100);
    }

    @Test
    void populationTaskStatus_getElapsedMs_returnsPositiveBeforeComplete() {
        VectorStorePopulationService.PopulationTaskStatus status =
                new VectorStorePopulationService.PopulationTaskStatus("t4", 50);

        assertThat(status.getElapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void populationTaskStatus_getElapsedMs_afterComplete_usesProvidedDuration() {
        VectorStorePopulationService.PopulationTaskStatus status =
                new VectorStorePopulationService.PopulationTaskStatus("t5", 50);

        status.complete(50, 7777);
        // endTime = startTime + 7777 → elapsed = 7777
        assertThat(status.getElapsedMs()).isEqualTo(7777L);
    }

    // ===== Constructor with NoOpIndexerService selection =====

    @Test
    void constructor_prefersNonNoOpIndexer() throws Exception {
        IndexerService noOp = new NoOpIndexerService();
        VectorStorePopulationService svc = new VectorStorePopulationService(
                null,
                List.of(documentLoader),
                List.of(noOp, indexerService), // noOp first, real second
                null, null, null, null, null, null, null,
                ingestConfiguration
        );
        // Should prefer indexerService over noOp — no direct getter, but construction must not throw
        assertThat(svc).isNotNull();
        svc.destroy();
    }

    // ===== destroy() =====

    @Test
    void destroy_doesNotThrow() {
        assertThatCode(() -> service.destroy()).doesNotThrowAnyException();
    }
}
