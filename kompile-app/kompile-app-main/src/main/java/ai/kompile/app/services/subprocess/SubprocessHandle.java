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

import ai.kompile.app.subprocess.SubprocessMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the state and lifecycle of a subprocess running an ingest job.
 *
 * This class maintains:
 * - Process reference and PID
 * - stdout/stderr reader threads
 * - Current phase and progress
 * - Last heartbeat time for liveness detection
 * - Cancellation state
 * - Result future for async completion
 */
public class SubprocessHandle {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessHandle.class);

    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
    private static final int FORCE_KILL_TIMEOUT_SECONDS = 5;

    private final String taskId;
    private final String fileName;
    private final Process process;
    private final Thread stdoutReader;
    private final Thread stderrReader;
    private final CompletableFuture<SubprocessResult> resultFuture;
    private final Path argsFile;
    private final Instant startTime;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean oomDetected = new AtomicBoolean(false);

    private volatile Instant lastHeartbeat;
    private volatile String currentPhase = "STARTING";
    private volatile int progressPercent = 0;
    private volatile String lastMessage = "";

    /**
     * Create a new subprocess handle.
     *
     * @param taskId Task identifier
     * @param fileName File being processed
     * @param process The subprocess
     * @param stdoutReader Thread reading stdout
     * @param stderrReader Thread reading stderr
     * @param resultFuture Future to complete when process exits
     * @param argsFile Temporary args file to clean up
     */
    public SubprocessHandle(
            String taskId,
            String fileName,
            Process process,
            Thread stdoutReader,
            Thread stderrReader,
            CompletableFuture<SubprocessResult> resultFuture,
            Path argsFile
    ) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.process = process;
        this.stdoutReader = stdoutReader;
        this.stderrReader = stderrReader;
        this.resultFuture = resultFuture;
        this.argsFile = argsFile;
        this.startTime = Instant.now();
        this.lastHeartbeat = Instant.now();
    }

    /**
     * Get the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Get the file name being processed.
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Get the process PID.
     */
    public long getPid() {
        return process.pid();
    }

    /**
     * Check if the process is still running.
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Get the start time.
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Get the elapsed time since start.
     */
    public Duration getElapsedTime() {
        return Duration.between(startTime, Instant.now());
    }

    /**
     * Get the last heartbeat time.
     */
    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    /**
     * Update the last heartbeat time.
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    /**
     * Check if the process is stale (no heartbeat for specified duration).
     *
     * @param threshold Duration after which process is considered stale
     * @return true if stale
     */
    public boolean isStale(Duration threshold) {
        if (lastHeartbeat == null) {
            return false;
        }
        return Duration.between(lastHeartbeat, Instant.now()).compareTo(threshold) > 0;
    }

    /**
     * Get the current phase.
     */
    public String getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Update the current phase.
     */
    public void setCurrentPhase(String phase) {
        this.currentPhase = phase;
    }

    /**
     * Get the current progress percentage.
     */
    public int getProgressPercent() {
        return progressPercent;
    }

    /**
     * Update progress.
     */
    public void updateProgress(String phase, int percent, String message) {
        this.currentPhase = phase;
        this.progressPercent = percent;
        this.lastMessage = message;
        updateHeartbeat();
    }

    /**
     * Get the last status message.
     */
    public String getLastMessage() {
        return lastMessage;
    }

    /**
     * Check if process was cancelled.
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Mark that OOM was detected in stderr.
     */
    public void setOomDetected(boolean detected) {
        oomDetected.set(detected);
    }

    /**
     * Check if OOM was detected.
     */
    public boolean isOomDetected() {
        return oomDetected.get();
    }

    /**
     * Get the result future.
     */
    public CompletableFuture<SubprocessResult> getResultFuture() {
        return resultFuture;
    }

    /**
     * Get the args file path (for cleanup).
     */
    public Path getArgsFile() {
        return argsFile;
    }

    /**
     * Cancel the subprocess.
     * Attempts graceful shutdown first, then force kills if necessary.
     */
    public void cancel() {
        if (cancelled.getAndSet(true)) {
            // Already cancelled
            return;
        }

        logger.info("Cancelling subprocess for task: {} (PID: {})", taskId, getPid());

        if (!process.isAlive()) {
            logger.info("Process already terminated");
            return;
        }

        // First, try graceful shutdown via SIGTERM
        process.destroy();

        try {
            // Wait for process to terminate
            boolean terminated = process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!terminated && process.isAlive()) {
                // Force kill if graceful shutdown failed
                logger.warn("Process {} did not terminate gracefully, forcing shutdown", taskId);
                process.destroyForcibly();
                process.waitFor(FORCE_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while cancelling subprocess {}", taskId);
            process.destroyForcibly();
        }

        // Interrupt stream readers
        if (stdoutReader != null && stdoutReader.isAlive()) {
            stdoutReader.interrupt();
        }
        if (stderrReader != null && stderrReader.isAlive()) {
            stderrReader.interrupt();
        }

        logger.info("Subprocess {} cancelled", taskId);
    }

    /**
     * Wait for the process to complete.
     *
     * @param timeout Maximum wait time
     * @return Exit code, or -1 if timeout
     */
    public int waitFor(Duration timeout) {
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (completed) {
                return process.exitValue();
            }
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Get current status as a snapshot.
     */
    public SubprocessStatus getStatus() {
        return new SubprocessStatus(
            taskId,
            fileName,
            getPid(),
            isAlive(),
            isCancelled(),
            isOomDetected(),
            currentPhase,
            progressPercent,
            lastMessage,
            startTime,
            lastHeartbeat,
            getElapsedTime()
        );
    }

    /**
     * Status snapshot record.
     */
    public record SubprocessStatus(
        String taskId,
        String fileName,
        long pid,
        boolean alive,
        boolean cancelled,
        boolean oomDetected,
        String currentPhase,
        int progressPercent,
        String lastMessage,
        Instant startTime,
        Instant lastHeartbeat,
        Duration elapsedTime
    ) {}

    /**
     * Result of subprocess execution.
     */
    public record SubprocessResult(
        String taskId,
        boolean success,
        int exitCode,
        int documentsLoaded,
        int chunksCreated,
        int chunksEmbedded,
        int documentsIndexed,
        long totalDurationMs,
        String indexPath,
        String errorMessage,
        String errorPhase,
        boolean cancelled,
        boolean oomKilled
    ) {
        public static SubprocessResult success(String taskId, SubprocessMessage.Completed completed) {
            return new SubprocessResult(
                taskId,
                true,
                0,
                completed.documentsLoaded(),
                completed.chunksCreated(),
                completed.chunksEmbedded(),
                completed.documentsIndexed(),
                completed.totalDurationMs(),
                completed.indexPath(),
                null,
                null,
                false,
                false
            );
        }

        public static SubprocessResult failure(String taskId, int exitCode, String errorMessage,
                                               String errorPhase, boolean cancelled, boolean oomKilled) {
            return new SubprocessResult(
                taskId,
                false,
                exitCode,
                0, 0, 0, 0, 0,
                null,
                errorMessage,
                errorPhase,
                cancelled,
                oomKilled
            );
        }
    }
}
