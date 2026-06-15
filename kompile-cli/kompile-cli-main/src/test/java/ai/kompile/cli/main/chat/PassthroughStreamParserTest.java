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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PassthroughStreamParser}.
 * <p>
 * Tests all 6 agent parsers (Claude, Gemini, Codex, OpenCode, Pi, plain text),
 * 12+ event types, multi-event parsing, interactive questions, approvals,
 * and edge cases (null, empty, invalid JSON).
 */
class PassthroughStreamParserTest {

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // ===================================================================
    // Claude Code parser — parseClaudeLineMulti
    // ===================================================================

    @Nested
    class ClaudeParser {

        @Test
        void nullLine_returnsEmpty() {
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(null);
            assertTrue(events.isEmpty());
        }

        @Test
        void blankLine_returnsEmpty() {
            List<PassthroughEvent> events = parser.parseClaudeLineMulti("   ");
            assertTrue(events.isEmpty());
        }

        @Test
        void systemEvent_withSessionId() {
            String line = "{\"type\":\"system\",\"session_id\":\"sess-abc-123\"}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(SessionInit.class, events.get(0));
            assertEquals("sess-abc-123", ((SessionInit) events.get(0)).sessionId());
        }

        @Test
        void systemEvent_withoutSessionId() {
            String line = "{\"type\":\"system\",\"status\":\"ready\"}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);
            assertTrue(events.isEmpty());
        }

        @Test
        void assistantTextBlock() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":" +
                    "[{\"type\":\"text\",\"text\":\"Here is the analysis of your code.\"}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
            assertEquals("Here is the analysis of your code.", ((TextChunk) events.get(0)).text());
        }

