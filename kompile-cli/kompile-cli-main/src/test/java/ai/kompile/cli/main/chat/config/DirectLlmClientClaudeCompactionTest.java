/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Claude Code session reports about its context reaches the chat loop:
 * the input size of the turn's last request (the turn's usage adds up every
 * request), the limits it applies to the model, whether Claude Code could
 * compact the session, and whether a turn the session no longer fits can be
 * retried.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientClaudeCompactionTest {

    /** How Claude Code 2.1.282 reports the API rejecting a request for its size. */
    private static final String PROMPT_TOO_LONG = """
            {"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Prompt is too long"}]},"parent_tool_use_id":null,"error":"invalid_request","is_api_error_message":true}""";

    /** A session that fails a compaction on its first turn and compacts on its second. */
    private static final String COMPACTS_ON_THE_SECOND_TURN = """
            turn() {
              emit '{"type":"system","subtype":"init","session_id":"native-1"}'
              if [ "$1" = 1 ]; then
                emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":4,"cache_read_input_tokens":180000,"cache_creation_input_tokens":1000}}},"parent_tool_use_id":null}'
                emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"first"}},"parent_tool_use_id":null}'
                emit '{"type":"system","subtype":"status","status":null,"compact_result":"failed","compact_error":"prompt too long","session_id":"native-1","uuid":"u1"}'
                emit '{"type":"result","subtype":"success","num_turns":2,"usage":{"input_tokens":8,"output_tokens":5,"cache_read_input_tokens":360000,"cache_creation_input_tokens":2000}}'
              else
                emit '{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":181004},"session_id":"native-1","uuid":"u2"}'
                emit '{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg-2","usage":{"input_tokens":9,"cache_creation_input_tokens":12000}}},"parent_tool_use_id":null}'
                emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"second"}},"parent_tool_use_id":null}'
                emit '{"type":"result","subtype":"success","num_turns":1,"usage":{"input_tokens":9,"output_tokens":3,"cache_creation_input_tokens":12000}}'
              fi
            }
            """;

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
    void aFailedCompactionHoldsUntilTheSessionCompacts() throws Exception {
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, COMPACTS_ON_THE_SECOND_TURN);

            DirectLlmClient.StreamResult first = client.streamChat("first", "system rules", null, null);
            assertFalse(first.failed, first.failureMessage);
            assertTrue(client.claudeCompactionFailed(), "Claude Code reported that it could not compact");
            assertEquals(181_004, first.contextInputTokens(),
                    "the last request's input with its cache, not the turn's summed usage");

            DirectLlmClient.StreamResult second = client.streamChat("second", "system rules", null, null);
            assertFalse(second.failed, second.failureMessage);
            assertFalse(client.claudeCompactionFailed(), "the session compacted");
            assertEquals(12_009, second.contextInputTokens(),
                    "the request after the compaction measures the compacted context");
        }
    }

    @Test
    void aReplacedSessionDropsTheFailure() throws Exception {
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, COMPACTS_ON_THE_SECOND_TURN);
            client.streamChat("first", "system rules", null, null);
            assertTrue(client.claudeCompactionFailed());

            // Kompile's compaction rebuilds the history, which replaces the session.
            client.clearHistory();
            assertFalse(client.claudeCompactionFailed());
        }
    }

    @Test
    void aSessionRejectedBeforeAnyOutputCanBeRetried() throws Exception {
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, """
                    turn() {
                      emit '{"type":"system","subtype":"init","session_id":"native-1"}'
                      emit '%s'
                      emit '{"type":"result","subtype":"success","is_error":true,"num_turns":1,"result":"Prompt is too long"}'
                    }
                    """.formatted(PROMPT_TOO_LONG));

            DirectLlmClient.StreamResult result = client.streamChat("next", "system rules", null, null);
            assertTrue(result.canRetryAfterContextOverflow(), result.failureMessage);
            assertTrue(client.claudeCompactionFailed(), "the session no longer fits");
        }
    }

    @Test
    void aSessionRejectedAfterAToolRanIsNotRetried() throws Exception {
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, """
                    turn() {
                      emit '{"type":"system","subtype":"init","session_id":"native-1"}'
                      emit '{"type":"assistant","message":{"id":"msg-1","content":[{"type":"tool_use","id":"tool-1","name":"Bash","input":{"command":"touch built"}}]},"parent_tool_use_id":null}'
                      emit '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"ok"}]},"parent_tool_use_id":null}'
                      emit '%s'
                      emit '{"type":"result","subtype":"success","is_error":true,"num_turns":2,"result":"Prompt is too long"}'
                    }
                    """.formatted(PROMPT_TOO_LONG));

            DirectLlmClient.StreamResult result = client.streamChat("next", "system rules", null, null);
            assertTrue(result.isContextOverflow(), result.failureMessage);
            assertFalse(result.isReplaySafe(), "the tool already ran");
            assertTrue(client.claudeCompactionFailed());
        }
    }

    @Test
    void theLimitsClaudeCodeReportsBelongToTheModelTheTurnRan() throws Exception {
        try (DirectLlmClient client = client()) {
            installFakeClaude(client, """
                    turn() {
                      emit '{"type":"system","subtype":"init","session_id":"native-1","model":"claude-opus-5-5"}'
                      emit '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}},"parent_tool_use_id":null}'
                      emit '{"type":"result","subtype":"success","num_turns":1,"result":"done","modelUsage":{"claude-haiku-4-5-20251001":{"contextWindow":200000,"maxOutputTokens":64000},"claude-opus-5-5":{"contextWindow":400000,"maxOutputTokens":32000}}}'
                    }
                    """);
            assertNull(client.claudeReportedLimits(null), "nothing is reported before a turn");

            DirectLlmClient.StreamResult result = client.streamChat("next", "system rules", null, null);
            assertFalse(result.failed, result.failureMessage);
            assertEquals(new ModelContextResolver.ModelLimits(400_000, 32_000),
                    client.claudeReportedLimits(null));
            assertNull(client.claudeReportedLimits("claude-sonnet-5"),
                    "no turn has run another model");
        }
    }

    private DirectLlmClient client() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        // A retry replaces the transport, and a replaced transport runs the real claude.
        DirectLlmClient client = DirectLlmClient.withConnectivityPolicy(
                config, JsonUtils.standardMapper(),
                ProviderConnectivityPolicy.forProvider("anthropic").withMaxAttempts(1), home);
        client.setOutputConsumer(ignored -> { });
        return client;
    }

    /** Installs a fake {@code claude} whose turns run the given {@link FakeClaudeCode} overrides. */
    private void installFakeClaude(DirectLlmClient client, String overrides) throws Exception {
        FakeClaudeCode fake = new FakeClaudeCode(home.resolve("claude"), overrides);
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, new ClaudeCliClient(home, null, fake.binary()));
    }
}
