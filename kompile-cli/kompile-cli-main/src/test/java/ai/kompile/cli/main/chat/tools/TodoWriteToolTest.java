package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

    /** Fresh per test — todo state persists to <workDir>/.kompile/memory, and a shared
     *  working directory leaks persisted todos into every later session's first load. */
    @TempDir
    Path workDir;

    private TodoWriteTool tool;
    private ToolContext context;
    private ObjectMapper om;
    private String sessionId;

    @BeforeEach
    void setUp() {
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

    @Test
    void testIdAndDescription() {
        assertEquals("todowrite", tool.id());
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("todo"));
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

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
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

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        assertEquals(1, todos.size());
        assertEquals("high", todos.get(0).priority);
    }

    @Test
    void testAddTaskInvalidPriorityDefaultsToMedium() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Some task");
        params.put("priority", "urgent");

        tool.execute(params, context);

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
        assertEquals("medium", todos.get(0).priority);
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

        String taskId = TodoWriteTool.getTodos(sessionId).get(0).id;

        // Update status
        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("status", "in_progress");

        ToolResult result = tool.execute(updateParams, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("pending → in_progress"));
        assertEquals("in_progress", TodoWriteTool.getTodos(sessionId).get(0).status);
    }

    @Test
    void testUpdateTaskCompleted() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Task to complete");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId).get(0).id;

        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("status", "completed");

        tool.execute(updateParams, context);
        assertEquals("completed", TodoWriteTool.getTodos(sessionId).get(0).status);
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

        String taskId = TodoWriteTool.getTodos(sessionId).get(0).id;

        ObjectNode deleteParams = om.createObjectNode();
        deleteParams.put("action", "delete");
        deleteParams.put("task_id", taskId);

        ToolResult result = tool.execute(deleteParams, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("Deleted"));
        assertTrue(TodoWriteTool.getTodos(sessionId).isEmpty());
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

        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
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

        // Different session should be empty
        String otherSession = "other-" + UUID.randomUUID();
        assertTrue(TodoWriteTool.getTodos(otherSession).isEmpty());
        assertEquals(1, TodoWriteTool.getTodos(sessionId).size());
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
        List<TodoWriteTool.TodoItem> todos = TodoWriteTool.getTodos(sessionId);
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
    void testSetPersistsTodosToProjectMemory(@TempDir Path dir) throws Exception {
        ToolContext isolated = new ToolContext("persist-" + UUID.randomUUID(), context.getAgent(),
                context.getPermissionService(), dir, new ToolRegistry(om));

        ObjectNode params = om.createObjectNode();
        params.put("action", "set");
        ObjectNode item = params.putArray("todos").addObject();
        item.put("subject", "Persisted task");
        item.put("status", "in_progress");
        item.put("priority", "high");

        ToolResult result = tool.execute(params, isolated);
        assertFalse(result.isError());

        Path file = dir.resolve(".kompile").resolve("memory").resolve("project-todos.json.md");
        assertTrue(Files.isRegularFile(file));
        JsonNode root = om.readTree(file.toFile());
        assertEquals(1, root.path("todos").size());
        JsonNode persisted = root.path("todos").get(0);
        assertEquals("Persisted task", persisted.path("subject").asText());
        assertEquals("in_progress", persisted.path("status").asText());
        assertEquals("high", persisted.path("priority").asText());
    }

    @Test
    void testUpdateSubjectAndPriority() throws Exception {
        ObjectNode addParams = om.createObjectNode();
        addParams.put("action", "add");
        addParams.put("subject", "Original subject");
        addParams.put("priority", "low");
        tool.execute(addParams, context);

        String taskId = TodoWriteTool.getTodos(sessionId).get(0).id;

        ObjectNode updateParams = om.createObjectNode();
        updateParams.put("action", "update");
        updateParams.put("task_id", taskId);
        updateParams.put("subject", "Updated subject");
        updateParams.put("priority", "high");

        tool.execute(updateParams, context);

        TodoWriteTool.TodoItem item = TodoWriteTool.getTodos(sessionId).get(0);
        assertEquals("Updated subject", item.subject);
        assertEquals("high", item.priority);
    }
}
