/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link ClaudeCliClient} transport against {@link FakeClaudeCode},
 * a fake {@code claude} binary that reproduces the real CLI's stream-json
 * contract for one persistent process: control requests and their answers, the
 * echo of each message it takes in, streamed turns, and its failure modes. The
 * fake logs each process's argv and everything written to its input, so the
 * tests assert the exact command and messages.
 *
 * <p>A turn registers the kompile tools, which touches files under
 * {@code user.home}, so the class runs under a temporary one.</p>
 */
@TemporaryUserHome
class ClaudeCliClientTest {

    /** The first message's turn streams text, starts a tool and waits; later turns answer. */
    private static final String LONG_FIRST_TURN = """
            turn() {
              if [ "$1" = 1 ]; then
                say_init
                say_text working
                start_tool
              else
                say_init
                say_text "$ANSWER"
                say_result
              fi
            }
            """;

    /** Claude Code's model rows in its answer to initialize: Sonnet takes efforts, Haiku none. */
    private static final String MODEL_CATALOG = """
            control() {
              if [ "$2" = initialize ]; then
                emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"models":[{"value":"sonnet","supportedEffortLevels":["low","medium","high"]},{"value":"haiku"}]}}}'
              else
                emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
              fi
            }
            """;

    /** Each process writes its arguments to {@code args}, one bracketed argument after another. */
    private static final String LOGS_ARGUMENTS = """
            startup() {
              printf '[%s]' "${args[@]}" > "$DIR/args.tmp" && mv "$DIR/args.tmp" "$DIR/args"
              if [ -n "$INSTRUCTIONS" ]; then cp "$INSTRUCTIONS" "$DIR/instructions.md"; fi
            }
            """;

    /** {@link #LOGS_ARGUMENTS}, and each process copies the MCP config it was given to {@code mcp.json}. */
    private static final String LOGS_ARGUMENTS_AND_MCP_CONFIG = """
            startup() {
              printf '[%s]' "${args[@]}" > "$DIR/args.tmp" && mv "$DIR/args.tmp" "$DIR/args"
              if [ -n "$INSTRUCTIONS" ]; then cp "$INSTRUCTIONS" "$DIR/instructions.md"; fi
              for arg in "${args[@]}"; do
                case "$arg" in --mcp-config=*) cp "${arg#--mcp-config=}" "$DIR/mcp.json" ;; esac
              done
            }
            """;

    /**
     * Claude Code's answers to {@code mcp_status} (its servers: Kompile's stdio one
     * and a remote one that failed) and to {@code mcp_reconnect}, which refuses a
     * server it does not run.
     */
    private static final String MCP_CONTROLS = """
            control() {
              case "$2" in
                mcp_status)
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"mcpServers":[{"name":"kompile","status":"connected","serverInfo":{"name":"kompile","version":"1.0.0"},"config":{"type":"stdio","command":"java","args":["-jar","kompile-cli.jar","mcp-stdio"]},"scope":"dynamic","tools":[{"name":"read"},{"name":"grep"}]},{"name":"docs","status":"failed","config":{"type":"sse","url":"http://127.0.0.1:8085/sse"},"error":"connect ECONNREFUSED"}]}}}'
                  ;;
                mcp_reconnect)
                  if [[ $3 == *'"serverName":"gone"'* ]]; then
                    emit '{"type":"control_response","response":{"subtype":"error","request_id":"'"$1"'","error":"Server not found: gone"}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'"}}'
                  fi
                  ;;
                *)
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  ;;
              esac
            }
            """;

    /** The second message's turn compacts the conversation, as Claude Code does once the context fills. */
    private static final String COMPACTS_SECOND_TURN = """
            turn() {
              say_init
              if [ "$1" = 2 ]; then
                emit '{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":150000},"session_id":"'"$SESSION"'","uuid":"c"}'
              fi
              say_text "$ANSWER"
              say_result
            }
            """;

    @TempDir
    Path tempDir;

