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
import ai.kompile.orchestrator.api.StateMachineService;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.model.audit.AuditEntityType;
import ai.kompile.orchestrator.model.audit.AuditEventType;
import ai.kompile.orchestrator.model.event.OrchestratorEventType;
import ai.kompile.orchestrator.model.event.TaskEvent;
import ai.kompile.orchestrator.model.llm.ConversationMessage;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskDefinitionRepository;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles task completion events and executes configured actions.
 * This service integrates with the React Agent architecture for LLM-based actions
 * and supports feedback loops with stored chat history.
 *
 * <p>Actions are executed based on the task's success/failure outcome:
 * <ul>
 *   <li>CONTINUE - No action, task completes normally</li>
 *   <li>RETRY - Retry the task (with configurable backoff)</li>
 *   <li>INVOKE_LLM / INVOKE_LLM_FOR_FIX - Send to React Agent for analysis</li>
 *   <li>TRANSITION_STATE - Transition state machine to target state</li>
 *   <li>EXECUTE_TASK - Execute another task</li>
 *   <li>SEND_TO_AGENT - Send to React Agent with feedback loop</li>
 *   <li>etc.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class TaskCompletionHandler {


    @Autowired
    private final TaskDefinitionRepository taskDefinitionRepository;
    @Autowired
    private final TaskInstanceRepository taskInstanceRepository;
    @Autowired
    private final TaskExecutionService taskExecutionService;
    @Autowired
    private final StateMachineService stateMachineService;
    @Autowired
    private final LlmIntegrationService llmIntegrationService;
    @Autowired
    private final AuditService auditService;

    @Autowired(required = false)
    private ConversationHistoryService conversationHistoryService;

    // Track retry counts per task instance to prevent infinite loops
    private final Map<Long, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    // Maximum retries before giving up
    private static final int MAX_RETRIES = 5;

    // Track active feedback loops (task instance ID -> LLM session ID)
    private final Map<Long, Long> activeFeedbackLoops = new ConcurrentHashMap<>();

    /**
     * Handle task completion events.
     * Listens for TASK_COMPLETED, TASK_FAILED, and TASK_TIMEOUT events.
     */
    @EventListener
    @Async
    @Transactional
    public void handleTaskCompletion(TaskEvent event) {
        // Only handle completion-related events
        if (!isCompletionEvent(event.getEventType())) {
            return;
        }

        Long taskInstanceId = event.getTaskInstanceId();
        String taskDefinitionId = event.getTaskDefinitionId();

        log.debug("Handling task completion event: {} for task instance {} (definition: {})",
                event.getEventType(), taskInstanceId, taskDefinitionId);

        try {
            // Load task instance and definition
            Optional<TaskInstance> instanceOpt = taskInstanceRepository.findById(taskInstanceId);
            if (instanceOpt.isEmpty()) {
                log.warn("Task instance not found: {}", taskInstanceId);
                return;
            }

            TaskInstance instance = instanceOpt.get();
            Optional<TaskDefinition> definitionOpt = taskDefinitionRepository.findById(taskDefinitionId);
            if (definitionOpt.isEmpty()) {
                log.warn("Task definition not found: {}", taskDefinitionId);
                return;
            }

            TaskDefinition definition = definitionOpt.get();

            // Determine success/failure
            boolean success = determineSuccess(event, instance, definition);

            // Get configured action
            TaskAction action = definition.getActionForOutcome(success);
            if (action == null) {
                log.debug("No action configured for {} outcome on task {}",
                        success ? "success" : "failure", taskDefinitionId);
                return;
            }

            log.info("Executing {} action {} for task {} (instance {})",
                    success ? "success" : "failure", action, taskDefinitionId, taskInstanceId);

            // Execute the action
            executeAction(action, definition, instance, success);

            // Audit log
            auditService.logEvent(
                    instance.getOrchestratorInstanceId(),
                    AuditEventType.TASK_LIFECYCLE,
                    AuditEntityType.TASK,
                    taskDefinitionId,
                    "Task action executed: " + action,
                    Map.of(
                            "taskInstanceId", taskInstanceId,
                            "action", action.name(),
                            "success", success
                    )
            );

        } catch (Exception e) {
            log.error("Error handling task completion for instance {}: {}",
                    taskInstanceId, e.getMessage(), e);
        }
    }

    /**
     * Execute the configured action with comprehensive error handling.
     */
    private void executeAction(TaskAction action, TaskDefinition definition,
                               TaskInstance instance, boolean success) {
        try {
            switch (action) {
                case CONTINUE:
                    // No action needed
                    break;

                case RETRY:
                    handleRetry(definition, instance, false);
                    break;

                case RETRY_WITH_BACKOFF:
                    handleRetry(definition, instance, true);
                    break;

                case INVOKE_LLM:
                    handleLlmInvocation(definition, instance, success, false);
                    break;

                case INVOKE_LLM_FOR_FIX:
                    handleLlmInvocation(definition, instance, success, true);
                    break;

                case SEND_TO_AGENT:
                    handleSendToAgent(definition, instance, success);
                    break;

                case TRANSITION_STATE:
                    handleStateTransition(definition, instance, success);
                    break;

                case EXECUTE_TASK:
                    handleExecuteTask(definition, instance, success);
                    break;

                case PARSE_LOG:
                    handleParseLog(definition, instance);
                    break;

                case NOTIFY:
                    handleNotification(definition, instance, success);
                    break;

                case LOG:
                    handleLogAction(definition, instance, success);
                    break;

                case ABORT:
                    handleAbort(definition, instance);
                    break;

                case AWAIT_APPROVAL:
                    handleAwaitApproval(definition, instance);
                    break;

                case ESCALATE:
                    handleEscalation(definition, instance);
                    break;

                case SKIP:
                    // Skip means no further action
                    log.info("Skipping further action for task {}", definition.getTaskId());
                    break;

                case WAIT_FOR_PROCESS:
                    handleWaitForProcess(definition, instance);
                    break;

                case CUSTOM:
                    handleCustomAction(definition, instance, success);
                    break;

                default:
                    log.warn("Unhandled action type: {}", action);
            }
        } catch (Exception e) {
            handleActionException(action, definition, instance, success, e);
        }
    }

    /**
     * Handle exceptions that occur during action execution.
     * Logs the error, records it in conversation history, and creates an audit entry.
     */
    private void handleActionException(TaskAction action, TaskDefinition definition,
                                        TaskInstance instance, boolean success, Exception e) {
        String errorMessage = String.format("Error executing action %s for task %s (instance %d): %s",
                action, definition.getTaskId(), instance.getId(), e.getMessage());

        log.error(errorMessage, e);

        // Record error in conversation history if there's an active feedback loop
        Long sessionId = activeFeedbackLoops.get(instance.getId());
        if (sessionId != null && conversationHistoryService != null) {
            try {
                conversationHistoryService.recordError(sessionId, errorMessage);
            } catch (Exception historyError) {
                log.warn("Failed to record error in conversation history: {}", historyError.getMessage());
            }
        }

        // Audit log the error
        try {
            Map<String, Object> errorDetails = new HashMap<>();
            errorDetails.put("taskInstanceId", instance.getId());
            errorDetails.put("action", action.name());
            errorDetails.put("success", success);
            errorDetails.put("errorType", e.getClass().getSimpleName());
            errorDetails.put("errorMessage", e.getMessage());

            // Include stack trace summary for debugging
            if (e.getStackTrace() != null && e.getStackTrace().length > 0) {
                StringBuilder stackSummary = new StringBuilder();
                int maxFrames = Math.min(5, e.getStackTrace().length);
                for (int i = 0; i < maxFrames; i++) {
                    stackSummary.append(e.getStackTrace()[i].toString()).append("\n");
                }
                errorDetails.put("stackTraceSummary", stackSummary.toString());
            }

            auditService.logEvent(
                    instance.getOrchestratorInstanceId(),
                    AuditEventType.ERROR,
                    AuditEntityType.TASK,
                    definition.getTaskId(),
                    "Action execution failed: " + action,
                    errorDetails
            );
        } catch (Exception auditError) {
            log.warn("Failed to audit log action error: {}", auditError.getMessage());
        }

        // For critical actions, attempt recovery or escalation
        if (action.requiresHumanIntervention() || action == TaskAction.ABORT) {
            try {
                handleActionFailureEscalation(action, definition, instance, e);
            } catch (Exception escalationError) {
                log.error("Failed to escalate action failure: {}", escalationError.getMessage());
            }
        }
    }

    /**
     * Escalate when a critical action fails.
     */
    private void handleActionFailureEscalation(TaskAction action, TaskDefinition definition,
                                                TaskInstance instance, Exception e) {
        log.warn("Escalating failed action {} for task {} to error state",
                action, definition.getTaskId());

        Map<String, Object> escalationDetails = new HashMap<>();
        escalationDetails.put("taskInstanceId", instance.getId());
        escalationDetails.put("failedAction", action.name());
        escalationDetails.put("errorMessage", e.getMessage());
        escalationDetails.put("requiresManualIntervention", true);

        // Try to transition to error state
        try {
            stateMachineService.transitionTo(
                    instance.getOrchestratorInstanceId(),
                    "ERROR",
                    Map.of(
                            "reason", "Action execution failed: " + action,
                            "taskId", definition.getTaskId(),
                            "errorType", e.getClass().getSimpleName()
                    )
            );
        } catch (Exception stateError) {
            log.error("Failed to transition to ERROR state after action failure: {}",
                    stateError.getMessage());
        }

        // Log escalation event
        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.TASK_LIFECYCLE,
                AuditEntityType.TASK,
                definition.getTaskId(),
                "Action failure escalated - requires manual intervention",
                escalationDetails
        );
    }

    /**
     * Handle retry actions with optional exponential backoff.
     */
    private void handleRetry(TaskDefinition definition, TaskInstance instance, boolean withBackoff) {
        Long taskInstanceId = instance.getId();
        AtomicInteger retryCount = retryCounters.computeIfAbsent(taskInstanceId, k -> new AtomicInteger(0));

        int currentRetry = retryCount.incrementAndGet();
        int maxRetries = definition.getRetryCount() > 0 ? definition.getRetryCount() : MAX_RETRIES;

        if (currentRetry > maxRetries) {
            log.warn("Max retries ({}) exceeded for task instance {}", maxRetries, taskInstanceId);
            retryCounters.remove(taskInstanceId);
            return;
        }

        // Calculate delay
        long delaySeconds = definition.getRetryDelaySeconds();
        if (withBackoff) {
            // Exponential backoff: delay * 2^(retry-1)
            delaySeconds = delaySeconds * (1L << (currentRetry - 1));
        }

        log.info("Retrying task {} (attempt {}/{}) after {} seconds",
                definition.getTaskId(), currentRetry, maxRetries, delaySeconds);

        // Schedule retry
        try {
            if (delaySeconds > 0) {
                Thread.sleep(delaySeconds * 1000);
            }

            // Execute retry
            taskExecutionService.executeTask(
                    definition.getTaskId(),
                    instance.getVariables(),
                    instance.getOrchestratorInstanceId()
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Retry interrupted for task {}", definition.getTaskId());
        }
    }

    /**
     * Handle LLM invocation actions.
     * Integrates with the React Agent architecture.
     */
    private void handleLlmInvocation(TaskDefinition definition, TaskInstance instance,
                                     boolean success, boolean forFix) {
        String prompt = definition.resolveActionLlmPrompt(
                success,
                instance.getOutput(),
                instance.getExitCode(),
                instance.getErrorMessage()
        );

        if (prompt == null || prompt.isEmpty()) {
            // Build default prompt
            if (forFix) {
                prompt = buildDefaultFixPrompt(definition, instance);
            } else {
                prompt = buildDefaultAnalysisPrompt(definition, instance, success);
            }
        }

        // Build LLM session request
        LlmSessionRequest request = buildLlmSessionRequest(definition, instance, prompt, forFix);

        log.info("Invoking LLM for task {} (forFix: {})", definition.getTaskId(), forFix);

        // Execute LLM session using the configured agent or default provider
        String providerId = definition.getAgentName() != null ? "chat-agent" : null;
        LlmSession session = providerId != null ?
                llmIntegrationService.startSession(providerId, request) :
                llmIntegrationService.startSession(request);

        // Store session reference for feedback loop tracking
        if (session != null && session.getId() != null) {
            activeFeedbackLoops.put(instance.getId(), session.getId());

            log.info("LLM session {} created for task instance {}",
                    session.getId(), instance.getId());

            // Record conversation history
            if (conversationHistoryService != null) {
                // Record the task output as context
                conversationHistoryService.recordTaskOutput(session.getId(), instance);

                // Record the user prompt
                conversationHistoryService.recordUserMessage(session.getId(), prompt);

                // Record the assistant response if available
                if (session.getOutput() != null) {
                    conversationHistoryService.recordAssistantMessage(
                            session.getId(), session.getOutput(), session.getOutputTokens());
                }
            }

            // Audit log the LLM interaction
            auditService.logEvent(
                    instance.getOrchestratorInstanceId(),
                    AuditEventType.LLM_INTERACTION,
                    AuditEntityType.LLM_SESSION,
                    String.valueOf(session.getId()),
                    "LLM invoked for task action",
                    Map.of(
                            "taskInstanceId", instance.getId(),
                            "taskDefinitionId", definition.getTaskId(),
                            "forFix", forFix,
                            "sessionStatus", session.getStatus().name()
                    )
            );
        }
    }

    /**
     * Handle send to React Agent with feedback loop.
     * This enables iterative analysis where the agent can request more information
     * or take additional actions based on the task output.
     */
    private void handleSendToAgent(TaskDefinition definition, TaskInstance instance, boolean success) {
        String prompt = definition.resolveActionLlmPrompt(
                success,
                instance.getOutput(),
                instance.getExitCode(),
                instance.getErrorMessage()
        );

        if (prompt == null || prompt.isEmpty()) {
            prompt = buildAgentPrompt(definition, instance, success);
        }

        // Build request with agent-specific configuration
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("enableTools", definition.isEnableTools());
        parameters.put("allowedTools", definition.getAllowedToolsList());
        parameters.put("enableRag", definition.isEnableRag());
        parameters.put("ragFolderId", definition.getRagFolderId());
        parameters.put("ragMaxResults", definition.getRagMaxResults());
        parameters.put("ragSimilarityThreshold", definition.getRagSimilarityThreshold());
        parameters.put("skipPermissions", definition.isSkipPermissions());
        parameters.put("agentName", definition.getAgentName());

        // Enable feedback loop mode
        parameters.put("feedbackLoopEnabled", true);
        parameters.put("sourceTaskInstanceId", instance.getId());
        parameters.put("sourceTaskOutput", instance.getOutput());

        LlmSessionRequest request = LlmSessionRequest.builder()
                .orchestratorInstanceId(instance.getOrchestratorInstanceId())
                .prompt(prompt)
                .systemPrompt(definition.getSystemPrompt())
                .workingDirectory(definition.getWorkingDirectory())
                .taskInstanceId(instance.getId())
                .parameters(parameters)
                .timeout(definition.getTimeout())
                .build();

        log.info("Sending task {} output to React Agent with feedback loop enabled",
                definition.getTaskId());

        // Use chat-agent provider for React Agent integration
        LlmSession session = llmIntegrationService.startSession("chat-agent", request);

        if (session != null && session.getId() != null) {
            activeFeedbackLoops.put(instance.getId(), session.getId());

            // Initialize feedback loop tracking
            if (conversationHistoryService != null) {
                conversationHistoryService.startFeedbackLoop(session.getId());

                // Record the task output as context
                conversationHistoryService.recordTaskOutput(session.getId(), instance);

                // Record the initial prompt
                conversationHistoryService.recordUserMessage(session.getId(), prompt);

                // Record assistant response if available
                if (session.getOutput() != null) {
                    conversationHistoryService.recordAssistantMessage(
                            session.getId(), session.getOutput(), session.getOutputTokens());
                }
            }

            auditService.logEvent(
                    instance.getOrchestratorInstanceId(),
                    AuditEventType.LLM_INTERACTION,
                    AuditEntityType.LLM_SESSION,
                    String.valueOf(session.getId()),
                    "Sent to React Agent with feedback loop",
                    Map.of(
                            "taskInstanceId", instance.getId(),
                            "feedbackLoopEnabled", true,
                            "sessionId", session.getId()
                    )
            );
        }
    }

    /**
     * Continue a feedback loop with additional input.
     * This allows the agent to request more information or take follow-up actions.
     *
     * @param sessionId The session to continue
     * @param feedbackMessage Additional input/feedback for the agent
     * @return The updated session, or null if feedback loop ended
     */
    public LlmSession continueFeedbackLoop(Long sessionId, String feedbackMessage) {
        if (conversationHistoryService == null) {
            log.warn("ConversationHistoryService not available, cannot continue feedback loop");
            return null;
        }

        // Check if we can continue the feedback loop
        if (!conversationHistoryService.startFeedbackLoop(sessionId)) {
            log.warn("Max feedback iterations reached for session {}", sessionId);
            conversationHistoryService.endFeedbackLoop(sessionId);
            return null;
        }

        // Record the feedback message
        conversationHistoryService.recordFeedback(sessionId, feedbackMessage);

        // Get conversation context for the next LLM call
        String context = conversationHistoryService.buildConversationContext(sessionId, 10);
        String fullPrompt = context + "\n\nNew feedback:\n" + feedbackMessage;

        // Send follow-up message to the LLM
        LlmSession updatedSession = llmIntegrationService.sendMessage(sessionId, fullPrompt);

        // Record the response
        if (updatedSession != null && updatedSession.getOutput() != null) {
            conversationHistoryService.recordAssistantMessage(
                    sessionId, updatedSession.getOutput(), updatedSession.getOutputTokens());
        }

        return updatedSession;
    }

    /**
     * End a feedback loop for a task instance.
     */
    public void endFeedbackLoopForTask(Long taskInstanceId) {
        Long sessionId = activeFeedbackLoops.remove(taskInstanceId);
        if (sessionId != null && conversationHistoryService != null) {
            conversationHistoryService.endFeedbackLoop(sessionId);
            log.info("Ended feedback loop for task instance {} (session {})", taskInstanceId, sessionId);
        }
    }

    /**
     * Handle state transition actions.
     */
    private void handleStateTransition(TaskDefinition definition, TaskInstance instance, boolean success) {
        String targetStateId = definition.getTargetStateIdForOutcome(success);
        if (targetStateId == null || targetStateId.isEmpty()) {
            log.warn("No target state configured for {} outcome on task {}",
                    success ? "success" : "failure", definition.getTaskId());
            return;
        }

        log.info("Transitioning to state {} after task {}",
                targetStateId, definition.getTaskId());

        stateMachineService.transitionTo(
                instance.getOrchestratorInstanceId(),
                targetStateId,
                Map.of("reason", "Task completion: " + definition.getName(),
                       "taskId", definition.getTaskId(),
                       "success", success)
        );
    }

    /**
     * Handle execute task actions.
     */
    private void handleExecuteTask(TaskDefinition definition, TaskInstance instance, boolean success) {
        String targetTaskId = definition.getTargetTaskIdForOutcome(success);
        if (targetTaskId == null || targetTaskId.isEmpty()) {
            log.warn("No target task configured for {} outcome on task {}",
                    success ? "success" : "failure", definition.getTaskId());
            return;
        }

        log.info("Executing follow-up task {} after task {}",
                targetTaskId, definition.getTaskId());

        // Pass through variables from the original task, plus output context
        Map<String, String> variables = new HashMap<>(instance.getVariables());
        variables.put("previousOutput", instance.getOutput() != null ? instance.getOutput() : "");
        variables.put("previousExitCode", instance.getExitCode() != null ?
                String.valueOf(instance.getExitCode()) : "");
        variables.put("previousSuccess", String.valueOf(success));

        taskExecutionService.executeTask(
                targetTaskId,
                variables,
                instance.getOrchestratorInstanceId()
        );
    }

    /**
     * Handle parse log action using output classifier.
     */
    private void handleParseLog(TaskDefinition definition, TaskInstance instance) {
        Long classifierId = definition.getOutputClassifierId();
        if (classifierId == null) {
            log.warn("No output classifier configured for task {}", definition.getTaskId());
            return;
        }

        log.info("Parsing output for task {} using classifier {}",
                definition.getTaskId(), classifierId);

        // The output classification service will handle the rest
        // This is typically called automatically but can be triggered explicitly
    }

    /**
     * Handle notification actions.
     */
    private void handleNotification(TaskDefinition definition, TaskInstance instance, boolean success) {
        log.info("Sending notification for task {} (success: {})",
                definition.getTaskId(), success);

        // TODO: Implement webhook/notification system
        // For now, just log
        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.TRIGGER_FIRED,
                AuditEntityType.TASK,
                definition.getTaskId(),
                "Notification triggered for task completion",
                Map.of(
                        "taskInstanceId", instance.getId(),
                        "success", success
                )
        );
    }

    /**
     * Handle log action.
     */
    private void handleLogAction(TaskDefinition definition, TaskInstance instance, boolean success) {
        log.info("Task {} completed with {} outcome. Output: {}",
                definition.getTaskId(),
                success ? "SUCCESS" : "FAILURE",
                truncateOutput(instance.getOutput(), 500));

        Map<String, Object> details = new HashMap<>();
        details.put("taskInstanceId", instance.getId());
        details.put("exitCode", instance.getExitCode() != null ? instance.getExitCode() : -1);
        details.put("outputPreview", truncateOutput(instance.getOutput(), 200));

        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.TASK_LIFECYCLE,
                AuditEntityType.TASK,
                definition.getTaskId(),
                String.format("Task logged: %s (%s)",
                        definition.getName(), success ? "success" : "failure"),
                details
        );
    }

    /**
     * Handle abort action.
     */
    private void handleAbort(TaskDefinition definition, TaskInstance instance) {
        log.warn("Aborting workflow due to task {} failure", definition.getTaskId());

        stateMachineService.transitionTo(
                instance.getOrchestratorInstanceId(),
                "ERROR",
                Map.of("reason", "Aborted by task: " + definition.getName(),
                       "taskId", definition.getTaskId())
        );

        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.WORKFLOW_LIFECYCLE,
                AuditEntityType.WORKFLOW,
                instance.getOrchestratorInstanceId(),
                "Workflow aborted",
                Map.of(
                        "abortingTaskId", definition.getTaskId(),
                        "abortingTaskInstanceId", instance.getId()
                )
        );
    }

    /**
     * Handle await approval action.
     */
    private void handleAwaitApproval(TaskDefinition definition, TaskInstance instance) {
        log.info("Task {} requires approval to continue", definition.getTaskId());

        // Transition to WAITING state
        stateMachineService.transitionTo(
                instance.getOrchestratorInstanceId(),
                "WAITING",
                Map.of("reason", "Awaiting approval for: " + definition.getName(),
                       "taskId", definition.getTaskId())
        );

        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.TASK_LIFECYCLE,
                AuditEntityType.TASK,
                definition.getTaskId(),
                "Awaiting manual approval",
                Map.of("taskInstanceId", instance.getId())
        );
    }

    /**
     * Handle escalation action.
     */
    private void handleEscalation(TaskDefinition definition, TaskInstance instance) {
        log.warn("Escalating task {} to human operator", definition.getTaskId());

        Map<String, Object> details = new HashMap<>();
        details.put("taskInstanceId", instance.getId());
        details.put("output", truncateOutput(instance.getOutput(), 1000));
        if (instance.getErrorMessage() != null) {
            details.put("errorMessage", instance.getErrorMessage());
        }

        auditService.logEvent(
                instance.getOrchestratorInstanceId(),
                AuditEventType.TASK_LIFECYCLE,
                AuditEntityType.TASK,
                definition.getTaskId(),
                "Task escalated to human operator",
                details
        );

        // TODO: Integrate with notification/ticketing system
    }

    /**
     * Handle wait for process action.
     */
    private void handleWaitForProcess(TaskDefinition definition, TaskInstance instance) {
        log.info("Waiting for external process completion for task {}", definition.getTaskId());

        // This would typically poll for process completion or wait for callback
        // For now, transition to WAITING state
        stateMachineService.transitionTo(
                instance.getOrchestratorInstanceId(),
                "WAITING",
                Map.of("reason", "Waiting for external process: " + definition.getName(),
                       "taskId", definition.getTaskId())
        );
    }

    /**
     * Handle custom action.
     */
    private void handleCustomAction(TaskDefinition definition, TaskInstance instance, boolean success) {
        log.info("Executing custom action for task {} (check task metadata)",
                definition.getTaskId());

        // Custom actions would be defined in task metadata
        // This is an extension point for custom behavior
    }

    // ==================== Helper Methods ====================

    private boolean isCompletionEvent(OrchestratorEventType eventType) {
        return eventType == OrchestratorEventType.TASK_COMPLETED ||
               eventType == OrchestratorEventType.TASK_FAILED ||
               eventType == OrchestratorEventType.TASK_TIMEOUT;
    }

    private boolean determineSuccess(TaskEvent event, TaskInstance instance, TaskDefinition definition) {
        // Check event type first
        if (event.getEventType() == OrchestratorEventType.TASK_FAILED ||
            event.getEventType() == OrchestratorEventType.TASK_TIMEOUT) {
            return false;
        }

        // Use definition's success/failure pattern matching
        return definition.isTaskSuccess(instance.getOutput(), instance.getExitCode());
    }

    private LlmSessionRequest buildLlmSessionRequest(TaskDefinition definition, TaskInstance instance,
                                                     String prompt, boolean forFix) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("agentName", definition.getAgentName());
        parameters.put("enableRag", definition.isEnableRag());
        parameters.put("ragFolderId", definition.getRagFolderId());
        parameters.put("ragMaxResults", definition.getRagMaxResults());
        parameters.put("enableTools", definition.isEnableTools());
        parameters.put("allowedTools", definition.getAllowedToolsList());
        parameters.put("skipPermissions", definition.isSkipPermissions());
        parameters.put("forFix", forFix);

        return LlmSessionRequest.builder()
                .orchestratorInstanceId(instance.getOrchestratorInstanceId())
                .prompt(prompt)
                .systemPrompt(definition.getSystemPrompt())
                .workingDirectory(definition.getWorkingDirectory())
                .taskInstanceId(instance.getId())
                .parameters(parameters)
                .timeout(definition.getTimeout())
                .build();
    }

    private String buildDefaultFixPrompt(TaskDefinition definition, TaskInstance instance) {
        return String.format("""
                The following task failed and needs to be fixed:

                Task: %s
                Task ID: %s
                Exit Code: %s
                Error Message: %s

                Output:
                ```
                %s
                ```

                Please analyze the error and suggest a fix. If you can determine the root cause,
                provide specific commands or code changes that would resolve the issue.
                """,
                definition.getName(),
                definition.getTaskId(),
                instance.getExitCode(),
                instance.getErrorMessage() != null ? instance.getErrorMessage() : "N/A",
                truncateOutput(instance.getOutput(), 5000)
        );
    }

    private String buildDefaultAnalysisPrompt(TaskDefinition definition, TaskInstance instance, boolean success) {
        return String.format("""
                Please analyze the output of the following task:

                Task: %s
                Task ID: %s
                Status: %s
                Exit Code: %s

                Output:
                ```
                %s
                ```

                Provide insights about the task execution and any recommendations.
                """,
                definition.getName(),
                definition.getTaskId(),
                success ? "SUCCESS" : "FAILURE",
                instance.getExitCode(),
                truncateOutput(instance.getOutput(), 5000)
        );
    }

    private String buildAgentPrompt(TaskDefinition definition, TaskInstance instance, boolean success) {
        return String.format("""
                You are assisting with an automated workflow. A task has completed and requires your analysis.

                Task: %s
                Status: %s
                Exit Code: %s

                Output:
                ```
                %s
                ```

                %s

                You can use available tools to investigate further or take corrective actions.
                Please analyze this output and take appropriate action based on the result.
                """,
                definition.getName(),
                success ? "SUCCESS" : "FAILURE",
                instance.getExitCode(),
                truncateOutput(instance.getOutput(), 5000),
                success ? "Determine if any follow-up actions are needed." :
                          "Determine the cause of the failure and suggest or implement a fix."
        );
    }

    private String truncateOutput(String output, int maxLength) {
        if (output == null) {
            return "";
        }
        if (output.length() <= maxLength) {
            return output;
        }
        return output.substring(0, maxLength) + "... [truncated]";
    }

    /**
     * Get the LLM session ID for an active feedback loop.
     */
    public Long getFeedbackLoopSession(Long taskInstanceId) {
        return activeFeedbackLoops.get(taskInstanceId);
    }

    /**
     * Clear the feedback loop tracking for a task instance.
     */
    public void clearFeedbackLoop(Long taskInstanceId) {
        activeFeedbackLoops.remove(taskInstanceId);
        retryCounters.remove(taskInstanceId);
    }
}
