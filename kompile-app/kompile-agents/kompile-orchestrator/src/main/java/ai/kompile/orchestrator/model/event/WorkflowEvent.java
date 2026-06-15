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
package ai.kompile.orchestrator.model.event;

import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStatus;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;
import lombok.Getter;

/**
 * Event fired for workflow lifecycle changes.
 */
@Getter
public class WorkflowEvent extends OrchestratorEvent {

    private final Long workflowId;
    private final WorkflowStatus status;
    private final Integer currentStep;
    private final Integer totalSteps;
    private final Long stepId;

    public WorkflowEvent(Object source, String orchestratorInstanceId,
                         OrchestratorEventType eventType, Long workflowId,
                         WorkflowStatus status, Integer currentStep,
                         Integer totalSteps, Long stepId) {
        super(source, orchestratorInstanceId, eventType,
                String.format("Workflow %d: %s (step %d/%d)",
                        workflowId, status, currentStep, totalSteps));
        this.workflowId = workflowId;
        this.status = status;
        this.currentStep = currentStep;
        this.totalSteps = totalSteps;
        this.stepId = stepId;
    }

    /**
     * Create from a Workflow.
     */
    public static WorkflowEvent from(Object source, Workflow workflow, OrchestratorEventType eventType) {
        return new WorkflowEvent(source, workflow.getOrchestratorInstanceId(),
                eventType, workflow.getId(), workflow.getStatus(),
                workflow.getCurrentStepNumber(), workflow.getMaxSteps(), null);
    }

    /**
     * Create from a WorkflowStep.
     */
    public static WorkflowEvent fromStep(Object source, Workflow workflow,
                                         WorkflowStep step, OrchestratorEventType eventType) {
        return new WorkflowEvent(source, workflow.getOrchestratorInstanceId(),
                eventType, workflow.getId(), workflow.getStatus(),
                step.getStepNumber(), workflow.getMaxSteps(), step.getId());
    }

    /**
     * Create a workflow started event.
     */
    public static WorkflowEvent started(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_STARTED);
    }

    /**
     * Create a workflow completed event.
     */
    public static WorkflowEvent completed(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_COMPLETED);
    }

    /**
     * Create a workflow failed event.
     */
    public static WorkflowEvent failed(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_FAILED);
    }

    /**
     * Create a step started event.
     */
    public static WorkflowEvent stepStarted(Object source, Workflow workflow, WorkflowStep step) {
        return fromStep(source, workflow, step, OrchestratorEventType.WORKFLOW_STEP_STARTED);
    }

    /**
     * Create a step completed event.
     */
    public static WorkflowEvent stepCompleted(Object source, Workflow workflow, WorkflowStep step) {
        return fromStep(source, workflow, step, OrchestratorEventType.WORKFLOW_STEP_COMPLETED);
    }

    /**
     * Create a waiting approval event.
     */
    public static WorkflowEvent waitingApproval(Object source, Workflow workflow, WorkflowStep step) {
        return fromStep(source, workflow, step, OrchestratorEventType.WORKFLOW_WAITING_APPROVAL);
    }

    /**
     * Create a workflow paused event.
     */
    public static WorkflowEvent paused(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_CANCELLED);
    }

    /**
     * Create a workflow resumed event.
     */
    public static WorkflowEvent resumed(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_STARTED);
    }

    /**
     * Create a workflow cancelled event.
     */
    public static WorkflowEvent cancelled(Object source, Workflow workflow) {
        return from(source, workflow, OrchestratorEventType.WORKFLOW_CANCELLED);
    }
}
