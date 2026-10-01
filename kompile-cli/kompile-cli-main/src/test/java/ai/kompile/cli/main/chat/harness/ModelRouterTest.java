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

import ai.kompile.cli.main.chat.config.ChatConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModelRouter#onTurnComplete} keys its rolling-score and cooldown maps by model name
 * using a {@code ConcurrentHashMap}, which throws {@code NullPointerException} on a null key.
 * A turn evaluated without a per-agent model override used to reach this method with a null
 * model and throw, silently aborting the harness's async evaluation before it could flush or
 * consider an auto-swap (see PerformanceHarnessTurnModelDefaultTest for the end-to-end path).
 * The store is passed as {@code null} throughout: a null model returns before either method
 * ever reads it.
 */
class ModelRouterTest {

    @Test
    void onTurnCompleteWithNullModelDoesNotThrow() {
        HarnessConfig config = new HarnessConfig();
        config.setAutoSwapEnabled(true);
        ModelRouter router = new ModelRouter(config, null,
                new ChatConfig("custom", null, "worker-model", "http://unused.invalid"));

        String swapped = assertDoesNotThrow(() ->
                router.onTurnComplete("coder", null, 4.5f, 100L, false));

        assertNull(swapped, "a null model carries no rolling stats to swap on");
    }

    @Test
    void onTurnCompleteWithNullModelAndRateLimitDoesNotThrow() {
        HarnessConfig config = new HarnessConfig();
        config.setAutoSwapEnabled(true);
        config.setRateLimitFallbackEnabled(true);
        ModelRouter router = new ModelRouter(config, null,
                new ChatConfig("custom", null, "worker-model", "http://unused.invalid"));

        String swapped = assertDoesNotThrow(() ->
                router.onTurnComplete("coder", null, 0f, 100L, true));

        assertNull(swapped, "a null model carries no rolling stats to swap on");
    }
}
