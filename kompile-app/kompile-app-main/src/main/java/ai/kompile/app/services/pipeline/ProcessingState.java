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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.LargeDocumentInfo;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tracks processing state for large document ingestion, enabling resume capability.
 *
 * <p>When processing large documents page-by-page, we checkpoint progress at each page
 * so that if processing is interrupted (crash, user cancel, memory pressure), we can
 * resume from the last successfully processed page rather than starting over.</p>
 *
 * <h2>State Persistence</h2>
 * <ul>
 *   <li>State is persisted to disk after each page is processed</li>
 *   <li>On startup, incomplete states can be detected and resumed</li>
 *   <li>Completed states are cleaned up after a configurable retention period</li>
 * </ul>
 *
 * @param taskId             Unique task identifier for this ingestion job
 * @param sourceId           Source document identifier (usually filename-based)
 * @param fileName           Original filename
 * @param sourcePath         Path to the source document
 * @param lastProcessedPage  Last page that was fully processed (0 = none)
 * @param totalPages         Total pages in the document
 * @param chunksCreated      Total chunks created so far
 * @param chunksEmbedded     Total chunks embedded so far
 * @param chunksIndexed      Total chunks indexed so far
 * @param phase              Current processing phase
 * @param startedAt          When processing started
 * @param lastUpdated        When state was last updated
 * @param lastError          Last error message if failed
 * @param processedChunkIds  IDs of processed chunks (for verification)
 */
public record ProcessingState(
    String taskId,
    String sourceId,
    String fileName,
    String sourcePath,
    int lastProcessedPage,
    int totalPages,
    int chunksCreated,
    int chunksEmbedded,
    int chunksIndexed,
    ProcessingPhase phase,
    Instant startedAt,
    Instant lastUpdated,
    String lastError,
    List<String> processedChunkIds
) {
    /**
     * Processing phases for large document ingestion.
     */
    public enum ProcessingPhase {
        /** Waiting to start */
        QUEUED,
        /** Reading document pages */
        LOADING,
        /** Chunking pages */
        CHUNKING,
        /** Generating embeddings */
        EMBEDDING,
        /** Writing to index */
        INDEXING,
        /** Successfully completed */
        COMPLETED,
        /** Failed with error */
        FAILED,
        /** Paused (e.g., due to memory pressure) */
        PAUSED,
        /** Cancelled by user */
        CANCELLED
    }

    /**
     * Creates an initial state for a new large document processing job.
     *
     * @param taskId  Unique task identifier
     * @param source  Document source descriptor
     * @param docInfo Document information
     * @return A new ProcessingState in QUEUED phase
     */
    public static ProcessingState initial(String taskId,
                                          DocumentSourceDescriptor source,
                                          LargeDocumentInfo docInfo) {
        Instant now = Instant.now();
        return new ProcessingState(
            taskId,
            source.getSourceId() != null ? source.getSourceId() : source.getOriginalFileName(),
            source.getOriginalFileName(),
            source.getPathOrUrl(),
            0,
            docInfo.totalPages(),
            0,
            0,
            0,
            ProcessingPhase.QUEUED,
            now,
            now,
            null,
            new ArrayList<>()
        );
    }

    /**
     * Creates a new state with the page counter updated.
     */
    public ProcessingState withPage(int page) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            page, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            ProcessingPhase.CHUNKING,
            startedAt, Instant.now(),
            lastError, processedChunkIds
        );
    }

    /**
     * Creates a new state with chunk counts updated.
     */
    public ProcessingState withChunks(int created) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            created, chunksEmbedded, chunksIndexed,
            phase,
            startedAt, Instant.now(),
            lastError, processedChunkIds
        );
    }

    /**
     * Creates a new state with all counts updated.
     */
    public ProcessingState withCounts(int created, int embedded, int indexed) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            created, embedded, indexed,
            phase,
            startedAt, Instant.now(),
            lastError, processedChunkIds
        );
    }

    /**
     * Creates a new state with phase updated.
     */
    public ProcessingState withPhase(ProcessingPhase newPhase) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            newPhase,
            startedAt, Instant.now(),
            lastError, processedChunkIds
        );
    }

    /**
     * Creates a completed state.
     */
    public ProcessingState completed() {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            totalPages, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            ProcessingPhase.COMPLETED,
            startedAt, Instant.now(),
            null, processedChunkIds
        );
    }

    /**
     * Creates a failed state with error message.
     */
    public ProcessingState failed(String error) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            ProcessingPhase.FAILED,
            startedAt, Instant.now(),
            error, processedChunkIds
        );
    }

    /**
     * Creates a cancelled state.
     */
    public ProcessingState cancelled() {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            ProcessingPhase.CANCELLED,
            startedAt, Instant.now(),
            "Cancelled by user", processedChunkIds
        );
    }

    /**
     * Creates a paused state.
     */
    public ProcessingState paused(String reason) {
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            ProcessingPhase.PAUSED,
            startedAt, Instant.now(),
            reason, processedChunkIds
        );
    }

    /**
     * Returns true if this state can be resumed.
     */
    @JsonIgnore
    public boolean canResume() {
        return phase != ProcessingPhase.COMPLETED &&
               phase != ProcessingPhase.CANCELLED &&
               lastProcessedPage < totalPages;
    }

    /**
     * Returns true if processing is in a terminal state.
     */
    @JsonIgnore
    public boolean isTerminal() {
        return phase == ProcessingPhase.COMPLETED ||
               phase == ProcessingPhase.FAILED ||
               phase == ProcessingPhase.CANCELLED;
    }

    /**
     * Returns true if processing is actively running.
     */
    @JsonIgnore
    public boolean isActive() {
        return phase == ProcessingPhase.LOADING ||
               phase == ProcessingPhase.CHUNKING ||
               phase == ProcessingPhase.EMBEDDING ||
               phase == ProcessingPhase.INDEXING;
    }

    /**
     * Returns progress as a percentage (0-100).
     */
    @JsonIgnore
    public int progressPercent() {
        if (totalPages <= 0) return 0;
        if (phase == ProcessingPhase.COMPLETED) return 100;
        return Math.min(99, (lastProcessedPage * 100) / totalPages);
    }

    /**
     * Returns duration in milliseconds.
     */
    @JsonIgnore
    public long durationMs() {
        return lastUpdated.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Returns a human-readable summary of the state.
     */
    @JsonIgnore
    public String getSummary() {
        return String.format("%s: page %d/%d (%d%%), %d chunks, phase=%s",
            fileName, lastProcessedPage, totalPages, progressPercent(),
            chunksCreated, phase);
    }

    /**
     * Adds a chunk ID to the processed list (returns new state).
     */
    public ProcessingState withChunkId(String chunkId) {
        List<String> newIds = new ArrayList<>(processedChunkIds);
        newIds.add(chunkId);
        return new ProcessingState(
            taskId, sourceId, fileName, sourcePath,
            lastProcessedPage, totalPages,
            chunksCreated, chunksEmbedded, chunksIndexed,
            phase,
            startedAt, Instant.now(),
            lastError, newIds
        );
    }
}
