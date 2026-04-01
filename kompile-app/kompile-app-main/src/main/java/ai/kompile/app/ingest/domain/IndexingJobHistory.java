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

package ai.kompile.app.ingest.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing the complete history of an indexing job.
 * This provides a summary view with environment, parameters, and status information,
 * complementing the detailed event log in IngestEvent.
 */
@Entity
@Table(name = "indexing_job_history", indexes = {
    @Index(name = "idx_job_history_task_id", columnList = "taskId", unique = true),
    @Index(name = "idx_job_history_status", columnList = "status"),
    @Index(name = "idx_job_history_start_time", columnList = "startTime"),
    @Index(name = "idx_job_history_file_name", columnList = "fileName")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexingJobHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    // ===================== JOB IDENTIFICATION =====================

    /**
     * Unique task ID for this indexing job.
     */
    @Column(nullable = false, length = 64, unique = true)
    private String taskId;

    /**
     * The file or source name being processed.
     */
    @Column(nullable = false, length = 512)
    private String fileName;

    /**
     * Original file size in bytes.
     */
    @Column
    private Long fileSizeBytes;

    /**
     * MIME type or content type of the source.
     */
    @Column(length = 128)
    private String contentType;

    // ===================== JOB STATUS =====================

    /**
     * Overall job status.
     */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    /**
     * The last phase that was completed or in progress when the job ended.
     */
    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    private IngestEvent.IngestPhase lastPhase;

    /**
     * If the job failed, which phase did it fail in.
     */
    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    private IngestEvent.IngestPhase failedPhase;

    /**
     * Progress percentage when job stopped (0-100).
     */
    @Column
    private Integer progressPercent;

    // ===================== TIMING =====================

    /**
     * When the job was queued/started.
     */
    @Column(nullable = false)
    private Instant startTime;

    /**
     * When the job completed, failed, or was cancelled.
     */
    @Column
    private Instant endTime;

    /**
     * Total duration in milliseconds.
     */
    @Column
    private Long totalDurationMs;

    /**
     * Time spent in loading phase (ms).
     */
    @Column
    private Long loadingDurationMs;

    /**
     * Time spent in conversion phase (ms).
     */
    @Column
    private Long conversionDurationMs;

    /**
     * Time spent in chunking phase (ms).
     */
    @Column
    private Long chunkingDurationMs;

    /**
     * Time spent in embedding phase (ms).
     */
    @Column
    private Long embeddingDurationMs;

    /**
     * Time spent in indexing phase (ms).
     */
    @Column
    private Long indexingDurationMs;

    // ===================== PROCESSING PARAMETERS =====================

    /**
     * Document loader used (e.g., "TikaLoader", "PdfLoader").
     */
    @Column(length = 128)
    private String loaderUsed;

    /**
     * Text chunker used (e.g., "RecursiveCharacterTextChunker").
     */
    @Column(length = 128)
    private String chunkerUsed;

    /**
     * Embedding model used (e.g., "bge-base-en-v1.5").
     */
    @Column(length = 128)
    private String embeddingModelUsed;

    /**
     * Index/vector store used (e.g., "AnseriniVectorStore").
     */
    @Column(length = 128)
    private String indexerUsed;

    /**
     * Configured chunk size.
     */
    @Column
    private Integer chunkSize;

    /**
     * Configured chunk overlap.
     */
    @Column
    private Integer chunkOverlap;

    /**
     * Embedding batch size used.
     */
    @Column
    private Integer embeddingBatchSize;

    /**
     * Number of worker threads used.
     */
    @Column
    private Integer workerThreads;

    /**
     * Whether parallel processing was enabled.
     */
    @Column
    private Boolean parallelProcessingEnabled;

    /**
     * Whether adaptive batching was enabled.
     */
    @Column
    private Boolean adaptiveBatchingEnabled;

    // ===================== RESULTS =====================

    /**
     * Number of documents loaded from source.
     */
    @Column
    private Integer documentsLoaded;

    /**
     * Number of chunks created.
     */
    @Column
    private Integer chunksCreated;

    /**
     * Number of chunks successfully embedded.
     */
    @Column
    private Integer chunksEmbedded;

    /**
     * Number of documents/chunks successfully indexed.
     */
    @Column
    private Integer documentsIndexed;

    /**
     * Total tokens processed (if tracked).
     */
    @Column
    private Long totalTokensProcessed;

    /**
     * Embedding dimension used.
     */
    @Column
    private Integer embeddingDimension;

    // ===================== ENVIRONMENT =====================

    /**
     * Java version.
     */
    @Column(length = 64)
    private String javaVersion;

    /**
     * Operating system name and version.
     */
    @Column(length = 128)
    private String osInfo;

    /**
     * Available processors at job start.
     */
    @Column
    private Integer availableProcessors;

    /**
     * Maximum heap memory at job start (bytes).
     */
    @Column
    private Long maxHeapMemoryBytes;

    /**
     * Free heap memory at job start (bytes).
     */
    @Column
    private Long freeHeapMemoryAtStart;

    /**
     * Memory usage percent at job start.
     */
    @Column
    private Double memoryUsagePercentAtStart;

    /**
     * Memory usage percent when job ended.
     */
    @Column
    private Double memoryUsagePercentAtEnd;

    /**
     * Peak memory usage percent during job.
     */
    @Column
    private Double peakMemoryUsagePercent;

    /**
     * ND4J backend used (e.g., "nd4j-native", "nd4j-cuda").
     */
    @Column(length = 64)
    private String nd4jBackend;

    /**
     * ND4J environment configuration as JSON.
     * Contains detailed settings like BLAS threads, workspace config, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String nd4jEnvironmentJson;

    // ===================== ERROR INFORMATION =====================

    /**
     * Error message if the job failed.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Error type/class name if the job failed.
     */
    @Column(length = 256)
    private String errorType;

    /**
     * Stack trace if the job failed (truncated).
     */
    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    /**
     * Failure reason category.
     * Uses custom converter to avoid H2 check constraint issues when adding new enum values.
     */
    @Column(length = 64)
    @Convert(converter = FailureReasonConverter.class)
    private FailureReason failureReason;

    // ===================== RESTART TRACKING =====================

    /**
     * Number of restart attempts for this job.
     */
    @Column
    private Integer restartAttempts;

    /**
     * Maximum restart attempts configured.
     */
    @Column
    private Integer maxRestartAttempts;

    /**
     * Timestamp of the last restart attempt.
     */
    @Column
    private Instant lastRestartTime;

    /**
     * Whether the job recovered successfully after restart(s).
     */
    @Column
    private Boolean recoveredAfterRestart;

    /**
     * Initial heap size in bytes before any restarts.
     */
    @Column
    private Long initialHeapBytes;

    /**
     * Final heap size in bytes after restart adjustments.
     */
    @Column
    private Long finalHeapBytes;

    /**
     * Initial batch size before any restarts.
     */
    @Column
    private Integer initialBatchSize;

    /**
     * Final batch size after restart adjustments.
     */
    @Column
    private Integer finalBatchSize;

    /**
     * Initial thread count before any restarts.
     */
    @Column
    private Integer initialThreadCount;

    /**
     * Final thread count after restart adjustments.
     */
    @Column
    private Integer finalThreadCount;

    /**
     * JSON array of restart history entries.
     * Each entry contains: attemptNumber, timestamp, reason, heapBytes, batchSize, threads, success
     */
    @Column(columnDefinition = "TEXT")
    private String restartHistoryJson;

    // ===================== METADATA =====================

    /**
     * Additional details as JSON (extensible).
     */
    @Column(columnDefinition = "TEXT")
    private String additionalDetails;

    /**
     * User or system that initiated the job.
     */
    @Column(length = 128)
    private String initiatedBy;

    /**
     * Index name or path used.
     */
    @Column(length = 512)
    private String indexPath;

    /**
     * Application version.
     */
    @Column(length = 64)
    private String appVersion;

    @PrePersist
    protected void onCreate() {
        if (startTime == null) {
            startTime = Instant.now();
        }
        if (status == null) {
            status = JobStatus.QUEUED;
        }
    }

    /**
     * Job status enumeration.
     */
    public enum JobStatus {
        /** Job is queued waiting to start */
        QUEUED,
        /** Job is currently running */
        RUNNING,
        /** Job completed successfully */
        COMPLETED,
        /** Job failed with an error */
        FAILED,
        /** Job was cancelled by user */
        CANCELLED,
        /** Job was killed due to memory pressure */
        MEMORY_KILLED,
        /** Job is paused (future use) */
        PAUSED
    }

    /**
     * Categorized failure reasons.
     */
    public enum FailureReason {
        /** No failure */
        NONE,
        /** Out of memory */
        OUT_OF_MEMORY,
        /** Memory kill threshold exceeded */
        MEMORY_KILLED,
        /** User cancelled the job */
        USER_CANCELLED,
        /** Document loading failed */
        LOAD_ERROR,
        /** Text conversion failed */
        CONVERSION_ERROR,
        /** Chunking failed */
        CHUNKING_ERROR,
        /** Embedding generation failed */
        EMBEDDING_ERROR,
        /** Index write failed */
        INDEXING_ERROR,
        /** Subprocess crashed or failed */
        SUBPROCESS_ERROR,
        /** Network/IO error */
        IO_ERROR,
        /** Invalid input or configuration */
        INVALID_INPUT,
        /** Timeout */
        TIMEOUT,
        /** Model not found in registry or staging service */
        MODEL_NOT_FOUND,
        /** Staging service connection or availability error */
        STAGING_ERROR,
        /** Unknown error */
        UNKNOWN
    }

    // ===================== FACTORY METHODS =====================

    /**
     * Create a new job history entry when a job is queued.
     */
    public static IndexingJobHistory createQueued(String taskId, String fileName) {
        return IndexingJobHistory.builder()
                .taskId(taskId)
                .fileName(fileName)
                .status(JobStatus.QUEUED)
                .lastPhase(IngestEvent.IngestPhase.QUEUED)
                .startTime(Instant.now())
                .progressPercent(0)
                .javaVersion(System.getProperty("java.version"))
                .osInfo(System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .availableProcessors(Runtime.getRuntime().availableProcessors())
                .maxHeapMemoryBytes(Runtime.getRuntime().maxMemory())
                .freeHeapMemoryAtStart(Runtime.getRuntime().freeMemory())
                .memoryUsagePercentAtStart(calculateMemoryUsagePercent())
                .build();
    }

    /**
     * Mark the job as running.
     * Also transitions lastPhase from QUEUED to LOADING for UI consistency.
     */
    public void markRunning() {
        this.status = JobStatus.RUNNING;
        // Update lastPhase to LOADING when job starts running
        // This ensures UI shows consistent state (RUNNING + LOADING, not RUNNING + QUEUED)
        if (this.lastPhase == IngestEvent.IngestPhase.QUEUED || this.lastPhase == null) {
            this.lastPhase = IngestEvent.IngestPhase.LOADING;
        }
    }

    /**
     * Mark the job as completed.
     */
    public void markCompleted() {
        this.status = JobStatus.COMPLETED;
        this.lastPhase = IngestEvent.IngestPhase.COMPLETED;
        this.progressPercent = 100;
        this.failureReason = FailureReason.NONE;
        finalizeJob();
    }

    /**
     * Mark the job as failed.
     */
    public void markFailed(IngestEvent.IngestPhase failedPhase, String errorMessage,
                           Throwable exception, FailureReason reason) {
        this.status = JobStatus.FAILED;
        this.failedPhase = failedPhase;
        this.errorMessage = errorMessage;
        this.failureReason = reason;
        if (exception != null) {
            this.errorType = exception.getClass().getName();
            this.stackTrace = truncateStackTrace(exception);
        }
        finalizeJob();
    }

    /**
     * Mark the job as cancelled.
     */
    public void markCancelled(IngestEvent.IngestPhase currentPhase, String reason) {
        this.status = JobStatus.CANCELLED;
        this.lastPhase = currentPhase;
        this.errorMessage = reason;
        this.failureReason = FailureReason.USER_CANCELLED;
        finalizeJob();
    }

    /**
     * Mark the job as killed due to memory pressure.
     */
    public void markMemoryKilled(IngestEvent.IngestPhase currentPhase, double memoryPercent) {
        this.status = JobStatus.MEMORY_KILLED;
        this.lastPhase = currentPhase;
        this.failureReason = FailureReason.MEMORY_KILLED;
        this.errorMessage = String.format("Job killed: memory usage %.1f%% exceeded threshold", memoryPercent);
        this.memoryUsagePercentAtEnd = memoryPercent;
        finalizeJob();
    }

    /**
     * Update the current phase.
     */
    public void updatePhase(IngestEvent.IngestPhase phase, int progressPercent) {
        this.lastPhase = phase;
        this.progressPercent = progressPercent;
    }

    /**
     * Update processing statistics.
     */
    public void updateStats(Integer documentsLoaded, Integer chunksCreated,
                           Integer chunksEmbedded, Integer documentsIndexed) {
        if (documentsLoaded != null) this.documentsLoaded = documentsLoaded;
        if (chunksCreated != null) this.chunksCreated = chunksCreated;
        if (chunksEmbedded != null) this.chunksEmbedded = chunksEmbedded;
        if (documentsIndexed != null) this.documentsIndexed = documentsIndexed;
    }

    /**
     * Update peak memory usage.
     */
    public void updatePeakMemory(double currentUsage) {
        if (this.peakMemoryUsagePercent == null || currentUsage > this.peakMemoryUsagePercent) {
            this.peakMemoryUsagePercent = currentUsage;
        }
    }

    // ===================== RESTART TRACKING METHODS =====================

    /**
     * Initialize restart tracking with initial configuration values.
     * Should be called when the job first starts.
     */
    public void initializeRestartTracking(int maxAttempts, long heapBytes, int batchSize, int threadCount) {
        this.restartAttempts = 0;
        this.maxRestartAttempts = maxAttempts;
        this.initialHeapBytes = heapBytes;
        this.finalHeapBytes = heapBytes;
        this.initialBatchSize = batchSize;
        this.finalBatchSize = batchSize;
        this.initialThreadCount = threadCount;
        this.finalThreadCount = threadCount;
        this.recoveredAfterRestart = false;
    }

    /**
     * Record a restart attempt with configuration adjustments.
     *
     * @param attemptNumber The attempt number (1-based)
     * @param reason The reason for the restart (e.g., "OUT_OF_MEMORY", "OOM_KILLED")
     * @param newHeapBytes New heap size after adjustment
     * @param newBatchSize New batch size after adjustment
     * @param newThreadCount New thread count after adjustment
     */
    public void recordRestartAttempt(int attemptNumber, String reason,
                                      long newHeapBytes, int newBatchSize, int newThreadCount) {
        this.restartAttempts = attemptNumber;
        this.lastRestartTime = Instant.now();
        this.finalHeapBytes = newHeapBytes;
        this.finalBatchSize = newBatchSize;
        this.finalThreadCount = newThreadCount;

        // Append to restart history JSON
        appendRestartHistoryEntry(attemptNumber, reason, newHeapBytes, newBatchSize, newThreadCount, false);
    }

    /**
     * Mark the last restart attempt as successful.
     */
    public void markRestartSuccessful() {
        this.recoveredAfterRestart = true;

        // Update the last entry in restart history to mark as successful
        if (this.restartHistoryJson != null && this.restartAttempts != null && this.restartAttempts > 0) {
            // Replace the last "success":false with "success":true
            int lastIdx = this.restartHistoryJson.lastIndexOf("\"success\":false");
            if (lastIdx > 0) {
                this.restartHistoryJson = this.restartHistoryJson.substring(0, lastIdx)
                    + "\"success\":true"
                    + this.restartHistoryJson.substring(lastIdx + "\"success\":false".length());
            }
        }
    }

    /**
     * Check if restarts have been exhausted.
     */
    public boolean hasExhaustedRestarts() {
        if (this.restartAttempts == null || this.maxRestartAttempts == null) {
            return false;
        }
        return this.restartAttempts >= this.maxRestartAttempts;
    }

    /**
     * Get the number of remaining restart attempts.
     */
    public int getRemainingRestartAttempts() {
        if (this.restartAttempts == null || this.maxRestartAttempts == null) {
            return 0;
        }
        return Math.max(0, this.maxRestartAttempts - this.restartAttempts);
    }

    /**
     * Append an entry to the restart history JSON array.
     */
    private void appendRestartHistoryEntry(int attemptNumber, String reason,
                                            long heapBytes, int batchSize, int threadCount, boolean success) {
        String entry = String.format(
            "{\"attempt\":%d,\"timestamp\":\"%s\",\"reason\":\"%s\",\"heapBytes\":%d,\"batchSize\":%d,\"threads\":%d,\"success\":%s}",
            attemptNumber,
            Instant.now().toString(),
            reason != null ? reason.replace("\"", "\\\"") : "UNKNOWN",
            heapBytes,
            batchSize,
            threadCount,
            success
        );

        if (this.restartHistoryJson == null || this.restartHistoryJson.isBlank()) {
            this.restartHistoryJson = "[" + entry + "]";
        } else {
            // Remove trailing ] and append new entry
            this.restartHistoryJson = this.restartHistoryJson.substring(0, this.restartHistoryJson.length() - 1)
                + "," + entry + "]";
        }
    }

    private void finalizeJob() {
        this.endTime = Instant.now();
        if (this.startTime != null) {
            this.totalDurationMs = java.time.Duration.between(this.startTime, this.endTime).toMillis();
        }
        this.memoryUsagePercentAtEnd = calculateMemoryUsagePercent();
    }

    private static double calculateMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        return (double) usedMemory / maxMemory * 100.0;
    }

    private static String truncateStackTrace(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 4000) break;
        }
        return sb.toString();
    }
}
