package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.tools.TodoWriteTool;

import java.util.List;
import java.util.Locale;

/**
 * Harness-driven periodic todo reminders. Pure logic: counts tool calls since the model last
 * touched its todo list and, when open tasks exist, produces a compact status block the loop
 * sends with the next provider request. Bounded per turn so an ignoring model cannot loop.
 */
public final class TodoAutopilot {
    public static final int DEFAULT_EVERY_TOOL_CALLS = 8;
    public static final int DEFAULT_MAX_REMINDERS_PER_TURN = 5;

    private static final String OPEN_TAG = "<todo-status source=\"harness\">";

    /** True for a note this class produced; user-turn decoration must not touch it. */
    public static boolean isHarnessReminder(String message) {
        return message != null && message.startsWith(OPEN_TAG);
    }

    private int everyToolCalls;
    private final int maxReminders;
    private final boolean autoStartFirstTask;
    private boolean enabled;
    private int sinceTodoTouch;
    private int reminders;
    private boolean autoStartAttempted;

    public TodoAutopilot() {
        this(DEFAULT_EVERY_TOOL_CALLS, DEFAULT_MAX_REMINDERS_PER_TURN, true);
    }

    public TodoAutopilot(int everyToolCalls, int maxReminders, boolean autoStartFirstTask) {
        this.everyToolCalls = Math.max(1, everyToolCalls);
        this.maxReminders = Math.max(0, maxReminders);
        this.autoStartFirstTask = autoStartFirstTask;
    }

    public TodoAutopilot(int everyToolCalls, int maxReminders) {
        this(everyToolCalls, maxReminders, true);
    }

    /** Project-config driven; a non-positive interval keeps the current one. */
    public synchronized void configure(boolean enabled, int everyToolCalls) {
        this.enabled = enabled;
        if (everyToolCalls > 0) this.everyToolCalls = Math.max(1, everyToolCalls);
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /** Called at the start of each user turn. */
    public synchronized void beginTurn() {
        sinceTodoTouch = 0;
        reminders = 0;
        autoStartAttempted = false;
    }

    /**
     * One harness auto-start per turn, fired at the first poll where a plan exists. Polls
     * made before the plan exists do not consume the attempt — the loop checks every batch
     * boundary and the plan typically lands after the first ones. Applied only when the
     * autopilot is enabled via openAutoStart.
     */
    public synchronized boolean shouldAutoStartFirst(boolean planExists, boolean openAutoStart) {
        if (!enabled || !autoStartFirstTask || !openAutoStart || autoStartAttempted) return false;
        if (!planExists) return false;
        autoStartAttempted = true;
        return true;
    }

    /** Record one executed tool call. */
    public synchronized void recordTool(String toolName) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith("todowrite") || name.endsWith("todoread") || name.endsWith("todo_apply_batch")) {
            sinceTodoTouch = 0;
        } else {
            sinceTodoTouch++;
        }
    }

    /** Returns a reminder when one is due, else null. Firing resets the counter. */
    public synchronized String nextReminder(List<TodoWriteTool.TodoItem> todos) {
        if (!enabled || todos == null || todos.isEmpty()
                || sinceTodoTouch < everyToolCalls || reminders >= maxReminders) {
            return null;
        }        StringBuilder open = new StringBuilder();
        String current = null;
        int openCount = 0;
        for (TodoWriteTool.TodoItem todo : todos) {
            boolean inProgress = "in_progress".equals(todo.status);
            if (!inProgress && !"pending".equals(todo.status)) continue;
            openCount++;
            if (inProgress && current == null) current = todo.subject;
            if (openCount <= 15) {
                open.append("- [").append(todo.status).append("] #").append(todo.id)
                        .append(' ').append(todo.subject).append('\n');
            }
        }
        if (openCount == 0) return null;
        reminders++;
        int calls = sinceTodoTouch;
        sinceTodoTouch = 0;
        StringBuilder out = new StringBuilder(OPEN_TAG).append('\n')
                .append(calls).append(" tool calls since your todo list was last updated. ")
                .append(openCount).append(" open task(s):\n").append(open);
        if (current == null) {
            out.append("No task is in_progress; mark the one you are working on in_progress.\n");
        }
        out.append("If any finished, mark them completed with todowrite (action='update'); ")
                .append("then continue the task. Do not reply to this note separately.\n")
                .append("</todo-status>");
        return out.toString();
    }
}
