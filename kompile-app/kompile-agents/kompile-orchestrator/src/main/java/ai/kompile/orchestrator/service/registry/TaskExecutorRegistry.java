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
package ai.kompile.orchestrator.service.registry;

import ai.kompile.orchestrator.api.TaskExecutor;
import ai.kompile.orchestrator.model.task.TaskType;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for task executors.
 */
@Slf4j
public class TaskExecutorRegistry {

    private final List<TaskExecutor> executors = new ArrayList<>();
    private final Map<TaskType, TaskExecutor> typeMapping = new ConcurrentHashMap<>();

    /**
     * Register a task executor.
     */
    public void register(TaskExecutor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        executors.add(executor);

        // Map supported types
        for (TaskType type : executor.getSupportedTypes()) {
            TaskExecutor existing = typeMapping.get(type);
            if (existing == null || executor.getPriority() > existing.getPriority()) {
                typeMapping.put(type, executor);
            }
        }

        // Sort by priority (descending)
        executors.sort(Comparator.comparingInt(TaskExecutor::getPriority).reversed());

        log.debug("Registered task executor for types: {}", executor.getSupportedTypes());
    }

    /**
     * Unregister a task executor.
     */
    public void unregister(TaskExecutor executor) {
        executors.remove(executor);

        // Rebuild type mapping
        typeMapping.clear();
        for (TaskExecutor e : executors) {
            for (TaskType type : e.getSupportedTypes()) {
                TaskExecutor existing = typeMapping.get(type);
                if (existing == null || e.getPriority() > existing.getPriority()) {
                    typeMapping.put(type, e);
                }
            }
        }
    }

    /**
     * Get an executor for a task type.
     */
    public Optional<TaskExecutor> getExecutor(TaskType type) {
        TaskExecutor executor = typeMapping.get(type);
        if (executor != null && executor.isAvailable()) {
            return Optional.of(executor);
        }

        // Fallback: find any available executor for this type
        return executors.stream()
                .filter(e -> e.getSupportedTypes().contains(type) && e.isAvailable())
                .findFirst();
    }

    /**
     * Get all executors for a task type.
     */
    public List<TaskExecutor> getExecutors(TaskType type) {
        return executors.stream()
                .filter(e -> e.getSupportedTypes().contains(type) && e.isAvailable())
                .collect(Collectors.toList());
    }

    /**
     * Get all registered executors.
     */
    public List<TaskExecutor> getAll() {
        return new ArrayList<>(executors);
    }

    /**
     * Check if an executor is available for a task type.
     */
    public boolean hasExecutor(TaskType type) {
        return getExecutor(type).isPresent();
    }
}
