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

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for todo list rendering in {@link TerminalRenderer}.
 * <p>
 * Tests {@code renderTodoList}, {@code renderTodoItem}, and {@code renderTodoUpdate}
 * with various statuses, priorities, descriptions, and edge cases.
 * Plain-text and ANSI tests verify that the current item stays identifiable.
 */
class TodoRenderingTest {

    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TerminalRenderer(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"todowrite", "todoread", "mcp__kompile__todowrite", "mcp__kompile__todoread",
            "TodoWrite", "TodoRead", "todo_write", "todo_read"})
    void completedCallsAndInspectorUseListUi(String name) throws Exception {
        var tasks = JsonUtils.standardMapper().readTree("""
                [{"id":"1","subject":"Done","status":"completed"},
                 {"id":"2","subject":"Working","status":"in_progress","priority":"high"},
                 {"id":"3","subject":"Skipped","status":"cancelled"}]
                """);
        // Use the real MCP wire contract: metadata becomes maps/lists on the provider route.
        var mapper = JsonUtils.standardMapper();
        var wire = ai.kompile.cli.main.chat.tools.McpToolResultSerializer.toMcpCallResult(mapper,
                ToolResult.success("Tasks", "generic output", Map.of("todos", tasks)));
        ToolResult result = ai.kompile.cli.main.chat.tools.McpToolResultSerializer.fromStructuredContentText(
                mapper, wire.path("structuredContent").toString(), false);
        assertNotNull(result);
        assertFalse(TerminalRenderer.summarizeToolResult(result, 500).contains("todos="));
        for (boolean ansi : List.of(false, true)) {
            TerminalRenderer viewer = new TerminalRenderer(ansi);
            String detail = viewer.renderToolResultDetail(name, "{}", result);
            String plain = TerminalRenderer.printableToolOutput(detail);
            assertTrue(plain.contains("Tasks [1/3]"), plain);
            assertTrue(plain.contains("▶ #2 Working [current] !high"), plain);
            assertTrue(plain.contains("✗ #3 Skipped"), plain);
            String completed = viewer.renderToolCallComplete(name, "{}", result);
            assertTrue(completed.endsWith(detail), completed);
            assertFalse(completed.contains("generic output"), completed);
            assertFalse(completed.contains("todos="), completed);
            var sections = viewer.renderToolDisplaySections(name, "{}", result, "", 128_000);
            assertEquals(1, sections.size());
            assertEquals("todos", sections.get(0).kind());
            assertEquals(AttributedString.fromAnsi(detail), AttributedString.fromAnsi(sections.get(0).formattedContent()));
            assertEquals(plain, sections.get(0).content());
            assertTrue(viewer.renderSubagentToolCall(name, "{}", result).contains("Tasks"));
        }
    }

    @Test
    void nativeProviderArgumentsAndJsonReadOutputUseListUi() {
        String arguments = "{\"todos\":[{\"content\":\"Native task\",\"status\":\"in_progress\"}]}";
        String written = renderer.renderToolCallComplete("TodoWrite", arguments, ToolResult.success("updated"));
        assertTrue(written.contains("▶ #1 Native task [current]"), written);
        String read = renderer.renderToolCallComplete("TodoRead", "{}",
                ToolResult.success("[{\"content\":\"Native task\",\"status\":\"completed\"}]"));
        assertTrue(read.contains("Tasks [1/1]"), read);
        assertTrue(read.contains("✓ #1 Native task"), read);
    }

    @Test
    void legacyReadCheckboxesUseListUi() {
        String rendered = renderer.renderToolCallComplete("mcp__kompile__todoread", "{}",
                ToolResult.success("Tasks", "[x] #1: Finished\n[~] #2: Current\n    description\n[ ] #3: Later"));
        assertTrue(rendered.contains("Tasks [1/3]"), rendered);
        assertTrue(rendered.contains("▶ #2 Current [current]"), rendered);
        assertTrue(rendered.contains("description"), rendered);
        assertFalse(rendered.contains("[~]"), rendered);
    }

    @Test
    void emptySnapshotsOverrideArgumentsAndErrorsStayVisible() {
        String arguments = "{\"todos\":[\"Stale task\"]}";
        ToolResult empty = ToolResult.success("Cleared", "generic output", Map.of("todos", List.of()));
        assertEquals("  No tasks", renderer.renderToolResultDetail("todowrite", arguments, empty));
        assertEquals("  No tasks", renderer.renderToolDisplaySections("todoread", "{}", empty, "", 100)
                .get(0).content());
        String denied = renderer.renderToolCallComplete("todowrite", arguments, ToolResult.error("permission denied"));
        assertTrue(denied.contains("permission denied"), denied);
        assertFalse(denied.contains("Tasks ["), denied);
        String unknown = renderer.renderToolCallComplete("todoread", "{}", ToolResult.success("unexpected response"));
        assertTrue(unknown.contains("unexpected response"), unknown);
        assertFalse(unknown.contains("No tasks"), unknown);
    }

    @Test
    void todoPayloadsCannotInjectTerminalControls() {
        var tasks = List.of(new TodoWriteTool.TodoItem("1\u001b[2J", "task\u001b[2J", "desc\u0007", "pending", "medium"));
        String detail = renderer.renderToolResultDetail("todowrite", "{}",
                ToolResult.success("updated", "", Map.of("todos", tasks)));
        assertFalse(detail.contains("\u001b"), detail);
        assertFalse(detail.contains("\u0007"), detail);
        assertTrue(detail.contains("#1 task"), detail);
    }

    // ===================================================================
    // renderTodoList
    // ===================================================================

    @Nested
    class RenderTodoList {

        @Test
        void emptyList_shouldShowNoTasks() {
            String output = renderer.renderTodoList(List.of());
            assertTrue(output.contains("No tasks"));
        }

        @Test
        void singlePendingTask_shouldShowProgressBar() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "1", "Fix login bug", null, "pending", "medium");
            String output = renderer.renderTodoList(List.of(item));

            assertTrue(output.contains("Tasks"), "Should show Tasks header");
            assertTrue(output.contains("0"), "0 completed");
            assertTrue(output.contains("1"), "1 total");
            assertTrue(output.contains("Fix login bug"), "Should show task subject");
        }

        @Test
        void mixedStatuses_shouldShowProgressCount() {
            List<TodoWriteTool.TodoItem> todos = List.of(
                    new TodoWriteTool.TodoItem("1", "Done task", null, "completed", "medium"),
                    new TodoWriteTool.TodoItem("2", "Active task", null, "in_progress", "medium"),
                    new TodoWriteTool.TodoItem("3", "Pending task", null, "pending", "medium")
            );
            String output = renderer.renderTodoList(todos);

            assertTrue(output.contains("Tasks"));
            assertTrue(output.contains("1"), "1 completed");
            assertTrue(output.contains("3"), "3 total");
            assertTrue(output.contains("Done task"));
            assertTrue(output.contains("Active task"));
            assertTrue(output.contains("Pending task"));
        }

        @Test
        void currentHighlight_shouldMoveWithInProgressStatus() {
            TodoWriteTool.TodoItem first = new TodoWriteTool.TodoItem(
                    "1", "First task", null, "in_progress", "medium");
            TodoWriteTool.TodoItem second = new TodoWriteTool.TodoItem(
                    "2", "Second task", null, "pending", "medium");
            List<TodoWriteTool.TodoItem> todos = List.of(first, second);

            String initial = renderer.renderTodoList(todos);
            assertTrue(initial.contains("▶ #1 First task [current]"));
            assertFalse(initial.contains("Second task [current]"));

            first.status = "completed";
            second.status = "in_progress";
            String advanced = renderer.renderTodoList(todos);
            assertFalse(advanced.contains("First task [current]"));
            assertTrue(advanced.contains("▶ #2 Second task [current]"));

            second.status = "completed";
            assertFalse(renderer.renderTodoList(todos).contains("[current]"));
        }

        @Test
        void multipleInProgressItems_shouldHighlightOnlyActiveItems() {
            List<TodoWriteTool.TodoItem> todos = List.of(
                    new TodoWriteTool.TodoItem("1", "Parallel A", null, "in_progress", "medium"),
                    new TodoWriteTool.TodoItem("2", "Parallel B", null, "in_progress", "medium"),
                    new TodoWriteTool.TodoItem("3", "Waiting", null, "pending", "medium")
            );
            String output = renderer.renderTodoList(todos);
            assertTrue(output.contains("▶ #1 Parallel A [current]"));
            assertTrue(output.contains("▶ #2 Parallel B [current]"));
            assertFalse(output.contains("Waiting [current]"));
        }

        @Test
        void allCompleted_shouldShowFullProgress() {
            List<TodoWriteTool.TodoItem> todos = List.of(
                    new TodoWriteTool.TodoItem("1", "Task A", null, "completed", "medium"),
                    new TodoWriteTool.TodoItem("2", "Task B", null, "completed", "medium")
            );
            String output = renderer.renderTodoList(todos);

            assertTrue(output.contains("2"), "2 completed out of 2");
        }

        @Test
        void cancelledTasks_shouldBeIncludedInTotal() {
            List<TodoWriteTool.TodoItem> todos = List.of(
                    new TodoWriteTool.TodoItem("1", "Cancelled", null, "cancelled", "medium"),
                    new TodoWriteTool.TodoItem("2", "Completed", null, "completed", "medium")
            );
            String output = renderer.renderTodoList(todos);

            assertTrue(output.contains("1"), "1 completed");
            assertTrue(output.contains("2"), "2 total");
        }
    }

    // ===================================================================
    // renderTodoItem
    // ===================================================================

    @Nested
    class RenderTodoItem {

        @Test
        void pendingItem_shouldShowCircleIcon() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "1", "Pending task", null, "pending", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("#1"), "Should show task ID");
            assertTrue(output.contains("Pending task"), "Should show subject");
        }

        @Test
        void completedItem_shouldShowCheckmark() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "2", "Done task", null, "completed", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("#2"));
            assertTrue(output.contains("Done task"));
        }

        @Test
        void inProgressItem_shouldMarkCurrentWithoutAnsi() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "3", "Working on it", null, "in_progress", "medium");
            String output = renderer.renderTodoItem(item);

            assertEquals("  ▶ #3 Working on it [current]", output);
            assertFalse(output.contains("\033"));
        }

        @Test
        void inProgressItem_shouldHighlightIconIdSubjectAndCurrentLabel() {
            TerminalRenderer ansiRenderer = new TerminalRenderer(true);
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "3", "Working on it", "Task details", "in_progress", "high");
            String output = ansiRenderer.renderTodoItem(item);
            AttributedString row = AttributedString.fromAnsi(output.split("\n")[0]);
            AttributedStyle currentStyle = AttributedStyle.DEFAULT.bold().foreground(AttributedStyle.YELLOW);
            for (String token : List.of("▶", "#3", "Working on it", "[current]")) {
                int start = row.toString().indexOf(token);
                assertTrue(start >= 0, "Missing current-item token: " + token);
                for (int i = start; i < start + token.length(); i++) {
                    assertEquals(currentStyle, row.styleAt(i), "Highlight for " + token);
                }
            }
            assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED),
                    row.styleAt(row.toString().indexOf("!high")), "Priority keeps its own styling");
            assertTrue(output.contains("Task details"));
        }

        @Test
        void inactiveItems_shouldNotHaveCurrentMarkerOrBoldSubject() {
            TerminalRenderer ansiRenderer = new TerminalRenderer(true);
            for (String status : List.of("pending", "completed", "cancelled")) {
                TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                        "1", "Inactive task", null, status, "medium");
                String output = ansiRenderer.renderTodoItem(item);
                assertFalse(output.contains("[current]"), status);
                assertFalse(output.contains("▶"), status);
                AttributedString row = AttributedString.fromAnsi(output);
                AttributedStyle style = row.styleAt(row.toString().indexOf("Inactive task"));
                assertNotEquals(style, style.bold(), "Inactive subject should not be bold: " + status);
            }
        }

        @Test
        void cancelledItem_shouldShowCross() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "4", "Cancelled task", null, "cancelled", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("#4"));
            assertTrue(output.contains("Cancelled task"));
        }

        @Test
        void highPriority_shouldShowPriorityLabel() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "5", "Urgent fix", null, "pending", "high");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("high"), "High priority should be shown");
        }

        @Test
        void lowPriority_shouldShowPriorityLabel() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "6", "Nice to have", null, "pending", "low");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("low"), "Low priority should be shown");
        }

        @Test
        void mediumPriority_shouldNotShowPriorityLabel() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "7", "Normal task", null, "pending", "medium");
            String output = renderer.renderTodoItem(item);

            // Medium is default — should not be explicitly shown
            assertFalse(output.contains("medium"),
                    "Medium priority should not be shown (it's the default)");
        }

        @Test
        void nullPriority_shouldNotShowPriorityLabel() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "8", "No priority", null, "pending", null);
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("No priority"));
        }

        @Test
        void withDescription_shouldShowTruncatedDescription() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "9", "Complex task", "This is a detailed description of the task", "pending", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("Complex task"));
            assertTrue(output.contains("detailed description"),
                    "Description should be shown");
        }

        @Test
        void emptyDescription_shouldNotShowDescriptionLine() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "10", "Simple task", "", "pending", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("Simple task"));
            // No extra description line
            assertEquals(1, output.split("\n").length,
                    "Should be single line without description");
        }
    }

    // ===================================================================
    // renderTodoUpdate
    // ===================================================================

    @Nested
    class RenderTodoUpdate {

        @Test
        void pendingToInProgress_shouldShowTransition() {
            String output = renderer.renderTodoUpdate("1", "Start work", "pending", "in_progress");

            assertTrue(output.contains("#1"), "Should show task ID");
            assertTrue(output.contains("Start work"), "Should show subject");
            assertTrue(output.contains("pending"), "Should show old status");
            assertTrue(output.contains("in_progress"), "Should show new status");
            assertTrue(output.contains("▶ #1 Start work [current]"));
        }

        @Test
        void inProgressToCompleted_shouldShowTransition() {
            String output = renderer.renderTodoUpdate("2", "Finish task", "in_progress", "completed");

            assertTrue(output.contains("#2"));
            assertTrue(output.contains("Finish task"));
            assertTrue(output.contains("in_progress"));
            assertTrue(output.contains("completed"));
            assertFalse(output.contains("[current]"));
        }

        @Test
        void pendingToCancelled_shouldShowTransition() {
            String output = renderer.renderTodoUpdate("3", "Skip this", "pending", "cancelled");

            assertTrue(output.contains("#3"));
            assertTrue(output.contains("pending"));
            assertTrue(output.contains("cancelled"));
        }

        @Test
        void sameStatus_shouldStillRender() {
            String output = renderer.renderTodoUpdate("4", "Refresh", "in_progress", "in_progress");

            assertTrue(output.contains("#4"));
            assertTrue(output.contains("Refresh"));
        }
    }
}
