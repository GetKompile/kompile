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
 * Unit tests for {@link PassthroughStreamParser#parseCodexLine(String)}.
 * <p>
 * Covers all Codex JSONL event types: thread.started, message.delta,
 * message.completed, item.started/updated/completed, turn.completed,
 * exec.started, error, turn.failed, envelope form, and noise suppression.
 */
class CodexParserTest {

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
    void threadStarted_shouldProduceSessionInit() {
        String line = ParserTestFixtures.codexThreadStarted("thread-xyz-789");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("thread-xyz-789");
    }

    // ===================================================================
    // Text events
    // ===================================================================

    @Test
    void messageDelta_shouldProduceTextChunk() {
        String line = ParserTestFixtures.codexMessageDelta("hello delta");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("hello delta");
    }

    @Test
    void messageCompleted_shouldProduceTextChunk() {
        String line = "{\"type\":\"message.completed\",\"content\":\"full message text\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("full message text");
    }

    // ===================================================================
    // Tool execution via item.*
    // ===================================================================

    @Test
    void itemStartedCommandExecution_shouldProduceToolUse() {
        String line = ParserTestFixtures.codexItemStarted("item-001", "ls -la /tmp");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("exec");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("ls -la /tmp", toolUse.input());
    }

    @Test
    void itemUpdatedAggregatedOutput_shouldProduceToolOutput() {
        // Prime the parser with item.started so it has a baseline of ""
        parser.parseCodexLine(ParserTestFixtures.codexItemStarted("item-002", "echo hi"));

        String line = ParserTestFixtures.codexItemUpdated("item-002", "hi\n");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolOutput("hi\n");
    }

    @Test
    void itemCompletedCommandExecution_shouldProduceToolComplete() {
        // Prime baseline so delta = full output
        parser.parseCodexLine(ParserTestFixtures.codexItemStarted("item-003", "echo done"));

        String line = ParserTestFixtures.codexItemCompletedCommand("item-003", "done\n", 0);
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolComplete("exec", 0);
    }

    @Test
    void itemCompletedAgentMessage_shouldProduceTextChunk() {
        String line = ParserTestFixtures.codexAgentMessage("item-004", "Task complete.");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("Task complete.");
    }

    @Test
    void itemUpdatedAgentMessage_shouldProduceOnlyNewTextDelta() {
        parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"id\":\"msg-001\",\"type\":\"agent_message\",\"text\":\"\"}}");

        PassthroughEvent first = parser.parseCodexLine(
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg-001\",\"type\":\"agent_message\",\"text\":\"Hello\"}}");
        PassthroughEvent second = parser.parseCodexLine(
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg-001\",\"type\":\"agent_message\",\"text\":\"Hello world\"}}");

        AgentOutputAssertions.assertThat(first)
                .hasEventCount(1)
                .hasTextChunk("Hello");
        AgentOutputAssertions.assertThat(second)
                .hasEventCount(1)
                .hasTextChunk(" world");
    }

    @Test
    void itemCompletedAgentMessage_afterUpdates_shouldNotDuplicateStreamedText() {
        parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"id\":\"msg-002\",\"type\":\"agent_message\",\"text\":\"\"}}");
        parser.parseCodexLine("{\"type\":\"item.updated\",\"item\":{\"id\":\"msg-002\",\"type\":\"agent_message\",\"text\":\"Done\"}}");

        PassthroughEvent event = parser.parseCodexLine(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"msg-002\",\"type\":\"agent_message\",\"text\":\"Done\"}}");

        assertNull(event, "Completion should not repeat text already streamed by item.updated");
    }

    @Test
    void responseItemWithContentArray_shouldProduceTextChunk() {
        String line = "{\"type\":\"response_item\",\"payload\":{\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"from content array\"}]}}";

        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("from content array");
    }

    // ===================================================================
    // Turn completed with usage
    // ===================================================================

    @Test
    void turnCompleted_shouldProduceTurnCompleteWithTokens() {
        String line = ParserTestFixtures.codexTurnCompleted(100L, 50L, 20L);
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTurnComplete();

        TurnComplete tc = AgentOutputAssertions.assertThat(event).firstOfType(TurnComplete.class);
        assertNotNull(tc);
        assertEquals(100L, tc.inputTokens());
        assertEquals(50L, tc.outputTokens());
        assertEquals(20L, tc.cacheReadTokens());
    }

    // ===================================================================
    // exec.started (legacy Codex format)
    // ===================================================================

    @Test
    void execStarted_shouldProduceToolUse() {
        String line = "{\"type\":\"exec.started\",\"command\":\"git status\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("exec");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertEquals("git status", toolUse.input());
    }

    // ===================================================================
    // Error events
    // ===================================================================

    @Test
    void errorEvent_shouldProduceTextChunkWithPrefix() {
        String line = "{\"type\":\"error\",\"message\":\"something went wrong\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("[Error]")
                .hasTextContaining("something went wrong");
    }

    @Test
    void turnFailed_shouldProduceTextChunkWithPrefix() {
        String line = "{\"type\":\"turn.failed\",\"error\":{\"message\":\"API timeout\"}}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("[Error]")
                .hasTextContaining("API timeout");
    }

    // ===================================================================
    // Noise suppression
    // ===================================================================

    @Test
    void readingPromptFromStdin_shouldReturnNull() {
        PassthroughEvent event = parser.parseCodexLine("Reading prompt from stdin...");
        assertNull(event, "Should suppress 'Reading prompt from stdin...'");
    }

    @Test
    void readingAdditionalInputFromStdin_shouldReturnNull() {
        String line = ParserTestFixtures.codexStdinNotice();
        PassthroughEvent event = parser.parseCodexLine(line);
        assertNull(event, "Should suppress 'Reading additional input from stdin...'");
    }

    // ===================================================================
    // Envelope form
    // ===================================================================

    @Test
    void envelopeForm_shouldUnwrapAndParse() {
        // {"id":"evt-1","msg":{"type":"thread.started","thread_id":"thread-env-001"}}
        String line = "{\"id\":\"evt-1\",\"msg\":{\"type\":\"thread.started\",\"thread_id\":\"thread-env-001\"}}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasSessionInit("thread-env-001");
    }

    // ===================================================================
    // exec_approval_request — interactive approval
    // ===================================================================

    @Test
    void execApprovalRequest_shouldProduceInteractiveApproval() {
        String line = ParserTestFixtures.codexExecApproval(
                "call-100", "turn-200", "rm -rf /tmp/old-builds",
                "/home/user/project", "Need to clean up old build artifacts");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveApproval("rm -rf /tmp/old-builds");

        InteractiveApproval approval = AgentOutputAssertions.assertThat(event)
                .firstOfType(InteractiveApproval.class);
        assertNotNull(approval);
        assertEquals("call-100", approval.callId());
        assertEquals("turn-200", approval.turnId());
        assertEquals("rm -rf /tmp/old-builds", approval.command());
        assertEquals("/home/user/project", approval.cwd());
        assertEquals("Need to clean up old build artifacts", approval.reason());
        assertEquals(2, approval.decisions().size());
        assertTrue(approval.decisions().contains("approve"));
        assertTrue(approval.decisions().contains("deny"));
    }

    @Test
    void execApprovalRequest_commandAsArray_shouldJoinParts() {
        String line = ParserTestFixtures.codexExecApprovalArrayCommand(
                "call-101", "git", "push", "origin", "main");
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveApproval("git push origin main");
    }

    @Test
    void execApprovalRequest_noDecisions_shouldDefaultToApproveAndDeny() {
        // Minimal approval request with no available_decisions field
        String line = "{\"type\":\"exec_approval_request\",\"call_id\":\"call-102\",\"command\":\"ls\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        InteractiveApproval approval = AgentOutputAssertions.assertThat(event)
                .firstOfType(InteractiveApproval.class);
        assertNotNull(approval);
        assertEquals(2, approval.decisions().size());
        assertTrue(approval.decisions().contains("approve"));
        assertTrue(approval.decisions().contains("deny"));
    }

    // ===================================================================
    // request_user_input — interactive question
    // ===================================================================

    @Test
    void requestUserInput_shouldProduceInteractiveQuestion() {
        String line = ParserTestFixtures.codexRequestUserInput(
                "call-200", "turn-300", "q-001", "Configuration",
                "Which environment?",
                new String[][]{{"production", "Live system"}, {"staging", "Test environment"}},
                false);
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasInteractiveQuestion("Which environment?");

        InteractiveQuestion iq = AgentOutputAssertions.assertThat(event)
                .firstOfType(InteractiveQuestion.class);
        assertNotNull(iq);
        assertEquals("call-200", iq.callId());
        assertEquals("turn-300", iq.turnId());
        assertEquals("q-001", iq.questionId());
        assertEquals("Configuration", iq.header());
        assertEquals("Which environment?", iq.question());
        assertEquals(2, iq.options().size());
        assertEquals("production", iq.options().get(0).label());
        assertEquals("Live system", iq.options().get(0).description());
        assertFalse(iq.freeformAllowed());
    }

    @Test
    void requestUserInput_noQuestions_shouldReturnNull() {
        String line = "{\"type\":\"request_user_input\",\"call_id\":\"call-201\",\"questions\":[]}";
        PassthroughEvent event = parser.parseCodexLine(line);
        assertNull(event, "Empty questions array should produce null");
    }

    // ===================================================================
    // exec.started with args array instead of command string
    // ===================================================================

    @Test
    void execStartedWithArgs_shouldProduceToolUse() {
        String line = "{\"type\":\"exec.started\",\"args\":[\"npm\",\"install\"]}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasToolUse("exec");

        ToolUse toolUse = AgentOutputAssertions.assertThat(event).firstOfType(ToolUse.class);
        assertNotNull(toolUse);
        assertTrue(toolUse.input().contains("npm"), "Should contain args");
    }

    // ===================================================================
    // turn.started — should return null
    // ===================================================================

    @Test
    void turnStarted_shouldReturnNull() {
        String line = "{\"type\":\"turn.started\"}";
        PassthroughEvent event = parser.parseCodexLine(line);
        assertNull(event, "turn.started should be silently skipped");
    }

    // ===================================================================
    // response.output_text.delta — should produce TextChunk
    // ===================================================================

    @Test
    void responseOutputTextDelta_shouldProduceTextChunk() {
        String line = "{\"type\":\"response.output_text.delta\",\"delta\":\"streaming text\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("streaming text");
    }

    // ===================================================================
    // agent_message_delta — should produce TextChunk
    // ===================================================================

    @Test
    void agentMessageDelta_shouldProduceTextChunk() {
        String line = "{\"type\":\"agent_message_delta\",\"delta\":\"agent text\"}";
        PassthroughEvent event = parser.parseCodexLine(line);

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextChunk("agent text");
    }

    // ===================================================================
    // Non-JSON text (not noise) → TextChunk
    // ===================================================================

    @Test
    void nonJsonText_shouldProduceTextChunk() {
        PassthroughEvent event = parser.parseCodexLine("Some plain text output");

        AgentOutputAssertions.assertThat(event)
                .hasEventCount(1)
                .hasTextContaining("Some plain text output");
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    void nullInput_shouldReturnNull() {
        assertNull(parser.parseCodexLine(null));
    }

    @Test
    void blankInput_shouldReturnNull() {
        assertNull(parser.parseCodexLine("   "));
    }
}
