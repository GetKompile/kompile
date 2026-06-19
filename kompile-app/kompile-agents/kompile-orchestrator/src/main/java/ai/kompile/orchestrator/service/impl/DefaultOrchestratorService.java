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

import ai.kompile.orchestrator.api.*;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.event.GenericOrchestratorEvent;
import ai.kompile.orchestrator.model.event.OrchestratorEventType;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.state.DefaultState;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.task.TaskExecutionOptions;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.repository.OrchestratorInstanceRepository;
import ai.kompile.orchestrator.repository.OrchestratorSnapshotRepository;
import ai.kompile.orchestrator.service.TriggerManager;
import ai.kompile.orchestrator.service.registry.HookRegistry;
import ai.kompile.orchestrator.service.registry.StateRegistry;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Default implementation of OrchestratorService.
 * Coordinates all orchestrator components and provides the main API.
 */
@Slf4j
public class DefaultOrchestratorService implements OrchestratorService {

    private final OrchestratorInstanceRepository instanceRepository;
    private final OrchestratorSnapshotRepository snapshotRepository;
    private final StateMachineService stateMachineService;
    private final TaskExecutionService taskExecutionService;
    private final WorkflowService workflowService;
    private final LlmIntegrationService llmIntegrationService;
    private final StateRegistry stateRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    // Optional components (can be set after construction)
    private HookRegistry hookRegistry;
    private TriggerManager triggerManager;
    private SnapshotService snapshotService;
    private RecoveryService recoveryService;
    private AuditService auditService;

