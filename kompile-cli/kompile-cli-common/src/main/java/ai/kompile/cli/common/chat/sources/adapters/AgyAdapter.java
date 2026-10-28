/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Antigravity CLI ({@code agy}) session state from
 * {@code ~/.gemini/antigravity-cli}.
 * <p>
 * Layout (verified on an installed CLI): {@code conversations/&lt;uuid&gt;.db} files hold
 * the real transcripts as protobuf blobs inside SQLite — opaque without Antigravity's
 * own protos. The readable projection lives in {@code cache/conversation_metadata.json}
 * (per-conversation summary, step count, workspace URI, timestamps) and
 * {@code cache/last_conversations.json} (workspace → most recent conversation id).
 * This adapter serves listings and resume targets from that cache and reports turns
 * as unavailable rather than fabricating content it cannot read.
 */
public class AgyAdapter implements ChatSourceAdapter {

    public static final String ID = "agy";

    private static final String UNAVAILABLE_TURNS =
            "agy stores turns in an opaque protobuf SQLite conversation store; "
                    + "resume with `agy --conversation <id>`.";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Antigravity CLI";
    }

    /** Root of the CLI's state: {@code ~/.gemini/antigravity-cli}. */
    protected Path rootDir() {
        return ChatAdapterSupport.userHome().resolve(".gemini").resolve("antigravity-cli");
    }

    protected Path metadataCache() {
        return rootDir().resolve("cache").resolve("conversation_metadata.json");
    }

    protected Path lastConversations() {
        return rootDir().resolve("cache").resolve("last_conversations.json");
    }

    @Override
    public SourceInfo discover() {
        Path metadata = metadataCache();
        if (!Files.isRegularFile(metadata)) {
            return SourceInfo.unavailable(id(), displayName(), rootDir().toString(),
                    "no conversation metadata cache (is the agy CLI installed and used?)");
        }
        int count = readConversationMetadata().size();
        return SourceInfo.available(id(), displayName(), rootDir().toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        Map<String, JsonNode> conversations = readConversationMetadata();
        Map<String, String> lastByWorkspace = readLastConversations();
        List<ChatSessionSummary> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : conversations.entrySet()) {
            String id = entry.getKey();
            JsonNode summary = entry.getValue().path("summary");
            String workspace = summary.path("WorkspaceUri").asText("");
            Path workspacePath = workspaceToPath(workspace);
            String workspacePathText = workspacePath == null ? null : workspacePath.toString();
            String title = firstText(summary, "Title", "Preview");
            if (title == null || title.isBlank()) {
                title = "(agy) " + id;
            }
            int steps = entry.getValue().path("stepCount").asInt(
                    summary.path("StepCount").asInt(0));
            long updated = updatedAtMillis(entry.getValue(), summary);
            if (updated == 0L) {
                updated = conversationDbModified(id);
            }
            out.add(new ChatSessionSummary(id, id(), title, id(), steps, updated, workspacePathText));
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        // Transcripts are protobuf blobs in conversations/<id>.db; without Antigravity's
        // protos there is no honest way to project them into turns.
        return Collections.emptyList();
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Map<String, JsonNode> conversations = readConversationMetadata();
        JsonNode entry = conversations.get(sessionId);
        if (entry == null) {
            // Accept prefix matches — callers often hold a shortened id
            for (Map.Entry<String, JsonNode> candidate : conversations.entrySet()) {
                if (candidate.getKey().startsWith(sessionId)
                        || sessionId.startsWith(candidate.getKey())) {
                    entry = candidate.getValue();
                    break;
                }
            }
        }
        if (entry == null) {
            return Optional.empty();
        }
        String workspace = entry.path("summary").path("WorkspaceUri").asText("");
        Path path = workspaceToPath(workspace);
        return path != null ? Optional.of(path) : Optional.empty();
    }

    // ── Cache readers ─────────────────────────────────────────────────────

    private Map<String, JsonNode> readConversationMetadata() {
        Path metadata = metadataCache();
        if (!Files.isRegularFile(metadata)) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(metadata.toFile());
            JsonNode conversations = root.path("conversations");
            if (!conversations.isObject()) {
                return Collections.emptyMap();
            }
            Map<String, JsonNode> out = new LinkedHashMap<>();
            conversations.fields().forEachRemaining(field -> out.put(field.getKey(), field.getValue()));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private Map<String, String> readLastConversations() {
        Path last = lastConversations();
        if (!Files.isRegularFile(last)) {
            return Collections.emptyMap();
        }
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(last.toFile());
            Map<String, String> out = new LinkedHashMap<>();
            root.fields().forEachRemaining(field -> out.put(field.getKey(), field.getValue().asText()));
            return out;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** {@code file:///home/user/project} → {@code /home/user/project}; null when not a file URI. */
    private static Path workspaceToPath(String workspaceUri) {
        if (workspaceUri == null || workspaceUri.isBlank()) {
            return null;
        }
        String value = workspaceUri;
        if (value.startsWith("file://")) {
            value = value.substring("file://".length());
        }
        if (value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long updatedAtMillis(JsonNode entry, JsonNode summary) {
        for (JsonNode holder : List.of(summary, entry)) {
            for (String field : List.of("UpdatedAt", "UpdatedAtMillis", "LastModified")) {
                JsonNode value = holder.path(field);
                if (value.isMissingNode()) {
                    continue;
                }
                if (value.canConvertToLong()) {
                    long raw = value.asLong();
                    // Heuristic: seconds vs millis — agy writes second-precision epochs
                    return raw < 100_000_000_000L ? raw * 1000L : raw;
                }
            }
        }
        // Fall back to the conversation .db file's mtime
        return 0L;
    }

    /**
     * Antigravity's authentication actions handled by {@code NativeCliAuth}; the adapter
     * only documents the resume surface.
     */

    /** The conversation database's modification time, or 0 when the db is absent. */
    private long conversationDbModified(String sessionId) {
        Path db = rootDir().resolve("conversations").resolve(sessionId + ".db");
        return Files.isRegularFile(db) ? ChatAdapterSupport.lastModified(db) : 0L;
    }

    /** Why live turns cannot be read, for callers that surface adapter limitations. */
    public static String turnsUnavailableReason() {
        return UNAVAILABLE_TURNS;
    }

    // ── Unused legacy hooks kept for adapter-contract compatibility ──────

    @SuppressWarnings("unused")
    private static long lastModifiedOf(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).lastModifiedTime().toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
