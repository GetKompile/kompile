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

        ObjectNode readParams = om.createObjectNode().put("include_details", true);
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
        assertTrue(schema.path("properties").has("session_id"));
        for (String field : List.of("query", "status", "priority", "task_id", "sort_by", "sort_dir", "offset", "limit", "include_details")) {
            assertTrue(schema.path("properties").has(field), field);
        }
        assertEquals(20, schema.path("properties").path("limit").path("default").asInt());
        assertEquals(100, schema.path("properties").path("limit").path("maximum").asInt());
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
        ObjectNode lookup = om.createObjectNode().put("session_id", target).put("include_details", true);
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

    @Test
    void pagesAreBoundedInTextAndSnapshotsAndDoNotMutateStoredTasks() throws Exception {
        ObjectNode set = om.createObjectNode().put("action", "set");
        var tasks = set.putArray("todos");
        for (int i = 1; i <= 25; i++) tasks.addObject().put("id", String.valueOf(i))
                .put("subject", "Task " + i).put("description", "verbose private details " + i);
        assertFalse(writeTool.execute(set, context).isError());
        ToolResult first = readTool.execute(om.createObjectNode(), context);
        var page = om.valueToTree(first.getMetadata().get("todos"));
        assertEquals(25, first.getMetadata().get("total"));
        assertEquals(25, first.getMetadata().get("matched"));
        assertEquals(20, first.getMetadata().get("returned"));
        assertEquals(20, first.getMetadata().get("nextOffset"));
        assertEquals(true, first.getMetadata().get("hasMore"));
        assertEquals(20, page.size());
        assertFalse(first.getOutput().contains("Task 21"));
        assertFalse(page.toString().contains("verbose private details"));
        assertFalse(first.getOutput().contains("verbose private details"));
        ToolResult second = readTool.execute(om.createObjectNode().put("offset", 20), context);
        assertEquals(5, second.getMetadata().get("returned"));
        assertEquals(false, second.getMetadata().get("hasMore"));
        assertFalse(second.getMetadata().containsKey("nextOffset"));
        assertEquals("21", om.valueToTree(second.getMetadata().get("todos")).get(0).path("id").asText());
        assertEquals(25, TodoWriteTool.getTodos(context).size());
        assertEquals("verbose private details 1", TodoWriteTool.getTodos(context).get(0).description);
        String rendered = new ai.kompile.cli.main.chat.render.TerminalRenderer(false)
                .renderToolResultDetail("todoread", "{}", second);
        assertTrue(rendered.contains("#21"));
        assertFalse(rendered.contains("#1 "));
        assertTrue(rendered.contains("Showing 21-25 of 25 matching (25 total)"));
        String renderedFirst = new ai.kompile.cli.main.chat.render.TerminalRenderer(false)
                .renderToolResultDetail("todoread", "{}", first);
        assertTrue(renderedFirst.contains("More: offset=20 limit=20"));
    }

    @Test
    void combinesSearchStatusPriorityAndExactIdBeforePaging() throws Exception {
        ObjectNode set = om.createObjectNode().put("action", "set");
        var tasks = set.putArray("todos");
        tasks.addObject().put("id", "1").put("subject", "unrelated").put("description", "Needle detail")
                .put("status", "in_progress").put("priority", "high");
        tasks.addObject().put("id", "2").put("subject", "needle done").put("status", "completed").put("priority", "high");
        tasks.addObject().put("id", "3").put("subject", "needle low").put("status", "in_progress").put("priority", "low");
        writeTool.execute(set, context);
        ToolResult result = readTool.execute(om.createObjectNode().put("query", "NEEDLE")
                .put("status", "in_progress").put("priority", "high").put("task_id", "1").put("limit", 1), context);
        assertFalse(result.isError(), result::getOutput);
        assertEquals(3, result.getMetadata().get("total"));
        assertEquals(1, result.getMetadata().get("matched"));
        assertEquals("1", om.valueToTree(result.getMetadata().get("todos")).get(0).path("id").asText());
        assertFalse(result.getOutput().contains("needle done"));
        assertFalse(result.getOutput().contains("Needle detail")); // searchable without verbose output
    }

    @Test
    void numericIdsPriorityTiesAndPositionSortAreDeterministic() throws Exception {
        ObjectNode set = om.createObjectNode().put("action", "set");
        var tasks = set.putArray("todos");
        tasks.addObject().put("id", "10").put("subject", "Zebra").put("priority", "high");
        tasks.addObject().put("id", "2").put("subject", "alpha").put("priority", "high");
        tasks.addObject().put("id", "custom").put("subject", "Beta").put("priority", "low");
        writeTool.execute(set, context);
        assertPageIds(om.createObjectNode().put("sort_by", "id"), "2", "10", "custom");
        assertPageIds(om.createObjectNode().put("sort_by", "id").put("sort_dir", "desc"), "custom", "10", "2");
        assertPageIds(om.createObjectNode().put("sort_by", "subject"), "2", "custom", "10");
        assertPageIds(om.createObjectNode().put("sort_by", "priority"), "2", "10", "custom");
        assertPageIds(om.createObjectNode().put("sort_dir", "desc"), "custom", "2", "10");
        assertPageIds(om.createObjectNode(), "10", "2", "custom"); // reads never reorder storage
    }

    @Test
    void cancelledNoMatchAndOutOfRangePagesHaveAccurateMetadata() throws Exception {
        addTask("Cancelled task");
        writeTool.execute(om.createObjectNode().put("action", "update").put("task_id", "1").put("status", "cancelled"), context);
        ToolResult cancelled = readTool.execute(om.createObjectNode().put("status", "cancelled"), context);
        assertTrue(cancelled.getOutput().contains("[-]"));
        assertEquals(0, cancelled.getMetadata().get("pending"));
        assertEquals(1, cancelled.getMetadata().get("cancelled"));
        ToolResult absent = readTool.execute(om.createObjectNode().put("query", "not present"), context);
        assertEquals(0, absent.getMetadata().get("matched"));
        assertTrue(absent.getOutput().contains("No tasks match"));
        ToolResult beyond = readTool.execute(om.createObjectNode().put("offset", Integer.MAX_VALUE), context);
        assertEquals(1, beyond.getMetadata().get("matched"));
        assertEquals(0, beyond.getMetadata().get("returned"));
        assertEquals(false, beyond.getMetadata().get("hasMore"));
        assertEquals(0, om.valueToTree(beyond.getMetadata().get("todos")).size());
    }

    @Test
    void invalidQueryInputsAreActionableErrorsEvenWithAnEmptyList() throws Exception {
        for (String json : List.of("{\"limit\":0}", "{\"limit\":101}", "{\"offset\":-1}",
                "{\"offset\":2147483648}", "{\"limit\":1.5}", "{\"limit\":\"2\"}", "{\"query\":[]}",
                "{\"status\":\"bogus\"}", "{\"priority\":\"urgent\"}", "{\"sort_by\":\"bogus\"}",
                "{\"sort_dir\":\"up\"}", "{\"include_details\":\"yes\"}")) {
            ToolResult result = readTool.execute(om.readTree(json), context);
            assertTrue(result.isError(), json);
            assertFalse(result.getOutput().isBlank(), json);
        }
    }

    @Test
    void timestampsSortByInstantAndLegacyDatesRemainReadable() throws Exception {
        ObjectNode persisted = om.createObjectNode();
        var tasks = persisted.putArray("todos");
        tasks.addObject().put("id", "legacy").put("subject", "Legacy task");
        tasks.addObject().put("id", "2").put("subject", "Earlier instant")
                .put("created_at", "2026-10-01T11:00:00+01:00").put("updated_at", "2026-10-01T11:00:00+01:00");
        tasks.addObject().put("id", "3").put("subject", "Later instant")
                .put("created_at", "2026-10-01T10:30:00Z").put("updated_at", "2026-10-01T10:30:00Z");
        Path snapshot = TodoWriteTool.sessionTodoPath(sessionId);
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, persisted.toString());
        assertPageIds(om.createObjectNode().put("sort_by", "created_at"), "legacy", "2", "3");
        assertPageIds(om.createObjectNode().put("sort_by", "updated_at").put("sort_dir", "desc"), "3", "2", "legacy");
        ToolResult details = readTool.execute(om.createObjectNode().put("task_id", "2").put("include_details", true), context);
        assertTrue(details.getOutput().contains("created:"));
        ToolResult emptyPage = readTool.execute(om.createObjectNode().put("offset", 100), context);
        String rendered = new ai.kompile.cli.main.chat.render.TerminalRenderer(false)
                .renderToolResultDetail("todoread", "{}", emptyPage);
        assertTrue(rendered.contains("No tasks on this page"));
        assertTrue(rendered.contains("0 of 3 matching (3 total)"));
    }

    private void assertPageIds(ObjectNode params, String... expected) throws Exception {
        ToolResult result = readTool.execute(params, context);
        assertFalse(result.isError(), result::getOutput);
        var nodes = om.valueToTree(result.getMetadata().get("todos"));
        var ids = new java.util.ArrayList<String>();
        nodes.forEach(node -> ids.add(node.path("id").asText()));
        assertEquals(List.of(expected), ids);
    }

    private void addTask(String subject) throws Exception {
        ObjectNode params = om.createObjectNode();
        params.put("action", "add");
        params.put("subject", subject);
        writeTool.execute(params, context);
    }
}
