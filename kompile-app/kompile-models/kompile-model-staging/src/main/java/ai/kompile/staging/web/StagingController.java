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

package ai.kompile.staging.web;

import ai.kompile.staging.archive.ArchiveModelManager;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.catalog.ModelCatalog;
import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.config.StagingSettings;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.web.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST API controller for the model staging service.
 */
@RestController
@RequestMapping("/api/staging")
@CrossOrigin(origins = "*")
public class StagingController {

    private static final Logger log = LoggerFactory.getLogger(StagingController.class);

    private final RegistryService registryService;
    private final StagingService stagingService;
    private final ExportService exportService;
    private final ImportService importService;
    private final CatalogService catalogService;
    private final ArchiveModelManager archiveModelManager;
    private final ModelSourceConfiguration modelSourceConfig;
    private final StagingSettingsService stagingSettingsService;

    @Value("${kompile.staging.project-dir:}")
    private String projectDir;

    @Value("${kompile.staging.settings-dir:${kompile.home:${user.home}/.kompile}}")
    private String settingsDir;

    @Autowired
    public StagingController(RegistryService registryService,
                            StagingService stagingService,
                            ExportService exportService,
                            ImportService importService,
                            CatalogService catalogService,
                            ArchiveModelManager archiveModelManager,
                            ModelSourceConfiguration modelSourceConfig,
                            StagingSettingsService stagingSettingsService) {
        this.registryService = registryService;
        this.stagingService = stagingService;
        this.exportService = exportService;
        this.importService = importService;
        this.catalogService = catalogService;
        this.archiveModelManager = archiveModelManager;
        this.modelSourceConfig = modelSourceConfig;
        this.stagingSettingsService = stagingSettingsService;
    }

    // ==================== Context & Settings Endpoints ====================

