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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ConversationImportTool parsing methods.
 * Tests use reflection to access private parser methods.
 */
public class ConversationImportToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConversationImportTool tool = new ConversationImportTool();

    @Test
    public void testToolIdAndDescription() {
        assertEquals("conversation_import", tool.id());
        assertTrue(tool.description().contains("Import conversations"));
        assertTrue(tool.description().contains("Claude Code"));
        assertTrue(tool.description().contains("OpenCode"));
        assertTrue(tool.description().contains("Codex"));
        assertTrue(tool.description().contains("Qwen"));
    }

    @Test
    public void testParseQwenJsonl(@TempDir Path tempDir) throws Exception {
        // Create a sample Qwen JSONL file
        Path qwenFile = tempDir.resolve("test.jsonl");
        List<String> lines = List.of(
                "{\"uuid\":\"test-1\",\"sessionId\":\"test-session\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"parts\":[{\"text\":\"Hello\"}]}}",
                "{\"uuid\":\"test-2\",\"sessionId\":\"test-session\",\"type\":\"assistant\",\"message\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hi there!\"}]}}",
                "{\"uuid\":\"test-3\",\"sessionId\":\"test-session\",\"type\":\"system\",\"subtype\":\"ui_telemetry\"}",
                "{\"uuid\":\"test-4\",\"sessionId\":\"test-session\",\"type\":\"user\",\"message\":{\"role\":\"user\",\"parts\":[{\"text\":\"How are you?\"}]}}"
        );
        Files.write(qwenFile, lines, StandardCharsets.UTF_8);

        // Use reflection to call the private parseQwenJsonl method
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "parseQwenJsonl", Path.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<?> messages = (List<?>) method.invoke(tool, qwenFile);

        assertEquals(3, messages.size());
        
        // Access message fields via reflection
        Object msg0 = messages.get(0);
        java.lang.reflect.Field roleField = msg0.getClass().getDeclaredField("role");
        java.lang.reflect.Field contentField = msg0.getClass().getDeclaredField("content");
        roleField.setAccessible(true);
        contentField.setAccessible(true);
        
        assertEquals("user", roleField.get(msg0));
        assertEquals("Hello", contentField.get(msg0));
        
        Object msg1 = messages.get(1);
        assertEquals("assistant", roleField.get(msg1));
        assertEquals("Hi there!", contentField.get(msg1));
        
        Object msg2 = messages.get(2);
        assertEquals("user", roleField.get(msg2));
        assertEquals("How are you?", contentField.get(msg2));
    }

    @Test
    public void testParseCodexJsonl(@TempDir Path tempDir) throws Exception {
        // Create a sample Codex JSONL file
        Path codexFile = tempDir.resolve("history.jsonl");
        List<String> lines = List.of(
                "{\"session_id\":\"session-1\",\"ts\":1234567890,\"text\":\"Hello\"}",
                "{\"session_id\":\"session-1\",\"ts\":1234567891,\"text\":\"How are you?\"}",
                "{\"session_id\":\"session-2\",\"ts\":1234567892,\"text\":\"Different session\"}"
        );
        Files.write(codexFile, lines, StandardCharsets.UTF_8);

        // Use reflection to call the private parseCodexJsonl method
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "parseCodexJsonl", Path.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<?> messages = (List<?>) method.invoke(tool, codexFile, "session-1");

        assertEquals(2, messages.size());
        
        java.lang.reflect.Field roleField = messages.get(0).getClass().getDeclaredField("role");
        java.lang.reflect.Field contentField = messages.get(0).getClass().getDeclaredField("content");
        roleField.setAccessible(true);
        contentField.setAccessible(true);
        
        assertEquals("user", roleField.get(messages.get(0)));
        assertEquals("Hello", contentField.get(messages.get(0)));
        assertEquals("user", roleField.get(messages.get(1)));
        assertEquals("How are you?", contentField.get(messages.get(1)));
    }

    @Test
    public void testParseOpenCodeSqlite(@TempDir Path tempDir) throws Exception {
        // Create a sample OpenCode SQLite database
        Path dbFile = tempDir.resolve("opencode.db");
        String dbUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            // Create tables
            stmt.execute("""
                    CREATE TABLE session (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        time_created INTEGER,
                        time_updated INTEGER
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE message (
                        id TEXT PRIMARY KEY,
                        session_id TEXT,
                        time_created INTEGER,
                        time_updated INTEGER,
                        data TEXT
                    )
                    """);

            stmt.execute("""
                    CREATE TABLE part (
                        id TEXT PRIMARY KEY,
                        message_id TEXT,
                        session_id TEXT,
                        time_created INTEGER,
                        time_updated INTEGER,
                        data TEXT
                    )
                    """);

            // Insert test data
            stmt.execute("INSERT INTO session VALUES ('session-1', 'Test Session', 1234567890, 1234567895)");

            stmt.execute("INSERT INTO message VALUES ('msg-1', 'session-1', 1234567890, 1234567890, " +
                    "'{\"role\":\"user\",\"time\":{\"created\":1234567890}}')");
            stmt.execute("INSERT INTO part VALUES ('part-1', 'msg-1', 'session-1', 1234567890, 1234567890, " +
                    "'{\"type\":\"text\",\"text\":\"Hello\"}')");

            stmt.execute("INSERT INTO message VALUES ('msg-2', 'session-1', 1234567891, 1234567891, " +
                    "'{\"role\":\"assistant\",\"time\":{\"created\":1234567891}}')");
            stmt.execute("INSERT INTO part VALUES ('part-2', 'msg-2', 'session-1', 1234567891, 1234567891, " +
                    "'{\"type\":\"text\",\"text\":\"Hi there!\"}')");
        }

        // Use reflection to call the private parseOpenCodeSqlite method
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "parseOpenCodeSqlite", Path.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<?> messages = (List<?>) method.invoke(tool, dbFile, "session-1");

        assertEquals(2, messages.size());
        
        java.lang.reflect.Field roleField = messages.get(0).getClass().getDeclaredField("role");
        java.lang.reflect.Field contentField = messages.get(0).getClass().getDeclaredField("content");
        roleField.setAccessible(true);
        contentField.setAccessible(true);
        
        assertEquals("user", roleField.get(messages.get(0)));
        assertEquals("Hello", contentField.get(messages.get(0)));
        assertEquals("assistant", roleField.get(messages.get(1)));
        assertEquals("Hi there!", contentField.get(messages.get(1)));
    }

    @Test
    public void testSanitizeId() throws Exception {
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "sanitizeId", String.class);
        method.setAccessible(true);

        assertEquals("test-id-123", method.invoke(tool, "test-id-123"));
        assertEquals("test_id_123", method.invoke(tool, "test@id#123"));
        assertEquals("test_id", method.invoke(tool, "test__id"));
        assertEquals("test", method.invoke(tool, "_test_"));
    }

    @Test
    public void testGetAgentName() throws Exception {
        java.lang.reflect.Method method = ConversationImportTool.class.getDeclaredMethod(
                "getAgentName", String.class);
        method.setAccessible(true);

        assertEquals("claude", method.invoke(tool, "claude-code"));
        assertEquals("opencode", method.invoke(tool, "opencode"));
        assertEquals("codex", method.invoke(tool, "codex"));
        assertEquals("qwen", method.invoke(tool, "qwen"));
        assertEquals("unknown", method.invoke(tool, "unknown"));
    }
}
