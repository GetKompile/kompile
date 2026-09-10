/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-history replay of executed tool calls must use protocol-correct
 * envelopes (OpenAI tool_calls + role:tool, Anthropic tool_use/tool_result);
 * prose-form "[Tool call ...]" replay teaches models to emit tool calls as
 * text, which the agentic loop cannot execute. Also covers the text-echo
 * rescue for models that already imitated the old format.
 */
class DirectLlmClientToolReplayTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private DirectLlmClient clientForProvider(String provider) {
        ChatConfig config = new ChatConfig(provider, "test-key",
                "fixture-model", "http://127.0.0.1:1/v1");
        return new DirectLlmClient(config, mapper);
    }

    // ── Envelope replay per route ───────────────────────────────────────────

    @Test
    void openAiRouteReplaysToolCallsAsStructuredEnvelopes() throws Exception {
        DirectLlmClient client = clientForProvider("openai");
        client.addReplayedToolCall("bash", "call_1", "{\"command\":\"ls\"}");
        client.addReplayedToolResult("bash", "call_1", "file.txt");

        ArrayNode messages = invokeBuildOpenAiMessages(client, "continue", null);
        // system is absent (no systemPrompt), then assistant envelope, tool result, user
        assertEquals(3, messages.size());
        JsonNode assistant = messages.get(0);
        assertEquals("assistant", assistant.path("role").asText());
        JsonNode call = assistant.path("tool_calls").get(0);
        assertEquals("call_1", call.path("id").asText());
        assertEquals("bash", call.path("function").path("name").asText());
        assertEquals("{\"command\":\"ls\"}", call.path("function").path("arguments").asText());

        JsonNode toolMessage = messages.get(1);
        assertEquals("tool", toolMessage.path("role").asText());
        assertEquals("call_1", toolMessage.path("tool_call_id").asText());
        assertEquals("file.txt", toolMessage.path("content").asText());

        assertEquals("continue", messages.get(2).path("content").asText());
        assertTrue(messages.get(2).path("role").asText().equals("user"));
    }

    @Test
    void anthropicRouteReplaysToolCallsAsToolUseBlocks() throws Exception {
        DirectLlmClient client = clientForProvider("anthropic");
        client.addReplayedToolCall("read", "callu_9", "{\"file_path\":\"a.txt\"}");
        client.addReplayedToolResult("read", "callu_9", "contents");

        ArrayNode messages = invokeBuildAnthropicMessages(client, "go on", null);
        assertEquals(3, messages.size());
        JsonNode assistant = messages.get(0);
        assertEquals("assistant", assistant.path("role").asText());
        JsonNode toolUse = assistant.path("content").get(0);
        assertEquals("tool_use", toolUse.path("type").asText());
        assertEquals("callu_9", toolUse.path("id").asText());
        assertEquals("read", toolUse.path("name").asText());
        assertEquals("a.txt", toolUse.path("input").path("file_path").asText());

        JsonNode user = messages.get(1);
        assertEquals("user", user.path("role").asText());
        JsonNode toolResult = user.path("content").get(0);
        assertEquals("tool_result", toolResult.path("type").asText());
        assertEquals("callu_9", toolResult.path("tool_use_id").asText());
        assertEquals("contents", toolResult.path("content").asText());
    }

    @Test
    void responsesRouteReplaysToolCallsAsFunctionCallItems() throws Exception {
        // openai-codex resolves to the OpenAI Responses protocol; chat-shaped
        // tool_calls/role:tool history items sent verbatim into Responses input
        // are rejected, so replay must use Responses-native item types.
        DirectLlmClient client = clientForProvider("openai-codex");
        client.addReplayedToolCall("bash", "call_r1", "{\"command\":\"ls\"}");
        client.addReplayedToolResult("bash", "call_r1", "out.txt");

        ArrayNode input = invokeBuildResponsesInput(client, "continue", null);
        boolean sawCall = false;
        boolean sawOutput = false;
        for (JsonNode item : input) {
            if ("function_call".equals(item.path("type").asText(""))) {
                assertEquals("call_r1", item.path("call_id").asText());
                assertEquals("bash", item.path("name").asText());
                assertEquals("completed", item.path("status").asText());
                sawCall = true;
            } else if ("function_call_output".equals(item.path("type").asText(""))) {
                assertEquals("call_r1", item.path("call_id").asText());
                assertEquals("out.txt", item.path("output").asText());
                sawOutput = true;
            }
        }
        assertTrue(sawCall, "replayed call must be a Responses function_call item");
        assertTrue(sawOutput, "replayed result must be a Responses function_call_output item");
    }

    @Test
    void legacyRoutesStillUseThePortableTextForm() throws Exception {
        // kompile-local resolves to the flattened KOMPILE_LOCAL route (TEXT).
        // OpenAI-compatible providers such as ollama are envelope-capable.
        DirectLlmClient client = clientForProvider("kompile-local");
        client.addReplayedToolCall("glob", "call_2", "{\"pattern\":\"*.md\"}");
        client.addReplayedToolResult("glob", "call_2", "README.md");

        ArrayNode messages = invokeBuildOpenAiMessages(client, "next", null);
        JsonNode assistant = messages.get(0);
        assertTrue(assistant.path("tool_calls").isMissingNode(),
                "text routes must not fabricate envelopes");
        assertTrue(assistant.path("content").asText().startsWith("[Tool call glob call_2]"));
        JsonNode user = messages.get(1);
        assertTrue(user.path("content").asText().startsWith("[Tool result glob call_2]"));
    }

    // ── Text-echo rescue ────────────────────────────────────────────────────

    @Test
    void rescueConvertsEchoedToolCallTextIntoStructuredCalls() {
        DirectLlmClient client = clientForProvider("zai");
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.text = "Checking the trace now.\n"
                + "[Tool call bash call_abc123]\n"
                + "{\"command\":\"grep DSP-TRACE log\",\"description\":\"probe\"}";

        ArrayNode toolDefs = mapper.createArrayNode();
        toolDefs.addObject().put("name", "bash");

        List<DirectLlmClient.ToolCallOutput> rescued =
                client.rescueTextEncodedToolCalls(result, toolDefs);

        assertEquals(1, rescued.size());
        assertEquals("bash", rescued.get(0).name);
        assertEquals("call_abc123", rescued.get(0).id);
        assertEquals("grep DSP-TRACE log",
                rescued.get(0).arguments.path("command").asText());
        assertEquals("Checking the trace now.", result.text.stripTrailing(),
                "the echoed tool-call text must not remain as assistant prose");
    }

    @Test
    void rescueIgnoresUnknownToolNamesAndUnparseableArguments() {
        DirectLlmClient client = clientForProvider("zai");
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.text = "Tool call bash call_x\n"
                + "[Tool call not_offered call_y]\n{\"a\":1}\n"
                + "[Tool call bash call_z]\n{not json}";

        ArrayNode toolDefs = mapper.createArrayNode();
        toolDefs.addObject().put("name", "bash");

        assertTrue(client.rescueTextEncodedToolCalls(result, toolDefs).isEmpty());
        assertTrue(result.text.contains("not_offered"),
                "non-qualifying text must be left untouched");
    }

    @Test
    void rescueSkipsRealProseAndAlreadyStructuredResults() {
        DirectLlmClient client = clientForProvider("zai");

        DirectLlmClient.StreamResult structured = new DirectLlmClient.StreamResult();
        structured.toolCalls.add(new DirectLlmClient.ToolCallOutput());
        structured.text = "[Tool call bash call_1]\n{}";
        assertTrue(client.rescueTextEncodedToolCalls(structured, toolDefs()).isEmpty());

        DirectLlmClient.StreamResult prose = new DirectLlmClient.StreamResult();
        prose.text = "I recommend reading the bash manual section on quoting.";
        assertTrue(client.rescueTextEncodedToolCalls(prose, toolDefs()).isEmpty());
        assertEquals("I recommend reading the bash manual section on quoting.", prose.text);

        DirectLlmClient.StreamResult noTools = new DirectLlmClient.StreamResult();
        noTools.text = "[Tool call bash call_1]\n{}";
        assertTrue(client.rescueTextEncodedToolCalls(noTools, null).isEmpty(),
                "no tool list means no rescue");
    }

    private ArrayNode toolDefs() {
        ArrayNode toolDefs = mapper.createArrayNode();
        toolDefs.addObject().put("name", "bash");
        return toolDefs;
    }

    // ── Reflection helpers ──────────────────────────────────────────────────

    private ArrayNode invokeBuildResponsesInput(
            DirectLlmClient client, String userMessage, String systemPrompt) throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "buildResponsesInput", String.class, String.class, List.class, boolean.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, userMessage, systemPrompt, null, true);
    }

    private ArrayNode invokeBuildOpenAiMessages(
            DirectLlmClient client, String userMessage, String systemPrompt) throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "buildOpenAiMessages", String.class, String.class, List.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, userMessage, systemPrompt, null);
    }

    private ArrayNode invokeBuildAnthropicMessages(
            DirectLlmClient client, String userMessage, String systemPrompt) throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "buildAnthropicMessages", String.class, List.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, userMessage, null);
    }
}
