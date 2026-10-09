package ai.kompile.cli.main.chat.enforcer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShellMandateHookTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"cd project && sed -n 1p input.txt", "cat input.txt", "printf MATCH | cat",
            "cd project && grep MATCH input.txt", "cd project; rg MATCH", "(cd project && cat)"})
    void nativeBashIsDeniedBeforeExecution(String command) throws Exception {
        var event = mapper.createObjectNode().put("tool_name", "Bash");
        event.putObject("tool_input").put("command", command);
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(event.toString(), error));
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("kompile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "until test -f ready; do :; done",
            "until ! kill -0 $pid 2>/dev/null; do :; done",
            "until false; do sleep 1; done",
            "bash -c 'until false; do :; done'"
    })
    void nativeUntilLoopsAreDeniedWithMonitorGuidance(String command) throws Exception {
        var event = mapper.createObjectNode().put("tool_name", "Bash");
        event.putObject("tool_input").put("command", command);
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(event.toString(), error));
        String message = error.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("until"), message);
        assertTrue(message.contains("action=monitor"), message);
    }

    @ParameterizedTest
    @CsvSource({"Write,file_path", "Edit,file_path", "MultiEdit,file_path",
            "NotebookEdit,notebook_path", "mcp__kompile__write,file_path", "mcp__kompile__edit,path"})
    void nativeMemoryMutationsAreDeniedWithMemoryToolGuidance(String tool, String field) {
        var event = mapper.createObjectNode().put("tool_name", tool);
        event.putObject("tool_input").put(field,
                "/home/user/.claude/projects/-repo/memory/feedback.md").put("content", "Remember this");
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(event.toString(), error));
        String message = error.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("memory"), message);
        assertTrue(message.contains("mcp__kompile__memory"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {".claude/memory/MEMORY.md", ".claude/projects/-repo/memory/feedback.md",
            "~/.claude/projects/-repo/memory/MEMORY.md", ".claude/MEMORY.md",
            ".claude/projects/-repo/MEMORY.md", ".claude/projects/-repo/./memory/subdir/../note.md",
            ".kompile/memory/feedback.md"})
    void claudeMemoryLocationsAreProtected(String target) {
        var event = mapper.createObjectNode().put("tool_name", "Write");
        event.putObject("tool_input").put("file_path", target).put("content", "Remember this");
        assertEquals(2, run(event.toString(), new ByteArrayOutputStream()));
    }

    @Test
    void hookUsesEventCwdAndResolvesSymlinkAliases(@TempDir Path cwd) throws Exception {
        Path memory = Files.createDirectories(cwd.resolve(".claude/projects/-repo/memory"));
        Files.createSymbolicLink(cwd.resolve("notes"), memory);
        Path existing = memory.resolve("feedback.md");
        Files.writeString(existing, "original");
        for (String target : new String[]{"notes/feedback.md", "notes/new.md"}) {
            var event = mapper.createObjectNode().put("tool_name", "Edit").put("cwd", cwd.toString());
            event.putObject("tool_input").put("file_path", target).put("new_string", "overwrite");
            assertEquals(2, run(event.toString(), new ByteArrayOutputStream()));
        }
        assertEquals("original", Files.readString(existing));
        assertFalse(Files.exists(memory.resolve("new.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {".claude/settings.json", ".claude/CLAUDE.md", ".claude/skills/review/SKILL.md",
            "src/memory/MEMORY.md", "docs/MEMORY.md", ".claude/projects/-repo/memory-backup.md"})
    void ordinaryFilesAndClaudeConfigurationRemainAllowed(String target) {
        var event = mapper.createObjectNode().put("tool_name", "Write");
        event.putObject("tool_input").put("file_path", target)
                .put("content", "Documentation mentioning .claude/projects/-repo/memory/MEMORY.md");
        assertEquals(0, run(event.toString(), new ByteArrayOutputStream()));
    }

    @Test
    void memoryToolAndReadOnlyMemoryAccessRemainAllowed() {
        for (String tool : new String[]{"Read", "mcp__kompile__read", "mcp__kompile__memory"}) {
            var event = mapper.createObjectNode().put("tool_name", tool);
            event.putObject("tool_input").put("file_path", ".claude/projects/-repo/memory/MEMORY.md")
                    .put("action", "save").put("content", "Remember this");
            assertEquals(0, run(event.toString(), new ByteArrayOutputStream()));
        }
    }

    @Test
    void compliantBuildAndDedicatedToolsRemainAllowed() throws Exception {
        assertEquals(0, run("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"mvn test\"}}", new ByteArrayOutputStream()));
        assertEquals(0, run("{\"tool_name\":\"mcp__kompile__grep\",\"tool_input\":{\"pattern\":\"MATCH\"}}", new ByteArrayOutputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "output", "status", "stream", "kill", "monitor", "unmonitor", "monitors"})
    void processActionsThatRunNoCommandRemainAllowed(String action) {
        var event = mapper.createObjectNode().put("tool_name", "mcp__kompile__process");
        event.putObject("tool_input").put("action", action).put("process_id", "proc-1");
        assertEquals(0, run(event.toString(), new ByteArrayOutputStream()));
    }

    @Test
    void processLaunchIsStillCheckedAndFailsClosedWithoutACommand() {
        var denied = mapper.createObjectNode().put("tool_name", "mcp__kompile__process");
        denied.putObject("tool_input").put("action", "launch").put("command", "cat input.txt");
        var error = new ByteArrayOutputStream();
        assertEquals(2, run(denied.toString(), error));
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("kompile"));

        var build = mapper.createObjectNode().put("tool_name", "mcp__kompile__process");
        build.putObject("tool_input").put("action", "launch").put("command", "mvn test");
        assertEquals(0, run(build.toString(), new ByteArrayOutputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"tool_name\":\"Bash\",\"tool_input\":{\"action\":\"output\"}}",
            "{\"tool_name\":\"mcp__kompile__process\",\"tool_input\":{\"action\":\"launch\"}}",
            "{\"tool_name\":\"mcp__kompile__process\",\"tool_input\":{\"process_id\":\"proc-1\"}}",
            "{\"tool_name\":\"mcp__kompile__process\",\"tool_input\":{\"action\":\" \"}}",
            "{\"tool_name\":\"mcp__kompile__process\",\"tool_input\":[]}"})
    void commandlessShellCallsStillFailClosed(String event) {
        assertEquals(2, run(event, new ByteArrayOutputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "null", "{}", "{\"tool_name\":\"Bash\",\"tool_input\":{}}",
            "{\"tool_name\":\"Write\",\"tool_input\":[]}"})
    void unreadableHookEventsFailClosed(String event) {
        assertEquals(2, run(event, new ByteArrayOutputStream()));
    }

    private int run(String event, ByteArrayOutputStream error) {
        return ShellMandateHook.run(new ByteArrayInputStream(event.getBytes(StandardCharsets.UTF_8)), new PrintStream(error));
    }
}
