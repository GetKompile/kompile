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

import ai.kompile.app.services.subprocess.SystemMemoryAnalyzer.MemoryStatus;
import ai.kompile.app.services.subprocess.SystemMemoryAnalyzer.ThreadAdjustment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages automatic restart of subprocesses after OOM failures.
 *
 * Tracks restart attempts per task, implements exponential backoff,
 * and coordinates with SystemMemoryAnalyzer to make memory-aware decisions.
 *
 * Key features:
 * - Track restart attempts and history per task
 * - Exponential backoff between restart attempts
 * - Memory-aware heap/thread adjustments
 * - Configurable max restart attempts
 * - Event emission for UI notifications
 */
@Service
public class SubprocessRestartManager {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessRestartManager.class);

    private final SystemMemoryAnalyzer memoryAnalyzer;
    private final SubprocessConfigService configService;

    // Track restart state per task
    private final Map<String, RestartState> restartStates = new ConcurrentHashMap<>();

    // Configuration with defaults
    @Value("${kompile.subprocess.restart.enabled:true}")
    private boolean restartEnabled;

    @Value("${kompile.subprocess.restart.max-attempts:3}")
    private int maxRestartAttempts;

    @Value("${kompile.subprocess.restart.initial-backoff-ms:5000}")
    private int initialBackoffMs;

    @Value("${kompile.subprocess.restart.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Value("${kompile.subprocess.restart.heap-increase-factor:1.25}")
    private double heapIncreaseFactor;

    @Value("${kompile.subprocess.restart.system-ram-safety-margin:0.15}")
    private double systemRamSafetyMargin;

    @Value("${kompile.subprocess.restart.thread-reduction-factor:0.5}")
    private double threadReductionFactor;

    @Value("${kompile.subprocess.restart.min-threads:1}")
    private int minThreads;

    @Autowired
    public SubprocessRestartManager(
            SystemMemoryAnalyzer memoryAnalyzer,
            @Autowired(required = false) SubprocessConfigService configService) {
        this.memoryAnalyzer = memoryAnalyzer;
        this.configService = configService;
        logger.info("SubprocessRestartManager initialized: maxAttempts={}, initialBackoff={}ms, " +
                        "backoffMultiplier={}, heapIncreaseFactor={}, safetyMargin={}",
                maxRestartAttempts, initialBackoffMs, backoffMultiplier, heapIncreaseFactor, systemRamSafetyMargin);
    }

    /**
     * Failure reasons that may trigger automatic restart.
     */
    public enum FailureReason {
        /** OutOfMemoryError detected in Java code */
        OUT_OF_MEMORY,
        /** Exit code 137 - killed by OOM killer */
        OOM_KILLED,
        /** GPU/CUDA out of memory detected */
        GPU_OUT_OF_MEMORY,
        /** High memory pressure warning before crash */
        MEMORY_PRESSURE,
        /** Native crash (SIGSEGV, SIGABRT, etc.) */
        NATIVE_CRASH,
        /** Process timed out */
        TIMEOUT,
        /** Process is alive but not sending heartbeats - potential deadlock */
        STALLED_NO_HEARTBEAT,
        /** Process is alive and sending heartbeats but no progress - potential deadlock in processing */
        STALLED_NO_PROGRESS,
        /** Batch size too large - array exceeds Integer.MAX_VALUE */
        BATCH_SIZE_TOO_LARGE,
        /** User cancelled */
        CANCELLED,
        /** Unknown error */
        UNKNOWN
    }

    /**
     * Determine failure reason from exit code and OOM detection.
     */
    public FailureReason categorizeFailure(int exitCode, boolean oomDetected) {
        if (oomDetected) {
            return FailureReason.OUT_OF_MEMORY;
        }
        return switch (exitCode) {
            case 137 -> FailureReason.OOM_KILLED;  // SIGKILL - often OOM killer
            case 134, 136, 139 -> FailureReason.NATIVE_CRASH;  // SIGABRT, SIGFPE, SIGSEGV
            case 130, 143 -> FailureReason.CANCELLED;  // SIGINT, SIGTERM
            default -> FailureReason.UNKNOWN;
        };
    }

    /**
     * Determine failure reason from exit code, OOM detection, and stderr analysis.
     * This overload detects GPU/CUDA OOM from stderr patterns.
     *
     * @param exitCode     The process exit code
     * @param oomDetected  True if Java OOM was detected
     * @param stderr       The stderr output from the subprocess
     * @return The categorized failure reason
     */
    public FailureReason categorizeFailure(int exitCode, boolean oomDetected, String stderr) {
        // First check for GPU OOM patterns in stderr
        if (stderr != null && !stderr.isEmpty() && containsGpuOomPattern(stderr)) {
            return FailureReason.GPU_OUT_OF_MEMORY;
        }

        // Fall back to standard categorization
        return categorizeFailure(exitCode, oomDetected);
    }

    /**
     * Check if stderr contains GPU/CUDA OOM patterns.
     */
    private boolean containsGpuOomPattern(String stderr) {
        String lower = stderr.toLowerCase();
        return lower.contains("cuda out of memory") ||
                lower.contains("cuda malloc failed") ||
                lower.contains("cublas_status_alloc_failed") ||
                lower.contains("out of memory") && (lower.contains("gpu") || lower.contains("cuda") || lower.contains("device")) ||
                lower.contains("nccl") && lower.contains("out of memory") ||
                lower.contains("could not allocate") && lower.contains("memory") && (lower.contains("gpu") || lower.contains("cuda"));
    }

    /**
     * Check if a failed task should be automatically restarted.
     *
     * @param taskId  The task identifier
     * @param reason  The failure reason
     * @return true if restart should be attempted
     */
    public boolean shouldRestart(String taskId, FailureReason reason) {
        if (!restartEnabled) {
            logger.debug("Restart disabled globally");
            return false;
        }

        // Restart for memory-related failures, batch size issues, and stall/deadlock scenarios
        boolean isRestartableReason = switch (reason) {
            case OUT_OF_MEMORY, OOM_KILLED, GPU_OUT_OF_MEMORY, MEMORY_PRESSURE, BATCH_SIZE_TOO_LARGE -> true;
            case STALLED_NO_HEARTBEAT, STALLED_NO_PROGRESS -> isRestartOnStall();
            case TIMEOUT -> isRestartOnTimeout();
            default -> false;
        };

        if (!isRestartableReason) {
            logger.debug("Failure reason {} is not eligible for automatic restart", reason);
            return false;
        }

        RestartState state = restartStates.computeIfAbsent(taskId, k -> new RestartState());

        if (state.attemptCount >= maxRestartAttempts) {
            logger.info("Task {} has exhausted all {} restart attempts", taskId, maxRestartAttempts);
            return false;
        }

        logger.info("Task {} eligible for restart due to {} (attempt {}/{})",
                taskId, reason, state.attemptCount + 1, maxRestartAttempts);
        return true;
    }

    /**
     * Check if manual restart is allowed for a task.
     */
    public boolean canManualRestart(String taskId) {
        // Manual restart is always allowed unless task is actively running
        return true;
    }

    /**
     * Get restart configuration with memory-aware adjustments.
     *
     * @param taskId             The task identifier
     * @param wasOomKilled       True if exit code was 137 (OOM killer)
     * @param currentHeapBytes   Current heap size in bytes
     * @param currentOffHeapBytes Current off-heap (JavaCPP) memory in bytes
     * @param currentOmpThreads  Current OMP_NUM_THREADS setting
     * @param currentBlasThreads Current OPENBLAS_NUM_THREADS setting
     * @param currentMaxThreads  Current max embedding threads
     * @param currentBatchSize   Current batch size
     * @return Restart configuration with adjustments
     */
    public RestartConfig getRestartConfig(
            String taskId,
            boolean wasOomKilled,
            long currentHeapBytes,
            long currentOffHeapBytes,
            int currentOmpThreads,
            int currentBlasThreads,
            int currentMaxThreads,
            int currentBatchSize) {

        RestartState state = restartStates.computeIfAbsent(taskId, k -> new RestartState());

        // Analyze memory situation (includes both heap and off-heap)
        MemoryStatus memStatus = memoryAnalyzer.analyzeForRestart(
                currentHeapBytes,
                currentOffHeapBytes,
                heapIncreaseFactor,
                systemRamSafetyMargin,
                wasOomKilled,
                currentOmpThreads,
                currentBlasThreads,
                currentMaxThreads,
                currentBatchSize
        );

        // Log the full analysis
        logger.info("Memory analysis for task {}: {}", taskId, memStatus.reason());
        logger.info("Memory analysis details: heap {} -> {}, off-heap {} -> {}",
                SystemMemoryAnalyzer.formatBytes(currentHeapBytes),
                SystemMemoryAnalyzer.formatBytes(memStatus.recommendedHeapBytes()),
                SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes),
                SystemMemoryAnalyzer.formatBytes(memStatus.recommendedOffHeapBytes()));

        // Calculate backoff with exponential increase
        long backoffMs = calculateBackoff(state.attemptCount);

        ThreadAdjustment threadAdj = memStatus.threadAdjustment();

        // Build restart config with off-heap settings
        return new RestartConfig(
                taskId,
                state.attemptCount + 1,
                maxRestartAttempts,
                backoffMs,
                SystemMemoryAnalyzer.bytesToHeapSize(memStatus.recommendedHeapBytes()),
                memStatus.recommendedHeapBytes(),
                memStatus.recommendedOffHeapBytes(),
                threadAdj != null ? threadAdj.recommendedOmpThreads() : currentOmpThreads,
                threadAdj != null ? threadAdj.recommendedOpenBlasThreads() : currentBlasThreads,
                threadAdj != null ? threadAdj.recommendedMaxThreads() : currentMaxThreads,
                threadAdj != null ? threadAdj.recommendedBatchSize() : currentBatchSize,
                memStatus.canIncreaseHeap(),
                memStatus.shouldReduceOffHeap(),
                memStatus
        );
    }

    /**
     * Get restart configuration with memory-aware adjustments and failure-specific handling.
     * This overload allows specifying the failure reason for targeted adjustments like
     * aggressive batch size reduction for BATCH_SIZE_TOO_LARGE errors.
     *
     * @param taskId             The task identifier
     * @param failureReason      The reason for the failure
     * @param wasOomKilled       True if exit code was 137 (OOM killer)
     * @param currentHeapBytes   Current heap size in bytes
     * @param currentOffHeapBytes Current off-heap (JavaCPP) memory in bytes
     * @param currentOmpThreads  Current OMP_NUM_THREADS setting
     * @param currentBlasThreads Current OPENBLAS_NUM_THREADS setting
     * @param currentMaxThreads  Current max embedding threads
     * @param currentBatchSize   Current batch size
     * @return Restart configuration with adjustments
     */
    public RestartConfig getRestartConfig(
            String taskId,
            FailureReason failureReason,
            boolean wasOomKilled,
            long currentHeapBytes,
            long currentOffHeapBytes,
            int currentOmpThreads,
            int currentBlasThreads,
            int currentMaxThreads,
            int currentBatchSize) {

        // For BATCH_SIZE_TOO_LARGE or GPU_OUT_OF_MEMORY, aggressively reduce batch size BEFORE memory analysis
        int adjustedBatchSize = currentBatchSize;
        if (failureReason == FailureReason.BATCH_SIZE_TOO_LARGE) {
            // Cut batch size to 25% (divide by 4) - this is aggressive but necessary
            // because the matrix multiplication creates arrays of size batch * sequence_length * hidden_dim
            adjustedBatchSize = Math.max(1, currentBatchSize / 4);
            logger.warn("BATCH_SIZE_TOO_LARGE detected for task {} - reducing batch size from {} to {}",
                    taskId, currentBatchSize, adjustedBatchSize);
        } else if (failureReason == FailureReason.GPU_OUT_OF_MEMORY) {
            // GPU OOM - aggressively reduce batch size since VRAM is the constraint
            // GPU memory is shared and less forgiving than heap, so we cut to 25%
            adjustedBatchSize = Math.max(1, currentBatchSize / 4);
            logger.warn("GPU_OUT_OF_MEMORY detected for task {} - aggressively reducing batch size from {} to {}",
                    taskId, currentBatchSize, adjustedBatchSize);
        }

        RestartState state = restartStates.computeIfAbsent(taskId, k -> new RestartState());

        // Analyze memory situation (includes both heap and off-heap)
        MemoryStatus memStatus = memoryAnalyzer.analyzeForRestart(
                currentHeapBytes,
                currentOffHeapBytes,
                heapIncreaseFactor,
                systemRamSafetyMargin,
                wasOomKilled,
                currentOmpThreads,
                currentBlasThreads,
                currentMaxThreads,
                adjustedBatchSize  // Use the adjusted batch size
        );

        // Log the full analysis
        String reasonStr = switch (failureReason) {
            case BATCH_SIZE_TOO_LARGE -> "BATCH_SIZE_TOO_LARGE - aggressive batch reduction applied";
            case GPU_OUT_OF_MEMORY -> "GPU_OUT_OF_MEMORY - aggressive batch reduction applied (VRAM constraint)";
            default -> memStatus.reason();
        };
        logger.info("Memory analysis for task {} ({}): {}", taskId, failureReason, reasonStr);
        logger.info("Memory analysis details: heap {} -> {}, off-heap {} -> {}, batch {} -> {}",
                SystemMemoryAnalyzer.formatBytes(currentHeapBytes),
                SystemMemoryAnalyzer.formatBytes(memStatus.recommendedHeapBytes()),
                SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes),
                SystemMemoryAnalyzer.formatBytes(memStatus.recommendedOffHeapBytes()),
                currentBatchSize,
                adjustedBatchSize);

        // Calculate backoff with exponential increase
        long backoffMs = calculateBackoff(state.attemptCount);

        ThreadAdjustment threadAdj = memStatus.threadAdjustment();

        // For BATCH_SIZE_TOO_LARGE or GPU_OUT_OF_MEMORY, use the aggressively reduced batch size
        int finalBatchSize = (failureReason == FailureReason.BATCH_SIZE_TOO_LARGE || failureReason == FailureReason.GPU_OUT_OF_MEMORY)
                ? adjustedBatchSize
                : (threadAdj != null ? threadAdj.recommendedBatchSize() : currentBatchSize);

        // Build restart config
        return new RestartConfig(
                taskId,
                state.attemptCount + 1,
                maxRestartAttempts,
                backoffMs,
                SystemMemoryAnalyzer.bytesToHeapSize(memStatus.recommendedHeapBytes()),
                memStatus.recommendedHeapBytes(),
                memStatus.recommendedOffHeapBytes(),
                threadAdj != null ? threadAdj.recommendedOmpThreads() : currentOmpThreads,
                threadAdj != null ? threadAdj.recommendedOpenBlasThreads() : currentBlasThreads,
                threadAdj != null ? threadAdj.recommendedMaxThreads() : currentMaxThreads,
                finalBatchSize,
                memStatus.canIncreaseHeap(),
                memStatus.shouldReduceOffHeap(),
                memStatus
        );
    }

    /**
     * Record that a restart attempt was made.
     *
     * @param taskId  The task identifier
     * @param success True if subprocess started successfully
     */
    public void recordRestartAttempt(String taskId, boolean success) {
        RestartState state = restartStates.computeIfAbsent(taskId, k -> new RestartState());
        state.attemptCount++;
        state.lastAttemptTime = Instant.now();
        state.lastAttemptSuccess = success;

        logger.info("Recorded restart attempt for task {}: attempt={}, success={}",
                taskId, state.attemptCount, success);
    }

    /**
     * Record that a restart was successful (task completed after restart).
     */
    public void recordRestartSuccess(String taskId) {
        RestartState state = restartStates.get(taskId);
        if (state != null) {
            state.recoverySuccessful = true;
            logger.info("Task {} recovered successfully after {} restart attempt(s)", taskId, state.attemptCount);
        }
    }

    /**
     * Clear restart state for a task (e.g., on completion or final failure).
     */
    public void clearRestartState(String taskId) {
        RestartState removed = restartStates.remove(taskId);
        if (removed != null) {
            logger.debug("Cleared restart state for task {}: {} attempts made", taskId, removed.attemptCount);
        }
    }

    /**
     * Get the current restart state for a task.
     */
    public RestartStatus getRestartStatus(String taskId) {
        RestartState state = restartStates.get(taskId);
        if (state == null) {
            return new RestartStatus(taskId, 0, maxRestartAttempts, false, null, false);
        }
        return new RestartStatus(
                taskId,
                state.attemptCount,
                maxRestartAttempts,
                state.lastAttemptSuccess,
                state.lastAttemptTime,
                state.recoverySuccessful
        );
    }

    /**
     * Reset restart counts for all tasks (e.g., for testing).
     */
    public void resetAllRestartStates() {
        int count = restartStates.size();
        restartStates.clear();
        logger.info("Reset restart states for {} tasks", count);
    }

    /**
     * Calculate backoff time with exponential increase.
     */
    private long calculateBackoff(int attemptCount) {
        if (attemptCount <= 0) {
            return initialBackoffMs;
        }
        // Exponential backoff: initial * multiplier^attempt
        return (long) (initialBackoffMs * Math.pow(backoffMultiplier, attemptCount));
    }

    // ========== Configuration Getters ==========

    public boolean isRestartEnabled() {
        return restartEnabled;
    }

    public int getMaxRestartAttempts() {
        return maxRestartAttempts;
    }

    public int getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public double getHeapIncreaseFactor() {
        return heapIncreaseFactor;
    }

    public double getSystemRamSafetyMargin() {
        return systemRamSafetyMargin;
    }

    public boolean isRestartOnStall() {
        return configService != null ? configService.isRestartOnStall() : true;
    }

    public boolean isRestartOnTimeout() {
        return configService != null ? configService.isRestartOnTimeout() : true;
    }

    public int getStallDetectionThresholdSeconds() {
        return configService != null ? configService.getStallDetectionThresholdSeconds() : 300;
    }

    // ========== Configuration Setters ==========

    public void setRestartEnabled(boolean enabled) {
        this.restartEnabled = enabled;
        logger.info("Restart enabled set to: {}", enabled);
    }

    public void setMaxRestartAttempts(int max) {
        this.maxRestartAttempts = max;
        logger.info("Max restart attempts set to: {}", max);
    }

    // ========== Inner Classes ==========

    /**
     * Internal state tracking for restart attempts.
     */
    private static class RestartState {
        int attemptCount = 0;
        Instant lastAttemptTime = null;
        boolean lastAttemptSuccess = false;
        boolean recoverySuccessful = false;
    }

    /**
     * Configuration for a restart attempt.
     */
    public record RestartConfig(
            String taskId,
            int attemptNumber,
            int maxAttempts,
            long backoffMs,
            String heapSize,
            long heapSizeBytes,
            long offHeapBytes,
            int ompNumThreads,
            int openBlasNumThreads,
            int maxThreads,
            int batchSize,
            boolean heapIncreased,
            boolean offHeapReduced,
            MemoryStatus memoryAnalysis
    ) {
        /**
         * Get a summary message for logging/display.
         */
        public String getSummary() {
            return String.format(
                    "Restart %d/%d in %dms: heap=%s (%s), off-heap=%s (%s), OMP=%d, BLAS=%d, maxThreads=%d, batch=%d",
                    attemptNumber, maxAttempts, backoffMs, heapSize,
                    heapIncreased ? "increased" : "unchanged",
                    SystemMemoryAnalyzer.formatBytes(offHeapBytes),
                    offHeapReduced ? "REDUCED" : "unchanged",
                    ompNumThreads, openBlasNumThreads, maxThreads, batchSize
            );
        }
    }

    /**
     * Status of restart attempts for a task.
     */
    public record RestartStatus(
            String taskId,
            int attemptsMade,
            int maxAttempts,
            boolean lastAttemptSuccess,
            Instant lastAttemptTime,
            boolean recoverySuccessful
    ) {
        public boolean hasAttemptsRemaining() {
            return attemptsMade < maxAttempts;
        }

        public int attemptsRemaining() {
            return Math.max(0, maxAttempts - attemptsMade);
        }
    }
}
