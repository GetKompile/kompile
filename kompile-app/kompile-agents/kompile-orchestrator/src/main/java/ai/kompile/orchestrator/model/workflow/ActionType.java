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
 * Types of actions that can be proposed by an LLM for workflow steps.
 */
public enum ActionType {
    /**
     * Execute a shell command.
     */
    EXECUTE_COMMAND,

    /**
     * Run a predefined task.
     */
    RUN_TASK,

    /**
     * Invoke LLM for further analysis.
     */
    INVOKE_LLM,

    /**
     * Wait for external event or user input.
     */
    WAIT,

    /**
     * Mark workflow as complete.
     */
    COMPLETE,

    /**
     * Mark workflow as failed.
     */
    FAIL,

    /**
     * Require user approval before continuing.
     */
    AWAIT_APPROVAL,

    /**
     * Execute code.
     */
    EXECUTE_CODE,

    /**
     * Make an HTTP request.
     */
    HTTP_REQUEST,

    /**
     * Custom action type.
     */
    CUSTOM
}
