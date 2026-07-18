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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.app.subprocess.SubprocessLogBus;
import ai.kompile.app.subprocess.SubprocessLogEvent;
import ai.kompile.app.subprocess.SubprocessLogSink;
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.crawler.*;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.core.kgembedding.KgeTrainingExecutor;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.crawl.graph.preprocessing.PreprocessingPipelineRunner;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.core.graphrag.conformance.OntologyAutoProvisioner;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Implementation of {@link UnifiedCrawlService} that orchestrates
 * multi-source crawling with automatic graph extraction and vector indexing.
 *
 * <p>Pipeline flow per source:</p>
 * <ol>
 *   <li>Crawl/load documents from each source (using CrawlerService or DocumentLoader)</li>
 *   <li>Chunk documents for processing</li>
 *   <li>Extract graph entities and relationships from each chunk via LLM</li>
 *   <li>Merge entities across sources (entity resolution)</li>
 *   <li>Index chunks to vector store</li>
 *   <li>Assemble final unified graph</li>
 * </ol>
 *
 * <p>Responsibilities are delegated to focused helper components:</p>
 * <ul>
 *   <li>{@link CrawlRuntimeConfigManager} -- hot-reloadable JSON config (5 s TTL)</li>
 *   <li>{@link CrawlSourceLoadingService} -- source dispatch, crawler/loader routing</li>
 *   <li>{@link CrawlDocumentChunkingService} -- content-type-aware chunking</li>
 *   <li>{@link CrawlBatchPlanner} -- cost-balanced batch planning</li>
 *   <li>{@link CrawlTextConversionService} -- text normalisation, background-graph copies</li>
 *   <li>{@link GraphExtractionOrchestrator} -- LLM / GraphConstructor extraction</li>
 *   <li>{@link VectorIndexingHelper} -- embedding + vector-store indexing</li>
 *   <li>{@link CrawlMemoryMonitor} -- heap / native memory pressure detection</li>
 *   <li>{@link PipelineStepTracker} -- pipeline step state machine</li>
 *   <li>{@link CrawlDocumentTracker} -- per-document progress events</li>
 *   <li>{@link CrawlLlmDispatcher} -- capacity-aware LLM dispatch</li>
 * </ul>
 */