    public DefaultOrchestratorService(OrchestratorInstanceRepository instanceRepository,
                                      OrchestratorSnapshotRepository snapshotRepository,
                                      StateMachineService stateMachineService,
                                      TaskExecutionService taskExecutionService,
                                      WorkflowService workflowService,
                                      LlmIntegrationService llmIntegrationService,
                                      StateRegistry stateRegistry,
                                      ApplicationEventPublisher eventPublisher,
                                      OrchestratorProperties properties) {
        this.instanceRepository = instanceRepository;
        this.snapshotRepository = snapshotRepository;
        this.stateMachineService = stateMachineService;
        this.taskExecutionService = taskExecutionService;
        this.workflowService = workflowService;
        this.llmIntegrationService = llmIntegrationService;
        this.stateRegistry = stateRegistry;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    public void setTriggerManager(TriggerManager triggerManager) {
        this.triggerManager = triggerManager;
    }

    public void setSnapshotService(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public void setRecoveryService(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    // ==================== Instance Lifecycle ====================

    @Override
    public OrchestratorInstance create(String name, String description) {
        return create(name, description, Collections.emptyMap(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    @Override
    public OrchestratorInstance create(String name, String description,
                                       Map<String, Object> initialContext,
                                       List<StateDefinition> customStates,
                                       List<TaskDefinition> taskDefinitions,
                                       List<LlmTrigger> llmTriggers) {
        String instanceId = UUID.randomUUID().toString();

        log.info("Creating orchestrator instance: {} ({})", name, instanceId);

        OrchestratorInstance instance = OrchestratorInstance.builder()
                .instanceId(instanceId)
                .name(name)
                .description(description)
                .status(OrchestratorStatus.CREATED)
                .context(initialContext != null ? new HashMap<>(initialContext) : new HashMap<>())
                .createdAt(LocalDateTime.now())
                .build();

        // Serialize context for persistence
        if (initialContext != null && !initialContext.isEmpty()) {
            try {
                instance.setContextJson(objectMapper.writeValueAsString(initialContext));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize initial context", e);
            }
        }

        instance = instanceRepository.save(instance);

        // Register custom states
        if (customStates != null) {
            for (StateDefinition state : customStates) {
                stateMachineService.registerState(state);
            }
        }

        // Register task definitions
        if (taskDefinitions != null) {
            for (TaskDefinition task : taskDefinitions) {
                taskExecutionService.registerTaskDefinition(task);
            }
        }

        // Register LLM triggers
        if (llmTriggers != null) {
            for (LlmTrigger trigger : llmTriggers) {
                llmIntegrationService.registerTrigger(trigger);
            }
        }

        publishEvent(instance, OrchestratorEventType.ORCHESTRATOR_CREATED, "Orchestrator created");

        return instance;
    }

    @Override
    public void start(String instanceId) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (instance.getStatus() != OrchestratorStatus.CREATED &&
            instance.getStatus() != OrchestratorStatus.PAUSED) {
            throw new IllegalStateException("Cannot start orchestrator in state: " + instance.getStatus());
        }

        log.info("Starting orchestrator: {}", instanceId);

        // Execute pre-start hooks
        if (hookRegistry != null) {
            hookRegistry.executePreStart(instance);
        }

        // Update status
        instance.setStatus(OrchestratorStatus.INITIALIZING);
        instance.setStartedAt(LocalDateTime.now());
        instance = instanceRepository.save(instance);

        // Initialize state machine with IDLE state
        stateMachineService.initialize(instanceId, DefaultState.IDLE.getStateId());

        // Log to audit
        if (auditService != null) {
            auditService.logOrchestratorStarted(instance);
        }

        publishEvent(instance, OrchestratorEventType.ORCHESTRATOR_STARTED, "Orchestrator started");

        // Execute post-start hooks
        if (hookRegistry != null) {
            hookRegistry.executePostStart(instance);
        }
    }

    @Override
    public void pause(String instanceId) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (instance.getStatus() != OrchestratorStatus.RUNNING) {
            throw new IllegalStateException("Cannot pause orchestrator in state: " + instance.getStatus());
        }

        log.info("Pausing orchestrator: {}", instanceId);

        instance.setStatus(OrchestratorStatus.PAUSED);
        instanceRepository.save(instance);

        publishEvent(instance, OrchestratorEventType.ORCHESTRATOR_PAUSED, "Orchestrator paused");
    }

    @Override
    public void resume(String instanceId) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (instance.getStatus() != OrchestratorStatus.PAUSED) {
            throw new IllegalStateException("Cannot resume orchestrator in state: " + instance.getStatus());
        }

        log.info("Resuming orchestrator: {}", instanceId);

        instance.setStatus(OrchestratorStatus.RUNNING);
        instanceRepository.save(instance);

        publishEvent(instance, OrchestratorEventType.ORCHESTRATOR_RESUMED, "Orchestrator resumed");

        // Re-enter current state to resume processing
        if (instance.getCurrentStateId() != null) {
            stateMachineService.forceTransitionTo(instanceId, instance.getCurrentStateId());
        }
    }

    @Override
    public void stop(String instanceId) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (instance.isTerminal()) {
            return; // Already stopped
        }

        log.info("Stopping orchestrator: {}", instanceId);

        // Execute pre-stop hooks
        if (hookRegistry != null) {
            hookRegistry.executePreStop(instance);
        }

        // Cancel all active workflows
        for (Workflow workflow : workflowService.getActiveWorkflows(instanceId)) {
            try {
                workflowService.cancelWorkflow(workflow.getId());
            } catch (Exception e) {
                log.warn("Error cancelling workflow {}: {}", workflow.getId(), e.getMessage());
            }
        }

        // Cancel all active LLM sessions
        for (LlmSession session : llmIntegrationService.getActiveSessions(instanceId)) {
            try {
                llmIntegrationService.cancelSession(session.getId());
            } catch (Exception e) {
                log.warn("Error cancelling LLM session {}: {}", session.getId(), e.getMessage());
            }
        }

        // Cancel all running tasks
        for (TaskInstance task : taskExecutionService.getRunningTasks(instanceId)) {
            try {
                taskExecutionService.cancelTask(task.getId());
            } catch (Exception e) {
                log.warn("Error cancelling task {}: {}", task.getId(), e.getMessage());
            }
        }

        // Update status
        instance.setStatus(OrchestratorStatus.CANCELLED);
        instance.setCompletedAt(LocalDateTime.now());
        instanceRepository.save(instance);

        // Log to audit
        if (auditService != null) {
            auditService.logOrchestratorStopped(instance);
        }

        publishEvent(instance, OrchestratorEventType.ORCHESTRATOR_STOPPED, "Orchestrator stopped");

        // Execute post-stop hooks
        if (hookRegistry != null) {
            hookRegistry.executePostStop(instance);
        }
    }

    @Override
    public void delete(String instanceId) {
        OrchestratorInstance instance = getInstance(instanceId).orElse(null);
        if (instance == null) {
            return;
        }

        // Stop if running
        if (instance.isActive()) {
            stop(instanceId);
        }

        log.info("Deleting orchestrator: {}", instanceId);

        // Deactivate snapshots (mark inactive, then delete inactive ones)
        snapshotRepository.deactivateSnapshots(instanceId);
        snapshotRepository.deleteInactiveSnapshots(instanceId);

        // Clear trigger error counts
        if (triggerManager != null) {
            triggerManager.clearErrorCounts(instanceId);
        }

        // Delete instance
        instanceRepository.delete(instance);
    }

    // ==================== Instance Queries ====================

    @Override
    public Optional<OrchestratorInstance> getInstance(String instanceId) {
        return instanceRepository.findById(instanceId);
    }

    @Override
    public List<OrchestratorInstance> getAllInstances() {
        return instanceRepository.findAll();
    }

    @Override
    public List<OrchestratorInstance> getRunningInstances() {
        return instanceRepository.findAllActive();
    }

    @Override
    public List<OrchestratorInstance> getInstancesByStatus(OrchestratorStatus status) {
        return instanceRepository.findByStatus(status);
    }

    // ==================== State Management ====================

    @Override
    public Optional<StateDefinition> getCurrentState(String instanceId) {
        return stateMachineService.getCurrentState(instanceId);
    }

    @Override
    public void transitionTo(String instanceId, String targetStateId) {
        transitionTo(instanceId, targetStateId, Collections.emptyMap());
    }

    @Override
    public void transitionTo(String instanceId, String targetStateId, Map<String, Object> context) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (!instance.isActive()) {
            throw new IllegalStateException("Orchestrator is not active: " + instanceId);
        }

        // Check hooks
        StateDefinition fromState = stateMachineService.getCurrentState(instanceId).orElse(null);
        StateDefinition toState = stateRegistry.get(targetStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown state: " + targetStateId));

        if (hookRegistry != null) {
            if (!hookRegistry.executePreStateTransition(instance, fromState, toState, context)) {
                log.info("State transition aborted by hook");
                return;
            }
        }

        // Perform transition
        stateMachineService.transitionTo(instanceId, targetStateId, context);

        // Check triggers
        if (triggerManager != null && toState != null) {
            triggerManager.checkStateEnterTriggers(instance, toState, context);
        }

        // Post-transition hooks
        if (hookRegistry != null) {
            hookRegistry.executePostStateTransition(instance, fromState, toState, context);
        }
    }

    @Override
    public void registerState(String instanceId, StateDefinition state) {
        stateMachineService.registerState(state);
    }

    @Override
    public void registerStateHandler(String stateId, StateHandler handler) {
        stateMachineService.registerHandler(stateId, handler);
    }

    // ==================== Task Management ====================

    @Override
    public TaskInstance executeTask(String instanceId, String taskDefinitionId, Map<String, String> variables) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (!instance.isActive()) {
            throw new IllegalStateException("Orchestrator is not active: " + instanceId);
        }

        TaskExecutionOptions options = TaskExecutionOptions.builder()
                .workingDirectory(instance.getWorkingDirectory())
                .streamOutput(properties.getTask().isStreamOutput())
                .build();

        // Log to audit
        TaskInstance task = taskExecutionService.executeTask(taskDefinitionId, variables, instanceId, options);

        if (auditService != null) {
            auditService.logTaskStarted(task);
        }

        return task;
    }

    @Override
    public TaskInstance executeCommand(String instanceId, String command) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (!instance.isActive()) {
            throw new IllegalStateException("Orchestrator is not active: " + instanceId);
        }

        TaskExecutionOptions options = TaskExecutionOptions.builder()
                .workingDirectory(instance.getWorkingDirectory())
                .streamOutput(properties.getTask().isStreamOutput())
                .build();

        return taskExecutionService.executeCommand(command, instanceId, options);
    }

