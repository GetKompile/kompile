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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service for reading/writing CLI chat transcripts and discovering
 * external agent conversations (Claude Code, OpenCode, Codex, Qwen).
 * Bridges CLI transcript files with the app's database-backed chat history.
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /**
     * Discover available conversation sources with counts.
     */
    public Map<String, SourceInfo> discoverSources() {
        Map<String, SourceInfo> sources = new LinkedHashMap<>();

        // Kompile CLI transcripts
        int kompileCount = countKompileTranscripts();
        sources.put(SOURCE_KOMPILE, new SourceInfo(conversationsDir.toString(), kompileCount, kompileCount > 0));

        // Claude Code
        Path claudeDir = getClaudeCodeDir();
        int claudeCount = Files.isDirectory(claudeDir) ? countClaudeCodeConversations(claudeDir) : 0;
        sources.put(SOURCE_CLAUDE_CODE, new SourceInfo(claudeDir.toString(), claudeCount, claudeCount > 0));

        // OpenCode
        Path opencodeDb = getOpenCodeDbPath();
        int opencodeCount = Files.exists(opencodeDb) ? countOpenCodeSessions(opencodeDb) : 0;
        sources.put(SOURCE_OPENCODE, new SourceInfo(opencodeDb.toString(), opencodeCount, opencodeCount > 0));

        // Codex
        Path codexHistory = getCodexHistoryPath();
        int codexCount = Files.exists(codexHistory) ? countCodexSessions(codexHistory) : 0;
        sources.put(SOURCE_CODEX, new SourceInfo(codexHistory.toString(), codexCount, codexCount > 0));

        // Qwen
        Path qwenDir = getQwenDir();
        int qwenCount = Files.isDirectory(qwenDir) ? countQwenConversations(qwenDir) : 0;
        sources.put(SOURCE_QWEN, new SourceInfo(qwenDir.toString(), qwenCount, qwenCount > 0));

        return sources;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LIST SESSIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * List CLI sessions, optionally filtered by source.
     */
    public List<CliSessionSummary> listSessions(String sourceFilter) {
        List<CliSessionSummary> all = new ArrayList<>();

        if (sourceFilter == null || sourceFilter.isEmpty() || sourceFilter.equals("all")) {
            all.addAll(listKompileSessions());
            all.addAll(listClaudeCodeSessions());
            all.addAll(listOpenCodeSessionsList());
            all.addAll(listCodexSessionsList());
            all.addAll(listQwenSessions());
        } else {
            switch (sourceFilter) {
                case SOURCE_KOMPILE -> all.addAll(listKompileSessions());
                case SOURCE_CLAUDE_CODE -> all.addAll(listClaudeCodeSessions());
                case SOURCE_OPENCODE -> all.addAll(listOpenCodeSessionsList());
                case SOURCE_CODEX -> all.addAll(listCodexSessionsList());
                case SOURCE_QWEN -> all.addAll(listQwenSessions());
            }
        }

        // Sort by lastModified descending
        all.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return all;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // READ TRANSCRIPT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Read and parse a CLI transcript into turns.
     */
    public CliTranscriptDetail readTranscript(String sessionId, String source) {
        return switch (source) {
            case SOURCE_KOMPILE -> readKompileTranscript(sessionId);
            case SOURCE_CLAUDE_CODE -> readClaudeCodeTranscript(sessionId);
            case SOURCE_OPENCODE -> readOpenCodeTranscript(sessionId);
            case SOURCE_CODEX -> readCodexTranscript(sessionId);
            case SOURCE_QWEN -> readQwenTranscript(sessionId);
            default -> throw new IllegalArgumentException("Unknown source: " + source);
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IMPORT: CLI -> App DB
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Import a CLI transcript into the app database.
     */
    public ChatSession importTranscript(String sessionId, String source) {
        CliTranscriptDetail detail = readTranscript(sessionId, source);
        if (detail == null || detail.turns().isEmpty()) {
            throw new IllegalArgumentException("No messages found in transcript: " + sessionId);
        }

        // Check for duplicate
        String importId = "imported-" + source + "-" + sanitizeId(sessionId);
        if (chatHistoryService.getSession(importId).isPresent()) {
            throw new IllegalStateException("Transcript already imported: " + sessionId);
        }

        // Create session
        ChatSession session = chatHistoryService.createSessionWithId(importId, detail.title(), source);

        // Add messages
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
    // SYNC HELPERS (used by CliTranscriptSyncService)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * List sessions from a source that haven't been imported into the DB yet.
     */
    public List<CliSessionSummary> listNewSessions(String source) {
        List<CliSessionSummary> all = listSessions(source);
        return all.stream()
                .filter(s -> {
                    String importId = "imported-" + s.source() + "-" + sanitizeId(s.sessionId());
                    return !chatHistoryService.getSession(importId).isPresent();
                })
                .toList();
    }

    /**
     * Bulk import new sessions from a source. Returns count of newly imported sessions.
     */
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

    /**
     * Export an app session to CLI transcript format.
     */
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

            // Header
            writer.println("──── Conversation: " + exportId + " ────");
            writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
            writer.println("Server:  app-export");
            writer.println("Agent:   " + (session.getSource() != null ? session.getSource() : "app"));
            writer.println("RAG:     unknown");
            writer.println();
            writer.println(SEPARATOR);
            writer.println();

            // Messages
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
    // KOMPILE TRANSCRIPT PARSING
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<CliSessionSummary> listKompileSessions() {
        List<CliSessionSummary> results = new ArrayList<>();
        if (!Files.isDirectory(conversationsDir)) return results;

        File[] files = conversationsDir.toFile().listFiles(
                (d, name) -> name.endsWith(".txt") && !name.equals("index.properties"));
        if (files == null) return results;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File file : files) {
            String sid = file.getName().replace(".txt", "");
            String title = "";
            String agent = "";
            try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Agent:")) agent = line.substring(6).trim();
                    if (line.startsWith("> ")) {
                        title = line.substring(2).trim();
                        if (title.length() > 80) title = title.substring(0, 77) + "...";
                        break;
                    }
                }
            } catch (IOException e) {
                title = "(unreadable)";
            }

            int messageCount = estimateMessageCount(file.toPath());
            results.add(new CliSessionSummary(sid, SOURCE_KOMPILE, title, agent, messageCount, file.lastModified()));
        }
        return results;
    }

    private CliTranscriptDetail readKompileTranscript(String sessionId) {
        Path file = conversationsDir.resolve(sessionId + ".txt");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Transcript not found: " + sessionId);
        }

        List<ParsedTurn> turns = parseKompileTranscriptFile(file);
        String title = turns.isEmpty() ? sessionId : turns.get(0).content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";

        String agent = "";
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.startsWith("Agent:")) {
                    agent = line.substring(6).trim();
                    break;
                }
            }
        } catch (IOException ignored) {}

        return new CliTranscriptDetail(sessionId, SOURCE_KOMPILE, title, agent, turns);
    }

    /**
     * Parse a kompile transcript file into turns.
     * Ports logic from ChatHistory.readTurns().
     */
    private List<ParsedTurn> parseKompileTranscriptFile(Path file) {
        List<ParsedTurn> turns = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            StringBuilder currentContent = new StringBuilder();
            String currentRole = null;

            for (String line : lines) {
                // Skip header lines
                if (line.startsWith("────") || line.startsWith("Started:") ||
                        line.startsWith("Server:") || line.startsWith("Agent:") ||
                        line.startsWith("RAG:") || line.equals(SEPARATOR)) {
                    continue;
                }

                // Skip system events and resume markers
                if (line.startsWith("[system]") || line.startsWith("[resumed") ||
                        line.startsWith("[tool:") || line.startsWith("[subagent:") ||
                        line.startsWith("[todo:") || line.startsWith("[agentic-step]")) {
                    continue;
                }

                if (line.startsWith("> ")) {
                    // Flush previous turn
                    if (currentRole != null && currentContent.length() > 0) {
                        turns.add(new ParsedTurn(currentRole, currentContent.toString().trim()));
                    }
                    currentRole = "user";
                    currentContent.setLength(0);
                    currentContent.append(line.substring(2));
                } else if ("user".equals(currentRole) && line.isEmpty()) {
                    if (currentContent.length() > 0) {
                        turns.add(new ParsedTurn("user", currentContent.toString().trim()));
                        currentContent.setLength(0);
                        currentRole = "assistant";
                    }
                } else if ("assistant".equals(currentRole)) {
                    if (line.startsWith("  [") && (line.contains("docs retrieved") || line.contains("completed in"))) {
                        continue;
                    }
                    if (line.startsWith("[agent:")) continue;

                    if (line.isEmpty() && currentContent.length() > 0) {
                        turns.add(new ParsedTurn("assistant", currentContent.toString().trim()));
                        currentContent.setLength(0);
                        currentRole = null;
                    } else {
                        if (currentContent.length() > 0) currentContent.append("\n");
                        currentContent.append(line);
                    }
                }
            }

            // Flush final turn
            if (currentRole != null && currentContent.length() > 0) {
                turns.add(new ParsedTurn(currentRole, currentContent.toString().trim()));
            }
        } catch (IOException e) {
            log.error("Failed to parse transcript: {}", file, e);
        }
        return turns;
    }

    private int countKompileTranscripts() {
        if (!Files.isDirectory(conversationsDir)) return 0;
        File[] files = conversationsDir.toFile().listFiles(
                (d, name) -> name.endsWith(".txt") && !name.equals("index.properties"));
        return files != null ? files.length : 0;
    }

    private int estimateMessageCount(Path file) {
        int count = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("> ")) count++;
            }
        } catch (IOException ignored) {}
        return count * 2; // user + assistant pairs
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CLAUDE CODE PARSING
    // ═══════════════════════════════════════════════════════════════════════════════

    private Path getClaudeCodeDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }

    private int countClaudeCodeConversations(Path dir) {
        return findClaudeCodeFiles(dir).size();
    }

    private List<FileInfo> findClaudeCodeFiles(Path dir) {
        List<FileInfo> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) return files;
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .forEach(p -> {
                    String id = dir.relativize(p).toString().replace(File.separator, "/");
                    files.add(new FileInfo(id, p, fileSize(p)));
                });
        } catch (IOException ignored) {}
        files.sort((a, b) -> Long.compare(b.size, a.size));
        return files;
    }

    private List<CliSessionSummary> listClaudeCodeSessions() {
        List<CliSessionSummary> results = new ArrayList<>();
        List<FileInfo> files = findClaudeCodeFiles(getClaudeCodeDir());
        for (FileInfo fi : files) {
            String title = extractFirstUserMessage(fi.path, SOURCE_CLAUDE_CODE);
            int msgCount = countJsonlMessages(fi.path);
            long lastMod = fileLastModified(fi.path);
            results.add(new CliSessionSummary(fi.id, SOURCE_CLAUDE_CODE, title, "claude", msgCount, lastMod));
        }
        return results;
    }

    private CliTranscriptDetail readClaudeCodeTranscript(String sessionId) {
        Path claudeDir = getClaudeCodeDir();
        Path file = claudeDir.resolve(sessionId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Claude Code conversation not found: " + sessionId);
        }
        List<ParsedTurn> turns = parseJsonlConversation(file);
        String title = turns.isEmpty() ? sessionId : turns.get(0).content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        return new CliTranscriptDetail(sessionId, SOURCE_CLAUDE_CODE, title, "claude", turns);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // OPENCODE PARSING (SQLite)
    // ═══════════════════════════════════════════════════════════════════════════════

    private Path getOpenCodeDbPath() {
        return Path.of(System.getProperty("user.home"), ".opencode", "opencode.db");
    }

    private int countOpenCodeSessions(Path dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            log.debug("SQLite JDBC driver not available, skipping OpenCode discovery");
            return 0;
        }
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(DISTINCT id) FROM session")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.debug("Failed to count OpenCode sessions: {}", e.getMessage());
            return 0;
        }
    }

    private List<CliSessionSummary> listOpenCodeSessionsList() {
        List<CliSessionSummary> results = new ArrayList<>();
        Path dbPath = getOpenCodeDbPath();
        if (!Files.exists(dbPath)) return results;

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            return results;
        }

        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            String query = "SELECT s.id, s.data, COUNT(m.id) as msg_count " +
                    "FROM session s LEFT JOIN message m ON s.id = m.session_id " +
                    "GROUP BY s.id ORDER BY s.time_created DESC";
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String data = rs.getString("data");
                    int msgCount = rs.getInt("msg_count");
                    String title = extractTitleFromSessionData(data);
                    results.add(new CliSessionSummary(id, SOURCE_OPENCODE, title, "opencode", msgCount * 2, System.currentTimeMillis()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to list OpenCode sessions: {}", e.getMessage());
        }
        return results;
    }

    private CliTranscriptDetail readOpenCodeTranscript(String sessionId) {
        Path dbPath = getOpenCodeDbPath();
        if (!Files.exists(dbPath)) {
            throw new IllegalArgumentException("OpenCode database not found");
        }

        List<ParsedTurn> turns = new ArrayList<>();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not available");
        }

        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            String query = "SELECT m.id, m.data as message_data, p.data as part_data " +
                    "FROM message m LEFT JOIN part p ON m.id = p.message_id " +
                    "WHERE m.session_id = ? ORDER BY m.time_created ASC, p.time_created ASC";
            try (var stmt = conn.prepareStatement(query)) {
                stmt.setString(1, sessionId);
                try (var rs = stmt.executeQuery()) {
                    String lastMessageId = null;
                    StringBuilder currentContent = new StringBuilder();
                    String currentRole = null;

                    while (rs.next()) {
                        String messageId = rs.getString("id");
                        String messageData = rs.getString("message_data");
                        String partData = rs.getString("part_data");

                        if (!messageId.equals(lastMessageId)) {
                            if (currentRole != null && currentContent.length() > 0) {
                                turns.add(new ParsedTurn(currentRole, currentContent.toString().trim()));
                            }
                            try {
                                JsonNode msgNode = MAPPER.readTree(messageData);
                                String role = msgNode.path("role").asText("");
                                currentRole = "user".equals(role) ? "user" : "assistant";
                            } catch (Exception e) {
                                currentRole = "user";
                            }
                            currentContent.setLength(0);
                            lastMessageId = messageId;
                        }

                        if (partData != null && !partData.isBlank()) {
                            try {
                                JsonNode partNode = MAPPER.readTree(partData);
                                String text = partNode.path("text").asText("");
                                if (text.isBlank()) text = partNode.path("content").asText("");
                                if (!text.isBlank()) {
                                    if (currentContent.length() > 0) currentContent.append("\n");
                                    currentContent.append(text);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (currentRole != null && currentContent.length() > 0) {
                        turns.add(new ParsedTurn(currentRole, currentContent.toString().trim()));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read OpenCode transcript: {}", sessionId, e);
        }

        String title = turns.isEmpty() ? sessionId : turns.get(0).content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        return new CliTranscriptDetail(sessionId, SOURCE_OPENCODE, title, "opencode", turns);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CODEX PARSING (JSONL)
    // ═══════════════════════════════════════════════════════════════════════════════

    private Path getCodexHistoryPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "history.jsonl");
    }

    private int countCodexSessions(Path historyFile) {
        Set<String> sessions = new HashSet<>();
        try {
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sid = node.path("session_id").asText("");
                    if (!sid.isBlank()) sessions.add(sid);
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return sessions.size();
    }

    private List<CliSessionSummary> listCodexSessionsList() {
        List<CliSessionSummary> results = new ArrayList<>();
        Path historyFile = getCodexHistoryPath();
        if (!Files.exists(historyFile)) return results;

        Map<String, CodexSessionInfo> sessions = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sid = node.path("session_id").asText("");
                    if (sid.isBlank()) continue;
                    sessions.computeIfAbsent(sid, k -> new CodexSessionInfo(k, "", 0));
                    CodexSessionInfo info = sessions.get(sid);
                    info.messageCount++;

                    String role = node.path("role").asText("");
                    if ("user".equals(role) && info.title.isEmpty()) {
                        String content = node.path("content").asText("");
                        if (!content.isBlank()) {
                            info.title = content.length() > 80 ? content.substring(0, 77) + "..." : content;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}

        for (CodexSessionInfo info : sessions.values()) {
            results.add(new CliSessionSummary(info.sessionId, SOURCE_CODEX, info.title, "codex",
                    info.messageCount, fileLastModified(historyFile)));
        }
        return results;
    }

    private CliTranscriptDetail readCodexTranscript(String sessionId) {
        Path historyFile = getCodexHistoryPath();
        if (!Files.exists(historyFile)) {
            throw new IllegalArgumentException("Codex history file not found");
        }

        List<ParsedTurn> turns = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sid = node.path("session_id").asText("");
                    if (!sessionId.equals(sid)) continue;

                    String role = node.path("role").asText("");
                    String content = node.path("content").asText("");
                    if (!content.isBlank() && ("user".equals(role) || "assistant".equals(role))) {
                        turns.add(new ParsedTurn(role, content));
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            log.error("Failed to read Codex transcript: {}", sessionId, e);
        }

        String title = turns.isEmpty() ? sessionId : turns.get(0).content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        return new CliTranscriptDetail(sessionId, SOURCE_CODEX, title, "codex", turns);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // QWEN PARSING
    // ═══════════════════════════════════════════════════════════════════════════════

    private Path getQwenDir() {
        return Path.of(System.getProperty("user.home"), ".qwen", "projects");
    }

    private int countQwenConversations(Path dir) {
        return findQwenFiles(dir).size();
    }

    private List<FileInfo> findQwenFiles(Path dir) {
        List<FileInfo> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) return files;
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .forEach(p -> {
                    String id = dir.relativize(p).toString().replace(File.separator, "/");
                    files.add(new FileInfo(id, p, fileSize(p)));
                });
        } catch (IOException ignored) {}
        return files;
    }

    private List<CliSessionSummary> listQwenSessions() {
        List<CliSessionSummary> results = new ArrayList<>();
        List<FileInfo> files = findQwenFiles(getQwenDir());
        for (FileInfo fi : files) {
            String title = extractFirstUserMessage(fi.path, SOURCE_QWEN);
            int msgCount = countJsonlMessages(fi.path);
            long lastMod = fileLastModified(fi.path);
            results.add(new CliSessionSummary(fi.id, SOURCE_QWEN, title, "qwen", msgCount, lastMod));
        }
        return results;
    }

    private CliTranscriptDetail readQwenTranscript(String sessionId) {
        Path qwenDir = getQwenDir();
        Path file = qwenDir.resolve(sessionId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Qwen conversation not found: " + sessionId);
        }
        List<ParsedTurn> turns = parseJsonlConversation(file);
        String title = turns.isEmpty() ? sessionId : turns.get(0).content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        return new CliTranscriptDetail(sessionId, SOURCE_QWEN, title, "qwen", turns);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SHARED PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Parse a JSONL conversation file (Claude Code / Qwen format).
     */
    private List<ParsedTurn> parseJsonlConversation(Path file) {
        List<ParsedTurn> turns = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String role = extractRole(node);
                    String content = extractContent(node);
                    if (role != null && content != null && !content.isBlank()) {
                        turns.add(new ParsedTurn(role, content));
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            log.error("Failed to parse JSONL: {}", file, e);
        }
        return turns;
    }

    private String extractRole(JsonNode node) {
        // Try "role" field
        String role = node.path("role").asText("");
        if ("user".equals(role) || "human".equals(role)) return "user";
        if ("assistant".equals(role)) return "assistant";

        // Try "type" field (Claude Code format)
        String type = node.path("type").asText("");
        if ("human".equals(type)) return "user";
        if ("assistant".equals(type)) return "assistant";

        return null;
    }

    private String extractContent(JsonNode node) {
        // Try "content" field directly as string
        JsonNode contentNode = node.get("content");
        if (contentNode == null) return null;

        if (contentNode.isTextual()) {
            return contentNode.asText();
        }

        // content is an array (Claude Code format): [{"type":"text","text":"..."}]
        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : contentNode) {
                if ("text".equals(item.path("type").asText(""))) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return null;
    }

    private String extractFirstUserMessage(Path file, String source) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String role = extractRole(node);
                    if ("user".equals(role)) {
                        String content = extractContent(node);
                        if (content != null && !content.isBlank()) {
                            return content.length() > 80 ? content.substring(0, 77) + "..." : content;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return "(no title)";
    }

    private String extractTitleFromSessionData(String data) {
        if (data == null) return "(no title)";
        try {
            JsonNode node = MAPPER.readTree(data);
            String title = node.path("title").asText("");
            if (!title.isBlank()) return title;
        } catch (Exception ignored) {}
        return "(no title)";
    }

    private int countJsonlMessages(Path file) {
        int count = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) count++;
            }
        } catch (IOException ignored) {}
        return count;
    }

    private long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private long fileLastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

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

    private record FileInfo(String id, Path path, long size) {}

    private static class CodexSessionInfo {
        String sessionId;
        String title;
        int messageCount;

        CodexSessionInfo(String sessionId, String title, int messageCount) {
            this.sessionId = sessionId;
            this.title = title;
            this.messageCount = messageCount;
        }
    }
}
