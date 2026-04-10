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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChatHistory — transcript writing and turn parsing.
 */
@DisplayName("ChatHistory")
class ChatHistoryTest {

    private ChatHistory history;
    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "test-" + System.nanoTime();
        history = new ChatHistory(sessionId);
    }

    @AfterEach
    void tearDown() {
        history.close();
        try {
            Files.deleteIfExists(history.getTranscriptFile());
        } catch (IOException ignored) {}
    }

    @Nested
    @DisplayName("Transcript writing")
    class TranscriptWriting {

        @Test
        void openCreatesFileWithHeader() throws IOException {
            history.open("http://localhost:8080", "claude", true);
            history.close();

            String content = history.readTranscript();
            assertNotNull(content);
            assertTrue(content.contains("Conversation: " + sessionId));
            assertTrue(content.contains("Started:"));
            assertTrue(content.contains("Server:  http://localhost:8080"));
            assertTrue(content.contains("Agent:   claude"));
            assertTrue(content.contains("RAG:     enabled"));
        }

        @Test
        void logUserMessageWritesPrefixed() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logUserMessage("What is kompile?");
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("> What is kompile?"));
        }

        @Test
        void logAssistantMessageWritesResponse() throws IOException {
            history.open("http://localhost:8080", "claude", true);
            history.logAssistantMessage("Kompile is an AI platform.", 3, 245);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("Kompile is an AI platform."));
            assertTrue(content.contains("[3 docs retrieved, 245ms]"));
        }

        @Test
        void logAssistantMessageNoDocs() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logAssistantMessage("Hello!", 0, 100);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("Hello!"));
            assertFalse(content.contains("docs retrieved"));
        }

        @Test
        void logSystemEvent() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logSystem("RAG disabled.");
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[system] RAG disabled."));
        }

        @Test
        void logToolCall() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logToolCall("read", false, 15);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[tool:read] ok (15ms)"));
        }

        @Test
        void logToolCallError() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logToolCall("bash", true, 500);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[tool:bash] error (500ms)"));
        }

        @Test
        void logAgentResponse() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logAgentResponse("claude", "Here is the response.", 1500);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[agent:claude]"));
            assertTrue(content.contains("Here is the response."));
            assertTrue(content.contains("[completed in 1500ms]"));
        }

        @Test
        void logSubagent() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logSubagent("explorer", "Find auth files", 800, false);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[subagent:explorer] Find auth files — complete (800ms)"));
        }

        @Test
        void logTodoEvent() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logTodoEvent("create", "1", "Fix the bug");
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[todo:create] #1 Fix the bug"));
        }

        @Test
        void logAgenticStep() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logAgenticStep(3, 10, 2);
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[agentic-step] 3/10 (2 tool calls)"));
        }
    }

    @Nested
    @DisplayName("Turn parsing (readTurns)")
    class TurnParsing {

        @Test
        void parsesUserAndAssistantTurns() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logUserMessage("What is kompile?");
            history.logAssistantMessage("Kompile is an AI platform.", 0, 100);
            history.logUserMessage("Tell me more.");
            history.logAssistantMessage("It supports RAG and ML pipelines.", 0, 200);
            history.close();

            List<ChatHistory.Turn> turns = history.readTurns();
            assertEquals(4, turns.size());
            assertEquals("user", turns.get(0).role());
            assertEquals("What is kompile?", turns.get(0).content());
            assertEquals("assistant", turns.get(1).role());
            assertEquals("Kompile is an AI platform.", turns.get(1).content());
            assertEquals("user", turns.get(2).role());
            assertEquals("assistant", turns.get(3).role());
        }

        @Test
        void skipsSystemEventsInTurns() throws IOException {
            history.open("http://localhost:8080", "claude", false);
            history.logUserMessage("Hello");
            history.logSystem("RAG enabled");
            history.logAssistantMessage("Hi!", 0, 50);
            history.close();

            List<ChatHistory.Turn> turns = history.readTurns();
            // System event should not appear as a turn
            boolean hasSystem = turns.stream().anyMatch(t -> t.content().contains("RAG enabled"));
            assertFalse(hasSystem);
        }

        @Test
        void emptyFileReturnsNoTurns() throws IOException {
            // Don't open/write anything
            List<ChatHistory.Turn> turns = history.readTurns();
            assertTrue(turns.isEmpty());
        }
    }

    @Nested
    @DisplayName("Resume handling")
    class ResumeHandling {

        @Test
        void resumeAddsMarker() throws IOException {
            // First open creates the file
            history.open("http://localhost:8080", "claude", false);
            history.logUserMessage("First session");
            history.close();

            // Second open resumes
            history = new ChatHistory(sessionId);
            history.open("http://localhost:8080", "claude", false);
            history.logUserMessage("After resume");
            history.close();

            String content = history.readTranscript();
            assertTrue(content.contains("[resumed"));
            assertTrue(content.contains("After resume"));
        }
    }

    @Nested
    @DisplayName("Null-safe writing")
    class NullSafe {

        @Test
        void logMethodsNoOpWhenNotOpened() {
            // These should not throw even when writer is null
            assertDoesNotThrow(() -> history.logUserMessage("test"));
            assertDoesNotThrow(() -> history.logAssistantMessage("test", 0, 0));
            assertDoesNotThrow(() -> history.logSystem("test"));
            assertDoesNotThrow(() -> history.logToolCall("read", false, 10));
            assertDoesNotThrow(() -> history.logAgentResponse("claude", "test", 100));
            assertDoesNotThrow(() -> history.logSubagent("explorer", "desc", 100, false));
            assertDoesNotThrow(() -> history.logTodoEvent("create", "1", "task"));
            assertDoesNotThrow(() -> history.logAgenticStep(1, 10, 2));
        }
    }

    @Nested
    @DisplayName("Transcript file path")
    class TranscriptFilePath {

        @Test
        void transcriptFilePathContainsSessionId() {
            assertTrue(history.getTranscriptFile().toString().contains(sessionId));
            assertTrue(history.getTranscriptFile().toString().endsWith(".txt"));
        }
    }
}
