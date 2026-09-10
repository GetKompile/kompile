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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.enforcer.DirectionJudge;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loop-level contract for the direction judge: strictly opt-in (a loop without one never
 * checks direction), in-place redirects keep the conversation context, drift beyond the
 * redirect budget halts the turn through the supervisor feedback lane — and the one-shot
 * /judge override does NOT disarm direction monitoring.
 */
class AgenticChatLoopDirectionJudgeTest {

    @TempDir
    Path workingDirectory;
    @TempDir
    Path sessionsDirectory;

    @Test
    void noDirectionJudgeMeansNoDirectionChecks() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        LoopClient client = new LoopClient(mapper, 1);
        AgenticChatLoop loop = newLoop(client, tools);
        AtomicInteger directionEvents = new AtomicInteger();
        loop.setInlineEnforcerActivityListener(event -> {
            if (event != null && event.startsWith("[direction]")) {
                directionEvents.incrementAndGet();
            }
        });

        loop.chat("fix the bug", "no-judge-" + UUID.randomUUID(), "coder", "default", false);

        assertEquals(0, directionEvents.get(),
                "without a direction judge the direction lane must remain completely inactive");
    }

    @Test
    void confidentDriftRedirectsInPlaceAndThenHalts() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        // Iteration 1 issues a tool call (so the direction check point is reached);
        // later iterations stream text only.
        LoopClient client = new LoopClient(mapper, 1);
        AgenticChatLoop loop = newLoop(client, tools);

        AtomicInteger toolVerdicts = new AtomicInteger();
        loop.setEnforcerToolCallInterceptor((u, a, t, i) -> {
            toolVerdicts.incrementAndGet();
            return EnforcerToolCallDecision.allow("ok");
        });

        DirectionJudge judge = new DirectionJudge(
                scriptedDriftBackend(), mapper,
                new DirectionJudge.Options("fix the bug", 1, 1, false));
        loop.setDirectionJudge(judge);

        JudgeControl control = new JudgeControl(
                "direction-" + UUID.randomUUID(), sessionsDirectory);
        loop.setJudgeControl(control);

        String output = loop.chat(
                "fix the bug", control.getSessionId(), "coder", "default", false);

        // First drift → one in-place redirect (same conversation, corrective message);
        // second drift → budget spent (max 1) → halt, never a third model call.
        assertEquals(1, judge.getRedirectsThisTurn());
        assertEquals(2, client.calls, "halt must end the turn: no third model call");
        assertTrue(output.contains("[Direction judge halted the turn"),
                "the halt must be visible in the transcript: " + output);
        assertTrue(client.prompts.get(1).contains("correcting course"),
                "the redirect must be fed to the model as the next message: "
                        + client.prompts.get(1));
        assertEquals(0, executions.get(),
                "superseded tool calls must not execute after a redirect");
    }

    @Test
    void judgeOverrideDoesNotDisarmDirectionMonitoring() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        LoopClient client = new LoopClient(mapper, 1);
        AgenticChatLoop loop = newLoop(client, tools);

        // The compliance judge is report-only this turn via the one-shot override,
        // yet direction monitoring must still act on confident drift.
        JudgeControl control = new JudgeControl(
                "direction-override-" + UUID.randomUUID(), sessionsDirectory);
        control.setOverrideNext(true);

        DirectionJudge judge = new DirectionJudge(
                scriptedDriftBackend(), mapper,
                new DirectionJudge.Options("fix the bug", 1, 0, false));
        loop.setDirectionJudge(judge);
        loop.setJudgeControl(control);

        String output = loop.chat(
                "fix the bug", control.getSessionId(), "coder", "default", false);

        assertTrue(judge.getChecksThisTurn() > 0,
                "the direction judge still runs under the judge override");
        assertTrue(output.contains("[Direction judge halted the turn"),
                "direction monitoring is NOT overridable by /judge override");
    }

    @Test
    void reportOnlyModeObservesWithoutActing() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        AtomicInteger executions = new AtomicInteger();
        tools.register(countingTool(mapper, executions));

        LoopClient client = new LoopClient(mapper, 1);
        AgenticChatLoop loop = newLoop(client, tools);

        DirectionJudge judge = new DirectionJudge(
                scriptedDriftBackend(), mapper,
                new DirectionJudge.Options("fix the bug", 1, 2, true));
        loop.setDirectionJudge(judge);

        JudgeControl control = new JudgeControl(
                "direction-report-" + UUID.randomUUID(), sessionsDirectory);
        loop.setJudgeControl(control);

        String output = loop.chat(
                "fix the bug", control.getSessionId(), "coder", "default", false);

        assertTrue(judge.getChecksThisTurn() > 0, "report-only still checks");
        assertFalse(output.contains("[Direction judge halted the turn"),
                "report-only must never halt");
        assertTrue(client.calls >= 2,
                "report-only lets the turn finish naturally (tool path continues)");
    }

    @Test
    void unifiedSessionOffSkipsDirectionLifecycleAndPreservesState() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        TextOnlyClient client = new TextOnlyClient(mapper);
        AgenticChatLoop loop = newLoop(client, tools);
        AtomicInteger directionCalls = new AtomicInteger();
        DirectionJudge judge = new DirectionJudge(new ai.kompile.cli.main.chat.harness.JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                directionCalls.incrementAndGet();
                return "{\"on_track\":false,\"confidence\":0.99,"
                        + "\"drift_reason\":\"must not run\","
                        + "\"redirect_prompt\":\"must not redirect\"}";
            }
            @Override public boolean isAvailable() { return true; }
        }, mapper, new DirectionJudge.Options("fix the bug", 1, 1, false));
        loop.setDirectionJudge(judge);

        JudgeControl control = new JudgeControl(
                "direction-off-" + UUID.randomUUID(), sessionsDirectory);
        control.setEnabled(false);
        loop.setJudgeControl(control);

        String output = loop.chat(
                "fix the bug", control.getSessionId(), "coder", "default", false);

        assertEquals(0, directionCalls.get());
        assertEquals(0, judge.getChecksThisTurn());
        assertEquals(0, judge.getSessionState().assessedTurns(),
                "disabled turns must not complete or mutate direction state");
        assertEquals(1, client.calls);
        assertTrue(output.contains("done-1"));
    }

    @Test
    void finalLowConfidenceDriftIsCheckedButNeverActs() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        TextOnlyClient client = new TextOnlyClient(mapper);
        AgenticChatLoop loop = newLoop(client, tools);
        DirectionJudge judge = new DirectionJudge(
                directionBackend("{\"on_track\":false,\"confidence\":0.2,"
                        + "\"drift_reason\":\"uncertain\","
                        + "\"redirect_prompt\":\"do not act\"}"),
                mapper, new DirectionJudge.Options(
                        "fix the bug", 3, 2, false, 0.6, 3));
        loop.setDirectionJudge(judge);

        String output = loop.chat(
                "fix the bug", "direction-low-confidence-" + UUID.randomUUID(),
                "coder", "default", false);

        assertEquals(1, judge.getChecksThisTurn(),
                "the final text response must be checked even before cadence iteration 3");
        assertEquals(1, client.calls,
                "low-confidence drift must not trigger a redirect request");
        assertEquals(0, judge.getRedirectsThisTurn());
        assertFalse(output.contains("[Direction judge halted the turn"));
    }

    @Test
    void thirdConsecutiveDriftAffectedTurnEscalatesBeforeAnotherRedirect() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ToolRegistry tools = new ToolRegistry(mapper);
        TextOnlyClient client = new TextOnlyClient(mapper);
        AgenticChatLoop loop = newLoop(client, tools);
        String drift = "{\"on_track\":false,\"confidence\":0.95,"
                + "\"drift_reason\":\"repeated scope runaway\","
                + "\"redirect_prompt\":\"return to the failing test\"}";
        String onTrack = "{\"on_track\":true,\"confidence\":0.95}";
        DirectionJudge judge = new DirectionJudge(
                sequenceDirectionBackend(drift, onTrack, drift, onTrack, drift),
                mapper, new DirectionJudge.Options(
                        "fix the bug", 1, 2, false, 0.6, 3));
        loop.setDirectionJudge(judge);

        String sessionId = "direction-cross-turn-" + UUID.randomUUID();
        String first = loop.chat("fix the bug", sessionId, "coder", "default", false);
        assertFalse(first.contains("persistent direction drift"));
        assertEquals(1, judge.getSessionState().consecutiveDriftTurns());

        String second = loop.chat("continue", sessionId, "coder", "default", false);
        assertFalse(second.contains("persistent direction drift"));
        assertEquals(2, judge.getSessionState().consecutiveDriftTurns());

        String third = loop.chat("continue", sessionId, "coder", "default", false);
        assertTrue(third.contains("persistent direction drift across 3 consecutive turns"),
                "third drift-affected turn must halt with cross-turn evidence: " + third);
        assertEquals(3, judge.getSessionState().consecutiveDriftTurns());
        assertEquals(5, client.calls,
                "cross-turn escalation halts before issuing a sixth redirect request");
        assertEquals(0, judge.getRedirectsThisTurn(),
                "persistent escalation happens before spending the third turn's redirect budget");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private AgenticChatLoop newLoop(
            DirectLlmClient client, ToolRegistry tools) {
        AgenticChatLoop loop = new AgenticChatLoop(
                null, JsonUtils.standardMapper(), tools,
                new PermissionService(), new AgentRegistry(),
                workingDirectory, client, null);
        return loop;
    }

    private static ai.kompile.cli.main.chat.harness.JudgeBackend scriptedDriftBackend() {
        String response = "{\"on_track\":false,\"confidence\":0.95,"
                + "\"drift_reason\":\"circular scope runaway\","
                + "\"redirect_prompt\":\"correcting course: return to the failing test\"}";
        return new ai.kompile.cli.main.chat.harness.JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return response;
            }
            @Override public boolean isAvailable() {
                return true;
            }
        };
    }

    private static ai.kompile.cli.main.chat.harness.JudgeBackend directionBackend(
            String response) {
        return sequenceDirectionBackend(response);
    }

    private static ai.kompile.cli.main.chat.harness.JudgeBackend sequenceDirectionBackend(
            String... responses) {
        AtomicInteger index = new AtomicInteger();
        return new ai.kompile.cli.main.chat.harness.JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                int current = Math.min(index.getAndIncrement(), responses.length - 1);
                return responses[current];
            }
            @Override public boolean isAvailable() {
                return true;
            }
        };
    }

    private static CliTool countingTool(ObjectMapper mapper, AtomicInteger executions) {
        return new CliTool() {
            @Override public String id() { return "dangerous_tool"; }
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

    private static final class LoopClient extends DirectLlmClient {
        private final ObjectMapper mapper;
        private final int toolCallOnIteration;
        private int calls;
        final List<String> prompts = new java.util.ArrayList<>();

        private LoopClient(ObjectMapper mapper, int toolCallOnIteration) {
            super(new ChatConfig(
                    "custom", null, "direction-test", "http://unused.invalid"), mapper);
            this.mapper = mapper;
            this.toolCallOnIteration = toolCallOnIteration;
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
            prompts.add(userMessage == null ? "" : userMessage);
            StreamResult result = new StreamResult();
            if (calls == toolCallOnIteration) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-" + calls;
                call.name = "dangerous_tool";
                call.arguments = mapper.createObjectNode().put("force", true);
                result.toolCalls.add(call);
                return result;
            }
            result.text = "done";
            return result;
        }
    }

    private static final class TextOnlyClient extends DirectLlmClient {
        private int calls;

        private TextOnlyClient(ObjectMapper mapper) {
            super(new ChatConfig(
                    "custom", null, "direction-text-test", "http://unused.invalid"), mapper);
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
            result.text = "done-" + calls;
            return result;
        }
    }
}
