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

import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.audit.*;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import ai.kompile.orchestrator.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing audit logs.
 * Provides async logging to avoid impacting main processing.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Orchestrator Lifecycle ====================

    @Async
    public CompletableFuture<AuditLogEntry> logOrchestratorStarted(OrchestratorInstance instance) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(instance.getInstanceId())
                .eventType(AuditEventType.ORCHESTRATOR_LIFECYCLE)
                .entityType(AuditEntityType.ORCHESTRATOR)
                .entityId(instance.getInstanceId())
                .action("ORCHESTRATOR_STARTED")
                .message("Orchestrator instance started: " + instance.getName())
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logOrchestratorStopped(OrchestratorInstance instance) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(instance.getInstanceId())
                .eventType(AuditEventType.ORCHESTRATOR_LIFECYCLE)
                .entityType(AuditEntityType.ORCHESTRATOR)
                .entityId(instance.getInstanceId())
                .action("ORCHESTRATOR_STOPPED")
                .message("Orchestrator instance stopped: " + instance.getName())
                .newValue(instance.getStatus().name())
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logOrchestratorRecovery(OrchestratorInstance instance, boolean success) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(instance.getInstanceId())
                .eventType(AuditEventType.RECOVERY)
                .entityType(AuditEntityType.ORCHESTRATOR)
                .entityId(instance.getInstanceId())
                .action(success ? "RECOVERY_SUCCEEDED" : "RECOVERY_FAILED")
                .message(success ? "Recovery succeeded" : "Recovery failed")
                .error(!success)
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== State Changes ====================

    @Async
    public CompletableFuture<AuditLogEntry> logStateTransition(OrchestratorInstance instance,
                                                               StateDefinition fromState,
                                                               StateDefinition toState,
                                                               Map<String, Object> context) {
        AuditLogEntry entry = AuditLogEntry.stateChange(
                instance.getInstanceId(),
                fromState != null ? fromState.getStateId() : null,
                toState.getStateId());

        try {
            entry.setDetailsJson(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize context for audit", e);
        }

        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Task Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logTaskStarted(TaskInstance task) {
        AuditLogEntry entry = AuditLogEntry.taskStarted(
                task.getOrchestratorInstanceId(),
                task.getId(),
                task.getName());
        entry.setDetailsJson(toJson(Map.of(
                "taskType", task.getTaskType().name(),
                "command", task.getCommand() != null ? task.getCommand() : ""
        )));
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logTaskCompleted(TaskInstance task) {
        AuditLogEntry entry = AuditLogEntry.taskCompleted(
                task.getOrchestratorInstanceId(),
                task.getId(),
                task.getName(),
                task.isSuccess());

        if (!task.isSuccess()) {
            entry.setErrorMessage(task.getErrorMessage());
        }
        if (task.getDuration() != null) {
            entry.setDurationMs(task.getDuration().toMillis());
        }

        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logTaskCancelled(TaskInstance task) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(task.getOrchestratorInstanceId())
                .eventType(AuditEventType.TASK_LIFECYCLE)
                .entityType(AuditEntityType.TASK)
                .entityId(task.getId() != null ? task.getId().toString() : null)
                .action("TASK_CANCELLED")
                .message("Task cancelled: " + task.getName())
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== LLM Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logLlmSessionStarted(LlmSession session) {
        AuditLogEntry entry = AuditLogEntry.llmSessionStarted(
                session.getOrchestratorInstanceId(),
                session.getId(),
                session.getProviderId());
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logLlmSessionCompleted(LlmSession session) {
        AuditLogEntry entry = AuditLogEntry.llmSessionCompleted(
                session.getOrchestratorInstanceId(),
                session.getId(),
                session.isSuccess(),
                session.getTotalTokens());

        if (!session.isSuccess()) {
            entry.setErrorMessage(session.getErrorMessage());
        }
        if (session.getDuration() != null) {
            entry.setDurationMs(session.getDuration().toMillis());
        }

        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Trigger Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logTriggerFired(String instanceId, LlmTrigger trigger) {
        AuditLogEntry entry = AuditLogEntry.triggerFired(
                instanceId,
                trigger.getTriggerId(),
                trigger.getName());
        entry.setDetailsJson(toJson(Map.of(
                "triggerType", trigger.getTriggerType().name(),
                "providerId", trigger.getLlmProviderId() != null ? trigger.getLlmProviderId() : ""
        )));
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Hook Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logHookExecuted(String instanceId, String hookId,
                                                            String hookAction, long durationMs,
                                                            boolean error, String errorMessage) {
        AuditLogEntry entry = AuditLogEntry.hookExecuted(instanceId, hookId, hookAction, durationMs);
        entry.setError(error);
        if (error) {
            entry.setErrorMessage(errorMessage);
        }
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Workflow Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logWorkflowStarted(Workflow workflow) {
        AuditLogEntry entry = AuditLogEntry.workflowEvent(
                workflow.getOrchestratorInstanceId(),
                workflow.getId(),
                "WORKFLOW_STARTED",
                "Workflow started: " + workflow.getName());
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logWorkflowCompleted(Workflow workflow) {
        AuditLogEntry entry = AuditLogEntry.workflowEvent(
                workflow.getOrchestratorInstanceId(),
                workflow.getId(),
                workflow.getStatus().name(),
                "Workflow " + workflow.getStatus().name().toLowerCase() + ": " + workflow.getName());
        entry.setError(workflow.getErrorMessage() != null);
        entry.setErrorMessage(workflow.getErrorMessage());
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    @Async
    public CompletableFuture<AuditLogEntry> logWorkflowStep(Workflow workflow, WorkflowStep step, String action) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(workflow.getOrchestratorInstanceId())
                .eventType(AuditEventType.WORKFLOW_LIFECYCLE)
                .entityType(AuditEntityType.WORKFLOW_STEP)
                .entityId(step.getId() != null ? step.getId().toString() : null)
                .action(action)
                .message(String.format("Workflow step %d: %s", step.getStepNumber(), action))
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Error Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logError(String instanceId, String action,
                                                      String errorMessage, Throwable exception) {
        AuditLogEntry entry = AuditLogEntry.error(instanceId, action, errorMessage, exception);
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Configuration Events ====================

    @Async
    public CompletableFuture<AuditLogEntry> logConfigurationChange(String instanceId, String configKey,
                                                                    String previousValue, String newValue) {
        AuditLogEntry entry = AuditLogEntry.configuration(
                instanceId, "CONFIG_CHANGED", configKey, previousValue, newValue);
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    // ==================== Query Methods ====================

    public List<AuditLogEntry> getAuditLog(String orchestratorInstanceId) {
        return auditRepository.findByOrchestratorInstanceIdOrderByTimestampDesc(orchestratorInstanceId);
    }

    public Page<AuditLogEntry> getAuditLog(String orchestratorInstanceId, Pageable pageable) {
        return auditRepository.findByOrchestratorInstanceIdOrderByTimestampDesc(orchestratorInstanceId, pageable);
    }

    public List<AuditLogEntry> getAuditLogByTimeRange(String orchestratorInstanceId,
                                                       LocalDateTime start, LocalDateTime end) {
        return auditRepository.findByOrchestratorInstanceIdAndTimestampBetweenOrderByTimestampDesc(
                orchestratorInstanceId, start, end);
    }

    public List<AuditLogEntry> getErrors(String orchestratorInstanceId) {
        return auditRepository.findByOrchestratorInstanceIdAndErrorTrueOrderByTimestampDesc(orchestratorInstanceId);
    }

    public List<AuditLogEntry> getByEventType(AuditEventType eventType) {
        return auditRepository.findByEventTypeOrderByTimestampDesc(eventType);
    }

    public List<AuditLogEntry> getByEntity(AuditEntityType entityType, String entityId) {
        return auditRepository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
    }

    public Page<AuditLogEntry> getRecentEntries(Pageable pageable) {
        return auditRepository.findRecentEntries(pageable);
    }

    // ==================== Maintenance ====================

    /**
     * Delete audit entries older than the given cutoff.
     */
    public void cleanupOldEntries(LocalDateTime cutoff) {
        auditRepository.deleteByTimestampBefore(cutoff);
        log.info("Deleted audit entries before {}", cutoff);
    }

    // ==================== Utility ====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
