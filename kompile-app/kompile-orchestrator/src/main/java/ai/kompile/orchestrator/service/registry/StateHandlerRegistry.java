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

import ai.kompile.orchestrator.api.StateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for state handlers.
 */
@Slf4j
public class StateHandlerRegistry {

    private final Map<String, List<StateHandler>> handlers = new ConcurrentHashMap<>();

    /**
     * Register a handler for a state.
     */
    public void register(String stateId, StateHandler handler) {
        if (stateId == null || handler == null) {
            throw new IllegalArgumentException("State ID and handler must not be null");
        }

        handlers.computeIfAbsent(stateId, k -> new ArrayList<>()).add(handler);

        // Sort by priority (descending)
        handlers.get(stateId).sort(Comparator.comparingInt(StateHandler::getPriority).reversed());

        log.debug("Registered handler for state: {}", stateId);
    }

    /**
     * Register a handler that declares its supported states.
     */
    public void registerAuto(StateHandler handler) {
        String[] supportedStates = handler.getSupportedStateIds();
        if (supportedStates == null || supportedStates.length == 0) {
            log.warn("Handler {} does not declare supported states", handler.getClass().getName());
            return;
        }

        for (String stateId : supportedStates) {
            register(stateId, handler);
        }
    }

    /**
     * Unregister a handler from a state.
     */
    public void unregister(String stateId, StateHandler handler) {
        List<StateHandler> stateHandlers = handlers.get(stateId);
        if (stateHandlers != null) {
            stateHandlers.remove(handler);
            if (stateHandlers.isEmpty()) {
                handlers.remove(stateId);
            }
        }
    }

    /**
     * Unregister all handlers for a state.
     */
    public void unregisterAll(String stateId) {
        handlers.remove(stateId);
    }

    /**
     * Get the primary handler for a state (highest priority).
     */
    public Optional<StateHandler> get(String stateId) {
        List<StateHandler> stateHandlers = handlers.get(stateId);
        if (stateHandlers == null || stateHandlers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(stateHandlers.get(0));
    }

    /**
     * Get all handlers for a state.
     */
    public List<StateHandler> getAll(String stateId) {
        return handlers.getOrDefault(stateId, Collections.emptyList());
    }

    /**
     * Check if a handler exists for a state.
     */
    public boolean hasHandler(String stateId) {
        List<StateHandler> stateHandlers = handlers.get(stateId);
        return stateHandlers != null && !stateHandlers.isEmpty();
    }

    /**
     * Get all registered state IDs.
     */
    public Set<String> getRegisteredStates() {
        return new HashSet<>(handlers.keySet());
    }

    /**
     * Clear all handlers.
     */
    public void clear() {
        handlers.clear();
    }
}
