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
package ai.kompile.orchestrator.service.impl;

import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.api.TaskExecutor;
import ai.kompile.orchestrator.config.OrchestratorProperties;
import ai.kompile.orchestrator.model.event.TaskEvent;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskDefinitionRepository;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import ai.kompile.orchestrator.service.registry.TaskDefinitionRegistry;
import ai.kompile.orchestrator.service.registry.TaskExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Default implementation of TaskExecutionService.
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultTaskExecutionService implements TaskExecutionService {

    private final TaskDefinitionRegistry definitionRegistry;
    private final TaskExecutorRegistry executorRegistry;
    private final TaskInstanceRepository instanceRepository;
    private final TaskDefinitionRepository definitionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrchestratorProperties properties;

    private final Semaphore concurrencySemaphore;

    public DefaultTaskExecutionService(TaskDefinitionRegistry definitionRegistry,
                                       TaskExecutorRegistry executorRegistry,
                                       TaskInstanceRepository instanceRepository,
                                       TaskDefinitionRepository definitionRepository,
                                       ApplicationEventPublisher eventPublisher,
                                       OrchestratorProperties properties) {
        this.definitionRegistry = definitionRegistry;
        this.executorRegistry = executorRegistry;
        this.instanceRepository = instanceRepository;
        this.definitionRepository = definitionRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.concurrencySemaphore = new Semaphore(properties.getTask().getMaxConcurrent());
    }

    // ==================== Task Definition Management ====================

    @Override
    public void registerTaskDefinition(TaskDefinition definition) {
        definitionRegistry.register(definition);
        definitionRepository.save(definition);
        log.info("Registered task definition: {} ({})", definition.getTaskId(), definition.getName());
    }

    @Override
    public Optional<TaskDefinition> getTaskDefinition(String taskDefinitionId) {
        Optional<TaskDefinition> fromRegistry = definitionRegistry.get(taskDefinitionId);
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }
        return definitionRepository.findById(taskDefinitionId);
    }

    @Override
    public List<TaskDefinition> getAllTaskDefinitions() {
        return definitionRegistry.getAll();
    }

    @Override
    public void unregisterTaskDefinition(String taskDefinitionId) {
        definitionRegistry.unregister(taskDefinitionId);
        definitionRepository.deleteById(taskDefinitionId);
        log.info("Unregistered task definition: {}", taskDefinitionId);
    }

    // ==================== Task Execution ====================

    @Override
    public TaskInstance executeTask(String taskDefinitionId, Map<String, String> variables, String orchestratorInstanceId) {
        return executeTask(taskDefinitionId, variables, orchestratorInstanceId, TaskExecutionOptions.defaults());
    }

    @Override
    public TaskInstance executeTask(String taskDefinitionId, Map<String, String> variables,
                                    String orchestratorInstanceId, TaskExecutionOptions options) {
        TaskDefinition definition = getTaskDefinition(taskDefinitionId)
                .orElseThrow(() -> new IllegalArgumentException("Task definition not found: " + taskDefinitionId));
        return executeTask(definition, variables, orchestratorInstanceId, options);
    }

    @Override
    public TaskInstance executeTask(TaskDefinition definition, Map<String, String> variables, String orchestratorInstanceId) {
        return executeTask(definition, variables, orchestratorInstanceId, TaskExecutionOptions.defaults());
    }

    @Override
    public TaskInstance executeTask(TaskDefinition definition, Map<String, String> variables,
                                    String orchestratorInstanceId, TaskExecutionOptions options) {
        // Validate required variables
        List<String> missingVars = definition.validateVariables(variables);
        if (!missingVars.isEmpty()) {
            throw new IllegalArgumentException("Missing required variables: " + missingVars);
        }

        // Get executor for task type
        TaskExecutor executor = executorRegistry.getExecutor(definition.getTaskType())
                .orElseThrow(() -> new IllegalStateException("No executor available for task type: " + definition.getTaskType()));

        // Create task instance
        TaskInstance instance = TaskInstance.fromDefinition(definition, variables);
        instance.setOrchestratorInstanceId(orchestratorInstanceId);

        // Apply options
        if (options.getWorkingDirectory() != null) {
            instance.setWorkingDirectory(options.getWorkingDirectory());
        }
        if (options.getTimeout() != null) {
            instance.setTimeout(options.getTimeout());
        }

        // Save initial instance
        instance = instanceRepository.save(instance);
        final TaskInstance savedInstance = instance;

        log.info("Executing task '{}' (type: {}, id: {}) for orchestrator {}",
                definition.getName(), definition.getTaskType(), instance.getId(), orchestratorInstanceId);

        // Check concurrency limit
        if (!options.isAsync()) {
            return executeWithConcurrencyControl(executor, savedInstance, definition, variables, options);
        } else {
            // For async, acquire permit in background
            return executeAsync(executor, savedInstance, definition, variables, options);
        }
    }

    private TaskInstance executeWithConcurrencyControl(TaskExecutor executor, TaskInstance instance,
                                                       TaskDefinition definition, Map<String, String> variables,
                                                       TaskExecutionOptions options) {
        try {
            if (!concurrencySemaphore.tryAcquire()) {
                log.warn("Task execution queue full, waiting for available slot");
                concurrencySemaphore.acquire();
            }

            try {
                return executor.execute(instance, options);
            } finally {
                concurrencySemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            instance.markFailed("Task execution interrupted");
            return instanceRepository.save(instance);
        }
    }

    private TaskInstance executeAsync(TaskExecutor executor, TaskInstance instance,
                                      TaskDefinition definition, Map<String, String> variables,
                                      TaskExecutionOptions options) {
        // For async, we start the task and return immediately
        Thread asyncThread = new Thread(() -> {
            try {
                concurrencySemaphore.acquire();
                try {
                    executor.execute(instance, options);
                } finally {
                    concurrencySemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                instance.markFailed("Task execution interrupted");
                instanceRepository.save(instance);
            }
        });
        asyncThread.setName("task-async-" + instance.getId());
        asyncThread.start();

        return instance;
    }

    @Override
    public TaskInstance executeCommand(String command, String orchestratorInstanceId) {
        return executeCommand(command, orchestratorInstanceId, TaskExecutionOptions.defaults());
    }

    @Override
    public TaskInstance executeCommand(String command, String orchestratorInstanceId, TaskExecutionOptions options) {
        // Create an ad-hoc task definition
        TaskDefinition adhocDef = TaskDefinition.builder()
                .taskId("adhoc-" + UUID.randomUUID().toString())
                .name("Ad-hoc Command")
                .taskType(TaskType.SHELL)
                .command(command)
                .timeoutSeconds(options.getTimeout() != null ? options.getTimeout().toSeconds() : properties.getTask().getDefaultTimeout().toSeconds())
                .build();

        return executeTask(adhocDef, Collections.emptyMap(), orchestratorInstanceId, options);
    }

    // ==================== Task Management ====================

    @Override
    public void cancelTask(Long taskInstanceId) {
        TaskInstance instance = instanceRepository.findById(taskInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("Task instance not found: " + taskInstanceId));

        if (!instance.isRunning()) {
            log.warn("Task {} is not running, cannot cancel", taskInstanceId);
            return;
        }

        // Find the executor that can handle this task
        Optional<TaskExecutor> executor = executorRegistry.getExecutor(instance.getTaskType());
        if (executor.isPresent()) {
            executor.get().cancel(taskInstanceId);
            log.info("Cancelled task: {}", taskInstanceId);
        } else {
            log.warn("No executor found to cancel task: {}", taskInstanceId);
        }

        // Update status if still running
        instance = instanceRepository.findById(taskInstanceId).orElse(instance);
        if (instance.isRunning()) {
            instance.markCancelled();
            instanceRepository.save(instance);
            eventPublisher.publishEvent(TaskEvent.cancelled(this, instance));
        }
    }

    @Override
    public Optional<TaskInstance> getTaskInstance(Long taskInstanceId) {
        return instanceRepository.findById(taskInstanceId);
    }

    @Override
    public List<TaskInstance> getTaskInstances(String orchestratorInstanceId) {
        return instanceRepository.findByOrchestratorInstanceId(orchestratorInstanceId);
    }

    @Override
    public List<TaskInstance> getRunningTasks(String orchestratorInstanceId) {
        return instanceRepository.findByOrchestratorInstanceIdAndStatus(orchestratorInstanceId, TaskStatus.RUNNING);
    }

    @Override
    public boolean isTaskRunning(Long taskInstanceId) {
        // First check the executor
        Optional<TaskInstance> instance = instanceRepository.findById(taskInstanceId);
        if (instance.isEmpty()) {
            return false;
        }

        Optional<TaskExecutor> executor = executorRegistry.getExecutor(instance.get().getTaskType());
        if (executor.isPresent() && executor.get().isRunning(taskInstanceId)) {
            return true;
        }

        // Fall back to DB status
        return instance.get().isRunning();
    }

    @Override
    public String getTaskOutput(Long taskInstanceId) {
        // Try to get live output from executor
        Optional<TaskInstance> instance = instanceRepository.findById(taskInstanceId);
        if (instance.isEmpty()) {
            return null;
        }

        Optional<TaskExecutor> executor = executorRegistry.getExecutor(instance.get().getTaskType());
        if (executor.isPresent()) {
            String liveOutput = executor.get().getCurrentOutput(taskInstanceId);
            if (liveOutput != null) {
                return liveOutput;
            }
        }

        // Fall back to stored output
        return instance.get().getOutput();
    }

    // ==================== Executor Management ====================

    @Override
    public void registerExecutor(TaskExecutor executor) {
        executorRegistry.register(executor);
        log.info("Registered task executor for types: {}", executor.getSupportedTypes());
    }

    @Override
    public Optional<TaskExecutor> getExecutor(TaskType taskType) {
        return executorRegistry.getExecutor(taskType);
    }

    @Override
    public List<TaskExecutor> getAllExecutors() {
        return executorRegistry.getAll();
    }

    /**
     * Get the current number of available execution slots.
     */
    public int getAvailableSlots() {
        return concurrencySemaphore.availablePermits();
    }

    /**
     * Get the maximum number of concurrent tasks.
     */
    public int getMaxConcurrent() {
        return properties.getTask().getMaxConcurrent();
    }
}
