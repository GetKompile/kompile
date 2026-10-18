package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KompileTuiContentViewTest {

    @Test
    void unchangedTranscriptLayoutIsReusedAcrossRedrawsAndScrolls() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("layout-cache")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("layout-cache"), new TerminalRenderer(true));
            try {
                for (int i = 0; i < 1000; i++) {
                    tui.recordInScrollRegion("line " + i + " " + "wrapped transcript ".repeat(8));
                }
                List<String> visible = tui.getVisibleContentLines();
                long builds = tui.transcriptLayoutBuildCountForTests();
                var management = java.lang.management.ManagementFactory.getThreadMXBean();
                com.sun.management.ThreadMXBean allocations = management instanceof com.sun.management.ThreadMXBean bean
                        && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean : null;
                long thread = Thread.currentThread().getId();
                long before = allocations == null ? -1 : allocations.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 100; i++) {
                    assertEquals(visible, tui.getVisibleContentLines());
                    tui.getContentScrollOffset();
                }
                if (before >= 0) {
                    long allocated = allocations.getThreadAllocatedBytes(thread) - before;
                    assertTrue(allocated < 8 * 1024 * 1024L,
                            "unchanged viewport reads must allocate for the viewport, not history: " + allocated);
                    System.out.println("100 cached transcript viewport reads allocated " + allocated + " bytes");
                }
                tui.scrollContent(1);
                tui.scrollToBottom();
                assertEquals(builds, tui.transcriptLayoutBuildCountForTests(),
                        "status ticks and scrolling must not reparse or wrap retained history");

                tui.recordInScrollRegion("new tail");
                assertTrue(tui.getVisibleContentLines().contains("new tail"));
                long afterEdit = tui.transcriptLayoutBuildCountForTests();
                assertTrue(afterEdit > builds, "content replacement invalidates the layout");
                var width = KompileTui.class.getDeclaredField("terminalWidth");
                width.setAccessible(true);
                width.setInt(tui, 40);
                tui.getVisibleContentLines();
                assertTrue(tui.transcriptLayoutBuildCountForTests() > afterEdit,
                        "column changes must rebuild wrapping");

                tui.showActivityView("test:layout", "pinned title", "other view body");
                assertTrue(tui.getVisibleContentLines().stream().anyMatch(line -> line.contains("other view body")));
                tui.showMainView();
                assertTrue(tui.getVisibleContentLines().contains("new tail"), "view switches must not reuse stale rows");
            } finally {
                tui.stop();
            }
        }
    }

    @Test
    void oneRowActivityCanReachBothHeaderAndTailAndClearsPinnedSelection() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("single-row-layout")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("single-row-layout"), new TerminalRenderer(false));
            try {
                tui.showActivityView("one-row", "title", "A\nB");
                tui.getVisibleContentLines();
                int body = tui.scrollTop();
                assertTrue(tui.beginTranscriptSelection(0, body));
                tui.finishTranscriptSelection(1, body);
                assertTrue(tui.hasTranscriptSelection());
                var height = KompileTui.class.getDeclaredField("terminalHeight");
                height.setAccessible(true);
                height.setInt(tui, 1);
                assertEquals(List.of("B"), tui.getVisibleContentLines());
                assertFalse(tui.hasTranscriptSelection(), "unpinned row indexes must not reuse pinned selection coordinates");
                assertTrue(tui.scrollToTop());
                assertTrue(tui.getVisibleContentLines().get(0).contains("title"));
                tui.scrollToBottom();
                assertEquals(List.of("B"), tui.getVisibleContentLines());
                tui.showTemporaryWindow("document", List.of("A", "B"));
                assertTrue(tui.scrollContent(-2)); // Positive wheel deltas scroll up, also in documents.
                assertEquals("B", tui.getVisibleContentLines().get(0).replace('|', ' ').replace('│', ' ').strip());
                assertTrue(tui.scrollToBottom());
                assertEquals(tui.getContentViewLines().size() - 1, tui.getContentScrollOffset(),
                        "the final document row must remain reachable without a pinned header");
                assertEquals(1, tui.getVisibleContentLines().size());
            } finally { tui.stop(); }
        }
    }

    @Test
    void transcriptRowsNeverEmitTerminalControlsFromToolOutput() {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("transcript-controls")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("transcript-controls"), new TerminalRenderer(true));
            for (String control : List.of("\b", "\u0000", "\u0007", "\u007f", "\u009b24;1H",
                    "\033]0;foreign title\007", "\033Pforeign payload\033\\")) {
                tui.recordInScrollRegion("prefix" + control + "suffix");
                for (String row : tui.getVisibleContentLines()) {
                    String plain = org.jline.utils.AttributedString.fromAnsi(row).toString();
                    assertFalse(plain.codePoints().anyMatch(Character::isISOControl),
                            () -> "terminal control survived in row: " + plain.codePoints().boxed().toList());
                }
            }
        }
    }

    @Test
    void ephemeralAlertsExpireWithoutClearingNewerMessagesOrTranscript() {
        assertEquals(10, KompileTui.EPHEMERAL_MESSAGE_SECONDS);
        try (BackgroundProcessManager processes = new BackgroundProcessManager("alert-expiry")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("alert-expiry"), new TerminalRenderer(false));
            tui.recordInScrollRegion("permanent result");
            java.util.List<Runnable> timers = new java.util.ArrayList<>();
            tui.showAlert("first completion", timers::add);
            tui.showAlert("newer completion", timers::add);
            timers.get(0).run();
            assertEquals("newer completion", tui.getTopBar().getAlert());
            timers.get(1).run();
            assertEquals("", tui.getTopBar().getAlert());
            assertTrue(tui.getVisibleContentLines().contains("permanent result"));
            assertFalse(tui.getVisibleContentLines().contains("newer completion"));
            tui.showAlert("cleared notice", timers::add);
            tui.clearAlert();
            tui.showAlert("replacement", timers::add);
            timers.get(2).run();
            assertEquals("replacement", tui.getTopBar().getAlert());
            timers.get(3).run();
            assertEquals("", tui.getTopBar().getAlert());
        }
    }

    @Test
    void managedAlertExpiryRepaintsOnlyHeaderAndRestoresCursor() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("managed-alert-expiry");
             var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                     "alert-expiry", "xterm", output, java.nio.charset.StandardCharsets.UTF_8)) {
            terminal.setSize(new org.jline.terminal.Size(80, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("managed-alert-expiry"), new TerminalRenderer(true));
            try {
                tui.start(terminal);
                terminal.writer().print("\033[20;1Hmanaged draft sentinel");
                terminal.writer().flush();
                VirtualTerminal screen = new VirtualTerminal(24, 80);
                screen.feed(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                int row = screen.getCursorRow();
                int col = screen.getCursorCol();
                output.reset();
                java.util.List<Runnable> timers = new java.util.ArrayList<>();
                tui.showAlert("[code-index] background refresh failed", timers::add);
                screen.feed(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                assertTrue(screen.getRow(1).contains("background refresh failed"), screen::screenDump);
                output.reset();
                timers.get(0).run();
                screen.feed(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("", tui.getCurrentAlert());
                assertFalse(screen.getRow(1).contains("background refresh failed"), screen::screenDump);
                assertTrue(screen.getRow(19).contains("managed draft sentinel"), screen::screenDump);
                assertEquals(row, screen.getCursorRow());
                assertEquals(col, screen.getCursorCol());
            } finally {
                tui.stop();
            }
        }
    }

    @Test
    void dashboardUsesAStableResponsivePinnedRegion() {
        assertEquals(0, KompileTui.dashboardRowsForTerminal(12));
        assertEquals(0, KompileTui.dashboardRowsForTerminal(16));
        assertEquals(8, KompileTui.dashboardRowsForTerminal(24));
        assertEquals(9, KompileTui.dashboardRowsForTerminal(80));
        assertEquals(0, KompileTui.dashboardRowsForLayout(16, 3, 2),
                "small terminals must retain usable transcript/input rows");
        assertEquals(5, KompileTui.dashboardRowsForLayout(24, 3, 3),
                "dashboard must leave a third of the terminal for the transcript");

        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-dashboard-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-dashboard-queue"),
                    new TerminalRenderer(false));
            int normalTop = tui.scrollTop();

            tui.setDashboard("Elthoria", java.util.stream.IntStream.range(0, 12)
                    .mapToObj(index -> "line " + index).toList());

            assertTrue(tui.getDashboardSnapshot().visible());
            assertEquals("Elthoria", tui.getDashboardSnapshot().title());
            assertEquals(8, tui.getDashboardSnapshot().lines().size());
            assertEquals(normalTop + KompileTui.dashboardRowsForTerminal(24), tui.scrollTop());

            int pinnedTop = tui.scrollTop();
            tui.setDashboard("Elthoria refreshed", java.util.List.of("new state"));
            assertEquals(pinnedTop, tui.scrollTop(),
                    "dashboard content changes must not move the transcript anchor");

            tui.clearDashboard();
            assertFalse(tui.getDashboardSnapshot().visible());
            assertEquals(normalTop, tui.scrollTop());
        } finally {
            processes.close();
        }
    }

    @Test
    void compactDashboardKeepsNextAndReportsTheActualHiddenCount() {
        java.util.List<String> lines = java.util.List.of(
                "Campaign", "Hero", "World", "Scene", "Combat", "Map", "Next");

        assertEquals(java.util.List.of(
                        "Campaign", "Hero", "World", "Next", "… 3 more details"),
                KompileTui.dashboardContentLines(lines, 5));
        assertEquals(java.util.List.of("Next"),
                KompileTui.dashboardContentLines(lines, 1));
    }

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
    void draggingAlongTheEdgeRowItStartedOnSelectsWithoutScrolling() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-edge-row-selection-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-edge-row-selection-queue"),
                    new TerminalRenderer(false));
            for (int i = 1; i <= 8; i++) tui.printInScrollRegion("line " + i);

            int top = tui.scrollTop() - 1;
            assertTrue(tui.beginTranscriptSelection(0, top));
            assertTrue(tui.dragTranscriptSelection(5, top));
            Thread.sleep(250);
            assertEquals(0, tui.getContentScrollOffset(),
                    "dragging along the row the press started on must not scroll, even when held");
            assertEquals("line 7", tui.getSelectedTranscriptText());

            assertTrue(tui.dragTranscriptSelection(5, top + 1));
            assertTrue(tui.dragTranscriptSelection(5, top));
            assertTrue(tui.finishTranscriptSelection(5, top));
            assertTrue(tui.getContentScrollOffset() >= 1,
                    "returning to the edge from another row must still scroll");
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
    void panelSizedToTranscriptColumnsCopiesWholeLinesWithoutStrayBorderRows() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-panel-width-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-panel-width-queue"),
                    new TerminalRenderer(false));
            int columns = KompileTui.transcriptColumns(tui.getTerminalWidth());
            String panel = new AsciiRenderer(new TerminalRenderer(false), columns)
                    .panel("Help", "/help    This help message", AsciiRenderer.ROUNDED, null);
            List<String> panelLines = List.of(panel.split("\n"));
            assertEquals(3, panelLines.size());
            tui.printInScrollRegion(panel);

            // The two-row viewport shows the body and bottom border, one row each:
            // a wider panel would wrap and leave each right border on its own row.
            // Without an attached terminal, JLine 4 serializes box drawing as ASCII.
            // Compare presentation rows here; the copied source must stay Unicode below.
            assertEquals(panelLines.subList(1, 3).stream()
                    .map(line -> new org.jline.utils.AttributedString(line).toAnsi()).toList(),
                    tui.getVisibleContentLines());
            int body = tui.scrollTop() - 1;
            assertTrue(tui.beginTranscriptSelection(0, body));
            assertTrue(tui.finishTranscriptSelection(columns - 1, body));
            assertEquals(panelLines.get(1), tui.getSelectedTranscriptText());
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
    void replacingBlocksOutsideScrollbackViewportKeepsVisibleMessagesInPlace() throws Exception {
        for (boolean typed : new boolean[]{false, true}) {
            for (boolean updateAbove : new boolean[]{false, true}) {
                var output = new java.io.ByteArrayOutputStream();
                try (BackgroundProcessManager processes = new BackgroundProcessManager("scrollback-replace");
                     var terminal = new org.jline.terminal.impl.LineDisciplineTerminal(
                             "scrollback-replace", "xterm", output, java.nio.charset.StandardCharsets.UTF_8)) {
                    terminal.setSize(new org.jline.terminal.Size(80, 24));
                    KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                            new MessageQueue("scrollback-replace"), new TerminalRenderer(true)) {
                        @Override public void requestRedraw() { }
                    };
                    try {
                        tui.start(terminal);
                        java.util.function.BiConsumer<String, String> upsert = (key, text) -> {
                            if (typed) tui.upsertDisplayBlock(ai.kompile.cli.main.chat.render.ChatDisplayBlock.text(key, "tool", text));
                            else tui.upsertMainTranscriptBlock(key, text);
                        };
                        upsert.accept("older-tool", "OLDER TOOL");
                        for (int i = 0; i < 100; i++) tui.recordInScrollRegion("MESSAGE-" + i);
                        upsert.accept("tail-tool", "LIVE TOOL\n" + "progress\n".repeat(29).stripTrailing());
                        tui.scrollContent(35);
                        List<String> visible = tui.getVisibleContentLines();
                        assertTrue(visible.stream().allMatch(line -> line.startsWith("MESSAGE-")));
                        VirtualTerminal screen = new VirtualTerminal(24, 80);
                        tui.redrawContentView(); screen.feed(output.toString(java.nio.charset.StandardCharsets.UTF_8)); output.reset();
                        String before = screen.screenDump();
                        if (updateAbove) upsert.accept("older-tool", "OLDER TOOL\n" + "new output\n".repeat(39).stripTrailing());
                        else upsert.accept("tail-tool", "COMPLETED TOOL");
                        tui.redrawContentView(); screen.feed(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                        assertEquals(visible, tui.getVisibleContentLines(),
                                "Updating a " + (typed ? "typed" : "legacy") + " block " + (updateAbove ? "above" : "below") + " the viewport must not replace visible messages");
                        assertEquals(before, screen.screenDump());
                        assertTrue(tui.getContentViewLines().contains("MESSAGE-99"));
                    } finally { tui.stop(); }
                }
            }
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
    void staleRefreshesCannotReplaceNewerActivityOrMainSelection() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-active-view-switch-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-active-view-switch-queue"),
                    new TerminalRenderer(false));
            tui.printInScrollRegion("retained parent transcript");

            tui.showActivityView("process:one", "process one",
                    "one-1\none-2\none-3\none-4\none-5\none-6\none-7");
            tui.showActivityView("process:two", "process two",
                    "two-1\ntwo-2\ntwo-3\ntwo-4\ntwo-5\ntwo-6\ntwo-7");

            // A delayed refresh for the previously viewed process cannot replace
            // the newer explicit selection.
            tui.updateActivityView("process:one", "stale process one", "stale output");
            assertEquals("process:two", tui.getContentViewKey());
            assertTrue(tui.getContentViewLines().contains("── process two ──"));
            assertFalse(tui.getContentViewLines().contains("stale output"));

            // The inverse stale callback (captured while Main was active) is also
            // guarded and cannot overwrite a child selected afterward.
            java.util.concurrent.atomic.AtomicBoolean mainStillSelected =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            tui.showMainViewIf(mainStillSelected::get);
            assertEquals("process:two", tui.getContentViewKey());

            // Once Main is explicitly selected, later child output stays retained
            // by its owner but cannot reopen the child transcript.
            tui.showMainView();
            tui.updateActivityView("process:two", "process two", "late child output");
            assertTrue(tui.isMainContentView());
            assertEquals(java.util.List.of("retained parent transcript"),
                    tui.getContentViewLines());
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
    void viewRefreshWaitingForDrawLockCannotOverwriteANewPicker() throws Exception {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-picker-view-race-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-picker-view-race-queue"), new TerminalRenderer(false));
            tui.rememberMainTranscriptLine("retained transcript");
            for (boolean main : new boolean[] { true, false }) {
                java.util.concurrent.FutureTask<Void> refresh = new java.util.concurrent.FutureTask<>(() -> {
                    if (main) tui.showMainViewIf(() -> true);
                    else tui.showActivityView("process:late", "late process", "late output");
                    return null;
                });
                Thread worker = new Thread(refresh, "delayed-view-refresh");
                worker.setDaemon(true);
                synchronized (tui.getDrawLock()) {
                    worker.start();
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals(Thread.State.BLOCKED, worker.getState(), "refresh must reach the draw lock");
                    tui.showTemporaryWindow("Provider and model", java.util.List.of("first model"));
                }
                refresh.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("__temporary__", tui.getContentViewKey(),
                        "a refresh queued before picker entry must recheck ownership under the draw lock");
                assertTrue(tui.getVisibleContentLines().get(0).contains("Provider and model"));
                tui.closeTemporaryWindow();
                assertEquals(java.util.List.of("retained transcript"), tui.getContentViewLines());
            }
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

    @Test
    void sidebarLatestShortcutAppearsWhenScrolledUpAndClickSnapsBackToNewestCards() {
        BackgroundProcessManager processes =
                new BackgroundProcessManager("tui-sidebar-latest-test");
        try {
            KompileTui tui = new KompileTui(
                    new BackgroundTaskManager(), processes,
                    new MessageQueue("tui-sidebar-latest-queue"), new TerminalRenderer(false));
            ChatUiConfig config = ChatUiConfig.classic();
            config.applyPreset("focused");
            config.set("layout.minChatColumns", "20");
            config.set("sidebar.wrap", "false");
            tui.setUiConfig(config);
            assertTrue(KompileTui.paneLayout(0, config).split(), "test requires the split pane layout");

            for (int i = 0; i < 60; i++) {
                tui.upsertDisplayBlock(new ChatDisplayBlock("t" + i, "tool", "read", "diff " + i, "diff " + i,
                        List.of(), false, false));
            }

            int controlY = tui.sidebarBottom() - 1;
            int paneStart = tui.getChatColumns() + 1;
            int paneEnd = KompileTui.transcriptColumns(0);
            java.util.function.IntPredicate hitAt = x -> tui.handleSidebarLatestClick(x, controlY);

            // Following the tail: no shortcut anywhere in the sidebar pane.
            assertFalse(tui.isSidebarLatestControlVisible());
            for (int x = paneStart; x < paneEnd; x++) assertFalse(hitAt.test(x), "shortcut click at x=" + x);

            // Wheel up inside the pane: the floating shortcut appears.
            assertTrue(tui.scrollSidebarAt(paneStart, controlY, -30));
            assertTrue(tui.isSidebarLatestControlVisible());

            // Clicking outside the control span does not jump; the span itself does.
            assertFalse(hitAt.test(paneStart), "left edge must not be part of the control");
            boolean jumped = false;
            for (int x = paneStart; x < paneEnd; x++) if (hitAt.test(x)) { jumped = true; break; }
            assertTrue(jumped, "clicking the control must jump back to the newest cards");
            assertFalse(tui.isSidebarLatestControlVisible());
        } finally {
            processes.close();
        }
    }
}
