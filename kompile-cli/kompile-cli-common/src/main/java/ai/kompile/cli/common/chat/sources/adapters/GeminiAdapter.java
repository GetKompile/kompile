/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reader for Gemini CLI's project-scoped JSON session files.
 *
 * <p>Gemini resumes by a 1-based picker index, but the persisted session UUID is
 * still the stable identity used by Kompile's source registry.</p>
 */
public class GeminiAdapter implements ChatSourceAdapter {

    public static final String ID = "gemini";

    private Path rootDir() {
        return ChatAdapterSupport.userHome().resolve(".gemini").resolve("tmp");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Gemini CLI";
    }

    @Override
    public SourceInfo discover() {
        Path root = rootDir();
        if (!Files.isDirectory(root)) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(), "directory missing");
        }
        try {
            return SourceInfo.available(id(), displayName(), root.toString(), list().size());
        } catch (IOException e) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(),
                    "sessions unreadable: " + e.getMessage());
        }
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        return listInternal(null);
    }

    @Override
    public List<ChatSessionSummary> list(Path workingDirectory) throws IOException {
        return listInternal(workingDirectory == null
                ? null : workingDirectory.toAbsolutePath().normalize());
    }

    private List<ChatSessionSummary> listInternal(Path workingDirectory) throws IOException {
        Path root = rootDir();
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }

        List<ChatSessionSummary> sessions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 3)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            ChatSessionSummary summary = toSummary(path);
                            if (summary == null) {
                                return;
                            }
                            if (workingDirectory != null
                                    && !workingDirectory.toString().equals(summary.workingDirectory())) {
                                return;
                            }
                            sessions.add(summary);
                        } catch (IOException ignored) {
                        }
                    });
        }
        sessions.sort(Comparator.comparingLong(ChatSessionSummary::lastModifiedMillis).reversed());
        return sessions;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Path file = findSession(sessionId).orElse(null);
        return file == null ? Collections.emptyList() : parse(file);
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        Path file = findSession(sessionId).orElse(null);
        if (file == null) {
            return Optional.empty();
        }
        String cwd = readRoot(file).path("workingDirectory").asText(null);
        return cwd == null || cwd.isBlank() ? Optional.empty() : Optional.of(Path.of(cwd));
    }

    @Override
    public String resolveTitle(String sessionId) throws IOException {
        for (ChatTurn turn : readTurns(sessionId)) {
            if (turn.isUser() && turn.content() != null && !turn.content().isBlank()) {
                String title = turn.content().trim();
                return title.length() > 80 ? title.substring(0, 77) + "..." : title;
            }
        }
        return sessionId;
    }

    private ChatSessionSummary toSummary(Path file) throws IOException {
        JsonNode root = readRoot(file);
        String sessionId = root.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String cwd = root.path("workingDirectory").asText(null);
        int count = root.path("messages").isArray() ? root.path("messages").size() : -1;
        String title = resolveTitle(root, sessionId);
        return new ChatSessionSummary(sessionId, id(), title, id(), count,
                Files.getLastModifiedTime(file).toMillis(), cwd);
    }

    private String resolveTitle(JsonNode root, String fallback) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return fallback;
        }
        for (JsonNode message : messages) {
            String role = ChatAdapterSupport.extractRole(message);
            String content = ChatAdapterSupport.extractContent(message);
            if ("user".equals(role) && content != null && !content.isBlank()) {
                String title = content.trim();
                return title.length() > 80 ? title.substring(0, 77) + "..." : title;
            }
        }
        return fallback;
    }

    private List<ChatTurn> parse(Path file) throws IOException {
        JsonNode root = readRoot(file);
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return Collections.emptyList();
        }

        List<ChatTurn> turns = new ArrayList<>();
        for (JsonNode message : messages) {
            String role = ChatAdapterSupport.extractRole(message);
            String content = ChatAdapterSupport.extractContent(message);
            if (role == null || content == null || content.isBlank()
                    || "system".equalsIgnoreCase(role) || "tool".equalsIgnoreCase(role)) {
                continue;
            }
            Instant timestamp = null;
            String rawTimestamp = message.path("timestamp").asText(null);
            if (rawTimestamp != null && !rawTimestamp.isBlank()) {
                try {
                    timestamp = Instant.parse(rawTimestamp);
                } catch (RuntimeException ignored) {
                }
            }
            turns.add(new ChatTurn(role, content, timestamp));
        }
        return turns;
    }

    private JsonNode readRoot(Path file) throws IOException {
        return ChatAdapterSupport.MAPPER.readTree(file.toFile());
    }

    private Optional<Path> findSession(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isBlank() || !Files.isDirectory(rootDir())) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(rootDir(), 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> sessionMatches(path, sessionId))
                    .findFirst();
        }
    }

    private boolean sessionMatches(Path file, String sessionId) {
        try {
            return sessionId.equals(readRoot(file).path("sessionId").asText(null));
        } catch (IOException ignored) {
            return false;
        }
    }

    static String projectHash(Path workingDirectory) {
        String value = workingDirectory.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format(Locale.ROOT, "%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
