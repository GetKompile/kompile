package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TodoReadToolTest {

    private TodoReadTool readTool;
    private TodoWriteTool writeTool;
    private ToolContext context;
    private ObjectMapper om;
    private String sessionId;

    @BeforeEach
    void setUp() {
        readTool = new TodoReadTool();
        writeTool = new TodoWriteTool();
        om = new ObjectMapper();
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
        assertEquals("todoread", readTool.id());
        assertNotNull(readTool.description());
    }

    @Test
    void testEmptyTodoList() throws Exception {
        ObjectNode params = om.createObjectNode();
        ToolResult result = readTool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("No tasks"));
    }

    @Test
    void testReadWithTasks() throws Exception {
        // Add some tasks
        addTask("First task");
        addTask("Second task");

        ObjectNode params = om.createObjectNode();
        ToolResult result = readTool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("First task"));
        assertTrue(result.getOutput().contains("Second task"));
        assertTrue(result.getOutput().contains("[ ]")); // pending status

        // Check metadata
        assertEquals(2, result.getMetadata().get("total"));
        assertEquals(2, result.getMetadata().get("pending"));
        assertEquals(0, result.getMetadata().get("completed"));
    }

    @Test
    void testReadWithMixedStatuses() throws Exception {
        addTask("Pending task");
        addTask("Done task");
        addTask("Working task");

        // Update second task to completed
        ObjectNode update1 = om.createObjectNode();
        update1.put("action", "update");
        update1.put("task_id", "2");
        update1.put("status", "completed");
        writeTool.execute(update1, context);

        // Update third task to in_progress
        ObjectNode update2 = om.createObjectNode();
        update2.put("action", "update");
        update2.put("task_id", "3");
        update2.put("status", "in_progress");
        writeTool.execute(update2, context);

        ObjectNode params = om.createObjectNode();
        ToolResult result = readTool.execute(params, context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("[x]")); // completed
        assertTrue(result.getOutput().contains("[~]")); // in_progress
        assertTrue(result.getOutput().contains("[ ]")); // pending

        assertEquals(3, result.getMetadata().get("total"));
        assertEquals(1, result.getMetadata().get("pending"));
        assertEquals(1, result.getMetadata().get("inProgress"));
        assertEquals(1, result.getMetadata().get("completed"));
    }

    @Test
    void testReadIncludesDescriptions() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", "Task with desc");
        params.put("task_description", "Detailed description here");
        writeTool.execute(params, context);

        ObjectNode readParams = om.createObjectNode();
        ToolResult result = readTool.execute(readParams, context);

        assertTrue(result.getOutput().contains("Detailed description here"));
    }

    @Test
    void testParameterSchemaIsEmpty() {
        // TodoRead takes no parameters
        var schema = readTool.parameterSchema();
        assertTrue(schema.has("properties"));
        assertEquals(0, schema.path("properties").size());
    }

    private void addTask(String subject) throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", subject);
        writeTool.execute(params, context);
    }
}
