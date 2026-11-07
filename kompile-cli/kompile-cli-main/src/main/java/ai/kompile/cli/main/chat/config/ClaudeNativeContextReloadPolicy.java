/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.context.CompactionWorkingSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/**
 * Keeps Kompile's memory, skills and reminders applied across Claude Code's own
 * compaction. Kompile delivers them inside user messages (the memory envelope,
 * expanded {@code <skill>} blocks, the reminder block) and through its MCP tools,
 * and Claude Code's summary drops them. Kompile re-sends memory on the next user
 * message, but an auto-compaction in the middle of a long agentic turn left the
 * rest of that turn without them, and nothing ever re-sent a skill or a memory
 * file the model had read.
 *
 * <p>Two SDK hooks registered at initialize: {@code PostToolUse} on Kompile's
 * memory and skill tools records the session's {@link CompactionWorkingSet} (it
 * never alters the tool result), and {@code SessionStart} with source
 * {@code compact} returns that working set as {@code additionalContext}.</p>
 */
final class ClaudeNativeContextReloadPolicy {
    static final String RECORD_CALLBACK_ID = "kompile-native-working-set-v1";
    static final String RECORD_MATCHER = "^mcp__kompile__(memory|skill_manager)$";
    static final String RELOAD_CALLBACK_ID = "kompile-native-compact-reload-v1";
    /** SessionStart matchers select the start source; only a compaction loses context. */
    static final String RELOAD_MATCHER = "compact";
    static final int DEFAULT_MAX_CHARS = 12_000;

    private final ObjectMapper mapper;
    private final CompactionWorkingSet workingSet;

    ClaudeNativeContextReloadPolicy(ObjectMapper mapper, Path workingDirectory) {
        this(mapper, workingDirectory, DEFAULT_MAX_CHARS);
    }

    ClaudeNativeContextReloadPolicy(ObjectMapper mapper, Path workingDirectory, int maxChars) {
        this.mapper = mapper;
        this.workingSet = new CompactionWorkingSet(workingDirectory, maxChars);
    }

    /** Records what an outbound user message carried; see {@link CompactionWorkingSet#recordPrompt}. */
    void recordPrompt(String message, String persistentMemorySnapshot) {
        workingSet.recordPrompt(message, persistentMemorySnapshot);
    }

    /** PostToolUse: note the memory file or skill a Kompile tool used. Never alters the result. */
    ObjectNode record(JsonNode event) {
        ObjectNode unchanged = mapper.createObjectNode();
        if (!"PostToolUse".equals(event.path("hook_event_name").asText())) return unchanged;
        String tool = event.path("tool_name").asText("");
        if (!tool.matches(RECORD_MATCHER)) return unchanged;
        workingSet.recordToolUse(tool, event.path("tool_input"), responseText(event.path("tool_response")));
        return unchanged;
    }

    /** SessionStart after a compaction: hand the working set back to the model. */
    ObjectNode reload(JsonNode event) {
        ObjectNode unchanged = mapper.createObjectNode();
        if (!"SessionStart".equals(event.path("hook_event_name").asText())
                || !RELOAD_MATCHER.equals(event.path("source").asText())) {
            return unchanged;
        }
        String context = reloadContext();
        if (context == null) return unchanged;
        ObjectNode specific = unchanged.putObject("hookSpecificOutput");
        specific.put("hookEventName", "SessionStart");
        specific.put("additionalContext", context);
        return unchanged;
    }

    /** The post-compaction context, or null when the session used nothing to restore. */
    String reloadContext() {
        return workingSet.reloadContext();
    }

    /** MCP results arrive as a string, a content-block array, or an object holding one. */
    private static String responseText(JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) return "";
        if (response.isTextual()) return response.asText();
        JsonNode blocks = response.isArray() ? response : response.path("content");
        if (blocks.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode block : blocks) {
                if (block.path("text").isTextual()) text.append(block.path("text").asText()).append('\n');
            }
            return text.toString();
        }
        return response.toString();
    }
}
