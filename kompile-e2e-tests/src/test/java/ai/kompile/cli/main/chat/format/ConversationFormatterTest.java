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

package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationFormatter")
class ConversationFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ChatHistory.Turn> SAMPLE_TURNS = List.of(
            new ChatHistory.Turn("user", "What is kompile?"),
            new ChatHistory.Turn("assistant", "Kompile is an AI/ML platform."),
            new ChatHistory.Turn("user", "Tell me more."),
            new ChatHistory.Turn("assistant", "It combines CLI tools, Spring Boot RAG, and ML pipelines.")
    );

    // ─── isSupported ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"kompile", "openai", "anthropic", "markdown", "jsonl"})
    @DisplayName("isSupported returns true for valid formats")
    void isSupportedValid(String format) {
        assertTrue(ConversationFormatter.isSupported(format));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "csv", "yaml", "html", ""})
    @DisplayName("isSupported returns false for invalid formats")
    void isSupportedInvalid(String format) {
        assertFalse(ConversationFormatter.isSupported(format));
    }

    // ─── Empty / null handling ─────────────────────────────────────────

    @Test
    @DisplayName("format returns empty string for empty turns list")
    void emptyTurns() {
        assertEquals("", ConversationFormatter.format(Collections.emptyList(), "openai"));
    }

    @Test
    @DisplayName("format returns empty string for null turns list")
    void nullTurns() {
        assertEquals("", ConversationFormatter.format(null, "openai"));
    }

    @Test
    @DisplayName("format throws for unknown format")
    void unknownFormat() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConversationFormatter.format(SAMPLE_TURNS, "xml"));
        assertTrue(ex.getMessage().contains("Unknown format"));
        assertTrue(ex.getMessage().contains("xml"));
    }

    // ─── Kompile format ────────────────────────────────────────────────

    @Nested
    @DisplayName("kompile format")
    class KompileFormat {

        @Test
        @DisplayName("user messages prefixed with '> ', assistant messages bare")
        void basicFormatting() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "kompile");

            assertTrue(result.contains("> What is kompile?"));
            assertTrue(result.contains("> Tell me more."));
            assertTrue(result.contains("Kompile is an AI/ML platform."));
            assertFalse(result.contains("> Kompile is an AI/ML platform."));
        }

        @Test
        @DisplayName("single user turn")
        void singleUserTurn() {
            List<ChatHistory.Turn> turns = List.of(new ChatHistory.Turn("user", "Hello"));
            String result = ConversationFormatter.format(turns, "kompile");
            assertEquals("> Hello", result);
        }

        @Test
        @DisplayName("single assistant turn")
        void singleAssistantTurn() {
            List<ChatHistory.Turn> turns = List.of(new ChatHistory.Turn("assistant", "Hi there"));
            String result = ConversationFormatter.format(turns, "kompile");
            assertEquals("Hi there", result);
        }

        @Test
        @DisplayName("turns separated by blank lines")
        void blankLineSeparation() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "kompile");
            // Each turn ends with \n\n, then stripTrailing removes trailing whitespace
            String[] blocks = result.split("\n\n");
            assertEquals(4, blocks.length);
        }
    }

    // ─── OpenAI / Anthropic JSON format ────────────────────────────────

    @Nested
    @DisplayName("openai/anthropic JSON format")
    class JsonFormat {

        @Test
        @DisplayName("produces valid JSON array")
        void validJson() throws Exception {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "openai");
            JsonNode root = MAPPER.readTree(result);
            assertTrue(root.isArray());
            assertEquals(4, root.size());
        }

        @Test
        @DisplayName("each element has role and content fields")
        void roleAndContent() throws Exception {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "openai");
            JsonNode root = MAPPER.readTree(result);

            JsonNode first = root.get(0);
            assertEquals("user", first.get("role").asText());
            assertEquals("What is kompile?", first.get("content").asText());

            JsonNode second = root.get(1);
            assertEquals("assistant", second.get("role").asText());
            assertEquals("Kompile is an AI/ML platform.", second.get("content").asText());
        }

        @Test
        @DisplayName("anthropic format produces identical output to openai")
        void anthropicSameAsOpenai() {
            String openai = ConversationFormatter.format(SAMPLE_TURNS, "openai");
            String anthropic = ConversationFormatter.format(SAMPLE_TURNS, "anthropic");
            assertEquals(openai, anthropic);
        }

        @Test
        @DisplayName("preserves multiline content in JSON")
        void multilineContent() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("assistant", "Line 1\nLine 2\nLine 3")
            );
            String result = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(result);
            assertEquals("Line 1\nLine 2\nLine 3", root.get(0).get("content").asText());
        }

        @Test
        @DisplayName("escapes special characters in JSON")
        void specialChars() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "Use \"quotes\" and \\backslash")
            );
            String result = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(result);
            assertEquals("Use \"quotes\" and \\backslash", root.get(0).get("content").asText());
        }
    }

    // ─── Markdown format ───────────────────────────────────────────────

    @Nested
    @DisplayName("markdown format")
    class MarkdownFormat {

        @Test
        @DisplayName("user turns get ## User header")
        void userHeader() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "markdown");
            assertTrue(result.contains("## User\n\nWhat is kompile?"));
        }

        @Test
        @DisplayName("assistant turns get ## Assistant header")
        void assistantHeader() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "markdown");
            assertTrue(result.contains("## Assistant\n\nKompile is an AI/ML platform."));
        }

        @Test
        @DisplayName("turns separated by --- dividers")
        void dividers() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "markdown");
            // 4 turns → 3 dividers
            int count = countOccurrences(result, "\n---\n");
            assertEquals(3, count);
        }

        @Test
        @DisplayName("no leading divider before first turn")
        void noLeadingDivider() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "markdown");
            assertTrue(result.startsWith("## User"));
        }

        @Test
        @DisplayName("single turn has no dividers")
        void singleTurnNoDividers() {
            List<ChatHistory.Turn> turns = List.of(new ChatHistory.Turn("user", "Hi"));
            String result = ConversationFormatter.format(turns, "markdown");
            assertFalse(result.contains("---"));
            assertEquals("## User\n\nHi", result);
        }
    }

    // ─── JSONL format ──────────────────────────────────────────────────

    @Nested
    @DisplayName("jsonl format")
    class JsonlFormat {

        @Test
        @DisplayName("one JSON object per line")
        void onePerLine() {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "jsonl");
            String[] lines = result.split("\n");
            assertEquals(4, lines.length);
        }

        @Test
        @DisplayName("each line is valid JSON with role and content")
        void validJsonPerLine() throws Exception {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "jsonl");
            String[] lines = result.split("\n");

            for (String line : lines) {
                JsonNode node = MAPPER.readTree(line);
                assertTrue(node.has("role"));
                assertTrue(node.has("content"));
                String role = node.get("role").asText();
                assertTrue("user".equals(role) || "assistant".equals(role));
            }
        }

        @Test
        @DisplayName("first line matches first turn")
        void firstLine() throws Exception {
            String result = ConversationFormatter.format(SAMPLE_TURNS, "jsonl");
            String firstLine = result.split("\n")[0];
            JsonNode node = MAPPER.readTree(firstLine);
            assertEquals("user", node.get("role").asText());
            assertEquals("What is kompile?", node.get("content").asText());
        }

        @Test
        @DisplayName("multiline content stays on single JSONL line")
        void multilineOnOneLine() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("assistant", "Line 1\nLine 2")
            );
            String result = ConversationFormatter.format(turns, "jsonl");
            String[] lines = result.split("\n");
            // The JSON-encoded newline (\n) is inside a single output line
            assertEquals(1, lines.length);
            JsonNode node = MAPPER.readTree(lines[0]);
            assertEquals("Line 1\nLine 2", node.get("content").asText());
        }
    }

    // ─── Cross-format consistency ──────────────────────────────────────

    @Nested
    @DisplayName("cross-format consistency")
    class CrossFormat {

        @Test
        @DisplayName("all formats preserve all turns")
        void allFormatsPreserveTurns() throws Exception {
            // kompile: count "> " prefixes for user turns + bare lines for assistant
            String kompile = ConversationFormatter.format(SAMPLE_TURNS, "kompile");
            assertEquals(2, countOccurrences(kompile, "> "));

            // openai: JSON array length
            String openai = ConversationFormatter.format(SAMPLE_TURNS, "openai");
            JsonNode arr = MAPPER.readTree(openai);
            assertEquals(4, arr.size());

            // markdown: count ## headers
            String markdown = ConversationFormatter.format(SAMPLE_TURNS, "markdown");
            assertEquals(2, countOccurrences(markdown, "## User"));
            assertEquals(2, countOccurrences(markdown, "## Assistant"));

            // jsonl: line count
            String jsonl = ConversationFormatter.format(SAMPLE_TURNS, "jsonl");
            assertEquals(4, jsonl.split("\n").length);
        }

        @Test
        @DisplayName("all formats handle unicode content")
        void unicodeContent() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "Explain \u00e9l\u00e8ve and \u00fc\u00f1\u00ed\u00e7\u00f6de"),
                    new ChatHistory.Turn("assistant", "\u65e5\u672c\u8a9e\u306e\u30c6\u30b9\u30c8")
            );

            String kompile = ConversationFormatter.format(turns, "kompile");
            assertTrue(kompile.contains("\u00e9l\u00e8ve"));

            String openai = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(openai);
            assertTrue(root.get(1).get("content").asText().contains("\u65e5\u672c\u8a9e"));

            String jsonl = ConversationFormatter.format(turns, "jsonl");
            assertTrue(jsonl.contains("\u00fc\u00f1\u00ed\u00e7\u00f6de"));
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
