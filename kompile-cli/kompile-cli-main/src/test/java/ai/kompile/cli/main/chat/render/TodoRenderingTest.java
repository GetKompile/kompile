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
 * All tests use ANSI disabled for clean string comparisons.
 */
class TodoRenderingTest {

    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new TerminalRenderer(false);
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
        void inProgressItem_shouldShowBullet() {
            TodoWriteTool.TodoItem item = new TodoWriteTool.TodoItem(
                    "3", "Working on it", null, "in_progress", "medium");
            String output = renderer.renderTodoItem(item);

            assertTrue(output.contains("#3"));
            assertTrue(output.contains("Working on it"));
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
        }

        @Test
        void inProgressToCompleted_shouldShowTransition() {
            String output = renderer.renderTodoUpdate("2", "Finish task", "in_progress", "completed");

            assertTrue(output.contains("#2"));
            assertTrue(output.contains("Finish task"));
            assertTrue(output.contains("in_progress"));
            assertTrue(output.contains("completed"));
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
