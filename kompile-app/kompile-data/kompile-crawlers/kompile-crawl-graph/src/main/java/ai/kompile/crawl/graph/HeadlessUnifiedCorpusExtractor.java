/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.model.Graph;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI-independent entry point for the production unified-corpus graph extractor.
 *
 * <p>The scheduled/UI crawl continues to own source scheduling, progress persistence, and its
 * parallel batch lifecycle. Local tools can use this facade after preparing folder-scoped chunks,
 * supplying their own CLI-agent or serving-subprocess bridges. Both paths therefore execute the
 * same {@link GraphExtractionOrchestrator} and {@link CrawlLlmDispatcher} rather than maintaining a
 * second semantic extraction implementation.</p>
 */
public final class HeadlessUnifiedCorpusExtractor implements AutoCloseable {

    private final CrawlLlmDispatcher llmDispatcher;
    private final GraphExtractionOrchestrator orchestrator;
    private final PipelineStepTracker pipelineStepTracker;
    private final ExecutorService extractionPool;

    public HeadlessUnifiedCorpusExtractor(CliAgentRunner cliAgentRunner) {
        this(cliAgentRunner, null, Math.max(1, Math.min(4,
                Runtime.getRuntime().availableProcessors())));
    }

    public HeadlessUnifiedCorpusExtractor(CliAgentRunner cliAgentRunner,
                                          LocalServingBackend localServingBackend,
                                          int parallelism) {
        if (cliAgentRunner == null && localServingBackend == null) {
            throw new IllegalArgumentException(
                    "A CLI-agent runner or local serving backend is required for headless extraction");
        }
        int effectiveParallelism = Math.max(1, parallelism);
        CrawlDocumentTracker documentTracker = new CrawlDocumentTracker();
        GraphPersistenceHelper persistenceHelper = new GraphPersistenceHelper();
        persistenceHelper.documentTracker = documentTracker;

        this.llmDispatcher = new CrawlLlmDispatcher(cliAgentRunner, localServingBackend);
        this.pipelineStepTracker = new PipelineStepTracker();
        this.orchestrator = new GraphExtractionOrchestrator();
        this.orchestrator.graphPersistenceHelper = persistenceHelper;
        this.orchestrator.documentTracker = documentTracker;
        this.orchestrator.llmDispatcher = llmDispatcher;
        this.orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        this.orchestrator.memoryMonitor = new CrawlMemoryMonitor();
        this.orchestrator.pipelineStepTracker = pipelineStepTracker;
        this.orchestrator.retainResultGraph = true;
        this.orchestrator.graphExtractionParallelism = effectiveParallelism;
        this.orchestrator.graphExtractionRemoteParallelism = effectiveParallelism;
        this.extractionPool = Executors.newFixedThreadPool(effectiveParallelism, runnable -> {
            Thread thread = new Thread(runnable, "headless-crawl-extraction");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Extract a semantic graph from already loaded/chunked corpus documents.
     */
    public Result extract(List<Document> documents,
                          GraphExtractionConfig graphExtraction,
                          ProcessingRouteConfig processingRoute,
                          String jobId,
                          Long factSheetId) {
        return extract(documents, graphExtraction, processingRoute, null, jobId, factSheetId);
    }

    public Result extract(List<Document> documents,
                          GraphExtractionConfig graphExtraction,
                          ProcessingRouteConfig processingRoute,
                          UnifiedCrawlRequest.RuntimeConfig runtimeConfig,
                          String jobId,
                          Long factSheetId) {
        List<Document> corpus = List.copyOf(Objects.requireNonNull(documents, "documents"));
        GraphExtractionConfig extractionConfig = graphExtraction != null
                ? graphExtraction : GraphExtractionConfig.builder().build();
        String effectiveJobId = jobId == null || jobId.isBlank()
                ? "local-" + UUID.randomUUID() : jobId;

        UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                .name("Headless local corpus extraction")
                .factSheetId(factSheetId)
                .graphExtraction(extractionConfig)
                .processingRoute(processingRoute)
                .runtimeConfig(runtimeConfig)
                .build();
        UnifiedCrawlJob job = UnifiedCrawlJob.builder()
                .jobId(effectiveJobId)
                .request(request)
                .status(new AtomicReference<>(UnifiedCrawlJob.Status.RUNNING))
                .startedAt(Instant.now())
                .build();
        job.getDocumentsDiscovered().set(corpus.size());
        job.getDocumentsLoaded().set(corpus.size());
        job.getChunksCreated().set(corpus.size());
        pipelineStepTracker.initializePipelineSteps(job);
        applyRuntimeConfig(runtimeConfig);

        Graph graph = Graph.builder()
                .id("headless:" + effectiveJobId)
                .name("Headless local corpus graph")
                .factSheetId(factSheetId)
                .build();
        orchestrator.extractGraphFromDocuments(
                corpus, extractionConfig, graph, job, extractionPool);

        boolean failed = job.getErrorCount().get() > 0
                || job.getGraphExtractionParseFailures().get() > 0;
        job.getStatus().set(failed
                ? UnifiedCrawlJob.Status.FAILED : UnifiedCrawlJob.Status.COMPLETED);
        job.setCompletedAt(Instant.now());
        return new Result(
                graph,
                List.copyOf(job.getErrors()),
                job.getGraphChunksProcessed().get(),
                job.getGraphExtractionParseFailures().get(),
                failed);
    }

    private void applyRuntimeConfig(UnifiedCrawlRequest.RuntimeConfig config) {
        if (config == null) return;
        if (config.getLlmCallTimeoutSeconds() != null) {
            llmDispatcher.setLlmCallTimeoutSeconds(config.getLlmCallTimeoutSeconds());
        }
        if (config.getGraphExtractionParallelism() != null) {
            orchestrator.graphExtractionParallelism = clampParallelism(
                    config.getGraphExtractionParallelism());
        }
        if (config.getGraphExtractionRemoteParallelism() != null) {
            orchestrator.graphExtractionRemoteParallelism = clampParallelism(
                    config.getGraphExtractionRemoteParallelism());
        }
        if (config.getGraphExtractionBatchSize() != null) {
            orchestrator.graphExtractionBatchSize = Math.max(1,
                    config.getGraphExtractionBatchSize());
        }
        if (config.getGraphExtractionTargetCharsPerBatch() != null) {
            orchestrator.graphExtractionTargetCharsPerBatch = Math.max(1,
                    config.getGraphExtractionTargetCharsPerBatch());
        }
        if (config.getGraphExtractionMaxItemsPerBatch() != null) {
            orchestrator.graphExtractionMaxItemsPerBatch = Math.max(1,
                    config.getGraphExtractionMaxItemsPerBatch());
        }
        if (config.getGraphExtractionBatchTimeoutSeconds() != null) {
            orchestrator.graphExtractionBatchTimeoutSeconds = Math.max(1,
                    config.getGraphExtractionBatchTimeoutSeconds());
        }
        if (config.getCostSortChunks() != null) {
            orchestrator.costSortChunks = config.getCostSortChunks();
        }
    }

    private int clampParallelism(int value) {
        return Math.max(1, Math.min(32, value));
    }

    @Override
    public void close() {
        extractionPool.shutdownNow();
        llmDispatcher.shutdownLlmTimeoutExecutor();
    }

    public record Result(Graph graph,
                         List<String> errors,
                         int chunksProcessed,
                         int parseFailures,
                         boolean failed) {
    }
}
