/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.PassthroughStreamParser.PassthroughEvent;
import ai.kompile.cli.main.chat.PassthroughStreamParser.SessionInit;
import ai.kompile.cli.main.chat.PassthroughStreamParser.ToolUse;
import ai.kompile.cli.main.chat.PassthroughStreamParser.TurnComplete;
import ai.kompile.cli.main.chat.testing.AgentOutputAssertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests for the Antigravity CLI ({@code agy}) stream-json protocol.
 * Fixtures mirror the real envelopes emitted by the installed CLI
 * ({@code agy -p <msg> --output-format stream-json}).
 */
class AgyParserTest {

    private final PassthroughStreamParser parser = new PassthroughStreamParser();

    // ===================================================================
    // Init → SessionInit
    // ===================================================================

    @Test
    void initEvent_shouldProduceSessionInit() {
        String line = "{\"event\":\"init\",\"conversation_id\":\"237f047d-00de-48fd\","
                + "\"init\":{\"cwd\":\"/repo\",\"tools\":[\"read_file\"],\"permission_mode\":\"request-review\"}}";
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("237f047d-00de-48fd");
    }

    @Test
    void initEventWithoutConversationId_shouldReturnNull() {
        String line = "{\"event\":\"init\",\"init\":{}}";
        assertNull(parser.parseAgyLine(line));
    }

    // ===================================================================
    // agent_response text deltas → TextChunk
    // ===================================================================

    @Test
    void agentResponseDelta_shouldProduceTextChunk() {
        String line = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c1\","
                + "\"step_index\":2,\"state\":\"ACTIVE\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\"apple\"}}";
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("apple");
    }

    @Test
    void agentResponseDoneWithTrailingNewline_shouldStreamTheDelta() {
        // The DONE agent step may carry a final text_delta (a trailing newline);
        // it is legitimate turn text, unlike its cumulative usage block.
        String line = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c1\","
                + "\"step_index\":2,\"state\":\"DONE\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\"\\n\",\"duration_seconds\":1.17,"
                + "\"usage\":{\"input_tokens\":30384,\"output_tokens\":4}}}";
        AgentOutputAssertions.assertThat(parser.parseAgyLine(line))
                .hasEventCount(1)
                .hasTextChunk("\n");
    }

    // ===================================================================
    // Tool steps → ToolUse on ACTIVE
    // ===================================================================

    @Test
    void toolStepActive_shouldProduceToolUseWithParameters() {
        String line = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c1\","
                + "\"step_index\":2,\"state\":\"ACTIVE\",\"step_type\":\"tool\","
                + "\"tool_name\":\"read_file\","
                + "\"tool_info\":{\"name\":\"read_file\",\"parameters\":{\"Path\":\"/etc/hostname\"}}}}";
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("read_file");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertTrue(toolUse.input().contains("Path"), "Parameters should be serialised into input");
    }

    @Test
    void toolStepDone_shouldReturnNull() {
        String line = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c1\","
                + "\"step_index\":2,\"state\":\"DONE\",\"step_type\":\"tool\",\"tool_name\":\"read_file\"}}";
        assertNull(parser.parseAgyLine(line));
    }

    @Test
    void userStep_shouldReturnNull() {
        String line = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"c1\","
                + "\"step_index\":0,\"state\":\"DONE\",\"step_type\":\"user_input\"}}";
        assertNull(parser.parseAgyLine(line));
    }

    // ===================================================================
    // Result → TurnComplete
    // ===================================================================

    @Test
    void result_shouldProduceTurnCompleteWithDisjointUsage() {
        // Real envelope: input_tokens already excludes cache_read_tokens
        String line = "{\"event\":\"result\",\"result\":{\"conversation_id\":\"c1\","
                + "\"status\":\"SUCCESS\",\"response\":\"PING\\n\",\"duration_seconds\":3.184096927,"
                + "\"num_turns\":1,"
                + "\"usage\":{\"input_tokens\":15259,\"output_tokens\":15,\"thinking_tokens\":0,"
                + "\"cache_read_tokens\":0,\"total_tokens\":15274}}}";
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTurnComplete();

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(3184L, tc.durationMs());
        assertEquals(1, tc.numTurns());
        assertEquals(15259L, tc.inputTokens());
        assertEquals(15L, tc.outputTokens());
        assertEquals(0L, tc.cacheReadTokens());
        assertEquals(0L, tc.cacheCreationTokens());
    }

