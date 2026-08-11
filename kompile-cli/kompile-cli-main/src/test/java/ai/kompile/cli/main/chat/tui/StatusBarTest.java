package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.AnsiConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBarTest {

    @AfterEach
    void clearActivity() {
        ChatCompleter.setActivity(null);
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
}
