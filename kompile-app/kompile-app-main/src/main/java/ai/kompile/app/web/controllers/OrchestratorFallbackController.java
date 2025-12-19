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
package ai.kompile.app.web.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fallback controller for orchestrator endpoints when the orchestrator module
 * is not available or fails to initialize. Returns empty responses instead of 404 errors.
 *
 * This controller activates when no OrchestratorService bean is present,
 * either because the module is not included or its initialization failed.
 */
@Slf4j
@RestController
@RequestMapping("/api/orchestrator")
@ConditionalOnMissingBean(type = "ai.kompile.orchestrator.api.OrchestratorService")
public class OrchestratorFallbackController {

    private static final String NOT_CONFIGURED_MESSAGE = "Orchestrator module is not configured. " +
            "Enable it with kompile.orchestrator.enabled=true and ensure database is available.";

    public OrchestratorFallbackController() {
        log.info("OrchestratorFallbackController activated - orchestrator module not available");
    }

    @GetMapping
    public ResponseEntity<List<Object>> getAllInstances() {
        log.debug("Orchestrator fallback: returning empty list for getAllInstances");
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/running")
    public ResponseEntity<List<Object>> getRunningInstances() {
        log.debug("Orchestrator fallback: returning empty list for getRunningInstances");
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Object>> getByStatus(@PathVariable String status) {
        log.debug("Orchestrator fallback: returning empty list for getByStatus({})", status);
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/{instanceId}")
    public ResponseEntity<Map<String, Object>> getInstance(@PathVariable String instanceId) {
        return ResponseEntity.ok(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> request) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @DeleteMapping("/{instanceId}")
    public ResponseEntity<Void> delete(@PathVariable String instanceId) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{instanceId}/state")
    public ResponseEntity<Map<String, Object>> getCurrentState(@PathVariable String instanceId) {
        return ResponseEntity.ok(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/transition")
    public ResponseEntity<Map<String, Object>> transitionTo(
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> request) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/states")
    public ResponseEntity<Map<String, Object>> registerState(
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> state) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @GetMapping("/{instanceId}/context")
    public ResponseEntity<Map<String, Object>> getContext(@PathVariable String instanceId) {
        return ResponseEntity.ok(Collections.emptyMap());
    }

    @PutMapping("/{instanceId}/context")
    public ResponseEntity<Map<String, Object>> updateContext(
            @PathVariable String instanceId,
            @RequestBody Map<String, Object> context) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/snapshot")
    public ResponseEntity<Map<String, Object>> createSnapshot(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }

    @PostMapping("/{instanceId}/recover")
    public ResponseEntity<Map<String, Object>> recover(@PathVariable String instanceId) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "not_configured",
                "message", NOT_CONFIGURED_MESSAGE
        ));
    }
}
