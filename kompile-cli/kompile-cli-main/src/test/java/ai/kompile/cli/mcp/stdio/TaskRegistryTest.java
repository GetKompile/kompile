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
package ai.kompile.cli.mcp.stdio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TaskRegistry} — durable, file-based task tracking
 * that survives MCP tool-call timeouts.
 */
class TaskRegistryTest {

    @TempDir
    Path tempDir;

    private TaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TaskRegistry(tempDir);
    }

    // ── Task creation and persistence ────────────────────────────────────

    @Test
    void createPersistsTaskToDisk() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "test task", "test prompt summary", "session-1", tempDir.toString());
        registry.create(record);

        // Verify file exists
        assertTrue(Files.exists(registry.getRegistryDir().resolve(taskId + ".task.json")));

        // Verify can be read back
        TaskRecord loaded = registry.get(taskId);
        assertNotNull(loaded);
        assertEquals(taskId, loaded.getTaskId());
        assertEquals("task", loaded.getTaskType());
        assertEquals("claude", loaded.getAgentName());
        assertEquals("test task", loaded.getDescription());
        assertEquals(TaskRecord.Status.PENDING, loaded.getStatus());
    }

    @Test
    void generateIdIsUnique() {
        String id1 = TaskRegistry.generateId("agent");
        String id2 = TaskRegistry.generateId("agent");
        assertNotEquals(id1, id2);
        assertTrue(id1.startsWith("task-agent-"));
        assertTrue(id2.startsWith("task-agent-"));
    }

    @Test
    void getNonExistentReturnsNull() {
        assertNull(registry.get("task-nonexistent-12345-0"));
    }

    // ── Task lifecycle transitions ───────────────────────────────────────

    @Test
    void taskLifecyclePendingToRunningToCompleted() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "qwen",
                "lifecycle test", "prompt", null, tempDir.toString());
        registry.create(record);

        // PENDING → RUNNING
        registry.update(taskId, r -> r.markRunning(12345));
        TaskRecord running = registry.get(taskId);
        assertEquals(TaskRecord.Status.RUNNING, running.getStatus());
        assertEquals(12345, running.getPid());
        assertNotNull(running.getStartedAt());
        assertTrue(running.isActive());
        assertFalse(running.isTerminal());

        // RUNNING → COMPLETED
        registry.update(taskId, r -> r.markCompleted(0, "done", "/path/to/output.md"));
        TaskRecord completed = registry.get(taskId);
        assertEquals(TaskRecord.Status.COMPLETED, completed.getStatus());
        assertEquals(0, completed.getExitCode());
        assertEquals("/path/to/output.md", completed.getOutputPath());
        assertNotNull(completed.getCompletedAt());
        assertFalse(completed.isActive());
        assertTrue(completed.isTerminal());
    }

    @Test
    void taskLifecycleRunningToDetached() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "detach test", "prompt", null, tempDir.toString());
        registry.create(record);

        registry.update(taskId, r -> r.markRunning(99999));
        registry.update(taskId, TaskRecord::markDetached);

        TaskRecord detached = registry.get(taskId);
        assertEquals(TaskRecord.Status.DETACHED, detached.getStatus());
        assertTrue(detached.isActive());
        assertFalse(detached.isTerminal());
    }

    @Test
    void taskLifecycleRunningToFailed() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "codex",
                "fail test", "prompt", null, tempDir.toString());
        registry.create(record);

        registry.update(taskId, r -> r.markRunning(11111));
        registry.update(taskId, r -> r.markFailed(1, "rate limited"));

        TaskRecord failed = registry.get(taskId);
        assertEquals(TaskRecord.Status.FAILED, failed.getStatus());
        assertEquals(1, failed.getExitCode());
        assertEquals("rate limited", failed.getResultSummary());
        assertTrue(failed.isTerminal());
    }

    @Test
    void taskLifecycleRunningToCancelled() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "gemini",
                "cancel test", "prompt", null, tempDir.toString());
        registry.create(record);

        registry.update(taskId, r -> r.markRunning(22222));

        // Cancel via registry (process won't actually exist in test)
        boolean cancelled = registry.cancel(taskId);
        assertTrue(cancelled);

        TaskRecord cancelledRecord = registry.get(taskId);
        assertEquals(TaskRecord.Status.CANCELLED, cancelledRecord.getStatus());
        assertTrue(cancelledRecord.isTerminal());
    }

    // ── Parent-child relationships ───────────────────────────────────────

    @Test
    void multiTaskParentChildTracking() {
        // Create parent task
        String parentId = TaskRegistry.generateId("multi");
        TaskRecord parent = new TaskRecord(parentId, "multi_task", "multi",
                "parent task", "multi prompt", null, tempDir.toString());
        parent.markRunning(ProcessHandle.current().pid());
        registry.create(parent);

        // Create child tasks
        String child1Id = TaskRegistry.generateId("agent");
        TaskRecord child1 = new TaskRecord(child1Id, "task", "claude",
                "child 1", "child prompt 1", null, tempDir.toString());
        child1.setParentTaskId(parentId);
        child1.setSubtaskName("backend");
        registry.create(child1);

        String child2Id = TaskRegistry.generateId("agent");
        TaskRecord child2 = new TaskRecord(child2Id, "task", "qwen",
                "child 2", "child prompt 2", null, tempDir.toString());
        child2.setParentTaskId(parentId);
        child2.setSubtaskName("tests");
        registry.create(child2);

        // Register children with parent
        registry.update(parentId, r -> r.addChildTaskId(child1Id));
        registry.update(parentId, r -> r.addChildTaskId(child2Id));

        // Verify parent knows its children
        TaskRecord loadedParent = registry.get(parentId);
        assertNotNull(loadedParent.getChildTaskIds());
        assertEquals(2, loadedParent.getChildTaskIds().size());
        assertTrue(loadedParent.getChildTaskIds().contains(child1Id));
        assertTrue(loadedParent.getChildTaskIds().contains(child2Id));

        // Verify children know their parent
        List<TaskRecord> children = registry.listChildren(parentId);
        assertEquals(2, children.size());
    }

    @Test
    void cancelTreeCancelsParentAndChildren() {
        String parentId = TaskRegistry.generateId("multi");
        TaskRecord parent = new TaskRecord(parentId, "multi_task", "multi",
                "parent", "prompt", null, tempDir.toString());
        parent.markRunning(ProcessHandle.current().pid());
        registry.create(parent);

        String childId = TaskRegistry.generateId("agent");
        TaskRecord child = new TaskRecord(childId, "task", "claude",
                "child", "prompt", null, tempDir.toString());
        child.setParentTaskId(parentId);
        child.markRunning(ProcessHandle.current().pid());
        registry.create(child);

        registry.update(parentId, r -> r.addChildTaskId(childId));

        int cancelled = registry.cancelTree(parentId);
        assertEquals(2, cancelled);

        assertEquals(TaskRecord.Status.CANCELLED, registry.get(parentId).getStatus());
        assertEquals(TaskRecord.Status.CANCELLED, registry.get(childId).getStatus());
    }

    // ── Listing and queries ──────────────────────────────────────────────

    @Test
    void listAllReturnsSortedByCreationTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            String id = TaskRegistry.generateId("agent");
            TaskRecord r = new TaskRecord(id, "task", "agent-" + i,
                    "task " + i, "prompt " + i, null, tempDir.toString());
            registry.create(r);
            Thread.sleep(10); // ensure different timestamps
        }

        List<TaskRecord> all = registry.listAll();
        assertEquals(3, all.size());
        // Newest first
        assertTrue(all.get(0).getCreatedAt().isAfter(all.get(1).getCreatedAt())
                || all.get(0).getCreatedAt().equals(all.get(1).getCreatedAt()));
    }

    @Test
    void listActiveFiltersCorrectly() {
        // Running task
        String runningId = TaskRegistry.generateId("agent");
        TaskRecord running = new TaskRecord(runningId, "task", "claude",
                "running", "prompt", null, tempDir.toString());
        running.markRunning(ProcessHandle.current().pid());
        registry.create(running);

        // Completed task
        String completedId = TaskRegistry.generateId("agent");
        TaskRecord completed = new TaskRecord(completedId, "task", "qwen",
                "completed", "prompt", null, tempDir.toString());
        completed.markCompleted(0, "done", null);
        registry.create(completed);

        // Detached task — use current process PID so it counts as alive
        String detachedId = TaskRegistry.generateId("agent");
        TaskRecord detached = new TaskRecord(detachedId, "task", "gemini",
                "detached", "prompt", null, tempDir.toString());
        detached.markRunning(ProcessHandle.current().pid());
        detached.markDetached();
        registry.create(detached);

        List<TaskRecord> active = registry.listActive();
        // Running and detached tasks whose pid is alive should appear
        assertTrue(active.size() >= 2);
        assertTrue(active.stream().anyMatch(r -> r.getTaskId().equals(runningId)));
        assertTrue(active.stream().anyMatch(r -> r.getTaskId().equals(detachedId)));
        assertFalse(active.stream().anyMatch(r -> r.getTaskId().equals(completedId)));
    }

    @Test
    void listRecentFiltersOldTasks() throws Exception {
        // Create a task with a recent timestamp
        String recentId = TaskRegistry.generateId("agent");
        TaskRecord recent = new TaskRecord(recentId, "task", "claude",
                "recent", "prompt", null, tempDir.toString());
        registry.create(recent);

        List<TaskRecord> recentTasks = registry.listRecent(1); // last 1 hour
        assertEquals(1, recentTasks.size());
        assertEquals(recentId, recentTasks.get(0).getTaskId());
    }

    // ── Process reconciliation ───────────────────────────────────────────

    @Test
    void reconcileMarksDeadProcessAsCompleted() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "dead process", "prompt", null, tempDir.toString());
        // Use a PID that definitely doesn't exist
        record.markRunning(999999999L);
        // Create an output file so reconciliation marks it COMPLETED, not FAILED
        Path outputFile = tempDir.resolve("output.md");
        try { Files.writeString(outputFile, "result"); } catch (Exception e) { fail(e); }
        record.setOutputPath(outputFile.toString());
        registry.create(record);

        registry.reconcileWithProcess(record);

        TaskRecord reconciled = registry.get(taskId);
        assertEquals(TaskRecord.Status.COMPLETED, reconciled.getStatus());
    }

    @Test
    void reconcileMarksDeadProcessWithoutOutputAsFailed() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "dead no output", "prompt", null, tempDir.toString());
        record.markRunning(999999999L);
        registry.create(record);

        registry.reconcileWithProcess(record);

        TaskRecord reconciled = registry.get(taskId);
        assertEquals(TaskRecord.Status.FAILED, reconciled.getStatus());
        assertTrue(reconciled.getResultSummary().contains("no longer running"));
    }

    @Test
    void reconcileKeepsAliveProcessRunning() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "alive process", "prompt", null, tempDir.toString());
        // Use current PID which IS alive
        record.markRunning(ProcessHandle.current().pid());
        registry.create(record);

        registry.reconcileWithProcess(record);

        TaskRecord reconciled = registry.get(taskId);
        assertEquals(TaskRecord.Status.RUNNING, reconciled.getStatus());
    }

    // ── Deletion and cleanup ─────────────────────────────────────────────

    @Test
    void deleteRemovesTaskFile() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "to delete", "prompt", null, tempDir.toString());
        registry.create(record);

        assertTrue(registry.delete(taskId));
        assertNull(registry.get(taskId));
        assertFalse(Files.exists(registry.getRegistryDir().resolve(taskId + ".task.json")));
    }

    @Test
    void evictStaleRemovesOldCompletedTasks() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "old task", "prompt", null, tempDir.toString());
        // Simulate a task completed 25 hours ago
        record.markCompleted(0, "done", null);
        record.setCompletedAt(Instant.now().minusSeconds(25 * 3600));
        registry.create(record);

        int evicted = registry.evictStale();
        assertEquals(1, evicted);
        assertNull(registry.get(taskId));
    }

    @Test
    void evictStaleKeepsRecentCompletedTasks() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "recent completed", "prompt", null, tempDir.toString());
        record.markCompleted(0, "done", null);
        registry.create(record);

        int evicted = registry.evictStale();
        assertEquals(0, evicted);
        assertNotNull(registry.get(taskId));
    }

    // ── Formatting ───────────────────────────────────────────────────────

    @Test
    void formatStatusIncludesKeyFields() {
        TaskRecord record = new TaskRecord("task-agent-123-1", "task", "claude",
                "format test", "prompt summary", null, tempDir.toString());
        record.markRunning(12345);
        record.setOutputPath("/path/to/output.md");
        record.setSubtaskName("backend");

        String formatted = TaskRegistry.formatStatus(record);
        assertTrue(formatted.contains("task-agent-123-1"));
        assertTrue(formatted.contains("RUNNING"));
        assertTrue(formatted.contains("claude"));
        assertTrue(formatted.contains("pid=12345"));
        assertTrue(formatted.contains("backend"));
        assertTrue(formatted.contains("/path/to/output.md"));
    }

    // ── isProcessAlive ───────────────────────────────────────────────────

    @Test
    void isProcessAliveReturnsTrueForCurrentProcess() {
        assertTrue(TaskRegistry.isProcessAlive(ProcessHandle.current().pid()));
    }

    @Test
    void isProcessAliveReturnsFalseForNonexistentPid() {
        assertFalse(TaskRegistry.isProcessAlive(999999999L));
    }

    @Test
    void isProcessAliveReturnsFalseForInvalidPid() {
        assertFalse(TaskRegistry.isProcessAlive(-1));
        assertFalse(TaskRegistry.isProcessAlive(0));
    }
}
