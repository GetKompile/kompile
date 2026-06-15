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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BackgroundProcessManager}.
 * <p>
 * Tests process launching, virtual process registration, process listing,
 * kill operations, lifecycle callbacks, cleanup, and state transitions.
 */
@DisabledOnOs(OS.WINDOWS)
class BackgroundProcessManagerTest {

    private BackgroundProcessManager manager;

    @BeforeEach
    void setUp() {
        manager = new BackgroundProcessManager("test-session-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    // ===================================================================
    // Virtual process registration
    // ===================================================================

    @Nested
    class VirtualProcesses {

        @Test
        void registerVirtual_shouldCreateEntry() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "echo test", "Test process", Map.of());

            assertNotNull(entry);
            assertNotNull(entry.getId());
            assertEquals("echo test", entry.getCommand());
            assertEquals("Test process", entry.getDescription());
            assertEquals(ProcessState.RUNNING, entry.getState());
            assertTrue(entry.isVirtual());
            assertTrue(entry.isRunning());
        }

        @Test
        void registerVirtual_shouldAppearInListAll() {
            manager.registerVirtual(ProcessKind.COMMAND, "cmd1", "First", Map.of());
            manager.registerVirtual(ProcessKind.JUDGE, "cmd2", "Second", Map.of());

            List<ProcessEntry> all = manager.listAll();
            assertEquals(2, all.size());
        }

        @Test
        void registerVirtual_shouldAppearInListRunning() {
            manager.registerVirtual(ProcessKind.COMMAND, "cmd1", "Running one", Map.of());
            List<ProcessEntry> running = manager.listRunning();
            assertEquals(1, running.size());
            assertTrue(running.get(0).isRunning());
        }

        @Test
        void virtualWithMetadata_shouldPreserveMetadata() {
            Map<String, String> meta = Map.of("key1", "val1", "key2", "val2");
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.ENFORCER, "cmd", "With meta", meta);

            assertEquals(meta, entry.getMetadata());
        }

        @Test
        void virtualWithNullKind_shouldDefaultToCommand() {
            ProcessEntry entry = manager.registerVirtual(null, "cmd", "Null kind", Map.of());
            assertEquals(ProcessKind.COMMAND, entry.getKind());
        }

