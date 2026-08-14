package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KompileTuiContentViewTest {

    @Test
    void scrollsRetainedTranscriptAndReturnsToLiveTail() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-content-scroll-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-content-scroll-queue"),
                    new TerminalRenderer(false));
            for (int i = 1; i <= 8; i++) {
                tui.printInScrollRegion("line " + i);
            }

            assertEquals(java.util.List.of("line 7", "line 8"), tui.getVisibleContentLines());
            assertTrue(tui.pageContent(1));
            assertTrue(tui.getContentScrollOffset() > 0);
            assertFalse(tui.getVisibleContentLines().contains("line 8"));

            assertTrue(tui.scrollToBottom());
            assertEquals(0, tui.getContentScrollOffset());
            assertEquals(java.util.List.of("line 7", "line 8"), tui.getVisibleContentLines());
        } finally {
            processes.close();
        }
    }

    @Test
    void liveActivityRefreshPreservesScrolledViewport() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-activity-scroll-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-activity-scroll-queue"),
                    new TerminalRenderer(false));
            tui.showActivityView("subagent:one", "subagent one",
                    "first\nsecond\nthird\nfourth");
            assertTrue(tui.pageContent(1));
            int priorOffset = tui.getContentScrollOffset();

            tui.updateActivityView("subagent:one", "subagent one",
                    "first\nsecond\nthird\nfourth\nfifth");

            assertTrue(tui.getContentScrollOffset() >= priorOffset);
            assertTrue(tui.getContentViewLines().contains("fifth"));
            assertFalse(tui.getVisibleContentLines().contains("fifth"));
        } finally {
            processes.close();
        }
    }

    @Test
    void switchesProcessContentInPlaceAndRestoresRetainedMainTranscript() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-content-view-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(),
                    processes,
                    new MessageQueue("tui-content-view-queue"),
                    new TerminalRenderer(false));

            tui.printInScrollRegion("parent line one");
            tui.rememberMainTranscriptLine("kompile> inspect the build");
            tui.showActivityView(
                    "process:proc-001",
                    "process [proc-001] · Build",
                    "first process line\nsecond process line");

            assertFalse(tui.isMainContentView());
            assertEquals("process:proc-001", tui.getContentViewKey());
            assertTrue(tui.getContentViewLines().stream()
                    .anyMatch(line -> line.contains("second process line")));
            assertTrue(tui.getContentViewLines().stream()
                    .anyMatch(line -> line.contains("process [proc-001]")));

            // Parent output is retained while the process view remains isolated.
            tui.printInScrollRegion("parent line two");
            assertFalse(tui.isMainContentView());
            assertFalse(tui.getContentViewLines().stream()
                    .anyMatch(line -> line.contains("parent line two")));

            tui.showMainView();

            assertTrue(tui.isMainContentView());
            assertEquals("Main chat", tui.getContentViewTitle());
            assertEquals(
                    java.util.List.of(
                            "parent line one",
                            "kompile> inspect the build",
                            "parent line two"),
                    tui.getContentViewLines());
        } finally {
            processes.close();
        }
    }
}
