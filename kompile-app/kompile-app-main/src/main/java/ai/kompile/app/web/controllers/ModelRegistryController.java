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

import ai.kompile.app.config.ModelAutoInitializationService;
import ai.kompile.app.staging.domain.StagingServiceConfig;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.ModelType;
import ai.kompile.ocr.integration.OcrPipelineService;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for model registry operations.
 *
 * <p>Models are accessed via the UI-configured staging service
 * or loaded archives. Configuration is stored in the database.
 */
@RestController
@RequestMapping("/api/models")
@CrossOrigin(origins = "*")
public class ModelRegistryController {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryController.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        restTemplate = new RestTemplate(factory);
    }

    // Optional embedding model for reload capability
    private final AnseriniEmbeddingModelImpl embeddingModel;

    // Optional vector store to update encoder model ID
    private final AnseriniVectorStoreImpl vectorStore;

    // Staging services for UI-configured staging
    private final StagingServiceConfigService stagingConfigService;
    private final StagingClientService stagingClientService;

    // Optional VLM/OCR pipeline service
    private final OcrPipelineService ocrPipelineService;

    // Optional auto-initialization service
    private final ModelAutoInitializationService modelAutoInitService;

    @Autowired
    public ModelRegistryController(
            @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel,
            @Lazy @Autowired(required = false) AnseriniVectorStoreImpl vectorStore,
            @Autowired(required = false) StagingServiceConfigService stagingConfigService,
            @Autowired(required = false) StagingClientService stagingClientService,
            @Lazy @Autowired(required = false) OcrPipelineService ocrPipelineService,
            @Lazy @Autowired(required = false) ModelAutoInitializationService modelAutoInitService) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.stagingConfigService = stagingConfigService;
        this.stagingClientService = stagingClientService;
        this.ocrPipelineService = ocrPipelineService;
        this.modelAutoInitService = modelAutoInitService;
    }

    /**
     * Initialize the vector store's encoder model ID on startup.
     * This ensures the vector store knows which model is loaded.
     */
    @PostConstruct
    public void initializeEncoderModelId() {
        if (embeddingModel != null && vectorStore != null) {
            String activeModelId = embeddingModel.getActiveModelId();
            if (activeModelId != null) {
                updateVectorStoreEncoderModel(activeModelId);
                log.info("Initialized vector store encoder model ID to: {} on startup", activeModelId);
            }
        }
    }

    /**
     * Get the full model registry from the configured source.
     */
    @GetMapping("/registry")
    public ResponseEntity<ModelRegistry> getRegistry() {
        try {
            ModelRegistry registry = buildRegistryFromSource();
            return ResponseEntity.ok(registry);
        } catch (Exception e) {
            log.error("Failed to load registry from source", e);
            return ResponseEntity.ok(ModelRegistry.empty());
        }
    }

    /**
     * Get models by type (encoder, cross_encoder, reranker).
     * Uses the UI-configured staging service.
     */
    @GetMapping("/registry/{type}")
    public ResponseEntity<List<ModelEntry>> getModelsByType(@PathVariable String type) {
        try {
            List<ModelEntry> models = new ArrayList<>();

            // Get models from UI-configured staging service
            if (stagingClientService != null) {
                Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
                if (registryOpt.isPresent()) {
                    JsonNode registry = registryOpt.get();
                    JsonNode modelsNode = registry.get("models");
                    if (modelsNode != null && modelsNode.isObject()) {
                        modelsNode.fields().forEachRemaining(entry -> {
                            JsonNode model = entry.getValue();
                            String modelType = model.has("type") ? model.get("type").asText() : null;
                            // Match type
                            if (type.equalsIgnoreCase(modelType) ||
                                    ("encoder".equalsIgnoreCase(type) && "dense_encoder".equalsIgnoreCase(modelType)) ||
                                    ("encoder".equalsIgnoreCase(type) && "sparse_encoder".equalsIgnoreCase(modelType)) ||
                                    ("reranker".equalsIgnoreCase(type) && "cross_encoder".equalsIgnoreCase(modelType))) {
                                models.add(jsonToModelEntry(entry.getKey(), model));
                            }
                        });
                    }
                }
            }

            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Failed to get models by type: {}", type != null ? type.replaceAll("[\\r\\n]", "_") : "null", e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Get a specific model by ID.
     * Uses the UI-configured staging service.
     */
    @GetMapping("/registry/model/{modelId}")
    public ResponseEntity<ModelEntry> getModel(@PathVariable String modelId) {
        try {
            // Get model from UI-configured staging service
            if (stagingClientService != null) {
                Optional<JsonNode> modelOpt = stagingClientService.getModel(modelId);
                if (modelOpt.isPresent()) {
                    return ResponseEntity.ok(jsonToModelEntry(modelId, modelOpt.get()));
                }
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to get model: {}", modelId != null ? modelId.replaceAll("[\\r\\n]", "_") : "null", e);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get model source status.
     * Uses the UI-configured staging service.
     */
    @GetMapping("/source/status")
    public ResponseEntity<Map<String, Object>> getModelSourceStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (stagingConfigService == null) {
            response.put("configured", false);
            response.put("message", "Staging service not available");
            return ResponseEntity.ok(response);
        }

        Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
        if (activeConfig.isEmpty()) {
            response.put("configured", false);
            response.put("message", "No staging service configured. Add one in Model Staging.");
            return ResponseEntity.ok(response);
        }

        StagingServiceConfig config = activeConfig.get();
        response.put("configured", true);
        response.put("sourceType", "staging");
        response.put("description", config.getEndpointUrl());
        response.put("name", config.getName());

        if (stagingClientService != null) {
            StagingClientService.ConnectionTestResult testResult = stagingClientService.testConnection(config);
            response.put("available", testResult.success());
            response.put("encoderCount", testResult.modelCount());
            response.put("crossEncoderCount", 0);
            if (!testResult.success()) {
                response.put("error", testResult.message());
            }
        } else {
            response.put("available", config.isVerified());
            if (config.getLastError() != null) {
                response.put("error", config.getLastError());
            }
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get staging service status.
     * Uses the UI-configured staging service.
     */
    @GetMapping("/staging/status")
    public ResponseEntity<StagingStatusResponse> getStagingStatus() {
        StagingStatusResponse response = new StagingStatusResponse();

        if (stagingConfigService != null && stagingClientService != null) {
            Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
            if (activeConfig.isPresent()) {
                StagingServiceConfig config = activeConfig.get();
                StagingClientService.ConnectionTestResult testResult = stagingClientService.testConnection(config);

                response.connected = testResult.success();
                response.stagingServiceUrl = config.getEndpointUrl();

                if (testResult.success()) {
                    try {
                        Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
                        if (registryOpt.isPresent()) {
                            List<StagingModelInfo> modelInfos = new ArrayList<>();
                            JsonNode modelsNode = registryOpt.get().get("models");
                            if (modelsNode != null && modelsNode.isObject()) {
                                modelsNode.fieldNames().forEachRemaining(modelId -> {
                                    StagingModelInfo info = new StagingModelInfo();
                                    info.modelId = modelId;
                                    info.status = "active";
                                    info.source = "staging";
                                    modelInfos.add(info);
                                });
                            }
                            response.modelsInStaging = modelInfos;
                        }
                    } catch (Exception e) {
                        log.debug("Error getting models from staging: {}", e.getMessage());
                    }
                }
            } else {
                response.connected = false;
                response.modelsInStaging = Collections.emptyList();
            }
        } else {
            response.connected = false;
            response.modelsInStaging = Collections.emptyList();
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get version and provenance summary for the registry.
     * Uses the UI-configured staging service.
     */
    @GetMapping("/registry/version-info")
    public ResponseEntity<VersionInfoResponse> getVersionInfo() {
        try {
            Map<String, Integer> modelsBySource = new LinkedHashMap<>();
            int totalModels = 0;
            String sourceType = "none";

            if (stagingConfigService != null && stagingClientService != null) {
                Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
                if (activeConfig.isPresent()) {
                    sourceType = "staging";
                    try {
                        Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
                        if (registryOpt.isPresent()) {
                            JsonNode modelsNode = registryOpt.get().get("models");
                            if (modelsNode != null && modelsNode.isObject()) {
                                totalModels = modelsNode.size();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Error counting models: {}", e.getMessage());
                    }
                }
            }
            modelsBySource.put(sourceType, totalModels);

            return ResponseEntity.ok(new VersionInfoResponse(
                    "2.0",
                    null,
                    totalModels,
                    totalModels,
                    modelsBySource,
                    Collections.emptyList()
            ));
        } catch (Exception e) {
            log.error("Failed to get version info", e);
            return ResponseEntity.ok(new VersionInfoResponse(
                    "2.0", null, 0, 0,
                    Collections.emptyMap(), Collections.emptyList()
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BUILT-IN MODEL CATALOG ENDPOINTS (from ModelConstants)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get all built-in models grouped by RAG pipeline phase.
     * This provides the complete catalog of models available for archive assembly.
     */
    @GetMapping("/catalog")
    public ResponseEntity<BuiltInModelCatalog> getBuiltInCatalog() {
        List<BuiltInModelInfo> denseEncoders = getBuiltInDenseEncoders();
        List<BuiltInModelInfo> sparseEncoders = getBuiltInSparseEncoders();
        List<BuiltInModelInfo> crossEncoders = getBuiltInCrossEncoders();

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("denseEncoders", denseEncoders.size());
        counts.put("sparseEncoders", sparseEncoders.size());
        counts.put("crossEncoders", crossEncoders.size());
        counts.put("total", denseEncoders.size() + sparseEncoders.size() + crossEncoders.size());

        return ResponseEntity.ok(new BuiltInModelCatalog(
                denseEncoders,
                sparseEncoders,
                crossEncoders,
                counts
        ));
    }

    /**
     * Get built-in dense encoder models (for semantic/vector retrieval)
     */
    @GetMapping("/catalog/dense-encoders")
    public ResponseEntity<List<BuiltInModelInfo>> getDenseEncodersEndpoint() {
        return ResponseEntity.ok(getBuiltInDenseEncoders());
    }

    /**
     * Get built-in sparse encoder models (for lexical/learned sparse retrieval)
     */
    @GetMapping("/catalog/sparse-encoders")
    public ResponseEntity<List<BuiltInModelInfo>> getSparseEncodersEndpoint() {
        return ResponseEntity.ok(getBuiltInSparseEncoders());
    }

    /**
     * Get built-in cross-encoder models (for reranking)
     */
    @GetMapping("/catalog/cross-encoders")
    public ResponseEntity<List<BuiltInModelInfo>> getCrossEncodersEndpoint() {
        return ResponseEntity.ok(getBuiltInCrossEncoders());
    }

    /**
     * Get a specific built-in model by ID
     */
    @GetMapping("/catalog/{modelId}")
    public ResponseEntity<BuiltInModelInfo> getBuiltInModel(@PathVariable String modelId) {
        // Check encoders first
        ModelDescriptor encoder = ModelConstants.getAnseriniEncoderModelDescriptor(modelId);
        if (encoder != null) {
            ModelDescriptor vocab = ModelConstants.getAnseriniEncoderVocabDescriptor(modelId);
            return ResponseEntity.ok(toBuiltInModelInfo(encoder, vocab));
        }

        // Check cross-encoders
        ModelDescriptor crossEncoder = ModelConstants.getCrossEncoderModelDescriptor(modelId);
        if (crossEncoder != null) {
            ModelDescriptor vocab = ModelConstants.getCrossEncoderVocabDescriptor(modelId);
            return ResponseEntity.ok(toBuiltInModelInfo(crossEncoder, vocab));
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * Assemble an archive with selected models for download.
     * Assigns RAG roles to models for automatic loading after import.
     */
    @PostMapping("/catalog/assemble")
    public ResponseEntity<AssembleArchiveResponse> assembleArchive(@RequestBody AssembleArchiveRequest request) {
        try {
            List<String> includedModels = new ArrayList<>();
            List<BuiltInModelInfo> modelDetails = new ArrayList<>();

            // Validate and collect all requested models
            if (request.denseEncoderIds != null) {
                for (String modelId : request.denseEncoderIds) {
                    if (ModelConstants.isEncoderModelAvailable(modelId)) {
                        ModelDescriptor desc = ModelConstants.getAnseriniEncoderModelDescriptor(modelId);
                        if ("dense".equals(desc.getMetadataString("model_type"))) {
                            includedModels.add(modelId);
                            modelDetails.add(toBuiltInModelInfo(desc,
                                    ModelConstants.getAnseriniEncoderVocabDescriptor(modelId)));
                        }
                    }
                }
            }

            if (request.sparseEncoderIds != null) {
                for (String modelId : request.sparseEncoderIds) {
                    if (ModelConstants.isEncoderModelAvailable(modelId)) {
                        ModelDescriptor desc = ModelConstants.getAnseriniEncoderModelDescriptor(modelId);
                        if ("sparse".equals(desc.getMetadataString("model_type"))) {
                            includedModels.add(modelId);
                            modelDetails.add(toBuiltInModelInfo(desc,
                                    ModelConstants.getAnseriniEncoderVocabDescriptor(modelId)));
                        }
                    }
                }
            }

            if (request.crossEncoderIds != null) {
                for (String modelId : request.crossEncoderIds) {
                    if (ModelConstants.isCrossEncoderModelAvailable(modelId)) {
                        includedModels.add(modelId);
                        modelDetails.add(toBuiltInModelInfo(
                                ModelConstants.getCrossEncoderModelDescriptor(modelId),
                                ModelConstants.getCrossEncoderVocabDescriptor(modelId)));
                    }
                }
            }

            if (includedModels.isEmpty()) {
                return ResponseEntity.badRequest().body(new AssembleArchiveResponse(
                        false, null, null, 0, 0,
                        Collections.emptyList(), Collections.emptyList(),
                        "No valid models selected for archive"
                ));
            }

            String archiveId = request.archiveId != null ? request.archiveId :
                    "custom-archive-" + System.currentTimeMillis();

            return ResponseEntity.ok(new AssembleArchiveResponse(
                    true,
                    archiveId,
                    "~/.kompile/archives/" + archiveId + ".karch",
                    0, // Size calculated during actual export
                    includedModels.size(),
                    includedModels,
                    modelDetails,
                    null
            ));

        } catch (Exception e) {
            log.error("Failed to assemble archive", e);
            return ResponseEntity.internalServerError().body(new AssembleArchiveResponse(
                    false, null, null, 0, 0,
                    Collections.emptyList(), Collections.emptyList(),
                    "Failed to assemble archive: " + e.getMessage()
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ARCHIVE IMPORT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Import an archive file by configuring it as the model source.
     *
     * <p>This endpoint switches the app to use the specified archive as its model source.
     * All models from the archive will become available.
     */
    @PostMapping("/import")
    public ResponseEntity<ArchiveImportResponse> importArchive(@RequestBody ArchiveImportRequest request) {
        try {
            // Configure the archive as the model source
            return localImportArchive(request);
        } catch (Exception e) {
            log.error("Archive import failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ArchiveImportResponse(false, null, null, 0, 0,
                            Collections.emptyList(), Collections.emptyList(),
                            "Import failed: " + e.getMessage())
            );
        }
    }

    /**
     * Refresh the model source to pick up any changes.
     */
    @PostMapping("/registry/refresh")
    public ResponseEntity<Map<String, Object>> refreshRegistry() {
        try {
            int encoderCount = 0;
            String sourceType = "none";

            if (stagingConfigService != null && stagingClientService != null) {
                Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
                if (activeConfig.isPresent()) {
                    sourceType = "staging";
                    StagingClientService.ConnectionTestResult testResult = stagingClientService.testConnection(activeConfig.get());
                    if (testResult.success()) {
                        encoderCount = testResult.modelCount();
                    }
                }
            }

            // Refresh caches
            refreshRegistryCache();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Model source refreshed",
                    "sourceType", sourceType,
                    "modelCount", encoderCount,
                    "encoderCount", encoderCount,
                    "crossEncoderCount", 0
            ));
        } catch (Exception e) {
            log.error("Failed to refresh model source", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EMBEDDING MODEL RELOAD ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get the current embedding model status.
     * Returns initialization state, source, dimensions, and other info.
     */
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> getEmbeddingModelStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("available", false);
            response.put("message", "Embedding model is not configured");
            return ResponseEntity.ok(response);
        }

        response.put("available", true);
        response.putAll(embeddingModel.getModelStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * List all available embedding models from registry and built-in sources.
     */
    @GetMapping("/embedding/available")
    public ResponseEntity<Map<String, Object>> getAvailableEmbeddingModels() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("available", false);
            response.put("message", "Embedding model is not configured");
            response.put("models", Collections.emptyList());
            return ResponseEntity.ok(response);
        }

        response.put("available", true);
        response.put("currentModel", embeddingModel.getActiveModelId());
        response.put("currentSource", embeddingModel.getModelSource().name());
        response.put("models", embeddingModel.getAvailableModelsInfo());

        return ResponseEntity.ok(response);
    }

    /**
     * Reload the current embedding model from its source.
     * Useful after registry updates or archive imports.
     */
    @PostMapping("/embedding/reload")
    public ResponseEntity<Map<String, Object>> reloadEmbeddingModel() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("success", false);
            response.put("error", "Embedding model is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        try {
            // First refresh the registry cache
            refreshRegistryCache();

            String previousModel = embeddingModel.getActiveModelId();
            String previousSource = embeddingModel.getModelSource().name();

            boolean success = embeddingModel.reloadModel();

            if (success) {
                // Update vector store's encoder model ID
                updateVectorStoreEncoderModel(embeddingModel.getActiveModelId());

                response.put("success", true);
                response.put("message", "Embedding model reloaded successfully");
                response.put("previousModel", previousModel);
                response.put("previousSource", previousSource);
                response.put("currentModel", embeddingModel.getActiveModelId());
                response.put("currentSource", embeddingModel.getModelSource().name());
                response.put("dimensions", embeddingModel.dimensions());
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", "Failed to reload embedding model");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        } catch (Exception e) {
            log.error("Failed to reload embedding model", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Switch to a different embedding model.
     * The model must be available in the registry or built-in catalog.
     */
    @PostMapping("/embedding/switch/{modelId}")
    public ResponseEntity<Map<String, Object>> switchEmbeddingModel(@PathVariable String modelId) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (embeddingModel == null) {
            response.put("success", false);
            response.put("error", "Embedding model is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        try {
            // First refresh the registry cache to pick up any new models
            refreshRegistryCache();

            String previousModel = embeddingModel.getActiveModelId();
            String previousSource = embeddingModel.getModelSource().name();

            boolean success = embeddingModel.reloadModel(modelId);

            if (success) {
                // Update vector store's encoder model ID
                updateVectorStoreEncoderModel(embeddingModel.getActiveModelId());

                response.put("success", true);
                response.put("message", "Switched to model: " + modelId);
                response.put("previousModel", previousModel);
                response.put("previousSource", previousSource);
                response.put("currentModel", embeddingModel.getActiveModelId());
                response.put("currentSource", embeddingModel.getModelSource().name());
                response.put("dimensions", embeddingModel.dimensions());
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("error", "Failed to switch to model: " + modelId);
                response.put("currentModel", embeddingModel.getActiveModelId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
        } catch (Exception e) {
            log.error("Failed to switch embedding model to: {}", modelId, e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Refresh model source AND reload the embedding model in one call.
     * This is the recommended endpoint to call after configuration changes.
     * Uses the UI-configured staging service (from database).
     */
    @PostMapping("/registry/refresh-and-reload")
    public ResponseEntity<Map<String, Object>> refreshRegistryAndReloadModel() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            int encoderCount = 0;
            int crossEncoderCount = 0;
            String sourceType = null;

            // 1. Try UI-configured staging service first
            if (stagingConfigService != null && stagingClientService != null) {
                Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
                if (activeConfig.isPresent()) {
                    StagingServiceConfig config = activeConfig.get();
                    sourceType = "staging";

                    // Test connection and get model count
                    StagingClientService.ConnectionTestResult testResult = stagingClientService.testConnection(config);
                    if (testResult.success()) {
                        encoderCount = testResult.modelCount();
                        log.info("Staging service refreshed: {} models available", encoderCount);
                        response.put("sourceRefreshed", true);
                        response.put("stagingName", config.getName());
                        response.put("stagingUrl", config.getEndpointUrl());
                    } else {
                        response.put("sourceRefreshed", false);
                        response.put("stagingError", testResult.message());
                    }
                } else {
                    response.put("sourceRefreshed", false);
                    response.put("message", "No active staging service configured. Add one in Model Staging.");
                }
            } else {
                response.put("sourceRefreshed", false);
                response.put("message", "Staging services not available");
            }

            response.put("sourceType", sourceType);
            response.put("modelCount", encoderCount + crossEncoderCount);
            response.put("encoderCount", encoderCount);
            response.put("crossEncoderCount", crossEncoderCount);

            // 2. Reload embedding model if available
            if (embeddingModel != null) {
                String previousModel = embeddingModel.getActiveModelId();
                boolean reloadSuccess = embeddingModel.reloadModel();

                response.put("embeddingModelReloaded", reloadSuccess);
                response.put("previousModel", previousModel);
                response.put("currentModel", embeddingModel.getActiveModelId());
                response.put("modelSource", embeddingModel.getModelSource().name());

                if (reloadSuccess) {
                    // Update vector store's encoder model ID
                    updateVectorStoreEncoderModel(embeddingModel.getActiveModelId());
                    response.put("dimensions", embeddingModel.dimensions());
                }
            } else {
                response.put("embeddingModelReloaded", false);
                response.put("embeddingModelMessage", "Embedding model not configured");
            }

            response.put("success", true);
            response.put("message", "Model source refreshed and embedding reloaded");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to refresh source and reload model", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // VLM MODEL RELOAD ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get the current VLM model status.
     * Returns whether VLM is available, loaded, and ready.
     */
    @GetMapping("/vlm/status")
    public ResponseEntity<Map<String, Object>> getVlmModelStatus() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (ocrPipelineService == null) {
            response.put("available", false);
            response.put("loaded", false);
            response.put("ready", false);
            response.put("message", "OCR pipeline service is not configured");
            return ResponseEntity.ok(response);
        }

        response.put("available", ocrPipelineService.isVlmAvailable());
        response.put("ready", ocrPipelineService.isVlmAvailable() && ocrPipelineService.isReady());
        response.put("ocrEnabled", ocrPipelineService.isOcrEnabled());

        if (modelAutoInitService != null) {
            response.put("embeddingInitialized", modelAutoInitService.isEmbeddingInitialized());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Trigger VLM model reload/initialization.
     * Called by the staging server callback or manually to load VLM models.
     */
    @PostMapping("/vlm/reload")
    public ResponseEntity<Map<String, Object>> reloadVlmModel() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (ocrPipelineService == null) {
            response.put("success", false);
            response.put("error", "OCR pipeline service is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        try {
            // VLM runs in subprocess only — direct initialization from main app
            ocrPipelineService.initializeVlmOnly();

            response.put("success", true);
            response.put("message", "VLM model reloaded successfully");
            response.put("vlmAvailable", ocrPipelineService.isVlmAvailable());
            response.put("ready", ocrPipelineService.isReady());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to reload VLM model", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get models by RAG role (retrieval or reranking).
     * Uses the UI-configured staging service.
     */
    @GetMapping("/registry/by-role/{role}")
    public ResponseEntity<List<ModelEntry>> getModelsByRole(@PathVariable String role) {
        try {
            List<ModelEntry> models = new ArrayList<>();

            // Get models from UI-configured staging service
            if (stagingClientService != null) {
                Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
                if (registryOpt.isPresent()) {
                    JsonNode registry = registryOpt.get();
                    JsonNode modelsNode = registry.get("models");
                    if (modelsNode != null && modelsNode.isObject()) {
                        modelsNode.fields().forEachRemaining(entry -> {
                            JsonNode model = entry.getValue();
                            JsonNode metadata = model.get("metadata");
                            if (metadata != null) {
                                String ragRole = metadata.has("rag_role") ? metadata.get("rag_role").asText() : null;
                                if (role.equalsIgnoreCase(ragRole)) {
                                    models.add(jsonToModelEntry(entry.getKey(), model));
                                }
                            }
                        });
                    }
                }
            }

            return ResponseEntity.ok(models);
        } catch (Exception e) {
            log.error("Failed to get models by role: {}", role, e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Local import implementation - configures an archive as the model source.
     */
    private ResponseEntity<ArchiveImportResponse> localImportArchive(ArchiveImportRequest request) {
        Path archivePath = Paths.get(request.archivePath);

        if (!Files.exists(archivePath)) {
            return ResponseEntity.badRequest().body(
                    new ArchiveImportResponse(false, null, null, 0, 0,
                            Collections.emptyList(), Collections.emptyList(),
                            "Archive file not found: " + archivePath)
            );
        }

        // Archive import is now handled via the staging service
        // Archives should be uploaded to the staging service, not imported locally
        return ResponseEntity.badRequest().body(
                new ArchiveImportResponse(false, null, null, 0, 0,
                        Collections.emptyList(), Collections.emptyList(),
                        "Local archive import is not supported. Upload archives to the staging service instead.")
        );
    }

    /**
     * Refresh internal caches (AnseriniEncoderFactory, CrossEncoderRerankerAdapter).
     */
    private void refreshRegistryCache() {
        try {
            // Refresh the encoder factory cache
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();
        } catch (Exception e) {
            log.warn("Failed to refresh AnseriniEncoderFactory cache: {}", e.getMessage());
        }

        try {
            // Refresh the cross-encoder adapter cache
            ai.kompile.vectorstore.anserini.reranking.CrossEncoderRerankerAdapter.refreshRegistry();
            log.info("CrossEncoderRerankerAdapter registry cache refreshed");
        } catch (Exception e) {
            log.warn("Failed to refresh CrossEncoderRerankerAdapter cache: {}", e.getMessage());
        }
    }

    /**
     * Update the vector store's encoder model ID when an embedding model is loaded.
     * This ensures the vector store knows which model was used for embeddings.
     */
    private void updateVectorStoreEncoderModel(String modelId) {
        if (vectorStore != null && modelId != null) {
            try {
                vectorStore.setEncoderModelId(modelId);
                log.info("Updated vector store encoder model ID to: {}", modelId);
            } catch (Exception e) {
                log.warn("Failed to update vector store encoder model ID: {}", e.getMessage());
            }
        }
    }

    private int toIntSafe(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BUILT-IN MODEL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private List<BuiltInModelInfo> getBuiltInDenseEncoders() {
        return ModelConstants.getAvailableEncoderModelIds().stream()
                .map(ModelConstants::getAnseriniEncoderModelDescriptor)
                .filter(d -> "dense".equals(d.getMetadataString("model_type")))
                .map(d -> toBuiltInModelInfo(d, ModelConstants.getAnseriniEncoderVocabDescriptor(d.getModelId())))
                .collect(Collectors.toList());
    }

    private List<BuiltInModelInfo> getBuiltInSparseEncoders() {
        return ModelConstants.getAvailableEncoderModelIds().stream()
                .map(ModelConstants::getAnseriniEncoderModelDescriptor)
                .filter(d -> "sparse".equals(d.getMetadataString("model_type")))
                .map(d -> toBuiltInModelInfo(d, ModelConstants.getAnseriniEncoderVocabDescriptor(d.getModelId())))
                .collect(Collectors.toList());
    }

    private List<BuiltInModelInfo> getBuiltInCrossEncoders() {
        return ModelConstants.getAvailableCrossEncoderModelIds().stream()
                .map(ModelConstants::getCrossEncoderModelDescriptor)
                .map(d -> toBuiltInModelInfo(d, ModelConstants.getCrossEncoderVocabDescriptor(d.getModelId())))
                .collect(Collectors.toList());
    }

    private BuiltInModelInfo toBuiltInModelInfo(ModelDescriptor model, ModelDescriptor vocab) {
        Map<String, Object> metadata = model.getMetadata();

        String modelType;
        String category;
        if (model.getModelType() == ModelType.CROSS_ENCODER_MODEL) {
            modelType = "cross_encoder";
            category = "reranking";
        } else if ("sparse".equals(model.getMetadataString("model_type"))) {
            modelType = "sparse_encoder";
            category = "retrieval";
        } else {
            modelType = "dense_encoder";
            category = "retrieval";
        }

        return new BuiltInModelInfo(
                model.getModelId(),
                modelType,
                category,
                model.getMetadataString("description"),
                model.getMetadataString("framework"),
                model.getVersion(),
                getMetadataInt(metadata, "embedding_dim"),
                getMetadataInt(metadata, "tokenizer_max_sequence_length"),
                getMetadataInt(metadata, "hidden_size"),
                getMetadataInt(metadata, "num_layers"),
                model.getMetadataString("training_data"),
                model.getMetadataString("input_format"),
                model.getMetadataString("output_type"),
                model.getMetadataString("tokenizer_type"),
                getMetadataBool(metadata, "tokenizer_do_lower_case", true),
                getMetadataBool(metadata, "tokenizer_strip_accents", true),
                model.getDownloadUrl(),
                vocab != null ? vocab.getDownloadUrl() : null,
                model.getMetadataString("huggingface_source"),
                model.getMetadataString("languages")
        );
    }

    private Integer getMetadataInt(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private boolean getMetadataBool(Map<String, Object> metadata, String key, boolean defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * Build a ModelRegistry from the UI-configured staging service.
     */
    private ModelRegistry buildRegistryFromSource() throws IOException {
        ModelRegistry registry = new ModelRegistry();
        registry.version = "2.0";

        // Use UI-configured staging service (from database)
        if (stagingClientService != null) {
            Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
            if (registryOpt.isPresent()) {
                JsonNode remoteRegistry = registryOpt.get();
                JsonNode modelsNode = remoteRegistry.get("models");
                if (modelsNode != null && modelsNode.isObject()) {
                    modelsNode.fields().forEachRemaining(entry -> {
                        registry.models.put(entry.getKey(), jsonToModelEntry(entry.getKey(), entry.getValue()));
                    });
                }
                // Get version from remote registry
                if (remoteRegistry.has("version")) {
                    registry.version = remoteRegistry.get("version").asText("2.0");
                }
                log.debug("Built registry with {} models from staging service", registry.models.size());
                return registry;
            }
        }

        log.debug("No staging service configured or available");
        return registry;
    }

    /**
     * Convert a JSON model node to ModelEntry.
     */
    private ModelEntry jsonToModelEntry(String modelId, JsonNode json) {
        ModelEntry entry = new ModelEntry();
        entry.modelId = modelId;
        entry.type = json.has("type") ? json.get("type").asText() : null;
        entry.path = json.has("path") ? json.get("path").asText() : null;
        entry.modelFile = json.has("model_file") ? json.get("model_file").asText() : null;
        entry.vocabFile = json.has("vocab_file") ? json.get("vocab_file").asText() : null;
        entry.checksum = json.has("checksum") ? json.get("checksum").asText() : null;
        entry.status = json.has("status") ? json.get("status").asText() : null;
        entry.promotedAt = json.has("promoted_at") ? json.get("promoted_at").asText() : null;

        JsonNode metadataNode = json.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            ModelMetadata metadata = new ModelMetadata();
            metadata.embeddingDim = metadataNode.has("embedding_dim") ? metadataNode.get("embedding_dim").asInt() : null;
            metadata.hiddenSize = metadataNode.has("hidden_size") ? metadataNode.get("hidden_size").asInt() : null;
            metadata.numLayers = metadataNode.has("num_layers") ? metadataNode.get("num_layers").asInt() : null;
            metadata.maxSequenceLength = metadataNode.has("max_sequence_length") ? metadataNode.get("max_sequence_length").asInt() : null;
            metadata.modelType = metadataNode.has("model_type") ? metadataNode.get("model_type").asText() : null;
            metadata.encoderType = metadataNode.has("encoder_type") ? metadataNode.get("encoder_type").asText() : null;
            metadata.framework = metadataNode.has("framework") ? metadataNode.get("framework").asText() : null;
            metadata.trainingData = metadataNode.has("training_data") ? metadataNode.get("training_data").asText() : null;
            metadata.sourceOrigin = metadataNode.has("source_origin") ? metadataNode.get("source_origin").asText() : null;
            metadata.description = metadataNode.has("description") ? metadataNode.get("description").asText() : null;
            metadata.ragRole = metadataNode.has("rag_role") ? metadataNode.get("rag_role").asText() : null;
            entry.metadata = metadata;
        }

        JsonNode tokenizerNode = json.get("tokenizer");
        if (tokenizerNode != null && tokenizerNode.isObject()) {
            TokenizerConfig tokenizer = new TokenizerConfig();
            tokenizer.doLowerCase = tokenizerNode.has("do_lower_case") ? tokenizerNode.get("do_lower_case").asBoolean() : null;
            tokenizer.addSpecialTokens = tokenizerNode.has("add_special_tokens") ? tokenizerNode.get("add_special_tokens").asBoolean() : null;
            tokenizer.stripAccents = tokenizerNode.has("strip_accents") ? tokenizerNode.get("strip_accents").asBoolean() : null;
            tokenizer.maxLength = tokenizerNode.has("max_length") ? tokenizerNode.get("max_length").asInt() : null;
            entry.tokenizer = tokenizer;
        }

        return entry;
    }

    // ==================== Inner Classes (DTOs) ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelRegistry {
        @JsonProperty("version")
        public String version = "1.0";

        @JsonProperty("updated_at")
        public String updatedAt;

        @JsonProperty("models")
        public Map<String, ModelEntry> models = new HashMap<>();

        @JsonProperty("installed_archives")
        public Map<String, ArchiveInstallInfo> installedArchives = new HashMap<>();

        public static ModelRegistry empty() {
            ModelRegistry registry = new ModelRegistry();
            registry.version = "1.0";
            registry.updatedAt = null;
            registry.models = new HashMap<>();
            registry.installedArchives = new HashMap<>();
            return registry;
        }

        public ModelEntry getModel(String modelId) {
            return models.get(modelId);
        }

        public List<ModelEntry> getModelsByType(String type) {
            return models.values().stream()
                    .filter(e -> e.type != null && e.type.equalsIgnoreCase(type))
                    .collect(Collectors.toList());
        }

        public List<ModelEntry> getActiveModels() {
            return models.values().stream()
                    .filter(e -> "active".equalsIgnoreCase(e.status))
                    .collect(Collectors.toList());
        }

        public List<ArchiveInstallInfo> getInstalledArchivesList() {
            return new ArrayList<>(installedArchives.values());
        }
    }

    /**
     * Information about an installed archive for provenance tracking.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchiveInstallInfo {
        @JsonProperty("archive_id")
        public String archiveId;

        @JsonProperty("archive_name")
        public String archiveName;

        @JsonProperty("version")
        public String version;

        @JsonProperty("installed_at")
        public String installedAt;

        @JsonProperty("source_url")
        public String sourceUrl;

        @JsonProperty("checksum")
        public String checksum;

        @JsonProperty("model_ids")
        public List<String> modelIds = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {
        @JsonProperty("model_id")
        public String modelId;

        @JsonProperty("type")
        public String type;

        @JsonProperty("path")
        public String path;

        @JsonProperty("model_file")
        public String modelFile = "model.sdz";

        @JsonProperty("vocab_file")
        public String vocabFile = "vocab.txt";

        @JsonProperty("checksum")
        public String checksum;

        @JsonProperty("status")
        public String status = "active";

        @JsonProperty("promoted_at")
        public String promotedAt;

        @JsonProperty("version")
        public String version;

        @JsonProperty("metadata")
        public ModelMetadata metadata;

        @JsonProperty("tokenizer")
        public TokenizerConfig tokenizer;

        /**
         * Get the effective version, checking top-level first then metadata.
         */
        public String getEffectiveVersion() {
            if (version != null && !version.isEmpty()) {
                return version;
            }
            if (metadata != null && metadata.version != null) {
                return metadata.version;
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelMetadata {
        @JsonProperty("embedding_dim")
        public Integer embeddingDim;

        @JsonProperty("hidden_size")
        public Integer hiddenSize;

        @JsonProperty("num_layers")
        public Integer numLayers;

        @JsonProperty("max_sequence_length")
        public int maxSequenceLength = 512;

        @JsonProperty("model_type")
        public String modelType;

        @JsonProperty("encoder_type")
        public String encoderType;

        @JsonProperty("rag_role")
        public String ragRole;

        @JsonProperty("framework")
        public String framework = "samediff";

        @JsonProperty("training_data")
        public String trainingData;

        @JsonProperty("source_origin")
        public String sourceOrigin;

        @JsonProperty("source_repository")
        public String sourceRepository;

        @JsonProperty("original_format")
        public String originalFormat;

        @JsonProperty("conversion_date")
        public String conversionDate;

        @JsonProperty("description")
        public String description;

        // === Provenance fields for version tracking ===

        @JsonProperty("version")
        public String version;

        @JsonProperty("staging_registry_version")
        public String stagingRegistryVersion;

        @JsonProperty("source_archive_id")
        public String sourceArchiveId;

        @JsonProperty("source_archive_version")
        public String sourceArchiveVersion;

        @JsonProperty("installed_from")
        public String installedFrom;

        @JsonProperty("installed_at")
        public String installedAt;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenizerConfig {
        @JsonProperty("do_lower_case")
        public boolean doLowerCase = true;

        @JsonProperty("add_special_tokens")
        public boolean addSpecialTokens = true;

        @JsonProperty("strip_accents")
        public boolean stripAccents = true;

        @JsonProperty("max_length")
        public int maxLength = 512;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StagingStatusResponse {
        @JsonProperty("connected")
        public boolean connected;

        @JsonProperty("stagingServiceUrl")
        public String stagingServiceUrl;

        @JsonProperty("modelsInStaging")
        public List<StagingModelInfo> modelsInStaging = new ArrayList<>();

        @JsonProperty("lastSync")
        public String lastSync;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StagingModelInfo {
        @JsonProperty("modelId")
        public String modelId;

        @JsonProperty("status")
        public String status;

        @JsonProperty("progress")
        public int progress;

        @JsonProperty("error")
        public String error;

        @JsonProperty("source")
        public String source;

        @JsonProperty("startedAt")
        public String startedAt;

        @JsonProperty("completedAt")
        public String completedAt;

        @JsonProperty("message")
        public String message;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BUILT-IN MODEL CATALOG DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Complete model information for UI display
     */
    public record BuiltInModelInfo(
            String modelId,
            String modelType,       // "dense_encoder", "sparse_encoder", "cross_encoder"
            String category,        // RAG pipeline phase: "retrieval", "reranking"
            String description,
            String framework,
            String version,
            Integer embeddingDim,
            Integer maxSequenceLength,
            Integer hiddenSize,
            Integer numLayers,
            String trainingData,
            String inputFormat,
            String outputType,
            String tokenizerType,
            boolean doLowerCase,
            boolean stripAccents,
            String downloadUrl,
            String vocabUrl,
            String huggingfaceSource,
            String languages
    ) {}

    /**
     * All built-in models grouped by RAG pipeline phase
     */
    public record BuiltInModelCatalog(
            List<BuiltInModelInfo> denseEncoders,
            List<BuiltInModelInfo> sparseEncoders,
            List<BuiltInModelInfo> crossEncoders,
            Map<String, Integer> counts
    ) {}

    /**
     * Request to assemble an archive with selected models
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssembleArchiveRequest {
        @JsonProperty("archiveId")
        public String archiveId;

        @JsonProperty("archiveName")
        public String archiveName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("version")
        public String version;

        @JsonProperty("denseEncoderIds")
        public List<String> denseEncoderIds;

        @JsonProperty("sparseEncoderIds")
        public List<String> sparseEncoderIds;

        @JsonProperty("crossEncoderIds")
        public List<String> crossEncoderIds;
    }

    /**
     * Response with archive assembly status
     */
    public record AssembleArchiveResponse(
            boolean success,
            String archiveId,
            String archivePath,
            long totalSizeBytes,
            int modelCount,
            List<String> includedModelIds,
            List<BuiltInModelInfo> includedModels,
            String error
    ) {}

    /**
     * Request to import an archive
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ArchiveImportRequest {
        @JsonProperty("archivePath")
        public String archivePath;

        @JsonProperty("verifyChecksums")
        public boolean verifyChecksums = true;

        @JsonProperty("forceOverwrite")
        public boolean forceOverwrite = false;

        @JsonProperty("skipCompatibilityCheck")
        public boolean skipCompatibilityCheck = false;

        @JsonProperty("roleAssignments")
        public Map<String, String> roleAssignments; // modelId -> role (retrieval/reranking)
    }

    /**
     * Response from archive import
     */
    public record ArchiveImportResponse(
            boolean success,
            String archiveId,
            String version,
            int importedCount,
            int skippedCount,
            List<String> importedModels,
            List<String> skippedModels,
            String error
    ) {}

    /**
     * Version and provenance summary for the registry
     */
    public record VersionInfoResponse(
            String registryVersion,
            String updatedAt,
            int totalModels,
            int activeModels,
            Map<String, Integer> modelsBySource,
            List<ArchiveInstallInfo> installedArchives
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // ACTIVE MODEL CONTEXT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get the active model context — which models are currently powering
     * embedding, reranking, and what staging alternatives are available.
     */
    @GetMapping("/active-context")
    public ResponseEntity<ActiveModelContext> getActiveModelContext() {
        EmbeddingContext embeddingCtx = null;
        RerankerContext rerankerCtx = null;
        StagingContext stagingCtx = null;
        List<String> availableEmbeddingModels = new ArrayList<>();
        List<String> availableRerankerModels = new ArrayList<>();

        // Embedding info
        if (embeddingModel != null) {
            try {
                String modelId = embeddingModel.getActiveModelId();
                String encoderType = embeddingModel.getEncoderType();
                int dims = embeddingModel.dimensions();
                boolean initialized = embeddingModel.isInitialized();
                String status = initialized ? "ready" : (modelId != null ? "loading" : "not_configured");
                embeddingCtx = new EmbeddingContext(modelId, encoderType, dims, status, initialized);
            } catch (Exception e) {
                log.debug("Error getting embedding context: {}", e.getMessage());
                embeddingCtx = new EmbeddingContext(null, null, 0, "error", false);
            }
        }

        // Reranker info
        if (vectorStore != null) {
            try {
                String rerankerModelId = vectorStore.getRerankerModelId();
                boolean rerankingAvailable = vectorStore.isRerankingAvailable();
                rerankerCtx = new RerankerContext(rerankerModelId, rerankingAvailable);
            } catch (Exception e) {
                log.debug("Error getting reranker context: {}", e.getMessage());
                rerankerCtx = new RerankerContext(null, false);
            }
        }

        // Staging info and available alternatives
        if (stagingConfigService != null && stagingClientService != null) {
            try {
                Optional<StagingServiceConfig> activeConfig = stagingConfigService.getActiveConfig();
                StagingClientService.ConnectionStatus connStatus = stagingClientService.getConnectionStatus();

                if (activeConfig.isPresent()) {
                    String endpointUrl = activeConfig.get().getEndpointUrl();
                    // Derive UI URL from endpoint (strip /api suffix if present)
                    String uiUrl = endpointUrl;
                    if (uiUrl != null && uiUrl.endsWith("/api")) {
                        uiUrl = uiUrl.substring(0, uiUrl.length() - 4);
                    }
                    stagingCtx = new StagingContext(connStatus.connected(), endpointUrl, uiUrl);

                    // Get available models from staging registry
                    if (connStatus.connected()) {
                        try {
                            Optional<JsonNode> registryOpt = stagingClientService.getRegistry();
                            if (registryOpt.isPresent()) {
                                JsonNode modelsNode = registryOpt.get().get("models");
                                if (modelsNode != null && modelsNode.isObject()) {
                                    modelsNode.fieldNames().forEachRemaining(modelId -> {
                                        JsonNode model = modelsNode.get(modelId);
                                        String type = model.path("type").asText("");
                                        if ("dense_encoder".equalsIgnoreCase(type) || "encoder".equalsIgnoreCase(type)) {
                                            availableEmbeddingModels.add(modelId);
                                        } else if ("cross_encoder".equalsIgnoreCase(type) || "reranker".equalsIgnoreCase(type)) {
                                            availableRerankerModels.add(modelId);
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Error fetching staging registry for alternatives: {}", e.getMessage());
                        }
                    }
                } else {
                    stagingCtx = new StagingContext(false, null, null);
                }
            } catch (Exception e) {
                log.debug("Error getting staging context: {}", e.getMessage());
                stagingCtx = new StagingContext(false, null, null);
            }
        }

        return ResponseEntity.ok(new ActiveModelContext(
                embeddingCtx, rerankerCtx, stagingCtx,
                availableEmbeddingModels, availableRerankerModels));
    }

    public record ActiveModelContext(
            EmbeddingContext embedding,
            RerankerContext reranker,
            StagingContext staging,
            List<String> availableEmbeddingModels,
            List<String> availableRerankerModels
    ) {}

    public record EmbeddingContext(
            String modelId,
            String encoderType,
            int dimensions,
            String status,
            boolean initialized
    ) {}

    public record RerankerContext(
            String modelId,
            boolean available
    ) {}

    public record StagingContext(
            boolean connected,
            String endpointUrl,
            String uiUrl
    ) {}
}
