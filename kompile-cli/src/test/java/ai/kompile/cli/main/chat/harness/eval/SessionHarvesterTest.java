package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.common.chat.sources.ChatAdapterSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionHarvesterTest {

    @TempDir
    Path tempDir;

    // ── Agent installation detection ──────────────────────────────────────

    private static boolean claudeInstalled;
    private static boolean piInstalled;
    private static boolean codexInstalled;
    private static boolean qwenInstalled;
    private static boolean geminiInstalled;
    private static boolean openCodeInstalled;
    private static boolean aiderInstalled;
    private static boolean clineInstalled;
    private static boolean cursorInstalled;
    private static boolean continueInstalled;
    private static boolean kompileInstalled;

    @BeforeAll
    static void detectInstalledAgents() {
        Path home = Path.of(System.getProperty("user.home"));
        claudeInstalled = Files.isDirectory(home.resolve(".claude").resolve("projects"));
        piInstalled = Files.isDirectory(home.resolve(".pi").resolve("agent").resolve("sessions"));
        codexInstalled = Files.isDirectory(home.resolve(".codex").resolve("sessions"));
        qwenInstalled = Files.isDirectory(home.resolve(".qwen").resolve("projects"));
        geminiInstalled = Files.isDirectory(home.resolve(".gemini"));
        openCodeInstalled = Files.exists(home.resolve(".local/share/opencode/opencode.db"))
                || Files.exists(home.resolve(".opencode/opencode.db"));
        aiderInstalled = commandOnPath("aider");
        clineInstalled = isClineInstalled();
        cursorInstalled = Files.isDirectory(
                ChatAdapterSupport.userConfigDir().resolve("Cursor").resolve("User"));
        continueInstalled = Files.isDirectory(home.resolve(".continue"));
        kompileInstalled = Files.isDirectory(home.resolve(".kompile").resolve("conversations"));
    }

    private static boolean commandOnPath(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isClineInstalled() {
        Path config = ChatAdapterSupport.userConfigDir();
        for (String variant : new String[]{"Code", "Code - Insiders"}) {
            for (String ext : new String[]{"saoudrizwan.claude-dev", "RooVeterinaryInc.roo-cline"}) {
                if (Files.isDirectory(config.resolve(variant).resolve("User")
                        .resolve("globalStorage").resolve(ext).resolve("tasks"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ========================================================================
    // Claude project dir encoding
    // ========================================================================

    @Test
    void findClaudeProjectDir_exactMatch() throws IOException {
        // Create a dir that matches the encoded path
        Path projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir);

        String absPath = "/home/user/myproject";
        String encoded = "-home-user-myproject";
        Path encoded_dir = projectsDir.resolve(encoded);
        Files.createDirectories(encoded_dir);

        Path result = SessionHarvester.findClaudeProjectDir(projectsDir, absPath);
        assertNotNull(result);
        assertEquals(encoded_dir, result);
    }

    @Test
    void findClaudeProjectDir_fallbackScan() throws IOException {
        Path projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir);

        // Create a dir with partial path encoding
        Path partialDir = projectsDir.resolve("-home-user");
        Files.createDirectories(partialDir);

        Path result = SessionHarvester.findClaudeProjectDir(projectsDir, "/home/user/myproject");
        assertNotNull(result);
    }

    @Test
    void findClaudeProjectDir_noMatch() throws IOException {
        Path projectsDir = tempDir.resolve("projects");
        Files.createDirectories(projectsDir);

        Path otherDir = projectsDir.resolve("-some-other-project");
        Files.createDirectories(otherDir);

        Path result = SessionHarvester.findClaudeProjectDir(projectsDir, "/home/user/myproject");
        assertNull(result);
    }

    @Test
    void findClaudeProjectDir_nonexistentDir() {
        Path result = SessionHarvester.findClaudeProjectDir(
                tempDir.resolve("nonexistent"), "/home/user/myproject");
        assertNull(result);
    }

    // ========================================================================
    // JSONL file discovery
    // ========================================================================

    @Test
    void findNewestJsonl_findsRecent() throws IOException {
        Path dir = tempDir.resolve("sessions");
        Files.createDirectories(dir);

        // Create some jsonl files
        Path old = dir.resolve("old-session.jsonl");
        Files.writeString(old, "{}\n");
        old.toFile().setLastModified(Instant.now().minusSeconds(3600).toEpochMilli());

        Path recent = dir.resolve("recent-session.jsonl");
        Files.writeString(recent, "{}\n");
        recent.toFile().setLastModified(System.currentTimeMillis());

        File result = SessionHarvester.findNewestJsonl(dir, Instant.now().minusSeconds(60));
        assertNotNull(result);
        assertEquals("recent-session.jsonl", result.getName());
    }

    @Test
    void findNewestJsonl_ignoresOldFiles() throws IOException {
        Path dir = tempDir.resolve("sessions");
        Files.createDirectories(dir);

        Path old = dir.resolve("old-session.jsonl");
        Files.writeString(old, "{}\n");
        // Set modification time to 2 hours ago
        old.toFile().setLastModified(Instant.now().minusSeconds(7200).toEpochMilli());

        // Looking for sessions started 1 second ago — old file should be skipped
        File result = SessionHarvester.findNewestJsonl(dir, Instant.now().minusSeconds(1));
        assertNull(result);
    }

    @Test
    void findNewestJsonl_emptyDir() throws IOException {
        Path dir = tempDir.resolve("empty");
        Files.createDirectories(dir);

        File result = SessionHarvester.findNewestJsonl(dir, Instant.now().minusSeconds(60));
        assertNull(result);
    }

    @Test
    void findNewestJsonl_nonexistentDir() {
        File result = SessionHarvester.findNewestJsonl(
                tempDir.resolve("nonexistent"), Instant.now());
        assertNull(result);
    }

    @Test
    void findNewestJsonl_ignoresNonJsonl() throws IOException {
        Path dir = tempDir.resolve("sessions");
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("notes.txt"), "not a session");
        Files.writeString(dir.resolve("data.json"), "{}");

        File result = SessionHarvester.findNewestJsonl(dir, Instant.now().minusSeconds(60));
        assertNull(result);
    }

    // ========================================================================
    // Recursive discovery
    // ========================================================================

    @Test
    void findNewestJsonlRecursive_findsInSubdirs() throws IOException {
        Path dir = tempDir.resolve("root");
        Path sub = dir.resolve("sub1").resolve("sub2");
        Files.createDirectories(sub);

        Path file = sub.resolve("deep-session.jsonl");
        Files.writeString(file, "{}\n");

        File result = SessionHarvester.findNewestJsonlRecursive(dir, Instant.now().minusSeconds(60));
        assertNotNull(result);
        assertEquals("deep-session.jsonl", result.getName());
    }

    // ========================================================================
    // Tool call harvesting
    // ========================================================================

    @Test
    void harvestToolCalls_unknownAgent_returnsEmpty() {
        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "unknown-agent", tempDir, Instant.now());
        assertTrue(result.isEmpty());
    }

    @Test
    void harvestToolCalls_noSessionDir_returnsEmpty() {
        // Claude projects dir doesn't exist under tempDir
        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "claude", tempDir, Instant.now());
        assertTrue(result.isEmpty());
    }

    // ========================================================================
    // Synthetic fixture tests for new harvest methods
    // ========================================================================

    @Test
    void findNewestTaskDir_findsRecentTask() throws IOException {
        Path tasksRoot = tempDir.resolve("tasks");
        Files.createDirectories(tasksRoot);

        // Old task
        Path oldTask = tasksRoot.resolve("task-old");
        Files.createDirectories(oldTask);
        Path oldConv = oldTask.resolve("api_conversation_history.json");
        Files.writeString(oldConv, "[]");
        oldConv.toFile().setLastModified(Instant.now().minusSeconds(7200).toEpochMilli());

        // Recent task
        Path newTask = tasksRoot.resolve("task-new");
        Files.createDirectories(newTask);
        Path newConv = newTask.resolve("api_conversation_history.json");
        Files.writeString(newConv, "[]");
        newConv.toFile().setLastModified(System.currentTimeMillis());

        File result = SessionHarvester.findNewestTaskDir(tasksRoot, Instant.now().minusSeconds(60));
        assertNotNull(result);
        assertEquals("task-new", result.getName());
    }

    @Test
    void findNewestTaskDir_ignoresOldTasks() throws IOException {
        Path tasksRoot = tempDir.resolve("tasks");
        Files.createDirectories(tasksRoot);

        Path oldTask = tasksRoot.resolve("task-old");
        Files.createDirectories(oldTask);
        Path conv = oldTask.resolve("api_conversation_history.json");
        Files.writeString(conv, "[]");
        conv.toFile().setLastModified(Instant.now().minusSeconds(7200).toEpochMilli());

        File result = SessionHarvester.findNewestTaskDir(tasksRoot, Instant.now().minusSeconds(1));
        assertNull(result);
    }

    @Test
    void findNewestTaskDir_skipsTasksWithoutConversation() throws IOException {
        Path tasksRoot = tempDir.resolve("tasks");
        Files.createDirectories(tasksRoot);

        // Task dir exists but has no api_conversation_history.json
        Path noConvTask = tasksRoot.resolve("task-noconv");
        Files.createDirectories(noConvTask);
        Files.writeString(noConvTask.resolve("task_metadata.json"), "{}");

        File result = SessionHarvester.findNewestTaskDir(tasksRoot, Instant.now().minusSeconds(60));
        assertNull(result);
    }

    @Test
    void findNewestTaskDir_nonexistentDir() {
        File result = SessionHarvester.findNewestTaskDir(
                tempDir.resolve("nonexistent"), Instant.now());
        assertNull(result);
    }

    @Test
    void findNewestSessionFile_findsJsonAndJsonl() throws IOException {
        Path dir = tempDir.resolve("sessions");
        Files.createDirectories(dir);

        Path jsonFile = dir.resolve("session1.json");
        Files.writeString(jsonFile, "{}");

        Path jsonlFile = dir.resolve("session2.jsonl");
        Files.writeString(jsonlFile, "{}");
        // Make jsonl newer
        jsonlFile.toFile().setLastModified(System.currentTimeMillis() + 1000);

        File result = SessionHarvester.findNewestSessionFile(dir, Instant.now().minusSeconds(60));
        assertNotNull(result);
        assertEquals("session2.jsonl", result.getName());
    }

    @Test
    void findNewestSessionFile_walksSubdirs() throws IOException {
        Path dir = tempDir.resolve("sessions");
        Path subDir = dir.resolve("sub");
        Files.createDirectories(subDir);

        Path deepFile = subDir.resolve("deep-session.json");
        Files.writeString(deepFile, "{}");

        File result = SessionHarvester.findNewestSessionFile(dir, Instant.now().minusSeconds(60));
        assertNotNull(result);
        assertEquals("deep-session.json", result.getName());
    }

    // ========================================================================
    // Live harvest tests — one per agent, assume-gated
    // ========================================================================

    @Test
    void harvestToolCalls_claude_live() {
        Assumptions.assumeTrue(claudeInstalled,
                "Claude Code not installed — ~/.claude/projects missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "claude", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30)); // look back 30 days
        assertNotNull(result);
        // With a 30-day window on a dev machine, we should find something
    }

    @Test
    void harvestToolCalls_pi_live() {
        Assumptions.assumeTrue(piInstalled,
                "Pi not installed — ~/.pi/agent/sessions missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "pi", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_codex_live() {
        Assumptions.assumeTrue(codexInstalled,
                "Codex not installed — ~/.codex/sessions missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "codex", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_qwen_live() {
        Assumptions.assumeTrue(qwenInstalled,
                "Qwen not installed — ~/.qwen/projects missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "qwen", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_gemini_live() {
        Assumptions.assumeTrue(geminiInstalled,
                "Gemini not installed — ~/.gemini missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "gemini", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_opencode_live() {
        Assumptions.assumeTrue(openCodeInstalled,
                "OpenCode not installed — no opencode.db found");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "opencode", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_aider_live() {
        Assumptions.assumeTrue(aiderInstalled,
                "Aider not installed");

        // Aider looks for .aider.chat.history.md in workDir
        Path workDir = Path.of(System.getProperty("user.dir"));
        Path aiderHistory = workDir.resolve(".aider.chat.history.md");
        Assumptions.assumeTrue(Files.exists(aiderHistory),
                "No .aider.chat.history.md in current directory");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "aider", workDir, Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_cline_live() {
        Assumptions.assumeTrue(clineInstalled,
                "Cline not installed — no tasks directory found");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "cline", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_cursor_live() {
        Assumptions.assumeTrue(cursorInstalled,
                "Cursor not installed");
        Assumptions.assumeTrue(ChatAdapterSupport.sqliteAvailable(),
                "sqlite-jdbc not available");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "cursor", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_continue_live() {
        Assumptions.assumeTrue(continueInstalled,
                "Continue not installed — ~/.continue missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "continue", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    @Test
    void harvestToolCalls_kompile_live() {
        Assumptions.assumeTrue(kompileInstalled,
                "Kompile not installed — ~/.kompile/conversations missing");

        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "kompile", Path.of(System.getProperty("user.dir")),
                Instant.now().minusSeconds(86400 * 30));
        assertNotNull(result);
    }

    // ========================================================================
    // Synthetic aider harvest test
    // ========================================================================

    @Test
    void harvestToolCalls_aider_synthetic() throws IOException {
        Path workDir = tempDir.resolve("aider-project");
        Files.createDirectories(workDir);

        Files.writeString(workDir.resolve(".aider.chat.history.md"), String.join("\n",
                "# aider chat started at 2025-06-01 10:00:00",
                "",
                "#### Fix the bug",
                "",
                "<<<<<<< SEARCH",
                "old",
                "=======",
                "new",
                ">>>>>>> REPLACE",
                "",
                "```bash",
                "pytest",
                "```"
        ), StandardCharsets.UTF_8);

        // harvestToolCalls dispatches to harvestAider which checks workDir
        // This tests the full dispatch path
        Map<String, Integer> result = SessionHarvester.harvestToolCalls(
                "aider", workDir, Instant.now().minusSeconds(86400));
        assertNotNull(result);
        assertFalse(result.isEmpty(), "Should extract edit_file and shell_command");
        assertTrue(result.containsKey("edit_file"));
        assertTrue(result.containsKey("shell_command"));
    }

    // ========================================================================
    // Synthetic kompile harvest test
    // ========================================================================

    @Test
    void harvestToolCalls_kompile_synthetic() throws IOException {
        // Test ToolCallExtractor.extractKompile directly with a synthetic transcript
        Path convDir = tempDir.resolve("conversations");
        Files.createDirectories(convDir);

        Path transcript = convDir.resolve("test-session.txt");
        Files.writeString(transcript, String.join("\n",
                "Started: 2025-06-01 10:00:00",
                "Agent: claude",
                "CWD: /tmp/project",
                "",
                "> Do something",
                "",
                "[tool:Read]",
                "[tool:Edit]",
                "Done."
        ), StandardCharsets.UTF_8);

        var extraction = ai.kompile.cli.common.chat.sources.ToolCallExtractor
                .extractKompile(transcript, "test-session");
        assertFalse(extraction.toolCalls().isEmpty(),
                "Should find tool calls in kompile transcript");
        assertEquals(2, extraction.toolCalls().size());
        assertEquals("Read", extraction.toolCalls().get(0).toolName());
        assertEquals("Edit", extraction.toolCalls().get(1).toolName());
        assertEquals("/tmp/project", extraction.projectDirectory());
    }
}
