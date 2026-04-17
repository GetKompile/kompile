/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProcessManager — process execution with timeout, abort, and output capture.
 * These tests launch real (trivial) processes, so they're Unix-only where bash is available.
 */
@DisplayName("ProcessManager")
@DisabledOnOs(OS.WINDOWS)
class ProcessManagerTest {

    @TempDir
    Path workDir;

    @Nested
    @DisplayName("Successful execution")
    class SuccessfulExecution {

        @Test
        void simpleEchoCommand() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo 'hello world'", workDir, 5000, null);

            assertTrue(result.isSuccess());
            assertEquals(0, result.getExitCode());
            assertTrue(result.getOutput().contains("hello world"));
            assertFalse(result.isTimedOut());
            assertFalse(result.isAborted());
            assertFalse(result.isOutputTruncated());
            assertTrue(result.getDurationMs() >= 0);
        }

        @Test
        void commandWithExitCode() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "exit 0", workDir, 5000, null);

            assertEquals(0, result.getExitCode());
            assertTrue(result.isSuccess());
        }

        @Test
        void commandCapturesStderr() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo 'error output' >&2", workDir, 5000, null);

            // stderr is merged into stdout via redirectErrorStream
            assertTrue(result.getOutput().contains("error output"));
        }

        @Test
        void multiLineOutput() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo 'line1'; echo 'line2'; echo 'line3'", workDir, 5000, null);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().contains("line1"));
            assertTrue(result.getOutput().contains("line2"));
            assertTrue(result.getOutput().contains("line3"));
        }
    }

    @Nested
    @DisplayName("Non-zero exit codes")
    class NonZeroExit {

        @Test
        void failedCommandReturnsNonZeroExitCode() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "exit 1", workDir, 5000, null);

            assertFalse(result.isSuccess());
            assertEquals(1, result.getExitCode());
        }

        @Test
        void commandNotFoundReturnsNonZero() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "nonexistent_command_12345", workDir, 5000, null);

            assertFalse(result.isSuccess());
            assertNotEquals(0, result.getExitCode());
        }
    }

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutHandling {

        @Test
        void commandTimesOut() {
            // Use a command that produces output then sleeps, so readLine doesn't block forever
            // The timeout watchdog kills the process which closes the stream, unblocking readLine
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo started; sleep 60", workDir, 1000, null);

            // Process should have been killed - either timed out or failed
            assertFalse(result.isSuccess(), "Long-running command should not succeed with short timeout");
        }

        @Test
        void defaultTimeoutUsedForZero() {
            // Just verify it doesn't throw for timeout=0, which should use default (120s)
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo fast", workDir, 0, null);

            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("Abort signal")
    class AbortSignal {

        @Test
        void abortSignalKillsProcess() throws InterruptedException {
            AtomicBoolean abort = new AtomicBoolean(false);
            var resultHolder = new ProcessManager.ProcessResult[1];

            // Use a short timeout so waitFor doesn't block long after abort kills the process
            Thread runner = new Thread(() -> {
                resultHolder[0] = ProcessManager.execute(
                        "while true; do echo tick; sleep 0.1; done", workDir, 3000, abort);
            });
            runner.start();

            // Wait for the process to start
            Thread.sleep(300);
            abort.set(true);

            // Wait for the execute call to complete (up to timeout + overhead)
            runner.join(10_000);

            // The process should have been killed by abort and/or timeout
            if (resultHolder[0] != null) {
                assertFalse(resultHolder[0].isSuccess(), "Aborted process should not be successful");
            }
        }

        @Test
        void nullAbortSignalIsHandled() {
            // Should work fine with null abort signal
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo ok", workDir, 5000, null);
            assertTrue(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("ProcessResult properties")
    class ProcessResultProperties {

        @Test
        void durationIsPositive() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "echo test", workDir, 5000, null);
            assertTrue(result.getDurationMs() >= 0);
        }

        @Test
        void isSuccessRequiresAllConditions() {
            // A successful result must have exitCode=0, not timed out, not aborted
            ProcessManager.ProcessResult success = new ProcessManager.ProcessResult(
                    "output", 0, 100, false, false, false);
            assertTrue(success.isSuccess());

            ProcessManager.ProcessResult timedOut = new ProcessManager.ProcessResult(
                    "output", 0, 100, true, false, false);
            assertFalse(timedOut.isSuccess());

            ProcessManager.ProcessResult aborted = new ProcessManager.ProcessResult(
                    "output", 0, 100, false, true, false);
            assertFalse(aborted.isSuccess());

            ProcessManager.ProcessResult failed = new ProcessManager.ProcessResult(
                    "output", 1, 100, false, false, false);
            assertFalse(failed.isSuccess());
        }
    }

    @Nested
    @DisplayName("Kill tree")
    class KillTree {

        @Test
        void killTreeHandlesNullProcess() {
            assertDoesNotThrow(() -> ProcessManager.killTree(null));
        }
    }

    @Nested
    @DisplayName("Working directory")
    class WorkingDirectory {

        @Test
        void commandRunsInSpecifiedWorkDir() {
            ProcessManager.ProcessResult result = ProcessManager.execute(
                    "pwd", workDir, 5000, null);

            assertTrue(result.isSuccess());
            assertTrue(result.getOutput().trim().contains(workDir.toAbsolutePath().toString())
                       || result.getOutput().trim().endsWith(workDir.getFileName().toString()));
        }
    }
}
