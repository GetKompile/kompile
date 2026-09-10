/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.enforcer.JudgeControl;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the unified judge control contract: policy review is authoritative, the quality
 * advisory is not duplicated, and the session switch disables every local judge lane.
 */
class AgenticChatLoopJudgeOverrideTest {

    @TempDir
    Path workingDirectory;
    @TempDir
    Path sessionsDirectory;

    @Test
    void armedJudgeOverrideDoesNotBypassEnforcerToolPolicy() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);

        AtomicInteger enforcerReviews = new AtomicInteger();
        AtomicInteger enforcerToolReviews = new AtomicInteger();
        AtomicInteger judgeReviews = new AtomicInteger();
        // The output judge is report-only for the armed turn, while the MCP
        // enforcer remains authoritative.
        loop.setInlineEnforcer(new StoppingEnforcerEvaluator(enforcerReviews),
                new EnforcerPolicy("no dangerous tools", 2, false), 2);
        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) -> {
            enforcerToolReviews.incrementAndGet();
            return EnforcerToolCallDecision.block("active enforcer policy blocks the call");
        });
        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) -> {
            judgeReviews.incrementAndGet();
            return EnforcerToolCallDecision.block("would block under normal rules");
        });

        JudgeControl control = new JudgeControl("override-" + UUID.randomUUID(),
                sessionsDirectory);
        control.setOverrideNext(true);
        loop.setJudgeControl(control);

        // Turn 1: judge override is armed, but the enforcer still blocks the MCP call.
        String overriddenOutput = loop.chat(
                "run the dangerous tool", control.getSessionId(), "coder", "default", false);

        assertEquals(1, enforcerToolReviews.get());
        assertEquals(0, judgeReviews.get(),
                "a configured policy judge replaces the duplicate quality advisory");
        assertEquals(1, enforcerReviews.get(), "turn output is still reviewed in report-only mode");
        assertEquals(0, executions.get(),
                "a judge override must not bypass the active enforcer's MCP block");
        assertTrue(client.calls >= 2);
        assertTrue(overriddenOutput.contains("done"));

        // Turn 2: override consumed — output enforcement is fully active again,
        // while MCP enforcement remains unchanged.
        int executionsBeforeTurn2 = executions.get();
        ToolCallingClient client2 = new ToolCallingClient(mapper);
        AgenticChatLoop loop2 = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client2, null);
        loop2.setInlineEnforcer(new StoppingEnforcerEvaluator(enforcerReviews),
                new EnforcerPolicy("no dangerous tools", 2, false), 2);
        loop2.setEnforcerToolCallInterceptor((user, assistant, tool, input) ->
                EnforcerToolCallDecision.block("active enforcer policy blocks the call"));
        loop2.setJudgeToolCallInterceptor((user, assistant, tool, input) ->
                EnforcerToolCallDecision.block("would block under normal rules"));
        loop2.setJudgeControl(control);

        String enforcedOutput = loop2.chat(
                "run it again", control.getSessionId(), "coder", "default", false);

        assertEquals(executionsBeforeTurn2, executions.get(),
                "the enforcer blocks MCP execution both with and without judge override");
        assertTrue(enforcedOutput.contains("Blocked by judge policy"));
    }

    @Test
    void sessionOffSkipsEveryJudgeLaneAndAllowsTheTool() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        AtomicInteger policyReviews = new AtomicInteger();
        AtomicInteger toolReviews = new AtomicInteger();
        AtomicInteger advisoryReviews = new AtomicInteger();
        loop.setInlineEnforcer(new StoppingEnforcerEvaluator(policyReviews),
                new EnforcerPolicy("no dangerous tools", 2, false), 2);
        loop.setEnforcerToolCallInterceptor((user, assistant, tool, input) -> {
            toolReviews.incrementAndGet();
            return EnforcerToolCallDecision.block("blocked");
        });
        loop.setJudgeToolCallInterceptor((user, assistant, tool, input) -> {
            advisoryReviews.incrementAndGet();
            return EnforcerToolCallDecision.block("advisory");
        });

        JudgeControl control = new JudgeControl("off-" + UUID.randomUUID(), sessionsDirectory);
        control.setEnabled(false);
        loop.setJudgeControl(control);

        String output = loop.chat(
                "run the tool", control.getSessionId(), "coder", "default", false);

        assertEquals(0, policyReviews.get());
        assertEquals(0, toolReviews.get());
        assertEquals(0, advisoryReviews.get());
        assertEquals(1, executions.get());
        assertTrue(output.contains("done"));
    }

    @Test
    void guidanceReachesTheJudgeEvaluatorPrompts() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        RecordingEnforcerEvaluator evaluator = new RecordingEnforcerEvaluator();

        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));
        ToolCallingClient client = new ToolCallingClient(mapper);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        loop.setInlineEnforcer(evaluator,
                new EnforcerPolicy("rules", 2, false), 2);

        JudgeControl control = new JudgeControl("guidance-" + UUID.randomUUID(),
                sessionsDirectory);
        control.setGuidance("the user explicitly approved local file edits");
        loop.setJudgeControl(control);

        loop.chat("hello", control.getSessionId(), "coder", "default", false);

        assertTrue(evaluator.lastUserPrompt.contains(
                        "the user explicitly approved local file edits"),
                "the enforcer judge prompt must carry the user's guidance");
        assertEquals(1, executions.get());
    }

    @Test
    void exactCommandApprovalOverridesOnlyTheMatchingJudgeVerdict() throws Exception {
        assertCommandReview("rm -rf build-cache", "rm -rf build-cache", true, 0);
        assertCommandReview("rm -rf other", "rm -rf build-cache", false, 1);
        assertCommandReview("rm -rf build-cache; rm other", "rm -rf build-cache", false, 1);
        assertCommandReview("rm -rf .kompile/memory", "rm -rf .kompile/memory", false, 0);
        assertCommandReview("cat source.txt", "cat source.txt", false, 0);
    }

    @Test
    void patternApprovalFlowsThroughTheLoopWithoutWideningItsScope() throws Exception {
        assertCommandReview("rm -rf target/cache-42", "--pattern rm -rf target/cache-*", true, 0);
        assertCommandReview("rm -rf other", "--pattern rm -rf target/cache-*", false, 1);
        assertCommandReview("rm -rf target/cache-42; rm other", "--pattern rm -rf target/cache-*", false, 1);
        assertCommandReview("rm -rf .kompile/memory", "--pattern rm -rf **", false, 0);
    }

    @Test
    void advisoryJudgeDoesNotReviewReadOnlyGitWithoutAPolicyJudge() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions, "bash"));
        ToolCallingClient client = new ToolCallingClient(mapper);
        client.command = "git log --oneline -5";
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools, new PermissionService(),
                new AgentRegistry(), workingDirectory, client, null);
        AtomicInteger reviews = new AtomicInteger();
        loop.setJudgeToolCallInterceptor((u, a, t, i) -> {
            reviews.incrementAndGet();
            return EnforcerToolCallDecision.block("fixture");
        });
        loop.chat("inspect history", UUID.randomUUID().toString(), "coder", "default", false);
        assertEquals(1, executions.get());
        assertEquals(0, reviews.get());
    }

    private void assertCommandReview(String command, String approved, boolean allowed, int expectedReviews)
            throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions, "bash"));
        ToolCallingClient client = new ToolCallingClient(mapper);
        client.command = command;
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, tools, new PermissionService(),
                new AgentRegistry(), workingDirectory, client, null);
        loop.setInlineEnforcer(new RecordingEnforcerEvaluator(), new EnforcerPolicy("rules", 2, false), 2);
        AtomicInteger reviews = new AtomicInteger();
        loop.setEnforcerToolCallInterceptor((u, a, t, i) -> {
            reviews.incrementAndGet();
            return EnforcerToolCallDecision.block("fixture");
        });
        JudgeControl control = new JudgeControl(UUID.randomUUID().toString(), sessionsDirectory);
        if (approved.startsWith("--pattern ")) control.approvePatternNext(approved.substring(10));
        else control.approveCommandNext(approved);
        loop.setJudgeControl(control);
        loop.chat("retry the approved command", control.getSessionId(), "coder", "default", false);
        assertEquals(allowed ? 1 : 0, executions.get(), command);
        assertEquals(expectedReviews, reviews.get(), command);
        assertEquals("", control.getApprovedCommandNext());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static final class StoppingEnforcerEvaluator implements EnforcerEvaluatorStub {
        private final AtomicInteger reviews;

        StoppingEnforcerEvaluator(AtomicInteger reviews) {
            this.reviews = reviews;
        }

        @Override
        public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                         EnforcerPolicy policy, int attempt) {
            reviews.incrementAndGet();
            return EnforcerDecision.stop(List.of("dangerous tool call refused"),
                    "stop under normal rules");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "stopping-stub";
        }
    }

    private interface EnforcerEvaluatorStub extends ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator {
    }

    private static final class RecordingEnforcerEvaluator implements EnforcerEvaluatorStub {
        String lastUserPrompt = "";

        @Override
        public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                         EnforcerPolicy policy, int attempt) {
            lastUserPrompt = userPrompt;
            return EnforcerDecision.pass("ok");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "recording-stub";
        }
    }

    private static CliTool countingTool(ObjectMapper mapper, AtomicInteger executions) {
        return countingTool(mapper, executions, "dangerous_tool");
    }

    private static CliTool countingTool(ObjectMapper mapper, AtomicInteger executions, String name) {
        return new CliTool() {
            @Override public String id() { return name; }
            @Override public String description() { return "must be supervised"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("executed");
            }
        };
    }

    private static final class ToolCallingClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private int calls;
        private String command;

        private ToolCallingClient(ObjectMapper mapper) {
            super(new ChatConfig(
                    "custom", null, "judge-override-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
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
            StreamResult result = new StreamResult();
            if (calls == 1) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-1";
                call.name = command == null ? "dangerous_tool" : "bash";
                call.arguments = command == null ? mapper.createObjectNode().put("force", true)
                        : mapper.createObjectNode().put("command", command);
                result.toolCalls.add(call);
                return result;
            }
            Consumer<String> output = getOutputConsumer();
            if (output != null) output.accept("done");
            result.text = "done";
            return result;
        }
    }
}
