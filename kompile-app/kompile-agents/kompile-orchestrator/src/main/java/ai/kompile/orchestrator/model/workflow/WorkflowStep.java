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

/**
 * Represents a single step in a workflow.
 */
@Entity
@Table(name = "workflow_steps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Workflow workflow;

    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WorkflowStepStatus status = WorkflowStepStatus.PENDING;

    @Column(name = "task_instance_id")
    private Long taskInstanceId;

    @Column(name = "llm_session_id")
    private Long llmSessionId;

    @Column(name = "llm_analysis", columnDefinition = "TEXT")
    private String llmAnalysis;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "actionType", column = @Column(name = "next_action_type")),
            @AttributeOverride(name = "command", column = @Column(name = "next_action_command", columnDefinition = "TEXT")),
            @AttributeOverride(name = "taskDefinitionId", column = @Column(name = "next_action_task_id")),
            @AttributeOverride(name = "llmPrompt", column = @Column(name = "next_action_llm_prompt", columnDefinition = "TEXT")),
            @AttributeOverride(name = "reasoning", column = @Column(name = "next_action_reasoning", columnDefinition = "TEXT")),
            @AttributeOverride(name = "expectedOutcome", column = @Column(name = "next_action_expected_outcome", columnDefinition = "TEXT")),
            @AttributeOverride(name = "finalStep", column = @Column(name = "next_action_is_final")),
            @AttributeOverride(name = "confidence", column = @Column(name = "next_action_confidence")),
            @AttributeOverride(name = "analysis", column = @Column(name = "next_action_analysis", columnDefinition = "TEXT")),
            @AttributeOverride(name = "customConfigJson", column = @Column(name = "next_action_custom_config", columnDefinition = "TEXT"))
    })
    private ActionProposal nextAction;

    @Column(name = "user_approved")
    @Builder.Default
    private boolean userApproved = false;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "task_output", columnDefinition = "TEXT")
    private String taskOutput;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "input_context_json", columnDefinition = "TEXT")
    private String inputContextJson;

    @Column(name = "output_context_json", columnDefinition = "TEXT")
    private String outputContextJson;

    /**
     * Mark the step as running.
     */
    public void markRunning() {
        this.status = WorkflowStepStatus.RUNNING;
        this.startTime = LocalDateTime.now();
    }

    /**
     * Mark the step as completed.
     */
    public void markCompleted(String llmAnalysis, ActionProposal nextAction) {
        this.status = WorkflowStepStatus.COMPLETED;
        this.llmAnalysis = llmAnalysis;
        this.nextAction = nextAction;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the step as failed.
     */
    public void markFailed(String errorMessage) {
        this.status = WorkflowStepStatus.FAILED;
        this.errorMessage = errorMessage;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the step as skipped.
     */
    public void markSkipped(String reason) {
        this.status = WorkflowStepStatus.SKIPPED;
        this.errorMessage = reason;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Mark the step as rejected.
     */
    public void markRejected(String reason) {
        this.status = WorkflowStepStatus.REJECTED;
        this.rejectionReason = reason;
        this.userApproved = false;
        this.endTime = LocalDateTime.now();
    }

    /**
     * Approve the step for execution.
     */
    public void approve() {
        this.userApproved = true;
        if (this.status == WorkflowStepStatus.WAITING_APPROVAL) {
            this.status = WorkflowStepStatus.PENDING;
        }
    }

    /**
     * Check if this step needs user approval.
     */
    public boolean needsApproval() {
        return status == WorkflowStepStatus.WAITING_APPROVAL ||
               (nextAction != null && nextAction.requiresApproval() && !userApproved);
    }

    /**
     * Check if this step is terminal.
     */
    public boolean isTerminal() {
        return status.isTerminal() || (nextAction != null && nextAction.isTerminal());
    }

    /**
     * Get the workflow ID.
     */
    public Long getWorkflowId() {
        return workflow != null ? workflow.getId() : null;
    }
}
