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

import ai.kompile.app.services.subprocess.ServingSubprocessLauncher;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Staging → serving auto-load bridge.
 *
 * <p>Polls {@code GET /api/staging/active} on the Model Staging service resolved from the
 * managed endpoint configuration every {@link KbConfig#getServingAutoLoadPollIntervalSeconds()}
 * seconds. When an active {@code llm_ggml} model is detected it:</p>
 * <ol>
 *   <li>Resolves the model's local file path via the staging registry API
 *       ({@code GET /api/staging/registry/model/{id}}).</li>
 *   <li>Downloads the model file to {@code ~/.kompile/llm-cache/{modelId}/} if not
 *       already cached — using the same convention as the LLM load controller in the
 *       serving subprocess.</li>
 *   <li>Calls {@link ServingSubprocessLauncher#loadModel} to start (or swap) the
 *       serving subprocess with the new model.</li>
 * </ol>
 *
 * <p>Controlled by {@link KbConfig#isServingAutoLoadEnabled()} (default {@code true}).
 * When no LLM model is active in staging the bridge idles without starting a subprocess
 * ("no artifact yet → inert"). Only {@code llm_ggml} type models are auto-loaded;
 * reasoning artifacts ({@code kge}, {@code psl}, {@code mebn}) are ignored here and
 * handled by {@link ModelDeploymentHook}.</p>
 */
@Service
public class StagingServingBridge {

    private static final Logger log = LoggerFactory.getLogger(StagingServingBridge.class);

    /** ModelType string in the staging active response for GGML/SameDiff LLMs. */
    private static final String LLM_MODEL_TYPE = "llm_ggml";

    /** Local cache directory resolved through the same CLI-managed project rules as serving. */
    private static Path llmCacheDir() {
        return KompileHome.llmCacheDirectory().toPath();
    }

    /** Explicit test override; production resolves the managed endpoint for each poll. */
    private String stagingUrl;

    @Autowired(required = false)
    private ServingSubprocessLauncher launcher;

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "staging-serving-bridge");
        t.setDaemon(true);
        return t;
    });

    /** Model ID that is currently loaded into the serving subprocess (null = none). */
    private volatile String currentModelId;

    /** Failure memo: last model whose load failed, with exponential backoff — without this the
     *  poll loop re-downloads a multi-GB artifact every interval forever (observed live 2026-07-05:
     *  41 silent re-download attempts in one boot). */
    private volatile String lastFailedModelId;
    private volatile int consecutiveFailures;
    private volatile long nextAttemptAtMillis;

    private volatile ScheduledFuture<?> pollTask;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (launcher == null) {
            log.debug("StagingServingBridge: ServingSubprocessLauncher not available, bridge inactive");
            return;
        }
        if (kbConfigManager != null && !kbConfigManager.current().isServingAutoLoadEnabled()) {
            log.info("StagingServingBridge: disabled by kbServingAutoLoadEnabled=false");
            return;
        }
        int intervalSec = resolveInterval();
        log.info("StagingServingBridge: starting with poll interval {}s, staging={}", intervalSec,
                stagingBase());
        pollTask = scheduler.scheduleAtFixedRate(
                this::poll, 5, intervalSec, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledFuture<?> task = pollTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdownNow();
    }

    // ── Poll logic ────────────────────────────────────────────────────────────

    /**
     * Single poll cycle: fetch active staging model, compare with current, swap if needed.
     * All errors are caught and logged — never propagated so the scheduler keeps running.
     */
    void poll() {
        if (kbConfigManager != null && !kbConfigManager.current().isServingAutoLoadEnabled()) {
            log.debug("StagingServingBridge: poll skipped (disabled by kbServingAutoLoadEnabled=false)");
            return;
        }
        try {
            String activeModelId = fetchActiveLlmModelId();
            if (Objects.equals(activeModelId, currentModelId)) {
                if (activeModelId == null || launcher.isRunning()) {
                    log.trace("StagingServingBridge: no change (active={})", activeModelId);
                    return;
                }
                log.warn("StagingServingBridge: serving subprocess for active model '{}' is not running; restarting",
                        activeModelId);
                currentModelId = null;
            }
            if (activeModelId == null) {
                // Active LLM removed from staging — stop the subprocess
                log.info("StagingServingBridge: no active LLM in staging; stopping serving subprocess");
                launcher.stop();
                currentModelId = null;
                return;
            }
            // Failure backoff: don't hammer staging every poll for a model that keeps failing.
            if (activeModelId.equals(lastFailedModelId) && System.currentTimeMillis() < nextAttemptAtMillis) {
                log.debug("StagingServingBridge: model '{}' in failure backoff for another {}s ({} consecutive failures)",
                        activeModelId,
                        Math.max(0, (nextAttemptAtMillis - System.currentTimeMillis()) / 1000),
                        consecutiveFailures);
                return;
            }
            // Model changed (or first detection) — resolve path and load
            log.info("StagingServingBridge: active LLM changed {} → {}; loading...",
                    currentModelId, activeModelId);
            try {
                loadModel(activeModelId);
                lastFailedModelId = null;
                consecutiveFailures = 0;
                nextAttemptAtMillis = 0L;
            } catch (Exception loadFailure) {
                consecutiveFailures = activeModelId.equals(lastFailedModelId) ? consecutiveFailures + 1 : 1;
                lastFailedModelId = activeModelId;
                long backoffSec = Math.min(600L, (long) resolveInterval() << Math.min(6, consecutiveFailures));
                nextAttemptAtMillis = System.currentTimeMillis() + backoffSec * 1000L;
                log.warn("StagingServingBridge: failed to load model '{}' (attempt {}): {} — next attempt in {}s",
                        activeModelId, consecutiveFailures, loadFailure.getMessage(), backoffSec);
                if (consecutiveFailures == 1 || consecutiveFailures % 10 == 0) {
                    log.warn("StagingServingBridge: load failure detail", loadFailure);
                }
            }
        } catch (Exception e) {
            log.debug("StagingServingBridge: poll error (will retry next interval): {}", e.getMessage());
        }
    }

    // ── Staging query helpers ─────────────────────────────────────────────────

    /**
     * Calls {@code GET /api/staging/active} and returns the model ID for {@code llm_ggml},
     * or {@code null} if no LLM is active or staging is unreachable.
     */
    String fetchActiveLlmModelId() throws IOException, InterruptedException {
        String url = stagingBase() + "/api/staging/active";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.debug("StagingServingBridge: /api/staging/active returned HTTP {}", resp.statusCode());
            return null;
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode active = root.path("active");
        if (active.isMissingNode() || active.isNull()) {
            return null;
        }
        JsonNode llmNode = active.path(LLM_MODEL_TYPE);
        if (llmNode.isMissingNode() || llmNode.isNull() || !llmNode.isTextual()) {
            return null;
        }
        String modelId = llmNode.asText().trim();
        return modelId.isBlank() ? null : modelId;
    }

    /**
     * Resolves the local model file path (downloading if not yet cached) and asks
     * {@link ServingSubprocessLauncher#loadModel} to start or swap the subprocess.
     */
    private void loadModel(String modelId) throws Exception {
        Path modelFilePath = resolveLocalModelPath(modelId);
        log.info("StagingServingBridge: loading model '{}' from {}", modelId, modelFilePath);
        launcher.loadModel(modelId, modelFilePath.toString(), null);
        currentModelId = modelId;
        log.info("StagingServingBridge: serving subprocess now serving model '{}'", modelId);
    }

    /**
     * Resolves the local filesystem path to the model file for {@code modelId}.
     *
     * <p>Strategy (same convention as the serving subprocess's {@code LlmModelController}):</p>
     * <ol>
     *   <li>Query staging registry for the entry: {@code GET /api/staging/registry/model/{id}}
     *       to discover the {@code model_file} filename.</li>
     *   <li>Check {@code ~/.kompile/llm-cache/{modelId}/{model_file}} — if it exists and has
     *       non-zero size, use it (cache hit, no download needed).</li>
     *   <li>If not cached, download the model file from staging
     *       ({@code GET /api/staging/registry/model/{id}/download/model}) and save to the
     *       local cache directory before returning the path.</li>
     * </ol>
     *
     * <p>The path returned is the model file, not the directory (matching what
     * {@code ServingSubprocessLauncher.loadModel} expects).</p>
     */
    Path resolveLocalModelPath(String modelId) throws Exception {
        // 1. Fetch registry entry to get filename
        String registryUrl = stagingBase() + "/api/staging/registry/model/" + modelId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(registryUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Staging registry returned HTTP " + resp.statusCode()
                    + " for model '" + modelId + "'");
        }
        JsonNode entry = objectMapper.readTree(resp.body());
        String modelFileName = textOrDefault(entry.get("model_file"), "model.sdz");

        // 2. Check local cache — single-file artifact
        Path modelDir = llmCacheDir().resolve(modelId);
        Path modelFile = modelDir.resolve(modelFileName);
        if (Files.exists(modelFile) && Files.size(modelFile) > 0) {
            log.info("StagingServingBridge: model '{}' found in local cache at {}", modelId, modelFile);
            return modelFile;
        }

        // 2b. Check local cache — sharded SameDiff artifact (<base>.shardN-of-M.sdnb). The registry's
        // model_file may name a single-file artifact that never existed for a sharded model; a valid
        // shard set already on disk must count as a cache hit (observed live 2026-07-05: 5.2GB of
        // valid shards ignored while the bridge re-downloaded a nonexistent model.sdz forever).
        Path shardedEntry = findCompleteShardedEntry(modelDir);
        if (shardedEntry != null) {
            log.info("StagingServingBridge: model '{}' found as sharded artifact in local cache, entry {}",
                    modelId, shardedEntry);
            return shardedEntry;
        }

        // 3. Download from staging — the registry's single model file first, full file listing
        // as fallback (covers sharded/multi-file artifacts and stale model_file metadata).
        log.info("StagingServingBridge: model '{}' not in local cache; downloading from staging...", modelId);
        Files.createDirectories(modelDir);
        try {
            downloadModelFile(modelId, modelFileName, modelFile);
        } catch (Exception primaryFailure) {
            log.warn("StagingServingBridge: single-file download failed for model '{}' ({}); "
                    + "falling back to the staging file listing", modelId, primaryFailure.getMessage());
            downloadAllRegistryFiles(modelId, modelDir);
        }
        // Also download tokenizer if present (best-effort)
        String vocabFileName = textOrDefault(entry.get("vocab_file"), "tokenizer.json");
        Path vocabFile = modelDir.resolve(vocabFileName);
        if (!Files.exists(vocabFile)) {
            try {
                downloadVocabFile(modelId, vocabFileName, vocabFile);
            } catch (Exception e) {
                log.debug("StagingServingBridge: tokenizer download failed (non-fatal): {}", e.getMessage());
            }
        }
        // Resolve what actually materialized
        if (Files.exists(modelFile) && Files.size(modelFile) > 0) {
            return modelFile;
        }
        Path fallbackEntry = findCompleteShardedEntry(modelDir);
        if (fallbackEntry != null) {
            return fallbackEntry;
        }
        throw new IOException("Model '" + modelId + "' could not be materialized in " + modelDir
                + " (expected '" + modelFileName + "' or a complete sharded artifact set)");
    }

    private void downloadModelFile(String modelId, String fileName, Path dest) throws Exception {
        String downloadUrl = stagingBase() + "/api/staging/registry/model/" + modelId + "/download/model";
        log.info("StagingServingBridge: downloading model file {} → {}", downloadUrl, dest);
        downloadFile(downloadUrl, dest);
    }

    private void downloadVocabFile(String modelId, String fileName, Path dest) throws Exception {
        String downloadUrl = stagingBase() + "/api/staging/registry/model/" + modelId + "/download/vocab";
        log.info("StagingServingBridge: downloading vocab file {} → {}", downloadUrl, dest);
        downloadFile(downloadUrl, dest);
    }

    private void downloadFile(String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30)) // large models can take a while
                .GET()
                .build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
        }
        // Stream to a temp sibling and move atomically: a failed/partial/empty download must never
        // overwrite a valid cached file or masquerade as one (a REPLACE_EXISTING copy straight to the
        // final path truncated a good cached manifest to 0 bytes on 2026-07-04).
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) {
                throw new IOException("Empty body (0 bytes) downloading " + url);
            }
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amns) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        log.info("StagingServingBridge: downloaded {} bytes to {}", Files.size(dest), dest);
    }

    /**
     * Downloads every file the staging registry lists for {@code modelId}
     * ({@code GET /api/staging/registry/model/{id}/files}) into {@code modelDir}, skipping files
     * already cached with non-zero size. Covers sharded/multi-file artifacts whose registry
     * {@code model_file} metadata does not correspond to a single downloadable file.
     */
    private void downloadAllRegistryFiles(String modelId, Path modelDir) throws Exception {
        String listUrl = stagingBase() + "/api/staging/registry/model/" + modelId + "/files";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(listUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Staging file listing returned HTTP " + resp.statusCode()
                    + " for model '" + modelId + "'");
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode arr = root.isArray() ? root : root.path("files");
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IOException("Staging file listing empty for model '" + modelId + "'");
        }
        int fetched = 0;
        for (JsonNode n : arr) {
            String name = n.isTextual() ? n.asText() : textOrDefault(n.get("name"), null);
            if (name == null || name.isBlank() || name.contains("..") || name.contains("/")) {
                continue;
            }
            Path dest = modelDir.resolve(name);
            if (Files.exists(dest) && Files.size(dest) > 0) {
                continue;
            }
            downloadFile(stagingBase() + "/api/staging/registry/model/" + modelId + "/download/file/" + name, dest);
            fetched++;
        }
        log.info("StagingServingBridge: fetched {} file(s) from staging listing for model '{}'", fetched, modelId);
    }

    /**
     * Detects a complete sharded SameDiff artifact in {@code modelDir}: shard files named
     * {@code <base>.shard<i>-of-<N>.sdnb} with all {@code N} shards present and non-empty.
     * Returns the canonical {@code <base>.sdnb} load path, which may intentionally not exist:
     * {@code SameDiff.load} uses that base name to discover and merge all sibling shards.
     * Returns {@code null} when no complete set exists. Package-private for tests.
     */
    static Path findCompleteShardedEntry(Path modelDir) {
        if (modelDir == null || !Files.isDirectory(modelDir)) {
            return null;
        }
        Pattern shardPattern = Pattern.compile("(.+)\\.shard(\\d+)-of-(\\d+)\\.sdnb");
        Map<String, Map<Integer, Path>> shardsByBase = new HashMap<>();
        Map<String, Integer> totalByBase = new HashMap<>();
        try (Stream<Path> files = Files.list(modelDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher m = shardPattern.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                try {
                    if (Files.size(f) == 0) {
                        continue;
                    }
                } catch (IOException sizeFailure) {
                    continue;
                }
                String base = m.group(1);
                shardsByBase.computeIfAbsent(base, k -> new HashMap<>()).put(Integer.parseInt(m.group(2)), f);
                totalByBase.put(base, Integer.parseInt(m.group(3)));
            }
        } catch (IOException listFailure) {
            return null;
        }
        for (Map.Entry<String, Integer> e : totalByBase.entrySet()) {
            Map<Integer, Path> shards = shardsByBase.get(e.getKey());
            int total = e.getValue();
            if (shards != null && total > 0 && shards.size() == total) {
                // SameDiffSerializer derives sibling shard names from this canonical base.
                // Passing shard 0 directly makes it look like a standalone SDNB and leaves
                // constants/variables stored in later shards unmaterialized.
                return modelDir.resolve(e.getKey() + ".sdnb");
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stagingBase() {
        String url = stagingUrl;
        if (url == null || url.isBlank()) {
            url = ServiceEndpointsConfigManager.shared().current().effectiveStagingUrl();
        }
        return url.trim().replaceAll("/+$", "");
    }

    private int resolveInterval() {
        if (kbConfigManager == null) {
            return 15;
        }
        KbConfig cfg = kbConfigManager.current();
        int v = cfg.getServingAutoLoadPollIntervalSeconds();
        return Math.max(5, Math.min(3600, v));
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        return (node != null && !node.isNull() && node.isTextual()) ? node.asText() : fallback;
    }
}
