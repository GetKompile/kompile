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
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Embedding worker stage for the parallel ingest pipeline.
 *
 * <p>Consumes chunks from the shared {@code chunkQueue}, computes vector embeddings
 * using the configured {@link EmbeddingModel}, and writes the results directly to
 * the vector store via {@link IndexerService}.</p>
 *
 * <p>Each worker instance manages a pool of embedding threads. Parallelism within
 * a single forward pass is delegated to OpenMP/BLAS internally — so typically one
 * embedding thread is sufficient and additional threads cause contention.</p>
 */
public class EmbeddingStage {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingStage.class);

    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private static final long GC_PAUSE_MS           = 2_000L;
    private static final long POLL_INTERVAL_MS      = 100L;
    private static final long FIVE_MINUTES_MS       = 300_000L;
    private static final long ONE_HOUR_MS           = 3_600_000L;

    // ---- shared pipeline state (injected) ----
    private final IngestPipelineConfig pipelineConfig;
    private final EmbeddingModel embeddingModel;
    private final IndexerService indexerService;
    private final BlockingQueue<RetrievedDoc> chunkQueue;
    private final ExecutorService embeddingExecutor;
    private final int embeddingParallelism;

    // counters shared with the pipeline
    private final AtomicInteger chunksCreated;
    private final AtomicInteger chunksEmbedded;
    private final AtomicInteger embeddingBatchesProcessed;
    private final AtomicLong tokensProcessed;
    private final AtomicLong startTimeMs;

    // resume / checkpoint
    private IngestCheckpointService checkpointService;
    private IngestCheckpointService.CheckpointState resumeState;
    private final Map<String, float[]> cachedEmbeddings;

    // batch history for UI
    private static final int BATCH_HISTORY_SIZE = 5;
    private final Deque<PipelineProgress.EmbeddingBatchMetrics> batchHistory;

    private final AtomicInteger fixedTotalBatches;
    private final AtomicInteger fixedBatchSize;
    private final AtomicInteger resumedBatchOffset;

    // worker status map (shared with pipeline for progress reporting)
    private final ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses;

    // current batch metrics for heartbeat/UI
    private volatile PipelineProgress.EmbeddingBatchMetrics currentEmbeddingBatchMetrics = null;

    // control
    private volatile boolean cancelled = false;
    private volatile boolean chunkingComplete = false;
    private volatile boolean embeddingFailed = false;
    private volatile String embeddingFailureReason = null;
    private volatile String lastError = null;

    private final PipelineProgressReporter progressReporter;

    /**
     * Constructs an EmbeddingStage with all required dependencies.
     *
     * @param pipelineConfig        pipeline configuration
     * @param embeddingModel        model for generating vector embeddings (may be null for passthrough)
     * @param indexerService        service for writing to the vector store
     * @param chunkQueue            shared queue that chunking workers fill
     * @param embeddingExecutor     thread pool for embedding workers
     * @param embeddingParallelism  number of embedding worker threads
     * @param chunksCreated         shared counter: total chunks queued
     * @param chunksEmbedded        shared counter: chunks successfully embedded
     * @param embeddingBatchesProcessed shared counter: embedding batches completed
     * @param tokensProcessed       shared counter: total tokens processed
     * @param startTimeMs           pipeline start timestamp
     * @param cachedEmbeddings      pre-computed embeddings from a previous run (may be empty)
     * @param batchHistory          shared deque for batch history (for UI)
     * @param fixedTotalBatches     shared atomic: set once when total chunks is known
     * @param fixedBatchSize        shared atomic: batch size used for total-batch calculation
     * @param resumedBatchOffset    shared atomic: batches already done when resuming
     * @param workerStatuses        shared map for per-worker status (for progress reporting)
     * @param progressReporter      reporter for progress callbacks
     */
    public EmbeddingStage(
            IngestPipelineConfig pipelineConfig,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            BlockingQueue<RetrievedDoc> chunkQueue,
            ExecutorService embeddingExecutor,
            int embeddingParallelism,
            AtomicInteger chunksCreated,
            AtomicInteger chunksEmbedded,
            AtomicInteger embeddingBatchesProcessed,
            AtomicLong tokensProcessed,
            AtomicLong startTimeMs,
            Map<String, float[]> cachedEmbeddings,
            Deque<PipelineProgress.EmbeddingBatchMetrics> batchHistory,
            AtomicInteger fixedTotalBatches,
            AtomicInteger fixedBatchSize,
            AtomicInteger resumedBatchOffset,
            ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses,
            PipelineProgressReporter progressReporter) {
        this.pipelineConfig = pipelineConfig;
        this.embeddingModel = embeddingModel;
        this.indexerService = indexerService;
        this.chunkQueue = chunkQueue;
        this.embeddingExecutor = embeddingExecutor;
        this.embeddingParallelism = embeddingParallelism;
        this.chunksCreated = chunksCreated;
        this.chunksEmbedded = chunksEmbedded;
        this.embeddingBatchesProcessed = embeddingBatchesProcessed;
        this.tokensProcessed = tokensProcessed;
        this.startTimeMs = startTimeMs;
        this.cachedEmbeddings = cachedEmbeddings;
        this.batchHistory = batchHistory;
        this.fixedTotalBatches = fixedTotalBatches;
        this.fixedBatchSize = fixedBatchSize;
        this.resumedBatchOffset = resumedBatchOffset;
        this.workerStatuses = workerStatuses;
        this.progressReporter = progressReporter;
    }

    public void setCheckpointService(IngestCheckpointService service) {
        this.checkpointService = service;
    }

    public void setResumeState(IngestCheckpointService.CheckpointState state) {
        this.resumeState = state;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public void signalChunkingComplete() {
        this.chunkingComplete = true;
    }

    public boolean isEmbeddingFailed() {
        return embeddingFailed;
    }

    public String getEmbeddingFailureReason() {
        return embeddingFailureReason;
    }

    public PipelineProgress.EmbeddingBatchMetrics getCurrentEmbeddingBatchMetrics() {
        return currentEmbeddingBatchMetrics;
    }

    public List<PipelineProgress.EmbeddingBatchMetrics> getBatchHistory() {
        return new ArrayList<>(batchHistory);
    }

    // ========== Worker Lifecycle ==========

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
    public List<Future<?>> startEmbeddingWorkers() {
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
                int modelBatchSize = embeddingModel != null ? embeddingModel.getOptimalBatchSize() : 32;
                logger.info("[PIPELINE-DEBUG] Embedding worker {} STARTED on thread {} with model batch size={}",
                        workerId, Thread.currentThread().getName(), modelBatchSize);

                // Report embedding phase started immediately (before warmup)
                // This ensures frontend knows we've entered embedding phase
                if (workerId == 0) { // Only first worker reports to avoid spam
                    int queueSize = chunkQueue.size();
                    reportProgressWithWorkers("embedding", 0,
                            String.format("Starting embedding workers (%d chunks queued)", queueSize));
                }

                // Check embedding model is ready - dimensions() blocks until initialized
                if (embeddingModel != null) {
                    logger.debug("Embedding worker {}: checking model readiness...", workerId);
                    try {
                        int dims = embeddingModel.dimensions();
                        if (dims > 0) {
                            logger.info("Embedding worker {}: Model ready (dimensions={})", workerId, dims);
                        } else {
                            String reason = "Model returned invalid dimensions (" + dims + ")";
                            logger.error("Embedding worker {}: {}. Model may have failed to initialize. Check model configuration.",
                                    workerId, reason);
                            embeddingFailed = true;
                            embeddingFailureReason = reason;
                            workerStatuses.put(workerKey,
                                    new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                            0, 0, 0, "model initialization failed"));
                            return; // Exit this worker
                        }
                    } catch (Exception e) {
                        String reason = "Failed to get model dimensions: " + e.getMessage();
                        logger.error("Embedding worker {}: {}", workerId, reason);
                        embeddingFailed = true;
                        embeddingFailureReason = reason;
                        workerStatuses.put(workerKey,
                                new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                        0, 0, 0, "model error: " + e.getMessage()));
                        return;
                    }
                } else {
                    String reason = "Embedding model is NULL";
                    logger.error("Embedding worker {}: {}!", workerId, reason);
                    embeddingFailed = true;
                    embeddingFailureReason = reason;
                    workerStatuses.put(workerKey,
                            new PipelineProgress.WorkerStatus(workerId, "embedding", "failed",
                                    0, 0, 0, "no embedding model"));
                    return;
                }

                // Watchdog variables for detecting stalls
                long lastActivityTime = System.currentTimeMillis();
                int consecutiveEmptyPolls = 0;
                final int MAX_EMPTY_POLLS_BEFORE_EXIT = 20;

                // Progress reporting while waiting
                long lastWaitingProgressReportMs = System.currentTimeMillis();
                final long WAITING_PROGRESS_REPORT_INTERVAL_MS = 200;

                // Memory pressure tracking
                long lastMemoryCheckTime = System.currentTimeMillis();
                final long MEMORY_CHECK_INTERVAL_MS = 5000;
                final double MEMORY_PRESSURE_THRESHOLD = 0.85;

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
                            System.gc();
                            try {
                                Thread.sleep(GC_PAUSE_MS);
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

                    // Eagerly fetch chunks up to model's batch size
                    List<RetrievedDoc> batch = new ArrayList<>(modelBatchSize);

                    // First poll with timeout (blocking wait if queue is empty)
                    RetrievedDoc first;
                    try {
                        first = chunkQueue.poll(pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);
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

                        // Process batch through embedding model
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
                            int progressPercent = total > 0 ? Math.min(99, (int) ((embedded * 100L) / total)) : 0;
                            reportProgressWithWorkers("embedding", progressPercent,
                                    String.format("Embedded %d/%d chunks (waiting for chunking, queue=%d)",
                                            embedded, total, chunkQueue.size()));
                        }
                    }

                    // Robust exit condition
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
                    if (inactiveMs > 5000 && inactiveMs % 5000 < pipelineConfig.queuePollTimeoutMs()) {
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

    // ========== Batch Processing ==========

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
    void processBatchEmbeddingOptimized(List<RetrievedDoc> batch, int workerId, AtomicInteger workerProcessed) {
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

            // Set fixed batch size, total batches, and resume offset on first batch
            if (fixedBatchSize.get() < 0 && currentBatchSize > 0) {
                if (fixedBatchSize.compareAndSet(-1, currentBatchSize)) {
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
            Set<String> sourceDocuments = new LinkedHashSet<>();

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

            if (toEmbed.isEmpty() && !batch.isEmpty()) {
                logger.debug("Batch {}: All {} chunks already indexed, skipping", batchNum, batch.size());
                return;
            }

            List<RetrievedDoc> actualBatch = toEmbed;

            for (RetrievedDoc chunk : actualBatch) {
                String content = chunk.getText();
                int len = content != null ? content.length() : 0;
                totalChars += len;
                if (len > maxChars)
                    maxChars = len;

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
                            : sourceDocuments.stream().limit(2).collect(Collectors.joining(", ")) +
                                    " + " + (sourceDocuments.size() - 2) + " more");

            // Generate embeddings for batch
            String modelName = embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "passthrough";
            logger.info("Batch {}: Starting embedding for {} chunks using {}",
                    batchNum, batch.size(), modelName);

            // === BEGIN BATCH: Report batch start ===
            int totalChunksCreated = chunksCreated.get();
            int embeddedSoFar = chunksEmbedded.get();
            int progressPercent = totalChunksCreated > 0 ? Math.min(95, (embeddedSoFar * 100) / totalChunksCreated) : 0;

            int embDim = embeddingModel != null ? embeddingModel.dimensions() : 0;
            String inputShape = "[" + inputTexts + " x PENDING]";
            String outputShape = "[" + inputTexts + " x " + embDim + "]";

            currentEmbeddingBatchMetrics = PipelineProgress.EmbeddingBatchMetrics.builder()
                    .batchNumber(displayBatchNum)
                    .totalBatches(estimatedTotalBatches)
                    .inputTexts(inputTexts)
                    .inputTokens(0)
                    .maxSequenceLength(0)
                    .avgSequenceLength(0)
                    .outputVectors(0)
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
            List<float[]> embeddings;
            int cachedCount = 0;
            int computedCount = 0;

            if (!cachedEmbeddings.isEmpty()) {
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

                List<float[]> newEmbeddings = null;
                if (!chunksToEmbed.isEmpty()) {
                    newEmbeddings = generateEmbeddingsOptimized(chunksToEmbed);
                    computedCount = newEmbeddings != null ? newEmbeddings.size() : 0;
                }

                embeddings = new ArrayList<>(actualBatch.size());
                int cachedIdx = 0;
                int computeIdx = 0;
                for (int i = 0; i < actualBatch.size(); i++) {
                    RetrievedDoc chunk = actualBatch.get(i);
                    String chunkId = chunk.getId();
                    if (chunkId != null && cachedEmbeddings.containsKey(chunkId)) {
                        embeddings.add(cachedEmbeddings.get(chunkId));
                        cachedEmbeddings.remove(chunkId);
                    } else if (newEmbeddings != null && computeIdx < newEmbeddings.size()) {
                        embeddings.add(newEmbeddings.get(computeIdx++));
                    } else {
                        logger.warn("Batch {}: Missing embedding for chunk {}", batchNum, i);
                        embeddings.add(null);
                    }
                }
            } else {
                embeddings = generateEmbeddingsOptimized(actualBatch);
                computedCount = embeddings != null ? embeddings.size() : 0;
            }
            // ========== END CACHED EMBEDDINGS SUPPORT ==========

            // Capture batch info IMMEDIATELY after encoding returns
            final EmbeddingModel.BatchInfo capturedBatchInfo = embeddingModel != null
                    ? embeddingModel.getCurrentBatchInfo() : null;

            // Checkpoint hook
            if (checkpointService != null && embeddings != null) {
                checkpointService.saveEmbeddings(new ParallelIngestPipeline.EmbeddedBatch(new ArrayList<>(actualBatch), embeddings));
            }

            long inferenceEnd = System.currentTimeMillis();
            long totalBatchTime = inferenceEnd - batchStartTime;
            logger.info("Batch {}: Embedding complete in {}ms, got {} embeddings",
                    batchNum, totalBatchTime, embeddings != null ? embeddings.size() : 0);

            boolean actualEmbeddingDone = (embeddings != null && !embeddings.isEmpty());

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
                outputSizeBytes = (long) outputVectors * embeddingDimension * 4;
            }

            boolean hasActualInfo = capturedBatchInfo != null
                    && capturedBatchInfo.numChunks() > 0
                    && capturedBatchInfo.numChunks() == inputTexts
                    && capturedBatchInfo.inputShape() != null
                    && capturedBatchInfo.inputShape().length > 0;

            int actualMaxSeqLen = hasActualInfo ? capturedBatchInfo.maxSeqLength() : 0;
            int actualInputTokens = hasActualInfo ? capturedBatchInfo.totalTokens() : 0;

            double batchThroughput = totalBatchTime > 0 ? (inputTexts * 1000.0 / totalBatchTime) : 0;
            double tokensPerSecond = (totalBatchTime > 0 && actualInputTokens > 0)
                    ? (actualInputTokens * 1000.0 / totalBatchTime) : 0;
            double embeddingsPerSecond = totalBatchTime > 0 ? (outputVectors * 1000.0 / totalBatchTime) : 0;

            String deviceType = detectDeviceType();

            int heartbeatSecs = (int) (totalBatchTime / 1000);
            boolean isStuck = totalBatchTime > 30000;

            if (hasActualInfo) {
                inputShape = capturedBatchInfo.inputShapeString();
                outputShape = capturedBatchInfo.outputShapeString();
            } else {
                inputShape = "[" + inputTexts + " x " + actualMaxSeqLen + "]";
                outputShape = "[" + outputVectors + " x " + embeddingDimension + "]";
            }

            String actualInputShapeStr = hasActualInfo ? capturedBatchInfo.inputShapeString() : inputShape;
            String actualOutputShapeStr = hasActualInfo ? capturedBatchInfo.outputShapeString() : outputShape;

            long actualTokenizeTime = hasActualInfo ? capturedBatchInfo.tokenizeTimeMs() : 0;
            long actualPaddingTime = hasActualInfo ? capturedBatchInfo.paddingTimeMs() : 0;
            long actualTensorTime = hasActualInfo ? capturedBatchInfo.tensorCreationTimeMs() : 0;
            long actualForwardTime = hasActualInfo ? capturedBatchInfo.forwardPassTimeMs() : 0;
            long actualExtractTime = hasActualInfo ? capturedBatchInfo.extractionTimeMs() : 0;

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
                    .tokenizationTimeMs(actualTokenizeTime)
                    .inferenceTimeMs(actualForwardTime)
                    .totalBatchTimeMs(totalBatchTime)
                    .paddingTimeMs(actualPaddingTime)
                    .tensorCreationTimeMs(actualTensorTime)
                    .forwardPassTimeMs(actualForwardTime)
                    .extractionTimeMs(actualExtractTime)
                    .currentStep("COMPLETED")
                    .heartbeatSeconds(heartbeatSecs)
                    .stepStartTimeMs(batchStartTime)
                    .isStuck(isStuck)
                    .tokensPerSecond(hasActualInfo ? capturedBatchInfo.tokensPerSecond() : tokensPerSecond)
                    .embeddingsPerSecond(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : embeddingsPerSecond)
                    .batchThroughput(hasActualInfo ? capturedBatchInfo.chunksPerSecond() : batchThroughput)
                    .modelName(modelName)
                    .deviceType(deviceType)
                    .isBatched(true)
                    .sourceDocuments(sourcesStr)
                    .sourceDocumentCount(sourceDocuments.size())
                    .inputTensorShape(inputShape)
                    .outputTensorShape(outputShape)
                    .actualInputShape(actualInputShapeStr)
                    .actualOutputShape(actualOutputShapeStr)
                    .statusLevel("COMPLETED")
                    .etaMessage(null)
                    .passageTokenCounts(hasActualInfo ? capturedBatchInfo.passageTokenCounts() : null)
                    .build();

            // Write embeddings directly to vector store
            int actualVectorIndexed = 0;
            if (actualEmbeddingDone && embeddings != null && !embeddings.isEmpty()) {
                try {
                    actualVectorIndexed = indexerService.indexToVectorStoreOnly(actualBatch, embeddings);
                    if (actualVectorIndexed != actualBatch.size()) {
                        logger.debug("Batch {}: Vector store indexed {} vs {} expected",
                                batchNum, actualVectorIndexed, actualBatch.size());
                    }

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
                } catch (IOException e) {
                    logger.error("Batch {}: Vector store write failed: {}", batchNum, e.getMessage());
                    throw new RuntimeException("Vector store write failed", e);
                }
            } else if (!actualEmbeddingDone) {
                logger.debug("Batch {}: Passthrough mode (no embeddings), chunks will be indexed to Lucene only",
                        batchNum);
            }

            int processed;
            if (actualEmbeddingDone) {
                processed = chunksEmbedded.addAndGet(actualBatch.size());
                workerProcessed.addAndGet(actualBatch.size());
                logger.info("Batch {}: COUNTER_INCREMENTED - chunksEmbedded now {} (added {}), vectorIndexed={}",
                        batchNum, processed, actualBatch.size(), actualVectorIndexed);

                addToBatchHistory(currentEmbeddingBatchMetrics);

                int batchTokens = (hasActualInfo && capturedBatchInfo != null)
                        ? capturedBatchInfo.totalTokens()
                        : 0;
                if (batchTokens > 0) {
                    tokensProcessed.addAndGet(batchTokens);
                }

                // === CHECKPOINT: Record progress for adaptive retry ===
                ai.kompile.app.subprocess.IngestCheckpoint checkpoint =
                        IngestSubprocessMain.getCurrentCheckpoint();
                if (checkpoint != null) {
                    List<Integer> batchIndices = new ArrayList<>();
                    int baseIndex = processed - actualBatch.size();
                    for (int i = 0; i < actualBatch.size(); i++) {
                        batchIndices.add(baseIndex + i);
                    }
                    checkpoint.markChunksEmbedded(batchIndices);
                    checkpoint.markChunksIndexed(batchIndices);
                    checkpoint.markBatchCompleted(batchNum);

                    if (batchNum % 5 == 0 || actualBatch.size() >= 50) {
                        IngestSubprocessMain.saveCheckpoint();
                        logger.debug("Checkpoint saved at batch {}, {} chunks processed", batchNum, processed);
                    }
                }
            } else {
                processed = chunksEmbedded.get();
                workerProcessed.addAndGet(actualBatch.size());
            }

            // Update worker status with throughput
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

            // === END BATCH: Report batch completion ===
            int total = chunksCreated.get();
            if (total > 0) {
                int endProgressPercent = total > 0 ? Math.min(95, (processed * 100) / total) : 0;

                String endInputShape = "[" + batch.size() + " x " + actualMaxSeqLen + "]";
                String endOutputShape = "[" + batch.size() + " x " + embeddingDimension + "]";

                String actualEndInputShape = hasActualInfo ? capturedBatchInfo.inputShapeString() : endInputShape;
                String actualEndOutputShape = hasActualInfo ? capturedBatchInfo.outputShapeString() : endOutputShape;

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
            throw new RuntimeException("OutOfMemoryError in batch " + batchNum + " - subprocess will restart with reduced settings", oom);
        } catch (RuntimeException re) {
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
            logger.error("Embedding batch {} failed: {}", batchNum, e.getMessage(), e);
            lastError = e.getMessage();
            throw new RuntimeException("Embedding batch " + batchNum + " failed: " + e.getMessage(), e);
        }
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
    List<float[]> generateEmbeddingsOptimized(List<RetrievedDoc> chunks) {
        logger.debug("generateEmbeddingsOptimized called: chunks={}, embeddingModel={}",
                chunks != null ? chunks.size() : "null",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "NULL");

        if (embeddingModel == null) {
            logger.debug("generateEmbeddingsOptimized: embeddingModel is null, passthrough mode");
            return null;
        }

        try {
            List<String> texts = new ArrayList<>(chunks.size());
            for (RetrievedDoc chunk : chunks) {
                String content = chunk.getText();
                texts.add(content != null ? content : "");
            }

            logger.debug("generateEmbeddingsOptimized: calling embedBatch with {} texts on {}",
                    texts.size(), embeddingModel.getClass().getSimpleName());

            long startTime = System.currentTimeMillis();

            final Thread currentThread = Thread.currentThread();
            final AtomicBoolean inferenceComplete = new AtomicBoolean(false);
            final int batchSize = texts.size();
            final String modelName = embeddingModel.getClass().getSimpleName();
            final int avgTextLength = texts.stream().mapToInt(String::length).sum() / Math.max(1, texts.size());
            final int maxTextLength = texts.stream().mapToInt(String::length).max().orElse(0);
            final int totalChars = texts.stream().mapToInt(String::length).sum();
            final int embeddingDim = embeddingModel.dimensions();

            final int globalChunkStart = chunksEmbedded.get();
            final int globalChunkEnd = globalChunkStart + batchSize - 1;
            final int totalChunksExpected = chunksCreated.get();

            final Set<String> sourceDocuments = new LinkedHashSet<>();
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
                        int lastSlash = Math.max(srcStr.lastIndexOf('/'), srcStr.lastIndexOf('\\'));
                        if (lastSlash >= 0)
                            srcStr = srcStr.substring(lastSlash + 1);
                        sourceDocuments.add(srcStr);
                    }
                }
            }
            final String sourcesStr = sourceDocuments.isEmpty() ? "unknown"
                    : (sourceDocuments.size() <= 3 ? String.join(", ", sourceDocuments)
                            : sourceDocuments.stream().limit(2).collect(Collectors.joining(", ")) +
                                    " + " + (sourceDocuments.size() - 2) + " more");

            final String firstChunkPreview = texts.isEmpty() ? ""
                    : texts.get(0).substring(0, Math.min(50, texts.get(0).length())).replace("\n", " ").trim() + "...";

            Thread heartbeat = new Thread(() -> {
                int tick = 0;
                while (!inferenceComplete.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);
                        tick++;
                        if (!inferenceComplete.get()) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            int heartbeatSecs = tick * 5;

                            String statusLevel;
                            if (elapsed > FIVE_MINUTES_MS) {
                                statusLevel = "EXTREMELY_SLOW";
                            } else if (elapsed > 120000) {
                                statusLevel = "VERY_SLOW";
                            } else if (elapsed > 60000) {
                                statusLevel = "SLOW";
                            } else if (elapsed > 30000) {
                                statusLevel = "PROCESSING";
                            } else {
                                statusLevel = "RUNNING";
                            }
                            boolean isStuck = elapsed > 30000;

                            String elapsedFormatted;
                            if (heartbeatSecs >= 60) {
                                int mins = heartbeatSecs / 60;
                                int secs = heartbeatSecs % 60;
                                elapsedFormatted = String.format("%dm %02ds", mins, secs);
                            } else {
                                elapsedFormatted = heartbeatSecs + "s";
                            }

                            Runtime runtime = Runtime.getRuntime();
                            long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                            long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
                            int memoryPercent = (int) ((usedMemoryMB * 100) / maxMemoryMB);

                            int totalChunksNow = chunksCreated.get();
                            int currentBatch = embeddingBatchesProcessed.get();
                            int displayBatch = currentBatch + resumedBatchOffset.get();
                            int estimatedTotal = fixedTotalBatches.get() > 0
                                    ? fixedTotalBatches.get()
                                    : (totalChunksNow > 0
                                            ? Math.max(displayBatch, (totalChunksNow + batchSize - 1) / batchSize)
                                            : displayBatch);

                            String etaInfo = "";
                            if (displayBatch > 0 && estimatedTotal > displayBatch) {
                                int remainingBatches = estimatedTotal - displayBatch;
                                long totalPipelineElapsed = System.currentTimeMillis() - startTimeMs.get();
                                if (totalPipelineElapsed > 0 && currentBatch > 0) {
                                    long avgBatchTimeMs = totalPipelineElapsed / currentBatch;
                                    long estRemainingMs = avgBatchTimeMs * remainingBatches;
                                    if (estRemainingMs > ONE_HOUR_MS) {
                                        etaInfo = String.format(" | ETA: ~%dh %dm", estRemainingMs / ONE_HOUR_MS, (estRemainingMs % ONE_HOUR_MS) / 60000);
                                    } else if (estRemainingMs > 60000) {
                                        etaInfo = String.format(" | ETA: ~%dm", estRemainingMs / 60000);
                                    } else if (estRemainingMs > 0) {
                                        etaInfo = String.format(" | ETA: ~%ds", estRemainingMs / 1000);
                                    }
                                }
                            }

                            EmbeddingModel.BatchInfo actualBatchInfo = embeddingModel.getCurrentBatchInfo();
                            boolean hasActualInfo = actualBatchInfo != null
                                    && actualBatchInfo.numChunks() > 0
                                    && actualBatchInfo.inputShape() != null
                                    && actualBatchInfo.inputShape().length > 0;

                            if (!hasActualInfo && actualBatchInfo != null && "TOKENIZING".equals(actualBatchInfo.step())) {
                                for (int retry = 0; retry < 3 && !hasActualInfo; retry++) {
                                    try {
                                        Thread.sleep(POLL_INTERVAL_MS);
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

                            String inputShape = hasActualInfo ? actualBatchInfo.inputShapeString() : "[WAITING]";
                            String outputShape = hasActualInfo ? actualBatchInfo.outputShapeString() : "[WAITING]";

                            String etaMsg = "";
                            if (currentBatch > 0 && estimatedTotal > currentBatch) {
                                int remainingBatches = estimatedTotal - currentBatch;
                                long totalPipelineElapsedForMsg = System.currentTimeMillis() - startTimeMs.get();
                                if (totalPipelineElapsedForMsg > 0) {
                                    long avgBatchTimeMsForMsg = totalPipelineElapsedForMsg / currentBatch;
                                    long estRemainingMs = avgBatchTimeMsForMsg * remainingBatches;
                                    if (estRemainingMs > ONE_HOUR_MS) {
                                        etaMsg = String.format("~%dh %dm remaining", estRemainingMs / ONE_HOUR_MS, (estRemainingMs % ONE_HOUR_MS) / 60000);
                                    } else if (estRemainingMs > 60000) {
                                        etaMsg = String.format("~%dm remaining", estRemainingMs / 60000);
                                    } else if (estRemainingMs > 0) {
                                        etaMsg = String.format("~%ds remaining", estRemainingMs / 1000);
                                    }
                                }
                            }

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
                                    .batchNumber(displayBatch)
                                    .totalBatches(estimatedTotal)
                                    .inputTexts(hasActualInfo ? actualNumChunks : batchSize)
                                    .inputTokens(actualTokens)
                                    .maxSequenceLength(hasActualInfo ? actualBatchInfo.maxSeqLength() : 0)
                                    .avgSequenceLength(hasActualInfo && actualBatchInfo.numChunks() > 0
                                            ? actualBatchInfo.totalTokens() / actualBatchInfo.numChunks() : 0)
                                    .outputVectors(actualNumChunks)
                                    .embeddingDimension(hasActualInfo ? actualBatchInfo.embeddingDim() : 0)
                                    .outputSizeBytes(hasActualInfo ? (long) actualNumChunks * actualBatchInfo.embeddingDim() * 4 : 0)
                                    .tokenizationTimeMs(actualTokenizeTimeMs)
                                    .inferenceTimeMs(actualForwardPassTimeMs)
                                    .totalBatchTimeMs(actualTotalTimeMs)
                                    .paddingTimeMs(actualPaddingTimeMs)
                                    .tensorCreationTimeMs(actualTensorTimeMs)
                                    .forwardPassTimeMs(actualForwardPassTimeMs)
                                    .extractionTimeMs(actualExtractTimeMs)
                                    .currentStep(actualStep)
                                    .heartbeatSeconds(heartbeatSecs)
                                    .stepStartTimeMs(hasActualInfo ? actualBatchInfo.stepStartTimeMs() : startTime)
                                    .isStuck(isStuck)
                                    .tokensPerSecond(actualTokPerSec)
                                    .embeddingsPerSecond(actualChunksPerSec)
                                    .batchThroughput(actualChunksPerSec)
                                    .modelName(modelName)
                                    .deviceType("CPU")
                                    .isBatched(true)
                                    .sourceDocuments(sourcesStr)
                                    .sourceDocumentCount(sourceDocuments.size())
                                    .inputTensorShape(inputShape)
                                    .outputTensorShape(outputShape)
                                    .actualInputShape(hasActualInfo ? actualBatchInfo.inputShapeString() : null)
                                    .actualOutputShape(hasActualInfo ? actualBatchInfo.outputShapeString() : null)
                                    .statusLevel(statusLevel)
                                    .etaMessage(etaMsg)
                                    .passageTokenCounts(hasActualInfo ? actualBatchInfo.passageTokenCounts() : null)
                                    .build();

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

                            if (hasActualInfo) {
                                logMsg.append("\n    Input Tensor:  ").append(actualBatchInfo.inputShapeString());
                                logMsg.append(" = ").append(actualBatchInfo.numChunks()).append(" chunks × ");
                                logMsg.append(actualBatchInfo.maxSeqLength()).append(" tokens/chunk (ACTUAL)");
                                logMsg.append("\n    Output Tensor: ").append(actualBatchInfo.outputShapeString());
                                logMsg.append(" = ").append(actualBatchInfo.numChunks()).append(" chunks × ");
                                logMsg.append(actualBatchInfo.embeddingDim()).append("-dim embeddings (ACTUAL)");
                                logMsg.append("\n    Token Stats: ").append(actualBatchInfo.totalTokens())
                                        .append(" tokens total (ACTUAL)");
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
                            logger.info("{}", logMsg);

                            StringBuilder uiStatus = new StringBuilder();
                            uiStatus.append("Batch ").append(currentBatch).append("/").append(estimatedTotal);
                            uiStatus.append(" | Chunks ").append(globalChunkStart).append("-").append(globalChunkEnd);
                            uiStatus.append("/").append(totalChunksExpected > 0 ? totalChunksExpected : "?");
                            uiStatus.append(" (").append(elapsedFormatted).append(")");
                            if (!statusLevel.equals("RUNNING")) {
                                uiStatus.append(" [").append(statusLevel).append("]");
                            }
                            if (!statusLevel.equals("RUNNING") && !statusLevel.equals("PROCESSING")) {
                                if (hasActualInfo) {
                                    uiStatus.append(" | ").append(actualBatchInfo.inputShapeString());
                                    uiStatus.append("->").append(actualBatchInfo.outputShapeString());
                                } else {
                                    uiStatus.append(" | [WAITING]->[WAITING]");
                                }
                            }
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
                                logger.warn("[EMBEDDING-HEARTBEAT] Failed to report progress: {}", e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "embedding-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            logger.debug("[EMBEDDING-METRICS] Calling embedBatch: texts={}, avgLen={}, model={}",
                    texts.size(),
                    texts.stream().mapToInt(String::length).sum() / Math.max(1, texts.size()),
                    embeddingModel.getClass().getSimpleName());

            List<float[]> embeddings;

            final int timeoutSeconds = pipelineConfig.embeddingTimeoutSeconds();
            final long EMBED_TIMEOUT_MS = timeoutSeconds > 0 ? timeoutSeconds * 1000L : Long.MAX_VALUE;

            final List<String> finalTexts = texts;
            ExecutorService timeoutExecutor =
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "embed-batch-timeout");
                    t.setDaemon(true);
                    return t;
                });

            try {
                Future<List<float[]>> embedFuture =
                    timeoutExecutor.submit(() -> embeddingModel.embedBatch(finalTexts));

                try {
                    embeddings = embedFuture.get(EMBED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    embedFuture.cancel(true);

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

                    for (int i = 0; i < Math.min(3, texts.size()); i++) {
                        String preview = texts.get(i);
                        if (preview.length() > 100) {
                            preview = preview.substring(0, 100) + "...";
                        }
                        logger.error("  Text[{}] preview: {}", i, preview.replace("\n", "\\n"));
                    }

                    String errorMsg = String.format(
                        "EMBEDDING TIMEOUT: The embedding model failed to respond after %d seconds for a batch of %d texts. " +
                        "This usually means the model is stuck or not properly initialized. " +
                        "Try: (1) Restart the application, (2) Reduce batch size, (3) Check available memory, " +
                        "(4) Increase timeout in Processing Settings.",
                        timeoutSeconds, texts.size());

                    lastError = errorMsg;

                    logger.error("=======================================================================");
                    logger.error("[EMBEDDING-TIMEOUT] FATAL ERROR - {}", errorMsg);
                    logger.error("=======================================================================");

                    throw new RuntimeException(errorMsg, te);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause();
                    if (cause instanceof OutOfMemoryError oom) {
                        logger.error("OUT OF MEMORY during embedding {} texts - subprocess will restart", texts.size());
                        lastError = "OutOfMemoryError during embedding";
                        throw new RuntimeException("OutOfMemoryError during embedding - subprocess will restart with reduced settings", oom);
                    } else if (cause instanceof Error) {
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
                timeoutExecutor.shutdownNow();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            logger.debug("[EMBEDDING-METRICS] embedBatch returned: embeddings={}, elapsed={}ms{}",
                    embeddings != null ? embeddings.size() : "null",
                    elapsed,
                    embeddings != null && !embeddings.isEmpty() && embeddings.get(0) != null
                            ? ", dim=" + embeddings.get(0).length
                            : "");

            logger.info("generateEmbeddingsOptimized: embedBatch returned {} embeddings in {}ms",
                    embeddings != null ? embeddings.size() : 0, elapsed);

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

                lastError = errorMsg;

                logger.error("=======================================================================");
                logger.error("[EMBEDDING-FAILURE] FATAL ERROR - {}", errorMsg);
                logger.error("=======================================================================");

                throw new RuntimeException(errorMsg);
            }

            if (embeddings.size() != chunks.size()) {
                logger.warn("Embedding count mismatch: got {} embeddings for {} chunks",
                        embeddings.size(), chunks.size());
            }

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
            logger.error("OUT OF MEMORY during embedding {} chunks - subprocess will restart", chunks.size());
            lastError = "OutOfMemoryError during embedding";
            throw new RuntimeException("OutOfMemoryError during embedding - subprocess will restart with reduced settings", oom);
        } catch (RuntimeException re) {
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
            logger.error("Failed to generate embeddings: {}", e.getMessage(), e);
            throw new RuntimeException("Embedding failed: " + e.getMessage(), e);
        }
    }

    // ========== Helpers ==========

    private void addToBatchHistory(PipelineProgress.EmbeddingBatchMetrics metrics) {
        if (metrics == null) return;
        batchHistory.addFirst(metrics);
        while (batchHistory.size() > BATCH_HISTORY_SIZE) {
            batchHistory.removeLast();
        }
    }

    private boolean checkSubprocessMemoryLimit() {
        SubprocessMemoryWatchdog watchdog = IngestSubprocessMain.getMemoryWatchdog();
        if (watchdog == null) {
            watchdog = ai.kompile.app.subprocess.VectorPopulationSubprocessMain.getMemoryWatchdog();
        }
        if (watchdog == null) {
            return false;
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
            }
            return true;
        }

        return false;
    }

    private void reportProgressWithWorkers(String phase, int percent, String message) {
        // Delegate to the shared progress reporter using current state
        if (progressReporter != null) {
            progressReporter.reportProgressWithWorkers(
                    phase, percent, message,
                    startTimeMs.get(),
                    0, // documentsProcessed - not tracked here; pipeline holds it
                    chunksCreated.get(),
                    chunksEmbedded.get(),
                    0, // chunksIndexed - managed by IndexingStage
                    tokensProcessed.get(),
                    0, // chunkingParallelism - not known here
                    embeddingParallelism,
                    workerStatuses,
                    chunkQueue.size(),
                    currentEmbeddingBatchMetrics,
                    getBatchHistory(),
                    null); // AdaptivePipelineConfig - not held here; caller wires it up
        }
    }

    private static String detectDeviceType() {
        String backend = System.getProperty("org.nd4j.backend", "");
        if (backend.toLowerCase().contains("cuda") || backend.toLowerCase().contains("gpu")) {
            return "CUDA";
        }
        try {
            Class.forName("org.nd4j.linalg.jcublas.JCublasNDArrayFactory");
            return "CUDA";
        } catch (ClassNotFoundException e) {
            return "CPU";
        }
    }
}
