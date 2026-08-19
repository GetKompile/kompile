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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeLlmEvaluatorProcessStateTest {

    @Test
    void quotaTextResponseMarksJudgeWatcherFailed() throws Exception {
        try (BackgroundProcessManager processes = new BackgroundProcessManager(
                "judge-state-test", Files.createTempDirectory("kompile-judge-state-"))) {
            JudgeLlmEvaluator evaluator = new JudgeLlmEvaluator(
                    new QuotaResponseBackend(), new ObjectMapper(), processes);

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
            evaluator.close();
        }
    }

    private static final class QuotaResponseBackend implements JudgeBackend {
        @Override
        public String generate(String userPrompt, String systemPrompt) {
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
