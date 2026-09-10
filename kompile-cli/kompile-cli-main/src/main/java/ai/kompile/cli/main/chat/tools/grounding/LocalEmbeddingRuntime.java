/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMessage;
import ai.kompile.cli.main.project.LocalProjectModelBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Folder-scoped embedding subprocess used by local RAG.
 *
 * <p>The child is pointed directly at {@code <project>/data/models}. It never inherits a staging
 * URL or an archive from a managed server, so model selection cannot escape the current project.</p>
 */
final class LocalEmbeddingRuntime {
    interface EmbeddingProcess {
        CompletableFuture<List<float[]>> embedBatch(List<String> texts, long timeoutMs);

        boolean isRunning();

        boolean isProcessAlive();

        void stop();
    }

    private static final class LauncherEmbeddingProcess implements EmbeddingProcess {
        private final EmbeddingSubprocessLauncher launcher;

        private LauncherEmbeddingProcess(EmbeddingSubprocessLauncher launcher) {
            this.launcher = launcher;
        }

        @Override
        public CompletableFuture<List<float[]>> embedBatch(List<String> texts, long timeoutMs) {
            return launcher.embedBatch(texts, timeoutMs);
        }

        @Override
        public boolean isRunning() {
            return launcher.isRunning();
        }

        @Override
        public boolean isProcessAlive() {
            return launcher.isProcessAlive();
        }

