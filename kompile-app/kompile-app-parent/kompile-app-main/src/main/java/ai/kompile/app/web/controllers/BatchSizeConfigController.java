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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.BatchSizeConfigService;
import ai.kompile.app.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for embedding batch size configuration and testing.
 *
 * <p>Provides endpoints to:
 * <ul>
 *   <li>List available embedding models with their batch configurations</li>
 *   <li>Get/update batch size settings per model</li>
 *   <li>Run batch size benchmarks to find optimal settings</li>
 *   <li>Reset configurations to defaults</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/embeddings/batch-config")
@CrossOrigin(origins = "*")
public class BatchSizeConfigController {

    private static final Logger log = LoggerFactory.getLogger(BatchSizeConfigController.class);

    private final BatchSizeConfigService configService;

    @Autowired
    public BatchSizeConfigController(BatchSizeConfigService configService) {
        this.configService = configService;
    }

    /**
     * Lists all available embedding models with their current batch configurations.
     *
     * @return List of embedding model information
     */
    @GetMapping("/models")
    public ResponseEntity<List<EmbeddingModelInfo>> getAvailableModels() {
        try {
            List<EmbeddingModelInfo> models = configService.getAvailableModels();
            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Error getting available models", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Gets batch size configuration for a specific model.
     *
     * @param modelId The model identifier
     * @return Batch size configuration
     */
    @GetMapping("/models/{modelId}")
    public ResponseEntity<BatchSizeConfigResponse> getModelConfig(@PathVariable String modelId) {
        try {
            BatchSizeConfigResponse config = configService.getConfiguration(modelId);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error getting config for model: {}", modelId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Gets global batch size configuration (not model-specific).
     *
     * @return Global batch size configuration
     */
    @GetMapping("/global")
    public ResponseEntity<BatchSizeConfigResponse> getGlobalConfig() {
        try {
            BatchSizeConfigResponse config = configService.getConfiguration(null);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error getting global config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Updates batch size configuration for a specific model.
     *
     * @param modelId The model identifier
     * @param request The configuration update request
     * @return Updated configuration
     */
    @PutMapping("/models/{modelId}")
    public ResponseEntity<?> updateModelConfig(
            @PathVariable String modelId,
            @RequestBody BatchSizeConfigRequest request
    ) {
        try {
            BatchSizeConfigResponse config = configService.updateConfiguration(modelId, request);
            log.info("Updated batch config for model {}: optimal={}, max={}",
                    modelId, config.currentOptimalBatchSize(), config.currentMaxBatchSize());
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid config update for model {}: {}", modelId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Cannot update config: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating config for model: {}", modelId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Updates global batch size configuration.
     *
     * @param request The configuration update request
     * @return Updated configuration
     */
    @PutMapping("/global")
    public ResponseEntity<?> updateGlobalConfig(@RequestBody BatchSizeConfigRequest request) {
        try {
            BatchSizeConfigResponse config = configService.updateConfiguration(null, request);
            log.info("Updated global batch config: optimal={}, max={}",
                    config.currentOptimalBatchSize(), config.currentMaxBatchSize());
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating global config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Runs a batch size benchmark test.
     *
     * @param request The benchmark test request
     * @return Benchmark results
     */
    @PostMapping("/test")
    public ResponseEntity<?> runBatchSizeTest(@RequestBody BatchSizeTestRequest request) {
        try {
            log.info("Starting batch size test: model={}, batchSizes={}, iterations={}",
                    request.modelId(), request.batchSizesToTest(), request.iterations());

            BatchSizeTestResponse response = configService.runBatchSizeTest(request);

            log.info("Batch size test completed: model={}, recommended={}, maxSafe={}, duration={}ms",
                    response.modelId(), response.recommendedBatchSize(),
                    response.maxSafeBatchSize(), response.testDurationMs());

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid batch test request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error running batch size test", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Test failed: " + e.getMessage()));
        }
    }

    /**
     * Resets batch size configuration to defaults for a model.
     *
     * @param modelId The model identifier
     * @return Reset configuration
     */
    @PostMapping("/models/{modelId}/reset")
    public ResponseEntity<?> resetModelConfig(@PathVariable String modelId) {
        try {
            BatchSizeConfigResponse config = configService.resetConfiguration(modelId);
            log.info("Reset batch config for model {}", modelId);
            return ResponseEntity.ok(config);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error resetting config for model: {}", modelId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Reset failed: " + e.getMessage()));
        }
    }

    /**
     * Resets global batch size configuration to defaults.
     *
     * @return Reset configuration
     */
    @PostMapping("/global/reset")
    public ResponseEntity<?> resetGlobalConfig() {
        try {
            BatchSizeConfigResponse config = configService.resetConfiguration(null);
            log.info("Reset global batch config");
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error resetting global config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Reset failed: " + e.getMessage()));
        }
    }

    // ========== TIMEOUT CONFIGURATION ENDPOINTS ==========

    /**
     * Gets current timeout configuration.
     *
     * <p>Timeouts control how long the system waits for various subprocess operations.
     * A value of 0 means no timeout (wait indefinitely).
     *
     * @return Current timeout configuration
     */
    @GetMapping("/timeouts")
    public ResponseEntity<Map<String, Object>> getTimeoutConfig() {
        try {
            Map<String, Object> config = configService.getTimeoutConfiguration();
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error getting timeout config", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Updates timeout configuration.
     *
     * <p>All timeout values are optional. Only provided values will be updated.
     * Set a timeout to 0 to disable it (wait indefinitely).
     *
     * @param request Map containing timeout values to update:
     *                - modelLoadTimeoutSeconds: Timeout for loading models (0 = no timeout)
     *                - requestTimeoutMs: Timeout for subprocess requests (0 = no timeout)
     *                - heartbeatTimeoutMs: Timeout for heartbeat detection (0 = no timeout)
     *                - embedTimeoutSeconds: Timeout for single embed (0 = no timeout)
     *                - embedBatchTimeoutSeconds: Timeout for batch embed (0 = no timeout)
     * @return Updated timeout configuration
     */
    @PutMapping("/timeouts")
    public ResponseEntity<?> updateTimeoutConfig(@RequestBody Map<String, Object> request) {
        try {
            Long modelLoadTimeoutSeconds = getAsLong(request, "modelLoadTimeoutSeconds");
            Long requestTimeoutMs = getAsLong(request, "requestTimeoutMs");
            Long heartbeatTimeoutMs = getAsLong(request, "heartbeatTimeoutMs");
            Long embedTimeoutSeconds = getAsLong(request, "embedTimeoutSeconds");
            Long embedBatchTimeoutSeconds = getAsLong(request, "embedBatchTimeoutSeconds");

            Map<String, Object> config = configService.updateTimeoutConfiguration(
                    modelLoadTimeoutSeconds,
                    requestTimeoutMs,
                    heartbeatTimeoutMs,
                    embedTimeoutSeconds,
                    embedBatchTimeoutSeconds);

            log.info("Updated timeout config: modelLoad={}s, request={}ms, heartbeat={}ms, embed={}s, embedBatch={}s",
                    modelLoadTimeoutSeconds, requestTimeoutMs, heartbeatTimeoutMs,
                    embedTimeoutSeconds, embedBatchTimeoutSeconds);

            return ResponseEntity.ok(config);
        } catch (IllegalStateException e) {
            log.warn("Cannot update timeout config: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating timeout config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Update failed: " + e.getMessage()));
        }
    }

    /**
     * Resets timeout configuration to defaults (no timeouts).
     *
     * @return Reset timeout configuration
     */
    @PostMapping("/timeouts/reset")
    public ResponseEntity<?> resetTimeoutConfig() {
        try {
            Map<String, Object> config = configService.resetTimeoutConfiguration();
            log.info("Reset timeout config to defaults (no timeouts)");
            return ResponseEntity.ok(config);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error resetting timeout config", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Reset failed: " + e.getMessage()));
        }
    }

    /**
     * Helper to safely extract Long values from request map.
     */
    private Long getAsLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Quick test endpoint to verify the controller is working.
     *
     * @return Status information
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        try {
            List<EmbeddingModelInfo> models = configService.getAvailableModels();
            long loadedCount = models.stream().filter(EmbeddingModelInfo::isLoaded).count();

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "totalModels", models.size(),
                    "loadedModels", loadedCount,
                    "availableModels", models.stream()
                            .filter(m -> !m.isLoaded())
                            .count()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }
}
