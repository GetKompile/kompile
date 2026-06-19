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

package ai.kompile.crawl.graph;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Hot-reloadable runtime configuration for the unified crawl pipeline.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 * Reads {@code graph-extraction-config.json} from
 * {@code ~/.kompile/data/config/} and applies settings to the service and its
 * helpers. Config is refreshed at most once every {@value #CONFIG_REFRESH_INTERVAL_NANOS}
 * nanoseconds (5 seconds).</p>
 */
@Component
class CrawlRuntimeConfigManager {

    private static final Logger log = LoggerFactory.getLogger(CrawlRuntimeConfigManager.class);

    private static final String GRAPH_EXTRACTION_CONFIG_FILENAME = "graph-extraction-config.json";
    private static final long CONFIG_REFRESH_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds
    private static final int DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE = 10;

    private final Path graphExtractionConfigPath;
    private final ObjectMapper configObjectMapper = JsonUtils.standardMapper();

    private volatile long graphExtractionConfigLastModified = Long.MIN_VALUE;
    private volatile CrawlRuntimeConfig crawlRuntimeConfig = CrawlRuntimeConfig.defaults();
    private volatile long lastConfigRefreshNanos = 0L;

    /** Global CLI-agent quota ledger; receives window/cap settings on each config apply. */
    @Autowired(required = false)
    private CliAgentQuotaLedger cliAgentQuotaLedger;

    CrawlRuntimeConfigManager() {
        this.graphExtractionConfigPath =
                KompileHome.dataDir().toPath().resolve("config").resolve(GRAPH_EXTRACTION_CONFIG_FILENAME);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the current config, re-reading the file at most once per 5 seconds.
     */
    synchronized CrawlRuntimeConfig refreshRuntimeConfig() {
        long now = System.nanoTime();
        if ((now - lastConfigRefreshNanos) < CONFIG_REFRESH_INTERVAL_NANOS && crawlRuntimeConfig != null) {
            return crawlRuntimeConfig;
        }
        lastConfigRefreshNanos = now;
        try {
            CrawlRuntimeConfig config;
            if (!Files.exists(graphExtractionConfigPath)) {
                graphExtractionConfigLastModified = Long.MIN_VALUE;
                config = CrawlRuntimeConfig.defaults();
            } else {
                long lastModified = Files.getLastModifiedTime(graphExtractionConfigPath).toMillis();
                if (lastModified != graphExtractionConfigLastModified) {
                    JsonNode root = configObjectMapper.readTree(graphExtractionConfigPath.toFile());
                    crawlRuntimeConfig = CrawlRuntimeConfig.from(root);
                    graphExtractionConfigLastModified = lastModified;
                    log.info("Loaded unified crawl runtime config from {}", graphExtractionConfigPath);
                }
                config = crawlRuntimeConfig;
            }
            return config;
        } catch (Exception e) {
            log.warn("Failed to read unified crawl runtime config from {}: {}",
                    graphExtractionConfigPath, e.getMessage());
            return crawlRuntimeConfig;
        }
    }

    /**
     * Applies settings from {@code config} to the service fields and helper beans.
     *
     * @param config           the config to apply
     * @param service          the owning service (receives volatile field updates)
     * @param memoryMonitor    receives memory threshold settings (may be null)
     * @param graphExtOrch     receives graph extraction settings (may be null)
     * @param vectorHelper     receives vector batch size and memory settings (may be null)
     * @param executor         the job executor (thread pool size is resized live)
     * @param executorQueueCapacity current queue capacity (used to detect resizing need)
     * @return the (possibly updated) queue capacity value; caller should store it
     */
    int applyRuntimeConfig(CrawlRuntimeConfig config,
                           UnifiedCrawlGraphServiceImpl service,
                           CrawlMemoryMonitor memoryMonitor,
                           GraphExtractionOrchestrator graphExtOrch,
                           VectorIndexingHelper vectorHelper,
                           CrawlLlmDispatcher llmDispatcher,
                           ThreadPoolExecutor executor,
                           int executorQueueCapacity) {
        service.maxConcurrentJobs = config.maxConcurrentJobs;
        service.queueCapacity = config.queueCapacity;
        service.memoryWaitThresholdPercent = config.memoryWaitThresholdPercent;
        service.memoryCriticalThresholdPercent = config.memoryCriticalThresholdPercent;
        service.memoryWaitTimeoutSeconds = config.memoryWaitTimeoutSeconds;
        service.nativeMemoryCleanupEnabled = config.nativeMemoryCleanupEnabled;
        service.nativeMemoryCleanupPasses = config.nativeMemoryCleanupPasses;
        service.nativeMemoryWaitThresholdPercent = config.nativeMemoryWaitThresholdPercent;
        service.nativeMemoryCriticalThresholdPercent = config.nativeMemoryCriticalThresholdPercent;
        service.graphExtractionBatchSize = config.graphExtractionBatchSize;
        service.backgroundGraphThreads = config.backgroundGraphThreads;
        service.sourceLoadParallelism = config.sourceLoadParallelism;
        service.chunkingParallelism = config.chunkingParallelism;
        service.graphExtractionParallelism = config.graphExtractionParallelism;
        service.graphExtractionTargetCharsPerBatch = config.graphExtractionTargetCharsPerBatch;
        service.chunkingTargetCharsPerTask = config.chunkingTargetCharsPerTask;
        service.configuredVectorBatchSize = config.vectorBatchSize;
        service.postProcessParallel = config.postProcessParallel;
        service.graphConstructorSkipEmbedding = config.graphConstructorSkipEmbedding;
        service.graphConstructorPersistMatrixGraph = config.graphConstructorPersistMatrixGraph;
        service.retainResultGraph = config.retainResultGraph;
        service.costSortChunks = config.costSortChunks;
        service.llmCallTimeoutSeconds = config.llmCallTimeoutSeconds;
        service.graphExtractionBatchTimeoutSeconds = config.graphExtractionBatchTimeoutSeconds;
        service.crawlGraphExtractionMaxCharsPerChunk = config.crawlGraphExtractionMaxCharsPerChunk;
        service.crawlGraphExtractionMaxCharsPerChunkVlm = config.crawlGraphExtractionMaxCharsPerChunkVlm;
        service.graphExtractionChunksPerPrompt = config.graphExtractionChunksPerPrompt;

        if (memoryMonitor != null) {
            memoryMonitor.applyConfig(config.memoryWaitThresholdPercent, config.memoryCriticalThresholdPercent,
                    config.memoryWaitTimeoutSeconds, config.nativeMemoryCleanupEnabled,
                    config.nativeMemoryCleanupPasses, config.nativeMemoryWaitThresholdPercent,
                    config.nativeMemoryCriticalThresholdPercent);
        }

        if (graphExtOrch != null) {
            graphExtOrch.graphExtractionBatchSize = config.graphExtractionBatchSize;
            graphExtOrch.graphExtractionTargetCharsPerBatch = config.graphExtractionTargetCharsPerBatch;
            graphExtOrch.graphExtractionParallelism = config.graphExtractionParallelism;
            graphExtOrch.costSortChunks = config.costSortChunks;
            graphExtOrch.graphConstructorSkipEmbedding = config.graphConstructorSkipEmbedding;
            graphExtOrch.graphConstructorPersistMatrixGraph = config.graphConstructorPersistMatrixGraph;
            graphExtOrch.retainResultGraph = config.retainResultGraph;
            graphExtOrch.memoryCriticalThresholdPercent = config.memoryCriticalThresholdPercent;
            graphExtOrch.memoryWaitTimeoutSeconds = config.memoryWaitTimeoutSeconds;
            graphExtOrch.graphExtractionBatchTimeoutSeconds = config.graphExtractionBatchTimeoutSeconds;
            graphExtOrch.maxCharsPerChunk = config.crawlGraphExtractionMaxCharsPerChunk;
            graphExtOrch.maxCharsPerChunkVlm = config.crawlGraphExtractionMaxCharsPerChunkVlm;
            graphExtOrch.graphExtractionChunksPerPrompt = config.graphExtractionChunksPerPrompt;
        }

        if (llmDispatcher != null) {
            llmDispatcher.setLlmCallTimeoutSeconds(config.llmCallTimeoutSeconds);
            llmDispatcher.setCircuitBreakerFailureThreshold(config.circuitBreakerFailureThreshold);
            llmDispatcher.setCircuitBreakerCooldownSeconds(config.circuitBreakerCooldownSeconds);
        }

        if (vectorHelper != null) {
            vectorHelper.configuredVectorBatchSize = config.vectorBatchSize;
            vectorHelper.memoryCriticalThresholdPercent = config.memoryCriticalThresholdPercent;
            vectorHelper.memoryWaitThresholdPercent = config.memoryWaitThresholdPercent;
            vectorHelper.memoryWaitTimeoutSeconds = config.memoryWaitTimeoutSeconds;
        }

        if (cliAgentQuotaLedger != null) {
            cliAgentQuotaLedger.setQuotaWindowMs(config.cliQuotaWindowMs);
            cliAgentQuotaLedger.setMinHealthyMs(config.cliQuotaMinHealthyMs);
            cliAgentQuotaLedger.setMaxRequestsPerWindow(config.cliMaxRequestsPerWindow);
            cliAgentQuotaLedger.setMaxTokensPerWindow(config.cliMaxTokensPerWindow);
        }

        int newQueueCapacity = executorQueueCapacity;
        if (executor != null && !executor.isShutdown()) {
            int threads = Math.max(1, config.maxConcurrentJobs);
            if (threads > executor.getMaximumPoolSize()) {
                executor.setMaximumPoolSize(threads);
                executor.setCorePoolSize(threads);
            } else {
                executor.setCorePoolSize(threads);
                executor.setMaximumPoolSize(threads);
            }

            int capacity = Math.max(1, config.queueCapacity);
            if (executorQueueCapacity != capacity
                    && executor.getActiveCount() == 0
                    && executor.getQueue().isEmpty()) {
                // Signal to caller that the executor should be rebuilt
                newQueueCapacity = -1;
            }
        }
        return newQueueCapacity;
    }

    /**
     * Applies per-request runtime overrides to the service fields.
     *
     * @param overrides override values from the incoming request (may be null)
     * @param service   service whose volatile fields are updated
     */
    void applyRequestOverrides(UnifiedCrawlRequest.RuntimeConfig overrides,
                               UnifiedCrawlGraphServiceImpl service) {
        if (overrides == null) return;
        if (overrides.getGraphExtractionParallelism() != null) {
            service.graphExtractionParallelism =
                    Math.max(1, Math.min(32, overrides.getGraphExtractionParallelism()));
        }
        if (overrides.getGraphExtractionBatchSize() != null) {
            service.graphExtractionBatchSize =
                    Math.max(1, Math.min(128, overrides.getGraphExtractionBatchSize()));
        }
        if (overrides.getGraphExtractionTargetCharsPerBatch() != null) {
            service.graphExtractionTargetCharsPerBatch =
                    Math.max(1000, Math.min(500000, overrides.getGraphExtractionTargetCharsPerBatch()));
        }
        if (overrides.getSourceLoadParallelism() != null) {
            service.sourceLoadParallelism =
                    Math.max(1, Math.min(32, overrides.getSourceLoadParallelism()));
        }
        if (overrides.getChunkingParallelism() != null) {
            service.chunkingParallelism =
                    Math.max(1, Math.min(32, overrides.getChunkingParallelism()));
        }
        if (overrides.getVectorBatchSize() != null) {
            service.configuredVectorBatchSize =
                    Math.max(0, Math.min(4096, overrides.getVectorBatchSize()));
        }
        if (overrides.getCostSortChunks() != null) {
            service.costSortChunks = overrides.getCostSortChunks();
        }
        if (overrides.getLlmCallTimeoutSeconds() != null) {
            service.llmCallTimeoutSeconds =
                    Math.max(10, Math.min(1800, overrides.getLlmCallTimeoutSeconds()));
        }
        if (overrides.getGraphExtractionBatchTimeoutSeconds() != null) {
            service.graphExtractionBatchTimeoutSeconds =
                    Math.max(60, Math.min(7200, overrides.getGraphExtractionBatchTimeoutSeconds()));
        }
    }

    // ── CrawlRuntimeConfig ──────────────────────────────────────────────────

    /**
     * Snapshot of all tunable parameters for the unified crawl pipeline.
     * Loaded from JSON; defaults match the hard-coded fallback values in the service.
     */
    static class CrawlRuntimeConfig {
        int maxConcurrentJobs = 1;
        int queueCapacity = 25;
        int memoryWaitThresholdPercent = 82;
        int memoryCriticalThresholdPercent = 90;
        int memoryWaitTimeoutSeconds = 300;
        boolean nativeMemoryCleanupEnabled = true;
        int nativeMemoryCleanupPasses = 3;
        int nativeMemoryWaitThresholdPercent = 82;
        int nativeMemoryCriticalThresholdPercent = 90;
        int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
        int backgroundGraphThreads = 2;
        int sourceLoadParallelism = 2;
        int chunkingParallelism = 2;
        int graphExtractionParallelism = 4;
        int graphExtractionTargetCharsPerBatch = 48_000;
        int chunkingTargetCharsPerTask = 200_000;
        int vectorBatchSize = 0;
        boolean postProcessParallel = false;
        boolean graphConstructorSkipEmbedding = true;
        boolean graphConstructorPersistMatrixGraph = false;
        boolean retainResultGraph = false;
        boolean costSortChunks = true;
        int llmCallTimeoutSeconds = 300;
        int graphExtractionBatchTimeoutSeconds = 2700;
        // Configurable truncation limits for inline LLM extraction.
        // Defaults equal the former hard-coded values; behaviour is unchanged unless set.
        int crawlGraphExtractionMaxCharsPerChunk = 12_000;
        int crawlGraphExtractionMaxCharsPerChunkVlm = 16_000;
        // Number of chunks to group into a single LLM prompt. 1 = one-call-per-chunk (default).
        int graphExtractionChunksPerPrompt = 1;
        int circuitBreakerFailureThreshold = 5;
        int circuitBreakerCooldownSeconds = 60;
        // CLI-agent quota ledger (cross-job; both time-window and request/token caps)
        long cliQuotaWindowMs = 18_000_000L;   // 5 hours rolling exhaustion window
        long cliQuotaMinHealthyMs = 60_000L;   // hysteresis gap before backoff resets
        long cliMaxRequestsPerWindow = 0;      // 0 = no global request cap
        long cliMaxTokensPerWindow = 0;        // 0 = no global token cap

        static CrawlRuntimeConfig defaults() {
            return new CrawlRuntimeConfig();
        }

        static CrawlRuntimeConfig from(JsonNode root) {
            CrawlRuntimeConfig config = defaults();
            if (root == null) {
                return config;
            }
            config.maxConcurrentJobs = intField(root, "crawlMaxConcurrentJobs", config.maxConcurrentJobs, 1, 16);
            config.queueCapacity = intField(root, "crawlQueueCapacity", config.queueCapacity, 1, 500);
            config.memoryWaitThresholdPercent = intField(root, "crawlMemoryWaitThresholdPercent", config.memoryWaitThresholdPercent, 1, 99);
            config.memoryCriticalThresholdPercent = intField(root, "crawlMemoryCriticalThresholdPercent", config.memoryCriticalThresholdPercent, 1, 100);
            config.memoryWaitTimeoutSeconds = intField(root, "crawlMemoryWaitTimeoutSeconds", config.memoryWaitTimeoutSeconds, 1, 7200);
            config.nativeMemoryCleanupEnabled = boolField(root, "crawlNativeMemoryCleanupEnabled", config.nativeMemoryCleanupEnabled);
            config.nativeMemoryCleanupPasses = intField(root, "crawlNativeMemoryCleanupPasses", config.nativeMemoryCleanupPasses, 1, 10);
            config.nativeMemoryWaitThresholdPercent = intField(root, "crawlNativeMemoryWaitThresholdPercent", config.nativeMemoryWaitThresholdPercent, 1, 99);
            config.nativeMemoryCriticalThresholdPercent = intField(root, "crawlNativeMemoryCriticalThresholdPercent", config.nativeMemoryCriticalThresholdPercent, 1, 100);
            config.graphExtractionBatchSize = intField(root, "crawlGraphExtractionBatchSize", config.graphExtractionBatchSize, 1, 128);
            config.backgroundGraphThreads = intField(root, "crawlBackgroundGraphThreads", config.backgroundGraphThreads, 1, 16);
            config.sourceLoadParallelism = intField(root, "crawlSourceLoadParallelism", config.sourceLoadParallelism, 1, 32);
            config.chunkingParallelism = intField(root, "crawlChunkingParallelism", config.chunkingParallelism, 1, 32);
            config.graphExtractionParallelism = intField(root, "crawlGraphExtractionParallelism", config.graphExtractionParallelism, 1, 32);
            config.graphExtractionTargetCharsPerBatch = intField(root, "crawlGraphExtractionTargetCharsPerBatch", config.graphExtractionTargetCharsPerBatch, 1000, 500000);
            config.chunkingTargetCharsPerTask = intField(root, "crawlChunkingTargetCharsPerTask", config.chunkingTargetCharsPerTask, 1000, 2000000);
            config.vectorBatchSize = intField(root, "crawlVectorBatchSize", config.vectorBatchSize, 0, 4096);
            config.postProcessParallel = boolField(root, "crawlPostProcessParallel", config.postProcessParallel);
            config.graphConstructorSkipEmbedding = boolField(root, "crawlGraphConstructorSkipEmbedding", config.graphConstructorSkipEmbedding);
            config.graphConstructorPersistMatrixGraph = boolField(root, "crawlGraphConstructorPersistMatrixGraph", config.graphConstructorPersistMatrixGraph);
            config.retainResultGraph = boolField(root, "crawlRetainResultGraph", config.retainResultGraph);
            config.costSortChunks = boolField(root, "crawlCostSortChunks", config.costSortChunks);
            config.llmCallTimeoutSeconds = intField(root, "crawlLlmCallTimeoutSeconds", config.llmCallTimeoutSeconds, 10, 1800);
            config.graphExtractionBatchTimeoutSeconds = intField(root, "crawlGraphExtractionBatchTimeoutSeconds", config.graphExtractionBatchTimeoutSeconds, 60, 7200);
            config.crawlGraphExtractionMaxCharsPerChunk = intField(root, "crawlGraphExtractionMaxCharsPerChunk", config.crawlGraphExtractionMaxCharsPerChunk, 500, 500_000);
            config.crawlGraphExtractionMaxCharsPerChunkVlm = intField(root, "crawlGraphExtractionMaxCharsPerChunkVlm", config.crawlGraphExtractionMaxCharsPerChunkVlm, 500, 500_000);
            config.graphExtractionChunksPerPrompt = intField(root, "crawlGraphExtractionChunksPerPrompt", config.graphExtractionChunksPerPrompt, 1, 64);
            config.circuitBreakerFailureThreshold = intField(root, "crawlCircuitBreakerFailureThreshold", config.circuitBreakerFailureThreshold, 1, 50);
            config.circuitBreakerCooldownSeconds = intField(root, "crawlCircuitBreakerCooldownSeconds", config.circuitBreakerCooldownSeconds, 5, 600);
            config.cliQuotaWindowMs = longField(root, "crawlCliQuotaWindowMs", config.cliQuotaWindowMs, 60_000L, 604_800_000L);
            config.cliQuotaMinHealthyMs = longField(root, "crawlCliQuotaMinHealthyMs", config.cliQuotaMinHealthyMs, 0L, 3_600_000L);
            config.cliMaxRequestsPerWindow = longField(root, "crawlCliMaxRequestsPerWindow", config.cliMaxRequestsPerWindow, 0L, 1_000_000_000L);
            config.cliMaxTokensPerWindow = longField(root, "crawlCliMaxTokensPerWindow", config.cliMaxTokensPerWindow, 0L, 1_000_000_000_000L);
            if (config.memoryCriticalThresholdPercent < config.memoryWaitThresholdPercent) {
                config.memoryCriticalThresholdPercent = config.memoryWaitThresholdPercent;
            }
            if (config.nativeMemoryCriticalThresholdPercent < config.nativeMemoryWaitThresholdPercent) {
                config.nativeMemoryCriticalThresholdPercent = config.nativeMemoryWaitThresholdPercent;
            }
            return config;
        }

        private static int intField(JsonNode root, String name, int fallback, int min, int max) {
            JsonNode node = root.get(name);
            if (node == null || !node.canConvertToInt()) {
                return fallback;
            }
            return Math.max(min, Math.min(max, node.asInt()));
        }

        private static long longField(JsonNode root, String name, long fallback, long min, long max) {
            JsonNode node = root.get(name);
            if (node == null || !node.canConvertToLong()) {
                return fallback;
            }
            return Math.max(min, Math.min(max, node.asLong()));
        }

        private static boolean boolField(JsonNode root, String name, boolean fallback) {
            JsonNode node = root.get(name);
            return node != null && node.isBoolean() ? node.asBoolean() : fallback;
        }
    }
}
