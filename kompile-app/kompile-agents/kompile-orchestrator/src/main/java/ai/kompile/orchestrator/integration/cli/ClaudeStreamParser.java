/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.integration.cli;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Parser for Claude CLI stream-json output format (Orchestrator version).
 *
 * <p>Claude CLI with --output-format stream-json outputs lines in these formats:
 * <ul>
 *   <li>{"type":"system","subtype":"init",...} - Session initialization</li>
 *   <li>{"type":"assistant","content":[...]} - Claude's response with content blocks</li>
 *   <li>{"type":"user","content":[...]} - Tool results echoed back</li>
 *   <li>{"type":"result","duration_ms":...,"cost_usd":...} - Final stats</li>
 * </ul>
 *
 * <p>Content blocks can be:
 * <ul>
 *   <li>{"type":"text","text":"..."} - Plain text response</li>
 *   <li>{"type":"thinking","thinking":"..."} - Claude's thinking (extended thinking)</li>
 *   <li>{"type":"tool_use","name":"...","input":{...}} - Tool/function call</li>
 *   <li>{"type":"tool_result","content":"..."} - Tool result</li>
 * </ul>
 *
 * <p>Also handles Anthropic API streaming format:
 * <ul>
 *   <li>message_start, message_delta, message_stop</li>
 *   <li>content_block_start, content_block_delta, content_block_stop</li>
 * </ul>
 */
@Component("orchestratorClaudeStreamParser")
@Slf4j
public class ClaudeStreamParser {

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    // Track content blocks being assembled per session
    private final Map<String, List<ContentBlock>> sessionBlocks = new ConcurrentHashMap<>();

    // Track modified files per session (from Edit/Write tool calls)
    private final Map<String, Set<String>> sessionModifiedFiles = new ConcurrentHashMap<>();

    // Pattern to filter system reminder tags
    private static final Pattern SYSTEM_REMINDER_PATTERN =
            Pattern.compile("<system-reminder>.*?</system-reminder>", Pattern.DOTALL);

    /**
     * Result of parsing a single line.
     */
    public record ParseResult(
            String type,            // Event type: "text", "thinking", "tool_use", "tool_result", "result", "error"
            String textContent,     // Extracted text content for display
            boolean isResult,       // True if this is the final result event
            boolean isError,        // True if this is an error
            Integer durationMs,     // Duration in ms (for result events)
            Double costUsd,         // Cost in USD (for result events)
            Integer numTurns,       // Number of turns (for result events)
            String toolName,        // Tool name (for tool_use events)
            JsonNode toolInput,     // Tool input JSON (for tool_use events)
            String toolId           // Tool ID for tracking (for tool_use events)
    ) {
        public static ParseResult text(String content) {
            return new ParseResult("text", content, false, false, null, null, null, null, null, null);
        }

        public static ParseResult thinking(String content) {
            return new ParseResult("thinking", content, false, false, null, null, null, null, null, null);
        }

        public static ParseResult toolUse(String toolName, JsonNode input, String toolId) {
            return new ParseResult("tool_use", formatToolUse(toolName, input), false, false,
                    null, null, null, toolName, input, toolId);
        }

        public static ParseResult toolResult(String content) {
            return new ParseResult("tool_result", content, false, false, null, null, null, null, null, null);
        }

        public static ParseResult result(Integer durationMs, Double costUsd, Integer numTurns) {
            return new ParseResult("result", null, true, false, durationMs, costUsd, numTurns, null, null, null);
        }

        public static ParseResult error(String message) {
            return new ParseResult("error", message, false, true, null, null, null, null, null, null);
        }

        private static String formatToolUse(String toolName, JsonNode input) {
            if (input == null) return "[" + toolName + "]";

            // Format based on tool type
            return switch (toolName) {
                case "Edit", "Read", "Write" -> {
                    String filePath = input.has("file_path") ? input.get("file_path").asText() : "unknown";
                    yield "[" + toolName + ": " + filePath + "]";
                }
                case "Bash" -> {
                    String cmd = input.has("command") ? input.get("command").asText() : "";
                    if (cmd.length() > 200) cmd = cmd.substring(0, 200) + "...";
                    yield "[Bash: " + cmd + "]";
                }
                case "Grep" -> {
                    String pattern = input.has("pattern") ? input.get("pattern").asText() : "";
                    String path = input.has("path") ? input.get("path").asText() : ".";
                    yield "[Grep: " + pattern + " in " + path + "]";
                }
                case "Glob" -> {
                    String pattern = input.has("pattern") ? input.get("pattern").asText() : "";
                    yield "[Glob: " + pattern + "]";
                }
                case "Task" -> {
                    String desc = input.has("description") ? input.get("description").asText() : "";
                    yield "[Task: " + desc + "]";
                }
                default -> "[" + toolName + "]";
            };
        }
    }

    /**
     * Internal class to track content block assembly.
     */
    @Getter
    private static class ContentBlock {
        String type;           // "thinking", "text", "tool_use"
        StringBuilder content = new StringBuilder();
        String toolName;
        String toolId;
        JsonNode toolInput;
    }

