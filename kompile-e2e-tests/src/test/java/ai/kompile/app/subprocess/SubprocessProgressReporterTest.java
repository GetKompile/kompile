package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessProgressReporter")
class SubprocessProgressReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ByteArrayOutputStream out;
    private PrintStream ps;

    @BeforeEach void setUp() {
        out = new ByteArrayOutputStream();
        ps = new PrintStream(out, true, StandardCharsets.UTF_8);
    }
    @AfterEach void tearDown() { ps.close(); }

    private String output() { return out.toString(StandardCharsets.UTF_8).trim(); }
    private long lineCount() {
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> !l.isBlank() && l.startsWith(SubprocessMessage.MESSAGE_PREFIX)).count();
    }

    @Nested @DisplayName("Message format")
    class Format {
        @Test void prefixed() {
            try (var r = new SubprocessProgressReporter("t1", ps)) {
                r.reportProgressImmediate("LOADING", 50, "s", "m");
            }
            assertTrue(output().startsWith(SubprocessMessage.MESSAGE_PREFIX));
        }

        @Test void validJson() throws Exception {
            try (var r = new SubprocessProgressReporter("t2", ps)) {
                r.reportProgressImmediate("EMBEDDING", 75, "b3", "proc");
            }
            String json = output().substring(SubprocessMessage.MESSAGE_PREFIX.length());
            var node = MAPPER.readTree(json);
            assertEquals("t2", node.get("taskId").asText());
        }

        @Test void phaseTransition() {
            try (var r = new SubprocessProgressReporter("t3", ps)) {
                r.reportPhaseTransition("LOADING", "CHUNKING", 5000);
            }
            assertTrue(output().contains("LOADING"));
            assertTrue(output().contains("CHUNKING"));
        }

        @Test void completed() {
            try (var r = new SubprocessProgressReporter("t4", ps)) {
                r.reportCompleted(5, 100, 100, 100, 50000, 100000, "/idx",
                        Map.of("LOADING", 2000L));
            }
            assertTrue(output().contains("/idx"));
        }

        @Test void failed() {
            try (var r = new SubprocessProgressReporter("t5", ps)) {
                r.reportFailed("EMBEDDING", new RuntimeException("OOM"));
            }
            assertTrue(output().contains("OOM"));
        }

        @Test void failedFromStrings() {
            try (var r = new SubprocessProgressReporter("t6", ps)) {
                r.reportFailed("IDX", "Lock", "LockEx", "trace");
            }
            assertTrue(output().contains("Lock"));
        }

        @Test void logMessage() {
            try (var r = new SubprocessProgressReporter("t7", ps)) {
                r.reportLog("WARN", "Watchdog", "Memory 82%");
            }
            assertTrue(output().contains("Memory 82%"));
        }

        @Test void workerStatus() {
            try (var r = new SubprocessProgressReporter("t8", ps)) {
                r.reportWorkerStatus("w1", "embedding", "processing", 100, 32, 4.5, "b3");
            }
            assertTrue(output().contains("w1"));
        }
    }

    @Nested @DisplayName("Heartbeat")
    class Heartbeat {
        @Test void sendsPeriodicMessages() throws InterruptedException {
            try (var r = new SubprocessProgressReporter("hb1", ps)) {
                r.startHeartbeat(100);
                Thread.sleep(350);
                r.stopHeartbeat();
            }
            long count = out.toString(StandardCharsets.UTF_8).lines()
                    .filter(l -> l.contains("uptimeMs")).count();
            assertTrue(count >= 2, "Expected >=2 heartbeats, got " + count);
        }

        @Test void stopPreventsMore() throws InterruptedException {
            try (var r = new SubprocessProgressReporter("hb2", ps)) {
                r.startHeartbeat(100);
                Thread.sleep(200);
                r.stopHeartbeat();
                long before = lineCount();
                Thread.sleep(300);
                long after = lineCount();
                assertTrue(after - before <= 1);
            }
        }
    }

    @Nested @DisplayName("Throttling")
    class Throttling {
        @Test void rapidUpdatesThrottled() {
            try (var r = new SubprocessProgressReporter("th1", ps)) {
                for (int i = 0; i < 100; i++)
                    r.reportProgress("E", i, "s" + i, "m");
            }
            long count = lineCount();
            assertTrue(count < 100, "Should throttle, got " + count);
            assertTrue(count >= 1);
        }

        @Test void immediateBypassesThrottle() {
            try (var r = new SubprocessProgressReporter("th2", ps)) {
                for (int i = 0; i < 10; i++)
                    r.reportProgressImmediate("E", i * 10, "s" + i, "m");
            }
            assertEquals(10, lineCount());
        }
    }

    @Nested @DisplayName("Lifecycle")
    class Lifecycle {
        @Test void elapsedTime() throws InterruptedException {
            try (var r = new SubprocessProgressReporter("lc1", ps)) {
                Thread.sleep(100);
                assertTrue(r.getElapsedMs() >= 90);
            }
        }

        @Test void taskId() {
            try (var r = new SubprocessProgressReporter("lc2", ps)) {
                assertEquals("lc2", r.getTaskId());
            }
        }

        @Test void closeStopsHeartbeat() throws InterruptedException {
            var r = new SubprocessProgressReporter("lc3", ps);
            r.startHeartbeat(100);
            Thread.sleep(200);
            r.close();
            long before = lineCount();
            Thread.sleep(300);
            assertTrue(lineCount() - before <= 1);
        }
    }

    @Test
    void fullPipelineFlow() {
        try (var r = new SubprocessProgressReporter("flow1", ps)) {
            r.reportPhaseTransition(null, "LOADING", 0);
            r.reportProgressImmediate("LOADING", 100, "done", "10 docs");
            r.reportPhaseTransition("LOADING", "CHUNKING", 2000);
            r.reportProgressImmediate("CHUNKING", 100, "done", "500 chunks");
            r.reportPhaseTransition("CHUNKING", "EMBEDDING", 5000);
            r.reportProgressImmediate("EMBEDDING", 50, "b5/10", "proc");
            r.reportProgressImmediate("EMBEDDING", 100, "done", "500 emb");
            r.reportPhaseTransition("EMBEDDING", "INDEXING", 15000);
            r.reportProgressImmediate("INDEXING", 100, "done", "500 idx");
            r.reportCompleted(10, 500, 500, 500, 75000, 150000, "/idx",
                    Map.of("LOADING", 2000L, "CHUNKING", 5000L, "EMBEDDING", 15000L, "INDEXING", 3000L));
        }
        assertTrue(lineCount() >= 10);
    }
}
