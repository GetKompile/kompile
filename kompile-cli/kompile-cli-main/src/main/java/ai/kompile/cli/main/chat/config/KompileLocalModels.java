/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.project.KompileProjectStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Inventory of models the first-party {@code kompile-local} provider can serve,
 * plus direct HuggingFace GGUF/weights acquisition.
 *
 * <p>This is the option source for setup and the in-session model picker. It
 * mirrors the kompile-chat-local option surface: installed local models
 * (distribution, {@code ~/.kompile/models}, and the current project's
 * {@code data/models} registry), cached HuggingFace downloads, and a download
 * action for new HuggingFace repositories.</p>
 */
public final class KompileLocalModels {

    /** Picker action appended after the installed inventory. */
    public static final String DOWNLOAD_ACTION =
            "Download from HuggingFace (owner/model, e.g. Qwen/Qwen2.5-0.5B-Instruct-GGUF)…";
    /** Picker action appended after the download action. */
    public static final String LOCAL_PATH_ACTION =
            "Use a local model file or directory…";

    private KompileLocalModels() {
    }

    /**
     * Every model id the local provider can currently resolve, deterministic and
     * deduplicated: HuggingFace-cached repos first, then installed distribution
     * and user models, then the current project's registered chat models.
     */
    public static List<LiveModelDiscovery.Model> discover() {
        return discover(new KompileModelManager());
    }

