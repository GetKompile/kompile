/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.modelmanager.ManagedModelArtifactCatalog;
import ai.kompile.modelmanager.ManagedModelArtifactDownloader;
import ai.kompile.modelmanager.ManagedModelRuntimeRegistrar;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Direct folder-local model acquisition. This path never launches scale-out staging. */
public final class LocalProjectModelAcquisition {

    public record Result(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            boolean downloaded,
            boolean dryRun,
            String disposition,
            ManagedModelArtifactCatalog.Definition definition) {
    }

    private LocalProjectModelAcquisition() {
    }

    public static Result acquire(
            Path projectRoot,
            String requestedModelId,
            Map<String, Object> options,
            boolean force,
            boolean dryRun) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, Object> values = options == null ? Map.of() : options;
        String modelId = firstNonBlank(requestedModelId, string(values, "modelId"));
        Path localPath = localPath(root, string(values, "localPath"));
        if (localPath != null) {
            if (!Files.exists(localPath)) {
                throw new IOException("Local model artifact does not exist: " + localPath);
            }
            String effectiveId = firstNonBlank(modelId,
                    localPath.getFileName() == null ? "local-model" : localPath.getFileName().toString());
            Path tokenizer = Files.isDirectory(localPath)
                    ? existing(localPath.resolve("tokenizer.json"))
                    : existing(localPath.resolveSibling("tokenizer.json"));
            if (!dryRun) {
                ManagedModelArtifactCatalog.Definition managed = modelId == null
                        ? null : ManagedModelArtifactCatalog.find(modelId).orElse(null);
                if (managed != null && Files.isRegularFile(localPath)
                        && localPath.startsWith(root.resolve("data/models").toAbsolutePath().normalize())
                        && localPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sdz")
                        && tokenizer != null) {
                    registerConverted(root, effectiveId, localPath);
                } else {
                    registerLocalSource(root, effectiveId, localPath, tokenizer);
                }
            }
            return new Result(effectiveId, localPath, tokenizer, false, dryRun,
                    "local", null);
        }
        if (modelId == null) {
            throw new IOException("Direct model acquisition requires modelId");
        }

        ManagedModelArtifactCatalog.Definition catalog = ManagedModelArtifactCatalog.find(modelId)
                .orElseThrow(() -> new IOException(
                        "No managed component manifest is registered for model '" + modelId + "'"));
        ManagedModelArtifactCatalog.Definition definition = applyOverrides(catalog, values);
        Path target = root.resolve("data/models").resolve(modelId).normalize();
        ManagedModelArtifactDownloader.Acquisition acquisition =
                new ManagedModelArtifactDownloader().acquire(definition, target, force, dryRun);
        Path modelPath = acquisition.primaryModel();
        Path tokenizerPath = acquisition.tokenizer();

