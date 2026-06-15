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

/**
 * Status of a workflow.
 */
public enum WorkflowStatus {
    /**
     * Workflow is actively in progress.
     */
    IN_PROGRESS,

    /**
     * Workflow is waiting for user approval of a step.
     */
    WAITING_APPROVAL,

    /**
     * Workflow is waiting for a task to complete.
     */
    WAITING_TASK,

    /**
     * Workflow is waiting for LLM response.
     */
    WAITING_LLM,

    /**
     * Workflow completed successfully.
     */
    COMPLETED,

    /**
     * Workflow failed.
     */
    FAILED,

    /**
     * Workflow was cancelled.
     */
    CANCELLED,

    /**
     * Workflow is paused.
     */
    PAUSED;

    /**
     * Check if workflow is still active.
     */
    public boolean isActive() {
        return this == IN_PROGRESS || this == WAITING_APPROVAL ||
               this == WAITING_TASK || this == WAITING_LLM || this == PAUSED;
    }

    /**
     * Check if workflow has finished.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
