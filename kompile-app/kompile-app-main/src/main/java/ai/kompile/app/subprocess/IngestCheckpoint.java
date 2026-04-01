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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checkpoint for tracking ingest progress across subprocess restarts.
 *
 * This enables true adaptive recovery: when a subprocess fails with OOM,
 * the parent can restart it with adjusted settings and resume from where
 * it left off instead of reprocessing everything.
 *
 * Checkpoints are written atomically after each successful batch to ensure
 * consistency even if the process crashes mid-write.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngestCheckpoint {

    private static final Logger logger = LoggerFactory.getLogger(IngestCheckpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    // === Identification ===

    /** Unique job ID for this ingest operation */
    private String jobId;

    /** Task ID (may differ from jobId if retrying) */
    private String taskId;

    /** Original file path being ingested */
    private String filePath;

    /** File checksum for validation */
    private String fileChecksum;

    // === Progress Tracking ===

    /** Current phase: LOADING, CHUNKING, EMBEDDING, INDEXING, COMPLETED */
    private String currentPhase;

    /** Total chunks created during chunking phase */
    private int totalChunks;

    /** Set of chunk indices that have been successfully embedded */
    private Set<Integer> embeddedChunkIndices;

    /** Set of chunk indices that have been successfully indexed */
    private Set<Integer> indexedChunkIndices;

    /** Last successfully completed batch number */
    private int lastCompletedBatch;

    /** Total batches expected */
    private int totalBatches;

    // === Settings that were used ===

    /** Heap size used in this attempt (e.g., "4g") */
    private String heapSize;

    /** Batch size used in this attempt */
    private int batchSize;

    /** Number of ND4J threads */
    private int nd4jThreads;

    /** Number of OMP threads */
    private int ompThreads;

    /** Number of embedding workers */
    private int embeddingWorkers;

    // === Failure Tracking ===

    /** Number of OOM failures encountered */
    private int oomFailureCount;

    /** History of settings that caused OOM */
    private List<FailedSettings> failedSettingsHistory;

    /** Last error message if failed */
    private String lastErrorMessage;

    /** Last error phase if failed */
    private String lastErrorPhase;

    // === Timestamps ===

    /** When this checkpoint was created */
    private Instant createdAt;

    /** When this checkpoint was last updated */
    private Instant updatedAt;

    /** Total processing time so far in milliseconds */
    private long totalProcessingTimeMs;

    public IngestCheckpoint() {
        this.embeddedChunkIndices = ConcurrentHashMap.newKeySet();
        this.indexedChunkIndices = ConcurrentHashMap.newKeySet();
        this.failedSettingsHistory = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Create a new checkpoint for a job.
     */
    public static IngestCheckpoint create(String jobId, String taskId, String filePath) {
        IngestCheckpoint cp = new IngestCheckpoint();
        cp.jobId = jobId;
        cp.taskId = taskId;
        cp.filePath = filePath;
        cp.currentPhase = "QUEUED";
        return cp;
    }

    /**
     * Load checkpoint from file, or create new if not exists.
     */
    public static IngestCheckpoint loadOrCreate(Path path, String jobId, String taskId, String filePath) {
        if (path != null && Files.exists(path)) {
            try {
                IngestCheckpoint cp = MAPPER.readValue(path.toFile(), IngestCheckpoint.class);
                // Validate it's for the same file
                if (cp.filePath != null && cp.filePath.equals(filePath)) {
                    logger.info("Loaded existing checkpoint for job {}: {} embedded, {} indexed chunks",
                            jobId, cp.getEmbeddedCount(), cp.getIndexedCount());
                    return cp;
                } else {
                    logger.warn("Checkpoint file mismatch: expected {}, got {}. Creating new.",
                            filePath, cp.filePath);
                }
            } catch (IOException e) {
                logger.warn("Failed to load checkpoint from {}: {}. Creating new.", path, e.getMessage());
            }
        }
        return create(jobId, taskId, filePath);
    }

    /**
     * Save checkpoint atomically to file.
     */
    public void save(Path path) throws IOException {
        if (path == null) {
            return;
        }

        this.updatedAt = Instant.now();

        // Write to temp file first, then atomic move
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            MAPPER.writeValue(tempPath.toFile(), this);
            Files.move(tempPath, path,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Fallback to non-atomic if atomic not supported
            Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Record that a batch of chunks was successfully embedded.
     */
    public void markChunksEmbedded(List<Integer> chunkIndices) {
        embeddedChunkIndices.addAll(chunkIndices);
        this.updatedAt = Instant.now();
    }

    /**
     * Record that a batch of chunks was successfully indexed.
     */
    public void markChunksIndexed(List<Integer> chunkIndices) {
        indexedChunkIndices.addAll(chunkIndices);
        this.updatedAt = Instant.now();
    }

    /**
     * Record a completed batch.
     */
    public void markBatchCompleted(int batchNumber) {
        this.lastCompletedBatch = batchNumber;
        this.updatedAt = Instant.now();
    }

    /**
     * Record an OOM failure with the settings that caused it.
     */
    public void recordOomFailure(String heapSize, int batchSize, int nd4jThreads,
                                  int ompThreads, String errorMessage, String phase) {
        this.oomFailureCount++;
        this.lastErrorMessage = errorMessage;
        this.lastErrorPhase = phase;

        FailedSettings failed = new FailedSettings();
        failed.heapSize = heapSize;
        failed.batchSize = batchSize;
        failed.nd4jThreads = nd4jThreads;
        failed.ompThreads = ompThreads;
        failed.failedAt = Instant.now();
        failed.errorMessage = errorMessage;
        failed.phase = phase;
        failed.chunksProcessedBefore = getEmbeddedCount();

        failedSettingsHistory.add(failed);
        this.updatedAt = Instant.now();
    }

    /**
     * Check if a chunk index has already been embedded.
     */
    public boolean isChunkEmbedded(int index) {
        return embeddedChunkIndices.contains(index);
    }

    /**
     * Check if a chunk index has already been indexed.
     */
    public boolean isChunkIndexed(int index) {
        return indexedChunkIndices.contains(index);
    }

    /**
     * Get count of embedded chunks.
     */
    public int getEmbeddedCount() {
        return embeddedChunkIndices.size();
    }

    /**
     * Get count of indexed chunks.
     */
    public int getIndexedCount() {
        return indexedChunkIndices.size();
    }

    /**
     * Check if we need to resume (some work done but not complete).
     */
    public boolean needsResume() {
        return (getEmbeddedCount() > 0 || getIndexedCount() > 0)
                && !"COMPLETED".equals(currentPhase);
    }

    /**
     * Get the next chunk index that needs embedding.
     */
    public int getNextChunkToEmbed() {
        for (int i = 0; i < totalChunks; i++) {
            if (!embeddedChunkIndices.contains(i)) {
                return i;
            }
        }
        return totalChunks; // All done
    }

    /**
     * Mark checkpoint as completed.
     */
    public void markCompleted(long totalTimeMs) {
        this.currentPhase = "COMPLETED";
        this.totalProcessingTimeMs = totalTimeMs;
        this.updatedAt = Instant.now();
    }

    // === Getters and Setters ===

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileChecksum() { return fileChecksum; }
    public void setFileChecksum(String fileChecksum) { this.fileChecksum = fileChecksum; }

    public String getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(String currentPhase) { this.currentPhase = currentPhase; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public Set<Integer> getEmbeddedChunkIndices() { return embeddedChunkIndices; }
    public void setEmbeddedChunkIndices(Set<Integer> indices) {
        this.embeddedChunkIndices = indices != null ? ConcurrentHashMap.newKeySet(indices.size()) : ConcurrentHashMap.newKeySet();
        if (indices != null) this.embeddedChunkIndices.addAll(indices);
    }

    public Set<Integer> getIndexedChunkIndices() { return indexedChunkIndices; }
    public void setIndexedChunkIndices(Set<Integer> indices) {
        this.indexedChunkIndices = indices != null ? ConcurrentHashMap.newKeySet(indices.size()) : ConcurrentHashMap.newKeySet();
        if (indices != null) this.indexedChunkIndices.addAll(indices);
    }

    public int getLastCompletedBatch() { return lastCompletedBatch; }
    public void setLastCompletedBatch(int batch) { this.lastCompletedBatch = batch; }

    public int getTotalBatches() { return totalBatches; }
    public void setTotalBatches(int batches) { this.totalBatches = batches; }

    public String getHeapSize() { return heapSize; }
    public void setHeapSize(String heapSize) { this.heapSize = heapSize; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getNd4jThreads() { return nd4jThreads; }
    public void setNd4jThreads(int threads) { this.nd4jThreads = threads; }

    public int getOmpThreads() { return ompThreads; }
    public void setOmpThreads(int threads) { this.ompThreads = threads; }

    public int getEmbeddingWorkers() { return embeddingWorkers; }
    public void setEmbeddingWorkers(int workers) { this.embeddingWorkers = workers; }

    public int getOomFailureCount() { return oomFailureCount; }
    public void setOomFailureCount(int count) { this.oomFailureCount = count; }

    public List<FailedSettings> getFailedSettingsHistory() { return failedSettingsHistory; }
    public void setFailedSettingsHistory(List<FailedSettings> history) {
        this.failedSettingsHistory = history != null ? history : new ArrayList<>();
    }

    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String msg) { this.lastErrorMessage = msg; }

    public String getLastErrorPhase() { return lastErrorPhase; }
    public void setLastErrorPhase(String phase) { this.lastErrorPhase = phase; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant time) { this.createdAt = time; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant time) { this.updatedAt = time; }

    public long getTotalProcessingTimeMs() { return totalProcessingTimeMs; }
    public void setTotalProcessingTimeMs(long ms) { this.totalProcessingTimeMs = ms; }

    /**
     * Record of settings that caused an OOM failure.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FailedSettings {
        public String heapSize;
        public int batchSize;
        public int nd4jThreads;
        public int ompThreads;
        public Instant failedAt;
        public String errorMessage;
        public String phase;
        public int chunksProcessedBefore;

        public FailedSettings() {}
    }
}
