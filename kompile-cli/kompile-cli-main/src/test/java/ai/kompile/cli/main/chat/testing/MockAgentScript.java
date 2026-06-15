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

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for bash scripts that simulate LLM CLI agent output.
 * <p>
 * Instead of inlining raw bash + JSON in every test, this builder produces a
 * complete {@code /bin/bash -c} command string that:
 * <ol>
 *   <li>Reads stdin to {@code /dev/null} so the client's message write doesn't
 *       cause a broken-pipe error.</li>
 *   <li>Echoes each JSON event line to stdout in the correct agent format.</li>
 *   <li>Optionally sleeps between lines (useful for testing cancel signals).</li>
 *   <li>Exits with a configurable exit code.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MockAgentScript script = MockAgentScript.claude()
 *     .sessionInit("session-1")
 *     .textResponse("Hello from mock Claude")
 *     .turnComplete(10, 5)
 *     .build();
 *
 * // Use directly:
 * String[] cmd = script.toCommand();   // ["/bin/bash", "-c", "..."]
 *
 * // Or via SubprocessTestHarness:
 * try (SubprocessTestHarness harness = SubprocessTestHarness.forAgent("claude")
 *         .script(script).build()) {
 *     harness.chat("hi").assertResultContains("Hello from mock Claude");
 * }
 * }</pre>
 */
public class MockAgentScript {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String script;

