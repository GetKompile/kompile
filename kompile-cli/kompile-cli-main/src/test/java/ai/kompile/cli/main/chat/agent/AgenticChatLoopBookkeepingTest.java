package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class AgenticChatLoopBookkeepingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void staleCompletionGetsCorrectedAndPendingTaskUpdatesAreAllowed(boolean lateRepair, boolean captured) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        tools.register(new TodoWriteTool());
        tools.register(new ai.kompile.cli.main.chat.tools.CliTool() {
            public String id() { return "noop"; }
            public String description() { return "read-only test fixture"; }
            public com.fasterxml.jackson.databind.JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            public String permissionKey() { return "read"; }
            public ai.kompile.cli.main.chat.tools.ToolResult execute(
                    com.fasterxml.jackson.databind.JsonNode params,
                    ai.kompile.cli.main.chat.tools.ToolContext context) {
                return ai.kompile.cli.main.chat.tools.ToolResult.success("observed");
            }
        });
        BookkeepingClient client = new BookkeepingClient(mapper, lateRepair);
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools,
                new PermissionService(), new AgentRegistry(), directory, client, null);
        List<String> prompts = new ArrayList<>();
        AtomicInteger corrections = new AtomicInteger();
        EnforcerJudge judge = new EnforcerJudge(new JudgeBackend() {
            @Override public boolean isAvailable() { return true; }
            @Override public String generate(String user, String system) {
                prompts.add(user);
                assertTrue(user.contains("[DEFAULT TASK BOOKKEEPING]"));
                if (user.contains("[PROPOSED MCP TOOL CALL]")) {
                    return "{\"action\":\"ALLOW\",\"reason\":\"fixture read\"}";
                }
                if (user.contains("No pending tool calls; final response.")
                        && (captured ? corrections.get() == 0 : user.contains("\"status\":\"in_progress\""))) {
                    corrections.incrementAndGet();
                    return """
                            {"compliant":false,"stop":false,"severity":"error",
                             "violations":["Finished task is still in_progress"],
                             "correction_prompt":"Use todowrite to mark task 1 completed before the final answer",
                             "reasoning":"Bookkeeping is stale"}
                            """;
                }
                return "{\"compliant\":true,\"stop\":false}";
            }
        }, mapper);
        loop.setInlineEnforcer(captured ? judge.captureForChild() : judge,
                new EnforcerPolicy("Never reveal secrets", 2, false), 2);
        String session = "bookkeeping-loop-" + UUID.randomUUID();
        try {
            String output = loop.chat("Implement the fix and validate it", session, "coder", "default", false);
            assertTrue(output.contains("Done"));
            assertFalse(output.contains("Blocked by judge"));
            assertEquals(1, corrections.get());
            assertEquals(4, client.calls);
            assertEquals("completed", TodoWriteTool.getTodos(session, directory).get(0).status);
            assertTrue(prompts.get(0).contains("Proposed calls, not yet executed:"));
            assertTrue(prompts.get(0).contains("todowrite"));
            assertTrue(prompts.stream().anyMatch(prompt ->
                    prompt.contains("Pending todowrite updates in full batch: 1")
                            && prompt.contains("\"status\":\"completed\"")),
                    "a late repair must be visible to both live and captured judges");
            if (!captured) {
                assertTrue(prompts.get(prompts.size() - 1).contains("\"status\":\"completed\""));
            }
        } finally {
            judge.close();
        }
    }

    @Test
    void boundedContextCountsAllNamespacedUpdatesRatherThanOnlyTheSample() {
        List<AgenticChatLoop.ToolCallRequest> calls = new ArrayList<>();
        var mapper = JsonUtils.standardMapper();
        for (int i = 0; i < 60; i++) {
            var call = new AgenticChatLoop.ToolCallRequest();
            call.name = i < 30 ? "read" : "mcp__kompile__todowrite";
            call.arguments = mapper.createObjectNode().put("payload", "x".repeat(5_000));
            calls.add(call);
        }
        var context = AgenticChatLoop.pendingToolContext(calls);
        String text = context.formatForPrompt(8_000);
        assertTrue(text.contains("Pending todowrite updates in full batch: 30"));
        assertTrue(text.contains("mcp__kompile__todowrite"));
        assertTrue(text.contains("Additional task updates omitted"));
        assertTrue(context.formatForPrompt(20_000).length() < 8_000,
                "the default judge budget must not truncate the repair evidence");
    }

    private static final class BookkeepingClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        int calls;
        final boolean lateRepair;
        BookkeepingClient(ObjectMapper mapper, boolean lateRepair) {
            super(new ChatConfig("custom", null, "bookkeeping-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
            this.lateRepair = lateRepair;
        }
        @Override
        public StreamResult streamChat(String user, String system, ArrayNode defs,
                                       List<ToolCallResultInput> results, String model,
                                       List<AttachmentInput> attachments) {
            calls++;
            StreamResult result = new StreamResult();
            result.text = calls == 1 ? "Starting the plan" : "Done";
            if (calls == 1 || calls == 3) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "task-call-" + calls;
                call.name = "todowrite";
                var args = mapper.createObjectNode();
                if (calls == 1) {
                    args.put("action", "set").putArray("todos").addObject()
                            .put("id", "1").put("subject", "Implement and validate")
                            .put("status", "in_progress");
                } else {
                    args.put("action", "update").put("task_id", "1").put("status", "completed");
                    assertTrue(user.contains("todowrite"), "the correction must be actionable");
                }
                call.arguments = args;
                if (calls == 3 && lateRepair) {
                    for (int i = 0; i < 10; i++) {
                        ToolCallOutput read = new ToolCallOutput();
                        read.id = "read-" + i;
                        read.name = "noop";
                        read.arguments = mapper.createObjectNode().put("index", i);
                        result.toolCalls.add(read);
                    }
                }
                result.toolCalls.add(call);
            }
            if (getOutputConsumer() != null) getOutputConsumer().accept(result.text);
            return result;
        }
    }
}
