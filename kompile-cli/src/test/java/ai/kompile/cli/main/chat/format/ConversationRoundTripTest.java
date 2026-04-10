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

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests export/import round-trips for all 4 CLI agent formats.
 */
class ConversationRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private List<ChatHistory.Turn> createTestTurns() {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        turns.add(new ChatHistory.Turn("user", "Hello, can you help me debug this code?"));
        turns.add(new ChatHistory.Turn("assistant", "Of course! Please share the code you're working on."));
        turns.add(new ChatHistory.Turn("user", "Here's the function:\n```python\ndef foo(x):\n    return x + 1\n```"));
        turns.add(new ChatHistory.Turn("assistant", "I see the issue. The function looks correct for simple addition, but let me check for edge cases."));
        return turns;
    }

    @Test
    void testClaudeCodeRoundTrip(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "claude", "test-session-001", "kompile", workingDir);
            assertEquals("claude-code", result.getAgent());
            assertEquals(workingDir.toAbsolutePath().normalize(), result.getWorkingDirectory());
            assertNotNull(result.getSessionPath());

            Path sessionFile = result.getSessionPath();
            assertTrue(Files.exists(sessionFile), "Session file should exist");
            List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            assertEquals(turns.size(), lines.size(), "Should have one line per turn");

            String lastUuid = null;
            for (int i = 0; i < lines.size(); i++) {
                JsonNode node = MAPPER.readTree(lines.get(i));
                assertTrue(node.has("uuid"), "Entry should have uuid");
                assertTrue(node.has("sessionId"), "Entry should have sessionId");
                assertTrue(node.has("timestamp"), "Entry should have timestamp");
                assertEquals(workingDir.toString(), node.path("cwd").asText());
                assertTrue(node.has("version"), "Entry should have version");
                assertTrue(node.has("type"), "Entry should have type");
                assertTrue(node.has("message"), "Entry should have message");

                if (lastUuid != null) {
                    assertEquals(lastUuid, node.path("parentUuid").asText(), "parentUuid should reference previous message");
                }
                lastUuid = node.path("uuid").asText();

                ChatHistory.Turn expected = turns.get(i);
                JsonNode message = node.get("message");

                if (i % 2 == 0) {
                    assertEquals("user", node.path("type").asText());
                    assertEquals("user", message.path("role").asText());
                    assertEquals(expected.content(), message.path("content").asText());
                } else {
                    assertEquals("assistant", node.path("type").asText());
                    assertEquals("assistant", message.path("role").asText());
                    assertTrue(message.has("content"), "Assistant should have content array");
                    assertTrue(message.get("content").isArray(), "Content should be array");
                    assertEquals(expected.content(), message.path("content").get(0).path("text").asText());
                }
            }

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("claude-code", "test-session-001");
            assertEquals(turns.size(), imported.size(), "Imported turns should match original count");
            assertEquals(workingDir.toAbsolutePath().normalize(),
                    ConversationReader.resolveExternalWorkingDirectory("claude-code", "test-session-001"));

            for (int i = 0; i < turns.size(); i++) {
                assertEquals(turns.get(i).role(), imported.get(i).role(), "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), imported.get(i).content(), "Content should match for turn " + i);
            }
        });
    }

    @Test
    void testCodexRoundTrip(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "codex", "test-session-002", "kompile", workingDir);
            assertEquals("codex", result.getAgent());
            assertEquals(workingDir.toAbsolutePath().normalize(), result.getWorkingDirectory());
            assertNotNull(result.getSessionPath());

            Path sessionFile = result.getSessionPath();
            assertTrue(Files.exists(sessionFile), "Session file should exist");
            List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            assertEquals(1 + (turns.size() * 2), lines.size(), "Should have session_meta plus response/event pairs");

            JsonNode meta = MAPPER.readTree(lines.get(0));
            assertEquals("session_meta", meta.path("type").asText());
            assertEquals("test-session-002", meta.path("payload").path("id").asText());
            assertEquals(workingDir.toString(), meta.path("payload").path("cwd").asText());
            assertEquals("cli", meta.path("payload").path("source").asText());
            assertEquals("openai", meta.path("payload").path("model_provider").asText());
            assertTrue(meta.path("payload").has("instructions"), "Should have instructions field");

            for (int i = 0; i < turns.size(); i++) {
                JsonNode messageNode = MAPPER.readTree(lines.get(1 + (i * 2)));
                assertEquals("response_item", messageNode.path("type").asText());

                JsonNode payload = messageNode.path("payload");
                String expectedRole = turns.get(i).role().equals("assistant") ? "assistant" : "user";
                assertEquals(expectedRole, payload.path("role").asText(), "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), payload.path("content").get(0).path("text").asText());
                assertEquals(turns.get(i).role().equals("assistant") ? "output_text" : "input_text",
                        payload.path("content").get(0).path("type").asText());

                JsonNode eventNode = MAPPER.readTree(lines.get(2 + (i * 2)));
                assertEquals("event_msg", eventNode.path("type").asText());
                assertEquals(turns.get(i).content(), eventNode.path("payload").path("message").asText());
                String expectedEventType = turns.get(i).role().equals("assistant") ? "agent_message" : "user_message";
                assertEquals(expectedEventType, eventNode.path("payload").path("type").asText());
                if (turns.get(i).role().equals("user")) {
                    assertTrue(eventNode.path("payload").has("images"), "User messages should have images array");
                }
            }

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("codex", "test-session-002");
            assertEquals(turns.size(), imported.size(), "Imported turns should match original count");
            assertEquals(workingDir.toAbsolutePath().normalize(),
                    ConversationReader.resolveExternalWorkingDirectory("codex", "test-session-002"));

            for (int i = 0; i < turns.size(); i++) {
                assertEquals(turns.get(i).role(), imported.get(i).role(), "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), imported.get(i).content(), "Content should match for turn " + i);
            }
        });
    }

    @Test
    void testQwenRoundTrip(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "qwen", "test-session-003", "kompile", workingDir);
            assertEquals("qwen", result.getAgent());
            assertEquals(workingDir.toAbsolutePath().normalize(), result.getWorkingDirectory());
            assertNotNull(result.getSessionPath());

            Path sessionFile = result.getSessionPath();
            assertTrue(Files.exists(sessionFile), "Session file should exist");
            List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            assertEquals(turns.size(), lines.size(), "Should have one line per turn");

            String lastUuid = null;
            for (int i = 0; i < lines.size(); i++) {
                JsonNode node = MAPPER.readTree(lines.get(i));
                assertTrue(node.has("uuid"), "Entry should have uuid");
                assertTrue(node.has("sessionId"), "Entry should have sessionId");
                assertTrue(node.has("timestamp"), "Entry should have timestamp");
                assertEquals(workingDir.toString(), node.path("cwd").asText());
                assertTrue(node.has("version"), "Entry should have version");
                assertTrue(node.has("type"), "Entry should have type");
                assertTrue(node.has("message"), "Entry should have message");

                if (lastUuid != null) {
                    assertEquals(lastUuid, node.path("parentUuid").asText(), "parentUuid should reference previous message");
                }
                lastUuid = node.path("uuid").asText();

                ChatHistory.Turn expected = turns.get(i);
                JsonNode message = node.get("message");

                if (i % 2 == 0) {
                    assertEquals("user", node.path("type").asText());
                    assertEquals("user", message.path("role").asText());
                    assertEquals(expected.content(), message.path("parts").get(0).path("text").asText());
                } else {
                    assertEquals("assistant", node.path("type").asText());
                    assertEquals("model", message.path("role").asText(), "Qwen uses 'model' for assistant");
                    assertEquals(expected.content(), message.path("parts").get(0).path("text").asText());
                }
            }

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("qwen", "test-session-003");
            assertEquals(turns.size(), imported.size(), "Imported turns should match original count");
            assertEquals(workingDir.toAbsolutePath().normalize(),
                    ConversationReader.resolveExternalWorkingDirectory("qwen", "test-session-003"));

            for (int i = 0; i < turns.size(); i++) {
                assertEquals(turns.get(i).role(), imported.get(i).role(), "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), imported.get(i).content(), "Content should match for turn " + i);
            }
        });
    }

    @Test
    void testGeminiRoundTrip(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            String sessionId = "57c64501-5630-4ac6-900f-c66418ae78ce";
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "gemini", sessionId, "kompile", workingDir);
            assertEquals("gemini", result.getAgent());
            assertEquals(workingDir.toAbsolutePath().normalize(), result.getWorkingDirectory());
            assertNotNull(result.getSessionPath());
            assertTrue(result.getSessionPath().getFileName().toString().endsWith("-57c64501.json"));

            JsonNode root = MAPPER.readTree(result.getSessionPath().toFile());
            assertEquals(sessionId, root.path("sessionId").asText());
            assertEquals(sha256Hex(workingDir.toString()), root.path("projectHash").asText());
            assertEquals(workingDir.toString(), root.path("workingDirectory").asText());
            assertEquals(turns.size(), root.path("messages").size());
            assertEquals("gemini", root.path("messages").get(1).path("type").asText());

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("gemini", sessionId);
            assertEquals(turns.size(), imported.size(), "Imported turns should match original count");
            assertEquals(workingDir.toAbsolutePath().normalize(),
                    ConversationReader.resolveExternalWorkingDirectory("gemini", sessionId));

            for (int i = 0; i < turns.size(); i++) {
                assertEquals(turns.get(i).role(), imported.get(i).role(), "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), imported.get(i).content(), "Content should match for turn " + i);
            }
        });
    }

    @Test
    void testUnsupportedAgent() {
        List<ChatHistory.Turn> turns = createTestTurns();
        
        assertThrows(IOException.class, () -> {
            ConversationExporter.exportToAgent(turns, "unknown-agent", "test-session", "kompile");
        }, "Should throw for unsupported agent");
    }

    @Test
    void testEmptyTurns() {
        List<ChatHistory.Turn> turns = new ArrayList<>();
        
        assertThrows(IOException.class, () -> {
            ConversationExporter.exportToAgent(turns, "claude", "test-session", "kompile");
        }, "Should throw for empty turns");
    }

    private void withTempEnvironment(Path tempDir, ThrowingRunnable action) throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path homeDir = tempDir.resolve("home");
        Path workingDir = tempDir.resolve("workspace");
        Files.createDirectories(homeDir);
        Files.createDirectories(workingDir);

        try {
            System.setProperty("user.home", homeDir.toString());
            System.setProperty("user.dir", workingDir.toString());
            action.run();
        } finally {
            if (originalHome != null) {
                System.setProperty("user.home", originalHome);
            }
            if (originalDir != null) {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    private String sha256Hex(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
