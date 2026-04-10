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

import java.util.regex.Pattern;

/**
 * Lightweight parser for CLI agent output in passthrough mode.
 * <p>
 * For Claude Code: parses stream-json events (assistant text, tool_use, result).
 * For other agents: passes through plain text and detects prompt patterns for turn boundaries.
 */
public class PassthroughStreamParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parsed event from agent output.
     */
    public interface PassthroughEvent {
    }

    public record TextChunk(String text) implements PassthroughEvent {}
    public record ToolUse(String name, String input) implements PassthroughEvent {}
    public record TurnComplete(long durationMs, double costUsd, int numTurns) implements PassthroughEvent {}
    public record SessionInit(String sessionId) implements PassthroughEvent {}
    public record PromptDetected() implements PassthroughEvent {}

    /**
     * Parse a line of Claude stream-json output.
     * Returns null if the line should be silently skipped.
     */
    public PassthroughEvent parseClaudeLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.has("type") ? node.get("type").asText() : "";

            switch (type) {
                case "system" -> {
                    // Session init
                    if (node.has("session_id")) {
                        return new SessionInit(node.get("session_id").asText());
                    }
                    return null;
                }
                case "assistant" -> {
                    // Extract text content from content blocks
                    if (node.has("message") && node.get("message").has("content")) {
                        StringBuilder text = new StringBuilder();
                        for (JsonNode block : node.get("message").get("content")) {
                            String blockType = block.has("type") ? block.get("type").asText() : "";
                            if ("text".equals(blockType) && block.has("text")) {
                                text.append(block.get("text").asText());
                            } else if ("tool_use".equals(blockType)) {
                                String name = block.has("name") ? block.get("name").asText() : "unknown";
                                String input = block.has("input") ? block.get("input").toString() : "";
                                return new ToolUse(name, input);
                            }
                        }
                        if (text.length() > 0) {
                            return new TextChunk(text.toString());
                        }
                    }
                    // Incremental content_block_delta
                    if (node.has("content_block") && node.get("content_block").has("text")) {
                        return new TextChunk(node.get("content_block").get("text").asText());
                    }
                    return null;
                }
                case "result" -> {
                    long duration = node.has("duration_ms") ? node.get("duration_ms").asLong() : 0;
                    double cost = node.has("cost_usd") ? node.get("cost_usd").asDouble() : 0.0;
                    int turns = node.has("num_turns") ? node.get("num_turns").asInt() : 0;
                    return new TurnComplete(duration, cost, turns);
                }
                default -> {
                    // For content_block types sent as top-level events
                    if (node.has("content_block")) {
                        JsonNode cb = node.get("content_block");
                        if (cb.has("text")) {
                            return new TextChunk(cb.get("text").asText());
                        }
                    }
                    return null;
                }
            }
        } catch (Exception e) {
            // Not valid JSON - treat as plain text
            return new TextChunk(line);
        }
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
