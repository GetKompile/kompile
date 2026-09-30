/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClaudeTaskBridge} shows the tasks Claude Code reports as rows of the
 * chat's process panel. The tests feed it the parser's task events directly and
 * read the rows back from a real {@link BackgroundProcessManager}.
 */
class ClaudeTaskBridgeTest {

    /** Two Claude Code processes, the sources of the events. */
    private static final Object PROCESS = new Object();
    private static final Object OTHER_PROCESS = new Object();

    @TempDir
    Path tempDir;

    private final LinkedBlockingQueue<String> stopped = new LinkedBlockingQueue<>();
    private final List<Thread> stoppingThreads = new CopyOnWriteArrayList<>();
    private BackgroundProcessManager processes;
    private ClaudeTaskBridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        // The manager keeps its logs under the nearest .kompile directory.
        Files.createDirectories(tempDir.resolve(".kompile"));
        processes = new BackgroundProcessManager("claude-task-bridge-test", tempDir);
        bridge = new ClaudeTaskBridge(processes, task -> {
            stoppingThreads.add(Thread.currentThread());
            stopped.add(task);
        });
    }

    @AfterEach
    void tearDown() {
        processes.close();
    }

    @Test
    void aStartedTaskBecomesAKillableRowThatDescribesIt() throws Exception {
        bridge.onEvent(PROCESS, started("task-1"));

        ProcessEntry row = row("task-1");
        assertEquals(ProcessKind.COMMAND, row.getKind());
        assertTrue(row.isVirtual());
        assertTrue(row.isRunning());
        assertTrue(row.isKillable(), "killing the row stops the task");
        assertEquals("claude local_agent", row.getCommand());
        assertEquals("Claude: index the docs", row.getDescription());
        assertEquals(Map.of("source", "claude-code", "task_id", "task-1", "tool_use_id", "toolu_task-1",
                "task_type", "local_agent", "backgrounded", "true"), row.getMetadata());
        assertEquals(List.of("Claude Code started local_agent task task-1: index the docs"), output(row));
    }

    @Test
    void aTaskWithoutTypeOrDescriptionIsNamedByItsId() throws Exception {
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskStarted("task-2", "", "", "", false));

        ProcessEntry row = row("task-2");
        assertEquals("claude task", row.getCommand());
        assertEquals("Claude Code task task-2", row.getDescription());
        assertEquals(Map.of("source", "claude-code", "task_id", "task-2", "backgrounded", "false"),
                row.getMetadata());
        assertEquals(List.of("Claude Code started task task-2"), output(row));
    }

    @Test
    void aStartReportedTwiceMakesOneRow() {
        bridge.onEvent(PROCESS, started("task-1"));
        bridge.onEvent(PROCESS, started("task-1"));

        assertEquals(1, processes.listAll().size(), String.valueOf(processes.listAll()));
    }

    @Test
    void progressUpdatesTheRowAndLogsEachNewLine() throws Exception {
        bridge.onEvent(PROCESS, started("task-1"));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskProgress(
                "task-1", "indexing the docs", "Grep", "", 1_200, 3, 4_500));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskProgress(
                "task-1", "", "Grep", "", 1_500, 4, 5_000));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskProgress(
                "task-1", "", "Read", "read 2 files", 1_800, 5, 6_000));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskProgress(
                "unknown-task", "elsewhere", "Bash", "", 1, 1, 1));

        ProcessEntry row = row("task-1");
        assertEquals("Claude: indexing the docs", row.getDescription(),
                "a report without a description keeps the last one");
        Map<String, String> metadata = row.getMetadata();
        assertEquals("task-1", metadata.get("task_id"));
        assertEquals("Read", metadata.get("last_tool"));
        assertEquals("5", metadata.get("tool_uses"));
        assertEquals("1800", metadata.get("total_tokens"));
        assertEquals("6000", metadata.get("duration_ms"));
        assertEquals(List.of("Claude Code started local_agent task task-1: index the docs",
                "Grep", "read 2 files"), output(row), "an unchanged line is logged once");
        assertEquals(1, processes.listAll().size(), "a report for an unknown task makes no row");
    }

    @Test
    void anEndedTaskEndsItsRowWithItsStatus() throws Exception {
        bridge.onEvent(PROCESS, started("task-ok"));
        bridge.onEvent(PROCESS, started("task-bad"));
        bridge.onEvent(PROCESS, started("task-stopped"));
        bridge.onEvent(PROCESS, started("task-interrupted"));

        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-ok", "completed", "indexed 40 files"));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-bad", "failed", ""));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-stopped", "killed", "stopped by Claude"));
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-interrupted", "stopped", "ping localhost"));

        assertEnded(row("task-ok"), ProcessState.COMPLETED, 0,
                "Claude Code reports the task completed: indexed 40 files");
        assertEnded(row("task-bad"), ProcessState.FAILED, 1, "Claude Code reports the task failed");
        // Kompile did not kill it, so the row does not claim a kill.
        assertEnded(row("task-stopped"), ProcessState.FAILED, -1,
                "Claude Code reports the task killed: stopped by Claude");
        assertEnded(row("task-interrupted"), ProcessState.FAILED, -1,
                "Claude Code reports the task stopped: ping localhost");
        assertTrue(stopped.isEmpty(), "Claude Code stopped its own task: " + stopped);
    }

    @Test
    void killingARowAsksClaudeCodeToStopTheTaskOffTheKillingThread() throws Exception {
        bridge.onEvent(PROCESS, started("task-1"));
        ProcessEntry row = row("task-1");

        assertTrue(processes.kill(row.getId()));

        assertEquals(ProcessState.KILLED, row.getState(), "the row ends when it is killed");
        assertEquals("task-1", stopped.poll(5, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), stoppingThreads.get(0),
                "the panel's thread must not wait on Claude Code");

        // Claude Code's report of the stop arrives afterwards and changes nothing.
        bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-1", "stopped", "stopped"));
        assertEquals(ProcessState.KILLED, row.getState());
        assertEquals(-1, row.getExitCode());
        assertFalse(processes.kill(row.getId()), "a killed row is not killed again");
        assertNull(stopped.poll(200, TimeUnit.MILLISECONDS), "the task is stopped once");
    }

    @Test
    void anEndReportedWhileTheRowIsBeingMadeStillEndsIt() throws Exception {
        // The manager announces a new row before the bridge knows the row's id:
        // an end that arrives then must wait for the id, not be lost.
        AtomicBoolean ended = new AtomicBoolean();
        processes.addChangeListener(() -> {
            if (ended.compareAndSet(false, true)) {
                bridge.onEvent(PROCESS, new ClaudeCliStreamParser.TaskEnded("task-1", "completed", ""));
            }
        });

        bridge.onEvent(PROCESS, started("task-1"));

        assertTrue(ended.get());
        ProcessEntry row = row("task-1");
        assertEquals(ProcessState.COMPLETED, row.getState());
        assertEquals(List.of("Claude Code started local_agent task task-1: index the docs",
                "Claude Code reports the task completed"), output(row));
    }

    @Test
    void anExitedProcessEndsOnlyItsOwnTasks() throws Exception {
        bridge.onEvent(PROCESS, started("task-1"));
        bridge.onEvent(OTHER_PROCESS, started("task-2"));

        bridge.processExited(PROCESS, 137);

        assertEnded(row("task-1"), ProcessState.FAILED, -1,
                "Claude Code exited (exit 137) before this task finished");
        assertTrue(row("task-2").isRunning(), "another process's task keeps running");
        ProcessEntry log = logRow();
        assertTrue(log.isRunning());
        assertEquals(List.of("Claude Code exited (exit 137)"), output(log));
        assertTrue(stopped.isEmpty(), String.valueOf(stopped));
    }

    @Test
    void closingEndsEveryRowAndTheLog() throws Exception {
        bridge.onLog("warning: slow MCP server");
        bridge.onEvent(PROCESS, started("task-1"));
        bridge.onEvent(OTHER_PROCESS, started("task-2"));

        bridge.close();

        for (String task : List.of("task-1", "task-2")) {
            assertEnded(row(task), ProcessState.FAILED, -1,
                    "Claude Code was closed before this task finished");
        }
        assertEquals(ProcessState.COMPLETED, logRow().getState());

        // Nothing reported after the close makes a row.
        bridge.onEvent(PROCESS, started("task-3"));
        bridge.onLog("late line");
        assertEquals(3, processes.listAll().size(), String.valueOf(processes.listAll()));
        assertTrue(stopped.isEmpty(), String.valueOf(stopped));
    }

    @Test
    void diagnosticsShareOneLogRowThatIsReplacedOnceItEnds() throws Exception {
        bridge.onLog("\u001B[33mwarning\u001B[0m: slow MCP server");
        bridge.onLog("   ");
        bridge.onLog(null);
        bridge.onLog("second line");

        assertEquals(1, processes.listAll().size(), String.valueOf(processes.listAll()));
        ProcessEntry log = logRow();
        assertEquals(ProcessKind.MCP, log.getKind());
        assertEquals("claude", log.getCommand());
        assertEquals("Claude Code log", log.getDescription());
        assertEquals(List.of("warning: slow MCP server", "second line"), output(log));

        assertTrue(processes.kill(log.getId()));
        bridge.onLog("after the kill");

        List<ProcessEntry> logs = processes.listAll().stream()
                .filter(entry -> entry.getKind() == ProcessKind.MCP)
                .toList();
        assertEquals(2, logs.size(), String.valueOf(logs));
        ProcessEntry replacement = logs.stream().filter(ProcessEntry::isRunning).findFirst().orElseThrow();
        assertEquals(List.of("after the kill"), output(replacement));
    }

    private static ClaudeCliStreamParser.TaskStarted started(String taskId) {
        return new ClaudeCliStreamParser.TaskStarted(taskId, "toolu_" + taskId, "index the docs",
                "local_agent", true);
    }

    private ProcessEntry row(String taskId) {
        return processes.listAll().stream()
                .filter(entry -> taskId.equals(entry.getMetadata().get("task_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + taskId + ": " + processes.listAll()));
    }

    private ProcessEntry logRow() {
        return processes.listAll().stream()
                .filter(entry -> entry.getKind() == ProcessKind.MCP)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log row: " + processes.listAll()));
    }

    private static void assertEnded(ProcessEntry row, ProcessState state, int exitCode, String lastLine)
            throws IOException {
        assertEquals(state, row.getState(), row.getDescription());
        assertEquals(exitCode, row.getExitCode(), row.getDescription());
        List<String> output = output(row);
        assertEquals(lastLine, output.get(output.size() - 1), String.valueOf(output));
    }

    private static List<String> output(ProcessEntry row) throws IOException {
        Path file = row.getOutputFile();
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }
}
