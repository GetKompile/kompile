package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tui.StatusBar;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StandardChatActivityPanelTest {

    @Test
    void reservesBoundedRowsWithoutChangingThemForActivity() {
        assertEquals(1, StandardChatActivityPanel.reservedRowsForTerminal(12, 100));
        assertEquals(3, StandardChatActivityPanel.reservedRowsForTerminal(16, 100));
        assertEquals(3, StandardChatActivityPanel.reservedRowsForTerminal(24, 100));
        assertEquals(6, StandardChatActivityPanel.reservedRowsForTerminal(80, 100));
    }

    @Test
    void rendersTreeAndOpensProcessOutputInTranscriptView() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-process-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);

            processes.registerVirtual(
                    ProcessKind.COMMAND,
                    "synthetic-command",
                    "Compile the CLI",
                    Map.of("source", "test"));
            panel.refresh();

            assertEquals(2, panel.currentMenuItems().size());
            assertTrue(panel.currentMenuItems().get(0).label().contains("Main chat"));
            assertTrue(panel.currentMenuItems().get(1).label().contains("Compile the CLI"));
            assertTrue(panel.selectNext());
            assertTrue(panel.isFocused());
            assertTrue(panel.inspectSelected().contains("synthetic-command"));
            assertFalse(panel.isViewingMain());
            assertEquals("proc-001 is a non-owned watcher; inspect/logs only",
                    panel.killSelected());

            assertTrue(panel.selectPrevious());
            StandardChatActivityPanel.ActivityView main = panel.openSelectedView();
            assertNotNull(main);
            assertTrue(main.main());
            assertTrue(panel.isViewingMain());

            panel.clearSelection();
            assertFalse(panel.isFocused());
        } finally {
            processes.close();
        }
    }

    @Test
    void completedProcessesLeaveLivePaneAndOwnedRunningProcessCanBeKilled() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-live-process-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            BackgroundProcessManager.ProcessEntry completed = processes.registerVirtual(
                    ProcessKind.COMMAND, "done", "Old completed process", Map.of());
            assertTrue(processes.complete(completed.getId()));
            panel.refresh();
            assertFalse(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("Old completed process")));

            BackgroundProcessManager.ProcessEntry running = processes.launch(
                    "sleep 30", "Killable managed process", java.nio.file.Path.of("."));
            panel.refresh();
            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView opened = panel.openSelectedView();
            assertNotNull(opened);
            assertFalse(opened.main());
            assertEquals("kill requested for " + running.getId(), panel.killSelected());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (running.isRunning() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(running.isRunning());
            StandardChatActivityPanel.ActivityView completedView = panel.currentView();
            assertFalse(completedView.main(),
                    "an explicitly opened process log should remain visible after exit");
            assertTrue(completedView.title().contains(running.getId()));
        } finally {
            processes.close();
        }
    }

    @Test
    void actionableProcessSortsAheadOfNewerWatcher() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-actionable-order-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);
            BackgroundProcessManager.ProcessEntry process = processes.launch(
                    "sleep 30", "Actionable build", java.nio.file.Path.of("."));
            processes.registerVirtual(
                    ProcessKind.JUDGE, "judge", "Newer informational watcher", Map.of());

            panel.refresh();

            assertTrue(panel.currentMenuItems().get(1).label().contains(process.getId()),
                    "bounded pane must show the killable process before a newer watcher");
        } finally {
            processes.close();
        }
    }

    @Test
    void preservesExplicitParentChildProcessHierarchy() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-tree-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            BackgroundProcessManager.ProcessEntry parent = processes.registerVirtual(
                    ProcessKind.COMMAND, "mvn test", "Parent build", Map.of());
            BackgroundProcessManager.ProcessEntry child = processes.registerVirtual(
                    ProcessKind.COMMAND, "surefire", "Child tests",
                    Map.of("parentProcessId", parent.getId()));

            var items = panel.activityItems();
            StandardChatActivityPanel.ActivityItem parentItem = items.stream()
                    .filter(item -> item.id().equals(parent.getId()))
                    .findFirst().orElseThrow();
            StandardChatActivityPanel.ActivityItem childItem = items.stream()
                    .filter(item -> item.id().equals(child.getId()))
                    .findFirst().orElseThrow();

            assertEquals(StandardChatActivityPanel.MAIN_KEY, parentItem.parentKey());
            assertEquals(1, parentItem.depth());
            assertEquals(parentItem.key(), childItem.parentKey());
            assertEquals(2, childItem.depth());

            panel.refresh();
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("  └─ [" + child.getId() + "]")));
        } finally {
            processes.close();
        }
    }

    @Test
    void keepsCompletedSubagentsVisibleAsRecentActivity() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-subagent-test");
        try {
            TerminalRenderer renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);

            bar.registerSubagent("explore-1", "explore", "Trace process management");
            String toolTranscript = renderer.renderSubagentToolCall(
                    "read", "{\"file_path\":\"AGENTS.md\"}",
                    ToolResult.success("AGENTS.md", "line one\nline two", Map.of("totalLines", 2)));
            bar.appendSubagentActivity("explore-1", "Read AGENTS.md ✓ 2 lines", toolTranscript);
            bar.unregisterSubagent("explore-1");
            panel.refresh();

            StandardChatActivityPanel.ActivityItem item = panel.activityItems().stream()
                    .filter(candidate -> candidate.id().equals("explore-1"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(StandardChatActivityPanel.ActivityKind.SUBAGENT, item.kind());
            assertFalse(item.active());
            assertTrue(item.status().startsWith("Read AGENTS.md"));
            assertTrue(panel.currentMenuItems().stream().anyMatch(menu ->
                    menu.label().contains("Trace process management")));

            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("Read AGENTS.md"));
            assertTrue(view.content().contains("line one"));
            assertTrue(view.content().contains("line two"));
            assertTrue(view.content().contains("totalLines=2"));
            assertEquals("explore-1", panel.viewedSubagentId());
        } finally {
            processes.close();
        }
    }

    @Test
    void mirrorsLiveSubagentActivityInlineAndExplainsBackgroundInspection() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-live-subagent-transcript-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            tasks.startTask("Parent turn");
            bar.registerSubagent("research-1", "researcher", "Check provider docs");
            bar.updateSubagentStatus("research-1", "responding");
            bar.appendSubagentActivity("research-1", "Searching docs", "  searched provider docs");
            bar.appendSubagentOutput("research-1", "live answer chunk");

            String foreground = panel.inlineSubagentTranscript("research-1");
            assertTrue(foreground.contains("Subagent [research-1] researcher"));
            assertTrue(foreground.contains("Searching docs"));
            assertTrue(foreground.contains("searched provider docs"));
            assertTrue(foreground.contains("live answer chunk"));
            assertTrue(foreground.contains("Ctrl+B background"));
            assertTrue(foreground.contains("↓ then Enter to inspect below"));

            assertNotNull(tasks.requestBackground());
            String backgrounded = panel.inlineSubagentTranscript("research-1");
            assertTrue(backgrounded.contains("Backgrounded"));
            assertTrue(backgrounded.contains("output continues in the subagent row below"));
            assertFalse(backgrounded.contains("live answer chunk"),
                    "detached output must stop repainting the main transcript block");
            panel.refresh();
            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("live answer chunk"),
                    "the selectable subagent row must retain the full live transcript");
        } finally {
            processes.close();
        }
    }

    @Test
    void deleteCancelsSelectedRunningSubagentButNotRecentOne() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-subagent-cancel-test");
        AtomicReference<String> cancelled = new AtomicReference<>();
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);
            panel.setSubagentRunner(new SubagentRunner() {
                @Override
                public String runSubagent(
                        AgentConfig agent, String prompt, ToolContext parentContext) {
                    return "";
                }

                @Override
                public boolean canCancel(String subagentId) {
                    return "explore-running".equals(subagentId);
                }

                @Override
                public boolean cancel(String subagentId) {
                    cancelled.set(subagentId);
                    return canCancel(subagentId);
                }
            });

            bar.registerSubagent("explore-running", "explore", "Trace cancellation");
            panel.refresh();
            assertTrue(panel.selectNext());
            assertTrue(panel.activityItems().stream()
                    .anyMatch(item -> item.id().equals("explore-running") && item.killable()));
            assertEquals("cancel requested for subagent explore-running", panel.killSelected());
            assertEquals("explore-running", cancelled.get());

            bar.unregisterSubagent("explore-running");
            panel.refresh();
            assertTrue(panel.selectNext());
            assertEquals("subagent is no longer running: explore-running", panel.killSelected());
        } finally {
            processes.close();
        }
    }

    @Test
    void toolRowsShowActionAndOutcomeAndOpenFullResult() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-tool-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            String input = "{\"file_path\":\"AGENTS.md\",\"offset\":1,\"limit\":250}";
            panel.recordToolStart("call-1", "mcp__kompile__read", input);
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("Read AGENTS.md")));
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.status().contains("running")));

            panel.recordToolComplete("call-1", "mcp__kompile__read", input,
                    ToolResult.success("AGENTS.md", "file body",
                            Map.of("totalLines", 252, "linesShown", 250)));
            StandardChatActivityPanel.ActivityItem item = panel.activityItems().stream()
                    .filter(candidate -> candidate.id().equals("call-1"))
                    .findFirst().orElseThrow();
            assertEquals(StandardChatActivityPanel.ActivityKind.TOOL, item.kind());
            assertTrue(item.status().contains("totalLines=252"));

            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.title().contains("Read AGENTS.md"));
            assertTrue(view.content().contains("↳ content:"));
            assertTrue(view.content().contains("file body"));
        } finally {
            processes.close();
        }
    }

    @Test
    void mainPaneKeepsOnlyFourMostRecentActivityRows() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-bounded-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 6);

            for (int i = 1; i <= 6; i++) {
                String input = "{\"file_path\":\"file-" + i + ".txt\"}";
                panel.recordToolStart("call-" + i, "read", input);
                panel.recordToolComplete("call-" + i, "read", input,
                        ToolResult.success("file-" + i + ".txt", "ok"));
            }

            assertEquals(5, panel.currentMenuItems().size(),
                    "Main return control plus four recent activity rows");
            assertFalse(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("file-1.txt")));
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("file-6.txt")));
            assertTrue(panel.activityItems().stream()
                    .anyMatch(item -> item.label().contains("file-1.txt")),
                    "Older activity remains navigable even when hidden inline");
        } finally {
            processes.close();
        }
    }

    @Test
    void idlePaneStillOccupiesItsReservedRows() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-idle-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);

            panel.refresh();

            assertEquals(2, panel.currentMenuItems().size());
            assertTrue(panel.currentMenuItems().stream().anyMatch(item -> item.label()
                    .contains("No active processes or subagents")));
        } finally {
            processes.close();
        }
    }
}
