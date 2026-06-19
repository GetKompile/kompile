/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.PassthroughStreamParser.*;
import ai.kompile.cli.main.chat.testing.AgentOutputAssertions;
import ai.kompile.cli.main.chat.testing.ParserTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughStreamParser#parseAgyLine(String)}.
 * <p>
 * Covers Agy/Qwen stream-json event types: init, message (assistant vs user),
 * tool_use, tool_result (skipped), result with stats, null/blank input, and
 * known noise lines that should be suppressed.
 */
class AgyParserTest {

    // ===================================================================
    // Setup
    // ===================================================================

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // ===================================================================
    // Setup / helper methods could go here
    // ===================================================================

    @Test
    void initEvent_shouldProduceSessionInit() {
        String line = ParserTestFixtures.geminiInit("gemini-session-abc");
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("gemini-session-abc");
    }

    // ===================================================================
    // Messages
    // ===================================================================

    @Test
    void messageWithRoleAssistant_shouldProduceTextChunk() {
        String line = ParserTestFixtures.geminiMessage("Here is the answer.");
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Here is the answer.");
    }

    @Test
    void messageWithRoleUser_shouldReturnNull() {
        String line = "{\"type\":\"message\",\"role\":\"user\",\"content\":\"What is 2+2?\"}";
        PassthroughEvent event = parser.parseAgyLine(line);

        assertNull(event, "User messages should be skipped");
    }

    // ===================================================================
    // Tool use
    // ===================================================================

    @Test
    void toolUseEvent_shouldProduceToolUseWithNameAndParams() {
        String line = ParserTestFixtures.geminiToolUse("search_web", "{\"query\":\"kompile docs\"}");
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("search_web");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("search_web", toolUse.name());
        assertTrue(toolUse.input().contains("query"), "Parameters should be serialised into input");
    }

    // ===================================================================
    // Tool result (skipped)
    // ===================================================================

    @Test
    void toolResult_shouldReturnNull() {
        String line = "{\"type\":\"tool_result\",\"tool_name\":\"search_web\",\"output\":\"some result\"}";
        PassthroughEvent event = parser.parseAgyLine(line);

        assertNull(event, "tool_result events should be skipped");
    }

    // ===================================================================
    // Result with stats → TurnComplete
    // ===================================================================

    @Test
    void resultWithStats_shouldProduceTurnComplete() {
        String line = ParserTestFixtures.geminiResult(5000L, 3);
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTurnComplete();

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(5000L, tc.durationMs());
        // numTurns is 1 when tool_calls > 0
        assertEquals(1, tc.numTurns());
    }

    @Test
    void resultWithZeroToolCalls_shouldProduceTurnCompleteWithNumTurnsZero() {
        String line = ParserTestFixtures.geminiResult(3000L, 0);
        PassthroughEvent event = parser.parseAgyLine(line);

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(0, tc.numTurns());
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

    // ===================================================================
    // Agy noise lines — should be suppressed
    // ===================================================================

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
    // Result with no tool_calls → numTurns=0
    // ===================================================================

    @Test
    void resultWithNoStats_shouldProduceTurnCompleteWithDefaults() {
        String line = "{\"type\":\"result\"}";
        PassthroughEvent event = parser.parseAgyLine(line);

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(0L, tc.durationMs());
        assertEquals(0, tc.numTurns());
    }

    // ===================================================================
    // Tool use with complex parameters
    // ===================================================================

    @Test
    void toolUseWithNestedParams_shouldPreserveJson() {
        String line = ParserTestFixtures.geminiToolUse("edit_file",
                "{\"path\":\"/tmp/test.java\",\"changes\":[{\"line\":5,\"text\":\"new content\"}]}");
        PassthroughEvent event = parser.parseAgyLine(line);

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("edit_file", toolUse.name());
        assertTrue(toolUse.input().contains("path"));
        assertTrue(toolUse.input().contains("changes"));
    }

    // ===================================================================
    // Message with empty content
    // ===================================================================

    @Test
    void messageWithEmptyContent_shouldProduceTextChunk() {
        String line = "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"\"}";
        PassthroughEvent event = parser.parseAgyLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("");
    }

    // ===================================================================
    // Noise lines — more patterns
    // ===================================================================

    @Test
    void yoloModeVariant_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("YOLO mode is enabled");
        assertNull(event, "YOLO mode variant should be suppressed");
    }

    @Test
    void retryingVariant_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("Retrying after 30 seconds...");
        assertNull(event, "Retry with different interval should be suppressed");
    }

    @Test
    void attemptVariant_shouldReturnNull() {
        PassthroughEvent event = parser.parseAgyLine("Attempt 5 failed: rate limited");
        assertNull(event, "Higher attempt number should still be suppressed");
    }
}
