/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.tools.ToolResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes MCP tool calls in the background, allowing long-running operations
 * to return immediately with a task ID that can be polled for status/results.
 *
 * <p>Works at the MCP dispatch level — any tool can be made async by passing
 * {@code _background: true} in the tool arguments. No per-tool changes needed.</p>
 *
 * <p>Completed results are retained for 30 minutes before cleanup.</p>
 */
public class AsyncToolExecutor {

    private static final int MAX_CONCURRENT = 8;
    private static final long RESULT_TTL_MS = 30 * 60 * 1000; // 30 minutes

    private final ExecutorService pool;
    private final ConcurrentHashMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    private final McpToolProgressLogger logger;

    public AsyncToolExecutor(McpToolProgressLogger logger) {
        this.logger = logger;
        this.pool = Executors.newFixedThreadPool(MAX_CONCURRENT, r -> {
            Thread t = new Thread(r, "mcp-async-tool-" + taskCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        // Periodic cleanup of stale completed tasks
        ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-async-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanup.scheduleAtFixedRate(this::cleanupStale, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Submit a tool call for background execution.
     *
     * @param toolName the tool being called
     * @param callable the actual tool execution
     * @return a task ID for polling
     */
    public String submit(String toolName, Callable<ToolResult> callable) {
        String taskId = "bg-" + toolName + "-" + System.currentTimeMillis();
        BackgroundTask task = new BackgroundTask(taskId, toolName);
        tasks.put(taskId, task);

        pool.submit(() -> {
            String callId = logger != null
                    ? logger.toolStart(toolName, Map.of("_background", "true"))
                    : null;
            long start = System.currentTimeMillis();
            try {
                ToolResult result = callable.call();
                long elapsed = System.currentTimeMillis() - start;
                task.complete(result, elapsed);
                if (logger != null && callId != null) {
                    logger.toolComplete(callId, toolName, elapsed,
                            McpToolProgressLogger.summarizeResult(result));
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                task.fail(e.getMessage(), elapsed);
                if (logger != null && callId != null) {
                    logger.toolError(callId, toolName, elapsed, e.getMessage());
                }
            }
        });

        return taskId;
    }

    /** Get the current status of a background task. */
    public TaskStatus getStatus(String taskId) {
        BackgroundTask task = tasks.get(taskId);
        if (task == null) return null;
        return task.toStatus();
    }

    /** Get all active (non-completed) tasks. */
    public List<TaskStatus> getActiveTasks() {
        List<TaskStatus> active = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            if (task.status == Status.RUNNING) {
                active.add(task.toStatus());
            }
        }
        return active;
    }

    /** Get all tasks (for full status report). */
    public List<TaskStatus> getAllTasks() {
        List<TaskStatus> all = new ArrayList<>();
        for (BackgroundTask task : tasks.values()) {
            all.add(task.toStatus());
        }
        all.sort(Comparator.comparing(t -> t.taskId));
        return all;
    }

    /** Shutdown the executor. */
    public void shutdown() {
        pool.shutdownNow();
    }

    private void cleanupStale() {
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(e -> {
            BackgroundTask task = e.getValue();
            return task.status != Status.RUNNING
                    && (now - task.completedAt) > RESULT_TTL_MS;
        });
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    enum Status { RUNNING, COMPLETED, FAILED }

    public record TaskStatus(
            String taskId,
            String toolName,
            String status,
            long elapsedMs,
            String resultSummary,
            ToolResult result
    ) {}

    private static class BackgroundTask {
        final String taskId;
        final String toolName;
        final long startedAt;
        volatile Status status = Status.RUNNING;
        volatile ToolResult result;
        volatile String error;
        volatile long completedAt;
        volatile long durationMs;

        BackgroundTask(String taskId, String toolName) {
            this.taskId = taskId;
            this.toolName = toolName;
            this.startedAt = System.currentTimeMillis();
        }

        void complete(ToolResult result, long durationMs) {
            this.result = result;
            this.durationMs = durationMs;
            this.completedAt = System.currentTimeMillis();
            this.status = Status.COMPLETED;
        }

        void fail(String error, long durationMs) {
            this.error = error;
            this.durationMs = durationMs;
            this.completedAt = System.currentTimeMillis();
            this.status = Status.FAILED;
        }

        TaskStatus toStatus() {
            long elapsed = status == Status.RUNNING
                    ? System.currentTimeMillis() - startedAt
                    : durationMs;
            String summary = switch (status) {
                case RUNNING -> "running for " + elapsed + "ms";
                case COMPLETED -> result != null
                        ? McpToolProgressLogger.summarizeResult(result)
                        : "completed";
                case FAILED -> "failed: " + error;
            };
            return new TaskStatus(taskId, toolName, status.name().toLowerCase(),
                    elapsed, summary, status == Status.COMPLETED ? result : null);
        }
    }
}
