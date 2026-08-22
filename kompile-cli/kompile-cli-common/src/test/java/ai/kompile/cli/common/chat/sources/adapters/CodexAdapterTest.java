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
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void configuredCodexHomeHonorsExplicitProperty() {
        String previous = System.getProperty("kompile.codex.home");
        Path configured = tempDir.resolve("custom-codex-home").toAbsolutePath().normalize();
        try {
            System.setProperty("kompile.codex.home", configured.toString());
            assertEquals(configured, CodexAdapter.configuredCodexHome());
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.codex.home");
            } else {
                System.setProperty("kompile.codex.home", previous);
            }
        }
    }

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

    @Test
    void matchesNativePickerTitleAndExcludesExecAndSubagentRollouts() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("07").resolve("14");
        Files.createDirectories(sessions);

        String rootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String execId = "11111111-2222-3333-4444-555555555555";
        String subagentId = "99999999-8888-7777-6666-555555555555";

        Files.writeString(
                sessions.resolve("rollout-2026-07-14T01-00-00-" + rootId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","originator":"codex-tui","source":"cli","thread_source":"user"}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Rollout prompt"}]}}
                {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"Root response"}]}}
                """.formatted(rootId),
                StandardCharsets.UTF_8);
        Files.writeString(
                sessions.resolve("rollout-2026-07-14T02-00-00-" + execId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","originator":"codex_exec","source":"exec","thread_source":"user"}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Exec prompt"}]}}
                """.formatted(execId),
                StandardCharsets.UTF_8);
        Files.writeString(
                sessions.resolve("rollout-2026-07-14T03-00-00-" + subagentId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","originator":"codex-tui","source":"cli","thread_source":{"sub_agent":{"depth":1}}}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Subagent prompt"}]}}
                """.formatted(subagentId),
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("history.jsonl"), """
                {"session_id":"%s","ts":1780000000,"text":"Native Codex picker title"}
                {"session_id":"%s","ts":1780000001,"text":"Exec prompt"}
                {"session_id":"%s","ts":1780000002,"text":"Subagent prompt"}
                """.formatted(rootId, execId, subagentId), StandardCharsets.UTF_8);

        CodexAdapter adapter = new TestCodexAdapter(tempDir);

        List<ChatSessionSummary> summaries = adapter.list();
        assertEquals(1, summaries.size());
        assertEquals(rootId, summaries.get(0).sessionId());
        assertEquals("Native Codex picker title", summaries.get(0).title());
        assertEquals("/work/project", summaries.get(0).workingDirectory());
        assertEquals(1, adapter.discover().sessionCount());
    }

    @Test
    void projectScopedListUsesRolloutMetadataWithoutHistoryOnlySessions() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("07").resolve("14");
        Files.createDirectories(sessions);

        String matchingId = "aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb";
        String unrelatedId = "cccccccc-4444-5555-6666-dddddddddddd";
        Files.writeString(
                sessions.resolve("rollout-2026-07-14T01-00-00-" + matchingId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","originator":"codex-tui","source":"cli","thread_source":"user"}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Matching Codex title"}]}}
                """.formatted(matchingId),
                StandardCharsets.UTF_8);
        Files.writeString(
                sessions.resolve("rollout-2026-07-14T02-00-00-" + unrelatedId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/other/project","originator":"codex-tui","source":"cli","thread_source":"user"}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Unrelated title"}]}}
                """.formatted(unrelatedId),
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("history.jsonl"), """
                {"session_id":"history-only","ts":1780000000,"text":"History only"}
                """, StandardCharsets.UTF_8);

        CodexAdapter adapter = new TestCodexAdapter(tempDir);

        List<ChatSessionSummary> summaries = adapter.list(Path.of("/work/project"));
        assertEquals(1, summaries.size());
        assertEquals(matchingId, summaries.get(0).sessionId());
        assertEquals("Matching Codex title", summaries.get(0).title());
        assertEquals("/work/project", summaries.get(0).workingDirectory());
    }

    @Test
    void matchesCodexNativeIndexedPickerWithoutScanningStaleRollouts() throws Exception {
        Path rollout = tempDir.resolve("indexed-rollout.jsonl");
        Files.writeString(rollout, """
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Indexed turn"}]}}
                """, StandardCharsets.UTF_8);
        String staleId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        Path staleRolloutDir = tempDir.resolve("sessions").resolve("2026").resolve("08").resolve("22");
        Files.createDirectories(staleRolloutDir);
        Files.writeString(
                staleRolloutDir.resolve("rollout-2026-08-22T01-00-00-" + staleId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","source":"cli"}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Stale rollout only"}]}}
                """.formatted(staleId),
                StandardCharsets.UTF_8);

        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("state_5.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE threads (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        first_user_message TEXT,
                        preview TEXT,
                        cwd TEXT,
                        source TEXT,
                        thread_source TEXT,
                        updated_at_ms INTEGER,
                        updated_at INTEGER,
                        created_at_ms INTEGER,
                        created_at INTEGER,
                        archived INTEGER,
                        rollout_path TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE thread_spawn_edges (
                        parent_thread_id TEXT,
                        child_thread_id TEXT,
                        status TEXT
                    )
                    """);
            insertIndexedThread(
                    connection,
                    "preview",
                    "Initial prompt",
                    "Initial prompt",
                    "Native preview title",
                    "/work/project",
                    "cli",
                    "user",
                    7_000L,
                    null);
            insertIndexedThread(connection, "matching", "Indexed title", "/work/project",
                    "cli", "user", 5_000L, rollout);
            insertIndexedThread(
                    connection,
                    "vscode",
                    null,
                    "VS Code first message",
                    "VS Code preview",
                    "/work/project",
                    "vscode",
                    "user",
                    4_500L,
                    rollout);
            insertIndexedThread(connection, "nested", "Nested title", "/work/project/child",
                    "cli", "user", 4_000L, rollout);
            insertIndexedThread(connection, "exec", "Exec title", "/work/project",
                    "exec", "user", 3_000L, rollout);
            insertIndexedThread(connection, "mcp", "MCP title", "/work/project",
                    "mcp", "user", 2_900L, rollout);
            insertIndexedThread(connection, "app-server", "App server title", "/work/project",
                    "app_server", "user", 2_800L, rollout);
            insertIndexedThread(connection, "unknown", "Unknown title", "/work/project",
                    "unknown", "user", 2_700L, rollout);
            insertIndexedThread(connection, "subagent", "Subagent title", "/work/project",
                    "cli", "subagent", 2_000L, rollout);
            insertIndexedThread(connection, "spawned", "Spawned title", "/work/project",
                    "cli", "user", 1_000L, rollout);
            statement.execute("INSERT INTO thread_spawn_edges(parent_thread_id, child_thread_id, status) "
                    + "VALUES ('matching', 'spawned', 'completed')");
        }

        CodexAdapter adapter = new TestCodexAdapter(tempDir);

        List<ChatSessionSummary> summaries = adapter.list(Path.of("/work/project"));
        assertEquals(
                List.of("preview", "matching", "vscode", "spawned"),
                summaries.stream().map(ChatSessionSummary::sessionId).toList());
        assertEquals("Native preview title", summaries.get(0).title());
        assertEquals(7_000L, summaries.get(0).lastModifiedMillis());
        assertEquals("Indexed title", summaries.get(1).title());
        assertEquals("VS Code preview", summaries.get(2).title());
        assertEquals("/work/project", summaries.get(0).workingDirectory());
        assertEquals("Indexed turn", adapter.readTurns("matching").get(0).content());
        assertTrue(adapter.isNativeThreadPresent("matching", Path.of("/other/project")),
                "Native UUID presence must not inherit picker working-directory filtering");
        assertFalse(adapter.isNativeThreadPresent(staleId, Path.of("/work/project")),
                "A stale rollout missing from the state DB must not become resumable under its old id");
        assertTrue(adapter.discover().available());
    }

    @Test
    void authoritativePresenceListCannotRepairRollouts() {
        Map<String, Object> params = CodexAdapter.threadListParams(null, null, true);

        assertEquals(true, params.get("useStateDbOnly"));
        assertFalse(params.containsKey("cwd"));
    }

    @Test
    void rolloutFallbackDisplaysOnlyNativeConversationMessages() throws Exception {
        Path sessions = tempDir.resolve("sessions").resolve("2026").resolve("07").resolve("30");
        Files.createDirectories(sessions);
        String sessionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        Files.writeString(
                sessions.resolve("rollout-2026-07-30T01-00-00-" + sessionId + ".jsonl"),
                """
                {"type":"session_meta","payload":{"id":"%s","cwd":"/work/project","source":"cli"}}
                {"type":"response_item","payload":{"role":"developer","content":[{"type":"input_text","text":"hidden instructions"}]}}
                {"type":"response_item","payload":{"role":"system","content":[{"type":"input_text","text":"hidden system prompt"}]}}
                {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"visible question"}]}}
                {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"visible answer"}]}}
                """.formatted(sessionId),
                StandardCharsets.UTF_8);

        List<ChatTurn> turns = new TestCodexAdapter(tempDir).readTurns(sessionId);

        assertEquals(List.of("user", "assistant"), turns.stream().map(ChatTurn::role).toList());
        assertEquals(List.of("visible question", "visible answer"),
                turns.stream().map(ChatTurn::content).toList());
    }

    private static void insertIndexedThread(
            Connection connection,
            String id,
            String title,
            String cwd,
            String source,
            String threadSource,
            long updated,
            Path rollout) throws Exception {
        insertIndexedThread(
                connection,
                id,
                title,
                null,
                null,
                cwd,
                source,
                threadSource,
                updated,
                rollout);
    }

    private static void insertIndexedThread(
            Connection connection,
            String id,
            String title,
            String firstUserMessage,
            String preview,
            String cwd,
            String source,
            String threadSource,
            long updated,
            Path rollout) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO threads(
                    id, title, first_user_message, preview, cwd, source, thread_source,
                    updated_at_ms, archived, rollout_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setString(3, firstUserMessage);
            statement.setString(4, preview);
            statement.setString(5, cwd);
            statement.setString(6, source);
            statement.setString(7, threadSource);
            statement.setLong(8, updated);
            statement.setString(9, rollout == null ? null : rollout.toAbsolutePath().toString());
            statement.executeUpdate();
        }
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

        @Override
        protected Optional<List<ChatSessionSummary>> listAppServerThreads(Path workingDirectory) {
            return Optional.empty();
        }

        @Override
        protected Optional<List<ChatSessionSummary>> listAuthoritativeAppServerThreads() {
            return Optional.empty();
        }

        @Override
        protected Optional<List<ChatTurn>> readAppServerTurns(String sessionId) {
            return Optional.empty();
        }
    }
}
