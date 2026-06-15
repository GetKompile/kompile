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

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChatSessionMetrics}.
 * <p>
 * Tests turn recording, token tracking, tool tracking, agentic steps,
 * RAG tracking, queue tracking, performance harness, role changes,
 * session outcome, formatted output, getTopTools, and JSON serialization.
 */
class ChatSessionMetricsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ChatSessionMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new ChatSessionMetrics("test-session-001");
    }

    // ===================================================================
    // Session identity
    // ===================================================================

    @Nested
    class SessionIdentity {

        @Test
        void sessionId_setFromConstructor() {
            assertEquals("test-session-001", metrics.getSessionId());
        }

        @Test
        void sessionStart_isRecentInstant() {
            Instant start = metrics.getSessionStart();
            assertNotNull(start);
            assertTrue(Duration.between(start, Instant.now()).toMillis() < 5000);
        }

        @Test
        void sessionDuration_isPositive() {
            Duration d = metrics.getSessionDuration();
            assertNotNull(d);
            assertTrue(d.toMillis() >= 0);
        }
    }

    // ===================================================================
    // Configuration
    // ===================================================================

    @Nested
    class Configuration {

        @Test
        void providerSetterGetter() {
            metrics.setProvider("anthropic");
            assertEquals("anthropic", metrics.getProvider());
        }

        @Test
        void modelSetterGetter() {
            metrics.setModel("claude-opus-4-6");
            assertEquals("claude-opus-4-6", metrics.getModel());
        }

        @Test
        void agentName_set() {
            metrics.setAgentName("claude");
            // No getter for agentName but verified in toJson
        }

        @Test
        void ragEnabled_set() {
            metrics.setRagEnabled(true);
            // Verified in toJson
        }
    }

    // ===================================================================
    // Turn tracking
    // ===================================================================

    @Nested
    class TurnTracking {

        @Test
        void initialState_zeroTurns() {
            assertEquals(0, metrics.getUserTurns());
            assertEquals(0, metrics.getAssistantTurns());
            assertEquals(0, metrics.getTotalTurns());
        }

        @Test
        void recordUserTurn_incrementsCount() {
            metrics.recordUserTurn("Hello, fix this bug");
            metrics.recordUserTurn("Thanks");

            assertEquals(2, metrics.getUserTurns());
        }

        @Test
        void recordAssistantTurn_incrementsCountAndTiming() {
            metrics.recordAssistantTurn("Here is the fix...", 1500);
            metrics.recordAssistantTurn("Done.", 500);

            assertEquals(2, metrics.getAssistantTurns());
            assertEquals(2000, metrics.getTotalApiLatencyMs());
            assertEquals(2, metrics.getApiCalls());
        }

        @Test
        void totalTurns_sumOfUserAndAssistant() {
            metrics.recordUserTurn("msg1");
            metrics.recordAssistantTurn("resp1", 100);
            metrics.recordUserTurn("msg2");
            metrics.recordAssistantTurn("resp2", 200);

            assertEquals(4, metrics.getTotalTurns());
        }

        @Test
        void recordSystemEvent_increments() {
            metrics.recordSystemEvent();
            metrics.recordSystemEvent();
            // No public getter but verified in toJson
        }

        @Test
        void avgResponseTime_calculated() {
            metrics.recordAssistantTurn("resp1", 1000);
            metrics.recordAssistantTurn("resp2", 3000);

            assertEquals(2000.0, metrics.getAvgResponseTimeMs(), 0.001);
        }

        @Test
        void avgResponseTime_noCallsReturnsZero() {
            assertEquals(0.0, metrics.getAvgResponseTimeMs(), 0.001);
        }

        @Test
        void estimatedTokens_fromCharLength() {
            metrics.recordUserTurn("A".repeat(400)); // 100 estimated tokens
            metrics.recordAssistantTurn("B".repeat(800), 100); // 200 estimated tokens

            assertEquals(100, metrics.getEstimatedInputTokens());
            assertEquals(200, metrics.getEstimatedOutputTokens());
        }
    }

    // ===================================================================
    // Token tracking
    // ===================================================================

    @Nested
    class TokenTracking {

        @Test
        void initialState_noActualTokens() {
            assertFalse(metrics.hasActualTokenCounts());
            assertEquals(0, metrics.getInputTokens());
            assertEquals(0, metrics.getOutputTokens());
        }

        @Test
        void recordTokenUsage_accumulates() {
            metrics.recordTokenUsage(1000, 500, 200, 50);
            metrics.recordTokenUsage(2000, 1000, 300, 100);

            assertTrue(metrics.hasActualTokenCounts());
            assertEquals(3000, metrics.getInputTokens());
            assertEquals(1500, metrics.getOutputTokens());
            assertEquals(500, metrics.getCacheReadTokens());
            assertEquals(150, metrics.getCacheCreationTokens());
            assertEquals(4500, metrics.getTotalTokens());
        }

        @Test
        void negativeTokens_ignored() {
            metrics.recordTokenUsage(-100, -50, -10, -5);

            assertEquals(0, metrics.getInputTokens());
            assertEquals(0, metrics.getOutputTokens());
        }
    }

    // ===================================================================
    // Tool tracking
    // ===================================================================

    @Nested
    class ToolTracking {

        @Test
        void initialState_noToolCalls() {
            assertEquals(0, metrics.getTotalToolCalls());
            assertEquals(0, metrics.getTotalToolErrors());
        }

        @Test
        void recordToolCall_incrementsCounts() {
            metrics.recordToolCall("Bash", false, 500);
            metrics.recordToolCall("Read", false, 200);
            metrics.recordToolCall("Bash", true, 100);

            assertEquals(3, metrics.getTotalToolCalls());
            assertEquals(1, metrics.getTotalToolErrors());
        }

        @Test
        void toolCallCounts_breakdownByName() {
            metrics.recordToolCall("Bash", false, 100);
            metrics.recordToolCall("Bash", false, 200);
            metrics.recordToolCall("Read", false, 50);

            Map<String, java.util.concurrent.atomic.AtomicInteger> counts = metrics.getToolCallCounts();
            assertEquals(2, counts.get("Bash").get());
            assertEquals(1, counts.get("Read").get());
        }
    }

    // ===================================================================
    // Agentic loop tracking
    // ===================================================================

    @Nested
    class AgenticLoopTracking {

        @Test
        void initialState_zeroSteps() {
            assertEquals(0, metrics.getAgenticSteps());
            assertEquals(0, metrics.getCompactionEvents());
        }

        @Test
        void recordAgenticStep_increments() {
            metrics.recordAgenticStep();
            metrics.recordAgenticStep();
            metrics.recordAgenticStep();

            assertEquals(3, metrics.getAgenticSteps());
        }

        @Test
        void recordCompaction_increments() {
            metrics.recordCompaction(50000, 25000);
            metrics.recordCompaction(30000, 15000);

            assertEquals(2, metrics.getCompactionEvents());
        }
    }

    // ===================================================================
    // RAG tracking
    // ===================================================================

    @Nested
    class RagTracking {

        @Test
        void initialState_noQueries() {
            assertEquals(0, metrics.getRagQueries());
            assertEquals(0, metrics.getDocumentsRetrieved());
        }

        @Test
        void recordRagQuery_accumulates() {
            metrics.recordRagQuery(5);
            metrics.recordRagQuery(3);

            assertEquals(2, metrics.getRagQueries());
            assertEquals(8, metrics.getDocumentsRetrieved());
        }
    }

    // ===================================================================
    // Queue tracking
    // ===================================================================

    @Nested
    class QueueTracking {

        @Test
        void initialState_zeros() {
            assertEquals(0, metrics.getMessagesQueued());
            assertEquals(0, metrics.getMessagesAutoDequeued());
            assertEquals(0, metrics.getTasksBackgrounded());
        }

        @Test
        void recordQueueEvents() {
            metrics.recordMessageQueued();
            metrics.recordMessageQueued();
            metrics.recordMessageAutoDequeued();
            metrics.recordTaskBackgrounded();

            assertEquals(2, metrics.getMessagesQueued());
            assertEquals(1, metrics.getMessagesAutoDequeued());
            assertEquals(1, metrics.getTasksBackgrounded());
        }
    }

    // ===================================================================
    // Performance harness tracking
    // ===================================================================

    @Nested
    class PerformanceHarness {

        @Test
        void recordEscape_incrementsCountAndType() {
            metrics.recordEscape("EMPTY_OUTPUT");
            metrics.recordEscape("TOOL_LOOP");
            metrics.recordEscape("EMPTY_OUTPUT");

            assertEquals(3, metrics.getEscapeCount());
            Map<String, java.util.concurrent.atomic.AtomicInteger> byType = metrics.getEscapesByType();
            assertEquals(2, byType.get("EMPTY_OUTPUT").get());
            assertEquals(1, byType.get("TOOL_LOOP").get());
        }

        @Test
        void recordEscape_nullType_countedButNotTyped() {
            metrics.recordEscape(null);

            assertEquals(1, metrics.getEscapeCount());
            assertTrue(metrics.getEscapesByType().isEmpty());
        }

        @Test
        void recordEscape_blankType_countedButNotTyped() {
            metrics.recordEscape("  ");

            assertEquals(1, metrics.getEscapeCount());
            assertTrue(metrics.getEscapesByType().isEmpty());
        }

        @Test
        void recordJudgeCall() {
            metrics.recordJudgeCall();
            metrics.recordJudgeCall();

            assertEquals(2, metrics.getJudgeCallCount());
        }

        @Test
        void recordModelSwap() {
            metrics.recordModelSwap();
            assertEquals(1, metrics.getModelSwapCount());
        }

        @Test
        void recordSubagentSpawned() {
            metrics.recordSubagentSpawned();
            metrics.recordSubagentSpawned();

            assertEquals(2, metrics.getSubagentsSpawned());
        }

        @Test
        void recordThinkingTokens_accumulates() {
            metrics.recordThinkingTokens(500);
            metrics.recordThinkingTokens(300);

            assertEquals(800, metrics.getThinkingTokens());
        }

        @Test
        void recordThinkingTokens_negativeIgnored() {
            metrics.recordThinkingTokens(-100);
            assertEquals(0, metrics.getThinkingTokens());
        }

        @Test
        void recordQualityScore_accumulates() {
            metrics.recordQualityScore("claude-opus", 0.9);
            metrics.recordQualityScore("claude-opus", 0.8);
            metrics.recordQualityScore("gpt-4", 0.7);

            Map<String, List<Double>> scores = metrics.getQualityScoresByModel();
            assertEquals(2, scores.get("claude-opus").size());
            assertEquals(1, scores.get("gpt-4").size());
        }

        @Test
        void recordQualityScore_negativeIgnored() {
            metrics.recordQualityScore("model", -0.5);
            assertTrue(metrics.getQualityScoresByModel().isEmpty());
        }

        @Test
        void recordQualityScore_nullModelIgnored() {
            metrics.recordQualityScore(null, 0.5);
            assertTrue(metrics.getQualityScoresByModel().isEmpty());
        }

        @Test
        void avgScoreByModel_calculated() {
            metrics.recordQualityScore("claude", 0.8);
            metrics.recordQualityScore("claude", 1.0);
            metrics.recordQualityScore("gemini", 0.6);

            Map<String, Double> avgs = metrics.getAvgScoreByModel();
            assertEquals(0.9, avgs.get("claude"), 0.001);
            assertEquals(0.6, avgs.get("gemini"), 0.001);
        }
    }

    // ===================================================================
    // Role tracking
    // ===================================================================

    @Nested
    class RoleTracking {

        @Test
        void initialState_noRole() {
            assertNull(metrics.getActiveRole());
            assertTrue(metrics.getRoleChanges().isEmpty());
        }

        @Test
        void setActiveRole_recordsChange() {
            metrics.setActiveRole("architect");
            metrics.setActiveRole("reviewer");

            assertEquals("reviewer", metrics.getActiveRole());
            List<ChatSessionMetrics.RoleChangeEvent> changes = metrics.getRoleChanges();
            assertEquals(2, changes.size());
            assertEquals("architect", changes.get(0).newRole);
            assertNull(changes.get(0).oldRole);
            assertEquals("reviewer", changes.get(1).newRole);
            assertEquals("architect", changes.get(1).oldRole);
        }

        @Test
        void setActiveRole_null_noChange() {
            metrics.setActiveRole(null);
            assertTrue(metrics.getRoleChanges().isEmpty());
        }

        @Test
        void roleChanges_areUnmodifiable() {
            metrics.setActiveRole("dev");
            assertThrows(UnsupportedOperationException.class,
                    () -> metrics.getRoleChanges().add(null));
        }
    }

    // ===================================================================
    // Session outcome
    // ===================================================================

    @Nested
    class SessionOutcome {

        @Test
        void initialState_noOutcome() {
            assertNull(metrics.getSessionOutcome());
            assertNull(metrics.getOutcomeReason());
        }

        @Test
        void setOutcome() {
            metrics.setSessionOutcome(TaskOutcome.COMPLETED, "All tests pass");

            assertEquals(TaskOutcome.COMPLETED, metrics.getSessionOutcome());
            assertEquals("All tests pass", metrics.getOutcomeReason());
        }

        @Test
        void taskPrompt_setAndGet() {
            metrics.setTaskPrompt("Fix the login bug");
            assertEquals("Fix the login bug", metrics.getTaskPrompt());
        }
    }

    // ===================================================================
    // getTopTools()
    // ===================================================================

    @Nested
    class TopTools {

        @Test
        void emptyTools_returnsEmptyList() {
            assertTrue(metrics.getTopTools(5).isEmpty());
        }

        @Test
        void topTools_sortedByCount() {
            metrics.recordToolCall("Bash", false, 100);
            metrics.recordToolCall("Bash", false, 100);
            metrics.recordToolCall("Bash", false, 100);
            metrics.recordToolCall("Read", false, 50);
            metrics.recordToolCall("Read", false, 50);
            metrics.recordToolCall("Grep", false, 30);

            List<Map.Entry<String, Integer>> top = metrics.getTopTools(2);

            assertEquals(2, top.size());
            assertEquals("Bash", top.get(0).getKey());
            assertEquals(3, top.get(0).getValue());
            assertEquals("Read", top.get(1).getKey());
            assertEquals(2, top.get(1).getValue());
        }

        @Test
        void topTools_nLargerThanTools_returnsAll() {
            metrics.recordToolCall("Bash", false, 100);

            List<Map.Entry<String, Integer>> top = metrics.getTopTools(10);
            assertEquals(1, top.size());
        }
    }

    // ===================================================================
    // formatDuration()
    // ===================================================================

    @Nested
    class FormatDuration {

        @Test
        void secondsOnly() {
            assertEquals("45s", metrics.formatDuration(Duration.ofSeconds(45)));
        }

        @Test
        void minutesAndSeconds() {
            assertEquals("2m 30s", metrics.formatDuration(Duration.ofSeconds(150)));
        }

        @Test
        void hoursMinutesSeconds() {
            assertEquals("1h 5m 30s", metrics.formatDuration(Duration.ofSeconds(3930)));
        }

        @Test
        void zeroSeconds() {
            assertEquals("0s", metrics.formatDuration(Duration.ZERO));
        }
    }

    // ===================================================================
    // toJson()
    // ===================================================================

    @Nested
    class ToJson {

        @Test
        void minimalSession_includesRequiredFields() {
            ObjectNode json = metrics.toJson(MAPPER);

            assertTrue(json.has("session"));
            assertTrue(json.has("turns"));
            assertTrue(json.has("tokens"));
            assertTrue(json.has("timing"));
            assertTrue(json.has("tools"));
            assertTrue(json.has("agentic"));

            assertEquals("test-session-001", json.get("session").get("sessionId").asText());
        }

        @Test
        void fullSession_includesAllFields() {
            metrics.setProvider("anthropic");
            metrics.setModel("claude-opus");
            metrics.setAgentName("claude");
            metrics.setRagEnabled(true);
            metrics.recordUserTurn("Hello");
            metrics.recordAssistantTurn("Hi there!", 500);
            metrics.recordTokenUsage(1000, 500, 200, 50);
            metrics.recordToolCall("Bash", false, 100);
            metrics.recordAgenticStep();
            metrics.recordRagQuery(3);
            metrics.recordMessageQueued();
            metrics.recordTaskBackgrounded();
            metrics.setSessionOutcome(TaskOutcome.COMPLETED, "Done");
            metrics.setTaskPrompt("Fix the bug");
            metrics.recordEscape("TOOL_LOOP");
            metrics.recordQualityScore("claude-opus", 0.95);
            metrics.recordJudgeCall();
            metrics.recordModelSwap();
            metrics.recordThinkingTokens(200);
            metrics.recordSubagentSpawned();

            ObjectNode json = metrics.toJson(MAPPER);

            // Session
            assertEquals("anthropic", json.get("session").get("provider").asText());
            assertEquals("claude-opus", json.get("session").get("model").asText());
            assertEquals("claude", json.get("session").get("agent").asText());
            assertTrue(json.get("session").get("ragEnabled").asBoolean());

            // Turns
            assertEquals(1, json.get("turns").get("user").asInt());
            assertEquals(1, json.get("turns").get("assistant").asInt());

            // Tokens
            assertEquals(1000, json.get("tokens").get("input").asLong());
            assertEquals(500, json.get("tokens").get("output").asLong());

            // Tools
            assertEquals(1, json.get("tools").get("totalCalls").asInt());
            assertTrue(json.get("tools").get("breakdown").has("Bash"));

            // RAG
            assertTrue(json.has("rag"));
            assertEquals(1, json.get("rag").get("queries").asInt());

            // Queue
            assertTrue(json.has("queue"));
            assertEquals(1, json.get("queue").get("messagesQueued").asInt());

            // Outcome
            assertTrue(json.has("outcome"));
            assertEquals("COMPLETED", json.get("outcome").get("outcome").asText());
            assertEquals("Fix the bug", json.get("outcome").get("taskPrompt").asText());

            // Escapes
            assertTrue(json.has("escapes"));
            assertEquals(1, json.get("escapes").get("totalEscapes").asInt());

            // Quality scores
            assertTrue(json.has("qualityScores"));
            assertEquals(0.95, json.get("qualityScores").get("claude-opus").asDouble(), 0.001);

            // Agentic harness fields
            assertEquals(1, json.get("agentic").get("judgeCalls").asInt());
            assertEquals(1, json.get("agentic").get("modelSwaps").asInt());
            assertEquals(200, json.get("agentic").get("thinkingTokens").asLong());
            assertEquals(1, json.get("agentic").get("subagentsSpawned").asInt());
        }

        @Test
        void noQueueActivity_queueSectionOmitted() {
            ObjectNode json = metrics.toJson(MAPPER);
            assertFalse(json.has("queue"));
        }

        @Test
        void noRag_ragSectionOmitted() {
            ObjectNode json = metrics.toJson(MAPPER);
            assertFalse(json.has("rag"));
        }

        @Test
        void ragEnabled_ragSectionPresent() {
            metrics.setRagEnabled(true);
            ObjectNode json = metrics.toJson(MAPPER);
            assertTrue(json.has("rag"));
        }

        @Test
        void noEscapes_escapesSectionOmitted() {
            ObjectNode json = metrics.toJson(MAPPER);
            assertFalse(json.has("escapes"));
        }

        @Test
        void noOutcome_outcomeSectionOmitted() {
            ObjectNode json = metrics.toJson(MAPPER);
            assertFalse(json.has("outcome"));
        }
    }

    // ===================================================================
    // Inner classes
    // ===================================================================

    @Nested
    class InnerClasses {

        @Test
        void turnMetric_allFields() {
            Instant now = Instant.now();
            ChatSessionMetrics.TurnMetric tm =
                    new ChatSessionMetrics.TurnMetric(now, "assistant", 500, 1200);

            assertEquals(now, tm.timestamp);
            assertEquals("assistant", tm.role);
            assertEquals(500, tm.chars);
            assertEquals(1200, tm.durationMs);
        }

        @Test
        void roleChangeEvent_allFields() {
            Instant now = Instant.now();
            ChatSessionMetrics.RoleChangeEvent rce =
                    new ChatSessionMetrics.RoleChangeEvent("reviewer", "architect", now);

            assertEquals("reviewer", rce.newRole);
            assertEquals("architect", rce.oldRole);
            assertEquals(now, rce.timestamp);
        }
    }
}
