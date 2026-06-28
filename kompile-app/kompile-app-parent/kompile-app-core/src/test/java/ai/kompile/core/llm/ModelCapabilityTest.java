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

package ai.kompile.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that per-call char budgets are <em>derived</em> from a model's real context window and max
 * output tokens (at {@link ModelCapability#CHARS_PER_TOKEN} chars/token) — not guessed constants.
 */
class ModelCapabilityTest {

    @Test
    void budgetsDeriveFromRealModelLimits() {
        // deepseek-chat: 64k context, 8192 max output — a small-context model (≤ 200k tokens).
        // opencode-cli uses deepseek-v4-flash-free which has a 1M-token context (see large-context test).
        ModelCapability cap = new ModelCapability("deepseek-chat", 64_000, 8_192, false);

        // Ceiling = (context - output - prompt overhead) * 4 — leaves full room for the output JSON.
        int expectedMax = (64_000 - 8_192 - ModelCapability.PROMPT_OVERHEAD_TOKENS) * ModelCapability.CHARS_PER_TOKEN;
        assertEquals(expectedMax, cap.maxInputChars());

        // Small-context path: start = output budget in chars (input ≈ the model's whole output budget).
        assertEquals(8_192 * ModelCapability.CHARS_PER_TOKEN, cap.initInputChars());

        // Floor = ~the output token count, never above the ceiling.
        assertEquals(8_192, cap.minInputChars());
        assertFalse(cap.local());
    }

    @Test
    void largeContextModelGetsLargeInitBudget() {
        // DeepSeek V4 flash/pro variants have large context windows. This test constructs the
        // capability directly so it stays focused on the budget formula rather than registry lookup.
        // The large-context path (contextTokens > 200 000) starts at 10 % of maxInputChars
        // rather than the tiny output-budget heuristic (which would be just 8 192 * 4 = 32 768 chars).
        ModelCapability cap = new ModelCapability("deepseek-v4-flash-free", 1_000_000, 8_192, false);

        // maxInputChars = (1 000 000 - 8 192 - 512) * 4 = 3 965 184
        int expectedMax = (1_000_000 - 8_192 - ModelCapability.PROMPT_OVERHEAD_TOKENS) * ModelCapability.CHARS_PER_TOKEN;
        assertEquals(expectedMax, cap.maxInputChars());

        // initInputChars = 10 % of maxInputChars (large-context path) — far larger than the old 32 768.
        int expectedInit = (int) Math.max(cap.minInputChars(), Math.min(cap.maxInputChars(), (long) (expectedMax * 0.10)));
        assertEquals(expectedInit, cap.initInputChars());
        assertTrue(cap.initInputChars() > 8_192 * ModelCapability.CHARS_PER_TOKEN,
                "large-context init must exceed the small-context output-budget heuristic");
        assertTrue(cap.initInputChars() >= cap.minInputChars(), "init >= min");
        assertTrue(cap.initInputChars() <= cap.maxInputChars(), "init <= max");
    }

    @Test
    void budgetsStayOrderedEvenForTinyContextModels() {
        // Overhead + output exceed context → ceiling collapses, but ordering must hold (no negatives).
        ModelCapability cap = new ModelCapability("tiny-local", 1_000, 512, true);
        assertTrue(cap.minInputChars() <= cap.maxInputChars(), "min <= max");
        assertTrue(cap.initInputChars() >= cap.minInputChars(), "init >= min");
        assertTrue(cap.initInputChars() <= cap.maxInputChars(), "init <= max");
        assertTrue(cap.maxInputChars() >= 1, "ceiling stays positive");
        assertTrue(cap.local());
    }

    @Test
    void compactConstructorClampsOutputToContextAndFloorsAtOne() {
        // maxOutputTokens cannot exceed the context window.
        ModelCapability cap = new ModelCapability("weird", 2_000, 99_999, false);
        assertEquals(2_000, cap.contextTokens());
        assertEquals(2_000, cap.maxOutputTokens());

        // Non-positive inputs floor to 1 rather than producing negative budgets.
        ModelCapability degenerate = new ModelCapability(null, 0, 0, false);
        assertEquals(1, degenerate.contextTokens());
        assertEquals(1, degenerate.maxOutputTokens());
    }

    @Test
    void unknownRemoteUsesRegistryDefaults() {
        ModelCapability cap = ModelCapability.unknownRemote();
        assertEquals(ModelContextWindows.DEFAULT_CONTEXT_WINDOW, cap.contextTokens());
        assertEquals(ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS, cap.maxOutputTokens());
        assertFalse(cap.local());
    }
}
