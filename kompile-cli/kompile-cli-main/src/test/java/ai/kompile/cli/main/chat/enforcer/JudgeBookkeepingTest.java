package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.TranscriptLogScope;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class JudgeBookkeepingTest {
    @TempDir Path directory;

    @Test
    void stateIsLiveAndSessionLocal() throws Exception {
        ToolContext context = context("bookkeeping-a");
        var mapper = JsonUtils.standardMapper();
        TodoWriteTool tool = new TodoWriteTool();
        assertFalse(tool.execute(mapper.readTree("""
                {"action":"set","session_id":"bookkeeping-a","todos":[
                  {"id":"1","subject":"Implement fix","status":"in_progress"},
                  {"id":"2","subject":"Validate","status":"pending"}]}
                """), context).isError());

        var state = mapper.readTree(JudgeBookkeeping.taskState("bookkeeping-a", directory));
        assertEquals(2, state.path("openTasks").asInt());
        assertEquals("in_progress", state.path("tasks").get(0).path("status").asText());
        assertFalse(JudgeBookkeeping.taskState("bookkeeping-b", directory).contains("Implement fix"));

        assertFalse(tool.execute(mapper.readTree("""
                {"action":"update","session_id":"bookkeeping-a","task_id":"1","status":"completed"}
                """), context).isError());
        state = mapper.readTree(JudgeBookkeeping.taskState("bookkeeping-a", directory));
        assertEquals(1, state.path("openTasks").asInt());
        assertEquals("completed", state.path("tasks").get(1).path("status").asText());
    }

    @Test
    void boundedProjectionRetainsOpenTasksAheadOfCompletedOnes() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var params = mapper.createObjectNode().put("action", "set").put("session_id", "many-tasks");
        var todos = params.putArray("todos");
        for (int i = 0; i < 50; i++) {
            todos.addObject().put("id", String.valueOf(i)).put("subject", "x".repeat(1000))
                    .put("status", i == 49 ? "in_progress" : "completed");
        }
        new TodoWriteTool().execute(params, context("many-tasks"));
        var state = mapper.readTree(JudgeBookkeeping.taskState("many-tasks", directory));
        assertEquals(50, state.path("totalTasks").asInt());
        assertEquals(10, state.path("omittedTasks").asInt());
        assertEquals(40, state.path("tasks").size());
        assertEquals("49", state.path("tasks").get(0).path("id").asText());
        assertTrue(state.path("tasks").get(0).path("subject").asText().length() < 200);
    }

    @Test
    void scopedStateDoesNotFollowAnotherTranscriptOrClosedOwner() throws Exception {
        try (var scope = TranscriptLogScope.openIsolated("scoped-tasks", directory, false);
             var binding = scope.bind()) {
            var mapper = JsonUtils.standardMapper();
            new TodoWriteTool().execute(mapper.readTree("""
                    {"action":"add","subject":"Scoped task","status":"pending"}
                    """), context("provider-session"));
            assertTrue(JudgeBookkeeping.currentTaskState().contains("Scoped task"));
            assertFalse(JudgeBookkeeping.taskState("provider-session", directory).contains("Scoped task"));
            scope.close();
            assertTrue(JudgeBookkeeping.currentTaskState().contains("unavailable"));
        }
    }

    @Test
    void missingIdentityIsUnknownRatherThanGlobalOrDefaultTasks() {
        assertTrue(JudgeBookkeeping.taskState(null, directory).contains("unavailable"));
        assertTrue(JudgeBookkeeping.taskState("", directory).contains("unavailable"));
    }

    private ToolContext context(String sessionId) {
        var mapper = JsonUtils.standardMapper();
        ToolContext context = new ToolContext(sessionId, AgentConfig.builder("coder").build(),
                new PermissionService(), directory, new ToolRegistry(mapper));
        context.setAutoApproveAll(true);
        return context;
    }
}
