package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticChatLoopSupervisorTest {

    @TempDir
    Path workingDirectory;

    @Test
    void qualityJudgeIsAdvisoryWhenNoPolicyJudgeIsConfigured() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicInteger judgeReviews = new AtomicInteger();
        AtomicReference<String> judgeInput = new AtomicReference<>();
        AtomicReference<String> feedbackSource = new AtomicReference<>();
        AtomicReference<String> feedbackText = new AtomicReference<>();
        AtomicReference<String> judgeActivity = new AtomicReference<>();

        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) -> {
            judgeReviews.incrementAndGet();
            judgeInput.set(input);
            return new EnforcerToolCallDecision(
                    EnforcerToolCallDecision.Action.BLOCK,
                    "call does not match the requested inspection",
                    List.of("unjustified side effect"),
                    "Inspect without running dangerous_tool",
                    null);
        });
        loop.setSupervisorFeedbackHandler((source, feedback, interrupt) -> {
            feedbackSource.set(source);
            feedbackText.set(feedback);
            return true;
        });
        loop.setInlineEnforcerActivityListener(event -> {
            if (event.startsWith("[judge advisory")) {
                judgeActivity.set(event);
            }
        });

        String output = loop.chat(
                "inspect the project", "supervisor-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(1, judgeReviews.get());
        assertTrue(judgeInput.get().contains("***REDACTED***"));
        assertFalse(judgeInput.get().contains("top-secret"));
        assertEquals(1, executions.get(), "an advisory judge verdict must not block MCP execution");
        assertNull(feedbackSource.get());
        assertNull(feedbackText.get());
        assertNotNull(judgeActivity.get());
        assertTrue(judgeActivity.get().contains("BLOCK"));
        assertFalse(client.toolResults.get(0).isError);
        assertTrue(client.toolResults.get(0).output.contains("executed"));
        assertEquals("corrected response", output);
    }

    @Test
    void policyJudgeBlocksWithoutRunningDuplicateQualityReview() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicInteger judgeReviews = new AtomicInteger();
        AtomicReference<String> feedbackSource = new AtomicReference<>();
        AtomicReference<String> feedbackText = new AtomicReference<>();

        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) ->
                new EnforcerToolCallDecision(
                        EnforcerToolCallDecision.Action.BLOCK,
                        "policy denies it", List.of("unsafe side effect"),
                        "Choose a read-only inspection", null));
        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) -> {
            judgeReviews.incrementAndGet();
            return EnforcerToolCallDecision.allow("the call is relevant");
        });
        loop.setSupervisorFeedbackHandler((source, feedback, interrupt) -> {
            feedbackSource.set(source);
            feedbackText.set(feedback);
            return true;
        });

        loop.chat("inspect the project", "enforcer-authority-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(0, judgeReviews.get(), "one policy judge replaces duplicate quality review");
        assertEquals(0, executions.get(), "the policy judge must block before MCP execution");
        assertEquals("enforcer", feedbackSource.get());
        assertEquals("Choose a read-only inspection", feedbackText.get());
        assertTrue(client.toolResults.get(0).isError);
        assertTrue(client.toolResults.get(0).output.contains("Blocked by enforcer"));
    }

    @Test
    void hostClassifiedReadOnlyGitSkipsProbabilisticPolicyBlock() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool("bash", mapper, executions));
        ToolCallingClient client = new ToolCallingClient(
                mapper, "", "bash", mapper.createObjectNode().put("command", "git diff --check"));
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setReminderManager(ReminderManager.inMemory(
                List.of("Git operations, especially resets, are not allowed"), List.of()));
        AtomicInteger policyReviews = new AtomicInteger();
        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) -> {
            policyReviews.incrementAndGet();
            return EnforcerToolCallDecision.block("broad Git reminder");
        });

        loop.chat("inspect the diff", "readonly-git-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(0, policyReviews.get(),
                "the LLM policy judge must not reclassify host-proven read-only Git as mutation");
        assertEquals(1, executions.get());
        assertFalse(client.toolResults.get(0).isError);
    }

    @Test
    void cancellationDuringSupervisorReviewCannotFallThroughToToolExecution() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));
        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger judgeReviews = new AtomicInteger();
        loop.setCancelSignal(cancelled);
        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) -> {
            cancelled.set(true);
            return EnforcerToolCallDecision.allow("review interrupted");
        });
        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) -> {
            judgeReviews.incrementAndGet();
            return EnforcerToolCallDecision.allow("should not run after cancellation");
        });

        String output = loop.chat(
                "inspect then cancel", "supervisor-cancel-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(0, executions.get());
        assertEquals(0, judgeReviews.get(),
                "cancellation in one supervisory REPL must skip later reviewers");
        assertEquals(1, client.calls, "cancelled review must not issue a follow-up model request");
        assertTrue(output.contains("Interrupted by user"));
    }

    @Test
    void approvedRewriteChangesArgumentsAndReportsItToTheMainModel() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicReference<JsonNode> executedArguments = new AtomicReference<>();
        tools.register(new CliTool() {
            @Override public String id() { return "dangerous_tool"; }
            @Override public String description() { return "rewrite fixture"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                executedArguments.set(params.deepCopy());
                return ToolResult.success("executed safely");
            }
        });
        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) ->
                new EnforcerToolCallDecision(
                        EnforcerToolCallDecision.Action.REWRITE,
                        "remove the unsafe flag", List.of(), "",
                        Map.of("force", false)));
        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) ->
                EnforcerToolCallDecision.allow("rewritten call is relevant"));

        loop.chat("rewrite safely", "supervisor-rewrite-" + UUID.randomUUID(),
                "coder", "default", false);

        assertNotNull(executedArguments.get());
        assertFalse(executedArguments.get().path("force").asBoolean(true));
        assertFalse(client.toolResults.get(0).isError);
        assertTrue(client.toolResults.get(0).output.contains("Arguments rewritten by enforcer"));
        assertTrue(client.toolResults.get(0).output.contains("executed safely"));
    }

    @Test
    void inlinePolicyJudgeReceivesReminderConstraintsAndToolPlanningContext() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));
        ToolCallingClient client = new ToolCallingClient(mapper, "I inspected the request and planned first.");
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setReminderManager(ReminderManager.inMemory(
                List.of("Plan before making changes"), List.of("Run focused tests")));

        List<String> judgePrompts = new java.util.ArrayList<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                judgePrompts.add(userPrompt);
                return userPrompt.contains("[PROPOSED MCP TOOL CALL]")
                        ? "{\"action\":\"ALLOW\",\"reason\":\"planned\"}"
                        : "{\"compliant\":true,\"stop\":false}";
            }
            @Override public boolean isAvailable() { return true; }
        };
        loop.setInlineEnforcer(new EnforcerJudge(backend, mapper),
                new EnforcerPolicy("Do not expose secrets.", 2, false), 2);

        loop.chat("inspect and update the parser", "reminder-enforcer-" + UUID.randomUUID(),
                "coder", "default", false);

        String toolPrompt = judgePrompts.stream()
                .filter(prompt -> prompt.contains("[PROPOSED MCP TOOL CALL]"))
                .findFirst().orElseThrow();
        assertTrue(toolPrompt.contains("[ACTIVE REMINDER CONSTRAINTS]"));
        assertTrue(toolPrompt.contains("[project] Plan before making changes"));
        assertTrue(toolPrompt.contains("[session] Run focused tests"));
        assertTrue(toolPrompt.contains("user: inspect and update the parser"));
        assertTrue(toolPrompt.contains("assistant: I inspected the request and planned first."));
        assertEquals(1, executions.get());
    }

    private static CliTool countingTool(ObjectMapper mapper, AtomicInteger executions) {
        return countingTool("dangerous_tool", mapper, executions);
    }

    private static CliTool countingTool(
            String toolId, ObjectMapper mapper, AtomicInteger executions) {
        return new CliTool() {
            @Override
            public String id() {
                return toolId;
            }

            @Override
            public String description() {
                return "must be supervised";
            }

            @Override
            public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }

            @Override
            public String permissionKey() {
                return "read";
            }

            @Override
            public ToolResult execute(JsonNode params, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("executed");
            }
        };
    }

    private static final class ToolCallingClient extends DirectLlmClient {
        private final String firstResponseText;
        private final String toolName;
        private final JsonNode toolArguments;
        private int calls;
        private List<ToolCallResultInput> toolResults = List.of();

        private ToolCallingClient(ObjectMapper mapper) {
            this(mapper, "");
        }

        private ToolCallingClient(ObjectMapper mapper, String firstResponseText) {
            this(mapper, firstResponseText, "dangerous_tool",
                    mapper.createObjectNode()
                            .put("force", true)
                            .put("api_key", "top-secret"));
        }

        private ToolCallingClient(ObjectMapper mapper, String firstResponseText,
                                  String toolName, JsonNode toolArguments) {
            super(new ChatConfig(
                    "custom", null, "supervisor-test", "http://unused.invalid"), mapper);
            this.firstResponseText = firstResponseText;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
        }

        @Override
        public StreamResult streamChat(
                String userMessage,
                String systemPrompt,
                ArrayNode toolDefs,
                List<ToolCallResultInput> toolResults,
                String modelOverride,
                List<AttachmentInput> attachments) {
            calls++;
            if (calls == 1) {
                StreamResult result = new StreamResult();
                result.text = firstResponseText;
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-1";
                call.name = toolName;
                call.arguments = toolArguments.deepCopy();
                result.toolCalls.add(call);
                return result;
            }

            this.toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
            Consumer<String> output = getOutputConsumer();
            if (output != null) output.accept("corrected response");
            StreamResult result = new StreamResult();
            result.text = "corrected response";
            return result;
        }
    }
}
