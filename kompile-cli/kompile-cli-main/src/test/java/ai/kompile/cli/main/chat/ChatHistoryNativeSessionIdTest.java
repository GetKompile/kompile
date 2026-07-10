/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resume tool must never hand a kompile passthrough-* id to a native agent —
 * these tests pin the mapping from a kompile transcript's [harvested:] markers to
 * the underlying agent's real session id.
 */
class ChatHistoryNativeSessionIdTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesHarvestedIdFromTranscript() throws Exception {
        Path transcript = tempDir.resolve("passthrough-1a2b3c4d.txt");
        Files.writeString(transcript, """
                ──── Conversation: passthrough-1a2b3c4d ────
                Started: 2026-07-01 10:00:00
                Agent:   claude
                [harvested:0196f9a7-1111-2222-3333-444455556666]

                > hello

                hi there
                """);

        assertEquals("0196f9a7-1111-2222-3333-444455556666",
                ChatHistory.resolveNativeSessionIdFrom(transcript, "claude"));
    }

    @Test
    void lastHarvestedMarkerWins() throws Exception {
        // The underlying agent session can change across resumes; each harvest appends
        // a fresh marker and the most recent one is the resumable session.
        Path transcript = tempDir.resolve("passthrough-fefefefe.txt");
        Files.writeString(transcript, """
                Agent:   claude
                [harvested:old-session-id]

                > first turn

                reply

                [harvested:new-session-id]
                """);

        assertEquals("new-session-id",
                ChatHistory.resolveNativeSessionIdFrom(transcript, "claude"));
    }

    @Test
    void returnsNullWhenNoMarkerRecorded() throws Exception {
        Path transcript = tempDir.resolve("passthrough-00000000.txt");
        Files.writeString(transcript, """
                Agent:   claude

                > hello

                hi
                """);

        assertNull(ChatHistory.resolveNativeSessionIdFrom(transcript, "claude"));
    }

    @Test
    void returnsNullForMissingTranscript() {
        assertNull(ChatHistory.resolveNativeSessionIdFrom(tempDir.resolve("nope.txt"), "claude"));
        assertNull(ChatHistory.resolveNativeSessionIdFrom(null, "claude"));
    }

    @Test
    void normalizesCodexRolloutFileNameToBareUuid() {
        // Codex harvest records the JSONL file name, but `codex resume` takes the uuid.
        assertEquals("abcd1234-ef56-7890-abcd-1234567890ab",
                ChatHistory.normalizeNativeSessionId(
                        "rollout-2026-06-27T10-30-00-abcd1234-ef56-7890-abcd-1234567890ab", "codex"));
    }

    @Test
    void leavesNonRolloutIdsUntouched() {
        assertEquals("0196f9a7-1111-2222-3333-444455556666",
                ChatHistory.normalizeNativeSessionId("0196f9a7-1111-2222-3333-444455556666", "claude"));
        assertEquals("ses_6f0a9b2c3d", ChatHistory.normalizeNativeSessionId("ses_6f0a9b2c3d", "opencode"));
        // rollout- prefix is only meaningful for codex; other agents keep the raw id
        assertEquals("rollout-custom-name",
                ChatHistory.normalizeNativeSessionId("rollout-custom-name", "claude"));
        assertNull(ChatHistory.normalizeNativeSessionId(null, "claude"));
        assertNull(ChatHistory.normalizeNativeSessionId("", "claude"));
    }

    // ── logHarvestedSource dedup (writes under a redirected user.home) ──────

    private String originalUserHome;

    @BeforeEach
    void saveUserHome() {
        originalUserHome = System.getProperty("user.home");
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void logHarvestedSourceDedupsRepeatedIds() throws Exception {
        System.setProperty("user.home", tempDir.toString());

        ChatHistory history = new ChatHistory("passthrough-dedup01");
        history.open("", "claude", false);
        history.logUserMessage("hello");
        history.logHarvestedSource("session-abc");
        history.logHarvestedSource("session-abc");
        history.logHarvestedSource("session-def");
        history.close();

        Path transcript = tempDir.resolve(".kompile").resolve("conversations")
                .resolve("passthrough-dedup01.txt");
        String content = Files.readString(transcript);
        assertEquals(1, countOccurrences(content, "[harvested:session-abc]"),
                "repeated harvest of the same id must be recorded once");
        assertTrue(content.contains("[harvested:session-def]"));
        assertEquals("session-def",
                ChatHistory.resolveNativeSessionIdFrom(transcript, "claude"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
