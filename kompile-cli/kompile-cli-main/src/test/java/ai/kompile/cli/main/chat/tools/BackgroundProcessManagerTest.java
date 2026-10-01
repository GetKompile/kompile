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

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void setUp(@TempDir Path projectDir) throws IOException {
        // A project .kompile keeps the process logs inside the temp dir, not the
        // enclosing checkout's .kompile or ~/.kompile.
        Files.createDirectories(projectDir.resolve(".kompile"));
        manager = new BackgroundProcessManager("test-session-" + System.nanoTime(), projectDir);
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

        @Test
        void processToolForcesMonitorsAndAllowsConcurrentLaunches() throws Exception {
            ProcessManagementTool tool = new ProcessManagementTool(manager);
            ObjectMapper mapper = new ObjectMapper();
            assertTrue(tool.parameterSchema().path("properties").path("monitor")
                    .path("default").asBoolean());
            PermissionService permissions = new PermissionService();
            permissions.setAutoApproveAll(true);
            ToolContext context = new ToolContext(
                    "concurrent-process-test", null, permissions,
                    Path.of(System.getProperty("user.dir")), null);

            ObjectNode firstLaunch = mapper.createObjectNode();
            firstLaunch.put("action", "launch");
            // Builtin blocking read: killable long-runner that is neither
            // sleep-family nor a file reader, so the harness mandate passes.
            firstLaunch.put("command", "read -t 60");
            firstLaunch.put("description", "first concurrent process");
            firstLaunch.put("monitor", false);
            ObjectNode secondLaunch = mapper.createObjectNode();
            secondLaunch.put("action", "launch");
            secondLaunch.put("command", "read -t 60");
            secondLaunch.put("description", "second concurrent process");

            ToolResult first = tool.execute(firstLaunch, context);
            ToolResult second = tool.execute(secondLaunch, context);
            assertFalse(first.isError(), first::getOutput);
            assertFalse(second.isError(), second::getOutput);
            String firstId = first.getMetadata().get("processId").toString();
            String secondId = second.getMetadata().get("processId").toString();
            assertNotEquals(firstId, secondId);
            assertEquals(Boolean.TRUE, first.getMetadata().get("monitored"));
            assertEquals(Boolean.TRUE, second.getMetadata().get("monitored"));
            assertEquals(Boolean.TRUE, first.getMetadata().get("monitorForced"),
                    "an explicit monitor=false must be ignored at the host boundary");
            assertEquals(Boolean.FALSE, second.getMetadata().get("monitorForced"),
                    "an omitted monitor flag should use the host default");
            assertTrue(manager.get(firstId).isRunning());
            assertTrue(manager.get(secondId).isRunning());
            assertNotNull(manager.getMonitor(firstId));
            assertNotNull(manager.getMonitor(secondId));
            assertEquals(2, manager.listRunning().size());
            assertEquals(2, manager.listMonitors().size());

            assertTrue(manager.kill(firstId));
            assertTrue(manager.kill(secondId));
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
    // close() / shutdown
    // ===================================================================

    @Nested
    class CloseShutdown {

        @Test
        void closePublishesExitForKilledProcessOnCallingThreadBeforeReturning() throws Exception {
            AtomicReference<ProcessState> notifiedState = new AtomicReference<>();
            AtomicBoolean listenerSawInterruptedThread = new AtomicBoolean();
            CountDownLatch fired = new CountDownLatch(1);
            manager.addExitListener(entry -> {
                notifiedState.set(entry.getState());
                listenerSawInterruptedThread.set(Thread.currentThread().isInterrupted());
                fired.countDown();
            });
            ProcessEntry entry = manager.launch(
                    "sleep 60", "closed by the manager", Path.of(System.getProperty("user.dir")));

            // Wait for process to start
            Thread.sleep(500);
            assertTrue(entry.isRunning());

            manager.close();

            // No wait: close() must have already published the exit by the time it returns.
            assertEquals(0, fired.getCount(), "exit listener should already have run");
            assertEquals(ProcessState.KILLED, notifiedState.get());
            assertFalse(listenerSawInterruptedThread.get(),
                    "the listener must not run on a thread close() interrupted");
        }

        @Test
        void closeDoesNotInvokeMonitorListeners() throws Exception {
            AtomicBoolean monitorFired = new AtomicBoolean();
            manager.addMonitorListener((entry, monitor) -> monitorFired.set(true));

            ProcessEntry entry = manager.launch(
                    "sleep 60", "monitored, then closed", Path.of(System.getProperty("user.dir")));

            // Wait for process to start
            Thread.sleep(500);
            assertTrue(entry.isRunning());
            assertNotNull(manager.monitor(entry.getId(), "should not wake on close"));

            manager.close();

            assertFalse(monitorFired.get(), "close() must not wake a monitor listener");
        }

        @Test
        void launchAfterCloseIsRefusedWithoutALogDirectoryOrAProcess(@TempDir Path scratch)
                throws Exception {
            Path marker = scratch.resolve("started");
            manager.close();
            assertFalse(Files.exists(manager.getOutputDir()));

            IOException refused = assertThrows(IOException.class, () -> manager.launch(
                    "touch '" + marker + "'", "launched after close", scratch));

            assertTrue(refused.getMessage().contains("is closed"), refused.getMessage());
            assertFalse(Files.exists(manager.getOutputDir()),
                    "a closed manager must not recreate its log directory");
            assertTrue(manager.listAll().isEmpty(), "a refused launch must not be listed");
            Thread.sleep(500);
            assertFalse(Files.exists(marker), "a closed manager must not start the process");
        }

        @Test
        void closeRacingALaunchLoopLeavesNoProcessRunning(@TempDir Path scratch) throws Exception {
            // Each process records its pid before it sleeps, so one started past close() is
            // found even when its launch threw instead of returning an entry.
            Path pids = Files.createDirectories(scratch.resolve("pids"));
            CountDownLatch firstLaunched = new CountDownLatch(1);
            Thread launcher = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    try {
                        manager.launch("echo $$ > '" + pids.resolve("p" + i) + "'; exec sleep 60",
                                "racing close", scratch);
                    } catch (IOException | RuntimeException stopped) {
                        return;
                    }
                    firstLaunched.countDown();
                }
            }, "launch-racing-close");
            launcher.start();
            assertTrue(firstLaunched.await(10, TimeUnit.SECONDS), "no launch succeeded");
            Path firstPid = pids.resolve("p0");
            long running = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(firstPid) || Files.readString(firstPid).isBlank())
                    && System.nanoTime() < running) {
                Thread.sleep(10);
            }

            manager.close();
            launcher.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(launcher.isAlive(), "launches must stop once the manager is closed");
            // A process started past close() writes its pid meanwhile.
            Thread.sleep(500);

            List<Long> recorded = new ArrayList<>();
            try (var files = Files.list(pids)) {
                for (Path file : files.toList()) {
                    String pid = Files.readString(file).trim();
                    if (!pid.isEmpty()) recorded.add(Long.parseLong(pid));
                }
            }
            assertFalse(recorded.isEmpty(), "the first launch must have recorded its pid");
            List<ProcessHandle> survivors = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (ProcessHandle child : ProcessHandle.current().children()
                    .filter(child -> recorded.contains(child.pid())).toList()) {
                try {
                    child.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException stillRunning) {
                    survivors.add(child);
                }
            }
            try {
                assertTrue(survivors.isEmpty(), "processes outlived close(): " + survivors);
            } finally {
                survivors.forEach(ProcessHandle::destroyForcibly);
            }
        }

        @Test
        void closePublishesExitEvenWhenAKilledProcesssDescendantKeepsTheStdoutPipeOpen() throws Exception {
            // killProcess() only signals the direct child pid. A backgrounded grandchild that
            // inherited the same stdout pipe survives the parent's SIGTERM/SIGKILL and keeps the
            // bg-proc-io capture thread blocked in a read() that will not see EOF on its own.
            // close()'s bounded executor drain (~1s) cannot cover this case: only
            // killAllRunning()'s own self-publish (fireExit) can still publish the exit on time.
            ProcessEntry entry = manager.launch(
                    "sleep 5 & echo GRANDCHILD_PID=$! ; wait",
                    "grandchild keeps the pipe open past the parent's death",
                    Path.of(System.getProperty("user.dir")));

            // Wait for process to start
            Thread.sleep(500);
            assertTrue(entry.isRunning());

            AtomicReference<ProcessState> notifiedState = new AtomicReference<>();
            CountDownLatch fired = new CountDownLatch(1);
            manager.addExitListener(exited -> {
                notifiedState.set(exited.getState());
                fired.countDown();
            });

            long grandchildPid = -1;
            try {
                manager.close();

                // No wait: close() must have already published the exit, even with the
                // grandchild still holding the pipe open, by the time it returns.
                assertEquals(0, fired.getCount(), "exit listener should already have run");
                assertEquals(ProcessState.KILLED, notifiedState.get());

                String output = Files.readString(entry.getOutputFile());
                int idx = output.indexOf("GRANDCHILD_PID=");
                assertTrue(idx >= 0, "expected the grandchild pid marker in output: " + output);
                String tail = output.substring(idx + "GRANDCHILD_PID=".length());
                int end = 0;
                while (end < tail.length() && Character.isDigit(tail.charAt(end))) {
                    end++;
                }
                grandchildPid = Long.parseLong(tail.substring(0, end));
            } finally {
                if (grandchildPid > 0) {
                    new ProcessBuilder("kill", "-9", String.valueOf(grandchildPid))
                            .redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS);
                }
            }
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

        @Test
        void newManagerForSameSession_shouldNumberAfterEarlierLogsAndKeepThem(@TempDir Path workDir)
                throws Exception {
            // The web chat starts a manager per run: the second run's first launch must
            // not reuse proc-001 and truncate the first run's log.
            Files.createDirectories(workDir.resolve(".kompile"));
            String sid = "resumed-session-" + System.nanoTime();
            ProcessEntry first;
            try (BackgroundProcessManager firstRun = new BackgroundProcessManager(sid, workDir)) {
                first = firstRun.launch("echo first-run-output", "first run", workDir);
                awaitExit(first);
            }
            assertEquals("proc-001", first.getId());

            try (BackgroundProcessManager secondRun = new BackgroundProcessManager(sid, workDir)) {
                ProcessEntry second = secondRun.launch("echo second-run-output", "second run", workDir);
                awaitExit(second);
                assertEquals("proc-002", second.getId());
                assertEquals(List.of("second-run-output"), Files.readAllLines(second.getOutputFile()));
            }
            assertEquals(List.of("first-run-output"), Files.readAllLines(first.getOutputFile()));
        }

        @Test
        void highestUsedIdNumber_shouldCountEveryKindAndIgnoreOtherFiles(@TempDir Path dir)
                throws Exception {
            assertEquals(0, BackgroundProcessManager.highestUsedIdNumber(dir.resolve("missing")));
            Files.writeString(dir.resolve("proc-003.log"), "");
            Files.writeString(dir.resolve("mcp-041.log"), "");
            Files.writeString(dir.resolve("proc-999.txt"), "");
            Files.writeString(dir.resolve("PROC-500.log"), "");
            Files.writeString(dir.resolve("proc-abc.log"), "");
            assertEquals(41, BackgroundProcessManager.highestUsedIdNumber(dir));
        }

        private void awaitExit(ProcessEntry entry) throws InterruptedException {
            for (int attempts = 0; entry.isRunning() && attempts < 50; attempts++) {
                Thread.sleep(100);
            }
            assertFalse(entry.isRunning(), entry.getId() + " should have exited");
        }
    }

    // ===================================================================
    // Virtual entry output (the MCP tool-bridge log)
    // ===================================================================

    @Nested
    class VirtualOutput {

        @TempDir Path workDir;
        private BackgroundProcessManager local;

        @BeforeEach
        void setUpProjectManager() throws IOException {
            // A project .kompile keeps the durable logs inside the temp dir.
            Files.createDirectories(workDir.resolve(".kompile"));
            local = new BackgroundProcessManager("virtual-output-" + System.nanoTime(), workDir);
        }

        @AfterEach
        void closeProjectManager() {
            local.close();
        }

        @Test
        void appendVirtualOutput_shouldWriteLogAndNotifyListenersInOrder() throws IOException {
            List<String> seen = new CopyOnWriteArrayList<>();
            local.addOutputListener((entry, line) -> seen.add(entry.getId() + ": " + line));
            ProcessEntry entry = local.registerVirtual(
                    ProcessKind.MCP, "mcp", "MCP tool bridge log", Map.of());

            assertTrue(entry.getId().startsWith("mcp-"), entry.getId());
            assertEquals("mcp", ProcessKind.MCP.label());
            assertTrue(entry.isVirtual());
            assertTrue(local.appendVirtualOutput(entry.getId(), "[MCP] first"));
            assertTrue(local.appendVirtualOutput(entry.getId(), "[MCP] second"));

            assertEquals(List.of("[MCP] first", "[MCP] second"),
                    Files.readAllLines(entry.getOutputFile()));
            assertEquals(List.of(entry.getId() + ": [MCP] first", entry.getId() + ": [MCP] second"), seen);
            assertEquals("[MCP] first\n[MCP] second", local.readOutput(entry.getId(), 10));
            assertTrue(entry.isRunning(), "output must not end the entry");
        }

        @Test
        void appendVirtualOutput_shouldRefuseEntriesItDoesNotOwn() throws IOException {
            assertFalse(local.appendVirtualOutput(null, "line"));
            assertFalse(local.appendVirtualOutput("mcp-999", "line"));

            ProcessEntry stopped = local.registerVirtual(
                    ProcessKind.MCP, "mcp", "MCP tool bridge log", Map.of());
            assertTrue(local.kill(stopped.getId()));
            assertFalse(local.appendVirtualOutput(stopped.getId(), "after kill"));
            assertFalse(Files.exists(stopped.getOutputFile()));

            ProcessEntry owned = local.launch("sleep 5", "owned", workDir);
            try {
                assertFalse(local.appendVirtualOutput(owned.getId(), "not virtual"));
            } finally {
                local.kill(owned.getId());
            }
        }

        @Test
        void appendVirtualOutput_shouldRefuseAfterCloseWithoutRecreatingTheLogDirectory() throws IOException {
            ProcessEntry entry = local.registerVirtual(
                    ProcessKind.MCP, "mcp", "MCP tool bridge log", Map.of());
            assertTrue(local.appendVirtualOutput(entry.getId(), "[MCP] open"));
            Path logDir = entry.getOutputFile().getParent();

            // The directory can be deleted with its session (a temporary home) while a
            // stale writer still holds the entry.
            local.close();
            assertTrue(entry.isRunning(), "close leaves a virtual entry to its owner");
            try (var walk = Files.walk(logDir)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {}
                        });
            }
            assertFalse(Files.exists(logDir), "precondition: the log directory is gone");

            assertFalse(local.appendVirtualOutput(entry.getId(), "[MCP] after close"));
            assertFalse(Files.exists(logDir), "a closed manager must not recreate its log directory");
        }

        @Test
        void killingVirtualEntry_shouldRunItsStopHandlerOnceAfterMarkingItKilled() {
            AtomicInteger stops = new AtomicInteger();
            AtomicReference<ProcessState> stateSeenByHandler = new AtomicReference<>();
            AtomicReference<ProcessEntry> task = new AtomicReference<>();
            task.set(local.registerVirtual(ProcessKind.COMMAND, "claude local_agent", "Claude: review",
                    Map.of("task_id", "t1"), () -> {
                        stops.incrementAndGet();
                        stateSeenByHandler.set(task.get().getState());
                    }));
            List<String> exits = new CopyOnWriteArrayList<>();
            local.addExitListener(entry -> exits.add(entry.getId() + "=" + entry.getExitCode()));

            assertTrue(task.get().isVirtual());
            assertTrue(task.get().isKillable(), "a stop handler makes a virtual entry killable");
            assertTrue(local.kill(task.get().getId()));
            assertFalse(local.kill(task.get().getId()), "a killed entry cannot be killed again");

            assertEquals(1, stops.get());
            assertEquals(ProcessState.KILLED, stateSeenByHandler.get());
            assertEquals(ProcessState.KILLED, task.get().getState());
            assertFalse(task.get().isKillable());
            assertEquals(List.of(task.get().getId() + "=-1"), exits);
        }

        @Test
        void virtualEntry_shouldOnlyBeKillableWithAStopHandlerAndNeverStopOnCompletion() {
            AtomicInteger stops = new AtomicInteger();
            ProcessEntry watcher = local.registerVirtual(ProcessKind.JUDGE, "judge", "Judge", Map.of());
            ProcessEntry task = local.registerVirtual(
                    ProcessKind.COMMAND, "claude local_bash", "Claude: build", Map.of(), stops::incrementAndGet);
            ProcessEntry failed = local.registerVirtual(
                    ProcessKind.COMMAND, "claude local_agent", "Claude: tests", Map.of(), stops::incrementAndGet);

            assertFalse(watcher.isKillable(), "a watcher without a stop handler is inspect-only");
            assertTrue(local.complete(task.getId()));
            assertTrue(local.fail(failed.getId(), 1));

            assertEquals(0, stops.get(), "only a kill runs the stop handler");
            assertFalse(task.isKillable());
            assertFalse(failed.isKillable());
            assertFalse(local.kill(task.getId()));
            assertEquals(0, stops.get());
        }

        @Test
        void throwingStopHandler_shouldStillLeaveTheEntryKilled() {
            ProcessEntry task = local.registerVirtual(ProcessKind.COMMAND, "claude task", "Claude: task",
                    Map.of(), () -> { throw new IllegalStateException("stop failed"); });

            assertTrue(local.kill(task.getId()));
            assertEquals(ProcessState.KILLED, task.getState());
            assertEquals(-1, task.getExitCode());
        }
    }
}