    /**
     * Get the staging server's current context — project dir, model dir, settings dir,
     * and whether this instance is project-scoped.
     */
    @GetMapping("/context")
    public Map<String, Object> getContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        boolean scoped = projectDir != null && !projectDir.isBlank();
        context.put("projectDir", scoped ? projectDir : null);
        context.put("modelDir", registryService.getModelDir().toAbsolutePath().toString());
        context.put("settingsDir", settingsDir);
        context.put("projectScoped", scoped);
        return context;
    }

    /**
     * Get the current staging settings.
     */
    @GetMapping("/settings")
    public StagingSettings getSettings() {
        return stagingSettingsService.getSettings();
    }

    /**
     * Update staging settings (callback URL, auto-reload, optimizer flags, etc.).
     * Settings are persisted to the settings file (project-local or global).
     */
    @PutMapping("/settings")
    public StagingSettings updateSettings(@RequestBody StagingSettings settings) {
        return stagingSettingsService.updateSettings(settings);
    }

    // ==================== Registry Endpoints ====================

    /**
     * Get the full model registry.
     */
    @GetMapping("/registry")
    public ModelRegistry getRegistry() {
        return registryService.loadRegistry();
    }

    /**
     * Get models by type.
     */
    @GetMapping("/registry/{type}")
    public List<ModelEntry> getModelsByType(@PathVariable String type) {
        return registryService.getModelsByType(ModelType.fromValue(type));
    }

    /**
     * Get a specific model.
     */
    @GetMapping("/registry/model/{modelId}")
    public ResponseEntity<ModelEntry> getModel(@PathVariable String modelId) {
        return registryService.getModel(modelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a model entry.
     */
    @PutMapping("/registry/model/{modelId}")
    public ResponseEntity<Map<String, Object>> updateModel(
            @PathVariable String modelId,
            @RequestBody UpdateModelRequest request) {
        log.info("Updating model: {} with request: {}", modelId, request);

        try {
            // Build update entry
            ModelEntry updates = ModelEntry.builder()
                    .modelId(modelId)
                    .type(request.toModelType())
                    .status(request.toModelStatus())
                    .metadata(request.toModelMetadata())
                    .tokenizer(request.toTokenizerConfig())
                    .preprocessor(request.toImagePreprocessorConfig())
                    .build();

            return registryService.updateModel(modelId, updates)
                    .map(updated -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("success", true);
                        response.put("message", "Model updated successfully");
                        response.put("model", updated);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("success", false);
                        response.put("error", "Model not found: " + modelId);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                    });
        } catch (Exception e) {
            log.error("Failed to update model: {}", modelId, e);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", "Failed to update model: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Re-probe a VLM model's vision encoder IO config.
     * Loads the vision encoder SameDiff graph and discovers input/output variable names.
     */
    @PostMapping("/registry/model/{modelId}/probe-vision-io")
    public ResponseEntity<Map<String, Object>> probeVisionEncoderIO(@PathVariable String modelId) {
        log.info("Probing vision encoder IO config for: {}", modelId);
        try {
            return registryService.getModel(modelId)
                    .map(entry -> {
                        if (!entry.getType().isVlm()) {
                            Map<String, Object> resp = new LinkedHashMap<>();
                            resp.put("success", false);
                            resp.put("error", "Model is not a VLM type: " + entry.getType());
                            return ResponseEntity.badRequest().body(resp);
                        }
                        java.nio.file.Path modelDir = registryService.getModelsDir()
                                .resolve(entry.getPath());
                        java.nio.file.Path modelFile = modelDir.resolve(entry.getModelFile());
                        ModelMetadata meta = entry.getMetadata();
                        if (meta == null) {
                            meta = ModelMetadata.builder().build();
                            entry.setMetadata(meta);
                        }
                        stagingService.probeVisionEncoderIOConfigPublic(modelDir, modelFile, meta);
                        registryService.updateModel(modelId, ModelEntry.builder()
                                .modelId(modelId)
                                .metadata(meta)
                                .build());
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("success", true);
                        resp.put("message", "Vision encoder IO config probed successfully");
                        resp.put("visionEncoderPixelValuesName", meta.getVisionEncoderPixelValuesName());
                        resp.put("visionEncoderPixelAttentionMaskName", meta.getVisionEncoderPixelAttentionMaskName());
                        resp.put("visionEncoderPrimaryOutputName", meta.getVisionEncoderPrimaryOutputName());
                        resp.put("visionEncoderOutputNames", meta.getVisionEncoderOutputNames());
                        return ResponseEntity.ok(resp);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("success", false);
                        resp.put("error", "Model not found: " + modelId);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(resp);
                    });
        } catch (Exception e) {
            log.error("Failed to probe vision encoder IO for: {}", modelId, e);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("error", "Probe failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
        }
    }

    /**
     * Delete a model from the registry.
     */
    @DeleteMapping("/registry/model/{modelId}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable String modelId) {
        log.info("Deleting model: {}", modelId);

        return registryService.removeModel(modelId)
                .map(removed -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("message", "Model deleted successfully");
                    response.put("modelId", modelId);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", false);
                    response.put("error", "Model not found: " + modelId);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                });
    }

    // ==================== Model File Download Endpoints ====================

    /**
     * Download a model file (.sdz/.onnx) for a registered model.
     * Used by the main app's RegistryBasedModelManager to fetch model files from staging.
     */
    @GetMapping("/registry/model/{modelId}/download/model")
    public ResponseEntity<?> downloadModelFile(@PathVariable String modelId) {
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelPath = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId)
                            .resolve(entry.getModelFile() != null ? entry.getModelFile() : "model.sdz");
                    if (!Files.exists(modelPath)) {
                        return ResponseEntity.notFound().<Void>build();
                    }
                    try {
                        org.springframework.core.io.Resource resource =
                                new org.springframework.core.io.FileSystemResource(modelPath);
                        return ResponseEntity.ok()
                                .header("Content-Disposition", "attachment; filename=\"" + modelPath.getFileName() + "\"")
                                .header("Content-Type", "application/octet-stream")
                                .body(resource);
                    } catch (Exception e) {
                        log.error("Failed to serve model file for '{}'", modelId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download a vocab file for a registered model.
     * Used by the main app's RegistryBasedModelManager to fetch vocab files from staging.
     */
    @GetMapping("/registry/model/{modelId}/download/vocab")
    public ResponseEntity<?> downloadVocabFile(@PathVariable String modelId) {
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelDir = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId);
                    String vocabFileName = entry.getVocabFile() != null ? entry.getVocabFile() : "vocab.txt";
                    Path vocabPath = modelDir.resolve(vocabFileName);
                    if (!Files.exists(vocabPath)) {
                        return ResponseEntity.notFound().<Void>build();
                    }
                    try {
                        org.springframework.core.io.Resource resource =
                                new org.springframework.core.io.FileSystemResource(vocabPath);
                        return ResponseEntity.ok()
                                .header("Content-Disposition", "attachment; filename=\"" + vocabPath.getFileName() + "\"")
                                .header("Content-Type", "application/octet-stream")
                                .body(resource);
                    } catch (Exception e) {
                        log.error("Failed to serve vocab file for '{}'", modelId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all files in a registered model's directory.
     * Clients use this to discover shard files for sharded models.
     */
    @GetMapping("/registry/model/{modelId}/files")
    public ResponseEntity<?> listModelFiles(@PathVariable String modelId) {
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelDir = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId);
                    if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
                        return ResponseEntity.notFound().<Object>build();
                    }
                    try {
                        List<Map<String, Object>> files = new java.util.ArrayList<>();
                        try (java.util.stream.Stream<Path> stream = Files.list(modelDir)) {
                            stream.filter(Files::isRegularFile).forEach(p -> {
                                try {
                                    Map<String, Object> info = new java.util.LinkedHashMap<>();
                                    info.put("name", p.getFileName().toString());
                                    info.put("size", Files.size(p));
                                    files.add(info);
                                } catch (IOException e) {
                                    log.debug("Could not read file size for {}: {}", p, e.getMessage());
                                }
                            });
                        }
                        Map<String, Object> result = new java.util.LinkedHashMap<>();
                        result.put("modelId", modelId);
                        result.put("files", files);
                        return ResponseEntity.ok((Object) result);
                    } catch (IOException e) {
                        log.error("Failed to list files for model '{}'", modelId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Object>build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Download any file by name from a registered model's directory.
     * Supports shard files, tokenizer files, etc.
     */
    @GetMapping("/registry/model/{modelId}/download/file/{fileName:.+}")
    public ResponseEntity<?> downloadFile(@PathVariable String modelId, @PathVariable String fileName) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelDir = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId);
                    Path filePath = modelDir.resolve(fileName);
                    if (!Files.exists(filePath) || !filePath.startsWith(modelDir)) {
                        return ResponseEntity.notFound().<Void>build();
                    }
                    try {
                        org.springframework.core.io.Resource resource =
                                new org.springframework.core.io.FileSystemResource(filePath);
                        return ResponseEntity.ok()
                                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                                .header("Content-Type", "application/octet-stream")
                                .body(resource);
                    } catch (Exception e) {
                        log.error("Failed to serve file '{}' for model '{}'", fileName, modelId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Catalog Endpoints ====================

    /**
     * Get the full model catalog (available models for download).
     */
    @GetMapping("/catalog")
    public ModelCatalog getCatalog() {
        return catalogService.getCatalog();
    }

    /**
     * Get a specific model from the catalog.
     */
    @GetMapping("/catalog/{modelId}")
    public ResponseEntity<CatalogModel> getCatalogModel(@PathVariable String modelId) {
        return catalogService.getModel(modelId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stage a model from the catalog by ID.
     */
    @PostMapping("/stage/catalog/{modelId}")
    public ResponseEntity<StagingModelInfo> stageFromCatalog(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "false") boolean autoPromote) {

        return catalogService.getModel(modelId)
                .map(catalogModel -> {
                    ModelType modelType = resolveModelType(catalogModel);

                    DownloadRequest.DownloadRequestBuilder dlBuilder = DownloadRequest.builder()
                            .source(catalogModel.getSource())
                            .repository(catalogModel.getRepo())
                            .modelId(catalogModel.getId())
                            .modelType(modelType)
                            .format(catalogModel.getFormat());
                    if (catalogModel.getFiles() != null && !catalogModel.getFiles().isEmpty()) {
                        dlBuilder.files(new java.util.HashMap<>(catalogModel.getFiles()));
                    }
                    DownloadRequest downloadRequest = dlBuilder.build();

                    // Start async staging
                    CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

                    // If auto-promote is requested, add completion handler
                    if (autoPromote) {
                        future.thenAccept(info -> {
                            if (info.getStatus() == ai.kompile.core.staging.StagingStatus.READY) {
                                stagingService.promoteModel(modelId, null);
                            }
                        });
                    }

                    // Return initial status immediately
                    StagingModelInfo initialStatus = StagingModelInfo.create(
                            catalogModel.getId(),
                            catalogModel.getSource() + ":" + catalogModel.getRepo(),
                            modelType);

                    return ResponseEntity.accepted().body(initialStatus);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Staging Endpoints ====================

    /**
     * Get all models in staging.
     */
    @GetMapping("/status")
    public StagingStatusResponse getStagingStatus() {
        List<StagingModelInfo> models = stagingService.getStagingModels();
        return StagingStatusResponse.builder()
                .connected(true)
                .modelsInStaging(models)
                .build();
    }

    /**
     * Get active models (one per type) for integration with the main app.
     * Returns a map of model type to active model ID.
     */
    @GetMapping("/active")
    public Map<String, Object> getActiveModels() {
        ModelRegistry registry = registryService.loadRegistry();
        Map<String, String> active = new LinkedHashMap<>();
        for (ModelEntry entry : registry.getActiveModels()) {
            if (entry.getType() != null) {
                active.put(entry.getType().getValue(), entry.getModelId());
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active", active);
        return response;
    }

    /**
     * Activate a specific model (deactivates other models of the same type).
     */
    @PostMapping("/models/{modelId}/activate")
    public ResponseEntity<Map<String, Object>> activateModel(@PathVariable String modelId) {
        log.info("Activating model: {}", modelId);
        Optional<ModelEntry> model = registryService.getModel(modelId);
        if (model.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ModelRegistry registry = registryService.loadRegistry();
        registry.setActiveModel(modelId);
        registryService.saveRegistry(registry);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Model " + modelId + " activated");
        response.put("modelId", modelId);
        response.put("type", model.get().getType() != null ? model.get().getType().getValue() : null);
        return ResponseEntity.ok(response);
    }

    /**
     * Normalize the registry to ensure only one model per type is active.
     * If multiple models of the same type are active, keeps the most recently promoted one.
     */
    @PostMapping("/normalize")
    public ResponseEntity<Map<String, Object>> normalizeRegistry() {
        log.info("Normalizing registry - ensuring one active model per type");
        ModelRegistry registry = registryService.loadRegistry();
        Map<String, String> changes = new LinkedHashMap<>();

        // Group active models by type
        Map<ModelType, List<ModelEntry>> activeByType = new LinkedHashMap<>();
        for (ModelEntry entry : registry.getActiveModels()) {
            activeByType.computeIfAbsent(entry.getType(), k -> new java.util.ArrayList<>()).add(entry);
        }

        // For types with multiple active models, keep only the most recently promoted
        for (Map.Entry<ModelType, List<ModelEntry>> typeEntry : activeByType.entrySet()) {
            List<ModelEntry> models = typeEntry.getValue();
            if (models.size() > 1) {
                // Sort by promotedAt descending, keep the first
                models.sort((a, b) -> {
                    String aTime = a.getPromotedAt() != null ? a.getPromotedAt() : "";
                    String bTime = b.getPromotedAt() != null ? b.getPromotedAt() : "";
                    return bTime.compareTo(aTime);
                });
                // Deactivate all except the first
                for (int i = 1; i < models.size(); i++) {
                    models.get(i).setStatus(ModelStatus.STAGED);
                    changes.put(models.get(i).getModelId(), "deactivated");
                }
                changes.put(models.get(0).getModelId(), "kept_active");
            }
        }

        if (!changes.isEmpty()) {
            registryService.saveRegistry(registry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("changes", changes);
        return ResponseEntity.ok(response);
    }

    /**
     * Get list of models currently in staging.
     */
    @GetMapping("/models")
    public List<StagingModelInfo> getModelsInStaging() {
        return stagingService.getStagingModels();
    }

    /**
     * Get a specific staged model by ID.
     */
    @GetMapping("/models/{modelId}")
    public ResponseEntity<StagingModelInfo> getStagedModel(@PathVariable String modelId) {
        StagingModelInfo info = stagingService.getStagingModel(modelId);
        if (info != null) {
            return ResponseEntity.ok(info);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Subscribe to real-time staging progress stream for a model (SSE).
     * Pushes status events as the model progresses through download, conversion, validation.
     */
    @GetMapping(value = "/models/{modelId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamStagingProgress(@PathVariable String modelId) {
        log.info("SSE subscription for staging progress: {}", modelId);
        return stagingService.subscribeToStagingStream(modelId);
    }

    /**
     * Cancel staging of a model (via /models endpoint).
     */
    @DeleteMapping("/models/{modelId}")
    public ResponseEntity<Map<String, Object>> cancelStagingModel(@PathVariable String modelId) {
        boolean cancelled = stagingService.cancelStaging(modelId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Staging cancelled"
            ));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Start staging a new model.
     */
    @PostMapping("/stage")
    public ResponseEntity<StagingModelInfo> stageModel(@RequestBody StageModelRequest request) {
        DownloadRequest.DownloadRequestBuilder builder = DownloadRequest.builder()
                .source(request.getSource())
                .repository(request.getRepository())
                .modelId(request.getModelId())
                .modelType(ModelType.fromValue(request.getType()))
                .format(request.getFormat())
                .revision(request.getRevision())
                .authToken(request.getAuthToken())
                .tokenizerUrl(request.getTokenizerUrl());
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            builder.files(new java.util.HashMap<>(request.getFiles()));
        }
        DownloadRequest downloadRequest = builder.build();

        // Start async staging
        CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

        // Return initial status immediately
        StagingModelInfo initialStatus = StagingModelInfo.create(
                request.getModelId(),
                request.getSource() + ":" + request.getRepository(),
                ModelType.fromValue(request.getType()));

        return ResponseEntity.accepted().body(initialStatus);
    }

    /**
     * Get staging status for a specific model.
     */
    @GetMapping("/status/{modelId}")
    public ResponseEntity<StagingModelInfo> getStagingStatus(@PathVariable String modelId) {
        StagingModelInfo info = stagingService.getStagingModel(modelId);
        if (info != null) {
            return ResponseEntity.ok(info);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Promote a staged model to production.
     */
    @PostMapping("/promote/{modelId}")
    public ResponseEntity<Map<String, Object>> promoteModel(
            @PathVariable String modelId,
            @RequestBody(required = false) PromoteModelRequest request) {

        ModelMetadata metadata = null;
        if (request != null) {
            metadata = ModelMetadata.builder()
                    .embeddingDim(request.getEmbeddingDim())
                    .hiddenSize(request.getHiddenSize())
                    .numLayers(request.getNumLayers())
                    .maxSequenceLength(request.getMaxSequenceLength())
                    .description(request.getDescription())
                    .framework("samediff")
                    .build();
        }

        boolean success = stagingService.promoteModel(modelId, metadata);

        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Model promoted to production",
                    "modelId", modelId
            ));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "error", "Failed to promote model. Check if it's in ready state."
            ));
        }
    }

    /**
     * Cancel a staging operation.
     */
    @DeleteMapping("/status/{modelId}")
    public ResponseEntity<Map<String, Object>> cancelStaging(@PathVariable String modelId) {
        boolean cancelled = stagingService.cancelStaging(modelId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Staging cancelled"
            ));
        }
        return ResponseEntity.notFound().build();
    }

    // ==================== Export/Import Endpoints ====================

    /**
     * Export models to a bundle.
     */
    @PostMapping("/export")
    public ResponseEntity<Map<String, Object>> exportModels(@RequestBody ExportRequest request) {
        Path outputPath = request.getOutputPath() != null
                ? Paths.get(request.getOutputPath())
                : Paths.get(exportService.generateBundleFilename());

        ExportService.ExportResult result;
        if (request.isExportAll()) {
            result = exportService.exportAll(outputPath);
        } else {
            result = exportService.export(request.getModelIds(), outputPath, request.getDescription());
        }

        if (result.isSuccess()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "bundlePath", result.getBundlePath().toString(),
                    "modelCount", result.getModelCount(),
                    "bundleSize", result.getBundleSize()
            ));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", result.getErrorMessage()
            ));
        }
    }

    /**
     * Import a model bundle.
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importBundle(@RequestBody ImportRequest request) {
        Path bundlePath = Paths.get(request.getBundlePath());

        ImportService.ImportResult result = importService.importBundle(
                bundlePath, request.isVerifyChecksums());

        if (result.isSuccess()) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "importedCount", result.getImportedCount(),
                    "totalCount", result.getTotalCount()
            ));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", result.getErrorMessage()
            ));
        }
    }

    // ==================== Auto-Optimization Configuration ====================

    /**
     * Get the current auto-optimization configuration.
     */
    @GetMapping("/config/auto-optimize")
    public ResponseEntity<Map<String, Object>> getAutoOptimizeConfig() {
        StageWithOptimizationRequest.OptimizationConfigDto config = stagingService.getAutoOptimizationConfig();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", config != null);
        response.put("config", config);
        return ResponseEntity.ok(response);
    }

    /**
     * Set or clear the auto-optimization configuration.
     * When set, models staged via catalog will be automatically optimized after validation.
     */
    @PostMapping("/config/auto-optimize")
    public ResponseEntity<Map<String, Object>> setAutoOptimizeConfig(
            @RequestBody(required = false) StageWithOptimizationRequest.OptimizationConfigDto config) {
        stagingService.setAutoOptimizationConfig(config);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("enabled", config != null);
        response.put("config", config);
        return ResponseEntity.ok(response);
    }

    /**
     * Clear the auto-optimization configuration.
     */
    @DeleteMapping("/config/auto-optimize")
    public ResponseEntity<Map<String, Object>> clearAutoOptimizeConfig() {
        stagingService.setAutoOptimizationConfig(null);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", false
        ));
    }

    /**
     * Stage a model from the catalog with optional optimization configuration.
     */
    @PostMapping("/stage/catalog/{modelId}/with-optimization")
    public ResponseEntity<StagingModelInfo> stageFromCatalogWithOptimization(
            @PathVariable String modelId,
            @RequestBody StageWithOptimizationRequest request) {

        return catalogService.getModel(modelId)
                .map(catalogModel -> {
                    ModelType modelType = resolveModelType(catalogModel);

                    DownloadRequest.DownloadRequestBuilder dlBuilder = DownloadRequest.builder()
                            .source(catalogModel.getSource())
                            .repository(catalogModel.getRepo())
                            .modelId(catalogModel.getId())
                            .modelType(modelType)
                            .format(catalogModel.getFormat());
                    if (catalogModel.getFiles() != null && !catalogModel.getFiles().isEmpty()) {
                        dlBuilder.files(new java.util.HashMap<>(catalogModel.getFiles()));
                    }
                    DownloadRequest downloadRequest = dlBuilder.build();

                    CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

                    if (request.isAutoPromote()) {
                        future.thenAccept(info -> {
                            if (info.getStatus() == ai.kompile.core.staging.StagingStatus.READY) {
                                stagingService.promoteModel(modelId, null);
                            }
                        });
                    }

                    // Store the per-request optimization config for this model staging
                    if (request.getOptimizationConfig() != null) {
                        stagingService.setAutoOptimizationConfig(request.getOptimizationConfig());
                    }

                    StagingModelInfo initialStatus = StagingModelInfo.create(
                            catalogModel.getId(),
                            catalogModel.getSource() + ":" + catalogModel.getRepo(),
                            modelType);

                    return ResponseEntity.accepted().body(initialStatus);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Helper Methods ====================

    /**
     * Resolve the ModelType for a catalog model by checking which catalog list it belongs to,
     * or falling back to the modelType field on the CatalogModel itself.
     */
    private ModelType resolveModelType(CatalogModel catalogModel) {
        // Check explicit modelType field first
        if (catalogModel.getModelType() != null) {
            try {
                return ModelType.fromValue(catalogModel.getModelType());
            } catch (Exception e) {
                log.debug("Unknown modelType '{}', falling back to catalog list detection", catalogModel.getModelType());
            }
        }

        // Fall back to catalog list membership
        if (catalogService.getVlm().contains(catalogModel)) {
            return ModelType.VLM_PIPELINE;
        }
        if (catalogService.getEncoders().contains(catalogModel)) {
            return ModelType.ENCODER;
        }
        if (catalogService.getCrossEncoders().contains(catalogModel)) {
            return ModelType.CROSS_ENCODER;
        }

        // Default
        return ModelType.ENCODER;
    }

    // ==================== Cleanup Endpoints ====================

    /**
     * Clean up failed staging attempts.
     */
    @DeleteMapping("/cleanup/failed")
    public ResponseEntity<Map<String, Object>> cleanupFailed() {
        int count = stagingService.cleanupFailed();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "cleanedUp", count
        ));
    }

    // ==================== Model Source Configuration Endpoints ====================

    /**
     * Get model source configuration status.
     */
    @GetMapping("/config/source")
    public ResponseEntity<Map<String, Object>> getModelSourceConfig() {
        ArchiveModelManager.ArchiveStatus archiveStatus = archiveModelManager.getStatus();

        Map<String, Object> archiveInfo = new LinkedHashMap<>();
        archiveInfo.put("initialized", archiveStatus.isInitialized());
        archiveInfo.put("loaded", archiveStatus.isArchiveLoaded());
        archiveInfo.put("archiveId", archiveStatus.getArchiveId() != null ? archiveStatus.getArchiveId() : "");
        archiveInfo.put("archiveVersion", archiveStatus.getArchiveVersion() != null ? archiveStatus.getArchiveVersion() : "");
        archiveInfo.put("totalModels", archiveStatus.getTotalModels());
        archiveInfo.put("extractedModels", archiveStatus.getExtractedModels());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", modelSourceConfig.getSourceType().name());
        result.put("hasArchiveSource", modelSourceConfig.hasArchiveSource());
        result.put("hasRegistrySource", modelSourceConfig.hasRegistrySource());
        result.put("archiveOnly", modelSourceConfig.isArchiveOnly());
        result.put("archivePath", modelSourceConfig.getArchivePath() != null ? modelSourceConfig.getArchivePath() : "");
        result.put("embeddedArchive", modelSourceConfig.getEmbeddedArchive() != null ? modelSourceConfig.getEmbeddedArchive() : "");
        result.put("registryUrls", modelSourceConfig.getRegistryUrls() != null ? modelSourceConfig.getRegistryUrls() : List.of());
        result.put("cacheDir", modelSourceConfig.getEffectiveCacheDir());
        result.put("verifyChecksums", modelSourceConfig.isVerifyChecksums());
        result.put("allowFallback", modelSourceConfig.isAllowFallback());
        result.put("archive", archiveInfo);

        return ResponseEntity.ok(result);
    }

    /**
     * Get archive status.
     */
    @GetMapping("/config/archive/status")
    public ResponseEntity<ArchiveModelManager.ArchiveStatus> getArchiveStatus() {
        return ResponseEntity.ok(archiveModelManager.getStatus());
    }

    /**
     * Get models available from the archive.
     */
    @GetMapping("/config/archive/models")
    public ResponseEntity<List<ModelEntry>> getArchiveModels() {
        return ResponseEntity.ok(archiveModelManager.getAllModels());
    }

    /**
     * Load an archive from a file path.
     */
    @PostMapping("/config/archive/load")
    public ResponseEntity<Map<String, Object>> loadArchive(@RequestBody Map<String, String> request) {
        String archivePath = request.get("archivePath");
        if (archivePath == null || archivePath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "archivePath is required"
            ));
        }

        try {
            archiveModelManager.loadArchiveFile(Paths.get(archivePath));
            ArchiveModelManager.ArchiveStatus status = archiveModelManager.getStatus();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Archive loaded successfully",
                    "archiveId", status.getArchiveId() != null ? status.getArchiveId() : "",
                    "archiveVersion", status.getArchiveVersion() != null ? status.getArchiveVersion() : "",
                    "totalModels", status.getTotalModels(),
                    "extractedModels", status.getExtractedModels()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to load archive: " + e.getMessage()
            ));
        }
    }

    // ==================== File Upload Endpoints ====================

    /**
     * Upload a model file for conversion/staging.
     * The file is saved to the staging directory with a unique name.
     *
     * @param file The model file to upload (ONNX, TensorFlow, Keras, etc.)
     * @return The server-side path where the file was saved
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadModelFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "No file provided"
            ));
        }

        try {
            // Get staging directory from config
            Path stagingDir = stagingService.getStagingDirectory();
            Path uploadsDir = stagingDir.resolve("uploads");
            Files.createDirectories(uploadsDir);

            // Generate unique filename to avoid collisions
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueFilename = UUID.randomUUID().toString() + extension;
            Path destPath = uploadsDir.resolve(uniqueFilename);

            // Save the file
            Files.copy(file.getInputStream(), destPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Uploaded model file: {} -> {} ({} bytes)",
                    originalFilename, destPath, file.getSize());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filePath", destPath.toAbsolutePath().toString(),
                    "originalFilename", originalFilename != null ? originalFilename : uniqueFilename,
                    "size", file.getSize()
            ));

        } catch (IOException e) {
            log.error("Failed to upload file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to save file: " + e.getMessage()
            ));
        }
    }

    /**
     * Upload and immediately stage a model file.
     * This combines upload + stage into a single operation.
     *
     * @param file The model file to upload
     * @param modelId The model ID to use
     * @param modelType The model type (dense_encoder, sparse_encoder, cross_encoder)
     * @param format The model format (onnx, tensorflow, keras)
     * @param autoPromote Whether to auto-promote after staging
     * @return Staging info for the model
     */
    @PostMapping("/upload-and-stage")
    public ResponseEntity<Map<String, Object>> uploadAndStageModel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("modelId") String modelId,
            @RequestParam(value = "modelType", defaultValue = "dense_encoder") String modelType,
            @RequestParam(value = "format", defaultValue = "onnx") String format,
            @RequestParam(value = "autoPromote", defaultValue = "false") boolean autoPromote) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "No file provided"
            ));
        }

        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "modelId is required"
            ));
        }

        try {
            // First upload the file
            Path stagingDir = stagingService.getStagingDirectory();
            Path uploadsDir = stagingDir.resolve("uploads");
            Files.createDirectories(uploadsDir);

            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueFilename = modelId + "-" + System.currentTimeMillis() + extension;
            Path destPath = uploadsDir.resolve(uniqueFilename);

            Files.copy(file.getInputStream(), destPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Uploaded model file for staging: {} -> {} ({} bytes)",
                    originalFilename, destPath, file.getSize());

            // Now stage the model (this will trigger conversion)
            StagingModelInfo stagingInfo = stagingService.stageLocalModel(
                    modelId,
                    destPath.toAbsolutePath().toString(),
                    format,
                    autoPromote
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filePath", destPath.toAbsolutePath().toString(),
                    "modelId", modelId,
                    "status", stagingInfo.getStatus().name(),
                    "message", stagingInfo.getMessage() != null ? stagingInfo.getMessage() : "Staging started"
            ));

        } catch (IOException e) {
            log.error("Failed to upload and stage model: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to upload and stage: " + e.getMessage()
            ));
        }
    }

    // ==================== Conversion Endpoints ====================

    /**
     * Convert a model file to SameDiff format.
     * This endpoint is called after uploading a file via /upload.
     *
     * @param request The conversion request with inputPath, format, modelId, etc.
     * @return Staging info for the converted model
     */
    @PostMapping("/convert")
    public ResponseEntity<Map<String, Object>> convertModel(@RequestBody ConvertModelRequest request) {
        if (request.getInputPath() == null || request.getInputPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "inputPath is required"
            ));
        }

        if (request.getModelId() == null || request.getModelId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "modelId is required"
            ));
        }

        try {
            String format = request.getFormat() != null ? request.getFormat() : "onnx";
            boolean autoPromote = request.isAutoPromote();

            log.info("Converting model: {} from {} (format: {})",
                    request.getModelId(), request.getInputPath(), format);

            // Stage the local model (this triggers conversion)
            StagingModelInfo stagingInfo = stagingService.stageLocalModel(
                    request.getModelId(),
                    request.getInputPath(),
                    format,
                    autoPromote
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("modelId", request.getModelId());
            response.put("status", stagingInfo.getStatus().name());
            response.put("progress", stagingInfo.getProgress());
            response.put("message", stagingInfo.getMessage() != null ? stagingInfo.getMessage() : "Conversion started");
            response.put("data", stagingInfo);

            return ResponseEntity.accepted().body(response);

        } catch (Exception e) {
            log.error("Failed to start conversion: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to start conversion: " + e.getMessage()
            ));
        }
    }

    /**
     * Request DTO for model conversion.
     */
    public static class ConvertModelRequest {
        private String inputPath;
        private String format;
        private String modelId;
        private String type;
        private String outputPath;
        private boolean autoStage = true;
        private boolean autoPromote = false;
        private Map<String, Object> metadata;

        public String getInputPath() { return inputPath; }
        public void setInputPath(String inputPath) { this.inputPath = inputPath; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getOutputPath() { return outputPath; }
        public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
        public boolean isAutoStage() { return autoStage; }
        public void setAutoStage(boolean autoStage) { this.autoStage = autoStage; }
        public boolean isAutoPromote() { return autoPromote; }
        public void setAutoPromote(boolean autoPromote) { this.autoPromote = autoPromote; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
