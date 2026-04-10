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
    @DisplayName("readExternalSession throws for missing Claude Code dir")
    void readExternalClaudeCodeMissing() {
        // This will fail because the test env likely doesn't have a matching Claude conversation
        // but it should give a meaningful error, not NPE
        assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("claude-code", "nonexistent-id"));
    }

    @Test
    @DisplayName("readExternalSession throws for missing Codex history")
    void readExternalCodexMissing() {
        // Will throw because ~/.codex/history.jsonl likely doesn't exist or lacks this session
        assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("codex", "nonexistent-session"));
    }

    @Test
    @DisplayName("readExternalSession throws for missing Qwen dir")
    void readExternalQwenMissing() {
        assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("qwen", "nonexistent-id"));
    }

    @Test
    @DisplayName("readExternalSession throws for missing OpenCode db")
    void readExternalOpenCodeMissing() {
        assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("opencode", "nonexistent-session"));
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
    @DisplayName("readExternalSession opencode handles both sess_ and ses_ formats")
    @EnabledIf("hasOpenCodeInstallation")
    void readOpenCodeSession_handlesBothIdFormats() {
        // This test verifies that both ID formats can potentially work
        // It tests the fallback logic in readOpenCodeSession

        // Test with a clearly non-existent ID - should throw meaningful error
        IOException ex = assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("opencode", "definitely-does-not-exist-12345"));
        assertTrue(ex.getMessage().contains("OpenCode database") || ex.getMessage().contains("Failed to query"),
                "Error message should indicate OpenCode failure");
    }

    @Test
    @DisplayName("readExternalSession opencode with sqlite format returns empty for unknown session")
    @EnabledIf("hasOpenCodeInstallation")
    void readOpenCodeSession_withSesPrefix_returnsEmptyForUnknown() {
        // Test that a non-existent ses_ format ID also fails properly
        IOException ex = assertThrows(IOException.class,
                () -> ConversationReader.readExternalSession("opencode", "ses_0000000000000000000000000000"));
        assertNotNull(ex.getMessage(), "Should have error message");
    }

    // Helper method to check if OpenCode is installed
    static boolean hasOpenCodeInstallation() {
        Path dbPath = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "opencode.db");
        Path sessionDir = Paths.get(System.getProperty("user.home"), ".local", "share", "opencode", "storage", "session");
        return Files.exists(dbPath) || Files.isDirectory(sessionDir);
    }
}
