/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PassthroughStreamParser, verifying parsing of Claude stream-json
 * output and plain-text agent output for all supported agents.
 */
@DisplayName("PassthroughStreamParser")
class PassthroughStreamParserTest {

    private PassthroughStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new PassthroughStreamParser();
    }

    // =========================================================================
    // Claude stream-json parsing
    // =========================================================================

    @Nested
    @DisplayName("Claude stream-json parsing")
    class ClaudeStreamJson {

        @Test
        @DisplayName("should parse session init event")
        void parsesSessionInit() {
            String line = "{\"type\":\"system\",\"session_id\":\"sess-123\"}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertNotNull(event);
            assertInstanceOf(PassthroughStreamParser.SessionInit.class, event);
            PassthroughStreamParser.SessionInit init = (PassthroughStreamParser.SessionInit) event;
            assertEquals("sess-123", init.sessionId());
        }

        @Test
        @DisplayName("should parse assistant text content")
        void parsesAssistantText() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello world\"}]}}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertNotNull(event);
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
            PassthroughStreamParser.TextChunk chunk = (PassthroughStreamParser.TextChunk) event;
            assertEquals("Hello world", chunk.text());
        }

        @Test
        @DisplayName("should parse tool_use from content blocks")
        void parsesToolUse() {
            String line = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file\":\"/tmp/test.txt\"}}]}}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertNotNull(event);
            assertInstanceOf(PassthroughStreamParser.ToolUse.class, event);
            PassthroughStreamParser.ToolUse toolUse = (PassthroughStreamParser.ToolUse) event;
            assertEquals("Read", toolUse.name());
            assertTrue(toolUse.input().contains("file"));
        }

        @Test
        @DisplayName("should parse result event with stats")
        void parsesResult() {
            String line = "{\"type\":\"result\",\"duration_ms\":1500,\"cost_usd\":0.0042,\"num_turns\":3}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertNotNull(event);
            assertInstanceOf(PassthroughStreamParser.TurnComplete.class, event);
            PassthroughStreamParser.TurnComplete tc = (PassthroughStreamParser.TurnComplete) event;
            assertEquals(1500, tc.durationMs());
            assertEquals(0.0042, tc.costUsd(), 0.0001);
            assertEquals(3, tc.numTurns());
        }

        @Test
        @DisplayName("should parse content_block text")
        void parsesContentBlock() {
            String line = "{\"content_block\":{\"text\":\"streaming chunk\"}}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertNotNull(event);
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
            assertEquals("streaming chunk", ((PassthroughStreamParser.TextChunk) event).text());
        }

        @Test
        @DisplayName("should return null for blank lines")
        void nullForBlank() {
            assertNull(parser.parseClaudeLine(""));
            assertNull(parser.parseClaudeLine("   "));
            assertNull(parser.parseClaudeLine(null));
        }

        @Test
        @DisplayName("should return TextChunk for non-JSON lines")
        void nonJsonAsText() {
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine("plain text output");
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
            assertEquals("plain text output", ((PassthroughStreamParser.TextChunk) event).text());
        }

        @Test
        @DisplayName("should return null for system event without session_id")
        void systemWithoutSessionId() {
            String line = "{\"type\":\"system\",\"message\":\"init\"}";
            assertNull(parser.parseClaudeLine(line));
        }

        @Test
        @DisplayName("should handle result with missing fields")
        void resultWithDefaults() {
            String line = "{\"type\":\"result\"}";
            PassthroughStreamParser.PassthroughEvent event = parser.parseClaudeLine(line);

            assertInstanceOf(PassthroughStreamParser.TurnComplete.class, event);
            PassthroughStreamParser.TurnComplete tc = (PassthroughStreamParser.TurnComplete) event;
            assertEquals(0, tc.durationMs());
            assertEquals(0.0, tc.costUsd());
            assertEquals(0, tc.numTurns());
        }
    }

    // =========================================================================
    // Plain text parsing (Codex/Gemini)
    // =========================================================================

    @Nested
    @DisplayName("Plain text parsing (Codex/Gemini)")
    class PlainTextParsing {

        @Test
        @DisplayName("should return TextChunk for normal lines")
        void normalLine() {
            Pattern promptPattern = Pattern.compile("^> $");
            PassthroughStreamParser.PassthroughEvent event = parser.parsePlainLine("Hello from Codex", promptPattern);

            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
            assertEquals("Hello from Codex\n", ((PassthroughStreamParser.TextChunk) event).text());
        }

        @Test
        @DisplayName("should detect prompt pattern for Codex (turn boundary)")
        void detectsCodexPrompt() {
            Pattern promptPattern = Pattern.compile("^> $");
            PassthroughStreamParser.PassthroughEvent event = parser.parsePlainLine("> ", promptPattern);

            assertInstanceOf(PassthroughStreamParser.PromptDetected.class, event);
        }

        @Test
        @DisplayName("should detect prompt pattern for Gemini (turn boundary)")
        void detectsGeminiPrompt() {
            Pattern promptPattern = Pattern.compile("^> $");
            PassthroughStreamParser.PassthroughEvent event = parser.parsePlainLine("> ", promptPattern);

            assertInstanceOf(PassthroughStreamParser.PromptDetected.class, event);
        }

        @Test
        @DisplayName("should not detect prompt when pattern doesn't match")
        void noPromptMatch() {
            Pattern promptPattern = Pattern.compile("^> $");
            PassthroughStreamParser.PassthroughEvent event = parser.parsePlainLine("not a prompt", promptPattern);

            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
        }

        @Test
        @DisplayName("should handle null prompt pattern")
        void nullPromptPattern() {
            PassthroughStreamParser.PassthroughEvent event = parser.parsePlainLine("some text", null);

            assertInstanceOf(PassthroughStreamParser.TextChunk.class, event);
            assertEquals("some text\n", ((PassthroughStreamParser.TextChunk) event).text());
        }

        @Test
        @DisplayName("should return null for null line")
        void nullLine() {
            assertNull(parser.parsePlainLine(null, Pattern.compile("^> $")));
        }
    }

    // =========================================================================
    // All agent types integration
    // =========================================================================

    @Nested
    @DisplayName("Agent-specific parsing integration")
    class AgentIntegration {

        @Test
        @DisplayName("Claude: full message flow (init -> text -> tool_use -> result)")
        void claudeFullFlow() {
            // Session init
            PassthroughStreamParser.PassthroughEvent e1 = parser.parseClaudeLine(
                    "{\"type\":\"system\",\"session_id\":\"abc-123\"}");
            assertInstanceOf(PassthroughStreamParser.SessionInit.class, e1);

            // Text response
            PassthroughStreamParser.PassthroughEvent e2 = parser.parseClaudeLine(
                    "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"I'll help you\"}]}}");
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, e2);

            // Tool use
            PassthroughStreamParser.PassthroughEvent e3 = parser.parseClaudeLine(
                    "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}");
            assertInstanceOf(PassthroughStreamParser.ToolUse.class, e3);
            assertEquals("Bash", ((PassthroughStreamParser.ToolUse) e3).name());

            // Result (turn complete)
            PassthroughStreamParser.PassthroughEvent e4 = parser.parseClaudeLine(
                    "{\"type\":\"result\",\"duration_ms\":5000,\"cost_usd\":0.01,\"num_turns\":2}");
            assertInstanceOf(PassthroughStreamParser.TurnComplete.class, e4);
            assertEquals(5000, ((PassthroughStreamParser.TurnComplete) e4).durationMs());
        }

        @Test
        @DisplayName("Codex: text lines followed by prompt detection")
        void codexFlow() {
            Pattern prompt = Pattern.compile("^> $");

            // Response text
            PassthroughStreamParser.PassthroughEvent e1 = parser.parsePlainLine("Here's the solution:", prompt);
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, e1);

            PassthroughStreamParser.PassthroughEvent e2 = parser.parsePlainLine("def hello():", prompt);
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, e2);

            // Prompt (turn boundary)
            PassthroughStreamParser.PassthroughEvent e3 = parser.parsePlainLine("> ", prompt);
            assertInstanceOf(PassthroughStreamParser.PromptDetected.class, e3);
        }

        @Test
        @DisplayName("Gemini: text lines followed by prompt detection")
        void geminiFlow() {
            Pattern prompt = Pattern.compile("^> $");

            PassthroughStreamParser.PassthroughEvent e1 = parser.parsePlainLine("I can help with that.", prompt);
            assertInstanceOf(PassthroughStreamParser.TextChunk.class, e1);

            PassthroughStreamParser.PassthroughEvent e2 = parser.parsePlainLine("> ", prompt);
            assertInstanceOf(PassthroughStreamParser.PromptDetected.class, e2);
        }
    }
}
