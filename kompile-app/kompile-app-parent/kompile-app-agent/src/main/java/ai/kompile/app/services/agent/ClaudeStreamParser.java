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

package ai.kompile.app.services.agent;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Parses Claude CLI stream-json output format AND Anthropic streaming API format.
 *
 * Supports two formats:
 *
 * 1. Claude CLI stream-json:
 *    - system (subtype: init) - Session initialization
 *    - assistant - Claude's response with content blocks (text, thinking, tool_use)
 *    - user - Tool results echoed back
 *    - result - Final stats (duration, cost, turns)
 *
 * 2. Anthropic Streaming API:
 *    - stream_event wrapper with nested event types:
 *      - message_start, message_delta, message_stop
 *      - content_block_start, content_block_delta, content_block_stop
 *    - Handles thinking_delta and text_delta incrementally
 *
 * Features:
 * - Filters out <system-reminder> tags from output
 * - Tracks modified files from Edit/Write tool calls
 * - Improved tool usage rendering
 */
@Service("appClaudeStreamParser")
public class ClaudeStreamParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStreamParser.class);

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    // Track content blocks by session and index for incremental assembly
    private final Map<String, ContentBlock> contentBlocks = new ConcurrentHashMap<>();

    // Track modified files per session (from Edit/Write tool calls)
    private final Map<String, Set<String>> modifiedFilesPerSession = new ConcurrentHashMap<>();

    // Track token generation metrics per session for hosted API responses
    private final Map<String, TokenSessionMetrics> tokenMetricsPerSession = new ConcurrentHashMap<>();

    // Track last streamed Codex agent_message text per session/item for delta rendering
    private final Map<String, String> codexAgentMessages = new ConcurrentHashMap<>();

    // Track last streamed Codex command output per session/item for delta rendering
    private final Map<String, String> codexCommandOutputs = new ConcurrentHashMap<>();

    /**
     * Tracks token metrics accumulated across streaming events for a session.
     */
    private static class TokenSessionMetrics {
        long messageStartTimeNanos;
        int inputTokens;
        int outputTokens;
        String model;

        TokenSessionMetrics() {
            this.messageStartTimeNanos = System.nanoTime();
        }
    }

    // Pattern to filter out system reminders
    private static final Pattern SYSTEM_REMINDER_PATTERN = Pattern.compile(
        "<system-reminder>.*?</system-reminder>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    /**
     * Tracks state for a content block being assembled from deltas.
     */
    private static class ContentBlock {
        String type; // "thinking" or "text" or "tool_use"
        StringBuilder content = new StringBuilder();
        String toolName;
        String toolId;

        ContentBlock(String type) {
            this.type = type;
        }
    }

    /**
     * Result of parsing a single line of stream-json output.
     */
    public record ParseResult(
        String type,           // Event type: system, assistant, user, result, tool_use, etc.
        String textContent,    // Extracted text for logging/display
        boolean isResult,      // True if this is the final result event
        boolean isError,       // True if result indicates error
        Integer durationMs,    // Duration from result event
        Double costUsd,        // Cost from result event
        Integer numTurns,      // Turn count from result event
        String toolName,       // Tool name if tool_use event
        JsonNode toolInput     // Tool input if tool_use event
    ) {
        public static ParseResult text(String content) {
            return new ParseResult("text", content, false, false, null, null, null, null, null);
        }

        public static ParseResult result(boolean isError, Integer durationMs, Double costUsd, Integer numTurns) {
            return new ParseResult("result", null, true, isError, durationMs, costUsd, numTurns, null, null);
        }

        public static ParseResult event(String type, String content) {
            return new ParseResult(type, content, false, false, null, null, null, null, null);
        }

        public static ParseResult toolUse(String toolName, JsonNode toolInput, String formatted) {
            return new ParseResult("tool_use", formatted, false, false, null, null, null, toolName, toolInput);
        }
    }

    /**
     * Parse a single line of stream-json output.
     * Returns the parsed result or null if no meaningful content.
     *
     * Handles Claude CLI, Anthropic Streaming API, and Codex CLI JSONL formats.
     */
    public ParseResult parseLine(String sessionId, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // Skip non-JSON lines (Claude sometimes outputs plain text on stderr)
        if (!line.trim().startsWith("{")) {
            // Filter system reminders from plain text too
            String filtered = filterSystemReminders(line);
            if (filtered.isBlank()) {
                return null;
            }
            return ParseResult.text(filtered);
        }

        try {
            JsonNode root = objectMapper.readTree(line);
            JsonNode eventRoot = root.has("msg") && root.get("msg").isObject() ? root.get("msg") : root;
            String type = eventRoot.has("type") ? eventRoot.get("type").asText() : null;

            if (type == null) {
                // Unknown format, return as-is
                return ParseResult.text(line);
            }

            return switch (type) {
                // Claude CLI format
                case "system" -> handleSystemEvent(sessionId, eventRoot, line);
                case "assistant" -> handleAssistantEvent(sessionId, eventRoot);
                case "user" -> handleUserEvent(sessionId, eventRoot);
                case "result" -> handleResultEvent(sessionId, eventRoot);

                // Anthropic Streaming API format
                case "stream_event" -> handleStreamEvent(sessionId, eventRoot);

                // Claude CLI content_block_delta (direct stream-json format)
                case "content_block_delta" -> handleContentBlockDelta(sessionId, sessionId, eventRoot);

                // Codex CLI --json format
                case "thread.started", "turn.started" -> null;
                case "message.delta", "agent_message_delta", "response.output_text.delta" ->
                        textResult(firstText(eventRoot, "delta", "text", "content"));
                case "message.completed", "agent_message", "response.output_text.done" ->
                        textResult(extractCodexText(eventRoot));
                case "response_item" -> textResult(extractCodexText(eventRoot.has("payload") ? eventRoot.get("payload") : eventRoot));
                case "item.started" -> handleCodexItemStarted(sessionId, eventRoot);
                case "item.updated" -> handleCodexItemUpdated(sessionId, eventRoot);
                case "item.completed" -> handleCodexItemCompleted(sessionId, eventRoot);
                case "turn.completed" -> ParseResult.result(false, null, null, null);
                case "turn.failed", "error" -> textResult("[Error] " + extractCodexError(eventRoot));

                default -> {
                    // Unknown type, return raw
                    yield ParseResult.event(type, line);
                }
            };

        } catch (JsonProcessingException e) {
            // Not valid JSON, return as plain text
            log.debug("Non-JSON line from Claude: {}", line);
            String filtered = filterSystemReminders(line);
            if (!filtered.isBlank()) {
                return ParseResult.text(filtered);
            }
            return null;
        }
    }

    /**
     * Handle Anthropic Streaming API stream_event wrapper.
     */
    private ParseResult handleStreamEvent(String sessionId, JsonNode root) {
        if (!root.has("event")) {
            return null;
        }

        JsonNode event = root.get("event");
        String eventType = event.has("type") ? event.get("type").asText() : null;
        String sessionKey = root.has("session_id") ? root.get("session_id").asText() : sessionId;

        if (eventType == null) {
            return null;
        }

        return switch (eventType) {
            case "message_start" -> handleMessageStart(sessionId, event);
            case "content_block_start" -> handleContentBlockStart(sessionId, sessionKey, event);
            case "content_block_delta" -> handleContentBlockDelta(sessionId, sessionKey, event);
            case "content_block_stop" -> handleContentBlockStop(sessionId, sessionKey, event);
            case "message_delta" -> handleMessageDelta(sessionId, event);
            case "message_stop" -> handleMessageStop(sessionId);
            default -> null; // Ignore unknown event types
        };
    }

    private ParseResult handleMessageStart(String sessionId, JsonNode event) {
        // Initialize token metrics tracking for this session
        TokenSessionMetrics metrics = new TokenSessionMetrics();
        if (event.has("message")) {
            JsonNode message = event.get("message");
            String model = message.has("model") ? message.get("model").asText() : "unknown";
            String msgId = message.has("id") ? message.get("id").asText() : "";
            metrics.model = model;
            // Extract input tokens from message_start usage if present
            if (message.has("usage")) {
                JsonNode usage = message.get("usage");
                metrics.inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
            }
            log.debug("Message start: model={}, id={}", model, msgId);
        }
        tokenMetricsPerSession.put(sessionId, metrics);
        return ParseResult.event("message_start", null);
    }

    private ParseResult handleContentBlockStart(String sessionId, String sessionKey, JsonNode event) {
        int index = event.has("index") ? event.get("index").asInt() : 0;
        String blockKey = sessionKey + ":" + index;

        if (event.has("content_block")) {
            JsonNode block = event.get("content_block");
            String blockType = block.has("type") ? block.get("type").asText() : "text";

            ContentBlock contentBlock = new ContentBlock(blockType);
            contentBlocks.put(blockKey, contentBlock);

            // For tool_use blocks, capture the name and id immediately
            if ("tool_use".equals(blockType)) {
                contentBlock.toolName = block.has("name") ? block.get("name").asText() : "unknown";
                contentBlock.toolId = block.has("id") ? block.get("id").asText() : "";
            }
        }
        return null;
    }

    private ParseResult handleContentBlockDelta(String sessionId, String sessionKey, JsonNode event) {
        int index = event.has("index") ? event.get("index").asInt() : 0;
        String blockKey = sessionKey + ":" + index;

        JsonNode delta = event.has("delta") ? event.get("delta") : event;
        if (delta == null) {
            return null;
        }

        String deltaType = delta.has("type") ? delta.get("type").asText() : "";

        ContentBlock block = contentBlocks.get(blockKey);
        if (block == null) {
            // Create block on-the-fly if we missed the start
            String inferredType = deltaType.replace("_delta", "");
            block = new ContentBlock(inferredType);
            contentBlocks.put(blockKey, block);
        }

        switch (deltaType) {
            case "thinking_delta" -> {
                String thinking = delta.has("thinking") ? delta.get("thinking").asText() : "";
                if (!thinking.isEmpty()) {
                    block.content.append(thinking);
                    return ParseResult.text(thinking);
                }
            }
            case "text_delta" -> {
                String text = delta.has("text") ? delta.get("text").asText() : "";
                if (!text.isEmpty()) {
                    // Filter out system reminders
                    String filtered = filterSystemReminders(text);
                    if (!filtered.isEmpty()) {
                        block.content.append(filtered);
                        return ParseResult.text(filtered);
                    }
                }
            }
            case "input_json_delta" -> {
                // Tool input JSON being streamed - accumulate but don't return
                String partial = delta.has("partial_json") ? delta.get("partial_json").asText() : "";
                if (!partial.isEmpty()) {
                    block.content.append(partial);
                }
            }
        }

        return null;
    }

    private ParseResult handleContentBlockStop(String sessionId, String sessionKey, JsonNode event) {
        int index = event.has("index") ? event.get("index").asInt() : 0;
        String blockKey = sessionKey + ":" + index;

        ContentBlock block = contentBlocks.remove(blockKey);
        if (block == null) {
            return null;
        }

        String content = block.content.toString();

        // Handle completed blocks based on type
        return switch (block.type) {
            case "thinking" -> ParseResult.text(content);
            case "tool_use" -> {
                // Tool invocation complete
                String toolOutput = formatToolUse(block.toolName, block.toolId, content);
                JsonNode input = null;
                try {
                    input = content.isEmpty() ? null : objectMapper.readTree(content);

                    // Track modified files from Edit/Write tools
                    if (input != null && ("Edit".equals(block.toolName) || "Write".equals(block.toolName))) {
                        if (input.has("file_path")) {
                            String filePath = input.get("file_path").asText();
                            trackModifiedFile(sessionId, filePath);
                        }
                    }
                } catch (JsonProcessingException e) {
                    log.debug("Could not parse tool input as JSON: {}", content);
                }
                yield ParseResult.toolUse(block.toolName, input, toolOutput);
            }
            case "text" -> ParseResult.text(content);
            default -> ParseResult.text(content);
        };
    }

    private ParseResult handleMessageDelta(String sessionId, JsonNode event) {
        if (event.has("delta")) {
            JsonNode delta = event.get("delta");
            String stopReason = delta.has("stop_reason") ? delta.get("stop_reason").asText() : null;
            if (stopReason != null) {
                log.debug("Message stop reason: {}", stopReason);
            }
        }
        if (event.has("usage")) {
            JsonNode usage = event.get("usage");
            int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
            // Accumulate output tokens into session metrics
            TokenSessionMetrics metrics = tokenMetricsPerSession.get(sessionId);
            if (metrics != null) {
                metrics.outputTokens = outputTokens; // Anthropic sends cumulative count
            }
            log.debug("Output tokens: {}", outputTokens);
        }
        return null;
    }

    private ParseResult handleMessageStop(String sessionId) {
        // Clear any remaining content blocks for this session
        contentBlocks.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        return ParseResult.event("message_stop", null);
    }

    /**
     * Format tool use for display.
     * Provides clean, readable output without excessive JSON noise.
     */
    private String formatToolUse(String toolName, String toolId, String inputJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n[").append(toolName).append("] ");

        if (inputJson == null || inputJson.isEmpty()) {
            sb.append("\n");
            return sb.toString();
        }

        try {
            JsonNode parsed = objectMapper.readTree(inputJson);

            // Format based on tool type for cleaner output
            switch (toolName) {
                case "Edit", "Read", "Write" -> {
                    String filePath = parsed.has("file_path") ? parsed.get("file_path").asText() : "";
                    sb.append(filePath).append("\n");
                }
                case "Bash" -> {
                    String command = parsed.has("command") ? parsed.get("command").asText() : "";
                    // Truncate long commands
                    if (command.length() > 200) {
                        command = command.substring(0, 200) + "...";
                    }
                    sb.append(command).append("\n");
                }
                case "Grep" -> {
                    String pattern = parsed.has("pattern") ? parsed.get("pattern").asText() : "";
                    String path = parsed.has("path") ? parsed.get("path").asText() : ".";
                    sb.append("'").append(pattern).append("' in ").append(path).append("\n");
                }
                case "Glob" -> {
                    String pattern = parsed.has("pattern") ? parsed.get("pattern").asText() : "";
                    sb.append(pattern).append("\n");
                }
                default -> {
                    // For unknown tools, show compact JSON
                    sb.append(objectMapper.writeValueAsString(parsed)).append("\n");
                }
            }
        } catch (JsonProcessingException e) {
            sb.append(inputJson).append("\n");
        }

        return sb.toString();
    }

    /**
     * Filter out <system-reminder> tags from text.
     */
    private String filterSystemReminders(String text) {
        if (text == null) {
            return "";
        }
        return SYSTEM_REMINDER_PATTERN.matcher(text).replaceAll("").trim();
    }

    private ParseResult handleCodexItemStarted(String sessionId, JsonNode root) {
        JsonNode item = root.get("item");
        if (item == null || !item.isObject()) {
            return null;
        }
        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");
        String key = codexKey(sessionId, itemId);
        if ("agent_message".equals(itemType) && !itemId.isEmpty()) {
            codexAgentMessages.put(key, extractCodexText(item, ""));
        } else if ("command_execution".equals(itemType)) {
            codexCommandOutputs.put(key, item.path("aggregated_output").asText(""));
            String command = item.path("command").asText("");
            return ParseResult.event("tool_use", command.isEmpty() ? null : "\n[exec] " + command + "\n");
        }
        return null;
    }

    private ParseResult handleCodexItemUpdated(String sessionId, JsonNode root) {
        JsonNode item = root.get("item");
        if (item == null || !item.isObject()) {
            return null;
        }
        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");
        if ("agent_message".equals(itemType)) {
            return codexTextDelta(sessionId, itemId, item, false);
        }
        if ("command_execution".equals(itemType) && item.has("aggregated_output")) {
            String key = codexKey(sessionId, itemId);
            String current = item.path("aggregated_output").asText("");
            String previous = codexCommandOutputs.getOrDefault(key, "");
            codexCommandOutputs.put(key, current);
            String delta = current.substring(Math.min(previous.length(), current.length()));
            return textResult(delta);
        }
        return null;
    }

    private ParseResult handleCodexItemCompleted(String sessionId, JsonNode root) {
        JsonNode item = root.get("item");
        if (item == null || !item.isObject()) {
            return null;
        }
        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");
        if ("agent_message".equals(itemType)) {
            return codexTextDelta(sessionId, itemId, item, true);
        }
        if ("command_execution".equals(itemType)) {
            String key = codexKey(sessionId, itemId);
            String current = item.path("aggregated_output").asText("");
            String previous = codexCommandOutputs.getOrDefault(key, "");
            codexCommandOutputs.remove(key);
            String delta = current.substring(Math.min(previous.length(), current.length()));
            return textResult(delta);
        }
        return null;
    }

    private ParseResult codexTextDelta(String sessionId, String itemId, JsonNode item, boolean completed) {
        String text = extractCodexText(item);
        if (text == null) {
            return null;
        }
        String key = codexKey(sessionId, itemId);
        String previous = itemId != null && !itemId.isEmpty()
                ? codexAgentMessages.getOrDefault(key, "") : "";
        String delta = text.substring(Math.min(previous.length(), text.length()));
        if (itemId != null && !itemId.isEmpty()) {
            if (completed) {
                codexAgentMessages.remove(key);
            } else {
                codexAgentMessages.put(key, text);
            }
        }
        return textResult(delta);
    }

    private ParseResult textResult(String text) {
        return text != null && !text.isEmpty() ? ParseResult.text(text) : null;
    }

    private String extractCodexError(JsonNode root) {
        if (root == null) return "Unknown error";
        String message = firstText(root, "message", "error");
        if (message != null) return message;
        JsonNode error = root.get("error");
        if (error != null && error.isObject()) {
            message = firstText(error, "message", "error");
            if (message != null) return message;
        }
        return "Unknown error";
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

    private String codexKey(String sessionId, String itemId) {
        return sessionId + ":" + (itemId != null ? itemId : "");
    }

    /**
     * Get token generation metrics for a session.
     * Returns a map with: outputTokens, inputTokens, totalGenerationMs, tokensPerSecond, model.
     * Returns null if no metrics are available.
     */
    public Map<String, Object> getTokenMetrics(String sessionId) {
        TokenSessionMetrics metrics = tokenMetricsPerSession.get(sessionId);
        if (metrics == null || metrics.outputTokens <= 0) {
            return null;
        }
        long totalMs = (System.nanoTime() - metrics.messageStartTimeNanos) / 1_000_000;
        double tokensPerSecond = totalMs > 0
                ? (metrics.outputTokens * 1000.0) / totalMs : 0.0;
        return Map.of(
                "outputTokens", metrics.outputTokens,
                "inputTokens", metrics.inputTokens,
                "totalGenerationMs", totalMs,
                "tokensPerSecond", Math.round(tokensPerSecond * 100.0) / 100.0,
                "model", metrics.model != null ? metrics.model : "unknown"
        );
    }

    /**
     * Clear all tracked content blocks for a session.
     * Call this when a session ends.
     */
    public void clearSession(String sessionId) {
        contentBlocks.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        tokenMetricsPerSession.remove(sessionId);
        codexAgentMessages.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
        codexCommandOutputs.keySet().removeIf(key -> key.startsWith(sessionId + ":"));
    }

    /**
     * Track a file that was modified by the session.
     */
    private void trackModifiedFile(String sessionId, String filePath) {
        if (sessionId != null && filePath != null && !filePath.isEmpty()) {
            modifiedFilesPerSession.computeIfAbsent(sessionId, k -> new LinkedHashSet<>()).add(filePath);
            log.debug("Tracked modified file for session {}: {}", sessionId, filePath);
        }
    }

    /**
     * Get all files modified during a session.
     * @param sessionId The session ID
     * @return List of file paths that were modified (Edit/Write tools), or empty list
     */
    public List<String> getModifiedFiles(String sessionId) {
        Set<String> files = modifiedFilesPerSession.get(sessionId);
        return files != null ? new ArrayList<>(files) : new ArrayList<>();
    }

    /**
     * Clear tracked modified files for a session.
     * Call this when a session ends and files have been retrieved.
     */
    public void clearModifiedFiles(String sessionId) {
        modifiedFilesPerSession.remove(sessionId);
    }

    private ParseResult handleSystemEvent(String sessionId, JsonNode root, String rawLine) {
        String subtype = root.has("subtype") ? root.get("subtype").asText() : "";

        if ("init".equals(subtype)) {
            String claudeSessionId = root.has("session_id") ? root.get("session_id").asText() : null;
            log.info("Claude session initialized: {}", claudeSessionId);
            return ParseResult.event("init", "Session initialized");
        }

        return ParseResult.event("system", rawLine);
    }

    private ParseResult handleAssistantEvent(String sessionId, JsonNode root) {
        StringBuilder textContent = new StringBuilder();

        if (root.has("message") && root.get("message").has("content")) {
            JsonNode contentArray = root.get("message").get("content");

            for (JsonNode block : contentArray) {
                String blockType = block.has("type") ? block.get("type").asText() : "";

                switch (blockType) {
                    case "text" -> {
                        String text = block.has("text") ? block.get("text").asText() : "";
                        // Filter out system reminders
                        String filtered = filterSystemReminders(text);
                        if (!filtered.isEmpty()) {
                            textContent.append(filtered);
                        }
                    }
                    case "thinking" -> {
                        String thinking = block.has("thinking") ? block.get("thinking").asText() : "";
                        if (!thinking.isEmpty()) {
                            textContent.append(thinking);
                        }
                    }
                    case "tool_use" -> {
                        String toolName = block.has("name") ? block.get("name").asText() : "";
                        String toolId = block.has("id") ? block.get("id").asText() : "";
                        JsonNode input = block.has("input") ? block.get("input") : null;

                        // Track modified files from Edit/Write tools
                        if (input != null && ("Edit".equals(toolName) || "Write".equals(toolName))) {
                            if (input.has("file_path")) {
                                String filePath = input.get("file_path").asText();
                                trackModifiedFile(sessionId, filePath);
                            }
                        }

                        // Format tool use nicely
                        String toolOutput = formatToolUse(toolName, toolId,
                            input != null ? input.toString() : "");
                        textContent.append(toolOutput).append("\n");
                    }
                    default -> {
                        // Unknown block type, log it
                        log.debug("Unknown content block type: {}", blockType);
                    }
                }
            }
        }

        return ParseResult.text(textContent.toString());
    }

    private ParseResult handleUserEvent(String sessionId, JsonNode root) {
        StringBuilder textContent = new StringBuilder();

        if (root.has("message") && root.get("message").has("content")) {
            JsonNode contentArray = root.get("message").get("content");

            for (JsonNode block : contentArray) {
                String blockType = block.has("type") ? block.get("type").asText() : "";

                if ("tool_result".equals(blockType)) {
                    String toolId = block.has("tool_use_id") ? block.get("tool_use_id").asText() : "";
                    JsonNode content = block.has("content") ? block.get("content") : null;
                    boolean isError = block.has("is_error") && block.get("is_error").asBoolean();

                    // Extract and format tool result content
                    String resultText = extractToolResultText(content);
                    if (!resultText.isEmpty()) {
                        // Truncate very long results for readability
                        String displayText = resultText;
                        if (displayText.length() > 500) {
                            displayText = displayText.substring(0, 500) + "\n... [truncated]";
                        }
                        String formatted = isError ? "ERROR: " + displayText : displayText;
                        textContent.append(formatted).append("\n");
                    }
                }
            }
        }

        return ParseResult.text(textContent.toString());
    }

    private ParseResult handleResultEvent(String sessionId, JsonNode root) {
        boolean isError = root.has("is_error") && root.get("is_error").asBoolean();
        Integer durationMs = root.has("duration_ms") ? root.get("duration_ms").asInt() : null;
        Double costUsd = root.has("total_cost_usd") ? root.get("total_cost_usd").asDouble() : null;
        Integer numTurns = root.has("num_turns") ? root.get("num_turns").asInt() : null;

        log.info("Claude session complete: duration={}ms, cost=${}, turns={}, error={}",
            durationMs, costUsd, numTurns, isError);

        return ParseResult.result(isError, durationMs, costUsd, numTurns);
    }

    private String extractToolResultText(JsonNode content) {
        if (content == null) {
            return "";
        }

        String result;
        if (content.isTextual()) {
            result = content.asText();
        } else if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item.has("type") && "text".equals(item.get("type").asText())) {
                    if (item.has("text")) {
                        sb.append(item.get("text").asText());
                    }
                }
            }
            result = sb.toString();
        } else {
            // Return JSON representation for complex content
            result = content.toString();
        }

        // Filter out system reminders from tool results
        return filterSystemReminders(result);
    }

    public boolean supportsStreamJson(String agentName) {
        if (agentName == null) {
            return true; // Default to Claude
        }
        String lower = agentName.toLowerCase();
        // Claude and Codex emit structured JSON streams; Gemini/Antigravity remains plain text here.
        return !lower.equals("gemini") && !lower.contains("gemini")
                && !lower.equals("agy") && !lower.contains("agy")
                && !lower.contains("antigravity");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANSI / TERMINAL ESCAPE SEQUENCE STRIPPING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Regex covering all common terminal escape sequences:
     * CSI (e.g. \033[0m, \033[?2004l), OSC (\033]...\007),
     * character set selection (\033(B), keypad/app modes (\033> \033=),
     * and string terminators (\033\\).
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\033\\[[0-9;?><]*[a-zA-Z]"
            + "|\033\\].*?(?:\033\\\\|\007)"
            + "|\033[()][0-9A-B]"
            + "|\033[>=<]"
            + "|\033\\\\"
    );

    /**
     * Strip all ANSI/terminal escape sequences from the given string.
     * Used to clean TUI cleanup codes (cursor show, bracketed paste disable, mouse mode resets, etc.)
     * that leak into subprocess output when TUI agents exit.
     */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }
}
