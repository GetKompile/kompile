package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ResourceMonitorCommandsTest {
    static final class TestView implements ResourceMonitorCommands.View {
        volatile boolean selected;
        boolean available = true;
        volatile String content;
        final CountDownLatch updated = new CountDownLatch(1);
        public boolean available() { return available; }
        public boolean selected() { return selected; }
        public void show(String text) { content = text; selected = true; }
        public void update(String text) { content = text; updated.countDown(); }
        public void hide() { selected = false; }
    }

    @Test void bareCommandStartsLiveViewAndStopDoesNotStopChildren() throws Exception {
        TestView view = new TestView();
        AtomicInteger controls = new AtomicInteger();
        try (var monitor = new ResourceMonitorCommands(() -> "CPU live", view,
                (action, id) -> { controls.incrementAndGet(); return "killed"; })) {
            assertEquals("", monitor.command(""));
            assertTrue(view.updated.await(3, TimeUnit.SECONDS));
            assertEquals("CPU live", view.content);
            assertTrue(monitor.command("stop").contains("subprocesses keep running"));
            assertFalse(view.selected);
            assertEquals(0, controls.get());
        }
    }

    @Test void mutationsRequireExactShapeAndConfirmation() throws Exception {
        AtomicInteger controls = new AtomicInteger();
        try (var monitor = new ResourceMonitorCommands(() -> "snapshot", new TestView(),
                (action, id) -> { controls.incrementAndGet(); return action + ":" + id; })) {
            for (String args : new String[]{"kill", "kill proc-1", "kill proc-1 yes", "kill proc-1 confirm extra",
                    "embeddings stop", "embeddings stop yes", "embeddings start confirm"})
                assertTrue(monitor.command(args).contains("Usage:"), args);
            assertEquals(0, controls.get());
            assertEquals("kill:proc-1", monitor.command("kill proc-1 confirm"));
            assertEquals("kill:watchdog:serving-x", monitor.command("kill watchdog:serving-x confirm"));
            assertEquals("embeddings:", monitor.command("embeddings stop confirm"));
            assertEquals(3, controls.get());
        }
    }

    @Test void unavailableTerminalIsHonestSnapshotAndDoesNotStartFollower() throws Exception {
        TestView view = new TestView(); view.available = false;
        AtomicInteger samples = new AtomicInteger();
        try (var monitor = new ResourceMonitorCommands(() -> "sample " + samples.incrementAndGet(), view,
                (action, id) -> fail("unexpected mutation"))) {
            assertTrue(monitor.command("").contains("require an interactive CLI terminal"));
            assertFalse(view.selected);
            monitor.refresh();
            assertEquals(1, samples.get());
        }
    }

    @Test void leavingViewAndClosingPreventsStaleRefresh() throws Exception {
        TestView view = new TestView();
        CountDownLatch sampling = new CountDownLatch(1), release = new CountDownLatch(1);
        var monitor = new ResourceMonitorCommands(() -> {
            sampling.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return "stale";
        }, view, (action, id) -> fail("unexpected mutation"));
        monitor.command("show");
        assertTrue(sampling.await(3, TimeUnit.SECONDS));
        view.selected = false;
        monitor.close();
        release.countDown();
        assertFalse(view.updated.await(100, TimeUnit.MILLISECONDS));
        assertEquals("Resource monitor is closed.", monitor.command("show"));
    }

    @Test void slashRouterUsesLivePageStartsAtTopAndClosesFollower() throws Exception {
        var repl = org.mockito.Mockito.mock(ChatRepl.class);
        var tui = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.tui.KompileTui.class);
        var permissions = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.permission.PermissionService.class);
        var loop = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.agent.AgenticChatLoop.class);
        var manager = org.mockito.Mockito.mock(ai.kompile.cli.main.chat.tools.BackgroundProcessManager.class);
        org.mockito.Mockito.when(repl.getWorkingDirectory()).thenReturn(java.nio.file.Path.of("."));
        org.mockito.Mockito.when(repl.getTui()).thenReturn(tui);
        org.mockito.Mockito.when(tui.isStarted()).thenReturn(true);
        var selected = new java.util.concurrent.atomic.AtomicReference<>("");
        org.mockito.Mockito.when(tui.getContentViewKey()).thenAnswer(ignored -> selected.get());
        org.mockito.Mockito.doAnswer(call -> { selected.set(call.getArgument(0)); return null; })
                .when(tui).showActivityView(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.when(loop.getCurrentAgentConfig()).thenReturn(
                org.mockito.Mockito.mock(ai.kompile.cli.main.chat.agent.AgentConfig.class));
        var router = new ChatCommandRouter(repl, null, null, null, null,
                ai.kompile.cli.common.util.JsonUtils.standardMapper(), "resource-router", true,
                null, null, new ai.kompile.cli.main.chat.render.TerminalRenderer(false),
                null, null, null, null, null, permissions, loop, null, manager, null, java.util.List.of(), null);
        try {
            assertTrue(router.handleSlashCommand("/resource-monitor help"));
            var monitor = (ResourceMonitorCommands) org.springframework.test.util.ReflectionTestUtils.getField(router, "resourceMonitor");
            org.springframework.test.util.ReflectionTestUtils.setField(monitor, "sample",
                    (java.util.function.Supplier<String>) () -> "HOST CPU 5%\nCHATS RSS 32 MiB");
            assertTrue(router.handleSlashCommand("/resource-monitor"));
            org.mockito.Mockito.verify(tui, org.mockito.Mockito.timeout(3000)).updateActivityView(
                    org.mockito.ArgumentMatchers.eq(ResourceMonitorCommands.VIEW_KEY),
                    org.mockito.ArgumentMatchers.eq(ResourceMonitorCommands.TITLE),
                    org.mockito.ArgumentMatchers.contains("HOST CPU"));
            org.mockito.Mockito.verify(tui, org.mockito.Mockito.timeout(3000).times(2)).scrollToTop();
            router.closeDashboardReports();
            assertEquals("Resource monitor is closed.", monitor.command("show"));
            org.mockito.Mockito.verifyNoInteractions(manager);
        } finally { router.closeDashboardReports(); }
    }

    @Test void commandIsReservedAndTerminalOnly() {
        assertTrue(ChatCommandCatalog.isBuiltin("resource-monitor"));
        assertEquals(ChatCommandCatalog.WebSupport.TERMINAL_REQUIRED, ChatCommandCatalog.webSupport("resource-monitor"));
        assertThrows(IllegalArgumentException.class, () -> new ai.kompile.cli.main.chat.skill.SkillRegistry()
                .register(ai.kompile.cli.main.chat.skill.SkillConfig.builder("resource-monitor").build()));
    }
}
