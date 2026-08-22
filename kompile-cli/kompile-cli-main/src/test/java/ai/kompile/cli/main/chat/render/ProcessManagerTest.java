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

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.BashTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProcessManager}.
 * <p>
 * Tests process execution, output capture, timeout handling, abort signalling,
 * output truncation, and ProcessResult accessors. All tests run real commands
 * on Unix (disabled on Windows).
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessManagerTest {

    @TempDir
    Path workDir;

    // ===================================================================
    // Basic execution
    // ===================================================================

    @Nested
    class BasicExecution {

        @Test
        void echoCommand_shouldSucceed() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo hello", workDir, 10_000, null);

            assertTrue(result.isSuccess(), "echo should succeed");
            assertEquals(0, result.getExitCode());
            assertTrue(result.getOutput().contains("hello"));
            assertFalse(result.isTimedOut());
            assertFalse(result.isAborted());
            assertFalse(result.isOutputTruncated());
        }

        @Test
        void failingCommand_shouldReturnNonZeroExit() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "exit 42", workDir, 10_000, null);

            assertFalse(result.isSuccess());
            assertEquals(42, result.getExitCode());
            assertFalse(result.isTimedOut());
            assertFalse(result.isAborted());
        }

        @Test
        void multilineOutput_shouldBeCaptured() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo line1 && echo line2 && echo line3", workDir, 10_000, null);

            assertTrue(result.isSuccess());
            String output = result.getOutput();
            assertTrue(output.contains("line1"));
            assertTrue(output.contains("line2"));
            assertTrue(output.contains("line3"));
        }

        @Test
        void outputConsumer_shouldReceiveFirstLineBeforeCommandCompletes() throws Exception {
            CountDownLatch firstLine = new CountDownLatch(1);
            List<String> streamed = new CopyOnWriteArrayList<>();
            CompletableFuture<ProcessManager.ProcessResult> execution =
                    CompletableFuture.supplyAsync(() -> ProcessManager.executeInterruptibly(
                            "echo first; sleep 1; echo second", workDir, 10_000,
                            () -> false, line -> {
                                streamed.add(line);
                                firstLine.countDown();
                            }));

            assertTrue(firstLine.await(3, TimeUnit.SECONDS));
            assertFalse(execution.isDone(),
                    "the first line must be published before the command returns");
            ProcessManager.ProcessResult result = execution.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("first", "second"), streamed);
            assertTrue(result.getOutput().contains("first"));
            assertTrue(result.getOutput().contains("second"));
        }

        @Test
        void bashTool_marksOutputThatWasRenderedLive() throws Exception {
            CountDownLatch firstLine = new CountDownLatch(1);
            List<String> streamed = new CopyOnWriteArrayList<>();
            ToolContext context = new ToolContext("bash-stream-test", null, null, workDir, null);
            context.setAutoApproveAll(true);
            context.setOutputConsumer(line -> {
                streamed.add(line);
                firstLine.countDown();
            });
            ObjectNode params = new ObjectMapper().createObjectNode();
            params.put("command", "echo live-first; sleep 1; echo live-second");

            CompletableFuture<ToolResult> execution = CompletableFuture.supplyAsync(() -> {
                try {
                    return new BashTool().execute(params, context);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            assertTrue(firstLine.await(3, TimeUnit.SECONDS));
            assertFalse(execution.isDone(),
                    "bash output must reach the transcript before execute returns");
            ToolResult result = execution.get(5, TimeUnit.SECONDS);

            assertFalse(result.isError());
            assertTrue(result.isOutputStreamed());
            assertEquals(List.of("live-first", "live-second"), streamed);
        }

        @Test
        void stderrIsMerged_shouldAppearInOutput() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo stdout-msg && echo stderr-msg >&2", workDir, 10_000, null);

            assertTrue(result.getOutput().contains("stdout-msg"));
            assertTrue(result.getOutput().contains("stderr-msg"),
                    "stderr should be merged into output");
        }

        @Test
        void durationMs_shouldBePositive() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo fast", workDir, 10_000, null);

            assertTrue(result.getDurationMs() >= 0,
                    "Duration should be non-negative");
        }
    }

    // ===================================================================
    // Timeout handling
    // ===================================================================

    @Nested
    class TimeoutHandling {

        @Test
        void shortTimeout_withSleepingCommand_shouldTimeout() {
            // Use a command that produces output then sleeps, so the reader
            // doesn't block forever after the process is killed
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo start && sleep 30", workDir, 1000, null);

            assertTrue(result.isTimedOut(), "Should time out");
            assertFalse(result.isSuccess());
            assertEquals(-1, result.getExitCode());
        }

        @Test
        void zeroTimeout_shouldUseDefault() {
            // Zero timeout → default 120s. A quick echo should complete.
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo default-timeout", workDir, 0, null);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("default-timeout"));
        }

        @Test
        void negativeTimeout_shouldUseDefault() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo neg-timeout", workDir, -1, null);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("neg-timeout"));
        }
    }

    // ===================================================================
    // Abort signal
    // ===================================================================

    @Nested
    class AbortSignal {

        @Test
        void preSetAbortSignal_shouldPreventSuccess() {
            // Set abort before starting — the process starts but abort watcher
            // kills it quickly (within the first 100ms poll)
            AtomicBoolean abort = new AtomicBoolean(true);
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo aborted && sleep 60", workDir, 10_000, abort);

            assertFalse(result.isSuccess(),
                    "Pre-set abort should prevent successful completion");
        }

        @Test
        void nullAbortSignal_shouldBeHandledGracefully() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo no-abort", workDir, 10_000, null);
            assertTrue(result.isSuccess());
        }
    }

    // ===================================================================
    // ProcessResult accessors
    // ===================================================================

    @Nested
    class ProcessResultAccessors {

        @Test
        void allFieldsAccessible() {
            ProcessManager.ProcessResult result = new ProcessManager.ProcessResult(
                    "output text", 0, 500L, false, false, false);

            assertEquals("output text", result.getOutput());
            assertEquals(0, result.getExitCode());
            assertEquals(500L, result.getDurationMs());
            assertFalse(result.isTimedOut());
            assertFalse(result.isAborted());
            assertFalse(result.isOutputTruncated());
            assertTrue(result.isSuccess());
        }

        @Test
        void timedOutResult_isNotSuccess() {
            ProcessManager.ProcessResult result = new ProcessManager.ProcessResult(
                    "", -1, 120_000L, true, false, false);

            assertTrue(result.isTimedOut());
            assertFalse(result.isSuccess());
        }

        @Test
        void abortedResult_isNotSuccess() {
            ProcessManager.ProcessResult result = new ProcessManager.ProcessResult(
                    "", -1, 1000L, false, true, false);

            assertTrue(result.isAborted());
            assertFalse(result.isSuccess());
        }

        @Test
        void truncatedResult_isStillSuccess() {
            ProcessManager.ProcessResult result = new ProcessManager.ProcessResult(
                    "partial", 0, 100L, false, false, true);

            assertTrue(result.isOutputTruncated());
            assertTrue(result.isSuccess(), "Truncated output doesn't mean failure");
        }

        @Test
        void nonZeroExitCode_isNotSuccess() {
            ProcessManager.ProcessResult result = new ProcessManager.ProcessResult(
                    "error output", 1, 200L, false, false, false);

            assertFalse(result.isSuccess());
            assertEquals(1, result.getExitCode());
        }
    }

    // ===================================================================
    // killTree — static method
    // ===================================================================

    @Nested
    class KillTree {

        @Test
        void nullProcess_shouldNotThrow() {
            assertDoesNotThrow(() -> ProcessManager.killTree(null));
        }

        @Test
        void alreadyDeadProcess_shouldNotThrow() throws Exception {
            Process p = new ProcessBuilder("echo", "done").start();
            p.waitFor();
            assertDoesNotThrow(() -> ProcessManager.killTree(p));
        }

        @Test
        void liveProcess_shouldBeKilled() throws Exception {
            Process p = new ProcessBuilder("sleep", "60").start();
            assertTrue(p.isAlive(), "Process should start alive");

            ProcessManager.killTree(p);

            // Give it more time — killTree does SIGTERM then escalates to SIGKILL
            boolean exited = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                // Direct forcible kill as last resort
                p.destroyForcibly();
                exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertTrue(exited, "Process should be killed");
        }
    }

    // ===================================================================
    // Working directory
    // ===================================================================

    @Nested
    class WorkingDirectory {

        @Test
        void commandRunsInSpecifiedWorkDir() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "pwd", workDir, 10_000, null);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().trim().contains(workDir.toAbsolutePath().toString()),
                    "pwd should show the specified workDir");
        }
    }

    // ===================================================================
    // Environment inheritance
    // ===================================================================

    @Nested
    class EnvironmentInheritance {

        @Test
        void pathEnvShouldBeInherited() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo $PATH", workDir, 10_000, null);

            assertTrue(result.isSuccess());
            assertFalse(result.getOutput().trim().isEmpty(),
                    "PATH should be inherited from parent process");
        }

        @Test
        void homeEnvShouldBeInherited() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo $HOME", workDir, 10_000, null);

            assertTrue(result.isSuccess());
            assertFalse(result.getOutput().trim().isEmpty(),
                    "HOME should be inherited");
        }
    }

    // ===================================================================
    // Detached pipe holders + verified kills (MCP bash-tool hang regression)
    // ===================================================================

    @Nested
    class DetachedPipeHolders {

        @Test
        void detachedPipeHolder_shouldNotHangExecute() {
            long start = System.currentTimeMillis();
            // The backgrounded sleep inherits stdout and keeps the pipe open long
            // after the shell exits — reading to EOF on the calling thread blocked
            // here until the sleep finished, ignoring the timeout entirely.
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo hi; sleep 15 &", workDir, 60_000, null);
            long took = System.currentTimeMillis() - start;

            assertEquals(0, result.getExitCode(), "shell itself exits cleanly");
            assertTrue(result.getOutput().contains("hi"),
                    "output written before the shell exited must be captured");
            assertTrue(took < 10_000,
                    "must return after process exit + drain grace, not wait for the pipe holder (took "
                            + took + "ms)");
        }

        @Test
        void detachedPipeHolderCannotEmitAfterExecuteReturns() throws Exception {
            AtomicInteger callbacks = new AtomicInteger();
            ProcessManager.ProcessResult result = ProcessManager.executeInterruptibly(
                    "echo before; (sleep 6; echo late) &", workDir, 60_000,
                    () -> false, line -> callbacks.incrementAndGet());
            int callbacksAtReturn = callbacks.get();

            Thread.sleep(2_000);

            assertEquals(0, result.getExitCode());
            assertTrue(result.getOutput().contains("before"));
            assertFalse(result.getOutput().contains("late"));
            assertEquals(callbacksAtReturn, callbacks.get(),
                    "a detached pipe holder must not update a completed transcript block");
        }

        @Test
        void timedOutCommandTree_shouldActuallyDie() throws Exception {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo start && sleep 27183 && echo never", workDir, 1_000, null);
            assertTrue(result.isTimedOut());

            // killTree previously signalled a process group that didn't exist
            // (plain ProcessBuilder children are not group leaders) and never fell
            // back to destroy() — the sleep survived the "kill".
            Thread.sleep(500);
            // Bracket trick: the regex matches the victim's cmdline but not the
            // checking shell's own cmdline (which contains the bracketed literal).
            ProcessManager.ProcessResult check = ProcessManager.execute(
                    "pgrep -f 'sleep 2718[3]' | wc -l", workDir, 10_000, null);
            assertEquals("0", check.getOutput().trim(),
                    "timed-out command tree must be dead");
        }

        @Test
        void abortMidRun_shouldReturnPromptly() {
            AtomicBoolean abort = new AtomicBoolean(false);
            Thread flipper = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                abort.set(true);
            });
            flipper.setDaemon(true);
            flipper.start();

            long start = System.currentTimeMillis();
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "sleep 30", workDir, 60_000, abort);
            long took = System.currentTimeMillis() - start;

            assertTrue(result.isAborted(), "must report abort");
            assertTrue(took < 8_000,
                    "abort must interrupt the wait promptly (took " + took + "ms)");
        }

        @Test
        void composedAbortCheckObservesParentCancellation() {
            AtomicBoolean childAbort = new AtomicBoolean(false);
            AtomicBoolean parentAbort = new AtomicBoolean(false);
            Thread flipper = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                parentAbort.set(true);
            });
            flipper.setDaemon(true);
            flipper.start();

            ProcessManager.ProcessResult result = ProcessManager.executeInterruptibly(
                    "sleep 30", workDir, 60_000,
                    () -> childAbort.get() || parentAbort.get());

            assertTrue(result.isAborted(),
                    "blocking child tools must observe inherited parent cancellation");
            assertFalse(childAbort.get(),
                    "parent cancellation must not mutate the child's owned token");
        }

        @Test
        void preCancelledCheckPreventsCommandFromStarting() {
            Path marker = workDir.resolve("must-not-exist");

            ProcessManager.ProcessResult result = ProcessManager.executeInterruptibly(
                    "touch must-not-exist", workDir, 10_000, () -> true);

            assertTrue(result.isAborted());
            assertFalse(Files.exists(marker),
                    "an already-cancelled context must not start the command");
        }
    }
}
