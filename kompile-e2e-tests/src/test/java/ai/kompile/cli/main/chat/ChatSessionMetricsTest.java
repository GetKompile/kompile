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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatSessionMetrics")
class ChatSessionMetricsTest {

    private ChatSessionMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ChatSessionMetrics("test-session");
    }

    @Nested
    @DisplayName("Turn tracking")
    class TurnTracking {

        @Test
        void initialCountsAreZero() {
            assertEquals(0, metrics.getUserTurns());
            assertEquals(0, metrics.getAssistantTurns());
            assertEquals(0, metrics.getTotalTurns());
        }

        @Test
        void recordUserTurnIncrements() {
            metrics.recordUserTurn("Hello");
            metrics.recordUserTurn("World");
            assertEquals(2, metrics.getUserTurns());
        }

        @Test
        void recordAssistantTurnIncrements() {
            metrics.recordAssistantTurn("Response 1", 100);
            metrics.recordAssistantTurn("Response 2", 200);
            assertEquals(2, metrics.getAssistantTurns());
        }

        @Test
        void totalTurnsIsSumOfUserAndAssistant() {
            metrics.recordUserTurn("Q1");
            metrics.recordAssistantTurn("A1", 100);
            metrics.recordUserTurn("Q2");
            assertEquals(3, metrics.getTotalTurns());
        }

        @Test
        void recordSystemEventIncrements() {
            metrics.recordSystemEvent();
            metrics.recordSystemEvent();
            // System events don't affect getTotalTurns (only user + assistant)
            assertEquals(0, metrics.getTotalTurns());
        }
    }

    @Nested
    @DisplayName("Token tracking")
    class TokenTracking {

        @Test
        void estimatedTokensFromChars() {
            metrics.recordUserTurn("Hello World!"); // 12 chars -> 3 estimated tokens
            assertEquals(3, metrics.getEstimatedInputTokens());
        }

        @Test
        void actualTokenUsage() {
            assertFalse(metrics.hasActualTokenCounts());
            metrics.recordTokenUsage(100, 50, 10, 5);
            assertTrue(metrics.hasActualTokenCounts());
            assertEquals(100, metrics.getInputTokens());
            assertEquals(50, metrics.getOutputTokens());
            assertEquals(150, metrics.getTotalTokens());
            assertEquals(10, metrics.getCacheReadTokens());
            assertEquals(5, metrics.getCacheCreationTokens());
        }

        @Test
        void tokenUsageAccumulates() {
            metrics.recordTokenUsage(100, 50, 0, 0);
            metrics.recordTokenUsage(200, 75, 0, 0);
            assertEquals(300, metrics.getInputTokens());
            assertEquals(125, metrics.getOutputTokens());
        }

        @Test
        void negativeTokensIgnored() {
            metrics.recordTokenUsage(-1, -1, -1, -1);
            assertEquals(0, metrics.getInputTokens());
            assertEquals(0, metrics.getOutputTokens());
        }
    }

    @Nested
    @DisplayName("Timing")
    class Timing {

        @Test
        void apiLatencyAccumulates() {
            metrics.recordAssistantTurn("r1", 100);
            metrics.recordAssistantTurn("r2", 200);
            assertEquals(300, metrics.getTotalApiLatencyMs());
            assertEquals(2, metrics.getApiCalls());
        }

        @Test
        void averageResponseTime() {
            metrics.recordAssistantTurn("r1", 100);
            metrics.recordAssistantTurn("r2", 300);
            assertEquals(200.0, metrics.getAvgResponseTimeMs(), 0.01);
        }

        @Test
        void averageResponseTimeZeroWhenNoCalls() {
            assertEquals(0.0, metrics.getAvgResponseTimeMs());
        }
    }

    @Nested
    @DisplayName("Tool tracking")
    class ToolTracking {

        @Test
        void recordToolCallCounts() {
            metrics.recordToolCall("read", false, 10);
            metrics.recordToolCall("read", false, 20);
            metrics.recordToolCall("bash", true, 30);

            assertEquals(3, metrics.getTotalToolCalls());
            assertEquals(1, metrics.getTotalToolErrors());
        }

        @Test
        void toolCallBreakdown() {
            metrics.recordToolCall("read", false, 10);
            metrics.recordToolCall("read", false, 20);
            metrics.recordToolCall("bash", false, 30);

            Map<String, java.util.concurrent.atomic.AtomicInteger> counts = metrics.getToolCallCounts();
            assertEquals(2, counts.get("read").get());
            assertEquals(1, counts.get("bash").get());
        }

        @Test
        void topToolsSortedByCount() {
            metrics.recordToolCall("read", false, 10);
            metrics.recordToolCall("read", false, 10);
            metrics.recordToolCall("read", false, 10);
            metrics.recordToolCall("bash", false, 10);
            metrics.recordToolCall("bash", false, 10);
            metrics.recordToolCall("edit", false, 10);

            List<Map.Entry<String, Integer>> top = metrics.getTopTools(2);
            assertEquals(2, top.size());
            assertEquals("read", top.get(0).getKey());
            assertEquals(3, top.get(0).getValue());
            assertEquals("bash", top.get(1).getKey());
            assertEquals(2, top.get(1).getValue());
        }

        @Test
        void topToolsWithFewerThanN() {
            metrics.recordToolCall("read", false, 10);
            List<Map.Entry<String, Integer>> top = metrics.getTopTools(5);
            assertEquals(1, top.size());
        }
    }

    @Nested
    @DisplayName("Agentic and RAG tracking")
    class AgenticAndRag {

        @Test
        void agenticSteps() {
            metrics.recordAgenticStep();
            metrics.recordAgenticStep();
            assertEquals(2, metrics.getAgenticSteps());
        }

        @Test
        void compactionEvents() {
            metrics.recordCompaction(10000, 5000);
            assertEquals(1, metrics.getCompactionEvents());
        }

        @Test
        void ragQueries() {
            metrics.recordRagQuery(3);
            metrics.recordRagQuery(5);
            assertEquals(2, metrics.getRagQueries());
            assertEquals(8, metrics.getDocumentsRetrieved());
        }
    }

    @Nested
    @DisplayName("Queue tracking")
    class QueueTracking {

        @Test
        void queueMetrics() {
            metrics.recordMessageQueued();
            metrics.recordMessageQueued();
            metrics.recordMessageAutoDequeued();
            metrics.recordTaskBackgrounded();

            assertEquals(2, metrics.getMessagesQueued());
            assertEquals(1, metrics.getMessagesAutoDequeued());
            assertEquals(1, metrics.getTasksBackgrounded());
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        void toJsonContainsAllSections() {
            metrics.setProvider("openai");
            metrics.setModel("gpt-4");
            metrics.setAgentName("coder");
            metrics.setRagEnabled(true);
            metrics.recordUserTurn("Hello");
            metrics.recordAssistantTurn("Hi", 100);
            metrics.recordToolCall("read", false, 10);
            metrics.recordAgenticStep();
            metrics.recordRagQuery(3);

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = metrics.toJson(mapper);

            assertTrue(json.has("session"));
            assertTrue(json.has("turns"));
            assertTrue(json.has("tokens"));
            assertTrue(json.has("timing"));
            assertTrue(json.has("tools"));
            assertTrue(json.has("agentic"));
            assertTrue(json.has("rag"));

            assertEquals("test-session", json.path("session").path("sessionId").asText());
            assertEquals("openai", json.path("session").path("provider").asText());
            assertEquals(1, json.path("turns").path("user").asInt());
            assertEquals(1, json.path("turns").path("assistant").asInt());
        }

        @Test
        void queueSectionOmittedWhenNoQueueActivity() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = metrics.toJson(mapper);
            assertFalse(json.has("queue"));
        }

        @Test
        void queueSectionIncludedWhenActive() {
            metrics.recordMessageQueued();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = metrics.toJson(mapper);
            assertTrue(json.has("queue"));
            assertEquals(1, json.path("queue").path("messagesQueued").asInt());
        }

        @Test
        void ragSectionOmittedWhenDisabledAndNoQueries() {
            metrics.setRagEnabled(false);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode json = metrics.toJson(mapper);
            assertFalse(json.has("rag"));
        }
    }

    @Nested
    @DisplayName("Save to file")
    class SaveToFile {

        @Test
        void savesJsonToFile(@TempDir Path tempDir) {
            metrics.recordUserTurn("Hello");
            metrics.recordAssistantTurn("Hi", 100);

            Path metricsFile = tempDir.resolve("metrics.json");
            metrics.saveToFile(metricsFile, new ObjectMapper());

            assertTrue(Files.exists(metricsFile));
        }

        @Test
        void savedFileIsValidJson(@TempDir Path tempDir) throws Exception {
            metrics.recordUserTurn("Hello");
            Path metricsFile = tempDir.resolve("metrics.json");
            metrics.saveToFile(metricsFile, new ObjectMapper());

            String content = Files.readString(metricsFile);
            ObjectMapper mapper = new ObjectMapper();
            assertDoesNotThrow(() -> mapper.readTree(content));
        }
    }

    @Nested
    @DisplayName("Duration formatting")
    class DurationFormatting {

        @Test
        void formatSeconds() {
            assertEquals("30s", metrics.formatDuration(Duration.ofSeconds(30)));
        }

        @Test
        void formatMinutesAndSeconds() {
            assertEquals("2m 30s", metrics.formatDuration(Duration.ofSeconds(150)));
        }

        @Test
        void formatHoursMinutesSeconds() {
            assertEquals("1h 5m 30s", metrics.formatDuration(Duration.ofSeconds(3930)));
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        void concurrentTurnRecordingIsThreadSafe() throws InterruptedException {
            int threads = 10;
            int turnsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < turnsPerThread; i++) {
                            metrics.recordUserTurn("msg");
                            metrics.recordAssistantTurn("resp", 10);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(threads * turnsPerThread, metrics.getUserTurns());
            assertEquals(threads * turnsPerThread, metrics.getAssistantTurns());
        }

        @Test
        void concurrentToolRecordingIsThreadSafe() throws InterruptedException {
            int threads = 10;
            int callsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < callsPerThread; i++) {
                            metrics.recordToolCall("read", false, 5);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(threads * callsPerThread, metrics.getTotalToolCalls());
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        void sessionProperties() {
            assertEquals("test-session", metrics.getSessionId());
            assertNotNull(metrics.getSessionStart());
            assertNotNull(metrics.getSessionDuration());
        }

        @Test
        void providerAndModel() {
            metrics.setProvider("anthropic");
            metrics.setModel("claude-3-5-sonnet");
            assertEquals("anthropic", metrics.getProvider());
            assertEquals("claude-3-5-sonnet", metrics.getModel());
        }
    }
}
