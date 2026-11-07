/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.render.SubagentTranscript;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.utils.AnsiConstants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Shows the tasks Claude Code runs by itself (background agents, background shell
 * commands, workflows, monitors) as rows in the chat's process panel, beside
 * Kompile's own processes. Killing a row asks Claude Code to stop the task. A row
 * ends when Claude Code reports that the task ended, when the Claude Code process
 * that ran it exits, or when the chat closes its Claude Code session. Claude
 * Code's diagnostics go to one log row.
 *
 * <p>A shell command or monitor never reaches the stream: Claude Code writes its
 * output to its own task log, which is tailed into the row as it grows. An agent
 * task's log links to Claude Code's transcript of the child, which grows whether
 * or not the parent still forwards the child's messages on the stream (a
 * backgrounded agent's are not). Once that transcript is found it alone presents
 * the child, replayed from its start; until then the forwarded messages do.</p>
 *
 * <p>Claude Code also lists its running background tasks after every change, but
 * that list can arrive before a task's own report, so a task missing from it is
 * not treated as ended.</p>
 *
 * <p>No lock is held while the process manager is called, except the log row's
 * and a tailed task log's own: the manager redraws the panel on the calling
 * thread, and a kill arrives on the thread that draws it.</p>
 */
final class ClaudeTaskBridge {

    private static final long TAIL_INTERVAL_MS = 250;
    private static final long LOCATE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_TAIL_BYTES_PER_READ = 1 << 20;
    private static final int MAX_PARTIAL_LINE_BYTES = 64 << 10;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final BackgroundProcessManager processes;
    private final Consumer<String> stopTask;
    private final Function<String, Path> taskOutputs;
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private final Map<Object, Row> processRows = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object logLock = new Object();
    private volatile String logRow;
    private ScheduledExecutorService tailer; // guarded by this

    /**
     * @param stopTask asks Claude Code to stop the task with the given id; called
     *                 when its row is killed, never on the killing thread
     */
    ClaudeTaskBridge(BackgroundProcessManager processes, Consumer<String> stopTask) {
        this(processes, stopTask, taskId -> null);
    }

    /**
     * @param taskOutputs Claude Code's log of the task with the given id, or null
     *                    while it cannot be found
     */
    ClaudeTaskBridge(BackgroundProcessManager processes, Consumer<String> stopTask,
                     Function<String, Path> taskOutputs) {
        this.processes = processes;
        this.stopTask = stopTask;
        this.taskOutputs = taskOutputs;
    }

    /**
     * Finds where Claude Code logs a task:
     * {@code <tmp>/claude-<uid>/<project>/<session>/tasks/<task>.output}, {@code <tmp>}
     * being {@code CLAUDE_CODE_TMPDIR}, {@code TMPDIR} or the system temp folder.
     * The project folder is Claude Code's own encoding of its working directory, so
     * it is found through the session id rather than derived again here.
     */
    static Function<String, Path> claudeTaskOutputs(Supplier<String> sessionId) {
        return taskId -> findTaskOutput(claudeTempFolders(), sessionId.get(), taskId);
    }

    /** The task's log under one of Claude Code's temp folders, or null. */
    static Path findTaskOutput(List<Path> claudeFolders, String session, String taskId) {
        if (session == null || taskId == null
                || !SAFE_NAME.matcher(session).matches() || !SAFE_NAME.matcher(taskId).matches()) {
            return null;
        }
        for (Path user : claudeFolders) {
            try (DirectoryStream<Path> projects = Files.newDirectoryStream(user, Files::isDirectory)) {
                for (Path project : projects) {
                    Path file = project.resolve(session).resolve("tasks").resolve(taskId + ".output");
                    if (Files.isRegularFile(file)) return file;
                }
            } catch (IOException | RuntimeException unreadable) {
                // Another user's folder, or one removed meanwhile.
            }
        }
        return null;
    }