        @Override
        public void stop() {
            launcher.stop();
        }
    }

    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_LIVE_RUNTIMES = Math.max(1,
            Integer.getInteger("kompile.local.rag.embedding.maxRuntimes", 1));
    /** Idle reaper: a subprocess with zero leases is stopped after this long (ms; 0 = never).
     *  Frees heap + native memory + resident DSP plans when nothing needs embeddings;
     *  rewarming is a cheap model reload against the on-disk DSP plan cache. Default 3 min.
     *  Overridable through runtimeOptions.embeddingIdleShutdownMs when that internal caller
     *  uses reusable leases; 0 disables. */
    private static final long IDLE_SHUTDOWN_MS = Long.getLong(
            "kompile.local.rag.embedding.idleShutdownMs", 180_000L);
    private static final LinkedHashMap<Path, LocalEmbeddingRuntime> LIVE_RUNTIMES =
            new LinkedHashMap<>(4, 0.75f, true);
    private static final Set<LocalEmbeddingRuntime> ALL_RUNTIMES =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Path, ArtifactHash> ARTIFACT_HASHES = new HashMap<>();
    private static final ScheduledThreadPoolExecutor IDLE_REAPER = createIdleReaper();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                LocalEmbeddingRuntime::shutdownAll,
                "kompile-local-rag-embedding-shutdown"));
    }

    private static ScheduledThreadPoolExecutor createIdleReaper() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "kompile-local-rag-embedding-idle-reaper");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private long lastUsedNanos = System.nanoTime();
    private long idleShutdownMs = IDLE_SHUTDOWN_MS;
    private long idleGeneration;
    private ScheduledFuture<?> pendingIdleReap;
    private String requestedPlacement;
    private volatile String effectiveBackend;
    private volatile Integer effectivePid;

    private final String modelId;
    private final String fingerprint;
    private final int dimensions;
    private final BatchConfiguration batchConfiguration;
    private final EmbeddingProcess process;
    private int leases;
    private int activeUses;
    private boolean retired;
    private boolean stopping;

    private LocalEmbeddingRuntime(String modelId, String fingerprint, int dimensions,
                                  BatchConfiguration batchConfiguration,
                                  EmbeddingProcess process) {
        this.modelId = modelId;
        this.fingerprint = fingerprint;
        this.dimensions = dimensions;
        this.batchConfiguration = batchConfiguration;
        this.process = process;
    }

    static synchronized LocalEmbeddingRuntime runtimeForTests(
            long idleShutdownMs, EmbeddingProcess process) {
        LocalEmbeddingRuntime runtime = new LocalEmbeddingRuntime(
                "test-encoder", "test-fingerprint", 1,
                new BatchConfiguration(1, 1, 1), process);
        runtime.idleShutdownMs = Math.max(0L, idleShutdownMs);
        ALL_RUNTIMES.add(runtime);
        return runtime;
    }

    LocalProjectRagSearch.EmbeddingRuntime acquireForTests(boolean closeRuntimeOnRelease) {
        synchronized (LocalEmbeddingRuntime.class) {
            return acquireLeaseLocked(closeRuntimeOnRelease);
        }
    }

    static void resetForTests() {
        shutdownAll();
        synchronized (LocalEmbeddingRuntime.class) {
            ARTIFACT_HASHES.clear();
        }
    }

    static int queuedIdleReapTasksForTests() {
        return IDLE_REAPER.getQueue().size();
    }

    private Lease acquireLeaseLocked(boolean closeRuntimeOnRelease) {
        if (retired || stopping) {
            throw new IllegalStateException("Embedding runtime is shutting down");
        }
        cancelIdleReapLocked(this);
        touchLocked(this);
        leases++;
        return new Lease(this, closeRuntimeOnRelease);
    }

    private static void applyIdleShutdownOverride(
            LocalEmbeddingRuntime runtime, Map<String, Object> runtimeOptions) {
        Object configured = runtimeOptions == null
                ? null : runtimeOptions.get("embeddingIdleShutdownMs");
        if (configured instanceof Number number) {
            runtime.idleShutdownMs = Math.max(0L, number.longValue());
        }
    }

    static synchronized LocalProjectRagSearch.EmbeddingRuntime open(Path projectRoot,
                                                                    ObjectMapper mapper) throws Exception {
        return open(projectRoot, mapper, null);
    }

    static synchronized LocalProjectRagSearch.EmbeddingRuntime open(Path projectRoot,
                                                                    ObjectMapper mapper,
                                                                    Map<String, Object> runtimeOptions) throws Exception {
        return open(projectRoot, mapper, runtimeOptions, false);
    }

    private static synchronized LocalProjectRagSearch.EmbeddingRuntime open(
            Path projectRoot,
            ObjectMapper mapper,
            Map<String, Object> runtimeOptions,
            boolean closeRuntimeOnRelease) throws Exception {
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
                && existing.process.isRunning()) {
            applyIdleShutdownOverride(existing, runtimeOptions);
            return existing.acquireLeaseLocked(closeRuntimeOnRelease);
        }
        if (existing != null) {
            retireLocked(existing);
            if (existing.leases == 0 && existing.activeUses == 0
                    && markStoppingLocked(existing)) {
                existing.closeInternal();
            }
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
        applyConfiguredPlacement(launcher, runtimeOptions);
        try {
            launcher.start();
            EmbeddingSubprocessMessage.LoadModelResponse loaded = launcher
                    .loadModel(selection.modelId(),
                            selection.batchConfiguration().optimal(),
                            selection.batchConfiguration().maximum(),
                            selection.batchConfiguration().absoluteMaximum(),
                            selection.modelConfig())
                    .get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!loaded.success()) {
                throw new IOException("Local embedding subprocess rejected model '"
                        + selection.modelId() + "': " + loaded.error());
            }
            // Capture effective backend + pid from the subprocess itself (RuntimeInfo
            // already reports the loaded ND4J backend class and JVM pid). This is what
            // lets crawl status show requested vs effective placement without shell tools.
            LocalEmbeddingRuntime runtime = new LocalEmbeddingRuntime(selection.modelId(),
                    selection.fingerprint(), loaded.dimensions(),
                    selection.batchConfiguration(), new LauncherEmbeddingProcess(launcher));
            runtime.requestedPlacement = runtimeOptions == null
                    ? null : String.valueOf(
                            runtimeOptions.getOrDefault("embeddingPlacement", "")).trim();
            applyIdleShutdownOverride(runtime, runtimeOptions);
            try {
                EmbeddingSubprocessMessage.StatusResponse st = launcher.getStatus()
                        .get(30, TimeUnit.SECONDS);
                if (st != null && st.runtimeInfo() != null) {
                    runtime.effectiveBackend = st.runtimeInfo().nd4jBackend();
                    runtime.effectivePid = st.runtimeInfo().pid() == null
                            ? null : st.runtimeInfo().pid().intValue();
                }
            } catch (Exception statusFailure) {
                // Status is best-effort telemetry; a status failure never fails the load.
            }
            LIVE_RUNTIMES.put(root, runtime);
            ALL_RUNTIMES.add(runtime);
            return runtime.acquireLeaseLocked(closeRuntimeOnRelease);
        } catch (Exception e) {
            launcher.stop();
            throw e;
        }
    }

    /**
     * Open the same folder-local runtime through the production embedding contract used by the
     * corpus topic model. Acquisition is deliberately out of scope: callers must provision the
     * artifact with {@code model_runtime} before opening this read-only execution lease.
     *
     * @param runtimeOptions MCP-supplied runtime options for this crawl (may be null). The
     *                       optional {@code embeddingPlacement} entry configures the embedding
     *                       subprocess device placement — e.g. {@code "gpu"}, {@code "gpu:1"},
     *                       {@code "gpu:1:8589934592"} (device:cap-bytes), or {@code "cpu"}.
     *                       Absent/blank = INHERIT (launcher defaults).
     */
    static EmbeddingModel openEmbeddingModel(Path projectRoot, ObjectMapper mapper,
                                             Map<String, Object> runtimeOptions) throws Exception {
        // Corpus topic analysis is a one-shot phase immediately followed by a large serving-model
        // call. Its lease must release the subprocess synchronously when it is the last user;
        // waiting for the normal RAG idle-reuse window overlaps both GPU models.
        return (EmbeddingModel) open(projectRoot, mapper, runtimeOptions, true);
    }

    /**
     * Runtime telemetry for the folder-local embedding subprocess, for crawl status/transcript.
     * Returns requested placement, effective ND4J backend + pid (as reported by the child itself),
     * model id, running state, and idle-shutdown policy. Never throws.
     */
    static synchronized java.util.Map<String, Object> runtimeStatus(Path projectRoot) {
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        LocalEmbeddingRuntime runtime;
        try {
            runtime = LIVE_RUNTIMES.get(projectRoot.toRealPath());
        } catch (IOException e) {
            status.put("running", false);
            status.put("error", "project root unresolved: " + e.getMessage());
            return status;
        }
        if (runtime == null) {
            status.put("running", false);
            return status;
        }
        status.put("running", runtime.process.isRunning());
        status.put("modelId", runtime.modelId);
        status.put("requestedPlacement", runtime.requestedPlacement);
        status.put("effectiveBackend", runtime.effectiveBackend);
        status.put("pid", runtime.effectivePid);
        status.put("leases", runtime.leases);
        status.put("idleShutdownMs", runtime.idleShutdownMs);
        status.put("idleMs", TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - runtime.lastUsedNanos)));
        return status;
    }

    static boolean hasConfiguredEncoder(Path projectRoot, ObjectMapper mapper) throws IOException {
        Path root = projectRoot.toRealPath();
        Path modelsDirectory = root.resolve("data/models");
        if (!Files.isDirectory(modelsDirectory) || Files.isSymbolicLink(modelsDirectory)) {
            return false;
        }
        Path realModels = modelsDirectory.toRealPath();
        if (!realModels.startsWith(root)) {
            throw new IOException("Folder-local model directory escapes the project: "
                    + modelsDirectory);
        }
        Path registryPath = containedRegularFile(realModels,
                realModels.resolve("registry.json"), "model registry");
        JsonNode registry = registryPath == null
                ? mapper.createObjectNode() : mapper.readTree(registryPath.toFile());
        return configuredEncoderModelId(root, registry, mapper) != null;
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
        beginUse(this);
        try {
            return process.embedBatch(texts, REQUEST_TIMEOUT.toMillis())
                    .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            endUse(this);
        }
    }

    private void closeInternal() {
        try {
            process.stop();
        } finally {
            synchronized (LocalEmbeddingRuntime.class) {
                stopping = false;
                // An unkillable child must remain counted against the hard capacity bound. The
                // shutdown hook will retry; new local searches degrade to lexical rather than
                // overcommitting memory.
                if (!process.isProcessAlive()) ALL_RUNTIMES.remove(this);
            }
        }
    }

    private static void ensureCapacityForNewRuntime() throws IOException {
        while (ALL_RUNTIMES.size() >= MAX_LIVE_RUNTIMES) {
            Iterator<Map.Entry<Path, LocalEmbeddingRuntime>> iterator =
                    LIVE_RUNTIMES.entrySet().iterator();
            LocalEmbeddingRuntime evicted = null;
            while (iterator.hasNext()) {
                Map.Entry<Path, LocalEmbeddingRuntime> candidate = iterator.next();
                if (candidate.getValue().leases == 0
                        && candidate.getValue().activeUses == 0
                        && !candidate.getValue().stopping) {
                    evicted = candidate.getValue();
                    iterator.remove();
                    break;
                }
            }
            if (evicted == null) {
                throw new IOException("All folder-local embedding subprocess slots are busy (max "
                        + MAX_LIVE_RUNTIMES + ")");
            }
            retireLocked(evicted);
            if (markStoppingLocked(evicted)) evicted.closeInternal();
        }
    }

    private static void beginUse(LocalEmbeddingRuntime runtime) {
        synchronized (LocalEmbeddingRuntime.class) {
            if (runtime.stopping || (runtime.retired && runtime.leases == 0)) {
                throw new IllegalStateException("Embedding runtime is shutting down");
            }
            cancelIdleReapLocked(runtime);
            touchLocked(runtime);
            runtime.activeUses++;
        }
    }

    private static void endUse(LocalEmbeddingRuntime runtime) {
        boolean closeNow = false;
        synchronized (LocalEmbeddingRuntime.class) {
            if (runtime.activeUses > 0) runtime.activeUses--;
            touchLocked(runtime);
            if (runtime.leases == 0 && runtime.activeUses == 0) {
                if (runtime.retired) {
                    closeNow = markStoppingLocked(runtime);
                } else {
                    scheduleIdleReapLocked(runtime);
                }
            }
        }
        if (closeNow) runtime.closeInternal();
    }

    private static void release(
            LocalEmbeddingRuntime runtime, boolean closeRuntimeOnRelease) {
        boolean closeNow = false;
        synchronized (LocalEmbeddingRuntime.class) {
            if (runtime.leases > 0) runtime.leases--;
            touchLocked(runtime);
            if (closeRuntimeOnRelease) retireLocked(runtime);
            if (runtime.leases == 0 && runtime.activeUses == 0) {
                if (runtime.retired) {
                    closeNow = markStoppingLocked(runtime);
                } else {
                    scheduleIdleReapLocked(runtime);
                }
            }
        }
        if (closeNow) runtime.closeInternal();
    }

    /**
     * Stop the subprocess once it has been idle (zero leases) past its configured
     * window. Frees the child's heap, native memory, and its resident DSP plans; the
     * on-disk DSP plan cache makes the next warm-up cheap. A runtime re-opened after
     * being reaped just relaunches the subprocess.
     */
    private static void touchLocked(LocalEmbeddingRuntime runtime) {
        runtime.lastUsedNanos = System.nanoTime();
    }

    private static void scheduleIdleReapLocked(LocalEmbeddingRuntime runtime) {
        scheduleIdleReapLocked(runtime, TimeUnit.MILLISECONDS.toNanos(runtime.idleShutdownMs));
    }

    private static void scheduleIdleReapLocked(
            LocalEmbeddingRuntime runtime, long delayNanos) {
        cancelIdleReapLocked(runtime);
        if (runtime.idleShutdownMs <= 0 || runtime.retired || runtime.stopping
                || runtime.leases > 0 || runtime.activeUses > 0) {
            return;
        }
        long generation = runtime.idleGeneration;
        runtime.pendingIdleReap = IDLE_REAPER.schedule(
                () -> reapIdleRuntime(runtime, generation),
                Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
    }

    private static void cancelIdleReapLocked(LocalEmbeddingRuntime runtime) {
        runtime.idleGeneration++;
        if (runtime.pendingIdleReap != null) {
            runtime.pendingIdleReap.cancel(false);
            runtime.pendingIdleReap = null;
        }
    }

    private static void reapIdleRuntime(LocalEmbeddingRuntime runtime, long generation) {
        boolean closeNow = false;
        long idleNanos = 0L;
        synchronized (LocalEmbeddingRuntime.class) {
            if (generation != runtime.idleGeneration || runtime.retired || runtime.stopping
                    || runtime.leases > 0 || runtime.activeUses > 0) {
                return;
            }
            runtime.pendingIdleReap = null;
            long requiredIdleNanos = TimeUnit.MILLISECONDS.toNanos(runtime.idleShutdownMs);
            idleNanos = Math.max(0L, System.nanoTime() - runtime.lastUsedNanos);
            if (idleNanos < requiredIdleNanos) {
                scheduleIdleReapLocked(runtime, requiredIdleNanos - idleNanos);
                return;
            }
            retireLocked(runtime);
            closeNow = markStoppingLocked(runtime);
        }
        if (closeNow) {
            System.out.println("[embedding-runtime] idle "
                    + TimeUnit.NANOSECONDS.toSeconds(idleNanos)
                    + "s — stopping subprocess to free memory "
                    + "(DSP plans cached on disk; next use rewarms)");
            // stop() sets the launcher's shutdown flag even during crash-restart backoff, which
            // prevents an ownerless replacement process from appearing after this timer fires.
            runtime.closeInternal();
        }
    }

    private static void retireLocked(LocalEmbeddingRuntime runtime) {
        runtime.retired = true;
        cancelIdleReapLocked(runtime);
        LIVE_RUNTIMES.entrySet().removeIf(entry -> entry.getValue() == runtime);
    }

    private static boolean markStoppingLocked(LocalEmbeddingRuntime runtime) {
        if (runtime.stopping) return false;
        runtime.stopping = true;
        cancelIdleReapLocked(runtime);
        return true;
    }

    private static void shutdownAll() {
        List<LocalEmbeddingRuntime> toClose = new ArrayList<>();
        synchronized (LocalEmbeddingRuntime.class) {
            List<LocalEmbeddingRuntime> runtimes = List.copyOf(ALL_RUNTIMES);
            LIVE_RUNTIMES.clear();
            for (LocalEmbeddingRuntime runtime : runtimes) {
                retireLocked(runtime);
                if (markStoppingLocked(runtime)) toClose.add(runtime);
            }
        }
        toClose.forEach(LocalEmbeddingRuntime::closeInternal);
    }

    private static ModelSelection resolveModel(Path projectRoot, Path modelsDirectory,
                                               ObjectMapper mapper) throws IOException {
        Path configuredRegistryPath = modelsDirectory.resolve("registry.json");
        Path registryPath = containedRegularFile(modelsDirectory,
                configuredRegistryPath, "model registry");
        JsonNode registry = registryPath == null
                ? mapper.createObjectNode() : mapper.readTree(registryPath.toFile());
        String modelId = encoderModelId(projectRoot, registry, mapper);
        JsonNode entry = registry.path("models").path(modelId);

        LocalProjectModelBootstrap.ResolvedProjectModel resolved;
        try {
            // Runtime execution resolves physical project artifacts read-only. Acquisition and
            // conversion are owned exclusively by model_runtime.
            resolved = LocalProjectModelBootstrap.ensure(projectRoot, modelId, Map.of(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving folder-local encoder '" + modelId + "'", e);
        }
        Path modelPath = containedRegularFile(modelsDirectory,
                resolved.modelPath(), "encoder artifact");
        if (modelPath == null) {
            throw new IOException("Folder-local encoder artifact is missing: " + resolved.modelPath());
        }
        Path modelDirectory = modelPath.getParent();
        String vocabFile = firstNonBlank(text(entry, "vocab_file"), "vocab.txt");
        Path vocabCandidate = resolved.tokenizerPath() == null
                ? modelDirectory.resolve(vocabFile) : resolved.tokenizerPath();
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
        String encoderType = firstNonBlank(
                text(entry.path("metadata"), "encoder_type"),
                text(entry, "encoder_type"));
        BatchConfiguration batchConfiguration = batchConfiguration(projectRoot, modelId, mapper);
        String fingerprint = sha256(modelId + "\n" + (encoderType == null ? "" : encoderType) + "\n"
                + entry.path("metadata") + "\n" + entry.path("tokenizer") + "\n"
                + batchConfiguration + "\n"
                + artifactChecksum(modelPath) + "\n" + artifactChecksum(vocabPath));
        return new ModelSelection(modelId, modelPath, vocabPath, encoderType, fingerprint,
                batchConfiguration);
    }

    private static BatchConfiguration batchConfiguration(
            Path projectRoot, String modelId, ObjectMapper mapper) throws IOException {
        BatchConfiguration defaults = new BatchConfiguration(4, 8, 8);
        Path configured = projectRoot.resolve("config/embedding-anserini-config.json").normalize();
        if (!Files.isRegularFile(configured)) {
            return defaults;
        }
        Path real = configured.toRealPath();
        if (!real.startsWith(projectRoot)) {
            throw new IOException("Folder-local embedding configuration escapes the project: "
                    + configured);
        }
        JsonNode config = mapper.readTree(real.toFile());
        String configuredModel = text(config, "modelIdentifier");
        if (configuredModel != null && !configuredModel.equals(modelId)) {
            return defaults;
        }
        int optimal = positiveInt(config, "baseOptimalBatchSize", defaults.optimal());
        int maximum = Math.max(optimal,
                positiveInt(config, "baseMaxBatchSize", defaults.maximum()));
        int absoluteMaximum = Math.max(maximum,
                positiveInt(config, "absoluteMaxBatchSize", maximum));
        return new BatchConfiguration(optimal, maximum, absoluteMaximum);
    }

    private static int positiveInt(JsonNode node, String field, int fallback) {
        int value = node.path(field).asInt(fallback);
        return value > 0 ? value : fallback;
    }

    private static String encoderModelId(Path projectRoot, JsonNode registry,
                                         ObjectMapper mapper) throws IOException {
        String configured = configuredEncoderModelId(projectRoot, registry, mapper);
        if (configured != null) return configured;
        throw new IOException("No ENCODER model is configured for the current folder");
    }

    private static String configuredEncoderModelId(Path projectRoot, JsonNode registry,
                                                   ObjectMapper mapper) throws IOException {
        String configuredModel = configuredEncoderSelection(projectRoot, mapper);
        if (configuredModel != null) {
            return configuredModel;
        }
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
        return null;
    }

    private static String configuredEncoderSelection(Path projectRoot, ObjectMapper mapper)
            throws IOException {
        Path configured = projectRoot.resolve("config/embedding-anserini-config.json").normalize();
        if (!Files.isRegularFile(configured)) return null;
        Path real = configured.toRealPath();
        if (!real.startsWith(projectRoot)) {
            throw new IOException("Folder-local embedding configuration escapes the project: "
                    + configured);
        }
        return text(mapper.readTree(real.toFile()), "modelIdentifier");
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

    static synchronized Map<String, String> modelConfig(Path projectRoot, ObjectMapper mapper)
            throws IOException {
        Path root = projectRoot.toRealPath();
        Path models = root.resolve("data/models").toRealPath();
        return resolveModel(root, models, mapper).modelConfig();
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

    /**
     * Apply the MCP-configured device placement to the embedding subprocess.
     *
     * <p>Sourced from the crawl request's runtime options — the MCP surface is the
     * configuration authority; there are no system-property fallbacks. The optional
     * {@code embeddingPlacement} entry accepts:</p>
     * <ul>
     *   <li>{@code "cpu"} — force CPU placement</li>
     *   <li>{@code "gpu"} — GPU placement, launcher-default device</li>
     *   <li>{@code "gpu:<device>"} — GPU placement pinned to an ND4J device index</li>
     *   <li>{@code "gpu:<device>:<maxBytes>"} — with a per-device memory cap</li>
     * </ul>
     * Absent/blank/{@code "inherit"} = INHERIT (launcher defaults apply).
     * Delivered through the shared device-agnostic placement infra — never
     * {@code CUDA_VISIBLE_DEVICES}.
     */
    private static void applyConfiguredPlacement(EmbeddingSubprocessLauncher launcher,
                                                 Map<String, Object> runtimeOptions) {
        Object raw = runtimeOptions == null ? null : runtimeOptions.get("embeddingPlacement");
        String placement = raw == null || String.valueOf(raw).isBlank()
                ? "inherit" : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (placement.isEmpty() || "inherit".equals(placement) || "auto".equals(placement)) {
            return;
        }
        try {
            if (placement.equals("cpu")) {
                launcher.applyPlacement(SubprocessPlacement.cpu());
                return;
            }
            if (placement.equals("gpu")) {
                launcher.applyPlacement(SubprocessPlacement.gpu(0, 0L));
                return;
            }
            if (placement.startsWith("gpu:")) {
                String[] parts = placement.split(":");
                int device = Integer.parseInt(parts[1].trim());
                long maxBytes = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0L;
                launcher.applyPlacement(SubprocessPlacement.gpu(device, maxBytes));
                return;
            }
            throw new IllegalArgumentException("embeddingPlacement must be one of: "
                    + "inherit, cpu, gpu, gpu:<device>[, :<maxBytes>]");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid embeddingPlacement '" + raw + "': " + e.getMessage(), e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Lease
            implements LocalProjectRagSearch.EmbeddingRuntime, EmbeddingModel {
        private final LocalEmbeddingRuntime runtime;
        private final boolean closeRuntimeOnRelease;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(LocalEmbeddingRuntime runtime, boolean closeRuntimeOnRelease) {
            this.runtime = runtime;
            this.closeRuntimeOnRelease = closeRuntimeOnRelease;
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
        public List<float[]> embedBatch(List<String> texts) {
            requireOpen();
            try {
                return runtime.embedBatch(texts);
            } catch (Exception e) {
                throw new IllegalStateException("Folder-local embedding failed for model '"
                        + runtime.modelId() + "'", e);
            }
        }

        @Override
        public INDArray embed(String text) {
            List<float[]> rows = embedBatch(List.of(text));
            return rows.isEmpty() ? Nd4j.empty() : Nd4j.create(rows.get(0));
        }

        @Override
        public INDArray embed(List<String> texts) {
            List<float[]> rows = embedBatch(texts);
            return rows.isEmpty() ? Nd4j.empty() : Nd4j.create(rows.toArray(float[][]::new));
        }

        @Override
        public INDArray embedDocuments(List<Document> documents) {
            if (documents == null || documents.isEmpty()) return Nd4j.empty();
            return embed(documents.stream().map(Document::getText).toList());
        }

        @Override
        public int dimensions() {
            return runtime.dimensions;
        }

        @Override
        public int getOptimalBatchSize() {
            return runtime.batchConfiguration.optimal();
        }

        @Override
        public int getMaxBatchSize() {
            return runtime.batchConfiguration.maximum();
        }

        @Override
        public String getModelName() {
            return runtime.modelId();
        }

        @Override
        public String getModelIdentifier() {
            return runtime.modelId();
        }

        @Override
        public boolean isInitialized() {
            return !closed.get() && runtime.process.isRunning() && runtime.dimensions > 0;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                LocalEmbeddingRuntime.release(runtime, closeRuntimeOnRelease);
            }
        }

        private void requireOpen() {
            if (closed.get()) throw new IllegalStateException("Embedding runtime lease is closed");
        }
    }

    private record ModelSelection(String modelId, Path modelPath, Path vocabPath,
                                  String encoderType, String fingerprint,
                                  BatchConfiguration batchConfiguration) {
        private Map<String, String> modelConfig() {
            Map<String, String> config = new LinkedHashMap<>();
            config.put("modelPath", modelPath.toString());
            config.put("vocabPath", vocabPath.toString());
            config.put("modelSource", "LOCAL_PROJECT");
            if (encoderType != null && !encoderType.isBlank()) {
                config.put("encoderType", encoderType);
            }
            return Map.copyOf(config);
        }
    }

    private record BatchConfiguration(int optimal, int maximum, int absoluteMaximum) {
    }

    private record ArtifactHash(String identity, String sha256) {
    }
}
