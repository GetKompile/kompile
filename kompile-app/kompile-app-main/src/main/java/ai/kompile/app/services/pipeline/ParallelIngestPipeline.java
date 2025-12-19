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

package ai.kompile.app.services.pipeline;

import ai.kompile.app.subprocess.IngestCheckpointService;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Parallel ingest pipeline with separate stages for chunking, embedding, and
 * indexing.
 *
 * <h2>Architecture</h2>
 * 
 * <pre>
 * Phase 1: PARALLEL CHUNKING (ForkJoinPool)
 *   - Documents are chunked concurrently
 *   - Each document is independent, allowing full parallelization
 *
 * Phase 2: PARALLEL EMBEDDING (ThreadPoolExecutor)
 *   - Chunks are embedded in parallel batches
 *   - Uses producer-consumer pattern with BlockingQueue
 *   - Multiple embedding workers process batches concurrently
 *
 * Phase 3: SEQUENTIAL INDEXING (Single Thread)
 *   - Embedded chunks are written to Lucene index
 *   - Sequential due to IndexWriter thread-safety constraints
 *   - Consumes from embedding output queue
 * </pre>
 *
 * <h2>Producer-Consumer Flow</h2>
 * 
 * <pre>
 * [Chunking] --&gt; [chunkQueue] --&gt; [Embedding Workers] --&gt; [embeddedQueue] --&gt; [Indexing]
 * </pre>
 */
