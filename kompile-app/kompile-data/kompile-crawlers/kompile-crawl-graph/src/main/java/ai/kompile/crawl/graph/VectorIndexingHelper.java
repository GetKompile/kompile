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

import ai.kompile.core.crawl.graph.BatchRetryPolicy;
import ai.kompile.core.crawl.graph.DynamicBatchSizer;
import ai.kompile.core.crawl.graph.ResourceGovernorAdapter;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles vector embedding and indexing of documents during crawl pipeline execution.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component
class VectorIndexingHelper {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexingHelper.class);

    private static final int MIN_EMBEDDING_BATCH_SIZE = 4;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired
    private CrawlMemoryMonitor memoryMonitor;

    @Autowired
    private CrawlDocumentTracker documentTracker;

    @Autowired(required = false)
    private CrawlIndexTrackingCallback crawlIndexTrackingCallback;

    @Autowired
    private PipelineStepTracker pipelineStepTracker;

    @Autowired
    private GraphPersistenceHelper graphPersistenceHelper;

    /** Live resource signal (CPU/RAM/heap/native/GPU-VRAM). Null on CPU-only/test contexts. */
    @Autowired(required = false)
    private ResourceGovernorAdapter resourceGovernor;

    // ------------------------------------------------------------------
    // Configurable fields — kept in sync with the orchestrator via package-private setter
    // ------------------------------------------------------------------

    volatile int configuredVectorBatchSize = 0;
    volatile int memoryCriticalThresholdPercent = 90;
    volatile int memoryWaitThresholdPercent = 82;
    volatile int memoryWaitTimeoutSeconds = 300;

    /**
     * Effective memory pressure (0..1) for the embedding AIMD batch sizer.
     *
     * <p>Prefers the governor's unified signal (heap + native + GPU VRAM for this GPU stage); falls
     * back to JVM-heap-only when no governor is wired. {@link DynamicBatchSizer#recordBatchResult}
     * expects a 0..1 fraction — passing the raw 0..100 percent previously pinned the batch at minimum.</p>
     */
    private double embeddingPressure(UnifiedCrawlJob job) {
        if (resourceGovernor != null) {
            return resourceGovernor.effectiveMemoryPressure("EMBEDDING");
        }
        return job.getMemoryUsagePercent().get() / 100.0;
    }

    /**
     * Whether embedding should be deferred right now because GPU VRAM has no headroom.
     * Delegates to the governor (which owns the threshold); never defers when no governor is wired.
     */
    boolean shouldDeferForGpu() {
        return resourceGovernor != null && resourceGovernor.shouldDeferLocalWork("EMBEDDING");
    }

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

    void indexDocuments(List<Document> documents,
                        VectorIndexConfig config,
                        UnifiedCrawlJob job) {
        try {
            updateProgress(job, "EMBEDDING", "Preparing vector indexing", documents.size() + " chunk(s)");
            // Switch to target collection if specified
            if (config.getCollectionName() != null && !config.getCollectionName().isBlank()) {
                vectorStore.switchIndexPath(config.getCollectionName());
            }

            int batchSize = resolveEmbeddingBatchSize(config, job);
            int totalBatches = (int) Math.ceil(documents.size() / (double) batchSize);
            job.getChunksQueuedForEmbedding().set(documents.size());
            job.getVectorBatchesTotal().set(totalBatches);
            job.getVectorBatchesCompleted().set(0);
            job.getEmbeddingBatchSize().set(batchSize);
            pipelineStepTracker.updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    0, documents.size(), 0, 0, totalBatches, 0, null,
                    "Prepared " + totalBatches + " embedding/indexing batch(es)");

            // AIMD-based dynamic batch sizer replaces primitive adaptBatchSizeForMemory()
            DynamicBatchSizer embeddingSizer = config.isAdaptiveBatching()
                    ? DynamicBatchSizer.forEmbedding(batchSize) : null;
            // Retry policy for vector indexing batches
            BatchRetryPolicy<Document> vectorRetryPolicy = BatchRetryPolicy.forVectorIndexing();

            // Pipelined embed→store: overlap vector store writes with embedding of the next batch.
            // A single-thread executor handles async store writes so we don't block embedding.
            ExecutorService storeExec = Executors.newSingleThreadExecutor(
                    r -> { Thread t = new Thread(r, "vector-store-" + job.getJobId().substring(0, 8)); t.setDaemon(true); return t; });
            Future<Integer> pendingStore = null;
            List<Document> pendingBatch = null;
            int pendingBatchNumber = 0;
            long pendingBatchStartNs = 0;

            try {
            for (int i = 0; i < documents.size();) {
                if (isCancelled(job)) return;
                waitForMemoryCapacity(job, "EMBEDDING");

                int adaptiveBatchSize;
                if (embeddingSizer != null) {
                    memoryMonitor.updateMemorySnapshot(job);
                    adaptiveBatchSize = embeddingSizer.currentBatchSize();
                } else {
                    adaptiveBatchSize = batchSize;
                }
                int end = Math.min(i + adaptiveBatchSize, documents.size());
                List<Document> batch = new ArrayList<>(documents.subList(i, end));
                int batchNumber = job.getVectorBatchesCompleted().get() + 1;
                String batchLabel = "Embedding/indexing batch " + batchNumber + "/" + totalBatches;
                job.getCurrentBatchSize().set(batch.size());
                job.getCurrentBatchStep().set("EMBEDDING_BATCH " + batchNumber + "/" + totalBatches);
                updateProgress(job, "EMBEDDING",
                        batchLabel,
                        batch.size() + " chunk(s), effectiveBatchSize=" + adaptiveBatchSize);
                for (Document document : batch) {
                    documentTracker.recordDocumentVectorProgress(job, document, "RUNNING", 0, 0,
                            batchLabel, null, false);
                }

                long batchStartNs = System.nanoTime();
                try {
                    // Phase 1: Embed current batch (GPU-bound)
                    float[][] embeddings = embedBatchOnly(batch);

                    // Collect previous store result before submitting new one
                    if (pendingStore != null) {
                        collectPendingStoreResult(pendingStore, pendingBatch, pendingBatchNumber, totalBatches,
                                pendingBatchStartNs, embeddingSizer, vectorRetryPolicy, job);
                        pendingStore = null;
                        pendingBatch = null;
                    }

                    // Phase 2: Submit store to async executor (I/O-bound, overlaps with next embed)
                    final List<Document> storeBatch = batch;
                    final float[][] storeEmbeddings = embeddings;
                    pendingStore = storeExec.submit(() -> storeEmbeddingBatch(storeBatch, storeEmbeddings));
                    pendingBatch = batch;
                    pendingBatchNumber = batchNumber;
                    pendingBatchStartNs = batchStartNs;
                } catch (Exception e) {
                    // Embedding failed — collect any pending store first
                    if (pendingStore != null) {
                        try {
                            collectPendingStoreResult(pendingStore, pendingBatch, pendingBatchNumber, totalBatches,
                                    pendingBatchStartNs, embeddingSizer, vectorRetryPolicy, job);
                        } catch (Exception storeEx) {
                            log.warn("[Job {}] Pending store also failed while handling embed failure: {}",
                                    job.getJobId(), storeEx.getMessage());
                        }
                        pendingStore = null;
                        pendingBatch = null;
                    }
                    // Record failure for AIMD batch sizer
                    if (embeddingSizer != null) {
                        long elapsedMs = (System.nanoTime() - batchStartNs) / 1_000_000L;
                        memoryMonitor.updateMemorySnapshot(job);
                        embeddingSizer.recordBatchResult(batch.size(), elapsedMs, false,
                                embeddingPressure(job));
                        embeddingSizer.publishStats(job);
                    }
                    String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    // Evaluate retry policy before giving up
                    // batchKey = i (the stable start index of this batch); local embedding model has
                    // no backend chain, so no fallback selector.
                    BatchRetryPolicy.RetryDecision<Document> retryDecision = vectorRetryPolicy.evaluateFailure(
                            i, batch, adaptiveBatchSize, errorDetail, null, null, job);
                    switch (retryDecision.getAction()) {
                        case RETRY_SAME_BACKEND: {
                            log.warn("[Job {}] Vector batch {}/{} failed ({}), retrying with batch size {} after {}ms backoff",
                                    job.getJobId(), batchNumber, totalBatches, errorDetail,
                                    retryDecision.getReducedBatchSize(), retryDecision.getBackoffMs());
                            documentTracker.recordEvent(job, "EMBEDDING", "WARN",
                                    "Retrying vector batch " + batchNumber + "/" + totalBatches,
                                    "attempt=" + retryDecision.getAttempt() + ", batchSize=" + retryDecision.getReducedBatchSize()
                                            + ", backoff=" + retryDecision.getBackoffMs() + "ms");
                            try { Thread.sleep(retryDecision.getBackoffMs()); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt(); throw e;
                            }
                            // Shrink the sizer if OOM-related
                            if (embeddingSizer != null) {
                                while (embeddingSizer.currentBatchSize() > retryDecision.getReducedBatchSize()) {
                                    embeddingSizer.recordBatchResult(batch.size(), 0, false,
                                            embeddingPressure(job));
                                }
                            }
                            // Don't advance i — retry the same batch segment
                            continue;
                        }
                        case DEAD_LETTER: {
                            log.warn("[Job {}] Vector batch {}/{} exhausted retries, {} items sent to dead-letter queue",
                                    job.getJobId(), batchNumber, totalBatches, batch.size());
                            for (Document document : batch) {
                                documentTracker.recordDocumentVectorProgress(job, document, "FAILED", 0, 0,
                                        "Vector indexing exhausted retries in batch " + batchNumber + "/" + totalBatches,
                                        errorDetail, true);
                            }
                            documentTracker.recordEvent(job, "EMBEDDING", "ERROR",
                                    "Vector batch dead-lettered",
                                    batch.size() + " items after " + retryDecision.getAttempt() + " attempts");
                            break; // move to next batch (policy already cleared this batch's attempts)
                        }
                        case ABORT:
                        default: {
                            for (Document document : batch) {
                                documentTracker.recordDocumentVectorProgress(job, document, "FAILED", 0, 0,
                                        "Vector indexing failed in batch " + batchNumber + "/" + totalBatches,
                                        errorDetail, true);
                            }
                            throw e;
                        }
                    }
                } finally {
                    trimNativeMemory(job, "EMBEDDING",
                            "after vector batch " + batchNumber + "/" + totalBatches);
                }
                i = end;
            }

            // Collect the final pending store result
            if (pendingStore != null) {
                collectPendingStoreResult(pendingStore, pendingBatch, pendingBatchNumber, totalBatches,
                        pendingBatchStartNs, embeddingSizer, vectorRetryPolicy, job);
            }
            } finally {
                storeExec.shutdownNow();
            }

            job.getCurrentBatchStep().set("COMMITTING");
            updateProgress(job, "INDEXING", "Committing vector store", null);
            vectorStore.flushAndCommit();
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);

            // Update document-level vector status in cross-index tracker
            markDocumentsVectorIndexedInCrossIndex(job, documents);

            updateProgress(job, "INDEXING",
                    "Vector indexing complete", job.getDocumentsIndexed().get() + " chunk(s)");
            pipelineStepTracker.completePipelineStep(job, "VECTOR_INDEXING", job.getDocumentsIndexed().get(),
                    job.getDocumentsIndexed().get() + " chunk(s) indexed");
            log.info("Indexed {} documents to vector store", job.getDocumentsIndexed().get());
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.error("Vector indexing failed: {}", errorDetail, e);
            job.getErrorCount().incrementAndGet();
            job.getErrors().add("Vector indexing failed: " + e.getClass().getSimpleName() + ": " + errorDetail);
            pipelineStepTracker.failPipelineStep(job, "VECTOR_INDEXING", "Vector indexing failed: " + errorDetail);
            documentTracker.recordEvent(job, "INDEXING", "ERROR", "Vector indexing failed",
                    e.getClass().getSimpleName() + ": " + errorDetail);
            throw new RuntimeException("Vector indexing failed: " + errorDetail, e);
        } finally {
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    // ------------------------------------------------------------------
    // Sub-methods
    // ------------------------------------------------------------------

    /**
     * Embed a batch of documents and return the embedding vectors without storing.
     * This is the first phase of a pipelined embed→store workflow.
     */
    float[][] embedBatchOnly(List<Document> batch) {
        EmbeddingModel embeddingModel = primaryEmbeddingModel();
        if (embeddingModel == null) {
            throw new IllegalStateException("Embedding model is null during vector indexing — "
                    + "this should have been caught before reaching embedBatchOnly. "
                    + batch.size() + " documents NOT indexed.");
        }

        List<String> texts = new ArrayList<>(batch.size());
        List<float[]> embeddings = null;
        try {
            for (Document document : batch) {
                texts.add(indexableDocumentText(document));
            }
            embeddings = embeddingModel.embedBatch(texts);
            if (embeddings == null || embeddings.size() != batch.size()) {
                int actual = embeddings == null ? -1 : embeddings.size();
                throw new IllegalStateException("Embedding model returned " + actual
                        + " embedding(s) for " + batch.size() + " document(s)");
            }
            float[][] embeddingArray = new float[embeddings.size()][];
            for (int i = 0; i < embeddings.size(); i++) {
                float[] embedding = embeddings.get(i);
                if (!isIndexableEmbedding(embedding)) {
                    throw new IllegalStateException("Embedding model returned a non-indexable vector for document "
                            + batch.get(i).getId());
                }
                embeddingArray[i] = embedding;
            }
            return embeddingArray;
        } catch (Exception e) {
            log.error("Embedding batch FAILED for {} documents: {}", batch.size(), e.getMessage(), e);
            throw new IllegalStateException("Embedding batch failed for " + batch.size()
                    + " document(s): " + e.getMessage(), e);
        } finally {
            texts.clear();
            if (embeddings != null) {
                embeddings.clear();
            }
        }
    }

    /**
     * Store pre-computed embeddings to the vector store.
     * This is the second phase of a pipelined embed→store workflow.
     */
    int storeEmbeddingBatch(List<Document> batch, float[][] embeddingArray) {
        try {
            return vectorStore.addWithFloatArrayEmbeddings(batch, embeddingArray);
        } finally {
            if (embeddingArray != null) {
                Arrays.fill(embeddingArray, null);
            }
        }
    }

    int addVectorBatch(List<Document> batch) {
        float[][] embeddingArray = embedBatchOnly(batch);
        return storeEmbeddingBatch(batch, embeddingArray);
    }

    /**
     * Collect the result of an async store write submitted during pipelined embed→store.
     * Records progress, AIMD stats, and cross-index tracking for the completed store batch.
     */
    void collectPendingStoreResult(Future<Integer> storeFuture, List<Document> batch,
                                   int batchNumber, int totalBatches, long batchStartNs,
                                   DynamicBatchSizer embeddingSizer,
                                   BatchRetryPolicy<Document> vectorRetryPolicy,
                                   UnifiedCrawlJob job) {
        int indexed;
        try {
            indexed = storeFuture.get(300, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            storeFuture.cancel(true);
            throw new IllegalStateException("Vector store write timed out for batch " + batchNumber
                    + "/" + totalBatches + " (" + batch.size() + " docs)", te);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            throw new IllegalStateException("Vector store write failed for batch " + batchNumber
                    + "/" + totalBatches + ": " + cause.getMessage(), cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for vector store write", ie);
        }
        String batchLabel = "Vector indexed in batch " + batchNumber + "/" + totalBatches;
        String failMsg = indexed < batch.size()
                ? "Vector store accepted " + indexed + "/" + batch.size() + " document(s) for this batch"
                : null;
        for (int docIndex = 0; docIndex < batch.size(); docIndex++) {
            boolean indexedDocument = docIndex < indexed;
            documentTracker.recordDocumentVectorProgress(job, batch.get(docIndex),
                    indexedDocument ? "COMPLETED" : "FAILED",
                    indexedDocument ? 1 : 0,
                    indexedDocument ? 1 : 0,
                    indexedDocument ? batchLabel : "Vector store accepted fewer documents than were embedded",
                    indexedDocument ? null : failMsg,
                    !indexedDocument);
        }
        markBatchVectorIndexedInCrossIndex(batch, indexed);
        job.getChunksEmbedded().addAndGet(indexed);
        job.getDocumentsIndexed().addAndGet(indexed);
        job.getVectorBatchesCompleted().incrementAndGet();
        if (embeddingSizer != null) {
            long elapsedMs = (System.nanoTime() - batchStartNs) / 1_000_000L;
            memoryMonitor.updateMemorySnapshot(job);
            embeddingSizer.recordBatchResult(batch.size(), elapsedMs, true,
                    embeddingPressure(job));
            embeddingSizer.publishStats(job);
        }
        String indexedLabel = "Indexed vector batch " + batchNumber + "/" + totalBatches;
        pipelineStepTracker.incrementPipelineStep(job, "VECTOR_INDEXING", indexed, 1, indexedLabel);
        updateProgress(job, "INDEXING", indexedLabel, indexed + " chunk(s)");
        batch.clear();
    }

    String indexableDocumentText(Document document) {
        String text = document != null ? document.getText() : null;
        if (text != null && !text.isBlank()) {
            return text;
        }
        String id = document != null ? document.getId() : null;
        Map<String, Object> metadata = document != null ? document.getMetadata() : null;
        String source = documentTracker.stringMeta(metadata, GraphConstants.META_SOURCE_PATH, GraphConstants.META_SOURCE,
                "source_path", "sourcePath", "source", "path", "fileName", "file_name", "title");
        return "document " + graphPersistenceHelper.firstNonBlank(id, source, "chunk");
    }

    boolean isIndexableEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return false;
        }
        double magnitude = 0.0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                return false;
            }
            magnitude += (double) value * value;
        }
        return Double.isFinite(magnitude) && magnitude > 1e-18;
    }

    int resolveEmbeddingBatchSize(VectorIndexConfig config, UnifiedCrawlJob job) {
        int optimal = 32;
        int max = 128;
        EmbeddingModel embeddingModel = primaryEmbeddingModel();
        if (embeddingModel != null) {
            try {
                optimal = Math.max(1, embeddingModel.getOptimalBatchSize());
                max = Math.max(optimal, embeddingModel.getMaxBatchSize());
                job.getEmbeddingModelOptimalBatchSize().set(optimal);
                job.getEmbeddingModelMaxBatchSize().set(max);
                job.getEmbeddingSingleDspPlan().set(embeddingModel.isSingleDspPlan());
                job.getEmbeddingDspPlanBatchSize().set(embeddingModel.getDspPlanBatchSize());
            } catch (Exception e) {
                log.debug("Could not read embedding model batch sizes: {}", e.getMessage());
            }
        }

        if (configuredVectorBatchSize > 0) {
            optimal = configuredVectorBatchSize;
        }
        if (config.getEmbeddingBatchSize() != null && config.getEmbeddingBatchSize() > 0) {
            optimal = config.getEmbeddingBatchSize();
        }
        if (config.getMaxEmbeddingBatchSize() != null && config.getMaxEmbeddingBatchSize() > 0) {
            max = Math.min(max, config.getMaxEmbeddingBatchSize());
        }
        int effectiveMax = Math.max(1, max);
        int candidate = Math.max(1, Math.min(optimal, effectiveMax));
        if (candidate < MIN_EMBEDDING_BATCH_SIZE && effectiveMax >= MIN_EMBEDDING_BATCH_SIZE) {
            return MIN_EMBEDDING_BATCH_SIZE;
        }
        return candidate;
    }

    int adaptBatchSizeForMemory(int configuredBatchSize, UnifiedCrawlJob job) {
        memoryMonitor.updateMemorySnapshot(job);
        int usage = job.getMemoryUsagePercent().get();
        if (memoryCriticalThresholdPercent > 0 && usage >= memoryCriticalThresholdPercent) {
            return Math.max(1, Math.min(MIN_EMBEDDING_BATCH_SIZE, configuredBatchSize));
        }
        if (memoryWaitThresholdPercent > 0 && usage >= memoryWaitThresholdPercent) {
            return Math.max(1, Math.min(configuredBatchSize, Math.max(1, configuredBatchSize / 2)));
        }
        return configuredBatchSize;
    }

    EmbeddingModel primaryEmbeddingModel() {
        if (embeddingModels == null || embeddingModels.isEmpty()) {
            return null;
        }
        for (EmbeddingModel model : embeddingModels) {
            try {
                if (model != null && model.isInitialized()) {
                    return model;
                }
            } catch (Exception ignored) {
                // Try next model.
            }
        }
        return embeddingModels.get(0);
    }

    boolean isEmbeddingModelReady(EmbeddingModel embeddingModel) {
        if (embeddingModel == null) {
            return false;
        }
        List<float[]> readinessProbe = null;
        try {
            if (embeddingModel.isInitialized()) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Embedding readiness check failed: {}", e.getMessage());
        }

        try {
            int knownDimensions = embeddingModel.dimensions();
            if (knownDimensions > 0 && embeddingModel.isInitialized()) {
                return true;
            }
        } catch (Exception e) {
            log.debug("Embedding dimension readiness check failed: {}", e.getMessage());
        }

        try {
            log.info("Embedding model is not initialized; running readiness probe before vector indexing");
            readinessProbe = embeddingModel.embedBatch(List.of("kompile embedding readiness probe"));
            boolean ready = readinessProbe != null
                    && readinessProbe.size() == 1
                    && isIndexableEmbedding(readinessProbe.get(0));
            if (!ready) {
                log.warn("Embedding readiness probe returned non-indexable output");
                return false;
            }
            return embeddingModel.isInitialized() || embeddingModel.dimensions() > 0;
        } catch (Exception e) {
            log.warn("Embedding readiness probe failed: {}", e.getMessage());
            return false;
        } finally {
            if (readinessProbe != null) {
                try { readinessProbe.clear(); } catch (UnsupportedOperationException e) {
                    log.debug("Readiness probe collection does not support clear(): {}", e.getMessage());
                }
            }
        }
    }

    String embeddingModelNotReadyReason(EmbeddingModel embeddingModel) {
        if (embeddingModel == null) {
            return "No embedding model available";
        }

        String modelId = embeddingModel.getClass().getSimpleName();
        String phase = "unknown";
        String error = null;
        String message = null;
        try {
            String value = embeddingModel.getModelIdentifier();
            if (value != null && !value.isBlank()) {
                modelId = value;
            }
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            String value = embeddingModel.getLoadingPhase();
            if (value != null && !value.isBlank()) {
                phase = value;
            }
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            error = embeddingModel.getInitializationError();
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }
        try {
            message = embeddingModel.getLoadingMessage();
        } catch (Exception ignored) {
            // Best-effort diagnostics only.
        }

        StringBuilder reason = new StringBuilder();
        reason.append("Embedding model '").append(modelId)
                .append("' not initialized (phase: ").append(phase).append(")");
        if (error != null && !error.isBlank()) {
            reason.append(", error: ").append(error);
        } else if (message != null && !message.isBlank()) {
            reason.append(", message: ").append(message);
        }
        return reason.toString();
    }

    void deferVectorIndexing(UnifiedCrawlJob job,
                             List<Document> documents,
                             VectorIndexConfig config,
                             String reason) {
        if (job == null) {
            return;
        }
        List<Document> source = documents != null ? documents : List.of();
        int alreadyIndexed = Math.max(0, Math.min(job.getDocumentsIndexed().get(), source.size()));
        List<Document> remaining = new ArrayList<>(source.subList(alreadyIndexed, source.size()));
        job.getDeferredEmbeddingChunks().clear();
        job.getDeferredEmbeddingChunks().addAll(remaining);
        job.setDeferredVectorIndexConfig(config);
        pipelineStepTracker.updatePipelineStep(job, "VECTOR_INDEXING", UnifiedCrawlJob.PipelineStepStatus.DEFERRED,
                alreadyIndexed, source.size(), 0,
                job.getVectorBatchesCompleted().get(), job.getVectorBatchesTotal().get(),
                0, null,
                reason + "; " + remaining.size() + " chunk(s) queued for later embedding");
        documentTracker.recordEvent(job, "VECTOR_INDEXING", "WARN",
                "Vector indexing deferred",
                reason + "; indexed=" + alreadyIndexed + ", pending=" + remaining.size());
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }

    /**
     * Marks a successfully vector-indexed batch in the cross-index tracker.
     */
    private void markBatchVectorIndexedInCrossIndex(List<Document> batch, int indexed) {
        if (crawlIndexTrackingCallback == null || batch == null || indexed <= 0) return;
        try {
            List<String> chunkIds = new ArrayList<>(Math.min(indexed, batch.size()));
            for (int i = 0; i < Math.min(indexed, batch.size()); i++) {
                String id = batch.get(i).getId();
                if (id != null) chunkIds.add(id);
            }
            if (!chunkIds.isEmpty()) {
                crawlIndexTrackingCallback.markPassagesVectorIndexed(chunkIds);
            }
        } catch (Exception e) {
            log.debug("Failed to mark vector-indexed batch in cross-index: {}", e.getMessage());
        }
    }

    /**
     * Updates document-level vector index status after all batches complete.
     */
    private void markDocumentsVectorIndexedInCrossIndex(UnifiedCrawlJob job, List<Document> documents) {
        if (crawlIndexTrackingCallback == null || documents == null || documents.isEmpty()) return;
        Long factSheetId = jobFactSheetId(job);
        if (factSheetId == null) return;

        try {
            // Count indexed passages per source
            Map<String, Integer> countBySource = new LinkedHashMap<>();
            for (Document doc : documents) {
                Map<String, Object> meta = doc.getMetadata();
                if (meta == null) continue;
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE) : null;
                if (sourcePath != null) {
                    countBySource.merge(sourcePath, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> entry : countBySource.entrySet()) {
                crawlIndexTrackingCallback.markDocumentVectorIndexed(
                        entry.getKey(), factSheetId, entry.getValue());
            }
        } catch (Exception e) {
            log.debug("[Job {}] Failed to update document vector status in cross-index: {}",
                    job.getJobId(), e.getMessage());
        }
    }

    /**
     * Minimal updateProgress: updates job phase field and emits an event if message is non-blank.
     * The orchestrator retains the full throttled updateProgress with estimateProgress; this version
     * is intentionally lightweight for use inside the extracted helper.
     */
    private void updateProgress(UnifiedCrawlJob job, String phase, String message, String details) {
        job.getCurrentPhase().set(phase);
        memoryMonitor.updateMemorySnapshot(job);
        if (message != null && !message.isBlank()) {
            documentTracker.recordEvent(job, phase, "INFO", message, details);
        }
        pipelineStepTracker.updatePipelineStepFromCounters(job, phase, message, details);
    }

    /**
     * Wait for memory pressure to drop before proceeding with a phase.
     * Uses CrawlMemoryMonitor for memory state; omits config refresh (config is synced by the orchestrator).
     */
    private boolean waitForMemoryCapacity(UnifiedCrawlJob job, String phase) {
        memoryMonitor.updateMemorySnapshot(job);
        if (!memoryMonitor.hasMemoryPressure(job, false)) {
            return true;
        }

        String detail = memoryMonitor.memoryPressureDetail(job);
        job.getCurrentBatchStep().set("MEMORY_BACKPRESSURE");
        pipelineStepTracker.updatePipelineStepFromCounters(job, phase, "Memory backpressure", detail);
        documentTracker.recordEvent(job, phase, "WARN", "Memory pressure detected", detail);
        log.warn("[Job {}] Memory pressure before phase {}: {}", job.getJobId(), phase, detail);

        // One-time cleanup attempt: trim native memory and request GC once
        String trimDetail = memoryMonitor.trimNativeMemory(job, phase, "memory backpressure");
        if (trimDetail != null) {
            documentTracker.recordEvent(job, phase, "INFO", "Native memory cleanup", trimDetail);
            log.info("[Job {}] Native memory cleanup during {}: {}", job.getJobId(), phase, trimDetail);
        }
        System.gc();
        memoryMonitor.updateMemorySnapshot(job);

        if (!memoryMonitor.hasMemoryPressure(job, true)) {
            memoryMonitor.updateMemorySnapshot(job);
            return true;
        }

        // Spin-wait with increasing sleep intervals.
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
            memoryMonitor.updateMemorySnapshot(job);
            if (iteration > 0 && iteration % 5 == 0) {
                System.gc();
            }
            documentTracker.recordEvent(job, phase, "INFO",
                    "Waiting for memory pressure to drop",
                    memoryMonitor.memoryPressureDetail(job));
            if (!memoryMonitor.hasMemoryPressure(job, false)) {
                job.getCurrentBatchStep().set(null);
                return true;
            }
            iteration++;
        }
        documentTracker.recordEvent(job, phase, "WARN", "Continuing despite memory pressure",
                memoryMonitor.memoryPressureDetail(job) + " after waiting " + memoryWaitTimeoutSeconds + "s");
        return false;
    }

    /**
     * Trim native memory and record the result as an event.
     */
    private void trimNativeMemory(UnifiedCrawlJob job, String phase, String reason) {
        String detail = memoryMonitor.trimNativeMemory(job, phase, reason);
        if (detail != null) {
            documentTracker.recordEvent(job, phase, "INFO", "Native memory cleanup", detail);
            log.info("[Job {}] Native memory cleanup during {}: {}", job.getJobId(), phase, detail);
        }
    }
}
