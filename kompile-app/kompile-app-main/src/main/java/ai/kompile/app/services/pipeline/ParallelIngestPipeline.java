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
import ai.kompile.app.subprocess.IngestSubprocessMain;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog;
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
 * Parallel ingest pipeline with separate stages for chunking, indexing, and embedding.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * Phase 1: PARALLEL CHUNKING (ForkJoinPool)
 *   - Documents are chunked concurrently
 *   - Each document is independent, allowing full parallelization
 *
 * Phase 2: PARALLEL INDEXING (writes to Lucene)
 *   - Chunks are written to Lucene keyword index
 *   - Enables keyword/BM25 search immediately
 *
 * Phase 3: PARALLEL EMBEDDING + VECTOR WRITE
 *   - Chunks are embedded (neural network inference)
 *   - Embeddings are written directly to vector store
 *   - Enables semantic/vector search
 * </pre>
 *
 * <h2>Producer-Consumer Flow</h2>
 *
 * <pre>
 *                      ┌──→ [Indexing Workers] ──→ Lucene (keyword search)
 * [Chunking] ──→ [chunkQueue] ──┤
 *                      └──→ [Embedding Workers] ──→ Vector Store (semantic search)
 * </pre>
 */
public class ParallelIngestPipeline implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ParallelIngestPipeline.class);

    // ========== DEFAULT VALUES (used when no configuration provided) ==========
    // These are fallback values - prefer using PipelineConfig for customization.
    // NOTE: Chunk batching has been removed - chunks are processed individually.
    // NDArray batching is still handled internally by the embedding model.
    // These batch size constants are only used for Lucene indexing batches.
    private static final int DEFAULT_MIN_BATCH_SIZE = 1;
    private static final int DEFAULT_BATCH_SIZE = 1;     // Single chunk processing
    private static final int DEFAULT_MAX_BATCH_SIZE = 128; // Only for indexing batches

    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final long DEFAULT_QUEUE_POLL_TIMEOUT_MS = 50;

    // Batch wait times are no longer used for embedding (single chunk processing)
    // Kept for backwards compatibility with PipelineConfig
    private static final long DEFAULT_MAX_BATCH_WAIT_MS = 500;
    private static final long DEFAULT_MIN_BATCH_WAIT_MS = 25;

    private static final int DEFAULT_CHUNKING_THREADS = Math.min(Runtime.getRuntime().availableProcessors() / 2, 16);
    // Use 1 embedding worker - parallelism comes from OpenMP/BLAS internally, not multiple workers
    // Multiple workers cause thread contention with OpenMP
    private static final int DEFAULT_EMBEDDING_THREADS = 1;
    private static final int DEFAULT_INDEXING_THREADS = 4;
    private static final int DEFAULT_INDEXING_BATCH_ACCUMULATION = 8; // Increased from 4
    private static final int DEFAULT_EMBEDDING_TIMEOUT_SECONDS = 300; // 5 minutes

    // ========== PROGRESS REPORTING ==========
    private static final int PROGRESS_REPORT_INTERVAL = 5;  // Report every 5 items for finer granularity
    private static final long PROGRESS_REPORT_INTERVAL_MS = 50;  // 50ms for more frequent updates

    /**
     * Configuration record for pipeline settings.
     * All values are configurable - use this to tune performance for your hardware.
     *
     * <p>Worker types:</p>
     * <ul>
     *   <li><b>indexingThreads</b> - Write chunks to Lucene (keyword search)</li>
     *   <li><b>embeddingThreads</b> - Compute embeddings AND write to vector store (semantic search)</li>
     * </ul>
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
            int embeddingThreads,            // Embedding workers: compute embeddings + write to vector store
            int indexingThreads,             // Indexing workers: write to Lucene (keyword index)
            int indexingBatchAccumulationSize,
            boolean skipEmbedding,           // Skip embedding computation entirely (keyword-only mode)
            int embeddingTimeoutSeconds      // Timeout for each embedding batch (0 = no timeout)
    ) {
        /**
         * Creates a default configuration.
         * Both indexing (Lucene) and embedding (vector store) run in parallel.
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
                    false,  // skipEmbedding
                    DEFAULT_EMBEDDING_TIMEOUT_SECONDS
            );
        }

        /**
         * Creates a high-throughput configuration for systems with more memory.
         * Chunks are processed individually; NDArray batching is internal to the model.
         */
        public static PipelineConfig highThroughput() {
            int cores = Runtime.getRuntime().availableProcessors();
            return new PipelineConfig(
                    1,      // Single chunk processing
                    1,      // Single chunk processing
                    256,    // For indexing batches only
                    2000,   // Larger queue
                    50,
                    500,
                    25,
                    Math.min(cores, 16),
                    1,      // 1 embedding thread - OpenMP handles internal parallelism
                    Math.min(cores / 2, 8),  // Indexing threads for Lucene
                    16,     // Indexing batch accumulation
                    false,  // skipEmbedding
                    DEFAULT_EMBEDDING_TIMEOUT_SECONDS
            );
        }

        /**
         * Creates a low-memory configuration for constrained systems.
         * Chunks are processed individually to minimize memory usage.
         */
        public static PipelineConfig lowMemory() {
            return new PipelineConfig(
                    1,      // Single chunk processing
                    1,      // Single chunk processing
                    32,     // Lower max for indexing
                    250,    // Smaller queue
                    50,
                    500,
                    25,
                    2,      // Fewer threads
                    1,      // Single embedding thread - OpenMP handles parallelism
                    2,      // 2 indexing threads
                    4,      // Indexing batch accumulation
                    false,  // skipEmbedding
                    DEFAULT_EMBEDDING_TIMEOUT_SECONDS
            );
        }

        /**
         * Creates a keyword-only configuration for maximum ingestion speed.
         * Embedding and vector store population is skipped entirely.
         * Call indexFromLucene() later to populate the vector store.
         */
        public static PipelineConfig keywordOnly() {
            int cores = Runtime.getRuntime().availableProcessors();
            return new PipelineConfig(
                    1,      // Single chunk processing
                    1,      // Single chunk processing
                    256,    // For indexing batches only
                    5000,   // Large queue for buffering
                    50,
                    500,
                    25,
                    Math.min(cores, 16),  // Max chunking parallelism
                    0,      // No embedding threads - keyword only
                    Math.min(cores / 2, 8),  // Indexing threads for Lucene
                    32,     // Large indexing batch accumulation
                    true,   // skipEmbedding
                    0       // No timeout needed for keyword-only mode
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
            private boolean skipEmbedding = false;
            private int embeddingTimeoutSeconds = DEFAULT_EMBEDDING_TIMEOUT_SECONDS;

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
            public Builder skipEmbedding(boolean v) { this.skipEmbedding = v; return this; }
            public Builder embeddingTimeoutSeconds(int v) { this.embeddingTimeoutSeconds = v; return this; }

            public PipelineConfig build() {
                return new PipelineConfig(
                        minBatchSize, defaultBatchSize, maxBatchSize, queueCapacity,
                        queuePollTimeoutMs, maxBatchWaitMs, minBatchWaitMs,
                        chunkingThreads, embeddingThreads, indexingThreads,
                        indexingBatchAccumulationSize, skipEmbedding, embeddingTimeoutSeconds
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

    // NOTE: Chunk batching has been removed. Chunks are processed individually.
    // NDArray batching is handled internally by the embedding model.
    // The indexingBatchAccumulationSize in PipelineConfig is used for Lucene writes only.

    // Thread pools
    private final ExecutorService chunkingExecutor;
    private final ExecutorService embeddingExecutor;  // Embedding + vector store writes
    private final ExecutorService indexingExecutor;   // Lucene (keyword index) writes
    private final int chunkingParallelism;
    private final int embeddingParallelism;
    private final int indexingParallelism;

    // Vector-only mode: when true, skip keyword index updates (for vector population from existing index)
    private final boolean vectorOnlyMode;

    // Skip-embedding mode: when true, skip all embedding and index only to keyword store
    // Vector store can be populated later via indexFromLucene()
    private final boolean skipEmbedding;

    // Producer-consumer queue - chunks are consumed by both indexing and embedding workers
    private final BlockingQueue<RetrievedDoc> chunkQueue;

    // Progress tracking (thread-safe)
    private final AtomicInteger documentsProcessed = new AtomicInteger(0);
    private final AtomicInteger chunksCreated = new AtomicInteger(0);
    private final AtomicInteger chunksEmbedded = new AtomicInteger(0);  // Embedded AND written to vector store
    private final AtomicInteger chunksIndexed = new AtomicInteger(0);   // Written to Lucene (keyword index)
    private final AtomicInteger embeddingBatchesProcessed = new AtomicInteger(0);
    private final AtomicLong tokensProcessed = new AtomicLong(0);  // Total tokens processed
    private final AtomicInteger indexingBatchesProcessed = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong lastProgressReportMs = new AtomicLong(0);

    // Sliding window rate tracking for accurate ETA calculation
    // Using a circular buffer to track progress samples over time
    private static final int RATE_WINDOW_SIZE = 10;  // Number of samples to track
    private static final long RATE_SAMPLE_INTERVAL_MS = 2000;  // Sample every 2 seconds
    private final long[] rateSampleTimestamps = new long[RATE_WINDOW_SIZE];
    private final int[] rateSampleCounts = new int[RATE_WINDOW_SIZE];
    private volatile int rateSampleIndex = 0;
    private volatile long lastRateSampleTime = 0;

    // Per-worker status tracking
    private final ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses = new ConcurrentHashMap<>();

    // Current embedding batch metrics (for progress reporting)
    private volatile PipelineProgress.EmbeddingBatchMetrics currentEmbeddingBatchMetrics = null;

    // Batch history - keep last N completed batches for UI visibility
    private static final int BATCH_HISTORY_SIZE = 5;
    private final java.util.Deque<PipelineProgress.EmbeddingBatchMetrics> batchHistory =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    // Fixed total batches - set once when total chunks is known, never changes
    private final AtomicInteger fixedTotalBatches = new AtomicInteger(-1);  // -1 means not yet set
    private final AtomicInteger fixedBatchSize = new AtomicInteger(-1);     // The batch size used for calculation
    private final AtomicInteger resumedBatchOffset = new AtomicInteger(0);  // Batches already done when resuming

    // Callback for progress updates
    private Consumer<PipelineProgress> progressCallback;

    // Control flags
    private volatile boolean cancelled = false;
    private volatile boolean chunkingComplete = false;
    private volatile boolean embeddingComplete = false;
    private volatile boolean indexingComplete = false;
    private volatile boolean embeddingFailed = false;  // Set when embedding model fails to initialize
    private volatile String embeddingFailureReason = null;
    private volatile String lastError = null;

    // Checkpoint service (optional)
    private IngestCheckpointService checkpointService;
    private IngestCheckpointService.CheckpointState resumeState;

    // Cached embeddings from previous run (for resume support)
    // Key is chunk ID, value is the pre-computed embedding
    private Map<String, float[]> cachedEmbeddings = new ConcurrentHashMap<>();

    // Adaptive memory recovery for OOM handling
    private final AdaptiveMemoryRecovery memoryRecovery;

    /**
     * Set checkpoint service for persistence and resume.
     */
    public void setCheckpointService(IngestCheckpointService service) {
        this.checkpointService = service;
    }

    /**
     * Set cached embeddings from a previous run.
     * These embeddings will be used instead of re-computing when processing chunks with matching IDs.
     */
    public void setCachedEmbeddings(Map<String, float[]> embeddings) {
        if (embeddings != null) {
            this.cachedEmbeddings.putAll(embeddings);
            logger.info("Loaded {} cached embeddings for resume", embeddings.size());
        }
    }

    /**
     * Get a cached embedding by chunk ID, or null if not cached.
     */
    public float[] getCachedEmbedding(String chunkId) {
        return cachedEmbeddings.get(chunkId);
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
        this(chunker, embeddingModel, indexerService, chunkingOptions,
             PipelineConfig.builder()
                     .defaultBatchSize(batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE)
                     .build(),
             false);  // vectorOnlyMode = false
    }

    /**
     * Creates a new parallel ingest pipeline with configurable parallel indexing.
     *
     * @param chunker                 The text chunker to use (null for no chunking)
     * @param embeddingModel          The embedding model for generating vectors
     * @param indexerService          The indexer service for storing documents
     * @param chunkingOptions         Options for chunking behavior
     * @param batchSize               Number of chunks per batch (0 to use model default)
     * @param parallelIndexingEnabled Deprecated: ignored. Indexing and embedding always run in parallel.
     * @deprecated Use constructor without parallelIndexingEnabled. Indexing and embedding always run in parallel.
     */
    @Deprecated
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize,
            boolean parallelIndexingEnabled) {
        // parallelIndexingEnabled is ignored - new architecture always runs in parallel
        this(chunker, embeddingModel, indexerService, chunkingOptions, batchSize);
    }

    /**
     * Creates a new parallel ingest pipeline with configurable parallel indexing and vector-only mode.
     *
     * @param chunker                 The text chunker to use (null for no chunking)
     * @param embeddingModel          The embedding model for generating vectors
     * @param indexerService          The indexer service for storing documents
     * @param chunkingOptions         Options for chunking behavior
     * @param batchSize               Number of chunks per batch (0 to use model default)
     * @param parallelIndexingEnabled Deprecated: ignored. Indexing and embedding always run in parallel.
     * @param vectorOnlyMode          When true, only index to vector store (skip keyword index)
     * @deprecated Use constructor with PipelineConfig. The parallelIndexingEnabled parameter is ignored.
     */
    @Deprecated
    public ParallelIngestPipeline(
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            Map<String, Object> chunkingOptions,
            int batchSize,
            boolean parallelIndexingEnabled,
            boolean vectorOnlyMode) {
        // parallelIndexingEnabled is ignored - new architecture always runs in parallel
        this(chunker, embeddingModel, indexerService, chunkingOptions,
             PipelineConfig.builder()
                     .defaultBatchSize(batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE)
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
        this.skipEmbedding = this.pipelineConfig.skipEmbedding();
        this.config = new AdaptivePipelineConfig();
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.indexerService = indexerService;
        this.chunkingOptions = chunkingOptions != null ? chunkingOptions : new HashMap<>();

        // Log embedding model status for debugging
        if (embeddingModel != null) {
            logger.info("ParallelIngestPipeline: embedding model provided - {}",
                    embeddingModel.getClass().getSimpleName());
        } else {
            logger.warn("ParallelIngestPipeline: NO embedding model - keyword-only mode");
        }

        // Log vector-only mode status
        if (vectorOnlyMode) {
            logger.info("ParallelIngestPipeline: VECTOR-ONLY MODE enabled - Lucene indexing will be skipped");
        }

        // Log skip-embedding mode status
        if (this.skipEmbedding) {
            logger.info("ParallelIngestPipeline: SKIP-EMBEDDING MODE enabled - embedding will be skipped");
            logger.info("ParallelIngestPipeline: Documents will be indexed to Lucene only");
            logger.info("ParallelIngestPipeline: Call indexFromLucene() later to populate vector store");
        }

        // NOTE: Chunk batching has been removed. Chunks are processed individually.
        // NDArray batching is handled internally by the embedding model.

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

        // Embedding workers: compute embeddings AND write to vector store
        this.embeddingExecutor = Executors.newFixedThreadPool(Math.max(1, embeddingParallelism), r -> {
            Thread t = new Thread(r, "embedding-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // Indexing workers: write to Lucene (keyword index)
        this.indexingExecutor = Executors.newFixedThreadPool(Math.max(1, indexingParallelism), r -> {
            Thread t = new Thread(r, "indexing-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        // Single queue for chunks - consumed by both indexing and embedding workers
        this.chunkQueue = new LinkedBlockingQueue<>(this.pipelineConfig.queueCapacity());

        // Initialize adaptive memory recovery for OOM handling (uses single chunk processing)
        this.memoryRecovery = new AdaptiveMemoryRecovery(1);

        logger.info("=== ParallelIngestPipeline INITIALIZED ===");
        logger.info("  Architecture: Chunks → [Indexing → Lucene] AND [Embedding → Vector Store]");
        logger.info("  Threading: chunking={}, indexing={} (Lucene), embedding={} (vector store)",
                chunkingParallelism, indexingParallelism, this.skipEmbedding ? 0 : embeddingParallelism);
        logger.info("  Chunk Processing: individual (no batching), queueCapacity={}", this.pipelineConfig.queueCapacity());
        logger.info("  NDArray batching: handled internally by embedding model");
        if (!this.skipEmbedding) {
            logger.info("  Embedding workers will compute embeddings AND write to vector store");
        }
        logger.info("  Indexing workers write directly to Lucene (keyword index)");
        logger.info("  SKIP EMBEDDING: {}", this.skipEmbedding ? "ENABLED (keyword-only)" : "DISABLED");
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

    /**
     * Returns whether embedding is being skipped (keyword-only mode).
     */
    public boolean isSkipEmbedding() {
        return skipEmbedding;
    }

    public void setProgressCallback(Consumer<PipelineProgress> callback) {
        this.progressCallback = callback;

        // Hook up memory recovery notifications to the progress callback
        if (memoryRecovery != null && callback != null) {
            memoryRecovery.setUserNotificationCallback(message -> {
                // Report memory adaptation events through the progress callback
                PipelineProgress memoryEvent = PipelineProgress.builder()
                        .phase("memory_adaptation")
                        .percent(0)
                        .message(message)
                        .build();
                callback.accept(memoryEvent);
            });
        }
    }

    /**
     * Processes documents through the parallel pipeline.
     *
     * @param documents The documents to process
     * @return Pipeline result with statistics
     */
    public PipelineResult process(List<Document> documents) throws InterruptedException {
        if (documents == null || documents.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, 0, List.of());
        }

        // If skip-embedding is enabled, use the optimized keyword-only path
        if (skipEmbedding) {
            return processKeywordOnly(documents);
        }

        startTimeMs.set(System.currentTimeMillis());
        int totalDocuments = documents.size();

        // === CHECKPOINT: Initialize checkpoint for document processing ===
        ai.kompile.app.subprocess.IngestCheckpoint checkpoint =
                ai.kompile.app.subprocess.IngestSubprocessMain.getCurrentCheckpoint();
        if (checkpoint != null) {
            checkpoint.setCurrentPhase("CHUNKING");
            // Note: For document processing, we can't easily skip chunks since chunking
            // happens on-the-fly. The checkpoint will track embedded chunks for resume
            // on the next attempt if OOM occurs.
        }

        int modelBatchSize = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : pipelineConfig.defaultBatchSize();
        logger.info("Starting parallel pipeline: {} documents, chunking={} threads, indexing={} threads, embedding={} threads, modelBatch={}",
                totalDocuments, chunkingParallelism, indexingParallelism, embeddingParallelism, modelBatchSize);

        reportProgress("starting", 1,
                String.format("Starting: %d documents, %d chunking + %d indexing + %d embedding workers",
                        totalDocuments, chunkingParallelism, indexingParallelism, embeddingParallelism));

        // Start indexing workers (consume from chunkQueue, write to Lucene)
        List<Future<?>> indexingFutures = startIndexingWorkers();
        if (!indexingFutures.isEmpty()) {
            logger.info("Started {} indexing workers (write to Lucene)", indexingFutures.size());
        }

        // Start embedding workers (consume from chunkQueue, compute embeddings, write to vector store)
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();
        if (!embeddingFutures.isEmpty()) {
            logger.info("Started {} embedding workers (compute embeddings + write to vector store)", embeddingFutures.size());
        }

        // Initialize queues with resume state if available
        if (resumeState != null) {
            // Restore orphaned chunks (created but not fully processed)
            for (RetrievedDoc chunk : resumeState.orphanedChunks()) {
                if (!chunkQueue.offer(chunk)) {
                    logger.warn("ParallelIngestPipeline: Failed to requeue orphaned chunk {}", chunk.getId());
                }
            }
            // Note: orphaned embeddings are no longer relevant since embedding workers
            // now write directly to vector store. Orphaned chunks will be re-embedded.
        }

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
                    int embedded = chunksEmbedded.get();  // Embedded AND written to vector store
                    int indexed = chunksIndexed.get();    // Written to Lucene
                    int queueSize = chunkQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (Math.max(indexed, embedded) * 1000.0 / elapsed) : 0;

                    // Determine phase based on actual work being done
                    String phase;
                    if (embeddingComplete && indexingComplete) {
                        phase = "complete";
                    } else if (indexed > 0 || embedded > 0) {
                        // Both indexing and embedding are happening in parallel
                        phase = "indexing+embedding";
                    } else if (created > 0) {
                        // Chunks created but not yet processed
                        phase = "processing";
                    } else {
                        phase = "chunking";
                    }

                    // Log worker status details
                    int activeWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "processing".equals(w.status())).count();
                    int waitingWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "waiting".equals(w.status())).count();

                    logger.debug("Periodic progress: phase={}, created={}, indexed={}, embedded={}, " +
                            "chunkQ={}, workers={} active/{} waiting, rate={}",
                            phase, created, indexed, embedded, queueSize, activeWorkers, waitingWorkers, rate);

                    // Simplified progress message
                    String progressMsg = String.format("%d created, %d indexed (Lucene), %d embedded (vector) (%.1f/sec) [Q:%d]",
                            created, indexed, embedded, rate, queueSize);
                    reportProgressWithWorkers(phase, 0, progressMsg);
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter: {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Phase 1: Parallel chunking (producer of chunk queue)
            // Indexing and embedding workers consume chunks as they're produced
            parallelChunkDocuments(documents);
            chunkingComplete = true;

            logger.info("Chunking complete: {} documents -> {} chunks",
                    documentsProcessed.get(), chunksCreated.get());

            // Wait for indexing to complete (Lucene writes)
            if (!indexingFutures.isEmpty()) {
                for (Future<?> future : indexingFutures) {
                    try {
                        future.get(30, TimeUnit.MINUTES);
                    } catch (TimeoutException e) {
                        logger.error("Lucene indexing timed out");
                        cancelled = true;
                    } catch (ExecutionException e) {
                        logger.error("Lucene indexing failed: {}", e.getCause().getMessage(), e.getCause());
                    }
                }
                indexingComplete = true;
                logger.info("Indexing complete: {} chunks indexed to Lucene", chunksIndexed.get());
            } else {
                indexingComplete = true;
            }

            // Wait for embedding to complete (embedding computation + vector store writes)
            String embeddingError = null;
            for (Future<?> future : embeddingFutures) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                    embeddingError = "Embedding timed out after 30 minutes";
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                    cancelled = true;  // Mark as failed so job history shows failure
                    embeddingError = e.getCause().getMessage();
                }
            }
            embeddingComplete = true;

            // If embedding failed, throw to ensure job is marked as failed
            if (embeddingError != null) {
                throw new RuntimeException("Embedding/indexing failed: " + embeddingError);
            }

            logger.info("Embedding complete: {} chunks embedded and written to vector store in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            // Collect processed IDs from both worker types
            List<String> processedIds = new ArrayList<>();
            // Note: With the new architecture, IDs are tracked during processing
            // For now, we return an empty list as tracking is done via counters

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (Math.max(chunksIndexed.get(), chunksEmbedded.get()) * 1000.0 / totalTimeMs) : 0;

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
                    tokensProcessed.get(),
                    totalTimeMs,
                    processedIds);
        } finally {
            // Stop periodic progress reporter
            progressReporter.shutdownNow();
        }
    }

    /**
     * Processes documents in keyword-only mode (skip embedding and vector store).
     * This is optimized for maximum ingestion throughput when vector store
     * population will be done later via indexFromLucene().
     * <p>
     * This method:
     * - Chunks documents in parallel
     * - Indexes directly to keyword store (no embedding computation)
     * - Skips vector store indexing entirely
     * - Is significantly faster than full processing
     * </p>
     *
     * @param documents The documents to process
     * @return Pipeline result with statistics
     */
    public PipelineResult processKeywordOnly(List<Document> documents) throws InterruptedException {
        if (documents == null || documents.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, 0, List.of());
        }

        startTimeMs.set(System.currentTimeMillis());
        int totalDocuments = documents.size();

        logger.info("Starting KEYWORD-ONLY pipeline: {} documents, chunking={} threads, indexing={} threads",
                totalDocuments, chunkingParallelism, indexingParallelism);
        logger.info("  SKIP EMBEDDING: Vector store will NOT be populated (use indexFromLucene() later)");

        reportProgress("starting", 1,
                String.format("Starting keyword-only: %d documents, %d chunking workers",
                        totalDocuments, chunkingParallelism));

        // Queue for direct indexing (bypass embedding)
        BlockingQueue<List<RetrievedDoc>> indexingQueue = new LinkedBlockingQueue<>(pipelineConfig.queueCapacity());

        // Start direct indexing worker (consumes chunks directly, no embedding)
        Future<List<String>> indexingFuture = indexingExecutor.submit(() -> {
            List<String> processedIds = new ArrayList<>();
            List<RetrievedDoc> accumulatedBatch = new ArrayList<>();
            int batchCount = 0;
            int targetBatchSize = pipelineConfig.indexingBatchAccumulationSize() * pipelineConfig.defaultBatchSize();

            while (!cancelled && !Thread.currentThread().isInterrupted()) {
                // Check subprocess memory watchdog
                if (checkSubprocessMemoryLimit()) {
                    logger.warn("Keyword indexing worker: subprocess memory limit reached, flushing and exiting");
                    if (!accumulatedBatch.isEmpty()) {
                        try {
                            indexerService.indexToKeywordIndexOnly(accumulatedBatch, true);
                            for (RetrievedDoc doc : accumulatedBatch) {
                                processedIds.add(doc.getId());
                                chunksIndexed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            logger.error("Error flushing batch on memory limit: {}", e.getMessage(), e);
                        }
                    }
                    break;
                }

                try {
                    // Poll for batches
                    List<RetrievedDoc> batch = indexingQueue.poll(pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);

                    if (batch != null) {
                        accumulatedBatch.addAll(batch);

                        // Index when we have enough or when chunking is complete
                        if (accumulatedBatch.size() >= targetBatchSize ||
                            (chunkingComplete && indexingQueue.isEmpty())) {

                            if (!accumulatedBatch.isEmpty()) {
                                try {
                                    // Index directly to keyword store only
                                    indexerService.indexToKeywordIndexOnly(accumulatedBatch, true);

                                    for (RetrievedDoc doc : accumulatedBatch) {
                                        processedIds.add(doc.getId());
                                        chunksIndexed.incrementAndGet();
                                    }
                                    batchCount++;
                                    indexingBatchesProcessed.incrementAndGet();
                                } catch (Exception e) {
                                    logger.error("Error indexing batch: {}", e.getMessage(), e);
                                    lastError = "Indexing error: " + e.getMessage();
                                }
                                accumulatedBatch.clear();
                            }
                        }
                    } else if (chunkingComplete && indexingQueue.isEmpty()) {
                        // Flush any remaining documents
                        if (!accumulatedBatch.isEmpty()) {
                            try {
                                indexerService.indexToKeywordIndexOnly(accumulatedBatch, true);
                                for (RetrievedDoc doc : accumulatedBatch) {
                                    processedIds.add(doc.getId());
                                    chunksIndexed.incrementAndGet();
                                }
                                batchCount++;
                            } catch (Exception e) {
                                logger.error("Error in final batch indexing: {}", e.getMessage(), e);
                            }
                        }
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            logger.info("Keyword-only indexing complete: {} chunks in {} batches", processedIds.size(), batchCount);
            return processedIds;
        });

        // Start periodic progress reporter
        ScheduledExecutorService progressReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-progress");
            t.setDaemon(true);
            return t;
        });
        progressReporter.scheduleAtFixedRate(() -> {
            try {
                if (!cancelled) {
                    int created = chunksCreated.get();
                    int indexed = chunksIndexed.get();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (indexed * 1000.0 / elapsed) : 0;

                    String phase = indexed > 0 ? "indexing" : "chunking";

                    reportProgressWithWorkers(phase, Math.min(99, (indexed * 100) / Math.max(1, created)),
                            String.format("KEYWORD-ONLY: %d created, %d indexed (%.1f/sec)",
                                    created, indexed, rate));
                }
            } catch (Exception e) {
                logger.error("Error in progress reporter: {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Phase 1: Parallel chunking (writes directly to indexing queue)
            parallelChunkDocumentsForKeywordOnly(documents, indexingQueue);
            chunkingComplete = true;

            logger.info("Chunking complete: {} documents -> {} chunks",
                    documentsProcessed.get(), chunksCreated.get());

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
                    String.format("=== KEYWORD-ONLY COMPLETE === %d docs -> %d chunks indexed in %dms (%.1f/sec)",
                            documentsProcessed.get(), chunksIndexed.get(), totalTimeMs, chunksPerSec));

            logger.info("========== KEYWORD-ONLY PIPELINE COMPLETE: {} documents -> {} chunks indexed in {}ms ({} chunks/sec) ==========",
                    documentsProcessed.get(), chunksIndexed.get(), totalTimeMs, String.format("%.1f", chunksPerSec));
            logger.info("  To populate vector store later, call: indexerService.indexFromLucene()");

            return new PipelineResult(
                    documentsProcessed.get(),
                    chunksCreated.get(),
                    chunksIndexed.get(),
                    tokensProcessed.get(),
                    totalTimeMs,
                    processedIds);
        } finally {
            progressReporter.shutdownNow();
        }
    }

    /**
     * Parallel chunking for keyword-only mode.
     * Writes chunks directly to the indexing queue (bypasses embedding queue).
     */
    private void parallelChunkDocumentsForKeywordOnly(List<Document> documents,
            BlockingQueue<List<RetrievedDoc>> indexingQueue) throws InterruptedException {
        int totalDocuments = documents.size();
        List<CompletableFuture<Void>> futures = new ArrayList<>(totalDocuments);

        for (int i = 0; i < documents.size(); i++) {
            if (cancelled) break;

            final Document doc = documents.get(i);
            final int docIndex = i;

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                if (cancelled || Thread.currentThread().isInterrupted()) return;

                try {
                    List<RetrievedDoc> chunks = chunkSingleDocument(doc, docIndex, totalDocuments);

                    if (!chunks.isEmpty()) {
                        // Queue chunks for indexing (batch them)
                        boolean queued = false;
                        int retries = 0;
                        while (!queued && !cancelled && !Thread.currentThread().isInterrupted() && retries < 30) {
                            queued = indexingQueue.offer(chunks, 1000, TimeUnit.MILLISECONDS);
                            if (!queued) retries++;
                        }

                        if (queued) {
                            chunksCreated.addAndGet(chunks.size());
                        } else {
                            logger.error("Failed to queue chunks for doc {}", docIndex);
                        }
                    }
                    documentsProcessed.incrementAndGet();
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
        } catch (ExecutionException e) {
            logger.error("Chunking failed: {}", e.getMessage(), e);
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
            return new PipelineResult(0, 0, 0, 0, 0, List.of());
        }

        startTimeMs.set(System.currentTimeMillis());
        int originalTotalChunks = chunks.size();

        // === CHECKPOINT RESUME: Skip already-processed chunks ===
        ai.kompile.app.subprocess.IngestCheckpoint checkpoint =
                ai.kompile.app.subprocess.IngestSubprocessMain.getCurrentCheckpoint();
        ai.kompile.app.subprocess.SubprocessArgs args =
                ai.kompile.app.subprocess.IngestSubprocessMain.getCurrentArgs();

        if (checkpoint != null && args != null && args.resume() && checkpoint.getEmbeddedCount() > 0) {
            int alreadyProcessed = checkpoint.getEmbeddedCount();
            if (alreadyProcessed < originalTotalChunks) {
                // Skip already-processed chunks
                List<RetrievedDoc> remainingChunks = chunks.subList(alreadyProcessed, originalTotalChunks);
                logger.info("========================================");
                logger.info("RESUMING FROM CHECKPOINT");
                logger.info("  Already processed: {} chunks", alreadyProcessed);
                logger.info("  Remaining: {} chunks", remainingChunks.size());
                logger.info("  Skipping first {} chunks", alreadyProcessed);
                logger.info("========================================");

                // Update chunks to process only remaining
                chunks = new ArrayList<>(remainingChunks);

                // Pre-populate counters with already-processed amounts
                chunksEmbedded.set(alreadyProcessed);
                chunksIndexed.set(alreadyProcessed);
                chunksCreated.set(alreadyProcessed); // Will be updated when we queue new chunks

                // Update checkpoint with total chunks
                checkpoint.setTotalChunks(originalTotalChunks);
            } else {
                // All chunks already processed
                logger.info("All {} chunks already processed according to checkpoint - nothing to do",
                        originalTotalChunks);
                checkpoint.markCompleted(0);
                return new PipelineResult(0, originalTotalChunks, originalTotalChunks, 0, originalTotalChunks, List.of());
            }
        } else if (checkpoint != null) {
            // First run - record total chunks
            checkpoint.setTotalChunks(originalTotalChunks);
            checkpoint.setCurrentPhase("EMBEDDING");
        }

        int totalChunks = chunks.size();

        int modelBatchSize = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : pipelineConfig.defaultBatchSize();
        logger.info("Starting PRE-CHUNKED pipeline: {} chunks (of {} total), embedding={} threads, modelBatch={}",
                totalChunks, originalTotalChunks, embeddingParallelism, modelBatchSize);

        reportProgress("starting", 1,
                String.format("Starting vector population: %d chunks, %d embedding workers",
                        totalChunks, embeddingParallelism));

        // CRITICAL FIX: Queue documents BEFORE starting embedding workers
        // Previously, workers started immediately and had only a few items available when their
        // 500ms batch timeout expired, resulting in tiny batches (e.g., 3 items instead of 32).
        // By pre-loading the queue, workers will have full batches available immediately.
        logger.info("Pre-loading {} documents to queue before starting embedding workers...", totalChunks);
        try {
            queuePreChunkedDocuments(chunks, totalChunks);
            chunkingComplete = true;
            logger.info("Pre-loaded {} documents to queue (size={})", chunksCreated.get(), chunkQueue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        // Now start embedding workers - queue is already populated with all documents
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();

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
                    int embedded = chunksEmbedded.get();  // Embedded AND written to vector store
                    int indexed = chunksIndexed.get();    // Written to Lucene
                    int queueSize = chunkQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    // In vector-only mode, rate is based on embedded count only (no Lucene indexing)
                    int rateBase = vectorOnlyMode ? embedded : Math.max(indexed, embedded);
                    double rate = elapsed > 0 ? (rateBase * 1000.0 / elapsed) : 0;

                    // Phase based on work being done - in vector-only mode, only embedding happens
                    String phase = vectorOnlyMode ? "embedding" :
                            ((indexed > 0 || embedded > 0) ? "indexing+embedding" : "processing");

                    int activeWorkers = (int) workerStatuses.values().stream()
                            .filter(w -> "processing".equals(w.status())).count();

                    // Calculate ETA using sliding window rate (more accurate than overall average)
                    // In vector-only mode, use embedded count; otherwise use max of indexed/embedded
                    int maxProgress = vectorOnlyMode ? embedded : Math.max(indexed, embedded);
                    long remaining = totalChunks - maxProgress;
                    String etaStr = calculateEtaString(remaining, maxProgress, rate);

                    logger.debug("Periodic progress: phase={}, queued={}, embedded={}, indexed={}/{}, " +
                            "chunkQ={}, active={}, rate={}, vectorOnlyMode={}",
                            phase, created, embedded, indexed, totalChunks, queueSize,
                            activeWorkers, rate, vectorOnlyMode);

                    // Progress message varies based on mode
                    String progressMsg = vectorOnlyMode
                            ? String.format("%d/%d embedded (vector only) (%.1f/sec)%s [Q:%d]",
                                    embedded, totalChunks, rate, etaStr, queueSize)
                            : String.format("%d/%d indexed (Lucene), %d embedded (vector) (%.1f/sec)%s [Q:%d]",
                                    indexed, totalChunks, embedded, rate, etaStr, queueSize);
                    reportProgressWithWorkers(phase, Math.min(99, (int) ((maxProgress * 100) / totalChunks)), progressMsg);
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter: {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            // Documents already queued before starting workers (see above)
            // Wait for embedding to complete
            String embeddingError = null;
            for (Future<?> future : embeddingFutures) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                    embeddingError = "Embedding timed out after 30 minutes";
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                    cancelled = true;  // Mark as failed so job history shows failure
                    embeddingError = e.getCause().getMessage();
                }
            }
            embeddingComplete = true;

            // If embedding failed, throw to ensure job is marked as failed
            if (embeddingError != null) {
                throw new RuntimeException("Embedding/indexing failed: " + embeddingError);
            }

            logger.info("Embedding complete: {} chunks embedded and written to vector store in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            // In pre-chunked/vector-only mode, embedding workers write directly to vector store
            // No separate indexing step needed
            List<String> processedIds = new ArrayList<>();

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (chunksEmbedded.get() * 1000.0 / totalTimeMs) : 0;

            reportProgress("COMPLETED", 100,
                    String.format("=== PIPELINE COMPLETE (pre-chunked) === %d chunks embedded to vector store in %dms (%.1f chunks/sec)",
                            chunksEmbedded.get(), totalTimeMs, chunksPerSec));

            logger.info("========== PIPELINE COMPLETE (pre-chunked): {} chunks embedded to vector store in {}ms ({} chunks/sec) ==========",
                    chunksEmbedded.get(), totalTimeMs, String.format("%.1f", chunksPerSec));

            return new PipelineResult(
                    totalChunks, // documents = total chunks for pre-chunked
                    chunksCreated.get(),
                    chunksEmbedded.get(),  // Use embedded count for vector-only mode
                    tokensProcessed.get(),
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
            while (!queued && !cancelled && !embeddingFailed && !Thread.currentThread().isInterrupted()) {
                // Try to offer with a timeout, so we can check for cancellation/failure
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

            // Check if embedding failed - fail fast
            if (embeddingFailed) {
                String reason = embeddingFailureReason != null ? embeddingFailureReason : "Unknown embedding failure";
                logger.error("Aborting pipeline - embedding failed: {}", reason);
                throw new RuntimeException("Embedding failed: " + reason);
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
                            while (!queued && !cancelled && !embeddingFailed && !Thread.currentThread().isInterrupted()
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
                            // Check if embedding failed - fail fast
                            if (embeddingFailed) {
                                String reason = embeddingFailureReason != null ? embeddingFailureReason : "Unknown embedding failure";
                                throw new RuntimeException("Embedding failed: " + reason);
                            }
                            if (!queued) {
                                logger.error(
                                        "Chunking doc {}: failed to queue chunk after {} retries - workers may have stalled",
                                        docIndex, maxRetries);
                                throw new RuntimeException("Chunk queue full - pipeline stalled");
                            }

                            // Chunks are consumed by both indexing workers (Lucene) and embedding workers (vector store)
                            // from the same chunkQueue - no separate queue needed
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
     * Start embedding worker threads that consume from chunkQueue, compute embeddings,
     * and write directly to the vector store.
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
                int modelBatchSize = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : 32;
                logger.info("Embedding worker {} STARTED on thread {} with model batch size={}",
                        workerId, Thread.currentThread().getName(), modelBatchSize);

                // Report embedding phase started immediately (before warmup)
                // This ensures frontend knows we've entered embedding phase
                if (workerId == 0) { // Only first worker reports to avoid spam
                    int queueSize = chunkQueue.size();
                    reportProgressWithWorkers("embedding", 0,
                            String.format("Starting embedding workers (%d chunks queued)", queueSize));
                }

                // Check embedding model is ready - dimensions() blocks until initialized
                // With proper synchronization in AnseriniEmbeddingModelImpl, this should
                // return immediately if already initialized, or block until init completes
                if (embeddingModel != null) {
                    logger.debug("Embedding worker {}: checking model readiness...", workerId);
                    try {
                        // This call blocks until model is initialized (lazy init with synchronization)
                        int dims = embeddingModel.dimensions();
                        if (dims > 0) {
                            logger.info("Embedding worker {}: Model ready (dimensions={})", workerId, dims);
                        } else {
                            // Model returned invalid dimensions - likely failed to initialize
                            String reason = "Model returned invalid dimensions (" + dims + ")";
                            logger.error("Embedding worker {}: {}. Model may have failed to initialize. Check model configuration.",
                                    workerId, reason);
                            embeddingFailed = true;
                            embeddingFailureReason = reason;
                            workerStatuses.put(workerKey,
                                    new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                            0, 0, 0, "model initialization failed"));
                            return; // Exit this worker - can't proceed without valid model
                        }
                    } catch (Exception e) {
                        String reason = "Failed to get model dimensions: " + e.getMessage();
                        logger.error("Embedding worker {}: {}", workerId, reason);
                        embeddingFailed = true;
                        embeddingFailureReason = reason;
                        workerStatuses.put(workerKey,
                                new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                        0, 0, 0, "model error: " + e.getMessage()));
                        return; // Exit this worker
                    }
                } else {
                    String reason = "Embedding model is NULL";
                    logger.error("Embedding worker {}: {}!", workerId, reason);
                    embeddingFailed = true;
                    embeddingFailureReason = reason;
                    workerStatuses.put(workerKey,
                            new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                    0, 0, 0, "no embedding model"));
                    return; // Exit this worker
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
                    // Check subprocess memory watchdog first (highest priority)
                    if (checkSubprocessMemoryLimit()) {
                        logger.warn("Embedding worker {}: subprocess memory limit reached, exiting", workerId);
                        break;
                    }

                    // Periodic memory pressure check (in-process fallback)
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
                    // Update status to waiting with cumulative throughput
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double cumulativeThroughput = elapsed > 0 ? (workerProcessed.get() * 1000.0 / elapsed) : 0;
                    workerStatuses.put(workerKey,
                            PipelineProgress.WorkerStatus.waitingWithThroughput(workerId, "embedding", workerProcessed.get(), cumulativeThroughput));

                    // Eagerly fetch chunks up to model's batch size - no waiting, grab what's available
                    // modelBatchSize is declared once at start of worker (before loop)
                    List<RetrievedDoc> batch = new ArrayList<>(modelBatchSize);

                    // First poll with timeout (blocking wait if queue is empty)
                    RetrievedDoc first;
                    try {
                        first = chunkQueue.poll(this.pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.info("Embedding worker {}: interrupted during poll, exiting", workerId);
                        break;
                    }
                    if (first != null) {
                        batch.add(first);
                        // Eagerly drain more items without waiting (non-blocking)
                        chunkQueue.drainTo(batch, modelBatchSize - 1);
                    }

                    // Check for interrupt after poll
                    if (Thread.currentThread().isInterrupted()) {
                        logger.info("Embedding worker {}: interrupted, exiting", workerId);
                        break;
                    }

                    if (!batch.isEmpty()) {
                        // Reset watchdog on activity
                        lastActivityTime = System.currentTimeMillis();
                        consecutiveEmptyPolls = 0;

                        logger.debug("Embedding worker {}: processing {} chunks, queue={}, cancelled={}",
                                workerId, batch.size(), chunkQueue.size(), cancelled);

                        // Update worker status
                        workerStatuses.put(workerKey,
                                PipelineProgress.WorkerStatus.processing(workerId, "embedding",
                                        workerProcessed.get(), batch.size(), 0,
                                        "processing " + batch.size() + " chunks"));

                        // Process batch through embedding model (NDArray batching happens inside)
                        processBatchEmbeddingOptimized(batch, workerId, workerProcessed);

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

                logger.info("Embedding worker {} finished: {} chunks processed at {} chunks/sec",
                        workerId, workerProcessed.get(), String.format("%.1f", throughput));
            }));
        }

        return futures;
    }

    /**
     * Starts indexing workers that write chunks directly to Lucene (keyword index).
     * These workers run in parallel with embedding workers, both consuming from chunkQueue.
     * This enables immediate keyword searchability while embedding is still processing.
     *
     * @return List of futures for the indexing workers
     */
    private List<Future<?>> startIndexingWorkers() {
        // In vector-only mode, skip Lucene indexing entirely
        if (vectorOnlyMode) {
            logger.info("Vector-only mode: skipping Lucene indexing workers");
            return new ArrayList<>();
        }

        List<Future<?>> futures = new ArrayList<>(indexingParallelism);

        // Initialize worker statuses
        for (int i = 0; i < indexingParallelism; i++) {
            workerStatuses.put("index-" + i, PipelineProgress.WorkerStatus.idle(i, "indexing"));
        }

        for (int i = 0; i < indexingParallelism; i++) {
            final int workerId = i;
            final String workerKey = "index-" + workerId;
            final AtomicInteger workerProcessed = new AtomicInteger(0);

            futures.add(indexingExecutor.submit(() -> {
                logger.info("Indexing worker {} STARTED on thread {} (writes to Lucene)",
                        workerId, Thread.currentThread().getName());

                List<RetrievedDoc> batchBuffer = new ArrayList<>();
                int batchNum = 0;
                long lastActivityTime = System.currentTimeMillis();
                int consecutiveEmptyPolls = 0;
                final int MAX_EMPTY_POLLS_BEFORE_EXIT = 20;
                final int INDEX_BATCH_SIZE = Math.max(32, this.pipelineConfig.indexingBatchAccumulationSize());

                while (!cancelled && !Thread.currentThread().isInterrupted()) {
                    // Check subprocess memory watchdog
                    if (checkSubprocessMemoryLimit()) {
                        logger.warn("Indexing worker {}: subprocess memory limit reached, exiting", workerId);
                        // Process any remaining items before exiting
                        if (!batchBuffer.isEmpty()) {
                            processIndexBatch(batchBuffer, workerId, workerProcessed, ++batchNum);
                            batchBuffer.clear();
                        }
                        break;
                    }

                    try {
                        // Poll for next chunk with timeout
                        RetrievedDoc chunk = chunkQueue.poll(
                                this.pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);

                        if (chunk != null) {
                            consecutiveEmptyPolls = 0;
                            lastActivityTime = System.currentTimeMillis();
                            batchBuffer.add(chunk);

                            // Update worker status
                            workerStatuses.put(workerKey,
                                    PipelineProgress.WorkerStatus.processing(workerId, "indexing",
                                            workerProcessed.get(), batchBuffer.size(), 0, "buffering"));

                            // Process batch when full or queue is empty and we have items
                            if (batchBuffer.size() >= INDEX_BATCH_SIZE ||
                                    (chunkQueue.isEmpty() && !batchBuffer.isEmpty())) {
                                processIndexBatch(batchBuffer, workerId, workerProcessed, ++batchNum);
                                batchBuffer.clear();
                            }
                        } else {
                            consecutiveEmptyPolls++;

                            // Process any remaining items in buffer
                            if (!batchBuffer.isEmpty()) {
                                processIndexBatch(batchBuffer, workerId, workerProcessed, ++batchNum);
                                batchBuffer.clear();
                            }

                            // Update status to waiting
                            workerStatuses.put(workerKey,
                                    PipelineProgress.WorkerStatus.waiting(workerId, "indexing",
                                            workerProcessed.get()));

                            // Exit condition: chunking complete AND queue empty for multiple polls
                            boolean standardExit = chunkingComplete && chunkQueue.isEmpty();
                            boolean watchdogExit = chunkingComplete && consecutiveEmptyPolls >= MAX_EMPTY_POLLS_BEFORE_EXIT;

                            if (standardExit || watchdogExit) {
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Process any remaining buffered chunks
                if (!batchBuffer.isEmpty()) {
                    processIndexBatch(batchBuffer, workerId, workerProcessed, ++batchNum);
                }

                // Mark worker as complete
                long elapsed = System.currentTimeMillis() - startTimeMs.get();
                double throughput = elapsed > 0 ? (workerProcessed.get() * 1000.0 / elapsed) : 0;
                workerStatuses.put(workerKey,
                        PipelineProgress.WorkerStatus.complete(workerId, "indexing", workerProcessed.get(),
                                throughput));

                logger.info("Indexing worker {} finished: {} chunks in {} batches (Lucene)",
                        workerId, workerProcessed.get(), batchNum);
            }));
        }

        return futures;
    }

    /**
     * Process a batch of chunks for Lucene (keyword) indexing only.
     * Vector store indexing is handled separately by embedding workers.
     */
    private void processIndexBatch(List<RetrievedDoc> batch, int workerId,
            AtomicInteger workerProcessed, int batchNum) {
        if (batch == null || batch.isEmpty() || cancelled) {
            return;
        }

        String workerKey = "index-" + workerId;
        int batchSize = batch.size();

        try {
            long batchStartTime = System.currentTimeMillis();

            // Update worker status
            workerStatuses.put(workerKey,
                    PipelineProgress.WorkerStatus.processing(workerId, "indexing",
                            workerProcessed.get(), batchSize, 0, "indexing batch " + batchNum));

            // Index to Lucene (keyword store) only
            indexerService.indexToKeywordIndexOnly(batch, true);

            // Update counters
            int indexed = chunksIndexed.addAndGet(batchSize);
            workerProcessed.addAndGet(batchSize);

            long elapsed = System.currentTimeMillis() - batchStartTime;
            double batchThroughput = elapsed > 0 ? (batchSize * 1000.0 / elapsed) : 0;

            // Update worker status with throughput
            workerStatuses.put(workerKey,
                    PipelineProgress.WorkerStatus.processing(workerId, "indexing",
                            workerProcessed.get(), batchSize, batchThroughput, "batch " + batchNum + " done"));

            logger.debug("Indexing worker {}: batch {} ({} chunks) in {}ms ({}/sec) [total: {}]",
                    workerId, batchNum, batchSize, elapsed, String.format("%.1f", batchThroughput), indexed);

        } catch (Exception e) {
            logger.error("Indexing worker {}: batch {} failed: {}", workerId, batchNum, e.getMessage(), e);
            lastError = "Lucene indexing failed: " + e.getMessage();
        }
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

            // Use fixed total batches - calculate once and reuse
            int totalChunks = chunksCreated.get();
            int currentBatchSize = batch.size();

            // Set fixed batch size, total batches, and resume offset on first batch (compareAndSet ensures thread-safety)
            if (fixedBatchSize.get() < 0 && currentBatchSize > 0) {
                if (fixedBatchSize.compareAndSet(-1, currentBatchSize)) {
                    // Only the thread that successfully set fixedBatchSize does the resume offset calculation
                    if (resumeState != null && resumeState.indexedIds().size() > 0) {
                        int alreadyIndexed = resumeState.indexedIds().size();
                        int batchesAlreadyDone = alreadyIndexed / currentBatchSize;
                        if (batchesAlreadyDone > 0) {
                            resumedBatchOffset.set(batchesAlreadyDone);
                            logger.info("Resume offset set to {} batches ({} chunks already indexed)",
                                    batchesAlreadyDone, alreadyIndexed);
                        }
                    }
                }
            }
            if (fixedTotalBatches.get() < 0 && totalChunks > 0 && fixedBatchSize.get() > 0) {
                int calculatedTotal = (totalChunks + fixedBatchSize.get() - 1) / fixedBatchSize.get();
                if (fixedTotalBatches.compareAndSet(-1, calculatedTotal)) {
                    logger.info("Fixed total batches set to {} (totalChunks={}, batchSize={})",
                            calculatedTotal, totalChunks, fixedBatchSize.get());
                }
            }

            // Adjust batch number for display to account for resumed batches
            int displayBatchNum = batchNum + resumedBatchOffset.get();

            // Use fixed value if set, otherwise fall back to current batch number
            int estimatedTotalBatches = fixedTotalBatches.get() > 0
                    ? fixedTotalBatches.get()
                    : Math.max(batchNum, totalChunks > 0 && currentBatchSize > 0
                            ? (totalChunks + currentBatchSize - 1) / currentBatchSize
                            : batchNum);

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

            // NO ESTIMATES - will get real data from encoder when available

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
            // NO ESTIMATES - shapes will be populated by encoder
            int embDim = embeddingModel != null ? embeddingModel.dimensions() : 0;
            String inputShape = "[" + inputTexts + " x PENDING]";
            String outputShape = "[" + inputTexts + " x " + embDim + "]";

            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                    .batchNumber(displayBatchNum)
                    .totalBatches(estimatedTotalBatches)
                    .inputTexts(inputTexts)
                    .inputTokens(0) // Will be set by encoder
                    .maxSequenceLength(0) // Will be set by encoder
                    .avgSequenceLength(0) // Will be set by encoder
                    .outputVectors(0) // Not yet generated
                    .embeddingDimension(embDim)
                    .outputSizeBytes(0)
                    .inferenceTimeMs(0)
                    .totalBatchTimeMs(0)
                    .currentStep("STARTING")
                    .heartbeatSeconds(0)
                    .stepStartTimeMs(batchStartTime)
                    .isStuck(false)
                    .tokensPerSecond(0)
                    .embeddingsPerSecond(0)
                    .batchThroughput(0)
                    .modelName(modelName)
                    .deviceType("CPU")
                    .isBatched(true)
                    .inputTensorShape(inputShape)
                    .outputTensorShape(outputShape)
                    .actualInputShape(null)
                    .actualOutputShape(null)
                    .statusLevel("RUNNING")
                    .build();

            reportProgressWithWorkers("embedding", progressPercent,
                    String.format("BEGIN BATCH %d: Embedding %d chunks (%d/%d total) using %s",
                            displayBatchNum, inputTexts, embeddedSoFar, totalChunksCreated, modelName));
            // === END BEGIN BATCH ===

            // ========== CACHED EMBEDDINGS SUPPORT ==========
            // Check if any chunks have cached embeddings from a previous run
            List<float[]> embeddings;
            int cachedCount = 0;
            int computedCount = 0;

            if (!cachedEmbeddings.isEmpty()) {
                // Separate chunks into cached and non-cached
                List<RetrievedDoc> chunksToEmbed = new ArrayList<>();
                List<Integer> cachedIndices = new ArrayList<>();
                List<Integer> computeIndices = new ArrayList<>();

                for (int i = 0; i < actualBatch.size(); i++) {
                    RetrievedDoc chunk = actualBatch.get(i);
                    String chunkId = chunk.getId();
                    if (chunkId != null && cachedEmbeddings.containsKey(chunkId)) {
                        cachedIndices.add(i);
                        cachedCount++;
                    } else {
                        chunksToEmbed.add(chunk);
                        computeIndices.add(i);
                    }
                }

                if (cachedCount > 0) {
                    logger.info("Batch {}: Using {} cached embeddings, computing {} new",
                            batchNum, cachedCount, chunksToEmbed.size());
                }

                // Compute embeddings only for non-cached chunks
                List<float[]> newEmbeddings = null;
                if (!chunksToEmbed.isEmpty()) {
                    newEmbeddings = generateEmbeddingsOptimized(chunksToEmbed);
                    computedCount = newEmbeddings != null ? newEmbeddings.size() : 0;
                }

                // Merge cached and new embeddings in correct order
                embeddings = new ArrayList<>(actualBatch.size());
                int cachedIdx = 0;
                int computeIdx = 0;
                for (int i = 0; i < actualBatch.size(); i++) {
                    RetrievedDoc chunk = actualBatch.get(i);
                    String chunkId = chunk.getId();
                    if (chunkId != null && cachedEmbeddings.containsKey(chunkId)) {
                        embeddings.add(cachedEmbeddings.get(chunkId));
                        // Remove from cache after use (to free memory)
                        cachedEmbeddings.remove(chunkId);
                    } else if (newEmbeddings != null && computeIdx < newEmbeddings.size()) {
                        embeddings.add(newEmbeddings.get(computeIdx++));
                    } else {
                        // Fallback - should not happen
                        logger.warn("Batch {}: Missing embedding for chunk {}", batchNum, i);
                        embeddings.add(null);
                    }
                }
            } else {
                // No cached embeddings - compute all
                embeddings = generateEmbeddingsOptimized(actualBatch);
                computedCount = embeddings != null ? embeddings.size() : 0;
            }
            // ========== END CACHED EMBEDDINGS SUPPORT ==========

            // CRITICAL: Capture batch info IMMEDIATELY after encoding returns, before any
            // other operation that might allow another worker to overwrite currentBatchInfo.
            // This fixes the race condition where shapes shown are from a different batch.
            final EmbeddingModel.BatchInfo capturedBatchInfo = embeddingModel != null
                    ? embeddingModel.getCurrentBatchInfo() : null;

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

            // DIAGNOSTIC: Log the actual embedding state to help debug counter issues
            logger.info("Batch {}: EMBEDDING_STATE - actualEmbeddingDone={}, embeddings={}, embeddingsSize={}, cachedCount={}, computedCount={}",
                    batchNum, actualEmbeddingDone,
                    embeddings != null ? "non-null" : "NULL",
                    embeddings != null ? embeddings.size() : 0,
                    cachedCount, computedCount);
            if (!actualEmbeddingDone) {
                logger.warn("Batch {}: WARNING - actualEmbeddingDone is FALSE - counter will NOT increment! " +
                        "This means generateEmbeddingsOptimized returned null/empty. Check embedding model logs.", batchNum);
            }

            // Calculate output metrics
            int outputVectors = actualEmbeddingDone ? embeddings.size() : 0;
            int embeddingDimension = 0;
            long outputSizeBytes = 0;
            if (actualEmbeddingDone && !embeddings.isEmpty() && embeddings.get(0) != null) {
                embeddingDimension = embeddings.get(0).length;
                outputSizeBytes = (long) outputVectors * embeddingDimension * 4; // 4 bytes per float
            }

            // Use the batch info we captured IMMEDIATELY after encoding
            // This avoids the race condition where another worker overwrites currentBatchInfo.
            boolean hasActualInfo = capturedBatchInfo != null
                    && capturedBatchInfo.numChunks() > 0
                    && capturedBatchInfo.numChunks() == inputTexts  // Validate same batch size
                    && capturedBatchInfo.inputShape() != null
                    && capturedBatchInfo.inputShape().length > 0;  // Validate shapes are populated

            // ONLY use actual data from encoder - NO ESTIMATES
            int actualMaxSeqLen = hasActualInfo ? capturedBatchInfo.maxSeqLength() : 0;
            int actualInputTokens = hasActualInfo ? capturedBatchInfo.totalTokens() : 0;

            // Calculate throughput metrics using ACTUAL token count if available
            double batchThroughput = totalBatchTime > 0 ? (inputTexts * 1000.0 / totalBatchTime) : 0;
            double tokensPerSecond = (totalBatchTime > 0 && actualInputTokens > 0)
                    ? (actualInputTokens * 1000.0 / totalBatchTime) : 0;
            double embeddingsPerSecond = totalBatchTime > 0 ? (outputVectors * 1000.0 / totalBatchTime) : 0;

            // Get model info
            String deviceType = "CPU"; // TODO: detect CUDA if available

            // Calculate heartbeat elapsed seconds and detect stuck state
            int heartbeatSecs = (int) (totalBatchTime / 1000);
            boolean isStuck = totalBatchTime > 30000; // Stuck if > 30 seconds for a batch

            // Update tensor shape strings with ACTUAL values - NO ESTIMATES
            if (hasActualInfo) {
                inputShape = capturedBatchInfo.inputShapeString();
                outputShape = capturedBatchInfo.outputShapeString();
            } else {
                // If embedding failed or no batch info, use what we have
                inputShape = "[" + inputTexts + " x " + actualMaxSeqLen + "]";
                outputShape = "[" + outputVectors + " x " + embeddingDimension + "]";
            }

            // Use actual shapes from encoder if available
            String actualInputShapeStr = hasActualInfo ? capturedBatchInfo.inputShapeString() : inputShape;
            String actualOutputShapeStr = hasActualInfo ? capturedBatchInfo.outputShapeString() : outputShape;

            // Use actual timing from encoder if available - NO FALLBACKS
            long actualTokenizeTime = hasActualInfo ? capturedBatchInfo.tokenizeTimeMs() : 0;
            long actualPaddingTime = hasActualInfo ? capturedBatchInfo.paddingTimeMs() : 0;
            long actualTensorTime = hasActualInfo ? capturedBatchInfo.tensorCreationTimeMs() : 0;
            long actualForwardTime = hasActualInfo ? capturedBatchInfo.forwardPassTimeMs() : 0;
            long actualExtractTime = hasActualInfo ? capturedBatchInfo.extractionTimeMs() : 0;

            // Build and store embedding batch metrics - ONLY ACTUAL data from encoder
            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                    .batchNumber(displayBatchNum)
                    .totalBatches(estimatedTotalBatches)
                    .inputTexts(inputTexts)
                    .inputTokens(actualInputTokens)
                    .maxSequenceLength(actualMaxSeqLen)
                    .avgSequenceLength(hasActualInfo && capturedBatchInfo.numChunks() > 0
                            ? capturedBatchInfo.totalTokens() / capturedBatchInfo.numChunks() : 0)
                    .outputVectors(outputVectors)
                    .embeddingDimension(hasActualInfo ? capturedBatchInfo.embeddingDim() : embeddingDimension)
                    .outputSizeBytes(outputSizeBytes)
                    // Timing - use actual from encoder
                    .tokenizationTimeMs(actualTokenizeTime)
                    .inferenceTimeMs(actualForwardTime)
                    .totalBatchTimeMs(totalBatchTime)
                    .paddingTimeMs(actualPaddingTime)
                    .tensorCreationTimeMs(actualTensorTime)
                    .forwardPassTimeMs(actualForwardTime)
                    .extractionTimeMs(actualExtractTime)
                    // Heartbeat/liveness
                    .currentStep("COMPLETED")
                    .heartbeatSeconds(heartbeatSecs)
                    .stepStartTimeMs(batchStartTime)
                    .isStuck(isStuck)
                    // Throughput - use actual from encoder if available
                    .tokensPerSecond(hasActualInfo ? capturedBatchInfo.tokensPerSecond() : tokensPerSecond)
                    .embeddingsPerSecond(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : embeddingsPerSecond)
                    .batchThroughput(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : batchThroughput)
                    .modelName(modelName)
                    .deviceType(deviceType)
                    .isBatched(true)
                    // ========== NEW DETAILED FIELDS ==========
                    // Source documents
                    .sourceDocuments(sourcesStr)
                    .sourceDocumentCount(sourceDocuments.size())
                    // Tensor shapes - use ACTUAL from encoder
                    .inputTensorShape(inputShape)
                    .outputTensorShape(outputShape)
                    .actualInputShape(actualInputShapeStr)
                    .actualOutputShape(actualOutputShapeStr)
                    // Status
                    .statusLevel("COMPLETED")
                    .etaMessage(null)
                    // Per-passage token counts
                    .passageTokenCounts(hasActualInfo ? capturedBatchInfo.passageTokenCounts() : null)
                    .build();

            // NOTE: Batch history is added AFTER confirming embedding succeeded (see below)
            // This ensures batch history, chunksEmbedded counter, and worker stats stay in sync

            // Write embeddings directly to vector store (no queue - embedding workers own the write)
            int actualVectorIndexed = 0;
            if (actualEmbeddingDone && embeddings != null && !embeddings.isEmpty()) {
                try {
                    // Write directly to vector store - this is the new simplified architecture
                    actualVectorIndexed = indexerService.indexToVectorStoreOnly(actualBatch, embeddings);
                    if (actualVectorIndexed != actualBatch.size()) {
                        logger.debug("Batch {}: Vector store indexed {} vs {} expected",
                                batchNum, actualVectorIndexed, actualBatch.size());
                    }

                    // Mark indexed chunks in checkpoint service for resume support
                    if (checkpointService != null && actualVectorIndexed > 0) {
                        List<String> indexedIds = actualBatch.stream()
                                .map(RetrievedDoc::getId)
                                .filter(id -> id != null)
                                .toList();
                        if (!indexedIds.isEmpty()) {
                            checkpointService.markIndexed(indexedIds);
                            logger.debug("Batch {}: Marked {} chunk IDs as indexed in checkpoint",
                                    batchNum, indexedIds.size());
                        }
                    }
                } catch (java.io.IOException e) {
                    logger.error("Batch {}: Vector store write failed: {}", batchNum, e.getMessage());
                    throw new RuntimeException("Vector store write failed", e);
                }
            } else if (!actualEmbeddingDone) {
                // Passthrough mode - no embeddings computed, indexer will handle embedding
                // This path is for keyword-only mode or when embedding model is null
                logger.debug("Batch {}: Passthrough mode (no embeddings), chunks will be indexed to Lucene only",
                        batchNum);
            }

            // Count as "embedded" (and written to vector store) if actual embedding work was done
            int processed;
            if (actualEmbeddingDone) {
                processed = chunksEmbedded.addAndGet(actualBatch.size());
                workerProcessed.addAndGet(actualBatch.size());
                logger.info("Batch {}: COUNTER_INCREMENTED - chunksEmbedded now {} (added {}), vectorIndexed={}",
                        batchNum, processed, actualBatch.size(), actualVectorIndexed);

                // Add to batch history ONLY after embedding succeeded
                // This keeps batch history, chunksEmbedded counter, and worker stats in sync
                addToBatchHistory(currentEmbeddingBatchMetrics);

                // Track total tokens processed for this batch - ONLY use actual data
                int batchTokens = (hasActualInfo && capturedBatchInfo != null)
                        ? capturedBatchInfo.totalTokens()
                        : 0; // NO ESTIMATES - use 0 if no actual data
                if (batchTokens > 0) {
                    tokensProcessed.addAndGet(batchTokens);
                }

                // === CHECKPOINT: Record progress for adaptive retry ===
                // Get chunk indices from the batch (if available) for checkpoint tracking
                ai.kompile.app.subprocess.IngestCheckpoint checkpoint =
                        ai.kompile.app.subprocess.IngestSubprocessMain.getCurrentCheckpoint();
                if (checkpoint != null) {
                    // Record batch completion using global progress counter
                    // The exact chunk indices don't matter as much as the count
                    List<Integer> batchIndices = new ArrayList<>();
                    int baseIndex = processed - actualBatch.size(); // Starting index for this batch
                    for (int i = 0; i < actualBatch.size(); i++) {
                        batchIndices.add(baseIndex + i);
                    }
                    checkpoint.markChunksEmbedded(batchIndices);
                    checkpoint.markChunksIndexed(batchIndices);
                    checkpoint.markBatchCompleted(batchNum);

                    // Save checkpoint periodically (every 5 batches or if batch is large)
                    if (batchNum % 5 == 0 || actualBatch.size() >= 50) {
                        ai.kompile.app.subprocess.IngestSubprocessMain.saveCheckpoint();
                        logger.debug("Checkpoint saved at batch {}, {} chunks processed", batchNum, processed);
                    }
                }
            } else {
                // Passthrough mode - chunks are not embedded, only indexed to Lucene
                processed = chunksEmbedded.get();
                // Still count as processed by this worker for status display
                workerProcessed.addAndGet(actualBatch.size());
            }

            // Update worker status with throughput (use displayBatchNum for UI)
            String statusText = actualEmbeddingDone
                    ? String.format("batch %d (%d @ %.1f/s)", displayBatchNum, batch.size(), batchThroughput)
                    : String.format("batch %d (%d passthrough)", displayBatchNum, batch.size());
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
                // processed = total chunks embedded and written to vector store so far
                int endProgressPercent = total > 0 ? Math.min(95, (processed * 100) / total) : 0;

                // Update batch metrics for END state - use capturedBatchInfo we saved earlier
                String endInputShape = "[" + batch.size() + " x " + actualMaxSeqLen + "]";
                String endOutputShape = "[" + batch.size() + " x " + embeddingDimension + "]";

                // Use the batch info we captured immediately after encoding (capturedBatchInfo)
                // This avoids race conditions with other workers
                String actualEndInputShape = hasActualInfo ? capturedBatchInfo.inputShapeString() : endInputShape;
                String actualEndOutputShape = hasActualInfo ? capturedBatchInfo.outputShapeString() : endOutputShape;

                // ONLY use actual token count from encoder - NO ESTIMATES
                int actualTokensForEnd = hasActualInfo ? capturedBatchInfo.totalTokens() : 0;
                currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                        .batchNumber(displayBatchNum)
                        .totalBatches(estimatedTotalBatches)
                        .inputTexts(batch.size())
                        .inputTokens(actualTokensForEnd)
                        .maxSequenceLength(hasActualInfo ? capturedBatchInfo.maxSeqLength() : actualMaxSeqLen)
                        .avgSequenceLength(hasActualInfo && capturedBatchInfo.numChunks() > 0
                                ? capturedBatchInfo.totalTokens() / capturedBatchInfo.numChunks() : 0)
                        .outputVectors(actualEmbeddingDone ? batch.size() : 0)
                        .embeddingDimension(hasActualInfo ? capturedBatchInfo.embeddingDim() : embeddingDimension)
                        .outputSizeBytes(outputSizeBytes)
                        .tokenizationTimeMs(hasActualInfo ? capturedBatchInfo.tokenizeTimeMs() : 0)
                        .inferenceTimeMs(hasActualInfo ? capturedBatchInfo.forwardPassTimeMs() : (actualEmbeddingDone ? totalBatchTime : 0))
                        .totalBatchTimeMs(totalBatchTime)
                        .paddingTimeMs(hasActualInfo ? capturedBatchInfo.paddingTimeMs() : 0)
                        .tensorCreationTimeMs(hasActualInfo ? capturedBatchInfo.tensorCreationTimeMs() : 0)
                        .forwardPassTimeMs(hasActualInfo ? capturedBatchInfo.forwardPassTimeMs() : 0)
                        .extractionTimeMs(hasActualInfo ? capturedBatchInfo.extractionTimeMs() : 0)
                        .currentStep("COMPLETE")
                        .heartbeatSeconds(0)
                        .stepStartTimeMs(0)
                        .isStuck(false)
                        .tokensPerSecond(hasActualInfo ? capturedBatchInfo.tokensPerSecond() : 0)
                        .embeddingsPerSecond(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : batchThroughput)
                        .batchThroughput(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : batchThroughput)
                        .modelName(modelName)
                        .deviceType("CPU")
                        .isBatched(true)
                        .inputTensorShape(endInputShape)
                        .outputTensorShape(endOutputShape)
                        .actualInputShape(actualEndInputShape)
                        .actualOutputShape(actualEndOutputShape)
                        .statusLevel("COMPLETE")
                        .passageTokenCounts(hasActualInfo ? capturedBatchInfo.passageTokenCounts() : null)
                        .build();

                reportProgressWithWorkers("embedding", endProgressPercent,
                        String.format("END BATCH %d: %s %d chunks in %dms (%.1f/s) [%d/%d total, dim=%d]",
                                batchNum,
                                actualEmbeddingDone ? "Embedded+Indexed" : "Skipped",
                                batch.size(), totalBatchTime, batchThroughput,
                                processed, total, embeddingDimension));
            }
            // === END END BATCH ===
        } catch (OutOfMemoryError oom) {
            // FAIL FAST: In-process OOM recovery is unreliable.
            // Let the subprocess crash cleanly so the parent can restart with reduced settings.
            // The parent process will use checkpoint to resume from last completed batch.
            logger.error("====================================================================");
            logger.error("OUT OF MEMORY in worker {} batch {} with {} chunks", workerId, batchNum, batch.size());
            logger.error("====================================================================");
            logger.error("Heap status: max={}MB, used={}MB, free={}MB",
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024),
                    Runtime.getRuntime().freeMemory() / (1024 * 1024));
            logger.error("OOM message: {}", oom.getMessage());
            logger.error("The subprocess will exit. Parent process will restart with reduced settings.");
            logger.error("====================================================================");

            lastError = "OutOfMemoryError in batch " + batchNum;
            // Throw to crash the subprocess cleanly - parent will restart with lower settings
            throw new RuntimeException("OutOfMemoryError in batch " + batchNum + " - subprocess will restart with reduced settings", oom);
        } catch (RuntimeException re) {
            // FAIL-FAST: All exceptions propagate immediately to fail the pipeline
            // Check if this is wrapping an OOM
            Throwable cause = re.getCause();
            while (cause != null) {
                if (cause instanceof OutOfMemoryError) {
                    logger.error("OUT OF MEMORY (wrapped) in batch {}: {} - subprocess will restart",
                            batchNum, cause.getMessage());
                    lastError = cause.getMessage();
                    throw re;
                }
                cause = cause.getCause();
            }
            logger.error("Embedding batch {} failed: {}", batchNum, re.getMessage(), re);
            lastError = re.getMessage();
            throw re;
        } catch (Exception e) {
            // FAIL-FAST: All exceptions propagate immediately to fail the pipeline
            logger.error("Embedding batch {} failed: {}", batchNum, e.getMessage(), e);
            lastError = e.getMessage();
            throw new RuntimeException("Embedding batch " + batchNum + " failed: " + e.getMessage(), e);
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
            // NO ESTIMATES - will get actual data from encoder
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
                            // Use fixed total batches if set, otherwise calculate
                            int totalChunks = chunksCreated.get();
                            int currentBatch = embeddingBatchesProcessed.get();
                            int displayBatch = currentBatch + resumedBatchOffset.get();  // Offset for resume
                            int estimatedTotal = fixedTotalBatches.get() > 0
                                    ? fixedTotalBatches.get()
                                    : (totalChunks > 0
                                            ? Math.max(displayBatch, (totalChunks + batchSize - 1) / batchSize)
                                            : displayBatch);

                            // Estimate remaining time using TOTAL pipeline elapsed time and completed batches
                            // NOT current batch elapsed (which would cause ETA to increase as batch takes longer)
                            String etaInfo = "";
                            if (displayBatch > 0 && estimatedTotal > displayBatch) {
                                int remainingBatches = estimatedTotal - displayBatch;
                                // Use total pipeline elapsed time divided by completed batches for average
                                long totalPipelineElapsed = System.currentTimeMillis() - startTimeMs.get();
                                if (totalPipelineElapsed > 0 && currentBatch > 0) {
                                    long avgBatchTimeMs = totalPipelineElapsed / currentBatch;
                                    long estRemainingMs = avgBatchTimeMs * remainingBatches;
                                    if (estRemainingMs > 3600000) { // > 1 hour
                                        etaInfo = String.format(" | ETA: ~%dh %dm", estRemainingMs / 3600000, (estRemainingMs % 3600000) / 60000);
                                    } else if (estRemainingMs > 60000) {
                                        etaInfo = String.format(" | ETA: ~%dm", estRemainingMs / 60000);
                                    } else if (estRemainingMs > 0) {
                                        etaInfo = String.format(" | ETA: ~%ds", estRemainingMs / 1000);
                                    }
                                }
                            }

                            // Get actual batch info from embedding model if available
                            // Poll a few times briefly to wait for actual shapes (encoder may be tokenizing)
                            EmbeddingModel.BatchInfo actualBatchInfo = embeddingModel.getCurrentBatchInfo();
                            boolean hasActualInfo = actualBatchInfo != null
                                    && actualBatchInfo.numChunks() > 0
                                    && actualBatchInfo.inputShape() != null
                                    && actualBatchInfo.inputShape().length > 0;

                            // If we don't have actual info yet, wait briefly and retry (encoder may be tokenizing)
                            if (!hasActualInfo && actualBatchInfo != null && "TOKENIZING".equals(actualBatchInfo.step())) {
                                for (int retry = 0; retry < 3 && !hasActualInfo; retry++) {
                                    try {
                                        Thread.sleep(100); // Brief wait for tokenization to complete
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                    actualBatchInfo = embeddingModel.getCurrentBatchInfo();
                                    hasActualInfo = actualBatchInfo != null
                                            && actualBatchInfo.numChunks() > 0
                                            && actualBatchInfo.inputShape() != null
                                            && actualBatchInfo.inputShape().length > 0;
                                }
                            }

                            // Build tensor shape strings - ONLY use actual from encoder, NO ESTIMATES
                            // If no actual info, show explicit "WAITING" to indicate data not yet available
                            String inputShape = hasActualInfo ? actualBatchInfo.inputShapeString() : "[WAITING]";
                            String outputShape = hasActualInfo ? actualBatchInfo.outputShapeString() : "[WAITING]";

                            // Build ETA message using total pipeline elapsed time (not current batch elapsed)
                            String etaMsg = "";
                            if (currentBatch > 0 && estimatedTotal > currentBatch) {
                                int remainingBatches = estimatedTotal - currentBatch;
                                long totalPipelineElapsedForMsg = System.currentTimeMillis() - startTimeMs.get();
                                if (totalPipelineElapsedForMsg > 0) {
                                    long avgBatchTimeMsForMsg = totalPipelineElapsedForMsg / currentBatch;
                                    long estRemainingMs = avgBatchTimeMsForMsg * remainingBatches;
                                    if (estRemainingMs > 3600000) {
                                        etaMsg = String.format("~%dh %dm remaining", estRemainingMs / 3600000, (estRemainingMs % 3600000) / 60000);
                                    } else if (estRemainingMs > 60000) {
                                        etaMsg = String.format("~%dm remaining", estRemainingMs / 60000);
                                    } else if (estRemainingMs > 0) {
                                        etaMsg = String.format("~%ds remaining", estRemainingMs / 1000);
                                    }
                                }
                            }

                            // ========== USE ACTUAL TIMING FROM ENCODER'S BATCHINFO ==========
                            // ONLY use real data from encoder - NO ESTIMATES
                            // If no actual info, use 0 or "WAITING" to indicate data not available
                            long actualTokenizeTimeMs = hasActualInfo ? actualBatchInfo.tokenizeTimeMs() : 0;
                            long actualPaddingTimeMs = hasActualInfo ? actualBatchInfo.paddingTimeMs() : 0;
                            long actualTensorTimeMs = hasActualInfo ? actualBatchInfo.tensorCreationTimeMs() : 0;
                            long actualForwardPassTimeMs = hasActualInfo ? actualBatchInfo.forwardPassTimeMs() : 0;
                            long actualExtractTimeMs = hasActualInfo ? actualBatchInfo.extractionTimeMs() : 0;
                            long actualTotalTimeMs = hasActualInfo ? actualBatchInfo.totalTimeMs() : 0;
                            double actualTokPerSec = hasActualInfo ? actualBatchInfo.tokensPerSecond() : 0;
                            double actualChunksPerSec = hasActualInfo ? actualBatchInfo.chunksPerSecond() : 0;
                            String actualStep = hasActualInfo ? actualBatchInfo.step() : "WAITING_FOR_ENCODER";
                            int actualTokens = hasActualInfo ? actualBatchInfo.totalTokens() : 0;
                            int actualNumChunks = hasActualInfo ? actualBatchInfo.numChunks() : 0;

                            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                                    .batchNumber(displayBatch)  // Use display batch for UI
                                    .totalBatches(estimatedTotal)
                                    .inputTexts(hasActualInfo ? actualNumChunks : batchSize) // Use actual OR known batch size
                                    .inputTokens(actualTokens) // 0 if no actual data
                                    .maxSequenceLength(hasActualInfo ? actualBatchInfo.maxSeqLength() : 0)
                                    .avgSequenceLength(hasActualInfo && actualBatchInfo.numChunks() > 0
                                            ? actualBatchInfo.totalTokens() / actualBatchInfo.numChunks() : 0)
                                    .outputVectors(actualNumChunks) // 0 if no actual data
                                    .embeddingDimension(hasActualInfo ? actualBatchInfo.embeddingDim() : 0)
                                    .outputSizeBytes(hasActualInfo ? (long) actualNumChunks * actualBatchInfo.embeddingDim() * 4 : 0)
                                    // ========== ACTUAL TIMING FROM ENCODER ==========
                                    .tokenizationTimeMs(actualTokenizeTimeMs)
                                    .inferenceTimeMs(actualForwardPassTimeMs)  // Use forward pass as primary inference time
                                    .totalBatchTimeMs(actualTotalTimeMs) // NO FALLBACK - use actual or 0
                                    .paddingTimeMs(actualPaddingTimeMs)
                                    .tensorCreationTimeMs(actualTensorTimeMs)
                                    .forwardPassTimeMs(actualForwardPassTimeMs)
                                    .extractionTimeMs(actualExtractTimeMs)
                                    // Heartbeat/liveness - key info for UI
                                    .currentStep(actualStep)
                                    .heartbeatSeconds(heartbeatSecs)
                                    .stepStartTimeMs(hasActualInfo ? actualBatchInfo.stepStartTimeMs() : startTime)
                                    .isStuck(isStuck)
                                    // ========== ACTUAL THROUGHPUT FROM ENCODER ==========
                                    .tokensPerSecond(actualTokPerSec)
                                    .embeddingsPerSecond(actualChunksPerSec)  // chunks/sec = embeddings/sec
                                    .batchThroughput(actualChunksPerSec)
                                    .modelName(modelName)
                                    .deviceType("CPU")
                                    .isBatched(true)
                                    // ========== SOURCE AND SHAPE INFO ==========
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
                                    // Per-passage token counts
                                    .passageTokenCounts(hasActualInfo ? actualBatchInfo.passageTokenCounts() : null)
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
                                // ========== TIMING BREAKDOWN FROM ENCODER ==========
                                if (actualBatchInfo.tokenizeTimeMs() > 0 || actualBatchInfo.forwardPassTimeMs() > 0) {
                                    logMsg.append("\n    TIMING: Tokenize=").append(actualBatchInfo.tokenizeTimeMs()).append("ms");
                                    logMsg.append(" | Pad=").append(actualBatchInfo.paddingTimeMs()).append("ms");
                                    logMsg.append(" | Tensor=").append(actualBatchInfo.tensorCreationTimeMs()).append("ms");
                                    logMsg.append(" | FORWARD=").append(actualBatchInfo.forwardPassTimeMs()).append("ms");
                                    logMsg.append(" | Extract=").append(actualBatchInfo.extractionTimeMs()).append("ms");
                                    if (actualBatchInfo.totalTimeMs() > 0) {
                                        logMsg.append(" | TOTAL=").append(actualBatchInfo.totalTimeMs()).append("ms");
                                    }
                                }
                                if (actualBatchInfo.tokensPerSecond() > 0) {
                                    logMsg.append("\n    THROUGHPUT: ").append(String.format("%.1f", actualBatchInfo.tokensPerSecond())).append(" tok/sec");
                                    logMsg.append(" | ").append(String.format("%.2f", actualBatchInfo.chunksPerSecond())).append(" chunks/sec");
                                }
                            } else {
                                // NO ACTUAL DATA FROM ENCODER - show explicit waiting state
                                String stepInfo = (actualBatchInfo != null && actualBatchInfo.step() != null)
                                        ? actualBatchInfo.step() : "WAITING_FOR_ENCODER";
                                logMsg.append("\n    Input Tensor:  [WAITING] - encoder not yet reporting");
                                logMsg.append("\n    Output Tensor: [WAITING] - encoder not yet reporting");
                                logMsg.append("\n    Token Stats: WAITING - (").append(stepInfo).append(")");
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
                            // Show tensor shapes for SLOW or worse batches - ONLY actual, NO ESTIMATES
                            if (!statusLevel.equals("RUNNING") && !statusLevel.equals("PROCESSING")) {
                                if (hasActualInfo) {
                                    uiStatus.append(" | ").append(actualBatchInfo.inputShapeString());
                                    uiStatus.append("→").append(actualBatchInfo.outputShapeString());
                                } else {
                                    uiStatus.append(" | [WAITING]→[WAITING]");
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
            // Configurable via pipelineConfig.embeddingTimeoutSeconds() (0 = no timeout)
            final int timeoutSeconds = pipelineConfig.embeddingTimeoutSeconds();
            final long EMBED_TIMEOUT_MS = timeoutSeconds > 0 ? timeoutSeconds * 1000L : Long.MAX_VALUE;

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
                    logger.error("=======================================================================");
                    logger.error("FATAL: EMBEDDING TIMEOUT after {}ms for batch of {} texts!",
                            EMBED_TIMEOUT_MS, texts.size());
                    logger.error("=======================================================================");
                    logger.error("The embedding model took too long to process this batch.");
                    logger.error("This typically indicates:");
                    logger.error("  1. The embedding model is not properly initialized");
                    logger.error("  2. The model is too large for available memory");
                    logger.error("  3. The batch size is too large");
                    logger.error("  4. A deadlock in the native code");
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

                    // Create a clear error message for the user
                    String errorMsg = String.format(
                        "EMBEDDING TIMEOUT: The embedding model failed to respond after %d seconds for a batch of %d texts. " +
                        "This usually means the model is stuck or not properly initialized. " +
                        "Try: (1) Restart the application, (2) Reduce batch size, (3) Check available memory, " +
                        "(4) Increase timeout in Processing Settings.",
                        timeoutSeconds, texts.size());

                    // Set lastError so it gets propagated to the user
                    lastError = errorMsg;

                    System.err.println("=======================================================================");
                    System.err.println("[EMBEDDING-TIMEOUT] FATAL ERROR - " + errorMsg);
                    System.err.println("=======================================================================");
                    System.err.flush();

                    // FAIL LOUDLY: Throw exception instead of silently skipping
                    // This will cause the pipeline to fail and report the error to the user
                    throw new RuntimeException(errorMsg, te);
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof OutOfMemoryError oom) {
                        // FAIL FAST: In-process OOM recovery is unreliable.
                        // Let the subprocess crash so parent can restart with reduced settings.
                        logger.error("OUT OF MEMORY during embedding {} texts - subprocess will restart", texts.size());
                        lastError = "OutOfMemoryError during embedding";
                        throw new RuntimeException("OutOfMemoryError during embedding - subprocess will restart with reduced settings", oom);
                    } else if (cause instanceof Error) {
                        // Other errors (StackOverflowError, etc.) - fail fast
                        logger.error("Critical error during embedding: {}", cause.getClass().getSimpleName(), cause);
                        throw new RuntimeException("Critical error: " + cause.getMessage(), cause);
                    } else if (cause instanceof Exception) {
                        throw (Exception) cause;
                    } else if (cause != null) {
                        throw new RuntimeException("Embedding failed", cause);
                    } else {
                        throw ee;
                    }
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

            // Validate we got the expected number of embeddings - FAIL LOUDLY if not
            if (embeddings == null || embeddings.isEmpty()) {
                String errorMsg = String.format(
                    "EMBEDDING FAILURE: Embedding model returned %s for batch of %d texts. " +
                    "The embedding model failed to generate embeddings. " +
                    "Check the embedding model logs for errors. Model: %s",
                    embeddings == null ? "NULL" : "EMPTY LIST",
                    texts.size(),
                    embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL");

                logger.error("=======================================================================");
                logger.error("FATAL: {}", errorMsg);
                logger.error("=======================================================================");
                logger.error("Check embedding model logs for errors. Model class: {}, initialized: {}",
                        embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL",
                        embeddingModel != null);

                // Set lastError so it gets propagated to the user
                lastError = errorMsg;

                System.err.println("=======================================================================");
                System.err.println("[EMBEDDING-FAILURE] FATAL ERROR - " + errorMsg);
                System.err.println("=======================================================================");
                System.err.flush();

                // FAIL LOUDLY: Throw exception instead of silently returning null
                throw new RuntimeException(errorMsg);
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
            // FAIL FAST: In-process OOM recovery is unreliable.
            // Let the subprocess crash so parent can restart with reduced settings.
            logger.error("OUT OF MEMORY during embedding {} chunks - subprocess will restart", chunks.size());
            lastError = "OutOfMemoryError during embedding";
            throw new RuntimeException("OutOfMemoryError during embedding - subprocess will restart with reduced settings", oom);
        } catch (RuntimeException re) {
            // FAIL-FAST: All exceptions propagate immediately to fail the pipeline
            // Check if this is wrapping an OOM
            Throwable cause = re.getCause();
            while (cause != null) {
                if (cause instanceof OutOfMemoryError) {
                    logger.error("OUT OF MEMORY (wrapped) during embedding: {} - subprocess will restart", cause.getMessage());
                    throw re;
                }
                cause = cause.getCause();
            }
            logger.error("Failed to generate embeddings: {}", re.getMessage(), re);
            throw re;
        } catch (Exception e) {
            // FAIL-FAST: All exceptions propagate immediately to fail the pipeline
            logger.error("Failed to generate embeddings: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding failed: " + e.getMessage(), e);
        }
    }

    // NOTE: The old startIndexingWorker() method that consumed from embeddedQueue has been removed.
    // With the new architecture:
    //   - Indexing workers (startIndexingWorkers) consume from chunkQueue and write to Lucene
    //   - Embedding workers (startEmbeddingWorkers) consume from chunkQueue, compute embeddings, and write to vector store

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

    /**
     * Updates the sliding window rate samples and calculates the windowed rate.
     * This provides a more accurate ETA by using recent throughput rather than overall average.
     *
     * @param currentProcessed current number of items processed
     * @return windowed rate in items per second, or -1 if not enough samples yet
     */
    private double updateAndGetWindowedRate(int currentProcessed) {
        long now = System.currentTimeMillis();

        // Only sample at intervals to avoid noise
        if (now - lastRateSampleTime >= RATE_SAMPLE_INTERVAL_MS) {
            // Store current sample
            rateSampleTimestamps[rateSampleIndex] = now;
            rateSampleCounts[rateSampleIndex] = currentProcessed;
            rateSampleIndex = (rateSampleIndex + 1) % RATE_WINDOW_SIZE;
            lastRateSampleTime = now;
        }

        // Find oldest valid sample in the window
        int oldestIndex = -1;
        long oldestTime = Long.MAX_VALUE;
        int validSamples = 0;

        for (int i = 0; i < RATE_WINDOW_SIZE; i++) {
            if (rateSampleTimestamps[i] > 0) {
                validSamples++;
                if (rateSampleTimestamps[i] < oldestTime) {
                    oldestTime = rateSampleTimestamps[i];
                    oldestIndex = i;
                }
            }
        }

        // Need at least 2 samples to calculate rate
        if (validSamples < 2 || oldestIndex < 0) {
            return -1;
        }

        // Calculate rate from oldest to current
        long timeDelta = now - oldestTime;
        int countDelta = currentProcessed - rateSampleCounts[oldestIndex];

        if (timeDelta > 0 && countDelta >= 0) {
            return (countDelta * 1000.0) / timeDelta;
        }

        return -1;
    }

    /**
     * Calculates ETA string using windowed rate with fallback to overall rate.
     *
     * @param remaining items remaining to process
     * @param currentProcessed current items processed
     * @param overallRate overall average rate (fallback)
     * @return formatted ETA string, or empty if cannot calculate
     */
    private String calculateEtaString(long remaining, int currentProcessed, double overallRate) {
        // Try windowed rate first (more accurate for ongoing work)
        double rate = updateAndGetWindowedRate(currentProcessed);

        // Fall back to overall rate if windowed rate not available
        if (rate < 0) {
            rate = overallRate;
        }

        if (rate > 0 && remaining > 0) {
            long etaSec = (long) (remaining / rate);
            if (etaSec > 3600) {
                return String.format(" (~%dh %dm)", etaSec / 3600, (etaSec % 3600) / 60);
            } else if (etaSec > 60) {
                return String.format(" (~%dm %ds)", etaSec / 60, etaSec % 60);
            } else {
                return String.format(" (~%ds)", etaSec);
            }
        }
        return "";
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

            int effectiveCompleted = vectorOnlyMode ? embedded : Math.max(indexed, embedded);
            double throughput = elapsed > 0 ? (effectiveCompleted * 1000.0 / elapsed) : 0;

            // Collect worker statuses
            List<PipelineProgress.WorkerStatus> workers = new ArrayList<>(workerStatuses.values());

            // Build queue status (embeddedQueue no longer exists - use 0)
            PipelineProgress.QueueStatus queueStatus = new PipelineProgress.QueueStatus(
                    chunkQueue.size(),
                    this.pipelineConfig.queueCapacity(),
                    0,  // embeddedQueue removed - embedding workers write directly to vector store
                    0);

            // Get current embedding batch metrics (may be null if not embedding)
            PipelineProgress.EmbeddingBatchMetrics batchMetrics = currentEmbeddingBatchMetrics;

            // Get batch history snapshot
            java.util.List<PipelineProgress.EmbeddingBatchMetrics> historySnapshot = getBatchHistory();

            // Build progress object first, then calculate overall progress
            PipelineProgress progress = new PipelineProgress(
                    phase,
                    0, // Will be calculated
                    message,
                    documentsProcessed.get(),
                    created,
                    embedded,
                    indexed,
                    tokensProcessed.get(),
                    throughput,
                    config.getMemoryUsagePercent(),
                    chunkingParallelism,
                    embeddingParallelism,
                    workers,
                    queueStatus,
                    batchMetrics,
                    historySnapshot);

            // Calculate the actual overall progress from work completed.
            // NOTE: PipelineProgress.calculateOverallProgress() does not know about vectorOnlyMode,
            // so compute percent here where we have full context.
            int calculatedPercent = calculateOverallProgressPercent(
                    phase,
                    progress.documentsProcessed(),
                    progress.chunksCreated(),
                    progress.chunksEmbedded(),
                    progress.chunksIndexed());

            // Create final progress with correct percentage
            PipelineProgress finalProgress = new PipelineProgress(
                    phase,
                    calculatedPercent,
                    message,
                    progress.documentsProcessed(),
                    progress.chunksCreated(),
                    progress.chunksEmbedded(),
                    progress.chunksIndexed(),
                    progress.tokensProcessed(),
                    progress.chunksPerSecond(),
                    progress.memoryUsagePercent(),
                    progress.chunkingWorkers(),
                    progress.embeddingWorkers(),
                    progress.workerStatuses(),
                    progress.queueStatus(),
                    progress.currentEmbeddingBatch(),
                    progress.batchHistory());

            try {
                progressCallback.accept(finalProgress);
            } catch (Exception e) {
                logger.warn("Progress callback failed: {}", e.getMessage());
            }
        }
    }

    private int calculateOverallProgressPercent(String phase, int documentsProcessed, int chunksCreated,
                                               int chunksEmbedded, int chunksIndexed) {
        if (phase != null) {
            String p = phase.trim().toLowerCase(java.util.Locale.ROOT);
            if (p.equals("completed") || p.equals("complete") || p.equals("done")) {
                return 100;
            }
        }

        if (chunksCreated <= 0) {
            return documentsProcessed > 0 ? Math.min(5, documentsProcessed) : 0;
        }

        // In vector-only mode, Lucene indexing is skipped entirely: embedding is the terminal stage.
        if (vectorOnlyMode) {
            final double READY_WEIGHT = 0.10;
            final double EMBEDDING_WEIGHT = 0.90;
            double embeddingProgress = (double) chunksEmbedded / chunksCreated;
            double overall = (READY_WEIGHT) + (embeddingProgress * EMBEDDING_WEIGHT);
            return clampPercent(overall);
        }

        // Passthrough/keyword-only mode: embedding is performed by indexer (or skipped).
        boolean isPassthroughMode = (chunksEmbedded == 0 && chunksIndexed > 0);
        if (isPassthroughMode || pipelineConfig.skipEmbedding()) {
            final double READY_WEIGHT = 0.10;
            final double INDEXING_WEIGHT = 0.90;
            double indexingProgress = (double) chunksIndexed / chunksCreated;
            double overall = (READY_WEIGHT) + (indexingProgress * INDEXING_WEIGHT);
            return clampPercent(overall);
        }

        // Standard mode: chunking (10%) + embedding (50%) + indexing (40%).
        final double READY_WEIGHT = 0.10;
        final double EMBEDDING_WEIGHT = 0.50;
        final double INDEXING_WEIGHT = 0.40;

        double embeddingProgress = (double) chunksEmbedded / chunksCreated;
        double indexingProgress = (double) chunksIndexed / chunksCreated;
        double overall = (READY_WEIGHT) + (embeddingProgress * EMBEDDING_WEIGHT) + (indexingProgress * INDEXING_WEIGHT);
        return clampPercent(overall);
    }

    private static int clampPercent(double overallProgress01) {
        double clamped = Math.max(0.0, Math.min(1.0, overallProgress01));
        return Math.min(100, Math.max(0, (int) (clamped * 100)));
    }

    public void cancel() {
        cancelled = true;
        logger.info("Pipeline cancellation requested");
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Check if the subprocess memory watchdog indicates we should stop or kill the pipeline.
     * This is only relevant when running in subprocess mode.
     *
     * @return true if we should terminate due to memory pressure
     */
    private boolean checkSubprocessMemoryLimit() {
        // Try both subprocess types - only one will be active at a time
        SubprocessMemoryWatchdog watchdog = IngestSubprocessMain.getMemoryWatchdog();
        if (watchdog == null) {
            watchdog = ai.kompile.app.subprocess.VectorPopulationSubprocessMain.getMemoryWatchdog();
        }
        if (watchdog == null) {
            return false; // Not running in subprocess mode
        }

        if (watchdog.shouldKill()) {
            if (!cancelled) {
                SubprocessMemoryWatchdog.MemorySnapshot snapshot = watchdog.getLastSnapshot();
                String msg = String.format(
                        "MEMORY KILL: Pipeline terminated due to memory kill threshold (%.1f%% > %d%%). " +
                        "Used: %dMB, Max: %dMB",
                        snapshot != null ? snapshot.usagePercent() : 0,
                        ai.kompile.app.subprocess.SubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT,
                        snapshot != null ? snapshot.usedMB() : 0,
                        snapshot != null ? snapshot.maxMB() : 0);
                logger.error(msg);
                lastError = msg;
                cancelled = true;
            }
            return true;
        }

        if (watchdog.shouldStop()) {
            if (!cancelled) {
                SubprocessMemoryWatchdog.MemorySnapshot snapshot = watchdog.getLastSnapshot();
                logger.warn("MEMORY PRESSURE: Memory threshold exceeded ({}%), stopping new work. Used: {}MB, Max: {}MB",
                        snapshot != null ? String.format("%.1f", snapshot.usagePercent()) : "?",
                        snapshot != null ? snapshot.usedMB() : 0,
                        snapshot != null ? snapshot.maxMB() : 0);
                // Don't set cancelled=true here - we want to finish current batch first
                // But we'll return true so the worker can decide to stop accepting new work
            }
            return true;
        }

        return false;
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
        // Without this, RetrievedDoc objects remain in memory after pipeline completion or cancellation
        int chunkQueueSize = chunkQueue.size();
        chunkQueue.clear();
        if (chunkQueueSize > 0) {
            logger.info("Cleared {} chunks from queue during close", chunkQueueSize);
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

        logger.info("ParallelIngestPipeline closed (indexing={} threads, embedding={} threads)",
                indexingParallelism, embeddingParallelism);
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

    /**
     * Adds a completed batch to the history, maintaining a fixed size.
     */
    private void addToBatchHistory(PipelineProgress.EmbeddingBatchMetrics metrics) {
        if (metrics == null) return;
        batchHistory.addFirst(metrics);
        // Trim to max size
        while (batchHistory.size() > BATCH_HISTORY_SIZE) {
            batchHistory.removeLast();
        }
    }

    /**
     * Returns the batch history (most recent first).
     */
    public java.util.List<PipelineProgress.EmbeddingBatchMetrics> getBatchHistory() {
        return new java.util.ArrayList<>(batchHistory);
    }

    /**
     * Returns true since indexing and embedding always run in parallel with the new architecture.
     * @deprecated This method is deprecated. Indexing and embedding always run in parallel.
     */
    @Deprecated
    public boolean isParallelIndexingEnabled() {
        return true;  // Always parallel in new architecture
    }

    public int getChunkQueueSize() {
        return chunkQueue.size();
    }

    /**
     * Returns 0 since embeddedQueue no longer exists.
     * Embedding workers now write directly to vector store.
     * @deprecated This method is deprecated. EmbeddedQueue no longer exists.
     */
    @Deprecated
    public int getEmbeddedQueueSize() {
        return 0;  // No longer exists
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
     * @return Handle for monitoring the workers
     */
    public ExternalProducerHandle startWorkersForExternalProducer() {
        startTimeMs.set(System.currentTimeMillis());

        logger.info("=== STARTING EXTERNAL PRODUCER WORKERS ===");
        int modelBatchSize = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : pipelineConfig.defaultBatchSize();
        logger.info("indexingParallelism={}, embeddingParallelism={}, modelBatchSize={}, embeddingModel={}, chunkQueue.size={}",
                indexingParallelism, embeddingParallelism, modelBatchSize,
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL",
                chunkQueue.size());

        // Start indexing workers (consume from chunkQueue, write to Lucene)
        logger.info("Calling startIndexingWorkers()...");
        List<Future<?>> indexingFutures = startIndexingWorkers();
        logger.info("startIndexingWorkers() returned {} futures (Lucene writers)", indexingFutures.size());

        // Start embedding workers (consume from chunkQueue, compute embeddings, write to vector store)
        logger.info("Calling startEmbeddingWorkers()...");
        List<Future<?>> embeddingFutures = startEmbeddingWorkers();
        logger.info("startEmbeddingWorkers() returned {} futures (embedding + vector store writers)", embeddingFutures.size());

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
                    int embedded = chunksEmbedded.get();  // Embedded AND written to vector store
                    int indexed = chunksIndexed.get();    // Written to Lucene
                    int chunkQueueSize = chunkQueue.size();
                    long elapsed = System.currentTimeMillis() - startTimeMs.get();
                    double rate = elapsed > 0 ? (Math.max(indexed, embedded) * 1000.0 / elapsed) : 0;

                    // Determine phase based on actual work being done
                    String phase;
                    if (embeddingComplete && indexingComplete) {
                        phase = "complete";
                    } else if (indexed > 0 || embedded > 0) {
                        phase = "indexing+embedding";
                    } else if (created > 0) {
                        phase = "processing";
                    } else {
                        phase = "waiting";
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
                            .append(", embeddingComplete=").append(embeddingComplete)
                            .append(", indexingComplete=").append(indexingComplete);
                    workerInfo.append("\n  [Queues] chunkQueue=").append(chunkQueueSize);
                    workerInfo.append("\n  [Totals] created=").append(created)
                            .append(", indexed=").append(indexed).append(" (Lucene)")
                            .append(", embedded=").append(embedded).append(" (vector)")
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
                            String.format("%d created, %d indexed (Lucene), %d embedded (vector) (%.1f/sec) [Q:%d]",
                                    created, indexed, embedded, rate, chunkQueueSize));
                }
            } catch (Exception e) {
                logger.error("Error in periodic progress reporter (ext): {}", e.getMessage(), e);
            }
        }, 1, 1, TimeUnit.SECONDS);

        return new ExternalProducerHandle(embeddingFutures, indexingFutures, progressReporter);
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
            // Wait for indexing to complete (Lucene writes)
            String indexingError = null;
            for (Future<?> future : handle.indexingFutures()) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Lucene indexing timed out");
                    cancelled = true;
                    indexingError = "Lucene indexing timed out after 30 minutes";
                } catch (ExecutionException e) {
                    logger.error("Lucene indexing failed: {}", e.getCause().getMessage(), e.getCause());
                    cancelled = true;  // Mark as failed so job history shows failure
                    indexingError = e.getCause().getMessage();
                }
            }
            indexingComplete = true;

            // If indexing failed, throw to ensure job is marked as failed
            if (indexingError != null) {
                throw new RuntimeException("Lucene indexing failed: " + indexingError);
            }
            logger.info("Indexing complete: {} chunks indexed to Lucene", chunksIndexed.get());

            // Wait for embedding to complete (embedding + vector store writes)
            String embeddingError = null;
            for (Future<?> future : handle.embeddingFutures()) {
                try {
                    future.get(30, TimeUnit.MINUTES);
                } catch (TimeoutException e) {
                    logger.error("Embedding timed out");
                    cancelled = true;
                    embeddingError = "Embedding timed out after 30 minutes";
                } catch (ExecutionException e) {
                    logger.error("Embedding failed: {}", e.getCause().getMessage(), e.getCause());
                    cancelled = true;  // Mark as failed so job history shows failure
                    embeddingError = e.getCause().getMessage();
                }
            }
            embeddingComplete = true;

            // If embedding failed, throw to ensure job is marked as failed
            if (embeddingError != null) {
                throw new RuntimeException("Embedding/indexing failed: " + embeddingError);
            }

            logger.info("Embedding complete: {} chunks embedded and written to vector store in {} batches",
                    chunksEmbedded.get(), embeddingBatchesProcessed.get());

            List<String> processedIds = new ArrayList<>();

            // ========== PIPELINE COMPLETE ==========
            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            double chunksPerSec = totalTimeMs > 0 ? (Math.max(chunksIndexed.get(), chunksEmbedded.get()) * 1000.0 / totalTimeMs) : 0;

            reportProgress("COMPLETED", 100,
                    String.format("=== PIPELINE COMPLETE (external producer) === %d created, %d indexed (Lucene), %d embedded (vector) in %dms (%.1f/sec)",
                            chunksCreated.get(), chunksIndexed.get(), chunksEmbedded.get(), totalTimeMs, chunksPerSec));

            logger.info("========== PIPELINE COMPLETE (external producer): {} created -> {} indexed (Lucene), {} embedded (vector) in {}ms ({} chunks/sec) ==========",
                    chunksCreated.get(), chunksIndexed.get(), chunksEmbedded.get(), totalTimeMs, String.format("%.1f", chunksPerSec));

            return new PipelineResult(
                    documentsProcessed.get(),
                    chunksCreated.get(),
                    Math.max(chunksIndexed.get(), chunksEmbedded.get()),  // Use the higher of the two
                    tokensProcessed.get(),
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
            List<Future<?>> indexingFutures,
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
