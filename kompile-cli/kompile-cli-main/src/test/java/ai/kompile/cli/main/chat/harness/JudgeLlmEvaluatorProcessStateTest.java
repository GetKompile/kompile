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

import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeLlmEvaluatorProcessStateTest {

    @Test
    void quotaTextResponseMarksJudgeWatcherFailed() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager(
                "judge-state-test", Files.createTempDirectory("kompile-judge-state-"))) {
            QuotaResponseBackend backend = new QuotaResponseBackend();
            JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                    backend, new ObjectMapper(), processes);

            TurnMetrics metrics = TurnMetrics.builder()
                    .sessionId("judge-state-test")
                    .agentName("test-agent")
                    .model("test-model")
                    .agentOutput("The agent produced a response.")
                    .build();

            JudgeDimensions result = evaluator.evaluate(metrics, "general", "test-agent");

            assertTrue(result.isError(), "quota text must be reported as a judge error");
            BackgroundProcessManager.ProcessEntry entry = processes.listAll().stream()
                    .filter(candidate -> candidate.getKind() == BackgroundProcessManager.ProcessKind.JUDGE)
                    .findFirst()
                    .orElseThrow();
            assertEquals(BackgroundProcessManager.ProcessState.FAILED, entry.getState(),
                    "an exhausted judge must not remain RUNNING");
            assertEquals(1, backend.calls.get(),
                    "quota text is a backend failure, not malformed JSON needing repair");
            evaluator.close();
        }
    }

    @Test
    void malformedQualityVerdictGetsOneRepairAndCanRecover() {
        AtomicInteger calls = new AtomicInteger();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                return calls.getAndIncrement() == 0
                        ? "{\"correctness\": context}"
                        : "{\"correctness\":5,\"completeness\":5,"
                        + "\"design_quality\":null,\"thinking_coherence\":null,"
                        + "\"reasoning\":\"repaired\"}";
            }

            @Override public boolean isAvailable() { return true; }
        };
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(backend, new ObjectMapper());
        TurnMetrics metrics = TurnMetrics.builder()
                .agentOutput("A correct and complete response.")
                .build();

        JudgeDimensions result = evaluator.evaluate(metrics, "general", "test-agent");

        assertFalse(result.isError());
        assertEquals(2, calls.get());
        evaluator.close();
    }

    @Test
    void qualityJudgeReceivesTheOriginalUserRequest() {
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        JudgeBackend backend = new JudgeBackend() {
            @Override
            public String generate(String userPrompt, String systemPrompt) {
                receivedPrompt.set(userPrompt);
                return "{\"correctness\":5,\"completeness\":5,"
                        + "\"design_quality\":null,\"thinking_coherence\":null,"
                        + "\"reasoning\":\"complete\"}";
            }

            @Override public boolean isAvailable() { return true; }
        };
        JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(backend, new ObjectMapper());
        TurnMetrics metrics = TurnMetrics.builder()
                .taskPrompt("Fix judge feedback delivery")
                .agentOutput("Implemented and tested the feedback path.")
                .build();

        JudgeDimensions result = evaluator.evaluate(metrics, "general", "test-agent");

        assertFalse(result.isError());
        assertTrue(receivedPrompt.get().contains("[USER REQUEST]"));
        assertTrue(receivedPrompt.get().contains("Fix judge feedback delivery"));
        evaluator.close();
    }

    private static final class QuotaResponseBackend implements JudgeBackend {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String generate(String userPrompt, String systemPrompt) {
            calls.incrementAndGet();
            return "You've hit your limit for the current session.";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describe() {
            return "quota-response-test";
        }
    }
}
