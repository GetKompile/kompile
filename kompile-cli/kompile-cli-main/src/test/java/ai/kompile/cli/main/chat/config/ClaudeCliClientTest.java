/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link ClaudeCliClient} transport against a fake {@code claude}
 * binary that reproduces the real CLI's stream-json contract (init, streamed
 * text deltas, terminal result event) and its auth-failure mode. The fake logs
 * its argv to a file so the test can assert the exact command shape.
 */
class ClaudeCliClientTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsTextDeltasAndExtractsFinalText() throws Exception {
        StringBuilder streamed = new StringBuilder();
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"native-session-1"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello "}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","duration_ms":10,"num_turns":1,"usage":{"input_tokens":5,"output_tokens":2,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "test-session", fake.toString())) {
            String text = client.send("sonnet", null, null, "hi", streamed::append, null);

            assertEquals("Hello world", text, "final text must equal the streamed deltas");
            assertEquals("Hello world", streamed.toString());

            String command = commandLine(cmdLog);
            assertTrue(command.contains("-p"), "turn must use headless -p mode: " + command);
            assertTrue(command.contains("--output-format"), command);
            assertTrue(command.contains("stream-json"), command);
            assertTrue(command.contains("--include-partial-messages"), command);
            assertTrue(command.contains("--session-id test-session"),
                    "first turn creates the native session: " + command);
        }
    }

    @Test
    void secondTurnResumesTheSameNativeSession() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"test-session"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "test-session", fake.toString())) {
            client.send("sonnet", null, null, "first", null, null);
            client.send("sonnet", null, null, "second", null, null);
        }

        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "each turn spawns one claude -p process");
        assertTrue(lines.get(0).contains("--session-id test-session"),
                "first turn creates: " + lines.get(0));
        assertTrue(lines.get(1).contains("--resume test-session"),
                "subsequent turns resume: " + lines.get(1));
    }

    @Test
    void systemPromptModelAndEffortReachTheCommandLine() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"done"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send("sonnet", "high", "be terse", "hi", null, null);
        }

        String command = commandLine(cmdLog);
        assertTrue(command.contains("--effort high"), command);
        assertTrue(command.contains("--model sonnet"), command);
        assertTrue(command.contains("Kompile Chat system instructions"), command);
        assertTrue(command.contains("be terse"), command);
    }

    @Test
    void tokenUsageFromTheResultEventReachesTheListener() throws Exception {
        List<String> usage = new ArrayList<>();
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"done"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":12,"output_tokens":3,"cache_read_input_tokens":4,"cache_creation_input_tokens":2}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send(null, null, null, "hi", null, new ClaudeCliClient.ActivityListener() {
                @Override
                public void onToolStart(String callId, String name, String input) { }

                @Override
                public void onToolComplete(String callId, String name, String output,
                                           int exitCode, boolean error) { }

                @Override
                public void onTokenUsage(long input, long output, long cacheRead, long cacheCreation) {
                    usage.add(input + "/" + output + "/" + cacheRead + "/" + cacheCreation);
                }
            });
        }

        // The parser normalizes cache tokens out of inclusive input: 12 - 4 - 2 = 6.
        assertEquals(List.of("6/3/4/2"), usage);
    }

    @Test
    void authFailureIsClassifiedAsReplaySafeWithAnActionableWarning() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                cat > /dev/null 2>&1 || true
                echo "Please run /login to continue." >&2
                exit 1
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            ClaudeCliClient.TurnNotStartedException failure =
                    assertThrows(ClaudeCliClient.TurnNotStartedException.class,
                            () -> client.send("sonnet", null, null, "hi", null, null));
            assertTrue(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "a login failure must classify as authentication: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("/login"),
                    "warning must name the fix: " + failure.getMessage());
        }
    }

    @Test
    void missingBinaryIsReplaySafeTurnNotStartedButNotAuth() {
        try (ClaudeCliClient client =
                     new ClaudeCliClient(tempDir, "s", tempDir.resolve("no-such-claude").toString())) {
            ClaudeCliClient.TurnNotStartedException failure =
                    assertThrows(ClaudeCliClient.TurnNotStartedException.class,
                            () -> client.send("sonnet", null, null, "hi", null, null));
            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "a missing binary is not an auth failure");
            assertTrue(failure.getMessage().contains("claude"), failure.getMessage());
        }
    }

    @Test
    void exitFailureWithoutAuthSignatureStaysTurnNotStarted() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                cat > /dev/null 2>&1 || true
                echo "model overloaded, try again" >&2
                exit 3
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            ClaudeCliClient.TurnNotStartedException failure =
                    assertThrows(ClaudeCliClient.TurnNotStartedException.class,
                            () -> client.send(null, null, null, "hi", null, null));
            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "overload must not be classified as auth: " + failure.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Writes an executable fake `claude` that logs argv to {@code cmdLog}. */
    private Path fakeClaude(String body, Path cmdLog) throws IOException {
        Path bin = Files.createDirectories(tempDir.resolve("fake-bin"));
        Path script = bin.resolve("claude-fake");
        Files.writeString(script,
                "#!/usr/bin/env bash\nCMD_LOG=" + cmdLog + "\n" + body,
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static String commandLine(Path cmdLog) throws IOException {
        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "the fake claude must record its argv");
        return String.join(" ", lines);
    }
}
