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
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.pipeline.PipelineProgress;
import ai.kompile.app.services.pipeline.PipelineResult;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.services.scheduler.ScheduledJob;
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
 * Uses the full ingest pipeline infrastructure (embedding + indexing) but skips
 * chunking
 * since documents in Lucene are already chunked.
 */
@Service
public class VectorStorePopulationService implements org.springframework.beans.factory.DisposableBean {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected VectorStorePopulationService() {}


    private static final Logger logger = LoggerFactory.getLogger(VectorStorePopulationService.class);

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private List<DocumentLoader> documentLoaders;
    @Autowired
    private IndexerService indexerService;
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private VectorStore vectorStore;
    @Autowired
    private IngestConfiguration ingestConfiguration;
    @Autowired(required = false)
    private AppIndexConfigService appIndexConfigService;
    @Autowired(required = false)
    private VectorPopulationSubprocessLauncher subprocessLauncher;
    @Autowired(required = false)
    private IngestProgressTracker ingestProgressTracker;
    @Autowired(required = false)
    private IndexingJobHistoryService jobHistoryService;
    @Autowired(required = false)
    private ResourceAwareJobScheduler resourceScheduler;

    @Value("${kompile.vectorpopulation.subprocess.enabled:true}")
    private volatile boolean subprocessModeEnabled;

    // Track active population tasks
    private final Map<String, PopulationTaskStatus> activeTasks = new ConcurrentHashMap<>();
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, ParallelIngestPipeline> activePipelines = new ConcurrentHashMap<>();
    private final Map<String, String> taskDisplayNames = new ConcurrentHashMap<>();
    private final Map<String, IngestPhase> taskLastPhases = new ConcurrentHashMap<>();

    // Progress update throttling
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 100;
    private final AtomicLong lastProgressUpdateTime = new AtomicLong(0);

    // Vector store verification throttling (check every 2 seconds)
    private final AtomicLong lastVectorStoreCheckTime = new AtomicLong(0);
    private final AtomicLong cachedVectorStoreCount = new AtomicLong(-1);

