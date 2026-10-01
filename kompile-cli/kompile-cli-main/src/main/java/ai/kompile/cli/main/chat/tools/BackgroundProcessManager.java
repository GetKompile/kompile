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
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks and manages background processes launched by the chat agent.
 * Provides launching, output capture, status tracking, and kill operations.
 *
 * Output is captured to files under:
 * <ul>
 *   <li>{@code <project>/.kompile/process-output/<session-id>/} when a project .kompile
 *       directory is found in the working directory ancestry, and</li>
 *   <li>{@code ~/.kompile/process-output/<session-id>/} as fallback.</li>
 * </ul>
 * Process entries are tracked in memory and can be listed, queried, and cleaned up.
 */
public class BackgroundProcessManager implements AutoCloseable {

    /**
     * Callback invoked when a tracked background process exits.
     */
    @FunctionalInterface
    public interface ExitCallback {
        void onProcessExit(ProcessEntry entry);
    }

    /** One-shot configuration that wakes the chat agent when a process exits. */
    public record ProcessMonitor(String processId, String message, Instant createdAt) {
        public ProcessMonitor {
            message = message == null ? "" : message.strip();
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }

    /** Callback invoked only when a process with an explicit monitor exits. */
    @FunctionalInterface
    public interface MonitorCallback {
        void onMonitoredProcessExit(ProcessEntry entry, ProcessMonitor monitor);
    }

    /**
     * Callback invoked for every output line as it is captured. The callback runs on
     * the process I/O thread, after the line has been flushed to the durable log.
     */
    @FunctionalInterface
    public interface OutputCallback {
        void onProcessOutput(ProcessEntry entry, String line);
    }

    /**
     * State of a tracked process.
     */
    public enum ProcessState {
        RUNNING, COMPLETED, FAILED, KILLED
    }

    /**
     * Category of tracked work. COMMAND entries are real launched OS subprocesses;
     * JUDGE and ENFORCER entries can be lightweight watcher registrations backed
     * by another component's lifecycle; SHARED entries mirror processes owned by
     * another session (published through coordination state, e.g. the MCP process
     * tool) and are refreshed by {@code SharedProcessMirror}; MCP entries hold the
     * session's MCP tool-bridge log (written with {@link #appendVirtualOutput}) so
     * it is browsable as process output instead of printing into the transcript.
     */
    public enum ProcessKind {
        COMMAND,
        JUDGE,
        ENFORCER,
        SHARED,
        MCP;

        public String label() { return name().toLowerCase(Locale.ROOT); }
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
        private volatile Path outputFile;
        private volatile String description;
        private final Process process;
        private final ProcessKind kind;
        private volatile Map<String, String> metadata;
        /** Last observed size of a shared mirror's owner log; drives live refresh. */
        private volatile long sharedOutputSize = -1L;
        private final AtomicBoolean exitNotified = new AtomicBoolean(false);
        private final AtomicBoolean killRequested = new AtomicBoolean(false);
        /** Stops the work a virtual entry stands for; null when its owner cannot stop it. */
        private volatile Runnable stopHandler;
        /** Restored from the session's process history: an earlier run of the session launched it. */
        private volatile boolean fromHistory;
        /** OS start time of {@link #pid}; tells the recorded process from a later one reusing the PID. */
        private volatile Instant osStart;

        ProcessEntry(String id, String command, long pid, Instant startTime,
                     Path outputFile, String description, Process process,
                     ProcessKind kind, Map<String, String> metadata) {
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
            this.kind = kind != null ? kind : ProcessKind.COMMAND;
            this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
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
        public ProcessKind getKind() { return kind; }
        public Map<String, String> getMetadata() { return metadata; }
        public boolean isVirtual() { return process == null; }

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

        /**
         * Whether killing this entry stops what it stands for: an owned process, a
         * virtual entry registered with a stop handler, or a process an earlier run of
         * the session launched. Shared mirrors are never killable.
         */
        public boolean isKillable() {
            return isRunning() && kind != ProcessKind.SHARED
                    && (process != null || stopHandler != null || (fromHistory && pid > 0));
        }
    }

    private static final boolean IS_UNIX =
            !System.getProperty("os.name", "").toLowerCase().startsWith("win");

    /** A log file named by {@link #nextId}, e.g. {@code proc-007.log}; group 1 is the number. */
    private static final Pattern ID_LOG_FILE = Pattern.compile("^[a-z]+-([0-9]{1,9})\\.log$");

