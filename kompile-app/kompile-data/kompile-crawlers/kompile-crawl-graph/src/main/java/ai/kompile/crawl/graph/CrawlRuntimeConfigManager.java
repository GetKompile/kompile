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
import ai.kompile.core.crawl.graph.CliAgentAvailabilityAdapter;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** The crawl* keys an operator may read/update via REST; everything else in the shared file is left untouched. */
    private static final Set<String> CRAWL_CONFIG_KEYS =
            Collections.unmodifiableSet(new LinkedHashSet<>(CrawlRuntimeConfig.defaults().toMap().keySet()));

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
                KompileHome.configDirectory().toPath().resolve(GRAPH_EXTRACTION_CONFIG_FILENAME);
    }

    /** Test seam: point the manager at a specific config file instead of the real ~/.kompile path. */
    CrawlRuntimeConfigManager(Path configPath) {
        this.graphExtractionConfigPath = configPath;
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

    /** Snapshot the effective crawl runtime knobs (the crawl* keys) for the REST runtime-config endpoint. */
    synchronized Map<String, Object> currentCrawlRuntimeConfig() {
        return refreshRuntimeConfig().toMap();
    }

    /**
     * Merge crawl* runtime-config overrides into the shared config file — preserving every other key
     * (e.g. the graph-extraction schema) — then force an immediate re-read. Unknown / non-crawl keys are
     * ignored so a config push can never clobber the rest of the shared file. Returns the new effective
     * config; a running crawl picks the change up at its next phase boundary.
     */
    synchronized Map<String, Object> updateCrawlRuntimeConfig(Map<String, Object> updates) throws IOException {
        JsonNode existing = Files.exists(graphExtractionConfigPath)
                ? configObjectMapper.readTree(graphExtractionConfigPath.toFile())
                : null;
        ObjectNode root = (existing instanceof ObjectNode on) ? on : configObjectMapper.createObjectNode();
        if (updates != null) {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                if (CRAWL_CONFIG_KEYS.contains(entry.getKey())) {
                    root.set(entry.getKey(), configObjectMapper.valueToTree(entry.getValue()));
                }
            }
        }
        Path parent = graphExtractionConfigPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        configObjectMapper.writerWithDefaultPrettyPrinter().writeValue(graphExtractionConfigPath.toFile(), root);
        // Force this and the next refresh to re-read the file so the change applies immediately.
        graphExtractionConfigLastModified = Long.MIN_VALUE;
        lastConfigRefreshNanos = 0L;
        return refreshRuntimeConfig().toMap();
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
        service.crawlIncrementalByContentHash = config.crawlIncrementalByContentHash;
        service.crawlForceFullRecrawl = config.crawlForceFullRecrawl;
        service.crawlClearGraphBeforeRun = config.crawlClearGraphBeforeRun;
        service.kgeAfterEnrichment = config.crawlKgeAfterEnrichment;
        service.kgeBatchSize = config.crawlKgeBatchSize;

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
            graphExtOrch.graphExtractionRemoteParallelism = config.graphExtractionRemoteParallelism;
            graphExtOrch.graphExtractionMaxItemsPerBatch = config.graphExtractionMaxItemsPerBatch;
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
            graphExtOrch.maxRebatchDepth = config.crawlGraphExtractionMaxRebatchDepth;
            graphExtOrch.wholesaleFailureThreshold = config.crawlGraphExtractionWholesaleFailureThreshold;
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
     * Applies per-request runtime overrides (job parameters) to the service and orchestrator fields.
     * Orchestrator-relevant knobs are propagated to {@code graphExtOrch} too, so a per-job override
     * actually reaches the extraction loop (not just the service).
     *
     * @param overrides    override values from the incoming request (may be null)
     * @param service      service whose volatile fields are updated
     * @param graphExtOrch graph extraction orchestrator (may be null) — receives extraction overrides
     */
    void applyRequestOverrides(UnifiedCrawlRequest.RuntimeConfig overrides,
                               UnifiedCrawlGraphServiceImpl service,
                               GraphExtractionOrchestrator graphExtOrch) {
        if (overrides == null) return;
        if (overrides.getGraphExtractionParallelism() != null) {
            int v = Math.max(1, Math.min(32, overrides.getGraphExtractionParallelism()));
            service.graphExtractionParallelism = v;
            if (graphExtOrch != null) graphExtOrch.graphExtractionParallelism = v;
        }
        if (overrides.getGraphExtractionBatchSize() != null) {
            int v = Math.max(1, Math.min(128, overrides.getGraphExtractionBatchSize()));
            service.graphExtractionBatchSize = v;
            if (graphExtOrch != null) graphExtOrch.graphExtractionBatchSize = v;
        }
        if (overrides.getGraphExtractionTargetCharsPerBatch() != null) {
            int v = Math.max(1000, Math.min(500000, overrides.getGraphExtractionTargetCharsPerBatch()));
            service.graphExtractionTargetCharsPerBatch = v;
            if (graphExtOrch != null) graphExtOrch.graphExtractionTargetCharsPerBatch = v;
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
            if (graphExtOrch != null) graphExtOrch.costSortChunks = overrides.getCostSortChunks();
        }
        if (overrides.getLlmCallTimeoutSeconds() != null) {
            service.llmCallTimeoutSeconds =
                    Math.max(10, Math.min(1800, overrides.getLlmCallTimeoutSeconds()));
        }
        if (overrides.getGraphExtractionBatchTimeoutSeconds() != null) {
            int v = Math.max(60, Math.min(7200, overrides.getGraphExtractionBatchTimeoutSeconds()));
            service.graphExtractionBatchTimeoutSeconds = v;
            if (graphExtOrch != null) graphExtOrch.graphExtractionBatchTimeoutSeconds = v;
        }
        if (overrides.getGraphExtractionRemoteParallelism() != null && graphExtOrch != null) {
            graphExtOrch.graphExtractionRemoteParallelism =
                    Math.max(1, Math.min(32, overrides.getGraphExtractionRemoteParallelism()));
        }
        if (overrides.getGraphExtractionMaxItemsPerBatch() != null && graphExtOrch != null) {
            graphExtOrch.graphExtractionMaxItemsPerBatch =
                    Math.max(1, Math.min(4096, overrides.getGraphExtractionMaxItemsPerBatch()));
        }
        if (overrides.getIncrementalByContentHash() != null) {
            service.crawlIncrementalByContentHash = overrides.getIncrementalByContentHash();
        }
        if (overrides.getForceFullRecrawl() != null) {
            service.crawlForceFullRecrawl = overrides.getForceFullRecrawl();
        }
        // [FIX-4] Per-request opt-in to clear the graph before running
        if (overrides.getClearGraphBeforeRun() != null) {
            service.crawlClearGraphBeforeRun = overrides.getClearGraphBeforeRun();
        }
    }

    /**
    /**
     * Derive the per-call char budget from the primary extraction model's context window and apply
     * it as the floor of {@code graphExtractionTargetCharsPerBatch} on the orchestrator.
     *
     * <p>Only fires when {@code graphConfig.getExtractionContextBudgetFraction()} is non-null and
     * {@code > 0}. The computed chars are {@code max(existing, budgetChars)} so the budget is only
     * ever raised, never reduced below the operator-configured static value.</p>
     *
     * @param graphConfig   extraction config from the request (may be null)
     * @param llmDispatcher dispatcher holding the {@link CliAgentAvailabilityAdapter}
     * @param graphExtOrch  orchestrator whose {@code graphExtractionTargetCharsPerBatch} is updated
     * @return a human-readable detail string when the budget was updated, or {@code null} if skipped
     */
    String applyContextBudget(GraphExtractionConfig graphConfig,
                              CrawlLlmDispatcher llmDispatcher,
                              GraphExtractionOrchestrator graphExtOrch) {
        if (llmDispatcher == null || llmDispatcher.cliAgentAvailability == null) return null;
        if (graphExtOrch == null) return null;

        double fraction = (graphConfig != null && graphConfig.getExtractionContextBudgetFraction() != null)
                ? graphConfig.getExtractionContextBudgetFraction()
                : 0.5; // default
        if (fraction <= 0.0) return null; // disabled

        double charsPerToken = (graphConfig != null && graphConfig.getExtractionCharsPerToken() != null)
                ? graphConfig.getExtractionCharsPerToken()
                : 3.5; // default

        int budgetChars = llmDispatcher.cliAgentAvailability.contextBudgetChars(fraction, charsPerToken);
        if (budgetChars <= 0) return null;

        int before = graphExtOrch.graphExtractionTargetCharsPerBatch;
        int after  = Math.max(before, budgetChars);
        if (after != before) {
            graphExtOrch.graphExtractionTargetCharsPerBatch = after;
            log.info("BATCH_BUDGET: raised graphExtractionTargetCharsPerBatch {}→{} (fraction={}, charsPerToken={})",
                    before, after, fraction, charsPerToken);
        }
        return "budgetChars=" + budgetChars + " fraction=" + fraction
                + " charsPerToken=" + charsPerToken + " before=" + before + " after=" + after;
    }

    /**
     * Push the per-crawl extraction model policy from a {@link GraphExtractionConfig} to the
     * {@link CliAgentAvailabilityAdapter}, which delegates to {@code CliAgentModelService}.
     *
     * @param graphConfig   extraction config from the incoming request (may be null)
     * @param llmDispatcher dispatcher that holds the wired {@link CliAgentAvailabilityAdapter}
     */
    void applyExtractionPolicy(GraphExtractionConfig graphConfig, CrawlLlmDispatcher llmDispatcher) {
        if (llmDispatcher == null || llmDispatcher.cliAgentAvailability == null) return;
        List<String> providerAllow  = graphConfig != null ? graphConfig.getExtractionModelProviderAllow()  : null;
        List<String> excludeMarkers = graphConfig != null ? graphConfig.getExtractionModelExcludeMarkers() : null;
        List<String> modelAllow     = graphConfig != null ? graphConfig.getExtractionModelAllow()          : null;
        llmDispatcher.cliAgentAvailability.setActiveExtractionPolicy(providerAllow, excludeMarkers, modelAllow);
        log.info("Applied extraction policy from GraphExtractionConfig: providerAllow={}, excludeMarkers={}, modelAllow={}",
                providerAllow, excludeMarkers, modelAllow);

        // Per-job/per-project fallback-executor overrides (paid-tier guardrails + timeout). Null
        // fields inherit the global model-fallback-config.json default. Pushed every crawl so the
        // override is always fresh from THIS job's config (no stale carry-over between crawls).
        Boolean paidFallbackEnabled = graphConfig != null ? graphConfig.getExtractionPaidFallbackEnabled() : null;
        Integer maxPaidCallsPerCrawl = graphConfig != null ? graphConfig.getExtractionMaxPaidCallsPerCrawl() : null;
        Integer perCallTimeoutSeconds = graphConfig != null ? graphConfig.getExtractionPerCallTimeoutSeconds() : null;
        llmDispatcher.cliAgentAvailability.setActiveExtractionFallbackOverride(
                paidFallbackEnabled, maxPaidCallsPerCrawl, perCallTimeoutSeconds);
        log.info("Applied extraction fallback override from GraphExtractionConfig: paidFallbackEnabled={}, maxPaidCallsPerCrawl={}, perCallTimeoutSeconds={}",
                paidFallbackEnabled, maxPaidCallsPerCrawl, perCallTimeoutSeconds);
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
        // Remote (CLI/API) extraction: few fat concurrent calls beat many (each remote call has a large
        // fixed cost). Local models use graphExtractionParallelism. Configurable per project/global
        // (.kompile/data/config) and per job (UnifiedCrawlRequest.RuntimeConfig).
        int graphExtractionRemoteParallelism = 2;
        // Safety cap on chunks per batch — the model-derived char budget is the primary control.
        int graphExtractionMaxItemsPerBatch = 64;
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
        // Per-chunk truncation ceiling for the inline-LLM path (extractGraphViaLlmDocument /
        // extractGraphViaLlmChunkGroup). Raised from 12 000 / 16 000 to 50 000 / 60 000 chars so
        // large-context CLI agents (e.g. opencode-cli / DeepSeek V4 at ~1 M tokens) can consume
        // full document sections without being hard-capped at ~3 output-token-budget's worth of text.
        // The old 12 000-char default was the primary cause of ~11.8 k-char/call on deepseek-v4:
        // the inline path truncated each chunk to 12 000 chars and the charBudget allowed only a
        // few chunks before the chunksPerPrompt or charBudget limit triggered a flush.
        // Projects that deliberately want small chunks for local/small-context models can override
        // crawlGraphExtractionMaxCharsPerChunk back to 12 000 in their project config.
        int crawlGraphExtractionMaxCharsPerChunk = 50_000;
        int crawlGraphExtractionMaxCharsPerChunkVlm = 60_000;
        // Number of chunks to group into a single LLM prompt. 1 = one-call-per-chunk (default).
        int graphExtractionChunksPerPrompt = 1;
        int circuitBreakerFailureThreshold = 5;
        int circuitBreakerCooldownSeconds = 60;
        /**
         * When true (default) each crawl skips files whose SHA-256 content hash
         * matches the value recorded on the previous crawl — only new/changed files
         * go through CONVERTING → CHUNKING → GRAPH_EXTRACTION → VECTOR_INDEXING.
         * Global steps (ENTITY_RESOLUTION, EDGE_COMPUTATION, ENRICHMENT) still run
         * over the full current graph regardless of this flag.
         */
        boolean crawlIncrementalByContentHash = true;
        /**
         * When true, bypass the content-hash skip for a single crawl run — every file
         * is (re-)processed and the hash store is updated.  Intended for forced
         * full re-crawls (e.g. after a schema change).  Does NOT clear the hash store.
         */
        boolean crawlForceFullRecrawl = false;
        /**
         * [FIX-4] When true (explicit opt-in only), clear the fact sheet's graph at the very
         * start of the crawl (before LOADING) so the new crawl starts from a clean slate.
         * Default is FALSE — re-runs MERGE/UPDATE the existing graph rather than wiping it,
         * preserving accumulated enrichment, confidence, and opinion data.
         *
         * <p>This is a destructive operation; use it only when you need to completely replace
         * the graph (e.g. after a schema change that makes old nodes/edges incompatible).</p>
         */
        boolean crawlClearGraphBeforeRun = false;
        // CLI-agent quota ledger (cross-job; both time-window and request/token caps)
        long cliQuotaWindowMs = 18_000_000L;   // 5 hours rolling exhaustion window
        long cliQuotaMinHealthyMs = 60_000L;   // hysteresis gap before backoff resets
        long cliMaxRequestsPerWindow = 0;      // 0 = no global request cap
        long cliMaxTokensPerWindow = 0;        // 0 = no global token cap
        /**
         * Maximum split depth for rebatch-on-failure in the inline-LLM extraction path.
         * When a multi-chunk group fails (timeout/empty/parse-error), it is split in half and
         * each half retried; halving repeats up to this many levels before a chunk is declared
         * individually failed (size-1 is the terminal base case). Depth=4 → max 2^4=16 splits,
         * so a failing group of 32 will recurse: 32→16→8→4→2→1, rescuing chunks that succeed
         * in smaller context. Set to 0 to disable rebatching (pre-fix behaviour).
         * Default: 4.
         */
        int crawlGraphExtractionMaxRebatchDepth = 4;
        /**
         * Wholesale-failure threshold for the finish-early guard in graph extraction.
         * When (failed_chunks / total_chunks) ≥ this fraction AND entities extracted == 0,
         * the crawl skips downstream semantic steps (ENTITY_RESOLUTION, EDGE_COMPUTATION,
         * ENRICHMENT) that require semantic entities to be meaningful, marks the job
         * FAILED/DEGRADED, and archives the failed chunks as a resumable GRAPH_EXTRACTION step.
         * Range 0.0 (always skip downstream on any failure) to 1.0 (only skip when ALL chunks fail).
         * Default: 1.0 — only skip when the extraction is a complete wholesale failure.
         */
        double crawlGraphExtractionWholesaleFailureThreshold = 1.0;
        /**
         * When true (default), automatically trigger async KGE embedding training after the
         * ENRICHMENT step completes successfully. The training runs in a daemon background
         * thread so it does not block the crawl pipeline. Set to false to skip KGE training
         * after enrichment (e.g. for test crawls or when KGE is run on demand instead).
         */
        boolean crawlKgeAfterEnrichment = true;
        /**
         * Batch size for KGE (Knowledge Graph Embedding) training (triples per mini-batch).
         * Overrides {@code KGEmbeddingConfig.TRANSE_DEFAULTS.batchSize()} when positive.
         * Default: 256. Reduce to 64–128 on memory-constrained hosts; increase to 512–2048 on
         * hosts with ample RAM/VRAM and large graphs.
         */
        int crawlKgeBatchSize = 256;
        /**
         * When true (default), heavy in-memory model operations (KGE training, embedding steps)
         * are serialized through a single-permit semaphore so at most one runs at a time.
         * Passthrough to {@code ResourceSchedulerConfig.serializedHeavyOps}; controlling it here
         * lets a per-project config override the global setting without editing
         * {@code resource-scheduler-config.json}.
         * Ignored when no {@code HeavyMemoryCoordinator} bean is wired (older contexts).
         */
        boolean crawlSerializedHeavyOps = true;
        /**
         * Per-project absolute RAM floor (MB) passed through to the resource governor's OOM check.
         * 0 means "use the global {@code governorRamFloorMb} from resource-scheduler-config.json".
         * When positive, a DECISION event is published if the host drops below this floor and
         * the KGE/embedding launch is deferred.
         * Default: 0 (use global).
         */
        long crawlGovernorRamFloorMb = 0;
        /**
         * When true, the local-serving extraction tier (SameDiff-LLM or any bean with
         * {@code getId()=="local-serving"}) is used side-by-side with the remote tier.
         * Batches whose cost (chars) is {@code <= crawlLocalCostThresholdChars} are routed
         * to the local tier; larger batches are routed to the remote tier.
         * Default is false (all batches go to the existing remote path, identical behaviour).
         */
        boolean crawlLocalTierEnabled = false;
        /**
         * Cost ceiling (characters) for routing a batch to the local tier.
         * A batch with {@code cost() <= threshold} goes LOCAL; {@code cost() > threshold}
         * goes REMOTE. Only applied when {@code crawlLocalTierEnabled=true} and the local
         * tier bean is present and {@code isAvailable()==true}.
         * Default: 24 000 chars.
         */
        long crawlLocalCostThresholdChars = 24_000L;
        /**
         * Wave parallelism to use when the local tier is active. Overrides the remote-parallelism
         * cap so local and remote batches can run concurrently without being bottlenecked by the
         * remote-parallelism ceiling (typically 2). Only applied when
         * {@code crawlLocalTierEnabled=true} and the local tier is present/available.
         * Default: 4 (same as graphExtractionParallelism default).
         */
        int crawlSideBySideParallelism = 4;

        /** Serialize the effective values keyed by the same crawl* names {@link #from} reads. */
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("crawlMaxConcurrentJobs", maxConcurrentJobs);
            m.put("crawlQueueCapacity", queueCapacity);
            m.put("crawlMemoryWaitThresholdPercent", memoryWaitThresholdPercent);
            m.put("crawlMemoryCriticalThresholdPercent", memoryCriticalThresholdPercent);
            m.put("crawlMemoryWaitTimeoutSeconds", memoryWaitTimeoutSeconds);
            m.put("crawlNativeMemoryCleanupEnabled", nativeMemoryCleanupEnabled);
            m.put("crawlNativeMemoryCleanupPasses", nativeMemoryCleanupPasses);
            m.put("crawlNativeMemoryWaitThresholdPercent", nativeMemoryWaitThresholdPercent);
            m.put("crawlNativeMemoryCriticalThresholdPercent", nativeMemoryCriticalThresholdPercent);
            m.put("crawlGraphExtractionBatchSize", graphExtractionBatchSize);
            m.put("crawlBackgroundGraphThreads", backgroundGraphThreads);
            m.put("crawlSourceLoadParallelism", sourceLoadParallelism);
            m.put("crawlChunkingParallelism", chunkingParallelism);
            m.put("crawlGraphExtractionParallelism", graphExtractionParallelism);
            m.put("crawlGraphExtractionRemoteParallelism", graphExtractionRemoteParallelism);
            m.put("crawlGraphExtractionMaxItemsPerBatch", graphExtractionMaxItemsPerBatch);
            m.put("crawlGraphExtractionTargetCharsPerBatch", graphExtractionTargetCharsPerBatch);
            m.put("crawlChunkingTargetCharsPerTask", chunkingTargetCharsPerTask);
            m.put("crawlVectorBatchSize", vectorBatchSize);
            m.put("crawlPostProcessParallel", postProcessParallel);
            m.put("crawlGraphConstructorSkipEmbedding", graphConstructorSkipEmbedding);
            m.put("crawlGraphConstructorPersistMatrixGraph", graphConstructorPersistMatrixGraph);
            m.put("crawlRetainResultGraph", retainResultGraph);
            m.put("crawlCostSortChunks", costSortChunks);
            m.put("crawlLlmCallTimeoutSeconds", llmCallTimeoutSeconds);
            m.put("crawlGraphExtractionBatchTimeoutSeconds", graphExtractionBatchTimeoutSeconds);
            m.put("crawlGraphExtractionMaxCharsPerChunk", crawlGraphExtractionMaxCharsPerChunk);
            m.put("crawlGraphExtractionMaxCharsPerChunkVlm", crawlGraphExtractionMaxCharsPerChunkVlm);
            m.put("crawlGraphExtractionChunksPerPrompt", graphExtractionChunksPerPrompt);
            m.put("crawlCircuitBreakerFailureThreshold", circuitBreakerFailureThreshold);
            m.put("crawlCircuitBreakerCooldownSeconds", circuitBreakerCooldownSeconds);
            m.put("crawlCliQuotaWindowMs", cliQuotaWindowMs);
            m.put("crawlCliQuotaMinHealthyMs", cliQuotaMinHealthyMs);
            m.put("crawlCliMaxRequestsPerWindow", cliMaxRequestsPerWindow);
            m.put("crawlCliMaxTokensPerWindow", cliMaxTokensPerWindow);
            m.put("crawlIncrementalByContentHash", crawlIncrementalByContentHash);
            m.put("crawlForceFullRecrawl", crawlForceFullRecrawl);
            m.put("crawlClearGraphBeforeRun", crawlClearGraphBeforeRun);
            m.put("crawlGraphExtractionMaxRebatchDepth", crawlGraphExtractionMaxRebatchDepth);
            m.put("crawlGraphExtractionWholesaleFailureThreshold", crawlGraphExtractionWholesaleFailureThreshold);
            m.put("crawlKgeAfterEnrichment", crawlKgeAfterEnrichment);
            m.put("crawlKgeBatchSize", crawlKgeBatchSize);
            m.put("crawlSerializedHeavyOps", crawlSerializedHeavyOps);
            m.put("crawlGovernorRamFloorMb", crawlGovernorRamFloorMb);
            return m;
        }

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
            config.graphExtractionRemoteParallelism = intField(root, "crawlGraphExtractionRemoteParallelism", config.graphExtractionRemoteParallelism, 1, 32);
            config.graphExtractionMaxItemsPerBatch = intField(root, "crawlGraphExtractionMaxItemsPerBatch", config.graphExtractionMaxItemsPerBatch, 1, 4096);
            // Upper bound raised from 500 000 → 4 000 000 chars to allow large-context models
            // (e.g. DeepSeek V4 / opencode-cli at ~1 M tokens ≈ 4 M chars) to send batches that
            // actually approach their context window. The orchestrator's AIMD sizer and the
            // model-derived maxInputChars() cap further constrain the actual per-call budget.
            config.graphExtractionTargetCharsPerBatch = intField(root, "crawlGraphExtractionTargetCharsPerBatch", config.graphExtractionTargetCharsPerBatch, 1000, 4_000_000);
            config.chunkingTargetCharsPerTask = intField(root, "crawlChunkingTargetCharsPerTask", config.chunkingTargetCharsPerTask, 1000, 2000000);
            config.vectorBatchSize = intField(root, "crawlVectorBatchSize", config.vectorBatchSize, 0, 4096);
            config.postProcessParallel = boolField(root, "crawlPostProcessParallel", config.postProcessParallel);
            config.graphConstructorSkipEmbedding = boolField(root, "crawlGraphConstructorSkipEmbedding", config.graphConstructorSkipEmbedding);
            config.graphConstructorPersistMatrixGraph = boolField(root, "crawlGraphConstructorPersistMatrixGraph", config.graphConstructorPersistMatrixGraph);
            config.retainResultGraph = boolField(root, "crawlRetainResultGraph", config.retainResultGraph);
            config.costSortChunks = boolField(root, "crawlCostSortChunks", config.costSortChunks);
            config.llmCallTimeoutSeconds = intField(root, "crawlLlmCallTimeoutSeconds", config.llmCallTimeoutSeconds, 10, 1800);
            config.graphExtractionBatchTimeoutSeconds = intField(root, "crawlGraphExtractionBatchTimeoutSeconds", config.graphExtractionBatchTimeoutSeconds, 60, 7200);
            // Upper bound raised from 500 000 → 2 000 000 chars so large-context CLI agents
            // (deepseek-v4 / opencode-cli with ~1 M-token window) can receive full document sections
            // without truncation at 12 000 chars (the old hard-coded default).
            config.crawlGraphExtractionMaxCharsPerChunk = intField(root, "crawlGraphExtractionMaxCharsPerChunk", config.crawlGraphExtractionMaxCharsPerChunk, 500, 2_000_000);
            config.crawlGraphExtractionMaxCharsPerChunkVlm = intField(root, "crawlGraphExtractionMaxCharsPerChunkVlm", config.crawlGraphExtractionMaxCharsPerChunkVlm, 500, 2_000_000);
            config.graphExtractionChunksPerPrompt = intField(root, "crawlGraphExtractionChunksPerPrompt", config.graphExtractionChunksPerPrompt, 1, 64);
            config.circuitBreakerFailureThreshold = intField(root, "crawlCircuitBreakerFailureThreshold", config.circuitBreakerFailureThreshold, 1, 50);
            config.circuitBreakerCooldownSeconds = intField(root, "crawlCircuitBreakerCooldownSeconds", config.circuitBreakerCooldownSeconds, 5, 600);
            config.cliQuotaWindowMs = longField(root, "crawlCliQuotaWindowMs", config.cliQuotaWindowMs, 60_000L, 604_800_000L);
            config.cliQuotaMinHealthyMs = longField(root, "crawlCliQuotaMinHealthyMs", config.cliQuotaMinHealthyMs, 0L, 3_600_000L);
            config.cliMaxRequestsPerWindow = longField(root, "crawlCliMaxRequestsPerWindow", config.cliMaxRequestsPerWindow, 0L, 1_000_000_000L);
            config.cliMaxTokensPerWindow = longField(root, "crawlCliMaxTokensPerWindow", config.cliMaxTokensPerWindow, 0L, 1_000_000_000_000L);
            config.crawlIncrementalByContentHash = boolField(root, "crawlIncrementalByContentHash", config.crawlIncrementalByContentHash);
            config.crawlForceFullRecrawl = boolField(root, "crawlForceFullRecrawl", config.crawlForceFullRecrawl);
            config.crawlClearGraphBeforeRun = boolField(root, "crawlClearGraphBeforeRun", config.crawlClearGraphBeforeRun);
            config.crawlGraphExtractionMaxRebatchDepth = intField(root, "crawlGraphExtractionMaxRebatchDepth", config.crawlGraphExtractionMaxRebatchDepth, 0, 8);
            {
                JsonNode n = root.get("crawlGraphExtractionWholesaleFailureThreshold");
                if (n != null && n.isNumber()) {
                    config.crawlGraphExtractionWholesaleFailureThreshold = Math.max(0.0, Math.min(1.0, n.asDouble()));
                }
            }
            config.crawlKgeAfterEnrichment = boolField(root, "crawlKgeAfterEnrichment", config.crawlKgeAfterEnrichment);
            config.crawlKgeBatchSize = intField(root, "crawlKgeBatchSize", config.crawlKgeBatchSize, 8, 65536);
            config.crawlSerializedHeavyOps = boolField(root, "crawlSerializedHeavyOps", config.crawlSerializedHeavyOps);
            config.crawlGovernorRamFloorMb = longField(root, "crawlGovernorRamFloorMb", config.crawlGovernorRamFloorMb, 0L, 1_048_576L);
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