    private static final String VECTOR_POPULATION_CONTENT_TYPE = "vector-population";

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
            @Autowired(required = false) IngestProgressTracker ingestProgressTracker,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService,
            @Autowired(required = false) ResourceAwareJobScheduler resourceScheduler,
            IngestConfiguration ingestConfiguration) {
        this.messagingTemplate = messagingTemplate;
        this.documentLoaders = documentLoaders;
        this.ingestConfiguration = ingestConfiguration;
        this.appIndexConfigService = appIndexConfigService;
        this.subprocessLauncher = subprocessLauncher;
        this.ingestProgressTracker = ingestProgressTracker;
        this.jobHistoryService = jobHistoryService;
        this.resourceScheduler = resourceScheduler;

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

        logger.info(
                "VectorStorePopulationService initialized: indexer={}, embeddingModel={}, vectorStore={}, subprocessLauncher={}",
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
     * Submit a vector population job through the resource-aware scheduler.
     * Falls back to direct async execution if the scheduler is unavailable.
     */
    public CompletableFuture<ScheduledJob.JobResult> scheduleVectorPopulation(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }

        if (resourceScheduler == null) {
            logger.debug("No scheduler available, falling back to direct vector population");
            final String finalTaskId = taskId;
            populateVectorStoreAsync(finalTaskId);
            return CompletableFuture.completedFuture(
                    new ScheduledJob.JobResult(true, null, 0));
        }

        final String finalTaskId = taskId;
        ScheduledJob job = ScheduledJob.builder()
                .jobId(finalTaskId)
                .jobType(JobResourceProfiles.VECTOR_POPULATION.serviceType())
                .description("Vector Population: " + finalTaskId)
                .resourceProfile(JobResourceProfiles.VECTOR_POPULATION)
                .priority(30)
                .executor(ctx -> {
                    PopulationResult result = populateVectorStore(finalTaskId);
                    if (!result.success()) {
                        throw new RuntimeException("Vector population failed: " + result.errorMessage());
                    }
                })
                .build();

        logger.info("Submitting vector population job to scheduler: {}", finalTaskId);
        return resourceScheduler.submit(job);
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
            // === SCHEDULER INTEGRATION: Submit through scheduler for queuing, priority, and history ===
            if (resourceScheduler != null) {
                logger.info("Starting vector population via SCHEDULER for task: {}", finalTaskId);
                ScheduledJob job = ScheduledJob.builder()
                        .jobId(finalTaskId)
                        .jobType("vectorPopulation")
                        .description("Vector population: " + finalTaskId)
                        .resourceProfile(JobResourceProfiles.VECTOR_POPULATION)
                        .executor(ctx -> {
                            try {
                                PopulationResult result = populateVectorStoreViaSubprocess(finalTaskId).get();
                                if (!result.success()) {
                                    throw new RuntimeException("Vector population failed: " + result.errorMessage());
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Vector population interrupted", e);
                            } catch (ExecutionException e) {
                                throw new RuntimeException("Vector population failed: " + e.getCause().getMessage(), e.getCause());
                            }
                        })
                        .priority(40)
                        .build();
                // Broadcast QUEUED state so the UI shows the task immediately
                broadcastProgress(finalTaskId, IngestPhase.QUEUED, 0, "Queued for processing",
                        0, 0, 0, 0, 0.0);
                return resourceScheduler.submit(job).thenApply(jr ->
                        new PopulationResult(finalTaskId, jr.success(), 0, jr.durationMs(), jr.errorMessage()));
            }

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
            // NOTE: Do NOT set embeddingBatchSize here - let it fall through to
            // AnseriniEmbeddingProperties which is controlled by the UI batch size settings
            Map<String, Object> options = new HashMap<>();
            options.put("parallelIndexing", true);
            options.put("indexingWorkers", 4);

            // Launch subprocess
            final String finalVectorPath = vectorPath;
            return subprocessLauncher.launchVectorPopulation(taskId, keywordPath, vectorPath, options)
                    .thenApply(result -> {
                        if (result.success()) {
                            // Ensure main app's VectorStore is pointing to the correct path and refreshed
                            ensureVectorStoreAtPath(finalVectorPath);
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
        // 1. Try AppIndexConfigService stored config (not getActualConfiguration which queries live services)
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getKeywordIndexPath() != null && !config.getKeywordIndexPath().isBlank()) {
                logger.info("Resolved keyword index path from stored config: {}", config.getKeywordIndexPath());
                return config.getKeywordIndexPath();
            }
        }

        // 2. Try indexer service
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
     * Uses a fallback chain to ensure a path is always returned:
     * 1. AppIndexConfigService (FactSheet-aware consolidated config)
     * 2. Current VectorStore instance path
     * 3. Default path based on user home
     */
    private String resolveVectorIndexPath() {
        // 1. Try AppIndexConfigService stored config (not getActualConfiguration which queries live services)
        // This ensures we use the configured path from app-index-config.json, not the VectorStore's current state
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getVectorStorePath() != null && !config.getVectorStorePath().isBlank()) {
                logger.info("Resolved vector index path from stored config: {}", config.getVectorStorePath());
                return config.getVectorStorePath();
            }
        }

        // 2. Try actual VectorStore instance path (fallback if config not available)
        if (vectorStore != null) {
            String storePath = vectorStore.getVectorStorePath();
            if (storePath != null && !storePath.isBlank() && !"N/A".equals(storePath)) {
                logger.info("Resolved vector index path from VectorStore instance: {}", storePath);
                return storePath;
            }
            // Also try getIndexPath() if available
            String indexPath = vectorStore.getIndexPath();
            if (indexPath != null && !indexPath.isBlank()) {
                logger.info("Resolved vector index path from VectorStore.getIndexPath(): {}", indexPath);
                return indexPath;
            }
        }

        // 3. Default fallback path
        String defaultPath = System.getProperty("user.home") + "/.kompile/models/anserini/indexes/vector_index";
        logger.warn("Could not resolve vector index path from config or VectorStore, using default: {}", defaultPath);
        return defaultPath;
    }

    /**
     * Ensures the main app's VectorStore is pointing to the correct path and refreshed.
     * This is called after subprocess completion to ensure the main app can read the new data.
     */
    private void ensureVectorStoreAtPath(String targetPath) {
        if (vectorStore == null || targetPath == null) {
            logger.warn("Cannot ensure VectorStore at path: vectorStore={}, targetPath={}",
                    vectorStore != null ? "present" : "null", targetPath);
            return;
        }

        try {
            String currentPath = vectorStore.getIndexPath();
            logger.info("Ensuring VectorStore at path: target={}, current={}", targetPath, currentPath);

            // Switch path if different
            if (!targetPath.equals(currentPath)) {
                logger.info("Switching VectorStore from {} to {}", currentPath, targetPath);
                boolean switched = vectorStore.switchIndexPath(targetPath);
                if (switched) {
                    logger.info("Successfully switched VectorStore to {}", targetPath);
                } else {
                    logger.error("Failed to switch VectorStore to {}", targetPath);
                }
            }

            // Always refresh reader to see newly written data
            vectorStore.refreshReader();
            long count = vectorStore.getApproxVectorCount();
            logger.info("VectorStore at {} now has {} vectors", targetPath, count);

        } catch (Exception e) {
            logger.error("Error ensuring VectorStore at path {}: {}", targetPath, e.getMessage(), e);
        }
    }

    /**
     * Synchronously populate vector store from Lucene index using pipeline.
     */
    public PopulationResult populateVectorStore(String taskId) throws IOException, InterruptedException {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }

        String indexPath = null;
        // Try to get keyword index path from config service first, then fall back to
        // property
        if (appIndexConfigService != null) {
            AppIndexConfig config = appIndexConfigService.getConfiguration();
            if (config != null && config.getKeywordIndexPath() != null && !config.getKeywordIndexPath().isBlank()) {
                indexPath = config.getKeywordIndexPath();
                logger.debug("Using keyword index path from config service: {}", indexPath);
            }
        }

        // Fall back to property if not set in config
        if (indexPath == null) {
            indexPath = resolveKeywordIndexPath();
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
            throw new IOException(
                    "Keyword index path is not configured. Please configure the keyword index path in Settings.");
        }

        Path indexDir = Paths.get(indexPath);
        if (!Files.exists(indexDir) || !Files.isDirectory(indexDir)) {
            throw new IOException("Anserini index directory does not exist: " + indexPath);
        }

        logger.info(
                "Starting vector store population from Lucene index: {} (taskId={}) - VECTOR-ONLY mode (skipping keyword re-indexing)",
                indexPath, taskId);

        String vectorPath = resolveVectorIndexPath();
        startIngestTracking(taskId, indexPath, vectorPath);

        long totalDocs = 0;
        PopulationTaskStatus status = null;
        ParallelIngestPipeline pipeline = null;

        try {
            // Find LuceneIndexLoader
            DocumentLoader luceneLoader = documentLoaders.stream()
                    .filter(l -> l.getClass().getSimpleName().contains("LuceneIndex"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("LuceneIndexLoader not found"));

            // Get total document count
            totalDocs = indexerService.getApproxTotalDocCount(null);
            logger.info("Found {} documents in Lucene index", totalDocs);

            // Initialize task status
            status = new PopulationTaskStatus(taskId, totalDocs);
            activeTasks.put(taskId, status);

            // Broadcast initial status
            broadcastProgress(taskId, IngestPhase.LOADING, 0, "Starting vector population...",
                    0, 0, 0, totalDocs, 0.0);

            // Create pipeline (null chunker = skip chunking)
            // IMPORTANT: Use vectorOnlyMode=true to skip keyword indexing since documents
            // are already in the keyword index
            int batchSize = ingestConfiguration.getEmbeddingTargetBatchSize();
            pipeline = new ParallelIngestPipeline(
                    null, // No chunker - documents are already chunked
                    embeddingModel,
                    indexerService,
                    null, // No chunking options
                    batchSize,
                    true, // Enable parallel indexing (for vector store only)
                    true // vectorOnlyMode - skip keyword index since we're populating from it
            );
            activePipelines.put(taskId, pipeline);

            // Set up progress callback
            final String finalTaskId = taskId;
            long finalTotalDocs = totalDocs;
            pipeline.setProgressCallback(progress -> handlePipelineProgress(finalTaskId, progress, finalTotalDocs));

            // Load documents from Lucene and process through pipeline
            List<RetrievedDoc> chunks = loadDocumentsFromLucene(luceneLoader, indexPath, taskId, totalDocs);

            if (cancelledTasks.contains(taskId)) {
                logger.info("Vector population cancelled: {}", taskId);
                cancelIngestTracking(taskId, "Cancelled");
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
            logger.info(
                    "Vector population complete: reported={} indexed, actual vector store count={}, duration={}ms ({} docs/sec)",
                    result.chunksIndexed(), actualVectorCount, duration, String.format("%.1f", rate));

            // Warn if there's a significant discrepancy (>1% or more than 10 docs
            // difference)
            if (actualVectorCount >= 0 && result.chunksIndexed() > 0) {
                long discrepancy = Math.abs(actualVectorCount - result.chunksIndexed());
                double discrepancyPercent = (discrepancy * 100.0) / result.chunksIndexed();
                if (discrepancy > 10 && discrepancyPercent > 1.0) {
                    logger.warn(
                            "VECTOR STORE COUNT DISCREPANCY: reported {} indexed but actual count is {} (discrepancy: {} = {}%)",
                            result.chunksIndexed(), actualVectorCount, discrepancy, String.format("%.1f", discrepancyPercent));
                }
            }

            // Use the actual vector store count if available, otherwise use reported count
            int confirmedIndexed = actualVectorCount >= 0 ? (int) actualVectorCount : result.chunksIndexed();
            Long actualKeywordCount = null;
            if (indexerService != null) {
                try {
                    actualKeywordCount = indexerService.getApproxTotalDocCount(null);
                } catch (Exception e) {
                    logger.trace("Could not fetch actual keyword index count: {}", e.getMessage());
                }
            }

            // Broadcast completion with verified count
            broadcastProgress(taskId, IngestPhase.COMPLETED, 100,
                    String.format("Complete: %d docs verified in vector store (%.1f/sec)", confirmedIndexed, rate),
                    result.documentsProcessed(), result.chunksCreated(), confirmedIndexed,
                    totalDocs, rate);

            status.complete(confirmedIndexed, duration);
            IngestStats finalStats = IngestStats.builder()
                    .documentsLoaded(result.documentsProcessed())
                    .chunksCreated(result.chunksCreated())
                    .chunksEmbedded(result.chunksIndexed())
                    .chunksIndexed(confirmedIndexed)
                    .documentsIndexed(confirmedIndexed)
                    .actualKeywordIndexCount(actualKeywordCount)
                    .actualVectorStoreCount(actualVectorCount >= 0 ? actualVectorCount : null)
                    .totalProcessingTimeMs(duration)
                    .docsPerSecond(rate)
                    .build();
            completeIngestTracking(taskId, finalStats);

            return new PopulationResult(taskId, true, confirmedIndexed, duration, null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Vector population interrupted: {}", taskId);
            if (cancelledTasks.contains(taskId)) {
                cancelIngestTracking(taskId, "Cancelled");
                return new PopulationResult(taskId, false, 0, 0, "Cancelled");
            }
            broadcastProgress(taskId, IngestPhase.FAILED, 0, "Interrupted", 0, 0, 0, totalDocs, 0.0);
            IngestPhase lastPhase = taskLastPhases.getOrDefault(taskId, IngestPhase.LOADING);
            failIngestTracking(taskId, lastPhase, "Interrupted");
            return new PopulationResult(taskId, false, 0, 0, "Interrupted");
        } catch (Exception e) {
            logger.error("Vector population failed: {}", e.getMessage(), e);
            broadcastProgress(taskId, IngestPhase.FAILED, 0, "Error: " + e.getMessage(), 0, 0, 0, totalDocs, 0.0);
            IngestPhase lastPhase = taskLastPhases.getOrDefault(taskId, IngestPhase.LOADING);
            failIngestTracking(taskId, lastPhase, e.getMessage());
            return new PopulationResult(taskId, false, 0, 0, e.getMessage());
        } finally {
            activePipelines.remove(taskId);
            if (pipeline != null) {
                try {
                    pipeline.close();
                } catch (Exception e) {
                    logger.debug("Error closing pipeline: {}", e.getMessage());
                }
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
        taskLastPhases.put(taskId, phase);

        // Calculate overall progress (loading=0-25%, embedding=25-75%, indexing=75-99%,
        // complete=100%)
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

        // Check vector store count periodically (e.g. every 2 seconds) to show live
        // updates in UI
        long lastCheck = lastVectorStoreCheckTime.get();
        if (now - lastCheck > 2000) {
            if (vectorStore != null) {
                // Use CAS to ensure only one thread performs the refresh
                if (lastVectorStoreCheckTime.compareAndSet(lastCheck, now)) {
                    try {
                        vectorStore.refreshReader();
                        long count = vectorStore.getApproxVectorCount();
                        cachedVectorStoreCount.set(count);
                        logger.debug("Refreshed vector store count: {}", count);
                    } catch (Exception e) {
                        logger.warn("Failed to refresh vector store reader for live update: {}", e.getMessage());
                    }
                }
            }
        }

        // Build stats with worker details
        IngestStats.Builder statsBuilder = IngestStats.builder()
                .documentsLoaded((int) totalDocs)
                .docsPerSecond(rate)
                .totalProcessingTimeMs(elapsedMs)
                .chunksCreated(progress.chunksCreated())
                .chunksIndexed(progress.chunksIndexed())
                .chunksEmbedded(progress.chunksEmbedded())
                .documentsIndexed(progress.chunksIndexed())
                .actualVectorStoreCount(cachedVectorStoreCount.get() >= 0 ? cachedVectorStoreCount.get() : null);

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
                    .passageTokenCounts(batch.passageTokenCounts())
                    .build());
        }

        IngestStats stats = statsBuilder.build();
        if (phase != IngestPhase.COMPLETED && phase != IngestPhase.FAILED) {
            recordIngestProgress(taskId, phase, progressPercent, progress.phase(), progress.message(), stats);
        }

        // Build progress update using factory method
        String displayName = getTaskDisplayName(taskId);
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId,
                displayName,
                phase,
                progressPercent,
                progress.phase(),
                progress.message(),
                stats);

        // Send async to avoid blocking pipeline
        if (messagingTemplate != null) {
            webSocketExecutor.submit(() -> {
                try {
                    messagingTemplate.convertAndSend("/topic/vector-population/progress", update);
                } catch (Exception e) {
                    logger.trace("Failed to send WebSocket update: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * Map pipeline phase string to IngestPhase enum.
     */
    private IngestPhase mapPhase(String phase) {
        if (phase == null)
            return IngestPhase.LOADING;
        return switch (phase.toLowerCase()) {
            case "starting", "queueing" -> IngestPhase.LOADING;
            case "embedding" -> IngestPhase.EMBEDDING;
            case "indexing" -> IngestPhase.INDEXING;
            case "indexing+embedding", "indexing_and_embedding" -> IngestPhase.INDEXING_AND_EMBEDDING;
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
                .documentsIndexed((int) indexed)
                .actualKeywordIndexCount(actualKeywordCount)
                .actualVectorStoreCount(actualVectorCount)
                .docsPerSecond(rate)
                .build();

        if (phase != IngestPhase.COMPLETED && phase != IngestPhase.FAILED) {
            recordIngestProgress(taskId, phase, progressPercent, phase.name(), message, stats);
        }

        // Build progress update using factory method
        String displayName = getTaskDisplayName(taskId);
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId,
                displayName,
                phase,
                progressPercent,
                phase.name(),
                message,
                stats);

        // Send async to avoid blocking pipeline
        if (messagingTemplate != null) {
            webSocketExecutor.submit(() -> {
                try {
                    messagingTemplate.convertAndSend("/topic/vector-population/progress", update);
                } catch (Exception e) {
                    logger.trace("Failed to send WebSocket update: {}", e.getMessage());
                }
            });
        }
    }

    private String buildTaskDisplayName(String keywordIndexPath) {
        if (keywordIndexPath == null || keywordIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + keywordIndexPath;
    }

    private String getTaskDisplayName(String taskId) {
        String displayName = taskDisplayNames.get(taskId);
        return displayName != null ? displayName : "Vector Population";
    }

    private void startIngestTracking(String taskId, String keywordIndexPath, String vectorIndexPath) {
        String displayName = buildTaskDisplayName(keywordIndexPath);
        taskDisplayNames.put(taskId, displayName);

        if (ingestProgressTracker != null) {
            ingestProgressTracker.startTask(taskId, displayName);
        }

        if (jobHistoryService != null) {
            try {
                IndexingJobHistory job = jobHistoryService.createJobWithEnvironment(
                        taskId,
                        displayName,
                        null,
                        null,
                        VECTOR_POPULATION_CONTENT_TYPE);
                if (job != null) {
                    jobHistoryService.markJobRunning(taskId);
                    if (vectorIndexPath != null && !vectorIndexPath.isBlank()) {
                        jobHistoryService.setIndexPath(taskId, vectorIndexPath);
                    }
                }
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to persist job history: {}", taskId, e.getMessage());
            }
        }
    }

    private void recordIngestProgress(String taskId, IngestPhase phase, int progressPercent,
            String currentStep, String message, IngestStats stats) {
        taskLastPhases.put(taskId, phase);

        if (ingestProgressTracker != null) {
            String displayName = getTaskDisplayName(taskId);
            ingestProgressTracker.updateProgress(taskId, displayName, phase, progressPercent, currentStep, message,
                    stats);
        }

        updateJobHistory(taskId, phase, progressPercent, stats);
    }

    private void completeIngestTracking(String taskId, IngestStats finalStats) {
        if (ingestProgressTracker != null) {
            String displayName = getTaskDisplayName(taskId);
            ingestProgressTracker.completeTask(taskId, displayName,
                    finalStats != null ? finalStats : IngestStats.empty());
        }

        if (jobHistoryService != null) {
            try {
                updateJobHistory(taskId, IngestPhase.COMPLETED, 100, finalStats);
                jobHistoryService.markJobCompleted(taskId);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job completed: {}", taskId, e.getMessage());
            }
        }

        cleanupTaskTracking(taskId);
    }

    private void failIngestTracking(String taskId, IngestPhase failedPhase, String errorMessage) {
        if (ingestProgressTracker != null) {
            String displayName = getTaskDisplayName(taskId);
            ingestProgressTracker.failTask(taskId, displayName, failedPhase, errorMessage);
        }

        if (jobHistoryService != null) {
            try {
                jobHistoryService.markJobFailed(taskId, mapPhaseToEventPhase(failedPhase), errorMessage, null,
                        IndexingJobHistory.FailureReason.UNKNOWN);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job failed: {}", taskId, e.getMessage());
            }
        }

        cleanupTaskTracking(taskId);
    }

    private void cancelIngestTracking(String taskId, String reason) {
        IngestPhase lastPhase = taskLastPhases.getOrDefault(taskId, IngestPhase.LOADING);
        long elapsedMs = 0;
        PopulationTaskStatus status = activeTasks.get(taskId);
        if (status != null) {
            elapsedMs = status.getElapsedMs();
        }
        IngestStats stats = IngestStats.builder().totalProcessingTimeMs(elapsedMs).build();

        if (ingestProgressTracker != null) {
            String displayName = getTaskDisplayName(taskId);
            ingestProgressTracker.cancelTask(taskId, displayName, lastPhase, reason, stats);
        }

        if (jobHistoryService != null) {
            try {
                jobHistoryService.markJobCancelled(taskId, mapPhaseToEventPhase(lastPhase), reason);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job cancelled: {}", taskId, e.getMessage());
            }
        }

        cleanupTaskTracking(taskId);
    }

    private void cleanupTaskTracking(String taskId) {
        taskDisplayNames.remove(taskId);
        taskLastPhases.remove(taskId);
        cancelledTasks.remove(taskId);
        activeTasks.remove(taskId);
    }

    private void updateJobHistory(String taskId, IngestPhase phase, int progressPercent, IngestStats stats) {
        if (jobHistoryService == null) {
            return;
        }

        try {
            jobHistoryService.updateJobProgress(taskId, mapPhaseToEventPhase(phase), progressPercent);
            if (stats != null) {
                Integer docsLoaded = stats.documentsLoaded() != null ? stats.documentsLoaded() : 0;
                Integer chunksCreated = stats.chunksCreated() != null ? stats.chunksCreated() : 0;
                Integer chunksEmbedded = stats.chunksEmbedded() != null ? stats.chunksEmbedded() : 0;
                Integer chunksIndexed = stats.chunksIndexed();
                if (chunksIndexed == null && stats.documentsIndexed() != null) {
                    chunksIndexed = stats.documentsIndexed();
                }
                if (chunksIndexed == null) {
                    chunksIndexed = 0;
                }
                jobHistoryService.updateJobStats(taskId, docsLoaded, chunksCreated, chunksEmbedded, chunksIndexed);

                if (stats.memoryUsagePercent() != null && stats.memoryUsagePercent() > 0) {
                    jobHistoryService.updateMemoryUsage(taskId, stats.memoryUsagePercent());
                }
            }
        } catch (Exception e) {
            logger.debug("[VectorPop {}] Failed to update job history: {}", taskId, e.getMessage());
        }
    }

    private IngestEvent.IngestPhase mapPhaseToEventPhase(IngestPhase phase) {
        if (phase == null) {
            return IngestEvent.IngestPhase.LOADING;
        }
        return switch (phase) {
            case QUEUED -> IngestEvent.IngestPhase.QUEUED;
            case UPLOADING, LOADING -> IngestEvent.IngestPhase.LOADING;
            case OCR_PROCESSING -> IngestEvent.IngestPhase.OCR_PROCESSING;
            case CONVERTING -> IngestEvent.IngestPhase.CONVERTING;
            case CHUNKING -> IngestEvent.IngestPhase.CHUNKING;
            case EXTRACTION -> IngestEvent.IngestPhase.EXTRACTION;
            case GRAPH_EXTRACTION -> IngestEvent.IngestPhase.GRAPH_EXTRACTION;
            case INDEXING_AND_EMBEDDING -> IngestEvent.IngestPhase.INDEXING_AND_EMBEDDING;
            case EMBEDDING -> IngestEvent.IngestPhase.EMBEDDING;
            case INDEXING -> IngestEvent.IngestPhase.INDEXING;
            case COMPLETED -> IngestEvent.IngestPhase.COMPLETED;
            case FAILED -> IngestEvent.IngestPhase.FAILED;
        };
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

    public static class PopulationResult {
        private String taskId;
        private boolean success;
        private long documentsIndexed;
        private long durationMs;
        private String errorMessage;

        public PopulationResult(String taskId, boolean success, long documentsIndexed, long durationMs,
                String errorMessage) {
            this.taskId = taskId;
            this.success = success;
            this.documentsIndexed = documentsIndexed;
            this.durationMs = durationMs;
            this.errorMessage = errorMessage;
        }

        public String taskId() {
            return taskId;
        }

        public boolean success() {
            return success;
        }

        public long documentsIndexed() {
            return documentsIndexed;
        }

        public long durationMs() {
            return durationMs;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    public static class PopulationTaskStatus {
        private String taskId;
        private long totalDocuments;
        private long startTime;
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

        public String getTaskId() {
            return taskId;
        }

        public long getTotalDocuments() {
            return totalDocuments;
        }

        public long getStartTime() {
            return startTime;
        }

        public long getDocumentsIndexed() {
            return documentsIndexed;
        }

        public int getProgressPercent() {
            return progressPercent;
        }

        public boolean isComplete() {
            return complete;
        }

        public long getEndTime() {
            return endTime;
        }

        public long getElapsedMs() {
            return complete ? endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }
}
