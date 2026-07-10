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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads OpenCode conversations from its SQLite store.
 *
 * <p>OpenCode currently has two transcript layouts in the wild: the legacy
 * {@code message}/{@code part} tables and the newer {@code session_message}
 * table. This adapter detects the schema per database and supports both so an
 * upgrade does not make older (or newly-created) sessions disappear.</p>
 */
public class OpenCodeAdapter implements ChatSourceAdapter {

    public static final String ID = "opencode";

    private static final String LEGACY_MESSAGE_TABLE = "message";
    private static final String LEGACY_PART_TABLE = "part";
    private static final String SESSION_MESSAGE_TABLE = "session_message";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    protected Path dbPath() {
        Path home = ChatAdapterSupport.userHome();
        List<Path> candidates = new ArrayList<>();

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            candidates.add(Path.of(xdgDataHome).resolve("opencode").resolve("opencode.db"));
        }
        candidates.add(home.resolve(".local").resolve("share").resolve("opencode").resolve("opencode.db"));
        candidates.add(home.resolve("Library").resolve("Application Support")
                .resolve("opencode").resolve("opencode.db"));

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData).resolve("opencode").resolve("opencode.db"));
        }
        candidates.add(home.resolve(".opencode").resolve("opencode.db"));

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    @Override
    public SourceInfo discover() {
        Path db = dbPath();
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return SourceInfo.unavailable(id(), displayName(), db.toString(), "sqlite-jdbc missing");
        }
        if (!Files.isRegularFile(db)) {
            return SourceInfo.unavailable(id(), displayName(), db.toString(), "database missing");
        }

        try (Connection conn = open(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM session")) {
            int count = rs.next() ? rs.getInt(1) : 0;
            return SourceInfo.available(id(), displayName(), db.toString(), count);
        } catch (SQLException e) {
            return SourceInfo.unavailable(id(), displayName(), db.toString(),
                    "database unreadable: " + e.getMessage());
        }
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        return list(-1);
    }

    @Override
    public List<ChatSessionSummary> list(int limit) throws IOException {
        if (limit == 0 || !ChatAdapterSupport.sqliteAvailable()) {
            return Collections.emptyList();
        }
        Path db = dbPath();
        if (!Files.isRegularFile(db)) {
            return Collections.emptyList();
        }

        List<ChatSessionSummary> out = new ArrayList<>();
        try (Connection conn = open(db)) {
            String sql = "SELECT s.id, s.title, s.directory, s.time_updated, "
                    + messageCountExpression(conn) + " AS mc "
                    + "FROM session s ORDER BY s.time_updated DESC, s.id DESC";
            if (limit >= 0) {
                sql += " LIMIT ?";
            }

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (limit >= 0) {
                    ps.setInt(1, limit);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sid = rs.getString("id");
                        String title = rs.getString("title");
                        String dir = rs.getString("directory");
                        long modified = rs.getLong("time_updated");
                        int count = rs.getInt("mc");
                        out.add(new ChatSessionSummary(
                                sid,
                                id(),
                                title == null || title.isBlank() ? "(opencode)" : title,
                                id(),
                                count,
                                modified,
                                dir));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list OpenCode sessions: " + e.getMessage(), e);
        }
        return out;
    }

    private static String messageCountExpression(Connection conn) throws SQLException {
        boolean hasSessionMessages = tableExists(conn, SESSION_MESSAGE_TABLE);
        boolean hasLegacyMessages = tableExists(conn, LEGACY_MESSAGE_TABLE);

        if (hasSessionMessages && hasLegacyMessages) {
            return "CASE WHEN EXISTS (SELECT 1 FROM session_message sm0 WHERE sm0.session_id = s.id) "
                    + "THEN (SELECT COUNT(*) FROM session_message sm WHERE sm.session_id = s.id) "
                    + "ELSE (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) END";
        }
        if (hasSessionMessages) {
            return "(SELECT COUNT(*) FROM session_message sm WHERE sm.session_id = s.id)";
        }
        if (hasLegacyMessages) {
            return "(SELECT COUNT(*) FROM message m WHERE m.session_id = s.id)";
        }
        return "0";
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return Collections.emptyList();
        }
        Path db = dbPath();
        if (!Files.isRegularFile(db)) {
            return Collections.emptyList();
        }

        try (Connection conn = open(db)) {
            if (tableExists(conn, SESSION_MESSAGE_TABLE)
                    && hasSessionRows(conn, SESSION_MESSAGE_TABLE, sessionId)) {
                return readSessionMessages(conn, sessionId);
            }
            if (tableExists(conn, LEGACY_MESSAGE_TABLE)) {
                return readLegacyMessages(conn, sessionId);
            }
            return Collections.emptyList();
        } catch (SQLException e) {
            throw new IOException("Failed to read OpenCode session: " + e.getMessage(), e);
        }
    }

    private static List<ChatTurn> readSessionMessages(Connection conn, String sessionId)
            throws SQLException {
        List<ChatTurn> out = new ArrayList<>();
        String sql = "SELECT type, data, time_created FROM session_message "
                + "WHERE session_id = ? ORDER BY seq ASC, time_created ASC, id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    String data = rs.getString("data");
                    long created = nullableLong(rs, "time_created");
                    ChatTurn turn = parseSessionMessage(type, data, created);
                    if (turn != null) {
                        out.add(turn);
                    }
                }
            }
        }
        return out;
    }

    private static ChatTurn parseSessionMessage(String storedType, String data, long created) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(data);
            String type = storedType;
            if (type == null || type.isBlank()) {
                type = node.path("type").asText("");
            }
            String normalized = type.toLowerCase(Locale.ROOT);
            String role;
            String content;

            switch (normalized) {
                case "user":
                    role = "user";
                    content = userMessageText(node);
                    break;
                case "assistant":
                    role = "assistant";
                    content = assistantMessageText(node);
                    break;
                case "system":
                    role = "system";
                    content = node.path("text").asText(null);
                    break;
                case "synthetic":
                    role = "assistant";
                    content = node.path("text").asText(null);
                    break;
                case "shell":
                    role = "assistant";
                    content = shellMessageText(node);
                    break;
                case "compaction":
                    role = "system";
                    content = compactionMessageText(node);
                    break;
                case "agent-switched":
                    role = "system";
                    content = "[agent-switched] " + node.path("agent").asText("");
                    break;
                case "model-switched":
                    role = "system";
                    content = "[model-switched] " + jsonValue(node.path("model"));
                    break;
                default:
                    role = normalizeRole(node.path("role").asText(normalized));
                    content = ChatAdapterSupport.extractContent(node);
                    break;
            }

            if (content == null || content.isBlank()) {
                return null;
            }
            return new ChatTurn(role, content, extractTimestamp(node, created));
        } catch (Exception e) {
            return null;
        }
    }

    private static String userMessageText(JsonNode node) {
        String text = node.path("text").asText("");
        StringBuilder out = new StringBuilder(text);

        JsonNode files = node.path("files");
        if (files.isArray()) {
            for (JsonNode file : files) {
                String name = firstText(file, "filename", "name", "path", "url");
                appendLine(out, name == null ? "[file]" : "[file:" + name + "]");
            }
        }

        JsonNode agents = node.path("agents");
        if (agents.isArray()) {
            for (JsonNode agent : agents) {
                String name = agent.isTextual() ? agent.asText() : firstText(agent, "name", "agent");
                if (name != null) {
                    appendLine(out, "[agent:" + name + "]");
                }
            }
        }
        return out.toString();
    }

    private static String assistantMessageText(JsonNode node) {
        JsonNode content = node.path("content");
        if (!content.isArray()) {
            return ChatAdapterSupport.extractContent(node);
        }

        StringBuilder out = new StringBuilder();
        for (JsonNode part : content) {
            appendLine(out, extractPartText(part));
        }
        return out.toString();
    }

    private static String shellMessageText(JsonNode node) {
        String command = node.path("command").asText("");
        String output = node.path("output").asText("");
        StringBuilder out = new StringBuilder("[shell]");
        if (!command.isBlank()) {
            out.append(' ').append(command);
        }
        appendLine(out, output);
        return out.toString();
    }

    private static String compactionMessageText(JsonNode node) {
        StringBuilder out = new StringBuilder("[compaction]");
        appendLine(out, node.path("summary").asText(""));
        appendLine(out, node.path("recent").asText(""));
        return out.toString();
    }

    private static List<ChatTurn> readLegacyMessages(Connection conn, String sessionId)
            throws SQLException {
        List<ChatTurn> out = new ArrayList<>();
        boolean hasParts = tableExists(conn, LEGACY_PART_TABLE);
        String sql = hasParts
                ? "SELECT m.id AS mid, m.data AS mdata, m.time_created AS mcreated, "
                    + "p.data AS pdata FROM message m "
                    + "LEFT JOIN part p ON m.id = p.message_id "
                    + "WHERE m.session_id = ? "
                    + "ORDER BY m.time_created ASC, m.id ASC, p.time_created ASC, p.id ASC"
                : "SELECT m.id AS mid, m.data AS mdata, m.time_created AS mcreated, "
                    + "NULL AS pdata FROM message m WHERE m.session_id = ? "
                    + "ORDER BY m.time_created ASC, m.id ASC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                String currentMessageId = null;
                String currentRole = null;
                String currentMessageData = null;
                long currentCreated = 0L;
                StringBuilder parts = new StringBuilder();

                while (rs.next()) {
                    String messageId = rs.getString("mid");
                    if (!Objects.equals(messageId, currentMessageId)) {
                        flushLegacyTurn(out, currentRole, currentMessageData, currentCreated, parts);
                        currentMessageId = messageId;
                        currentMessageData = rs.getString("mdata");
                        currentRole = normalizeRole(extractRoleFromMessage(currentMessageData));
                        currentCreated = nullableLong(rs, "mcreated");
                        parts.setLength(0);
                    }
                    appendLine(parts, extractPartText(rs.getString("pdata")));
                }
                flushLegacyTurn(out, currentRole, currentMessageData, currentCreated, parts);
            }
        }
        return out;
    }

    private static void flushLegacyTurn(List<ChatTurn> out, String role, String messageData,
                                        long created, StringBuilder parts) {
        if (role == null) {
            return;
        }
        String content = parts.length() > 0 ? parts.toString() : extractMessageText(messageData);
        if (content != null && !content.isBlank()) {
            out.add(new ChatTurn(role, content, extractTimestamp(messageData, created)));
        }
    }

    private static String extractRoleFromMessage(String messageData) {
        if (messageData == null || messageData.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(messageData);
            String role = node.path("role").asText(null);
            if (role == null || role.isBlank()) {
                role = node.path("type").asText(null);
            }
            return role != null && !role.isBlank() ? role : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractPartText(String partData) {
        if (partData == null || partData.isBlank()) {
            return null;
        }
        try {
            return extractPartText(ChatAdapterSupport.MAPPER.readTree(partData));
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractPartText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String type = node.path("type").asText("").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "text" -> node.path("text").asText(null);
            case "reasoning" -> prefix("[thinking] ", node.path("text").asText(null));
            case "tool" -> toolPartText(node);
            case "file" -> filePartText(node);
            case "subtask" -> subtaskPartText(node);
            case "agent" -> prefix("[agent:", node.path("name").asText(null), "]");
            case "compaction" -> "[compaction]";
            case "retry" -> prefix("[retry] ", jsonValue(node.path("error")));
            default -> ChatAdapterSupport.extractContent(node);
        };
    }

    private static String toolPartText(JsonNode node) {
        String name = firstText(node, "tool", "name");
        if (name == null) {
            name = "unknown";
        }

        JsonNode state = node.path("state");
        JsonNode input = state.path("input");
        if (input.isMissingNode() || input.isNull()) {
            input = node.path("input");
        }

        StringBuilder out = new StringBuilder("[tool:").append(name).append(']');
        String inputText = jsonValue(input);
        if (inputText != null && !inputText.isBlank() && !"{}".equals(inputText)) {
            out.append(' ').append(inputText);
        }

        String status = state.path("status").asText("");
        String result = firstNonBlank(
                jsonValue(state.path("output")),
                contentArrayText(state.path("content")),
                jsonValue(state.path("result")));
        String error = jsonValue(state.path("error"));

        if (!error.isBlank() || "error".equalsIgnoreCase(status)) {
            appendLine(out, "[tool-error] " + (error.isBlank() ? status : error));
        } else if (!result.isBlank()) {
            appendLine(out, "[tool-result] " + result);
        }
        return out.toString();
    }

    private static String filePartText(JsonNode node) {
        String name = firstText(node, "filename", "path", "url");
        return name == null ? "[file]" : "[file:" + name + "]";
    }

    private static String subtaskPartText(JsonNode node) {
        String agent = node.path("agent").asText("");
        String prompt = node.path("prompt").asText("");
        StringBuilder out = new StringBuilder("[subtask");
        if (!agent.isBlank()) {
            out.append(':').append(agent);
        }
        out.append(']');
        if (!prompt.isBlank()) {
            out.append(' ').append(prompt);
        }
        return out.toString();
    }

    private static String contentArrayText(JsonNode content) {
        if (!content.isArray()) {
            return jsonValue(content);
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode item : content) {
            String text = ChatAdapterSupport.extractContent(item);
            if (text == null || text.isBlank()) {
                text = jsonValue(item);
            }
            appendLine(out, text);
        }
        return out.toString();
    }

    private static String extractMessageText(String messageData) {
        if (messageData == null || messageData.isBlank()) {
            return null;
        }
        try {
            return ChatAdapterSupport.extractContent(ChatAdapterSupport.MAPPER.readTree(messageData));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return Optional.empty();
        }
        Path db = dbPath();
        if (!Files.isRegularFile(db)) {
            return Optional.empty();
        }
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT directory FROM session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dir = rs.getString(1);
                    if (dir != null && !dir.isBlank()) {
                        return Optional.of(Path.of(dir));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to resolve OpenCode working directory: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public String resolveTitle(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return sessionId;
        }
        Path db = dbPath();
        if (!Files.isRegularFile(db)) {
            return sessionId;
        }
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT title FROM session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String title = rs.getString(1);
                    if (title != null && !title.isBlank()) {
                        return title;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to resolve OpenCode title: " + e.getMessage(), e);
        }
        return sessionId;
    }

    private static Connection open(Path db) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA query_only = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasSessionRows(Connection conn, String table, String sessionId)
            throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE session_id = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private static Instant extractTimestamp(String json, long fallback) {
        if (json != null && !json.isBlank()) {
            try {
                return extractTimestamp(ChatAdapterSupport.MAPPER.readTree(json), fallback);
            } catch (Exception ignore) {
            }
        }
        return toInstant(fallback);
    }

    private static Instant extractTimestamp(JsonNode node, long fallback) {
        JsonNode created = node.path("time").path("created");
        if (created.isMissingNode() || created.isNull()) {
            created = node.path("timestamp");
        }
        Instant parsed = parseInstant(created);
        return parsed != null ? parsed : toInstant(fallback);
    }

    private static Instant parseInstant(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return toInstant(value.asLong());
        }
        if (value.isTextual()) {
            String raw = value.asText();
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignore) {
                try {
                    return toInstant(Long.parseLong(raw));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Instant toInstant(long value) {
        if (value <= 0) {
            return null;
        }
        return value < 100_000_000_000L
                ? Instant.ofEpochSecond(value)
                : Instant.ofEpochMilli(value);
    }

    private static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return "assistant";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.equals("user") || normalized.equals("human")) {
            return "user";
        }
        if (normalized.equals("assistant") || normalized.equals("ai")
                || normalized.equals("model") || normalized.equals("synthetic")) {
            return "assistant";
        }
        return normalized;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String jsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }

    private static String prefix(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value;
    }

    private static String prefix(String prefix, String value, String suffix) {
        return value == null || value.isBlank() ? null : prefix + value + suffix;
    }

    private static void appendLine(StringBuilder out, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(value);
    }
}