        @Test
        void assistantToolUseBlock() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":" +
                    "[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls -la\"}}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(ToolUse.class, events.get(0));
            assertEquals("Bash", ((ToolUse) events.get(0)).name());
            assertTrue(((ToolUse) events.get(0)).input().contains("ls -la"));
        }

        @Test
        void assistantMixedTextAndToolUse() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"I'll run this command:\"}," +
                    "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"git status\"}}," +
                    "{\"type\":\"text\",\"text\":\"Let me check the results.\"}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(3, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
            assertEquals("I'll run this command:", ((TextChunk) events.get(0)).text());
            assertInstanceOf(ToolUse.class, events.get(1));
            assertEquals("Bash", ((ToolUse) events.get(1)).name());
            assertInstanceOf(TextChunk.class, events.get(2));
            assertEquals("Let me check the results.", ((TextChunk) events.get(2)).text());
        }

        @Test
        void assistantAskUserQuestion() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"tool_use\",\"id\":\"call-42\",\"name\":\"AskUserQuestion\"," +
                    "\"input\":{\"question\":\"Which file?\",\"options\":[" +
                    "{\"label\":\"File A\",\"description\":\"The main file\"}," +
                    "{\"label\":\"File B\",\"description\":\"The test file\"}]}}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(InteractiveQuestion.class, events.get(0));
            InteractiveQuestion q = (InteractiveQuestion) events.get(0);
            assertEquals("call-42", q.callId());
            assertEquals("Which file?", q.question());
            assertEquals(2, q.options().size());
            assertEquals("File A", q.options().get(0).label());
            assertEquals("The test file", q.options().get(1).description());
        }

        @Test
        void askUserQuestionWithQuestionsArray() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"tool_use\",\"id\":\"call-99\",\"name\":\"AskUserQuestion\"," +
                    "\"input\":{\"questions\":[{\"question\":\"Pick env\",\"header\":\"Environment\"," +
                    "\"options\":[{\"label\":\"prod\"},{\"label\":\"staging\"}]}]}}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            InteractiveQuestion q = (InteractiveQuestion) events.get(0);
            assertEquals("Pick env", q.question());
            assertEquals("Environment", q.header());
            assertEquals(2, q.options().size());
        }

        @Test
        void askUserQuestion_noInput() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"tool_use\",\"id\":\"call-0\",\"name\":\"AskUserQuestion\"}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            InteractiveQuestion q = (InteractiveQuestion) events.get(0);
            assertEquals("The agent is requesting input", q.question());
        }

        @Test
        void resultEvent() {
            String line = "{\"type\":\"result\",\"duration_ms\":5432,\"cost_usd\":0.015,\"num_turns\":3}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TurnComplete.class, events.get(0));
            TurnComplete tc = (TurnComplete) events.get(0);
            assertEquals(5432, tc.durationMs());
            assertEquals(0.015, tc.costUsd(), 0.001);
            assertEquals(3, tc.numTurns());
        }

        @Test
        void contentBlockDelta() {
            String line = "{\"type\":\"assistant\",\"content_block\":{\"text\":\"streaming chunk\"}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
            assertEquals("streaming chunk", ((TextChunk) events.get(0)).text());
        }

        @Test
        void unknownType_withContentBlock() {
            String line = "{\"type\":\"delta\",\"content_block\":{\"text\":\"some text\"}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
        }

        @Test
        void invalidJson_treatedAsPlainText() {
            String line = "This is not JSON at all";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
            assertEquals("This is not JSON at all", ((TextChunk) events.get(0)).text());
        }

        @Test
        void textThenAskUser_bothEmitted() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[" +
                    "{\"type\":\"text\",\"text\":\"I need some info.\"}," +
                    "{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"AskUserQuestion\"," +
                    "\"input\":{\"question\":\"Continue?\"}}]}}";
            List<PassthroughEvent> events = parser.parseClaudeLineMulti(line);

            assertEquals(2, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
            assertInstanceOf(InteractiveQuestion.class, events.get(1));
        }
    }

    // ===================================================================
    // OpenCode parser — parseOpenCodeLine / parseOpenCodeLineMulti
    // ===================================================================

    @Nested
    class OpenCodeParser {

        @Test
        void nullLine_returnsNull() {
            assertNull(parser.parseOpenCodeLine(null));
        }

        @Test
        void blankLine_returnsNull() {
            assertNull(parser.parseOpenCodeLine("  "));
        }

        @Test
        void stepStart_sessionInit() {
            String line = "{\"type\":\"step_start\",\"sessionID\":\"oc-session-1\"}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(SessionInit.class, event);
            assertEquals("oc-session-1", ((SessionInit) event).sessionId());
        }

        @Test
        void textEvent() {
            String line = "{\"type\":\"text\",\"part\":{\"text\":\"Here is the code fix.\"}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Here is the code fix.", ((TextChunk) event).text());
        }

        @Test
        void reasoningEvent() {
            String line = "{\"type\":\"reasoning\",\"part\":{\"text\":\"Let me think about this...\"}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(ThinkingChunk.class, event);
            assertEquals("Let me think about this...", ((ThinkingChunk) event).text());
        }

        @Test
        void toolUseEvent_withDescription() {
            String line = "{\"type\":\"tool_use\",\"part\":{\"tool\":\"Read\"," +
                    "\"state\":{\"input\":{\"description\":\"Reading main.py\"}}}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("Read", ((ToolUse) event).name());
            assertEquals("Reading main.py", ((ToolUse) event).input());
        }

        @Test
        void toolUseEvent_withCommand() {
            String line = "{\"type\":\"tool_use\",\"part\":{\"tool\":\"Bash\"," +
                    "\"state\":{\"input\":{\"command\":\"npm test\"}}}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("Bash", ((ToolUse) event).name());
            assertEquals("npm test", ((ToolUse) event).input());
        }

        @Test
        void stepFinish_withTokens() {
            String line = "{\"type\":\"step_finish\",\"part\":{\"tokens\":{" +
                    "\"input\":1500,\"output\":300,\"cache\":{\"read\":200,\"write\":50}}}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(TokenUsage.class, event);
            TokenUsage tu = (TokenUsage) event;
            assertEquals(1500, tu.inputTokens());
            assertEquals(300, tu.outputTokens());
            assertEquals(200, tu.cacheReadTokens());
            assertEquals(50, tu.cacheCreationTokens());
        }

        @Test
        void stepFinish_withCostOnly() {
            String line = "{\"type\":\"step_finish\",\"part\":{\"cost\":0.025}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(TurnComplete.class, event);
            assertEquals(0.025, ((TurnComplete) event).costUsd(), 0.001);
        }

        @Test
        void invalidJson_returnsNull() {
            PassthroughEvent event = parser.parseOpenCodeLine("not json");
            assertNull(event);
        }

        @Test
        void askUserTool_parsedAsInteractiveQuestion() {
            String line = "{\"type\":\"tool_use\",\"part\":{\"tool\":\"ask_user\"," +
                    "\"callID\":\"c-42\",\"state\":{\"input\":{\"question\":\"Continue?\"," +
                    "\"options\":[\"Yes\",\"No\"]}}}}";
            PassthroughEvent event = parser.parseOpenCodeLine(line);

            assertInstanceOf(InteractiveQuestion.class, event);
            InteractiveQuestion q = (InteractiveQuestion) event;
            assertEquals("c-42", q.callId());
            assertEquals("Continue?", q.question());
            assertEquals(2, q.options().size());
            assertEquals("Yes", q.options().get(0).label());
        }

        // Multi-event variant

        @Test
        void multi_completedToolUse_emitsBothEvents() {
            String line = "{\"type\":\"tool_use\",\"part\":{\"tool\":\"Bash\"," +
                    "\"state\":{\"input\":{\"command\":\"echo hello\"}," +
                    "\"output\":\"hello\",\"status\":\"completed\"," +
                    "\"metadata\":{\"exit\":0}}}}";
            List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

            assertEquals(2, events.size());
            assertInstanceOf(ToolUse.class, events.get(0));
            assertInstanceOf(ToolComplete.class, events.get(1));
            ToolComplete tc = (ToolComplete) events.get(1);
            assertEquals("Bash", tc.name());
            assertEquals("hello", tc.output());
            assertEquals(0, tc.exitCode());
        }

        @Test
        void multi_pendingToolUse_emitsSingleEvent() {
            String line = "{\"type\":\"tool_use\",\"part\":{\"tool\":\"Read\"," +
                    "\"state\":{\"input\":{\"description\":\"reading file\"},\"status\":\"pending\"}}}";
            List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(ToolUse.class, events.get(0));
        }

        @Test
        void multi_nullLine_returnsEmpty() {
            assertTrue(parser.parseOpenCodeLineMulti(null).isEmpty());
        }

        @Test
        void multi_textEvent_singleEvent() {
            String line = "{\"type\":\"text\",\"part\":{\"text\":\"hello world\"}}";
            List<PassthroughEvent> events = parser.parseOpenCodeLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(TextChunk.class, events.get(0));
        }
    }

    // ===================================================================
    // Gemini/Qwen parser — parseGeminiLine
    // ===================================================================

    @Nested
    class GeminiParser {

        @Test
        void nullLine_returnsNull() {
            assertNull(parser.parseGeminiLine(null));
        }

        @Test
        void blankLine_returnsNull() {
            assertNull(parser.parseGeminiLine("  "));
        }

        @Test
        void initEvent_sessionInit() {
            String line = "{\"type\":\"init\",\"session_id\":\"gem-sess-01\",\"model\":\"gemini-2.5-pro\"}";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(SessionInit.class, event);
            assertEquals("gem-sess-01", ((SessionInit) event).sessionId());
        }

        @Test
        void messageEvent_assistantText() {
            String line = "{\"type\":\"message\",\"role\":\"assistant\"," +
                    "\"content\":\"I found the bug in the loop condition.\"}";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("I found the bug in the loop condition.", ((TextChunk) event).text());
        }

        @Test
        void messageEvent_userRole_skipped() {
            String line = "{\"type\":\"message\",\"role\":\"user\",\"content\":\"fix the bug\"}";
            PassthroughEvent event = parser.parseGeminiLine(line);
            assertNull(event);
        }

        @Test
        void toolUseEvent() {
            String line = "{\"type\":\"tool_use\",\"tool_name\":\"Read\"," +
                    "\"parameters\":{\"file_path\":\"/src/main.py\",\"limit\":100}}";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("Read", ((ToolUse) event).name());
            assertTrue(((ToolUse) event).input().contains("/src/main.py"));
        }

        @Test
        void toolResultEvent_skipped() {
            String line = "{\"type\":\"tool_result\",\"output\":\"file contents here\"}";
            PassthroughEvent event = parser.parseGeminiLine(line);
            assertNull(event);
        }

        @Test
        void resultEvent_withStats() {
            String line = "{\"type\":\"result\",\"stats\":{\"duration_ms\":2500,\"tool_calls\":3}}";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(TurnComplete.class, event);
            TurnComplete tc = (TurnComplete) event;
            assertEquals(2500, tc.durationMs());
            assertEquals(1, tc.numTurns());
        }

        @Test
        void resultEvent_noToolCalls() {
            String line = "{\"type\":\"result\",\"stats\":{\"duration_ms\":100,\"tool_calls\":0}}";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(TurnComplete.class, event);
            assertEquals(0, ((TurnComplete) event).numTurns());
        }

        @Test
        void unknownType_skipped() {
            String line = "{\"type\":\"heartbeat\",\"ts\":12345}";
            assertNull(parser.parseGeminiLine(line));
        }

        @Test
        void invalidJson_nonNoiseText_treatedAsTextChunk() {
            String line = "Some meaningful output text from gemini";
            PassthroughEvent event = parser.parseGeminiLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Some meaningful output text from gemini", ((TextChunk) event).text());
        }

        @Test
        void knownNoise_yoloMode_suppressed() {
            assertNull(parser.parseGeminiLine("YOLO mode is enabled for this session"));
        }
    }

    // ===================================================================
    // Codex parser — parseCodexLine
    // ===================================================================

    @Nested
    class CodexParser {

        @Test
        void nullLine_returnsNull() {
            assertNull(parser.parseCodexLine(null));
        }

        @Test
        void blankLine_returnsNull() {
            assertNull(parser.parseCodexLine(""));
        }

        @Test
        void threadStarted_sessionInit() {
            String line = "{\"type\":\"thread.started\",\"thread_id\":\"th_abc123\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(SessionInit.class, event);
            assertEquals("th_abc123", ((SessionInit) event).sessionId());
        }

        @Test
        void messageDelta_textChunk() {
            String line = "{\"type\":\"message.delta\",\"delta\":\"Hello, I can help with that.\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Hello, I can help with that.", ((TextChunk) event).text());
        }

        @Test
        void messageCompleted_textChunk() {
            String line = "{\"type\":\"message.completed\",\"text\":\"Full message text here.\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Full message text here.", ((TextChunk) event).text());
        }

        @Test
        void execStarted_toolUse() {
            String line = "{\"type\":\"exec.started\",\"command\":\"python test.py\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("exec", ((ToolUse) event).name());
            assertEquals("python test.py", ((ToolUse) event).input());
        }

        @Test
        void errorEvent() {
            String line = "{\"type\":\"error\",\"message\":\"Rate limit exceeded\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertTrue(((TextChunk) event).text().contains("[Error]"));
            assertTrue(((TextChunk) event).text().contains("Rate limit exceeded"));
        }

        @Test
        void turnFailed() {
            String line = "{\"type\":\"turn.failed\",\"error\":{\"message\":\"Context window exceeded\"}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertTrue(((TextChunk) event).text().contains("Context window exceeded"));
        }

        @Test
        void execApprovalRequest() {
            String line = "{\"type\":\"exec_approval_request\",\"call_id\":\"c1\",\"turn_id\":\"t1\"," +
                    "\"command\":[\"rm\",\"-rf\",\"temp/\"],\"cwd\":\"/home/user\"," +
                    "\"reason\":\"Clean temp directory\"," +
                    "\"available_decisions\":[\"approve\",\"deny\",\"always_approve\"]}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(InteractiveApproval.class, event);
            InteractiveApproval ia = (InteractiveApproval) event;
            assertEquals("c1", ia.callId());
            assertEquals("t1", ia.turnId());
            assertEquals("rm -rf temp/", ia.command());
            assertEquals("/home/user", ia.cwd());
            assertEquals("Clean temp directory", ia.reason());
            assertEquals(3, ia.decisions().size());
        }

        @Test
        void execApproval_noDecisions_defaultsToApproveDeny() {
            String line = "{\"type\":\"exec_approval_request\",\"call_id\":\"c2\"," +
                    "\"command\":\"ls\",\"cwd\":\"/tmp\"}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(InteractiveApproval.class, event);
            InteractiveApproval ia = (InteractiveApproval) event;
            assertEquals(2, ia.decisions().size());
            assertTrue(ia.decisions().contains("approve"));
            assertTrue(ia.decisions().contains("deny"));
        }

        @Test
        void requestUserInput_withQuestions() {
            String line = "{\"type\":\"request_user_input\",\"call_id\":\"c3\",\"turn_id\":\"t2\"," +
                    "\"questions\":[{\"id\":\"q1\",\"header\":\"Setup\"," +
                    "\"question\":\"Which framework?\",\"isOther\":false," +
                    "\"options\":[{\"label\":\"React\",\"description\":\"Frontend\"},{\"label\":\"Vue\"}]}]}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(InteractiveQuestion.class, event);
            InteractiveQuestion q = (InteractiveQuestion) event;
            assertEquals("c3", q.callId());
            assertEquals("t2", q.turnId());
            assertEquals("q1", q.questionId());
            assertEquals("Setup", q.header());
            assertEquals("Which framework?", q.question());
            assertFalse(q.freeformAllowed());
            assertEquals(2, q.options().size());
            assertEquals("React", q.options().get(0).label());
        }

        @Test
        void turnCompleted_withUsage() {
            String line = "{\"type\":\"turn.completed\",\"usage\":{" +
                    "\"input_tokens\":5000,\"output_tokens\":1200,\"cached_input_tokens\":3000}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TurnComplete.class, event);
            TurnComplete tc = (TurnComplete) event;
            assertEquals(5000, tc.inputTokens());
            assertEquals(1200, tc.outputTokens());
            assertEquals(3000, tc.cacheReadTokens());
        }

        @Test
        void envelopedEvent() {
            String line = "{\"id\":\"evt-1\",\"msg\":{\"type\":\"message.delta\",\"delta\":\"Hi\"}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Hi", ((TextChunk) event).text());
        }

        @Test
        void itemStarted_commandExecution() {
            String line = "{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\"," +
                    "\"id\":\"item-1\",\"command\":\"npm install\",\"aggregated_output\":\"\"}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("exec", ((ToolUse) event).name());
            assertEquals("npm install", ((ToolUse) event).input());
        }

        @Test
        void itemCompleted_commandExecution_toolComplete() {
            // First start the item to seed aggregated output
            parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\"," +
                    "\"id\":\"item-2\",\"command\":\"echo hi\",\"aggregated_output\":\"\"}}");

            String line = "{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\"," +
                    "\"id\":\"item-2\",\"aggregated_output\":\"hi\\n\",\"exit_code\":0}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(ToolComplete.class, event);
            ToolComplete tc = (ToolComplete) event;
            assertEquals("exec", tc.name());
            assertEquals(0, tc.exitCode());
        }

        @Test
        void invalidJson_nonNoise_textChunk() {
            PassthroughEvent event = parser.parseCodexLine("Some warning output from codex");

            assertInstanceOf(TextChunk.class, event);
            assertEquals("Some warning output from codex", ((TextChunk) event).text());
        }

        @Test
        void knownNoise_stdinMessage_suppressed() {
            assertNull(parser.parseCodexLine("Reading additional input from stdin..."));
        }

        @Test
        void responseItem_text() {
            String line = "{\"type\":\"response_item\",\"payload\":{\"text\":\"agent response\"}}";
            PassthroughEvent event = parser.parseCodexLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("agent response", ((TextChunk) event).text());
        }
    }

    // ===================================================================
    // Pi parser — parsePiLine / parsePiLineMulti
    // ===================================================================

    @Nested
    class PiParser {

        @Test
        void nullLine_returnsNull() {
            assertNull(parser.parsePiLine(null));
        }

        @Test
        void blankLine_returnsNull() {
            assertNull(parser.parsePiLine("  "));
        }

        @Test
        void sessionEvent() {
            String line = "{\"type\":\"session\",\"id\":\"pi-sess-42\"}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(SessionInit.class, event);
            assertEquals("pi-sess-42", ((SessionInit) event).sessionId());
        }

        @Test
        void messageUpdate_textDelta() {
            String line = "{\"type\":\"message_update\",\"assistantMessageEvent\":" +
                    "{\"type\":\"text_delta\",\"delta\":\"The function has a bug.\"}}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("The function has a bug.", ((TextChunk) event).text());
        }

        @Test
        void messageEnd_suppressed() {
            String line = "{\"type\":\"message_end\"}";
            assertNull(parser.parsePiLine(line));
        }

        @Test
        void toolExecutionStart() {
            String line = "{\"type\":\"tool_execution_start\",\"toolName\":\"Bash\"," +
                    "\"args\":{\"command\":\"make build\"},\"toolCallId\":\"tc-1\"}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(ToolUse.class, event);
            assertEquals("Bash", ((ToolUse) event).name());
            assertTrue(((ToolUse) event).input().contains("make build"));
        }

        @Test
        void toolExecutionUpdate_partialResult() {
            // Start the tool first to set up state
            parser.parsePiLine("{\"type\":\"tool_execution_start\",\"toolName\":\"Bash\"," +
                    "\"args\":{},\"toolCallId\":\"tc-2\"}");

            String line = "{\"type\":\"tool_execution_update\",\"toolCallId\":\"tc-2\"," +
                    "\"partialResult\":{\"content\":[{\"type\":\"text\",\"text\":\"Building...\"}]}}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(ToolOutput.class, event);
            assertEquals("Building...", ((ToolOutput) event).output());
        }

        @Test
        void toolExecutionEnd() {
            // Start the tool
            parser.parsePiLine("{\"type\":\"tool_execution_start\",\"toolName\":\"Bash\"," +
                    "\"args\":{},\"toolCallId\":\"tc-3\"}");

            String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc-3\"," +
                    "\"toolName\":\"Bash\",\"isError\":false," +
                    "\"result\":{\"exitCode\":0,\"content\":[{\"type\":\"text\",\"text\":\"Done!\"}]}}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(ToolComplete.class, event);
            ToolComplete tc = (ToolComplete) event;
            assertEquals("Bash", tc.name());
            assertEquals(0, tc.exitCode());
            assertFalse(tc.error());
        }

        @Test
        void toolExecutionEnd_withError() {
            parser.parsePiLine("{\"type\":\"tool_execution_start\",\"toolName\":\"Bash\"," +
                    "\"args\":{},\"toolCallId\":\"tc-4\"}");

            String line = "{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc-4\"," +
                    "\"toolName\":\"Bash\",\"isError\":true," +
                    "\"result\":{\"exitCode\":1,\"content\":[{\"type\":\"text\",\"text\":\"Error!\"}]}}";
            PassthroughEvent event = parser.parsePiLine(line);

            assertInstanceOf(ToolComplete.class, event);
            assertTrue(((ToolComplete) event).error());
            assertEquals(1, ((ToolComplete) event).exitCode());
        }

        @Test
        void unknownType_skipped() {
            assertNull(parser.parsePiLine("{\"type\":\"ping\"}"));
        }

        @Test
        void invalidJson_treatedAsTextChunk() {
            PassthroughEvent event = parser.parsePiLine("plain text from pi");

            assertInstanceOf(TextChunk.class, event);
            assertEquals("plain text from pi", ((TextChunk) event).text());
        }

        // Multi-event variant

        @Test
        void multi_nullLine_returnsEmpty() {
            assertTrue(parser.parsePiLineMulti(null).isEmpty());
        }

        @Test
        void multi_validEvent_wrapsInList() {
            String line = "{\"type\":\"session\",\"id\":\"pi-m\"}";
            List<PassthroughEvent> events = parser.parsePiLineMulti(line);

            assertEquals(1, events.size());
            assertInstanceOf(SessionInit.class, events.get(0));
        }

        @Test
        void multi_suppressedEvent_returnsEmpty() {
            assertTrue(parser.parsePiLineMulti("{\"type\":\"message_end\"}").isEmpty());
        }
    }

    // ===================================================================
    // Plain text parser — parsePlainLine
    // ===================================================================

    @Nested
    class PlainTextParser {

        @Test
        void nullLine_returnsNull() {
            assertNull(parser.parsePlainLine(null, null));
        }

        @Test
        void regularLine_textChunkWithNewline() {
            PassthroughEvent event = parser.parsePlainLine("some output", null);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("some output\n", ((TextChunk) event).text());
        }

        @Test
        void promptPattern_detected() {
            Pattern prompt = Pattern.compile("^> $");
            PassthroughEvent event = parser.parsePlainLine("> ", prompt);

            assertInstanceOf(PromptDetected.class, event);
        }

        @Test
        void nonMatchingPromptPattern_textChunk() {
            Pattern prompt = Pattern.compile("^\\$ $");
            PassthroughEvent event = parser.parsePlainLine("some text", prompt);

            assertInstanceOf(TextChunk.class, event);
        }

        @Test
        void emptyLine_textChunkWithNewline() {
            PassthroughEvent event = parser.parsePlainLine("", null);

            assertInstanceOf(TextChunk.class, event);
            assertEquals("\n", ((TextChunk) event).text());
        }
    }

    // ===================================================================
    // Record constructors / accessors
    // ===================================================================

    @Nested
    class RecordAccessors {

        @Test
        void turnComplete_backwardCompatConstructor() {
            TurnComplete tc = new TurnComplete(5000, 0.01, 2);
            assertEquals(5000, tc.durationMs());
            assertEquals(0.01, tc.costUsd(), 0.001);
            assertEquals(2, tc.numTurns());
            assertEquals(0, tc.inputTokens());
            assertEquals(0, tc.outputTokens());
            assertEquals(0, tc.cacheReadTokens());
            assertEquals(0, tc.cacheCreationTokens());
        }

        @Test
        void turnComplete_fullConstructor() {
            TurnComplete tc = new TurnComplete(3000, 0.05, 1, 5000, 2000, 1000, 500);
            assertEquals(5000, tc.inputTokens());
            assertEquals(2000, tc.outputTokens());
            assertEquals(1000, tc.cacheReadTokens());
            assertEquals(500, tc.cacheCreationTokens());
        }

        @Test
        void toolComplete_backwardCompatConstructor() {
            ToolComplete tc = new ToolComplete("Bash", "output", 0);
            assertEquals("Bash", tc.name());
            assertEquals("output", tc.output());
            assertEquals(0, tc.exitCode());
            assertFalse(tc.error());
        }

        @Test
        void toolComplete_exitCodeNonZero_errorTrue() {
            ToolComplete tc = new ToolComplete("Bash", "fail", 1);
            assertTrue(tc.error());
        }

        @Test
        void toolComplete_fullConstructor() {
            ToolComplete tc = new ToolComplete("Read", "data", 0, true);
            assertTrue(tc.error());
            assertEquals(0, tc.exitCode());
        }

        @Test
        void textChunk_accessors() {
            TextChunk tc = new TextChunk("hello");
            assertEquals("hello", tc.text());
        }

        @Test
        void thinkingChunk_accessors() {
            ThinkingChunk tc = new ThinkingChunk("reasoning");
            assertEquals("reasoning", tc.text());
        }

        @Test
        void tokenUsage_accessors() {
            TokenUsage tu = new TokenUsage(100, 200, 50, 25);
            assertEquals(100, tu.inputTokens());
            assertEquals(200, tu.outputTokens());
            assertEquals(50, tu.cacheReadTokens());
            assertEquals(25, tu.cacheCreationTokens());
        }

        @Test
        void toolOutput_accessors() {
            ToolOutput to = new ToolOutput("partial output");
            assertEquals("partial output", to.output());
        }

        @Test
        void sessionInit_accessors() {
            SessionInit si = new SessionInit("s-1");
            assertEquals("s-1", si.sessionId());
        }

        @Test
        void questionOption_accessors() {
            QuestionOption qo = new QuestionOption("Yes", "Approve the action");
            assertEquals("Yes", qo.label());
            assertEquals("Approve the action", qo.description());
        }

        @Test
        void interactiveQuestion_accessors() {
            InteractiveQuestion iq = new InteractiveQuestion(
                    "c1", "t1", "q1", "Header", "What next?",
                    List.of(new QuestionOption("A", "Option A")), true);
            assertEquals("c1", iq.callId());
            assertEquals("t1", iq.turnId());
            assertEquals("q1", iq.questionId());
            assertEquals("Header", iq.header());
            assertEquals("What next?", iq.question());
            assertEquals(1, iq.options().size());
            assertTrue(iq.freeformAllowed());
        }

        @Test
        void interactiveApproval_accessors() {
            InteractiveApproval ia = new InteractiveApproval(
                    "c2", "t2", "rm -rf /tmp/test", "/home", "cleanup",
                    List.of("approve", "deny"));
            assertEquals("c2", ia.callId());
            assertEquals("t2", ia.turnId());
            assertEquals("rm -rf /tmp/test", ia.command());
            assertEquals("/home", ia.cwd());
            assertEquals("cleanup", ia.reason());
            assertEquals(2, ia.decisions().size());
        }

        @Test
        void promptDetected_isPassthroughEvent() {
            PromptDetected pd = new PromptDetected();
            assertInstanceOf(PassthroughEvent.class, pd);
        }
    }

    // ===================================================================
    // Codex delta accumulation (stateful)
    // ===================================================================

    @Nested
    class CodexDeltaAccumulation {

        @Test
        void itemUpdated_emitsDelta() {
            // Start a command execution
            parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"type\":\"command_execution\"," +
                    "\"id\":\"cmd-1\",\"command\":\"cat log.txt\",\"aggregated_output\":\"line1\\n\"}}");

            // Update with more output
            String update = "{\"type\":\"item.updated\",\"item\":{\"type\":\"command_execution\"," +
                    "\"id\":\"cmd-1\",\"aggregated_output\":\"line1\\nline2\\n\"}}";
            PassthroughEvent event = parser.parseCodexLine(update);

            assertInstanceOf(ToolOutput.class, event);
            assertEquals("line2\n", ((ToolOutput) event).output());
        }

        @Test
        void agentMessageDelta_emitsTextDelta() {
            // Start agent message
            parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"type\":\"agent_message\"," +
                    "\"id\":\"msg-1\",\"text\":\"Hello\"}}");

            // Update with more text
            String update = "{\"type\":\"item.updated\",\"item\":{\"type\":\"agent_message\"," +
                    "\"id\":\"msg-1\",\"text\":\"Hello World\"}}";
            PassthroughEvent event = parser.parseCodexLine(update);

            assertInstanceOf(TextChunk.class, event);
            assertEquals(" World", ((TextChunk) event).text());
        }

        @Test
        void agentMessageCompleted_cleanupState() {
            parser.parseCodexLine("{\"type\":\"item.started\",\"item\":{\"type\":\"agent_message\"," +
                    "\"id\":\"msg-2\",\"text\":\"Partial\"}}");

            String completed = "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\"," +
                    "\"id\":\"msg-2\",\"text\":\"Partial response done\"}}";
            PassthroughEvent event = parser.parseCodexLine(completed);

            assertInstanceOf(TextChunk.class, event);
            assertEquals(" response done", ((TextChunk) event).text());
        }
    }
}
