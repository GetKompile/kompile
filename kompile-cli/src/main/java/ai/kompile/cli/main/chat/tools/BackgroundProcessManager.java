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

import ai.kompile.cli.common.KompileHome;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks and manages background processes launched by the chat agent.
 * Provides launching, output capture, status tracking, and kill operations.
 *
 * Output is captured to files under {@code ~/.kompile/process-output/<session-id>/}.
 * Process entries are tracked in memory and can be listed, queried, and cleaned up.
 */
public class BackgroundProcessManager {

    /**
     * Callback invoked when a tracked background process exits.
     */
    @FunctionalInterface
    public interface ExitCallback {
        void onProcessExit(ProcessEntry entry);
    }

    /**
     * State of a tracked process.
     */
    public enum ProcessState {
        RUNNING, COMPLETED, FAILED, KILLED
    }

    /**
     * Information about a tracked process.
     */
    public static class ProcessEntry {
        private final String id;
        private final String command;
        private final long pid;
        private final Instant startTime;
        private volatile Instant endTime;
        private volatile Integer exitCode;
        private volatile ProcessState state;
        private final Path outputFile;
        private final String description;
        private final Process process;

        ProcessEntry(String id, String command, long pid, Instant startTime,
                     Path outputFile, String description, Process process) {
            this.id = id;
            this.command = command;
            this.pid = pid;
            this.startTime = startTime;
            this.endTime = null;
            this.exitCode = null;
            this.state = ProcessState.RUNNING;
            this.outputFile = outputFile;
            this.description = description;
            this.process = process;
        }

        public String getId() { return id; }
        public String getCommand() { return command; }
        public long getPid() { return pid; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public Integer getExitCode() { return exitCode; }
        public ProcessState getState() { return state; }
        public Path getOutputFile() { return outputFile; }
        public String getDescription() { return description; }

        /**
         * Duration from start to end (or to now if still running).
         */
        public Duration getDuration() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end);
        }

