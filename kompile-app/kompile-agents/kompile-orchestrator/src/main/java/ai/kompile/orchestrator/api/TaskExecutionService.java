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

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing task definitions and executions.
 */
public interface TaskExecutionService {

    // ==================== Task Definition Management ====================

    /**
     * Register a task definition.
     *
     * @param definition The task definition to register
     */
    void registerTaskDefinition(TaskDefinition definition);

    /**
     * Get a task definition by ID.
     *
     * @param taskDefinitionId The task definition ID
     * @return The task definition, or empty if not found
     */
    Optional<TaskDefinition> getTaskDefinition(String taskDefinitionId);

    /**
     * Get all task definitions.
     *
     * @return List of all task definitions
     */
    List<TaskDefinition> getAllTaskDefinitions();

    /**
     * Unregister a task definition.
     *
     * @param taskDefinitionId The task definition ID
     */
    void unregisterTaskDefinition(String taskDefinitionId);

    // ==================== Task Execution ====================

    /**
     * Execute a task from a definition ID.
     *
     * @param taskDefinitionId       The task definition ID
     * @param variables              Variables for template substitution
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The task instance
     */
    TaskInstance executeTask(String taskDefinitionId, Map<String, String> variables, String orchestratorInstanceId);

    /**
     * Execute a task from a definition ID with options.
     *
     * @param taskDefinitionId       The task definition ID
     * @param variables              Variables for template substitution
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param options                Execution options
     * @return The task instance
     */
    TaskInstance executeTask(String taskDefinitionId, Map<String, String> variables,
                             String orchestratorInstanceId, TaskExecutionOptions options);

    /**
     * Execute a task from a definition.
     *
     * @param definition             The task definition
     * @param variables              Variables for template substitution
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The task instance
     */
    TaskInstance executeTask(TaskDefinition definition, Map<String, String> variables, String orchestratorInstanceId);

    /**
     * Execute a task from a definition with options.
     *
     * @param definition             The task definition
     * @param variables              Variables for template substitution
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param options                Execution options
     * @return The task instance
     */
    TaskInstance executeTask(TaskDefinition definition, Map<String, String> variables,
                             String orchestratorInstanceId, TaskExecutionOptions options);

    /**
     * Execute a command directly without a task definition.
     *
     * @param command                The command to execute
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return The task instance
     */
    TaskInstance executeCommand(String command, String orchestratorInstanceId);

    /**
     * Execute a command with options.
     *
     * @param command                The command to execute
     * @param orchestratorInstanceId The orchestrator instance ID
     * @param options                Execution options
     * @return The task instance
     */
    TaskInstance executeCommand(String command, String orchestratorInstanceId, TaskExecutionOptions options);

    // ==================== Task Management ====================

    /**
     * Cancel a running task.
     *
     * @param taskInstanceId The task instance ID
     */
    void cancelTask(Long taskInstanceId);

    /**
     * Get a task instance by ID.
     *
     * @param taskInstanceId The task instance ID
     * @return The task instance, or empty if not found
     */
    Optional<TaskInstance> getTaskInstance(Long taskInstanceId);

    /**
     * Get all task instances for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of task instances
     */
    List<TaskInstance> getTaskInstances(String orchestratorInstanceId);

    /**
     * Get running task instances for an orchestrator.
     *
     * @param orchestratorInstanceId The orchestrator instance ID
     * @return List of running task instances
     */
    List<TaskInstance> getRunningTasks(String orchestratorInstanceId);

    /**
     * Check if a task is running.
     *
     * @param taskInstanceId The task instance ID
     * @return true if the task is running
     */
    boolean isTaskRunning(Long taskInstanceId);

    /**
     * Get the current output of a running task.
     *
     * @param taskInstanceId The task instance ID
     * @return The current output
     */
    String getTaskOutput(Long taskInstanceId);

    // ==================== Executor Management ====================

    /**
     * Register a task executor.
     *
     * @param executor The executor to register
     */
    void registerExecutor(TaskExecutor executor);

    /**
     * Get an executor for a task type.
     *
     * @param taskType The task type
     * @return The executor, or empty if not found
     */
    Optional<TaskExecutor> getExecutor(TaskType taskType);

    /**
     * Get all registered executors.
     *
     * @return List of executors
     */
    List<TaskExecutor> getAllExecutors();
}
