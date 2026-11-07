/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.SessionModelLadder;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.AttachmentInput;
import ai.kompile.cli.main.chat.config.ConnectivityEvent;
import ai.kompile.cli.main.chat.config.FailureKind;
import ai.kompile.cli.main.chat.config.ProviderActivityListener;
import ai.kompile.cli.main.chat.config.ReplayedToolCallInput;
import ai.kompile.cli.main.chat.config.TokenCountResult;
import ai.kompile.cli.main.chat.config.ToolCallOutput;
import ai.kompile.cli.main.chat.config.ToolCallResultInput;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TemporaryUserHome
@Timeout(30)
class HeadlessAgentRunnerFailoverTest {
    private static final ObjectMapper JSON = JsonUtils.standardMapper();
    @TempDir Path project;

    @Test
    void rejectedRequestRetainsCompletedToolsAndChosenRouteOnNextWebTurn() throws Exception {
        String session = session();
        ladder(session, "{\"model\":\"backup\",\"thinking\":\"high\"}", "");
        Path input = project.resolve("completed-tool.txt");
        Files.writeString(input, "READ_COMPLETED_ONCE");
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<HeadlessRunEvent> events = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                // The isolated title request may arrive alongside the first web turn.
                if (request.toString().contains("Generate a concise, specific chat title")) {
                    answer(exchange, "test title");
                    return;
                }
                requests.add(request);
                if (requests.size() == 1) {
                    String arguments = JSON.createObjectNode().put("file_path", input.toString()).toString();
                    var function = JSON.createObjectNode().put("name", "read").put("arguments", arguments);
                    var tool = JSON.createObjectNode().put("index", 0).put("id", "read-once").put("type", "function");
                    tool.set("function", function);
                    var delta = JSON.createObjectNode();
                    delta.putArray("tool_calls").add(tool);
                    sse(exchange, delta.toString(), "tool_calls");
                } else if ("primary".equals(request.path("model").asText())) {
                    byte[] body = "{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"insufficient_quota\",\"message\":\"out of quota\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, body.length);
                    exchange.getResponseBody().write(body);
                } else answer(exchange, "finished");
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig primary = config(endpoint);
            primary.saveProject(project);
            String chosenPrimary = Files.readString(ChatConfig.projectConfigPath(project));
            HeadlessAgentRunner.Options first = options(session, primary, events);
            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(first);
            assertEquals(0, result.exitCode(), events.toString());
            assertEquals("finished", result.text());
            assertEquals(List.of("primary", "primary", "backup"), models(requests));
            assertEquals(1, events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.TOOL_COMPLETED
                    && "read-once".equals(e.callId())).count(), events.toString());
            JsonNode retry = requests.get(2);
            assertEquals(1, countMessage(retry, "tool", "READ_COMPLETED_ONCE"), retry.toString());
            assertEquals(1, countMessage(retry, "user", "read the fixture"), retry.toString());
            assertEquals(chosenPrimary, Files.readString(ChatConfig.projectConfigPath(project)));

