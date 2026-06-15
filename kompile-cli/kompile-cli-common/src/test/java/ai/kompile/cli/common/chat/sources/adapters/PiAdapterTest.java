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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PiAdapterTest {

    private PiAdapter adapter;
    private String originalHome;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        adapter = new PiAdapter();
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

    // ─── Identity ─────────────────────────────────────────────────────────

    @Test
    void testId() {
        assertEquals("pi", adapter.id());
    }

    @Test
    void testDisplayName() {
        assertEquals("Pi", adapter.displayName());
    }

    // ─── Discovery ────────────────────────────────────────────────────────

    @Test
    void testDiscoverWhenDirectoryMissing() {
        SourceInfo info = adapter.discover();
        assertFalse(info.available());
        assertEquals("pi", info.source());
        assertEquals("Pi", info.displayName());
        assertTrue(info.reason().contains("missing"));
    }

    @Test
    void testDiscoverWhenDirectoryExistsButEmpty() throws IOException {
        Path sessionsDir = tempDir.resolve(".pi").resolve("agent").resolve("sessions");
        Files.createDirectories(sessionsDir);

        SourceInfo info = adapter.discover();
        assertTrue(info.available());
        assertEquals(0, info.sessionCount());
    }

    @Test
    void testDiscoverCountsSessions() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_abc.jsonl", "sess-abc", "/home/user/project",
                userEntry("Hello"), assistantEntry("Hi there"));
        writeSession(projectDir, "1235_def.jsonl", "sess-def", "/home/user/project",
                userEntry("Question"), assistantEntry("Answer"));

        SourceInfo info = adapter.discover();
        assertTrue(info.available());
        assertEquals(2, info.sessionCount());
    }

    // ─── Listing ──────────────────────────────────────────────────────────

    @Test
    void testListWhenEmpty() throws IOException {
        List<ChatSessionSummary> sessions = adapter.list();
        assertTrue(sessions.isEmpty());
    }

    @Test
    void testListReturnsSessions() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_abc.jsonl", "sess-abc", "/home/user/project",
                userEntry("Hello"), assistantEntry("Hi there"));
        writeSession(projectDir, "1235_def.jsonl", "sess-def", "/home/user/project",
                userEntry("Question"));

        List<ChatSessionSummary> sessions = adapter.list();
        assertEquals(2, sessions.size());
        // Sorted by modification time descending
        assertTrue(sessions.stream().anyMatch(s -> "sess-abc".equals(s.sessionId())));
        assertTrue(sessions.stream().anyMatch(s -> "sess-def".equals(s.sessionId())));
    }

    @Test
    void testListSkipsNonSessionFiles() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_abc.jsonl", "sess-abc", "/home/user/project",
                userEntry("Hello"));
        // Write a non-session JSONL (header type != "session")
        Files.writeString(projectDir.resolve("other.jsonl"),
                "{\"type\":\"config\",\"version\":1}\n{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"test\"}}",
                StandardCharsets.UTF_8);

        List<ChatSessionSummary> sessions = adapter.list();
        assertEquals(1, sessions.size());
        assertEquals("sess-abc", sessions.get(0).sessionId());
    }

    // ─── Reading Turns ────────────────────────────────────────────────────

    @Test
    void testReadTurnsBasicConversation() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-abc.jsonl", "sess-abc", "/home/user/project",
                userEntry("Hello, how are you?"),
                assistantEntry("I'm doing well, thanks!"),
                userEntry("Can you help me with code?"),
                assistantEntry("Of course! What do you need?"));

        List<ChatTurn> turns = adapter.readTurns("sess-abc");
        assertEquals(4, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Hello, how are you?", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("I'm doing well, thanks!", turns.get(1).content());
        assertEquals("user", turns.get(2).role());
        assertEquals("Can you help me with code?", turns.get(2).content());
        assertEquals("assistant", turns.get(3).role());
        assertEquals("Of course! What do you need?", turns.get(3).content());
    }

    @Test
    void testReadTurnsFiltersToolResults() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-tool.jsonl", "sess-tool", "/home/user/project",
                userEntry("Run ls"),
                // toolResult entry — should be filtered
                "{\"type\":\"toolResult\",\"id\":\"tr1\",\"parentId\":\"a1\",\"timestamp\":\"2025-01-01T00:00:02Z\",\"message\":{\"role\":\"tool\",\"content\":\"file1.txt\\nfile2.txt\"}}",
                assistantEntry("I see two files."));

        List<ChatTurn> turns = adapter.readTurns("sess-tool");
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Run ls", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("I see two files.", turns.get(1).content());
    }

    @Test
    void testReadTurnsFiltersBashExecution() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-bash.jsonl", "sess-bash", "/home/user/project",
                userEntry("Run a command"),
                // bashExecution entry — should be filtered
                "{\"type\":\"bashExecution\",\"id\":\"be1\",\"parentId\":\"a1\",\"timestamp\":\"2025-01-01T00:00:02Z\",\"message\":{\"role\":\"bashExecution\",\"command\":\"ls -la\",\"output\":\"total 0\"}}",
                assistantEntry("Done."));

        List<ChatTurn> turns = adapter.readTurns("sess-bash");
        assertEquals(2, turns.size());
    }

    @Test
    void testReadTurnsFiltersLeafAndLabel() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-leaf.jsonl", "sess-leaf", "/home/user/project",
                userEntry("Hello"),
                "{\"type\":\"leaf\",\"id\":\"l1\",\"parentId\":\"a1\",\"timestamp\":\"2025-01-01T00:00:02Z\"}",
                "{\"type\":\"label\",\"id\":\"lb1\",\"parentId\":\"a1\",\"timestamp\":\"2025-01-01T00:00:03Z\"}",
                assistantEntry("World"));

        List<ChatTurn> turns = adapter.readTurns("sess-leaf");
        assertEquals(2, turns.size());
        assertEquals("Hello", turns.get(0).content());
        assertEquals("World", turns.get(1).content());
    }

    @Test
    void testReadTurnsFiltersBranchAndCompactionSummary() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-sum.jsonl", "sess-sum", "/home/user/project",
                userEntry("Start"),
                "{\"type\":\"branchSummary\",\"id\":\"bs1\",\"timestamp\":\"2025-01-01T00:00:02Z\",\"message\":{\"role\":\"assistant\",\"content\":\"Summary of branch\"}}",
                "{\"type\":\"compactionSummary\",\"id\":\"cs1\",\"timestamp\":\"2025-01-01T00:00:03Z\",\"message\":{\"role\":\"assistant\",\"content\":\"Compacted\"}}",
                assistantEntry("End"));

        List<ChatTurn> turns = adapter.readTurns("sess-sum");
        assertEquals(2, turns.size());
    }

    @Test
    void testReadTurnsMapsCustomToUser() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-custom.jsonl", "sess-custom", "/home/user/project",
                // custom entries are user-injected content
                "{\"type\":\"custom\",\"id\":\"c1\",\"timestamp\":\"2025-01-01T00:00:01Z\",\"message\":{\"role\":\"custom\",\"content\":\"Injected context\"}}",
                assistantEntry("I see the context."));

        List<ChatTurn> turns = adapter.readTurns("sess-custom");
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Injected context", turns.get(0).content());
    }

    @Test
    void testReadTurnsReturnsEmptyForMissingSession() throws IOException {
        List<ChatTurn> turns = adapter.readTurns("nonexistent-session");
        assertTrue(turns.isEmpty());
    }

    @Test
    void testReadTurnsSkipsBlankContent() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-blank.jsonl", "sess-blank", "/home/user/project",
                userEntry("Hello"),
                // assistant with blank content — should be skipped
                "{\"type\":\"assistant\",\"id\":\"a1\",\"timestamp\":\"2025-01-01T00:00:02Z\",\"message\":{\"role\":\"assistant\",\"content\":\"   \"}}",
                assistantEntry("Real response"));

        List<ChatTurn> turns = adapter.readTurns("sess-blank");
        assertEquals(2, turns.size());
        assertEquals("Hello", turns.get(0).content());
        assertEquals("Real response", turns.get(1).content());
    }

    @Test
    void testReadTurnsWithContentBlockArray() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        // Pi can also use content block arrays like Anthropic API format
        String assistantWithBlocks = "{\"type\":\"assistant\",\"id\":\"a1\",\"timestamp\":\"2025-01-01T00:00:02Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Block response\"}]}}";
        writeSession(projectDir, "1234_sess-blocks.jsonl", "sess-blocks", "/home/user/project",
                userEntry("Hello"), assistantWithBlocks);

        List<ChatTurn> turns = adapter.readTurns("sess-blocks");
        assertEquals(2, turns.size());
        assertEquals("Block response", turns.get(1).content());
    }

    @Test
    void testReadTurnsWithTextFieldOnMessage() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        // Pi also stores text directly in a "text" field on some message types
        String msgWithTextField = "{\"type\":\"user\",\"id\":\"u1\",\"timestamp\":\"2025-01-01T00:00:01Z\"," +
                "\"message\":{\"role\":\"user\",\"text\":\"Text field content\"}}";
        writeSession(projectDir, "1234_sess-text.jsonl", "sess-text", "/home/user/project",
                msgWithTextField, assistantEntry("Got it"));

        List<ChatTurn> turns = adapter.readTurns("sess-text");
        assertEquals(2, turns.size());
        assertEquals("Text field content", turns.get(0).content());
    }

    // ─── Working Directory ────────────────────────────────────────────────

    @Test
    void testResolveWorkingDirectory() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-cwd.jsonl", "sess-cwd", "/home/user/project",
                userEntry("Hello"));

        Optional<Path> cwd = adapter.resolveWorkingDirectory("sess-cwd");
        assertTrue(cwd.isPresent());
        assertEquals(Path.of("/home/user/project"), cwd.get());
    }

    @Test
    void testResolveWorkingDirectoryEmpty() throws IOException {
        Optional<Path> cwd = adapter.resolveWorkingDirectory("nonexistent");
        assertFalse(cwd.isPresent());
    }

    // ─── Title Resolution ─────────────────────────────────────────────────

    @Test
    void testResolveTitleFromFirstUserMessage() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234_sess-title.jsonl", "sess-title", "/home/user/project",
                userEntry("Help me debug this function"),
                assistantEntry("Sure, let me look at it."));

        String title = adapter.resolveTitle("sess-title");
        assertEquals("Help me debug this function", title);
    }

    @Test
    void testResolveTitleTruncatesLongFirstMessage() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        String longMessage = "A".repeat(100);
        writeSession(projectDir, "1234_sess-long.jsonl", "sess-long", "/home/user/project",
                userEntry(longMessage));

        String title = adapter.resolveTitle("sess-long");
        assertEquals(80, title.length());
        assertTrue(title.endsWith("..."));
    }

    @Test
    void testResolveTitleFallsBackToSessionId() throws IOException {
        String title = adapter.resolveTitle("nonexistent-session");
        assertEquals("nonexistent-session", title);
    }

    // ─── Project Directory Encoding ───────────────────────────────────────

    @Test
    void testEncodeProjectDirUnix() {
        assertEquals("--home-user-project--", PiAdapter.encodeProjectDir("/home/user/project"));
    }

    @Test
    void testEncodeProjectDirWindows() {
        // C:\Users\dev\project → strip leading \, then replace \ and : with -
        // C: becomes C-, \Users becomes -Users → C--Users-dev-project
        assertEquals("--C--Users-dev-project--", PiAdapter.encodeProjectDir("C:\\Users\\dev\\project"));
    }

    @Test
    void testEncodeProjectDirNested() {
        assertEquals("--home-user-deep-nested-path--",
                PiAdapter.encodeProjectDir("/home/user/deep/nested/path"));
    }

    // ─── Session Matching ─────────────────────────────────────────────────

    @Test
    void testFindSessionByFilenameContaining() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        writeSession(projectDir, "1234567890_my-session-id.jsonl", "my-session-id", "/home/user/project",
                userEntry("Hello"));

        List<ChatTurn> turns = adapter.readTurns("my-session-id");
        assertFalse(turns.isEmpty(), "Should find session by filename match");
    }

    @Test
    void testFindSessionByHeaderId() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        // Filename does NOT contain session ID, but header does
        writeSession(projectDir, "1234567890_random.jsonl", "header-session-id", "/home/user/project",
                userEntry("Hello"));

        List<ChatTurn> turns = adapter.readTurns("header-session-id");
        assertFalse(turns.isEmpty(), "Should find session by header ID");
    }

    // ─── findMatchingProjectDirs ──────────────────────────────────────────

    @Test
    void testFindMatchingProjectDirs() throws IOException {
        Path sessionsRoot = tempDir.resolve(".pi").resolve("agent").resolve("sessions");
        Files.createDirectories(sessionsRoot);
        Path match = sessionsRoot.resolve("--home-user-project--");
        Files.createDirectories(match);
        Path noMatch = sessionsRoot.resolve("--home-other-project--");
        Files.createDirectories(noMatch);

        List<Path> matches = PiAdapter.findMatchingProjectDirs(sessionsRoot, "/home/user/project");
        assertEquals(1, matches.size());
        assertEquals(match, matches.get(0));
    }

    @Test
    void testFindMatchingProjectDirsNoMatch() throws IOException {
        Path sessionsRoot = tempDir.resolve(".pi").resolve("agent").resolve("sessions");
        Files.createDirectories(sessionsRoot);
        Files.createDirectories(sessionsRoot.resolve("--home-other-project--"));

        List<Path> matches = PiAdapter.findMatchingProjectDirs(sessionsRoot, "/home/user/project");
        assertTrue(matches.isEmpty());
    }

    // ─── parseJsonl static method ─────────────────────────────────────────

    @Test
    void testParseJsonlDirectly() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        Path file = projectDir.resolve("test.jsonl");
        writeSession(projectDir, "test.jsonl", "direct-test", "/home/user/project",
                userEntry("Direct parse test"),
                assistantEntry("Response"));

        List<ChatTurn> turns = PiAdapter.parseJsonl(file);
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Direct parse test", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("Response", turns.get(1).content());
    }

    @Test
    void testParseJsonlSkipsHeaderLine() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        Path file = projectDir.resolve("header-skip.jsonl");
        // The header line (type=session) should be skipped even if it has content
        writeSession(projectDir, "header-skip.jsonl", "skip-header", "/home/user/project",
                userEntry("Only user message"));

        List<ChatTurn> turns = PiAdapter.parseJsonl(file);
        assertEquals(1, turns.size());
        assertEquals("Only user message", turns.get(0).content());
    }

    @Test
    void testParseJsonlHandlesMalformedLines() throws IOException {
        Path projectDir = createProjectDir("home-user-project");
        Path file = projectDir.resolve("malformed.jsonl");
        String content = "{\"type\":\"session\",\"version\":3,\"id\":\"sess-mal\",\"timestamp\":\"2025-01-01T00:00:00Z\",\"cwd\":\"/tmp\"}\n" +
                "this is not valid json\n" +
                "{\"type\":\"user\",\"id\":\"u1\",\"timestamp\":\"2025-01-01T00:00:01Z\",\"message\":{\"role\":\"user\",\"content\":\"Valid line\"}}\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        List<ChatTurn> turns = PiAdapter.parseJsonl(file);
        assertEquals(1, turns.size());
        assertEquals("Valid line", turns.get(0).content());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Path createProjectDir(String encodedName) throws IOException {
        Path dir = tempDir.resolve(".pi").resolve("agent").resolve("sessions")
                .resolve("--" + encodedName + "--");
        Files.createDirectories(dir);
        return dir;
    }

    private void writeSession(Path projectDir, String filename, String sessionId,
                              String cwd, String... entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        // Session header
        sb.append(String.format(
                "{\"type\":\"session\",\"version\":3,\"id\":\"%s\",\"timestamp\":\"2025-01-01T00:00:00Z\",\"cwd\":\"%s\"}",
                sessionId, cwd));
        sb.append('\n');
        // Entries
        for (String entry : entries) {
            sb.append(entry).append('\n');
        }
        Files.writeString(projectDir.resolve(filename), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String userEntry(String content) {
        return String.format(
                "{\"type\":\"user\",\"id\":\"u%d\",\"timestamp\":\"2025-01-01T00:00:01Z\"," +
                        "\"message\":{\"role\":\"user\",\"content\":\"%s\"}}",
                content.hashCode() & 0x7fffffff, escapeJson(content));
    }

    private static String assistantEntry(String content) {
        return String.format(
                "{\"type\":\"assistant\",\"id\":\"a%d\",\"timestamp\":\"2025-01-01T00:00:02Z\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"%s\"}}",
                content.hashCode() & 0x7fffffff, escapeJson(content));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
