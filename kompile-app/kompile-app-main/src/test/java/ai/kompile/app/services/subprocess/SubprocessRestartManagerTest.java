package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessRestartManager")
class SubprocessRestartManagerTest {

    private SystemMemoryAnalyzer memoryAnalyzer;
    private SubprocessRestartManager restartManager;

    @BeforeEach void setUp() throws Exception {
        memoryAnalyzer = new SystemMemoryAnalyzer();
        restartManager = new SubprocessRestartManager(memoryAnalyzer, null);
        // Set fields via setters since @Value won't work in unit tests
        restartManager.setRestartEnabled(true);
        restartManager.setMaxRestartAttempts(3);
        // Set @Value fields via reflection
        setField("initialBackoffMs", 5000);
        setField("backoffMultiplier", 2.0);
        setField("heapIncreaseFactor", 1.25);
        setField("systemRamSafetyMargin", 0.15);
        setField("threadReductionFactor", 0.5);
        setField("minThreads", 1);
    }

    private void setField(String name, Object value) throws Exception {
        var field = SubprocessRestartManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(restartManager, value);
    }

    @Nested @DisplayName("FailureReason categorization")
    class FailureCategorization {
        @Test void oomDetectedTakesPriority() {
            assertEquals(SubprocessRestartManager.FailureReason.OUT_OF_MEMORY,
                    restartManager.categorizeFailure(1, true));
        }

        @Test void exitCode137IsOomKilled() {
            assertEquals(SubprocessRestartManager.FailureReason.OOM_KILLED,
                    restartManager.categorizeFailure(137, false));
        }

        @Test void exitCode134IsNativeCrash() {
            assertEquals(SubprocessRestartManager.FailureReason.NATIVE_CRASH,
                    restartManager.categorizeFailure(134, false));
        }

        @Test void exitCode136IsNativeCrash() {
            assertEquals(SubprocessRestartManager.FailureReason.NATIVE_CRASH,
                    restartManager.categorizeFailure(136, false));
        }

        @Test void exitCode139IsNativeCrash() {
            assertEquals(SubprocessRestartManager.FailureReason.NATIVE_CRASH,
                    restartManager.categorizeFailure(139, false));
        }

        @Test void exitCode130IsCancelled() {
            assertEquals(SubprocessRestartManager.FailureReason.CANCELLED,
                    restartManager.categorizeFailure(130, false));
        }

        @Test void exitCode143IsCancelled() {
            assertEquals(SubprocessRestartManager.FailureReason.CANCELLED,
                    restartManager.categorizeFailure(143, false));
        }

        @Test void unknownExitCode() {
            assertEquals(SubprocessRestartManager.FailureReason.UNKNOWN,
                    restartManager.categorizeFailure(1, false));
        }
    }

    @Nested @DisplayName("shouldRestart")
    class ShouldRestart {
        @Test void oomIsRestartable() {
            assertTrue(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.OUT_OF_MEMORY));
        }

