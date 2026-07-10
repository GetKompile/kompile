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
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.common.chat.sources.adapters;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void readsLegacyMultipartMessagesWithoutDuplicatingMessageFallback() throws Exception {
        Path db = tempDir.resolve("legacy-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE message (
                            id TEXT PRIMARY KEY,
                            session_id TEXT NOT NULL,
                            time_created INTEGER,
                            data TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE part (
                            id TEXT PRIMARY KEY,
                            message_id TEXT NOT NULL,
                            session_id TEXT NOT NULL,
                            time_created INTEGER,
                            data TEXT NOT NULL
                        )
                        """);
            }

            insertSession(connection, "legacy-session", "Legacy import", "/work/legacy", 1_700_000_002_000L);
            insertMessage(connection, "msg-user", "legacy-session", 1_700_000_001_000L, """
                    {"role":"user","content":"Inspect the importer","time":{"created":1700000001000}}
                    """);
            insertMessage(connection, "msg-assistant", "legacy-session", 1_700_000_002_000L, """
                    {"role":"assistant","content":"fallback must not repeat"}
                    """);
            insertPart(connection, "part-text", "msg-assistant", "legacy-session", 1_700_000_002_001L, """
                    {"type":"text","text":"I found the adapter."}
                    """);
            insertPart(connection, "part-reasoning", "msg-assistant", "legacy-session", 1_700_000_002_002L, """
                    {"type":"reasoning","text":"Check the SQLite layout"}
                    """);
            insertPart(connection, "part-tool", "msg-assistant", "legacy-session", 1_700_000_002_003L, """
                    {"type":"tool","tool":"read","state":{"status":"completed","input":{"file_path":"a.txt"},"output":"file contents"}}
                    """);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        SourceInfo source = adapter.discover();
        assertTrue(source.available());
        assertEquals(1, source.sessionCount());

        List<ChatSessionSummary> summaries = adapter.list(1);
        assertEquals(1, summaries.size());
        assertEquals(2, summaries.get(0).messageCount());
        assertEquals("/work/legacy", summaries.get(0).workingDirectory());
        assertEquals("Legacy import", adapter.resolveTitle("legacy-session"));
        assertEquals(Path.of("/work/legacy"),
                adapter.resolveWorkingDirectory("legacy-session").orElseThrow());

        List<ChatTurn> turns = adapter.readTurns("legacy-session");
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Inspect the importer", turns.get(0).content());
        assertEquals(Instant.ofEpochMilli(1_700_000_001_000L), turns.get(0).timestamp());

        String assistant = turns.get(1).content();
        assertTrue(assistant.contains("I found the adapter."));
        assertTrue(assistant.contains("[thinking] Check the SQLite layout"));
        assertTrue(assistant.contains("[tool:read]"));
        assertTrue(assistant.contains("\"file_path\":\"a.txt\""));
        assertTrue(assistant.contains("[tool-result] file contents"));
        assertFalse(assistant.contains("fallback must not repeat"));
        assertEquals(1, occurrences(assistant, "[tool:read]"));
    }

    @Test
    void readsCurrentSessionMessageSchemaIncludingToolState() throws Exception {
        Path db = tempDir.resolve("current-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE session_message (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        time_created INTEGER,
                        data TEXT NOT NULL
                    )
                    """);

            insertSession(connection, "older-session", "Older", "/work/older", 1_700_000_001_000L);
            insertSession(connection, "current-session", "Current import", "/work/current", 1_700_000_005_000L);

            insertSessionMessage(connection, "sm-user", "current-session", "user", 1, 1_700_000_003_000L, """
                    {"text":"Add OpenCode imports","files":[],"agents":[],"time":{"created":1700000003000}}
                    """);
            insertSessionMessage(connection, "sm-assistant", "current-session", "assistant", 2, 1_700_000_004_000L, """
                    {
                      "agent":"build",
                      "content":[
                        {"type":"text","id":"text-1","text":"Importer updated."},
                        {"type":"reasoning","id":"reason-1","text":"Validate both schemas"},
                        {
                          "type":"tool",
                          "id":"tool-1",
                          "name":"read",
                          "state":{
                            "status":"completed",
                            "input":{"file_path":"OpenCodeAdapter.java"},
                            "content":[{"type":"text","text":"source contents"}],
                            "structured":{}
                          }
                        }
                      ],
                      "time":{"created":1700000004000}
                    }
                    """);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        List<ChatSessionSummary> summaries = adapter.list(1);
        assertEquals(1, summaries.size());
        assertEquals("current-session", summaries.get(0).sessionId());
        assertEquals(2, summaries.get(0).messageCount());
        assertTrue(adapter.list(0).isEmpty());

        List<ChatTurn> turns = adapter.readTurns("current-session");
        assertEquals(2, turns.size());
        assertEquals("Add OpenCode imports", turns.get(0).content());
        assertEquals(Instant.ofEpochMilli(1_700_000_003_000L), turns.get(0).timestamp());

        String assistant = turns.get(1).content();
        assertTrue(assistant.contains("Importer updated."));
        assertTrue(assistant.contains("[thinking] Validate both schemas"));
        assertTrue(assistant.contains("[tool:read]"));
        assertTrue(assistant.contains("OpenCodeAdapter.java"));
        assertTrue(assistant.contains("[tool-result] source contents"));
        assertEquals(Instant.ofEpochMilli(1_700_000_004_000L), turns.get(1).timestamp());
    }

    @Test
    void prefersSessionMessageRowsWhenLegacyTablesAlsoExist() throws Exception {
        Path db = tempDir.resolve("mixed-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE message (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        time_created INTEGER,
                        data TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE part (
                        id TEXT PRIMARY KEY,
                        message_id TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        time_created INTEGER,
                        data TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE session_message (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        time_created INTEGER,
                        data TEXT NOT NULL
                    )
                    """);

            insertSession(connection, "mixed-session", "Mixed", "/work/mixed", 5_000L);
            insertMessage(connection, "legacy-copy", "mixed-session", 1_000L, """
                    {"role":"user","content":"legacy duplicate"}
                    """);
            insertSessionMessage(connection, "current-copy", "mixed-session", "user", 1, 2_000L, """
                    {"text":"current message","files":[],"agents":[]}
                    """);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        assertEquals(1, adapter.list().get(0).messageCount());

        List<ChatTurn> turns = adapter.readTurns("mixed-session");
        assertEquals(1, turns.size());
        assertEquals("current message", turns.get(0).content());
    }

    private static void createSessionTable(Path db) throws Exception {
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE session (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        directory TEXT,
                        time_updated INTEGER
                    )
                    """);
        }
    }

    private static Connection connect(Path db) throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
    }

    private static void insertSession(Connection connection, String id, String title,
                                      String directory, long updated) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO session(id, title, directory, time_updated) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setString(3, directory);
            statement.setLong(4, updated);
            statement.executeUpdate();
        }
    }

    private static void insertMessage(Connection connection, String id, String sessionId,
                                      long created, String data) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO message(id, session_id, time_created, data) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setLong(3, created);
            statement.setString(4, data);
            statement.executeUpdate();
        }
    }

    private static void insertPart(Connection connection, String id, String messageId,
                                   String sessionId, long created, String data) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO part(id, message_id, session_id, time_created, data) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, messageId);
            statement.setString(3, sessionId);
            statement.setLong(4, created);
            statement.setString(5, data);
            statement.executeUpdate();
        }
    }

    private static void insertSessionMessage(Connection connection, String id, String sessionId,
                                             String type, int sequence, long created,
                                             String data) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO session_message(id, session_id, type, seq, time_created, data) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, type);
            statement.setInt(4, sequence);
            statement.setLong(5, created);
            statement.setString(6, data);
            statement.executeUpdate();
        }
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static final class TestOpenCodeAdapter extends OpenCodeAdapter {
        private final Path db;

        private TestOpenCodeAdapter(Path db) {
            this.db = db;
        }

        @Override
        protected Path dbPath() {
            return db;
        }
    }
}
