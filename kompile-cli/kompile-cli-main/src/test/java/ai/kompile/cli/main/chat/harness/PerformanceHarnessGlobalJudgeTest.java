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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerToolCallDecision;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceHarnessGlobalJudgeTest {

    @Test
    void globalOffSkipsExplicitInProcessJudgeBackend() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                calls.incrementAndGet();
                return "{\"action\":\"BLOCK\",\"reason\":\"must not run\"}";
            }
            @Override public boolean isAvailable() { return true; }
        };
        PerformanceHarness harness = new PerformanceHarness(
                null,
                new ChatConfig("custom", null, "worker", "http://unused.invalid"),
                mapper,
                new TerminalRenderer(false),
                new ChatSessionMetrics("global-off-test"),
                null,
                backend);
        harness.setJudgeGlobalEnabled(false);

        EnforcerToolCallDecision decision = harness.evaluateToolCall(
                "update tasks", "", "todowrite", "{\"action\":\"update\"}");

        assertTrue(decision.isAllowed());
        assertEquals(0, calls.get());
        harness.shutdown();
    }

    @Test
    void completedTurnQualityJudgingRemainsAdvisory() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                int call = calls.getAndIncrement();
                return call == 1
                        ? "{\"correctness\":5,\"completeness\":5,"
                          + "\"design_quality\":null,\"thinking_coherence\":null,"
                          + "\"reasoning\":\"The response completes the request.\"}"
                        : "{\"correctness\":1,\"completeness\":2,"
                          + "\"design_quality\":null,\"thinking_coherence\":null,"
                          + "\"reasoning\":\"The response asks for a task instead of doing it.\"}";
            }
            @Override public boolean isAvailable() { return true; }
        };
        PerformanceHarness harness = new PerformanceHarness(
                null,
                new ChatConfig("custom", null, "worker", "http://unused.invalid"),
                mapper,
                new TerminalRenderer(false),
                new ChatSessionMetrics("feedback-test"),
                null,
                backend);
        harness.setJudgeGlobalEnabled(true);
        harness.getConfig().setJudgeEnabled(true);
        harness.getConfig().setEscapeDetectionEnabled(false);
        harness.getConfig().setThinkingAnalysisEnabled(false);
        harness.getConfig().setAutoSwapEnabled(false);
        harness.getConfig().setPersistCrossSession(false);
        harness.evaluateTurnAsync(TurnMetrics.builder()
                .sessionId("low-quality")
                .agentName("coder")
                .model("worker")
                .taskPrompt("Fix the task-tool notification bug")
                .agentOutput("What task would you like me to perform?")
                .build());
        harness.evaluateTurnAsync(TurnMetrics.builder()
                .sessionId("high-quality")
                .agentName("coder")
                .model("worker")
                .taskPrompt("Answer two plus two")
                .agentOutput("Four.")
                .build());
        harness.shutdown();

        assertEquals(2, calls.get());
        ModelPerformanceRecord lowQuality =
                harness.getStore().findLatestRecord("low-quality", "coder");
        assertEquals("Fix the task-tool notification bug", lowQuality.getTaskPrompt());
        assertEquals(1.0f, lowQuality.getJudgeCorrectness());
        assertEquals(2.0f, lowQuality.getJudgeCompleteness());
        assertTrue(harness.getStore().isDirty(),
                "persistCrossSession=false must retain records in memory without flushing them");
    }
}