    private MockAgentScript(String script) {
        this.script = script;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns a ready-to-use command array: {@code ["/bin/bash", "-c", script]}.
     */
    public String[] toCommand() {
        return new String[]{"/bin/bash", "-c", script};
    }

    /**
     * Returns the raw bash script string (useful for debugging or embedding in
     * larger scripts).
     */
    public String toScript() {
        return script;
    }

    // =========================================================================
    // Static factory methods
    // =========================================================================

    /** Builder for Claude stream-json format. */
    public static Builder claude() {
        return new Builder("claude");
    }

    /** Builder for Codex JSONL format. */
    public static Builder codex() {
        return new Builder("codex");
    }

    /** Builder for OpenCode {@code --format json} format. */
    public static Builder opencode() {
        return new Builder("opencode");
    }

    /** Builder for Pi JSON streaming format. */
    public static Builder pi() {
        return new Builder("pi");
    }

    /** Builder for Gemini/Qwen stream-json format. */
    public static Builder gemini() {
        return new Builder("gemini");
    }

    /**
     * Builder with no format assumptions.  The caller provides complete JSON
     * strings via {@link Builder#rawLine(String)}.
     */
    public static Builder raw() {
        return new Builder("raw");
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static class Builder {

        private final String agentFormat;
        private final List<String> lines = new ArrayList<>();
        private int exitCode = 0;

        /** Counter used to generate incrementing item/call IDs for Codex and Pi. */
        private int itemN = 0;

        Builder(String agentFormat) {
            this.agentFormat = agentFormat;
        }

        // ------------------------------------------------------------------
        // Agent-aware event methods
        // ------------------------------------------------------------------

        /**
         * Emits a session/thread-start event with the given ID.
         * <p>
         * Format per agent:
         * <ul>
         *   <li>claude   → {@code {"type":"system","session_id":"<id>"}}</li>
         *   <li>codex    → {@code {"type":"thread.started","thread_id":"<id>"}}</li>
         *   <li>opencode → {@code {"type":"step_start","sessionID":"<id>"}}</li>
         *   <li>pi       → {@code {"type":"session","id":"<id>"}}</li>
         *   <li>gemini   → {@code {"type":"init","session_id":"<id>"}}</li>
         * </ul>
         */
        public Builder sessionInit(String sessionId) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (agentFormat) {
                case "claude":
                    node.put("type", "system");
                    node.put("session_id", sessionId);
                    break;
                case "codex":
                    node.put("type", "thread.started");
                    node.put("thread_id", sessionId);
                    break;
                case "opencode":
                    node.put("type", "step_start");
                    node.put("sessionID", sessionId);
                    break;
                case "pi":
                    node.put("type", "session");
                    node.put("id", sessionId);
                    break;
                case "gemini":
                    node.put("type", "init");
                    node.put("session_id", sessionId);
                    break;
                default:
                    // raw / unknown — no-op; caller uses rawLine()
                    return this;
            }
            return addJson(node);
        }

        /**
         * Emits a text/assistant-message event.
         * <p>
         * Format per agent:
         * <ul>
         *   <li>claude   → {@code {"type":"assistant","message":{"content":[{"type":"text","text":"..."}]}}}</li>
         *   <li>codex    → {@code {"type":"item.completed","item":{"id":"item_<n>","type":"agent_message","text":"..."}}}</li>
         *   <li>opencode → {@code {"type":"text","part":{"type":"text","text":"..."}}}</li>
         *   <li>pi       → {@code {"type":"message_update",...,"assistantMessageEvent":{"type":"text_delta","delta":"..."}}}</li>
         *   <li>gemini   → {@code {"type":"message","role":"assistant","content":"..."}}</li>
         * </ul>
         */
        public Builder textResponse(String text) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (agentFormat) {
                case "claude": {
                    ObjectNode block = MAPPER.createObjectNode();
                    block.put("type", "text");
                    block.put("text", text);
                    ArrayNode content = MAPPER.createArrayNode();
                    content.add(block);
                    ObjectNode message = MAPPER.createObjectNode();
                    message.set("content", content);
                    node.put("type", "assistant");
                    node.set("message", message);
                    break;
                }
                case "codex": {
                    String itemId = "item_" + itemN++;
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("id", itemId);
                    item.put("type", "agent_message");
                    item.put("text", text);
                    node.put("type", "item.completed");
                    node.set("item", item);
                    break;
                }
                case "opencode": {
                    ObjectNode part = MAPPER.createObjectNode();
                    part.put("type", "text");
                    part.put("text", text);
                    node.put("type", "text");
                    node.set("part", part);
                    break;
                }
                case "pi": {
                    ObjectNode message = MAPPER.createObjectNode();
                    message.put("id", "m1");
                    message.put("role", "assistant");
                    ObjectNode evt = MAPPER.createObjectNode();
                    evt.put("type", "text_delta");
                    evt.put("delta", text);
                    node.put("type", "message_update");
                    node.set("message", message);
                    node.set("assistantMessageEvent", evt);
                    break;
                }
                case "gemini": {
                    node.put("type", "message");
                    node.put("role", "assistant");
                    node.put("content", text);
                    break;
                }
                default:
                    // raw — no-op
                    return this;
            }
            return addJson(node);
        }

        /**
         * Emits a tool-use/tool-start event.
         *
         * @param name  the tool/command name
         * @param input the command or input text (embedded as a JSON string value)
         *
         * Format per agent:
         * <ul>
         *   <li>claude   → {@code {"type":"assistant","message":{"content":[{"type":"tool_use","name":"...","input":{"command":"..."}}]}}}</li>
         *   <li>codex    → {@code {"type":"item.started","item":{"id":"item_<n>","type":"command_execution","command":"...","status":"in_progress"}}}</li>
         *   <li>opencode → {@code {"type":"tool_use","part":{"type":"tool","tool":"...","state":{"status":"completed","input":{"command":"..."},"output":"","metadata":{"exit":0}}}}}</li>
         *   <li>pi       → {@code {"type":"tool_execution_start","toolCallId":"call_<n>","toolName":"...","args":{"command":"..."}}}</li>
         *   <li>gemini   → {@code {"type":"tool_use","tool_name":"...","parameters":{"command":"..."}}}</li>
         * </ul>
         */
        public Builder toolUse(String name, String input) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (agentFormat) {
                case "claude": {
                    ObjectNode inputNode = MAPPER.createObjectNode();
                    inputNode.put("command", input);
                    ObjectNode block = MAPPER.createObjectNode();
                    block.put("type", "tool_use");
                    block.put("name", name);
                    block.set("input", inputNode);
                    ArrayNode content = MAPPER.createArrayNode();
                    content.add(block);
                    ObjectNode message = MAPPER.createObjectNode();
                    message.set("content", content);
                    node.put("type", "assistant");
                    node.set("message", message);
                    break;
                }
                case "codex": {
                    String itemId = "item_" + itemN++;
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("id", itemId);
                    item.put("type", "command_execution");
                    item.put("command", input);
                    item.put("status", "in_progress");
                    node.put("type", "item.started");
                    node.set("item", item);
                    break;
                }
                case "opencode": {
                    ObjectNode inputNode = MAPPER.createObjectNode();
                    inputNode.put("command", input);
                    ObjectNode metadata = MAPPER.createObjectNode();
                    metadata.put("exit", 0);
                    ObjectNode state = MAPPER.createObjectNode();
                    state.put("status", "completed");
                    state.set("input", inputNode);
                    state.put("output", "");
                    state.set("metadata", metadata);
                    ObjectNode part = MAPPER.createObjectNode();
                    part.put("type", "tool");
                    part.put("tool", name);
                    part.set("state", state);
                    node.put("type", "tool_use");
                    node.set("part", part);
                    break;
                }
                case "pi": {
                    String callId = "call_" + itemN++;
                    ObjectNode args = MAPPER.createObjectNode();
                    args.put("command", input);
                    node.put("type", "tool_execution_start");
                    node.put("toolCallId", callId);
                    node.put("toolName", name);
                    node.set("args", args);
                    break;
                }
                case "gemini": {
                    ObjectNode params = MAPPER.createObjectNode();
                    params.put("command", input);
                    node.put("type", "tool_use");
                    node.put("tool_name", name);
                    node.set("parameters", params);
                    break;
                }
                default:
                    return this;
            }
            return addJson(node);
        }