    /**
     * The session's process history, kept beside its logs once a run calls
     * {@link #enableSessionHistory}: the commands the session launched, so a later run
     * of it (the web chat starts one per message) lists them and reads their logs.
     */
    static final String HISTORY_FILE = "processes.json";
    private static final int HISTORY_LIMIT = 200;
    /** An id {@link #nextId} produced; group 1 is the number. */
    private static final Pattern HISTORY_ID = Pattern.compile("^[a-z]+-([0-9]{1,9})$");
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");
    /** How far apart two readings of one process's OS start time may be. */
    private static final Duration START_TOLERANCE = Duration.ofSeconds(1);
    static final String OUTLIVED_NOTE = "exit status unknown: it outlived the run that launched it";
    private static final ObjectMapper HISTORY_JSON = JsonUtils.standardMapper();

    private final String sessionId;
    private final Path outputDir;
    private final Map<String, ProcessEntry> processes = new ConcurrentHashMap<>();
    private final Map<String, ProcessMonitor> monitors = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ExecutorService ioExecutor;
    private volatile ExitCallback exitCallback;
    private final Thread shutdownHook;
    private volatile boolean historyEnabled;
    /** Set once {@link #close()} has drained; a closed manager writes no more virtual output. */
    private volatile boolean closed;
    /**
     * Set when {@link #close()} or the shutdown hook begins, under {@link #launchLock}. A launch
     * holds that lock until its process is registered, so shutdown either kills it or refuses it.
     */
    private boolean stopping;
    private final Object launchLock = new Object();
    private final Object historyLock = new Object();
    /** The history last read or written, so an unchanged list is not rewritten. */
    private String lastHistory;

    // General state-change listeners (fired on launch, output, exit, kill)
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Output listeners are separate from state listeners so callers can redraw the
    // currently viewed process without treating every output line as a lifecycle change.
    private final List<OutputCallback> outputListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<ExitCallback> exitListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<MonitorCallback> monitorListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Default retention for completed process entries (1 hour).
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(1);

    /**
     * Upper bound on how long {@link #close()} waits for an in-flight natural-exit
     * publication (a capture thread already past its read loop, finishing up in
     * {@code captureOutputAndWait}) to drain before forcing the I/O executor down.
     */
    private static final long CLOSE_DRAIN_MILLIS = 1000L;

    public BackgroundProcessManager(String sessionId) {
        this(sessionId, null);
    }

    /**
     * Create a manager with an optional working directory for log resolution.
     */
    public BackgroundProcessManager(String sessionId, Path workingDirectory) {
        this.sessionId = sessionId;
        this.outputDir = locateOutputRoot(workingDirectory)
                .resolve("process-output")
                .resolve(sessionId);
        // A session can outlive its manager: the web chat starts one per run and a
        // resumed session starts a fresh one. Numbering continues after the ids the
        // session's logs already use, so a launch never truncates an earlier log.
        this.counter.set(highestUsedIdNumber(outputDir));
        this.ioExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bg-proc-io");
            t.setDaemon(true);
            return t;
        });

