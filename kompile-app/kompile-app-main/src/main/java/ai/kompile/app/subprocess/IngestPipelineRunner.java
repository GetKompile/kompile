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

package ai.kompile.app.subprocess;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.pipeline.PipelineProgress;
import ai.kompile.app.services.pipeline.PipelineResult;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the document ingest pipeline within the subprocess.
 *
 * This class orchestrates the full pipeline:
 * 1. LOADING - Load documents from file using DocumentLoaders
 * 2. CHUNKING - Split documents into chunks using TextChunkers
 * 3. EMBEDDING - Generate embeddings using EmbeddingModel
 * 4. INDEXING - Index documents using IndexerService
 *
 * Progress is reported via SubprocessProgressReporter (STDOUT JSON)
 * and HttpIngestCallback (HTTP callbacks for persistence).
 */
public class IngestPipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IngestPipelineRunner.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final List<DocumentLoader> documentLoaders;
    private final List<TextChunker> textChunkers;
    private final EmbeddingModel embeddingModel;
    private final IndexerService indexerService;
    private final VectorStore vectorStore;

    public IngestPipelineRunner(
            List<DocumentLoader> documentLoaders,
            List<TextChunker> textChunkers,
            EmbeddingModel embeddingModel,
            IndexerService indexerService,
            VectorStore vectorStore) {
        this.documentLoaders = documentLoaders != null ? documentLoaders : List.of();
        this.textChunkers = textChunkers != null ? textChunkers : List.of();
        this.embeddingModel = embeddingModel;
        this.indexerService = indexerService;
        this.vectorStore = vectorStore;
    }

    /**
     * Execute the full ingest pipeline.
     *
     * @param args         Pipeline arguments
     * @param reporter     Progress reporter for STDOUT
     * @param httpCallback HTTP callback for persistence
     * @return Pipeline result with statistics
     * @throws PipelineException if pipeline fails
     */
    public PipelineResult execute(SubprocessArgs args, SubprocessProgressReporter reporter,
            HttpIngestCallback httpCallback) throws PipelineException {

        String taskId = args.taskId();
        Path filePath = Paths.get(args.filePath());

        final Nd4jEnvironmentConfig nd4jEnvironmentInvoked = parseNd4jEnvironmentConfig(args.nd4jConfigJson());
        final Nd4jEnvironmentConfig nd4jEnvironmentUsed = captureNd4jEnvironmentConfig();

        long loadingStart = 0, loadingDuration = 0;
        long chunkingStart = 0, chunkingDuration = 0;
        long embeddingStart = 0, embeddingDuration = 0;
        long indexingStart = 0, indexingDuration = 0;

        int documentsLoaded = 0;
        int chunksCreated = 0;
        int chunksEmbedded = 0;
        int documentsIndexed = 0;

        try {
            // ========== PHASE 1: LOADING ==========
            String phase = "LOADING";
            reporter.reportPhaseStart(phase);
            httpCallback.logPhaseStarted(taskId, args.filePath(), phase, null);
            loadingStart = System.currentTimeMillis();

            logger.info("Loading documents from: {}", filePath);
            reporter.reportLog("INFO", "pipeline", "Starting document loading from: " + filePath.getFileName());
            reporter.reportProgressImmediate(phase, 5, "Finding loader", "Selecting document loader...");

            // Create document source descriptor
            DocumentSourceDescriptor sourceDescriptor = DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(args.filePath())
                    .originalFileName(filePath.getFileName().toString())
                    .sourceId(args.taskId())
                    .metadata(new HashMap<>())
                    .build();

            // Find appropriate loader
            DocumentLoader loader = findLoader(args.loaderName(), sourceDescriptor);
            if (loader == null) {
                throw new PipelineException(phase, "No suitable document loader found for file: " + filePath);
            }

            logger.info("Using loader: {}", loader.getClass().getSimpleName());
            reporter.reportLog("INFO", "loader", "Selected loader: " + loader.getClass().getSimpleName());
            reporter.reportProgressImmediate(phase, 10, "Loading documents",
                    "Using " + loader.getClass().getSimpleName());

            // Load documents
            List<Document> documents = loader.load(sourceDescriptor);
            documentsLoaded = documents.size();
            loadingDuration = System.currentTimeMillis() - loadingStart;
            reporter.reportLog("INFO", "loader", "Loaded " + documentsLoaded + " documents in " + loadingDuration + "ms");

            reporter.reportPhaseTransition(phase, "CHUNKING", loadingDuration);
            httpCallback.logPhaseCompleted(taskId, args.filePath(), phase, documentsLoaded,
                    "Loaded " + documentsLoaded + " documents");

            if (documents.isEmpty()) {
                logger.warn("No documents loaded from file");
                return new PipelineResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
            }

            // ========== PARALLEL PIPELINE ==========
            // Configure chunking options
            Map<String, Object> chunkingOptions = new HashMap<>();
            chunkingOptions.put("chunkSize", args.chunkSize());
            chunkingOptions.put("overlap", args.chunkOverlap());
            // Disable garbage collection - the SentenceFilter marks chunks not ending
            // with . ! ? as garbage, which is too aggressive for most content
            chunkingOptions.put("collectGarbage", false);

            // Find chunker
            TextChunker chunker = findChunker(args.chunkerName());
            if (chunker == null) {
                throw new PipelineException("CHUNKING", "No suitable text chunker found");
            }
            reporter.reportLog("INFO", "chunker", "Using chunker: " + chunker.getClass().getSimpleName());

            // Initialize Checkpoint Service if path provided
            IngestCheckpointService checkpointService = null;
            if (args.checkpointPath() != null && !args.checkpointPath().isBlank()) {
                try {
                    checkpointService = new IngestCheckpointService(Paths.get(args.checkpointPath()), taskId);
                    checkpointService.init();
                    // Save args for context if fresh start
                    if (!args.resume()) {
                        checkpointService.saveArgs(args);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to initialize checkpoint service: {}", e.getMessage());
                }
            }

            // Initialize Parallel Pipeline
            try (ParallelIngestPipeline pipeline = new ParallelIngestPipeline(
                    chunker,
                    embeddingModel,
                    indexerService,
                    chunkingOptions,
                    args.embeddingBatchSize(),
                    true // parallel indexing enabled
            )) {
                // Configure checkpointing
                if (checkpointService != null) {
                    pipeline.setCheckpointService(checkpointService);
                    if (args.resume()) {
                        IngestCheckpointService.CheckpointState state = checkpointService.loadState();
                        pipeline.resumeFrom(state);

                        // Skip loading documents if resume state suggests we are past chunking
                        // However, simplest resume strategy for now is to reload docs and let the
                        // pipeline filter
                        // based on checkointed chunks.
                        // But if we want to skip loading huge files...
                        // For now, allow reload.
                    }
                }

                // Determine loading phase metrics for reporting
                final int finalDocumentsLoaded = documentsLoaded;
                final long finalLoadingStart = loadingStart;
                final long finalLoadingDuration = loadingDuration;
                final String finalFilePath = args.filePath();

                // Set up progress reporting callback
                pipeline.setProgressCallback(progress -> {
                    // Update task-level metrics
                    String currentPhase = progress.phase().toUpperCase();

                    // Collect runtime info periodically
                    // Use a simple counter or time check if needed, or just collect every time
                    // (might be heavy)
                    // For now, let's collect it every update since update interval is controlled by
                    // ParallelPipeline (100ms)
                    // But maybe throttle slightly if needed.
                    // Actually ParallelPipeline throttles updates to 100ms.
                    SubprocessMessage.RuntimeInfo runtimeInfo = SubprocessMessage.RuntimeInfo.collect(
                            null, null, null, List.of(finalFilePath),
                            nd4jEnvironmentInvoked, nd4jEnvironmentUsed);

                    // Convert PipelineProgress -> SubprocessMessage.ProgressStats
                    SubprocessMessage.ProgressStats stats = convertToStats(
                            progress,
                            finalDocumentsLoaded,
                            finalLoadingStart,
                            finalLoadingDuration,
                            runtimeInfo);

                    // Forward to STDOUT reporter
                    reporter.reportProgressImmediate(
                            currentPhase,
                            progress.percent(),
                            progress.message(), // step
                            progress.message(), // message
                            stats);

                    // Forward to HTTP callback for persistence
                    // (Log every 10% or on phase change to avoid spamming HTTP)
                    // Or just use the logProgress which is non-blocking async usually?
                    // HttpIngestCallback uses HttpClient which is synchronous in our impl.
                    // We should be careful. ParallelPipeline runs this on progress thread.
                    // So it won't block embedding, but might block progress reporting.
                });

                // Execute Pipeline
                reporter.reportLog("INFO", "pipeline", "Starting parallel ingest pipeline with " + documents.size() + " documents");
                ai.kompile.app.services.pipeline.PipelineResult pipelineResult = pipeline.process(documents);
                reporter.reportLog("INFO", "pipeline", "Pipeline complete: " + pipelineResult.chunksCreated() + " chunks created, " + pipelineResult.chunksIndexed() + " indexed");

                // Convert to inner PipelineResult for compatibility with IngestSubprocessMain
                return new IngestPipelineRunner.PipelineResult(
                        finalDocumentsLoaded,
                        pipelineResult.chunksCreated(),
                        pipelineResult.chunksIndexed(), // Approximation: assume all indexed were embedded
                        pipelineResult.chunksIndexed(), // "documentsIndexed" actually tracks chunks
                        pipelineResult.tokensProcessed(),
                        finalLoadingDuration,
                        0, // Chunking duration not exposed by ParallelPipeline yet
                        0, // Embedding duration not exposed by ParallelPipeline yet
                        0 // Indexing duration not exposed by ParallelPipeline yet
                );
            }
        } catch (PipelineException e) {
            reporter.reportLog("ERROR", "pipeline", "Pipeline failed in phase " + e.getPhase() + ": " + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Pipeline execution failed", e);
            reporter.reportLog("ERROR", "pipeline", "Pipeline execution failed: " + e.getMessage());
            throw new PipelineException("EXECUTION", e.getMessage(), e);
        }
    }

    /**
     * Find a document loader by name or auto-detect based on source descriptor.
     */
    private DocumentLoader findLoader(String loaderName, DocumentSourceDescriptor sourceDescriptor) {
        if (loaderName != null && !loaderName.isBlank()) {
            // Try to find by name
            for (DocumentLoader loader : documentLoaders) {
                String simpleName = loader.getClass().getSimpleName().toLowerCase();
                if (simpleName.contains(loaderName.toLowerCase())) {
                    return loader;
                }
            }
        }

        // Auto-detect based on source descriptor
        for (DocumentLoader loader : documentLoaders) {
            if (loader.supports(sourceDescriptor)) {
                return loader;
            }
        }

        // Fall back to first available loader
        if (!documentLoaders.isEmpty()) {
            return documentLoaders.get(0);
        }

        return null;
    }

    /**
     * Find a text chunker by name or return default.
     */
    private TextChunker findChunker(String chunkerName) {
        if (chunkerName != null && !chunkerName.isBlank()) {
            // Try to find by name
            for (TextChunker chunker : textChunkers) {
                String simpleName = chunker.getClass().getSimpleName().toLowerCase();
                if (simpleName.contains(chunkerName.toLowerCase())) {
                    return chunker;
                }
            }
        }

        // Return first available chunker
        if (!textChunkers.isEmpty()) {
            return textChunkers.get(0);
        }

        return null;
    }

    // Get text content from Spring AI Document
    private SubprocessMessage.ProgressStats convertToStats(
            PipelineProgress progress,
            int documentsLoaded,
            long loadingStart,
            long loadingDuration,
            SubprocessMessage.RuntimeInfo runtimeInfo) {

        long totalTime = System.currentTimeMillis() - loadingStart;

        // Convert workers
        List<SubprocessMessage.WorkerStatusSnapshot> workers = new ArrayList<>();
        if (progress.workerStatuses() != null) {
            for (PipelineProgress.WorkerStatus w : progress.workerStatuses()) {
                workers.add(new SubprocessMessage.WorkerStatusSnapshot(
                        w.workerId(), w.workerType(), w.status(),
                        w.itemsProcessed(), w.currentBatchSize(),
                        w.throughput(), w.currentItem()));
            }
        }

        // Extract tensor shape and timing info from embedding batch metrics
        Integer currentBatchNumber = null;
        Integer totalBatches = null;
        Integer inputTexts = null;
        Integer maxSequenceLength = null;
        Integer embeddingDimension = null;
        String actualInputShape = null;
        String actualOutputShape = null;
        String currentStep = null;
        Long tokenizationTimeMs = null;
        Long paddingTimeMs = null;
        Long tensorCreationTimeMs = null;
        Long forwardPassTimeMs = null;
        Long extractionTimeMs = null;
        int[] passageTokenCounts = null;

        PipelineProgress.EmbeddingBatchMetrics bm = progress.currentEmbeddingBatch();
        if (bm != null) {
            currentBatchNumber = bm.batchNumber() > 0 ? bm.batchNumber() : null;
            totalBatches = bm.totalBatches() > 0 ? bm.totalBatches() : null;
            inputTexts = bm.inputTexts() > 0 ? bm.inputTexts() : null;
            maxSequenceLength = bm.maxSequenceLength() > 0 ? bm.maxSequenceLength() : null;
            embeddingDimension = bm.embeddingDimension() > 0 ? bm.embeddingDimension() : null;
            actualInputShape = bm.actualInputShape();
            actualOutputShape = bm.actualOutputShape();
            currentStep = bm.currentStep();
            tokenizationTimeMs = bm.tokenizationTimeMs() > 0 ? bm.tokenizationTimeMs() : null;
            paddingTimeMs = bm.paddingTimeMs() > 0 ? bm.paddingTimeMs() : null;
            tensorCreationTimeMs = bm.tensorCreationTimeMs() > 0 ? bm.tensorCreationTimeMs() : null;
            forwardPassTimeMs = bm.forwardPassTimeMs() > 0 ? bm.forwardPassTimeMs() : null;
            extractionTimeMs = bm.extractionTimeMs() > 0 ? bm.extractionTimeMs() : null;
            passageTokenCounts = bm.passageTokenCounts();
        }

        // Convert batch history from PipelineProgress.EmbeddingBatchMetrics to SubprocessMessage.BatchHistoryEntry
        java.util.List<SubprocessMessage.BatchHistoryEntry> batchHistoryEntries = null;
        if (progress.batchHistory() != null && !progress.batchHistory().isEmpty()) {
            batchHistoryEntries = progress.batchHistory().stream()
                    .map(h -> new SubprocessMessage.BatchHistoryEntry(
                            h.batchNumber(),
                            h.inputTexts(),
                            h.maxSequenceLength(),
                            h.embeddingDimension(),
                            h.actualInputShape(),
                            h.actualOutputShape(),
                            h.totalBatchTimeMs(),
                            h.currentStep(),
                            h.tokensPerSecond(),
                            h.passageTokenCounts()
                    ))
                    .toList();
        }

        return new SubprocessMessage.ProgressStats(
                documentsLoaded,
                progress.chunksCreated(),
                progress.chunksEmbedded(),
                progress.chunksIndexed(),
                progress.tokensProcessed(),  // tokensProcessed
                0L,  // totalTokensInIndex - not tracked during progress
                totalTime,
                loadingDuration,
                0L, // dynamic duration tracking not explicitly passed in PipelineProgress yet
                0L,
                0L,
                null, null,
                0, // active batch size
                progress.chunkingWorkers() + progress.embeddingWorkers(),
                true,
                progress.chunksPerSecond(),
                0.0,
                progress.memoryUsagePercent(),
                "OK",
                progress.phase().toUpperCase(),
                "PROCESSING",
                progress.queueStatus() != null ? progress.queueStatus().chunkQueueSize() : 0,
                progress.queueStatus() != null ? progress.queueStatus().embeddedQueueSize() : 0,
                workers,
                runtimeInfo,
                // ========== Embedding batch metrics with tensor shapes ==========
                currentBatchNumber,               // currentBatchNumber
                totalBatches,                     // totalBatches
                inputTexts,                       // inputTexts
                maxSequenceLength,                // maxSequenceLength
                embeddingDimension,               // embeddingDimension
                actualInputShape,                 // actualInputShape
                actualOutputShape,                // actualOutputShape
                currentStep,                      // currentStep
                tokenizationTimeMs,               // tokenizationTimeMs
                paddingTimeMs,                    // paddingTimeMs
                tensorCreationTimeMs,             // tensorCreationTimeMs
                forwardPassTimeMs,                // forwardPassTimeMs
                extractionTimeMs,                 // extractionTimeMs
                batchHistoryEntries,              // batchHistory
                passageTokenCounts,               // passageTokenCounts
                // ========== Resume/Restart info ==========
                null,                             // resumedFromChunkCount (not applicable for ingest)
                null,                             // resumedFromEmbeddedCount
                null,                             // resumedFromIndexedCount
                null                              // isResumedRun
        );
    }

    private static Nd4jEnvironmentConfig parseNd4jEnvironmentConfig(String nd4jConfigJson) {
        if (nd4jConfigJson == null || nd4jConfigJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
        } catch (Exception e) {
            logger.debug("Failed to parse ND4J config JSON from args: {}", e.getMessage());
            return null;
        }
    }

    private static Nd4jEnvironmentConfig captureNd4jEnvironmentConfig() {
        try {
            var env = Nd4j.getEnvironment();
            boolean isCuda = !env.isCPU();
            return new Nd4jEnvironmentConfig(
                    // Thread configuration
                    (int) env.maxThreads(),
                    (int) env.maxMasterThreads(),
                    // Debug/verbose modes
                    env.isDebug(),
                    env.isVerbose(),
                    env.isProfiling(),
                    // Core settings
                    env.isEnableBlas(),
                    env.helpersAllowed(),
                    env.isDetectingLeaks(),
                    // Performance thresholds
                    env.tadThreshold(),
                    env.elementwiseThreshold(),
                    // Memory limits (read as 0 if not explicitly set)
                    0L, // maxPrimaryMemory - no getter available
                    0L, // maxSpecialMemory - no getter available
                    0L, // maxDeviceMemory - no getter available
                    // Lifecycle tracking
                    env.isLifecycleTracking(),
                    env.isTrackViews(),
                    env.isTrackDeletions(),
                    env.isSnapshotFiles(),
                    env.isTrackOperations(),
                    (int) env.getStackDepth(),
                    (int) env.getReportInterval(),
                    (int) env.getMaxDeletionHistory(),
                    // Individual tracker toggles
                    env.isNDArrayTracking(),
                    env.isDataBufferTracking(),
                    env.isTADCacheTracking(),
                    env.isShapeCacheTracking(),
                    env.isOpContextTracking(),
                    // Advanced debugging - function tracing
                    env.isFuncTracePrintAllocate(),
                    env.isFuncTracePrintDeallocate(),
                    env.isFuncTracePrintJavaOnly(),
                    // Advanced debugging - other
                    env.isLogNativeNDArrayCreation(),
                    env.isLogNDArrayEvents(),
                    env.isTruncateNDArrayLogStrings(),
                    env.numWorkspaceEventsToKeep(),
                    env.isCheckInputChange(),
                    env.isCheckOutputChange(),
                    env.isTrackWorkspaceOpenClose(),
                    env.isDeleteShapeInfo(),
                    env.isDeletePrimary(),
                    env.isDeleteSpecial(),
                    env.isVariableTracingEnabled(),
                    // JavaCPP settings
                    "true".equals(System.getProperty("org.bytedeco.javacpp.logger.debug")),
                    "true".equals(System.getProperty("org.bytedeco.javacpp.pathsFirst")),
                    // BLAS configuration - use defaults since ND4J Environment doesn't expose getters
                    true,  // blasSerializationEnabled
                    1,     // openBlasThreads
                    getOmpNumThreads(),  // ompNumThreads
                    // CUDA settings - only populated if running on CUDA backend
                    isCuda ? env.cudaCurrentDevice() : null,
                    isCuda ? env.cudaMemoryPinned() : null,
                    isCuda ? env.cudaUseManagedMemory() : null,
                    isCuda ? env.cudaMemoryPoolSize() : null,
                    isCuda ? env.cudaForceP2P() : null,
                    isCuda ? env.cudaAllocatorEnabled() : null,
                    isCuda ? env.cudaMaxBlocks() : null,
                    isCuda ? env.cudaMaxThreadsPerBlock() : null,
                    isCuda ? env.cudaAsyncExecution() : null,
                    isCuda ? env.cudaStreamLimit() : null,
                    isCuda ? env.cudaUseDeviceHost() : null,
                    isCuda ? env.cudaEventLimit() : null,
                    isCuda ? env.cudaCachingAllocatorLimit() : null,
                    isCuda ? env.cudaUseUnifiedMemory() : null,
                    isCuda ? env.cudaPrefetchSize() : null,
                    isCuda ? env.cudaGraphOptimization() : null,
                    isCuda ? env.cudaTensorCoreEnabled() : null,
                    isCuda ? env.cudaBlockingSync() : null,
                    isCuda ? env.cudaDeviceSchedule() : null,
                    isCuda ? env.cudaStackSize() : null,
                    isCuda ? env.cudaMallocHeapSize() : null,
                    isCuda ? env.cudaPrintfFifoSize() : null,
                    isCuda ? env.cudaDevRuntimeSyncDepth() : null,
                    isCuda ? env.cudaDevRuntimePendingLaunchCount() : null,
                    isCuda ? env.cudaMaxL2FetchGranularity() : null,
                    isCuda ? env.cudaPersistingL2CacheSize() : null,
                    // Triton settings
                    isCuda ? env.tritonBuildThreads() : null,
                    isCuda ? env.tritonCacheEnabled() : null,
                    isCuda ? env.tritonVerbose() : null,
                    isCuda ? env.tritonAlwaysCompile() : null,
                    isCuda ? env.tritonNumWarps() : null,
                    isCuda ? env.tritonNumStages() : null,
                    isCuda ? env.tritonNumCTAs() : null,
                    isCuda ? env.tritonEnableFpFusion() : null,
                    isCuda ? env.tritonCacheDir() : null,
                    isCuda ? env.tritonDumpDir() : null,
                    isCuda ? env.tritonOverrideArch() : null
            );
        } catch (Exception e) {
            logger.debug("Failed to capture ND4J environment config in subprocess: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get current OpenMP thread count from NativeOps.
     */
    private static int getOmpNumThreads() {
        try {
            return NativeOpsHolder.getInstance().getDeviceNativeOps().ompGetNumThreads();
        } catch (Exception e) {
            logger.debug("Failed to get OMP num threads: {}", e.getMessage());
            return 4; // default
        }
    }

    /**
     * Result of pipeline execution.
     */
    public record PipelineResult(
            int documentsLoaded,
            int chunksCreated,
            int chunksEmbedded,
            int documentsIndexed,
            long tokensProcessed,
            long loadingDurationMs,
            long chunkingDurationMs,
            long embeddingDurationMs,
            long indexingDurationMs) {
        public long totalDurationMs() {
            return loadingDurationMs + chunkingDurationMs + embeddingDurationMs + indexingDurationMs;
        }
    }

    /**
     * Exception thrown when pipeline fails.
     */
    public static class PipelineException extends Exception {
        private final String phase;

        public PipelineException(String phase, String message) {
            super(message);
            this.phase = phase;
        }

        public PipelineException(String phase, String message, Throwable cause) {
            super(message, cause);
            this.phase = phase;
        }

        public String getPhase() {
            return phase;
        }
    }
}
