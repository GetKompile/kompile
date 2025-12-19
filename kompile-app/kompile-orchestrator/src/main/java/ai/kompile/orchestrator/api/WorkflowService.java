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
package ai.kompile.orchestrator.api;

import ai.kompile.orchestrator.model.workflow.Workflow;
import ai.kompile.orchestrator.model.workflow.WorkflowStep;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing multi-step workflows with LLM-driven proposals.
 */
public interface WorkflowService {

    // ==================== Workflow Lifecycle ====================

    /**
     * Start a new workflow.
     *
     * @param name                   The workflow name
     * @param description            The workflow description
     * @param initialPrompt          The initial prompt/goal
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The created workflow
     */
    Workflow startWorkflow(String name, String description, String initialPrompt, String orchestratorInstanceId);

    /**
     * Start a new workflow with options.
     *
     * @param name                   The workflow name
     * @param description            The workflow description
     * @param initialPrompt          The initial prompt/goal
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param autoAdvance            Whether to auto-advance steps
     * @param maxSteps               Maximum number of steps
     * @param llmProviderId          The LLM provider to use
     * @return The created workflow
     */
    Workflow startWorkflow(String name, String description, String initialPrompt,
                           String orchestratorInstanceId, boolean autoAdvance,
                           Integer maxSteps, String llmProviderId);

    /**
     * Advance workflow to the next step.
     *
     * @param workflowId The workflow ID
     */
    void advanceWorkflow(Long workflowId);

    /**
     * Pause a workflow.
     *
     * @param workflowId The workflow ID
     */
    void pauseWorkflow(Long workflowId);

    /**
     * Resume a paused workflow.
     *
     * @param workflowId The workflow ID
     */
    void resumeWorkflow(Long workflowId);

    /**
     * Cancel a workflow.
     *
     * @param workflowId The workflow ID
     */
    void cancelWorkflow(Long workflowId);

    // ==================== Step Management ====================

    /**
     * Approve a pending step.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     */
    void approveStep(Long workflowId, Integer stepNumber);

    /**
     * Reject a pending step with feedback.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     * @param feedback   Feedback for the rejection
     */
    void rejectStep(Long workflowId, Integer stepNumber, String feedback);

    /**
     * Skip a step.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     * @param reason     Reason for skipping
     */
    void skipStep(Long workflowId, Integer stepNumber, String reason);

    /**
     * Get a workflow step.
     *
     * @param workflowId The workflow ID
     * @param stepNumber The step number
     * @return The workflow step, or empty if not found
     */
    Optional<WorkflowStep> getStep(Long workflowId, Integer stepNumber);

    /**
     * Get all steps for a workflow.
     *
     * @param workflowId The workflow ID
     * @return List of workflow steps
     */
    List<WorkflowStep> getSteps(Long workflowId);

    // ==================== Workflow Queries ====================

    /**
     * Get a workflow by ID.
     *
     * @param workflowId The workflow ID
     * @return The workflow, or empty if not found
     */
    Optional<Workflow> getWorkflow(Long workflowId);

    /**
     * Get all workflows for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of workflows
     */
    List<Workflow> getWorkflows(String orchestratorInstanceId);

    /**
     * Get active workflows for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of active workflows
     */
    List<Workflow> getActiveWorkflows(String orchestratorInstanceId);

    /**
     * Check if a workflow is active.
     *
     * @param workflowId The workflow ID
     * @return true if the workflow is active
     */
    boolean isWorkflowActive(Long workflowId);

    // ==================== Context Management ====================

    /**
     * Update workflow context.
     *
     * @param workflowId The workflow ID
     * @param context    The context updates
     */
    void updateContext(Long workflowId, Map<String, Object> context);

    /**
     * Get workflow context.
     *
     * @param workflowId The workflow ID
     * @return The workflow context
     */
    Map<String, Object> getContext(Long workflowId);

    /**
     * Get workflow history for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param limit                  Maximum number of workflows to return
     * @return List of workflows
     */
    List<Workflow> getWorkflowHistory(String orchestratorInstanceId, int limit);
}
