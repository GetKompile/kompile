package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgentLoopDetectionTest {
    @TempDir Path directory;
    private String previousHome;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final AtomicInteger executions = new AtomicInteger();
    private final PermissionService permissions = new PermissionService();
    private volatile java.util.concurrent.CountDownLatch stubbornStarted;
    private volatile java.util.concurrent.CountDownLatch stubbornRelease;

    @BeforeEach
    void isolateHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
        ChatCompleter.setTranscriptBlockOutput(null);
    }

    @Test
    void mainStopsForeverRepeatingAgentAndWarnsOnWire() {
        ScriptedClient client = new ScriptedClient(1, 0);
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        String session = "loop-" + UUID.randomUUID();
        String response = loop.chat("inspect", session, "coder", "default", false);
        assertTrue(response.contains("Loop guard stopped"), response);
        assertEquals(5, executions.get());
        assertEquals(5, client.requests);
        assertTrue(client.messages.stream().anyMatch(m -> m != null && m.contains("[Loop guard]")),
                "warnings must reach the model, not only the terminal");
        // A genuine new user turn must not inherit a prior turn's detector.
        loop.chat("retry after new guidance", session, "coder", "default", false);
        assertEquals(10, executions.get());
    }

    @Test
    void mainStopsMidBatchAndClosesEveryRequestedEnvelope() throws Exception {
        ScriptedClient client = new ScriptedClient(8, 0);
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        assertTrue(loop.chat("inspect", "batch-" + UUID.randomUUID(), "coder", "default", false)
                .contains("Loop guard stopped"));
        assertEquals(5, executions.get(), "the remaining calls must not run");
        Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
        field.setAccessible(true);
        var entries = ((ConversationLedger) field.get(loop)).snapshot().activeEntries();
        assertEquals(8, entries.stream().filter(e -> e.type == CompactionService.EntryType.TOOL_CALL).count());
        assertEquals(8, entries.stream().filter(e -> e.type == CompactionService.EntryType.TOOL_RESULT).count());
        assertEquals(3, entries.stream().filter(e -> e.content != null
                && e.content.startsWith("Not executed: Loop guard")).count());
        assertTrue(entries.stream().anyMatch(e -> e.type == CompactionService.EntryType.ASSISTANT
                && e.content.contains("Loop guard stopped")), "halt must survive replay");
    }

    @Test
    void missingToolRetryLoopIsStopped() {
        ScriptedClient client = new ScriptedClient(1, 0);
        String response = mainLoop(client, new ToolRegistry(mapper)).chat(
                "inspect", "missing-" + UUID.randomUUID(), "coder", "default", false);
        assertTrue(response.contains("Loop guard stopped"));
        assertEquals(5, client.requests);
        assertEquals(0, executions.get());
    }

    @Test
    void unexpectedToolFailureLoopIsStopped() {
        ScriptedClient client = new ScriptedClient(1, 0);
        String response = mainLoop(client, tools(false, true)).chat(
                "inspect", "failed-" + UUID.randomUUID(), "coder", "default", false);
        assertTrue(response.contains("Loop guard stopped"));
        assertEquals(5, executions.get());
    }

    @Test
    void repeatedCommentaryOverChangingResultsIsNotALoop() {
        ScriptedClient client = new ScriptedClient(1, 12);
        String response = mainLoop(client, tools(true, false)).chat(
                "inspect", "progress-" + UUID.randomUUID(), "coder", "default", false);
        assertFalse(response.contains("Loop guard"));
        assertTrue(response.endsWith("done"));
        assertEquals(12, executions.get());
    }

    @Test
    void childStopsRepeatingAndReportsFailureRatherThanCompletion() throws Exception {
        ScriptedClient client = new ScriptedClient(1, 0);
        ToolRegistry tools = tools(false, false);
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> runner.runSubagent(childAgent(), "inspect", parent(tools)));
            assertTrue(failure.getMessage().contains("Loop guard stopped"));
            assertEquals(5, executions.get());
            assertTrue(client.messages.stream().anyMatch(m -> m != null && m.contains("[Loop guard]")));
        }
    }

    @Test
    void childSkipsRemainderOfRepeatingBatch() throws Exception {
        ScriptedClient client = new ScriptedClient(8, 0);
        ToolRegistry tools = tools(false, false);
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            assertThrows(IllegalStateException.class,
                    () -> runner.runSubagent(childAgent(), "inspect", parent(tools)));
            assertEquals(5, executions.get());
            assertEquals(1, client.requests);
            assertEquals(8, client.replayedResults, "stopped child history must close every tool call");
        }
    }

    @Test
    void childCanContinueWhileResultsChange() throws Exception {
        ScriptedClient client = new ScriptedClient(1, 12);
        ToolRegistry tools = tools(true, false);
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            assertTrue(runner.runSubagent(childAgent(), "inspect", parent(tools)).endsWith("done"));
            assertEquals(12, executions.get());
        }
    }

    @Test
    void mainStopsProviderOwnedLoopInsideOneRequestWithoutCancellingSession() {
        ScriptedClient client = new ScriptedClient(1, 0);
        client.nativeLoop = true;
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        loop.setCancelSignal(cancelled);
        String session = "native-" + UUID.randomUUID();
        assertTrue(loop.chat("inspect", session, "coder", "default", false).contains("Loop guard stopped"));
        assertEquals(1, client.requests, "native tools never return to the outer model loop");
        assertEquals(5, client.nativeCalls);
        assertFalse(cancelled.get(), "automatic halt must not poison user cancellation state");
        client.nativeLoop = false;
        client.finishNow = true;
        assertEquals("done", loop.chat("new guidance", session, "coder", "default", false));
    }

    @Test
    void childStopsProviderOwnedLoopEvenWithoutLifecycleListener() throws Exception {
        ScriptedClient client = new ScriptedClient(1, 0);
        client.nativeLoop = true;
        ToolRegistry tools = tools(false, false);
        ToolContext parent = parent(tools);
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> runner.runSubagent(childAgent(), "inspect", parent));
            assertTrue(failure.getMessage().contains("Loop guard stopped"));
            assertEquals(1, client.requests);
            assertEquals(5, client.nativeCalls);
            assertFalse(parent.isAborted());
            assertFalse(client.getCancellationCheck().getAsBoolean(),
                    "the stopped turn's guard must not remain installed in a retained child");
        }
    }

    @Test
    void callerCancellationIsHonoredAndOriginalPredicateRestored() {
        ScriptedClient client = new ScriptedClient(1, 0);
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        java.util.function.BooleanSupplier original = () -> true;
        client.setCancellationCheck(original);
        client.probeCancellation = true;
        String result = loop.chat("inspect", "caller-cancel-" + UUID.randomUUID(), "coder", "default", false);
        assertTrue(result.contains("["), "a cancelled response must not look like silent successful completion");
        assertTrue(client.sawCancellation);
        assertTrue(result.contains("partial cancelled response"));
        assertTrue(client.replayedText.contains("partial cancelled response"),
                "the cancelled turn's partial reply must survive canonical-history replay");
        assertEquals(0, executions.get());
        assertSame(original, client.getCancellationCheck());
    }

    @Test
    void providerExceptionRestoresCancellationPredicate() {
        ScriptedClient client = new ScriptedClient(1, 0);
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        java.util.function.BooleanSupplier original = () -> false;
        client.setCancellationCheck(original);
        client.failRequest = true;
        assertThrows(IllegalStateException.class,
                () -> loop.chat("inspect", "exception-" + UUID.randomUUID(), "coder", "default", false));
        assertSame(original, client.getCancellationCheck());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"start", "input", "output", "complete"})
    void displayFailuresDoNotStopWorkOrLeakTheTemporaryCancellationScope(String failedPhase) {
        ChatConfig nativeConfig = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        nativeConfig.setAuthenticationMethod("oauth");
        nativeConfig.setDefaultMemory(false);
        nativeConfig.setContextWindowTokens(200_000);
        ScriptedClient client = new ScriptedClient(1, 0, nativeConfig);
        client.leaveNativeToolOpen = true;
        AgenticChatLoop loop = mainLoop(client, tools(false, false));
        java.util.function.BooleanSupplier original = () -> false;
        java.util.function.Consumer<String> output = ignored -> { };
        client.setCancellationCheck(original);
        client.setOutputConsumer(output);
        AtomicInteger failures = new AtomicInteger();
        loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
            private void observe(String phase) {
                if (failedPhase.equals(phase)) {
                    failures.incrementAndGet();
                    throw new IllegalStateException("display " + phase + " failed");
                }
            }
            @Override public void onToolStart(String id, String name, String input) { observe("start"); }
            @Override public void onToolInput(String id, String name, String input) { observe("input"); }
            @Override public void onToolOutput(String id, String name, String input, String text) { observe("output"); }
            @Override public void onToolComplete(String id, String name, String input, ToolResult result) {
                observe("complete");
            }
        });
        assertEquals("partial", loop.chat("inspect", "cleanup-" + UUID.randomUUID(), "coder", "default", false));
        assertEquals(1, failures.get());
        assertSame(original, client.getCancellationCheck());
        assertSame(output, client.getOutputConsumer());
        assertNull(client.getProviderActivityListener());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationReleasesChildEvenWhenToolSwallowsInterruption(boolean abortParent) throws Exception {
        ScriptedClient client = new ScriptedClient(1, 0);
        ToolRegistry tools = tools(false, false);
        ToolContext parent = parent(tools);
        stubbornStarted = new java.util.concurrent.CountDownLatch(1);
        stubbornRelease = new java.util.concurrent.CountDownLatch(1);
        var childId = new java.util.concurrent.atomic.AtomicReference<String>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String prompt) { childId.set(id); }
                @Override public void onSubagentEnd(String id) { }
            });
            var result = executor.submit(() -> runner.runSubagent(childAgent(), "inspect", parent));
            assertTrue(stubbornStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (abortParent) parent.abort();
            else assertTrue(runner.cancel(childId.get()));
            assertTrue(result.get(2, java.util.concurrent.TimeUnit.SECONDS).contains("Subagent aborted"));
            assertEquals(abortParent, parent.isAborted(), "a child's cancellation must not cancel its parent");
            assertFalse(runner.hasPendingWork(childId.get()));
        } finally {
            stubbornRelease.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void policyRewritesCountActualExecutedInputsAsProgressAndReachModelHistory() throws Exception {
        ScriptedClient client = new ScriptedClient(1, 12);
        ToolRegistry tools = tools(false, false);
        ToolContext parent = parent(tools);
        AtomicInteger rewrites = new AtomicInteger();
        var reviewer = new ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator() {
            public boolean isAvailable() { return true; }
            public String describe() { return "fixture reviewer"; }
            public ai.kompile.cli.main.chat.enforcer.EnforcerDecision evaluate(String prompt, String text,
                    ai.kompile.cli.main.chat.enforcer.EnforcerPolicy policy, int attempt) {
                return ai.kompile.cli.main.chat.enforcer.EnforcerDecision.pass("ok");
            }
        };
        parent.setSubagentSupervision(new DirectSubagentSupervision.Contract(
                new ai.kompile.cli.main.chat.workflow.WorkflowController.ChildContract(
                        ai.kompile.cli.main.chat.workflow.WorkflowPolicy.off(), "", false),
                "inspect", reviewer, ai.kompile.cli.main.chat.enforcer.EnforcerPolicy.from("read only", 3),
                (prompt, text, name, input) -> new ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision(
                        ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision.Action.REWRITE,
                        "correct target", List.of(), "", java.util.Map.of("file_path", "file-"
                                + rewrites.incrementAndGet() + ".java")), true, 3));
        try (DirectSubagentRunner runner = childRunner(client, tools)) {
            assertTrue(runner.runSubagent(childAgent(), "inspect", parent).endsWith("done"));
            assertEquals(12, executions.get());
            assertEquals(12, client.toolOutputs.size());
            assertTrue(client.toolOutputs.get(0).contains("Arguments rewritten"));
            assertTrue(client.toolOutputs.get(0).contains("file-1.java"));
            assertTrue(client.toolOutputs.get(11).contains("file-12.java"));
        }
    }

    private AgenticChatLoop mainLoop(ScriptedClient client, ToolRegistry tools) {
        return new AgenticChatLoop(null, mapper, tools, permissions, new AgentRegistry(), directory, client, null);
    }

    private DirectSubagentRunner childRunner(ScriptedClient client, ToolRegistry tools) {
        return new DirectSubagentRunner(config(), mapper, tools, permissions, new TerminalRenderer(false)) {
            @Override protected DirectLlmClient createClient(ChatConfig config, ToolContext context) {
                return client;
            }
        };
    }

    private ToolContext parent(ToolRegistry tools) {
        ToolContext context = new ToolContext("parent", AgentConfig.builder("parent").build(),
                permissions, directory, tools);
        context.setOutputConsumer(ignored -> {});
        return context;
    }

    private AgentConfig childAgent() {
        return AgentConfig.builder("explore").isSubagent(true).systemPrompt("Inspect files").build();
    }

    private ToolRegistry tools(boolean progressing, boolean throwsError) {
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(new CliTool() {
            @Override public String id() { return "read"; }
            @Override public String description() { return "test read"; }
            @Override public String permissionKey() { return "read"; }
            @Override public JsonNode parameterSchema() { return mapper.createObjectNode().put("type", "object"); }
            @Override public ToolResult execute(JsonNode params, ToolContext context) {
                int count = executions.incrementAndGet();
                var release = stubbornRelease;
                if (release != null) {
                    stubbornStarted.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); }
                        catch (InterruptedException ignored) { /* deliberately uncooperative test tool */ }
                    }
                }
                if (throwsError) throw new LinkageError("test missing dependency");
                return ToolResult.success(progressing ? "file version " + count : "unchanged file");
            }
        });
        return registry;
    }

    private static ChatConfig config() {
        ChatConfig config = new ChatConfig("custom", "test", "loop-test", "http://unused.invalid");
        config.setAutoCompactEnabled(false);
        return config;
    }

    private final class ScriptedClient extends DirectLlmClient {
        final List<String> messages = new ArrayList<>();
        final List<String> replayedText = new ArrayList<>();
        final List<String> toolOutputs = new ArrayList<>();
        final int batchSize;
        final int stopAfter;
        int requests;
        int replayedResults;
        int nativeCalls;
        boolean nativeLoop;
        boolean finishNow;
        boolean probeCancellation;
        boolean sawCancellation;
        boolean failRequest;
        boolean leaveNativeToolOpen;

        ScriptedClient(int batchSize, int stopAfter) {
            this(batchSize, stopAfter, config());
        }

        ScriptedClient(int batchSize, int stopAfter, ChatConfig config) {
            super(config, mapper);
            this.batchSize = batchSize;
            this.stopAfter = stopAfter;
        }

        @Override public StreamResult streamChat(String message, String system, ArrayNode definitions,
                List<ToolCallResultInput> results, String model) {
            return streamChat(message, system, definitions, results, model, null);
        }

        @Override public StreamResult streamChat(String message, String system, ArrayNode definitions,
                List<ToolCallResultInput> results, String model, List<AttachmentInput> attachments) {
            assertTrue(++requests <= 40, "harness failed to interrupt repeating provider");
            messages.add(message);
            if (results != null) results.forEach(result -> toolOutputs.add(result.output));
            if (failRequest) throw new IllegalStateException("provider request failed");
            StreamResult result = new StreamResult();
            if (probeCancellation) {
                sawCancellation = isCancelled();
                result.text = "partial cancelled response";
                result.cancelled = sawCancellation;
                return result;
            }
            if (leaveNativeToolOpen) {
                getProviderActivityListener().onToolStart("unfinished", "Read", "{\"file_path\":\"x\"}");
                getProviderActivityListener().onToolInput("unfinished", "Read", "{\"file_path\":\"x\"}");
                getProviderActivityListener().onToolOutput("unfinished", "Read", "file content");
                result.text = "partial";
                return result;
            }
            if (nativeLoop) {
                ProviderActivityListener listener = getProviderActivityListener();
                assertNotNull(listener);
                for (int i = 0; i < 40 && !isCancelled(); i++) {
                    String callId = "native-" + i;
                    listener.onToolStart(callId, "Read", "{}");
                    listener.onToolInput(callId, "Read", "{\"file_path\":\"same.java\"}");
                    listener.onToolComplete(callId, "Read", "unchanged", 0, false);
                    nativeCalls++;
                }
                assertTrue(isCancelled(), "provider must observe cooperative cancellation mid-stream");
                result.cancelled = true;
                return result;
            }
            if (finishNow || stopAfter > 0 && requests > stopAfter) {
                result.text = "done";
                return result;
            }
            result.text = "I am inspecting the file. ".repeat(12);
            for (int i = 0; i < batchSize; i++) {
                ToolCallOutput call = new ToolCallOutput();
                call.id = "call-" + requests + "-" + i;
                call.name = "read";
                call.arguments = mapper.createObjectNode().put("file_path", "same.java");
                result.toolCalls.add(call);
            }
            return result;
        }

        @Override public void addToHistory(String role, String text) {
            replayedText.add(text);
            super.addToHistory(role, text);
        }

        @Override public void addReplayedToolResults(List<ToolCallResultInput> results, String model) {
            replayedResults += results.size();
            super.addReplayedToolResults(results, model);
        }
    }
}