    @Override
    public void cancelTask(Long taskInstanceId) {
        taskExecutionService.cancelTask(taskInstanceId);
    }

    @Override
    public void registerTaskDefinition(TaskDefinition definition) {
        taskExecutionService.registerTaskDefinition(definition);
    }

    // ==================== Workflow Management ====================

    @Override
    public Workflow startWorkflow(String instanceId, String name, String initialPrompt) {
        return startWorkflow(instanceId, name, initialPrompt,
                properties.getWorkflow().isAutoAdvance(),
                properties.getWorkflow().getMaxSteps());
    }

    @Override
    public Workflow startWorkflow(String instanceId, String name, String initialPrompt,
                                  boolean autoAdvance, Integer maxSteps) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        if (!instance.isActive()) {
            throw new IllegalStateException("Orchestrator is not active: " + instanceId);
        }

        Workflow workflow = workflowService.startWorkflow(name, null, initialPrompt,
                instanceId, autoAdvance, maxSteps, properties.getWorkflow().getDefaultLlmProvider());

        if (auditService != null) {
            auditService.logWorkflowStarted(workflow);
        }

        return workflow;
    }

    @Override
    public void advanceWorkflow(Long workflowId) {
        workflowService.advanceWorkflow(workflowId);
    }

    @Override
    public void approveWorkflowStep(Long workflowId, Integer stepNumber) {
        workflowService.approveStep(workflowId, stepNumber);
    }

    @Override
    public void rejectWorkflowStep(Long workflowId, Integer stepNumber, String feedback) {
        workflowService.rejectStep(workflowId, stepNumber, feedback);
    }

    @Override
    public void cancelWorkflow(Long workflowId) {
        workflowService.cancelWorkflow(workflowId);
    }

    // ==================== LLM Management ====================

    @Override
    public LlmSession invokeLlm(String instanceId, String prompt) {
        return llmIntegrationService.invoke(prompt, instanceId);
    }

    @Override
    public LlmSession invokeLlm(String instanceId, String providerId, String prompt) {
        return llmIntegrationService.invoke(providerId, prompt, instanceId);
    }

    @Override
    public void cancelLlmSession(Long sessionId) {
        llmIntegrationService.cancelSession(sessionId);
    }

    @Override
    public void registerLlmTrigger(LlmTrigger trigger) {
        llmIntegrationService.registerTrigger(trigger);
    }

    @Override
    public void registerLlmProvider(LlmProvider provider) {
        llmIntegrationService.registerProvider(provider);
    }

    // ==================== Context Management ====================

    @Override
    public void updateContext(String instanceId, Map<String, Object> context) {
        OrchestratorInstance instance = getInstance(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

        instance.updateContext(context);

        // Serialize for persistence
        try {
            instance.setContextJson(objectMapper.writeValueAsString(instance.getContext()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize context", e);
        }

        instanceRepository.save(instance);
    }

    @Override
    public Map<String, Object> getContext(String instanceId) {
        return getInstance(instanceId)
                .map(OrchestratorInstance::getContext)
                .orElse(Collections.emptyMap());
    }

    // ==================== Recovery ====================

    @Override
    public void createSnapshot(String instanceId) {
        if (snapshotService != null) {
            snapshotService.createSnapshot(instanceId);
        }
    }

    @Override
    public void recover(String instanceId) {
        if (recoveryService != null) {
            OrchestratorInstance instance = getInstance(instanceId)
                    .orElseThrow(() -> new IllegalArgumentException("Orchestrator not found: " + instanceId));

            // Execute pre-recovery hooks
            if (hookRegistry != null) {
                hookRegistry.executePreRecovery(instance);
            }

            boolean success = recoveryService.recoverOrchestrator(instanceId);

            // Execute post-recovery hooks
            if (success && hookRegistry != null) {
                hookRegistry.executePostRecovery(instance);
            }
        }
    }

    // ==================== Hook Registration ====================

    /**
     * Register an orchestrator hook.
     */
    public void registerHook(OrchestratorHook hook) {
        if (hookRegistry != null) {
            hookRegistry.register(hook);
        }
    }

    /**
     * Unregister an orchestrator hook.
     */
    public void unregisterHook(String hookId) {
        if (hookRegistry != null) {
            hookRegistry.unregister(hookId);
        }
    }

    // ==================== Utility ====================

    private void publishEvent(OrchestratorInstance instance, OrchestratorEventType eventType, String message) {
        eventPublisher.publishEvent(new GenericOrchestratorEvent(this, instance.getInstanceId(), eventType, message));
    }
}
