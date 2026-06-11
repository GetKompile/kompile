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

package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight parser for CLI agent output in passthrough mode.
 * <p>
 * For Claude Code: parses stream-json events (assistant text, tool_use, result).
 * For other agents: passes through plain text and detects prompt patterns for turn boundaries.
 */
public class PassthroughStreamParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Tracks last aggregated_output per Codex item ID for delta computation. */
    private final Map<String, String> codexAggregatedOutputs = new HashMap<>();

    /** Tracks last streamed text per Codex agent_message item ID for delta computation. */
    private final Map<String, String> codexAgentMessages = new HashMap<>();

    /**
     * Parsed event from agent output.
     */
    public interface PassthroughEvent {
    }

    public record TextChunk(String text) implements PassthroughEvent {}
    /** Model reasoning/thinking content — not displayed as response text but signals active generation. */
    public record ThinkingChunk(String text) implements PassthroughEvent {}
    public record ToolUse(String name, String input) implements PassthroughEvent {}
    public record TurnComplete(long durationMs, double costUsd, int numTurns,
                               long inputTokens, long outputTokens,
                               long cacheReadTokens, long cacheCreationTokens) implements PassthroughEvent {
        public TurnComplete(long durationMs, double costUsd, int numTurns) {
            this(durationMs, costUsd, numTurns, 0, 0, 0, 0);
        }
    }
    public record TokenUsage(long inputTokens, long outputTokens,
                              long cacheReadTokens, long cacheCreationTokens) implements PassthroughEvent {}
    public record ToolOutput(String output) implements PassthroughEvent {}
    public record ToolComplete(String name, String output, int exitCode, boolean error) implements PassthroughEvent {
        /** Backward-compatible constructor — error defaults to exitCode != 0. */
        public ToolComplete(String name, String output, int exitCode) {
            this(name, output, exitCode, exitCode != 0);
        }
    }
    public record SessionInit(String sessionId) implements PassthroughEvent {}
    public record PromptDetected() implements PassthroughEvent {}

    /** A single option in an interactive question. */
    public record QuestionOption(String label, String description) {}

    /**
     * Agent is asking the user a question with optional choices.
     * Emitted by Codex ({@code request_user_input}), OpenCode ({@code ask_user} tool),
     * or Claude Code ({@code AskUserQuestion} tool).
     *
     * @param callId        tool/request call ID (for response routing)
     * @param turnId        turn ID (Codex-specific, may be null)
     * @param questionId    per-question ID within a multi-question request (Codex)
     * @param header        optional header/title for the question
     * @param question      the question text
     * @param options       selectable options (empty if free-form only)
     * @param freeformAllowed whether the user can type a custom answer
     */
    public record InteractiveQuestion(
            String callId, String turnId, String questionId,
            String header, String question,
            List<QuestionOption> options, boolean freeformAllowed
    ) implements PassthroughEvent {}

    /**
     * Agent needs user approval for a command execution.
     * Emitted by Codex ({@code exec_approval_request}).
     *
     * @param callId    tool call ID
     * @param turnId    turn ID
     * @param command   the command to approve
     * @param cwd       working directory
     * @param reason    why the agent wants to run this
     * @param decisions available decisions (e.g. "approve", "deny")
     */
    public record InteractiveApproval(
            String callId, String turnId,
            String command, String cwd, String reason,
            List<String> decisions
    ) implements PassthroughEvent {}

    /**
     * Parse a line of Claude stream-json output.
     * Returns a list of events (may be empty if the line should be skipped).
     * <p>
     * An assistant message can contain both text blocks and tool_use blocks
     * (e.g., text explaining choices followed by an AskUserQuestion tool).
     * All content blocks are returned as separate events so the caller can
     * render the text before presenting the interactive prompt.
     */
    public List<PassthroughEvent> parseClaudeLineMulti(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "system" -> {
                    if (node.has("session_id")) {
                        return List.of(new SessionInit(node.get("session_id").asText()));
                    }
                    return List.of();
                }
                case "assistant" -> {
                    if (node.has("message") && node.get("message").has("content")) {
                        List<PassthroughEvent> events = new ArrayList<>();
                        StringBuilder text = new StringBuilder();
                        for (JsonNode block : node.get("message").get("content")) {
                            String blockType = block.has("type") ? block.get("type").asText() : "";
                            if ("text".equals(blockType) && block.has("text")) {
                                text.append(block.get("text").asText());
                            } else if ("tool_use".equals(blockType)) {
                                // Flush accumulated text before the tool event
                                if (text.length() > 0) {
                                    events.add(new TextChunk(text.toString()));
                                    text.setLength(0);
                                }
                                PassthroughEvent askEvent = parseClaudeAskUser(block);
                                if (askEvent != null) {
                                    events.add(askEvent);
                                } else {
                                    String name = block.has("name") ? block.get("name").asText() : "unknown";
                                    String input = block.has("input") ? block.get("input").toString() : "";
                                    events.add(new ToolUse(name, input));
                                }
                            }
                        }
                        // Flush any remaining text after all blocks
                        if (text.length() > 0) {
                            events.add(new TextChunk(text.toString()));
                        }
                        return events;
                    }
                    // Incremental content_block_delta
                    if (node.has("content_block") && node.get("content_block").has("text")) {
                        return List.of(new TextChunk(node.get("content_block").get("text").asText()));
                    }
                    return List.of();
                }
                case "result" -> {
                    long duration = node.has("duration_ms") ? node.get("duration_ms").asLong() : 0;
                    double cost = node.has("cost_usd") ? node.get("cost_usd").asDouble() : 0.0;
                    int turns = node.has("num_turns") ? node.get("num_turns").asInt() : 0;
                    return List.of(new TurnComplete(duration, cost, turns));
                }
                default -> {
                    if (node.has("content_block")) {
                        JsonNode cb = node.get("content_block");
                        if (cb.has("text")) {
                            return List.of(new TextChunk(cb.get("text").asText()));
                        }
                    }
                    return List.of();
                }
            }
        } catch (Exception e) {
            // Not valid JSON - treat as plain text
            return List.of(new TextChunk(line));
        }
    }


    /**
     * Parse a line of OpenCode JSON output ({@code --format json}).
     * <p>
     * OpenCode event types:
     * <ul>
     *   <li>{@code text} — text chunk with {@code part.text}</li>
     *   <li>{@code tool_use} — tool invocation with {@code part.tool}, {@code part.state.input/output}</li>
     *   <li>{@code step_start} — turn boundary (contains sessionID)</li>
     *   <li>{@code step_finish} — turn end with tokens/cost</li>
     * </ul>
     */
    public PassthroughEvent parseOpenCodeLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "step_start" -> {
                    if (node.has("sessionID")) {
                        return new SessionInit(node.get("sessionID").asText());
                    }
                    return null;
                }
                case "text" -> {
                    JsonNode part = node.get("part");
                    if (part != null && part.has("text")) {
                        return new TextChunk(part.get("text").asText());
                    }
                    return null;
                }
                case "reasoning" -> {
                    JsonNode part = node.get("part");
                    if (part != null && part.has("text")) {
                        return new ThinkingChunk(part.get("text").asText());
                    }
                    return null;
                }
                case "tool_use" -> {
                    JsonNode part = node.get("part");
                    if (part != null) {
                        String tool = part.has("tool") ? part.get("tool").asText() : "unknown";
                        // Detect ask_user / AskUserQuestion tool — these need interactive handling
                        String toolLower = tool.toLowerCase();
                        if (toolLower.contains("ask_user") || toolLower.contains("askuserquestion")
                                || toolLower.contains("question")) {
                            return parseOpenCodeAskUser(part);
                        }
                        String input = "";
                        if (part.has("state") && part.get("state").has("input")) {
                            JsonNode inputNode = part.get("state").get("input");
                            // Use the description if available, otherwise show command/args
                            if (inputNode.has("description")) {
                                input = inputNode.get("description").asText();
                            } else if (inputNode.has("command")) {
                                input = inputNode.get("command").asText();
                            } else {
                                input = inputNode.toString();
                            }
                        }
                        return new ToolUse(tool, input);
                    }
                    return null;
                }
                case "step_finish" -> {
                    JsonNode part = node.get("part");
                    if (part != null && part.has("tokens")) {
                        JsonNode tokens = part.get("tokens");
                        long input = tokens.has("input") ? tokens.get("input").asLong() : 0;
                        long output = tokens.has("output") ? tokens.get("output").asLong() : 0;
                        long cacheRead = 0;
                        long cacheWrite = 0;
                        if (tokens.has("cache")) {
                            JsonNode cache = tokens.get("cache");
                            cacheRead = cache.has("read") ? cache.get("read").asLong() : 0;
                            cacheWrite = cache.has("write") ? cache.get("write").asLong() : 0;
                        }
                        return new TokenUsage(input, output, cacheRead, cacheWrite);
                    }
                    double cost = 0.0;
                    if (part != null && part.has("cost")) {
                        cost = part.get("cost").asDouble();
                    }
                    return new TurnComplete(0, cost, 0);
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            // Not valid JSON — TUI noise (spinner, progress bars) from opencode.
            // All real content arrives as JSON events with --format json.
            return null;
        }
    }

    /**
     * Parse a line of Gemini/Qwen stream-json output ({@code -o stream-json}).
     * <p>
     * These agents share the same output format:
     * <ul>
     *   <li>{@code init} — session start with {@code session_id}, {@code model}</li>
     *   <li>{@code message} with {@code role: "assistant"} and {@code delta: true} — text chunk</li>
     *   <li>{@code tool_use} — tool invocation with {@code tool_name}, {@code parameters}</li>
     *   <li>{@code tool_result} — tool completion</li>
     *   <li>{@code result} — turn end with {@code stats} (tokens, duration, cost)</li>
     * </ul>
     */
    public PassthroughEvent parseGeminiLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "init" -> {
                    if (node.has("session_id")) {
                        return new SessionInit(node.get("session_id").asText());
                    }
                    return null;
                }
                case "message" -> {
                    String role = node.has("role") ? node.get("role").asText() : "";
                    if ("assistant".equals(role) && node.has("content")) {
                        return new TextChunk(node.get("content").asText());
                    }
                    // Skip user messages and non-delta messages
                    return null;
                }
                case "tool_use" -> {
                    String toolName = node.has("tool_name") ? node.get("tool_name").asText() : "unknown";
                    String params = node.has("parameters") ? node.get("parameters").toString() : "";
                    return new ToolUse(toolName, params);
                }
                case "tool_result" -> {
                    // Tool completion — skip (result displayed via tool_use)
                    return null;
                }
                case "result" -> {
                    long durationMs = 0;
                    double cost = 0.0;
                    int toolCallCount = 0;
                    if (node.has("stats")) {
                        JsonNode stats = node.get("stats");
                        if (stats.has("duration_ms")) durationMs = stats.get("duration_ms").asLong();
                        if (stats.has("tool_calls")) toolCallCount = stats.get("tool_calls").asInt();
                    }
                    return new TurnComplete(durationMs, cost, toolCallCount > 0 ? 1 : 0);
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            // Not valid JSON — suppress known noise lines from Gemini/Qwen stderr
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !isGeminiNoiseLine(trimmed)) {
                return new TextChunk(trimmed);
            }
            return null;
        }
    }

    /** Lines that Gemini/Qwen write to stderr that we should suppress. */
    private static boolean isGeminiNoiseLine(String line) {
        return line.startsWith("YOLO mode is enabled")
                || line.startsWith("Attempt ") && line.contains("failed:")
                || line.startsWith("Retrying after");
    }

    /**
     * Parse a line of Codex JSONL output ({@code --json}).
     * <p>
     * Codex event types:
     * <ul>
     *   <li>{@code thread.started} — session start with {@code thread_id}</li>
     *   <li>{@code turn.started} / {@code turn.completed} — turn boundaries</li>
     *   <li>{@code message.delta} — text chunk with {@code delta}</li>
     *   <li>{@code exec.started} / {@code exec.completed} — tool execution</li>
     *   <li>{@code error} — error with {@code message}</li>
     *   <li>{@code turn.failed} — turn failure with error</li>
     * </ul>
     */
    public PassthroughEvent parseCodexLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(line);

            // Codex may wrap events in {"id": "...", "msg": {...}} envelope
            JsonNode node = root;
            if (root.has("msg") && root.get("msg").isObject()) {
                node = root.get("msg");
            }

            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "thread.started" -> {
                    if (node.has("thread_id")) {
                        return new SessionInit(node.get("thread_id").asText());
                    }
                    return null;
                }
                case "message.delta", "agent_message_delta", "response.output_text.delta" -> {
                    String text = firstText(node, "delta", "text", "content");
                    return text != null && !text.isEmpty() ? new TextChunk(text) : null;
                }
                case "message.completed", "agent_message", "response.output_text.done" -> {
                    String text = extractCodexText(node);
                    return text != null && !text.isEmpty() ? new TextChunk(text) : null;
                }
                case "exec.started" -> {
                    String command = "";
                    if (node.has("command")) {
                        command = node.get("command").asText();
                    } else if (node.has("args")) {
                        command = node.get("args").toString();
                    }
                    return new ToolUse("exec", command);
                }
                case "exec.completed" -> {
                    // Tool result — skip
                    return null;
                }
                case "exec_approval_request" -> {
                    String callId = node.has("call_id") ? node.get("call_id").asText() : "";
                    String turnId = node.has("turn_id") ? node.get("turn_id").asText() : "";
                    // Command can be a string or array
                    String command = "";
                    if (node.has("command")) {
                        JsonNode cmdNode = node.get("command");
                        if (cmdNode.isArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode c : cmdNode) {
                                if (sb.length() > 0) sb.append(" ");
                                sb.append(c.asText());
                            }
                            command = sb.toString();
                        } else {
                            command = cmdNode.asText();
                        }
                    }
                    String cwd = node.has("cwd") ? node.get("cwd").asText() : "";
                    String reason = node.has("reason") ? node.get("reason").asText() : "";
                    List<String> decisions = new ArrayList<>();
                    if (node.has("available_decisions") && node.get("available_decisions").isArray()) {
                        for (JsonNode d : node.get("available_decisions")) {
                            decisions.add(d.asText());
                        }
                    }
                    if (decisions.isEmpty()) {
                        decisions.add("approve");
                        decisions.add("deny");
                    }
                    return new InteractiveApproval(callId, turnId, command, cwd, reason, decisions);
                }
                case "request_user_input" -> {
                    String callId = node.has("call_id") ? node.get("call_id").asText() : "";
                    String turnId = node.has("turn_id") ? node.get("turn_id").asText() : "";
                    if (node.has("questions") && node.get("questions").isArray()) {
                        for (JsonNode q : node.get("questions")) {
                            String qId = q.has("id") ? q.get("id").asText() : "";
                            String header = q.has("header") ? q.get("header").asText() : "";
                            String question = q.has("question") ? q.get("question").asText() : "";
                            boolean isOther = q.has("isOther") && q.get("isOther").asBoolean();
                            List<QuestionOption> options = new ArrayList<>();
                            if (q.has("options") && q.get("options").isArray()) {
                                for (JsonNode opt : q.get("options")) {
                                    String label = opt.has("label") ? opt.get("label").asText() : "";
                                    String desc = opt.has("description") ? opt.get("description").asText() : "";
                                    options.add(new QuestionOption(label, desc));
                                }
                            }
                            return new InteractiveQuestion(callId, turnId, qId, header, question, options, isOther);
                        }
                    }
                    return null;
                }
                case "error" -> {
                    String msg = node.has("message") ? node.get("message").asText() : "Unknown error";
                    return new TextChunk("[Error] " + msg);
                }
                case "turn.failed" -> {
                    String msg = "Turn failed";
                    if (node.has("error") && node.get("error").has("message")) {
                        msg = node.get("error").get("message").asText();
                    }
                    return new TextChunk("[Error] " + msg);
                }
                case "item.started" -> {
                    JsonNode item = node.get("item");
                    if (item != null) {
                        String itemType = item.has("type") ? item.get("type").asText() : "";
                        String itemId = item.has("id") ? item.get("id").asText() : "";
                        if ("command_execution".equals(itemType)) {
                            String command = item.has("command") ? item.get("command").asText() : "";
                            String aggregated = item.has("aggregated_output") ? item.get("aggregated_output").asText() : "";
                            codexAggregatedOutputs.put(itemId, aggregated);
                            return new ToolUse("exec", command);
                        } else if ("agent_message".equals(itemType) && !itemId.isEmpty()) {
                            codexAgentMessages.put(itemId, extractCodexText(item, ""));
                        }
                    }
                    return null;
                }
                case "item.updated" -> {
                    JsonNode item = node.get("item");
                    if (item != null) {
                        String itemType = item.has("type") ? item.get("type").asText() : "";
                        String itemId = item.has("id") ? item.get("id").asText() : "";
                        if ("command_execution".equals(itemType) && item.has("aggregated_output")) {
                            String newAggregated = item.get("aggregated_output").asText();
                            String previous = codexAggregatedOutputs.getOrDefault(itemId, "");
                            codexAggregatedOutputs.put(itemId, newAggregated);
                            // Emit delta: new chars since last snapshot
                            String delta = newAggregated.substring(Math.min(previous.length(), newAggregated.length()));
                            if (!delta.isEmpty()) {
                                return new ToolOutput(delta);
                            }
                        } else if ("agent_message".equals(itemType)) {
                            return codexAgentMessageDelta(itemId, item, false);
                        }
                    }
                    return null;
                }
                case "item.completed" -> {
                    JsonNode item = node.get("item");
                    if (item != null) {
                        String itemType = item.has("type") ? item.get("type").asText() : "";
                        String itemId = item.has("id") ? item.get("id").asText() : "";
                        if ("agent_message".equals(itemType)) {
                            return codexAgentMessageDelta(itemId, item, true);
                        } else if ("command_execution".equals(itemType)) {
                            String aggregated = item.has("aggregated_output") ? item.get("aggregated_output").asText() : "";
                            int exitCode = item.has("exit_code") && !item.get("exit_code").isNull()
                                    ? item.get("exit_code").asInt() : -1;
                            String previous = codexAggregatedOutputs.getOrDefault(itemId, "");
                            codexAggregatedOutputs.remove(itemId);
                            // Emit delta output
                            String delta = aggregated.substring(Math.min(previous.length(), aggregated.length()));
                            return new ToolComplete("exec", delta, exitCode);
                        }
                    }
                    return null;
                }
                case "turn.completed" -> {
                    long durationMs = 0;
                    double cost = 0.0;
                    long inputTokens = 0;
                    long outputTokens = 0;
                    long cacheReadTokens = 0;
                    long cacheCreationTokens = 0;
                    if (node.has("usage")) {
                        JsonNode usage = node.get("usage");
                        inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asLong() : 0;
                        outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asLong() : 0;
                        cacheReadTokens = usage.has("cached_input_tokens") ? usage.get("cached_input_tokens").asLong() : 0;
                    }
                    return new TurnComplete(durationMs, cost, 0, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens);
                }
                case "turn.started" -> {
                    return null; // Skip
                }
                case "response_item" -> {
                    String text = extractCodexText(node.has("payload") ? node.get("payload") : node);
                    return text != null && !text.isEmpty() ? new TextChunk(text) : null;
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            // Not valid JSON — could be a plain-text warning
            String trimmed = line.trim();
            if (!trimmed.isEmpty()
                    && !trimmed.equals("Reading additional input from stdin...")
                    && !trimmed.equals("Reading prompt from stdin...")) {
                return new TextChunk(trimmed);
            }
            return null;
        }
    }

    private PassthroughEvent codexAgentMessageDelta(String itemId, JsonNode item, boolean completed) {
        String text = extractCodexText(item);
        if (text == null) {
            return null;
        }

        String previous = itemId != null && !itemId.isEmpty()
                ? codexAgentMessages.getOrDefault(itemId, "") : "";
        String delta = text.substring(Math.min(previous.length(), text.length()));
        if (itemId != null && !itemId.isEmpty()) {
            if (completed) {
                codexAgentMessages.remove(itemId);
            } else {
                codexAgentMessages.put(itemId, text);
            }
        }
        return !delta.isEmpty() ? new TextChunk(delta) : null;
    }

    private String extractCodexText(JsonNode node) {
        return extractCodexText(node, null);
    }

    private String extractCodexText(JsonNode node, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        String direct = firstText(node, "text", "delta", "content", "message");
        if (direct != null) {
            return direct;
        }
        if (node.has("message") && node.get("message").isObject()) {
            String fromMessage = extractCodexText(node.get("message"));
            if (fromMessage != null) return fromMessage;
        }
        if (node.has("content") && node.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node.get("content")) {
                String partText = firstText(part, "text", "content", "delta");
                if (partText != null) sb.append(partText);
            }
            if (sb.length() > 0) return sb.toString();
        }
        if (node.has("payload") && node.get("payload").isObject()) {
            String fromPayload = extractCodexText(node.get("payload"));
            if (fromPayload != null) return fromPayload;
        }
        return defaultValue;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Parse an OpenCode ask_user tool_use event into an InteractiveQuestion.
     * The tool input may contain question text and options.
     */
    private PassthroughEvent parseOpenCodeAskUser(JsonNode part) {
        String tool = part.has("tool") ? part.get("tool").asText() : "ask_user";
        String callId = part.has("callID") ? part.get("callID").asText()
                : part.has("call_id") ? part.get("call_id").asText() : "";
        String question = "";
        List<QuestionOption> options = new ArrayList<>();
        boolean freeform = true;

        if (part.has("state") && part.get("state").has("input")) {
            JsonNode inputNode = part.get("state").get("input");
            if (inputNode.has("question")) {
                question = inputNode.get("question").asText();
            } else if (inputNode.has("text")) {
                question = inputNode.get("text").asText();
            } else if (inputNode.isTextual()) {
                question = inputNode.asText();
            }
            // Options may be in "options" or "choices"
            JsonNode optionsNode = inputNode.has("options") ? inputNode.get("options")
                    : inputNode.has("choices") ? inputNode.get("choices") : null;
            if (optionsNode != null && optionsNode.isArray()) {
                for (JsonNode opt : optionsNode) {
                    if (opt.isTextual()) {
                        options.add(new QuestionOption(opt.asText(), ""));
                    } else {
                        String label = opt.has("label") ? opt.get("label").asText() : opt.asText();
                        String desc = opt.has("description") ? opt.get("description").asText() : "";
                        options.add(new QuestionOption(label, desc));
                    }
                }
            }
        }

        if (question.isEmpty()) {
            question = "The agent is requesting input";
        }
        return new InteractiveQuestion(callId, "", "", "", question, options, freeform);
    }

    /**
     * Check if a Claude tool_use block is an AskUserQuestion and parse it.
     * Returns an InteractiveQuestion if it is, null otherwise.
     */
    PassthroughEvent parseClaudeAskUser(JsonNode block) {
        String name = block.has("name") ? block.get("name").asText() : "";
        if (!"AskUserQuestion".equalsIgnoreCase(name)) return null;

        String callId = block.has("id") ? block.get("id").asText() : "";
        JsonNode input = block.has("input") ? block.get("input") : null;
        if (input == null) {
            return new InteractiveQuestion(callId, "", "", "", "The agent is requesting input",
                    new ArrayList<>(), true);
        }

        // Claude AskUserQuestion format: {questions: [{question, header, options: [{label, description}]}]}
        if (input.has("questions") && input.get("questions").isArray()) {
            for (JsonNode q : input.get("questions")) {
                String header = q.has("header") ? q.get("header").asText() : "";
                String question = q.has("question") ? q.get("question").asText() : "";
                boolean multiSelect = q.has("multiSelect") && q.get("multiSelect").asBoolean();
                List<QuestionOption> options = new ArrayList<>();
                if (q.has("options") && q.get("options").isArray()) {
                    for (JsonNode opt : q.get("options")) {
                        String label = opt.has("label") ? opt.get("label").asText() : "";
                        String desc = opt.has("description") ? opt.get("description").asText() : "";
                        options.add(new QuestionOption(label, desc));
                    }
                }
                return new InteractiveQuestion(callId, "", "", header, question, options, true);
            }
        }

        // Simple format: {question: "...", options: [...]}
        String question = input.has("question") ? input.get("question").asText() : "The agent is requesting input";
        List<QuestionOption> options = new ArrayList<>();
        if (input.has("options") && input.get("options").isArray()) {
            for (JsonNode opt : input.get("options")) {
                if (opt.isTextual()) {
                    options.add(new QuestionOption(opt.asText(), ""));
                } else {
                    String label = opt.has("label") ? opt.get("label").asText() : "";
                    String desc = opt.has("description") ? opt.get("description").asText() : "";
                    options.add(new QuestionOption(label, desc));
                }
            }
        }
        return new InteractiveQuestion(callId, "", "", "", question, options, true);
    }

    /**
     * Multi-event variant of {@link #parseOpenCodeLine(String)}.
     * A single {@code tool_use} line with {@code state.status=completed} produces
     * both a {@link ToolUse} and a {@link ToolComplete} event.
     * A {@code step_finish} line with token data produces a {@link TokenUsage} event.
     */
    public List<PassthroughEvent> parseOpenCodeLineMulti(String line) {
        if (line == null || line.isBlank()) return List.of();

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            if ("tool_use".equals(type)) {
                JsonNode part = node.get("part");
                if (part != null) {
                    String tool = part.has("tool") ? part.get("tool").asText() : "unknown";
                    // Detect ask_user tools — delegate to single-event parser
                    String toolLower = tool.toLowerCase();
                    if (toolLower.contains("ask_user") || toolLower.contains("askuserquestion")
                            || toolLower.contains("question")) {
                        PassthroughEvent event = parseOpenCodeAskUser(part);
                        return event != null ? List.of(event) : List.of();
                    }

                    List<PassthroughEvent> events = new ArrayList<>();
                    String input = "";
                    String output = "";
                    int exitCode = -1;
                    boolean completed = false;

                    if (part.has("state")) {
                        JsonNode state = part.get("state");
                        if (state.has("input")) {
                            JsonNode inputNode = state.get("input");
                            if (inputNode.has("description")) {
                                input = inputNode.get("description").asText();
                            } else if (inputNode.has("command")) {
                                input = inputNode.get("command").asText();
                            } else {
                                input = inputNode.toString();
                            }
                        }
                        if (state.has("output")) {
                            output = state.get("output").asText();
                        }
                        if (state.has("metadata") && state.get("metadata").has("exit")) {
                            exitCode = state.get("metadata").get("exit").asInt();
                        }
                        completed = "completed".equals(state.has("status") ? state.get("status").asText() : "");
                    }

                    events.add(new ToolUse(tool, input));
                    if (completed) {
                        events.add(new ToolComplete(tool, output, exitCode));
                    }
                    return events;
                }
            }

            // For all other types, delegate to single-event parser
            PassthroughEvent event = parseOpenCodeLine(line);
            return event != null ? List.of(event) : List.of();
        } catch (Exception e) {
            PassthroughEvent event = parseOpenCodeLine(line);
            return event != null ? List.of(event) : List.of();
        }
    }

    /** Tracks last Pi tool output per callId for delta computation. */
    private final Map<String, String> piToolOutputs = new HashMap<>();

    /**
     * Parse a single line of Pi agent JSON output.
     * <p>
     * Pi event types:
     * <ul>
     *   <li>{@code session} — session start with {@code id}</li>
     *   <li>{@code message_update} — text delta via {@code assistantMessageEvent.delta}</li>
     *   <li>{@code message_end} — message completed (suppressed to avoid duplicating deltas)</li>
     *   <li>{@code tool_execution_start} — tool invocation with {@code toolName}, {@code args}</li>
     *   <li>{@code tool_execution_update} — partial tool output</li>
     *   <li>{@code tool_execution_end} — tool completion with {@code result}, {@code exitCode}</li>
     * </ul>
     */
    public PassthroughEvent parsePiLine(String line) {
        if (line == null || line.isBlank()) return null;

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "session" -> {
                    String id = node.has("id") ? node.get("id").asText() : "";
                    return new SessionInit(id);
                }
                case "message_update" -> {
                    JsonNode event = node.get("assistantMessageEvent");
                    if (event != null) {
                        String eventType = event.has("type") ? event.get("type").asText() : "";
                        if ("text_delta".equals(eventType) && event.has("delta")) {
                            return new TextChunk(event.get("delta").asText());
                        }
                    }
                    return null;
                }
                case "message_end" -> {
                    // Suppress — text was already emitted as text_delta events
                    return null;
                }
                case "tool_execution_start" -> {
                    String toolName = node.has("toolName") ? node.get("toolName").asText() : "unknown";
                    String args = node.has("args") ? node.get("args").toString() : "";
                    String callId = node.has("toolCallId") ? node.get("toolCallId").asText() : "";
                    piToolOutputs.put(callId, "");
                    return new ToolUse(toolName, args);
                }
                case "tool_execution_update" -> {
                    String callId = node.has("toolCallId") ? node.get("toolCallId").asText() : "";
                    if (node.has("partialResult") && node.get("partialResult").has("content")) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : node.get("partialResult").get("content")) {
                            if ("text".equals(block.has("type") ? block.get("type").asText() : "")
                                    && block.has("text")) {
                                sb.append(block.get("text").asText());
                            }
                        }
                        String fullOutput = sb.toString();
                        piToolOutputs.put(callId, fullOutput);
                        return new ToolOutput(fullOutput);
                    }
                    return null;
                }
                case "tool_execution_end" -> {
                    String callId = node.has("toolCallId") ? node.get("toolCallId").asText() : "";
                    String toolName = node.has("toolName") ? node.get("toolName").asText() : "unknown";
                    int exitCode = 0;
                    boolean isError = false;
                    String fullOutput = "";

                    if (node.has("result")) {
                        JsonNode result = node.get("result");
                        if (result.has("exitCode") && !result.get("exitCode").isNull()) {
                            exitCode = result.get("exitCode").asInt();
                        }
                        if (result.has("content")) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode block : result.get("content")) {
                                if ("text".equals(block.has("type") ? block.get("type").asText() : "")
                                        && block.has("text")) {
                                    sb.append(block.get("text").asText());
                                }
                            }
                            fullOutput = sb.toString();
                        }
                    }
                    if (node.has("isError")) {
                        isError = node.get("isError").asBoolean();
                    }

                    // Compute delta from last partial output
                    String previous = piToolOutputs.getOrDefault(callId, "");
                    piToolOutputs.remove(callId);
                    String delta = fullOutput.substring(Math.min(previous.length(), fullOutput.length()));

                    return new ToolComplete(toolName, delta, exitCode, isError || exitCode != 0);
                }
                default -> {
                    return null;
                }
            }
        } catch (Exception e) {
            // Not valid JSON — treat as plain text
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return new TextChunk(trimmed);
            }
            return null;
        }
    }

    /**
     * Multi-event variant of {@link #parsePiLine(String)}.
     */
    public List<PassthroughEvent> parsePiLineMulti(String line) {
        if (line == null || line.isBlank()) return List.of();
        PassthroughEvent event = parsePiLine(line);
        return event != null ? List.of(event) : List.of();
    }

    /**
     * Parse a plain text line from a non-Claude agent.
     * Detects prompt patterns that indicate the agent is waiting for input.
     */
    public PassthroughEvent parsePlainLine(String line, Pattern promptPattern) {
        if (line == null) {
            return null;
        }

        if (promptPattern != null && promptPattern.matcher(line).matches()) {
            return new PromptDetected();
        }

        return new TextChunk(line + "\n");
    }
}
