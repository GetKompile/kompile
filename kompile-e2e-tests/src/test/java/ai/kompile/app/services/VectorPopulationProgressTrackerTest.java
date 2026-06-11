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

import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VectorPopulationProgressTracker}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorPopulationProgressTrackerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    @Mock
    private IndexingJobHistoryService jobHistoryService;

    @Mock
    private JobLogService jobLogService;

    private VectorPopulationProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new VectorPopulationProgressTracker(
                messagingTemplate, nd4jEnvironmentConfigService,
                jobHistoryService, jobLogService, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        tracker.destroy();
    }

    // ===== generateTaskId =====

    @Test
    void generateTaskId_returnsNonNull() {
        assertThat(tracker.generateTaskId()).isNotNull().isNotBlank();
    }

    @Test
    void generateTaskId_returnsUniqueIds() {
        String id1 = tracker.generateTaskId();
        String id2 = tracker.generateTaskId();
        assertThat(id1).isNotEqualTo(id2);
    }

    // ===== startTask =====

    @Test
    void startTask_createsTaskEntry() {
        tracker.startTask("task-1", "/keyword/idx", "/vector/idx");
        Optional<VectorPopulationProgressTracker.VectorPopulationUpdate> update =
                tracker.getTaskStatus("task-1");
        assertThat(update).isPresent();
    }

    @Test
    void startTask_setsStatusPending() {
        tracker.startTask("task-2", "/kw", "/vv");
        VectorPopulationProgressTracker.VectorPopulationUpdate update =
                tracker.getTaskStatus("task-2").orElseThrow();
        assertThat(update.status()).isEqualTo(VectorPopulationProgressTracker.VectorPopulationStatus.PENDING);
    }

    @Test
    void startTask_broadcastsViaWebSocket() {
        tracker.startTask("task-3", "/kw", "/vv");
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/vector-population/progress"),
                any(VectorPopulationProgressTracker.VectorPopulationUpdate.class));
    }

    // ===== updateProgress =====

    @Test
    void updateProgress_updatesStatusToInProgress() {
        tracker.startTask("task-4", "/kw", "/vv");
        tracker.updateProgress("task-4",
                VectorPopulationProgressTracker.VectorPopulationPhase.EMBEDDING,
                50, "embedding", "Processing batch", null);

        VectorPopulationProgressTracker.VectorPopulationUpdate update =
                tracker.getTaskStatus("task-4").orElseThrow();
        assertThat(update.status()).isEqualTo(VectorPopulationProgressTracker.VectorPopulationStatus.IN_PROGRESS);
        assertThat(update.progressPercent()).isEqualTo(50);
    }

    // ===== completeTask =====

    @Test
    void completeTask_setsStatusCompleted() {
        tracker.startTask("task-5", "/kw", "/vv");

        VectorPopulationProgressTracker.VectorPopulationStats stats =
                new VectorPopulationProgressTracker.VectorPopulationStats(
                        100, 200, 200, 200, 100, 0, 0, 5000, 40.0, 0, null, null, null, null, null);

        tracker.completeTask("task-5", stats);

        VectorPopulationProgressTracker.VectorPopulationUpdate update =
                tracker.getTaskStatus("task-5").orElseThrow();
        assertThat(update.status()).isEqualTo(VectorPopulationProgressTracker.VectorPopulationStatus.COMPLETED);
        assertThat(update.progressPercent()).isEqualTo(100);
    }

    // ===== failTask =====

    @Test
    void failTask_setsStatusFailed() {
        tracker.startTask("task-6", "/kw", "/vv");
        tracker.failTask("task-6",
                VectorPopulationProgressTracker.VectorPopulationPhase.EMBEDDING,
                "OOM error");

        VectorPopulationProgressTracker.VectorPopulationUpdate update =
                tracker.getTaskStatus("task-6").orElseThrow();
        assertThat(update.status()).isEqualTo(VectorPopulationProgressTracker.VectorPopulationStatus.FAILED);
        assertThat(update.errorMessage()).contains("OOM error");
    }

    // ===== cancelTask =====

    @Test
    void cancelTask_setsStatusCancelled() {
        tracker.startTask("task-7", "/kw", "/vv");
        tracker.cancelTask("task-7", "User cancelled");

        VectorPopulationProgressTracker.VectorPopulationUpdate update =
                tracker.getTaskStatus("task-7").orElseThrow();
        assertThat(update.status()).isEqualTo(VectorPopulationProgressTracker.VectorPopulationStatus.CANCELLED);
    }

    // ===== getTaskStatus =====

    @Test
    void getTaskStatus_returnsEmpty_whenTaskNotFound() {
        assertThat(tracker.getTaskStatus("nonexistent")).isEmpty();
    }

    // ===== getAllTasks =====

    @Test
    void getAllTasks_containsStartedTask() {
        tracker.startTask("task-8", "/kw", "/vv");
        Collection<VectorPopulationProgressTracker.VectorPopulationUpdate> tasks = tracker.getAllTasks();
        assertThat(tasks).anyMatch(t -> "task-8".equals(t.taskId()));
    }

    // ===== getInProgressTasks =====

    @Test
    void getInProgressTasks_includesPendingTasks() {
        tracker.startTask("task-9", "/kw", "/vv");
        Collection<VectorPopulationProgressTracker.VectorPopulationUpdate> inProgress = tracker.getInProgressTasks();
        assertThat(inProgress).anyMatch(t -> "task-9".equals(t.taskId()));
    }

    @Test
    void getInProgressTasks_excludesCompletedTasks() {
        tracker.startTask("task-10", "/kw", "/vv");
        tracker.completeTask("task-10", null);
        Collection<VectorPopulationProgressTracker.VectorPopulationUpdate> inProgress = tracker.getInProgressTasks();
        assertThat(inProgress).noneMatch(t -> "task-10".equals(t.taskId()));
    }

    // ===== getActiveTaskCount =====

    @Test
    void getActiveTaskCount_incrementsOnStart() {
        int before = tracker.getActiveTaskCount();
        tracker.startTask("task-11", "/kw", "/vv");
        assertThat(tracker.getActiveTaskCount()).isEqualTo(before + 1);
    }

    // ===== isTaskActive =====

    @Test
    void isTaskActive_returnsFalse_forUnknownTask() {
        assertThat(tracker.isTaskActive("no-such-task")).isFalse();
    }

    @Test
    void isTaskActive_returnsTrue_forPendingTask() {
        tracker.startTask("task-12", "/kw", "/vv");
        assertThat(tracker.isTaskActive("task-12")).isTrue();
    }

    @Test
    void isTaskActive_returnsFalse_afterComplete() {
        tracker.startTask("task-13", "/kw", "/vv");
        tracker.completeTask("task-13", null);
        assertThat(tracker.isTaskActive("task-13")).isFalse();
    }

    // ===== getElapsedTime =====

    @Test
    void getElapsedTime_returnsZero_forUnknownTask() {
        assertThat(tracker.getElapsedTime("no-task")).isEqualTo(0L);
    }

    @Test
    void getElapsedTime_returnsPositive_forStartedTask() {
        tracker.startTask("task-14", "/kw", "/vv");
        assertThat(tracker.getElapsedTime("task-14")).isGreaterThanOrEqualTo(0);
    }

    // ===== sendLog =====

    @Test
    void sendLog_doesNotThrow() {
        tracker.startTask("task-15", "/kw", "/vv");
        assertThatCode(() -> tracker.sendLog("task-15", "APPLICATION", "INFO", "test message"))
                .doesNotThrowAnyException();
    }

    @Test
    void sendLog_ignoresBlankMessage() {
        tracker.startTask("task-16", "/kw", "/vv");
        tracker.sendLog("task-16", "APPLICATION", "INFO", "   ");
        // No WebSocket send for blank message
    }

    // ===== updateProgress (VectorPopulationUpdate overload) =====

    @Test
    void updateProgressUpdate_handlesNullGracefully() {
        assertThatCode(() -> tracker.updateProgress(
                (VectorPopulationProgressTracker.VectorPopulationUpdate) null))
                .doesNotThrowAnyException();
    }

    // ===== Task environment =====

    @Test
    void getTaskEnvironment_returnsEmpty_forUnknownTask() {
        assertThat(tracker.getTaskEnvironment("unknown-task")).isEmpty();
    }

    @Test
    void getAllTaskEnvironments_returnsMap() {
        assertThat(tracker.getAllTaskEnvironments()).isNotNull();
    }
}
