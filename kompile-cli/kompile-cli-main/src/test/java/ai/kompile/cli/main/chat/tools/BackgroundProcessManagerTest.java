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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    // One-shot process monitors
    // ===================================================================

    @Nested
    class ProcessMonitors {

        @Test
        void monitoredLaunchWakesOnlyForConfiguredProcessAndConsumesMonitor() throws Exception {
            CountDownLatch monitoredExit = new CountDownLatch(1);
            AtomicReference<ProcessEntry> exitedEntry = new AtomicReference<>();
            AtomicReference<ProcessMonitor> firedMonitor = new AtomicReference<>();
            manager.addMonitorListener((entry, monitor) -> {
                exitedEntry.set(entry);
                firedMonitor.set(monitor);
                monitoredExit.countDown();
            });

            ProcessEntry unmonitored = manager.launch(
                    "printf 'ordinary\\n'", "ordinary", Path.of(System.getProperty("user.dir")));
            while (unmonitored.isRunning()) Thread.sleep(10);
            assertFalse(monitoredExit.await(100, TimeUnit.MILLISECONDS),
                    "ordinary process exits must not fire monitor callbacks");

            ProcessEntry monitored = manager.launchMonitored(
                    "printf 'monitored\\n'", "monitored",
                    Path.of(System.getProperty("user.dir")), "inspect the build output");

            assertTrue(monitoredExit.await(5, TimeUnit.SECONDS));
            assertEquals(monitored.getId(), exitedEntry.get().getId());
            assertEquals("inspect the build output", firedMonitor.get().message());
            assertNull(manager.getMonitor(monitored.getId()),
                    "completion monitors must be consumed after one terminal event");
        }

        @Test
        void processToolCanConfigureListAndCancelMonitor() throws Exception {
            ProcessEntry entry = manager.launch(
                    "sleep 10", "monitor tool", Path.of(System.getProperty("user.dir")));
            ProcessManagementTool tool = new ProcessManagementTool(manager);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode monitor = mapper.createObjectNode();
            monitor.put("action", "monitor");
            monitor.put("process_id", entry.getId());
            monitor.put("monitor_message", "resume verification");

            ToolResult created = tool.execute(monitor, null);
            assertFalse(created.isError());
            assertEquals("resume verification", manager.getMonitor(entry.getId()).message());

            ObjectNode list = mapper.createObjectNode().put("action", "monitors");
            ToolResult listed = tool.execute(list, null);
            assertTrue(listed.getOutput().contains(entry.getId()));
            assertTrue(listed.getOutput().contains("resume verification"));

            ObjectNode cancel = mapper.createObjectNode();
            cancel.put("action", "unmonitor");
            cancel.put("process_id", entry.getId());
            assertFalse(tool.execute(cancel, null).isError());
            assertNull(manager.getMonitor(entry.getId()));
            assertTrue(manager.kill(entry.getId()));
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
        void launchShouldExposeManagedTerminalEnv() throws Exception {
            ProcessEntry entry = manager.launch(
                    "printf '%s' \"$GEMINI_CLI_TRUST_WORKSPACE\"",
                    "Managed env", Path.of(System.getProperty("user.dir")));

            int attempts = 0;
            while (entry.isRunning() && attempts < 50) {
                Thread.sleep(100);
                attempts++;
            }

            assertEquals(ProcessState.COMPLETED, entry.getState());
            assertEquals(0, entry.getExitCode());
            assertTrue(manager.readOutput(entry.getId(), 10).contains("true"),
                    "process launch should inherit managed terminal workspace trust env");
        }

        @Test
        void readOutputShouldExposeRunningProcessTail() throws Exception {
            ProcessEntry entry = manager.launch(
                    "printf 'live-ready\\n'; sleep 10",
                    "Live tail", Path.of(System.getProperty("user.dir")));

            String output = "";
            int attempts = 0;
            while (attempts < 50) {
                output = manager.readOutput(entry.getId(), 10);
                if (output.contains("live-ready")) {
                    break;
                }
                Thread.sleep(100);
                attempts++;
            }

            assertTrue(output.contains("live-ready"),
                    "running process output should be readable before exit");
            assertTrue(entry.isRunning(), "process should still be running while output is tailed");
            assertTrue(BackgroundProcessManager.readOutputFile(entry.getOutputFile(), 10).contains("live-ready"));
            assertTrue(manager.kill(entry.getId()));
        }

        @Test
        void outputListenerShouldReceiveLinesBeforeProcessExit() throws Exception {
            CountDownLatch firstLine = new CountDownLatch(1);
            AtomicReference<String> captured = new AtomicReference<>();
            manager.addOutputListener((process, line) -> {
                captured.set(line);
                firstLine.countDown();
            });

            ProcessEntry entry = manager.launch(
                    "printf 'streamed-line\\n'; sleep 10",
                    "Streaming output", Path.of(System.getProperty("user.dir")));

            assertTrue(firstLine.await(5, TimeUnit.SECONDS),
                    "output listener should fire while process is still running");
            assertEquals("streamed-line", captured.get());
            assertTrue(entry.isRunning(), "line callback must precede process exit");
            assertTrue(manager.kill(entry.getId()));
        }

        @Test
        void processToolStreamShouldExposeRunningProcessOutput() throws Exception {
            ProcessEntry entry = manager.launch(
                    "printf 'tool-stream-ready\\n'; sleep 10",
                    "Tool stream", Path.of(System.getProperty("user.dir")));
            ProcessManagementTool tool = new ProcessManagementTool(manager);
            ObjectNode params = new ObjectMapper().createObjectNode();
            params.put("action", "stream");
            params.put("process_id", entry.getId());
            params.put("tail_lines", 10);
            params.put("follow_seconds", 1);
            CountDownLatch firstLine = new CountDownLatch(1);
            List<String> streamed = new CopyOnWriteArrayList<>();
            ToolContext context = new ToolContext(
                    "process-stream-test", null, null,
                    Path.of(System.getProperty("user.dir")), null);
            context.setOutputConsumer(line -> {
                streamed.add(line);
                firstLine.countDown();
            });

            CompletableFuture<ToolResult> execution = CompletableFuture.supplyAsync(() -> {
                try {
                    return tool.execute(params, context);
                } catch (ToolExecutionException e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(firstLine.await(3, TimeUnit.SECONDS));
            assertFalse(execution.isDone(),
                    "process stream must publish its initial tail while still following");
            ToolResult result = execution.get(5, TimeUnit.SECONDS);

            assertFalse(result.isError());
            assertTrue(result.isOutputStreamed());
            assertTrue(result.getOutput().contains("tool-stream-ready"),
                    "process stream should include output from a still-running process");
            assertTrue(result.getOutput().contains("stream status:"));
            assertTrue(streamed.stream().anyMatch(line -> line.contains("tool-stream-ready")));
            assertTrue(streamed.stream().anyMatch(line -> line.contains("stream status:")));
            assertTrue(manager.kill(entry.getId()));
        }

        @Test
        void processToolStreamObservesToolContextCancellation() throws Exception {
            ProcessEntry entry = manager.launch(
                    "printf 'cancel-ready\\n'; sleep 10",
                    "Cancelled tool stream", Path.of(System.getProperty("user.dir")));
            String output = "";
            for (int attempt = 0; attempt < 50 && !output.contains("cancel-ready"); attempt++) {
                output = manager.readOutput(entry.getId(), 10);
                Thread.sleep(50);
            }

            ProcessManagementTool tool = new ProcessManagementTool(manager);
            ObjectNode params = new ObjectMapper().createObjectNode();
            params.put("action", "stream");
            params.put("process_id", entry.getId());
            params.put("tail_lines", 10);
            params.put("follow_seconds", 30);
            ToolContext context = new ToolContext(
                    "cancelled-process-stream-test", null, null,
                    Path.of(System.getProperty("user.dir")), null);
            context.abort();

            long started = System.nanoTime();
            ToolResult result = tool.execute(params, context);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(result.isError());
            assertTrue(result.getOutput().contains("stream status: cancelled"));
            assertTrue(elapsedMs < 2_000,
                    "cancelled process stream should not wait for the follow deadline");
            assertTrue(manager.kill(entry.getId()));
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
        void naturalExitNotifiesRegisteredListenerExactlyOnceAfterOutputFlush() throws Exception {
            CountDownLatch exited = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            BackgroundProcessManager.ExitCallback listener = entry -> {
                callbacks.incrementAndGet();
                assertTrue(Files.exists(entry.getOutputFile()));
                try {
                    assertTrue(Files.readString(entry.getOutputFile()).contains("wake-agent"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                exited.countDown();
            };
            manager.addExitListener(listener);

            manager.launch("printf 'wake-agent\\n'", "Wake test",
                    Path.of(System.getProperty("user.dir")));

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertEquals(1, callbacks.get());
            manager.removeExitListener(listener);
        }

        @Test
        void killRealProcess_shouldRemainKilledAndNotifyExactlyOnce() throws Exception {
            AtomicInteger exits = new AtomicInteger();
            AtomicReference<ProcessState> notifiedState = new AtomicReference<>();
            CountDownLatch exitNotified = new CountDownLatch(1);
            manager.addExitListener(entry -> {
                notifiedState.set(entry.getState());
                exits.incrementAndGet();
                exitNotified.countDown();
            });
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
            assertEquals(ProcessState.KILLED, entry.getState(),
                    "the waiter must preserve an explicit user kill");
            assertTrue(exitNotified.await(5, TimeUnit.SECONDS),
                    "the process waiter must publish its terminal event");
            assertEquals(1, exits.get(), "kill/watcher races must emit one terminal event");
            assertEquals(ProcessState.KILLED, notifiedState.get(),
                    "exit listeners must be able to filter user-killed processes");
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

        @Test
        void outputDir_shouldUseProjectKompileHomeWhenAvailable() throws Exception {
            Path tempRoot = Files.createTempDirectory("kompile-process-manager-test");
            try {
                Path nestedWorkDir = tempRoot.resolve("module").resolve("subdir");
                Files.createDirectories(nestedWorkDir);
                Path projectKompile = tempRoot.resolve(".kompile");
                Files.createDirectories(projectKompile);

                String sid = "project-session-123";
                try (BackgroundProcessManager projectManager =
                             new BackgroundProcessManager(sid, nestedWorkDir)) {
                    assertEquals(projectKompile.resolve("process-output").resolve(sid),
                            projectManager.getOutputDir());
                }
            } finally {
                try (var walk = Files.walk(tempRoot)) {
                    walk.sorted((a, b) -> b.compareTo(a))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {}
                            });
                }
            }
        }
    }
}
