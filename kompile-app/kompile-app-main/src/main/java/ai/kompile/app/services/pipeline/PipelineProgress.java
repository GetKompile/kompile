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

import java.util.List;
import java.util.ArrayList;

/**
 * Progress update from the ingest pipeline with per-worker status.
 *
 * <h2>Progress Calculation</h2>
 * <p>
 * The overall progress is calculated based on total work units across all phases.
 * Each chunk passes through 3 stages: chunking → embedding → indexing.
 * The progress percentage reflects the proportion of total work completed.
 * </p>
 *
 * <pre>
 * Total Work = chunksCreated + chunksEmbedded + chunksIndexed
 * Max Work   = expectedChunks * 3 (once chunksCreated is known)
 * Progress   = (Total Work / Max Work) * 100
 * </pre>
 */
public record PipelineProgress(
        String phase,
        int percent,
        String message,
        int documentsProcessed,
        int chunksCreated,
        int chunksEmbedded,
        int chunksIndexed,
        long tokensProcessed,  // Total tokens processed during tokenization
        double chunksPerSecond,
        double memoryUsagePercent,
        int chunkingWorkers,
        int embeddingWorkers,
        List<WorkerStatus> workerStatuses,
        QueueStatus queueStatus,
        EmbeddingBatchMetrics currentEmbeddingBatch,
        List<EmbeddingBatchMetrics> batchHistory  // Last N completed batches (most recent first)
) {

    /**
     * Calculates the true overall progress percentage based on work units COMPLETED.
     *
     * <p>Progress is calculated based on actual completed work at each stage.
     * A chunk only contributes to progress when it COMPLETES each stage - queued
     * items don't count.</p>
     *
     * <p>Two modes of operation:</p>
     * <ul>
     *   <li><b>Separate embedding:</b> Pipeline has embedding model, embedding happens
     *       before indexing. Progress = chunking (10%) + embedding (50%) + indexing (40%)</li>
     *   <li><b>Passthrough mode:</b> No embedding model in pipeline, indexer handles
     *       embedding. chunksEmbedded stays 0, progress = chunking (10%) + indexing (90%)</li>
     * </ul>
     *
     * @return Progress percentage from 0-100
     */
    public int calculateOverallProgress() {
        if (chunksCreated <= 0) {
            // Still loading/chunking - show early progress based on docs processed
            return documentsProcessed > 0 ? Math.min(5, documentsProcessed) : 0;
        }

        // Detect passthrough mode: if chunksEmbedded is 0 but chunksIndexed > 0,
        // then embedding is being done by the indexer (passthrough mode)
        boolean isPassthroughMode = (chunksEmbedded == 0 && chunksIndexed > 0);

        if (isPassthroughMode) {
            // Passthrough mode: chunking (10%) + indexing (90%)
            // In this mode, indexer handles both embedding and indexing
            final double CHUNKING_WEIGHT = 0.10;
            final double INDEXING_WEIGHT = 0.90;

            double chunkingProgress = 1.0; // Chunking complete once we know count
            double indexingProgress = (double) chunksIndexed / chunksCreated;

            double overallProgress = (chunkingProgress * CHUNKING_WEIGHT) +
                                     (indexingProgress * INDEXING_WEIGHT);

            return Math.min(100, Math.max(0, (int) (overallProgress * 100)));
        } else {
            // Separate embedding mode: chunking (10%) + embedding (50%) + indexing (40%)
            final double CHUNKING_WEIGHT = 0.10;
            final double EMBEDDING_WEIGHT = 0.50;
            final double INDEXING_WEIGHT = 0.40;

            double chunkingProgress = 1.0; // Chunking complete once we know count
            double embeddingProgress = (double) chunksEmbedded / chunksCreated;
            double indexingProgress = (double) chunksIndexed / chunksCreated;

            double overallProgress = (chunkingProgress * CHUNKING_WEIGHT) +
                                     (embeddingProgress * EMBEDDING_WEIGHT) +
                                     (indexingProgress * INDEXING_WEIGHT);

            return Math.min(100, Math.max(0, (int) (overallProgress * 100)));
        }
    }

    /**
     * Calculates progress based purely on the final output (indexed chunks).
     * This gives a more accurate "true completion" percentage.
     *
     * @return Progress percentage from 0-100 based on indexed chunks
     */
    public int calculateCompletionProgress() {
        if (chunksCreated <= 0) {
            return 0;
        }
        return Math.min(100, (int) ((double) chunksIndexed / chunksCreated * 100));
    }

    /**
     * Returns phase-specific progress (0-100 for current phase only).
     */
    public int getPhaseProgress() {
        if (chunksCreated <= 0) {
            return 0;
        }

        return switch (phase.toLowerCase()) {
            case "chunking" -> 100; // If we're past chunking, it's done
            case "embedding" -> chunksCreated > 0 ? (int) ((double) chunksEmbedded / chunksCreated * 100) : 0;
            case "indexing" -> chunksCreated > 0 ? (int) ((double) chunksIndexed / chunksCreated * 100) : 0;
            case "complete", "completed" -> 100;
            default -> percent;
        };
    }
    /**
     * Status of an individual worker.
     */
    public record WorkerStatus(
            int workerId,
            String workerType,  // "chunking", "embedding", "indexing"
            String status,      // "idle", "processing", "waiting", "complete"
            int itemsProcessed,
            int currentBatchSize,
            double throughput,  // items per second
            String currentItem  // e.g., document name or chunk ID
    ) {
        public static WorkerStatus idle(int id, String type) {
            return new WorkerStatus(id, type, "idle", 0, 0, 0, null);
        }

        public static WorkerStatus processing(int id, String type, int processed, int batchSize, double throughput, String item) {
            return new WorkerStatus(id, type, "processing", processed, batchSize, throughput, item);
        }

        public static WorkerStatus waiting(int id, String type, int processed) {
            return new WorkerStatus(id, type, "waiting", processed, 0, 0, null);
        }

        /**
         * Creates a waiting status with cumulative throughput.
         */
        public static WorkerStatus waitingWithThroughput(int id, String type, int processed, double throughput) {
            return new WorkerStatus(id, type, "waiting", processed, 0, throughput, null);
        }

        public static WorkerStatus complete(int id, String type, int processed, double throughput) {
            return new WorkerStatus(id, type, "complete", processed, 0, throughput, null);
        }
    }

    /**
     * Status of inter-stage queues.
     */
    public record QueueStatus(
            int chunkQueueSize,
            int chunkQueueCapacity,
            int embeddedQueueSize,
            int embeddedQueueCapacity
    ) {
        public double chunkQueueUtilization() {
            return chunkQueueCapacity > 0 ? (chunkQueueSize * 100.0 / chunkQueueCapacity) : 0;
        }

        public double embeddedQueueUtilization() {
            return embeddedQueueCapacity > 0 ? (embeddedQueueSize * 100.0 / embeddedQueueCapacity) : 0;
        }
    }

    /**
     * Metrics for the current embedding batch being processed.
     * Provides detailed insight into the embedding inference process.
     */
    public record EmbeddingBatchMetrics(
            // Batch identification
            int batchNumber,           // Current batch number (1-indexed)
            int totalBatches,          // Total expected batches (estimate)

            // Input metrics
            int inputTexts,            // Number of text chunks in this batch
            int inputTokens,           // Total tokens in this batch (if available)
            int maxSequenceLength,     // Max sequence length after padding
            int avgSequenceLength,     // Average sequence length before padding

            // Output metrics
            int outputVectors,         // Number of embedding vectors produced
            int embeddingDimension,    // Dimension of each embedding vector (e.g., 768, 1024)
            long outputSizeBytes,      // Total size of output embeddings in bytes

            // Timing metrics - coarse grained
            long tokenizationTimeMs,   // Time spent tokenizing this batch
            long inferenceTimeMs,      // Time spent in model inference
            long totalBatchTimeMs,     // Total time for this batch (tokenize + inference + overhead)

            // Detailed timing breakdown - fine grained (from encoder)
            long paddingTimeMs,        // Time spent padding sequences
            long tensorCreationTimeMs, // Time spent creating input tensors (INDArray)
            long forwardPassTimeMs,    // Time spent in actual neural network forward pass
            long extractionTimeMs,     // Time spent extracting embeddings from output

            // Heartbeat/liveness tracking
            String currentStep,        // Current inference step: TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTION
            int heartbeatSeconds,      // Seconds elapsed since batch started (for liveness)
            long stepStartTimeMs,      // When current step started (epoch ms)
            boolean isStuck,           // True if step is taking unusually long (>30s)

            // Throughput metrics
            double tokensPerSecond,    // Tokenization throughput
            double embeddingsPerSecond,// Inference throughput
            double batchThroughput,    // Overall batch throughput (texts/sec)

            // Model info
            String modelName,          // Name of the embedding model
            String deviceType,         // CPU, CUDA, etc.
            boolean isBatched,         // Whether batched inference is being used

            // ========== DETAILED SOURCE AND SHAPE INFO ==========
            // Source document tracking
            String sourceDocuments,    // Comma-separated list of source document names (truncated if many)
            int sourceDocumentCount,   // Total number of unique source documents in this batch

            // Tensor shape information (as human-readable strings)
            String inputTensorShape,   // e.g., "[32, 512]" for [batch_size, max_seq_length]
            String outputTensorShape,  // e.g., "[32, 768]" for [batch_size, embedding_dim]

            // Actual tensor info from encoder (when available)
            String actualInputShape,   // Actual input tensor shape from encoder
            String actualOutputShape,  // Actual output tensor shape from encoder

            // Processing status
            String statusLevel,        // RUNNING, PROCESSING, SLOW, VERY_SLOW, EXTREMELY_SLOW
            String etaMessage,         // Estimated time remaining message

            // Per-passage token counts
            int[] passageTokenCounts   // Token count for each passage in the batch
    ) {
        public static EmbeddingBatchMetrics empty() {
            return new EmbeddingBatchMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    null, 0, 0, false, 0, 0, 0, null, null, false,
                    null, 0, null, null, null, null, null, null, null);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int batchNumber;
            private int totalBatches;
            private int inputTexts;
            private int inputTokens;
            private int maxSequenceLength;
            private int avgSequenceLength;
            private int outputVectors;
            private int embeddingDimension;
            private long outputSizeBytes;
            private long tokenizationTimeMs;
            private long inferenceTimeMs;
            private long totalBatchTimeMs;
            private long paddingTimeMs;
            private long tensorCreationTimeMs;
            private long forwardPassTimeMs;
            private long extractionTimeMs;
            private String currentStep;
            private int heartbeatSeconds;
            private long stepStartTimeMs;
            private boolean isStuck;
            private double tokensPerSecond;
            private double embeddingsPerSecond;
            private double batchThroughput;
            private String modelName;
            private String deviceType;
            private boolean isBatched;
            // New detailed fields
            private String sourceDocuments;
            private int sourceDocumentCount;
            private String inputTensorShape;
            private String outputTensorShape;
            private String actualInputShape;
            private String actualOutputShape;
            private String statusLevel;
            private String etaMessage;
            private int[] passageTokenCounts;

            public Builder batchNumber(int v) { this.batchNumber = v; return this; }
            public Builder totalBatches(int v) { this.totalBatches = v; return this; }
            public Builder inputTexts(int v) { this.inputTexts = v; return this; }
            public Builder inputTokens(int v) { this.inputTokens = v; return this; }
            public Builder maxSequenceLength(int v) { this.maxSequenceLength = v; return this; }
            public Builder avgSequenceLength(int v) { this.avgSequenceLength = v; return this; }
            public Builder outputVectors(int v) { this.outputVectors = v; return this; }
            public Builder embeddingDimension(int v) { this.embeddingDimension = v; return this; }
            public Builder outputSizeBytes(long v) { this.outputSizeBytes = v; return this; }
            public Builder tokenizationTimeMs(long v) { this.tokenizationTimeMs = v; return this; }
            public Builder inferenceTimeMs(long v) { this.inferenceTimeMs = v; return this; }
            public Builder totalBatchTimeMs(long v) { this.totalBatchTimeMs = v; return this; }
            public Builder paddingTimeMs(long v) { this.paddingTimeMs = v; return this; }
            public Builder tensorCreationTimeMs(long v) { this.tensorCreationTimeMs = v; return this; }
            public Builder forwardPassTimeMs(long v) { this.forwardPassTimeMs = v; return this; }
            public Builder extractionTimeMs(long v) { this.extractionTimeMs = v; return this; }
            public Builder currentStep(String v) { this.currentStep = v; return this; }
            public Builder heartbeatSeconds(int v) { this.heartbeatSeconds = v; return this; }
            public Builder stepStartTimeMs(long v) { this.stepStartTimeMs = v; return this; }
            public Builder isStuck(boolean v) { this.isStuck = v; return this; }
            public Builder tokensPerSecond(double v) { this.tokensPerSecond = v; return this; }
            public Builder embeddingsPerSecond(double v) { this.embeddingsPerSecond = v; return this; }
            public Builder batchThroughput(double v) { this.batchThroughput = v; return this; }
            public Builder modelName(String v) { this.modelName = v; return this; }
            public Builder deviceType(String v) { this.deviceType = v; return this; }
            public Builder isBatched(boolean v) { this.isBatched = v; return this; }
            // New detailed field builders
            public Builder sourceDocuments(String v) { this.sourceDocuments = v; return this; }
            public Builder sourceDocumentCount(int v) { this.sourceDocumentCount = v; return this; }
            public Builder inputTensorShape(String v) { this.inputTensorShape = v; return this; }
            public Builder outputTensorShape(String v) { this.outputTensorShape = v; return this; }
            public Builder actualInputShape(String v) { this.actualInputShape = v; return this; }
            public Builder actualOutputShape(String v) { this.actualOutputShape = v; return this; }
            public Builder statusLevel(String v) { this.statusLevel = v; return this; }
            public Builder etaMessage(String v) { this.etaMessage = v; return this; }
            public Builder passageTokenCounts(int[] v) { this.passageTokenCounts = v; return this; }

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
                        statusLevel, etaMessage, passageTokenCounts
                );
            }
        }
    }

    /**
     * Alias for percent() for backward compatibility.
     */
    public int progressPercent() {
        return percent;
    }

    /**
     * Returns the number of batches completed (chunksIndexed / batch size estimate).
     */
    public int batchesCompleted() {
        return chunksIndexed > 0 ? Math.max(1, chunksIndexed / 50) : 0;
    }

    /**
     * Creates a simple progress update with minimal fields.
     */
    public static PipelineProgress simple(String phase, int percent, String message) {
        return new PipelineProgress(phase, percent, message, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new ArrayList<>(), new QueueStatus(0, 0, 0, 0), null, null);
    }

    /**
     * Builder for creating PipelineProgress instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String phase = "";
        private int percent = 0;
        private String message = "";
        private int documentsProcessed = 0;
        private int chunksCreated = 0;
        private int chunksEmbedded = 0;
        private int chunksIndexed = 0;
        private long tokensProcessed = 0;
        private double chunksPerSecond = 0;
        private double memoryUsagePercent = 0;
        private int chunkingWorkers = 0;
        private int embeddingWorkers = 0;
        private List<WorkerStatus> workerStatuses = new ArrayList<>();
        private QueueStatus queueStatus = new QueueStatus(0, 0, 0, 0);
        private EmbeddingBatchMetrics currentEmbeddingBatch = null;
        private List<EmbeddingBatchMetrics> batchHistory = null;

        public Builder phase(String phase) { this.phase = phase; return this; }
        public Builder percent(int percent) { this.percent = percent; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder documentsProcessed(int v) { this.documentsProcessed = v; return this; }
        public Builder chunksCreated(int v) { this.chunksCreated = v; return this; }
        public Builder chunksEmbedded(int v) { this.chunksEmbedded = v; return this; }
        public Builder chunksIndexed(int v) { this.chunksIndexed = v; return this; }
        public Builder tokensProcessed(long v) { this.tokensProcessed = v; return this; }
        public Builder chunksPerSecond(double v) { this.chunksPerSecond = v; return this; }
        public Builder memoryUsagePercent(double v) { this.memoryUsagePercent = v; return this; }
        public Builder chunkingWorkers(int v) { this.chunkingWorkers = v; return this; }
        public Builder embeddingWorkers(int v) { this.embeddingWorkers = v; return this; }
        public Builder workerStatuses(List<WorkerStatus> v) { this.workerStatuses = v; return this; }
        public Builder queueStatus(QueueStatus v) { this.queueStatus = v; return this; }
        public Builder currentEmbeddingBatch(EmbeddingBatchMetrics v) { this.currentEmbeddingBatch = v; return this; }
        public Builder batchHistory(List<EmbeddingBatchMetrics> v) { this.batchHistory = v; return this; }

        public PipelineProgress build() {
            return new PipelineProgress(phase, percent, message, documentsProcessed,
                    chunksCreated, chunksEmbedded, chunksIndexed, tokensProcessed,
                    chunksPerSecond, memoryUsagePercent, chunkingWorkers, embeddingWorkers,
                    workerStatuses, queueStatus, currentEmbeddingBatch, batchHistory);
        }
    }
}
