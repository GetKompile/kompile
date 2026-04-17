package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        assertTrue(output.contains("todowrite"));
        assertTrue(output.contains("Add new task"));
    }

    @Test
    void testRenderToolCallStartExitPlanMode() {
        String output = renderer.renderToolCallStart("exit_plan_mode", "Plan ready");
        assertTrue(output.contains("exit_plan_mode"));
    }

    @Test
    void testRenderToolCallComplete() {
        ToolResult result = ToolResult.success("Added task #1: Implement feature");
        String output = renderer.renderToolCallComplete("todowrite", result);
        assertTrue(output.contains("todowrite"));
    }

    @Test
    void testRenderToolCallCompleteError() {
        ToolResult result = ToolResult.error("subject is required");
        String output = renderer.renderToolCallComplete("todowrite", result);
        assertTrue(output.contains("todowrite"));
        assertTrue(output.contains("subject is required"));
    }

    @Test
    void testRenderToolCallDenied() {
        String output = renderer.renderToolCallDenied("bash", "Destructive command");
        assertTrue(output.contains("bash"));
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
        assertTrue(output.contains("3"));
        assertTrue(output.contains("read"));
        assertTrue(output.contains("2"));
        assertTrue(output.contains("grep"));
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
}