        if (!dryRun) {
            register(root, definition, modelPath, "SOURCE");
        }
        String disposition = dryRun ? "preview" : acquisition.downloaded() ? "downloaded" : "cached";
        return new Result(modelId, modelPath, tokenizerPath, acquisition.downloaded(), dryRun,
                disposition, definition);
    }

    /** Register a converted managed artifact with both project inventory and the runtime registry. */
    public static ModelEntry registerConverted(
            Path projectRoot, String modelId, Path convertedModel) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path modelsRoot = root.resolve("data/models").toAbsolutePath().normalize();
        Path modelPath = convertedModel.toAbsolutePath().normalize();
        if (!Files.isRegularFile(modelPath) || !modelPath.startsWith(modelsRoot)) {
            throw new IOException("Converted managed model must exist under " + modelsRoot + ": " + modelPath);
        }
        ManagedModelArtifactCatalog.Definition definition = ManagedModelArtifactCatalog.find(modelId)
                .orElseThrow(() -> new IOException(
                        "No managed component manifest is registered for model '" + modelId + "'"));
        Path directory = modelPath.getParent();
        Path tokenizer = directory.resolve("tokenizer.json");
        if (!Files.isRegularFile(tokenizer)) {
            throw new IOException("Converted managed encoder is missing tokenizer.json beside " + modelPath);
        }

        ModelEntry entry = ManagedModelRuntimeRegistrar.register(
                modelsRoot, definition, modelPath, tokenizer);
        register(root, definition, modelPath, "RUNTIME");
        return entry;
    }

    private static ManagedModelArtifactCatalog.Definition applyOverrides(
            ManagedModelArtifactCatalog.Definition definition, Map<String, Object> options)
            throws IOException {
        String source = firstNonBlank(string(options, "source"), definition.source());
        String repository = firstNonBlank(string(options, "repository"), definition.repository());
        String revision = firstNonBlank(string(options, "revision"), definition.revision());
        String format = firstNonBlank(string(options, "format"), definition.format());
        String type = firstNonBlank(string(options, "type"), definition.modelType());
        if (!definition.source().equalsIgnoreCase(source)) {
            throw new IOException("Model '" + definition.modelId()
                    + "' component manifest is pinned to source " + definition.source());
        }
        if (!definition.repository().equals(repository)) {
            throw new IOException("Model '" + definition.modelId()
                    + "' component manifest is pinned to repository " + definition.repository());
        }
        if (!definition.revision().equals(revision)) {
            throw new IOException("Model '" + definition.modelId()
                    + "' component manifest is pinned to revision " + definition.revision());
        }
        if (!definition.format().equalsIgnoreCase(format)) {
            throw new IOException("Model '" + definition.modelId()
                    + "' component manifest is pinned to format " + definition.format());
        }
        return new ManagedModelArtifactCatalog.Definition(
                definition.modelId(), source, repository, revision, format, type,
                definition.role(), definition.primaryComponentKey(),
                definition.tokenizerComponentKey(), definition.components(), definition.metadata());
    }

    private static void register(
            Path root,
            ManagedModelArtifactCatalog.Definition definition,
            Path modelPath,
            String artifactStage) {
        KompileProjectStore store = new KompileProjectStore();
        ensureProject(store, root);
        KompileProjectModel model = new KompileProjectModel();
        model.setId(definition.modelId());
        model.setModelId(definition.modelId());
        model.setRegistryModelId(definition.modelId());
        model.setRole(definition.role());
        model.setSource(definition.source());
        model.setSourceRepository(definition.repository());
        model.setSourceRevision(definition.revision());
        model.setPath(root.relativize(modelPath.toAbsolutePath().normalize()).toString());
        Map<String, String> metadata = new LinkedHashMap<>(definition.metadata());
        metadata.put("registry.type", definition.modelType());
        metadata.put("format", definition.format());
        metadata.put("acquisition", "direct");
        metadata.put("artifact.stage", artifactStage);
        metadata.put("runtime.ready", Boolean.toString("RUNTIME".equals(artifactStage)));
        model.setMetadata(metadata);
        store.registerModel(root, model);
    }

    private static void registerLocalSource(
            Path root, String modelId, Path modelPath, Path tokenizerPath) {
        KompileProjectStore store = new KompileProjectStore();
        ensureProject(store, root);
        KompileProjectModel model = new KompileProjectModel();
        model.setId(modelId);
        model.setModelId(modelId);
        model.setRegistryModelId(modelId);
        model.setRole("MODEL");
        model.setSource("LOCAL");
        Path absolute = modelPath.toAbsolutePath().normalize();
        model.setPath(absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString());
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("acquisition", "direct");
        metadata.put("artifact.stage", "SOURCE");
        metadata.put("runtime.ready", "false");
        if (tokenizerPath != null) metadata.put("tokenizer", tokenizerPath.toString());
        model.setMetadata(metadata);
        store.registerModel(root, model);
    }

    private static void ensureProject(KompileProjectStore store, Path root) {
        if (Files.isRegularFile(root.resolve(KompileProjectStore.MANIFEST_FILE))) {
            return;
        }
        KompileProjectInitRequest request = new KompileProjectInitRequest();
        Path name = root.getFileName();
        request.setName(name == null ? "kompile-project" : name.toString());
        request.setDescription("Folder-local Kompile project metadata");
        request.setInitializeGit(false);
        store.init(root, request);
    }

    private static Path localPath(Path root, String value) {
        if (value == null) return null;
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static Path existing(Path path) {
        return Files.isRegularFile(path) ? path : null;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
