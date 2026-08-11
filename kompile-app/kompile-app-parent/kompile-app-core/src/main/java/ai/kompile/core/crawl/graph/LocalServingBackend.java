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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.llm.StructuredChatLanguageModel;

/**
 * Dependency-free bridge to the local LLM serving subprocess, letting the crawl pipeline route
 * extraction to it as a <b>quota-free local backend</b> without depending on {@code kompile-app-main}
 * (where the {@code ServingSubprocessLauncher} lives).
 *
 * <p>The serving subprocess exposes {@code POST /api/llm/generate} over loopback HTTP; the app-main
 * implementation delegates to that launcher and returns the generated text. It is a distinct lane
 * from the in-process {@code LLMChat}: a {@code LOCAL_MODEL} backend opts into it by setting
 * {@code agentName="serving"} on the {@link ProcessingRouteConfig.ProcessingBackend}.</p>
 *
 * <p>Injected as {@code @Autowired(required = false)}; when absent (CPU-only builds, unit tests, or
 * contexts without app-main) the dispatcher falls back to its normal local path, so a {@code null}
 * adapter is always safe. Once {@code #1}'s capacity gate is live, registering a low-priority
 * {@code LOCAL_MODEL} serving backend gives crawls a quota-free fallback for when CLI backends are
 * rate-limited or quota-exhausted.</p>
 */
public interface LocalServingBackend {

    /**
     * Whether the serving subprocess is running with a model loaded and can accept a generate call
     * right now. The dispatcher must consult this before {@link #generate(String)} and treat the
     * backend as unavailable (skip / fall back) when it returns {@code false}.
     *
     * @return {@code true} when a generate call would be served
     */
    boolean isAvailable();

    /**
     * Whether this backend is configured for the exact requested model identifier.
     *
     * <p>The default is deliberately conservative so older/test adapters cannot accidentally claim
     * an explicit model request. Dispatchers may use a positive match to bypass generic provider
     * fallback and must not substitute a different model if the matched backend is unavailable.</p>
     *
     * @param modelId exact model identifier requested by the crawl
     * @return {@code true} only when this backend owns that exact model
     */
    default boolean matchesModel(String modelId) {
        return false;
    }

    /**
     * Generate only if the same exact model is still active when generation begins.
     *
     * <p>Production adapters should override this atomically with their model lifecycle lock. The
     * conservative default preserves compatibility while re-checking the model immediately before
     * generation.</p>
     */
    default String generateForModel(String modelId, String prompt) throws Exception {
        if (!matchesModel(modelId)) {
            throw new IllegalStateException("Requested serving model is no longer active: " + modelId);
        }
        return generate(prompt);
    }

    /**
     * Generate for the exact requested model with a request-scoped output-token budget.
     *
     * <p>The default keeps older adapters source-compatible while preserving the identity check.
     * Production adapters can override this to forward the budget without reloading the model.</p>
     */
    default String generateForModel(String modelId, String prompt, int maxNewTokens) throws Exception {
        if (!matchesModel(modelId)) {
            throw new IllegalStateException("Requested serving model is no longer active: " + modelId);
        }
        return generate(prompt, maxNewTokens);
    }

    /** Whether this bridge preserves structured messages, tools, and parsed calls end to end. */
    default boolean supportsStructuredChat() {
        return false;
    }

    /**
     * Run model-owned structured chat. The default deliberately fails instead of flattening the
     * request into raw text; callers that select this capability must never silently fall back.
     */
    default StructuredChatLanguageModel.Response generateChat(
            StructuredChatLanguageModel.Request request, int maxNewTokens) throws Exception {
        throw new UnsupportedOperationException("Structured chat is not supported by this serving backend");
    }

    /** Structured generation guarded by exact model identity. */
    default StructuredChatLanguageModel.Response generateChatForModel(
            String modelId, StructuredChatLanguageModel.Request request, int maxNewTokens) throws Exception {
        if (!matchesModel(modelId)) {
            throw new IllegalStateException("Requested serving model is no longer active: " + modelId);
        }
        return generateChat(request, maxNewTokens);
    }

    /**
     * Run text generation on the loaded serving model.
     *
     * @param prompt the extraction prompt
     * @return the generated text (never {@code null}; empty string when the model returned nothing)
     * @throws Exception on HTTP/I/O failure or a model-reported error finish reason
     */
    String generate(String prompt) throws Exception;

    /**
     * Run generation with a request-scoped output-token budget.
     *
     * <p>The compatibility default delegates to {@link #generate(String)} so adapters that cannot
     * vary generation length continue to work unchanged.</p>
     */
    default String generate(String prompt, int maxNewTokens) throws Exception {
        return generate(prompt);
    }
}
