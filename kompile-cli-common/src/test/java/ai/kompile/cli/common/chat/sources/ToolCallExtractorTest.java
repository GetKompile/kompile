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

package ai.kompile.cli.common.chat.sources;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallExtractorTest {

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
        clineInstalled = isClineInstalled(home);
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

    private static boolean isClineInstalled(Path home) {
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
    // Claude extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractClaude_parsesToolUseBlocks() throws IOException {
        Path file = tempDir.resolve("claude-session.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"summary\",\"cwd\":\"/home/user/project\"}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[" +
                        "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/tmp/test.txt\"}}," +
                        "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}" +
                        "]}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[" +
                        "{\"type\":\"tool_use\",\"name\":\"Agent\",\"input\":{\"subagent_type\":\"Explore\",\"prompt\":\"find files\"}}" +
                        "]}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractClaude(file, "test-session");

        assertEquals("test-session", result.sessionId());
        assertEquals("claude-code", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        assertEquals(3, result.toolCalls().size());

        assertEquals("Read", result.toolCalls().get(0).toolName());
        assertFalse(result.toolCalls().get(0).isError());
        assertEquals("/home/user/project", result.toolCalls().get(0).projectDirectory());

        assertEquals("Bash", result.toolCalls().get(1).toolName());

        // Agent tool should be renamed to Agent:<subagent_type>
        assertEquals("Agent:Explore", result.toolCalls().get(2).toolName());
    }

    @Test
    void extractClaude_emptyFile() throws IOException {
        Path file = tempDir.resolve("empty.jsonl");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractClaude(file, "empty");
        assertEquals(0, result.toolCalls().size());
        assertNull(result.projectDirectory());
    }

    @Test
    void extractClaude_skipsNonAssistantEntries() throws IOException {
        Path file = tempDir.resolve("mixed.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}",
                "{\"type\":\"result\",\"message\":{\"role\":\"tool\",\"content\":\"output\"}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractClaude(file, "mixed");
        assertEquals(0, result.toolCalls().size());
    }

    // ── Claude live session test ──────────────────────────────────────────

    @Test
    void extractClaude_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(claudeInstalled,
                "Claude Code not installed — ~/.claude/projects missing");

        Path projectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        Path sessionFile = findAnyJsonl(projectsDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                "No Claude Code session files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractClaude(sessionFile, "live-claude");
        assertNotNull(result);
        assertEquals("claude-code", result.source());
        // A real session should have at least some tool calls
        assertNotNull(result.toolCalls());
    }

    // ========================================================================
    // Pi extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractPi_parsesSessionHeaderAndToolUse() throws IOException {
        Path file = tempDir.resolve("pi-session.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"session\",\"version\":3,\"id\":\"pi-test\",\"cwd\":\"/home/user/project\"}",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"read a file\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[" +
                        "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"/tmp/f.txt\"}}," +
                        "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"cat /tmp/f.txt\"}}" +
                        "]}}",
                "{\"type\":\"toolResult\",\"message\":{\"role\":\"tool\",\"name\":\"Read\",\"isError\":false,\"content\":\"file contents\"}}",
                "{\"type\":\"toolResult\",\"message\":{\"role\":\"tool\",\"name\":\"BadTool\",\"isError\":true,\"content\":\"something failed\"}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractPi(file, "pi-test");

        assertEquals("pi-test", result.sessionId());
        assertEquals("pi", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        // 2 tool_use calls + 1 error toolResult = 3
        assertEquals(3, result.toolCalls().size());

        assertEquals("Read", result.toolCalls().get(0).toolName());
        assertFalse(result.toolCalls().get(0).isError());
        assertEquals("/home/user/project", result.toolCalls().get(0).projectDirectory());

        assertEquals("Bash", result.toolCalls().get(1).toolName());

        assertEquals("BadTool", result.toolCalls().get(2).toolName());
        assertTrue(result.toolCalls().get(2).isError());
    }

    @Test
    void extractPi_noCwd() throws IOException {
        Path file = tempDir.resolve("pi-nocwd.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"session\",\"version\":3,\"id\":\"pi-nocwd\",\"cwd\":\"\"}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[" +
                        "{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{\"pattern\":\"TODO\"}}" +
                        "]}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractPi(file, "pi-nocwd");
        assertNull(result.projectDirectory());
        assertEquals(1, result.toolCalls().size());
        assertNull(result.toolCalls().get(0).projectDirectory());
    }

    @Test
    void extractPi_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(piInstalled,
                "Pi not installed — ~/.pi/agent/sessions missing");

        Path sessionsDir = Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions");
        Path sessionFile = findAnyJsonl(sessionsDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                "No Pi session files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractPi(sessionFile, "live-pi");
        assertNotNull(result);
        assertEquals("pi", result.source());
        assertNotNull(result.toolCalls());
    }

    // ========================================================================
    // Codex extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractCodex_parsesFunctionCalls() throws IOException {
        Path file = tempDir.resolve("codex-session.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"session_meta\",\"payload\":{\"cwd\":\"/home/user/project\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"read_file\",\"arguments\":{\"path\":\"/tmp/test\"}}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"name\":\"shell\",\"arguments\":{\"cmd\":\"ls\"},\"status\":\"success\"}}",
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"name\":\"bad_tool\",\"arguments\":{},\"status\":\"error\"}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCodex(file, "codex-test");

        assertEquals("codex", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        assertEquals(3, result.toolCalls().size());

        assertEquals("read_file", result.toolCalls().get(0).toolName());
        assertFalse(result.toolCalls().get(0).isError());

        assertEquals("shell", result.toolCalls().get(1).toolName());
        assertFalse(result.toolCalls().get(1).isError());

        assertEquals("bad_tool", result.toolCalls().get(2).toolName());
        assertTrue(result.toolCalls().get(2).isError());
    }

    @Test
    void extractCodex_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(codexInstalled,
                "Codex not installed — ~/.codex/sessions missing");

        Path sessionsDir = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        Path sessionFile = findAnyJsonl(sessionsDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                "No Codex session files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCodex(sessionFile, "live-codex");
        assertNotNull(result);
        assertEquals("codex", result.source());
    }

    // ========================================================================
    // Qwen extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractQwen_parsesFunctionCalls() throws IOException {
        Path file = tempDir.resolve("qwen-session.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"type\":\"meta\",\"cwd\":\"/home/user/project\"}",
                "{\"type\":\"assistant\",\"message\":{\"parts\":[" +
                        "{\"thought\":true,\"text\":\"Let me think...\"}," +
                        "{\"functionCall\":{\"name\":\"readFile\",\"args\":{\"path\":\"/tmp/file\"}}}" +
                        "]}}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractQwen(file, "qwen-test");

        assertEquals("qwen", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        assertEquals(1, result.toolCalls().size());
        assertEquals("readFile", result.toolCalls().get(0).toolName());
        // Thought entries should be skipped
    }

    @Test
    void extractQwen_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(qwenInstalled,
                "Qwen not installed — ~/.qwen/projects missing");

        Path projectsDir = Path.of(System.getProperty("user.home"), ".qwen", "projects");
        Path sessionFile = findAnyJsonl(projectsDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                "No Qwen session files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractQwen(sessionFile, "live-qwen");
        assertNotNull(result);
        assertEquals("qwen", result.source());
    }

    // ========================================================================
    // Gemini extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractGemini_parsesJsonFile() throws IOException {
        Path file = tempDir.resolve("gemini-session.json");
        Files.writeString(file, "{\"workingDirectory\":\"/home/user/project\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hello\"}," +
                "{\"role\":\"tool\",\"name\":\"read_file\",\"input\":{\"path\":\"/tmp/f\"},\"error\":false}," +
                "{\"role\":\"tool\",\"name\":\"bad_tool\",\"input\":{},\"error\":true}," +
                "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"write_file\",\"args\":{\"path\":\"/tmp/out\"}}}]}" +
                "]}", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractGemini(file, "gemini-test");

        assertEquals("gemini", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        assertEquals(3, result.toolCalls().size());

        assertEquals("read_file", result.toolCalls().get(0).toolName());
        assertFalse(result.toolCalls().get(0).isError());

        assertEquals("bad_tool", result.toolCalls().get(1).toolName());
        assertTrue(result.toolCalls().get(1).isError());

        assertEquals("write_file", result.toolCalls().get(2).toolName());
    }

    @Test
    void extractGemini_parsesJsonlFile() throws IOException {
        Path file = tempDir.resolve("gemini-session.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"role\":\"tool\",\"name\":\"search\",\"input\":{\"q\":\"test\"},\"error\":false}",
                "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"execute\",\"args\":{\"cmd\":\"ls\"}}}]}"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractGemini(file, "gemini-jsonl");

        assertEquals(2, result.toolCalls().size());
        assertEquals("search", result.toolCalls().get(0).toolName());
        assertEquals("execute", result.toolCalls().get(1).toolName());
    }

    @Test
    void extractGemini_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(geminiInstalled,
                "Gemini not installed — ~/.gemini missing");

        Path geminiDir = Path.of(System.getProperty("user.home"), ".gemini");
        Path sessionFile = findAnySessionFile(geminiDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                "No Gemini session files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractGemini(sessionFile, "live-gemini");
        assertNotNull(result);
        assertEquals("gemini", result.source());
    }

    // ========================================================================
    // Aider extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractAider_parsesEditAndShellPatterns() throws IOException {
        Path file = tempDir.resolve(".aider.chat.history.md");
        Files.writeString(file, String.join("\n",
                "# aider chat started at 2025-06-01 10:00:00",
                "",
                "#### Fix the bug in main.py",
                "",
                "I'll fix the bug by editing the file:",
                "",
                "<<<<<<< SEARCH",
                "old code",
                "=======",
                "new code",
                ">>>>>>> REPLACE",
                "",
                "#### Run the tests",
                "",
                "Let me run the test suite:",
                "",
                "```bash",
                "python -m pytest",
                "```",
                "",
                "+++ b/newfile.py",
                "print('hello')"
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractAider(file, "aider-test");

        assertEquals("aider", result.source());
        assertNotNull(result.projectDirectory());
        // edit_file (SEARCH/REPLACE), shell_command (```bash), write_file (+++ b/)
        assertEquals(3, result.toolCalls().size());

        assertEquals("edit_file", result.toolCalls().get(0).toolName());
        assertEquals("shell_command", result.toolCalls().get(1).toolName());
        assertEquals("write_file", result.toolCalls().get(2).toolName());
        assertTrue(result.toolCalls().get(2).toolInput().contains("newfile.py"));
    }

    @Test
    void extractAider_emptyFile() throws IOException {
        Path file = tempDir.resolve("empty.md");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractAider(file, "empty-aider");
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    void extractAider_noToolPatterns() throws IOException {
        Path file = tempDir.resolve("notools.md");
        Files.writeString(file, String.join("\n",
                "# aider chat started at 2025-06-01 10:00:00",
                "",
                "#### What is Python?",
                "",
                "Python is a programming language."
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractAider(file, "no-tools");
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    void extractAider_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(aiderInstalled,
                "Aider not installed");

        // Check for history in cwd or home
        Path cwd = Path.of(System.getProperty("user.dir"), ".aider.chat.history.md");
        Path home = Path.of(System.getProperty("user.home"), ".aider.chat.history.md");
        Path historyFile = Files.exists(cwd) ? cwd : (Files.exists(home) ? home : null);
        org.junit.jupiter.api.Assumptions.assumeTrue(historyFile != null,
                "No .aider.chat.history.md found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractAider(historyFile, "live-aider");
        assertNotNull(result);
        assertEquals("aider", result.source());
    }

    // ========================================================================
    // Cline extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractCline_parsesToolUseBlocks() throws IOException {
        Path taskDir = tempDir.resolve("task-123");
        Files.createDirectories(taskDir);

        // Write task_metadata.json with cwd
        Files.writeString(taskDir.resolve("task_metadata.json"),
                "{\"cwd\":\"/home/user/project\",\"title\":\"Fix tests\"}",
                StandardCharsets.UTF_8);

        Path convFile = taskDir.resolve("api_conversation_history.json");
        Files.writeString(convFile, "[" +
                "{\"role\":\"user\",\"content\":\"fix the test\"}," +
                "{\"role\":\"assistant\",\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"I'll fix it.\"}," +
                    "{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"read_file\",\"input\":{\"path\":\"/tmp/test.py\"}}," +
                    "{\"type\":\"tool_use\",\"id\":\"tu2\",\"name\":\"write_to_file\",\"input\":{\"path\":\"/tmp/test.py\",\"content\":\"fixed\"}}" +
                "]}," +
                "{\"role\":\"user\",\"content\":[" +
                    "{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":\"file contents\",\"is_error\":false}," +
                    "{\"type\":\"tool_result\",\"tool_use_id\":\"tu2\",\"content\":\"error writing\",\"is_error\":true}" +
                "]}" +
                "]", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCline(convFile, "task-123");

        assertEquals("cline", result.source());
        assertEquals("/home/user/project", result.projectDirectory());
        // 2 tool_use + 1 error tool_result = 3
        assertEquals(3, result.toolCalls().size());

        assertEquals("read_file", result.toolCalls().get(0).toolName());
        assertFalse(result.toolCalls().get(0).isError());

        assertEquals("write_to_file", result.toolCalls().get(1).toolName());
        assertFalse(result.toolCalls().get(1).isError());

        // Error tool_result
        assertEquals("tu2", result.toolCalls().get(2).toolName());
        assertTrue(result.toolCalls().get(2).isError());
    }

    @Test
    void extractCline_noMetadata() throws IOException {
        Path taskDir = tempDir.resolve("task-no-meta");
        Files.createDirectories(taskDir);

        Path convFile = taskDir.resolve("api_conversation_history.json");
        Files.writeString(convFile, "[" +
                "{\"role\":\"assistant\",\"content\":[" +
                    "{\"type\":\"tool_use\",\"name\":\"execute_command\",\"input\":{\"command\":\"ls\"}}" +
                "]}" +
                "]", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCline(convFile, "no-meta");
        assertNull(result.projectDirectory());
        assertEquals(1, result.toolCalls().size());
        assertEquals("execute_command", result.toolCalls().get(0).toolName());
    }

    @Test
    void extractCline_emptyConversation() throws IOException {
        Path convFile = tempDir.resolve("api_conversation_history.json");
        Files.writeString(convFile, "[]", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCline(convFile, "empty-cline");
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    void extractCline_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(clineInstalled,
                "Cline not installed — no tasks directory found");

        Path config = ChatAdapterSupport.userConfigDir();
        Path convFile = findClineConversation(config);
        org.junit.jupiter.api.Assumptions.assumeTrue(convFile != null,
                "No Cline api_conversation_history.json found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCline(convFile, "live-cline");
        assertNotNull(result);
        assertEquals("cline", result.source());
    }

    // ========================================================================
    // Cursor extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractCursor_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(cursorInstalled,
                "Cursor not installed");
        org.junit.jupiter.api.Assumptions.assumeTrue(ChatAdapterSupport.sqliteAvailable(),
                "sqlite-jdbc not available");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractCursor("latest");
        assertNotNull(result);
        assertEquals("cursor", result.source());
        assertNotNull(result.toolCalls());
    }

    // ========================================================================
    // Continue extraction — synthetic fixture (JSON)
    // ========================================================================

    @Test
    void extractContinue_parsesToolCallsArray() throws IOException {
        Path file = tempDir.resolve("continue-session.json");
        Files.writeString(file, "{\"history\":[" +
                "{\"role\":\"user\",\"content\":\"fix tests\"}," +
                "{\"role\":\"assistant\",\"content\":\"I'll fix them.\"," +
                    "\"tool_calls\":[" +
                        "{\"id\":\"tc1\",\"type\":\"function\",\"function\":{\"name\":\"readFile\",\"arguments\":\"{\\\"path\\\":\\\"/tmp/t\\\"}\"}}," +
                        "{\"id\":\"tc2\",\"type\":\"function\",\"function\":{\"name\":\"runCommand\",\"arguments\":\"{\\\"cmd\\\":\\\"pytest\\\"}\"}}" +
                    "]" +
                "}" +
                "]}", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractContinue(file, "continue-test");

        assertEquals("continue", result.source());
        assertEquals(2, result.toolCalls().size());
        assertEquals("readFile", result.toolCalls().get(0).toolName());
        assertEquals("runCommand", result.toolCalls().get(1).toolName());
    }

    @Test
    void extractContinue_parsesContentBlocksWithToolUse() throws IOException {
        Path file = tempDir.resolve("continue-blocks.json");
        Files.writeString(file, "{\"messages\":[" +
                "{\"role\":\"assistant\",\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"Let me help.\"}," +
                    "{\"type\":\"tool_use\",\"name\":\"editFile\",\"input\":{\"path\":\"/tmp/f\",\"content\":\"new\"}}" +
                "]}" +
                "]}", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractContinue(file, "continue-blocks");

        assertEquals(1, result.toolCalls().size());
        assertEquals("editFile", result.toolCalls().get(0).toolName());
    }

    @Test
    void extractContinue_emptyHistory() throws IOException {
        Path file = tempDir.resolve("continue-empty.json");
        Files.writeString(file, "{\"history\":[]}", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractContinue(file, "empty");
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    void extractContinue_rootArrayFormat() throws IOException {
        Path file = tempDir.resolve("continue-array.json");
        Files.writeString(file, "[" +
                "{\"role\":\"assistant\",\"tool_calls\":[" +
                    "{\"function\":{\"name\":\"grep\",\"arguments\":\"{}\"}}" +
                "]}" +
                "]", StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractContinue(file, "array-format");

        assertEquals(1, result.toolCalls().size());
        assertEquals("grep", result.toolCalls().get(0).toolName());
    }

    @Test
    void extractContinue_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(continueInstalled,
                "Continue not installed — ~/.continue missing");

        Path sessionsDir = Path.of(System.getProperty("user.home"), ".continue", "sessions");
        if (Files.isDirectory(sessionsDir)) {
            Path sessionFile = findAnyJsonFile(sessionsDir);
            org.junit.jupiter.api.Assumptions.assumeTrue(sessionFile != null,
                    "No Continue session files found");

            ToolCallExtractor.ExtractionResult result =
                    ToolCallExtractor.extractContinue(sessionFile, "live-continue");
            assertNotNull(result);
            assertEquals("continue", result.source());
        }
    }

    // ========================================================================
    // Kompile extraction — synthetic fixture
    // ========================================================================

    @Test
    void extractKompile_parsesToolAndSubagentLines() throws IOException {
        Path file = tempDir.resolve("kompile-session.txt");
        Files.writeString(file, String.join("\n",
                "Started: 2025-06-01 10:00:00",
                "Agent: claude",
                "CWD: /home/user/project",
                "",
                "> Fix the bug",
                "",
                "Let me look at the code.",
                "[tool:Read /tmp/main.py]",
                "[tool:Edit]",
                "[subagent:code-reviewer]",
                "I've fixed it.",
                "",
                "> Run tests",
                "",
                "[tool:Bash]",
                "[agentic-step]",
                "[system] step completed",
                "Tests passed."
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractKompile(file, "kompile-test");

        assertEquals("kompile", result.source());
        assertEquals("/home/user/project", result.projectDirectory());

        // Read, Edit, subagent:code-reviewer, Bash = 4 tool calls
        assertEquals(4, result.toolCalls().size());

        assertEquals("Read", result.toolCalls().get(0).toolName());
        assertEquals("/tmp/main.py", result.toolCalls().get(0).toolInput());

        assertEquals("Edit", result.toolCalls().get(1).toolName());
        assertEquals("{}", result.toolCalls().get(1).toolInput());

        assertEquals("subagent:code-reviewer", result.toolCalls().get(2).toolName());

        assertEquals("Bash", result.toolCalls().get(3).toolName());
    }

    @Test
    void extractKompile_noCwd() throws IOException {
        Path file = tempDir.resolve("kompile-nocwd.txt");
        Files.writeString(file, String.join("\n",
                "Started: 2025-06-01 10:00:00",
                "Agent: claude",
                "",
                "> Hello",
                "",
                "[tool:Grep]",
                "Found something."
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractKompile(file, "nocwd");
        assertNull(result.projectDirectory());
        assertEquals(1, result.toolCalls().size());
        assertEquals("Grep", result.toolCalls().get(0).toolName());
    }

    @Test
    void extractKompile_emptyTranscript() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, String.join("\n",
                "Started: 2025-06-01 10:00:00",
                "Agent: claude",
                ""
        ), StandardCharsets.UTF_8);

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractKompile(file, "empty-kompile");
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    void extractKompile_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(kompileInstalled,
                "Kompile not installed — ~/.kompile/conversations missing");

        Path convDir = Path.of(System.getProperty("user.home"), ".kompile", "conversations");
        Path txtFile = findAnyTxtFile(convDir);
        org.junit.jupiter.api.Assumptions.assumeTrue(txtFile != null,
                "No Kompile transcript files found");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractKompile(txtFile, "live-kompile");
        assertNotNull(result);
        assertEquals("kompile", result.source());
    }

    // ========================================================================
    // OpenCode extraction — live only (requires SQLite)
    // ========================================================================

    @Test
    void extractOpenCode_liveSession() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(openCodeInstalled,
                "OpenCode not installed — no opencode.db found");
        org.junit.jupiter.api.Assumptions.assumeTrue(ChatAdapterSupport.sqliteAvailable(),
                "sqlite-jdbc not available");

        ToolCallExtractor.ExtractionResult result =
                ToolCallExtractor.extractOpenCode("latest");
        assertNotNull(result);
        assertEquals("opencode", result.source());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Path findAnyJsonl(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.walk(dir, 4)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path findAnyJsonFile(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path findAnyTxtFile(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path findAnySessionFile(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.walk(dir, 3)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".json") || name.endsWith(".jsonl");
                    })
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path findClineConversation(Path configRoot) {
        String[] variants = {"Code", "Code - Insiders"};
        String[] exts = {"saoudrizwan.claude-dev", "RooVeterinaryInc.roo-cline"};
        for (String variant : variants) {
            for (String ext : exts) {
                Path tasks = configRoot.resolve(variant).resolve("User")
                        .resolve("globalStorage").resolve(ext).resolve("tasks");
                if (!Files.isDirectory(tasks)) continue;
                try (var stream = Files.list(tasks)) {
                    Path taskDir = stream.filter(Files::isDirectory)
                            .filter(d -> Files.exists(d.resolve("api_conversation_history.json")))
                            .findFirst().orElse(null);
                    if (taskDir != null) {
                        return taskDir.resolve("api_conversation_history.json");
                    }
                } catch (IOException e) {
                    continue;
                }
            }
        }
        return null;
    }
}
