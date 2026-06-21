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
     * Starting input-char budget: sized so the first call's input ≈ the model's entire output budget
     * (in tokens). For entity-dense text this sits near the output-truncation boundary without grossly
     * exceeding it; the yield-gated AIMD sizer ramps up when output has headroom and shrinks the moment
     * a batch returns nothing (the truncation signal). Clamped to {@code [minInputChars, maxInputChars]}.
     */
    public int initInputChars() {
        long start = (long) maxOutputTokens * CHARS_PER_TOKEN;
        return (int) Math.max(minInputChars(), Math.min(maxInputChars(), start));
    }

    /** A safe default capability for when no model can be resolved (unknown remote LLM). */
    public static ModelCapability unknownRemote() {
        return new ModelCapability(null, ModelContextWindows.DEFAULT_CONTEXT_WINDOW,
                ModelContextWindows.DEFAULT_MAX_OUTPUT_TOKENS, false);
    }
}
