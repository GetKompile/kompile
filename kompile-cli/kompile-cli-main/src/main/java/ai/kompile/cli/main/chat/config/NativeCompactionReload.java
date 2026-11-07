/*
 * Copyright 2026 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.context.CompactionWorkingSet;
import ai.kompile.cli.main.chat.mcp.McpDiagnostics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Restores the memory files and skills a provider-owned session used after the provider
 * compacts it, for the CLIs that give Kompile no post-compaction hook: OpenCode and the
 * Antigravity CLI (agy). Their compaction summarizes away the tool results and messages
 * that carried memory files and expanded skills, while every Kompile turn re-sends only the
 * instructions and the turn's own memory envelope.
 *
 * <p>The client feeds what each turn sends and each Kompile memory/skill tool call returns
 * ({@link CompactionWorkingSet}). Once the client sees the provider compact, the next
 * prompt carries the reload ahead of the message. An optional mirror file keeps the current
 * reload on disk for an in-process hook (OpenCode's {@code experimental.session.compacting}
 * plugin hook, which feeds it to OpenCode's summarizer for a compaction mid-turn).</p>
 */
final class NativeCompactionReload {
    /** The reload mirror a provider-side plugin reads: JSON {@code {"context": "<reload>"}}. */
    static final String FILE_ENV = "KOMPILE_COMPACTION_RELOAD_FILE";
    static final int DEFAULT_MAX_CHARS = 12_000;

    private final CompactionWorkingSet workingSet;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private Path mirror;
    private String mirrored;
    private boolean pending;

    NativeCompactionReload(Path workingDirectory, ObjectMapper mapper) {
        this.workingSet = new CompactionWorkingSet(workingDirectory, DEFAULT_MAX_CHARS);
        this.mapper = mapper;
        this.enabled = Boolean.parseBoolean(System.getProperty("kompile.chat.compactionReload", "true"));
    }

    boolean enabled() {
        return enabled;
    }

    /** Records what an outbound message carried (reminders, persistent memory, skills). */
    synchronized void recordPrompt(String message) {
        if (!enabled || message == null) return;
        workingSet.recordPrompt(message, MemoryContextProjection.persistentSnapshot(message));
        refreshMirror();
    }

    /**
     * Records one settled tool call. Kompile's tools arrive under the provider's name for
     * them ({@code kompile_memory} on OpenCode); a generic MCP dispatcher tool whose
     * arguments name the server and tool is unwrapped first.
     */
    synchronized void recordToolUse(String toolName, String inputJson, String output) {
        if (!enabled || toolName == null) return;
        JsonNode input = parse(inputJson);
        String tool = toolName;
        JsonNode server = first(input, "server_name", "serverName", "ServerName", "server");
        JsonNode inner = first(input, "tool_name", "toolName", "ToolName");
        if (server.isTextual() && inner.isTextual()) {
            if (!"kompile".equalsIgnoreCase(server.asText().strip())) return;
            tool = inner.asText();
            JsonNode arguments = first(input, "arguments", "Arguments", "args", "input");
            input = arguments.isTextual() ? parse(arguments.asText()) : arguments;
        }
        workingSet.recordToolUse(tool, input, output);
        refreshMirror();
    }

    /** The provider compacted the session: the next prompt carries the reload. */
    synchronized void compacted() {
        if (enabled) pending = true;
    }

    synchronized boolean pending() {
        return pending;
    }

    /**
     * {@code message}, preceded by the reload when the provider compacted since the last
     * prompt. Reminders and persistent memory the message delivers itself are not repeated.
     */
    synchronized String withPendingReload(String message) {
        if (!pending) return message;
        pending = false;
        String text = message == null ? "" : message;
        boolean carriesReminders = ReminderManager.stripReminderBlock(text).length() < text.strip().length();
        boolean carriesMemory = MemoryContextProjection.persistentSnapshot(text) != null;
        String reload = workingSet.reloadContext(!carriesReminders, !carriesMemory);
        return reload == null ? message : reload + "\n\n" + text;
    }

    String reloadContext() {
        return workingSet.reloadContext();
    }

    /** Keep the current reload in {@code file} from now on; null stops mirroring. */
    synchronized void mirrorTo(Path file) {
        mirror = file;
        mirrored = null;
        refreshMirror();
    }

    private void refreshMirror() {
        Path file = mirror;
        if (file == null) return;
        String reload = workingSet.reloadContext();
        if (reload == null || Objects.equals(reload, mirrored)) return;
        try {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(mapper.createObjectNode().put("context", reload)),
                    StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            mirrored = reload;
        } catch (IOException e) {
            McpDiagnostics.log("[MCP] Warning: could not write the compaction reload to " + file
                    + ": " + e.getMessage());
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return MissingNode.getInstance();
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return MissingNode.getInstance();
        }
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) return value;
        }
        return MissingNode.getInstance();
    }
}
