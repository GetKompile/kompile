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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.*;
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

    private static final int  MAX_RETRIES           = 30;

    // Pipeline configuration
    private final IngestPipelineConfig pipelineConfig;

    // Progress reporter (encapsulates ETA, rate, and progress callback logic)
    private final PipelineProgressReporter progressReporter;

    // Pipeline stages (encapsulate worker logic)
    private final EmbeddingStage embeddingStage;
    private final IndexingStage indexingStage;

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

    // Per-worker status tracking
    private final ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses = new ConcurrentHashMap<>();


    // Batch history - keep last N completed batches for UI visibility (populated by EmbeddingStage)
    private final Deque<PipelineProgress.EmbeddingBatchMetrics> batchHistory =
            new ConcurrentLinkedDeque<>();

    // Fixed total batches - set once when total chunks is known, never changes
    private final AtomicInteger fixedTotalBatches = new AtomicInteger(-1);  // -1 means not yet set
    private final AtomicInteger fixedBatchSize = new AtomicInteger(-1);     // The batch size used for calculation
    private final AtomicInteger resumedBatchOffset = new AtomicInteger(0);  // Batches already done when resuming

    // Callback for progress updates
    private Consumer<PipelineProgress> progressCallback;

    // Callback fired after each batch of chunks is written to the keyword index.
    // Used by DocumentIngestService to register passages in cross-index tracking.
    private Consumer<List<RetrievedDoc>> onChunkIndexedCallback;

    // Control flags
    private volatile boolean cancelled = false;
    private volatile boolean chunkingComplete = false;
    private volatile boolean embeddingComplete = false;
    private volatile boolean indexingComplete = false;
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
        if (embeddingStage != null) {
            embeddingStage.setResumeState(state);
        }
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
             IngestPipelineConfig.builder()
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
             IngestPipelineConfig.builder()
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
            IngestPipelineConfig pipelineConfig) {
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
            IngestPipelineConfig pipelineConfig,
            boolean vectorOnlyMode) {
        this.pipelineConfig = pipelineConfig != null ? pipelineConfig : IngestPipelineConfig.defaults();
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

        // Initialize progress reporter (delegates ETA/rate/callback logic to separate class)
        this.progressReporter = new PipelineProgressReporter(this.pipelineConfig, vectorOnlyMode);

        // Initialize pipeline stages (delegate worker logic to separate classes)
        this.embeddingStage = new EmbeddingStage(
                this.pipelineConfig,
                embeddingModel,
                indexerService,
                this.chunkQueue,
                this.embeddingExecutor,
                this.embeddingParallelism,
                this.chunksCreated,
                this.chunksEmbedded,
                this.embeddingBatchesProcessed,
                this.tokensProcessed,
                this.startTimeMs,
                this.cachedEmbeddings,
                this.batchHistory,
                this.fixedTotalBatches,
                this.fixedBatchSize,
                this.resumedBatchOffset,
                this.workerStatuses,
                this.progressReporter);

        this.indexingStage = new IndexingStage(
                this.pipelineConfig,
                indexerService,
                this.chunkQueue,
                this.indexingExecutor,
                this.indexingParallelism,
                this.chunksIndexed,
                this.indexingBatchesProcessed,
                this.startTimeMs,
                this.workerStatuses);

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
    public IngestPipelineConfig getPipelineConfig() {
        return pipelineConfig;
    }

    /**
     * Returns whether embedding is being skipped (keyword-only mode).
     */
    public boolean isSkipEmbedding() {
        return skipEmbedding;
    }

    public void setOnChunkIndexedCallback(Consumer<List<RetrievedDoc>> callback) {
        this.onChunkIndexedCallback = callback;
        if (indexingStage != null) {
            indexingStage.setOnChunkIndexedCallback(callback);
        }
    }

    public void setProgressCallback(Consumer<PipelineProgress> callback) {
        this.progressCallback = callback;
        progressReporter.setProgressCallback(callback);

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
        List<Future<?>> indexingFutures = indexingStage.startIndexingWorkers(vectorOnlyMode);
        if (!indexingFutures.isEmpty()) {
            logger.info("Started {} indexing workers (write to Lucene)", indexingFutures.size());
        }

        // Start embedding workers (consume from chunkQueue, compute embeddings, write to vector store)
        List<Future<?>> embeddingFutures = embeddingStage.startEmbeddingWorkers();
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
            embeddingStage.signalChunkingComplete();
            indexingStage.signalChunkingComplete();

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

                            if (onChunkIndexedCallback != null) {
                                try {
                                    onChunkIndexedCallback.accept(new ArrayList<>(accumulatedBatch));
                                } catch (Exception cbEx) {
                                    logger.warn("Cross-index callback failed: {}", cbEx.getMessage());
                                }
                            }

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

                                    // Notify cross-index tracking
                                    if (onChunkIndexedCallback != null) {
                                        try {
                                            onChunkIndexedCallback.accept(new ArrayList<>(accumulatedBatch));
                                        } catch (Exception cbEx) {
                                            logger.warn("Cross-index callback failed: {}", cbEx.getMessage());
                                        }
                                    }

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

                                // Notify cross-index tracking
                                if (onChunkIndexedCallback != null) {
                                    try {
                                        onChunkIndexedCallback.accept(new ArrayList<>(accumulatedBatch));
                                    } catch (Exception cbEx) {
                                        logger.warn("Cross-index callback failed: {}", cbEx.getMessage());
                                    }
                                }

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
                        while (!queued && !cancelled && !Thread.currentThread().isInterrupted() && retries < MAX_RETRIES) {
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
        List<Future<?>> embeddingFutures = embeddingStage.startEmbeddingWorkers();

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
            while (!queued && !cancelled && !embeddingStage.isEmbeddingFailed() && !Thread.currentThread().isInterrupted()) {
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
            if (embeddingStage.isEmbeddingFailed()) {
                String reason = embeddingStage.getEmbeddingFailureReason() != null ? embeddingStage.getEmbeddingFailureReason() : "Unknown embedding failure";
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
                            while (!queued && !cancelled && !embeddingStage.isEmbeddingFailed() && !Thread.currentThread().isInterrupted()
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
                            if (embeddingStage.isEmbeddingFailed()) {
                                String reason = embeddingStage.getEmbeddingFailureReason() != null ? embeddingStage.getEmbeddingFailureReason() : "Unknown embedding failure";
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

    // startEmbeddingWorkers() and startIndexingWorkers() are delegated to EmbeddingStage and IndexingStage.
    // processBatchEmbeddingOptimized(), generateEmbeddingsOptimized(), processIndexBatch() are in those stage classes.

    private RetrievedDoc convertToRetrievedDoc(Document doc) {
        return new RetrievedDoc(
                doc.getId() != null ? doc.getId() : UUID.randomUUID().toString(),
                doc.getText() != null ? doc.getText() : "",
                doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>());
    }

    private Document convertToSpringDocument(RetrievedDoc doc) {
        return new Document(
                doc.getId(),
                doc.getText(),
                doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>());
    }

    // ========== Progress Reporting (delegates to PipelineProgressReporter) ==========

    /**
     * Calculates ETA string using windowed rate with fallback to overall rate.
     * Delegates to {@link PipelineProgressReporter}.
     */
    private String calculateEtaString(long remaining, int currentProcessed, double overallRate) {
        return progressReporter.calculateEtaString(remaining, currentProcessed, overallRate);
    }

    private void reportProgressThrottled(String phase, int percent, String message) {
        progressReporter.reportProgressThrottled(phase, percent, message,
                startTimeMs.get(),
                documentsProcessed.get(), chunksCreated.get(),
                chunksEmbedded.get(), chunksIndexed.get(),
                tokensProcessed.get(),
                chunkingParallelism, embeddingParallelism,
                workerStatuses, chunkQueue.size(),
                embeddingStage.getCurrentEmbeddingBatchMetrics(), getBatchHistory(), config);
    }

    private void reportProgress(String phase, int percent, String message) {
        progressReporter.reportProgress(phase, percent, message,
                startTimeMs.get(),
                documentsProcessed.get(), chunksCreated.get(),
                chunksEmbedded.get(), chunksIndexed.get(),
                tokensProcessed.get(),
                chunkingParallelism, embeddingParallelism,
                workerStatuses, chunkQueue.size(),
                embeddingStage.getCurrentEmbeddingBatchMetrics(), getBatchHistory(), config);
    }

    private void reportProgressWithWorkers(String phase, int ignoredPercent, String message) {
        progressReporter.reportProgressWithWorkers(phase, ignoredPercent, message,
                startTimeMs.get(),
                documentsProcessed.get(), chunksCreated.get(),
                chunksEmbedded.get(), chunksIndexed.get(),
                tokensProcessed.get(),
                chunkingParallelism, embeddingParallelism,
                workerStatuses, chunkQueue.size(),
                embeddingStage.getCurrentEmbeddingBatchMetrics(), getBatchHistory(), config);
    }

    public void cancel() {
        cancelled = true;
        embeddingStage.cancel();
        indexingStage.cancel();
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
     * Returns the batch history (most recent first).
     * The history is populated by EmbeddingStage as batches complete.
     */
    public List<PipelineProgress.EmbeddingBatchMetrics> getBatchHistory() {
        return new ArrayList<>(batchHistory);
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
        List<Future<?>> indexingFutures = indexingStage.startIndexingWorkers(vectorOnlyMode);
        logger.info("startIndexingWorkers() returned {} futures (Lucene writers)", indexingFutures.size());

        // Start embedding workers (consume from chunkQueue, compute embeddings, write to vector store)
        logger.info("Calling startEmbeddingWorkers()...");
        List<Future<?>> embeddingFutures = embeddingStage.startEmbeddingWorkers();
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
        embeddingStage.signalChunkingComplete();
        indexingStage.signalChunkingComplete();
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
