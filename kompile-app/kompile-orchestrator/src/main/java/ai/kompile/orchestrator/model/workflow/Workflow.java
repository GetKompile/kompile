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
package ai.kompile.orchestrator.model.workflow;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a multi-step workflow with LLM-driven step proposals.
 */
@Entity
@Table(name = "workflows")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "orchestrator_instance_id")
    private String orchestratorInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WorkflowStatus status = WorkflowStatus.IN_PROGRESS;

    @Column(name = "initial_prompt", columnDefinition = "TEXT")
    private String initialPrompt;

    @Column(name = "current_step_number")
    @Builder.Default
    private Integer currentStepNumber = 0;

    @Column(name = "completed_steps")
    @Builder.Default
    private Integer completedSteps = 0;

    @Column(name = "max_steps")
    @Builder.Default
    private Integer maxSteps = 20;

    @Column(name = "auto_advance")
    @Builder.Default
    private boolean autoAdvance = false;

    @Column(name = "working_directory")
    private String workingDirectory;

    @Column(name = "llm_provider_id")
    private String llmProviderId;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @OneToMany(mappedBy = "workflow", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("stepNumber ASC")
    @Builder.Default
    private List<WorkflowStep> steps = new ArrayList<>();

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

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
     * Get the current step.
     */
    public WorkflowStep getCurrentStep() {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        return steps.stream()
                .filter(s -> s.getStepNumber().equals(currentStepNumber))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get a step by number.
     */
    public WorkflowStep getStep(int stepNumber) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(s -> s.getStepNumber() == stepNumber)
                .findFirst()
                .orElse(null);
    }

    /**
     * Add a new step to the workflow.
     */
    public WorkflowStep addStep(String description) {
        int nextStepNumber = steps != null ? steps.size() : 0;
        WorkflowStep step = WorkflowStep.builder()
                .workflow(this)
                .stepNumber(nextStepNumber)
                .description(description)
                .status(WorkflowStepStatus.PENDING)
                .build();

        if (steps == null) {
            steps = new ArrayList<>();
        }
        steps.add(step);
        return step;
    }

    /**
     * Check if the workflow has reached the maximum number of steps.
     */
    public boolean hasReachedMaxSteps() {
        return completedSteps != null && maxSteps != null && completedSteps >= maxSteps;
    }

    /**
     * Check if the workflow is still active.
     */
    public boolean isActive() {
        return status != null && status.isActive();
    }

    /**
     * Mark the workflow as completed.
     */
    public void markCompleted(String summary) {
        this.status = WorkflowStatus.COMPLETED;
        this.summary = summary;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the workflow as failed.
     */
    public void markFailed(String errorMessage) {
        this.status = WorkflowStatus.FAILED;
        this.errorMessage = errorMessage;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the workflow as cancelled.
     */
    public void markCancelled() {
        this.status = WorkflowStatus.CANCELLED;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Increment the completed steps counter.
     */
    public void incrementCompletedSteps() {
        if (completedSteps == null) {
            completedSteps = 0;
        }
        completedSteps++;
    }

    /**
     * Advance to the next step.
     */
    public void advanceStep() {
        if (currentStepNumber == null) {
            currentStepNumber = 0;
        }
        currentStepNumber++;
    }
}
