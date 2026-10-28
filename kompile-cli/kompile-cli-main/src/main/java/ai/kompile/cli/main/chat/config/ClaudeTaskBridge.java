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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shows the tasks Claude Code runs by itself (background agents, background shell
 * commands, workflows, monitors) as rows in the chat's process panel, beside
 * Kompile's own processes. Killing a row asks Claude Code to stop the task. A row
 * ends when Claude Code reports that the task ended, when the Claude Code process
 * that ran it exits, or when the chat closes its Claude Code session. Claude
 * Code's diagnostics go to one log row.
 *
 * <p>Claude Code also lists its running background tasks after every change, but
 * that list can arrive before a task's own report, so a task missing from it is
 * not treated as ended.</p>
 *
 * <p>No lock is held while the process manager is called, except the log row's
 * own: the manager redraws the panel on the calling thread, and a kill arrives
 * on the thread that draws it.</p>
 */
final class ClaudeTaskBridge {

    private final BackgroundProcessManager processes;
    private final Consumer<String> stopTask;
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private final Map<Object, Row> processRows = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object logLock = new Object();
    private volatile String logRow;

    /**
     * @param stopTask asks Claude Code to stop the task with the given id; called
     *                 when its row is killed, never on the killing thread
     */
    ClaudeTaskBridge(BackgroundProcessManager processes, Consumer<String> stopTask) {
        this.processes = processes;
        this.stopTask = stopTask;
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
            ClaudeCliStreamParser.Event event = child.event();
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
            if (event instanceof ClaudeCliStreamParser.ToolStart) row.telemetry.tool();
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
            processes.updateVirtual(row.id(), null, metadata(row),
                    !(event instanceof ClaudeCliStreamParser.Text || event instanceof ClaudeCliStreamParser.Thinking));
            return;
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
        row.registered(id);
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
            finishStream(row);
            processes.appendVirtualOutput(id, line);
        }
    }

    private void end(ClaudeCliStreamParser.TaskEnded ended) {
        Row row = rows.remove(ended.taskId());
        if (row == null) return; // Already ended, or killed in the panel.
        String line = "Claude Code reports the task " + ended.status()
                + (ended.summary().isBlank() ? "" : ": " + ended.summary());
        row.end(id -> {
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
        });
    }

    /** End the rows of {@code source}'s tasks, or every row when it is null. */
    private void endAll(Object source, String line) {
        for (Row row : rows.values()) {
            if ((source == null || row.source == source) && rows.remove(row.taskId, row)) {
                row.end(id -> {
                    row.transcript.finish();
                    finishStream(row);
                    processes.appendVirtualOutput(id, line);
                    processes.fail(id, -1);
                });
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

    /**
     * One task or session row. Whoever takes it out of its map ends it; the end waits
     * for the process entry when that is still being made.
     */
    private static final class Row {
        final String taskId;
        final Object source;
        final Map<String, String> metadata;
        final SubagentTranscript transcript = new SubagentTranscript();
        final ai.kompile.cli.main.chat.SubagentTelemetry telemetry = new ai.kompile.cli.main.chat.SubagentTelemetry();
        final ClaudeCliUsageLedger usage = new ClaudeCliUsageLedger(false);
        String requestId = "";
        volatile String lastLine = "";
        String streamKind = "";             // output-reader thread only
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
