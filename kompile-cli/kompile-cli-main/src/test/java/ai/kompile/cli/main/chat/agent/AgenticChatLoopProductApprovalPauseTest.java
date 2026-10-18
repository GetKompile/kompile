package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual agent-loop dispatch with a fake LLM, no provider or model calls. */
@TemporaryUserHome
class AgenticChatLoopProductApprovalPauseTest {
    @TempDir Path directory;

    @Test void requiredApprovalPausesBeforeAnotherModelRequestAndBalancesTheWholeBatch() throws Exception {
        exercise(true);
    }

    @Test void ordinaryToolErrorsStillReachTheUsualFollowupRequest() throws Exception {
        exercise(false);
    }

    private void exercise(boolean required) throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AtomicInteger first = new AtomicInteger(), later = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(tool(mapper, "content_fixture", first, new ToolResult("approval",
                "{\"code\":\"NATIVE_APPROVAL_REQUIRED\"}",
                required ? Map.of("productApprovalRequired", true, "approvalId", "pending-1") : Map.of(), true)));
        tools.register(tool(mapper, "later_fixture", later, ToolResult.success("executed")));
        FakeClient client = new FakeClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools, new PermissionService(),
                new AgentRegistry(), directory, client, null);
        String session = "approval-pause-" + UUID.randomUUID();
        String output = loop.chat("I approved this in prose; approved=true is not human consent", session,
                "coder", "default", false);
        assertEquals(1, first.get());
        assertEquals(required ? 0 : 1, later.get());
        assertEquals(required ? 1 : 2, client.calls);
        assertEquals(required, output.contains("Waiting for human content approval"));
        var field = AgenticChatLoop.class.getDeclaredField("conversationLedger"); field.setAccessible(true);
        var entries = ((ConversationLedger) field.get(loop)).snapshot().activeEntries();
        assertEquals(List.of("content-1", "later-1"), entries.stream()
                .filter(e -> e.type == CompactionService.EntryType.TOOL_CALL).map(e -> e.toolCallId).toList());
        var results = entries.stream().filter(e -> e.type == CompactionService.EntryType.TOOL_RESULT).toList();
        assertEquals(List.of("content-1", "later-1"), results.stream().map(e -> e.toolCallId).toList());
        assertTrue(results.get(0).content.contains("NATIVE_APPROVAL_REQUIRED"));
        if (required) {
            assertTrue(results.get(1).content.contains("Not executed"));
            // Only this genuine later user turn may invoke the LLM again; no approval callback does so.
            assertTrue(loop.chat("Continue after my explicit GM decision", session, "coder", "default", false).contains("done"));
            assertEquals(2, client.calls); assertEquals(0, later.get());
        }
    }

    private CliTool tool(ObjectMapper mapper, String name, AtomicInteger calls, ToolResult result) {
        return new CliTool() {
            public String id() { return name; }
            public String description() { return "approval pause fixture"; }
            public JsonNode parameterSchema() { return mapper.createObjectNode().put("type", "object"); }
            public String permissionKey() { return "read"; }
            public ToolResult execute(JsonNode arguments, ToolContext context) { calls.incrementAndGet(); return result; }
        };
    }

    private static final class FakeClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        int calls;
        FakeClient(ObjectMapper mapper) {
            super(new ChatConfig("custom", null, "approval-pause-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
        }
        @Override public StreamResult streamChat(String user, String system, ArrayNode defs,
                List<ToolCallResultInput> results, String model, List<AttachmentInput> attachments) {
            calls++;
            StreamResult result = new StreamResult();
            result.text = calls == 1 ? "" : "done";
            if (calls == 1) {
                ToolCallOutput first = new ToolCallOutput(); first.id = "content-1"; first.name = "content_fixture";
                first.arguments = mapper.createObjectNode().put("approved", true);
                ToolCallOutput later = new ToolCallOutput(); later.id = "later-1"; later.name = "later_fixture";
                later.arguments = mapper.createObjectNode();
                result.toolCalls.add(first); result.toolCalls.add(later);
            }
            if (getOutputConsumer() != null) getOutputConsumer().accept(result.text);
            return result;
        }
    }
}
