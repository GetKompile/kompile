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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancelling a chat turn on the Claude Code route stops {@code claude} and the
 * tools it started. The turn is reported as cancelled, not failed, keeps the
 * text streamed so far and its native session, and is never launched again.
 * The fake {@code claude} logs its argv, streams some text, then runs a
 * 30-second tool.
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
    void aCancelledTurnStopsClaudeAndIsNeverLaunchedAgain() throws Exception {
        Path toolPid = home.resolve("tool.pid");
        DirectLlmClient.StreamResult result;
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, toolPid);
            // A failed resumed turn would be retried in a new session; a cancelled one must not be.
            client.resumeClaudeNativeSession("saved-session", HashUtils.sha256Hex("system rules"));
            // The user cancels while Claude is running the tool.
            client.setCancellationCheck(() -> Files.exists(toolPid));

            long started = System.nanoTime();
            result = client.streamChat("run the long task", "system rules", null, null);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 10_000,
                    "cancel must stop claude, not wait for it: the turn took " + elapsedMillis + " ms");
            DirectLlmClient.ClaudeNativeSession session = client.claudeNativeSession();
            assertNotNull(session, "the next turn resumes the session");
            assertEquals("saved-session", session.sessionId());
        }

        assertTrue(result.cancelled, "the turn is reported as cancelled");
        assertFalse(result.failed, "a cancel is not a failure: " + result.failureMessage);
        assertEquals("working", result.text, "text streamed before the cancel is kept");
        List<String> argv = Files.readAllLines(home.resolve("argv.log"), StandardCharsets.UTF_8);
        assertEquals(1, argv.size(), "a cancelled turn is never launched again: " + argv);
        assertTrue(argv.get(0).contains("--resume saved-session"), argv.get(0));
        ClaudeCliClientTest.assertToolStopped(toolPid);
    }

    private DirectLlmClient client() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        DirectLlmClient client = new DirectLlmClient(config, JsonUtils.standardMapper(), home);
        client.setOutputConsumer(ignored -> { });
        return client;
    }

    private void installFakeClaude(DirectLlmClient client, Path toolPid) throws Exception {
        Path fake = home.resolve("fake-claude");
        Files.writeString(fake, """
                #!/usr/bin/env bash
                cat > /dev/null 2>&1 || true
                printf '%s\\n' "$*" >> 'HOME_DIR/argv.log'
                echo '{"type":"system","subtype":"init","session_id":"saved-session"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"working"}}}'
                sleep 30 > /dev/null 2>&1 &
                echo $! > 'TOOL_PID.tmp' && mv 'TOOL_PID.tmp' 'TOOL_PID'
                wait
                printf '%s\\n' '{"type":"result","subtype":"success","num_turns":1,"usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """.replace("HOME_DIR", home.toString()).replace("TOOL_PID", toolPid.toString()),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, new ClaudeCliClient(home, null, fake.toString()));
    }
}
