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
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.pipeline.PipelineProgress;
import ai.kompile.app.services.pipeline.PipelineResult;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import ai.kompile.vectorstore.anserini.util.NativeCompatibleDirectoryFactory;
import org.apache.lucene.store.FSDirectory;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Main entry point for the vector population subprocess.
 *
 * This is a lightweight standalone application that:
 * 1. Initializes ND4J environment (same as MainApplication)
 * 2. Creates a minimal Spring ApplicationContext
 * 3. Reads documents from existing Lucene keyword index
 * 4. Embeds and indexes them to vector store
 * 5. Reports progress via STDOUT JSON and HTTP callbacks
 *
 * Usage:
 * java -cp <classpath> ai.kompile.app.subprocess.VectorPopulationSubprocessMain <args-file.json>
 */
public class VectorPopulationSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Redirect System.out to System.err for logging, keep protocol messages on original stdout
    private static PrintStream originalStdout;

    // Static holder for current args - allows embedding model to access during bean creation
    private static volatile VectorPopulationSubprocessArgs currentArgs;

    // Static holder for memory watchdog - allows pipeline to check memory limits
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    private static volatile Nd4jEnvironmentConfig nd4jEnvironmentInvoked;
    private static volatile Nd4jEnvironmentConfig nd4jEnvironmentUsed;
    private static volatile String embeddingModelId;
    private static volatile Integer embeddingModelDimension;

    /**
     * Get the current subprocess args.
     * This is used by embedding model during bean creation to access model source configuration.
     */
    public static VectorPopulationSubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    /**
     * Get the memory watchdog for this subprocess.
     * This allows the pipeline to check memory limits and decide whether to stop/kill.
     */
    public static SubprocessMemoryWatchdog getMemoryWatchdog() {
        return memoryWatchdog;
    }

    public static void main(String[] args) {
        // Capture original stdout for protocol messages
        originalStdout = System.out;

        // Redirect System.out to stderr so logging doesn't interfere with protocol
        System.setOut(System.err);

        int exitCode = 0;
        VectorPopulationSubprocessArgs subprocessArgs = null;
        SubprocessProgressReporter reporter = null;
        HttpIngestCallback httpCallback = null;

        try {
            // Parse arguments
            if (args.length < 1) {
                System.err.println("Usage: VectorPopulationSubprocessMain <args-file.json>");
                System.exit(1);
            }

            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            subprocessArgs = VectorPopulationSubprocessArgs.fromFile(argsFile);
            currentArgs = subprocessArgs; // Set for embedding model access
            logger.info("Loaded vector population args for task: {}", subprocessArgs.taskId());

            // Create and start memory watchdog with GPU thresholds
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    subprocessArgs.memoryThresholdPercent(),
                    subprocessArgs.memoryCriticalPercent(),
                    subprocessArgs.memoryKillThresholdPercent(),
                    subprocessArgs.memoryCheckIntervalMs(),
                    subprocessArgs.gpuMemoryThresholdPercent(),
                    subprocessArgs.gpuMemoryCriticalPercent(),
                    subprocessArgs.gpuMemoryKillThresholdPercent()
            );
            memoryWatchdog.start();
            logger.info("Memory watchdog started: heap stop={}%, critical={}%, kill={}% GPU stop={}%, critical={}%, kill={}",
                    subprocessArgs.memoryThresholdPercent(),
                    subprocessArgs.memoryCriticalPercent(),
                    subprocessArgs.memoryKillThresholdPercent(),
                    subprocessArgs.gpuMemoryThresholdPercent(),
                    subprocessArgs.gpuMemoryCriticalPercent(),
                    subprocessArgs.gpuMemoryKillThresholdPercent());

            // Initialize progress reporter and HTTP callback FIRST so we can report progress during setup
            reporter = new SubprocessProgressReporter(subprocessArgs.taskId(), originalStdout);
            // Allow the memory watchdog to surface warnings via the same progress/log channel
            memoryWatchdog.setProgressReporter(reporter);

            // Start heartbeat immediately
            reporter.startHeartbeat();
            reporter.reportProgress("INITIALIZING", 0, "Starting", "Starting vector population subprocess...", null);

            // Configure model source from parent (BEFORE Spring context so embedding model can use it)
            reporter.reportProgress("INITIALIZING", 2, "Configuring", "Configuring model source from parent...", null);
            configureModelSource(subprocessArgs);
            reporter.reportProgress("INITIALIZING", 5, "Configured", "Model source configured", null);

            // Initialize HTTP callback
            if (subprocessArgs.callbackBaseUrl() != null && !subprocessArgs.callbackBaseUrl().isBlank()) {
                httpCallback = new HttpIngestCallback(subprocessArgs.callbackBaseUrl());
            }

            // Initialize ND4J BEFORE Spring context
            reporter.reportProgress("INITIALIZING", 8, "ND4J", "Initializing ND4J environment...", null);
            logger.info("Initializing ND4J environment...");
            initializeNd4j(subprocessArgs.nd4jConfigJson());
            reporter.reportProgress("INITIALIZING", 15, "ND4J Ready", "ND4J initialized", null);

            // Notify main app that we're starting
            if (httpCallback != null) {
                httpCallback.markJobRunning(subprocessArgs.taskId());
            }

            // Create minimal Spring context
            reporter.reportProgress("INITIALIZING", 18, "Spring", "Creating Spring context...", null);
            logger.info("Creating Spring context...");
            try (AnnotationConfigApplicationContext context = createContext(subprocessArgs)) {

                // CRITICAL: Configure batch size from subprocess args BEFORE using embedding model
                // The subprocess has its own Spring context with fresh AnseriniEmbeddingProperties
                // that defaults to 32. We need to inject the batch size from the args file.
                try {
                    ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties embeddingProps =
                            context.getBean(ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties.class);
                    if (embeddingProps != null) {
                        int argsBatchSize = subprocessArgs.embeddingBatchSize();
                        int argsMaxBatch = subprocessArgs.maxBatchSize();
                        embeddingProps.setBaseOptimalBatchSize(argsBatchSize);
                        embeddingProps.setBaseMaxBatchSize(argsMaxBatch);
                        logger.info("Subprocess: Configured AnseriniEmbeddingProperties from args: optimalBatch={}, maxBatch={}",
                                argsBatchSize, argsMaxBatch);
                    }
                } catch (Exception e) {
                    logger.debug("Could not configure AnseriniEmbeddingProperties from args: {}", e.getMessage());
                }

                reporter.reportProgress("INITIALIZING", 25, "Beans", "Selecting embedding model...", null);

                // Get required beans - select non-NoOp implementations
                EmbeddingModel embeddingModel = selectEmbeddingModel(context);
                IndexerService indexerService = selectIndexerService(context);

                String embeddingModelName = embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "none";
                String indexerName = indexerService != null ? indexerService.getClass().getSimpleName() : "none";
                embeddingModelId = subprocessArgs.modelIdentifier();
                embeddingModelDimension = null;

                reporter.reportProgress("INITIALIZING", 30, "Model", "Selected: " + embeddingModelName, null);
                logger.info("Selected embedding model: {}", embeddingModelName);
                logger.info("Selected indexer service: {}", indexerName);

                // Force embedding model initialization to catch any errors early
                if (embeddingModel != null) {
                    reporter.reportProgress("INITIALIZING", 35, "Loading", "Loading embedding model weights (this may take a moment)...", null);
                    int dims = embeddingModel.dimensions();
                    if (dims <= 0) {
                        reporter.reportProgress("FAILED", 0, "Error", "Embedding model returned invalid dimensions: " + dims, null);
                        throw new IllegalStateException("Embedding model returned invalid dimensions: " + dims);
                    }
                    embeddingModelDimension = dims;
                    reporter.reportProgress("INITIALIZING", 45, "Ready", "Embedding model ready: dimensions=" + dims, null);
                }

                // Log full feature set
                logSubprocessFeatures(context, subprocessArgs, reporter, embeddingModel, indexerService);

                // ========== CHECKPOINT/RESUME SUPPORT ==========
                // Load checkpoint state if available (enables resume from failed jobs)
                IngestCheckpointService checkpointService = null;
                IngestCheckpointService.CheckpointState checkpointState = null;
                Set<String> alreadyIndexedIds = new HashSet<>();
                Map<String, float[]> cachedEmbeddings = new HashMap<>();

                if (subprocessArgs.checkpointBasePath() != null && !subprocessArgs.checkpointBasePath().isBlank()) {
                    Path checkpointBasePath = Paths.get(subprocessArgs.checkpointBasePath());
                    checkpointService = new IngestCheckpointService(checkpointBasePath, subprocessArgs.taskId());

                    if (checkpointService.hasCheckpoint()) {
                        reporter.reportProgress("RESUMING", 48, "Loading checkpoint",
                                "Found checkpoint from previous run, loading resume state...", null);
                        checkpointState = checkpointService.loadState();
                        alreadyIndexedIds = checkpointState.indexedIds();
                        cachedEmbeddings = checkpointState.orphanedEmbeddings();

                        // Set static fields for progress reporting
                        resumedFromIndexedCount = alreadyIndexedIds.size();
                        isResumedRun = true;

                        logger.info("RESUME: Found {} already-indexed documents, {} cached embeddings to reuse",
                                alreadyIndexedIds.size(), cachedEmbeddings.size());
                        reporter.reportProgress("RESUMING", 50, "Checkpoint loaded",
                                String.format("Resuming: %d docs already indexed, %d embeddings cached",
                                        alreadyIndexedIds.size(), cachedEmbeddings.size()), null);
                    } else {
                        // Initialize checkpoint for this run
                        try {
                            checkpointService.init();
                            logger.info("Initialized new checkpoint at: {}", checkpointBasePath);
                        } catch (IOException e) {
                            logger.warn("Failed to initialize checkpoint directory: {}", e.getMessage());
                            checkpointService = null;
                        }
                    }
                }

                // Store checkpoint service for pipeline to use during processing
                final IngestCheckpointService finalCheckpointService = checkpointService;

                // Load documents from Lucene keyword index
                logger.info("Loading documents from Lucene index: {}", subprocessArgs.keywordIndexPath());
                reporter.reportPhaseStart("LOADING");
                long loadingStartMs = System.currentTimeMillis();
                List<RetrievedDoc> allDocuments = loadDocumentsFromLucene(
                        subprocessArgs.keywordIndexPath(),
                        reporter);

                // Filter out already-indexed documents (resume support)
                List<RetrievedDoc> documents;
                int skippedCount = 0;
                if (!alreadyIndexedIds.isEmpty()) {
                    documents = new ArrayList<>();
                    for (RetrievedDoc doc : allDocuments) {
                        String docId = doc.getId();
                        if (docId != null && alreadyIndexedIds.contains(docId)) {
                            skippedCount++;
                        } else {
                            documents.add(doc);
                        }
                    }
                    logger.info("RESUME: Skipping {} already-indexed documents, {} remaining to process",
                            skippedCount, documents.size());
                    reporter.reportProgress("LOADING", 55, "Filtered",
                            String.format("Skipped %d already-indexed, %d to process", skippedCount, documents.size()), null);
                } else {
                    documents = allDocuments;
                }

                long loadingDurationMs = System.currentTimeMillis() - loadingStartMs;
                reporter.reportPhaseTransition("LOADING", "EMBEDDING", loadingDurationMs);

                logger.info("Loaded {} documents from Lucene index (skipped {} already indexed)",
                        documents.size(), skippedCount);

                // Create and execute pipeline (skip chunking - documents already chunked)
                logger.info("Starting embedding and indexing pipeline...");

                // Build pipeline configuration from subprocess args
                // Note: Chunk batching has been removed - chunks are processed individually.
                // NDArray batching is now handled directly by the embedding model using its optimal batch size.
                // These batch size parameters are kept for backwards compatibility but mainly affect Lucene indexing.
                // IMPORTANT: minBatchSize must respect adaptive recovery settings (min=4).
                // Don't force a high minimum (like 32) that defeats OOM recovery!
                int minBatchSize = Math.max(AdaptiveRecoverySettings.MIN_BATCH_SIZE,
                        Math.min(subprocessArgs.embeddingBatchSize(), subprocessArgs.embeddingBatchSize() / 2));
                ParallelIngestPipeline.PipelineConfig pipelineConfig = ParallelIngestPipeline.PipelineConfig.builder()
                        .minBatchSize(minBatchSize)
                        .defaultBatchSize(subprocessArgs.embeddingBatchSize())
                        .maxBatchSize(subprocessArgs.maxBatchSize())
                        .queueCapacity(subprocessArgs.queueCapacity())
                        .indexingThreads(subprocessArgs.indexingWorkers())
                        .indexingBatchAccumulationSize(subprocessArgs.indexingBatchAccumulationSize())
                        .embeddingThreads(subprocessArgs.embeddingThreads())
                        .build();

                logger.info("Pipeline config: minBatch={}, batchSize={}, maxBatch={}, queue={}, indexThreads={}, embeddingThreads={}",
                        minBatchSize,
                        subprocessArgs.embeddingBatchSize(),
                        subprocessArgs.maxBatchSize(),
                        subprocessArgs.queueCapacity(),
                        subprocessArgs.indexingWorkers(),
                        subprocessArgs.embeddingThreads());

                // Use vectorOnlyMode=true since we're populating vector store from existing keyword index
                ParallelIngestPipeline pipeline = new ParallelIngestPipeline(
                        null, // No chunker - documents are pre-chunked
                        embeddingModel,
                        indexerService,
                        null, // No chunking options
                        pipelineConfig,
                        true  // vectorOnlyMode - skip keyword index since we're populating from it
                );

                // Set checkpoint service for progress persistence and resume
                if (finalCheckpointService != null) {
                    pipeline.setCheckpointService(finalCheckpointService);
                    logger.info("Checkpoint service attached to pipeline for progress tracking");
                }

                // Set up progress callback
                final SubprocessProgressReporter finalReporter = reporter;
                final int totalLoadedDocs = documents.size();
                final int skippedFromResume = skippedCount;
                pipeline.setProgressCallback(progress -> reportPipelineProgress(progress, finalReporter,
                        totalLoadedDocs + skippedFromResume)); // Include skipped in total for accurate progress

                // If we have cached embeddings from previous run, inject them into the pipeline
                // to skip re-embedding those chunks
                if (!cachedEmbeddings.isEmpty()) {
                    pipeline.setCachedEmbeddings(cachedEmbeddings);
                    logger.info("Injected {} cached embeddings from previous run", cachedEmbeddings.size());
                }

                // Execute pipeline
                PipelineResult result = pipeline.processPreChunked(documents);

                // CRITICAL: Flush any pending batched commits before reporting completion
                // With batch commit optimization, some documents may be buffered but not yet committed
                try {
                    Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores =
                            context.getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);
                    for (ai.kompile.core.embeddings.VectorStore vs : vectorStores.values()) {
                        if (!vs.getClass().getSimpleName().contains("NoOp")) {
                            logger.info("Flushing vector store: {}", vs.getClass().getSimpleName());
                            vs.flushAndCommit();
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error flushing vector stores: {}", e.getMessage());
                }

                // Report completion
                Map<String, Long> phaseDurations = new HashMap<>();
                phaseDurations.put("LOADING", loadingDurationMs);
                phaseDurations.put("EMBEDDING", result.totalTimeMs() / 2);  // Approximate split
                phaseDurations.put("INDEXING", result.totalTimeMs() / 2);

                reporter.reportCompleted(
                        documents.size(),
                        result.chunksCreated(),
                        result.chunksIndexed(),  // Use chunksIndexed as proxy for embedded
                        result.chunksIndexed(),
                        result.tokensProcessed(),
                        0,  // totalTokensInIndex - would need to query the index
                        subprocessArgs.vectorIndexPath(),
                        phaseDurations);

                if (httpCallback != null) {
                    httpCallback.markJobCompleted(subprocessArgs.taskId());
                }
                logger.info("Vector population completed successfully: {} chunks indexed", result.chunksIndexed());

                // Close pipeline
                pipeline.close();
            }

        } catch (InterruptedException e) {
            logger.info("Subprocess interrupted (likely cancelled)");
            if (reporter != null) {
                reporter.reportFailed("UNKNOWN", "Process interrupted", "InterruptedException", null);
            }
            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logCancelled(subprocessArgs.taskId(), "vector-population", "UNKNOWN",
                        "Process interrupted");
            }
            Thread.currentThread().interrupt();
            exitCode = 130;

        } catch (OutOfMemoryError oom) {
            // Handle OOM gracefully - log detailed info and exit cleanly
            // NOTE: If we reach here, it means adaptive memory recovery in the pipeline
            // either failed after all retry attempts, or OOM occurred outside the pipeline
            logger.error("====================================================================");
            logger.error("FATAL OUT OF MEMORY in vector population subprocess!");
            logger.error("====================================================================");
            logger.error("Adaptive memory recovery was attempted but ultimately failed.");
            logger.error("The pipeline tried reducing batch size and ND4J threads, but heap was exhausted.");
            logger.error("");
            logger.error("Recommendations:");
            logger.error("  1) Increase subprocess heap: -Xmx4g or higher");
            logger.error("  2) Reduce initial embeddingBatchSize (current: {})",
                    subprocessArgs != null ? subprocessArgs.embeddingBatchSize() : "unknown");
            logger.error("  3) Process fewer documents at a time");
            logger.error("  4) Use a smaller embedding model");
            logger.error("");
            logger.error("Current heap: max={}MB, used={}MB, free={}MB",
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024),
                    Runtime.getRuntime().freeMemory() / (1024 * 1024));
            logger.error("====================================================================");

            // Try to recover memory for cleanup
            System.gc();

            String phase = "EMBEDDING";
            String errorMsg = "OutOfMemoryError - adaptive recovery failed, heap exhausted. " +
                    "Increase -Xmx or reduce batch size.";

            if (reporter != null) {
                reporter.reportFailed(phase, errorMsg, "OutOfMemoryError", null);
            }

            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logFailed(
                        subprocessArgs.taskId(),
                        "vector-population",
                        phase,
                        errorMsg,
                        getStackTrace(oom));
                // DON'T mark job as failed here - let the PARENT process handle retry logic
                // The parent will decide whether to retry or mark as failed
                logger.info("OOM detected - NOT marking job as failed. Parent will handle retry.");
            }

            exitCode = 137; // Standard OOM exit code

        } catch (Exception e) {
            logger.error("Subprocess failed", e);

            String phase = "UNKNOWN";
            String errorMsg = e.getMessage();

            // Check if this is an OOM-related exception
            boolean isOomRelated = isOomRelated(e);

            if (reporter != null) {
                reporter.reportFailed(phase, e);
            }

            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logFailed(
                        subprocessArgs.taskId(),
                        "vector-population",
                        phase,
                        errorMsg,
                        getStackTrace(e));

                // DON'T mark job as failed for OOM-related errors - let parent handle retry
                if (!isOomRelated) {
                    httpCallback.markJobFailed(subprocessArgs.taskId(), phase, errorMsg, "UNKNOWN");
                } else {
                    logger.info("OOM-related exception detected - NOT marking job as failed. Parent will handle retry.");
                }
            }

            exitCode = isOomRelated ? 137 : 1;

        } finally {
            // Stop memory watchdog
            if (memoryWatchdog != null) {
                try {
                    memoryWatchdog.close();
                    logger.info("Memory watchdog stopped");
                } catch (Exception e) {
                    logger.warn("Error stopping memory watchdog: {}", e.getMessage());
                }
            }

            // Stop heartbeat
            if (reporter != null) {
                reporter.stopHeartbeat();
                reporter.close();
            }

            // Close HTTP callback
            if (httpCallback != null) {
                httpCallback.close();
            }

            // Cleanup ND4J resources
            cleanupNd4j();
        }

        System.exit(exitCode);
    }

    /**
     * Load documents from existing Lucene keyword index.
     */
    private static List<RetrievedDoc> loadDocumentsFromLucene(
            String indexPath,
            SubprocessProgressReporter reporter) throws Exception {

        Path indexDir = Paths.get(indexPath);
        if (!Files.exists(indexDir) || !Files.isDirectory(indexDir)) {
            throw new IllegalArgumentException("Lucene index directory does not exist: " + indexPath);
        }

        List<RetrievedDoc> documents = new ArrayList<>();

        try (FSDirectory directory = NativeCompatibleDirectoryFactory.openFSDirectory(indexDir);
             IndexReader reader = DirectoryReader.open(directory)) {

            int totalDocs = reader.maxDoc();
            logger.info("Found {} documents in Lucene index", totalDocs);

            for (int i = 0; i < totalDocs; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                Document doc = reader.storedFields().document(i);

                // Extract text content - try common field names
                String content = doc.get("contents");
                if (content == null) content = doc.get("content");
                if (content == null) content = doc.get("body");
                if (content == null) content = doc.get("raw");
                if (content == null) content = doc.get("text");

                if (content == null || content.isBlank()) {
                    continue; // Skip empty documents
                }

                // Get document ID
                String docId = doc.get("id");
                if (docId == null) docId = doc.get("docid");
                if (docId == null) docId = String.valueOf(i);

                // Build metadata from other fields
                Map<String, Object> metadata = new HashMap<>();
                for (var field : doc.getFields()) {
                    String name = field.name();
                    if (!name.equals("contents") && !name.equals("content") &&
                            !name.equals("body") && !name.equals("raw") && !name.equals("text")) {
                        String value = field.stringValue();
                        if (value != null) {
                            metadata.put(name, value);
                        }
                    }
                }

                documents.add(RetrievedDoc.builder()
                        .id(docId)
                        .text(content)
                        .metadata(metadata)
                        .build());

                // Report progress every 500 documents
                if (i % 500 == 0 || i == totalDocs - 1) {
                    int percent = Math.min(100, (i * 100) / totalDocs);
                    reporter.reportProgress("LOADING", percent, "Loading documents",
                            String.format("Loaded %d/%d documents", i + 1, totalDocs),
                            createLoadingStats(i + 1, totalDocs));
                }
            }
        }

        return documents;
    }

    // Static fields to track resume state for progress reporting
    private static volatile int resumedFromIndexedCount = 0;
    private static volatile boolean isResumedRun = false;

    /**
     * Report pipeline progress to the subprocess reporter.
     */
    private static void reportPipelineProgress(PipelineProgress progress, SubprocessProgressReporter reporter, int totalLoadedDocs) {
        try {
            String phase = mapPhase(progress.phase());

            // Capture queue sizes
            PipelineProgress.QueueStatus qs = progress.queueStatus();
            int chunkQueueSize = qs != null ? qs.chunkQueueSize() : 0;
            int embeddingQueueSize = qs != null ? qs.embeddedQueueSize() : 0;

            // Capture worker status snapshots for UI visibility
            List<SubprocessMessage.WorkerStatusSnapshot> workerSnapshots = null;
            if (progress.workerStatuses() != null && !progress.workerStatuses().isEmpty()) {
                workerSnapshots = progress.workerStatuses().stream()
                        .map(ws -> new SubprocessMessage.WorkerStatusSnapshot(
                                ws.workerId(),
                                ws.workerType(),
                                ws.status(),
                                ws.itemsProcessed(),
                                ws.currentBatchSize(),
                                ws.throughput(),
                                ws.currentItem()
                        ))
                        .toList();
            }

            // Prefer live batch size from current embedding batch metrics, otherwise use configured target
            int effectiveBatchSize = 0;
            PipelineProgress.EmbeddingBatchMetrics bm = progress.currentEmbeddingBatch();
            if (bm != null && bm.inputTexts() > 0) {
                effectiveBatchSize = bm.inputTexts();
            } else if (currentArgs != null && currentArgs.embeddingBatchSize() > 0) {
                effectiveBatchSize = currentArgs.embeddingBatchSize();
            } else {
                effectiveBatchSize = 32;
            }

            int effectiveWorkerThreads = progress.embeddingWorkers();
            if (effectiveWorkerThreads <= 0 && currentArgs != null && currentArgs.embeddingThreads() > 0) {
                effectiveWorkerThreads = currentArgs.embeddingThreads();
            }
            if (effectiveWorkerThreads <= 0) {
                effectiveWorkerThreads = 1;
            }

            SubprocessMessage.RuntimeInfo runtimeInfo = buildRuntimeInfo();

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

            // Include resumed count in embedded total for accurate progress display
            int totalChunksEmbedded = progress.chunksEmbedded() + (isResumedRun ? resumedFromIndexedCount : 0);
            int totalChunksIndexed = progress.chunksIndexed() + (isResumedRun ? resumedFromIndexedCount : 0);

            SubprocessMessage.ProgressStats stats = new SubprocessMessage.ProgressStats(
                    Math.max(0, totalLoadedDocs),     // documentsLoaded
                    progress.chunksCreated(),         // chunksCreated
                    totalChunksEmbedded,              // chunksEmbedded (includes resumed)
                    totalChunksIndexed,               // documentsIndexed (includes resumed)
                    progress.tokensProcessed(),       // tokensProcessed
                    0L,                               // totalTokensInIndex
                    0L,                               // totalProcessingTimeMs
                    0L,                               // loadingDurationMs
                    0L,                               // chunkingDurationMs
                    0L,                               // embeddingDurationMs
                    0L,                               // indexingDurationMs
                    "LuceneIndex",                    // loaderUsed
                    null,                             // chunkerUsed
                    effectiveBatchSize,               // batchSize
                    effectiveWorkerThreads,           // workerThreads
                    true,                             // parallelProcessing
                    progress.chunksPerSecond(),       // chunksPerSecond
                    0.0,                              // docsPerSecond
                    progress.memoryUsagePercent(),    // memoryUsagePercent
                    "OK",                             // memoryStatus
                    progress.phase(),                 // activeStage
                    "PROCESSING",                     // pipelineStatus
                    chunkQueueSize,                   // chunkQueueSize
                    embeddingQueueSize,               // embeddingQueueSize
                    workerSnapshots,                  // workerStatuses
                    runtimeInfo,                      // runtimeInfo
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
                    isResumedRun ? resumedFromIndexedCount : null,  // resumedFromChunkCount
                    isResumedRun ? resumedFromIndexedCount : null,  // resumedFromEmbeddedCount
                    isResumedRun ? resumedFromIndexedCount : null,  // resumedFromIndexedCount
                    isResumedRun                                    // isResumedRun
            );

            reporter.reportProgress(phase, progress.progressPercent(), progress.phase(),
                    progress.message(), stats);

        } catch (Exception e) {
            logger.warn("Failed to report progress: {}", e.getMessage());
        }
    }

    private static String mapPhase(String pipelinePhase) {
        // Pipeline phases here come from ParallelIngestPipeline and include values like:
        // "starting", "queueing", "processing", "embedding", "indexing+embedding", "complete"
        // For vector population, anything involving embedding should be shown as EMBEDDING.
        if (pipelinePhase == null) {
            return "EMBEDDING";
        }

        String p = pipelinePhase.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty()) {
            return "EMBEDDING";
        }

        if (p.contains("fail") || p.contains("error")) return "FAILED";
        if (p.contains("cancel")) return "CANCELLED";
        if (p.contains("complete") || p.contains("done")) return "COMPLETED";

        if (p.contains("embed")) return "EMBEDDING";
        if (p.contains("index")) return "INDEXING";

        // In pre-chunked vector population mode, most remaining phases ("starting", "queueing", "processing")
        // are part of the embedding/indexing pipeline, not Lucene document loading.
        if (p.contains("queue") || p.contains("process") || p.contains("start")) return "EMBEDDING";

        if (p.contains("load") || p.contains("chunk")) return "LOADING";

        return "EMBEDDING";
    }

    private static SubprocessMessage.ProgressStats createLoadingStats(int loaded, int total) {
        SubprocessMessage.RuntimeInfo runtimeInfo = buildRuntimeInfo();
        return new SubprocessMessage.ProgressStats(
                loaded,                  // documentsLoaded
                0,                       // chunksCreated
                0,                       // chunksEmbedded
                0,                       // documentsIndexed
                0L,                      // tokensProcessed
                0L,                      // totalTokensInIndex
                0L,                      // totalProcessingTimeMs
                0L,                      // loadingDurationMs
                0L,                      // chunkingDurationMs
                0L,                      // embeddingDurationMs
                0L,                      // indexingDurationMs
                "LuceneIndex",           // loaderUsed
                null,                    // chunkerUsed
                32,                      // batchSize
                1,                       // workerThreads
                true,                    // parallelProcessing
                0.0,                     // chunksPerSecond
                0.0,                     // docsPerSecond
                0.0,                     // memoryUsagePercent
                "OK",                    // memoryStatus
                "LOADING",               // activeStage
                "PROCESSING",            // pipelineStatus
                0,                       // chunkQueueSize
                0,                       // embeddingQueueSize
                null,                    // workerStatuses
                runtimeInfo,             // runtimeInfo
                // ========== Embedding batch metrics (null during loading) ==========
                null,                    // currentBatchNumber
                null,                    // totalBatches
                null,                    // inputTexts
                null,                    // maxSequenceLength
                null,                    // embeddingDimension
                null,                    // actualInputShape
                null,                    // actualOutputShape
                null,                    // currentStep
                null,                    // tokenizationTimeMs
                null,                    // paddingTimeMs
                null,                    // tensorCreationTimeMs
                null,                    // forwardPassTimeMs
                null,                    // extractionTimeMs
                null,                    // batchHistory
                null,                    // passageTokenCounts
                // ========== Resume/Restart info ==========
                null,                    // resumedFromChunkCount
                null,                    // resumedFromEmbeddedCount
                null,                    // resumedFromIndexedCount
                null                     // isResumedRun
        );
    }

    /**
     * Initialize ND4J environment - must be called BEFORE any ND4J/SameDiff operations.
     */
    private static void initializeNd4j(String nd4jConfigJson) throws Exception {
        logger.info("Initializing ND4J backend and environment...");

        // Initialize DifferentialFunctionClassHolder
        DifferentialFunctionClassHolder.initInstance();

        // Use built-in backend discovery - automatically finds CUDA, CPU, or other available backends
        // This mirrors MainApplication's approach and avoids hardcoding nd4j-native
        Nd4jBackend backend = Nd4jBackend.load();
        Nd4j.backend = backend;
        logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

        // NativeOps is automatically initialized by backend loading
        NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
        nativeOps.initializeDevicesAndFunctions();

        // Apply ND4J environment config if provided
        if (nd4jConfigJson != null && !nd4jConfigJson.isBlank()) {
            try {
                Nd4jEnvironmentConfig config = OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
                nd4jEnvironmentInvoked = config;
                applyNd4jEnvironmentConfig(config);
            } catch (Exception e) {
                logger.warn("Failed to parse ND4J config JSON, using defaults: {}", e.getMessage());
                nd4jEnvironmentInvoked = Nd4jEnvironmentConfig.defaults();
                applyNd4jEnvironmentConfig(nd4jEnvironmentInvoked);
            }
        } else {
            nd4jEnvironmentInvoked = Nd4jEnvironmentConfig.defaults();
            applyNd4jEnvironmentConfig(nd4jEnvironmentInvoked);
        }
        nd4jEnvironmentUsed = captureNd4jEnvironmentConfig();

        logger.info("ND4J initialized: maxThreads={}, backend={}",
                Nd4j.getEnvironment().maxThreads(),
                Nd4j.getBackend().getClass().getSimpleName());
    }

    /**
     * Apply ND4J environment configuration.
     * This mirrors MainApplication.applyNd4jEnvironmentConfig() exactly.
     */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) {
            config = Nd4jEnvironmentConfig.defaults();
        }
        logger.info("Applying ND4J environment configuration (subprocess)...");

        try {
            // === CORE SETTINGS (must be set first) ===
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
                logger.debug("Set enableBlas to {}", config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
                logger.debug("Set helpersAllowed to {}", config.helpersAllowed());
            }

            // === THREAD CONFIGURATION ===
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
                logger.debug("Set maxThreads to {}", config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
                logger.debug("Set maxMasterThreads to {}", config.maxMasterThreads());
            }

            // === DEBUG/VERBOSE MODES ===
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(config.debug());
                logger.debug("Set debug to {}", config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
                logger.debug("Set verbose to {}", config.verbose());
            }
            if (config.profiling() != null) {
                Nd4j.getEnvironment().setProfiling(config.profiling());
                logger.debug("Set profiling to {}", config.profiling());
            }
            if (config.leaksDetector() != null) {
                Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
                logger.debug("Set leaksDetector to {}", config.leaksDetector());
            }

            // === PERFORMANCE THRESHOLDS ===
            if (config.tadThreshold() != null) {
                Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
                logger.debug("Set tadThreshold to {}", config.tadThreshold());
            }
            if (config.elementwiseThreshold() != null) {
                Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
                logger.debug("Set elementwiseThreshold to {}", config.elementwiseThreshold());
            }

            // === MEMORY LIMITS ===
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) {
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
                logger.debug("Set maxPrimaryMemory to {}", config.maxPrimaryMemory());
            }
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) {
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
                logger.debug("Set maxSpecialMemory to {}", config.maxSpecialMemory());
            }
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) {
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
                logger.debug("Set maxDeviceMemory to {}", config.maxDeviceMemory());
            }

            // === LIFECYCLE TRACKING MASTER SWITCH ===
            if (config.lifecycleTracking() != null) {
                Nd4j.getEnvironment().setLifecycleTracking(config.lifecycleTracking());
                logger.debug("Set lifecycleTracking to {}", config.lifecycleTracking());
            }

            // === LIFECYCLE TRACKING SUB-OPTIONS ===
            if (config.trackViews() != null) {
                Nd4j.getEnvironment().setTrackViews(config.trackViews());
            }
            if (config.trackDeletions() != null) {
                Nd4j.getEnvironment().setTrackDeletions(config.trackDeletions());
            }
            if (config.snapshotFiles() != null) {
                Nd4j.getEnvironment().setSnapshotFiles(config.snapshotFiles());
            }
            if (config.trackOperations() != null) {
                Nd4j.getEnvironment().setTrackOperations(config.trackOperations());
            }

            // === LIFECYCLE TRACKING PARAMETERS ===
            if (config.stackDepth() != null) {
                Nd4j.getEnvironment().setStackDepth(config.stackDepth());
            }
            if (config.reportInterval() != null) {
                Nd4j.getEnvironment().setReportInterval(config.reportInterval());
            }
            if (config.maxDeletionHistory() != null) {
                Nd4j.getEnvironment().setMaxDeletionHistory(config.maxDeletionHistory());
            }

            // === INDIVIDUAL TRACKER TOGGLES ===
            if (config.ndArrayTracking() != null) {
                Nd4j.getEnvironment().setNDArrayTracking(config.ndArrayTracking());
            }
            if (config.dataBufferTracking() != null) {
                Nd4j.getEnvironment().setDataBufferTracking(config.dataBufferTracking());
            }
            if (config.tadCacheTracking() != null) {
                Nd4j.getEnvironment().setTADCacheTracking(config.tadCacheTracking());
            }
            if (config.shapeCacheTracking() != null) {
                Nd4j.getEnvironment().setShapeCacheTracking(config.shapeCacheTracking());
            }
            if (config.opContextTracking() != null) {
                Nd4j.getEnvironment().setOpContextTracking(config.opContextTracking());
            }

            // === ADVANCED DEBUGGING - FUNCTION TRACING ===
            if (config.funcTracePrintAllocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForAllocate(config.funcTracePrintAllocate());
            }
            if (config.funcTracePrintDeallocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForDeallocate(config.funcTracePrintDeallocate());
            }
            if (config.funcTracePrintJavaOnly() != null) {
                Nd4j.getEnvironment().setFuncTracePrintJavaOnly(config.funcTracePrintJavaOnly());
            }

            // === ADVANCED DEBUGGING - OTHER ===
            if (config.logNativeNDArrayCreation() != null) {
                Nd4j.getEnvironment().setLogNativeNDArrayCreation(config.logNativeNDArrayCreation());
            }
            if (config.logNDArrayEvents() != null) {
                Nd4j.getEnvironment().setLogNDArrayEvents(config.logNDArrayEvents());
            }
            if (config.checkInputChange() != null) {
                Nd4j.getEnvironment().setCheckInputChange(config.checkInputChange());
            }
            if (config.checkOutputChange() != null) {
                Nd4j.getEnvironment().setCheckOutputChange(config.checkOutputChange());
            }
            if (config.trackWorkspaceOpenClose() != null) {
                Nd4j.getEnvironment().setTrackWorkspaceOpenClose(config.trackWorkspaceOpenClose());
            }
            if (config.deleteShapeInfo() != null) {
                Nd4j.getEnvironment().setDeleteShapeInfo(config.deleteShapeInfo());
            }
            if (config.deletePrimary() != null) {
                Nd4j.getEnvironment().setDeletePrimary(config.deletePrimary());
            }
            if (config.deleteSpecial() != null) {
                Nd4j.getEnvironment().setDeleteSpecial(config.deleteSpecial());
            }
            if (config.variableTracingEnabled() != null) {
                Nd4j.getEnvironment().setVariableTracingEnabled(config.variableTracingEnabled());
            }

            // === JAVACPP SETTINGS (system properties) ===
            if (config.javacppLoggerDebug() != null) {
                System.setProperty("org.bytedeco.javacpp.logger.debug", config.javacppLoggerDebug().toString());
            }
            if (config.javacppPathsFirst() != null) {
                System.setProperty("org.bytedeco.javacpp.pathsFirst", config.javacppPathsFirst().toString());
            }

            // === OMP CONFIGURATION ===
            if (config.ompNumThreads() != null && config.ompNumThreads() > 0) {
                NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
                nativeOps.setOmpNumThreads(config.ompNumThreads());
            }

            logger.info("ND4J config applied: maxThreads={}, ompThreads={}",
                    Nd4j.getEnvironment().maxThreads(), getOmpNumThreads());
            logger.info("  debug={}, verbose={}, profiling={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().isDebug(),
                    Nd4j.getEnvironment().isVerbose(),
                    Nd4j.getEnvironment().isProfiling(),
                    Nd4j.getEnvironment().isLifecycleTracking());

        } catch (Exception e) {
            logger.error("Error applying ND4J environment configuration: {}", e.getMessage(), e);
            logger.warn("Falling back to ND4J default settings");
        }
    }

    private static Nd4jEnvironmentConfig captureNd4jEnvironmentConfig() {
        try {
            return Nd4jEnvironmentConfig.captureFromEnvironment(null, getOmpNumThreads());
        } catch (Exception e) {
            logger.debug("Failed to capture ND4J environment config in subprocess: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get configured OpenMP thread count.
     * Note: ompGetNumThreads() returns threads in the CURRENT parallel region (1 outside parallel regions).
     * We use Nd4j.getEnvironment().maxThreads() which shows the CONFIGURED max threads,
     * which is what actually matters for display purposes.
     */
    private static int getOmpNumThreads() {
        try {
            // First try to get the configured max threads from ND4J environment
            // This is more reliable than ompGetNumThreads() which returns 1 outside parallel regions
            return (int) Nd4j.getEnvironment().maxThreads();
        } catch (Exception e) {
            logger.debug("Failed to get max threads from ND4J: {}", e.getMessage());
            // Fallback to OMP_NUM_THREADS environment variable
            String ompEnv = System.getenv("OMP_NUM_THREADS");
            if (ompEnv != null && !ompEnv.isBlank()) {
                try {
                    return Integer.parseInt(ompEnv.trim());
                } catch (NumberFormatException nfe) {
                    // ignore
                }
            }
            return Runtime.getRuntime().availableProcessors(); // reasonable default
        }
    }

    private static SubprocessMessage.RuntimeInfo buildRuntimeInfo() {
        VectorPopulationSubprocessArgs args = currentArgs;
        List<String> inputFiles = new ArrayList<>();
        if (args != null) {
            if (args.keywordIndexPath() != null && !args.keywordIndexPath().isBlank()) {
                inputFiles.add(args.keywordIndexPath());
            }
            if (args.vectorIndexPath() != null && !args.vectorIndexPath().isBlank()) {
                inputFiles.add(args.vectorIndexPath());
            }
        }
        return SubprocessMessage.RuntimeInfo.collect(
                embeddingModelId,
                null,
                embeddingModelDimension,
                inputFiles,
                nd4jEnvironmentInvoked,
                nd4jEnvironmentUsed
        );
    }

    /**
     * Select the best embedding model from the context, preferring non-NoOp implementations.
     */
    private static EmbeddingModel selectEmbeddingModel(AnnotationConfigApplicationContext context) {
        Map<String, EmbeddingModel> embeddingModels = context.getBeansOfType(EmbeddingModel.class);
        logger.info("Found {} embedding model beans: {}", embeddingModels.size(), embeddingModels.keySet());

        // Filter out NoOp implementations
        EmbeddingModel selected = embeddingModels.values().stream()
                .filter(e -> !(e instanceof ai.kompile.core.embeddings.NoOpEmbeddingModelImpl))
                .filter(e -> !e.getClass().getSimpleName().contains("NoOp"))
                .findFirst()
                .orElse(null);

        if (selected == null && !embeddingModels.isEmpty()) {
            // Fall back to any available model
            selected = embeddingModels.values().iterator().next();
            logger.warn("No non-NoOp embedding model found, falling back to: {}",
                    selected.getClass().getSimpleName());
        }

        return selected;
    }

    /**
     * Select the best indexer service from the context, preferring non-NoOp implementations.
     */
    private static IndexerService selectIndexerService(AnnotationConfigApplicationContext context) {
        Map<String, IndexerService> indexerServices = context.getBeansOfType(IndexerService.class);
        logger.info("Found {} indexer service beans: {}", indexerServices.size(), indexerServices.keySet());

        // Filter out NoOp implementations
        IndexerService selected = indexerServices.values().stream()
                .filter(s -> !(s instanceof ai.kompile.core.indexers.NoOpIndexerService))
                .filter(s -> !s.getClass().getSimpleName().contains("NoOp"))
                .findFirst()
                .orElse(null);

        if (selected == null && !indexerServices.isEmpty()) {
            // Fall back to any available service
            selected = indexerServices.values().iterator().next();
            logger.warn("No non-NoOp indexer service found, falling back to: {}",
                    selected.getClass().getSimpleName());
        }

        return selected;
    }

    /**
     * Create the minimal Spring context with only required beans.
     */
    private static AnnotationConfigApplicationContext createContext(VectorPopulationSubprocessArgs args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        // Enable subprocess mode
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.mode", "true");
        context.getEnvironment().getSystemProperties().put("kompile.vectorpopulation.mode", "true");

        // Set properties from args
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.taskId", args.taskId());

        // Set index paths
        if (args.keywordIndexPath() != null) {
            context.getEnvironment().getSystemProperties().put("anserini.indexPath", args.keywordIndexPath());
        }
        if (args.vectorIndexPath() != null) {
            context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.index-path", args.vectorIndexPath());
        }

        // CRITICAL: Enable the Anserini vector store so it's created (not NoOp fallback)
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.enabled", "true");
        // Ensure persistence is enabled so subprocess writes to the same path as main app
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.persistence-enabled", "true");

        // Set batch size
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.embeddingBatchSize",
                String.valueOf(args.embeddingBatchSize()));

        // Register configuration class - reuse existing subprocess config
        context.register(SubprocessIngestConfiguration.class);

        // Set active profile
        context.getEnvironment().setActiveProfiles("subprocess");

        // Refresh context
        context.refresh();

        return context;
    }

    /**
     * Log comprehensive subprocess feature set.
     * This provides full visibility into what capabilities the subprocess has.
     */
    private static void logSubprocessFeatures(AnnotationConfigApplicationContext context,
                                               VectorPopulationSubprocessArgs args,
                                               SubprocessProgressReporter reporter,
                                               EmbeddingModel selectedEmbedding,
                                               IndexerService selectedIndexer) {
        logger.info("═══════════════════════════════════════════════════════════════════════════════");
        logger.info("             VECTOR POPULATION SUBPROCESS FEATURE SET REPORT");
        logger.info("═══════════════════════════════════════════════════════════════════════════════");

        // Task Info
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ TASK CONFIGURATION                                                          │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        logger.info("│ Task ID:           {}", args.taskId());
        logger.info("│ Keyword Index:     {}", args.keywordIndexPath());
        logger.info("│ Vector Index:      {}", args.vectorIndexPath());
        logger.info("│ Embedding Batch:   {}", args.embeddingBatchSize());
        logger.info("│ Parallel Indexing: {}", args.parallelIndexing());
        logger.info("│ Callback URL:      {}", args.callbackBaseUrl() != null ? args.callbackBaseUrl() : "(none)");
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Embedding Models
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ EMBEDDING MODELS                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, EmbeddingModel> embeddings = context.getBeansOfType(EmbeddingModel.class);
            if (embeddings.isEmpty()) {
                logger.info("│ ⚠ NO EMBEDDING MODELS AVAILABLE                                            │");
            } else {
                logger.info("│ {} embedding model(s) available:                                            ", embeddings.size());
                for (Map.Entry<String, EmbeddingModel> entry : embeddings.entrySet()) {
                    EmbeddingModel model = entry.getValue();
                    String name = model.getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    boolean isSelected = model == selectedEmbedding;
                    String marker = isSelected ? "★" : (isNoOp ? "⚠" : "✓");
                    logger.info("│   {} {} (bean: {}){}", marker, name, bean, isSelected ? " [SELECTED]" : "");
                    // Log model details if available
                    try {
                        String modelId = model.getModelIdentifier();
                        int dimensions = model.getDimensions();
                        logger.info("│       Model ID: {}", modelId != null ? modelId : "(unknown)");
                        logger.info("│       Dimensions: {}", dimensions > 0 ? dimensions : "(unknown)");
                    } catch (Exception ex) {
                        // Ignore - some models don't expose these
                    }
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing embedding models: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Indexer Services
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ INDEXER SERVICES                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, IndexerService> indexers = context.getBeansOfType(IndexerService.class);
            if (indexers.isEmpty()) {
                logger.info("│ ⚠ NO INDEXER SERVICES AVAILABLE                                            │");
            } else {
                logger.info("│ {} indexer service(s) available:                                            ", indexers.size());
                for (Map.Entry<String, IndexerService> entry : indexers.entrySet()) {
                    IndexerService svc = entry.getValue();
                    String name = svc.getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    boolean isSelected = svc == selectedIndexer;
                    String marker = isSelected ? "★" : (isNoOp ? "⚠" : "✓");
                    logger.info("│   {} {} (bean: {}){}", marker, name, bean, isSelected ? " [SELECTED]" : "");
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing indexers: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Vector Stores
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ VECTOR STORES                                                               │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores = context.getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);
            if (vectorStores.isEmpty()) {
                logger.info("│ ⚠ NO VECTOR STORES AVAILABLE                                               │");
            } else {
                logger.info("│ {} vector store(s) available:                                               ", vectorStores.size());
                for (Map.Entry<String, ai.kompile.core.embeddings.VectorStore> entry : vectorStores.entrySet()) {
                    String name = entry.getValue().getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    String marker = isNoOp ? "⚠" : "✓";
                    logger.info("│   {} {} (bean: {})", marker, name, bean);
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing vector stores: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // ND4J Environment
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ ND4J ENVIRONMENT                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            logger.info("│ Backend:           {}", Nd4j.getBackend().getClass().getSimpleName());
            logger.info("│ Max Threads:       {}", Nd4j.getEnvironment().maxThreads());
            logger.info("│ Max Master Threads:{}", Nd4j.getEnvironment().maxMasterThreads());
            logger.info("│ BLAS Enabled:      {}", Nd4j.getEnvironment().isEnableBlas());
            logger.info("│ Helpers Allowed:   {}", Nd4j.getEnvironment().helpersAllowed());
            logger.info("│ Debug Mode:        {}", Nd4j.getEnvironment().isDebug());
            logger.info("│ Verbose Mode:      {}", Nd4j.getEnvironment().isVerbose());
            logger.info("│ Profiling:         {}", Nd4j.getEnvironment().isProfiling());
            logger.info("│ Lifecycle Tracking:{}", Nd4j.getEnvironment().isLifecycleTracking());
            logger.info("│ Leak Detector:     {}", Nd4j.getEnvironment().isDetectingLeaks());
        } catch (Exception e) {
            logger.info("│ ⚠ Error reading ND4J environment: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // System Resources
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ SYSTEM RESOURCES                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        int availableProcessors = runtime.availableProcessors();
        logger.info("│ Available CPUs:    {}", availableProcessors);
        logger.info("│ Max Heap:          {} MB", maxMemory);
        logger.info("│ Total Heap:        {} MB", totalMemory);
        logger.info("│ Used Heap:         {} MB", usedMemory);
        logger.info("│ Free Heap:         {} MB", freeMemory);
        logger.info("│ Java Version:      {}", System.getProperty("java.version"));
        logger.info("│ OS:                {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Feature Summary
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ FEATURE SUMMARY                                                             │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, EmbeddingModel> embeddings = context.getBeansOfType(EmbeddingModel.class);
            Map<String, IndexerService> indexers = context.getBeansOfType(IndexerService.class);
            Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores = context.getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);

            // Check selected implementations
            boolean hasRealEmbedding = selectedEmbedding != null &&
                !selectedEmbedding.getClass().getSimpleName().contains("NoOp");
            boolean hasRealIndexer = selectedIndexer != null &&
                !selectedIndexer.getClass().getSimpleName().contains("NoOp");
            long realVectorStores = vectorStores.values().stream()
                .filter(v -> !v.getClass().getSimpleName().contains("NoOp"))
                .count();

            boolean fullyFunctional = hasRealEmbedding && hasRealIndexer;

            logger.info("│ Embedding:         {} (selected: {})", hasRealEmbedding ? "✓" : "✗",
                    selectedEmbedding != null ? selectedEmbedding.getClass().getSimpleName() : "none");
            logger.info("│ Keyword Indexing:  {} (selected: {})", hasRealIndexer ? "✓" : "✗",
                    selectedIndexer != null ? selectedIndexer.getClass().getSimpleName() : "none");
            logger.info("│ Vector Storage:    {} (stores: {}, real: {})", realVectorStores > 0 ? "✓" : "✗",
                    vectorStores.size(), realVectorStores);
            logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
            if (fullyFunctional) {
                logger.info("│ ✓ VECTOR POPULATION SUBPROCESS IS FULLY FUNCTIONAL                        │");
            } else {
                logger.info("│ ⚠ VECTOR POPULATION SUBPROCESS HAS LIMITED FUNCTIONALITY                  │");
                if (!hasRealEmbedding) logger.info("│   - Using NoOp embedding model - vectors will be dummy data!              │");
                if (!hasRealIndexer) logger.info("│   - Using NoOp indexer service - documents won't be indexed!              │");
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error generating summary: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");
        logger.info("═══════════════════════════════════════════════════════════════════════════════");

        // Also report via subprocess protocol for main app visibility
        reporter.reportLog("INFO", "Vector population subprocess feature set initialized - see subprocess logs for details");
    }

    /**
     * Configure model source from parent process args.
     * This MUST be called BEFORE Spring context is created so the embedding model
     * uses the same model source as the parent.
     *
     * Model sources (only 2 options):
     * 1. "staging" - Uses staging service (remote model download)
     * 2. "archive" - Uses locally loaded archive
     */
    private static void configureModelSource(VectorPopulationSubprocessArgs args) {
        String sourceType = args.modelSourceType();
        String modelId = args.modelIdentifier();

        logger.info("Configuring model source from parent: type={}, modelId={}", sourceType, modelId);

        if (sourceType == null || sourceType.isBlank()) {
            logger.warn("No model source type specified by parent - embedding may use default config");
            return;
        }

        try {
            if ("staging".equalsIgnoreCase(sourceType)) {
                // Configure staging service using the static method
                String stagingUrl = args.stagingUrl();
                String stagingApiKey = args.stagingApiKey();

                if (stagingUrl != null && !stagingUrl.isBlank()) {
                    logger.info("Configuring staging service: url={}, modelId={}", stagingUrl, modelId);
                    ai.kompile.embedding.anserini.AnseriniEncoderFactory.configureStagingService(stagingUrl, stagingApiKey);
                    // Model selection is handled by the embedding model constructor
                    // which reads from AnseriniEncoderFactory.getSelectedDenseRetrievalModel()
                } else {
                    logger.warn("Staging source type specified but no staging URL provided");
                }

            } else if ("archive".equalsIgnoreCase(sourceType)) {
                // Load from archive path using the static method
                String archivePath = args.archivePath();

                if (archivePath != null && !archivePath.isBlank()) {
                    java.nio.file.Path path = java.nio.file.Paths.get(archivePath);
                    if (java.nio.file.Files.exists(path)) {
                        logger.info("Loading model from archive: {}", archivePath);
                        ai.kompile.embedding.anserini.AnseriniEncoderFactory.loadArchive(path);
                    } else {
                        logger.error("Archive path does not exist: {}", archivePath);
                    }
                } else {
                    logger.warn("Archive source type specified but no archive path provided");
                }

            } else {
                logger.warn("Unknown model source type: {} - expected 'staging' or 'archive'", sourceType);
            }

        } catch (Exception e) {
            logger.error("Failed to configure model source from parent: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup ND4J resources before exit.
     */
    private static void cleanupNd4j() {
        try {
            logger.info("Cleaning up ND4J resources...");

            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            Nd4j.getMemoryManager().releaseCurrentContext();
            DifferentialFunctionClassHolder.cleanup();

            try {
                Nd4j.getNativeOps().clearTADCache();
                Nd4j.getNativeOps().clearShapeCache();
            } catch (Exception e) {
                logger.debug("Could not clear native caches", e);
            }

            logger.info("ND4J cleanup completed");

        } catch (Exception e) {
            logger.warn("Error during ND4J cleanup", e);
        }
    }

    /**
     * Check if an exception is OOM-related (either is OOM or wraps OOM or has OOM in message).
     */
    private static boolean isOomRelated(Throwable e) {
        if (e == null) return false;

        // Check if it's an OutOfMemoryError
        if (e instanceof OutOfMemoryError) return true;

        // Check message for OOM indicators
        String msg = e.getMessage();
        if (msg != null) {
            String upper = msg.toUpperCase();
            if (upper.contains("OUTOFMEMORY") ||
                upper.contains("OUT OF MEMORY") ||
                upper.contains("JAVA HEAP SPACE") ||
                upper.contains("GC OVERHEAD LIMIT") ||
                upper.contains("HEAP EXHAUSTED")) {
                return true;
            }
        }

        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof OutOfMemoryError) return true;
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                String upper = causeMsg.toUpperCase();
                if (upper.contains("OUTOFMEMORY") || upper.contains("OUT OF MEMORY")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }

    private static String getStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        if (e.getCause() != null) {
            sb.append("Caused by: ").append(e.getCause().toString()).append("\n");
        }
        return sb.toString();
    }
}
