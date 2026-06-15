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
import ai.kompile.orchestrator.model.OrchestratorInstance;
import ai.kompile.orchestrator.model.OrchestratorStatus;
import ai.kompile.orchestrator.model.llm.LlmTrigger;
import ai.kompile.orchestrator.model.state.StateDefinition;
import ai.kompile.orchestrator.model.task.TaskDefinition;
import ai.kompile.orchestrator.web.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for orchestrator instance management.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    // ==================== Lifecycle Operations ====================

    /**
     * Create a new orchestrator instance.
     */
    @PostMapping
    public ResponseEntity<OrchestratorInstance> create(@RequestBody CreateOrchestratorRequest request) {
        log.info("Creating orchestrator: {}", request.getName());

        OrchestratorInstance instance = orchestratorService.create(
                request.getName(),
                request.getDescription(),
                request.getInitialContext(),
                request.getCustomStates(),
                request.getTaskDefinitions(),
                request.getLlmTriggers());

        return ResponseEntity.status(HttpStatus.CREATED).body(instance);
    }

    /**
     * Start an orchestrator instance.
     */
    @PostMapping("/{instanceId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("instanceId") String instanceId) {
        log.info("Starting orchestrator: {}", instanceId);
        orchestratorService.start(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "status", "STARTED",
                "message", "Orchestrator started successfully"));
    }

    /**
     * Pause an orchestrator instance.
     */
    @PostMapping("/{instanceId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable("instanceId") String instanceId) {
        log.info("Pausing orchestrator: {}", instanceId);
        orchestratorService.pause(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "status", "PAUSED",
                "message", "Orchestrator paused successfully"));
    }

    /**
     * Resume a paused orchestrator instance.
     */
    @PostMapping("/{instanceId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable("instanceId") String instanceId) {
        log.info("Resuming orchestrator: {}", instanceId);
        orchestratorService.resume(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "status", "RESUMED",
                "message", "Orchestrator resumed successfully"));
    }

    /**
     * Stop an orchestrator instance.
     */
    @PostMapping("/{instanceId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable("instanceId") String instanceId) {
        log.info("Stopping orchestrator: {}", instanceId);
        orchestratorService.stop(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "status", "STOPPED",
                "message", "Orchestrator stopped successfully"));
    }

    /**
     * Delete an orchestrator instance.
     */
    @DeleteMapping("/{instanceId}")
    public ResponseEntity<Void> delete(@PathVariable("instanceId") String instanceId) {
        log.info("Deleting orchestrator: {}", instanceId);
        orchestratorService.delete(instanceId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Query Operations ====================

    /**
     * Get an orchestrator instance by ID.
     */
    @GetMapping("/{instanceId}")
    public ResponseEntity<OrchestratorInstance> getInstance(@PathVariable("instanceId") String instanceId) {
        return orchestratorService.getInstance(instanceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all orchestrator instances.
     */
    @GetMapping
    public ResponseEntity<List<OrchestratorInstance>> getAllInstances() {
        return ResponseEntity.ok(orchestratorService.getAllInstances());
    }

    /**
     * Get running orchestrator instances.
     */
    @GetMapping("/running")
    public ResponseEntity<List<OrchestratorInstance>> getRunningInstances() {
        return ResponseEntity.ok(orchestratorService.getRunningInstances());
    }

    /**
     * Get orchestrator instances by status.
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<OrchestratorInstance>> getByStatus(@PathVariable("status") OrchestratorStatus status) {
        return ResponseEntity.ok(orchestratorService.getInstancesByStatus(status));
    }

    // ==================== State Operations ====================

    /**
     * Get the current state of an orchestrator.
     */
    @GetMapping("/{instanceId}/state")
    public ResponseEntity<StateDefinition> getCurrentState(@PathVariable("instanceId") String instanceId) {
        return orchestratorService.getCurrentState(instanceId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Transition to a new state.
     */
    @PostMapping("/{instanceId}/transition")
    public ResponseEntity<Map<String, Object>> transitionTo(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StateTransitionRequest request) {
        log.info("Transitioning orchestrator {} to state: {}", instanceId, request.getTargetStateId());
        orchestratorService.transitionTo(instanceId, request.getTargetStateId(), request.getContext());
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "targetState", request.getTargetStateId(),
                "message", "Transition initiated successfully"));
    }

    /**
     * Register a custom state.
     */
    @PostMapping("/{instanceId}/states")
    public ResponseEntity<Map<String, Object>> registerState(
            @PathVariable("instanceId") String instanceId,
            @RequestBody StateDefinition state) {
        log.info("Registering state {} for orchestrator: {}", state.getStateId(), instanceId);
        orchestratorService.registerState(instanceId, state);
        return ResponseEntity.ok(Map.of(
                "stateId", state.getStateId(),
                "message", "State registered successfully"));
    }

    // ==================== Context Operations ====================

    /**
     * Get the context of an orchestrator.
     */
    @GetMapping("/{instanceId}/context")
    public ResponseEntity<Map<String, Object>> getContext(@PathVariable("instanceId") String instanceId) {
        return ResponseEntity.ok(orchestratorService.getContext(instanceId));
    }

    /**
     * Update the context of an orchestrator.
     */
    @PutMapping("/{instanceId}/context")
    public ResponseEntity<Map<String, Object>> updateContext(
            @PathVariable("instanceId") String instanceId,
            @RequestBody Map<String, Object> context) {
        orchestratorService.updateContext(instanceId, context);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "message", "Context updated successfully"));
    }

    // ==================== Snapshot/Recovery Operations ====================

    /**
     * Create a snapshot of an orchestrator.
     */
    @PostMapping("/{instanceId}/snapshot")
    public ResponseEntity<Map<String, Object>> createSnapshot(@PathVariable("instanceId") String instanceId) {
        log.info("Creating snapshot for orchestrator: {}", instanceId);
        orchestratorService.createSnapshot(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "message", "Snapshot created successfully"));
    }

    /**
     * Recover an orchestrator from its latest snapshot.
     */
    @PostMapping("/{instanceId}/recover")
    public ResponseEntity<Map<String, Object>> recover(@PathVariable("instanceId") String instanceId) {
        log.info("Recovering orchestrator: {}", instanceId);
        orchestratorService.recover(instanceId);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId,
                "message", "Recovery initiated"));
    }

    // ==================== Exception Handlers ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Conflict",
                "message", e.getMessage()));
    }
}
