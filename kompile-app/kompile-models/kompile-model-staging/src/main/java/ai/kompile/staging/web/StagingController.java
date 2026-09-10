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
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.catalog.ModelCatalog;
import ai.kompile.staging.config.ModelSourceConfiguration;
import ai.kompile.staging.config.StagingSettings;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.diagnostics.ImportDiagnosticEvent;
import ai.kompile.staging.download.ComponentUrlDownloader;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.HuggingFaceReference;
import ai.kompile.staging.download.TextModelAssetMap;
import ai.kompile.staging.download.TextModelAssetUrlMap;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.staging.staging.ModelIdPolicy;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.sdx.SdxProjectOutputService;
import org.nd4j.dsp.model.SdxTargetProfile;
import ai.kompile.staging.web.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * REST API controller for the model staging service.
 */
@RestController
@RequestMapping("/api/staging")
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
    private final StagingAssetLimits assetLimits;

    @Value("${kompile.staging.project-dir:}")
    private String projectDir;

    @Value("${kompile.staging.settings-dir:${kompile.home:${user.home}/.kompile}}")
    private String settingsDir;

    public StagingController(RegistryService registryService,
                            StagingService stagingService,
                            ExportService exportService,
                            ImportService importService,
                            CatalogService catalogService,
                            ArchiveModelManager archiveModelManager,
                            ModelSourceConfiguration modelSourceConfig,
                            StagingSettingsService stagingSettingsService) {
        this(registryService, stagingService, exportService, importService, catalogService,
                archiveModelManager, modelSourceConfig, stagingSettingsService,
                new StagingAssetLimits());
    }

    @Autowired
    public StagingController(RegistryService registryService,
                            StagingService stagingService,
                            ExportService exportService,
                            ImportService importService,
                            CatalogService catalogService,
                            ArchiveModelManager archiveModelManager,
                            ModelSourceConfiguration modelSourceConfig,
                            StagingSettingsService stagingSettingsService,
                            StagingAssetLimits assetLimits) {
        this.registryService = registryService;
        this.stagingService = stagingService;
        this.exportService = exportService;
        this.importService = importService;
        this.catalogService = catalogService;
        this.archiveModelManager = archiveModelManager;
        this.modelSourceConfig = modelSourceConfig;
        this.stagingSettingsService = stagingSettingsService;
        this.assetLimits = assetLimits == null ? new StagingAssetLimits() : assetLimits;
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
        if (settings == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settings body is required");
        }
        String callbackUrl = settings.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            settings.setCallbackUrl(null);
        } else {
            try {
                settings.setCallbackUrl(ServiceEndpointsConfigManager.requireHttpBaseUrl(
                        "callback_url", callbackUrl));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
            }
        }
        if (settings.getCallbackTimeoutMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "callback_timeout_ms must be greater than zero");
        }
        return stagingSettingsService.updateSettings(settings);
    }

    /** Test the UI/CLI-managed callback dependency without changing it. */
    @PostMapping("/settings/test-callback")
    public StagingSettingsService.CallbackTestResult testSettingsCallback() {
        return stagingSettingsService.testCallback();
    }

    /**
     * Browse the newest sanitized import diagnostics across recent attempts.
     */
    @GetMapping("/import-diagnostics")
    public List<ImportDiagnosticEvent> getImportDiagnostics(
            @RequestParam(defaultValue = "50") int limit) {
        return stagingService.getImportDiagnostics(limit);
    }

    /**
     * Browse the retained event sequence for one import attempt.
     */
    @GetMapping("/import-diagnostics/{attemptId}")
    public List<ImportDiagnosticEvent> getImportDiagnostics(
            @PathVariable String attemptId) {
        return stagingService.getImportDiagnostics(attemptId);
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
        requireModelIdPath(modelId);
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
        requireModelIdPath(modelId);
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
        requireModelIdPath(modelId);
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
                        Path modelDir = registryService.getModelsDir()
                                .resolve(entry.getPath());
                        Path modelFile = modelDir.resolve(entry.getModelFile());
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
        requireModelIdPath(modelId);
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
        requireModelIdPath(modelId);
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelPath = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId)
                            .resolve(entry.getModelFile() != null ? entry.getModelFile() : "model.sdz");
                    if (!Files.exists(modelPath)) {
                        return ResponseEntity.notFound().<Void>build();
                    }
                    try {
                        Resource resource =
                                new FileSystemResource(modelPath);
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
        requireModelIdPath(modelId);
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
                        Resource resource =
                                new FileSystemResource(vocabPath);
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
        requireModelIdPath(modelId);
        return registryService.getModel(modelId)
                .map(entry -> {
                    Path modelDir = registryService.getModelDir().resolve(
                            entry.getPath() != null ? entry.getPath() : modelId);
                    if (!Files.exists(modelDir) || !Files.isDirectory(modelDir)) {
                        return ResponseEntity.notFound().<Object>build();
                    }
                    try {
                        List<Map<String, Object>> files = new ArrayList<>();
                        try (Stream<Path> stream = Files.list(modelDir)) {
                            stream.filter(Files::isRegularFile).forEach(p -> {
                                try {
                                    Map<String, Object> info = new LinkedHashMap<>();
                                    info.put("name", p.getFileName().toString());
                                    info.put("size", Files.size(p));
                                    files.add(info);
                                } catch (IOException e) {
                                    log.debug("Could not read file size for {}: {}", p, e.getMessage());
                                }
                            });
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
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
        requireModelIdPath(modelId);
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
                        Resource resource =
                                new FileSystemResource(filePath);
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
                            .format(catalogModel.getFormat())
                            .audioSynthesis(catalogModel.getAudioSynthesis());
                    if (catalogModel.getFiles() != null && !catalogModel.getFiles().isEmpty()) {
                        dlBuilder.files(new HashMap<>(catalogModel.getFiles()));
                    }
                    if (catalogModel.getAssetUrls() != null && !catalogModel.getAssetUrls().isEmpty()) {
                        dlBuilder.textAssetUrls(TextModelAssetUrlMap.fromUrlMap(
                                new HashMap<>(catalogModel.getAssetUrls())));
                    }
                    DownloadRequest downloadRequest = dlBuilder.build();

                    // Start async staging
                    CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

                    // If auto-promote is requested, add completion handler
                    if (autoPromote) {
                        future.thenAccept(info -> {
                            if (info.getStatus() == StagingStatus.READY
                                    || info.getStatus() == StagingStatus.COMPLETED) {
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

        registryService.updateRegistry(registry -> registry.setActiveModel(modelId));

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
        Map<String, String> changes = new LinkedHashMap<>();
        registryService.updateRegistry(registry -> {
            Map<ModelType, List<ModelEntry>> activeByType = new LinkedHashMap<>();
            for (ModelEntry entry : registry.getActiveModels()) {
                activeByType.computeIfAbsent(entry.getType(), k -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<ModelType, List<ModelEntry>> typeEntry : activeByType.entrySet()) {
                List<ModelEntry> models = typeEntry.getValue();
                if (models.size() > 1) {
                    models.sort((a, b) -> {
                        String aTime = a.getPromotedAt() != null ? a.getPromotedAt() : "";
                        String bTime = b.getPromotedAt() != null ? b.getPromotedAt() : "";
                        return bTime.compareTo(aTime);
                    });
                    for (int i = 1; i < models.size(); i++) {
                        models.get(i).setStatus(ModelStatus.STAGED);
                        changes.put(models.get(i).getModelId(), "deactivated");
                    }
                    changes.put(models.get(0).getModelId(), "kept_active");
                }
            }
        });

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
        requireModelIdPath(modelId);
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
        requireModelIdPath(modelId);
        log.info("SSE subscription for staging progress: {}", modelId);
        return stagingService.subscribeToStagingStream(modelId);
    }

    /**
     * Cancel staging of a model (via /models endpoint).
     */
    @DeleteMapping("/models/{modelId}")
    public ResponseEntity<Map<String, Object>> cancelStagingModel(@PathVariable String modelId) {
        requireModelIdPath(modelId);
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
        DownloadRequest downloadRequest = toDownloadRequest(request);
        stagingService.stageModelAsync(downloadRequest);
        return acceptedStatus(downloadRequest);
    }

    private DownloadRequest toDownloadRequest(StageModelRequest request) {
        if (request == null) {
            throw badRequest("Staging request is required.", null);
        }
        String modelId = requireModelIdPath(request.getModelId());
        if (request.getSource() == null || request.getSource().isBlank()) {
            throw badRequest("source is required.", null);
        }
        String normalizedSource = request.getSource().trim().toLowerCase(Locale.ROOT);
        boolean componentSource = ComponentUrlDownloader.isComponentSource(normalizedSource);
        if (!componentSource
                && (request.getRepository() == null || request.getRepository().isBlank())) {
            throw badRequest("repository is required.", null);
        }
        if (componentSource
                && request.getRepository() != null
                && !request.getRepository().isBlank()) {
            throw badRequest(
                    "https-components is repository-free; remove repository.", null);
        }

        if ("trusted-local".equals(normalizedSource)) {
            throw badRequest("trusted-local is reserved for internal registry staging.", null);
        }
        if ("huggingface".equals(normalizedSource) || "hf".equals(normalizedSource)) {
            try {
                HuggingFaceReference.parse(request.getRepository(), request.getRevision());
            } catch (IllegalArgumentException invalidRepository) {
                throw badRequest(invalidRepository.getMessage(), invalidRepository);
            }
        } else if ("local".equals(normalizedSource)
                && !request.getRepository().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw badRequest("Local staging requires an opaque upload handle.", null);
        } else if (componentSource
                && ((request.getRevision() != null && !request.getRevision().isBlank())
                    || (request.getAuthToken() != null && !request.getAuthToken().isBlank()))) {
            throw badRequest(
                    "https-components accepts only public URLs and no revision or auth token.",
                    null);
        }

        try {
            String outputFormat =
                    SdxProjectOutputService.normalizeOutputFormat(request.getOutputFormat());
            String targetProfile = request.getTargetProfile();
            String quantizationProfile =
                    SdxProjectOutputService.normalizeQuantization(
                            request.getQuantizationProfile());
            String targetSoc = request.getTargetSoc();
            ModelType modelType = ModelType.fromValue(request.getType());

            TextModelAssetMap textAssets = request.getTextAssets();
            if (textAssets == null && request.getFiles() != null) {
                textAssets = TextModelAssetMap.fromFileMap(request.getFiles());
            }

            boolean targetOutput =
                    SdxProjectOutputService.OUTPUT_KPROJECT.equals(outputFormat)
                            || (targetProfile != null && !targetProfile.isBlank());
            if (targetOutput) {
                targetProfile =
                        SdxProjectOutputService.normalizeTargetProfile(targetProfile);
                targetSoc = SdxProjectOutputService.normalizeTargetSoc(
                        SdxTargetProfile.fromId(targetProfile),
                        targetSoc);
                textAssets = validateRunnableTextSource(request, textAssets);
            }

            DownloadRequest.DownloadRequestBuilder builder = DownloadRequest.builder()
                    .source(request.getSource())
                    .repository(request.getRepository())
                    .modelId(modelId)
                    .modelType(modelType)
                    .format(request.getFormat())
                    .revision(request.getRevision())
                    .authToken(request.getAuthToken())
                    .tokenizerUrl(request.getTokenizerUrl())
                    .audioSynthesis(request.getAudioSynthesis())
                    .outputFormat(outputFormat)
                    .targetProfile(targetProfile)
                    .quantizationProfile(quantizationProfile)
                    .targetSoc(targetSoc)
                    .textAssets(textAssets)
                    .textAssetUrls(request.getTextAssetUrls());
            if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                builder.files(new HashMap<>(request.getFiles()));
            }
            return builder.build();
        } catch (ResponseStatusException alreadyMapped) {
            throw alreadyMapped;
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage(), invalid);
        }
    }

    private TextModelAssetMap validateRunnableTextSource(
            StageModelRequest request,
            TextModelAssetMap declaredAssets) {
        String source = request.getSource().trim().toLowerCase(Locale.ROOT);
        boolean componentSource = ComponentUrlDownloader.isComponentSource(source);
        if (!"huggingface".equals(source)
                && !"hf".equals(source)
                && !"local".equals(source)
                && !componentSource) {
            throw badRequest(
                    "Offline chat packages accept a pinned Hugging Face repository or a local "
                            + "multipart text bundle, or a complete public HTTPS component bundle.",
                    null);
        }

        TextModelAssetMap assets = declaredAssets == null
                ? new TextModelAssetMap()
                : declaredAssets;
        if ("huggingface".equals(source) || "hf".equals(source)) {
            // Repository URLs and mutable branch/tag names are resolved through the
            // Hugging Face API by the downloader before any asset download. An empty
            // declaration intentionally requests discovery; multiple model candidates
            // fail closed until one exact path is selected.
            return assets;
        }
        if (componentSource) {
            if (!assets.toFileMap().isEmpty()
                    || (request.getFiles() != null && !request.getFiles().isEmpty())) {
                throw badRequest(
                        "https-components accepts absolute component URLs only, not repository paths.",
                        null);
            }
            TextModelAssetUrlMap urls = request.getTextAssetUrls();
            List<String> missing = urls == null
                    ? List.of("complete textAssetUrls bundle")
                    : urls.missingRunnableChatAssets();
            if (!missing.isEmpty()) {
                throw badRequest(
                        "Runnable HTTPS component staging is missing: "
                                + String.join(", ", missing) + ".",
                        null);
            }
            return assets;
        }

        List<String> missing = assets.missingRunnableChatAssets();
        if (!missing.isEmpty()) {
            throw badRequest(
                    "Runnable mobile chat staging is missing: " + String.join(", ", missing) + ".",
                    null);
        }
        return assets;
    }

    private ResponseEntity<StagingModelInfo> acceptedStatus(DownloadRequest request) {
        String source = ComponentUrlDownloader.isComponentSource(request.getSource())
                ? ComponentUrlDownloader.SOURCE
                : request.getSource() + ":" + request.getRepository();
        StagingModelInfo initialStatus = StagingModelInfo.create(
                request.getModelId(),
                source,
                request.getModelType());
        return ResponseEntity.accepted().body(initialStatus);
    }

    private static String requireModelIdPath(String modelId) {
        try {
            return ModelIdPolicy.requireValid(modelId);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(invalid.getMessage(), invalid);
        }
    }

    private static ResponseStatusException badRequest(String message, Throwable cause) {
        return cause == null
                ? new ResponseStatusException(HttpStatus.BAD_REQUEST, message)
                : new ResponseStatusException(HttpStatus.BAD_REQUEST, message, cause);
    }

    /**
     * Get staging status for a specific model.
     */
    @GetMapping("/status/{modelId}")
    public ResponseEntity<StagingModelInfo> getStagingStatus(@PathVariable String modelId) {
        requireModelIdPath(modelId);
        StagingModelInfo info = stagingService.getStagingModel(modelId);
        if (info != null) {
            return ResponseEntity.ok(info);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Download the completed target SDZ or canonical offline project. Compilation remains
     * asynchronous through /stage; incomplete, failed, or ambiguous outputs are not served.
     */
    @GetMapping("/models/{modelId}/output")
    public ResponseEntity<Resource> downloadStagedOutput(@PathVariable String modelId) {
        requireModelIdPath(modelId);
        Optional<Path> output = stagingService.getStagedOutput(modelId);
        if (output.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = output.get();
        try {
            String fileName = path.getFileName().toString();
            String contentType = fileName.toLowerCase(Locale.ROOT).endsWith(".kproject")
                    ? "application/vnd.kompile.project+zip"
                    : "application/vnd.kompile.sdx+zip";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(Files.size(path))
                    .header(
                            "Content-Disposition",
                            "attachment; filename=\"" + fileName + "\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new FileSystemResource(path));
        } catch (IOException e) {
            log.error("Failed to serve mobile artifact for {}", modelId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Promote a staged model to production.
     */
    @PostMapping("/promote/{modelId}")
    public ResponseEntity<Map<String, Object>> promoteModel(
            @PathVariable String modelId,
            @RequestBody(required = false) PromoteModelRequest request) {

        requireModelIdPath(modelId);
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
     * Stage a completed training artifact manifest for deployment.
     */
    @PostMapping("/training-artifacts/stage")
    public ResponseEntity<Map<String, Object>> stageTrainingArtifact(
            @RequestBody TrainingArtifactStageRequest request) {
        try {
            StagingModelInfo info = stagingService.stageTrainingArtifact(request);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "modelId", info.getModelId(),
                    "status", String.valueOf(info.getStatus()),
                    "staging", info
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Failed to stage training artifact", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to stage training artifact"
            ));
        }
    }

    /**
     * Cancel a staging operation.
     */
    @DeleteMapping("/status/{modelId}")
    public ResponseEntity<Map<String, Object>> cancelStaging(@PathVariable String modelId) {
        requireModelIdPath(modelId);
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
                            .format(catalogModel.getFormat())
                            .audioSynthesis(catalogModel.getAudioSynthesis());
                    if (catalogModel.getFiles() != null && !catalogModel.getFiles().isEmpty()) {
                        dlBuilder.files(new HashMap<>(catalogModel.getFiles()));
                    }
                    if (catalogModel.getAssetUrls() != null && !catalogModel.getAssetUrls().isEmpty()) {
                        dlBuilder.textAssetUrls(TextModelAssetUrlMap.fromUrlMap(
                                new HashMap<>(catalogModel.getAssetUrls())));
                    }
                    DownloadRequest downloadRequest = dlBuilder.build();

                    CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

                    if (request.isAutoPromote()) {
                        future.thenAccept(info -> {
                            if (info.getStatus() == StagingStatus.READY
                                    || info.getStatus() == StagingStatus.COMPLETED) {
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

        String modelId = catalogModel.getId();
        if (catalogContainsId(catalogService.getAudioSynthesis(), modelId)) {
            return ModelType.AUDIO_SYNTHESIS;
        }
        if (catalogContainsId(catalogService.getLlm(), modelId)) {
            return ModelType.LLM_GGML;
        }
        if (catalogContainsId(catalogService.getVlm(), modelId)) {
            return ModelType.VLM_PIPELINE;
        }
        if (catalogContainsId(catalogService.getCrossEncoders(), modelId)) {
            return ModelType.CROSS_ENCODER;
        }
        if (catalogContainsId(catalogService.getEncoders(), modelId)) {
            return ModelType.DENSE_ENCODER;
        }

        String format = catalogModel.getFormat() != null
                ? catalogModel.getFormat().trim().toLowerCase(Locale.ROOT) : "";
        if ("gguf".equals(format) || "ggml".equals(format)) {
            return ModelType.LLM_GGML;
        }
        if ("vlm".equals(format) || "vlm_pipeline".equals(format)) {
            return ModelType.VLM_PIPELINE;
        }

        // Default
        return ModelType.DENSE_ENCODER;
    }

    private boolean catalogContainsId(List<CatalogModel> models, String modelId) {
        if (models == null || modelId == null) {
            return false;
        }
        return models.stream().anyMatch(model -> modelId.equals(model.getId()));
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
     * Atomically upload every source artifact needed to produce a runnable offline SDX chat
     * project. Multipart names are the same stable keys used by {@link TextModelAssetMap}.
     */
    @PostMapping(
            value = "/stage/text-bundle",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StagingModelInfo> stageTextBundle(
            @RequestPart("request") StageModelRequest request,
            @RequestPart(TextModelAssetMap.MODEL) MultipartFile model,
            @RequestPart(TextModelAssetMap.TOKENIZER) MultipartFile tokenizer,
            @RequestPart(value = TextModelAssetMap.TOKENIZER_CONFIG, required = false)
                    MultipartFile tokenizerConfig,
            @RequestPart(value = TextModelAssetMap.SPECIAL_TOKENS_MAP, required = false)
                    MultipartFile specialTokensMap,
            @RequestPart(value = TextModelAssetMap.ADDED_TOKENS, required = false)
                    MultipartFile addedTokens,
            @RequestPart(value = TextModelAssetMap.CHAT_TEMPLATE, required = false)
                    MultipartFile chatTemplate,
            @RequestPart(value = TextModelAssetMap.GENERATION_CONFIG, required = false)
                    MultipartFile generationConfig,
            @RequestPart(value = TextModelAssetMap.MODEL_CONFIG, required = false)
                    MultipartFile modelConfig,
            @RequestPart(value = TextModelAssetMap.TEXT_GENERATION, required = false)
                    MultipartFile textGeneration) {
        Path bundleDir = null;
        String uploadHandle = UUID.randomUUID().toString();
        try {
            bundleDir = stagingService.getStagingDirectory()
                    .resolve("uploads")
                    .resolve("text-bundles")
                    .resolve(uploadHandle)
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(bundleDir);

            String modelFile = localModelFileName(request.getFormat());
            TextModelAssetMap assets = TextModelAssetMap.builder()
                    .model(saveBundlePart(bundleDir, model, modelFile, true))
                    .tokenizer(saveBundlePart(
                            bundleDir,
                            tokenizer,
                            TextModelAssetMap.TOKENIZER_FILE,
                            true))
                    .tokenizerConfig(saveBundlePart(
                            bundleDir,
                            tokenizerConfig,
                            TextModelAssetMap.TOKENIZER_CONFIG_FILE,
                            false))
                    .specialTokensMap(saveBundlePart(
                            bundleDir,
                            specialTokensMap,
                            TextModelAssetMap.SPECIAL_TOKENS_MAP_FILE,
                            false))
                    .addedTokens(saveBundlePart(
                            bundleDir,
                            addedTokens,
                            TextModelAssetMap.ADDED_TOKENS_FILE,
                            false))
                    .chatTemplate(saveBundlePart(
                            bundleDir,
                            chatTemplate,
                            TextModelAssetMap.CHAT_TEMPLATE_FILE,
                            false))
                    .generationConfig(saveBundlePart(
                            bundleDir,
                            generationConfig,
                            TextModelAssetMap.GENERATION_CONFIG_FILE,
                            false))
                    .modelConfig(saveBundlePart(
                            bundleDir,
                            modelConfig,
                            TextModelAssetMap.MODEL_CONFIG_FILE,
                            false))
                    .textGeneration(saveBundlePart(
                            bundleDir,
                            textGeneration,
                            TextModelAssetMap.TEXT_GENERATION_FILE,
                            false))
                    .build();

            List<String> missing = assets.missingRunnableChatAssets();
            if (!missing.isEmpty()) {
                throw badRequest(
                        "Local text bundle is missing: " + String.join(", ", missing) + ".",
                        null);
            }

            request.setSource("local");
            request.setRepository(uploadHandle);
            request.setTextAssets(assets);
            request.setFiles(assets.toFileMap());
            if (request.getType() == null || request.getType().isBlank()) {
                request.setType("llm_ggml");
            }
            request.setOutputFormat(SdxProjectOutputService.OUTPUT_KPROJECT);

            DownloadRequest downloadRequest = toDownloadRequest(request);
            Path cleanupRoot = bundleDir;
            CompletableFuture<StagingModelInfo> future =
                    stagingService.stageModelAsync(downloadRequest);
            future.whenComplete((ignored, failure) -> deleteBundle(cleanupRoot));
            return acceptedStatus(downloadRequest);
        } catch (ResponseStatusException invalid) {
            deleteBundle(bundleDir);
            throw invalid;
        } catch (IOException failure) {
            deleteBundle(bundleDir);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to persist the local text-model bundle.",
                    failure);
        }
    }

    private String saveBundlePart(
            Path bundleDir,
            MultipartFile part,
            String canonicalName,
            boolean required) throws IOException {
        if (part == null || part.isEmpty()) {
            if (required) {
                throw badRequest(canonicalName + " is required and must not be empty.", null);
            }
            return null;
        }
        Path normalizedBundle = bundleDir.toAbsolutePath().normalize();
        Path destination = normalizedBundle.resolve(canonicalName).normalize();
        if (!destination.startsWith(normalizedBundle)) {
            throw badRequest("Unsafe text-model asset name: " + canonicalName, null);
        }

        String assetKey = assetKeyForCanonicalName(canonicalName);
        long maximum = assetLimits.maxBytesFor(assetKey);
        if (part.getSize() > maximum) {
            throw badRequest(canonicalName + " exceeds its configured byte limit.", null);
        }

        Path pending = destination.resolveSibling(
                "." + destination.getFileName() + ".part-" + UUID.randomUUID());
        long copied = 0L;
        try (InputStream input = new BufferedInputStream(part.getInputStream());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(pending))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied = Math.addExact(copied, count);
                if (copied > maximum) {
                    throw badRequest(canonicalName + " exceeds its configured byte limit.", null);
                }
                output.write(buffer, 0, count);
            }
        } catch (ArithmeticException overflow) {
            Files.deleteIfExists(pending);
            throw badRequest(canonicalName + " is too large.", overflow);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(pending);
            throw failure;
        }

        if (copied <= 0L) {
            Files.deleteIfExists(pending);
            throw badRequest(canonicalName + " is empty.", null);
        }
        try {
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }

        long bundleBytes;
        try (Stream<Path> files = Files.walk(normalizedBundle)) {
            bundleBytes = files
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    })
                    .sum();
        } catch (java.io.UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
        if (bundleBytes > assetLimits.getTotalBytes()) {
            Files.deleteIfExists(destination);
            throw badRequest("Text-model bundle exceeds the configured total-byte limit.", null);
        }
        return canonicalName;
    }

    private static String assetKeyForCanonicalName(String canonicalName) {
        if (canonicalName.startsWith("model.")) {
            return TextModelAssetMap.MODEL;
        }
        if (TextModelAssetMap.TOKENIZER_FILE.equals(canonicalName)) {
            return TextModelAssetMap.TOKENIZER;
        }
        if (TextModelAssetMap.ADDED_TOKENS_FILE.equals(canonicalName)) {
            return TextModelAssetMap.ADDED_TOKENS;
        }
        if (TextModelAssetMap.SPECIAL_TOKENS_MAP_FILE.equals(canonicalName)) {
            return TextModelAssetMap.SPECIAL_TOKENS_MAP;
        }
        return TextModelAssetMap.MODEL_CONFIG;
    }

    private static String localModelFileName(String format) {
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gguf" -> "model.gguf";
            case "ggml" -> "model.ggml";
            case "sdz", "samediff" -> "model.sdz";
            case "onnx" -> "model.onnx";
            default -> throw badRequest(
                    "Local runnable-chat bundles support GGUF, GGML, SDZ/SameDiff, or ONNX.",
                    null);
        };
    }

    private static void deleteBundle(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    log.warn("Could not remove temporary text-model bundle {}", path, cleanupFailure);
                }
            });
        } catch (IOException cleanupFailure) {
            log.warn("Could not inspect temporary text-model bundle {}", root, cleanupFailure);
        }
    }

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
            OpaqueUpload upload = persistOpaqueUpload(file);
            log.info("Stored model upload {} ({} bytes)", upload.handle(), file.getSize());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "uploadHandle", upload.handle(),
                    "inputPath", upload.handle(),
                    "originalFilename", upload.originalFilename(),
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

    private OpaqueUpload persistOpaqueUpload(MultipartFile file) throws IOException {
        String handle = UUID.randomUUID().toString();
        Path uploadDirectory = stagingService.getStagingDirectory()
                .resolve("uploads")
                .resolve(handle)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(uploadDirectory);
        String original = file.getOriginalFilename() == null
                ? "model"
                : Paths.get(file.getOriginalFilename()).getFileName().toString();
        String extension = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < original.length()) {
            String candidate = original.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,10}")) {
                extension = "." + candidate;
            }
        }
        String modelFile = "model" + extension;
        try {
            saveBundlePart(uploadDirectory, file, modelFile, true);
            return new OpaqueUpload(handle, modelFile, original, uploadDirectory);
        } catch (IOException | RuntimeException failure) {
            deleteBundle(uploadDirectory);
            throw failure;
        }
    }

    private CompletableFuture<StagingModelInfo> stageOpaqueUpload(
            OpaqueUpload upload,
            String modelId,
            String modelType,
            String format,
            boolean autoPromote) {
        String validModelId = requireModelIdPath(modelId);
        ModelType type = ModelType.fromValue(modelType);
        DownloadRequest request = DownloadRequest.builder()
                .source("local")
                .repository(upload.handle())
                .modelId(validModelId)
                .modelType(type)
                .format(format)
                .files(Map.of(TextModelAssetMap.MODEL, upload.modelFile()))
                .build();
        CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(request);
        if (autoPromote) {
            future.thenAccept(staged -> {
                if (staged.getStatus() == StagingStatus.COMPLETED
                        || staged.getStatus() == StagingStatus.READY) {
                    stagingService.promoteModel(validModelId, null);
                }
            });
        }
        future.whenComplete((ignored, failure) -> deleteBundle(upload.directory()));
        return future;
    }

    private OpaqueUpload resolveOpaqueUpload(String handle) throws IOException {
        if (handle == null || !handle.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("Invalid upload handle");
        }
        Path uploadRoot = stagingService.getStagingDirectory()
                .resolve("uploads")
                .toAbsolutePath()
                .normalize();
        Path directory = uploadRoot.resolve(handle).normalize();
        if (!directory.startsWith(uploadRoot)
                || !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("Unknown upload handle");
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> models = files
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .toList();
            if (models.size() != 1) {
                throw new IOException("Upload handle does not contain exactly one model file");
            }
            Path model = models.get(0);
            return new OpaqueUpload(handle, model.getFileName().toString(),
                    model.getFileName().toString(), directory);
        }
    }

    private record OpaqueUpload(
            String handle,
            String modelFile,
            String originalFilename,
            Path directory) {}

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

        String validModelId;
        try {
            validModelId = requireModelIdPath(modelId);
        } catch (ResponseStatusException invalid) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", invalid.getReason() == null ? "Invalid modelId" : invalid.getReason()));
        }

        try {
            OpaqueUpload upload = persistOpaqueUpload(file);
            CompletableFuture<StagingModelInfo> future = stageOpaqueUpload(
                    upload, validModelId, modelType, format, autoPromote);
            StagingModelInfo stagingInfo = stagingService.getStagingModel(validModelId);

            return ResponseEntity.accepted().body(Map.of(
                    "success", true,
                    "uploadHandle", upload.handle(),
                    "modelId", validModelId,
                    "status", stagingInfo.getStatus().name(),
                    "message", stagingInfo.getMessage() != null
                            ? stagingInfo.getMessage()
                            : "Staging started"
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
                    "error", "inputPath must contain the opaque upload handle returned by /upload"
            ));
        }

        String validModelId;
        try {
            validModelId = requireModelIdPath(request.getModelId());
        } catch (ResponseStatusException invalid) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", invalid.getReason() == null ? "Invalid modelId" : invalid.getReason()));
        }

        try {
            String format = request.getFormat() != null ? request.getFormat() : "onnx";
            boolean autoPromote = request.isAutoPromote();
            String type = request.getType() == null || request.getType().isBlank()
                    ? "dense_encoder"
                    : request.getType();
            OpaqueUpload upload = resolveOpaqueUpload(request.getInputPath());

            log.info("Converting uploaded model {} (format: {})", validModelId, format);
            stageOpaqueUpload(upload, validModelId, type, format, autoPromote);
            StagingModelInfo stagingInfo = stagingService.getStagingModel(validModelId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("modelId", validModelId);
            response.put("uploadHandle", upload.handle());
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

}
