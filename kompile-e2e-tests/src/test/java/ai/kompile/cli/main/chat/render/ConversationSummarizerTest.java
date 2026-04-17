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
package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConversationSummarizer")
class ConversationSummarizerTest {

    private RecordingDirectLlmClient client;
    private ConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        client = new RecordingDirectLlmClient(new ChatConfig(), new ObjectMapper());
        summarizer = new ConversationSummarizer(client);
    }

    @Nested
    @DisplayName("Empty or null history")
    class EmptyHistory {

        @Test
        void nullHistoryReturnsEmptyResultWithoutCallingLlm() {
            ConversationSummarizer.SummaryResult result =
                    summarizer.summarize(null, null, null);

            assertTrue(result.isEmpty());
            assertEquals(0, client.callCount);
        }

        @Test
        void emptyHistoryReturnsEmptyResultWithoutCallingLlm() {
            ConversationSummarizer.SummaryResult result =
                    summarizer.summarize(new ArrayList<>(), null, null);

            assertTrue(result.isEmpty());
            assertEquals(0, client.callCount);
        }
    }

    @Nested
    @DisplayName("Prompt structure")
    class PromptStructure {

        @Test
        void promptIncludesAllNineSectionHeaders() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hello"));

            summarizer.summarize(history, null, null);

            String prompt = client.lastPrompt;
            assertNotNull(prompt);
            assertTrue(prompt.contains("## 1. Primary Request and Intent"));
            assertTrue(prompt.contains("## 2. Key Technical Concepts"));
            assertTrue(prompt.contains("## 3. Files and Code Sections"));
            assertTrue(prompt.contains("## 4. Errors and Fixes"));
            assertTrue(prompt.contains("## 5. Problem Solving"));
            assertTrue(prompt.contains("## 6. All User Messages"));
            assertTrue(prompt.contains("## 7. Pending Tasks"));
            assertTrue(prompt.contains("## 8. Current Work"));
            assertTrue(prompt.contains("## 9. Optional Next Step"));
        }

        @Test
        void systemPromptExplainsSummarizerRole() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            summarizer.summarize(history, null, null);

            assertNotNull(client.lastSystemPrompt);
            assertTrue(client.lastSystemPrompt.toLowerCase().contains("summariz"));
        }

        @Test
        void focusInstructionIsIncludedInPromptWhenProvided() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            summarizer.summarize(history, "focus on the API changes", null);

            assertTrue(client.lastPrompt.contains("focus on the API changes"));
        }

        @Test
        void blankFocusInstructionIsOmitted() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            summarizer.summarize(history, "   ", null);

            assertFalse(client.lastPrompt.contains("Additional focus from the user"));
        }

        @Test
        void nullFocusInstructionIsOmitted() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            summarizer.summarize(history, null, null);

            assertFalse(client.lastPrompt.contains("Additional focus from the user"));
        }

        @Test
        void modelOverrideIsPassedThrough() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            summarizer.summarize(history, null, "gpt-4o-mini");

            assertEquals("gpt-4o-mini", client.lastModelOverride);
        }
    }

    @Nested
    @DisplayName("Transcript serialization")
    class TranscriptSerialization {

        @Test
        void userMessageIsLabeledAndIncluded() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("What is 2+2?"));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("[USER]"));
            assertTrue(client.lastPrompt.contains("What is 2+2?"));
        }

        @Test
        void assistantMessageIsLabeledAndIncluded() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.assistant("The answer is 4."));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("[ASSISTANT]"));
            assertTrue(client.lastPrompt.contains("The answer is 4."));
        }

        @Test
        void systemMessageIsLabeledAndIncluded() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.system("you are helpful"));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("[SYSTEM]"));
            assertTrue(client.lastPrompt.contains("you are helpful"));
        }

        @Test
        void toolCallIncludesToolName() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.toolCall(
                    "read", "c1", "reading /tmp/foo"));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("[TOOL_CALL read]"));
            assertTrue(client.lastPrompt.contains("reading /tmp/foo"));
        }

        @Test
        void toolResultIncludesToolName() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.toolResult(
                    "bash", "c1", "hello world"));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("[TOOL_RESULT bash]"));
            assertTrue(client.lastPrompt.contains("hello world"));
        }

        @Test
        void largeToolResultIsTruncatedInTranscript() {
            // Build a tool result > 4KB
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 10_000; i++) huge.append('x');

            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.toolResult(
                    "bash", "c1", huge.toString()));

            summarizer.summarize(history, null, null);

            assertTrue(client.lastPrompt.contains("(tool result truncated for summarization)"),
                    "expected truncation marker in prompt");
            // The prompt should not contain the full 10K xs
            assertFalse(client.lastPrompt.contains("x".repeat(5000)),
                    "tool result should have been trimmed below 5000 chars");
        }

        @Test
        void nullContentEntryIsSkipped() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(new CompactionService.ConversationEntry(
                    CompactionService.EntryType.USER, "user", null, null, null));
            history.add(CompactionService.ConversationEntry.user("real content"));

            ConversationSummarizer.SummaryResult result =
                    summarizer.summarize(history, null, null);

            // should still call LLM since list isn't empty
            assertEquals(1, client.callCount);
            assertTrue(client.lastPrompt.contains("real content"));
        }

        @Test
        void multipleEntriesAreSerializedInOrder() {
            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("first"));
            history.add(CompactionService.ConversationEntry.assistant("second"));
            history.add(CompactionService.ConversationEntry.user("third"));

            summarizer.summarize(history, null, null);

            int firstIdx = client.lastPrompt.indexOf("first");
            int secondIdx = client.lastPrompt.indexOf("second");
            int thirdIdx = client.lastPrompt.indexOf("third");
            assertTrue(firstIdx > 0 && secondIdx > firstIdx && thirdIdx > secondIdx,
                    "entries should appear in chronological order in the transcript");
        }
    }

    @Nested
    @DisplayName("Result propagation")
    class ResultPropagation {

        @Test
        void summaryTextIsReturnedFromLlmCall() {
            client.cannedResponse.text = "Structured summary body";
            client.cannedResponse.inputTokens = 1000;
            client.cannedResponse.outputTokens = 200;

            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            ConversationSummarizer.SummaryResult result =
                    summarizer.summarize(history, null, null);

            assertEquals("Structured summary body", result.getSummary());
            assertEquals(1000, result.getInputTokens());
            assertEquals(200, result.getOutputTokens());
            assertFalse(result.isEmpty());
        }

        @Test
        void emptyStreamResponseYieldsEmptySummaryResult() {
            client.cannedResponse.text = "";

            List<CompactionService.ConversationEntry> history = new ArrayList<>();
            history.add(CompactionService.ConversationEntry.user("hi"));

            ConversationSummarizer.SummaryResult result =
                    summarizer.summarize(history, null, null);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // Fake LLM client: records inputs, returns canned output.
    // Overriding streamOneShot lets us inspect the exact prompt the
    // summarizer built without any network traffic.
    // ========================================================================

    private static class RecordingDirectLlmClient extends DirectLlmClient {
        StreamResult cannedResponse = new StreamResult();
        String lastPrompt;
        String lastSystemPrompt;
        String lastModelOverride;
        int callCount;

        RecordingDirectLlmClient(ChatConfig config, ObjectMapper objectMapper) {
            super(config, objectMapper);
            cannedResponse.text = "DEFAULT SUMMARY";
        }

        @Override
        public StreamResult streamOneShot(String prompt, String systemPrompt, String modelOverride) {
            this.lastPrompt = prompt;
            this.lastSystemPrompt = systemPrompt;
            this.lastModelOverride = modelOverride;
            this.callCount++;
            return cannedResponse;
        }

        @Override
        public StreamResult streamChat(String userMessage, String systemPrompt,
                                        ArrayNode toolDefs,
                                        List<ToolCallResultInput> toolResults,
                                        String modelOverride) {
            // Should never be reached in these tests — ConversationSummarizer
            // goes through streamOneShot, which is overridden above.
            throw new AssertionError("streamChat should not be called");
        }
    }
}
