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
import ai.kompile.staging.conversion.ConversionArtifact;
import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.diagnostics.ImportDiagnosticCode;
import ai.kompile.staging.diagnostics.ImportDiagnosticEvent;
import ai.kompile.staging.diagnostics.ImportDiagnosticJournal;
import ai.kompile.staging.diagnostics.ImportDiagnosticSeverity;
import ai.kompile.staging.diagnostics.ImportPhase;
import ai.kompile.staging.download.*;
import ai.kompile.staging.download.DownloadProgress;
import ai.kompile.staging.http.SafeHttpTransport;
import ai.kompile.staging.optimization.OptimizationService;
import ai.kompile.staging.sdx.SdxProjectOutputService;
import ai.kompile.staging.web.dto.StageWithOptimizationRequest;
import ai.kompile.staging.web.dto.TrainingArtifactStageRequest;
import ai.kompile.modelmanager.registry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.ggml.format.GGUFHeader;
import org.nd4j.ggml.format.GGUFReader;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
    private final SdxProjectOutputService sdxProjectOutputService;
    private final StagingAssetLimits assetLimits;
    private final ImportDiagnosticJournal diagnosticJournal;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path stagingDir;
    private final Path modelsDir;

    // Track active staging operations
    private final Map<String, StagingModelInfo> stagingModels = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> stagingEmitters = new ConcurrentHashMap<>();
    private final Map<String, StagingOperation> activeOperations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // Auto-optimization configuration (set via API, applied to newly staged models)
    private volatile StageWithOptimizationRequest.OptimizationConfigDto autoOptimizationConfig;

    @Autowired
    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices,
                          OptimizationService optimizationService,
                          SdxProjectOutputService sdxProjectOutputService,
                          StagingAssetLimits assetLimits,
                          ImportDiagnosticJournal diagnosticJournal) {
        this.registryService = registryService;
        this.conversionService = conversionService;
        this.downloadServices = downloadServices;
        this.optimizationService = optimizationService;
        this.sdxProjectOutputService = sdxProjectOutputService;
        this.assetLimits = assetLimits == null ? new StagingAssetLimits() : assetLimits;
        this.diagnosticJournal = diagnosticJournal == null
                ? new ImportDiagnosticJournal()
                : diagnosticJournal;
        this.modelsDir = registryService.getModelDir().toAbsolutePath().normalize();
        this.stagingDir = modelsDir.resolve(".staging").normalize();
        ensureDirectories();
    }

    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices,
                          OptimizationService optimizationService,
                          SdxProjectOutputService sdxProjectOutputService,
                          StagingAssetLimits assetLimits) {
        this(
                registryService,
                conversionService,
                downloadServices,
                optimizationService,
                sdxProjectOutputService,
                assetLimits,
                new ImportDiagnosticJournal());
    }

    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices,
                          OptimizationService optimizationService,
                          SdxProjectOutputService sdxProjectOutputService) {
        this(
                registryService,
                conversionService,
                downloadServices,
                optimizationService,
                sdxProjectOutputService,
                new StagingAssetLimits());
    }

    /**
     * Compatibility constructor for focused unit tests and non-Spring embedders that
     * do not request mobile project output.
     */
    public StagingService(RegistryService registryService,
                          ConversionService conversionService,
                          List<DownloadService> downloadServices,
                          OptimizationService optimizationService) {
        this(
                registryService,
                conversionService,
                downloadServices,
                optimizationService,
                null,
                new StagingAssetLimits());
    }

    public StageWithOptimizationRequest.OptimizationConfigDto getAutoOptimizationConfig() {
        return autoOptimizationConfig;
    }

    public void setAutoOptimizationConfig(StageWithOptimizationRequest.OptimizationConfigDto config) {
        this.autoOptimizationConfig = config;
        log.info("Auto-optimization config {}", config != null ? "set" : "cleared");
    }

    public List<ImportDiagnosticEvent> getImportDiagnostics(int limit) {
        return diagnosticJournal.recent(limit);
    }

    public List<ImportDiagnosticEvent> getImportDiagnostics(String attemptId) {
        return diagnosticJournal.attempt(attemptId);
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
        StagingOperation operation = beginOperation(request);
        Future<?> worker = executor.submit(() -> runAsyncOperation(request, operation));
        operation.attachWorker(worker);
        return operation.completion;
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
        StagingOperation operation = beginOperation(request);
        if (!operation.markStarted()) {
            return operation.info;
        }
        try {
            return executeStage(request, progressCallback == null ? progress -> {} : progressCallback, operation);
        } finally {
            finishOperation(operation);
        }
    }

    private ai.kompile.core.staging.StagingModelInfo executeStage(
            DownloadRequest request,
            Consumer<ai.kompile.core.staging.StagingModelInfo> progressCallback,
            StagingOperation operation) {
        String modelId = operation.modelId;
        String source = operation.source;
        StagingModelInfo info = operation.info;
        ImportPhase diagnosticPhase = ImportPhase.RESOLVE;
        progressCallback.accept(info);
        emitStagingStatus(modelId, info);

        try {
            operation.checkpoint();
            // 1. Resolve/discover/select and download.
            boolean repositoryDiscovery = "huggingface".equalsIgnoreCase(request.getSource());
            if (repositoryDiscovery) {
                diagnosticPhase = ImportPhase.DISCOVER;
                recordDiagnostic(
                        operation,
                        diagnosticPhase,
                        ImportDiagnosticCode.DISCOVERY_STARTED,
                        "Discovering runnable model and tokenizer/config assets.",
                        diagnosticDetails(request));
            } else if (ComponentUrlDownloader.isComponentSource(request.getSource())) {
                diagnosticPhase = ImportPhase.SELECT;
                recordDiagnostic(
                        operation,
                        diagnosticPhase,
                        ImportDiagnosticCode.ASSETS_SELECTED,
                        "Selected the explicit model, tokenizer, and configuration URLs.",
                        diagnosticDetails(request));
            }
            diagnosticPhase = ImportPhase.DOWNLOAD;
            recordDiagnostic(
                    operation,
                    diagnosticPhase,
                    ImportDiagnosticCode.DOWNLOAD_STARTED,
                    "Downloading the selected model bundle.",
                    diagnosticDetails(request));
            info.withStatus(StagingStatus.DOWNLOADING, 5, "Downloading from " + source);
            progressCallback.accept(info);

            Path pendingDir = workspace("pending", modelId);

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

            operation.checkpoint();
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
            }, operation);
            operation.checkpoint();

            if (!downloadResult.isSuccess()) {
                throw new IOException("Download failed: " + downloadResult.getErrorMessage());
            }
            if (repositoryDiscovery) {
                recordDiagnostic(
                        operation,
                        ImportPhase.DISCOVER,
                        ImportDiagnosticCode.DISCOVERY_COMPLETE,
                        "Repository discovery resolved an immutable revision and runnable assets.",
                        diagnosticDetails(request));
                recordDiagnostic(
                        operation,
                        ImportPhase.SELECT,
                        ImportDiagnosticCode.ASSETS_SELECTED,
                        "Selected the exact discovered model and companion assets.",
                        diagnosticDetails(request));
            }
            Map<String, Object> downloadedDetails = new LinkedHashMap<>(diagnosticDetails(request));
            downloadedDetails.put("downloadedBytes", downloadResult.getTotalBytes());
            putDiagnosticDetail(downloadedDetails, "bundleChecksum", downloadResult.getChecksum());
            recordDiagnostic(
                    operation,
                    ImportPhase.DOWNLOAD,
                    ImportDiagnosticCode.DOWNLOAD_COMPLETE,
                    "Downloaded the selected model bundle.",
                    downloadedDetails);

            // 2. Convert if needed
            Path modelPath = downloadResult.getModelPath();
            Path outputPath;
            ConversionArtifact conversionArtifact = null;

            boolean convertVlmBundle = isVlmPipeline(request.getModelType(), request.getFormat())
                    && hasVlmOnnxComponents(pendingDir);
            boolean convertSingleModel = shouldConvert(
                    modelPath, request.getModelType(), request.getFormat());

            if (convertVlmBundle) {
                diagnosticPhase = ImportPhase.COMPILE;
                recordDiagnostic(
                        operation,
                        diagnosticPhase,
                        ImportDiagnosticCode.COMPILE_STARTED,
                        "Compiling the VLM component bundle into runtime-owned SameDiff/SDZ artifacts.",
                        diagnosticDetails(request));
                info.withStatus(StagingStatus.CONVERTING, 40, "Converting VLM components to SameDiff format");
                progressCallback.accept(info);

                outputPath = convertVlmBundle(pendingDir, operation);
                recordDiagnostic(
                        operation,
                        ImportPhase.COMPILE,
                        ImportDiagnosticCode.COMPILE_COMPLETE,
                        "Compiled and validated the multipart VLM SameDiff/SDZ bundle.",
                        Map.of("artifact", outputPath.getFileName().toString()));
            } else if (convertSingleModel) {
                diagnosticPhase = ImportPhase.COMPILE;
                recordDiagnostic(
                        operation,
                        diagnosticPhase,
                        ImportDiagnosticCode.COMPILE_STARTED,
                        "Compiling the source model into the canonical SameDiff/SDZ artifact.",
                        diagnosticDetails(request));
                info.withStatus(StagingStatus.CONVERTING, 40, "Converting to SameDiff format");
                progressCallback.accept(info);

                outputPath = pendingDir.resolve("model.sdz");
                ConversionResult conversionResult = conversionService.convert(
                        modelPath, outputPath, request.getFormat(), operation);

                if (!conversionResult.isSuccess()) {
                    throw new IOException("Conversion failed: " + conversionResult.getErrorMessage());
                }
                conversionArtifact = conversionResult.getArtifact();
                outputPath = conversionArtifact.requireCanonicalSdz();
                recordDiagnostic(
                        operation,
                        ImportPhase.COMPILE,
                        ImportDiagnosticCode.COMPILE_COMPLETE,
                        "Compiled one canonical SameDiff/SDZ artifact.",
                        Map.of("artifact", outputPath.getFileName().toString()));
            } else {
                outputPath = modelPath;
                if (isCanonicalSdz(outputPath)) {
                    conversionArtifact = ConversionArtifact.canonicalSdz(outputPath);
                }
            }
            operation.checkpoint();

            // 2b. Download tokenizer if needed for converted artifacts.
            if (shouldConvert(modelPath, request.getModelType(), request.getFormat())) {
                ensureTokenizer(request, pendingDir, modelPath);
                ensureChatTemplate(request, pendingDir, modelPath);
            }
            operation.checkpoint();

            // 3. Validate SameDiff artifacts. VLM pipeline manifests are validated by their
            // pipeline loader at runtime and must not be loaded as SameDiff graphs.
            diagnosticPhase = ImportPhase.VALIDATE;
            recordDiagnostic(
                    operation,
                    diagnosticPhase,
                    ImportDiagnosticCode.VALIDATION_STARTED,
                    "Validating the model and runnable chat asset bundle.",
                    diagnosticDetails(request));
            info.withStatus(StagingStatus.VALIDATING, 70, "Validating model");
            progressCallback.accept(info);

            if (requiresSameDiffValidation(outputPath, request.getModelType(), request.getFormat())) {
                ConversionService.ValidationResult validationResult = conversionService.validate(outputPath);
                if (!validationResult.isValid()) {
                    throw new IOException("Validation failed: " + validationResult.getErrorMessage());
                }
            } else {
                log.info("Skipping SameDiff validation for non-SameDiff model artifact: {}", outputPath);
            }
            recordDiagnostic(
                    operation,
                    ImportPhase.VALIDATE,
                    ImportDiagnosticCode.VALIDATION_COMPLETE,
                    "Validated the staged model and its runnable chat assets.",
                    diagnosticDetails(request));

            // Persist the trusted serving ABI with the staged artifact so promotion
            // remains correct across service restarts.
            if (request.getModelType() == ModelType.AUDIO_SYNTHESIS) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                        pendingDir.resolve(AUDIO_SYNTHESIS_CONFIG_FILE).toFile(),
                        request.getAudioSynthesis());
            }

            // 3b. Target compilation is host-only and content-addressed. Model output is the
            // complete enriched SDZ; project output wraps those exact bytes with project.kgraph
            // and Markdown provenance. No provider format or CPU fallback becomes an input.
            boolean targetOutput = SdxProjectOutputService.isTargetOutputRequested(request);
            boolean projectOutput = SdxProjectOutputService.isProjectOutputRequested(request);
            if (targetOutput) {
                if (sdxProjectOutputService == null) {
                    throw new IllegalStateException(
                            "Mobile SDX output is unavailable in this staging service");
                }
                String compileMessage = projectOutput
                        ? "Compiling the exact mobile target and packaging the synced graph project."
                        : "Compiling the exact mobile target and packaging a complete chat model.";
                diagnosticPhase = ImportPhase.COMPILE;
                recordDiagnostic(
                        operation,
                        diagnosticPhase,
                        ImportDiagnosticCode.COMPILE_STARTED,
                        compileMessage,
                        diagnosticDetails(request));
                info.withStatus(
                        StagingStatus.VALIDATING,
                        82,
                        compileMessage);
                progressCallback.accept(info);
                emitStagingStatus(modelId, info);
                if (conversionArtifact == null) {
                    throw new IOException(
                            "Mobile target output requires one canonical .sdz artifact");
                }
                Path mobileOutput = sdxProjectOutputService.createOutput(
                        pendingDir, conversionArtifact, request, operation);
                info.setCurrentFile(
                        pendingDir.relativize(mobileOutput).toString()
                                .replace(File.separatorChar, '/'));
                recordDiagnostic(
                        operation,
                        ImportPhase.COMPILE,
                        ImportDiagnosticCode.COMPILE_COMPLETE,
                        projectOutput
                                ? "Compiled the model and packaged the synced graph/Markdown project."
                                : "Compiled and packaged the complete accelerator chat model.",
                        Map.of("artifact", mobileOutput.getFileName().toString()));
            }

            // 4. Move to verified staging
            info.withStatus(
                    StagingStatus.READY,
                    90,
                    projectOutput
                            ? "Offline graph-chat project ready for download"
                            : targetOutput
                                    ? "Accelerator chat model (.sdz) ready for download"
                                    : "Model ready for promotion");
            progressCallback.accept(info);

            operation.checkpoint();
            Path verifiedDir = workspace("verified", modelId);
            if (Files.exists(verifiedDir, LinkOption.NOFOLLOW_LINKS)) {
                deleteDirectory(verifiedDir);
            }
            moveDirectory(pendingDir, verifiedDir);
            operation.checkpoint();

            diagnosticPhase = ImportPhase.CACHE;
            recordDiagnostic(
                    operation,
                    diagnosticPhase,
                    ImportDiagnosticCode.CACHE_COMPLETE,
                    "Stored the verified, content-addressed staging output.",
                    Map.of("workspace", "verified/" + modelId));
            info.completed();
            progressCallback.accept(info);
            recordDiagnostic(
                    operation,
                    ImportPhase.CACHE,
                    ImportDiagnosticCode.IMPORT_COMPLETE,
                    "The model import is complete and ready for use.",
                    diagnosticDetails(request));

            log.info("Model {} staged successfully", modelId);
            return info;

        } catch (CancellationException cancelled) {
            cleanupCancelledWorkspaces(modelId);
            diagnosticJournal.record(
                    operation.attemptId,
                    operation.modelId,
                    operation.source,
                    diagnosticPhase,
                    ImportDiagnosticCode.IMPORT_CANCELLED,
                    ImportDiagnosticSeverity.INFO,
                    "The import was cancelled by the user.",
                    "",
                    Map.of());
            info.cancelled("Cancelled by user");
            progressCallback.accept(info);
            emitStagingStatus(modelId, info);
            return info;
        } catch (Exception e) {
            ImportDiagnosticEvent failure = diagnosticJournal.failure(
                    operation.attemptId,
                    operation.modelId,
                    operation.source,
                    diagnosticPhase,
                    e);
            log.error("Staging failed for model {} during {} ({})",
                    modelId, diagnosticPhase.value(), e.getClass().getSimpleName());
            log.debug("Staging failure details for model {}", modelId, e);
            moveToFailed(workspace("pending", modelId), modelId);
            StagingModelInfo failed = info.failed("Staging failed: " + failure.summary());
            progressCallback.accept(failed);
            emitStagingStatus(modelId, failed);
            return failed;
        }
    }

    /**
     * Promote a staged model to production.
     */
    public boolean promoteModel(String modelId, ModelMetadata metadata) {
        ModelIdPolicy.requireValid(modelId);
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
            ModelType type = info.getType() instanceof ModelType ? (ModelType) info.getType() : ModelType.DENSE_ENCODER;
            Path verifiedDir = stagingDir.resolve("verified").resolve(modelId);
            Path productionDir = modelsDir.resolve(type.getDirectoryName()).resolve(modelId);
            boolean movePending = Files.isDirectory(verifiedDir);
            Path artifactDir;
            if (movePending) {
                artifactDir = verifiedDir;
            } else if (Files.isDirectory(productionDir)) {
                // Promotion may have failed after the verified bundle was moved but before the
                // registry commit. Resume from that production bundle instead of stranding a
                // multi-gigabyte model in an unregistered state.
                log.warn("Verified directory missing for {}; resuming promotion from {}",
                        modelId, productionDir);
                artifactDir = productionDir;
            } else {
                log.error("Neither verified nor recoverable production directory found for model {}", modelId);
                return false;
            }

            AudioSynthesisConfig audioSynthesis = null;
            if (type == ModelType.AUDIO_SYNTHESIS) {
                Path audioConfigPath = artifactDir.resolve(AUDIO_SYNTHESIS_CONFIG_FILE);
                if (!Files.isRegularFile(audioConfigPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(audioConfigPath)) {
                    log.error("Verified audio synthesis configuration is missing for {}", modelId);
                    return false;
                }
                audioSynthesis = objectMapper.readValue(
                        audioConfigPath.toFile(), AudioSynthesisConfig.class);
            }

            // Find model and vocab files using shard-aware helpers
            Path modelFile = findModelFile(artifactDir);
            Path vocabFile = findVocabFile(artifactDir);
            boolean sharded = isShardedModel(artifactDir);

            // For sharded models, the logical base name is "model.sdnb" and we also
            // create a 0-byte marker file so SameDiff.load() can discover the shards.
            String modelFileName;
            if (sharded) {
                modelFileName = "model.sdnb";
                // Create 0-byte marker if not already present
                Path marker = artifactDir.resolve("model.sdnb");
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
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(artifactDir)) {
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
                probeVisionEncoderIOConfig(artifactDir, modelFile, metadata);
            }
            if (type.isLlm()
                    && (metadata.getMaxSequenceLength() == null || metadata.getMaxSequenceLength() <= 0)) {
                enrichLlmContextFromGguf(artifactDir, modelFile, metadata);
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

            // All potentially expensive validation and hashing happens before the move. If a
            // later registry write fails, the next promotion request resumes from production.
            if (movePending) {
                moveDirectory(verifiedDir, productionDir);
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
     * Persist the GGUF-declared context window ({@code <arch>.context_length}) into the registry
     * entry's metadata at promotion time, so every registry consumer budgets from the model's real
     * window instead of a small default (LFM2.5 declares 128k; the old default budgeted 2k).
     * Prefers the promoted model file; falls back to the first {@code .gguf} in the production dir
     * (sharded promotions register a marker as the model file).
     */
    private void enrichLlmContextFromGguf(Path productionDir, Path modelFile, ModelMetadata metadata) {
        try {
            Path gguf = null;
            if (modelFile != null
                    && modelFile.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
                gguf = modelFile;
            } else if (productionDir != null && Files.isDirectory(productionDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(productionDir, "*.gguf")) {
                    for (Path p : ds) {
                        gguf = p;
                        break;
                    }
                }
            }
            if (gguf == null) {
                return;
            }
            Integer context = ai.kompile.utils.GgufMetadataReader.readContextLength(gguf).orElse(null);
            if (context != null && context > 0) {
                metadata.setMaxSequenceLength(context);
                log.info("Registry metadata: context window {} read from GGUF header {} at promotion",
                        context, gguf.getFileName());
            }
        } catch (Exception e) {
            log.debug("GGUF context enrichment skipped: {}", e.getMessage());
        }
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
        String modelId = ModelIdPolicy.requireValid(sanitizeModelId(firstNonBlank(
                request.getModelId(),
                stringValue(manifest.get("trainedModelId")),
                stringValue(manifest.get("modelId")))));
        ModelType modelType = resolveTrainingArtifactModelType(request, manifest, modelFile);

        StagingModelInfo info = StagingModelInfo.create(modelId, "training-artifact:" + manifestPath, modelType);
        stagingModels.put(modelId, info);
        emitStagingStatus(modelId, info);

        Path verifiedDir = workspace("verified", modelId);
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
        String validModelId = ModelIdPolicy.requireValid(modelId);
        Path modelPath = Paths.get(Objects.requireNonNull(filePath, "filePath"))
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(modelPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(modelPath)) {
            throw new IllegalArgumentException("Trusted local model is not a regular file: " + modelPath);
        }

        String lowerName = modelPath.getFileName().toString().toLowerCase(Locale.ROOT);
        ModelType inferredType = inferLocalModelType(lowerName, format);
        Path sourceDirectory = modelPath.getParent();
        if (sourceDirectory == null) {
            throw new IllegalArgumentException("Trusted local model has no parent directory");
        }

        Map<String, String> files = new LinkedHashMap<>();
        files.put(TextModelAssetMap.MODEL, modelPath.getFileName().toString());
        for (Map.Entry<String, String> asset : Map.of(
                TextModelAssetMap.TOKENIZER, TextModelAssetMap.TOKENIZER_FILE,
                TextModelAssetMap.TOKENIZER_CONFIG, TextModelAssetMap.TOKENIZER_CONFIG_FILE,
                TextModelAssetMap.MODEL_CONFIG, TextModelAssetMap.MODEL_CONFIG_FILE,
                TextModelAssetMap.GENERATION_CONFIG, TextModelAssetMap.GENERATION_CONFIG_FILE,
                TextModelAssetMap.SPECIAL_TOKENS_MAP, TextModelAssetMap.SPECIAL_TOKENS_MAP_FILE,
                TextModelAssetMap.ADDED_TOKENS, TextModelAssetMap.ADDED_TOKENS_FILE,
                TextModelAssetMap.CHAT_TEMPLATE, TextModelAssetMap.CHAT_TEMPLATE_FILE).entrySet()) {
            if (Files.isRegularFile(sourceDirectory.resolve(asset.getValue()), LinkOption.NOFOLLOW_LINKS)) {
                files.put(asset.getKey(), asset.getValue());
            }
        }
        if (inferredType.isVlm()) {
            addFirstExistingLocalAsset(
                    files,
                    sourceDirectory,
                    "vlm.model.vision_encoder",
                    "vision_encoder.onnx",
                    "encoder.onnx",
                    "vision_encoder.sdz",
                    "encoder.sdz");
            addFirstExistingLocalAsset(
                    files,
                    sourceDirectory,
                    "vlm.model.embed_tokens",
                    "embed_tokens.onnx",
                    "embeddings.onnx",
                    "embed_tokens.sdz",
                    "embeddings.sdz");
            addFirstExistingLocalAsset(
                    files,
                    sourceDirectory,
                    "vlm.model.decoder",
                    "decoder_model_merged.onnx",
                    "decoder_model.onnx",
                    "decoder.onnx",
                    "decoder.sdz",
                    "decoder_model.sdz");
            addFirstExistingLocalAsset(
                    files,
                    sourceDirectory,
                    "vlm.preprocessor_config",
                    "preprocessor_config.json",
                    "processor_config.json");
        }

        DownloadRequest request = DownloadRequest.builder()
                .source("trusted-local")
                .repository(sourceDirectory.toString())
                .format(format)
                .modelType(inferredType)
                .modelId(validModelId)
                .files(files)
                .textAssets(TextModelAssetMap.fromFileMap(files))
                .build();
        CompletableFuture<StagingModelInfo> completion = stageModelAsync(request);
        if (autoPromote) {
            completion.thenAccept(staged -> {
                if (staged.getStatus() == StagingStatus.COMPLETED
                        || staged.getStatus() == StagingStatus.READY) {
                    promoteModel(validModelId, null);
                }
            });
        }
        return stagingModels.get(validModelId);
    }

    private void addFirstExistingLocalAsset(
            Map<String, String> files,
            Path sourceDirectory,
            String key,
            String... names) {
        for (String name : names) {
            if (Files.isRegularFile(sourceDirectory.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                files.put(key, name);
                return;
            }
        }
    }

    /**
     * Get staging info for a specific model.
     */
    public StagingModelInfo getStagingModel(String modelId) {
        return stagingModels.get(ModelIdPolicy.requireValid(modelId));
    }

    /**
     * Resolve the single completed mobile .sdz or .kproject for a staged model. Pending, failed,
     * ambiguous, symbolic-link, and path-traversal cases are deliberately invisible.
     */
    public Optional<Path> getStagedOutput(String modelId) {
        ModelIdPolicy.requireValid(modelId);
        Path verifiedRoot = stagingDir.resolve("verified").toAbsolutePath().normalize();
        Path outputDir = verifiedRoot.resolve(modelId).resolve("outputs").normalize();
        if (!outputDir.startsWith(verifiedRoot)
                || !Files.isDirectory(outputDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(outputDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> outputs = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".sdz") || name.endsWith(".kproject");
                    })
                    .sorted()
                    .toList();
            if (outputs.size() != 1) {
                if (outputs.size() > 1) {
                    log.error("Staged model {} has ambiguous mobile artifact outputs", modelId);
                }
                return Optional.empty();
            }
            return Optional.of(outputs.get(0));
        } catch (IOException e) {
            log.warn("Could not resolve staged mobile artifact for {}", modelId, e);
            return Optional.empty();
        }
    }

    /**
     * Cancel a staging operation.
     */
    public boolean cancelStaging(String modelId) {
        String validModelId = ModelIdPolicy.requireValid(modelId);
        StagingOperation operation = activeOperations.get(validModelId);
        if (operation == null || !operation.requestCancellation()) {
            return false;
        }
        if (!operation.started.get()) {
            completeCancelledBeforeStart(operation);
        }
        try {
            if (!operation.quiesced.await(
                    assetLimits.getCancellationWaitMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Timed out waiting for staging operation {} to quiesce", validModelId);
                return false;
            }
            return operation.info.getStatus() == StagingStatus.CANCELLED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
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
     * Repair staged models by checking for missing vocabulary and tokenizer protocol metadata.
     *
     * <p>GGUF-derived SameDiff graphs staged before tokenizer metadata preservation was added may
     * have a valid {@code tokenizer.json} but no {@code tokenizer_config.json}. For those models,
     * repair deterministically backfills the source container's chat template and BOS/EOS/PAD
     * tokens. It never invents protocol tokens and never overwrites metadata already present.</p>
     *
     * @return model IDs mapped to whether all applicable repairs succeeded
     */
    public Map<String, Boolean> repairStagedModels() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        ModelRegistry registry = registryService.loadRegistry();

        for (Map.Entry<String, ModelEntry> entry : registry.getAllModels().entrySet()) {
            String modelId = entry.getKey();
            ModelEntry model = entry.getValue();

            if (model.getPath() == null) {
                continue;
            }

            Path modelPath = modelsDir.resolve(model.getPath());
            if (!Files.exists(modelPath)) {
                results.put(modelId, false);
                continue;
            }

            boolean vocabOk = true;
            Path vocabPath = modelsDir.resolve(model.getVocabFilePath());
            if (!Files.exists(vocabPath)) {
                try {
                    Path found = findFile(
                            modelPath, "vocab.txt", "tokenizer.json", "sentencepiece.model");
                    if (found != null) {
                        model.setVocabFile(found.getFileName().toString());
                        registryService.addModel(model);
                        log.info("Repaired vocab path for model {}: {}", modelId, found.getFileName());
                    } else {
                        vocabOk = false;
                        log.warn("No vocab file found for model {}", modelId);
                    }
                } catch (IOException e) {
                    vocabOk = false;
                    log.warn("Error repairing vocabulary for model '{}'", modelId, e);
                }
            }

            boolean tokenizerMetadataOk = true;
            if (model.getType() == ModelType.LLM_GGML) {
                tokenizerMetadataOk =
                        ensureTokenizerMetadata(modelId, modelPath, null);
            }
            results.put(modelId, vocabOk && tokenizerMetadataOk);
        }

        return results;
    }

    /**
     * Repair tokenizer protocol metadata for one registered GGUF-derived model.
     *
     * <p>This is the targeted migration counterpart to {@link #repairStagedModels()} and is useful
     * when a caller must validate one model before loading it.</p>
     */
    public boolean repairTokenizerMetadata(String modelId) {
        Optional<ModelEntry> registered = registryService.getModel(modelId);
        if (registered.isEmpty() || registered.get().getPath() == null) {
            return false;
        }
        ModelEntry model = registered.get();
        if (model.getType() != ModelType.LLM_GGML) {
            return true;
        }
        Path modelPath = modelsDir.resolve(model.getPath());
        return Files.isDirectory(modelPath)
                && ensureTokenizerMetadata(modelId, modelPath, null);
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
        ModelIdPolicy.requireValid(modelId);
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

    private void recordDiagnostic(
            StagingOperation operation,
            ImportPhase phase,
            ImportDiagnosticCode code,
            String summary,
            Map<String, ?> details) {
        diagnosticJournal.record(
                operation.attemptId,
                operation.modelId,
                operation.source,
                phase,
                code,
                ImportDiagnosticSeverity.INFO,
                summary,
                "",
                details);
    }

    private Map<String, Object> diagnosticDetails(DownloadRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        putDiagnosticDetail(details, "format", request.getFormat());
        putDiagnosticDetail(details, "outputFormat", request.getOutputFormat());
        putDiagnosticDetail(details, "targetProfile", request.getTargetProfile());
        putDiagnosticDetail(details, "quantizationProfile", request.getQuantizationProfile());
        putDiagnosticDetail(details, "targetSoc", request.getTargetSoc());
        putDiagnosticDetail(details, "requestedRevision", request.getRequestedRevision());
        putDiagnosticDetail(details, "resolvedRevision", request.getRevision());
        putDiagnosticDetail(details, "sourceReference", request.getSourceReference());

        Map<String, String> assets = request.effectiveSourceAssetProvenance();
        if (assets.isEmpty() && request.getTextAssetUrls() != null) {
            assets = request.getTextAssetUrls().toUrlMap();
        }
        for (Map.Entry<String, String> asset : assets.entrySet()) {
            putDiagnosticDetail(details, "asset." + asset.getKey(), asset.getValue());
        }
        if (!assets.isEmpty()) {
            details.put("selectedAssetCount", assets.size());
        }
        return details;
    }

    private static void putDiagnosticDetail(
            Map<String, Object> details,
            String key,
            Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            details.put(key, value);
        }
    }

    private StagingOperation beginOperation(DownloadRequest request) {
        Objects.requireNonNull(request, "request");
        String modelId = ModelIdPolicy.requireValid(request.getModelId());
        if (isBlank(request.getSource())) {
            throw new IllegalArgumentException("Staging source is required");
        }
        boolean componentBundle =
                ComponentUrlDownloader.isComponentSource(request.getSource());
        if (isBlank(request.getRepository()) && !componentBundle) {
            throw new IllegalArgumentException("Staging repository or upload handle is required");
        }
        if (request.getModelType() == ModelType.AUDIO_SYNTHESIS
                && request.getAudioSynthesis() == null) {
            throw new IllegalArgumentException(
                    "audio_synthesis configuration is required for an audio synthesis model");
        }
        String source = request.getSource() + ":"
                + (componentBundle ? "component-bundle" : request.getRepository());
        String attemptId = diagnosticJournal.start(
                modelId,
                source,
                diagnosticDetails(request));
        diagnosticJournal.record(
                attemptId,
                modelId,
                source,
                ImportPhase.RESOLVE,
                ImportDiagnosticCode.SOURCE_RESOLVED,
                ImportDiagnosticSeverity.INFO,
                componentBundle
                        ? "Resolved a complete repository-free component bundle."
                        : "Resolved the requested model source.",
                "",
                diagnosticDetails(request));
        StagingModelInfo info = StagingModelInfo.create(modelId, source, request.getModelType());
        StagingOperation operation = new StagingOperation(modelId, source, attemptId, info);
        StagingOperation active = activeOperations.putIfAbsent(modelId, operation);
        if (active != null) {
            throw new IllegalStateException("A staging operation is already active for model " + modelId);
        }
        stagingModels.put(modelId, info);
        return operation;
    }

    private void runAsyncOperation(DownloadRequest request, StagingOperation operation) {
        if (!operation.markStarted()) {
            return;
        }
        try {
            StagingModelInfo result = executeStage(request, progress -> {}, operation);
            operation.completion.complete(result);
        } catch (Throwable failure) {
            operation.completion.completeExceptionally(failure);
        } finally {
            finishOperation(operation);
        }
    }

    private void finishOperation(StagingOperation operation) {
        if (!operation.finished.compareAndSet(false, true)) {
            return;
        }
        activeOperations.remove(operation.modelId, operation);
        operation.runningThread = null;
        operation.quiesced.countDown();
        if (operation.info.getStatus().isTerminal()) {
            completeStagingEmitters(operation.modelId);
        }
    }

    private void completeCancelledBeforeStart(StagingOperation operation) {
        if (operation.started.get()) {
            return;
        }
        cleanupCancelledWorkspaces(operation.modelId);
        operation.info.cancelled("Cancelled by user");
        emitStagingStatus(operation.modelId, operation.info);
        operation.completion.complete(operation.info);
        finishOperation(operation);
    }

    private void cleanupCancelledWorkspaces(String modelId) {
        for (String phase : List.of("pending", "verified")) {
            try {
                deleteDirectory(workspace(phase, modelId));
            } catch (IOException cleanupFailure) {
                log.warn("Could not clean cancelled {} workspace for {}", phase, modelId, cleanupFailure);
            }
        }
    }

    private Path workspace(String phase, String modelId) {
        Path root = stagingDir.resolve(phase).toAbsolutePath().normalize();
        return ModelIdPolicy.contained(root, modelId);
    }

    private boolean isCanonicalSdz(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sdz");
    }

    private DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    private DownloadResult download(DownloadRequest request, Path destination,
                                     Consumer<DownloadProgress> progressCallback) {
        return download(request, destination, progressCallback, StagingCancellation.NONE);
    }

    private DownloadResult download(
            DownloadRequest request,
            Path destination,
            Consumer<DownloadProgress> progressCallback,
            StagingCancellation cancellation) {
        cancellation.checkpoint();
        for (DownloadService downloader : downloadServices) {
            if (downloader.canHandle(request.getSource())) {
                return downloader.download(request, destination, progressCallback, cancellation);
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

    private boolean isVlmPipeline(ModelType type, String format) {
        if (type != null && type.isVlm()) {
            return true;
        }
        String normalizedFormat = format != null ? format.trim().toLowerCase(Locale.ROOT) : "";
        return "vlm".equals(normalizedFormat) || "vlm_pipeline".equals(normalizedFormat);
    }

    private boolean hasVlmOnnxComponents(Path directory) throws IOException {
        return findFile(
                directory,
                "decoder_model_merged.onnx",
                "decoder_model.onnx",
                "decoder.onnx",
                "vision_encoder.onnx",
                "embed_tokens.onnx") != null;
    }

    /**
     * Convert a downloaded VLM bundle before it becomes runnable. Native inference workers
     * consume SDZ and must never become responsible for ONNX importer availability.
     */
    private Path convertVlmBundle(Path directory, StagingCancellation cancellation) throws IOException {
        Path decoder = findFile(
                directory,
                "decoder_model_merged.onnx",
                "decoder_model.onnx",
                "decoder.onnx");
        Path tokenizer = findFile(directory, "tokenizer.json");
        if (decoder == null) {
            throw new IOException(
                    "VLM conversion requires decoder_model_merged.onnx, decoder_model.onnx, or decoder.onnx");
        }
        if (tokenizer == null) {
            throw new IOException("VLM conversion requires tokenizer.json");
        }

        Path vision = findFile(directory, "vision_encoder.onnx", "encoder.onnx");
        Path embedTokens = findFile(directory, "embed_tokens.onnx", "embeddings.onnx");

        convertVlmComponent(vision, directory.resolve("vision_encoder.sdz"), cancellation, true);
        convertVlmComponent(embedTokens, directory.resolve("embed_tokens.sdz"), cancellation, false);
        return convertVlmComponent(decoder, directory.resolve("decoder.sdz"), cancellation, true);
    }

    private Path convertVlmComponent(
            Path source,
            Path target,
            StagingCancellation cancellation,
            boolean required) throws IOException {
        if (source == null) {
            if (required) {
                throw new IOException("Required VLM component is missing for " + target.getFileName());
            }
            return null;
        }

        cancellation.checkpoint();
        ConversionResult result = conversionService.convertVlmOnnx(source, target, cancellation);
        if (!result.isSuccess() || result.getArtifact() == null) {
            throw new IOException(
                    "VLM component conversion failed for " + source.getFileName() + ": "
                            + result.getErrorMessage());
        }
        cancellation.checkpoint();
        return result.getArtifact().requireCanonicalSdz();
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
            if (isUsableTokenizerJson(tokenizerJson)) {
                log.debug("tokenizer.json already present at {}", tokenizerJson);
                return;
            }

            HttpDownloader httpDownloader = downloadServices.stream()
                    .filter(HttpDownloader.class::isInstance)
                    .map(HttpDownloader.class::cast)
                    .findFirst()
                    .orElse(null);
            if (httpDownloader == null) {
                log.warn(
                        "No HTTP downloader is configured; tokenizer bootstrap is unavailable for model {}",
                        request.getModelId());
                return;
            }

            Set<String> candidates = new LinkedHashSet<>();
            if (request.getTokenizerUrl() != null && !request.getTokenizerUrl().isBlank()) {
                candidates.add(request.getTokenizerUrl());
            }
            candidates.addAll(inferTokenizerUrlCandidates(request.getRepository()));

            Path partialTokenizer = pendingDir.resolve("tokenizer.json.part");
            Map<String, String> headers = new HashMap<>();
            if (request.getAuthToken() != null && !request.getAuthToken().isBlank()) {
                headers.put("Authorization", "Bearer " + request.getAuthToken());
            }

            for (String tokenizerUrl : candidates) {
                String safeUrl = safeRemoteUrlForDiagnostics(tokenizerUrl);
                log.info(
                        "Trying tokenizer.json from {} for model {}",
                        safeUrl,
                        request.getModelId());
                try {
                    Files.deleteIfExists(partialTokenizer);
                    long downloadedBytes = httpDownloader.downloadAsset(
                            tokenizerUrl,
                            partialTokenizer,
                            headers,
                            assetLimits.maxBytesFor(TextModelAssetMap.TOKENIZER));
                    if (isUsableTokenizerJson(partialTokenizer)) {
                        replaceAtomically(partialTokenizer, tokenizerJson);
                        log.info(
                                "Downloaded tokenizer.json ({} bytes) from {}",
                                downloadedBytes,
                                safeUrl);
                        return;
                    }
                    log.debug(
                            "Remote tokenizer from {} was not a valid tokenizer.json; trying next candidate",
                            safeUrl);
                } catch (Exception e) {
                    log.debug(
                            "Failed to fetch tokenizer from {}: {}",
                            safeUrl,
                            e.getMessage());
                } finally {
                    try {
                        Files.deleteIfExists(partialTokenizer);
                    } catch (IOException cleanupFailure) {
                        log.debug(
                                "Failed to clean tokenizer partial file for model {}: {}",
                                request.getModelId(),
                                cleanupFailure.getMessage());
                    }
                }
            }
            log.warn(
                    "No tokenizer.json could be downloaded for model {}. "
                            + "LLM loading may need manual tokenizer setup.",
                    request.getModelId());
        } catch (Exception e) {
            log.warn("Failed to download tokenizer for model '{}'", request.getModelId(), e);
        }
    }

    /**
     * Ensure the staged model carries the tokenizer protocol metadata declared by its source GGUF.
     *
     * <p>{@code tokenizer.json} contains vocabulary and merges, while the chat template and
     * BOS/EOS/PAD roles live in {@code tokenizer_config.json}. The migration reads those roles from
     * the source container. It never guesses token strings and never overwrites existing fields.</p>
     */
    private void ensureChatTemplate(
            DownloadRequest request, Path pendingDir, Path originalModelPath) {
        ensureTokenizerMetadata(request.getModelId(), pendingDir, originalModelPath);
    }

    private boolean ensureTokenizerMetadata(
            String modelId, Path modelDirectory, Path originalModelPath) {
        Path configPath = modelDirectory.resolve(TextModelAssetMap.TOKENIZER_CONFIG_FILE);
        try {
            Path gguf = findGgufBeside(modelDirectory, originalModelPath);
            if (gguf == null) {
                log.warn(
                        "No source GGUF is available to repair tokenizer metadata for model {}",
                        modelId);
                return false;
            }

            String template;
            List<String> tokens;
            int bosId;
            int eosId;
            int padId;
            try (GGUFReader reader = new GGUFReader(gguf.toFile())) {
                GGUFHeader header = reader.getHeader();
                template = header.getChatTemplate();
                tokens = header.getTokens();
                bosId = header.getBosTokenId();
                eosId = header.getEosTokenId();
                padId = header.getPadTokenId();
            }

            ObjectNode config = Files.isRegularFile(configPath)
                    ? requireObjectConfig(configPath)
                    : objectMapper.createObjectNode();
            boolean changed = applyTokenizerMetadata(
                    config, template, tokens, bosId, eosId, padId);
            if (!changed) {
                log.debug("Tokenizer metadata already complete for model {}", modelId);
                return true;
            }

            Path partial =
                    modelDirectory.resolve(TextModelAssetMap.TOKENIZER_CONFIG_FILE + ".part");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), config);
            replaceAtomically(partial, configPath);
            log.info(
                    "Wrote {} for model {} from source container {} "
                            + "(chatTemplate={}, bos={}, eos={}, pad={})",
                    TextModelAssetMap.TOKENIZER_CONFIG_FILE,
                    modelId,
                    gguf.getFileName(),
                    config.hasNonNull("chat_template"),
                    config.hasNonNull("bos_token"),
                    config.hasNonNull("eos_token"),
                    config.hasNonNull("pad_token"));
            return true;
        } catch (Exception e) {
            log.warn(
                    "Could not preserve tokenizer metadata for model '{}': {}",
                    modelId, e.getMessage());
            return false;
        }
    }

    private ObjectNode requireObjectConfig(Path configPath) throws IOException {
        var root = objectMapper.readTree(configPath.toFile());
        if (!(root instanceof ObjectNode object)) {
            throw new IOException(
                    TextModelAssetMap.TOKENIZER_CONFIG_FILE + " must contain a JSON object");
        }
        return object;
    }

    static boolean applyTokenizerMetadata(
            ObjectNode config,
            String chatTemplate,
            List<String> tokens,
            int bosId,
            int eosId,
            int padId) {
        boolean changed = false;
        if ((!config.hasNonNull("chat_template")
                || config.path("chat_template").asText("").isBlank())
                && chatTemplate != null
                && !chatTemplate.isBlank()) {
            config.put("chat_template", chatTemplate);
            changed = true;
        }
        changed |= putTokenIfResolvable(config, "bos_token", tokens, bosId);
        changed |= putTokenIfResolvable(config, "eos_token", tokens, eosId);
        changed |= putTokenIfResolvable(config, "pad_token", tokens, padId);
        return changed;
    }

    private static boolean putTokenIfResolvable(
            ObjectNode config, String field, List<String> tokens, int id) {
        if (config.hasNonNull(field) || tokens == null || id < 0 || id >= tokens.size()) {
            return false;
        }
        config.put(field, tokens.get(id));
        return true;
    }

    private Path findGgufBeside(Path pendingDir, Path originalModelPath) throws IOException {
        if (originalModelPath != null
                && originalModelPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf")
                && Files.isRegularFile(originalModelPath)) {
            return originalModelPath;
        }
        try (Stream<Path> entries = Files.list(pendingDir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private boolean isUsableTokenizerJson(Path candidate) {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return false;
        }
        try {
            var root = objectMapper.readTree(candidate.toFile());
            return root != null && root.isObject() && root.has("model");
        } catch (IOException invalid) {
            return false;
        }
    }

    private static String safeRemoteUrlForDiagnostics(String value) {
        try {
            return SafeHttpTransport.safeUriForDiagnostics(URI.create(value));
        } catch (RuntimeException invalid) {
            return "<invalid-remote-uri>";
        }
    }

    private static void replaceAtomically(Path source, Path destination)
            throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
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
        Path single = findFile(
                dir,
                "decoder.sdz",
                "decoder_model_merged.sdz",
                "pipeline.json",
                "decoder_model_merged.onnx",
                "model.sdz",
                ".fb");
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
        Path verifiedDir = workspace("verified", modelId);
        Path artifactDir = verifiedDir;
        ModelType inferredType = ModelType.DENSE_ENCODER;
        if (!Files.isDirectory(artifactDir)) {
            artifactDir = null;
            for (ModelType candidateType : ModelType.values()) {
                Path candidate = modelsDir
                        .resolve(candidateType.getDirectoryName())
                        .resolve(modelId);
                if (Files.isDirectory(candidate)) {
                    artifactDir = candidate;
                    inferredType = candidateType;
                    break;
                }
            }
            if (artifactDir == null) {
                return null;
            }
            log.warn("Recovered unregistered promotion candidate {} from {}", modelId, artifactDir);
        }

        // Infer model type by inspecting artefacts in the verified or recoverable production directory.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactDir)) {
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
            log.warn("Could not inspect staged artifact dir {} for type inference", artifactDir, e);
        }

        StagingModelInfo info = new StagingModelInfo();
        info.setModelId(modelId);
        info.setType(inferredType);
        info.setStatus(StagingStatus.READY);
        return info;
    }

    private static final class StagingOperation implements StagingCancellation {
        private final String modelId;
        private final String source;
        private final String attemptId;
        private final StagingModelInfo info;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CountDownLatch quiesced = new CountDownLatch(1);
        private final CompletableFuture<StagingModelInfo> completion = new CompletableFuture<>();
        private volatile Future<?> worker;
        private volatile Thread runningThread;

        private StagingOperation(
                String modelId,
                String source,
                String attemptId,
                StagingModelInfo info) {
            this.modelId = modelId;
            this.source = source;
            this.attemptId = attemptId;
            this.info = info;
        }

        private boolean markStarted() {
            if (finished.get() || !started.compareAndSet(false, true)) {
                return false;
            }
            runningThread = Thread.currentThread();
            return true;
        }

        private void attachWorker(Future<?> worker) {
            this.worker = worker;
            if (cancellationRequested.get()) {
                worker.cancel(true);
            }
        }

        private boolean requestCancellation() {
            if (finished.get()) {
                return false;
            }
            cancellationRequested.set(true);
            Future<?> submitted = worker;
            if (submitted != null) {
                submitted.cancel(true);
            }
            Thread running = runningThread;
            if (running != null) {
                running.interrupt();
            }
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return cancellationRequested.get();
        }
    }

    private String calculateChecksum(Path file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (var input = new java.io.BufferedInputStream(Files.newInputStream(file), buffer.length)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            log.warn("Failed to calculate checksum for {}", file, e);
            return null;
        }
    }
}
