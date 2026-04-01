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

package ai.kompile.app.services.pipeline;

import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Adaptive memory recovery system for handling OutOfMemoryError during embedding.
 *
 * <h2>When OOM Occurs, This System:</h2>
 * <ol>
 *   <li>STOPS the current batch processing immediately</li>
 *   <li>REDUCES batch size by 50% (down to minimum of 1)</li>
 *   <li>REDUCES ND4J/OpenMP threads to conserve memory</li>
 *   <li>NOTIFIES the user about the adaptation</li>
 *   <li>RETRIES the failed batch with reduced settings</li>
 * </ol>
 *
 * <h2>Recovery Strategy:</h2>
 * <pre>
 * OOM Count | Batch Size Reduction | Thread Reduction | Action
 * ----------|---------------------|------------------|--------
 * 1st OOM   | 50%                 | 50%              | Reduce and retry
 * 2nd OOM   | 75%                 | 75%              | Aggressive reduction
 * 3rd OOM   | 87.5% (min 1)       | 1 thread         | Minimal resources
 * 4th+ OOM  | FAIL                | N/A              | Cannot recover
 * </pre>
 */
public class AdaptiveMemoryRecovery {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveMemoryRecovery.class);

    // Maximum recovery attempts before giving up
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    // Minimum batch size (cannot go below this)
    // Must match AdaptiveRecoverySettings.MIN_BATCH_SIZE for consistency
    private static final int ABSOLUTE_MIN_BATCH_SIZE = 4;

    // Minimum threads (cannot go below this)
    private static final int ABSOLUTE_MIN_THREADS = 1;

    // Memory thresholds
    private static final double MEMORY_PRESSURE_THRESHOLD = 0.70;
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.85;

    // State tracking
    private final AtomicInteger oomCount = new AtomicInteger(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger();
    private final AtomicInteger currentOmpThreads = new AtomicInteger();
    private final AtomicInteger currentMaxThreads = new AtomicInteger();
    private final int originalBatchSize;
    private final int originalOmpThreads;
    private final int originalMaxThreads;

    // Progress callback for user notifications
    private Consumer<String> userNotificationCallback;

    /**
     * Creates an adaptive memory recovery handler.
     *
     * @param initialBatchSize The initial batch size before any OOM
     */
    public AdaptiveMemoryRecovery(int initialBatchSize) {
        this.originalBatchSize = initialBatchSize;
        this.currentBatchSize.set(initialBatchSize);

        // Get current ND4J thread settings
        int ompThreads = 4; // default
        int maxThreads = 4; // default
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            // Note: omp_get_num_threads returns 1 outside parallel regions, use configured value
            maxThreads = (int) Nd4j.getEnvironment().maxThreads();
            ompThreads = maxThreads; // Use same as max threads for now
        } catch (Exception e) {
            logger.debug("Could not read ND4J thread settings, using defaults");
        }

        this.originalOmpThreads = ompThreads;
        this.originalMaxThreads = maxThreads;
        this.currentOmpThreads.set(ompThreads);
        this.currentMaxThreads.set(maxThreads);

        logger.info("AdaptiveMemoryRecovery initialized: batchSize={}, ompThreads={}, maxThreads={}",
                initialBatchSize, ompThreads, maxThreads);
    }

    /**
     * Sets a callback for user notifications.
     * The callback receives a message string that should be shown to the user.
     */
    public void setUserNotificationCallback(Consumer<String> callback) {
        this.userNotificationCallback = callback;
    }

    /**
     * Handles an OOM error and adapts settings for retry.
     *
     * @param batchSize The batch size that caused OOM
     * @param oom The OutOfMemoryError that occurred
     * @return RecoveryAction indicating whether to retry and with what settings
     */
    public RecoveryAction handleOOM(int batchSize, OutOfMemoryError oom) {
        int attempts = oomCount.incrementAndGet();

        // Log the OOM with full details
        logger.error("====================================================================");
        logger.error("OUT OF MEMORY ERROR #{} DETECTED", attempts);
        logger.error("====================================================================");
        logger.error("Batch size at failure: {}", batchSize);
        logger.error("Memory state: {}", getMemoryStatus());
        logger.error("OOM message: {}", oom.getMessage());

        // Try to free some memory immediately
        System.gc();
        try {
            Thread.sleep(500); // Give GC time to run
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check if we've exceeded max recovery attempts
        if (attempts > MAX_RECOVERY_ATTEMPTS) {
            String failMsg = String.format(
                    "FATAL: Failed to recover from OOM after %d attempts. " +
                    "Batch size reduced from %d to %d, threads from %d to %d. " +
                    "Increase heap size (-Xmx) or use a smaller embedding model.",
                    attempts, originalBatchSize, currentBatchSize.get(),
                    originalMaxThreads, currentMaxThreads.get());

            notifyUser("FATAL OOM: " + failMsg);
            logger.error(failMsg);

            return RecoveryAction.fail(failMsg);
        }

        // Calculate new reduced settings
        int newBatchSize = calculateReducedBatchSize(attempts);
        int newOmpThreads = calculateReducedThreads(attempts);
        int newMaxThreads = calculateReducedMaxThreads(attempts);

        // Apply the reduced settings
        applyReducedSettings(newBatchSize, newOmpThreads, newMaxThreads);

        // Build notification message
        String notification = String.format(
                "[MEMORY ADAPTATION] OOM Recovery #%d: Reducing batch size %d→%d, " +
                "ND4J threads %d→%d, OMP threads %d→%d. Retrying...",
                attempts,
                currentBatchSize.get(), newBatchSize,
                currentMaxThreads.get(), newMaxThreads,
                currentOmpThreads.get(), newOmpThreads);

        // Update current values
        currentBatchSize.set(newBatchSize);
        currentOmpThreads.set(newOmpThreads);
        currentMaxThreads.set(newMaxThreads);

        // Notify user
        notifyUser(notification);
        logger.warn(notification);

        // Log recovery plan
        logger.info("Recovery plan:");
        logger.info("  - Batch size: {} → {}", batchSize, newBatchSize);
        logger.info("  - OMP threads: {} → {}", originalOmpThreads, newOmpThreads);
        logger.info("  - Max threads: {} → {}", originalMaxThreads, newMaxThreads);
        logger.info("  - Memory after GC: {}", getMemoryStatus());

        return RecoveryAction.retry(newBatchSize, newOmpThreads, newMaxThreads);
    }

    /**
     * Applies reduced settings to the ND4J environment.
     */
    private void applyReducedSettings(int newBatchSize, int newOmpThreads, int newMaxThreads) {
        try {
            // Set ND4J max threads
            Nd4j.getEnvironment().setMaxThreads(newMaxThreads);
            Nd4j.getEnvironment().setMaxMasterThreads(newMaxThreads);

            // Set OpenMP threads via NativeOps
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.setOmpNumThreads(newOmpThreads);

            logger.info("Applied reduced ND4J settings: maxThreads={}, ompThreads={}",
                    newMaxThreads, newOmpThreads);

        } catch (Exception e) {
            logger.warn("Could not apply all ND4J thread reductions: {}", e.getMessage());
        }
    }

    /**
     * Calculates reduced batch size based on OOM attempt number.
     */
    private int calculateReducedBatchSize(int attempts) {
        int current = currentBatchSize.get();
        int reduced;

        switch (attempts) {
            case 1:
                // First OOM: reduce by 50%
                reduced = Math.max(ABSOLUTE_MIN_BATCH_SIZE, current / 2);
                break;
            case 2:
                // Second OOM: reduce to 25% of original
                reduced = Math.max(ABSOLUTE_MIN_BATCH_SIZE, originalBatchSize / 4);
                break;
            case 3:
                // Third OOM: go to absolute minimum
                reduced = ABSOLUTE_MIN_BATCH_SIZE;
                break;
            default:
                reduced = ABSOLUTE_MIN_BATCH_SIZE;
        }

        return reduced;
    }

    /**
     * Calculates reduced OMP thread count based on OOM attempt number.
     */
    private int calculateReducedThreads(int attempts) {
        int current = currentOmpThreads.get();
        int reduced;

        switch (attempts) {
            case 1:
                // First OOM: reduce by 50%
                reduced = Math.max(ABSOLUTE_MIN_THREADS, current / 2);
                break;
            case 2:
                // Second OOM: reduce to 25%
                reduced = Math.max(ABSOLUTE_MIN_THREADS, originalOmpThreads / 4);
                break;
            case 3:
                // Third OOM: single thread
                reduced = ABSOLUTE_MIN_THREADS;
                break;
            default:
                reduced = ABSOLUTE_MIN_THREADS;
        }

        return reduced;
    }

    /**
     * Calculates reduced max thread count based on OOM attempt number.
     */
    private int calculateReducedMaxThreads(int attempts) {
        int current = currentMaxThreads.get();
        int reduced;

        switch (attempts) {
            case 1:
                // First OOM: reduce by 50%
                reduced = Math.max(ABSOLUTE_MIN_THREADS, current / 2);
                break;
            case 2:
                // Second OOM: reduce to 25%
                reduced = Math.max(ABSOLUTE_MIN_THREADS, originalMaxThreads / 4);
                break;
            case 3:
                // Third OOM: single thread
                reduced = ABSOLUTE_MIN_THREADS;
                break;
            default:
                reduced = ABSOLUTE_MIN_THREADS;
        }

        return reduced;
    }

    /**
     * Gets the current effective batch size (after any reductions).
     */
    public int getCurrentBatchSize() {
        return currentBatchSize.get();
    }

    /**
     * Gets the number of OOM recoveries that have occurred.
     */
    public int getOomCount() {
        return oomCount.get();
    }

    /**
     * Checks if we're under memory pressure and proactively reduces settings.
     * Call this before starting a batch to prevent OOM.
     *
     * @return true if settings were reduced proactively
     */
    public boolean checkAndReduceIfNeeded() {
        double memoryUsage = getMemoryUsagePercent();

        if (memoryUsage >= MEMORY_CRITICAL_THRESHOLD) {
            // Critical memory - reduce aggressively
            int newBatchSize = Math.max(ABSOLUTE_MIN_BATCH_SIZE, currentBatchSize.get() / 2);
            if (newBatchSize < currentBatchSize.get()) {
                String msg = String.format(
                        "[PROACTIVE MEMORY REDUCTION] Memory at %.1f%% (critical). " +
                        "Reducing batch size %d→%d to prevent OOM.",
                        memoryUsage * 100, currentBatchSize.get(), newBatchSize);
                notifyUser(msg);
                logger.warn(msg);
                currentBatchSize.set(newBatchSize);
                return true;
            }
        } else if (memoryUsage >= MEMORY_PRESSURE_THRESHOLD) {
            // Under pressure - reduce moderately
            int newBatchSize = Math.max(ABSOLUTE_MIN_BATCH_SIZE, (int)(currentBatchSize.get() * 0.75));
            if (newBatchSize < currentBatchSize.get()) {
                String msg = String.format(
                        "[PROACTIVE MEMORY REDUCTION] Memory at %.1f%% (high). " +
                        "Reducing batch size %d→%d to prevent OOM.",
                        memoryUsage * 100, currentBatchSize.get(), newBatchSize);
                logger.info(msg);
                currentBatchSize.set(newBatchSize);
                return true;
            }
        }

        return false;
    }

    /**
     * Resets recovery state (call when pipeline completes successfully).
     */
    public void reset() {
        oomCount.set(0);
        currentBatchSize.set(originalBatchSize);
        currentOmpThreads.set(originalOmpThreads);
        currentMaxThreads.set(originalMaxThreads);

        // Restore original ND4J settings
        try {
            Nd4j.getEnvironment().setMaxThreads(originalMaxThreads);
            Nd4j.getEnvironment().setMaxMasterThreads(originalMaxThreads);
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.setOmpNumThreads(originalOmpThreads);
            logger.info("Reset ND4J settings to original: maxThreads={}, ompThreads={}",
                    originalMaxThreads, originalOmpThreads);
        } catch (Exception e) {
            logger.warn("Could not restore original ND4J settings: {}", e.getMessage());
        }
    }

    /**
     * Gets current memory usage as a percentage (0.0 to 1.0).
     */
    private double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (double) usedMemory / maxMemory;
    }

    /**
     * Gets a human-readable memory status string.
     */
    private String getMemoryStatus() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double usedPercent = (double) usedMemory / maxMemory * 100;

        return String.format("Used: %dMB / %dMB (%.1f%%), Free: %dMB",
                usedMemory / (1024 * 1024),
                maxMemory / (1024 * 1024),
                usedPercent,
                freeMemory / (1024 * 1024));
    }

    /**
     * Notifies the user about a memory-related event.
     */
    private void notifyUser(String message) {
        // Always log to stderr for immediate visibility
        System.err.println("[AdaptiveMemoryRecovery] " + message);
        System.err.flush();

        // Call the callback if set
        if (userNotificationCallback != null) {
            try {
                userNotificationCallback.accept(message);
            } catch (Exception e) {
                logger.debug("User notification callback failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Result of OOM handling - indicates whether to retry and with what settings.
     */
    public static class RecoveryAction {
        private final boolean shouldRetry;
        private final int newBatchSize;
        private final int newOmpThreads;
        private final int newMaxThreads;
        private final String failureReason;

        private RecoveryAction(boolean shouldRetry, int newBatchSize, int newOmpThreads,
                              int newMaxThreads, String failureReason) {
            this.shouldRetry = shouldRetry;
            this.newBatchSize = newBatchSize;
            this.newOmpThreads = newOmpThreads;
            this.newMaxThreads = newMaxThreads;
            this.failureReason = failureReason;
        }

        public static RecoveryAction retry(int newBatchSize, int newOmpThreads, int newMaxThreads) {
            return new RecoveryAction(true, newBatchSize, newOmpThreads, newMaxThreads, null);
        }

        public static RecoveryAction fail(String reason) {
            return new RecoveryAction(false, 0, 0, 0, reason);
        }

        public boolean shouldRetry() { return shouldRetry; }
        public int getNewBatchSize() { return newBatchSize; }
        public int getNewOmpThreads() { return newOmpThreads; }
        public int getNewMaxThreads() { return newMaxThreads; }
        public String getFailureReason() { return failureReason; }
    }
}
