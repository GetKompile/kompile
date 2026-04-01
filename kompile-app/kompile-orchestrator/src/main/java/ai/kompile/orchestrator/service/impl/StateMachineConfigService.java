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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.model.state.StateCategory;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.state.StateTransition;
import ai.kompile.orchestrator.repository.StateDefinitionRepository;
import ai.kompile.orchestrator.repository.StateTransitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing state machine configuration.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StateMachineConfigService {

    private final StateDefinitionRepository stateRepository;
    private final StateTransitionRepository transitionRepository;

    // ==================== State CRUD ====================

    /**
     * Get all states for an instance.
     */
    @Transactional(readOnly = true)
    public List<StateDefinition> getStatesForInstance(String instanceId) {
        return stateRepository.findByOrchestratorInstanceIdOrderByDisplayOrderAsc(instanceId);
    }

    /**
     * Get all states including global/builtin states.
     */
    @Transactional(readOnly = true)
    public List<StateDefinition> getAllAvailableStates(String instanceId) {
        return stateRepository.findByInstanceIdIncludingGlobal(instanceId);
    }

    /**
     * Get a state by ID.
     */
    @Transactional(readOnly = true)
    public Optional<StateDefinition> getState(String stateId) {
        return stateRepository.findById(stateId);
    }

    /**
     * Get a state by ID for a specific instance.
     */
    @Transactional(readOnly = true)
    public Optional<StateDefinition> getStateForInstance(String instanceId, String stateId) {
        return stateRepository.findByStateIdAndOrchestratorInstanceId(stateId, instanceId);
    }

    /**
     * Create a new state.
     */
    @Transactional
    public StateDefinition createState(String instanceId, StateDefinition state) {
        log.info("Creating state {} for instance {}", state.getStateId(), instanceId);

        if (stateRepository.existsById(state.getStateId())) {
            throw new IllegalArgumentException("State with ID already exists: " + state.getStateId());
        }

        state.setOrchestratorInstanceId(instanceId);
        state.setBuiltin(false);

        // Set default display order
        if (state.getDisplayOrder() == 0) {
            long count = stateRepository.countByOrchestratorInstanceId(instanceId);
            state.setDisplayOrder((int) (count * 10));
        }

        return stateRepository.save(state);
    }

    /**
     * Update an existing state.
     */
    @Transactional
    public StateDefinition updateState(String instanceId, String stateId, StateDefinition updates) {
        log.info("Updating state {} for instance {}", stateId, instanceId);

        StateDefinition existing = stateRepository.findById(stateId)
                .orElseThrow(() -> new IllegalArgumentException("State not found: " + stateId));

        // Don't allow updating builtin states
        if (existing.isBuiltin()) {
            throw new IllegalArgumentException("Cannot modify builtin state: " + stateId);
        }

        // Verify instance ownership
        if (existing.getOrchestratorInstanceId() != null &&
                !existing.getOrchestratorInstanceId().equals(instanceId)) {
            throw new IllegalArgumentException("State belongs to different instance");
        }

        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setCategory(updates.getCategory());
        existing.setAllowedNextStates(updates.getAllowedNextStates());
        existing.setHandlerClassName(updates.getHandlerClassName());
        existing.setTimeoutSeconds(updates.getTimeoutSeconds());
        existing.setAutoAdvance(updates.isAutoAdvance());
        existing.setPolling(updates.isPolling());
        existing.setPollingIntervalMs(updates.getPollingIntervalMs());
        existing.setLlmTriggerConfig(updates.getLlmTriggerConfig());
        existing.setDisplayOrder(updates.getDisplayOrder());
        existing.setPositionX(updates.getPositionX());
        existing.setPositionY(updates.getPositionY());
        existing.setOnEnterTaskId(updates.getOnEnterTaskId());
        existing.setOnExitTaskId(updates.getOnExitTaskId());

        return stateRepository.save(existing);
    }

    /**
     * Delete a state.
     */
    @Transactional
    public void deleteState(String instanceId, String stateId) {
        log.info("Deleting state {} for instance {}", stateId, instanceId);

        StateDefinition state = stateRepository.findById(stateId)
                .orElseThrow(() -> new IllegalArgumentException("State not found: " + stateId));

        if (state.isBuiltin()) {
            throw new IllegalArgumentException("Cannot delete builtin state: " + stateId);
        }

        // Delete related transitions
        transitionRepository.deleteByOrchestratorInstanceIdAndFromStateId(instanceId, stateId);
        transitionRepository.deleteByOrchestratorInstanceIdAndToStateId(instanceId, stateId);

        stateRepository.delete(state);
    }

    /**
     * Update state positions (for visual editor).
     */
    @Transactional
    public void updateStatePositions(String instanceId, List<StatePositionUpdate> positions) {
        for (StatePositionUpdate update : positions) {
            stateRepository.findById(update.getStateId()).ifPresent(state -> {
                state.setPositionX(update.getX());
                state.setPositionY(update.getY());
                stateRepository.save(state);
            });
        }
    }

    // ==================== Transition CRUD ====================

    /**
     * Get all transitions for an instance.
     */
    @Transactional(readOnly = true)
    public List<StateTransition> getTransitionsForInstance(String instanceId) {
        return transitionRepository.findByOrchestratorInstanceIdOrderByPriorityDesc(instanceId);
    }

    /**
     * Get transitions from a specific state.
     */
    @Transactional(readOnly = true)
    public List<StateTransition> getTransitionsFromState(String instanceId, String stateId) {
        return transitionRepository.findByOrchestratorInstanceIdAndFromStateIdOrderByPriorityDesc(instanceId, stateId);
    }

    /**
     * Get a transition by ID.
     */
    @Transactional(readOnly = true)
    public Optional<StateTransition> getTransition(Long transitionId) {
        return transitionRepository.findById(transitionId);
    }

    /**
     * Create a new transition.
     */
    @Transactional
    public StateTransition createTransition(String instanceId, StateTransition transition) {
        log.info("Creating transition from {} to {} for instance {}",
                transition.getFromStateId(), transition.getToStateId(), instanceId);

        transition.setOrchestratorInstanceId(instanceId);

        // Validate states exist
        if (!stateRepository.existsById(transition.getFromStateId())) {
            throw new IllegalArgumentException("From state not found: " + transition.getFromStateId());
        }
        if (!stateRepository.existsById(transition.getToStateId())) {
            throw new IllegalArgumentException("To state not found: " + transition.getToStateId());
        }

        // Also add to allowedNextStates in the source state
        stateRepository.findById(transition.getFromStateId()).ifPresent(fromState -> {
            if (fromState.getAllowedNextStates() == null) {
                fromState.setAllowedNextStates(new HashSet<>());
            }
            fromState.getAllowedNextStates().add(transition.getToStateId());
            stateRepository.save(fromState);
        });

        return transitionRepository.save(transition);
    }

    /**
     * Update an existing transition.
     */
    @Transactional
    public StateTransition updateTransition(String instanceId, Long transitionId, StateTransition updates) {
        log.info("Updating transition {} for instance {}", transitionId, instanceId);

        StateTransition existing = transitionRepository.findById(transitionId)
                .orElseThrow(() -> new IllegalArgumentException("Transition not found: " + transitionId));

        if (!existing.getOrchestratorInstanceId().equals(instanceId)) {
            throw new IllegalArgumentException("Transition belongs to different instance");
        }

        String oldToState = existing.getToStateId();
        String newToState = updates.getToStateId();

        existing.setFromStateId(updates.getFromStateId());
        existing.setToStateId(updates.getToStateId());
        existing.setName(updates.getName());
        existing.setDescription(updates.getDescription());
        existing.setConditionType(updates.getConditionType());
        existing.setConditionExpression(updates.getConditionExpression());
        existing.setAutoTrigger(updates.isAutoTrigger());
        existing.setPriority(updates.getPriority());
        existing.setOnTransitionTaskId(updates.getOnTransitionTaskId());
        existing.setEnabled(updates.isEnabled());
        existing.setLabel(updates.getLabel());

        // Update allowedNextStates if target changed
        if (!Objects.equals(oldToState, newToState)) {
            stateRepository.findById(existing.getFromStateId()).ifPresent(fromState -> {
                if (fromState.getAllowedNextStates() != null) {
                    fromState.getAllowedNextStates().remove(oldToState);
                    fromState.getAllowedNextStates().add(newToState);
                    stateRepository.save(fromState);
                }
            });
        }

        return transitionRepository.save(existing);
    }

    /**
     * Delete a transition.
     */
    @Transactional
    public void deleteTransition(String instanceId, Long transitionId) {
        log.info("Deleting transition {} for instance {}", transitionId, instanceId);

        StateTransition transition = transitionRepository.findById(transitionId)
                .orElseThrow(() -> new IllegalArgumentException("Transition not found: " + transitionId));

        if (!transition.getOrchestratorInstanceId().equals(instanceId)) {
            throw new IllegalArgumentException("Transition belongs to different instance");
        }

        // Check if there are other transitions with the same from/to
        List<StateTransition> others = transitionRepository.findByOrchestratorInstanceIdAndFromStateIdAndToStateId(
                instanceId, transition.getFromStateId(), transition.getToStateId());

        // If this is the only transition, remove from allowedNextStates
        if (others.size() <= 1) {
            stateRepository.findById(transition.getFromStateId()).ifPresent(fromState -> {
                if (fromState.getAllowedNextStates() != null) {
                    fromState.getAllowedNextStates().remove(transition.getToStateId());
                    stateRepository.save(fromState);
                }
            });
        }

        transitionRepository.delete(transition);
    }

    // ==================== Full State Machine Config ====================

    /**
     * Get the complete state machine configuration for an instance.
     */
    @Transactional(readOnly = true)
    public StateMachineConfig getStateMachineConfig(String instanceId) {
        List<StateDefinition> states = getStatesForInstance(instanceId);
        List<StateTransition> transitions = getTransitionsForInstance(instanceId);

        return StateMachineConfig.builder()
                .instanceId(instanceId)
                .states(states)
                .transitions(transitions)
                .stateCount(states.size())
                .transitionCount(transitions.size())
                .build();
    }

    /**
     * Import a full state machine configuration.
     */
    @Transactional
    public StateMachineConfig importStateMachineConfig(String instanceId, StateMachineConfig config) {
        log.info("Importing state machine config for instance {} with {} states and {} transitions",
                instanceId, config.getStates().size(), config.getTransitions().size());

        // Clear existing configuration
        transitionRepository.deleteByOrchestratorInstanceId(instanceId);
        stateRepository.deleteByOrchestratorInstanceId(instanceId);

        // Import states
        for (StateDefinition state : config.getStates()) {
            state.setOrchestratorInstanceId(instanceId);
            state.setBuiltin(false);
            stateRepository.save(state);
        }

        // Import transitions
        for (StateTransition transition : config.getTransitions()) {
            transition.setId(null);
            transition.setOrchestratorInstanceId(instanceId);
            transitionRepository.save(transition);
        }

        return getStateMachineConfig(instanceId);
    }

    /**
     * Create default states for an instance.
     */
    @Transactional
    public List<StateDefinition> createDefaultStates(String instanceId) {
        log.info("Creating default states for instance {}", instanceId);

        List<StateDefinition> defaults = new ArrayList<>();

        // Initial state
        defaults.add(StateDefinition.builder()
                .stateId(instanceId + "_CREATED")
                .orchestratorInstanceId(instanceId)
                .name("Created")
                .description("Initial state when workflow is created")
                .category(StateCategory.INITIAL)
                .displayOrder(0)
                .positionX(50.0)
                .positionY(200.0)
                .build());

        // Processing state
        defaults.add(StateDefinition.builder()
                .stateId(instanceId + "_PROCESSING")
                .orchestratorInstanceId(instanceId)
                .name("Processing")
                .description("Active processing state")
                .category(StateCategory.PROCESSING)
                .displayOrder(10)
                .positionX(250.0)
                .positionY(200.0)
                .build());

        // Waiting state
        defaults.add(StateDefinition.builder()
                .stateId(instanceId + "_AWAITING_APPROVAL")
                .orchestratorInstanceId(instanceId)
                .name("Awaiting Approval")
                .description("Waiting for user approval")
                .category(StateCategory.WAITING)
                .displayOrder(20)
                .positionX(450.0)
                .positionY(100.0)
                .build());

        // Success state
        defaults.add(StateDefinition.builder()
                .stateId(instanceId + "_COMPLETED")
                .orchestratorInstanceId(instanceId)
                .name("Completed")
                .description("Successfully completed")
                .category(StateCategory.TERMINAL)
                .displayOrder(30)
                .positionX(650.0)
                .positionY(200.0)
                .build());

        // Error state
        defaults.add(StateDefinition.builder()
                .stateId(instanceId + "_FAILED")
                .orchestratorInstanceId(instanceId)
                .name("Failed")
                .description("Failed with error")
                .category(StateCategory.ERROR)
                .displayOrder(40)
                .positionX(450.0)
                .positionY(300.0)
                .build());

        // Save all states and set up transitions
        for (StateDefinition state : defaults) {
            stateRepository.save(state);
        }

        // Create default transitions
        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_CREATED")
                .toStateId(instanceId + "_PROCESSING")
                .name("Start")
                .conditionType(StateTransition.TransitionConditionType.ALWAYS)
                .autoTrigger(true)
                .build());

        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_PROCESSING")
                .toStateId(instanceId + "_AWAITING_APPROVAL")
                .name("Needs Approval")
                .conditionType(StateTransition.TransitionConditionType.MANUAL)
                .autoTrigger(false)
                .build());

        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_PROCESSING")
                .toStateId(instanceId + "_COMPLETED")
                .name("Success")
                .conditionType(StateTransition.TransitionConditionType.ON_SUCCESS)
                .autoTrigger(true)
                .build());

        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_PROCESSING")
                .toStateId(instanceId + "_FAILED")
                .name("Failure")
                .conditionType(StateTransition.TransitionConditionType.ON_FAILURE)
                .autoTrigger(true)
                .build());

        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_AWAITING_APPROVAL")
                .toStateId(instanceId + "_PROCESSING")
                .name("Approved")
                .conditionType(StateTransition.TransitionConditionType.MANUAL)
                .autoTrigger(false)
                .build());

        createTransition(instanceId, StateTransition.builder()
                .fromStateId(instanceId + "_AWAITING_APPROVAL")
                .toStateId(instanceId + "_FAILED")
                .name("Rejected")
                .conditionType(StateTransition.TransitionConditionType.MANUAL)
                .autoTrigger(false)
                .build());

        return defaults;
    }

    // ==================== DTOs ====================

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StatePositionUpdate {
        private String stateId;
        private Double x;
        private Double y;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class StateMachineConfig {
        private String instanceId;
        private List<StateDefinition> states;
        private List<StateTransition> transitions;
        private int stateCount;
        private int transitionCount;
    }
}
