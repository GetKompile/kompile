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
 * Entity representing a single event in the document ingest pipeline.
 * Events track state transitions during document processing: conversion, chunking, embedding, indexing.
 *
 * This provides an audit trail for debugging and performance analysis.
 */
@Entity
@Table(name = "ingest_events", indexes = {
    @Index(name = "idx_ingest_task_id", columnList = "taskId"),
    @Index(name = "idx_ingest_event_type", columnList = "eventType"),
    @Index(name = "idx_ingest_timestamp", columnList = "timestamp"),
    @Index(name = "idx_ingest_task_phase", columnList = "taskId, phase")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * Task ID that groups all events for a single ingest operation.
     */
    @Column(nullable = false, length = 64)
    private String taskId;

    /**
     * The file name being processed.
     */
    @Column(nullable = false, length = 512)
    private String fileName;

    /**
     * Type of event (state transition).
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    /**
     * Current processing phase.
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private IngestPhase phase;

    /**
     * Previous phase (for state transitions).
     */
    @Column
    @Enumerated(EnumType.STRING)
    private IngestPhase previousPhase;

    /**
     * When this event occurred.
     */
    @Column(nullable = false)
    private Instant timestamp;

    /**
     * Duration in milliseconds for this phase (when phase completes).
     */
    @Column
    private Long durationMs;

    /**
     * Human-readable message describing the event.
     */
    @Column(length = 1024)
    private String message;

    /**
     * Additional details (e.g., chunk count, byte size, error details).
     */
    @Column(columnDefinition = "TEXT")
    private String details;

    /**
     * Number of items processed at this point (chunks, documents, etc.).
     */
    @Column
    private Integer itemsProcessed;

    /**
     * Total items to process (for progress calculation).
     */
    @Column
    private Integer totalItems;

    /**
     * Size in bytes processed at this point.
     */
    @Column
    private Long bytesProcessed;

    /**
     * Memory usage percentage at this point.
     */
    @Column
    private Double memoryUsagePercent;

    /**
     * If this is an error event, the error message.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Stack trace for error events.
     */
    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    /**
     * ND4J environment configuration snapshot captured at job start.
     * Stored as JSON to allow reproduction of issues with the same environment settings.
     * Only populated for QUEUED events.
     */
    @Column(columnDefinition = "TEXT")
    private String nd4jEnvironmentSnapshot;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * Types of events that can occur during ingest.
     */
    public enum EventType {
        /** Task has been queued for processing */
        QUEUED,
        /** Phase has started */
        PHASE_STARTED,
        /** Progress update within a phase */
        PROGRESS,
        /** Phase completed successfully */
        PHASE_COMPLETED,
        /** State transition from one phase to another */
        STATE_TRANSITION,
        /** Warning occurred but processing continues */
        WARNING,
        /** Error occurred, processing may continue or fail */
        ERROR,
        /** Task completed successfully */
        COMPLETED,
        /** Task failed */
        FAILED,
        /** Task was cancelled */
        CANCELLED,
        /** Task was forcibly killed due to memory pressure exceeding kill threshold */
        MEMORY_KILLED,

        // ======== OOM Restart Event Types ========
        /** Automatic restart scheduled after OOM failure */
        RESTART_SCHEDULED,
        /** Restart attempt has started */
        RESTART_ATTEMPTED,
        /** Process recovered successfully after restart */
        RESTART_SUCCEEDED,
        /** All restart attempts exhausted, process failed to recover */
        RESTART_FAILED,
        /** Detailed memory analysis result (system RAM, heap, recommendations) */
        MEMORY_ANALYSIS,
        /** Heap size was adjusted (increased or kept same due to RAM limits) */
        HEAP_ADJUSTED,
        /** Thread counts were reduced to save memory */
        THREADS_REDUCED,
        /** User triggered manual restart */
        MANUAL_RESTART,

        // ======== Extraction Event Types ========
        /** Content extraction phase started with details about extractors */
        EXTRACTION_STARTED,
        /** Progress update during content extraction */
        EXTRACTION_PROGRESS,
        /** Content extraction completed with summary of extracted items */
        EXTRACTION_COMPLETED,
        /** Individual extractor completed its work */
        EXTRACTOR_COMPLETED,
        /** Individual extractor encountered an error */
        EXTRACTOR_ERROR
    }

    /**
     * Phases of the ingest pipeline.
     */
    public enum IngestPhase {
        /** Task is queued */
        QUEUED,
        /** Loading raw document from source */
        LOADING,
        /** VLM/OCR processing pages (between LOADING and CONVERTING) */
        OCR_PROCESSING,
        /** Converting rich format to plain text */
        CONVERTING,
        /** Chunking text into segments */
        CHUNKING,
        /** Concurrent content extraction (chunking + structured output) */
        EXTRACTION,
        /** Concurrent keyword indexing and embedding (keyword index updated immediately) */
        INDEXING_AND_EMBEDDING,
        /** Graph extraction phase */
        GRAPH_EXTRACTION,
        /** Generating embeddings for chunks */
        EMBEDDING,
        /** Indexing chunks in vector store */
        INDEXING,
        /** Processing completed */
        COMPLETED,
        /** Processing failed */
        FAILED
    }

    /**
     * Create a queued event.
     */
    public static IngestEvent queued(String taskId, String fileName) {
        return queued(taskId, fileName, null);
    }

    /**
     * Create a queued event with ND4J environment snapshot.
     *
     * @param taskId               The task identifier
     * @param fileName             The file being processed
     * @param nd4jEnvironmentJson  JSON representation of ND4J environment config at job start
     */
    public static IngestEvent queued(String taskId, String fileName, String nd4jEnvironmentJson) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.QUEUED)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .message("Document queued for processing")
                .nd4jEnvironmentSnapshot(nd4jEnvironmentJson)
                .build();
    }

    /**
     * Create a phase started event.
     */
    public static IngestEvent phaseStarted(String taskId, String fileName, IngestPhase phase,
                                            IngestPhase previousPhase, String message) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.PHASE_STARTED)
                .phase(phase)
                .previousPhase(previousPhase)
                .timestamp(Instant.now())
                .message(message)
                .build();
    }

    /**
     * Create a progress event.
     */
    public static IngestEvent progress(String taskId, String fileName, IngestPhase phase,
                                        int itemsProcessed, int totalItems, String message) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.PROGRESS)
                .phase(phase)
                .timestamp(Instant.now())
                .itemsProcessed(itemsProcessed)
                .totalItems(totalItems)
                .message(message)
                .build();
    }

    /**
     * Create a phase completed event.
     */
    public static IngestEvent phaseCompleted(String taskId, String fileName, IngestPhase phase,
                                              long durationMs, int itemsProcessed, String message) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.PHASE_COMPLETED)
                .phase(phase)
                .timestamp(Instant.now())
                .durationMs(durationMs)
                .itemsProcessed(itemsProcessed)
                .message(message)
                .build();
    }

    /**
     * Create a state transition event.
     */
    public static IngestEvent stateTransition(String taskId, String fileName,
                                               IngestPhase fromPhase, IngestPhase toPhase,
                                               long phaseDurationMs, String message) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.STATE_TRANSITION)
                .phase(toPhase)
                .previousPhase(fromPhase)
                .timestamp(Instant.now())
                .durationMs(phaseDurationMs)
                .message(message)
                .build();
    }

    /**
     * Create an error event.
     */
    public static IngestEvent error(String taskId, String fileName, IngestPhase phase,
                                     String errorMessage, Throwable exception) {
        IngestEvent.IngestEventBuilder builder = IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.ERROR)
                .phase(phase)
                .timestamp(Instant.now())
                .message("Error in phase: " + phase)
                .errorMessage(errorMessage);

        if (exception != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : exception.getStackTrace()) {
                sb.append(element.toString()).append("\n");
                if (sb.length() > 4000) break; // Limit stack trace size
            }
            builder.stackTrace(sb.toString());
        }

        return builder.build();
    }

    /**
     * Create a task completed event.
     */
    public static IngestEvent completed(String taskId, String fileName, long totalDurationMs,
                                         int totalItemsProcessed, String summary) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.COMPLETED)
                .phase(IngestPhase.COMPLETED)
                .timestamp(Instant.now())
                .durationMs(totalDurationMs)
                .itemsProcessed(totalItemsProcessed)
                .message(summary)
                .build();
    }

    /**
     * Create a task failed event.
     */
    public static IngestEvent failed(String taskId, String fileName, IngestPhase failedPhase,
                                      long totalDurationMs, String errorMessage, Throwable exception) {
        IngestEvent.IngestEventBuilder builder = IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.FAILED)
                .phase(IngestPhase.FAILED)
                .previousPhase(failedPhase)
                .timestamp(Instant.now())
                .durationMs(totalDurationMs)
                .message("Task failed in phase: " + failedPhase)
                .errorMessage(errorMessage);

        if (exception != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : exception.getStackTrace()) {
                sb.append(element.toString()).append("\n");
                if (sb.length() > 4000) break;
            }
            builder.stackTrace(sb.toString());
        }

        return builder.build();
    }

    /**
     * Create a cancelled event.
     */
    public static IngestEvent cancelled(String taskId, String fileName, IngestPhase currentPhase,
                                         long durationMs, String reason) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.CANCELLED)
                .phase(currentPhase)
                .timestamp(Instant.now())
                .durationMs(durationMs)
                .message("Task cancelled: " + reason)
                .build();
    }

    /**
     * Create a memory killed event.
     * This event is fired when a job is forcibly terminated due to memory usage
     * exceeding the configured kill threshold.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param killedPhase     The phase in which the job was killed
     * @param durationMs      Time elapsed since task started
     * @param memoryPercent   Memory usage percentage at time of kill
     * @param killThreshold   The configured kill threshold percentage
     * @param itemsProcessed  Number of items processed before kill
     * @param details         Additional details (JSON or structured data)
     */
    public static IngestEvent memoryKilled(String taskId, String fileName, IngestPhase killedPhase,
                                            long durationMs, double memoryPercent, int killThreshold,
                                            int itemsProcessed, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.MEMORY_KILLED)
                .phase(killedPhase)
                .timestamp(Instant.now())
                .durationMs(durationMs)
                .memoryUsagePercent(memoryPercent)
                .itemsProcessed(itemsProcessed)
                .message(String.format("Task forcibly killed: memory usage %.1f%% exceeded kill threshold %d%%",
                        memoryPercent, killThreshold))
                .details(details)
                .build();
    }

    // ======== OOM Restart Event Factory Methods ========

    /**
     * Create a restart scheduled event.
     *
     * @param taskId        The task identifier
     * @param fileName      The file being processed
     * @param currentPhase  The phase when restart was scheduled
     * @param attemptNumber The restart attempt number (1-based)
     * @param maxAttempts   Maximum allowed restart attempts
     * @param backoffMs     Time until restart in milliseconds
     * @param details       Additional details (JSON with memory analysis)
     */
    public static IngestEvent restartScheduled(String taskId, String fileName, IngestPhase currentPhase,
                                                int attemptNumber, int maxAttempts, long backoffMs, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.RESTART_SCHEDULED)
                .phase(currentPhase)
                .timestamp(Instant.now())
                .itemsProcessed(attemptNumber)
                .totalItems(maxAttempts)
                .message(String.format("Restart scheduled (attempt %d/%d) in %dms after OOM",
                        attemptNumber, maxAttempts, backoffMs))
                .details(details)
                .build();
    }

    /**
     * Create a restart attempted event.
     *
     * @param taskId        The task identifier
     * @param fileName      The file being processed
     * @param attemptNumber The restart attempt number (1-based)
     * @param maxAttempts   Maximum allowed restart attempts
     * @param heapSize      The heap size being used for this attempt
     * @param details       Additional details (JSON with configuration)
     */
    public static IngestEvent restartAttempted(String taskId, String fileName,
                                                int attemptNumber, int maxAttempts, String heapSize, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.RESTART_ATTEMPTED)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .itemsProcessed(attemptNumber)
                .totalItems(maxAttempts)
                .message(String.format("Restart attempt %d/%d starting with heap=%s",
                        attemptNumber, maxAttempts, heapSize))
                .details(details)
                .build();
    }

    /**
     * Create a restart succeeded event.
     *
     * @param taskId        The task identifier
     * @param fileName      The file being processed
     * @param attemptNumber The successful restart attempt number
     * @param recoveryTimeMs Time taken to recover in milliseconds
     */
    public static IngestEvent restartSucceeded(String taskId, String fileName,
                                                int attemptNumber, long recoveryTimeMs) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.RESTART_SUCCEEDED)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .itemsProcessed(attemptNumber)
                .durationMs(recoveryTimeMs)
                .message(String.format("Process recovered after %d restart attempt(s)", attemptNumber))
                .build();
    }

    /**
     * Create a restart failed event (all attempts exhausted).
     *
     * @param taskId        The task identifier
     * @param fileName      The file being processed
     * @param totalAttempts Total number of restart attempts made
     * @param totalTimeMs   Total time spent on all attempts
     * @param errorMessage  Final error message
     */
    public static IngestEvent restartFailed(String taskId, String fileName,
                                             int totalAttempts, long totalTimeMs, String errorMessage) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.RESTART_FAILED)
                .phase(IngestPhase.FAILED)
                .timestamp(Instant.now())
                .itemsProcessed(totalAttempts)
                .durationMs(totalTimeMs)
                .message(String.format("All %d restart attempts exhausted. Manual action required.", totalAttempts))
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a memory analysis event.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param currentPhase    The current phase
     * @param canIncreaseHeap Whether heap can be increased
     * @param reason          Detailed reason explaining the memory analysis
     * @param details         Additional details (JSON with full analysis)
     */
    public static IngestEvent memoryAnalysis(String taskId, String fileName, IngestPhase currentPhase,
                                              boolean canIncreaseHeap, String reason, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.MEMORY_ANALYSIS)
                .phase(currentPhase)
                .timestamp(Instant.now())
                .message(String.format("Memory analysis: %s heap increase. %s",
                        canIncreaseHeap ? "CAN" : "CANNOT", reason))
                .details(details)
                .build();
    }

    /**
     * Create a heap adjusted event.
     *
     * @param taskId      The task identifier
     * @param fileName    The file being processed
     * @param oldHeapSize Previous heap size
     * @param newHeapSize New heap size
     * @param increased   Whether heap was increased (vs kept same)
     * @param reason      Reason for the adjustment
     */
    public static IngestEvent heapAdjusted(String taskId, String fileName,
                                            String oldHeapSize, String newHeapSize,
                                            boolean increased, String reason) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.HEAP_ADJUSTED)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .message(String.format("Heap %s: %s -> %s. %s",
                        increased ? "increased" : "unchanged", oldHeapSize, newHeapSize, reason))
                .build();
    }

    /**
     * Create a threads reduced event.
     *
     * @param taskId     The task identifier
     * @param fileName   The file being processed
     * @param oldThreads Previous thread count
     * @param newThreads New thread count
     * @param reason     Reason for the reduction
     * @param details    Additional details (JSON with all thread settings)
     */
    public static IngestEvent threadsReduced(String taskId, String fileName,
                                              int oldThreads, int newThreads, String reason, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.THREADS_REDUCED)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .message(String.format("Threads reduced: %d -> %d. %s", oldThreads, newThreads, reason))
                .details(details)
                .build();
    }

    /**
     * Create a manual restart event.
     *
     * @param taskId   The task identifier
     * @param fileName The file being processed
     * @param reason   Reason for manual restart
     */
    public static IngestEvent manualRestart(String taskId, String fileName, String reason) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.MANUAL_RESTART)
                .phase(IngestPhase.QUEUED)
                .timestamp(Instant.now())
                .message("Manual restart triggered: " + reason)
                .build();
    }

    // ======== Extraction Event Factory Methods ========

    /**
     * Create an extraction started event.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param documentCount   Number of documents to extract from
     * @param extractors      List of extractor names being used
     * @param details         Additional details (JSON with extractor configuration)
     */
    public static IngestEvent extractionStarted(String taskId, String fileName,
                                                 int documentCount, String extractors, String details) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.EXTRACTION_STARTED)
                .phase(IngestPhase.EXTRACTION)
                .timestamp(Instant.now())
                .totalItems(documentCount)
                .message(String.format("Starting extraction with %d documents using extractors: %s",
                        documentCount, extractors))
                .details(details)
                .build();
    }

    /**
     * Create an extraction progress event.
     *
     * @param taskId           The task identifier
     * @param fileName         The file being processed
     * @param extractorName    Name of the extractor reporting progress
     * @param itemsProcessed   Number of documents processed
     * @param totalItems       Total number of documents to process
     * @param chunksCreated    Number of chunks created so far
     * @param structuredItems  Number of structured items extracted so far
     * @param message          Progress message
     */
    public static IngestEvent extractionProgress(String taskId, String fileName,
                                                  String extractorName, int itemsProcessed, int totalItems,
                                                  int chunksCreated, int structuredItems, String message) {
        String details = String.format("{\"extractor\":\"%s\",\"chunksCreated\":%d,\"structuredItems\":%d}",
                extractorName, chunksCreated, structuredItems);
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.EXTRACTION_PROGRESS)
                .phase(IngestPhase.EXTRACTION)
                .timestamp(Instant.now())
                .itemsProcessed(itemsProcessed)
                .totalItems(totalItems)
                .message(message)
                .details(details)
                .build();
    }

    /**
     * Create an extraction completed event with detailed summary.
     *
     * @param taskId              The task identifier
     * @param fileName            The file being processed
     * @param documentsProcessed  Number of documents processed
     * @param chunksCreated       Number of chunks created
     * @param entitiesExtracted   Number of entities extracted
     * @param relationshipsExtracted Number of relationships extracted
     * @param conceptsExtracted   Number of concepts extracted
     * @param factsExtracted      Number of facts extracted
     * @param tablesExtracted     Number of tables extracted
     * @param durationMs          Time taken in milliseconds
     * @param extractorsUsed      Comma-separated list of extractors used
     * @param details             Additional details (JSON with full breakdown)
     */
    public static IngestEvent extractionCompleted(String taskId, String fileName,
                                                   int documentsProcessed, int chunksCreated,
                                                   int entitiesExtracted, int relationshipsExtracted,
                                                   int conceptsExtracted, int factsExtracted, int tablesExtracted,
                                                   long durationMs, String extractorsUsed, String details) {
        int totalStructured = entitiesExtracted + relationshipsExtracted + conceptsExtracted + factsExtracted + tablesExtracted;
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.EXTRACTION_COMPLETED)
                .phase(IngestPhase.EXTRACTION)
                .timestamp(Instant.now())
                .itemsProcessed(documentsProcessed)
                .durationMs(durationMs)
                .message(String.format("Extraction completed: %d chunks, %d structured items (entities=%d, relationships=%d, concepts=%d, facts=%d, tables=%d) from %d documents in %dms",
                        chunksCreated, totalStructured, entitiesExtracted, relationshipsExtracted,
                        conceptsExtracted, factsExtracted, tablesExtracted, documentsProcessed, durationMs))
                .details(details)
                .build();
    }

    /**
     * Create an extractor completed event for tracking individual extractor progress.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param extractorName   Name of the extractor
     * @param extractorType   Type of the extractor (e.g., CHUNKING, ENTITY, CONCEPT)
     * @param itemsProduced   Number of items produced by this extractor
     * @param durationMs      Time taken by this extractor
     */
    public static IngestEvent extractorCompleted(String taskId, String fileName,
                                                  String extractorName, String extractorType,
                                                  int itemsProduced, long durationMs) {
        return IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.EXTRACTOR_COMPLETED)
                .phase(IngestPhase.EXTRACTION)
                .timestamp(Instant.now())
                .itemsProcessed(itemsProduced)
                .durationMs(durationMs)
                .message(String.format("Extractor '%s' (%s) completed: %d items in %dms",
                        extractorName, extractorType, itemsProduced, durationMs))
                .build();
    }

    /**
     * Create an extractor error event.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param extractorName   Name of the extractor that failed
     * @param errorMessage    Error message
     * @param exception       The exception that occurred (may be null)
     */
    public static IngestEvent extractorError(String taskId, String fileName,
                                              String extractorName, String errorMessage, Throwable exception) {
        IngestEventBuilder builder = IngestEvent.builder()
                .taskId(taskId)
                .fileName(fileName)
                .eventType(EventType.EXTRACTOR_ERROR)
                .phase(IngestPhase.EXTRACTION)
                .timestamp(Instant.now())
                .message(String.format("Extractor '%s' error: %s", extractorName, errorMessage))
                .errorMessage(errorMessage);

        if (exception != null) {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement element : exception.getStackTrace()) {
                sb.append(element.toString()).append("\n");
                if (sb.length() > 4000) break;
            }
            builder.stackTrace(sb.toString());
        }

        return builder.build();
    }
}
