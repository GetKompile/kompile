package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.Insights;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.Panel;
import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.KompileTui;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDashboardControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loadsOnceAndRefreshesOnlyAfterExactSuccessfulTriggers() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-controller-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicInteger calls = new AtomicInteger();
            McpDashboardController controller = controller(tui, calls,
                    """
                    {"schemaVersion":"kompile.dashboard.v1","title":"Elthoria · Ashen March",
                     "contextVersion":"ctx-1","lines":["Campaign  Ashen March","World  Week 4 · Summer"]}
                    """);

            controller.prepare();
            assertTrue(tui.getDashboardSnapshot().visible());
            assertEquals(List.of("Loading current state…"), tui.getDashboardSnapshot().lines());

            McpDashboardController.RefreshResult startup = controller.refresh();
            assertTrue(startup.refreshed());
            assertEquals(1, calls.get());
            assertEquals("Elthoria · Ashen March", tui.getDashboardSnapshot().title());

            controller.onToolComplete("mcp__elthoria__get_game", "{}", success());
            assertEquals(1, calls.get());

            controller.onToolComplete("mcp_tool_call",
                    "{\"tool\":\"mcp__elthoria__advance_world_week\",\"arguments\":{}}",
                    success());
            awaitCalls(calls, 2);
            assertEquals(2, calls.get());

            controller.onToolComplete("mcp__elthoria__advance_world_week", "{}",
                    ToolResult.error("failed"));
            assertEquals(2, calls.get());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void hideAndShowPreserveTheLastGoodSnapshotWithoutPolling() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-hide-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicInteger calls = new AtomicInteger();
            McpDashboardController controller = controller(tui, calls,
                    "{\"schemaVersion\":\"kompile.dashboard.v1\",\"title\":\"Game\",\"contextVersion\":\"v1\",\"lines\":[\"Ready\"]}");
            controller.refresh();

            assertEquals("Dashboard hidden.", controller.command("hide").message());
            assertFalse(tui.getDashboardSnapshot().visible());
            controller.onToolComplete("mcp__elthoria__advance_world_week", "{}", success());
            assertEquals(1, calls.get());

            assertEquals("Dashboard shown; refresh scheduled.", controller.command("show").message());
            awaitCalls(calls, 2);
            awaitDashboard(tui, List.of("Ready"));
            assertTrue(tui.getDashboardSnapshot().visible());
            assertEquals(List.of("Ready"), tui.getDashboardSnapshot().lines());
            assertEquals(2, calls.get(), "showing dirty retained state must refresh exactly once");
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void invalidRefreshKeepsTheLastGoodDashboard() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-last-good-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicReference<String> response = new AtomicReference<>(
                    "{\"schemaVersion\":\"kompile.dashboard.v1\",\"title\":\"Game\",\"contextVersion\":\"v1\",\"lines\":[\"Ready\"]}");
            McpDashboardController controller = new McpDashboardController(
                    config(), response::get, tui, mapper);
            assertTrue(controller.refresh().refreshed());

            response.set("{\"schemaVersion\":\"unknown\",\"lines\":[]}");
            assertFalse(controller.refresh().refreshed());
            assertEquals("Game", tui.getDashboardSnapshot().title());
            assertEquals(List.of("Ready"), tui.getDashboardSnapshot().lines());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void hiddenMutationCannotBeClearedByAnOlderInFlightRefresh() throws Exception {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-generation-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch olderFetchStarted = new CountDownLatch(1);
            CountDownLatch releaseOlderFetch = new CountDownLatch(1);
            McpDashboardController controller = new McpDashboardController(
                    config(), () -> {
                        int call = calls.incrementAndGet();
                        if (call == 2) {
                            olderFetchStarted.countDown();
                            releaseOlderFetch.await();
                        }
                        String state = call >= 3 ? "new state" : "old state";
                        return "{\"schemaVersion\":\"kompile.dashboard.v1\","
                                + "\"title\":\"Game\",\"contextVersion\":\"v" + call
                                + "\",\"lines\":[\"" + state + "\"]}";
                    }, tui, mapper);
            assertTrue(controller.refresh().refreshed());

            controller.requestRefresh();
            assertTrue(olderFetchStarted.await(2, TimeUnit.SECONDS));
            controller.command("hide");
            controller.onToolComplete(
                    "mcp__elthoria__advance_world_week", "{}", success());
            releaseOlderFetch.countDown();

            assertEquals("Dashboard shown; refresh scheduled.",
                    controller.command("show").message());
            awaitDashboard(tui, List.of("new state"));
            assertTrue(calls.get() >= 3);
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void parserBoundsContentAndRemovesTerminalControls() throws Exception {
        String lines = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> "\"line " + index + " " + "x".repeat(120) + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        String raw = "{\"schemaVersion\":\"kompile.dashboard.v1\","
                + "\"title\":\"\\u001b[31mDanger\\u001b[0m\\u0007\","
                + "\"contextVersion\":\"ctx\",\"lines\":[" + lines + "]}";

        McpDashboardController.DashboardSnapshot snapshot =
                McpDashboardController.parse(raw, mapper);

        assertEquals("Danger", snapshot.title());
        assertEquals(8, snapshot.lines().size());
        assertTrue(snapshot.lines().stream().allMatch(line ->
                line.codePointCount(0, line.length()) <= 100));
        assertTrue(snapshot.lines().stream().noneMatch(line -> line.contains("\u001b")));
    }

    @Test
    void sourceRefreshHasAHardCancellableTimeout() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-timeout-test");
        try {
            KompileTui tui = tui(processes, true);
            CountDownLatch blocked = new CountDownLatch(1);
            McpDashboardController controller = new McpDashboardController(
                    config(), () -> {
                        blocked.await();
                        return "never";
                    }, tui, mapper, 25L);

            long started = System.nanoTime();
            McpDashboardController.RefreshResult result = controller.refresh();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(result.refreshed());
            assertTrue(result.message().contains("timed out"));
            assertTrue(elapsedMillis < 1_000, "dashboard timeout must remain bounded");
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void unwrapsOnlyTheGatewayToolField() {
        assertEquals("mcp__elthoria__advance_world_week",
                McpDashboardController.effectiveToolName("mcp_tool_call",
                        "{\"tool\":\"mcp__elthoria__advance_world_week\"}", mapper));
        assertEquals("mcp__elthoria__next_turn",
                McpDashboardController.effectiveToolName(
                        "mcp__elthoria__next_turn", "{}", mapper));
        assertEquals("", McpDashboardController.effectiveToolName(
                "mcp_tool_call", "not-json", mapper));
    }

    @Test
    void headlessToolCompletionNeverFetchesAnInvisibleDashboard() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-headless-test");
        try {
            KompileTui tui = tui(processes, false);
            AtomicInteger calls = new AtomicInteger();
            McpDashboardController controller = controller(tui, calls,
                    "{\"schemaVersion\":\"kompile.dashboard.v1\",\"title\":\"Game\",\"lines\":[\"Ready\"]}");

            controller.onToolComplete(
                    "mcp__elthoria__advance_world_week", "{}", success());

            assertEquals(0, calls.get());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void withoutAProjectDashboardTheChatShowsItsSessionPanel() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-panel-test");
        try {
            KompileTui tui = tui(processes, true);
            PanelSource source = new PanelSource(Panel.Line.of("Tools: 3 calls · p50 120ms"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);
            assertTrue(controller.isConfigured());

            controller.prepare();
            assertEquals(SessionInsightsPanel.TITLE, tui.getDashboardSnapshot().title());
            assertEquals(List.of("Loading session insights…"), tui.getDashboardSnapshot().lines());

            assertTrue(controller.refresh().refreshed());
            assertEquals(SessionInsightsPanel.TITLE, tui.getDashboardSnapshot().title());
            assertEquals(List.of("Tools: 3 calls · p50 120ms"), tui.getDashboardSnapshot().lines());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void theSessionPanelRefreshesAfterEveryToolCallFailedOnesIncluded() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-every-call-test");
        try {
            KompileTui tui = tui(processes, true);
            PanelSource source = new PanelSource(Panel.Line.of("Tools: 1 call"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);
            assertTrue(controller.refresh().refreshed());
            assertEquals(1, source.asks.get());

            controller.onToolComplete("bash", "{\"command\":\"false\"}", ToolResult.error("exit 1"));
            awaitAsks(source, 2);
            controller.onToolComplete("read", "{}", success());
            awaitAsks(source, 3);

            source.line = Panel.Line.of("Tools: 4 calls · 1 failed");
            controller.onToolComplete("edit", "{}", success());
            awaitDashboard(tui, List.of("Tools: 4 calls · 1 failed"));
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void aHeadlessChatOnlyMarksItsSessionPanelStale() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-headless-test");
        try {
            KompileTui tui = tui(processes, false);
            PanelSource source = new PanelSource(Panel.Line.of("Tools: 1 call"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);

            controller.onToolComplete("bash", "{}", success());

            assertEquals(0, source.asks.get());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void aFirstFailureExplainsItselfAndArmsTheRetryTimer() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-first-failure-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicReference<ToolContext> context = new AtomicReference<>();
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            McpDashboardController controller = sessionController(tui, context::get, source);

            McpDashboardController.RefreshResult failed = controller.refresh();
            assertFalse(failed.refreshed());
            assertEquals("Dashboard refresh failed: the chat session is not ready", failed.message());
            assertEquals(SessionInsightsPanel.TITLE, tui.getDashboardSnapshot().title());
            assertEquals(List.of("Session insights unavailable · /dashboard refresh", "the chat session is not ready"),
                    tui.getDashboardSnapshot().lines());
            PanelWatcher watcher = controller.panelWatcher();
            assertNotNull(watcher, "a panel whose first read failed is retried on the timer");
            assertTrue(watcher.isRunning());

            context.set(session(tempDir));
            assertTrue(controller.refresh().refreshed());
            assertEquals(List.of("Judge: no verdicts yet"), tui.getDashboardSnapshot().lines());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void aLaterFailureKeepsTheSessionRowsWithoutAnAlert() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-later-failure-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicReference<ToolContext> context = new AtomicReference<>(session(tempDir));
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            McpDashboardController controller = sessionController(tui, context::get, source);
            assertTrue(controller.refresh().refreshed());
            String alert = tui.getCurrentAlert();

            context.set(null);
            assertFalse(controller.refresh().refreshed());

            assertEquals(List.of("Judge: no verdicts yet"), tui.getDashboardSnapshot().lines());
            assertEquals(alert, tui.getCurrentAlert(), "the panel tries again on its timer, quietly");
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void unchangedSessionRowsAreNotRedrawn() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-redraw-test");
        try {
            AtomicInteger draws = new AtomicInteger();
            KompileTui tui = tui(processes, true, draws);
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);

            assertTrue(controller.refresh().refreshed());
            assertEquals(1, draws.get());
            assertTrue(controller.refresh().refreshed());
            assertEquals(1, draws.get(), "the same rows: nothing to draw");

            source.line = Panel.Line.of("Judge: 1 flagged");
            assertTrue(controller.refresh().refreshed());
            assertEquals(2, draws.get());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void aProjectDashboardTakesTheAreaInsteadOfTheSessionPanel() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-precedence-test");
        try {
            KompileTui tui = tui(processes, true);
            AtomicInteger calls = new AtomicInteger();
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            McpDashboardController controller = new McpDashboardController(config(), () -> {
                calls.incrementAndGet();
                return "{\"schemaVersion\":\"kompile.dashboard.v1\",\"title\":\"Game\",\"contextVersion\":\"v1\",\"lines\":[\"Ready\"]}";
            }, tui, mapper, McpDashboardController.SOURCE_TIMEOUT_MILLIS, sessionPanel(() -> session(tempDir), source));

            controller.prepare();
            assertEquals(List.of("Loading current state…"), tui.getDashboardSnapshot().lines());
            assertTrue(controller.refresh().refreshed());
            assertEquals("Game", tui.getDashboardSnapshot().title());
            assertEquals(1, calls.get());

            controller.onToolComplete("bash", "{}", success());
            assertEquals(1, calls.get(), "the project dashboard refreshes only after its own triggers");
            assertEquals(0, source.asks.get());
            assertNull(controller.panelWatcher());
            assertEquals("Dashboard hidden.", controller.command("hide").message());
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void hidingTheSessionPanelSaysHowToKeepItOff() {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-hide-test");
        try {
            KompileTui tui = tui(processes, true);
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);
            assertTrue(controller.refresh().refreshed());

            String hidden = controller.command("hide").message();
            assertTrue(hidden.startsWith("Dashboard hidden."), hidden);
            assertTrue(hidden.contains("\"sessionPanel\": false"), hidden);
            assertTrue(hidden.contains(String.valueOf(InsightsConfig.configFile())), hidden);
            assertFalse(tui.getDashboardSnapshot().visible());

            controller.onToolComplete("bash", "{}", success());
            assertEquals(1, source.asks.get(), "a hidden panel is only marked stale");
            assertEquals("Dashboard shown; refresh scheduled.", controller.command("show").message());
            awaitAsks(source, 2);
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void aWriteToAFileThePanelReadsRefreshesIt() throws Exception {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-watch-test");
        try {
            KompileTui tui = tui(processes, true);
            PanelSource source = new PanelSource(Panel.Line.of("Tools: no calls yet"));
            source.watches = List.of(new Panel.Watch(tempDir, "chat-1.jsonl"));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);
            assertTrue(controller.refresh().refreshed());
            assertTrue(controller.panelWatcher().isWatching(tempDir));

            source.line = Panel.Line.of("Tools: 1 call");
            Files.writeString(tempDir.resolve("chat-1.jsonl"), "{}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            awaitAsks(source, 2);
            awaitDashboard(tui, List.of("Tools: 1 call"));
            controller.close();
        } finally {
            processes.close();
        }
    }

    @Test
    void closingTheControllerStopsTheSessionPanelsWatcher() throws Exception {
        BackgroundProcessManager processes = new BackgroundProcessManager("dashboard-session-close-test");
        try {
            KompileTui tui = tui(processes, true);
            PanelSource source = new PanelSource(Panel.Line.of("Judge: no verdicts yet"));
            source.watches = List.of(new Panel.Watch(tempDir, null));
            McpDashboardController controller = sessionController(tui, () -> session(tempDir), source);
            assertTrue(controller.refresh().refreshed());
            PanelWatcher watcher = controller.panelWatcher();
            assertTrue(watcher.isRunning());

            controller.close();

            assertNull(controller.panelWatcher());
            awaitStopped(watcher);
            assertFalse(watcher.isWatching(tempDir));
            controller.onToolComplete("bash", "{}", success());
            assertFalse(controller.requestRefresh());
            assertEquals(1, source.asks.get());
        } finally {
            processes.close();
        }
    }

    private McpDashboardController controller(
            KompileTui tui, AtomicInteger calls, String response) {
        return new McpDashboardController(config(), () -> {
            synchronized (calls) {
                calls.incrementAndGet();
                calls.notifyAll();
            }
            return response;
        }, tui, mapper);
    }

    private McpConfigStore.DashboardConfig config() {
        return new McpConfigStore.DashboardConfig(
                "elthoria",
                "mcp__elthoria__get_play_dashboard",
                mapper.createObjectNode(),
                Set.of("mcp__elthoria__advance_world_week"));
    }

    private KompileTui tui(BackgroundProcessManager processes, boolean interactive) {
        return tui(processes, interactive, new AtomicInteger());
    }

    /** @param draws counts the dashboards set */
    private KompileTui tui(BackgroundProcessManager processes, boolean interactive, AtomicInteger draws) {
        return new KompileTui(new BackgroundTaskManager(), processes,
                new MessageQueue("dashboard-queue"), new TerminalRenderer(false)) {
            @Override
            public boolean isStarted() {
                return interactive;
            }

            @Override
            public void setDashboard(String title, List<String> lines) {
                super.setDashboard(title, lines);
                draws.incrementAndGet();
                synchronized (this) {
                    notifyAll();
                }
            }
        };
    }

    /** A chat without a project dashboard, showing its session panel over {@code sources}. */
    private McpDashboardController sessionController(
            KompileTui tui, Supplier<ToolContext> context, InsightSource... sources) {
        return new McpDashboardController(null, null, tui, mapper,
                McpDashboardController.SOURCE_TIMEOUT_MILLIS, sessionPanel(context, sources));
    }

    private SessionInsightsPanel sessionPanel(Supplier<ToolContext> context, InsightSource... sources) {
        return new SessionInsightsPanel(context, () -> McpDashboardController.MAX_LINES, (ignored, dir) -> {
            Insights insights = new Insights();
            for (InsightSource source : sources) {
                insights.register(source);
            }
            return insights;
        }, Clock.systemUTC(), mapper);
    }

    private static ToolContext session(Path workDir) {
        return new ToolContext("chat-1", null, null, workDir, null);
    }

    private static void awaitAsks(PanelSource source, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (source.asks) {
            while (source.asks.get() < expected) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(source.asks, left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the session panel", interrupted);
                }
            }
        }
        assertEquals(expected, source.asks.get());
    }

    private static void awaitStopped(PanelWatcher watcher) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (watcher.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(watcher.isRunning(), "the watcher's thread ends with the controller");
    }

    private ToolResult success() {
        return ToolResult.success("ok", "ok", Map.of());
    }

    private void awaitCalls(AtomicInteger calls, int expected) {
        synchronized (calls) {
            if (calls.get() < expected) {
                try {
                    calls.wait(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for dashboard refresh", interrupted);
                }
            }
        }
        assertEquals(expected, calls.get());
    }

    private void awaitDashboard(KompileTui tui, List<String> expectedLines) {
        synchronized (tui) {
            if (!expectedLines.equals(tui.getDashboardSnapshot().lines())) {
                try {
                    tui.wait(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for dashboard render", interrupted);
                }
            }
        }
        assertEquals(expectedLines, tui.getDashboardSnapshot().lines());
    }

    /** A session-panel source with one row, counting the refreshes that read it. */
    private static final class PanelSource implements InsightSource {
        final AtomicInteger asks = new AtomicInteger();
        volatile Panel.Line line;
        volatile List<Panel.Watch> watches = List.of();

        PanelSource(Panel.Line line) {
            this.line = line;
        }

        @Override
        public String topic() {
            return "judge";
        }

        @Override
        public String description() {
            return "judge verdicts";
        }

        @Override
        public List<String> keywords() {
            return List.of("judge");
        }

        @Override
        public InsightReport report(InsightsQuery query) {
            throw new AssertionError("the panel must not build a report");
        }

        @Override
        public Panel.Line panelLine(InsightsQuery query) {
            synchronized (asks) {
                asks.incrementAndGet();
                asks.notifyAll();
            }
            return line;
        }

        @Override
        public List<Panel.Watch> panelWatches(InsightsQuery query) {
            return watches;
        }
    }
}
