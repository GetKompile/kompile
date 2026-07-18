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
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proper subprocess management with process group handling, kill tree,
 * timeout management, and output buffering. Comparable to OpenCode's Shell module.
 *
 * Features:
 * - Own process group/session on Linux via setsid(1), so group signals reach the whole tree
 * - Kill tree: SIGTERM → 200ms wait → SIGKILL escalation, always followed by direct
 *   destroy of the root and every descendant — a plain ProcessBuilder child is NOT a
 *   process group leader, so a group signal alone can be a silent no-op
 * - Timeout (default 120s) bounds the WHOLE call including output draining: a detached
 *   child that inherited the output pipe must never hang the caller past its deadline
 * - Output buffering with 30KB truncation for metadata
 * - Abort signal integration
 */
public class ProcessManager {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int SIGKILL_TIMEOUT_MS = 200;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    /** Grace period after process exit for the reader to drain straggler output. */
    private static final int OUTPUT_DRAIN_TIMEOUT_MS = 5_000;
    private static final boolean IS_UNIX = !System.getProperty("os.name", "").toLowerCase().startsWith("win");
    /** setsid(1) makes the shell its own session/group leader so {@code kill -- -pid} works; absent on macOS. */
    private static final String SETSID = detectSetsid();

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
     * @param abortSignal abort flag (checked while waiting for the process)
     * @return the process result
     */
    public static ProcessResult execute(String command, Path workDir, int timeoutMs, AtomicBoolean abortSignal) {
        if (timeoutMs <= 0) timeoutMs = DEFAULT_TIMEOUT_MS;

        long startTime = System.currentTimeMillis();
        long deadline = startTime + timeoutMs;
        Process process = null;

        try {
            List<String> argv = new ArrayList<>();
            if (SETSID != null) {
                argv.add(SETSID);
            }
            argv.add("bash");
            argv.add("-c");
            argv.add(command);
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            // Inherit environment
            Map<String, String> env = pb.environment();
            inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                    "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "TERM");

            process = pb.start();
            ACTIVE_PROCESSES.add(process);

            final Process proc = process;

            // Output is read on its own thread: the pipe can outlive the process (a
            // backgrounded child that inherited stdout keeps the write end open), and a
            // read on the calling thread would block past any timeout.
            final StringBuilder output = new StringBuilder();
            final AtomicBoolean outputTruncated = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() < MAX_OUTPUT_CHARS) {
                                output.append(line).append('\n');
                            } else {
                                outputTruncated.set(true);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Stream closed by a kill — nothing to report
                }
            }, "process-output-" + proc.pid());
            reader.setDaemon(true);
            reader.start();

            // Wait for exit, abort, or the deadline — whichever comes first.
            boolean timedOut = false;
            boolean aborted = false;
            while (proc.isAlive()) {
                if (abortSignal != null && abortSignal.get()) {
                    aborted = true;
                    break;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    timedOut = true;
                    break;
                }
                proc.waitFor(Math.min(remaining, 100), TimeUnit.MILLISECONDS);
            }

            if (timedOut || aborted) {
                killTree(proc);
            }

            // Let the reader drain the tail, then abandon it (daemon thread) — a
            // detached pipe holder must not stall the call.
            reader.join((timedOut || aborted) ? 500 : OUTPUT_DRAIN_TIMEOUT_MS);

            long durationMs = System.currentTimeMillis() - startTime;
            String outputStr;
            synchronized (output) {
                outputStr = output.toString();
            }
            boolean truncated = outputTruncated.get();
            if (truncated) {
                outputStr += "\n... (output truncated at " + MAX_OUTPUT_CHARS + " chars)";
            }

            if (timedOut) {
                return new ProcessResult(outputStr, -1, durationMs, true, false, truncated);
            }
            if (aborted) {
                return new ProcessResult(outputStr, -1, durationMs, false, true, truncated);
            }
            return new ProcessResult(outputStr, process.exitValue(), durationMs, false, false, truncated);

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
     * Kill process tree: SIGTERM → wait 200ms → SIGKILL, verified.
     * On Unix the process group is signalled first (effective when the process was
     * spawned via setsid and leads its own group), then the root and every descendant
     * are destroyed directly — the group signal alone reaches nothing when the child
     * is not a group leader.
     */
    public static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;

        long pid = process.pid();
        // Snapshot before killing the root — descendants reparent once it dies.
        List<ProcessHandle> descendants = process.descendants().toList();

        if (IS_UNIX) {
            try {
                new ProcessBuilder("kill", "-TERM", "--", "-" + pid)
                        .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // fall through to direct destroy below
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

        // The group kill is best-effort; always destroy the root and descendants directly.
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);

        try {
            if (!process.waitFor(SIGKILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (IS_UNIX) {
                    try {
                        new ProcessBuilder("kill", "-9", "--", "-" + pid)
                                .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // destroyForcibly below is the fallback
                    }
                }
                process.destroyForcibly();
                descendants.forEach(ProcessHandle::destroyForcibly);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
    }

    private static String detectSetsid() {
        if (!IS_UNIX) return null;
        for (String candidate : new String[]{"/usr/bin/setsid", "/bin/setsid"}) {
            File f = new File(candidate);
            if (f.canExecute()) return candidate;
        }
        return null;
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
