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
     * Run text generation on the loaded serving model.
     *
     * @param prompt the extraction prompt
     * @return the generated text (never {@code null}; empty string when the model returned nothing)
     * @throws Exception on HTTP/I/O failure or a model-reported error finish reason
     */
    String generate(String prompt) throws Exception;
}
