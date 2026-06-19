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

import ai.kompile.app.subprocess.IngestSubprocessMain;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Indexing worker stage for the parallel ingest pipeline.
 *
 * <p>Consumes chunks from the shared {@code chunkQueue} and writes them to the
 * Lucene keyword index via {@link IndexerService}. Runs in parallel with the
 * {@link EmbeddingStage} so that keyword search is available as soon as possible
 * while dense-vector embeddings are still being computed.</p>
 *
 * <p>Batch writes are used to amortise Lucene commit overhead.  The batch size
 * is controlled by {@link IngestPipelineConfig#indexingBatchAccumulationSize()}.</p>
 */
public class IndexingStage {

    private static final Logger logger = LoggerFactory.getLogger(IndexingStage.class);

    // ---- dependencies ----
    private final IngestPipelineConfig pipelineConfig;
    private final IndexerService indexerService;
    private final BlockingQueue<RetrievedDoc> chunkQueue;
    private final ExecutorService indexingExecutor;
    private final int indexingParallelism;

    // ---- shared counters ----
    private final AtomicInteger chunksIndexed;
    private final AtomicInteger indexingBatchesProcessed;
    private final AtomicLong startTimeMs;

    // ---- shared worker-status map ----
    private final ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses;

    // ---- optional callbacks ----
    private Consumer<List<RetrievedDoc>> onChunkIndexedCallback;

    // ---- control flags (written by the pipeline) ----
    private volatile boolean cancelled = false;
    private volatile boolean chunkingComplete = false;
    private volatile String lastError = null;

    /**
     * Constructs an IndexingStage.
     *
     * @param pipelineConfig          pipeline configuration
     * @param indexerService          service for writing to the Lucene keyword index
     * @param chunkQueue              shared queue that chunking workers fill
     * @param indexingExecutor        thread pool for indexing workers
     * @param indexingParallelism     number of indexing worker threads
     * @param chunksIndexed           shared counter: total chunks written to Lucene
     * @param indexingBatchesProcessed shared counter: Lucene batches completed
     * @param startTimeMs             pipeline start timestamp
     * @param workerStatuses          shared map for per-worker status (for progress reporting)
     */
    public IndexingStage(
            IngestPipelineConfig pipelineConfig,
            IndexerService indexerService,
            BlockingQueue<RetrievedDoc> chunkQueue,
            ExecutorService indexingExecutor,
            int indexingParallelism,
            AtomicInteger chunksIndexed,
            AtomicInteger indexingBatchesProcessed,
            AtomicLong startTimeMs,
            ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses) {
        this.pipelineConfig = pipelineConfig;
        this.indexerService = indexerService;
        this.chunkQueue = chunkQueue;
        this.indexingExecutor = indexingExecutor;
        this.indexingParallelism = indexingParallelism;
        this.chunksIndexed = chunksIndexed;
        this.indexingBatchesProcessed = indexingBatchesProcessed;
        this.startTimeMs = startTimeMs;
        this.workerStatuses = workerStatuses;
    }

    public void setOnChunkIndexedCallback(Consumer<List<RetrievedDoc>> callback) {
        this.onChunkIndexedCallback = callback;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public void signalChunkingComplete() {
        this.chunkingComplete = true;
    }

    public String getLastError() {
        return lastError;
    }

    // ========== Worker Lifecycle ==========

    /**
     * Starts indexing workers that write chunks directly to Lucene (keyword index).
     * These workers run in parallel with embedding workers, both consuming from chunkQueue.
     * This enables immediate keyword searchability while embedding is still processing.
     *
     * @param vectorOnlyMode when true, skip Lucene indexing entirely
     * @return List of futures for the indexing workers
     */
    public List<Future<?>> startIndexingWorkers(boolean vectorOnlyMode) {
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
                final int INDEX_BATCH_SIZE = Math.max(32, pipelineConfig.indexingBatchAccumulationSize());

                while (!cancelled && !Thread.currentThread().isInterrupted()) {
                    // Check subprocess memory watchdog
                    if (checkSubprocessMemoryLimit()) {
                        logger.warn("Indexing worker {}: subprocess memory limit reached, exiting", workerId);
                        if (!batchBuffer.isEmpty()) {
                            processIndexBatch(batchBuffer, workerId, workerProcessed, ++batchNum);
                            batchBuffer.clear();
                        }
                        break;
                    }

                    try {
                        RetrievedDoc chunk = chunkQueue.poll(
                                pipelineConfig.queuePollTimeoutMs(), TimeUnit.MILLISECONDS);

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

    // ========== Batch Processing ==========

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

            // Notify cross-index tracking callback
            if (onChunkIndexedCallback != null) {
                try {
                    onChunkIndexedCallback.accept(batch);
                } catch (Exception cbEx) {
                    logger.warn("Cross-index tracking callback failed for batch {}: {}", batchNum, cbEx.getMessage());
                }
            }

            // Update counters
            int indexed = chunksIndexed.addAndGet(batchSize);
            workerProcessed.addAndGet(batchSize);
            indexingBatchesProcessed.incrementAndGet();

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

    // ========== Helpers ==========

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
}
