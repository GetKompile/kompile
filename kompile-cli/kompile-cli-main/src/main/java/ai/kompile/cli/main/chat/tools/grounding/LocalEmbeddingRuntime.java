/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Folder-scoped embedding subprocess used by local RAG.
 *
 * <p>The child is pointed directly at {@code <project>/data/models}. It never inherits a staging
 * URL or an archive from a managed server, so model selection cannot escape the current project.</p>
 */
final class LocalEmbeddingRuntime {
    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_LIVE_RUNTIMES = Math.max(1,
            Integer.getInteger("kompile.local.rag.embedding.maxRuntimes", 1));
    private static final LinkedHashMap<Path, LocalEmbeddingRuntime> LIVE_RUNTIMES =
            new LinkedHashMap<>(4, 0.75f, true);
    private static final Set<LocalEmbeddingRuntime> ALL_RUNTIMES =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Path, ArtifactHash> ARTIFACT_HASHES = new HashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                LocalEmbeddingRuntime::shutdownAll,
                "kompile-local-rag-embedding-shutdown"));
    }

    private final String modelId;
    private final String fingerprint;
    private final EmbeddingSubprocessLauncher launcher;
    private int leases;
    private boolean retired;

    private LocalEmbeddingRuntime(String modelId, String fingerprint,
                                  EmbeddingSubprocessLauncher launcher) {
        this.modelId = modelId;
        this.fingerprint = fingerprint;
        this.launcher = launcher;
    }

    static synchronized LocalProjectRagSearch.EmbeddingRuntime open(Path projectRoot,
                                                                    ObjectMapper mapper) throws Exception {
        Path root = projectRoot.toRealPath();
        Path configuredModelsDirectory = root.resolve("data/models");
        if (Files.isSymbolicLink(configuredModelsDirectory)) {
            throw new IOException("Folder-local model directory must not be a symbolic link: "
                    + configuredModelsDirectory);
        }
        Path modelsDirectory = configuredModelsDirectory.toRealPath();
        if (!modelsDirectory.startsWith(root)) {
            throw new IOException("Folder-local model directory escapes the project: "
                    + configuredModelsDirectory);
        }
        ModelSelection selection = resolveModel(root, modelsDirectory, mapper);
        LocalEmbeddingRuntime existing = LIVE_RUNTIMES.get(root);
        if (existing != null && existing.fingerprint.equals(selection.fingerprint())
                && existing.launcher.isRunning()) {
            existing.leases++;
            return new Lease(existing);
        }
        if (existing != null) {
            LIVE_RUNTIMES.remove(root);
            existing.retired = true;
            if (existing.leases == 0) existing.closeInternal();
        }
        ensureCapacityForNewRuntime();

        int heapMb = Integer.getInteger("kompile.local.rag.embedding.heapMb", 4096);
        EmbeddingSubprocessLauncher launcher = EmbeddingSubprocessLauncher.builder()
                .maxHeapMb(heapMb)
                .maxPhysicalMb((long) heapMb * 4)
                .requestTimeoutMs(REQUEST_TIMEOUT.toMillis())
                .loadModelTimeoutMs(LOAD_TIMEOUT.toMillis())
                .workingDirectory(root)
                .environment(Map.of("KOMPILE_MODEL_CACHE_DIR", modelsDirectory.toString()))
                .localModelOnly(true)
                .build();
        try {
            launcher.start();
            EmbeddingSubprocessMessage.LoadModelResponse loaded = launcher
                    .loadModel(selection.modelId(), 32, 64, 64, Map.of())
                    .get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!loaded.success()) {
                throw new IOException("Local embedding subprocess rejected model '"
                        + selection.modelId() + "': " + loaded.error());
            }
            LocalEmbeddingRuntime runtime = new LocalEmbeddingRuntime(selection.modelId(),
                    selection.fingerprint(), launcher);
            runtime.leases = 1;
            LIVE_RUNTIMES.put(root, runtime);
            ALL_RUNTIMES.add(runtime);
            return new Lease(runtime);
        } catch (Exception e) {
            launcher.stop();
            throw e;
        }
    }

    private String modelId() {
        return modelId;
    }

    private String fingerprint() {
        return fingerprint;
    }

    private List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return launcher.embedBatch(texts, REQUEST_TIMEOUT.toMillis())
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void closeInternal() {
        launcher.stop();
        // An unkillable child must remain counted against the hard capacity bound. The shutdown
        // hook will retry; new local searches degrade to lexical rather than overcommitting memory.
        if (!launcher.isProcessAlive()) ALL_RUNTIMES.remove(this);
    }

    private static void ensureCapacityForNewRuntime() throws IOException {
        while (ALL_RUNTIMES.size() >= MAX_LIVE_RUNTIMES) {
            Iterator<Map.Entry<Path, LocalEmbeddingRuntime>> iterator =
                    LIVE_RUNTIMES.entrySet().iterator();
            LocalEmbeddingRuntime evicted = null;
            while (iterator.hasNext()) {
                Map.Entry<Path, LocalEmbeddingRuntime> candidate = iterator.next();
                if (candidate.getValue().leases == 0) {
                    evicted = candidate.getValue();
                    iterator.remove();
                    break;
                }
            }
            if (evicted == null) {
                throw new IOException("All folder-local embedding subprocess slots are busy (max "
                        + MAX_LIVE_RUNTIMES + ")");
            }
            evicted.retired = true;
            evicted.closeInternal();
        }
    }

    private static synchronized void release(LocalEmbeddingRuntime runtime) {
        if (runtime.leases > 0) runtime.leases--;
        if (runtime.retired && runtime.leases == 0) runtime.closeInternal();
    }

    private static synchronized void shutdownAll() {
        List<LocalEmbeddingRuntime> runtimes = List.copyOf(ALL_RUNTIMES);
        LIVE_RUNTIMES.clear();
        runtimes.forEach(runtime -> runtime.retired = true);
        runtimes.forEach(LocalEmbeddingRuntime::closeInternal);
    }

    private static ModelSelection resolveModel(Path projectRoot, Path modelsDirectory,
                                               ObjectMapper mapper) throws IOException {
        Path configuredRegistryPath = modelsDirectory.resolve("registry.json");
        Path registryPath = containedRegularFile(modelsDirectory,
                configuredRegistryPath, "model registry");
        if (registryPath == null) {
            throw new IOException("No folder-local model registry exists at " + configuredRegistryPath
                    + ". Materialize an ENCODER project model before semantic search.");
        }
        JsonNode registry = mapper.readTree(registryPath.toFile());
        String modelId = encoderModelId(projectRoot, registry, mapper);
        JsonNode entry = registry.path("models").path(modelId);
        if (entry.isMissingNode()) {
            throw new IOException("Folder-local encoder '" + modelId
                    + "' is absent from " + registryPath);
        }
        String status = entry.path("status").asText("active");
        if (!"active".equalsIgnoreCase(status)) {
            throw new IOException("Folder-local encoder '" + modelId
                    + "' is not materialized (registry status: " + status + ")");
        }

        String relativeDirectory = firstNonBlank(text(entry, "path"), modelId);
        // Mirror RegistryBasedModelManager exactly: <cache>/<modelId> wins when it exists;
        // entry.path is consulted only when that directory is absent.
        Path defaultModelDirectory = modelsDirectory.resolve(modelId).normalize();
        Path modelDirectory = Files.exists(defaultModelDirectory)
                ? defaultModelDirectory : modelsDirectory.resolve(relativeDirectory).normalize();
        if (!Files.isDirectory(modelDirectory)) {
            throw new IOException("Folder-local encoder directory is missing: " + modelDirectory);
        }
        modelDirectory = modelDirectory.toRealPath();
        if (!modelDirectory.startsWith(modelsDirectory)) {
            throw new IOException("Encoder registry path escapes the current project: "
                    + relativeDirectory);
        }
        String modelFile = firstNonBlank(text(entry, "model_file"), "model.sdz");
        String vocabFile = firstNonBlank(text(entry, "vocab_file"), "vocab.txt");
        Path modelPath = containedRegularFile(modelsDirectory,
                modelDirectory.resolve(modelFile), "encoder artifact");
        if (modelPath == null) {
            throw new IOException("Folder-local encoder artifact is missing: "
                    + modelDirectory.resolve(modelFile));
        }
        Path vocabCandidate = modelDirectory.resolve(vocabFile);
        if (!Files.exists(vocabCandidate)) {
            Path vocabTxt = modelDirectory.resolve("vocab.txt");
            vocabCandidate = Files.exists(vocabTxt)
                    ? vocabTxt : modelDirectory.resolve("tokenizer.json");
        }
        Path vocabPath = containedRegularFile(modelsDirectory,
                vocabCandidate, "encoder vocabulary");
        if (vocabPath == null) {
            throw new IOException("Folder-local encoder vocabulary is missing: "
                    + modelDirectory.resolve(vocabFile));
        }
        String fingerprint = sha256(modelId + "\n" + mapper.writeValueAsString(entry) + "\n"
                + artifactChecksum(modelPath) + "\n" + artifactChecksum(vocabPath));
        return new ModelSelection(modelId, fingerprint);
    }

    private static String encoderModelId(Path projectRoot, JsonNode registry,
                                         ObjectMapper mapper) throws IOException {
        Path manifestPath = containedRegularFile(projectRoot,
                projectRoot.resolve("kompile.project.json"), "project manifest");
        if (manifestPath != null) {
            JsonNode manifest = mapper.readTree(manifestPath.toFile());
            for (JsonNode model : manifest.path("models")) {
                if ("ENCODER".equalsIgnoreCase(model.path("role").asText())
                        && !"ARCHIVED".equalsIgnoreCase(model.path("lifecycle").asText())) {
                    String selected = firstNonBlank(text(model, "registryModelId"),
                            text(model, "modelId"), text(model, "id"));
                    if (selected != null) {
                        return selected;
                    }
                }
            }
        }
        JsonNode models = registry.path("models");
        var fields = models.fields();
        while (fields.hasNext()) {
            var candidate = fields.next();
            JsonNode entry = candidate.getValue();
            String role = entry.path("metadata").path("rag_role").asText("");
            String type = entry.path("type").asText("");
            if ("ENCODER".equalsIgnoreCase(role)
                    || type.toLowerCase().contains("encoder")) {
                return candidate.getKey();
            }
        }
        throw new IOException("No ENCODER model is configured for the current folder");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path containedRegularFile(Path root, Path candidate, String description)
            throws IOException {
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized)) {
            return null;
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Folder-local " + description + " escapes the project: "
                    + candidate);
        }
        return real;
    }

    static synchronized String modelFingerprint(Path projectRoot, ObjectMapper mapper)
            throws IOException {
        Path root = projectRoot.toRealPath();
        Path models = root.resolve("data/models").toRealPath();
        if (!models.startsWith(root)) throw new IOException("Model directory escapes project");
        return resolveModel(root, models, mapper).fingerprint();
    }

    private static String artifactChecksum(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        Object changeTime = null;
        try {
            changeTime = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            // Without ctime, same-size in-place replacements can preserve mtime and file key.
            // Rehash on every selection rather than trusting an ambiguous metadata identity.
        }
        String identity = attributes.size() + ":" + attributes.lastModifiedTime().toMillis()
                + ":" + attributes.fileKey() + ":" + changeTime;
        ArtifactHash cached = ARTIFACT_HASHES.get(path);
        if (changeTime != null && cached != null && cached.identity().equals(identity)) {
            return cached.sha256();
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        ARTIFACT_HASHES.put(path, new ArtifactHash(identity, checksum));
        return checksum;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Lease implements LocalProjectRagSearch.EmbeddingRuntime {
        private final LocalEmbeddingRuntime runtime;
        private boolean closed;

        private Lease(LocalEmbeddingRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public String modelId() {
            return runtime.modelId();
        }

        @Override
        public String fingerprint() {
            return runtime.fingerprint();
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) throws Exception {
            if (closed) throw new IllegalStateException("Embedding runtime lease is closed");
            return runtime.embedBatch(texts);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                LocalEmbeddingRuntime.release(runtime);
            }
        }
    }

    private record ModelSelection(String modelId, String fingerprint) {
    }

    private record ArtifactHash(String identity, String sha256) {
    }
}
