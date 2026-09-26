package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.activity.ProjectActivityView;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.cli.main.chat.tui.VirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StandardChatActivityPanelTest {

    @Test
    void completedRowsExpireOnIdleCallbackWithoutRemovingHistoryOrRunningWork() {        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("activity-expiry")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            AtomicReference<java.time.Instant> now = new AtomicReference<>(java.time.Instant.now());
            java.util.List<Runnable> timers = new java.util.ArrayList<>();
            java.util.List<Long> delays = new java.util.ArrayList<>();
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar,
                    () -> 6, now::get, (delay, callback) -> { delays.add(delay); timers.add(callback); });
            panel.recordToolComplete("done", "read", "{}", ToolResult.success("retained result"));
            panel.recordToolStart("running", "process", "{}");
            assertEquals(10_000L, delays.get(0));
            assertTrue(panel.currentMenuItems().stream().anyMatch(row -> row.id().equals("tool:done")));
            now.set(now.get().plusMillis(9_999));
            panel.refresh();
            assertTrue(panel.currentMenuItems().stream().anyMatch(row -> row.id().equals("tool:done")));
            now.set(now.get().plusMillis(1));
            timers.remove(0).run(); // no input or process event triggers this refresh
            assertFalse(panel.currentMenuItems().stream().anyMatch(row -> row.id().equals("tool:done")));
            assertTrue(panel.currentMenuItems().stream().anyMatch(row -> row.id().equals("tool:running")));

            bar.registerSubagent("child", "explorer", "retained child");
            bar.unregisterSubagent("child");
            now.set(bar.getRecentSubagents().get(0).getCompletedAt());
            panel.refresh();
            assertTrue(panel.activityItems().stream().anyMatch(row -> row.id().equals("child")));
            now.set(now.get().plusSeconds(10));
            timers.remove(0).run();
            assertFalse(panel.activityItems().stream().anyMatch(row -> row.id().equals("child")));
            assertEquals(1, bar.getRecentSubagents().size(), "history must remain available");
            bar.registerSubagent("child", "explorer", "follow-up");
            panel.refresh();
            assertTrue(panel.activityItems().stream().anyMatch(row -> row.id().equals("child") && row.active()));
        }
    }

    @Test
    void openCompletedViewRemainsWhileItsFeedbackExpires() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("view-expiry")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            AtomicReference<java.time.Instant> now = new AtomicReference<>(java.time.Instant.now());
            java.util.List<Runnable> timers = new java.util.ArrayList<>();
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar,
                    () -> 6, now::get, (delay, callback) -> timers.add(callback));
            panel.recordToolComplete("done", "read", "{}", ToolResult.success("retained result"));
            assertTrue(panel.selectNext());
            assertNotNull(panel.openSelectedView());
            assertTrue(bar.render(40, 120).contains("viewing done"));
            now.set(now.get().plusSeconds(10));
            timers.remove(0).run();
            assertFalse(bar.render(40, 120).contains("viewing done"));
            assertTrue(panel.activityItems().stream().anyMatch(row -> row.id().equals("done")));
            panel.returnToMain();
            assertFalse(panel.activityItems().stream().anyMatch(row -> row.id().equals("done")));
        }
    }

    @Test
    void newerPanelFeedbackGetsItsFullLifetimeAfterEarlierCompletionTimer() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("feedback-expiry")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            AtomicReference<java.time.Instant> now = new AtomicReference<>(java.time.Instant.now());
            java.util.List<Runnable> timers = new java.util.ArrayList<>();
            java.util.List<Long> delays = new java.util.ArrayList<>();
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar,
                    () -> 6, now::get, (delay, callback) -> { delays.add(delay); timers.add(callback); });
            panel.recordToolComplete("done", "read", "{}", ToolResult.success("result"));
            now.set(now.get().plusSeconds(5));
            panel.selectNext();
            panel.openSelectedView();
            now.set(now.get().plusSeconds(5));
            timers.remove(0).run();
            assertTrue(bar.render(40, 120).contains("viewing done"));
            assertEquals(5_000L, delays.get(delays.size() - 1));
            now.set(now.get().plusSeconds(5));
            timers.remove(0).run();
            assertFalse(bar.render(40, 120).contains("viewing done"));
        }
    }

    @Test
    void reservesBoundedRowsWithoutChangingThemForActivity() {
        assertEquals(1, StandardChatActivityPanel.reservedRowsForTerminal(12, 100));
        assertEquals(3, StandardChatActivityPanel.reservedRowsForTerminal(16, 100));
        assertEquals(3, StandardChatActivityPanel.reservedRowsForTerminal(24, 100));
        assertEquals(6, StandardChatActivityPanel.reservedRowsForTerminal(80, 100));
    }

    @Test
    void slashCompletionsTemporarilyReplaceReservedActivityRows() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-completion-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 3);
            processes.registerVirtual(
                    ProcessKind.COMMAND, "build", "Retained activity", Map.of());
            panel.refresh();
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("Retained activity")));

            assertTrue(panel.updateCompletions(java.util.List.of(
                    new ChatCompleter.CompletionItem("/help", "Show help"),
                    new ChatCompleter.CompletionItem("/status", "Show status"),
                    new ChatCompleter.CompletionItem("/agent", "Switch agent"),
                    new ChatCompleter.CompletionItem("/tools", "List tools"))));

            assertTrue(panel.hasCompletions());
            assertEquals(2, panel.currentMenuItems().size(),
                    "two candidate rows plus one hint must fit the fixed three-row panel");
            assertEquals("/help", panel.currentMenuItems().get(0).label());
            assertTrue(panel.currentMenuItems().get(1).status().contains("+2 more"));
            assertTrue(bar.render(40, 120).contains("type to filter"));
            assertFalse(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("Retained activity")));

            assertTrue(panel.updateCompletions(java.util.List.of()));
            assertFalse(panel.hasCompletions());
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("Retained activity")),
                    "clearing slash completion must restore the activity panel");
        } finally {
            processes.close();
        }
    }

    @Test
    void filteringAndClearingCompletionsErasePreviousFrames() {
        for (int panelRows : new int[]{1, 3, 6}) {
            BackgroundTaskManager tasks = new BackgroundTaskManager();
            BackgroundProcessManager processes =
                    new BackgroundProcessManager("completion-frame-test-" + panelRows);
            try {
                StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
                StandardChatActivityPanel panel = new StandardChatActivityPanel(
                        tasks, processes, bar, () -> panelRows);
                var candidates = java.util.List.of(
                        new ChatCompleter.CompletionItem("/help", "Show help"),
                        new ChatCompleter.CompletionItem("/status", "Show status"),
                        new ChatCompleter.CompletionItem("/agent", "Switch agent"),
                        new ChatCompleter.CompletionItem("/tools", "List tools"),
                        new ChatCompleter.CompletionItem("/model", "Choose model"));
                var frames = java.util.List.of(candidates, candidates.subList(0, 1),
                        candidates, java.util.List.<ChatCompleter.CompletionItem>of());
                VirtualTerminal screen = new VirtualTerminal(40, 120);
                String transcript = "\033[1;1HTranscript must remain intact";
                screen.feed(transcript);
                for (var completions : frames) {
                    panel.updateCompletions(completions);
                    String frame = bar.render(40, 120);
                    screen.feed(frame);
                    VirtualTerminal fresh = new VirtualTerminal(40, 120);
                    fresh.feed(transcript);
                    fresh.feed(frame);
                    assertEquals(fresh.screenDump(), screen.screenDump(),
                            "stale completion frame with panel rows=" + panelRows
                                    + ", candidates=" + completions.size());
                }
            } finally {
                processes.close();
            }
        }
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
    void opensUnifiedJudgeChatAndFollowsEnforcerActivity() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-aux-repl-test");
        AuxiliaryChatRepl judge = AuxiliaryChatRepl.observer(
                AuxiliaryChatRepl.Kind.JUDGE, "ready");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);
            panel.registerAuxiliaryRepl(judge);

            judge.observe("[judge advisory] read · ALLOW");
            ChatRepl.appendSupervisorActivity(
                    judge, "[tool decision] BLOCK · policy violation");
            long replRows = panel.activityItems().stream()
                    .filter(item -> item.kind() == StandardChatActivityPanel.ActivityKind.REPL)
                    .count();
            assertEquals(1, replRows,
                    "judge and enforcer activity should share one supervisory REPL");
            for (int i = 0; i < panel.activityItems().size()
                    && !"repl:judge".equals(panel.getSelectedKey()); i++) {
                assertTrue(panel.selectNext());
            }
            assertEquals("repl:judge", panel.getSelectedKey());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();

            assertNotNull(view);
            assertFalse(view.main());
            assertTrue(view.title().contains("Judge chat"));
            assertTrue(view.content().contains("judge advisory"));
            assertTrue(view.content().contains("[judge]"));
            assertTrue(view.content().contains("[tool decision] BLOCK"));

            judge.observe("[judge turn decision] ALLOW");
            assertTrue(panel.currentView().content().contains("ALLOW"),
                    "the selected auxiliary view must read the current in-process transcript");
            assertEquals("judge is an in-process supervisory chat; inspect only",
                    panel.killSelected());
        } finally {
            judge.close();
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
    void evictedViewedSubagentCanonicalizesBackToMain() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-evicted-subagent-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            bar.registerSubagent("expired-child", "explore", "Old retained child");
            panel.refresh();
            assertTrue(panel.selectNext());
            assertFalse(panel.openSelectedView().main());
            assertEquals("expired-child", panel.viewedSubagentId());
            bar.unregisterSubagent("expired-child");

            // StatusBar retains only eight completed children. Evict the viewed
            // child without explicitly changing the panel's viewedKey.
            for (int i = 0; i < 8; i++) {
                String id = "replacement-" + i;
                bar.registerSubagent(id, "explore", "Replacement " + i);
                bar.unregisterSubagent(id);
            }
            assertFalse(panel.activityItems().stream()
                    .anyMatch(item -> item.id().equals("expired-child")));

            StandardChatActivityPanel.ActivityView recovered = panel.currentView();
            assertTrue(recovered.main());
            assertTrue(panel.isViewingMain());
            assertEquals("", panel.viewedSubagentId(),
                    "renderer fallback and input routing must share Main state");
        } finally {
            processes.close();
        }
    }

    @Test
    void followUpsStayWithInteractiveChildButExpiredChildFallsThroughToMain() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-subagent-follow-up-test");
        AtomicBoolean acceptsFollowUps = new AtomicBoolean(true);
        AtomicReference<String> acceptedMessage = new AtomicReference<>();
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);
            panel.setSubagentRunner(new SubagentRunner() {
                @Override
                public String runSubagent(
                        AgentConfig agent, String prompt, ToolContext parentContext) {
                    return "";
                }

                @Override
                public boolean sendMessage(String subagentId, String message) {
                    if (!acceptsFollowUps.get()) return false;
                    acceptedMessage.set(subagentId + ":" + message);
                    return true;
                }
            });

            bar.registerSubagent("interactive-child", "explore", "Retained child");
            panel.refresh();
            assertTrue(panel.selectNext());
            assertTrue(bar.render(40, 180)
                    .contains("Enter opens subagent; then type sends follow-up"));
            assertFalse(panel.openSelectedView().main());
            assertTrue(bar.render(40, 180)
                    .contains("type sends to open subagent when supported"));

            assertTrue(panel.trySendMessageToViewedSubagent("first follow-up"));
            assertEquals("interactive-child:first follow-up", acceptedMessage.get());
            assertFalse(panel.isViewingMain());

            acceptsFollowUps.set(false);
            assertFalse(panel.trySendMessageToViewedSubagent("continue in parent"),
                    "false tells ChatRepl to dispatch this same message to the parent");
            assertTrue(panel.isViewingMain());
            assertEquals("", panel.viewedSubagentId());
            assertTrue(bar.getActiveSubagents().get(0).getTranscript()
                    .contains("Follow-up was not sent"));
            assertTrue(bar.render(40, 180).contains("continuing in Main chat"));
        } finally {
            processes.close();
        }
    }

    @Test
    void mirrorsLiveSubagentActivityInlineAndExplainsBackgroundInspection() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        AtomicBoolean blockingSubagentInvocation = new AtomicBoolean(false);
        tasks.setBackgroundableCheck(blockingSubagentInvocation::get);
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-live-subagent-transcript-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);
            AtomicReference<String> backgroundFollowUp = new AtomicReference<>();
            panel.setSubagentRunner(new SubagentRunner() {
                @Override
                public String runSubagent(
                        AgentConfig agent, String prompt, ToolContext parentContext) {
                    return "";
                }

                @Override
                public boolean sendMessage(String subagentId, String message) {
                    backgroundFollowUp.set(subagentId + ":" + message);
                    return true;
                }
            });

            tasks.startTask("Parent turn");
            assertFalse(tasks.isCurrentTaskBackgroundable(),
                    "model thinking alone must not advertise Ctrl+B");
            bar.registerSubagent("research-1", "researcher", "Check provider docs");
            bar.updateSubagentStatus("research-1", "responding");
            bar.appendSubagentActivity("research-1", "Searching docs", "  searched provider docs");
            bar.appendSubagentOutput("research-1", "live answer chunk");

            String retainedFollowUp = panel.inlineSubagentTranscript("research-1");
            assertFalse(retainedFollowUp.contains("Ctrl+B"),
                    "an already-independent retained follow-up needs no background action");
            assertTrue(retainedFollowUp.contains("Delete to stop"));

            blockingSubagentInvocation.set(true);
            assertTrue(tasks.isCurrentTaskBackgroundable());

            String foreground = panel.inlineSubagentTranscript("research-1");
            assertTrue(foreground.contains("Subagent [research-1] researcher"));
            assertTrue(foreground.contains("Searching docs"),
                    "one-line steps stay in the collapsed block");
            assertFalse(foreground.contains("searched provider docs"),
                    "raw tool detail must stay out of the main-window block");
            assertFalse(foreground.contains("live answer chunk"),
                    "streamed model text must stay out of the main-window block");
            assertTrue(foreground.contains("Ctrl+B background this invocation"));
            assertTrue(foreground.contains("Delete to stop"));
            assertTrue(foreground.contains("Enter to inspect"));
            assertFalse(panel.inlineSubagentTranscript("research-1").contains("live answer chunk"),
                    "the collapsed main-window block never repaints raw output");

            assertNotNull(tasks.requestBackground());
            blockingSubagentInvocation.set(false);
            String backgrounded = panel.inlineSubagentTranscript("research-1");
            assertTrue(backgrounded.contains("Backgrounded"));
            assertTrue(backgrounded.contains("output continues in the subagent row below"));
            assertTrue(backgrounded.contains("Delete to stop"));
            assertFalse(backgrounded.contains("live answer chunk"),
                    "detached output must stop repainting the main transcript block");
            assertFalse(panel.activityItems().stream()
                            .anyMatch(item -> item.kind()
                                    == StandardChatActivityPanel.ActivityKind.TASK),
                    "the detached parent turn must not add a non-interactive duplicate beside its live child");
            panel.refresh();
            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("live answer chunk"),
                    "the selectable subagent row must retain the full live transcript");
            assertTrue(panel.trySendMessageToViewedSubagent("continue the crawl"));
            assertEquals("research-1:continue the crawl", backgroundFollowUp.get(),
                    "the only visible background row must route input to the retained child");

            bar.unregisterSubagent("research-1");
            assertTrue(panel.trySendMessageToViewedSubagent("one more detail"));
            assertEquals("research-1:one more detail", backgroundFollowUp.get(),
                    "a completed child remains an interactive retained conversation");
            assertFalse(panel.activityItems().stream()
                            .anyMatch(item -> item.kind()
                                    == StandardChatActivityPanel.ActivityKind.TASK),
                    "the parent duplicate must stay hidden while its retained child remains visible");

            for (int i = 0; i < 8; i++) {
                String id = "later-child-" + i;
                bar.registerSubagent(id, "explore", "Later child " + i);
                bar.unregisterSubagent(id);
            }
            assertTrue(panel.activityItems().stream()
                            .anyMatch(item -> item.kind()
                                    == StandardChatActivityPanel.ActivityKind.TASK),
                    "the detached parent log must return after its linked child is evicted");
            assertTrue(panel.currentView().main(),
                    "evicting the open child should return the transcript to Main");
            panel.clearSelection();
            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView taskView = panel.openSelectedView();
            assertTrue(taskView.content().contains("follow-ups: subagent research-1"));
            assertEquals("research-1", panel.viewedSubagentId(),
                    "the parent task row must retain the exact child message target");
            assertTrue(panel.trySendMessageToViewedSubagent("after row eviction"));
            assertEquals("research-1:after row eviction", backgroundFollowUp.get(),
                    "opening the literal task row must not fall through to Main");
        } finally {
            processes.close();
        }
    }

    @Test
    void inlineSubagentBlockCollapsesToAFewOneLineStepsWithRunningIndicator() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-activity-inline-collapse-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            bar.registerSubagent("busy-1", "general", "Run the long task");
            for (int i = 0; i < 9; i++) {
                bar.appendSubagentActivity("busy-1", "Grep pattern #" + i + " …", null);
                bar.appendSubagentActivity("busy-1", "Grep pattern #" + i + " ✓ 12 matches", null);
            }
            bar.appendSubagentActivity("busy-1", "Bash mvn test …", null);

            String block = panel.inlineSubagentTranscript("busy-1");
            List<String> lines = block.lines().toList();
            assertTrue(lines.size() <= 7,
                    "the collapsed block stays within about five rows, got " + lines.size());
            assertTrue(block.contains("10 steps"),
                    "the hidden-step count must be preserved: " + block);
            assertTrue(block.contains("… +7 earlier steps"),
                    "earlier steps collapse into a count: " + block);
            assertTrue(block.contains("⟳ Bash mvn test …"),
                    "the in-flight command gets the running indicator: " + block);
            assertTrue(block.contains("⎿ Grep pattern #8 ✓ 12 matches"),
                    "the last finished step keeps its outcome: " + block);
            assertFalse(block.contains("Grep pattern #5"),
                    "older steps must collapse out: " + block);
            assertFalse(block.contains("[older activity omitted]"),
                    "the raw transcript marker must never appear inline: " + block);

            bar.appendSubagentActivity("busy-1", "Bash mvn test ✓ 64/64 green", null);
            String settled = panel.inlineSubagentTranscript("busy-1");
            assertTrue(settled.contains("⎿ Bash mvn test ✓ 64/64 green"),
                    "a finished call replaces its in-flight row: " + settled);
            assertFalse(settled.contains("Bash mvn test …"),
                    "the provisional in-flight row must not stack beside its result: " + settled);

            bar.registerSubagent("fresh-1", "explore", "Just spawned");
            String fresh = panel.inlineSubagentTranscript("fresh-1");
            assertTrue(fresh.contains("⟳ running"),
                    "before any step the live status becomes the running row: " + fresh);
            assertFalse(fresh.lines().count() > 4, "a fresh child stays minimal: " + fresh);

            bar.unregisterSubagent("busy-1");
            String recent = panel.inlineSubagentTranscript("busy-1");
            assertTrue(recent.contains("completed") || recent.contains("⎿ Bash mvn test ✓ 64/64 green"),
                    "a retained child still shows its collapsed steps: " + recent);
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
    void updatedProviderToolInputAppearsInTheOpenedActivityDetail() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("provider-tool-input-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);
            String callId = "provider-call-1";
            panel.recordToolStart(callId, "external_tool", "{}");
            String finalInput = "{\"path\":\"/tmp/final.txt\",\"extra\":\"complete\"}";
            panel.updateToolInput(callId, "external_tool", finalInput);
            panel.recordToolComplete(callId, "external_tool", finalInput,
                    ToolResult.success("done"));

            assertTrue(panel.selectNext());
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("path=/tmp/final.txt"), view.content());
            assertTrue(view.content().contains("extra=complete"), view.content());
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
    void monitorsAppearAsTraceableRowsNestedUnderTheirWatchedProcess() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-monitor-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 5);

            BackgroundProcessManager.ProcessEntry watched = processes.launch(
                    "sleep 30", "Watched build", java.nio.file.Path.of("."));
            assertNotNull(processes.monitor(watched.getId(), "report build result"));

            panel.refresh();
            var items = panel.activityItems();
            StandardChatActivityPanel.ActivityItem processItem = items.stream()
                    .filter(item -> item.id().equals(watched.getId()))
                    .findFirst().orElseThrow();
            StandardChatActivityPanel.ActivityItem monitorItem = items.stream()
                    .filter(item -> item.key().equals("monitor:" + watched.getId()))
                    .findFirst().orElseThrow();
            assertEquals(StandardChatActivityPanel.ActivityKind.MONITOR, monitorItem.kind());
            assertEquals(processItem.key(), monitorItem.parentKey(),
                    "each monitor must be traceable to the process it watches");
            assertTrue(monitorItem.label().contains("report build result"));
            assertTrue(monitorItem.status().startsWith("armed"));
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(item -> item.label().contains("report build result")));

            assertTrue(panel.selectNext());
            panel.selectNext();
            StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
            assertNotNull(view);
            assertFalse(view.main());
            assertTrue(view.content().contains("report build result"),
                    "monitor detail view must show its wake message");
            assertTrue(view.content().contains("sleep 30"),
                    "monitor detail view must show the watched process command");
            assertTrue(view.content().contains("Del cancels the wake-up"));

            // Del on a monitor row cancels the wake-up only — the process survives.
            assertEquals("monitor cancelled for " + watched.getId(), panel.killSelected());
            assertTrue(processes.getMonitor(watched.getId()) == null);
            assertTrue(watched.isRunning(),
                    "cancelling a monitor must not kill the watched process");
            processes.kill(watched.getId());
        } finally {
            processes.close();
        }
    }

    @Test
    void cancellingMonitorRemovesRowAndKeepsProcessRow() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-monitor-cancel-test");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);

            BackgroundProcessManager.ProcessEntry watched = processes.launch(
                    "sleep 30", "Monitored watcher", java.nio.file.Path.of("."));
            assertNotNull(processes.monitor(watched.getId(), "wake me when done"));

            panel.refresh();
            assertTrue(panel.activityItems().stream()
                    .anyMatch(item -> item.kind() == StandardChatActivityPanel.ActivityKind.MONITOR));
            assertTrue(processes.removeMonitor(watched.getId()));
            panel.refresh();
            assertFalse(panel.activityItems().stream()
                    .anyMatch(item -> item.kind() == StandardChatActivityPanel.ActivityKind.MONITOR),
                    "cancelling a monitor must remove its traceable row");
            assertTrue(panel.activityItems().stream()
                    .anyMatch(item -> item.id().equals(watched.getId())),
                    "the watched process row must remain visible");
            processes.kill(watched.getId());
        } finally {
            processes.close();
        }
    }

    @Test
    void projectDashboardIsSelectableFilterableAndReturnsToMain() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes =
                new BackgroundProcessManager("standard-project-activity-test");
        AtomicBoolean visible = new AtomicBoolean();
        AtomicReference<String> filter = new AtomicReference<>("");
        AtomicReference<String> content = new AtomicReference<>("first snapshot");
        AtomicInteger refreshes = new AtomicInteger();
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 4);
            panel.setProjectActivityView(new ProjectActivityView() {
                @Override public String compactStatus() { return "2 agents · 1 running"; }
                @Override public String title() { return filter.get().isBlank()
                        ? "Project activity" : "Project activity · " + filter.get(); }
                @Override public String content() { return content.get(); }
                @Override public boolean hasLiveWork() { return true; }
                @Override public boolean isVisible() { return visible.get(); }
                @Override public void show(String nextFilter) {
                    filter.set(nextFilter == null ? "" : nextFilter);
                    visible.set(true);
                }
                @Override public void hide() { visible.set(false); }
                @Override public void refresh() { refreshes.incrementAndGet(); }
            });

            StandardChatActivityPanel.ActivityItem item = panel.activityItems().stream()
                    .filter(activity -> activity.kind()
                            == StandardChatActivityPanel.ActivityKind.PROJECT)
                    .findFirst().orElseThrow();
            assertEquals("agents", item.id());
            assertTrue(item.status().contains("2 agents"));
            assertTrue(panel.currentMenuItems().stream()
                    .anyMatch(menu -> menu.label().contains("Project activity")));

            StandardChatActivityPanel.ActivityView opened =
                    panel.openProjectActivity("codex");
            assertNotNull(opened);
            assertTrue(visible.get());
            assertEquals("codex", filter.get());
            assertTrue(panel.isViewingProjectActivity());
            assertTrue(opened.title().contains("codex"));
            assertEquals("first snapshot", opened.content());

            content.set("second snapshot");
            assertEquals("second snapshot", panel.currentView().content());
            panel.refreshProjectActivity();
            assertEquals(1, refreshes.get());

            panel.returnToMain();
            assertFalse(visible.get());
            assertTrue(panel.isViewingMain());

            assertTrue(panel.selectNext());
            assertEquals(StandardChatActivityPanel.PROJECT_ACTIVITY_KEY,
                    panel.getSelectedKey());
            StandardChatActivityPanel.ActivityView keyboardOpened = panel.openSelectedView();
            assertNotNull(keyboardOpened);
            assertTrue(visible.get());
            assertTrue(filter.get().isBlank(),
                    "opening the project row directly should clear a prior slash-command filter");
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
                    .contains("No auxiliary chats, active processes, or subagents")));
        } finally {
            processes.close();
        }
    }

    @Test
    void sharedMirrorRowsCarryOwnerLabelOpenLiveOutputAndNeverTouchOwnerProcess() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-shared-mirror-test", java.nio.file.Files.createTempDirectory(
                "shared-mirror-test"));
        java.nio.file.Path ownerLog = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "shared-mirror-owner.log");
        java.nio.file.Files.write(ownerLog, ("compile step 1\n").getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 6);
            processes.upsertShared(
                    "shared-owner-session-proc-001",
                    "mvn -o test",
                    "Maven test run · codex",
                    999999L,
                    java.time.Instant.now(),
                    BackgroundProcessManager.ProcessState.RUNNING,
                    null,
                    null,
                    ownerLog,
                    Map.of("ownerSessionId", "owner-session",
                            "sharedProcessId", "proc-001",
                            "ownerAgent", "codex",
                            "source", "coordination"));
            panel.refresh();

            var row = panel.activityItems().stream()
                    .filter(item -> item.id().startsWith("shared-"))
                    .filter(item -> item.kind() == StandardChatActivityPanel.ActivityKind.PROCESS)
                    .findFirst()
                    .orElseThrow();
            assertTrue(row.label().contains("codex"),
                    "shared row must carry the owner label: " + row.label());
            assertTrue(row.label().contains("Maven test run"),
                    "shared row keeps the description: " + row.label());
            assertFalse(row.killable(),
                    "mirror must not offer Del-stop: the owner session owns the PID");

            // Opening the row shows the live owner log FIRST, metadata as a
            // footer, and never just the output-file path.
            assertTrue(panel.selectNext(), "shared row should be selectable");
            var view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("compile step 1"),
                    "detail must show the live owner log tail: " + view.content());
            assertTrue(view.content().contains("owner: codex"),
                    "detail must identify the owner: " + view.content());
            assertTrue(view.content().indexOf("compile step 1")
                            < view.content().indexOf("log: "),
                    "the log body must come before the log-path footer");
            assertTrue(view.content().contains("output captured") == false
                            || view.content().indexOf("compile step 1") >= 0,
                    "an existing log must never render as only a path placeholder");

            // Kill routing must refuse mirrors: the OS PID belongs to another session.
            assertFalse(processes.kill(row.id()),
                    "kill must refuse a shared mirror instead of signaling the owner PID");
            assertTrue(processes.listAll().stream()
                            .filter(entry -> entry.getKind() == ProcessKind.SHARED)
                            .findFirst().orElseThrow().isRunning(),
                    "refused kill leaves the mirror following the owner's true state");
        } finally {
            processes.close();
            java.nio.file.Files.deleteIfExists(ownerLog);
        }
    }

    @Test
    void emptyProcessLogStillShowsStreamedPlaceholderNotJustPath() throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        BackgroundProcessManager processes = new BackgroundProcessManager(
                "standard-activity-empty-log-test", java.nio.file.Files.createTempDirectory(
                "empty-log-test"));
        java.nio.file.Path ownerLog = java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "shared-mirror-empty.log");
        try {
            StatusBar bar = new StatusBar(
                    tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(
                    tasks, processes, bar, () -> 6);
            java.nio.file.Files.write(ownerLog, new byte[0]);
            processes.upsertShared(
                    "shared-owner-session-proc-002",
                    "sleep-runner",
                    "empty log mirror",
                    999998L,
                    java.time.Instant.now(),
                    BackgroundProcessManager.ProcessState.RUNNING,
                    null,
                    null,
                    ownerLog,
                    Map.of("ownerSessionId", "owner-session"));
            panel.refresh();
            assertTrue(panel.selectNext(), "shared row should be selectable");
            var view = panel.openSelectedView();
            assertNotNull(view);
            String content = view.content();
            assertTrue(content.contains("no output") || content.contains("No output"),
                    "an empty log must say so instead of rendering only the path: "
                            + content);
        } finally {
            processes.close();
            java.nio.file.Files.deleteIfExists(ownerLog);
        }
    }
}
