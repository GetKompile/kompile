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
            String text = client.send("sonnet", null, false, null, "hi", streamed::append, null);

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
    void streamsToolLifecycleThinkingNoticesAndDeduplicatesAggregateText() throws Exception {
        StringBuilder streamed = new StringBuilder();
        List<String> toolStarts = new ArrayList<>();
        List<String> toolInputs = new ArrayList<>();
        List<String> toolProgress = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        List<String> thinking = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"native-session"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"message_start","message":{"id":"message-1"}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Thinking"}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Before tool. "}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"/tmp/a\\"}"}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_stop","index":2}}'
                printf '%s\\n' '{"type":"assistant","message":{"id":"message-1","content":[{"type":"thinking","thinking":"Thinking"},{"type":"text","text":"Before tool. "},{"type":"tool_use","id":"tool-1","name":"Read","input":{"path":"/tmp/a"}}]}}'
                printf '%s\\n' '{"type":"tool_progress","tool_use_id":"tool-1","content":"progress output"}'
                printf '%s\\n' '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":[{"type":"text","text":"file contents"}]}]}}'
                printf '%s\\n' '{"type":"system","subtype":"status","message":"Tool finished"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"message_start","message":{"id":"message-2"}}}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"After tool."}}}'
                printf '%s\\n' '{"type":"assistant","message":{"id":"message-2","content":[{"type":"text","text":"After tool."}]}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":3,"output_tokens":2}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "test-session", fake.toString())) {
            String text = client.send(null, null, false, null, "hi", streamed::append,
                    new ClaudeCliClient.ActivityListener() {
                        @Override
                        public void onToolStart(String callId, String name, String input) {
                            toolStarts.add(callId + ":" + name);
                        }

                        @Override
                        public void onToolInput(String callId, String name, String input) {
                            toolInputs.add(callId + ":" + input);
                        }

                        @Override
                        public void onToolOutput(String callId, String name, String output) {
                            toolProgress.add(callId + ":" + output);
                        }

                        @Override
                        public void onToolComplete(String callId, String name, String output,
                                                   int exitCode, boolean error) {
                            toolResults.add(callId + ":" + output + ":" + error);
                        }

                        @Override
                        public void onThinking(String text) {
                            thinking.add(text);
                        }

                        @Override
                        public void onNotice(String text) {
                            notices.add(text);
                        }

                        @Override
                        public void onTokenUsage(long input, long output,
                                                 long cacheRead, long cacheCreation) { }
                    });

            // The tool call closes the prose line before it.
            assertEquals("Before tool. \nAfter tool.", text);
            assertEquals("Before tool. \nAfter tool.", streamed.toString());
            assertEquals(List.of("tool-1:Read"), toolStarts);
            assertEquals(List.of("tool-1:{\"path\":\"/tmp/a\"}"), toolInputs);
            assertEquals(List.of("tool-1:progress output"), toolProgress);
            assertEquals(List.of("tool-1:file contents:false"), toolResults);
            assertEquals(List.of("Thinking"), thinking);
            assertEquals(List.of("Tool finished"), notices);
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
            client.send("sonnet", null, false, null, "first", null, null);
            client.send("sonnet", null, false, null, "second", null, null);
        }

        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "each turn spawns one claude -p process");
        assertTrue(lines.get(0).contains("--session-id test-session"),
                "first turn creates: " + lines.get(0));
        assertTrue(lines.get(1).contains("--resume test-session"),
                "subsequent turns resume: " + lines.get(1));
    }

    @Test
    void promptIsWrittenToAFileAndArgvOnlyCarriesTheReadInstruction() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path promptLog = tempDir.resolve("prompt.log");
        // The fake claude scans its argv for the prompt-file path (the argument
        // after "Read this file and act on the prompt in the file: ") and
        // prints the file's content — what a real turn would read.
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                PROMPT_PATH=$(printf '%s\\n' "$*" | grep -oE '/[^ ]+\\.txt' | head -1)
                if [ -n "$PROMPT_PATH" ] && [ -f "$PROMPT_PATH" ]; then
                  cat "$PROMPT_PATH" > "$PROMPT_LOG"
                fi
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"done"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """, cmdLog, promptLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send("sonnet", "high", false, "be terse", "hi", null, null);
        }

        String command = commandLine(cmdLog);
        assertTrue(command.contains("--dangerously-skip-permissions"),
                "headless chat tool calls must not stall on permission prompts: " + command);
        assertTrue(command.contains("-p"), command);
        assertTrue(command.contains("Read this file and act on the prompt in the file: "),
                "argv must carry the short read-the-file instruction: " + command);
        assertTrue(command.contains("--effort high"), command);
        assertTrue(command.contains("--model sonnet"), command);
        // Prompt CONTENT must never appear in argv (that's what blows up argv).
        assertFalse(command.contains("be terse"),
                "prompt content leaked into argv: " + command);
        assertFalse(command.contains("Kompile Chat system instructions"),
                "system instructions leaked into argv: " + command);
        // The argv carries the file path; the fake copied the file's content
        // before Kompile cleaned the temp file up. That copy is the full prompt.
        String promptPath = command.replaceAll(
                ".*Read this file and act on the prompt in the file: ([^ ]+).*", "$1");
        assertTrue(promptPath.endsWith(".txt"), "argv must reference the prompt file: " + promptPath);
        String promptContent = Files.readString(promptLog, StandardCharsets.UTF_8);
        assertTrue(promptContent.contains("Kompile Chat system instructions"), promptContent);
        assertTrue(promptContent.contains("be terse"), promptContent);
        assertTrue(promptContent.contains("hi"), promptContent);
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
            client.send(null, null, false, null, "hi", null, new ClaudeCliClient.ActivityListener() {
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
                            () -> client.send("sonnet", null, false, null, "hi", null, null));
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
                            () -> client.send("sonnet", null, false, null, "hi", null, null));
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
                            () -> client.send(null, null, false, null, "hi", null, null));
            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "overload must not be classified as auth: " + failure.getMessage());
        }
    }

    @Test
    void ultracodeEffortAndFastModeSettingsArePerTurnArgv() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send("claude-opus-5-5", "ultracode", true, null, "first", null, null);
            client.send("claude-opus-5-5", "high", false, null, "second", null, null);
        }

        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("--effort ultracode"), lines.get(0));
        assertTrue(lines.get(0).contains("--settings {\"fastMode\":true}"),
                "headless fast mode is only honored through --settings: " + lines.get(0));
        // Each turn is its own process, so turning either option off takes effect
        // on the next turn without touching the user's Claude Code settings.
        assertTrue(lines.get(1).contains("--effort high"), lines.get(1));
        assertFalse(lines.get(1).contains("--settings"), lines.get(1));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Writes an executable fake `claude` that logs argv to {@code cmdLog}. */
    private Path fakeClaude(String body, Path cmdLog) throws IOException {
        return fakeClaude(body, cmdLog, tempDir.resolve("unused-prompt.log"));
    }

    /** Overload whose fake also copies the --prompt-file content to {@code promptLog}. */
    private Path fakeClaude(String body, Path cmdLog, Path promptLog) throws IOException {
        Path bin = Files.createDirectories(tempDir.resolve("fake-bin"));
        Path script = bin.resolve("claude-fake");
        Files.writeString(script,
                "#!/usr/bin/env bash\nCMD_LOG=" + cmdLog + "\nPROMPT_LOG=" + promptLog + "\n" + body,
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
