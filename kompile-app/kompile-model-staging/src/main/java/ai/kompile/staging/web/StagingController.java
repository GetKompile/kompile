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
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.export.ExportService;
import ai.kompile.staging.export.ImportService;
import ai.kompile.staging.registry.*;
import ai.kompile.staging.staging.StagingModelInfo;
import ai.kompile.staging.staging.StagingService;
import ai.kompile.staging.web.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @Autowired
    public StagingController(RegistryService registryService,
                            StagingService stagingService,
                            ExportService exportService,
                            ImportService importService,
                            CatalogService catalogService,
                            ArchiveModelManager archiveModelManager,
                            ModelSourceConfiguration modelSourceConfig) {
        this.registryService = registryService;
        this.stagingService = stagingService;
        this.exportService = exportService;
        this.importService = importService;
        this.catalogService = catalogService;
        this.archiveModelManager = archiveModelManager;
        this.modelSourceConfig = modelSourceConfig;
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
                    // Determine model type from catalog location
                    ModelType modelType = catalogService.getEncoders().contains(catalogModel)
                            ? ModelType.ENCODER
                            : ModelType.CROSS_ENCODER;

                    DownloadRequest downloadRequest = DownloadRequest.builder()
                            .source(catalogModel.getSource())
                            .repository(catalogModel.getRepo())
                            .modelId(catalogModel.getId())
                            .modelType(modelType)
                            .format(catalogModel.getFormat())
                            .build();

                    // Start async staging
                    CompletableFuture<StagingModelInfo> future = stagingService.stageModelAsync(downloadRequest);

                    // If auto-promote is requested, add completion handler
                    if (autoPromote) {
                        future.thenAccept(info -> {
                            if (info.getStatus() == ai.kompile.staging.staging.StagingStatus.READY) {
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
        DownloadRequest downloadRequest = DownloadRequest.builder()
                .source(request.getSource())
                .repository(request.getRepository())
                .modelId(request.getModelId())
                .modelType(ModelType.fromValue(request.getType()))
                .format(request.getFormat())
                .revision(request.getRevision())
                .authToken(request.getAuthToken())
                .build();

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
