/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.staging.cli;

import ai.kompile.core.staging.StagingModelInfo;
import ai.kompile.core.staging.StagingStatus;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.catalog.CatalogModel;
import ai.kompile.staging.catalog.CatalogService;
import ai.kompile.staging.download.DownloadRequest;
import ai.kompile.staging.download.TextModelAssetMap;
import ai.kompile.staging.download.TextModelAssetUrlMap;
import ai.kompile.staging.staging.StagingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Synchronous one-process model bootstrap ABI used by folder-local MCP commands.
 *
 * <p>The command deliberately runs through the same catalog, downloader, converter,
 * validator, promotion, and registry services as the normal staging service. It
 * prints one machine-readable result marker and then exits; it never starts the
 * staging HTTP server.</p>
 */
@Component
@Command(
        name = "bootstrap",
        description = "Resolve, import, stage, and promote one model into a project-local model store",
        mixinStandardHelpOptions = true)
public class BootstrapCommand implements Callable<Integer> {
    public static final String RESULT_PREFIX = "MODEL_BOOTSTRAP_RESULT:";

    private final CatalogService catalogService;
    private final StagingService stagingService;
    private final RegistryService registryService;
    private final ObjectMapper objectMapper;

    @Option(names = "--model-id", required = true, description = "Project/registry model id")
    private String modelId;

    @Option(names = "--local-path", description = "Trusted local model artifact to import")
    private Path localPath;

    @Option(names = "--source", description = "Source type when not using the catalog")
    private String source;

    @Option(names = "--repository", description = "Source repository or URL")
    private String repository;

    @Option(names = "--revision", description = "Source branch, tag, or revision")
    private String revision;

    @Option(names = "--format", description = "Source model format")
    private String format;

    @Option(names = "--type", description = "Registry model type")
    private String type;

    @Option(names = "--promote", negatable = true, defaultValue = "true",
            description = "Promote the staged model into the project-local registry")
    private boolean promote;

    @Option(names = "--timeout-minutes", defaultValue = "60",
            description = "Maximum time to wait for a trusted-local import")
    private long timeoutMinutes;

