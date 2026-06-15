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
 * Unit tests for {@link PassthroughStreamParser#parseOpenCodeLine(String)} and
 * {@link PassthroughStreamParser#parseOpenCodeLineMulti(String)}.
 * <p>
 * Covers OpenCode {@code --format json} event types: step_start, text, tool_use,
 * step_finish (with and without tokens), and multi-event behaviour for completed tool_use.
 */
class OpenCodeParserTest {

    // ===================================================================
    // Setup
    // ===================================================================

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // ===================================================================
    // parseOpenCodeLine — session init
    // ===================================================================

    @Test
    void stepStart_shouldProduceSessionInit() {
        String line = ParserTestFixtures.openCodeStepStart("oc-session-abc");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("oc-session-abc");
    }

    // ===================================================================
    // parseOpenCodeLine — text
    // ===================================================================

    @Test
    void textEvent_shouldProduceTextChunk() {
        String line = ParserTestFixtures.openCodeText("Thinking about your question...");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Thinking about your question...");
    }

    // ===================================================================
    // parseOpenCodeLine — tool_use (single event)
    // ===================================================================

    @Test
    void toolUseEvent_shouldProduceToolUse() {
        String line = ParserTestFixtures.openCodeToolUse("bash", "cat /etc/hosts", "127.0.0.1 localhost\n", 0);
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("bash");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("bash", toolUse.name());
        assertEquals("cat /etc/hosts", toolUse.input());
    }

    // ===================================================================
    // parseOpenCodeLine — step_finish with tokens → TokenUsage
    // ===================================================================

    @Test
    void stepFinishWithTokens_shouldProduceTokenUsage() {
        String line = ParserTestFixtures.openCodeStepFinish(200L, 80L, 40L, 10L);
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTokenUsageWithCache(200L, 80L, 40L, 10L);
    }

    // ===================================================================
    // parseOpenCodeLine — step_finish without tokens → TurnComplete
    // ===================================================================

    @Test
    void stepFinishWithoutTokens_shouldProduceTurnComplete() {
        // step_finish with cost but no tokens node
        String line = "{\"type\":\"step_finish\",\"part\":{\"cost\":0.0025}}";
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTurnComplete();

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(0.0025, tc.costUsd(), 1e-9);
    }

    // ===================================================================
    // parseOpenCodeLine — edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnNull() {
        assertNull(parser.parseOpenCodeLine(null));
    }

    @Test
    void blankInput_shouldReturnNull() {
        assertNull(parser.parseOpenCodeLine("   "));
    }

    // ===================================================================
    // parseOpenCodeLineMulti — tool_use with status=completed → [ToolUse, ToolComplete]
    // ===================================================================

    @Test
    void multiToolUseCompleted_shouldProduceTwoEvents() {
        String line = ParserTestFixtures.openCodeToolUse("bash", "ls /tmp", "file1\nfile2\n", 0);
        List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(2)
                .firstEventIs(ToolUse.class)
                .lastEventIs(ToolComplete.class)
                .hasToolUse("bash")
                .hasToolComplete("bash", 0);
    }

    // ===================================================================
    // parseOpenCodeLineMulti — step_finish with cache tokens → [TokenUsage]
    // ===================================================================

    @Test
    void multiStepFinishWithCacheTokens_shouldProduceSingleTokenUsage() {
        String line = ParserTestFixtures.openCodeStepFinish(500L, 120L, 80L, 30L);
        List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTokenUsageWithCache(500L, 120L, 80L, 30L);

        TokenUsage tu = AgentOutputAssertions.assertThat(events).firstOfType(TokenUsage.class);
        assertNotNull(tu);
        assertEquals(500L, tu.inputTokens());
        assertEquals(120L, tu.outputTokens());
        assertEquals(80L, tu.cacheReadTokens());
        assertEquals(30L, tu.cacheCreationTokens());
    }

    // ===================================================================
    // parseOpenCodeLineMulti — text → [TextChunk]
    // ===================================================================

    @Test
    void multiTextEvent_shouldProduceSingleTextChunk() {
        String line = ParserTestFixtures.openCodeText("Multi-variant text output.");
        List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

        AgentOutputAssertions.assertThat(events)
                .hasEventCount(1)
                .hasTextChunk("Multi-variant text output.");
    }

    // ===================================================================
    // parseOpenCodeLine — reasoning → ThinkingChunk
    // ===================================================================

    @Test
    void reasoningEvent_shouldProduceThinkingChunk() {
        String line = ParserTestFixtures.openCodeReasoning("Thinking about the best approach...");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasThinkingChunk("Thinking about the best approach...");
    }

    @Test
    void reasoningEvent_emptyText_shouldProduceThinkingChunk() {
        String line = ParserTestFixtures.openCodeReasoning("");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasThinkingChunk("");
    }

    @Test
    void reasoningEvent_longText_shouldPreserveContent() {
        String longText = "Step 1: Read the configuration. Step 2: Validate. Step 3: Apply changes.";
        String line = ParserTestFixtures.openCodeReasoning(longText);
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasThinkingChunk(longText);
    }

    // ===================================================================
    // parseOpenCodeLine — ask_user tool → InteractiveQuestion
    // ===================================================================

    @Test
    void askUserTool_shouldProduceInteractiveQuestion() {
        String line = ParserTestFixtures.openCodeAskUser(
                "oc-call-001", "Which file should I edit?", "foo.java", "bar.java");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveQuestion("Which file should I edit?");

        InteractiveQuestion iq = AgentOutputAssertions.assertThat(event)
                .firstOfType(InteractiveQuestion.class);
        assertNotNull(iq);
        assertEquals("oc-call-001", iq.callId());
        assertEquals(2, iq.options().size());
        assertEquals("foo.java", iq.options().get(0).label());
        assertEquals("bar.java", iq.options().get(1).label());
    }

    @Test
    void askUserTool_noOptions_shouldProduceQuestionOnly() {
        String line = ParserTestFixtures.openCodeAskUser("oc-call-002", "What should I do next?");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveQuestion("What should I do next?");

        InteractiveQuestion iq = AgentOutputAssertions.assertThat(event)
                .firstOfType(InteractiveQuestion.class);
        assertNotNull(iq);
        assertTrue(iq.options().isEmpty());
        assertTrue(iq.freeformAllowed());
    }

    @Test
    void askUserQuestion_withTextField_shouldUseTextAsQuestion() {
        String line = ParserTestFixtures.openCodeAskUserWithText(
                "oc-call-003", "Do you want to continue?");
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveQuestion("Do you want to continue?");
    }

    // ===================================================================
    // parseOpenCodeLine — tool_use with description in input
    // ===================================================================

    @Test
    void toolUseWithDescription_shouldUseDescription() {
        String line = "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"tool\":\"edit\","
                + "\"state\":{\"input\":{\"description\":\"Fixing the bug in line 42\"}}}}";
        PassthroughEvent event = parser.parseOpenCodeLine(line);

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("edit", toolUse.name());
        assertEquals("Fixing the bug in line 42", toolUse.input());
    }

    // ===================================================================
    // parseOpenCodeLine — non-JSON noise should return null
    // ===================================================================

    @Test
    void nonJsonNoiseLine_shouldReturnNull() {
        PassthroughEvent event = parser.parseOpenCodeLine("⣿ Thinking...");
        assertNull(event, "TUI spinner noise should be suppressed");
    }

    // ===================================================================
    // parseOpenCodeLineMulti — edge cases
    // ===================================================================

    @Test
    void multiNullInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(null);
        AgentOutputAssertions.assertThat(events).isEmpty();
    }

    @Test
    void multiBlankInput_shouldReturnEmptyList() {
        List<PassthroughEvent> events = parser.parseOpenCodeLineMulti("  ");
        AgentOutputAssertions.assertThat(events).isEmpty();
    }
}
