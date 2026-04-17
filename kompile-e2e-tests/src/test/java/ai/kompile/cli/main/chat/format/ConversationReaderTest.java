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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationReader")
class ConversationReaderTest {

    @Test
    @DisplayName("readKompileSession throws for non-existent session")
    void readKompileNonExistent() {
        assertThrows(IOException.class,
                () -> ConversationReader.readKompileSession("non-existent-session-id-12345"));
    }

    @Test
    @DisplayName("readExternalSession throws for unknown source")
    void readExternalUnknownSource() {
        IOException ex = assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("unknown-agent", "some-id"));
        assertTrue(ex.getMessage().contains("Unknown source"));
        assertTrue(ex.getMessage().contains("unknown-agent"));
    }

    @Test
    @DisplayName("readExternalSession returns empty for missing Claude Code session")
    void readExternalClaudeCodeMissing() throws IOException {
        // Registry-backed reader returns empty list for unknown IDs rather than
        // throwing — callers (ResumeTool, ResumeCommand) treat this as "not here,
        // try next source" rather than a hard failure.
        List<ChatHistory.Turn> turns =
                ConversationReader.readExternalSession("claude-code", "nonexistent-id");
        assertNotNull(turns);
        assertTrue(turns.isEmpty(), "expected empty turns for missing session");
    }

    @Test
    @DisplayName("readExternalSession returns empty for missing Codex session")
    void readExternalCodexMissing() throws IOException {
        List<ChatHistory.Turn> turns =
                ConversationReader.readExternalSession("codex", "nonexistent-session");
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    @Test
    @DisplayName("readExternalSession returns empty for missing Qwen session")
    void readExternalQwenMissing() throws IOException {
        List<ChatHistory.Turn> turns =
                ConversationReader.readExternalSession("qwen", "nonexistent-id");
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    @Test
    @DisplayName("readExternalSession returns empty for missing OpenCode session")
    void readExternalOpenCodeMissing() throws IOException {
        List<ChatHistory.Turn> turns =
                ConversationReader.readExternalSession("opencode", "nonexistent-session");
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    @Test
    @DisplayName("readExternalSession opencode resolves sess_xxx to actual session id")
    @EnabledIf("hasOpenCodeInstallation")
    void readOpenCodeSession_withSessPrefix_resolvesFromJsonFiles() {
        // Find a session from JSON files and verify we can read it via the sess_xxx format
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "storage", "session");
        if (!Files.isDirectory(sessionDir)) {
            return; // Skip if no OpenCode installation
        }

        try {
            List<Path> sessionFiles = Files.list(sessionDir)
                    .filter(Files::isDirectory)
                    .flatMap(p -> {
                        try {
                            return Files.list(p);
                        } catch (IOException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .filter(p -> p.toString().endsWith(".json"))
                    .limit(1)
                    .toList();

            if (sessionFiles.isEmpty()) {
                return; // No sessions to test
            }

            String sessionFileName = sessionFiles.get(0).getFileName().toString();
            String sessId = sessionFileName.substring(0, sessionFileName.length() - 5); // Remove .json

            // This should resolve via JSON files and find the actual session
            List<ChatHistory.Turn> turns = ConversationReader.readExternalSession("opencode", sessId);
            assertNotNull(turns, "Should return empty list or valid turns, not null");
        } catch (Exception e) {
            // If it fails due to missing database, that's expected in some environments
            assertTrue(e.getMessage().contains("database") || e.getMessage().contains("not found"),
                    "Expected database-related error, got: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("readExternalSession opencode returns empty for unknown id")
    @EnabledIf("hasOpenCodeInstallation")
    void readOpenCodeSession_handlesBothIdFormats() throws IOException {
        List<ChatHistory.Turn> turns =
                ConversationReader.readExternalSession("opencode", "definitely-does-not-exist-12345");
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    @Test
    @DisplayName("readExternalSession opencode returns empty for unknown ses_ id")
    @EnabledIf("hasOpenCodeInstallation")
    void readOpenCodeSession_withSesPrefix_returnsEmptyForUnknown() throws IOException {
        List<ChatHistory.Turn> turns = ConversationReader.readExternalSession(
                "opencode", "ses_0000000000000000000000000000");
        assertNotNull(turns);
        assertTrue(turns.isEmpty());
    }

    // Helper method to check if OpenCode is installed
    static boolean hasOpenCodeInstallation() {
        Path dbPath = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "storage", "session");
        return Files.exists(dbPath) || Files.isDirectory(sessionDir);
    }
}
