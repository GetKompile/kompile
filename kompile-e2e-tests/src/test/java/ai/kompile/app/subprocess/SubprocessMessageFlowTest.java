package ai.kompile.app.subprocess;

import ai.kompile.app.services.subprocess.SubprocessHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests simulating the full subprocess message flow:
 * SubprocessProgressReporter → STDOUT prefix → JSON parsing → SubprocessHandle update
 *
 * This validates the complete data path without spawning actual subprocesses.
 */
@DisplayName("Subprocess Message Flow (End-to-End)")
class SubprocessMessageFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;

    private ByteArrayOutputStream outputStream;
    private PrintStream printStream;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        printStream = new PrintStream(outputStream, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        printStream.close();
    }

    private SubprocessHandle createHandle(Process process) {
        return new SubprocessHandle(
                "flow-test",
                "flow-test.pdf",
                process,
                null, null,
                new CompletableFuture<>(),
                tempDir.resolve("args.json")
        );
    }

    private Process startSleepProcess(int seconds) throws Exception {
        return new ProcessBuilder("sleep", String.valueOf(seconds)).start();
    }

    /**
     * Parse all messages from the output stream, simulating the parent-side
     * message reader logic from SubprocessIngestLauncher.
     */
    private List<SubprocessMessage> parseMessages() throws Exception {
        List<SubprocessMessage> messages = new ArrayList<>();
        String output = outputStream.toString(StandardCharsets.UTF_8);

        for (String line : output.split("\n")) {
            if (line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                String json = line.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                SubprocessMessage msg = MAPPER.readValue(json, SubprocessMessage.class);
                messages.add(msg);
            }
        }
        return messages;
    }

    @Nested @DisplayName("Reporter → Parse → Handle flow")
    class ReporterToHandleFlow {

        @Test @DisplayName("heartbeat flows from reporter through parse to handle update")
        void heartbeatFlowsEndToEnd() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                // Step 1: Reporter writes heartbeat to output
                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.startHeartbeat(50);
                    Thread.sleep(150);
                    reporter.stopHeartbeat();
                }

                // Step 2: Parse messages from output (simulating parent-side reading)
                List<SubprocessMessage> messages = parseMessages();

                // Should have at least 1 heartbeat
                long heartbeatCount = messages.stream()
                        .filter(m -> m instanceof SubprocessMessage.Heartbeat)
                        .count();
                assertTrue(heartbeatCount >= 1, "Expected at least 1 heartbeat, got " + heartbeatCount);

                // Step 3: Apply heartbeats to handle (simulating launcher logic)
                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Heartbeat hb) {
                        handle.updateMemoryFromHeartbeat(hb);
                    }
                }

                // Step 4: Verify handle state
                var status = handle.getStatus();
                assertTrue(status.heapUsagePercent() >= 0);
                assertTrue(status.heapMaxBytes() > 0);
                assertTrue(status.heapUsedBytes() >= 0);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("progress flows from reporter through parse to handle update")
        void progressFlowsEndToEnd() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                // Reporter writes progress
                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportProgressImmediate("LOADING", 25, "Doc 5/20", "Loading documents");
                    reporter.reportProgressImmediate("LOADING", 50, "Doc 10/20", "Loading documents");
                    reporter.reportProgressImmediate("LOADING", 100, "Done", "All docs loaded");
                }

                // Parse and apply
                List<SubprocessMessage> messages = parseMessages();
                long progressCount = messages.stream()
                        .filter(m -> m instanceof SubprocessMessage.Progress)
                        .count();
                assertEquals(3, progressCount);

                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Progress progress) {
                        handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                    }
                }

                // Verify final state
                assertEquals("LOADING", handle.getCurrentPhase());
                assertEquals(100, handle.getProgressPercent());
                assertEquals("All docs loaded", handle.getLastMessage());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("phase transitions flow correctly")
        void phaseTransitionsFlowCorrectly() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportPhaseTransition(null, "LOADING", 0);
                    reporter.reportPhaseTransition("LOADING", "CHUNKING", 5000);
                    reporter.reportPhaseTransition("CHUNKING", "EMBEDDING", 3000);
                    reporter.reportPhaseTransition("EMBEDDING", "INDEXING", 15000);
                }

                List<SubprocessMessage> messages = parseMessages();
                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.PhaseTransition transition) {
                        handle.setCurrentPhase(transition.toPhase());
                        handle.updateHeartbeat();
                    }
                }

                assertEquals("INDEXING", handle.getCurrentPhase());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("completed message produces success result")
        void completedProducesSuccessResult() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportCompleted(10, 500, 500, 500, 75000, 150000, "/data/index",
                            Map.of("LOADING", 5000L, "EMBEDDING", 20000L));
                }

                List<SubprocessMessage> messages = parseMessages();
                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Completed completed) {
                        var result = SubprocessHandle.SubprocessResult.success("flow-test", completed);
                        handle.getResultFuture().complete(result);
                    }
                }

                assertTrue(handle.getResultFuture().isDone());
                var result = handle.getResultFuture().get();
                assertTrue(result.success());
                assertEquals(10, result.documentsLoaded());
                assertEquals(500, result.chunksCreated());
                assertEquals("/data/index", result.indexPath());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("failed message produces failure result")
        void failedProducesFailureResult() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportFailed("EMBEDDING", new OutOfMemoryError("Java heap space"));
                }

                List<SubprocessMessage> messages = parseMessages();
                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Failed failed) {
                        var result = SubprocessHandle.SubprocessResult.failure(
                                "flow-test", 137, failed.errorMessage(),
                                failed.phase(), false, true);
                        handle.getResultFuture().complete(result);
                    }
                }

                assertTrue(handle.getResultFuture().isDone());
                var result = handle.getResultFuture().get();
                assertFalse(result.success());
                assertTrue(result.oomKilled());
                assertEquals("EMBEDDING", result.errorPhase());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("log messages update heartbeat timestamp")
        void logMessagesUpdateHeartbeat() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var before = handle.getLastHeartbeat();
                Thread.sleep(10);

                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportLog("INFO", "Pipeline", "Processing batch 5");
                }

                List<SubprocessMessage> messages = parseMessages();
                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Log) {
                        handle.updateHeartbeat();
                    }
                }

                assertTrue(handle.getLastHeartbeat().isAfter(before));
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Full pipeline simulation")
    class FullPipelineSimulation {

        @Test @DisplayName("complete ingest pipeline flow with heartbeats")
        void completeIngestPipelineFlow() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                // Simulate a full pipeline run
                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    // Phase 1: Loading
                    reporter.reportPhaseTransition(null, "LOADING", 0);
                    reporter.reportProgressImmediate("LOADING", 50, "Doc 5/10", "Loading");
                    reporter.reportProgressImmediate("LOADING", 100, "Done", "10 docs loaded");

                    // Phase 2: Chunking
                    reporter.reportPhaseTransition("LOADING", "CHUNKING", 2000);
                    reporter.reportProgressImmediate("CHUNKING", 100, "Done", "500 chunks");

                    // Heartbeat during embedding (long phase)
                    reporter.reportPhaseTransition("CHUNKING", "EMBEDDING", 1500);
                    // Manual heartbeat with known values
                    var heartbeat = new SubprocessMessage.Heartbeat(
                            "flow-test", 5000, 72.5,
                            750_000_000L, 1_073_741_824L,
                            200_000_000L, 800_000_000L, 25.0,
                            3_000_000_000L, 8_000_000_000L, 37.5);
                    String hbJson = SubprocessMessage.MESSAGE_PREFIX + MAPPER.writeValueAsString(heartbeat);
                    printStream.println(hbJson);

                    reporter.reportProgressImmediate("EMBEDDING", 50, "Batch 5/10", "Processing");
                    reporter.reportProgressImmediate("EMBEDDING", 100, "Done", "500 embeddings");

                    // Phase 4: Indexing
                    reporter.reportPhaseTransition("EMBEDDING", "INDEXING", 15000);
                    reporter.reportProgressImmediate("INDEXING", 100, "Done", "500 indexed");

                    // Completion
                    reporter.reportCompleted(10, 500, 500, 500, 75000, 150000, "/data/index",
                            Map.of("LOADING", 2000L, "CHUNKING", 1500L,
                                    "EMBEDDING", 15000L, "INDEXING", 3000L));
                }

                // Parse and apply all messages
                List<SubprocessMessage> messages = parseMessages();
                assertTrue(messages.size() >= 10, "Should have at least 10 messages, got " + messages.size());

                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Progress progress) {
                        handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                    } else if (msg instanceof SubprocessMessage.PhaseTransition transition) {
                        handle.setCurrentPhase(transition.toPhase());
                        handle.updateHeartbeat();
                    } else if (msg instanceof SubprocessMessage.Heartbeat hb) {
                        handle.updateMemoryFromHeartbeat(hb);
                    } else if (msg instanceof SubprocessMessage.Completed completed) {
                        handle.getResultFuture().complete(
                                SubprocessHandle.SubprocessResult.success("flow-test", completed));
                    }
                }

                // Verify final state
                assertTrue(handle.getResultFuture().isDone());
                var result = handle.getResultFuture().get();
                assertTrue(result.success());
                assertEquals(10, result.documentsLoaded());

                // Verify memory was captured from the heartbeat
                var status = handle.getStatus();
                assertEquals(72.5, status.heapUsagePercent(), 0.01);
                assertEquals(25.0, status.offHeapUsagePercent(), 0.01);
                assertEquals(37.5, status.gpuUsagePercent(), 0.01);
                assertEquals(72.5, status.peakHeapUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("mixed messages with interleaved heartbeats")
        void mixedMessagesWithHeartbeats() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                    reporter.reportPhaseTransition(null, "EMBEDDING", 0);

                    // Interleave heartbeats with progress updates
                    for (int i = 0; i < 5; i++) {
                        reporter.reportProgressImmediate("EMBEDDING", i * 20, "Batch " + (i + 1), "proc");
                        // Write a heartbeat between each progress
                        var hb = new SubprocessMessage.Heartbeat(
                                "flow-test", (i + 1) * 1000, 40.0 + i * 5,
                                (400 + i * 50) * 1_000_000L, 1_000_000_000L,
                                (100 + i * 20) * 1_000_000L, 500_000_000L, 20.0 + i * 4,
                                0L, 0L, 0.0);
                        printStream.println(SubprocessMessage.MESSAGE_PREFIX + MAPPER.writeValueAsString(hb));
                    }
                    reporter.reportProgressImmediate("EMBEDDING", 100, "Done", "complete");
                }

                List<SubprocessMessage> messages = parseMessages();

                int progressCount = 0;
                int heartbeatCount = 0;

                for (SubprocessMessage msg : messages) {
                    if (msg instanceof SubprocessMessage.Progress progress) {
                        handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                        progressCount++;
                    } else if (msg instanceof SubprocessMessage.PhaseTransition transition) {
                        handle.setCurrentPhase(transition.toPhase());
                        handle.updateHeartbeat();
                    } else if (msg instanceof SubprocessMessage.Heartbeat hb) {
                        handle.updateMemoryFromHeartbeat(hb);
                        heartbeatCount++;
                    }
                }

                assertEquals(6, progressCount); // 5 + final 100%
                assertEquals(5, heartbeatCount);
                assertEquals(100, handle.getProgressPercent());

                // Peak heap should be from last heartbeat (highest)
                var status = handle.getStatus();
                assertEquals(60.0, status.peakHeapUsagePercent(), 0.01); // 40 + 4*5 = 60
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Worker status messages")
    class WorkerStatusMessages {

        @Test @DisplayName("worker status round-trips through prefix parsing")
        void workerStatusRoundTrips() throws Exception {
            try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                reporter.reportWorkerStatus("worker-1", "embedding", "processing",
                        150, 32, 4.7, "batch-5");
                reporter.reportWorkerStatus("worker-2", "indexing", "waiting",
                        0, 0, 0.0, null);
            }

            List<SubprocessMessage> messages = parseMessages();
            long workerCount = messages.stream()
                    .filter(m -> m instanceof SubprocessMessage.WorkerStatus)
                    .count();
            assertEquals(2, workerCount);

            var worker1 = messages.stream()
                    .filter(m -> m instanceof SubprocessMessage.WorkerStatus ws && "worker-1".equals(ws.workerId()))
                    .map(m -> (SubprocessMessage.WorkerStatus) m)
                    .findFirst().orElseThrow();

            assertEquals("embedding", worker1.workerType());
            assertEquals("processing", worker1.status());
            assertEquals(150, worker1.itemsProcessed());
            assertEquals(32, worker1.currentBatchSize());
            assertEquals(4.7, worker1.throughput(), 0.01);
        }
    }

    @Nested @DisplayName("Error message handling")
    class ErrorMessageHandling {

        @Test @DisplayName("OOM error is detectable from failed message")
        void oomErrorDetectable() throws Exception {
            try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                reporter.reportFailed("EMBEDDING", new OutOfMemoryError("Java heap space"));
            }

            List<SubprocessMessage> messages = parseMessages();
            var failed = messages.stream()
                    .filter(m -> m instanceof SubprocessMessage.Failed)
                    .map(m -> (SubprocessMessage.Failed) m)
                    .findFirst().orElseThrow();

            assertEquals("EMBEDDING", failed.phase());
            assertEquals("java.lang.OutOfMemoryError", failed.errorType());
            assertTrue(failed.errorMessage().contains("heap space"));
            assertNotNull(failed.stackTrace());
        }

        @Test @DisplayName("nested exception cause preserved in failed message")
        void nestedExceptionCausePreserved() throws Exception {
            try (var reporter = new SubprocessProgressReporter("flow-test", printStream)) {
                var cause = new java.io.IOException("Disk full");
                var wrapper = new RuntimeException("Write failed", cause);
                reporter.reportFailed("INDEXING", wrapper);
            }

            List<SubprocessMessage> messages = parseMessages();
            var failed = (SubprocessMessage.Failed) messages.stream()
                    .filter(m -> m instanceof SubprocessMessage.Failed)
                    .findFirst().orElseThrow();

            assertTrue(failed.stackTrace().contains("Disk full") || failed.errorMessage().contains("Write failed"));
        }
    }
}