    @Test
    void streamsTextDeltasAndExtractsFinalText() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  emit '{"type":"system","subtype":"init","session_id":"native-session-1"}'
                  say_text 'Hello '
                  say_text 'world'
                  emit '{"type":"result","subtype":"success","duration_ms":10,"num_turns":1,"usage":{"input_tokens":5,"output_tokens":2,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}'
                }
                """);
        StringBuilder streamed = new StringBuilder();

        try (ClaudeCliClient client = client(fake)) {
            String text = client.send("sonnet", null, false, null, "hi", "", streamed::append, null);

            assertEquals("Hello world", text);
            assertEquals("Hello world", streamed.toString(), "final text must equal the streamed deltas");
            assertEquals("native-session-1", client.nativeSession().sessionId());
        }
        String command = fake.argv().get(0);
        assertTrue(args(command).contains("-p"), command);
        assertTrue(command.contains("--input-format stream-json"), command);
        assertTrue(command.contains("--output-format stream-json"), command);
        assertTrue(command.contains("--include-partial-messages"), command);
        assertTrue(command.contains("--replay-user-messages"), command);
        assertTrue(command.contains("--session-id test-session"), "first turn creates the native session: " + command);
    }

    @Test
    void toolActivityThinkingNoticesAndRetriesReachTheListener() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  emit '{"type":"system","subtype":"init","session_id":"native-session"}'
                  emit '{"type":"system","subtype":"status","status":"requesting","session_id":"native-session"}'
                  emit '{"type":"system","subtype":"api_retry","attempt":2,"max_retries":10,"retry_delay_ms":1500,"error_status":529,"error":"overloaded","session_id":"native-session"}'
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"message-1"}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Thinking"}}}'
                  emit '{"type":"system","subtype":"thinking_tokens","estimated_tokens":2,"estimated_tokens_delta":2,"session_id":"native-session"}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Before tool. "}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"/tmp/a\\"}"}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_stop","index":2}}'
                  emit '{"type":"assistant","message":{"id":"message-1","content":[{"type":"thinking","thinking":"Thinking"},{"type":"text","text":"Before tool. "},{"type":"tool_use","id":"tool-1","name":"Read","input":{"path":"/tmp/a"}}]}}'
                  emit '{"type":"tool_progress","tool_use_id":"tool-1","content":"progress output"}'
                  emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":[{"type":"text","text":"file contents"}]}]}}'
                  emit '{"type":"system","subtype":"hook_started","hook_id":"h","hook_name":"PostToolUse:Read","hook_event":"PostToolUse","session_id":"native-session"}'
                  emit '{"type":"system","subtype":"notification","key":"k","text":"Tool finished","priority":"high","session_id":"native-session"}'
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"message-2"}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"After tool."}}}'
                  emit '{"type":"assistant","message":{"id":"message-2","content":[{"type":"text","text":"After tool."}]}}'
                  emit '{"type":"result","subtype":"success","usage":{"input_tokens":3,"output_tokens":2}}'
                }
                """);
        StringBuilder streamed = new StringBuilder();
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            String text = client.send("sonnet", null, false, null, "read /tmp/a", "", streamed::append, recorder);

            // The tool call closes the prose line before it.
            assertEquals("Before tool. \nAfter tool.", text);
            assertEquals(text, streamed.toString(), "final text must equal the streamed deltas");
        }
        assertEquals(List.of("tool-1:Read"), recorder.toolStarts);
        assertEquals(List.of("tool-1:{\"path\":\"/tmp/a\"}"), recorder.toolInputs);
        assertEquals(List.of("tool-1:progress output"), recorder.toolOutputs);
        assertEquals(List.of("tool-1:file contents:false"), recorder.toolResults);
        assertEquals(List.of("Thinking"), recorder.thinking);
        // Request status, thinking-token estimates and hook lifecycles are not notices.
        assertEquals(List.of("Tool finished"), recorder.notices);
        assertEquals(List.of("2/10:1500:overloaded, HTTP 529"), recorder.retries);
    }

    @Test
    void aFailedTurnReportsTheCliErrorWithoutProtocolLines() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"message-1"}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Bash","input":{}}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\": \\"ls"}}}'
                  echo 'API Error: connection reset' >&2
                  exit 1
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            Exception failure = assertThrows(Exception.class,
                    () -> client.send("sonnet", null, false, null, "list files", "", null, null));

            String message = failure.getMessage();
            assertTrue(message.contains("API Error: connection reset"), message);
            assertFalse(message.contains("stream_event"), "protocol lines are not diagnostics: " + message);
            assertFalse(message.contains("partial_json"), "protocol lines are not diagnostics: " + message);
        }
    }

    @Test
    void instructionsGoToClaudeCodeAsASystemPromptFile() throws Exception {
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", "high", false, "be terse", "hi", "", null, null));
        }
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), argv.toString());
        String command = argv.get(0);
        assertTrue(command.contains("--dangerously-skip-permissions"),
                "headless chat tool calls must not stall on permission prompts: " + command);
        assertTrue(args(command).contains("-p"), command);
        assertTrue(command.contains("--effort high"), command);
        assertTrue(command.contains("--model sonnet"), command);
        assertFalse(command.contains("be terse"), "prompt content leaked into argv: " + command);
        assertFalse(command.contains("Kompile Chat system instructions"),
                "system instructions leaked into argv: " + command);
        Path instructions = instructionsPath(command);
        assertEquals("[Kompile Chat system instructions]\nbe terse\n[End Kompile Chat system instructions]\n"
                        + ClaudeCliClient.INSTRUCTIONS_UPDATE_NOTE + "\n",
                Files.readString(fake.path("instructions.md"), StandardCharsets.UTF_8),
                "instructions must go to Claude Code as a system prompt file: " + command);
        // A new session receives the instructions only as its system prompt.
        assertEquals(List.of("hi"), fake.messages());
        // The file lives as long as the process.
        FakeClaudeCode.await("the instructions file to be deleted", () -> !Files.exists(instructions));
    }

    @Test
    void attachedImagesGoToClaudeCodeAsImageBlocksAheadOfTheText() throws Exception {
        FakeClaudeCode fake = fake();
        DirectLlmClient.AttachmentInput image = new DirectLlmClient.AttachmentInput(
                "/tmp/page.png", "image/png", true, "cGFnZQ==", null);
        DirectLlmClient.AttachmentInput notes = new DirectLlmClient.AttachmentInput(
                "/tmp/notes.txt", "text/plain", false, null, "margin notes");

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "describe it", "",
                    List.of(image, notes), null, null));
        }
        JsonNode content = null;
        for (JsonNode input : fake.inputs()) {
            if ("user".equals(input.path("type").asText())) {
                content = input.path("message").path("content");
            }
        }
        assertNotNull(content);
        assertTrue(content.isArray(), "a turn with attachments sends content blocks: " + content);
        assertEquals(3, content.size(), content.toString());
        JsonNode source = content.get(0).path("source");
        assertEquals("image", content.get(0).path("type").asText());
        assertEquals("base64", source.path("type").asText());
        assertEquals("image/png", source.path("media_type").asText());
        assertEquals("cGFnZQ==", source.path("data").asText());
        assertEquals("[File: /tmp/notes.txt]\nmargin notes", content.get(1).path("text").asText());
        assertEquals("text", content.get(2).path("type").asText());
        assertEquals("describe it", content.get(2).path("text").asText());
    }

    @Test
    void jvmShutdownStopsTheLiveSessionAndDeletesItsInstructionsFile() throws Exception {
        // The claude child is started directly via ProcessBuilder.start(), so it
        // is never tracked by ProcessManager's own shutdown hook (which only
        // kills processes it started itself): nothing would stop it, or delete
        // its instructions file, if the JVM exited without close() running
        // first (a crash, Ctrl-C, or any other path that skips it). This runs
        // the registered hook's body directly -- the same Runnable the
        // constructor hands to Runtime.getRuntime().addShutdownHook -- without
        // an actual JVM exit, so the test can assert on the result.
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", "high", false, "be terse", "hi", "", null, null));
            Path instructions = instructionsPath(fake.argv().get(0));
            assertTrue(client.processAlive(), "the fake process must still be running before shutdown");
            assertTrue(Files.exists(instructions), "the instructions file must exist while the process runs");

            client.stopOnJvmShutdown();

            assertFalse(client.processAlive(), "JVM shutdown must stop the live session's process");
            assertFalse(Files.exists(instructions),
                    "JVM shutdown must delete the live session's instructions file");
        }
    }

    @Test
    void jvmShutdownIsANoOpBeforeAnySessionStarts() throws Exception {
        // No turn ever started a process: there is nothing live for the hook to
        // stop, and it must not start one itself just by running.
        FakeClaudeCode fake = fake();
        try (ClaudeCliClient client = client(fake)) {
            assertDoesNotThrow(client::stopOnJvmShutdown);
            assertEquals(List.of(), fake.argv(), "the shutdown hook must not start a process by itself");
        }
    }

    @Test
    void changedInstructionsRideInOneTurnAfterTheUserMessage() throws Exception {
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            client.send("sonnet", null, false, "be terse", "first", "", null, null);
            client.send("sonnet", null, false, "be terse", "second", "", null, null);
            client.send("sonnet", null, false, "be thorough", "third", "", null, null);
            client.send("sonnet", null, false, "be thorough", "fourth", "", null, null);
        }
        List<String> messages = fake.messages();
        assertEquals(4, messages.size(), messages.toString());
        // A new session receives the instructions only as its system prompt.
        assertEquals("first", messages.get(0));
        assertEquals("second", messages.get(1));
        // A started session keeps the system prompt Claude Code recorded, so a
        // change rides in one turn, after the user's message, under the labels
        // that system prompt tells Claude to expect.
        String changed = messages.get(2);
        assertTrue(changed.startsWith("[User message]\nthird\n[End user message]\n"), changed);
        assertTrue(changed.contains(ClaudeCliClient.UPDATED_INSTRUCTIONS_LABEL + "\nbe thorough\n"
                + ClaudeCliClient.UPDATED_INSTRUCTIONS_END), changed);
        String systemPrompt = Files.readString(fake.path("instructions.md"), StandardCharsets.UTF_8);
        assertTrue(systemPrompt.contains(ClaudeCliClient.UPDATED_INSTRUCTIONS_LABEL)
                && systemPrompt.contains(ClaudeCliClient.UPDATED_INSTRUCTIONS_END), systemPrompt);
        assertEquals("fourth", messages.get(3));
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), argv.toString());
        assertTrue(argv.get(0).contains("--append-system-prompt-file "), argv.get(0));
    }

    @Test
    void aCompactionSendsInstructionsTheProcessDidNotStartWithAgain() throws Exception {
        FakeClaudeCode fake = fake(COMPACTS_SECOND_TURN);
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            client.send("sonnet", null, false, "be terse", "first", "", null, null);
            client.send("sonnet", null, false, "be thorough", "second", "", null, recorder);
            // Compacted, the session holds the system prompt the process started with.
            assertEquals(HashUtils.sha256Hex("be terse"), client.nativeSession().instructionsDigest());
            client.send("sonnet", null, false, "be thorough", "third", "", null, null);
            client.send("sonnet", null, false, "be thorough", "fourth", "", null, null);
            assertEquals(HashUtils.sha256Hex("be thorough"), client.nativeSession().instructionsDigest());
        }
        assertEquals(List.of("auto:150000"), recorder.compactions, "the turn still shows its compaction");
        String update = ClaudeCliClient.UPDATED_INSTRUCTIONS_LABEL + "\nbe thorough\n"
                + ClaudeCliClient.UPDATED_INSTRUCTIONS_END;
        List<String> messages = fake.messages();
        assertEquals(4, messages.size(), messages.toString());
        assertEquals("first", messages.get(0));
        assertTrue(messages.get(1).contains(update), messages.get(1));
        // The summary may have dropped the update the compacted turn carried.
        assertTrue(messages.get(2).startsWith("[User message]\nthird\n[End user message]\n"), messages.get(2));
        assertTrue(messages.get(2).contains(update), messages.get(2));
        assertEquals("fourth", messages.get(3));
        assertEquals(1, fake.argv().size(), fake.argv().toString());
    }

    @Test
    void aCompactionKeepsInstructionsTheProcessStartedWith() throws Exception {
        FakeClaudeCode fake = fake(COMPACTS_SECOND_TURN);

        try (ClaudeCliClient client = client(fake)) {
            client.send("sonnet", null, false, "be terse", "first", "", null, null);
            client.send("sonnet", null, false, "be terse", "second", "", null, null);
            client.send("sonnet", null, false, "be terse", "third", "", null, null);
        }
        // The rendered system prompt already holds them.
        assertEquals(List.of("first", "second", "third"), fake.messages());
    }

    @Test
    void aResumedSessionContinuesThroughResume() throws Exception {
        FakeClaudeCode fake = fake();
        String digest = HashUtils.sha256Hex("be terse");

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "unused", fake.binary())) {
            assertNull(client.nativeSession(), "no native session before a turn");
            client.resumeSession("saved-session", digest);

            assertEquals("ok", client.send("sonnet", null, false, "be terse", "next", "", null, null));
            assertEquals(new DirectLlmClient.ClaudeNativeSession("saved-session", digest), client.nativeSession());
        }
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), argv.toString());
        assertTrue(argv.get(0).contains("--resume saved-session"), argv.get(0));
        assertEquals(List.of("next"), fake.messages(),
                "the resumed session already holds the instructions and the conversation");
    }

    @Test
    void anUnknownResumeSessionFailsAsNotStarted() throws Exception {
        // What Claude Code 2.1.282 does for `--resume <unknown id>` in stream-json mode.
        FakeClaudeCode fake = fake("""
                startup() {
                  echo "No conversation found with session ID: gone-session" >&2
                  emit '{"type":"result","subtype":"error_during_execution","duration_ms":0,"duration_api_ms":0,"is_error":true,"num_turns":0,"session_id":"gone-session","total_cost_usd":0,"errors":["No conversation found with session ID: gone-session"]}'
                  exit 1
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            client.resumeSession("gone-session", null);
            ClaudeCliClient.TurnNotStartedException failure = assertThrows(
                    ClaudeCliClient.TurnNotStartedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException, failure.getMessage());
            assertTrue(failure.getMessage().contains("No conversation found with session ID: gone-session"),
                    failure.getMessage());
            assertFalse(client.processAlive());
        }
        assertTrue(fake.argv().get(0).contains("--resume gone-session"), fake.argv().get(0));
    }

    @Test
    void aRefusedFirstTurnCreatesNoSessionToResume() throws Exception {
        FakeClaudeCode fake = fake("""
                startup() {
                  emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"errors":["Session ID test-session is already in use"]}'
                  exit 1
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            ClaudeCliClient.TurnNotStartedException failure = assertThrows(
                    ClaudeCliClient.TurnNotStartedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertTrue(failure.getMessage().contains("is already in use"), failure.getMessage());
            assertNull(client.nativeSession(), "a refused turn must not be resumed later");
        }
    }

    @Test
    void anApiErrorFailsTheTurnOnceAndKeepsTheSession() throws Exception {
        // Claude Code 2.1.282 reports the API's rejection as a synthetic assistant
        // message. This result names no detail and counts no completed turn.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Prompt is too long"}]},"parent_tool_use_id":null,"error":"invalid_request","is_api_error_message":true}'
                  emit '{"type":"result","subtype":"success","is_error":true,"num_turns":0}'
                }
                """);
        StringBuilder streamed = new StringBuilder();
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            // The API answered a model request, so the turn ran: it is not a
            // refusal that a new session could replay unchanged.
            ClaudeCliClient.TurnFailedException failure = assertThrows(ClaudeCliClient.TurnFailedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", streamed::append, recorder));

            assertTrue(failure.getMessage().contains("Claude reported an error: Prompt is too long"),
                    failure.getMessage());
            assertEquals("", streamed.toString(), "the error is not answer text");
            assertEquals(List.of(), recorder.notices, "the caller reports the failure once");
            assertTrue(client.processAlive(), "a failed turn leaves the session's process running");
            assertNotNull(client.nativeSession(), "the session took the message, so it can be resumed");
        }
    }

    @Test
    void quotaResetHintSurvivesAGenericTerminalError() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"You have hit your limit - resets 5pm (UTC)"}]},"parent_tool_use_id":null,"error":"rate_limit","is_api_error_message":true}'
                  emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"errors":["Request failed"]}'
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            ClaudeCliClient.TurnFailedException failure = assertThrows(ClaudeCliClient.TurnFailedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertTrue(failure.getMessage().contains("Request failed"), failure.getMessage());
            assertTrue(failure.getMessage().contains("resets 5pm (UTC)"), failure.getMessage());
            assertTrue(client.processAlive(), "quota exhaustion keeps the same vendor process");
            assertNotNull(client.nativeSession(), "the same session remains available for resumption");
        }
    }

    @Test
    void tokenUsageFromTheResultEventReachesTheListener() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  say_text done
                  emit '{"type":"result","subtype":"success","num_turns":1,"usage":{"input_tokens":12,"output_tokens":3,"cache_read_input_tokens":4,"cache_creation_input_tokens":2}}'
                }
                """);
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("done", client.send(null, null, false, null, "hi", "", null, recorder));
        }
        // input_tokens is uncached input; cache reads and writes arrive separately.
        assertEquals(List.of("12/3/4/2"), recorder.usage);
    }

    @Test
    void eachRequestsTokensReachTheListenerWhileTheTurnRuns() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000}}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_task","name":"Task","input":{}}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"prompt\\":\\"look\\"}"}}}'
                  emit '{"type":"stream_event","event":{"type":"content_block_stop","index":0}}'
                  emit '{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}}'
                  emit '{"type":"assistant","parent_tool_use_id":"toolu_task","message":{"id":"msg-sub","content":[],"usage":{"input_tokens":900,"output_tokens":5}}}'
                  emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_task","content":"found it"}]}}'
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":20,"output_tokens":1,"cache_read_input_tokens":1000}}}}'
                  say_text done
                  emit '{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}}'
                  emit '{"type":"result","subtype":"success","num_turns":2,"usage":{"input_tokens":30,"output_tokens":34,"cache_read_input_tokens":2000},"modelUsage":{"claude-sonnet-5":{"inputTokens":30,"outputTokens":34,"cacheReadInputTokens":2000},"claude-haiku-4-5":{"inputTokens":900,"outputTokens":75}}}'
                }
                """);
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("done", client.send("sonnet", null, false, null, "look it up", "",
                    text -> recorder.timeline.add("text:" + text), recorder));
        }
        // Each request counts as it runs, the subagent's input included; the
        // result adds the subagent's output, which only modelUsage reports.
        assertEquals(List.of("usage:10/1/1000/0", "usage:0/29/0/0", "usage:900/0/0/0",
                "done:toolu_task", "usage:20/1/1000/0", "text:done", "usage:0/3/0/0",
                "usage:0/75/0/0"), recorder.timeline);
        assertEquals(List.of("{\"prompt\":\"look\"}"), recorder.toolInputDeltas);
    }

    @Test
    void aCancelledTurnStillCountsTheTokensItUsed() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000}}}}'
                  say_text working
                  start_tool
                }
                on_interrupt() {
                  if [ -f "$DIR/tool.pid" ]; then kill "$(cat "$DIR/tool.pid")" 2>/dev/null; fi
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"cancelled":[],"still_queued":[]}}}'
                  emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"usage":{"input_tokens":10,"output_tokens":25,"cache_read_input_tokens":1000},"modelUsage":{"claude-sonnet-5":{"inputTokens":10,"outputTokens":25,"cacheReadInputTokens":1000}}}'
                }
                """);
        StringBuilder streamed = new StringBuilder();
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));
            assertThrows(CancellationException.class, () -> client.send("sonnet", null, false, null,
                    "run the long task", "", streamed::append, recorder));
            fake.assertToolStopped();
        }
        // The interrupted request's output was billed though its turn is not shown.
        assertEquals(List.of("10/1/1000/0", "0/24/0/0"), recorder.usage);
    }

    @Test
    void aLoginFailureClassifiesAsAuthentication() throws Exception {
        FakeClaudeCode fake = fake("""
                startup() {
                  echo "Please run /login to continue." >&2
                  exit 1
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            Exception failure = assertThrows(Exception.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertInstanceOf(ClaudeCliClient.ClaudeCliAuthenticationException.class, failure,
                    "a login failure must classify as authentication: " + failure);
            assertTrue(failure.getMessage().contains("/login"), "warning must name the fix: " + failure.getMessage());
            assertNull(client.nativeSession(), "a process that never took the message created no session");
        }
    }

    @Test
    void aMissingBinaryIsNotAnAuthenticationFailure() throws Exception {
        String missing = tempDir.resolve("no-such-claude").toString();

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "s", missing)) {
            ClaudeCliClient.TurnNotStartedException failure = assertThrows(
                    ClaudeCliClient.TurnNotStartedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "a missing binary is not an auth failure");
            assertTrue(failure.getMessage().contains("no-such-claude"), failure.getMessage());
        }
    }

    @Test
    void anOverloadedCliIsNotAnAuthenticationFailure() throws Exception {
        FakeClaudeCode fake = fake("""
                startup() {
                  echo "model overloaded, try again" >&2
                  exit 3
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            ClaudeCliClient.TurnNotStartedException failure = assertThrows(
                    ClaudeCliClient.TurnNotStartedException.class,
                    () -> client.send("sonnet", null, false, null, "hi", "", null, null));

            assertFalse(failure instanceof ClaudeCliClient.ClaudeCliAuthenticationException,
                    "overload must not be classified as auth: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("exit 3"), failure.getMessage());
        }
    }

    @Test
    void ultracodeAndFastModeStartTheProcessAndChangeInPlace() throws Exception {
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            client.send("claude-opus-5-5", "ultracode", true, null, "first", "", null, null);
            client.send("claude-opus-5-5", "high", false, null, "second", "", null, null);
            client.send("claude-opus-5-5", "ultracode", false, null, "third", "", null, null);
        }
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), argv.toString());
        assertTrue(argv.get(0).contains("--effort ultracode"), argv.get(0));
        assertTrue(argv.get(0).contains("--settings {\"ultracode\":true,\"maxEffortLevel\":null,\"fastMode\":true}"),
                "startup settings must carry ultracode and fast mode without an effort cap: " + argv.get(0));
        List<JsonNode> flags = fake.controls("apply_flag_settings");
        assertEquals(2, flags.size(), flags.toString());
        JsonNode high = flags.get(0).path("settings");
        assertEquals("high", high.path("effortLevel").asText(), high.toString());
        // A concrete effort also pins maxEffortLevel to it, so a user's own
        // CLAUDE_CODE_EFFORT_LEVEL or settings-file effortLevel cannot silently
        // override what Kompile asked for (confirmed live against the real CLI).
        assertEquals("high", high.path("maxEffortLevel").asText(), high.toString());
        assertFalse(high.path("ultracode").asBoolean(true), high.toString());
        assertFalse(high.path("fastMode").asBoolean(true), high.toString());
        JsonNode ultracode = flags.get(1).path("settings");
        assertTrue(ultracode.path("ultracode").asBoolean(), ultracode.toString());
        assertFalse(ultracode.has("effortLevel"), ultracode.toString());
        // apply_flag_settings merges keys on Claude Code's side rather than
        // replacing the settings object, so the maxEffortLevel:"high" cap the
        // previous switch pinned would otherwise survive untouched here.
        // Ultracode runs at effort "xhigh", so any cap below that makes Claude
        // Code refuse it outright (ultracode_unavailable) -- this switch must
        // clear the cap explicitly, not just omit it from this payload.
        assertTrue(ultracode.has("maxEffortLevel") && ultracode.get("maxEffortLevel").isNull(),
                ultracode.toString());
        assertFalse(ultracode.path("fastMode").asBoolean(true), ultracode.toString());
        assertTrue(fake.controls("set_model").isEmpty(), fake.controls().toString());
    }

    @Test
    void startupFastModeWithoutExplicitEffortDoesNotResetTheUsersEffort() throws Exception {
        FakeClaudeCode fake = fake();
        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, true, null, "hi", "", null, null));
        }
        String args = fake.argv().get(0);
        assertTrue(args.contains("--settings {\"fastMode\":true}"), args);
        assertFalse(args.contains("--effort"), args);
        assertFalse(args.contains("effortLevel"), args);
        assertFalse(args.contains("maxEffortLevel"), args);
    }

    @Test
    void oneProcessServesEveryTurnOfTheSession() throws Exception {
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            assertEquals("ok", client.send("sonnet", null, false, null, "second", "", null, null));
            assertTrue(client.processAlive());
        }
        List<String> argv = fake.argv();
        assertEquals(1, argv.size(), argv.toString());
        assertTrue(argv.get(0).contains("--session-id test-session"), argv.get(0));
        assertEquals(List.of("first", "second"), fake.messages());
        JsonNode initialize = fake.controls().get(0);
        assertEquals("initialize", initialize.path("subtype").asText(), initialize.toString());
        // With this affordance an interrupt spares the background tasks.
        assertTrue(initialize.path("perTaskStopAffordance").asBoolean(), initialize.toString());
    }

    @Test
    void aProcessThatExitedIsReplacedByOneThatResumesTheSession() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  say_text "$ANSWER"
                  say_result
                  exit 0
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            FakeClaudeCode.await("the process to exit", () -> !client.processAlive());
            assertEquals("ok", client.send("sonnet", null, false, null, "second", "", null, null));
        }
        List<String> argv = fake.argv();
        assertEquals(2, argv.size(), argv.toString());
        assertTrue(argv.get(0).contains("--session-id test-session"), argv.get(0));
        assertTrue(argv.get(1).contains("--resume test-session"), argv.get(1));
        assertEquals(List.of("first", "second"), fake.messages());
    }

    @Test
    void aRunningSessionTakesModelEffortAndFastModeInPlace() throws Exception {
        FakeClaudeCode fake = fake();
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            client.send("sonnet", "high", false, null, "first", "", null, recorder);
            client.send("opus", "high", false, null, "second", "", null, recorder);
            client.send("opus", null, true, null, "third", "", null, recorder);
            client.send(null, null, true, null, "fourth", "", null, recorder);
        }
        assertEquals(List.of(), recorder.notices);
        assertEquals(List.of("opus", "default"), fake.controls("set_model").stream()
                .map(request -> request.path("model").asText())
                .toList());
        List<JsonNode> flags = fake.controls("apply_flag_settings");
        assertEquals(1, flags.size(), flags.toString());
        JsonNode settings = flags.get(0).path("settings");
        assertTrue(settings.has("effortLevel") && settings.get("effortLevel").isNull(), settings.toString());
        // Clearing effort clears its cap too, so a later default turn is not left
        // pinned to whatever effort the session last requested.
        assertTrue(settings.has("maxEffortLevel") && settings.get("maxEffortLevel").isNull(), settings.toString());
        assertFalse(settings.path("ultracode").asBoolean(true), settings.toString());
        assertTrue(settings.path("fastMode").asBoolean(), settings.toString());
        assertEquals(1, fake.argv().size(), fake.argv().toString());
        assertEquals(List.of("first", "second", "third", "fourth"), fake.messages());
    }

    @Test
    void anIdleSettingsChangeReachesTheLiveSessionBeforeItsNextTurn() throws Exception {
        // A /model switch made while idle (no turn running) must reach the live
        // Claude Code process right away, not wait for the chat's own next
        // send(): a follow-up turn Claude Code starts by itself in between
        // (such as its reply once a background task finished) would otherwise
        // still run at the old settings, since only send() used to apply them.
        FakeClaudeCode fake = fake();

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", "high", false, null, "first", "", null, null));

            client.applyIdleSettings("opus", "high", false);
            // Applied on the client's own worker thread, not this one: poll for it.
            FakeClaudeCode.await("the idle settings change to reach the live session",
                    () -> !fake.controls("set_model").isEmpty());
            assertEquals(List.of("opus"), fake.controls("set_model").stream()
                    .map(request -> request.path("model").asText())
                    .toList());

            // The next send() asks for exactly what was just applied while idle;
            // it must not repeat the request Claude Code already has in place.
            assertEquals("ok", client.send("opus", "high", false, null, "second", "", null, null));
            assertEquals(1, fake.controls("set_model").size(), fake.controls("set_model").toString());
        }
        assertEquals(1, fake.argv().size(), fake.argv().toString());
    }

    @Test
    void anIdleSettingsChangeIsANoOpBeforeTheFirstTurnAndAfterTheSessionEnds() throws Exception {
        // No session has started yet: there is nothing live to push the change
        // onto, and the first send() applies current settings itself.
        FakeClaudeCode fake = fake();
        try (ClaudeCliClient client = client(fake)) {
            client.applyIdleSettings("opus", "high", false);
            assertEquals(List.of(), fake.argv(), "an idle push must not start a process by itself");
        }

        // The session's process already exited on its own: pushing to it is a
        // no-op too, not a failure the caller (off the UI thread) would have to
        // handle, since its own next send() starts a fresh one anyway.
        FakeClaudeCode exiting = fake("""
                turn() {
                  say_init
                  say_text "$ANSWER"
                  say_result
                  exit 0
                }
                """);
        try (ClaudeCliClient client = client(exiting)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            FakeClaudeCode.await("the process to exit", () -> !client.processAlive());
            assertDoesNotThrow(() -> client.applyIdleSettings("opus", "high", false));
            assertEquals(List.of(), exiting.controls("set_model"));
        }
    }

    @Test
    void twoIdleSettingsChangesDuringATurnCoalesceIntoOneApplyingTheLatest() throws Exception {
        // Two quick /model switches while a turn is in flight (e.g. haiku then
        // opus, before either could apply) must not leave two waiters racing
        // to push their own captured settings once the turn ends: only the
        // latest request must ever be applied, and applying it must never
        // block the thread that called applyIdleSettings -- a UI thread -- for
        // as long as the turn runs.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  touch "$DIR/started"
                  while [ ! -f "$DIR/go" ]; do sleep 0.05; done
                  say_text "$ANSWER"
                  say_result
                }
                """);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ClaudeCliClient client = client(fake)) {
            Thread turn = new Thread(() -> {
                try {
                    result.set(client.send("sonnet", "high", false, null, "first", "", null, null));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "claude-turn");
            turn.start();
            FakeClaudeCode.awaitFile(fake.path("started"));

            // Fired from independent threads, as two quick switches from a UI
            // thread would arrive -- neither call may block on the turn above,
            // or on each other.
            Thread first = new Thread(() -> client.applyIdleSettings("haiku", "high", false));
            Thread second = new Thread(() -> client.applyIdleSettings("opus", "high", false));
            first.start();
            second.start();
            first.join(2_000);
            second.join(2_000);
            assertFalse(first.isAlive(), "applyIdleSettings must not block its caller on a turn in progress");
            assertFalse(second.isAlive(), "applyIdleSettings must not block its caller on a turn in progress");

            Files.createFile(fake.path("go"));
            turn.join(10_000);
            assertFalse(turn.isAlive(), "the turn must complete once released");
            assertNull(failure.get(), failure.get() == null ? "" : failure.get().toString());
            assertEquals("ok", result.get());

            // The application itself runs on the client's own worker thread,
            // after the turn above released turnLock: poll for it.
            FakeClaudeCode.await("the coalesced idle settings change to reach the live session",
                    () -> !fake.controls("set_model").isEmpty());
        }

        List<JsonNode> setModel = fake.controls("set_model");
        assertEquals(1, setModel.size(), "only the latest request must ever be applied: " + setModel);
        assertEquals("opus", setModel.get(0).path("model").asText());
    }

    @Test
    void anIdleSettingsRefusalBecomesANoticeOnTheNextTurn() throws Exception {
        // Non-selection idle changes still carry their refusal to the next turn.
        // A rejected model is not cached as active; sending the old model below
        // must neither switch back nor repeat the invalid selection.
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" = set_model ]; then
                    emit '{"type":"control_response","response":{"subtype":"error","request_id":"'"$1"'","error":"model not available"}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                """);
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            // Baked into the process's own startup argv, not a control request:
            // this send() must not itself trigger set_model.
            assertEquals("ok", client.send("sonnet", "high", false, null, "first", "", null, null));

            client.applyIdleSettings("opus", "high", false);
            // Applied, and refused, on the client's own worker thread: poll for
            // the request to land before the next turn starts.
            FakeClaudeCode.await("the idle settings change to reach the live session",
                    () -> !fake.controls("set_model").isEmpty());

            assertEquals("ok", client.send("sonnet", "high", false, null, "second", "", null, recorder));
        }
        assertEquals(List.of("Claude Code did not apply the model change: model not available"), recorder.notices);
        assertEquals(1, fake.controls("set_model").size(), fake.controls("set_model").toString());
    }

    @Test
    void rejectedModelSelectionReportsImmediatelyAndKeepsTheConfirmedModel() throws Exception {
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" = set_model ] && [[ "$3" == *'fable[1m]'* ]]; then
                    emit '{"type":"control_response","response":{"subtype":"error","request_id":"'"$1"'","error":"1m context is disabled"}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                """);
        LinkedBlockingQueue<String> results = new LinkedBlockingQueue<>();
        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            assertTrue(client.selectModel("fable[1m]", () -> results.add("accepted"), results::add));
            assertEquals("1m context is disabled", results.poll(5, TimeUnit.SECONDS),
                    "the rejection must not wait for another user turn");
            assertEquals("ok", client.send("sonnet", null, false, null, "still works", "", null, null));
            assertEquals(1, fake.controls("set_model").size(), "the old model was never changed");
            // A second attempt must be checked, not treated as an already-applied model.
            assertTrue(client.selectModel("fable[1m]", () -> results.add("accepted"), results::add));
            assertEquals("1m context is disabled", results.poll(5, TimeUnit.SECONDS));
            assertTrue(client.selectModel("opus", () -> results.add("accepted"), results::add));
            assertEquals("accepted", results.poll(5, TimeUnit.SECONDS));
            assertEquals("ok", client.send("opus", null, false, null, "new model", "", null, null));
            assertEquals(List.of("fable[1m]", "fable[1m]", "opus"), fake.controls("set_model").stream()
                    .map(request -> request.path("model").asText()).toList());
            assertEquals(1, fake.argv().size(), "the switch keeps the provider session");
        }
    }

    @Test
    void modelSelectionDoesNotCommitBeforeAcknowledgement() throws Exception {
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" = set_model ]; then
                    while [ ! -f "$DIR/ack" ]; do sleep 0.02; done
                  fi
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                }
                """);
        LinkedBlockingQueue<String> results = new LinkedBlockingQueue<>();
        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            assertTrue(client.selectModel("opus", () -> results.add("accepted"), results::add));
            fake.awaitControl("set_model");
            assertNull(results.poll(100, TimeUnit.MILLISECONDS));
            Files.createFile(fake.path("ack"));
            assertEquals("accepted", results.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSettingClaudeCodeRefusesBecomesANotice() throws Exception {
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" = set_model ]; then
                    emit '{"type":"control_response","response":{"subtype":"error","request_id":"'"$1"'","error":"model not available"}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                """);
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            client.send("sonnet", null, false, null, "first", "", null, null);
            assertEquals("ok", client.send("opus", null, false, null, "second", "", null, recorder));
        }
        assertEquals(List.of("Claude Code did not apply the model change: model not available"), recorder.notices);
    }

    @Test
    void cancellingATurnInterruptsItAndKeepsTheSession() throws Exception {
        FakeClaudeCode fake = fake(LONG_FIRST_TURN);
        StringBuilder streamed = new StringBuilder();

        try (ClaudeCliClient client = client(fake)) {
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));
            long started = System.nanoTime();
            assertThrows(CancellationException.class, () -> client.send("sonnet", null, false, null,
                    "run the long task", "", streamed::append, null));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsed < 10_000, "the turn must end when interrupted, not run on: it took " + elapsed + " ms");
            assertEquals("working", streamed.toString(), "text streamed before the cancel is kept");
            assertTrue(fake.awaitControl("interrupt").path("cancel_queued").asBoolean());
            fake.assertToolStopped();
            assertTrue(client.processAlive(), "an interrupted turn leaves the session's process running");

            client.setCancellationCheck(null);
            assertEquals("ok", client.send("sonnet", null, false, null, "next", "", null, null));
        }
        assertEquals(1, fake.argv().size(), fake.argv().toString());
    }

    @Test
    void aCancelledTurnClaudeCodeTookDoesNotSendItsInstructionsAgain() throws Exception {
        FakeClaudeCode fake = fake(LONG_FIRST_TURN);
        StringBuilder streamed = new StringBuilder();

        try (ClaudeCliClient client = client(fake)) {
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));
            assertThrows(CancellationException.class, () -> client.send("sonnet", null, false, "be terse",
                    "run the long task", "", streamed::append, null));
            client.setCancellationCheck(null);
            assertEquals("ok", client.send("sonnet", null, false, "be terse", "next", "", null, null));
        }
        // The process holds the instructions as its system prompt, so the turn
        // after the cancelled one sends the user's message alone.
        assertEquals(List.of("run the long task", "next"), fake.messages());
    }

    @Test
    void aTurnClaudeCodeDoesNotStopEndsWithItsProcess() throws Exception {
        FakeClaudeCode fake = fake(LONG_FIRST_TURN + """
                on_interrupt() { :; }
                """);
        StringBuilder streamed = new StringBuilder();
        Recorder recorder = new Recorder();

        try (ClaudeCliClient client = client(fake)) {
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));
            long started = System.nanoTime();
            assertThrows(CancellationException.class, () -> client.send("sonnet", null, false, null,
                    "run the long task", "", streamed::append, recorder));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsed < 15_000, "the CLI must be stopped, not awaited: the turn took " + elapsed + " ms");
            assertEquals(List.of("Claude Code did not stop the turn when asked, so its process was stopped."),
                    recorder.notices);
            assertFalse(client.processAlive());
            fake.assertToolStopped();
        }
    }

    @Test
    void interruptingTheTurnThreadEndsTheTurn() throws Exception {
        FakeClaudeCode fake = fake(LONG_FIRST_TURN);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ClaudeCliClient client = client(fake)) {
            Thread turn = new Thread(() -> {
                try {
                    client.send("sonnet", null, false, null, "run the long task", "", null, null);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "claude-turn");
            turn.start();
            FakeClaudeCode.awaitFile(fake.path("tool.pid"));
            turn.interrupt();
            turn.join(10_000);

            assertFalse(turn.isAlive(), "an interrupted turn must not wait for the CLI to finish");
            assertInstanceOf(InterruptedException.class, failure.get());
            fake.assertToolStopped();
        }
    }

    @Test
    void aTurnClaudeCodeStartsByItselfIsAnnouncedAndShownOnce() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  say_text "$ANSWER"
                  say_result
                  if [ "$1" = 1 ]; then
                    (
                      while [ ! -f "$DIR/go" ]; do sleep 0.05; done
                      say_init
                      say_text 'task finished'
                      say_result
                    ) &
                  fi
                }
                """);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setFollowUpListener(announced::add);
            assertEquals("ok", client.send("sonnet", null, false, null, "start the task", "", null, null));

            // A background task finished and Claude Code answered it on its own.
            Files.createFile(fake.path("go"));
            assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));
            StringBuilder shown = new StringBuilder();
            assertEquals("task finished", client.adoptFollowUp("f1", shown::append, null));
            assertEquals("task finished", shown.toString());
            assertEquals("", client.adoptFollowUp("f1", null, null), "a follow-up is shown once");
            assertEquals("", client.adoptFollowUp("f99", null, null));
        }
    }

    @Test
    void aMessageFoldedIntoAFollowUpShowsItsTextInTheReply() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  if [ "$1" = 1 ]; then
                    say_init
                    say_text "$ANSWER"
                    say_result
                    (
                      while [ ! -f "$DIR/go" ]; do sleep 0.05; done
                      say_init
                      say_text 'task finished. '
                    ) &
                  else
                    say_text 'and your question'
                    say_result
                  fi
                }
                """);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setFollowUpListener(announced::add);
            assertEquals("ok", client.send("sonnet", null, false, null, "start the task", "", null, null));
            Files.createFile(fake.path("go"));
            assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));

            // Claude Code takes a message into the turn it is running, so the reply
            // is that turn: what it said before the message, then the answer.
            assertEquals("task finished. and your question",
                    client.send("sonnet", null, false, null, "question", "", null, null));
            assertEquals("", client.adoptFollowUp("f1", null, null), "the reply already showed the follow-up");
        }
    }

    @Test
    void aFollowUpKnowsTheTaskEndsThatStartedIt() throws Exception {
        // A turn Claude Code starts by itself answers the tasks that ended: what
        // each was, how it ended and its summary are what the turn was asked.
        // The background part waits on a FIFO the test writes to.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-9","description":"Run the build","task_type":"local_bash"}'
                    emit '{"type":"system","subtype":"task_started","task_id":"task-2","tool_use_id":"tool-10","description":"Watch the logs","task_type":"local_bash"}'
                    mkfifo "$DIR/go"
                    (
                      read -t 10 -r _ <> "$DIR/go"
                      emit '{"type":"system","subtype":"task_notification","task_id":"task-1","status":"completed","summary":"Build passed"}'
                      emit '{"type":"system","subtype":"task_notification","task_id":"task-3","status":"failed","summary":""}'
                      say_init
                      say_text 'the build passed'
                      say_result
                    ) &
                  fi
                  say_text "$ANSWER"
                  say_result
                }
                """);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setFollowUpListener(announced::add);
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, null));

            // Opened for reading and writing, the FIFO holds the line until the fake reads it.
            try (FileChannel go = FileChannel.open(fake.path("go"), StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                go.write(ByteBuffer.wrap("go\n".getBytes(StandardCharsets.UTF_8)));
                assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));
            }
            // The task still running set nothing off; an unknown task goes by its id.
            assertEquals(List.of("Background task \"Run the build\" completed: Build passed",
                            "Background task task-3 failed"),
                    client.followUpTriggers("f1"));
            assertEquals("the build passed", client.adoptFollowUp("f1", null, null));
            assertEquals(List.of(), client.followUpTriggers("f99"));
        }
    }

    @Test
    void aTaskClaudeCodeReportsEndedTwiceIsOneTriggerLine() throws Exception {
        // Claude Code reports one task's terminal status from two different
        // events: task_updated (status nested under "patch", no description of
        // its own) fires first, then task_notification (flat fields) reports the
        // same task_id's end again. Both must fold into exactly one trigger
        // line, keeping the description only the earlier task_started carried.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-9","description":"Run the build","task_type":"local_bash"}'
                    mkfifo "$DIR/go"
                    (
                      read -t 10 -r _ <> "$DIR/go"
                      emit '{"type":"system","subtype":"task_updated","task_id":"task-1","patch":{"status":"completed"}}'
                      emit '{"type":"system","subtype":"task_notification","task_id":"task-1","status":"completed","summary":"Build passed"}'
                      say_init
                      say_text 'the build passed'
                      say_result
                    ) &
                  fi
                  say_text "$ANSWER"
                  say_result
                }
                """);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setFollowUpListener(announced::add);
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, null));

            try (FileChannel go = FileChannel.open(fake.path("go"), StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                go.write(ByteBuffer.wrap("go\n".getBytes(StandardCharsets.UTF_8)));
                assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));
            }
            // One task, ended twice: one trigger line, not two, and it is not the
            // description-less fallback the second (duplicate) report alone would give.
            assertEquals(List.of("Background task \"Run the build\" completed: Build passed"),
                    client.followUpTriggers("f1"));
            assertEquals("the build passed", client.adoptFollowUp("f1", null, null));
        }
    }

    @Test
    void aTaskEndRecordedBeforeATakeIsNotRepeatedAsAStaleTriggerAfterIt() throws Exception {
        // task_updated (task-1's first terminal report) arrives and is recorded
        // while turn 1 ("build it") is still in flight; turn 1 completing takes
        // that same message in, which clears the pending task-end map so the
        // next follow-up starts clean. A later, duplicate task_notification for
        // the same task id (Claude Code reports one task's end from two
        // different events) must still be recognized as the same, already
        // answered end -- not a brand new one whose description was already
        // consumed by the first report, which would otherwise surface as a
        // second, unlabeled trigger line in a follow-up after it.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-9","description":"Run the build","task_type":"local_bash"}'
                    emit '{"type":"system","subtype":"task_updated","task_id":"task-1","patch":{"status":"completed"}}'
                  elif [ "$1" = 2 ]; then
                    mkfifo "$DIR/go"
                    (
                      read -t 10 -r _ <> "$DIR/go"
                      emit '{"type":"system","subtype":"task_notification","task_id":"task-1","status":"completed","summary":"Build passed"}'
                      say_init
                      say_text 'anything else finished'
                      say_result
                    ) &
                  fi
                  say_text "$ANSWER"
                  say_result
                }
                """);
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setFollowUpListener(announced::add);
            // Turn 1 records task-1's first terminal report and takes it in
            // when it completes, which drops it from the pending map.
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, null));
            // Turn 2 is a second, real message: taking it in leaves nothing new
            // pending either, since only turn 1 ever reported a task end.
            assertEquals("ok", client.send("sonnet", null, false, null, "what's next", "", null, null));

            try (FileChannel go = FileChannel.open(fake.path("go"), StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                go.write(ByteBuffer.wrap("go\n".getBytes(StandardCharsets.UTF_8)));
                assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));
            }
            assertEquals(List.of(), client.followUpTriggers("f1"),
                    "task-1's end was already recorded and answered before either take(); a later, "
                            + "duplicate report for the same id must not surface as a new, unlabeled trigger");
            assertEquals("anything else finished", client.adoptFollowUp("f1", null, null));
        }
    }

    @Test
    void claudeCodeTasksAreProcessRowsThatStopTheirTask() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-9","description":"Run the build","task_type":"local_bash"}'
                  say_text "$ANSWER"
                  say_result
                }
                """);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".kompile"));

        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-test", workspace);
             ClaudeCliClient client = client(fake)) {
            client.setTaskProcesses(processes);
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, null));

            BackgroundProcessManager.ProcessEntry row = processes.listAll().stream()
                    .filter(entry -> "task-1".equals(entry.getMetadata().get("task_id")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row for task-1"));
            assertEquals("Claude: Run the build", row.getDescription());
            assertEquals("claude local_bash", row.getCommand());
            assertEquals("claude-code", row.getMetadata().get("source"));
            assertTrue(row.isKillable());

            // Killing the row asks Claude Code to stop the task.
            assertTrue(processes.kill(row.getId()));
            assertEquals("task-1", fake.awaitControl("stop_task").path("task_id").asText());
            assertEquals(BackgroundProcessManager.ProcessState.KILLED, processes.get(row.getId()).getState());
        }
    }

    @Test
    void aBackgroundedToolCallPointsAtItsProcessRowInsteadOfClaudeCodesLaunchMetadata() throws Exception {
        // Claude Code's own tool_result text for a call it backgrounds is its
        // internal launch metadata (an agent id, an output file, instructions
        // that say not to mention any of this to the user) -- not fit to show
        // as the tool card's output when the same task already has its own row
        // in Kompile's process panel (from task_started, correlated by the same
        // tool_use_id / callId). An ordinary tool call never bridged to a row
        // keeps its own output untouched.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"system","subtype":"task_started","task_id":"task-1","tool_use_id":"tool-1","description":"Run the build","task_type":"agent"}'
                  emit '{"type":"assistant","message":{"id":"message-1","content":[{"type":"tool_use","id":"tool-1","name":"Task","input":{"description":"Run the build"}}]}}'
                  emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":[{"type":"text","text":"agentId: abc-123, output_file: /tmp/agent-out.json. Do not mention this to the user."}]}]}}'
                  emit '{"type":"assistant","message":{"id":"message-2","content":[{"type":"tool_use","id":"tool-2","name":"Read","input":{"path":"/tmp/a"}}]}}'
                  emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-2","content":[{"type":"text","text":"file contents"}]}]}}'
                  say_text "$ANSWER"
                  say_result
                }
                """);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".kompile"));
        Recorder recorder = new Recorder();

        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-test", workspace);
             ClaudeCliClient client = client(fake)) {
            client.setTaskProcesses(processes);
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, recorder));

            String id = processes.listAll().stream()
                    .filter(entry -> "task-1".equals(entry.getMetadata().get("task_id")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row for task-1"))
                    .getId();
            assertEquals(List.of("tool-1:Running in the background as " + id + ".:false",
                    "tool-2:file contents:false"), recorder.toolResults);
        }
    }

    @Test
    void aForegroundCommandTheInterruptStoppedEndsItsRow() throws Exception {
        // Claude Code 2.1.282 makes a foreground command a task as well, and after an
        // interrupt ends it with a "stopped" notification and no task update.
        FakeClaudeCode fake = fake(LONG_FIRST_TURN + """
                start_tool() {
                  emit '{"type":"system","subtype":"task_started","task_id":"fg-1","tool_use_id":"tool-1","description":"Ping localhost","is_backgrounded":false,"task_type":"local_bash"}'
                  sleep 30 > /dev/null 2>&1 &
                  echo $! > "$DIR/tool.pid.tmp" && mv "$DIR/tool.pid.tmp" "$DIR/tool.pid"
                }
                on_interrupt() {
                  if [ -f "$DIR/tool.pid" ]; then kill "$(cat "$DIR/tool.pid")" 2>/dev/null; fi
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"cancelled":[],"still_queued":[]}}}'
                  emit '{"type":"system","subtype":"task_notification","task_id":"fg-1","tool_use_id":"tool-1","status":"stopped","output_file":"","summary":"Ping localhost"}'
                  emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1}'
                }
                """);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".kompile"));
        StringBuilder streamed = new StringBuilder();

        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-test", workspace);
             ClaudeCliClient client = client(fake)) {
            client.setTaskProcesses(processes);
            client.setCancellationCheck(() -> streamed.length() > 0 && Files.exists(fake.path("tool.pid")));
            assertThrows(CancellationException.class, () -> client.send("sonnet", null, false, null,
                    "run the long task", "", streamed::append, null));

            String id = processes.listAll().stream()
                    .filter(entry -> "fg-1".equals(entry.getMetadata().get("task_id")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no row for fg-1: " + processes.listAll()))
                    .getId();
            FakeClaudeCode.await("the stopped command's row to end", () -> !processes.get(id).isRunning());
            assertEquals(BackgroundProcessManager.ProcessState.FAILED, processes.get(id).getState());
            assertEquals(-1, processes.get(id).getExitCode());
            fake.assertToolStopped();
            assertTrue(fake.controls("stop_task").isEmpty(), "Claude Code stopped the command itself");
            assertTrue(client.processAlive(), "an interrupted turn leaves the session's process running");
        }
    }

    @Test
    void aControlRequestFromClaudeCodeIsAnsweredSoTheTurnGoesOn() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  emit '{"type":"control_request","request_id":"cc-1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls"}}}'
                  say_text "$ANSWER"
                  say_result
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "list files", "", null, null));

            FakeClaudeCode.await("the answer to the control request",
                    () -> controlResponse(fake) != null);
            JsonNode response = controlResponse(fake).path("response");
            assertEquals("cc-1", response.path("request_id").asText(), response.toString());
            assertEquals("error", response.path("subtype").asText(), response.toString());
            assertEquals("Kompile does not handle can_use_tool requests", response.path("error").asText());
        }
    }

    @Test
    void aFollowUpMarkerNamesItsFollowUp() {
        assertEquals("f1", ClaudeCliClient.followUpId(ClaudeCliClient.followUpMarker("f1")));
        assertNull(ClaudeCliClient.followUpId("hello"));
        assertNull(ClaudeCliClient.followUpId(null));
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
    void claudeCodeNamesTheChatThatOwnsItsProcessPanel() throws Exception {
        // Claude Code passes its environment to Kompile's MCP server, which then
        // registers as this chat's child, so its monitored processes wake the chat.
        FakeClaudeCode fake = fake("""
                startup() {
                  printf '%s' "${KOMPILE_PARENT_SESSION_ID-}" > "$DIR/parent.tmp" && mv "$DIR/parent.tmp" "$DIR/parent-session"
                }
                """);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".kompile"));

        try (BackgroundProcessManager processes = new BackgroundProcessManager("claude-test", workspace);
             ClaudeCliClient client = client(fake)) {
            client.setTaskProcesses(processes);
            assertEquals("ok", client.send("sonnet", null, false, null, "build it", "", null, null));

            FakeClaudeCode.awaitFile(fake.path("parent-session"));
            assertEquals("claude-test", Files.readString(fake.path("parent-session")));
        }

        // Without a panel, whatever parent the chat inherited is left alone.
        ProcessBuilder builder = new ProcessBuilder("claude");
        builder.environment().put(ClaudeCliClient.PARENT_SESSION_ENV, "inherited");
        ClaudeCliClient.withParentSession(builder, null);
        assertEquals("inherited", builder.environment().get(ClaudeCliClient.PARENT_SESSION_ENV));
    }

    @Test
    void aJudgeRunsClaudeCodeWithoutToolsMcpServersSkillsOrASavedSession() throws Exception {
        FakeClaudeCode verdict = new FakeClaudeCode(tempDir.resolve("verdict-claude"), LOGS_ARGUMENTS);
        Path judgeWorkspace = Files.createDirectories(tempDir.resolve("judge-workspace"));

        try (ClaudeCliClient client = new ClaudeCliClient(judgeWorkspace, null, verdict.binary(),
                new ClaudeCliClient.Mode(true, true, ""))) {
            assertEquals("ok", client.send("sonnet", null, false, "judge the turn", "the turn", "", null, null));
        }
        String args = Files.readString(verdict.path("args"), StandardCharsets.UTF_8);
        // No built-in tool, no MCP server (the project's .mcp.json included), no skills.
        assertTrue(args.contains("[--tools][][--strict-mcp-config]"), args);
        assertFalse(args.contains("[--mcp-config"), args);
        assertTrue(args.contains("[--disable-slash-commands]"), args);
        assertTrue(args.contains("[--no-session-persistence]"), args);
        // One request: no later message carries changed instructions.
        assertEquals("[Kompile Chat system instructions]\njudge the turn\n[End Kompile Chat system instructions]\n",
                Files.readString(verdict.path("instructions.md"), StandardCharsets.UTF_8));
        // Kompile's tools are not registered in the project.
        assertFalse(Files.exists(judgeWorkspace.resolve(".mcp.json")));
        assertFalse(Files.exists(judgeWorkspace.resolve(".claude").resolve("settings.local.json")));

        // A judge that holds a conversation keeps its session, so changed
        // instructions can reach it; it has no tools to carry forged ones.
        FakeClaudeCode conversation = new FakeClaudeCode(tempDir.resolve("conversation-claude"), LOGS_ARGUMENTS);
        try (ClaudeCliClient client = new ClaudeCliClient(judgeWorkspace, null, conversation.binary(),
                new ClaudeCliClient.Mode(true, false, ""))) {
            assertEquals("ok", client.send("sonnet", null, false, "judge the turn", "why?", "", null, null));
        }
        String conversationArgs = Files.readString(conversation.path("args"), StandardCharsets.UTF_8);
        assertTrue(conversationArgs.contains("[--tools][][--strict-mcp-config]"), conversationArgs);
        assertFalse(conversationArgs.contains("[--no-session-persistence]"), conversationArgs);
        assertTrue(Files.readString(conversation.path("instructions.md"), StandardCharsets.UTF_8)
                .endsWith(ClaudeCliClient.INSTRUCTIONS_UPDATE_NOTE + "\n"));
        assertFalse(Files.exists(judgeWorkspace.resolve(".claude").resolve("settings.local.json")));

        // A chat's own session has Claude Code's tools and registers Kompile's in a
        // config of its own: the project's shared .mcp.json is not touched.
        FakeClaudeCode chat = new FakeClaudeCode(tempDir.resolve("chat-claude"), LOGS_ARGUMENTS_AND_MCP_CONFIG);
        Path chatWorkspace = Files.createDirectories(tempDir.resolve("chat-workspace"));
        Path kompile = Files.createFile(tempDir.resolve("kompile")).toAbsolutePath();
        assertTrue(kompile.toFile().setExecutable(true));
        String previousBinary = System.getProperty("kompile.cli.binary");
        System.setProperty("kompile.cli.binary", kompile.toString());
        try (ClaudeCliClient client = new ClaudeCliClient(chatWorkspace, null, chat.binary())) {
            assertEquals("ok", client.send("sonnet", null, false, "be terse", "hi", "", null, null));
            assertTrue(Files.exists(chatWorkspace.resolve(".claude").resolve("settings.local.json")));
        } finally {
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
        String chatArgs = Files.readString(chat.path("args"), StandardCharsets.UTF_8);
        assertFalse(chatArgs.contains("[--tools]"), chatArgs);
        assertFalse(chatArgs.contains("[--disallowedTools]"),
                "native availability and Kompile MCP injection must remain unchanged: " + chatArgs);
        assertFalse(chatArgs.contains("[--settings]"),
                "a default-effort launch must leave the user's settings alone: " + chatArgs);
        assertFalse(chatArgs.contains("[--strict-mcp-config]"), chatArgs);
        assertFalse(chatArgs.contains("[--disable-slash-commands]"), chatArgs);
        assertFalse(chatArgs.contains("[--no-session-persistence]"), chatArgs);
        assertTrue(chatArgs.contains("[--mcp-config="), chatArgs);
        assertEquals(kompile.toString(), JsonUtils.standardMapper().readTree(chat.path("mcp.json").toFile())
                .path("mcpServers").path("kompile").path("command").asText());
        assertFalse(Files.exists(chatWorkspace.resolve(".mcp.json")));
        int configStart = chatArgs.indexOf("[--mcp-config=") + "[--mcp-config=".length();
        Path chatConfig = Path.of(chatArgs.substring(configStart, chatArgs.indexOf(']', configStart)));
        assertFalse(Files.exists(chatConfig), "closing the chat deletes its config");
    }

    @Test
    void aJudgeRunsAtItsPreferredEffortWhereClaudeCodeListsItForTheModel() throws Exception {
        ClaudeCliClient.Mode judge = new ClaudeCliClient.Mode(true, true, "low");

        FakeClaudeCode sonnet = new FakeClaudeCode(tempDir.resolve("sonnet-claude"), MODEL_CATALOG);
        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, null, sonnet.binary(), judge)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "judge this", "", null, null));
        }
        assertFalse(sonnet.argv().get(0).contains("--effort"), sonnet.argv().get(0));
        List<JsonNode> flags = sonnet.controls("apply_flag_settings");
        assertEquals(1, flags.size(), flags.toString());
        assertEquals("low", flags.get(0).path("settings").path("effortLevel").asText(), flags.toString());
        // A plain effortLevel request is not enough: a user's own effort (an
        // effortLevel in ~/.claude/settings.json, or its CLAUDE_CODE_EFFORT_LEVEL
        // env var) wins over it, confirmed live, so the judge would silently run
        // at the user's effort instead of its own and risk the 45s deadline.
        // maxEffortLevel pinned to the same value is what actually forces it.
        assertEquals("low", flags.get(0).path("settings").path("maxEffortLevel").asText(), flags.toString());
        // The effort is in place before Claude Code takes the message in.
        List<String> inputs = sonnet.inputs().stream()
                .map(input -> input.path("request").path("subtype").asText(input.path("type").asText()))
                .toList();
        assertTrue(inputs.indexOf("apply_flag_settings") < inputs.indexOf("user"), inputs.toString());

        // Haiku takes no effort, so the judge runs at Claude Code's default.
        FakeClaudeCode haiku = new FakeClaudeCode(tempDir.resolve("haiku-claude"), MODEL_CATALOG);
        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, null, haiku.binary(), judge)) {
            assertEquals("ok", client.send("haiku", null, false, null, "judge this", "", null, null));
        }
        assertEquals(List.of(), haiku.controls("apply_flag_settings"));
        assertFalse(haiku.argv().get(0).contains("--effort"), haiku.argv().get(0));

        // An effort the request names wins.
        FakeClaudeCode named = new FakeClaudeCode(tempDir.resolve("named-claude"), MODEL_CATALOG);
        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, null, named.binary(), judge)) {
            assertEquals("ok", client.send("sonnet", "high", false, null, "judge this", "", null, null));
        }
        assertTrue(named.argv().get(0).contains("--effort high"), named.argv().get(0));
        assertTrue(named.argv().get(0).contains(
                "--settings {\"effortLevel\":\"high\",\"maxEffortLevel\":\"high\",\"ultracode\":false,\"fastMode\":false}"),
                "startup must pin the requested effort, not just runtime switches: " + named.argv().get(0));
        assertEquals(List.of(), named.controls("apply_flag_settings"));

        // A chat's own session keeps Claude Code's default.
        FakeClaudeCode chat = new FakeClaudeCode(tempDir.resolve("chat-claude"), MODEL_CATALOG);
        try (ClaudeCliClient client = client(chat)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "hi", "", null, null));
        }
        assertEquals(List.of(), chat.controls("apply_flag_settings"));
    }

    @Test
    void aResumedJudgeSessionKeepsTheEffortAnEarlierProcessListed() throws Exception {
        // What a resumed process prints first is its first message's, so its own
        // catalog is not awaited; here only the process that began the session has one.
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" = initialize ] && [ -z "$RESUMED" ]; then
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"models":[{"value":"sonnet","supportedEffortLevels":["low","high"]}]}}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                turn() {
                  say_init
                  say_text "$ANSWER"
                  say_result
                  exit 0
                }
                """);

        try (ClaudeCliClient client = new ClaudeCliClient(tempDir, "test-session", fake.binary(),
                new ClaudeCliClient.Mode(true, false, "low"))) {
            assertEquals("ok", client.send("sonnet", null, false, null, "first", "", null, null));
            FakeClaudeCode.await("the process to exit", () -> !client.processAlive());
            assertEquals("ok", client.send("sonnet", null, false, null, "second", "", null, null));
        }
        List<String> argv = fake.argv();
        assertEquals(2, argv.size(), argv.toString());
        assertTrue(argv.get(1).contains("--resume test-session"), argv.get(1));
        assertEquals(List.of("low", "low"), fake.controls("apply_flag_settings").stream()
                .map(request -> request.path("settings").path("effortLevel").asText())
                .toList());
    }

    @Test
    void anEffortIsListedOnlyForAModelThatTakesIt() throws Exception {
        JsonNode rows = JsonUtils.standardMapper().readTree("""
                [{"value":"default","supportedEffortLevels":["low","high"]},
                 {"value":"sonnet","supportedEffortLevels":["LOW","medium"]},
                 {"value":"opus","supportedEffortLevels":["low"],"disabled":true},
                 {"value":"haiku"}]
                """);

        assertTrue(ClaudeCliClient.effortListed(rows, "sonnet", "low"));
        assertTrue(ClaudeCliClient.effortListed(rows, "Sonnet", " medium "));
        assertTrue(ClaudeCliClient.effortListed(rows, null, "high"), "a turn without a model runs the default row");
        assertFalse(ClaudeCliClient.effortListed(rows, "sonnet", "high"));
        assertFalse(ClaudeCliClient.effortListed(rows, "opus", "low"), "a disabled row takes nothing");
        assertFalse(ClaudeCliClient.effortListed(rows, "haiku", "low"));
        assertFalse(ClaudeCliClient.effortListed(rows, "claude-unlisted", "low"));
        assertFalse(ClaudeCliClient.effortListed(rows, "sonnet", ""));
        assertFalse(ClaudeCliClient.effortListed(null, "sonnet", "low"));
    }

    @Test
    void mcpServersComeFromTheLiveProcessAndReconnectRestartsOneByName() throws Exception {
        FakeClaudeCode fake = fake(MCP_CONTROLS);

        try (ClaudeCliClient client = client(fake)) {
            // No process serves the session before its first message, and asking starts none.
            assertNull(client.mcpServers(Duration.ofSeconds(5)));
            assertFalse(client.reconnectMcpServer("kompile", Duration.ofSeconds(5)));
            assertTrue(fake.argv().isEmpty(), fake.argv().toString());

            assertEquals("ok", client.send("sonnet", null, false, null, "hi", "", null, null));
            List<ClaudeMcpServer> servers = client.mcpServers(Duration.ofSeconds(5));

            assertEquals(List.of(
                    new ClaudeMcpServer("kompile", "connected", "kompile 1.0.0", "stdio",
                            List.of("java", "-jar", "kompile-cli.jar", "mcp-stdio"), 2, ""),
                    new ClaudeMcpServer("docs", "failed", "", "sse",
                            List.of("http://127.0.0.1:8085/sse"), 0, "connect ECONNREFUSED")), servers);
            assertTrue(servers.get(0).isKompileStdio());
            assertFalse(servers.get(1).isKompileStdio());

            assertTrue(client.reconnectMcpServer("kompile", Duration.ofSeconds(5)));
            IOException refused = assertThrows(IOException.class,
                    () -> client.reconnectMcpServer("gone", Duration.ofSeconds(5)));
            assertEquals("Server not found: gone", refused.getMessage());
        }
        assertEquals(List.of("kompile", "gone"), fake.controls("mcp_reconnect").stream()
                .map(request -> request.path("serverName").asText()).toList());
        assertEquals(1, fake.argv().size(), fake.argv().toString());
    }

    @Test
    void anMcpRequestClaudeCodeDoesNotAnswerTimesOutAndTheSessionGoesOn() throws Exception {
        FakeClaudeCode fake = fake("""
                control() {
                  if [ "$2" != mcp_status ]; then
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                """);

        try (ClaudeCliClient client = client(fake)) {
            assertEquals("ok", client.send("sonnet", null, false, null, "hi", "", null, null));

            IOException silent = assertThrows(IOException.class,
                    () -> client.mcpServers(Duration.ofSeconds(1)));

            assertEquals("Claude Code did not answer mcp_status within 1 s", silent.getMessage());
            assertEquals("ok", client.send("sonnet", null, false, null, "next", "", null, null));
        }
        assertEquals(1, fake.argv().size(), fake.argv().toString());
    }

    @Test
    void aMessageWrittenIntoTheRunningTurnIsTakenIntoIt() throws Exception {
        // Claude Code folds the message into the turn it runs: it echoes the
        // message, and the turn goes on to its result.
        FakeClaudeCode fake = fake("""
                turn() {
                  if [ "$1" = 1 ]; then
                    say_init
                    say_text working
                  else
                    say_text ' done'
                    say_result
                  fi
                }
                """);
        Injections injections = new Injections();

        try (ClaudeCliClient client = client(fake)) {
            client.setInjectionListener(injections);
            BackgroundTurn turn = new BackgroundTurn(client, "start");
            turn.awaitOutput("working");

            assertTrue(client.injectIntoRunningTurn("event-1", "proc-7 ended"));
            assertEquals("event-1", injections.delivered.poll(10, TimeUnit.SECONDS));
            turn.join();

            assertNull(turn.failure.get());
            assertEquals("working done", turn.reply.get());
            assertEquals(List.of("start", "proc-7 ended"), fake.messages());
            JsonNode injected = fake.inputs().stream()
                    .filter(input -> "event-1".equals(input.path("uuid").asText()))
                    .findFirst().orElseThrow();
            // Priority "now" would abort the turn; without one Claude Code folds the message in.
            assertTrue(injected.path("priority").isMissingNode(), injected.toString());
            assertTrue(injections.dropped.isEmpty(), injections.dropped.toString());
        }
    }

    @Test
    void aMessageTheStoppedTurnLeftQueuedIsReportedDropped() throws Exception {
        // The turn has not taken the message in when it is stopped, so the
        // interrupt's cancel_queued drops it, and Claude Code names it in its answer.
        FakeClaudeCode fake = fake("""
                QUEUED=
                turn() {
                  say_init
                  say_text working
                  IFS= read -r queued
                  printf '%s\\n' "$queued" >> "$DIR/stdin.log"
                  [[ $queued =~ $re_uuid ]] && QUEUED="${BASH_REMATCH[1]}"
                }
                on_interrupt() {
                  emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{"cancelled":["'"$QUEUED"'"],"still_queued":[]}}}'
                  emit '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1}'
                }
                """);
        Injections injections = new Injections();
        AtomicBoolean stop = new AtomicBoolean();

        try (ClaudeCliClient client = client(fake)) {
            client.setInjectionListener(injections);
            client.setCancellationCheck(stop::get);
            BackgroundTurn turn = new BackgroundTurn(client, "start");
            turn.awaitOutput("working");

            assertTrue(client.injectIntoRunningTurn("event-1", "proc-7 ended"));
            stop.set(true);
            turn.join();

            assertInstanceOf(CancellationException.class, turn.failure.get());
            assertTrue(fake.awaitControl("interrupt").path("cancel_queued").asBoolean(),
                    "the interrupt drops the messages still queued");
            assertEquals("event-1", injections.dropped.poll(10, TimeUnit.SECONDS));
            assertTrue(injections.delivered.isEmpty(), injections.delivered.toString());
            assertFalse(client.injectIntoRunningTurn("event-2", "proc-8 ended"), "no turn runs");
        }
    }

    @Test
    void nothingIsWrittenWhenNoTurnRuns() throws Exception {
        FakeClaudeCode fake = fake();
        Injections injections = new Injections();

        try (ClaudeCliClient client = client(fake)) {
            client.setInjectionListener(injections);
            assertFalse(client.injectIntoRunningTurn("event-1", "proc-7 ended"), "no process runs yet");
            assertEquals("ok", client.send("sonnet", null, false, null, "hi", "", null, null));
            assertFalse(client.injectIntoRunningTurn("event-2", "proc-8 ended"), "the turn has ended");
        }
        assertEquals(List.of("hi"), fake.messages());
        assertTrue(injections.delivered.isEmpty(), injections.delivered.toString());
        assertTrue(injections.dropped.isEmpty(), injections.dropped.toString());
    }

    @Test
    void aMessageTheTurnEndsBeforeTakingInRunsAsATurnOfItsOwn() throws Exception {
        // The message reaches Claude Code too late for the turn, so Claude Code
        // takes it in once the turn ends and answers it in a turn of its own.
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  if [ "$1" = 1 ]; then
                    say_text working
                    while [ ! -f "$DIR/go" ]; do sleep 0.05; done
                  else
                    say_text "$ANSWER"
                  fi
                  say_result
                }
                """);
        Injections injections = new Injections();
        LinkedBlockingQueue<String> announced = new LinkedBlockingQueue<>();

        try (ClaudeCliClient client = client(fake)) {
            client.setInjectionListener(injections);
            client.setFollowUpListener(announced::add);
            BackgroundTurn turn = new BackgroundTurn(client, "start");
            turn.awaitOutput("working");

            assertTrue(client.injectIntoRunningTurn("event-1", "proc-7 ended"));
            Files.createFile(fake.path("go"));
            turn.join();

            assertNull(turn.failure.get());
            assertEquals("working", turn.reply.get());
            assertEquals("event-1", injections.delivered.poll(10, TimeUnit.SECONDS));
            assertEquals("f1", announced.poll(10, TimeUnit.SECONDS));
            assertEquals("ok", client.adoptFollowUp("f1", null, null));
            assertTrue(injections.dropped.isEmpty(), injections.dropped.toString());
        }
    }

    @Test
    void aMessageUnreadWhenClaudeCodeExitsIsReportedDropped() throws Exception {
        FakeClaudeCode fake = fake("""
                turn() {
                  say_init
                  say_text working
                  IFS= read -r queued
                  printf '%s\\n' "$queued" >> "$DIR/stdin.log"
                  exit 3
                }
                """);
        Injections injections = new Injections();

        try (ClaudeCliClient client = client(fake)) {
            client.setInjectionListener(injections);
            BackgroundTurn turn = new BackgroundTurn(client, "start");
            turn.awaitOutput("working");

            assertTrue(client.injectIntoRunningTurn("event-1", "proc-7 ended"));
            turn.join();

            assertNotNull(turn.failure.get(), "the turn fails with its process");
            assertTrue(String.valueOf(turn.failure.get().getMessage()).contains("exit 3"),
                    turn.failure.get().toString());
            assertEquals("event-1", injections.dropped.poll(10, TimeUnit.SECONDS));
            assertTrue(injections.delivered.isEmpty(), injections.delivered.toString());
            assertFalse(client.injectIntoRunningTurn("event-2", "proc-8 ended"), "no process runs");
        }
    }

    private FakeClaudeCode fake() throws IOException {
        return fake("");
    }

    private FakeClaudeCode fake(String overrides) throws IOException {
        return new FakeClaudeCode(tempDir.resolve("claude"), overrides);
    }

    private ClaudeCliClient client(FakeClaudeCode fake) {
        return new ClaudeCliClient(tempDir, "test-session", fake.binary());
    }

    private static List<String> args(String command) {
        return List.of(command.split(" "));
    }

    /** The system prompt file a command names; it is deleted once its process ends. */
    private static Path instructionsPath(String command) {
        List<String> args = args(command);
        int flag = args.indexOf("--append-system-prompt-file");
        assertTrue(flag >= 0 && flag + 1 < args.size(),
                "instructions must go to Claude Code as a system prompt file: " + command);
        String path = args.get(flag + 1);
        assertTrue(path.endsWith(".md"), command);
        return Path.of(path);
    }

    /** The first control response written to the fake, or null before there is one. */
    private static JsonNode controlResponse(FakeClaudeCode fake) throws IOException {
        for (JsonNode input : fake.inputs()) {
            if ("control_response".equals(input.path("type").asText())) return input;
        }
        return null;
    }

    /** Records what a turn reports to its activity listener. */
    private static final class Recorder implements ClaudeCliClient.ActivityListener {
        final List<String> toolStarts = new ArrayList<>();
        final List<String> toolInputs = new ArrayList<>();
        final List<String> toolOutputs = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        final List<String> retries = new ArrayList<>();
        final List<String> usage = new ArrayList<>();
        final List<String> compactions = new ArrayList<>();
        final List<String> toolInputDeltas = new ArrayList<>();
        /** Usage and finished tools in the order they arrived; a test's output can add its text. */
        final List<String> timeline = new ArrayList<>();

        @Override
        public void onToolStart(String callId, String name, String input) {
            toolStarts.add(callId + ":" + name);
        }

        @Override
        public void onToolInputDelta(String delta) {
            toolInputDeltas.add(delta);
        }

        @Override
        public void onToolInput(String callId, String name, String input) {
            toolInputs.add(callId + ":" + input);
        }

        @Override
        public void onToolOutput(String callId, String name, String output) {
            toolOutputs.add(callId + ":" + output);
        }

        @Override
        public void onToolComplete(String callId, String name, String output, int exitCode, boolean error) {
            toolResults.add(callId + ":" + output + ":" + error);
            timeline.add("done:" + callId);
        }

        @Override
        public void onTokenUsage(long input, long output, long cacheRead, long cacheCreation) {
            String counts = input + "/" + output + "/" + cacheRead + "/" + cacheCreation;
            usage.add(counts);
            timeline.add("usage:" + counts);
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
        public void onRetry(int attempt, int maxAttempts, long delayMs, String reason) {
            retries.add(attempt + "/" + maxAttempts + ":" + delayMs + ":" + reason);
        }

        @Override
        public void onCompacted(String trigger, long tokensBefore) {
            compactions.add(trigger + ":" + tokensBefore);
        }
    }

    /** Records what became of each message written into a running turn. */
    private static final class Injections implements DirectLlmClient.ClaudeInjectionListener {
        final LinkedBlockingQueue<String> delivered = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> dropped = new LinkedBlockingQueue<>();

        @Override
        public void delivered(String id) {
            delivered.add(id);
        }

        @Override
        public void dropped(String id) {
            dropped.add(id);
        }
    }

    /** A turn sent on a thread of its own, so the test can act while it runs. */
    private static final class BackgroundTurn {
        final StringBuffer streamed = new StringBuffer();
        final AtomicReference<String> reply = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        BackgroundTurn(ClaudeCliClient client, String message) {
            thread = new Thread(() -> {
                try {
                    reply.set(client.send("sonnet", null, false, null, message, "", streamed::append, null));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "claude-turn");
            thread.start();
        }

        void awaitOutput(String text) throws Exception {
            FakeClaudeCode.await("the turn to show " + text, () -> streamed.toString().contains(text));
        }

        void join() throws InterruptedException {
            thread.join(10_000);
            assertFalse(thread.isAlive(), "the turn did not end");
        }
    }
}
