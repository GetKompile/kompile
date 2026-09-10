package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.jline.terminal.impl.LineDisciplineTerminal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TerminalRendererTest {

    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        // Disable ANSI for predictable test output
        renderer = new TerminalRenderer(false);
    }

    @Test
    void terminalTitleAnimatesWhileBusyAndStopsAtReady() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "title-test", "xterm", output, StandardCharsets.UTF_8);
        TerminalTitleController controller = new TerminalTitleController();
        try {
            controller.attach(terminal, "kompile chat — coder");
            output.reset();

            controller.update(ChatActivityPhase.WORKING, "read src/Main.java");
            String initial = output.toString(StandardCharsets.UTF_8);
            assertTrue(initial.contains(
                    "\033]2;⠋ kompile chat — coder · ⚙ Working · read src/Main.java\007"),
                    "main tool use must carry the fixed tool icon, not just the spinner");

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!output.toString(StandardCharsets.UTF_8).contains("\033]2;⠙ ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\033]2;⠙ "),
                    "busy title should advance its spinner frame");

            controller.update(ChatActivityPhase.READY, "");
            String ready = output.toString(StandardCharsets.UTF_8);
            assertTrue(ready.endsWith("\033]2;kompile chat — coder\007"));
            int readyLength = output.size();
            Thread.sleep(220);
            assertEquals(readyLength, output.size(),
                    "no busy frames may be written after work completes");
        } finally {
            controller.detach();
            terminal.close();
        }
    }

    @Test
    void terminalTitleRemovesBusyMarkerForTerminalStatesAndDetach() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "title-terminal-state-test", "xterm", output, StandardCharsets.UTF_8);
        TerminalTitleController controller = new TerminalTitleController();
        try {
            controller.attach(terminal, "kompile chat (local)");
            controller.setReadyTitle("kompile chat (local) — gpt-5");
            output.reset();
            controller.update(ChatActivityPhase.THINKING, "");
            controller.update(ChatActivityPhase.INTERRUPTED, "");
            String interrupted = output.toString(StandardCharsets.UTF_8);
            assertTrue(interrupted.endsWith(
                    "\033]2;kompile chat (local) — gpt-5 · Interrupted\007"));

            output.reset();
            controller.detach();
            assertTrue(output.toString(StandardCharsets.UTF_8)
                    .endsWith("\033]2;kompile chat (local) — gpt-5\007"),
                    "detach must restore the ready title even during teardown");
        } finally {
            terminal.close();
        }
    }

    @Test
    void terminalTitleShowsProcessOverlayWhenIdleAndClearsAtReady() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "title-process-test", "xterm", output, StandardCharsets.UTF_8);
        TerminalTitleController controller = new TerminalTitleController();
        try {
            controller.attach(terminal, "kompile chat — coder");
            output.reset();

            controller.updateProcessActivity(2);
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith(
                    "\033]2;▶ kompile chat — coder · 2 processes\007"),
                    "running background processes must show in the tab title");

            output.reset();
            controller.updateProcessActivity(1);
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith(
                    "\033]2;▶ kompile chat — coder · 1 process\007"));

            output.reset();
            controller.updateProcessActivity(0);
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith(
                    "\033]2;kompile chat — coder\007"),
                    "zero processes must restore the plain ready title");

            // Foreground work owns the title; the overlay must not fight the busy animation.
            output.reset();
            controller.updateProcessActivity(3);
            output.reset(); // drop the idle overlay frame; only the busy frames remain
            controller.update(ChatActivityPhase.THINKING, "");
            String busy = output.toString(StandardCharsets.UTF_8);
            assertFalse(busy.contains("▶ "),
                    "busy foreground work must suppress the process overlay");

            // When the turn ends the overlay reapplies over the ready title.
            controller.update(ChatActivityPhase.READY, "");
            assertTrue(output.toString(StandardCharsets.UTF_8).endsWith(
                    "\033]2;▶ kompile chat — coder · 3 processes\007"),
                    "finished foreground work must hand the title back to the overlay");
        } finally {
            controller.detach();
            terminal.close();
        }
    }

    // ========================================================================
    // Todo list rendering
    // ========================================================================

    @Test
    void testRenderEmptyTodoList() {
        String output = renderer.renderTodoList(List.of());
        assertTrue(output.contains("No tasks"));
    }

    @Test
    void testRenderTodoListWithItems() {
        List<TodoWriteTool.TodoItem> todos = new ArrayList<>();
        todos.add(new TodoWriteTool.TodoItem("1", "First task", "", "pending", "medium"));
        todos.add(new TodoWriteTool.TodoItem("2", "Second task", "Details", "in_progress", "high"));
        todos.add(new TodoWriteTool.TodoItem("3", "Third task", "", "completed", "medium"));

        String output = renderer.renderTodoList(todos);

        assertTrue(output.contains("Tasks"));
        assertTrue(output.contains("1/3")); // 1 completed out of 3
        assertTrue(output.contains("First task"));
        assertTrue(output.contains("Second task"));
        assertTrue(output.contains("Third task"));
        assertTrue(output.contains("Details")); // description
    }

    @Test
    void testRenderTodoListProgressBar() {
        List<TodoWriteTool.TodoItem> todos = new ArrayList<>();
        todos.add(new TodoWriteTool.TodoItem("1", "Done", "", "completed", "medium"));
        todos.add(new TodoWriteTool.TodoItem("2", "Also done", "", "completed", "medium"));
        todos.add(new TodoWriteTool.TodoItem("3", "Not done", "", "pending", "medium"));
        todos.add(new TodoWriteTool.TodoItem("4", "Not done either", "", "pending", "medium"));

        String output = renderer.renderTodoList(todos);

        // Should show 2/4 completion
        assertTrue(output.contains("2/4"));
    }

    @Test
    void testRenderTodoItemPending() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "1", "Pending task", "", "pending", "medium");

        String output = renderer.renderTodoItem(item);

        assertTrue(output.contains("Pending task"));
        assertTrue(output.contains("#1"));
    }

    @Test
    void testRenderTodoItemInProgress() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "2", "Working on it", "", "in_progress", "medium");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("Working on it"));
    }

    @Test
    void testRenderTodoItemCompleted() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "3", "All done", "", "completed", "medium");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("All done"));
    }

    @Test
    void testRenderTodoItemCancelled() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "4", "Cancelled task", "", "cancelled", "medium");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("Cancelled task"));
    }

    @Test
    void testRenderTodoItemHighPriority() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "1", "Urgent fix", "", "pending", "high");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("Urgent fix"));
        assertTrue(output.contains("high"));
    }

    @Test
    void testRenderTodoItemLowPriority() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "1", "Minor cleanup", "", "pending", "low");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("low"));
    }

    @Test
    void testRenderTodoItemMediumPriorityNotShown() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "1", "Normal task", "", "pending", "medium");

        String output = renderer.renderTodoItem(item);
        // Medium priority is the default and should not be shown
        assertFalse(output.contains("medium"));
    }

    @Test
    void testRenderTodoItemWithDescription() {
        TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                "1", "Task with desc", "Some details about the task", "pending", "medium");

        String output = renderer.renderTodoItem(item);
        assertTrue(output.contains("Some details about the task"));
    }

    @Test
    void testRenderTodoUpdate() {
        String output = renderer.renderTodoUpdate("1", "My task", "pending", "in_progress");
        assertTrue(output.contains("#1"));
        assertTrue(output.contains("My task"));
        assertTrue(output.contains("pending → in_progress"));
    }

    @Test
    void testRenderTodoUpdateCompleted() {
        String output = renderer.renderTodoUpdate("3", "Done task", "in_progress", "completed");
        assertTrue(output.contains("in_progress → completed"));
    }

    // ========================================================================
    // Tool call rendering
    // ========================================================================

    @Test
    void testRenderToolCallStart() {
        String output = renderer.renderToolCallStart("todowrite", "Add new task");
        assertTrue(output.contains("Todowrite"));
        assertTrue(output.contains("Add new task"));
    }

    @Test
    void testRenderToolCallStartExitPlanMode() {
        String output = renderer.renderToolCallStart("exit_plan_mode", "Plan ready");
        assertTrue(output.contains("Exit Plan Mode"));
    }

    @Test
    void testRenderToolCallStartMcpPrefix() {
        // MCP-prefixed tool names should be cleaned up for display
        String output = renderer.renderToolCallStart("mcp__kompile__read",
                "{\"file_path\":\"src/app/foo.ts\"}");
        assertTrue(output.contains("Read"), "Should display 'Read' not 'mcp__kompile__read'");
        assertTrue(output.contains("src/app/foo.ts"), "Should extract file_path from JSON");
        assertFalse(output.contains("mcp__"), "Should not contain MCP prefix");
        assertFalse(output.contains("file_path"), "Should not show JSON key name");
    }

    @Test
    void testRenderToolCallStartGlobPattern() {
        String output = renderer.renderToolCallStart("mcp__kompile__glob",
                "{\"pattern\":\"**/*.java\"}");
        assertTrue(output.contains("Glob"));
        assertTrue(output.contains("**/*.java"));
    }

    @Test
    void testRenderToolCallComplete() {
        ToolResult result = ToolResult.success("Added task #1: Implement feature");
        String output = renderer.renderToolCallComplete("todowrite", result);
        assertTrue(output.contains("Todowrite"));
    }

    @Test
    void largeToolDetailIsBoundedForInteractiveRows() {
        StringBuilder largeOutput = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            largeOutput.append("line-").append(i).append('\n');
        }

        String rendered = renderer.renderToolCallComplete(
                "read", "{\"file_path\":\"large.txt\"}",
                ToolResult.success(largeOutput.toString()));

        assertTrue(rendered.contains("line-0"), "the interactive preview should retain its head");
        assertTrue(rendered.contains("tool detail truncated"),
                "large results should advertise that the inline preview was bounded");
        assertTrue(rendered.split("\\R", -1).length <= 52,
                "a single tool result must not consume the entire input viewport");
        assertFalse(rendered.contains("line-199"),
                "the full result belongs in the activity detail view, not the live row");
    }

    @Test
    void grepResultDetailHighlightsLinesViaEmbeddedFilenames() {
        TerminalRenderer ansiRenderer = new TerminalRenderer(true);
        String grepOutput = String.join("\n",
                "src/App.java:10:public class App {",
                "src/App.java:11:  int count = 1;",
                "README");

        // grep input has no file key — per-line inference must kick in.
        String detail = ansiRenderer.renderToolResultDetail("grep",
                "{\"pattern\":\"class\"}", ToolResult.success(grepOutput));

        assertTrue(detail.contains("↳ content:"), "content block label: " + detail);
        assertTrue(detail.contains("\033[1;34mpublic\033[0m"),
                "java match line keyword-styled: " + detail);
        assertFalse(detail.contains("\033[1;34mREADME"),
                "non-code line must stay unstyled: " + detail);
        assertTrue(AsciiRenderer.stripAnsi(detail).contains("src/App.java:10:public class App {"),
                "visible text preserved: " + detail);
    }

    @Test
    void readBatchResultDetailHighlightsEachFileSection() {
        TerminalRenderer ansiRenderer = new TerminalRenderer(true);
        String batchOutput = String.join("\n",
                "3/3 files read",
                "",
                "== src/App.java (2 lines)",
                "     1\tpublic class App {",
                "     2\t}",
                "",
                "== scripts/main.py (1 line)",
                "     1\tdef run():",
                "",
                "== notes.txt (1 line)",
                "     1\tmodule.py:8:def shouldStayPlain():");

        String detail = ansiRenderer.renderToolResultDetail("mcp__kompile__read_batch",
                "{\"files\":[\"src/App.java\",{\"file_path\":\"scripts/main.py\"},\"notes.txt\"]}",
                ToolResult.success(batchOutput));

        assertTrue(detail.contains("\033[1;34mpublic\033[0m"),
                "Java section should use its header path as the language hint: " + detail);
        assertTrue(detail.contains("\033[1;34mdef\033[0m"),
                "Python section should switch to its own header path: " + detail);
        int plainSection = detail.indexOf("== notes.txt");
        assertTrue(plainSection >= 0, "Plain-text section should remain visible: " + detail);
        assertFalse(detail.substring(plainSection).contains("\033[1;34mdef"),
                "An unrecognized section must neither inherit the prior hint nor infer one from its body: " + detail);
    }

    @Test
    void testRenderToolCallCompleteError() {
        ToolResult result = ToolResult.error("subject is required");
        String output = renderer.renderToolCallComplete("todowrite", result);
        assertTrue(output.contains("Todowrite"));
        assertTrue(output.contains("subject is required"));
    }

    @Test
    void testRenderToolCallDenied() {
        String output = renderer.renderToolCallDenied("bash", "Destructive command");
        assertTrue(output.contains("Bash"));
        assertTrue(output.contains("denied"));
    }

    // ========================================================================
    // Reminder section rendering
    // ========================================================================

    @Test
    void testRenderReminderSectionEmptyWhenAbsent() {
        assertEquals("", renderer.renderReminderSection(null));
        assertEquals("", renderer.renderReminderSection("   "));
    }

    @Test
    void testRenderReminderSectionShowsHeaderAndEachReminder() {
        String section = renderer.renderReminderSection(
                "The user configured these reminders. Apply them to this prompt:\n"
                        + "1. [project] Plan before making changes\n"
                        + "2. [session] Run focused tests");

        assertTrue(section.contains("REMINDERS APPLIED TO THIS PROMPT"));
        assertTrue(section.contains("1. [project] Plan before making changes"));
        assertTrue(section.contains("2. [session] Run focused tests"));
        assertFalse(section.contains("The user configured"),
                "the block narration duplicates the header and must be dropped");
    }

    // ========================================================================
    // Agent loop rendering
    // ========================================================================

    @Test
    void testRenderContextGroup() {
        Map<String, Integer> counts = Map.of("read", 3, "grep", 2);
        String output = renderer.renderContextGroup(counts);
        assertTrue(output.contains("5 calls"));
        assertTrue(output.contains("3 Read"));
        assertTrue(output.contains("2 Grep"));
    }

    @Test
    void renderContextGroupAggregatesNormalizedToolNames() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("read", 2);
        counts.put("mcp__kompile__read", 3);
        counts.put("Read", 1);

        String output = renderer.renderContextGroup(counts);

        assertTrue(output.contains("6 calls"));
        assertTrue(output.contains("6 Reads"));
        assertFalse(output.contains("Mcp"));
    }

    @Test
    void renderContextGroupRollsUpOverflowBuckets() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("read", 8);
        counts.put("grep", 7);
        counts.put("glob", 6);
        counts.put("list", 5);
        counts.put("webfetch", 4);
        counts.put("todoread", 3);

        String output = renderer.renderContextGroup(counts);

        assertTrue(output.contains("33 calls"));
        assertTrue(output.contains("8 Reads"));
        assertTrue(output.contains("7 Greps"));
        assertTrue(output.contains("6 Globs"));
        assertTrue(output.contains("5 Lists"));
        assertTrue(output.contains("+7 more across 2 tools"));
        assertFalse(output.contains("Webfetch"));
        assertFalse(output.contains("Todoread"));
    }

    @Test
    void testRenderCompactionNotice() {
        String output = renderer.renderCompactionNotice(50000, 10000);
        assertTrue(output.contains("50000"));
        assertTrue(output.contains("10000"));
    }

    // ========================================================================
    // ANSI formatting helpers (no-ANSI mode)
    // ========================================================================

    @Test
    void testPlainTextPassthrough() {
        // When ANSI is disabled, formatting helpers return plain text
        assertEquals("hello", renderer.bold("hello"));
        assertEquals("hello", renderer.dim("hello"));
        assertEquals("hello", renderer.red("hello"));
        assertEquals("hello", renderer.green("hello"));
        assertEquals("hello", renderer.yellow("hello"));
        assertEquals("hello", renderer.blue("hello"));
        assertEquals("hello", renderer.cyan("hello"));
        assertEquals("hello", renderer.magenta("hello"));
    }

    @Test
    void testAnsiEnabled() {
        TerminalRenderer ansiRenderer = new TerminalRenderer(true);
        // With ANSI enabled, output should contain escape codes
        String bold = ansiRenderer.bold("test");
        assertTrue(bold.contains("\033["));
        assertTrue(bold.contains("test"));
    }

    @Test
    void testTruncatePreview() {
        String longText = "a".repeat(300);
        String truncated = TerminalRenderer.truncatePreview(longText, 100);
        assertEquals(100, truncated.length());
        assertTrue(truncated.endsWith("..."));
    }

    @Test
    void testTruncatePreviewShortText() {
        String shortText = "hello";
        assertEquals("hello", TerminalRenderer.truncatePreview(shortText, 100));
    }

    @Test
    void testTruncatePreviewNull() {
        assertEquals("", TerminalRenderer.truncatePreview(null, 100));
    }

    // ========================================================================
    // Tool name & input prettification
    // ========================================================================

    @Test
    void testPrettifyToolNameMcpPrefix() {
        assertEquals("Read", TerminalRenderer.prettifyToolName("mcp__kompile__read"));
        assertEquals("Glob", TerminalRenderer.prettifyToolName("mcp__kompile__glob"));
        assertEquals("Grep", TerminalRenderer.prettifyToolName("mcp__kompile__grep"));
        assertEquals("Bash", TerminalRenderer.prettifyToolName("mcp__kompile__bash"));
        assertEquals("Edit", TerminalRenderer.prettifyToolName("mcp__kompile__edit"));
        assertEquals("Write", TerminalRenderer.prettifyToolName("mcp__kompile__write"));
    }

    @Test
    void testPrettifyToolNameUnderscores() {
        assertEquals("Code Search", TerminalRenderer.prettifyToolName("code_search"));
        assertEquals("Exit Plan Mode", TerminalRenderer.prettifyToolName("exit_plan_mode"));
        assertEquals("Edit Coordinator", TerminalRenderer.prettifyToolName("edit_coordinator"));
    }

    @Test
    void testPrettifyToolNameCamelCase() {
        assertEquals("ToolSearch", TerminalRenderer.prettifyToolName("ToolSearch"));
        assertEquals("AskUserQuestion", TerminalRenderer.prettifyToolName("AskUserQuestion"));
    }

    @Test
    void testPrettifyToolNameSimple() {
        assertEquals("Read", TerminalRenderer.prettifyToolName("read"));
        assertEquals("Exec", TerminalRenderer.prettifyToolName("exec"));
    }

    @Test
    void testStripMcpPrefix() {
        assertEquals("read", TerminalRenderer.stripMcpPrefix("mcp__kompile__read"));
        assertEquals("code_search", TerminalRenderer.stripMcpPrefix("mcp__kompile__code_search"));
        assertEquals("read", TerminalRenderer.stripMcpPrefix("Read"));
        assertEquals("toolsearch", TerminalRenderer.stripMcpPrefix("ToolSearch"));
    }

    @Test
    void testPrettifyToolInputJsonFilePath() {
        String input = "{\"file_path\":\"src/app/foo.ts\",\"limit\":100}";
        String result = TerminalRenderer.prettifyToolInput("read", input, 80);
        assertEquals("src/app/foo.ts", result);
    }

    @Test
    void testPrettifyToolInputJsonPattern() {
        String input = "{\"pattern\":\"**/*.java\"}";
        String result = TerminalRenderer.prettifyToolInput("glob", input, 80);
        assertEquals("**/*.java", result);
    }

    @Test
    void testPrettifyToolInputJsonGrepWithPath() {
        String input = "{\"pattern\":\"renderTool\",\"path\":\"src/\"}";
        String result = TerminalRenderer.prettifyToolInput("grep", input, 80);
        assertEquals("renderTool in src/", result);
    }

    @Test
    void testPrettifyToolInputPlainText() {
        String result = TerminalRenderer.prettifyToolInput("exec", "ls -la", 80);
        assertEquals("ls -la", result);
    }

    @Test
    void testPrettifyToolInputUnknownTool() {
        String input = "{\"foo\":\"bar\",\"baz\":42}";
        String result = TerminalRenderer.prettifyToolInput("unknown_tool", input, 80);
        assertEquals("foo=bar, baz=42", result);
    }

    @Test
    void testPrettifyToolInputEmpty() {
        assertEquals("", TerminalRenderer.prettifyToolInput("read", "", 80));
        assertEquals("", TerminalRenderer.prettifyToolInput("read", null, 80));
        assertEquals("", TerminalRenderer.prettifyToolInput("read", "  ", 80));
    }
}
