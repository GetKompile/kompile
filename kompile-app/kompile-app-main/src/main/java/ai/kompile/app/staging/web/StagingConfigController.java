/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.staging.web;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for managing staging service configurations
 * and interacting with remote staging services.
 */
@RestController
@RequestMapping("/api/staging-config")
@CrossOrigin(origins = "*")
public class StagingConfigController {

    private final StagingServiceConfigService configService;
    private final StagingClientService clientService;

    public StagingConfigController(StagingServiceConfigService configService,
                                   StagingClientService clientService) {
        this.configService = configService;
        this.clientService = clientService;
    }

    // ==================== Configuration CRUD ====================

    /**
     * Get all staging service configurations.
     */
    @GetMapping("/configs")
    public List<StagingServiceConfig> getAllConfigs() {
        return configService.getAllConfigs();
    }

    /**
     * Get a specific configuration by ID.
     */
    @GetMapping("/configs/{id}")
    public ResponseEntity<StagingServiceConfig> getConfig(@PathVariable Long id) {
        return configService.getConfigById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the active configuration.
     */
    @GetMapping("/configs/active")
    public ResponseEntity<StagingServiceConfig> getActiveConfig() {
        return configService.getActiveConfig()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Create a new configuration.
     */
    @PostMapping("/configs")
    public ResponseEntity<?> createConfig(@RequestBody StagingServiceConfigDto dto) {
        try {
            StagingServiceConfig config = StagingServiceConfig.builder()
                    .name(dto.name())
                    .endpointUrl(dto.endpointUrl())
                    .apiKey(dto.apiKey())
                    .active(dto.active() != null ? dto.active() : false)
                    .connectionTimeoutMs(dto.connectionTimeoutMs() != null ? dto.connectionTimeoutMs() : 5000)
                    .readTimeoutMs(dto.readTimeoutMs() != null ? dto.readTimeoutMs() : 30000)
                    .autoSync(dto.autoSync() != null ? dto.autoSync() : false)
                    .syncIntervalMinutes(dto.syncIntervalMinutes() != null ? dto.syncIntervalMinutes() : 60)
                    .description(dto.description())
                    .build();

            StagingServiceConfig created = configService.createConfig(config);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing configuration.
     */
    @PutMapping("/configs/{id}")
    public ResponseEntity<?> updateConfig(@PathVariable Long id, @RequestBody StagingServiceConfigDto dto) {
        try {
            StagingServiceConfig updates = StagingServiceConfig.builder()
                    .name(dto.name())
                    .endpointUrl(dto.endpointUrl())
                    .apiKey(dto.apiKey())
                    .active(dto.active() != null ? dto.active() : false)
                    .connectionTimeoutMs(dto.connectionTimeoutMs() != null ? dto.connectionTimeoutMs() : 5000)
                    .readTimeoutMs(dto.readTimeoutMs() != null ? dto.readTimeoutMs() : 30000)
                    .autoSync(dto.autoSync() != null ? dto.autoSync() : false)
                    .syncIntervalMinutes(dto.syncIntervalMinutes() != null ? dto.syncIntervalMinutes() : 60)
                    .description(dto.description())
                    .build();

            StagingServiceConfig updated = configService.updateConfig(id, updates);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a configuration.
     */
    @DeleteMapping("/configs/{id}")
    public ResponseEntity<?> deleteConfig(@PathVariable Long id) {
        try {
            configService.deleteConfig(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Configuration deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Set a configuration as active.
     */
    @PostMapping("/configs/{id}/activate")
    public ResponseEntity<?> activateConfig(@PathVariable Long id) {
        try {
            StagingServiceConfig activated = configService.setActive(id);
            return ResponseEntity.ok(activated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Connection Testing ====================

    /**
     * Test connection to a staging service by configuration ID.
     */
    @PostMapping("/configs/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        Optional<StagingServiceConfig> optConfig = configService.getConfigById(id);
        if (optConfig.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StagingServiceConfig config = optConfig.get();
        StagingClientService.ConnectionTestResult result = clientService.testConnection(config);

        // Update verification status in database
        configService.updateVerificationStatus(id, result.success(), result.message());

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("modelCount", result.modelCount());
        response.put("version", result.version() != null ? result.version() : "");
        return ResponseEntity.ok(response);
    }

    /**
     * Test connection to the active staging service.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testActiveConnection() {
        StagingClientService.ConnectionTestResult result = clientService.testActiveConnection();

        // Update verification status if we have an active config
        configService.getActiveConfig().ifPresent(config ->
                configService.updateVerificationStatus(config.getId(), result.success(), result.message()));

        return ResponseEntity.ok(Map.of(
                "success", result.success(),
                "message", result.message(),
                "modelCount", result.modelCount(),
                "version", result.version() != null ? result.version() : ""
        ));
    }

    // ==================== Remote Staging Operations ====================

    /**
     * Get the model registry from the active staging service.
     */
    @GetMapping("/remote/registry")
    public ResponseEntity<?> getRemoteRegistry() {
        return clientService.getRegistry()
                .map(registry -> ResponseEntity.ok((Object) registry))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    /**
     * Get the model catalog from the active staging service.
     */
    @GetMapping("/remote/catalog")
    public ResponseEntity<?> getRemoteCatalog() {
        return clientService.getCatalog()
                .map(catalog -> ResponseEntity.ok((Object) catalog))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    /**
     * Get staging status from the active staging service.
     */
    @GetMapping("/remote/status")
    public ResponseEntity<?> getRemoteStagingStatus() {
        return clientService.getStagingStatus()
                .map(status -> ResponseEntity.ok((Object) status))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    /**
     * Stage a model from the catalog on the remote staging service.
     */
    @PostMapping("/remote/stage/{modelId}")
    public ResponseEntity<?> stageRemoteModel(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "false") boolean autoPromote) {
        return clientService.stageModelFromCatalog(modelId, autoPromote)
                .map(result -> ResponseEntity.ok((Object) result))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Failed to stage model or no active staging service")));
    }

    /**
     * Promote a staged model on the remote staging service.
     */
    @PostMapping("/remote/promote/{modelId}")
    public ResponseEntity<Map<String, Object>> promoteRemoteModel(@PathVariable String modelId) {
        boolean success = clientService.promoteModel(modelId);
        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Model promoted successfully"
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "error", "Failed to promote model or no active staging service"
            ));
        }
    }

    /**
     * Get a specific model from the remote registry.
     */
    @GetMapping("/remote/model/{modelId}")
    public ResponseEntity<?> getRemoteModel(@PathVariable String modelId) {
        return clientService.getModel(modelId)
                .map(model -> ResponseEntity.ok((Object) model))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== DTOs ====================

    /**
     * DTO for creating/updating staging service configurations.
     */
    public record StagingServiceConfigDto(
            String name,
            String endpointUrl,
            String apiKey,
            Boolean active,
            Integer connectionTimeoutMs,
            Integer readTimeoutMs,
            Boolean autoSync,
            Integer syncIntervalMinutes,
            String description
    ) {}
}