    /**
     * Parse a single line of Claude CLI output.
     *
     * @param sessionId Session identifier for tracking state
     * @param line The line to parse
     * @return ParseResult with extracted content, or null if line should be skipped
     */
    public ParseResult parseLine(String sessionId, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // Filter out system reminder tags
        line = SYSTEM_REMINDER_PATTERN.matcher(line).replaceAll("");
        if (line.isBlank()) {
            return null;
        }

        // Try to parse as JSON
        try {
            if (line.trim().startsWith("{")) {
                JsonNode json = objectMapper.readTree(line);
                return parseJsonEvent(sessionId, json);
            }
        } catch (Exception e) {
            // Not valid JSON, treat as plain text
            log.trace("Line is not JSON: {}", line);
        }

        // Plain text output
        return ParseResult.text(line);
    }

    /**
     * Parse a JSON event from Claude CLI.
     */
    private ParseResult parseJsonEvent(String sessionId, JsonNode json) {
        String type = json.has("type") ? json.get("type").asText() : null;
        if (type == null) {
            return null;
        }

        return switch (type) {
            case "system" -> parseSystemEvent(json);
            case "assistant" -> parseAssistantEvent(sessionId, json);
            case "user" -> parseUserEvent(json);
            case "result" -> parseResultEvent(json);
            case "error" -> parseErrorEvent(json);

            // Anthropic API streaming events
            case "message_start" -> null; // Just initialization
            case "content_block_start" -> parseContentBlockStart(sessionId, json);
            case "content_block_delta" -> parseContentBlockDelta(sessionId, json);
            case "content_block_stop" -> parseContentBlockStop(sessionId, json);
            case "message_delta" -> parseMessageDelta(json);
            case "message_stop" -> null; // End of message

            default -> {
                log.trace("Unknown event type: {}", type);
                yield null;
            }
        };
    }

    /**
     * Parse system event (initialization).
     */
    private ParseResult parseSystemEvent(JsonNode json) {
        String subtype = json.has("subtype") ? json.get("subtype").asText() : "";
        if ("init".equals(subtype)) {
            log.debug("Claude session initialized");
        }
        return null; // System events are not displayed
    }

    /**
     * Parse assistant event (Claude's response).
     */
    private ParseResult parseAssistantEvent(String sessionId, JsonNode json) {
        if (!json.has("content") || !json.get("content").isArray()) {
            return null;
        }

        StringBuilder textBuilder = new StringBuilder();
        JsonNode contentArray = json.get("content");

        for (JsonNode block : contentArray) {
            String blockType = block.has("type") ? block.get("type").asText() : "text";

            switch (blockType) {
                case "text" -> {
                    String text = block.has("text") ? block.get("text").asText() : "";
                    textBuilder.append(text);
                }
                case "thinking" -> {
                    String thinking = block.has("thinking") ? block.get("thinking").asText() : "";
                    // Could include thinking in output if desired
                    log.trace("Claude thinking: {}", thinking.substring(0, Math.min(100, thinking.length())));
                }
                case "tool_use" -> {
                    String toolName = block.has("name") ? block.get("name").asText() : "unknown";
                    String toolId = block.has("id") ? block.get("id").asText() : null;
                    JsonNode input = block.has("input") ? block.get("input") : null;

                    // Track modified files
                    trackModifiedFile(sessionId, toolName, input);

                    // Return tool use event
                    if (textBuilder.isEmpty()) {
                        return ParseResult.toolUse(toolName, input, toolId);
                    }
                }
            }
        }

        if (!textBuilder.isEmpty()) {
            return ParseResult.text(textBuilder.toString());
        }
        return null;
    }

    /**
     * Parse user event (typically tool results).
     */
    private ParseResult parseUserEvent(JsonNode json) {
        if (!json.has("content") || !json.get("content").isArray()) {
            return null;
        }

        StringBuilder resultBuilder = new StringBuilder();
        for (JsonNode block : json.get("content")) {
            String blockType = block.has("type") ? block.get("type").asText() : "";
            if ("tool_result".equals(blockType)) {
                String content = block.has("content") ? block.get("content").asText() : "";
                // Tool results can be verbose, truncate if needed
                if (content.length() > 1000) {
                    content = content.substring(0, 1000) + "... [truncated]";
                }
                resultBuilder.append(content);
            }
        }

        if (!resultBuilder.isEmpty()) {
            return ParseResult.toolResult(resultBuilder.toString());
        }
        return null;
    }

    /**
     * Parse result event (final stats).
     */
    private ParseResult parseResultEvent(JsonNode json) {
        Integer durationMs = json.has("duration_ms") ? json.get("duration_ms").asInt() : null;
        Double costUsd = json.has("cost_usd") ? json.get("cost_usd").asDouble() : null;
        Integer numTurns = json.has("num_turns") ? json.get("num_turns").asInt() : null;

        log.debug("Claude session completed: duration={}ms, cost=${}, turns={}",
                durationMs, costUsd, numTurns);

        return ParseResult.result(durationMs, costUsd, numTurns);
    }

