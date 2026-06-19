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
package ai.kompile.orchestrator.model.task;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an actual execution of a task definition.
 */
@Entity
@Table(name = "task_instances")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskInstance {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_definition_id")
    private String taskDefinitionId;

    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    @Builder.Default
    private TaskType taskType = TaskType.SHELL;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "command", columnDefinition = "TEXT")
    private String command;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "working_directory")
    private String workingDirectory;

    @Column(name = "timeout_seconds")
    @Builder.Default
    private Long timeoutSeconds = 300L;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "llm_session_id")
    private Long llmSessionId;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Transient
    @Builder.Default
    private Map<String, String> variables = new HashMap<>();

    @Column(name = "process_id")
    private Long processId;

    @Column(name = "retry_attempt")
    @Builder.Default
    private int retryAttempt = 0;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Transient
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    /**
     * Get the timeout as a Duration.
     */
    public Duration getTimeout() {
        return Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 300L);
    }

    /**
     * Set the timeout from a Duration.
     */
    public void setTimeout(Duration timeout) {
        this.timeoutSeconds = timeout != null ? timeout.toSeconds() : 300L;
    }

    /**
     * Get the execution duration if the task has started.
     */
    public Duration getDuration() {
        if (startTime == null) {
            return Duration.ZERO;
        }
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        return Duration.between(startTime, end);
    }

    /**
     * Check if the task is currently running.
     */
    public boolean isRunning() {
        return status == TaskStatus.RUNNING;
    }

    /**
     * Check if the task has completed (success or failure).
     */
    public boolean isComplete() {
        return status.isTerminal();
    }

    /**
     * Check if the task succeeded.
     */
    public boolean isSuccess() {
        return status == TaskStatus.SUCCESS;
    }

    /**
     * Check if the task failed.
     */
    public boolean isFailed() {
        return status == TaskStatus.FAILED || status == TaskStatus.TIMEOUT;
    }

    /**
     * Mark the task as running.
     */
    public void markRunning() {
        this.status = TaskStatus.RUNNING;
        this.startTime = LocalDateTime.now();
    }

    /**
     * Mark the task as completed successfully.
     */
    public void markSuccess(String output, Integer exitCode) {
        this.status = TaskStatus.SUCCESS;
        this.output = output;
        this.exitCode = exitCode;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the task as failed.
     */
    public void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the task as failed with exit code.
     */
    public void markFailed(String output, Integer exitCode, String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.output = output;
        this.exitCode = exitCode;
        this.errorMessage = errorMessage;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the task as timed out.
     */
    public void markTimeout() {
        this.status = TaskStatus.TIMEOUT;
        this.errorMessage = "Task timed out after " + timeoutSeconds + " seconds";
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the task as cancelled.
     */
    public void markCancelled() {
        this.status = TaskStatus.CANCELLED;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Append output to the existing output.
     */
    public void appendOutput(String line) {
        if (this.output == null) {
            this.output = line;
        } else {
            this.output = this.output + "\n" + line;
        }
    }

    /**
     * Create a new instance from a task definition.
     */
    public static TaskInstance fromDefinition(TaskDefinition definition, Map<String, String> variables) {
        TaskInstance instance = TaskInstance.builder()
                .taskDefinitionId(definition.getTaskId())
                .name(definition.getName())
                .taskType(definition.getTaskType())
                .command(definition.resolveCommand(variables))
                .workingDirectory(definition.getWorkingDirectory())
                .timeoutSeconds(definition.getTimeoutSeconds())
                .variables(variables != null ? new HashMap<>(variables) : new HashMap<>())
                .status(TaskStatus.PENDING)
                .build();

        // Carry over HTTP-specific fields as metadata for HttpTaskExecutor
        if (definition.getTaskType() == TaskType.HTTP) {
            Map<String, String> httpMeta = new HashMap<>();
            if (definition.getHttpUrl() != null) {
                String resolvedUrl = definition.resolveTemplate(definition.getHttpUrl(), variables);
                httpMeta.put("httpUrl", resolvedUrl);
                instance.setCommand(resolvedUrl);
            }
            if (definition.getHttpMethod() != null) {
                httpMeta.put("httpMethod", definition.getHttpMethod());
            }
            if (definition.getHttpHeadersJson() != null) {
                httpMeta.put("httpHeaders", definition.getHttpHeadersJson());
            }
            if (definition.getHttpBodyTemplate() != null) {
                String resolvedBody = definition.resolveTemplate(definition.getHttpBodyTemplate(), variables);
                httpMeta.put("httpBody", resolvedBody);
            }
            try {
                instance.setMetadataJson(OBJECT_MAPPER.writeValueAsString(httpMeta));
            } catch (Exception e) {
                // Best effort — metadata serialization failure shouldn't block task creation
            }
        }

        // Carry over LLM-specific fields as metadata for LlmQueryTaskExecutor
        if (definition.getTaskType() == TaskType.LLM_QUERY) {
            Map<String, String> llmMeta = new HashMap<>();
            if (definition.getPromptTemplate() != null) {
                llmMeta.put("prompt", definition.resolvePrompt(variables));
            }
            if (definition.getSystemPrompt() != null) {
                llmMeta.put("systemPrompt", definition.getSystemPrompt());
            }
            if (definition.getAgentName() != null) {
                llmMeta.put("agentName", definition.getAgentName());
            }
            try {
                instance.setMetadataJson(OBJECT_MAPPER.writeValueAsString(llmMeta));
            } catch (Exception e) {
                // Best effort
            }
        }

        // Carry over CODE-specific fields as metadata for CodeTaskExecutor
        if (definition.getTaskType() == TaskType.CODE) {
            Map<String, String> codeMeta = new HashMap<>();
            if (definition.getExecutorClassName() != null) {
                codeMeta.put("executorClassName", definition.getExecutorClassName());
            }
            // Carry language variable into metadata so it survives DB round-trips
            if (variables != null && variables.containsKey("language")) {
                codeMeta.put("language", variables.get("language"));
            }
            try {
                instance.setMetadataJson(OBJECT_MAPPER.writeValueAsString(codeMeta));
            } catch (Exception e) {
                // Best effort
            }
        }

        return instance;
    }
}
