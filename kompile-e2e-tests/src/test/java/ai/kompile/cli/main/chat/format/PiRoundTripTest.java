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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests Pi export/import round-trip: conversations exported to Pi's native JSONL
 * format can be re-imported via the PiAdapter through ConversationReader.
 */
class PiRoundTripTest {

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
    void testPiRoundTrip(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "test-pi-session-001", "kompile", workingDir);

            // Verify export metadata
            assertEquals("pi", result.getAgent());
            assertEquals(workingDir.toAbsolutePath().normalize(), result.getWorkingDirectory());
            assertNotNull(result.getSessionPath());
            assertTrue(result.getResumeCommand().contains("pi --session"));
            assertTrue(result.getResumeCommand().contains("test-pi-session-001"));

            // Verify the JSONL file structure
            Path sessionFile = result.getSessionPath();
            assertTrue(Files.exists(sessionFile), "Session file should exist");
            List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
            assertEquals(1 + turns.size(), lines.size(), "Should have header + one line per turn");

            // Verify session header
            JsonNode header = MAPPER.readTree(lines.get(0));
            assertEquals("session", header.path("type").asText());
            assertEquals(3, header.path("version").asInt());
            assertEquals("test-pi-session-001", header.path("id").asText());
            assertEquals(workingDir.toString(), header.path("cwd").asText());
            assertTrue(header.has("timestamp"));

            // Verify each turn entry
            String lastId = null;
            for (int i = 0; i < turns.size(); i++) {
                JsonNode entry = MAPPER.readTree(lines.get(i + 1));
                ChatHistory.Turn expected = turns.get(i);

                // Entry structure
                assertTrue(entry.has("id"), "Entry should have id");
                assertTrue(entry.has("timestamp"), "Entry should have timestamp");
                assertTrue(entry.has("message"), "Entry should have message");

                // Type matches role
                String expectedType = expected.role().equals("assistant") ? "assistant" : "user";
                assertEquals(expectedType, entry.path("type").asText());

                // Parent chain
                if (lastId != null) {
                    assertEquals(lastId, entry.path("parentId").asText(),
                            "Entry should reference previous entry as parent");
                }
                lastId = entry.path("id").asText();

                // Message content
                JsonNode message = entry.path("message");
                assertEquals(expectedType, message.path("role").asText());
                assertEquals(expected.content(), message.path("content").asText());
            }

            // Verify round-trip: read back via ConversationReader
            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("pi", "test-pi-session-001");
            assertEquals(turns.size(), imported.size(), "Imported turns should match original count");

            for (int i = 0; i < turns.size(); i++) {
                assertEquals(turns.get(i).role(), imported.get(i).role(),
                        "Role should match for turn " + i);
                assertEquals(turns.get(i).content(), imported.get(i).content(),
                        "Content should match for turn " + i);
            }

            // Verify working directory round-trip
            Path resolvedCwd = ConversationReader.resolveExternalWorkingDirectory("pi", "test-pi-session-001");
            assertEquals(workingDir.toAbsolutePath().normalize(), resolvedCwd);
        });
    }

    @Test
    void testPiExportProjectDirEncoding(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "encoding-test", "kompile", workingDir);

            // The session file should be under a --encoded-path-- directory
            Path sessionFile = result.getSessionPath();
            Path projectDir = sessionFile.getParent();
            String dirName = projectDir.getFileName().toString();
            assertTrue(dirName.startsWith("--"), "Project dir should start with --");
            assertTrue(dirName.endsWith("--"), "Project dir should end with --");
        });
    }

    @Test
    void testPiExportWithMultilineContent(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = new ArrayList<>();
            turns.add(new ChatHistory.Turn("user", "Line 1\nLine 2\nLine 3"));
            turns.add(new ChatHistory.Turn("assistant", "Response with\nnewlines\npreserved"));

            Path workingDir = tempDir.resolve("workspace");
            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "multiline-test", "kompile", workingDir);

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("pi", "multiline-test");
            assertEquals(2, imported.size());
            assertEquals("Line 1\nLine 2\nLine 3", imported.get(0).content());
            assertEquals("Response with\nnewlines\npreserved", imported.get(1).content());
        });
    }

    @Test
    void testPiExportSingleTurn(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = new ArrayList<>();
            turns.add(new ChatHistory.Turn("user", "Single message"));

            Path workingDir = tempDir.resolve("workspace");
            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "single-turn", "kompile", workingDir);

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("pi", "single-turn");
            assertEquals(1, imported.size());
            assertEquals("user", imported.get(0).role());
            assertEquals("Single message", imported.get(0).content());
        });
    }

    @Test
    void testPiExportAssistantOnlyTurns(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = new ArrayList<>();
            turns.add(new ChatHistory.Turn("assistant", "Proactive response"));

            Path workingDir = tempDir.resolve("workspace");
            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "assistant-only", "kompile", workingDir);

            List<ChatHistory.Turn> imported = ConversationReader.readExternalSession("pi", "assistant-only");
            assertEquals(1, imported.size());
            assertEquals("assistant", imported.get(0).role());
        });
    }

    @Test
    void testPiExportResumeCommand(@TempDir Path tempDir) throws Exception {
        withTempEnvironment(tempDir, () -> {
            List<ChatHistory.Turn> turns = createTestTurns();
            Path workingDir = tempDir.resolve("workspace");

            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    turns, "pi", "resume-cmd-test", "kompile", workingDir);

            assertEquals("pi --session resume-cmd-test", result.getResumeCommand());
        });
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
}
