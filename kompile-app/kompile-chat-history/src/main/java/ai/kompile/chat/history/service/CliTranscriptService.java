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

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for reading/writing CLI chat transcripts and discovering
 * external agent conversations. All per-source parsing is delegated
 * to {@link ChatSourceAdapter} instances via {@link ChatSourceRegistry}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kompile.chat.history.cli-sync-enabled", havingValue = "true", matchIfMissing = true)
public class CliTranscriptService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final String SEPARATOR = "──────────────────────────────────";

    public static final String SOURCE_KOMPILE = "kompile";
    public static final String SOURCE_CLAUDE_CODE = "claude-code";
    public static final String SOURCE_OPENCODE = "opencode";
    public static final String SOURCE_CODEX = "codex";
    public static final String SOURCE_QWEN = "qwen";

    private final ChatHistoryProperties properties;
    private final ChatHistoryService chatHistoryService;

    private Path conversationsDir;

    @PostConstruct
    void init() {
        String customPath = properties.getCliConversationsPath();
        if (customPath != null && !customPath.isBlank()) {
            conversationsDir = Path.of(customPath);
        } else {
            conversationsDir = Path.of(System.getProperty("user.home"), ".kompile", "conversations");
        }
        log.info("CLI transcript directory: {}", conversationsDir);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SOURCE DISCOVERY
    // ═══════════════════════════════════════════════════════════════════════════════

    public Map<String, SourceInfo> discoverSources() {
        Map<String, SourceInfo> out = new LinkedHashMap<>();
        for (ChatSourceAdapter adapter : ChatSourceRegistry.getInstance().all()) {
            try {
                ai.kompile.cli.common.chat.sources.SourceInfo info = adapter.discover();
                out.put(adapter.id(), new SourceInfo(info.path(), info.sessionCount(), info.available()));
            } catch (Exception e) {
                log.warn("Adapter {} discover() failed: {}", adapter.id(), e.getMessage());
                out.put(adapter.id(), new SourceInfo("", 0, false));
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LIST SESSIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    public List<CliSessionSummary> listSessions(String sourceFilter) {
        List<CliSessionSummary> all = new ArrayList<>();
        Collection<ChatSourceAdapter> adapters;
        if (sourceFilter == null || sourceFilter.isEmpty() || sourceFilter.equals("all")) {
            adapters = ChatSourceRegistry.getInstance().all();
        } else {
            ChatSourceAdapter a = ChatSourceRegistry.getInstance().find(sourceFilter).orElse(null);
            adapters = a != null ? List.of(a) : Collections.emptyList();
        }
        for (ChatSourceAdapter adapter : adapters) {
            try {
                for (ChatSessionSummary s : adapter.list()) {
                    all.add(new CliSessionSummary(
                            s.sessionId(), s.source(),
                            s.title() != null ? s.title() : "(untitled)",
                            s.agent(), s.messageCount(), s.lastModifiedMillis()));
                }
            } catch (Exception e) {
                log.debug("Failed to list {} sessions: {}", adapter.id(), e.getMessage());
            }
        }
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return all;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // READ TRANSCRIPT
    // ═══════════════════════════════════════════════════════════════════════════════

    public CliTranscriptDetail readTranscript(String sessionId, String source) {
        ChatSourceAdapter adapter = ChatSourceRegistry.getInstance().find(source).orElse(null);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown source: " + source);
        }
        try {
            List<ChatTurn> turns = adapter.readTurns(sessionId);
            List<ParsedTurn> parsed = turns.stream()
                    .map(t -> new ParsedTurn(t.role(), t.content()))
                    .toList();
            String title = parsed.isEmpty() ? sessionId : parsed.get(0).content();
            if (title.length() > 80) title = title.substring(0, 77) + "...";

            String agent = source;
            try {
                String resolved = adapter.resolveTitle(sessionId);
                if (resolved != null && !resolved.equals(sessionId)) title = resolved;
            } catch (Exception ignored) {}

            return new CliTranscriptDetail(sessionId, source, title, agent, parsed);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read " + source + " session " + sessionId + ": " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IMPORT: CLI -> App DB
    // ═══════════════════════════════════════════════════════════════════════════════

    public ChatSession importTranscript(String sessionId, String source) {
        CliTranscriptDetail detail = readTranscript(sessionId, source);
        if (detail == null || detail.turns().isEmpty()) {
            throw new IllegalArgumentException("No messages found in transcript: " + sessionId);
        }

        String importId = "imported-" + source + "-" + sanitizeId(sessionId);
        if (chatHistoryService.getSession(importId).isPresent()) {
            throw new IllegalStateException("Transcript already imported: " + sessionId);
        }

        ChatSession session = chatHistoryService.createSessionWithId(importId, detail.title(), source);

        for (ParsedTurn turn : detail.turns()) {
            ChatMessage.MessageRole role = "user".equals(turn.role())
                    ? ChatMessage.MessageRole.USER
                    : ChatMessage.MessageRole.ASSISTANT;
            chatHistoryService.addMessage(session.getSessionId(), role, turn.content(), null);
        }

        log.info("Imported {} messages from {} session {} as {}", detail.turns().size(), source, sessionId, importId);
        return chatHistoryService.getSession(importId).orElse(session);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SYNC HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    public List<CliSessionSummary> listNewSessions(String source) {
        List<CliSessionSummary> all = listSessions(source);
        return all.stream()
                .filter(s -> {
                    String importId = "imported-" + s.source() + "-" + sanitizeId(s.sessionId());
                    return !chatHistoryService.getSession(importId).isPresent();
                })
                .toList();
    }

    public int syncSource(String source, int batchSize) {
        List<CliSessionSummary> newSessions = listNewSessions(source);
        int imported = 0;

        for (CliSessionSummary session : newSessions) {
            if (imported >= batchSize) break;
            try {
                importTranscript(session.sessionId(), session.source());
                imported++;
            } catch (Exception e) {
                log.debug("Skipping session {} from {}: {}", session.sessionId(), source, e.getMessage());
            }
        }

        return imported;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXPORT: App DB -> CLI transcript
    // ═══════════════════════════════════════════════════════════════════════════════

    public Path exportToTranscript(String appSessionId) throws IOException {
        ChatSession session = chatHistoryService.getSession(appSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + appSessionId));

        List<ChatMessage> messages = chatHistoryService.getSessionMessages(appSessionId);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Session has no messages: " + appSessionId);
        }

        Files.createDirectories(conversationsDir);
        String exportId = "exported-app-" + sanitizeId(appSessionId);
        Path targetFile = conversationsDir.resolve(exportId + ".txt");

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(targetFile.toFile()), StandardCharsets.UTF_8)), true)) {

            writer.println("──── Conversation: " + exportId + " ────");
            writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
            writer.println("Server:  app-export");
            writer.println("Agent:   " + (session.getSource() != null ? session.getSource() : "app"));
            writer.println("RAG:     unknown");
            writer.println();
            writer.println(SEPARATOR);
            writer.println();

            for (ChatMessage msg : messages) {
                if (msg.getRole() == ChatMessage.MessageRole.USER) {
                    writer.println("> " + msg.getContent());
                    writer.println();
                } else if (msg.getRole() == ChatMessage.MessageRole.ASSISTANT) {
                    writer.println(msg.getContent());
                    writer.println();
                } else if (msg.getRole() == ChatMessage.MessageRole.SYSTEM) {
                    writer.println("[system] " + msg.getContent());
                    writer.println();
                }
            }
        }

        log.info("Exported session {} to {}", appSessionId, targetFile);
        return targetFile;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    public record SourceInfo(String path, int count, boolean available) {}

    public record CliSessionSummary(
            String sessionId, String source, String title, String agent,
            int messageCount, long lastModified
    ) {}

    public record CliTranscriptDetail(
            String sessionId, String source, String title, String agent,
            List<ParsedTurn> turns
    ) {}

    public record ParsedTurn(String role, String content) {}
}
