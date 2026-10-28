package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("user.home")
class TodoWriteToolTest {

    /** Fresh project root per test; session-scoped todo files live beneath its managed memory. */
    @TempDir
    Path workDir;

    private TodoWriteTool tool;
    private ToolContext context;
    private ObjectMapper om;
    private String sessionId;
    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", workDir.toString());
        tool = new TodoWriteTool();
        om = new ObjectMapper();
        // Use a unique session ID per test to avoid cross-test contamination
        sessionId = "test-" + UUID.randomUUID();

        AgentConfig agent = AgentConfig.builder("coder")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService perms = new PermissionService();
        ToolRegistry registry = new ToolRegistry(om);
        context = new ToolContext(sessionId, agent, perms, workDir, registry);
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void testIdAndDescription() {
        assertEquals("todowrite", tool.id());
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("todo"));
        assertTrue(tool.compactHint().contains("current transcript"));
    }

    @Test
    void testAddTask() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Implement feature X");
        params.put("task_description", "Add the new endpoint");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Added task"));

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(1, todos.size());
        assertEquals("Implement feature X", todos.get(0).subject);
        assertEquals("Add the new endpoint", todos.get(0).description);
        assertEquals("pending", todos.get(0).status);
        assertEquals("medium", todos.get(0).priority);
    }

    @Test
    void testAddTaskWithPriority() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Critical fix");
        params.put("priority", "high");

        tool.execute(params, context);

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(1, todos.size());
        assertEquals("high", todos.get(0).priority);
    }

    @Test
    void testAddTaskInvalidPriorityIsRejected() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Some task");
        params.put("priority", "urgent");

        assertTrue(tool.execute(params, context).isError());
        assertTrue(TodoWriteTool.getTodos(sessionId, workDir).isEmpty());
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(sessionId)));
    }

    @Test
    void testAddTaskRequiresSubject() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");

        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("subject is required"));
    }

    @Test
    void testUpdateTaskStatus() throws Exception {
        // Add a task first
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Task to update");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId, workDir).get(0).id;

        // Update status
        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("status", "in_progress");

        ToolResult result = tool.execute(updateParams, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("pending → in_progress"));
        assertEquals("in_progress", TodoWriteTool.getTodos(sessionId, workDir).get(0).status);
    }

    @Test
    void testUpdateTaskCompleted() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Task to complete");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId, workDir).get(0).id;

        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("status", "completed");

        tool.execute(updateParams, context);
        assertEquals("completed", TodoWriteTool.getTodos(sessionId, workDir).get(0).status);
    }

    @Test
    void testUpdateTaskNotFound() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "update");
        params.put("task_id", "999");
        params.put("status", "completed");

        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Task not found"));
    }

    @Test
    void testAddStampsCreatedAt() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Timestamped task");
        tool.execute(params, context);

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(1, todos.size());
        assertNotNull(todos.get(0).createdAt, "createdAt must be set on add");
        assertDoesNotThrow(() -> java.time.Instant.parse(todos.get(0).createdAt),
                "createdAt must be an ISO-8601 instant");
        // A fresh item has no mutation yet.
        assertNull(todos.get(0).updatedAt);
    }

    @Test
    void testUpdateStampsUpdatedAtAndPreservesCreatedAt() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Task to update");
        tool.execute(addParams, context);
        TodoWriteTool.TodoItem added = TodoWriteTool.getTodos(sessionId, workDir).get(0);
        String createdAt = added.createdAt;
        assertNotNull(createdAt);

        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", added.id);
        updateParams.put("status", "completed");
        tool.execute(updateParams, context);

        TodoWriteTool.TodoItem updated = TodoWriteTool.getTodos(sessionId, workDir).get(0);
        assertEquals("completed", updated.status);
        assertEquals(createdAt, updated.createdAt, "createdAt must survive updates");
        assertNotNull(updated.updatedAt, "updatedAt must be stamped on status change");
        assertDoesNotThrow(() -> java.time.Instant.parse(updated.updatedAt));
    }

    @Test
    void testSetPreservesCreatedAtForExistingTasks() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Original");
        tool.execute(addParams, context);
        TodoWriteTool.TodoItem original = TodoWriteTool.getTodos(sessionId, workDir).get(0);

        // set replaces subject of the existing task but must keep its creation time
        ObjectNode setParams = om.createObjectNode();
        setParams.put("action", "set");
        ArrayNode todos = setParams.putArray("todos");
        ObjectNode item = todos.addObject();
        item.put("id", original.id);
        item.put("subject", "Renamed");
        item.put("status", "completed");
        tool.execute(setParams, context);

        TodoWriteTool.TodoItem after = TodoWriteTool.getTodos(sessionId, workDir).get(0);
        assertEquals("Renamed", after.subject);
        assertEquals(original.createdAt, after.createdAt, "set must retain createdAt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "set", "batch"})
    void fieldOnlyMutationsRefreshModelProvenance(String action) throws Exception {
        for (String field : List.of("subject", "task_description", "priority", "status")) {
            ObjectNode before = seedTimestampedTask(true);
            String value = switch (field) {
                case "subject" -> "Renamed";
                case "task_description" -> ""; // Clearing a description is also a real edit.
                case "priority" -> "high";
                default -> "completed";
            };
            ToolResult result = mutateTask(action, field, value);
            assertFalse(result.isError(), result.getOutput());
            JsonNode persisted = om.readTree(TodoWriteTool.sessionTodoPath(sessionId).toFile())
                    .path("todos").get(0);
            assertEquals("model", persisted.path("updated_by").asText(), action + ": " + field);
            String updatedAt = persisted.path("updated_at").asText();
            assertTrue(java.time.Instant.parse(updatedAt).isAfter(
                    java.time.Instant.parse(before.path("updated_at").asText())), action + ": " + field);
            ObjectNode expected = before.deepCopy();
            expected.put("task_description".equals(field) ? "description" : field, value);
            expected.put("updated_by", "model").put("updated_at", updatedAt);
            assertEquals(expected, persisted, action + ": " + field);
            JsonNode snapshot = om.valueToTree(result.getMetadata().get(TodoWriteTool.TASKS_METADATA));
            TodoWriteTool.TodoItem reloaded = TodoWriteTool.getTodos(sessionId, workDir).get(0);
            assertEquals(om.valueToTree(reloaded), snapshot.get(0));
            assertEquals(before.path("created_at").asText(), reloaded.createdAt);
            assertEquals(updatedAt, reloaded.updatedAt);
            assertEquals("model", reloaded.updatedBy);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "set", "batch"})
    void unchangedMutationsPreserveProvenance(String action) throws Exception {
        for (boolean previouslyUpdated : List.of(false, true)) {
            for (String field : List.of("subject", "task_description", "priority", "status")) {
                ObjectNode before = seedTimestampedTask(previouslyUpdated);
                String value = before.path("task_description".equals(field) ? "description" : field).asText();
                ToolResult result = mutateTask(action, field, value);
                assertFalse(result.isError(), result.getOutput());
                JsonNode persisted = om.readTree(TodoWriteTool.sessionTodoPath(sessionId).toFile())
                        .path("todos").get(0);
                assertEquals(before, persisted, action + ": " + field + ", updated=" + previouslyUpdated);
            }
        }
    }

    @Test
    void legacyUpdateBackfillsTimestampsWithoutClaimingAContentChange() throws Exception {
        ObjectNode legacy = seedTimestampedTask(false);
        legacy.remove("created_at");
        persistTask(legacy);
        ToolResult result = mutateTask("update", "subject", "Original");
        assertFalse(result.isError(), result.getOutput());
        TodoWriteTool.TodoItem item = TodoWriteTool.getTodos(sessionId, workDir).get(0);
        assertDoesNotThrow(() -> java.time.Instant.parse(item.createdAt));
        assertEquals(item.createdAt, item.updatedAt);
        assertNull(item.updatedBy, "Backfill alone must not claim a model content change");
    }

    @Test
    void harnessMutationSharesProvenanceAndNoopKeepsIt() throws Exception {
        ObjectNode before = seedTimestampedTask(true);
        var mutation = new TodoWriteTool.HarnessMutation(sessionId, TodoWriteTool.HarnessMutationKind.AUTO_START_FIRST);
        var result = TodoWriteTool.applyHarnessMutation(mutation, workDir);
        assertTrue(result.applied(), result.reason());
        TodoWriteTool.TodoItem item = result.todos().get(0);
        assertEquals("in_progress", item.status);
        assertEquals("harness", item.updatedBy);
        assertEquals(before.path("created_at").asText(), item.createdAt);
        assertTrue(java.time.Instant.parse(item.updatedAt).isAfter(
                java.time.Instant.parse(before.path("updated_at").asText())));
        JsonNode saved = om.readTree(TodoWriteTool.sessionTodoPath(sessionId).toFile()).path("todos");
        assertFalse(TodoWriteTool.applyHarnessMutation(mutation, workDir).applied());
        assertEquals(saved, om.readTree(TodoWriteTool.sessionTodoPath(sessionId).toFile()).path("todos"));
    }

    private ObjectNode seedTimestampedTask(boolean previouslyUpdated) throws Exception {
        ObjectNode item = om.createObjectNode().put("id", "1").put("subject", "Original")
                .put("description", "Details").put("status", "pending").put("priority", "medium")
                .put("created_at", "2000-01-01T00:00:00Z");
        if (previouslyUpdated) item.put("updated_at", "2001-01-01T00:00:00Z").put("updated_by", "harness");
        persistTask(item);
        return item;
    }

    private void persistTask(ObjectNode item) throws Exception {
        Path file = TodoWriteTool.sessionTodoPath(sessionId);
        Files.createDirectories(file.getParent());
        ObjectNode root = om.createObjectNode();
        root.putArray("todos").add(item);
        Files.writeString(file, om.writeValueAsString(root));
    }

    private ToolResult mutateTask(String action, String field, String value) throws Exception {
        ObjectNode request = om.createObjectNode();
        if ("set".equals(action)) {
            request.put("action", "set");
            ObjectNode item = request.putArray("todos").addObject().put("id", "1");
            // Exercise the set-only content alias as well as subject updates.
            item.put("content", "subject".equals(field) ? value : "Original");
            if (!"subject".equals(field)) item.put(field, value);
        } else {
            request.put("action", "update").put("task_id", "1").put(field, value);
        }
        if ("batch".equals(action)) {
            ObjectNode batch = om.createObjectNode();
            batch.putArray("operations").add(request);
            return tool.executeBatch(batch, context);
        }
        return tool.execute(request, context);
    }

    @Test
    void testCreatedAtRoundTripsThroughPersistence() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Persisted task");
        tool.execute(params, context);

        // Read straight from the JSON file to confirm created_at is persisted
        Path file = TodoWriteTool.sessionTodoPath(sessionId);
        JsonNode root = om.readTree(file.toFile());
        JsonNode first = root.path("todos").get(0);
        assertTrue(first.hasNonNull("created_at"), "created_at must be persisted");
        assertDoesNotThrow(() -> java.time.Instant.parse(first.get("created_at").asText()));
    }

    @Test
    void testUpdateRequiresTaskId() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "update");

        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("task_id is required"));
    }

    @Test
    void testDeleteTask() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Task to delete");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId, workDir).get(0).id;

        ObjectNode deleteParams = om.createObjectNode();
        deleteParams.put("action", "delete");
        deleteParams.put("task_id", taskId);

        ToolResult result = tool.execute(deleteParams, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Deleted"));
        assertTrue(TodoWriteTool.getTodos(sessionId, workDir).isEmpty());
    }

    @Test
    void testDeleteTaskNotFound() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "delete");
        params.put("task_id", "nonexistent");

        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
    }

    @Test
    void testUnknownAction() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "freeze");

        ToolResult result = tool.execute(params, context);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown action"));
    }

    @Test
    void testMultipleTasks() throws Exception {
        for (int i = 1; i <= 5; i++) {
            ObjectNode params = om.createObjectNode();
            params.put("action", "add");
            params.put("subject", "Task " + i);
            tool.execute(params, context);
        }

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(5, todos.size());
        assertEquals("Task 1", todos.get(0).subject);
        assertEquals("Task 5", todos.get(4).subject);
    }

    @Test
    void testSessionIsolation() throws Exception {
        // Add to current session
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Session A task");
        tool.execute(params, context);

        // A different transcript in the same project starts empty and persists separately.
        String otherSession = "other-" + UUID.randomUUID();
        assertTrue(TodoWriteTool.getTodos(otherSession, workDir).isEmpty());
        ToolContext otherContext = new ToolContext(otherSession, context.getAgent(),
                context.getPermissionService(), workDir, new ToolRegistry(om));
        ObjectNode otherParams = om.createObjectNode();
        otherParams.put("action", "add");
        otherParams.put("subject", "Session B task");
        tool.execute(otherParams, otherContext);

        assertEquals(1, TodoWriteTool.getTodos(sessionId, workDir).size());
        assertEquals("Session A task", TodoWriteTool.getTodos(sessionId, workDir).get(0).subject);
        assertEquals("Session B task", TodoWriteTool.getTodos(otherSession, workDir).get(0).subject);
        assertNotEquals(TodoWriteTool.sessionTodoPath(sessionId),
                TodoWriteTool.sessionTodoPath(otherSession));
    }

    @Test
    void testPersistedTasksReloadForResumedSession() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Survives process restart");
        tool.execute(params, context);

        ToolContext resumedContext = new ToolContext(sessionId, context.getAgent(),
                context.getPermissionService(), workDir, new ToolRegistry(om));

        List<TodoWriteTool.TodoItem> reloaded = TodoWriteTool.getTodos(resumedContext);
        assertEquals(1, reloaded.size());
        assertEquals("Survives process restart", reloaded.get(0).subject);
    }

    @Test
    void testConcurrentAddsInOneSessionAreNotLost() throws Exception {
        int taskCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<ToolResult>> writes = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                int taskNumber = i;
                writes.add(executor.submit(() -> {
                    ObjectNode params = om.createObjectNode();
                    params.put("action", "add");
                    params.put("subject", "Concurrent task " + taskNumber);
                    return tool.execute(params, context);
                }));
            }
            for (Future<ToolResult> write : writes) {
                assertFalse(write.get().isError());
            }
        } finally {
            executor.shutdownNow();
        }

        List<TodoWriteTool.TodoItem> persisted = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(taskCount, persisted.size());
        assertEquals(taskCount, persisted.stream().map(item -> item.id).distinct().count());
    }

    @Test
    @ResourceLock("kompile.transcript.uuid")
    void testDefaultWriteUsesActiveTranscriptId() throws Exception {
        String previous = System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        String activeTranscriptId = "transcript-" + UUID.randomUUID();
        try {
            System.setProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, activeTranscriptId);
            ObjectNode params = om.createObjectNode();
            params.put("action", "add");
            params.put("subject", "Bound to active transcript");

            tool.execute(params, context);

            assertEquals("Bound to active transcript",
                    TodoWriteTool.getTodos(activeTranscriptId, workDir).get(0).subject);
            assertTrue(TodoWriteTool.getTodos(sessionId, workDir).isEmpty());
        } finally {
            if (previous == null) {
                System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            } else {
                System.setProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, previous);
            }
        }
    }

    @Test
    void testLegacyGlobalTasksRequireTheirRecordedSessionOrExplicitLegacyLookup() throws Exception {
        Path legacyFile = workDir.resolve(".kompile").resolve("memory")
                .resolve("project-todos.json.md");
        Files.createDirectories(legacyFile.getParent());
        ObjectNode legacyRoot = om.createObjectNode();
        legacyRoot.put("schema", "kompile-project-todos-v1");
        legacyRoot.put("sessionId", "old-session");
        legacyRoot.putArray("todos").addObject()
                .put("id", "1")
                .put("subject", "Legacy task")
                .put("status", "pending")
                .put("priority", "medium");
        Files.writeString(legacyFile, om.writeValueAsString(legacyRoot));

        assertTrue(TodoWriteTool.getTodos(sessionId, workDir).isEmpty(),
                "legacy global data must not leak into an unrelated current session");
        assertEquals("Legacy task",
                TodoWriteTool.getTodos("old-session", workDir).get(0).subject);
        assertEquals("Legacy task",
                TodoWriteTool.getTodos(TodoWriteTool.LEGACY_SESSION_ID, workDir).get(0).subject);
    }

    @Test
    void testSessionIdCannotEscapeTodoDirectory() {
        Path taskFile = TodoWriteTool.sessionTodoPath("../../outside");
        Path todoDirectory = workDir.resolve(".kompile").resolve("conversations")
                .toAbsolutePath().normalize();

        assertEquals(todoDirectory, taskFile.getParent());
        assertTrue(taskFile.startsWith(todoDirectory));
    }

    @Test
    void testSetAcceptsPlainStringItems() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "set");
        ArrayNode arr = params.putArray("todos");
        arr.add("First plain task");
        arr.add("Second plain task");

        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Set 2 task(s)"));
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals("First plain task", todos.get(0).subject);
        assertEquals("pending", todos.get(0).status);
        assertEquals("medium", todos.get(1).priority);
    }

    /**
     * Persisting must not rely on Jackson bean reflection over TodoItem — that has no
     * registered reflection config in the native image and made todowrite fail there.
     * The array is built field by field; this pins the persisted shape.
     */
    @Test
    void testSetPersistsTodosToTranscriptState() throws Exception {
        String persistedSessionId = "persist-" + UUID.randomUUID();
        ToolContext isolated = new ToolContext(persistedSessionId, context.getAgent(),
                context.getPermissionService(), workDir, new ToolRegistry(om));

        ObjectNode params = om.createObjectNode();
        params.put("action", "set");
        ObjectNode item = params.putArray("todos").addObject();
        item.put("subject", "Persisted task");
        item.put("status", "in_progress");
        item.put("priority", "high");

        ToolResult result = tool.execute(params, isolated);
        assertFalse(result.isError());

        Path file = TodoWriteTool.sessionTodoPath(persistedSessionId);
        assertTrue(Files.isRegularFile(file));
        JsonNode root = om.readTree(file.toFile());
        assertEquals("kompile-session-todos-v2", root.path("schema").asText());
        assertEquals(persistedSessionId, root.path("sessionId").asText());
        assertEquals(1, root.path("todos").size());
        JsonNode persisted = root.path("todos").get(0);
        assertEquals("Persisted task", persisted.path("subject").asText());
        assertEquals("in_progress", persisted.path("status").asText());
        assertEquals("high", persisted.path("priority").asText());
        assertFalse(Files.exists(workDir.resolve(".kompile").resolve("memory")
                .resolve("project-todos.json.md")));
    }

    private void seedGuardedList() throws Exception {
        ToolResult result = tool.execute(om.readTree("""
                {"action":"set","todos":[
                  {"id":"first","subject":"Keep first","description":"Important details","status":"in_progress","priority":"high"},
                  {"id":"second","subject":"Keep second","status":"completed"}]}
                """), context);
        assertFalse(result.isError(), result.getOutput());
    }

    private ToolResult assertRejectedUnchanged(JsonNode request) throws Exception {
        Path file = TodoWriteTool.sessionTodoPath(sessionId);
        byte[] before = Files.readAllBytes(file);
        JsonNode tasks = om.valueToTree(TodoWriteTool.getTodos(sessionId, workDir));
        ToolResult result = tool.execute(request, context);
        assertTrue(result.isError(), String.valueOf(request));
        assertTrue(result.getOutput().contains("No tasks were changed"), result.getOutput());
        assertTrue(result.getOutput().contains("todoread"), result.getOutput());
        assertFalse(result.getMetadata().containsKey(TodoWriteTool.TASKS_METADATA));
        assertArrayEquals(before, Files.readAllBytes(file), String.valueOf(request));
        assertEquals(tasks, om.valueToTree(TodoWriteTool.getTodos(sessionId, workDir)));
        return result;
    }

    @Test
    void setCannotClearOrDropEvenCompletedTasks() throws Exception {
        seedGuardedList();
        for (String json : List.of(
                "{\"action\":\"set\",\"todos\":[]}",
                "{\"action\":\"set\",\"todos\":[{\"id\":\"first\",\"subject\":\"Keep first\"}]}",
                "{\"action\":\"set\",\"todos\":[\"Unrelated new plan\"]}",
                "{\"action\":\"set\",\"todos\":[{\"subject\":\"New plan\"}]}")) {
            assertRejectedUnchanged(om.readTree(json));
        }
    }

    @Test
    void malformedSetItemsRejectWholeCallNotJustBadItems() throws Exception {
        seedGuardedList();
        for (String malformed : List.of("null", "42", "true", "[]", "{}", "\" \"",
                "{\"subject\":null}", "{\"subject\":\" \"}", "{\"subject\":3}",
                "{\"subject\":\"Bad\",\"status\":\"done\"}",
                "{\"subject\":\"Bad\",\"priority\":\"urgent\"}",
                "{\"subject\":\"Bad\",\"id\":1}",
                "{\"subject\":\"Bad\",\"task_id\":\"first\"}",
                "{\"subject\":\"Bad\",\"content\":\"Conflicting\"}")) {
            ObjectNode request = om.createObjectNode().put("action", "set");
            request.set("todos", om.readTree("""
                    [{"id":"first","subject":"Would change first"},{"id":"second","subject":"Keep second"}]
                    """));
            ((ArrayNode) request.get("todos")).add(om.readTree(malformed));
            assertRejectedUnchanged(request);
        }
    }

    @Test
    void duplicateAndInventedSetIdsAreRejected() throws Exception {
        seedGuardedList();
        for (String id : List.of("first", "invented")) {
            ObjectNode request = om.createObjectNode().put("action", "set");
            ArrayNode tasks = request.putArray("todos");
            tasks.addObject().put("id", "first").put("subject", "First");
            tasks.addObject().put("id", "second").put("subject", "Second");
            tasks.addObject().put("id", id).put("subject", "Extra");
            assertRejectedUnchanged(request);
        }
    }

    @Test
    void invalidMutationsDoNotPartiallyApplyValidFields() throws Exception {
        seedGuardedList();
        for (String json : List.of(
                "null", "[]", "{}", "{\"action\":false}",
                "{\"action\":\"set\"}", "{\"action\":\"set\",\"todos\":{}}",
                "{\"action\":\"update\",\"task_id\":\"first\"}",
                "{\"action\":\"update\",\"task_id\":\"first\",\"subject\":\"Renamed\",\"status\":\"done\"}",
                "{\"action\":\"update\",\"task_id\":\"first\",\"status\":\"completed\",\"priority\":\"urgent\"}",
                "{\"action\":\"update\",\"task_id\":\"first\",\"subject\":null}",
                "{\"action\":\"update\",\"task_id\":\"first\",\"subject\":\" \"}",
                "{\"action\":\"update\",\"task_id\":1,\"status\":\"completed\"}",
                "{\"action\":\"update\",\"id\":\"first\",\"status\":\"completed\"}",
                "{\"action\":\"update\",\"task_id\":\"first\",\"status\":\"completed\",\"todos\":[]}",
                "{\"action\":\"add\",\"subject\":\"New task\",\"task_id\":\"invented\"}",
                "{\"action\":\"add\",\"subject\":\"New task\",\"status\":\"done\"}",
                "{\"action\":\"add\",\"subject\":true}",
                "{\"action\":\"delete\",\"task_id\":\"first\",\"todos\":[]}",
                "{\"action\":\"delete\",\"task_id\":\" \"}",
                "{\"action\":\"add\",\"subject\":\"New task\",\"session_id\":null}")) {
            assertRejectedUnchanged(om.readTree(json));
        }
    }

    @Test
    void inventedTaskAndSessionIdsCannotTouchRealList() throws Exception {
        seedGuardedList();
        assertRejectedUnchanged(om.readTree("""
                {"action":"update","task_id":"invented","status":"completed"}
                """));
        assertRejectedUnchanged(om.readTree("{\"action\":\"delete\",\"task_id\":\"invented\"}"));
        assertRejectedUnchanged(om.readTree("""
                {"action":"set","session_id":"invented-session","todos":["Wrong list"]}
                """));
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath("invented-session")));
    }

    @Test
    void realTranscriptWithoutSnapshotCanReceiveItsFirstTask() throws Exception {
        seedGuardedList();
        String historicalId = "real-historical-chat";
        Path transcript = new ai.kompile.cli.main.chat.ChatHistory(historicalId).getTranscriptFile();
        Files.writeString(transcript, "Existing conversation");
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(historicalId)));
        ObjectNode add = om.createObjectNode().put("action", "add").put("session_id", historicalId)
                .put("subject", "First historical task");
        ToolResult result = tool.execute(add, context);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("First historical task", TodoWriteTool.getTodos(historicalId, workDir).get(0).subject);
        assertEquals(2, TodoWriteTool.getTodos(sessionId, workDir).size());
    }

    @Test
    void pathLikeIdsCannotUseOutsideFilesAsEvidenceOfARealTranscript() throws Exception {
        seedGuardedList();
        Files.writeString(workDir.resolve("outside.txt"), "Not a transcript");
        for (String id : List.of("../../outside", "..\\outside", "bad\0id")) {
            ObjectNode add = om.createObjectNode().put("action", "add").put("session_id", id)
                    .put("subject", "Must not create a list");
            assertRejectedUnchanged(add);
            assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(id)));
        }
    }

    @Test
    void safeSetRetainsIdsAndOmittedDetailsWhileAppendingGeneratedIds() throws Exception {
        seedGuardedList();
        ToolResult result = tool.execute(om.readTree("""
                {"action":"set","todos":[
                  {"id":"first","content":"Updated first"},
                  {"id":"second","subject":"Keep second"},
                  {"subject":"Append without invented ID"},"Also append"]}
                """), context);
        assertFalse(result.isError(), result.getOutput());
        List<TodoWriteTool.TodoItem> tasks = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals(4, tasks.size());
        assertEquals(4, tasks.stream().map(item -> item.id).distinct().count());
        assertEquals("Updated first", tasks.get(0).subject);
        assertEquals("Important details", tasks.get(0).description);
        assertEquals("in_progress", tasks.get(0).status);
        assertEquals("high", tasks.get(0).priority);
        assertEquals("completed", tasks.get(1).status);
    }

    @Test
    void initialSetReservesExplicitIdsBeforeAssigningNewOnes() throws Exception {
        ToolResult result = tool.execute(om.readTree("""
                {"action":"set","todos":["Generated",{"id":"1","subject":"Explicit"},
                  {"id":"2147483647","subject":"Large numeric ID"}]}
                """), context);
        assertFalse(result.isError(), result.getOutput());
        var tasks = TodoWriteTool.getTodos(sessionId, workDir);
        assertEquals("2147483648", tasks.get(0).id);
        assertEquals(3, tasks.stream().map(item -> item.id).distinct().count());
    }

    @Test
    void malformedInitialSetDoesNotCreateSnapshot() throws Exception {
        ToolResult result = tool.execute(om.readTree("""
                {"action":"set","todos":[{"id":"1","subject":"Valid"},{"id":"1","subject":"Duplicate"}]}
                """), context);
        assertTrue(result.isError());
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(sessionId)));
    }

    @Test
    void corruptPersistedStateCannotBeOverwritten() throws Exception {
        seedGuardedList();
        Path file = TodoWriteTool.sessionTodoPath(sessionId);
        for (String corrupt : List.of("not json", "", "null", "{}", "{\"todos\":[{}]}",
                "{\"todos\":[{\"id\":\"1\",\"subject\":\"A\"},{\"id\":\"1\",\"subject\":\"B\"}]}")) {
            Files.writeString(file, corrupt);
            ToolResult result = tool.execute(om.readTree("{\"action\":\"add\",\"subject\":\"Do not overwrite\"}"), context);
            assertTrue(result.isError(), corrupt);
            assertEquals(corrupt, Files.readString(file));
        }
    }

    @Test
    void staleFullSnapshotCannotDropAConcurrentAppend() throws Exception {
        seedGuardedList();
        ObjectNode stale = om.createObjectNode().put("action", "set");
        stale.set("todos", om.readTree(Files.readString(TodoWriteTool.sessionTodoPath(sessionId))).get("todos"));
        assertFalse(tool.execute(om.readTree("{\"action\":\"add\",\"subject\":\"Concurrent addition\"}"), context).isError());
        assertRejectedUnchanged(stale);
    }

    @Test
    void explicitEmptyDescriptionAndDeleteRemainSupported() throws Exception {
        seedGuardedList();
        assertFalse(tool.execute(om.readTree("""
                {"action":"update","task_id":"first","task_description":""}
                """), context).isError());
        assertEquals("", TodoWriteTool.getTodos(sessionId, workDir).get(0).description);
        assertFalse(tool.execute(om.readTree("{\"action\":\"delete\",\"task_id\":\"second\"}"), context).isError());
        assertEquals(1, TodoWriteTool.getTodos(sessionId, workDir).size());
    }

    @Test
    void schemaAndHintsAdvertiseGuardrails() {
        JsonNode schema = tool.parameterSchema();
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(4, schema.path("properties").path("action").path("enum").size());
        assertTrue(schema.path("properties").path("todos").has("items"));
        assertTrue(tool.description().contains("never invent"));
        assertTrue(tool.compactHint().contains("retain all existing IDs"));
    }

    @Test
    void testUpdateSubjectAndPriority() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Original subject");
        addParams.put("priority", "low");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId, workDir).get(0).id;

        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("subject", "Updated subject");
        updateParams.put("priority", "high");

        tool.execute(updateParams, context);

        TodoWriteTool.TodoItem item = TodoWriteTool.getTodos(sessionId, workDir).get(0);
        assertEquals("Updated subject", item.subject);
        assertEquals("high", item.priority);
    }
}