    public BootstrapCommand(
            CatalogService catalogService,
            StagingService stagingService,
            RegistryService registryService,
            ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.stagingService = stagingService;
        this.registryService = registryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws Exception {
        Optional<ModelEntry> installed = registryService.getModel(modelId);
        Path installedPath = installed.map(this::resolveModelPath).orElse(null);
        if (installed.isPresent() && isRuntimeReady(installed.get(), installedPath)) {
            emit(installed.get(), "existing");
            return 0;
        }

        StagingModelInfo staged;
        if (localPath != null) {
            Path artifact = localPath.toAbsolutePath().normalize();
            if (!Files.isRegularFile(artifact)) {
                throw new IllegalArgumentException("Local model artifact does not exist: " + artifact);
            }
            staged = stagingService.stageLocalModel(
                    modelId, artifact.toString(), inferFormat(artifact, format), false);
            staged = waitForTerminal(staged);
        } else {
            DownloadRequest request = buildDownloadRequest();
            staged = stagingService.stageModel(request);
        }

        if (staged == null || (staged.getStatus() != StagingStatus.READY
                && staged.getStatus() != StagingStatus.COMPLETED)) {
            String status = staged == null ? "missing" : String.valueOf(staged.getStatus());
            String error = staged == null ? null : staged.getError();
            throw new IllegalStateException("Model staging failed (status=" + status + ")"
                    + (error == null ? "" : ": " + error));
        }

        if (promote && !stagingService.promoteModel(modelId, null)) {
            throw new IllegalStateException("Model was staged but could not be promoted: " + modelId);
        }

        ModelEntry entry = registryService.getModel(modelId)
                .orElseThrow(() -> new IllegalStateException(
                        "Model promotion did not create a registry entry: " + modelId));
        emit(entry, localPath == null ? "staged" : "imported");
        return 0;
    }

    private DownloadRequest buildDownloadRequest() {
        CatalogModel catalog = catalogService.getModel(modelId).orElse(null);
        String effectiveSource = firstNonBlank(source, catalog == null ? null : catalog.getSource());
        String effectiveRepository =
                firstNonBlank(repository, catalog == null ? null : catalog.getRepo());
        String effectiveFormat = firstNonBlank(format, catalog == null ? null : catalog.getFormat(), "auto");
        String effectiveType = firstNonBlank(type, catalog == null ? null : catalog.getModelType());
        if (effectiveType == null && "gguf".equalsIgnoreCase(effectiveFormat)) {
            effectiveType = ModelType.LLM_GGML.getValue();
        }
        if (effectiveSource == null || effectiveRepository == null) {
            throw new IllegalArgumentException(
                    "Model is not in the catalog; --source and --repository are required");
        }

        Map<String, String> files = catalog == null || catalog.getFiles() == null
                ? Map.of() : catalog.getFiles();
        Map<String, String> assetUrls = catalog == null || catalog.getAssetUrls() == null
                ? Map.of() : catalog.getAssetUrls();

        return DownloadRequest.builder()
                .source(effectiveSource)
                .repository(effectiveRepository)
                .revision(revision)
                .format(effectiveFormat)
                .modelType(ModelType.fromValue(effectiveType))
                .audioSynthesis(catalog == null ? null : catalog.getAudioSynthesis())
                .modelId(modelId)
                .files(files)
                .textAssets(TextModelAssetMap.fromFileMap(files))
                .textAssetUrls(TextModelAssetUrlMap.fromUrlMap(assetUrls))
                .build();
    }

    private StagingModelInfo waitForTerminal(StagingModelInfo initial) throws InterruptedException {
        long timeoutNanos = Duration.ofMinutes(Math.max(1, timeoutMinutes)).toNanos();
        long deadline = System.nanoTime() + timeoutNanos;
        StagingModelInfo current = initial;
        while (System.nanoTime() < deadline) {
            current = stagingService.getStagingModel(modelId);
            if (current != null && (current.getStatus() == StagingStatus.READY
                    || current.getStatus() == StagingStatus.COMPLETED
                    || current.getStatus() == StagingStatus.FAILED
                    || current.getStatus() == StagingStatus.CANCELLED)) {
                return current;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out staging local model after "
                + Math.max(1, timeoutMinutes) + " minute(s)");
    }

    private void emit(ModelEntry entry, String disposition) throws Exception {
        Path modelPath = resolveModelPath(entry);
        if (!isRuntimeReady(entry, modelPath)) {
            throw new IllegalStateException("Registry entry has no runnable model artifact: " + modelId);
        }
        Path directory = modelPath.getParent();
        Path tokenizer = directory == null ? null : directory.resolve("tokenizer.json");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", entry.getModelId());
        result.put("modelType", entry.getType() == null ? null : entry.getType().getValue());
        result.put("modelPath", modelPath.toString());
        result.put("tokenizerPath", tokenizer != null && Files.isRegularFile(tokenizer)
                ? tokenizer.toString() : null);
        result.put("modelDirectory", directory == null ? null : directory.toString());
        result.put("disposition", disposition);
        System.out.println(RESULT_PREFIX + objectMapper.writeValueAsString(result));
    }

    private Path resolveModelPath(ModelEntry entry) {
        if (entry.getPath() == null || entry.getModelFile() == null) {
            return null;
        }
        Path candidate = registryService.getModelDir()
                .resolve(entry.getPath())
                .resolve(entry.getModelFile())
                .toAbsolutePath().normalize();
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    static boolean isRuntimeReady(ModelEntry entry, Path modelPath) {
        if (entry == null || modelPath == null || !Files.isRegularFile(modelPath)) {
            return false;
        }
        ModelType modelType = entry.getType();
        if (modelType == null || !modelType.isVlm()) {
            return true;
        }

        String modelFileName = modelPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if ("pipeline.json".equals(modelFileName)) {
            return true;
        }
        if (!(modelFileName.endsWith(".sdz")
                || modelFileName.endsWith(".fb")
                || modelFileName.endsWith(".sdnb"))) {
            return false;
        }

        Path directory = modelPath.getParent();
        return hasRuntimeComponent(
                        directory,
                        "decoder.sdz",
                        "decoder_model.sdz",
                        "decoder_model_merged.sdz",
                        "language_model.sdz",
                        "model.sdz")
                && hasRuntimeComponent(
                        directory,
                        "vision_encoder.sdz",
                        "encoder.sdz");
    }

    private static boolean hasRuntimeComponent(Path directory, String... names) {
        if (directory == null) {
            return false;
        }
        for (String name : names) {
            if (Files.isRegularFile(directory.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static String inferFormat(Path artifact, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String name = artifact.getFileName().toString().toLowerCase();
        if (name.endsWith(".gguf") || name.endsWith(".ggml")) {
            return "gguf";
        }
        if (name.endsWith(".safetensors")) {
            return "safetensors";
        }
        if (name.endsWith(".sdz") || name.endsWith(".fb")) {
            return "samediff";
        }
        if (name.endsWith(".onnx")) {
            return "onnx";
        }
        return "samediff";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
