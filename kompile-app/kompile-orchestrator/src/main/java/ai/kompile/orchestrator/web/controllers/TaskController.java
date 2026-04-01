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
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.orchestrator.api.OrchestratorService;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.model.task.TaskInstance;
import ai.kompile.orchestrator.web.dto.ExecuteCommandRequest;
import ai.kompile.orchestrator.web.dto.ExecuteTaskRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for task management.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator/{instanceId}/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final OrchestratorService orchestratorService;
    private final TaskExecutionService taskExecutionService;

    /**
     * Execute a defined task.
     */
    @PostMapping("/execute")
    public ResponseEntity<TaskInstance> executeTask(
            @PathVariable("instanceId") String instanceId,
            @RequestBody ExecuteTaskRequest request) {
        log.info("Executing task {} for orchestrator: {}", request.getTaskDefinitionId(), instanceId);

        TaskInstance task = orchestratorService.executeTask(
                instanceId,
                request.getTaskDefinitionId(),
                request.getVariables());

        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    /**
     * Execute a command directly.
     */
    @PostMapping("/command")
    public ResponseEntity<TaskInstance> executeCommand(
            @PathVariable("instanceId") String instanceId,
            @RequestBody ExecuteCommandRequest request) {
        log.info("Executing command for orchestrator: {}", instanceId);

        TaskInstance task = orchestratorService.executeCommand(instanceId, request.getCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    /**
     * Cancel a running task.
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("taskId") Long taskId) {
        log.info("Cancelling task {} for orchestrator: {}", taskId, instanceId);
        orchestratorService.cancelTask(taskId);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "message", "Task cancellation requested"));
    }

    /**
     * Get a task instance by ID.
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskInstance> getTask(
            @PathVariable("instanceId") String instanceId,
            @PathVariable("taskId") Long taskId) {
        return taskExecutionService.getTaskInstance(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all running tasks for an orchestrator.
     */
    @GetMapping("/running")
    public ResponseEntity<List<TaskInstance>> getRunningTasks(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(taskExecutionService.getRunningTasks(instanceId));
    }

    /**
     * Get task history for an orchestrator.
     */
    @GetMapping("/history")
    public ResponseEntity<List<TaskInstance>> getTaskHistory(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(taskExecutionService.getTaskInstances(instanceId));
    }

    /**
     * Register a task definition.
     */
    @PostMapping("/definitions")
    public ResponseEntity<Map<String, Object>> registerTaskDefinition(
            @PathVariable("instanceId") String instanceId,
            @RequestBody TaskDefinition definition) {
        log.info("Registering task definition {} for orchestrator: {}", definition.getTaskId(), instanceId);
        orchestratorService.registerTaskDefinition(definition);
        return ResponseEntity.ok(Map.of(
                "taskId", definition.getTaskId(),
                "message", "Task definition registered successfully"));
    }
}
