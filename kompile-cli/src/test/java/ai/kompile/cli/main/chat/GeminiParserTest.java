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
 * Unit tests for {@link PassthroughStreamParser#parseGeminiLine(String)}.
 * <p>
 * Covers Gemini/Qwen stream-json event types: init, message (assistant vs user),
 * tool_use, tool_result (skipped), result with stats, null/blank input, and
 * known noise lines that should be suppressed.
 */
class GeminiParserTest {

    // ===================================================================
    // Setup
    // ===================================================================

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // ===================================================================
    // Session init
    // ===================================================================

    @Test
    void initEvent_shouldProduceSessionInit() {
        String line = ParserTestFixtures.geminiInit("gemini-session-abc");
        PassthroughEvent event = parser.parseGeminiLine(line);

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
        PassthroughEvent event = parser.parseGeminiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Here is the answer.");
    }

    @Test
    void messageWithRoleUser_shouldReturnNull() {
        String line = "{\"type\":\"message\",\"role\":\"user\",\"content\":\"What is 2+2?\"}";
        PassthroughEvent event = parser.parseGeminiLine(line);

        assertNull(event, "User messages should be skipped");
    }

    // ===================================================================
    // Tool use
    // ===================================================================

    @Test
    void toolUseEvent_shouldProduceToolUseWithNameAndParams() {
        String line = ParserTestFixtures.geminiToolUse("search_web", "{\"query\":\"kompile docs\"}");
        PassthroughEvent event = parser.parseGeminiLine(line);

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
        PassthroughEvent event = parser.parseGeminiLine(line);

        assertNull(event, "tool_result events should be skipped");
    }

    // ===================================================================
    // Result with stats → TurnComplete
    // ===================================================================

    @Test
    void resultWithStats_shouldProduceTurnComplete() {
        String line = ParserTestFixtures.geminiResult(5000L, 3);
        PassthroughEvent event = parser.parseGeminiLine(line);

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
        PassthroughEvent event = parser.parseGeminiLine(line);

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(0, tc.numTurns());
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnNull() {
        assertNull(parser.parseGeminiLine(null));
    }

    @Test
    void blankInput_shouldReturnNull() {
        assertNull(parser.parseGeminiLine("   "));
    }

    // ===================================================================
    // Gemini noise lines — should be suppressed
    // ===================================================================

    @Test
    void yoloModeNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseGeminiLine("YOLO mode is enabled - skipping all confirmations");
        assertNull(event, "YOLO mode line should be suppressed");
    }

    @Test
    void retryNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseGeminiLine("Retrying after 5 seconds...");
        assertNull(event, "Retry noise line should be suppressed");
    }

    @Test
    void attemptFailedNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseGeminiLine("Attempt 2 failed: connection reset");
        assertNull(event, "Attempt failed noise line should be suppressed");
    }
}
