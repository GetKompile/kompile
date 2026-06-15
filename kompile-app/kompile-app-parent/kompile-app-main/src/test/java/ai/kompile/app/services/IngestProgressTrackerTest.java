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
package ai.kompile.app.services;

import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestProgressTrackerTest {

    private SimpMessagingTemplate messagingTemplate;
    private IngestProgressTracker tracker;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        tracker = new IngestProgressTracker(messagingTemplate, null);
    }

    @AfterEach
    void tearDown() {
        tracker.destroy();
    }

    // --- generateTaskId ---

    @Test
    void generateTaskIdReturnsUniqueIds() {
        String id1 = tracker.generateTaskId();
        String id2 = tracker.generateTaskId();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    // --- startTask ---

    @Test
    void startTaskReturnsTrue() {
        boolean result = tracker.startTask("task-1", "report.pdf");
        assertTrue(result);
    }

    @Test
    void startTaskSendsQueuedWebSocketMessage() {
        tracker.startTask("task-1", "report.pdf");
        verify(messagingTemplate).convertAndSend(eq("/topic/ingest/task-1"), any(IngestProgressUpdate.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/ingest/all"), any(IngestProgressUpdate.class));
    }

    @Test
    void startTaskIsIdempotent() {
        assertTrue(tracker.startTask("task-1", "report.pdf"));
        assertFalse(tracker.startTask("task-1", "report.pdf"));
    }

    @Test
    void startTaskTracksFactSheetId() {
        tracker.startTask("task-1", "report.pdf", 42L);
        assertEquals(42L, tracker.getTaskFactSheetId("task-1"));
    }

    @Test
    void startTaskWithNullFactSheetIdDoesNotTrack() {
        tracker.startTask("task-1", "report.pdf", null);
        assertNull(tracker.getTaskFactSheetId("task-1"));
    }

    // --- getTaskStatus ---

    @Test
    void getTaskStatusAfterStart() {
        tracker.startTask("task-1", "report.pdf");
        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals("task-1", status.get().taskId());
        assertEquals("report.pdf", status.get().fileName());
        assertEquals(IngestStatus.PENDING, status.get().status());
    }

    @Test
    void getTaskStatusReturnsEmptyForUnknownTask() {
        assertTrue(tracker.getTaskStatus("unknown").isEmpty());
    }

    // --- updateProgress ---

    @Test
    void updateProgressSetsPhaseAndPercent() {
        tracker.startTask("task-1", "report.pdf");
        IngestStats stats = IngestStats.builder().documentsLoaded(5).build();
        tracker.updateProgress("task-1", "report.pdf", IngestPhase.CHUNKING, 50, "Chunking", "Processing...", stats);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestPhase.CHUNKING, status.get().phase());
        assertEquals(50, status.get().progressPercent());
        assertEquals(IngestStatus.IN_PROGRESS, status.get().status());
    }

    @Test
    void updateProgressWithPrebuiltUpdate() {
        tracker.startTask("task-1", "report.pdf", 99L);

        IngestProgressUpdate update = IngestProgressUpdate.progress(
                "task-1", "report.pdf", IngestPhase.EMBEDDING, 75, "Embedding", "Batch 3/4",
                IngestStats.builder().chunksEmbedded(300).build(), null);
        tracker.updateProgress(update);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestPhase.EMBEDDING, status.get().phase());
        // factSheetId should be injected from tracked data
        assertEquals(99L, status.get().factSheetId());
    }

    @Test
    void updateProgressIgnoresNullUpdate() {
        tracker.updateProgress((IngestProgressUpdate) null);
        // No exception
    }

    @Test
    void updateProgressTracksNewTaskAutomatically() {
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                "new-task", "new.pdf", IngestPhase.LOADING, 10, "Loading", "...",
                IngestStats.builder().build(), null);
        tracker.updateProgress(update);

        assertTrue(tracker.getTaskStatus("new-task").isPresent());
    }

    // --- completeTask ---

    @Test
    void completeTaskSetsCompletedStatus() {
        tracker.startTask("task-1", "report.pdf");
        IngestStats stats = IngestStats.builder()
                .documentsLoaded(10)
                .chunksCreated(50)
                .totalProcessingTimeMs(5000L)
                .build();
        tracker.completeTask("task-1", "report.pdf", stats);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.COMPLETED, status.get().status());
    }

    @Test
    void completeTaskIncludesFactSheetId() {
        tracker.startTask("task-1", "report.pdf", 42L);
        IngestStats stats = IngestStats.builder().documentsLoaded(1).build();
        tracker.completeTask("task-1", "report.pdf", stats);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(42L, status.get().factSheetId());
    }

    // --- failTask ---

    @Test
    void failTaskSetsFailedStatus() {
        tracker.startTask("task-1", "report.pdf");
        tracker.failTask("task-1", "report.pdf", IngestPhase.LOADING, "File not found");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.FAILED, status.get().status());
        assertEquals(FailureReason.UNKNOWN, status.get().failureReason());
    }

    @Test
    void failTaskWithSpecificReason() {
        tracker.startTask("task-1", "report.pdf");
        tracker.failTask("task-1", "report.pdf", IngestPhase.EMBEDDING, "OOM", FailureReason.OUT_OF_MEMORY);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(FailureReason.OUT_OF_MEMORY, status.get().failureReason());
    }

    // --- failTaskOutOfMemory ---

    @Test
    void failTaskOutOfMemorySetsOomFailure() {
        tracker.startTask("task-1", "report.pdf");
        tracker.failTaskOutOfMemory("task-1", "report.pdf", IngestPhase.EMBEDDING, "Java heap space");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.FAILED, status.get().status());
        assertEquals(FailureReason.OUT_OF_MEMORY, status.get().failureReason());
    }

    // --- cancelTask ---

    @Test
    void cancelTaskSetsCancelledStatus() {
        tracker.startTask("task-1", "report.pdf");
        IngestStats stats = IngestStats.builder().documentsLoaded(3).build();
        tracker.cancelTask("task-1", "report.pdf", IngestPhase.CHUNKING, "User requested", stats);

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.CANCELLED, status.get().status());
    }

    // --- isTaskActive ---

    @Test
    void isTaskActiveReturnsTrueForPendingTask() {
        tracker.startTask("task-1", "file.pdf");
        assertTrue(tracker.isTaskActive("task-1"));
    }

    @Test
    void isTaskActiveReturnsFalseForCompletedTask() {
        tracker.startTask("task-1", "file.pdf");
        tracker.completeTask("task-1", "file.pdf", IngestStats.builder().build());
        assertFalse(tracker.isTaskActive("task-1"));
    }

    @Test
    void isTaskActiveReturnsFalseForUnknownTask() {
        assertFalse(tracker.isTaskActive("unknown"));
    }

    // --- getActiveTaskCount ---

    @Test
    void getActiveTaskCountReflectsActiveOnly() {
        tracker.startTask("task-1", "a.pdf");
        tracker.startTask("task-2", "b.pdf");
        tracker.startTask("task-3", "c.pdf");
        assertEquals(3, tracker.getActiveTaskCount());

        tracker.completeTask("task-1", "a.pdf", IngestStats.builder().build());
        assertEquals(2, tracker.getActiveTaskCount());

        tracker.failTask("task-2", "b.pdf", IngestPhase.LOADING, "Error");
        assertEquals(1, tracker.getActiveTaskCount());
    }

    // --- getAllTasks / getInProgressTasks ---

    @Test
    void getAllTasksReturnsAllTrackedTasks() {
        tracker.startTask("task-1", "a.pdf");
        tracker.startTask("task-2", "b.pdf");
        tracker.completeTask("task-1", "a.pdf", IngestStats.builder().build());

        Collection<IngestProgressUpdate> all = tracker.getAllTasks();
        assertEquals(2, all.size());
    }

    @Test
    void getInProgressTasksExcludesCompleted() {
        tracker.startTask("task-1", "a.pdf");
        tracker.startTask("task-2", "b.pdf");
        tracker.completeTask("task-1", "a.pdf", IngestStats.builder().build());

        Collection<IngestProgressUpdate> inProgress = tracker.getInProgressTasks();
        assertEquals(1, inProgress.size());
    }

    // --- getElapsedTime ---

    @Test
    void getElapsedTimeReturnsZeroForUnknownTask() {
        assertEquals(0, tracker.getElapsedTime("unknown"));
    }

    @Test
    void getElapsedTimeReturnsPositiveForStartedTask() throws InterruptedException {
        tracker.startTask("task-1", "file.pdf");
        Thread.sleep(50);
        assertTrue(tracker.getElapsedTime("task-1") >= 50);
    }

    // --- sendLog ---

    @Test
    void sendLogStdoutSendsToWebSocket() {
        tracker.startTask("task-1", "file.pdf");
        tracker.sendLog("task-1", "STDOUT", "Loading document...");

        verify(messagingTemplate).convertAndSend(eq("/topic/ingest/task-1/logs"), any(IngestLogEntry.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/ingest/logs"), any(IngestLogEntry.class));
    }

    @Test
    void sendLogStderrSendsToWebSocket() {
        tracker.startTask("task-1", "file.pdf");
        tracker.sendLog("task-1", "STDERR", "Warning: large file");

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/ingest/task-1/logs"), any(IngestLogEntry.class));
    }

    @Test
    void sendLogIgnoresBlankMessage() {
        tracker.sendLog("task-1", "STDOUT", "");
        tracker.sendLog("task-1", "STDOUT", null);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(IngestLogEntry.class));
    }

    @Test
    void sendLogWithLevelSendsToWebSocket() {
        tracker.sendLog("task-1", "SYSTEM", "WARN", "Low memory");
        verify(messagingTemplate).convertAndSend(eq("/topic/ingest/task-1/logs"), any(IngestLogEntry.class));
    }

    // --- notifyRestartScheduled ---

    @Test
    void notifyRestartScheduledSendsUpdate() {
        tracker.startTask("task-1", "big.pdf");
        tracker.notifyRestartScheduled("task-1", "big.pdf", IngestPhase.EMBEDDING,
                1, 3, System.currentTimeMillis() + 30000, "4g", true,
                4, 4, "High peak memory usage");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.IN_PROGRESS, status.get().status());
        assertNotNull(status.get().restartInfo());
        assertEquals(1, status.get().restartInfo().attemptNumber());
        assertEquals(3, status.get().restartInfo().maxAttempts());
        assertTrue(status.get().restartInfo().restartScheduled());
    }

    // --- notifyRestartExecuting ---

    @Test
    void notifyRestartExecutingSendsUpdate() {
        tracker.startTask("task-1", "big.pdf");
        tracker.notifyRestartExecuting("task-1", "big.pdf", 2, 3, "6g");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-1");
        assertTrue(status.isPresent());
        assertNotNull(status.get().restartInfo());
        assertEquals(2, status.get().restartInfo().attemptNumber());
        assertFalse(status.get().restartInfo().restartScheduled());
    }

    // --- TaskProgressContext ---

    @Test
    void taskProgressContextTracksState() {
        IngestProgressTracker.TaskProgressContext ctx = tracker.createContext("task-ctx", "doc.pdf");
        assertEquals("task-ctx", ctx.getTaskId());
        assertEquals("doc.pdf", ctx.getFileName());

        ctx.setDocumentsLoaded(5);
        ctx.setChunksCreated(20);
        ctx.setLoaderUsed("pdf-loader");
        ctx.setChunkerUsed("recursive");
        ctx.updateProgress(IngestPhase.CHUNKING, 50, "Chunking", "50%");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-ctx");
        assertTrue(status.isPresent());
        assertEquals(IngestPhase.CHUNKING, status.get().phase());
    }

    @Test
    void taskProgressContextComplete() {
        IngestProgressTracker.TaskProgressContext ctx = tracker.createContext("task-ctx", "doc.pdf");
        ctx.setDocumentsLoaded(5);
        ctx.setChunksCreated(20);
        ctx.complete();

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-ctx");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.COMPLETED, status.get().status());
    }

    @Test
    void taskProgressContextFail() {
        IngestProgressTracker.TaskProgressContext ctx = tracker.createContext("task-ctx", "doc.pdf");
        ctx.fail(IngestPhase.LOADING, "Parse error");

        Optional<IngestProgressUpdate> status = tracker.getTaskStatus("task-ctx");
        assertTrue(status.isPresent());
        assertEquals(IngestStatus.FAILED, status.get().status());
    }

    @Test
    void createContextWithFactSheetId() {
        IngestProgressTracker.TaskProgressContext ctx = tracker.createContext("task-ctx", "doc.pdf", 77L);
        assertEquals(77L, tracker.getTaskFactSheetId("task-ctx"));
    }

    // --- null messagingTemplate ---

    @Test
    void worksWithoutWebSocket() {
        IngestProgressTracker noWs = new IngestProgressTracker(null, null);
        try {
            noWs.startTask("task-nows", "file.pdf");
            noWs.updateProgress("task-nows", "file.pdf", IngestPhase.LOADING, 10, "Loading", "...", null);
            noWs.completeTask("task-nows", "file.pdf", IngestStats.builder().build());

            Optional<IngestProgressUpdate> status = noWs.getTaskStatus("task-nows");
            assertTrue(status.isPresent());
            assertEquals(IngestStatus.COMPLETED, status.get().status());
        } finally {
            noWs.destroy();
        }
    }
}
