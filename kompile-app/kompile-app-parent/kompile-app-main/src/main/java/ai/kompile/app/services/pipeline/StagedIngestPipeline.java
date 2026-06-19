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

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.GraphExtractionConfigService;
import ai.kompile.app.services.pipeline.stages.ChunkingStage;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddedChunk;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddingOutput;
import ai.kompile.app.services.pipeline.stages.ExtractionStage;
import ai.kompile.app.services.pipeline.stages.GraphBuildingStage;
import ai.kompile.app.services.pipeline.stages.IndexingStage.IndexingOutput;
import ai.kompile.app.services.pipeline.stages.TokenizationStage;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Staged document ingest pipeline with producer-consumer queues between stages.
 *
 * <h2>Architecture</h2>
 * <pre>
 * ┌─────────┐    ┌──────────────┐    ┌──────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
 * │ EXTRACT │───▶│  TOKENIZE    │───▶│    CHUNK     │───▶│   EMBED     │───▶│    INDEX    │───▶│ GRAPH BUILD │
 * │ (1-2T)  │    │   (2-4T)     │    │   (4-8T)     │    │  (1-4T)     │    │ (1T+Batch)  │    │ (Optional)  │
 * └────┬────┘    └──────┬───────┘    └──────┬───────┘    └──────┬──────┘    └──────┬──────┘    └─────────────┘
 *      │                │                    │                   │                  │
 *      ▼                ▼                    ▼                   ▼                  ▼
 *  [Queue A]        [Queue B]           [Queue C]           [Queue D]          [Queue E]
 * </pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Parallel processing at each stage</li>
 *   <li>Bounded queues for backpressure</li>
 *   <li>Adaptive thread counts based on system resources</li>
 *   <li>Memory-aware throttling</li>
 *   <li>Progress reporting per stage</li>
 *   <li>Graceful cancellation support</li>
 *   <li>Optional entity/relationship extraction and knowledge graph building</li>
 * </ul>
 */
