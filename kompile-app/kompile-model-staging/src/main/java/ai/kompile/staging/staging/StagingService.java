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

package ai.kompile.staging.staging;

import ai.kompile.staging.conversion.ConversionResult;
import ai.kompile.staging.conversion.ConversionService;
import ai.kompile.staging.download.*;
import ai.kompile.staging.registry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Service for staging models through the download-convert-validate-promote pipeline.
 */
@Service
public class StagingService {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private final RegistryService registryService;
    private final ConversionService conversionService;
    private final List<DownloadService> downloadServices;
    private final Path stagingDir;
    private final Path modelsDir;

    // Track active staging operations
    private final Map<String, StagingModelInfo> stagingModels = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Autowired
    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices) {
        this.registryService = registryService;
        this.conversionService = conversionService;
        this.downloadServices = downloadServices;
        this.modelsDir = registryService.getModelDir();
        this.stagingDir = modelsDir.resolve(".staging");
        ensureDirectories();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(stagingDir.resolve("pending"));
            Files.createDirectories(stagingDir.resolve("verified"));
            Files.createDirectories(stagingDir.resolve("failed"));
        } catch (IOException e) {
            log.error("Failed to create staging directories", e);
        }
    }

    /**
     * Stage a model asynchronously.
     * Downloads, converts, validates, and prepares for promotion.
     */
    public CompletableFuture<StagingModelInfo> stageModelAsync(DownloadRequest request) {
        return CompletableFuture.supplyAsync(() -> stageModel(request), executor);
    }

    /**
     * Stage a model synchronously.
     */
    public StagingModelInfo stageModel(DownloadRequest request) {
        return stageModel(request, progress -> {});
    }

    /**
     * Stage a model with progress callback.
     */
    public StagingModelInfo stageModel(DownloadRequest request, Consumer<StagingModelInfo> progressCallback) {
        String modelId = request.getModelId();
        String source = request.getSource() + ":" + request.getRepository();

        StagingModelInfo info = StagingModelInfo.create(modelId, source, request.getModelType());
        stagingModels.put(modelId, info);
        progressCallback.accept(info);

        try {
            // 1. Download
            info.withStatus(StagingStatus.DOWNLOADING, 10, "Downloading from " + source);
            progressCallback.accept(info);

            Path pendingDir = stagingDir.resolve("pending").resolve(modelId);
            DownloadResult downloadResult = download(request, pendingDir);

            if (!downloadResult.isSuccess()) {
                return info.failed("Download failed: " + downloadResult.getErrorMessage());
            }

            // 2. Convert if needed
            Path modelPath = downloadResult.getModelPath();
            Path outputPath;

            if (needsConversion(modelPath)) {
                info.withStatus(StagingStatus.CONVERTING, 40, "Converting to SameDiff format");
                progressCallback.accept(info);

                outputPath = pendingDir.resolve("model.sdz");
                ConversionResult conversionResult = conversionService.convert(
                        modelPath, outputPath, request.getFormat());

                if (!conversionResult.isSuccess()) {
                    moveToFailed(pendingDir, modelId);
                    return info.failed("Conversion failed: " + conversionResult.getErrorMessage());
                }
            } else {
                outputPath = modelPath;
            }

            // 3. Validate
            info.withStatus(StagingStatus.VALIDATING, 70, "Validating model");
            progressCallback.accept(info);

            ConversionService.ValidationResult validationResult = conversionService.validate(outputPath);
            if (!validationResult.isValid()) {
                moveToFailed(pendingDir, modelId);
                return info.failed("Validation failed: " + validationResult.getErrorMessage());
            }

            // 4. Move to verified staging
            info.withStatus(StagingStatus.READY, 90, "Model ready for promotion");
            progressCallback.accept(info);

            Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
            moveDirectory(pendingDir, verifiedDir);

            info.completed();
            progressCallback.accept(info);

            log.info("Model {} staged successfully", modelId);
            return info;

        } catch (Exception e) {
            log.error("Staging failed for model {}", modelId, e);
            return info.failed("Staging failed: " + e.getMessage());
        }
    }

    /**
     * Promote a staged model to production.
     */
    public boolean promoteModel(String modelId, ModelMetadata metadata) {
        StagingModelInfo info = stagingModels.get(modelId);
        if (info == null) {
            info = findStagedModel(modelId);
        }

        // Accept READY, COMPLETED, or PROMOTING status (PROMOTING for auto-promote flow)
        if (info == null || (info.getStatus() != StagingStatus.READY
                && info.getStatus() != StagingStatus.COMPLETED
                && info.getStatus() != StagingStatus.PROMOTING)) {
            log.error("Model {} is not ready for promotion (current status: {})", modelId,
                    info != null ? info.getStatus() : "not found");
            return false;
        }

        try {
            Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
            if (!Files.exists(verifiedDir)) {
                log.error("Verified directory not found for model {}", modelId);
                return false;
            }

            // Create production directory
            ModelType type = info.getType() != null ? info.getType() : ModelType.ENCODER;
            Path productionDir = modelsDir.resolve(type.getDirectoryName()).resolve(modelId);
            Files.createDirectories(productionDir);

            // Move files
            moveDirectory(verifiedDir, productionDir);

            // Find model and vocab files
            Path modelFile = findFile(productionDir, "model.sdz", ".fb");
            Path vocabFile = findFile(productionDir, "vocab.txt");

            // Calculate checksum
            String checksum = modelFile != null ? calculateChecksum(modelFile) : null;

            // Create registry entry
            ModelEntry entry = ModelEntry.builder()
                    .modelId(modelId)
                    .type(type)
                    .path(type.getDirectoryName() + "/" + modelId)
                    .modelFile(modelFile != null ? modelFile.getFileName().toString() : "model.sdz")
                    .vocabFile(vocabFile != null ? vocabFile.getFileName().toString() : "vocab.txt")
                    .checksum(checksum)
                    .status(ModelStatus.ACTIVE)
                    .promotedAt(Instant.now().toString())
                    .metadata(metadata)
                    .tokenizer(TokenizerConfig.defaultBertConfig())
                    .build();

            registryService.addModel(entry);

            // Update staging info
            info.withStatus(StagingStatus.COMPLETED, 100, "Model promoted successfully");
            stagingModels.remove(modelId);

            log.info("Model {} promoted to production", modelId);
            return true;

        } catch (Exception e) {
            log.error("Failed to promote model {}", modelId, e);
            return false;
        }
    }

    /**
     * Get all models currently in staging.
     */
    public List<StagingModelInfo> getStagingModels() {
        return new ArrayList<>(stagingModels.values());
    }

    /**
     * Get the staging directory path.
     */
    public Path getStagingDirectory() {
        return stagingDir;
    }

    /**
     * Stage a model from a local file path (skip download, go directly to conversion).
     *
     * @param modelId The model ID to use
     * @param filePath The path to the local model file
     * @param format The model format (onnx, tensorflow, keras, samediff)
     * @param autoPromote Whether to auto-promote after staging completes
     * @return Staging info for the model
     */
    public StagingModelInfo stageLocalModel(String modelId, String filePath, String format, boolean autoPromote) {
        Path modelPath = Paths.get(filePath);
        String source = "local:" + filePath;

        StagingModelInfo info = StagingModelInfo.create(modelId, source, ModelType.DENSE_ENCODER);
        stagingModels.put(modelId, info);

        // Run staging asynchronously
        executor.submit(() -> {
            try {
                // 1. Set up pending directory
                Path pendingDir = stagingDir.resolve("pending").resolve(modelId);
                Files.createDirectories(pendingDir);

                // Copy local file to pending directory
                Path localModelPath = pendingDir.resolve(modelPath.getFileName());
                Files.copy(modelPath, localModelPath, StandardCopyOption.REPLACE_EXISTING);

                info.withStatus(StagingStatus.DOWNLOADING, 20, "File copied to staging");

                // 2. Convert if needed
                Path outputPath;
                if (needsConversion(localModelPath)) {
                    info.withStatus(StagingStatus.CONVERTING, 40, "Converting to SameDiff format");

                    outputPath = pendingDir.resolve("model.sdz");
                    ConversionResult conversionResult = conversionService.convert(
                            localModelPath, outputPath, format);

                    if (!conversionResult.isSuccess()) {
                        moveToFailed(pendingDir, modelId);
                        info.failed("Conversion failed: " + conversionResult.getErrorMessage());
                        return;
                    }
                } else {
                    outputPath = localModelPath;
                }

                // 3. Validate
                info.withStatus(StagingStatus.VALIDATING, 70, "Validating model");

                ConversionService.ValidationResult validationResult = conversionService.validate(outputPath);
                if (!validationResult.isValid()) {
                    moveToFailed(pendingDir, modelId);
                    info.failed("Validation failed: " + validationResult.getErrorMessage());
                    return;
                }

                // 4. Move to verified staging
                Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
                moveDirectory(pendingDir, verifiedDir);

                log.info("Local model {} staged successfully", modelId);

                // 5. Auto-promote if requested, otherwise leave in READY state
                if (autoPromote) {
                    info.withStatus(StagingStatus.PROMOTING, 95, "Auto-promoting to registry");
                    boolean promoted = promoteModel(modelId, null);
                    if (promoted) {
                        info.completed();
                    } else {
                        info.withStatus(StagingStatus.READY, 90, "Model ready for manual promotion");
                    }
                } else {
                    // Stay in READY state for manual promotion
                    info.withStatus(StagingStatus.READY, 100, "Model ready for promotion");
                }

            } catch (Exception e) {
                log.error("Staging failed for local model {}", modelId, e);
                info.failed("Staging failed: " + e.getMessage());
            }
        });

        return info;
    }

    /**
     * Get staging info for a specific model.
     */
    public StagingModelInfo getStagingModel(String modelId) {
        return stagingModels.get(modelId);
    }

    /**
     * Cancel a staging operation.
     */
    public boolean cancelStaging(String modelId) {
        StagingModelInfo info = stagingModels.get(modelId);
        if (info != null && !info.getStatus().isTerminal()) {
            info.failed("Cancelled by user");
            stagingModels.remove(modelId);
            return true;
        }
        return false;
    }

    /**
     * Clean up failed staging attempts.
     */
    public int cleanupFailed() {
        try {
            Path failedDir = stagingDir.resolve("failed");
            if (!Files.exists(failedDir)) {
                return 0;
            }

            int count = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(failedDir)) {
                for (Path dir : stream) {
                    deleteDirectory(dir);
                    count++;
                }
            }
            return count;
        } catch (IOException e) {
            log.error("Failed to cleanup failed staging", e);
            return 0;
        }
    }

    // Helper methods

    private DownloadResult download(DownloadRequest request, Path destination) {
        for (DownloadService downloader : downloadServices) {
            if (downloader.canHandle(request.getSource())) {
                return downloader.download(request, destination);
            }
        }
        return DownloadResult.failure("No downloader available for source: " + request.getSource());
    }

    private boolean needsConversion(Path modelPath) {
        if (modelPath == null) return false;
        String name = modelPath.getFileName().toString().toLowerCase();
        return name.endsWith(".onnx") || name.endsWith(".pb") || name.endsWith(".h5");
    }

    private void moveToFailed(Path source, String modelId) {
        try {
            // Only move if the source directory exists
            if (source == null || !Files.exists(source)) {
                log.debug("Source directory does not exist, skipping move to failed: {}", source);
                return;
            }
            Path failedDir = stagingDir.resolve("failed").resolve(modelId);
            moveDirectory(source, failedDir);
        } catch (IOException e) {
            log.error("Failed to move to failed directory", e);
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        // Check if source exists before attempting to move
        if (source == null || !Files.exists(source)) {
            throw new NoSuchFileException(source != null ? source.toString() : "null", null,
                    "Source directory does not exist");
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // If move fails, try copy and delete
            copyDirectory(source, target);
            deleteDirectory(source);
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path targetPath = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy: " + path, e);
            }
        });
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete: {}", path);
                    }
                });
    }

    private Path findFile(Path dir, String... names) throws IOException {
        for (String name : names) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path path : stream) {
                    if (path.getFileName().toString().endsWith(name) ||
                        path.getFileName().toString().equals(name)) {
                        return path;
                    }
                }
            }
        }
        return null;
    }

    private StagingModelInfo findStagedModel(String modelId) {
        Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
        if (Files.exists(verifiedDir)) {
            return StagingModelInfo.builder()
                    .modelId(modelId)
                    .status(StagingStatus.READY)
                    .build();
        }
        return null;
    }

    private String calculateChecksum(Path file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