        /**
         * Emits a tool-complete/tool-result event.
         * <p>
         * Not all agent formats have a distinct tool-complete event:
         * <ul>
         *   <li>claude   → no separate tool-result in stream-json; skipped.</li>
         *   <li>codex    → {@code {"type":"item.completed","item":{"id":"item_<n>","type":"command_execution","command":"...","aggregated_output":"...","exit_code":...,"status":"completed"}}}</li>
         *   <li>opencode → no separate event (output is embedded in {@code toolUse}); skipped.</li>
         *   <li>pi       → {@code {"type":"tool_execution_end","toolCallId":"call_<n>","toolName":"...","result":{"content":[{"type":"text","text":"..."}],"exitCode":...},"isError":false}}</li>
         *   <li>gemini   → no separate event; skipped.</li>
         * </ul>
         *
         * @param name     the tool name (used as the command value for Codex)
         * @param output   tool output text
         * @param exitCode process exit code
         */
        public Builder toolComplete(String name, String output, int exitCode) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (agentFormat) {
                case "codex": {
                    String itemId = "item_" + itemN++;
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("id", itemId);
                    item.put("type", "command_execution");
                    item.put("command", name);
                    item.put("aggregated_output", output);
                    item.put("exit_code", exitCode);
                    item.put("status", "completed");
                    node.put("type", "item.completed");
                    node.set("item", item);
                    break;
                }
                case "pi": {
                    String callId = "call_" + itemN++;
                    ObjectNode textBlock = MAPPER.createObjectNode();
                    textBlock.put("type", "text");
                    textBlock.put("text", output);
                    ArrayNode content = MAPPER.createArrayNode();
                    content.add(textBlock);
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("content", content);
                    result.put("exitCode", exitCode);
                    node.put("type", "tool_execution_end");
                    node.put("toolCallId", callId);
                    node.put("toolName", name);
                    node.set("result", result);
                    node.put("isError", false);
                    break;
                }
                default:
                    // claude / opencode / gemini / raw — no distinct tool-complete event
                    return this;
            }
            return addJson(node);
        }

        /**
         * Emits a turn/session-complete event with token usage.
         * <p>
         * Pi has no turn-complete event; this call is silently ignored for Pi.
         * <ul>
         *   <li>claude   → {@code {"type":"result","duration_ms":0,"cost_usd":0.0}}</li>
         *   <li>codex    → {@code {"type":"turn.completed","usage":{"input_tokens":...,"output_tokens":...}}}</li>
         *   <li>opencode → {@code {"type":"step_finish","part":{"tokens":{"input":...,"output":...},"cost":0}}}</li>
         *   <li>gemini   → {@code {"type":"result","stats":{"duration_ms":0,"tool_calls":0}}}</li>
         * </ul>
         *
         * @param inputTokens  number of input/prompt tokens consumed
         * @param outputTokens number of output/completion tokens generated
         */
        public Builder turnComplete(long inputTokens, long outputTokens) {
            ObjectNode node = MAPPER.createObjectNode();
            switch (agentFormat) {
                case "claude":
                    node.put("type", "result");
                    node.put("duration_ms", 0);
                    node.put("cost_usd", 0.0);
                    break;
                case "codex": {
                    ObjectNode usage = MAPPER.createObjectNode();
                    usage.put("input_tokens", inputTokens);
                    usage.put("output_tokens", outputTokens);
                    node.put("type", "turn.completed");
                    node.set("usage", usage);
                    break;
                }
                case "opencode": {
                    ObjectNode tokens = MAPPER.createObjectNode();
                    tokens.put("input", inputTokens);
                    tokens.put("output", outputTokens);
                    ObjectNode part = MAPPER.createObjectNode();
                    part.set("tokens", tokens);
                    part.put("cost", 0);
                    node.put("type", "step_finish");
                    node.set("part", part);
                    break;
                }
                case "pi":
                    // Pi has no turn-complete event — silently skip
                    return this;
                case "gemini": {
                    ObjectNode stats = MAPPER.createObjectNode();
                    stats.put("duration_ms", 0);
                    stats.put("tool_calls", 0);
                    node.put("type", "result");
                    node.set("stats", stats);
                    break;
                }
                default:
                    return this;
            }
            return addJson(node);
        }

        /**
         * Inserts a {@code sleep <ms>ms} statement between the previous and next
         * echo lines.  Useful for testing cancellation / timeout behaviour.
         *
         * @param ms milliseconds to sleep
         */
        public Builder sleepMs(long ms) {
            lines.add("sleep " + (ms / 1000.0));
            return this;
        }

        /**
         * Appends a raw JSON line verbatim.  Use this as an escape hatch when the
         * built-in helpers do not cover the exact event shape you need.
         *
         * @param json complete JSON string (must be a single line)
         */
        public Builder rawLine(String json) {
            lines.add("echo " + singleQuote(json));
            return this;
        }

        /**
         * Sets the exit code that the generated script will exit with.
         * Defaults to {@code 0}.
         */
        public Builder exitCode(int code) {
            this.exitCode = code;
            return this;
        }

        /**
         * Builds and returns a {@link MockAgentScript} whose bash script contains
         * all registered events.
         */
        public MockAgentScript build() {
            StringBuilder sb = new StringBuilder();
            // Always drain stdin first so the writing end of the pipe doesn't break
            sb.append("cat > /dev/null");
            for (String line : lines) {
                sb.append("; ").append(line);
            }
            if (exitCode != 0) {
                sb.append("; exit ").append(exitCode);
            }
            return new MockAgentScript(sb.toString());
        }

        // ------------------------------------------------------------------
        // Internal helpers
        // ------------------------------------------------------------------

        private Builder addJson(ObjectNode node) {
            String json = serialize(node);
            lines.add("echo " + singleQuote(json));
            return this;
        }

        /**
         * Wraps {@code value} in single quotes, escaping any embedded single
         * quotes using the {@code '\''} shell trick.
         *
         * <p>For example, the value {@code it's} becomes {@code 'it'\''s'}.
         */
        private static String singleQuote(String value) {
            return "'" + value.replace("'", "'\\''") + "'";
        }

        private static String serialize(ObjectNode node) {
            try {
                return MAPPER.writeValueAsString(node);
            } catch (Exception e) {
                throw new RuntimeException("MockAgentScript: failed to serialize JSON", e);
            }
        }
    }
}
