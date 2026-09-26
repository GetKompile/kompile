/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateful decoder for the {@code claude -p --output-format stream-json} protocol
 * used by Standard Chat. Deliberately separate from the passthrough decoder:
 * provider-side tools are observed and rendered, never re-dispatched locally.
 */
final class ClaudeCliStreamParser {

    interface Event { }

    record SessionInit(String sessionId) implements Event { }
    record Text(String text) implements Event { }
    record Thinking(String text) implements Event { }
    record ToolStart(String callId, String name, String input) implements Event { }
    record ToolInput(String callId, String name, String input) implements Event { }
    record ToolOutput(String callId, String name, String output) implements Event { }
    record ToolComplete(String callId, String name, String output, boolean error) implements Event { }
    record Notice(String text) implements Event { }
    record TurnComplete(String result, boolean error, String errorMessage,
                        long inputTokens, long outputTokens,
                        long cacheReadTokens, long cacheCreationTokens) implements Event { }

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final Map<Integer, Block> blocks = new HashMap<>();
    private final Map<Integer, StringBuilder> streamedText = new HashMap<>();
    private final Map<Integer, StringBuilder> streamedThinking = new HashMap<>();
    private final Set<String> startedToolCalls = new HashSet<>();
    private final Set<String> completedInputs = new HashSet<>();
    private final Map<String, String> toolNames = new HashMap<>();
    private String messageId = "";

    List<Event> parse(String line) {
        if (line == null || line.isBlank()) return List.of();
        try {
            JsonNode node = mapper.readTree(line);
            if (node == null || !node.isObject()) return List.of();
            return switch (node.path("type").asText()) {
                case "system" -> parseSystem(node);
                case "stream_event" -> parseStreamEvent(node.path("event"));
                case "assistant" -> parseAssistant(node);
                case "user" -> parseUser(node);
                case "tool_progress" -> parseToolProgress(node);
                case "result" -> parseResult(node);
                default -> List.of();
            };
        } catch (Exception ignored) {
            // The transport keeps malformed/non-JSON output in its diagnostics;
            // it must not be mistaken for assistant prose.
            return List.of();
        }
    }

    private List<Event> parseSystem(JsonNode node) {
        String subtype = node.path("subtype").asText("");
        // Only the CLI's init handshake binds the native session id. Every other
        // system event is a status/notice (compaction, tool permission, …) and may
        // still carry a session_id — emitting SessionInit for those would swallow
        // the notice, so they surface as Notice instead.
        if ("init".equals(subtype)) {
            String session = node.path("session_id").asText("");
            return session.isBlank() ? List.of() : List.of(new SessionInit(session));
        }
        String text = firstText(node, "message", "status", "description", "content");
        if (text.isBlank() && !subtype.isBlank()) text = subtype;
        return text.isBlank() ? List.of() : List.of(new Notice(text));
    }

    private List<Event> parseStreamEvent(JsonNode event) {
        if (!event.isObject()) return List.of();
        String type = event.path("type").asText();
        if ("message_start".equals(type)) {
            messageId = event.path("message").path("id").asText("");
            blocks.clear();
            streamedText.clear();
            streamedThinking.clear();
            return List.of();
        }
        if ("content_block_start".equals(type)) {
            int index = event.path("index").asInt(0);
            JsonNode contentBlock = event.path("content_block");
            if (index < 0 || !contentBlock.isObject()) return List.of();
            String blockType = contentBlock.path("type").asText();
            Block block = new Block(blockType);
            blocks.put(index, block);
            if ("tool_use".equals(blockType)) {
                block.callId = toolCallId(contentBlock, index);
                block.name = contentBlock.path("name").asText("unknown");
                block.input = jsonText(contentBlock.path("input"));
                return startTool(block);
            }
            return List.of();
        }
        if ("content_block_delta".equals(type)) {
            int index = event.path("index").asInt(0);
            JsonNode delta = event.path("delta");
            if (index < 0 || !delta.isObject()) return List.of();
            String deltaType = delta.path("type").asText();
            if ("text_delta".equals(deltaType)) {
                String text = delta.path("text").asText("");
                streamedText.computeIfAbsent(index, ignored -> new StringBuilder()).append(text);
                return text.isEmpty() ? List.of() : List.of(new Text(text));
            }
            if ("thinking_delta".equals(deltaType)) {
                String text = delta.path("thinking").asText("");
                streamedThinking.computeIfAbsent(index, ignored -> new StringBuilder()).append(text);
                return text.isEmpty() ? List.of() : List.of(new Thinking(text));
            }
            if ("input_json_delta".equals(deltaType)) {
                Block block = blocks.get(index);
                if (block != null && "tool_use".equals(block.type)) {
                    block.partialInput.append(delta.path("partial_json").asText(""));
                }
            }
            return List.of();
        }
        if ("content_block_stop".equals(type)) {
            int index = event.path("index").asInt(0);
            Block block = blocks.get(index);
            if (block != null && "tool_use".equals(block.type)) {
                String partial = block.partialInput.toString();
                if (!partial.isBlank()) block.input = normalizeJson(partial);
                return finishToolInput(block);
            }
        }
        return List.of();
    }

