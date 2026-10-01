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

package ai.kompile.staging.subprocess;

import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.staging.domain.TrainingJobHistory.FailureReason;
import ai.kompile.staging.service.TrainingJobHistoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A training job gets one outcome, reported once: the first of the child's own report, a cancel, a
 * stall, a timeout, a rejected report, shutdown, or the child's exit. Nothing written after the
 * outcome reopens the job. The run then ends once: its args file is deleted, its subprocess log is
 * closed with the outcome, and no child is left running. {@link FakeTrainingChild} runs through the
 * launcher's real command line and reports through the real reporter.
 *
 * <p>libnd4j prints to fd 1 beneath the child's System.setOut redirect. Native text without a newline
 * used to hide the report written after it: the launcher took the line for plain output, and a
 * completed job failed as "exited without reporting completion". The launcher now gives the reports a
 * pipe of their own and sends fd 1 to stderr, where it still finds the reports of a child that writes
 * them to fd 1.
 *
 * <p>Every kill goes through the child's process handle, which leaves its pipes open: a report the child
 * wrote before it died is still read and logged. {@code Process.destroyForcibly()} closes them, and the
 * launcher's reader then loses whatever it had not read yet.
 */
@Timeout(60)
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "children are found by their command line, which Windows does not report")
class TrainingSubprocessLauncherVerdictTest {

    /** History writes that give a job its outcome. */
    private static final Set<String> OUTCOME_WRITES =
            Set.of("markCompleted", "markFailed", "markCancelled", "markMemoryKilled");
    private static final String CHILD_FAILURE = "boom: real cause";
    /** Well inside the 5 s the launcher waits for its child's output once the child has exited. */
    private static final long SLOW_REPORT_READ_MS = 500;

    @TempDir
    Path tmp;

    private final TrainingJobHistoryService history = mock(TrainingJobHistoryService.class);
    private final List<String> launched = new ArrayList<>();
    private String userHome;
    private String userDir;
    private TrainingSubprocessLauncher launcher;
    /** How late the launcher reads each report of its child; see {@link #slowReportReader()}. */
    private volatile long reportReadDelayMs;
    /** A report the launcher's reader is held on until {@link #readerReleased}; see {@link #slowReportReader()}. */
    private volatile String heldReport;
    private final CountDownLatch readerHeld = new CountDownLatch(1);
    private final CountDownLatch readerReleased = new CountDownLatch(1);
    /** Where the test and a {@code held-} scenario of {@link FakeTrainingChild} signal each other. */
    private Path handshake;

    @BeforeEach
    void setUp() throws Exception {
        // Subprocess logs go under user.dir; keep them, and anything under user.home, in the temp dir
        userHome = System.getProperty("user.home");
        userDir = System.getProperty("user.dir");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        System.setProperty("user.dir", Files.createDirectories(tmp.resolve("work/.kompile")).getParent().toString());
        handshake = Files.createDirectories(tmp.resolve("handshake"));

        launcher = new TrainingSubprocessLauncher(slowReportReader(), history);
        launcher.mainClass = FakeTrainingChild.class.getName();
        launcher.subprocessHeapSize = "64m";
        launcher.staleTimeoutMs = 120_000L;
    }

