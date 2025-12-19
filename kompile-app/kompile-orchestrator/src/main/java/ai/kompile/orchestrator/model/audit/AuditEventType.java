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

/**
 * Types of audit events.
 */
public enum AuditEventType {
    /**
     * Orchestrator lifecycle events (start, stop, pause, resume).
     */
    ORCHESTRATOR_LIFECYCLE,

    /**
     * State machine state changes.
     */
    STATE_CHANGE,

    /**
     * Task lifecycle events (start, complete, fail, cancel).
     */
    TASK_LIFECYCLE,

    /**
     * LLM session interactions.
     */
    LLM_INTERACTION,

    /**
     * Workflow lifecycle events.
     */
    WORKFLOW_LIFECYCLE,

    /**
     * Trigger activations.
     */
    TRIGGER_FIRED,

    /**
     * Hook executions.
     */
    HOOK_EXECUTED,

    /**
     * Error events.
     */
    ERROR,

    /**
     * Configuration changes.
     */
    CONFIGURATION_CHANGE,

    /**
     * Recovery events.
     */
    RECOVERY,

    /**
     * Snapshot events.
     */
    SNAPSHOT,

    /**
     * User actions.
     */
    USER_ACTION,

    /**
     * API calls.
     */
    API_CALL,

    /**
     * Security-related events.
     */
    SECURITY
}
