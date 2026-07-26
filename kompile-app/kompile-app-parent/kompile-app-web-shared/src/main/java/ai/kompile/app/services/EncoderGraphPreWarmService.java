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

package ai.kompile.app.services;

import ai.kompile.app.subprocess.model.ModelInitSubprocessArgs;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Eagerly pre-optimizes SameDiff encoder graph-optimization caches at startup /
 * provisioning time, so the first crawl's embedding subprocess loads from the fast
 * {@code model.opt.sdz} cache instead of paying the graph-optimization cost inline.
 *
 * <p>At {@link PostConstruct} time a single daemon thread is started with a
 * {@value #STARTUP_DELAY_MS}-ms startup delay (giving the {@code StagingServingBridge} time
 * to poll and populate the model registry). It then runs once, and for each registered
 * encoder model:
 * <ol>
 *   <li>Checks whether a valid {@code model.opt.sdz} + {@code .fp} fingerprint file already
 *       exists on disk — if so, skips (idempotent; the cache-hit path in
 *       {@code SameDiffEncoder.loadSameDiffModel} is already fast).</li>
 *   <li>Otherwise launches a {@link ModelInitSubprocessLauncher} subprocess for the model.
 *       The subprocess runs {@code AnseriniEncoderFactory.createEncoder(modelId)} which
 *       calls {@code SameDiffEncoder.loadSameDiffModel()} → {@code applyGraphOptimization()}
 *       → {@code saveGraphOptimizationCache()} — the same path used lazily on first crawl,
 *       now executed eagerly.  No optimization logic is duplicated here.</li>
 * </ol>
 *
 * <p>All failures are non-fatal: a failed pre-warm is logged and skipped; the lazy path in
 * the embedding subprocess still covers first-use.
 *
 * <h2>Guard conditions</h2>
 * <ul>
 *   <li>Runs only when {@link ModelInitSubprocessLauncher} is available in the context.</li>
 *   <li>Skips models whose opt-cache fingerprint is valid (cheap filesystem check).</li>
 *   <li>Does NOT run a subprocess per model on every app restart — only when cache is
 *       absent or stale.</li>
 * </ul>
 */
@Service
public class EncoderGraphPreWarmService {

    private static final Logger log = LoggerFactory.getLogger(EncoderGraphPreWarmService.class);

    /** Startup delay before the pre-warm sweep, letting the staging bridge populate the registry. */
    static final long STARTUP_DELAY_MS = 60_000L;

    /**
     * Base directory where the registry stores encoder model bundles.
     * Non-final so tests can override via {@code ReflectionTestUtils.setField}.
     */
    static Path REGISTRY_BASE = Paths.get(System.getProperty("user.home"), ".kompile", "models");

    /**
     * Source for the set of registered encoder model IDs.
     * Defaults to {@link AnseriniEncoderFactory#getAvailableModelIds()}.
     * Package-private: tests may inject a mock supplier to avoid static-method coupling.
     */
    Supplier<Set<String>> modelIdSource = AnseriniEncoderFactory::getAvailableModelIds;

    /**
     * Source for per-model info maps (keys: {@code path}, {@code modelFile}).
     * Defaults to {@link AnseriniEncoderFactory#getModelInfoMap(String)}.
     * Package-private: tests may inject a mock function.
     */
    Function<String, Map<String, Object>> modelInfoSource = AnseriniEncoderFactory::getModelInfoMap;

    @Autowired(required = false)
    private ModelInitSubprocessLauncher launcher;

    // ── Spring lifecycle ──────────────────────────────────────────────────────

