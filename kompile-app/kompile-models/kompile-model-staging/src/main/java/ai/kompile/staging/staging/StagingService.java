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
import ai.kompile.staging.web.dto.TrainingArtifactStageRequest;
import ai.kompile.modelmanager.registry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String TRAINING_ARTIFACT_MANIFEST_FILE = "training-artifact.json";
    private static final String AUDIO_SYNTHESIS_CONFIG_FILE = ".audio-synthesis.json";

    private final RegistryService registryService;
    private final ConversionService conversionService;
    private final List<DownloadService> downloadServices;
    private final OptimizationService optimizationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
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
                          OptimizationService optimizationService) {
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
    public CompletableFuture<ai.kompile.core.staging.StagingModelInfo> stageModelAsync(DownloadRequest request) {
        return CompletableFuture.supplyAsync(() -> stageModel(request), executor);
    }

    /**
     * Stage a model synchronously.
     */
    public ai.kompile.core.staging.StagingModelInfo stageModel(DownloadRequest request) {
        return stageModel(request, progress -> {});
    }

    /**
     * Stage a model with progress callback.
     */
    public ai.kompile.core.staging.StagingModelInfo stageModel(DownloadRequest request, Consumer<ai.kompile.core.staging.StagingModelInfo> progressCallback) {
        String modelId = request.getModelId();
        String source = request.getSource() + ":" + request.getRepository();

        StagingModelInfo info = StagingModelInfo.create(modelId, source, request.getModelType());
        if (request.getModelType() == ModelType.AUDIO_SYNTHESIS
                && request.getAudioSynthesis() == null) {
            return info.failed(
                    "audio_synthesis configuration is required for an audio synthesis model");
        }
        stagingModels.put(modelId, info);
        progressCallback.accept(info);
        emitStagingStatus(modelId, info);

        try {
            // 1. Download
            info.withStatus(StagingStatus.DOWNLOADING, 5, "Downloading from " + source);
            progressCallback.accept(info);

            Path pendingDir = stagingDir.resolve("pending").resolve(modelId);

            // Clean up any stale workspace left by a prior failed or cancelled attempt.
            // Without this, a previous partial download/conversion can leave artifacts
            // (e.g. a half-written .onnx file) that cause the ONNX importer to encounter
            // duplicate variable registrations on the next run.
            if (Files.exists(pendingDir)) {
                log.info("Cleaning stale pending workspace for model {} before re-attempt", modelId);
                try {
                    deleteDirectory(pendingDir);
                } catch (IOException delEx) {
                    log.warn("Could not clean stale pending workspace for {}: {}", modelId, delEx.getMessage());
                }
            }

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

            if (shouldConvert(modelPath, request.getModelType(), request.getFormat())) {
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

            // 2b. Download tokenizer if needed for converted artifacts.
            if (shouldConvert(modelPath, request.getModelType(), request.getFormat())) {
                ensureTokenizer(request, pendingDir, modelPath);
            }

            // 3. Validate SameDiff artifacts. VLM pipeline manifests are validated by their
            // pipeline loader at runtime and must not be loaded as SameDiff graphs.
            info.withStatus(StagingStatus.VALIDATING, 70, "Validating model");
            progressCallback.accept(info);

            if (requiresSameDiffValidation(outputPath, request.getModelType(), request.getFormat())) {
                ConversionService.ValidationResult validationResult = conversionService.validate(outputPath);
                if (!validationResult.isValid()) {
                    moveToFailed(pendingDir, modelId);
                    return info.failed("Validation failed: " + validationResult.getErrorMessage());
                }
            } else {
                log.info("Skipping SameDiff validation for non-SameDiff model artifact: {}", outputPath);
            }

            // Persist the trusted serving ABI with the staged artifact so promotion
            // remains correct across service restarts.
            if (request.getModelType() == ModelType.AUDIO_SYNTHESIS) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                        pendingDir.resolve(AUDIO_SYNTHESIS_CONFIG_FILE).toFile(),
                        request.getAudioSynthesis());
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
            // Move any partial pending workspace to failed so it does not accumulate
            // and confuse a subsequent re-staging attempt for the same model.
            moveToFailed(stagingDir.resolve("pending").resolve(modelId), modelId);
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
            ModelType type = info.getType() instanceof ModelType ? (ModelType) info.getType() : ModelType.DENSE_ENCODER;
            AudioSynthesisConfig audioSynthesis = null;
            if (type == ModelType.AUDIO_SYNTHESIS) {
                Path audioConfigPath = verifiedDir.resolve(AUDIO_SYNTHESIS_CONFIG_FILE);
                if (!Files.isRegularFile(audioConfigPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(audioConfigPath)) {
                    log.error("Verified audio synthesis configuration is missing for {}", modelId);
                    return false;
                }
                audioSynthesis = objectMapper.readValue(
                        audioConfigPath.toFile(), AudioSynthesisConfig.class);
            }
            Path productionDir = modelsDir.resolve(type.getDirectoryName()).resolve(modelId);
            Files.createDirectories(productionDir);

            // Move files
            moveDirectory(verifiedDir, productionDir);

            // Find model and vocab files using shard-aware helpers
            Path modelFile = findModelFile(productionDir);
            Path vocabFile = findVocabFile(productionDir);
            boolean sharded = isShardedModel(productionDir);

            // For sharded models, the logical base name is "model.sdnb" and we also
            // create a 0-byte marker file so SameDiff.load() can discover the shards.
            String modelFileName;
            if (sharded) {
                modelFileName = "model.sdnb";
                // Create 0-byte marker if not already present
                Path marker = productionDir.resolve("model.sdnb");
                if (!Files.exists(marker)) {
                    Files.createFile(marker);
                } else {
                    // Truncate to 0 bytes if it has content (pre-existing marker case)
                    if (Files.size(marker) > 0) {
                        Files.write(marker, new byte[0]);
                    }
                }
            } else {
                modelFileName = modelFile != null ? modelFile.getFileName().toString() : "model.sdz";
            }

            String vocabFileName = vocabFile != null ? vocabFile.getFileName().toString() : "vocab.txt";

            // Calculate checksum on the most representative file:
            // prefer shard0 over a 0-byte marker, otherwise the single model file
            Path checksumTarget = modelFile;
            if (sharded && checksumTarget != null) {
                String cName = checksumTarget.getFileName().toString();
                // If findModelFile returned a shard file, use it; if it returned the marker, find shard0
                if (!cName.contains(".shard")) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(productionDir)) {
                        for (Path p : ds) {
                            if (p.getFileName().toString().contains(".shard0-of-")) {
                                checksumTarget = p;
                                break;
                            }
                        }
                    }
                }
            }
            String checksum = checksumTarget != null ? calculateChecksum(checksumTarget) : null;

            // Auto-probe vision encoder IO config for VLM models
            if (metadata == null) {
                metadata = ModelMetadata.builder().build();
            }
            if (type.isVlm()) {
                probeVisionEncoderIOConfig(productionDir, modelFile, metadata);
            }

            // Use LLM-style tokenizer config for LLM models (no BERT lowercasing)
            TokenizerConfig tokenizerConfig = type.isLlm()
                    ? TokenizerConfig.builder()
                            .doLowerCase(false)
                            .addSpecialTokens(true)
                            .stripAccents(false)
                            .maxLength(4096)
                            .padding("max_length")
                            .truncation(true)
                            .build()
                    : TokenizerConfig.defaultBertConfig();

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
                    .tokenizer(tokenizerConfig)
                    .audioSynthesis(audioSynthesis)
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
        if (!isSameDiffArtifact(visionEncoderFile)) {
            log.debug("Skipping vision encoder IO probe for non-SameDiff artifact: {}", visionEncoderFile);
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
            log.warn("Failed to auto-probe vision encoder IO config from {}", visionEncoderFile, e);
        }
    }

    /**
     * Get all models currently in staging.
     */
    @SuppressWarnings("unchecked")
    public List<ai.kompile.core.staging.StagingModelInfo> getStagingModels() {
        return (List<ai.kompile.core.staging.StagingModelInfo>) (List<?>) new ArrayList<>(stagingModels.values());
    }

    /**
     * Get the staging directory path.
     */
    public Path getStagingDirectory() {
        return stagingDir;
    }

    /**
     * Stage a completed training artifact manifest into verified staging, optionally promoting it.
     */
    public StagingModelInfo stageTrainingArtifact(TrainingArtifactStageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Training artifact staging request is required");
        }

        Path manifestPath = resolveTrainingArtifactManifestPath(request);
        Map<String, Object> manifest = readTrainingArtifactManifest(manifestPath);
        Path outputDir = resolveTrainingArtifactOutputDir(request, manifestPath, manifest);
        Path modelFile = resolveTrainingArtifactModelFile(outputDir, manifest);
        String modelId = sanitizeModelId(firstNonBlank(
                request.getModelId(),
                stringValue(manifest.get("trainedModelId")),
                stringValue(manifest.get("modelId"))));
        ModelType modelType = resolveTrainingArtifactModelType(request, manifest, modelFile);

        StagingModelInfo info = StagingModelInfo.create(modelId, "training-artifact:" + manifestPath, modelType);
        stagingModels.put(modelId, info);
        emitStagingStatus(modelId, info);

        Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
        try {
            if (Files.exists(verifiedDir)) {
                deleteDirectory(verifiedDir);
            }
            copyTrainingArtifactFiles(outputDir, manifestPath, modelFile, verifiedDir);

            info.setCurrentFile(modelFile.getFileName().toString());
            info.withStatus(StagingStatus.READY, 100, "Training artifact ready for promotion");
            emitStagingStatus(modelId, info);

            if (request.isAutoPromote()) {
                info.withStatus(StagingStatus.PROMOTING, 95, "Promoting training artifact to registry");
                emitStagingStatus(modelId, info);
                boolean promoted = promoteModel(modelId, buildTrainingArtifactMetadata(manifest, request, modelId));
                if (promoted) {
                    info.completed();
                } else {
                    info.withStatus(StagingStatus.READY, 100, "Training artifact ready for manual promotion");
                    emitStagingStatus(modelId, info);
                }
            }
        } catch (Exception e) {
            log.error("Failed to stage training artifact {} from {}", modelId, manifestPath, e);
            moveToFailed(verifiedDir, modelId);
            info.failed("Training artifact staging failed: " + e.getMessage());
            emitStagingStatus(modelId, info);
        }

        return info;
    }

    private Path resolveTrainingArtifactManifestPath(TrainingArtifactStageRequest request) {
        String manifestPath = request.getManifestPath();
        if (isBlank(manifestPath) && !isBlank(request.getOutputDir())) {
            manifestPath = Paths.get(request.getOutputDir()).resolve(TRAINING_ARTIFACT_MANIFEST_FILE).toString();
        }
        if (isBlank(manifestPath)) {
            throw new IllegalArgumentException("manifestPath or outputDir is required");
        }

        Path resolved = Paths.get(manifestPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Training artifact manifest not found: " + resolved);
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readTrainingArtifactManifest(Path manifestPath) {
        try {
            Object parsed = objectMapper.readValue(manifestPath.toFile(), Map.class);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("Training artifact manifest must be a JSON object: " + manifestPath);
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read training artifact manifest: " + manifestPath, e);
        }
    }

    private Path resolveTrainingArtifactOutputDir(TrainingArtifactStageRequest request,
                                                  Path manifestPath,
                                                  Map<String, Object> manifest) {
        String outputDir = firstNonBlank(request.getOutputDir(), stringValue(manifest.get("outputDir")));
        Path resolved = isBlank(outputDir)
                ? manifestPath.getParent()
                : Paths.get(outputDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Training artifact output directory not found: " + resolved);
        }
        return resolved;
    }

    private Path resolveTrainingArtifactModelFile(Path outputDir, Map<String, Object> manifest) {
        List<Path> candidates = new ArrayList<>();
        String modelPath = stringValue(manifest.get("modelPath"));
        if (!isBlank(modelPath)) {
            Path candidate = Paths.get(modelPath);
            if (!candidate.isAbsolute()) {
                candidate = outputDir.resolve(candidate);
            }
            candidates.add(candidate.toAbsolutePath().normalize());
        }

        String modelFile = stringValue(manifest.get("modelFile"));
        if (!isBlank(modelFile)) {
            candidates.add(outputDir.resolve(modelFile).toAbsolutePath().normalize());
        }

        try {
            Path discovered = findModelFile(outputDir);
            if (discovered != null) {
                candidates.add(discovered.toAbsolutePath().normalize());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to inspect training artifact output directory: " + outputDir, e);
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Training artifact model file not found in " + outputDir);
    }

    private ModelType resolveTrainingArtifactModelType(TrainingArtifactStageRequest request,
                                                       Map<String, Object> manifest,
                                                       Path modelFile) {
        Map<String, Object> registrySuggestion = mapValue(manifest.get("registrySuggestion"));
        String configuredType = firstNonBlank(
                request.getModelType(),
                stringValue(manifest.get("modelType")),
                stringValue(registrySuggestion.get("modelType")),
                stringValue(registrySuggestion.get("type")));
        if (!isBlank(configuredType)) {
            return ModelType.fromValue(configuredType);
        }

        String fileName = modelFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".gguf") || fileName.endsWith(".ggml")) {
            return ModelType.LLM_GGML;
        }
        return ModelType.DENSE_ENCODER;
    }

    private void copyTrainingArtifactFiles(Path outputDir,
                                           Path manifestPath,
                                           Path modelFile,
                                           Path verifiedDir) throws IOException {
        Files.createDirectories(verifiedDir);
        Files.copy(modelFile, verifiedDir.resolve(modelFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(manifestPath, verifiedDir.resolve(TRAINING_ARTIFACT_MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING);

        for (String artifactName : List.of(
                "tokenizer.json",
                "vocab.txt",
                "sentencepiece.model",
                "tokenizer_config.json",
                "merges.txt",
                "special_tokens_map.json",
                "added_tokens.json")) {
            Path optionalArtifact = outputDir.resolve(artifactName);
            if (Files.isRegularFile(optionalArtifact)) {
                Files.copy(optionalArtifact, verifiedDir.resolve(artifactName), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private ModelMetadata buildTrainingArtifactMetadata(Map<String, Object> manifest,
                                                        TrainingArtifactStageRequest request,
                                                        String modelId) {
        Map<String, Object> dataset = mapValue(manifest.get("dataset"));
        String baseModelId = stringValue(manifest.get("baseModelId"));
        String trainingType = stringValue(manifest.get("trainingType"));
        String taskId = stringValue(manifest.get("taskId"));
        String description = firstNonBlank(
                request.getDescription(),
                "Training artifact " + modelId + " from " + firstNonBlank(baseModelId, "unknown base model"));

        return ModelMetadata.builder()
                .framework("samediff")
                .modelType(isBlank(trainingType) ? "trained" : trainingType.toLowerCase(Locale.ROOT))
                .trainingData(firstNonBlank(
                        stringValue(dataset.get("datasetId")),
                        stringValue(dataset.get("sourcePath"))))
                .sourceOrigin("training-artifact")
                .sourceRepository(baseModelId)
                .originalFormat(stringValue(manifest.get("modelFormat")))
                .conversionDate(Instant.now().toString())
                .description(description)
                .version(taskId)
                .build();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, mapValue) -> map.put(String.valueOf(key), mapValue));
            return map;
        }
        return Collections.emptyMap();
    }

    private String sanitizeModelId(String modelId) {
        if (isBlank(modelId)) {
            throw new IllegalArgumentException("Training artifact manifest is missing trainedModelId");
        }
        String sanitized = modelId.trim().replace('\\', '/');
        int slash = sanitized.lastIndexOf('/');
        if (slash >= 0) {
            sanitized = sanitized.substring(slash + 1);
        }
        sanitized = sanitized.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (isBlank(sanitized)) {
            throw new IllegalArgumentException("Training artifact model id is empty after sanitization");
        }
        return sanitized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

        // Infer model type from file extension/format.
        String lowerName = modelPath.getFileName().toString().toLowerCase();
        ModelType inferredType = inferLocalModelType(lowerName, format);

        StagingModelInfo info = StagingModelInfo.create(modelId, source, inferredType);
        stagingModels.put(modelId, info);
        emitStagingStatus(modelId, info);

        // Run staging asynchronously
        executor.submit(() -> {
            try {
                // 1. Set up pending directory
                Path pendingDir = stagingDir.resolve("pending").resolve(modelId);
                // Clean up stale workspace from a prior failed/cancelled attempt so that
                // a re-staging run always gets a fresh directory.  Without this the ONNX
                // importer can encounter duplicate-variable errors from leftover artifacts.
                if (Files.exists(pendingDir)) {
                    log.info("Cleaning stale pending workspace for model {} before re-attempt", modelId);
                    deleteDirectory(pendingDir);
                }
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
                if (shouldConvert(localModelPath, inferredType, format)) {
                    info.withStatus(StagingStatus.CONVERTING, 40, "Converting to SameDiff format");

                    // GGUF/GGML models convert to sharded .sdnb; others use single .sdz
                    String localFileName = localModelPath.getFileName().toString().toLowerCase();
                    boolean isGguf = localFileName.endsWith(".gguf") || localFileName.endsWith(".ggml");
                    outputPath = pendingDir.resolve(isGguf ? "model.sdnb" : "model.sdz");

                    // Copy tokenizer.json from the source directory if present
                    if (isGguf) {
                        Path sourceTokenizer = modelPath.getParent() != null
                                ? modelPath.getParent().resolve("tokenizer.json") : null;
                        if (sourceTokenizer != null && Files.exists(sourceTokenizer)
                                && Files.size(sourceTokenizer) > 0) {
                            Files.copy(sourceTokenizer, pendingDir.resolve("tokenizer.json"),
                                    StandardCopyOption.REPLACE_EXISTING);
                            log.info("Copied tokenizer.json from source dir for model {}", modelId);
                        }
                    }

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

                // 3. Validate SameDiff artifacts. VLM pipeline manifests are not SameDiff graphs.
                info.withStatus(StagingStatus.VALIDATING, 70, "Validating model");

                if (requiresSameDiffValidation(outputPath, inferredType, format)) {
                    ConversionService.ValidationResult validationResult = conversionService.validate(outputPath);
                    if (!validationResult.isValid()) {
                        moveToFailed(pendingDir, modelId);
                        info.failed("Validation failed: " + validationResult.getErrorMessage());
                        return;
                    }
                } else {
                    log.info("Skipping SameDiff validation for non-SameDiff local model artifact: {}", outputPath);
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
                // Move partial pending workspace to failed so it doesn't accumulate
                // and interfere with a future retry for the same model.
                moveToFailed(stagingDir.resolve("pending").resolve(modelId), modelId);
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
                    log.warn("Error repairing model '{}'", modelId, e);
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
                } catch (Exception e) {
                    log.warn("Failed to complete SSE emitter for model '{}'", modelId, e);
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

    private boolean shouldConvert(Path modelPath, ModelType type, String format) {
        if (type != null && type.isVlm()) {
            return false;
        }
        String normalizedFormat = format != null ? format.trim().toLowerCase(Locale.ROOT) : "";
        if ("vlm".equals(normalizedFormat) || "vlm_pipeline".equals(normalizedFormat)) {
            return false;
        }
        return needsConversion(modelPath);
    }

    private ModelType inferLocalModelType(String lowerName, String format) {
        String normalizedFormat = format != null ? format.trim().toLowerCase(Locale.ROOT) : "";
        if ("vlm".equals(normalizedFormat)
                || "vlm_pipeline".equals(normalizedFormat)
                || "pipeline.json".equals(lowerName)) {
            return ModelType.VLM_PIPELINE;
        }
        if (lowerName != null && (lowerName.endsWith(".gguf") || lowerName.endsWith(".ggml"))) {
            return ModelType.LLM_GGML;
        }
        return ModelType.DENSE_ENCODER;
    }

    private boolean requiresSameDiffValidation(Path modelPath, ModelType type, String format) {
        if (type != null && type.isVlm()) {
            return false;
        }
        String normalizedFormat = format != null ? format.trim().toLowerCase(Locale.ROOT) : "";
        if ("vlm".equals(normalizedFormat) || "vlm_pipeline".equals(normalizedFormat)) {
            return false;
        }
        return isSameDiffArtifact(modelPath);
    }

    private boolean isSameDiffArtifact(Path modelPath) {
        if (modelPath == null || modelPath.getFileName() == null) {
            return false;
        }
        String name = modelPath.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".sdz")
                || name.endsWith(".fb")
                || name.endsWith(".sdnb")
                || (name.contains(".shard") && name.endsWith(".sdnb"));
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
            log.warn("Failed to download tokenizer for model '{}'", request.getModelId(), e);
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
        // First check for VLM runtime artifacts or real single-file SameDiff model.
        Path single = findFile(dir, "pipeline.json", "decoder_model_merged.onnx", "model.sdz", ".fb");
        if (single != null) return single;

        // Check for a single (non-sharded) .sdnb file with content
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(".sdnb") && !name.contains(".shard") && Files.size(path) > 0) {
                    return path;
                }
            }
        }

        // Look for sharded model files (shard0 is most representative)
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
     * Returns false if a single-file .sdz or .fb model exists with content,
     * because that takes precedence over any stale shard files.
     */
    private boolean isShardedModel(Path dir) throws IOException {
        // If a real single-file model exists, it is NOT sharded regardless of shard files
        Path sdz = dir.resolve("model.sdz");
        if (Files.exists(sdz) && Files.size(sdz) > 0) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String fname = path.getFileName().toString();
                if (fname.endsWith(".fb") && Files.size(path) > 0) {
                    return false;
                }
            }
        }
        // Now check for shard files
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
        if (!Files.exists(verifiedDir)) {
            return null;
        }

        // Infer model type by inspecting artefacts in the verified directory
        ModelType inferredType = ModelType.DENSE_ENCODER;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(verifiedDir)) {
            for (Path p : stream) {
                String fname = p.getFileName().toString().toLowerCase();
                if (fname.endsWith(".gguf") || fname.endsWith(".ggml")) {
                    inferredType = ModelType.LLM_GGML;
                    break;
                }
                if (fname.equals("tokenizer.json")) {
                    try {
                        if (Files.size(p) > 100) {
                            inferredType = ModelType.LLM_GGML;
                            // keep scanning — a .gguf file takes priority but tokenizer.json is sufficient
                        }
                    } catch (IOException ignored) { }
                }
            }
        } catch (IOException e) {
            log.warn("Could not inspect verified dir {} for type inference", verifiedDir, e);
        }

        StagingModelInfo info = new StagingModelInfo();
        info.setModelId(modelId);
        info.setType(inferredType);
        info.setStatus(StagingStatus.READY);
        return info;
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
            log.warn("Failed to calculate checksum for {}", file, e);
            return null;
        }
    }
}
