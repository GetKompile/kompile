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
    void readsInstalledSessionMessageSchemaWithoutSequenceColumn() throws Exception {
        Path db = tempDir.resolve("installed-session-message-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE session_message (
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        time_created INTEGER NOT NULL,
                        time_updated INTEGER NOT NULL,
                        data TEXT NOT NULL
                    )
                    """);
            insertSession(connection, "installed-session", "Installed schema", "/work/current", 3_000L);
            insertUnsequencedSessionMessage(
                    connection,
                    "assistant-message",
                    "installed-session",
                    "assistant",
                    2_000L,
                    """
                    {"content":[{"type":"text","text":"Second turn"}]}
                    """);
            insertUnsequencedSessionMessage(
                    connection,
                    "user-message",
                    "installed-session",
                    "user",
                    1_000L,
                    """
                    {"text":"First turn","files":[],"agents":[]}
                    """);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        List<ChatTurn> turns = adapter.readTurns("installed-session");

        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("First turn", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("Second turn", turns.get(1).content());
    }

    @Test
    void usesMessagePartsWhenSessionMessageContainsOnlyControlEvents() throws Exception {
        Path db = tempDir.resolve("installed-hybrid-opencode.db");
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
                        time_created INTEGER NOT NULL,
                        time_updated INTEGER NOT NULL,
                        data TEXT NOT NULL
                    )
                    """);

            insertSession(connection, "hybrid-session", "Hybrid", "/work/hybrid", 5_000L);
            insertMessage(connection, "hybrid-user", "hybrid-session", 1_000L, """
                    {"role":"user","time":{"created":1000}}
                    """);
            insertPart(connection, "hybrid-user-text", "hybrid-user", "hybrid-session", 1_001L, """
                    {"type":"text","text":"Define JSON in one sentence"}
                    """);
            insertMessage(connection, "hybrid-assistant", "hybrid-session", 2_000L, """
                    {"role":"assistant","time":{"created":2000}}
                    """);
            insertPart(connection, "hybrid-assistant-text", "hybrid-assistant",
                    "hybrid-session", 2_001L, """
                    {"type":"text","text":"JSON is a text format for structured data."}
                    """);

            insertUnsequencedSessionMessage(connection, "agent-event-1", "hybrid-session",
                    "agent-switched", 900L, """
                    {"agent":"build"}
                    """);
            insertUnsequencedSessionMessage(connection, "model-event", "hybrid-session",
                    "model-switched", 901L, """
                    {"model":{"id":"test-model"}}
                    """);
            insertUnsequencedSessionMessage(connection, "agent-event-2", "hybrid-session",
                    "agent-switched", 902L, """
                    {"agent":"build"}
                    """);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        assertEquals(2, adapter.list().get(0).messageCount());

        List<ChatTurn> turns = adapter.readTurns("hybrid-session");
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Define JSON in one sentence", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("JSON is a text format for structured data.", turns.get(1).content());
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

    @Test
    void excludesChildSessionsFromNativeResumeListing() throws Exception {
        Path db = tempDir.resolve("root-only-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE session ADD COLUMN parent_id TEXT");
            insertSession(connection, "root-session", "Native root title", "/work/project", 1_000L);
            insertSession(connection, "child-session", "Explore (@explore subagent)", "/work/project", 2_000L);
            statement.execute("UPDATE session SET parent_id = 'root-session' WHERE id = 'child-session'");
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);

        assertEquals(1, adapter.discover().sessionCount());
        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals("root-session", summaries.get(0).sessionId());
        assertEquals("Native root title", summaries.get(0).title());
    }

    @Test
    void legacySchemaFallsBackToTheExactWorkingDirectory() throws Exception {
        Path db = tempDir.resolve("project-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db)) {
            insertSession(connection, "matching", "Matching", "/work/project", 3_000L);
            insertSession(connection, "nested", "Nested", "/work/project/child", 2_000L);
            insertSession(connection, "unrelated", "Unrelated", "/other/project", 1_000L);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);

        List<ChatSessionSummary> summaries = adapter.list(Path.of("/work/project"));
        assertEquals(1, summaries.size());
        assertEquals("matching", summaries.get(0).sessionId());
        assertEquals("/work/project", summaries.get(0).workingDirectory());
    }

    @Test
    void projectScopedListMatchesNativeOpenCodeRootsAndDefaultLimit() throws Exception {
        Path db = tempDir.resolve("native-project-opencode.db");
        createSessionTable(db);
        try (Connection connection = connect(db);
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE session ADD COLUMN project_id TEXT");
            statement.execute("ALTER TABLE session ADD COLUMN parent_id TEXT");
            statement.execute("""
                    CREATE TABLE project (
                        id TEXT PRIMARY KEY,
                        worktree TEXT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO project(id, worktree) VALUES "
                    + "('current-project', '/work/project'), "
                    + "('other-project', '/other/project')");

            for (int i = 0; i < 102; i++) {
                insertProjectSession(
                        connection,
                        String.format("root-%03d", i),
                        "Native title " + i,
                        i % 2 == 0 ? "/work/project" : "/work/project/module",
                        10_000L + i,
                        "current-project",
                        null);
            }
            insertProjectSession(
                    connection,
                    "child-session",
                    "Explore (@explore subagent)",
                    "/work/project",
                    99_999L,
                    "current-project",
                    "root-101");
            insertProjectSession(
                    connection,
                    "unrelated",
                    "Other project",
                    "/other/project",
                    100_000L,
                    "other-project",
                    null);
        }

        OpenCodeAdapter adapter = new TestOpenCodeAdapter(db);
        List<ChatSessionSummary> summaries =
                adapter.list(Path.of("/work/project/deeper"));

        assertTrue(adapter.isWorkingDirectoryScopeAuthoritative());
        assertEquals(100, summaries.size());
        assertEquals("root-101", summaries.get(0).sessionId());
        assertEquals("Native title 101", summaries.get(0).title());
        assertEquals("root-002", summaries.get(99).sessionId());
        assertTrue(summaries.stream()
                .anyMatch(summary -> "/work/project/module".equals(summary.workingDirectory())));
        assertFalse(summaries.stream()
                .anyMatch(summary -> "child-session".equals(summary.sessionId())));
        assertFalse(summaries.stream()
                .anyMatch(summary -> "unrelated".equals(summary.sessionId())));
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

    private static void insertProjectSession(
            Connection connection,
            String id,
            String title,
            String directory,
            long updated,
            String projectId,
            String parentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO session("
                        + "id, title, directory, time_updated, project_id, parent_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setString(3, directory);
            statement.setLong(4, updated);
            statement.setString(5, projectId);
            statement.setString(6, parentId);
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

    private static void insertUnsequencedSessionMessage(
            Connection connection,
            String id,
            String sessionId,
            String type,
            long created,
            String data) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO session_message("
                        + "id, session_id, type, time_created, time_updated, data"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, type);
            statement.setLong(4, created);
            statement.setLong(5, created);
            statement.setString(6, data);
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
