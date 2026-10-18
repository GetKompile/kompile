package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
import ai.kompile.cli.main.chat.activity.*;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("user.home")
class TodoTimelineLifecycleTest {
    @TempDir Path root;
    private String previousHome;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private ToolContext context;
    private ActivityStorage storage;
    private TimelineLifecycle runtime;

    @BeforeEach void setup() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", root.toString());
        context = new ToolContext("todo-owner", AgentConfig.builder("coder").enabledTools(Set.of("*")).build(),
                new PermissionService(), root, new ToolRegistry(mapper));
        storage = new ActivityStorage();
        runtime = new TimelineLifecycle(new ConversationActivityService(storage, List.of()),
                "todo-owner", root, false);
    }

    @AfterEach void cleanup() {
        runtime.close();
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    private ObjectNode request(String action) { return mapper.createObjectNode().put("action", action); }
    private List<ActivityEvent> changes(ActivityIdentity identity) throws Exception {
        return new ActivityJournalReader(storage.eventsPath(identity)).read(ActivityReadBudget.DEFAULT).events()
                .stream().filter(event -> event.eventType().equals("todo.changed")).toList();
    }

    @Test void snapshotSuccessEmitsStatusTransitionsNotRawTaskText() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        assertFalse(tool.execute(request("add").put("subject", "private task body"), context).isError());
        assertFalse(tool.execute(request("update").put("task_id", "1").put("status", "completed"), context).isError());
        assertFalse(tool.execute(request("delete").put("task_id", "1"), context).isError());
        List<ActivityEvent> events = changes(runtime.identity());
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(event -> event.source().available()));
        assertEquals("pending", events.get(0).attributes().get("after"));
        assertEquals("pending", events.get(1).attributes().get("before"));
        assertEquals("completed", events.get(1).attributes().get("after"));
        assertEquals("absent", events.get(2).attributes().get("after"));
        assertTrue(events.stream().allMatch(event -> event.runId().equals(runtime.identity().runId())));
        assertTrue(events.stream().allMatch(event -> event.attributes().get("basis").equals("snapshot-saved")));
        assertFalse(mapper.writeValueAsString(events).contains("private task body"));
        assertTrue(TodoWriteTool.getTodos(context).isEmpty());
    }

    @Test void invalidActionAndFailedSnapshotCannotEmitTransition() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        assertTrue(tool.execute(request("update").put("task_id", "missing"), context).isError());
        Path snapshot = TodoWriteTool.sessionTodoPath("todo-owner");
        Files.createDirectories(snapshot);
        assertTrue(tool.execute(request("add").put("subject", "cannot save"), context).isError());
        assertTrue(changes(runtime.identity()).isEmpty());
    }

    @Test void rejectedMutationDoesNotEmitATransitionOrChangeSnapshot() throws Exception {
        TodoWriteTool tool = new TodoWriteTool();
        assertFalse(tool.execute(request("add").put("subject", "Keep task"), context).isError());
        Path snapshot = TodoWriteTool.sessionTodoPath("todo-owner");
        byte[] before = Files.readAllBytes(snapshot);
        assertTrue(tool.execute(request("update").put("task_id", "1")
                .put("status", "completed").put("priority", "urgent"), context).isError());
        assertTrue(tool.execute(request("set").set("todos", mapper.createArrayNode()), context).isError());
        assertArrayEquals(before, Files.readAllBytes(snapshot));
        assertEquals(1, changes(runtime.identity()).size());
    }

    @Test void explicitTargetKeepsItsSnapshotAndStripsCallerRun() throws Exception {
        ToolContext target = new ToolContext("target-history", context.getAgent(), context.getPermissionService(),
                root, new ToolRegistry(mapper));
        ObjectNode initial = request("set");
        initial.putArray("todos");
        assertFalse(new TodoWriteTool().execute(initial, target).isError());
        assertFalse(new TodoWriteTool().execute(request("add").put("subject", "historical task")
                .put("session_id", "target-history"), context).isError());
        assertTrue(TodoWriteTool.getTodos(context).isEmpty());
        assertEquals(1, TodoWriteTool.getTodos("target-history", root).size());
        List<ActivityEvent> events = changes(ActivityIdentity.conversation("target-history", root));
        assertEquals(1, events.size());
        assertEquals("target-history", events.get(0).identity().conversationId());
        assertEquals("", events.get(0).runId());
        assertEquals("", events.get(0).actorId());
        assertTrue(changes(runtime.identity()).isEmpty());
    }
}
