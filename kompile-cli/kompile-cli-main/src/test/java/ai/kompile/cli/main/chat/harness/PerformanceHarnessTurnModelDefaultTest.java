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
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a defect where {@link PerformanceHarness#turnMetrics()} left the turn's
 * model unset whenever the caller has no per-agent override to carry — the normal case for
 * every turn. {@link ModelRouter#onTurnComplete} keys its rolling-score and cooldown maps by
 * model using a {@code ConcurrentHashMap}, which throws on a null key, so every such turn threw
 * inside the harness's async executor. The catch block there swallows the exception unless
 * verbose logging is on, so the turn silently never reached {@code store.flush()} or the
 * auto-swap logic.
 */
class PerformanceHarnessTurnModelDefaultTest {

    @TempDir
    Path tempDir;

    @Test
    void turnMetricsDefaultsModelFromChatConfigWhenCallerLeavesItUnset() {
        ObjectMapper mapper = new ObjectMapper();
        JudgeBackend backend = new JudgeBackend() {
            @Override public String generate(String userPrompt, String systemPrompt) {
                return "{\"correctness\":5,\"completeness\":5,"
                        + "\"design_quality\":null,\"thinking_coherence\":null,"
                        + "\"reasoning\":\"Matches the request.\"}";
            }
            @Override public boolean isAvailable() { return true; }
        };
        PerformanceHarness harness = new PerformanceHarness(
                null,
                new ChatConfig("custom", null, "worker-model", "http://unused.invalid"),
                mapper,
                new TerminalRenderer(false),
                new ChatSessionMetrics("model-default-test"),
                null,
                backend);
        harness.setJudgeGlobalEnabled(true);
        harness.getConfig().setJudgeEnabled(true);
        harness.getConfig().setEscapeDetectionEnabled(false);
        harness.getConfig().setThinkingAnalysisEnabled(false);
        harness.getConfig().setAutoSwapEnabled(true);
        // Matches the real default (persistCrossSession=true) so this test exercises the
        // actual store.flush() call rather than skipping it. Redirecting to an isolated
        // temp file (instead of ~/.kompile/perf-data.json) keeps that flush 100% safe.
        harness.getConfig().setPersistCrossSession(true);
        harness.getStore().loadFromFile(tempDir.resolve("perf-data.json"));

        // Mirrors AgenticChatLoop's real call site for a turn with no per-agent model
        // override: no explicit .model(...) on the builder returned by turnMetrics().
        harness.evaluateTurnAsync(harness.turnMetrics()
                .sessionId("model-default-session")
                .agentName("coder")
                .taskPrompt("Answer two plus two")
                .agentOutput("Four.")
                .build());
        harness.shutdown();

        ModelPerformanceRecord record =
                harness.getStore().findLatestRecord("model-default-session", "coder");
        assertEquals("worker-model", record.getModel(),
                "turnMetrics() must default the model from chatConfig so ModelRouter never "
                        + "sees a null model key");
        assertTrue(record.getQualityScore() > 0,
                "recomputeComposite must run to completion instead of throwing inside "
                        + "ModelRouter.onTurnComplete and being swallowed by the async catch");
        assertFalse(harness.getStore().isDirty(),
                "store.flush() must actually run and clear the dirty flag — proves "
                        + "recomputeComposite reached the flush call instead of throwing "
                        + "inside ModelRouter.onTurnComplete on the null model key");
        assertTrue(Files.exists(tempDir.resolve("perf-data.json")),
                "a real flush to the redirected temp file must have happened");
    }
}
