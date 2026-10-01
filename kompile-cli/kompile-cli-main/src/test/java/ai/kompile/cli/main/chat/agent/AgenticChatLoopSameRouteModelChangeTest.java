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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
