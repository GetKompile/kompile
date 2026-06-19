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
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.crawler.*;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.crawl.graph.preprocessing.PreprocessingPipelineRunner;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
    private final ConcurrentHashMap<String, Future<?>> jobFutures = new ConcurrentHashMap<>();
    // Token trackers delegated to CrawlLlmDispatcher
    private final ConcurrentHashMap<String, Long> queuedSequences = new ConcurrentHashMap<>();
    private final Set<String> runningJobIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong submitSequence = new AtomicLong(0L);
    private volatile ThreadPoolExecutor executor;
    private int executorQueueCapacity = -1;
    private final AtomicInteger t_counter_source = new AtomicInteger(0);

    // Shared thread pools -- reused across jobs to avoid per-job allocation/teardown overhead.
    // Sized once in initializeExecutor(), torn down in shutdownExecutor().
    private volatile ExecutorService sharedChunkingPool;
    private volatile ExecutorService sharedGraphExtractionPool;
    private volatile ExecutorService sharedSourceLoadPool;

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
    // Configurable truncation limits for inline LLM extraction (defaults equal the former
    // hard-coded values so behaviour is unchanged unless set via graph-extraction-config.json).
    volatile int crawlGraphExtractionMaxCharsPerChunk = 12_000;
    volatile int crawlGraphExtractionMaxCharsPerChunkVlm = 16_000;
    // Chunks-per-prompt grouping (1 = legacy one-LLM-call-per-chunk, bit-for-bit identical).
    volatile int graphExtractionChunksPerPrompt = 1;

    // Optional Spring dependencies

    @Autowired(required = false)
    private GraphConstructor graphConstructor;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private CrossDocumentRelationCallback crossDocumentRelationCallback;

    @Autowired(required = false)
    private List<ai.kompile.core.graphrag.DocumentGraphExtractor> documentGraphExtractors;

    @Autowired(required = false)
    private ai.kompile.knowledgegraph.service.GraphEdgeComputationService graphEdgeComputationService;

    @Autowired(required = false)
    private GraphCompactionService graphCompactionService;

    @Autowired(required = false)
    private CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private ProcessingCapacityTracker processingCapacityTracker;

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

    // CrawlBatchPlanner is used by CrawlDocumentChunkingService; no direct use here.

    @PostConstruct
    public synchronized void initializeExecutor() {
        CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
        executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                llmDispatcher, executor, executorQueueCapacity);
        pipelineStepTracker.setGraphConstructorPresent(graphConstructor != null);
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

        log.info("Unified crawl executor initialized: maxConcurrentJobs={}, queueCapacity={}, memoryWait={}%, memoryCritical={}%",
                threads, capacity, memoryWaitThresholdPercent, memoryCriticalThresholdPercent);
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

    @Override
    public UnifiedCrawlJob startJob(UnifiedCrawlRequest request) {
        CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
        executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                llmDispatcher, executor, executorQueueCapacity);
        runtimeConfigManager.applyRequestOverrides(request.getRuntimeConfig(), this);
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
        jobs.put(jobId, job);

        long sequence = submitSequence.incrementAndGet();
        queuedSequences.put(jobId, sequence);
        updateQueueSnapshots();

        try {
            Future<?> future = executor().submit(() -> runQueuedJob(job));
            jobFutures.put(jobId, future);
            updateQueueSnapshots();
        } catch (RejectedExecutionException e) {
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
        return executor;
    }

    private void runQueuedJob(UnifiedCrawlJob job) {
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
            runningJobIds.remove(jobId);
            jobFutures.remove(jobId);
            llmDispatcher.removeTracker(jobId);
            updateQueueSnapshots();
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
                        || j.getStatus().get() == UnifiedCrawlJob.Status.RUNNING)
                .collect(Collectors.toList());
    }

    @Override
    public boolean cancelJob(String jobId) {
        UnifiedCrawlJob job = jobs.get(jobId);
        if (job == null) return false;
        UnifiedCrawlJob.Status current = job.getStatus().get();
        if (current == UnifiedCrawlJob.Status.COMPLETED
                || current == UnifiedCrawlJob.Status.FAILED
                || current == UnifiedCrawlJob.Status.CANCELLED) {
            return false;
        }
        job.getStatus().set(UnifiedCrawlJob.Status.CANCELLED);
        String cancelledPhase = job.getCurrentPhase().get();
        UnifiedCrawlJob.PipelineStepProgress cancelledStep = ensurePipelineStep(job, cancelledPhase);
        if (cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.RUNNING
                || cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.BACKPRESSURE
                || cancelledStep.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.PENDING) {
            applyPipelineStepUpdate(cancelledStep, UnifiedCrawlJob.PipelineStepStatus.CANCELLED,
                    cancelledStep.getCompletedItems().get(), cancelledStep.getTotalItems().get(),
                    cancelledStep.getFailedItems().get(), cancelledStep.getCompletedBatches().get(),
                    cancelledStep.getTotalBatches().get(), cancelledStep.getCurrentBatchSize().get(),
                    cancelledStep.getCurrentItem().get(), "Job cancelled");
        }
        job.getCurrentPhase().set("CANCELLED");
        job.getProgressPercent().set(Math.max(job.getProgressPercent().get(), 0));
        job.setCompletedAt(Instant.now());
        queuedSequences.remove(jobId);
        Future<?> future = jobFutures.get(jobId);
        if (future != null) {
            future.cancel(false);
            if (current == UnifiedCrawlJob.Status.PENDING) {
                jobFutures.remove(jobId);
            }
        }
        recordEvent(job, "CANCELLED", "WARN", "Unified crawl job cancelled", null);
        updateQueueSnapshots();
        return true;
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
                queuedSequences.remove(jobId);
                Future<?> future = jobFutures.remove(jobId);
                if (future != null) {
                    future.cancel(false);
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
            graphExtractionOrchestrator.extractGraphFromDocuments(pending, config, g, job, sharedGraphExtractionPool);
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
        // On-demand archiving converts a job's deferred embedding chunks into a durable, restart-safe archive.
        if (!"VECTOR_INDEXING".equals(step)) {
            log.warn("[Job {}] On-demand archive is only supported for VECTOR_INDEXING (got {})", jobId, step);
            return null;
        }
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
    }

    @Override
    public void registerRehydratedJob(UnifiedCrawlJob job) {
        if (job != null && job.getJobId() != null) {
            jobs.putIfAbsent(job.getJobId(), job);
        }
    }

    @Override
    public List<CrawlStepArchiveService.ResumableCrawlJob> listResumableCrawlJobs() {
        return crawlStepArchiveService != null ? crawlStepArchiveService.listResumableCrawlJobs() : List.of();
    }

    /**
     * Archive a pipeline step's inputs to disk and mark it ARCHIVED. Falls back to SKIP (so the job
     * still completes) when no archive service is wired or the write fails.
     */
    private void archiveCrawlStep(UnifiedCrawlJob job, String stepId, List<Document> chunks,
                                  Object config, String message) {
        if (crawlStepArchiveService != null) {
            try {
                String dir = crawlStepArchiveService.archive(job, stepId, chunks, config);
                pipelineStepTracker.archivePipelineStep(job, stepId,
                        dir != null ? message : message + " (archive write failed)");
                if (dir != null) {
                    recordEvent(job, stepId, "INFO", "Step archived for later", message);
                    return;
                }
            } catch (Exception e) {
                log.warn("[Job {}] Failed to archive step {}: {}", job.getJobId(), stepId, e.getMessage(), e);
            }
        }
        skipPipelineStep(job, stepId, message + " (archive unavailable — skipped)");
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
                graphExtractionOrchestrator.extractGraphFromDocuments(chunks, cfg, g, job, sharedGraphExtractionPool);
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
                graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 2);
                return 1;
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
        try {
            CrawlRuntimeConfigManager.CrawlRuntimeConfig config = runtimeConfigManager.refreshRuntimeConfig();
            executorQueueCapacity = runtimeConfigManager.applyRuntimeConfig(
                    config, this, memoryMonitor, graphExtractionOrchestrator, vectorIndexingHelper,
                    llmDispatcher, executor, executorQueueCapacity);
            job.getStatus().set(UnifiedCrawlJob.Status.RUNNING);
            job.setStartedAt(Instant.now());
            initializePipelineSteps(job);
            CrawlStepPlan stepPlan = CrawlStepPlan.from(job.getRequest());
            try {
                stepPlan.validate();
            } catch (IllegalArgumentException e) {
                failPipelineStep(job, "LOADING", "Invalid step selection: " + e.getMessage());
                throw new IllegalStateException("Invalid crawl step plan: " + e.getMessage(), e);
            }
            recordEvent(job, "LOADING", "INFO", "Pipeline step plan", stepPlan.actions().toString());
            updateMemorySnapshot(job);

            TokenBudgetTracker tokenTracker = new TokenBudgetTracker();
            tokenTracker.registerBackend("default");
            llmDispatcher.registerTracker(job.getJobId(), tokenTracker);
            updateProgress(job, "LOADING", 1, "Starting source loading",
                    job.getRequest().getSources().size() + " source(s)");

            // Phase 1: Crawl/load documents from all sources
            Map<String, List<Document>> docsBySource = new LinkedHashMap<>();
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
            allDocuments = new ArrayList<>(contentTypeRouter.routeByContentType(job, allDocuments));
            job.getCurrentFile().set(null);
            completePipelineStep(job, "ROUTING", docGraphDocs.size(),
                    allDocuments.size() + " document(s) routed to text pipeline");
            updateProgress(job, "ROUTING", estimateProgress(job),
                    "Content routing complete", allDocuments.size() + " text-pipeline document(s)");
            log.info("[Job {}] Content routing complete: {} documents for text pipeline", job.getJobId(), allDocuments.size());

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

            VectorIndexConfig indexConfig = job.getRequest().getVectorIndex();
            final List<Document> chunksForIndex = new ArrayList<>(chunkedDocuments);
            boolean doVectorIndex = stepPlan.isRun("VECTOR_INDEXING") && indexConfig != null && indexConfig.isEnabled()
                    && vectorStore != null && !chunksForIndex.isEmpty();
            if (doVectorIndex) {
                updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.PENDING,
                        0, chunksForIndex.size(), 0, 0, 0, 0, null,
                        "Queued until after crawl graph cleanup");
                recordEvent(job, "VECTOR_INDEXING", "INFO",
                        "Vector indexing queued after graph cleanup",
                        chunksForIndex.size() + " chunk(s)");
            }

            // Phase 6: LLM graph extraction
            Graph unifiedGraph = new Graph();
            unifiedGraph.setId(job.getJobId());
            unifiedGraph.setEntities(new ArrayList<>());
            unifiedGraph.setRelationships(new ArrayList<>());
            unifiedGraph.setCommunities(new ArrayList<>());

            GraphExtractionConfig graphConfig = job.getRequest().getGraphExtraction();

            {
                boolean graphRequested = graphConfig != null && graphConfig.isEnabled();
                boolean vectorRequested = job.getRequest().getVectorIndex() != null
                        && job.getRequest().getVectorIndex().isEnabled();
                StringBuilder configReport = new StringBuilder();
                configReport.append("Crawl configuration for job ").append(job.getJobId()).append(":\n");
                configReport.append("  Graph extraction: ").append(graphRequested ? "ENABLED" : "DISABLED").append("\n");
                configReport.append("  Vector indexing:  ").append(vectorRequested ? "ENABLED" : "DISABLED").append("\n");
                configReport.append("  GraphConstructor: ").append(graphConstructor != null ? "AVAILABLE" : "NOT CONFIGURED").append("\n");
                configReport.append("  LLMChat:          ").append(llmChat != null ? "AVAILABLE (" + llmChat.getClass().getSimpleName() + ")" : "NOT CONFIGURED").append("\n");
                configReport.append("  KnowledgeGraph:   ").append(knowledgeGraphService != null ? "AVAILABLE" : "NOT CONFIGURED");
                log.info("[Job {}] {}", job.getJobId(), configReport);
                recordEvent(job, job.getCurrentPhase().get(), "INFO",
                        "Configuration summary",
                        "graphExtraction=" + (graphRequested ? "ON" : "OFF")
                                + ", vectorIndex=" + (vectorRequested ? "ON" : "OFF")
                                + ", graphConstructor=" + (graphConstructor != null ? "YES" : "NO")
                                + ", llm=" + (llmChat != null ? llmChat.getClass().getSimpleName() : "NONE")
                                + ", knowledgeGraph=" + (knowledgeGraphService != null ? "YES" : "NO"));

                if (graphRequested && graphConstructor == null && llmChat == null) {
                    String warnMsg = "Graph extraction was requested but NO LLM or GraphConstructor is configured. "
                            + "Graph extraction will be SKIPPED. Configure an LLM provider to enable it.";
                    log.warn("[Job {}] {}", job.getJobId(), warnMsg);
                    job.getErrors().add(warnMsg);
                    job.getErrorCount().incrementAndGet();
                    recordEvent(job, "GRAPH_EXTRACTION", "WARN", warnMsg, null);
                }
            }

            if (stepPlan.isRun("GRAPH_EXTRACTION") && graphConfig != null && graphConfig.isEnabled()
                    && (graphConstructor != null || llmChat != null)) {

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
                log.info("[Job {}] Starting LLM graph extraction for {} chunks (vector indexing queued after graph cleanup)",
                        job.getJobId(), chunkedDocuments.size());
                graphExtractionOrchestrator.extractGraphFromDocuments(chunkedDocuments, graphConfig, unifiedGraph, job, sharedGraphExtractionPool);
                job.getCurrentFile().set(null);

                int extractedEntities = job.getEntitiesExtracted().get();
                int extractedRelationships = job.getRelationshipsExtracted().get();
                int graphErrors = job.getErrorCount().get();
                String graphSummary = extractedEntities + " entities, " + extractedRelationships + " relationships";

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
                trimNativeMemory(job, "GRAPH_EXTRACTION", "after graph extraction");
            } else if (stepPlan.isArchive("GRAPH_EXTRACTION")) {
                archiveCrawlStep(job, "GRAPH_EXTRACTION", new ArrayList<>(chunkedDocuments), graphConfig,
                        chunkedDocuments.size() + " chunk(s) archived for later graph extraction");
            } else if (stepPlan.isSkip("GRAPH_EXTRACTION")) {
                skipPipelineStep(job, "GRAPH_EXTRACTION", "Graph extraction skipped by step plan");
            } else {
                if (graphConfig == null || !graphConfig.isEnabled()) {
                    skipPipelineStep(job, "GRAPH_EXTRACTION", "Graph extraction disabled in request configuration");
                } else {
                    skipPipelineStep(job, "GRAPH_EXTRACTION",
                            "Graph extraction SKIPPED: no LLM or GraphConstructor configured. "
                                    + "GraphConstructor=" + (graphConstructor != null) + ", LLMChat=" + (llmChat != null));
                }
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
            surfaceCrawlResults(job, chunkedDocuments);
            trimNativeMemory(job, "GRAPH_PREP", "before entity resolution");

            if (isCancelled(job)) return;

            // Phase 6.5: Entity resolution
            GraphExtractionConfig graphConfigForResolution = job.getRequest().getGraphExtraction();
            if (stepPlan.isRun("ENTITY_RESOLUTION") && graphCompactionService != null && graphConfigForResolution != null
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
                    if (useEmbeddingResolution && (!memoryReady || hasNativeMemoryPressure(job, nativeMemoryWaitThresholdPercent))) {
                        useEmbeddingResolution = false;
                        recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                "Embedding-assisted entity resolution disabled for memory pressure",
                                memoryPressureDetail(job));
                    }
                    if (useEmbeddingResolution) {
                        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                        if (!vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                            useEmbeddingResolution = false;
                            recordEvent(job, "ENTITY_RESOLUTION", "WARN",
                                    "Embedding-assisted entity resolution skipped because embedding model is not ready",
                                    vectorIndexingHelper.embeddingModelNotReadyReason(embModel));
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
                    log.warn("[Job {}] Graph compaction failed (non-fatal): {}", job.getJobId(), errorDetail, e);
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
            boolean doEdgeComputation = stepPlan.isRun("EDGE_COMPUTATION")
                    && graphEdgeComputationService != null && knowledgeGraphService != null;
            if (!doEdgeComputation && stepPlan.isArchive("EDGE_COMPUTATION")) {
                archiveCrawlStep(job, "EDGE_COMPUTATION", new ArrayList<>(), null,
                        "Edge computation archived to run later");
            } else if (!doEdgeComputation && stepPlan.isSkip("EDGE_COMPUTATION")) {
                skipPipelineStep(job, "EDGE_COMPUTATION", "Edge computation skipped by step plan");
            }

            Future<?> sharedEdgeFuture = null;
            if (doEdgeComputation) {
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
                        graphEdgeComputationService.computeSharedEntityEdges(factSheetId, 2);
                        incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Shared entity edges computed");
                        log.info("[Job {}] Shared entity edge computation complete", job.getJobId());
                    } catch (Exception e) {
                        log.warn("[Job {}] Shared entity edge computation failed: {}", job.getJobId(), e.getMessage());
                    }
                });
                edgePool.shutdown();
            }

            if (doVectorIndex) {
                EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                if (!vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                    vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                            "Embedding model not ready: " + vectorIndexingHelper.embeddingModelNotReadyReason(embModel));
                } else if (vectorIndexingHelper.shouldDeferForGpu()) {
                    // GPU VRAM has no headroom right now — defer embedding (a heavy local workload)
                    // instead of risking OOM. DeferredEmbeddingResumer drains it when VRAM frees.
                    log.info("[Job {}] Deferring vector indexing — GPU under pressure; will resume when VRAM frees",
                            job.getJobId());
                    vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                            "GPU under pressure — embedding deferred for resource recovery");
                } else {
                    try {
                        job.getCurrentFile().set("(indexing " + chunksForIndex.size() + " chunks)");
                        log.info("[Job {}] Indexing {} chunks to vector store (concurrent with edge computation)",
                                job.getJobId(), chunksForIndex.size());
                        vectorIndexingHelper.indexDocuments(chunksForIndex, indexConfig, job);
                        log.info("[Job {}] Vector indexing complete", job.getJobId());
                    } catch (Exception e) {
                        String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.warn("[Job {}] Vector indexing deferred after failure: {}", job.getJobId(), errorDetail, e);
                        vectorIndexingHelper.deferVectorIndexing(job, chunksForIndex, indexConfig,
                                "Vector indexing failed and was deferred: " + errorDetail);
                    }
                }
                chunksForIndex.clear();
            } else if (stepPlan.isArchive("VECTOR_INDEXING") && !chunksForIndex.isEmpty()) {
                archiveCrawlStep(job, "VECTOR_INDEXING", new ArrayList<>(chunksForIndex), indexConfig,
                        chunksForIndex.size() + " chunk(s) archived for later embedding");
                chunksForIndex.clear();
            } else {
                chunksForIndex.clear();
                skipPipelineStep(job, "VECTOR_INDEXING", stepPlan.isSkip("VECTOR_INDEXING")
                        ? "Vector indexing skipped by step plan" : "Vector indexing disabled or unavailable");
            }
            job.getCurrentFile().set(null);

            if (doEdgeComputation) {
                if (sharedEdgeFuture != null) {
                    try { sharedEdgeFuture.get(300, TimeUnit.SECONDS); }
                    catch (Exception e) { log.warn("[Job {}] Shared edge future: {}", job.getJobId(), e.getMessage()); }
                }
                if (!isCancelled(job)) {
                    try {
                        boolean memoryReady = waitForMemoryCapacity(job, "EDGE_COMPUTATION");
                        Long factSheetId = jobFactSheetId(job);
                        EmbeddingModel embModel = vectorIndexingHelper.primaryEmbeddingModel();
                        if (memoryReady && vectorIndexingHelper.isEmbeddingModelReady(embModel)) {
                            graphEdgeComputationService.computeEmbeddingSimilarityEdges(factSheetId, 0.7, 10);
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0, "Embedding similarity edges computed");
                        } else {
                            incrementPipelineStep(job, "EDGE_COMPUTATION", 1, 0,
                                    "Embedding similarity edges skipped until embedding model is ready");
                            recordEvent(job, "EDGE_COMPUTATION", "WARN", "Embedding similarity edges skipped",
                                    memoryReady ? vectorIndexingHelper.embeddingModelNotReadyReason(embModel) : memoryPressureDetail(job));
                        }
                    } catch (Exception e) {
                        log.warn("[Job {}] Embedding similarity edge computation failed: {}", job.getJobId(), e.getMessage());
                    }
                }
                completePipelineStep(job, "EDGE_COMPUTATION", 2, "Graph edge computation complete");
                log.info("[Job {}] Graph edge computation complete", job.getJobId());
            } else {
                skipPipelineStep(job, "EDGE_COMPUTATION", "Graph edge computation disabled or unavailable");
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
            boolean hasFailedSteps = job.getPipelineSteps().stream()
                    .anyMatch(step -> step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.FAILED);
            boolean hasDeferredEmbedding = !job.getDeferredEmbeddingChunks().isEmpty();

            if (hasFailedSteps) {
                List<String> failedNames = job.getPipelineSteps().stream()
                        .filter(step -> step.getStatus().get() == UnifiedCrawlJob.PipelineStepStatus.FAILED)
                        .map(step -> step.getStepId() != null ? step.getStepId() : step.getDisplayName())
                        .toList();
                String failedStepsStr = String.join(", ", failedNames);
                String errorMsg = "Crawl completed but pipeline step(s) FAILED: " + failedStepsStr;
                log.error("[Job {}] {}", job.getJobId(), errorMsg);
                job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
                job.getCurrentPhase().set("FAILED");
                job.setErrorMessage(errorMsg);
                job.setCompletedAt(Instant.now());
                job.getErrors().add(errorMsg);
                recordEvent(job, "FAILED", "ERROR", "Pipeline step failure",
                        errorMsg + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
            } else if (hasDeferredEmbedding) {
                String message = "Crawl graph completed; "
                        + job.getDeferredEmbeddingChunks().size() + " chunk(s) pending bge-m3/vector embedding";
                log.warn("[Job {}] {}", job.getJobId(), message);
                job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING);
                job.getCurrentPhase().set("PENDING_EMBEDDING");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "PENDING_EMBEDDING", "WARN", "Unified crawl completed pending embedding",
                        message + ". " + job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                publishGraphBuildCompletedEvent(job);
            } else {
                job.getStatus().set(UnifiedCrawlJob.Status.COMPLETED);
                job.getCurrentPhase().set("COMPLETED");
                job.getProgressPercent().set(100);
                job.setCompletedAt(Instant.now());
                recordEvent(job, "COMPLETED", "INFO", "Unified crawl completed",
                        job.getDocumentsLoaded().get() + " docs, " + finalChunkCount + " chunks");
                publishGraphBuildCompletedEvent(job);
            }
            log.info("Unified crawl job {} completed: {} docs, {} chunks, {} entities, {} relationships",
                    job.getJobId(), job.getDocumentsLoaded().get(), finalChunkCount,
                    job.getEntitiesExtracted().get(), job.getRelationshipsExtracted().get());

        } catch (Throwable e) {
            log.error("Unified crawl job {} failed: {}", job.getJobId(), e.getMessage(), e);
            failPipelineStep(job, job.getCurrentPhase().get(), e.getClass().getSimpleName() + ": " + e.getMessage());
            job.getStatus().set(UnifiedCrawlJob.Status.FAILED);
            job.getCurrentPhase().set("FAILED");
            job.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            job.setCompletedAt(Instant.now());
            recordEvent(job, "FAILED", "ERROR", "Unified crawl failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
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
            eventPublisher.publishEvent(new ai.kompile.core.graphbuilder.GraphBuildCompletedEvent(
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
        if (phase.equals("ENRICHMENT")) return 99;
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
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    private String humanizePhase(String phase) {
        return PipelineStepTracker.humanizePhase(phase);
    }
}
