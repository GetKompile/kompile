/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticChatLoopEnforcerCorrectionTest {

    @TempDir
    Path workingDirectory;

    @Test
    void stopVerdictAfterToolWorkInterruptsThroughTheUserFeedbackLane() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(noopTool(mapper, executions));

        SequenceClient client = new SequenceClient(mapper, true, false);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StopThenPassEvaluator evaluator = new StopThenPassEvaluator(false);
        AtomicReference<String> feedbackSource = new AtomicReference<>();
        AtomicReference<String> feedbackText = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("avoid forbidden drafts", 1, false), 1);
        loop.setSupervisorFeedbackHandler((source, feedback, interrupt) -> {
            feedbackSource.set(source);
            feedbackText.set(feedback);
            interrupted.set(interrupt);
            return true;
        });

        String output = loop.chat("inspect then answer", "correction-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(2, client.calls, "the rejected turn must stop before an in-loop retry");
        assertEquals(1, executions.get());
        assertEquals(List.of(1), evaluator.attempts);
        assertEquals(List.of("inspect then answer"), evaluator.userPrompts);
        assertEquals("enforcer", feedbackSource.get());
        assertTrue(feedbackText.get().contains("[correction 1/1]"));
        assertTrue(feedbackText.get().contains("Original user request:\ninspect then answer"));
        assertTrue(feedbackText.get().contains("produce a compliant response"));
        assertTrue(interrupted.get());
        assertTrue(output.contains("Interrupted by judge"));
        assertFalse(output.contains("compliant revision"));
        assertFalse(output.contains("Blocked by judge policy"));
    }

    @Test
    void repeatedStopVerdictsEndAfterTheConfiguredCorrectionBudget() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        SequenceClient client = new SequenceClient(mapper, false, true);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, new ToolRegistry(mapper), new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StopThenPassEvaluator evaluator = new StopThenPassEvaluator(true);
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("avoid forbidden drafts", 1, false), 1);

        String output = loop.chat("answer safely", "bounded-correction-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(2, client.calls, "one initial response plus one configured correction");
        assertEquals(List.of(1, 2), evaluator.attempts);
        assertEquals(List.of("answer safely", "answer safely"), evaluator.userPrompts);
        assertTrue(output.contains("Blocked by judge policy"));
    }

    @Test
    void correctionBudgetSurvivesTheInterruptingUserTurnBoundary() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        SequenceClient client = new SequenceClient(mapper, false, true);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, new ToolRegistry(mapper), new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StopThenPassEvaluator evaluator = new StopThenPassEvaluator(true);
        AtomicInteger forwarded = new AtomicInteger();
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("avoid forbidden drafts", 1, false), 1);
        loop.setSupervisorFeedbackHandler((source, feedback, interrupt) -> {
            forwarded.incrementAndGet();
            return true;
        });

        String output = loop.chat(
                "[enforcer feedback]\n[correction 1/1]\nRewrite safely",
                "carried-budget-" + UUID.randomUUID(), "coder", "default", false);

        assertEquals(1, client.calls);
        assertEquals(List.of(2), evaluator.attempts,
                "the next turn must continue the prior correction attempt count");
        assertEquals(0, forwarded.get(), "an exhausted correction chain must not self-loop");
        assertTrue(output.contains("Blocked by judge policy"));
    }

    @Test
    void injectedMemoryContextIsNotMistakenForTheUserRequest() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        SequenceClient client = new SequenceClient(mapper, false, false);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, new ToolRegistry(mapper), new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StopThenPassEvaluator evaluator = new StopThenPassEvaluator(false);
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("follow the actual request", 1, false), 1);
        String actualRequest = "Fix the task-tool completion notification bug";
        String enriched = "<memory_context>\n" + "old context ".repeat(600)
                + "\n</memory_context>\n\n" + actualRequest;

        loop.chat(enriched, "raw-request-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(List.of(actualRequest, actualRequest), evaluator.userPrompts,
                "every review must receive the actual request, including after a correction");
    }

    @Test
    void acceptedQueuedUserRequestRemainsTheReviewObjectiveAfterCorrection() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(noopTool(mapper, executions));
        SequenceClient client = new SequenceClient(mapper, true, false);
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, tools, new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        StopThenPassEvaluator evaluator = new StopThenPassEvaluator(false);
        loop.setInlineEnforcer(evaluator, new EnforcerPolicy("follow user steering", 1, false), 1);
        String queuedRequest = "Report findings only; do not edit files";
        AtomicInteger polls = new AtomicInteger();
        loop.setQueuedMessageSupplier(() -> polls.incrementAndGet() == 2
                ? AgenticChatLoop.QueuedInput.immediate(queuedRequest) : null);

        String output = loop.chat("inspect then fix", "queued-review-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(1, executions.get());
        assertEquals(3, client.calls);
        assertEquals(List.of(1, 2), evaluator.attempts);
        assertEquals(List.of(queuedRequest, queuedRequest), evaluator.userPrompts,
                "accepted user steering, unlike an internal correction, changes the objective");
        assertTrue(output.contains("compliant revision"));
    }

    private static CliTool noopTool(ObjectMapper mapper, AtomicInteger executions) {
        return new CliTool() {
            @Override public String id() { return "noop_tool"; }
            @Override public String description() { return "test fixture"; }
            @Override public JsonNode parameterSchema() {
                return mapper.createObjectNode().put("type", "object");
            }
            @Override public String permissionKey() { return "read"; }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                executions.incrementAndGet();
                return ToolResult.success("inspected");
            }
        };
    }

    private static final class StopThenPassEvaluator implements EnforcerEvaluator {
        private final boolean alwaysStop;
        private final List<Integer> attempts = new ArrayList<>();
        private final List<String> userPrompts = new ArrayList<>();

        private StopThenPassEvaluator(boolean alwaysStop) {
            this.alwaysStop = alwaysStop;
        }

        @Override
        public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                         EnforcerPolicy policy, int attempt) {
            userPrompts.add(userPrompt);
            attempts.add(attempt);
            if (alwaysStop || attempts.size() == 1) {
                return EnforcerDecision.stop(List.of("forbidden draft"), "blocked fixture response");
            }
            return EnforcerDecision.pass("corrected");
        }

        @Override public boolean isAvailable() { return true; }
        @Override public String describe() { return "stop-then-pass"; }
    }

    private static final class SequenceClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private final boolean startWithTool;
        private final boolean alwaysBad;
        private final List<String> userMessages = new ArrayList<>();
        private int calls;

        private SequenceClient(ObjectMapper mapper, boolean startWithTool, boolean alwaysBad) {
            super(new ChatConfig(
                    "custom", null, "correction-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
            this.startWithTool = startWithTool;
            this.alwaysBad = alwaysBad;
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
            userMessages.add(userMessage == null ? "" : userMessage);
            StreamResult result = new StreamResult();
            if (startWithTool && calls == 1) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-1";
                call.name = "noop_tool";
                call.arguments = mapper.createObjectNode();
                result.toolCalls.add(call);
            } else if (alwaysBad || calls == (startWithTool ? 2 : 1)) {
                result.text = "forbidden draft";
            } else {
                result.text = "compliant revision";
            }
            return result;
        }
    }
}