        @Test void oomKilledIsRestartable() {
            assertTrue(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.OOM_KILLED));
        }

        @Test void nativeCrashNotRestartable() {
            assertFalse(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.NATIVE_CRASH));
        }

        @Test void cancelledNotRestartable() {
            assertFalse(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.CANCELLED));
        }

        @Test void unknownNotRestartable() {
            assertFalse(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.UNKNOWN));
        }

        @Test void disabledGlobally() {
            restartManager.setRestartEnabled(false);
            assertFalse(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.OUT_OF_MEMORY));
        }

        @Test void exhaustedAttempts() {
            String taskId = "exhaust";
            for (int i = 0; i < 3; i++) {
                assertTrue(restartManager.shouldRestart(taskId, SubprocessRestartManager.FailureReason.OUT_OF_MEMORY));
                restartManager.recordRestartAttempt(taskId, true);
            }
            assertFalse(restartManager.shouldRestart(taskId, SubprocessRestartManager.FailureReason.OUT_OF_MEMORY));
        }

        @Test void batchSizeTooLargeIsRestartable() {
            assertTrue(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE));
        }

        @Test void memoryPressureIsRestartable() {
            assertTrue(restartManager.shouldRestart("t1", SubprocessRestartManager.FailureReason.MEMORY_PRESSURE));
        }
    }

    @Nested @DisplayName("Restart state tracking")
    class StateTracking {
        @Test void initialStatus() {
            var status = restartManager.getRestartStatus("new-task");
            assertEquals(0, status.attemptsMade());
            assertTrue(status.hasAttemptsRemaining());
            assertEquals(3, status.attemptsRemaining());
        }

        @Test void recordAttempt() {
            restartManager.recordRestartAttempt("t1", true);
            var status = restartManager.getRestartStatus("t1");
            assertEquals(1, status.attemptsMade());
            assertTrue(status.lastAttemptSuccess());
            assertNotNull(status.lastAttemptTime());
        }

        @Test void recordSuccess() {
            restartManager.recordRestartAttempt("t1", true);
            restartManager.recordRestartSuccess("t1");
            var status = restartManager.getRestartStatus("t1");
            assertTrue(status.recoverySuccessful());
        }

        @Test void clearState() {
            restartManager.recordRestartAttempt("t1", true);
            restartManager.clearRestartState("t1");
            var status = restartManager.getRestartStatus("t1");
            assertEquals(0, status.attemptsMade());
        }

        @Test void resetAll() {
            restartManager.recordRestartAttempt("t1", true);
            restartManager.recordRestartAttempt("t2", false);
            restartManager.resetAllRestartStates();
            assertEquals(0, restartManager.getRestartStatus("t1").attemptsMade());
            assertEquals(0, restartManager.getRestartStatus("t2").attemptsMade());
        }
    }

    @Nested @DisplayName("RestartConfig")
    class RestartConfigTests {
        @Test void getRestartConfigReturnsConfig() {
            var config = restartManager.getRestartConfig(
                    "t1", false,
                    4L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024,
                    4, 4, 2, 32);

            assertNotNull(config);
            assertEquals("t1", config.taskId());
            assertEquals(1, config.attemptNumber());
            assertEquals(3, config.maxAttempts());
            assertTrue(config.backoffMs() > 0);
            assertNotNull(config.heapSize());
            assertNotNull(config.memoryAnalysis());
        }

        @Test void batchSizeTooLargeReducesBatch() {
            var config = restartManager.getRestartConfig(
                    "t1", SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE,
                    false,
                    4L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024,
                    4, 4, 2, 32);

            // Batch should be reduced to 25% (32/4 = 8)
            assertTrue(config.batchSize() < 32);
        }

        @Test void configSummary() {
            var config = restartManager.getRestartConfig(
                    "t1", false,
                    4L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024,
                    4, 4, 2, 32);

            String summary = config.getSummary();
            assertNotNull(summary);
            assertTrue(summary.contains("Restart"));
            assertTrue(summary.contains("heap="));
        }
    }

    @Nested @DisplayName("RestartStatus record")
    class RestartStatusTests {
        @Test void hasAttemptsRemaining() {
            var status = new SubprocessRestartManager.RestartStatus("t", 1, 3, true, null, false);
            assertTrue(status.hasAttemptsRemaining());
            assertEquals(2, status.attemptsRemaining());
        }

        @Test void noAttemptsRemaining() {
            var status = new SubprocessRestartManager.RestartStatus("t", 3, 3, false, null, false);
            assertFalse(status.hasAttemptsRemaining());
            assertEquals(0, status.attemptsRemaining());
        }
    }

    @Nested @DisplayName("Configuration")
    class Configuration {
        @Test void getters() {
            assertTrue(restartManager.isRestartEnabled());
            assertEquals(3, restartManager.getMaxRestartAttempts());
        }

        @Test void canManualRestart() {
            assertTrue(restartManager.canManualRestart("any-task"));
        }
    }
}
