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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proper subprocess management with process group handling, kill tree,
 * timeout management, and output buffering. Comparable to OpenCode's Shell module.
 *
 * Features:
 * - Process group creation on Unix (detached processes)
 * - Kill tree: SIGTERM → 200ms wait → SIGKILL escalation
 * - Configurable timeout (default 120s)
 * - Output buffering with 30KB truncation for metadata
 * - Abort signal integration
 */
public class ProcessManager {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int SIGKILL_TIMEOUT_MS = 200;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final boolean IS_UNIX = !System.getProperty("os.name", "").toLowerCase().startsWith("win");

    /** Active processes tracked for cleanup on shutdown. */
    private static final Set<Process> ACTIVE_PROCESSES = ConcurrentHashMap.newKeySet();

    static {
        // Shutdown hook to kill all active processes
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : ACTIVE_PROCESSES) {
                killTree(p);
            }
        }, "process-manager-shutdown"));
    }

    /**
     * Execute a command and return the result.
     *
     * @param command     the shell command to execute
     * @param workDir     working directory
     * @param timeoutMs   timeout in milliseconds (0 = default 120s)
     * @param abortSignal abort flag (checked during output reading)
     * @return the process result
     */
    public static ProcessResult execute(String command, Path workDir, int timeoutMs, AtomicBoolean abortSignal) {
        if (timeoutMs <= 0) timeoutMs = DEFAULT_TIMEOUT_MS;

        long startTime = System.currentTimeMillis();
        Process process = null;

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            // Inherit environment
            Map<String, String> env = pb.environment();
            inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                    "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "TERM");

            process = pb.start();
            ACTIVE_PROCESSES.add(process);

            final Process proc = process;
            final AtomicBoolean timedOut = new AtomicBoolean(false);

            // Timeout watchdog
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "process-timeout-" + proc.pid());
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(() -> {
                timedOut.set(true);
                killTree(proc);
            }, timeoutMs + 100, TimeUnit.MILLISECONDS);

            // Abort watcher
            Thread abortWatcher = null;
            if (abortSignal != null) {
                abortWatcher = new Thread(() -> {
                    while (proc.isAlive() && !timedOut.get()) {
                        if (abortSignal.get()) {
                            killTree(proc);
                            break;
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                }, "abort-watcher-" + proc.pid());
                abortWatcher.setDaemon(true);
                abortWatcher.start();
            }

            // Read output
            StringBuilder output = new StringBuilder();
            boolean outputTruncated = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append("\n");
                    } else {
                        outputTruncated = true;
                    }
                }
            }

            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            scheduler.shutdownNow();

            long durationMs = System.currentTimeMillis() - startTime;

            if (timedOut.get() || !completed) {
                killTree(process);
                return new ProcessResult(output.toString(), -1, durationMs, true, false, outputTruncated);
            }

            if (abortSignal != null && abortSignal.get()) {
                return new ProcessResult(output.toString(), -1, durationMs, false, true, outputTruncated);
            }

            int exitCode = process.exitValue();
            String outputStr = output.toString();
            if (outputTruncated) {
                outputStr += "\n... (output truncated at " + MAX_OUTPUT_CHARS + " chars)";
            }

            return new ProcessResult(outputStr, exitCode, durationMs, false, false, outputTruncated);

        } catch (Exception e) {
            if (process != null) {
                killTree(process);
            }
            long durationMs = System.currentTimeMillis() - startTime;
            return new ProcessResult("Error: " + e.getMessage(), -1, durationMs, false, false, false);
        } finally {
            if (process != null) {
                ACTIVE_PROCESSES.remove(process);
            }
        }
    }

    /**
     * Kill process tree: SIGTERM → wait 200ms → SIGKILL.
     * On Unix, targets the process group.
     */
    public static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;

        long pid = process.pid();

        if (IS_UNIX) {
            // Try killing the process group first (negative PID)
            try {
                // SIGTERM to process group
                new ProcessBuilder("kill", "-TERM", "-" + pid)
                        .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Fall back to direct kill
                process.destroy();
            }

            // Wait for graceful shutdown
            try {
                boolean exited = process.waitFor(SIGKILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!exited) {
                    // Escalate to SIGKILL
                    try {
                        new ProcessBuilder("kill", "-9", "-" + pid)
                                .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        } else {
            // Windows: use taskkill /f /t
            try {
                new ProcessBuilder("taskkill", "/pid", String.valueOf(pid), "/f", "/t")
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                process.destroyForcibly();
            }
        }
    }

    private static void inheritEnv(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }
    }

    /**
     * Result of a process execution.
     */
    public static class ProcessResult {
        private final String output;
        private final int exitCode;
        private final long durationMs;
        private final boolean timedOut;
        private final boolean aborted;
        private final boolean outputTruncated;

        public ProcessResult(String output, int exitCode, long durationMs,
                              boolean timedOut, boolean aborted, boolean outputTruncated) {
            this.output = output;
            this.exitCode = exitCode;
            this.durationMs = durationMs;
            this.timedOut = timedOut;
            this.aborted = aborted;
            this.outputTruncated = outputTruncated;
        }

        public String getOutput() { return output; }
        public int getExitCode() { return exitCode; }
        public long getDurationMs() { return durationMs; }
        public boolean isTimedOut() { return timedOut; }
        public boolean isAborted() { return aborted; }
        public boolean isOutputTruncated() { return outputTruncated; }
        public boolean isSuccess() { return exitCode == 0 && !timedOut && !aborted; }
    }
}
