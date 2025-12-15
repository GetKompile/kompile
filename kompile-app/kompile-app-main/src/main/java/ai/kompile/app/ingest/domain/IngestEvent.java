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
        MEMORY_KILLED
    }

    /**
     * Phases of the ingest pipeline.
     */
    public enum IngestPhase {
        /** Task is queued */
        QUEUED,
        /** Loading raw document from source */
        LOADING,
        /** Converting rich format to plain text */
        CONVERTING,
        /** Chunking text into segments */
        CHUNKING,
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
}
