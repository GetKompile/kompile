/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.adapters.CodexAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.format.ConversationExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Ensures that a first-party native resume target exists before the agent is launched.
 *
 * <p>Kompile transcripts are the recovery source of truth. A healthy native session is
 * never rewritten. If the native session is absent, the existing per-vendor exporter
 * reconstructs it using the original native id when safe, or a new id otherwise. Codex
 * always receives a new id because its durable queue and thread state are keyed by id.</p>
 */
public final class NativeResumeCoordinator {

    public static final List<String> FIRST_PARTY_AGENTS =
            List.of("claude", "codex", "qwen", "opencode", "gemini", "pi");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> RESTORE_LOCKS = new ConcurrentHashMap<>();

    private NativeResumeCoordinator() {
    }

    public enum Outcome {
        PRESENT,
        RECREATED
    }

    public record Result(
            String agent,
            String requestedSessionId,
            String nativeSessionId,
            String launchToken,
            Path workingDirectory,
            Outcome outcome,
            ConversationExporter.ExportResult exportResult) {

        public boolean recreated() {
            return outcome == Outcome.RECREATED;
        }
    }

    /**
     * Makes a native session resumable. This method is deliberately synchronous so a
     * caller cannot launch an agent against an id that has not been verified yet.
     */
    public static Result ensure(
            String kompileSessionId,
            String agent,
            String nativeSessionId,
            List<ChatHistory.Turn> turns,
            String sourceAgent,
            Path workingDirectory) throws IOException {
        String normalizedAgent = normalizeAgent(agent);
        if (!FIRST_PARTY_AGENTS.contains(normalizedAgent)) {
            throw new IOException("Native reconstruction is unsupported for agent: " + agent);
        }

        Path cwd = normalizeWorkingDirectory(workingDirectory);
        String requestedId = clean(nativeSessionId);
        String lockKey = normalizedAgent + "|" + (requestedId == null ? kompileSessionId : requestedId)
                + "|" + cwd;
        Object lock = RESTORE_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());

        synchronized (lock) {
            String candidateId = requestedId;
            if (kompileSessionId != null && !kompileSessionId.isBlank()) {
                String persistedId = clean(ChatHistory.resolveNativeSessionId(
                        kompileSessionId, normalizedAgent));
                if (persistedId != null) {
                    candidateId = persistedId;
                }
            }
            if (candidateId != null && isPresent(normalizedAgent, candidateId, cwd)) {
                return result(normalizedAgent, requestedId, candidateId, cwd, Outcome.PRESENT, null);
            }

            if (turns == null || turns.isEmpty()) {
                throw new IOException("Cannot recreate " + normalizedAgent
                        + " session without a saved Kompile transcript");
            }

            // Codex owns its native thread index. Recreate through App Server so a corrupt
            // or missing state_N.sqlite never requires Kompile to guess private SQL schema.
            if ("codex".equals(normalizedAgent)) {
                Optional<String> recreated = new CodexAdapter().recreateThread(
                        toNativeTurns(turns), cwd);
                if (recreated.isPresent() && isPresent("codex", recreated.get(), cwd)) {
                    String recreatedId = recreated.get();
                    ConversationExporter.ExportResult nativeResult =
                            new ConversationExporter.ExportResult(
                                    recreatedId, "codex", null,
                                    "codex resume " + recreatedId, cwd);
                    if (kompileSessionId != null && !kompileSessionId.isBlank()) {
                        ChatHistory.recordNativeSessionId(kompileSessionId, recreatedId);
                    }
                    return result(normalizedAgent, requestedId, recreatedId, cwd,
                            Outcome.RECREATED, nativeResult);
                }
            }

            // A missing Codex thread must never be recreated under its stale id. Codex keeps
            // durable per-thread queue state separately, so reusing the id can make a seemingly
            // fresh resume inherit old queued submissions and reject the first new message.
            String exportSessionId = "codex".equals(normalizedAgent) ? null : candidateId;
            ConversationExporter.ExportResult exported =
                    ConversationExporter.exportToAgent(
                            turns,
                            normalizedAgent,
                            exportSessionId,
                            clean(sourceAgent) == null ? normalizedAgent : sourceAgent,
                            cwd);

            String recreatedId = exported.getSessionId();
            if (recreatedId == null || recreatedId.isBlank()
                    || !isPresent(normalizedAgent, recreatedId, cwd)) {
                throw new IOException("Recreated " + normalizedAgent
                        + " session could not be verified: " + recreatedId);
            }

            if (kompileSessionId != null && !kompileSessionId.isBlank()
                    && !Objects.equals(candidateId, recreatedId)) {
                ChatHistory.recordNativeSessionId(kompileSessionId, recreatedId);
            }

            return result(normalizedAgent, requestedId, recreatedId, cwd, Outcome.RECREATED, exported);
        }
    }

    public static String normalizeAgent(String agent) {
        if (agent == null) {
            return "";
        }
        return switch (agent.trim().toLowerCase(Locale.ROOT)) {
            case "claude-code", "claude" -> "claude";
            case "pi-cli", "pi" -> "pi";
            default -> agent.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static Result result(
            String agent,
            String requestedId,
            String nativeId,
            Path cwd,
            Outcome outcome,
            ConversationExporter.ExportResult exportResult) throws IOException {
        String launchToken = nativeId;
        if ("gemini".equals(agent)) {
            Integer index = ConversationExporter.resolveGeminiSessionIndex(cwd, nativeId);
            launchToken = index == null ? "latest" : Integer.toString(index);
        }
        return new Result(agent, requestedId, nativeId, launchToken, cwd, outcome, exportResult);
    }

    private static List<ChatTurn> toNativeTurns(List<ChatHistory.Turn> turns) {
        List<ChatTurn> nativeTurns = new ArrayList<>();
        for (ChatHistory.Turn turn : turns) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            nativeTurns.add(new ChatTurn(
                    "assistant".equalsIgnoreCase(turn.role()) ? "assistant" : "user",
                    turn.content()));
        }
        return nativeTurns;
    }

    private static boolean isPresent(String agent, String sessionId, Path cwd) {
        if ("gemini".equals(agent)) {
            return geminiSessionExists(sessionId, cwd);
        }
        if ("codex".equals(agent)) {
            return new CodexAdapter().isNativeThreadPresent(sessionId, cwd);
        }

        String source = "claude".equals(agent) ? "claude-code" : agent;
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);
        if (adapter == null) {
            return false;
        }

        try {
            List<ChatSessionSummary> sessions = cwd == null
                    ? adapter.list()
                    : adapter.list(cwd);
            if (sessions.stream().anyMatch(summary -> sessionId.equals(summary.sessionId()))) {
                return true;
            }
        } catch (Exception ignored) {
            // readTurns below is a useful fallback for older adapter schemas.
        }

        try {
            List<?> turns = adapter.readTurns(sessionId);
            return turns != null && !turns.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean geminiSessionExists(String sessionId, Path cwd) {
        Path chats = Path.of(System.getProperty("user.home"), ".gemini", "tmp",
                sha256(cwd.toString()), "chats");
        if (!Files.isDirectory(chats)) {
            return false;
        }

        try (Stream<Path> files = Files.list(chats)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .anyMatch(path -> geminiFileMatches(path, sessionId));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean geminiFileMatches(Path file, String sessionId) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            return root != null && sessionId.equals(root.path("sessionId").asText(null));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path normalizeWorkingDirectory(Path workingDirectory) {
        Path cwd = workingDirectory != null
                ? workingDirectory
                : Path.of(System.getProperty("user.dir"));
        return cwd.toAbsolutePath().normalize();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
