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

/**
 * Types of triggers that can invoke an LLM session.
 */
public enum LlmTriggerType {
    /**
     * Triggered when entering a specific state.
     */
    ON_STATE_ENTER,

    /**
     * Triggered when exiting a specific state.
     */
    ON_STATE_EXIT,

    /**
     * Triggered when any task fails.
     */
    ON_TASK_ERROR,

    /**
     * Triggered when any task times out.
     */
    ON_TASK_TIMEOUT,

    /**
     * Triggered when a pattern is found in task output.
     */
    ON_PATTERN_MATCH,

    /**
     * Triggered when a specific task completes.
     */
    ON_TASK_COMPLETE,

    /**
     * Triggered at each workflow step.
     */
    ON_WORKFLOW_STEP,

    /**
     * Triggered when the same error occurs multiple times.
     */
    ON_ERROR_REPEATED,

    /**
     * Triggered on a schedule (cron expression).
     */
    ON_SCHEDULE,

    /**
     * Only triggered manually via API.
     */
    MANUAL
}