        /**
         * Whether the process is still running.
         */
        public boolean isRunning() {
            return state == ProcessState.RUNNING;
        }
    }

    private static final boolean IS_UNIX =
            !System.getProperty("os.name", "").toLowerCase().startsWith("win");

    private final String sessionId;
    private final Path outputDir;
    private final Map<String, ProcessEntry> processes = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ExecutorService ioExecutor;
    private volatile ExitCallback exitCallback;
    private final Thread shutdownHook;

    /**
     * Default retention for completed process entries (1 hour).
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(1);

    public BackgroundProcessManager(String sessionId) {
        this.sessionId = sessionId;
        this.outputDir = KompileHome.homeDirectory().toPath()
                .resolve("process-output")
                .resolve(sessionId);
        this.ioExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bg-proc-io");
            t.setDaemon(true);
            return t;
        });

        // Register shutdown hook to kill all running processes
        this.shutdownHook = new Thread(() -> {
            for (ProcessEntry entry : processes.values()) {
                if (entry.isRunning() && entry.process != null && entry.process.isAlive()) {
                    killProcess(entry);
                }
            }
            ioExecutor.shutdownNow();
        }, "bg-proc-manager-shutdown-" + sessionId);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    /**
     * Set a callback invoked when any tracked process exits.
     */
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    /**
     * Launch a background process from a command string.
     *
     * @param command     shell command to execute
     * @param description human-readable description of the process
     * @param workDir     working directory for the process
     * @return the new ProcessEntry
     * @throws IOException if the process cannot be started or output directory cannot be created
     */
    public ProcessEntry launch(String command, String description, Path workDir) throws IOException {
        return launch(new String[]{"bash", "-c", command}, command, description, workDir);
    }

    /**
     * Launch a background process from an argument array.
     *
     * @param args        command and arguments
     * @param description human-readable description of the process
     * @param workDir     working directory for the process
     * @return the new ProcessEntry
     * @throws IOException if the process cannot be started or output directory cannot be created
     */
    public ProcessEntry launch(String[] args, String description, Path workDir) throws IOException {
        String command = String.join(" ", args);
        return launch(args, command, description, workDir);
    }

    private ProcessEntry launch(String[] args, String command, String description, Path workDir) throws IOException {
        // Ensure output directory exists
        Files.createDirectories(outputDir);

        String id = "proc-" + String.format("%03d", counter.incrementAndGet());
        Path outputFile = outputDir.resolve(id + ".log");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workDir != null ? workDir.toFile() : new File("."));
        pb.redirectErrorStream(true);

        // Inherit key environment variables
        Map<String, String> env = pb.environment();
        for (String key : List.of("PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "TERM")) {
            String val = System.getenv(key);
            if (val != null) env.put(key, val);
        }

        Process process = pb.start();
        ProcessEntry entry = new ProcessEntry(
                id, command, process.pid(), Instant.now(), outputFile, description, process);
        processes.put(id, entry);

        // Start daemon thread to capture output and watch for exit
        ioExecutor.submit(() -> captureOutputAndWait(entry));

        return entry;
    }

    /**
     * Capture stdout/stderr to the output file and update the entry when the process exits.
     */
    private void captureOutputAndWait(ProcessEntry entry) {
        try (InputStream is = entry.process.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is));
             BufferedWriter writer = Files.newBufferedWriter(entry.outputFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }

            // Process has exited; get exit code
            int exitCode = entry.process.waitFor();
            entry.endTime = Instant.now();
            entry.exitCode = exitCode;
            entry.state = exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry.endTime = Instant.now();
            entry.exitCode = -1;
            entry.state = ProcessState.KILLED;
        } catch (IOException e) {
            entry.endTime = Instant.now();
            entry.exitCode = -1;
            entry.state = ProcessState.FAILED;
        }

        // Invoke callback
        ExitCallback cb = exitCallback;
        if (cb != null) {
            try {
                cb.onProcessExit(entry);
            } catch (Exception ignored) {
                // Don't let callback errors propagate
            }
        }
    }

    /**
     * Kill a tracked process by its process ID string (e.g. "proc-001").
     *
     * @return true if the process was found and kill was attempted
     */
    public boolean kill(String processId) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null) return false;
        return killProcess(entry);
    }

    /**
     * Kill a tracked process by its OS PID.
     *
     * @return true if a matching process was found and kill was attempted
     */
    public boolean killByPid(long pid) {
        for (ProcessEntry entry : processes.values()) {
            if (entry.pid == pid && entry.isRunning()) {
                return killProcess(entry);
            }
        }
        return false;
    }

    private boolean killProcess(ProcessEntry entry) {
        if (!entry.isRunning() || entry.process == null || !entry.process.isAlive()) {
            return false;
        }

        long pid = entry.pid;
        if (IS_UNIX) {
            try {
                new ProcessBuilder("kill", "-TERM", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                entry.process.destroy();
            }

            try {
                boolean exited = entry.process.waitFor(500, TimeUnit.MILLISECONDS);
                if (!exited) {
                    try {
                        new ProcessBuilder("kill", "-9", String.valueOf(pid))
                                .redirectErrorStream(true).start().waitFor(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        entry.process.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                entry.process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        } else {
            try {
                new ProcessBuilder("taskkill", "/pid", String.valueOf(pid), "/f", "/t")
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                entry.process.destroyForcibly();
            }
        }

        entry.endTime = Instant.now();
        entry.state = ProcessState.KILLED;
        entry.exitCode = -1;

        return true;
    }

    /**
     * Get a process entry by ID.
     */
    public ProcessEntry get(String processId) {
        return processes.get(processId);
    }

    /**
     * List all tracked processes.
     */
    public List<ProcessEntry> listAll() {
        List<ProcessEntry> list = new ArrayList<>(processes.values());
        list.sort(Comparator.comparing(ProcessEntry::getStartTime));
        return list;
    }

    /**
     * List only running processes.
     */
    public List<ProcessEntry> listRunning() {
        List<ProcessEntry> list = new ArrayList<>();
        for (ProcessEntry entry : processes.values()) {
            if (entry.isRunning()) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparing(ProcessEntry::getStartTime));
        return list;
    }

    /**
     * Read the last N lines of a process's captured output.
     *
     * @param processId the process ID
     * @param tailLines number of lines to return from the end
     * @return the output lines, or an error message if unavailable
     */
    public String readOutput(String processId, int tailLines) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null) {
            return "Process not found: " + processId;
        }

        Path file = entry.outputFile;
        if (!Files.exists(file)) {
            return "(no output captured yet)";
        }

        try {
            List<String> allLines = Files.readAllLines(file);
            if (allLines.isEmpty()) {
                return "(no output)";
            }

            int start = Math.max(0, allLines.size() - tailLines);
            List<String> tail = allLines.subList(start, allLines.size());

            StringBuilder sb = new StringBuilder();
            if (start > 0) {
                sb.append("... (").append(start).append(" earlier lines omitted)\n");
            }
            for (String line : tail) {
                sb.append(line).append("\n");
            }
            return sb.toString().stripTrailing();

        } catch (IOException e) {
            return "Error reading output: " + e.getMessage();
        }
    }

    /**
     * Remove completed/failed/killed process entries older than the default retention period.
     *
     * @return number of entries removed
     */
    public int cleanup() {
        return cleanup(DEFAULT_RETENTION);
    }

    /**
     * Remove completed/failed/killed process entries older than the given retention.
     *
     * @return number of entries removed
     */
    public int cleanup(Duration retention) {
        Instant cutoff = Instant.now().minus(retention);
        int removed = 0;

        Iterator<Map.Entry<String, ProcessEntry>> it = processes.entrySet().iterator();
        while (it.hasNext()) {
            ProcessEntry entry = it.next().getValue();
            if (!entry.isRunning() && entry.endTime != null && entry.endTime.isBefore(cutoff)) {
                it.remove();
                // Also delete the output file
                try {
                    Files.deleteIfExists(entry.outputFile);
                } catch (IOException ignored) {}
                removed++;
            }
        }

        return removed;
    }

    /**
     * Get the output directory for this session's process logs.
     */
    public Path getOutputDir() {
        return outputDir;
    }

    /**
     * Get the session ID this manager is tracking for.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Shuts down this manager: kills all running processes, shuts down the I/O executor,
     * and removes the JVM shutdown hook to prevent accumulation across multiple sessions.
     * Should be called when the chat session ends.
     */
    public void close() {
        // Kill all running processes
        for (ProcessEntry entry : processes.values()) {
            if (entry.isRunning() && entry.process != null && entry.process.isAlive()) {
                killProcess(entry);
            }
        }
        ioExecutor.shutdownNow();

        // Remove shutdown hook to prevent leak
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down — hook can't be removed, which is fine
        }
    }
}
