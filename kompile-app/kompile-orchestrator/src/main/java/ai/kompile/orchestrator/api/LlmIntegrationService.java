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
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.workflow.ActionProposal;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for LLM integration and trigger management.
 */
public interface LlmIntegrationService {

    // ==================== Session Management ====================

    /**
     * Start an LLM session.
     *
     * @param request The session request
     * @return The created session
     */
    LlmSession startSession(LlmSessionRequest request);

    /**
     * Start an LLM session with a specific provider.
     *
     * @param providerId The provider ID
     * @param request    The session request
     * @return The created session
     */
    LlmSession startSession(String providerId, LlmSessionRequest request);

    /**
     * Send a message to an existing session.
     *
     * @param sessionId The session ID
     * @param message   The message to send
     * @return The updated session
     */
    LlmSession sendMessage(Long sessionId, String message);

    /**
     * Cancel an active session.
     *
     * @param sessionId The session ID
     */
    void cancelSession(Long sessionId);

    /**
     * Get a session by ID.
     *
     * @param sessionId The session ID
     * @return The session, or empty if not found
     */
    Optional<LlmSession> getSession(Long sessionId);

    /**
     * Get all sessions for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of sessions
     */
    List<LlmSession> getSessions(String orchestratorInstanceId);

    /**
     * Get active sessions for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of active sessions
     */
    List<LlmSession> getActiveSessions(String orchestratorInstanceId);

    /**
     * Check if a session is active.
     *
     * @param sessionId The session ID
     * @return true if the session is active
     */
    boolean isSessionActive(Long sessionId);

    /**
     * Stream session output.
     *
     * @param sessionId The session ID
     * @return Flux of output lines
     */
    Flux<String> streamOutput(Long sessionId);

    // ==================== Trigger Management ====================

    /**
     * Register an LLM trigger.
     *
     * @param trigger The trigger to register
     */
    void registerTrigger(LlmTrigger trigger);

    /**
     * Unregister a trigger by ID.
     *
     * @param triggerId The trigger ID
     */
    void unregisterTrigger(String triggerId);

    /**
     * Get a trigger by ID.
     *
     * @param triggerId The trigger ID
     * @return The trigger, or empty if not found
     */
    Optional<LlmTrigger> getTrigger(String triggerId);

    /**
     * Get all registered triggers.
     *
     * @return List of triggers
     */
    List<LlmTrigger> getAllTriggers();

    /**
     * Enable a trigger.
     *
     * @param triggerId The trigger ID
     */
    void enableTrigger(String triggerId);

    /**
     * Disable a trigger.
     *
     * @param triggerId The trigger ID
     */
    void disableTrigger(String triggerId);

    // ==================== Manual Invocation ====================

    /**
     * Invoke LLM with a prompt.
     *
     * @param prompt                 The prompt
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The session
     */
    LlmSession invoke(String prompt, String orchestratorInstanceId);

    /**
     * Invoke LLM with a prompt and provider.
     *
     * @param providerId             The provider ID
     * @param prompt                 The prompt
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The session
     */
    LlmSession invoke(String providerId, String prompt, String orchestratorInstanceId);

    /**
     * Invoke LLM with context.
     *
     * @param prompt                 The prompt template
     * @param context                Context for variable substitution
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The session
     */
    LlmSession invokeWithContext(String prompt, Map<String, Object> context, String orchestratorInstanceId);

    // ==================== Provider Management ====================

    /**
     * Register an LLM provider.
     *
     * @param provider The provider to register
     */
    void registerProvider(LlmProvider provider);

    /**
     * Get a provider by ID.
     *
     * @param providerId The provider ID
     * @return The provider, or empty if not found
     */
    Optional<LlmProvider> getProvider(String providerId);

    /**
     * Get all available providers.
     *
     * @return List of providers
     */
    List<LlmProvider> getAllProviders();

    /**
     * Get the default provider.
     *
     * @return The default provider, or empty if none configured
     */
    Optional<LlmProvider> getDefaultProvider();

    // ==================== Response Parsing ====================

    /**
     * Parse an action proposal from LLM output.
     *
     * @param output The LLM output
     * @return The parsed action proposal, or null if not parseable
     */
    ActionProposal parseActionProposal(String output);

    /**
     * Parse structured output from LLM response.
     *
     * @param output The LLM output
     * @return Parsed map of key-value pairs
     */
    Map<String, Object> parseStructuredOutput(String output);

    // ==================== Additional Query Methods ====================

    /**
     * Get triggers for an orchestrator instance.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of triggers
     */
    List<LlmTrigger> getTriggers(String orchestratorInstanceId);

    /**
     * Get available provider IDs.
     *
     * @return List of provider IDs
     */
    List<String> getAvailableProviders();

    /**
     * Get session history for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param limit                  Maximum number of sessions to return
     * @return List of sessions
     */
    List<LlmSession> getSessionHistory(String orchestratorInstanceId, int limit);

    /**
     * Stream the response of an LLM session.
     *
     * @param sessionId The session ID
     * @return Flux of response tokens/lines
     */
    Flux<String> streamResponse(Long sessionId);
}
