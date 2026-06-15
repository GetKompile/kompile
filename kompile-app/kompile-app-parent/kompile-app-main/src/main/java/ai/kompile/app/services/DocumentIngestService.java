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

package ai.kompile.app.services;

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.ingest.service.TextConversionService;
import ai.kompile.app.services.pipeline.AdaptivePipelineConfig;
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.pipeline.PipelineResult;
import ai.kompile.app.services.pipeline.ProcessingState;
import ai.kompile.app.services.preprocessing.LargeDocumentPreprocessor;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.services.scheduler.ScheduledJob;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.app.web.dto.IngestProgressUpdate.OcrProcessingMetrics;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service for asynchronous document ingest with real-time progress tracking via
 * WebSocket.
 */
@Service
public class DocumentIngestService implements org.springframework.beans.factory.DisposableBean {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected DocumentIngestService() {}


    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestService.class);

    // Timing constants
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L; // 5 seconds

    // Subprocess mode configuration - now managed via SubprocessConfigService for
    // dynamic updates
    @Autowired(required = false)
    private SubprocessConfigService subprocessConfigService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private List<DocumentLoader> documentLoaders;
    @Autowired
    private List<TextChunker> textChunkers;
    @Autowired
    private List<IndexerService> indexerServices;
    private IndexerService indexerService;
    @Autowired
    private IngestConfiguration ingestConfiguration;
    @Lazy
    @Autowired
    private MemoryWatchdogService memoryWatchdogService;
    @Autowired
    private TextConversionService textConversionService;
    @Autowired(required = false)
    private IngestEventService ingestEventService;
    @Autowired(required = false)
    private IndexingJobHistoryService indexingJobHistoryService;
    @Lazy
    @Autowired(required = false)
    private ai.kompile.core.embeddings.EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private LargeDocumentPreprocessor largeDocumentPreprocessor;
    @Autowired(required = false)
    private SubprocessIngestLauncher subprocessIngestLauncher;
    @Autowired(required = false)
    private ResourceAwareJobScheduler resourceScheduler;

    // Track active tasks for status queries - use bounded map to prevent memory
    // leaks
    // Maximum 1000 entries, oldest entries removed when limit reached
    private static final int MAX_ACTIVE_TASKS = 1000;
    private final Map<String, IngestProgressUpdate> activeTasksStatus = new ConcurrentHashMap<>();

    // Track cancelled tasks - tasks in this set should stop processing as soon as
    // possible
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    // Track active pipelines for cancellation
    private final Map<String, ParallelIngestPipeline> activePipelines = new ConcurrentHashMap<>();

    // Single scheduled executor for cleanup tasks instead of spawning threads per
    // task
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ingest-cleanup-scheduler");
        t.setDaemon(true);
        return t;
    });

    // CRITICAL: Dedicated async executor for WebSocket progress updates
    // This prevents blocking worker threads (especially the embedding thread)
    // during network I/O.
    // WebSocket sends are offloaded to this thread pool, allowing workers to
    // continue immediately.
    private final ExecutorService webSocketExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "websocket-progress-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    // Executor for parallel batch processing - uses available CPU cores
    private ExecutorService batchExecutor;

    // Progress update throttling - minimum interval between WebSocket updates (ms)
    // 100ms provides responsive updates while avoiding UI overload
    // Reduced from 250ms to provide more visibility into pipeline progress
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 100;
    private final AtomicLong lastProgressUpdateTime = new AtomicLong(0);

    // Parallel processing configuration

    @Autowired
    public DocumentIngestService(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired List<DocumentLoader> documentLoaders,
            @Autowired List<TextChunker> textChunkers,
            @Autowired List<IndexerService> indexerServices,
            @Lazy @Autowired List<ai.kompile.core.embeddings.EmbeddingModel> embeddingModels,
            IngestConfiguration ingestConfiguration,
            @Lazy MemoryWatchdogService memoryWatchdogService,
            TextConversionService textConversionService,
            @Autowired(required = false) IngestEventService ingestEventService,
            @Autowired(required = false) IndexingJobHistoryService indexingJobHistoryService,
            @Autowired(required = false) LargeDocumentPreprocessor largeDocumentPreprocessor,
            @Autowired(required = false) SubprocessIngestLauncher subprocessIngestLauncher,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) ResourceAwareJobScheduler resourceScheduler) {
        this.messagingTemplate = messagingTemplate; // May be null if WebSocket not configured
        this.documentLoaders = documentLoaders;
        this.textChunkers = textChunkers;
        this.ingestConfiguration = ingestConfiguration;
        this.memoryWatchdogService = memoryWatchdogService;
        this.textConversionService = textConversionService;
        this.ingestEventService = ingestEventService;
        this.indexingJobHistoryService = indexingJobHistoryService;
        this.subprocessIngestLauncher = subprocessIngestLauncher;
        this.subprocessConfigService = subprocessConfigService;
        this.resourceScheduler = resourceScheduler;

        // Select non-NoOp indexer if available
        this.indexerService = indexerServices.stream()
                .filter(s -> !(s instanceof NoOpIndexerService))
                .findFirst()
                .orElse(indexerServices.isEmpty() ? null : indexerServices.get(0));

        // Select non-NoOp embedding model if available
        // Filter by class name to avoid triggering lazy proxy resolution
        this.embeddingModel = embeddingModels.stream()
                .filter(e -> !e.getClass().getSimpleName().contains("NoOp"))
                .findFirst()
                .orElse(embeddingModels.isEmpty() ? null : embeddingModels.get(0));

        if (this.embeddingModel != null) {
            logger.info("DocumentIngestService will use embedding model: {}",
                    this.embeddingModel.getClass().getSimpleName());
        } else {
            logger.warn("No embedding model available - pipeline will use passthrough mode");
        }

        // Initialize large document preprocessor
        this.largeDocumentPreprocessor = largeDocumentPreprocessor;
        if (this.largeDocumentPreprocessor != null) {
            logger.info("Large document preprocessor available - streaming enabled for large files");
        } else {
            logger.info("Large document preprocessor not available - all documents will use standard pipeline");
        }

        // Initialize parallel batch executor with bounded thread pool
        int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        this.batchExecutor = new ThreadPoolExecutor(
                parallelism, parallelism,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "ingest-batch-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // Back-pressure: run in calling thread if queue full
        );
        logger.info("Initialized batch executor with {} threads", parallelism);

        // Log subprocess mode availability
        if (this.subprocessIngestLauncher != null) {
            logger.info("SubprocessIngestLauncher available - subprocess mode is supported");
        } else {
            logger.warn("SubprocessIngestLauncher is NULL - subprocess mode will NOT be available! " +
                    "All ingestion will use in-process parallel pipeline.");
        }
        if (this.subprocessConfigService != null) {
            logger.info("SubprocessConfigService available - global subprocess enabled: {}",
                    subprocessConfigService.isEnabled());
        } else {
            logger.warn("SubprocessConfigService is NULL");
        }
    }

    /**
     * Cleanup resources when the bean is destroyed.
     */
    @Override
    public void destroy() {
        logger.info("Shutting down DocumentIngestService executors");
        cleanupScheduler.shutdown();
        batchExecutor.shutdown();
        webSocketExecutor.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
            if (!batchExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
            if (!webSocketExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                webSocketExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            batchExecutor.shutdownNow();
            webSocketExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Clear any remaining task statuses and cancelled tasks
        activeTasksStatus.clear();
        cancelledTasks.clear();
        activePipelines.clear();
        logger.info("DocumentIngestService cleanup complete");
    }

    /**
     * Initialize periodic cleanup tasks.
     */
    @PostConstruct
    public void init() {
        // Resolve computed fields when using no-arg constructor (AOT/native image path)
        if (this.indexerService == null && this.indexerServices != null && !this.indexerServices.isEmpty()) {
            this.indexerService = indexerServices.stream()
                    .filter(s -> !(s instanceof NoOpIndexerService))
                    .findFirst()
                    .orElse(indexerServices.get(0));
        }

        // MEMORY LEAK FIX: Schedule periodic cleanup of stale cancelled tasks
        // This handles edge cases where cancelledTasks entries might not be cleaned up
        // (e.g., async task never ran, or unusual exception paths)
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupStaleCancelledTasks();
            } catch (Exception e) {
                logger.warn("Error during stale cancelled tasks cleanup: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.MINUTES); // Run every 10 minutes

        logger.info("DocumentIngestService initialized with periodic cleanup tasks");
    }

    /**
     * Cleans up stale entries in cancelledTasks that are no longer in
     * activeTasksStatus.
     * This handles edge cases where the cancellation flag wasn't properly cleared.
     */
    private void cleanupStaleCancelledTasks() {
        if (cancelledTasks.isEmpty()) {
            return;
        }

        int initialSize = cancelledTasks.size();

        // Remove entries that are no longer tracked as active tasks
        cancelledTasks.removeIf(taskId -> {
            IngestProgressUpdate status = activeTasksStatus.get(taskId);
            if (status == null) {
                // Task no longer tracked - safe to remove cancellation flag
                logger.debug("Removing stale cancellation flag for task: {}", taskId);
                return true;
            }
            // Also remove if task is in terminal state
            IngestProgressUpdate.IngestStatus taskStatus = status.status();
            if (taskStatus == IngestProgressUpdate.IngestStatus.COMPLETED ||
                    taskStatus == IngestProgressUpdate.IngestStatus.FAILED ||
                    taskStatus == IngestProgressUpdate.IngestStatus.CANCELLED) {
                logger.debug("Removing cancellation flag for terminal task: {} (status={})", taskId, taskStatus);
                return true;
            }
            return false;
        });

        int removed = initialSize - cancelledTasks.size();
        if (removed > 0) {
            logger.info("Cleaned up {} stale cancellation entries", removed);
        }
    }

    /**
     * Gets the memory watchdog service for external access.
     */
    public MemoryWatchdogService getMemoryWatchdogService() {
        return memoryWatchdogService;
    }

    /**
     * Checks if a new job can be accepted based on current limits and memory.
     */
    public boolean canAcceptJob() {
        return ingestConfiguration.canAcceptNewJob();
    }

    /**
     * Gets the current ingest configuration for external access.
     */
    public IngestConfiguration getConfiguration() {
        return ingestConfiguration;
    }

    /**
     * Debug method to trace chunker resolution.
     * Returns detailed information about how a chunker name would be resolved.
     */
    public Map<String, Object> debugChunkerResolution(String requestedName) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("requestedName", requestedName);
        debug.put("requestedNameIsNull", requestedName == null);
        debug.put("requestedNameIsEmpty", requestedName != null && requestedName.isEmpty());

        // List all available chunkers
        List<Map<String, String>> available = new ArrayList<>();
        for (TextChunker chunker : textChunkers) {
            Map<String, String> info = new LinkedHashMap<>();
            info.put("getName", chunker.getName());
            info.put("class", chunker.getClass().getSimpleName());
            info.put("isNoOp", String.valueOf(isNoOpChunker(chunker)));
            available.add(info);
        }
        debug.put("availableChunkers", available);

        // Show alias map
        debug.put("CHUNKER_ALIASES", CHUNKER_ALIASES);

        if (requestedName != null && !requestedName.isEmpty()) {
            // Step 1: Exact match
            TextChunker exactMatch = textChunkers.stream()
                    .filter(chunker -> requestedName.equals(chunker.getName()))
                    .findFirst()
                    .orElse(null);
            debug.put("step1_exactMatch", exactMatch != null ? exactMatch.getName() : null);

            // Step 2: Alias lookup
            String mappedName = CHUNKER_ALIASES.get(requestedName);
            debug.put("step2_aliasLookup", mappedName);

            if (mappedName != null) {
                TextChunker aliasMatch = textChunkers.stream()
                        .filter(chunker -> mappedName.equals(chunker.getName()))
                        .findFirst()
                        .orElse(null);
                debug.put("step2_aliasMatch", aliasMatch != null ? aliasMatch.getName() : null);
            }

            // Step 3: Partial match
            String normalized = requestedName.toLowerCase().replace("_", "-").replace("spring-", "");
            debug.put("step3_normalizedName", normalized);
            TextChunker partialMatch = textChunkers.stream()
                    .filter(chunker -> {
                        String name = chunker.getName().toLowerCase();
                        return name.contains(normalized) || normalized.contains(name);
                    })
                    .filter(chunker -> !isNoOpChunker(chunker))
                    .findFirst()
                    .orElse(null);
            debug.put("step3_partialMatch", partialMatch != null ? partialMatch.getName() : null);
        }

        // Final resolution using findChunker
        TextChunker resolved = findChunker(requestedName);
        debug.put("finalResolvedChunker", resolved != null ? resolved.getName() : null);
        debug.put("finalResolvedClass", resolved != null ? resolved.getClass().getSimpleName() : null);

        // What selectBestChunker returns
        TextChunker fallback = selectBestChunker();
        debug.put("selectBestChunker", fallback != null ? fallback.getName() : null);

        return debug;
    }

    /**
     * Processing mode for document ingestion.
     * Determines whether to use subprocess isolation or in-process pipeline.
     */
    public enum ProcessingMode {
        /** Use global configuration to decide (default behavior) */
        AUTO,
        /** Force subprocess isolation mode */
        SUBPROCESS,
        /** Force in-process parallel pipeline mode */
        INPROCESS
    }

    /**
     * Asynchronously processes a document file with progress updates via WebSocket.
     * Uses global configuration to decide between subprocess and in-process mode.
     */
    @Async("taskExecutor")
    public void processDocumentAsync(String taskId, Path filePath, String loaderName, String chunkerName) {
        processDocumentAsync(taskId, filePath, loaderName, chunkerName, ProcessingMode.AUTO);
    }

    /**
     * Asynchronously processes a document file with progress updates via WebSocket.
     *
     * @param taskId      unique task identifier for progress tracking
     * @param filePath    path to the document file
     * @param loaderName  name of the document loader to use (optional)
     * @param chunkerName name of the text chunker to use (optional)
     * @param mode        processing mode - AUTO (use global config), SUBPROCESS
     *                    (force isolation), or INPROCESS (force same JVM)
     */
    @Async("taskExecutor")
    public void processDocumentAsync(String taskId, Path filePath, String loaderName, String chunkerName,
            ProcessingMode mode) {
        processDocumentAsync(taskId, filePath, loaderName, chunkerName, mode, null);
    }

    /**
     * Asynchronously processes a document file with progress updates via WebSocket.
     *
     * @param taskId            unique task identifier for progress tracking
     * @param filePath          path to the document file
     * @param loaderName        name of the document loader to use (optional)
     * @param chunkerName       name of the text chunker to use (optional)
     * @param mode              processing mode - AUTO (use global config), SUBPROCESS
     *                          (force isolation), or INPROCESS (force same JVM)
     * @param subprocessOptions per-request subprocess configuration (heapSize, timeoutMinutes, etc.)
     */
    @Async("taskExecutor")
    public void processDocumentAsync(String taskId, Path filePath, String loaderName, String chunkerName,
            ProcessingMode mode, Map<String, Object> subprocessOptions) {
        String fileName = filePath.getFileName().toString();
        long startTime = System.currentTimeMillis();

        // Default to AUTO if null
        if (mode == null) {
            mode = ProcessingMode.AUTO;
        }

        // DEBUG: Log incoming parameters
        logger.info("[Task {}] processDocumentAsync called with: filePath={}, loaderName={}, chunkerName='{}', mode={}",
                taskId, filePath,
                loaderName != null ? loaderName.replaceAll("[\\r\\n]", "_") : null,
                chunkerName != null ? chunkerName.replaceAll("[\\r\\n]", "_") : null,
                mode);

        // ========== SUBPROCESS MODE DELEGATION ==========
        // Decision logic based on ProcessingMode:
        // - AUTO: use global SubprocessConfigService.isEnabled() setting
        // - SUBPROCESS: force subprocess mode (with fallback to in-process if unavailable)
        // - INPROCESS: force in-process mode, skip subprocess entirely
        //
        // NO IMPLICIT OVERRIDES: User's choice is respected. The subprocess handles
        // all document types including large/streaming documents.
        boolean useSubprocess = determineUseSubprocess(mode, taskId);

        logger.info("[Task {}] Processing mode: {} -> useSubprocess={} (service available: {}, launcher available: {})",
                taskId, mode, useSubprocess, subprocessConfigService != null, subprocessIngestLauncher != null);

        if (useSubprocess && subprocessIngestLauncher != null) {
            logger.info("[Task {}] Delegating to SUBPROCESS mode for crash isolation", taskId);

            // === SCHEDULER INTEGRATION: Submit through scheduler for queuing, priority, and history ===
            if (resourceScheduler != null) {
                try {
                    final String fLoaderName = loaderName;
                    final String fChunkerName = chunkerName;
                    ScheduledJob job = ScheduledJob.builder()
                            .jobId(taskId)
                            .jobType("ingest")
                            .description("Ingest: " + fileName)
                            .resourceProfile(JobResourceProfiles.INGEST)
                            .executor(ctx -> {
                                try {
                                    var resultFuture = subprocessIngestLauncher.launchIngest(
                                            taskId, filePath, fLoaderName, fChunkerName, subprocessOptions);
                                    var result = resultFuture.get();
                                    if (result != null && !result.success()) {
                                        throw new RuntimeException("Ingest failed: " + result.errorMessage());
                                    }
                                    if (result != null) {
                                        logger.info("[Task {}] Subprocess ingest completed via scheduler: {} docs loaded, {} indexed",
                                                taskId, result.documentsLoaded(), result.documentsIndexed());
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException("Ingest interrupted", e);
                                } catch (ExecutionException e) {
                                    throw new RuntimeException("Ingest failed: " + e.getCause().getMessage(), e.getCause());
                                }
                            })
                            .priority(50)
                            .build();
                    // Broadcast QUEUED state so the UI shows the task immediately
                    // while it waits in the scheduler priority queue
                    sendProgress(IngestProgressUpdate.queued(taskId, fileName, null));
                    resourceScheduler.submit(job);
                    logger.info("[Task {}] Ingest submitted to scheduler (queued)", taskId);
                    return;
                } catch (Exception e) {
                    logger.error("[Task {}] Failed to submit ingest to scheduler: {}", taskId, e.getMessage(), e);
                    // Fall through to direct subprocess launch
                }
            }

            try {
                subprocessIngestLauncher.launchIngest(taskId, filePath, loaderName, chunkerName, subprocessOptions)
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                logger.error("[Task {}] Subprocess ingest failed: {}", taskId, error.getMessage());
                            } else if (result != null && result.success()) {
                                logger.info("[Task {}] Subprocess ingest completed: {} docs loaded, {} indexed",
                                        taskId, result.documentsLoaded(), result.documentsIndexed());
                            } else if (result != null) {
                                logger.error("[Task {}] Subprocess ingest failed: {}", taskId, result.errorMessage());
                            }
                        });
                return; // Exit - subprocess handles the rest
            } catch (Exception e) {
                logger.error("[Task {}] Failed to launch subprocess: {}", taskId, e.getMessage(), e);
                // For explicit SUBPROCESS request, warn about the fallback
                if (mode == ProcessingMode.SUBPROCESS) {
                    logger.warn("[Task {}] User explicitly requested SUBPROCESS mode but launch failed. " +
                            "Falling back to in-process mode.", taskId);
                }
                // Fall through to in-process mode
            }
        } else if (useSubprocess && subprocessIngestLauncher == null) {
            // User requested subprocess mode but the launcher bean is not available
            logger.error("[Task {}] SUBPROCESS mode requested but SubprocessIngestLauncher is NOT available! " +
                    "Check if the SubprocessIngestLauncher bean was created successfully.", taskId);
            if (mode == ProcessingMode.SUBPROCESS) {
                logger.warn("[Task {}] User explicitly requested SUBPROCESS mode but it is not available. " +
                        "This is a degraded operation - proceeding with in-process mode.", taskId);
            }
        } else {
            // User requested in-process mode or AUTO resolved to in-process
            logger.info("[Task {}] Using IN-PROCESS mode (mode={}, subprocess available: {})",
                    taskId, mode, subprocessIngestLauncher != null);
        }

        // Reset progress throttle for this new task
        lastProgressUpdateTime.set(0);

        // Phase timing tracking
        long loadingStartTime = 0, loadingEndTime = 0;
        long conversionStartTime = 0, conversionEndTime = 0;
        long chunkingStartTime = 0, chunkingEndTime = 0;
        long embeddingStartTime = 0;

        // Current memory state helper
        IngestProgressUpdate.IngestStats.Builder statsBuilder = IngestProgressUpdate.IngestStats.builder();

        // Track current phase for event logging
        IngestEvent.IngestPhase currentEventPhase = IngestEvent.IngestPhase.QUEUED;

        // Register job start
        if (!ingestConfiguration.tryStartJob()) {
            logger.warn("[Task {}] Max concurrent jobs reached, job will be queued", taskId);
        }

        // Register with memory watchdog for monitoring, providing a cancellation
        // callback
        // The callback will be invoked if memory exceeds the kill threshold
        if (memoryWatchdogService != null) {
            final String finalTaskId = taskId;
            memoryWatchdogService.registerJob(taskId, fileName, () -> {
                logger.warn("[Task {}] Memory kill callback invoked - forcibly stopping job", finalTaskId);

                // NOTE: We do NOT add to cancelledTasks here because memory kills are handled
                // separately via isJobKilled() check after pipeline completes.
                // This ensures proper differentiation between user cancellation and memory
                // kill.

                // Cancel the pipeline if it exists to stop processing immediately
                ParallelIngestPipeline pipeline = activePipelines.get(finalTaskId);
                if (pipeline != null) {
                    logger.info("[Task {}] Cancelling active pipeline due to memory kill", finalTaskId);
                    pipeline.cancel();
                } else {
                    logger.debug("[Task {}] No active pipeline to cancel (may be in earlier phase)", finalTaskId);
                }

                // The audit event and WebSocket notification are already sent by
                // MemoryWatchdogService
                // before this callback is invoked, so we don't need to do that here
            });
        }

        try {
            // Check for cancellation at start
            if (isTaskCancelled(taskId)) {
                logger.info("[Task {}] Task was cancelled before starting", taskId);
                throw new TaskCancelledException("Task cancelled before starting");
            }

            // Check memory before starting
            IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();
            statsBuilder.memoryUsagePercent(memInfo.usagePercent())
                    .memoryStatus(
                            memInfo.criticalExceeded() ? "CRITICAL" : (memInfo.thresholdExceeded() ? "WARNING" : "OK"));

            if (memInfo.criticalExceeded()) {
                logger.warn("[Task {}] Memory critical: {}%, waiting for memory to free up",
                        taskId, String.format("%.1f", memInfo.usagePercent()));
                // Wait briefly and check again
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                memInfo = ingestConfiguration.getMemoryInfo();
                if (memInfo.criticalExceeded()) {
                    throw new MemoryPressureException("System memory critical (" +
                            String.format("%.1f", memInfo.usagePercent()) + "%). Please try again later.");
                }
            }

            // Send initial queued status with memory info
            sendProgress(IngestProgressUpdate.progress(taskId, fileName, IngestPhase.QUEUED, 0,
                    "Queued for processing",
                    String.format("Memory: %.1f%% (%s)", memInfo.usagePercent(),
                            memInfo.criticalExceeded() ? "Critical" : "OK"),
                    statsBuilder.build()));

            // Log queued event
            logEvent(() -> ingestEventService.logQueued(taskId, fileName));

            // Create job history entry
            final Long fileSize = java.nio.file.Files.exists(filePath) ? java.nio.file.Files.size(filePath) : null;
            final String contentType = java.nio.file.Files.probeContentType(filePath);
            updateJobHistory(() -> {
                indexingJobHistoryService.createJobWithEnvironment(
                        taskId, fileName,
                        ingestEventService != null ? ingestEventService.captureNd4jEnvironment() : null,
                        fileSize, contentType);
                indexingJobHistoryService.markJobRunning(taskId);
            });

            // Phase 1: Loading
            loadingStartTime = System.currentTimeMillis();
            currentEventPhase = IngestEvent.IngestPhase.LOADING;
            logEvent(() -> ingestEventService.logPhaseStarted(taskId, fileName,
                    IngestEvent.IngestPhase.LOADING, IngestEvent.IngestPhase.QUEUED));
            statsBuilder.loaderUsed(loaderName != null ? loaderName : "auto-detect");

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.LOADING, 10,
                    "Loading document", String.format("Starting document loading using %s...",
                            loaderName != null ? loaderName : "auto-detected loader"),
                    statsBuilder.build()));

            // Find appropriate loader
            DocumentLoader selectedLoader = findLoader(filePath, loaderName);
            if (selectedLoader == null) {
                throw new RuntimeException("No suitable loader found for file: " + filePath);
            }

            logger.info("[Task {}] Using loader: {}", taskId, selectedLoader.getName());
            statsBuilder.loaderUsed(selectedLoader.getName());

            // Send loader selection update
            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.LOADING, 15,
                    "Loader selected",
                    String.format("Using loader: %s", selectedLoader.getName()),
                    statsBuilder.build()));

            // Create source descriptor
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(filePath.toString())
                    .originalFileName(fileName)
                    .sourceId("upload_" + fileName + "_" + taskId)
                    .metadata(Map.of(
                            "upload_timestamp", System.currentTimeMillis(),
                            "task_id", taskId))
                    .build();

            // Check if this is a large document that should use streaming
            if (largeDocumentPreprocessor != null && largeDocumentPreprocessor.isLargeDocument(filePath)) {
                // Also check if streaming is supported for this file type
                if (largeDocumentPreprocessor.supportsStreaming(filePath)) {
                    logger.info("[Task {}] Document qualifies for large document streaming processing", taskId);
                    processLargeDocumentStreaming(taskId, filePath, fileName, sourceDescriptor,
                            selectedLoader, chunkerName, startTime, statsBuilder);
                    return; // Large document processing handles everything
                } else {
                    logger.warn("[Task {}] Large document detected but streaming not supported for this file type. " +
                            "Using standard pipeline (may cause high memory usage).", taskId);
                }
            }

            // Load documents (standard flow for small/medium documents)
            // Use progress-aware loading to surface OCR/VLM page-by-page progress
            final long ocrStartTime = System.currentTimeMillis();
            final int[] ocrCumulativeTokens = {0};
            final int[] ocrPagesCompleted = {0};
            List<Document> loadedDocuments = selectedLoader.load(sourceDescriptor, loaderProgress -> {
                if ("OCR_PROCESSING".equals(loaderProgress.phase())) {
                    Map<String, Object> m = loaderProgress.metrics();
                    int currentPage = m.get("currentPage") != null ? ((Number) m.get("currentPage")).intValue() : 0;
                    int totalPages = m.get("totalPages") != null ? ((Number) m.get("totalPages")).intValue() : 0;
                    String vlmModelId = (String) m.get("vlmModelId");

                    OcrProcessingMetrics ocrMetrics;
                    if (m.containsKey("generatedTokens") && m.get("generatedTokens") != null) {
                        // Page completed with token metrics
                        int genTokens = ((Number) m.get("generatedTokens")).intValue();
                        int promptTokens = m.get("promptTokens") != null ? ((Number) m.get("promptTokens")).intValue() : 0;
                        double tps = m.get("tokensPerSecond") != null ? ((Number) m.get("tokensPerSecond")).doubleValue() : 0.0;
                        long genMs = m.get("generateTimeMs") != null ? ((Number) m.get("generateTimeMs")).longValue() : 0L;
                        ocrCumulativeTokens[0] += genTokens;
                        ocrPagesCompleted[0]++;
                        long totalOcrMs = System.currentTimeMillis() - ocrStartTime;
                        double avgTps = totalOcrMs > 0 ? (ocrCumulativeTokens[0] * 1000.0 / totalOcrMs) : 0.0;
                        ocrMetrics = OcrProcessingMetrics.pageCompleted(
                                currentPage, totalPages, genTokens, promptTokens, tps, genMs, vlmModelId,
                                ocrCumulativeTokens[0], ocrPagesCompleted[0], totalOcrMs, avgTps);
                    } else {
                        // Sub-step within a page (Rendering, Preprocessing, Generating)
                        ocrMetrics = OcrProcessingMetrics.pageStep(
                                currentPage, totalPages, loaderProgress.currentStep(), vlmModelId);
                    }

                    statsBuilder.currentOcrMetrics(ocrMetrics)
                            .ocrProcessingTimeMs(System.currentTimeMillis() - ocrStartTime);

                    // Map 0-100% OCR progress to 10-25% overall progress
                    int ocrPercent = 10 + (int) (loaderProgress.progressPercent() * 0.15);
                    sendProgress(IngestProgressUpdate.progress(
                            taskId, fileName, IngestPhase.OCR_PROCESSING, ocrPercent,
                            loaderProgress.currentStep(), loaderProgress.message(),
                            statsBuilder.build()));
                }
            });
            int documentsLoaded = loadedDocuments.size();
            loadingEndTime = System.currentTimeMillis();
            long loadingDuration = loadingEndTime - loadingStartTime;

            logger.info("[Task {}] Loaded {} documents in {}ms", taskId, documentsLoaded, loadingDuration);

            statsBuilder.documentsLoaded(documentsLoaded)
                    .loadingTimeMs(loadingDuration)
                    .totalProcessingTimeMs(System.currentTimeMillis() - startTime);

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.LOADING, 25,
                    "Documents loaded",
                    String.format("Loaded %d document(s) in %dms using %s",
                            documentsLoaded, loadingDuration, selectedLoader.getName()),
                    statsBuilder.build()));

            if (loadedDocuments.isEmpty()) {
                throw new RuntimeException("No documents could be loaded from file: " + fileName);
            }

            // Log loading phase completion
            final int documentsLoadedFinal = documentsLoaded;
            logEvent(() -> ingestEventService.logPhaseCompleted(taskId, fileName,
                    IngestEvent.IngestPhase.LOADING, documentsLoadedFinal,
                    "Loaded " + documentsLoadedFinal + " documents"));

            // Check for cancellation after loading
            if (isTaskCancelled(taskId)) {
                logger.info("[Task {}] Task was cancelled after document loading", taskId);
                throw new TaskCancelledException("Task cancelled after loading " + documentsLoaded + " documents");
            }

            // Phase 2: Text Conversion (rich format -> plain text)
            conversionStartTime = System.currentTimeMillis();
            currentEventPhase = IngestEvent.IngestPhase.CONVERTING;
            logEvent(() -> ingestEventService.logPhaseStarted(taskId, fileName,
                    IngestEvent.IngestPhase.CONVERTING, IngestEvent.IngestPhase.LOADING));

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.CONVERTING, 27,
                    "Converting to plain text",
                    String.format("Converting %d documents to plain text...", documentsLoaded),
                    statsBuilder.build()));

            // Perform text conversion
            TextConversionService.ConversionResult conversionResult = textConversionService.convert(loadedDocuments);
            List<Document> convertedDocuments = conversionResult.documents();
            conversionEndTime = System.currentTimeMillis();
            long conversionDuration = conversionEndTime - conversionStartTime;

            logger.info("[Task {}] Converted {} documents in {}ms ({}→{} chars, {}% compression)",
                    taskId, conversionResult.documentsProcessed(), conversionDuration,
                    conversionResult.inputChars(), conversionResult.outputChars(),
                    String.format("%.1f", conversionResult.getCompressionRatio() * 100));

            statsBuilder.conversionTimeMs(conversionDuration)
                    .totalProcessingTimeMs(System.currentTimeMillis() - startTime);

            // Log any conversion warnings
            if (!conversionResult.warnings().isEmpty()) {
                for (String warning : conversionResult.warnings()) {
                    logger.warn("[Task {}] Conversion warning: {}", taskId, warning);
                }
            }

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.CONVERTING, 29,
                    "Conversion complete",
                    String.format("Converted %d docs in %dms (%.0f%% of original size)",
                            conversionResult.documentsProcessed(), conversionDuration,
                            conversionResult.getCompressionRatio() * 100),
                    statsBuilder.build()));

            // Log conversion phase completion
            final int convertedCount = conversionResult.documentsProcessed();
            logEvent(() -> ingestEventService.logPhaseCompleted(taskId, fileName,
                    IngestEvent.IngestPhase.CONVERTING, convertedCount,
                    String.format("Converted %d documents, %.0f%% of original",
                            convertedCount, conversionResult.getCompressionRatio() * 100)));

            // Check for cancellation after conversion
            if (isTaskCancelled(taskId)) {
                logger.info("[Task {}] Task was cancelled after text conversion", taskId);
                throw new TaskCancelledException("Task cancelled after converting " + convertedCount + " documents");
            }

            // Use converted documents for subsequent processing
            loadedDocuments = convertedDocuments;

            // Phase 3 & 4 & 5: Parallel Chunking, Embedding, and Indexing using the new
            // pipeline
            chunkingStartTime = System.currentTimeMillis();
            currentEventPhase = IngestEvent.IngestPhase.CHUNKING;
            logEvent(() -> ingestEventService.logPhaseStarted(taskId, fileName,
                    IngestEvent.IngestPhase.CHUNKING, IngestEvent.IngestPhase.CONVERTING));

            TextChunker selectedChunker = findChunker(chunkerName);
            String actualChunkerUsed = selectedChunker != null && !isNoOpChunker(selectedChunker)
                    ? selectedChunker.getName()
                    : "none";
            statsBuilder.chunkerUsed(actualChunkerUsed);

            // Get adaptive pipeline configuration
            AdaptivePipelineConfig pipelineConfig = new AdaptivePipelineConfig();
            logger.info("[Task {}] Using parallel pipeline: {}", taskId, pipelineConfig);

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.CHUNKING, 30,
                    "Starting parallel pipeline",
                    String.format("Processing %d docs with %d workers, batch size %d",
                            documentsLoaded, pipelineConfig.getOptimalParallelism(),
                            pipelineConfig.getOptimalBatchSize()),
                    statsBuilder.build()));

            // Verify indexer is available
            if (indexerService == null) {
                throw new RuntimeException("No indexer service available");
            }

            // Setup chunking options from configuration (optimized defaults)
            Map<String, Object> chunkingOptions = ingestConfiguration.getChunkingOptions();
            logger.debug("[Task {}] Using chunking options: chunkSize={}, overlap={}",
                    taskId, chunkingOptions.get("chunkSize"), chunkingOptions.get("overlap"));

            // Create and configure the parallel pipeline
            final String finalTaskId = taskId;
            final String finalFileName = fileName;
            final long finalStartTime = startTime;
            final String finalLoaderName = selectedLoader.getName();
            final String finalChunkerUsed = actualChunkerUsed;
            final int finalDocumentsLoaded = documentsLoaded;

            PipelineResult pipelineResult;

            // PERFORMANCE: Use configured batch size for pipeline indexing
            // Adaptive batch size considers memory pressure
            int batchSize = ingestConfiguration.calculateOptimalBatchSize(documentsLoaded * 10); // Estimate 10
                                                                                                 // chunks/doc

            try (ParallelIngestPipeline pipeline = new ParallelIngestPipeline(
                    selectedChunker != null && !isNoOpChunker(selectedChunker) ? selectedChunker : null,
                    embeddingModel, // Pass the embedding model to enable actual embedding
                    indexerService,
                    chunkingOptions,
                    batchSize)) {

                // Register pipeline for cancellation support
                activePipelines.put(taskId, pipeline);

                // Update job history with processing parameters
                final String embeddingModelName = embeddingModel != null ? embeddingModel.getClass().getSimpleName()
                        : "none";
                final String indexerName = indexerService != null ? indexerService.getClass().getSimpleName() : "none";
                final int chunkSizeVal = chunkingOptions.containsKey("chunkSize")
                        ? (int) chunkingOptions.get("chunkSize")
                        : 0;
                final int chunkOverlapVal = chunkingOptions.containsKey("overlap")
                        ? (int) chunkingOptions.get("overlap")
                        : 0;
                final int finalBatchSize = batchSize;
                final int workerCount = pipelineConfig.getOptimalParallelism();
                updateJobHistory(() -> indexingJobHistoryService.updateJobParameters(
                        finalTaskId,
                        finalLoaderName,
                        finalChunkerUsed,
                        embeddingModelName,
                        indexerName,
                        chunkSizeVal,
                        chunkOverlapVal,
                        finalBatchSize,
                        workerCount,
                        true, // parallel processing enabled
                        ingestConfiguration.isAdaptiveBatchSize()));

                // Check if already cancelled before starting pipeline
                if (isTaskCancelled(taskId)) {
                    logger.info("[Task {}] Task was cancelled before pipeline started", taskId);
                    throw new TaskCancelledException("Task cancelled before pipeline started");
                }

                // Set progress callback to forward updates to WebSocket
                pipeline.setProgressCallback(progress -> {
                    // Map pipeline phase to ingest phase
                    IngestPhase phase = switch (progress.phase()) {
                        case "chunking" -> IngestPhase.CHUNKING;
                        case "indexing" -> IngestPhase.INDEXING;
                        case "complete" -> IngestPhase.COMPLETED;
                        default -> IngestPhase.EMBEDDING;
                    };

                    // DEBUG: Log what we receive from pipeline
                    logger.info("[Task {}] Pipeline callback - workerStatuses null? {}, size: {}",
                            finalTaskId,
                            progress.workerStatuses() == null,
                            progress.workerStatuses() != null ? progress.workerStatuses().size() : 0);
                    if (progress.workerStatuses() != null && !progress.workerStatuses().isEmpty()) {
                        for (var ws : progress.workerStatuses()) {
                            logger.info("[Task {}] Pipeline worker: type={}, id={}, status={}",
                                    finalTaskId, ws.workerType(), ws.workerId(), ws.status());
                        }
                    }

                    // Convert worker statuses from pipeline to DTO
                    List<IngestProgressUpdate.WorkerStatusDto> workerDtos = progress.workerStatuses() != null
                            ? progress.workerStatuses().stream()
                                    .map(w -> new IngestProgressUpdate.WorkerStatusDto(
                                            w.workerId(), w.workerType(), w.status(),
                                            w.itemsProcessed(), w.currentBatchSize(),
                                            w.throughput(), w.currentItem()))
                                    .collect(java.util.stream.Collectors.toList())
                            : List.of();

                    // Convert queue status
                    IngestProgressUpdate.QueueStatusDto queueDto = progress.queueStatus() != null
                            ? new IngestProgressUpdate.QueueStatusDto(
                                    progress.queueStatus().chunkQueueSize(),
                                    progress.queueStatus().chunkQueueCapacity(),
                                    progress.queueStatus().embeddedQueueSize(),
                                    progress.queueStatus().embeddedQueueCapacity(),
                                    progress.queueStatus().chunkQueueUtilization(),
                                    progress.queueStatus().embeddedQueueUtilization())
                            : null;

                    // Convert embedding batch metrics
                    IngestProgressUpdate.EmbeddingBatchMetrics embeddingBatchMetrics = null;
                    if (progress.currentEmbeddingBatch() != null) {
                        var batch = progress.currentEmbeddingBatch();
                        embeddingBatchMetrics = IngestProgressUpdate.EmbeddingBatchMetrics.builder()
                                .batchNumber(batch.batchNumber())
                                .totalBatches(batch.totalBatches())
                                .inputTexts(batch.inputTexts())
                                .inputTokens(batch.inputTokens())
                                .maxSequenceLength(batch.maxSequenceLength())
                                .avgSequenceLength(batch.avgSequenceLength())
                                .outputVectors(batch.outputVectors())
                                .embeddingDimension(batch.embeddingDimension())
                                .outputSizeBytes(batch.outputSizeBytes())
                                .tokenizationTimeMs(batch.tokenizationTimeMs())
                                .inferenceTimeMs(batch.inferenceTimeMs())
                                .totalBatchTimeMs(batch.totalBatchTimeMs())
                                // Detailed timing breakdown
                                .paddingTimeMs(batch.paddingTimeMs())
                                .tensorCreationTimeMs(batch.tensorCreationTimeMs())
                                .forwardPassTimeMs(batch.forwardPassTimeMs())
                                .extractionTimeMs(batch.extractionTimeMs())
                                // Heartbeat/liveness
                                .currentStep(batch.currentStep())
                                .heartbeatSeconds(batch.heartbeatSeconds())
                                .stepStartTimeMs(batch.stepStartTimeMs())
                                .isStuck(batch.isStuck())
                                // Throughput
                                .tokensPerSecond(batch.tokensPerSecond())
                                .embeddingsPerSecond(batch.embeddingsPerSecond())
                                .batchThroughput(batch.batchThroughput())
                                .modelName(batch.modelName())
                                .deviceType(batch.deviceType())
                                .isBatched(batch.isBatched())
                                // ========== NEW DETAILED FIELDS ==========
                                // Source documents
                                .sourceDocuments(batch.sourceDocuments())
                                .sourceDocumentCount(batch.sourceDocumentCount())
                                // Tensor shapes
                                .inputTensorShape(batch.inputTensorShape())
                                .outputTensorShape(batch.outputTensorShape())
                                .actualInputShape(batch.actualInputShape())
                                .actualOutputShape(batch.actualOutputShape())
                                // Status
                                .statusLevel(batch.statusLevel())
                                .etaMessage(batch.etaMessage())
                                // Per-passage token counts
                                .passageTokenCounts(batch.passageTokenCounts())
                                .build();
                    }

                    // Build stats from progress
                    IngestStats pipelineStats = IngestStats.builder()
                            .documentsLoaded(finalDocumentsLoaded)
                            .chunksCreated(progress.chunksCreated())
                            .chunksEmbedded(progress.chunksEmbedded())
                            .chunksIndexed(progress.chunksIndexed())
                            .documentsIndexed(progress.chunksIndexed())
                            .totalProcessingTimeMs(System.currentTimeMillis() - finalStartTime)
                            .loaderUsed(finalLoaderName)
                            .chunkerUsed(finalChunkerUsed)
                            .currentBatch(progress.batchesCompleted())
                            .parallelProcessing(true)
                            .workerThreads(progress.embeddingWorkers() + 1) // embedding + indexing
                            .chunksPerSecond(progress.chunksPerSecond())
                            .memoryUsagePercent(progress.memoryUsagePercent())
                            .memoryStatus(progress.memoryUsagePercent() > 90 ? "CRITICAL"
                                    : (progress.memoryUsagePercent() > 80 ? "WARNING" : "OK"))
                            .activeStage(progress.phase().toUpperCase())
                            .pipelineStatus("PROCESSING")
                            .workerStatuses(workerDtos)
                            .queueStatus(queueDto)
                            .currentEmbeddingBatch(embeddingBatchMetrics)
                            .build();

                    // Map progress percent: pipeline reports 0-100, we want 30-95 for
                    // chunking+indexing
                    int mappedProgress = 30 + (int) (progress.progressPercent() * 0.65);

                    // Show created chunks during chunking, indexed chunks during indexing
                    String statusDetail;
                    if ("chunking".equals(progress.phase())) {
                        statusDetail = String.format("%d chunks created (%.1f/sec) | Memory: %.0f%%",
                                progress.chunksCreated(), progress.chunksPerSecond(),
                                progress.memoryUsagePercent());
                    } else {
                        statusDetail = String.format("%d/%d indexed (%.1f/sec) | Memory: %.0f%%",
                                progress.chunksIndexed(), progress.chunksCreated(),
                                progress.chunksPerSecond(), progress.memoryUsagePercent());
                    }

                    sendThrottledProgress(IngestProgressUpdate.progress(
                            finalTaskId, finalFileName, phase, mappedProgress,
                            progress.phase() + ": " + progress.message(),
                            statusDetail,
                            pipelineStats));
                });

                // Run the pipeline
                pipelineResult = pipeline.process(loadedDocuments);

                // Check if pipeline encountered an error (check before closing try block)
                if (pipeline.hasError()) {
                    String errorMsg = pipeline.getLastError();
                    logger.error("[Task {}] Pipeline failed with error: {}", taskId, errorMsg);
                    throw new RuntimeException("Pipeline error: " + errorMsg);
                }
            } finally {
                // Always unregister the pipeline
                activePipelines.remove(taskId);
            }

            // Check if job was killed by memory watchdog (takes priority over user
            // cancellation)
            if (memoryWatchdogService != null && memoryWatchdogService.isJobKilled(taskId)) {
                IngestConfiguration.MemoryInfo killMemInfo = ingestConfiguration.getMemoryInfo();
                logger.error("[Task {}] Job was killed by memory watchdog during pipeline execution", taskId);
                throw new MemoryKilledException(
                        String.format("Job forcibly killed: memory %.1f%% exceeded kill threshold %d%%",
                                killMemInfo.usagePercent(), ingestConfiguration.getMemoryKillThresholdPercent()),
                        killMemInfo.usagePercent(),
                        ingestConfiguration.getMemoryKillThresholdPercent());
            }

            // Check if task was cancelled by user during pipeline execution
            if (isTaskCancelled(taskId)) {
                logger.info("[Task {}] Task was cancelled during pipeline execution", taskId);
                throw new TaskCancelledException("Task cancelled during pipeline execution");
            }

            // Clear loaded documents
            loadedDocuments.clear();
            loadedDocuments = null;

            chunkingEndTime = System.currentTimeMillis();
            embeddingStartTime = chunkingStartTime; // Pipeline handles both phases

            int totalChunks = pipelineResult.chunksCreated();
            int processedChunks = pipelineResult.chunksIndexed();
            List<String> processedDocumentIds = pipelineResult.processedDocumentIds();

            logger.info("[Task {}] Pipeline complete: {} docs -> {} chunks in {}ms ({} chunks/sec)",
                    taskId, pipelineResult.documentsProcessed(), totalChunks,
                    pipelineResult.totalTimeMs(), String.format("%.1f", pipelineResult.getChunksPerSecond()));

            long totalTime = System.currentTimeMillis() - startTime;
            long finalEmbeddingDuration = System.currentTimeMillis() - embeddingStartTime;
            long finalLoadingDuration = loadingEndTime - loadingStartTime;
            long finalConversionDuration = conversionEndTime - conversionStartTime;
            long finalChunkingDuration = chunkingEndTime - chunkingStartTime;

            // Calculate final throughput
            double finalChunksPerSec = totalTime > 0 ? (totalChunks * 1000.0 / totalTime) : 0;
            double finalDocsPerSec = totalTime > 0 ? (documentsLoaded * 1000.0 / totalTime) : 0;

            // Get final memory status
            IngestConfiguration.MemoryInfo finalMemInfo = ingestConfiguration.getMemoryInfo();

            logger.info("[Task {}] Completed in {}ms (load: {}ms, convert: {}ms, chunk: {}ms, embed: {}ms). " +
                    "Documents: {}, Chunks: {}, Throughput: {} chunks/sec",
                    taskId, totalTime, finalLoadingDuration, finalConversionDuration, finalChunkingDuration,
                    finalEmbeddingDuration, documentsLoaded, totalChunks, String.format("%.1f", finalChunksPerSec));

            // Log completion event
            final long totalTimeFinal = totalTime;
            final int totalChunksFinal = totalChunks;
            logEvent(() -> ingestEventService.logCompleted(taskId, fileName, totalChunksFinal,
                    String.format("Processed %d chunks in %dms (%.1f chunks/sec)",
                            totalChunksFinal, totalTimeFinal, finalChunksPerSec)));

            // Update job history with final stats and mark completed
            final int docsLoadedForHistory = documentsLoaded;
            final int finalProcessedChunks = processedChunks;
            final long finalLoadDuration = finalLoadingDuration;
            final long finalConvDuration = finalConversionDuration;
            final long finalChunkDuration = finalChunkingDuration;
            final long finalEmbedDuration = finalEmbeddingDuration;
            updateJobHistory(() -> {
                indexingJobHistoryService.updateJobStats(taskId, docsLoadedForHistory, totalChunksFinal,
                        finalProcessedChunks, finalProcessedChunks);
                indexingJobHistoryService.updatePhaseTiming(taskId, IngestEvent.IngestPhase.LOADING, finalLoadDuration);
                indexingJobHistoryService.updatePhaseTiming(taskId, IngestEvent.IngestPhase.CONVERTING,
                        finalConvDuration);
                indexingJobHistoryService.updatePhaseTiming(taskId, IngestEvent.IngestPhase.CHUNKING,
                        finalChunkDuration);
                indexingJobHistoryService.updatePhaseTiming(taskId, IngestEvent.IngestPhase.EMBEDDING,
                        finalEmbedDuration);
                indexingJobHistoryService.updatePhaseTiming(taskId, IngestEvent.IngestPhase.INDEXING,
                        finalEmbedDuration);
                indexingJobHistoryService.markJobCompleted(taskId);
            });

            // Send completion with comprehensive stats
            IngestStats finalStats = IngestProgressUpdate.IngestStats.builder()
                    .documentsLoaded(documentsLoaded)
                    .chunksCreated(totalChunks)
                    .chunksEmbedded(processedChunks)
                    .chunksIndexed(processedChunks)
                    .documentsIndexed(processedChunks)
                    .totalProcessingTimeMs(totalTime)
                    .loaderUsed(selectedLoader.getName())
                    .chunkerUsed(actualChunkerUsed)
                    .processedDocumentIds(processedDocumentIds)
                    .loadingTimeMs(finalLoadingDuration)
                    .conversionTimeMs(finalConversionDuration)
                    .chunkingTimeMs(finalChunkingDuration)
                    .embeddingTimeMs(finalEmbeddingDuration)
                    .indexingTimeMs(finalEmbeddingDuration) // Combined with embedding
                    .batchSize(pipelineConfig.getOptimalBatchSize())
                    .parallelProcessing(true)
                    .workerThreads(pipelineConfig.getOptimalParallelism())
                    .chunksPerSecond(finalChunksPerSec)
                    .docsPerSecond(finalDocsPerSec)
                    .memoryUsagePercent(finalMemInfo.usagePercent())
                    .memoryStatus(finalMemInfo.criticalExceeded() ? "CRITICAL"
                            : (finalMemInfo.thresholdExceeded() ? "WARNING" : "OK"))
                    .build();

            sendProgress(IngestProgressUpdate.completed(taskId, fileName, finalStats));

        } catch (TaskCancelledException e) {
            // Task was cancelled by user - progress and event already sent via cancelTask()
            // Note: cancelTask() already logged the cancellation event, so we skip logging
            // here
            // to avoid duplicate events in the log
            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("[Task {}] Cancelled by user after {}ms: {}",
                    taskId, totalTime, e.getMessage());
            // Clear the cancellation flag
            clearCancellation(taskId);
            // Update job history
            final IngestEvent.IngestPhase cancelledPhase = currentEventPhase;
            updateJobHistory(() -> indexingJobHistoryService.markJobCancelled(taskId, cancelledPhase, e.getMessage()));
        } catch (MemoryKilledException e) {
            // Job was forcibly killed due to memory exceeding kill threshold
            // Note: The MemoryWatchdogService already logged the MEMORY_KILLED audit event
            // We just need to send the final progress update
            long totalTime = System.currentTimeMillis() - startTime;
            logger.error("[Task {}] MEMORY KILLED after {}ms: {} (memory: {}%, threshold: {}%)",
                    taskId, totalTime, e.getMessage(), String.format("%.1f", e.getMemoryPercent()),
                    e.getKillThreshold());

            // Update job history
            final IngestEvent.IngestPhase killedPhase = currentEventPhase;
            updateJobHistory(
                    () -> indexingJobHistoryService.markJobMemoryKilled(taskId, killedPhase, e.getMemoryPercent()));

            sendProgress(IngestProgressUpdate.memoryKilled(
                    taskId, fileName,
                    String.format("Job forcibly killed - memory %.1f%% exceeded kill threshold %d%%",
                            e.getMemoryPercent(), e.getKillThreshold())));
        } catch (MemoryPressureException e) {
            // Memory pressure exceptions are already handled with proper cancellation
            // message
            long totalTime = System.currentTimeMillis() - startTime;
            logger.warn("[Task {}] Stopped due to memory pressure after {}ms: {}",
                    taskId, totalTime, e.getMessage());
            // Log cancellation event
            final IngestEvent.IngestPhase cancelPhase = currentEventPhase;
            logEvent(() -> ingestEventService.logCancelled(taskId, fileName, cancelPhase,
                    "Memory pressure: " + e.getMessage()));
            // Update job history
            updateJobHistory(() -> indexingJobHistoryService.markJobCancelled(taskId, cancelPhase,
                    "Memory pressure: " + e.getMessage()));
        } catch (InterruptedException e) {
            long totalTime = System.currentTimeMillis() - startTime;
            logger.warn("[Task {}] Interrupted after {}ms", taskId, totalTime);
            Thread.currentThread().interrupt();

            // Log cancellation event
            final IngestEvent.IngestPhase cancelPhase = currentEventPhase;
            logEvent(() -> ingestEventService.logCancelled(taskId, fileName, cancelPhase, "Interrupted"));
            // Update job history
            updateJobHistory(() -> indexingJobHistoryService.markJobCancelled(taskId, cancelPhase, "Interrupted"));

            sendProgress(IngestProgressUpdate.cancelled(
                    taskId, fileName, IngestPhase.FAILED,
                    "Job was interrupted",
                    IngestStats.builder().totalProcessingTimeMs(totalTime).build()));
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            logger.error("[Task {}] Failed after {}ms: {}", taskId, totalTime, e.getMessage(), e);

            // Log failure event
            final IngestEvent.IngestPhase failPhase = currentEventPhase;
            logEvent(() -> ingestEventService.logFailed(taskId, fileName, failPhase, e.getMessage(), e));
            // Update job history
            updateJobHistory(() -> indexingJobHistoryService.markJobFailed(taskId, failPhase, e.getMessage(), e,
                    determineFailureReason(e, failPhase)));

            sendProgress(IngestProgressUpdate.failed(
                    taskId, fileName, IngestPhase.FAILED, e.getMessage(),
                    IngestStats.builder().totalProcessingTimeMs(totalTime).build()));
        } finally {
            // Unregister from memory watchdog
            if (memoryWatchdogService != null) {
                memoryWatchdogService.unregisterJob(taskId);
            }

            // Clear any remaining cancellation flag
            clearCancellation(taskId);

            // Always complete the job to free up the slot
            ingestConfiguration.completeJob();
            logger.debug("[Task {}] Job completed, active jobs: {}", taskId, ingestConfiguration.getActiveJobCount());
        }
    }

    /**
     * Checks if the job should stop due to memory pressure.
     * This checks both the watchdog signal and current memory state.
     * Throws MemoryKilledException if the job has been forcibly killed.
     */

    /**
     * Determines whether to use subprocess mode based on the requested
     * ProcessingMode.
     *
     * @param mode   the requested processing mode
     * @param taskId task ID for logging
     * @return true if subprocess mode should be used, false for in-process mode
     */
    private boolean determineUseSubprocess(ProcessingMode mode, String taskId) {
        logger.info(
                "[Task {}] determineUseSubprocess: mode={}, subprocessIngestLauncher={}, subprocessConfigService={}",
                taskId, mode,
                subprocessIngestLauncher != null ? subprocessIngestLauncher.getClass().getSimpleName() : "NULL",
                subprocessConfigService != null ? "available" : "NULL");

        switch (mode) {
            case SUBPROCESS:
                // Force subprocess mode - only fallback if launcher unavailable
                if (subprocessIngestLauncher == null) {
                    logger.warn("[Task {}] SUBPROCESS mode requested but launcher not available! " +
                            "SubprocessIngestLauncher bean was not injected. Using in-process.",
                            taskId);
                    return false;
                }
                logger.info("[Task {}] SUBPROCESS mode: returning true (will use subprocess)", taskId);
                return true;

            case INPROCESS:
                // Force in-process mode
                logger.info("[Task {}] INPROCESS mode: returning false (will use in-process)", taskId);
                return false;

            case AUTO:
            default:
                // Use global configuration
                boolean globalEnabled = subprocessConfigService != null && subprocessConfigService.isEnabled();
                logger.info("[Task {}] AUTO mode: global subprocess enabled={}, returning {}",
                        taskId, globalEnabled, globalEnabled);
                return globalEnabled;
        }
    }

    /**
     * Exception thrown when a job is stopped due to memory pressure.
     */
    public static class MemoryPressureException extends RuntimeException {
        public MemoryPressureException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when a task is cancelled by user request.
     */
    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when a task is forcibly killed due to memory exceeding kill
     * threshold.
     * This is more severe than MemoryPressureException - the job must terminate
     * immediately.
     */
    public static class MemoryKilledException extends RuntimeException {
        private double memoryPercent;
        private int killThreshold;

        public MemoryKilledException(String message, double memoryPercent, int killThreshold) {
            super(message);
            this.memoryPercent = memoryPercent;
            this.killThreshold = killThreshold;
        }

        public double getMemoryPercent() {
            return memoryPercent;
        }

        public int getKillThreshold() {
            return killThreshold;
        }
    }

    /**
     * Gets the current status of a task.
     */
    public Optional<IngestProgressUpdate> getTaskStatus(String taskId) {
        return Optional.ofNullable(activeTasksStatus.get(taskId));
    }

    /**
     * Gets all active tasks.
     */
    public Collection<IngestProgressUpdate> getAllActiveTasks() {
        return activeTasksStatus.values();
    }

    /**
     * Submit an ingest job through the resource-aware scheduler.
     * The scheduler queues the job and dispatches it when GPU resources are available.
     *
     * <p>Falls back to direct {@link #processDocumentAsync} if the scheduler is not available.</p>
     *
     * @return CompletableFuture that completes when the ingest finishes
     */
    public CompletableFuture<ScheduledJob.JobResult> scheduleIngest(
            String taskId, Path filePath, String loaderName, String chunkerName,
            Map<String, Object> options) {
        if (resourceScheduler == null) {
            logger.debug("[Task {}] No scheduler available, falling back to direct async ingest", taskId);
            processDocumentAsync(taskId, filePath, loaderName, chunkerName, ProcessingMode.AUTO, options);
            return CompletableFuture.completedFuture(
                    new ScheduledJob.JobResult(true, null, 0));
        }

        String fileName = filePath.getFileName().toString();
        ScheduledJob job = ScheduledJob.builder()
                .jobId(taskId)
                .jobType(JobResourceProfiles.INGEST.serviceType())
                .description("Ingest: " + fileName)
                .resourceProfile(JobResourceProfiles.INGEST)
                .priority(50)
                .metadata(Map.of(
                        "filePath", filePath.toString(),
                        "loaderName", loaderName != null ? loaderName : "",
                        "chunkerName", chunkerName != null ? chunkerName : ""
                ))
                .executor(ctx -> processDocumentAsync(
                        taskId, filePath, loaderName, chunkerName, ProcessingMode.AUTO, options))
                .build();

        logger.info("[Task {}] Submitting ingest job to scheduler for '{}'", taskId, fileName);
        return resourceScheduler.submit(job);
    }

    /**
     * Cancels a task by its ID. The task will stop processing as soon as possible.
     * Returns true if the task was found and marked for cancellation, false
     * otherwise.
     */
    public boolean cancelTask(String taskId) {
        IngestProgressUpdate currentStatus = activeTasksStatus.get(taskId);
        if (currentStatus == null) {
            // Task may be running in subprocess mode (tracked by
            // SubprocessIngestLauncher/IngestProgressTracker)
            if (subprocessIngestLauncher != null && subprocessIngestLauncher.cancelIngest(taskId)) {
                logger.info("[Task {}] Subprocess task marked for cancellation", taskId);
                return true;
            }

            // Defensive fallback: if we have an active pipeline but no status entry, still
            // attempt cancellation
            ParallelIngestPipeline pipeline = activePipelines.get(taskId);
            if (pipeline != null) {
                cancelledTasks.add(taskId);
                pipeline.cancel();
                logger.info("[Task {}] Pipeline cancelled (status entry missing)", taskId);
                return true;
            }

            logger.warn("[Task {}] Cannot cancel - task not found", taskId);
            return false;
        }

        // Check if task is already completed or failed
        IngestProgressUpdate.IngestStatus status = currentStatus.status();
        if (status == IngestProgressUpdate.IngestStatus.COMPLETED ||
                status == IngestProgressUpdate.IngestStatus.FAILED ||
                status == IngestProgressUpdate.IngestStatus.CANCELLED) {
            logger.info("[Task {}] Cannot cancel - task already in terminal state: {}", taskId, status);
            return false;
        }

        // Mark task for cancellation
        cancelledTasks.add(taskId);
        logger.info("[Task {}] Task marked for cancellation", taskId);

        // Cancel the pipeline if it's actively processing
        ParallelIngestPipeline pipeline = activePipelines.get(taskId);
        if (pipeline != null) {
            pipeline.cancel();
            logger.info("[Task {}] Pipeline cancelled", taskId);
        }

        // Send immediate cancellation progress update
        IngestProgressUpdate.IngestStats stats = currentStatus.stats() != null ? currentStatus.stats()
                : IngestProgressUpdate.IngestStats.empty();

        sendProgress(IngestProgressUpdate.cancelled(
                taskId,
                currentStatus.fileName(),
                currentStatus.phase(),
                "Cancelled by user request",
                stats));

        // Log cancellation event immediately so it appears in the event log
        // The async task will also log when it catches TaskCancelledException,
        // but this ensures the event is logged even if the task hasn't started yet
        final String fileName = currentStatus.fileName();
        final IngestPhase currentPhase = currentStatus.phase();
        logEvent(() -> {
            // Map IngestProgressUpdate.IngestPhase to IngestEvent.IngestPhase
            IngestEvent.IngestPhase eventPhase = mapToEventPhase(currentPhase);
            ingestEventService.logCancelled(taskId, fileName, eventPhase, "Cancelled by user request");
        });

        return true;
    }

    /**
     * Maps IngestProgressUpdate.IngestPhase to IngestEvent.IngestPhase.
     */
    private IngestEvent.IngestPhase mapToEventPhase(IngestPhase phase) {
        if (phase == null) {
            return IngestEvent.IngestPhase.QUEUED;
        }
        return switch (phase) {
            case QUEUED -> IngestEvent.IngestPhase.QUEUED;
            case UPLOADING -> IngestEvent.IngestPhase.QUEUED; // No direct mapping, use QUEUED
            case LOADING -> IngestEvent.IngestPhase.LOADING;
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
     * Checks if a task has been cancelled.
     */
    public boolean isTaskCancelled(String taskId) {
        return cancelledTasks.contains(taskId);
    }

    /**
     * Clears the cancellation flag for a task.
     * Called after the task has been fully stopped.
     */
    private void clearCancellation(String taskId) {
        cancelledTasks.remove(taskId);
    }

    /**
     * Sends a progress update with throttling to prevent overwhelming WebSocket
     * clients.
     * Updates are rate-limited to at most one every PROGRESS_UPDATE_INTERVAL_MS
     * milliseconds,
     * except for completion/failure updates which are always sent immediately.
     *
     * <p>
     * <b>Thread Safety:</b> Uses atomic compare-and-set to prevent duplicate sends
     * from concurrent threads. WebSocket sends are offloaded to a dedicated
     * executor
     * to avoid blocking worker threads.
     * </p>
     */
    private void sendThrottledProgress(IngestProgressUpdate update) {
        // Always send completion/failure updates immediately
        IngestProgressUpdate.IngestStatus status = update.status();
        if (status == IngestProgressUpdate.IngestStatus.COMPLETED ||
                status == IngestProgressUpdate.IngestStatus.FAILED ||
                status == IngestProgressUpdate.IngestStatus.CANCELLED) {
            sendProgressAsync(update);
            return;
        }

        // Always update stored status so getTaskStatus() returns latest
        activeTasksStatus.put(update.taskId(), update);

        // Throttle WebSocket sends to prevent UI overload
        // Use compareAndSet for atomic check-then-act to prevent race conditions
        long now = System.currentTimeMillis();
        long lastUpdate = lastProgressUpdateTime.get();
        if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
            // Atomically try to claim this update slot
            // Only one thread will succeed; others will see the updated timestamp and skip
            if (lastProgressUpdateTime.compareAndSet(lastUpdate, now)) {
                sendProgressAsync(update);
            } else {
                // Another thread beat us - that's fine, they'll send the update
                logger.debug("[Task {}] Lost throttle race, skipping duplicate send", update.taskId());
            }
        } else {
            // Log throttled updates at debug level
            logger.debug("[Task {}] Throttled progress update ({}ms since last): phase={}, message={}",
                    update.taskId(), now - lastUpdate, update.phase(), update.message());
        }
        // If throttled, status is still updated in activeTasksStatus above
    }

    /**
     * Sends a progress update asynchronously via WebSocket.
     * This method returns immediately without blocking the caller thread.
     *
     * <p>
     * WebSocket I/O is offloaded to a dedicated executor to prevent blocking
     * worker threads (especially the embedding thread which is
     * performance-critical).
     * </p>
     */
    private void sendProgressAsync(IngestProgressUpdate update) {
        // Submit WebSocket send to dedicated executor - returns immediately
        webSocketExecutor.submit(() -> {
            try {
                sendProgress(update);
            } catch (Exception e) {
                logger.warn("[Task {}] Async WebSocket send failed: {}", update.taskId(), e.getMessage());
            }
        });
    }

    private void sendProgress(IngestProgressUpdate update) {
        // Enforce bounds on activeTasksStatus to prevent memory leak
        if (activeTasksStatus.size() >= MAX_ACTIVE_TASKS) {
            // Remove oldest completed/failed entries first
            Iterator<Map.Entry<String, IngestProgressUpdate>> iter = activeTasksStatus.entrySet().iterator();
            while (iter.hasNext() && activeTasksStatus.size() >= MAX_ACTIVE_TASKS) {
                Map.Entry<String, IngestProgressUpdate> entry = iter.next();
                IngestProgressUpdate.IngestStatus status = entry.getValue().status();
                if (status == IngestProgressUpdate.IngestStatus.COMPLETED ||
                        status == IngestProgressUpdate.IngestStatus.FAILED) {
                    iter.remove();
                }
            }
        }

        // Store latest status
        activeTasksStatus.put(update.taskId(), update);

        // Schedule cleanup for completed/failed/cancelled tasks using shared scheduler
        // (not spawning threads)
        // MEMORY LEAK FIX: Include CANCELLED status in cleanup to prevent accumulation
        if (update.status() == IngestProgressUpdate.IngestStatus.COMPLETED ||
                update.status() == IngestProgressUpdate.IngestStatus.FAILED ||
                update.status() == IngestProgressUpdate.IngestStatus.CANCELLED) {
            final String taskIdToRemove = update.taskId();
            cleanupScheduler.schedule(() -> {
                activeTasksStatus.remove(taskIdToRemove);
                // Also ensure cancelled task is cleaned from the cancellation set
                cancelledTasks.remove(taskIdToRemove);
                logger.trace("Cleaned up terminal task status: {} (status={})", taskIdToRemove, update.status());
            }, 5, TimeUnit.MINUTES);
        }

        // Send to WebSocket topic (only if messaging template is available)
        if (messagingTemplate != null) {
            String topic = "/topic/ingest/" + update.taskId();
            // Use INFO level for important progress updates to verify they're being sent
            int workerCount = update.stats() != null && update.stats().workerStatuses() != null
                    ? update.stats().workerStatuses().size()
                    : 0;
            logger.info("[Task {}] WebSocket progress: phase={}, progress={}%, message={}, workers={}",
                    update.taskId(), update.phase(), update.progressPercent(), update.message(), workerCount);
            if (workerCount > 0) {
                logger.debug("[Task {}] Worker statuses: {}", update.taskId(), update.stats().workerStatuses());
            }
            messagingTemplate.convertAndSend(topic, update);

            // Also send to a general topic for monitoring all ingests
            messagingTemplate.convertAndSend("/topic/ingest/all", update);
        } else {
            logger.warn("[Task {}] NO WebSocket template - progress not sent: phase={}, progress={}%",
                    update.taskId(), update.phase(), update.progressPercent());
        }
    }

    /**
     * Processes a large document using streaming mode.
     *
     * <p>
     * Large documents are processed page-by-page to avoid memory pressure.
     * Pages are streamed directly into the pipeline's chunk queue, allowing
     * embedding and indexing to proceed in parallel with loading.
     * </p>
     *
     * @param taskId           Task identifier
     * @param filePath         Path to the document file
     * @param fileName         Original file name
     * @param sourceDescriptor Document source descriptor
     * @param selectedLoader   Loader to use (for fallback if streaming fails)
     * @param chunkerName      Requested chunker name
     * @param startTime        Processing start time
     * @param statsBuilder     Stats builder for progress updates
     */
    private void processLargeDocumentStreaming(
            String taskId,
            Path filePath,
            String fileName,
            DocumentSourceDescriptor sourceDescriptor,
            DocumentLoader selectedLoader,
            String chunkerName,
            long startTime,
            IngestStats.Builder statsBuilder) throws Exception {

        logger.info("[Task {}] Starting large document streaming processing for: {}", taskId, fileName);

        // Send streaming mode notification
        sendProgress(IngestProgressUpdate.progress(
                taskId, fileName, IngestPhase.LOADING, 18,
                "Large document detected",
                "Using streaming mode for memory-efficient processing",
                statsBuilder.build()));

        // Find chunker
        TextChunker selectedChunker = findChunker(chunkerName);
        String actualChunkerUsed = selectedChunker != null && !isNoOpChunker(selectedChunker)
                ? selectedChunker.getName()
                : "none";
        statsBuilder.chunkerUsed(actualChunkerUsed);

        // Verify indexer is available
        if (indexerService == null) {
            throw new RuntimeException("No indexer service available");
        }

        // Get chunking options
        Map<String, Object> chunkingOptions = ingestConfiguration.getChunkingOptions();

        // Calculate batch size
        int batchSize = ingestConfiguration.calculateOptimalBatchSize(500); // Estimate for large doc

        // Create pipeline with external producer support
        try (ParallelIngestPipeline pipeline = new ParallelIngestPipeline(
                selectedChunker != null && !isNoOpChunker(selectedChunker) ? selectedChunker : null,
                embeddingModel,
                indexerService,
                chunkingOptions,
                batchSize)) {

            // Register pipeline for cancellation
            activePipelines.put(taskId, pipeline);

            // Check for cancellation
            if (isTaskCancelled(taskId)) {
                throw new TaskCancelledException("Task cancelled before streaming started");
            }

            // Set progress callback with worker status support
            final String finalFileName = fileName;
            final long finalStartTime = startTime;
            pipeline.setProgressCallback(progress -> {
                IngestPhase phase = switch (progress.phase()) {
                    case "chunking" -> IngestPhase.CHUNKING;
                    case "indexing" -> IngestPhase.INDEXING;
                    case "complete" -> IngestPhase.COMPLETED;
                    default -> IngestPhase.EMBEDDING;
                };

                // DEBUG: Log what we receive from pipeline
                logger.info("[Task {}] Streaming callback - workerStatuses null? {}, size: {}, phase: {}",
                        taskId,
                        progress.workerStatuses() == null,
                        progress.workerStatuses() != null ? progress.workerStatuses().size() : 0,
                        progress.phase());

                // Convert worker statuses from pipeline to DTO
                List<IngestProgressUpdate.WorkerStatusDto> workerDtos = progress.workerStatuses() != null
                        ? progress.workerStatuses().stream()
                                .map(w -> new IngestProgressUpdate.WorkerStatusDto(
                                        w.workerId(), w.workerType(), w.status(),
                                        w.itemsProcessed(), w.currentBatchSize(),
                                        w.throughput(), w.currentItem()))
                                .collect(java.util.stream.Collectors.toList())
                        : List.of();

                // Convert queue status
                IngestProgressUpdate.QueueStatusDto queueDto = progress.queueStatus() != null
                        ? new IngestProgressUpdate.QueueStatusDto(
                                progress.queueStatus().chunkQueueSize(),
                                progress.queueStatus().chunkQueueCapacity(),
                                progress.queueStatus().embeddedQueueSize(),
                                progress.queueStatus().embeddedQueueCapacity(),
                                progress.queueStatus().chunkQueueUtilization(),
                                progress.queueStatus().embeddedQueueUtilization())
                        : null;

                // Convert embedding batch metrics
                IngestProgressUpdate.EmbeddingBatchMetrics embeddingBatchMetrics = null;
                if (progress.currentEmbeddingBatch() != null) {
                    var batch = progress.currentEmbeddingBatch();
                    embeddingBatchMetrics = IngestProgressUpdate.EmbeddingBatchMetrics.builder()
                            .batchNumber(batch.batchNumber())
                            .totalBatches(batch.totalBatches())
                            .inputTexts(batch.inputTexts())
                            .inputTokens(batch.inputTokens())
                            .maxSequenceLength(batch.maxSequenceLength())
                            .avgSequenceLength(batch.avgSequenceLength())
                            .outputVectors(batch.outputVectors())
                            .embeddingDimension(batch.embeddingDimension())
                            .outputSizeBytes(batch.outputSizeBytes())
                            .tokenizationTimeMs(batch.tokenizationTimeMs())
                            .inferenceTimeMs(batch.inferenceTimeMs())
                            .totalBatchTimeMs(batch.totalBatchTimeMs())
                            // Detailed timing breakdown
                            .paddingTimeMs(batch.paddingTimeMs())
                            .tensorCreationTimeMs(batch.tensorCreationTimeMs())
                            .forwardPassTimeMs(batch.forwardPassTimeMs())
                            .extractionTimeMs(batch.extractionTimeMs())
                            // Heartbeat/liveness
                            .currentStep(batch.currentStep())
                            .heartbeatSeconds(batch.heartbeatSeconds())
                            .stepStartTimeMs(batch.stepStartTimeMs())
                            .isStuck(batch.isStuck())
                            // Throughput
                            .tokensPerSecond(batch.tokensPerSecond())
                            .embeddingsPerSecond(batch.embeddingsPerSecond())
                            .batchThroughput(batch.batchThroughput())
                            .modelName(batch.modelName())
                            .deviceType(batch.deviceType())
                            .isBatched(batch.isBatched())
                            .passageTokenCounts(batch.passageTokenCounts())
                            .build();
                }

                // Build full stats with worker info
                IngestStats pipelineStats = IngestStats.builder()
                        .documentsLoaded(progress.documentsProcessed())
                        .chunksCreated(progress.chunksCreated())
                        .chunksEmbedded(progress.chunksEmbedded())
                        .chunksIndexed(progress.chunksIndexed())
                        .documentsIndexed(progress.chunksIndexed())
                        .totalProcessingTimeMs(System.currentTimeMillis() - finalStartTime)
                        .loaderUsed(statsBuilder.build().loaderUsed())
                        .chunkerUsed(statsBuilder.build().chunkerUsed())
                        .currentBatch(progress.batchesCompleted())
                        .parallelProcessing(true)
                        .workerThreads(progress.embeddingWorkers() + 1)
                        .chunksPerSecond(progress.chunksPerSecond())
                        .memoryUsagePercent(progress.memoryUsagePercent())
                        .memoryStatus(progress.memoryUsagePercent() > 90 ? "CRITICAL"
                                : (progress.memoryUsagePercent() > 80 ? "WARNING" : "OK"))
                        .activeStage(progress.phase().toUpperCase())
                        .pipelineStatus("PROCESSING")
                        .workerStatuses(workerDtos)
                        .queueStatus(queueDto)
                        .currentEmbeddingBatch(embeddingBatchMetrics)
                        .build();

                int mappedProgress = 30 + (int) (progress.progressPercent() * 0.65);

                // Build message showing full pipeline progress
                String currentStep;
                String statusMessage;
                if (progress.chunksIndexed() > 0) {
                    currentStep = String.format("Indexing: %d/%d", progress.chunksIndexed(), progress.chunksCreated());
                    statusMessage = String.format("%d created → %d embedded → %d indexed (%.1f/sec)",
                            progress.chunksCreated(), progress.chunksEmbedded(), progress.chunksIndexed(),
                            progress.chunksPerSecond());
                } else if (progress.chunksEmbedded() > 0) {
                    currentStep = String.format("Embedding: %d/%d", progress.chunksEmbedded(),
                            progress.chunksCreated());
                    statusMessage = String.format("%d created → %d embedded (%.1f/sec)",
                            progress.chunksCreated(), progress.chunksEmbedded(), progress.chunksPerSecond());
                } else {
                    currentStep = String.format("Processing: %d chunks queued", progress.chunksCreated());
                    statusMessage = String.format("%d chunks created, waiting for embedding...",
                            progress.chunksCreated());
                }

                sendThrottledProgress(IngestProgressUpdate.progress(
                        taskId, finalFileName, phase, mappedProgress,
                        currentStep,
                        statusMessage,
                        pipelineStats));
            });

            // Start pipeline workers (they will consume from the chunk queue)
            ParallelIngestPipeline.ExternalProducerHandle handle = pipeline.startWorkersForExternalProducer();

            // Send progress update for streaming start
            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.LOADING, 20,
                    "Streaming pages",
                    "Loading and processing pages in streaming mode...",
                    statsBuilder.build()));

            // Track last known chunk count to calculate delta for pipeline updates
            final int[] lastChunkCount = { 0 };

            // Stream pages directly into the pipeline's chunk queue
            ProcessingState result = largeDocumentPreprocessor.processLargeDocument(
                    sourceDescriptor,
                    pipeline.getChunkQueue(),
                    selectedChunker,
                    chunkingOptions,
                    progress -> {
                        // Update pipeline's chunk counter for accurate stats
                        // Calculate delta since last progress update
                        int currentChunks = progress.chunksCreated();
                        int delta = currentChunks - lastChunkCount[0];
                        if (delta > 0) {
                            pipeline.incrementChunksCreated(delta);
                            lastChunkCount[0] = currentChunks;
                        }

                        // Build stats with current chunk count for UI display
                        IngestStats loadingStats = IngestStats.builder()
                                .documentsLoaded(progress.currentPage())
                                .chunksCreated(progress.chunksCreated())
                                .chunksEmbedded(0)
                                .chunksIndexed(0)
                                .documentsIndexed(0)
                                .totalProcessingTimeMs(System.currentTimeMillis() - startTime)
                                .loaderUsed(statsBuilder.build().loaderUsed())
                                .chunkerUsed(statsBuilder.build().chunkerUsed())
                                .parallelProcessing(true)
                                .activeStage("LOADING")
                                .pipelineStatus("LOADING")
                                .build();

                        // Send UI progress update with actual chunk counts
                        sendThrottledProgress(IngestProgressUpdate.progress(
                                taskId, fileName, IngestPhase.LOADING,
                                10 + (int) (progress.progressPercent() * 0.2), // 10-30% for loading
                                String.format("Page %d/%d (%d chunks)",
                                        progress.currentPage(), progress.totalPages(), progress.chunksCreated()),
                                String.format("Loading: %d/%d pages processed, %d chunks created",
                                        progress.currentPage(), progress.totalPages(), progress.chunksCreated()),
                                loadingStats));
                    });

            // Signal that all chunks have been added
            pipeline.signalChunkingComplete();

            logger.info("[Task {}] Loading complete: {} pages -> {} chunks, signaling embedding phase",
                    taskId, result.lastProcessedPage(), result.chunksCreated());

            // Send phase transition - loading complete, now embedding
            IngestStats chunkingCompleteStats = IngestStats.builder()
                    .documentsLoaded(result.totalPages())
                    .chunksCreated(result.chunksCreated())
                    .chunksEmbedded(0)
                    .chunksIndexed(0)
                    .documentsIndexed(0)
                    .totalProcessingTimeMs(System.currentTimeMillis() - startTime)
                    .loaderUsed(statsBuilder.build().loaderUsed())
                    .chunkerUsed(statsBuilder.build().chunkerUsed())
                    .parallelProcessing(true)
                    .activeStage("EMBEDDING")
                    .pipelineStatus("PROCESSING")
                    .build();

            sendProgress(IngestProgressUpdate.progress(
                    taskId, fileName, IngestPhase.CHUNKING, 30,
                    "Chunking complete",
                    String.format("%d pages → %d chunks ready for embedding",
                            result.lastProcessedPage(), result.chunksCreated()),
                    chunkingCompleteStats));

            // Wait for embedding and indexing to complete
            PipelineResult pipelineResult = pipeline.awaitCompletion(handle);

            // Shutdown the progress reporter
            handle.shutdown();

            // Check if job was killed by memory watchdog during streaming
            if (memoryWatchdogService != null && memoryWatchdogService.isJobKilled(taskId)) {
                IngestConfiguration.MemoryInfo killMemInfo = ingestConfiguration.getMemoryInfo();
                logger.error("[Task {}] Job was killed by memory watchdog during large document streaming", taskId);
                throw new MemoryKilledException(
                        String.format("Job forcibly killed: memory %.1f%% exceeded kill threshold %d%%",
                                killMemInfo.usagePercent(), ingestConfiguration.getMemoryKillThresholdPercent()),
                        killMemInfo.usagePercent(),
                        ingestConfiguration.getMemoryKillThresholdPercent());
            }

            // Check if cancelled by user during streaming
            if (isTaskCancelled(taskId)) {
                logger.info("[Task {}] Task was cancelled during large document streaming", taskId);
                throw new TaskCancelledException("Task cancelled during large document streaming");
            }

            // Processing complete
            long totalTime = System.currentTimeMillis() - startTime;
            double chunksPerSec = totalTime > 0 ? (pipelineResult.chunksIndexed() * 1000.0 / totalTime) : 0;

            logger.info("[Task {}] Large document streaming complete: {} pages -> {} chunks in {}ms ({} chunks/sec)",
                    taskId, result.lastProcessedPage(), pipelineResult.chunksIndexed(),
                    totalTime, String.format("%.1f", chunksPerSec));

            // Log completion event
            logEvent(() -> ingestEventService.logCompleted(taskId, fileName,
                    pipelineResult.chunksIndexed(),
                    String.format("Streamed %d pages, created %d chunks in %dms",
                            result.lastProcessedPage(), pipelineResult.chunksIndexed(), totalTime)));

            // Send completion
            IngestStats finalStats = IngestStats.builder()
                    .documentsLoaded(result.totalPages())
                    .chunksCreated(pipelineResult.chunksCreated())
                    .chunksEmbedded(pipelineResult.chunksIndexed())
                    .chunksIndexed(pipelineResult.chunksIndexed())
                    .documentsIndexed(pipelineResult.chunksIndexed())
                    .totalProcessingTimeMs(totalTime)
                    .loaderUsed("streaming-" + selectedLoader.getName())
                    .chunkerUsed(actualChunkerUsed)
                    .processedDocumentIds(pipelineResult.processedDocumentIds())
                    .parallelProcessing(true)
                    .chunksPerSecond(chunksPerSec)
                    .memoryUsagePercent(ingestConfiguration.getMemoryUsagePercent())
                    .memoryStatus("OK")
                    .build();

            sendProgress(IngestProgressUpdate.completed(taskId, fileName, finalStats));

        } finally {
            activePipelines.remove(taskId);
        }
    }

    private DocumentLoader findLoader(Path filePath, String loaderName) {
        DocumentSourceDescriptor tempDescriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(filePath.toString())
                .originalFileName(filePath.getFileName().toString())
                .build();

        if (loaderName != null && !loaderName.isEmpty()) {
            return documentLoaders.stream()
                    .filter(loader -> loaderName.equals(loader.getName()))
                    .findFirst()
                    .orElse(null);
        }

        // Auto-detect
        return documentLoaders.stream()
                .filter(loader -> loader.supports(tempDescriptor))
                .findFirst()
                .orElse(null);
    }

    /**
     * Mapping of UI chunker strategy IDs to backend chunker names.
     * This handles the mismatch between frontend naming conventions and backend
     * getName() values.
     * Key = UI name (from CHUNKER_STRATEGIES in api-models.ts)
     * Value = Backend chunker getName() return value
     */
    private static final Map<String, String> CHUNKER_ALIASES = Map.ofEntries(
            // UI "spring_recursive_character" -> backend "recursive-character"
            // (RecursiveCharacterTextChunker)
            Map.entry("spring_recursive_character", "recursive-character"),
            Map.entry("custom_recursive_character", "recursive-character"),
            Map.entry("recursive-character", "recursive-character"),
            // UI "opennlp_sentence" -> backend "opennlp_sentence" (OpenNLPSentenceChunker)
            Map.entry("opennlp_sentence", "opennlp_sentence"),
            // UI "sentence" -> backend "sentence" (SentenceTextChunker)
            Map.entry("sentence", "sentence"),
            // Token chunker
            Map.entry("spring_token", "spring_token"),
            // Markdown chunkers
            Map.entry("custom_markdown", "custom_markdown"),
            Map.entry("spring_markdown", "spring_markdown"));

    private TextChunker findChunker(String chunkerName) {
        if (chunkerName != null && !chunkerName.isEmpty()) {
            // Try exact match first
            TextChunker specified = textChunkers.stream()
                    .filter(chunker -> chunkerName.equals(chunker.getName()))
                    .findFirst()
                    .orElse(null);
            if (specified != null && !isNoOpChunker(specified)) {
                logger.info("Found chunker by exact match: {}", specified.getName());
                return specified;
            }

            // Try alias mapping (UI name -> backend name)
            String mappedName = CHUNKER_ALIASES.get(chunkerName);
            if (mappedName != null) {
                TextChunker aliasMatch = textChunkers.stream()
                        .filter(chunker -> mappedName.equals(chunker.getName()))
                        .findFirst()
                        .orElse(null);
                if (aliasMatch != null && !isNoOpChunker(aliasMatch)) {
                    logger.info("Resolved chunker: UI name '{}' -> backend name '{}' ({})",
                            chunkerName, mappedName, aliasMatch.getClass().getSimpleName());
                    return aliasMatch;
                }
            }

            // Try partial/contains match as fallback
            String normalizedRequest = chunkerName.toLowerCase().replace("_", "-").replace("spring-", "");
            TextChunker partialMatch = textChunkers.stream()
                    .filter(chunker -> {
                        String name = chunker.getName().toLowerCase();
                        return name.contains(normalizedRequest) || normalizedRequest.contains(name);
                    })
                    .filter(chunker -> !isNoOpChunker(chunker))
                    .findFirst()
                    .orElse(null);
            if (partialMatch != null) {
                logger.info("Found chunker via partial match: requested='{}', found='{}'",
                        chunkerName, partialMatch.getName());
                return partialMatch;
            }

            logger.warn("Specified chunker '{}' not found. Available: {}. Will auto-select.",
                    chunkerName, textChunkers.stream().map(TextChunker::getName).collect(Collectors.joining(", ")));
        }

        // Auto-select best chunker
        return selectBestChunker();
    }

    private TextChunker selectBestChunker() {
        List<TextChunker> realChunkers = textChunkers.stream()
                .filter(c -> !isNoOpChunker(c))
                .collect(Collectors.toList());

        if (realChunkers.isEmpty()) {
            return null;
        }

        // Prioritize by type/name preference
        List<String> preferredPatterns = Arrays.asList(
                "opennlp", "recursive", "character", "sentence", "markdown", "token");

        for (String pattern : preferredPatterns) {
            Optional<TextChunker> preferred = realChunkers.stream()
                    .filter(chunker -> chunker.getName().toLowerCase().contains(pattern) ||
                            chunker.getClass().getSimpleName().toLowerCase().contains(pattern))
                    .findFirst();
            if (preferred.isPresent()) {
                return preferred.get();
            }
        }

        return realChunkers.get(0);
    }

    private boolean isNoOpChunker(TextChunker chunker) {
        if (chunker == null)
            return true;

        String className = chunker.getClass().getSimpleName().toLowerCase();
        String chunkerName = chunker.getName().toLowerCase();

        return className.contains("noop") || className.contains("dummy") ||
                className.contains("mock") || className.contains("stub") ||
                chunkerName.contains("noop") || chunkerName.contains("no-op") ||
                chunkerName.contains("dummy") || chunkerName.equals("none");
    }

    /**
     * Helper to log events safely (handles null ingestEventService).
     */
    private void logEvent(Runnable eventLogger) {
        if (ingestEventService == null) {
            logger.trace("logEvent skipped - ingestEventService is null");
            return;
        }
        if (!ingestEventService.isEnabled()) {
            logger.trace("logEvent skipped - ingestEventService is disabled");
            return;
        }
        try {
            eventLogger.run();
        } catch (Exception e) {
            logger.warn("Failed to log ingest event: {}", e.getMessage(), e);
        }
    }

    /**
     * Helper to safely execute job history operations.
     * Handles null service and exceptions gracefully.
     */
    private void updateJobHistory(Runnable historyUpdater) {
        if (indexingJobHistoryService != null) {
            try {
                historyUpdater.run();
            } catch (Exception e) {
                logger.warn("Failed to update job history: {}", e.getMessage());
            }
        }
    }

    /**
     * Determines the failure reason category based on exception type and phase.
     */
    private IndexingJobHistory.FailureReason determineFailureReason(Exception e, IngestEvent.IngestPhase phase) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String exceptionType = e.getClass().getSimpleName().toLowerCase();

        // Check for out of memory (note: OutOfMemoryError is an Error, not Exception,
        // so we detect via message or wrapped cause)
        if (message.contains("out of memory") || message.contains("heap space") ||
                message.contains("gc overhead") || exceptionType.contains("outofmemory") ||
                (e.getCause() != null && e.getCause() instanceof OutOfMemoryError)) {
            return IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
        }

        // Check for IO errors
        if (e instanceof java.io.IOException || message.contains("io error") ||
                message.contains("connection") || message.contains("network")) {
            return IndexingJobHistory.FailureReason.IO_ERROR;
        }

        // Check for timeout
        if (e instanceof java.util.concurrent.TimeoutException ||
                message.contains("timeout") || message.contains("timed out")) {
            return IndexingJobHistory.FailureReason.TIMEOUT;
        }

        // Phase-specific errors
        return switch (phase) {
            case LOADING -> IndexingJobHistory.FailureReason.LOAD_ERROR;
            case CONVERTING -> IndexingJobHistory.FailureReason.CONVERSION_ERROR;
            case CHUNKING -> IndexingJobHistory.FailureReason.CHUNKING_ERROR;
            case EMBEDDING -> IndexingJobHistory.FailureReason.EMBEDDING_ERROR;
            case INDEXING -> IndexingJobHistory.FailureReason.INDEXING_ERROR;
            default -> IndexingJobHistory.FailureReason.UNKNOWN;
        };
    }

    /**
     * Returns diagnostic information about all active pipelines.
     * Useful for identifying bottlenecks in the ingestion process.
     */
    public Map<String, Object> getActivePipelineDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("activePipelineCount", activePipelines.size());

        List<Map<String, Object>> pipelines = new ArrayList<>();
        for (Map.Entry<String, ParallelIngestPipeline> entry : activePipelines.entrySet()) {
            String taskId = entry.getKey();
            ParallelIngestPipeline pipeline = entry.getValue();

            Map<String, Object> pipelineInfo = new LinkedHashMap<>();
            pipelineInfo.put("taskId", taskId);
            pipelineInfo.put("cancelled", pipeline.isCancelled());
            pipelineInfo.put("hasError", pipeline.hasError());
            pipelineInfo.put("lastError", pipeline.getLastError());

            // Queue status
            Map<String, Object> queues = new LinkedHashMap<>();
            queues.put("chunkQueueSize", pipeline.getChunkQueueSize());
            queues.put("embeddedQueueSize", pipeline.getEmbeddedQueueSize());
            pipelineInfo.put("queues", queues);

            // Bottleneck analysis
            String bottleneck = analyzeBottleneck(pipeline);
            pipelineInfo.put("likelyBottleneck", bottleneck);

            pipelines.add(pipelineInfo);
        }
        diagnostics.put("pipelines", pipelines);

        return diagnostics;
    }

    /**
     * Analyzes pipeline queues to identify the likely bottleneck.
     */
    private String analyzeBottleneck(ParallelIngestPipeline pipeline) {
        int chunkQueueSize = pipeline.getChunkQueueSize();
        int embeddedQueueSize = pipeline.getEmbeddedQueueSize();

        // If chunk queue is full and embedded queue is empty, embedding is the
        // bottleneck
        if (chunkQueueSize > 150 && embeddedQueueSize < 20) {
            return "EMBEDDING - chunk queue backing up, embedding workers can't keep up";
        }

        // If embedded queue is full, indexing is the bottleneck
        if (embeddedQueueSize > 150) {
            return "INDEXING - embedded queue backing up, indexing can't keep up";
        }

        // If both queues are low, chunking may be the bottleneck or system is balanced
        if (chunkQueueSize < 20 && embeddedQueueSize < 20) {
            return "CHUNKING or BALANCED - queues are low, either chunking is slow or system is well balanced";
        }

        return "UNKNOWN - queues are in intermediate state";
    }

    /**
     * Returns the map of active pipelines for external access.
     */
    public Map<String, ParallelIngestPipeline> getActivePipelines() {
        return Collections.unmodifiableMap(activePipelines);
    }

}
