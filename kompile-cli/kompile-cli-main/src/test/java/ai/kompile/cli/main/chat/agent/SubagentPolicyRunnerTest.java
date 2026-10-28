package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.chat.workflow.WorkflowPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
@Timeout(15)
class SubagentPolicyRunnerTest {
    @TempDir Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    final PermissionService permissions = new PermissionService();
    final ToolRegistry tools = new ToolRegistry(mapper);
    final AtomicInteger clients = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void reset() throws Exception {
        SubagentConfig.defaults().save(SubagentConfig.path());
        permissions.allowAll();
    }
    @AfterEach void noSlotLeak() { assertEquals(0, SubagentAdmission.activeCount()); }

    private ToolContext parent() {
        var context = new ToolContext("parent", AgentConfig.builder("parent").build(), permissions, dir, tools);
        context.setOutputConsumer(ignored -> {});
        context.setSubagentSupervision(new DirectSubagentSupervision.Contract(
                new WorkflowController.ChildContract(WorkflowPolicy.off(), "", false), "test",
                null, null, null, false, 1));
        return context;
    }

    private ChatConfig config() {
        var config = new ChatConfig("custom", "test", "inherited-model", "http://unused.invalid");
        config.setAutoCompactEnabled(false);
        return config;
    }

    private DirectSubagentRunner runner(CountDownLatch started, CountDownLatch release, boolean fail) {
        return new DirectSubagentRunner(config(), mapper, tools, permissions, new TerminalRenderer(false)) {
            @Override protected DirectLlmClient createClient(ChatConfig config, ToolContext context) {
                clients.incrementAndGet();
                return new DirectLlmClient(config, mapper) {
                    @Override public StreamResult streamChat(String message, String system, ArrayNode definitions,
                            List<ToolCallResultInput> results, String model) {
                        requests.incrementAndGet();
                        if (started != null) started.countDown();
                        var response = new StreamResult();
                        if (release != null) {
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException e) { response.cancelled = true; return response; }
                        }
                        if (fail) throw new IllegalStateException("provider failed");
                        response.text = "done";
                        return response;
                    }
                };
            }
        };
    }

    @Test void bansCheckInheritedAndExplicitEffectiveModelsBeforeCreatingTransport() throws Exception {
        new SubagentConfig(3, List.of("*inherited*", "*haiku*")).save(SubagentConfig.path());
        try (var runner = runner(null, null, false)) {
            assertThrows(IllegalStateException.class, () -> runner.runSubagent(AgentConfig.builder("child").build(), "test", parent()));
            assertThrows(IllegalStateException.class, () -> runner.runSubagent(
                    AgentConfig.builder("child").modelOverride("claude-haiku-4.5").build(), "test", parent()));
            assertEquals(0, clients.get());
            assertEquals("done", runner.runSubagent(AgentConfig.builder("child").modelOverride("allowed").build(), "test", parent()));
            assertEquals(1, requests.get());
        }
    }

    @Test void separateRunnersShareTheCapAndCancellationReleasesOnlyAfterRunEnds() throws Exception {
        new SubagentConfig(1, List.of()).save(SubagentConfig.path());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var first = runner(started, release, false); var second = runner(null, null, false)) {
            var id = new AtomicReference<String>();
            first.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                public void onSubagentStart(String childId, String type, String description) { id.set(childId); }
                public void onSubagentEnd(String childId) { }
            });
            var future = pool.submit(() -> first.runSubagent(AgentConfig.builder("first").build(), "test", parent()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(1, SubagentAdmission.activeCount());
            assertThrows(IllegalStateException.class, () -> second.runSubagent(AgentConfig.builder("second").build(), "test", parent()));
            assertEquals(1, clients.get());
            assertTrue(first.cancel(id.get()));
            future.get(5, TimeUnit.SECONDS);
            assertEquals(0, SubagentAdmission.activeCount());
            assertEquals("done", second.runSubagent(AgentConfig.builder("second").build(), "test", parent()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void queuedFollowupCanReuseCapacityAtALimitOfOne() throws Exception {
        new SubagentConfig(1, List.of()).save(SubagentConfig.path());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var id = new AtomicReference<String>();
        var followup = new AtomicReference<String>();
        var pool = Executors.newSingleThreadExecutor();
        try (var runner = runner(started, release, false)) {
            var endings = new AtomicInteger();
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                public void onSubagentStart(String childId, String type, String description) { id.set(childId); }
                public void onSubagentEnd(String childId) {
                    // Queue after the conversation loop returns, while the old run still owns its lease.
                    if (endings.incrementAndGet() == 1) assertTrue(runner.sendMessage(childId, "queued followup"));
                }
            });
            runner.setAsyncCompletionListener((childId, result) -> { followup.set(result); completed.countDown(); });
            var initial = pool.submit(() -> runner.runSubagent(AgentConfig.builder("child").build(), "first", parent()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("done", initial.get(5, TimeUnit.SECONDS));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runner.hasPendingWork(id.get()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(runner.hasPendingWork(id.get()));
            assertEquals("done", followup.get());
            assertEquals(2, requests.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void providerAndStartupObserverFailuresReleaseSlots() throws Exception {
        new SubagentConfig(1, List.of()).save(SubagentConfig.path());
        try (var failed = runner(null, null, true); var observer = runner(null, null, false)) {
            assertThrows(IllegalStateException.class, () -> failed.runSubagent(AgentConfig.builder("child").build(), "test", parent()));
            assertEquals(0, SubagentAdmission.activeCount());
            observer.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                public void onSubagentStart(String id, String type, String description) { throw new IllegalStateException("observer"); }
                public void onSubagentEnd(String id) { }
            });
            assertThrows(IllegalStateException.class, () -> observer.runSubagent(AgentConfig.builder("child").build(), "test", parent()));
        }
    }

    @Test void savedModelsAreRecheckedOnDurableResumeAndRetainedFollowups() throws Exception {
        var id = new AtomicReference<String>();
        var followup = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        try (var runner = runner(null, null, false); var resumer = runner(null, null, false)) {
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                public void onSubagentStart(String childId, String type, String description) { id.set(childId); }
                public void onSubagentEnd(String childId) { }
            });
            runner.setAsyncCompletionListener((childId, result) -> { followup.set(result); done.countDown(); });
            runner.runSubagent(AgentConfig.builder("child").modelOverride("saved-model").build(), "first", parent(), SubagentRunOptions.resumable("policy-child"));
            new SubagentConfig(1, List.of("saved-*")).save(SubagentConfig.path());
            assertThrows(IllegalStateException.class, () -> resumer.resumeSubagent(AgentConfig.builder("child").build(), id.get(), "resume", parent()));
            assertEquals(1, clients.get(), "durable resume must be denied before creating transport");
            assertTrue(runner.sendMessage(id.get(), "retained followup"));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runner.hasPendingWork(id.get()) && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(runner.hasPendingWork(id.get()));
            assertTrue(followup.get().contains("banned by pattern"));
            assertEquals(1, requests.get());
            assertEquals("done", resumer.resumeSubagent(AgentConfig.builder("child").modelOverride("allowed").build(), id.get(), "allowed route", parent()));
        }
    }

    @Test void serverRouteUsesSharedCapAndRefusesUnknownModelWhenBansExist() throws Exception {
        var server = new ServerSubagentRunner("http://unused.invalid", tools, permissions, mapper, new TerminalRenderer(false));
        new SubagentConfig(1, List.of()).save(SubagentConfig.path());
        try (var lease = SubagentAdmission.acquire("custom", "allowed")) {
            assertTrue(assertThrows(IllegalStateException.class, () -> server.runSubagent(AgentConfig.builder("child").build(), "test", parent()))
                    .getMessage().contains("concurrency limit"));
        }
        new SubagentConfig(1, List.of("*haiku*")).save(SubagentConfig.path());
        assertTrue(assertThrows(IllegalStateException.class, () -> server.runSubagent(AgentConfig.builder("child").build(), "test", parent()))
                .getMessage().contains("cannot be verified"));
    }
}
