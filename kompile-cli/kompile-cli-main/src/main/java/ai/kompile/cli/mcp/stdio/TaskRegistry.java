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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Durable, file-based registry for agent task records.
 *
 * <p>Each task is persisted as a JSON file under
 * {@code <workDir>/.kompile/task-registry/<taskId>.task.json}.
 * This survives MCP tool-call timeouts — a task ID is always recoverable
 * by scanning the registry directory, even if the original MCP response
 * was never delivered to the caller.</p>
 *
 * <p>The registry also reconciles with live OS processes: when asked for
 * active tasks, it checks whether each recorded PID is still alive and
 * updates RUNNING records whose process has disappeared.</p>
 */
public class TaskRegistry {

    private static final String TASK_FILE_SUFFIX = ".task.json";
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    /** Completed/failed tasks older than this are cleaned up by {@link #evictStale()}. */
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private final Path registryDir;
    private final ObjectMapper mapper;

    public TaskRegistry(Path workDir) {
        this.registryDir = workDir.resolve(".kompile").resolve("task-registry");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(registryDir);
        } catch (IOException e) {
            System.err.println("[TaskRegistry] Warning: Could not create registry dir: " + e.getMessage());
        }
    }

    /** Constructor for testing with a custom ObjectMapper. */
    TaskRegistry(Path workDir, ObjectMapper mapper) {
        this.registryDir = workDir.resolve(".kompile").resolve("task-registry");
        this.mapper = mapper;
        try {
            Files.createDirectories(registryDir);
        } catch (IOException e) {
            System.err.println("[TaskRegistry] Warning: Could not create registry dir: " + e.getMessage());
        }
    }

    // ── ID generation ────────────────────────────────────────────────────

    /** Generate a unique task ID. Format: {@code task-<type>-<timestamp>-<seq>}. */
    public static String generateId(String taskType) {
        return "task-" + taskType + "-" + System.currentTimeMillis() + "-" + (SEQ.incrementAndGet() % 10000);
    }

    // ── CRUD operations ──────────────────────────────────────────────────

    /**
     * Create and persist a new task record. The record is written atomically
     * (write to tmp, then rename) so partial writes never corrupt the registry.
     *
     * @return the persisted TaskRecord
     */
    public TaskRecord create(TaskRecord record) {
        persist(record);
        return record;
    }

    /**
     * Update an existing task record. Reads the current state from disk,
     * applies the mutation, and writes it back atomically.
     */
    public TaskRecord update(String taskId, java.util.function.Consumer<TaskRecord> mutator) {
        TaskRecord record = get(taskId);
        if (record == null) return null;
        mutator.accept(record);
        persist(record);
        return record;
    }

    /** Read a task record by ID. Returns null if not found. */
    public TaskRecord get(String taskId) {
        Path file = registryDir.resolve(taskId + TASK_FILE_SUFFIX);
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(file.toFile(), TaskRecord.class);
        } catch (IOException e) {
            System.err.println("[TaskRegistry] Warning: Could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** Delete a task record. */
    public boolean delete(String taskId) {
        try {
            return Files.deleteIfExists(registryDir.resolve(taskId + TASK_FILE_SUFFIX));
        } catch (IOException e) {
            return false;
        }
    }

    // ── Queries ──────────────────────────────────────────────────────────

    /** List all task records, sorted by creation time (newest first). */
    public List<TaskRecord> listAll() {
        return readAll().stream()
                .sorted(Comparator.comparing(TaskRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /** List active (RUNNING or DETACHED) tasks after reconciling with live processes. */
    public List<TaskRecord> listActive() {
        List<TaskRecord> all = readAll();
        List<TaskRecord> active = new ArrayList<>();
        for (TaskRecord r : all) {
            if (r.isActive()) {
                reconcileWithProcess(r);
                if (r.isActive()) {
                    active.add(r);
                }
            }
        }
        active.sort(Comparator.comparing(TaskRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return active;
    }

    /** List tasks by parent task ID. */
    public List<TaskRecord> listChildren(String parentTaskId) {
        return readAll().stream()
                .filter(r -> parentTaskId.equals(r.getParentTaskId()))
                .sorted(Comparator.comparing(TaskRecord::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /** List recent tasks (last N hours). */
    public List<TaskRecord> listRecent(int hours) {
        Instant cutoff = Instant.now().minus(Duration.ofHours(hours));
        return readAll().stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(cutoff))
                .sorted(Comparator.comparing(TaskRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    // ── Process reconciliation ───────────────────────────────────────────

    /**
     * Check if a task's recorded PID is still alive. If not, mark it as
     * COMPLETED or FAILED depending on context.
     */
    public void reconcileWithProcess(TaskRecord record) {
        if (!record.isActive() || record.getPid() <= 0) return;

        if (!isProcessAlive(record.getPid())) {
            // Process is gone — check if there's an output file to determine success
            if (record.getOutputPath() != null && Files.exists(Path.of(record.getOutputPath()))) {
                record.markCompleted(0, "Process ended (reconciled from registry)", record.getOutputPath());
            } else {
                record.markFailed(-1, "Process disappeared (pid " + record.getPid() + " no longer running)");
            }
            persist(record);
        } else {
            record.touch();
        }
    }

    /**
     * Reconcile all active tasks with their live processes.
     *
     * @return number of tasks whose status changed
     */
    public int reconcileAll() {
        int changed = 0;
        for (TaskRecord r : readAll()) {
            if (r.isActive() && r.getPid() > 0) {
                TaskRecord.Status before = r.getStatus();
                reconcileWithProcess(r);
                if (r.getStatus() != before) changed++;
            }
        }
        return changed;
    }

    // ── Task cancellation ────────────────────────────────────────────────

    /**
     * Cancel a running/detached task by killing its process.
     *
     * @return true if the task was cancelled
     */
    public boolean cancel(String taskId) {
        TaskRecord record = get(taskId);
        if (record == null) return false;
        if (!record.isActive()) return false;

        if (record.getPid() > 0) {
            try {
                ProcessHandle.of(record.getPid()).ifPresent(ProcessHandle::destroy);
            } catch (Exception e) {
                System.err.println("[TaskRegistry] Warning: Could not kill pid " + record.getPid() + ": " + e.getMessage());
            }
        }

        record.markCancelled();
        persist(record);
        return true;
    }

    /**
     * Cancel a parent task and all its children.
     *
     * @return number of tasks cancelled
     */
    public int cancelTree(String parentTaskId) {
        int count = 0;
        TaskRecord parent = get(parentTaskId);
        if (parent != null && parent.isActive()) {
            cancel(parentTaskId);
            count++;
        }
        for (TaskRecord child : listChildren(parentTaskId)) {
            if (child.isActive()) {
                cancel(child.getTaskId());
                count++;
            }
        }
        return count;
    }

    // ── Cleanup ──────────────────────────────────────────────────────────

    /**
     * Evict terminal task records older than 24 hours.
     *
     * @return number of records evicted
     */
    public int evictStale() {
        int evicted = 0;
        Instant cutoff = Instant.now().minus(COMPLETED_TTL);
        for (TaskRecord r : readAll()) {
            if (r.isTerminal() && r.getCompletedAt() != null && r.getCompletedAt().isBefore(cutoff)) {
                delete(r.getTaskId());
                evicted++;
            }
        }
        return evicted;
    }

    // ── Formatting ───────────────────────────────────────────────────────

    /** Format a task record as a human-readable status line. */
    public static String formatStatus(TaskRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(r.getTaskId()).append("** [").append(r.getStatus()).append("]");
        if (r.getAgentName() != null) sb.append(" agent=").append(r.getAgentName());
        if (r.getSubtaskName() != null) sb.append(" subtask=").append(r.getSubtaskName());
        if (r.getPid() > 0) sb.append(" pid=").append(r.getPid());

        if (r.getCreatedAt() != null) {
            Duration elapsed = Duration.between(r.getCreatedAt(), r.isTerminal() && r.getCompletedAt() != null ? r.getCompletedAt() : Instant.now());
            sb.append(" elapsed=").append(formatDuration(elapsed));
        }

        if (r.getDescription() != null) {
            String desc = r.getDescription().length() > 60
                    ? r.getDescription().substring(0, 57) + "..."
                    : r.getDescription();
            sb.append(" \"").append(desc).append("\"");
        }

        if (r.getOutputPath() != null) {
            sb.append("\n  output: `").append(r.getOutputPath()).append("`");
        }
        if (r.getResultSummary() != null && !r.getResultSummary().isEmpty()) {
            String summary = r.getResultSummary().length() > 100
                    ? r.getResultSummary().substring(0, 97) + "..."
                    : r.getResultSummary();
            sb.append("\n  result: ").append(summary);
        }
        return sb.toString();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private void persist(TaskRecord record) {
        try {
            Path file = registryDir.resolve(record.getTaskId() + TASK_FILE_SUFFIX);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), record);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Fall back to non-atomic write
            try {
                Path file = registryDir.resolve(record.getTaskId() + TASK_FILE_SUFFIX);
                mapper.writeValue(file.toFile(), record);
            } catch (IOException e2) {
                System.err.println("[TaskRegistry] Error persisting task " + record.getTaskId() + ": " + e2.getMessage());
            }
        }
    }

    private List<TaskRecord> readAll() {
        List<TaskRecord> result = new ArrayList<>();
        if (!Files.exists(registryDir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(registryDir, "*" + TASK_FILE_SUFFIX)) {
            for (Path file : stream) {
                try {
                    result.add(mapper.readValue(file.toFile(), TaskRecord.class));
                } catch (IOException e) {
                    System.err.println("[TaskRegistry] Warning: Skipping corrupt file " + file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[TaskRegistry] Warning: Could not list registry: " + e.getMessage());
        }
        return result;
    }

    static boolean isProcessAlive(long pid) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        long m = s / 60;
        s = s % 60;
        if (m < 60) return m + "m" + s + "s";
        long h = m / 60;
        m = m % 60;
        return h + "h" + m + "m";
    }

    /** Visible for testing. */
    Path getRegistryDir() {
        return registryDir;
    }
}