@Service
public class UnifiedCrawlGraphServiceImpl implements UnifiedCrawlService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedCrawlGraphServiceImpl.class);

    /** Shared, project-standard JSON mapper (archived step configs + request preprocessing config). */
    private static final ObjectMapper JSON_MAPPER = JsonUtils.standardMapper();

    private static final AtomicInteger t_counter_chunk = new AtomicInteger(0);
    private static final AtomicInteger t_counter_extract = new AtomicInteger(0);

    private final ConcurrentHashMap<String, UnifiedCrawlJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobExecution> jobExecutions = new ConcurrentHashMap<>();

    private enum JobExecutionState {
        QUEUED, RUNNING, CANCELLED_BEFORE_START, FINISHED
    }

    private static final class JobExecution {
        private final AtomicReference<JobExecutionState> state =
                new AtomicReference<>(JobExecutionState.QUEUED);
        private volatile FutureTask<Void> task;
    }

    // Token trackers delegated to CrawlLlmDispatcher
    private final ConcurrentHashMap<String, Long> queuedSequences = new ConcurrentHashMap<>();
    private final Set<String> runningJobIds = ConcurrentHashMap.newKeySet();
    /** Maps scheduler-generated IDs (e.g. "crawl-55ef8175") to internal job UUIDs. */
    private final ConcurrentHashMap<String, String> jobIdAliases = new ConcurrentHashMap<>();
    private final AtomicLong submitSequence = new AtomicLong(0L);
    private volatile ThreadPoolExecutor executor;
    private int executorQueueCapacity = -1;
    private final AtomicInteger t_counter_source = new AtomicInteger(0);

    // Shared thread pools -- reused across jobs to avoid per-job allocation/teardown overhead.
    // Sized once in initializeExecutor(), torn down in shutdownExecutor().
    private volatile ExecutorService sharedChunkingPool;
    private volatile ExecutorService sharedGraphExtractionPool;
    private volatile ExecutorService sharedSourceLoadPool;
    private volatile ExecutorService sharedVectorIndexPool;

    // Configuration fields -- written by CrawlRuntimeConfigManager.applyRuntimeConfig.
    // Package-private so the config manager can update them directly.
    volatile int maxConcurrentJobs = 1;
    volatile int queueCapacity = 25;
    volatile int memoryWaitThresholdPercent = 82;
    volatile int memoryCriticalThresholdPercent = 90;
    volatile int memoryWaitTimeoutSeconds = 300;
    volatile boolean nativeMemoryCleanupEnabled = true;
    volatile int nativeMemoryCleanupPasses = 3;
    volatile int nativeMemoryWaitThresholdPercent = 82;
    volatile int nativeMemoryCriticalThresholdPercent = 90;
    volatile int graphExtractionBatchSize = 10;
    volatile int backgroundGraphThreads = 2;
    volatile int structuredGraphPersistenceThreads = 4;
    volatile int sourceLoadParallelism = 2;
    volatile int chunkingParallelism = 2;
    volatile int graphExtractionParallelism = 4;
    volatile int graphExtractionTargetCharsPerBatch = 48_000;
    volatile int chunkingTargetCharsPerTask = 200_000;
    volatile int configuredVectorBatchSize = 0;
    volatile boolean postProcessParallel = false;
    volatile boolean graphConstructorSkipEmbedding = true;
    volatile boolean graphConstructorPersistMatrixGraph = false;
    volatile boolean retainResultGraph = false;
    volatile boolean costSortChunks = true;
    volatile int llmCallTimeoutSeconds = 300;
    volatile int graphExtractionBatchTimeoutSeconds = 2700;
    // Per-chunk truncation ceiling for inline LLM extraction. Raised from 12 000 / 16 000 chars
    // to 50 000 / 60 000 so large-context CLI agents (e.g. opencode-cli / DeepSeek V4 ~1 M tokens)
    // are not capped at the old ~11.8 k-char/call average caused by the 12 000 limit.
    // Synced from graph-extraction-config.json / project config at crawl start.
    volatile int crawlGraphExtractionMaxCharsPerChunk = 50_000;
    volatile int crawlGraphExtractionMaxCharsPerChunkVlm = 60_000;
    // Chunks-per-prompt grouping (1 = legacy one-LLM-call-per-chunk, bit-for-bit identical).
    volatile int graphExtractionChunksPerPrompt = 1;
    /**
     * When true, skip per-file pipeline steps (CONVERTING/CHUNKING/GRAPH_EXTRACTION/
     * VECTOR_INDEXING) for files whose SHA-256 content hash matches the stored hash
     * from the previous crawl. Global steps (ENTITY_RESOLUTION, EDGE_COMPUTATION,
     * ENRICHMENT) still run over the full current graph. Default: ON.
     */
    volatile boolean crawlIncrementalByContentHash = true;
    /**
     * When true, bypass the content-hash skip for this crawl run — every file is
     * (re-)processed and the hash store is updated.  Intended for forced full re-crawls.
     */
    volatile boolean crawlForceFullRecrawl = false;
    /**
     * [FIX-4] When true (explicit opt-in only), clear the fact sheet's graph at the very
     * start of the crawl before LOADING, so the new crawl starts from a clean slate.
     * Default is FALSE — re-runs MERGE/UPDATE the graph, preserving enrichment/confidence.
     */
    volatile boolean crawlClearGraphBeforeRun = false;
    /**
     * When true (default), automatically trigger async KGE embedding training after the
     * ENRICHMENT step completes successfully. Controlled by {@code crawlKgeAfterEnrichment}
     * in {@code graph-extraction-config.json}. Set false to skip auto-KGE (on-demand only).
     */
    volatile boolean kgeAfterEnrichment = true;
    /**
     * Batch size (triples per mini-batch) for KGE training. Overrides TRANSE_DEFAULTS when > 0.
     * Controlled by {@code crawlKgeBatchSize} in {@code graph-extraction-config.json}.
     * Default: 256 (smaller than TRANSE_DEFAULTS=1024 to reduce peak memory).
     */
    volatile int kgeBatchSize = 256;

    // Optional Spring dependencies

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private GraphExtractionCheckpointStore graphExtractionCheckpointStore;

    /** Optional full-application boundary that resolves every crawl to a concrete fact sheet. */
    @Autowired(required = false)
    private CrawlFactSheetScopeResolver factSheetScopeResolver;

    /** Optional app-main hook that derives/binds crawl schema and materializes type hierarchy metadata. */
    @Autowired(required = false)
    private OntologyAutoProvisioner ontologyAutoProvisioner;

    /**
     * Optional matrix graph store — in subprocess mode this is {@code SubprocessMatrixGraphStore}
     * (HTTP delegate); in in-process mode it is {@code VectorStoreMatrixGraphStore} sharing the
     * same {@code AnseriniVectorStoreImpl} bean as {@link #vectorStore}.  Used solely to
     * propagate the deferred-embedding flag to the subprocess's vector store so graph-node
     * adds during GRAPH_PREP do not embed synchronously there.
     */
    @Autowired(required = false)
    private MatrixGraphStore matrixGraphStore;

    @Autowired(required = false)
    private CrossDocumentRelationCallback crossDocumentRelationCallback;

    @Autowired(required = false)
    private List<ai.kompile.core.graphrag.DocumentGraphExtractor> documentGraphExtractors;

    @Autowired(required = false)
    private ai.kompile.knowledgegraph.service.GraphEdgeComputationService graphEdgeComputationService;

    @Autowired(required = false)
    private GraphCompactionService graphCompactionService;

    @Autowired(required = false)
    private ai.kompile.knowledgegraph.resolution.IdentityGraphService identityGraphService;

    @Autowired(required = false)
    private CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * Shared bus carrying live log + lifecycle events from every managed subprocess
     * (embedding, learning, …). The crawl registers a per-job {@link SubprocessLogSink}
     * while it runs so each subprocess's own output streams to the crawl UI in real time.
     */
    @Autowired(required = false)
    private SubprocessLogBus subprocessLogBus;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

    /**
     * Optional resource governor adapter (app-main). Injected {@code required=false} so the crawl
     * module compiles without app-main. Used to check the OOM floor before starting KGE training
     * and to publish DECISION events when the governor defers a heavy op.
     */
    @Autowired(required = false)
    private ResourceGovernorAdapter resourceGovernorAdapter;

    /**
     * Optional heavy-memory serialization gate (app-main). Injected {@code required=false} so the
     * crawl module compiles without app-main. Not used directly in the crawl pipeline (KGE acquires
     * it internally via {@code KGEmbeddingJobService}) but consulted here to publish a DECISION
     * event when the KGE start is delayed by the gate.
     */
    @Autowired(required = false)
    private HeavyMemoryCoordinator heavyMemoryCoordinator;

    // Required Spring dependencies

    @Autowired
    private CrawlMemoryMonitor memoryMonitor;

    @Autowired
    private PipelineStepTracker pipelineStepTracker;

    @Autowired
    private CrawlLlmDispatcher llmDispatcher;

    @Autowired
    private CrawlDocumentTracker documentTracker;

    @Autowired
    private GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    private ContentTypeRouter contentTypeRouter;

    @Autowired
    private EmailGraphExtractor emailGraphExtractor;

    @Autowired
    private RuleBasedDocumentGraphExtractor ruleBasedDocumentGraphExtractor;

    @Autowired
    private VectorIndexingHelper vectorIndexingHelper;

    @Autowired
    private GraphExtractionOrchestrator graphExtractionOrchestrator;

    // Extracted helper components

    @Autowired
    private CrawlRuntimeConfigManager runtimeConfigManager;

    @Autowired
    private CrawlSourceLoadingService sourceLoadingService;

    @Autowired
    private CrawlDocumentChunkingService documentChunkingService;

    @Autowired
    private CrawlTextConversionService textConversionService;

    // Durable step-archive persistence (impl lives in app-main). Optional: when absent, archiving is a
    // no-op and the pipeline behaves exactly as before.
    @Autowired(required = false)
    private CrawlStepArchiveService crawlStepArchiveService;

    // Optional document preprocessing pipeline (PII redaction, dedup, unicode norm, ...). Opt-in.
    @Autowired(required = false)
    private PreprocessingPipelineRunner preprocessingPipelineRunner;

    // Optional graph hydration pipeline (ENRICHMENT step: MAP derivation + prune/compact + health).
    @Autowired(required = false)
    private GraphHydrationOrchestrator graphHydrationOrchestrator;

    // Optional KGE embedding training (auto-triggered after ENRICHMENT when kgeAfterEnrichment=true).
    @Autowired(required = false)
    private ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService kgeEmbeddingJobService;

    /** Refreshes the generic reasoning graph after KGE; does not require MEBN to be enabled. */
    @Autowired(required = false)
    private MebnTheoryRegistrationService reasoningGraphRegistrationService;

    // CrawlBatchPlanner is used by CrawlDocumentChunkingService; no direct use here.

    @PostConstruct
    public synchronized void initializeExecutor() {
        CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
        executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                llmDispatcher, executor, executorQueueCapacity);
        pipelineStepTracker.setGraphConstructorPresent(hasGraphConstructor());
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        int threads = Math.max(1, maxConcurrentJobs);
        int capacity = Math.max(1, queueCapacity);
        executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("unified-crawl-" + t.getId());
                    t.setUncaughtExceptionHandler((thread, ex) ->
                            log.error("Uncaught error in thread {}: {}", thread.getName(), ex.getMessage(), ex));
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executorQueueCapacity = capacity;

        if (sharedChunkingPool == null || sharedChunkingPool.isShutdown()) {
            int chunkThreads = Math.max(1, chunkingParallelism);
            sharedChunkingPool = Executors.newFixedThreadPool(chunkThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-chunk-shared-" + t_counter_chunk.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        if (sharedGraphExtractionPool == null || sharedGraphExtractionPool.isShutdown()) {
            int extractThreads = Math.max(1, graphExtractionParallelism);
            sharedGraphExtractionPool = Executors.newFixedThreadPool(extractThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-extract-shared-" + t_counter_extract.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        if (sharedSourceLoadPool == null || sharedSourceLoadPool.isShutdown()) {
            int sourceThreads = Math.max(1, sourceLoadParallelism);
            sharedSourceLoadPool = Executors.newFixedThreadPool(sourceThreads, r -> {
                Thread t = new Thread(r, "unified-crawl-source-shared-" + t_counter_source.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        if (sharedVectorIndexPool == null || sharedVectorIndexPool.isShutdown()) {
            sharedVectorIndexPool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "unified-crawl-vector-index");
                t.setDaemon(true);
                return t;
            });
        }

        log.info("Unified crawl executor initialized: maxConcurrentJobs={}, queueCapacity={}, memoryWait={}%, memoryCritical={}%",
                threads, capacity, memoryWaitThresholdPercent, memoryCriticalThresholdPercent);
    }

    private boolean hasGraphConstructor() {
        return graphExtractionOrchestrator != null && graphExtractionOrchestrator.hasGraphConstructor();
    }

    @PreDestroy
    public void shutdownExecutor() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        shutdownPool(sharedChunkingPool, "sharedChunkingPool");
        shutdownPool(sharedGraphExtractionPool, "sharedGraphExtractionPool");
        shutdownPool(sharedSourceLoadPool, "sharedSourceLoadPool");
        shutdownPool(sharedVectorIndexPool, "sharedVectorIndexPool");
        archiveDeferredEmbeddingChunksOnShutdown();
    }

    /**
     * On graceful shutdown, persist in-memory deferred embedding chunks to disk for all
     * COMPLETED_PENDING_EMBEDDING jobs so they can be replayed after restart via
     * {@code POST /api/unified-crawl/jobs/{jobId}/steps/VECTOR_INDEXING/run}.
     */
    private void archiveDeferredEmbeddingChunksOnShutdown() {
        int archived = 0;
        int failed = 0;
        for (UnifiedCrawlJob job : jobs.values()) {
            if (job.getStatus().get() != UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING) continue;
            List<org.springframework.ai.document.Document> chunks = job.getDeferredEmbeddingChunks();
            if (chunks == null || chunks.isEmpty()) continue;
            try {
                String dir = archiveStep(job.getJobId(), "VECTOR_INDEXING");
                if (dir != null) {
                    archived++;
                    log.info("[Shutdown] Archived {} deferred embedding chunk(s) for job {} → {}",
                            chunks.size(), job.getJobId(), dir);
                }
            } catch (Exception e) {
                failed++;
                log.warn("[Shutdown] Failed to archive deferred embedding chunks for job {}: {}",
                        job.getJobId(), e.getMessage());
            }
        }
        if (archived > 0 || failed > 0) {
            log.info("[Shutdown] Deferred-embedding archival complete: {} job(s) archived, {} failed",
                    archived, failed);
        }
    }

    private void shutdownPool(ExecutorService pool, String name) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(15, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private UnifiedCrawlRequest normalizeMandatoryGraphExtraction(UnifiedCrawlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Crawl request is required");
        }
        GraphExtractionConfig graphConfig = request.getGraphExtraction();
        if (graphConfig == null) {
            request.setGraphExtraction(GraphExtractionConfig.builder().build());
        }
        return request;
    }

    private UnifiedCrawlRequest resolveFactSheetScope(UnifiedCrawlRequest request) {
        if (factSheetScopeResolver != null) {
            factSheetScopeResolver.resolveScope(request);
        }
        String requestedName = request.getFactSheetName();
        if (request.getFactSheetId() == null && requestedName != null && !requestedName.isBlank()) {
            throw new IllegalArgumentException("Fact sheet '" + requestedName.trim()
                    + "' must resolve to a concrete ID before the crawl starts");
        }
        if (factSheetScopeResolver != null && request.getFactSheetId() == null) {
            throw new IllegalStateException("The crawl fact-sheet resolver did not provide a concrete ID");
        }
        return request;
    }

    @Override
    public UnifiedCrawlJob startJob(UnifiedCrawlRequest request) {
        request = resolveFactSheetScope(normalizeMandatoryGraphExtraction(request));
        CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
        executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                llmDispatcher, executor, executorQueueCapacity);
        runtimeConfigManager.applyRequestOverrides(request.getRuntimeConfig(), this, graphExtractionOrchestrator);
        // Push per-crawl extraction model policy (providerAllow / excludeMarkers / modelAllow) to the
        // model service so dynamic model selection in CliAgentLLMChat respects per-project overrides.
        runtimeConfigManager.applyExtractionPolicy(request.getGraphExtraction(), llmDispatcher);
        if (request.getSources() == null || request.getSources().isEmpty()) {
            throw new IllegalArgumentException("At least one source is required");
        }

        String jobId = UUID.randomUUID().toString();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId(jobId)
                .request(request)
                .status(new AtomicReference<>(UnifiedCrawlJob.Status.PENDING))
                .createdAt(Instant.now())
                .queuedAt(Instant.now())
                .build();
        job.getMaxConcurrentJobs().set(Math.max(1, maxConcurrentJobs));
        job.getQueueCapacity().set(Math.max(1, queueCapacity));
        job.getCurrentPhase().set("QUEUED");
        recordEvent(job, "QUEUED", "INFO", "Queued unified crawl job",
                request.getSources().size() + " source(s)");

        List<UnifiedCrawlJob.SourceProgress> sourceProgressList = new ArrayList<>();
        for (UnifiedCrawlSource source : request.getSources()) {
            sourceProgressList.add(UnifiedCrawlJob.SourceProgress.builder()
                    .label(source.getLabel())
                    .sourceType(source.getSourceType() != null ? source.getSourceType().name() : "UNKNOWN")
                    .pathOrUrl(source.getPathOrUrl())
                    .status(UnifiedCrawlJob.Status.PENDING)
                    .build());
        }
        job.setSourceProgress(sourceProgressList);
        initializePipelineSteps(job);
        // ── Pre-flight model probe (Part 2) ──────────────────────────────────────────────────────
        // Run a quick availability probe for candidate extraction models so the result is visible
        // in the job's TuningDecision log BEFORE extraction starts. Failures here must NEVER abort
        // the crawl — the probe is purely informational and health-tracking.
        GraphExtractionConfig graphExtConfig = request.getGraphExtraction();
        try {
            if (llmDispatcher != null && llmDispatcher.cliAgentAvailability != null) {
                int probeMaxModels = 12; // default; future: read from graphExtConfig if a field is added
                Map<String, String> probeResults =
                        llmDispatcher.cliAgentAvailability.probeExtractionModels(probeMaxModels);
                if (!probeResults.isEmpty()) {
                    StringBuilder detailSb = new StringBuilder();
                    probeResults.forEach((model, outcome) -> {
                        if (detailSb.length() > 0) detailSb.append(", ");
                        detailSb.append(model).append('=').append(outcome);
                    });
                    String probeDetail = detailSb.toString();
                    log.info("[Job {}] MODEL_PREFLIGHT_PROBE: {}", jobId, probeDetail);
                    job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                            .timestamp(Instant.now())
                            .stage("MODEL_PREFLIGHT_PROBE")
                            .oldValue(0).newValue(probeResults.size())
                            .direction("PROBE")
                            .reason("preflight_model_availability")
                            .detail(probeDetail)
                            .memoryPercent(job.getMemoryUsagePercent().get())
                            .build());
                }
            }
        } catch (Exception probeEx) {
            log.warn("[Job {}] MODEL_PREFLIGHT_PROBE failed (non-fatal): {}", jobId, probeEx.getMessage());
        }
        // ── Context-window-derived batch budget (Part 3) ──────────────────────────────────────────
        // Apply the primary-model context-window char budget to the orchestrator and record it as a
        // TuningDecision so it is visible in the job UI. Only fires when fraction > 0.
        try {
            if (llmDispatcher != null && llmDispatcher.cliAgentAvailability != null) {
                String budgetDetail = runtimeConfigManager.applyContextBudget(
                        graphExtConfig, llmDispatcher, graphExtractionOrchestrator);
                if (budgetDetail != null) {
                    log.info("[Job {}] BATCH_BUDGET: {}", jobId, budgetDetail);
                    job.recordTuningDecision(UnifiedCrawlJob.TuningDecision.builder()
                            .timestamp(Instant.now())
                            .stage("BATCH_BUDGET")
                            .oldValue(graphExtractionTargetCharsPerBatch).newValue(graphExtractionOrchestrator.graphExtractionTargetCharsPerBatch)
                            .direction("MAXIMIZE")
                            .reason("context_window_budget")
                            .detail(budgetDetail)
                            .memoryPercent(job.getMemoryUsagePercent().get())
                            .build());
                }
            }
        } catch (Exception budgetEx) {
            log.warn("[Job {}] BATCH_BUDGET apply failed (non-fatal): {}", jobId, budgetEx.getMessage());
        }
        jobs.put(jobId, job);

        long sequence = submitSequence.incrementAndGet();
        queuedSequences.put(jobId, sequence);
        updateQueueSnapshots();

        JobExecution execution = new JobExecution();
        FutureTask<Void> task = new FutureTask<>(() -> {
            runQueuedJob(job, execution);
            return null;
        });
        execution.task = task;
        jobExecutions.put(jobId, execution);

        try {
            executor().execute(task);
            updateQueueSnapshots();
        } catch (RejectedExecutionException e) {
            execution.state.set(JobExecutionState.FINISHED);
            jobExecutions.remove(jobId, execution);
            queuedSequences.remove(jobId);
            job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
            job.setErrorMessage("Unified crawl queue is full");
            job.setCompletedAt(Instant.now());
            recordEvent(job, "QUEUED", "ERROR", "Unified crawl queue is full",
                    "queueCapacity=" + queueCapacity + ", activeJobs=" + runningJobIds.size());
            updateQueueSnapshots();
            throw new IllegalStateException("Unified crawl queue is full; try again after running jobs finish");
        }

        log.info("Queued unified crawl job {} with {} sources", jobId, request.getSources().size());
        return job;
    }

    private ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) {
            initializeExecutor();
        } else {
            CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
            int newCapacity = runtimeConfigManager.applyRuntimeConfig(
                    config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                    llmDispatcher, executor, executorQueueCapacity);
            if (newCapacity == -1) {
                if (executor != null && !executor.isShutdown()) {
                    executor.shutdown();
                    executor = null;
                }
                executorQueueCapacity = -1;
                initializeExecutor();
            } else {
                executorQueueCapacity = newCapacity;
            }
        }
        // Apply the current parallelism config to the already-running shared stage pools so a runtime
        // change (e.g. crawlGraphExtractionParallelism via the kompile JSON config) takes effect on the
        // next crawl WITHOUT an app restart. The pools are created once at startup size; without this
        // they silently keep it — the cause of serial graph extraction despite a higher configured N.
        resizeSharedPoolsToConfig();
        return executor;
    }

    /** Resize the shared stage pools in place to match the current runtime parallelism config. */
    private void resizeSharedPoolsToConfig() {
        resizePool(sharedGraphExtractionPool, Math.max(1, graphExtractionParallelism), "graph-extraction");
        resizePool(sharedChunkingPool, Math.max(1, chunkingParallelism), "chunking");
        resizePool(sharedSourceLoadPool, Math.max(1, sourceLoadParallelism), "source-load");
    }

    private void resizePool(ExecutorService pool, int target, String name) {
        if (!(pool instanceof ThreadPoolExecutor tpe) || tpe.isShutdown()) {
            return;
        }
        if (tpe.getCorePoolSize() == target && tpe.getMaximumPoolSize() == target) {
            return;
        }
        // ThreadPoolExecutor requires max >= core at all times; order the two setters for grow vs shrink.
        if (target >= tpe.getCorePoolSize()) {
            tpe.setMaximumPoolSize(target);
            tpe.setCorePoolSize(target);
        } else {
            tpe.setCorePoolSize(target);
            tpe.setMaximumPoolSize(target);
        }
        log.info("Resized shared {} pool to {} threads (runtime config change)", name, target);
    }

    private CompletableFuture<Void> startVectorIndexingFuture(
            UnifiedCrawlJob job,
            List<Document> chunksForIndex,
            VectorIndexConfig indexConfig) {
        ExecutorService pool = sharedVectorIndexPool;
        if (pool == null || pool.isShutdown()) {
            initializeExecutor();
            pool = sharedVectorIndexPool;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            pool.execute(() -> {
                try {
                    runVectorIndexingStep(job, chunksForIndex, indexConfig);
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private void waitForVectorIndexingFuture(
            UnifiedCrawlJob job,
            CompletableFuture<Void> vectorIndexFuture,
            List<Document> chunksForIndex,
            VectorIndexConfig indexConfig) {
        try {
            if (vectorIndexFuture == null) {
                log.warn("[Job {}] VECTOR_INDEXING future was not started; deferring {} chunk(s)",
                        job.getJobId(), chunksForIndex.size());
                vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                        "Vector indexing worker did not start");
                archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                        job.getDeferredVectorIndexConfig(),
                        job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived (worker did not start)");
                return;
            }
            log.info("[Job {}] Waiting for background VECTOR_INDEXING to finish before embedding-similarity edges",
                    job.getJobId());
            recordEvent(job, "VECTOR_INDEXING", "INFO",
                    "Waiting for background vector indexing to finish",
                    chunksForIndex.size() + " chunk(s)");
            vectorIndexFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            vectorIndexFuture.cancel(false);
            log.warn("[Job {}] Interrupted while waiting for VECTOR_INDEXING; deferring {} chunk(s)",
                    job.getJobId(), chunksForIndex.size());
            vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                    "Interrupted while waiting for vector indexing");
            archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                    job.getDeferredVectorIndexConfig(),
                    job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived (interrupted)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            if (isTerminalEmbeddingModelFailure(vectorIndexingHelper.primaryEmbeddingModel())
                    || detail.contains("Embedding model failed")) {
                failPipelineStep(job, "VECTOR_INDEXING", "Background vector indexing failed: " + detail);
                throw new IllegalStateException("Background vector indexing failed: " + detail, cause);
            }
            log.warn("[Job {}] Background VECTOR_INDEXING failed before completion: {}",
                    job.getJobId(), detail, cause);
            vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                    "Background vector indexing failed: " + detail);
            archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                    job.getDeferredVectorIndexConfig(),
                    job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived after background failure");
        } finally {
            chunksForIndex.clear();
        }
    }

    private void runVectorIndexingStep(
            UnifiedCrawlJob job,
            List<Document> chunksForIndex,
            VectorIndexConfig indexConfig) {
        if (isCancelled(job)) {
            log.info("[Job {}] VECTOR_INDEXING skipped because crawl was cancelled before indexing started",
                    job.getJobId());
            return;
        }

        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
        if (!vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
            if (isTerminalEmbeddingModelFailure(embModel)) {
                String reason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
                failPipelineStep(job, "VECTOR_INDEXING", "Embedding model failed: " + reason);
                recordEvent(job, "VECTOR_INDEXING", "ERROR", "Embedding model failed",
                        "vector indexing: " + reason);
                throw new IllegalStateException("Embedding model failed for vector indexing: " + reason);
            }
            boolean embModelIsLoading = false;
            try { embModelIsLoading = embModel != null && embModel.isLoading(); } catch (Exception ignored) {}
            if (!embModelIsLoading) {
                String unavailableReason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
                log.warn("[Job {}] Embedding model unavailable and not loading ({}); deferring vector indexing immediately",
                        job.getJobId(), unavailableReason);
                vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                        "Embedding model unavailable: " + unavailableReason);
                archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                        job.getDeferredVectorIndexConfig(),
                        job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived (model unavailable)");
                return;
            }

            String notReadyReason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
            log.info("[Job {}] Embedding model not ready ({}); background vector indexing waiting for load",
                    job.getJobId(), notReadyReason);
            updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, chunksForIndex.size(), 0, 0, 0, 0, null,
                    "Loading embedding model...");
            recordEvent(job, "VECTOR_INDEXING", "INFO", "Waiting for embedding model to load",
                    notReadyReason);
            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS,
                    "Loading embedding model: " + notReadyReason);

            final long embModelLoadTimeoutMs = 300_000L;
            final long embPollIntervalMs = 5_000L;
            long embWaitStart = System.currentTimeMillis();
            boolean embReady = false;
            while (!isCancelled(job)) {
                embModel = vectorIndexingHelper.primaryEmbeddingModel();
                if (vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                    embReady = true;
                    break;
                }
                if (isTerminalEmbeddingModelFailure(embModel)) {
                    String reason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
                    failPipelineStep(job, "VECTOR_INDEXING", "Embedding model failed: " + reason);
                    recordEvent(job, "VECTOR_INDEXING", "ERROR", "Embedding model failed",
                            "vector indexing: " + reason);
                    throw new IllegalStateException("Embedding model failed for vector indexing: " + reason);
                }
                long elapsed = System.currentTimeMillis() - embWaitStart;
                if (elapsed >= embModelLoadTimeoutMs) {
                    log.warn("[Job {}] Embedding model load timed out after {}ms; falling back to deferred indexing",
                            job.getJobId(), elapsed);
                    break;
                }
                String loadPhase = embModel != null ? embModel.getLoadingPhase() : "starting";
                String loadMsg = embModel != null ? embModel.getLoadingMessage() : null;
                long loadElapsed = embModel != null ? embModel.getLoadingElapsedMs() : elapsed;
                String progressMsg = "Loading embedding model"
                        + (loadPhase != null ? " [" + loadPhase + "]" : "")
                        + (loadMsg != null ? ": " + loadMsg : "")
                        + " (" + (loadElapsed / 1000) + "s)";
                recordEvent(job, "VECTOR_INDEXING", "INFO", "Embedding model loading", progressMsg);
                publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, progressMsg);
                try { Thread.sleep(embPollIntervalMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!embReady) {
                log.warn("[Job {}] Embedding model unavailable after wait; deferring vector indexing",
                        job.getJobId());
                vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                        "Embedding model load timed out; deferred as fallback");
                archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                        job.getDeferredVectorIndexConfig(),
                        job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived (embedding load timeout)");
                return;
            }
            log.info("[Job {}] Embedding model loaded in background; proceeding to vector indexing",
                    job.getJobId());
            recordEvent(job, "VECTOR_INDEXING", "INFO", "Embedding model ready",
                    "Proceeding with background vector indexing");
            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS,
                    "Embedding model loaded; starting vector indexing");
        }

        if (vectorIndexingHelper.shouldDeferForGpu()) {
            log.info("[Job {}] Deferring vector indexing; GPU under pressure will resume when VRAM frees",
                    job.getJobId());
            vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                    "GPU under pressure; embedding deferred for resource recovery");
            archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                    job.getDeferredVectorIndexConfig(),
                    job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived (GPU pressure)");
            return;
        }

        try {
            log.info("[Job {}] Indexing {} chunks to vector store in background while graph extraction continues",
                    job.getJobId(), chunksForIndex.size());
            vectorIndexingHelper.indexDocuments(chunksForIndex, indexConfig, job);
            log.info("[Job {}] Background vector indexing complete", job.getJobId());
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("[Job {}] Vector indexing deferred after background failure: {}",
                    job.getJobId(), errorDetail, e);
            vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                    "Vector indexing failed and was deferred: " + errorDetail);
            archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(job.getDeferredEmbeddingChunks()),
                    job.getDeferredVectorIndexConfig(),
                    job.getDeferredEmbeddingChunks().size() + " chunk(s) auto-archived after failure");
        }
    }

    private EmbeddingModel waitForEmbeddingModelReady(UnifiedCrawlJob job, String phase, String purpose) {
        final long embModelLoadTimeoutMs = 300_000L;
        final long embPollIntervalMs = 5_000L;
        long waitStart = System.currentTimeMillis();
        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
        if (vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
            return embModel;
        }
        failIfEmbeddingModelTerminal(job, phase, purpose, embModel);

        String notReadyReason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
        log.info("[Job {}] Embedding model not ready for {} ({}); waiting for load",
                job.getJobId(), purpose, notReadyReason);
        recordEvent(job, phase, "INFO", "Waiting for embedding model to load", purpose + ": " + notReadyReason);
        publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS,
                "Loading embedding model for " + purpose + ": " + notReadyReason);

        while (!isCancelled(job)) {
            embModel = vectorIndexingHelper.primaryEmbeddingModel();
            if (vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                recordEvent(job, phase, "INFO", "Embedding model ready", purpose);
                return embModel;
            }
            failIfEmbeddingModelTerminal(job, phase, purpose, embModel);
            long elapsed = System.currentTimeMillis() - waitStart;
            if (elapsed >= embModelLoadTimeoutMs) {
                String reason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
                recordEvent(job, phase, "ERROR", "Embedding model readiness timed out",
                        purpose + ": " + reason + " after " + elapsed + "ms");
                return null;
            }
            String loadPhase = embModel != null ? embModel.getLoadingPhase() : "starting";
            String loadMsg = embModel != null ? embModel.getLoadingMessage() : null;
            long loadElapsed = embModel != null ? embModel.getLoadingElapsedMs() : elapsed;
            String progressMsg = "Loading embedding model for " + purpose
                    + (loadPhase != null ? " [" + loadPhase + "]" : "")
                    + (loadMsg != null ? ": " + loadMsg : "")
                    + " (" + (loadElapsed / 1000) + "s)";
            recordEvent(job, phase, "INFO", "Embedding model loading", progressMsg);
            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, progressMsg);
            try { Thread.sleep(embPollIntervalMs); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private void failIfEmbeddingModelTerminal(UnifiedCrawlJob job, String phase, String purpose,
                                              EmbeddingModel embModel) {
        if (!isTerminalEmbeddingModelFailure(embModel)) {
            return;
        }
        String reason = vectorIndexingHelper.embeddingModelNotReadyReason(embModel);
        String detail = purpose + ": " + reason;
        recordEvent(job, phase, "ERROR", "Embedding model failed", detail);
        publishProgressEvent(job, CrawlProgressEvent.EventType.ERROR,
                "Embedding model failed for " + purpose + ": " + reason);
        throw new IllegalStateException("Embedding model failed for " + purpose + ": " + reason);
    }

    private boolean isTerminalEmbeddingModelFailure(EmbeddingModel embModel) {
        if (embModel == null) {
            return false;
        }
        try {
            return "FAILED".equalsIgnoreCase(embModel.getLoadingPhase());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void runQueuedJob(UnifiedCrawlJob job, JobExecution execution) {
        if (!execution.state.compareAndSet(JobExecutionState.QUEUED, JobExecutionState.RUNNING)) {
            return;
        }

        String jobId = job.getJobId();
        queuedSequences.remove(jobId);
        runningJobIds.add(jobId);
        updateQueueSnapshots();
        try {
            if (isCancelled(job)) {
                return;
            }
            executeJob(job);
        } finally {
            try {
                runningJobIds.remove(jobId);
                llmDispatcher.removeTracker(jobId);
                llmDispatcher.clearOpencodeModelIndex(jobId);
            } finally {
                execution.state.set(JobExecutionState.FINISHED);
                jobExecutions.remove(jobId, execution);
                if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLING) {
                    completeCancelledJob(job);
                }
                updateQueueSnapshots();
            }
        }
    }

    @Override
    public Optional<UnifiedCrawlJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<UnifiedCrawlJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public List<UnifiedCrawlJob> getActiveJobs() {
        return jobs.values().stream()
                .filter(j -> j.getStatus().get() == UnifiedCrawlJob.Status.PENDING
                        || j.getStatus().get() == UnifiedCrawlJob.Status.RUNNING
                        || j.getStatus().get() == UnifiedCrawlJob.Status.CANCELLING)
                .collect(Collectors.toList());
    }

    @Override
    public boolean cancelJob(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null) {
            return false;
        }

        UnifiedCrawlJob.Status current;
        do {
            current = job.getStatus().get();
            if (current == UnifiedCrawlJob.Status.COMPLETED
                    || current == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                    || current == UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH
                    || current == UnifiedCrawlJob.Status.FAILED
                    || current == UnifiedCrawlJob.Status.CANCELLING
                    || current == UnifiedCrawlJob.Status.CANCELLED) {
                return false;
            }
        } while (!job.getStatus().compareAndSet(current, UnifiedCrawlJob.Status.CANCELLING));

        recordEvent(job, job.getCurrentPhase().get(), "WARN",
                "Unified crawl cancellation requested",
                "The job remains active until its worker and owned resources have quiesced");

        JobExecution execution = jobExecutions.get(jobId);
        if (execution != null
                && execution.state.compareAndSet(
                        JobExecutionState.QUEUED, JobExecutionState.CANCELLED_BEFORE_START)) {
            queuedSequences.remove(jobId);
            FutureTask<Void> task = execution.task;
            if (task != null) {
                task.cancel(false);
                ThreadPoolExecutor currentExecutor = executor;
                if (currentExecutor != null) {
                    currentExecutor.remove(task);
                }
            }
            execution.state.set(JobExecutionState.FINISHED);
            jobExecutions.remove(jobId, execution);
            completeCancelledJob(job);
        }

        updateQueueSnapshots();
        return true;
    }

    private void completeCancelledJob(UnifiedCrawlJob job) {
        if (job.getStatus().get() != UnifiedCrawlJob.Status.CANCELLING) {
            return;
        }

        String cancelledPhase = job.getCurrentPhase().get();
        try {
            UnifiedCrawlJob.PipelineStepProgress cancelledStep = ensurePipelineStep(job, cancelledPhase);
            UnifiedCrawlJob.PipelineStepStatus stepStatus = cancelledStep.getStatus().get();
            if (stepStatus == UnifiedCrawlJob.PipelineStepStatus.RUNNING
                    || stepStatus == UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE
                    || stepStatus == UnifiedCrawlJob.PipelineStepStatus.PENDING) {
                applyPipelineStepUpdate(cancelledStep, UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        cancelledStep.getCompletedItems().get(), cancelledStep.getTotalItems().get(),
                        cancelledStep.getFailedItems().get(), cancelledStep.getCompletedBatches().get(),
                        cancelledStep.getTotalBatches().get(), cancelledStep.getCurrentBatchSize().get(),
                        cancelledStep.getCurrentItem().get(), "Job cancelled after worker quiescence");
            }
        } finally {
            job.getCurrentPhase().set("CANCELLED");
            job.getProgressPercent().set(Math.max(job.getProgressPercent().get(), 0));
            job.setCompletedAt(Instant.now());
            job.getStatus().compareAndSet(
                    UnifiedCrawlJob.Status.CANCELLING, UnifiedCrawlJob.Status.CANCELLED);
        }
        recordEvent(job, "CANCELLED", "WARN", "Unified crawl job cancelled",
                "Worker and job-scoped resources have quiesced");
    }

    @Override
    public int cleanupJobs() {
        int removed = 0;
        Iterator<Map.Entry<String, UnifiedCrawlJob>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, UnifiedCrawlJob> entry = it.next();
            UnifiedCrawlJob.Status status = entry.getValue().getStatus().get();
            // NOTE: COMPLETED_PENDING_EMBEDDING is intentionally NOT evicted here — its deferred
            // chunks must survive until DeferredEmbeddingResumer drains them and flips to COMPLETED.
            if (status == UnifiedCrawlJob.Status.COMPLETED
                    || status == UnifiedCrawlJob.Status.FAILED
                    || status == UnifiedCrawlJob.Status.CANCELLED) {
                String jobId = entry.getKey();
                JobExecution execution = jobExecutions.get(jobId);
                if (execution != null && execution.state.get() != JobExecutionState.FINISHED) {
                    continue;
                }
                queuedSequences.remove(jobId);
                if (execution != null) {
                    jobExecutions.remove(jobId, execution);
                }
                runningJobIds.remove(jobId);
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int resumeDeferredEmbedding(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null
                || job.getStatus().get() != UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING) {
            return 0;
        }
        List<Document> pending = new ArrayList<>(job.getDeferredEmbeddingChunks());
        if (pending.isEmpty()) {
            return 0;
        }
        VectorIndexConfig config = job.getDeferredVectorIndexConfig();
        if (config == null) {
            log.warn("[Job {}] Cannot resume deferred embedding — no deferred vector index config", jobId);
            return 0;
        }
        log.info("[Job {}] Resuming deferred embedding for {} chunk(s)", jobId, pending.size());
        try {
            vectorIndexingHelper.indexDocuments(pending, config, job);
            // Clear ONLY on full success so a partial/failed resume keeps the chunks for retry.
            job.getDeferredEmbeddingChunks().clear();
            if (job.getStatus().compareAndSet(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING,
                    UnifiedCrawlJob.Status.COMPLETED)) {
                job.getCurrentPhase().set("COMPLETED");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "COMPLETED", "INFO", "Deferred embedding completed",
                        pending.size() + " chunk(s) embedded after resource recovery");
                log.info("[Job {}] Deferred embedding completed — job marked COMPLETED", jobId);
            }
            return pending.size();
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Leave the deferred chunks intact (do not clear) so the next resumer tick retries them.
            log.warn("[Job {}] Deferred embedding resume failed ({}); will retry on next tick", jobId, detail);
            return 0;
        }
    }

    @Override
    public int resumeDeferredGraph(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null
                || job.getStatus().get() != UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH) {
            return 0;
        }
        List<Document> pending = new ArrayList<>(job.getDeferredGraphChunks());
        if (pending.isEmpty()) {
            return 0;
        }
        GraphExtractionConfig config = job.getDeferredGraphExtractionConfig();
        if (config == null && job.getRequest() != null) {
            config = job.getRequest().getGraphExtraction();
        }
        if (config == null) {
            log.warn("[Job {}] Cannot resume deferred graph extraction — no graph extraction config", jobId);
            return 0;
        }
        log.info("[Job {}] Resuming deferred graph extraction for {} chunk(s)", jobId, pending.size());
        try {
            Graph g = new Graph();
            g.setId(job.getJobId());
            g.setEntities(new ArrayList<>());
            g.setRelationships(new ArrayList<>());
            g.setCommunities(new ArrayList<>());
            graphExtractionOrchestrator.resetGraphExtractionProgress(job, pending.size());
            graphExtractionOrchestrator.progressNotifier = j -> publishProgressEvent(j, CrawlProgressEvent.EventType.PROGRESS, "Graph extraction progress");
            try {
                graphExtractionOrchestrator.extractGraphFromDocuments(pending, config, g, job, sharedGraphExtractionPool);
            } finally {
                graphExtractionOrchestrator.progressNotifier = null;
            }
            // Clear ONLY on full success so a partial/failed resume keeps the chunks for the next tick.
            job.getDeferredGraphChunks().clear();
            if (job.getStatus().compareAndSet(UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH,
                    UnifiedCrawlJob.Status.COMPLETED)) {
                job.getCurrentPhase().set("COMPLETED");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "COMPLETED", "INFO", "Deferred graph extraction completed",
                        pending.size() + " chunk(s) extracted after resource recovery");
                log.info("[Job {}] Deferred graph extraction completed — job marked COMPLETED", jobId);
            }
            return pending.size();
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Leave the deferred chunks intact (do not clear) so the next resumer tick retries them.
            log.warn("[Job {}] Deferred graph resume failed ({}); will retry on next tick", jobId, detail);
            return 0;
        }
    }

    @Override
    public int resumeArchivedStep(String jobId, String stepId) {
        if (crawlStepArchiveService == null) {
            log.warn("Cannot resume archived step {} for job {} — archive service unavailable", stepId, jobId);
            return -1;
        }
        String step = pipelineStepTracker.normalizeStepId(stepId);
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null) {
            CrawlStepArchiveService.ArchivedJobSnapshot snap = crawlStepArchiveService.loadSnapshot(jobId);
            if (snap == null) {
                log.warn("Cannot resume archived step {} — job {} not in memory and no snapshot on disk", step, jobId);
                return -1;
            }
            job = rehydrateJob(snap);
            registerRehydratedJob(job);
            log.info("[Job {}] Rehydrated from persisted snapshot to resume archived step {}", jobId, step);
        }
        CrawlStepArchiveService.ArchivedStepData data = crawlStepArchiveService.load(jobId, step);
        if (data == null) {
            log.warn("[Job {}] No archive found on disk for step {}", jobId, step);
            return -1;
        }
        UnifiedCrawlJob.PipelineStepProgress sp = ensurePipelineStep(job, step);
        // Double-resume guard: only one caller may move the step out of ARCHIVED.
        if (!sp.getStatus().compareAndSet(UnifiedCrawlJob.PipelineStepStatus.ARCHIVED,
                UnifiedCrawlJob.PipelineStepStatus.RUNNING)) {
            UnifiedCrawlJob.PipelineStepStatus current = sp.getStatus().get();
            if (current == UnifiedCrawlJob.PipelineStepStatus.COMPLETED) {
                log.info("[Job {}] Archived step {} already completed", jobId, step);
                return 0;
            }
            log.info("[Job {}] Archived step {} not resumable (status={})", jobId, step, current);
            return 0;
        }
        List<Document> chunks = data.chunks() != null ? data.chunks() : new ArrayList<>();
        log.info("[Job {}] Resuming archived step {} with {} chunk(s)", jobId, step, chunks.size());
        try {
            int processed = runArchivedStep(job, step, chunks, data.configJson());
            completePipelineStep(job, step, processed, "Archived step resumed and completed");
            crawlStepArchiveService.markStepCompleted(jobId, step);
            recordEvent(job, step, "INFO", "Archived step resumed", processed + " item(s) processed");
            return processed;
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Revert to ARCHIVED so the step can be retried.
            pipelineStepTracker.archivePipelineStep(job, step, "Resume failed; still archived: " + detail);
            log.warn("[Job {}] Failed to resume archived step {}: {}", jobId, step, detail, e);
            return -1;
        }
    }

    @Override
    public String archiveStep(String jobId, String stepId) {
        if (crawlStepArchiveService == null) {
            return null;
        }
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        String step = pipelineStepTracker.normalizeStepId(stepId);
        if ("VECTOR_INDEXING".equals(step)) {
            List<Document> chunks = new ArrayList<>(job.getDeferredEmbeddingChunks());
            if (chunks.isEmpty()) {
                log.warn("[Job {}] No deferred chunks available to archive for {}", jobId, step);
                return null;
            }
            VectorIndexConfig cfg = job.getDeferredVectorIndexConfig() != null
                    ? job.getDeferredVectorIndexConfig()
                    : (job.getRequest() != null ? job.getRequest().getVectorIndex() : null);
            String dir = crawlStepArchiveService.archive(job, step, chunks, cfg);
            if (dir != null) {
                pipelineStepTracker.archivePipelineStep(job, step,
                        chunks.size() + " chunk(s) archived to disk for later embedding");
                job.getDeferredEmbeddingChunks().clear();
            }
            return dir;
        } else if ("GRAPH_EXTRACTION".equals(step)) {
            // Archive graph-extraction with the job's extraction config so the step can be
            // re-run against the fact sheet's existing graph state on resume.
            GraphExtractionConfig cfg = job.getRequest() != null ? job.getRequest().getGraphExtraction() : null;
            String dir = crawlStepArchiveService.archive(job, step, List.of(), cfg);
            if (dir != null) {
                pipelineStepTracker.archivePipelineStep(job, step,
                        "Graph extraction archived for later re-run on fact sheet graph");
            }
            return dir;
        } else {
            log.warn("[Job {}] On-demand archive is only supported for VECTOR_INDEXING and GRAPH_EXTRACTION (got {})", jobId, step);
            return null;
        }
    }

    @Override
    public void registerRehydratedJob(UnifiedCrawlJob job) {
        if (job != null && job.getJobId() != null) {
            jobs.putIfAbsent(job.getJobId(), job);
        }
    }

    @Override
    public void registerJobIdAlias(String alias, String internalJobId) {
        if (alias != null && internalJobId != null) {
            jobIdAliases.put(alias, internalJobId);
        }
    }

    @Override
    public String resolveJobId(String aliasOrInternalId) {
        if (aliasOrInternalId == null) return null;
        return jobIdAliases.getOrDefault(aliasOrInternalId, aliasOrInternalId);
    }

    @Override
    public List<CrawlStepArchiveService.ResumableCrawlJob> listResumableCrawlJobs() {
        return crawlStepArchiveService != null ? crawlStepArchiveService.listResumableCrawlJobs() : List.of();
    }

    @Override
    public Map<String, Object> getCrawlRuntimeConfig() {
        return runtimeConfigManager.currentCrawlRuntimeConfig();
    }

    @Override
    public Map<String, Object> updateCrawlRuntimeConfig(Map<String, Object> updates) {
        try {
            return runtimeConfigManager.updateCrawlRuntimeConfig(updates);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist crawl runtime config: " + e.getMessage(), e);
        }
    }

    /**
     * Archive a pipeline step's inputs to disk and mark it ARCHIVED. Falls back to SKIP (so the job
     * still completes) when no archive service is wired or the write fails, UNLESS the step is already
     * DEFERRED — in that case the DEFERRED status is preserved so the embedding resumer can pick it up.
     *
     * <p>The DEFERRED-preservation rule applies to the pre-archive step state: if the step was DEFERRED
     * before this call, a failed/missing archive must NOT downgrade it to ARCHIVED or SKIPPED because the
     * embedding resumer (DeferredEmbeddingResumer) relies on that status to find pending chunks.
     */
    private void archiveCrawlStep(UnifiedCrawlJob job, String stepId, List<Document> chunks,
                                  Object config, String message) {
        // Capture current status before any writes so archive failures don't override DEFERRED.
        boolean stepWasDeferred = job.getPipelineSteps().stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .findFirst()
                .map(s -> s.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.DEFERRED)
                .orElse(false);

        if (crawlStepArchiveService != null) {
            try {
                String dir = crawlStepArchiveService.archive(job, stepId, chunks, config);
                if (dir != null) {
                    pipelineStepTracker.archivePipelineStep(job, stepId, message);
                    recordEvent(job, stepId, "INFO", "Step archived for later", message);
                    return;
                }
                // archive() returned null (write failed) — log but don't flip status
                log.warn("[Job {}] Archive write returned null for step {} — leaving step status unchanged",
                        job.getJobId(), stepId);
            } catch (Exception e) {
                log.warn("[Job {}] Failed to archive step {}: {}", job.getJobId(), stepId, e.getMessage(), e);
            }
        }
        // Archive unavailable or failed. If the step was already DEFERRED, preserve that status
        // so the embedding resumer can find the chunks. Otherwise fall back to SKIPPED.
        if (stepWasDeferred) {
            log.debug("[Job {}] Step {} was DEFERRED before archive attempt — preserving DEFERRED status (archive unavailable/failed)",
                    job.getJobId(), stepId);
        } else {
            skipPipelineStep(job, stepId, message + " (archive unavailable — skipped)");
        }
    }

    /**
     * Persist a resumable checkpoint for a job that failed mid-pipeline so the user can click "resume"
     * and re-run only the steps that did not finish. Graph extraction and vector indexing already persist
     * their own resumable state — the graph-extraction orchestrator archives the precise failed chunks
     * (see {@link GraphExtractionOrchestrator}), and vector indexing defers its chunks for the embedding
     * resumer — so re-archiving them here would duplicate the write and clobber those precise sets. This
     * therefore only covers the graph-state steps that otherwise have no resume path: a failed entity
     * resolution / edge computation re-runs from the persisted fact-sheet graph (no chunks needed). No-op
     * when no archive service is wired or there is no graph to operate on. The {@code archive()} call also
     * flags the owning job resumable in the history store, which is what surfaces it to the resume UI.
     */
    void checkpointFailedJobForResume(UnifiedCrawlJob job) {
        if (crawlStepArchiveService == null || job == null) {
            return;
        }
        // Only checkpoint graph-state steps when a graph actually exists to resolve / compute edges over.
        boolean haveGraph = job.getEntitiesExtracted().get() > 0 || job.getRelationshipsExtracted().get() > 0;
        if (!haveGraph) {
            return;
        }
        GraphExtractionConfig graphConfig = job.getRequest() != null ? job.getRequest().getGraphExtraction() : null;
        List<String> checkpointed = new ArrayList<>();
        if (isStepCheckpointable(job, "ENTITY_RESOLUTION")) {
            checkpointResumableStep(job, "ENTITY_RESOLUTION", List.of(), graphConfig, checkpointed);
        }
        if (isStepCheckpointable(job, "EDGE_COMPUTATION")) {
            checkpointResumableStep(job, "EDGE_COMPUTATION", List.of(), null, checkpointed);
        }
        if (!checkpointed.isEmpty()) {
            log.info("[Job {}] Saved failure checkpoint; resumable step(s): {}", job.getJobId(), checkpointed);
            recordEvent(job, "FAILED", "INFO", "Checkpoint saved — job can be resumed",
                    "Resumable step(s): " + String.join(", ", checkpointed));
        }
    }

    /** True when a step has not reached a terminal/non-resumable state and can be archived for resume. */
    private boolean isStepCheckpointable(UnifiedCrawlJob job, String stepId) {
        UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, stepId);
        UnifiedCrawlJob.PipelineStepStatus status = step.getStatus().get();
        return status != UnifiedCrawlJob.PipelineStepStatus.COMPLETED
                && status != UnifiedCrawlJob.PipelineStepStatus.SKIPPED
                && status != UnifiedCrawlJob.PipelineStepStatus.ARCHIVED
                && status != UnifiedCrawlJob.PipelineStepStatus.CANCELLED;
    }

    /** Archive one step's inputs for resume and mark it ARCHIVED so the per-step "Run now" action appears. */
    private void checkpointResumableStep(UnifiedCrawlJob job, String stepId, List<Document> chunks,
                                         Object config, List<String> checkpointed) {
        try {
            String dir = crawlStepArchiveService.archive(job, stepId, chunks, config);
            if (dir != null) {
                pipelineStepTracker.archivePipelineStep(job, stepId,
                        "Checkpointed on failure — resume to re-run this step");
                checkpointed.add(stepId);
            }
        } catch (Exception e) {
            log.warn("[Job {}] Failed to checkpoint step {} for resume: {}", job.getJobId(), stepId, e.getMessage(), e);
        }
    }

    /** Re-run a single archived step using its persisted chunks/config and the existing helpers. */
    private int runArchivedStep(UnifiedCrawlJob job, String step, List<Document> chunks, String configJson) {
        switch (step) {
            case "VECTOR_INDEXING" -> {
                VectorIndexConfig cfg = parseStepConfig(configJson, VectorIndexConfig.class);
                if (cfg == null) {
                    cfg = job.getDeferredVectorIndexConfig();
                }
                if (cfg == null && job.getRequest() != null) {
                    cfg = job.getRequest().getVectorIndex();
                }
                vectorIndexingHelper.indexDocuments(chunks, cfg, job);
                return chunks.size();
            }
            case "GRAPH_EXTRACTION" -> {
                GraphExtractionConfig cfg = parseStepConfig(configJson, GraphExtractionConfig.class);
                if (cfg == null && job.getRequest() != null) {
                    cfg = job.getRequest().getGraphExtraction();
                }
                Graph g = new Graph();
                g.setId(job.getJobId());
                g.setEntities(new ArrayList<>());
                g.setRelationships(new ArrayList<>());
                g.setCommunities(new ArrayList<>());
                graphExtractionOrchestrator.resetGraphExtractionProgress(job, chunks.size());
                graphExtractionOrchestrator.progressNotifier = j -> publishProgressEvent(j, CrawlProgressEvent.EventType.PROGRESS, "Graph extraction progress");
                try {
                    graphExtractionOrchestrator.extractGraphFromDocuments(chunks, cfg, g, job, sharedGraphExtractionPool);
                } finally {
                    graphExtractionOrchestrator.progressNotifier = null;
                }
                return chunks.size();
            }
            case "ENTITY_RESOLUTION" -> {
                if (graphCompactionService == null) {
                    throw new IllegalStateException("Graph compaction service not available");
                }
                GraphExtractionConfig cfg = parseStepConfig(configJson, GraphExtractionConfig.class);
                if (cfg == null && job.getRequest() != null) {
                    cfg = job.getRequest().getGraphExtraction();
                }
                Long factSheetId = jobFactSheetId(job);
                double threshold = cfg != null ? entityResolutionSimilarityThreshold(cfg) : 0.9;
                graphCompactionService.compact(factSheetId,
                        new GraphCompactionService.CompactionConfig(threshold, true, false, 0.88, p -> { }));
                return 1;
            }
            case "EDGE_COMPUTATION" -> {
                if (graphEdgeComputationService == null) {
                    throw new IllegalStateException("Graph edge computation service not available");
                }
                Long factSheetId = jobFactSheetId(job);
                // minSharedEntities=1: link document pairs that share even a single entity
                // so per-document islands are connected and cells survive the component sweep.
                graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 1);
                graphEdgeComputationService.computeNameBasedCrossDocEdges(factSheetId);
                // [FIX-2] Flush cross-doc edges to the vector store so they are durable.
                if (knowledgeGraphService != null) {
                    knowledgeGraphService.flushPendingNodes();
                    log.info("[Job {}] EDGE_COMPUTATION resume: flushed cross-doc edges to persistent store", job.getJobId());
                }
                // Embedding-similarity edges: only when the embedding model is ready so that
                // resume works in CPU mode and backfills similarity edges later on GPU.
                EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                if (vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                    // Document-similarity edges only require document node embeddings; do not
                    // synchronously backfill every extracted entity during crawl/resume.
                    graphEdgeComputationService.backfillDocumentNodeEmbeddings(factSheetId);
                    graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                    // [FIX-2] Flush embedding-similarity edges to the vector store.
                    if (knowledgeGraphService != null) {
                        knowledgeGraphService.flushPendingNodes();
                        log.info("[Job {}] EDGE_COMPUTATION resume: flushed similarity edges to persistent store", job.getJobId());
                    }
                } else {
                    log.info("[Job {}] EDGE_COMPUTATION resume: embedding model not ready ({}), " +
                                    "skipping similarity edges — re-run this step when GPU is available",
                            job.getJobId(), vectorIndexingHelper.embeddingModelNotReadyReason(embModel));
                }
                return 1;
            }
            case "ENRICHMENT" -> {
                // Re-run the full hydration pipeline (DERIVATION + PRUNE_COMPACT + GNN_SCORING
                // + ONTOLOGY_CONFORMANCE + HEALTH)
                // against the already-persisted graph without re-crawling or re-extracting.
                Long factSheetId = jobFactSheetId(job);
                if (graphHydrationOrchestrator == null) {
                    log.warn("[Job {}] ENRICHMENT step resume: graphHydrationOrchestrator not wired — skipping",
                            job.getJobId());
                    return 0;
                }
                HydrationResult result = graphHydrationOrchestrator.run(
                        factSheetId,
                        HydrationConfig.defaults(),
                        (stage, msg) -> log.info("[Job {}] ENRICHMENT re-run [{}]: {}",
                                job.getJobId(), stage, msg));
                log.info("[Job {}] ENRICHMENT re-run complete: derivedRelations={} factsMaterialized={} "
                        + "merges={} orphansRemoved={} componentNodesRemoved={} gnnEdgesScored={}",
                        job.getJobId(),
                        result.relationsDerived(),
                        result.factsMaterialized(),
                        result.mergesPerformed(),
                        result.orphansRemoved(),
                        result.componentNodesRemoved(),
                        result.gnnEdgesScored());
                return result.stagesRun();
            }
            case "PRUNE" -> {
                // Re-run ONLY the prune/compact pass (P1–P5 + PH health snapshot) without
                // re-running derivation first. Idempotent: uses the current inferred-fact set.
                Long factSheetId = jobFactSheetId(job);
                if (graphHydrationOrchestrator == null) {
                    log.warn("[Job {}] PRUNE step resume: graphHydrationOrchestrator not wired — skipping",
                            job.getJobId());
                    return 0;
                }
                HydrationResult result = graphHydrationOrchestrator.runPruneOnly(factSheetId);
                log.info("[Job {}] PRUNE re-run complete: merges={} orphansRemoved={} componentNodesRemoved={}",
                        job.getJobId(),
                        result.mergesPerformed(),
                        result.orphansRemoved(),
                        result.componentNodesRemoved());
                return result.componentNodesRemoved() + result.orphansRemoved() + result.mergesPerformed();
            }
            case "DERIVATION" -> {
                // Re-run ONLY the MAP inference derivation without prune or conformance.
                // Use this when embeddings are back and you want to re-ground inferred facts
                // without touching the existing graph topology.
                Long factSheetId = jobFactSheetId(job);
                if (graphHydrationOrchestrator == null) {
                    log.warn("[Job {}] DERIVATION step resume: graphHydrationOrchestrator not wired — skipping",
                            job.getJobId());
                    return 0;
                }
                HydrationResult result = graphHydrationOrchestrator.runDerivationOnly(factSheetId);
                log.info("[Job {}] DERIVATION re-run complete: derivedRelations={} factsMaterialized={}",
                        job.getJobId(),
                        result.relationsDerived(),
                        result.factsMaterialized());
                return result.factsMaterialized();
            }
            case "ONTOLOGY_CONFORMANCE" -> {
                // Re-run ONLY the ontology conformance tagging pass. Tag-only: never removes nodes.
                // No-op if no ontology is bound to the fact sheet.
                Long factSheetId = jobFactSheetId(job);
                if (graphHydrationOrchestrator == null) {
                    log.warn("[Job {}] ONTOLOGY_CONFORMANCE step resume: graphHydrationOrchestrator not wired — skipping",
                            job.getJobId());
                    return 0;
                }
                HydrationResult result = graphHydrationOrchestrator.runOntologyConformanceOnly(factSheetId);
                log.info("[Job {}] ONTOLOGY_CONFORMANCE re-run complete: stagesRun={}",
                        job.getJobId(), result.stagesRun());
                return result.stagesRun();
            }
            default -> throw new IllegalArgumentException("Step is not resumable from archive: " + step);
        }
    }

    /** Reconstruct a minimal job from a persisted snapshot so its archived steps can be resumed. */
    private UnifiedCrawlJob rehydrateJob(CrawlStepArchiveService.ArchivedJobSnapshot snap) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name(snap.name())
                .factSheetId(snap.factSheetId())
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId(snap.jobId())
                .request(req)
                .build();
        job.getStatus().set(UnifiedCrawlJob.Status.RUNNING);
        initializePipelineSteps(job);
        if (snap.archivedSteps() != null) {
            for (String s : snap.archivedSteps()) {
                pipelineStepTracker.archivePipelineStep(job, s, "Rehydrated archived step");
            }
        }
        return job;
    }

    /** Convert the request's {@code Object} preprocessing config into a typed config (or null). */
    private PreprocessingConfig resolvePreprocessingConfig(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof PreprocessingConfig pc) {
            return pc;
        }
        try {
            return JSON_MAPPER.convertValue(raw, PreprocessingConfig.class);
        } catch (Exception e) {
            log.warn("Ignoring invalid preprocessing config: {}", e.getMessage());
            return null;
        }
    }

    private <T> T parseStepConfig(String json, Class<T> type) {
        if (json == null || json.isBlank() || "null".equals(json.trim())) {
            return null;
        }
        try {
            return JSON_MAPPER.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to parse archived step config for {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<UnifiedCrawlJob> retryJob(String originalJobId, String retryPhase, List<String> documentKeys) {
        UnifiedCrawlJob originalJob = jobs.get(originalJobId);
        if (originalJob == null) {
            log.warn("Retry requested for unknown job {}", originalJobId);
            return Optional.empty();
        }

        // Collect failed document keys from the original job
        List<String> failedKeys = new ArrayList<>();
        for (Map.Entry<String, UnifiedCrawlJob.DocumentProgress> entry : originalJob.getDocumentProgress().entrySet()) {
            UnifiedCrawlJob.DocumentProgress dp = entry.getValue();
            if (!"FAILED".equals(dp.getStatus())) continue;
            if (retryPhase != null && !retryPhase.equals(dp.getPhase())) continue;
            if (documentKeys != null && !documentKeys.isEmpty() && !documentKeys.contains(entry.getKey())) continue;
            failedKeys.add(entry.getKey());
        }

        if (failedKeys.isEmpty()) {
            log.info("No failed documents to retry in job {} (phase={})", originalJobId, retryPhase);
            return Optional.empty();
        }

        // Build a retry request from the original, carrying only failed documents
        UnifiedCrawlRequest originalRequest = originalJob.getRequest();
        UnifiedCrawlRequest retryRequest = UnifiedCrawlRequest.builder()
                .name((originalRequest.getName() != null ? originalRequest.getName() : "Crawl") + " (retry)")
                .factSheetId(originalRequest.getFactSheetId())
                .factSheetName(originalRequest.getFactSheetName())
                .sources(originalRequest.getSources())
                .graphExtraction(originalRequest.getGraphExtraction())
                .vectorIndex(originalRequest.getVectorIndex())
                .processingRoute(originalRequest.getProcessingRoute())
                .runtimeConfig(originalRequest.getRuntimeConfig())
                .preprocessing(originalRequest.getPreprocessing())
                .pipelines(originalRequest.getPipelines())
                .routeRules(originalRequest.getRouteRules())
                .defaultPipelineId(originalRequest.getDefaultPipelineId())
                .distribution(originalRequest.getDistribution())
                .retryFromJobId(originalJobId)
                .retryPhase(retryPhase)
                .retryDocumentKeys(failedKeys)
                .maxValidationRetries(originalRequest.getMaxValidationRetries())
                .build();

        log.info("Starting retry job from {} with {} failed documents (phase={})",
                originalJobId, failedKeys.size(), retryPhase);
        UnifiedCrawlJob retryJob = startJob(retryRequest);
        return Optional.of(retryJob);
    }

    @Override
    public List<AvailableSourceType> getAvailableSourceTypes() {
        List<AvailableSourceType> types = new ArrayList<>();

        addSourceType(types, DocumentSourceDescriptor.SourceType.DIRECTORY,
                "Local Directory", "Crawl a local filesystem directory",
                List.of("pathOrUrl"), List.of("includePatterns", "excludePatterns", "maxDepth", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.FILE,
                "Single File", "Load a single file",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.URL,
                "Web URL", "Load content from a single URL",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.WEB_CRAWL,
                "Web Crawl", "Recursively crawl a website starting from a seed URL",
                List.of("pathOrUrl"), List.of("maxDepth", "maxDocuments", "sameDomainOnly", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.EMAIL,
                "Email", "Load email content from configured mail sources",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "folder", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.IMAP,
                "IMAP Inbox", "Crawl an email inbox via IMAP",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "folder", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.POP3,
                "POP3 Inbox", "Crawl an email inbox via POP3",
                List.of("pathOrUrl"), List.of("host", "port", "username", "password", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.MBOX,
                "MBOX Archive", "Load an mbox email archive",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.MAILDIR,
                "Maildir Archive", "Load a Maildir email archive",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.EMLX_DIR,
                "Apple Mail Archive", "Load an Apple Mail .emlx directory",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.PST,
                "Outlook PST", "Load an Outlook PST archive",
                List.of("pathOrUrl"), List.of("crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.SLACK,
                "Slack", "Ingest messages from a Slack workspace",
                List.of("pathOrUrl"), List.of("token", "workspaceId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.SLACK_HISTORY,
                "Slack History", "Load exported Slack history",
                List.of("pathOrUrl"), List.of("token", "workspaceId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.DISCORD,
                "Discord", "Ingest messages from a Discord server",
                List.of("pathOrUrl"), List.of("botToken", "guildId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.DISCORD_HISTORY,
                "Discord History", "Load exported Discord message history",
                List.of("pathOrUrl"), List.of("botToken", "guildId", "channels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GMAIL,
                "Gmail", "Crawl Gmail messages via the Gmail API",
                List.of("pathOrUrl"), List.of("oauthToken", "query", "labels", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GDOCS,
                "Google Docs", "Crawl Google Docs via the Google APIs",
                List.of("pathOrUrl"), List.of("oauthToken", "documentId", "folderId", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GDRIVE,
                "Google Drive", "Load files from Google Drive",
                List.of("pathOrUrl"), List.of("folderId", "oauthToken", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.ONEDRIVE,
                "OneDrive", "Load files from Microsoft OneDrive",
                List.of("pathOrUrl"), List.of("driveId", "folderId", "oauthToken", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.GOOGLE_WORKSPACE,
                "Google Workspace", "Crawl Gmail, Drive, Docs, and Calendar from Google Workspace",
                List.of("pathOrUrl"), List.of("oauthToken", "workspaceId", "includeGmail", "includeDrive", "crawlerId"));
        addSourceType(types, DocumentSourceDescriptor.SourceType.CONFLUENCE,
                "Confluence", "Load pages from Confluence",
                List.of("pathOrUrl"), List.of("spaceKey", "apiToken", "crawlerId"));

        return types;
    }

    // ---- Internal pipeline execution ----

    private void executeJob(UnifiedCrawlJob job) {
        // Per-job listener that streams every managed subprocess's own logs + lifecycle
        // (embedding, learning, …) into this crawl's live UI. Registered after STARTED below,
        // detached in the finally so it never leaks across jobs.
        final SubprocessLogSink subprocessSink = ev -> handleSubprocessLog(job, ev);
        try {
            CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
            executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                    config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                    llmDispatcher, executor, executorQueueCapacity);
            runtimeConfigManager.applyRequestOverrides(job.getRequest().getRuntimeConfig(), this, graphExtractionOrchestrator);
            if (!job.getStatus().compareAndSet(
                    UnifiedCrawlJob.Status.PENDING, UnifiedCrawlJob.Status.RUNNING)) {
                if (isCancelled(job)) {
                    return;
                }
                throw new IllegalStateException("Cannot start crawl job from status " + job.getStatus().get());
            }
            job.setStartedAt(Instant.now());
            initializePipelineSteps(job);
            publishProgressEvent(job, CrawlProgressEvent.EventType.STARTED, "Crawl started");
            if (subprocessLogBus != null) {
                subprocessLogBus.register(subprocessSink);
            }
            // Attribute all embedding-subprocess activity to this job for the whole run, so its
            // events relay to the crawl UI even when embedding runs on executor threads.
            if (vectorIndexingHelper != null) {
                vectorIndexingHelper.setActiveCrawlJobId(job.getJobId());
            }
            CrawlStepPlan stepPlan = CrawlStepPlan.from(job.getRequest());
            try {
                stepPlan.validate();
            } catch (IllegalArgumentException e) {
                failPipelineStep(job, "LOADING", "Invalid step selection: " + e.getMessage());
                throw new IllegalStateException("Invalid crawl step plan: " + e.getMessage(), e);
            }
            recordEvent(job, "LOADING", "INFO", "Pipeline step plan", stepPlan.actions().toString());
            updateMemorySnapshot(job);

            // [FIX-4] Clear graph before loading if explicitly requested (default=false = merge/update).
            // This is a destructive opt-in only; the default preserves all prior enrichment/confidence.
            if (crawlClearGraphBeforeRun && knowledgeGraphService != null) {
                Long clearFactSheetId = jobFactSheetId(job);
                if (clearFactSheetId != null) {
                    log.warn("[Job {}] crawlClearGraphBeforeRun=true: CLEARING graph and extraction checkpoints for factSheetId={} before LOADING",
                            job.getJobId(), clearFactSheetId);
                    recordEvent(job, "LOADING", "WARN", "Graph and extraction checkpoints cleared before crawl",
                            "crawlClearGraphBeforeRun=true: all nodes/edges and graph extraction checkpoints for factSheetId=" + clearFactSheetId + " deleted");
                    if (graphExtractionCheckpointStore != null) {
                        try {
                            graphExtractionCheckpointStore.clearFactSheet(clearFactSheetId);
                        } catch (Exception checkpointEx) {
                            failPipelineStep(job, "LOADING",
                                    "Graph extraction checkpoint clear failed: " + checkpointEx.getMessage());
                            throw new IllegalStateException("Failed to clear graph extraction checkpoints before crawl", checkpointEx);
                        }
                    }
                    try {
                        knowledgeGraphService.deleteByFactSheetId(clearFactSheetId);
                        knowledgeGraphService.flushPendingNodes();
                        log.info("[Job {}] Graph cleared for factSheetId={}", job.getJobId(), clearFactSheetId);
                    } catch (Exception clearEx) {
                        log.warn("[Job {}] Graph clear failed (non-fatal): {}", job.getJobId(), clearEx.getMessage());
                    }
                } else {
                    log.debug("[Job {}] crawlClearGraphBeforeRun=true but no factSheetId — skipping clear", job.getJobId());
                }
            }

            TokenBudgetTracker tokenTracker = new TokenBudgetTracker();
            tokenTracker.registerBackend("default");
            llmDispatcher.registerTracker(job.getJobId(), tokenTracker);
            updateProgress(job, "LOADING", 1, "Starting source loading",
                    job.getRequest().getSources().size() + " source(s)");

            // Phase 1: Crawl/load documents from all sources
            Map<String, List<Document>> docsBySource = new LinkedHashMap<>();
            // Propagate incremental-crawl flags to the loading service so the per-file
            // hash-check skip logic can read them without a back-reference to this class.
            sourceLoadingService.crawlIncrementalByContentHash = crawlIncrementalByContentHash;
            sourceLoadingService.crawlForceFullRecrawl = crawlForceFullRecrawl;
            List<CrawlSourceLoadingService.SourceLoadResult> sourceResults =
                    sourceLoadingService.loadSources(job, sourceLoadParallelism, sharedSourceLoadPool);
            List<Document> allDocuments = new ArrayList<>();
            for (CrawlSourceLoadingService.SourceLoadResult result : sourceResults) {
                if (result.documents() == null) {
                    continue;
                }
                docsBySource.put(result.label(), result.documents());
                allDocuments.addAll(result.documents());
            }
            int sourceCount = sourceResults.size();
            if (allDocuments.isEmpty() && job.getErrorCount().get() > 0) {
                String reason = sourceLoadingService.summarizeSourceLoadErrors(job);
                failPipelineStep(job, "LOADING", reason);
                throw new IllegalStateException(reason);
            }
            completePipelineStep(job, "LOADING", sourceCount,
                    allDocuments.size() + " document(s) loaded from " + sourceCount + " source(s)");

            sourceLoadingService.registerCrawledSourcesAsFacts(job, sourceResults);

            sourceResults.clear();
            docsBySource.clear();

            if (isCancelled(job)) return;

            // Phase 2: Text conversion (fast, CPU-only)
            updatePipelineStep(job, "CONVERTING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Normalizing loaded document text");
            updateProgress(job, "CONVERTING", estimateProgress(job),
                    "Normalizing loaded document text", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "CONVERTING");
            allDocuments = new ArrayList<>(textConversionService.convertDocumentText(allDocuments, job));
            completePipelineStep(job, "CONVERTING", allDocuments.size(),
                    allDocuments.size() + " document(s) normalized");
            log.info("[Job {}] Text conversion complete: {} documents", job.getJobId(), allDocuments.size());

            if (isCancelled(job)) return;

            // Phase 2b: Optional document preprocessing (PII redaction, dedup, unicode norm, ...). Opt-in:
            // only runs when the step plan enables it AND a preprocessing config is present.
            if (stepPlan.isRun("PREPROCESSING") && preprocessingPipelineRunner != null) {
                PreprocessingConfig ppConfig = resolvePreprocessingConfig(job.getRequest().getPreprocessing());
                if (ppConfig != null && ppConfig.isEnabled() && ppConfig.hasAnyStepEnabled()) {
                    updatePipelineStep(job, "PREPROCESSING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                            0, allDocuments.size(), 0, 0, 0, 0, null, "Preprocessing documents");
                    waitForMemoryCapacity(job, "PREPROCESSING");
                    List<Document> preprocessed = preprocessingPipelineRunner.run(allDocuments, ppConfig, null);
                    if (preprocessed != null) {
                        allDocuments = new ArrayList<>(preprocessed);
                    }
                    completePipelineStep(job, "PREPROCESSING", allDocuments.size(),
                            allDocuments.size() + " document(s) preprocessed");
                    log.info("[Job {}] Preprocessing complete: {} documents", job.getJobId(), allDocuments.size());
                } else {
                    skipPipelineStep(job, "PREPROCESSING", "No preprocessing steps enabled");
                }
                if (isCancelled(job)) return;
            } else if (stepPlan.isSkip("PREPROCESSING")) {
                skipPipelineStep(job, "PREPROCESSING", "Preprocessing skipped by step plan");
            } else {
                skipPipelineStep(job, "PREPROCESSING", "Preprocessing pipeline not available");
            }

            // Phase 2.5: Dynamic PDF classification and routing
            ProcessingRouteConfig routeConfig = contentTypeRouter.resolveProcessingRouteConfig(job);
            if (routeConfig.getPdfRoutingMode() != ProcessingRouteConfig.PdfRoutingMode.DISABLED) {
                allDocuments = contentTypeRouter.classifyAndRoutePdfs(job, allDocuments, routeConfig);
            }

            if (isCancelled(job)) return;

            // Phase 3: Content-type routing
            final List<Document> docGraphDocs = textConversionService.copyDocumentsForBackgroundGraph(allDocuments);
            updatePipelineStep(job, "ROUTING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Routing content by document type");
            updateProgress(job, "ROUTING", estimateProgress(job),
                    "Routing content by document type", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "ROUTING");
            job.getCurrentFile().set("(formula graph extraction for " + allDocuments.size() + " documents)");
            // Routing is PURE classification: it returns the routed docs and COLLECTS the
            // structured-graph persistence into this sink instead of running it inline.
            List<Runnable> graphPersistence = new ArrayList<>();
            allDocuments = new ArrayList<>(contentTypeRouter.routeByContentType(job, allDocuments, graphPersistence));
            job.getCurrentFile().set(null);
            completePipelineStep(job, "ROUTING", docGraphDocs.size(),
                    allDocuments.size() + " document(s) routed to text pipeline");
            updateProgress(job, "ROUTING", estimateProgress(job),
                    "Content routing complete", allDocuments.size() + " text-pipeline document(s)");
            log.info("[Job {}] Content routing complete: {} documents for text pipeline", job.getJobId(), allDocuments.size());

            // Structured-graph persistence (formula/table/cell/document nodes) is independent per
            // routed document. Use bounded parallelism across documents; each task preserves its own
            // workbook ordering and sends bounded node/edge batches to the graph subprocess.
            if (!graphPersistence.isEmpty()) {
                long gpStart = System.currentTimeMillis();
                AtomicInteger completedPersistenceOps = new AtomicInteger();
                int persistenceThreads = Math.max(1,
                        Math.min(structuredGraphPersistenceThreads, graphPersistence.size()));
                job.getCurrentFile().set("(structured graph persistence: 0/" + graphPersistence.size()
                        + " ops, " + persistenceThreads + " workers)");
                ExecutorService persistencePool = Executors.newFixedThreadPool(persistenceThreads, r -> {
                    Thread t = new Thread(r, "structured-graph-" + job.getJobId().substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });
                try {
                    List<Callable<Void>> tasks = graphPersistence.stream()
                            .<Callable<Void>>map(task -> () -> {
                                if (!isCancelled(job)) {
                                    task.run();
                                    int completed = completedPersistenceOps.incrementAndGet();
                                    if (completed == graphPersistence.size() || completed % 10 == 0) {
                                        job.getCurrentFile().set("(structured graph persistence: "
                                                + completed + "/" + graphPersistence.size() + " ops)");
                                    }
                                }
                                return null;
                            })
                            .toList();
                    for (Future<Void> future : persistencePool.invokeAll(tasks)) {
                        future.get();
                    }
                    if (isCancelled(job)) {
                        return;
                    }
                } catch (Exception e) {
                    Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                    String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                    String message = "Structured graph persistence failed after " + completedPersistenceOps.get()
                            + "/" + graphPersistence.size() + " ops: " + detail;
                    job.getErrorCount().incrementAndGet();
                    job.setErrorMessage(message);
                    failPipelineStep(job, "ROUTING", message);
                    recordEvent(job, "ROUTING", "ERROR", "Structured graph persistence failed", message);
                    throw new IllegalStateException(message, cause);
                } finally {
                    persistencePool.shutdownNow();
                    job.getCurrentFile().set(null);
                }
                log.info("[Job {}] Structured graph persistence complete: {} ops, {} workers in {}ms",
                        job.getJobId(), graphPersistence.size(), persistenceThreads,
                        System.currentTimeMillis() - gpStart);
            }

            if (isCancelled(job)) return;

            // PARALLEL FORK: start cheap rule-based graph work in background
            updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                    "Starting rule-based graph preparation", null);
            int graphPrepTaskCount = crossDocumentRelationCallback != null && knowledgeGraphService != null ? 4 : 3;
            updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, graphPrepTaskCount, 0, 0, 0, 0, null,
                    "Starting rule-based graph preparation");
            ExecutorService backgroundGraphPool = Executors.newFixedThreadPool(Math.max(1, backgroundGraphThreads), r -> {
                Thread t = new Thread(r, "unified-crawl-bg-" + job.getJobId().substring(0, 8));
                t.setDaemon(true);
                return t;
            });
            List<Future<?>> backgroundGraphFutures = new ArrayList<>();

            final List<Document> emailDocs = textConversionService.copyDocumentsForBackgroundGraph(allDocuments);
            backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                try {
                    if (isCancelled(job)) return;
                    log.info("[Job {}] [BG] Email graph extraction starting", job.getJobId());
                    emailGraphExtractor.applyEmailGraphExtraction(job, emailDocs);
                    if (isCancelled(job)) return;
                    incrementPipelineStep(job, "GRAPH_PREP", 1, 0, "Email graph extraction complete");
                    log.info("[Job {}] [BG] Email graph extraction complete", job.getJobId());
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    if (!isCancelled(job)) {
                        failPipelineStep(job, "GRAPH_PREP", "Email graph extraction failed: " + errorDetail);
                    }
                    log.warn("[Job {}] [BG] Email graph extraction failed: {}", job.getJobId(), errorDetail, e);
                } finally {
                    int released = emailDocs.size();
                    emailDocs.clear();
                    if (!isCancelled(job)) {
                        recordEvent(job, "GRAPH_PREP", "INFO",
                                "Released email graph document batch", released + " document reference(s)");
                    }
                    trimNativeMemory(job, "GRAPH_PREP", "after releasing email graph documents");
                }
            }));

            backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                try {
                    if (isCancelled(job)) return;
                    log.info("[Job {}] [BG] Document graph extraction for {} documents",
                            job.getJobId(), docGraphDocs.size());
                    ruleBasedDocumentGraphExtractor.applyDocumentGraphExtraction(job, docGraphDocs);
                    if (isCancelled(job)) return;
                    incrementPipelineStep(job, "GRAPH_PREP", 1, 0, "Document graph extraction complete");
                    log.info("[Job {}] [BG] Document graph extraction complete", job.getJobId());
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    if (!isCancelled(job)) {
                        failPipelineStep(job, "GRAPH_PREP", "Document graph extraction failed: " + errorDetail);
                    }
                    log.warn("[Job {}] [BG] Document graph extraction failed: {}", job.getJobId(), errorDetail, e);
                } finally {
                    int released = docGraphDocs.size();
                    docGraphDocs.clear();
                    if (!isCancelled(job)) {
                        recordEvent(job, "GRAPH_PREP", "INFO",
                                "Released document graph input batch", released + " document reference(s)");
                    }
                    trimNativeMemory(job, "GRAPH_PREP", "after releasing document graph inputs");
                }
            }));

            if (crossDocumentRelationCallback != null && knowledgeGraphService != null) {
                backgroundGraphFutures.add(backgroundGraphPool.submit(() -> {
                    try {
                        if (isCancelled(job)) return;
                        Long factSheetId = jobFactSheetId(job);
                        log.info("[Job {}] [BG] Cross-document relation extraction starting (factSheetId={})",
                                job.getJobId(), factSheetId);
                        int edgesCreated = crossDocumentRelationCallback.extractRelationsFromGraphNodes(factSheetId);
                        if (isCancelled(job)) return;
                        job.getRelationshipsExtracted().addAndGet(edgesCreated);
                        incrementPipelineStep(job, "GRAPH_PREP", 1, 0,
                                "Cross-document relation extraction complete");
                        log.info("[Job {}] [BG] Cross-document relations: {} edges created (factSheetId={})",
                                job.getJobId(), edgesCreated, factSheetId);
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                        if (!isCancelled(job)) {
                            failPipelineStep(job, "GRAPH_PREP",
                                    "Cross-document relation extraction failed: " + errorDetail);
                        }
                        log.warn("[Job {}] [BG] Cross-document relation extraction failed: {}", job.getJobId(), errorDetail, e);
                    }
                }));
            }
            backgroundGraphPool.shutdown();

            // CRITICAL PATH: chunk -> sort by cost -> LLM extract
            job.getCurrentFile().set("(chunking " + allDocuments.size() + " documents)");
            updatePipelineStep(job, "CHUNKING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, allDocuments.size(), 0, 0, 0, 0, null,
                    "Chunking routed documents");
            updateProgress(job, "CHUNKING", estimateProgress(job),
                    "Chunking routed documents", allDocuments.size() + " document(s)");
            waitForMemoryCapacity(job, "CHUNKING");
            log.info("[Job {}] Chunking {} documents...", job.getJobId(), allDocuments.size());
            List<Document> chunkedDocuments = new ArrayList<>(
                    documentChunkingService.chunkDocuments(allDocuments, job,
                            chunkingParallelism, chunkingTargetCharsPerTask, costSortChunks, sharedChunkingPool));
            job.getChunksProcessed().set(chunkedDocuments.size());
            job.getChunksCreated().set(chunkedDocuments.size());
            job.getCurrentFile().set(null);
            updateProgress(job, "CHUNKING", estimateProgress(job),
                    "Chunking complete", chunkedDocuments.size() + " chunk(s)");
            completePipelineStep(job, "CHUNKING", allDocuments.size(),
                    allDocuments.size() + " document(s) chunked into " + chunkedDocuments.size() + " chunk(s)");
            log.info("[Job {}] Chunking complete: {} documents -> {} chunks",
                    job.getJobId(), allDocuments.size(), chunkedDocuments.size());

            updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                    "Registering snippet graph nodes", chunkedDocuments.size() + " chunk(s)");
            contentTypeRouter.registerSnippetNodes(job, chunkedDocuments);
            incrementPipelineStep(job, "GRAPH_PREP", 1, 0, "Snippet graph nodes registered");

            registerChunksInCrossIndex(job, chunkedDocuments);

            int releasedRoutedDocs = allDocuments.size();
            allDocuments.clear();
            recordEvent(job, "CHUNKING", "INFO",
                    "Released routed document batch after chunking",
                    releasedRoutedDocs + " document reference(s), chunks retained for graph/vector phases");
            trimNativeMemory(job, "CHUNKING", "after releasing routed documents");

            if (isCancelled(job)) return;

            if (costSortChunks && chunkedDocuments.size() > 1) {
                chunkedDocuments.sort((a, b) -> {
                    int lenA = a.getText() != null ? a.getText().length() : 0;
                    int lenB = b.getText() != null ? b.getText().length() : 0;
                    return Integer.compare(lenB, lenA);
                });
            }
            if (costSortChunks && !chunkedDocuments.isEmpty()) {
                int longest = chunkedDocuments.get(0).getText() != null ? chunkedDocuments.get(0).getText().length() : 0;
                int shortest = chunkedDocuments.get(chunkedDocuments.size() - 1).getText() != null
                        ? chunkedDocuments.get(chunkedDocuments.size() - 1).getText().length() : 0;
                log.info("[Job {}] Chunks sorted by cost: longest={}chars, shortest={}chars",
                        job.getJobId(), longest, shortest);
            }

            // Mark SURFACING complete now — chunks are ready. This must happen BEFORE any
            // concurrent work (vector indexing future) is submitted so that
            // vectorStep.startedAt >= surfaceStep.completedAt is guaranteed.
            surfaceCrawlResults(job, chunkedDocuments);

            VectorIndexConfig indexConfig = job.getRequest().getVectorIndex();
            final List<Document> chunksForIndex = new ArrayList<>(chunkedDocuments);
            boolean doVectorIndex = stepPlan.isRun("VECTOR_INDEXING") && indexConfig != null && indexConfig.isEnabled()
                    && vectorStore != null && !chunksForIndex.isEmpty();
            CompletableFuture<Void> vectorIndexFuture = null;
            if (doVectorIndex) {
                updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.PENDING,
                        0, chunksForIndex.size(), 0, 0, 0, 0, null,
                        "Queued to run concurrently with graph extraction");
                recordEvent(job, "VECTOR_INDEXING", "INFO",
                        "Vector indexing queued concurrently with graph extraction",
                        chunksForIndex.size() + " chunk(s)");
                vectorIndexFuture = startVectorIndexingFuture(job, chunksForIndex, indexConfig);
            }

            // Phase 6: LLM graph extraction
            Graph unifiedGraph = new Graph();
            unifiedGraph.setId(job.getJobId());
            unifiedGraph.setEntities(new ArrayList<>());
            unifiedGraph.setRelationships(new ArrayList<>());
            unifiedGraph.setCommunities(new ArrayList<>());
            // Set to true when extraction hits the wholesale-failure threshold (0 entities + too many
            // failed chunks). When true, downstream semantic steps are skipped — they are meaningless
            // without a semantic graph. Failed chunks are archived for a resumable re-run.
            boolean[] graphWholesaleFailure = {false};

            GraphExtractionConfig graphConfig = job.getRequest().getGraphExtraction();
            boolean graphConstructorAvailable = hasGraphConstructor();

            {
                boolean vectorRequested = job.getRequest().getVectorIndex() != null
                        && job.getRequest().getVectorIndex().isEnabled();
                StringBuilder configReport = new StringBuilder();
                configReport.append("Crawl configuration for job ").append(job.getJobId()).append(":\n");
                configReport.append("  Graph extraction: MANDATORY\n");
                configReport.append("  Vector indexing:  ").append(vectorRequested ? "ENABLED" : "DISABLED").append("\n");
                configReport.append("  GraphConstructor: ").append(graphConstructorAvailable ? "AVAILABLE" : "NOT CONFIGURED").append("\n");
                configReport.append("  LLMChat:          ").append(llmChat != null ? "AVAILABLE (" + llmChat.getClass().getSimpleName() + ")" : "NOT CONFIGURED").append("\n");
                configReport.append("  KnowledgeGraph:   ").append(knowledgeGraphService != null ? "AVAILABLE" : "NOT CONFIGURED");
                log.info("[Job {}] {}", job.getJobId(), configReport);
                recordEvent(job, job.getCurrentPhase().get(), "INFO",
                        "Configuration summary",
                        "graphExtraction=MANDATORY"
                                + ", vectorIndex=" + (vectorRequested ? "ON" : "OFF")
                                + ", graphConstructor=" + (graphConstructorAvailable ? "YES" : "NO")
                                + ", llm=" + (llmChat != null ? llmChat.getClass().getSimpleName() : "NONE")
                                + ", knowledgeGraph=" + (knowledgeGraphService != null ? "YES" : "NO"));

                if (!graphConstructorAvailable && llmChat == null) {
                    String warnMsg = "Graph extraction is mandatory but NO LLM or GraphConstructor is configured. "
                            + "Graph extraction will be SKIPPED until a graph extraction engine is available.";
                    log.warn("[Job {}] {}", job.getJobId(), warnMsg);
                    job.getErrors().add(warnMsg);
                    job.getErrorCount().incrementAndGet();
                    recordEvent(job, "GRAPH_EXTRACTION", "WARN", warnMsg, null);
                }
            }

            if (stepPlan.isRun("GRAPH_EXTRACTION") && (graphConstructorAvailable || llmChat != null)) {

                // Retry filtering: when this is a retry job, only process chunks whose parent
                // document key is in the retry list (i.e., documents that failed in the original job)
                List<String> retryKeys = job.getRequest().getRetryDocumentKeys();
                if (retryKeys != null && !retryKeys.isEmpty()) {
                    Set<String> retryKeySet = new HashSet<>(retryKeys);
                    int originalSize = chunkedDocuments.size();
                    chunkedDocuments = chunkedDocuments.stream()
                            .filter(doc -> {
                                Map<String, Object> meta = doc.getMetadata();
                                String sourcePath = documentTracker.documentSourcePath(meta, doc.getId());
                                String docKey = documentTracker.documentKeyFromSourcePath(sourcePath, doc.getId());
                                return retryKeySet.contains(docKey);
                            })
                            .collect(Collectors.toList());
                    int filtered = originalSize - chunkedDocuments.size();
                    if (filtered > 0) {
                        log.info("[Job {}] Retry mode: filtered {} already-succeeded chunks, {} remaining for retry",
                                job.getJobId(), filtered, chunkedDocuments.size());
                        recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                "Retry: skipped " + filtered + " succeeded chunks",
                                chunkedDocuments.size() + " chunks to retry");
                    }
                }

                job.getCurrentFile().set("(graph extraction: " + chunkedDocuments.size() + " chunks, parallel LLM)");
                graphExtractionOrchestrator.resetGraphExtractionProgress(job, chunkedDocuments.size());
                updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                        0, chunkedDocuments.size(), 0, 0, 0, 0, null,
                        "Starting graph extraction");
                updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                        "Starting graph extraction", chunkedDocuments.size() + " chunk(s)");
                waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                log.info("[Job {}] Starting LLM graph extraction for {} chunks (vector indexing running concurrently if enabled)",
                        job.getJobId(), chunkedDocuments.size());
                graphExtractionOrchestrator.progressNotifier = j -> publishProgressEvent(j, CrawlProgressEvent.EventType.PROGRESS, "Graph extraction progress");
                try {
                    graphExtractionOrchestrator.extractGraphFromDocuments(chunkedDocuments, graphConfig, unifiedGraph, job, sharedGraphExtractionPool);
                } finally {
                    graphExtractionOrchestrator.progressNotifier = null;
                }
                job.getCurrentFile().set(null);

                int extractedEntities = job.getEntitiesExtracted().get();
                int extractedRelationships = job.getRelationshipsExtracted().get();
                int graphErrors = job.getErrorCount().get();
                String graphSummary = extractedEntities + " entities, " + extractedRelationships + " relationships";

                // Finish-early wholesale-failure guard: when entities==0 AND a sufficient fraction of
                // chunks failed, skip downstream semantic steps (ENTITY_RESOLUTION, EDGE_COMPUTATION,
                // ENRICHMENT) that are meaningless without a semantic graph. The threshold comes from
                // crawlGraphExtractionWholesaleFailureThreshold (default 1.0 = only on total failure).
                // Failed chunks are already archived as a resumable GRAPH_EXTRACTION step by the
                // orchestrator, so the crawl can be re-run once the extraction issue is resolved.
                boolean wholesaleFailure = false;
                if (extractedEntities == 0 && chunkedDocuments.size() > 0) {
                    // Zero entities from a non-empty chunk set is always a failure — either errors
                    // dominated, OR every chunk "parsed" without exception yet produced nothing
                    // (graphErrors == 0), which is a SILENT parse/LLM-config failure that must never
                    // be rubber-stamped as a completed step. The old guard only fired when
                    // graphErrors > 0, so silent zero-yield fell through to completePipelineStep().
                    double failedFraction = (double) graphErrors / chunkedDocuments.size();
                    double threshold = graphExtractionOrchestrator.wholesaleFailureThreshold;
                    boolean silentZeroYield = graphErrors == 0;
                    if (silentZeroYield || failedFraction >= threshold) {
                        wholesaleFailure = true;
                        graphWholesaleFailure[0] = true;
                        String detail = silentZeroYield
                                ? "all " + chunkedDocuments.size() + " chunk(s) parsed without error but yielded 0 entities"
                                        + " (likely an extraction-response parse or LLM-configuration problem)"
                                : graphErrors + "/" + chunkedDocuments.size() + " chunks failed ("
                                        + String.format("%.0f%%", failedFraction * 100) + ")";
                        String failMsg = "Graph extraction produced 0 entities: " + detail
                                + " — downstream semantic steps SKIPPED. Chunks archived for resumable re-run.";
                        log.error("[Job {}] {}", job.getJobId(), failMsg);
                        job.getErrors().add(failMsg);
                        recordEvent(job, "GRAPH_EXTRACTION", "ERROR", failMsg,
                                "graphErrors=" + graphErrors + ", chunks=" + chunkedDocuments.size()
                                        + ", threshold=" + threshold + ", failedFraction=" + String.format("%.2f", failedFraction));
                        publishProgressEvent(job, CrawlProgressEvent.EventType.ERROR, failMsg);
                        failPipelineStep(job, "GRAPH_EXTRACTION", failMsg);
                        // Skip the downstream semantic steps by marking them so they don't run.
                        for (String skipStep : new String[]{"ENTITY_RESOLUTION", "EDGE_COMPUTATION", "ENRICHMENT"}) {
                            skipPipelineStep(job, skipStep,
                                    "Skipped: graph extraction produced 0 entities (" + detail + ")");
                        }
                    }
                }
                if (!wholesaleFailure) {
                if (extractedEntities == 0 && extractedRelationships == 0 && graphErrors > 0) {
                    String failMsg = "LLM graph extraction failed for all " + chunkedDocuments.size()
                            + " chunks (" + graphErrors + " errors). Check LLM configuration.";
                    log.error("[Job {}] {}", job.getJobId(), failMsg);
                    job.getErrors().add(failMsg);
                    recordEvent(job, "GRAPH_EXTRACTION", "ERROR", failMsg, null);
                    failPipelineStep(job, "GRAPH_EXTRACTION", failMsg);
                } else {
                    if (graphErrors > 0) {
                        String warnMsg = graphErrors + " of " + chunkedDocuments.size()
                                + " chunks failed LLM graph extraction. " + graphSummary + " from successful chunks.";
                        log.warn("[Job {}] {}", job.getJobId(), warnMsg);
                        recordEvent(job, "GRAPH_EXTRACTION", "WARN", warnMsg, null);
                    }
                    updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                            "Graph extraction complete", graphSummary);
                    completePipelineStep(job, "GRAPH_EXTRACTION", graphExtractionOrchestrator.completeGraphExtractionProgress(job),
                            graphSummary);
                }
                } // end !wholesaleFailure
                trimNativeMemory(job, "GRAPH_EXTRACTION", "after graph extraction");
            } else if (stepPlan.isArchive("GRAPH_EXTRACTION")) {
                archiveCrawlStep(job, "GRAPH_EXTRACTION", new ArrayList<>(chunkedDocuments), graphConfig,
                        chunkedDocuments.size() + " chunk(s) archived for later graph extraction");
            } else if (stepPlan.isSkip("GRAPH_EXTRACTION")) {
                skipPipelineStep(job, "GRAPH_EXTRACTION", "Graph extraction skipped because a required upstream step did not run");
            } else {
                skipPipelineStep(job, "GRAPH_EXTRACTION",
                        "Graph extraction SKIPPED: no LLM or GraphConstructor configured. "
                                + "GraphConstructor=" + graphConstructorAvailable + ", LLMChat=" + (llmChat != null));
            }

            if (isCancelled(job)) return;

            // JOIN: wait for background graph work to finish
            for (int i = 0; i < backgroundGraphFutures.size(); i++) {
                waitForBackgroundGraphFuture(job, backgroundGraphFutures.get(i),
                        i + 1, backgroundGraphFutures.size());
            }
            emailDocs.clear();
            docGraphDocs.clear();
            UnifiedCrawlJob.PipelineStepProgress graphPrepStep = ensurePipelineStep(job, "GRAPH_PREP");
            if (graphPrepStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.RUNNING
                    || graphPrepStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE) {
                completePipelineStep(job, "GRAPH_PREP",
                        Math.max(graphPrepStep.getCompletedItems().get(), graphPrepStep.getTotalItems().get()),
                        "Rule-based graph preparation complete");
            }
            trimNativeMemory(job, "GRAPH_PREP", "before entity resolution");

            if (isCancelled(job)) return;

            // Phase 6.5: Entity resolution
            // Skipped when graphWholesaleFailure: 0 semantic entities → resolution is a no-op and
            // downstream steps (edge computation, enrichment) are equally meaningless. The failed
            // chunks are archived and resumable via GET /api/unified-crawl/jobs/resumable.
            GraphExtractionConfig graphConfigForResolution = job.getRequest().getGraphExtraction();
            if (!graphWholesaleFailure[0] && stepPlan.isRun("ENTITY_RESOLUTION") && graphCompactionService != null && graphConfigForResolution != null
                    && graphConfigForResolution.isEntityResolution()) {
                try {
                    updateProgress(job, "ENTITY_RESOLUTION", estimateProgress(job),
                            "Running entity resolution", null);
                    boolean memoryReady = waitForMemoryCapacity(job, "ENTITY_RESOLUTION");
                    log.info("[Job {}] Running entity resolution / graph compaction", job.getJobId());
                    updatePipelineStep(job, "ENTITY_RESOLUTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                            0, 1, 0, 0, 0, 0, null, "Running entity resolution");
                    Long factSheetId = jobFactSheetId(job);
                    boolean useEmbeddingResolution = graphConfigForResolution.isEntityResolutionUseEmbeddings();
                    double embeddingResolutionThreshold = graphConfigForResolution.getEntityResolutionEmbeddingThreshold() > 0
                            ? graphConfigForResolution.getEntityResolutionEmbeddingThreshold() : 0.88;
                    if (useEmbeddingResolution) {
                        if (!memoryReady || hasNativeMemoryPressure(job, nativeMemoryWaitThresholdPercent)) {
                            String reason = memoryPressureDetail(job);
                            useEmbeddingResolution = false;
                            recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                    "Embedding-assisted entity resolution disabled",
                                    "Native memory unavailable; falling back to deterministic resolution: " + reason);
                        } else {
                            EmbeddingModel embModel = waitForEmbeddingModelReady(job, "ENTITY_RESOLUTION",
                                    "embedding-assisted entity resolution");
                            if (embModel == null) {
                                if (isCancelled(job)) return;
                                String reason = vectorIndexingHelper.embeddingModelNotReadyReason(
                                        vectorIndexingHelper.primaryEmbeddingModel());
                                useEmbeddingResolution = false;
                                recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                        "Embedding-assisted entity resolution disabled",
                                        "Embedding model unavailable; falling back to deterministic resolution: " + reason);
                            }
                        }
                    }
                    recordEvent(job, "ENTITY_RESOLUTION", "INFO", "Entity resolution mode",
                            "embeddings=" + useEmbeddingResolution
                                    + ", threshold=" + entityResolutionSimilarityThreshold(graphConfigForResolution)
                                    + ", embeddingThreshold=" + embeddingResolutionThreshold);
                    var compactionResult = graphCompactionService.compact(factSheetId,
                            new GraphCompactionService.CompactionConfig(
                                    entityResolutionSimilarityThreshold(graphConfigForResolution),
                                    true, useEmbeddingResolution, embeddingResolutionThreshold,
                                    progress -> recordEntityResolutionProgress(job, progress)));
                    if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                        updatePipelineStep(job, "ENTITY_RESOLUTION",
                                UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                                0, 1, 0, 0, 0, 0, null, "Entity resolution cancelled");
                        return;
                    }
                    log.info("[Job {}] Graph compaction: {} entities merged into {} ({}ms)",
                            job.getJobId(), compactionResult.entitiesMerged(),
                            compactionResult.finalEntityCount(), compactionResult.elapsedMs());
                    // Promote barcode/GTIN signals (resolved during compaction) into first-class
                    // IDENTIFIER nodes + RESOLVES_TO edges, mirroring the HTTP compact/advanced path —
                    // automated crawls otherwise never materialize them.
                    materializeIdentifiers(job, factSheetId);
                    completePipelineStep(job, "ENTITY_RESOLUTION", 1,
                            compactionResult.entitiesMerged() + " merge(s), "
                                    + compactionResult.finalEntityCount() + " final entities");
                } catch (CancellationException e) {
                    updatePipelineStep(job, "ENTITY_RESOLUTION",
                            UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                            0, 1, 0, 0, 0, 0, null, "Entity resolution cancelled");
                    recordEvent(job, "ENTITY_RESOLUTION", "WARN", "Entity resolution cancelled", e.getMessage());
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    String errorDetail = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                    failPipelineStep(job, "ENTITY_RESOLUTION", "Graph compaction failed: " + errorDetail);
                    log.warn("[Job {}] Graph compaction failed: {}", job.getJobId(), errorDetail, e);
                    throw new IllegalStateException("Graph compaction failed: " + errorDetail, e);
                } finally {
                    trimNativeMemory(job, "ENTITY_RESOLUTION", "after entity resolution");
                }
            } else if (stepPlan.isArchive("ENTITY_RESOLUTION")) {
                archiveCrawlStep(job, "ENTITY_RESOLUTION", new ArrayList<>(), graphConfigForResolution,
                        "Entity resolution archived to run later");
            } else if (stepPlan.isSkip("ENTITY_RESOLUTION")) {
                skipPipelineStep(job, "ENTITY_RESOLUTION", "Entity resolution skipped by step plan");
            } else {
                skipPipelineStep(job, "ENTITY_RESOLUTION", "Entity resolution disabled or unavailable");
            }

            if (isCancelled(job)) return;

            // Phase 7+8: Edge computation and vector indexing -- overlapped
            // Edge computation is skipped on wholesale extraction failure (no semantic graph to compute over).
            boolean doEdgeComputation = !graphWholesaleFailure[0] && stepPlan.isRun("EDGE_COMPUTATION")
                    && graphEdgeComputationService != null && knowledgeGraphService != null;
            if (!doEdgeComputation && stepPlan.isArchive("EDGE_COMPUTATION")) {
                archiveCrawlStep(job, "EDGE_COMPUTATION", new ArrayList<>(), null,
                        "Edge computation archived to run later");
            } else if (!doEdgeComputation && stepPlan.isSkip("EDGE_COMPUTATION")) {
                skipPipelineStep(job, "EDGE_COMPUTATION", "Edge computation skipped by step plan");
            }

            Future<?> sharedEdgeFuture = null;
            if (doEdgeComputation) {
                updateProgress(job, "EDGE_COMPUTATION", estimateProgress(job),
                        "Computing automatic graph edges", null);
                ExecutorService edgePool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "unified-crawl-edges-" + job.getJobId().substring(0, 8));
                    t.setDaemon(true);
                    return t;
                });
                updatePipelineStep(job, "EDGE_COMPUTATION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                        0, 2, 0, 0, 0, 0, null, "Computing automatic graph edges");
                sharedEdgeFuture = edgePool.submit(() -> {
                    try {
                        Long factSheetId = jobFactSheetId(job);
                        log.info("[Job {}] Computing shared entity edges for factSheetId={}",
                                job.getJobId(), factSheetId);
                        // minSharedEntities=1: link document pairs that share even a single entity
                        // so per-document islands are connected and cells survive the component sweep.
                        graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 1);
                        graphEdgeComputationService.computeNameBasedCrossDocEdges(factSheetId);
                        // [FIX-2] Persist cross-doc/SHARED_ENTITY edges to the vector store so they
                        // survive beyond this JVM session.  addEdge only mutates the in-memory adjacency
                        // matrix; without this flush the ~49k SHARED_ENTITY edges are lost on restart.
                        if (knowledgeGraphService != null) {
                            knowledgeGraphService.flushPendingNodes();
                            log.info("[Job {}] EDGE_COMPUTATION: flushed cross-doc edges to persistent store", job.getJobId());
                        }
                        incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Shared entity edges computed");
                        log.info("[Job {}] Shared entity edge computation complete", job.getJobId());
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                        log.warn("[Job {}] Shared entity edge computation failed: {}", job.getJobId(), errorDetail, e);
                        throw new IllegalStateException("Shared entity edge computation failed: " + errorDetail, e);
                    }
                });
                edgePool.shutdown();
            }

            if (doVectorIndex) {
                waitForVectorIndexingFuture(job, vectorIndexFuture, chunksForIndex, indexConfig);
            } else if (stepPlan.isArchive("VECTOR_INDEXING") && !chunksForIndex.isEmpty()) {
                archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(chunksForIndex), indexConfig,
                        chunksForIndex.size() + " chunk(s) archived for later embedding");
                chunksForIndex.clear();
            } else {
                chunksForIndex.clear();
                skipPipelineStep(job, "VECTOR_INDEXING", stepPlan.isSkip("VECTOR_INDEXING")
                        ? "Vector indexing skipped by step plan" : "Vector indexing disabled or unavailable");
            }

            // Barrier: await any async node-embedding tasks dispatched during GRAPH_PREP/GRAPH_EXTRACTION.
            // These tasks run in AnseriniVectorStoreImpl's background pool and must complete before
            // embedding-similarity edge computation or KGE training begins.
            if (vectorStore != null) {
                try {
                    vectorStore.awaitPendingEmbeddings();
                    log.info("[Job {}] Async graph-node embeddings settled before edge computation", job.getJobId());
                } catch (Exception awaitEx) {
                    String errorDetail = awaitEx.getMessage() != null ? awaitEx.getMessage()
                            : awaitEx.getClass().getSimpleName();
                    if (doEdgeComputation) {
                        failPipelineStep(job, "EDGE_COMPUTATION",
                                "Async graph-node embeddings did not fully settle: " + errorDetail);
                        log.warn("[Job {}] Async graph-node embedding barrier failed: {}",
                                job.getJobId(), errorDetail, awaitEx);
                        throw new IllegalStateException(
                                "Async graph-node embeddings did not fully settle: " + errorDetail, awaitEx);
                    }
                    log.warn("[Job {}] Async graph-node embedding barrier warning: {}",
                            job.getJobId(), errorDetail, awaitEx);
                    recordEvent(job, "EDGE_COMPUTATION", "WARN",
                            "Async graph-node embeddings did not fully settle",
                            errorDetail);
                }
            }
            job.getCurrentFile().set(null);

            if (doEdgeComputation) {
                if (sharedEdgeFuture != null) {
                    try {
                        sharedEdgeFuture.get(300, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failPipelineStep(job, "EDGE_COMPUTATION", "Shared edge computation interrupted");
                        throw new IllegalStateException("Shared edge computation interrupted", e);
                    } catch (TimeoutException e) {
                        failPipelineStep(job, "EDGE_COMPUTATION", "Shared edge computation timed out");
                        throw new IllegalStateException("Shared edge computation timed out", e);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        String errorDetail = cause.getMessage() != null ? cause.getMessage()
                                : cause.getClass().getSimpleName();
                        failPipelineStep(job, "EDGE_COMPUTATION", "Shared edge computation failed: " + errorDetail);
                        throw new IllegalStateException("Shared edge computation failed: " + errorDetail, cause);
                    }
                }
                if (!isCancelled(job)) {
                    try {
                        boolean runEmbeddingSimilarityEdges = true;
                        boolean memoryReady = waitForMemoryCapacity(job, "EDGE_COMPUTATION");
                        if (!memoryReady || hasNativeMemoryPressure(job, nativeMemoryWaitThresholdPercent)) {
                            String reason = memoryPressureDetail(job);
                            runEmbeddingSimilarityEdges = false;
                            recordEvent(job, "EDGE_COMPUTATION", "WARN",
                                    "Embedding similarity edges skipped",
                                    "Native memory unavailable: " + reason);
                        }
                        Long factSheetId = jobFactSheetId(job);
                        if (runEmbeddingSimilarityEdges) {
                            EmbeddingModel embModel = waitForEmbeddingModelReady(job, "EDGE_COMPUTATION",
                                    "embedding similarity edge computation");
                            if (embModel == null) {
                                if (isCancelled(job)) return;
                                String reason = vectorIndexingHelper.embeddingModelNotReadyReason(
                                        vectorIndexingHelper.primaryEmbeddingModel());
                                runEmbeddingSimilarityEdges = false;
                                recordEvent(job, "EDGE_COMPUTATION", "WARN",
                                        "Embedding similarity edges skipped",
                                        "Embedding model unavailable: " + reason);
                            }
                        }
                        if (runEmbeddingSimilarityEdges) {
                            // Document-similarity edges only require document node embeddings; do not
                            // synchronously backfill every extracted entity during crawl.
                            graphEdgeComputationService.backfillDocumentNodeEmbeddings(factSheetId);
                            graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                            // [FIX-2] Flush embedding-similarity edges to the vector store so they persist.
                            if (knowledgeGraphService != null) {
                                knowledgeGraphService.flushPendingNodes();
                                log.info("[Job {}] EDGE_COMPUTATION: flushed embedding-similarity edges to persistent store", job.getJobId());
                            }
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Embedding similarity edges computed");
                        } else {
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0,
                                    "Embedding similarity edges skipped; shared/name-based edges retained");
                        }
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
                        failPipelineStep(job, "EDGE_COMPUTATION",
                                "Embedding similarity edge computation failed: " + errorDetail);
                        log.warn("[Job {}] Embedding similarity edge computation failed: {}", job.getJobId(), errorDetail, e);
                        throw new IllegalStateException("Embedding similarity edge computation failed: " + errorDetail, e);
                    }
                }
                completePipelineStep(job, "EDGE_COMPUTATION", 2, "Graph edge computation complete");
                log.info("[Job {}] Graph edge computation complete", job.getJobId());
            } else {
                skipPipelineStep(job, "EDGE_COMPUTATION", "Graph edge computation disabled or unavailable");
            }

            // ── Phase 9: Post-crawl enrichment (PSL/MEBN MAP derivation + prune/compact + health) ──────
            // Skipped when graphWholesaleFailure: enrichment over an empty graph is a no-op.
            if (!graphWholesaleFailure[0] && stepPlan.isRun("ENRICHMENT")) {
                updateProgress(job, "ENRICHMENT", 83,
                        "Post-crawl enrichment starting",
                        "PSL/MEBN MAP derivation, pruning/compaction, ontology, health");
                if (graphHydrationOrchestrator == null) {
                    // C2: Make the silent-skip footgun visible. When the hydration orchestrator bean
                    // is absent, PSL/MEBN MAP derivation, Opinion-based pruning, and confidence
                    // accumulation all silently don't run. This leaves every edge at its cold-start
                    // prior (SPECULATIVE) with no chance to climb toward ESTABLISHED.
                    log.warn("[Job {}] ENRICHMENT: graphHydrationOrchestrator bean is absent — " +
                            "confidence/PSL/prune model will NOT run for this crawl. " +
                            "Cold-start Opinions remain at their initial priors. " +
                            "Ensure kompile-knowledge-graph is on the classpath and " +
                            "GraphHydrationOrchestrator is Spring-managed.", job.getJobId());
                    recordEvent(job, "ENRICHMENT", "WARN",
                            "Enrichment bean absent — confidence/PSL/prune skipped",
                            "graphHydrationOrchestrator is null: no MAP derivation, Opinion pruning, " +
                            "or confidence accumulation will run for this crawl");
                }
                if (graphHydrationOrchestrator != null && knowledgeGraphService != null) {
                    Long factSheetId = jobFactSheetId(job);
                    if (factSheetId != null) {
                        // deriveOntology step: derive/bind structural schema and materialize crawl
                        // entity types + type hierarchy before regrounding.
                        if (ontologyAutoProvisioner != null
                                && !Boolean.FALSE.equals(job.getRequest().getDeriveOntology())) {
                            try {
                                ontologyAutoProvisioner.provisionOntology(factSheetId);
                            } catch (RuntimeException e) {
                                log.warn("[Job {}] deriveOntology step failed (continuing enrichment): {}",
                                        job.getJobId(), e.toString());
                            }
                        }
                        try {
                            updateProgress(job, "ENRICHMENT", 83,
                                    "Post-crawl enrichment running", "PSL/MEBN hydration is active");
                            updatePipelineStep(job, "ENRICHMENT",
                                    UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                    0, GraphHydrationOrchestrator.TOTAL_STAGES,
                                    0, 0, 0, 0,
                                    GraphHydrationOrchestrator.STAGE_DERIVATION,
                                    "Post-crawl enrichment: PSL/MEBN MAP derivation, prune/compact, GNN scoring, ontology, health");
                            final int[] stagesDone = {0};
                            HydrationResult hr = graphHydrationOrchestrator.run(
                                    factSheetId,
                                    resolveHydrationConfig(job.getRequest()),
                                    (stageId, message) -> recordHydrationSubStageProgress(
                                            job, stageId, message, ++stagesDone[0],
                                            GraphHydrationOrchestrator.TOTAL_STAGES));
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                updatePipelineStep(job, "ENRICHMENT",
                                        UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                                        stagesDone[0], GraphHydrationOrchestrator.TOTAL_STAGES,
                                        0, 0, 0, 0, null, "Enrichment cancelled");
                                return;
                            }
                            String summary = "Enrichment complete: stages=" + hr.stagesRun()
                                    + " derived=" + hr.relationsDerived()
                                    + " retracted=" + hr.retractedAtomCount()
                                    + " prunedRetracted=" + hr.factsRetractedPruned()
                                    + " prunedConfidence=" + hr.factsConfidencePruned()
                                    + " merges=" + hr.mergesPerformed()
                                    + " orphans=" + hr.orphansRemoved()
                                    + " components=" + hr.componentNodesRemoved()
                                    + " gnnEdgesScored=" + hr.gnnEdgesScored();
                            // [FIX-2] Flush enrichment-derived edges/updates to the vector store.
                            try {
                                knowledgeGraphService.flushPendingNodes();
                                log.info("[Job {}] ENRICHMENT: flushed enrichment graph state to persistent store", job.getJobId());
                            } catch (Exception flushEx) {
                                log.warn("[Job {}] ENRICHMENT: non-fatal flush error: {}", job.getJobId(), flushEx.getMessage());
                            }
                            completePipelineStep(job, "ENRICHMENT",
                                    GraphHydrationOrchestrator.TOTAL_STAGES, summary);
                            recordEvent(job, "ENRICHMENT", "INFO", "Post-crawl enrichment complete", summary);
                            log.info("[Job {}] {}", job.getJobId(), summary);
                            // ── Phase 10: LEARNING (KGE training) — inline tracked crawl step ─────
                            // Mandate: crawl stays RUNNING until KGE finishes; progress (epoch/loss)
                            // surfaces in the crawl UI via CrawlProgressEvent.
                            if (kgeAfterEnrichment && kgeEmbeddingJobService != null && factSheetId != null) {
                                final long kgeFactSheetId = factSheetId;
                                final String kgeCrawlJobId = job.getJobId();
                                final int effectiveBatchSize = kgeBatchSize > 0 ? kgeBatchSize
                                        : KGEmbeddingConfig.TRANSE_DEFAULTS.batchSize();
                                final KGEmbeddingConfig kgeCfg =
                                        KGEmbeddingConfig.TRANSE_DEFAULTS
                                                .toBuilder()
                                                .batchSize(effectiveBatchSize)
                                                .build();

                                String batchDecision = "kge-training batch-size=" + effectiveBatchSize
                                        + " (crawlKgeBatchSize=" + kgeBatchSize + ")";
                                recordEvent(job, "LEARNING", "INFO", "KGE batch-size decision", batchDecision);
                                publishProgressEvent(job, CrawlProgressEvent.EventType.DECISION, batchDecision);

                                if (resourceGovernorAdapter != null && resourceGovernorAdapter.shouldThrottleHeavyMemory()) {
                                    String oomReason = resourceGovernorAdapter.memoryPressureReason();
                                    String oomMsg = "kge-training skipped by OOM floor: " + oomReason;
                                    log.warn("[Job {}] Post-enrichment KGE skipped — {}", kgeCrawlJobId, oomReason);
                                    recordEvent(job, "LEARNING", "WARN", "KGE skipped by OOM floor", oomMsg);
                                    publishProgressEvent(job, CrawlProgressEvent.EventType.DECISION, oomMsg);
                                    skipPipelineStep(job, "LEARNING", oomMsg);
                                } else {
                                    if (heavyMemoryCoordinator != null && heavyMemoryCoordinator.isEnabled()) {
                                        String gateMsg = "kge-training will acquire heavy-memory gate before training";
                                        recordEvent(job, "LEARNING", "INFO", "KGE gate decision", gateMsg);
                                        publishProgressEvent(job, CrawlProgressEvent.EventType.DECISION, gateMsg);
                                    }
                                    // Start LEARNING step — crawl job stays RUNNING during this.
                                    updateProgress(job, "LEARNING", 99,
                                            "KGE training starting", "factSheet=" + kgeFactSheetId);
                                    updatePipelineStep(job, "LEARNING",
                                            UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                            0, kgeCfg.epochs(), 0, 0, kgeCfg.epochs(), 0,
                                            "epoch 0/" + kgeCfg.epochs(),
                                            "KGE training starting (factSheet=" + kgeFactSheetId + ")");
                                    publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS,
                                            "KGE training starting");

                                    // Per-epoch callback: forward epoch/loss to the crawl UI.
                                    KgeTrainingExecutor.ProgressCallback kgeCallback =
                                            (cJobId, epoch, totalEpochs, loss) -> {
                                                job.getCurrentPhase().set("LEARNING");
                                                updateMemorySnapshot(job);
                                                String epochMsg = "KGE epoch " + epoch + "/" + totalEpochs
                                                        + " loss=" + String.format("%.4f", loss);
                                                updatePipelineStep(job, "LEARNING",
                                                        UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                                        epoch, totalEpochs, 0, epoch, totalEpochs, 0,
                                                        "epoch " + epoch + "/" + totalEpochs,
                                                        epochMsg);
                                                recordEvent(job, "LEARNING", "INFO", "KGE epoch progress", epochMsg);
                                                publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, epochMsg);
                                            };

                                    try {
                                        KGEmbeddingJob kgeJob =
                                                kgeEmbeddingJobService.trainSynchronously(
                                                        kgeCrawlJobId,
                                                        kgeFactSheetId,
                                                        KGEmbeddingAlgorithm.TRANSE,
                                                        kgeCfg,
                                                        kgeCallback);
                                        if (kgeJob.getStatus().name().equals("COMPLETED")) {
                                            String doneMsg = "KGE training complete: entities="
                                                    + (kgeJob.getEntitiesEmbedded() != null ? kgeJob.getEntitiesEmbedded() : "?")
                                                    + " relations=" + (kgeJob.getRelationsEmbedded() != null ? kgeJob.getRelationsEmbedded() : "?")
                                                    + " loss=" + (kgeJob.getCurrentLoss() != null
                                                            ? String.format("%.4f", kgeJob.getCurrentLoss()) : "?");
                                            try {
                                                if (reasoningGraphRegistrationService != null) {
                                                    int graphEntities = reasoningGraphRegistrationService
                                                            .registerReasoningGraphForFactSheet(kgeFactSheetId);
                                                    doneMsg += "; semanticGraphEntities=" + graphEntities;
                                                }
                                                if (graphHydrationOrchestrator != null) {
                                                    HydrationConfig defaults = HydrationConfig.defaults();
                                                    HydrationResult semanticPass = graphHydrationOrchestrator.run(
                                                            kgeFactSheetId,
                                                            new HydrationConfig(
                                                                    Set.of(GraphHydrationOrchestrator.STAGE_DERIVATION),
                                                                    defaults.confidencePruneThreshold(), false),
                                                            (stage, message) -> recordEvent(job, "LEARNING", "INFO",
                                                                    "Post-KGE " + stage, message));
                                                    doneMsg += "; semanticConsensusPass="
                                                            + (semanticPass.runId() != null ? "complete" : "skipped");
                                                }
                                            } catch (Exception semanticEx) {
                                                String semanticMessage = "Post-KGE semantic consensus failed (non-fatal): "
                                                        + semanticEx.getMessage();
                                                log.warn("[Job {}] {}", kgeCrawlJobId, semanticMessage, semanticEx);
                                                recordEvent(job, "LEARNING", "WARN",
                                                        "Post-KGE semantic consensus failed", semanticMessage);
                                                doneMsg += "; semanticConsensusPass=failed";
                                            }
                                            completePipelineStep(job, "LEARNING", kgeCfg.epochs(), doneMsg);
                                            recordEvent(job, "LEARNING", "INFO", "KGE training complete", doneMsg);
                                            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, doneMsg);
                                            log.info("[Job {}] {}", kgeCrawlJobId, doneMsg);
                                        } else {
                                            String failMsg = "KGE training ended with status=" + kgeJob.getStatus()
                                                    + ": " + kgeJob.getErrorMessage();
                                            failPipelineStep(job, "LEARNING", failMsg);
                                            recordEvent(job, "LEARNING", "WARN", "KGE training non-completed", failMsg);
                                            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, failMsg);
                                            log.warn("[Job {}] {}", kgeCrawlJobId, failMsg);
                                        }
                                    } catch (IllegalStateException alreadyRunning) {
                                        String skipMsg = "KGE skipped — training already running for factSheet=" + kgeFactSheetId;
                                        log.info("[Job {}] {}", kgeCrawlJobId, skipMsg);
                                        skipPipelineStep(job, "LEARNING", skipMsg);
                                        recordEvent(job, "LEARNING", "INFO", "KGE already running", skipMsg);
                                    } catch (Exception kgeEx) {
                                        String errMsg = "KGE training failed (non-fatal): " + kgeEx.getMessage();
                                        log.warn("[Job {}] {}", kgeCrawlJobId, kgeEx.getMessage(), kgeEx);
                                        failPipelineStep(job, "LEARNING", errMsg);
                                        recordEvent(job, "LEARNING", "WARN", "KGE training error", errMsg);
                                        publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, errMsg);
                                    }
                                }
                            } else {
                                skipPipelineStep(job, "LEARNING",
                                        "KGE training skipped: kgeAfterEnrichment="
                                                + kgeAfterEnrichment
                                                + ", service=" + (kgeEmbeddingJobService != null ? "present" : "absent")
                                                + ", factSheetId=" + factSheetId);
                            }
                        } catch (Exception e) {
                            log.warn("[Job {}] ENRICHMENT step failed (non-fatal): {}",
                                    job.getJobId(), e.getMessage(), e);
                            recordEvent(job, "ENRICHMENT", "WARN", "Enrichment failed (non-fatal)", e.getMessage());
                            skipPipelineStep(job, "ENRICHMENT", "Enrichment failed: " + e.getMessage());
                        } finally {
                            trimNativeMemory(job, "ENRICHMENT", "after post-crawl enrichment");
                        }
                    } else {
                        skipPipelineStep(job, "ENRICHMENT", "ENRICHMENT skipped: no fact sheet ID for job");
                    }
                } else {
                    skipPipelineStep(job, "ENRICHMENT",
                            "ENRICHMENT skipped: graph hydration orchestrator not available");
                }
            } else if (stepPlan.isArchive("ENRICHMENT")) {
                archiveCrawlStep(job, "ENRICHMENT", new ArrayList<>(), null, "Enrichment archived");
                log.warn("[Job {}] ENRICHMENT: step archived/excluded from step plan — " +
                        "confidence/PSL/prune model will NOT run for this crawl. " +
                        "Cold-start Opinions remain at their initial priors. " +
                        "Add ENRICHMENT to enabledSteps to activate.", job.getJobId());
            } else if (stepPlan.isSkip("ENRICHMENT")) {
                skipPipelineStep(job, "ENRICHMENT", "Enrichment skipped by step plan");
                // C2: Explicit skip — the footgun is intentional but still needs to be visible.
                log.warn("[Job {}] ENRICHMENT: step explicitly skipped by step plan — " +
                        "confidence/PSL/prune model will NOT run for this crawl. " +
                        "Cold-start Opinions remain at their initial priors.", job.getJobId());
            } else {
                skipPipelineStep(job, "ENRICHMENT", "Enrichment disabled or step plan excludes it");
                // C2: step plan did not enable ENRICHMENT — warn so the omission is not silent.
                log.warn("[Job {}] ENRICHMENT: step not enabled in step plan — " +
                        "confidence/PSL/prune model will NOT run for this crawl. " +
                        "Cold-start Opinions remain at their initial priors. " +
                        "Add ENRICHMENT to enabledSteps to activate.", job.getJobId());
            }

            int finalChunkCount = chunkedDocuments.size();
            if (retainResultGraph) {
                job.setResultGraph(unifiedGraph);
            } else {
                graphExtractionOrchestrator.releaseInMemoryGraph(unifiedGraph);
                job.setResultGraph(null);
                recordEvent(job, "COMPLETED", "INFO", "Released in-memory extraction graph",
                        "Persisted fact-sheet graph is the source of truth for UI/API graph results");
            }
            chunkedDocuments.clear();
            trimNativeMemory(job, "COMPLETED", "after releasing crawl document/result buffers");
            List<String> failedNames = job.getPipelineSteps().stream()
                    .filter(step -> step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.FAILED)
                    .map(step -> step.getStepId() != null ? step.getStepId() : step.getDisplayName())
                    .filter(Objects::nonNull)
                    .toList();
            // Proper degrading at all levels: DEGRADABLE steps (post-graph enhancements like KGE/LEARNING)
            // must NOT discard a crawl that already produced + persisted the graph — their failure degrades
            // the job to COMPLETED-with-warnings. Only a HARD step failure (loading/extraction/persistence)
            // fails the whole crawl. The degradable set is configurable (see degradableStepIds()).
            Set<String> degradable = degradableStepIds();
            List<String> hardFailed = failedNames.stream()
                    .filter(n -> !degradable.contains(n.toUpperCase(Locale.ROOT)))
                    .toList();
            boolean hasDeferredEmbedding = !job.getDeferredEmbeddingChunks().isEmpty();

            if (isCancelled(job)) {
                return;
            }

            if (!hardFailed.isEmpty()) {
                String failedStepsStr = String.join(", ", hardFailed);
                String errorMsg = "Crawl completed but pipeline step(s) FAILED: " + failedStepsStr;
                if (!job.getStatus().compareAndSet(
                        UnifiedCrawlJob.Status.RUNNING, UnifiedCrawlJob.Status.FAILED)) {
                    return;
                }
                log.error("[Job {}] {}", job.getJobId(), errorMsg);
                job.getCurrentPhase().set("FAILED");
                job.setErrorMessage(errorMsg);
                job.setCompletedAt(Instant.now());
                job.getErrors().add(errorMsg);
                recordEvent(job, "FAILED", "ERROR", "Pipeline step failure",
                        errorMsg + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                checkpointFailedJobForResume(job);
                publishProgressEvent(job, CrawlProgressEvent.EventType.ERROR, errorMsg);
            } else if (hasDeferredEmbedding) {
                recordDegradedSteps(job, failedNames);
                String message = "Crawl graph completed; "
                        + job.getDeferredEmbeddingChunks().size() + " chunk(s) pending bge-m3/vector embedding";
                if (!job.getStatus().compareAndSet(
                        UnifiedCrawlJob.Status.RUNNING,
                        UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING)) {
                    return;
                }
                log.warn("[Job {}] {}", job.getJobId(), message);
                job.getCurrentPhase().set("PENDING_EMBEDDING");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "PENDING_EMBEDDING", "WARN", "Unified crawl completed pending embedding",
                        message + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                publishGraphBuildCompletedEvent(job);
                publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, message);
            } else {
                recordDegradedSteps(job, failedNames);
                if (!job.getStatus().compareAndSet(
                        UnifiedCrawlJob.Status.RUNNING, UnifiedCrawlJob.Status.COMPLETED)) {
                    return;
                }
                job.getCurrentPhase().set("COMPLETED");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "COMPLETED", "INFO", "Unified crawl completed",
                        job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                publishGraphBuildCompletedEvent(job);
                publishProgressEvent(job, CrawlProgressEvent.EventType.COMPLETED, "Unified crawl completed");
            }
            log.info("Unified crawl job {} completed: {} docs, {} chunks, {} entities, {} relationships",
                    job.getJobId(), job.getDocumentsLoaded().get(), finalChunkCount,
                    job.getEntitiesExtracted().get(), job.getRelationshipsExtracted().get());

        } catch (Throwable e) {
            if (isCancelled(job)) {
                log.info("Unified crawl job {} acknowledged cancellation during phase {}",
                        job.getJobId(), job.getCurrentPhase().get());
                return;
            }
            if (!job.getStatus().compareAndSet(
                    UnifiedCrawlJob.Status.RUNNING, UnifiedCrawlJob.Status.FAILED)) {
                return;
            }
            log.error("Unified crawl job {} failed: {}", job.getJobId(), e.getMessage(), e);
            failPipelineStep(job, job.getCurrentPhase().get(), e.getClass().getSimpleName() + ": " + e.getMessage());
            job.getCurrentPhase().set("FAILED");
            job.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            job.setCompletedAt(Instant.now());
            recordEvent(job, "FAILED", "ERROR", "Unified crawl failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            checkpointFailedJobForResume(job);
            publishProgressEvent(job, CrawlProgressEvent.EventType.ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // Detach the per-job subprocess log listener registered at start.
            if (subprocessLogBus != null) {
                subprocessLogBus.unregister(subprocessSink);
            }
            if (vectorIndexingHelper != null) {
                vectorIndexingHelper.setActiveCrawlJobId(null);
            }
        }
    }

    private void waitForBackgroundGraphFuture(UnifiedCrawlJob job, Future<?> future, int index, int total) {
        long lastHeartbeatNanos = 0L;
        while (!isCancelled(job)) {
            try {
                updateProgress(job, "GRAPH_PREP", estimateProgress(job),
                        "Waiting for background graph task " + index + "/" + total, null);
                future.get(15, TimeUnit.SECONDS);
                return;
            } catch (TimeoutException e) {
                long now = System.nanoTime();
                if (now - lastHeartbeatNanos >= TimeUnit.SECONDS.toNanos(30)) {
                    lastHeartbeatNanos = now;
                    recordEvent(job, "GRAPH_PREP", "INFO",
                            "Still waiting for background graph task " + index + "/" + total,
                            "Rule-based graph preparation remains active");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        0, total, 0, 0, 0, 0, null,
                        "Interrupted while waiting for background graph preparation");
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                String errorDetail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                failPipelineStep(job, "GRAPH_PREP",
                        "Background graph task " + index + "/" + total + " failed: " + errorDetail);
                recordEvent(job, "GRAPH_PREP", "ERROR", "Background graph task failed",
                        "task=" + index + "/" + total + ", error=" + errorDetail);
                return;
            } catch (CancellationException e) {
                updatePipelineStep(job, "GRAPH_PREP", UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                        0, total, 0, 0, 0, 0, null, "Background graph task was cancelled");
                return;
            }
        }
    }

    @FunctionalInterface
    private interface PostProcessRunnable {
        void run() throws Exception;
    }

    private double entityResolutionSimilarityThreshold(GraphExtractionConfig config) {
        return graphPersistenceHelper.entityResolutionSimilarityThreshold(config);
    }

    private void surfaceCrawlResults(UnifiedCrawlJob job, List<Document> chunkedDocuments) {
        if (isCancelled(job)) return;
        int chunks = chunkedDocuments != null ? chunkedDocuments.size() : 0;
        updatePipelineStep(job, "SURFACING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, 1, 0, 0, 0, 0, null, "Surfacing raw crawl graph and chunk evidence");
        updateProgress(job, "SURFACING", estimateProgress(job), "Surfacing crawl output",
                job.getDocumentsLoaded().get() + " document(s), " + chunks + " chunk(s), "
                        + job.getEntitiesExtracted().get() + " entities, "
                        + job.getRelationshipsExtracted().get() + " relationships");
        completePipelineStep(job, "SURFACING", 1, "Raw crawl surface available before cleanup/enrichment");
        recordEvent(job, "SURFACING", "INFO", "Crawl surface ready",
                "Graph and chunk evidence persisted; cleanup/enrichment can run from this surface");
    }

    private void publishGraphBuildCompletedEvent(UnifiedCrawlJob job) {
        if (eventPublisher == null || job == null) return;
        try {
            Long factSheetId = jobFactSheetId(job);
            eventPublisher.publishEvent(new GraphBuildCompletedEvent(
                    this, job.getJobId(),
                    job.getEntitiesExtracted().get(),
                    job.getRelationshipsExtracted().get(),
                    factSheetId, job.snapshotEntityTypeCounts()));
        } catch (Exception ex) {
            log.warn("[Job {}] Failed to publish GraphBuildCompletedEvent: {}", job.getJobId(), ex.getMessage());
        }
    }

    // ---- Pipeline step tracking delegates ----

    private void initializePipelineSteps(UnifiedCrawlJob job) {
        pipelineStepTracker.initializePipelineSteps(job);
    }

    private UnifiedCrawlJob.PipelineStepProgress ensurePipelineStep(UnifiedCrawlJob job, String phase) {
        return pipelineStepTracker.ensurePipelineStep(job, phase);
    }

    private void updatePipelineStepFromCounters(UnifiedCrawlJob job, String phase, String message, String details) {
        pipelineStepTracker.updatePipelineStepFromCounters(job, phase, message, details);
    }

    private void updatePipelineStep(UnifiedCrawlJob job, String phase,
                                    UnifiedCrawlJob.PipelineStepStatus status,
                                    int completedItems, int totalItems, int failedItems,
                                    int completedBatches, int totalBatches, int currentBatchSize,
                                    String currentItem, String message) {
        pipelineStepTracker.updatePipelineStep(job, phase, status,
                completedItems, totalItems, failedItems,
                completedBatches, totalBatches, currentBatchSize, currentItem, message);
    }

    private void applyPipelineStepUpdate(UnifiedCrawlJob.PipelineStepProgress step,
                                          UnifiedCrawlJob.PipelineStepStatus status,
                                          int completedItems, int totalItems, int failedItems,
                                          int completedBatches, int totalBatches, int currentBatchSize,
                                          String currentItem, String message) {
        pipelineStepTracker.applyPipelineStepUpdate(step, status,
                completedItems, totalItems, failedItems,
                completedBatches, totalBatches, currentBatchSize, currentItem, message);
    }

    private void completePipelineStep(UnifiedCrawlJob job, String phase, int completedItems, String message) {
        pipelineStepTracker.completePipelineStep(job, phase, completedItems, message);
    }

    private void failPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        pipelineStepTracker.failPipelineStep(job, phase, message);
    }

    private void skipPipelineStep(UnifiedCrawlJob job, String phase, String message) {
        pipelineStepTracker.skipPipelineStep(job, phase, message);
    }

    /**
     * Pipeline step ids whose failure DEGRADES the crawl (COMPLETED-with-warning) rather than failing
     * the whole job — post-graph enhancements computed over an already-persisted graph (KGE/LEARNING
     * embedding training, and downstream reasoning hydration). Configurable on the fly via the
     * {@code kompile.crawl.degradableSteps} property (comma-separated, case-insensitive); default
     * {@code LEARNING,DERIVATION,ONTOLOGY_CONFORMANCE}. A hard step (loading/extraction/persistence)
     * is never degradable — its failure must fail the crawl.
     */
    private Set<String> degradableStepIds() {
        String prop = System.getProperty("kompile.crawl.degradableSteps",
                "LEARNING,DERIVATION,ONTOLOGY_CONFORMANCE");
        Set<String> out = new HashSet<>();
        for (String s : prop.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Surface degraded (failed-but-non-fatal) enhancement steps on a crawl that still completed: a
     * warning event in the per-job timeline + a DECISION progress event so it is visible in the crawl
     * UI, and an entry in the job's error list (informational, not fatal). No-op when nothing degraded.
     */
    private void recordDegradedSteps(UnifiedCrawlJob job, List<String> failedNames) {
        if (failedNames == null || failedNames.isEmpty()) {
            return;
        }
        String msg = "Crawl completed; optional step(s) degraded (skipped): "
                + String.join(", ", failedNames) + " — graph persisted, enhancement skipped";
        log.warn("[Job {}] {}", job.getJobId(), msg);
        job.getErrors().add(msg);
        recordEvent(job, "COMPLETED", "WARN", "Degraded optional step(s)", msg);
        publishProgressEvent(job, CrawlProgressEvent.EventType.DECISION, msg);
    }

    private void incrementPipelineStep(UnifiedCrawlJob job, String phase,
                                       int completedItemsDelta, int completedBatchesDelta, String message) {
        pipelineStepTracker.incrementPipelineStep(job, phase, completedItemsDelta, completedBatchesDelta, message);
    }

    // ---- Memory monitoring ----

    private boolean waitForMemoryCapacity(UnifiedCrawlJob job, String phase) {
        CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
        executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                llmDispatcher, executor, executorQueueCapacity);
        runtimeConfigManager.applyRequestOverrides(job.getRequest().getRuntimeConfig(), this, graphExtractionOrchestrator);
        updateMemorySnapshot(job);
        if (!hasMemoryPressure(job, false)) {
            return true;
        }

        String detail = memoryPressureDetail(job);
        job.getCurrentBatchStep().set("MEMORY_BACKPRESSURE");
        updatePipelineStepFromCounters(job, phase, "Memory backpressure", detail);
        recordEvent(job, phase, "WARN", "Memory pressure detected", detail);
        log.warn("[Job {}] Memory pressure before phase {}: {}", job.getJobId(), phase, detail);

        trimNativeMemory(job, phase, "memory backpressure");
        System.gc();
        updateMemorySnapshot(job);

        if (!hasMemoryPressure(job, true)) {
            updateMemorySnapshot(job);
            return true;
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, memoryWaitTimeoutSeconds));
        int iteration = 0;
        while (!isCancelled(job) && System.currentTimeMillis() < deadline) {
            try {
                long sleepMs = Math.min(5000L, 2000L + iteration * 1000L);
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            CrawlRuntimeConfigManager.CrawlRuntimeConfig refreshed = runtimeConfigManager.refreshRuntimeConfig();
            executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                    refreshed, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                    llmDispatcher, executor, executorQueueCapacity);
            runtimeConfigManager.applyRequestOverrides(job.getRequest().getRuntimeConfig(), this, graphExtractionOrchestrator);
            updateMemorySnapshot(job);
            if (iteration > 0 && iteration % 5 == 0) {
                System.gc();
            }
            updateProgress(job, phase, estimateProgress(job),
                    "Waiting for memory pressure to drop", memoryPressureDetail(job));
            if (!hasMemoryPressure(job, false)) {
                job.getCurrentBatchStep().set(null);
                return true;
            }
            iteration++;
        }
        recordEvent(job, phase, "WARN", "Continuing despite memory pressure",
                memoryPressureDetail(job) + " after waiting " + memoryWaitTimeoutSeconds + "s");
        return false;
    }

    private void updateProgress(UnifiedCrawlJob job, String phase, int progressPercent,
                                String message, String details) {
        job.getCurrentPhase().set(phase);
        int boundedProgress = Math.max(progressPercent, estimateProgressForPhase(job, phase));
        boundedProgress = Math.max(0, Math.min(100, boundedProgress));
        job.getProgressPercent().accumulateAndGet(boundedProgress, Math::max);
        updateMemorySnapshot(job);
        long now = System.nanoTime();
        if ((now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
            lastProgressEventNanos = now;
            if (message != null && !message.isBlank()) {
                recordEvent(job, phase, "INFO", message, details);
            }
            updatePipelineStepFromCounters(job, phase, message, details);
            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, message);
        }
    }

    /**
     * Publish a {@link CrawlProgressEvent} so {@code CrawlProgressSseController} can stream live per-step
     * progress — and the rolling LLM transcript carried in the snapshot — to connected SSE clients. The
     * 250ms throttle on the progress tick keeps the cadence sane; terminal callers fire once. Cheap when
     * nobody is subscribed (the SSE controller short-circuits on empty emitter lists) and never lets a
     * publish/serialization hiccup break the crawl.
     */
    private void publishProgressEvent(UnifiedCrawlJob job, CrawlProgressEvent.EventType eventType, String message) {
        if (eventPublisher == null || job == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new CrawlProgressEvent(
                    this, job.getJobId(), job.toProgressSnapshot(), eventType, message));
        } catch (Exception e) {
            log.debug("[Job {}] Failed to publish crawl progress event: {}", job.getJobId(), e.getMessage());
        }
    }

    private volatile long lastSubprocessLogEventNanos = 0L;

    /**
     * Relay a managed subprocess's own log/lifecycle event into this crawl's live stream.
     *
     * <p>Registered as a per-job {@link SubprocessLogSink} on the {@link SubprocessLogBus} for the
     * duration of {@link #executeJob}. Filters to events tagged with this job's id (subprocess
     * launchers stamp the jobId when started for a crawl), records them on the job transcript, and
     * publishes a crawl-progress event so the UI shows what the embedding/learning subprocess is
     * doing in real time. Lifecycle (spin-up/shutdown) and ERROR events bypass the throttle and are
     * surfaced as DECISION/PROGRESS immediately; ordinary log lines ride the shared 250 ms throttle
     * so a chatty subprocess can't flood the SSE stream. Runs on the subprocess reader thread, so it
     * is best-effort and never throws back into the bus.</p>
     */
    private void handleSubprocessLog(UnifiedCrawlJob job, SubprocessLogEvent ev) {
        if (job == null || ev == null) {
            return;
        }
        // Only surface events from a subprocess this crawl owns.
        if (ev.jobId() == null || !ev.jobId().equals(job.getJobId())) {
            return;
        }
        try {
            String phase = job.getCurrentPhase().get();
            boolean lifecycle = ev.stream() == SubprocessLogEvent.Stream.LIFECYCLE;
            boolean error = "ERROR".equals(ev.level());
            String label = "[" + ev.subprocessId() + "] " + ev.message();
            if (lifecycle || error) {
                recordEvent(job, phase, error ? "ERROR" : "INFO", label, null);
                publishProgressEvent(job, lifecycle
                        ? CrawlProgressEvent.EventType.DECISION
                        : CrawlProgressEvent.EventType.PROGRESS, label);
            } else {
                long now = System.nanoTime();
                if ((now - lastSubprocessLogEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
                    lastSubprocessLogEventNanos = now;
                    recordEvent(job, phase, ev.level(), label, null);
                    publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, label);
                }
            }
        } catch (Exception e) {
            log.debug("[Job {}] subprocess log relay failed: {}", job.getJobId(), e.getMessage());
        }
    }

    /**
     * Promote identifier signals (resolved during compaction) into first-class {@code IDENTIFIER}
     * nodes + {@code RESOLVES_TO} edges via IdentityGraphService, so automated crawls
     * materialize them — previously only the HTTP compact/advanced path did. Best-effort and
     * non-fatal: a failure here never fails the crawl.
     */
    private void materializeIdentifiers(UnifiedCrawlJob job, Long factSheetId) {
        if (identityGraphService == null || factSheetId == null) {
            return;
        }
        try {
            var result = identityGraphService.materialize(factSheetId);
            if (result.identifierNodes() > 0 || result.resolveEdges() > 0 || !result.collisions().isEmpty()) {
                log.info("[Job {}] Identifier graph materialized: {} IDENTIFIER node(s), {} RESOLVES_TO edge(s), {} collision(s)",
                        job.getJobId(), result.identifierNodes(), result.resolveEdges(), result.collisions().size());
                recordEvent(job, "ENTITY_RESOLUTION", "INFO", "Identifiers materialized",
                        result.identifierNodes() + " identifier node(s), " + result.resolveEdges()
                                + " resolves-to edge(s), " + result.collisions().size() + " collision(s)");
            }
        } catch (Exception e) {
            log.warn("[Job {}] Identifier materialization failed (non-fatal): {}",
                    job.getJobId(), e.getMessage());
            recordEvent(job, "ENTITY_RESOLUTION", "WARN", "Identifier materialization failed", e.getMessage());
        }
    }

    private void recordEntityResolutionProgress(
            UnifiedCrawlJob job, GraphCompactionService.CompactionProgress progress) {
        if (job == null || progress == null || isCancelled(job)) return;
        job.getCurrentPhase().set("ENTITY_RESOLUTION");
        updateMemorySnapshot(job);

        int total = Math.max(1, progress.total());
        int processed = Math.max(0, Math.min(total, progress.processed()));
        int phaseProgress = 72 + (int) Math.min(8, (processed * 8L) / total);
        job.getProgressPercent().accumulateAndGet(phaseProgress, Math::max);

        String currentItem = progress.blockType() != null && !progress.blockType().isBlank()
                ? progress.blockType() : progress.stage();
        String message = progress.message() != null && !progress.message().isBlank()
                ? progress.message() : "Entity resolution progress";
        String details = "stage=" + progress.stage()
                + ", processed=" + processed + "/" + total
                + ", block=" + progress.blockIndex() + "/" + progress.blockCount()
                + ", blockSize=" + progress.blockSize()
                + ", candidates=" + progress.candidates()
                + ", elapsedMs=" + progress.elapsedMs()
                + ", " + memoryPressureDetail(job);

        updatePipelineStep(job, "ENTITY_RESOLUTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                processed, total, 0, 0, 0, 0, currentItem, message);
        recordEvent(job, "ENTITY_RESOLUTION",
                progress.stage() != null && progress.stage().contains("MEMORY_PRESSURE") ? "WARN" : "INFO",
                message, details);
    }

    /**
     * Per-sub-stage progress callback for the ENRICHMENT step.
     *
     * <p>Called by {@link GraphHydrationOrchestrator} after each of the three top-level
     * hydration stages (DERIVATION / PRUNE_COMPACT / GNN_SCORING / ONTOLOGY_CONFORMANCE /
     * HEALTH). Updates the pipeline step
     * tracker with the completed sub-stage count and publishes a progress event through the
     * same 250 ms throttle used by all other pipeline steps.</p>
     *
     * @param job           the running crawl job
     * @param stageId       hydration stage ID ({@code DERIVATION}, {@code PRUNE_COMPACT},
     *                      {@code GNN_SCORING}, {@code ONTOLOGY_CONFORMANCE}, {@code HEALTH})
     * @param message       human-readable sub-stage summary from the orchestrator
     * @param stagesCompleted number of stages completed so far (1-based)
     * @param totalStages   total number of stages (from {@link GraphHydrationOrchestrator#TOTAL_STAGES})
     */
    private void recordHydrationSubStageProgress(UnifiedCrawlJob job, String stageId,
                                                  String message, int stagesCompleted,
                                                  int totalStages) {
        if (job == null || isCancelled(job)) return;
        job.getCurrentPhase().set("ENRICHMENT");
        updateMemorySnapshot(job);

        int boundedDone = Math.max(0, Math.min(totalStages, stagesCompleted));
        int total = Math.max(1, totalStages);
        int phaseProgress = 83 + (int) Math.min(16, (boundedDone * 16L) / total);
        job.getProgressPercent().accumulateAndGet(phaseProgress, Math::max);

        String fullMessage = "[" + stageId + "] " + (message != null ? message : "Post-crawl enrichment sub-stage");

        updatePipelineStep(job, "ENRICHMENT", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                boundedDone, total, 0, 0, 0, 0, stageId, fullMessage);
        recordEvent(job, "ENRICHMENT", "INFO", fullMessage, null);

        long now = System.nanoTime();
        if ((now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
            lastProgressEventNanos = now;
            publishProgressEvent(job, CrawlProgressEvent.EventType.PROGRESS, fullMessage);
        }
    }

    private static final long PROGRESS_EVENT_INTERVAL_NANOS = 250_000_000L;
    private volatile long lastProgressEventNanos = 0L;

    private void updateMemorySnapshot(UnifiedCrawlJob job) { memoryMonitor.updateMemorySnapshot(job); }
    private boolean hasMemoryPressure(UnifiedCrawlJob job, boolean critical) { return memoryMonitor.hasMemoryPressure(job, critical); }
    private boolean hasNativeMemoryPressure(UnifiedCrawlJob job, int thresholdPercent) { return memoryMonitor.hasNativeMemoryPressure(job, thresholdPercent); }
    private String memoryPressureDetail(UnifiedCrawlJob job) { return memoryMonitor.memoryPressureDetail(job); }

    private void trimNativeMemory(UnifiedCrawlJob job, String phase, String reason) {
        String detail = memoryMonitor.trimNativeMemory(job, phase, reason);
        if (detail != null) {
            recordEvent(job, phase, "INFO", "Native memory cleanup", detail);
            log.info("[Job {}] Native memory cleanup during {}: {}", job.getJobId(), phase, detail);
        }
    }

    // ---- Progress estimation ----

    private int estimateProgress(UnifiedCrawlJob job) {
        UnifiedCrawlJob.Status status = job.getStatus().get();
        if (status == UnifiedCrawlJob.Status.COMPLETED) return 100;
        if (status == UnifiedCrawlJob.Status.FAILED || status == UnifiedCrawlJob.Status.CANCELLED) {
            return job.getProgressPercent().get();
        }
        return estimateProgressForPhase(job, job.getCurrentPhase().get());
    }

    private int estimateProgressForPhase(UnifiedCrawlJob job, String phase) {
        if (phase == null || phase.equals("QUEUED")) return 0;
        if (phase.equals("DISCOVERING") || phase.equals("LOADING")) {
            List<UnifiedCrawlJob.SourceProgress> sourceProgress = job.getSourceProgress();
            int totalSources = sourceProgress != null ? sourceProgress.size() : 0;
            if (totalSources > 0) {
                int done = 0;
                for (UnifiedCrawlJob.SourceProgress sp : sourceProgress) {
                    UnifiedCrawlJob.Status s = sp.getStatus();
                    if (s == UnifiedCrawlJob.Status.COMPLETED || s == UnifiedCrawlJob.Status.FAILED) done++;
                }
                return 2 + (int) Math.min(18, (done * 18L) / totalSources);
            }
            return 5;
        }
        if (phase.equals("CONVERTING")) return 22;
        if (phase.equals("ROUTING") || phase.equals("GRAPH_PREP")) return 28;
        if (phase.equals("CHUNKING")) return 35;
        if (phase.equals("GRAPH_EXTRACTION")) {
            int total = graphExtractionOrchestrator.graphChunksTotal(job);
            int done = graphExtractionOrchestrator.normalizeGraphChunksProcessed(job);
            return total > 0 ? 40 + (int) Math.min(30, (done * 30L) / total) : 40;
        }
        if (phase.equals("SURFACING")) return 71;
        if (phase.equals("ENTITY_RESOLUTION")) {
            UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
            int total = step.getTotalItems().get();
            int done = step.getCompletedItems().get();
            return total > 0 ? 72 + (int) Math.min(8, (done * 8L) / total) : 72;
        }
        if (phase.equals("EDGE_COMPUTATION")) return 82;
        if (phase.equals("EMBEDDING") || phase.equals("INDEXING") || phase.equals("VECTOR_INDEXING")) {
            int total = job.getChunksQueuedForEmbedding().get();
            int done = Math.max(job.getChunksEmbedded().get(), job.getDocumentsIndexed().get());
            return total > 0 ? 85 + (int) Math.min(14, (done * 14L) / total) : 85;
        }
        if (phase.equals("ENRICHMENT")) {
            UnifiedCrawlJob.PipelineStepProgress step = ensurePipelineStep(job, phase);
            int total = step.getTotalItems().get();
            int done = step.getCompletedItems().get();
            // Progress band 83–99: 16 points over TOTAL_STAGES sub-stages
            return total > 0 ? 83 + (int) Math.min(16, (done * 16L) / total) : 83;
        }
        return Math.max(1, job.getProgressPercent().get());
    }

    // ---- Event delegates ----

    private void recordEvent(UnifiedCrawlJob job, String phase, String level, String message, String details) {
        documentTracker.recordEvent(job, phase, level, message, details);
    }

    // ---- Cross-index registration ----

    private void registerChunksInCrossIndex(UnifiedCrawlJob job, List<Document> chunkedDocuments) {
        if (crawlIndexTrackingCallback == null || chunkedDocuments == null || chunkedDocuments.isEmpty()) return;
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) return;

        try {
            Map<String, List<Document>> chunksBySource = new LinkedHashMap<>();
            Map<String, String> fileNameBySource = new LinkedHashMap<>();
            for (int i = 0; i < chunkedDocuments.size(); i++) {
                Document chunk = chunkedDocuments.get(i);
                Map<String, Object> meta = chunk.getMetadata();
                if (meta == null) continue;

                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath == null) continue;

                chunksBySource.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(chunk);

                if (!fileNameBySource.containsKey(sourcePath)) {
                    String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                            ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                    fileNameBySource.put(sourcePath, fileName);
                }
            }

            int totalRegistered = 0;
            for (Map.Entry<String, List<Document>> entry : chunksBySource.entrySet()) {
                String sourcePath = entry.getKey();
                List<Document> chunks = entry.getValue();
                String fileName = fileNameBySource.get(sourcePath);

                List<CrawlIndexTrackingCallback.CrawlPassageInfo> passages = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Document chunk = chunks.get(i);
                    String chunkId = chunk.getId();
                    if (chunkId == null) continue;
                    passages.add(new CrawlIndexTrackingCallback.CrawlPassageInfo(
                            chunkId, i, chunk.getText(), chunk.getMetadata()));
                }

                totalRegistered += crawlIndexTrackingCallback.registerDocumentAndPassages(
                        sourcePath, fileName, factSheetId, passages);
            }

            if (totalRegistered > 0) {
                log.info("[Job {}] Registered {} passage(s) across {} source(s) in cross-index tracker",
                        job.getJobId(), totalRegistered, chunksBySource.size());
            }
        } catch (Exception e) {
            log.warn("[Job {}] Cross-index registration failed (non-fatal): {}", job.getJobId(), e.getMessage());
        }
    }

    // ---- LLM dispatch delegates ----

    private String promptWithCapacityFallback(String prompt, String taskType, UnifiedCrawlJob job) {
        return llmDispatcher.promptWithCapacityFallback(prompt, taskType, job);
    }

    private void recordTokenUsage(UnifiedCrawlJob job, String backendId, String prompt, String response) {
        llmDispatcher.recordTokenUsage(job, backendId, prompt, response);
    }

    // ---- Queue management ----

    private void updateQueueSnapshots() {
        int active = runningJobIds.size();
        int queued = queuedSequences.size();
        for (UnifiedCrawlJob job : jobs.values()) {
            job.getActiveJobs().set(active);
            job.getQueuedJobs().set(queued);
            job.getMaxConcurrentJobs().set(Math.max(1, maxConcurrentJobs));
            job.getQueueCapacity().set(Math.max(1, queueCapacity));
            if (job.getStatus().get() == UnifiedCrawlJob.Status.PENDING) {
                Long seq = queuedSequences.get(job.getJobId());
                if (seq != null) {
                    long ahead = queuedSequences.values().stream().filter(other -> other < seq).count();
                    job.getQueuePosition().set((int) ahead + 1);
                }
            } else {
                job.getQueuePosition().set(0);
            }
        }
    }

    // ---- Source type helpers ----

    private void addSourceType(List<AvailableSourceType> types,
                               DocumentSourceDescriptor.SourceType sourceType,
                               String displayName, String description,
                               List<String> requiredProperties, List<String> optionalProperties) {
        types.add(new AvailableSourceType(sourceType.name(), displayName, description,
                isSourceTypeAvailable(sourceType), requiredProperties, optionalProperties));
    }

    private boolean isSourceTypeAvailable(DocumentSourceDescriptor.SourceType type) {
        if (type == DocumentSourceDescriptor.SourceType.DIRECTORY
                || type == DocumentSourceDescriptor.SourceType.FILE
                || type == DocumentSourceDescriptor.SourceType.URL
                || type == DocumentSourceDescriptor.SourceType.WEB_CRAWL) {
            return true;
        }
        return sourceLoadingService.hasLoaderFor(type) || sourceLoadingService.hasCrawlerFor(type);
    }

    // ---- Misc helpers ----

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    private boolean isCancelled(UnifiedCrawlJob job) {
        return job != null && job.isCancellationRequested();
    }

    private String humanizePhase(String phase) {
        return PipelineStepTracker.humanizePhase(phase);
    }

    /**
     * Converts the optional {@link UnifiedCrawlRequest.HydrationConfig} from the crawl request
     * into the orchestrator's {@link HydrationConfig}. When the request carries no hydration
     * config (null), {@link HydrationConfig#defaults()} is returned so the ENRICHMENT step
     * is unaffected for crawls that do not specify hydration settings.
     *
     * @param request the crawl request (may be null)
     * @return the resolved HydrationConfig to pass to the orchestrator
     */
    private HydrationConfig resolveHydrationConfig(UnifiedCrawlRequest request) {
        if (request == null || request.getHydration() == null) {
            return HydrationConfig.defaults();
        }
        UnifiedCrawlRequest.HydrationConfig h = request.getHydration();
        return new HydrationConfig(
                h.getEnabledStageIds() != null ? h.getEnabledStageIds() : Set.of(),
                h.getConfidencePruneThreshold() > 0 ? h.getConfidencePruneThreshold() : 0.4,
                h.isDryRun());
    }

    private static boolean isEmbeddingUnavailableError(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("lane unavailable") || msg.contains("DEVICE_ERROR")
                || msg.contains("Embedding lane") || msg.contains("allocation failed")
                || msg.contains("CUDA context") || msg.contains("Embedding sub-batch failed"))) {
            return true;
        }
        return isEmbeddingUnavailableError(t.getCause());
    }
}