            List<HeadlessRunEvent> nextEvents = new CopyOnWriteArrayList<>();
            HeadlessAgentRunner.Result next = new HeadlessAgentRunner().run(options(session, config(endpoint), nextEvents));
            assertEquals(0, next.exitCode(), nextEvents.toString());
            assertEquals(List.of("primary", "primary", "backup", "backup"), models(requests));
            assertEquals("backup", nextEvents.get(0).toolName());
            assertEquals("high", nextEvents.get(0).metadata().get("thinking"));
            assertEquals(chosenPrimary, Files.readString(ChatConfig.projectConfigPath(project)));
        } finally { server.stop(0); }
    }

    @Test
    void perTurnBudgetIsBoundedAndPolicyReloadsOnTheNextTurn() throws Exception {
        String session = session();
        ladder(session, "{\"model\":\"backup\"},{\"model\":\"third\"}", ",\"maxRungsPerTurn\":1");
        Harness harness = harness(options(session, config("http://localhost:1"), new ArrayList<>()));
        Predicate<AgenticChatLoop.ProviderChatFailure> turn = harness.turn();
        assertTrue(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("backup", harness.config.getModel());
        assertFalse(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("backup", harness.config.getModel());
        ladder(session, "{\"model\":\"newly-saved\"}", ",\"maxRungsPerTurn\":1");
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("newly-saved", harness.config.getModel());
        SessionModelLadder disabled = SessionModelLadder.load(session, project);
        disabled.setEnabled(false);
        disabled.save();
        assertFalse(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
    }

    @Test
    void cancelledStoppedInterruptedRefusedUnsafeAndRemoteRequestsNeverSwitch() throws Exception {
        String session = session();
        ladder(session, "{\"model\":\"backup\"}", "");
        Harness harness = harness(options(session, config("http://localhost:1"), new ArrayList<>()));
        Predicate<AgenticChatLoop.ProviderChatFailure> turn = harness.turn();
        assertFalse(turn.test(failure(FailureKind.REFUSAL, true)));
        assertFalse(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, false)));
        harness.cancel.set(true);
        assertFalse(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        harness.cancel.set(false);
        Thread.currentThread().interrupt();
        try { assertFalse(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, true))); }
        finally { Thread.interrupted(); }
        harness.config.setProvider("kompile");
        assertFalse(turn.test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        harness.config.setProvider("opencode");
        harness.config.setAuthenticationMethod("native");
        assertFalse(HeadlessAgentRunner.SessionModelLifecycle.switchable(harness.config));
        verify(harness.loop, never()).changeDirectSettings(any(Runnable.class), eq(true));

        AgentRunController controller = new AgentRunController(AgentRunController.Mode.AUTO);
        controller.stop();
        HeadlessAgentRunner.Options stopped = new HeadlessAgentRunner.Options("hello", session(), false,
                null, null, HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, controller,
                event -> {}, config("http://localhost:1"), null, false, false, null, false);
        ladder(stopped.sessionId(), "{\"model\":\"backup\"}", "");
        Harness stoppedHarness = harness(stopped);
        assertFalse(stoppedHarness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        verify(stoppedHarness.loop, never()).changeDirectSettings(any(Runnable.class), eq(true));
    }

    @Test
    void deniedModelAndUnavailableCredentialAreSkippedBeforeActivation() throws Exception {
        String session = session();
        CredentialStore.create().putApiKey("custom", "usable-account", "USABLE_SECRET", false);
        ladder(session, "{\"model\":\"denied\"},{\"model\":\"missing\",\"provider\":\"custom\","
                + "\"credentialName\":\"not-configured\",\"authenticationMethod\":\"api-key\"},"
                + "{\"model\":\"allowed\",\"credentialName\":\"usable-account\",\"authenticationMethod\":\"api-key\"}", "");
        Harness harness = harness(options(session, config("http://localhost:1"), new ArrayList<>()));
        when(harness.loop.isFailoverModelAllowed("denied")).thenReturn(false);
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("allowed", harness.config.getModel());
        verify(harness.loop, times(1)).changeDirectSettings(any(Runnable.class), eq(true));
        String notices = harness.notices.toString();
        assertFalse(notices.contains("provider-failure-secret"), notices);
        assertFalse(notices.contains("not-configured"), notices);
        assertFalse(notices.contains("USABLE_SECRET"), notices);
        assertTrue(notices.contains("Skipping unavailable fallback custom/missing"), notices);
    }

    @Test
    void namedCredentialAuthAndThinkingSurviveResumeWithoutSecretsOrPrimaryOverwrite() throws Exception {
        String session = session();
        CredentialStore.create().putApiKey("openai", "web-backup-account", "BACKUP_SECRET", false);
        ladder(session, "{\"provider\":\"openai\",\"model\":\"gpt-backup\",\"credentialName\":\"web-backup-account\","
                + "\"authenticationMethod\":\"api-key\",\"thinking\":\"high\"}", "");
        ChatConfig source = new ChatConfig("openai", "PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        source.setAuthenticationMethod("api-key");
        source.setThinking("low");
        Harness harness = harness(options(session, source, new ArrayList<>()));
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("BACKUP_SECRET", source.resolveRequestAuth().token());
        assertEquals("high", source.getThinking());
        String runtime = Files.readString(harness.lifecycle.routePath());
        String policy = Files.readString(SessionModelLadder.sessionPath(session));
        assertFalse(runtime.contains("PRIMARY_SECRET") || runtime.contains("BACKUP_SECRET"), runtime);
        assertFalse(policy.contains("PRIMARY_SECRET") || policy.contains("BACKUP_SECRET"), policy);
        assertFalse(harness.notices.toString().contains("SECRET"), harness.notices.toString());

        ChatConfig next = new ChatConfig("openai", "PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        next.setAuthenticationMethod("api-key");
        next.setThinking("low");
        var lifecycle = new HeadlessAgentRunner.SessionModelLifecycle(options(session, next, new ArrayList<>()), next);
        lifecycle.restore(next, AgentConfig.builder("coder").build());
        assertEquals("gpt-backup", next.getModel());
        assertEquals("web-backup-account", next.getCredentialName());
        assertEquals("api-key", next.getAuthenticationMethod());
        assertEquals("BACKUP_SECRET", next.resolveRequestAuth().token());
        assertEquals("high", next.getThinking());
    }

    @Test
    void explicitUnnamedAccountNeverResurrectsTheInlinePrimaryCredential() throws Exception {
        String session = session();
        CredentialStore.create().putApiKey("openai", "DEFAULT_ACCOUNT_SECRET");
        ladder(session, "{\"provider\":\"openai\",\"model\":\"gpt-backup\","
                + "\"authenticationMethod\":\"api-key\"}", "");
        ChatConfig primary = new ChatConfig("openai", "INLINE_PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        primary.setAuthenticationMethod("api-key");
        Harness harness = harness(options(session, primary, new ArrayList<>()));
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertEquals("DEFAULT_ACCOUNT_SECRET", primary.resolveRequestAuth().token());

        ChatConfig resumed = new ChatConfig("openai", "INLINE_PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        resumed.setAuthenticationMethod("api-key");
        var lifecycle = new HeadlessAgentRunner.SessionModelLifecycle(options(session, resumed, new ArrayList<>()), resumed);
        lifecycle.restore(resumed, AgentConfig.builder("coder").build());
        assertEquals("gpt-backup", resumed.getModel());
        assertEquals("DEFAULT_ACCOUNT_SECRET", resumed.resolveRequestAuth().token());
    }

    @Test
    void crossProviderNativeLoginCannotInheritPrimaryTokenOrEndpoint() throws Exception {
        String session = session();
        ladder(session, "{\"provider\":\"anthropic\",\"model\":\"claude-sonnet-4-6\","
                + "\"authenticationMethod\":\"native\",\"thinking\":\"high\"}", "");
        ChatConfig primary = new ChatConfig("openai", "PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        Harness harness = harness(options(session, primary, new ArrayList<>()));
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        assertTrue(primary.isClaudeCliNative());
        assertNull(primary.resolveClaudeCodeToken());
        assertNull(primary.getBaseUrl());
        ChatConfig resumed = new ChatConfig("openai", "PRIMARY_SECRET", "gpt-primary", "http://localhost:1");
        var lifecycle = new HeadlessAgentRunner.SessionModelLifecycle(options(session, resumed, new ArrayList<>()), resumed);
        lifecycle.restore(resumed, AgentConfig.builder("coder").build());
        assertTrue(resumed.isClaudeCliNative());
        assertNull(resumed.resolveClaudeCodeToken());
        assertNull(resumed.getBaseUrl());
    }

    @Test
    void manualSelectionOrChangedAgentAllowlistWinsOverRuntimeSelection() throws Exception {
        String session = session();
        ladder(session, "{\"model\":\"backup\"}", "");
        Harness harness = harness(options(session, config("http://localhost:1"), new ArrayList<>()));
        assertTrue(harness.turn().test(failure(FailureKind.QUOTA_EXHAUSTED, true)));
        ChatConfig denied = config("http://localhost:1");
        var deniedLifecycle = new HeadlessAgentRunner.SessionModelLifecycle(options(session, denied, new ArrayList<>()), denied);
        deniedLifecycle.restore(denied, AgentConfig.builder("coder").allowedModels(List.of("primary")).build());
        assertEquals("primary", denied.getModel());

        HeadlessAgentRunner.Options opts = options(session, config("http://localhost:1"), new ArrayList<>());
        assertTrue(opts.sessionStateStoreOrDefault().updateModel(session, project, "manual-model").applied());
        ChatConfig manual = config("http://localhost:1");
        var manualLifecycle = new HeadlessAgentRunner.SessionModelLifecycle(opts, manual);
        manualLifecycle.restore(manual, AgentConfig.builder("coder").build());
        assertEquals("primary", manual.getModel(), "new explicit web state invalidates an old runtime route");
    }

    private Harness harness(HeadlessAgentRunner.Options options) {
        return new Harness(options);
    }

    private final class Harness {
        final AgenticChatLoop loop = mock(AgenticChatLoop.class);
        final ChatConfig config;
        final AtomicBoolean cancel = new AtomicBoolean();
        final List<String> notices = new ArrayList<>();
        final HeadlessAgentRunner.SessionModelLifecycle lifecycle;
        final AtomicReference<Supplier<Predicate<AgenticChatLoop.ProviderChatFailure>>> factory = new AtomicReference<>();

        Harness(HeadlessAgentRunner.Options options) {
            config = options.chatConfig();
            lifecycle = new HeadlessAgentRunner.SessionModelLifecycle(options, config);
            when(loop.failoverSourceConfig()).thenAnswer(ignored -> config);
            when(loop.isFailoverModelAllowed(anyString())).thenReturn(true);
            doAnswer(call -> { factory.set(call.getArgument(0)); return null; })
                    .when(loop).setProviderFailoverFactory(any());
            doAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return new AgenticChatLoop.DirectSettingsChange(0, false);
            }).when(loop).changeDirectSettings(any(Runnable.class), eq(true));
            lifecycle.install(loop, config, cancel, new ChatSessionMetrics(options.sessionId()), notices::add);
        }

        Predicate<AgenticChatLoop.ProviderChatFailure> turn() { return factory.get().get(); }
    }

    private HeadlessAgentRunner.Options options(String session, ChatConfig config, List<HeadlessRunEvent> events) {
        return new HeadlessAgentRunner.Options("read the fixture", session, true, null, null,
                HeadlessAgentRunner.OutputMode.JSON, project, 0, null, null, null,
                events::add, config, null, false, false, null, true)
                .withWebInput(new WebChatInput(WebChatInput.VERSION, "read the fixture", "", session))
                .withSessionStateStore(new ChatSessionStateStore(project.resolve("web-state")));
    }

    private void ladder(String session, String rungs, String extra) throws Exception {
        Path file = SessionModelLadder.sessionPath(session);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"enabled\":true,\"rungs\":[" + rungs + "]" + extra + "}");
    }

    private static ChatConfig config(String endpoint) { return new ChatConfig("custom", null, "primary", endpoint); }
    private static String session() { return "web-failover-" + UUID.randomUUID(); }

    private static AgenticChatLoop.ProviderChatFailure failure(FailureKind kind, boolean safe) throws Exception {
        var constructor = AgenticChatLoop.ProviderChatFailure.class.getDeclaredConstructor(
                String.class, FailureKind.class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance("provider-failure-secret", kind, 429, safe);
    }

    private static List<String> models(List<JsonNode> requests) {
        return requests.stream().map(request -> request.path("model").asText()).toList();
    }

    private static long countMessage(JsonNode request, String role, String content) {
        long count = 0;
        for (JsonNode message : request.path("messages")) {
            if (role.equals(message.path("role").asText()) && message.toString().contains(content)) count++;
        }
        return count;
    }

    private static void answer(HttpExchange exchange, String text) throws java.io.IOException {
        sse(exchange, JSON.createObjectNode().put("content", text).toString(), "stop");
    }

    private static void sse(HttpExchange exchange, String delta, String finish) throws java.io.IOException {
        byte[] body = ("data: {\"choices\":[{\"delta\":" + delta + ",\"finish_reason\":\"" + finish
                + "\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
