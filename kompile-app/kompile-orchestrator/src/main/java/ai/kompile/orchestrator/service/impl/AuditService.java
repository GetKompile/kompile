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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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

    // ==================== Advanced Query Methods ====================

    /**
     * Search audit logs with multiple filters and text search.
     */
    public Page<AuditLogEntry> searchAuditLogs(String instanceId, AuditSearchCriteria criteria, Pageable pageable) {
        if (criteria.getSearch() != null && !criteria.getSearch().isEmpty()) {
            return auditRepository.findWithFiltersAndSearch(
                    instanceId,
                    criteria.getEventType(),
                    criteria.getEntityType(),
                    criteria.getFromTime(),
                    criteria.getToTime(),
                    criteria.isErrorsOnly(),
                    criteria.getSearch(),
                    pageable);
        } else {
            return auditRepository.findWithFilters(
                    instanceId,
                    criteria.getEventType(),
                    criteria.getEntityType(),
                    criteria.getFromTime(),
                    criteria.getToTime(),
                    criteria.isErrorsOnly(),
                    pageable);
        }
    }

    /**
     * Get audit log statistics for an instance.
     */
    public AuditStats getStats(String instanceId) {
        long totalEvents = auditRepository.countByOrchestratorInstanceId(instanceId);
        long errorCount = auditRepository.countErrors(instanceId);
        Double avgDuration = auditRepository.averageDuration(instanceId);

        // Get counts by event type
        List<Object[]> eventTypeCounts = auditRepository.countByEventType(instanceId);
        Map<String, Long> eventsByType = new LinkedHashMap<>();
        for (Object[] row : eventTypeCounts) {
            if (row[0] != null) {
                eventsByType.put(row[0].toString(), (Long) row[1]);
            }
        }

        // Get counts by entity type
        List<Object[]> entityTypeCounts = auditRepository.countByEntityType(instanceId);
        Map<String, Long> eventsByEntityType = new LinkedHashMap<>();
        for (Object[] row : entityTypeCounts) {
            if (row[0] != null) {
                eventsByEntityType.put(row[0].toString(), (Long) row[1]);
            }
        }

        // Get events by hour for the last 24 hours
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Object[]> hourCounts = auditRepository.countByHour(instanceId, since);
        List<AuditStats.HourlyCount> eventsByHour = new ArrayList<>();
        for (Object[] row : hourCounts) {
            int hour = ((Number) row[0]).intValue();
            long count = (Long) row[1];
            eventsByHour.add(new AuditStats.HourlyCount(hour, count));
        }

        return AuditStats.builder()
                .totalEvents(totalEvents)
                .errorCount(errorCount)
                .avgDurationMs(avgDuration != null ? avgDuration : 0.0)
                .eventsByType(eventsByType)
                .eventsByEntityType(eventsByEntityType)
                .eventsByHour(eventsByHour)
                .build();
    }

    /**
     * Export audit logs to JSON format.
     */
    public String exportToJson(String instanceId, AuditSearchCriteria criteria) {
        Pageable pageable = PageRequest.of(0, 10000); // Limit export to 10000 records
        Page<AuditLogEntry> entries = searchAuditLogs(instanceId, criteria, pageable);

        ObjectMapper exportMapper = new ObjectMapper();
        exportMapper.registerModule(new JavaTimeModule());

        try {
            return exportMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries.getContent());
        } catch (JsonProcessingException e) {
            log.error("Failed to export audit logs to JSON", e);
            return "[]";
        }
    }

    /**
     * Export audit logs to CSV format.
     */
    public String exportToCsv(String instanceId, AuditSearchCriteria criteria) {
        Pageable pageable = PageRequest.of(0, 10000); // Limit export to 10000 records
        Page<AuditLogEntry> entries = searchAuditLogs(instanceId, criteria, pageable);

        StringBuilder csv = new StringBuilder();
        // CSV Header
        csv.append("ID,Timestamp,Event Type,Entity Type,Entity ID,Action,Message,Error,Error Message,Duration (ms),Actor ID,Trigger ID,Hook ID\n");

        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

        for (AuditLogEntry entry : entries.getContent()) {
            csv.append(escapeCsv(entry.getId() != null ? entry.getId().toString() : "")).append(",");
            csv.append(escapeCsv(entry.getTimestamp() != null ? entry.getTimestamp().format(formatter) : "")).append(",");
            csv.append(escapeCsv(entry.getEventType() != null ? entry.getEventType().name() : "")).append(",");
            csv.append(escapeCsv(entry.getEntityType() != null ? entry.getEntityType().name() : "")).append(",");
            csv.append(escapeCsv(entry.getEntityId())).append(",");
            csv.append(escapeCsv(entry.getAction())).append(",");
            csv.append(escapeCsv(entry.getMessage())).append(",");
            csv.append(entry.isError()).append(",");
            csv.append(escapeCsv(entry.getErrorMessage())).append(",");
            csv.append(entry.getDurationMs() != null ? entry.getDurationMs() : "").append(",");
            csv.append(escapeCsv(entry.getActorId())).append(",");
            csv.append(escapeCsv(entry.getTriggerId())).append(",");
            csv.append(escapeCsv(entry.getHookId())).append("\n");
        }

        return csv.toString();
    }

    /**
     * Get audit logs by actor.
     */
    public List<AuditLogEntry> getByActor(String instanceId, String actorId) {
        return auditRepository.findByOrchestratorInstanceIdAndActorIdOrderByTimestampDesc(instanceId, actorId);
    }

    // ==================== Generic Event Logging ====================

    /**
     * Log a generic event with custom parameters.
     * This is useful for extensibility when specific methods aren't available.
     */
    @Async
    public CompletableFuture<AuditLogEntry> logEvent(String instanceId, AuditEventType eventType,
                                                      AuditEntityType entityType, String entityId,
                                                      String message, Map<String, Object> details) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(eventType.name())
                .message(message)
                .detailsJson(toJson(details))
                .build();
        return CompletableFuture.completedFuture(auditRepository.save(entry));
    }

    /**
     * Synchronous version for use in critical paths.
     */
    public AuditLogEntry logEventSync(String instanceId, AuditEventType eventType,
                                      AuditEntityType entityType, String entityId,
                                      String message, Map<String, Object> details) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(eventType.name())
                .message(message)
                .detailsJson(toJson(details))
                .build();
        return auditRepository.save(entry);
    }

    // ==================== Utility ====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // Escape quotes and wrap in quotes if contains comma, quote, or newline
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ==================== DTOs ====================

    /**
     * Search criteria for audit log queries.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AuditSearchCriteria {
        private AuditEventType eventType;
        private AuditEntityType entityType;
        private LocalDateTime fromTime;
        private LocalDateTime toTime;
        private String search;
        private String actorId;
        private boolean errorsOnly;
    }

    /**
     * Audit log statistics.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AuditStats {
        private long totalEvents;
        private long errorCount;
        private double avgDurationMs;
        private Map<String, Long> eventsByType;
        private Map<String, Long> eventsByEntityType;
        private List<HourlyCount> eventsByHour;

        @lombok.Data
        @lombok.AllArgsConstructor
        @lombok.NoArgsConstructor
        public static class HourlyCount {
            private int hour;
            private long count;
        }
    }
}
