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
package ai.kompile.orchestrator.model.llm;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an LLM session/invocation.
 */
@Entity
@Table(name = "llm_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "provider_display_name")
    private String providerDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private LlmSessionStatus status = LlmSessionStatus.STARTING;

    @Column(name = "initial_prompt", columnDefinition = "TEXT")
    private String initialPrompt;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "trigger_id")
    private String triggerId;

    @Column(name = "task_instance_id")
    private Long taskInstanceId;

    @Column(name = "workflow_id")
    private Long workflowId;

    @Column(name = "workflow_step_id")
    private Long workflowStepId;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "process_id")
    private Long processId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @Transient
    @Builder.Default
    private Map<String, Object> parameters = new HashMap<>();

    @Column(name = "working_directory")
    private String workingDirectory;

    @Column(name = "files_modified", columnDefinition = "TEXT")
    private String filesModified;

    @Column(name = "actions_proposed", columnDefinition = "TEXT")
    private String actionsProposed;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (startTime == null) {
            startTime = LocalDateTime.now();
        }
    }

    /**
     * Get the session duration.
     */
    public Duration getDuration() {
        if (startTime == null) {
            return Duration.ZERO;
        }
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        return Duration.between(startTime, end);
    }

    /**
     * Check if the session is still active.
     */
    public boolean isActive() {
        return status.isActive();
    }

    /**
     * Check if the session completed successfully.
     */
    public boolean isSuccess() {
        return status.isSuccess();
    }

    /**
     * Mark the session as running.
     */
    public void markRunning() {
        this.status = LlmSessionStatus.RUNNING;
        if (this.startTime == null) {
            this.startTime = LocalDateTime.now();
        }
    }

    /**
     * Mark the session as completed.
     */
    public void markCompleted(String output) {
        this.status = LlmSessionStatus.COMPLETED;
        this.output = output;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the session as failed.
     */
    public void markFailed(String errorMessage) {
        this.status = LlmSessionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the session as timed out.
     */
    public void markTimeout() {
        this.status = LlmSessionStatus.TIMEOUT;
        this.errorMessage = "Session timed out";
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the session as cancelled.
     */
    public void markCancelled() {
        this.status = LlmSessionStatus.CANCELLED;
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
     * Get total tokens used.
     */
    public int getTotalTokens() {
        int input = inputTokens != null ? inputTokens : 0;
        int output = outputTokens != null ? outputTokens : 0;
        return input + output;
    }
}
