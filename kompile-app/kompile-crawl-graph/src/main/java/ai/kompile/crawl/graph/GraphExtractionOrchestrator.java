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
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates LLM-based and GraphConstructor-based graph extraction from documents.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 *
 * <p>Handles the graph extraction phase of the crawl pipeline:
 * <ul>
 *   <li>Dispatches to {@link GraphConstructor} when available</li>
 *   <li>Falls back to inline LLM extraction when GraphConstructor is absent</li>
 *   <li>Manages cost-balanced batch planning, adaptive parallelism, and retry policies</li>
 *   <li>Records per-document and per-batch progress through the pipeline step tracker</li>
 * </ul>
 */
@Component
class GraphExtractionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionOrchestrator.class);

    private static final int DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE = 10;

    // Pre-allocated extractor lists — avoids per-call List.of() allocation in recordDocumentProgress
    private static final List<String> EXTRACTORS_GRAPH_CONSTRUCTOR = List.of("GraphConstructor");
    private static final List<String> EXTRACTORS_INLINE_LLM = List.of("InlineLLM");

    // Sentinel empty int[2] for getOrDefault — avoids allocation when key exists
    private static final int[] EMPTY_COUNTS = new int[2];

    /** Minimum interval between progress event recording (recordEvent + pipeline step update). */
    private static final long PROGRESS_EVENT_INTERVAL_NANOS = 250_000_000L; // 250ms
    private volatile long lastProgressEventNanos = 0L;

    // -------------------------------------------------------------------------
    // Configuration — synced from the parent orchestrator on each extraction call
    // -------------------------------------------------------------------------

    volatile int graphExtractionBatchSize = DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
    volatile int graphExtractionTargetCharsPerBatch = 48_000;
    volatile int graphExtractionParallelism = 16;
    volatile boolean costSortChunks = true;
    volatile boolean graphConstructorSkipEmbedding = true;
    volatile boolean graphConstructorPersistMatrixGraph = false;
    volatile boolean retainResultGraph = false;
    volatile int memoryCriticalThresholdPercent = 90;
    volatile int memoryWaitTimeoutSeconds = 300;

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Autowired(required = false)
    KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    GraphConstructor graphConstructor;

    @Autowired(required = false)
    EntityMentionRepository entityMentionRepository;

    @Autowired
    GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    CrawlDocumentTracker documentTracker;

    @Autowired
    CrawlLlmDispatcher llmDispatcher;

    @Autowired
    CrawlMemoryMonitor memoryMonitor;

    @Autowired
    PipelineStepTracker pipelineStepTracker;

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    /**
     * Dispatches graph extraction to either {@link GraphConstructor} or inline LLM extraction,
     * depending on availability.
     *
     * @param documents           documents to extract the graph from
     * @param config              extraction configuration (entity types, schema, prompt overrides)
     * @param targetGraph         in-memory graph to merge extracted entities/relationships into
     *                            (only populated when {@code retainResultGraph} is {@code true})
     * @param job                 the running crawl job
     * @param extractionPool      shared executor for concurrent batch extraction
     */
    void extractGraphFromDocuments(List<Document> documents,
                                   GraphExtractionConfig config,
                                   Graph targetGraph,
                                   UnifiedCrawlJob job,
                                   ExecutorService extractionPool) {
        // Configure the graph constructor if available
        if (graphConstructor != null) {
            graphConstructor.configure(new GraphConstructor.ExtractionModelConfig(
                    config.getLlmProvider(),
                    config.getModelName(),
                    config.getTemperature(),
                    config.getMaxTokens(),
                    config.getCustomPrompt()
            ));
        }

        // Delegate to GraphConstructor when available. Unified crawl owns scoped
        // fact-sheet graph persistence below, so by default the constructor only
        // extracts and returns semantic entities/relationships.
        if (graphConstructor != null) {
            extractGraphViaConstructor(documents, config, targetGraph, job, extractionPool);
        } else if (llmDispatcher != null) {
            extractGraphViaLlm(documents, config, targetGraph, job, extractionPool);
        } else {
            log.warn("No GraphConstructor or LLMChat available, skipping graph extraction");
        }
    }

    // -------------------------------------------------------------------------
    // GraphConstructor-based extraction
    // -------------------------------------------------------------------------

    /**
     * Delegates extraction to GraphConstructor which handles persistence and embedding.
     */
    private void extractGraphViaConstructor(List<Document> documents,
                                             GraphExtractionConfig config,
                                             Graph targetGraph,
                                             UnifiedCrawlJob job,
                                             ExecutorService extractExec) {
        GraphSchema schema = buildGraphSchema(config);
        SchemaEnforcementMode mode = config.getSchemaMode() != null
                ? config.getSchemaMode()
                : SchemaEnforcementMode.LENIENT;

        // Convert all documents → RetrievedDoc, then send all at once to constructGraphFromDocs.
        // The MatrixGraphConstructor handles parallelism internally (now 8 concurrent LLM threads).
        // BulkGraphSyncService handles JPA sync in <50ms even for 500+ entities, so batching for
        // DB performance is no longer needed.
        List<RetrievedDoc> allRetrievedDocs = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;
            allRetrievedDocs.add(toRetrievedDoc(doc));
        }

        if (allRetrievedDocs.isEmpty()) return;

        int batchSize = resolveGraphExtractionBatchSize(config);
        int totalDocs = allRetrievedDocs.size();
        List<CostBatch<RetrievedDoc>> batches = planCostBatches(
                allRetrievedDocs,
                doc -> estimateTextCost(doc.getText(), doc.getMetadata()),
                batchSize,
                Math.max(1, graphExtractionTargetCharsPerBatch),
                costSortChunks);
        allRetrievedDocs.clear();
        int totalBatches = batches.size();
        int parallelism = resolveGraphExtractionParallelism(job, totalBatches);
        OuterParallelismAdvisor outerAdvisor = new OuterParallelismAdvisor(parallelism);
        DynamicBatchSizer graphBatchSizer = DynamicBatchSizer.forGraphExtraction(batchSize);
        resetGraphExtractionProgress(job, totalDocs);
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, totalDocs, 0, 0, totalBatches, 0, null,
                "Planned graph extraction batches");
        job.getCurrentFile().set("(graph extraction: " + totalDocs
                + " chunks in " + totalBatches + " cost-balanced batch(es))");
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned graph extraction batches",
                "chunks=" + totalDocs + ", batches=" + totalBatches
                        + ", maxBatchSize=" + batchSize + ", targetChars=" + graphExtractionTargetCharsPerBatch
                        + ", parallelism=" + parallelism);
        log.info("[Job {}] Starting graph extraction for {} chunks in {} cost-balanced batch(es), parallelism={}, maxItems={}, targetChars={}",
                job.getJobId(), totalDocs, totalBatches, parallelism, batchSize,
                graphExtractionTargetCharsPerBatch);
        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION", "after planning graph batches");

        try {
            try {
                List<Future<GraphBatchResult>> futures = new ArrayList<>(batches.size());
                AtomicInteger completedBatches = new AtomicInteger(0);
                for (CostBatch<RetrievedDoc> batch : batches) {
                    if (isCancelled(job)) {
                        return;
                    }
                    // Block submission until the advisor's concurrency gate has a free permit.
                    // This enforces adaptive parallelism: when memory is critical, only 1
                    // batch runs; when memory is low, permits ramp back up.
                    try {
                        outerAdvisor.acquirePermit();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    futures.add(extractExec.submit(() -> {
                        long batchStartTime = System.currentTimeMillis();
                        UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                        step.getActiveTasks().incrementAndGet();
                        step.setLastUpdatedAt(Instant.now());
                        try {
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled before batch start");
                                return new GraphBatchResult(batch, null, null);
                            }
                            memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled while waiting for memory");
                                return new GraphBatchResult(batch, null, null);
                            }
                            job.getCurrentBatchSize().set(batch.items().size());
                            String batchLabel = batch.index() + "/" + totalBatches;
                            job.getCurrentBatchStep().set("GRAPH_BATCH " + batchLabel);
                            for (RetrievedDoc item : batch.items()) {
                                documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                                        "LLM graph batch " + batchLabel,
                                        null, EXTRACTORS_GRAPH_CONSTRUCTOR, false);
                            }
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Extracting graph batch " + batchLabel,
                                    batch.items().size() + " chunk(s), cost=" + batch.cost());
                            Map<String, RetrievedDoc> batchDocsById = new HashMap<>(batch.items().size());
                            for (RetrievedDoc item : batch.items()) {
                                if (item.getId() != null) batchDocsById.putIfAbsent(item.getId(), item);
                            }
                            Set<String> terminalDocIds = ConcurrentHashMap.newKeySet();
                            GraphConstructor.ProgressListener progressListener = progress ->
                                    recordGraphConstructorProgress(job, batch, batchDocsById, totalBatches,
                                            progress, terminalDocIds);
                            Graph graph = graphConstructor.constructGraphFromDocs(batch.items(), schema, mode,
                                    graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph,
                                    progressListener);

                            if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                recordCancelledGraphBatch(job, batch, "Graph extraction cancelled; discarding batch result");
                                return new GraphBatchResult(batch, null, null);
                            }

                            if (graph != null) {
                                GraphPersistenceHelper.GraphPersistResult persisted = graphPersistenceHelper.persistConstructedGraphBatch(job, graph, batch.items(), config);
                                if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                    recordCancelledGraphBatch(job, batch, "Graph extraction cancelled after graph persistence");
                                    releaseInMemoryGraph(graph);
                                    return new GraphBatchResult(batch, null, null);
                                }
                                if (terminalDocIds.isEmpty()) {
                                    recordGraphExtractionDiagnostics(job, graph, batch, totalBatches);
                                    recordGraphExtractionBatchPerDocument(job, graph, batch, totalBatches);
                                }
                                if (retainResultGraph) {
                                    synchronized (targetGraph) {
                                        mergeGraphInto(graph, targetGraph, config);
                                    }
                                }
                                int entities = graph.getEntities() != null ? graph.getEntities().size() : 0;
                                int rels = graph.getRelationships() != null ? graph.getRelationships().size() : 0;
                                if (terminalDocIds.isEmpty()) {
                                    job.getEntitiesExtracted().addAndGet(entities);
                                    job.getRelationshipsExtracted().addAndGet(rels);
                                }
                                log.info("[Job {}] Graph extraction batch {} complete: {} entities, {} rels (totals: {}/{})",
                                        job.getJobId(), batchLabel, entities, rels,
                                        job.getEntitiesExtracted().get(), job.getRelationshipsExtracted().get());
                                if (persisted.entities() > 0 || persisted.relationships() > 0) {
                                    documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                            "Persisted semantic graph batch " + batchLabel,
                                            persisted.entities() + " entities, " + persisted.relationships()
                                                    + " relationships written to fact-sheet graph");
                                }
                            }

                            int missingTerminalUpdates = Math.max(0, batch.items().size() - terminalDocIds.size());
                            if (missingTerminalUpdates > 0) {
                                incrementGraphChunksProcessed(job, missingTerminalUpdates);
                            }
                            int processed = normalizeGraphChunksProcessed(job);
                            int done = completedBatches.incrementAndGet();
                            UnifiedCrawlJob.PipelineStepProgress graphStepForUpdate = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                            pipelineStepTracker.applyPipelineStepUpdate(graphStepForUpdate, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                    processed, totalDocs, graphStepForUpdate.getFailedItems().get(),
                                    done, totalBatches, 0, null,
                                    "Completed graph batch " + batchLabel);
                            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                                    "Completed graph batch " + batchLabel,
                                    processed + "/" + totalDocs + " chunk(s)");
                            // Notify outer advisor of batch completion for adaptive parallelism
                            memoryMonitor.updateMemorySnapshot(job);
                            double heapPct = job.getMemoryUsagePercent().get() / 100.0;
                            int heapPctInt = job.getMemoryUsagePercent().get();
                            long batchElapsed = System.currentTimeMillis() - batchStartTime;
                            outerAdvisor.afterBatchComplete(batchElapsed, heapPct);
                            // Record success for AIMD graph batch sizer
                            graphBatchSizer.recordBatchResult(batch.items().size(), batchElapsed, true, heapPctInt);
                            graphBatchSizer.publishStats(job);
                            job.getCurrentBatchStep().set("GRAPH_BATCHES " + done + "/" + totalBatches
                                    + " parallelism=" + outerAdvisor.getCurrentParallelism());
                            documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                                    "Completed graph batch " + batch.index() + "/" + totalBatches,
                                    processed + "/" + totalDocs + " chunk(s)");
                            if (graph != null) {
                                releaseInMemoryGraph(graph);
                            }
                            batchDocsById.clear();
                            terminalDocIds.clear();
                            batch.items().clear();

                            return new GraphBatchResult(batch, null, null);
                        } finally {
                            memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                    "after graph batch " + batch.index() + "/" + totalBatches);
                            step.getActiveTasks().updateAndGet(v -> Math.max(0, v - 1));
                            step.setLastUpdatedAt(Instant.now());
                            outerAdvisor.releasePermit();
                        }
                    }));
                }

                // Retry policy for graph extraction batches
                BatchRetryPolicy<RetrievedDoc> graphRetryPolicy = BatchRetryPolicy.forGraphExtraction();

                for (int i = 0; i < futures.size(); i++) {
                    Future<GraphBatchResult> extractFuture = futures.get(i);
                    CostBatch<RetrievedDoc> plannedBatch = batches.get(i);
                    if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction cancelled");
                        break;
                    }
                    try {
                        extractFuture.get(2700, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        extractFuture.cancel(true);
                        log.warn("[Job {}] Graph extraction batch {}/{} timed out after 45min",
                                job.getJobId(), plannedBatch.index(), totalBatches);
                        job.getErrors().add("Graph extraction batch " + plannedBatch.index() + "/" + totalBatches
                                + " timed out after 45min");
                        job.getErrorCount().incrementAndGet();
                        for (RetrievedDoc item : plannedBatch.items()) {
                            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                    "Graph extraction batch timed out", "Timed out after 45min",
                                    EXTRACTORS_GRAPH_CONSTRUCTOR, true);
                        }
                        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                "Graph extraction batch timed out",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        continue;
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        String errorDetail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                        log.warn("[Job {}] Graph extraction batch {}/{} failed: {}",
                                job.getJobId(), plannedBatch.index(), totalBatches, errorDetail);
                        // Record failure for AIMD graph batch sizer
                        memoryMonitor.updateMemorySnapshot(job);
                        graphBatchSizer.recordBatchResult(plannedBatch.items().size(), 0, false,
                                job.getMemoryUsagePercent().get());
                        graphBatchSizer.publishStats(job);
                        // Evaluate retry policy
                        BatchRetryPolicy.RetryDecision<RetrievedDoc> retryDecision = graphRetryPolicy.evaluateFailure(
                                plannedBatch.items(), plannedBatch.items().size(), errorDetail, null, null, job);
                        switch (retryDecision.getAction()) {
                            case RETRY_SAME_BACKEND: {
                                log.info("[Job {}] Retrying graph extraction batch {}/{} (attempt {}), backoff={}ms",
                                        job.getJobId(), plannedBatch.index(), totalBatches,
                                        retryDecision.getAttempt(), retryDecision.getBackoffMs());
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN",
                                        "Retrying graph extraction batch " + plannedBatch.index() + "/" + totalBatches,
                                        "attempt=" + retryDecision.getAttempt() + ", backoff=" + retryDecision.getBackoffMs() + "ms"
                                                + ", reason=" + retryDecision.getReason());
                                try { Thread.sleep(retryDecision.getBackoffMs()); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    cancelGraphFutures(futures);
                                    recordCancelledGraphBatch(job, plannedBatch, "Graph extraction interrupted during retry backoff");
                                    return;
                                }
                                // Resubmit the failed batch to the executor
                                try {
                                    outerAdvisor.acquirePermit();
                                } catch (InterruptedException ie2) {
                                    Thread.currentThread().interrupt();
                                    cancelGraphFutures(futures);
                                    return;
                                }
                                final CostBatch<RetrievedDoc> retryBatch = plannedBatch;
                                futures.set(i, extractExec.submit(() -> {
                                    long batchStartTime = System.currentTimeMillis();
                                    UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                                    step.getActiveTasks().incrementAndGet();
                                    step.setLastUpdatedAt(Instant.now());
                                    try {
                                        if (isCancelled(job) || Thread.currentThread().isInterrupted()) {
                                            recordCancelledGraphBatch(job, retryBatch, "Graph extraction retry cancelled");
                                            return new GraphBatchResult(retryBatch, null, null);
                                        }
                                        memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                                        job.getCurrentBatchSize().set(retryBatch.items().size());
                                        job.getCurrentBatchStep().set("GRAPH_BATCH " + retryBatch.index() + "/" + totalBatches + " (retry)");
                                        Graph graph = graphConstructor.constructGraphFromDocs(retryBatch.items(), schema, mode,
                                                graphConstructorSkipEmbedding, !graphConstructorPersistMatrixGraph, null);
                                        if (graph != null) {
                                            GraphPersistenceHelper.GraphPersistResult persisted = graphPersistenceHelper.persistConstructedGraphBatch(job, graph, retryBatch.items(), config);
                                            if (retainResultGraph) {
                                                synchronized (targetGraph) {
                                                    mergeGraphInto(graph, targetGraph, config);
                                                }
                                            }
                                            int entities = graph.getEntities() != null ? graph.getEntities().size() : 0;
                                            int rels = graph.getRelationships() != null ? graph.getRelationships().size() : 0;
                                            job.getEntitiesExtracted().addAndGet(entities);
                                            job.getRelationshipsExtracted().addAndGet(rels);
                                            releaseInMemoryGraph(graph);
                                        }
                                        int done2 = completedBatches.incrementAndGet();
                                        incrementGraphChunksProcessed(job, retryBatch.items().size());
                                        long batchElapsed = System.currentTimeMillis() - batchStartTime;
                                        memoryMonitor.updateMemorySnapshot(job);
                                        graphBatchSizer.recordBatchResult(retryBatch.items().size(), batchElapsed, true,
                                                job.getMemoryUsagePercent().get());
                                        graphBatchSizer.publishStats(job);
                                        return new GraphBatchResult(retryBatch, null, null);
                                    } finally {
                                        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                                "after graph batch retry " + retryBatch.index() + "/" + totalBatches);
                                        step.getActiveTasks().updateAndGet(v -> Math.max(0, v - 1));
                                        step.setLastUpdatedAt(Instant.now());
                                        outerAdvisor.releasePermit();
                                    }
                                }));
                                // Re-process this index to collect the retry future result
                                i--;
                                continue;
                            }
                            case DEAD_LETTER: {
                                log.warn("[Job {}] Graph extraction batch {}/{} exhausted retries, {} items dead-lettered",
                                        job.getJobId(), plannedBatch.index(), totalBatches, plannedBatch.items().size());
                                job.getErrors().add("Graph extraction batch " + plannedBatch.index() + "/" + totalBatches
                                        + " exhausted retries: " + errorDetail);
                                job.getErrorCount().incrementAndGet();
                                for (RetrievedDoc item : plannedBatch.items()) {
                                    documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                            "Graph extraction exhausted retries", errorDetail,
                                            EXTRACTORS_GRAPH_CONSTRUCTOR, true);
                                }
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                        "Graph extraction batch dead-lettered",
                                        plannedBatch.items().size() + " items after " + retryDecision.getAttempt() + " attempts");
                                int processed = incrementGraphChunksProcessed(job, plannedBatch.items().size());
                                int done = completedBatches.incrementAndGet();
                                UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");
                                int failed = step.getFailedItems().incrementAndGet();
                                pipelineStepTracker.applyPipelineStepUpdate(step, UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                                        processed, totalDocs, failed,
                                        done, totalBatches, 0, null,
                                        "Dead-lettered graph batch " + plannedBatch.index() + "/" + totalBatches);
                                graphRetryPolicy.resetAttempts();
                                plannedBatch.items().clear();
                                continue;
                            }
                            case ABORT:
                            default: {
                                // Fatal — same as before: cancel all futures and throw
                                cancelGraphFutures(futures);
                                String fatalMessage = "Fatal graph extraction failure: " + errorDetail;
                                job.getErrors().add(fatalMessage);
                                job.getErrorCount().incrementAndGet();
                                pipelineStepTracker.failPipelineStep(job, "GRAPH_EXTRACTION", fatalMessage);
                                documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                                        "Fatal graph extraction failure",
                                        fatalMessage);
                                throw new IllegalStateException(fatalMessage, cause);
                            }
                        }
                    } catch (CancellationException ce) {
                        if (isCancelled(job)) {
                            recordCancelledGraphBatch(job, plannedBatch, "Graph extraction batch cancelled");
                            break;
                        }
                        throw ce;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cancelGraphFutures(futures);
                        recordCancelledGraphBatch(job, plannedBatch, "Graph extraction interrupted");
                        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "WARN", "Graph extraction interrupted",
                                "batch=" + plannedBatch.index() + "/" + totalBatches);
                        return;
                    }
                }
            } finally {
                // Don't shutdown — shared pool is reused across jobs
                job.getCurrentBatchSize().set(0);
                job.getCurrentBatchStep().set(null);
            }
        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.error("[Job {}] Graph extraction failed: {}", job.getJobId(), errorDetail, e);
            job.getErrors().add("Graph extraction failed: " + errorDetail);
            job.getErrorCount().incrementAndGet();
            if (isFatalLlmUnavailable(e)) {
                throw new RuntimeException("Graph extraction failed: " + errorDetail, e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inline LLM-based extraction (fallback when no GraphConstructor)
    // -------------------------------------------------------------------------

    /**
     * Fallback: inline LLM extraction when no GraphConstructor is available.
     * Entities are persisted to KnowledgeGraphService and also stored in-memory on the job's result graph.
     */
    private void extractGraphViaLlm(List<Document> documents,
                                     GraphExtractionConfig config,
                                     Graph targetGraph,
                                     UnifiedCrawlJob job,
                                     ExecutorService llmExec) {
        String extractionPrompt = buildExtractionPrompt(config);
        resetGraphExtractionProgress(job, documents.size());

        List<CostBatch<Document>> batches = planCostBatches(
                documents,
                this::estimateDocumentCost,
                resolveGraphExtractionBatchSize(config),
                Math.max(1, graphExtractionTargetCharsPerBatch),
                costSortChunks);
        int parallelism = resolveGraphExtractionParallelism(job, batches.size());
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                0, documents.size(), 0, 0, batches.size(), 0, null,
                "Planned inline LLM extraction batches");
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "INFO",
                "Planned inline LLM extraction batches",
                "chunks=" + documents.size() + ", batches=" + batches.size()
                        + ", parallelism=" + parallelism + ", targetChars=" + graphExtractionTargetCharsPerBatch);

        // Shared cross-chunk caches to eliminate N+1 DB queries:
        // - parentDocCache: same sourcePath repeated across chunks from same document
        // - entityNodeCache: same entity IDs may appear across chunks
        ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache = new ConcurrentHashMap<>();
        Long factSheetId = jobFactSheetId(job);

        if (parallelism <= 1 || batches.size() <= 1) {
            for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                if (isCancelled(job)) return;
                extractGraphViaLlmDocument(documents.get(docIndex), docIndex, documents.size(),
                        extractionPrompt, config, targetGraph, job,
                        parentDocCache, entityNodeCache, factSheetId);
                memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                        "after inline graph chunk " + (docIndex + 1) + "/" + documents.size());
            }
            job.getCurrentBatchStep().set(null);
            return;
        }

        // Use shared graph extraction pool — avoids per-job thread creation/teardown overhead
        try {
            List<Future<?>> futures = new ArrayList<>(batches.size());
            AtomicInteger offset = new AtomicInteger(0);
            for (CostBatch<Document> batch : batches) {
                int baseIndex = offset.getAndAdd(batch.items().size());
                futures.add(llmExec.submit(() -> {
                    memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
                    job.getCurrentBatchSize().set(batch.items().size());
                    job.getCurrentBatchStep().set("LLM_BATCH " + batch.index() + "/" + batches.size());
                    for (int i = 0; i < batch.items().size(); i++) {
                        if (isCancelled(job)) break;
                        extractGraphViaLlmDocument(batch.items().get(i), baseIndex + i, documents.size(),
                                extractionPrompt, config, targetGraph, job,
                                parentDocCache, entityNodeCache, factSheetId);
                        memoryMonitor.trimNativeMemory(job, "GRAPH_EXTRACTION",
                                "after inline graph chunk " + (baseIndex + i + 1) + "/" + documents.size());
                    }
                    updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size(),
                            batch.items().size() + " chunk(s), cost=" + batch.cost());
                    pipelineStepTracker.incrementPipelineStep(job, "GRAPH_EXTRACTION", 0, 1,
                            "Completed inline LLM batch " + batch.index() + "/" + batches.size());
                }));
            }
            for (Future<?> future : futures) {
                if (isCancelled(job)) break;
                try {
                    future.get(2700, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(false);
                    job.getErrors().add("Inline LLM extraction batch timed out after 45min");
                    job.getErrorCount().incrementAndGet();
                    documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR",
                            "Inline LLM extraction batch timed out", null);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.warn("[Job {}] Inline LLM extraction batch failed: {}",
                            job.getJobId(), cause.getMessage());
                    job.getErrors().add("Inline LLM extraction batch failed: " + cause.getMessage());
                    job.getErrorCount().incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Don't shutdown — shared pool is reused across jobs
            job.getCurrentBatchSize().set(0);
            job.getCurrentBatchStep().set(null);
        }
    }

    private void extractGraphViaLlmDocument(Document doc,
                                            int docIndex,
                                            int totalDocuments,
                                            String extractionPrompt,
                                            GraphExtractionConfig config,
                                            Graph targetGraph,
                                            UnifiedCrawlJob job,
                                            ConcurrentHashMap<String, Optional<GraphNode>> parentDocCache,
                                            ConcurrentHashMap<String, Optional<GraphNode>> entityNodeCache,
                                            Long factSheetId) {
        String jobId = job.getJobId();
        if (isCancelled(job)) return;
        memoryMonitor.waitForMemoryCapacity(job, "GRAPH_EXTRACTION");
        job.getCurrentBatchStep().set("GRAPH_CHUNK " + (docIndex + 1) + "/" + totalDocuments);
        updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                "Extracting graph from chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                "Inline LLM graph extraction " + (docIndex + 1) + "/" + totalDocuments,
                null, EXTRACTORS_INLINE_LLM, false);

        boolean recordedResult = false;
        try {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "SKIPPED", 0, 0, 0,
                        "Skipped blank chunk", null, EXTRACTORS_INLINE_LLM, false);
                return;
            }

            // VLM-extracted documents may contain valuable structural markup;
            // allow more text through for better entity/relationship extraction.
            boolean isVlmContent = doc.getMetadata() != null
                    && Boolean.TRUE.equals(doc.getMetadata().get(GraphConstants.META_VLM_PROCESSED));
            int maxLength = isVlmContent ? 16000 : 12000;

            // Truncate very long documents to avoid LLM context limits
            if (text.length() > maxLength) {
                text = text.substring(0, maxLength);
            }

            // Add VLM context hint when applicable
            String vlmHint = "";
            if (isVlmContent) {
                String vlmModel = doc.getMetadata().get("vlm_model") instanceof String
                        ? (String) doc.getMetadata().get("vlm_model") : "unknown";
                vlmHint = "\n\nNote: This text was extracted from a PDF using a Visual Language Model (" +
                        vlmModel + "). It may contain document structure markup (headings, tables, " +
                        "form fields, references). Extract entities and relationships from both the " +
                        "prose content and the structured elements (table rows, form field key-value " +
                        "pairs, section headings).\n";
            }

            String fullPrompt = extractionPrompt + vlmHint + "\n\nText to analyze:\n" + text;
            String response = llmDispatcher.promptWithCapacityFallback(fullPrompt, "llm", job);

            if (response != null && !response.isBlank()) {
                String json = extractJsonFromResponse(response);
                if (json != null) {
                    GraphExtractionSchema.ExtractionResult result = GraphExtractionValidator.fromJson(json);
                    var validation = GraphExtractionValidator.validate(result);
                    if (validation.valid()) {
                        Graph chunkGraph = GraphExtractionValidator.toGraph(result);
                        if (retainResultGraph) {
                            synchronized (targetGraph) {
                                mergeGraphInto(chunkGraph, targetGraph, config);
                            }
                        }
                        job.getEntitiesExtracted().addAndGet(
                                chunkGraph.getEntities() != null ? chunkGraph.getEntities().size() : 0);
                        job.getRelationshipsExtracted().addAndGet(
                                chunkGraph.getRelationships() != null ? chunkGraph.getRelationships().size() : 0);
                        int entityCount = chunkGraph.getEntities() != null ? chunkGraph.getEntities().size() : 0;
                        int relationshipCount = chunkGraph.getRelationships() != null ? chunkGraph.getRelationships().size() : 0;
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "COMPLETED", 0,
                                entityCount, relationshipCount,
                                "Inline LLM graph extraction complete", null, EXTRACTORS_INLINE_LLM, true);
                        recordedResult = true;

                        // Persist LLM-extracted entities and relations to KnowledgeGraphService
                        if (knowledgeGraphService != null && result.entities() != null && !result.entities().isEmpty()) {
                            Map<String, Object> docMeta = doc.getMetadata();
                            String sourcePath = docMeta != null && docMeta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                                    ? (String) docMeta.get(GraphConstants.META_SOURCE_PATH)
                                    : docMeta != null && docMeta.get(GraphConstants.META_SOURCE) instanceof String
                                    ? (String) docMeta.get(GraphConstants.META_SOURCE) : null;

                            GraphNode parentDocumentNode = null;
                            String parentNodeId = null;
                            if (sourcePath != null) {
                                final Long fsId = factSheetId;
                                Optional<GraphNode> docNode = parentDocCache.computeIfAbsent(
                                        sourcePath, sp -> knowledgeGraphService.getNodeByExternalId(
                                                sp, NodeLevel.DOCUMENT, fsId));
                                if (docNode.isPresent()) {
                                    parentDocumentNode = docNode.get();
                                    parentNodeId = parentDocumentNode.getNodeId();
                                    if (factSheetId == null) {
                                        factSheetId = parentDocumentNode.getFactSheetId();
                                    }
                                }
                            }

                            Map<String, String> externalToNodeId = new HashMap<>();
                            String inlineContainsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);
                            for (var entity : result.entities()) {
                                try {
                                    String entityType = graphPersistenceHelper.safeEntityType(entity.type());
                                    Map<String, Object> entityMeta = new LinkedHashMap<>();
                                    entityMeta.put("entity_type", entity.type());
                                    entityMeta.put(GraphConstants.META_SOURCE, jobId);
                                    entityMeta.put("extraction_method", "llm");
                                    if (sourcePath != null) entityMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                                    if (entity.properties() != null) entityMeta.putAll(entity.properties());

                                    final Long entityFsId = factSheetId;
                                    Optional<GraphNode> existing = entityNodeCache.computeIfAbsent(
                                            entity.id(), eid -> knowledgeGraphService.getNodeByExternalId(
                                                    eid, NodeLevel.ENTITY, entityFsId));
                                    GraphNode node;
                                    if (existing.isPresent()) {
                                        node = existing.get();
                                    } else {
                                        node = knowledgeGraphService.createNode(NodeLevel.ENTITY, entity.id(),
                                                entity.name(), entity.description(), entityMeta, factSheetId);
                                        // Cache the newly created node
                                        entityNodeCache.put(entity.id(), Optional.of(node));
                                    }
                                    externalToNodeId.put(entity.id(), node.getNodeId());
                                    job.incrementEntityType(entityType);

                                    if (parentNodeId != null) {
                                        graphPersistenceHelper.recordEntityMention(parentDocumentNode,
                                                entity.name(),
                                                entityType,
                                                entity.confidence(),
                                                factSheetId,
                                                "inline_llm",
                                                sourcePath,
                                                null);
                                        String description = graphPersistenceHelper.semanticRelationDescription(
                                                "Document contains " + entityType + " " + entity.name(), inlineContainsLabel);
                                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", sourcePath, entity.id(), inlineContainsLabel, description, null,
                                                graphPersistenceHelper.metadataProperties(
                                                        "entityType", entity.type(),
                                                        "entityName", entity.name()));
                                        knowledgeGraphService.createEdgeWithMetadata(parentNodeId, node.getNodeId(),
                                                EdgeType.CONTAINS, 1.0, inlineContainsLabel, description, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId);
                                    }
                                } catch (Exception e) {
                                    log.debug("[Job {}] Failed to persist LLM entity '{}': {}", jobId, entity.name(), e.getMessage());
                                }
                            }

                            if (result.relations() != null) {
                                for (var rel : result.relations()) {
                                    try {
                                        String srcNodeId = externalToNodeId.get(rel.source());
                                        String tgtNodeId = externalToNodeId.get(rel.target());
                                        if (srcNodeId == null || tgtNodeId == null) continue;
                                        String label = graphPersistenceHelper.semanticRelationLabel(rel.type());
                                        String description = graphPersistenceHelper.semanticRelationDescription(rel.description(), label);
                                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                                "inline_llm", rel.source(), rel.target(), label, description,
                                                rel.confidence(), rel.properties());
                                        knowledgeGraphService.createEdgeWithMetadata(srcNodeId, tgtNodeId,
                                                EdgeType.USER_DEFINED,
                                                rel.confidence() != null ? rel.confidence() : 1.0,
                                                label, description, metaJson,
                                                EdgeProvenance.EXTRACTED, factSheetId);
                                        job.incrementRelationshipType(label);
                                    } catch (Exception e) {
                                        log.debug("[Job {}] Failed to persist LLM relation '{}': {}", jobId, rel.type(), e.getMessage());
                                    }
                                }
                            }
                        }
                    } else {
                        log.debug("Graph extraction validation failed: {}", validation.errors());
                        documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                                "Graph extraction validation failed", String.join("; ", validation.errors()),
                                EXTRACTORS_INLINE_LLM, true);
                        recordedResult = true;
                    }
                }
            }
            if (!recordedResult) {
                // LLM returned null or empty — this is a failure, not a quiet success
                job.getErrorCount().incrementAndGet();
                documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                        "LLM graph extraction returned no result",
                        "LLM returned null/empty — check LLM configuration",
                        EXTRACTORS_INLINE_LLM, true);
            }

        } catch (Exception e) {
            String errorDetail = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName() + " at " + (e.getStackTrace().length > 0 ? e.getStackTrace()[0] : "unknown");
            log.warn("[Job {}] Graph extraction failed for document: {}", job.getJobId(), errorDetail, e);
            job.getErrors().add("Graph extraction failed: " + errorDetail);
            job.getErrorCount().incrementAndGet();
            documentTracker.recordDocumentProgress(job, doc, "GRAPH_EXTRACTION", "FAILED", 0, 0, 0,
                    "Graph extraction failed", errorDetail, EXTRACTORS_INLINE_LLM, true);
        } finally {
            incrementGraphChunksProcessed(job, 1);
            updateProgress(job, "GRAPH_EXTRACTION", estimateProgress(job),
                    "Completed graph chunk " + (docIndex + 1) + "/" + totalDocuments, null);
        }
    }

    // -------------------------------------------------------------------------
    // Graph chunk progress tracking
    // -------------------------------------------------------------------------

    int resetGraphExtractionProgress(UnifiedCrawlJob job, int totalChunks) {
        int total = Math.max(0, totalChunks);
        job.getGraphChunksTotal().set(total);
        job.getGraphChunksProcessed().set(0);
        return total;
    }

    int incrementGraphChunksProcessed(UnifiedCrawlJob job, int delta) {
        if (job == null || delta <= 0) {
            return normalizeGraphChunksProcessed(job);
        }
        int total = graphChunksTotal(job);
        return job.getGraphChunksProcessed().updateAndGet(current -> {
            int next = Math.max(0, current) + delta;
            return total > 0 ? Math.min(total, next) : next;
        });
    }

    int normalizeGraphChunksProcessed(UnifiedCrawlJob job) {
        if (job == null) {
            return 0;
        }
        int total = graphChunksTotal(job);
        return job.getGraphChunksProcessed().updateAndGet(current -> {
            int normalized = Math.max(0, current);
            return total > 0 ? Math.min(total, normalized) : normalized;
        });
    }

    int completeGraphExtractionProgress(UnifiedCrawlJob job) {
        if (job == null) {
            return 0;
        }
        int total = graphChunksTotal(job);
        if (total <= 0) {
            return normalizeGraphChunksProcessed(job);
        }
        job.getGraphChunksProcessed().set(total);
        return total;
    }

    int graphChunksTotal(UnifiedCrawlJob job) {
        return job != null ? Math.max(0, job.getGraphChunksTotal().get()) : 0;
    }

    // -------------------------------------------------------------------------
    // Progress reporting helpers
    // -------------------------------------------------------------------------

    private void recordGraphConstructorProgress(UnifiedCrawlJob job,
                                                CostBatch<RetrievedDoc> batch,
                                                Map<String, RetrievedDoc> batchDocsById,
                                                int totalBatches,
                                                GraphConstructor.DocumentExtractionProgress progress,
                                                Set<String> terminalDocIds) {
        if (job == null || progress == null) {
            return;
        }
        if (isCancelled(job)) {
            return;
        }
        RetrievedDoc item = progress.documentId() != null && batchDocsById != null
                ? batchDocsById.get(progress.documentId())
                : null;
        if (item == null && batch != null && batch.items() != null
                && progress.documentIndex() >= 0 && progress.documentIndex() < batch.items().size()) {
            item = batch.items().get(progress.documentIndex());
        }
        String docKey = item != null ? documentTracker.documentKey(item) : progress.documentId();
        if (docKey == null || docKey.isBlank()) {
            docKey = "graph-doc-" + Math.max(0, progress.documentIndex());
        }
        String fileName = item != null
                ? documentTracker.documentFileName(item.getMetadata(), item.getId())
                : docKey;
        int ordinal = Math.max(0, progress.documentIndex()) + 1;
        int total = Math.max(1, progress.totalDocuments());
        int batchIndex = batch != null ? batch.index() : 0;
        String batchLabel = batchIndex > 0
                ? "batch " + batchIndex + "/" + totalBatches
                : "graph extraction";

        job.getCurrentPhase().set("GRAPH_EXTRACTION");
        job.getCurrentFile().set(fileName);
        job.getProgressPercent().accumulateAndGet(estimateProgressForPhase(job, "GRAPH_EXTRACTION"), Math::max);
        memoryMonitor.updateMemorySnapshot(job);
        UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, "GRAPH_EXTRACTION");

        if (progress.status() == GraphConstructor.DocumentExtractionStatus.STARTED) {
            String message = "LLM graph extraction " + ordinal + "/" + total + " started (" + batchLabel + ")";
            job.getCurrentBatchStep().set("GRAPH_CHUNK " + ordinal + "/" + total + " (" + batchLabel + ")");
            pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                    normalizeGraphChunksProcessed(job), graphChunksTotal(job),
                    step.getFailedItems().get(), step.getCompletedBatches().get(), step.getTotalBatches().get(),
                    1, fileName, message);
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "RUNNING", 0, 0, 0,
                    message, null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
            return;
        }

        boolean failed = progress.status() == GraphConstructor.DocumentExtractionStatus.FAILED
                || progress.status() == GraphConstructor.DocumentExtractionStatus.TIMED_OUT;
        String terminalKey = progress.documentId() != null ? progress.documentId() : docKey;
        if (terminalDocIds != null && !terminalDocIds.add(terminalKey)) {
            return;
        }

        int processed = incrementGraphChunksProcessed(job, 1);
        if (failed) {
            job.getErrorCount().incrementAndGet();
            String error = progress.errorMessage() != null && !progress.errorMessage().isBlank()
                    ? progress.errorMessage()
                    : progress.status().name();
            job.getErrors().add(fileName + ": " + error);
        } else {
            job.getEntitiesExtracted().addAndGet(Math.max(0, progress.entities()));
            job.getRelationshipsExtracted().addAndGet(Math.max(0, progress.relationships()));
        }

        job.getProgressPercent().accumulateAndGet(estimateProgressForPhase(job, "GRAPH_EXTRACTION"), Math::max);
        String message = failed
                ? "LLM graph extraction failed " + ordinal + "/" + total + " (" + batchLabel + ")"
                : "LLM graph extraction complete " + ordinal + "/" + total + " (" + batchLabel + ")";
        String details = failed
                ? progress.errorMessage()
                : progress.entities() + " entities, " + progress.relationships() + " relationships"
                + ", chars=" + progress.textLength() + ", took=" + progress.elapsedMs() + "ms";
        pipelineStepTracker.updatePipelineStep(job, "GRAPH_EXTRACTION", UnifiedCrawlJob.PipelineStepStatus.RUNNING,
                processed, graphChunksTotal(job),
                step.getFailedItems().get() + (failed ? 1 : 0),
                step.getCompletedBatches().get(), step.getTotalBatches().get(),
                1, fileName, message);
        documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", failed ? "FAILED" : "COMPLETED", 0,
                failed ? 0 : Math.max(0, progress.entities()),
                failed ? 0 : Math.max(0, progress.relationships()),
                message, failed ? details : null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", failed ? "ERROR" : "INFO",
                fileName + ": " + message, details);
    }

    private void recordCancelledGraphBatch(UnifiedCrawlJob job,
                                           CostBatch<RetrievedDoc> batch,
                                           String message) {
        if (batch == null || batch.items() == null) {
            return;
        }
        for (RetrievedDoc item : batch.items()) {
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "CANCELLED", 0, 0, 0,
                    message, null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        }
    }

    private void recordGraphExtractionDiagnostics(UnifiedCrawlJob job,
                                                   Graph graph,
                                                   CostBatch<?> batch,
                                                   int totalBatches) {
        if (graph.getMetadata() == null || graph.getMetadata().isEmpty()) {
            return;
        }
        Object failedDocsValue = graph.getMetadata().get("extractionFailedDocs");
        int failedDocs = failedDocsValue instanceof Number number ? number.intValue() : 0;
        if (failedDocs <= 0) {
            return;
        }

        Object errorsValue = graph.getMetadata().get("extractionErrors");
        String detail = errorsValue instanceof Collection<?> errors
                ? errors.stream().limit(5).map(String::valueOf).collect(Collectors.joining("; "))
                : String.valueOf(errorsValue);
        String message = "Graph extraction batch " + batch.index() + "/" + totalBatches
                + " had " + failedDocs + " document extraction error(s)";
        job.getErrorCount().addAndGet(failedDocs);
        job.getErrors().add(message + (detail == null || detail.isBlank() ? "" : ": " + detail));
        documentTracker.recordEvent(job, "GRAPH_EXTRACTION", "ERROR", message, detail);
        log.warn("[Job {}] {}: {}", job.getJobId(), message, detail);
    }

    private void recordGraphExtractionBatchPerDocument(UnifiedCrawlJob job,
                                                       Graph graph,
                                                       CostBatch<RetrievedDoc> batch,
                                                       int totalBatches) {
        Map<String, int[]> countsByDocId = new HashMap<>();
        if (graph.getEntities() != null) {
            for (Entity entity : graph.getEntities()) {
                String docId = documentTracker.sourceDocumentId(entity.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[0]++;
                }
            }
        }
        if (graph.getRelationships() != null) {
            for (Relationship relationship : graph.getRelationships()) {
                String docId = documentTracker.sourceDocumentId(relationship.getMetadata());
                if (docId != null) {
                    countsByDocId.computeIfAbsent(docId, ignored -> new int[2])[1]++;
                }
            }
        }

        String batchCompleteMsg = "LLM graph batch " + batch.index() + "/" + totalBatches + " complete";
        for (RetrievedDoc item : batch.items()) {
            int[] counts = countsByDocId.getOrDefault(item.getId(), EMPTY_COUNTS);
            documentTracker.recordDocumentProgress(job, item, "GRAPH_EXTRACTION", "COMPLETED", 0,
                    counts[0], counts[1],
                    batchCompleteMsg,
                    null, EXTRACTORS_GRAPH_CONSTRUCTOR, true);
        }
    }

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

    private int resolveGraphExtractionBatchSize(GraphExtractionConfig config) {
        int size = graphExtractionBatchSize > 0
                ? graphExtractionBatchSize
                : DEFAULT_GRAPH_EXTRACTION_BATCH_SIZE;
        return Math.max(1, Math.min(size, 128));
    }

    private int resolveGraphExtractionParallelism(UnifiedCrawlJob job, int plannedBatchCount) {
        int configured = Math.max(1, graphExtractionParallelism);
        // When GraphConstructor persists its own matrix graph and embeds inline,
        // reduce parallelism to avoid GPU/OpenMP contention. The default unified
        // crawl path skips constructor-local persistence and writes only the
        // scoped fact-sheet graph, so no embedding throttle is needed there.
        if (graphConstructor != null && graphConstructorPersistMatrixGraph && !graphConstructorSkipEmbedding) {
            configured = Math.min(configured, 2);
        }
        memoryMonitor.updateMemorySnapshot(job);
        // Only force to 1 at CRITICAL memory pressure, not just the wait threshold
        if (memoryCriticalThresholdPercent > 0
                && job.getMemoryUsagePercent().get() >= memoryCriticalThresholdPercent) {
            configured = 1;
        }
        return Math.max(1, Math.min(configured, Math.max(1, plannedBatchCount)));
    }

    // -------------------------------------------------------------------------
    // Prompt and JSON extraction helpers
    // -------------------------------------------------------------------------

    private String buildExtractionPrompt(GraphExtractionConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(GraphExtractionValidator.getExtractionPromptInstructions());

        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            sb.append("\n\nFocus on extracting these entity types: ");
            sb.append(String.join(", ", config.getEntityTypes()));
        }

        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            sb.append("\nFocus on extracting these relationship types: ");
            sb.append(String.join(", ", config.getRelationshipTypes()));
        }

        if (config.getCustomPrompt() != null && !config.getCustomPrompt().isBlank()) {
            sb.append("\n\nAdditional instructions: ").append(config.getCustomPrompt());
        }

        return sb.toString();
    }

    private String extractJsonFromResponse(String response) {
        // Find JSON block — may be wrapped in ```json ... ```
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", contentStart);
            if (jsonEnd > contentStart) {
                return response.substring(contentStart, jsonEnd).trim();
            }
        }

        // Try to find raw JSON object
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Graph merge and schema helpers
    // -------------------------------------------------------------------------

    private void mergeGraphInto(Graph source, Graph target, GraphExtractionConfig config) {
        if (source.getEntities() != null) {
            // Build O(1) lookup index for entity resolution instead of O(N) stream scan per entity
            Map<String, Entity> entityIndex = null;
            if (config.isEntityResolution() && target.getEntities() != null && !target.getEntities().isEmpty()) {
                entityIndex = new HashMap<>(target.getEntities().size());
                for (Entity e : target.getEntities()) {
                    if (e.getTitle() != null && e.getType() != null) {
                        entityIndex.put(e.getType() + "|" + e.getTitle().toLowerCase(), e);
                    }
                }
            }

            for (Entity entity : source.getEntities()) {
                // Filter by confidence threshold
                if (entity.getConfidence() != null && entity.getConfidence() < config.getMinConfidence()) {
                    continue;
                }

                // Check for duplicate by title+type (O(1) HashMap lookup)
                if (config.isEntityResolution() && entityIndex != null
                        && entity.getTitle() != null && entity.getType() != null) {
                    String key = entity.getType() + "|" + entity.getTitle().toLowerCase();
                    Entity existing = entityIndex.get(key);
                    if (existing != null) {
                        // Merge text units
                        if (entity.getTextUnits() != null) {
                            List<String> units = existing.getTextUnits();
                            if (units == null) {
                                units = new ArrayList<>();
                                existing.setTextUnits(units);
                            }
                            units.addAll(entity.getTextUnits());
                        }
                        continue; // Skip duplicate
                    }
                    // Register the new entity in the index for future merges
                    entityIndex.put(key, entity);
                }
                target.getEntities().add(entity);
            }
        }

        if (source.getRelationships() != null) {
            for (Relationship rel : source.getRelationships()) {
                if (rel.getConfidence() != null && rel.getConfidence() < config.getMinConfidence()) {
                    continue;
                }
                target.getRelationships().add(rel);
            }
        }
    }

    /**
     * Builds a GraphSchema from GraphExtractionConfig entity/relationship type lists.
     */
    private GraphSchema buildGraphSchema(GraphExtractionConfig config) {
        List<NodeType> nodeTypes = null;
        List<RelationshipType> relTypes = null;

        if (config.getEntityTypes() != null && !config.getEntityTypes().isEmpty()) {
            nodeTypes = config.getEntityTypes().stream()
                    .map(t -> new NodeType(t, t + " entity type", null))
                    .collect(Collectors.toList());
        }

        if (config.getRelationshipTypes() != null && !config.getRelationshipTypes().isEmpty()) {
            relTypes = config.getRelationshipTypes().stream()
                    .map(t -> new RelationshipType(t, t + " relationship type", null))
                    .collect(Collectors.toList());
        }

        if (nodeTypes == null && relTypes == null) {
            return null;
        }

        return new GraphSchema(nodeTypes, relTypes, null);
    }

    // -------------------------------------------------------------------------
    // Document conversion helper
    // -------------------------------------------------------------------------

    /**
     * Converts a Spring AI Document to a RetrievedDoc for use with GraphConstructor.
     */
    private static RetrievedDoc toRetrievedDoc(Document doc) {
        Map<String, Object> metadata = doc.getMetadata() != null
                ? new HashMap<>(doc.getMetadata())
                : new HashMap<>();
        return new RetrievedDoc(doc.getId(), doc.getText(), metadata);
    }

    // -------------------------------------------------------------------------
    // In-memory graph release
    // -------------------------------------------------------------------------

    void releaseInMemoryGraph(Graph graph) {
        if (graph == null) {
            return;
        }
        if (graph.getEntities() != null) {
            graph.getEntities().clear();
        }
        if (graph.getRelationships() != null) {
            graph.getRelationships().clear();
        }
        if (graph.getCommunities() != null) {
            graph.getCommunities().clear();
        }
        if (graph.getMetadata() != null) {
            graph.getMetadata().clear();
        }
    }

    // -------------------------------------------------------------------------
    // Cost estimation and batch planning
    // -------------------------------------------------------------------------

    private long estimateTextCost(String text, Map<String, Object> metadata) {
        long cost = text != null ? Math.max(1, text.length()) : 1;
        if (metadata == null) {
            return cost;
        }
        if (Boolean.TRUE.equals(metadata.get(GraphConstants.META_VLM_PROCESSED))) {
            cost = Math.round(cost * 1.5);
        }
        Object contentType = metadata.get(GraphConstants.META_CONTENT_TYPE);
        if (contentType instanceof String type) {
            String normalized = type.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("table") || normalized.contains("vlm")) {
                cost = Math.round(cost * 1.3);
            } else if (normalized.contains("html")) {
                cost = Math.round(cost * 1.15);
            }
        }
        return Math.max(1, cost);
    }

    private long estimateDocumentCost(Document document) {
        return estimateTextCost(document != null ? document.getText() : null,
                document != null ? document.getMetadata() : null);
    }

    private <T> List<CostBatch<T>> planCostBatches(List<T> items,
                                                    Function<T, Long> costEstimator,
                                                    int maxItemsPerBatch,
                                                    long targetCostPerBatch,
                                                    boolean balanceByCost) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        int maxItems = Math.max(1, maxItemsPerBatch);
        long targetCost = Math.max(0, targetCostPerBatch);
        List<CostItem<T>> costItems = new ArrayList<>(items.size());
        for (T item : items) {
            long cost = 1L;
            try {
                Long estimated = costEstimator.apply(item);
                if (estimated != null) {
                    cost = Math.max(1L, estimated);
                }
            } catch (Exception ignored) {
                cost = 1L;
            }
            costItems.add(new CostItem<>(item, cost));
        }

        if (!balanceByCost) {
            List<CostBatch<T>> batches = new ArrayList<>();
            List<T> current = new ArrayList<>();
            long currentCost = 0L;
            for (CostItem<T> costItem : costItems) {
                boolean overItemLimit = current.size() >= maxItems;
                boolean overCostLimit = targetCost > 0 && !current.isEmpty()
                        && currentCost + costItem.cost() > targetCost;
                if (overItemLimit || overCostLimit) {
                    batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
                    current.clear();
                    currentCost = 0L;
                }
                current.add(costItem.item());
                currentCost += costItem.cost();
            }
            if (!current.isEmpty()) {
                batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
            }
            return batches;
        }

        costItems.sort((a, b) -> Long.compare(b.cost(), a.cost()));

        // Use a min-heap by cost to find the lightest batch in O(log B) instead of O(B)
        PriorityQueue<MutableCostBatch<T>> batchHeap = new PriorityQueue<>(
                Comparator.comparingLong(b -> b.cost));
        for (CostItem<T> costItem : costItems) {
            MutableCostBatch<T> best = null;
            if (!batchHeap.isEmpty()) {
                MutableCostBatch<T> lightest = batchHeap.peek();
                if (lightest.items.size() < maxItems) {
                    long newCost = lightest.cost + costItem.cost();
                    if (targetCost <= 0 || lightest.items.isEmpty() || newCost <= targetCost) {
                        best = batchHeap.poll();
                    }
                }
            }
            if (best == null) {
                best = new MutableCostBatch<>();
            }
            best.add(costItem.item(), costItem.cost());
            batchHeap.offer(best);
        }

        List<CostBatch<T>> batches = new ArrayList<>(batchHeap.size());
        for (MutableCostBatch<T> batch : batchHeap) {
            batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(batch.items), batch.cost));
        }
        return batches;
    }

    // -------------------------------------------------------------------------
    // Progress update helpers
    // -------------------------------------------------------------------------

    private void updateProgress(UnifiedCrawlJob job, String phase, int progressPercent,
                                String message, String details) {
        job.getCurrentPhase().set(phase);
        int boundedProgress = Math.max(progressPercent, estimateProgressForPhase(job, phase));
        boundedProgress = Math.max(0, Math.min(100, boundedProgress));
        job.getProgressPercent().accumulateAndGet(boundedProgress, Math::max);
        memoryMonitor.updateMemorySnapshot(job);
        // Throttle expensive event recording + pipeline step updates to at most once per 250ms.
        // Atomic counters above are always updated — they're cheap.
        long now = System.nanoTime();
        if ((now - lastProgressEventNanos) >= PROGRESS_EVENT_INTERVAL_NANOS) {
            lastProgressEventNanos = now;
            if (message != null && !message.isBlank()) {
                documentTracker.recordEvent(job, phase, "INFO", message, details);
            }
            pipelineStepTracker.updatePipelineStepFromCounters(job, phase, message, details);
        }
    }

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
                    if (s == UnifiedCrawlJob.Status.COMPLETED || s == UnifiedCrawlJob.Status.FAILED) {
                        done++;
                    }
                }
                return 2 + (int) Math.min(18, (done * 18L) / totalSources);
            }
            return 5;
        }
        if (phase.equals("CONVERTING")) return 22;
        if (phase.equals("ROUTING") || phase.equals("GRAPH_PREP")) return 28;
        if (phase.equals("CHUNKING")) return 35;
        if (phase.equals("GRAPH_EXTRACTION")) {
            int total = graphChunksTotal(job);
            int done = normalizeGraphChunksProcessed(job);
            return total > 0 ? 40 + (int) Math.min(30, (done * 30L) / total) : 40;
        }
        if (phase.equals("SURFACING")) return 71;
        if (phase.equals("ENTITY_RESOLUTION")) {
            UnifiedCrawlJob.PipelineStepProgress step = pipelineStepTracker.ensurePipelineStep(job, phase);
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

    // -------------------------------------------------------------------------
    // Inline private helpers
    // -------------------------------------------------------------------------

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

    private boolean isFatalLlmUnavailable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("all cli agents failed")
                        || lower.contains("terminalquotaerror")
                        || lower.contains("insufficient_quota")
                        || lower.contains("you've hit your limit")
                        || lower.contains("you have hit your limit")
                        || lower.contains("you've hit your usage limit")
                        || lower.contains("you have hit your usage limit")
                        || lower.contains("you have exhausted your capacity")
                        || lower.contains("rate limit reached")
                        || lower.contains("rate limit exceeded")
                        || lower.contains("quota exceeded")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void cancelGraphFutures(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner types — batch planning data structures
    // -------------------------------------------------------------------------

    private record CostBatch<T>(int index, List<T> items, long cost) {
    }

    private record CostItem<T>(T item, long cost) {
    }

    private record GraphBatchResult(CostBatch<RetrievedDoc> batch, Graph graph, Throwable error) {
    }

    private static class MutableCostBatch<T> {
        private final List<T> items = new ArrayList<>();
        private long cost;

        void add(T item, long itemCost) {
            items.add(item);
            cost += itemCost;
        }
    }

    // -------------------------------------------------------------------------
    // OuterParallelismAdvisor — adaptive concurrency control for graph batches
    // -------------------------------------------------------------------------

    static class OuterParallelismAdvisor {
        private volatile int currentParallelism;
        private final int maxParallelism;
        private final Semaphore concurrencyGate;
        private int consecutiveLowMemoryBatches;
        private long lastRampTime;
        private static final long RAMP_COOLDOWN_MS = 12_000;
        private static final double CRITICAL_THRESHOLD = 0.82;
        private static final double HIGH_THRESHOLD = 0.70;
        private static final double LOW_THRESHOLD = 0.60;
        private static final int RAMP_AFTER_LOW_COUNT = 2;

        OuterParallelismAdvisor(int initialParallelism) {
            this.maxParallelism = Math.max(1, initialParallelism);
            this.currentParallelism = this.maxParallelism;
            this.concurrencyGate = new Semaphore(this.maxParallelism);
            this.lastRampTime = System.currentTimeMillis();
        }

        /**
         * Acquire a permit before submitting a batch. Blocks if the advisor
         * has reduced parallelism and all permits are in use.
         */
        void acquirePermit() throws InterruptedException {
            concurrencyGate.acquire();
        }

        /**
         * Release a permit after a batch completes (call from task finally block).
         */
        void releasePermit() {
            concurrencyGate.release();
        }

        synchronized void afterBatchComplete(long batchMs, double heapPercent) {
            int oldParallelism = currentParallelism;

            if (heapPercent > CRITICAL_THRESHOLD) {
                currentParallelism = 1;
                consecutiveLowMemoryBatches = 0;
                if (oldParallelism != 1) {
                    // Drain excess permits so only 1 batch can run concurrently
                    drainPermits(oldParallelism, 1);
                    log.info("Outer graph parallelism reduced {} -> 1 (reason: heap {}% > critical {}%)",
                            oldParallelism, Math.round(heapPercent * 100), Math.round(CRITICAL_THRESHOLD * 100));
                }
            } else if (heapPercent > HIGH_THRESHOLD) {
                consecutiveLowMemoryBatches = 0;
                // Hold at current level
            } else if (heapPercent < LOW_THRESHOLD) {
                consecutiveLowMemoryBatches++;
                long now = System.currentTimeMillis();
                if (consecutiveLowMemoryBatches >= RAMP_AFTER_LOW_COUNT
                        && now - lastRampTime >= RAMP_COOLDOWN_MS
                        && currentParallelism < maxParallelism) {
                    int newParallelism = Math.min(maxParallelism, currentParallelism + 1);
                    // Release additional permits to allow more concurrency
                    concurrencyGate.release(newParallelism - currentParallelism);
                    currentParallelism = newParallelism;
                    lastRampTime = now;
                    consecutiveLowMemoryBatches = 0;
                    log.info("Outer graph parallelism increased {} -> {} (reason: {} consecutive low-memory batches, heap {}%)",
                            oldParallelism, currentParallelism, RAMP_AFTER_LOW_COUNT, Math.round(heapPercent * 100));
                }
            } else {
                consecutiveLowMemoryBatches = 0;
            }
        }

        private void drainPermits(int from, int to) {
            int toDrain = from - to;
            for (int d = 0; d < toDrain; d++) {
                if (!concurrencyGate.tryAcquire()) break;
            }
        }

        int getCurrentParallelism() { return currentParallelism; }
    }
}
