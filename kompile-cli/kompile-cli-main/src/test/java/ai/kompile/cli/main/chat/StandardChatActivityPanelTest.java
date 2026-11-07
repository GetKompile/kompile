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
    void killByKeyRoutesSidebarSelectionIntoTheActivityKillPath(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-kill", workspace)) {
            var renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
            var running = processes.launch("sleep 30", "Sidebar-selected managed process",
                    workspace);
            // The sidebar's key for this row is the panel's activity key.
            String sidebarKey = "process:" + running.getId();
            String result = panel.killByKey(sidebarKey);
            assertTrue(result.contains("kill requested for " + running.getId()), result);
            assertTrue(awaitCondition(() -> !running.isRunning()),
                    "sidebar Delete must terminate the selected managed process");
        }
    }

    @Test
    void killByKeyWithUnknownOrBlankKeyFailsSafeWithoutTransferringTheKill(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path unrelated) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("sidebar-kill-safe", unrelated)) {
            var renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
            var running = processes.launch("sleep 30", "Must stay alive", unrelated);
            assertEquals("No activity selected", panel.killByKey(null));
            assertEquals("No activity selected", panel.killByKey("   "));
            assertEquals("No activity selected", panel.killByKey("process:does-not-exist"));
            assertFalse(panel.isFocused(), "an unmatched key must not leave the panel focused");
            assertTrue(running.isRunning(), "a stale sidebar selection must not kill a live process");
            // Cleanup so the test leaves no sleep process behind.
            assertTrue(processes.kill(running.getId()));
            assertTrue(awaitCondition(() -> !running.isRunning()));
        }
    }

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    @Test
    void typedChildBrowserUsesMainProjectionAndLiveUiPolicyWithoutParentLeakage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("child-format", workspace)) {
            var renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
            var config = ai.kompile.cli.main.chat.config.ChatUiConfig.classic();
            panel.setTranscriptUiConfig(() -> config);
            var row = processes.registerVirtual(ProcessKind.COMMAND, "claude local_agent", "Inspect code",
                    Map.of("source", "claude-code", "task_type", "local_agent"));
            var transcript = new ai.kompile.cli.main.chat.render.SubagentTranscript();
            assertTrue(processes.setVirtualTranscript(row.getId(), transcript));
            processes.appendVirtualOutput(row.getId(), "raw-log-sentinel");
            transcript.text("thinking", "private reasoning");
            transcript.text("assistant", "**Formatted** child prose");
            panel.refresh();
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            var projection = new ai.kompile.cli.main.chat.tui.ChatSidebar(renderer);
            for (var block : row.transcriptBlocks(renderer)) {
                assertTrue(view.content().contains(projection.inline(block, config)), view.content());
            }
            assertFalse(view.content().contains("**Formatted**"));
            assertFalse(view.content().contains("raw-log-sentinel"));
            assertFalse(panel.resultDisplayBlocks().toString().contains("child prose"));

            config.set("content.thinking.inline", "none");
            config.set("content.thinking.sidebar", "off");
            var refreshed = panel.currentView();
            assertFalse(refreshed.content().contains("private reasoning"), refreshed.content());
            transcript.text("assistant", " continues");
            assertTrue(panel.currentView().content().contains("child prose continues"));
            transcript.finish();
            processes.complete(row.getId());
            assertTrue(panel.currentView().content().contains("child prose continues"));
            assertTrue(processes.readOutput(row.getId(), 10).contains("raw-log-sentinel"), "diagnostic log is unchanged");
        }
    }

    @Test
    void mirroredTaskBrowserUsesTheSameStyledProjectionAndLivePreferences(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        var child = new ai.kompile.cli.main.chat.render.SubagentTranscript();
        child.message("user", "inspect code");
        child.text("thinking", "private reasoning");
        child.text("assistant", "# Findings\n**Formatted** child prose\n```java\npublic class App {}\n```\n");
        child.toolStart("read", "mcp__kompile__read", "{\"file_path\":\"App.java\"}");
        child.toolComplete("read", "public class App {}", false);
        var sidecar = workspace.resolve("child.transcript.json");
        child.writeSnapshot(sidecar);
        var raw = workspace.resolve("child.log");
        java.nio.file.Files.writeString(raw, "raw-log-sentinel\nThinking:\nAssistant:\n");
        for (boolean ansi : List.of(false, true)) {
            try (var processes = new BackgroundProcessManager("mirrored-child-" + ansi, workspace)) {
                var renderer = new TerminalRenderer(ansi);
                var bar = new StatusBar(tasks, processes, null, renderer);
                var panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
                var config = ai.kompile.cli.main.chat.config.ChatUiConfig.classic();
                panel.setTranscriptUiConfig(() -> config);
                var row = processes.upsertShared("child", "kompile", "MCP child", -1, java.time.Instant.now(),
                        BackgroundProcessManager.ProcessState.RUNNING, null, null, raw,
                        Map.of("kind", "subagent", "transcript_file", sidecar.toString(), "transcript_revision", "1"));
                panel.refresh();
                assertTrue(panel.selectNext());
                var projection = new ai.kompile.cli.main.chat.tui.ChatSidebar(renderer);
                for (String preset : List.of("classic", "focused")) {
                    config.applyPreset(preset);
                    config.set("content.tool.inline", "full");
                    var view = panel.openSelectedView();
                    for (var block : child.blocks(renderer)) {
                        String expected = projection.inline(block, config);
                        if (!expected.isBlank()) assertTrue(view.content().contains(expected), view.content());
                    }
                    assertFalse(view.content().contains("raw-log-sentinel"));
                    assertFalse(panel.resultDisplayBlocks().toString().contains("child prose"));
                }
                config.set("content.thinking.inline", "none");
                config.set("content.thinking.sidebar", "off");
                assertFalse(panel.currentView().content().contains("private reasoning"));
                var goodFrame = row.transcriptBlocks(renderer);
                java.nio.file.Files.writeString(sidecar, "invalid");
                processes.upsertShared("child", "kompile", "MCP child", -1, row.getStartTime(),
                        BackgroundProcessManager.ProcessState.RUNNING, null, null, raw,
                        Map.of("kind", "subagent", "transcript_file", sidecar.toString(), "transcript_revision", "2"));
                assertEquals(goodFrame, row.transcriptBlocks(renderer), "retain the last valid frame on a bad update");
                child.writeSnapshot(sidecar);
            }
        }
    }

    @Test
    void typedTranscriptCannotAttachToSharedTerminalOrClosedEntries(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        var processes = new BackgroundProcessManager("transcript-ownership", workspace);
        try {
            var transcript = new ai.kompile.cli.main.chat.render.SubagentTranscript();
            processes.upsertShared("foreign", "claude", "other chat", -1, java.time.Instant.now(),
                    BackgroundProcessManager.ProcessState.RUNNING, null, null, null, Map.of());
            assertFalse(processes.setVirtualTranscript("foreign", transcript));
            assertFalse(processes.setVirtualTranscript("unknown", transcript));
            var row = processes.registerVirtual(ProcessKind.COMMAND, "claude", "local child", Map.of());
            processes.complete(row.getId());
            assertFalse(processes.setVirtualTranscript(row.getId(), transcript));
            var live = processes.registerVirtual(ProcessKind.COMMAND, "claude", "live child", Map.of());
            processes.close();
            assertFalse(processes.setVirtualTranscript(live.getId(), transcript));
        } finally {
            processes.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void mirroredMcpSubagentLogsOpenInBrowserWithoutLeakingIntoParentCards(int children,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        var coordinator = new ai.kompile.cli.main.coordination.CoordinationStateManager(
                workspace, "mcp-owner", ai.kompile.cli.common.util.JsonUtils.standardMapper());
        coordinator.registerAgent("MCP fixture", "parent-chat", "kompile", 1, 0L, null, null);
        try (BackgroundProcessManager processes = new BackgroundProcessManager("parent-chat", workspace);
             BackgroundProcessManager owner = new BackgroundProcessManager("mcp-owner", workspace);
             SharedProcessMirror mirror = new SharedProcessMirror(processes, coordinator, "parent-chat")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            for (int n = 1; n <= children; n++) {
                var child = owner.registerVirtual(ProcessKind.COMMAND, "kompile", "Subagent: child " + n,
                        Map.of("kind", "subagent"));
                owner.appendVirtualOutputChunk(child.getId(), "Thinking:\nchild-" + n + " live");
                coordinator.publishProcess(child.getId(), child.getCommand(), child.getDescription(), -1L,
                        "RUNNING", child.getOutputFile().toString(), "kompile", null, "subagent",
                        child.getStartTime(), null, null);
            }
            mirror.pollOnce();
            panel.refresh();
            var cards = panel.resultDisplayBlocks();
            assertEquals(children, cards.size());
            assertTrue(cards.stream().allMatch(card -> card.sections().isEmpty()));
            assertFalse(cards.toString().contains("Thinking:"));
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            assertTrue(view.content().contains(" live"), view.content());
            for (var child : owner.listAll()) {
                owner.appendVirtualOutputChunk(child.getId(), " stream\nAssistant:\nchild answer\n");
                owner.complete(child.getId());
                coordinator.updateProcessState(child.getId(), "COMPLETED", child.getEndTime(), child.getExitCode());
            }
            mirror.pollOnce();
            assertEquals(view.key(), panel.currentView().key());
            assertTrue(panel.currentView().content().contains(" live stream"));
            assertTrue(panel.currentView().content().contains("child answer"));
            assertFalse(panel.resultDisplayBlocks().toString().contains("child answer"));
            assertTrue(processes.listAll().stream().noneMatch(BackgroundProcessManager.ProcessEntry::isRunning));
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void automaticProcessCardsExcludeOtherSessionsButKeepLocalAndParentedMcpOutput(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("codex-chat", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            var local = processes.registerVirtual(ProcessKind.COMMAND, "build", "Local build", Map.of());
            processes.appendVirtualOutput(local.getId(), "local-output");
            for (String parent : List.of("codex-chat", "claude-chat", "")) {
                var log = workspace.resolve("mcp-" + parent + ".log");
                java.nio.file.Files.writeString(log, "output-for-" + parent + "\n");
                processes.upsertShared("shared-" + parent, "build", "MCP build " + parent, 0L,
                        java.time.Instant.now(), BackgroundProcessManager.ProcessState.RUNNING,
                        null, null, log, parent.isEmpty()
                                ? Map.of("ownerSessionId", "unregistered-mcp", "ownerAgent", "claude")
                                : Map.of("ownerSessionId", "mcp-" + parent, "ownerAgent", "claude",
                                        "parentSessionId", parent));
            }
            panel.refresh();

            assertEquals(4, panel.activityItems().stream()
                    .filter(item -> item.kind() == StandardChatActivityPanel.ActivityKind.PROCESS).count(),
                    "shared process inspection must remain available");
            var cards = panel.resultDisplayBlocks();
            assertEquals(java.util.Set.of("process:" + local.getId(), "process:shared-codex-chat"),
                    cards.stream().map(ai.kompile.cli.main.chat.render.ChatDisplayBlock::key)
                            .collect(java.util.stream.Collectors.toSet()),
                    "only this chat's work may automatically enter its transcript");
            assertTrue(cards.toString().contains("local-output"));
            assertTrue(cards.toString().contains("output-for-codex-chat"));
            assertFalse(cards.toString().contains("output-for-claude-chat"));

            java.nio.file.Files.writeString(workspace.resolve("mcp-claude-chat.log"), "foreign-live-update\n");
            panel.refresh();
            assertEquals(2, panel.resultDisplayBlocks().size());
            assertFalse(panel.resultDisplayBlocks().toString().contains("foreign-live-update"),
                    "foreign output growth must not leak on redraw either");
        }
    }

    @Test
    void mcpAndClaudeDiagnosticsStayBrowsableWithoutAutomaticTranscriptCards(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        java.nio.file.Files.createDirectories(workspace.resolve(".kompile"));
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-logs", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            var bridge = processes.registerVirtual(ProcessKind.MCP, "mcp", "MCP tool bridge log", Map.of());
            var claude = processes.registerVirtual(ProcessKind.MCP, "claude", "Claude Code log", Map.of());
            var command = processes.registerVirtual(ProcessKind.COMMAND, "build", "Local build", Map.of());
            processes.appendVirtualOutput(bridge.getId(), "[MCP] Wrote kompile MCP tools for a Claude Code launch");
            processes.appendVirtualOutput(claude.getId(), "Claude Code diagnostic");
            processes.appendVirtualOutput(command.getId(), "build output");
            panel.refresh();

            assertEquals(List.of("process:" + command.getId()), panel.resultDisplayBlocks().stream()
                    .map(ai.kompile.cli.main.chat.render.ChatDisplayBlock::key).toList(),
                    "diagnostic log rows are not background work to copy into the transcript");
            processes.appendVirtualOutput(bridge.getId(), "[MCP] Pre-configured Claude Code hooks");
            processes.appendVirtualOutput(claude.getId(), "Warning: MCP connection failed");
            panel.refresh();
            assertEquals(List.of("process:" + command.getId()), panel.resultDisplayBlocks().stream()
                    .map(ai.kompile.cli.main.chat.render.ChatDisplayBlock::key).toList(),
                    "live diagnostic updates must not reintroduce transcript cards");

            var views = new java.util.HashMap<String, String>();
            for (int i = 1; i < panel.activityItems().size(); i++) {
                assertTrue(panel.selectNext());
                var view = panel.openSelectedView();
                assertNotNull(view);
                views.put(view.key(), view.content());
            }
            assertTrue(views.get("process:" + bridge.getId()).contains("[MCP] Pre-configured Claude Code hooks"));
            assertTrue(views.get("process:" + claude.getId()).contains("Warning: MCP connection failed"),
                    "real diagnostics must remain inspectable in the process browser");
            assertTrue(panel.resultDisplayBlocks().get(0).sections().get(0).content().contains("build output"));
        }
    }

    @Test
    void claudeChildViewerReadsTheLiveConversationNotOnlyToolSummaries(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        java.nio.file.Files.createDirectories(workspace.resolve(".kompile"));
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-child-view", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            var child = processes.registerVirtual(ProcessKind.COMMAND, "claude local_agent", "Claude: inspect",
                    Map.of("source", "claude-code", "task_id", "child", "task_type", "local_agent"));
            var launch = panel.resultDisplayBlocks().get(0);
            assertTrue(launch.classic().contains("Launched subagent [" + child.getId() + "]"));
            assertTrue(launch.sections().isEmpty());
            processes.appendVirtualOutput(child.getId(), "User:\nInspect the parser\nThinking:\nFind the dropped frames\nAssistant:");
            processes.appendVirtualOutputChunk(child.getId(), "I will inspect ");
            processes.appendVirtualOutputChunk(child.getId(), "the parser");
            panel.refresh();
            assertEquals(launch, panel.resultDisplayBlocks().get(0),
                    "Claude child streaming must not copy its conversation into automatic process cards");
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            assertNotNull(view);
            assertTrue(view.content().contains("model: ? · vendor: anthropic"), "do not guess the parent's model");
            processes.updateVirtual(child.getId(), null, Map.of("source", "claude-code", "task_type", "local_agent",
                    "model", "claude-child", "total_tokens", "410", "tool_uses", "2"));
            assertTrue(panel.currentView().content().contains("model: claude-child · vendor: anthropic"));
            assertTrue(panel.currentView().content().contains("410 total (provider)"));
            assertTrue(panel.currentView().content().contains("tools: 2"));
            assertTrue(view.content().contains("User:\nInspect the parser"), view.content());
            assertTrue(view.content().contains("Thinking:\nFind the dropped frames"), view.content());
            assertTrue(view.content().contains("Assistant:\nI will inspect the parser"), view.content());
            processes.appendVirtualOutputChunk(child.getId(), "\nResult [read]:\nfile contents\nAssistant:\nFixed it\n");
            assertTrue(panel.currentView().content().contains("Fixed it"));
            processes.complete(child.getId());
            assertTrue(panel.currentView().content().contains("file contents"), "completion retains the open transcript");
        }
    }

    @Test
    void nativeAgentsGetLaunchNoticesButShellProcessesKeepTheirOutputCards() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("native-agent-launches")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            for (String type : List.of("agent", "local_agent", "background_agent", "local_bash")) {
                var entry = processes.registerVirtual(ProcessKind.COMMAND, "claude " + type, "Task " + type,
                        Map.of("source", "claude-code", "task_id", type, "task_type", type));
                processes.appendVirtualOutput(entry.getId(), "private output for " + type);
            }
            var cards = panel.resultDisplayBlocks();
            assertEquals(4, cards.size());
            for (var card : cards) {
                if (card.kind().equals("subagent")) {
                    assertTrue(card.classic().contains("Launched subagent"));
                    assertTrue(card.sections().isEmpty());
                    assertFalse(card.toString().contains("private output"));
                    processes.fail(card.key().substring("process:".length()), 1);
                } else {
                    assertEquals("process", card.kind());
                    assertTrue(card.sections().get(0).content().contains("private output for local_bash"),
                            "native shell process output is outside the subagent-display change");
                }
            }
            assertEquals(3, cards.stream().filter(card -> card.kind().equals("subagent")).count());
            assertTrue(panel.resultDisplayBlocks().stream().filter(card -> card.kind().equals("subagent"))
                    .allMatch(card -> card.error() && !card.running() && card.sections().isEmpty()));
        }
    }

    @Test
    void subagentLaunchLabelCannotExpandIntoMultilinePromptOrOutput() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("bounded-agent-launch")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 4);
            bar.registerSubagent("long-1", "general", "First line\n\n" + "long description ".repeat(100));
            String launch = panel.inlineSubagentTranscript("long-1");
            assertEquals(2, launch.lines().count());
            assertTrue(launch.contains("general — First line long description"));
            assertTrue(launch.length() < 320, "a large task description must stay a small launch notice");
        }
    }

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
    void toolDetailRetainsStreamedProcessAndBashOutput() {
        for (String toolName : List.of("process", "bash", "mcp__kompile__process")) {
            BackgroundTaskManager tasks = new BackgroundTaskManager();
            try (BackgroundProcessManager processes = new BackgroundProcessManager("stream-detail")) {
                StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
                StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
                ToolResult result = new ToolResult("stream proc-1",
                        "Process: proc-1\ncompiling sources\nBUILD SUCCESS", Map.of(
                        ToolResult.OUTPUT_STREAMED_METADATA, true));
                panel.recordToolComplete("streamed", toolName,
                        "{\"action\":\"stream\",\"process_id\":\"proc-1\"}", result);

                assertTrue(panel.selectNext());
                StandardChatActivityPanel.ActivityView view = panel.openSelectedView();
                assertNotNull(view);
                assertTrue(view.content().contains("compiling sources"), view.content());
                assertTrue(view.content().contains("BUILD SUCCESS"), view.content());
                assertFalse(view.content().contains(ToolResult.OUTPUT_STREAMED_METADATA), view.content());
                assertTrue(result.isOutputStreamed(), "opening details must not mutate the result");
            }
        }
    }

    @Test
    void tinyAllocationsUseAvailableRowsForProcessesAndCompletions() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("tiny-activity-budget")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            AtomicInteger rows = new AtomicInteger(1);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, rows::get);
            for (int i = 0; i < 12; i++) panel.recordToolStart("tool-" + i, "process", "{}");
            panel.refresh();
            assertEquals(1, panel.currentMenuItems().size());
            assertTrue(panel.currentMenuItems().get(0).id().startsWith("tool:"));
            assertTrue(panel.currentMenuItems().get(0).status().contains("more"));
            rows.set(0);
            panel.refresh();
            assertTrue(panel.currentMenuItems().isEmpty());
            rows.set(3);
            panel.refresh();
            assertEquals(2, panel.currentMenuItems().size());
            rows.set(1);
            panel.refresh();
            assertEquals(1, panel.currentMenuItems().size());
            panel.updateCompletions(List.of(new ChatCompleter.CompletionItem("/help", "Help"),
                    new ChatCompleter.CompletionItem("/status", "Status")));
            assertEquals(1, panel.currentMenuItems().size());
            assertEquals("/help", panel.currentMenuItems().get(0).label());
            rows.set(0);
            panel.refresh();
            assertTrue(panel.currentMenuItems().isEmpty());
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
    void showsSubagentLaunchOnlyAndExplainsBackgroundInspection() {
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
            assertTrue(foreground.contains("subagent [research-1] researcher"));
            assertTrue(foreground.contains("Launched subagent"));
            assertFalse(foreground.contains("Searching docs"),
                    "even step summaries belong in the process browser, not the launch notice");
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
    void subagentLaunchStaysCompactInEveryPresetWhileBrowserRetainsLiveDetails() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("subagent-launch-only")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(true));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 4);
            bar.registerSubagent("busy-1", "general", "Run the long task");
            bar.updateSubagentMetadata("busy-1", Map.of("model", "child-model", "vendor", "child-vendor",
                    "input_tokens", "120", "output_tokens", "8", "stream_output_tokens", "3",
                    "authentication", "native", "thinking", "high", "tool_uses", "9"));
            String launch = panel.inlineSubagentTranscript("busy-1");
            assertTrue(launch.contains("Launched subagent [busy-1] general — Run the long task"));
            assertTrue(launch.contains("process browser below"));
            assertTrue(launch.contains("Enter to inspect"));
            assertEquals(2, launch.lines().count());

            for (int i = 0; i < 9; i++) {
                bar.appendSubagentActivity("busy-1", "Grep pattern #" + i + " ✓ 12 matches", "raw tool detail " + i);
            }
            bar.updateSubagentStatus("busy-1", "Bash mvn test …");
            bar.appendSubagentOutput("busy-1", "live answer chunk");
            assertEquals(launch, panel.inlineSubagentTranscript("busy-1"),
                    "activity and streamed output must not rewrite the launch notice");
            var block = panel.subagentDisplayBlock("busy-1");
            assertEquals("subagent:busy-1", block.key());
            assertTrue(block.running());
            assertTrue(block.sections().isEmpty(), "no child transcript can leak through output-section overrides");
            var sidebar = new ai.kompile.cli.main.chat.tui.ChatSidebar(new TerminalRenderer(false));
            for (String preset : List.of("classic", "focused", "verbose")) {
                var config = ai.kompile.cli.main.chat.config.ChatUiConfig.classic();
                config.applyPreset(preset);
                config.set("content.subagent.inline", "full");
                config.set("content.output.inline", "full");
                String inline = sidebar.inline(block, config);
                assertTrue(inline.contains("Launched subagent"), preset);
                assertFalse(inline.contains("Grep pattern"), preset);
                assertFalse(inline.contains("Bash mvn"), preset);
                assertFalse(inline.contains("raw tool detail"), preset);
                assertFalse(inline.contains("live answer chunk"), preset);
                assertEquals(2, inline.lines().count(), preset);
            }

            panel.refresh();
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            assertTrue(view.content().contains("raw tool detail 8"));
            assertTrue(view.content().contains("model: child-model · vendor: child-vendor"));
            assertTrue(view.title().startsWith("child-vendor/child-model · ↑120 ↓8 ~3 est."));
            assertTrue(view.content().contains("tokens: 120 in · 8 out"));
            assertTrue(view.content().contains("streaming ~3 out (estimated)"));
            assertTrue(view.content().contains("authentication: native"));
            assertTrue(view.content().contains("thinking: high"));
            assertTrue(view.content().contains("tools: 9"));
            bar.updateSubagentMetadata("busy-1", Map.of("model", "child-model", "vendor", "child-vendor",
                    "input_tokens", "120", "output_tokens", "12"));
            assertTrue(panel.currentView().content().contains("tokens: 120 in · 12 out"));
            assertTrue(panel.currentView().title().contains("↑120 ↓12"));
            assertFalse(panel.currentView().title().contains("est."));
            assertTrue(view.content().contains("live answer chunk"));
            bar.appendSubagentOutput("busy-1", " and more live output");
            assertTrue(panel.currentView().content().contains("and more live output"));
            bar.updateSubagentStatus("busy-1", "failed");
            bar.unregisterSubagent("busy-1");
            var completed = panel.subagentDisplayBlock("busy-1");
            assertFalse(completed.running());
            assertTrue(completed.error(), "failure status must remain visible without its transcript");
            assertEquals(launch, completed.classic());
            assertTrue(panel.currentView().content().contains("raw tool detail 8"));
            assertTrue(panel.currentView().content().contains("model: child-model · vendor: child-vendor"));
            assertTrue(panel.currentView().content().contains("tokens: 120 in · 12 out"));
            panel.returnToMain();
            assertTrue(panel.currentView().main());
            assertNull(panel.subagentDisplayBlock("unknown"));
        }
    }

    @Test
    void childIdentityAndLiveTokensStayPinnedWhileFollowingLongOutput() throws Exception {
        var tasks = new BackgroundTaskManager();
        try (var processes = new BackgroundProcessManager("child-telemetry-pinned")) {
            var renderer = new TerminalRenderer(false);
            var bar = new StatusBar(tasks, processes, null, renderer);
            var panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 4);
            bar.registerSubagent("child", "general", "Inspect long output");
            bar.updateSubagentMetadata("child", Map.of("model", "child-model", "vendor", "anthropic",
                    "input_tokens", "100", "output_tokens", "7", "stream_output_tokens", "3"));
            bar.appendSubagentOutput("child", "long child output\n".repeat(100));
            panel.refresh();
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            var tui = new ai.kompile.cli.main.chat.tui.KompileTui(tasks, processes,
                    new MessageQueue("child-telemetry-pinned"), renderer);
            try {
                for (int width : new int[]{80, 120}) {
                    var widthField = tui.getClass().getDeclaredField("terminalWidth");
                    widthField.setAccessible(true);
                    widthField.setInt(tui, width);
                    var heightField = tui.getClass().getDeclaredField("terminalHeight");
                    heightField.setAccessible(true);
                    heightField.setInt(tui, 24);
                    tui.showActivityView(view.key(), view.title(), view.content());
                    String header = tui.getVisibleContentLines().get(0);
                    assertTrue(header.contains("anthropic/child-model"), header);
                    assertTrue(header.contains("↑100 ↓7 ~3 est."), header);
                    assertTrue(tui.getVisibleContentLines().stream().skip(1)
                            .anyMatch(line -> line.contains("long child output")));
                    tui.updateActivityView(view.key(), view.title().replace("↓7 ~3 est.", "↓12"), view.content());
                    assertTrue(tui.getVisibleContentLines().get(0).contains("↓12"));
                }
            } finally {
                tui.stop();
            }
        }
    }

    @Test
    void openedBackgroundTaskFollowsItsLinkedSubagentInsteadOfFallingBackToMain() {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        tasks.setBackgroundableCheck(() -> true);
        try (var processes = new BackgroundProcessManager("opened-background-child")) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            tasks.startTask("Delegated work");
            assertNotNull(tasks.requestBackground());
            panel.refresh();
            assertTrue(panel.selectNext());
            var opened = panel.openSelectedView();
            assertTrue(opened.key().startsWith("task:"));
            bar.registerSubagent("child", "general", "Delegated work");
            bar.appendSubagentOutput("child", "Assistant: live investigation\n");
            bar.appendSubagentActivity("child", "read fixture", "Tool read: evidence");
            assertEquals(opened.key(), panel.currentView().key(), "a newly linked child must not close the opened view");
            assertTrue(panel.currentView().content().contains("live investigation"));
            assertTrue(panel.currentView().content().contains("Tool read: evidence"));
            bar.appendSubagentOutput("child", "next live chunk");
            assertTrue(panel.currentView().content().contains("next live chunk"));
            bar.unregisterSubagent("child");
            assertTrue(panel.currentView().content().contains("live investigation"));
        }
    }

    @Test
    void liveToolTranscriptIsBoundedIsolatedAndIgnoresLateOutput() {
        var tasks = new BackgroundTaskManager();
        try (var processes = new BackgroundProcessManager("bounded-live-tools")) {
            var bar = new StatusBar(tasks, processes, null, new TerminalRenderer(false));
            var panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            panel.recordToolStart("one", "mcp__kompile__task", "{}");
            panel.refresh();
            assertTrue(panel.selectNext());
            panel.openSelectedView();
            panel.recordToolOutput("one", "mcp__kompile__task", "{}", "old-start" + "x".repeat(150_000));
            panel.recordToolOutput("one", "mcp__kompile__task", "{}", "\nlatest prose and tools\n");
            panel.recordToolOutput("two", "mcp__kompile__multi_task", "{}", "sibling-output");
            String content = panel.currentView().content();
            assertTrue(content.contains("older tool output omitted"));
            assertTrue(content.contains("latest prose and tools"));
            assertFalse(content.contains("old-start"));
            assertFalse(content.contains("sibling-output"));
            assertTrue(content.length() < 130_000);
            panel.recordToolComplete("one", "mcp__kompile__task", "{}", ToolResult.success("done"));
            panel.recordToolOutput("one", "mcp__kompile__task", "{}", "late-output");
            assertFalse(panel.currentView().content().contains("late-output"));
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

    @Test
    void directSubagentViewProjectsTypedBlocksExactlyLikeTheProcessBrowser(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("child-direct-format", workspace)) {
            var renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
            var config = ai.kompile.cli.main.chat.config.ChatUiConfig.classic();
            panel.setTranscriptUiConfig(() -> config);
            var transcript = new ai.kompile.cli.main.chat.render.SubagentTranscript();
            SubagentRunner runner = new SubagentRunner() {
                @Override public String runSubagent(AgentConfig agentConfig, String prompt, ToolContext parentContext) {
                    throw new UnsupportedOperationException("not run in this test");
                }
                @Override public ai.kompile.cli.main.chat.render.SubagentTranscript transcriptOf(String subagentId) {
                    return "child-1".equals(subagentId) ? transcript : null;
                }
            };
            panel.setSubagentRunner(runner);
            bar.registerSubagent("child-1", "explore", "Analyze things");
            bar.appendSubagentOutput("child-1", "raw-log-sentinel");
            transcript.text("thinking", "child reasoning");
            transcript.text("assistant", "**Formatted** child prose");
            transcript.toolStart("t1", "Read", "{\"path\":\"x.java\"}");
            transcript.toolComplete("t1", "file contents", false);
            panel.refresh();
            assertTrue(panel.activityItems().stream().anyMatch(item ->
                            item.kind() == StandardChatActivityPanel.ActivityKind.SUBAGENT
                                    && item.id().equals("child-1")),
                    "the registered child must appear as a selectable SUBAGENT row");
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            var projection = new ai.kompile.cli.main.chat.tui.ChatSidebar(renderer);
            for (var block : transcript.blocks(renderer)) {
                assertTrue(view.content().contains(projection.inline(block, config)), view.content());
            }
            assertFalse(view.content().contains("**Formatted**"),
                    "markdown must be projected, not shown raw: " + view.content());
            assertFalse(view.content().contains("raw-log-sentinel"),
                    "typed parts replace the legacy raw transcript: " + view.content());
            assertFalse(panel.resultDisplayBlocks().toString().contains("child prose"),
                    "child prose must stay in the child view, never parent cards");

            config.set("content.thinking.inline", "none");
            config.set("content.thinking.sidebar", "off");
            var refreshed = panel.currentView();
            assertFalse(refreshed.content().contains("child reasoning"), refreshed.content());
            transcript.text("assistant", " continues");
            assertTrue(panel.currentView().content().contains(" continues"),
                    "a follow-up part after the tool card still projects live: " + view.content());
            transcript.finish();
            assertTrue(panel.currentView().content().contains(" continues"));
        }
    }

    @Test
    void directSubagentViewFallsBackToLegacyTranscriptWhenNoTypedPartsExist(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("child-legacy", workspace)) {
            var renderer = new TerminalRenderer(true);
            StatusBar bar = new StatusBar(tasks, processes, null, renderer);
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6, renderer);
            SubagentRunner runner = new SubagentRunner() {
                @Override public String runSubagent(AgentConfig agentConfig, String prompt, ToolContext parentContext) {
                    throw new UnsupportedOperationException("not run in this test");
                }
            };
            panel.setSubagentRunner(runner);
            bar.registerSubagent("child-2", "general", "Summarize docs");
            bar.appendSubagentOutput("child-2", "legacy log line");
            panel.refresh();
            assertTrue(panel.selectNext());
            var view = panel.openSelectedView();
            assertTrue(view.content().contains("legacy log line"),
                    "a runner without typed parts keeps the raw transcript: " + view.content());
        }
    }

    @Test
    void processLaunchRowFollowsItsProcessAndTailsTheLiveLog(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("launch-follow", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6,
                    () -> java.time.Instant.now().plusSeconds(3600), (millis, callback) -> { });
            var entry = processes.launch("echo first-line; sleep 1; echo second-line; sleep 30",
                    "follow me", workspace);
            String input = "{\"action\":\"launch\",\"command\":\"build\"}";
            panel.recordToolStart("launch-1", "process", input);
            panel.recordToolComplete("launch-1", "process", input, ToolResult.success(
                    "launched " + entry.getId(), "Launched background process", Map.of("processId", entry.getId())));

            // The clock is an hour ahead: an unlinked completed call would have expired.
            var row = toolItem(panel, "tool:launch-1");
            assertNotNull(row, "a launch row stays while its process runs");
            assertTrue(row.active(), row.toString());
            assertTrue(row.status().contains(entry.getId() + " running"), row.status());

            awaitOutput(processes, entry.getId(), "first-line");
            var view = openView(panel, "tool:launch-1");
            assertTrue(view.content().contains("live log: " + entry.getId()), view.content());
            assertTrue(view.content().contains("first-line"), view.content());
            awaitOutput(processes, entry.getId(), "second-line");
            assertTrue(panel.currentView().content().contains("second-line"),
                    "the opened call view must follow output written after the call returned");

            processes.kill(entry.getId());
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (entry.isRunning() && System.nanoTime() < deadline) Thread.sleep(50);
            assertFalse(entry.isRunning());
            // Still listed only because it is the opened view; it reports the end.
            String ended = toolItem(panel, "tool:launch-1").status();
            assertTrue(ended.contains(entry.getId() + " " + entry.getState().name().toLowerCase(java.util.Locale.ROOT)),
                    ended);
        }
    }

    @Test
    void mcpProcessCallFollowsTheMirroredProcessNamedInItsResult(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("chat-session", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            var log = workspace.resolve("mcp-build.log");
            java.nio.file.Files.writeString(log, "remote build line\n");
            // Another chat's process with the same owner id must never be picked.
            processes.upsertShared("shared-mcp-other-proc-7", "make", "other chat's make", -1,
                    java.time.Instant.now(), BackgroundProcessManager.ProcessState.RUNNING, null, null, null,
                    Map.of("sharedProcessId", "proc-7", "parentSessionId", "other-chat"));
            processes.upsertShared("shared-mcp-1-proc-7", "make", "make", -1, java.time.Instant.now(),
                    BackgroundProcessManager.ProcessState.RUNNING, null, null, log,
                    Map.of("sharedProcessId", "proc-7", "parentSessionId", "chat-session"));
            String input = "{\"action\":\"launch\",\"command\":\"make\"}";
            panel.recordToolStart("toolu_launch", "mcp__kompile__process", input);
            // Claude Code hands back only the MCP result text.
            panel.recordToolComplete("toolu_launch", "mcp__kompile__process", input, ToolResult.success(
                    "launched", "Launched background process:\n  ID:      proc-7\n  PID:     42"));

            var row = toolItem(panel, "tool:toolu_launch");
            assertNotNull(row);
            assertTrue(row.status().contains("proc-7 running"), row.status());
            var view = openView(panel, "tool:toolu_launch");
            assertTrue(view.content().contains("live log: proc-7"), view.content());
            assertTrue(view.content().contains("remote build line"), view.content());
        }
    }

    @Test
    void mcpBashCallShowsItsLiveLogInItsOwnRowOnly(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace) throws Exception {
        BackgroundTaskManager tasks = new BackgroundTaskManager();
        try (BackgroundProcessManager processes = new BackgroundProcessManager("chat-session", workspace)) {
            StatusBar bar = new StatusBar(tasks, processes, null, new TerminalRenderer(false));
            StandardChatActivityPanel panel = new StandardChatActivityPanel(tasks, processes, bar, () -> 6);
            String input = "{\"command\":\"mvn -q test\",\"description\":\"run tests\"}";
            panel.recordToolStart("toolu_bash", "mcp__kompile__bash", input);
            var log = workspace.resolve("toolcall.log");
            java.nio.file.Files.writeString(log, "Tests run: 3, Failures: 0\n");
            var callLog = processes.upsertShared("shared-mcp-1-toolcall-1", "mvn -q test", "bash: run tests", -1,
                    java.time.Instant.now(), BackgroundProcessManager.ProcessState.RUNNING, null, null, log,
                    Map.of("sharedProcessId", "toolcall-1", "parentSessionId", "chat-session",
                            "kind", StandardChatActivityPanel.TOOL_CALL_PROCESS_KIND));
            var foreign = processes.upsertShared("shared-mcp-2-toolcall-2", "mvn -q test", "bash: run tests", -1,
                    java.time.Instant.now(), BackgroundProcessManager.ProcessState.RUNNING, null, null, log,
                    Map.of("sharedProcessId", "toolcall-2", "parentSessionId", "other-chat",
                            "kind", StandardChatActivityPanel.TOOL_CALL_PROCESS_KIND));

            var items = panel.activityItems();
            assertTrue(items.stream().noneMatch(item -> item.key().equals("process:" + callLog.getId())),
                    "the call's log is folded into its tool row: " + items);
            assertTrue(items.stream().noneMatch(item -> item.key().equals("process:" + foreign.getId())),
                    "another chat's shell call is not this pane's work: " + items);
            assertTrue(panel.resultDisplayBlocks().stream().noneMatch(block -> block.toString().contains("Tests run")),
                    "the provider's own tool card carries the output; no duplicate transcript card");

            var view = openView(panel, "tool:toolu_bash");
            assertTrue(view.content().contains("Tests run: 3"), view.content());

            panel.recordToolComplete("toolu_bash", "mcp__kompile__bash", input,
                    ToolResult.success("exit 0", "Tests run: 3, Failures: 0"));
            assertFalse(panel.currentView().content().contains("live log:"),
                    "once the result arrives it is the record, not the duplicate log");
        }
    }

    private static StandardChatActivityPanel.ActivityItem toolItem(StandardChatActivityPanel panel, String key) {
        return panel.activityItems().stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    private static StandardChatActivityPanel.ActivityView openView(StandardChatActivityPanel panel, String key) {
        for (int i = 0; i < 16 && panel.selectNext(); i++) {
            var view = panel.openSelectedView();
            if (view != null && key.equals(view.key())) return view;
        }
        fail("no selectable row " + key + " in " + panel.activityItems());
        return null;
    }

    private static void awaitOutput(BackgroundProcessManager processes, String id, String text) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!processes.readOutput(id, 50).contains(text)) {
            if (System.nanoTime() > deadline) fail("process " + id + " never printed " + text);
            Thread.sleep(50);
        }
    }
}
