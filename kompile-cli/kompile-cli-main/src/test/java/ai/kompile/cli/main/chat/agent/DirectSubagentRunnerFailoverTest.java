package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.SessionModelLadder;
import ai.kompile.cli.main.chat.SessionModelLadder.ModelRung;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Request-boundary tests: the transport is scripted and never performs network I/O. */
@TemporaryUserHome
@Timeout(20)
class DirectSubagentRunnerFailoverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path project;

    private record Request(String message, String model, String provider, String credential,
                           String auth, String thinking, String systemPrompt,
                           List<String> history, List<DirectLlmClient.ToolCallResultInput> pending,
                           int activeChildren) { }

    private static final class ScriptedClient extends DirectLlmClient {
        final ChatConfig config;
        final List<Request> requests = new ArrayList<>();
        final List<String> history = new ArrayList<>();
        final Function<Request, StreamResult> script;
        int budgetUpdates;
        int retainedNativeReplays;

        ScriptedClient(ChatConfig config, Path project, Function<Request, StreamResult> script) {
            super(config, JSON, project);
            this.config = config;
            this.script = script;
        }

        @Override public StreamResult streamChat(String message, String systemPrompt, ArrayNode tools,
                                                  List<ToolCallResultInput> pending, String model) {
            Request request = new Request(message, model, config.getProvider(), config.getCredentialName(),
                    config.getAuthenticationMethod(), config.getThinking(), systemPrompt,
                    List.copyOf(history), pending == null ? List.of() : List.copyOf(pending),
                    SubagentAdmission.activeCount());
            requests.add(request);
            StreamResult result = script.apply(request);
            if (!result.failed && !result.cancelled) {
                if (pending != null) addReplayedToolResults(pending, model);
                if (message != null) addToHistory("user", message);
                if (result.text != null && !result.text.isEmpty()) addToHistory("assistant", result.text);
                for (var call : result.toolCalls) {
                    addReplayedToolCalls(List.of(new ReplayedToolCallInput(
                            call.name, call.id, call.arguments.toString())), model);
                }
            }
            return result;
        }

        @Override public void clearHistory() { history.clear(); }
        @Override public void clearHistoryForProviderSwitch() {
            retainedNativeReplays++;
            history.clear();
            super.clearHistoryForProviderSwitch();
        }
        @Override public void addToHistory(String role, String content) { history.add(role + ":" + content); }
        @Override public void addReplayedToolCalls(List<ReplayedToolCallInput> calls, String model) {
            calls.forEach(call -> history.add("call:" + call.callId() + ":" + call.toolName()));
        }
        @Override public void addReplayedToolResults(List<ToolCallResultInput> results, String model) {
            results.forEach(result -> history.add("result:" + result.callId + ":" + result.output));
        }
        @Override public void setContextWindowTokens(int tokens) {
            budgetUpdates++;
            super.setContextWindowTokens(tokens);
        }
    }

    private static final class Runner extends DirectSubagentRunner {
        final Function<Request, DirectLlmClient.StreamResult> script;
        ScriptedClient client;
        String id;
        int starts;
        int ends;

        Runner(ChatConfig config, ToolRegistry tools, PermissionService permissions,
               Function<Request, DirectLlmClient.StreamResult> script) {
            super(config, JSON, tools, permissions, new TerminalRenderer(false));
            this.script = script;
            setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String child, String type, String description) {
                    id = child;
                    starts++;
                }
                @Override public void onSubagentEnd(String child) { ends++; }
            });
        }
        @Override protected DirectLlmClient createClient(ChatConfig config, ToolContext context) {
            return client = new ScriptedClient(config, context.getWorkingDirectory(), script);
        }
    }

    private static ChatConfig config() {
        ChatConfig config = new ChatConfig();
        config.setProvider("openai");
        config.setAuthenticationMethod("api-key");
        config.setApiKey("test-inline-secret");
        config.setModel("parent-model");
        config.setThinking("high");
        config.setContextWindowTokens(100_000);
        config.setMaxOutputTokens(256);
        config.setAutoCompactEnabled(false);
        return config;
    }

    private static ModelRung rung(String model) {
        // A legacy model-only rung inherits this fixture's inline test key.
        return new ModelRung("openai", model, null, null, "low");
    }

    private static void policy(boolean enabled, List<String> bans, ModelRung... rungs) throws Exception {
        new SubagentConfig(1, bans, enabled, List.of(rungs)).save(SubagentConfig.path());
    }

    private ToolContext parent(ToolRegistry tools, PermissionService permissions) {
        return new ToolContext("parent", AgentConfig.builder("parent").build(), permissions, project, tools);
    }

    private static PermissionService permissions() {
        PermissionService permissions = new PermissionService();
        permissions.allowAll();
        return permissions;
    }

    private static AgentConfig agent() {
        return AgentConfig.builder("child").isSubagent(true).modelOverride("child-primary").build();
    }

    private static DirectLlmClient.StreamResult quota() {
        var result = new DirectLlmClient.StreamResult();
        result.failed = true;
        result.failureKind = DirectLlmClient.FailureKind.QUOTA_EXHAUSTED;
        result.failureMessage = "weekly usage limit reached";
        return result;
    }

    private static DirectLlmClient.StreamResult done() {
        var result = new DirectLlmClient.StreamResult();
        result.text = "done";
        return result;
    }

    private static DirectLlmClient.StreamResult toolCall() {
        var result = new DirectLlmClient.StreamResult();
        var tool = new DirectLlmClient.ToolCallOutput();
        tool.id = "once-id";
        tool.name = "once";
        tool.arguments = JSON.createObjectNode();
        result.toolCalls.add(tool);
        return result;
    }

    private static final class Once implements CliTool {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String id() { return "once"; }
        @Override public String description() { return "A completed local side effect"; }
        @Override public String permissionKey() { return "read"; }
        @Override public JsonNode parameterSchema() { return JSON.createObjectNode().put("type", "object"); }
        @Override public ToolResult execute(JsonNode input, ToolContext context) {
            return ToolResult.success("completed-" + calls.incrementAndGet());
        }
    }

    @Test void quotaAfterCompletedToolPreservesChildLedgerAndNeverExecutesItTwice() throws Exception {
        policy(true, List.of(), rung("backup"));
        ChatConfig parentConfig = config();
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        var once = new Once();
        tools.register(once);
        AtomicInteger requests = new AtomicInteger();
        try (var runner = new Runner(parentConfig, tools, permissions, request -> switch (requests.incrementAndGet()) {
            case 1 -> toolCall();
            case 2 -> quota();
            default -> done();
        })) {
            assertEquals("done", runner.runSubagent(agent(), "initial task", parent(tools, permissions),
                    SubagentRunOptions.resumable("failover-ledger")));
            assertEquals(1, once.calls.get());
            assertEquals(List.of("child-primary", "child-primary", "backup"),
                    runner.client.requests.stream().map(Request::model).toList());
            Request retry = runner.client.requests.get(2);
            assertNull(retry.message());
            assertTrue(retry.pending().isEmpty(), "completed results were replayed, not submitted twice");
            assertEquals(List.of("user:initial task", "call:once-id:once", "result:once-id:completed-1"), retry.history());
            assertEquals(runner.client.requests.get(0).systemPrompt(), retry.systemPrompt(),
                    "failover must retain child supervision");
            assertTrue(runner.client.requests.stream().allMatch(request -> request.activeChildren() == 1));
            assertEquals(1, runner.starts);
            assertEquals(1, runner.ends);
            assertTrue(runner.client.budgetUpdates >= 2, "the fallback must resize the child compactor/client");
            assertEquals(1, runner.client.retainedNativeReplays, "failover must not dispose the native process/session");
            assertEquals("parent-model", parentConfig.getModel());
            assertEquals("high", parentConfig.getThinking());
            assertEquals("low", runner.client.config.getThinking());
            var service = new SubagentResumeService(JSON);
            var ledger = service.ledger(runner.id);
            assertEquals(1, ledger.stream().filter(entry -> entry.type == CompactionService.EntryType.USER).count());
            assertEquals(1, ledger.stream().filter(entry -> entry.type == CompactionService.EntryType.TOOL_RESULT
                    && "completed-1".equals(entry.content)).count());
            assertEquals("backup", service.metadataFor(runner.id).model(), "resume metadata follows the active child route");
        }
        assertEquals(0, SubagentAdmission.activeCount());
    }

    enum Unsafe { PARTIAL_OUTPUT, NATIVE_SIDE_EFFECT, RETURNED_TOOL_CALL, POLICY_REFUSAL, UNSAFE_OVERFLOW, CANCELLED }

    @ParameterizedTest @EnumSource(Unsafe.class)
    void unsafeOrRefusedResultsNeverSwitch(Unsafe hazard) throws Exception {
        policy(true, List.of(), rung("backup"));
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        try (var runner = new Runner(config(), tools, permissions, request -> {
            var result = quota();
            switch (hazard) {
                case PARTIAL_OUTPUT -> result.responseStarted = true;
                case NATIVE_SIDE_EFFECT -> result.providerSideEffectsObserved = true;
                case RETURNED_TOOL_CALL -> result.toolCalls.addAll(toolCall().toolCalls);
                case POLICY_REFUSAL -> result.failureKind = DirectLlmClient.FailureKind.REFUSAL;
                case UNSAFE_OVERFLOW -> {
                    result.failureKind = DirectLlmClient.FailureKind.CONTEXT_OVERFLOW;
                    result.responseStarted = true;
                }
                case CANCELLED -> result.cancelled = true;
            }
            return result;
        })) {
            if (hazard == Unsafe.CANCELLED) {
                assertTrue(runner.runSubagent(agent(), "task", parent(tools, permissions)).contains("aborted"));
            } else {
                assertThrows(IllegalStateException.class,
                        () -> runner.runSubagent(agent(), "task", parent(tools, permissions)));
            }
            assertEquals(1, runner.client.requests.size());
            assertEquals("child-primary", runner.client.config.getModel());
        }
        assertEquals(0, SubagentAdmission.activeCount());
    }

    @Test void currentBansAndAgentModelAllowlistAreCheckedBeforeApplyingFallback() throws Exception {
        policy(true, List.of(), rung("newly-banned"), rung("not-allowed"), rung("allowed"));
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        try (var runner = new Runner(config(), tools, permissions, request -> {
            if (request.model().equals("child-primary")) {
                try { policy(true, List.of("openai/newly-*"), rung("allowed")); }
                catch (Exception failure) { throw new AssertionError(failure); }
                return quota();
            }
            return done();
        })) {
            AgentConfig allowed = agent().toBuilder()
                    .allowedModels(List.of("child-primary", "newly-banned", "allowed")).build();
            assertEquals("done", runner.runSubagent(allowed, "task", parent(tools, permissions)));
            assertEquals(List.of("child-primary", "allowed"),
                    runner.client.requests.stream().map(Request::model).toList());
        }
    }

    @Test void disabledAndExhaustedPoliciesTerminateWithoutReplayingTheTask() throws Exception {
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        policy(false, List.of(), rung("backup"));
        try (var runner = new Runner(config(), tools, permissions, request -> quota())) {
            assertThrows(IllegalStateException.class, () -> runner.runSubagent(agent(), "task", parent(tools, permissions)));
            assertEquals(1, runner.client.requests.size());
        }
        policy(true, List.of(), rung("child-primary"), rung("backup-1"), rung("backup-2"));
        try (var runner = new Runner(config(), tools, permissions, request -> quota())) {
            assertThrows(IllegalStateException.class, () -> runner.runSubagent(agent(), "task", parent(tools, permissions)));
            assertEquals(List.of("child-primary", "backup-1", "backup-2"),
                    runner.client.requests.stream().map(Request::model).toList(),
                    "the source must track child modelOverride, not the parent's model");
            assertEquals("task", runner.client.requests.get(2).message());
            assertTrue(runner.client.requests.get(2).history().isEmpty(), "rejected initial tasks never enter saved history");
        }
    }

    @Test void durableResumeUsesFallbackIdentityUnlessTheCallerExplicitlyOverridesIt() throws Exception {
        CredentialStore.create().putApiKey("openai", "primary", "test-primary-secret", true);
        CredentialStore.create().putApiKey("openai", "backup-account", "test-backup-secret", false);
        policy(true, List.of(), new ModelRung("openai", "backup", "backup-account", null, "low", "api-key"));
        ChatConfig parentConfig = config();
        parentConfig.setApiKey(null);
        parentConfig.setAuthenticationScope("session");
        parentConfig.setCredentialName("primary");
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        String id;
        try (var runner = new Runner(parentConfig, tools, permissions,
                request -> request.model().equals("child-primary") ? quota() : done())) {
            assertEquals("done", runner.runSubagent(agent(), "original task", parent(tools, permissions),
                    SubagentRunOptions.resumable("durable-fallback")));
            id = runner.id;
        }
        var active = SessionModelLadder.forSubagent(id, project, List.of(), true).activeRung().orElseThrow();
        assertEquals("backup", active.model());
        assertEquals("backup-account", active.credentialName());
        assertEquals("api-key", active.authenticationMethod());
        assertEquals("low", active.thinking());
        var metadata = new SubagentResumeService(JSON).metadataFor(id);
        assertEquals("backup-account", metadata.credentialName());
        assertEquals("low", metadata.thinking());
        String persisted = Files.readString(SessionModelLadder.sessionPath(id))
                + Files.readString(SubagentResumeMetadata.sidecarFor(
                        ai.kompile.cli.common.KompileHome.homeDirectory().toPath().resolve("conversations"), id));
        assertFalse(persisted.contains("test-primary-secret"));
        assertFalse(persisted.contains("test-backup-secret"));

        AgentConfig noOverride = AgentConfig.builder("child").isSubagent(true).build();
        try (var resumed = new Runner(parentConfig, tools, permissions, request -> done())) {
            assertEquals("done", resumed.resumeSubagent(noOverride, id, "next task", parent(tools, permissions)));
            assertEquals(id, resumed.id);
            Request request = resumed.client.requests.get(0);
            assertEquals("backup", request.model());
            assertEquals("backup-account", request.credential());
            assertEquals("api-key", request.auth());
            assertEquals("low", request.thinking());
            assertEquals(1, request.history().stream().filter("user:original task"::equals).count());
        }
        AgentConfig explicit = noOverride.toBuilder().providerOverride("openai")
                .modelOverride("explicit-model").authenticationMethodOverride("api-key").thinkingOverride("medium").build();
        try (var resumed = new Runner(parentConfig, tools, permissions, request -> done())) {
            assertEquals("done", resumed.resumeSubagent(explicit, id, "explicit task", parent(tools, permissions)));
            Request request = resumed.client.requests.get(0);
            assertEquals("explicit-model", request.model());
            assertEquals("primary", request.credential());
            assertEquals("medium", request.thinking());
            assertEquals(id, resumed.id);
        }
        assertEquals("parent-model", parentConfig.getModel());
        assertEquals("primary", parentConfig.getCredentialName());
    }

    @Test void crossProviderFallbackReplaysCompletedToolResultsWithoutChangingToolControls() throws Exception {
        CredentialStore.create().putApiKey("anthropic", "other-account", "test-other-secret", false);
        policy(true, List.of(), new ModelRung("anthropic", "claude-sonnet-4-5", "other-account",
                null, "low", "api-key"));
        var permissions = permissions();
        var tools = new ToolRegistry(JSON);
        var once = new Once();
        tools.register(once);
        AtomicInteger calls = new AtomicInteger();
        try (var runner = new Runner(config(), tools, permissions, request -> switch (calls.incrementAndGet()) {
            case 1 -> toolCall();
            case 2 -> quota();
            default -> done();
        })) {
            assertEquals("done", runner.runSubagent(agent(), "cross-provider task", parent(tools, permissions)));
            Request retry = runner.client.requests.get(2);
            assertEquals("anthropic", retry.provider());
            assertEquals("claude-sonnet-4-5", retry.model());
            assertEquals("other-account", retry.credential());
            assertEquals("api-key", retry.auth());
            assertEquals("low", retry.thinking());
            assertEquals(List.of("user:cross-provider task", "call:once-id:once", "result:once-id:completed-1"),
                    retry.history());
            assertEquals(runner.client.requests.get(0).systemPrompt(), retry.systemPrompt());
            assertEquals(1, once.calls.get());
        }
    }
}
