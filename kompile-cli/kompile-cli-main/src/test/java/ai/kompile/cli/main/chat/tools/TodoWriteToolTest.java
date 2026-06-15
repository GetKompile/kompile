package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TodoWriteToolTest {

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
        context = new ToolContext(sessionId, agent, perms, Paths.get("."), registry);
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
