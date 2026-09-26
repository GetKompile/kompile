/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.utils.HashUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
            String text = client.send("sonnet", null, false, null, "hi", "", streamed::append, null);

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
            String text = client.send(null, null, false, null, "hi", "", streamed::append,
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
            client.send("sonnet", null, false, null, "first", "", null, null);
            client.send("sonnet", null, false, null, "second", "", null, null);
        }

        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "each turn spawns one claude -p process");
        assertTrue(lines.get(0).contains("--session-id test-session"),
                "first turn creates: " + lines.get(0));
        assertTrue(lines.get(1).contains("--resume test-session"),
                "subsequent turns resume: " + lines.get(1));
    }

    @Test
    void instructionsGoToTheSystemPromptFileAndTheTurnFileHoldsOnlyTheTurn() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path promptLog = tempDir.resolve("prompt.log");
        Path instructionsLog = tempDir.resolve("prompt.log.instructions");
        // The fake claude copies the two files named in its argv, the turn file
        // (*.txt) and the system prompt file (*.md), before Kompile deletes them.
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                PROMPT_PATH=$(printf '%s\\n' "$*" | grep -oE '/[^ ]+\\.txt' | head -1)
                if [ -n "$PROMPT_PATH" ] && [ -f "$PROMPT_PATH" ]; then
                  cat "$PROMPT_PATH" > "$PROMPT_LOG"
                fi
                INSTRUCTIONS_PATH=$(printf '%s\\n' "$*" | grep -oE '/[^ ]+\\.md' | head -1)
                if [ -n "$INSTRUCTIONS_PATH" ] && [ -f "$INSTRUCTIONS_PATH" ]; then
                  cat "$INSTRUCTIONS_PATH" > "$PROMPT_LOG.instructions"
                fi
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"done"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """, cmdLog, promptLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send("sonnet", "high", false, "be terse", "hi", "", null, null);
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
        // argv carries the two file paths; the fake copied their content before
        // Kompile cleaned the temp files up.
        String promptPath = command.replaceAll(
                ".*Read this file and act on the prompt in the file: ([^ ]+).*", "$1");
        assertTrue(promptPath.endsWith(".txt"), "argv must reference the prompt file: " + promptPath);
        String instructionsPath = command.replaceAll(".*--append-system-prompt-file ([^ ]+).*", "$1");
        assertTrue(instructionsPath.endsWith(".md"),
                "instructions must go to Claude Code as a system prompt file: " + command);
        // The instructions are the system prompt; the turn file is just the message.
        assertEquals("[Kompile Chat system instructions]\nbe terse\n[End Kompile Chat system instructions]\n",
                Files.readString(instructionsLog, StandardCharsets.UTF_8));
        assertEquals("hi", Files.readString(promptLog, StandardCharsets.UTF_8));
        assertFalse(Files.exists(Path.of(promptPath)), "turn file must be deleted: " + promptPath);
        assertFalse(Files.exists(Path.of(instructionsPath)),
                "instructions file must be deleted: " + instructionsPath);
    }

    @Test
    void changedInstructionsRideInTheNextTurnOnceAfterTheUserMessage() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path promptLog = tempDir.resolve("prompt.log");
        Path fake = fakeClaude(turnFileRecordingBody("s"), cmdLog, promptLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            client.send(null, null, false, "be terse", "first", "", null, null);
            client.send(null, null, false, "be terse", "second", "", null, null);
            client.send(null, null, false, "be thorough", "third", "", null, null);
            client.send(null, null, false, "be thorough", "fourth", "", null, null);
        }

        List<String> turns = recordedTurns(promptLog);
        assertEquals(4, turns.size(), String.valueOf(turns));
        // A new session receives the instructions only as its system prompt.
        assertEquals("first", turns.get(0));
        assertEquals("second", turns.get(1));
        // A started session keeps the system prompt Claude Code recorded, so a
        // change rides in one turn, after the user's message.
        assertTrue(turns.get(2).startsWith("[User message]\nthird\n[End user message]\n"), turns.get(2));
        assertTrue(turns.get(2).contains("[Updated Kompile Chat system instructions: these replace"),
                turns.get(2));
        assertTrue(turns.get(2).contains("be thorough"), turns.get(2));
        assertEquals("fourth", turns.get(3));
        for (String command : Files.readAllLines(cmdLog, StandardCharsets.UTF_8)) {
            assertTrue(command.contains("--append-system-prompt-file "), command);
        }
    }

    @Test
    void aResumedSessionContinuesWithResumeAndKeepsItsInstructions() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path promptLog = tempDir.resolve("prompt.log");
        Path fake = fakeClaude(turnFileRecordingBody("saved-session"), cmdLog, promptLog);
        String digest = HashUtils.sha256Hex("be terse");

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "unused", fake.toString())) {
            assertNull(client.nativeSession(), "no native session before a turn");
            client.resumeSession("saved-session", digest);
            client.send(null, null, false, "be terse", "next", "", null, null);
            assertEquals(new DirectLlmClient.ClaudeNativeSession("saved-session", digest),
                    client.nativeSession());
        }

        assertTrue(commandLine(cmdLog).contains("--resume saved-session"), commandLine(cmdLog));
        assertEquals(List.of("next"), recordedTurns(promptLog),
                "the resumed session already holds the instructions and the conversation");
    }

    @Test
    void anUnknownResumeSessionIsARefusedTurnThatNeverStarted() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        // What Claude Code 2.1.282 does for `--resume <unknown id>` in stream-json mode.
        Path fake = fakeClaude("""
                printf '%s\\n' "$*" >> "$CMD_LOG"
                echo "No conversation found with session ID: gone-session" >&2
                printf '%s\\n' '{"type":"result","subtype":"error_during_execution","duration_ms":0,"duration_api_ms":0,"is_error":true,"num_turns":0,"session_id":"gone-session","total_cost_usd":0,"errors":["No conversation found with session ID: gone-session"]}'
                exit 1
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "unused", fake.toString())) {
            client.resumeSession("gone-session", null);
            ClaudeCliClient.TurnNotStartedException failure =
                    assertThrows(ClaudeCliClient.TurnNotStartedException.class,
                            () -> client.send(null, null, false, null, "hi", "", null, null));
            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("No conversation found with session ID: gone-session"),
                    failure.getMessage());
        }
        assertTrue(commandLine(cmdLog).contains("--resume gone-session"), commandLine(cmdLog));
    }

    @Test
    void aRefusedFirstTurnDoesNotStartTheSession() throws Exception {
        Path cmdLog = tempDir.resolve("cmd.log");
        Path fake = fakeClaude("""
                printf '%s\\n' '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"errors":["Session ID s is already in use"]}'
                exit 1
                """, cmdLog);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            assertThrows(ClaudeCliClient.TurnNotStartedException.class,
                    () -> client.send(null, null, false, null, "hi", "", null, null));
            assertNull(client.nativeSession(), "a refused turn must not be resumed later");
        }
    }

    @Test
    void theTurnFileLeadsWithTheUserMessageAndLabelsEverythingElse() {
        String message = "<kompile_reminders>\n1. Plan before making changes.\n</kompile_reminders>\n\n"
                + "<memory_context>\nThe notes live in docs/.\n</memory_context>\n\nwhat is in NOTES.md?";
        String turn = ClaudeCliClient.composeTurn(message, "be thorough",
                "[Earlier conversation restored by Kompile: past turns]\n[user]\nhello\n[End earlier conversation]");

        assertTrue(turn.startsWith("[User message]\nwhat is in NOTES.md?\n[End user message]\n"), turn);
        int context = turn.indexOf("[Kompile context for this turn: it applies to the user message above");
        int reminders = turn.indexOf("Plan before making changes.");
        int memory = turn.indexOf("The notes live in docs/.");
        int update = turn.indexOf("[Updated Kompile Chat system instructions");
        int restored = turn.indexOf("[Earlier conversation restored by Kompile");
        assertTrue(context > 0 && context < reminders && reminders < memory && memory < update
                && update < restored, turn);
        assertEquals(1, turn.split("what is in NOTES.md\\?", -1).length - 1,
                "the user's message appears once\n" + turn);

        // A bare message is sent as-is.
        assertEquals("what is in NOTES.md?", ClaudeCliClient.composeTurn("what is in NOTES.md?", "", ""));
        assertEquals("", ClaudeCliClient.composeTurn(null, null, null));
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
            client.send(null, null, false, null, "hi", "", null, new ClaudeCliClient.ActivityListener() {
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
                            () -> client.send("sonnet", null, false, null, "hi", "", null, null));
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
                            () -> client.send("sonnet", null, false, null, "hi", "", null, null));
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
                            () -> client.send(null, null, false, null, "hi", "", null, null));
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
            client.send("claude-opus-5-5", "ultracode", true, null, "first", "", null, null);
            client.send("claude-opus-5-5", "high", false, null, "second", "", null, null);
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

    @Test
    void claudeProcessesNeverInheritTheApiKeyRoutesKey() {
        // claude prefers ANTHROPIC_API_KEY over its own login, which would move
        // the Claude Code route onto the API-key route's billing and identity.
        ProcessBuilder builder = new ProcessBuilder("claude");
        builder.environment().put("ANTHROPIC_API_KEY", "api-route-key");
        builder.environment().put("KOMPILE_TEST_MARKER", "kept");
        ClaudeCliClient.withoutApiKeyEnvironment(builder);
        assertFalse(builder.environment().containsKey("ANTHROPIC_API_KEY"));
        assertEquals("kept", builder.environment().get("KOMPILE_TEST_MARKER"));
    }

    @Test
    void cancellingATurnStopsTheCliAndTheToolsItStarted() throws Exception {
        Path toolPid = tempDir.resolve("tool.pid");
        Path fake = fakeClaude(toolRunningTurnBody(toolPid), tempDir.resolve("cmd.log"));
        StringBuilder streamed = new StringBuilder();

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            // The user cancels while Claude is running a tool.
            client.setCancellationCheck(() -> Files.exists(toolPid));
            long started = System.nanoTime();
            assertThrows(CancellationException.class,
                    () -> client.send(null, null, false, null, "hi", "", streamed::append, null));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 10_000,
                    "the CLI must be stopped, not awaited: the turn took " + elapsedMillis + " ms");
        }
        assertEquals("working", streamed.toString(), "text streamed before the cancel is kept");
        assertToolStopped(toolPid);
    }

    @Test
    void interruptingTheTurnThreadStopsTheCli() throws Exception {
        Path toolPid = tempDir.resolve("tool.pid");
        Path fake = fakeClaude(toolRunningTurnBody(toolPid), tempDir.resolve("cmd.log"));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", fake.toString())) {
            Thread turn = new Thread(() -> {
                try {
                    client.send(null, null, false, null, "hi", "", null, null);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            turn.start();
            awaitFile(toolPid);
            turn.interrupt();
            turn.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(turn.isAlive(), "an interrupted turn must not wait for the CLI to finish");
        }
        assertInstanceOf(InterruptedException.class, failure.get());
        assertToolStopped(toolPid);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Writes an executable fake `claude` that logs argv to {@code cmdLog}. */
    private Path fakeClaude(String body, Path cmdLog) throws IOException {
        return fakeClaude(body, cmdLog, tempDir.resolve("unused-prompt.log"));
    }

    /** Overload whose fake can also copy the turn file's content to {@code promptLog}. */
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

    /**
     * A fake turn that appends its turn file to {@code $PROMPT_LOG}, one record per
     * turn, and answers "ok" in session {@code sessionId}.
     */
    private static String turnFileRecordingBody(String sessionId) {
        return """
                printf '%s\\n' "$*" >> "$CMD_LOG"
                PROMPT_PATH=$(printf '%s\\n' "$*" | grep -oE '/[^ ]+\\.txt' | head -1)
                cat "$PROMPT_PATH" >> "$PROMPT_LOG"
                printf '\\n=== end of turn ===\\n' >> "$PROMPT_LOG"
                echo '{"type":"system","subtype":"init","session_id":"SESSION"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}}'
                printf '%s\\n' '{"type":"result","subtype":"success","num_turns":1,"usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """.replace("SESSION", sessionId);
    }

    private static List<String> recordedTurns(Path promptLog) throws IOException {
        return List.of(Files.readString(promptLog, StandardCharsets.UTF_8)
                .split("\n=== end of turn ===\n"));
    }

    private static String commandLine(Path cmdLog) throws IOException {
        List<String> lines = Files.readAllLines(cmdLog, StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty(), "the fake claude must record its argv");
        return String.join(" ", lines);
    }

    /**
     * A turn that streams "working", then runs a 30-second tool (a background
     * sleep whose pid it writes to {@code toolPid}) and waits for it, like
     * Claude running a slow Bash command.
     */
    private static String toolRunningTurnBody(Path toolPid) {
        return """
                cat > /dev/null 2>&1 || true
                echo '{"type":"system","subtype":"init","session_id":"s"}'
                printf '%s\\n' '{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"working"}}}'
                sleep 30 > /dev/null 2>&1 &
                echo $! > 'TOOL_PID.tmp' && mv 'TOOL_PID.tmp' 'TOOL_PID'
                wait
                printf '%s\\n' '{"type":"result","subtype":"success","usage":{"input_tokens":1,"output_tokens":1}}'
                exit 0
                """.replace("TOOL_PID", toolPid.toString());
    }

    private static void awaitFile(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, "the fake claude never started its tool");
            Thread.sleep(20);
        }
    }

    /** The tool the fake CLI started must not outlive the cancelled turn. */
    static void assertToolStopped(Path toolPid) throws Exception {
        long pid = Long.parseLong(Files.readString(toolPid, StandardCharsets.UTF_8).strip());
        Optional<ProcessHandle> tool = ProcessHandle.of(pid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tool.map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) {
                tool.get().destroyForcibly();
                fail("the tool the CLI started (pid " + pid + ") outlived the cancelled turn");
            }
            Thread.sleep(20);
        }
    }
}
