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

import ai.kompile.orchestrator.model.state.StateDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing the state machine and state transitions.
 */
public interface StateMachineService {

    // ==================== State Registration ====================

    /**
     * Register a new state definition.
     *
     * @param state The state definition to register
     */
    void registerState(StateDefinition state);

    /**
     * Register multiple state definitions.
     *
     * @param states The state definitions to register
     */
    void registerStates(Collection<StateDefinition> states);

    /**
     * Unregister a state by ID.
     *
     * @param stateId The state ID to unregister
     */
    void unregisterState(String stateId);

    // ==================== State Queries ====================

    /**
     * Get a state definition by ID.
     *
     * @param stateId The state ID
     * @return The state definition, or empty if not found
     */
    Optional<StateDefinition> getState(String stateId);

    /**
     * Get all registered state definitions.
     *
     * @return Set of all state definitions
     */
    Set<StateDefinition> getAllStates();

    /**
     * Get allowed transitions from a state.
     *
     * @param fromStateId The source state ID
     * @return Set of allowed target state IDs
     */
    Set<String> getAllowedTransitions(String fromStateId);

    /**
     * Check if a state exists.
     *
     * @param stateId The state ID
     * @return true if the state exists
     */
    boolean stateExists(String stateId);

    // ==================== State Transitions ====================

    /**
     * Transition an orchestrator to a new state.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param targetStateId          The target state ID
     * @throws IllegalStateException if transition is not allowed
     */
    void transitionTo(String orchestratorInstanceId, String targetStateId);

    /**
     * Transition an orchestrator to a new state with context.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param targetStateId          The target state ID
     * @param context                Additional context for the transition
     * @throws IllegalStateException if transition is not allowed
     */
    void transitionTo(String orchestratorInstanceId, String targetStateId, Map<String, Object> context);

    /**
     * Check if transition to a state is allowed.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param targetStateId          The target state ID
     * @return true if transition is allowed
     */
    boolean canTransitionTo(String orchestratorInstanceId, String targetStateId);

    /**
     * Force a transition without validation (for recovery).
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param targetStateId          The target state ID
     */
    void forceTransitionTo(String orchestratorInstanceId, String targetStateId);

    // ==================== Current State ====================

    /**
     * Get the current state ID for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The current state ID, or null if not found
     */
    String getCurrentStateId(String orchestratorInstanceId);

    /**
     * Get the current state definition for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The current state definition, or empty if not found
     */
    Optional<StateDefinition> getCurrentState(String orchestratorInstanceId);

    // ==================== Handler Management ====================

    /**
     * Register a handler for a specific state.
     *
     * @param stateId The state ID
     * @param handler The handler to register
     */
    void registerHandler(String stateId, StateHandler handler);

    /**
     * Get the handler for a state.
     *
     * @param stateId The state ID
     * @return The handler, or empty if not registered
     */
    Optional<StateHandler> getHandler(String stateId);

    /**
     * Unregister a handler for a state.
     *
     * @param stateId The state ID
     */
    void unregisterHandler(String stateId);

    // ==================== Lifecycle ====================

    /**
     * Initialize the state machine for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param initialStateId         The initial state ID
     */
    void initialize(String orchestratorInstanceId, String initialStateId);

    /**
     * Reset the state machine for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     */
    void reset(String orchestratorInstanceId);
}
