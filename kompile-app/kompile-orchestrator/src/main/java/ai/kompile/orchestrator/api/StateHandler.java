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

import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.state.StateHandlerResult;

import java.time.Duration;
import java.util.Map;

/**
 * Interface for handling state machine states.
 * Implementations provide the logic for each state in the orchestrator.
 */
public interface StateHandler {

    /**
     * Called when entering this state.
     *
     * @param instance The orchestrator instance
     * @param state    The state definition being entered
     * @param context  The current context
     */
    default void onEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        // Default: no-op
    }

    /**
     * Main handler logic. Called while in this state.
     * For polling states, called repeatedly at the polling interval.
     * For action states, called once.
     *
     * @param instance The orchestrator instance
     * @param state    The current state definition
     * @param context  The current context
     * @return The result of handling, including next state suggestion
     */
    StateHandlerResult handle(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context);

    /**
     * Called when exiting this state.
     *
     * @param instance The orchestrator instance
     * @param state    The state definition being exited
     * @param context  The current context
     */
    default void onExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        // Default: no-op
    }

    /**
     * Whether this handler should be called repeatedly (polling) or once.
     *
     * @return true if this is a polling handler
     */
    default boolean isPolling() {
        return false;
    }

    /**
     * Polling interval if isPolling() returns true.
     *
     * @return The polling interval
     */
    default Duration getPollingInterval() {
        return Duration.ofSeconds(2);
    }

    /**
     * Get the state IDs this handler can handle.
     * If empty, the handler must be explicitly registered for specific states.
     *
     * @return Array of state IDs this handler supports
     */
    default String[] getSupportedStateIds() {
        return new String[0];
    }

    /**
     * Priority for handler selection when multiple handlers support the same state.
     * Higher priority handlers are selected first.
     *
     * @return The priority (default 0)
     */
    default int getPriority() {
        return 0;
    }
}
