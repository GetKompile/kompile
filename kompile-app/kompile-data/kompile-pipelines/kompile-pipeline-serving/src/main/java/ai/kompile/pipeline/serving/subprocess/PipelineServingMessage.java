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

package ai.kompile.pipeline.serving.subprocess;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface for pipeline subprocess communication messages.
 * All messages are serialized to JSON and written to STDOUT with prefix
 * "PIPELINE_MSG:".
 *
 * <p>This mirrors {@code SubprocessMessage} from kompile-app-core but is
 * specific to pipeline serving. Key differences from ingest messages:
 * tracks request latency and executor state rather than document/chunk counts.</p>
 *
 * <p>The parent process reads these from the subprocess stdout to:
 * <ul>
 *   <li>Detect readiness and health via heartbeats</li>
 *   <li>Forward progress to WebSocket clients / UI</li>
 *   <li>Track per-request execution results</li>
 *   <li>Detect OOM/stall and trigger restart</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PipelineServingMessage.Ready.class, name = "READY"),
        @JsonSubTypes.Type(value = PipelineServingMessage.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = PipelineServingMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
        @JsonSubTypes.Type(value = PipelineServingMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = PipelineServingMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = PipelineServingMessage.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = PipelineServingMessage.RequestResult.class, name = "REQUEST_RESULT"),
        @JsonSubTypes.Type(value = PipelineServingMessage.Log.class, name = "LOG")
})
public sealed interface PipelineServingMessage
        permits PipelineServingMessage.Ready,
        PipelineServingMessage.Progress,
        PipelineServingMessage.PhaseTransition,
        PipelineServingMessage.Heartbeat,
        PipelineServingMessage.Completed,
        PipelineServingMessage.Failed,
        PipelineServingMessage.RequestResult,
        PipelineServingMessage.Log {

    /** Message prefix used to distinguish pipeline messages from other stdout output */
    String MESSAGE_PREFIX = "PIPELINE_MSG:";

    /** Get the task ID associated with this message */
    String taskId();

    /**
     * Subprocess initialized, pipeline executor ready, HTTP server up (for persistent serving).
     */
    record Ready(
            String taskId,
            long startupTimeMs,
            String pipelineId,
            String pipelineKind,
            int port,
            long pid
    ) implements PipelineServingMessage {}

    /**
     * Progress update during pipeline initialization (loading models, warming up steps).
     */
    record Progress(
            String taskId,
            String phase,
            int progressPercent,
            String message
    ) implements PipelineServingMessage {}

    /**
     * Transition between execution phases.
     * Phases: LOADING_PIPELINE -> INITIALIZING_STEPS -> READY -> EXECUTING
     */
    record PhaseTransition(
            String taskId,
            String fromPhase,
            String toPhase,
            long phaseDurationMs
    ) implements PipelineServingMessage {}

    /**
     * Periodic liveness signal with memory snapshot.
     * Used by the parent to detect stale/stuck subprocesses.
     */
    record Heartbeat(
            String taskId,
            long uptimeMs,
            long heapUsedBytes,
            long heapMaxBytes,
            long offHeapUsedBytes,
            long gpuUsedBytes,
            long gpuMaxBytes,
            int activeRequests,
            long totalRequestsServed
    ) implements PipelineServingMessage {}

    /**
     * Terminal success for ONE_SHOT execution mode.
     * Contains the pipeline output data.
     */
    record Completed(
            String taskId,
            String requestId,
            long durationMs,
            Map<String, Object> outputData
    ) implements PipelineServingMessage {}

    /**
     * Terminal failure at the subprocess level (not per-request).
     * Subprocess will exit after emitting this.
     */
    record Failed(
            String taskId,
            String phase,
            String errorMessage,
            String errorType,
            String stackTrace
    ) implements PipelineServingMessage {}

    /**
     * Per-request result in PERSISTENT_SERVING mode.
     * Emitted on stdout AND returned via HTTP response.
     */
    record RequestResult(
            String taskId,
            String requestId,
            boolean success,
            long durationMs,
            Map<String, Object> outputData,
            String errorMessage
    ) implements PipelineServingMessage {}

    /**
     * Forwarded log line from the subprocess.
     */
    record Log(
            String taskId,
            String level,
            String source,
            String message,
            long timestamp
    ) implements PipelineServingMessage {}
}
