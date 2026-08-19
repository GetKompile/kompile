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
            assertTrue(tui.scrollContent(1));
            assertTrue(tui.scrollToTop());
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
    void mismatchedRefreshSwitchesActiveViewAndKeepsItScrollable() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-active-view-switch-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-active-view-switch-queue"),
                    new TerminalRenderer(false));

            tui.showActivityView("process:one", "process one",
                    "one-1\none-2\none-3\none-4\none-5\none-6\none-7");
            assertTrue(tui.pageContent(1));
            assertTrue(tui.getContentScrollOffset() > 0);

            // A background refresh for a newly selected process must switch the
            // rendered view instead of being silently discarded.
            tui.updateActivityView("process:two", "process two",
                    "two-1\ntwo-2\ntwo-3\ntwo-4\ntwo-5\ntwo-6\ntwo-7");
            assertEquals("process:two", tui.getContentViewKey());
            assertTrue(tui.getContentViewLines().contains("── process two ──"));
            assertEquals(0, tui.getContentScrollOffset());

            // Repaint is safe even before an interactive terminal is attached.
            tui.redrawContentView();
            assertTrue(tui.pageContent(1));
            assertFalse(tui.getVisibleContentLines().contains("two-7"));
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

    @Test
    void resumedTranscriptSurvivesTuiViewSwitchAndRepaint() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-resume-transcript-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(),
                    processes,
                    new MessageQueue("tui-resume-transcript-queue"),
                    new TerminalRenderer(false));

            // Managed passthrough resume records lines through the TUI state API
            // while its cursor-safe renderer handles the immediate terminal write.
            tui.recordInScrollRegion("── Resumed conversation (2 turns) ──");
            tui.recordInScrollRegion("You: prior question");
            tui.recordInScrollRegion("Assistant: prior answer");

            tui.showActivityView("process:resume", "process resume", "working");
            tui.showMainView();
            tui.redrawContentView();

            assertEquals(
                    java.util.List.of(
                            "── Resumed conversation (2 turns) ──",
                            "You: prior question",
                            "Assistant: prior answer"),
                    tui.getContentViewLines());
        } finally {
            processes.close();
        }
    }

    @Test
    void temporaryWindowOwnsRenderingAndRestoresActiveView() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-temporary-window-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-temporary-window-queue"),
                    new TerminalRenderer(false));
            tui.printInScrollRegion("parent output");
            tui.showActivityView("process:one", "process one", "process output");

            tui.showTemporaryWindow("Provider and model", java.util.List.of(
                    "Active: OpenAI / gpt-4o", "1  Anthropic", "2  OpenAI"));
            assertTrue(tui.isTemporaryWindowActive());
            assertEquals("__temporary__", tui.getContentViewKey());
            assertTrue(tui.getContentViewLines().stream()
                    .anyMatch(line -> line.contains("Active: OpenAI / gpt-4o")));

            // Async activity/output cannot overwrite the modal, but is retained.
            tui.recordInScrollRegion("output while picker is open");
            tui.updateActivityView("process:one", "process one", "new process output");
            assertTrue(tui.getContentViewLines().stream()
                    .noneMatch(line -> line.contains("new process output")));

            tui.closeTemporaryWindow();
            assertFalse(tui.isTemporaryWindowActive());
            assertEquals("process:one", tui.getContentViewKey());
            assertTrue(tui.getContentViewLines().stream()
                    .anyMatch(line -> line.contains("process output")));

            tui.showMainView();
            assertTrue(tui.getContentViewLines().contains("output while picker is open"));
        } finally {
            processes.close();
        }
    }
}