public class ParallelIngestPipeline implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ParallelIngestPipeline.class);

    // ========== DEFAULT VALUES (used when no configuration provided) ==========
    // These are fallback values - prefer using PipelineConfig for customization.
    private static final int DEFAULT_MIN_BATCH_SIZE = 1;
    private static final int DEFAULT_BATCH_SIZE = 16;    // Increased from 4
    private static final int DEFAULT_MAX_BATCH_SIZE = 64; // Increased from 8

    private static final int DEFAULT_QUEUE_CAPACITY = 1000; // Increased from 500
    private static final long DEFAULT_QUEUE_POLL_TIMEOUT_MS = 50;

    private static final long DEFAULT_MAX_BATCH_WAIT_MS = 500;
    private static final long DEFAULT_MIN_BATCH_WAIT_MS = 25;

    private static final int DEFAULT_CHUNKING_THREADS = Math.min(Runtime.getRuntime().availableProcessors() / 2, 16);
    private static final int DEFAULT_EMBEDDING_THREADS = 1; // Single thread for OpenMP
    private static final int DEFAULT_INDEXING_THREADS = 4;
    private static final int DEFAULT_INDEXING_BATCH_ACCUMULATION = 8; // Increased from 4

    // ========== PROGRESS REPORTING ==========
    private static final int PROGRESS_REPORT_INTERVAL = 5;  // Report every 5 items for finer granularity
    private static final long PROGRESS_REPORT_INTERVAL_MS = 50;  // 50ms for more frequent updates

    /**
     * Configuration record for pipeline settings.
     * All values are configurable - use this to tune performance for your hardware.
     */
    public record PipelineConfig(
            int minBatchSize,
            int defaultBatchSize,
            int maxBatchSize,
            int queueCapacity,
            long queuePollTimeoutMs,
            long maxBatchWaitMs,
            long minBatchWaitMs,
            int chunkingThreads,
            int embeddingThreads,
            int indexingThreads,
            int indexingBatchAccumulationSize,
            boolean parallelIndexingEnabled
    ) {
        /**
         * Creates a default configuration with reasonable defaults.
         */
        public static PipelineConfig defaults() {
            return new PipelineConfig(
                    DEFAULT_MIN_BATCH_SIZE,
                    DEFAULT_BATCH_SIZE,
                    DEFAULT_MAX_BATCH_SIZE,
                    DEFAULT_QUEUE_CAPACITY,
                    DEFAULT_QUEUE_POLL_TIMEOUT_MS,
                    DEFAULT_MAX_BATCH_WAIT_MS,
                    DEFAULT_MIN_BATCH_WAIT_MS,
                    DEFAULT_CHUNKING_THREADS,
                    DEFAULT_EMBEDDING_THREADS,
                    DEFAULT_INDEXING_THREADS,
                    DEFAULT_INDEXING_BATCH_ACCUMULATION,
                    true
            );
        }

        /**
         * Creates a high-throughput configuration for systems with more memory.
         */
        public static PipelineConfig highThroughput() {
            int cores = Runtime.getRuntime().availableProcessors();
            return new PipelineConfig(
                    1,
                    32,     // Larger batches
                    128,    // Higher max
                    2000,   // Larger queue
                    50,
                    500,
                    25,
                    Math.min(cores, 16),
                    1,      // Still single thread for OpenMP
                    Math.min(cores / 2, 8),  // More indexing threads
                    16,     // More batch accumulation
                    true
            );
        }

        /**
         * Creates a low-memory configuration for constrained systems.
         */
        public static PipelineConfig lowMemory() {
            return new PipelineConfig(
                    1,
                    4,      // Small batches
                    16,     // Lower max
                    250,    // Smaller queue
                    50,
                    500,
                    25,
                    2,      // Fewer threads
                    1,
                    2,
                    4,
                    true
            );
        }

        /**
         * Builder for custom configuration.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int minBatchSize = DEFAULT_MIN_BATCH_SIZE;
            private int defaultBatchSize = DEFAULT_BATCH_SIZE;
            private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
            private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
            private long queuePollTimeoutMs = DEFAULT_QUEUE_POLL_TIMEOUT_MS;
            private long maxBatchWaitMs = DEFAULT_MAX_BATCH_WAIT_MS;
            private long minBatchWaitMs = DEFAULT_MIN_BATCH_WAIT_MS;
            private int chunkingThreads = DEFAULT_CHUNKING_THREADS;
            private int embeddingThreads = DEFAULT_EMBEDDING_THREADS;
            private int indexingThreads = DEFAULT_INDEXING_THREADS;
            private int indexingBatchAccumulationSize = DEFAULT_INDEXING_BATCH_ACCUMULATION;
            private boolean parallelIndexingEnabled = true;

            public Builder minBatchSize(int v) { this.minBatchSize = v; return this; }
            public Builder defaultBatchSize(int v) { this.defaultBatchSize = v; return this; }
            public Builder maxBatchSize(int v) { this.maxBatchSize = v; return this; }
            public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
            public Builder queuePollTimeoutMs(long v) { this.queuePollTimeoutMs = v; return this; }
            public Builder maxBatchWaitMs(long v) { this.maxBatchWaitMs = v; return this; }
            public Builder minBatchWaitMs(long v) { this.minBatchWaitMs = v; return this; }
            public Builder chunkingThreads(int v) { this.chunkingThreads = v; return this; }
            public Builder embeddingThreads(int v) { this.embeddingThreads = v; return this; }
            public Builder indexingThreads(int v) { this.indexingThreads = v; return this; }
            public Builder indexingBatchAccumulationSize(int v) { this.indexingBatchAccumulationSize = v; return this; }
            public Builder parallelIndexingEnabled(boolean v) { this.parallelIndexingEnabled = v; return this; }

            public PipelineConfig build() {
                return new PipelineConfig(
                        minBatchSize, defaultBatchSize, maxBatchSize, queueCapacity,
                        queuePollTimeoutMs, maxBatchWaitMs, minBatchWaitMs,
                        chunkingThreads, embeddingThreads, indexingThreads,
                        indexingBatchAccumulationSize, parallelIndexingEnabled
                );
            }
        }
    }

    // Pipeline configuration
    private final PipelineConfig pipelineConfig;

    // Pipeline components
    private final AdaptivePipelineConfig config;
    private final TextChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final IndexerService indexerService;
    private final Map<String, Object> chunkingOptions;
    private final int batchSize;

    // Adaptive batch accumulator for efficient embedding
    private final AdaptiveBatchAccumulator<RetrievedDoc> batchAccumulator;

    // Thread pools
    private final ExecutorService chunkingExecutor;
    private final ExecutorService embeddingExecutor;
    private final ExecutorService indexingExecutor;
    private final int chunkingParallelism;
    private final int embeddingParallelism;
    private final int indexingParallelism;
    private final boolean parallelIndexingEnabled;

    // Vector-only mode: when true, skip keyword index updates (for vector population from existing index)
    private final boolean vectorOnlyMode;

    // Producer-consumer queues - individual chunks flow through pipeline
    private final BlockingQueue<RetrievedDoc> chunkQueue;
    private final BlockingQueue<EmbeddedBatch> embeddedQueue;

    // Progress tracking (thread-safe)
    private final AtomicInteger documentsProcessed = new AtomicInteger(0);
    private final AtomicInteger chunksCreated = new AtomicInteger(0);
    private final AtomicInteger chunksEmbedded = new AtomicInteger(0);
    private final AtomicInteger chunksIndexed = new AtomicInteger(0);
    private final AtomicInteger embeddingBatchesProcessed = new AtomicInteger(0);
    private final AtomicInteger indexingBatchesProcessed = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong lastProgressReportMs = new AtomicLong(0);

    // Per-worker status tracking
    private final ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses = new ConcurrentHashMap<>();
    private final AtomicInteger indexingWorkerProcessed = new AtomicInteger(0);

    // Current embedding batch metrics (for progress reporting)
    private volatile PipelineProgress.EmbeddingBatchMetrics currentEmbeddingBatchMetrics = null;

    // Callback for progress updates
    private Consumer<PipelineProgress> progressCallback;

    // Control flags
    private volatile boolean cancelled = false;
    private volatile boolean chunkingComplete = false;
    private volatile boolean embeddingComplete = false;
    private volatile String lastError = null;

    // Checkpoint service (optional)
    private IngestCheckpointService checkpointService;
    private IngestCheckpointService.CheckpointState resumeState;

    /**
     * Set checkpoint service for persistence and resume.
     */
    public void setCheckpointService(IngestCheckpointService service) {
        this.checkpointService = service;
    }

    /**
     * Provide resume state to skip already processed items.
     */
    public void resumeFrom(IngestCheckpointService.CheckpointState state) {
        this.resumeState = state;
        if (state != null) {
            logger.info("Resuming pipeline: {} indexed, {} embedded, {} orphaned chunks",
                    state.indexedIds().size(), state.embeddedIds().size(), state.orphanedChunks().size());

            // Pre-count "virtual" progress
            int virtualIndexed = state.indexedIds().size();
            chunksIndexed.addAndGet(virtualIndexed);
            chunksEmbedded.addAndGet(virtualIndexed + state.orphanedEmbeddings().size());
            chunksCreated.addAndGet(virtualIndexed + state.orphanedEmbeddings().size() + state.orphanedChunks().size());
        }
    }

    /**
     * Embedded batch containing chunks with their pre-computed embeddings.
     */
    public record EmbeddedBatch(
            List<RetrievedDoc> chunks,
            List<float[]> embeddings) {
    }

    /**
     * Creates a new parallel ingest pipeline with default configuration.
     *
     * @param chunker         The text chunker to use (null for no chunking)
     * @param embeddingModel  The embedding model for generating vectors
     * @param indexerService  The indexer service for storing documents
     * @param chunkingOptions Options for chunking behavior
     * @param batchSize       Number of chunks per batch (0 to use model default)
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize) {
        this(chunker, embeddingModel, indexerService, chunkingOptions, batchSize, true, false);
    }

    /**
     * Creates a new parallel ingest pipeline with configurable parallel indexing.
     *
     * @param chunker                 The text chunker to use (null for no chunking)
     * @param embeddingModel          The embedding model for generating vectors
     * @param indexerService          The indexer service for storing documents
     * @param chunkingOptions         Options for chunking behavior
     * @param batchSize               Number of chunks per batch (0 to use model default)
     * @param parallelIndexingEnabled Whether to run keyword and vector indexing in parallel
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize,
            boolean parallelIndexingEnabled) {
        this(chunker, embeddingModel, indexerService, chunkingOptions, batchSize, parallelIndexingEnabled, false);
    }

    /**
     * Creates a new parallel ingest pipeline with configurable parallel indexing and vector-only mode.
     *
     * @param chunker                 The text chunker to use (null for no chunking)
     * @param embeddingModel          The embedding model for generating vectors
     * @param indexerService          The indexer service for storing documents
     * @param chunkingOptions         Options for chunking behavior
     * @param batchSize               Number of chunks per batch (0 to use model default)
     * @param parallelIndexingEnabled Whether to run keyword and vector indexing in parallel
     * @param vectorOnlyMode          When true, only index to vector store (skip keyword index)
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize,
            boolean parallelIndexingEnabled,
            boolean vectorOnlyMode) {
        this(chunker, embeddingModel, indexerService, chunkingOptions,
             PipelineConfig.builder()
                     .defaultBatchSize(batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE)
                     .parallelIndexingEnabled(parallelIndexingEnabled)
                     .build(),
             vectorOnlyMode);
    }

    /**
     * Creates a new parallel ingest pipeline with full configuration control.
     *
     * @param chunker         The text chunker to use (null for no chunking)
     * @param embeddingModel  The embedding model for generating vectors
     * @param indexerService  The indexer service for storing documents
     * @param chunkingOptions Options for chunking behavior
     * @param pipelineConfig  Pipeline configuration for threading, batching, and queues
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            PipelineConfig pipelineConfig) {
        this(chunker, embeddingModel, indexerService, chunkingOptions, pipelineConfig, false);
    }

    /**
     * Creates a new parallel ingest pipeline with full configuration control and vector-only mode.
     *
     * @param chunker         The text chunker to use (null for no chunking)
     * @param embeddingModel  The embedding model for generating vectors
     * @param indexerService  The indexer service for storing documents
     * @param chunkingOptions Options for chunking behavior
     * @param pipelineConfig  Pipeline configuration for threading, batching, and queues
     * @param vectorOnlyMode  When true, only index to vector store (skip keyword index)
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            PipelineConfig pipelineConfig,
            boolean vectorOnlyMode) {
        this.pipelineConfig = pipelineConfig != null ? pipelineConfig : PipelineConfig.defaults();
        this.vectorOnlyMode = vectorOnlyMode;
        this.config = new AdaptivePipelineConfig();
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.indexerService = indexerService;
        this.chunkingOptions = chunkingOptions != null ? chunkingOptions : new HashMap<>();
        this.parallelIndexingEnabled = this.pipelineConfig.parallelIndexingEnabled();

        // Log embedding model status for debugging
        if (embeddingModel != null) {
            logger.info("ParallelIngestPipeline: embedding model provided - {}",
                    embeddingModel.getClass().getSimpleName());
        } else {
            logger.warn("ParallelIngestPipeline: NO embedding model - using passthrough mode");
        }

        // Log vector-only mode status
        if (vectorOnlyMode) {
            logger.info("ParallelIngestPipeline: VECTOR-ONLY MODE enabled - keyword indexing will be skipped");
        }

        // Determine optimal batch size from embedding model if available, respecting config limits
        int modelOptimal = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : this.pipelineConfig.defaultBatchSize();
        int modelMax = embeddingModel != null ? embeddingModel.getMaxBatchSize() : this.pipelineConfig.maxBatchSize();
        int configMax = this.pipelineConfig.maxBatchSize();
        int effectiveMax = Math.max(modelMax, configMax); // Use the higher of model or config max
        int requestedBatch = this.pipelineConfig.defaultBatchSize() > 0 ? this.pipelineConfig.defaultBatchSize() : modelOptimal;
        this.batchSize = Math.max(this.pipelineConfig.minBatchSize(), Math.min(requestedBatch, effectiveMax));

        // Create adaptive batch accumulator for efficient embedding
        this.batchAccumulator = new AdaptiveBatchAccumulator<>(
                this.pipelineConfig.minBatchSize(),
                this.batchSize,
                effectiveMax,
                this.pipelineConfig.maxBatchWaitMs(),
                this.pipelineConfig.minBatchWaitMs());

        // Use configured parallelism
        this.chunkingParallelism = this.pipelineConfig.chunkingThreads();
        this.embeddingParallelism = this.pipelineConfig.embeddingThreads();
        this.indexingParallelism = this.pipelineConfig.indexingThreads();

        // Create thread pools
        this.chunkingExecutor = new ForkJoinPool(
                chunkingParallelism,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                (t, e) -> logger.error("Chunking thread {} failed: {}", t.getName(), e.getMessage(), e),
                true);

        this.embeddingExecutor = Executors.newFixedThreadPool(embeddingParallelism, r -> {
            Thread t = new Thread(r, "embedding-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // Create indexing executor for parallel keyword/vector indexing
        this.indexingExecutor = Executors.newFixedThreadPool(indexingParallelism, r -> {
            Thread t = new Thread(r, "indexing-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // Create bounded queues for backpressure using configured capacity
        this.chunkQueue = new LinkedBlockingQueue<>(this.pipelineConfig.queueCapacity());
        this.embeddedQueue = new LinkedBlockingQueue<>(this.pipelineConfig.queueCapacity());

        logger.info("=== ParallelIngestPipeline INITIALIZED ===");
        logger.info("  Threading: chunking={}, embedding={}, indexing={}",
                chunkingParallelism, embeddingParallelism, indexingParallelism);
        logger.info("  Batching: pipeline_batch={} (model_optimal={}, model_max={}, config_max={}), queueCapacity={}",
                this.batchSize, modelOptimal, modelMax, configMax, this.pipelineConfig.queueCapacity());
        logger.info("  DYNAMIC BATCH SIZING: The encoder will dynamically adjust batch sizes based on");
        logger.info("    actual sequence lengths. For 512-token sequences: batch ~{}. For shorter texts,",
                modelOptimal);
        logger.info("    batches will be larger. See [DYNAMIC-BATCH] log entries for actual sizes used.");
        logger.info("  PARALLEL INDEXING: {} (keyword+vector indexes updated concurrently)",
                parallelIndexingEnabled ? "ENABLED" : "DISABLED");
        logger.info("  Indexing batch accumulation: {}", this.pipelineConfig.indexingBatchAccumulationSize());
        logger.info("==========================================");
    }

    /**
     * Legacy constructor for backward compatibility.
     */
    public ParallelIngestPipeline(
            TextChunker chunker,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize) {
        this(chunker, null, indexerService, chunkingOptions, batchSize);
    }

    /**
     * Returns the current pipeline configuration.
     */
    public PipelineConfig getPipelineConfig() {
        return pipelineConfig;
    }

    public void setProgressCallback(Consumer<PipelineProgress> callback) {
        this.progressCallback = callback;
    }

    /**
     * Processes documents through the parallel pipeline.
     *
     * @param documents The documents to process
     * @return Pipeline result with statistics
     */
    public PipelineResult process(List<Document> documents) throws InterruptedException {
        if (documents == null || documents.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, List.of());
        }

        startTimeMs.set(System.currentTimeMillis());
        int totalDocuments = documents.size();

        logger.info("Starting parallel pipeline: {} documents, chunking={} threads, embedding={} threads, batch={}",
                totalDocuments, chunkingParallelism, embeddingParallelism, batchSize);

        reportProgress("starting", 1,
                String.format("Starting: %d documents, %d+%d workers",
                        totalDocuments, chunkingParallelism, embeddingParallelism));

        // Start embedding workers (consumers of chunk queue, producers of embedded
        // queue)
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();

        // Initialize queues with resume state if available
        if (resumeState != null) {
            // Restore orphaned chunks (created but not fully processed)
            for (RetrievedDoc chunk : resumeState.orphanedChunks()) {
                if (!chunkQueue.offer(chunk)) {
                    logger.warn("ParallelIngestPipeline: Failed to requeue orphaned chunk {}", chunk.getId());
                }
            }

            // Restore orphaned embeddings (embedded but not indexed)
            if (!resumeState.orphanedEmbeddings().isEmpty()) {
                // Convert map to list of chunks/embeddings
                // Implementation detail: we need RetrievedDoc objects for these IDs.
                // If we only have IDs and embeddings, we can't fully reconstruct RetrievedDoc
                // unless we reload them.
                // Ideally, IngestCheckpointService loaded them.
                // For now, simpler approach: if we have orphaned embeddings, we might still
                // need to re-chunk them
                // OR we rely on chunks.jsonl to have them.
                // Let's assume orphanedChunks contains EVERYTHING not indexed.
                // But we want to skip re-embedding if possible.
                // Complex resume logic:
                // 1. Chunks that are invalid (not indexed) should be re-processed.
                // 2. If we have their embedding, use it.
            }
        }

        // Start indexing worker (consumer of embedded queue)
        Future<List<String>> indexingFuture = startIndexingWorker();

        // Start periodic progress reporter (every 1 second for responsive progress)
        ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-progress");
            t.setDaemon(true);
            return t;
        });
        progressReporter.scheduleAtFixedRate(() -> {
            try {
                if (!cancelled) {
                    int created = chunksCreated.get();
                    int embedded = chunksEmbedded.get();
                    int indexed = chunksIndexed.get();
                    int queueSize = chunkQueue.size();
                    int embeddedQueueSize = embeddedQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (indexed * 1000.0 / elapsed) : 0;

                    // Determine phase based on actual work being done, not just flags
                    // If we have created chunks but none embedded yet, we're in embedding phase
                    // (chunks are waiting to be processed by embedding workers)
                    String phase;
                    if (indexed > 0 || embeddingComplete) {
                        phase = "indexing";
                    } else if (created > 0) {
                        // Chunks exist but not yet embedded - embedding is the active phase
                        phase = "embedding";
                    } else {
                        phase = "chunking";
                    }

                    // Log worker status details
                    int activeWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "processing".equals(w.status())).count();
                    int waitingWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "waiting".equals(w.status())).count();

                    logger.debug("Periodic progress: phase={}, created={}, embedded={}, indexed={}, " +
                            "chunkQ={}, embedQ={}, workers={} active/{} waiting, rate={}",
                            phase, created, embedded, indexed, queueSize, embeddedQueueSize,
                            activeWorkers, waitingWorkers, rate);

                    reportProgressWithWorkers(phase, 0,
                            String.format("%d created, %d embedded, %d indexed (%.1f/sec) [Q:%d/%d]",
                                    created, embedded, indexed, rate, queueSize, embeddedQueueSize));
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter: {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Phase 1: Parallel chunking (producer of chunk queue)
            parallelChunkDocuments(documents);
            chunkingComplete = true;

            logger.info("Chunking complete: {} documents -> {} chunks",
                    documentsProcessed.get(), chunksCreated.get());

            // Wait for embedding to complete
            for (Future<?> future : embeddingFutures) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                }
            }
            embeddingComplete = true;

            logger.info("Embedding complete: {} chunks embedded in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            // Wait for indexing to complete
            List<String> processedIds;
            try {
                processedIds = indexingFuture.get(30, TimeUnit.MINUTES);
            } catch (TimeoutException | ExecutionException e) {
                logger.error("Indexing failed: {}", e.getMessage(), e);
                processedIds = new ArrayList<>();
            }

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (chunksIndexed.get() * 1000.0 / totalTimeMs) : 0;

            // Send highly visible completion message
            reportProgress("COMPLETED", 100,
                    String.format("=== PIPELINE COMPLETE === %d docs -> %d chunks created -> %d indexed in %dms (%.1f chunks/sec)",
                            documentsProcessed.get(), chunksCreated.get(), chunksIndexed.get(), totalTimeMs, chunksPerSec));

            logger.info("========== PIPELINE COMPLETE: {} documents -> {} chunks -> {} indexed in {}ms ({} chunks/sec) ==========",
                    documentsProcessed.get(), chunksCreated.get(), chunksIndexed.get(), totalTimeMs, String.format("%.1f", chunksPerSec));

            return new PipelineResult(
                    documentsProcessed.get(),
                    chunksCreated.get(),
                    chunksIndexed.get(),
                    totalTimeMs,
                    processedIds);
        } finally {
            // Stop periodic progress reporter
            progressReporter.shutdownNow();
        }
    }

    /**
     * Processes pre-chunked documents through the pipeline, skipping the chunking phase.
     * This is optimized for vector store population from existing Lucene indexes.
     *
     * @param chunks The pre-chunked documents to process (embedding + indexing only)
     * @return Pipeline result with statistics
     */
    public PipelineResult processPreChunked(List<RetrievedDoc> chunks) throws InterruptedException {
        if (chunks == null || chunks.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, List.of());
        }

        startTimeMs.set(System.currentTimeMillis());
        int totalChunks = chunks.size();

        logger.info("Starting PRE-CHUNKED pipeline: {} chunks, embedding={} threads, indexing={} threads, batch={}",
                totalChunks, embeddingParallelism, indexingParallelism, batchSize);

        reportProgress("starting", 1,
                String.format("Starting vector population: %d chunks, %d+%d workers",
                        totalChunks, embeddingParallelism, indexingParallelism));

        // Start embedding workers (consumers of chunk queue, producers of embedded queue)
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();

        // Start indexing worker (consumer of embedded queue)
        Future<List<String>> indexingFuture = startIndexingWorker();

        // Start periodic progress reporter (every 1 second for responsive progress)
        ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-progress");
            t.setDaemon(true);
            return t;
        });
        progressReporter.scheduleAtFixedRate(() -> {
            try {
                if (!cancelled) {
                    int created = chunksCreated.get();
                    int embedded = chunksEmbedded.get();
                    int indexed = chunksIndexed.get();
                    int queueSize = chunkQueue.size();
                    int embeddedQueueSize = embeddedQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (indexed * 1000.0 / elapsed) : 0;

                    // Phase is always embedding or indexing for pre-chunked data
                    String phase = indexed > 0 || embeddingComplete ? "indexing" : "embedding";

                    int activeWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "processing".equals(w.status())).count();

                    // Calculate ETA
                    String etaStr = "";
                    if (indexed > 0 && indexed < totalChunks) {
                        long remaining = totalChunks - indexed;
                        if (rate > 0) {
                            long etaSec = (long) (remaining / rate);
                            if (etaSec > 60) {
                                etaStr = String.format(" (~%dm %ds)", etaSec / 60, etaSec % 60);
                            } else {
                                etaStr = String.format(" (~%ds)", etaSec);
                            }
                        }
                    }

                    logger.debug("Periodic progress: phase={}, queued={}, embedded={}, indexed={}/{}, " +
                            "chunkQ={}, embedQ={}, active={}, rate={}",
                            phase, created, embedded, indexed, totalChunks, queueSize, embeddedQueueSize,
                            activeWorkers, rate);

                    reportProgressWithWorkers(phase, Math.min(99, (int) ((indexed * 100) / totalChunks)),
                            String.format("%d/%d indexed (%.1f/sec)%s [Q:%d/%d]",
                                    indexed, totalChunks, rate, etaStr, queueSize, embeddedQueueSize));
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter: {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Skip chunking - queue pre-chunked documents directly
            queuePreChunkedDocuments(chunks, totalChunks);
            chunkingComplete = true;

            logger.info("Queued {} pre-chunked documents for embedding", chunksCreated.get());

            // Wait for embedding to complete
            for (Future<?> future : embeddingFutures) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                }
            }
            embeddingComplete = true;

            logger.info("Embedding complete: {} chunks embedded in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            // Wait for indexing to complete
            List<String> processedIds;
            try {
                processedIds = indexingFuture.get(30, TimeUnit.MINUTES);
            } catch (TimeoutException | ExecutionException e) {
                logger.error("Indexing failed: {}", e.getMessage(), e);
                processedIds = new ArrayList<>();
            }

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (chunksIndexed.get() * 1000.0 / totalTimeMs) : 0;

            reportProgress("COMPLETED", 100,
                    String.format("=== PIPELINE COMPLETE (pre-chunked) === %d chunks indexed in %dms (%.1f chunks/sec)",
                            chunksIndexed.get(), totalTimeMs, chunksPerSec));

            logger.info("========== PIPELINE COMPLETE (pre-chunked): {} chunks indexed in {}ms ({} chunks/sec) ==========",
                    chunksIndexed.get(), totalTimeMs, String.format("%.1f", chunksPerSec));

            return new PipelineResult(
                    totalChunks, // documents = total chunks for pre-chunked
                    chunksCreated.get(),
                    chunksIndexed.get(),
                    totalTimeMs,
                    processedIds);
        } finally {
            progressReporter.shutdownNow();
        }
    }

    /**
     * Queue pre-chunked documents directly to the embedding queue (skip chunking).
     */
    private void queuePreChunkedDocuments(List<RetrievedDoc> chunks, int totalChunks) throws InterruptedException {
        logger.info("Queueing {} pre-chunked documents for embedding...", totalChunks);

        int queuedCount = 0;
        long lastProgressLogTime = System.currentTimeMillis();

        for (RetrievedDoc chunk : chunks) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                break;
            }

            // Queue directly to chunkQueue for embedding workers
            // Use blocking put with backpressure - wait indefinitely for space
            // This allows the pipeline to naturally throttle when embedding is slow
            boolean queued = false;
            int waitCycles = 0;
            while (!queued && !cancelled && !Thread.currentThread().isInterrupted()) {
                // Try to offer with a timeout, so we can check for cancellation
                queued = chunkQueue.offer(chunk, 5000, TimeUnit.MILLISECONDS);
                if (!queued) {
                    waitCycles++;
                    long now = System.currentTimeMillis();
                    // Log progress every 30 seconds when waiting
                    if (now - lastProgressLogTime > 30000) {
                        int embedded = chunksEmbedded.get();
                        int indexed = chunksIndexed.get();
                        logger.info("Queue backpressure: queued {}/{}, embedded {}, indexed {}, queue size {}",
                                queuedCount, totalChunks, embedded, indexed, chunkQueue.size());
                        lastProgressLogTime = now;
                    }
                    // After 5 minutes of waiting on a single chunk, log a warning
                    if (waitCycles > 0 && waitCycles % 60 == 0) {
                        logger.warn("Waiting to queue chunk {}/{} for {}s - embedding may be slow. " +
                                        "Queue: {}/{}, Embedded: {}, Indexed: {}",
                                queuedCount + 1, totalChunks, waitCycles * 5,
                                chunkQueue.size(), this.pipelineConfig.queueCapacity(),
                                chunksEmbedded.get(), chunksIndexed.get());
                    }
                }
            }

            // Check if we were cancelled while waiting
            if (cancelled || Thread.currentThread().isInterrupted()) {
                logger.info("Queueing cancelled after {} chunks", queuedCount);
                break;
            }

            queuedCount++;
            chunksCreated.incrementAndGet();

            // Report progress periodically
            if (queuedCount % 100 == 0 || queuedCount == totalChunks) {
                int progressPercent = Math.min(25, (queuedCount * 25) / totalChunks);
                reportProgress("queueing", progressPercent,
                        String.format("Queued %d/%d chunks", queuedCount, totalChunks));
            }
        }

        logger.info("Finished queueing {} chunks for embedding", queuedCount);
    }

    /**
     * Phase 1: Parallel chunking using ForkJoinPool.
     */
    private void parallelChunkDocuments(List<Document> documents) throws InterruptedException {
        int totalDocuments = documents.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>(totalDocuments);

        // Track per-thread chunking stats
        ConcurrentHashMap<Long, AtomicInteger> threadChunkCounts = new ConcurrentHashMap<>();
        AtomicInteger chunkingWorkerIdCounter = new AtomicInteger(0);
        ConcurrentHashMap<Long, Integer> threadWorkerIds = new ConcurrentHashMap<>();

        for (int i = 0; i < documents.size(); i++) {
            if (cancelled)
                break;

            final Document doc = documents.get(i);
            final int docIndex = i;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (cancelled || Thread.currentThread().isInterrupted())
                    return;

                // Track this thread's worker ID
                long threadId = Thread.currentThread().getId();
                int workerId = threadWorkerIds.computeIfAbsent(threadId,
                        k -> chunkingWorkerIdCounter.getAndIncrement() % chunkingParallelism);
                String workerKey = "chunk-" + workerId;
                AtomicInteger threadChunks = threadChunkCounts.computeIfAbsent(threadId, k -> new AtomicInteger(0));

                try {
                    // === BEGIN CHUNK: Report document chunking start ===
                    workerStatuses.put(workerKey,
                            PipelineProgress.WorkerStatus.processing(workerId, "chunking",
                                    threadChunks.get(), 1, 0, "doc " + (docIndex + 1)));

                    int totalChunksCreatedBefore = chunksCreated.get();
                    int progressPercent = 5 + (int) ((documentsProcessed.get() / (double) totalDocuments) * 20);
                    String docName = doc.getMetadata() != null && doc.getMetadata().containsKey("source")
                            ? String.valueOf(doc.getMetadata().get("source"))
                            : "doc-" + (docIndex + 1);
                    reportProgressWithWorkers("chunking", progressPercent,
                            String.format("BEGIN CHUNK doc %d/%d: %s (worker %d, total chunks so far: %d)",
                                    docIndex + 1, totalDocuments, docName, workerId, totalChunksCreatedBefore));
                    // === END BEGIN CHUNK ===

                    long startTime = System.currentTimeMillis();
                    List<RetrievedDoc> chunks = chunkSingleDocument(doc, docIndex, totalDocuments);

                    if (!chunks.isEmpty()) {
                        // Stream individual chunks to queue with timeout to prevent blocking forever
                        // This allows large documents to start flowing through embedding immediately
                        for (RetrievedDoc chunk : chunks) {
                            if (cancelled || Thread.currentThread().isInterrupted())
                                break;

                            // Use offer with timeout instead of blocking put
                            boolean queued = false;
                            int retries = 0;
                            final int maxRetries = 30; // 30 * 1000ms = 30 seconds max wait per chunk
                            while (!queued && !cancelled && !Thread.currentThread().isInterrupted()
                                    && retries < maxRetries) {
                                queued = chunkQueue.offer(chunk, 1000, TimeUnit.MILLISECONDS);
                                if (!queued) {
                                    retries++;
                                    if (retries % 10 == 0) {
                                        logger.warn("Chunking doc {}: chunk queue full ({}), waiting... (retry {}/{})",
                                                docIndex, chunkQueue.size(), retries, maxRetries);
                                    }
                                }
                            }
                            if (!queued) {
                                logger.error(
                                        "Chunking doc {}: failed to queue chunk after {} retries - embedding may have stalled",
                                        docIndex, maxRetries);
                                throw new RuntimeException("Chunk queue full - embedding pipeline stalled");
                            }

                            chunksCreated.incrementAndGet();
                            threadChunks.incrementAndGet();

                            // Checkpoint hook
                            if (checkpointService != null) {
                                checkpointService.saveChunks(List.of(chunk));
                            }
                        }
                    }
                    documentsProcessed.incrementAndGet();

                    long elapsed = System.currentTimeMillis() - startTime;
                    double throughput = elapsed > 0 ? (chunks.size() * 1000.0 / elapsed) : 0;

                    // Update worker status with throughput
                    workerStatuses.put(workerKey,
                            PipelineProgress.WorkerStatus.processing(workerId, "chunking",
                                    threadChunks.get(), chunks.size(), throughput, "doc " + (docIndex + 1)));

                    // === END CHUNK: Report document chunking completion ===
                    int processed = documentsProcessed.get();
                    int chunksNow = chunksCreated.get();
                    int progressPercentEnd = 5 + (int) ((processed / (double) totalDocuments) * 20);
                    String docNameEnd = doc.getMetadata() != null && doc.getMetadata().containsKey("source")
                            ? String.valueOf(doc.getMetadata().get("source"))
                            : "doc-" + (docIndex + 1);
                    reportProgressWithWorkers("chunking", progressPercentEnd,
                            String.format("END CHUNK doc %d/%d: %s -> %d chunks in %dms (%.1f/s) [total: %d/%d docs, %d chunks]",
                                    docIndex + 1, totalDocuments, docNameEnd,
                                    chunks.size(), elapsed, throughput,
                                    processed, totalDocuments, chunksNow));
                    // === END END CHUNK ===
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Error chunking document {}: {}", docIndex, e.getMessage(), e);
                }
            }, chunkingExecutor);

            futures.add(future);
        }

        // Wait for all chunking to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            logger.error("Chunking timed out after 30 minutes");
            cancelled = true;
            lastError = "Chunking timed out after 30 minutes";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OutOfMemoryError) {
                logger.error("Chunking failed due to OutOfMemoryError - cancelling pipeline", cause);
                cancelled = true;
                lastError = "Out of memory during chunking. Try processing smaller documents or increasing heap size.";
                // Try to free memory
                System.gc();
            } else {
                logger.error("Chunking failed: {}", cause.getMessage(), cause);
                lastError = "Chunking failed: " + cause.getMessage();
            }
        }

        // Mark chunking workers as complete
        long elapsed = System.currentTimeMillis() - startTimeMs.get();
        for (Map.Entry<Long, Integer> entry : threadWorkerIds.entrySet()) {
            int workerId = entry.getValue();
            AtomicInteger chunks = threadChunkCounts.getOrDefault(entry.getKey(), new AtomicInteger(0));
            double throughput = elapsed > 0 ? (chunks.get() * 1000.0 / elapsed) : 0;
            workerStatuses.put("chunk-" + workerId,
                    PipelineProgress.WorkerStatus.complete(workerId, "chunking", chunks.get(), throughput));
        }
    }

    private List<RetrievedDoc> chunkSingleDocument(Document doc, int docIndex, int totalDocs) {
        RetrievedDoc retrievedDoc = convertToRetrievedDoc(doc);

        if (chunker == null) {
            return List.of(retrievedDoc);
        }

        // Skip documents with null or empty text content
        String text = retrievedDoc.getText();
        if (text == null || text.trim().isEmpty()) {
            logger.debug("Skipping chunking for document {} - no text content", docIndex);
            return List.of(); // Return empty list for empty documents
        }

        try {
            // Check if this document was already fully indexed (resume skip)
            if (resumeState != null) {
                // Optimization: we can't easily map Doc -> chunks without chunking.
                // But if we know ALL chunks of this doc are indexed, we skip.
                // Tracking that is hard.
                // Simple resume: We Re-Chunk everything.
                // But we discard chunks that are already indexed downstream.
            }

            return chunker.chunk(retrievedDoc, chunkingOptions, progress -> {
                if (progress.totalChars() > 50000) {
                    logger.debug("Doc {}/{}: {} - {} chunks",
                            docIndex + 1, totalDocs, progress.phase(), progress.chunksCreated());
                }
            });
        } catch (Exception e) {
            logger.warn("Chunking failed for document {}, using whole document: {}", docIndex, e.getMessage());
            return List.of(retrievedDoc);
        }
    }

    /**
     * Start embedding worker threads that consume from chunkQueue and produce to
     * embeddedQueue.
     *
     * <h2>Optimization: Adaptive Batch Accumulation</h2>
     * <p>
     * Instead of processing chunks immediately as they arrive (leading to many
     * small batches),
     * we use an adaptive batch accumulator that:
     * </p>
     * <ul>
     * <li>Waits to collect larger batches for better GPU/model utilization</li>
     * <li>Adjusts batch size based on memory pressure</li>
     * <li>Respects latency constraints (max wait time)</li>
     * <li>Drains remaining items when producer completes</li>
     * </ul>
     */
    private List<Future<?>> startEmbeddingWorkers() {
        List<Future<?>> futures = new ArrayList<>(embeddingParallelism);

        // Initialize worker statuses
        for (int i = 0; i < embeddingParallelism; i++) {
            workerStatuses.put("embed-" + i, PipelineProgress.WorkerStatus.idle(i, "embedding"));
        }

        for (int i = 0; i < embeddingParallelism; i++) {
            final int workerId = i;
            final String workerKey = "embed-" + workerId;
            final AtomicInteger workerProcessed = new AtomicInteger(0);

            futures.add(embeddingExecutor.submit(() -> {
                System.err.println("[PIPELINE-DEBUG] Embedding worker " + workerId + " STARTED on "
                        + Thread.currentThread().getName());
                System.err.flush();
                logger.info("Embedding worker {} STARTED on thread {} with adaptive batching (target={}, max={})",
                        workerId, Thread.currentThread().getName(),
                        batchAccumulator.getTargetBatchSize(), batchAccumulator.getMaxBatchSize());

                // Report embedding phase started immediately (before warmup)
                // This ensures frontend knows we've entered embedding phase
                if (workerId == 0) { // Only first worker reports to avoid spam
                    int queueSize = chunkQueue.size();
                    reportProgressWithWorkers("embedding", 0,
                            String.format("Starting embedding workers (%d chunks queued)", queueSize));
                }

                // Wait for embedding model to be ready (warmup may be in progress)
                // This prevents concurrent access to the SameDiff model during warmup
                if (embeddingModel != null) {
                    System.err.println("[PIPELINE-DEBUG] Worker " + workerId + ": checking model dimensions...");
                    System.err.flush();
                    int warmupWaitCount = 0;
                    while (!cancelled && !Thread.currentThread().isInterrupted()) {
                        try {
                            // Try to get dimensions - this will succeed if model is ready
                            // This is a lightweight check that doesn't trigger full inference
                            int dims = embeddingModel.dimensions();
                            System.err.println("[PIPELINE-DEBUG] Worker " + workerId + ": got dimensions=" + dims);
                            System.err.flush();
                            if (dims > 0) {
                                if (warmupWaitCount > 0) {
                                    logger.info("Embedding worker {}: Model ready (dimensions={}), waited {}ms",
                                            workerId, dims, warmupWaitCount * 100);
                                }
                                break;
                            }
                        } catch (Exception e) {
                            System.err.println(
                                    "[PIPELINE-DEBUG] Worker " + workerId + ": dimensions() threw: " + e.getMessage());
                            System.err.flush();
                            // Model not ready yet
                        }

                        warmupWaitCount++;
                        if (warmupWaitCount % 50 == 0) { // Every 5 seconds
                            System.err.println("[PIPELINE-DEBUG] Worker " + workerId + ": waiting for warmup, count="
                                    + warmupWaitCount);
                            System.err.flush();
                            logger.info("Embedding worker {}: Waiting for model warmup ({} checks)...",
                                    workerId, warmupWaitCount);
                            workerStatuses.put(workerKey,
                                    new PipelineProgress.WorkerStatus(workerId, "embedding", "waiting",
                                            0, 0, 0, "waiting for model warmup"));
                            // Report progress during warmup so frontend knows embedding phase has started
                            int queueSize = chunkQueue.size();
                            reportProgressWithWorkers("embedding", 0,
                                    String.format("Model warmup in progress (worker %d, queue=%d)", workerId, queueSize));
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } else {
                    System.err.println("[PIPELINE-DEBUG] Worker " + workerId + ": embeddingModel is NULL!");
                    System.err.flush();
                }

                // Watchdog variables for detecting stalls
                long lastActivityTime = System.currentTimeMillis();
                int consecutiveEmptyPolls = 0;
                final int MAX_EMPTY_POLLS_BEFORE_EXIT = 20; // 20 * 50ms = 1 second of inactivity after completion

                // Progress reporting while waiting - track last report time
                long lastWaitingProgressReportMs = System.currentTimeMillis();
                final long WAITING_PROGRESS_REPORT_INTERVAL_MS = 200; // Report every 200ms while waiting

                // Memory pressure tracking
                long lastMemoryCheckTime = System.currentTimeMillis();
                final long MEMORY_CHECK_INTERVAL_MS = 5000; // Check memory every 5 seconds
                final double MEMORY_PRESSURE_THRESHOLD = 0.85; // 85% heap usage

                while (!cancelled && !Thread.currentThread().isInterrupted()) {
                    // Periodic memory pressure check
                    long now = System.currentTimeMillis();
                    if (now - lastMemoryCheckTime > MEMORY_CHECK_INTERVAL_MS) {
                        lastMemoryCheckTime = now;
                        Runtime runtime = Runtime.getRuntime();
                        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                        double memoryUsage = (double) usedMemory / runtime.maxMemory();
                        if (memoryUsage > MEMORY_PRESSURE_THRESHOLD) {
                            logger.warn("Embedding worker {}: HIGH MEMORY PRESSURE ({}%), pausing 2s for GC...",
                                    workerId, String.format("%.1f", memoryUsage * 100));
                            workerStatuses.put(workerKey,
                                    PipelineProgress.WorkerStatus.processing(workerId, "embedding",
                                            workerProcessed.get(), 0, 0, "memory pressure pause"));
                            System.gc(); // Hint to GC
                            try {
                                Thread.sleep(2000); // Pause to let GC run
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    // Update status to waiting/accumulating with cumulative throughput
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double cumulativeThroughput = elapsed > 0 ? (workerProcessed.get() * 1000.0 / elapsed) : 0;
                    workerStatuses.put(workerKey,
                            PipelineProgress.WorkerStatus.waitingWithThroughput(workerId, "embedding", workerProcessed.get(), cumulativeThroughput));

                    System.err
                            .println("[PIPELINE-DEBUG] Worker " + workerId + ": calling accumulateFlatBatch, queueSize="
                                    + chunkQueue.size() + ", chunkingComplete=" + chunkingComplete);
                    System.err.flush();

                    // Use adaptive accumulator to collect individual chunks into optimal batch
                    // Chunks flow through immediately - no waiting for entire documents
                    List<RetrievedDoc> batch = batchAccumulator.accumulateFlatBatch(
                            chunkQueue,
                            () -> chunkingComplete,
                            this.pipelineConfig.queuePollTimeoutMs());

                    System.err.println("[PIPELINE-DEBUG] Worker " + workerId
                            + ": accumulateFlatBatch returned batch.size=" + batch.size());
                    System.err.flush();

                    // Check for interrupt after accumulation
                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("Embedding worker {}: interrupted, exiting", workerId);
                        break;
                    }

                    if (!batch.isEmpty()) {
                        // Reset watchdog on activity
                        lastActivityTime = System.currentTimeMillis();
                        consecutiveEmptyPolls = 0;

                        System.err.println("[PIPELINE-DEBUG] Worker " + workerId + ": GOT BATCH of " + batch.size()
                                + " chunks, about to embed");
                        System.err.flush();
                        logger.info("Embedding worker {}: got batch of {} chunks, queue={}, cancelled={}",
                                workerId, batch.size(), chunkQueue.size(), cancelled);

                        // Show which batch we're about to process (counter increments inside
                        // processBatchEmbeddingOptimized)
                        int nextBatchNum = embeddingBatchesProcessed.get() + 1;
                        workerStatuses.put(workerKey,
                                PipelineProgress.WorkerStatus.processing(workerId, "embedding",
                                        workerProcessed.get(), batch.size(), 0,
                                        "batch " + nextBatchNum + " (" + batch.size() + " chunks)"));

                        processBatchEmbeddingOptimized(batch, workerId, workerProcessed);

                        // Checkpoint hook is inside optimized method for accuracy

                        System.err.println(
                                "[PIPELINE-DEBUG] Worker " + workerId + ": processBatchEmbeddingOptimized RETURNED");
                        System.err.flush();
                        // Reset waiting report timer after processing
                        lastWaitingProgressReportMs = System.currentTimeMillis();
                    } else {
                        consecutiveEmptyPolls++;

                        // Periodically report progress while waiting for chunks
                        long currentTimeMs = System.currentTimeMillis();
                        if (currentTimeMs - lastWaitingProgressReportMs >= WAITING_PROGRESS_REPORT_INTERVAL_MS) {
                            lastWaitingProgressReportMs = currentTimeMs;
                            int embedded = chunksEmbedded.get();
                            int total = chunksCreated.get();
                            int progressPercent = total > 0 ? Math.min(99, (embedded * 100) / total) : 0;
                            reportProgressWithWorkers("embedding", progressPercent,
                                    String.format("Embedded %d/%d chunks (waiting for chunking, queue=%d)",
                                            embedded, total, chunkQueue.size()));
                        }
                    }

                    // Robust exit condition with multiple checks:
                    // 1. Standard exit: chunking complete AND queue empty
                    // 2. Watchdog exit: many consecutive empty polls after chunking complete
                    // This handles race conditions where chunkingComplete flag might be stale
                    boolean standardExit = chunkingComplete && chunkQueue.isEmpty();
                    boolean watchdogExit = chunkingComplete && consecutiveEmptyPolls >= MAX_EMPTY_POLLS_BEFORE_EXIT;

                    if (standardExit || watchdogExit) {
                        if (watchdogExit && !standardExit) {
                            logger.info("Embedding worker {}: exiting via watchdog after {} empty polls",
                                    workerId, consecutiveEmptyPolls);
                        }
                        break;
                    }

                    // Log periodic heartbeat during long waits
                    long inactiveMs = System.currentTimeMillis() - lastActivityTime;
                    if (inactiveMs > 5000 && inactiveMs % 5000 < this.pipelineConfig.queuePollTimeoutMs()) {
                        logger.debug(
                                "Embedding worker {}: waiting for chunks (inactive {}ms, queue={}, chunkingComplete={})",
                                workerId, inactiveMs, chunkQueue.size(), chunkingComplete);
                    }
                }

                // Mark worker as complete
                long elapsed = System.currentTimeMillis() - startTimeMs.get();
                double throughput = elapsed > 0 ? (workerProcessed.get() * 1000.0 / elapsed) : 0;
                workerStatuses.put(workerKey,
                        PipelineProgress.WorkerStatus.complete(workerId, "embedding", workerProcessed.get(),
                                throughput));

                // Log accumulator stats
                AdaptiveBatchAccumulator.AccumulatorStats stats = batchAccumulator.getStats();
                logger.debug("Embedding worker {} finished: {} chunks, accumulator stats: {}",
                        workerId, workerProcessed.get(), stats);
            }));
        }

        return futures;
    }

    /**
     * Process a batch of chunks through the embedding model.
     * 
     * @deprecated Use processBatchEmbeddingOptimized instead
     */
    @Deprecated
    private void processBatchEmbedding(List<RetrievedDoc> batch, int workerId, AtomicInteger workerProcessed) {
        processBatchEmbeddingOptimized(batch, workerId, workerProcessed);
    }

    /**
     * Optimized batch embedding that uses the new embedBatch method for better
     * efficiency.
     *
     * <h2>Optimization Strategy</h2>
     * <ul>
     * <li>Uses embedBatch() which processes all texts in a single model call</li>
     * <li>Avoids intermediate INDArray allocations per chunk</li>
     * <li>Returns float[] directly for immediate use in indexing</li>
     * <li>Reports accurate throughput metrics</li>
     * <li>Captures detailed batch metrics for UI monitoring</li>
     * </ul>
     */
    private void processBatchEmbeddingOptimized(List<RetrievedDoc> batch, int workerId, AtomicInteger workerProcessed) {
        // Increment counter FIRST so progress is visible even if we return early
        int batchNum = embeddingBatchesProcessed.incrementAndGet();
        String workerKey = "embed-" + workerId;

        logger.debug("processBatchEmbeddingOptimized ENTERED: batchNum={}, batchSize={}",
                batchNum, batch != null ? batch.size() : "null");

        if (batch == null || batch.isEmpty()) {
            logger.warn("processBatchEmbeddingOptimized: batch {} has {} items (null={}), skipping",
                    batchNum, batch != null ? batch.size() : 0, batch == null);
            return;
        }
        if (cancelled) {
            logger.warn("processBatchEmbeddingOptimized: batch {} cancelled, skipping {} chunks",
                    batchNum, batch.size());
            return;
        }

        try {
            long batchStartTime = System.currentTimeMillis();

            // Estimate total batches based on current progress
            int totalChunks = chunksCreated.get();
            int estimatedTotalBatches = totalChunks > 0 ? Math.max(batchNum, (totalChunks + batchSize - 1) / batchSize)
                    : batchNum;

            // Calculate input metrics and extract source documents
            int inputTexts = batch.size();
            int totalChars = 0;
            int maxChars = 0;
            java.util.Set<String> sourceDocuments = new java.util.LinkedHashSet<>();

            // Resume/Dedup logic: Filter out chunks that are already fully indexed
            List<RetrievedDoc> toEmbed = new ArrayList<>();
            List<RetrievedDoc> alreadyDone = new ArrayList<>();

            for (RetrievedDoc chunk : batch) {
                if (resumeState != null && resumeState.indexedIds().contains(chunk.getId())) {
                    alreadyDone.add(chunk);
                } else {
                    toEmbed.add(chunk);
                }
            }

            // If all done, skip inference but pass downstream to ensure completion stats?
            // Actually if they are indexed, we don't need to do ANYTHING.
            if (toEmbed.isEmpty() && !batch.isEmpty()) {
                logger.debug("Batch {}: All {} chunks already indexed, skipping", batchNum, batch.size());
                return;
            }

            // If partial batch, we process what's left
            List<RetrievedDoc> actualBatch = toEmbed;

            for (RetrievedDoc chunk : actualBatch) {
                String content = chunk.getContent();
                int len = content != null ? content.length() : 0;
                totalChars += len;
                if (len > maxChars)
                    maxChars = len;

                // Extract source document info
                Map<String, Object> meta = chunk.getMetadata();
                if (meta != null) {
                    Object source = meta.get("source");
                    if (source == null)
                        source = meta.get("filename");
                    if (source == null)
                        source = meta.get("file_name");
                    if (source != null) {
                        String srcStr = source.toString();
                        int lastSlash = Math.max(srcStr.lastIndexOf('/'), srcStr.lastIndexOf('\\'));
                        if (lastSlash >= 0)
                            srcStr = srcStr.substring(lastSlash + 1);
                        sourceDocuments.add(srcStr);
                    }
                }
            }
            int avgChars = inputTexts > 0 ? totalChars / inputTexts : 0;
            String sourcesStr = sourceDocuments.isEmpty() ? "unknown"
                    : (sourceDocuments.size() <= 3 ? String.join(", ", sourceDocuments)
                            : sourceDocuments.stream().limit(2).collect(java.util.stream.Collectors.joining(", ")) +
                                    " + " + (sourceDocuments.size() - 2) + " more");

            // Estimate tokens (rough approximation: ~4 chars per token for English)
            int estimatedTokens = totalChars / 4;
            int estimatedMaxSeqLen = Math.min(512, maxChars / 4); // Most models have 512 max
            int estimatedAvgSeqLen = avgChars / 4;

            // Generate embeddings for batch using optimized batch method
            // NOTE: If embeddingModel is null, this returns null (passthrough mode)
            String modelName = embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "passthrough";
            logger.info("Batch {}: Starting embedding for {} chunks using {}",
                    batchNum, batch.size(), modelName);

            // === BEGIN BATCH: Report batch start with clear indicator ===
            int totalChunksCreated = chunksCreated.get();
            int embeddedSoFar = chunksEmbedded.get();
            int progressPercent = totalChunksCreated > 0 ? Math.min(95, (embeddedSoFar * 100) / totalChunksCreated) : 0;

            // Set batch metrics to show we're starting this batch
            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                    .batchNumber(batchNum)
                    .totalBatches(estimatedTotalBatches)
                    .inputTexts(inputTexts)
                    .inputTokens(estimatedTokens)
                    .maxSequenceLength(estimatedMaxSeqLen)
                    .avgSequenceLength(estimatedAvgSeqLen)
                    .outputVectors(0) // Not yet generated
                    .embeddingDimension(0)
                    .outputSizeBytes(0)
                    .inferenceTimeMs(0)
                    .totalBatchTimeMs(0)
                    .currentStep("EMBEDDING")
                    .heartbeatSeconds(0)
                    .stepStartTimeMs(batchStartTime)
                    .isStuck(false)
                    .tokensPerSecond(0)
                    .embeddingsPerSecond(0)
                    .batchThroughput(0)
                    .modelName(modelName)
                    .deviceType("CPU")
                    .statusLevel("RUNNING")
                    .build();

            reportProgressWithWorkers("embedding", progressPercent,
                    String.format("BEGIN BATCH %d: Embedding %d chunks (%d/%d total) using %s",
                            batchNum, inputTexts, embeddedSoFar, totalChunksCreated, modelName));
            // === END BEGIN BATCH ===

            List<float[]> embeddings = generateEmbeddingsOptimized(actualBatch);

            // Checkpoint hook
            if (checkpointService != null && embeddings != null) {
                // Create temporary batch wrapper to save
                checkpointService.saveEmbeddings(new EmbeddedBatch(new ArrayList<>(actualBatch), embeddings));
            }

            long inferenceEnd = System.currentTimeMillis();
            long totalBatchTime = inferenceEnd - batchStartTime;
            logger.info("Batch {}: Embedding complete in {}ms, got {} embeddings",
                    batchNum, totalBatchTime, embeddings != null ? embeddings.size() : 0);

            // Track whether actual embedding work was done
            boolean actualEmbeddingDone = (embeddings != null && !embeddings.isEmpty());

            // Calculate output metrics
            int outputVectors = actualEmbeddingDone ? embeddings.size() : 0;
            int embeddingDimension = 0;
            long outputSizeBytes = 0;
            if (actualEmbeddingDone && !embeddings.isEmpty() && embeddings.get(0) != null) {
                embeddingDimension = embeddings.get(0).length;
                outputSizeBytes = (long) outputVectors * embeddingDimension * 4; // 4 bytes per float
            }

            // Calculate throughput metrics
            double batchThroughput = totalBatchTime > 0 ? (inputTexts * 1000.0 / totalBatchTime) : 0;
            double tokensPerSecond = totalBatchTime > 0 ? (estimatedTokens * 1000.0 / totalBatchTime) : 0;
            double embeddingsPerSecond = totalBatchTime > 0 ? (outputVectors * 1000.0 / totalBatchTime) : 0;

            // Get model info
            String deviceType = "CPU"; // TODO: detect CUDA if available

            // Calculate heartbeat elapsed seconds and detect stuck state
            int heartbeatSecs = (int) (totalBatchTime / 1000);
            boolean isStuck = totalBatchTime > 30000; // Stuck if > 30 seconds for a batch

            // Build tensor shape strings
            String inputShape = "[" + inputTexts + ", " + estimatedMaxSeqLen + "]";
            String outputShape = "[" + outputVectors + ", " + embeddingDimension + "]";

            // Build and store embedding batch metrics
            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                    .batchNumber(batchNum)
                    .totalBatches(estimatedTotalBatches)
                    .inputTexts(inputTexts)
                    .inputTokens(estimatedTokens)
                    .maxSequenceLength(estimatedMaxSeqLen)
                    .avgSequenceLength(estimatedAvgSeqLen)
                    .outputVectors(outputVectors)
                    .embeddingDimension(embeddingDimension)
                    .outputSizeBytes(outputSizeBytes)
                    // Timing - coarse grained
                    .tokenizationTimeMs(0) // Not separately tracked in current impl
                    .inferenceTimeMs(totalBatchTime)
                    .totalBatchTimeMs(totalBatchTime)
                    // Timing - fine grained (populated from encoder metrics if available)
                    .paddingTimeMs(0)
                    .tensorCreationTimeMs(0)
                    .forwardPassTimeMs(totalBatchTime) // Treat whole time as forward pass for now
                    .extractionTimeMs(0)
                    // Heartbeat/liveness
                    .currentStep("COMPLETED")
                    .heartbeatSeconds(heartbeatSecs)
                    .stepStartTimeMs(batchStartTime)
                    .isStuck(isStuck)
                    // Throughput
                    .tokensPerSecond(tokensPerSecond)
                    .embeddingsPerSecond(embeddingsPerSecond)
                    .batchThroughput(batchThroughput)
                    .modelName(modelName)
                    .deviceType(deviceType)
                    .isBatched(true)
                    // ========== NEW DETAILED FIELDS ==========
                    // Source documents
                    .sourceDocuments(sourcesStr)
                    .sourceDocumentCount(sourceDocuments.size())
                    // Tensor shapes
                    .inputTensorShape(inputShape)
                    .outputTensorShape(outputShape)
                    .actualInputShape(null) // Not available in completion path
                    .actualOutputShape(null)
                    // Status
                    .statusLevel("COMPLETED")
                    .etaMessage(null)
                    .build();

            // Put batch in queue for indexing (with or without embeddings)
            // Use offer with timeout instead of blocking put to prevent deadlock
            // if the indexing worker stalls or fails
            EmbeddedBatch embeddedBatch = new EmbeddedBatch(new ArrayList<>(actualBatch), embeddings);
            boolean queued = false;
            int retries = 0;
            final int maxRetries = 60; // 60 * 1000ms = 60 seconds max wait
            while (!queued && !cancelled && !Thread.currentThread().isInterrupted() && retries < maxRetries) {
                queued = embeddedQueue.offer(embeddedBatch, 1000, TimeUnit.MILLISECONDS);
                if (!queued) {
                    retries++;
                    if (retries % 10 == 0) {
                        logger.warn("Batch {}: embedded queue full ({}), waiting... (retry {}/{})",
                                batchNum, embeddedQueue.size(), retries, maxRetries);
                    }
                }
            }
            if (!queued) {
                logger.error("Batch {}: failed to queue after {} retries - indexing may have stalled",
                        batchNum, maxRetries);
                // Don't silently continue - propagate the failure
                throw new RuntimeException("Embedded queue full - indexing pipeline stalled");
            }

            // Only count as "embedded" if actual embedding work was done
            // If embeddingModel is null, indexer will handle embedding - don't double count
            int processed;
            if (actualEmbeddingDone) {
                processed = chunksEmbedded.addAndGet(batch.size());
                workerProcessed.addAndGet(batch.size());
            } else {
                // Passthrough mode - chunks go directly to indexer for embedding
                // Don't increment chunksEmbedded here - it will be counted when indexed
                // (see passthrough handling in processIndexBatchForWorker)
                processed = chunksEmbedded.get();
                // Still count as processed by this worker for status display
                workerProcessed.addAndGet(batch.size());
            }

            // Update worker status with throughput
            String statusText = actualEmbeddingDone
                    ? String.format("batch %d (%d @ %.1f/s)", batchNum, batch.size(), batchThroughput)
                    : String.format("batch %d (%d passthrough)", batchNum, batch.size());
            workerStatuses.put(workerKey,
                    PipelineProgress.WorkerStatus.processing(workerId, "embedding",
                            workerProcessed.get(), batch.size(), batchThroughput, statusText));

            String embeddingStatus = actualEmbeddingDone ? "embedded" : "queued for indexer";
            logger.debug("Embedding worker {}: batch {} ({} chunks {}) in {}ms ({}/sec), dim={}, output={}KB",
                    workerId, batchNum, batch.size(), embeddingStatus, totalBatchTime,
                    String.format("%.1f", batchThroughput), embeddingDimension, outputSizeBytes / 1024);

            // === END BATCH: Report batch completion with clear indicator ===
            int total = chunksCreated.get();
            if (total > 0) {
                // Always report as "embedding" phase so frontend shows correct UI state
                // Even in passthrough mode, we're still in the embedding phase of the pipeline
                int embeddedQueuedCount = actualEmbeddingDone ? processed : embeddedQueue.size();
                int endProgressPercent = total > 0 ? Math.min(95, (embeddedQueuedCount * 100) / total) : 0;

                // Update batch metrics for END state
                currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                        .batchNumber(batchNum)
                        .totalBatches(estimatedTotalBatches)
                        .inputTexts(batch.size())
                        .inputTokens(0) // Already processed
                        .maxSequenceLength(0)
                        .avgSequenceLength(0)
                        .outputVectors(actualEmbeddingDone ? batch.size() : 0)
                        .embeddingDimension(embeddingDimension)
                        .outputSizeBytes(outputSizeBytes)
                        .inferenceTimeMs(actualEmbeddingDone ? totalBatchTime : 0)
                        .totalBatchTimeMs(totalBatchTime)
                        .currentStep("COMPLETE")
                        .heartbeatSeconds(0)
                        .stepStartTimeMs(0)
                        .isStuck(false)
                        .tokensPerSecond(0)
                        .embeddingsPerSecond(batchThroughput)
                        .batchThroughput(batchThroughput)
                        .modelName(modelName)
                        .deviceType("CPU")
                        .statusLevel("COMPLETE")
                        .build();

                reportProgressWithWorkers("embedding", endProgressPercent,
                        String.format("END BATCH %d: %s %d chunks in %dms (%.1f/s) [%d/%d total, dim=%d]",
                                batchNum,
                                actualEmbeddingDone ? "Embedded" : "Queued",
                                batch.size(), totalBatchTime, batchThroughput,
                                embeddedQueuedCount, total, embeddingDimension));
            }
            // === END END BATCH ===
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (OutOfMemoryError oom) {
            // Handle OOM gracefully - log, trigger GC, and continue with next batch
            logger.error("OUT OF MEMORY in embedding worker {} processing batch {} ({} chunks)! " +
                    "Consider reducing batch size (current: {}) or increasing heap (-Xmx).",
                    workerId, batchNum, batch.size(), batchSize);
            // Try to recover some memory
            System.gc();
            // Update last error for progress reporting
            lastError = "OutOfMemoryError in batch " + batchNum + " - reduce batch size or increase heap";
        } catch (Exception e) {
            logger.error("Embedding batch failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Generate embeddings for a batch of chunks using the legacy method.
     * 
     * @deprecated Use generateEmbeddingsOptimized instead
     */
    @Deprecated
    private List<float[]> generateEmbeddings(List<RetrievedDoc> chunks) {
        return generateEmbeddingsOptimized(chunks);
    }

    /**
     * Optimized embedding generation that uses the new embedBatch interface.
     *
     * <h2>Key Optimizations</h2>
     * <ul>
     * <li>Uses embedBatch() which handles batch processing internally</li>
     * <li>Avoids creating intermediate INDArray that needs extraction</li>
     * <li>Returns float[] directly for immediate indexing use</li>
     * <li>Memory efficient - no temporary matrices</li>
     * </ul>
     */
    private List<float[]> generateEmbeddingsOptimized(List<RetrievedDoc> chunks) {
        logger.debug("generateEmbeddingsOptimized called: chunks={}, embeddingModel={}",
                chunks != null ? chunks.size() : "null",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL");

        if (embeddingModel == null) {
            logger.debug("generateEmbeddingsOptimized: embeddingModel is null, passthrough mode");
            return null;
        }

        try {
            // Extract texts from chunks
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedDoc chunk : chunks) {
                String content = chunk.getContent();
                texts.add(content != null ? content : "");
            }

            logger.debug("generateEmbeddingsOptimized: calling embedBatch with {} texts on {}",
                    texts.size(), embeddingModel.getClass().getSimpleName());

            // Use the optimized batch embedding method
            // This processes all texts in a single model call and returns float[] directly
            long startTime = System.currentTimeMillis();

            // Start a heartbeat thread to show we're alive during long inference
            // Also updates currentEmbeddingBatchMetrics so the UI can show real-time
            // progress
            final Thread currentThread = Thread.currentThread();
            final java.util.concurrent.atomic.AtomicBoolean inferenceComplete = new java.util.concurrent.atomic.AtomicBoolean(
                    false);
            final int batchSize = texts.size();
            final String modelName = embeddingModel.getClass().getSimpleName();
            final int avgTextLength = texts.stream().mapToInt(String::length).sum() / Math.max(1, texts.size());
            final int maxTextLength = texts.stream().mapToInt(String::length).max().orElse(0);
            final int totalChars = texts.stream().mapToInt(String::length).sum();
            final int estimatedTokens = totalChars / 4; // ~4 chars per token
            // Estimate input tensor shape: [batch_size, max_seq_length]
            // BERT models typically cap at 512 tokens, so estimate max sequence length
            final int estimatedMaxSeqLen = Math.min(512, (maxTextLength / 4) + 2); // +2 for [CLS] and [SEP]
            final int estimatedAvgSeqLen = Math.min(512, (avgTextLength / 4) + 2);
            // Get embedding dimension for output tensor shape
            final int embeddingDim = embeddingModel.dimensions();

            // Capture chunk info for heartbeat display
            final int globalChunkStart = chunksEmbedded.get(); // Current position before this batch
            final int globalChunkEnd = globalChunkStart + batchSize - 1;
            final int totalChunksExpected = chunksCreated.get();

            // Get source documents from chunks metadata
            final java.util.Set<String> sourceDocuments = new java.util.LinkedHashSet<>();
            final List<String> chunkIds = new ArrayList<>();
            for (RetrievedDoc chunk : chunks) {
                chunkIds.add(chunk.getId() != null ? chunk.getId() : "unknown");
                Map<String, Object> meta = chunk.getMetadata();
                if (meta != null) {
                    Object source = meta.get("source");
                    if (source == null)
                        source = meta.get("filename");
                    if (source == null)
                        source = meta.get("file_name");
                    if (source != null) {
                        String srcStr = source.toString();
                        // Extract just filename from path
                        int lastSlash = Math.max(srcStr.lastIndexOf('/'), srcStr.lastIndexOf('\\'));
                        if (lastSlash >= 0)
                            srcStr = srcStr.substring(lastSlash + 1);
                        sourceDocuments.add(srcStr);
                    }
                }
            }
            final String sourcesStr = sourceDocuments.isEmpty() ? "unknown"
                    : (sourceDocuments.size() <= 3 ? String.join(", ", sourceDocuments)
                            : sourceDocuments.stream().limit(2).collect(java.util.stream.Collectors.joining(", ")) +
                                    " + " + (sourceDocuments.size() - 2) + " more");

            // Get preview of first chunk content
            final String firstChunkPreview = texts.isEmpty() ? ""
                    : texts.get(0).substring(0, Math.min(50, texts.get(0).length())).replace("\n", " ").trim() + "...";
            Thread heartbeat = new Thread(() -> {
                int tick = 0;
                while (!inferenceComplete.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(5000); // 5 second heartbeat
                        tick++;
                        if (!inferenceComplete.get()) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            int heartbeatSecs = tick * 5;

                            // Progressive status levels based on elapsed time
                            String statusLevel;
                            if (elapsed > 300000) { // > 5 minutes
                                statusLevel = "EXTREMELY_SLOW";
                            } else if (elapsed > 120000) { // > 2 minutes
                                statusLevel = "VERY_SLOW";
                            } else if (elapsed > 60000) { // > 1 minute
                                statusLevel = "SLOW";
                            } else if (elapsed > 30000) { // > 30 seconds
                                statusLevel = "PROCESSING";
                            } else {
                                statusLevel = "RUNNING";
                            }
                            boolean isStuck = elapsed > 30000;

                            // Format elapsed time as MM:SS for better readability
                            String elapsedFormatted;
                            if (heartbeatSecs >= 60) {
                                int mins = heartbeatSecs / 60;
                                int secs = heartbeatSecs % 60;
                                elapsedFormatted = String.format("%dm %02ds", mins, secs);
                            } else {
                                elapsedFormatted = heartbeatSecs + "s";
                            }

                            // Memory stats for visibility
                            Runtime runtime = Runtime.getRuntime();
                            long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                            long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
                            int memoryPercent = (int) ((usedMemoryMB * 100) / maxMemoryMB);

                            // Update metrics for real-time UI display
                            // Calculate estimated total batches based on chunks created
                            int totalChunks = chunksCreated.get();
                            int currentBatch = embeddingBatchesProcessed.get();
                            int estimatedTotal = totalChunks > 0
                                    ? Math.max(currentBatch, (totalChunks + batchSize - 1) / batchSize)
                                    : currentBatch;

                            // Estimate remaining time if we have completed batches
                            String etaInfo = "";
                            if (currentBatch > 1 && estimatedTotal > currentBatch) {
                                // Use average time per batch from previous batches (rough estimate)
                                int remainingBatches = estimatedTotal - currentBatch;
                                // Assume current batch time is representative
                                long estRemainingMs = elapsed * remainingBatches;
                                if (estRemainingMs > 60000) {
                                    etaInfo = String.format(" | ETA: ~%dm", estRemainingMs / 60000);
                                } else if (estRemainingMs > 0) {
                                    etaInfo = String.format(" | ETA: ~%ds", estRemainingMs / 1000);
                                }
                            }

                            // Get actual batch info from embedding model if available
                            EmbeddingModel.BatchInfo actualBatchInfo = embeddingModel.getCurrentBatchInfo();
                            boolean hasActualInfo = actualBatchInfo != null && actualBatchInfo.numChunks() > 0;

                            // Build tensor shape strings
                            String inputShape = hasActualInfo ? actualBatchInfo.inputShapeString()
                                    : "[" + batchSize + ", " + estimatedMaxSeqLen + "]";
                            String outputShape = hasActualInfo ? actualBatchInfo.outputShapeString()
                                    : "[" + batchSize + ", " + embeddingDim + "]";

                            // Build ETA message
                            String etaMsg = "";
                            if (currentBatch > 1 && estimatedTotal > currentBatch) {
                                int remainingBatches = estimatedTotal - currentBatch;
                                long estRemainingMs = elapsed * remainingBatches;
                                if (estRemainingMs > 60000) {
                                    etaMsg = String.format("~%dm remaining", estRemainingMs / 60000);
                                } else if (estRemainingMs > 0) {
                                    etaMsg = String.format("~%ds remaining", estRemainingMs / 1000);
                                }
                            }

                            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                                    .batchNumber(currentBatch)
                                    .totalBatches(estimatedTotal)
                                    .inputTexts(batchSize)
                                    .inputTokens(estimatedTokens)
                                    .maxSequenceLength(estimatedMaxSeqLen)
                                    .avgSequenceLength(estimatedAvgSeqLen)
                                    .outputVectors(batchSize) // Each chunk produces one embedding vector
                                    .embeddingDimension(embeddingDim)
                                    .outputSizeBytes((long) batchSize * embeddingDim * 4) // 4 bytes per float
                                    // Timing - still in progress
                                    .tokenizationTimeMs(0)
                                    .inferenceTimeMs(elapsed)
                                    .totalBatchTimeMs(elapsed)
                                    .paddingTimeMs(0)
                                    .tensorCreationTimeMs(0)
                                    .forwardPassTimeMs(elapsed)
                                    .extractionTimeMs(0)
                                    // Heartbeat/liveness - key info for UI
                                    .currentStep("FORWARD_PASS")
                                    .heartbeatSeconds(heartbeatSecs)
                                    .stepStartTimeMs(startTime)
                                    .isStuck(isStuck)
                                    // Throughput - 0 until complete
                                    .tokensPerSecond(0)
                                    .embeddingsPerSecond(0)
                                    .batchThroughput(0)
                                    .modelName(modelName)
                                    .deviceType("CPU")
                                    .isBatched(true)
                                    // ========== NEW DETAILED FIELDS ==========
                                    // Source documents
                                    .sourceDocuments(sourcesStr)
                                    .sourceDocumentCount(sourceDocuments.size())
                                    // Tensor shapes
                                    .inputTensorShape(inputShape)
                                    .outputTensorShape(outputShape)
                                    .actualInputShape(hasActualInfo ? actualBatchInfo.inputShapeString() : null)
                                    .actualOutputShape(hasActualInfo ? actualBatchInfo.outputShapeString() : null)
                                    // Status
                                    .statusLevel(statusLevel)
                                    .etaMessage(etaMsg)
                                    .build();

                            // Detailed console output for long-running batches
                            StringBuilder logMsg = new StringBuilder();
                            logMsg.append("[EMBEDDING-HEARTBEAT] [").append(statusLevel).append("] ");
                            logMsg.append("Batch ").append(currentBatch).append("/").append(estimatedTotal);
                            logMsg.append(" | Elapsed: ").append(elapsedFormatted);
                            if (hasActualInfo) {
                                logMsg.append(" | Step: ").append(actualBatchInfo.step());
                            }
                            logMsg.append("\n    Chunks: ").append(globalChunkStart).append("-").append(globalChunkEnd);
                            logMsg.append(" of ").append(totalChunksExpected > 0 ? totalChunksExpected : "?");
                            logMsg.append(" (").append(batchSize).append(" in this batch)");
                            logMsg.append("\n    Sources: ").append(sourcesStr);
                            logMsg.append("\n    Preview: \"").append(firstChunkPreview).append("\"");

                            // Show actual tensor shapes if available, otherwise estimates
                            if (hasActualInfo) {
                                logMsg.append("\n    Input Tensor:  ").append(actualBatchInfo.inputShapeString());
                                logMsg.append(" = ").append(actualBatchInfo.numChunks()).append(" chunks × ");
                                logMsg.append(actualBatchInfo.maxSeqLength()).append(" tokens/chunk (ACTUAL)");
                                logMsg.append("\n    Output Tensor: ").append(actualBatchInfo.outputShapeString());
                                logMsg.append(" = ").append(actualBatchInfo.numChunks()).append(" chunks × ");
                                logMsg.append(actualBatchInfo.embeddingDim()).append("-dim embeddings (ACTUAL)");
                                logMsg.append("\n    Token Stats: ").append(actualBatchInfo.totalTokens())
                                        .append(" tokens total (ACTUAL)");
                            } else {
                                logMsg.append("\n    Input Tensor:  [").append(batchSize).append(", ")
                                        .append(estimatedMaxSeqLen).append("]");
                                logMsg.append(" = ").append(batchSize).append(" chunks × ").append(estimatedMaxSeqLen)
                                        .append(" max tokens/chunk (estimated)");
                                logMsg.append("\n    Output Tensor: [").append(batchSize).append(", ")
                                        .append(embeddingDim).append("]");
                                logMsg.append(" = ").append(batchSize).append(" chunks × ").append(embeddingDim)
                                        .append("-dim embeddings (estimated)");
                                logMsg.append("\n    Token Stats: ~").append(estimatedTokens)
                                        .append(" tokens total (estimated)");
                            }
                            logMsg.append("\n    Char Stats:  ").append(totalChars).append(" chars total");
                            logMsg.append(", avg ").append(avgTextLength).append(" chars/chunk");
                            logMsg.append(", max ").append(maxTextLength).append(" chars/chunk");
                            logMsg.append("\n    Memory: ").append(usedMemoryMB).append("/").append(maxMemoryMB)
                                    .append("MB (").append(memoryPercent).append("%)");
                            logMsg.append(" | Model: ").append(modelName);
                            logMsg.append("\n    Thread: ").append(currentThread.getName()).append(" [")
                                    .append(currentThread.getState()).append("]");
                            if (!etaInfo.isEmpty()) {
                                logMsg.append(etaInfo);
                            }
                            System.err.println(logMsg);
                            System.err.flush();

                            // CRITICAL: Push progress update via WebSocket so UI shows real-time heartbeat
                            // info
                            // Build informative status message for UI
                            StringBuilder uiStatus = new StringBuilder();
                            uiStatus.append("Batch ").append(currentBatch).append("/").append(estimatedTotal);
                            uiStatus.append(" | Chunks ").append(globalChunkStart).append("-").append(globalChunkEnd);
                            uiStatus.append("/").append(totalChunksExpected > 0 ? totalChunksExpected : "?");
                            uiStatus.append(" (").append(elapsedFormatted).append(")");
                            if (!statusLevel.equals("RUNNING")) {
                                uiStatus.append(" [").append(statusLevel).append("]");
                            }
                            // Show tensor shapes for SLOW or worse batches - use actual if available
                            if (!statusLevel.equals("RUNNING") && !statusLevel.equals("PROCESSING")) {
                                if (hasActualInfo) {
                                    uiStatus.append(" | ").append(actualBatchInfo.inputShapeString());
                                    uiStatus.append("→").append(actualBatchInfo.outputShapeString());
                                } else {
                                    uiStatus.append(" | [").append(batchSize).append("×").append(estimatedMaxSeqLen)
                                            .append("]");
                                    uiStatus.append("→[").append(batchSize).append("×").append(embeddingDim)
                                            .append("]");
                                }
                            }
                            // Show source files for slow batches
                            if (!statusLevel.equals("RUNNING") && sourceDocuments.size() > 0) {
                                uiStatus.append(" | ").append(sourcesStr);
                            }
                            if (memoryPercent > 80) {
                                uiStatus.append(" | Mem:").append(memoryPercent).append("%");
                            }
                            if (!etaInfo.isEmpty()) {
                                uiStatus.append(etaInfo);
                            }

                            try {
                                reportProgressWithWorkers("embedding", 0, uiStatus.toString());
                            } catch (Exception e) {
                                System.err
                                        .println("[EMBEDDING-HEARTBEAT] Failed to report progress: " + e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "embedding-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            System.err.println("[EMBEDDING-METRICS] Calling embedBatch: texts=" + texts.size() +
                    ", avgLen=" + (texts.stream().mapToInt(String::length).sum() / Math.max(1, texts.size())) +
                    ", model=" + embeddingModel.getClass().getSimpleName());
            System.err.flush();

            List<float[]> embeddings;

            // Use timeout to prevent indefinite blocking on native calls
            // Default: 5 minutes per batch (adjust based on batch size and hardware)
            final long EMBED_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes

            // Submit embedding to a separate thread with timeout
            final List<String> finalTexts = texts;
            java.util.concurrent.ExecutorService timeoutExecutor =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "embed-batch-timeout");
                    t.setDaemon(true);
                    return t;
                });

            try {
                java.util.concurrent.Future<List<float[]>> embedFuture =
                    timeoutExecutor.submit(() -> embeddingModel.embedBatch(finalTexts));

                try {
                    embeddings = embedFuture.get(EMBED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    // Timeout occurred - the embedding is stuck
                    embedFuture.cancel(true);  // Try to interrupt

                    // Log detailed info about the problematic batch
                    logger.error("EMBEDDING TIMEOUT after {}ms for batch of {} texts!",
                            EMBED_TIMEOUT_MS, texts.size());
                    logger.error("Batch details: avgLen={}, maxLen={}, minLen={}",
                            texts.stream().mapToInt(String::length).average().orElse(0),
                            texts.stream().mapToInt(String::length).max().orElse(0),
                            texts.stream().mapToInt(String::length).min().orElse(0));

                    // Log first few characters of each text for debugging
                    for (int i = 0; i < Math.min(3, texts.size()); i++) {
                        String preview = texts.get(i);
                        if (preview.length() > 100) {
                            preview = preview.substring(0, 100) + "...";
                        }
                        logger.error("  Text[{}] preview: {}", i, preview.replace("\n", "\\n"));
                    }

                    System.err.println("[EMBEDDING-TIMEOUT] Batch timed out after " + EMBED_TIMEOUT_MS +
                            "ms - skipping batch of " + texts.size() + " texts");
                    System.err.flush();

                    // Return null to skip this batch - pipeline will continue with next batch
                    return null;
                } catch (java.util.concurrent.ExecutionException ee) {
                    // Fix: Handle OutOfMemoryError and other Errors properly
                    // OutOfMemoryError extends Error, not Exception, so casting to Exception fails
                    Throwable cause = ee.getCause();
                    if (cause instanceof OutOfMemoryError) {
                        logger.error("OUT OF MEMORY during embedding batch of {} texts! " +
                                "Consider reducing batch size or increasing heap.", texts.size());
                        // Return null to skip this batch gracefully instead of crashing
                        return null;
                    } else if (cause instanceof Error) {
                        // Other errors (StackOverflowError, etc.) - log and skip batch
                        logger.error("Critical error during embedding: {}", cause.getClass().getSimpleName(), cause);
                        return null;
                    } else if (cause instanceof Exception) {
                        throw (Exception) cause;
                    } else if (cause != null) {
                        throw new RuntimeException("Embedding failed", cause);
                    }
                    throw ee;
                }
            } finally {
                inferenceComplete.set(true);
                heartbeat.interrupt();
                // Shutdown the timeout executor to prevent thread leak
                timeoutExecutor.shutdownNow();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.err.println("[EMBEDDING-METRICS] embedBatch returned: embeddings=" +
                    (embeddings != null ? embeddings.size() : "null") + ", elapsed=" + elapsed + "ms" +
                    (embeddings != null && !embeddings.isEmpty() && embeddings.get(0) != null
                            ? ", dim=" + embeddings.get(0).length
                            : ""));
            System.err.flush();

            logger.info("generateEmbeddingsOptimized: embedBatch returned {} embeddings in {}ms",
                    embeddings != null ? embeddings.size() : 0, elapsed);

            // Validate we got the expected number of embeddings
            if (embeddings == null || embeddings.isEmpty()) {
                logger.warn("Embedding model returned null or empty for batch of {} texts", texts.size());
                return null;
            }

            if (embeddings.size() != chunks.size()) {
                logger.warn("Embedding count mismatch: got {} embeddings for {} chunks",
                        embeddings.size(), chunks.size());
            }

            // Check for empty embeddings (failures)
            int failures = 0;
            for (float[] embedding : embeddings) {
                if (embedding == null || embedding.length == 0) {
                    failures++;
                }
            }
            if (failures > 0) {
                logger.debug("Batch had {} failed embeddings out of {}", failures, embeddings.size());
            }

            return embeddings;
        } catch (OutOfMemoryError oom) {
            // Catch OOM at the outer level too (not wrapped in ExecutionException)
            logger.error("OUT OF MEMORY in generateEmbeddingsOptimized for {} texts! " +
                    "Heap exhausted - reduce batch size or increase -Xmx", chunks.size());
            // Attempt to free memory by suggesting GC (may help in some cases)
            System.gc();
            return null;
        } catch (Exception e) {
            logger.error("Failed to generate embeddings: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Start multiple indexing workers that consume from embeddedQueue concurrently.
     * Multiple workers improve throughput by processing batches in parallel.
     */
    private Future<List<String>> startIndexingWorker() {
        // Initialize all indexing worker statuses
        for (int i = 0; i < indexingParallelism; i++) {
            workerStatuses.put("index-" + i, PipelineProgress.WorkerStatus.idle(i, "indexing"));
        }

        // Thread-safe collection for aggregating results from all workers
        List<String> aggregatedProcessedIds = Collections.synchronizedList(new ArrayList<>());
        // Track per-worker processed counts
        ConcurrentHashMap<Integer, AtomicInteger> perWorkerProcessed = new ConcurrentHashMap<>();

        // Start multiple indexing workers
        List<CompletableFuture<Void>> workerFutures = new ArrayList<>(indexingParallelism);

        for (int workerIdx = 0; workerIdx < indexingParallelism; workerIdx++) {
            final int workerId = workerIdx;
            final String workerKey = "index-" + workerId;
            perWorkerProcessed.put(workerId, new AtomicInteger(0));

            CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(() -> {
                logger.info("Indexing worker {} started on thread {}", workerId, Thread.currentThread().getName());
                List<String> localProcessedIds = new ArrayList<>();

                // Watchdog variables for robust exit detection
                int consecutiveEmptyPolls = 0;
                final int MAX_EMPTY_POLLS_AFTER_COMPLETE = 10; // 10 * 50ms = 500ms grace period

                // Progress reporting while waiting - track last report time
                long lastWaitingProgressReportMs = System.currentTimeMillis();
                final long WAITING_PROGRESS_REPORT_INTERVAL_MS = 200; // Report every 200ms while waiting

                while (!cancelled && !Thread.currentThread().isInterrupted()) {
                    try {
                        // Update status to waiting with cumulative throughput
                        long elapsedMs = System.currentTimeMillis() - startTimeMs.get();
                        int workerTotal = perWorkerProcessed.get(workerId).get();
                        double cumulativeThroughput = elapsedMs > 0 ? (workerTotal * 1000.0 / elapsedMs) : 0;
                        workerStatuses.put(workerKey,
                                PipelineProgress.WorkerStatus.waitingWithThroughput(workerId, "indexing",
                                        workerTotal, cumulativeThroughput));

                        // Poll with timeout to check for completion
                        EmbeddedBatch batch = embeddedQueue.poll(this.pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);

                        if (batch != null) {
                            // Reset watchdog on successful dequeue
                            consecutiveEmptyPolls = 0;
                            processIndexBatchForWorker(batch, localProcessedIds, workerId,
                                    perWorkerProcessed.get(workerId));
                            // Reset waiting report timer after processing
                            lastWaitingProgressReportMs = System.currentTimeMillis();
                        } else {
                            // Queue was empty on this poll
                            if (embeddingComplete) {
                                consecutiveEmptyPolls++;
                            }

                            // Periodically report progress while waiting for work
                            long now = System.currentTimeMillis();
                            if (now - lastWaitingProgressReportMs >= WAITING_PROGRESS_REPORT_INTERVAL_MS) {
                                lastWaitingProgressReportMs = now;
                                int indexed = chunksIndexed.get();
                                int total = chunksCreated.get();
                                int progressPercent = total > 0 ? Math.min(99, (indexed * 100) / total) : 0;
                                reportProgressWithWorkers("indexing", progressPercent,
                                        String.format("Indexed %d/%d chunks (waiting for embeddings, queue=%d)",
                                                indexed, total, embeddedQueue.size()));
                            }
                        }

                        // Robust exit condition:
                        // 1. Standard exit: embedding complete AND queue empty (checked atomically-ish)
                        // 2. Watchdog exit: embedding complete AND multiple consecutive empty polls
                        boolean queueEmpty = embeddedQueue.isEmpty();
                        boolean standardExit = embeddingComplete && queueEmpty;
                        boolean watchdogExit = embeddingComplete
                                && consecutiveEmptyPolls >= MAX_EMPTY_POLLS_AFTER_COMPLETE;

                        if (standardExit || watchdogExit) {
                            // Double-check the queue is really empty before exiting
                            if (embeddedQueue.isEmpty()) {
                                if (watchdogExit && !standardExit) {
                                    logger.info("Indexing worker {}: exiting via watchdog after {} empty polls",
                                            workerId, consecutiveEmptyPolls);
                                }
                                break;
                            } else {
                                // Queue not empty after all - reset and continue
                                consecutiveEmptyPolls = 0;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (OutOfMemoryError oom) {
                        // Handle OOM gracefully - log, trigger GC, and try to continue
                        logger.error("OUT OF MEMORY in indexing worker {} processing batch! " +
                                "Consider reducing queue capacity or increasing heap (-Xmx).", workerId);
                        // Try to recover some memory
                        System.gc();
                        lastError = "OutOfMemoryError in indexing worker " + workerId + " - increase heap";
                        // Sleep briefly to let GC run
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } catch (RuntimeException e) {
                        logger.error("Indexing worker {}: error processing batch, continuing: {}",
                                workerId, e.getMessage());
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // Mark this worker as complete
                long elapsed = System.currentTimeMillis() - startTimeMs.get();
                int processed = perWorkerProcessed.get(workerId).get();
                double throughput = elapsed > 0 ? (processed * 1000.0 / elapsed) : 0;
                workerStatuses.put(workerKey,
                        PipelineProgress.WorkerStatus.complete(workerId, "indexing", processed, throughput));

                // Add local results to aggregated list
                aggregatedProcessedIds.addAll(localProcessedIds);

                logger.info("Indexing worker {} finished: {} documents indexed", workerId, localProcessedIds.size());
            }, indexingExecutor);

            workerFutures.add(workerFuture);
        }

        logger.info("Started {} indexing workers", indexingParallelism);

        // Return a future that completes when ALL workers are done
        return CompletableFuture.allOf(workerFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    logger.info("All {} indexing workers completed, total indexed: {}",
                            indexingParallelism, aggregatedProcessedIds.size());
                    return aggregatedProcessedIds;
                });
    }

    /**
     * Process an embedded batch for a specific worker (supports multiple concurrent
     * workers).
     */
    private void processIndexBatchForWorker(EmbeddedBatch batch, List<String> processedIds,
            int workerId, AtomicInteger workerProcessed) {
        if (batch.chunks().isEmpty() || cancelled)
            return;

        String workerKey = "index-" + workerId;

        try {
            int batchNum = indexingBatchesProcessed.get() + 1;
            int batchSize = batch.chunks().size();

            // === BEGIN INDEX: Report batch indexing start ===
            workerStatuses.put(workerKey,
                    PipelineProgress.WorkerStatus.processing(workerId, "indexing",
                            workerProcessed.get(), batchSize, 0,
                            "batch " + batchNum));

            int indexedBefore = chunksIndexed.get();
            int totalChunks = chunksCreated.get();
            String indexMode = vectorOnlyMode ? "vector-only" : (parallelIndexingEnabled ? "parallel" : "sequential");
            int progressPercent = 60 + (totalChunks > 0 ? (int) ((indexedBefore / (double) totalChunks) * 35) : 0);
            reportProgressWithWorkers("indexing", progressPercent,
                    String.format("BEGIN INDEX batch %d: %d chunks (%s mode, worker %d) [indexed so far: %d/%d]",
                            batchNum, batchSize, indexMode, workerId, indexedBefore, totalChunks));
            // === END BEGIN INDEX ===

            long batchStartTime = System.currentTimeMillis();

            // Index the batch WITH pre-computed embeddings to avoid re-embedding
            // Track ACTUAL persisted count, not just batch size sent
            int actualVectorIndexed = 0;
            // batchSize already declared at top of method

            // VECTOR-ONLY MODE: Skip keyword indexing, only update vector store
            // This is used when populating vector store from existing keyword index
            if (vectorOnlyMode && batch.embeddings() != null && !batch.embeddings().isEmpty()) {
                logger.debug("Worker {}: VECTOR-ONLY INDEXING batch ({} chunks, {} embeddings) - skipping keyword index",
                        workerId, batch.chunks().size(), batch.embeddings().size());
                try {
                    actualVectorIndexed = indexerService.indexToVectorStoreOnly(batch.chunks(), batch.embeddings());
                    if (actualVectorIndexed != batchSize) {
                        logger.debug("Worker {}: VECTOR-ONLY indexing returned {} actual vs {} batch size",
                                workerId, actualVectorIndexed, batchSize);
                    }
                } catch (java.io.IOException e) {
                    logger.error("Worker {}: VECTOR-ONLY INDEXING FAILED: {}", workerId, e.getMessage());
                    throw new RuntimeException("Vector-only indexing failed", e);
                }
            } else if (parallelIndexingEnabled && batch.embeddings() != null && !batch.embeddings().isEmpty()) {
                // PARALLEL MODE: Run keyword and vector indexing concurrently
                logger.debug("Worker {}: PARALLEL INDEXING batch ({} chunks, {} embeddings)",
                        workerId, batch.chunks().size(), batch.embeddings().size());
                java.util.concurrent.CompletableFuture<ai.kompile.core.indexers.IndexerService.IndexingResult> indexingFuture =
                        indexerService.indexDocumentsParallel(batch.chunks(), batch.embeddings());

                try {
                    ai.kompile.core.indexers.IndexerService.IndexingResult result = indexingFuture.get(60, TimeUnit.SECONDS);
                    // Use the actual vector indexed count from the result
                    actualVectorIndexed = result.vectorIndexed();
                    if (actualVectorIndexed != batchSize) {
                        logger.warn("Worker {}: PARALLEL INDEXING returned {} actual vector indexed vs {} batch size",
                                workerId, actualVectorIndexed, batchSize);
                    }
                } catch (java.util.concurrent.TimeoutException e) {
                    logger.error("Worker {}: PARALLEL INDEXING TIMEOUT after 60s for {} chunks",
                            workerId, batch.chunks().size());
                    indexingFuture.cancel(true);
                    lastError = "Parallel indexing timed out after 60 seconds";
                    throw new RuntimeException("Parallel indexing timed out");
                } catch (java.util.concurrent.ExecutionException e) {
                    logger.error("Worker {}: PARALLEL INDEXING FAILED: {}", workerId, e.getCause().getMessage());
                    throw new RuntimeException("Parallel indexing failed", e.getCause());
                }
            } else if (batch.embeddings() != null && !batch.embeddings().isEmpty()) {
                // SEQUENTIAL MODE with embeddings
                logger.debug("Worker {}: SEQUENTIAL INDEXING batch ({} chunks, {} embeddings)",
                        workerId, batch.chunks().size(), batch.embeddings().size());
                indexerService.indexDocumentsWithFloatEmbeddings(batch.chunks(), batch.embeddings());
                // Sequential mode doesn't return count yet, assume all indexed
                actualVectorIndexed = batchSize;
            } else {
                // PASSTHROUGH MODE: No embeddings provided
                // The indexer will handle embedding if needed
                logger.debug("Worker {}: PASSTHROUGH INDEXING batch ({} chunks)",
                        workerId, batch.chunks().size());
                indexerService.indexDocuments(batch.chunks());
                actualVectorIndexed = batchSize;
                // In passthrough mode, also count as embedded since no separate embedding step
                // This fulfills the promise in embedding worker: "it will be counted when indexed"
                chunksEmbedded.addAndGet(batchSize);
            }

            // Track processed IDs
            for (RetrievedDoc chunk : batch.chunks()) {
                if (chunk.getId() != null) {
                    processedIds.add(chunk.getId());
                }
            }

            // Use the ACTUAL count of documents persisted to vector store
            int indexed = chunksIndexed.addAndGet(actualVectorIndexed);
            workerProcessed.addAndGet(batchSize);
            indexingWorkerProcessed.addAndGet(batchSize);
            indexingBatchesProcessed.incrementAndGet(); // Increment but reuse batchNum from start

            long elapsed = System.currentTimeMillis() - batchStartTime;
            double batchThroughput = elapsed > 0 ? (batchSize * 1000.0 / elapsed) : 0;

            // Update worker status with throughput
            workerStatuses.put(workerKey,
                    PipelineProgress.WorkerStatus.processing(workerId, "indexing",
                            workerProcessed.get(), batchSize, batchThroughput,
                            indexMode + " batch " + batchNum));

            logger.debug("Worker {}: indexed batch {} ({} chunks, {}) in {}ms ({}/sec)",
                    workerId, batchNum, batchSize, indexMode, elapsed, String.format("%.1f", batchThroughput));

            // === END INDEX: Report batch indexing completion ===
            int total = chunksCreated.get();
            if (total > 0) {
                int progressPercentEnd = 60 + (int) ((indexed / (double) total) * 35);
                reportProgressWithWorkers("indexing", progressPercentEnd,
                        String.format("END INDEX batch %d: %d chunks in %dms (%.1f/s) [%d/%d total, %s mode]",
                                batchNum, batchSize, elapsed, batchThroughput,
                                indexed, total, indexMode));
            }
            // === END END INDEX ===
        } catch (Exception e) {
            logger.error("Worker {}: indexing batch failed: {}", workerId, e.getMessage(), e);
            lastError = "Indexing failed: " + e.getMessage();
            throw new RuntimeException("Indexing batch failed", e);
        }
    }

    private RetrievedDoc convertToRetrievedDoc(Document doc) {
        return new RetrievedDoc(
                doc.getId() != null ? doc.getId() : UUID.randomUUID().toString(),
                doc.getText() != null ? doc.getText() : "",
                doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>());
    }

    private Document convertToSpringDocument(RetrievedDoc doc) {
        return new Document(
                doc.getId(),
                doc.getContent(),
                doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>());
    }

    private void reportProgressThrottled(String phase, int percent, String message) {
        long now = System.currentTimeMillis();
        long lastReport = lastProgressReportMs.get();
        if (now - lastReport >= PROGRESS_REPORT_INTERVAL_MS) {
            if (lastProgressReportMs.compareAndSet(lastReport, now)) {
                reportProgress(phase, percent, message);
            }
        }
    }

    private void reportProgress(String phase, int percent, String message) {
        reportProgressWithWorkers(phase, percent, message);
    }

    private void reportProgressWithWorkers(String phase, int ignoredPercent, String message) {
        if (progressCallback != null) {
            long elapsed = System.currentTimeMillis() - startTimeMs.get();
            int created = chunksCreated.get();
            int embedded = chunksEmbedded.get();
            int indexed = chunksIndexed.get();

            double throughput = elapsed > 0 ? (indexed * 1000.0 / elapsed) : 0;

            // Collect worker statuses
            List<PipelineProgress.WorkerStatus> workers = new ArrayList<>(workerStatuses.values());

            // DEBUG: Log worker collection
            logger.info("reportProgressWithWorkers: collecting workers - workerStatuses.size()={}, workers.size()={}",
                    workerStatuses.size(), workers.size());
            if (!workers.isEmpty()) {
                for (PipelineProgress.WorkerStatus ws : workers) {
                    logger.info("  Worker: id={}, type={}, status={}", ws.workerId(), ws.workerType(), ws.status());
                }
            }

            // Build queue status
            PipelineProgress.QueueStatus queueStatus = new PipelineProgress.QueueStatus(
                    chunkQueue.size(),
                    this.pipelineConfig.queueCapacity(),
                    embeddedQueue.size(),
                    this.pipelineConfig.queueCapacity());

            // Get current embedding batch metrics (may be null if not embedding)
            PipelineProgress.EmbeddingBatchMetrics batchMetrics = currentEmbeddingBatchMetrics;

            // Build progress object first, then calculate overall progress
            PipelineProgress progress = new PipelineProgress(
                    phase,
                    0, // Will be calculated
                    message,
                    documentsProcessed.get(),
                    created,
                    embedded,
                    indexed,
                    throughput,
                    config.getMemoryUsagePercent(),
                    chunkingParallelism,
                    embeddingParallelism,
                    workers,
                    queueStatus,
                    batchMetrics);

            // Calculate the actual overall progress from work completed
            int calculatedPercent = progress.calculateOverallProgress();

            // Create final progress with correct percentage
            PipelineProgress finalProgress = new PipelineProgress(
                    phase,
                    calculatedPercent,
                    message,
                    progress.documentsProcessed(),
                    progress.chunksCreated(),
                    progress.chunksEmbedded(),
                    progress.chunksIndexed(),
                    progress.chunksPerSecond(),
                    progress.memoryUsagePercent(),
                    progress.chunkingWorkers(),
                    progress.embeddingWorkers(),
                    progress.workerStatuses(),
                    progress.queueStatus(),
                    progress.currentEmbeddingBatch());

            try {
                progressCallback.accept(finalProgress);
            } catch (Exception e) {
                logger.warn("Progress callback failed: {}", e.getMessage());
            }
        }
    }

    public void cancel() {
        cancelled = true;
        logger.info("Pipeline cancellation requested");
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the last error that occurred during pipeline processing.
     * 
     * @return error message or null if no error
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Returns true if the pipeline encountered a fatal error.
     */
    public boolean hasError() {
        return lastError != null;
    }

    @Override
    public void close() {
        cancelled = true;

        try {
            chunkingExecutor.shutdown();
            if (!chunkingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                chunkingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            chunkingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            embeddingExecutor.shutdown();
            if (!embeddingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                embeddingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            embeddingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            indexingExecutor.shutdown();
            if (!indexingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                indexingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            indexingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // MEMORY LEAK FIX: Clear all queues to release held objects
        // Without this, RetrievedDoc and EmbeddedBatch objects remain in memory
        // after pipeline completion or cancellation
        int chunkQueueSize = chunkQueue.size();
        int embeddedQueueSize = embeddedQueue.size();
        chunkQueue.clear();
        embeddedQueue.clear();
        if (chunkQueueSize > 0 || embeddedQueueSize > 0) {
            logger.info("Cleared {} chunks and {} embedded batches from queues during close",
                    chunkQueueSize, embeddedQueueSize);
        }

        // MEMORY LEAK FIX: Clear worker status map to prevent unbounded growth
        // Worker statuses accumulate across pipeline runs if not cleared
        int workerStatusCount = workerStatuses.size();
        workerStatuses.clear();
        if (workerStatusCount > 0) {
            logger.debug("Cleared {} worker statuses during close", workerStatusCount);
        }

        // MEMORY LEAK FIX: Null out metrics reference to allow GC
        currentEmbeddingBatchMetrics = null;

        logger.info("ParallelIngestPipeline closed (parallelIndexing={})", parallelIndexingEnabled);
    }

    // Getters for monitoring
    public int getChunkingParallelism() {
        return chunkingParallelism;
    }

    public int getEmbeddingParallelism() {
        return embeddingParallelism;
    }

    public int getIndexingParallelism() {
        return indexingParallelism;
    }

    public boolean isParallelIndexingEnabled() {
        return parallelIndexingEnabled;
    }

    public int getChunkQueueSize() {
        return chunkQueue.size();
    }

    public int getEmbeddedQueueSize() {
        return embeddedQueue.size();
    }

    // ========== External Producer Support ==========
    // These methods enable streaming large documents directly into the pipeline
    // without going through the normal parallelChunkDocuments() flow.

    /**
     * Gets direct access to the chunk queue for external producers.
     *
     * <p>
     * This allows large document processors to stream chunks directly into the
     * pipeline,
     * bypassing the normal document chunking flow. The queue has built-in
     * backpressure -
     * if it's full, producers will block until space is available.
     * </p>
     *
     * <p>
     * <b>Important:</b> When using this method, you must:
     * </p>
     * <ol>
     * <li>Call {@link #startWorkersForExternalProducer()} before adding chunks</li>
     * <li>Call {@link #signalChunkingComplete()} when done adding chunks</li>
     * <li>Call {@link #awaitCompletion()} to wait for all processing to finish</li>
     * </ol>
     *
     * @return The blocking queue where chunks should be placed
     */
    public BlockingQueue<RetrievedDoc> getChunkQueue() {
        return chunkQueue;
    }

    /**
     * Starts embedding and indexing workers without starting the chunking phase.
     *
     * <p>
     * Use this when chunks will be provided by an external producer (like the
     * LargeDocumentPreprocessor) rather than the internal parallelChunkDocuments()
     * method.
     * </p>
     *
     * @return Tuple of embedding futures and indexing future for monitoring
     */
    public ExternalProducerHandle startWorkersForExternalProducer() {
        startTimeMs.set(System.currentTimeMillis());

        logger.info("=== STARTING EXTERNAL PRODUCER WORKERS ===");
        logger.info("embeddingParallelism={}, batchSize={}, embeddingModel={}, chunkQueue.size={}",
                embeddingParallelism, batchSize,
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL",
                chunkQueue.size());

        // Start embedding workers (consumers of chunk queue, producers of embedded
        // queue)
        logger.info("Calling startEmbeddingWorkers()...");
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();
        logger.info("startEmbeddingWorkers() returned {} futures", embeddingFutures.size());

        // Start indexing worker (consumer of embedded queue)
        logger.info("Calling startIndexingWorker()...");
        Future<List<String>> indexingFuture = startIndexingWorker();
        logger.info("startIndexingWorker() returned, workers are now running");

        // Start periodic progress reporter (every 1 second) for external producer mode
        ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-progress-ext");
            t.setDaemon(true);
            return t;
        });
        progressReporter.scheduleAtFixedRate(() -> {
            try {
                if (!cancelled) {
                    int created = chunksCreated.get();
                    int embedded = chunksEmbedded.get();
                    int indexed = chunksIndexed.get();
                    int chunkQueueSize = chunkQueue.size();
                    int embeddedQueueSize = embeddedQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (indexed * 1000.0 / elapsed) : 0;

                    // Determine phase based on actual work being done
                    String phase;
                    if (indexed > 0 || embeddingComplete) {
                        phase = "indexing";
                    } else if (embedded > 0) {
                        phase = "embedding";
                    } else if (created > 0) {
                        // Chunks created but not yet embedded
                        phase = "embedding";
                    } else {
                        phase = "chunking";
                    }

                    // Log worker status details
                    int activeWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "processing".equals(w.status())).count();
                    int waitingWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "waiting".equals(w.status())).count();

                    // Build per-worker status string
                    StringBuilder workerInfo = new StringBuilder();
                    workerInfo.append("\n  [Pipeline Status] phase=").append(phase)
                            .append(", chunkingComplete=").append(chunkingComplete)
                            .append(", embeddingComplete=").append(embeddingComplete);
                    workerInfo.append("\n  [Queues] chunkQueue=").append(chunkQueueSize)
                            .append(", embeddedQueue=").append(embeddedQueueSize);
                    workerInfo.append("\n  [Totals] created=").append(created)
                            .append(", embedded=").append(embedded)
                            .append(", indexed=").append(indexed)
                            .append(" (").append(String.format("%.1f", rate)).append("/sec)")
                            .append(" [workers: ").append(activeWorkers).append(" active, ")
                            .append(waitingWorkers).append(" waiting]");

                    // Log per-worker statuses
                    for (Map.Entry<String, PipelineProgress.WorkerStatus> entry : workerStatuses.entrySet()) {
                        PipelineProgress.WorkerStatus ws = entry.getValue();
                        workerInfo.append("\n  [Worker ").append(entry.getKey()).append("] ")
                                .append("status=").append(ws.status())
                                .append(", processed=").append(ws.itemsProcessed())
                                .append(", currentBatch=").append(ws.currentBatchSize())
                                .append(", throughput=").append(String.format("%.1f", ws.throughput()))
                                .append(", task=").append(ws.currentItem() != null ? ws.currentItem() : "idle");
                    }

                    logger.info("Pipeline Progress:{}", workerInfo);

                    reportProgressWithWorkers(phase, 0,
                            String.format("%d created, %d embedded, %d indexed (%.1f/sec) [Q:%d/%d]",
                                    created, embedded, indexed, rate, chunkQueueSize, embeddedQueueSize));
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter (ext): {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        return new ExternalProducerHandle(embeddingFutures, indexingFuture, progressReporter);
    }

    /**
     * Signals that external chunking is complete.
     *
     * <p>
     * Call this after all chunks have been added to the queue via an external
     * producer.
     * This allows the embedding workers to drain the queue and complete.
     * </p>
     */
    public void signalChunkingComplete() {
        chunkingComplete = true;
        logger.info("External chunking signaled complete: {} chunks created", chunksCreated.get());
    }

    /**
     * Increments the chunks created counter for external producers.
     *
     * @param count Number of chunks added
     */
    public void incrementChunksCreated(int count) {
        chunksCreated.addAndGet(count);
    }

    /**
     * Waits for all pipeline workers to complete processing.
     *
     * <p>
     * Call this after signaling chunking complete to wait for embedding and
     * indexing
     * to finish processing all queued chunks.
     * </p>
     *
     * @param handle The handle returned by
     *               {@link #startWorkersForExternalProducer()}
     * @return Pipeline result with statistics
     * @throws InterruptedException if interrupted while waiting
     */
    public PipelineResult awaitCompletion(ExternalProducerHandle handle) throws InterruptedException {
        try {
            // Wait for embedding to complete
            for (Future<?> future : handle.embeddingFutures()) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                }
            }
            embeddingComplete = true;

            logger.info("Embedding complete: {} chunks embedded in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            // Wait for indexing to complete
            List<String> processedIds;
            try {
                processedIds = handle.indexingFuture().get(30, TimeUnit.MINUTES);
            } catch (TimeoutException | ExecutionException e) {
                logger.error("Indexing failed: {}", e.getMessage(), e);
                processedIds = new ArrayList<>();
            }

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (chunksIndexed.get() * 1000.0 / totalTimeMs) : 0;

            reportProgress("COMPLETED", 100,
                    String.format("=== PIPELINE COMPLETE (external producer) === %d chunks created -> %d indexed in %dms (%.1f chunks/sec)",
                            chunksCreated.get(), chunksIndexed.get(), totalTimeMs, chunksPerSec));

            logger.info("========== PIPELINE COMPLETE (external producer): {} chunks created -> {} indexed in {}ms ({} chunks/sec) ==========",
                    chunksCreated.get(), chunksIndexed.get(), totalTimeMs, String.format("%.1f", chunksPerSec));

            return new PipelineResult(
                    documentsProcessed.get(),
                    chunksCreated.get(),
                    chunksIndexed.get(),
                    totalTimeMs,
                    processedIds);
        } finally {
            // CRITICAL: Stop the progress reporter to prevent heartbeat from continuing
            // after job completion
            handle.shutdown();
            logger.debug("Progress reporter shut down after pipeline completion");
        }
    }

    /**
     * Handle for managing external producer workflow.
     */
    public record ExternalProducerHandle(
            List<Future<?>> embeddingFutures,
            Future<List<String>> indexingFuture,
            ScheduledExecutorService progressReporter) {
        /**
         * Shuts down the progress reporter. Call this after awaitCompletion.
         */
        public void shutdown() {
            if (progressReporter != null) {
                progressReporter.shutdownNow();
            }
        }
    }
}
