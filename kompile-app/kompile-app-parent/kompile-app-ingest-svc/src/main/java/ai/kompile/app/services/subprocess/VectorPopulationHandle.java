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

package ai.kompile.app.services.subprocess;

import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.app.subprocess.SubprocessMessage;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handle for tracking a vector population subprocess.
 */
public class VectorPopulationHandle {
    private final String taskId;
    private final String keywordIndexPath;
    private final String vectorIndexPath;
    private final Process process;
    private final CompletableFuture<VectorPopulationResult> resultFuture;
    private final Path argsFile;
    private final Instant startTime;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean oomDetected = new AtomicBoolean(false);
    private volatile SubprocessRestartManager.FailureReason failureReason = null;

    private volatile Instant lastHeartbeat;
    private volatile Instant lastProgress;
    private volatile double lastHeapUsagePercent = 0.0;
    private volatile long lastHeapUsedBytes = 0L;
    private volatile long lastHeapMaxBytes = 0L;
    private volatile double lastOffHeapUsagePercent = 0.0;
    private volatile long lastOffHeapUsedBytes = 0L;
    private volatile long lastOffHeapMaxBytes = 0L;
    private volatile double lastGpuUsagePercent = 0.0;
    private volatile long lastGpuUsedBytes = 0L;
    private volatile long lastGpuMaxBytes = 0L;
    private volatile String currentPhase = "STARTING";
    private volatile int progressPercent = 0;
    private volatile String lastMessage = "";
    private volatile boolean startupComplete = false;
    volatile SubprocessLogWriter logWriter;

    public VectorPopulationHandle(String taskId, String keywordIndexPath, String vectorIndexPath,
            Process process, CompletableFuture<VectorPopulationResult> resultFuture,
            Path argsFile) {
        this.taskId = taskId;
        this.keywordIndexPath = keywordIndexPath;
        this.vectorIndexPath = vectorIndexPath;
        this.process = process;
        this.resultFuture = resultFuture;
        this.argsFile = argsFile;
        this.startTime = Instant.now();
        this.lastHeartbeat = Instant.now();
        this.lastProgress = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getKeywordIndexPath() {
        return keywordIndexPath;
    }

    public String getVectorIndexPath() {
        return vectorIndexPath;
    }

    public Process getProcess() {
        return process;
    }

    public CompletableFuture<VectorPopulationResult> getResultFuture() {
        return resultFuture;
    }

    public Path getArgsFile() {
        return argsFile;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isOomDetected() {
        return oomDetected.get();
    }

    public void setOomDetected(boolean detected) {
        oomDetected.set(detected);
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }

    public void setStartupComplete(boolean complete) {
        this.startupComplete = complete;
    }

    public SubprocessRestartManager.FailureReason getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(SubprocessRestartManager.FailureReason reason) {
        this.failureReason = reason;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String phase) {
        this.currentPhase = phase;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void updateProgress(String phase, int percent, String message) {
        this.currentPhase = phase;
        this.progressPercent = percent;
        this.lastMessage = message;
        this.lastProgress = Instant.now();
        updateHeartbeat();
    }

    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    public void updateHeartbeat(SubprocessMessage.Heartbeat heartbeat) {
        updateHeartbeat();
        if (heartbeat != null) {
            this.lastHeapUsagePercent = heartbeat.memoryUsagePercent();
            this.lastHeapUsedBytes = heartbeat.heapUsedBytes();
            this.lastHeapMaxBytes = heartbeat.heapMaxBytes();
            this.lastOffHeapUsagePercent = heartbeat.offHeapUsagePercent();
            this.lastOffHeapUsedBytes = heartbeat.offHeapUsedBytes();
            this.lastOffHeapMaxBytes = heartbeat.offHeapMaxBytes();
            this.lastGpuUsagePercent = heartbeat.gpuUsagePercent();
            this.lastGpuUsedBytes = heartbeat.gpuUsedBytes();
            this.lastGpuMaxBytes = heartbeat.gpuMaxBytes();
        }
    }

    public Duration timeSinceLastProgress() {
        Instant lp = lastProgress;
        if (lp == null) {
            return Duration.ZERO;
        }
        return Duration.between(lp, Instant.now());
    }

    public long getElapsedMs() {
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    public String getHeapSummary() {
        long max = lastHeapMaxBytes;
        long used = lastHeapUsedBytes;
        double pct = lastHeapUsagePercent;
        if (max <= 0) {
            return "unknown";
        }
        long usedMb = used / (1024 * 1024);
        long maxMb = max / (1024 * 1024);
        return String.format("%.1f%% (%d/%d MB)", pct, usedMb, maxMb);
    }

    public boolean isStale(Duration threshold) {
        if (lastHeartbeat == null)
            return false;
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(threshold) > 0;
    }

    public void cancel() {
        if (cancelled.getAndSet(true))
            return;
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    public void waitFor(Duration timeout) {
        try {
            process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Status getStatus() {
        return new Status(taskId, keywordIndexPath, vectorIndexPath,
                process.pid(), isAlive(), isCancelled(), isOomDetected(),
                currentPhase, progressPercent, lastMessage, startTime, lastHeartbeat,
                lastHeapUsagePercent, lastHeapUsedBytes, lastHeapMaxBytes,
                lastOffHeapUsagePercent, lastOffHeapUsedBytes, lastOffHeapMaxBytes,
                lastGpuUsagePercent, lastGpuUsedBytes, lastGpuMaxBytes);
    }

    public record Status(String taskId, String keywordIndexPath, String vectorIndexPath,
            long pid, boolean alive, boolean cancelled, boolean oomDetected,
            String currentPhase, int progressPercent, String lastMessage,
            Instant startTime, Instant lastHeartbeat,
            double heapUsagePercent, long heapUsedBytes, long heapMaxBytes,
            double offHeapUsagePercent, long offHeapUsedBytes, long offHeapMaxBytes,
            double gpuUsagePercent, long gpuUsedBytes, long gpuMaxBytes) {
    }
}
