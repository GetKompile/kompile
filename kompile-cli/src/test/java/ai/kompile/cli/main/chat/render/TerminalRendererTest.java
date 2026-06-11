package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
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
    // Agent loop rendering
    // ========================================================================

    @Test
    void testRenderAgentTurnStart() {
        String output = renderer.renderAgentTurnStart(3, 50);
        assertTrue(output.contains("3/50"));
    }

    @Test
    void testRenderMaxStepsWarning() {
        String output = renderer.renderMaxStepsWarning(50);
        assertTrue(output.contains("50"));
    }

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
