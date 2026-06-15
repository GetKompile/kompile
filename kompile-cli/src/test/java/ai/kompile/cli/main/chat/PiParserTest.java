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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughStreamParser#parsePiLine(String)} and
 * {@link PassthroughStreamParser#parsePiLineMulti(String)}.
 * <p>
 * Covers Pi event types: session, message_update (text_delta), message_end (suppressed),
 * tool_execution_start/update/end with delta computation, and multi-variant delegation.
 */
class PiParserTest {

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
    void session_shouldProduceSessionInit() {
        String line = ParserTestFixtures.piSession("pi-session-111");
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("pi-session-111");
    }

    // ===================================================================
    // Text deltas
    // ===================================================================

    @Test
    void messageUpdateTextDelta_shouldProduceTextChunk() {
        String line = ParserTestFixtures.piTextDelta("msg-001", "Hello from Pi!");
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Hello from Pi!");
    }

    @Test
    void messageEnd_shouldReturnNull() {
        String line = ParserTestFixtures.piMessageEnd("msg-001", "Hello from Pi!");
        PassthroughEvent event = parser.parsePiLine(line);

        assertNull(event, "message_end should be suppressed to avoid duplicating deltas");
    }

    // ===================================================================
    // Text delta sequence: delta events emitted, message_end suppressed
    // ===================================================================

    @Test
    void textDeltaSequenceWithoutDuplicatingMessageEnd() {
        PassthroughEvent delta1 = parser.parsePiLine(ParserTestFixtures.piTextDelta("msg-002", "part one "));
        PassthroughEvent delta2 = parser.parsePiLine(ParserTestFixtures.piTextDelta("msg-002", "part two"));
        PassthroughEvent end    = parser.parsePiLine(ParserTestFixtures.piMessageEnd("msg-002", "part one part two"));

        assertNotNull(delta1, "First delta should produce a TextChunk");
        assertNotNull(delta2, "Second delta should produce a TextChunk");
        assertNull(end, "message_end must be suppressed");

        AgentOutputAssertions.assertThat(delta1).hasTextChunk("part one ");
        AgentOutputAssertions.assertThat(delta2).hasTextChunk("part two");
    }

    // ===================================================================
    // Tool execution
    // ===================================================================

    @Test
    void toolExecutionStart_shouldProduceToolUse() {
        String line = ParserTestFixtures.piToolStart("call-001", "read_file", "{\"path\":\"/etc/hosts\"}");
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("read_file");
    }

    @Test
    void toolExecutionUpdate_shouldProduceToolOutput() {
        // Prime tool call state
        parser.parsePiLine(ParserTestFixtures.piToolStart("call-002", "bash", "{}"));

        String line = ParserTestFixtures.piToolUpdate("call-002", "partial output so far");
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolOutput("partial output so far");
    }

    @Test
    void toolExecutionEnd_shouldProduceToolCompleteWithDelta() {
        // Simulate start + partial update so delta is only the new portion
        parser.parsePiLine(ParserTestFixtures.piToolStart("call-003", "bash", "{}"));
        parser.parsePiLine(ParserTestFixtures.piToolUpdate("call-003", "first line\n"));

        // Final output is "first line\nsecond line\n"; delta should be "second line\n"
        String line = ParserTestFixtures.piToolEnd("call-003", "bash", "first line\nsecond line\n", 0);
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolComplete("bash", 0);

        ToolComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(ToolComplete.class);
        assertNotNull(tc);
        // Delta = full minus already-streamed partial
        assertEquals("second line\n", tc.output());
        assertFalse(tc.error(), "Exit code 0 should mean error=false");
    }

    @Test
    void toolExecutionEnd_noPartialPriming_shouldUseFullOutputAsDelta() {
        parser.parsePiLine(ParserTestFixtures.piToolStart("call-004", "grep", "{}"));
        // No piToolUpdate — delta = full output
        String line = ParserTestFixtures.piToolEnd("call-004", "grep", "match line\n", 0);
        PassthroughEvent event = parser.parsePiLine(line);

        ToolComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(ToolComplete.class);
        assertNotNull(tc);
        assertEquals("match line\n", tc.output());
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnNull() {
        assertNull(parser.parsePiLine(null));
    }

    @Test
    void blankInput_shouldReturnNull() {
        assertNull(parser.parsePiLine("   "));
    }

    // ===================================================================
    // parsePiLineMulti — delegates to parsePiLine
    // ===================================================================

    @Test
    void multiVariant_delegatesToParsePiLine() {
        String line = ParserTestFixtures.piTextDelta("msg-010", "delegated text");
        List<PassthroughEvent> events = parser.parsePiLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTextChunk("delegated text");
    }

    @Test
    void multiVariant_nullInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parsePiLineMulti(null);
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    @Test
    void multiVariant_blankInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parsePiLineMulti("  ");
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    @Test
    void multiVariant_suppressedEvent_shouldReturnEmptyList() {
        // message_end is suppressed — multi should also return empty
        String line = ParserTestFixtures.piMessageEnd("msg-020", "done");
        List<PassthroughEvent> events = parser.parsePiLineMulti(line);
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    // ===================================================================
    // Non-JSON plain-text fallback
    // ===================================================================

    @Test
    void nonJsonText_shouldProduceTextChunk() {
        String line = ParserTestFixtures.piPlainText("Here is a plain text response from Pi.");
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("Here is a plain text response from Pi.");
    }

    @Test
    void nonJsonTextWithMarkdown_shouldPreserveFormatting() {
        String line = "**Bold** and `code` and [link](http://example.com)";
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("**Bold**");
    }

    @Test
    void nonJsonTextWithSpecialChars_shouldPreserve() {
        String line = "Error: file → not found (check /tmp/foo.txt)";
        PassthroughEvent event = parser.parsePiLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("file");
    }

    // ===================================================================
    // Tool execution with error exit code
    // ===================================================================

    @Test
    void toolExecutionEnd_withErrorExitCode_shouldHaveErrorFlag() {
        parser.parsePiLine(ParserTestFixtures.piToolStart("call-err-001", "bash", "{}"));
        String line = ParserTestFixtures.piToolEnd("call-err-001", "bash", "command not found\n", 127);
        PassthroughEvent event = parser.parsePiLine(line);

        ToolComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(ToolComplete.class);
        assertNotNull(tc);
        assertEquals(127, tc.exitCode());
        // exitCode != 0 should set error=true
        assertTrue(tc.error(), "Non-zero exit code should set error=true");
    }

    // ===================================================================
    // Multiple tool executions — independent delta tracking
    // ===================================================================

    @Test
    void multipleToolExecutions_shouldTrackDeltasIndependently() {
        // Start two tool calls
        parser.parsePiLine(ParserTestFixtures.piToolStart("tool-a", "bash", "{}"));
        parser.parsePiLine(ParserTestFixtures.piToolStart("tool-b", "grep", "{}"));

        // Update tool-a
        parser.parsePiLine(ParserTestFixtures.piToolUpdate("tool-a", "output-a-partial"));
        // Update tool-b
        parser.parsePiLine(ParserTestFixtures.piToolUpdate("tool-b", "output-b-partial"));

        // Complete tool-a with more output
        PassthroughEvent eventA = parser.parsePiLine(
                ParserTestFixtures.piToolEnd("tool-a", "bash", "output-a-partial-and-more", 0));
        // Complete tool-b with more output
        PassthroughEvent eventB = parser.parsePiLine(
                ParserTestFixtures.piToolEnd("tool-b", "grep", "output-b-partial-full", 0));

        ToolComplete tcA = AgentOutputAssertions.assertThat(eventA).firstOfType(ToolComplete.class);
        ToolComplete tcB = AgentOutputAssertions.assertThat(eventB).firstOfType(ToolComplete.class);

        assertNotNull(tcA);
        assertNotNull(tcB);
        // Deltas should only contain the new portion
        assertEquals("-and-more", tcA.output());
        assertEquals("-full", tcB.output());
    }

    // ===================================================================
    // Unknown JSON event type
    // ===================================================================

    @Test
    void unknownJsonEventType_shouldReturnNull() {
        String line = "{\"type\":\"heartbeat\",\"data\":\"ping\"}";
        PassthroughEvent event = parser.parsePiLine(line);
        assertNull(event, "Unknown JSON event types should be ignored");
    }
}