    @AfterEach
    void tearDown() throws IOException {
        // A failed test may still hold a reader, which shutdown would wait for
        readerReleased.countDown();
        try {
            launcher.shutdown();
        } finally {
            System.setProperty("user.home", userHome);
            System.setProperty("user.dir", userDir);
            // A failing test may leave args files behind; they are removed by name only
            for (String jobId : launched) {
                for (String name : argsFilesOf(jobId)) {
                    Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), name));
                }
            }
        }
    }

    @Test
    void childFailureIsTheOutcomeNotItsExit() throws Exception {
        // The child exits while the launcher is still reading its report
        reportReadDelayMs = SLOW_REPORT_READ_MS;
        String jobId = launch("fail");

        awaitRunEnded(jobId);
        InOrder order = inOrder(history);
        order.verify(history).markRunning(jobId);
        order.verify(history).markFailed(jobId, CHILD_FAILURE, null, FailureReason.TRAINING_ERROR);
        verify(history, never()).markFailed(eq(jobId), startsWith("Subprocess"), any(), any());
        assertOneOutcomeLast(jobId, "markFailed");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("FAILED", status.getStatus());
        assertEquals(CHILD_FAILURE, status.getError());
        assertNotNull(status.getCompletedAt());
        assertEquals("FAILED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void completionIsTheOutcome() throws Exception {
        // The child exits while the launcher is still reading its report
        reportReadDelayMs = SLOW_REPORT_READ_MS;
        String jobId = launch("complete");

        awaitRunEnded(jobId);
        verify(history).markCompleted(jobId, 0.5, 0.6, 10L, "/tmp/out");
        assertOneOutcomeLast(jobId, "markCompleted");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("COMPLETED", status.getStatus());
        assertEquals("/tmp/out", status.getOutputModelPath());
        assertNull(status.getError());
        assertEquals("COMPLETED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void nativeOutputGoesToStderrAndTheReportToItsOwnPipe() throws Exception {
        String jobId = launch("native-then-complete");

        awaitRunEnded(jobId);
        verify(history).markCompleted(jobId, 0.5, 0.6, 10L, "/tmp/out");
        assertOneOutcomeLast(jobId, "markCompleted");
        assertEquals("COMPLETED", logEndStateOf(jobId));

        List<String> stderr = loggedLinesOf(jobId, "STDERR");
        List<String> stdout = loggedLinesOf(jobId, "STDOUT");
        assertTrue(stderr.stream().anyMatch(line -> line.contains("[native] no newline")), "stderr: " + stderr);
        assertTrue(stderr.stream().noneMatch(line -> line.contains(TrainingSubprocessMessage.MESSAGE_PREFIX)),
                "stderr: " + stderr);
        assertFalse(stdout.isEmpty(), "the report never reached stdout");
        assertTrue(stdout.stream().allMatch(line -> line.startsWith(TrainingSubprocessMessage.MESSAGE_PREFIX)),
                "stdout: " + stdout);
    }

    @Test
    void completionBehindNativeOutputOnTheSameLineIsTheOutcome() throws Exception {
        // What the report pipe of a child started without the protocol channel holds
        String jobId = launch("glued-then-complete");

        awaitRunEnded(jobId);
        verify(history).markCompleted(jobId, 0.5, 0.6, 10L, "/tmp/out");
        assertOneOutcomeLast(jobId, "markCompleted");
        assertEquals("COMPLETED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("COMPLETED", logEndStateOf(jobId));
    }

    @Test
    void outOfMemoryTextInFrontOfAReportIsStillEvidence() throws Exception {
        String jobId = launch("glued-oom-then-crash");

        awaitRunEnded(jobId);
        verify(history).markMemoryKilled(jobId, 100.0);
        assertOneOutcomeLast(jobId, "markMemoryKilled");
        assertEquals("Subprocess ran out of memory (exit code 134)", launcher.getJobStatus(jobId).getError());
    }

    @Test
    void aChildThatWritesItsReportsToFd1IsReadFromStderr() throws Exception {
        // A child from before the protocol channel: its reports reach stderr behind native text
        String jobId = launch("legacy-native-then-complete");

        awaitRunEnded(jobId);
        verify(history).markCompleted(jobId, 0.5, 0.6, 10L, "/tmp/out");
        assertOneOutcomeLast(jobId, "markCompleted");
        assertEquals("COMPLETED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("COMPLETED", logEndStateOf(jobId));
    }

    @Test
    void outOfMemoryTextInFrontOfAReportOnStderrIsStillEvidence() throws Exception {
        String jobId = launch("legacy-oom-then-crash");

        awaitRunEnded(jobId);
        verify(history).markMemoryKilled(jobId, 100.0);
        assertOneOutcomeLast(jobId, "markMemoryKilled");
        assertEquals("Subprocess ran out of memory (exit code 134)", launcher.getJobStatus(jobId).getError());
    }

    @Test
    void aReportQuotedOnStderrIsNotTheOutcome() throws Exception {
        // The child writes its reports to the protocol channel, so nothing on its stderr is one
        String jobId = launch("quoted-report-then-exit0");

        awaitRunEnded(jobId);
        verify(history, never()).markCompleted(eq(jobId), anyDouble(), anyDouble(), anyLong(), any());
        verify(history).markFailed(jobId, "Subprocess exited without reporting completion", null,
                FailureReason.TRAINING_ERROR);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", logEndStateOf(jobId));
    }

    @Test
    void cleanExitWithoutCompletionFails() throws Exception {
        String jobId = launch("exit0-silent");

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess exited without reporting completion", null,
                FailureReason.TRAINING_ERROR);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("FAILED", logEndStateOf(jobId));
    }

    @Test
    void nonZeroExitFailsWithItsCode() throws Exception {
        String jobId = launch("exit7");

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess exited with code 7", null, FailureReason.TRAINING_ERROR);
        assertOneOutcomeLast(jobId, "markFailed");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("FAILED", status.getStatus());
        assertEquals("Subprocess exited with code 7", status.getError());
        assertEquals("FAILED", logEndStateOf(jobId));
    }

    @Test
    void outOfMemoryExitIsMemoryKilled() throws Exception {
        // HotSpot prints its -XX:+ExitOnOutOfMemoryError notice on fd 1, which reaches stderr, then exits
        // with code 3, while the launcher is still reading the report written before it.
        reportReadDelayMs = SLOW_REPORT_READ_MS;
        String jobId = launch("oom");

        awaitRunEnded(jobId);
        verify(history).markMemoryKilled(jobId, 100.0);
        assertOneOutcomeLast(jobId, "markMemoryKilled");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("MEMORY_KILLED", status.getStatus());
        assertEquals("Subprocess ran out of memory (exit code 3)", status.getError());
        assertEquals("OOM", logEndStateOf(jobId));
    }

    @Test
    void progressBehindTheOutcomeDoesNotReopenTheJob() throws Exception {
        String jobId = launch("fail-then-progress");

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, CHILD_FAILURE, null, FailureReason.TRAINING_ERROR);
        verify(history, never()).updateProgress(eq(jobId), anyInt(), anyLong(), anyDouble(), anyDouble());
        assertOneOutcomeLast(jobId, "markFailed");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("FAILED", status.getStatus());
        assertEquals(CHILD_FAILURE, status.getError());
    }

    @Test
    void cancelIsTheOutcomeAndStopsTheChild() throws Exception {
        String jobId = launch("progress-flood");
        awaitStatus(jobId, "TRAINING");

        assertTrue(launcher.cancelTraining(jobId));
        assertFalse(launcher.cancelTraining(jobId), "a job is cancelled once");
        awaitRunEnded(jobId);
        assertFalse(launcher.cancelTraining(jobId), "an ended job has nothing to cancel");

        InOrder order = inOrder(history);
        order.verify(history).markRunning(jobId);
        order.verify(history).markCancelled(jobId, "Cancelled by user");
        assertOneOutcomeLast(jobId, "markCancelled");
        assertEquals("CANCELLED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("CANCELLED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void stalledChildFailsOnceAndIsKilled() throws Exception {
        launcher.staleTimeoutMs = 1_000L;
        String jobId = launch("no-heartbeat");
        Thread.sleep(1_500);

        launcher.checkForStaleProcesses();

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess stalled (no heartbeat)", null, FailureReason.TIMEOUT);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("FAILED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void timedOutChildFailsOnceAndIsKilled() throws Exception {
        launcher.maxRunMillis = 1_500;
        String jobId = launch("hang");

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess timed out after 1500 ms", null, FailureReason.TIMEOUT);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", launcher.getJobStatus(jobId).getStatus());
        assertEquals("FAILED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void rejectedReportFailsTheJobAndStopsTheChild() throws Exception {
        // The child would sleep for a minute after its report; the run ends only if it is killed
        String jobId = launch("nonfinite");

        awaitRunEnded(jobId);
        String rejection = "Rejected training subprocess PROGRESS message: metric 'loss' is non-finite: NaN";
        verify(history).markFailed(jobId, rejection, null, FailureReason.TRAINING_ERROR);
        assertOneOutcomeLast(jobId, "markFailed");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("FAILED", status.getStatus());
        assertEquals(rejection, status.getError());
        assertNoChildLeft(jobId);
    }

    @Test
    void failedStartIsTheOutcomeAndLeavesNothingBehind() throws Exception {
        String jobId = newJobId();
        RuntimeException dbDown = new RuntimeException("db down");
        doThrow(dbDown).when(history).createJob(eq(jobId), any(), any(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> launch(jobId, "complete"));

        assertEquals("db down", thrown.getMessage());
        verify(history).markFailed(eq(jobId), eq("Failed to start training subprocess: db down"), same(dbDown),
                eq(FailureReason.IO_ERROR));
        verify(history, never()).markRunning(jobId);
        assertOneOutcomeLast(jobId, "markFailed");

        TrainingJobStatus status = launcher.getJobStatus(jobId);
        assertEquals("FAILED", status.getStatus());
        assertEquals("Failed to start training subprocess: db down", status.getError());
        assertEquals(List.of(), argsFilesOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void shutdownCancelsEveryRunOnceAndRefusesNewOnes() throws Exception {
        String first = launch("hang");
        String second = launch("hang");

        launcher.shutdown();

        for (String jobId : List.of(first, second)) {
            assertEquals(List.of(), argsFilesOf(jobId), "args file of " + jobId);
            verify(history).markCancelled(jobId, "Application shutdown");
            assertOneOutcomeLast(jobId, "markCancelled");
            assertEquals("CANCELLED", launcher.getJobStatus(jobId).getStatus());
            assertEquals("CANCELLED", logEndStateOf(jobId));
            assertNoChildLeft(jobId);
        }

        String later = newJobId();
        assertThrows(IllegalStateException.class, () -> launch(later, "complete"));
        verify(history, never()).createJob(eq(later), any(), any(), any());
        assertEquals(List.of(), argsFilesOf(later));
        assertNoChildLeft(later);
    }

    @Test
    void launchThatShutdownOvertakesIsCancelledBeforeItsChildStarts() throws Exception {
        // The launch passed the first shutdown check and is writing its history row when shutdown
        // takes its snapshot of the running children, so the snapshot cannot hold this job
        String jobId = newJobId();
        CountDownLatch writingHistory = new CountDownLatch(1);
        CountDownLatch shutDown = new CountDownLatch(1);
        doAnswer(invocation -> {
            writingHistory.countDown();
            shutDown.await(20, TimeUnit.SECONDS);
            return null;
        }).when(history).createJob(eq(jobId), any(), any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> launch = executor.submit(() -> {
                launch(jobId, "hang");
                return null;
            });
            assertTrue(writingHistory.await(20, TimeUnit.SECONDS));
            launcher.shutdown();
            shutDown.countDown();

            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> launch.get(20, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, refused.getCause());
        } finally {
            executor.shutdownNow();
        }
        verify(history).markCancelled(jobId, "Application shutdown before start");
        verify(history, never()).markRunning(jobId);
        assertOneOutcomeLast(jobId, "markCancelled");
        assertEquals("CANCELLED", launcher.getJobStatus(jobId).getStatus());
        assertEquals(List.of(), argsFilesOf(jobId));
        assertNoChildLeft(jobId);
    }

    @Test
    void cancelStillReadsWhatTheChildWroteBeforeItDied() throws Exception {
        String jobId = launchWithLastReportUnread("held-then-last-report");

        assertTrue(launcher.cancelTraining(jobId));
        releaseReaderOnceKilled(jobId);

        awaitRunEnded(jobId);
        verify(history).markCancelled(jobId, "Cancelled by user");
        assertOneOutcomeLast(jobId, "markCancelled");
        assertEquals("CANCELLED", logEndStateOf(jobId));
        assertLastReportLogged(jobId);
    }

    @Test
    void stallKillStillReadsWhatTheChildWroteBeforeItDied() throws Exception {
        String jobId = launchWithLastReportUnread("held-then-last-report");
        launcher.staleTimeoutMs = 1L;

        launcher.checkForStaleProcesses();
        releaseReaderOnceKilled(jobId);

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess stalled (no heartbeat)", null, FailureReason.TIMEOUT);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", logEndStateOf(jobId));
        assertLastReportLogged(jobId);
    }

    @Test
    void timeoutKillStillReadsWhatTheChildWroteBeforeItDied() throws Exception {
        // Long enough for the child to start and write both reports first
        launcher.maxRunMillis = 8_000;
        String jobId = launchWithLastReportUnread("held-then-last-report");

        releaseReaderOnceKilled(jobId);

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId, "Subprocess timed out after 8000 ms", null, FailureReason.TIMEOUT);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", logEndStateOf(jobId));
        assertLastReportLogged(jobId);
    }

    @Test
    void rejectionKillStillReadsWhatTheChildWroteBeforeItDied() throws Exception {
        String jobId = launchWithLastReportUnread("held-nonfinite-then-last-report");

        // The reader rejects the first report and kills the child itself, then reads on
        readerReleased.countDown();

        awaitRunEnded(jobId);
        verify(history).markFailed(jobId,
                "Rejected training subprocess PROGRESS message: metric 'loss' is non-finite: NaN", null,
                FailureReason.TRAINING_ERROR);
        assertOneOutcomeLast(jobId, "markFailed");
        assertEquals("FAILED", logEndStateOf(jobId));
        assertNoChildLeft(jobId);
        assertLastReportLogged(jobId);
    }

    @Test
    void shutdownStillReadsWhatTheChildWroteBeforeItDied() throws Exception {
        String jobId = launchWithLastReportUnread("held-then-last-report");

        // Shutdown waits for the run to end, and the run for the held reader
        Thread shutdown = new Thread(launcher::shutdown, "test-shutdown");
        shutdown.start();
        releaseReaderOnceKilled(jobId);
        shutdown.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(shutdown.isAlive(), "shutdown never returned");

        awaitRunEnded(jobId);
        verify(history).markCancelled(jobId, "Application shutdown");
        assertOneOutcomeLast(jobId, "markCancelled");
        assertEquals("CANCELLED", logEndStateOf(jobId));
        assertLastReportLogged(jobId);
    }

    // ==================== helpers ====================

    private String newJobId() {
        String jobId = "train-verdict-" + UUID.randomUUID();
        launched.add(jobId);
        return jobId;
    }

    private String launch(String scenario) throws IOException {
        String jobId = newJobId();
        launch(jobId, scenario);
        return jobId;
    }

    private void launch(String jobId, String scenario) throws IOException {
        launcher.launchSubprocess(TrainingSubprocessArgs.builder()
                .taskId(jobId)
                .trainingType("FINETUNE")
                .modelId("model")
                .datasetId("dataset")
                .option("scenario", scenario)
                .option("handshakeDir", handshake.toString())
                .build(), null);
    }

    /**
     * Launches a {@code held-} scenario: the child writes {@link FakeTrainingChild#FIRST_REPORT}, which
     * holds the launcher's reader, then {@link FakeTrainingChild#LAST_REPORT}. The last report is then in
     * the pipe, unread, until the reader is released.
     */
    private String launchWithLastReportUnread(String scenario) throws Exception {
        heldReport = FakeTrainingChild.FIRST_REPORT;
        String jobId = launch(scenario);
        assertTrue(readerHeld.await(20, TimeUnit.SECONDS), "the launcher never read the first report");
        Files.createFile(handshake.resolve("go"));
        Path written = handshake.resolve("written");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(written) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(written), "the child never wrote its last report");
        return jobId;
    }

    /** Releases the held reader once the child is dead: a kill that closed the pipes has dropped the last report by then. */
    private void releaseReaderOnceKilled(String jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!childrenOf(jobId).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        readerReleased.countDown();
        assertNoChildLeft(jobId);
    }

    /** The report the child wrote just before it was killed reached the job's log. */
    private void assertLastReportLogged(String jobId) throws IOException {
        List<String> stdout = loggedLinesOf(jobId, "STDOUT");
        assertTrue(stdout.stream().anyMatch(line -> line.contains(FakeTrainingChild.LAST_REPORT)),
                "stdout: " + stdout);
    }

    /**
     * The launcher's mapper, reading each report {@link #reportReadDelayMs} late, as a busy launcher
     * can: the child is then gone before its last lines are read, and the launcher must read them
     * before it judges the exit. It holds the reader on {@link #heldReport} until the test releases it.
     */
    private ObjectMapper slowReportReader() {
        return new ObjectMapper() {
            @Override
            public <T> T readValue(String content, Class<T> valueType) throws JsonProcessingException {
                try {
                    String held = heldReport;
                    if (held != null && content.contains(held)) {
                        readerHeld.countDown();
                        readerReleased.await(30, TimeUnit.SECONDS);
                    }
                    Thread.sleep(reportReadDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.readValue(content, valueType);
            }
        };
    }

    /** A run has ended once its args file is gone: that is the last thing it does before closing its streams. */
    private static void awaitRunEnded(String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!argsFilesOf(jobId).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of(), argsFilesOf(jobId), "the run of " + jobId + " never ended");
    }

    private void awaitStatus(String jobId, String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            TrainingJobStatus status = launcher.getJobStatus(jobId);
            if (status != null && state.equals(status.getStatus())) {
                return;
            }
            Thread.sleep(10);
        }
        fail(jobId + " never reached " + state + "; it is " + launcher.getJobStatus(jobId));
    }

    /** The history writes for a job, in the order they were made. */
    private List<String> historyWritesOf(String jobId) {
        return mockingDetails(history).getInvocations().stream()
                .filter(invocation -> invocation.getArguments().length > 0
                        && jobId.equals(invocation.getArguments()[0]))
                .map(invocation -> invocation.getMethod().getName())
                .toList();
    }

    /** A job's history holds exactly one outcome, and nothing was written after it. */
    private void assertOneOutcomeLast(String jobId, String outcomeWrite) {
        List<String> writes = historyWritesOf(jobId);
        assertEquals(1, writes.stream().filter(OUTCOME_WRITES::contains).count(),
                "outcome writes of " + jobId + ": " + writes);
        assertEquals(outcomeWrite, writes.get(writes.size() - 1), "history writes of " + jobId + ": " + writes);
    }

    private static void assertNoChildLeft(String jobId) {
        assertEquals(List.of(), childrenOf(jobId), "children of " + jobId + " still running");
    }

    /** A job's running children; a dead child, reaped or not, has no command line left to match. */
    private static List<Long> childrenOf(String jobId) {
        return ProcessHandle.current().children()
                .filter(ProcessHandle::isAlive)
                .filter(child -> commandLineOf(child).contains("training-args-" + jobId + "-"))
                .map(ProcessHandle::pid)
                .toList();
    }

    /**
     * A child's whole command line. {@code ProcessHandle.Info} stops at one page, and a child's
     * classpath alone is longer than that, so the args file named after it would never be seen.
     */
    private static String commandLineOf(ProcessHandle process) {
        try {
            return new String(Files.readAllBytes(Path.of("/proc", Long.toString(process.pid()), "cmdline")),
                    StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException noProcFs) {
            return process.info().commandLine().orElse("");
        }
    }

    /** The names of a job's args files left in the temp dir. */
    private static List<String> argsFilesOf(String jobId) throws IOException {
        Pattern argsFile = Pattern.compile(Pattern.quote("training-args-" + jobId + "-") + "[0-9]+\\.json");
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> argsFile.matcher(name).matches())
                    .toList();
        }
    }

    /** The lines a job's subprocess log holds from one stream, once its run has ended. */
    private List<String> loggedLinesOf(String jobId, String stream) throws IOException {
        Path logFile = tmp.resolve("work/.kompile/logs/subprocesses/training/" + jobId + ".log");
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = new ArrayList<>();
        for (String record : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
            JsonNode node = mapper.readTree(record);
            if (stream.equals(node.path("stream").asText())) {
                lines.add(node.path("line").asText());
            }
        }
        return lines;
    }

    /** The state a job's subprocess log ended in, once its run has ended. */
    private String logEndStateOf(String jobId) throws Exception {
        Path meta = tmp.resolve("work/.kompile/logs/subprocesses/training/" + jobId + ".meta.json");
        ObjectMapper mapper = new ObjectMapper();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String state = null;
        while (System.nanoTime() < deadline) {
            try {
                state = mapper.readTree(meta.toFile()).path("state").asText(null);
                if (state != null && !"RUNNING".equals(state)) {
                    return state;
                }
            } catch (IOException notWrittenYet) {
                // Missing, or caught mid-write
            }
            Thread.sleep(20);
        }
        return state;
    }
}
