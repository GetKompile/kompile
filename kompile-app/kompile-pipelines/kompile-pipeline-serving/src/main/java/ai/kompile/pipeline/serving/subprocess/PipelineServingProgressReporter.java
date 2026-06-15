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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports progress from the pipeline subprocess to the parent via STDOUT JSON messages.
 *
 * <p>All messages are prefixed with "PIPELINE_MSG:" to distinguish them from other
 * stdout output. This class manages a heartbeat thread that periodically sends
 * liveness signals with memory snapshots.</p>
 *
 * <p>Mirrors {@code SubprocessProgressReporter} from kompile-app-core but emits
 * {@link PipelineServingMessage} types.</p>
 */
public class PipelineServingProgressReporter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PipelineServingProgressReporter.class);

    private final ObjectMapper objectMapper;
    private final PrintStream out;
    private final String taskId;
    private final long startTimeMs;
    private final long heartbeatIntervalMs;

    private final ScheduledExecutorService heartbeatExecutor;
    private volatile ScheduledFuture<?> heartbeatFuture;

    // Tracking for heartbeat stats
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final AtomicLong totalRequestsServed = new AtomicLong(0);

    public PipelineServingProgressReporter(String taskId, PrintStream out, long heartbeatIntervalMs) {
        this.taskId = taskId;
        this.out = out;
        this.objectMapper = new ObjectMapper();
        this.startTimeMs = System.currentTimeMillis();
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
    }

    public PipelineServingProgressReporter(String taskId, PrintStream out) {
        this(taskId, out, 3000L);
    }

    /**
     * Start the periodic heartbeat thread.
     */
    public void startHeartbeat() {
        if (heartbeatFuture != null) {
            return;
        }
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
                this::emitHeartbeat,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );
        logger.debug("Heartbeat started with interval {}ms", heartbeatIntervalMs);
    }

    /**
     * Report that the subprocess is ready to accept work.
     */
    public void reportReady(String pipelineId, String pipelineKind, int port, long pid) {
        long startupTime = System.currentTimeMillis() - startTimeMs;
        emit(new PipelineServingMessage.Ready(taskId, startupTime, pipelineId, pipelineKind, port, pid));
    }

    /**
     * Report progress during initialization.
     */
    public void reportProgress(String phase, int percent, String message) {
        emit(new PipelineServingMessage.Progress(taskId, phase, percent, message));
    }

    /**
     * Report a phase transition.
     */
    public void reportPhaseTransition(String fromPhase, String toPhase, long durationMs) {
        emit(new PipelineServingMessage.PhaseTransition(taskId, fromPhase, toPhase, durationMs));
    }

    /**
     * Report successful completion of a ONE_SHOT execution.
     */
    public void reportCompleted(String requestId, long durationMs, Map<String, Object> outputData) {
        emit(new PipelineServingMessage.Completed(taskId, requestId, durationMs, outputData));
    }

    /**
     * Report a fatal subprocess-level failure.
     */
    public void reportFailed(String phase, Throwable t) {
        String stackTrace = truncateStackTrace(t, 50);
        emit(new PipelineServingMessage.Failed(
                taskId, phase,
                t.getMessage() != null ? t.getMessage() : t.getClass().getName(),
                t.getClass().getName(),
                stackTrace
        ));
    }

    /**
     * Report a per-request result in PERSISTENT_SERVING mode.
     */
    public void reportRequestResult(String requestId, boolean success, long durationMs,
                                    Map<String, Object> outputData, String errorMessage) {
        totalRequestsServed.incrementAndGet();
        emit(new PipelineServingMessage.RequestResult(
                taskId, requestId, success, durationMs, outputData, errorMessage
        ));
    }

    /**
     * Report a log message from the subprocess.
     */
    public void reportLog(String level, String source, String message) {
        emit(new PipelineServingMessage.Log(taskId, level, source, message, System.currentTimeMillis()));
    }

    /**
     * Increment active request counter (for heartbeat stats).
     */
    public void requestStarted() {
        activeRequests.incrementAndGet();
    }

    /**
     * Decrement active request counter (for heartbeat stats).
     */
    public void requestFinished() {
        activeRequests.decrementAndGet();
    }

    @Override
    public void close() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void emitHeartbeat() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long heapUsed = runtime.totalMemory() - runtime.freeMemory();
            long heapMax = runtime.maxMemory();

            // Off-heap from JavaCPP Pointer.totalBytes() if available
            long offHeapUsed = getOffHeapBytes();

            // GPU memory - try to read via ND4J native ops if available
            long gpuUsed = 0;
            long gpuMax = 0;

            emit(new PipelineServingMessage.Heartbeat(
                    taskId,
                    System.currentTimeMillis() - startTimeMs,
                    heapUsed,
                    heapMax,
                    offHeapUsed,
                    gpuUsed,
                    gpuMax,
                    activeRequests.get(),
                    totalRequestsServed.get()
            ));
        } catch (Exception e) {
            logger.debug("Failed to emit heartbeat: {}", e.getMessage());
        }
    }

    private long getOffHeapBytes() {
        try {
            Class<?> pointerClass = Class.forName("org.bytedeco.javacpp.Pointer");
            return (long) pointerClass.getMethod("totalBytes").invoke(null);
        } catch (Exception e) {
            return 0;
        }
    }

    private void emit(PipelineServingMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            synchronized (out) {
                out.println(PipelineServingMessage.MESSAGE_PREFIX + json);
                out.flush();
            }
        } catch (Exception e) {
            logger.error("Failed to emit pipeline message: {}", e.getMessage());
        }
    }

    private String truncateStackTrace(Throwable t, int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append("\n");
        StackTraceElement[] frames = t.getStackTrace();
        int lines = Math.min(frames.length, maxLines);
        for (int i = 0; i < lines; i++) {
            sb.append("\tat ").append(frames[i]).append("\n");
        }
        if (frames.length > maxLines) {
            sb.append("\t... ").append(frames.length - maxLines).append(" more\n");
        }
        return sb.toString();
    }
}
