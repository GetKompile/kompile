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

/**
 * Types of events that can be published by the orchestrator.
 */
public enum OrchestratorEventType {
    // Orchestrator lifecycle
    ORCHESTRATOR_CREATED,
    ORCHESTRATOR_STARTED,
    ORCHESTRATOR_STOPPED,
    ORCHESTRATOR_PAUSED,
    ORCHESTRATOR_RESUMED,
    ORCHESTRATOR_COMPLETED,
    ORCHESTRATOR_FAILED,
    ORCHESTRATOR_CANCELLED,

    // State machine
    STATE_ENTERING,
    STATE_ENTERED,
    STATE_EXITING,
    STATE_EXITED,
    STATE_CHANGED,
    STATE_HANDLER_STARTED,
    STATE_HANDLER_COMPLETED,
    STATE_TIMEOUT,

    // Tasks
    TASK_CREATED,
    TASK_STARTED,
    TASK_OUTPUT,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
    TASK_TIMEOUT,

    // Workflows
    WORKFLOW_STARTED,
    WORKFLOW_STEP_STARTED,
    WORKFLOW_STEP_COMPLETED,
    WORKFLOW_STEP_FAILED,
    WORKFLOW_WAITING_APPROVAL,
    WORKFLOW_STEP_APPROVED,
    WORKFLOW_STEP_REJECTED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    WORKFLOW_CANCELLED,

    // LLM
    LLM_SESSION_STARTED,
    LLM_SESSION_OUTPUT,
    LLM_SESSION_COMPLETED,
    LLM_SESSION_FAILED,
    LLM_TRIGGER_FIRED,
    LLM_ACTION_PROPOSED,

    // Recovery
    SNAPSHOT_CREATED,
    RECOVERY_STARTED,
    RECOVERY_COMPLETED,

    // Errors
    ERROR_OCCURRED,
    ERROR_REPEATED
}
