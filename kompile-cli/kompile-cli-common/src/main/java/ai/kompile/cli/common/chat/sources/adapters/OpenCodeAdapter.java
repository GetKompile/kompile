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
import java.util.Optional;

public class OpenCodeAdapter implements ChatSourceAdapter {

    public static final String ID = "opencode";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenCode";
    }

    protected Path dbPath() {
        Path primary = ChatAdapterSupport.userHome().resolve(".local").resolve("share")
                .resolve("opencode").resolve("opencode.db");
        if (Files.exists(primary)) return primary;
        Path fallback = ChatAdapterSupport.userHome().resolve(".opencode").resolve("opencode.db");
        return fallback;
    }

    @Override
    public SourceInfo discover() {
        if (!ChatAdapterSupport.sqliteAvailable()) {
            return SourceInfo.unavailable(id(), displayName(), dbPath().toString(), "sqlite-jdbc missing");
        }
        Path db = dbPath();
        if (!Files.exists(db)) {
            return SourceInfo.unavailable(id(), displayName(), db.toString(), "database missing");
        }
        int count = 0;
        try (Connection conn = open(db);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM session")) {
            if (rs.next()) count = rs.getInt(1);
        } catch (SQLException ignore) {
        }
        return SourceInfo.available(id(), displayName(), db.toString(), count);
    }

    @Override
    public List<ChatSessionSummary> list() throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) return Collections.emptyList();
        Path db = dbPath();
        if (!Files.exists(db)) return Collections.emptyList();
        List<ChatSessionSummary> out = new ArrayList<>();
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT s.id, s.title, s.directory, s.time_updated, " +
                             "  (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) AS mc " +
                             "FROM session s ORDER BY s.time_updated DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("id");
                    String title = rs.getString("title");
                    String dir = rs.getString("directory");
                    long modified = rs.getLong("time_updated");
                    int count = rs.getInt("mc");
                    out.add(new ChatSessionSummary(sid, id(),
                            title == null ? "(opencode)" : title, id(),
                            count, modified, dir));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to list OpenCode sessions: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<ChatTurn> readTurns(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) return Collections.emptyList();
        Path db = dbPath();
        if (!Files.exists(db)) return Collections.emptyList();
        List<ChatTurn> out = new ArrayList<>();
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT m.id AS mid, m.data AS mdata, p.data AS pdata " +
                             "FROM message m LEFT JOIN part p ON m.id = p.message_id " +
                             "WHERE m.session_id = ? " +
                             "ORDER BY m.time_created ASC, p.time_created ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                String lastMessageId = null;
                String currentRole = null;
                StringBuilder buffer = new StringBuilder();
                while (rs.next()) {
                    String messageId = rs.getString("mid");
                    String mdata = rs.getString("mdata");
                    String partData = rs.getString("pdata");

                    if (!java.util.Objects.equals(messageId, lastMessageId)) {
                        if (currentRole != null && buffer.length() > 0) {
                            out.add(new ChatTurn(currentRole, buffer.toString()));
                        }
                        buffer.setLength(0);
                        currentRole = normalizeRole(extractRoleFromMessage(mdata));
                        lastMessageId = messageId;
                    }

                    String fromPart = extractPartText(partData);
                    if (fromPart != null) {
                        if (buffer.length() > 0) buffer.append('\n');
                        buffer.append(fromPart);
                        continue;
                    }
                    String fromMessage = extractMessageText(mdata);
                    if (fromMessage != null) {
                        if (buffer.length() > 0) buffer.append('\n');
                        buffer.append(fromMessage);
                    }
                }
                if (currentRole != null && buffer.length() > 0) {
                    out.add(new ChatTurn(currentRole, buffer.toString()));
                }
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read OpenCode session: " + e.getMessage(), e);
        }
        return out;
    }

    private static String extractRoleFromMessage(String messageData) {
        if (messageData == null || messageData.isBlank()) return null;
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(messageData);
            String role = node.path("role").asText(null);
            return role != null && !role.isBlank() ? role : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Optional<Path> resolveWorkingDirectory(String sessionId) throws IOException {
        if (!ChatAdapterSupport.sqliteAvailable()) return Optional.empty();
        Path db = dbPath();
        if (!Files.exists(db)) return Optional.empty();
        try (Connection conn = open(db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT directory FROM session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dir = rs.getString(1);
                    if (dir != null && !dir.isBlank()) return Optional.of(Path.of(dir));
                }
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return Optional.empty();
    }

    private static Connection open(Path db) throws SQLException {
        String url = "jdbc:sqlite:" + db.toAbsolutePath();
        return DriverManager.getConnection(url);
    }

    private static String normalizeRole(String raw) {
        if (raw == null) return "assistant";
        String n = raw.toLowerCase();
        if (n.equals("user") || n.equals("human")) return "user";
        if (n.equals("assistant") || n.equals("ai") || n.equals("model")) return "assistant";
        return n;
    }

    private static String extractPartText(String partData) {
        if (partData == null || partData.isBlank()) return null;
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(partData);
            String type = node.path("type").asText(null);
            if (type != null && !type.equalsIgnoreCase("text")) return null;
            return ChatAdapterSupport.extractContent(node);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractMessageText(String messageData) {
        if (messageData == null || messageData.isBlank()) return null;
        try {
            JsonNode node = ChatAdapterSupport.MAPPER.readTree(messageData);
            return ChatAdapterSupport.extractContent(node);
        } catch (Exception e) {
            return null;
        }
    }
}
