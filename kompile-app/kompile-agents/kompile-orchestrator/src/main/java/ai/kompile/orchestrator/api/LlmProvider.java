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
package ai.kompile.orchestrator.api;

import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Interface for LLM providers that can be used by the orchestrator.
 */
public interface LlmProvider {

    /**
     * Information about an available model from this provider.
     */
    record ModelInfo(
            String id,           // Model identifier (e.g., "gpt-4o", "claude-3-5-sonnet")
            String displayName,  // Human-readable name
            String description,  // Optional description
            long contextWindow,  // Max context tokens, or -1 if unknown
            boolean supportsTools // Whether model supports tool/function calling
    ) {
        public ModelInfo(String id, String displayName) {
            this(id, displayName, null, -1, true);
        }

        public ModelInfo(String id, String displayName, String description) {
            this(id, displayName, description, -1, true);
        }
    }

    /**
     * Get the unique identifier for this provider.
     *
     * @return The provider ID
     */
    String getId();

    /**
     * Get the human-readable display name.
     *
     * @return The display name
     */
    String getDisplayName();

    /**
     * Check if this provider is available and configured.
     *
     * @return true if the provider can be used
     */
    boolean isAvailable();

    /**
     * Start a new LLM session.
     *
     * @param request The session request
     * @return The created session
     */
    LlmSession startSession(LlmSessionRequest request);

    /**
     * Send a message to an existing session (for multi-turn conversations).
     *
     * @param sessionId The session ID
     * @param message   The message to send
     * @return The updated session with response
     */
    LlmSession sendMessage(Long sessionId, String message);

    /**
     * Cancel an active session.
     *
     * @param sessionId The session ID
     */
    void cancelSession(Long sessionId);

    /**
     * Check if a session is still active.
     *
     * @param sessionId The session ID
     * @return true if the session is active
     */
    boolean isSessionActive(Long sessionId);

    /**
     * Stream session output for real-time display.
     *
     * @param sessionId The session ID
     * @return Flux of output lines
     */
    Flux<String> streamOutput(Long sessionId);

    /**
     * Parse actions from LLM output.
     *
     * @param output The LLM output
     * @return List of parsed action strings
     */
    default List<String> parseActions(String output) {
        return List.of();
    }

    /**
     * Parse structured output (JSON) from LLM response.
     *
     * @param output The LLM output
     * @return Parsed map of key-value pairs
     */
    default Map<String, Object> parseStructuredOutput(String output) {
        return Map.of();
    }

    /**
     * Parse an action proposal from LLM output.
     *
     * @param output The LLM output
     * @return The parsed action proposal, or null if not parseable
     */
    default ActionProposal parseActionProposal(String output) {
        return null;
    }

    /**
     * Get the priority of this provider for selection.
     *
     * @return The priority (higher = preferred)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Check if this provider supports file access.
     *
     * @return true if the provider can access files
     */
    default boolean supportsFileAccess() {
        return false;
    }

    /**
     * Check if this provider supports streaming.
     *
     * @return true if the provider supports streaming output
     */
    default boolean supportsStreaming() {
        return true;
    }

    /**
     * Get the maximum tokens this provider supports.
     *
     * @return The maximum tokens, or -1 for no limit
     */
    default int getMaxTokens() {
        return -1;
    }

    /**
     * Get the list of available models from this provider.
     * Implementations should query their respective APIs:
     * - OpenAI: GET /v1/models
     * - Anthropic: GET /v1/models
     * - Ollama: GET /api/tags
     * - Gemini/Vertex AI: models.list
     *
     * @return List of available models, empty list if unable to fetch
     */
    default List<ModelInfo> getAvailableModels() {
        return List.of();
    }

    /**
     * Check if this provider supports dynamic model listing.
     * Returns false if the provider can only return a static/hardcoded list.
     *
     * @return true if getAvailableModels() queries the provider API dynamically
     */
    default boolean supportsModelListing() {
        return false;
    }
}
