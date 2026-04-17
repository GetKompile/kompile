package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessMessage Protocol")
class SubprocessMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void messagePrefixConstant() {
        assertEquals("INGEST_MSG:", SubprocessMessage.MESSAGE_PREFIX);
    }

    @Nested @DisplayName("Progress")
    class ProgressTests {
        @Test void factory() {
            var msg = SubprocessMessage.progress("t1", "EMBEDDING", 45, "Batch 5/10", "Processing");
            assertEquals("t1", msg.taskId());
            assertEquals("EMBEDDING", msg.phase());
            assertEquals(45, msg.progressPercent());
            assertEquals("Batch 5/10", msg.currentStep());
            assertNull(msg.stats());
        }

        @Test void serializesToJson() throws Exception {
            var msg = SubprocessMessage.progress("t2", "CHUNKING", 30, "s", "m");
            String json = MAPPER.writeValueAsString(msg);
            assertTrue(json.contains("t2"));
            assertTrue(json.contains("CHUNKING"));
        }
    }

    @Nested @DisplayName("PhaseTransition")
    class PhaseTransitionTests {
        @Test void factory() {
            var msg = SubprocessMessage.phaseTransition("t1", "LOADING", "CHUNKING", 5000);
            assertEquals("LOADING", msg.fromPhase());
            assertEquals("CHUNKING", msg.toPhase());
            assertEquals(5000, msg.phaseDurationMs());
        }
    }

    @Nested @DisplayName("Heartbeat")
    class HeartbeatTests {
        @Test void factory() {
            var msg = SubprocessMessage.heartbeat("t1", 15000);
            assertEquals("t1", msg.taskId());
            assertEquals(15000, msg.uptimeMs());
        }
    }

    @Nested @DisplayName("Completed")
    class CompletedTests {
        @Test void factory() {
            var durations = Map.of("LOADING", 2000L, "EMBEDDING", 15000L);
            var msg = SubprocessMessage.completed("t1", 10, 500, 500, 500,
                    75000L, 150000L, 25000L, "/idx", durations);
            assertEquals(10, msg.documentsLoaded());
            assertEquals(500, msg.chunksCreated());
            assertEquals("/idx", msg.indexPath());
            assertEquals(2, msg.phaseDurations().size());
        }
    }

    @Nested @DisplayName("Failed")
    class FailedTests {
        @Test void fromException() {
            var msg = SubprocessMessage.failed("t1", "EMBEDDING", new RuntimeException("OOM"));
            assertEquals("EMBEDDING", msg.phase());
            assertTrue(msg.errorMessage().contains("OOM"));
            assertEquals("java.lang.RuntimeException", msg.errorType());
            assertNotNull(msg.stackTrace());
        }

        @Test void fromStrings() {
            var msg = SubprocessMessage.failed("t1", "INDEXING", "Lock", "LockEx", "stack");
            assertEquals("Lock", msg.errorMessage());
            assertEquals("LockEx", msg.errorType());
        }

        @Test void nestedCause() {
            var cause = new java.io.IOException("Disk full");
            var wrapper = new RuntimeException("Write failed", cause);
            var msg = SubprocessMessage.failed("t1", "INDEXING", wrapper);
            assertTrue(msg.stackTrace().contains("Disk full") || msg.errorMessage().contains("Write failed"));
        }
    }

    @Nested @DisplayName("WorkerStatus")
    class WorkerStatusTests {
        @Test void factory() {
            var msg = SubprocessMessage.workerStatus("t1", "w1", "embedding", "processing",
                    150, 32, 4.7, "batch-5");
            assertEquals("w1", msg.workerId());
            assertEquals(150, msg.itemsProcessed());
            assertEquals(4.7, msg.throughput(), 0.01);
        }
    }

    @Nested @DisplayName("Log")
    class LogTests {
        @Test void withSource() {
            var msg = SubprocessMessage.log("t1", "WARN", "Watchdog", "Memory high");
            assertEquals("WARN", msg.level());
            assertEquals("Watchdog", msg.source());
        }

        @Test void withTimestamp() {
            long ts = System.currentTimeMillis();
            var msg = SubprocessMessage.log("t1", "ERROR", "src", "msg", ts);
            assertEquals(ts, msg.timestamp());
        }
    }

    @Test
    void allTypesHaveTaskId() {
        String t = "shared";
        assertEquals(t, SubprocessMessage.progress(t, "P", 0, "s", "m").taskId());
        assertEquals(t, SubprocessMessage.phaseTransition(t, "A", "B", 0).taskId());
        assertEquals(t, SubprocessMessage.heartbeat(t, 0).taskId());
        assertEquals(t, SubprocessMessage.completed(t, 0, 0, 0, 0, 0, 0, 0, "", Map.of()).taskId());
        assertEquals(t, SubprocessMessage.failed(t, "P", "e", "t", "s").taskId());
        assertEquals(t, SubprocessMessage.workerStatus(t, "w", "t", "s", 0, 0, 0, "").taskId());
        assertEquals(t, SubprocessMessage.log(t, "I", "s", "m").taskId());
    }

    @Test
    void typesDistinguishable() {
        assertInstanceOf(SubprocessMessage.Progress.class,
                SubprocessMessage.progress("t", "P", 0, "s", "m"));
        assertInstanceOf(SubprocessMessage.Heartbeat.class,
                SubprocessMessage.heartbeat("t", 0));
        assertInstanceOf(SubprocessMessage.Completed.class,
                SubprocessMessage.completed("t", 0, 0, 0, 0, 0, 0, 0, "", Map.of()));
    }
}
