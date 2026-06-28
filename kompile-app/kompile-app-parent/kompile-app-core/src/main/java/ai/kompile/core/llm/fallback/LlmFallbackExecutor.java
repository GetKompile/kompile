/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.llm.fallback;

/**
 * SPI for executing an LLM prompt with automatic model fallback.
 *
 * <p>Implementations (e.g. in kompile-app-main) detect throttle/timeout signals
 * and switch to the next agent/model in the configured fallback chain.
 * The interface lives in kompile-app-core so lower-level modules
 * (kompile-knowledge-graph, kompile-crawl-graph, etc.) can accept it via
 * {@code @Autowired(required = false)} without introducing an upward dependency.</p>
 */
public interface LlmFallbackExecutor {

    /**
     * Execute an LLM prompt with automatic model fallback.
     *
     * <p>Handles timeout detection, throttle signal detection, and switches to
     * the next model/provider in the configured fallback chain on failure.</p>
     *
     * @param prompt    the user prompt to send
     * @param backendId a label used for transcript logging (e.g. "graph-constructor")
     * @return the LLM response content (never null, never blank on success)
     * @throws RuntimeException if all fallback attempts are exhausted or the
     *                          executor is misconfigured
     */
    default String executeWithFallback(String prompt, String backendId) {
        return executeWithFallback(prompt, backendId, r -> true);
    }

    /**
     * Execute with fallback plus a caller-supplied response validator. A quota-cut/truncated
     * response is non-blank and contains no throttle keyword, so without a validator it is
     * returned as "success" and only fails downstream (unparseable JSON) with NO model switch.
     * When {@code responseValidator} rejects a response, the executor treats it as a failure and
     * advances to the next model in the chain — making failover actually fire on quota cutoffs.
     *
     * @param responseValidator returns true if the response is acceptable; false triggers failover
     */
    String executeWithFallback(String prompt, String backendId,
                               java.util.function.Predicate<String> responseValidator);
}
