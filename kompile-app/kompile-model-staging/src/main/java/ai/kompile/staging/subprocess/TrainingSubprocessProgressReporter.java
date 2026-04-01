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

package ai.kompile.staging.subprocess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports progress from the training subprocess to the main application via STDOUT JSON messages.
 * All messages are prefixed with "TRAINING_MSG:" to distinguish from other stdout output.
 */
public class TrainingSubprocessProgressReporter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TrainingSubprocessProgressReporter.class);

    private final ObjectMapper objectMapper;
    private final PrintStream out;
    private final String taskId;
    private final long startTimeMs;
    private final AtomicLong lastProgressTime = new AtomicLong(0);

    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 3_000;
    private static final long PROGRESS_THROTTLE_MS = 50;

    private final ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    public TrainingSubprocessProgressReporter(String taskId) {
        this(taskId, System.out);
    }

    public TrainingSubprocessProgressReporter(String taskId, PrintStream out) {
        this.taskId = taskId;
        this.out = out;
        this.objectMapper = new ObjectMapper();
        this.startTimeMs = System.currentTimeMillis();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "training-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
    }

    public void startHeartbeat() {
        startHeartbeat(DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    public void startHeartbeat(long intervalMs) {
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled()) return;
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        logger.debug("Started heartbeat thread with {}ms interval", intervalMs);
    }

    public void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private void sendHeartbeat() {
        try {
            long uptimeMs = System.currentTimeMillis() - startTimeMs;
            send(TrainingSubprocessMessage.heartbeat(taskId, uptimeMs));
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    public void reportPhaseTransition(String fromPhase, String toPhase, long durationMs) {
        send(TrainingSubprocessMessage.phaseTransition(taskId, fromPhase, toPhase, durationMs));
    }

    public void reportProgress(long step, int epoch, int totalEpochs, double loss, double lr,
                                String phase, double epochProgress, double overallProgress, String message) {
        long now = System.currentTimeMillis();
        long lastTime = lastProgressTime.get();
        if (now - lastTime < PROGRESS_THROTTLE_MS) return;
        lastProgressTime.set(now);
        send(TrainingSubprocessMessage.progress(taskId, step, epoch, totalEpochs, loss, lr,
                phase, epochProgress, overallProgress, message));
    }

    public void reportProgressImmediate(long step, int epoch, int totalEpochs, double loss, double lr,
                                         String phase, double epochProgress, double overallProgress, String message) {
        lastProgressTime.set(System.currentTimeMillis());
        send(TrainingSubprocessMessage.progress(taskId, step, epoch, totalEpochs, loss, lr,
                phase, epochProgress, overallProgress, message));
    }

    public void reportMetrics(long step, int epoch, double trainLoss, double evalLoss, double lr,
                               double gradNorm, double tokensPerSec, double samplesPerSec,
                               Map<String, Double> customMetrics) {
        send(TrainingSubprocessMessage.metricsUpdate(taskId, step, epoch, trainLoss, evalLoss,
                lr, gradNorm, tokensPerSec, samplesPerSec, customMetrics));
    }

    public void reportCompleted(double finalLoss, double finalEvalLoss, long totalSteps,
                                 int totalEpochs, String outputPath, Map<String, Double> finalMetrics) {
        long totalDuration = System.currentTimeMillis() - startTimeMs;
        send(TrainingSubprocessMessage.completed(taskId, finalLoss, finalEvalLoss, totalSteps,
                totalEpochs, totalDuration, outputPath, finalMetrics));
    }

    public void reportFailed(String phase, Throwable exception) {
        send(TrainingSubprocessMessage.failed(taskId, phase, exception));
    }

    public void reportFailed(String phase, String errorMessage, String errorType, String stackTrace) {
        send(TrainingSubprocessMessage.failed(taskId, phase, errorMessage, errorType, stackTrace));
    }

    public void reportCheckpointSaved(long step, int epoch, String checkpointPath, double loss) {
        send(TrainingSubprocessMessage.checkpointSaved(taskId, step, epoch, checkpointPath, loss));
    }

    public void reportLog(String level, String message) {
        reportLog(level, "training-subprocess", message);
    }

    public void reportLog(String level, String source, String message) {
        switch (level.toUpperCase()) {
            case "ERROR" -> logger.error("[{}] {}", source, message);
            case "WARN" -> logger.warn("[{}] {}", source, message);
            case "DEBUG" -> logger.debug("[{}] {}", source, message);
            default -> logger.info("[{}] {}", source, message);
        }
        send(TrainingSubprocessMessage.log(taskId, level.toUpperCase(), source, message));
    }

    private synchronized void send(TrainingSubprocessMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            out.println(TrainingSubprocessMessage.MESSAGE_PREFIX + json);
            out.flush();
        } catch (JsonProcessingException e) {
            System.err.println("Failed to serialize training subprocess message: " + e.getMessage());
            logger.error("Failed to serialize training subprocess message", e);
        }
    }

    public String getTaskId() { return taskId; }

    public long getElapsedMs() { return System.currentTimeMillis() - startTimeMs; }

    @Override
    public void close() {
        stopHeartbeat();
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
