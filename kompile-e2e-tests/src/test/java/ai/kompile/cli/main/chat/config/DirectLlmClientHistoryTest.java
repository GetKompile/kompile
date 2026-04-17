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
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the history-management methods on DirectLlmClient used by the
 * /compact feature: getHistorySize, addToHistory, clearHistory,
 * replaceHistoryWithSummary, and the history-preserving streamOneShot wrapper.
 * None of these tests make network calls.
 */
@DisplayName("DirectLlmClient history management")
class DirectLlmClientHistoryTest {

    private DirectLlmClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new DirectLlmClient(new ChatConfig(), objectMapper);
    }

    @Nested
    @DisplayName("Basic history ops")
    class BasicOps {

        @Test
        void newClientStartsWithEmptyHistory() {
            assertEquals(0, client.getHistorySize());
        }

        @Test
        void addToHistoryGrowsHistory() {
            client.addToHistory("user", "hello");
            client.addToHistory("assistant", "hi there");
            assertEquals(2, client.getHistorySize());
        }

        @Test
        void clearHistoryResetsToEmpty() {
            client.addToHistory("user", "hello");
            client.addToHistory("assistant", "hi");
            client.clearHistory();
            assertEquals(0, client.getHistorySize());
        }
    }

    @Nested
    @DisplayName("replaceHistoryWithSummary")
    class ReplaceHistoryWithSummary {

        @Test
        void replacesExistingHistoryWithTwoMessages() {
            // Seed with 5 prior messages
            for (int i = 0; i < 5; i++) {
                client.addToHistory("user", "msg " + i);
            }
            assertEquals(5, client.getHistorySize());

            client.replaceHistoryWithSummary("This is the summary.");

            // Summary injects as one user + one assistant message
            assertEquals(2, client.getHistorySize());
        }

        @Test
        void nullSummaryClearsHistoryWithoutAddingMessages() {
            client.addToHistory("user", "old message");
            client.replaceHistoryWithSummary(null);
            assertEquals(0, client.getHistorySize());
        }

        @Test
        void blankSummaryClearsHistoryWithoutAddingMessages() {
            client.addToHistory("user", "old message");
            client.replaceHistoryWithSummary("   \t\n  ");
            assertEquals(0, client.getHistorySize());
        }

        @Test
        void replaceOnEmptyHistoryAddsTwoMessages() {
            client.replaceHistoryWithSummary("Summary content.");
            assertEquals(2, client.getHistorySize());
        }
    }

    @Nested
    @DisplayName("streamOneShot history preservation")
    class StreamOneShotHistoryPreservation {

        @Test
        void historyIsRestoredAfterSuccessfulCall() {
            // Seed real history with 3 messages
            client.addToHistory("user", "first");
            client.addToHistory("assistant", "second");
            client.addToHistory("user", "third");

            DirectLlmClient pollutingClient = new PollutingDirectLlmClient(
                    new ChatConfig(), objectMapper);
            pollutingClient.addToHistory("user", "first");
            pollutingClient.addToHistory("assistant", "second");
            pollutingClient.addToHistory("user", "third");

            pollutingClient.streamOneShot("summarize plz", "sys", null);

            // Even though the overridden streamChat added bogus messages,
            // streamOneShot must have restored the original 3 messages
            assertEquals(3, pollutingClient.getHistorySize(),
                    "streamOneShot should restore the pre-call history");
        }

        @Test
        void historyIsRestoredEvenWhenUnderlyingCallThrows() {
            DirectLlmClient throwingClient = new ThrowingDirectLlmClient(
                    new ChatConfig(), objectMapper);
            throwingClient.addToHistory("user", "existing");
            throwingClient.addToHistory("assistant", "content");

            assertThrows(RuntimeException.class,
                    () -> throwingClient.streamOneShot("x", "y", null));

            assertEquals(2, throwingClient.getHistorySize(),
                    "streamOneShot should restore history even on exception");
        }

        @Test
        void streamOneShotInvokesStreamChatWithNullToolArgs() {
            CapturingDirectLlmClient capturing = new CapturingDirectLlmClient(
                    new ChatConfig(), objectMapper);
            capturing.streamOneShot("prompt text", "system text", "model-x");

            assertEquals("prompt text", capturing.capturedUserMessage);
            assertEquals("system text", capturing.capturedSystemPrompt);
            assertEquals("model-x", capturing.capturedModelOverride);
            assertNull(capturing.capturedToolDefs);
            assertNull(capturing.capturedToolResults);
        }
    }

    // ========================================================================
    // Test doubles
    // ========================================================================

    /** streamChat adds bogus messages, simulating the real method's side-effects. */
    private static class PollutingDirectLlmClient extends DirectLlmClient {
        PollutingDirectLlmClient(ChatConfig c, ObjectMapper m) { super(c, m); }

        @Override
        public StreamResult streamChat(String userMessage, String systemPrompt,
                                        ArrayNode toolDefs,
                                        List<ToolCallResultInput> toolResults,
                                        String modelOverride) {
            // Simulate the real method's history mutation
            addToHistory("user", userMessage != null ? userMessage : "");
            addToHistory("assistant", "polluting response");
            StreamResult r = new StreamResult();
            r.text = "polluting response";
            return r;
        }
    }

    /** streamChat throws, exercising the finally-block restore path. */
    private static class ThrowingDirectLlmClient extends DirectLlmClient {
        ThrowingDirectLlmClient(ChatConfig c, ObjectMapper m) { super(c, m); }

        @Override
        public StreamResult streamChat(String userMessage, String systemPrompt,
                                        ArrayNode toolDefs,
                                        List<ToolCallResultInput> toolResults,
                                        String modelOverride) {
            throw new RuntimeException("boom");
        }
    }

    /** streamChat records its arguments so we can verify streamOneShot forwards them. */
    private static class CapturingDirectLlmClient extends DirectLlmClient {
        String capturedUserMessage;
        String capturedSystemPrompt;
        ArrayNode capturedToolDefs;
        List<ToolCallResultInput> capturedToolResults;
        String capturedModelOverride;

        CapturingDirectLlmClient(ChatConfig c, ObjectMapper m) { super(c, m); }

        @Override
        public StreamResult streamChat(String userMessage, String systemPrompt,
                                        ArrayNode toolDefs,
                                        List<ToolCallResultInput> toolResults,
                                        String modelOverride) {
            this.capturedUserMessage = userMessage;
            this.capturedSystemPrompt = systemPrompt;
            this.capturedToolDefs = toolDefs;
            this.capturedToolResults = toolResults;
            this.capturedModelOverride = modelOverride;
            return new StreamResult();
        }
    }
}
