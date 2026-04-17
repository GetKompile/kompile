/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeStreamParser token metrics tracking.
 * Verifies that input/output token counts, throughput, and model info
 * are correctly parsed from both Claude CLI and Anthropic streaming API formats.
 */
@DisplayName("ClaudeStreamParser Token Metrics Tests")
public class ClaudeStreamParserTokenMetricsTest {

    private ClaudeStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new ClaudeStreamParser();
    }

    @Nested
    @DisplayName("Anthropic Streaming API Token Metrics")
    class AnthropicStreamingApiMetrics {

        @Test
        @DisplayName("Should extract input tokens from message_start event")
        void shouldExtractInputTokensFromMessageStart() {
            String sessionId = "test-session-1";

            // message_start with usage containing input_tokens
            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_123","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":150,"output_tokens":0}}}}""";

            parser.parseLine(sessionId, messageStart);

            // Simulate some output via message_delta
            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}}""";

            parser.parseLine(sessionId, messageDelta);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics, "Token metrics should be available");
            assertEquals(150, metrics.get("inputTokens"));
            assertEquals(42, metrics.get("outputTokens"));
            assertEquals("claude-sonnet-4-20250514", metrics.get("model"));
        }

        @Test
        @DisplayName("Should extract output tokens from message_delta usage")
        void shouldExtractOutputTokensFromMessageDelta() {
            String sessionId = "test-session-2";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_456","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":200,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            // First delta with partial output tokens
            String delta1 = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":10}}}""";
            parser.parseLine(sessionId, delta1);

            // Final delta with cumulative output tokens
            String delta2 = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":85}}}""";
            parser.parseLine(sessionId, delta2);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics);
            assertEquals(85, metrics.get("outputTokens"), "Should use cumulative output token count from last delta");
            assertEquals(200, metrics.get("inputTokens"));
        }

        @Test
        @DisplayName("Should calculate tokens per second")
        void shouldCalculateTokensPerSecond() throws InterruptedException {
            String sessionId = "test-session-3";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_789","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":100,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            // Allow some time to pass so tokensPerSecond > 0
            Thread.sleep(10);

            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}}""";
            parser.parseLine(sessionId, messageDelta);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics);
            assertTrue(metrics.containsKey("tokensPerSecond"));
            double tps = ((Number) metrics.get("tokensPerSecond")).doubleValue();
            assertTrue(tps >= 0, "Tokens per second should be non-negative");
        }

        @Test
        @DisplayName("Should include totalGenerationMs in metrics")
        void shouldIncludeTotalGenerationMs() {
            String sessionId = "test-session-4";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_gen","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":50,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}}""";
            parser.parseLine(sessionId, messageDelta);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics);
            assertTrue(metrics.containsKey("totalGenerationMs"));
            long totalMs = ((Number) metrics.get("totalGenerationMs")).longValue();
            assertTrue(totalMs >= 0, "Total generation time should be non-negative");
        }

        @Test
        @DisplayName("Should extract model name from message_start")
        void shouldExtractModelName() {
            String sessionId = "test-session-model";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_model","type":"message","role":"assistant","model":"claude-opus-4-20250514","usage":{"input_tokens":300,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":100}}}""";
            parser.parseLine(sessionId, messageDelta);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics);
            assertEquals("claude-opus-4-20250514", metrics.get("model"));
        }
    }

    @Nested
    @DisplayName("Token Metrics Edge Cases")
    class TokenMetricsEdgeCases {

        @Test
        @DisplayName("Should return null metrics for unknown session")
        void shouldReturnNullForUnknownSession() {
            Map<String, Object> metrics = parser.getTokenMetrics("nonexistent-session");
            assertNull(metrics, "Should return null for session with no metrics");
        }

        @Test
        @DisplayName("Should return null metrics when no output tokens")
        void shouldReturnNullWhenNoOutputTokens() {
            String sessionId = "test-no-output";

            // message_start but no delta with output tokens
            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_empty","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":100,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNull(metrics, "Should return null when output tokens is 0");
        }

        @Test
        @DisplayName("Should clear session metrics properly")
        void shouldClearSessionMetrics() {
            String sessionId = "test-clear";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_clear","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":50,"output_tokens":0}}}}""";
            parser.parseLine(sessionId, messageStart);

            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":25}}}""";
            parser.parseLine(sessionId, messageDelta);

            // Verify metrics exist
            assertNotNull(parser.getTokenMetrics(sessionId));

            // Clear session
            parser.clearSession(sessionId);

            // Verify metrics are gone
            assertNull(parser.getTokenMetrics(sessionId));
        }

        @Test
        @DisplayName("Should handle missing usage in message_start")
        void shouldHandleMissingUsageInMessageStart() {
            String sessionId = "test-no-usage";

            String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_nousage","type":"message","role":"assistant","model":"claude-sonnet-4-20250514"}}}""";
            parser.parseLine(sessionId, messageStart);

            String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":30}}}""";
            parser.parseLine(sessionId, messageDelta);

            Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
            assertNotNull(metrics);
            assertEquals(0, metrics.get("inputTokens"), "Input tokens should default to 0 when usage missing");
            assertEquals(30, metrics.get("outputTokens"));
        }

        @Test
        @DisplayName("Should handle multiple sessions independently")
        void shouldHandleMultipleSessionsIndependently() {
            String session1 = "session-a";
            String session2 = "session-b";

            // Session 1
            parser.parseLine(session1, """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_a","type":"message","role":"assistant","model":"claude-sonnet-4-20250514","usage":{"input_tokens":100,"output_tokens":0}}}}""");
            parser.parseLine(session1, """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}}""");

            // Session 2
            parser.parseLine(session2, """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_b","type":"message","role":"assistant","model":"claude-opus-4-20250514","usage":{"input_tokens":200,"output_tokens":0}}}}""");
            parser.parseLine(session2, """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":75}}}""");

            Map<String, Object> metrics1 = parser.getTokenMetrics(session1);
            Map<String, Object> metrics2 = parser.getTokenMetrics(session2);

            assertNotNull(metrics1);
            assertNotNull(metrics2);

            assertEquals(100, metrics1.get("inputTokens"));
            assertEquals(50, metrics1.get("outputTokens"));
            assertEquals("claude-sonnet-4-20250514", metrics1.get("model"));

            assertEquals(200, metrics2.get("inputTokens"));
            assertEquals(75, metrics2.get("outputTokens"));
            assertEquals("claude-opus-4-20250514", metrics2.get("model"));
        }
    }

    @Nested
    @DisplayName("Claude CLI Format Token Handling")
    class ClaudeCliFormat {

        @Test
        @DisplayName("Should parse result event with duration")
        void shouldParseResultEventWithDuration() {
            String sessionId = "test-cli-result";

            String resultLine = """
                {"type":"result","subtype":"success","is_error":false,"duration_ms":5432,"total_cost_usd":0.012,"num_turns":3}""";

            ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, resultLine);
            assertNotNull(result);
            assertTrue(result.isResult());
            assertFalse(result.isError());
            assertEquals(5432, result.durationMs());
            assertEquals(3, result.numTurns());
        }

        @Test
        @DisplayName("Should parse result event with error")
        void shouldParseResultEventWithError() {
            String sessionId = "test-cli-error";

            String resultLine = """
                {"type":"result","subtype":"error","is_error":true,"duration_ms":1000,"total_cost_usd":0.001,"num_turns":1}""";

            ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, resultLine);
            assertNotNull(result);
            assertTrue(result.isResult());
            assertTrue(result.isError());
        }
    }

    @Nested
    @DisplayName("Text Content Parsing")
    class TextContentParsing {

        @Test
        @DisplayName("Should parse text_delta content blocks")
        void shouldParseTextDeltaContentBlocks() {
            String sessionId = "test-text-delta";

            // Start a content block
            parser.parseLine(sessionId, """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""");

            // Send text delta
            ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello world"}}}""");

            assertNotNull(result);
            assertEquals("text", result.type());
            assertEquals("Hello world", result.textContent());
        }

        @Test
        @DisplayName("Should filter system reminders from text")
        void shouldFilterSystemReminders() {
            String sessionId = "test-filter";

            String lineWithReminder = "Some text <system-reminder>secret stuff</system-reminder> more text";
            ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, lineWithReminder);

            assertNotNull(result);
            assertFalse(result.textContent().contains("system-reminder"));
            assertFalse(result.textContent().contains("secret stuff"));
            assertTrue(result.textContent().contains("Some text"));
            assertTrue(result.textContent().contains("more text"));
        }

        @Test
        @DisplayName("Should handle null and blank lines")
        void shouldHandleNullAndBlankLines() {
            assertNull(parser.parseLine("session", null));
            assertNull(parser.parseLine("session", ""));
            assertNull(parser.parseLine("session", "   "));
        }
    }

    @Nested
    @DisplayName("Tool Use Tracking")
    class ToolUseTracking {

        @Test
        @DisplayName("Should track modified files from Edit tool")
        void shouldTrackModifiedFilesFromEditTool() {
            String sessionId = "test-edit-tracking";

            // Parse an assistant event with tool_use for Edit
            String assistantEvent = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","id":"tool_1","input":{"file_path":"/src/main.ts","old_string":"old","new_string":"new"}}]}}""";

            parser.parseLine(sessionId, assistantEvent);

            var modifiedFiles = parser.getModifiedFiles(sessionId);
            assertEquals(1, modifiedFiles.size());
            assertEquals("/src/main.ts", modifiedFiles.get(0));
        }

        @Test
        @DisplayName("Should track modified files from Write tool")
        void shouldTrackModifiedFilesFromWriteTool() {
            String sessionId = "test-write-tracking";

            String assistantEvent = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Write","id":"tool_2","input":{"file_path":"/src/new-file.ts","content":"hello"}}]}}""";

            parser.parseLine(sessionId, assistantEvent);

            var modifiedFiles = parser.getModifiedFiles(sessionId);
            assertEquals(1, modifiedFiles.size());
            assertEquals("/src/new-file.ts", modifiedFiles.get(0));
        }

        @Test
        @DisplayName("Should clear modified files on clearModifiedFiles")
        void shouldClearModifiedFiles() {
            String sessionId = "test-clear-files";

            String assistantEvent = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","id":"tool_3","input":{"file_path":"/src/file.ts","old_string":"a","new_string":"b"}}]}}""";

            parser.parseLine(sessionId, assistantEvent);
            assertEquals(1, parser.getModifiedFiles(sessionId).size());

            parser.clearModifiedFiles(sessionId);
            assertEquals(0, parser.getModifiedFiles(sessionId).size());
        }
    }

    @Nested
    @DisplayName("Stream JSON Support Detection")
    class StreamJsonSupport {

        @Test
        @DisplayName("Should support stream-json for Claude")
        void shouldSupportStreamJsonForClaude() {
            assertTrue(parser.supportsStreamJson("claude"));
            assertTrue(parser.supportsStreamJson("Claude"));
            assertTrue(parser.supportsStreamJson(null)); // Default to Claude
        }

        @Test
        @DisplayName("Should not support stream-json for Codex")
        void shouldNotSupportStreamJsonForCodex() {
            assertFalse(parser.supportsStreamJson("codex"));
            assertFalse(parser.supportsStreamJson("Codex"));
        }

        @Test
        @DisplayName("Should not support stream-json for Gemini")
        void shouldNotSupportStreamJsonForGemini() {
            assertFalse(parser.supportsStreamJson("gemini"));
            assertFalse(parser.supportsStreamJson("Gemini"));
        }
    }
}
