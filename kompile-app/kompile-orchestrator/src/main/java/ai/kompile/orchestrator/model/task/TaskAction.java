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

/**
 * Actions that can be triggered on task completion (success or failure).
 * These actions integrate with the orchestrator's state machine,
 * React Agent architecture, and LLM capabilities.
 */
public enum TaskAction {

    /**
     * Continue to the next step without any special action.
     */
    CONTINUE,

    /**
     * Retry the task immediately.
     */
    RETRY,

    /**
     * Retry with exponential backoff.
     */
    RETRY_WITH_BACKOFF,

    /**
     * Invoke an LLM to analyze the output.
     * Uses the React Agent architecture with feedback loops.
     */
    INVOKE_LLM,

    /**
     * Invoke an LLM specifically to generate a fix for the failure.
     * Stores the conversation history for audit.
     */
    INVOKE_LLM_FOR_FIX,

    /**
     * Transition to a specific state in the state machine.
     */
    TRANSITION_STATE,

    /**
     * Execute another task by its ID.
     */
    EXECUTE_TASK,

    /**
     * Send a notification (webhook, email, etc.).
     */
    NOTIFY,

    /**
     * Log the result and continue.
     */
    LOG,

    /**
     * Skip this step and move on.
     */
    SKIP,

    /**
     * Abort the workflow entirely.
     */
    ABORT,

    /**
     * Wait for manual approval before continuing.
     */
    AWAIT_APPROVAL,

    /**
     * Escalate to a human operator.
     */
    ESCALATE,

    /**
     * Parse the log output using the output classifier.
     */
    PARSE_LOG,

    /**
     * Wait for an external process to complete.
     */
    WAIT_FOR_PROCESS,

    /**
     * Send the output to the React Agent for processing.
     * This enables feedback loops where the agent can iteratively
     * analyze and respond to task output.
     */
    SEND_TO_AGENT,

    /**
     * Custom action defined in the task's metadata.
     */
    CUSTOM;

    /**
     * Check if this action involves LLM/Agent interaction.
     */
    public boolean involvesLlm() {
        return this == INVOKE_LLM ||
               this == INVOKE_LLM_FOR_FIX ||
               this == SEND_TO_AGENT;
    }

    /**
     * Check if this action involves state transitions.
     */
    public boolean involvesStateChange() {
        return this == TRANSITION_STATE ||
               this == ABORT ||
               this == SKIP;
    }

    /**
     * Check if this action requires human intervention.
     */
    public boolean requiresHumanIntervention() {
        return this == AWAIT_APPROVAL || this == ESCALATE;
    }

    /**
     * Check if this action involves retrying.
     */
    public boolean involvesRetry() {
        return this == RETRY || this == RETRY_WITH_BACKOFF;
    }
}
