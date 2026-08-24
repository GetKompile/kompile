package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KompileTuiContentViewTest {

    @Test
    void queuePaneUsesStableBoundedRowsForTerminalHeight() {
        assertEquals(1, KompileTui.queueRowsForTerminal(12));
        assertEquals(2, KompileTui.queueRowsForTerminal(16));
        assertEquals(3, KompileTui.queueRowsForTerminal(24));
        assertEquals(4, KompileTui.queueRowsForTerminal(40));
        assertEquals(5, KompileTui.queueRowsForTerminal(80));
    }

    @Test
    void emptyQueuePaneRendersNoPlaceholder() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-empty-queue-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-empty-queue"),
                    new TerminalRenderer(false));

            assertTrue(tui.visibleQueueLines().isEmpty());
        } finally {
            processes.close();
        }
    }

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
    void scrollToBottomControlOnlyHandlesClicksInsideItsVisibleHitBox() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-scroll-bottom-control-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-scroll-bottom-control-queue"),
                    new TerminalRenderer(false));
            for (int i = 1; i <= 8; i++) {
                tui.printInScrollRegion("line " + i);
            }

            assertFalse(tui.isScrollToBottomControlVisible());
            assertFalse(tui.handleScrollToBottomClick(
                    tui.scrollToBottomControlX(), tui.scrollToBottomControlY()));

            assertTrue(tui.pageContent(1));
            int scrolledOffset = tui.getContentScrollOffset();
            assertTrue(tui.isScrollToBottomControlVisible());
            int controlX = tui.scrollToBottomControlX();
            int controlY = tui.scrollToBottomControlY();
            assertFalse(tui.handleScrollToBottomClick(controlX, controlY - 1),
                    "same column on another row must not activate the control");
            assertFalse(tui.handleScrollToBottomClick(controlX - 1, controlY),
                    "viewport clicks outside the floating label must remain inert");
            assertEquals(scrolledOffset, tui.getContentScrollOffset());

            assertTrue(tui.handleScrollToBottomClick(controlX, controlY));
            assertEquals(0, tui.getContentScrollOffset());
            assertFalse(tui.isScrollToBottomControlVisible());
            assertFalse(tui.handleScrollToBottomClick(2, 2),
                    "the hidden control must not retain an active hit box");
        } finally {
            processes.close();
        }
    }

    @Test
    void transcriptSelectionHighlightsStyledRowsAndCopiesPlainText() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-transcript-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-transcript-selection-queue"),
                    new TerminalRenderer(false));
            tui.printInScrollRegion("alpha");
            tui.printInScrollRegion("\033[31mbravo\033[0m");

            assertTrue(tui.beginTranscriptSelection(1, tui.scrollTop() - 1));
            assertTrue(tui.dragTranscriptSelection(2, tui.scrollTop()));
            assertTrue(tui.finishTranscriptSelection(2, tui.scrollTop()));

            assertTrue(tui.hasTranscriptSelection());
            assertEquals("lpha\nbra", tui.getSelectedTranscriptText());
            assertTrue(tui.getVisibleContentLines().stream()
                    .anyMatch(line -> line.contains("\033[") && line.contains("7")),
                    "selected cells should render with inverse-video SGR");

            assertTrue(tui.clearTranscriptSelection());
            assertFalse(tui.hasTranscriptSelection());
            assertEquals("", tui.getSelectedTranscriptText());
        } finally {
            processes.close();
        }
    }

    @Test
    void draggingAtViewportEdgeScrollsAndExtendsSelection() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-scrolled-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-scrolled-selection-queue"),
                    new TerminalRenderer(false));
            for (int i = 1; i <= 8; i++) tui.printInScrollRegion("line " + i);

            int top = tui.scrollTop() - 1;
            int bottom = tui.scrollBottom() - 2;
            assertTrue(tui.beginTranscriptSelection(5, bottom));
            assertTrue(tui.dragTranscriptSelection(5, top));
            assertEquals(1, tui.getContentScrollOffset());
            assertEquals("6\nline 7\nline 8", tui.getSelectedTranscriptText());

            assertTrue(tui.dragTranscriptSelection(5, top));
            assertEquals(2, tui.getContentScrollOffset());
            assertEquals("5\nline 6\nline 7\nline 8", tui.getSelectedTranscriptText());
            assertTrue(tui.finishTranscriptSelection(5, top));

            assertTrue(tui.scrollContent(1));
            assertEquals("5\nline 6\nline 7\nline 8", tui.getSelectedTranscriptText(),
                    "selection coordinates must remain stable while the viewport scrolls");
        } finally {
            processes.close();
        }
    }

    @Test
    void copiedSelectionJoinsSoftWrappedRowsWithoutInventingNewlines() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-soft-wrap-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-soft-wrap-selection-queue"),
                    new TerminalRenderer(false));
            tui.printInScrollRegion("x".repeat(90));

            int top = tui.scrollTop() - 1;
            assertTrue(tui.beginTranscriptSelection(75, top));
            assertTrue(tui.dragTranscriptSelection(5, top + 1));
            assertTrue(tui.finishTranscriptSelection(5, top + 1));

            assertEquals("x".repeat(10), tui.getSelectedTranscriptText());
            assertFalse(tui.getSelectedTranscriptText().contains("\n"));
        } finally {
            processes.close();
        }
    }

    @Test
    void selectionSnapsWideAndCombiningCharactersToGlyphBoundaries() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-wide-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-wide-selection-queue"),
                    new TerminalRenderer(false));
            int top = tui.scrollTop() - 1;
            tui.printInScrollRegion("A界B");

            assertTrue(tui.beginTranscriptSelection(0, top));
            assertTrue(tui.dragTranscriptSelection(1, top));
            assertTrue(tui.finishTranscriptSelection(1, top));
            assertEquals("A界", tui.getSelectedTranscriptText(),
                    "either cell of a wide glyph must select the complete glyph");

            tui.clearTranscriptSelection();
            tui.printInScrollRegion("Ae\u0301B");
            assertTrue(tui.beginTranscriptSelection(0, top + 1));
            assertTrue(tui.dragTranscriptSelection(1, top + 1));
            assertTrue(tui.finishTranscriptSelection(1, top + 1));
            assertEquals("Ae\u0301", tui.getSelectedTranscriptText(),
                    "zero-width combining marks must remain attached to their base glyph");
        } finally {
            processes.close();
        }
    }

    @Test
    void edgeHoldContinuesScrollingAndPinnedHeaderIsNotPulledIntoSelection() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-held-edge-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-held-edge-selection-queue"),
                    new TerminalRenderer(false));
            tui.showActivityView("process:held", "held process",
                    "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8");
            int top = tui.scrollTop() - 1;
            int bottom = tui.scrollBottom() - 2;

            assertTrue(tui.beginTranscriptSelection(5, bottom));
            assertTrue(tui.dragTranscriptSelection(5, top));
            int firstOffset = tui.getContentScrollOffset();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (tui.getContentScrollOffset() <= firstOffset && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(tui.getContentScrollOffset() > firstOffset,
                    "holding the pointer at the edge should keep scrolling without new motion reports");
            assertFalse(tui.getSelectedTranscriptText().contains("held process"),
                    "the pinned activity title must not become the drag endpoint");
            assertTrue(tui.finishTranscriptSelection(5, top));
        } finally {
            processes.close();
        }
    }

    @Test
    void appendOnlyActivityRefreshPreservesSelectionButChangedPrefixClearsIt() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-live-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-live-selection-queue"),
                    new TerminalRenderer(false));
            tui.showActivityView("process:live", "live process",
                    "line 1\nline 2\nline 3\nline 4\nline 5\nline 6");
            int bottom = tui.scrollBottom() - 2;
            assertTrue(tui.beginTranscriptSelection(0, bottom));
            assertTrue(tui.dragTranscriptSelection(2, bottom));
            assertTrue(tui.finishTranscriptSelection(2, bottom));
            assertEquals("lin", tui.getSelectedTranscriptText());

            tui.updateActivityView("process:live", "live process",
                    "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7");
            assertEquals("lin", tui.getSelectedTranscriptText(),
                    "append-only refreshes must not erase a completed selection");

            tui.updateActivityView("process:live", "live process",
                    "changed 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7");
            assertFalse(tui.hasTranscriptSelection(),
                    "mutating rows before the selected range must invalidate stale coordinates");
        } finally {
            processes.close();
        }
    }

    @Test
    void liveToolBlockIsReplacedInPlaceWithoutDuplicateRows() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-live-tool-block-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-live-tool-block-queue"),
                    new TerminalRenderer(false));
            tui.printInScrollRegion("before");

            assertFalse(tui.upsertMainTranscriptBlock(
                    "tool:call-1", "Run build\n  │ compiling"));
            tui.printInScrollRegion("after");
            assertFalse(tui.upsertMainTranscriptBlock(
                    "tool:call-1", "Ran build ✓\n  │ compiling\n  │ tests passed"));

            assertEquals(java.util.List.of(
                    "before",
                    "Ran build ✓",
                    "  │ compiling",
                    "  │ tests passed",
                    "after"), tui.getContentViewLines());
            assertFalse(tui.getContentViewLines().contains("Run build"));
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
