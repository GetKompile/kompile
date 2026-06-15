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

package ai.kompile.cli.main.chat.config;

import java.util.Map;

/**
 * Static lookup of model names to context window sizes (in tokens)
 * and capability flags (vision support).
 * Used to auto-detect the appropriate context window and model
 * capabilities when the user hasn't explicitly configured them.
 */
public final class ModelContextWindows {

    /** Default context window when model is unknown. */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    /** Default max output tokens when model is unknown. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;

    private record ModelSpec(int contextWindow, int maxOutputTokens, boolean supportsVision) {}

    private static final Map<String, ModelSpec> MODEL_SPECS = Map.ofEntries(
            // Anthropic — all Claude 3+ models support vision
            entry("claude-opus-4", 200_000, 32_000, true),
            entry("claude-sonnet-4", 200_000, 16_000, true),
            entry("claude-haiku-4", 200_000, 8_192, true),
            entry("claude-3.5-sonnet", 200_000, 8_192, true),
            entry("claude-3.5-haiku", 200_000, 8_192, true),
            entry("claude-3-opus", 200_000, 4_096, true),
            entry("claude-3-sonnet", 200_000, 4_096, true),
            entry("claude-3-haiku", 200_000, 4_096, true),

            // OpenAI — GPT-4o/4.1/4-turbo and o-series support vision
            entry("gpt-4o", 128_000, 16_384, true),
            entry("gpt-4o-mini", 128_000, 16_384, true),
            entry("gpt-4.1", 1_047_576, 32_768, true),
            entry("gpt-4.1-mini", 1_047_576, 16_384, true),
            entry("gpt-4.1-nano", 1_047_576, 16_384, true),
            entry("gpt-4-turbo", 128_000, 4_096, true),
            entry("gpt-4", 8_192, 4_096, false),
            entry("gpt-3.5-turbo", 16_385, 4_096, false),
            entry("o4-mini", 200_000, 100_000, true),
            entry("o3", 200_000, 100_000, true),
            entry("o3-mini", 200_000, 65_536, false),
            entry("o1", 200_000, 100_000, true),
            entry("o1-mini", 128_000, 65_536, false),

            // Google Gemini — all models support vision
            entry("gemini-2.5-pro", 1_048_576, 65_536, true),
            entry("gemini-2.5-flash", 1_048_576, 65_536, true),
            entry("gemini-2.0-flash", 1_048_576, 8_192, true),
            entry("gemini-1.5-pro", 2_097_152, 8_192, true),
            entry("gemini-1.5-flash", 1_048_576, 8_192, true),

            // DeepSeek — text only
            entry("deepseek-chat", 64_000, 8_192, false),
            entry("deepseek-coder", 64_000, 8_192, false),
            entry("deepseek-reasoner", 64_000, 8_192, false),

            // Groq (hosted models) — text only
            entry("llama-3.3-70b-versatile", 128_000, 32_768, false),
            entry("mixtral-8x7b-32768", 32_768, 4_096, false),
            entry("llama-3.1-8b-instant", 131_072, 8_192, false),

            // Common Ollama models — text only
            entry("llama3.3", 128_000, 4_096, false),
            entry("llama3.1", 128_000, 4_096, false),
            entry("llama3", 8_192, 4_096, false),
            entry("qwen2.5-coder", 131_072, 8_192, false),
            entry("codellama", 16_384, 4_096, false),
            entry("deepseek-coder-v2", 128_000, 8_192, false),
            entry("mistral", 32_768, 4_096, false),
            entry("mixtral", 32_768, 4_096, false),
            entry("phi3", 128_000, 4_096, false),
            entry("gemma2", 8_192, 4_096, false),
            entry("command-r-plus", 128_000, 4_096, false)
    );

    private ModelContextWindows() {}

    /**
     * Look up the context window size for a model. Performs prefix matching
     * so that versioned model IDs (e.g. "claude-sonnet-4-20250514") match
     * the base entry ("claude-sonnet-4").
     *
     * @return context window in tokens, or {@link #DEFAULT_CONTEXT_WINDOW} if unknown
     */
    public static int getContextWindow(String model) {
        if (model == null || model.isBlank()) return DEFAULT_CONTEXT_WINDOW;
        ModelSpec spec = resolve(model);
        return spec != null ? spec.contextWindow : DEFAULT_CONTEXT_WINDOW;
    }

    /**
     * Look up the max output tokens for a model.
     *
     * @return max output tokens, or {@link #DEFAULT_MAX_OUTPUT_TOKENS} if unknown
     */
    public static int getMaxOutputTokens(String model) {
        if (model == null || model.isBlank()) return DEFAULT_MAX_OUTPUT_TOKENS;
        ModelSpec spec = resolve(model);
        return spec != null ? spec.maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
    }

    /**
     * Check if a model supports vision (image) inputs.
     * Performs the same prefix matching and OpenRouter prefix stripping
     * as the context window lookup.
     *
     * @return true if the model supports vision inputs, false if text-only or unknown
     */
    public static boolean supportsVision(String model) {
        if (model == null || model.isBlank()) return false;
        ModelSpec spec = resolve(model);
        return spec != null && spec.supportsVision;
    }

    /**
     * Resolve a model name to its spec.
     * Tries exact match first, then prefix matching for versioned model IDs.
     * Also strips OpenRouter-style provider prefixes (e.g. "anthropic/claude-sonnet-4").
     */
    private static ModelSpec resolve(String model) {
        String normalized = model.toLowerCase().trim();

        // Strip OpenRouter-style provider prefix
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }

        // Exact match
        if (MODEL_SPECS.containsKey(normalized)) {
            return MODEL_SPECS.get(normalized);
        }

        // Strip Ollama tag suffixes (e.g. "qwen2.5-coder:32b" -> "qwen2.5-coder")
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            String base = normalized.substring(0, colon);
            if (MODEL_SPECS.containsKey(base)) {
                return MODEL_SPECS.get(base);
            }
        }

        // Prefix match: "claude-sonnet-4-20250514" matches "claude-sonnet-4"
        // Use longest matching prefix to avoid "gpt-4" matching instead of "gpt-4o"
        String bestKey = null;
        for (String key : MODEL_SPECS.keySet()) {
            if (normalized.startsWith(key)) {
                if (bestKey == null || key.length() > bestKey.length()) {
                    bestKey = key;
                }
            }
        }
        return bestKey != null ? MODEL_SPECS.get(bestKey) : null;
    }

    private static Map.Entry<String, ModelSpec> entry(String model, int contextWindow, int maxOutput, boolean supportsVision) {
        return Map.entry(model, new ModelSpec(contextWindow, maxOutput, supportsVision));
    }
}
