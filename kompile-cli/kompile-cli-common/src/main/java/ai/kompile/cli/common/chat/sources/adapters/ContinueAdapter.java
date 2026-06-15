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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Continue.dev stores sessions in two formats depending on version:
 * <ul>
 *     <li>Newer: {@code ~/.continue/sessions/<id>.json} (one file per session,
 *         optionally with a {@code sessions.json} index)</li>
 *     <li>Older: SQLite at {@code ~/.continue/session.db} with {@code sessions}
 *         and {@code session_history} tables</li>
 * </ul>
 * Both contain OpenAI-shape {@code {role, content}} message arrays; we accept
 * either and prefer newer JSON files when both are present.
 */
public class ContinueAdapter implements ChatSourceAdapter {

    public static final String ID = "continue";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Continue";
    }

    protected Path continueHome() {
        return ChatAdapterSupport.userHome().resolve(".continue");
    }

    protected Path sessionsDir() {
        return continueHome().resolve("sessions");
    }

    protected Path sqliteDb() {
        Path candidate = continueHome().resolve("session.db");
        if (Files.exists(candidate)) return candidate;
        return continueHome().resolve("index").resolve("session.db");
    }

    @Override
    public SourceInfo discover() {
        Path home = continueHome();
        if (!Files.isDirectory(home)) {
            return SourceInfo.unavailable(id(), displayName(), home.toString(), "directory missing");
        }
        int count = 0;
        boolean seen = false;
        Path dir = sessionsDir();
        if (Files.isDirectory(dir)) {
            seen = true;
            count += countJsonSessions(dir);
        }
        Path db = sqliteDb();
        if (Files.exists(db)) {
            seen = true;
            if (ChatAdapterSupport.sqliteAvailable()) {
                count += countSqliteSessions(db);
            }
        }
        if (!seen) {
            return SourceInfo.unavailable(id(), displayName(), home.toString(), "no sessions found");
        }
        return SourceInfo.available(id(), displayName(), home.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        List<ChatSessionSummary> out = new ArrayList<>();
        Path dir = sessionsDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> addJsonSummary(p, out));
            }
        }
        Path db = sqliteDb();
        if (Files.exists(db) && ChatAdapterSupport.sqliteAvailable()) {
            addSqliteSummaries(db, out);
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        Path json = sessionsDir().resolve(sessionId + ".json");
        if (Files.exists(json)) {
            return readJsonTurns(json);
        }
        Path db = sqliteDb();
        if (Files.exists(db) && ChatAdapterSupport.sqliteAvailable()) {
            return readSqliteTurns(db, sessionId);
        }
        return Collections.emptyList();
    }

    private static int countJsonSessions(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .count();
        } catch (IOException ignore) {
            return 0;
        }
    }

    private static int countSqliteSessions(Path db) {
        try (Connection conn = open(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignore) {
        }
        return 0;
    }

    private void addJsonSummary(Path file, List<ChatSessionSummary> out) {
        String fileName = file.getFileName().toString();
        String id = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
        String title = null;
        int turnCount = 0;
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(file.toFile());
            JsonNode t = root.path("title");
            if (t.isTextual() && !t.asText().isBlank()) title = t.asText();
            JsonNode history = historyArray(root);
            if (history.isArray()) turnCount = history.size();
        } catch (Exception ignore) {
        }
        out.add(new ChatSessionSummary(id, id(),
                title == null ? "(continue)" : title,
                id(), turnCount, ChatAdapterSupport.lastModified(file)));
    }

    private void addSqliteSummaries(Path db, List<ChatSessionSummary> out) {
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT session_id, title, date_created FROM sessions ORDER BY date_created DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString(1);
                    String title = rs.getString(2);
                    long modified = 0L;
                    try {
                        String created = rs.getString(3);
                        if (created != null) modified = parseLong(created);
                    } catch (Exception ignore) {
                    }
                    int turnCount = countSqliteTurns(conn, sid);
                    out.add(new ChatSessionSummary(sid, id(),
                            title == null ? "(continue)" : title,
                            id(), turnCount, modified));
                }
            }
        } catch (SQLException ignore) {
        }
    }

    private static int countSqliteTurns(Connection conn, String sessionId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM session_history WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignore) {
        }
        return 0;
    }

    private static List<ChatTurn> readJsonTurns(Path file) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        JsonNode root = ChatAdapterSupport.MAPPER.readTree(file.toFile());
        JsonNode history = historyArray(root);
        if (history.isArray()) {
            for (JsonNode msg : history) {
                String role = ChatAdapterSupport.extractRole(msg);
                String content = ChatAdapterSupport.extractContent(msg);
                if (role != null && content != null && !content.isBlank()) {
                    out.add(new ChatTurn(role, content));
                }
            }
        }
        return out;
    }

    private static List<ChatTurn> readSqliteTurns(Path db, String sessionId) throws IOException {
        List<ChatTurn> out = new ArrayList<>();
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT role, content FROM session_history WHERE session_id = ? ORDER BY rowid ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String role = rs.getString(1);
                    String content = rs.getString(2);
                    String parsed = unwrapMaybeJson(content);
                    if (role != null && parsed != null && !parsed.isBlank()) {
                        out.add(new ChatTurn(role.toLowerCase(), parsed));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read Continue session: " + e.getMessage(), e);
        }
        return out;
    }

    private static String unwrapMaybeJson(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = ChatAdapterSupport.MAPPER.readTree(trimmed);
                String extracted = ChatAdapterSupport.extractContent(node);
                if (extracted != null) return extracted;
            } catch (Exception ignore) {
            }
        }
        return raw;
    }

    private static JsonNode historyArray(JsonNode root) {
        if (root.isArray()) return root;
        for (String field : new String[]{"history", "messages", "conversation"}) {
            JsonNode arr = root.path(field);
            if (arr.isArray()) return arr;
        }
        return root.path("history");
    }

    private static Connection open(Path db) throws SQLException {
        String url = "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro";
        return DriverManager.getConnection(url);
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignore) {
            return 0L;
        }
    }
}
