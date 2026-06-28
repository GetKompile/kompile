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

/**
 * Resolved capabilities of the LLM a crawl will call for extraction — looked up ONCE at crawl start
 * (from {@link ModelContextWindows} for remote/known models, or the local model registry's
 * max-sequence-length for on-device staged models) and reused for batch budgeting.
 *
 * <p>Every char budget below is <em>derived</em> from the two real model numbers — {@code
 * contextTokens} and {@code maxOutputTokens} — at the codebase's {@value #CHARS_PER_TOKEN}-chars-per-
 * token convention (token ≈ {@code text.length() / 4}, matching {@code CrawlLlmDispatcher}'s token
 * accounting). Nothing here is a magic constant: each budget traces back to the model's published
 * context window and output ceiling.</p>
 *
 * <p>The key insight driving these bounds: for entity/relationship extraction the binding limit is
 * not input context but <b>max output tokens</b> — the extraction JSON grows with input, and a batch
 * that would emit more than {@code maxOutputTokens} of JSON silently truncates (unparseable → empty
 * graph). So {@link #maxInputChars()} caps input to leave full room for output, {@link
 * #initInputChars()} starts where input ≈ the whole output budget, and the caller's yield-gated AIMD
 * sizer discovers the data-dependent sweet spot in between.</p>
 *
 * @param modelId          resolved model identifier (for logging); may be null/blank if unknown
 * @param contextTokens    the model's total context window, in tokens
 * @param maxOutputTokens  the model's maximum generated (output) tokens per call
 * @param local            true for on-device SameDiff/ONNX models, false for remote CLI/API agents
 */
public record ModelCapability(String modelId, int contextTokens, int maxOutputTokens, boolean local) {

    /** Codebase token-estimation convention: 1 token ≈ 4 characters (see {@code CrawlLlmDispatcher}). */
    public static final int CHARS_PER_TOKEN = 4;

    /** Tokens reserved for the extraction instruction wrapper + schema description on every call. */
    public static final int PROMPT_OVERHEAD_TOKENS = 512;

    public ModelCapability {
        contextTokens = Math.max(1, contextTokens);
        maxOutputTokens = Math.max(1, Math.min(maxOutputTokens, contextTokens));
    }

    /**
     * Hard ceiling on INPUT characters per call: the context window minus the reserved output budget
     * and prompt overhead, in chars. Sending more than this cannot coexist with the model's own
     * output in-context, so it is the absolute upper bound the adaptive sizer may ramp toward.
     */
    public int maxInputChars() {
        long usableTokens = (long) contextTokens - maxOutputTokens - PROMPT_OVERHEAD_TOKENS;
        long chars = Math.max(1L, usableTokens) * CHARS_PER_TOKEN;
        return (int) Math.min(Integer.MAX_VALUE, chars);
    }

    /**
     * Floor on input characters per call — a small budget (~the output-token count, in chars) so even
     * a tiny-context model still makes forward progress at one chunk per call. Never exceeds the
     * ceiling.
     */
    public int minInputChars() {
        long floor = Math.max(1L, maxOutputTokens);
        return (int) Math.min(maxInputChars(), floor);
    }

    /**
     * Starting input-char budget for the AIMD sizer on the first extraction call.
     *
     * <p>For <b>small-context models</b> (contextTokens ≤ 200 000 — local, Ollama, Claude 3-series,
     * GPT-4, etc.) we keep the original conservative heuristic: start ≈ the output budget in chars
     * ({@code maxOutputTokens × CHARS_PER_TOKEN}). This sits near the output-truncation boundary
     * without grossly exceeding it; the yield-gated AIMD sizer ramps up when there is headroom.</p>
     *
     * <p>For <b>large-context models</b> (contextTokens > 200 000 — GPT-4.1, Gemini 1.5/2.x,
     * DeepSeek V4 / opencode-cli "deepseek-v4-flash-free", etc.) the output budget is
     * {@code maxOutputTokens × 4 ≈ 32 768 chars}, which is only ~3 % of a 1 M-token window.
     * Starting there means the AIMD ramp takes dozens of calls to reach a useful input size —
     * on a small corpus it may never approach the ceiling. Instead, start at 10 % of
     * {@code maxInputChars()} so the first wave immediately sends a large batch: for DeepSeek at
     * 1 M tokens that is ~396 000 chars (≈ 99 k tokens), yielding rich extractions on the first
     * call rather than ramping for many waves.</p>
     *
     * <p>Both paths are clamped to {@code [minInputChars, maxInputChars]} so they are always
     * valid inputs for the sizer.</p>
     */
    public int initInputChars() {
        // Large-context threshold: > 200 000 tokens (covers GPT-4.1/4.1-mini/nano, Gemini 1.5+/2.x,
        // DeepSeek V4 ~1 M, etc.). Below this threshold, use the original output-budget heuristic.
        final int LARGE_CONTEXT_TOKEN_THRESHOLD = 200_000;
        long start;
        if (contextTokens > LARGE_CONTEXT_TOKEN_THRESHOLD) {
            // Start at 10 % of usable input chars so the first wave is immediately large.
            // For DeepSeek 1 M / 8 192 output: maxInputChars = (1 000 000 - 8 192 - 512) * 4 ≈ 3 964 384.
            // 10 % = 396 438 chars (≈ 99 k tokens), vs the old 32 768 chars (≈ 8 k tokens).
            start = Math.max(1L, (long) (maxInputChars() * 0.10));
        } else {
            // Small-context / local: start ≈ the output budget to stay near the truncation boundary.
            start = (long) maxOutputTokens * CHARS_PER_TOKEN;
        }
        return (int) Math.max(minInputChars(), Math.min(maxInputChars(), start));
    }

    /** A safe default capability for when no model can be resolved (unknown remote LLM). */
    public static ModelCapability unknownRemote() {
        return new ModelCapability(null, ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS, false);
    }
}
