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
 * Tests for detached agent tracking — verifying that tasks remain visible
 * and pollable even when the original MCP call times out.
 *
 * <p>These tests simulate the scenario where:
 * 1. A task/multi_task dispatch creates a durable record before spawning
 * 2. The child process runs longer than the MCP timeout
 * 3. The task remains visible in the registry with RUNNING/DETACHED status
 * 4. A later poll can find, check status, collect results, or cancel
 */
class DetachedAgentTrackingTest {

    @TempDir
    Path tempDir;

    private TaskRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TaskRegistry(tempDir);
    }

    // ── Scenario: Child exceeds MCP timeout ──────────────────────────────

    @Test
    void taskRemainsVisibleAfterTimeout() {
        // Simulate: task record created before process launch
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "long running analysis", "Investigate all usages of...",
                "session-1", tempDir.toString());
        registry.create(record);

        // Simulate: process started
        registry.update(taskId, r -> r.markRunning(ProcessHandle.current().pid()));

        // Simulate: MCP call times out, but process still running
        // The caller would get a timeout error, but can poll later

        // Poll by ID should find the task
        TaskRecord polled = registry.get(taskId);
        assertNotNull(polled, "Task should be findable after MCP timeout");
        assertEquals(TaskRecord.Status.RUNNING, polled.getStatus());
        assertEquals(ProcessHandle.current().pid(), polled.getPid());

        // Active tasks listing should include it
        List<TaskRecord> active = registry.listActive();
        assertTrue(active.stream().anyMatch(r -> r.getTaskId().equals(taskId)),
                "Task should appear in active tasks list after timeout");
    }

    @Test
    void detachedTaskRemainsPolllable() {
        // Create and mark as detached (timeout occurred)
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "qwen",
                "detached work", "Build and test...",
                "session-1", tempDir.toString());
        record.markRunning(ProcessHandle.current().pid());
        record.markDetached();
        registry.create(record);

        // Should appear in active listing
        List<TaskRecord> active = registry.listActive();
        assertTrue(active.stream().anyMatch(r -> r.getTaskId().equals(taskId)));

        // Direct poll should work
        TaskRecord polled = registry.get(taskId);
        assertNotNull(polled);
        assertEquals(TaskRecord.Status.DETACHED, polled.getStatus());
        assertTrue(polled.isActive());
    }

    // ── Scenario: multi_task parent and child persistence ────────────────

    @Test
    void multiTaskParentAndChildIdsPersisted() {
        // Simulate multi_task creating parent record first
        String parentId = TaskRegistry.generateId("multi");
        TaskRecord parent = new TaskRecord(parentId, "multi_task", "multi",
                "parallel build", "Backend + tests", "session-1", tempDir.toString());
        parent.markRunning(ProcessHandle.current().pid());
        registry.create(parent);

        // Each subtask gets its own record with parent reference
        String child1 = TaskRegistry.generateId("agent");
        TaskRecord c1 = new TaskRecord(child1, "task", "claude",
                "backend impl", "Implement...", "session-1", tempDir.toString());
        c1.setParentTaskId(parentId);
        c1.setSubtaskName("backend");
        c1.markRunning(ProcessHandle.current().pid());
        registry.create(c1);
        registry.update(parentId, r -> r.addChildTaskId(child1));

        String child2 = TaskRegistry.generateId("agent");
        TaskRecord c2 = new TaskRecord(child2, "task", "qwen",
                "test writing", "Write tests...", "session-1", tempDir.toString());
        c2.setParentTaskId(parentId);
        c2.setSubtaskName("tests");
        c2.markRunning(ProcessHandle.current().pid());
        registry.create(c2);
        registry.update(parentId, r -> r.addChildTaskId(child2));

        // Verify persistence — new registry instance reads from disk
        TaskRegistry freshRegistry = new TaskRegistry(tempDir);
        TaskRecord loadedParent = freshRegistry.get(parentId);
        assertNotNull(loadedParent);
        assertEquals(2, loadedParent.getChildTaskIds().size());
        assertTrue(loadedParent.getChildTaskIds().contains(child1));
        assertTrue(loadedParent.getChildTaskIds().contains(child2));

        List<TaskRecord> children = freshRegistry.listChildren(parentId);
        assertEquals(2, children.size());
    }

    // ── Scenario: Result collection after detached child completes ───────

    @Test
    void resultCollectedAfterDetachedChildCompletes() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "analysis task", "Analyze...", "session-1", tempDir.toString());
        record.markRunning(ProcessHandle.current().pid());
        record.markDetached(); // caller timed out
        registry.create(record);

        // Simulate child process completing and writing output file
        Path outputFile = tempDir.resolve(".kompile/task-results/claude-result.md");
        try {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, "# Analysis Result\nFound 3 issues...");
        } catch (Exception e) { fail(e); }

        // Update task with result
        registry.update(taskId, r ->
                r.markCompleted(0, "Analysis complete, 3 issues found",
                        outputFile.toAbsolutePath().toString()));

        // Later poll should find the result
        TaskRecord result = registry.get(taskId);
        assertEquals(TaskRecord.Status.COMPLETED, result.getStatus());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getResultSummary().contains("3 issues"));
        assertNotNull(result.getOutputPath());
        assertTrue(Files.exists(Path.of(result.getOutputPath())));
    }

    // ── Scenario: Poll reports detached/running after original times out ─

    @Test
    void pollReportsActiveChildrenAfterOriginalTimeout() {
        // Create parent and children
        String parentId = TaskRegistry.generateId("multi");
        TaskRecord parent = new TaskRecord(parentId, "multi_task", "multi",
                "timed out multi", "...", "session-1", tempDir.toString());
        parent.markRunning(ProcessHandle.current().pid());
        registry.create(parent);

        // Child 1 completed
        String c1Id = TaskRegistry.generateId("agent");
        TaskRecord c1 = new TaskRecord(c1Id, "task", "claude",
                "fast child", "...", "session-1", tempDir.toString());
        c1.setParentTaskId(parentId);
        c1.markCompleted(0, "done", null);
        registry.create(c1);
        registry.update(parentId, r -> r.addChildTaskId(c1Id));

        // Child 2 still running
        String c2Id = TaskRegistry.generateId("agent");
        TaskRecord c2 = new TaskRecord(c2Id, "task", "qwen",
                "slow child", "...", "session-1", tempDir.toString());
        c2.setParentTaskId(parentId);
        c2.markRunning(ProcessHandle.current().pid());
        registry.create(c2);
        registry.update(parentId, r -> r.addChildTaskId(c2Id));

        // Active listing should include the still-running child
        List<TaskRecord> active = registry.listActive();
        assertTrue(active.stream().anyMatch(r -> r.getTaskId().equals(c2Id)));

        // Parent listing should show both children
        List<TaskRecord> children = registry.listChildren(parentId);
        assertEquals(2, children.size());

        // One completed, one still active
        long completedCount = children.stream()
                .filter(r -> r.getStatus() == TaskRecord.Status.COMPLETED).count();
        long runningCount = children.stream()
                .filter(r -> r.getStatus() == TaskRecord.Status.RUNNING).count();
        assertEquals(1, completedCount);
        assertEquals(1, runningCount);
    }

    // ── Scenario: Cancellation and cleanup ───────────────────────────────

    @Test
    void cancelDetachedTaskUpdatesStatus() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "to cancel", "...", "session-1", tempDir.toString());
        record.markRunning(ProcessHandle.current().pid());
        record.markDetached();
        registry.create(record);

        // Cancel the task (won't kill our own process in tests, but updates status)
        boolean cancelled = registry.cancel(taskId);
        assertTrue(cancelled);

        TaskRecord afterCancel = registry.get(taskId);
        assertEquals(TaskRecord.Status.CANCELLED, afterCancel.getStatus());
        assertTrue(afterCancel.isTerminal());
        assertFalse(afterCancel.isActive());
    }

    @Test
    void cannotCancelAlreadyCompletedTask() {
        String taskId = TaskRegistry.generateId("agent");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "already done", "...", "session-1", tempDir.toString());
        record.markCompleted(0, "done", null);
        registry.create(record);

        boolean cancelled = registry.cancel(taskId);
        assertFalse(cancelled, "Should not cancel an already completed task");

        // Status should not change
        assertEquals(TaskRecord.Status.COMPLETED, registry.get(taskId).getStatus());
    }

    // ── Scenario: Result file metadata ties back to task ─────────────────

    @Test
    void resultFileContainsTaskMetadata() {
        String taskId = TaskRegistry.generateId("agent");
        String parentId = TaskRegistry.generateId("multi");
        TaskRecord record = new TaskRecord(taskId, "task", "claude",
                "metadata test", "Analyze the codebase...",
                "session-123", tempDir.toString());
        record.setParentTaskId(parentId);
        record.setSubtaskName("analysis");
        record.setRoleName("researcher");
        record.markRunning(12345);
        record.markCompleted(0, "Found 5 classes", "/output/result.md");
        registry.create(record);

        TaskRecord loaded = registry.get(taskId);
        assertEquals(taskId, loaded.getTaskId());
        assertEquals(parentId, loaded.getParentTaskId());
        assertEquals("claude", loaded.getAgentName());
        assertEquals("researcher", loaded.getRoleName());
        assertEquals("analysis", loaded.getSubtaskName());
        assertEquals(12345, loaded.getPid());
        assertEquals("/output/result.md", loaded.getOutputPath());
        assertEquals("Analyze the codebase...", loaded.getPromptSummary());
    }

    // ── Scenario: AGENTS.md injection ────────────────────────────────────

    @Test
    void agentsMdAutoDiscovery() throws Exception {
        // Create AGENTS.md in the temp work directory
        Path agentsMd = tempDir.resolve("AGENTS.md");
        Files.writeString(agentsMd, """
                # Agent Instructions

                ## Banned Commands
                - Never use `tail` on log files
                - Never use `git reset --hard`
                - Always use Maven, not direct `make`
                """);

        // Verify the file exists
        assertTrue(Files.exists(agentsMd));
        String content = Files.readString(agentsMd);
        assertTrue(content.contains("Banned Commands"));
        assertTrue(content.contains("tail"));
        assertTrue(content.contains("git reset --hard"));
    }

    // ── Scenario: Stale record cleanup ───────────────────────────────────

    @Test
    void staleRecordCleanupDoesNotRemoveActive() {
        // Active task (should not be evicted)
        String activeId = TaskRegistry.generateId("agent");
        TaskRecord active = new TaskRecord(activeId, "task", "claude",
                "active", "...", "session-1", tempDir.toString());
        active.markRunning(ProcessHandle.current().pid());
        registry.create(active);

        // Very old completed task (should be evicted)
        String oldId = TaskRegistry.generateId("agent");
        TaskRecord old = new TaskRecord(oldId, "task", "qwen",
                "old", "...", "session-2", tempDir.toString());
        old.markCompleted(0, "done", null);
        old.setCompletedAt(Instant.now().minusSeconds(48 * 3600));
        registry.create(old);

        int evicted = registry.evictStale();
        assertEquals(1, evicted);

        // Active task should still exist
        assertNotNull(registry.get(activeId));
        // Old task should be gone
        assertNull(registry.get(oldId));
    }
}
