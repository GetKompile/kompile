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
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.model.state.StateCategory;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.state.StateTransition;
import ai.kompile.orchestrator.service.impl.StateMachineConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for state machine configuration.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/state-machine")
@RequiredArgsConstructor
public class StateMachineController {

    private final StateMachineConfigService stateMachineService;

    // ==================== State Endpoints ====================

    /**
     * Get all states for an instance.
     */
    @GetMapping("/states")
    public ResponseEntity<List<StateDefinition>> getStates(
            @PathVariable("instanceId") String instanceId,
            @RequestParam(required = false, defaultValue = "false") boolean includeGlobal) {
        if (includeGlobal) {
            return ResponseEntity.ok(stateMachineService.getAllAvailableStates(instanceId));
        }
        return ResponseEntity.ok(stateMachineService.getStatesForInstance(instanceId));
    }

    /**
     * Get a specific state.
     */
    @GetMapping("/states/{stateId}")
    public ResponseEntity<StateDefinition> getState(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("stateId") String stateId) {
        return stateMachineService.getState(stateId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new state.
     */
    @PostMapping("/states")
    public ResponseEntity<StateDefinition> createState(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StateDefinition state) {
        log.info("Creating state {} for instance {}", state.getStateId(), instanceId);

        StateDefinition created = stateMachineService.createState(instanceId, state);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Create default states for an instance.
     */
    @PostMapping("/states/defaults")
    public ResponseEntity<List<StateDefinition>> createDefaultStates(
            @PathVariable("instanceId") String instanceId) {
        log.info("Creating default states for instance {}", instanceId);

        List<StateDefinition> states = stateMachineService.createDefaultStates(instanceId);
        return ResponseEntity.status(HttpStatus.CREATED).body(states);
    }

    /**
     * Update a state.
     */
    @PutMapping("/states/{stateId}")
    public ResponseEntity<StateDefinition> updateState(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("stateId") String stateId,
            @RequestBody StateDefinition state) {
        log.info("Updating state {} for instance {}", stateId, instanceId);

        StateDefinition updated = stateMachineService.updateState(instanceId, stateId, state);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a state.
     */
    @DeleteMapping("/states/{stateId}")
    public ResponseEntity<Map<String, Object>> deleteState(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("stateId") String stateId) {
        log.info("Deleting state {} for instance {}", stateId, instanceId);

        stateMachineService.deleteState(instanceId, stateId);
        return ResponseEntity.ok(Map.of(
                "stateId", stateId,
                "message", "State deleted successfully"));
    }

    /**
     * Update state positions (batch update for visual editor).
     */
    @PostMapping("/states/positions")
    public ResponseEntity<Map<String, Object>> updateStatePositions(
            @PathVariable("instanceId") String instanceId,
            @RequestBody List<StateMachineConfigService.StatePositionUpdate> positions) {
        log.debug("Updating {} state positions for instance {}", positions.size(), instanceId);

        stateMachineService.updateStatePositions(instanceId, positions);
        return ResponseEntity.ok(Map.of(
                "message", "Positions updated successfully",
                "count", positions.size()));
    }

    /**
     * Get available state categories.
     */
    @GetMapping("/categories")
    public ResponseEntity<StateCategory[]> getStateCategories(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(StateCategory.values());
    }

    // ==================== Transition Endpoints ====================

    /**
     * Get all transitions for an instance.
     */
    @GetMapping("/transitions")
    public ResponseEntity<List<StateTransition>> getTransitions(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(stateMachineService.getTransitionsForInstance(instanceId));
    }

    /**
     * Get transitions from a specific state.
     */
    @GetMapping("/states/{stateId}/transitions")
    public ResponseEntity<List<StateTransition>> getTransitionsFromState(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("stateId") String stateId) {
        return ResponseEntity.ok(stateMachineService.getTransitionsFromState(instanceId, stateId));
    }

    /**
     * Get a specific transition.
     */
    @GetMapping("/transitions/{transitionId}")
    public ResponseEntity<StateTransition> getTransition(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("transitionId") Long transitionId) {
        return stateMachineService.getTransition(transitionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new transition.
     */
    @PostMapping("/transitions")
    public ResponseEntity<StateTransition> createTransition(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StateTransition transition) {
        log.info("Creating transition from {} to {} for instance {}",
                transition.getFromStateId(), transition.getToStateId(), instanceId);

        StateTransition created = stateMachineService.createTransition(instanceId, transition);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Create a transition from a specific state.
     */
    @PostMapping("/states/{stateId}/transitions")
    public ResponseEntity<StateTransition> createTransitionFromState(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("stateId") String stateId,
            @RequestBody StateTransition transition) {
        transition.setFromStateId(stateId);
        log.info("Creating transition from {} to {} for instance {}",
                stateId, transition.getToStateId(), instanceId);

        StateTransition created = stateMachineService.createTransition(instanceId, transition);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update a transition.
     */
    @PutMapping("/transitions/{transitionId}")
    public ResponseEntity<StateTransition> updateTransition(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("transitionId") Long transitionId,
            @RequestBody StateTransition transition) {
        log.info("Updating transition {} for instance {}", transitionId, instanceId);

        StateTransition updated = stateMachineService.updateTransition(instanceId, transitionId, transition);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a transition.
     */
    @DeleteMapping("/transitions/{transitionId}")
    public ResponseEntity<Map<String, Object>> deleteTransition(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("transitionId") Long transitionId) {
        log.info("Deleting transition {} for instance {}", transitionId, instanceId);

        stateMachineService.deleteTransition(instanceId, transitionId);
        return ResponseEntity.ok(Map.of(
                "transitionId", transitionId,
                "message", "Transition deleted successfully"));
    }

    /**
     * Get available transition condition types.
     */
    @GetMapping("/transition-conditions")
    public ResponseEntity<StateTransition.TransitionConditionType[]> getTransitionConditionTypes(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(StateTransition.TransitionConditionType.values());
    }

    // ==================== Full Configuration Endpoints ====================

    /**
     * Get the complete state machine configuration.
     */
    @GetMapping
    public ResponseEntity<StateMachineConfigService.StateMachineConfig> getStateMachineConfig(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(stateMachineService.getStateMachineConfig(instanceId));
    }

    /**
     * Import a complete state machine configuration.
     */
    @PostMapping("/import")
    public ResponseEntity<StateMachineConfigService.StateMachineConfig> importStateMachineConfig(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StateMachineConfigService.StateMachineConfig config) {
        log.info("Importing state machine config for instance {} with {} states",
                instanceId, config.getStates() != null ? config.getStates().size() : 0);

        StateMachineConfigService.StateMachineConfig imported =
                stateMachineService.importStateMachineConfig(instanceId, config);
        return ResponseEntity.ok(imported);
    }

    /**
     * Export the state machine configuration (same as GET, but explicit endpoint).
     */
    @GetMapping("/export")
    public ResponseEntity<StateMachineConfigService.StateMachineConfig> exportStateMachineConfig(
            @PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(stateMachineService.getStateMachineConfig(instanceId));
    }
}
