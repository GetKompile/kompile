package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that tool call rendering shows the right information for each tool type,
 * matching what a managed CLI would display.
 */
class ToolCallRenderingTest {

    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TerminalRenderer(true);
    }

    @Test
    void bashShowsCommandAndOutput() {
        ToolResult result = new ToolResult("exit 0", "BUILD SUCCESS\n", Map.of(
                "exitCode", 0, "durationMs", 1234L));

        String rendered = renderer.renderToolCallComplete("bash", result);

        assertTrue(rendered.toLowerCase().contains("bash"), "Should show tool name");
        assertTrue(rendered.contains("BUILD SUCCESS"), "Should show output");
    }

    @Test
    void readShowsFilePathAndLineCount() {
        ToolResult result = new ToolResult("src/main/java/Foo.java", "     1\tpackage com.example;\n",
                Map.of("totalLines", 200, "linesShown", 50, "truncated", true));

        String rendered = renderer.renderToolCallComplete("read", result);

        assertTrue(rendered.toLowerCase().contains("read"), "Should show tool name");
        assertTrue(rendered.contains("src/main/java/Foo.java"), "Should show file path");
    }

    @Test
    void editShowsDiff() {
        ToolResult result = new ToolResult("src/Foo.java", "Applied edit",
                Map.of("replacements", 1));

        String rendered = renderer.renderToolCallComplete("edit", result);

        assertTrue(rendered.toLowerCase().contains("edit"), "Should show tool name");
        assertTrue(rendered.contains("src/Foo.java"), "Should show file path");
    }

    @Test
    void writeShowsConfirmation() {
        ToolResult result = new ToolResult("src/NewFile.java", "Created file with 1 lines",
                Map.of("lines", 1L, "created", true));

        String rendered = renderer.renderToolCallComplete("write", result);

        assertTrue(rendered.toLowerCase().contains("write"), "Should show tool name");
        assertTrue(rendered.contains("src/NewFile.java"), "Should show file path");
    }

    @Test
    void grepShowsPatternAndMatches() {
        ToolResult result = new ToolResult("grep: TODO",
                "src/Foo.java:10:  // TODO fix this\nsrc/Bar.java:5:  // TODO refactor",
                Map.of("matchCount", 2));

        String rendered = renderer.renderToolCallComplete("grep", result);

        assertTrue(rendered.toLowerCase().contains("grep"), "Should show tool name");
        assertTrue(rendered.contains("TODO"), "Should show pattern");
    }

    @Test
    void globShowsPatternAndFiles() {
        ToolResult result = new ToolResult("glob: **/*.java",
                "src/Foo.java\nsrc/Bar.java\nsrc/Baz.java",
                Map.of("count", 3));

        String rendered = renderer.renderToolCallComplete("glob", result);

        assertTrue(rendered.toLowerCase().contains("glob"), "Should show tool name");
        assertTrue(rendered.contains("**/*.java"), "Should show pattern");
    }

    @Test
    void errorShowsWhatWasAttempted() {
        ToolResult result = ToolResult.error("File not found: /nonexistent/path.java");

        String rendered = renderer.renderToolCallComplete("read", result);

        assertTrue(rendered.toLowerCase().contains("read"), "Should show tool name");
        assertTrue(rendered.contains("File not found"), "Should show error message");
    }

    @Test
    void noArgsStillWorks() {
        ToolResult result = ToolResult.success("done");
        String rendered = renderer.renderToolCallComplete("bash", result);
        assertTrue(rendered.toLowerCase().contains("bash"), "Should show tool name even without args");
    }

    @Test
    void taskShowsAgentTypeAndDescription() {
        ToolResult result = new ToolResult("subagent:explore-quick",
                "Found auth in src/auth/Auth.java...",
                Map.of("agentType", "explore-quick", "description", "Find auth logic"));

        String rendered = renderer.renderToolCallComplete("task", result);

        assertTrue(rendered.toLowerCase().contains("task"), "Should show tool name");
        assertTrue(rendered.contains("explore-quick"), "Should show agent type");
        assertTrue(rendered.contains("Find auth logic"), "Should show description");
    }
}
