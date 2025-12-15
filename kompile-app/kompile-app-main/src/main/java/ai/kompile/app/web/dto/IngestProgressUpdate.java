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

package ai.kompile.app.web.dto;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO for real-time document ingest progress updates sent via WebSocket.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestProgressUpdate(
        String taskId,
        String fileName,
        IngestPhase phase,
        IngestStatus status,
        int progressPercent,
        String currentStep,
        String message,
        IngestStats stats,
        String errorMessage,
        Instant timestamp
) {
    /**
     * The current phase of the ingest pipeline.
     */
    public enum IngestPhase {
        QUEUED,
        UPLOADING,
        LOADING,
        /** Converting rich format to plain text */
        CONVERTING,
        CHUNKING,
        EMBEDDING,
        INDEXING,
        COMPLETED,
        FAILED
    }

    /**
     * Overall status of the ingest task.
     */
    public enum IngestStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Status of an individual pipeline worker.
     */
    public record WorkerStatusDto(
            int workerId,
            String workerType,  // "embedding", "indexing"
            String status,      // "idle", "processing", "waiting", "complete"
            int itemsProcessed,
            int currentBatchSize,
            double throughput,
            String currentItem
    ) {}

    /**
     * Status of inter-stage queues.
     */
    public record QueueStatusDto(
            int chunkQueueSize,
            int chunkQueueCapacity,
            int embeddedQueueSize,
            int embeddedQueueCapacity,
            double chunkQueueUtilization,
            double embeddedQueueUtilization
    ) {}

    /**
     * Runtime information about the subprocess executing the ingest pipeline.
     * Provides visibility into the isolated JVM process.
     */
    public record SubprocessRuntimeInfo(
            // Process identification
            Long pid,                       // Process ID
            Long uptimeMs,                  // Time since subprocess started
            String processMode,             // "SUBPROCESS" or "IN_PROCESS"

            // JVM info
            String javaVersion,             // e.g., "17.0.2"
            String javaVendor,              // e.g., "Eclipse Adoptium"
            String javaHome,                // Path to JDK
            String vmName,                  // e.g., "OpenJDK 64-Bit Server VM"
            String vmVersion,               // e.g., "17.0.2+8"

            // Memory configuration
            Long heapMaxBytes,              // -Xmx value
            Long heapUsedBytes,             // Current heap usage
            Long heapFreeBytes,             // Available heap
            Double heapUsagePercent,        // Heap usage percentage
            Long nonHeapUsedBytes,          // Metaspace etc.

            // GC info
            Long gcCount,                   // Total GC invocations
            Long gcTimeMs,                  // Total GC time

            // Process resources
            Integer availableProcessors,    // CPU cores available
            String workingDirectory,        // CWD of subprocess
            String tempDirectory,           // java.io.tmpdir

            // Command line
            String commandLine,             // Reconstructed command line
            List<String> jvmArguments,      // JVM flags (e.g., -Xmx, -D...)
            List<String> inputFiles,        // Files being processed

            // Environment variables (filtered for relevance)
            String ndj4Backend,             // ND4J_BACKEND
            String cudaVisibleDevices,      // CUDA_VISIBLE_DEVICES
            String ompNumThreads,           // OMP_NUM_THREADS
            String mklNumThreads,           // MKL_NUM_THREADS

            // ND4J environment configuration snapshots
            Nd4jEnvironmentConfig nd4jEnvironmentInvoked,
            Nd4jEnvironmentConfig nd4jEnvironmentUsed,

            // Native library info
            String nd4jBackend,             // Detected ND4J backend (CPU/CUDA)
            String blasVendor,              // BLAS vendor (OpenBLAS, MKL, etc.)
            Boolean cudaAvailable,          // Whether CUDA is available
            String cudaVersion,             // CUDA version if available

            // Model info
            String embeddingModelId,        // Model being used
            String embeddingModelPath,      // Path to model file
            Integer embeddingDimension      // Output dimension
    ) {
        public static SubprocessRuntimeInfo empty() {
            return new SubprocessRuntimeInfo(
                    null, null, "UNKNOWN",
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null,
                    null, null, null,
                    null, List.of(), List.of(),
                    null, null, null, null,
                    null, null,
                    null, null, null, null,
                    null, null, null
            );
        }
    }

    /**
     * Metrics for the current embedding batch being processed.
     * Provides detailed insight into the embedding inference process.
     */
    public record EmbeddingBatchMetrics(
            // Batch identification
            Integer batchNumber,           // Current batch number (1-indexed)
            Integer totalBatches,          // Total expected batches

            // Input metrics
            Integer inputTexts,            // Number of text chunks in this batch
            Integer inputTokens,           // Total tokens in this batch (if available)
            Integer maxSequenceLength,     // Max sequence length after padding
            Integer avgSequenceLength,     // Average sequence length before padding

            // Output metrics
            Integer outputVectors,         // Number of embedding vectors produced
            Integer embeddingDimension,    // Dimension of each embedding vector (e.g., 768, 1024)
            Long outputSizeBytes,          // Total size of output embeddings in bytes

            // Timing metrics - coarse grained
            Long tokenizationTimeMs,       // Time spent tokenizing this batch
            Long inferenceTimeMs,          // Time spent in model inference
            Long totalBatchTimeMs,         // Total time for this batch (tokenize + inference + overhead)

            // Detailed timing breakdown - fine grained (from encoder)
            Long paddingTimeMs,            // Time spent padding sequences
            Long tensorCreationTimeMs,     // Time spent creating input tensors (INDArray)
            Long forwardPassTimeMs,        // Time spent in actual neural network forward pass
            Long extractionTimeMs,         // Time spent extracting embeddings from output

            // Heartbeat/liveness tracking
            String currentStep,            // Current inference step: TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTION
            Integer heartbeatSeconds,      // Seconds elapsed since batch started (for liveness)
            Long stepStartTimeMs,          // When current step started (epoch ms)
            Boolean isStuck,               // True if step is taking unusually long (>30s)

            // Throughput metrics
            Double tokensPerSecond,        // Tokenization throughput
            Double embeddingsPerSecond,    // Inference throughput
            Double batchThroughput,        // Overall batch throughput (texts/sec)

            // Model info
            String modelName,              // Name of the embedding model
            String deviceType,             // CPU, CUDA, etc.
            Boolean isBatched,             // Whether batched inference is being used

            // ========== DETAILED SOURCE AND SHAPE INFO ==========
            // Source document tracking
            String sourceDocuments,        // Comma-separated list of source document names (truncated if many)
            Integer sourceDocumentCount,   // Total number of unique source documents in this batch

            // Tensor shape information (as human-readable strings)
            String inputTensorShape,       // e.g., "[32, 512]" for [batch_size, max_seq_length]
            String outputTensorShape,      // e.g., "[32, 768]" for [batch_size, embedding_dim]

            // Actual tensor info from encoder (when available)
            String actualInputShape,       // Actual input tensor shape from encoder
            String actualOutputShape,      // Actual output tensor shape from encoder

            // Processing status
            String statusLevel,            // RUNNING, PROCESSING, SLOW, VERY_SLOW, EXTREMELY_SLOW
            String etaMessage              // Estimated time remaining message
    ) {
        public static EmbeddingBatchMetrics empty() {
            return new EmbeddingBatchMetrics(
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null
            );
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Integer batchNumber;
            private Integer totalBatches;
            private Integer inputTexts;
            private Integer inputTokens;
            private Integer maxSequenceLength;
            private Integer avgSequenceLength;
            private Integer outputVectors;
            private Integer embeddingDimension;
            private Long outputSizeBytes;
            private Long tokenizationTimeMs;
            private Long inferenceTimeMs;
            private Long totalBatchTimeMs;
            private Long paddingTimeMs;
            private Long tensorCreationTimeMs;
            private Long forwardPassTimeMs;
            private Long extractionTimeMs;
            private String currentStep;
            private Integer heartbeatSeconds;
            private Long stepStartTimeMs;
            private Boolean isStuck;
            private Double tokensPerSecond;
            private Double embeddingsPerSecond;
            private Double batchThroughput;
            private String modelName;
            private String deviceType;
            private Boolean isBatched;
            // New detailed fields
            private String sourceDocuments;
            private Integer sourceDocumentCount;
            private String inputTensorShape;
            private String outputTensorShape;
            private String actualInputShape;
            private String actualOutputShape;
            private String statusLevel;
            private String etaMessage;

            public Builder batchNumber(Integer v) { this.batchNumber = v; return this; }
            public Builder totalBatches(Integer v) { this.totalBatches = v; return this; }
            public Builder inputTexts(Integer v) { this.inputTexts = v; return this; }
            public Builder inputTokens(Integer v) { this.inputTokens = v; return this; }
            public Builder maxSequenceLength(Integer v) { this.maxSequenceLength = v; return this; }
            public Builder avgSequenceLength(Integer v) { this.avgSequenceLength = v; return this; }
            public Builder outputVectors(Integer v) { this.outputVectors = v; return this; }
            public Builder embeddingDimension(Integer v) { this.embeddingDimension = v; return this; }
            public Builder outputSizeBytes(Long v) { this.outputSizeBytes = v; return this; }
            public Builder tokenizationTimeMs(Long v) { this.tokenizationTimeMs = v; return this; }
            public Builder inferenceTimeMs(Long v) { this.inferenceTimeMs = v; return this; }
            public Builder totalBatchTimeMs(Long v) { this.totalBatchTimeMs = v; return this; }
            public Builder paddingTimeMs(Long v) { this.paddingTimeMs = v; return this; }
            public Builder tensorCreationTimeMs(Long v) { this.tensorCreationTimeMs = v; return this; }
            public Builder forwardPassTimeMs(Long v) { this.forwardPassTimeMs = v; return this; }
            public Builder extractionTimeMs(Long v) { this.extractionTimeMs = v; return this; }
            public Builder currentStep(String v) { this.currentStep = v; return this; }
            public Builder heartbeatSeconds(Integer v) { this.heartbeatSeconds = v; return this; }
            public Builder stepStartTimeMs(Long v) { this.stepStartTimeMs = v; return this; }
            public Builder isStuck(Boolean v) { this.isStuck = v; return this; }
            public Builder tokensPerSecond(Double v) { this.tokensPerSecond = v; return this; }
            public Builder embeddingsPerSecond(Double v) { this.embeddingsPerSecond = v; return this; }
            public Builder batchThroughput(Double v) { this.batchThroughput = v; return this; }
            public Builder modelName(String v) { this.modelName = v; return this; }
            public Builder deviceType(String v) { this.deviceType = v; return this; }
            public Builder isBatched(Boolean v) { this.isBatched = v; return this; }
            // New detailed field builders
            public Builder sourceDocuments(String v) { this.sourceDocuments = v; return this; }
            public Builder sourceDocumentCount(Integer v) { this.sourceDocumentCount = v; return this; }
            public Builder inputTensorShape(String v) { this.inputTensorShape = v; return this; }
            public Builder outputTensorShape(String v) { this.outputTensorShape = v; return this; }
            public Builder actualInputShape(String v) { this.actualInputShape = v; return this; }
            public Builder actualOutputShape(String v) { this.actualOutputShape = v; return this; }
            public Builder statusLevel(String v) { this.statusLevel = v; return this; }
            public Builder etaMessage(String v) { this.etaMessage = v; return this; }

            public EmbeddingBatchMetrics build() {
                return new EmbeddingBatchMetrics(
                        batchNumber, totalBatches, inputTexts, inputTokens,
                        maxSequenceLength, avgSequenceLength, outputVectors,
                        embeddingDimension, outputSizeBytes, tokenizationTimeMs,
                        inferenceTimeMs, totalBatchTimeMs, paddingTimeMs,
                        tensorCreationTimeMs, forwardPassTimeMs, extractionTimeMs,
                        currentStep, heartbeatSeconds, stepStartTimeMs, isStuck,
                        tokensPerSecond, embeddingsPerSecond, batchThroughput,
                        modelName, deviceType, isBatched,
                        sourceDocuments, sourceDocumentCount,
                        inputTensorShape, outputTensorShape,
                        actualInputShape, actualOutputShape,
                        statusLevel, etaMessage
                );
            }
        }
    }

    /**
     * Statistics about the ingest process.
     */
    public record IngestStats(
            Integer documentsLoaded,
            Integer chunksCreated,
            Integer chunksEmbedded,
            Integer chunksIndexed,      // Number of chunks indexed (used by frontend progress bar)
            Integer documentsIndexed,   // Legacy field - in subprocess mode, this also holds chunks indexed
            Long totalProcessingTimeMs,
            String loaderUsed,
            String chunkerUsed,
            List<String> processedDocumentIds,
            // Enhanced timing breakdown
            Long loadingTimeMs,
            Long conversionTimeMs,
            Long chunkingTimeMs,
            Long embeddingTimeMs,
            Long indexingTimeMs,
            // Batch processing details
            Integer currentBatch,
            Integer totalBatches,
            Integer batchSize,
            Boolean parallelProcessing,
            Integer workerThreads,
            // Throughput metrics
            Double chunksPerSecond,
            Double docsPerSecond,
            // Memory info
            Double memoryUsagePercent,
            String memoryStatus,
            // ========== PIPELINE ARCHITECTURE DETAILS ==========
            // Extraction pipeline info
            Integer extractionThreads,
            Integer extractionQueueSize,
            Double extractionRate,        // docs/sec
            Long extractionTimeMs,
            // Chunking pipeline info
            Integer chunkingThreads,
            Integer chunkingQueueSize,
            // Embedding pipeline info (pipelined tokenization + inference)
            Boolean pipelinedEmbedding,
            Integer tokenizationThreads,
            Integer tokenizationQueueSize,
            Integer embeddingQueueDepth,
            Double tokenizationRate,      // tokens/sec
            Double inferenceRate,         // embeddings/sec
            // Indexing pipeline info (Lucene batch mode)
            Boolean luceneBatchMode,
            Double ramBufferSizeMb,
            Integer mergeThreads,
            // Active stage indicators
            String activeStage,           // CHUNKING, TOKENIZING, EMBEDDING, INDEXING
            String pipelineStatus,        // IDLE, WARMING_UP, PROCESSING, DRAINING, COMPLETE
            // Per-worker status
            List<WorkerStatusDto> workerStatuses,
            QueueStatusDto queueStatus,
            // ========== EMBEDDING INFERENCE METRICS ==========
            // Current embedding batch details
            EmbeddingBatchMetrics currentEmbeddingBatch,
            // ========== SUBPROCESS RUNTIME INFO ==========
            // Runtime details when running in subprocess mode
            SubprocessRuntimeInfo subprocessRuntimeInfo
    ) {
        public static IngestStats empty() {
            return new IngestStats(0, 0, 0, 0, 0, 0L, null, null, List.of(),
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    // Pipeline fields - extraction
                    null, null, null, null,
                    // Pipeline fields - chunking, embedding, indexing
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    // Worker statuses
                    List.of(), null,
                    // Embedding batch metrics
                    null,
                    // Subprocess runtime info
                    null);
        }

        /**
         * Builder for creating IngestStats incrementally.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Integer documentsLoaded = 0;
            private Integer chunksCreated = 0;
            private Integer chunksEmbedded = 0;
            private Integer chunksIndexed = 0;
            private Integer documentsIndexed = 0;
            private Long totalProcessingTimeMs = 0L;
            private String loaderUsed;
            private String chunkerUsed;
            private List<String> processedDocumentIds = List.of();
            private Long loadingTimeMs;
            private Long conversionTimeMs;
            private Long chunkingTimeMs;
            private Long embeddingTimeMs;
            private Long indexingTimeMs;
            private Integer currentBatch;
            private Integer totalBatches;
            private Integer batchSize;
            private Boolean parallelProcessing;
            private Integer workerThreads;
            private Double chunksPerSecond;
            private Double docsPerSecond;
            private Double memoryUsagePercent;
            private String memoryStatus;
            // Pipeline fields - extraction
            private Integer extractionThreads;
            private Integer extractionQueueSize;
            private Double extractionRate;
            private Long extractionTimeMs;
            // Pipeline fields - chunking
            private Integer chunkingThreads;
            private Integer chunkingQueueSize;
            private Boolean pipelinedEmbedding;
            private Integer tokenizationThreads;
            private Integer tokenizationQueueSize;
            private Integer embeddingQueueDepth;
            private Double tokenizationRate;
            private Double inferenceRate;
            private Boolean luceneBatchMode;
            private Double ramBufferSizeMb;
            private Integer mergeThreads;
            private String activeStage;
            private String pipelineStatus;
            private List<WorkerStatusDto> workerStatuses = List.of();
            private QueueStatusDto queueStatus;
            private EmbeddingBatchMetrics currentEmbeddingBatch;
            private SubprocessRuntimeInfo subprocessRuntimeInfo;

            public Builder documentsLoaded(Integer val) { this.documentsLoaded = val; return this; }
            public Builder chunksCreated(Integer val) { this.chunksCreated = val; return this; }
            public Builder chunksEmbedded(Integer val) { this.chunksEmbedded = val; return this; }
            public Builder chunksIndexed(Integer val) { this.chunksIndexed = val; return this; }
            public Builder documentsIndexed(Integer val) { this.documentsIndexed = val; return this; }
            public Builder totalProcessingTimeMs(Long val) { this.totalProcessingTimeMs = val; return this; }
            public Builder loaderUsed(String val) { this.loaderUsed = val; return this; }
            public Builder chunkerUsed(String val) { this.chunkerUsed = val; return this; }
            public Builder processedDocumentIds(List<String> val) { this.processedDocumentIds = val; return this; }
            public Builder loadingTimeMs(Long val) { this.loadingTimeMs = val; return this; }
            public Builder conversionTimeMs(Long val) { this.conversionTimeMs = val; return this; }
            public Builder chunkingTimeMs(Long val) { this.chunkingTimeMs = val; return this; }
            public Builder embeddingTimeMs(Long val) { this.embeddingTimeMs = val; return this; }
            public Builder indexingTimeMs(Long val) { this.indexingTimeMs = val; return this; }
            public Builder currentBatch(Integer val) { this.currentBatch = val; return this; }
            public Builder totalBatches(Integer val) { this.totalBatches = val; return this; }
            public Builder batchSize(Integer val) { this.batchSize = val; return this; }
            public Builder parallelProcessing(Boolean val) { this.parallelProcessing = val; return this; }
            public Builder workerThreads(Integer val) { this.workerThreads = val; return this; }
            public Builder chunksPerSecond(Double val) { this.chunksPerSecond = val; return this; }
            public Builder docsPerSecond(Double val) { this.docsPerSecond = val; return this; }
            public Builder memoryUsagePercent(Double val) { this.memoryUsagePercent = val; return this; }
            public Builder memoryStatus(String val) { this.memoryStatus = val; return this; }
            // Pipeline field builders - extraction
            public Builder extractionThreads(Integer val) { this.extractionThreads = val; return this; }
            public Builder extractionQueueSize(Integer val) { this.extractionQueueSize = val; return this; }
            public Builder extractionRate(Double val) { this.extractionRate = val; return this; }
            public Builder extractionTimeMs(Long val) { this.extractionTimeMs = val; return this; }
            // Pipeline field builders - chunking
            public Builder chunkingThreads(Integer val) { this.chunkingThreads = val; return this; }
            public Builder chunkingQueueSize(Integer val) { this.chunkingQueueSize = val; return this; }
            public Builder pipelinedEmbedding(Boolean val) { this.pipelinedEmbedding = val; return this; }
            public Builder tokenizationThreads(Integer val) { this.tokenizationThreads = val; return this; }
            public Builder tokenizationQueueSize(Integer val) { this.tokenizationQueueSize = val; return this; }
            public Builder embeddingQueueDepth(Integer val) { this.embeddingQueueDepth = val; return this; }
            public Builder tokenizationRate(Double val) { this.tokenizationRate = val; return this; }
            public Builder inferenceRate(Double val) { this.inferenceRate = val; return this; }
            public Builder luceneBatchMode(Boolean val) { this.luceneBatchMode = val; return this; }
            public Builder ramBufferSizeMb(Double val) { this.ramBufferSizeMb = val; return this; }
            public Builder mergeThreads(Integer val) { this.mergeThreads = val; return this; }
            public Builder activeStage(String val) { this.activeStage = val; return this; }
            public Builder pipelineStatus(String val) { this.pipelineStatus = val; return this; }
            public Builder workerStatuses(List<WorkerStatusDto> val) { this.workerStatuses = val; return this; }
            public Builder queueStatus(QueueStatusDto val) { this.queueStatus = val; return this; }
            public Builder currentEmbeddingBatch(EmbeddingBatchMetrics val) { this.currentEmbeddingBatch = val; return this; }
            public Builder subprocessRuntimeInfo(SubprocessRuntimeInfo val) { this.subprocessRuntimeInfo = val; return this; }

            public IngestStats build() {
                return new IngestStats(documentsLoaded, chunksCreated, chunksEmbedded,
                        chunksIndexed, documentsIndexed,
                        totalProcessingTimeMs, loaderUsed, chunkerUsed, processedDocumentIds,
                        loadingTimeMs, conversionTimeMs, chunkingTimeMs, embeddingTimeMs, indexingTimeMs,
                        currentBatch, totalBatches, batchSize, parallelProcessing, workerThreads,
                        chunksPerSecond, docsPerSecond, memoryUsagePercent, memoryStatus,
                        // Extraction fields
                        extractionThreads, extractionQueueSize, extractionRate, extractionTimeMs,
                        // Remaining pipeline fields
                        chunkingThreads, chunkingQueueSize, pipelinedEmbedding, tokenizationThreads,
                        tokenizationQueueSize, embeddingQueueDepth, tokenizationRate, inferenceRate,
                        luceneBatchMode, ramBufferSizeMb, mergeThreads, activeStage, pipelineStatus,
                        // Worker statuses
                        workerStatuses, queueStatus,
                        // Embedding batch metrics
                        currentEmbeddingBatch,
                        // Subprocess runtime info
                        subprocessRuntimeInfo);
            }
        }
    }

    /**
     * Creates an initial queued update.
     */
    public static IngestProgressUpdate queued(String taskId, String fileName) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                IngestPhase.QUEUED,
                IngestStatus.PENDING,
                0,
                "Queued for processing",
                "Document upload queued",
                IngestStats.empty(),
                null,
                Instant.now()
        );
    }

    /**
     * Creates a progress update for a specific phase.
     */
    public static IngestProgressUpdate progress(String taskId, String fileName, IngestPhase phase,
                                                  int progressPercent, String currentStep, String message,
                                                  IngestStats stats) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                phase,
                IngestStatus.IN_PROGRESS,
                progressPercent,
                currentStep,
                message,
                stats,
                null,
                Instant.now()
        );
    }

    /**
     * Creates a completion update.
     */
    public static IngestProgressUpdate completed(String taskId, String fileName, IngestStats stats) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                IngestPhase.COMPLETED,
                IngestStatus.COMPLETED,
                100,
                "Complete",
                "Document successfully processed and indexed",
                stats,
                null,
                Instant.now()
        );
    }

    /**
     * Creates a failure update.
     */
    public static IngestProgressUpdate failed(String taskId, String fileName, IngestPhase failedPhase,
                                                String errorMessage, IngestStats stats) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                failedPhase,
                IngestStatus.FAILED,
                -1,
                "Failed",
                "Document processing failed",
                stats,
                errorMessage,
                Instant.now()
        );
    }

    /**
     * Creates a cancellation update due to memory pressure.
     */
    public static IngestProgressUpdate memoryPressure(String taskId, String fileName, String message) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                IngestPhase.FAILED,
                IngestStatus.CANCELLED,
                -1,
                "Stopped due to memory pressure",
                message,
                IngestStats.empty(),
                "Job stopped: " + message,
                Instant.now()
        );
    }

    /**
     * Creates a cancellation update with stats (for jobs that partially completed).
     */
    public static IngestProgressUpdate cancelled(String taskId, String fileName, IngestPhase currentPhase,
                                                   String reason, IngestStats stats) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                currentPhase,
                IngestStatus.CANCELLED,
                -1,
                "Cancelled",
                reason,
                stats,
                "Job cancelled: " + reason,
                Instant.now()
        );
    }

    /**
     * Creates an update for a job forcibly killed due to memory exceeding kill threshold.
     * This is a critical system event indicating emergency memory protection.
     */
    public static IngestProgressUpdate memoryKilled(String taskId, String fileName, String message) {
        return new IngestProgressUpdate(
                taskId,
                fileName,
                IngestPhase.FAILED,
                IngestStatus.FAILED,
                -1,
                "MEMORY KILLED",
                message,
                IngestStats.empty(),
                "CRITICAL: " + message,
                Instant.now()
        );
    }

    /**
     * A single log entry from the subprocess.
     * Streamed via WebSocket to /topic/ingest/{taskId}/logs
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestLogEntry(
            String taskId,
            String level,           // DEBUG, INFO, WARN, ERROR
            String source,          // STDOUT, STDERR, SYSTEM
            String message,
            String logger,          // Logger name (e.g., class name)
            Instant timestamp,
            Long sequenceNumber     // For ordering logs
    ) {
        public static IngestLogEntry stdout(String taskId, String message, long seq) {
            return new IngestLogEntry(taskId, "INFO", "STDOUT", message, null, Instant.now(), seq);
        }

        public static IngestLogEntry stderr(String taskId, String message, long seq) {
            // Detect log level from message content
            String level = "INFO";
            if (message.contains("ERROR") || message.contains("Exception") || message.contains("FATAL")) {
                level = "ERROR";
            } else if (message.contains("WARN")) {
                level = "WARN";
            } else if (message.contains("DEBUG") || message.contains("TRACE")) {
                level = "DEBUG";
            }
            return new IngestLogEntry(taskId, level, "STDERR", message, null, Instant.now(), seq);
        }

        public static IngestLogEntry system(String taskId, String level, String message, long seq) {
            return new IngestLogEntry(taskId, level, "SYSTEM", message, null, Instant.now(), seq);
        }

        public static IngestLogEntry error(String taskId, String message, long seq) {
            return new IngestLogEntry(taskId, "ERROR", "STDERR", message, null, Instant.now(), seq);
        }
    }
}
