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

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.api.WorkflowService;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.event.WorkflowEvent;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.task.TaskExecutionOptions;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.*;
import ai.kompile.orchestrator.repository.WorkflowRepository;
import ai.kompile.orchestrator.repository.WorkflowStepRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Default implementation of WorkflowService.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultWorkflowService implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final LlmIntegrationService llmService;
    private final TaskExecutionService taskService;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Workflow Lifecycle ====================

    @Override
    public Workflow startWorkflow(String name, String description, String initialPrompt, String orchestratorInstanceId) {
        return startWorkflow(name, description, initialPrompt, orchestratorInstanceId,
                properties.getWorkflow().isAutoAdvance(),
                properties.getWorkflow().getMaxSteps(),
                properties.getWorkflow().getDefaultLlmProvider());
    }

    @Override
    public Workflow startWorkflow(String name, String description, String initialPrompt,
                                  String orchestratorInstanceId, boolean autoAdvance,
                                  Integer maxSteps, String llmProviderId) {
        log.info("Starting workflow '{}' for orchestrator {}", name, orchestratorInstanceId);

        Workflow workflow = Workflow.builder()
                .name(name)
                .description(description)
                .initialPrompt(initialPrompt)
                .orchestratorInstanceId(orchestratorInstanceId)
                .status(WorkflowStatus.IN_PROGRESS)
                .autoAdvance(autoAdvance)
                .maxSteps(maxSteps != null ? maxSteps : properties.getWorkflow().getMaxSteps())
                .llmProviderId(llmProviderId)
                .startTime(LocalDateTime.now())
                .currentStepNumber(0)
                .completedSteps(0)
                .build();

        workflow = workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.started(this, workflow));

        // Start the first step
        startNextStep(workflow);

        return workflow;
    }

    @Override
    public void advanceWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        if (!workflow.isActive()) {
            throw new IllegalStateException("Workflow is not active: " + workflowId);
        }

        WorkflowStep currentStep = workflow.getCurrentStep();
        if (currentStep == null) {
            // No current step, start first step
            startNextStep(workflow);
            return;
        }

        if (currentStep.getStatus() == WorkflowStepStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Current step requires approval before advancing");
        }

        if (!currentStep.getStatus().isTerminal()) {
            throw new IllegalStateException("Current step is not complete");
        }

        // Check if we've reached max steps
        if (workflow.hasReachedMaxSteps()) {
            workflow.markCompleted("Maximum steps reached");
            workflowRepository.save(workflow);
            eventPublisher.publishEvent(WorkflowEvent.completed(this, workflow));
            return;
        }

        // Check if last step was terminal
        if (currentStep.isTerminal()) {
            workflow.markCompleted("Workflow completed by terminal step");
            workflowRepository.save(workflow);
            eventPublisher.publishEvent(WorkflowEvent.completed(this, workflow));
            return;
        }

        // Advance to next step
        startNextStep(workflow);
    }

    private void startNextStep(Workflow workflow) {
        // Get context from previous step
        Map<String, Object> context = getContext(workflow.getId());
        WorkflowStep previousStep = workflow.getCurrentStep();
        if (previousStep != null && previousStep.getNextAction() != null) {
            // Add previous step's output to context
            context.put("previousAction", previousStep.getNextAction().getActionType().name());
            context.put("previousOutput", previousStep.getTaskOutput());
        }

        // Create new step
        int nextStepNumber = workflow.getSteps().size();
        WorkflowStep step = WorkflowStep.builder()
                .workflow(workflow)
                .stepNumber(nextStepNumber)
                .status(WorkflowStepStatus.RUNNING)
                .startTime(LocalDateTime.now())
                .build();

        // Store context
        try {
            step.setInputContextJson(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize context", e);
        }

        step = stepRepository.save(step);
        workflow.getSteps().add(step);
        workflow.setCurrentStepNumber(nextStepNumber);
        workflow = workflowRepository.save(workflow);

        // Invoke LLM to analyze and propose next action
        invokeLlmForStep(workflow, step, context);
    }

    private void invokeLlmForStep(Workflow workflow, WorkflowStep step, Map<String, Object> context) {
        String prompt = buildStepPrompt(workflow, step, context);

        LlmSessionRequest request = LlmSessionRequest.builder()
                .prompt(prompt)
                .orchestratorInstanceId(workflow.getOrchestratorInstanceId())
                .workflowId(workflow.getId())
                .workflowStepId(step.getId())
                .build();

        try {
            String providerId = workflow.getLlmProviderId();
            LlmSession session = providerId != null
                    ? llmService.startSession(providerId, request)
                    : llmService.startSession(request);

            step.setLlmSessionId(session.getId());
            stepRepository.save(step);

            // Parse action proposal when session completes
            // This would typically be handled by an event listener
            log.info("Started LLM session {} for workflow step {}", session.getId(), step.getId());

        } catch (Exception e) {
            log.error("Failed to invoke LLM for workflow step: {}", e.getMessage(), e);
            step.markFailed("LLM invocation failed: " + e.getMessage());
            stepRepository.save(step);

            workflow.markFailed("LLM invocation failed: " + e.getMessage());
            workflowRepository.save(workflow);
            eventPublisher.publishEvent(WorkflowEvent.failed(this, workflow));
        }
    }

    private String buildStepPrompt(Workflow workflow, WorkflowStep step, Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are helping with a multi-step workflow.\n\n");
        prompt.append("Workflow: ").append(workflow.getName()).append("\n");
        prompt.append("Goal: ").append(workflow.getInitialPrompt()).append("\n\n");

        if (step.getStepNumber() > 0) {
            prompt.append("Previous steps completed: ").append(step.getStepNumber()).append("\n");
            if (context.get("previousOutput") != null) {
                prompt.append("Previous step output: ").append(context.get("previousOutput")).append("\n");
            }
        }

        prompt.append("\nAnalyze the current state and propose the next action. ");
        prompt.append("Return a JSON response with:\n");
        prompt.append("- type: The action type (SHELL_COMMAND, LLM_QUERY, TASK, CUSTOM, COMPLETE, ERROR)\n");
        prompt.append("- command: The command to execute (for SHELL_COMMAND)\n");
        prompt.append("- description: Description of the action\n");
        prompt.append("- reasoning: Why this action is needed\n");
        prompt.append("- expectedOutcome: What you expect to happen\n");
        prompt.append("- finalStep: true if this completes the workflow\n");

        return prompt.toString();
    }

    @Override
    public void pauseWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        if (workflow.getStatus() != WorkflowStatus.IN_PROGRESS) {
            throw new IllegalStateException("Workflow is not in progress: " + workflowId);
        }

        workflow.setStatus(WorkflowStatus.PAUSED);
        workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.paused(this, workflow));
        log.info("Paused workflow: {}", workflowId);
    }

    @Override
    public void resumeWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        if (workflow.getStatus() != WorkflowStatus.PAUSED) {
            throw new IllegalStateException("Workflow is not paused: " + workflowId);
        }

        workflow.setStatus(WorkflowStatus.IN_PROGRESS);
        workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.resumed(this, workflow));
        log.info("Resumed workflow: {}", workflowId);

        // Continue execution if auto-advance
        if (workflow.isAutoAdvance()) {
            advanceWorkflow(workflowId);
        }
    }

    @Override
    public void cancelWorkflow(Long workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        if (!workflow.isActive()) {
            return;
        }

        // Cancel any running tasks
        WorkflowStep currentStep = workflow.getCurrentStep();
        if (currentStep != null && currentStep.getTaskInstanceId() != null) {
            try {
                taskService.cancelTask(currentStep.getTaskInstanceId());
            } catch (Exception e) {
                log.warn("Error cancelling task for workflow step: {}", e.getMessage());
            }
        }

        // Cancel any running LLM sessions
        if (currentStep != null && currentStep.getLlmSessionId() != null) {
            try {
                llmService.cancelSession(currentStep.getLlmSessionId());
            } catch (Exception e) {
                log.warn("Error cancelling LLM session for workflow step: {}", e.getMessage());
            }
        }

        workflow.markCancelled();
        workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.cancelled(this, workflow));
        log.info("Cancelled workflow: {}", workflowId);
    }

    // ==================== Step Management ====================

    @Override
    public void approveStep(Long workflowId, Integer stepNumber) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WorkflowStep step = workflow.getStep(stepNumber);
        if (step == null) {
            throw new IllegalArgumentException("Step not found: " + stepNumber);
        }

        if (step.getStatus() != WorkflowStepStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Step is not waiting for approval");
        }

        step.approve();
        stepRepository.save(step);

        log.info("Approved workflow step: workflow={}, step={}", workflowId, stepNumber);

        // Execute the approved action
        executeStepAction(workflow, step);
    }

    @Override
    public void rejectStep(Long workflowId, Integer stepNumber, String feedback) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WorkflowStep step = workflow.getStep(stepNumber);
        if (step == null) {
            throw new IllegalArgumentException("Step not found: " + stepNumber);
        }

        step.markRejected(feedback);
        stepRepository.save(step);

        log.info("Rejected workflow step: workflow={}, step={}, reason={}", workflowId, stepNumber, feedback);

        // Re-invoke LLM with feedback
        Map<String, Object> context = getContext(workflowId);
        context.put("rejectionFeedback", feedback);
        invokeLlmForStep(workflow, step, context);
    }

    @Override
    public void skipStep(Long workflowId, Integer stepNumber, String reason) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WorkflowStep step = workflow.getStep(stepNumber);
        if (step == null) {
            throw new IllegalArgumentException("Step not found: " + stepNumber);
        }

        step.markSkipped(reason);
        stepRepository.save(step);

        workflow.incrementCompletedSteps();
        workflowRepository.save(workflow);

        log.info("Skipped workflow step: workflow={}, step={}, reason={}", workflowId, stepNumber, reason);

        // Advance to next step if auto-advance
        if (workflow.isAutoAdvance()) {
            advanceWorkflow(workflowId);
        }
    }

    private void executeStepAction(Workflow workflow, WorkflowStep step) {
        ActionProposal action = step.getNextAction();
        if (action == null) {
            log.warn("No action to execute for step {}", step.getId());
            return;
        }

        step.markRunning();
        stepRepository.save(step);

        try {
            switch (action.getActionType()) {
                case EXECUTE_COMMAND:
                    executeShellCommand(workflow, step, action);
                    break;
                case RUN_TASK:
                    executeTask(workflow, step, action);
                    break;
                case INVOKE_LLM:
                    executeLlmQuery(workflow, step, action);
                    break;
                case COMPLETE:
                    completeWorkflow(workflow, step, action);
                    break;
                case FAIL:
                    failWorkflow(workflow, step, action);
                    break;
                default:
                    executeCustomAction(workflow, step, action);
                    break;
            }
        } catch (Exception e) {
            log.error("Error executing step action: {}", e.getMessage(), e);
            step.markFailed("Action execution failed: " + e.getMessage());
            stepRepository.save(step);
        }
    }

    private void executeShellCommand(Workflow workflow, WorkflowStep step, ActionProposal action) {
        TaskExecutionOptions options = TaskExecutionOptions.builder()
                .async(true)
                .streamOutput(true)
                .workingDirectory(workflow.getWorkingDirectory())
                .build();

        TaskInstance task = taskService.executeCommand(
                action.getCommand(),
                workflow.getOrchestratorInstanceId(),
                options);

        step.setTaskInstanceId(task.getId());
        stepRepository.save(step);
    }

    private void executeTask(Workflow workflow, WorkflowStep step, ActionProposal action) {
        TaskExecutionOptions options = TaskExecutionOptions.builder()
                .async(true)
                .streamOutput(true)
                .workingDirectory(workflow.getWorkingDirectory())
                .build();

        TaskInstance task = taskService.executeTask(
                action.getTaskDefinitionId(),
                Collections.emptyMap(),
                workflow.getOrchestratorInstanceId(),
                options);

        step.setTaskInstanceId(task.getId());
        stepRepository.save(step);
    }

    private void executeLlmQuery(Workflow workflow, WorkflowStep step, ActionProposal action) {
        LlmSessionRequest request = LlmSessionRequest.builder()
                .prompt(action.getLlmPrompt())
                .orchestratorInstanceId(workflow.getOrchestratorInstanceId())
                .workflowId(workflow.getId())
                .workflowStepId(step.getId())
                .build();

        LlmSession session = workflow.getLlmProviderId() != null
                ? llmService.startSession(workflow.getLlmProviderId(), request)
                : llmService.startSession(request);

        step.setLlmSessionId(session.getId());
        stepRepository.save(step);
    }

    private void completeWorkflow(Workflow workflow, WorkflowStep step, ActionProposal action) {
        step.markCompleted("Workflow completed", action);
        stepRepository.save(step);

        workflow.markCompleted(action.getReasoning());
        workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.completed(this, workflow));
    }

    private void failWorkflow(Workflow workflow, WorkflowStep step, ActionProposal action) {
        step.markFailed(action.getReasoning());
        stepRepository.save(step);

        workflow.markFailed(action.getReasoning());
        workflowRepository.save(workflow);

        eventPublisher.publishEvent(WorkflowEvent.failed(this, workflow));
    }

    private void executeCustomAction(Workflow workflow, WorkflowStep step, ActionProposal action) {
        log.info("Custom action requested: {}", action.getReasoning());
        // Custom actions can be handled by event listeners or extensions
        step.setStatus(WorkflowStepStatus.WAITING_APPROVAL);
        stepRepository.save(step);
    }

    @Override
    public Optional<WorkflowStep> getStep(Long workflowId, Integer stepNumber) {
        return workflowRepository.findById(workflowId)
                .map(w -> w.getStep(stepNumber));
    }

    @Override
    public List<WorkflowStep> getSteps(Long workflowId) {
        return workflowRepository.findById(workflowId)
                .map(Workflow::getSteps)
                .orElse(Collections.emptyList());
    }

    // ==================== Workflow Queries ====================

    @Override
    public Optional<Workflow> getWorkflow(Long workflowId) {
        return workflowRepository.findById(workflowId);
    }

    @Override
    public List<Workflow> getWorkflows(String orchestratorInstanceId) {
        return workflowRepository.findByOrchestratorInstanceId(orchestratorInstanceId);
    }

    @Override
    public List<Workflow> getActiveWorkflows(String orchestratorInstanceId) {
        return workflowRepository.findByOrchestratorInstanceIdAndStatusIn(
                orchestratorInstanceId,
                List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PAUSED));
    }

    @Override
    public boolean isWorkflowActive(Long workflowId) {
        return workflowRepository.findById(workflowId)
                .map(Workflow::isActive)
                .orElse(false);
    }

    @Override
    public List<Workflow> getWorkflowHistory(String orchestratorInstanceId, int limit) {
        return workflowRepository.findTopByOrchestratorInstanceIdOrderByCreatedAtDesc(
                orchestratorInstanceId, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // ==================== Context Management ====================

    @Override
    public void updateContext(Long workflowId, Map<String, Object> context) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        Map<String, Object> existing = getContext(workflowId);
        existing.putAll(context);

        try {
            workflow.setContextJson(objectMapper.writeValueAsString(existing));
            workflowRepository.save(workflow);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize workflow context", e);
        }
    }

    @Override
    public Map<String, Object> getContext(Long workflowId) {
        return workflowRepository.findById(workflowId)
                .map(workflow -> {
                    if (workflow.getContextJson() == null) {
                        return new HashMap<String, Object>();
                    }
                    try {
                        return objectMapper.readValue(workflow.getContextJson(),
                                new TypeReference<HashMap<String, Object>>() {});
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize workflow context", e);
                        return new HashMap<String, Object>();
                    }
                })
                .orElse(new HashMap<>());
    }

    /**
     * Handle task completion for workflow steps.
     */
    public void onTaskCompleted(TaskInstance task) {
        if (task.getOrchestratorInstanceId() == null) {
            return;
        }

        // Find active workflow steps for this orchestrator that may have this task
        List<Workflow> workflows = workflowRepository.findByOrchestratorInstanceIdAndStatusIn(
                task.getOrchestratorInstanceId(),
                List.of(WorkflowStatus.IN_PROGRESS, WorkflowStatus.PAUSED));

        for (Workflow workflow : workflows) {
            WorkflowStep currentStep = workflow.getCurrentStep();
            if (currentStep != null && task.getId().equals(currentStep.getTaskInstanceId())) {
                currentStep.setTaskOutput(task.getOutput());

                if (task.isSuccess()) {
                    ActionProposal proposal = ActionProposal.builder()
                            .actionType(ActionType.COMPLETE)
                            .reasoning("Task completed successfully")
                            .build();
                    currentStep.markCompleted(task.getOutput(), proposal);
                } else {
                    currentStep.markFailed(task.getErrorMessage());
                }

                stepRepository.save(currentStep);

                // Advance workflow if auto-advance
                if (workflow.isAutoAdvance() && task.isSuccess()) {
                    workflow.incrementCompletedSteps();
                    workflowRepository.save(workflow);
                    advanceWorkflow(workflow.getId());
                }
            }
        }
    }

    /**
     * Handle LLM session completion for workflow steps.
     */
    public void onLlmSessionCompleted(LlmSession session) {
        if (session.getWorkflowStepId() == null) {
            return;
        }

        WorkflowStep step = stepRepository.findById(session.getWorkflowStepId()).orElse(null);
        if (step == null) {
            return;
        }

        step.setLlmAnalysis(session.getOutput());

        // Parse action proposal
        ActionProposal proposal = llmService.parseActionProposal(session.getOutput());
        if (proposal != null) {
            step.setNextAction(proposal);

            // Check if needs approval
            if (!step.getWorkflow().isAutoAdvance() || proposal.requiresApproval()) {
                step.setStatus(WorkflowStepStatus.WAITING_APPROVAL);
            } else {
                step.setStatus(WorkflowStepStatus.PENDING);
            }
        } else {
            step.markFailed("Could not parse action from LLM response");
        }

        stepRepository.save(step);

        // Auto-execute if approved
        Workflow workflow = step.getWorkflow();
        if (workflow != null && workflow.isAutoAdvance() && step.getStatus() == WorkflowStepStatus.PENDING) {
            executeStepAction(workflow, step);
        }
    }
}
