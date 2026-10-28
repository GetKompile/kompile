package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.ProcessCoordEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessListQueryTest {
    @TempDir Path tempDir;
    private final ObjectMapper om = JsonUtils.standardMapper();
    private BackgroundProcessManager manager;
    private ProcessManagementTool tool;

    @BeforeEach
    void setUp() {
        manager = new BackgroundProcessManager("list-query-session", tempDir);
        tool = new ProcessManagementTool(manager);
    }

    @AfterEach
    void tearDown() { manager.close(); }

    @Test
    void schemaExposesDiscoverableQueryControls() {
        var props = tool.parameterSchema().path("properties");
        for (String key : List.of("query", "status", "scope", "kind", "sort_by", "sort_dir", "offset", "limit", "include_details")) {
            assertTrue(props.has(key), key);
        }
        assertEquals(20, props.path("limit").path("default").asInt());
        assertEquals(100, props.path("limit").path("maximum").asInt());
        assertEquals("start_time", props.path("sort_by").path("default").asText());
        assertEquals("desc", props.path("sort_dir").path("default").asText());
    }

    @Test
    void compactDefaultHasBoundedPagesWithoutLogsOrFullListMetadata() throws Exception {
        var ids = new java.util.ArrayList<String>();
        for (int i = 1; i <= 25; i++) ids.add(manager.registerVirtual(COMMAND, "command " + i, "description " + i, Map.of()).getId());
        ids.sort(String::compareTo);
        ToolResult first = list(om.createObjectNode().put("sort_by", "id").put("sort_dir", "asc"));
        assertEquals(25, first.getMetadata().get("total"));
        assertEquals(25, first.getMetadata().get("matched"));
        assertEquals(20, first.getMetadata().get("returned"));
        assertEquals(20, first.getMetadata().get("nextOffset"));
        assertEquals(true, first.getMetadata().get("hasMore"));
        assertTrue(first.getOutput().contains(ids.get(0)), first::getOutput);
        assertFalse(first.getOutput().contains(ids.get(20)));
        assertFalse(first.getOutput().contains("recent output:"));
        ToolResult second = list(om.createObjectNode().put("sort_by", "id").put("sort_dir", "asc").put("offset", 20));
        assertEquals(5, second.getMetadata().get("returned"));
        assertEquals(false, second.getMetadata().get("hasMore"));
        assertFalse(second.getMetadata().containsKey("nextOffset"));
        assertFalse(second.getOutput().contains(ids.get(0)));
        assertTrue(second.getOutput().contains(ids.get(20)));
        assertEquals(25, manager.listAll().size());
    }

    @Test
    void combinesCaseInsensitiveDescriptionSearchWithStateKindAndScopeBeforePaging() throws Exception {
        var first = manager.registerVirtual(COMMAND, "builder", "Needle CPU build", Map.of());
        var done = manager.registerVirtual(COMMAND, "builder", "Needle done", Map.of());
        manager.complete(done.getId());
        manager.registerVirtual(JUDGE, "builder", "Needle judge", Map.of());
        ToolResult result = list(om.createObjectNode().put("query", "NEEDLE")
                .put("status", "RUNNING").put("kind", "command").put("scope", "local").put("limit", 1));
        assertEquals(3, result.getMetadata().get("total"));
        assertEquals(1, result.getMetadata().get("matched"));
        assertTrue(result.getOutput().contains(first.getId()));
        assertFalse(result.getOutput().contains(done.getId()));
        assertFalse(result.getOutput().contains("judge-"));
        assertEquals(1, list(om.createObjectNode().put("status", "completed")).getMetadata().get("matched"));
    }

    @Test
    void sortsBeforePagingAndReadsLogsOnlyWhenRequested() throws Exception {
        var zebra = manager.registerVirtual(COMMAND, "opaque command", "Zebra", Map.of("detail", "secret metadata"));
        var alpha = manager.registerVirtual(COMMAND, "another opaque command", "alpha", Map.of());
        Files.writeString(zebra.getOutputFile(), "diagnostic log marker\n");
        ToolResult sorted = list(om.createObjectNode().put("sort_by", "description").put("sort_dir", "asc").put("limit", 1));
        assertTrue(sorted.getOutput().contains(alpha.getId()));
        assertFalse(sorted.getOutput().contains(zebra.getId()));
        ToolResult compact = list(om.createObjectNode().put("query", zebra.getId()));
        assertFalse(compact.getOutput().contains("diagnostic log marker"));
        assertFalse(compact.getOutput().contains("secret metadata"));
        ToolResult detailed = list(om.createObjectNode().put("query", zebra.getId()).put("include_details", true));
        assertTrue(detailed.getOutput().contains("diagnostic log marker"));
        assertTrue(detailed.getOutput().contains("opaque command"));
        assertTrue(detailed.getOutput().contains("secret metadata"));
    }

    @Test
    void sharedMirrorsAreDeduplicatedBeforeScopeAndQueryFilters() throws Exception {
        var local = manager.registerVirtual(COMMAND, "local command", "Local", Map.of());
        var mirror = manager.registerVirtual(SHARED, "remote command", "Mirrored", Map.of(
                "ownerSessionId", "owner-a", "ownerAgent", "other-agent", "sharedProcessId", "proc-remote"));
        var mirroredCoordination = shared("proc-remote", "owner-a", "Mirrored");
        // IDs can collide across owners, so dedup must use owner + process ID, not bare ID.
        var anotherOwner = shared("proc-remote", "owner-b", "Distinct remote");
        var localCoordination = shared(local.getId(), "list-query-session", "Local publication");
        CoordinationStateManager coordinator = mock(CoordinationStateManager.class);
        when(coordinator.getSessionId()).thenReturn("list-query-session");
        when(coordinator.queryProcesses()).thenReturn(List.of(mirroredCoordination, anotherOwner, localCoordination));
        tool = new ProcessManagementTool(manager, coordinator);
        ToolResult all = list(om.createObjectNode());
        assertEquals(3, all.getMetadata().get("total"));
        assertEquals(2, all.getMetadata().get("shared"));
        ToolResult shared = list(om.createObjectNode().put("scope", "shared").put("kind", "shared"));
        assertEquals(2, shared.getMetadata().get("matched"));
        assertTrue(shared.getOutput().contains(mirror.getId()));
        assertTrue(shared.getOutput().contains("owner-b/proc-remote"));
        assertFalse(shared.getOutput().contains(local.getId()));
        ToolResult ownerSearch = list(om.createObjectNode().put("query", "OWNER-B"));
        assertEquals(1, ownerSearch.getMetadata().get("matched"));
        ToolResult localOnly = list(om.createObjectNode().put("scope", "local"));
        assertEquals(1, localOnly.getMetadata().get("matched"));
        assertFalse(localOnly.getOutput().contains(mirror.getId()));
    }

    @Test
    void emptyNoMatchAndOverflowOffsetsAreDistinctAndBounded() throws Exception {
        ToolResult empty = list(om.createObjectNode());
        assertTrue(empty.getOutput().contains("No tracked processes"));
        assertEquals(0, empty.getMetadata().get("total"));
        manager.registerVirtual(MCP, "log", "diagnostic", Map.of());
        ToolResult absent = list(om.createObjectNode().put("query", "not present"));
        assertEquals(0, absent.getMetadata().get("matched"));
        assertTrue(absent.getOutput().contains("No processes match"));
        ToolResult beyond = list(om.createObjectNode().put("offset", Integer.MAX_VALUE));
        assertEquals(1, beyond.getMetadata().get("matched"));
        assertEquals(0, beyond.getMetadata().get("returned"));
        assertEquals(false, beyond.getMetadata().get("hasMore"));
    }

    @Test
    void badInputsReturnHelpfulErrorsInsteadOfIgnoringFilters() throws Exception {
        for (String json : List.of("{\"limit\":0}", "{\"limit\":101}", "{\"offset\":-1}",
                "{\"offset\":2147483648}", "{\"limit\":1.5}", "{\"limit\":\"2\"}", "{\"query\":{}}",
                "{\"status\":\"bogus\"}", "{\"kind\":\"bogus\"}", "{\"scope\":\"bogus\"}",
                "{\"sort_by\":\"bogus\"}", "{\"sort_dir\":\"up\"}", "{\"include_details\":1}")) {
            var params = (ObjectNode) om.readTree(json);
            params.put("action", "list");
            ToolResult result = tool.execute(params, null);
            assertTrue(result.isError(), json);
            assertFalse(result.getOutput().isBlank(), json);
        }
    }

    @Test
    void sharedRunningAliasesTerminalStatesAndNewestFirstSorting() throws Exception {
        var active = shared("proc-active", "remote", "active process");
        active.setState("ACTIVE");
        active.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        var starting = shared("proc-starting", "remote", "starting process");
        starting.setState("STARTING");
        starting.setStartedAt(Instant.parse("2026-01-02T00:00:00Z"));
        var failed = shared("proc-failed", "remote", "failed process");
        failed.setState("failed");
        var killed = shared("proc-killed", "remote", "killed process");
        killed.setState("KILLED");
        CoordinationStateManager coordinator = mock(CoordinationStateManager.class);
        when(coordinator.getSessionId()).thenReturn("list-query-session");
        when(coordinator.queryProcesses()).thenReturn(List.of(active, failed, starting, killed));
        tool = new ProcessManagementTool(manager, coordinator);
        ToolResult running = list(om.createObjectNode().put("status", "running").put("limit", 1));
        assertEquals(2, running.getMetadata().get("matched"));
        assertTrue(running.getOutput().contains("remote/proc-starting"));
        assertFalse(running.getOutput().contains("remote/proc-active"));
        assertTrue(list(om.createObjectNode().put("status", "failed")).getOutput().contains("remote/proc-failed"));
        assertEquals(1, list(om.createObjectNode().put("status", "killed")).getMetadata().get("matched"));
    }

    private ToolResult list(ObjectNode params) throws Exception {
        params.put("action", "list");
        ToolResult result = tool.execute(params, null);
        assertFalse(result.isError(), result::getOutput);
        return result;
    }

    private ProcessCoordEntry shared(String id, String owner, String description) {
        return new ProcessCoordEntry(id, owner, "remote-agent", "remote command", description,
                0, "RUNNING", Instant.now(), null, 3600);
    }
}
