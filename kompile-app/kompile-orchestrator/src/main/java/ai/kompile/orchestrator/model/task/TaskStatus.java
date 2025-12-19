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
 * Status of a task instance.
 */
public enum TaskStatus {
    /**
     * Task is pending execution.
     */
    PENDING,

    /**
     * Task is currently running.
     */
    RUNNING,

    /**
     * Task completed successfully.
     */
    SUCCESS,

    /**
     * Task failed with an error.
     */
    FAILED,

    /**
     * Task timed out.
     */
    TIMEOUT,

    /**
     * Task was cancelled.
     */
    CANCELLED;

    /**
     * Check if this status represents a running task.
     */
    public boolean isRunning() {
        return this == PENDING || this == RUNNING;
    }

    /**
     * Check if this status represents a terminal state.
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT || this == CANCELLED;
    }

    /**
     * Check if this status represents a successful completion.
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Check if this status represents a failure.
     */
    public boolean isFailure() {
        return this == FAILED || this == TIMEOUT;
    }
}
