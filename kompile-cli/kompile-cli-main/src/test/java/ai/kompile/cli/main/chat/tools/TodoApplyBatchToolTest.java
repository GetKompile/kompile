package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.enforcer.JudgeToolPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ai.kompile.cli.main.chat.testing.TemporaryUserHome
class TodoApplyBatchToolTest {
    @TempDir Path workDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TodoApplyBatchTool tool = new TodoApplyBatchTool();
    private ToolContext context;
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "batch-" + java.util.UUID.randomUUID();
        context = context(sessionId);
    }

    private ToolContext context(String id) {
        return new ToolContext(id, AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                new PermissionService(), workDir, new ToolRegistry(mapper));
    }

    private ToolResult apply(String json) throws Exception {
        return tool.execute(mapper.readTree(json), context);
    }

    private void seed() throws Exception {
        assertFalse(new TodoWriteTool().execute(mapper.readTree("""
                {"action":"set","todos":[
                  {"id":"1","subject":"First","status":"in_progress","description":"Keep me","priority":"high"},
                  {"id":"2","subject":"Next"}, {"id":"3","subject":"Unrelated"}]}
                """), context).isError());
    }

    private List<TodoWriteTool.TodoItem> todos() {
        return TodoWriteTool.getTodos(sessionId, workDir);
    }

    @Test
    void schemaAndWorkflowDiscovery() {
        assertEquals("todo_apply_batch", tool.id());
        assertEquals("todowrite", tool.permissionKey());
        JsonNode schema = tool.parameterSchema();
        assertEquals("operations", schema.path("required").get(0).asText());
        JsonNode operations = schema.path("properties").path("operations");
        assertEquals(1, operations.path("minItems").asInt());
        assertFalse(operations.path("items").path("properties").has("session_id"));
        assertFalse(operations.path("items").path("properties").has("todos"));
        assertEquals(3, operations.path("items").path("properties").path("action").path("enum").size());
        ToolRegistry registry = ToolRegistryFactory.create(mapper, "", new ai.kompile.cli.main.chat.agent.AgentRegistry(),
                new PermissionService(), new ai.kompile.cli.main.chat.render.TerminalRenderer(false), null);
        assertNotNull(registry.get(tool.id()));
        registry.getDynamicToolManager().activateGroup("workflow");
        assertTrue(registry.getDynamicToolManager().getActiveToolIds().contains(tool.id()));
        assertTrue(JudgeToolPolicy.isRoutineSessionTool("mcp__kompile__todo_apply_batch"));
        assertTrue(JudgeToolPolicy.remindersMayGovernRoutineTools("Do not use todo_apply_batch"));
    }

    @Test
    void completesOneTaskAndStartsNextWithoutRewritingOtherFields() throws Exception {
        seed();
        ToolResult result = apply("""
                {"operations":[{"action":"update","task_id":"1","status":"completed"},
                               {"action":"update","task_id":"2","status":"in_progress"}]}
                """);
        assertFalse(result.isError(), result.getOutput());
        assertEquals(List.of("completed", "in_progress", "pending"), todos().stream().map(t -> t.status).toList());
        assertEquals("Keep me", todos().get(0).description);
        assertEquals("high", todos().get(0).priority);
        assertEquals("model", todos().get(0).updatedBy);
        assertNotNull(todos().get(0).updatedAt);
        assertNotNull(todos().get(0).createdAt);
        JsonNode snapshot = (JsonNode) result.getMetadata().get(TodoWriteTool.TASKS_METADATA);
        assertEquals(3, snapshot.size());
        assertEquals("completed", snapshot.get(0).path("status").asText());
    }

    @Test
    void mixedOperationsExecuteInOrderAndReturnGeneratedIds() throws Exception {
        seed();
        ToolResult result = apply("""
                {"operations":[{"action":"add","subject":"New","priority":"low"},
                  {"action":"update","task_id":"1","subject":"Renamed","task_description":""},
                  {"action":"update","task_id":"2","status":"in_progress"},
                  {"action":"update","task_id":"2","status":"completed"},
                  {"action":"delete","task_id":"3"}]}
                """);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("Added task #4: New"));
        assertEquals(List.of("1", "2", "4"), todos().stream().map(t -> t.id).toList());
        assertEquals("Renamed", todos().get(0).subject);
        assertEquals("", todos().get(0).description);
        assertEquals("completed", todos().get(1).status);
        assertEquals("low", todos().get(2).priority);
    }

    @Test
    void unknownIdRollsBackEarlierAddsUpdatesAndDeletes() throws Exception {
        seed();
        byte[] before = Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId));
        ToolResult result = apply("""
                {"operations":[{"action":"update","task_id":"1","status":"completed"},
                  {"action":"add","subject":"Must not persist"}, {"action":"delete","task_id":"2"},
                  {"action":"update","task_id":"invented","status":"completed"}]}
                """);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("operation 4"));
        assertFalse(result.getMetadata().containsKey(TodoWriteTool.TASKS_METADATA));
        assertArrayEquals(before, Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId)));
        assertEquals("in_progress", todos().get(0).status);
        assertEquals(3, todos().size());
    }

    @Test
    void deletingSameTaskTwiceRejectsWholeBatch() throws Exception {
        seed();
        byte[] before = Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId));
        assertTrue(apply("""
                {"operations":[{"action":"delete","task_id":"1"},{"action":"delete","task_id":"1"}]}
                """).isError());
        assertArrayEquals(before, Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId)));
    }

    @Test
    void malformedBatchesLeavePersistedBytesUnchanged() throws Exception {
        seed();
        byte[] before = Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId));
        for (String json : List.of("null", "[]", "{}", "{\"operations\":[]}",
                "{\"operations\":null}", "{\"operations\":{}}", "{\"operations\":[null]}",
                "{\"operations\":[\"update\"]}", "{\"operations\":[],\"unexpected\":true}",
                "{\"operations\":[{\"action\":\"set\",\"todos\":[]}]}",
                "{\"operations\":[{\"action\":\"add\",\"subject\":\"x\",\"session_id\":\"other\"}]}",
                "{\"operations\":[{\"action\":\"add\",\"subject\":\"x\"}],\"session_id\":null}")) {
            ToolResult result = apply(json);
            assertTrue(result.isError(), json);
            assertArrayEquals(before, Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId)), json);
        }
        for (String operation : List.of("{\"action\":\"add\"}",
                "{\"action\":\"update\",\"task_id\":\"1\",\"status\":\"done\"}",
                "{\"action\":\"update\",\"task_id\":\"1\",\"priority\":\"urgent\"}",
                "{\"action\":\"update\",\"task_id\":\"1\",\"subject\":\" \"}",
                "{\"action\":\"delete\",\"task_id\":1}",
                "{\"action\":\"update\",\"task_id\":\"1\",\"subject\":null}")) {
            ToolResult result = apply("{\"operations\":[{\"action\":\"add\",\"subject\":\"valid\"}," + operation + "]}");
            assertTrue(result.isError(), operation);
            assertTrue(result.getOutput().contains("operation 2"));
            assertArrayEquals(before, Files.readAllBytes(TodoWriteTool.sessionTodoPath(sessionId)));
        }
    }

    @Test
    void failedFirstBatchDoesNotCreateSnapshot() throws Exception {
        assertTrue(apply("""
                {"operations":[{"action":"add","subject":"Staged"},{"action":"delete","task_id":"unknown"}]}
                """).isError());
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(sessionId)));
        assertTrue(todos().isEmpty());
    }

    @Test
    void targetsExistingSessionAndRejectsUnknownTarget() throws Exception {
        seed();
        String other = "other-" + java.util.UUID.randomUUID();
        ToolContext otherContext = context(other);
        JsonNode add = mapper.readTree("{\"operations\":[{\"action\":\"add\",\"subject\":\"Other\"}]}");
        assertFalse(tool.execute(add, otherContext).isError());
        ToolResult result = apply("{\"session_id\":\"" + other
                + "\",\"operations\":[{\"action\":\"update\",\"task_id\":\"1\",\"status\":\"completed\"}]}");
        assertFalse(result.isError(), result.getOutput());
        assertEquals("completed", TodoWriteTool.getTodos(other, workDir).get(0).status);
        assertEquals("in_progress", todos().get(0).status);
        assertTrue(apply("{\"session_id\":\"unknown-" + java.util.UUID.randomUUID()
                + "\",\"operations\":[{\"action\":\"add\",\"subject\":\"Wrong session\"}]}").isError());
    }

    @Test
    void transcriptScopeOverridesToolContextSession() throws Exception {
        String transcript = "transcript-" + java.util.UUID.randomUUID();
        try (TranscriptLogScope ignored = TranscriptLogScope.open(transcript, workDir, false)) {
            assertFalse(apply("{\"operations\":[{\"action\":\"add\",\"subject\":\"Scoped\"}]}").isError());
        }
        assertEquals("Scoped", TodoWriteTool.getTodos(transcript, workDir).get(0).subject);
        assertTrue(todos().isEmpty());
    }

    @Test
    void batchHonorsExistingTodoWritePermissionDeny() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("todowrite", PermissionService.PermissionLevel.DENY);
        ToolContext denied = new ToolContext(sessionId,
                AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                permissions, workDir, new ToolRegistry(mapper));
        assertThrows(ToolExecutionException.class, () -> tool.execute(mapper.readTree(
                "{\"operations\":[{\"action\":\"add\",\"subject\":\"Denied\"}]}"), denied));
        assertFalse(Files.exists(TodoWriteTool.sessionTodoPath(sessionId)));
    }

    @Test
    void corruptSnapshotCannotBeOverwritten() throws Exception {
        seed();
        Path snapshot = TodoWriteTool.sessionTodoPath(sessionId);
        Files.writeString(snapshot, "broken json");
        assertTrue(apply("{\"operations\":[{\"action\":\"add\",\"subject\":\"No overwrite\"}]}").isError());
        assertEquals("broken json", Files.readString(snapshot));
    }

    @Test
    void resultSnapshotIsDetachedFromLaterBatches() throws Exception {
        ToolResult first = apply("{\"operations\":[{\"action\":\"add\",\"subject\":\"Task\"}]}");
        assertFalse(first.isError());
        assertFalse(apply("{\"operations\":[{\"action\":\"update\",\"task_id\":\"1\",\"status\":\"completed\"}]}").isError());
        assertEquals("pending", ((JsonNode) first.getMetadata().get(TodoWriteTool.TASKS_METADATA)).get(0).path("status").asText());
    }
}