    private List<Event> parseAssistant(JsonNode node) {
        JsonNode message = node.path("message");
        String aggregateMessageId = message.path("id").asText("");
        if (!aggregateMessageId.isBlank() && !aggregateMessageId.equals(messageId)) {
            // Older CLI versions may emit aggregate messages without stream events.
            messageId = aggregateMessageId;
            blocks.clear();
            streamedText.clear();
            streamedThinking.clear();
        }
        JsonNode content = message.path("content");
        if (!content.isArray()) return List.of();
        List<Event> events = new ArrayList<>();
        // The CLI emits one aggregate per finished block, carrying just that block
        // at content[0], so an aggregate's array index says nothing about its stream
        // index. A block is a repeat when this message already streamed its text.
        for (int index = 0; index < content.size(); index++) {
            JsonNode block = content.get(index);
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                String text = block.path("text").asText("");
                if (!text.isEmpty() && !alreadyStreamed(streamedText, text)) events.add(new Text(text));
            } else if ("thinking".equals(type)) {
                String thinking = block.path("thinking").asText("");
                if (!thinking.isEmpty() && !alreadyStreamed(streamedThinking, thinking)) {
                    events.add(new Thinking(thinking));
                }
            } else if ("tool_use".equals(type)) {
                Block tool = new Block(type);
                tool.callId = toolCallId(block, index);
                tool.name = block.path("name").asText("unknown");
                tool.input = jsonText(block.path("input"));
                events.addAll(startTool(tool));
                events.addAll(finishToolInput(tool));
            }
            // Unknown content blocks are not rendered as assistant prose. The
            // aggregate still retains all supported text/thinking/tool content.
        }
        return events;
    }

    private List<Event> parseUser(JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return List.of();
        List<Event> events = new ArrayList<>();
        for (JsonNode block : content) {
            if (!"tool_result".equals(block.path("type").asText())) continue;
            String callId = block.path("tool_use_id").asText("");
            if (callId.isBlank()) continue;
            String name = toolNames.getOrDefault(callId, "unknown");
            String output = contentText(block.path("content"));
            boolean error = block.path("is_error").asBoolean(false);
            events.add(new ToolComplete(callId, name, output, error));
        }
        return events;
    }

    private List<Event> parseToolProgress(JsonNode node) {
        String callId = node.path("tool_use_id").asText(node.path("call_id").asText(""));
        String output = firstText(node, "content", "output", "message");
        if (callId.isBlank() || output.isBlank()) return List.of();
        return List.of(new ToolOutput(callId, toolNames.getOrDefault(callId, "unknown"), output));
    }

    private List<Event> parseResult(JsonNode node) {
        JsonNode usage = node.path("usage");
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheCreation = usage.path("cache_creation_input_tokens").asLong(0);
        long inclusiveInput = usage.path("input_tokens").asLong(0);
        long input = Math.max(0, inclusiveInput - cacheRead - cacheCreation);
        String subtype = node.path("subtype").asText("");
        boolean error = node.path("is_error").asBoolean(false) || subtype.startsWith("error");
        String errorMessage = error
                ? firstText(node, "error", "error_message", "message", "result") : "";
        String result = node.path("result").asText("");
        return List.of(new TurnComplete(result, error, errorMessage, input,
                usage.path("output_tokens").asLong(0), cacheRead, cacheCreation));
    }

    private List<Event> startTool(Block block) {
        if (block.callId == null || block.callId.isBlank()) return List.of();
        toolNames.put(block.callId, block.name);
        if (!startedToolCalls.add(block.callId)) return List.of();
        return List.of(new ToolStart(block.callId, block.name, block.input));
    }

    private List<Event> finishToolInput(Block block) {
        if (block.callId == null || block.callId.isBlank()
                || !completedInputs.add(block.callId)) return List.of();
        return List.of(new ToolInput(block.callId, block.name, block.input));
    }

    private String toolCallId(JsonNode block, int index) {
        String id = block.path("id").asText("");
        if (!id.isBlank()) return id;
        return (messageId.isBlank() ? "claude" : messageId) + ":tool:" + index;
    }

    private static boolean alreadyStreamed(Map<Integer, StringBuilder> streamedBlocks, String text) {
        for (StringBuilder streamed : streamedBlocks.values()) {
            if (streamed.toString().equals(text)) return true;
        }
        return false;
    }

    private static String jsonText(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.toString();
    }

    private String normalizeJson(String json) {
        try {
            JsonNode parsed = mapper.readTree(json);
            return parsed == null ? json : parsed.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    private static String contentText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.toString();
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if (item.path("type").asText().equals("text")) {
                if (text.length() > 0) text.append('\n');
                text.append(item.path("text").asText(""));
            } else if (item.path("type").asText().equals("image")) {
                if (text.length() > 0) text.append('\n');
                String mediaType = item.path("source").path("media_type").asText("unknown type");
                text.append("[image result: ").append(mediaType).append(']');
            } else {
                if (text.length() > 0) text.append('\n');
                text.append(item.toString());
            }
        }
        return text.toString();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
            if (value.isObject() || value.isArray()) return value.toString();
        }
        return "";
    }

    private static final class Block {
        private final String type;
        private final StringBuilder partialInput = new StringBuilder();
        private String callId;
        private String name = "unknown";
        private String input = "";

        private Block(String type) {
            this.type = type;
        }
    }
}
