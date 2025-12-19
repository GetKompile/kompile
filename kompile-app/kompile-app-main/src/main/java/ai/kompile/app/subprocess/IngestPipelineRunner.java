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
                return new PipelineResult(0, 0, 0, 0, 0, 0, 0, 0);
            }

            // ========== PARALLEL PIPELINE ==========
            // Configure chunking options
            Map<String, Object> chunkingOptions = new HashMap<>();
            chunkingOptions.put("chunkSize", args.chunkSize());
            chunkingOptions.put("overlap", args.chunkOverlap());

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

        return new SubprocessMessage.ProgressStats(
                documentsLoaded,
                progress.chunksCreated(),
                progress.chunksEmbedded(),
                progress.chunksIndexed(),
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
                runtimeInfo);
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
            return new Nd4jEnvironmentConfig(
                    // Thread configuration
                    (int) Nd4j.getEnvironment().maxThreads(),
                    (int) Nd4j.getEnvironment().maxMasterThreads(),
                    // Debug/verbose modes
                    Nd4j.getEnvironment().isDebug(),
                    Nd4j.getEnvironment().isVerbose(),
                    Nd4j.getEnvironment().isProfiling(),
                    // Core settings
                    Nd4j.getEnvironment().isEnableBlas(),
                    Nd4j.getEnvironment().helpersAllowed(),
                    Nd4j.getEnvironment().isDetectingLeaks(),
                    // Performance thresholds
                    Nd4j.getEnvironment().tadThreshold(),
                    Nd4j.getEnvironment().elementwiseThreshold(),
                    // Memory limits (read as 0 if not explicitly set)
                    0L, // maxPrimaryMemory - no getter available
                    0L, // maxSpecialMemory - no getter available
                    0L, // maxDeviceMemory - no getter available
                    // Lifecycle tracking
                    Nd4j.getEnvironment().isLifecycleTracking(),
                    Nd4j.getEnvironment().isTrackViews(),
                    Nd4j.getEnvironment().isTrackDeletions(),
                    Nd4j.getEnvironment().isSnapshotFiles(),
                    Nd4j.getEnvironment().isTrackOperations(),
                    (int) Nd4j.getEnvironment().getStackDepth(),
                    (int) Nd4j.getEnvironment().getReportInterval(),
                    (int) Nd4j.getEnvironment().getMaxDeletionHistory(),
                    // Individual tracker toggles
                    Nd4j.getEnvironment().isNDArrayTracking(),
                    Nd4j.getEnvironment().isDataBufferTracking(),
                    Nd4j.getEnvironment().isTADCacheTracking(),
                    Nd4j.getEnvironment().isShapeCacheTracking(),
                    Nd4j.getEnvironment().isOpContextTracking(),
                    // Advanced debugging - function tracing
                    Nd4j.getEnvironment().isFuncTracePrintAllocate(),
                    Nd4j.getEnvironment().isFuncTracePrintDeallocate(),
                    Nd4j.getEnvironment().isFuncTracePrintJavaOnly(),
                    // Advanced debugging - other
                    Nd4j.getEnvironment().isLogNativeNDArrayCreation(),
                    Nd4j.getEnvironment().isLogNDArrayEvents(),
                    Nd4j.getEnvironment().isCheckInputChange(),
                    Nd4j.getEnvironment().isCheckOutputChange(),
                    Nd4j.getEnvironment().isTrackWorkspaceOpenClose(),
                    Nd4j.getEnvironment().isDeleteShapeInfo(),
                    Nd4j.getEnvironment().isDeletePrimary(),
                    Nd4j.getEnvironment().isDeleteSpecial(),
                    Nd4j.getEnvironment().isVariableTracingEnabled(),
                    // JavaCPP settings
                    "true".equals(System.getProperty("org.bytedeco.javacpp.logger.debug")),
                    "true".equals(System.getProperty("org.bytedeco.javacpp.pathsFirst")),
                    // BLAS configuration - use defaults since ND4J Environment doesn't expose getters
                    true,  // blasSerializationEnabled
                    1      // openBlasThreads
            );
        } catch (Exception e) {
            logger.debug("Failed to capture ND4J environment config in subprocess: {}", e.getMessage());
            return null;
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
