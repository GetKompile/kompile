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
package ai.kompile.orchestrator.model.output;

/**
 * Actions to take when an output pattern is matched.
 */
public enum ClassificationAction {
    /**
     * Retry the task with the same or modified parameters.
     */
    RETRY,

    /**
     * Invoke LLM to analyze and potentially fix the error.
     */
    INVOKE_LLM,

    /**
     * Skip this step and continue to the next.
     */
    SKIP,

    /**
     * Abort the workflow/task immediately.
     */
    ABORT,

    /**
     * Send a notification (webhook, email, etc.).
     */
    NOTIFY,

    /**
     * Log and continue without intervention.
     */
    LOG_CONTINUE,

    /**
     * Wait for user approval before continuing.
     */
    AWAIT_APPROVAL,

    /**
     * Execute a custom handler task.
     */
    EXECUTE_HANDLER,

    /**
     * Transition to a specific state.
     */
    TRANSITION_STATE,

    /**
     * No action, just classify for reporting.
     */
    NONE,

    /**
     * Custom action defined in action configuration.
     */
    CUSTOM
}
