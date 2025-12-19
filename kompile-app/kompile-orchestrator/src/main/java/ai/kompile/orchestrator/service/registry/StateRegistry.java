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
package ai.kompile.orchestrator.service.registry;

import ai.kompile.orchestrator.model.state.StateDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for state definitions.
 */
@Slf4j
public class StateRegistry {

    private final Map<String, StateDefinition> states = new ConcurrentHashMap<>();

    /**
     * Register a state definition.
     */
    public void register(StateDefinition state) {
        if (state == null || state.getStateId() == null) {
            throw new IllegalArgumentException("State and state ID must not be null");
        }
        states.put(state.getStateId(), state);
        log.debug("Registered state: {}", state.getStateId());
    }

    /**
     * Register multiple state definitions.
     */
    public void registerAll(Collection<StateDefinition> definitions) {
        definitions.forEach(this::register);
    }

    /**
     * Unregister a state.
     */
    public void unregister(String stateId) {
        states.remove(stateId);
        log.debug("Unregistered state: {}", stateId);
    }

    /**
     * Get a state by ID.
     */
    public Optional<StateDefinition> get(String stateId) {
        return Optional.ofNullable(states.get(stateId));
    }

    /**
     * Check if a state exists.
     */
    public boolean exists(String stateId) {
        return states.containsKey(stateId);
    }

    /**
     * Get all registered states.
     */
    public Set<StateDefinition> getAll() {
        return new HashSet<>(states.values());
    }

    /**
     * Get all state IDs.
     */
    public Set<String> getAllIds() {
        return new HashSet<>(states.keySet());
    }

    /**
     * Get allowed transitions from a state.
     */
    public Set<String> getAllowedTransitions(String fromStateId) {
        StateDefinition state = states.get(fromStateId);
        if (state == null) {
            return Collections.emptySet();
        }
        return state.getAllowedNextStates() != null
                ? new HashSet<>(state.getAllowedNextStates())
                : Collections.emptySet();
    }

    /**
     * Check if transition is allowed.
     */
    public boolean isTransitionAllowed(String fromStateId, String toStateId) {
        StateDefinition state = states.get(fromStateId);
        if (state == null) {
            return false;
        }
        return state.canTransitionTo(toStateId);
    }

    /**
     * Clear all states.
     */
    public void clear() {
        states.clear();
    }
}
