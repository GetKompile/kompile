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

package ai.kompile.cli.main.chat.harness;

/**
 * Abstraction over how the judge LLM generates text.
 * <p>
 * Three execution modes:
 * <ul>
 *   <li><b>remote</b> — HTTP call to a remote provider (Anthropic, OpenAI, etc.) or local API server (ollama)</li>
 *   <li><b>local</b> — in-process inference via SameDiff TextGenerator / GenerationPipeline</li>
 *   <li><b>auto-server</b> — dynamically start a local server (ollama, kompile-app), then delegate to remote</li>
 * </ul>
 * <p>
 * {@link JudgeLlmEvaluator} owns the prompt construction and response parsing;
 * this interface only handles the raw text generation.
 */
public interface JudgeBackend {

    /**
     * Generate text from a user prompt and system prompt.
     *
     * @param userPrompt   the evaluation prompt built by JudgeLlmEvaluator
     * @param systemPrompt the judge system instructions
     * @return raw generated text (expected to be JSON)
     * @throws Exception on generation failure
     */
    String generate(String userPrompt, String systemPrompt) throws Exception;

    /**
     * Whether this backend is ready to serve requests.
     */
    boolean isAvailable();

    /**
     * Pre-start the backend so it is warm when the first evaluation arrives.
     * For persistent subprocess backends this launches the process early;
     * for API backends this is a no-op.
     *
     * @param systemPrompt the judge system instructions to use for the session
     */
    default void warmUp(String systemPrompt) {}

    /**
     * Clean up resources (model memory, server processes, etc.).
     */
    default void close() {}

    /**
     * Human-readable description of the backend for logging/diagnostics.
     */
    default String describe() {
        return getClass().getSimpleName();
    }
}