        @Test
        void virtualWithNullDescription_shouldUseKindLabel() {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.JUDGE, "cmd", null, Map.of());
            assertEquals("judge", entry.getDescription());
        }

        @Test
        void virtualWithNullCommand_shouldUseKindLabel() {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.ENFORCER, null, "desc", Map.of());
            assertEquals("enforcer", entry.getCommand());
        }

        @Test
        void judgeKind_shouldGenerateJudgePrefixedId() {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.JUDGE, "cmd", "desc", Map.of());
            assertTrue(entry.getId().startsWith("judge-"), "ID should start with judge-");
        }

        @Test
        void enforcerKind_shouldGenerateEnforcerPrefixedId() {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.ENFORCER, "cmd", "desc", Map.of());
            assertTrue(entry.getId().startsWith("enforcer-"), "ID should start with enforcer-");
        }

        @Test
        void commandKind_shouldGenerateProcPrefixedId() {
            ProcessEntry entry = manager.registerVirtual(ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(entry.getId().startsWith("proc-"), "ID should start with proc-");
        }
    }

    // ===================================================================
    // Complete / Fail virtual processes
    // ===================================================================

    @Nested
    class CompleteAndFail {

        @Test
        void complete_shouldTransitionToCompleted() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(manager.complete(entry.getId()));

            ProcessEntry updated = manager.get(entry.getId());
            assertEquals(ProcessState.COMPLETED, updated.getState());
            assertEquals(0, updated.getExitCode());
            assertNotNull(updated.getEndTime());
            assertFalse(updated.isRunning());
        }

        @Test
        void fail_shouldTransitionToFailed() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(manager.fail(entry.getId(), 42));

            ProcessEntry updated = manager.get(entry.getId());
            assertEquals(ProcessState.FAILED, updated.getState());
            assertEquals(42, updated.getExitCode());
            assertNotNull(updated.getEndTime());
        }

        @Test
        void failWithDefaultExitCode_shouldUseMinusOne() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(manager.fail(entry.getId()));

            assertEquals(-1, manager.get(entry.getId()).getExitCode());
        }

        @Test
        void completeAlreadyCompleted_shouldReturnFalse() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            manager.complete(entry.getId());
            assertFalse(manager.complete(entry.getId()), "Cannot complete already-completed");
        }

        @Test
        void failAlreadyCompleted_shouldReturnFalse() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            manager.complete(entry.getId());
            assertFalse(manager.fail(entry.getId()), "Cannot fail already-completed");
        }

        @Test
        void completeNonexistent_shouldReturnFalse() {
            assertFalse(manager.complete("nonexistent-id"));
        }

        @Test
        void failNonexistent_shouldReturnFalse() {
            assertFalse(manager.fail("nonexistent-id"));
        }
    }

    // ===================================================================
    // Kill
    // ===================================================================

    @Nested
    class KillOperations {

        @Test
        void killVirtualRunning_shouldTransitionToKilled() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(manager.kill(entry.getId()));

            assertEquals(ProcessState.KILLED, manager.get(entry.getId()).getState());
            assertEquals(-1, manager.get(entry.getId()).getExitCode());
        }

        @Test
        void killNonexistent_shouldReturnFalse() {
            assertFalse(manager.kill("does-not-exist"));
        }

        @Test
        void killAlreadyCompleted_shouldReturnFalse() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            manager.complete(entry.getId());
            assertFalse(manager.kill(entry.getId()), "Cannot kill completed process");
        }

        @Test
        void killByPid_noMatchingPid_shouldReturnFalse() {
            assertFalse(manager.killByPid(999999999L));
        }
    }

    // ===================================================================
    // Get
    // ===================================================================

    @Nested
    class GetOperations {

        @Test
        void getExisting_shouldReturnEntry() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertNotNull(manager.get(entry.getId()));
        }

        @Test
        void getNonexistent_shouldReturnNull() {
            assertNull(manager.get("nonexistent"));
        }
    }

    // ===================================================================
    // Listing
    // ===================================================================

    @Nested
    class Listing {

        @Test
        void listAll_emptyManager_shouldReturnEmpty() {
            assertTrue(manager.listAll().isEmpty());
        }

        @Test
        void listRunning_emptyManager_shouldReturnEmpty() {
            assertTrue(manager.listRunning().isEmpty());
        }

        @Test
        void listRunning_afterComplete_shouldExcludeCompleted() {
            ProcessEntry e1 = manager.registerVirtual(ProcessKind.COMMAND, "c1", "d1", Map.of());
            ProcessEntry e2 = manager.registerVirtual(ProcessKind.COMMAND, "c2", "d2", Map.of());

            manager.complete(e1.getId());

            List<ProcessEntry> running = manager.listRunning();
            assertEquals(1, running.size());
            assertEquals(e2.getId(), running.get(0).getId());
        }

        @Test
        void listAll_shouldBeSortedByStartTime() {
            ProcessEntry e1 = manager.registerVirtual(ProcessKind.COMMAND, "first", "d1", Map.of());
            ProcessEntry e2 = manager.registerVirtual(ProcessKind.COMMAND, "second", "d2", Map.of());

            List<ProcessEntry> all = manager.listAll();
            assertEquals(2, all.size());
            // First registered should come first (earlier start time)
            assertEquals(e1.getId(), all.get(0).getId());
            assertEquals(e2.getId(), all.get(1).getId());
        }
    }

    // ===================================================================
    // ProcessEntry accessors
    // ===================================================================

    @Nested
    class ProcessEntryAccessors {

        @Test
        void duration_whileRunning_shouldBePositive() throws InterruptedException {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            Thread.sleep(10);
            Duration d = entry.getDuration();
            assertTrue(d.toMillis() >= 10, "Duration should be at least 10ms");
        }

        @Test
        void duration_afterComplete_shouldBeFixed() throws InterruptedException {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            Thread.sleep(10);
            manager.complete(entry.getId());

            Duration d1 = entry.getDuration();
            Thread.sleep(50);
            Duration d2 = entry.getDuration();

            // After completion, duration should be fixed (within small tolerance)
            assertTrue(Math.abs(d1.toMillis() - d2.toMillis()) < 5,
                    "Duration should be frozen after completion");
        }

        @Test
        void processKindLabel_shouldBeLowercase() {
            assertEquals("command", ProcessKind.COMMAND.label());
            assertEquals("judge", ProcessKind.JUDGE.label());
            assertEquals("enforcer", ProcessKind.ENFORCER.label());
        }
    }

    // ===================================================================
    // Change listeners
    // ===================================================================

    @Nested
    class ChangeListeners {

        @Test
        void listener_shouldFireOnRegister() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            manager.addChangeListener(latch::countDown);

            manager.registerVirtual(ProcessKind.COMMAND, "cmd", "desc", Map.of());
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Listener should fire on register");
        }

        @Test
        void listener_shouldFireOnComplete() throws InterruptedException {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());

            CountDownLatch latch = new CountDownLatch(1);
            manager.addChangeListener(latch::countDown);

            manager.complete(entry.getId());
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Listener should fire on complete");
        }

        @Test
        void listener_shouldFireOnKill() throws InterruptedException {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());

            CountDownLatch latch = new CountDownLatch(1);
            manager.addChangeListener(latch::countDown);

            manager.kill(entry.getId());
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Listener should fire on kill");
        }

        @Test
        void removeListener_shouldStopFiring() throws InterruptedException {
            AtomicReference<Integer> count = new AtomicReference<>(0);
            Runnable listener = () -> count.updateAndGet(c -> c + 1);

            manager.addChangeListener(listener);
            manager.registerVirtual(ProcessKind.COMMAND, "c1", "d1", Map.of());
            assertEquals(1, count.get());

            manager.removeChangeListener(listener);
            manager.registerVirtual(ProcessKind.COMMAND, "c2", "d2", Map.of());
            assertEquals(1, count.get(), "Removed listener should not fire again");
        }

        @Test
        void buggyListener_shouldNotBreakManager() {
            manager.addChangeListener(() -> { throw new RuntimeException("boom"); });

            // Should not throw despite buggy listener
            assertDoesNotThrow(() -> manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of()));
        }
    }

    // ===================================================================
    // Exit callback
    // ===================================================================

    @Nested
    class ExitCallback {

        @Test
        void exitCallback_shouldFireOnRealProcessExit() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<ProcessEntry> exitedEntry = new AtomicReference<>();

            manager.setExitCallback(entry -> {
                exitedEntry.set(entry);
                latch.countDown();
            });

            ProcessEntry entry = manager.launch(
                    "echo callback-test", "Callback test", Path.of(System.getProperty("user.dir")));

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Exit callback should fire");
            assertNotNull(exitedEntry.get());
            assertEquals(ProcessState.COMPLETED, exitedEntry.get().getState());
        }
    }

    // ===================================================================
    // Real process launch
    // ===================================================================

    @Nested
    class RealProcessLaunch {

        @Test
        void launchEcho_shouldComplete() throws Exception {
            ProcessEntry entry = manager.launch(
                    "echo real-launch-test", "Echo test", Path.of(System.getProperty("user.dir")));

            assertNotNull(entry);
            assertFalse(entry.isVirtual(), "Real processes should not be virtual");

            // Wait for completion
            int attempts = 0;
            while (entry.isRunning() && attempts < 50) {
                Thread.sleep(100);
                attempts++;
            }

            assertFalse(entry.isRunning(), "Process should have completed");
            assertEquals(ProcessState.COMPLETED, entry.getState());
            assertEquals(0, entry.getExitCode());
        }

        @Test
        void launchWithArgsArray_shouldWork() throws Exception {
            ProcessEntry entry = manager.launch(
                    new String[]{"echo", "array-test"},
                    "Args array", Path.of(System.getProperty("user.dir")));

            assertNotNull(entry);

            int attempts = 0;
            while (entry.isRunning() && attempts < 50) {
                Thread.sleep(100);
                attempts++;
            }

            assertEquals(ProcessState.COMPLETED, entry.getState());
        }

        @Test
        void launchFailingCommand_shouldSetFailed() throws Exception {
            ProcessEntry entry = manager.launch(
                    "exit 7", "Failing cmd", Path.of(System.getProperty("user.dir")));

            int attempts = 0;
            while (entry.isRunning() && attempts < 50) {
                Thread.sleep(100);
                attempts++;
            }

            assertEquals(ProcessState.FAILED, entry.getState());
            assertEquals(7, entry.getExitCode());
        }

        @Test
        void killRealProcess_shouldSetKilledOrFailed() throws Exception {
            ProcessEntry entry = manager.launch(
                    "sleep 60", "Long sleep", Path.of(System.getProperty("user.dir")));

            // Wait for process to start
            Thread.sleep(500);
            assertTrue(entry.isRunning());

            assertTrue(manager.kill(entry.getId()));

            int attempts = 0;
            while (entry.isRunning() && attempts < 100) {
                Thread.sleep(100);
                attempts++;
            }

            assertFalse(entry.isRunning(), "Process should no longer be running");
            // Race: captureOutputAndWait may set FAILED before kill sets KILLED
            assertTrue(entry.getState() == ProcessState.KILLED || entry.getState() == ProcessState.FAILED,
                    "State should be KILLED or FAILED, got: " + entry.getState());
        }
    }

    // ===================================================================
    // Cleanup
    // ===================================================================

    @Nested
    class CleanupTests {

        @Test
        void cleanup_emptyManager_shouldReturnZero() {
            assertEquals(0, manager.cleanup());
        }

        @Test
        void cleanup_runningProcesses_shouldNotBeRemoved() {
            manager.registerVirtual(ProcessKind.COMMAND, "cmd", "running", Map.of());

            assertEquals(0, manager.cleanup(Duration.ZERO),
                    "Running processes should never be cleaned up");
            assertEquals(1, manager.listAll().size());
        }

        @Test
        void cleanup_completedWithZeroRetention_shouldBeRemoved() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            manager.complete(entry.getId());

            int removed = manager.cleanup(Duration.ZERO);
            assertEquals(1, removed);
            assertTrue(manager.listAll().isEmpty());
        }
    }

    // ===================================================================
    // Read output
    // ===================================================================

    @Nested
    class ReadOutput {

        @Test
        void readOutput_nonexistentProcess_shouldReturnNotFound() {
            String output = manager.readOutput("nonexistent", 10);
            assertTrue(output.contains("not found"));
        }

        @Test
        void readOutput_virtualProcessNoFile_shouldReturnNoOutput() {
            ProcessEntry entry = manager.registerVirtual(
                    ProcessKind.COMMAND, "cmd", "desc", Map.of());
            String output = manager.readOutput(entry.getId(), 10);
            assertTrue(output.contains("no output"), "Should indicate no output yet");
        }
    }

    // ===================================================================
    // Session ID and output dir
    // ===================================================================

    @Nested
    class SessionInfo {

        @Test
        void sessionId_shouldMatchConstructor() {
            String sid = "my-session-123";
            BackgroundProcessManager mgr = new BackgroundProcessManager(sid);
            try {
                assertEquals(sid, mgr.getSessionId());
            } finally {
                mgr.close();
            }
        }

        @Test
        void outputDir_shouldContainSessionId() {
            assertTrue(manager.getOutputDir().toString().contains("test-session"));
        }
    }
}
