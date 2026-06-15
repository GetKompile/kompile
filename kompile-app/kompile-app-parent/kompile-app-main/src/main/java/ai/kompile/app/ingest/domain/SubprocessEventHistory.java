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
 * Entity for tracking embedding subprocess lifecycle events.
 * This tracks subprocess starts, stops, crashes, restarts, and model loading events
 * independently of document processing jobs.
 */
@Entity
@Table(name = "subprocess_event_history", indexes = {
        @Index(name = "idx_subprocess_event_type", columnList = "eventType"),
        @Index(name = "idx_subprocess_event_time", columnList = "timestamp"),
        @Index(name = "idx_subprocess_event_model", columnList = "modelId"),
        @Index(name = "idx_subprocess_restart_attempt", columnList = "restartAttemptNumber")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubprocessEventHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Event type.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EventType eventType;

    /**
     * Model ID being used/loaded.
     */
    @Column(length = 255)
    private String modelId;

    /**
     * Timestamp of the event.
     */
    @Column(nullable = false)
    private Instant timestamp;

    /**
     * For restart events: the attempt number (1-based).
     */
    @Column
    private Integer restartAttemptNumber;

    /**
     * For restart events: the maximum attempts configured.
     */
    @Column
    private Integer maxRestartAttempts;

    /**
     * For crash/restart events: the failure reason.
     */
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    /**
     * For crash events: detailed error message.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * For crash events: the exit code of the subprocess.
     */
    @Column
    private Integer exitCode;

    /**
     * For restart events: the backoff delay in milliseconds.
     */
    @Column
    private Long backoffMs;

    /**
     * For restart events: heap size in bytes after adjustment.
     */
    @Column
    private Long heapBytes;

    /**
     * For restart events: batch size after adjustment.
     */
    @Column
    private Integer batchSize;

    /**
     * For restart events: thread count after adjustment.
     */
    @Column
    private Integer threadCount;

    /**
     * For model loaded events: the embedding dimensions.
     */
    @Column
    private Integer embeddingDimensions;

    /**
     * For model loaded events: the encoder type.
     */
    @Column(length = 64)
    private String encoderType;

    /**
     * Whether this restart was successful (subprocess came back up).
     */
    @Column
    private Boolean restartSuccessful;

    /**
     * Associated task ID if a job was in progress when the event occurred.
     */
    @Column(length = 64)
    private String taskId;

    /**
     * Event types for subprocess lifecycle.
     */
    public enum EventType {
        SUBPROCESS_STARTED,
        SUBPROCESS_STOPPED,
        SUBPROCESS_CRASHED,
        SUBPROCESS_RESTARTING,
        SUBPROCESS_RESTART_SUCCESS,
        SUBPROCESS_RESTART_EXHAUSTED,
        MODEL_LOADING,
        MODEL_LOADED,
        MODEL_FAILED
    }

    // Factory methods

    public static SubprocessEventHistory subprocessStarted(String modelId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_STARTED)
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory subprocessStopped(String modelId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_STOPPED)
                .modelId(modelId)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory subprocessCrashed(String modelId, String error, Integer exitCode, String taskId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_CRASHED)
                .modelId(modelId)
                .errorMessage(error)
                .exitCode(exitCode)
                .taskId(taskId)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory subprocessRestarting(String modelId, int attemptNumber, int maxAttempts,
                                                               String reason, long backoffMs,
                                                               long heapBytes, int batchSize, int threads,
                                                               String taskId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_RESTARTING)
                .modelId(modelId)
                .restartAttemptNumber(attemptNumber)
                .maxRestartAttempts(maxAttempts)
                .failureReason(reason)
                .backoffMs(backoffMs)
                .heapBytes(heapBytes)
                .batchSize(batchSize)
                .threadCount(threads)
                .taskId(taskId)
                .restartSuccessful(false)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory subprocessRestartSuccess(String modelId, int attemptNumber, String taskId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_RESTART_SUCCESS)
                .modelId(modelId)
                .restartAttemptNumber(attemptNumber)
                .restartSuccessful(true)
                .taskId(taskId)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory subprocessRestartExhausted(String modelId, int totalAttempts, String lastReason, String taskId) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.SUBPROCESS_RESTART_EXHAUSTED)
                .modelId(modelId)
                .restartAttemptNumber(totalAttempts)
                .failureReason(lastReason)
                .restartSuccessful(false)
                .taskId(taskId)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory modelLoaded(String modelId, int dimensions, String encoderType) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.MODEL_LOADED)
                .modelId(modelId)
                .embeddingDimensions(dimensions)
                .encoderType(encoderType)
                .timestamp(Instant.now())
                .build();
    }

    public static SubprocessEventHistory modelFailed(String modelId, String error) {
        return SubprocessEventHistory.builder()
                .eventType(EventType.MODEL_FAILED)
                .modelId(modelId)
                .errorMessage(error)
                .timestamp(Instant.now())
                .build();
    }
}
