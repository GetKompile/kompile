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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services;

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.pipeline.PipelineProgress;
import ai.kompile.app.services.pipeline.PipelineResult;
import ai.kompile.app.services.subprocess.VectorPopulationSubprocessLauncher;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpEmbeddingModelImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.StreamingDocumentLoader;
import ai.kompile.core.retrievers.RetrievedDoc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Service for populating vector store from existing Lucene keyword index.
 * Uses the full ingest pipeline infrastructure (embedding + indexing) but skips chunking
 * since documents in Lucene are already chunked.
 */
@Service
public class VectorStorePopulationService implements org.springframework.beans.factory.DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(VectorStorePopulationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final List<DocumentLoader> documentLoaders;
    private final IndexerService indexerService;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final IngestConfiguration ingestConfiguration;
    private final AppIndexConfigService appIndexConfigService;
    private final VectorPopulationSubprocessLauncher subprocessLauncher;

    @Value("${anserini.indexPath:${anserini.index.path:#{null}}}")
    private String anseriniIndexPath;

    @Value("${kompile.vectorpopulation.subprocess.enabled:true}")
    private boolean subprocessModeEnabled;

    @Value("${kompile.vectorstore.anserini.index-path:${user.home}/.kompile/anserini-vector-index}")
    private String vectorIndexPath;

    // Track active population tasks
    private final Map<String, PopulationTaskStatus> activeTasks = new ConcurrentHashMap<>();
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, ParallelIngestPipeline> activePipelines = new ConcurrentHashMap<>();

    // Progress update throttling
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 100;
    private final AtomicLong lastProgressUpdateTime = new AtomicLong(0);

    // Async WebSocket executor
    private final ExecutorService webSocketExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "vector-pop-websocket-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public VectorStorePopulationService(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired List<DocumentLoader> documentLoaders,
            @Autowired List<IndexerService> indexerServices,
            @Autowired(required = false) List<EmbeddingModel> embeddingModels,
            @Autowired(required = false) List<VectorStore> vectorStores,
            @Autowired(required = false) AppIndexConfigService appIndexConfigService,
            @Autowired(required = false) VectorPopulationSubprocessLauncher subprocessLauncher,
            IngestConfiguration ingestConfiguration) {
        this.messagingTemplate = messagingTemplate;
        this.documentLoaders = documentLoaders;
        this.ingestConfiguration = ingestConfiguration;
        this.appIndexConfigService = appIndexConfigService;
        this.subprocessLauncher = subprocessLauncher;

        // Select best indexer (prefer non-NoOp)
        IndexerService selected = null;
        for (IndexerService svc : indexerServices) {
            if (!(svc instanceof NoOpIndexerService)) {
                selected = svc;
                break;
            }
        }
        this.indexerService = selected != null ? selected : indexerServices.get(0);

        // Select best embedding model (prefer non-NoOp)
        EmbeddingModel selectedModel = null;
        if (embeddingModels != null) {
            for (EmbeddingModel model : embeddingModels) {
                if (!(model instanceof NoOpEmbeddingModelImpl)) {
                    selectedModel = model;
                    break;
                }
            }
            if (selectedModel == null && !embeddingModels.isEmpty()) {
                selectedModel = embeddingModels.get(0);
            }
        }
        this.embeddingModel = selectedModel;

        // Select best vector store (prefer non-NoOp)
        VectorStore selectedVectorStore = null;
        if (vectorStores != null) {
            for (VectorStore store : vectorStores) {
                if (!(store instanceof NoOpVectorStoreImpl)) {
                    selectedVectorStore = store;
                    break;
                }
            }
            if (selectedVectorStore == null && !vectorStores.isEmpty()) {
                selectedVectorStore = vectorStores.get(0);
            }
        }
        this.vectorStore = selectedVectorStore;

        logger.info("VectorStorePopulationService initialized: indexer={}, embeddingModel={}, vectorStore={}, subprocessLauncher={}",
                indexerService.getClass().getSimpleName(),
                embeddingModel != null ? embeddingModel.getModelName() : "none",
                vectorStore != null ? vectorStore.getClass().getSimpleName() : "none",
                subprocessLauncher != null ? "available" : "not available");
    }

    /**
     * Check if subprocess mode is enabled and available.
     */
    public boolean isSubprocessModeEnabled() {
        return subprocessModeEnabled && subprocessLauncher != null;
    }

    /**
     * Enable or disable subprocess mode at runtime.
     */
    public void setSubprocessModeEnabled(boolean enabled) {
        this.subprocessModeEnabled = enabled;
        logger.info("Subprocess mode for vector population: {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Starts asynchronous vector store population from Lucene index.
     * Uses subprocess mode if enabled, otherwise runs in-process.
     *
     * @return Task ID for tracking progress
     */
    @Async
    public CompletableFuture<PopulationResult> populateVectorStoreAsync(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }

        final String finalTaskId = taskId;

        // Use subprocess mode if enabled
        if (isSubprocessModeEnabled()) {
            logger.info("Starting vector population in SUBPROCESS mode for task: {}", finalTaskId);
            return populateVectorStoreViaSubprocess(finalTaskId);
        }

        // Fall back to in-process mode
        logger.info("Starting vector population in IN-PROCESS mode for task: {}", finalTaskId);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return populateVectorStore(finalTaskId);
            } catch (Exception e) {
                logger.error("Vector population failed: {}", e.getMessage(), e);
                return new PopulationResult(finalTaskId, false, 0, 0, e.getMessage());
            }
        });
    }

    /**
     * Populate vector store using subprocess for process isolation.
     * This prevents native crashes from affecting the main application.
     */
    private CompletableFuture<PopulationResult> populateVectorStoreViaSubprocess(String taskId) {
        try {
            // Resolve paths
            String keywordPath = resolveKeywordIndexPath();
            String vectorPath = resolveVectorIndexPath();

            if (keywordPath == null || keywordPath.isBlank()) {
                return CompletableFuture.completedFuture(
                        new PopulationResult(taskId, false, 0, 0,
                                "Keyword index path is not configured"));
            }

            // Build options
            Map<String, Object> options = new HashMap<>();
            options.put("embeddingBatchSize", ingestConfiguration.getEmbeddingTargetBatchSize());
            options.put("parallelIndexing", true);
            options.put("indexingWorkers", 4);

            // Launch subprocess
            return subprocessLauncher.launchVectorPopulation(taskId, keywordPath, vectorPath, options)
                    .thenApply(result -> {
                        if (result.success()) {
                            return new PopulationResult(taskId, true,
                                    result.documentsIndexed(),
                                    result.totalDurationMs(),
                                    null);
                        } else {
                            return new PopulationResult(taskId, false, 0, 0,
                                    result.errorMessage());
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("Subprocess vector population failed: {}", ex.getMessage(), ex);
                        return new PopulationResult(taskId, false, 0, 0, ex.getMessage());
                    });

        } catch (Exception e) {
            logger.error("Failed to launch subprocess for vector population: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(
                    new PopulationResult(taskId, false, 0, 0, e.getMessage()));
        }
    }

    /**
     * Resolve the keyword index path from available sources.
     */
    private String resolveKeywordIndexPath() {
        // Try config service first
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getKeywordIndexPath() != null && !config.getKeywordIndexPath().isBlank()) {
                return config.getKeywordIndexPath();
            }
        }

        // Fall back to property
        if (anseriniIndexPath != null && !anseriniIndexPath.isBlank()) {
            return anseriniIndexPath;
        }

        // Try indexer service
        if (indexerService != null) {
            try {
                return indexerService.getIndexPath();
            } catch (Exception e) {
                logger.trace("Could not get index path from indexer: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * Resolve the vector index path from available sources.
     */
    private String resolveVectorIndexPath() {
        // Try config service first
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getVectorStorePath() != null && !config.getVectorStorePath().isBlank()) {
                return config.getVectorStorePath();
            }
        }

        // Fall back to property
        return vectorIndexPath;
    }

    /**
     * Synchronously populate vector store from Lucene index using pipeline.
     */
    public PopulationResult populateVectorStore(String taskId) throws IOException, InterruptedException {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }

        // Try to get keyword index path from config service first, then fall back to property
        String indexPath = null;
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getKeywordIndexPath() != null && !config.getKeywordIndexPath().isBlank()) {
                indexPath = config.getKeywordIndexPath();
                logger.debug("Using keyword index path from config service: {}", indexPath);
            }
        }

        // Fall back to property if not set in config
        if (indexPath == null || indexPath.isBlank()) {
            indexPath = anseriniIndexPath;
        }

        // Also try to get from indexer service directly
        if ((indexPath == null || indexPath.isBlank()) && indexerService != null) {
            try {
                indexPath = indexerService.getIndexPath();
                logger.debug("Using keyword index path from indexer service: {}", indexPath);
            } catch (Exception e) {
                logger.trace("Could not get index path from indexer service: {}", e.getMessage());
            }
        }

        if (indexPath == null || indexPath.isBlank()) {
            throw new IOException("Keyword index path is not configured. Please configure the keyword index path in Settings.");
        }

        Path indexDir = Paths.get(indexPath);
        if (!Files.exists(indexDir) || !Files.isDirectory(indexDir)) {
            throw new IOException("Anserini index directory does not exist: " + indexPath);
        }

        logger.info("Starting vector store population from Lucene index: {} (taskId={}) - VECTOR-ONLY mode (skipping keyword re-indexing)", indexPath, taskId);

        // Find LuceneIndexLoader
        DocumentLoader luceneLoader = documentLoaders.stream()
                .filter(l -> l.getClass().getSimpleName().contains("LuceneIndex"))
                .findFirst()
                .orElseThrow(() -> new IOException("LuceneIndexLoader not found"));

        // Get total document count
        long totalDocs = indexerService.getApproxTotalDocCount(null);
        logger.info("Found {} documents in Lucene index", totalDocs);

        // Initialize task status
        PopulationTaskStatus status = new PopulationTaskStatus(taskId, totalDocs);
        activeTasks.put(taskId, status);

        // Broadcast initial status
        broadcastProgress(taskId, IngestPhase.LOADING, 0, "Starting vector population...",
                0, 0, 0, totalDocs, 0.0);

        // Create pipeline (null chunker = skip chunking)
        // IMPORTANT: Use vectorOnlyMode=true to skip keyword indexing since documents are already in the keyword index
        int batchSize = ingestConfiguration.getEmbeddingTargetBatchSize();
        ParallelIngestPipeline pipeline = new ParallelIngestPipeline(
                null, // No chunker - documents are already chunked
                embeddingModel,
                indexerService,
                null, // No chunking options
                batchSize,
                true,  // Enable parallel indexing (for vector store only)
                true   // vectorOnlyMode - skip keyword index since we're populating from it
        );
        activePipelines.put(taskId, pipeline);

        // Set up progress callback
        final String finalTaskId = taskId;
        pipeline.setProgressCallback(progress -> handlePipelineProgress(finalTaskId, progress, totalDocs));

        try {
            // Load documents from Lucene and process through pipeline
            List<RetrievedDoc> chunks = loadDocumentsFromLucene(luceneLoader, indexPath, taskId, totalDocs);

            if (cancelledTasks.contains(taskId)) {
                logger.info("Vector population cancelled: {}", taskId);
                return new PopulationResult(taskId, false, 0, 0, "Cancelled");
            }

            // Process through pipeline (skips chunking)
            PipelineResult result = pipeline.processPreChunked(chunks);

            long duration = result.totalTimeMs();
            double rate = duration > 0 ? (result.chunksIndexed() * 1000.0 / duration) : 0;

            // VERIFICATION: Check actual vector store count to ensure persistence
            long actualVectorCount = -1;
            if (vectorStore != null) {
                // Refresh the reader to see newly committed documents
                vectorStore.refreshReader();
                actualVectorCount = vectorStore.getApproxVectorCount();
            }

            // Log the reported vs actual counts
            logger.info("Vector population complete: reported={} indexed, actual vector store count={}, duration={}ms ({} docs/sec)",
                    result.chunksIndexed(), actualVectorCount, duration, String.format("%.1f", rate));

            // Warn if there's a significant discrepancy (>1% or more than 10 docs difference)
            if (actualVectorCount >= 0 && result.chunksIndexed() > 0) {
                long discrepancy = Math.abs(actualVectorCount - result.chunksIndexed());
                double discrepancyPercent = (discrepancy * 100.0) / result.chunksIndexed();
                if (discrepancy > 10 && discrepancyPercent > 1.0) {
                    logger.warn("VECTOR STORE COUNT DISCREPANCY: reported {} indexed but actual count is {} (discrepancy: {} = {:.1f}%)",
                            result.chunksIndexed(), actualVectorCount, discrepancy, discrepancyPercent);
                }
            }

            // Use the actual vector store count if available, otherwise use reported count
            int confirmedIndexed = actualVectorCount >= 0 ? (int) actualVectorCount : result.chunksIndexed();

            // Broadcast completion with verified count
            broadcastProgress(taskId, IngestPhase.COMPLETED, 100,
                    String.format("Complete: %d docs verified in vector store (%.1f/sec)", confirmedIndexed, rate),
                    result.documentsProcessed(), result.chunksCreated(), confirmedIndexed,
                    totalDocs, rate);

            status.complete(confirmedIndexed, duration);

            return new PopulationResult(taskId, true, confirmedIndexed, duration, null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Vector population interrupted: {}", taskId);
            broadcastProgress(taskId, IngestPhase.FAILED, 0, "Interrupted", 0, 0, 0, totalDocs, 0.0);
            return new PopulationResult(taskId, false, 0, 0, "Interrupted");
        } catch (Exception e) {
            logger.error("Vector population failed: {}", e.getMessage(), e);
            broadcastProgress(taskId, IngestPhase.FAILED, 0, "Error: " + e.getMessage(), 0, 0, 0, totalDocs, 0.0);
            return new PopulationResult(taskId, false, 0, 0, e.getMessage());
        } finally {
            activePipelines.remove(taskId);
            try {
                pipeline.close();
            } catch (Exception e) {
                logger.debug("Error closing pipeline: {}", e.getMessage());
            }
        }
    }

    /**
     * Load documents from Lucene index using streaming loader.
     */
    private List<RetrievedDoc> loadDocumentsFromLucene(
            DocumentLoader loader,
            String indexPath,
            String taskId,
            long totalDocs) throws Exception {

        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(indexPath)
                .originalFileName("lucene-index")
                .sourceId("lucene-index-import")
                .build();

        List<RetrievedDoc> chunks = new ArrayList<>();

        if (loader instanceof StreamingDocumentLoader) {
            StreamingDocumentLoader streamingLoader = (StreamingDocumentLoader) loader;
            Iterator<Document> iterator = streamingLoader.streamPages(descriptor, null);

            int loaded = 0;
            while (iterator.hasNext()) {
                if (cancelledTasks.contains(taskId) || Thread.currentThread().isInterrupted()) {
                    break;
                }

                Document doc = iterator.next();
                chunks.add(convertToRetrievedDoc(doc));
                loaded++;

                // Report loading progress
                if (loaded % 500 == 0 || loaded == totalDocs) {
                    int percent = totalDocs > 0 ? (int) ((loaded * 25) / totalDocs) : 0;
                    broadcastProgress(taskId, IngestPhase.LOADING, percent,
                            String.format("Loading %d/%d documents", loaded, totalDocs),
                            loaded, 0, 0, totalDocs, 0.0);
                }
            }

            logger.info("Loaded {} documents from Lucene index", loaded);
        } else {
            // Non-streaming fallback
            List<Document> docs = loader.load(descriptor);
            for (Document doc : docs) {
                chunks.add(convertToRetrievedDoc(doc));
            }
            logger.info("Loaded {} documents (non-streaming)", docs.size());
        }

        return chunks;
    }

    /**
     * Convert Spring AI Document to RetrievedDoc.
     */
    private RetrievedDoc convertToRetrievedDoc(Document doc) {
        Map<String, Object> metadata = doc.getMetadata() != null
                ? new HashMap<>(doc.getMetadata())
                : new HashMap<>();

        return RetrievedDoc.builder()
                .id(doc.getId() != null ? doc.getId() : UUID.randomUUID().toString())
                .text(doc.getText())
                .metadata(metadata)
                .build();
    }

    /**
     * Handle progress updates from pipeline.
     */
    private void handlePipelineProgress(String taskId, PipelineProgress progress, long totalDocs) {
        if (cancelledTasks.contains(taskId)) {
            return;
        }

        // Map pipeline phase to ingest phase
        IngestPhase phase = mapPhase(progress.phase());

        // Calculate overall progress (loading=0-25%, embedding=25-75%, indexing=75-99%, complete=100%)
        int overallPercent;
        if ("complete".equals(progress.phase())) {
            overallPercent = 100;
        } else if ("indexing".equals(progress.phase())) {
            overallPercent = 75 + (progress.progressPercent() * 24 / 100);
        } else if ("embedding".equals(progress.phase())) {
            overallPercent = 25 + (progress.progressPercent() * 50 / 100);
        } else {
            overallPercent = progress.progressPercent();
        }

        // Calculate throughput - use chunksPerSecond from progress
        double rate = progress.chunksPerSecond();

        // Calculate elapsed time from task status
        PopulationTaskStatus status = activeTasks.get(taskId);
        long elapsedMs = status != null ? status.getElapsedMs() : 0;

        // Build detailed progress with worker statuses and queue info
        broadcastDetailedProgress(taskId, phase, overallPercent, progress, totalDocs, rate, elapsedMs);

        // Update task status
        if (status != null) {
            status.updateProgress(progress.chunksIndexed(), overallPercent);
        }
    }

    /**
     * Broadcast detailed progress update via WebSocket including worker statuses.
     */
    private void broadcastDetailedProgress(String taskId, IngestPhase phase, int progressPercent,
                                           PipelineProgress progress, long totalDocs,
                                           double rate, long elapsedMs) {
        // Throttle updates
        long now = System.currentTimeMillis();
        long last = lastProgressUpdateTime.get();
        if (now - last < PROGRESS_UPDATE_INTERVAL_MS && progressPercent < 100) {
            return;
        }
        lastProgressUpdateTime.set(now);

        if (messagingTemplate == null) {
            return;
        }

        // Build stats with worker details
        IngestStats.Builder statsBuilder = IngestStats.builder()
                .documentsLoaded((int) totalDocs)
                .docsPerSecond(rate)
                .totalProcessingTimeMs(elapsedMs)
                .documentsLoaded(progress.documentsProcessed())
                .chunksCreated(progress.chunksCreated())
                .chunksIndexed(progress.chunksIndexed())
                .chunksEmbedded(progress.chunksEmbedded());

        // Add worker statuses if available
        if (progress.workerStatuses() != null && !progress.workerStatuses().isEmpty()) {
            List<IngestProgressUpdate.WorkerStatusDto> workerDtos = progress.workerStatuses().stream()
                    .map(ws -> new IngestProgressUpdate.WorkerStatusDto(
                            ws.workerId(),
                            ws.workerType(),
                            ws.status(),
                            ws.itemsProcessed(),
                            ws.currentBatchSize(),
                            ws.throughput(),
                            ws.currentItem()))
                    .collect(java.util.stream.Collectors.toList());
            statsBuilder.workerStatuses(workerDtos);
        }

        // Add queue status if available
        if (progress.queueStatus() != null) {
            var qs = progress.queueStatus();
            statsBuilder.queueStatus(new IngestProgressUpdate.QueueStatusDto(
                    qs.chunkQueueSize(),
                    qs.chunkQueueCapacity(),
                    qs.embeddedQueueSize(),
                    qs.embeddedQueueCapacity(),
                    qs.chunkQueueUtilization(),
                    qs.embeddedQueueUtilization()));
        }

        // Add embedding batch metrics if available
        if (progress.currentEmbeddingBatch() != null) {
            var batch = progress.currentEmbeddingBatch();
            statsBuilder.currentEmbeddingBatch(IngestProgressUpdate.EmbeddingBatchMetrics.builder()
                    .batchNumber(batch.batchNumber())
                    .totalBatches(batch.totalBatches())
                    .inputTexts(batch.inputTexts())
                    .inputTokens(batch.inputTokens())
                    .currentStep(batch.currentStep())
                    .heartbeatSeconds(batch.heartbeatSeconds())
                    .forwardPassTimeMs(batch.forwardPassTimeMs())
                    .totalBatchTimeMs(batch.totalBatchTimeMs())
                    .embeddingDimension(batch.embeddingDimension())
                    .isStuck(batch.isStuck())
                    .sourceDocuments(batch.sourceDocuments())
                    .sourceDocumentCount(batch.sourceDocumentCount())
                    .inputTensorShape(batch.inputTensorShape())
                    .outputTensorShape(batch.outputTensorShape())
                    .batchThroughput(batch.batchThroughput())
                    .build());
        }

        IngestStats stats = statsBuilder.build();

        // Build progress update using factory method
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId,
                null, // fileName not applicable for vector population
                phase,
                progressPercent,
                progress.phase(),
                progress.message(),
                stats);

        // Send async to avoid blocking pipeline
        webSocketExecutor.submit(() -> {
            try {
                messagingTemplate.convertAndSend("/topic/vector-population/progress", update);
            } catch (Exception e) {
                logger.trace("Failed to send WebSocket update: {}", e.getMessage());
            }
        });
    }

    /**
     * Map pipeline phase string to IngestPhase enum.
     */
    private IngestPhase mapPhase(String phase) {
        if (phase == null) return IngestPhase.LOADING;
        return switch (phase.toLowerCase()) {
            case "starting", "queueing" -> IngestPhase.LOADING;
            case "embedding" -> IngestPhase.EMBEDDING;
            case "indexing" -> IngestPhase.INDEXING;
            case "complete", "completed" -> IngestPhase.COMPLETED;
            default -> IngestPhase.LOADING;
        };
    }

    /**
     * Broadcast progress update via WebSocket.
     */
    private void broadcastProgress(String taskId, IngestPhase phase, int progressPercent,
                                   String message, long documents, long chunks,
                                   long indexed, long total, double rate) {
        // Throttle updates
        long now = System.currentTimeMillis();
        long last = lastProgressUpdateTime.get();
        if (now - last < PROGRESS_UPDATE_INTERVAL_MS && progressPercent < 100) {
            return;
        }
        lastProgressUpdateTime.set(now);

        if (messagingTemplate == null) {
            return;
        }

        // Fetch actual index counts periodically (every ~1 second during indexing)
        Long actualKeywordCount = null;
        Long actualVectorCount = null;
        if (phase == IngestPhase.INDEXING || phase == IngestPhase.COMPLETED) {
            try {
                if (indexerService != null) {
                    actualKeywordCount = indexerService.getApproxTotalDocCount(null);
                }
                if (vectorStore != null) {
                    // Refresh to see latest committed documents
                    vectorStore.refreshReader();
                    actualVectorCount = vectorStore.getApproxVectorCount();
                }
            } catch (Exception e) {
                logger.trace("Could not fetch actual index counts: {}", e.getMessage());
            }
        }

        // Build stats with actual counts
        IngestStats stats = IngestStats.builder()
                .documentsLoaded((int) documents)
                .chunksCreated((int) chunks)
                .chunksIndexed((int) indexed)
                .actualKeywordIndexCount(actualKeywordCount)
                .actualVectorStoreCount(actualVectorCount)
                .docsPerSecond(rate)
                .build();

        // Build progress update using factory method
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId,
                null, // fileName not applicable for vector population
                phase,
                progressPercent,
                phase.name(),
                message,
                stats);

        // Send async to avoid blocking pipeline
        webSocketExecutor.submit(() -> {
            try {
                messagingTemplate.convertAndSend("/topic/vector-population/progress", update);
            } catch (Exception e) {
                logger.trace("Failed to send WebSocket update: {}", e.getMessage());
            }
        });
    }

    /**
     * Cancel an active population task.
     * Works with both in-process and subprocess modes.
     */
    public boolean cancelTask(String taskId) {
        // Try subprocess cancellation first
        if (subprocessLauncher != null && subprocessLauncher.cancelVectorPopulation(taskId)) {
            logger.info("Cancelled vector population subprocess: {}", taskId);
            return true;
        }

        // Fall back to in-process cancellation
        if (!activeTasks.containsKey(taskId)) {
            return false;
        }

        cancelledTasks.add(taskId);

        ParallelIngestPipeline pipeline = activePipelines.get(taskId);
        if (pipeline != null) {
            pipeline.cancel();
        }

        logger.info("Cancelled vector population task: {}", taskId);
        return true;
    }

    /**
     * Get status of a population task.
     */
    public PopulationTaskStatus getTaskStatus(String taskId) {
        return activeTasks.get(taskId);
    }

    /**
     * Get all active tasks.
     */
    public Map<String, PopulationTaskStatus> getActiveTasks() {
        return new HashMap<>(activeTasks);
    }

    @Override
    public void destroy() throws Exception {
        webSocketExecutor.shutdown();
        try {
            if (!webSocketExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                webSocketExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            webSocketExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== Result/Status classes ==========

    public record PopulationResult(
            String taskId,
            boolean success,
            long documentsIndexed,
            long durationMs,
            String errorMessage
    ) {}

    public static class PopulationTaskStatus {
        private final String taskId;
        private final long totalDocuments;
        private final long startTime;
        private volatile long documentsIndexed;
        private volatile int progressPercent;
        private volatile boolean complete;
        private volatile long endTime;

        public PopulationTaskStatus(String taskId, long totalDocuments) {
            this.taskId = taskId;
            this.totalDocuments = totalDocuments;
            this.startTime = System.currentTimeMillis();
        }

        public void updateProgress(long indexed, int percent) {
            this.documentsIndexed = indexed;
            this.progressPercent = percent;
        }

        public void complete(long indexed, long durationMs) {
            this.documentsIndexed = indexed;
            this.progressPercent = 100;
            this.complete = true;
            this.endTime = startTime + durationMs;
        }

        public String getTaskId() { return taskId; }
        public long getTotalDocuments() { return totalDocuments; }
        public long getStartTime() { return startTime; }
        public long getDocumentsIndexed() { return documentsIndexed; }
        public int getProgressPercent() { return progressPercent; }
        public boolean isComplete() { return complete; }
        public long getEndTime() { return endTime; }
        public long getElapsedMs() {
            return complete ? endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }
}
