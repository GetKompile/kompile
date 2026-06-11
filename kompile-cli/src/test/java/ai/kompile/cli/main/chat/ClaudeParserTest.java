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
 * Unit tests for {@link PassthroughStreamParser#parseClaudeLineMulti(String)}.
 * <p>
 * Covers the full range of Claude stream-json events: session init, text chunks,
 * tool_use blocks, result events, content-block deltas, and edge cases.
 */
class ClaudeParserTest {

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
    void systemEvent_shouldProduceSessionInit() {
        String line = ParserTestFixtures.claudeSystemEvent("session-abc-123");
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasSessionInit("session-abc-123");
    }

    // ===================================================================
    // Text chunks
    // ===================================================================

    @Test
    void assistantTextEvent_shouldProduceTextChunk() {
        String line = ParserTestFixtures.claudeTextEvent("Hello, world!");
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTextChunk("Hello, world!");
    }

    @Test
    void contentBlockDelta_shouldProduceTextChunk() {
        String line = ParserTestFixtures.claudeContentBlockDelta("incremental text");
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTextChunk("incremental text");
    }

    // ===================================================================
    // Tool use
    // ===================================================================

    @Test
    void assistantToolUseEvent_shouldProduceToolUse() {
        String line = ParserTestFixtures.claudeToolUseEvent("read_file", "{\"path\":\"/tmp/test.txt\"}");
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasToolUse("read_file");

        ToolUse toolUse = AgentOutputAssertions.assertThat(events).firstOfType(ToolUse.class);
        assertNotNull(toolUse, "Expected a ToolUse event");
        assertEquals("read_file", toolUse.name());
        assertTrue(toolUse.input().contains("path"), "Input should contain 'path'");
    }

    // ===================================================================
    // Result / TurnComplete
    // ===================================================================

    @Test
    void resultEvent_shouldProduceTurnComplete() {
        String line = ParserTestFixtures.claudeResultEvent(12345L, 0.0042);
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTurnComplete();

        TurnComplete tc = AgentOutputAssertions.assertThat(events).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(12345L, tc.durationMs());
        assertEquals(0.0042, tc.costUsd(), 1e-9);
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(null);
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    @Test
    void blankInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parseClaudeLineMulti("   ");
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    @Test
    void nonJsonText_shouldProduceTextChunkFallback() {
        String line = "This is not JSON at all.";
        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTextChunk("This is not JSON at all.");
    }

    // ===================================================================
    // Mixed text + tool_use in a single assistant message
    // ===================================================================

    @Test
    void mixedTextAndToolUseInOneMessage_shouldProduceBothEvents() {
        // Build a raw JSON assistant message with both a text block and a tool_use block
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"Let me read that file.\"},"
                + "{\"type\":\"tool_use\",\"name\":\"read_file\",\"input\":{\"path\":\"/tmp/x.txt\"}}"
                + "]}}";

        List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(2)
                .firstEventIs(TextChunk.class)
                .lastEventIs(ToolUse.class)
                .hasTextChunk("Let me read that file.")
                .hasToolUse("read_file");
    }
}
