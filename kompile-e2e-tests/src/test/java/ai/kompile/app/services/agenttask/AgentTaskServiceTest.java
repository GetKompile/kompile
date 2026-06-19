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

package ai.kompile.app.services.agenttask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentTaskService} — CRUD, persistence, filtering, session management,
 * and task completion lifecycle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskServiceTest {

    @TempDir
    Path tempDir;

    private AgentTaskService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService();
        // Override baseDir to use tempDir so tests are isolated
        Field baseDirField = AgentTaskService.class.getDeclaredField("baseDir");
        baseDirField.setAccessible(true);
        baseDirField.set(service, tempDir);

        service.init();
    }

    // ── create ──────────────────────────────────────────────────────────────────

    @Test
    void create_returnsTaskWithIdAndStatus() {
        AgentTask task = service.create(
                "session-1", "Fix the bug", "Detailed description",
                "alice", "high", List.of("backend"), null);

        assertNotNull(task);
        assertTrue(task.getId() > 0);
        assertEquals("session-1", task.getSessionId());
        assertEquals("Fix the bug", task.getTitle());
        assertEquals("Detailed description", task.getDescription());
        assertEquals("pending", task.getStatus());
        assertEquals("alice", task.getAssignee());
        assertEquals("high", task.getPriority());
        assertTrue(task.getTags().contains("backend"));
        assertNotNull(task.getCreatedAt());
        assertNotNull(task.getUpdatedAt());
    }

    @Test
    void create_withNullPriority_defaultsMedium() {
        AgentTask task = service.create(
                "sess", "Task", null, null, null, null, null);
        assertEquals("medium", task.getPriority());
    }

    @Test
    void create_withNullTags_defaultsEmptyList() {
        AgentTask task = service.create(
                "sess", "Task", null, null, "low", null, null);
        assertNotNull(task.getTags());
        assertTrue(task.getTags().isEmpty());
    }

    @Test
    void create_withNullSession_usesGlobalSession() {
        AgentTask task = service.create(
                null, "Global task", null, null, null, null, null);
        // Session should be normalized to "_global"
        assertEquals("_global", task.getSessionId());
    }

    @Test
    void create_withBlankSession_usesGlobalSession() {
        AgentTask task = service.create(
                "  ", "Blank session", null, null, null, null, null);
        assertEquals("_global", task.getSessionId());
    }

    @Test
    void create_withParentTaskId_preserved() {
        AgentTask parent = service.create("s1", "Parent", null, null, null, null, null);
        AgentTask child = service.create("s1", "Child", null, null, null, null,
                String.valueOf(parent.getId()));
        assertEquals(String.valueOf(parent.getId()), child.getParentTaskId());
    }

    @Test
    void create_multipleTasksHaveIncreasingIds() {
        AgentTask t1 = service.create("s", "T1", null, null, null, null, null);
        AgentTask t2 = service.create("s", "T2", null, null, null, null, null);
        AgentTask t3 = service.create("s", "T3", null, null, null, null, null);
        assertTrue(t2.getId() > t1.getId());
        assertTrue(t3.getId() > t2.getId());
    }

    // ── get ─────────────────────────────────────────────────────────────────────

    @Test
    void get_existingTask_returnsIt() {
        AgentTask created = service.create("s", "Task", null, null, null, null, null);
        AgentTask found = service.get(created.getId());
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());
    }

    @Test
    void get_unknownId_returnsNull() {
        assertNull(service.get(99999L));
    }

    // ── listForSession ───────────────────────────────────────────────────────────

    @Test
    void listForSession_returnsTasksForThatSession() {
        service.create("sess-A", "A1", null, null, null, null, null);
        service.create("sess-A", "A2", null, null, null, null, null);
        service.create("sess-B", "B1", null, null, null, null, null);

        List<AgentTask> tasks = service.listForSession("sess-A", null, null, null);
        assertEquals(2, tasks.size());
        tasks.forEach(t -> assertEquals("sess-A", t.getSessionId()));
    }

    @Test
    void listForSession_unknownSession_returnsEmpty() {
        List<AgentTask> tasks = service.listForSession("no-such-session", null, null, null);
        assertNotNull(tasks);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void listForSession_withStatusFilter_returnsOnlyMatching() {
        service.create("s", "Pending task", null, null, null, null, null);
        AgentTask t2 = service.create("s", "In progress", null, null, null, null, null);
        service.update(t2.getId(), null, null, "in_progress", null, null, null);

        List<AgentTask> pending = service.listForSession("s", "pending", null, null);
        List<AgentTask> inProgress = service.listForSession("s", "in_progress", null, null);

        assertEquals(1, pending.size());
        assertEquals(1, inProgress.size());
        assertEquals("in_progress", inProgress.get(0).getStatus());
    }

    @Test
    void listForSession_withAssigneeFilter() {
        service.create("s", "Task1", null, "alice", null, null, null);
        service.create("s", "Task2", null, "bob", null, null, null);
        service.create("s", "Task3", null, "alice", null, null, null);

        List<AgentTask> aliceTasks = service.listForSession("s", null, "alice", null);
        assertEquals(2, aliceTasks.size());
        aliceTasks.forEach(t -> assertEquals("alice", t.getAssignee()));
    }

    @Test
    void listForSession_withTagFilter() {
        service.create("s", "Task1", null, null, null, List.of("backend", "urgent"), null);
        service.create("s", "Task2", null, null, null, List.of("frontend"), null);
        service.create("s", "Task3", null, null, null, List.of("backend"), null);

        List<AgentTask> backendTasks = service.listForSession("s", null, null, "backend");
        assertEquals(2, backendTasks.size());
    }

    @Test
    void listForSession_sortedByIdAscending() {
        service.create("s", "C", null, null, null, null, null);
        service.create("s", "B", null, null, null, null, null);
        service.create("s", "A", null, null, null, null, null);

        List<AgentTask> tasks = service.listForSession("s", null, null, null);
        for (int i = 1; i < tasks.size(); i++) {
            assertTrue(tasks.get(i).getId() > tasks.get(i - 1).getId());
        }
    }

    // ── listAll ──────────────────────────────────────────────────────────────────

    @Test
    void listAll_returnsTasksFromAllSessions() {
        service.create("sess-X", "X1", null, null, null, null, null);
        service.create("sess-Y", "Y1", null, null, null, null, null);
        service.create("sess-Y", "Y2", null, null, null, null, null);

        List<AgentTask> all = service.listAll(null, null, null);
        assertTrue(all.size() >= 3);
    }

    @Test
    void listAll_withStatusFilter_crossSession() {
        service.create("sA", "T1", null, null, null, null, null);
        AgentTask t2 = service.create("sB", "T2", null, null, null, null, null);
        service.update(t2.getId(), null, null, "completed", null, null, null);

        List<AgentTask> completed = service.listAll("completed", null, null);
        assertTrue(completed.stream().allMatch(t -> "completed".equals(t.getStatus())));
    }

    // ── update ───────────────────────────────────────────────────────────────────

    @Test
    void update_existingTask_updatesFields() {
        AgentTask task = service.create("s", "Old title", null, null, "low", null, null);

        AgentTask updated = service.update(
                task.getId(), "New title", "New desc", "in_progress",
                "bob", "critical", List.of("tag1"));

        assertNotNull(updated);
        assertEquals("New title", updated.getTitle());
        assertEquals("New desc", updated.getDescription());
        assertEquals("in_progress", updated.getStatus());
        assertEquals("bob", updated.getAssignee());
        assertEquals("critical", updated.getPriority());
        assertTrue(updated.getTags().contains("tag1"));
    }

    @Test
    void update_unknownTask_returnsNull() {
        AgentTask result = service.update(
                99999L, "title", null, null, null, null, null);
        assertNull(result);
    }

    @Test
    void update_toCompletedStatus_setsCompletedAt() {
        AgentTask task = service.create("s", "Task", null, null, null, null, null);
        assertNull(task.getCompletedAt());

        AgentTask updated = service.update(
                task.getId(), null, null, "completed", null, null, null);
        assertNotNull(updated.getCompletedAt());
    }

    @Test
    void update_partialUpdate_onlyChangesSpecifiedFields() {
        AgentTask task = service.create("s", "Title", "Desc", "alice", "high", null, null);
        String originalTitle = task.getTitle();
        String originalDesc = task.getDescription();

        // Only update status
        AgentTask updated = service.update(task.getId(), null, null, "in_progress", null, null, null);
        assertEquals(originalTitle, updated.getTitle());
        assertEquals(originalDesc, updated.getDescription());
        assertEquals("alice", updated.getAssignee());
        assertEquals("in_progress", updated.getStatus());
    }

    // ── delete ───────────────────────────────────────────────────────────────────

    @Test
    void delete_existingTask_returnsTrue() {
        AgentTask task = service.create("s", "To delete", null, null, null, null, null);
        boolean deleted = service.delete(task.getId());
        assertTrue(deleted);
        assertNull(service.get(task.getId()));
    }

    @Test
    void delete_unknownTask_returnsFalse() {
        assertFalse(service.delete(88888L));
    }

    @Test
    void delete_removesFromList() {
        AgentTask t1 = service.create("s", "T1", null, null, null, null, null);
        AgentTask t2 = service.create("s", "T2", null, null, null, null, null);

        service.delete(t1.getId());

        List<AgentTask> remaining = service.listForSession("s", null, null, null);
        assertTrue(remaining.stream().noneMatch(t -> t.getId() == t1.getId()));
        assertTrue(remaining.stream().anyMatch(t -> t.getId() == t2.getId()));
    }

    // ── complete ─────────────────────────────────────────────────────────────────

    @Test
    void complete_existingTask_setsCompletedStatus() {
        AgentTask task = service.create("s", "Complete me", null, null, null, null, null);
        AgentTask completed = service.complete(task.getId(), "All done!");

        assertNotNull(completed);
        assertEquals("completed", completed.getStatus());
        assertEquals("All done!", completed.getCompletionNote());
        assertNotNull(completed.getCompletedAt());
    }

    @Test
    void complete_withNullNote_doesNotSetNote() {
        AgentTask task = service.create("s", "Task", null, null, null, null, null);
        AgentTask completed = service.complete(task.getId(), null);
        assertNotNull(completed);
        assertEquals("completed", completed.getStatus());
        assertNull(completed.getCompletionNote());
    }

    @Test
    void complete_unknownTask_returnsNull() {
        assertNull(service.complete(77777L, "note"));
    }

    // ── summary ──────────────────────────────────────────────────────────────────

    @Test
    void summary_emptyService_allZero() {
        Map<String, Object> result = service.summary(null);
        assertNotNull(result);
        assertEquals(0L, result.get("total"));
    }

    @Test
    void summary_withTasks_countsByStatus() {
        service.create("s", "T1", null, null, null, null, null); // pending
        AgentTask t2 = service.create("s", "T2", null, null, null, null, null);
        service.complete(t2.getId(), null); // completed

        Map<String, Object> result = service.summary("s");
        assertEquals(2L, result.get("total"));
        assertTrue(result.containsKey("pending"));
        assertTrue(result.containsKey("completed"));
        assertEquals(1L, result.get("pending"));
        assertEquals(1L, result.get("completed"));
    }

    @Test
    void summary_withSessionId_filtersToThatSession() {
        service.create("sess-A", "A1", null, null, null, null, null);
        service.create("sess-B", "B1", null, null, null, null, null);
        service.create("sess-B", "B2", null, null, null, null, null);

        Map<String, Object> summaryA = service.summary("sess-A");
        assertEquals(1L, summaryA.get("total"));

        Map<String, Object> summaryB = service.summary("sess-B");
        assertEquals(2L, summaryB.get("total"));
    }

    @Test
    void summary_crossSession_includesBySessionBreakdown() {
        service.create("X", "X1", null, null, null, null, null);
        service.create("Y", "Y1", null, null, null, null, null);

        Map<String, Object> result = service.summary(null);
        assertTrue(result.containsKey("bySession"));
        @SuppressWarnings("unchecked")
        Map<String, Long> bySession = (Map<String, Long>) result.get("bySession");
        assertTrue(bySession.containsKey("X") || bySession.containsKey("Y"));
    }

    // ── listSessions ─────────────────────────────────────────────────────────────

    @Test
    void listSessions_returnsAllSessionIds() {
        service.create("alpha", "T", null, null, null, null, null);
        service.create("beta", "T", null, null, null, null, null);

        List<String> sessions = service.listSessions();
        assertTrue(sessions.contains("alpha"));
        assertTrue(sessions.contains("beta"));
    }

    @Test
    void listSessions_initiallyEmpty() {
        // Fresh service with tempDir
        try {
            AgentTaskService fresh = new AgentTaskService();
            Field f = AgentTaskService.class.getDeclaredField("baseDir");
            f.setAccessible(true);
            f.set(fresh, tempDir.resolve("fresh-" + System.currentTimeMillis()));
            fresh.init();
            List<String> sessions = fresh.listSessions();
            assertNotNull(sessions);
            assertTrue(sessions.isEmpty());
        } catch (Exception e) {
            fail("Setup failed: " + e.getMessage());
        }
    }

    // ── Task model getters/setters ────────────────────────────────────────────────

    @Test
    void agentTask_settersAndGetters() {
        AgentTask task = new AgentTask();
        task.setId(42L);
        task.setSessionId("my-session");
        task.setTitle("Title");
        task.setDescription("Desc");
        task.setStatus("pending");
        task.setAssignee("dev");
        task.setPriority("high");
        task.setTags(List.of("a", "b"));
        task.setParentTaskId("p1");
        task.setCompletionNote("Done");
        task.setCreatedAt("2025-01-01T00:00:00Z");
        task.setUpdatedAt("2025-01-02T00:00:00Z");
        task.setCompletedAt("2025-01-03T00:00:00Z");

        assertEquals(42L, task.getId());
        assertEquals("my-session", task.getSessionId());
        assertEquals("Title", task.getTitle());
        assertEquals("Desc", task.getDescription());
        assertEquals("pending", task.getStatus());
        assertEquals("dev", task.getAssignee());
        assertEquals("high", task.getPriority());
        assertEquals(List.of("a", "b"), task.getTags());
        assertEquals("p1", task.getParentTaskId());
        assertEquals("Done", task.getCompletionNote());
        assertEquals("2025-01-01T00:00:00Z", task.getCreatedAt());
        assertEquals("2025-01-02T00:00:00Z", task.getUpdatedAt());
        assertEquals("2025-01-03T00:00:00Z", task.getCompletedAt());
    }

    // ── Persistence: tasks survive re-init ────────────────────────────────────────

    @Test
    void tasks_persistedToDisk_surviveFreshInit() throws Exception {
        AgentTask created = service.create(
                "persist-sess", "Persisted Task", "desc", null, "medium", null, null);
        long taskId = created.getId();

        // Create a new service pointing to the same directory
        AgentTaskService freshService = new AgentTaskService();
        Field f = AgentTaskService.class.getDeclaredField("baseDir");
        f.setAccessible(true);
        f.set(freshService, tempDir);
        freshService.init();

        AgentTask reloaded = freshService.get(taskId);
        assertNotNull(reloaded, "Task should be reloaded from disk");
        assertEquals("Persisted Task", reloaded.getTitle());
        assertEquals("persist-sess", reloaded.getSessionId());
    }
}