    private static List<Path> claudeTempFolders() {
        Set<Path> bases = new LinkedHashSet<>();
        List<Path> folders = new ArrayList<>();
        String claudeTmp = System.getenv("CLAUDE_CODE_TMPDIR");
        for (String base : new String[] {claudeTmp, System.getenv("TMPDIR"),
                System.getProperty("java.io.tmpdir"), "/tmp"}) {
            if (base == null || base.isBlank()) continue;
            Path path = Path.of(base).toAbsolutePath().normalize();
            if (!bases.add(path) || !Files.isDirectory(path)) continue;
            if (base.equals(claudeTmp)) folders.add(path);
            try (DirectoryStream<Path> users = Files.newDirectoryStream(path, "claude-*")) {
                for (Path user : users) {
                    if (Files.isDirectory(user)) folders.add(user);
                }
            } catch (IOException | RuntimeException unreadable) {
                // Nothing of Claude Code's here.
            }
        }
        return folders;
    }

    /** A killable session row, independent of which provider the chat selects. */
    void processStarted(Object source, long pid, String sessionId, Runnable stopProcess) {
        if (closed.get()) return;
        Row row = new Row("", source, Map.of("source", "claude-code",
                "pid", Long.toString(pid), "session_id", sessionId));
        if (processRows.putIfAbsent(source, row) != null) return;
        String id = processes.registerVirtual(ProcessKind.COMMAND, "claude",
                "Claude Code session", row.metadata,
                () -> CompletableFuture.runAsync(stopProcess)).getId();
        // The session's replies stream into the chat itself; say so instead of an empty log.
        processes.appendVirtualOutput(id, "Claude Code session " + sessionId + " (pid " + pid + ")."
                + " Its replies stream into the chat, its diagnostics into the Claude Code log row,"
                + " and each of its tasks into a row of its own.");
        row.registered(id);
        // Closing can race registration; Row also handles exit-before-registration.
        if (closed.get() && processRows.remove(source, row)) {
            row.end(entry -> processes.fail(entry, -1));
        }
    }

    /** Record a task event reported by the Claude Code process {@code source}. */
    void onEvent(Object source, ClaudeCliStreamParser.Event event) {
        if (event instanceof ClaudeCliStreamParser.TaskStarted started) {
            start(source, started);
        } else if (event instanceof ClaudeCliStreamParser.TaskProgress progress) {
            progress(progress);
        } else if (event instanceof ClaudeCliStreamParser.TaskEnded ended) {
            end(ended);
        } else if (event instanceof ClaudeCliStreamParser.SubagentEvent child) {
            transcript(source, child);
        }
    }

    /** Child content belongs to its task row, never the parent chat or MCP log. */
    private void transcript(Object source, ClaudeCliStreamParser.SubagentEvent child) {
        for (Row row : rows.values()) {
            if (row.source != source || !child.toolUseId().equals(row.metadata.get("tool_use_id"))) continue;
            String id = row.id();
            if (id == null) return;
            locked(row, () -> {
                // The followed transcript already holds every forwarded message.
                if (row.tail == null || !row.tail.following) childEvent(row, id, child.event());
            });
            return;
        }
    }

