package ai.kompile.cli.main.lsp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.LspTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tool permission/validation/rendering only: never starts a server or touches an index. */
@Timeout(5)
class LspCallHierarchyToolTest {
    @TempDir Path tmp;

    private LspServerManager manager;
    private MockedStatic<LspServerManager> singleton;

    @BeforeEach void isolateManager() {
        manager = mock(LspServerManager.class);
        singleton = mockStatic(LspServerManager.class);
        singleton.when(LspServerManager::getInstance).thenReturn(manager);
    }

    @AfterEach void releaseSingletonMock() {
        if (singleton != null) {
            singleton.close();
        }
    }

    private ObjectNode params() {
        return JsonUtils.standardMapper().createObjectNode().put("action", "call_hierarchy")
                .put("file_path", "Calls.java").put("line", 1).put("column", 2);
    }

    private ToolContext context() {
        var context = new ToolContext("lsp-test", null, new PermissionService(), tmp, null);
        context.setAutoApproveAll(true);
        return context;
    }

    @Test void schemaExposesOptInOneHopAction() {
        var tool = new LspTool();
        var props = tool.parameterSchema().path("properties");
        assertTrue(props.path("action").path("enum").toString().contains("call_hierarchy"));
        assertEquals(1, props.path("max_depth").path("maximum").asInt());
        assertEquals(100, props.path("max_results").path("maximum").asInt());
        assertTrue(tool.description().contains("not exact runtime dispatch"));
        assertEquals("lsp", tool.permissionKey());
    }

    @Test void existingLspPermissionCheckedBeforeAnyResolutionOrServerStart() {
        AtomicReference<String> permission = new AtomicReference<>();
        var denied = new ToolContext("denied", null, new PermissionService(), tmp, null) {
            @Override public void checkPermission(String key, String description) throws ToolExecutionException {
                permission.set(key);
                throw new ToolExecutionException("denied", true);
            }
        };
        assertThrows(ToolExecutionException.class, () -> new LspTool().execute(params(), denied));
        assertEquals("lsp", permission.get());
        verifyNoInteractions(manager);
    }

    @Test void missingFileIsAnExplicitErrorBeforeServerResolution() {
        var error = assertThrows(ToolExecutionException.class, () -> new LspTool().execute(params(), context()));
        assertTrue(error.getMessage().contains("file not found"));
        verify(manager).setWorkingDirectory(tmp);
        verifyNoMoreInteractions(manager);
    }

    @Test void rejectsHeuristicSymbolAddressing() throws Exception {
        var p = params().put("symbol", "Overloaded.target");
        ToolResult result = new LspTool().execute(p, context());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("heuristics are not used"));
    }

    @Test void rejectsDepthLimitsTimeoutAndNonIntegralBoundsBeforeServerStart() throws Exception {
        for (String input : List.of(
                "{\"max_depth\":2}", "{\"max_depth\":0}", "{\"max_results\":0}",
                "{\"max_results\":101}", "{\"timeout_ms\":0}", "{\"timeout_ms\":30001}",
                "{\"timeout_ms\":1.5}", "{\"max_results\":\"20\"}", "{\"max_results\":null}",
                "{\"max_results\":9223372036854775807}", "{\"direction\":\"both\"}",
                "{\"direction\":\"\"}", "{\"line\":0}", "{\"column\":-1}")) {
            var p = params();
            p.setAll((ObjectNode) JsonUtils.standardMapper().readTree(input));
            assertTrue(new LspTool().execute(p, context()).isError(), input);
        }
        for (String missing : List.of("line", "column")) {
            var p = params();
            p.remove(missing);
            assertTrue(new LspTool().execute(p, context()).isError(), missing);
        }
    }

    @Test void incomingAndOutgoingLabelExactDeclarationsAndCallerRanges() throws Exception {
        var target = LspCallHierarchyTest.item("file:///Target.java", "target(int)", 2);
        var peer = LspCallHierarchyTest.item("jdt://library/Other.class", "target(String)", 8);
        Range range = new Range(new Position(10, 4), new Position(10, 12));
        var result = new LspServerConnection.CallHierarchyResult(List.of(target), 1,
                List.of(new LspServerConnection.CallHierarchyEdge(peer, List.of(range), 2)), 3);
        for (String direction : List.of("incoming", "outgoing")) {
            ToolResult rendered = render(result, direction);
            JsonNode json = JsonUtils.standardMapper().readTree(rendered.getOutput());
            assertFalse(rendered.isError());
            assertFalse(json.path("persisted").asBoolean());
            assertTrue(json.path("callsTruncated").asBoolean());
            var edge = json.path("calls").get(0);
            assertEquals(direction.equals("incoming") ? peer.getUri() : target.getUri(), edge.path("fromUri").asText());
            assertEquals(direction.equals("incoming") ? "target(int)" : "target(String)", edge.path("to").path("detail").asText());
            assertEquals(10, edge.path("fromRanges").get(0).path("start").path("line").asInt());
            assertEquals(12, edge.path("fromRanges").get(0).path("end").path("character").asInt());
            assertTrue(edge.path("rangesTruncated").asBoolean());
            assertEquals(5, json.path("declarations").get(0).path("selectionRange").path("start").path("character").asInt());
            assertEquals(5, json.path("declarations").get(0).path("range").path("end").path("line").asInt());
            assertFalse(rendered.getOutput().contains("\"token\""), "opaque protocol data must not leak into output");
        }
    }

    @Test void ambiguousChoiceIsAnErrorEvenWhenChoiceListIsCappedToOne() throws Exception {
        var item = LspCallHierarchyTest.item("file:///Target.java", "target(int)", 2);
        var result = render(new LspServerConnection.CallHierarchyResult(List.of(item), 2, List.of(), 0), "incoming");
        assertTrue(result.isError());
        var json = JsonUtils.standardMapper().readTree(result.getOutput());
        assertEquals("ambiguous", json.path("status").asText());
        assertTrue(json.path("declarationsTruncated").asBoolean());
        assertTrue(json.path("message").asText().contains("Choose an exact declaration"));
        assertEquals(0, json.path("calls").size());
    }

    @Test void noDeclarationIsNotProofOfNoCalls() throws Exception {
        var result = render(new LspServerConnection.CallHierarchyResult(List.of(), 0, List.of(), 0), "incoming");
        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("no_declaration"));
        assertTrue(result.getOutput().contains("does not prove there are no calls"));
    }

    private ToolResult render(LspServerConnection.CallHierarchyResult result, String direction) throws Exception {
        var method = LspTool.class.getDeclaredMethod("renderCallHierarchy", LspServerConnection.CallHierarchyResult.class, String.class);
        method.setAccessible(true);
        return (ToolResult) method.invoke(null, result, direction);
    }
}