        // Register shutdown hook to kill all running processes
        this.shutdownHook = new Thread(() -> {
            stopLaunching();
            killAllRunning();
            ioExecutor.shutdownNow();
        }, "bg-proc-manager-shutdown-" + sessionId);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    /**
     * Resolve where process-output logs should be rooted.
     * Uses the nearest ancestor's {@code .kompile} directory when available,
     * with fallback to {@code ~/.kompile}.
     */
    public static Path locateOutputRoot(Path workingDirectory) {
        Path current = workingDirectory != null
                ? workingDirectory.toAbsolutePath().normalize()
                : Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        while (current != null) {
            Path candidate = current.resolve(".kompile");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return KompileHome.homeDirectory().toPath();
    }

    /**
     * Highest id number among the process logs in {@code dir}; 0 when there are none
     * or the directory cannot be listed.
     */
    static int highestUsedIdNumber(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int highest = 0;
        try (DirectoryStream<Path> logs = Files.newDirectoryStream(dir, "*.log")) {
            for (Path log : logs) {
                Matcher matcher = ID_LOG_FILE.matcher(log.getFileName().toString());
                if (matcher.matches()) {
                    highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // Unlistable: number from 1, as a fresh session does.
        }
        return highest;
    }

    /**
     * Whether {@code sessionId} can name a session's log directory. The constructor
     * resolves it as a path segment, so an id that arrives from outside this process
     * must pass this check first.
     */
    public static boolean isSafeSessionId(String sessionId) {
        return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches()
                && !sessionId.equals(".") && !sessionId.equals("..");
    }

    /**
     * Restore the session's process history (see {@link #restoreSessionHistory}) and keep
     * it current from now on: every change rewrites it and {@link #close} records the
     * final states.
     *
     * @return number of entries restored
     */
    public int enableSessionHistory() {
        int restored = restoreSessionHistory();
        historyEnabled = true;
        return restored;
    }

    /**
     * Load the processes earlier runs of this session recorded, writing nothing. They
     * list and tail like this run's own. A recorded RUNNING process stays RUNNING, and
     * killable, only while the same OS process (PID and start time) is alive; otherwise
     * it reads as KILLED with an unknown exit status.
     *
     * @return number of entries restored
     */
    public int restoreSessionHistory() {
        String text;
        JsonNode records;
        try {
            Path file = outputDir.resolve(HISTORY_FILE);
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            text = Files.readString(file);
            records = HISTORY_JSON.readTree(text).path("processes");
        } catch (IOException | RuntimeException e) {
            // An unreadable history leaves this run with its own processes only.
            return 0;
        }
        synchronized (historyLock) {
            lastHistory = text;
        }
        int restored = 0;
        int highest = 0;
        for (JsonNode record : records) {
            ProcessEntry entry = fromHistoryRecord(record);
            if (entry != null && processes.putIfAbsent(entry.id, entry) == null) {
                Matcher matcher = HISTORY_ID.matcher(entry.id);
                if (matcher.matches()) {
                    highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
                }
                restored++;
            }
        }
        counter.accumulateAndGet(highest, Math::max);
        return restored;
    }

    /** One recorded process as a restored entry; null when the record is unusable. */
    private ProcessEntry fromHistoryRecord(JsonNode record) {
        String id = record.path("id").asText("");
        Instant start = historyInstant(record.path("startTime"));
        ProcessState state = historyState(record.path("state").asText(""));
        if (!HISTORY_ID.matcher(id).matches() || start == null || state == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        record.path("metadata").fields().forEachRemaining(field -> {
            if (field.getValue().isTextual()) {
                metadata.put(field.getKey(), field.getValue().asText());
            }
        });
        long pid = record.path("pid").asLong(-1L);
        Path log = outputDir.resolve(id + ".log");
        JsonNode description = record.path("description");
        ProcessEntry entry = new ProcessEntry(id, record.path("command").asText(""), pid, start, log,
                description.isTextual() ? description.asText() : null, null, ProcessKind.COMMAND, metadata);
        entry.fromHistory = true;
        entry.osStart = historyInstant(record.path("osStart"));
        if (state != ProcessState.RUNNING) {
            Instant end = historyInstant(record.path("endTime"));
            entry.state = state;
            entry.exitCode = record.path("exitCode").isInt() ? record.path("exitCode").intValue() : null;
            entry.endTime = end != null ? end : start;
        } else if (recordedProcess(pid, entry.osStart) == null) {
            markOutlived(entry);
        }
        return entry;
    }

    private static Instant historyInstant(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ProcessState historyState(String name) {
        try {
            return ProcessState.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The live OS process a record names. The start time must match too, so a PID the
     * system has since handed to another process never does.
     */
    private static ProcessHandle recordedProcess(long pid, Instant osStart) {
        if (pid <= 0 || osStart == null) {
            return null;
        }
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .filter(handle -> handle.info().startInstant()
                        .map(start -> Duration.between(start, osStart).abs().compareTo(START_TOLERANCE) <= 0)
                        .orElse(false))
                .orElse(null);
    }

    /**
     * Close out a recorded RUNNING process that is gone. The run that launched it ended
     * first, so nothing recorded how it ended; its last output bounds when.
     */
    private static void markOutlived(ProcessEntry entry) {
        Instant end = entry.startTime;
        try {
            Instant modified = Files.getLastModifiedTime(entry.outputFile).toInstant();
            if (modified.isAfter(end)) {
                end = modified;
            }
        } catch (IOException | RuntimeException e) {
            // No log: the start time is the only bound.
        }
        Map<String, String> metadata = new LinkedHashMap<>(entry.metadata);
        metadata.put("note", OUTLIVED_NOTE);
        entry.metadata = Map.copyOf(metadata);
        entry.state = ProcessState.KILLED;
        entry.exitCode = null;
        entry.endTime = end;
    }

    /**
     * Rewrite the session's process history if it changed: this run's commands and the
     * entries restored from earlier runs, the newest {@value #HISTORY_LIMIT}. It is written
     * beside the file and moved over it, so a reader never sees a partial list.
     */
    private void persistHistory() {
        if (!historyEnabled) {
            return;
        }
        synchronized (historyLock) {
            List<ProcessEntry> recorded = new ArrayList<>();
            for (ProcessEntry entry : processes.values()) {
                if (entry.fromHistory || (entry.process != null && entry.kind == ProcessKind.COMMAND)) {
                    recorded.add(entry);
                }
            }
            recorded.sort(Comparator.comparing(ProcessEntry::getStartTime).thenComparing(ProcessEntry::getId));
            if (recorded.size() > HISTORY_LIMIT) {
                recorded = recorded.subList(recorded.size() - HISTORY_LIMIT, recorded.size());
            }
            Path file = outputDir.resolve(HISTORY_FILE);
            if (recorded.isEmpty() && lastHistory == null && !Files.exists(file)) {
                return;
            }
            ObjectNode root = HISTORY_JSON.createObjectNode();
            root.put("version", 1);
            ArrayNode list = root.putArray("processes");
            for (ProcessEntry entry : recorded) {
                ObjectNode record = list.addObject();
                record.put("id", entry.id);
                record.put("command", entry.command);
                String description = entry.description;
                if (description != null) record.put("description", description);
                record.put("pid", entry.pid);
                record.put("state", entry.state.name());
                record.put("startTime", entry.startTime.toString());
                Instant end = entry.endTime;
                if (end != null) record.put("endTime", end.toString());
                Integer exit = entry.exitCode;
                if (exit != null) record.put("exitCode", exit.intValue());
                Instant osStart = entry.osStart;
                if (osStart != null) record.put("osStart", osStart.toString());
                if (!entry.metadata.isEmpty()) {
                    ObjectNode metadata = record.putObject("metadata");
                    new TreeMap<>(entry.metadata).forEach(metadata::put);
                }
            }
            try {
                String json = HISTORY_JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
                if (json.equals(lastHistory)) {
                    return;
                }
                Files.createDirectories(outputDir);
                Path temp = Files.createTempFile(outputDir, "." + HISTORY_FILE + ".", ".tmp");
                try {
                    Files.writeString(temp, json);
                    try {
                        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                lastHistory = json;
            } catch (IOException e) {
                // The history serves later runs; this run's processes are unaffected.
            }
        }
    }

    /**
     * Set a callback invoked when any tracked process exits.
     */
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    public void addExitListener(ExitCallback listener) {
        if (listener != null) exitListeners.addIfAbsent(listener);
    }

    public void removeExitListener(ExitCallback listener) {
        exitListeners.remove(listener);
    }

    public void addMonitorListener(MonitorCallback listener) {
        if (listener != null) monitorListeners.addIfAbsent(listener);
    }

    public void removeMonitorListener(MonitorCallback listener) {
        monitorListeners.remove(listener);
    }

    /**
     * Create or replace a one-shot completion monitor for a running local command.
     * The entry lock closes the race between registration and terminal-state publication.
     */
    public ProcessMonitor monitor(String processId, String message) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null) return null;
        ProcessMonitor monitor;
        synchronized (entry) {
            if (!entry.isRunning() || entry.isVirtual()
                    || entry.getKind() != ProcessKind.COMMAND) {
                return null;
            }
            monitor = new ProcessMonitor(entry.getId(), message, Instant.now());
            monitors.put(entry.getId(), monitor);
        }
        fireChange();
        return monitor;
    }

    public boolean removeMonitor(String processId) {
        boolean removed = processId != null && monitors.remove(processId) != null;
        if (removed) fireChange();
        return removed;
    }

    public ProcessMonitor getMonitor(String processId) {
        return processId == null ? null : monitors.get(processId);
    }

    public List<ProcessMonitor> listMonitors() {
        List<ProcessMonitor> result = new ArrayList<>(monitors.values());
        result.sort(Comparator.comparing(ProcessMonitor::createdAt));
        return result;
    }

    /**
     * Register a listener invoked on any process state change (launch, exit, kill).
     * Useful for status bar redraws.
     */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /** Register a listener for live output from every real process. */
    public void addOutputListener(OutputCallback listener) {
        if (listener != null) {
            outputListeners.add(listener);
        }
    }

    public void removeOutputListener(OutputCallback listener) {
        outputListeners.remove(listener);
    }

    /** Update a virtual watcher's visible state without replacing its process entry. */
    public boolean updateVirtual(String processId, String description, Map<String, String> metadata) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null || !entry.isVirtual() || !entry.isRunning()) {
            return false;
        }
        if (description != null && !description.isBlank()) {
            entry.description = description;
        }
        if (metadata != null) {
            entry.metadata = Map.copyOf(metadata);
        }
        fireChange();
        return true;
    }

    /**
     * Append one line to a running virtual entry's durable log and notify output
     * listeners, exactly as captured subprocess output is recorded. Virtual entries
     * have no OS stream of their own; this is how their owner gives them output.
     *
     * @return false when the manager is closed, the entry is not a running virtual
     *         entry, or the log cannot be written
     */
    public boolean appendVirtualOutput(String processId, String line) {
        ProcessEntry entry = processId != null ? processes.get(processId) : null;
        if (closed || entry == null || !entry.isVirtual() || !entry.isRunning() || line == null) {
            return false;
        }
        Path file = entry.outputFile;
        if (file == null) {
            return false;
        }
        synchronized (entry) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                return false;
            }
        }
        fireOutput(entry, line);
        return true;
    }

    /**
     * Create or refresh a mirror of a process owned by ANOTHER session (published
     * through coordination state, e.g. the MCP process tool's own JVM). Repeated
     * polls with the same {@code localId} update the entry in place. The output
     * file is the owner's durable log: this manager only reads it for tails and
     * never deletes it. A locally killed mirror stays KILLED until the owner
     * publishes a terminal state, and terminal owner state always wins so a stale
     * RUNNING snapshot cannot resurrect a finished row.
     *
     * @return the mirrored entry
     */
    public ProcessEntry upsertShared(String localId, String command, String description,
                                     long pid, Instant startTime, ProcessState state,
                                     Integer exitCode, Instant endTime, Path outputFile,
                                     Map<String, String> metadata) {
        ProcessState resolvedState = state != null ? state : ProcessState.RUNNING;
        Map<String, String> resolvedMetadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        ProcessEntry entry = processes.get(localId);
        boolean changed;
        if (entry == null || entry.getKind() != ProcessKind.SHARED) {
            entry = new ProcessEntry(localId,
                    command != null ? command : "shared process",
                    pid,
                    startTime != null ? startTime : Instant.now(),
                    outputFile,
                    description != null && !description.isBlank() ? description : "shared process",
                    null, ProcessKind.SHARED, resolvedMetadata);
            if (resolvedState != ProcessState.RUNNING) {
                entry.state = resolvedState;
                entry.exitCode = exitCode;
                entry.endTime = endTime != null ? endTime : Instant.now();
            }
            processes.put(localId, entry);
            changed = true;
        } else {
            changed = false;
            if (description != null && !description.isBlank()
                    && !description.equals(entry.description)) {
                entry.description = description;
                changed = true;
            }
            if (!resolvedMetadata.equals(entry.metadata)) {
                entry.metadata = resolvedMetadata;
                changed = true;
            }
            if (outputFile != null && !outputFile.equals(entry.outputFile)) {
                entry.outputFile = outputFile;
                changed = true;
            }
            if (resolvedState != ProcessState.RUNNING) {
                Instant newEnd = endTime != null ? endTime
                        : (entry.endTime != null ? entry.endTime : Instant.now());
                if (entry.state != resolvedState
                        || !Objects.equals(entry.exitCode, exitCode)
                        || !newEnd.equals(entry.endTime)) {
                    entry.state = resolvedState;
                    entry.exitCode = exitCode;
                    entry.endTime = newEnd;
                    changed = true;
                }
            }
            // RUNNING snapshots keep the current state: they must not resurrect a
            // locally killed or already terminal mirror.
        }
        long size = -1L;
        if (entry.outputFile != null) {
            try {
                size = Files.size(entry.outputFile);
            } catch (IOException ignored) {
                size = -1L;
            }
        }
        if (size != entry.sharedOutputSize) {
            entry.sharedOutputSize = size;
            changed = true;
        }
        if (changed) fireChange();
        return entry;
    }

    /**
     * Remove shared mirrors whose coordination entries are gone. Output files are
     * never deleted — they belong to the owning session.
     */
    public void pruneShared(Set<String> keepLocalIds) {
        boolean changed = false;
        for (ProcessEntry entry : processes.values()) {
            if (entry.getKind() == ProcessKind.SHARED
                    && (keepLocalIds == null || !keepLocalIds.contains(entry.getId()))) {
                if (processes.remove(entry.getId()) != null) {
                    changed = true;
                }
            }
        }
        if (changed) fireChange();
    }

    private void fireOutput(ProcessEntry entry, String line) {
        for (OutputCallback listener : outputListeners) {
            try {
                listener.onProcessOutput(entry, line);
            } catch (RuntimeException ignored) {
                // A redraw listener must not interrupt output capture.
            }
        }
    }

    private void fireChange() {
        for (Runnable l : changeListeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                // Swallow — a buggy listener must not break the REPL.
            }
        }
        persistHistory();
    }

    /**
     * Launch a background process from a command string.
     *
     * @param command     shell command to execute
     * @param description human-readable description of the process
     * @param workDir     working directory for the process
     * @return the new ProcessEntry
     * @throws IOException if the manager is closed, the process cannot be started or the output
     *                     directory cannot be created
     */
    public ProcessEntry launch(String command, String description, Path workDir) throws IOException {
        return launchMonitored(command, description, workDir, "");
    }

    /** Launch a command with a one-shot completion monitor installed before output capture starts. */
    public ProcessEntry launchMonitored(String command, String description, Path workDir,
                                        String monitorMessage) throws IOException {
        return launch(new String[]{"bash", "-c", command}, command, description, workDir,
                true, monitorMessage);
    }

    /**
     * Launch a background process from an argument array.
     *
     * @param args        command and arguments
     * @param description human-readable description of the process
     * @param workDir     working directory for the process
     * @return the new ProcessEntry
     * @throws IOException if the manager is closed, the process cannot be started or the output
     *                     directory cannot be created
     */
    public ProcessEntry launch(String[] args, String description, Path workDir) throws IOException {
        String command = String.join(" ", args);
        return launch(args, command, description, workDir, true, "");
    }

    private ProcessEntry launch(String[] args, String command, String description, Path workDir,
                                boolean monitored, String monitorMessage) throws IOException {
        ProcessEntry entry;
        synchronized (launchLock) {
            // A process started past close() would run on with no one left to kill it.
            if (stopping) {
                throw new IOException("Background process manager for session " + sessionId
                        + " is closed");
            }
            // Ensure output directory exists
            Files.createDirectories(outputDir);

            String id = nextId(ProcessKind.COMMAND);
            Path outputFile = outputDir.resolve(id + ".log");

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(workDir != null ? workDir.toFile() : new File("."));
            pb.redirectErrorStream(true);

            // Inherit the same baseline environment used by managed agent subprocesses.
            Map<String, String> env = pb.environment();
            for (String key : List.of("PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                    "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "TERM", "COLORTERM",
                    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY")) {
                String val = System.getenv(key);
                if (val != null) env.put(key, val);
            }
            env.put("GEMINI_CLI_TRUST_WORKSPACE", "true");

            Process process = pb.start();
            entry = new ProcessEntry(
                    id, command, process.pid(), Instant.now(), outputFile, description, process,
                    ProcessKind.COMMAND, Map.of());
            entry.osStart = process.info().startInstant().orElse(null);
            processes.put(id, entry);
            if (monitored) {
                monitors.put(id, new ProcessMonitor(id, monitorMessage, Instant.now()));
            }

            // Start daemon thread to capture output and watch for exit
            ioExecutor.submit(() -> captureOutputAndWait(entry));
        }

        fireChange();
        return entry;
    }

    /** Refuses every later launch; one already past the check finishes registering first. */
    private void stopLaunching() {
        synchronized (launchLock) {
            stopping = true;
        }
    }

    /**
     * Register a non-owned process or logical watcher in the same process list
     * used by the chat status bar. The caller owns its lifecycle and should call
     * {@link #complete(String)} or {@link #fail(String, int)} when finished.
     */
    public ProcessEntry registerVirtual(ProcessKind kind, String command, String description,
                                        Map<String, String> metadata) {
        return registerVirtual(kind, command, description, -1L, metadata);
    }

    /**
     * Register a non-owned process or logical watcher with a known OS PID.
     */
    public ProcessEntry registerVirtual(ProcessKind kind, String command, String description,
                                        long pid, Map<String, String> metadata) {
        return registerVirtual(kind, command, description, pid, metadata, null);
    }

    /**
     * Register a virtual entry whose owner can stop the work it stands for. Killing the
     * entry marks it killed and then runs {@code stop} once; completing or failing it does not.
     */
    public ProcessEntry registerVirtual(ProcessKind kind, String command, String description,
                                        Map<String, String> metadata, Runnable stop) {
        return registerVirtual(kind, command, description, -1L, metadata, stop);
    }

    private ProcessEntry registerVirtual(ProcessKind kind, String command, String description,
                                         long pid, Map<String, String> metadata, Runnable stop) {
        ProcessKind resolvedKind = kind != null ? kind : ProcessKind.COMMAND;
        String id = nextId(resolvedKind);
        Path outputFile = outputDir.resolve(id + ".log");
        ProcessEntry entry = new ProcessEntry(
                id,
                command != null ? command : resolvedKind.label(),
                pid,
                Instant.now(),
                outputFile,
                description != null ? description : resolvedKind.label(),
                null,
                resolvedKind,
                metadata);
        entry.stopHandler = stop;
        processes.put(id, entry);
        fireChange();
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
                // Notify after the durable log is updated so an activity view can
                // immediately re-read the complete line without racing the writer.
                fireOutput(entry, line);
            }

            // Process has exited; get exit code
            int exitCode = entry.process.waitFor();
            synchronized (entry) {
                entry.endTime = Instant.now();
                if (entry.killRequested.get()) {
                    entry.exitCode = -1;
                    entry.state = ProcessState.KILLED;
                } else {
                    entry.exitCode = exitCode;
                    entry.state = exitCode == 0
                            ? ProcessState.COMPLETED : ProcessState.FAILED;
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (entry) {
                entry.endTime = Instant.now();
                entry.exitCode = -1;
                entry.state = ProcessState.KILLED;
            }
        } catch (IOException e) {
            synchronized (entry) {
                entry.endTime = Instant.now();
                entry.exitCode = -1;
                entry.state = entry.killRequested.get()
                        ? ProcessState.KILLED : ProcessState.FAILED;
            }
        }

        fireExit(entry);
        fireChange();
    }

    private void fireExit(ProcessEntry entry) {
        if (entry == null || !entry.exitNotified.compareAndSet(false, true)) return;
        ProcessMonitor monitor = monitors.remove(entry.getId());
        ExitCallback cb = exitCallback;
        if (cb != null) {
            try {
                cb.onProcessExit(entry);
            } catch (Exception ignored) {
                // Don't let callback errors propagate
            }
        }
        for (ExitCallback listener : exitListeners) {
            try {
                listener.onProcessExit(entry);
            } catch (RuntimeException ignored) {
                // Completion observation must never break process cleanup.
            }
        }
        if (monitor != null) {
            for (MonitorCallback listener : monitorListeners) {
                try {
                    listener.onMonitoredProcessExit(entry, monitor);
                } catch (RuntimeException ignored) {
                    // Agent wake-up failures must never break process cleanup.
                }
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
        if (entry.getKind() == ProcessKind.SHARED) {
            // Another session owns this OS process; a local refusal keeps the
            // mirror following the owner's true state instead of faking one.
            return false;
        }
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
                if (entry.getKind() == ProcessKind.SHARED) {
                    // A PID match alone does not confer ownership.
                    return false;
                }
                return killProcess(entry);
            }
        }
        return false;
    }

    private boolean killProcess(ProcessEntry entry) {
        if (entry.fromHistory) {
            return killRecorded(entry);
        }
        synchronized (entry) {
            if (!entry.isRunning()) {
                return false;
            }
            if (entry.process == null) {
                entry.killRequested.set(true);
                entry.endTime = Instant.now();
                entry.state = ProcessState.KILLED;
                entry.exitCode = -1;
            } else {
                if (!entry.process.isAlive()) {
                    // The capture owner still has to drain output and record the
                    // real exit code. Its synchronized publication will make this
                    // terminal before any later kill can claim the entry.
                    return false;
                }
                // Publish intent atomically with the RUNNING-state check. The waiter
                // cannot publish a natural terminal event between these operations.
                entry.killRequested.set(true);
            }
        }

        if (entry.process == null) {
            if (entry.pid > 0) {
                ProcessHandle.of(entry.pid).ifPresent(handle -> {
                    handle.destroy();
                    try {
                        if (handle.isAlive()) {
                            Thread.sleep(500);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
            }
            Runnable stop = entry.stopHandler;
            if (stop != null) {
                try {
                    stop.run();
                } catch (RuntimeException ignored) {
                    // The entry is already killed; its owner reports its own stop failures.
                }
            }
            fireExit(entry);
            fireChange();
            return true;
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

        synchronized (entry) {
            entry.endTime = Instant.now();
            entry.state = ProcessState.KILLED;
            entry.exitCode = -1;
        }

        fireChange();
        return true;
    }

    /**
     * Stop a process an earlier run of the session launched. Only the recorded OS
     * process is signalled, through a handle that carries its start time, so a reused
     * PID is never touched. One that is already gone is closed out and false returned.
     */
    private boolean killRecorded(ProcessEntry entry) {
        ProcessHandle handle;
        synchronized (entry) {
            if (!entry.isRunning()) {
                return false;
            }
            handle = recordedProcess(entry.pid, entry.osStart);
            if (handle == null) {
                markOutlived(entry);
            } else {
                entry.killRequested.set(true);
                entry.endTime = Instant.now();
                entry.state = ProcessState.KILLED;
                entry.exitCode = -1;
            }
        }
        if (handle != null) {
            handle.destroy();
            try {
                if (handle.isAlive()) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
        fireExit(entry);
        fireChange();
        return handle != null;
    }

    /**
     * Mark a tracked virtual/owned process completed.
     */
    public boolean complete(String processId) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null) return false;
        synchronized (entry) {
            if (!entry.isRunning()) return false;
            entry.endTime = Instant.now();
            entry.exitCode = 0;
            entry.state = ProcessState.COMPLETED;
        }
        fireChange();
        return true;
    }

    /**
     * Mark a tracked virtual/owned process failed.
     */
    public boolean fail(String processId, int exitCode) {
        ProcessEntry entry = processes.get(processId);
        if (entry == null) return false;
        synchronized (entry) {
            if (!entry.isRunning()) return false;
            entry.endTime = Instant.now();
            entry.exitCode = exitCode;
            entry.state = ProcessState.FAILED;
        }
        fireChange();
        return true;
    }

    /**
     * Mark a tracked virtual/owned process failed with a generic exit code.
     */
    public boolean fail(String processId) {
        return fail(processId, -1);
    }

    private String nextId(ProcessKind kind) {
        String prefix = switch (kind != null ? kind : ProcessKind.COMMAND) {
            case JUDGE -> "judge";
            case ENFORCER -> "enforcer";
            case SHARED -> "shared";
            case MCP -> "mcp";
            case COMMAND -> "proc";
        };
        return prefix + "-" + String.format("%03d", counter.incrementAndGet());
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
        return readOutputFile(entry.outputFile, tailLines);
    }

    /**
     * Read the last N lines of a captured output file. Safe for running processes:
     * captureOutputAndWait flushes every line, so callers can use this as a live tail.
     */
    public static String readOutputFile(Path file, int tailLines) {
        if (file == null || !Files.exists(file)) {
            return "(no output captured yet)";
        }
        try {
            TailResult tail = tailOutputFile(file, tailLines);
            if (tail.lines().isEmpty()) {
                return "(no output)";
            }
            StringBuilder sb = new StringBuilder();
            if (tail.omittedLines() > 0) {
                sb.append("... (").append(tail.omittedLines()).append(" earlier lines omitted)\n");
            }
            for (String line : tail.lines()) {
                sb.append(line).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "Error reading output: " + e.getMessage();
        }
    }

    /** Return the last N lines and omission count for a possibly still-growing output file. */
    public static TailResult tailOutputFile(Path file, int tailLines) throws IOException {
        if (file == null || tailLines <= 0 || !Files.exists(file)) {
            return new TailResult(List.of(), 0);
        }
        Deque<String> tail = new ArrayDeque<>();
        long total = 0;
        try (java.util.stream.Stream<String> lines = Files.lines(file)) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                total++;
                tail.addLast(iterator.next());
                while (tail.size() > tailLines) {
                    tail.removeFirst();
                }
            }
        }
        return new TailResult(new ArrayList<>(tail), Math.max(0, total - tail.size()));
    }

    public record TailResult(List<String> lines, long omittedLines) {}

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
            // Shared mirrors own nothing: their logs belong to the owning session
            // and their removal is driven by coordination eviction, not retention.
            if (entry.getKind() == ProcessKind.SHARED) {
                continue;
            }
            if (!entry.isRunning() && entry.endTime != null && entry.endTime.isBefore(cutoff)) {
                it.remove();
                monitors.remove(entry.getId());
                // Also delete the output file
                try {
                    Files.deleteIfExists(entry.outputFile);
                } catch (IOException ignored) {}
                removed++;
            }
        }

        if (removed > 0) {
            persistHistory();
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
     * Kill every process still running under this manager and publish its exit before
     * returning. {@code killProcess}'s real-process branch marks the entry KILLED but,
     * unlike the virtual-process branch, does not call {@link #fireExit}: normally the
     * {@code bg-proc-io} capture thread publishes it once the process's stdout pipe
     * reaches EOF. Callers of this method are about to interrupt that thread, so it
     * publishes here instead, on the calling thread, which is never interrupted.
     * {@code fireExit} is a once-only CAS, so this is a harmless no-op for the
     * virtual-process branch, which already published.
     *
     * <p>Monitors are cleared first so a kill from here never wakes an agent: {@code
     * close()} and the JVM shutdown hook are both terminal, unattended shutdowns, not a
     * user-directed kill.
     */
    private void killAllRunning() {
        monitors.clear();
        monitorListeners.clear();
        for (ProcessEntry entry : processes.values()) {
            if (entry.isRunning() && entry.process != null && entry.process.isAlive()) {
                if (killProcess(entry)) {
                    fireExit(entry);
                }
            }
        }
    }

    /**
     * Shuts down this manager: kills all running processes, publishes each of their
     * exits, shuts down the I/O executor, and removes the JVM shutdown hook to prevent
     * accumulation across multiple sessions. Should be called when the chat session ends.
     */
    @Override
    public void close() {
        stopLaunching();
        // Kill all running processes and publish their exits. Ones restored from an
        // earlier run are not this run's to stop: they stay recorded as running for a
        // later run to kill.
        killAllRunning();
        persistHistory();
        historyEnabled = false;

        // Bound-wait for any natural-exit publication already in flight (a capture
        // thread past its read loop, e.g. one whose process just exited on its own)
        // before forcing the executor down. shutdownNow() only interrupts; it does not
        // wait, and a killed process's descendant can keep the stdout pipe open past
        // its own exit, so the wait stays bounded rather than joining the thread.
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(CLOSE_DRAIN_MILLIS, TimeUnit.MILLISECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // The log directory can go with its session, so a late writer (a diagnostics
        // sink still registered) must not recreate it.
        closed = true;

        // Remove shutdown hook to prevent leak
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM is already shutting down — hook can't be removed, which is fine
        }
    }
}