    /**
     * Parse error event.
     */
    private ParseResult parseErrorEvent(JsonNode json) {
        String message = json.has("message") ? json.get("message").asText() : "Unknown error";
        log.warn("Claude error: {}", message);
        return ParseResult.error(message);
    }

    // ==================== Anthropic API Streaming Events ====================

    /**
     * Parse content_block_start event.
     */
    private ParseResult parseContentBlockStart(String sessionId, JsonNode json) {
        if (!json.has("content_block")) return null;

        JsonNode block = json.get("content_block");
        String blockType = block.has("type") ? block.get("type").asText() : "text";

        ContentBlock contentBlock = new ContentBlock();
        contentBlock.type = blockType;

        if ("tool_use".equals(blockType)) {
            contentBlock.toolName = block.has("name") ? block.get("name").asText() : "unknown";
            contentBlock.toolId = block.has("id") ? block.get("id").asText() : null;
        }

        // Store for assembly
        sessionBlocks.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(contentBlock);

        return null; // Content comes in delta events
    }

    /**
     * Parse content_block_delta event.
     */
    private ParseResult parseContentBlockDelta(String sessionId, JsonNode json) {
        if (!json.has("delta")) return null;

        JsonNode delta = json.get("delta");
        String deltaType = delta.has("type") ? delta.get("type").asText() : "";

        List<ContentBlock> blocks = sessionBlocks.get(sessionId);
        if (blocks == null || blocks.isEmpty()) return null;

        ContentBlock currentBlock = blocks.get(blocks.size() - 1);

        switch (deltaType) {
            case "text_delta" -> {
                String text = delta.has("text") ? delta.get("text").asText() : "";
                currentBlock.content.append(text);
                return ParseResult.text(text);
            }
            case "thinking_delta" -> {
                String thinking = delta.has("thinking") ? delta.get("thinking").asText() : "";
                currentBlock.content.append(thinking);
                return ParseResult.thinking(thinking);
            }
            case "input_json_delta" -> {
                String partial = delta.has("partial_json") ? delta.get("partial_json").asText() : "";
                currentBlock.content.append(partial);
            }
        }

        return null;
    }

    /**
     * Parse content_block_stop event.
     */
    private ParseResult parseContentBlockStop(String sessionId, JsonNode json) {
        List<ContentBlock> blocks = sessionBlocks.get(sessionId);
        if (blocks == null || blocks.isEmpty()) return null;

        ContentBlock completedBlock = blocks.get(blocks.size() - 1);

        if ("tool_use".equals(completedBlock.type)) {
            // Parse the complete tool input JSON
            try {
                String inputJson = completedBlock.content.toString();
                if (!inputJson.isBlank()) {
                    completedBlock.toolInput = objectMapper.readTree(inputJson);
                }
            } catch (Exception e) {
                log.debug("Failed to parse tool input JSON: {}", e.getMessage());
            }

            // Track modified files
            trackModifiedFile(sessionId, completedBlock.toolName, completedBlock.toolInput);

            return ParseResult.toolUse(completedBlock.toolName, completedBlock.toolInput, completedBlock.toolId);
        }

        return null;
    }

    /**
     * Parse message_delta event (contains usage stats).
     */
    private ParseResult parseMessageDelta(JsonNode json) {
        if (json.has("usage")) {
            JsonNode usage = json.get("usage");
            int outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
            log.debug("Message completed: output_tokens={}", outputTokens);
        }
        return null;
    }

    // ==================== Session Management ====================

    /**
     * Track modified files from tool calls.
     */
    private void trackModifiedFile(String sessionId, String toolName, JsonNode input) {
        if (input == null) return;
        if (!"Edit".equals(toolName) && !"Write".equals(toolName)) return;

        if (input.has("file_path")) {
            String filePath = input.get("file_path").asText();
            sessionModifiedFiles.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                    .add(filePath);
            log.debug("Tracked modified file: {}", filePath);
        }
    }

    /**
     * Get files modified during a session.
     */
    public Set<String> getModifiedFiles(String sessionId) {
        return sessionModifiedFiles.getOrDefault(sessionId, Set.of());
    }

    /**
     * Clear session state.
     */
    public void clearSession(String sessionId) {
        sessionBlocks.remove(sessionId);
        sessionModifiedFiles.remove(sessionId);
    }

    /**
     * Parse multiple lines at once (for non-streaming processing).
     */
    public List<ParseResult> parseLines(String sessionId, String output) {
        List<ParseResult> results = new ArrayList<>();

        if (output == null || output.isBlank()) {
            return results;
        }

        for (String line : output.split("\n")) {
            ParseResult result = parseLine(sessionId, line);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Extract text content from multiple parse results.
     */
    public String extractTextContent(List<ParseResult> results) {
        StringBuilder text = new StringBuilder();
        for (ParseResult result : results) {
            if (result.textContent() != null && "text".equals(result.type())) {
                text.append(result.textContent());
            }
        }
        return text.toString();
    }
}
