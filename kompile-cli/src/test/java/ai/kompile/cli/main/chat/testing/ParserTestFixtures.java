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

package ai.kompile.cli.main.chat.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Static factory methods that produce JSON event strings matching each agent's
 * output format. Used by parser tests to avoid inlining raw JSON in every test.
 * <p>
 * Formats covered: Claude (stream-json), Codex (JSONL), OpenCode (--format json),
 * Pi, and Gemini/Qwen (stream-json).
 */
public final class ParserTestFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ParserTestFixtures() {
        // utility class
    }

    // =========================================================================
    // Claude events
    // =========================================================================

    /**
     * {@code {"type":"system","session_id":"..."}}
     */
    public static String claudeSystemEvent(String sessionId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "system");
        node.put("session_id", sessionId);
        return serialize(node);
    }

    /**
     * {@code {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}}
     */
    public static String claudeTextEvent(String text) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "text");
        block.put("text", text);

        ArrayNode content = MAPPER.createArrayNode();
        content.add(block);

        ObjectNode message = MAPPER.createObjectNode();
        message.set("content", content);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "assistant");
        node.set("message", message);
        return serialize(node);
    }

    /**
     * {@code {"type":"assistant","message":{"content":[{"type":"tool_use","name":"...","input":...}]}}}
     *
     * @param name  tool name
     * @param input raw JSON string to embed as the input value
     */
    public static String claudeToolUseEvent(String name, String input) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "tool_use");
        block.put("name", name);
        try {
            block.set("input", MAPPER.readTree(input));
        } catch (Exception e) {
            block.put("input", input);
        }

        ArrayNode content = MAPPER.createArrayNode();
        content.add(block);

        ObjectNode message = MAPPER.createObjectNode();
        message.set("content", content);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "assistant");
        node.set("message", message);
        return serialize(node);
    }

    /**
     * {@code {"type":"result","duration_ms":...,"cost_usd":...}}
     */
    public static String claudeResultEvent(long durationMs, double cost) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "result");
        node.put("duration_ms", durationMs);
        node.put("cost_usd", cost);
        return serialize(node);
    }

    /**
     * {@code {"content_block":{"text":"..."}}}
     * <p>
     * Used for incremental content-block delta events emitted by the Claude
     * streaming API.
     */
    public static String claudeContentBlockDelta(String text) {
        ObjectNode contentBlock = MAPPER.createObjectNode();
        contentBlock.put("text", text);

        ObjectNode node = MAPPER.createObjectNode();
        node.set("content_block", contentBlock);
        return serialize(node);
    }

    // =========================================================================
    // Codex events
    // =========================================================================

    /**
     * {@code {"type":"thread.started","thread_id":"..."}}
     */
    public static String codexThreadStarted(String threadId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "thread.started");
        node.put("thread_id", threadId);
        return serialize(node);
    }

    /**
     * {@code {"type":"item.started","item":{"id":"...","type":"command_execution","command":"...","status":"in_progress"}}}
     */
    public static String codexItemStarted(String itemId, String command) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", itemId);
        item.put("type", "command_execution");
        item.put("command", command);
        item.put("status", "in_progress");

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "item.started");
        node.set("item", item);
        return serialize(node);
    }

    /**
     * {@code {"type":"item.updated","item":{"id":"...","type":"command_execution","aggregated_output":"..."}}}
     */
    public static String codexItemUpdated(String itemId, String aggregatedOutput) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", itemId);
        item.put("type", "command_execution");
        item.put("aggregated_output", aggregatedOutput);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "item.updated");
        node.set("item", item);
        return serialize(node);
    }

    /**
     * {@code {"type":"item.completed","item":{"id":"...","type":"command_execution","aggregated_output":"...","exit_code":...,"status":"completed"}}}
     */
    public static String codexItemCompletedCommand(String itemId, String aggregatedOutput, int exitCode) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", itemId);
        item.put("type", "command_execution");
        item.put("aggregated_output", aggregatedOutput);
        item.put("exit_code", exitCode);
        item.put("status", "completed");

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "item.completed");
        node.set("item", item);
        return serialize(node);
    }

    /**
     * {@code {"type":"item.completed","item":{"id":"...","type":"agent_message","text":"..."}}}
     */
    public static String codexAgentMessage(String itemId, String text) {
        ObjectNode item = MAPPER.createObjectNode();
        item.put("id", itemId);
        item.put("type", "agent_message");
        item.put("text", text);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "item.completed");
        node.set("item", item);
        return serialize(node);
    }

    /**
     * {@code {"type":"turn.completed","usage":{"input_tokens":...,"output_tokens":...,"cached_input_tokens":...}}}
     */
    public static String codexTurnCompleted(long input, long output, long cacheRead) {
        ObjectNode usage = MAPPER.createObjectNode();
        usage.put("input_tokens", input);
        usage.put("output_tokens", output);
        usage.put("cached_input_tokens", cacheRead);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "turn.completed");
        node.set("usage", usage);
        return serialize(node);
    }

    /**
     * {@code {"type":"message.delta","delta":"..."}}
     */
    public static String codexMessageDelta(String delta) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message.delta");
        node.put("delta", delta);
        return serialize(node);
    }

    /**
     * {@code "Reading additional input from stdin..."}
     * <p>
     * Plain-text noise line emitted by Codex to stderr. The parser suppresses it.
     */
    public static String codexStdinNotice() {
        return "Reading additional input from stdin...";
    }

    // =========================================================================
    // OpenCode events
    // =========================================================================

    /**
     * {@code {"type":"step_start","sessionID":"..."}}
     */
    public static String openCodeStepStart(String sessionId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "step_start");
        node.put("sessionID", sessionId);
        return serialize(node);
    }

    /**
     * {@code {"type":"text","part":{"type":"text","text":"..."}}}
     */
    public static String openCodeText(String text) {
        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "text");
        part.put("text", text);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "text");
        node.set("part", part);
        return serialize(node);
    }

    /**
     * Full {@code tool_use} event with {@code state.status=completed}.
     * <p>
     * Format:
     * <pre>
     * {"type":"tool_use","part":{"type":"tool","tool":"...","state":{"status":"completed",
     *   "input":{"command":"..."},"output":"...","metadata":{"output":"...","exit":...}}}}
     * </pre>
     *
     * @param tool     tool name (e.g. {@code "bash"})
     * @param command  command string placed inside {@code state.input.command}
     * @param output   tool output placed in {@code state.output} and {@code state.metadata.output}
     * @param exitCode exit code placed in {@code state.metadata.exit}
     */
    public static String openCodeToolUse(String tool, String command, String output, int exitCode) {
        ObjectNode inputNode = MAPPER.createObjectNode();
        inputNode.put("command", command);

        ObjectNode metadata = MAPPER.createObjectNode();
        metadata.put("output", output);
        metadata.put("exit", exitCode);

        ObjectNode state = MAPPER.createObjectNode();
        state.put("status", "completed");
        state.set("input", inputNode);
        state.put("output", output);
        state.set("metadata", metadata);

        ObjectNode part = MAPPER.createObjectNode();
        part.put("type", "tool");
        part.put("tool", tool);
        part.set("state", state);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_use");
        node.set("part", part);
        return serialize(node);
    }

    /**
     * {@code {"type":"step_finish","part":{"tokens":{"input":...,"output":...,"cache":{"read":...,"write":...}},"cost":0}}}
     */
    public static String openCodeStepFinish(long input, long output, long cacheRead, long cacheWrite) {
        ObjectNode cache = MAPPER.createObjectNode();
        cache.put("read", cacheRead);
        cache.put("write", cacheWrite);

        ObjectNode tokens = MAPPER.createObjectNode();
        tokens.put("input", input);
        tokens.put("output", output);
        tokens.set("cache", cache);

        ObjectNode part = MAPPER.createObjectNode();
        part.set("tokens", tokens);
        part.put("cost", 0);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "step_finish");
        node.set("part", part);
        return serialize(node);
    }

    // =========================================================================
    // Pi events
    // =========================================================================

    /**
     * {@code {"type":"session","id":"..."}}
     */
    public static String piSession(String sessionId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "session");
        node.put("id", sessionId);
        return serialize(node);
    }

    /**
     * {@code {"type":"message_update","message":{"id":"...","role":"assistant"},"assistantMessageEvent":{"type":"text_delta","delta":"..."}}}
     */
    public static String piTextDelta(String messageId, String delta) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("id", messageId);
        message.put("role", "assistant");

        ObjectNode assistantMessageEvent = MAPPER.createObjectNode();
        assistantMessageEvent.put("type", "text_delta");
        assistantMessageEvent.put("delta", delta);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message_update");
        node.set("message", message);
        node.set("assistantMessageEvent", assistantMessageEvent);
        return serialize(node);
    }

    /**
     * {@code {"type":"message_end","message":{"id":"...","role":"assistant","content":[{"type":"text","text":"..."}]}}}
     */
    public static String piMessageEnd(String messageId, String fullText) {
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", fullText);

        ArrayNode content = MAPPER.createArrayNode();
        content.add(textBlock);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("id", messageId);
        message.put("role", "assistant");
        message.set("content", content);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message_end");
        node.set("message", message);
        return serialize(node);
    }

    /**
     * {@code {"type":"tool_execution_start","toolCallId":"...","toolName":"...","args":...}}
     *
     * @param callId   tool call ID
     * @param toolName tool name
     * @param argsJson raw JSON string for the args value
     */
    public static String piToolStart(String callId, String toolName, String argsJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_execution_start");
        node.put("toolCallId", callId);
        node.put("toolName", toolName);
        try {
            node.set("args", MAPPER.readTree(argsJson));
        } catch (Exception e) {
            node.put("args", argsJson);
        }
        return serialize(node);
    }

    /**
     * {@code {"type":"tool_execution_update","toolCallId":"...","partialResult":{"content":[{"type":"text","text":"..."}]}}}
     */
    public static String piToolUpdate(String callId, String partialOutput) {
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", partialOutput);

        ArrayNode content = MAPPER.createArrayNode();
        content.add(textBlock);

        ObjectNode partialResult = MAPPER.createObjectNode();
        partialResult.set("content", content);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_execution_update");
        node.put("toolCallId", callId);
        node.set("partialResult", partialResult);
        return serialize(node);
    }

    /**
     * {@code {"type":"tool_execution_end","toolCallId":"...","toolName":"...","result":{"content":[{"type":"text","text":"..."}],"exitCode":...},"isError":false}}
     */
    public static String piToolEnd(String callId, String toolName, String output, int exitCode) {
        ObjectNode textBlock = MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", output);

        ArrayNode content = MAPPER.createArrayNode();
        content.add(textBlock);

        ObjectNode result = MAPPER.createObjectNode();
        result.set("content", content);
        result.put("exitCode", exitCode);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_execution_end");
        node.put("toolCallId", callId);
        node.put("toolName", toolName);
        node.set("result", result);
        node.put("isError", false);
        return serialize(node);
    }

    // =========================================================================
    // Gemini events
    // =========================================================================

    /**
     * {@code {"type":"init","session_id":"..."}}
     */
    public static String geminiInit(String sessionId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "init");
        node.put("session_id", sessionId);
        return serialize(node);
    }

    /**
     * {@code {"type":"message","role":"assistant","content":"..."}}
     */
    public static String geminiMessage(String text) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "message");
        node.put("role", "assistant");
        node.put("content", text);
        return serialize(node);
    }

    /**
     * {@code {"type":"tool_use","tool_name":"...","parameters":...}}
     *
     * @param name       tool name
     * @param paramsJson raw JSON string for the parameters value
     */
    public static String geminiToolUse(String name, String paramsJson) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "tool_use");
        node.put("tool_name", name);
        try {
            node.set("parameters", MAPPER.readTree(paramsJson));
        } catch (Exception e) {
            node.put("parameters", paramsJson);
        }
        return serialize(node);
    }

    /**
     * {@code {"type":"result","stats":{"duration_ms":...,"tool_calls":...}}}
     */
    public static String geminiResult(long durationMs, int toolCalls) {
        ObjectNode stats = MAPPER.createObjectNode();
        stats.put("duration_ms", durationMs);
        stats.put("tool_calls", toolCalls);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "result");
        node.set("stats", stats);
        return serialize(node);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static String serialize(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("ParserTestFixtures: failed to serialize JSON node", e);
        }
    }
}
