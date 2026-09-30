/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancelling a chat turn on the Claude Code route interrupts the turn, which
 * stops the tool it was running. The turn is reported as cancelled, not failed,
 * keeps the text streamed so far and its native session, and is never sent
 * again; the session's process keeps running for the next turn. The fake
 * {@code claude} streams some text, then runs a 30-second tool.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientClaudeCancelTest {

    @TempDir Path home;
    private String previousHome;

    @BeforeEach
    void isolateHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void aCancelledTurnInterruptsClaudeAndIsNeverSentAgain() throws Exception {
        FakeClaudeCode fake = new FakeClaudeCode(home.resolve("claude"), """
                turn() {
                  say_init
                  say_text working
                  start_tool
                }
                """);
        StringBuilder streamed = new StringBuilder();
        DirectLlmClient.StreamResult result;
        try (DirectLlmClient client = client(streamed)) {
            ClaudeCliClient claude = installFakeClaude(client, fake);
            // A failed resumed turn would be retried in a new session; a cancelled one must not be.
            client.resumeClaudeNativeSession("saved-session", HashUtils.sha256Hex("system rules"));
            // The user cancels while Claude is running the tool.
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));

            long started = System.nanoTime();
            result = client.streamChat("run the long task", "system rules", null, null);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 10_000,
                    "cancel must interrupt the turn, not wait for it: the turn took " + elapsedMillis + " ms");
            DirectLlmClient.ClaudeNativeSession session = client.claudeNativeSession();
            assertNotNull(session, "the next turn resumes the session");
            assertEquals("saved-session", session.sessionId());
            assertTrue(claude.processAlive(), "the session's process keeps running for the next turn");
        }

        assertTrue(result.cancelled, "the turn is reported as cancelled");
        assertFalse(result.failed, "a cancel is not a failure: " + result.failureMessage);
        assertEquals("working", result.text, "text streamed before the cancel is kept");
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), "a cancelled turn is never launched again: " + argv);
        assertTrue(argv.get(0).contains("--resume saved-session"), argv.get(0));
        assertEquals(1, fake.messages().size(), "a cancelled turn is never sent again: " + fake.messages());
        assertTrue(fake.awaitControl("interrupt").path("cancel_queued").asBoolean());
        fake.assertToolStopped();
    }

    private DirectLlmClient client(StringBuilder streamed) {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        DirectLlmClient client = new DirectLlmClient(config, JsonUtils.standardMapper(), home);
        client.setOutputConsumer(streamed::append);
        return client;
    }

    private ClaudeCliClient installFakeClaude(DirectLlmClient client, FakeClaudeCode fake) throws Exception {
        ClaudeCliClient claude = new ClaudeCliClient(home, null, fake.binary());
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, claude);
        return claude;
    }
}