    /** Overload pinning the cache root so tests never touch the user cache. */
    static List<LiveModelDiscovery.Model> discover(KompileModelManager manager) {
        List<LiveModelDiscovery.Model> models = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : cachedHuggingFaceModelIds(manager)) {
            if (seen.add(id.toLowerCase(Locale.ROOT))) {
                models.add(new LiveModelDiscovery.Model(id, List.of()));
            }
        }
        for (String id : installedModelIds()) {
            if (seen.add(id.toLowerCase(Locale.ROOT))) {
                models.add(new LiveModelDiscovery.Model(id, List.of()));
            }
        }
        for (String id : projectModelIds()) {
            if (seen.add(id.toLowerCase(Locale.ROOT))) {
                models.add(new LiveModelDiscovery.Model(id, List.of()));
            }
        }
        return models;
    }

    /**
     * Download one HuggingFace repository into the local model cache and return
     * the model directory. Cached repositories are returned untouched.
     *
     * @param repository HuggingFace repo id such as {@code Qwen/Qwen2.5-0.5B-Instruct-GGUF}
     * @param revision   branch, tag, or commit; {@code null} uses {@code main}
     * @param token      HuggingFace token for gated repos; {@code null} reads
     *                   {@code HF_TOKEN} / {@code HUGGING_FACE_HUB_TOKEN}
     */
    public static Path downloadFromHuggingFace(
            String repository, String revision, String token, Consumer<String> progress)
            throws IOException {
        String repo = repository == null ? "" : repository.trim();
        if (!isHuggingFaceRepoId(repo)) {
            throw new IOException("'" + repository + "' is not a HuggingFace repository id "
                    + "(expected owner/model, e.g. Qwen/Qwen2.5-0.5B-Instruct-GGUF)");
        }
        KompileModelManager manager = new KompileModelManager();
        if (manager.isPipelineModelCached(repo)) {
            if (progress != null) progress.accept("Cached: " + repo);
            return manager.getPipelineModelDirectory(repo);
        }
        if (progress != null) progress.accept("Downloading " + repo + " from HuggingFace…");
        String effectiveToken = firstNonBlank(token,
                System.getenv("HF_TOKEN"), System.getenv("HUGGING_FACE_HUB_TOKEN"));
        Path directory = manager.downloadPipelineModel(
                repo, revision, effectiveToken,
                message -> {
                    if (progress != null) progress.accept(message);
                });
        if (progress != null) progress.accept("Downloaded " + repo);
        return directory;
    }

    /** Whether this string is an {@code owner/model} HuggingFace repository id. */
    public static boolean isHuggingFaceRepoId(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");
    }

    /** Repo ids already present in the HuggingFace pipeline model cache. */
    static List<String> cachedHuggingFaceModelIds(KompileModelManager manager) {
        try {
            return manager.listCachedPipelineModels();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Runnable model file names under the same roots
     * {@link KompileLocalServingBootstrap} searches at startup: the install
     * distribution, {@code ~/.kompile/models}, and the legacy DL4J LLM cache.
     */
    static List<String> installedModelIds() {
        List<Path> roots = new ArrayList<>();
        try {
            roots.add(KompileHome.installDirectory().toPath()
                    .resolve("models").resolve("chat"));
            roots.add(KompileHome.installDirectory().toPath()
                    .resolve("sdx-sdk").resolve("models"));
        } catch (RuntimeException ignored) {
            // No installed distribution; the user cache still applies.
        }
        roots.add(KompileHome.homeDirectory().toPath().resolve("models"));
        roots.add(Path.of(System.getProperty("user.home", "."))
                .resolve(".cache").resolve("dl4j-llm-models"));

        Set<String> ids = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root, 3)) {
                files.filter(Files::isRegularFile)
                        .filter(KompileLocalModels::isSupportedModelFile)
                        .map(path -> stripModelExtension(path.getFileName().toString()))
                        .forEach(ids::add);
            } catch (IOException ignored) {
                // A missing or unreadable root contributes nothing.
            }
        }
        return List.copyOf(ids);
    }

    /** The serving subprocess consumes GGUF/GGML and staged SameDiff archives. */
    private static boolean isSupportedModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gguf") || name.endsWith(".sdz");
    }

    /** Chat-model ids registered in the current project's model registry. */
    static List<String> projectModelIds() {
        Path projectRoot = currentProjectRoot();
        if (projectRoot == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (java.util.Map<String, Object> model : LocalProjectModelBootstrap.inventory(projectRoot)) {
            Object role = model.get("role");
            if (role == null || !"LLM".equalsIgnoreCase(String.valueOf(role))) continue;
            if (!Boolean.TRUE.equals(model.get("artifactReady"))) continue;
            String id = firstNonBlank(
                    string(model.get("registryModelId")),
                    string(model.get("modelId")),
                    string(model.get("id")));
            if (id != null) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /**
     * Resolve a selection through the current project's model registry.
     * Returns the registered runtime artifact, or {@code null} when no
     * project registry or no matching chat model exists.
     */
    static Path resolveProjectModel(String selection) {
        Path projectRoot = currentProjectRoot();
        if (projectRoot == null || selection == null || selection.isBlank()) {
            return null;
        }
        String requested = selection.trim();
        java.util.Map<String, Object> best = null;
        for (java.util.Map<String, Object> model : LocalProjectModelBootstrap.inventory(projectRoot)) {
            Object role = model.get("role");
            if (role == null || !"LLM".equalsIgnoreCase(String.valueOf(role))) continue;
            if (!Boolean.TRUE.equals(model.get("artifactReady"))) continue;
            if (!matchesProjectModel(model, requested)) continue;
            if (best == null || specificity(model) > specificity(best)) {
                best = model;
            }
        }
        if (best == null) {
            return null;
        }
        String artifact = string(best.get("resolvedArtifact"));
        return artifact == null ? null : Path.of(artifact);
    }

    private static boolean matchesProjectModel(java.util.Map<String, Object> model, String requested) {
        for (String key : List.of("id", "modelId", "registryModelId")) {
            String value = string(model.get(key));
            if (value != null && value.equalsIgnoreCase(requested)) {
                return true;
            }
        }
        return false;
    }

    /** Prefer exact registry ids over looser matches (length is a cheap proxy). */
    private static int specificity(java.util.Map<String, Object> model) {
        String id = firstNonBlank(
                string(model.get("registryModelId")),
                string(model.get("modelId")),
                string(model.get("id")));
        return id == null ? 0 : id.length();
    }

    private static Path currentProjectRoot() {
        try {
            return new KompileProjectStore().findProjectRoot(
                    Path.of(System.getProperty("user.dir", "."))).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stripModelExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : List.of(".gguf", ".sdz", ".safetensors", ".onnx")) {
            if (lower.endsWith(extension)) {
                return fileName.substring(0, fileName.length() - extension.length());
            }
        }
        return fileName;
    }

    private static String string(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
