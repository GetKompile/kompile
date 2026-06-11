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
import ai.kompile.staging.download.DownloadProgress;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.web.dto.StageWithOptimizationRequest;
import ai.kompile.modelmanager.registry.*;
import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.nd4j.autodiff.samediff.SameDiff;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Service for staging models through the download-convert-validate-promote pipeline.
 */
@Service
public class StagingService implements ai.kompile.core.staging.StagingServiceApi {

    private static final Logger log = LoggerFactory.getLogger(StagingService.class);

    private final RegistryService registryService;
    private final ConversionService conversionService;
    private final List<DownloadService> downloadServices;
    private final OptimizationService optimizationService;
    private final Path stagingDir;
    private final Path modelsDir;

    // Track active staging operations
    private final Map<String, StagingModelInfo> stagingModels = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> stagingEmitters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // Auto-optimization configuration (set via API, applied to newly staged models)
    private volatile StageWithOptimizationRequest.OptimizationConfigDto autoOptimizationConfig;

    @Autowired
    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices,
                          @Lazy OptimizationService optimizationService) {
        this.registryService = registryService;
        this.conversionService = conversionService;
        this.downloadServices = downloadServices;
        this.optimizationService = optimizationService;
        this.modelsDir = registryService.getModelDir();
        this.stagingDir = modelsDir.resolve(".staging");
        ensureDirectories();
    }

    public StageWithOptimizationRequest.OptimizationConfigDto getAutoOptimizationConfig() {
        return autoOptimizationConfig;
    }

    public void setAutoOptimizationConfig(StageWithOptimizationRequest.OptimizationConfigDto config) {
        this.autoOptimizationConfig = config;
        log.info("Auto-optimization config {}", config != null ? "set" : "cleared");
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
        emitStagingStatus(modelId, info);

        try {
            // 1. Download
            info.withStatus(StagingStatus.DOWNLOADING, 5, "Downloading from " + source);
            progressCallback.accept(info);

            Path pendingDir = stagingDir.resolve("pending").resolve(modelId);
            DownloadResult downloadResult = download(request, pendingDir, dlProgress -> {
                if (dlProgress.getPhase() == DownloadProgress.Phase.DOWNLOADING) {
                    // Map download 0-100% to staging 5-35%
                    int dlPercent = dlProgress.getProgressPercent();
                    int stagingPercent = 5 + (int)(dlPercent * 0.30);
                    info.withDownloadProgress(
                            stagingPercent,
                            dlProgress.getMessage(),
                            dlProgress.getBytesDownloaded(),
                            dlProgress.getTotalBytes(),
                            dlProgress.getBytesPerSecond()
                    );
                    info.setCurrentFile(dlProgress.getFileName());
                } else if (dlProgress.getPhase() == DownloadProgress.Phase.EXTRACTING) {
                    info.withStatus(StagingStatus.DOWNLOADING, 36, dlProgress.getMessage());
                } else if (dlProgress.getPhase() == DownloadProgress.Phase.VERIFYING) {
                    info.withStatus(StagingStatus.DOWNLOADING, 37, dlProgress.getMessage());
                }
            });

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

            // 2b. Download tokenizer if needed (GGUF models don't include HF tokenizer.json)
            if (needsConversion(modelPath)) {
                ensureTokenizer(request, pendingDir, modelPath);
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
            ModelType type = info.getType() instanceof ModelType ? (ModelType) info.getType() : ModelType.ENCODER;
            Path productionDir = modelsDir.resolve(type.getDirectoryName()).resolve(modelId);
            Files.createDirectories(productionDir);

            // Move files
            moveDirectory(verifiedDir, productionDir);

            // Find model and vocab files using shard-aware helpers
            Path modelFile = findModelFile(productionDir);
            Path vocabFile = findVocabFile(productionDir);
            boolean sharded = isShardedModel(productionDir);

            // For sharded models, model_file stores the logical base name "model.sdz"
            // which SameDiff.load() uses to discover shard files in the same directory.
            String modelFileName;
            if (sharded) {
                modelFileName = "model.sdz";
            } else {
                modelFileName = modelFile != null ? modelFile.getFileName().toString() : "model.sdz";
            }

            String vocabFileName = vocabFile != null ? vocabFile.getFileName().toString() : "vocab.txt";

            // Calculate checksum on whatever representative file we have
            String checksum = modelFile != null ? calculateChecksum(modelFile) : null;

            // Auto-probe vision encoder IO config for VLM models
            if (metadata == null) {
                metadata = ModelMetadata.builder().build();
            }
            if (type.isVlm()) {
                probeVisionEncoderIOConfig(productionDir, modelFile, metadata);
            }

            // Create registry entry
            ModelEntry entry = ModelEntry.builder()
                    .modelId(modelId)
                    .type(type)
                    .path(type.getDirectoryName() + "/" + modelId)
                    .modelFile(modelFileName)
                    .vocabFile(vocabFileName)
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
     * Public version of probe for use by REST endpoints on existing registry models.
     */
    public void probeVisionEncoderIOConfigPublic(Path productionDir, Path modelFile, ModelMetadata metadata) {
        probeVisionEncoderIOConfig(productionDir, modelFile, metadata);
    }

    /**
     * Auto-probe a vision encoder SameDiff model to discover I/O variable names.
     * Populates metadata fields so they are saved in the registry and can be
     * overridden by the user later via the model details UI.
     */
    private void probeVisionEncoderIOConfig(Path productionDir, Path modelFile, ModelMetadata metadata) {
        // Find the vision encoder model file - could be the main model or a sub-component
        Path visionEncoderFile = null;

        // For VLM pipeline models, look for a vision_encoder subdirectory or file
        try (var stream = Files.walk(productionDir, 2)) {
            visionEncoderFile = stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        String parent = p.getParent().getFileName().toString().toLowerCase();
                        return (name.endsWith(".fb") || name.endsWith(".sdz"))
                                && (parent.contains("vision") || name.contains("vision"));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("Error searching for vision encoder file", e);
        }

        // Fall back to the main model file for VLM_VISION_ENCODER type
        if (visionEncoderFile == null && modelFile != null) {
            visionEncoderFile = modelFile;
        }

        if (visionEncoderFile == null || !Files.exists(visionEncoderFile)) {
            log.debug("No vision encoder model file found for probing in {}", productionDir);
            return;
        }

        try {
            log.info("Auto-probing vision encoder IO config from: {}", visionEncoderFile);
            SameDiff sd = SameDiff.load(visionEncoderFile.toFile(), false);

            // Discover pixel values input
            String pixelValuesName = null;
            String pixelAttentionMaskName = null;
            for (String input : sd.inputs()) {
                String lower = input.toLowerCase();
                if (pixelValuesName == null && lower.contains("pixel_value")) {
                    pixelValuesName = input;
                } else if (pixelValuesName == null && lower.contains("pixel") && !lower.contains("mask")) {
                    pixelValuesName = input;
                }
                if (pixelAttentionMaskName == null && lower.contains("pixel_attention_mask")) {
                    pixelAttentionMaskName = input;
                }
            }
            if (pixelValuesName == null) {
                pixelValuesName = "pixel_values";
            }

            // Discover outputs
            String primaryOutput = null;
            List<String> outputNames = new ArrayList<>();

            List<String> registered = sd.outputs();
            if (registered != null && !registered.isEmpty()) {
                outputNames.addAll(registered);
                primaryOutput = registered.get(0);
                for (String name : registered) {
                    String lower = name.toLowerCase();
                    if (lower.contains("last_hidden_state") || lower.contains("image_embeds")) {
                        primaryOutput = name;
                        break;
                    }
                }
            }

            if (outputNames.isEmpty()) {
                String[] wellKnown = {"image_embeds", "last_hidden_state", "pooler_output",
                        "encoder_output", "hidden_states", "visual_features"};
                for (String name : wellKnown) {
                    if (sd.hasVariable(name)) {
                        outputNames.add(name);
                        if (primaryOutput == null) primaryOutput = name;
                    }
                }
            }

            if (outputNames.isEmpty()) {
                for (String varName : sd.variableMap().keySet()) {
                    String lower = varName.toLowerCase();
                    if (lower.contains("last_hidden_state") || lower.contains("image_embeds")
                            || lower.contains("pooler_output") || lower.contains("encoder_output")) {
                        outputNames.add(varName);
                        if (primaryOutput == null) primaryOutput = varName;
                    }
                }
            }

            if (outputNames.isEmpty()) {
                primaryOutput = "image_embeds";
                outputNames.add("image_embeds");
            }

            metadata.setVisionEncoderPixelValuesName(pixelValuesName);
            metadata.setVisionEncoderPixelAttentionMaskName(pixelAttentionMaskName);
            metadata.setVisionEncoderPrimaryOutputName(primaryOutput);
            metadata.setVisionEncoderOutputNames(outputNames);

            log.info("Vision encoder IO config probed: pixelValues={}, pixelAttentionMask={}, primaryOutput={}, outputs={}",
                    pixelValuesName, pixelAttentionMaskName, primaryOutput, outputNames);
        } catch (Exception e) {
            log.warn("Failed to auto-probe vision encoder IO config from {}: {}", visionEncoderFile, e.getMessage());
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
        emitStagingStatus(modelId, info);

        // Run staging asynchronously
        executor.submit(() -> {
            try {
                // 1. Set up pending directory
                Path pendingDir = stagingDir.resolve("pending").resolve(modelId);
                Files.createDirectories(pendingDir);

                // Copy local file to pending directory
                Path localModelPath = pendingDir.resolve(modelPath.getFileName());
                Files.copy(modelPath, localModelPath, StandardCopyOption.REPLACE_EXISTING);

                long fileSize = Files.exists(localModelPath) ? Files.size(localModelPath) : 0;
                info.withDownloadProgress(20, "File copied to staging",
                        fileSize, fileSize, 0);
                info.setCurrentFile(modelPath.getFileName().toString());

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
            emitStagingStatus(modelId, info);
            completeStagingEmitters(modelId);
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

    /**
     * Repair staged models by checking for missing vocab files and attempting to fix issues.
     * Returns a map of model IDs to whether they were successfully repaired.
     */
    public Map<String, Boolean> repairStagedModels() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        ModelRegistry registry = registryService.loadRegistry();

        for (Map.Entry<String, ModelEntry> entry : registry.getAllModels().entrySet()) {
            String modelId = entry.getKey();
            ModelEntry model = entry.getValue();

            if (model.getPath() == null) continue;

            Path modelPath = modelsDir.resolve(model.getPath());
            if (!Files.exists(modelPath)) {
                results.put(modelId, false);
                continue;
            }

            // Check for vocab file
            Path vocabPath = modelsDir.resolve(model.getVocabFilePath());
            if (!Files.exists(vocabPath)) {
                // Try to find any vocab file in the model directory
                try {
                    Path found = findFile(modelPath, "vocab.txt", "tokenizer.json", "sentencepiece.model");
                    if (found != null) {
                        model.setVocabFile(found.getFileName().toString());
                        registryService.addModel(model);
                        results.put(modelId, true);
                        log.info("Repaired vocab path for model {}: {}", modelId, found.getFileName());
                    } else {
                        results.put(modelId, false);
                        log.warn("No vocab file found for model {}", modelId);
                    }
                } catch (IOException e) {
                    results.put(modelId, false);
                    log.warn("Error repairing model {}: {}", modelId, e.getMessage());
                }
            } else {
                // Model is fine
                results.put(modelId, true);
            }
        }

        return results;
    }

    /**
     * Get list of staged model IDs that are missing vocab files.
     */
    public List<String> getStagedModelsMissingVocab() {
        List<String> missing = new ArrayList<>();
        ModelRegistry registry = registryService.loadRegistry();

        for (Map.Entry<String, ModelEntry> entry : registry.getAllModels().entrySet()) {
            String modelId = entry.getKey();
            ModelEntry model = entry.getValue();

            if (model.getPath() == null) continue;

            Path vocabPath = modelsDir.resolve(model.getVocabFilePath());
            if (!Files.exists(vocabPath)) {
                missing.add(modelId);
            }
        }

        return missing;
    }

    // ==================== SSE Streaming ====================

    /**
     * Subscribe to real-time staging progress updates for a model via SSE.
     *
     * @param modelId the model being staged
     * @return SseEmitter for streaming status events
     */
    public SseEmitter subscribeToStagingStream(String modelId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 min timeout
        stagingEmitters.computeIfAbsent(modelId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> {
            List<SseEmitter> emitters = stagingEmitters.get(modelId);
            if (emitters != null) emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            List<SseEmitter> emitters = stagingEmitters.get(modelId);
            if (emitters != null) emitters.remove(emitter);
        });
        emitter.onError(e -> {
            List<SseEmitter> emitters = stagingEmitters.get(modelId);
            if (emitters != null) emitters.remove(emitter);
        });

        // Send current status immediately so client has the latest state
        StagingModelInfo current = stagingModels.get(modelId);
        if (current != null) {
            try {
                emitter.send(SseEmitter.event().name("status").data(current));
            } catch (IOException e) {
                log.debug("Failed to send initial status for model {}", modelId);
            }
        }

        return emitter;
    }

    /**
     * Push a status update to all SSE subscribers for a model.
     */
    private void emitStagingStatus(String modelId, StagingModelInfo info) {
        List<SseEmitter> emitters = stagingEmitters.get(modelId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(info));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    /**
     * Complete and close all SSE emitters for a model (on terminal state).
     */
    private void completeStagingEmitters(String modelId) {
        List<SseEmitter> emitters = stagingEmitters.get(modelId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            emitters.clear();
        }
    }

    // Helper methods

    private DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    private DownloadResult download(DownloadRequest request, Path destination,
                                     Consumer<DownloadProgress> progressCallback) {
        for (DownloadService downloader : downloadServices) {
            if (downloader.canHandle(request.getSource())) {
                return downloader.download(request, destination, progressCallback);
            }
        }
        return DownloadResult.failure("No downloader available for source: " + request.getSource());
    }

    private boolean needsConversion(Path modelPath) {
        if (modelPath == null) return false;
        String name = modelPath.getFileName().toString().toLowerCase();
        return name.endsWith(".onnx")
                || name.endsWith(".pb")
                || name.endsWith(".h5")
                || name.endsWith(".gguf")
                || name.endsWith(".ggml");
    }

    /**
     * Ensure a tokenizer.json exists in the staging directory for the model.
     * GGUF models embed tokenizer metadata but not in HuggingFace format.
     * If a tokenizerUrl was provided in the request, download it.
     * Otherwise, try to infer the URL from the GGUF source URL.
     */
    private void ensureTokenizer(DownloadRequest request, Path pendingDir, Path originalModelPath) {
        try {
            Path tokenizerJson = pendingDir.resolve("tokenizer.json");
            if (Files.exists(tokenizerJson) && Files.size(tokenizerJson) > 100) {
                log.debug("tokenizer.json already present at {}", tokenizerJson);
                return;
            }

            List<String> candidates = new java.util.ArrayList<>();
            if (request.getTokenizerUrl() != null && !request.getTokenizerUrl().isBlank()) {
                candidates.add(request.getTokenizerUrl());
            }
            candidates.addAll(inferTokenizerUrlCandidates(request.getRepository()));

            for (String tokenizerUrl : candidates) {
                log.info("Trying tokenizer.json from {} for model {}", tokenizerUrl, request.getModelId());
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(tokenizerUrl).openConnection();
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("User-Agent", "Kompile-Model-Staging/1.0");
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        try (java.io.InputStream in = conn.getInputStream()) {
                            Files.copy(in, tokenizerJson, StandardCopyOption.REPLACE_EXISTING);
                        }
                        if (Files.size(tokenizerJson) > 100) {
                            log.info("Downloaded tokenizer.json ({} bytes) from {}", Files.size(tokenizerJson), tokenizerUrl);
                            conn.disconnect();
                            return;
                        }
                    } else {
                        log.debug("HTTP {} from {}, trying next candidate", code, tokenizerUrl);
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    log.debug("Failed to fetch tokenizer from {}: {}", tokenizerUrl, e.getMessage());
                }
            }
            log.warn("No tokenizer.json could be downloaded for model {}. LLM loading may need manual tokenizer setup.", request.getModelId());
        } catch (Exception e) {
            log.warn("Failed to download tokenizer for model {}: {}", request.getModelId(), e.getMessage());
        }
    }

    /**
     * Try to infer a tokenizer.json URL from a HuggingFace GGUF model URL.
     * GGUF repos often don't contain tokenizer.json, so we return the GGUF repo URL
     * first (caller will try it), and the caller should fall through if 404.
     */
    private String inferTokenizerUrl(String modelUrl) {
        if (modelUrl == null) return null;
        if (modelUrl.contains("huggingface.co") && modelUrl.contains("/resolve/")) {
            int resolveIdx = modelUrl.indexOf("/resolve/");
            String repoBase = modelUrl.substring(0, resolveIdx);
            String branch = "main";
            String afterResolve = modelUrl.substring(resolveIdx + "/resolve/".length());
            int slashIdx = afterResolve.indexOf('/');
            if (slashIdx > 0) {
                branch = afterResolve.substring(0, slashIdx);
            }
            return repoBase + "/resolve/" + branch + "/tokenizer.json";
        }
        return null;
    }

    /**
     * Try multiple tokenizer URL candidates when the primary fails.
     */
    private List<String> inferTokenizerUrlCandidates(String modelUrl) {
        List<String> candidates = new java.util.ArrayList<>();
        String primary = inferTokenizerUrl(modelUrl);
        if (primary != null) candidates.add(primary);
        return candidates;
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

    /**
     * Find the logical model file in a directory. For sharded models (produced by
     * saveAutoShard), the actual files are model.shard0-of-N.sdnb etc. but
     * SameDiff.load() resolves them from a base name like "model.sdz".
     * Returns the shard-0 file if sharded, or a single .sdz/.fb file if present.
     */
    private Path findModelFile(Path dir) throws IOException {
        // First check for a real single-file model
        Path single = findFile(dir, "model.sdz", ".fb");
        if (single != null) return single;

        // Look for sharded model files
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.contains(".shard0-of-") && name.endsWith(".sdnb")) {
                    return path;
                }
            }
        }
        return null;
    }

    /**
     * Find a tokenizer/vocab file in a directory, checking multiple common names.
     */
    private Path findVocabFile(Path dir) throws IOException {
        return findFile(dir, "tokenizer.json", "vocab.txt", "sentencepiece.model");
    }

    /**
     * Check if a model directory contains sharded files rather than a single model file.
     */
    private boolean isShardedModel(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.contains(".shard0-of-") && name.endsWith(".sdnb")) {
                    return true;
                }
            }
        }
        return false;
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
