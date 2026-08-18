package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.AnsiConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBarTest {

    @AfterEach
    void clearActivity() {
        ChatCompleter.setActivity(null);
    }

    @Test
    void leavesLastTerminalColumnUnusedToPreventAutoWrap() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-width-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            ChatCompleter.setActivity("Working: an intentionally very long crawl operation");

            String rendered = bar.buildStatusContent(20);
            String plain = AnsiConstants.stripAnsi(rendered);
            assertEquals(19, plain.length(), plain);
            assertEquals(19, StatusBar.safeDrawWidth(20));
            assertFalse(rendered.contains("\n"));
            assertFalse(rendered.contains("\r"));
        } finally {
            processes.close();
        }
    }

    @Test
    void rendersThinkingAndWorkingActivity() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));

            ChatCompleter.setActivity("Thinking");
            String thinking = AnsiConstants.stripAnsi(bar.buildStatusContent());
            assertTrue(thinking.contains("Thinking"), thinking);

            ChatCompleter.setActivity("Working: Read");
            String working = AnsiConstants.stripAnsi(bar.buildStatusContent());
            assertTrue(working.contains("Working: Read"), working);
        } finally {
            processes.close();
        }
    }

    @Test
    void rendersInterruptedActivityAsTerminalWithoutSpinner() {
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-interrupted-test");
        try {
            StatusBar bar = new StatusBar(
                    new BackgroundTaskManager(), processes, null, new TerminalRenderer(true));
            ChatCompleter.markInterrupted();

            String rendered = AnsiConstants.stripAnsi(bar.buildStatusContent());
            assertTrue(rendered.contains("Interrupted by user"), rendered);
            assertTrue(ChatCompleter.isActivityTerminal());
        } finally {
            processes.close();
        }
    }

    @Test
    void retainsCompletedSubagentsForProcessManagement() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-subagent-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));

            bar.registerSubagent("agent-1", "explore", "Inspect the activity pane");
            bar.updateSubagentStatus("agent-1", "completed");
            bar.unregisterSubagent("agent-1");

            assertTrue(bar.getActiveSubagents().isEmpty());
            assertEquals(1, bar.getRecentSubagents().size());
            assertEquals("completed", bar.getRecentSubagents().get(0).getStatus());
            assertTrue(AnsiConstants.stripAnsi(bar.renderProcessPanel())
                    .contains("Recent Subagents"));
        } finally {
            processes.close();
        }
    }

    @Test
    void showsSubagentNameTaskAndThinkingStateTogether() {
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-subagent-details-test");
        try {
            StatusBar bar = new StatusBar(
                    new BackgroundTaskManager(), processes, null, new TerminalRenderer(true));
            bar.registerSubagent("agent-details", "codex", "Inspect cancellation");
            bar.updateSubagentStatus("agent-details", "thinking");

            String rendered = AnsiConstants.stripAnsi(bar.buildStatusContent());
            assertTrue(rendered.contains("codex"), rendered);
            assertTrue(rendered.contains("Inspect cancellation"), rendered);
            assertTrue(rendered.contains("thinking"), rendered);
        } finally {
            processes.close();
        }
    }

    @Test
    void retainsRawStreamingTranscriptAndReactivatesSameConversation() {
        BackgroundProcessManager processes = new BackgroundProcessManager("status-bar-stream-test");
        try {
            StatusBar bar = new StatusBar(
                    new BackgroundTaskManager(), processes, null, new TerminalRenderer(true));
            bar.registerSubagent("agent-live", "explore", "Inspect runtime");
            bar.appendSubagentActivity("agent-live", "responding", "Assistant › ");
            bar.appendSubagentOutput("agent-live", "first ");
            bar.appendSubagentOutput("agent-live", "response");
            bar.unregisterSubagent("agent-live");

            assertEquals("Assistant › first response",
                    bar.getRecentSubagents().get(0).getTranscript());

            StatusBar.SubagentEntry resumed = bar.registerSubagent(
                    "agent-live", "explore", "Interactive follow-up");
            assertEquals(1, bar.getActiveSubagents().size());
            assertTrue(bar.getRecentSubagents().isEmpty());
            assertEquals("Assistant › first response", resumed.getTranscript());
            assertEquals("starting follow-up", resumed.getStatus());
        } finally {
            processes.close();
        }
    }
}
