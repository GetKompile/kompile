package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.enforcer.EnforcerJudge;
import ai.kompile.cli.main.chat.harness.JudgeBackend;
import ai.kompile.cli.main.chat.harness.ResilientJudgeBackend;
import ai.kompile.cli.main.chat.harness.PerformanceHarness;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.mcp.McpConfigStore;
import ai.kompile.cli.main.chat.mcp.McpDashboardController;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises component dispatch, rather than invoking context wrappers directly. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("SYSTEM_ERR")
class ChatAsyncDispatchContextTest {
    @TempDir Path home;

    @Test
    void maintenanceFailureAndSuccessorHandoffKeepConstructionOwner() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            ChatRepl repl = mock(ChatRepl.class);
            ChatHistory history = mock(ChatHistory.class);
            AtomicBoolean busy = new AtomicBoolean();
            when(repl.isLlmBusy()).thenAnswer(call -> busy.get());
            doAnswer(call -> { busy.set(call.getArgument(0)); return null; })
                    .when(repl).setLlmBusy(anyBoolean());
            doAnswer(call -> { a.emit("failure-handler"); return null; })
                    .when(history).logSystem(anyString());
            ChatMessageHandler handler;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                handler = new ChatMessageHandler(repl, null, null, new ObjectMapper(), "a", true,
                        history, null, null, new TerminalRenderer(), null,
                        mock(AgenticChatLoop.class), mock(BackgroundTaskManager.class), mock(MessageQueue.class),
                        new AtomicBoolean(), List.of(), null);
            }
            AtomicInteger releases = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Thread> first = new AtomicReference<>(), successor = new AtomicReference<>();
            doAnswer(call -> {
                a.emit("handoff");
                if (releases.incrementAndGet() == 1) {
                    // This is the real dispatch-owner finally -> successor dispatch seam.
                    assertTrue(handler.dispatchMaintenanceTurn(() -> {
                        successor.set(Thread.currentThread());
                        a.emit("successor");
                    }, "context-successor"));
                } else {
                    done.countDown();
                }
                return null;
            }).when(repl).dispatchQueuedMessageAfterTurnRelease();
            try (var ui = b.ui.bind(); var log = b.log.bind()) {
                assertTrue(handler.dispatchMaintenanceTurn(() -> {
                    first.set(Thread.currentThread());
                    a.emit("maintenance");
                    throw new IllegalStateException("expected turn failure");
                }, "context-maintenance"));
                assertTrue(done.await(5, TimeUnit.SECONDS));
                join(first.get());
                join(successor.get());
                b.assertCurrent();
            }
            assertFalse(busy.get(), "finally must release the turn reservation");
            assertEquals(2, releases.get());
            a.assertObservations(5);
            assertTrue(a.output.containsAll(List.of("maintenance", "failure-handler", "handoff", "successor")));
            assertTrue(b.output.isEmpty());
            a.assertLoggedOnlyHere(b, "failure-handler");
            a.assertLoggedOnlyHere(b, "successor");
        }
    }

    @Test
    void deadlineBackendUsesConstructionOwnerAndLeavesCallerAndPoolUnbound() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            JudgeBackend backend = new JudgeBackend() {
                @Override public String generate(String user, String system) {
                    a.emit("deadline");
                    return "ok";
                }
                @Override public boolean isAvailable() { return true; }
            };
            ResilientJudgeBackend resilient;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                resilient = new ResilientJudgeBackend(backend, List.of(), 5_000, 1_000);
            }
            try {
                try (var ui = b.ui.bind(); var log = b.log.bind()) {
                    assertEquals("ok", resilient.generate("u", "s"));
                    b.assertCurrent();
                }
                // Probe the component executor without a wrapper: no owner may leak out.
                ExecutorService executor = field(resilient, "executor", ExecutorService.class);
                assertNull(executor.submit(TranscriptLogScope::currentExplicitScope).get(5, TimeUnit.SECONDS));
                assertSame(ChatUiSession.current(), executor.submit(ChatUiSession::current).get(5, TimeUnit.SECONDS));
                a.assertObservations(1);
                assertEquals(List.of("deadline"), a.output);
                assertTrue(b.output.isEmpty());
                a.assertLoggedOnlyHere(b, "deadline");
            } finally {
                resilient.close();
            }
        }
    }

    @Test
    void warmupFinallyNotifiesRegistrationOwnerThenRestoresWarmupOwner() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            JudgeBackend backend = new JudgeBackend() {
                @Override public String generate(String user, String system) { return "ok"; }
                @Override public void warmUp(String system) {
                    a.emit("warmup");
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test wait expired");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("expected warmup failure");
                }
                @Override public boolean isAvailable() {
                    a.emit("warmup-finally");
                    return true;
                }
            };
            EnforcerJudge judge;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                judge = new EnforcerJudge(backend, new ObjectMapper());
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                try (var ui = b.ui.bind(); var log = b.log.bind()) {
                    judge.addStateListener(() -> b.emit("registered-listener"));
                }
                try (var ui = a.ui.bind(); var log = a.log.bind()) {
                    judge.addStateListener(() -> a.emit("listener-after-b"));
                }
                release.countDown();
                assertTrue(judge.awaitWarm(5_000));
                a.assertObservations(3);
                b.assertObservations(1);
                a.assertLoggedOnlyHere(b, "warmup-finally");
                a.assertLoggedOnlyHere(b, "listener-after-b");
                b.assertLoggedOnlyHere(a, "registered-listener");
            } finally {
                release.countDown();
                assertTrue(judge.awaitWarm(5_000));
                judge.close();
            }
        }
    }

    @Test
    void outputReaderOwnsCallbackAndStreamCleanupEvenAfterUiCloses() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            // Exercise the actual reader without starting an external OpenCode process.
            Class<?> type = Class.forName("ai.kompile.cli.main.chat.config.OpenCodeServeClient");
            var constructor = type.getDeclaredConstructor(ObjectMapper.class, Path.class);
            constructor.setAccessible(true);
            Object client;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                client = constructor.newInstance(new ObjectMapper(), home);
            }
            Method readLines = type.getDeclaredMethod("readLines", java.io.InputStream.class,
                    StringBuilder.class, Consumer.class);
            readLines.setAccessible(true);
            try {
                for (int round = 0; round < 2; round++) {
                    if (round == 1) a.ui.close();
                    AtomicBoolean cleaned = new AtomicBoolean();
                    var input = new ByteArrayInputStream("line\n".getBytes(StandardCharsets.UTF_8)) {
                        @Override public void close() {
                            a.emit("reader-cleanup");
                            cleaned.set(true);
                        }
                    };
                    StringBuilder sink = new StringBuilder();
                    try (var ui = b.ui.bind(); var log = b.log.bind()) {
                        Thread reader = (Thread) readLines.invoke(client, input, sink,
                                (Consumer<String>) line -> a.emit("reader-callback"));
                        join(reader);
                        b.assertCurrent();
                    }
                    assertTrue(cleaned.get(), "closed UI must not skip stream cleanup");
                    assertEquals("line\n", sink.toString());
                }
                a.assertObservations(4);
                assertEquals(List.of("reader-callback", "reader-cleanup"), a.output,
                        "second reader still runs but its closed UI suppresses output");
                assertTrue(b.output.isEmpty());
                a.assertLoggedOnlyHere(b, "reader-callback");
                a.assertLoggedOnlyHere(b, "reader-cleanup");
            } finally {
                ((AutoCloseable) client).close();
            }
        }
    }

    @Test
    void unboundDeadlineComponentKeepsDynamicLegacyLogSelection() throws Exception {
        try (Environment env = new Environment(home);
             TranscriptLogScope parent = TranscriptLogScope.open("legacy-parent", home, false);
             Owner sibling = new Owner("sibling", home)) {
            JudgeBackend backend = new JudgeBackend() {
                @Override public String generate(String user, String system) {
                    System.err.println("dynamic-legacy-worker");
                    return TranscriptLogScope.currentTranscriptId();
                }
                @Override public boolean isAvailable() { return true; }
            };
            ResilientJudgeBackend resilient = new ResilientJudgeBackend(backend, List.of(), 5_000, 1_000);
            try (TranscriptLogScope child = TranscriptLogScope.open("legacy-child", home, false);
                 var ui = sibling.ui.bind(); var log = sibling.log.bind()) {
                assertEquals("legacy-child", resilient.generate("u", "s"));
                sibling.assertCurrent();
                assertTrue(Files.readString(child.logFile()).contains("dynamic-legacy-worker"));
                assertFalse(Files.readString(parent.logFile()).contains("dynamic-legacy-worker"));
                assertFalse(Files.readString(sibling.log.logFile()).contains("dynamic-legacy-worker"));
            } finally {
                resilient.close();
            }
        }
    }

    @Test
    void toolWorkerDetachedFailureAndLateOutputKeepLoopOwner() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            ObjectMapper mapper = new ObjectMapper();
            ToolRegistry tools = new ToolRegistry(mapper);
            PermissionService permissions = new PermissionService();
            AgentRegistry agents = new AgentRegistry();
            AgenticChatLoop loop;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                loop = new AgenticChatLoop(null, mapper, tools, permissions, agents, home,
                        null, null, new SkillRegistry());
            }
            ToolContext context = new ToolContext("a", agents.getDefault(), permissions, home, tools);
            CountDownLatch release = new CountDownLatch(1), completed = new CountDownLatch(1);
            AtomicReference<ToolContext> executing = new AtomicReference<>();
            AtomicReference<Thread> completionThread = new AtomicReference<>();
            AtomicReference<ToolResult> terminalResult = new AtomicReference<>();
            CliTool tool = mock(CliTool.class);
            when(tool.execute(any(), any())).thenAnswer(call -> {
                executing.set(call.getArgument(1));
                a.emit("tool-worker");
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test wait expired");
                throw new ToolExecutionException("expected tool failure");
            });
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                loop.backgroundActiveTurn(line -> a.emit("late-tool-output"), () -> { }, result -> {
                    completionThread.set(Thread.currentThread());
                    terminalResult.set(result);
                    a.emit("detached-finally");
                    completed.countDown();
                });
            }
            field(loop, "blockingSubagentInvocation", AtomicBoolean.class).set(true);
            Method execute = AgenticChatLoop.class.getDeclaredMethod("executeToolInterruptibly",
                    CliTool.class, com.fasterxml.jackson.databind.JsonNode.class, ToolContext.class,
                    String.class, java.util.function.Function.class);
            execute.setAccessible(true);
            try {
                try (var ui = b.ui.bind(); var log = b.log.bind()) {
                    execute.invoke(loop, tool, mapper.createObjectNode(), context, "task",
                            (java.util.function.Function<ToolResult, Path>) result -> {
                                a.emit("save-completion");
                                return null;
                            });
                    release.countDown();
                    assertTrue(completed.await(5, TimeUnit.SECONDS));
                    join(completionThread.get());
                    // Retained tool output may be called by a foreign worker after completion.
                    executing.get().emitOutput("late output");
                    b.assertCurrent();
                }
                assertTrue(terminalResult.get().isError());
                assertTrue(terminalResult.get().getOutput().contains("expected tool failure"));
                a.assertObservations(4);
                assertTrue(b.output.isEmpty());
                a.assertLoggedOnlyHere(b, "tool-worker");
                a.assertLoggedOnlyHere(b, "save-completion");
                a.assertLoggedOnlyHere(b, "detached-finally");
                a.assertLoggedOnlyHere(b, "late-tool-output");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void postTurnHarnessDispatchKeepsOwnerOnItsReusedExecutor() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            JudgeBackend backend = new JudgeBackend() {
                @Override public String generate(String user, String system) {
                    a.emit("post-turn-judge");
                    return "{\"correctness\":5,\"completeness\":5,\"reasoning\":\"done\"}";
                }
                @Override public boolean isAvailable() { return true; }
            };
            PerformanceHarness harness;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                harness = new PerformanceHarness(null,
                        new ChatConfig("custom", null, "worker", "http://unused.invalid"),
                        new ObjectMapper(), new TerminalRenderer(false), null, null, backend);
            }
            harness.getConfig().setEnabled(true);
            harness.setJudgeGlobalEnabled(true);
            harness.getConfig().setJudgeEnabled(true);
            harness.getConfig().setEscapeDetectionEnabled(false);
            harness.getConfig().setThinkingAnalysisEnabled(false);
            harness.getConfig().setAutoSwapEnabled(false);
            harness.getConfig().setPersistCrossSession(false);
            try {
                try (var ui = b.ui.bind(); var log = b.log.bind()) {
                    harness.evaluateTurnAsync("coder", "worker", "Four.", "a", 1L);
                    ExecutorService executor = field(harness, "judgeExecutor", ExecutorService.class);
                    // Single-thread executor: this probe runs after the actual evaluation.
                    assertNull(executor.submit(TranscriptLogScope::currentExplicitScope).get(5, TimeUnit.SECONDS));
                    b.assertCurrent();
                }
                a.assertObservations(1);
                a.assertLoggedOnlyHere(b, "post-turn-judge");
                assertTrue(b.output.isEmpty());
            } finally {
                harness.shutdown();
            }
        }
    }

    @Test
    void dashboardFetchAndRefreshDispatchKeepOwnerAndRestoreBothWorkers() throws Exception {
        try (Environment env = new Environment(home);
             Owner a = new Owner("a", home); Owner b = new Owner("b", home)) {
            Class<?> fetcherType = Class.forName(McpDashboardController.class.getName() + "$DashboardFetcher");
            Object fetcher = mock(fetcherType, invocation -> {
                a.emit("dashboard-fetch");
                return "{\"schemaVersion\":\"kompile.dashboard.v1\",\"title\":\"Test\",\"lines\":[\"Ready\"]}";
            });
            KompileTui tui = mock(KompileTui.class);
            doAnswer(call -> { a.emit("dashboard-apply"); return null; })
                    .when(tui).setDashboard(anyString(), anyList());
            var constructor = McpDashboardController.class.getDeclaredConstructor(
                    McpConfigStore.DashboardConfig.class, fetcherType, KompileTui.class, ObjectMapper.class);
            constructor.setAccessible(true);
            McpDashboardController controller;
            try (var ui = a.ui.bind(); var log = a.log.bind()) {
                controller = (McpDashboardController) constructor.newInstance(
                        new McpConfigStore.DashboardConfig("server", "tool", null, null),
                        fetcher, tui, new ObjectMapper());
            }
            try {
                try (var ui = b.ui.bind(); var log = b.log.bind()) {
                    assertTrue(controller.requestRefresh());
                    for (String field : List.of("refreshExecutor", "sourceExecutor")) {
                        ExecutorService executor = field(controller, field, ExecutorService.class);
                        assertNull(executor.submit(TranscriptLogScope::currentExplicitScope).get(5, TimeUnit.SECONDS));
                    }
                    b.assertCurrent();
                }
                a.assertObservations(2);
                assertEquals(List.of("dashboard-fetch", "dashboard-apply"), a.output);
                assertTrue(b.output.isEmpty());
                a.assertLoggedOnlyHere(b, "dashboard-fetch");
                a.assertLoggedOnlyHere(b, "dashboard-apply");
            } finally {
                controller.close();
            }
        }
    }

    private static void join(Thread worker) throws InterruptedException {
        assertNotNull(worker);
        worker.join(5_000);
        assertFalse(worker.isAlive(), "component worker did not finish");
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private record Observation(ChatUiSession ui, TranscriptLogScope log) { }

    private static final class Owner implements AutoCloseable {
        final ChatUiSession ui = new ChatUiSession();
        final TranscriptLogScope log;
        final List<String> output = new CopyOnWriteArrayList<>();
        final List<Observation> observations = new CopyOnWriteArrayList<>();

        Owner(String id, Path home) throws Exception {
            log = TranscriptLogScope.openIsolated(id, home, false);
            try (var binding = ui.bind()) { ChatCompleter.setContentOutput(output::add); }
        }

        void emit(String marker) {
            observations.add(new Observation(ChatUiSession.current(), TranscriptLogScope.currentExplicitScope()));
            ChatCompleter.printAbove(marker);
            System.err.println(marker);
        }

        void assertCurrent() {
            assertSame(ui, ChatUiSession.current());
            assertSame(log, TranscriptLogScope.currentExplicitScope());
        }

        void assertObservations(int count) {
            assertEquals(count, observations.size());
            for (Observation observation : observations) {
                assertSame(ui, observation.ui());
                assertSame(log, observation.log());
            }
        }

        void assertLoggedOnlyHere(Owner other, String marker) throws Exception {
            assertTrue(Files.readString(log.logFile()).contains(marker));
            assertFalse(Files.readString(other.log.logFile()).contains(marker));
        }

        @Override public void close() { ui.close(); log.close(); }
    }

    private static final class Environment implements AutoCloseable {
        private final String home = System.getProperty("user.home");
        private final String transcript = System.getProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
        private final PrintStream err = System.err;
        private final PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

        Environment(Path path) {
            System.setProperty("user.home", path.toString());
            System.clearProperty(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY);
            System.setErr(sink);
        }

        @Override public void close() {
            System.setErr(err);
            restore("user.home", home);
            restore(TranscriptLogScope.TRANSCRIPT_ID_PROPERTY, transcript);
            sink.close();
        }

        private static void restore(String name, String value) {
            if (value == null) System.clearProperty(name); else System.setProperty(name, value);
        }
    }
}
