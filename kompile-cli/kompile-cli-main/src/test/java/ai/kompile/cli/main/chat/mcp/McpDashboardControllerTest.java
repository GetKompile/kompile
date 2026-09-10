package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.KompileTui;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDashboardControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
        return new KompileTui(new BackgroundTaskManager(), processes,
                new MessageQueue("dashboard-queue"), new TerminalRenderer(false)) {
            @Override
            public boolean isStarted() {
                return interactive;
            }

            @Override
            public void setDashboard(String title, List<String> lines) {
                super.setDashboard(title, lines);
                synchronized (this) {
                    notifyAll();
                }
            }
        };
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
}