public class StagedIngestPipeline implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StagedIngestPipeline.class);

    // Queue configuration
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final long QUEUE_POLL_TIMEOUT_MS = 100;

    // Pipeline stages
    private final ExtractionStage extractionStage;
    private final TokenizationStage tokenizationStage;
    private final ChunkingStage chunkingStage;
    private final ai.kompile.app.services.pipeline.stages.EmbeddingStage embeddingStage;
    private final ai.kompile.app.services.pipeline.stages.IndexingStage indexingStage;
    private final GraphBuildingStage graphBuildingStage;

    // Thread pools for each stage
    private final ExecutorService extractionExecutor;
    private final ExecutorService tokenizationExecutor;
    private final ExecutorService chunkingExecutor;
    private final ExecutorService embeddingExecutor;
    private final ExecutorService indexingExecutor;
    private final ExecutorService graphBuildingExecutor;

    // Inter-stage queues
    private final BlockingQueue<ExtractionStage.ExtractionOutput> extractionQueue;
    private final BlockingQueue<TokenizationStage.TokenizationOutput> tokenizationQueue;
    private final BlockingQueue<ChunkingStage.ChunkingOutput> chunkingQueue;
    private final BlockingQueue<EmbeddingOutput> embeddingQueue;
    private final BlockingQueue<IndexingOutput> indexingQueue;

    // Accumulated chunks for graph building (collected during embedding stage)
    private final List<RetrievedDoc> accumulatedChunks = Collections.synchronizedList(new ArrayList<>());

    // Configuration
    private final PipelineSettings settings;
    private final GraphExtractionConfigService graphExtractionConfigService;
    private final GraphConstructor graphConstructor;

    // State tracking
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong startTimeMs = new AtomicLong(0);

    // Progress tracking
    private final AtomicInteger filesSubmitted = new AtomicInteger(0);
    private final AtomicInteger filesExtracted = new AtomicInteger(0);
    private final AtomicInteger documentsTokenized = new AtomicInteger(0);
    private final AtomicInteger documentsChunked = new AtomicInteger(0);
    private final AtomicInteger chunksEmbedded = new AtomicInteger(0);
    private final AtomicInteger chunksIndexed = new AtomicInteger(0);
    private final AtomicLong tokensProcessed = new AtomicLong(0);
    private final AtomicInteger entitiesExtracted = new AtomicInteger(0);
    private final AtomicInteger relationshipsExtracted = new AtomicInteger(0);
    private final AtomicBoolean graphBuildingComplete = new AtomicBoolean(false);

    // Progress callback
    private Consumer<StagedPipelineProgress> progressCallback;

    /**
     * Creates a staged pipeline with the given components and default settings.
     */
    public StagedIngestPipeline(
            List<DocumentLoader> loaders,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService
    ) {
        this(loaders, chunker, embeddingModel, indexerService, null, null, PipelineSettings.adaptive());
    }

    /**
     * Creates a staged pipeline with the given components, graph constructor, and default settings.
     */
    public StagedIngestPipeline(
            List<DocumentLoader> loaders,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            GraphConstructor graphConstructor
    ) {
        this(loaders, chunker, embeddingModel, indexerService, graphConstructor, null, PipelineSettings.adaptive());
    }

    /**
     * Creates a staged pipeline with custom settings (no graph constructor).
     */
    public StagedIngestPipeline(
            List<DocumentLoader> loaders,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            PipelineSettings settings
    ) {
        this(loaders, chunker, embeddingModel, indexerService, null, null, settings);
    }

    /**
     * Creates a staged pipeline with custom settings and optional graph constructor.
     */
    public StagedIngestPipeline(
            List<DocumentLoader> loaders,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            GraphConstructor graphConstructor,
            PipelineSettings settings
    ) {
        this(loaders, chunker, embeddingModel, indexerService, graphConstructor, null, settings);
    }

    /**
     * Creates a staged pipeline with UI-configurable graph extraction settings.
     * This constructor allows the graph extraction configuration to be read from
     * the GraphExtractionConfigService (persisted UI settings) instead of PipelineSettings.
     */
    public StagedIngestPipeline(
            List<DocumentLoader> loaders,
            TextChunker chunker,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            GraphConstructor graphConstructor,
            GraphExtractionConfigService graphExtractionConfigService,
            PipelineSettings settings
    ) {
        this.settings = settings != null ? settings : PipelineSettings.adaptive();
        this.graphExtractionConfigService = graphExtractionConfigService;
        this.graphConstructor = graphConstructor;

        // Create stages
        this.extractionStage = new ExtractionStage(loaders);
        this.tokenizationStage = new TokenizationStage();
        this.chunkingStage = new ChunkingStage(chunker);
        this.embeddingStage = new ai.kompile.app.services.pipeline.stages.EmbeddingStage(embeddingModel);
        this.indexingStage = new ai.kompile.app.services.pipeline.stages.IndexingStage(indexerService);
        this.graphBuildingStage = new GraphBuildingStage(graphConstructor);

        // Configure stages
        configureStages();

        // Create thread pools with adaptive sizing
        this.extractionExecutor = createExecutor("extraction", this.settings.extractionThreads());
        this.tokenizationExecutor = createExecutor("tokenization", this.settings.tokenizationThreads());
        this.chunkingExecutor = createExecutor("chunking", this.settings.chunkingThreads());
        this.embeddingExecutor = createExecutor("embedding", this.settings.embeddingThreads());
        this.indexingExecutor = createExecutor("indexing", 1); // Sequential for Lucene
        this.graphBuildingExecutor = createExecutor("graph-building", 1); // Sequential for graph operations

        // Create inter-stage queues
        int queueCapacity = this.settings.queueCapacity();
        this.extractionQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.tokenizationQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.chunkingQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.embeddingQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.indexingQueue = new LinkedBlockingQueue<>(queueCapacity);

        logger.info("StagedIngestPipeline initialized: extract={}, tokenize={}, chunk={}, embed={}, queue={}, graph={}",
                this.settings.extractionThreads(), this.settings.tokenizationThreads(),
                this.settings.chunkingThreads(), this.settings.embeddingThreads(), queueCapacity,
                isGraphBuildingEnabled() ? "enabled" : "disabled");
    }

    private void configureStages() {
        Map<String, Object> extractionOptions = new HashMap<>();
        extractionOptions.put("preferredLoader", settings.preferredLoader());
        extractionOptions.put("autoDetectLoader", settings.autoDetectLoader());
        extractionStage.configure(extractionOptions);

        Map<String, Object> tokenizationOptions = new HashMap<>();
        tokenizationOptions.put("enabled", settings.enablePreTokenization());
        tokenizationOptions.put("maxTokenLength", settings.maxTokenLength());
        tokenizationOptions.put("tokenizerModel", settings.tokenizerModel());
        tokenizationStage.configure(tokenizationOptions);

        Map<String, Object> chunkingOptions = new HashMap<>();
        chunkingOptions.put("chunkSize", settings.chunkSize());
        chunkingOptions.put("chunkOverlap", settings.chunkOverlap());
        chunkingOptions.put("preserveParagraphs", settings.preserveParagraphs());
        chunkingStage.configure(chunkingOptions);

        Map<String, Object> embeddingOptions = new HashMap<>();
        embeddingOptions.put("batchSize", settings.embeddingBatchSize());
        embeddingOptions.put("adaptiveBatching", true);
        embeddingStage.configure(embeddingOptions);

        Map<String, Object> indexingOptions = new HashMap<>();
        indexingOptions.put("batchSize", settings.indexBatchSize());
        indexingOptions.put("adaptiveBatching", true);
        indexingStage.configure(indexingOptions);

        // Configure graph building from the config service (UI settings) if available,
        // otherwise fall back to pipeline settings
        configureGraphBuildingStage();
    }

    /**
     * Configures the graph building stage from the GraphExtractionConfigService if available,
     * otherwise uses PipelineSettings. This allows UI-based configuration to override defaults.
     */
    private void configureGraphBuildingStage() {
        Map<String, Object> graphBuildingOptions = new HashMap<>();

        if (graphExtractionConfigService != null) {
            // Read from UI-configurable service (persisted settings)
            GraphExtractionConfigService.GraphExtractionConfig config = graphExtractionConfigService.getConfig();
            graphBuildingOptions.put("enabled", config.enabled != null && config.enabled);
            graphBuildingOptions.put("batchSize", config.batchSize != null ? config.batchSize : 10);

            // Set schema enforcement mode
            if (config.schemaEnforcement != null) {
                try {
                    graphBuildingOptions.put("schemaEnforcementMode",
                            SchemaEnforcementMode.valueOf(config.schemaEnforcement));
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid schema enforcement mode: {}, using LENIENT", config.schemaEnforcement);
                    graphBuildingOptions.put("schemaEnforcementMode", SchemaEnforcementMode.LENIENT);
                }
            }

            // Configure the GraphConstructor with model settings
            if (graphConstructor != null) {
                GraphConstructor.ExtractionModelConfig modelConfig = new GraphConstructor.ExtractionModelConfig(
                        config.extractionModelProvider != null ? config.extractionModelProvider : "default",
                        config.extractionModelName,
                        config.extractionTemperature != null ? config.extractionTemperature : 0.0,
                        config.extractionMaxTokens != null ? config.extractionMaxTokens : 4096,
                        config.customExtractionPrompt
                );
                graphConstructor.configure(modelConfig);
                logger.debug("Configured graph constructor model: provider={}, model={}, temperature={}, maxTokens={}",
                        modelConfig.provider(), modelConfig.modelName(), modelConfig.temperature(), modelConfig.maxTokens());
            }

            logger.debug("Graph building configured from UI settings: enabled={}, batchSize={}, schemaEnforcement={}",
                    config.enabled, config.batchSize, config.schemaEnforcement);
        } else {
            // Fall back to pipeline settings
            graphBuildingOptions.put("enabled", settings.enableGraphBuilding());
            graphBuildingOptions.put("batchSize", settings.graphBuildingBatchSize());
            logger.debug("Graph building configured from pipeline settings: enabled={}, batchSize={}",
                    settings.enableGraphBuilding(), settings.graphBuildingBatchSize());
        }

        graphBuildingStage.configure(graphBuildingOptions);
    }

    /**
     * Check if graph building is enabled (from config service if available, otherwise pipeline settings).
     */
    private boolean isGraphBuildingEnabled() {
        if (graphExtractionConfigService != null) {
            return graphExtractionConfigService.isEnabled();
        }
        return settings.enableGraphBuilding();
    }

    /**
     * Get the graph building batch size (from config service if available, otherwise pipeline settings).
     */
    private int getGraphBuildingBatchSize() {
        if (graphExtractionConfigService != null) {
            return graphExtractionConfigService.getBatchSize();
        }
        return settings.graphBuildingBatchSize();
    }

    private ExecutorService createExecutor(String stageName, int threadCount) {
        return new ThreadPoolExecutor(
                threadCount, threadCount,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "pipeline-" + stageName);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Sets the progress callback for real-time updates.
     */
    public void setProgressCallback(Consumer<StagedPipelineProgress> callback) {
        this.progressCallback = callback;
    }

    /**
     * Processes a single file through the pipeline.
     */
    public PipelineResult processFile(Path filePath, String taskId) throws Exception {
        return processFiles(List.of(filePath), taskId);
    }

    /**
     * Processes multiple files through the pipeline concurrently.
     */
    public PipelineResult processFiles(List<Path> filePaths, String taskId) throws Exception {
        if (filePaths == null || filePaths.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, 0, 0, false, 0, List.of(), 0);
        }

        if (running.getAndSet(true)) {
            throw new IllegalStateException("Pipeline is already running");
        }

        try {
            resetState();
            startTimeMs.set(System.currentTimeMillis());
            filesSubmitted.set(filePaths.size());

            logger.info("Starting staged pipeline for {} files, taskId={}", filePaths.size(), taskId);
            reportProgress("starting", 0, "Initializing pipeline");

            // Start stage workers
            startStageWorkers();

            // Submit files for extraction
            for (int i = 0; i < filePaths.size(); i++) {
                if (cancelled.get()) break;

                Path filePath = filePaths.get(i);
                final int fileIndex = i;

                extractionExecutor.submit(() -> {
                    try {
                        ExtractionStage.ExtractionInput input = new ExtractionStage.ExtractionInput(
                                filePath, settings.preferredLoader(), taskId, null, null
                        );
                        ExtractionStage.ExtractionOutput output = extractionStage.process(input);
                        extractionQueue.put(output);
                        filesExtracted.incrementAndGet();
                        reportProgress("extraction", calculateProgress(),
                                String.format("Extracted %d/%d files", filesExtracted.get(), filesSubmitted.get()));
                    } catch (Exception e) {
                        logger.error("Extraction failed for {}: {}", filePath, e.getMessage(), e);
                    }
                });
            }

            // Wait for all stages to complete
            awaitCompletion(taskId);

            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            List<String> indexedIds = collectIndexedIds();

            logger.info("Pipeline complete: {} files -> {} docs -> {} chunks in {}ms",
                    filesExtracted.get(), documentsChunked.get(), chunksIndexed.get(), totalTimeMs);

            reportProgress("complete", 100,
                    String.format("Indexed %d chunks from %d files", chunksIndexed.get(), filesExtracted.get()));

            return new PipelineResult(
                    filesExtracted.get(),
                    documentsChunked.get(),
                    chunksIndexed.get(),
                    tokensProcessed.get(),
                    entitiesExtracted.get(),
                    relationshipsExtracted.get(),
                    isGraphBuildingEnabled(),
                    totalTimeMs,
                    indexedIds,
                    calculateThroughput(totalTimeMs)
            );

        } finally {
            running.set(false);
        }
    }

    /**
     * Processes already-loaded documents through the pipeline (skipping extraction).
     */
    public PipelineResult processDocuments(List<Document> documents, String taskId) throws Exception {
        if (documents == null || documents.isEmpty()) {
            return new PipelineResult(0, 0, 0, 0, 0, 0, false, 0, List.of(), 0);
        }

        if (running.getAndSet(true)) {
            throw new IllegalStateException("Pipeline is already running");
        }

        try {
            resetState();
            startTimeMs.set(System.currentTimeMillis());
            filesExtracted.set(1); // Treat as single extraction

            logger.info("Starting staged pipeline for {} pre-loaded documents, taskId={}", documents.size(), taskId);
            reportProgress("starting", 0, "Initializing pipeline");

            // Start stage workers
            startStageWorkers();

            // Create synthetic extraction output and queue it directly
            ExtractionStage.ExtractionOutput extractionOutput = new ExtractionStage.ExtractionOutput(
                    documents, "pre-loaded", 0, 0, taskId, null
            );
            extractionQueue.put(extractionOutput);

            // Wait for all stages to complete
            awaitCompletion(taskId);

            long totalTimeMs = System.currentTimeMillis() - startTimeMs.get();
            List<String> indexedIds = collectIndexedIds();

            logger.info("Pipeline complete: {} documents -> {} chunks in {}ms",
                    documents.size(), chunksIndexed.get(), totalTimeMs);

            reportProgress("complete", 100,
                    String.format("Indexed %d chunks from %d documents", chunksIndexed.get(), documents.size()));

            return new PipelineResult(
                    1, // Single "file" (the document batch)
                    documentsChunked.get(),
                    chunksIndexed.get(),
                    tokensProcessed.get(),
                    entitiesExtracted.get(),
                    relationshipsExtracted.get(),
                    isGraphBuildingEnabled(),
                    totalTimeMs,
                    indexedIds,
                    calculateThroughput(totalTimeMs)
            );

        } finally {
            running.set(false);
        }
    }

    private void startStageWorkers() {
        // Tokenization worker(s)
        for (int i = 0; i < settings.tokenizationThreads(); i++) {
            tokenizationExecutor.submit(() -> runTokenizationWorker());
        }

        // Chunking worker(s)
        for (int i = 0; i < settings.chunkingThreads(); i++) {
            chunkingExecutor.submit(() -> runChunkingWorker());
        }

        // Embedding worker(s)
        for (int i = 0; i < settings.embeddingThreads(); i++) {
            embeddingExecutor.submit(() -> runEmbeddingWorker());
        }

        // Indexing worker (single-threaded)
        indexingExecutor.submit(() -> runIndexingWorker());

        // Graph building worker (single-threaded, runs after indexing if enabled)
        if (isGraphBuildingEnabled() && graphBuildingStage.isEnabled()) {
            graphBuildingExecutor.submit(() -> runGraphBuildingWorker());
        }
    }

    private void runTokenizationWorker() {
        while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ExtractionStage.ExtractionOutput extraction = extractionQueue.poll(
                        QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (extraction == null) {
                    if (isExtractionComplete()) break;
                    continue;
                }

                TokenizationStage.TokenizationOutput tokenization = tokenizationStage.process(extraction);
                tokenizationQueue.put(tokenization);
                documentsTokenized.addAndGet(tokenization.documentCount());
                tokensProcessed.addAndGet(tokenization.totalTokens());

                reportProgress("tokenization", calculateProgress(),
                        String.format("Tokenized %d documents (%d tokens)", documentsTokenized.get(), tokensProcessed.get()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Tokenization worker error: {}", e.getMessage(), e);
            }
        }
    }

    private void runChunkingWorker() {
        while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
            try {
                TokenizationStage.TokenizationOutput tokenization = tokenizationQueue.poll(
                        QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (tokenization == null) {
                    if (isTokenizationComplete()) break;
                    continue;
                }

                ChunkingStage.ChunkingOutput chunking = chunkingStage.process(tokenization);
                chunkingQueue.put(chunking);
                documentsChunked.addAndGet(chunking.documentsChunked());

                reportProgress("chunking", calculateProgress(),
                        String.format("Created %d chunks from %d documents",
                                chunking.chunkCount(), documentsChunked.get()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Chunking worker error: {}", e.getMessage(), e);
            }
        }
    }

    private void runEmbeddingWorker() {
        while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ChunkingStage.ChunkingOutput chunking = chunkingQueue.poll(
                        QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (chunking == null) {
                    if (isChunkingComplete()) break;
                    continue;
                }

                EmbeddingOutput embedding = embeddingStage.process(chunking);
                embeddingQueue.put(embedding);
                chunksEmbedded.addAndGet(embedding.chunkCount());

                reportProgress("embedding", calculateProgress(),
                        String.format("Embedded %d chunks", chunksEmbedded.get()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Embedding worker error: {}", e.getMessage(), e);
            }
        }
    }

    private void runIndexingWorker() {
        List<String> allIndexedIds = new ArrayList<>();

        while (!cancelled.get() && !Thread.currentThread().isInterrupted()) {
            try {
                EmbeddingOutput embedding = embeddingQueue.poll(
                        QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (embedding == null) {
                    if (isEmbeddingComplete()) break;
                    continue;
                }

                // Collect chunks for graph building before indexing
                if (isGraphBuildingEnabled() && embedding.embeddedChunks() != null) {
                    for (EmbeddedChunk ec : embedding.embeddedChunks()) {
                        accumulatedChunks.add(ec.chunk());
                    }
                }

                IndexingOutput indexing = indexingStage.process(embedding);
                chunksIndexed.addAndGet(indexing.chunksIndexed());
                allIndexedIds.addAll(indexing.indexedDocumentIds());

                // Queue indexing output for graph building
                if (isGraphBuildingEnabled()) {
                    try {
                        indexingQueue.put(indexing);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                reportProgress("indexing", calculateProgress(),
                        String.format("Indexed %d chunks", chunksIndexed.get()));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Indexing worker error: {}", e.getMessage(), e);
            }
        }
    }

    private void runGraphBuildingWorker() {
        if (!isGraphBuildingEnabled()) {
            graphBuildingComplete.set(true);
            return;
        }

        // Wait for indexing to complete before starting graph building
        while (!isIndexingComplete() && !cancelled.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                graphBuildingComplete.set(true);
                return;
            }
        }

        if (cancelled.get()) {
            graphBuildingComplete.set(true);
            return;
        }

        try {
            // Drain remaining items from indexing queue
            IndexingOutput lastIndexingOutput = null;
            while (!indexingQueue.isEmpty()) {
                lastIndexingOutput = indexingQueue.poll(100, TimeUnit.MILLISECONDS);
            }

            if (accumulatedChunks.isEmpty()) {
                logger.debug("No chunks to process for graph building");
                graphBuildingComplete.set(true);
                return;
            }

            logger.info("Starting graph building for {} accumulated chunks", accumulatedChunks.size());
            reportProgress("graph-building", calculateProgress(),
                    String.format("Building knowledge graph from %d chunks", accumulatedChunks.size()));

            // Set the chunks on the graph building stage
            graphBuildingStage.setChunksToProcess(new ArrayList<>(accumulatedChunks));

            // Create a synthetic indexing output for the graph building stage
            IndexingOutput syntheticOutput = lastIndexingOutput != null ? lastIndexingOutput :
                    new IndexingOutput(
                            List.of(), chunksIndexed.get(), 0, 0,
                            null, null, null, null, Map.of()
                    );

            // Process graph building
            GraphBuildingStage.GraphBuildingOutput graphOutput = graphBuildingStage.process(syntheticOutput);

            entitiesExtracted.set(graphOutput.entitiesExtracted());
            relationshipsExtracted.set(graphOutput.relationshipsExtracted());

            reportProgress("graph-building", calculateProgress(),
                    String.format("Extracted %d entities, %d relationships",
                            graphOutput.entitiesExtracted(), graphOutput.relationshipsExtracted()));

            logger.info("Graph building complete: {} entities, {} relationships",
                    graphOutput.entitiesExtracted(), graphOutput.relationshipsExtracted());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Graph building interrupted");
        } catch (Exception e) {
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("Graph building worker error: {}", e.getMessage(), e);
        } finally {
            graphBuildingComplete.set(true);
        }
    }

    private boolean isIndexingComplete() {
        return isEmbeddingComplete() && embeddingQueue.isEmpty();
    }

    private boolean isExtractionComplete() {
        return filesExtracted.get() >= filesSubmitted.get() && extractionQueue.isEmpty();
    }

    private boolean isTokenizationComplete() {
        return isExtractionComplete() && tokenizationQueue.isEmpty();
    }

    private boolean isChunkingComplete() {
        return isTokenizationComplete() && chunkingQueue.isEmpty();
    }

    private boolean isEmbeddingComplete() {
        return isChunkingComplete() && embeddingQueue.isEmpty();
    }

    private void awaitCompletion(String taskId) throws InterruptedException {
        // Wait for extraction to complete
        while (!isExtractionComplete() && !cancelled.get()) {
            Thread.sleep(100);
        }

        // Signal end of extraction by waiting for queue drain
        while (!extractionQueue.isEmpty() && !cancelled.get()) {
            Thread.sleep(50);
        }

        // Wait for downstream stages
        int maxWaitSeconds = 3600; // 1 hour max
        int waited = 0;
        while (waited < maxWaitSeconds && !cancelled.get()) {
            if (isEmbeddingComplete() && embeddingQueue.isEmpty()) {
                // Give indexing a bit more time to finish
                Thread.sleep(500);
                if (embeddingQueue.isEmpty()) {
                    break;
                }
            }
            Thread.sleep(100);
            waited++;
        }

        // Wait for graph building to complete if enabled
        if (isGraphBuildingEnabled() && graphBuildingStage.isEnabled()) {
            int graphWaited = 0;
            while (graphWaited < maxWaitSeconds && !cancelled.get() && !graphBuildingComplete.get()) {
                Thread.sleep(100);
                graphWaited++;
            }
        }
    }

    private List<String> collectIndexedIds() {
        // In a real implementation, we'd collect these from the indexing stage
        return List.of();
    }

    private int calculateProgress() {
        if (filesSubmitted.get() == 0) return 0;

        // Weight each stage: extraction=20%, tokenization=10%, chunking=20%, embedding=30%, indexing=20%
        double extractionProgress = (filesExtracted.get() * 20.0) / filesSubmitted.get();
        double tokenizationProgress = (documentsTokenized.get() * 10.0) / Math.max(1, filesExtracted.get() * 5); // Estimate 5 docs/file
        double chunkingProgress = (documentsChunked.get() * 20.0) / Math.max(1, documentsTokenized.get());
        double embeddingProgress = (chunksEmbedded.get() * 30.0) / Math.max(1, documentsChunked.get() * 10); // Estimate 10 chunks/doc
        double indexingProgress = (chunksIndexed.get() * 20.0) / Math.max(1, chunksEmbedded.get());

        return Math.min(99, (int) (extractionProgress + tokenizationProgress + chunkingProgress + embeddingProgress + indexingProgress));
    }

    private double calculateThroughput(long totalTimeMs) {
        return totalTimeMs > 0 ? (chunksIndexed.get() * 1000.0) / totalTimeMs : 0;
    }

    private void reportProgress(String stage, int percent, String message) {
        if (progressCallback != null) {
            StagedPipelineProgress progress = new StagedPipelineProgress(
                    stage, percent, filesExtracted.get(), documentsTokenized.get(),
                    documentsChunked.get(), chunksEmbedded.get(), chunksIndexed.get(),
                    tokensProcessed.get(),
                    entitiesExtracted.get(), relationshipsExtracted.get(),
                    extractionQueue.size(), tokenizationQueue.size(),
                    chunkingQueue.size(), embeddingQueue.size(), indexingQueue.size(),
                    isGraphBuildingEnabled(), graphBuildingComplete.get(),
                    message, getMemoryUsagePercent()
            );
            progressCallback.accept(progress);
        }
    }

    private double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (usedMemory * 100.0) / maxMemory;
    }

    private void resetState() {
        cancelled.set(false);
        filesSubmitted.set(0);
        filesExtracted.set(0);
        documentsTokenized.set(0);
        documentsChunked.set(0);
        chunksEmbedded.set(0);
        chunksIndexed.set(0);
        tokensProcessed.set(0);
        entitiesExtracted.set(0);
        relationshipsExtracted.set(0);
        graphBuildingComplete.set(false);
        extractionQueue.clear();
        tokenizationQueue.clear();
        chunkingQueue.clear();
        embeddingQueue.clear();
        indexingQueue.clear();
        accumulatedChunks.clear();

        extractionStage.reset();
        tokenizationStage.reset();
        chunkingStage.reset();
        embeddingStage.reset();
        indexingStage.reset();
        graphBuildingStage.reset();
    }

    /**
     * Cancels the pipeline processing.
     */
    public void cancel() {
        cancelled.set(true);
        extractionStage.cancel();
        tokenizationStage.cancel();
        chunkingStage.cancel();
        embeddingStage.cancel();
        indexingStage.cancel();
        graphBuildingStage.cancel();
    }

    /**
     * Returns true if the pipeline is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the current settings.
     */
    public PipelineSettings getSettings() {
        return settings;
    }

    /**
     * Returns metrics for all stages.
     */
    public Map<String, PipelineStage.StageMetrics> getAllMetrics() {
        Map<String, PipelineStage.StageMetrics> metrics = new LinkedHashMap<>();
        metrics.put("extraction", extractionStage.getMetrics());
        metrics.put("tokenization", tokenizationStage.getMetrics());
        metrics.put("chunking", chunkingStage.getMetrics());
        metrics.put("embedding", embeddingStage.getMetrics());
        metrics.put("indexing", indexingStage.getMetrics());
        metrics.put("graph-building", graphBuildingStage.getMetrics());
        return metrics;
    }

    @Override
    public void close() {
        cancel();
        shutdownExecutor(extractionExecutor, "extraction");
        shutdownExecutor(tokenizationExecutor, "tokenization");
        shutdownExecutor(chunkingExecutor, "chunking");
        shutdownExecutor(embeddingExecutor, "embedding");
        shutdownExecutor(indexingExecutor, "indexing");
        shutdownExecutor(graphBuildingExecutor, "graph-building");
        logger.debug("StagedIngestPipeline closed");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Progress information during pipeline execution.
     */
    public record StagedPipelineProgress(
            String activeStage,
            int progressPercent,
            int filesExtracted,
            int documentsTokenized,
            int documentsChunked,
            int chunksEmbedded,
            int chunksIndexed,
            long tokensProcessed,
            int entitiesExtracted,
            int relationshipsExtracted,
            int extractionQueueSize,
            int tokenizationQueueSize,
            int chunkingQueueSize,
            int embeddingQueueSize,
            int indexingQueueSize,
            boolean graphBuildingEnabled,
            boolean graphBuildingComplete,
            String message,
            double memoryUsagePercent
    ) {}

    /**
     * Final result of pipeline processing.
     */
    public record PipelineResult(
            int filesProcessed,
            int documentsChunked,
            int chunksIndexed,
            long tokensProcessed,
            int entitiesExtracted,
            int relationshipsExtracted,
            boolean graphBuildingEnabled,
            long totalTimeMs,
            List<String> indexedDocumentIds,
            double chunksPerSecond
    ) {}
}
