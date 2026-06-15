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

import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.task.TaskExecutionOptions;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.model.task.TaskType;

import java.util.Map;
import java.util.Set;

/**
 * Interface for task executors that handle different task types.
 */
public interface TaskExecutor {

    /**
     * Get the task types this executor supports.
     *
     * @return Set of supported task types
     */
    Set<TaskType> getSupportedTypes();

    /**
     * Execute a task from a definition with variables.
     *
     * @param definition The task definition
     * @param variables  Variables for template substitution
     * @param options    Execution options
     * @return The task instance (may still be running if async)
     */
    TaskInstance execute(TaskDefinition definition, Map<String, String> variables, TaskExecutionOptions options);

    /**
     * Execute a task instance directly.
     *
     * @param taskInstance The task instance to execute
     * @param options      Execution options
     * @return The task instance (updated with results)
     */
    TaskInstance execute(TaskInstance taskInstance, TaskExecutionOptions options);

    /**
     * Cancel a running task.
     *
     * @param taskInstanceId The task instance ID
     */
    void cancel(Long taskInstanceId);

    /**
     * Check if a task is currently running.
     *
     * @param taskInstanceId The task instance ID
     * @return true if the task is running
     */
    boolean isRunning(Long taskInstanceId);

    /**
     * Get the current output of a running task.
     *
     * @param taskInstanceId The task instance ID
     * @return The current output (may be partial)
     */
    String getCurrentOutput(Long taskInstanceId);

    /**
     * Get the priority of this executor.
     * Higher priority executors are tried first for task type matching.
     *
     * @return The priority (default 0)
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Check if this executor is available/configured.
     *
     * @return true if the executor can be used
     */
    default boolean isAvailable() {
        return true;
    }
}
