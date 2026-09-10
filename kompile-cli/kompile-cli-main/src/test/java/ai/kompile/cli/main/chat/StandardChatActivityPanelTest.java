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

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StandardChatActivityPanelTest {

    @Test
    void completedRowsExpireOnIdleCallbackWithoutRemovingHistoryOrRunningWork() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
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
            assertTrue(foreground.contains("Searching docs"));
            assertTrue(foreground.contains("searched provider docs"));
            assertTrue(foreground.contains("live answer chunk"));
            assertTrue(foreground.contains("Ctrl+B background this invocation"));
            assertTrue(foreground.contains("Delete to stop"));
            assertTrue(foreground.contains("Enter to inspect"));

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
}