    @PostConstruct
    void schedulePreWarm() {
        if (launcher == null) {
            log.debug("EncoderGraphPreWarmService: ModelInitSubprocessLauncher not available — skipping pre-warm");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            runPreWarm();
        }, "encoder-graph-prewarm");
        t.setDaemon(true);
        t.start();
        log.info("EncoderGraphPreWarmService: pre-warm scheduled (starts in {}s)", STARTUP_DELAY_MS / 1000);
    }

    // ── Pre-warm logic ────────────────────────────────────────────────────────

    /**
     * Sweep all registered encoder models and launch a model-init subprocess for any
     * that lack a valid graph-optimization cache.  Package-private for direct unit-test
     * invocation without threading.
     */
    void runPreWarm() {
        Set<String> modelIds;
        try {
            modelIds = modelIdSource.get();
        } catch (Exception e) {
            log.warn("EncoderGraphPreWarmService: failed to list encoder models — {}", e.getMessage());
            return;
        }

        if (modelIds.isEmpty()) {
            log.info("EncoderGraphPreWarmService: no encoder models registered — nothing to pre-warm");
            return;
        }

        log.info("EncoderGraphPreWarmService: checking opt-cache for {} encoder model(s): {}",
                modelIds.size(), modelIds);

        for (String modelId : modelIds) {
            try {
                preWarmModel(modelId);
            } catch (Exception e) {
                log.warn("EncoderGraphPreWarmService: non-fatal — failed to pre-warm '{}': {}",
                        modelId, e.getMessage());
            }
        }
    }

    private void preWarmModel(String modelId) {
        Path modelPath = resolveModelPath(modelId);
        if (modelPath == null) {
            log.debug("EncoderGraphPreWarmService: model file not found on disk for '{}' — skipping", modelId);
            return;
        }

        if (isOptCacheValid(modelPath)) {
            log.info("EncoderGraphPreWarmService: opt cache already valid for '{}' at {} — skipping",
                    modelId, modelPath);
            return;
        }

        log.info("EncoderGraphPreWarmService: no valid opt cache for '{}' at {} — launching pre-warm subprocess",
                modelId, modelPath);

        ModelInitSubprocessArgs args = ModelInitSubprocessArgs.builder()
                .taskId("prewarm-" + modelId)
                .modelIdentifier(modelId)
                .modelSourceType("registry")
                // Goal is cache population, not output validation — skip the encode+validate cycle.
                .skipValidation(true)
                .build();

        // Fire-and-forget: the subprocess writes model.opt.sdz + .fp then exits.
        // A failure listener logs at WARN so init does not propagate the failure.
        launcher.launchModelInit(args, null, null,
                failure -> log.warn(
                        "EncoderGraphPreWarmService: pre-warm subprocess failed for '{}': {} (retriable={})",
                        modelId,
                        failure != null ? failure.errorMessage() : "unknown",
                        failure != null && failure.retriable()));
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    /**
     * Resolve the absolute filesystem path to the {@code .sdz} model file, or
     * {@code null} if it cannot be determined or does not exist on disk.
     *
     * <p>Tries the registry entry's {@code path} directory first, then falls back to
     * {@code <REGISTRY_BASE>/<modelId>/<modelFile>} — mirroring the two-candidate
     * lookup in {@code RegistryBasedModelManager.loadBundleFromLocalCache}.
     */
    Path resolveModelPath(String modelId) {
        try {
            Map<String, Object> info = modelInfoSource.apply(modelId);
            if (info == null) return null;

            String modelFile = (String) info.get("modelFile");
            if (modelFile == null || modelFile.isBlank()) return null;

            // Primary: registry entry's relative 'path' directory
            Object relPathObj = info.get("path");
            String relPath = relPathObj instanceof String s ? s : null;
            if (relPath != null && !relPath.isBlank()) {
                Path candidate = REGISTRY_BASE.resolve(relPath).resolve(modelFile);
                if (Files.exists(candidate)) return candidate;
            }

            // Fallback: modelId as directory name
            Path fallback = REGISTRY_BASE.resolve(modelId).resolve(modelFile);
            if (Files.exists(fallback)) return fallback;

        } catch (Exception e) {
            log.debug("EncoderGraphPreWarmService: error resolving model path for '{}': {}",
                    modelId, e.getMessage());
        }
        return null;
    }

    // ── Cache-validity check ──────────────────────────────────────────────────

    /**
     * Returns {@code true} when a valid graph-optimization cache already exists for
     * {@code modelPath} — i.e. both the {@code model.opt.sdz} file and a matching
     * {@code model.opt.sdz.fp} fingerprint ({@code "<sizeBytes>,<lastModifiedMillis>"})
     * are present and the fingerprint matches the current size+mtime of {@code modelPath}.
     *
     * <p><b>Filesystem check only.</b>  This mirrors the guard in
     * {@code SameDiffEncoder.isOptCacheValid()} without duplicating any optimization
     * logic.  The optimization itself always runs inside the {@link ModelInitSubprocessLauncher}
     * subprocess via {@code SameDiffEncoder.loadSameDiffModel()} when the cache is absent.
     */
    static boolean isOptCacheValid(Path modelPath) {
        try {
            String fileName = modelPath.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String optFileName = dot > 0
                    ? fileName.substring(0, dot) + ".opt" + fileName.substring(dot)
                    : fileName + ".opt";
            Path optPath = modelPath.resolveSibling(optFileName);
            Path fpPath  = optPath.resolveSibling(optPath.getFileName() + ".fp");

            if (!Files.exists(optPath) || !Files.isRegularFile(optPath)) return false;
            if (!Files.exists(fpPath)) return false;

            String expected = Files.size(modelPath) + "," + Files.getLastModifiedTime(modelPath).toMillis();
            String stored   = Files.readString(fpPath).trim();
            return expected.equals(stored);
        } catch (Exception e) {
            return false;
        }
    }
}
