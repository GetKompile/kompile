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

import ai.kompile.orchestrator.api.StateHandler;
import ai.kompile.orchestrator.api.StateMachineService;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.event.StateChangeEvent;
import ai.kompile.orchestrator.model.state.DefaultState;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.state.StateHandlerResult;
import ai.kompile.orchestrator.model.task.TaskExecutionOptions;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.repository.OrchestratorInstanceRepository;
import ai.kompile.orchestrator.service.registry.StateHandlerRegistry;
import ai.kompile.orchestrator.service.registry.StateRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Default implementation of StateMachineService.
 * Manages state transitions, handlers, and polling logic.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultStateMachineService implements StateMachineService {

    private final StateRegistry stateRegistry;
    private final StateHandlerRegistry handlerRegistry;
    private final OrchestratorInstanceRepository instanceRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final ScheduledExecutorService pollingExecutor = Executors.newScheduledThreadPool(4);
    private final Map<String, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();

    /**
     * Optional task execution service for executing onEnterTaskId/onExitTaskId hooks.
     * Set via setter to avoid circular dependency with TaskExecutionService.
     */
    private TaskExecutionService taskExecutionService;

    public void setTaskExecutionService(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    // ==================== State Registration ====================

    @Override
    public void registerState(StateDefinition state) {
        stateRegistry.register(state);
        log.info("Registered state: {} ({})", state.getStateId(), state.getName());
    }

    @Override
    public void registerStates(Collection<StateDefinition> states) {
        states.forEach(this::registerState);
    }

    @Override
    public void unregisterState(String stateId) {
        stateRegistry.unregister(stateId);
        log.info("Unregistered state: {}", stateId);
    }

    // ==================== State Queries ====================

    @Override
    public Optional<StateDefinition> getState(String stateId) {
        return stateRegistry.get(stateId);
    }

    @Override
    public Set<StateDefinition> getAllStates() {
        return stateRegistry.getAll();
    }

    @Override
    public Set<String> getAllowedTransitions(String fromStateId) {
        return stateRegistry.getAllowedTransitions(fromStateId);
    }

    @Override
    public boolean stateExists(String stateId) {
        return stateRegistry.exists(stateId);
    }

    // ==================== State Transitions ====================

    @Override
    public void transitionTo(String orchestratorInstanceId, String targetStateId) {
        transitionTo(orchestratorInstanceId, targetStateId, Collections.emptyMap());
    }

    @Override
    public void transitionTo(String orchestratorInstanceId, String targetStateId, Map<String, Object> context) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator instance not found: " + orchestratorInstanceId));

        String currentStateId = instance.getCurrentStateId();

        // Validate transition
        if (!canTransitionTo(orchestratorInstanceId, targetStateId)) {
            throw new IllegalStateException(String.format(
                    "Transition from '%s' to '%s' is not allowed for orchestrator '%s'",
                    currentStateId, targetStateId, orchestratorInstanceId));
        }

        performTransition(instance, targetStateId, context, false);
    }

    @Override
    public boolean canTransitionTo(String orchestratorInstanceId, String targetStateId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElse(null);

        if (instance == null) {
            return false;
        }

        String currentStateId = instance.getCurrentStateId();

        // If no current state (initial), any initial state is allowed
        if (currentStateId == null) {
            StateDefinition targetState = stateRegistry.get(targetStateId).orElse(null);
            return targetState != null && targetState.isInitial();
        }

        return stateRegistry.isTransitionAllowed(currentStateId, targetStateId);
    }

    @Override
    public void forceTransitionTo(String orchestratorInstanceId, String targetStateId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator instance not found: " + orchestratorInstanceId));

        performTransition(instance, targetStateId, Collections.emptyMap(), true);
    }

    private void performTransition(OrchestratorInstance instance, String targetStateId,
                                   Map<String, Object> context, boolean forced) {
        String previousStateId = instance.getCurrentStateId();
        StateDefinition previousState = previousStateId != null
                ? stateRegistry.get(previousStateId).orElse(null)
                : null;
        StateDefinition targetState = stateRegistry.get(targetStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown target state: " + targetStateId));

        log.info("Transitioning orchestrator {} from {} to {} {}",
                instance.getInstanceId(), previousStateId, targetStateId, forced ? "(forced)" : "");

        // Stop any polling for the previous state
        stopPolling(instance.getInstanceId());

        // Exit previous state
        if (previousState != null) {
            executeStateExit(instance, previousState, context);
        }

        // Update instance state
        instance.setPreviousStateId(previousStateId);
        instance.setCurrentStateId(targetStateId);
        if (context != null && !context.isEmpty()) {
            instance.updateContext(context);
        }

        // Update orchestrator status based on state category
        updateOrchestratorStatus(instance, targetState);

        instanceRepository.save(instance);

        // Publish state change event
        eventPublisher.publishEvent(new StateChangeEvent(
                this, instance.getInstanceId(), previousStateId, targetStateId,
                instance.getContext()));

        // Enter new state
        executeStateEnter(instance, targetState, context);

        // Handle the new state
        handleState(instance, targetState);
    }

    private void updateOrchestratorStatus(OrchestratorInstance instance, StateDefinition state) {
        switch (state.getCategory()) {
            case INITIAL:
                instance.setStatus(OrchestratorStatus.INITIALIZING);
                break;
            case TERMINAL:
                if (state.getStateId().equals(DefaultState.SUCCESS.getStateId())) {
                    instance.setStatus(OrchestratorStatus.COMPLETED);
                } else if (state.getStateId().equals(DefaultState.CANCELLED.getStateId())) {
                    instance.setStatus(OrchestratorStatus.CANCELLED);
                } else {
                    instance.setStatus(OrchestratorStatus.COMPLETED);
                }
                break;
            case ERROR:
                instance.setStatus(OrchestratorStatus.FAILED);
                break;
            case WAITING:
                instance.setStatus(OrchestratorStatus.PAUSED);
                break;
            case PROCESSING:
            default:
                instance.setStatus(OrchestratorStatus.RUNNING);
                break;
        }
    }

    private void executeStateEnter(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        Optional<StateHandler> handler = handlerRegistry.get(state.getStateId());
        if (handler.isPresent()) {
            try {
                eventPublisher.publishEvent(StateChangeEvent.entering(
                        this, instance.getInstanceId(), state.getStateId(), context));
                handler.get().onEnter(instance, state, context);
            } catch (Exception e) {
                log.error("Error in onEnter handler for state {}: {}", state.getStateId(), e.getMessage(), e);
            }
        }

        // Execute onEnterTaskId if configured
        if (state.getOnEnterTaskId() != null && taskExecutionService != null) {
            try {
                log.info("Executing onEnter task '{}' for state '{}' on orchestrator {}",
                        state.getOnEnterTaskId(), state.getStateId(), instance.getInstanceId());
                TaskExecutionOptions options = TaskExecutionOptions.builder()
                        .async(true)
                        .streamOutput(true)
                        .build();
                TaskInstance task = taskExecutionService.executeTask(
                        state.getOnEnterTaskId(),
                        Collections.emptyMap(),
                        instance.getInstanceId(),
                        options);
                log.info("Started onEnter task {} (instance {}) for state '{}'",
                        state.getOnEnterTaskId(), task.getId(), state.getStateId());
            } catch (Exception e) {
                log.error("Error executing onEnter task '{}' for state {}: {}",
                        state.getOnEnterTaskId(), state.getStateId(), e.getMessage(), e);
            }
        }
    }

    private void executeStateExit(OrchestratorInstance instance, StateDefinition state, Map<String, Object> context) {
        Optional<StateHandler> handler = handlerRegistry.get(state.getStateId());
        if (handler.isPresent()) {
            try {
                eventPublisher.publishEvent(StateChangeEvent.exiting(
                        this, instance.getInstanceId(), state.getStateId(), context));
                handler.get().onExit(instance, state, context);
            } catch (Exception e) {
                log.error("Error in onExit handler for state {}: {}", state.getStateId(), e.getMessage(), e);
            }
        }

        // Execute onExitTaskId if configured
        if (state.getOnExitTaskId() != null && taskExecutionService != null) {
            try {
                log.info("Executing onExit task '{}' for state '{}' on orchestrator {}",
                        state.getOnExitTaskId(), state.getStateId(), instance.getInstanceId());
                TaskExecutionOptions options = TaskExecutionOptions.builder()
                        .async(true)
                        .streamOutput(true)
                        .build();
                TaskInstance task = taskExecutionService.executeTask(
                        state.getOnExitTaskId(),
                        Collections.emptyMap(),
                        instance.getInstanceId(),
                        options);
                log.info("Started onExit task {} (instance {}) for state '{}'",
                        state.getOnExitTaskId(), task.getId(), state.getStateId());
            } catch (Exception e) {
                log.error("Error executing onExit task '{}' for state {}: {}",
                        state.getOnExitTaskId(), state.getStateId(), e.getMessage(), e);
            }
        }
    }

    private void handleState(OrchestratorInstance instance, StateDefinition state) {
        Optional<StateHandler> handlerOpt = handlerRegistry.get(state.getStateId());

        if (handlerOpt.isEmpty()) {
            // No handler - check for auto-advance
            if (state.isAutoAdvance() && state.getAllowedNextStates() != null && !state.getAllowedNextStates().isEmpty()) {
                String nextState = state.getAllowedNextStates().iterator().next();
                log.debug("Auto-advancing from {} to {}", state.getStateId(), nextState);
                performTransition(instance, nextState, Collections.emptyMap(), false);
            }
            return;
        }

        StateHandler handler = handlerOpt.get();

        if (state.isPolling() || handler.isPolling()) {
            // Start polling
            startPolling(instance, state, handler);
        } else {
            // Execute once
            executeHandler(instance, state, handler);
        }
    }

    private void executeHandler(OrchestratorInstance instance, StateDefinition state, StateHandler handler) {
        try {
            Map<String, Object> context = instance.getContext() != null
                    ? new HashMap<>(instance.getContext())
                    : new HashMap<>();

            StateHandlerResult result = handler.handle(instance, state, context);

            // Apply context updates
            if (result.getContextUpdates() != null && !result.getContextUpdates().isEmpty()) {
                instance.updateContext(result.getContextUpdates());
                instanceRepository.save(instance);
            }

            // Handle result
            processHandlerResult(instance, state, result);

        } catch (Exception e) {
            log.error("Error executing handler for state {}: {}", state.getStateId(), e.getMessage(), e);
            StateHandlerResult errorResult = StateHandlerResult.error("Handler execution failed: " + e.getMessage(), e);
            processHandlerResult(instance, state, errorResult);
        }
    }

    private void processHandlerResult(OrchestratorInstance instance, StateDefinition state, StateHandlerResult result) {
        if (result.isError()) {
            log.warn("Handler error in state {}: {}", state.getStateId(), result.getErrorMessage());
            instance.setErrorMessage(result.getErrorMessage());
            instanceRepository.save(instance);
        }

        // Check for LLM trigger
        if (result.isTriggerLlm()) {
            log.info("LLM trigger requested by handler in state {}", state.getStateId());
            // LLM triggering will be handled by DefaultOrchestratorService
        }

        // Check for state transition
        if (result.isComplete() && result.getNextStateId() != null) {
            String nextStateId = result.getNextStateId();
            Map<String, Object> context = result.getContextUpdates() != null
                    ? result.getContextUpdates()
                    : Collections.emptyMap();

            // Validate and perform transition
            if (stateRegistry.isTransitionAllowed(state.getStateId(), nextStateId) ||
                nextStateId.equals(DefaultState.FAILED.getStateId())) {
                performTransition(instance, nextStateId, context, result.isError());
            } else {
                log.warn("Handler suggested invalid transition from {} to {}", state.getStateId(), nextStateId);
            }
        } else if (!result.isComplete() && !state.isPolling()) {
            // Handler wants to stay but state is not polling - this is unexpected
            log.debug("Handler returned incomplete but state {} is not polling", state.getStateId());
        }
    }

    private void startPolling(OrchestratorInstance instance, StateDefinition state, StateHandler handler) {
        String instanceId = instance.getInstanceId();
        long intervalMs = handler.getPollingInterval() != null
                ? handler.getPollingInterval().toMillis()
                : state.getPollingIntervalMs();

        log.debug("Starting polling for orchestrator {} in state {} (interval: {}ms)",
                instanceId, state.getStateId(), intervalMs);

        ScheduledFuture<?> future = pollingExecutor.scheduleAtFixedRate(() -> {
            try {
                // Reload instance
                OrchestratorInstance current = instanceRepository.findById(instanceId).orElse(null);
                if (current == null || !current.getCurrentStateId().equals(state.getStateId())) {
                    // Instance was removed or state changed - stop polling
                    stopPolling(instanceId);
                    return;
                }

                executeHandler(current, state, handler);

            } catch (Exception e) {
                log.error("Error during polling for orchestrator {} in state {}: {}",
                        instanceId, state.getStateId(), e.getMessage(), e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        pollingTasks.put(instanceId, future);
    }

    private void stopPolling(String instanceId) {
        ScheduledFuture<?> future = pollingTasks.remove(instanceId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped polling for orchestrator {}", instanceId);
        }
    }

    // ==================== Current State ====================

    @Override
    public String getCurrentStateId(String orchestratorInstanceId) {
        return instanceRepository.findById(orchestratorInstanceId)
                .map(OrchestratorInstance::getCurrentStateId)
                .orElse(null);
    }

    @Override
    public Optional<StateDefinition> getCurrentState(String orchestratorInstanceId) {
        String stateId = getCurrentStateId(orchestratorInstanceId);
        return stateId != null ? stateRegistry.get(stateId) : Optional.empty();
    }

    // ==================== Handler Management ====================

    @Override
    public void registerHandler(String stateId, StateHandler handler) {
        handlerRegistry.register(stateId, handler);
        log.info("Registered handler for state: {}", stateId);
    }

    @Override
    public Optional<StateHandler> getHandler(String stateId) {
        return handlerRegistry.get(stateId);
    }

    @Override
    public void unregisterHandler(String stateId) {
        StateHandler handler = handlerRegistry.get(stateId).orElse(null);
        if (handler != null) {
            handlerRegistry.unregister(stateId, handler);
            log.info("Unregistered handler for state: {}", stateId);
        }
    }

    // ==================== Lifecycle ====================

    @Override
    public void initialize(String orchestratorInstanceId, String initialStateId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator instance not found: " + orchestratorInstanceId));

        if (instance.getCurrentStateId() != null) {
            throw new IllegalStateException("Orchestrator already initialized: " + orchestratorInstanceId);
        }

        StateDefinition initialState = stateRegistry.get(initialStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown initial state: " + initialStateId));

        if (!initialState.isInitial()) {
            log.warn("Initializing orchestrator with non-initial state: {}", initialStateId);
        }

        performTransition(instance, initialStateId, Collections.emptyMap(), false);
    }

    @Override
    public void reset(String orchestratorInstanceId) {
        OrchestratorInstance instance = instanceRepository.findById(orchestratorInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator instance not found: " + orchestratorInstanceId));

        stopPolling(orchestratorInstanceId);

        instance.setCurrentStateId(null);
        instance.setPreviousStateId(null);
        instance.setStatus(OrchestratorStatus.CREATED);
        instance.setErrorMessage(null);
        instance.getContext().clear();

        instanceRepository.save(instance);

        log.info("Reset orchestrator: {}", orchestratorInstanceId);
    }

    /**
     * Shutdown the polling executor.
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down state machine service");
        pollingTasks.values().forEach(f -> f.cancel(true));
        pollingTasks.clear();
        pollingExecutor.shutdown();
        try {
            if (!pollingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                pollingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
