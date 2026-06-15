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

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void discoverDeduplicatesSessionsAcrossRolloutsAndHistory() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("05").resolve("31");
        Files.createDirectories(sessions);

        String sessionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

        // Write 3 rollout files for the same session (simulates codex resume behavior)
        for (int i = 0; i < 3; i++) {
            String filename = String.format("rollout-2026-05-31T0%d-00-00-%s.jsonl", i, sessionId);
            String content = """
                    {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project"}}
                    {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"response %d"}]}}
                    """.formatted(sessionId, i);
            Files.writeString(sessions.resolve(filename), content, StandardCharsets.UTF_8);
        }

        // Also write a history.jsonl entry for the same session
        String historyEntry = """
                {"session_id":"%s","ts":1780000000,"text":"user message"}
                """.formatted(sessionId);
        Files.writeString(tempDir.resolve("history.jsonl"), historyEntry, StandardCharsets.UTF_8);

        TestCodexAdapter adapter = new TestCodexAdapter(tempDir);

        // discover() should count 1 unique session, not 4 (3 rollouts + 1 history)
        SourceInfo info = adapter.discover();
        assertTrue(info.available());
        assertEquals(1, info.sessionCount());
    }

    @Test
    void listDeduplicatesMultipleRolloutsForSameSession() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("05").resolve("31");
        Files.createDirectories(sessions);

        String sessionId = "11111111-2222-3333-4444-555555555555";

        // Write 5 rollout files for the same session
        for (int i = 0; i < 5; i++) {
            String filename = String.format("rollout-2026-05-31T0%d-00-00-%s.jsonl", i, sessionId);
            String content = """
                    {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project"}}
                    {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"response %d"}]}}
                    """.formatted(sessionId, i);
            Files.writeString(sessions.resolve(filename), content, StandardCharsets.UTF_8);
        }

        TestCodexAdapter adapter = new TestCodexAdapter(tempDir);

        // list() should return 1 entry, not 5
        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals(sessionId, summaries.get(0).sessionId());
    }

    @Test
    void discoverCountsDistinctSessionsCorrectly() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("05").resolve("31");
        Files.createDirectories(sessions);

        // Session A: 3 rollout files
        for (int i = 0; i < 3; i++) {
            String filename = String.format("rollout-2026-05-31T0%d-00-00-session-aaa.jsonl", i);
            Files.writeString(sessions.resolve(filename),
                    "{\"type\":\"session_meta\",\"payload\":{\"id\":\"session-aaa\",\"cwd\":\"/work\"}}\n",
                    StandardCharsets.UTF_8);
        }

        // Session B: 1 rollout file
        Files.writeString(sessions.resolve("rollout-2026-05-31T00-00-00-session-bbb.jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"session-bbb\",\"cwd\":\"/work\"}}\n",
                StandardCharsets.UTF_8);

        // Session C: only in history.jsonl (no rollout)
        Files.writeString(tempDir.resolve("history.jsonl"),
                "{\"session_id\":\"session-ccc\",\"ts\":1780000000,\"text\":\"hello\"}\n",
                StandardCharsets.UTF_8);

        TestCodexAdapter adapter = new TestCodexAdapter(tempDir);

        SourceInfo info = adapter.discover();
        assertTrue(info.available());
        assertEquals(3, info.sessionCount()); // aaa + bbb + ccc
    }

    private static class TestCodexAdapter extends CodexAdapter {
        private final Path root;

        private TestCodexAdapter(Path root) {
            this.root = root;
        }

        @Override
        protected Path codexHome() {
            return root;
        }
    }
}
