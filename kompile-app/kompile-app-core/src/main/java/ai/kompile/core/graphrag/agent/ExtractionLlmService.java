/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.agent;

/**
 * Abstraction for LLM completion used by graph extraction agents.
 *
 * <p>Implementations can wrap the local SameDiff model (via LLMChat),
 * CLI-based agents (claude-cli, gemini-cli), or any other text completion backend.
 * The extraction agent selects which provider to use based on the
 * {@code llmProvider} key in {@link RelationExtractionAgent.ExtractionConfig#options()}.
 *
 * <p>Implementations are discovered via Spring autowiring: any bean implementing
 * this interface will be available for selection by name.
 */
public interface ExtractionLlmService {

    /**
     * Unique identifier for this provider (e.g., "llm-chat", "claude-cli", "gemini-cli").
     * Used to select the provider via extraction config options.
     */
    String getId();

    /**
     * Human-readable description of this provider.
     */
    String getDescription();

    /**
     * Send a prompt and return the text completion.
     *
     * @param prompt the full prompt text
     * @return the LLM's text response
     * @throws ExtractionLlmException if the completion fails
     */
    String complete(String prompt);

    /**
     * Whether this provider is currently available and ready to accept requests.
     */
    boolean isAvailable();

    /**
     * Override the model used by this provider at runtime.
     * Default implementation is a no-op for providers that don't support model switching.
     *
     * @param model the model name to use, or null to reset to provider default
     */
    default void setModelOverride(String model) {
        // Default no-op — providers that support model switching should override this
    }

    /**
     * Get the effective model currently in use.
     * Returns null if no override is set (uses provider default).
     */
    default String getEffectiveModel() {
        return null;
    }

    /**
     * Exception thrown when an LLM completion fails.
     */
    class ExtractionLlmException extends RuntimeException {
        public ExtractionLlmException(String message) {
            super(message);
        }

        public ExtractionLlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
