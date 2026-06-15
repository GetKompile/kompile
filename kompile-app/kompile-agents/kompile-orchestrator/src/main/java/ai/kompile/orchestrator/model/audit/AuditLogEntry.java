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
package ai.kompile.orchestrator.model.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Represents an audit log entry for tracking orchestrator events.
 */
@Entity
@Table(name = "orchestrator_audit_log", indexes = {
        @Index(name = "idx_audit_instance", columnList = "orchestrator_instance_id"),
        @Index(name = "idx_audit_event_type", columnList = "event_type"),
        @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private AuditEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type")
    private AuditEntityType entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "previous_value", columnDefinition = "TEXT")
    private String previousValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "source")
    private String source;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_type")
    private String actorType;

    @Column(name = "trigger_id")
    private String triggerId;

    @Column(name = "hook_id")
    private String hookId;

    @Column(name = "error")
    @Builder.Default
    private boolean error = false;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "timestamp", nullable = false)
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }

    // Factory methods for common audit events

    public static AuditLogEntry stateChange(String instanceId, String fromState, String toState) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.STATE_CHANGE)
                .entityType(AuditEntityType.STATE)
                .action("STATE_TRANSITION")
                .previousValue(fromState)
                .newValue(toState)
                .message(String.format("State changed from %s to %s", fromState, toState))
                .build();
    }

    public static AuditLogEntry taskStarted(String instanceId, Long taskId, String taskName) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.TASK_LIFECYCLE)
                .entityType(AuditEntityType.TASK)
                .entityId(taskId != null ? taskId.toString() : null)
                .action("TASK_STARTED")
                .message(String.format("Task started: %s", taskName))
                .build();
    }

    public static AuditLogEntry taskCompleted(String instanceId, Long taskId, String taskName, boolean success) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.TASK_LIFECYCLE)
                .entityType(AuditEntityType.TASK)
                .entityId(taskId != null ? taskId.toString() : null)
                .action(success ? "TASK_COMPLETED" : "TASK_FAILED")
                .message(String.format("Task %s: %s", success ? "completed" : "failed", taskName))
                .error(!success)
                .build();
    }

    public static AuditLogEntry llmSessionStarted(String instanceId, Long sessionId, String providerId) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.LLM_INTERACTION)
                .entityType(AuditEntityType.LLM_SESSION)
                .entityId(sessionId != null ? sessionId.toString() : null)
                .action("LLM_SESSION_STARTED")
                .message(String.format("LLM session started with provider: %s", providerId))
                .build();
    }

    public static AuditLogEntry llmSessionCompleted(String instanceId, Long sessionId, boolean success, Integer tokens) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.LLM_INTERACTION)
                .entityType(AuditEntityType.LLM_SESSION)
                .entityId(sessionId != null ? sessionId.toString() : null)
                .action(success ? "LLM_SESSION_COMPLETED" : "LLM_SESSION_FAILED")
                .message(String.format("LLM session %s, tokens: %d", success ? "completed" : "failed", tokens))
                .error(!success)
                .build();
    }

    public static AuditLogEntry triggerFired(String instanceId, String triggerId, String triggerName) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.TRIGGER_FIRED)
                .entityType(AuditEntityType.TRIGGER)
                .entityId(triggerId)
                .triggerId(triggerId)
                .action("TRIGGER_FIRED")
                .message(String.format("Trigger fired: %s", triggerName))
                .build();
    }

    public static AuditLogEntry hookExecuted(String instanceId, String hookId, String hookAction, long durationMs) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.HOOK_EXECUTED)
                .entityType(AuditEntityType.HOOK)
                .entityId(hookId)
                .hookId(hookId)
                .action(hookAction)
                .durationMs(durationMs)
                .message(String.format("Hook executed: %s (%dms)", hookAction, durationMs))
                .build();
    }

    public static AuditLogEntry workflowEvent(String instanceId, Long workflowId, String action, String message) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.WORKFLOW_LIFECYCLE)
                .entityType(AuditEntityType.WORKFLOW)
                .entityId(workflowId != null ? workflowId.toString() : null)
                .action(action)
                .message(message)
                .build();
    }

    public static AuditLogEntry error(String instanceId, String action, String errorMessage, Throwable exception) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.ERROR)
                .action(action)
                .error(true)
                .errorMessage(errorMessage)
                .message(String.format("Error: %s", errorMessage))
                .detailsJson(exception != null ? exception.getClass().getName() : null)
                .build();
    }

    public static AuditLogEntry configuration(String instanceId, String action, String configKey,
                                              String previousValue, String newValue) {
        return AuditLogEntry.builder()
                .orchestratorInstanceId(instanceId)
                .eventType(AuditEventType.CONFIGURATION_CHANGE)
                .entityType(AuditEntityType.CONFIGURATION)
                .entityId(configKey)
                .action(action)
                .previousValue(previousValue)
                .newValue(newValue)
                .message(String.format("Configuration changed: %s", configKey))
                .build();
    }
}
