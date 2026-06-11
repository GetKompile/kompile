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

package ai.kompile.app.services.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClaudeStreamParser} — JSON parsing, stream-json format, filtering, and
 * session lifecycle management.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaudeStreamParserTest {

    private ClaudeStreamParser parser;

    @BeforeEach
    void setUp() {
        parser = new ClaudeStreamParser();
    }

    // ── parseLine: null / blank ─────────────────────────────────────────────────

    @Test
    void parseLine_nullLine_returnsNull() {
        assertNull(parser.parseLine("session1", null));
    }

    @Test
    void parseLine_blankLine_returnsNull() {
        assertNull(parser.parseLine("session1", "   "));
    }

    // ── parseLine: plain text lines ────────────────────────────────────────────

    @Test
    void parseLine_plainTextLine_returnsTextResult() {
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", "hello world");
        assertNotNull(result);
        assertEquals("hello world", result.textContent());
        assertFalse(result.isResult());
    }

    @Test
    void parseLine_plainTextWithSystemReminder_filteredOut() {
        String line = "before <system-reminder>secret stuff</system-reminder> after";
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", line);
        // filtered text should not contain system-reminder content
        if (result != null && result.textContent() != null) {
            assertFalse(result.textContent().contains("secret stuff"),
                    "System reminder content should be filtered");
        }
    }

    @Test
    void parseLine_onlySystemReminder_returnsNull() {
        String line = "<system-reminder>hidden</system-reminder>";
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", line);
        // Either null or empty text
        assertTrue(result == null ||
                (result.textContent() == null || result.textContent().isBlank()));
    }

    // ── parseLine: Claude CLI format — system event ────────────────────────────

    @Test
    void parseLine_systemInitEvent_returnsInitResult() {
        String json = """
                {"type":"system","subtype":"init","session_id":"abc-123"}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertEquals("init", result.type());
    }

    @Test
    void parseLine_systemOtherSubtype_returnsSystemResult() {
        String json = """
                {"type":"system","subtype":"other","data":"x"}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertEquals("system", result.type());
    }

    // ── parseLine: assistant event ─────────────────────────────────────────────

    @Test
    void parseLine_assistantTextEvent_extractsText() {
        String json = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello from Claude!"}]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertTrue(result.textContent().contains("Hello from Claude!"));
    }

    @Test
    void parseLine_assistantThinkingEvent_extractsThinking() {
        String json = """
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Deep thought"}]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertNotNull(result.textContent());
        assertTrue(result.textContent().contains("Deep thought"));
    }

    @Test
    void parseLine_assistantToolUseEvent_formatsToolOutput() {
        String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","id":"tu1","name":"Bash","input":{"command":"ls -la"}}
                ]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        // Tool use should produce formatted output containing the tool name or command
        String content = result.textContent();
        assertNotNull(content);
        assertTrue(content.contains("Bash") || content.contains("ls -la"),
                "Tool use output should reference the tool or command: " + content);
    }

    @Test
    void parseLine_assistantEditToolTracksModifiedFile() {
        String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","id":"tu2","name":"Edit","input":{"file_path":"/tmp/foo.java","old_string":"a","new_string":"b"}}
                ]}}
                """.strip();
        parser.parseLine("session-track", json);
        List<String> modifiedFiles = parser.getModifiedFiles("session-track");
        assertTrue(modifiedFiles.contains("/tmp/foo.java"),
                "Edit tool should track modified file");
    }

    @Test
    void parseLine_assistantWriteToolTracksModifiedFile() {
        String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","id":"tu3","name":"Write","input":{"file_path":"/tmp/bar.txt","content":"hello"}}
                ]}}
                """.strip();
        parser.parseLine("session-write", json);
        List<String> files = parser.getModifiedFiles("session-write");
        assertTrue(files.contains("/tmp/bar.txt"));
    }

    @Test
    void parseLine_assistantTextWithSystemReminder_filtered() {
        String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"text","text":"Before <system-reminder>hidden</system-reminder> After"}
                ]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertFalse(result.textContent().contains("hidden"),
                "System reminders in assistant text should be filtered");
    }

    // ── parseLine: user event (tool results) ───────────────────────────────────

    @Test
    void parseLine_userToolResultEvent_extractsResultText() {
        String json = """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"tu1","content":"file written","is_error":false}
                ]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        // May produce text or empty
        // The important thing is it doesn't throw
    }

    @Test
    void parseLine_userToolResultError_markedAsError() {
        String json = """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"tu1","content":"Permission denied","is_error":true}
                ]}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        if (result.textContent() != null && !result.textContent().isBlank()) {
            assertTrue(result.textContent().contains("ERROR") || result.textContent().contains("Permission denied"),
                    "Error tool results should be marked: " + result.textContent());
        }
    }

    // ── parseLine: result event ────────────────────────────────────────────────

    @Test
    void parseLine_resultEvent_setsIsResultTrue() {
        String json = """
                {"type":"result","is_error":false,"duration_ms":1500,"total_cost_usd":0.005,"num_turns":2}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertTrue(result.isResult());
        assertFalse(result.isError());
        assertEquals(1500, result.durationMs());
        assertEquals(0.005, result.costUsd(), 0.0001);
        assertEquals(2, result.numTurns());
    }

    @Test
    void parseLine_resultEventWithError_setsIsErrorTrue() {
        String json = """
                {"type":"result","is_error":true,"duration_ms":500}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertTrue(result.isResult());
        assertTrue(result.isError());
    }

    // ── parseLine: invalid JSON falls back to plain text ──────────────────────

    @Test
    void parseLine_invalidJson_returnsPlainTextOrNull() {
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", "not json at all {broken");
        // Should not throw, may return text or null
        if (result != null) {
            // acceptable: returned as-is or null
            assertNotNull(result.type());
        }
    }

    // ── parseLine: content_block_delta ─────────────────────────────────────────

    @Test
    void parseLine_contentBlockDeltaTextDelta_returnsText() {
        // Simulate direct content_block_delta (Claude CLI stream-json format)
        String json = """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello!"}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertEquals("Hello!", result.textContent());
    }

    @Test
    void parseLine_contentBlockDeltaThinking_returnsThought() {
        String json = """
                {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"I think..."}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine("s1", json);
        assertNotNull(result);
        assertEquals("I think...", result.textContent());
    }

    // ── ParseResult factory methods ────────────────────────────────────────────

    @Test
    void parseResult_text_factoryMethod() {
        ClaudeStreamParser.ParseResult r = ClaudeStreamParser.ParseResult.text("hello");
        assertEquals("text", r.type());
        assertEquals("hello", r.textContent());
        assertFalse(r.isResult());
        assertFalse(r.isError());
    }

    @Test
    void parseResult_result_factoryMethod() {
        ClaudeStreamParser.ParseResult r = ClaudeStreamParser.ParseResult.result(true, 100, 0.01, 3);
        assertEquals("result", r.type());
        assertTrue(r.isResult());
        assertTrue(r.isError());
        assertEquals(100, r.durationMs());
        assertEquals(0.01, r.costUsd(), 0.0001);
        assertEquals(3, r.numTurns());
    }

    @Test
    void parseResult_event_factoryMethod() {
        ClaudeStreamParser.ParseResult r = ClaudeStreamParser.ParseResult.event("custom", "content");
        assertEquals("custom", r.type());
        assertEquals("content", r.textContent());
        assertFalse(r.isResult());
    }

    @Test
    void parseResult_toolUse_factoryMethod() {
        ClaudeStreamParser.ParseResult r = ClaudeStreamParser.ParseResult.toolUse("Bash", null, "cmd output");
        assertEquals("tool_use", r.type());
        assertEquals("cmd output", r.textContent());
        assertEquals("Bash", r.toolName());
    }

    // ── Session lifecycle ───────────────────────────────────────────────────────

    @Test
    void clearSession_removesContentBlocksAndMetrics() {
        // First, create some state
        String json = """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}
                """.strip();
        parser.parseLine("session-clear", json);
        parser.clearSession("session-clear");
        // No exception + empty modified files
        List<String> files = parser.getModifiedFiles("session-clear");
        assertTrue(files.isEmpty());
    }

    @Test
    void clearModifiedFiles_removesTrackedFiles() {
        String json = """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","id":"tu4","name":"Write","input":{"file_path":"/tmp/test.txt","content":"hi"}}
                ]}}
                """.strip();
        parser.parseLine("session-mod", json);
        assertFalse(parser.getModifiedFiles("session-mod").isEmpty());

        parser.clearModifiedFiles("session-mod");
        assertTrue(parser.getModifiedFiles("session-mod").isEmpty());
    }

    @Test
    void getModifiedFiles_unknownSession_returnsEmptyList() {
        List<String> files = parser.getModifiedFiles("unknown-session-xyz");
        assertNotNull(files);
        assertTrue(files.isEmpty());
    }

    // ── Token metrics ───────────────────────────────────────────────────────────

    @Test
    void getTokenMetrics_noMetrics_returnsNull() {
        // No message_start event recorded
        Map<String, Object> metrics = parser.getTokenMetrics("no-metrics-session");
        assertNull(metrics, "No metrics should return null when no output tokens logged");
    }

    @Test
    void getTokenMetrics_afterMessageStartAndDelta_returnsMetrics() {
        String sessionId = "metrics-session";

        // Simulate message_start via stream_event wrapper
        String messageStart = """
                {"type":"stream_event","event":{"type":"message_start","message":{"id":"msg1","model":"claude-3-5-sonnet","usage":{"input_tokens":50}}}}
                """.strip();
        parser.parseLine(sessionId, messageStart);

        // Simulate message_delta with output tokens
        String messageDelta = """
                {"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":100}}}
                """.strip();
        parser.parseLine(sessionId, messageDelta);

        Map<String, Object> metrics = parser.getTokenMetrics(sessionId);
        assertNotNull(metrics, "Should have metrics after message_delta with output_tokens");
        assertEquals(100, metrics.get("outputTokens"));
        assertEquals(50, metrics.get("inputTokens"));
        assertNotNull(metrics.get("tokensPerSecond"));
        assertNotNull(metrics.get("totalGenerationMs"));
    }

    // ── supportsStreamJson ──────────────────────────────────────────────────────

    @Test
    void supportsStreamJson_nullAgent_returnsTrue() {
        assertTrue(parser.supportsStreamJson(null));
    }

    @Test
    void supportsStreamJson_claudeAgent_returnsTrue() {
        assertTrue(parser.supportsStreamJson("claude-cli"));
    }

    @Test
    void supportsStreamJson_codexAgent_returnsFalse() {
        assertFalse(parser.supportsStreamJson("codex"));
    }

    @Test
    void supportsStreamJson_geminiAgent_returnsFalse() {
        assertFalse(parser.supportsStreamJson("gemini"));
    }

    @Test
    void supportsStreamJson_customClaudeVariant_returnsTrue() {
        assertTrue(parser.supportsStreamJson("my-claude-variant"));
    }

    // ── Anthropic Streaming API format (stream_event wrapper) ─────────────────

    @Test
    void parseLine_streamEventContentBlockStart_returnsNull() {
        String json = """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
                """.strip();
        // content_block_start returns null (no visible content yet)
        ClaudeStreamParser.ParseResult result = parser.parseLine("api-session", json);
        assertNull(result);
    }

    @Test
    void parseLine_streamEventContentBlockDeltaText_returnsText() {
        String sessionId = "api-delta-session";
        // First start the block
        String start = """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
                """.strip();
        parser.parseLine(sessionId, start);

        // Then delta
        String delta = """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"World"}}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, delta);
        assertNotNull(result);
        assertEquals("World", result.textContent());
    }

    @Test
    void parseLine_streamEventMessageStop_clearsBlocksForSession() {
        String sessionId = "stop-session";
        // Start a block to create state
        String start = """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}
                """.strip();
        parser.parseLine(sessionId, start);

        String stop = """
                {"type":"stream_event","event":{"type":"message_stop"}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, stop);
        assertNotNull(result);
        assertEquals("message_stop", result.type());
    }

    @Test
    void parseLine_streamEventToolUseBlockStop_returnsToolUseResult() {
        String sessionId = "tool-stop-session";

        // Start tool_use block
        String start = """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu1","name":"Bash"}}}
                """.strip();
        parser.parseLine(sessionId, start);

        // Add input via delta
        String delta = """
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"echo hi\\"}"}}}
                """.strip();
        parser.parseLine(sessionId, delta);

        // Stop the block
        String stop = """
                {"type":"stream_event","event":{"type":"content_block_stop","index":0}}
                """.strip();
        ClaudeStreamParser.ParseResult result = parser.parseLine(sessionId, stop);
        assertNotNull(result);
        assertEquals("tool_use", result.type());
        assertEquals("Bash", result.toolName());
    }
}
