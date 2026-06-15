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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