    /** Show one of the child's messages, from the stream or from its followed transcript. */
    private void childEvent(Row row, String id, ClaudeCliStreamParser.Event event) {
        if (event instanceof ClaudeCliStreamParser.SubagentModel model) {
            row.telemetry.put("model", model.model());
            publishMetadata(row);
            return;
        }
        if (event instanceof ClaudeCliStreamParser.RequestUsage usage) {
            if (!usage.requestId().equals(row.requestId)) {
                row.telemetry.beginRequest();
                row.requestId = usage.requestId();
            }
            var added = row.usage.request(usage);
            row.telemetry.usage(added.input(), added.output(), added.cacheRead(), added.cacheCreation());
            row.telemetry.put("context_tokens", Long.toString(row.usage.requestInputTokens(usage)));
            publishMetadata(row);
            return;
        }
        // A replayed transcript repeats the tools the stream already counted.
        if (event instanceof ClaudeCliStreamParser.ToolStart tool && row.countedTools.add(tool.callId())) {
            row.telemetry.tool();
        }
        if (event instanceof ClaudeCliStreamParser.ToolInputDelta delta) row.telemetry.stream(delta.delta());
        present(row.transcript, event);
        if (event instanceof ClaudeCliStreamParser.Text text) {
            stream(row, "Assistant", text.text());
        } else if (event instanceof ClaudeCliStreamParser.Thinking thinking) {
            stream(row, "Thinking", thinking.text());
        } else {
            finishStream(row);
            String detail = "";
            if (event instanceof ClaudeCliStreamParser.UserText user) {
                detail = "User:\n" + user.text();
            } else if (event instanceof ClaudeCliStreamParser.ToolStart tool) {
                detail = "Tool [" + tool.callId() + "]: " + tool.name();
            } else if (event instanceof ClaudeCliStreamParser.ToolInput input) {
                detail = "Input: " + input.input();
            } else if (event instanceof ClaudeCliStreamParser.ToolOutput output) {
                detail = "Output [" + output.callId() + "]:\n" + output.output();
            } else if (event instanceof ClaudeCliStreamParser.ToolComplete complete) {
                detail = "Result [" + complete.callId() + "]" + (complete.error() ? " (error)" : "")
                        + ":\n" + complete.output();
            } else if (event instanceof ClaudeCliStreamParser.Notice notice) {
                detail = notice.text();
            } else if (event instanceof ClaudeCliStreamParser.ApiError error) {
                detail = "Error: " + error.text();
            }
            if (!detail.isEmpty()) processes.appendVirtualOutput(id, detail);
        }
        processes.updateVirtual(id, null, metadata(row),
                !(event instanceof ClaudeCliStreamParser.Text || event instanceof ClaudeCliStreamParser.Thinking));
    }

    /** Run {@code action} under the row's tail lock, which guards its stream state against the tailer. */
    private static void locked(Row row, Runnable action) {
        OutputTail tail = row.tail;
        if (tail == null) {
            action.run();
        } else {
            synchronized (tail) {
                action.run();
            }
        }
    }

    /** Preserve roles/tool identity before formatting; never interpret the raw log. */
    private static void present(SubagentTranscript transcript, ClaudeCliStreamParser.Event event) {
        if (event instanceof ClaudeCliStreamParser.Text text) transcript.text("assistant", text.text());
        else if (event instanceof ClaudeCliStreamParser.Thinking thinking) transcript.text("thinking", thinking.text());
        else if (event instanceof ClaudeCliStreamParser.UserText user) transcript.message("user", user.text());
        else if (event instanceof ClaudeCliStreamParser.ToolStart tool) transcript.toolStart(tool.callId(), tool.name(), tool.input());
        else if (event instanceof ClaudeCliStreamParser.ToolInput input) transcript.toolInput(input.callId(), input.input());
        else if (event instanceof ClaudeCliStreamParser.ToolOutput output) transcript.toolOutput(output.callId(), output.output());
        else if (event instanceof ClaudeCliStreamParser.ToolComplete complete) transcript.toolComplete(complete.callId(), complete.output(), complete.error());
        else if (event instanceof ClaudeCliStreamParser.Notice notice) transcript.message("metadata", notice.text());
        else if (event instanceof ClaudeCliStreamParser.ApiError error) transcript.message("error", error.text());
    }

    private void stream(Row row, String kind, String text) {
        if (text.isEmpty()) return;
        row.telemetry.stream(text);
        if (!kind.equals(row.streamKind)) {
            finishStream(row);
            processes.appendVirtualOutput(row.id(), kind + ":");
            row.streamKind = kind;
        }
        processes.appendVirtualOutputChunk(row.id(), text);
        row.streamHasNewline = text.endsWith("\n");
    }

    private void finishStream(Row row) {
        if (row.streamKind.isEmpty()) return;
        if (!row.streamHasNewline) processes.appendVirtualOutputChunk(row.id(), "\n");
        row.streamKind = "";
    }

    /** Add a line of Claude Code's diagnostics to its log row. */
    void onLog(String line) {
        if (closed.get() || line == null || line.isBlank()) return;
        synchronized (logLock) {
            String id = logRow;
            BackgroundProcessManager.ProcessEntry entry = id == null ? null : processes.get(id);
            if (entry == null || !entry.isRunning()) {
                id = processes.registerVirtual(ProcessKind.MCP, "claude", "Claude Code log", Map.of()).getId();
                logRow = id;
            }
            processes.appendVirtualOutput(id, AnsiConstants.stripAnsi(line));
        }
        // A close that ran meanwhile could not see a row made here.
        if (closed.get()) completeLog();
    }

