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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool that imports conversations from external CLI tools (Claude Code, OpenCode, Codex)
 * into kompile's plain-text transcript format at {@code ~/.kompile/conversations/}.
 * <p>
 * Actions:
 * <ul>
 *   <li><b>discover</b> - Auto-discover all conversation directories from Claude Code, OpenCode, and Codex</li>
 *   <li><b>list</b> - List conversations from a specific source (claude-code, opencode, codex)</li>
 *   <li><b>import</b> - Import a specific conversation by source and ID into kompile format</li>
 *   <li><b>import-all</b> - Import all conversations from a source</li>
 * </ul>
 */
public class ConversationImportTool implements CliTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final String SEPARATOR = "──────────────────────────────────";

    private static final String SOURCE_CLAUDE_CODE = "claude-code";
    private static final String SOURCE_OPENCODE = "opencode";
    private static final String SOURCE_CODEX = "codex";
    private static final String SOURCE_QWEN = "qwen";

    @Override
    public String id() {
        return "conversation_import";
    }

    @Override
    public String description() {
        return "Import conversations from external CLI tools into kompile's transcript format. " +
                "Supports Claude Code (~/.claude/projects/), OpenCode (SQLite database), " +
                "Codex (~/.codex/history.jsonl), and Qwen (~/.qwen/projects/). " +
                "Use 'discover' to find available sources, 'list' to see conversations from a source, " +
                "'import' to import a specific conversation, or 'import-all' to import everything from a source.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action to perform: 'discover', 'list', 'import', or 'import-all'");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "Source to import from: 'claude-code', 'opencode', or 'codex'. Required for 'list', 'import', and 'import-all'.");

        ObjectNode conversationId = props.putObject("conversation_id");
        conversationId.put("type", "string");
        conversationId.put("description", "Conversation ID or filename to import. Required for 'import' action.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() {
        return "conversation_import";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Import conversations from external CLI tools");

        String action = params.path("action").asText("");
        String source = params.path("source").asText("");
        String conversationId = params.path("conversation_id").asText("");

        switch (action) {
            case "discover":
                return doDiscover();
            case "list":
                return doList(source);
            case "import":
                return doImport(source, conversationId);
            case "import-all":
                return doImportAll(source);
            default:
                return ToolResult.error("Unknown action: " + action +
                        ". Use 'discover', 'list', 'import', or 'import-all'.");
        }
    }

    // ─── discover ───────────────────────────────────────────────────────

    private ToolResult doDiscover() {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation source discovery:\n\n");
        int totalFound = 0;

        // Claude Code
        Path claudeDir = getClaudeCodeDir();
        if (Files.isDirectory(claudeDir)) {
            int count = countClaudeCodeConversations(claudeDir);
            sb.append("  claude-code: ").append(claudeDir).append("\n");
            sb.append("    Found ").append(count).append(" conversation file(s)\n\n");
            totalFound += count;
        } else {
            sb.append("  claude-code: not found (").append(claudeDir).append(")\n\n");
        }

        // OpenCode - SQLite database
        Path opencodeDb = getOpenCodeDbPath();
        if (Files.exists(opencodeDb)) {
            int count = countOpenCodeSessions(opencodeDb);
            sb.append("  opencode: ").append(opencodeDb).append("\n");
            sb.append("    Found ").append(count).append(" session(s) in SQLite database\n\n");
            totalFound += count;
        } else {
            sb.append("  opencode: not found (").append(opencodeDb).append(")\n\n");
        }

        // Codex - JSONL history file
        Path codexHistory = getCodexHistoryPath();
        if (Files.exists(codexHistory)) {
            int count = countCodexSessions(codexHistory);
            sb.append("  codex: ").append(codexHistory).append("\n");
            sb.append("    Found ").append(count).append(" session(s) in JSONL file\n\n");
            totalFound += count;
        } else {
            sb.append("  codex: not found (").append(codexHistory).append(")\n\n");
        }

        // Qwen
        Path qwenDir = getQwenDir();
        if (Files.isDirectory(qwenDir)) {
            int count = countQwenConversations(qwenDir);
            sb.append("  qwen: ").append(qwenDir).append("\n");
            sb.append("    Found ").append(count).append(" conversation file(s)\n\n");
            totalFound += count;
        } else {
            sb.append("  qwen: not found (").append(qwenDir).append(")\n\n");
        }

        sb.append("Total discoverable conversations: ").append(totalFound);

        return ToolResult.success("conversation_import: discover", sb.toString(),
                Map.of("totalFound", totalFound));
    }

    // ─── list ───────────────────────────────────────────────────────────

    private ToolResult doList(String source) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'list' action. Use 'claude-code', 'opencode', 'codex', or 'qwen'.");
        }

        switch (source) {
            case SOURCE_CLAUDE_CODE:
                return listClaudeCode();
            case SOURCE_OPENCODE:
                return listOpenCode();
            case SOURCE_CODEX:
                return listCodex();
            case SOURCE_QWEN:
                return listQwen();
            default:
                return ToolResult.error("Unknown source: " + source +
                        ". Use 'claude-code', 'opencode', 'codex', or 'qwen'.");
        }
    }

    private ToolResult listClaudeCode() {
        Path claudeDir = getClaudeCodeDir();
        if (!Files.isDirectory(claudeDir)) {
            return ToolResult.success("Claude Code directory not found: " + claudeDir);
        }

        List<FileInfo> files = findClaudeCodeFiles(claudeDir);
        if (files.isEmpty()) {
            return ToolResult.success("No .jsonl conversation files found in " + claudeDir);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Claude Code conversations (").append(files.size()).append(" files):\n\n");
        for (FileInfo fi : files) {
            sb.append("  ").append(fi.id).append("  (").append(formatSize(fi.size)).append(")\n");
            sb.append("    Path: ").append(fi.path).append("\n");
        }

        return ToolResult.success("conversation_import: list claude-code", sb.toString(),
                Map.of("count", files.size()));
    }

    private ToolResult listOpenCode() {
        Path opencodeDb = getOpenCodeDbPath();
        if (!Files.exists(opencodeDb)) {
            return ToolResult.success("OpenCode database not found: " + opencodeDb);
        }

        try {
            List<OpenCodeSession> sessions = listOpenCodeSessions(opencodeDb);
            if (sessions.isEmpty()) {
                return ToolResult.success("No sessions found in OpenCode database");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("OpenCode sessions (").append(sessions.size()).append(" sessions):\n\n");
            for (OpenCodeSession s : sessions) {
                sb.append("  ").append(s.id).append("  ")
                  .append(s.title.isEmpty() ? "(no title)" : s.title)
                  .append("  (").append(s.messageCount).append(" messages)\n");
            }

            return ToolResult.success("conversation_import: list opencode", sb.toString(),
                    Map.of("count", sessions.size()));
        } catch (SQLException e) {
            return ToolResult.error("Failed to query OpenCode database: " + e.getMessage());
        }
    }

    private ToolResult listCodex() {
        Path codexHistory = getCodexHistoryPath();
        if (!Files.exists(codexHistory)) {
            return ToolResult.success("Codex history file not found: " + codexHistory);
        }

        try {
            Map<String, CodexSession> sessions = listCodexSessions(codexHistory);
            if (sessions.isEmpty()) {
                return ToolResult.success("No sessions found in Codex history");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Codex conversations (").append(sessions.size()).append(" sessions):\n\n");
            for (CodexSession s : sessions.values()) {
                sb.append("  ").append(s.sessionId).append("  (").append(s.messageCount).append(" messages)\n");
            }

            return ToolResult.success("conversation_import: list codex", sb.toString(),
                    Map.of("count", sessions.size()));
        } catch (IOException e) {
            return ToolResult.error("Failed to read Codex history: " + e.getMessage());
        }
    }

    private ToolResult listQwen() {
        Path qwenDir = getQwenDir();
        if (!Files.isDirectory(qwenDir)) {
            return ToolResult.success("Qwen directory not found: " + qwenDir);
        }

        List<FileInfo> files = findQwenFiles(qwenDir);
        if (files.isEmpty()) {
            return ToolResult.success("No conversation files found in " + qwenDir);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Qwen conversations (").append(files.size()).append(" files):\n\n");
        for (FileInfo fi : files) {
            sb.append("  ").append(fi.id).append("  (").append(formatSize(fi.size)).append(")\n");
            sb.append("    Path: ").append(fi.path).append("\n");
        }

        return ToolResult.success("conversation_import: list qwen", sb.toString(),
                Map.of("count", files.size()));
    }

    // ─── import ─────────────────────────────────────────────────────────

    private ToolResult doImport(String source, String conversationId) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'import' action.");
        }
        if (conversationId.isEmpty()) {
            return ToolResult.error("'conversation_id' is required for 'import' action.");
        }

        switch (source) {
            case SOURCE_CLAUDE_CODE:
                return importClaudeCodeConversation(conversationId);
            case SOURCE_OPENCODE:
                return importOpenCodeConversation(conversationId);
            case SOURCE_CODEX:
                return importCodexConversation(conversationId);
            case SOURCE_QWEN:
                return importQwenConversation(conversationId);
            default:
                return ToolResult.error("Unknown source: " + source);
        }
    }

    private ToolResult doImportAll(String source) {
        if (source.isEmpty()) {
            return ToolResult.error("'source' is required for 'import-all' action.");
        }

        switch (source) {
            case SOURCE_CLAUDE_CODE:
                return importAllClaudeCode();
            case SOURCE_OPENCODE:
                return importAllOpenCode();
            case SOURCE_CODEX:
                return importAllCodex();
            case SOURCE_QWEN:
                return importAllQwen();
            default:
                return ToolResult.error("Unknown source: " + source);
        }
    }

    private ToolResult importAllClaudeCode() {
        List<FileInfo> files = findClaudeCodeFiles(getClaudeCodeDir());
        return doImportAllFiles(SOURCE_CLAUDE_CODE, files);
    }

    private ToolResult importAllOpenCode() {
        Path opencodeDb = getOpenCodeDbPath();
        if (!Files.exists(opencodeDb)) {
            return ToolResult.success("OpenCode database not found: " + opencodeDb);
        }
        try {
            List<OpenCodeSession> sessions = listOpenCodeSessions(opencodeDb);
            int imported = 0;
            int skipped = 0;
            int failed = 0;
            StringBuilder details = new StringBuilder();

            for (OpenCodeSession session : sessions) {
                try {
                    String targetId = "imported-" + SOURCE_OPENCODE + "-" + sanitizeId(session.id);
                    Path targetFile = getConversationsDir().resolve(targetId + ".txt");

                    if (Files.exists(targetFile)) {
                        skipped++;
                        details.append("  SKIPPED (exists): ").append(session.id).append("\n");
                        continue;
                    }

                    List<Message> messages = parseOpenCodeSqlite(opencodeDb, session.id);
                    if (messages.isEmpty()) {
                        skipped++;
                        details.append("  SKIPPED (empty): ").append(session.id).append("\n");
                        continue;
                    }

                    writeTranscript(targetId, SOURCE_OPENCODE, messages);
                    imported++;
                    details.append("  IMPORTED: ").append(session.id).append(" -> ").append(targetId).append("\n");
                } catch (Exception e) {
                    failed++;
                    details.append("  FAILED: ").append(session.id).append(" - ").append(e.getMessage()).append("\n");
                }
            }

            return buildImportResult(SOURCE_OPENCODE, imported, skipped, failed, details);
        } catch (SQLException e) {
            return ToolResult.error("Failed to query OpenCode database: " + e.getMessage());
        }
    }

    private ToolResult importAllCodex() {
        Path codexHistory = getCodexHistoryPath();
        if (!Files.exists(codexHistory)) {
            return ToolResult.success("Codex history file not found: " + codexHistory);
        }
        try {
            Map<String, CodexSession> sessions = listCodexSessions(codexHistory);
            int imported = 0;
            int skipped = 0;
            int failed = 0;
            StringBuilder details = new StringBuilder();

            for (CodexSession session : sessions.values()) {
                try {
                    String targetId = "imported-" + SOURCE_CODEX + "-" + sanitizeId(session.sessionId);
                    Path targetFile = getConversationsDir().resolve(targetId + ".txt");

                    if (Files.exists(targetFile)) {
                        skipped++;
                        details.append("  SKIPPED (exists): ").append(session.sessionId).append("\n");
                        continue;
                    }

                    List<Message> messages = parseCodexJsonl(codexHistory, session.sessionId);
                    if (messages.isEmpty()) {
                        skipped++;
                        details.append("  SKIPPED (empty): ").append(session.sessionId).append("\n");
                        continue;
                    }

                    writeTranscript(targetId, SOURCE_CODEX, messages);
                    imported++;
                    details.append("  IMPORTED: ").append(session.sessionId).append(" -> ").append(targetId).append("\n");
                } catch (Exception e) {
                    failed++;
                    details.append("  FAILED: ").append(session.sessionId).append(" - ").append(e.getMessage()).append("\n");
                }
            }

            return buildImportResult(SOURCE_CODEX, imported, skipped, failed, details);
        } catch (IOException e) {
            return ToolResult.error("Failed to read Codex history: " + e.getMessage());
        }
    }

    private ToolResult importAllQwen() {
        List<FileInfo> files = findQwenFiles(getQwenDir());
        return doImportAllFiles(SOURCE_QWEN, files);
    }

    private ToolResult doImportAllFiles(String source, List<FileInfo> files) {
        if (files.isEmpty()) {
            return ToolResult.success("No conversations found for source: " + source);
        }

        int imported = 0;
        int skipped = 0;
        int failed = 0;
        StringBuilder details = new StringBuilder();

        for (FileInfo fi : files) {
            try {
                String targetId = "imported-" + source + "-" + sanitizeId(fi.id);
                Path targetFile = getConversationsDir().resolve(targetId + ".txt");

                if (Files.exists(targetFile)) {
                    skipped++;
                    details.append("  SKIPPED (exists): ").append(fi.id).append("\n");
                    continue;
                }

                List<Message> messages;
                switch (source) {
                    case SOURCE_CLAUDE_CODE:
                        messages = parseClaudeCodeJsonl(fi.path);
                        break;
                    case SOURCE_QWEN:
                        messages = parseQwenJsonl(fi.path);
                        break;
                    default:
                        messages = Collections.emptyList();
                }

                if (messages.isEmpty()) {
                    skipped++;
                    details.append("  SKIPPED (empty): ").append(fi.id).append("\n");
                    continue;
                }

                writeTranscript(targetId, source, messages);
                imported++;
                details.append("  IMPORTED: ").append(fi.id).append(" -> ").append(targetId).append("\n");
            } catch (Exception e) {
                failed++;
                details.append("  FAILED: ").append(fi.id).append(" - ").append(e.getMessage()).append("\n");
            }
        }

        return buildImportResult(source, imported, skipped, failed, details);
    }

    private ToolResult buildImportResult(String source, int imported, int skipped, int failed, StringBuilder details) {
        StringBuilder sb = new StringBuilder();
        sb.append("Import from ").append(source).append(" complete:\n");
        sb.append("  Imported: ").append(imported).append("\n");
        sb.append("  Skipped:  ").append(skipped).append("\n");
        sb.append("  Failed:   ").append(failed).append("\n\n");
        sb.append("Details:\n").append(details);

        return ToolResult.success("conversation_import: import-all " + source, sb.toString(),
                Map.of("imported", imported, "skipped", skipped, "failed", failed));
    }

    private ToolResult importClaudeCodeConversation(String conversationId) {
        Path claudeDir = getClaudeCodeDir();
        List<FileInfo> files = findClaudeCodeFiles(claudeDir);

        FileInfo target = findFileById(files, conversationId);
        if (target == null) {
            return ToolResult.error("Conversation not found: " + conversationId +
                    ". Use 'list' action with source='claude-code' to see available conversations.");
        }

        try {
            List<Message> messages = parseClaudeCodeJsonl(target.path);
            if (messages.isEmpty()) {
                return ToolResult.success("No parseable messages found in: " + conversationId);
            }

            String targetId = "imported-" + SOURCE_CLAUDE_CODE + "-" + sanitizeId(target.id);
            writeTranscript(targetId, SOURCE_CLAUDE_CODE, messages);

            return ToolResult.success("conversation_import: import",
                    "Imported " + messages.size() + " messages from Claude Code conversation '" +
                            conversationId + "' as '" + targetId + "'",
                    Map.of("targetId", targetId, "messageCount", messages.size()));
        } catch (Exception e) {
            return ToolResult.error("Failed to import: " + e.getMessage());
        }
    }

    private ToolResult importOpenCodeConversation(String conversationId) {
        Path opencodeDb = getOpenCodeDbPath();
        if (!Files.exists(opencodeDb)) {
            return ToolResult.error("OpenCode database not found: " + opencodeDb);
        }

        try {
            List<Message> messages = parseOpenCodeSqlite(opencodeDb, conversationId);
            if (messages.isEmpty()) {
                return ToolResult.success("No parseable messages found in session: " + conversationId);
            }

            String targetId = "imported-" + SOURCE_OPENCODE + "-" + sanitizeId(conversationId);
            writeTranscript(targetId, SOURCE_OPENCODE, messages);

            return ToolResult.success("conversation_import: import",
                    "Imported " + messages.size() + " messages from OpenCode session '" +
                            conversationId + "' as '" + targetId + "'",
                    Map.of("targetId", targetId, "messageCount", messages.size()));
        } catch (SQLException | IOException e) {
            return ToolResult.error("Failed to import: " + e.getMessage());
        }
    }

    private ToolResult importCodexConversation(String conversationId) {
        Path codexHistory = getCodexHistoryPath();
        if (!Files.exists(codexHistory)) {
            return ToolResult.error("Codex history file not found: " + codexHistory);
        }

        try {
            List<Message> messages = parseCodexJsonl(codexHistory, conversationId);
            if (messages.isEmpty()) {
                return ToolResult.success("No parseable messages found in: " + conversationId);
            }

            String targetId = "imported-" + SOURCE_CODEX + "-" + sanitizeId(conversationId);
            writeTranscript(targetId, SOURCE_CODEX, messages);

            return ToolResult.success("conversation_import: import",
                    "Imported " + messages.size() + " messages from Codex conversation '" +
                            conversationId + "' as '" + targetId + "'",
                    Map.of("targetId", targetId, "messageCount", messages.size()));
        } catch (IOException e) {
            return ToolResult.error("Failed to import: " + e.getMessage());
        }
    }

    private ToolResult importQwenConversation(String conversationId) {
        Path qwenDir = getQwenDir();
        List<FileInfo> files = findQwenFiles(qwenDir);

        FileInfo target = findFileById(files, conversationId);
        if (target == null) {
            return ToolResult.error("Conversation not found: " + conversationId +
                    ". Use 'list' action with source='qwen' to see available conversations.");
        }

        try {
            List<Message> messages = parseQwenJsonl(target.path);
            if (messages.isEmpty()) {
                return ToolResult.success("No parseable messages found in: " + conversationId);
            }

            String targetId = "imported-" + SOURCE_QWEN + "-" + sanitizeId(target.id);
            writeTranscript(targetId, SOURCE_QWEN, messages);

            return ToolResult.success("conversation_import: import",
                    "Imported " + messages.size() + " messages from Qwen conversation '" +
                            conversationId + "' as '" + targetId + "'",
                    Map.of("targetId", targetId, "messageCount", messages.size()));
        } catch (Exception e) {
            return ToolResult.error("Failed to import: " + e.getMessage());
        }
    }

    // ─── Parsing: Claude Code (.jsonl) ──────────────────────────────────

    /**
     * Parses Claude Code JSONL files. Each line is a JSON object.
     * We look for objects with "type" of "human" or "assistant", or
     * objects with a "role" field containing "user"/"assistant"
     * and a "content" field.
     */
    private List<Message> parseClaudeCodeJsonl(Path file) {
        List<Message> messages = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String role = extractRole(node);
                    String content = extractContent(node);
                    if (role != null && content != null && !content.isBlank()) {
                        messages.add(new Message(role, content));
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Return whatever we have
        }
        return messages;
    }

    // ─── Parsing: OpenCode (JSON) ───────────────────────────────────────

    /**
     * Parses OpenCode JSON session files. Expected structure:
     * { "messages": [ { "role": "user", "content": "..." }, ... ] }
     * Also handles: { "session": { "messages": [...] } }
     */
    private List<Message> parseOpenCodeJson(Path file) {
        List<Message> messages = new ArrayList<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(content);
            JsonNode messagesNode = findMessagesArray(root);
            if (messagesNode != null && messagesNode.isArray()) {
                for (JsonNode msg : messagesNode) {
                    String role = extractRole(msg);
                    String text = extractContent(msg);
                    if (role != null && text != null && !text.isBlank()) {
                        messages.add(new Message(role, text));
                    }
                }
            }
        } catch (Exception e) {
            // Return whatever we have
        }
        return messages;
    }

    // ─── Parsing: Codex (JSON) ──────────────────────────────────────────

    /**
     * Parses Codex JSON conversation files. Expected structure:
     * { "messages": [ { "role": "user", "content": "..." }, ... ] }
     * Also handles: { "conversation": { "messages": [...] } }
     * or { "items": [ { "role": "user", "content": "..." }, ... ] }
     */
    private List<Message> parseCodexJson(Path file) {
        List<Message> messages = new ArrayList<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(content);
            JsonNode messagesNode = findMessagesArray(root);
            if (messagesNode != null && messagesNode.isArray()) {
                for (JsonNode msg : messagesNode) {
                    String role = extractRole(msg);
                    String text = extractContent(msg);
                    if (role != null && text != null && !text.isBlank()) {
                        messages.add(new Message(role, text));
                    }
                }
            }
        } catch (Exception e) {
            // Return whatever we have
        }
        return messages;
    }

    // ─── Parsing: OpenCode (SQLite) ─────────────────────────────────────

    /**
     * Parses OpenCode SQLite database for a specific session.
     * Queries the message and part tables to reconstruct the conversation.
     */
    private List<Message> parseOpenCodeSqlite(Path dbPath, String sessionId) throws SQLException {
        List<Message> messages = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            // Query messages for this session, ordered by creation time
            String query = """
                SELECT m.id, m.data as message_data, p.data as part_data
                FROM message m
                LEFT JOIN part p ON m.id = p.message_id
                WHERE m.session_id = ?
                ORDER BY m.time_created ASC, p.time_created ASC
            """;
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, sessionId);
                try (ResultSet rs = stmt.executeQuery()) {
                    String lastMessageId = null;
                    StringBuilder currentContent = new StringBuilder();
                    String currentRole = null;

                    while (rs.next()) {
                        String messageId = rs.getString("id");
                        String messageData = rs.getString("message_data");
                        String partData = rs.getString("part_data");

                        // New message detected
                        if (!messageId.equals(lastMessageId)) {
                            // Save previous message
                            if (currentRole != null && currentContent.length() > 0) {
                                messages.add(new Message(currentRole, currentContent.toString().trim()));
                            }

                            // Extract role from message data
                            try {
                                JsonNode msgNode = MAPPER.readTree(messageData);
                                String role = msgNode.path("role").asText("");
                                currentRole = "user".equals(role) ? "user" : "assistant";
                            } catch (Exception e) {
                                currentRole = "user"; // Default
                            }
                            currentContent.setLength(0);
                            lastMessageId = messageId;
                        }

                        // Extract text from part data
                        if (partData != null && !partData.isBlank()) {
                            try {
                                JsonNode partNode = MAPPER.readTree(partData);
                                String type = partNode.path("type").asText("");
                                if ("text".equals(type)) {
                                    String text = partNode.path("text").asText("");
                                    if (!text.isBlank()) {
                                        if (currentContent.length() > 0) {
                                            currentContent.append("\n");
                                        }
                                        currentContent.append(text);
                                    }
                                }
                            } catch (Exception e) {
                                // Skip unparseable parts
                            }
                        }
                    }

                    // Save final message
                    if (currentRole != null && currentContent.length() > 0) {
                        messages.add(new Message(currentRole, currentContent.toString().trim()));
                    }
                }
            }
        }
        return messages;
    }

    // ─── Parsing: Codex (JSONL history) ─────────────────────────────────

    /**
     * Parses Codex history.jsonl file for a specific session.
     * Format: {"session_id": "...", "ts": 1234567890, "text": "..."}
     */
    private List<Message> parseCodexJsonl(Path file, String sessionId) throws IOException {
        List<Message> messages = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String nodeSessionId = node.path("session_id").asText("");

                // Filter by session ID
                if (!sessionId.equals(nodeSessionId)) {
                    continue;
                }

                String text = node.path("text").asText("");
                if (!text.isBlank()) {
                    // Codex doesn't have explicit role - all entries are user messages
                    messages.add(new Message("user", text));
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }

        return messages;
    }

    // ─── Parsing: Qwen (JSONL) ──────────────────────────────────────────

    /**
     * Parses Qwen JSONL conversation files.
     * Format: {"type": "user|assistant", "message": {"role": "...", "parts": [{"text": "..."}]}}
     */
    private List<Message> parseQwenJsonl(Path file) {
        List<Message> messages = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);

                    // Skip system/telemetry messages
                    String type = node.path("type").asText("");
                    if (!"user".equals(type) && !"assistant".equals(type)) {
                        continue;
                    }

                    String role = extractRole(node);
                    String content = extractQwenContent(node);
                    if (role != null && content != null && !content.isBlank()) {
                        messages.add(new Message(role, content));
                    }
                } catch (Exception e) {
                    // Skip unparseable lines
                }
            }
        } catch (IOException e) {
            // Return whatever we have
        }
        return messages;
    }

    private String extractQwenContent(JsonNode node) {
        JsonNode messageNode = node.path("message");
        JsonNode partsNode = messageNode.path("parts");
        if (partsNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : partsNode) {
                String text = part.path("text").asText();
                if (!text.isBlank()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(text);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        // Fallback: check for direct text field
        String text = node.path("text").asText();
        if (!text.isBlank()) {
            return text;
        }
        return null;
    }

    // ─── Transcript writer ──────────────────────────────────────────────

    private void writeTranscript(String targetId, String source, List<Message> messages) throws IOException {
        Path conversationsDir = getConversationsDir();
        Files.createDirectories(conversationsDir);

        Path targetFile = conversationsDir.resolve(targetId + ".txt");
        String agentName = getAgentName(source);

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(targetFile.toFile(), false),
                        StandardCharsets.UTF_8)), true)) {

            writer.println("──── Conversation: " + targetId + " ────");
            writer.println("Started: " + TIMESTAMP_FMT.format(Instant.now()));
            writer.println("Server:  (imported from " + source + ")");
            writer.println("Agent:   " + agentName);
            writer.println("RAG:     disabled");
            writer.println();
            writer.println(SEPARATOR);
            writer.println();

            for (Message msg : messages) {
                if ("user".equals(msg.role)) {
                    writer.println("> " + msg.content);
                    writer.println();
                } else {
                    writer.println(msg.content);
                    writer.println();
                }
            }
        }
    }

    // ─── Path helpers ───────────────────────────────────────────────────

    private Path getClaudeCodeDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "projects");
    }

    private Path getOpenCodeDir() {
        return Path.of(System.getProperty("user.home"), ".opencode", "sessions");
    }

    private Path getOpenCodeDbPath() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
    }

    private Path getCodexHistoryPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "history.jsonl");
    }

    private List<Path> getCodexDirs() {
        return Arrays.asList(
                Path.of(System.getProperty("user.home"), ".codex", "conversations"),
                Path.of(System.getProperty("user.home"), ".codex", "history")
        );
    }

    private Path getQwenDir() {
        return Path.of(System.getProperty("user.home"), ".qwen", "projects");
    }

    private Path getConversationsDir() {
        return KompileHome.homeDirectory().toPath().resolve("conversations");
    }

    // ─── File discovery helpers ─────────────────────────────────────────

    private int countClaudeCodeConversations(Path claudeDir) {
        return findClaudeCodeFiles(claudeDir).size();
    }

    private int countOpenCodeSessions(Path dbPath) {
        if (!Files.exists(dbPath)) return 0;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM session")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            // Ignore
        }
        return 0;
    }

    private int countCodexSessions(Path historyPath) {
        if (!Files.exists(historyPath)) return 0;
        try {
            Set<String> sessionIds = new HashSet<>();
            for (String line : Files.readAllLines(historyPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String sessionId = node.path("session_id").asText("");
                    if (!sessionId.isBlank()) {
                        sessionIds.add(sessionId);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
            return sessionIds.size();
        } catch (IOException e) {
            return 0;
        }
    }

    private int countQwenConversations(Path qwenDir) {
        return findQwenFiles(qwenDir).size();
    }

    /**
     * Find all .jsonl files recursively under the Claude Code projects directory.
     * Claude Code stores conversations in project subdirectories.
     */
    private List<FileInfo> findClaudeCodeFiles(Path claudeDir) {
        List<FileInfo> results = new ArrayList<>();
        if (!Files.isDirectory(claudeDir)) return results;

        try (Stream<Path> walk = Files.walk(claudeDir, 5)) {
            walk.filter(p -> p.toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            // Use relative path from claude dir as ID
                            String relPath = claudeDir.relativize(p).toString();
                            String id = relPath.replace(File.separator, "_")
                                    .replaceAll("\\.jsonl$", "");
                            results.add(new FileInfo(id, p, Files.size(p)));
                        } catch (IOException e) {
                            // skip
                        }
                    });
        } catch (IOException e) {
            // Return whatever we found
        }

        results.sort(Comparator.comparing(fi -> fi.id));
        return results;
    }

    /**
     * Find all .json files in a directory (non-recursive).
     */
    private List<FileInfo> findJsonFiles(Path dir) {
        List<FileInfo> results = new ArrayList<>();
        if (!Files.isDirectory(dir)) return results;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    String name = p.getFileName().toString().replaceAll("\\.json$", "");
                    results.add(new FileInfo(name, p, Files.size(p)));
                }
            }
        } catch (IOException e) {
            // Return whatever we found
        }

        results.sort(Comparator.comparing(fi -> fi.id));
        return results;
    }

    /**
     * Find all .jsonl files recursively under the Qwen projects directory.
     */
    private List<FileInfo> findQwenFiles(Path qwenDir) {
        List<FileInfo> results = new ArrayList<>();
        if (!Files.isDirectory(qwenDir)) return results;

        try (Stream<Path> walk = Files.walk(qwenDir, 5)) {
            walk.filter(p -> p.toString().endsWith(".jsonl") && Files.isRegularFile(p))
                    .forEach(p -> {
                        try {
                            String relPath = qwenDir.relativize(p).toString();
                            String id = relPath.replace(File.separator, "_")
                                    .replaceAll("\\.jsonl$", "");
                            results.add(new FileInfo(id, p, Files.size(p)));
                        } catch (IOException e) {
                            // skip
                        }
                    });
        } catch (IOException e) {
            // Return whatever we found
        }

        results.sort(Comparator.comparing(fi -> fi.id));
        return results;
    }

    // ─── JSON extraction helpers ────────────────────────────────────────

    /**
     * Extract the role from a JSON message node.
     * Handles multiple formats: { "role": "user" }, { "type": "human" }, etc.
     */
    private String extractRole(JsonNode node) {
        // Standard: { "role": "user" | "assistant" }
        if (node.has("role")) {
            String role = node.get("role").asText("");
            if ("user".equalsIgnoreCase(role) || "human".equalsIgnoreCase(role)) {
                return "user";
            }
            if ("assistant".equalsIgnoreCase(role) || "ai".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
                return "assistant";
            }
        }

        // Claude Code format: { "type": "human" | "assistant" }
        // Qwen format: { "type": "user" | "assistant" } at the outer level
        if (node.has("type")) {
            String type = node.get("type").asText("");
            if ("human".equalsIgnoreCase(type) || "user".equalsIgnoreCase(type)) {
                return "user";
            }
            if ("assistant".equalsIgnoreCase(type)) {
                return "assistant";
            }
        }

        // Sender field variant
        if (node.has("sender")) {
            String sender = node.get("sender").asText("");
            if ("user".equalsIgnoreCase(sender) || "human".equalsIgnoreCase(sender)) {
                return "user";
            }
            if ("assistant".equalsIgnoreCase(sender) || "bot".equalsIgnoreCase(sender)) {
                return "assistant";
            }
        }

        return null;
    }

    /**
     * Extract text content from a JSON message node.
     * Handles: { "content": "text" }, { "content": [{ "text": "..." }] },
     * { "message": "text" }, { "text": "text" }
     */
    private String extractContent(JsonNode node) {
        // Direct "content" string
        if (node.has("content")) {
            JsonNode contentNode = node.get("content");
            if (contentNode.isTextual()) {
                return contentNode.asText();
            }
            // Content as array of blocks: [{ "type": "text", "text": "..." }]
            if (contentNode.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentNode) {
                    if (block.isTextual()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.asText());
                    } else if (block.has("text")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(block.get("text").asText(""));
                    }
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
        }

        // "message" field
        if (node.has("message")) {
            JsonNode msgNode = node.get("message");
            if (msgNode.isTextual()) {
                return msgNode.asText();
            }
        }

        // "text" field
        if (node.has("text")) {
            JsonNode textNode = node.get("text");
            if (textNode.isTextual()) {
                return textNode.asText();
            }
        }

        return null;
    }

    /**
     * Find the messages array in a JSON document.
     * Tries: root.messages, root.session.messages, root.conversation.messages,
     * root.items, root.data.messages
     */
    private JsonNode findMessagesArray(JsonNode root) {
        if (root == null) return null;

        // Direct messages array
        if (root.has("messages") && root.get("messages").isArray()) {
            return root.get("messages");
        }

        // Nested under "session"
        if (root.has("session") && root.get("session").has("messages")) {
            JsonNode msgs = root.get("session").get("messages");
            if (msgs.isArray()) return msgs;
        }

        // Nested under "conversation"
        if (root.has("conversation") && root.get("conversation").has("messages")) {
            JsonNode msgs = root.get("conversation").get("messages");
            if (msgs.isArray()) return msgs;
        }

        // "items" array
        if (root.has("items") && root.get("items").isArray()) {
            return root.get("items");
        }

        // Nested under "data"
        if (root.has("data") && root.get("data").has("messages")) {
            JsonNode msgs = root.get("data").get("messages");
            if (msgs.isArray()) return msgs;
        }

        // If root itself is an array, treat it as the messages
        if (root.isArray()) {
            return root;
        }

        return null;
    }

    // ─── Utility helpers ────────────────────────────────────────────────

    private FileInfo findFileById(List<FileInfo> files, String id) {
        // Exact match first
        for (FileInfo fi : files) {
            if (fi.id.equals(id)) return fi;
        }
        // Try with/without extension
        for (FileInfo fi : files) {
            if (fi.id.equalsIgnoreCase(id) ||
                    fi.path.getFileName().toString().equals(id) ||
                    fi.path.getFileName().toString().equals(id + ".jsonl") ||
                    fi.path.getFileName().toString().equals(id + ".json")) {
                return fi;
            }
        }
        // Partial match
        for (FileInfo fi : files) {
            if (fi.id.contains(id) || id.contains(fi.id)) {
                return fi;
            }
        }
        return null;
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private String getAgentName(String source) {
        switch (source) {
            case SOURCE_CLAUDE_CODE:
                return "claude";
            case SOURCE_OPENCODE:
                return "opencode";
            case SOURCE_CODEX:
                return "codex";
            case SOURCE_QWEN:
                return "qwen";
            default:
                return "unknown";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    // ─── OpenCode/Codex session listing helpers ─────────────────────────

    /**
     * List all sessions from OpenCode SQLite database.
     */
    private List<OpenCodeSession> listOpenCodeSessions(Path dbPath) throws SQLException {
        List<OpenCodeSession> sessions = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, title, message_count FROM session ORDER BY time_updated DESC")) {
            while (rs.next()) {
                String id = rs.getString("id");
                String title = rs.getString("title");
                int messageCount = getMessageCount(conn, id);
                sessions.add(new OpenCodeSession(id, title, messageCount));
            }
        }
        return sessions;
    }

    private int getMessageCount(Connection conn, String sessionId) throws SQLException {
        String query = "SELECT COUNT(*) FROM message WHERE session_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * List all sessions from Codex history.jsonl file.
     * Returns a map of session_id -> CodexSession with message count.
     */
    private Map<String, CodexSession> listCodexSessions(Path historyPath) throws IOException {
        Map<String, Integer> sessionMessageCounts = new LinkedHashMap<>();
        for (String line : Files.readAllLines(historyPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                String sessionId = node.path("session_id").asText("");
                if (!sessionId.isBlank()) {
                    sessionMessageCounts.merge(sessionId, 1, Integer::sum);
                }
            } catch (Exception e) {
                // Skip unparseable lines
            }
        }

        Map<String, CodexSession> sessions = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sessionMessageCounts.entrySet()) {
            sessions.put(entry.getKey(), new CodexSession(entry.getKey(), entry.getValue()));
        }
        return sessions;
    }

    // ─── Inner types ────────────────────────────────────────────────────

    private static class FileInfo {
        final String id;
        final Path path;
        final long size;

        FileInfo(String id, Path path, long size) {
            this.id = id;
            this.path = path;
            this.size = size;
        }
    }

    private static class Message {
        final String role;
        final String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static record OpenCodeSession(String id, String title, int messageCount) {}

    private static record CodexSession(String sessionId, int messageCount) {}
}
