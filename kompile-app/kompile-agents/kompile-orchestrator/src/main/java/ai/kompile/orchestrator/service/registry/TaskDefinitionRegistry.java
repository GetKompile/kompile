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

import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.task.TaskType;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for task definitions.
 */
@Slf4j
public class TaskDefinitionRegistry {

    private final Map<String, TaskDefinition> definitions = new ConcurrentHashMap<>();

    /**
     * Register a task definition.
     */
    public void register(TaskDefinition definition) {
        if (definition == null || definition.getTaskId() == null) {
            throw new IllegalArgumentException("Definition and task ID must not be null");
        }
        definitions.put(definition.getTaskId(), definition);
        log.debug("Registered task definition: {}", definition.getTaskId());
    }

    /**
     * Unregister a task definition.
     */
    public void unregister(String taskId) {
        definitions.remove(taskId);
        log.debug("Unregistered task definition: {}", taskId);
    }

    /**
     * Get a task definition by ID.
     */
    public Optional<TaskDefinition> get(String taskId) {
        return Optional.ofNullable(definitions.get(taskId));
    }

    /**
     * Check if a task definition exists.
     */
    public boolean exists(String taskId) {
        return definitions.containsKey(taskId);
    }

    /**
     * Get all task definitions.
     */
    public List<TaskDefinition> getAll() {
        return new ArrayList<>(definitions.values());
    }

    /**
     * Get task definitions by type.
     */
    public List<TaskDefinition> getByType(TaskType type) {
        return definitions.values().stream()
                .filter(d -> d.getTaskType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Clear all definitions.
     */
    public void clear() {
        definitions.clear();
    }
}
