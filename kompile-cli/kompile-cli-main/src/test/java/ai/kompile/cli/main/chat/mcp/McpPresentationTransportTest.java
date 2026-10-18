package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.chat.sources.ProductDisplayTurn;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.exec.ExecJsonEvents;
import ai.kompile.cli.main.chat.exec.HeadlessRunEvent;
import ai.kompile.cli.main.chat.exec.ToolCallJson;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** In-memory MCP through the actual display/event/canonical-history seams; no subprocess or model. */
class McpPresentationTransportTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private static final String PAYLOAD = "{\"presentation\":{\"version\":1,\"kind\":\"vendor.board\",\"data\":{\"savedId\":7,\"runtimeId\":\"runtime-1\"}}}";

    private ObjectNode envelope(String text) {
        ObjectNode root = json.createObjectNode().put("isError", false);
        root.putArray("content").addObject().put("type", "text").put("text", text);
        return root;
    }

    private ToolResult execute(JsonNode envelope) throws Exception {
        var remote = McpBundleToolLoader.renderResult(envelope);
        var info = new McpBundleToolLoader.RemoteTool("show_board", "Read-only view", json.createObjectNode().put("type", "object"));
        var endpoint = new McpBundleToolLoader.McpEndpoint() {
            public void initialize() { }
            public List<McpBundleToolLoader.RemoteTool> listTools() { return List.of(info); }
            public McpBundleToolLoader.RemoteCallResult callTool(String name, JsonNode args) {
                assertEquals("show_board", name);
                return remote;
            }
            public void close() { }
        };
        var context = new ToolContext("session", null, new PermissionService(), directory, new ToolRegistry(json));
        context.setAutoApproveAll(true);
        return new McpBundleToolLoader.McpTool("actual-server", info, endpoint).execute(json.createObjectNode(), context);
    }

    @Test @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void completeSuccessfulMcpPreservesModelOutputAndRoundTripsTheExactLiveDescriptor() throws Exception {
        ToolResult result = execute(envelope(PAYLOAD));
        assertFalse(result.isError());
        assertEquals(PAYLOAD, result.getOutput(), "do not alter the model-visible result");
        assertTrue(result.getMetadata().isEmpty(), "no new metadata for model serialization");
        var detail = ToolCallJson.detail(json, "mcp_tool_call", "{\"tool\":\"caller-spoof\"}", result);
        assertEquals("actual-server", detail.path("presentation").path("source").path("serverId").asText());
        assertEquals("show_board", detail.path("presentation").path("source").path("toolName").asText());
        assertFalse(detail.path("presentation").has("identity"));
        ToolCallJson.identify(detail, "session", "turn-1", "call-1");
        var completed = HeadlessRunEvent.toolCompleted("session", "call-1", "mcp_tool_call", "{}", true, 10, detail).withSequence(8);
        var wire = json.readTree(ExecJsonEvents.event(json, completed));
        assertEquals("tool", wire.path("type").asText());
        assertEquals(detail, wire.path("detail"));
        var entry = ProductDisplayTurn.presentation("session", "turn-1", "call-1", "mcp_tool_call", wire.path("detail").path("presentation"));
        assertNotNull(entry);

        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        try {
            var history = new ChatHistory("session");
            history.open("", "gm", false, directory);
            history.logUserMessage("Show board");
            history.logProductAgentResponse("gm", "Ready", 10, "turn-1", List.of(entry, entry));
            history.close();
            var adapter = new KompileAdapter(history.getTranscriptFile().getParent());
            var display = adapter.readProductDisplayTurns("session");
            assertEquals(2, display.size());
            assertEquals("turn-1", display.get(1).productTurnId());
            assertEquals(List.of(entry), display.get(1).toolPresentations());
            assertEquals(wire.path("detail").path("presentation"), display.get(1).toolPresentations().get(0).presentation());
            assertEquals(display, adapter.readProductDisplayTurns("session"));
            assertEquals("Ready", history.readTurns().get(1).content());
            assertNull(history.readTurns().get(1).rawContentBlocks());
        } finally { System.setProperty("user.home", oldHome); }
    }

    @Test void errorsPartialMalformedMixedAndNativeOutputsNeverAcquirePresentation() throws Exception {
        for (ObjectNode invalid : List.of(envelope(PAYLOAD).put("isError", true), envelope(PAYLOAD).put("partial", true),
                envelope(PAYLOAD).put("truncated", true), envelope(PAYLOAD.substring(0, PAYLOAD.length() - 2)),
                envelope("Look here: " + PAYLOAD), envelope(PAYLOAD + " {}"),
                envelope(PAYLOAD.replace("\"data\":", "\"source\":{},\"data\":")))) {
            assertNull(McpBundleToolLoader.presentation(execute(invalid)));
        }
        ObjectNode mixed = envelope(PAYLOAD);
        ((com.fasterxml.jackson.databind.node.ArrayNode) mixed.path("content")).addObject().put("type", "text").put("text", "preview");
        assertNull(McpBundleToolLoader.presentation(execute(mixed)));
        ToolResult nativeResult = ToolResult.success("native", PAYLOAD,
                Map.of("presentation", json.readTree(PAYLOAD).path("presentation")));
        assertFalse(ToolCallJson.detail(json, "native", "{}", nativeResult).has("presentation"));
        ToolResult complete = execute(envelope(PAYLOAD));
        ToolResult shortened = new ToolResult(complete.getTitle(), "preview", complete.getMetadata(), complete.isError());
        assertFalse(ToolCallJson.detail(json, "mcp_tool_call", "{}", shortened).has("presentation"));
        var ordinary = ToolCallJson.detail(json, "mcp_tool_call", "{}", complete);
        ToolCallJson.identify(ordinary, "session", null, "call");
        assertFalse(ordinary.has("presentation"), "managed display must not invent a product turn");
    }

    @Test void outputOnlyRebuildKeepsTrustedPresentationFromTheCompleteRawResult() throws Exception {
        ToolResult complete = execute(envelope(PAYLOAD));
        ObjectNode original = McpBundleToolLoader.presentation(complete);
        assertNotNull(original, "A complete successful MCP result owns display provenance");
        // The loop rebuilds a plain ToolResult whenever output text changes, even by whitespace.
        ToolResult rebuilt = new ToolResult(complete.getTitle(), complete.getOutput() + "\n", complete.getMetadata(), false);
        assertNull(McpBundleToolLoader.presentation(rebuilt), "A plain rebuild has no provenance");
        ToolResult reattached = McpBundleToolLoader.reattach(rebuilt, original);
        assertEquals(rebuilt.getOutput(), reattached.getOutput());
        assertEquals(original, McpBundleToolLoader.presentation(reattached));
        ToolResult errored = McpBundleToolLoader.reattach(ToolResult.error("native failure"), original);
        assertTrue(errored.isError(), "An error result must never gain display provenance");
        assertNull(McpBundleToolLoader.presentation(errored));
    }

    @Test @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void displayStorageFailureDoesNotChangeCompletedToolSuccess() throws Exception {
        ToolResult result = execute(envelope(PAYLOAD));
        var detail = ToolCallJson.detail(json, "mcp_tool_call", "{}", result);
        ToolCallJson.identify(detail, "session", "turn", "call");
        var entry = ProductDisplayTurn.presentation("session", "turn", "call", "mcp_tool_call", detail.path("presentation"));
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        try {
            Files.createDirectories(directory.resolve(".kompile/conversations/session.txt"));
            var history = new ChatHistory("session");
            history.open("", "gm", false, directory);
            assertDoesNotThrow(() -> history.logProductAgentResponse("gm", "Ready", 1, "turn", List.of(entry)));
            history.close();
            assertFalse(result.isError());
            assertEquals(PAYLOAD, result.getOutput());
        } finally { System.setProperty("user.home", oldHome); }
    }
}
