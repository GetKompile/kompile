package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.tools.TodoWriteTool.TodoItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoAutopilotTest {
    private static List<TodoItem> open() {
        return List.of(new TodoItem("1", "build it", "", "pending", "medium"),
                new TodoItem("2", "done", "", "completed", "medium"));
    }

    @Test
    void firesAfterThresholdAndResets() {
        TodoAutopilot a = new TodoAutopilot(3, 5);
        a.configure(true, 3);
        a.recordTool("read"); a.recordTool("grep");
        assertNull(a.nextReminder(open()));
        a.recordTool("edit");
        String r = a.nextReminder(open());
        assertNotNull(r);
        assertTrue(r.contains("#1 build it"));
        assertFalse(r.contains("#2"));
        assertTrue(r.contains("No task is in_progress"));
        assertNull(a.nextReminder(open()));
    }

    @Test
    void todoToolResetsCounter() {
        TodoAutopilot a = new TodoAutopilot(2, 5);
        a.configure(true, 2);
        a.recordTool("read"); a.recordTool("todowrite"); a.recordTool("read");
        assertNull(a.nextReminder(open()));
        a.recordTool("read");
        assertNotNull(a.nextReminder(open()));
    }

    @Test
    void batchTodoToolResetsCounter() {
        for (String name : List.of("todo_apply_batch", "mcp__kompile__todo_apply_batch")) {
            TodoAutopilot a = new TodoAutopilot(2, 5);
            a.configure(true, 2);
            a.recordTool("read");
            a.recordTool(name);
            a.recordTool("read");
            assertNull(a.nextReminder(open()), name);
            a.recordTool("read");
            assertNotNull(a.nextReminder(open()), name);
        }
    }

    @Test
    void cappedPerTurnAndNoOpenTasksSilent() {
        TodoAutopilot a = new TodoAutopilot(1, 2);
        a.configure(true, 1);
        for (int i = 0; i < 2; i++) { a.recordTool("x"); assertNotNull(a.nextReminder(open())); }
        a.recordTool("x");
        assertNull(a.nextReminder(open()));
        a.beginTurn(); a.recordTool("x");
        assertNotNull(a.nextReminder(open()));
        a.recordTool("x");
        assertNull(a.nextReminder(List.of(new TodoItem("2", "d", "", "completed", "low"))));
    }

    @Test
    void reminderIsRecognizedAsHarnessNote() {
        TodoAutopilot a = new TodoAutopilot(1, 5);
        a.configure(true, 1);
        a.recordTool("x");
        assertTrue(TodoAutopilot.isHarnessReminder(a.nextReminder(open())));
        assertFalse(TodoAutopilot.isHarnessReminder("user text"));
        assertFalse(TodoAutopilot.isHarnessReminder(null));
    }

    @Test
    void disabledAutopilotIsSilent() {
        TodoAutopilot a = new TodoAutopilot(1, 5);
        a.recordTool("x");
        a.recordTool("x");
        assertNull(a.nextReminder(open()), "disabled autopilot must not remind");
        a.configure(true, 1);
        a.beginTurn();
        a.recordTool("x");
        assertNotNull(a.nextReminder(open()));
    }

    @Test
    void autoStartFiresOncePerTurnWhenEnabledAndPlanned() {
        TodoAutopilot a = new TodoAutopilot(4, 5);
        a.configure(true, 4);
        a.beginTurn();
        assertFalse(a.shouldAutoStartFirst(false, true), "no plan, no auto-start");
        assertFalse(a.shouldAutoStartFirst(false, true), "planless polls must not consume the attempt");
        assertTrue(a.shouldAutoStartFirst(true, true));
        assertFalse(a.shouldAutoStartFirst(true, true), "one auto-start per turn");
        a.beginTurn();
        assertTrue(a.shouldAutoStartFirst(true, true));
        a.beginTurn();
        assertFalse(a.shouldAutoStartFirst(true, false), "disabled gate must block");
    }
}
