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

import ai.kompile.app.services.EmbeddingStatusBroadcaster;
import ai.kompile.core.util.FieldNames;
import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * REST controller for managing staging service configurations
 * and interacting with remote staging services.
 */
@RestController
@RequestMapping("/api/staging-config")
@CrossOrigin(origins = "*")
public class StagingConfigController {

    private static final Logger log = LoggerFactory.getLogger(StagingConfigController.class);

    private final StagingServiceConfigService configService;
    private final StagingClientService clientService;
    private final AnseriniEmbeddingModelImpl embeddingModel;
    private final EmbeddingStatusBroadcaster statusBroadcaster;
    private final Executor taskExecutor;

    // Track active model loading tasks
    private final Map<String, ModelLoadTask> activeLoadTasks = new ConcurrentHashMap<>();

    /**
     * Represents an in-progress model loading task.
     */
    public record ModelLoadTask(
            String taskId,
            String modelId,
            long startTime,
            String status,  // "downloading", "loading", "completed", "failed"
            String message
    ) {}

    @Autowired
    public StagingConfigController(StagingServiceConfigService configService,
                                   StagingClientService clientService,
                                   @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel,
                                   @Autowired(required = false) EmbeddingStatusBroadcaster statusBroadcaster,
                                   @Qualifier("taskExecutor") @Autowired(required = false) Executor taskExecutor) {
        this.configService = configService;
        this.clientService = clientService;
        this.embeddingModel = embeddingModel;
        this.statusBroadcaster = statusBroadcaster;
        this.taskExecutor = taskExecutor;
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

    // ==================== Connection Testing & Retry ====================

    /**
     * Get the current connection status.
     * Useful for UI to check if a connection retry is needed.
     */
    @GetMapping("/connection-status")
    public ResponseEntity<Map<String, Object>> getConnectionStatus() {
        StagingClientService.ConnectionStatus status = clientService.getConnectionStatus();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("connected", status.connected());
        response.put("attempted", status.attempted());
        response.put("endpointUrl", status.endpointUrl());
        response.put("lastError", status.lastError());
        response.put("lastAttemptTimeMs", status.lastAttemptTimeMs());
        response.put("consecutiveFailures", status.consecutiveFailures());
        response.put("canRetry", status.canRetry());
        response.put("timeSinceLastAttemptMs", status.timeSinceLastAttemptMs());

        // Include active config info if available
        configService.getActiveConfig().ifPresent(config -> {
            response.put("activeConfigId", config.getId());
            response.put("activeConfigName", config.getName());
        });

        return ResponseEntity.ok(response);
    }

    /**
     * Retry connection to the active staging service.
     * This is the main endpoint for users to retry a failed connection.
     */
    @PostMapping("/retry-connection")
    public ResponseEntity<Map<String, Object>> retryConnection() {
        log.info("Retry connection requested");

        StagingClientService.ConnectionTestResult result = clientService.retryConnection();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("modelCount", result.modelCount());
        response.put("version", result.version() != null ? result.version() : "");

        // If successful, also try to get active models
        if (result.success()) {
            clientService.getActiveModels().ifPresent(active -> {
                response.put("activeModels", active);
            });
        }

        // Include updated connection status
        StagingClientService.ConnectionStatus status = clientService.getConnectionStatus();
        response.put("connectionStatus", Map.of(
                "connected", status.connected(),
                "consecutiveFailures", status.consecutiveFailures(),
                "canRetry", status.canRetry()
        ));

        if (result.success()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }

    /**
     * Retry connection and discover models from the staging service.
     * This performs a full discovery: test connection, get registry, get active models,
     * and automatically triggers model reload if an embedding model is configured.
     */
    @PostMapping("/retry-discover")
    public ResponseEntity<Map<String, Object>> retryDiscover() {
        log.info("Retry discover requested");

        Map<String, Object> response = new LinkedHashMap<>();

        // Step 1: Test/retry connection
        StagingClientService.ConnectionTestResult connectionResult = clientService.retryConnection();
        response.put("connectionSuccess", connectionResult.success());
        response.put("connectionMessage", connectionResult.message());

        if (!connectionResult.success()) {
            response.put("success", false);
            response.put("error", "Connection failed: " + connectionResult.message());
            response.put("canRetry", true);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        // Step 2: Get registry
        Optional<JsonNode> registry = clientService.getRegistry();
        if (registry.isPresent()) {
            response.put("registryAvailable", true);
            response.put("modelCount", connectionResult.modelCount());
        } else {
            response.put("registryAvailable", false);
        }

        // Step 3: Get active models from remote staging
        Optional<Map<String, String>> activeModels = clientService.getActiveModels();
        if (activeModels.isPresent()) {
            response.put("activeModels", activeModels.get());
            response.put("hasActiveModels", !activeModels.get().isEmpty());
        } else {
            response.put("activeModels", Map.of());
            response.put("hasActiveModels", false);
        }

        // Step 4: Trigger model reload if embedding model is available
        if (embeddingModel != null) {
            try {
                // Refresh the encoder factory's registry cache
                ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();

                String currentModelId = embeddingModel.getActiveModelId();
                log.info("Triggering model reload after successful connection discovery. Current model: {}", currentModelId);

                // If we have active models from staging, try to load the dense encoder
                String denseEncoderModel = null;
                if (activeModels.isPresent()) {
                    Map<String, String> models = activeModels.get();
                    denseEncoderModel = models.get("dense_encoder");
                    if (denseEncoderModel == null) {
                        denseEncoderModel = models.get("encoder");
                    }
                }

                boolean reloadSuccess;
                if (denseEncoderModel != null && clientService.isModelDownloaded(denseEncoderModel)) {
                    // Load the specific model from staging
                    log.info("Loading dense encoder model from staging: {}", denseEncoderModel);
                    reloadSuccess = embeddingModel.reloadModel(denseEncoderModel);
                    response.put("loadedModel", denseEncoderModel);
                } else if (currentModelId != null) {
                    // Reload the current model
                    reloadSuccess = embeddingModel.reloadModel(currentModelId);
                    response.put("loadedModel", currentModelId);
                } else {
                    // Just reinitialize
                    reloadSuccess = embeddingModel.reloadModel();
                    response.put("loadedModel", embeddingModel.getActiveModelId());
                }

                response.put("modelReloadSuccess", reloadSuccess);
                if (reloadSuccess) {
                    response.put("embeddingDimensions", embeddingModel.dimensions());
                    response.put("modelSource", embeddingModel.getModelSource().name());
                    log.info("Model reload successful after connection discovery");
                } else {
                    response.put("modelReloadError", "Model reload returned false - check logs for details");
                    log.warn("Model reload returned false after connection discovery");
                }

                // Broadcast status update via WebSocket
                if (statusBroadcaster != null) {
                    statusBroadcaster.broadcastNow();
                }
            } catch (Exception e) {
                log.error("Failed to reload model after connection discovery: {}", e.getMessage(), e);
                response.put("modelReloadSuccess", false);
                response.put("modelReloadError", e.getMessage());
            }
        } else {
            response.put("modelReloadSuccess", false);
            response.put("modelReloadError", "Embedding model not configured");
        }

        // Always broadcast status update after discovery attempt
        if (statusBroadcaster != null) {
            statusBroadcaster.broadcastNow();
        }

        response.put("success", true);
        response.put("message", "Successfully discovered " + connectionResult.modelCount() + " models");
        response.put("canRetry", false);

        return ResponseEntity.ok(response);
    }

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

        Map<String, Object> response = new HashMap<>();
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
     * Returns 200 with an empty registry when no staging service is configured or reachable,
     * so clients do not log spurious 503 errors on pages that always load this endpoint.
     */
    @GetMapping("/remote/registry")
    public ResponseEntity<?> getRemoteRegistry() {
        return clientService.getRegistry()
                .map(registry -> ResponseEntity.ok((Object) registry))
                .orElse(ResponseEntity.ok(Map.of("models", Collections.emptyMap(), "available", false)));
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

    /**
     * Check if a model exists on the remote staging service.
     * Returns versioning suggestions if the model exists.
     */
    @GetMapping("/remote/model/{modelId}/exists")
    public ResponseEntity<?> checkRemoteModelExists(@PathVariable String modelId) {
        return clientService.checkModelExists(modelId)
                .map(result -> ResponseEntity.ok((Object) result))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    /**
     * Delete a model from the remote staging service's registry.
     *
     * @param modelId The model ID to delete
     * @param deleteFiles Whether to also delete model files from disk (default: true)
     * @return Deletion result including success status and details
     */
    @DeleteMapping("/remote/model/{modelId}")
    public ResponseEntity<?> deleteRemoteModel(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "true") boolean deleteFiles) {
        return clientService.deleteModel(modelId, deleteFiles)
                .map(result -> {
                    // Check if the result indicates success
                    boolean success = result.path("success").asBoolean(false);
                    if (success) {
                        return ResponseEntity.ok((Object) result);
                    } else {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) result);
                    }
                })
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    /**
     * Replace a model on the remote staging service.
     *
     * <p>This endpoint supports development/snapshot workflows where models are frequently
     * replaced, while also encouraging versioning best practices.</p>
     *
     * @param modelId The model ID to replace
     * @param request The replacement request with model details and options
     * @return Replacement result including warnings if applicable
     */
    @PutMapping("/remote/model/{modelId}/replace")
    public ResponseEntity<?> replaceRemoteModel(
            @PathVariable String modelId,
            @RequestBody ReplaceModelRequest request) {
        Map<String, Object> replaceRequest = new HashMap<>();
        replaceRequest.put("type", request.type());
        replaceRequest.put("path", request.path());
        replaceRequest.put("modelFile", request.modelFile());
        replaceRequest.put("vocabFile", request.vocabFile());
        replaceRequest.put("checksum", request.checksum());
        replaceRequest.put("force", request.force() != null ? request.force() : false);
        replaceRequest.put("deleteOldFiles", request.deleteOldFiles() != null ? request.deleteOldFiles() : true);
        replaceRequest.put("suggestedVersionedId", request.suggestedVersionedId());
        replaceRequest.put("embeddingDim", request.embeddingDim());
        replaceRequest.put("hiddenSize", request.hiddenSize());
        replaceRequest.put("numLayers", request.numLayers());
        replaceRequest.put("maxSequenceLength", request.maxSequenceLength());
        replaceRequest.put("description", request.description());
        replaceRequest.put("framework", request.framework());
        replaceRequest.put("sourceOrigin", request.sourceOrigin());
        replaceRequest.put("sourceRepository", request.sourceRepository());

        return clientService.replaceModel(modelId, replaceRequest)
                .map(result -> {
                    // Check if the result indicates success
                    boolean success = result.path("success").asBoolean(false);
                    if (success) {
                        return ResponseEntity.ok((Object) result);
                    } else {
                        // Blocked due to existing model and force=false
                        return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) result);
                    }
                })
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "No active staging service or connection failed")));
    }

    // ==================== Active Model Management ====================

    /**
     * Get active models from the remote staging service (one per type).
     * Returns a map of type -> modelId for currently active models.
     * Returns 200 with empty active map when no staging service is configured or reachable,
     * so clients do not log spurious 503 errors on pages that always load this endpoint.
     */
    @GetMapping("/remote/active")
    public ResponseEntity<?> getActiveModels() {
        return clientService.getActiveModels()
                .map(active -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("active", active);
                    response.put("types", List.of("encoder", "sparse_encoder", "cross_encoder"));
                    return ResponseEntity.ok((Object) response);
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("active", Collections.emptyMap());
                    response.put("types", List.of("encoder", "sparse_encoder", "cross_encoder"));
                    response.put("available", false);
                    return ResponseEntity.ok(response);
                });
    }

    /**
     * Activate a model (deactivates other models of the same type).
     */
    @PostMapping("/remote/models/{modelId}/activate")
    public ResponseEntity<Map<String, Object>> activateModel(@PathVariable String modelId) {
        boolean success = clientService.activateModel(modelId);

        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Model activated successfully",
                    FieldNames.MODEL_ID, modelId
            ));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "error", "Failed to activate model or no active staging service"
            ));
        }
    }

    // ==================== SDZ Upload ====================

    /**
     * Upload an .sdz model file to the remote staging service.
     * This proxies the upload to the active staging service.
     *
     * @param modelFile The .sdz model file
     * @param vocabFile Optional vocabulary file
     * @param modelId Model identifier
     * @param modelType Model type (dense_encoder, sparse_encoder, cross_encoder)
     * @param version Optional version
     * @param embeddingDim Optional embedding dimension
     * @param maxSequenceLength Optional max sequence length
     * @param description Optional description
     * @param overwrite Whether to overwrite existing model
     * @return Upload result
     */
    @PostMapping("/remote/upload-sdz")
    public ResponseEntity<Object> uploadSdzToRemote(
            @RequestParam("modelFile") MultipartFile modelFile,
            @RequestParam(value = "vocabFile", required = false) MultipartFile vocabFile,
            @RequestParam(FieldNames.MODEL_ID) String modelId,
            @RequestParam(value = "modelType", defaultValue = "dense_encoder") String modelType,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "embeddingDim", required = false) Integer embeddingDim,
            @RequestParam(value = "maxSequenceLength", required = false) Integer maxSequenceLength,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {

        // Validate model file
        if (modelFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "No model file provided"
            ));
        }

        String originalFilename = modelFile.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".sdz")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Model file must be an .sdz file"
            ));
        }

        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "modelId is required"
            ));
        }

        try {
            // Read file bytes
            byte[] modelFileBytes = modelFile.getBytes();
            byte[] vocabFileBytes = null;
            String vocabFileName = null;

            if (vocabFile != null && !vocabFile.isEmpty()) {
                vocabFileBytes = vocabFile.getBytes();
                vocabFileName = vocabFile.getOriginalFilename();
            }

            // Forward to staging service
            Optional<JsonNode> result = clientService.uploadSdzModel(
                    modelFileBytes,
                    originalFilename,
                    vocabFileBytes,
                    vocabFileName,
                    modelId,
                    modelType,
                    version,
                    embeddingDim,
                    maxSequenceLength,
                    description,
                    overwrite
            );

            return result
                    .map(json -> {
                        boolean success = json.path("success").asBoolean(false);
                        if (success) {
                            return ResponseEntity.ok((Object) json);
                        } else {
                            return ResponseEntity.status(HttpStatus.CONFLICT).body((Object) json);
                        }
                    })
                    .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("success", false, "error", "No active staging service or connection failed")));

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to read uploaded file: " + e.getMessage()
            ));
        }
    }

    // ==================== Model Download & Load Operations ====================

    /**
     * Download a model from the remote staging service and load it into the application.
     * This is the main entry point for loading remote models.
     *
     * <p><b>ASYNC OPERATION:</b> This endpoint returns immediately after downloading the model.
     * The model loading happens asynchronously in the background. Progress updates are
     * broadcast via WebSocket to /topic/model/status.</p>
     *
     * @param modelId The model ID to download and load
     * @return Result with download status and task ID for tracking loading progress
     */
    @PostMapping("/remote/download-and-load/{modelId}")
    public ResponseEntity<Map<String, Object>> downloadAndLoadModel(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();
        String taskId = UUID.randomUUID().toString();

        log.info("Download and load request for model: {} (taskId: {})", sanitizeForLog(modelId), taskId);

        // Step 1: Download the model from remote staging (synchronous - fast)
        StagingClientService.DownloadModelResult downloadResult = clientService.downloadModel(modelId);

        if (!downloadResult.success()) {
            response.put("success", false);
            response.put("phase", "download");
            response.put("error", downloadResult.message());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }

        response.put("downloadSuccess", true);
        response.put("localPath", downloadResult.localPath() != null ? downloadResult.localPath().toString() : null);
        response.put("modelType", downloadResult.modelType());
        response.put("embeddingDim", downloadResult.embeddingDim());
        response.put("hasVocab", downloadResult.hasVocab());

        // Step 2: Check if model loading is possible
        if (embeddingModel == null) {
            response.put("success", true);
            response.put("loadSuccess", false);
            response.put("loadMessage", "Embedding model not configured - model downloaded but not loaded");
            log.warn("Model {} downloaded but embedding model not configured for loading", modelId);
            return ResponseEntity.ok(response);
        }

        // Check if this is a dense encoder (loadable as embedding model)
        String modelType = downloadResult.modelType();
        if (modelType != null && !modelType.equals("dense_encoder") && !modelType.equals("encoder")) {
            // Not a dense encoder - download succeeded but we can't load it as embedding model
            response.put("success", true);
            response.put("loadSuccess", false);
            response.put("loadMessage", "Model type '" + modelType + "' is not a dense encoder - downloaded but not loaded as embedding model");
            log.info("Model {} downloaded (type: {}) - not loaded as embedding model", modelId, modelType);
            return ResponseEntity.ok(response);
        }

        // Step 3: Start async model loading
        if (taskExecutor == null) {
            // Fallback to synchronous loading if no executor available
            log.warn("No task executor available, falling back to synchronous model loading");
            return loadModelSynchronously(modelId, response);
        }

        // Record the loading task
        ModelLoadTask loadTask = new ModelLoadTask(taskId, modelId, System.currentTimeMillis(), "loading", "Starting model load...");
        activeLoadTasks.put(taskId, loadTask);

        // Start async model loading
        String previousModel = embeddingModel.getActiveModelId();
        taskExecutor.execute(() -> loadModelAsync(taskId, modelId, previousModel));

        // Return immediately with loading status
        response.put("success", true);
        response.put("loading", true);
        response.put(FieldNames.TASK_ID, taskId);
        response.put("previousModel", previousModel);
        response.put("message", "Model download complete. Loading in background - check /topic/model/status for progress.");
        log.info("Model {} downloaded, async loading started (taskId: {})", modelId, taskId);

        // Trigger immediate WebSocket broadcast so UI gets the loading status
        if (statusBroadcaster != null) {
            statusBroadcaster.forceNextBroadcast();
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Async method to load the model in the background.
     * Progress is broadcast via WebSocket through EmbeddingStatusBroadcaster.
     */
    private void loadModelAsync(String taskId, String modelId, String previousModel) {
        try {
            log.info("Starting async model load for {} (taskId: {})", modelId, taskId);

            // Refresh registry cache first
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();

            // Load the model - this triggers subprocess events that are broadcast via WebSocket
            boolean loadSuccess = embeddingModel.reloadModel(modelId);

            if (loadSuccess) {
                log.info("Async model load completed successfully for {} (taskId: {})", modelId, taskId);
                activeLoadTasks.put(taskId, new ModelLoadTask(taskId, modelId, System.currentTimeMillis(), "completed",
                        "Model loaded successfully. Dimensions: " + embeddingModel.dimensions()));
            } else {
                log.warn("Async model load failed for {} (taskId: {})", modelId, taskId);
                activeLoadTasks.put(taskId, new ModelLoadTask(taskId, modelId, System.currentTimeMillis(), "failed",
                        "Failed to load model into embedding engine"));
            }
        } catch (Exception e) {
            log.error("Error in async model load for {} (taskId: {}): {}", modelId, taskId, e.getMessage(), e);
            activeLoadTasks.put(taskId, new ModelLoadTask(taskId, modelId, System.currentTimeMillis(), "failed",
                    "Error loading model: " + e.getMessage()));
        } finally {
            // Force a status broadcast after loading completes
            if (statusBroadcaster != null) {
                statusBroadcaster.broadcastNow();
            }
            // Clean up old tasks after 5 minutes
            cleanupOldTasks();
        }
    }

    /**
     * Fallback synchronous model loading when no executor is available.
     */
    private ResponseEntity<Map<String, Object>> loadModelSynchronously(String modelId, Map<String, Object> response) {
        try {
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            String previousModel = embeddingModel.getActiveModelId();
            boolean loadSuccess = embeddingModel.reloadModel(modelId);

            if (loadSuccess) {
                response.put("success", true);
                response.put("loadSuccess", true);
                response.put("previousModel", previousModel);
                response.put("currentModel", embeddingModel.getActiveModelId());
                response.put("dimensions", embeddingModel.dimensions());
                response.put("message", "Model downloaded and loaded successfully");
                log.info("Model {} downloaded and loaded successfully (sync)", modelId);
                return ResponseEntity.ok(response);
            } else {
                response.put("success", true);
                response.put("loadSuccess", false);
                response.put("loadError", "Failed to load model into embedding engine");
                log.warn("Model {} downloaded but failed to load (sync)", modelId);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Failed to load model {} into embedding engine (sync): {}", modelId, e.getMessage(), e);
            response.put("success", true);
            response.put("loadSuccess", false);
            response.put("loadError", "Error loading model: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Get the status of a model loading task.
     */
    @GetMapping("/remote/load-status/{taskId}")
    public ResponseEntity<Map<String, Object>> getLoadStatus(@PathVariable String taskId) {
        ModelLoadTask task = activeLoadTasks.get(taskId);
        Map<String, Object> response = new LinkedHashMap<>();

        if (task == null) {
            response.put("found", false);
            response.put("message", "Task not found or expired");
            return ResponseEntity.ok(response);
        }

        response.put("found", true);
        response.put(FieldNames.TASK_ID, task.taskId());
        response.put(FieldNames.MODEL_ID, task.modelId());
        response.put("status", task.status());
        response.put("message", task.message());
        response.put("elapsedMs", System.currentTimeMillis() - task.startTime());

        // Add current model info if available
        if (embeddingModel != null && "completed".equals(task.status())) {
            response.put("currentModel", embeddingModel.getActiveModelId());
            response.put("dimensions", embeddingModel.dimensions());
            response.put("initialized", embeddingModel.isInitialized());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Clean up tasks older than 5 minutes.
     */
    private void cleanupOldTasks() {
        long cutoff = System.currentTimeMillis() - (5 * 60 * 1000);
        activeLoadTasks.entrySet().removeIf(entry ->
                entry.getValue().startTime() < cutoff &&
                        ("completed".equals(entry.getValue().status()) || "failed".equals(entry.getValue().status())));
    }

    /**
     * Download a model from the remote staging service without loading it.
     * Use this to pre-download models for later use.
     *
     * @param modelId The model ID to download
     * @return Download result
     */
    @PostMapping("/remote/download/{modelId}")
    public ResponseEntity<Map<String, Object>> downloadModel(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();

        log.info("Download request for model: {}", sanitizeForLog(modelId));

        StagingClientService.DownloadModelResult downloadResult = clientService.downloadModel(modelId);

        response.put("success", downloadResult.success());
        response.put("message", downloadResult.message());
        response.put(FieldNames.MODEL_ID, downloadResult.modelId());
        response.put("localPath", downloadResult.localPath() != null ? downloadResult.localPath().toString() : null);
        response.put("modelType", downloadResult.modelType());
        response.put("embeddingDim", downloadResult.embeddingDim());
        response.put("hasVocab", downloadResult.hasVocab());

        if (downloadResult.success()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Check if a model is already downloaded locally.
     *
     * @param modelId The model ID to check
     * @return Status of local model
     */
    @GetMapping("/remote/downloaded/{modelId}")
    public ResponseEntity<Map<String, Object>> checkModelDownloaded(@PathVariable String modelId) {
        boolean downloaded = clientService.isModelDownloaded(modelId);
        return ResponseEntity.ok(Map.of(
                FieldNames.MODEL_ID, modelId,
                "downloaded", downloaded
        ));
    }

    // ==================== Model Status Broadcasting ====================

    /**
     * Subscribe to model status broadcasting.
     * This enables the WebSocket broadcaster to send status updates.
     */
    @PostMapping("/broadcast/subscribe")
    public ResponseEntity<Map<String, Object>> subscribeToModelStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (statusBroadcaster == null) {
            response.put("status", "unavailable");
            response.put("message", "Model status broadcaster is not available");
            return ResponseEntity.ok(response);
        }

        statusBroadcaster.enableBroadcasting();

        response.put("status", "success");
        response.put("broadcasting", statusBroadcaster.isBroadcasting());
        response.put("subscriberCount", statusBroadcaster.getSubscriberCount());
        response.put("topic", EmbeddingStatusBroadcaster.TOPIC_COMBINED_STATUS);
        response.put("message", "Subscribed to model status broadcast");

        log.debug("Model status broadcast subscribed, subscribers: {}", statusBroadcaster.getSubscriberCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Unsubscribe from model status broadcasting.
     * This may disable the WebSocket broadcaster if no more subscribers.
     */
    @PostMapping("/broadcast/unsubscribe")
    public ResponseEntity<Map<String, Object>> unsubscribeFromModelStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (statusBroadcaster == null) {
            response.put("status", "unavailable");
            response.put("message", "Model status broadcaster is not available");
            return ResponseEntity.ok(response);
        }

        statusBroadcaster.disableBroadcasting();

        response.put("status", "success");
        response.put("broadcasting", statusBroadcaster.isBroadcasting());
        response.put("subscriberCount", statusBroadcaster.getSubscriberCount());
        response.put("message", "Unsubscribed from model status broadcast");

        log.debug("Model status broadcast unsubscribed, subscribers: {}", statusBroadcaster.getSubscriberCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Get the current model status broadcast status.
     */
    @GetMapping("/broadcast/status")
    public ResponseEntity<Map<String, Object>> getModelBroadcastStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (statusBroadcaster == null) {
            response.put("status", "unavailable");
            response.put("message", "Model status broadcaster is not available");
            return ResponseEntity.ok(response);
        }

        response.put("status", "success");
        response.put("broadcasting", statusBroadcaster.isBroadcasting());
        response.put("subscriberCount", statusBroadcaster.getSubscriberCount());
        response.put("topic", EmbeddingStatusBroadcaster.TOPIC_COMBINED_STATUS);

        return ResponseEntity.ok(response);
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

    /**
     * DTO for model replacement requests.
     */
    public record ReplaceModelRequest(
            String type,
            String path,
            String modelFile,
            String vocabFile,
            String checksum,
            Boolean force,
            Boolean deleteOldFiles,
            String suggestedVersionedId,
            Integer embeddingDim,
            Integer hiddenSize,
            Integer numLayers,
            Integer maxSequenceLength,
            String description,
            String framework,
            String sourceOrigin,
            String sourceRepository
    ) {}

    // ==================== Registry Normalization ====================

    /**
     * Normalize the registry to ensure only ONE model per type is active.
     * Use this to fix registries where multiple models of the same type are marked active.
     */
    @PostMapping("/remote/normalize")
    public ResponseEntity<Map<String, Object>> normalizeRegistry() {
        return clientService.normalizeRegistry()
                .map(changes -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("changes", changes);
                    response.put("message", changes.isEmpty()
                            ? "Registry already normalized"
                            : "Normalized " + changes.size() + " model type(s)");
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "success", false,
                        "error", "No active staging service or normalization failed"
                )));
    }

    // ==================== Local Registry & Optimization Operations ====================

    /**
     * Get the local model registry.
     * Returns all models downloaded/installed locally.
     */
    @GetMapping("/registry")
    public ResponseEntity<Map<String, Object>> getLocalRegistry() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<Map<String, Object>> models = new ArrayList<>();

            // Get models from AnseriniEncoderFactory registry
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var registryModels = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getAvailableModels();

            if (registryModels != null) {
                for (var entry : registryModels.entrySet()) {
                    String modelId = entry.getKey();
                    var modelInfo = entry.getValue();

                    Map<String, Object> modelMap = new LinkedHashMap<>();
                    modelMap.put(FieldNames.MODEL_ID, modelId);
                    modelMap.put("type", modelInfo.get("type"));
                    modelMap.put("status", modelInfo.get("status"));
                    modelMap.put("path", modelInfo.get("path"));
                    modelMap.put("modelFile", modelInfo.get("modelFile"));
                    modelMap.put("vocabFile", modelInfo.get("vocabFile"));

                    // Add metadata if available
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    if (modelInfo.containsKey("embeddingDim")) {
                        metadata.put("embeddingDim", modelInfo.get("embeddingDim"));
                    }
                    if (modelInfo.containsKey("optimized")) {
                        metadata.put("optimized", modelInfo.get("optimized"));
                    }
                    if (modelInfo.containsKey("optimizedAt")) {
                        metadata.put("optimizedAt", modelInfo.get("optimizedAt"));
                    }
                    if (modelInfo.containsKey("optimizationTimeMs")) {
                        metadata.put("optimizationTimeMs", modelInfo.get("optimizationTimeMs"));
                    }
                    if (modelInfo.containsKey("unoptimizedBackupFile")) {
                        metadata.put("unoptimizedBackupFile", modelInfo.get("unoptimizedBackupFile"));
                    }
                    modelMap.put("metadata", metadata);

                    models.add(modelMap);
                }
            }

            response.put("models", models);
            response.put("version", "1.0");
            response.put("lastUpdated", Instant.now().toString());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get local registry: {}", e.getMessage(), e);
            response.put("error", e.getMessage());
            response.put("models", Collections.emptyList());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get models by type from the local registry.
     */
    @GetMapping("/registry/{type}")
    public ResponseEntity<List<Map<String, Object>>> getLocalModelsByType(@PathVariable String type) {
        try {
            List<Map<String, Object>> models = new ArrayList<>();

            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var registryModels = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getAvailableModels();

            if (registryModels != null) {
                for (var entry : registryModels.entrySet()) {
                    String modelId = entry.getKey();
                    var modelInfo = entry.getValue();

                    String modelType = (String) modelInfo.get("type");
                    if (type.equalsIgnoreCase(modelType) ||
                            ("encoder".equalsIgnoreCase(type) && "dense_encoder".equalsIgnoreCase(modelType))) {

                        Map<String, Object> modelMap = new LinkedHashMap<>();
                        modelMap.put(FieldNames.MODEL_ID, modelId);
                        modelMap.put("type", modelType);
                        modelMap.put("status", modelInfo.get("status"));
                        modelMap.put("path", modelInfo.get("path"));
                        models.add(modelMap);
                    }
                }
            }

            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Failed to get models by type: {}", type, e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Get optimization status for a specific model.
     */
    @GetMapping("/registry/model/{modelId}/optimization")
    public ResponseEntity<Map<String, Object>> getOptimizationStatus(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(FieldNames.MODEL_ID, modelId);

        try {
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var modelInfo = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getModelInfoMap(modelId);

            if (modelInfo == null) {
                response.put("error", "Model not found: " + modelId);
                return ResponseEntity.notFound().build();
            }

            boolean optimized = modelInfo.containsKey("optimized") && Boolean.TRUE.equals(modelInfo.get("optimized"));
            response.put("optimized", optimized);

            if (modelInfo.containsKey("optimizedAt")) {
                response.put("optimizedAt", modelInfo.get("optimizedAt"));
            }
            if (modelInfo.containsKey("optimizationTimeMs")) {
                response.put("optimizationTimeMs", modelInfo.get("optimizationTimeMs"));
            }

            String backupFile = (String) modelInfo.get("unoptimizedBackupFile");
            boolean hasBackup = backupFile != null && !backupFile.isEmpty() &&
                    Files.exists(Paths.get(backupFile));
            response.put("hasBackup", hasBackup);
            if (hasBackup) {
                response.put("backupFile", backupFile);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get optimization status for {}: {}", modelId, e.getMessage(), e);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Check if a model can be optimized.
     */
    @GetMapping("/registry/model/{modelId}/can-optimize")
    public ResponseEntity<Map<String, Object>> canOptimize(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(FieldNames.MODEL_ID, modelId);

        try {
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var modelInfo = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getModelInfoMap(modelId);

            if (modelInfo == null) {
                response.put("canOptimize", false);
                response.put("reason", "Model not found: " + modelId);
                return ResponseEntity.ok(response);
            }

            // Check if already optimized
            boolean optimized = modelInfo.containsKey("optimized") && Boolean.TRUE.equals(modelInfo.get("optimized"));
            if (optimized) {
                response.put("canOptimize", false);
                response.put("reason", "Model is already optimized");
                response.put("hasBackup", modelInfo.containsKey("unoptimizedBackupFile"));
                return ResponseEntity.ok(response);
            }

            // Check if model file exists
            String path = (String) modelInfo.get("path");
            String modelFile = (String) modelInfo.getOrDefault("modelFile", "model.fb");
            if (path != null) {
                Path fullPath = Paths.get(path, modelFile);
                if (!Files.exists(fullPath)) {
                    response.put("canOptimize", false);
                    response.put("reason", "Model file not found: " + fullPath);
                    return ResponseEntity.ok(response);
                }
                response.put("modelFile", fullPath.toString());
            }

            response.put("canOptimize", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to check can-optimize for {}: {}", modelId, e.getMessage(), e);
            response.put("canOptimize", false);
            response.put("reason", "Error: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Optimize a model in-place.
     * Creates a backup of the original and saves the optimized version.
     */
    @PostMapping("/registry/model/{modelId}/optimize")
    public ResponseEntity<Map<String, Object>> optimizeModel(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(FieldNames.MODEL_ID, modelId);

        try {
            log.info("Optimizing model: {}", sanitizeForLog(modelId));

            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var modelInfo = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getModelInfoMap(modelId);

            if (modelInfo == null) {
                response.put("success", false);
                response.put("error", "Model not found: " + modelId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Check if already optimized
            boolean optimized = modelInfo.containsKey("optimized") && Boolean.TRUE.equals(modelInfo.get("optimized"));
            if (optimized) {
                response.put("success", false);
                response.put("error", "Model is already optimized");
                return ResponseEntity.badRequest().body(response);
            }

            // Get model path
            String path = (String) modelInfo.get("path");
            String modelFile = (String) modelInfo.getOrDefault("modelFile", "model.fb");

            if (path == null) {
                response.put("success", false);
                response.put("error", "Model path not found");
                return ResponseEntity.badRequest().body(response);
            }

            Path modelPath = Paths.get(path, modelFile);
            if (!Files.exists(modelPath)) {
                response.put("success", false);
                response.put("error", "Model file not found: " + modelPath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Create backup
            Path backupPath = Paths.get(path, modelFile + ".unoptimized");
            Files.copy(modelPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Created backup at: {}", backupPath);

            // Load, optimize, and save using SDZSerializer
            long startTime = System.currentTimeMillis();
            org.nd4j.autodiff.samediff.SameDiff sd = org.nd4j.autodiff.samediff.serde.SDZSerializer.load(modelPath.toFile(), true);
            if (sd == null) {
                response.put("success", false);
                response.put("error", "Failed to load model from: " + modelPath);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            // Get model outputs for optimization
            List<String> targetOutputs = sd.outputs();
            if (targetOutputs == null || targetOutputs.isEmpty()) {
                response.put("success", false);
                response.put("error", "Model has no outputs defined");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            // Save optimized model (applies graph optimizations: matmul+add fusion, constant folding, etc.)
            org.nd4j.autodiff.samediff.serde.SDZSerializer.saveOptimized(sd, modelPath.toFile(), false, null, targetOutputs);
            long optimizationTimeMs = System.currentTimeMillis() - startTime;

            log.info("Model {} optimized in {}ms", modelId, optimizationTimeMs);

            // Update registry metadata
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.updateModelOptimizationStatus(
                    modelId, true, backupPath.toString(), optimizationTimeMs);

            response.put("success", true);
            response.put("message", "Model optimized successfully");
            response.put("optimizationTimeMs", optimizationTimeMs);
            response.put("backupFile", backupPath.toString());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to optimize model {}: {}", modelId, e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Restore a model to its unoptimized state.
     */
    @PostMapping("/registry/model/{modelId}/restore")
    public ResponseEntity<Map<String, Object>> restoreUnoptimized(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(FieldNames.MODEL_ID, modelId);

        try {
            log.info("Restoring unoptimized version of model: {}", modelId);

            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
            var modelInfo = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getModelInfoMap(modelId);

            if (modelInfo == null) {
                response.put("success", false);
                response.put("error", "Model not found: " + modelId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            String backupFile = (String) modelInfo.get("unoptimizedBackupFile");
            if (backupFile == null || backupFile.isEmpty()) {
                response.put("success", false);
                response.put("error", "No backup file found for model");
                return ResponseEntity.badRequest().body(response);
            }

            Path backupPath = Paths.get(backupFile);
            if (!Files.exists(backupPath)) {
                response.put("success", false);
                response.put("error", "Backup file does not exist: " + backupFile);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Get model path
            String path = (String) modelInfo.get("path");
            String modelFileName = (String) modelInfo.getOrDefault("modelFile", "model.fb");
            Path modelPath = Paths.get(path, modelFileName);

            // Restore from backup
            Files.copy(backupPath, modelPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Restored model from backup: {}", backupPath);

            // Delete backup
            Files.deleteIfExists(backupPath);
            log.info("Deleted backup file: {}", backupPath);

            // Update registry metadata
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.updateModelOptimizationStatus(
                    modelId, false, null, null);

            response.put("success", true);
            response.put("message", "Model restored to unoptimized state");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to restore model {}: {}", modelId, e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private static String sanitizeForLog(String value) {
        if (value == null) return null;
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
