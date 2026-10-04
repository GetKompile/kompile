/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code /model} change that stays on the chat's route keeps a provider-owned
 * session: on the Claude Code route the process that ran the last turn runs the
 * next one, with the new model applied in place and no restored history, so the
 * tasks it started keep running. An HTTP route still rebuilds its wire history.
 * Drives the real ledger, DirectLlmClient and AgenticChatLoop against a fake
 * {@code claude} that logs its argv and every line it takes in.
 */
@TemporaryUserHome
class AgenticChatLoopSameRouteModelChangeTest {
    private static final String SESSION = "same-route-model-change-test";

    @TempDir Path directory;

    @AfterEach
    void resetChatOutput() {
        ChatCompleter.setContentOutput(null);
        ChatCompleter.setTranscriptBlockOutput(null);
        ChatCompleter.setActivity(null);
    }

    @Test
    void aClaudeCodeModelChangeKeepsTheProcessAndAppliesTheModelToIt() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig config = claudeCodeConfig("claude-opus-5-5");
        Path project = Files.createDirectories(directory.resolve("project"));
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, null);
            loop.configureConversationSession(SESSION);
            // Restoring a durable conversation resets the transport, so install after it.
            FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), "ANSWER=done");
            fake.install(client, project, null);
            Object claudeCode = claudeCodeClient(client);
            assertNotNull(claudeCode);

            assertEquals("done", loop.chat("first question", SESSION, "coder", "default", false).strip(),
                    String.valueOf(lines));

            AgenticChatLoop.DirectSettingsChange change = loop.changeDirectSettings(
                    () -> config.applyLlmSettingsFrom(claudeCodeConfig("claude-sonnet-4-6")));
            assertTrue(change.keptProviderSession(), "a same-route model change keeps the Claude Code session");
            // Checked before the next turn: a replaced client would start a new claude.
            assertSame(claudeCode, claudeCodeClient(client),
                    "the model change must not replace the Claude Code process");

            assertEquals("done", loop.chat("second question", SESSION, "coder", "default", false).strip(),
                    String.valueOf(lines));

            List<String> argv = fake.argv();
            assertEquals(1, argv.size(), "one Claude Code process runs both turns\n" + argv);
            assertTrue(argv.get(0).contains("--model claude-opus-5-5"), argv.get(0));
            List<JsonNode> modelChanges = fake.controls("set_model");
            assertEquals(1, modelChanges.size(), fake.controls().toString());
            assertEquals("claude-sonnet-4-6", modelChanges.get(0).path("model").asText());

            List<String> messages = fake.messages();
            assertEquals(2, messages.size(), String.valueOf(messages));
            String second = messages.get(1);
            assertTrue(second.contains("second question"), second);
            assertFalse(second.contains("[Earlier conversation restored by"),
                    "the kept session already holds the conversation\n" + second);
            assertFalse(second.contains("first question"), second);
        }
    }

    @Test
    void anHttpModelChangeStillRebuildsTheWireHistory() {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("openai", "test-key", "gpt-4o", null);
        try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            loop.restoreHistory(List.of(
                    new ChatHistory.Turn("user", "Remember the deployment target."),
                    new ChatHistory.Turn("assistant", "The target is staging.")));
            client.addToHistory("tool", "provider-specific wire envelope");

            AgenticChatLoop.DirectSettingsChange change = loop.changeDirectSettings(
                    () -> config.applyLlmSettingsFrom(new ChatConfig("openai", "test-key", "gpt-4.1", null)));

            assertFalse(change.keptProviderSession());
            assertEquals(2, change.retainedMessages());
            assertEquals(2, client.getHistorySize(),
                    "the new model receives the conversation without stale wire envelopes");
            assertEquals("gpt-4.1", client.getConfiguredModel());
        }
    }

    @Test
    void switchingAwayKeepsClaudeTasksControllableAndSwitchingBackReusesTheProcess() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig config = claudeCodeConfig("claude-opus-5-5");
        Path project = Files.createDirectories(directory.resolve("project"));
        ChatCompleter.setContentOutput(ignored -> {});
        HttpServer otherProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        otherProvider.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    data: {"choices":[{"delta":{"content":"Other provider result"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
            exchange.close();
        });
        otherProvider.start();
        FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), """
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    emit '{"type":"system","subtype":"task_started","task_id":"task-1","description":"Run the build","task_type":"local_bash"}'
                    emit '{"type":"system","subtype":"task_started","task_id":"task-2","description":"Watch the build","task_type":"local_agent"}'
                  fi
                  say_text done
                  say_result
                }
                on_stop_task() {
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  emit '{"type":"system","subtype":"task_notification","task_id":"task-1","status":"stopped","summary":"stopped"}'
                  say_init
                  say_text 'Late Claude response'
                  say_result
                }
                """);
        try (BackgroundProcessManager processes = new BackgroundProcessManager(SESSION, project);
             DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, processes);
            loop.configureConversationSession(SESSION);
            fake.installBinary(client);
            client.setClaudeTaskProcesses(processes);
            LinkedBlockingQueue<String> followUps = new LinkedBlockingQueue<>();
            client.setClaudeFollowUpListener(followUps::add);
            assertEquals("done", loop.chat("start work", SESSION, "coder", "default", false).strip());
            Object claude = claudeCodeClient(client);
            DirectLlmClient.ClaudeNativeSession nativeSession = client.claudeNativeSession();
            BackgroundProcessManager.ProcessEntry task = processes.listAll().stream()
                    .filter(entry -> "task-1".equals(entry.getMetadata().get("task_id")))
                    .findFirst().orElseThrow();
            BackgroundProcessManager.ProcessEntry remaining = processes.listAll().stream()
                    .filter(entry -> "task-2".equals(entry.getMetadata().get("task_id")))
                    .findFirst().orElseThrow();

            ChatConfig other = new ChatConfig("openai", "test-key", "gpt-4o",
                    "http://127.0.0.1:" + otherProvider.getAddress().getPort());
            assertFalse(loop.changeDirectSettings(() -> config.applyLlmSettingsFrom(other))
                    .keptProviderSession());
            assertSame(claude, claudeCodeClient(client));
            assertEquals(nativeSession.sessionId(), client.claudeNativeSession().sessionId());
            assertTrue(task.isRunning());
            assertTrue(task.isKillable());
            client.syncClaudeIdleSettings("gpt-4o", "high", false);
            assertEquals("Other provider result",
                    loop.chat("Other provider work", SESSION, "coder", "default", false).strip());
            assertTrue(processes.kill(task.getId()));
            assertEquals("task-1", fake.awaitControl("stop_task").path("task_id").asText());
            assertTrue(fake.controls("set_model").isEmpty(),
                    "switching providers must not push their settings to Claude");

            String followUp = followUps.poll(5, TimeUnit.SECONDS);
            assertNotNull(followUp, "Claude follow-ups remain connected after switching providers");
            DirectLlmClient.StreamResult late = client.streamChat(followUp, "", null, null);
            assertEquals("Late Claude response", late.text);
            assertNull(late.claudeNativeSession,
                    "the parked session must not claim it holds the other provider's work");
            assertEquals(1, fake.messages().size(), "the follow-up is adopted, not sent again");
            // A later non-Claude replay/compaction must not kill retained work either.
            loop.rebuildDirectHistoryForProviderSwitch();
            assertSame(claude, claudeCodeClient(client));
            assertTrue(remaining.isRunning());
            loop.changeDirectSettings(() -> config.applyLlmSettingsFrom(claudeCodeConfig("claude-opus-5-5")));
            assertSame(claude, claudeCodeClient(client));
            assertEquals("done", loop.chat("continue work", SESSION, "coder", "default", false).strip());
            assertEquals(1, fake.argv().size(), "switching back must reuse the live process");
            assertTrue(fake.messages().get(1).contains("Other provider result"),
                    "the retained session must receive intervening provider work");

            // Stop the retained session from the panel while another provider is selected.
            loop.changeDirectSettings(() -> config.applyLlmSettingsFrom(other));
            BackgroundProcessManager.ProcessEntry session = processes.listAll().stream()
                    .filter(entry -> entry.getMetadata().containsKey("pid"))
                    .filter(entry -> "claude-code".equals(entry.getMetadata().get("source")))
                    .findFirst().orElseThrow();
            long pid = Long.parseLong(session.getMetadata().get("pid"));
            assertTrue(session.isRunning());
            assertTrue(session.isKillable());
            assertTrue(processes.kill(session.getId()));
            FakeClaudeCode.await("the retained Claude process to exit",
                    () -> ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
            FakeClaudeCode.await("Claude task rows to end", () -> !remaining.isRunning());
            assertEquals(BackgroundProcessManager.ProcessState.KILLED, session.getState());
            client.clearHistory();
            assertFalse(remaining.isRunning());
            assertNull(client.claudeNativeSession());
        } finally {
            otherProvider.stop(0);
        }
    }

    @Test
    void closingTheChatOffRouteStopsTheRetainedClaudeProcess() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatConfig config = claudeCodeConfig("claude-opus-5-5");
        Path project = Files.createDirectories(directory.resolve("project"));
        FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), "ANSWER=done");
        ChatCompleter.setContentOutput(ignored -> {});
        try (BackgroundProcessManager processes = new BackgroundProcessManager(SESSION, project);
             DirectLlmClient client = new DirectLlmClient(config, mapper, project)) {
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), project, client, processes);
            loop.configureConversationSession(SESSION);
            fake.installBinary(client);
            client.setClaudeTaskProcesses(processes);
            assertEquals("done", loop.chat("first question", SESSION, "coder", "default", false).strip());
            BackgroundProcessManager.ProcessEntry session = processes.listAll().stream()
                    .filter(entry -> entry.getMetadata().containsKey("pid"))
                    .findFirst().orElseThrow();
            long pid = Long.parseLong(session.getMetadata().get("pid"));
            loop.changeDirectSettings(() -> config.applyLlmSettingsFrom(
                    new ChatConfig("openai", "test-key", "gpt-4o", null)));
            assertTrue(session.isRunning());
            client.close();
            assertFalse(session.isRunning());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertNull(client.claudeNativeSession());
        }
    }

    private static ChatConfig claudeCodeConfig(String model) {
        ChatConfig config = new ChatConfig("anthropic", null, model, null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        return config;
    }

    /** The Claude Code client the DirectLlmClient runs its turns on. */
    private static Object claudeCodeClient(DirectLlmClient client) throws Exception {
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        return field.get(client);
    }
}