    /** The Claude Code process {@code source} exited, and its tasks with it. */
    void processExited(Object source, int exitCode) {
        endAll(source, "Claude Code exited (exit " + exitCode + ") before this task finished");
        Row process = processRows.remove(source);
        if (process != null) process.end(id -> {
            if (exitCode == 0) processes.complete(id);
            else processes.fail(id, exitCode);
        });
        onLog("Claude Code exited (exit " + exitCode + ")");
    }

    /** The chat closed its Claude Code session: end every row that is left. */
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (this) {
            if (tailer != null) tailer.shutdownNow();
        }
        endAll(null, "Claude Code was closed before this task finished");
        for (Row row : processRows.values()) {
            if (processRows.remove(row.source, row)) row.end(id -> processes.fail(id, -1));
        }
        completeLog();
    }

    private void start(Object source, ClaudeCliStreamParser.TaskStarted started) {
        if (closed.get()) return;
        Row row = new Row(started.taskId(), source, metadata(started));
        if (rows.putIfAbsent(row.taskId, row) != null) return;
        // A close that ran meanwhile could not see the row.
        if (closed.get()) {
            rows.remove(row.taskId, row);
            return;
        }
        String type = started.taskType();
        String description = started.description();
        String id = processes.registerVirtual(ProcessKind.COMMAND,
                type.isBlank() ? "claude task" : "claude " + type,
                description.isBlank() ? "Claude Code task " + row.taskId : "Claude: " + description,
                row.metadata, () -> killed(row)).getId();
        processes.appendVirtualOutput(id, "Claude Code started " + (type.isBlank() ? "" : type + " ")
                + "task " + row.taskId + (description.isBlank() ? "" : ": " + description));
        processes.setVirtualTranscript(id, row.transcript);
        // Set before the row is registered, so every forwarded message sees its tail lock.
        row.tail = new OutputTail(type.equals("agent") || type.endsWith("_agent")
                ? new ClaudeCliStreamParser() : null);
        row.registered(id);
        startTailing();
    }

    private synchronized void startTailing() {
        if (tailer != null || closed.get()) return;
        tailer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "claude-task-output");
            thread.setDaemon(true);
            return thread;
        });
        tailer.scheduleWithFixedDelay(() -> {
            for (Row row : rows.values()) {
                try {
                    readTail(row, false);
                } catch (RuntimeException e) {
                    // One unreadable log must not stop the others being tailed.
                }
            }
        }, 0, TAIL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Append what Claude Code added to the task's log since the last read: whole
     * lines only, unless {@code last}, which reads to the end before the row ends.
     */
    private void readTail(Row row, boolean last) {
        OutputTail tail = row.tail;
        String id = row.id();
        if (tail == null || id == null) return;
        synchronized (tail) {
            if (tail.file == null) {
                long now = System.nanoTime();
                if (!last && now - tail.lastLocate < LOCATE_INTERVAL_NANOS) return;
                tail.lastLocate = now;
                tail.file = taskOutputs.apply(row.taskId);
                if (tail.file == null) return;
            }
            try (FileChannel channel = FileChannel.open(tail.file, StandardOpenOption.READ)) {
                if (tail.sidechain != null && !tail.following) {
                    // The child's transcript holds its whole conversation, forwarded
                    // messages included, so it replaces what they showed.
                    tail.following = true;
                    row.transcript = new SubagentTranscript();
                    processes.setVirtualTranscript(id, row.transcript);
                }
                if (channel.size() < tail.offset) {
                    // Rewritten from the start.
                    tail.offset = 0;
                    tail.partial = new byte[0];
                }
                do {
                    long available = channel.size() - tail.offset;
                    if (available <= 0) break;
                    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, MAX_TAIL_BYTES_PER_READ));
                    int read = channel.read(buffer, tail.offset);
                    if (read <= 0) break;
                    tail.offset += read;
                    byte[] bytes = new byte[tail.partial.length + read];
                    System.arraycopy(tail.partial, 0, bytes, 0, tail.partial.length);
                    System.arraycopy(buffer.array(), 0, bytes, tail.partial.length, read);
                    // A newline byte never sits inside a multi-byte UTF-8 character.
                    int end = bytes.length;
                    while (end > 0 && bytes[end - 1] != '\n') end--;
                    // A line that never ends (a \r progress bar) is shown rather than held forever.
                    // A transcript line always ends, and a split one would decode as nothing.
                    if (end == 0 && tail.sidechain == null && bytes.length >= MAX_PARTIAL_LINE_BYTES) {
                        end = bytes.length;
                    }
                    tail.partial = Arrays.copyOfRange(bytes, end, bytes.length);
                    if (end > 0) show(row, id, tail, new String(bytes, 0, end, StandardCharsets.UTF_8));
                } while (last);
            } catch (IOException e) {
                // Removed with its session; whatever was read stays in the row.
                return;
            }
            if (last && tail.partial.length > 0) {
                String rest = new String(tail.partial, StandardCharsets.UTF_8);
                if (tail.sidechain != null) show(row, id, tail, rest);
                else processes.appendVirtualOutput(id, rest);
                tail.partial = new byte[0];
            }
        }
    }

    /** Whole lines read from a task's log: a shell's output verbatim, an agent's as its messages. */
    private void show(Row row, String id, OutputTail tail, String lines) {
        if (tail.sidechain == null) {
            processes.appendVirtualOutputChunk(id, lines);
            return;
        }
        String toolUseId = row.metadata.getOrDefault("tool_use_id", row.taskId);
        for (String line : lines.split("\n")) {
            for (ClaudeCliStreamParser.Event event : tail.sidechain.parseSidechain(line, toolUseId)) {
                if (event instanceof ClaudeCliStreamParser.SubagentEvent child) childEvent(row, id, child.event());
            }
        }
    }

    private void progress(ClaudeCliStreamParser.TaskProgress progress) {
        Row row = rows.get(progress.taskId());
        String id = row == null ? null : row.id();
        if (id == null) return;
        row.telemetry.put("last_tool", progress.lastToolName());
        if (progress.toolUses() > 0) row.telemetry.put("tool_uses", Long.toString(progress.toolUses()));
        if (progress.totalTokens() > 0) row.telemetry.put("total_tokens", Long.toString(progress.totalTokens()));
        if (progress.durationMs() > 0) row.telemetry.put("duration_ms", Long.toString(progress.durationMs()));
        Map<String, String> metadata = metadata(row);
        processes.updateVirtual(id,
                progress.description().isBlank() ? null : "Claude: " + progress.description(), metadata);
        String line = progress.summary().isBlank() ? progress.lastToolName() : progress.summary();
        if (!line.isBlank() && !line.equals(row.lastLine)) {
            row.lastLine = line;
            locked(row, () -> {
                finishStream(row);
                processes.appendVirtualOutput(id, line);
            });
        }
    }

    private void end(ClaudeCliStreamParser.TaskEnded ended) {
        Row row = rows.remove(ended.taskId());
        if (row == null) return; // Already ended, or killed in the panel.
        String line = "Claude Code reports the task " + ended.status()
                + (ended.summary().isBlank() ? "" : ": " + ended.summary());
        row.end(id -> locked(row, () -> {
            // What the log gained since the last read still belongs to the transcript.
            readTail(row, true);
            row.transcript.finish();
            finishStream(row);
            processes.appendVirtualOutput(id, line);
            switch (ended.status()) {
                case "completed" -> processes.complete(id);
                case "failed" -> processes.fail(id, 1);
                // Stopped by Claude Code itself. A kill is not replayed: that would
                // report the row as a Kompile process exit.
                default -> processes.fail(id, -1);
            }
        }));
    }

    /** End the rows of {@code source}'s tasks, or every row when it is null. */
    private void endAll(Object source, String line) {
        for (Row row : rows.values()) {
            if ((source == null || row.source == source) && rows.remove(row.taskId, row)) {
                row.end(id -> locked(row, () -> {
                    readTail(row, true);
                    row.transcript.finish();
                    finishStream(row);
                    processes.appendVirtualOutput(id, line);
                    processes.fail(id, -1);
                }));
            }
        }
    }

    /** The row was killed in the panel: the task is Claude Code's to stop. */
    private void killed(Row row) {
        if (rows.remove(row.taskId, row)) {
            row.transcript.finish();
            CompletableFuture.runAsync(() -> stopTask.accept(row.taskId));
        }
    }

    /**
     * A short pointer to the process-panel row for the tool call with this
     * {@code tool_use_id}, so the tool's own card can point at the row instead
     * of repeating Claude Code's task-launch text; null when no row is tracked
     * for it (the call was never backgrounded, its task_started has not been
     * handled yet, or its row already ended).
     */
    String pointerFor(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) return null;
        for (Row row : rows.values()) {
            if (toolUseId.equals(row.metadata.get("tool_use_id"))) {
                String id = row.id();
                return id == null ? null : "Running in the background as " + id + ".";
            }
        }
        return null;
    }

    private void completeLog() {
        String id = logRow;
        if (id != null) processes.complete(id);
    }

    private static Map<String, String> metadata(ClaudeCliStreamParser.TaskStarted started) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "claude-code");
        metadata.put("task_id", started.taskId());
        putIfSet(metadata, "tool_use_id", started.toolUseId());
        putIfSet(metadata, "task_type", started.taskType());
        metadata.put("backgrounded", Boolean.toString(started.backgrounded()));
        return Map.copyOf(metadata);
    }

    private Map<String, String> metadata(Row row) {
        Map<String, String> metadata = new LinkedHashMap<>(row.metadata);
        metadata.putAll(row.telemetry.snapshot());
        return metadata;
    }

    private void publishMetadata(Row row) {
        processes.updateVirtual(row.id(), null, metadata(row));
    }

    private static void putIfSet(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    /** How far a task's log has been read; guarded by itself, as is its row's stream state. */
    private static final class OutputTail {
        /** Decodes an agent's log, Claude Code's transcript of the child; null for a shell's output. */
        final ClaudeCliStreamParser sidechain;
        Path file;
        long offset;
        long lastLocate = System.nanoTime() - LOCATE_INTERVAL_NANOS;
        byte[] partial = new byte[0];
        /** An agent's log was opened: it now presents the child instead of forwarded messages. */
        boolean following;

        OutputTail(ClaudeCliStreamParser sidechain) {
            this.sidechain = sidechain;
        }
    }

    /**
     * One task or session row. Whoever takes it out of its map ends it; the end waits
     * for the process entry when that is still being made.
     */
    private static final class Row {
        final String taskId;
        final Object source;
        final Map<String, String> metadata;
        volatile SubagentTranscript transcript = new SubagentTranscript(); // replaced once an agent's log is followed
        final ai.kompile.cli.main.chat.SubagentTelemetry telemetry = new ai.kompile.cli.main.chat.SubagentTelemetry();
        final ClaudeCliUsageLedger usage = new ClaudeCliUsageLedger(false);
        final Set<String> countedTools = ConcurrentHashMap.newKeySet();
        String requestId = "";
        volatile String lastLine = "";
        volatile OutputTail tail;           // Claude Code's own log; null for sessions
        String streamKind = "";             // guarded by the tail's lock when there is a tail
        boolean streamHasNewline;
        private String id;                // guarded by this
        private Consumer<String> ending;  // guarded by this

        Row(String taskId, Object source, Map<String, String> metadata) {
            this.taskId = taskId;
            this.source = source;
            this.metadata = metadata;
        }

        synchronized String id() {
            return id;
        }

        /** The entry exists: run an end that arrived while it was being made. */
        void registered(String entryId) {
            Consumer<String> pending;
            synchronized (this) {
                id = entryId;
                pending = ending;
            }
            if (pending != null) pending.accept(entryId);
        }

        void end(Consumer<String> action) {
            String entryId;
            synchronized (this) {
                entryId = id;
                if (entryId == null) {
                    ending = action;
                    return;
                }
            }
            action.accept(entryId);
        }
    }
}
