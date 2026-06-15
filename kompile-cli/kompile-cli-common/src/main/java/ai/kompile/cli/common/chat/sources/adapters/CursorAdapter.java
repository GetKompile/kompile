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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Cursor IDE stores conversations in SQLite databases managed by VSCode storage:
 *   {configRoot}/Cursor/User/workspaceStorage/<hash>/state.vscdb   (per-workspace)
 *   {configRoot}/Cursor/User/globalStorage/state.vscdb             (global)
 *
 * The {@code cursorDiskKV} table holds key/value rows where {@code value} is a JSON blob
 * for keys such as {@code composerData:<id>} (chat threads) and {@code history:<id>}.
 */
public class CursorAdapter implements ChatSourceAdapter {

    public static final String ID = "cursor";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Cursor";
    }

    protected Path cursorRoot() {
        return ChatAdapterSupport.userConfigDir().resolve("Cursor").resolve("User");
    }

    protected List<Path> stateDbFiles() {
        Path root = cursorRoot();
        List<Path> out = new ArrayList<>();
        Path global = root.resolve("globalStorage").resolve("state.vscdb");
        if (Files.exists(global)) out.add(global);
        Path workspaceStorage = root.resolve("workspaceStorage");
        if (Files.isDirectory(workspaceStorage)) {
            try (Stream<Path> stream = Files.list(workspaceStorage)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.resolve("state.vscdb"))
                        .filter(Files::exists)
                        .forEach(out::add);
            } catch (IOException ignore) {
            }
        }
        return out;
    }

    @Override
    public SourceInfo discover() {
        Path root = cursorRoot();
        if (!Files.isDirectory(root)) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(), "directory missing");
        }
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(), "sqlite-jdbc missing");
        }
        List<Path> dbs = stateDbFiles();
        if (dbs.isEmpty()) {
            return SourceInfo.unavailable(id(), displayName(), root.toString(), "no state.vscdb found");
        }
        int count = 0;
        for (Path db : dbs) {
            count += countSessionsInDb(db);
        }
        return SourceInfo.available(id(), displayName(), root.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) return Collections.emptyList();
        List<Path> dbs = stateDbFiles();
        if (dbs.isEmpty()) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        long modified = 0L;
        for (Path db : dbs) {
            long dbModified = ChatAdapterSupport.lastModified(db);
            if (dbModified > modified) modified = dbModified;
            collectSummariesFromDb(db, dbModified, out);
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) return Collections.emptyList();
        List<Path> dbs = stateDbFiles();
        for (Path db : dbs) {
            List<ChatTurn> turns = readSessionFromDb(db, sessionId);
            if (!turns.isEmpty()) return turns;
        }
        return Collections.emptyList();
    }

    private int countSessionsInDb(Path db) {
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM cursorDiskKV WHERE key LIKE 'composerData:%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignore) {
        }
        return 0;
    }

    private void collectSummariesFromDb(Path db, long modified, List<ChatSessionSummary> out) {
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT key, value FROM cursorDiskKV WHERE key LIKE 'composerData:%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    String sid = sessionIdFromKey(key);
                    if (sid == null) continue;
                    ComposerSummary summary = parseComposerSummary(value);
                    out.add(new ChatSessionSummary(
                            sid, id(),
                            summary.title == null ? "(cursor)" : summary.title,
                            id(), summary.turnCount, modified));
                }
            }
        } catch (SQLException ignore) {
        }
    }

    private List<ChatTurn> readSessionFromDb(Path db, String sessionId) {
        List<ChatTurn> out = new ArrayList<>();
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM cursorDiskKV WHERE key = ?")) {
            ps.setString(1, "composerData:" + sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    collectTurnsFromComposer(json, out);
                }
            }
        } catch (SQLException ignore) {
        }
        if (!out.isEmpty()) return out;
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM cursorDiskKV WHERE key = ?")) {
            ps.setString(1, "history:" + sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    collectTurnsFromHistory(json, out);
                }
            }
        } catch (SQLException ignore) {
        }
        return out;
    }

    private static String sessionIdFromKey(String key) {
        if (key == null) return null;
        int i = key.indexOf(':');
        if (i < 0 || i == key.length() - 1) return null;
        return key.substring(i + 1);
    }

    private static ComposerSummary parseComposerSummary(String json) {
        ComposerSummary result = new ComposerSummary();
        if (json == null || json.isBlank()) return result;
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(json);
            JsonNode title = root.path("name");
            if (!title.isTextual()) title = root.path("title");
            if (title.isTextual() && !title.asText().isBlank()) result.title = title.asText();
            JsonNode conversation = root.path("conversation");
            if (conversation.isArray()) {
                result.turnCount = conversation.size();
                return result;
            }
            JsonNode tabs = root.path("tabs");
            if (tabs.isArray()) {
                int count = 0;
                for (JsonNode tab : tabs) {
                    JsonNode bubbles = tab.path("bubbles");
                    if (bubbles.isArray()) count += bubbles.size();
                }
                result.turnCount = count;
            }
        } catch (Exception ignore) {
        }
        return result;
    }

    private static void collectTurnsFromComposer(String json, List<ChatTurn> out) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(json);
            JsonNode conversation = root.path("conversation");
            if (conversation.isArray()) {
                for (JsonNode msg : conversation) {
                    addTurn(msg, out);
                }
                return;
            }
            JsonNode tabs = root.path("tabs");
            if (tabs.isArray()) {
                for (JsonNode tab : tabs) {
                    JsonNode bubbles = tab.path("bubbles");
                    if (bubbles.isArray()) {
                        for (JsonNode b : bubbles) {
                            addTurn(b, out);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static void collectTurnsFromHistory(String json, List<ChatTurn> out) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode root = ChatAdapterSupport.MAPPER.readTree(json);
            if (root.isArray()) {
                for (JsonNode msg : root) {
                    addTurn(msg, out);
                }
                return;
            }
            JsonNode messages = root.path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    addTurn(msg, out);
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static void addTurn(JsonNode node, List<ChatTurn> out) {
        String role = ChatAdapterSupport.extractRole(node);
        if (role == null) {
            JsonNode type = node.path("type");
            if (type.isTextual()) {
                String t = type.asText().toLowerCase();
                if (t.equals("1") || t.equals("user")) role = "user";
                else if (t.equals("2") || t.equals("assistant") || t.equals("ai")) role = "assistant";
            }
        }
        if (role == null) return;
        String content = ChatAdapterSupport.extractContent(node);
        if (content == null || content.isBlank()) return;
        out.add(new ChatTurn(role, content));
    }

    private static Connection open(Path db) throws SQLException {
        String url = "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro";
        return DriverManager.getConnection(url);
    }

    private static final class ComposerSummary {
        String title;
        int turnCount;
    }
}
