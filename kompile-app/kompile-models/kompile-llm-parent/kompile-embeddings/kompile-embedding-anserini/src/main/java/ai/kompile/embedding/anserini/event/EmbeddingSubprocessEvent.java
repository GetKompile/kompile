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

package ai.kompile.embedding.anserini.event;

import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Spring ApplicationEvent for embedding subprocess updates.
 * Published by AnseriniEmbeddingModelImpl and listened to by EmbeddingStatusBroadcaster
 * to forward subprocess logs/progress to the UI via WebSocket.
 */
public class EmbeddingSubprocessEvent extends ApplicationEvent {

    public enum EventType {
        PROGRESS,
        PHASE_TRANSITION,
        LOG,
        ERROR,
        HEARTBEAT,
        MODEL_LOADED,
        MODEL_FAILED,
        SUBPROCESS_STARTED,
        SUBPROCESS_STOPPED,
        SUBPROCESS_CRASHED,
        SUBPROCESS_RESTARTING,
        SUBPROCESS_RESTART_SUCCESS,
        SUBPROCESS_RESTART_EXHAUSTED
    }

    private final EventType eventType;
    private final String modelId;
    private final Map<String, Object> data;

    public EmbeddingSubprocessEvent(Object source, EventType eventType, String modelId, Map<String, Object> data) {
        super(source);
        this.eventType = eventType;
        this.modelId = modelId;
        this.data = data;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getModelId() {
        return modelId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    // Note: getTimestamp() is inherited from ApplicationEvent

    // Factory methods for common events
    public static EmbeddingSubprocessEvent progress(Object source, String modelId, String phase, int percent, String message) {
        return new EmbeddingSubprocessEvent(source, EventType.PROGRESS, modelId, Map.of(
                "phase", phase,
                "progressPercent", percent,
                "message", message != null ? message : ""
        ));
    }

    public static EmbeddingSubprocessEvent phaseTransition(Object source, String modelId, String fromPhase, String toPhase, long durationMs) {
        return new EmbeddingSubprocessEvent(source, EventType.PHASE_TRANSITION, modelId, Map.of(
                "fromPhase", fromPhase != null ? fromPhase : "",
                "toPhase", toPhase,
                "durationMs", durationMs
        ));
    }

    public static EmbeddingSubprocessEvent log(Object source, String modelId, String level, String logSource, String message) {
        return new EmbeddingSubprocessEvent(source, EventType.LOG, modelId, Map.of(
                "level", level,
                "source", logSource != null ? logSource : "",
                "message", message != null ? message : ""
        ));
    }

    public static EmbeddingSubprocessEvent error(Object source, String modelId, String errorMessage, String errorType, String phase) {
        return new EmbeddingSubprocessEvent(source, EventType.ERROR, modelId, Map.of(
                "errorMessage", errorMessage != null ? errorMessage : "",
                "errorType", errorType != null ? errorType : "",
                "phase", phase != null ? phase : ""
        ));
    }

    public static EmbeddingSubprocessEvent modelLoaded(Object source, String modelId, int dimensions, String encoderType) {
        return new EmbeddingSubprocessEvent(source, EventType.MODEL_LOADED, modelId, Map.of(
                "dimensions", dimensions,
                "encoderType", encoderType != null ? encoderType : ""
        ));
    }

    public static EmbeddingSubprocessEvent modelFailed(Object source, String modelId, String error) {
        return new EmbeddingSubprocessEvent(source, EventType.MODEL_FAILED, modelId, Map.of(
                "error", error != null ? error : ""
        ));
    }

    public static EmbeddingSubprocessEvent subprocessStarted(Object source, String modelId) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_STARTED, modelId, Map.of());
    }

    public static EmbeddingSubprocessEvent subprocessStopped(Object source, String modelId) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_STOPPED, modelId, Map.of());
    }

    public static EmbeddingSubprocessEvent subprocessCrashed(Object source, String modelId, String error) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_CRASHED, modelId, Map.of(
                "error", error != null ? error : ""
        ));
    }

    public static EmbeddingSubprocessEvent subprocessRestarting(Object source, String modelId,
                                                                  int attemptNumber, int maxAttempts,
                                                                  String reason, long backoffMs,
                                                                  long heapBytes, int batchSize, int threads) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_RESTARTING, modelId, Map.of(
                "attemptNumber", attemptNumber,
                "maxAttempts", maxAttempts,
                "reason", reason != null ? reason : "",
                "backoffMs", backoffMs,
                "heapBytes", heapBytes,
                "batchSize", batchSize,
                "threads", threads
        ));
    }

    public static EmbeddingSubprocessEvent subprocessRestartSuccess(Object source, String modelId, int attemptNumber) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_RESTART_SUCCESS, modelId, Map.of(
                "attemptNumber", attemptNumber
        ));
    }

    public static EmbeddingSubprocessEvent subprocessRestartExhausted(Object source, String modelId,
                                                                        int totalAttempts, String lastReason) {
        return new EmbeddingSubprocessEvent(source, EventType.SUBPROCESS_RESTART_EXHAUSTED, modelId, Map.of(
                "totalAttempts", totalAttempts,
                "lastReason", lastReason != null ? lastReason : ""
        ));
    }
}
