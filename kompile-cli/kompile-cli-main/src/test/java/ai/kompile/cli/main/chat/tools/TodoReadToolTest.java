package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("user.home")
class TodoReadToolTest {

    /** Fresh project root per test; session-scoped todo files live beneath its managed memory. */
    @TempDir
    Path workDir;

    private TodoReadTool readTool;
    private TodoWriteTool writeTool;
    private ToolContext context;
    private ObjectMapper om;
    private String sessionId;
    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", workDir.toString());
        readTool = new TodoReadTool();
        writeTool = new TodoWriteTool();
        om = new ObjectMapper();
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
        assertEquals("todoread", readTool.id());
        assertNotNull(readTool.description());
        assertTrue(readTool.compactHint().contains("session_id"));
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
    void testHistoricalSessionLookupDoesNotChangeDefaultSession() throws Exception {
        String oldSessionId = "old-" + UUID.randomUUID();
        ToolContext oldContext = new ToolContext(oldSessionId, context.getAgent(),
                context.getPermissionService(), workDir, new ToolRegistry(om));
        ObjectNode oldTask = om.createObjectNode();
        oldTask.put("action", "add");
        oldTask.put("subject", "Task from an older chat");
        writeTool.execute(oldTask, oldContext);

        ToolResult current = readTool.execute(om.createObjectNode(), context);
        assertTrue(current.getOutput().contains("No tasks"));
        assertFalse(current.getOutput().contains("Task from an older chat"));

        ObjectNode lookup = om.createObjectNode();
        lookup.put("session_id", oldSessionId);
        ToolResult historical = readTool.execute(lookup, context);
        assertFalse(historical.isError());
        assertTrue(historical.getOutput().contains("Task from an older chat"));
        assertEquals(oldSessionId, historical.getMetadata().get("sessionId"));

        ToolResult currentAgain = readTool.execute(om.createObjectNode(), context);
        assertTrue(currentAgain.getOutput().contains("No tasks"));
    }

    @Test
    void testParameterSchemaOffersHistoricalSessionLookup() {
        var schema = readTool.parameterSchema();
        assertTrue(schema.has("properties"));
        assertEquals(1, schema.path("properties").size());
        assertTrue(schema.path("properties").has("session_id"));
    }

    @Test
    void allActionsCarryTargetSessionSnapshotsAndRetainedResultsDoNotChange() throws Exception {
        String target = "other-session";
        // A real historical transcript can receive its first tasks without a todo snapshot.
        Path transcript = new ChatHistory(target).getTranscriptFile();
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "Existing historical conversation");
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(target)));
        var renderer = new ai.kompile.cli.main.chat.render.TerminalRenderer(false);
        ObjectNode params = om.createObjectNode();
        params.put("session_id", target);
        params.put("action", "set");
        params.putArray("todos").addObject().put("id", "1").put("subject", "Target task");
        ToolResult set = writeTool.execute(params, context);
        assertEquals(target, set.getMetadata().get("sessionId"));
        assertTrue(renderer.renderToolResultDetail("todowrite", params.toString(), set).contains("○ #1 Target task"));

        params.remove("todos");
        params.put("action", "update");
        params.put("task_id", "1");
        params.put("status", "in_progress");
        ToolResult update = writeTool.execute(params, context);
        assertTrue(renderer.renderToolResultDetail("todowrite", params.toString(), update)
                .contains("▶ #1 Target task [current]"));
        assertTrue(renderer.renderToolResultDetail("todowrite", "{}", set).contains("○ #1 Target task"),
                "Later updates must not mutate an earlier result snapshot");

        params.put("action", "add");
        params.remove("task_id");
        params.put("subject", "Another task");
        params.put("status", "cancelled");
        ToolResult add = writeTool.execute(params, context);
        assertTrue(renderer.renderToolResultDetail("todowrite", "{}", add).contains("✗ #2 Another task"));
        ObjectNode lookup = om.createObjectNode().put("session_id", target);
        ToolResult read = readTool.execute(lookup, context);
        assertEquals(renderer.renderToolResultDetail("todowrite", "{}", add),
                renderer.renderToolResultDetail("todoread", "{}", read));
        assertEquals("  No tasks", renderer.renderToolResultDetail("todoread", "{}",
                readTool.execute(om.createObjectNode(), context)), "Current session must stay separate");

        params.put("action", "delete");
        params.remove(List.of("subject", "status"));
        params.put("task_id", "1");
        ToolResult delete = writeTool.execute(params, context);
        String afterDelete = renderer.renderToolResultDetail("todowrite", "{}", delete);
        assertFalse(afterDelete.contains("Target task"));
        assertTrue(afterDelete.contains("Another task"));
        params.put("task_id", "2");
        ToolResult cleared = writeTool.execute(params, context);
        assertEquals("  No tasks", renderer.renderToolResultDetail("todowrite", "{}", cleared));
        assertEquals("  No tasks", renderer.renderToolResultDetail("todoread", "{}", readTool.execute(lookup, context)));
        ToolResult failed = writeTool.execute(params, context);
        assertTrue(failed.isError());
        assertFalse(failed.getMetadata().containsKey("todos"));
    }

    private void addTask(String subject) throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", subject);
        writeTool.execute(params, context);
    }
}
