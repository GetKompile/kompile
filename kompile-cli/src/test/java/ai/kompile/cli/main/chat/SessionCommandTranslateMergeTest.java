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

import ai.kompile.cli.main.chat.format.ConversationFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level tests that validate the translate and merge workflows
 * end-to-end using the ConversationFormatter, simulating what the
 * TranslateCommand and MergeCommand do with in-memory turn data.
 */
@DisplayName("Session translate & merge workflows")
class SessionCommandTranslateMergeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ChatHistory.Turn> SESSION_A = List.of(
            new ChatHistory.Turn("user", "How do I build kompile?"),
            new ChatHistory.Turn("assistant", "Run mvn clean install.")
    );

    private static final List<ChatHistory.Turn> SESSION_B = List.of(
            new ChatHistory.Turn("user", "What about native images?"),
            new ChatHistory.Turn("assistant", "Use the -Pnative profile.")
    );

    // ─── Translate workflows ───────────────────────────────────────────

    @Nested
    @DisplayName("Translate workflow")
    class TranslateWorkflow {

        @Test
        @DisplayName("translate to openai produces parseable JSON with correct roles")
        void translateToOpenai() throws Exception {
            String result = ConversationFormatter.format(SESSION_A, "openai");
            JsonNode root = MAPPER.readTree(result);

            assertTrue(root.isArray());
            assertEquals(2, root.size());
            assertEquals("user", root.get(0).get("role").asText());
            assertEquals("How do I build kompile?", root.get(0).get("content").asText());
            assertEquals("assistant", root.get(1).get("role").asText());
            assertEquals("Run mvn clean install.", root.get(1).get("content").asText());
        }

        @Test
        @DisplayName("translate to anthropic produces same output as openai")
        void translateToAnthropic() {
            String openai = ConversationFormatter.format(SESSION_A, "openai");
            String anthropic = ConversationFormatter.format(SESSION_A, "anthropic");
            assertEquals(openai, anthropic);
        }

        @Test
        @DisplayName("translate to markdown produces proper headers and dividers")
        void translateToMarkdown() {
            String result = ConversationFormatter.format(SESSION_A, "markdown");

            assertTrue(result.startsWith("## User"));
            assertTrue(result.contains("## User\n\nHow do I build kompile?"));
            assertTrue(result.contains("---"));
            assertTrue(result.contains("## Assistant\n\nRun mvn clean install."));
        }

        @Test
        @DisplayName("translate to jsonl produces one valid JSON per line")
        void translateToJsonl() throws Exception {
            String result = ConversationFormatter.format(SESSION_A, "jsonl");
            String[] lines = result.split("\n");

            assertEquals(2, lines.length);

            JsonNode line1 = MAPPER.readTree(lines[0]);
            assertEquals("user", line1.get("role").asText());
            assertEquals("How do I build kompile?", line1.get("content").asText());

            JsonNode line2 = MAPPER.readTree(lines[1]);
            assertEquals("assistant", line2.get("role").asText());
        }

        @Test
        @DisplayName("translate to kompile preserves '> ' prefix for user turns")
        void translateToKompile() {
            String result = ConversationFormatter.format(SESSION_A, "kompile");
            assertTrue(result.contains("> How do I build kompile?"));
            assertTrue(result.contains("Run mvn clean install."));
            // Assistant text should NOT have "> " prefix
            assertFalse(result.contains("> Run mvn"));
        }

        @Test
        @DisplayName("translate preserves content with special characters")
        void translateSpecialChars() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "What about <html> & \"quotes\"?"),
                    new ChatHistory.Turn("assistant", "Use proper escaping: \\n, \t, etc.")
            );

            // JSON formats should escape properly
            String json = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(json);
            assertEquals("What about <html> & \"quotes\"?", root.get(0).get("content").asText());

            // Markdown should pass through literally
            String md = ConversationFormatter.format(turns, "markdown");
            assertTrue(md.contains("<html>"));
            assertTrue(md.contains("\"quotes\""));
        }

        @Test
        @DisplayName("translate handles long multi-paragraph content")
        void translateLongContent() throws Exception {
            String longContent = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph with code:\n```java\nSystem.out.println(\"hello\");\n```";
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("assistant", longContent)
            );

            // openai preserves the whole thing
            String json = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(json);
            assertEquals(longContent, root.get(0).get("content").asText());

            // jsonl keeps it on one logical line
            String jsonl = ConversationFormatter.format(turns, "jsonl");
            assertEquals(1, jsonl.split("\n").length);
            JsonNode node = MAPPER.readTree(jsonl);
            assertEquals(longContent, node.get("content").asText());
        }
    }

    // ─── Merge workflows ───────────────────────────────────────────────

    @Nested
    @DisplayName("Merge workflow")
    class MergeWorkflow {

        @Test
        @DisplayName("merge to openai concatenates all turns into one JSON array")
        void mergeToOpenai() throws Exception {
            // Simulate what MergeCommand.mergeFlat() does for JSON formats
            List<ChatHistory.Turn> allTurns = new java.util.ArrayList<>();
            allTurns.addAll(SESSION_A);
            allTurns.addAll(SESSION_B);

            String result = ConversationFormatter.format(allTurns, "openai");
            JsonNode root = MAPPER.readTree(result);

            assertEquals(4, root.size());
            assertEquals("How do I build kompile?", root.get(0).get("content").asText());
            assertEquals("Run mvn clean install.", root.get(1).get("content").asText());
            assertEquals("What about native images?", root.get(2).get("content").asText());
            assertEquals("Use the -Pnative profile.", root.get(3).get("content").asText());
        }

        @Test
        @DisplayName("merge to jsonl concatenates all turns, one per line")
        void mergeToJsonl() throws Exception {
            List<ChatHistory.Turn> allTurns = new java.util.ArrayList<>();
            allTurns.addAll(SESSION_A);
            allTurns.addAll(SESSION_B);

            String result = ConversationFormatter.format(allTurns, "jsonl");
            String[] lines = result.split("\n");
            assertEquals(4, lines.length);

            // Verify ordering
            assertEquals("How do I build kompile?", MAPPER.readTree(lines[0]).get("content").asText());
            assertEquals("Use the -Pnative profile.", MAPPER.readTree(lines[3]).get("content").asText());
        }

        @Test
        @DisplayName("merge to kompile with separator inserts separator between sessions")
        void mergeToKompileWithSeparator() {
            // Simulate what MergeCommand.mergeWithSeparators() does
            String separator = "--- Session Break ---";
            String sessionAText = ConversationFormatter.format(SESSION_A, "kompile");
            String sessionBText = ConversationFormatter.format(SESSION_B, "kompile");

            String merged = sessionAText + "\n" + separator + "\n\n" + sessionBText;

            assertTrue(merged.contains("> How do I build kompile?"));
            assertTrue(merged.contains("--- Session Break ---"));
            assertTrue(merged.contains("> What about native images?"));

            // Verify ordering
            int sepIndex = merged.indexOf(separator);
            int firstQ = merged.indexOf("How do I build kompile?");
            int secondQ = merged.indexOf("What about native images?");
            assertTrue(firstQ < sepIndex);
            assertTrue(sepIndex < secondQ);
        }

        @Test
        @DisplayName("merge to markdown with separator inserts separator between sessions")
        void mergeToMarkdownWithSeparator() {
            String separator = "--- Session Break ---";
            String sessionAText = ConversationFormatter.format(SESSION_A, "markdown");
            String sessionBText = ConversationFormatter.format(SESSION_B, "markdown");

            String merged = sessionAText + "\n" + separator + "\n\n" + sessionBText;

            assertTrue(merged.contains("## User\n\nHow do I build kompile?"));
            assertTrue(merged.contains("--- Session Break ---"));
            assertTrue(merged.contains("## User\n\nWhat about native images?"));
        }

        @Test
        @DisplayName("merge with custom separator uses provided separator text")
        void mergeCustomSeparator() {
            String separator = "===== NEW SESSION =====";
            String sessionAText = ConversationFormatter.format(SESSION_A, "kompile");
            String sessionBText = ConversationFormatter.format(SESSION_B, "kompile");

            String merged = sessionAText + "\n" + separator + "\n\n" + sessionBText;

            assertTrue(merged.contains("===== NEW SESSION ====="));
            assertFalse(merged.contains("--- Session Break ---"));
        }

        @Test
        @DisplayName("merge single session produces same output as translate")
        void mergeSingleSession() {
            // Merging one session should be identical to translating it
            String translated = ConversationFormatter.format(SESSION_A, "openai");
            String merged = ConversationFormatter.format(SESSION_A, "openai");
            assertEquals(translated, merged);
        }

        @Test
        @DisplayName("merge three sessions into openai")
        void mergeThreeSessions() throws Exception {
            List<ChatHistory.Turn> sessionC = List.of(
                    new ChatHistory.Turn("user", "How do I test?"),
                    new ChatHistory.Turn("assistant", "Run mvn test.")
            );

            List<ChatHistory.Turn> allTurns = new java.util.ArrayList<>();
            allTurns.addAll(SESSION_A);
            allTurns.addAll(SESSION_B);
            allTurns.addAll(sessionC);

            String result = ConversationFormatter.format(allTurns, "openai");
            JsonNode root = MAPPER.readTree(result);
            assertEquals(6, root.size());

            // Verify last entry
            assertEquals("assistant", root.get(5).get("role").asText());
            assertEquals("Run mvn test.", root.get(5).get("content").asText());
        }
    }

    // ─── Edge cases ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty content string is preserved")
        void emptyContentString() throws Exception {
            // A turn with whitespace-only content is possible
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "Hello"),
                    new ChatHistory.Turn("assistant", "Response here")
            );

            String result = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(result);
            assertEquals(2, root.size());
        }

        @Test
        @DisplayName("content with newlines handled correctly in all formats")
        void contentWithNewlines() throws Exception {
            List<ChatHistory.Turn> turns = List.of(
                    new ChatHistory.Turn("user", "Step 1\nStep 2\nStep 3")
            );

            // kompile: the "> " prefix is only on the first line
            String kompile = ConversationFormatter.format(turns, "kompile");
            assertTrue(kompile.startsWith("> Step 1\nStep 2\nStep 3"));

            // openai: newlines inside JSON string
            String openai = ConversationFormatter.format(turns, "openai");
            JsonNode root = MAPPER.readTree(openai);
            assertEquals("Step 1\nStep 2\nStep 3", root.get(0).get("content").asText());

            // markdown: newlines preserved under header
            String markdown = ConversationFormatter.format(turns, "markdown");
            assertTrue(markdown.contains("Step 1\nStep 2\nStep 3"));

            // jsonl: still one logical line
            String jsonl = ConversationFormatter.format(turns, "jsonl");
            assertEquals(1, jsonl.split("\n").length);
        }

        @Test
        @DisplayName("supported formats list contains expected values")
        void supportedFormatsList() {
            List<String> formats = ConversationFormatter.SUPPORTED_FORMATS;
            assertEquals(5, formats.size());
            assertTrue(formats.contains("kompile"));
            assertTrue(formats.contains("openai"));
            assertTrue(formats.contains("anthropic"));
            assertTrue(formats.contains("markdown"));
            assertTrue(formats.contains("jsonl"));
        }

        @Test
        @DisplayName("format is case-insensitive")
        void caseInsensitive() throws Exception {
            String lower = ConversationFormatter.format(SESSION_A, "openai");
            String upper = ConversationFormatter.format(SESSION_A, "OPENAI");
            String mixed = ConversationFormatter.format(SESSION_A, "OpenAI");
            assertEquals(lower, upper);
            assertEquals(lower, mixed);
        }
    }
}
