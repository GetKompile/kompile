package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KompileTuiSidebarTest {
    private static ChatUiConfig focused() { ChatUiConfig c = ChatUiConfig.classic(); c.applyPreset("focused"); return c; }
    private static ChatDisplayBlock result(String body) {
        return new ChatDisplayBlock("tool:one", "tool", "read", "Read file ✓", "Read file ✓\n" + body,
                List.of(new ChatDisplayBlock.Section("content", "content", "Content", body)), false, false);
    }
    @Test void savedSidebarVisibilitySurvivesConfigReloadAndResize(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var path = root.resolve("ui.json");
        ChatUiConfig config = ChatUiConfig.load(List.of(path));
        config.applyPreset("focused");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("saved-sidebar", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("saved-sidebar")) {
            terminal.setSize(new Size(120, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("saved-sidebar"), new TerminalRenderer(true));
            try {
                tui.setUiConfig(config); tui.start(terminal);
                assertTrue(tui.isSidebarVisible());
                tui.toggleSidebar();
                tui.getUiConfig().saveDefault();
                tui.setUiConfig(ChatUiConfig.load(List.of(path)));
                assertFalse(tui.isSidebarVisible());
                terminal.setSize(new Size(60, 24)); tui.handleResize();
                terminal.setSize(new Size(120, 24)); tui.handleResize();
                assertFalse(tui.isSidebarVisible(), "Resize must not discard the saved hidden preference");
                tui.toggleSidebar(); tui.getUiConfig().saveDefault();
                tui.setUiConfig(ChatUiConfig.load(List.of(path)));
                assertTrue(tui.isSidebarVisible());
            } finally { tui.stop(); }
        }
    }

    @Test void actualSidebarDrawerAndInspectorFramesKeepTranscriptTokenColors() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var renderer = new TerminalRenderer(true);
        String input = "{\"file_path\":\"App.java\"}";
        var result = ai.kompile.cli.main.chat.tools.ToolResult.success("read", "public class App { int count = 42; }", java.util.Map.of());
        String summary = renderer.renderToolCallSummary("read", input, result);
        String classic = renderer.renderToolCallComplete("read", input, result);
        ChatDisplayBlock block = new ChatDisplayBlock("tool:color", "tool", "read", summary, classic,
                renderer.renderToolDisplaySections("read", input, result, "", 128_000), false, false);
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("styled-sidebar", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-color-frame")) {
            terminal.setSize(new Size(120, 32));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("sidebar-color-frame"), renderer);
            try {
                ChatUiConfig config = focused(); config.set("content.tool.sidebar", "expanded");
                config.set("content.arguments.sidebar", "off"); config.set("sidebar.follow", "manual");
                tui.setUiConfig(config); tui.start(terminal); tui.upsertDisplayBlock(block);
                VirtualTerminal frame = new VirtualTerminal(32, 120);
                for (int view = 0; view < 3; view++) {
                    if (view == 1) tui.openResultsDrawer();
                    if (view == 2) tui.openResultInspector();
                    tui.redrawContentView(); frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                    assertEquals(tui.scrollBottom() - 1, frame.getCursorRow(), frame::screenDump);
                    assertEquals(0, frame.getCursorCol(), frame::screenDump);
                    for (String token : List.of("Read", "public", "42")) {
                        var expected = java.util.Arrays.stream(classic.split("\\R")).map(org.jline.utils.AttributedString::fromAnsi)
                                .filter(line -> line.toString().contains(token)).findFirst().orElseThrow();
                        var expectedStyle = expected.styleAt(expected.toString().indexOf(token));
                        assertNotEquals(org.jline.utils.AttributedStyle.DEFAULT, expectedStyle);
                        boolean found = false;
                        for (int row = 0; row < 32; row++) {
                            var cells = org.jline.utils.AttributedString.fromAnsi(frame.getStyledRowFull(row));
                            int index = cells.toString().indexOf(token);
                            if (index >= 0 && expectedStyle.equals(cells.styleAt(index))) found = true;
                        }
                        assertTrue(found, "View " + view + " lost token style for " + token + "\n" + frame.screenDump());
                    }
                }
            } finally { tui.stop(); }
        }
    }

    @Test void currentTodoHighlightSurvivesSidebarDrawerAndInspector() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var renderer = new TerminalRenderer(true);
        String classic = renderer.renderTodoList(List.of(
                new ai.kompile.cli.main.chat.tools.TodoWriteTool.TodoItem("1", "Done", null, "completed", "medium"),
                new ai.kompile.cli.main.chat.tools.TodoWriteTool.TodoItem("2", "Active task", null, "in_progress", "medium"),
                new ai.kompile.cli.main.chat.tools.TodoWriteTool.TodoItem("3", "Waiting", null, "pending", "medium")));
        ChatDisplayBlock block = new ChatDisplayBlock("todos:current", "todos", "todowrite", "Todos", classic,
                List.of(new ChatDisplayBlock.Section("todos", "todos", "Todos", classic, classic)), false, false);
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("current-todo", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("current-todo-frame")) {
            terminal.setSize(new Size(120, 32));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("current-todo-frame"), renderer);
            try {
                ChatUiConfig config = focused(); config.set("content.todos.sidebar", "expanded");
                config.set("sidebar.follow", "manual");
                tui.setUiConfig(config); tui.start(terminal); tui.upsertDisplayBlock(block);
                VirtualTerminal frame = new VirtualTerminal(32, 120);
                var currentStyle = org.jline.utils.AttributedStyle.DEFAULT.bold().foreground(org.jline.utils.AttributedStyle.YELLOW);
                for (int view = 0; view < 3; view++) {
                    if (view == 1) tui.openResultsDrawer();
                    if (view == 2) tui.openResultInspector();
                    tui.redrawContentView(); frame.feed(output.toString(StandardCharsets.UTF_8)); output.reset();
                    for (String token : List.of("▶", "#2", "Active task", "[current]")) {
                        boolean found = false;
                        for (int row = 0; row < 32; row++) {
                            var cells = org.jline.utils.AttributedString.fromAnsi(frame.getStyledRowFull(row));
                            int index = cells.toString().indexOf(token);
                            if (index >= 0 && currentStyle.equals(cells.styleAt(index))) found = true;
                        }
                        assertTrue(found, "View " + view + " lost current-item highlight for " + token + "\n" + frame.screenDump());
                    }
                }
            } finally { tui.stop(); }
        }
    }

    @Test void sectionBarsUseDisplayColumnsAndRespectTheirReservedRows() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("bounded-section-bars")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("bounded-section-bars"), new TerminalRenderer(true));
            try {
                tui.getTopBar().setAgentName("模型界面".repeat(40));
                tui.getTopBar().setAlert("警告消息".repeat(40));
                tui.getStatusBar().setActiveAgent("模型界面".repeat(40));
                tui.getStatusBar().setMenuItems(java.util.stream.IntStream.range(0, 10)
                        .mapToObj(i -> new StatusBar.MenuItem("item-" + i, "模型界面".repeat(40), "e\u0301😀", false)).toList(),
                        "提示消息".repeat(40));
                for (int width : new int[]{8, 20, 60, 120, 240}) {
                    String bars = tui.getTopBar().render(width) + tui.getStatusBar().render(30, width, 3);
                    for (String row : bars.split("\\033\\[[0-9]+;1H")) {
                        if (row.isEmpty()) continue;
                        String text = ai.kompile.utils.AnsiConstants.stripAnsi(row);
                        assertTrue(new org.jline.utils.AttributedString(text).columnLength() <= width - 1,
                                "Section row overflow at " + width + ": " + text);
                    }
                    VirtualTerminal screen = new VirtualTerminal(30, width);
                    screen.feed("\033[24;1HTRANSCRIPT\033[25;1HINPUT");
                    screen.feed(bars);
                    assertTrue(screen.getRow(23).startsWith("TRANS"), screen::screenDump);
                    assertTrue(screen.getRow(24).startsWith("INPUT"), screen::screenDump);
                    assertEquals(width - 1, screen.getRow(2).stripTrailing().length());
                }
            } finally { tui.stop(); }
        }
    }

    @Test void resizeCollapsesToTranscriptAndRestoresConfiguredSectionsWithoutDebris() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("responsive-sections", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("responsive-sections")) {
            terminal.setSize(new Size(120, 32));
            MessageQueue queue = new MessageQueue("responsive-sections");
            queue.enqueue("QUEUED SECTION");
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, queue, new TerminalRenderer(true));
            try {
                ChatUiConfig config = focused(); config.set("layout.sidebarWidthPercent", "25");
                tui.setUiConfig(config);
                tui.setReservedMiddleRows(3);
                tui.getStatusBar().setMenuItems(List.of(new StatusBar.MenuItem("activity", "ACTIVITY SECTION", "running", false)), "ACTIVITY HINT");
                tui.setDashboard("DASHBOARD SECTION", List.of("dashboard content"));
                tui.start(terminal);
                tui.recordInScrollRegion("TRANSCRIPT SENTINEL");
                tui.upsertDisplayBlock(result("RESULT SECTION"));
                List<String> retained = tui.getContentViewLines();
                var paint = KompileTui.class.getDeclaredMethod("renderFrame", boolean.class, boolean.class);
                paint.setAccessible(true);
                VirtualTerminal screen = new VirtualTerminal(32, 120);
                for (Size size : List.of(new Size(120, 32), new Size(48, 12), new Size(120, 8),
                        new Size(80, 18), new Size(240, 60), new Size(40, 24), new Size(120, 32))) {
                    terminal.setSize(size); tui.handleResize(); screen.resize(size.getRows(), size.getColumns());
                    synchronized (tui.getDrawLock()) {
                        output.reset();
                        // Reflow debris must be erased even outside today's section footprints.
                        terminal.writer().print("\033[1;1HOLD SECTION FRAGMENT"); terminal.writer().flush();
                        paint.invoke(tui, false, true);
                        screen.feed(output.toString(StandardCharsets.UTF_8));
                        output.reset();
                        paint.invoke(tui, false, true);
                        screen.feed(output.toString(StandardCharsets.UTF_8));
                    }
                    String dump = screen.screenDump();
                    assertTrue(dump.contains("TRANSCRIPT SENTINEL"), dump);
                    assertFalse(dump.contains("OLD SECTION FRAGMENT"), dump);
                    assertEquals(retained, tui.getContentViewLines());
                    assertEquals(25, tui.getUiConfig().getSidebarWidthPercent());
                    assertTrue(tui.scrollTop() >= 1 && tui.scrollBottom() <= size.getRows(), dump);
                    if (tui.isCompactLayout()) {
                        assertEquals(1, tui.scrollTop());
                        assertEquals(0, tui.getQueueRegionRows()); assertEquals(0, tui.getReservedMiddleRows());
                        assertFalse(tui.isSidebarVisible());
                        for (String section : List.of("kompile", "ACTIVITY", "DASHBOARD", "QUEUED", "Read file"))
                            assertFalse(dump.contains(section), dump);
                    } else {
                        assertTrue(dump.contains("kompile"), dump);
                        assertTrue(dump.contains("ACTIVITY SECTION"), dump);
                        if (size.getColumns() >= 120) {
                            assertTrue(tui.isSidebarVisible());
                            assertEquals(size.getRows(), tui.sidebarBottom());
                            for (int row = tui.scrollTop() - 1; row < size.getRows(); row++)
                                assertEquals('│', screen.getRow(row).charAt(tui.getChatColumns()), dump);
                            assertTrue(tui.isSidebarCoordinate(tui.getChatColumns() + 2, size.getRows() - 1));
                            assertFalse(tui.isSidebarCoordinate(tui.getChatColumns() + 2, size.getRows()));
                            assertTrue(dump.contains("Read file"), dump);
                            assertTrue(dump.contains("DASHBOARD SECTION"), dump);
                        }
                    }
                }
            } finally { tui.stop(); }
        }
    }

    @Test void chromeSharesRemainingRowsWithoutStarvingTheAttachedTranscript() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("shared-row-budget", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("shared-row-budget")) {
            terminal.setSize(new Size(120, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("shared-row-budget"), new TerminalRenderer(true));
            try {
                tui.setUiConfig(focused());
                tui.attachLineReader(new InputPaneLineReader(terminal));
                tui.setReservedMiddleRows(1_000);
                tui.getTopBar().setChatTitleSupplier(() -> "Long title ".repeat(100));
                tui.start(terminal);
                for (int height : List.of(16, 20, 24, 32, 48, 80, 24)) {
                    terminal.setSize(new Size(120, height));
                    tui.handleResize();
                    for (boolean dashboard : List.of(false, true)) {
                        if (dashboard) tui.setDashboard("Dashboard", List.of("detail"));
                        else tui.clearDashboard();
                        int transcriptRows = tui.transcriptBottom() - tui.scrollTop() + 1;
                        assertTrue(transcriptRows >= Math.min(4, height / 3), "height=" + height);
                        if (height >= 20) assertTrue(transcriptRows >= height / 3, "height=" + height);
                        assertTrue(tui.transcriptBottom() < tui.inputTop());
                        assertTrue(tui.scrollBottom() < tui.queueTop());
                        assertTrue(tui.queueBottom() + tui.getReservedMiddleRows() + StatusBar.STATUS_HEIGHT <= height);
                        assertTrue(tui.getReservedMiddleRows() < height / 2);
                    }
                }
            } finally { tui.stop(); }
        }
    }

    @Test void geometryFallsBackWithoutStarvingChat() {
        assertFalse(KompileTui.paneLayout(80, focused()).split());
        KompileTui.PaneLayout wide = KompileTui.paneLayout(120, focused());
        assertTrue(wide.split()); assertTrue(wide.chatColumns() >= 60);
        assertEquals(119, wide.chatColumns() + wide.sidebarColumns() + 1);
        assertFalse(KompileTui.paneLayout(120, ChatUiConfig.classic()).split());
    }
    @Test void livePolicySwitchReprojectsRetainedResultsAndInspectorIsIndependent() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-projection")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("sidebar-projection"), new TerminalRenderer(false));
            tui.recordInScrollRegion("user chat");
            tui.upsertDisplayBlock(result("TOOL BODY"));
            assertTrue(String.join("\n", tui.getContentViewLines()).contains("TOOL BODY"));
            tui.setUiConfig(focused());
            assertEquals(List.of("user chat"), tui.getContentViewLines());
            tui.openResultsDrawer();
            assertTrue(tui.isSidebarFocused());
            assertTrue(tui.expandSidebarSelection());
            assertTrue(tui.isResultsView());
            assertFalse(tui.isSidebarFocused());
            assertTrue(String.join("\n", tui.getContentViewLines()).contains("TOOL BODY"));
            tui.upsertDisplayBlock(result("UPDATED BODY"));
            assertTrue(String.join("\n", tui.getContentViewLines()).contains("UPDATED BODY"));
            tui.closeResultsView();
            assertEquals(List.of("user chat"), tui.getContentViewLines());
            tui.setUiConfig(ChatUiConfig.classic());
            assertTrue(String.join("\n", tui.getContentViewLines()).contains("UPDATED BODY"));
            tui.stop();
        }
    }
    @Test void changedRetentionLimitAppliesImmediatelyWithoutDeletingChat() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-limits")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("sidebar-limits"), new TerminalRenderer(false));
            try {
                ChatUiConfig config = ChatUiConfig.classic(); config.applyPreset("verbose");
                tui.setUiConfig(config);
                for (int i = 0; i < 4; i++) tui.upsertDisplayBlock(new ChatDisplayBlock("edit:" + i, "tool", "edit",
                        "EDIT-" + i, "EDIT-" + i + "\n  ↳ Diff:\n+change" + i,
                        List.of(new ChatDisplayBlock.Section("diff", "diff", "Diff", "+change" + i)), false, false));
                List<String> chat = tui.getContentViewLines();
                config.set("sidebar.maxItems", "2");
                tui.setUiConfig(config);
                assertEquals(chat, tui.getContentViewLines(), "Inspector eviction must not delete transcript text");
                tui.openResultsDrawer();
                String drawer = String.join("\n", tui.getContentViewLines());
                assertTrue(drawer.contains("EDIT-0"), drawer); // Selection is protected.
                assertTrue(drawer.contains("EDIT-3"), drawer);
                assertFalse(drawer.contains("EDIT-1") || drawer.contains("EDIT-2"), drawer);
                assertTrue(drawer.contains("older cards omitted"), drawer);
            } finally { tui.stop(); }
        }
    }

    @Test void cardEvictionDoesNotEraseStillRetainedChatOnUiChange() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-eviction")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("sidebar-eviction"), new TerminalRenderer(false));
            try {
                for (int i = 0; i < 300; i++) tui.upsertDisplayBlock(ChatDisplayBlock.text("assistant:" + i, "assistant", "MESSAGE-" + i));
                List<String> retained = tui.getContentViewLines();
                ChatUiConfig config = ChatUiConfig.classic(); config.set("layout.mode", "auto");
                tui.setUiConfig(config);
                assertEquals(retained, tui.getContentViewLines());
                assertTrue(tui.getContentViewLines().contains("MESSAGE-5"));
            } finally { tui.stop(); }
        }
    }
    @Test void uiPreviewAndCancelRestoreEvictedAssistantText() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("ui-preview-retention")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("ui-preview-retention"), new TerminalRenderer(false));
            try {
                for (int i = 0; i < 300; i++) tui.upsertDisplayBlock(
                        ChatDisplayBlock.text("assistant:" + i, "assistant", "MESSAGE-" + i + "\nBODY-" + i));
                List<String> original = tui.getContentViewLines();
                tui.updateTemporaryWindow("UI preview", List.of("Choose a setting"));
                for (String mode : List.of("none", "summary", "full", "none")) {
                    ChatUiConfig preview = ChatUiConfig.classic(); preview.set("content.assistant.inline", mode);
                    tui.setUiConfig(preview);
                }
                tui.setUiConfig(ChatUiConfig.classic()); // Cancel restores the saved preferences.
                tui.closeTemporaryWindow();
                assertEquals(original, tui.getContentViewLines(), "Previews must not destroy retained source text");
            } finally { tui.stop(); }
        }
    }

    @Test void inspectorBudgetCannotDeleteAnUpdatedTranscriptBlock() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("ui-card-budget")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("ui-card-budget"), new TerminalRenderer(false));
            try {
                tui.upsertDisplayBlock(ChatDisplayBlock.text("selected", "assistant", "SELECTED"));
                tui.upsertDisplayBlock(ChatDisplayBlock.text("large", "assistant", "OLD ASSISTANT"));
                tui.upsertDisplayBlock(new ChatDisplayBlock("large", "assistant", "", "",
                        "VISIBLE ASSISTANT\n" + "x".repeat(2_100_000), List.of(), false, false));
                // The bounded inspector cache may reject/evict a card. The chat owns its text.
                assertTrue(tui.getContentViewLines().contains("VISIBLE ASSISTANT"));
                tui.setUiConfig(focused());
                assertTrue(tui.getContentViewLines().contains("VISIBLE ASSISTANT"));
            } finally { tui.stop(); }
        }
    }

    @Test void hiddenTranscriptSourcesStayBoundedAndUiCannotResurrectTrimmedRows() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("ui-source-budget")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("ui-source-budget"), new TerminalRenderer(false));
            try {
                ChatUiConfig hidden = ChatUiConfig.classic(); hidden.set("content.assistant.inline", "none");
                tui.setUiConfig(hidden);
                String body = java.util.stream.IntStream.range(0, 2005).mapToObj(i -> "LINE-" + i)
                        .collect(java.util.stream.Collectors.joining("\n"));
                tui.upsertDisplayBlock(ChatDisplayBlock.text("large", "assistant", body));
                assertTrue(tui.getContentViewLines().isEmpty());
                tui.setUiConfig(ChatUiConfig.classic());
                assertEquals(2000, tui.getContentViewLines().size());
                assertEquals("LINE-5", tui.getContentViewLines().get(0));
                tui.setUiConfig(hidden); tui.setUiConfig(ChatUiConfig.classic());
                assertEquals(2000, tui.getContentViewLines().size());
                assertEquals("LINE-5", tui.getContentViewLines().get(0));
            } finally { tui.stop(); }
        }
    }

    @Test void uiCloseRefreshesResultsAndInspectorInsteadOfRestoringStaleSnapshots() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager("ui-derived-views")) {
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes,
                    new MessageQueue("ui-derived-views"), new TerminalRenderer(false));
            try {
                tui.setUiConfig(focused()); tui.upsertDisplayBlock(result("ORIGINAL BODY"));
                tui.openResultsDrawer(); tui.updateTemporaryWindow("UI", List.of("Choose"));
                ChatUiConfig hide = focused(); hide.set("content.tool.sidebar", "off");
                tui.setUiConfig(hide); tui.closeTemporaryWindow();
                assertFalse(String.join("\n", tui.getContentViewLines()).contains("Read file"));
                tui.setUiConfig(focused()); tui.openResultInspector();
                tui.updateTemporaryWindow("UI", List.of("Choose"));
                tui.upsertDisplayBlock(result("UPDATED DURING UI")); tui.closeTemporaryWindow();
                assertTrue(String.join("\n", tui.getContentViewLines()).contains("UPDATED DURING UI"));
                assertFalse(String.join("\n", tui.getContentViewLines()).contains("ORIGINAL BODY"));
            } finally { tui.stop(); }
        }
    }

    @Test void splitFramebufferConfinesOutputAndResizeKeepsRetainedData() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("sidebar", "xterm", output, StandardCharsets.UTF_8);
             BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-frame")) {
            terminal.setSize(new Size(120, 24));
            KompileTui tui = new KompileTui(new BackgroundTaskManager(), processes, new MessageQueue("sidebar-frame"), new TerminalRenderer(true));
            try {
                tui.setUiConfig(focused());
                tui.start(terminal);
                tui.recordInScrollRegion("CHAT ONLY");
                tui.upsertDisplayBlock(result("OUTPUT MUST STAY IN SIDEBAR"));
                tui.toggleResultSelection();
                tui.toggleSidebarSection("content");
                tui.redrawContentView();
                VirtualTerminal frame = new VirtualTerminal(24, 120);
                frame.feed(output.toString(StandardCharsets.UTF_8));
                String dump = frame.screenDump();
                assertTrue(dump.contains("CHAT ONLY"), dump);
                assertTrue(dump.contains("Read file"), dump);
                for (int row = tui.scrollTop() - 1; row < tui.inputTop() - 1; row++) {
                    assertFalse(frame.getRow(row).substring(0, tui.getChatColumns()).contains("OUTPUT MUST"), dump);
                }
                assertFalse(tui.isTranscriptCoordinate(110, tui.scrollTop() - 1));
                tui.toggleSidebarFocus();
                assertTrue(tui.isSidebarFocused());
                terminal.setSize(new Size(72, 24)); tui.handleResize();
                assertFalse(tui.isSidebarVisible());
                assertFalse(tui.isSidebarFocused());
                assertFalse(tui.navigateSidebar(1));
                assertFalse(tui.expandSidebarSelection());
                tui.openResultInspector();
                assertTrue(String.join("\n", tui.getContentViewLines()).contains("OUTPUT MUST STAY IN SIDEBAR"));
            } finally { tui.stop(); }
        }
    }
}