    @Test
    void resultWithCacheRead_shouldKeepDisjointCounts() {
        String line = "{\"event\":\"result\",\"result\":{\"conversation_id\":\"c1\","
                + "\"status\":\"SUCCESS\",\"response\":\"apple\\n\",\"duration_seconds\":2.548755756,"
                + "\"num_turns\":2,"
                + "\"usage\":{\"input_tokens\":30662,\"output_tokens\":8,\"thinking_tokens\":0,"
                + "\"cache_read_tokens\":30214,\"total_tokens\":30670}}}";
        TurnComplete tc = AgentOutputAssertions.assertThat(parser.parseAgyLine(line))
                .firstOfType(TurnComplete.class);
        assertNotNull(tc);
        // Disjoint: no subtraction — agy's input_tokens already excludes the cached prefix
        assertEquals(30662L, tc.inputTokens());
        assertEquals(30214L, tc.cacheReadTokens());
    }

    @Test
    void resultWithNoUsage_shouldProduceTurnCompleteWithDefaults() {
        String line = "{\"event\":\"result\",\"result\":{\"conversation_id\":\"c1\","
                + "\"status\":\"SUCCESS\",\"response\":\"ok\",\"duration_seconds\":0,\"num_turns\":0}}";
        TurnComplete tc = AgentOutputAssertions.assertThat(parser.parseAgyLine(line))
                .firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(0L, tc.durationMs());
        assertEquals(0, tc.numTurns());
    }

    // ===================================================================
    // CLI slash command reports
    // ===================================================================

    @Test
    void commandResult_shouldReturnNull() {
        String line = "{\"event\":\"command_result\",\"command\":{\"name\":\"model\","
                + "\"data\":{\"id\":\"claude-opus-4-6-thinking\",\"label\":\"Claude Opus 4.6 (Thinking)\"}}}";
        assertNull(parser.parseAgyLine(line));
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnNull() {
        assertNull(parser.parseAgyLine(null));
    }

    @Test
    void blankInput_shouldReturnNull() {
        assertNull(parser.parseAgyLine("   "));
    }

    @Test
    void unknownFutureEvent_shouldReturnNull() {
        String line = "{\"event\":\"future_thing\",\"data\":{}}";
        assertNull(parser.parseAgyLine(line));
    }

    @Test
    void malformedJson_shouldFallBackToTextChunk() {
        // agy writes plain-text notices to stdout; only known noise patterns are
        // suppressed, so an arbitrary non-JSON line surfaces as text.
        AgentOutputAssertions.assertThat(parser.parseAgyLine("{not json"))
                .hasEventCount(1)
                .hasTextContaining("{not json");
    }

    // ===================================================================
    // Agy noise lines — should be suppressed
    // ===================================================================

    @Test
    void jetskiPermissionNotice_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine(
                "jetski: no output produced — a tool required the \"read_file\" permission"
                        + " that headless mode cannot prompt for, so it was auto-denied.");
        assertNull(event, "jetski permission notices should be suppressed");
    }

    @Test
    void yoloModeNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("YOLO mode is enabled - skipping all confirmations");
        assertNull(event, "YOLO mode line should be suppressed");
    }

    @Test
    void retryNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("Retrying after 5 seconds...");
        assertNull(event, "Retry noise line should be suppressed");
    }

    @Test
    void attemptFailedNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("Attempt 2 failed: connection reset");
        assertNull(event, "Attempt failed noise line should be suppressed");
    }

    // ===================================================================
    // Non-JSON text (not noise) → TextChunk fallback
    // ===================================================================

    @Test
    void nonJsonNonNoise_shouldProduceTextChunk() {
        PassthroughEvent event = parser.parseAgyLine("Some legitimate output text");

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("Some legitimate output text");
    }

    // ===================================================================
    // parseAgyLineMulti wrapper
    // ===================================================================

    @Test
    void parseAgyLineMulti_shouldWrapSingleEvent() {
        String line = "{\"event\":\"init\",\"conversation_id\":\"abc\"}";
        var events = parser.parseAgyLineMulti(line);
        assertEquals(1, events.size());
        assertInstanceOf(SessionInit.class, events.get(0));
    }

    @Test
    void parseAgyLineMulti_shouldReturnEmptyForNoise() {
        assertTrue(parser.parseAgyLineMulti("Retrying after 5 seconds...").isEmpty());
    }

    private static void assertInstanceOf(Class<?> expected, Object actual) {
        assertNotNull(actual);
        assertTrue(expected.isInstance(actual),
                "Expected " + expected.getSimpleName() + " but was "
                        + actual.getClass().getSimpleName());
    }
}
