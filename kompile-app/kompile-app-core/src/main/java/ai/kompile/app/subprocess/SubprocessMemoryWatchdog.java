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

package ai.kompile.app.subprocess;

import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Memory watchdog for subprocess ingest jobs.
 *
 * This runs inside the subprocess JVM and monitors memory usage.
 * When memory exceeds configured thresholds, it sets flags that the
 * pipeline can check to gracefully terminate before OOM.
 *
 * <p>Unlike the parent's MemoryWatchdogService which manages multiple jobs,
 * this watchdog is specific to a single subprocess and provides:
 * <ul>
 *   <li>Graceful stop flag - signals pipeline to stop accepting new work</li>
 *   <li>Kill flag - signals pipeline to terminate immediately</li>
 *   <li>Memory info reporting for progress updates</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * SubprocessMemoryWatchdog watchdog = new SubprocessMemoryWatchdog(
 *     80,  // graceful stop at 80%
 *     90,  // critical at 90% (GC hint)
 *     95,  // kill at 95%
 *     2000 // check every 2 seconds
 * );
 * watchdog.start();
 *
 * // In pipeline loop:
 * if (watchdog.shouldKill()) {
 *     // Terminate immediately
 * } else if (watchdog.shouldStop()) {
 *     // Stop accepting new work, drain current
 * }
 *
 * watchdog.stop();
 * </pre>
 */
public class SubprocessMemoryWatchdog implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessMemoryWatchdog.class);

    private final int memoryThresholdPercent;
    private final int memoryCriticalPercent;
    private final int memoryKillThresholdPercent;
    private final long checkIntervalMs;

    // GPU memory monitoring fields
    private final boolean gpuMonitoringEnabled;
    private final int gpuDeviceId;  // Primary device (current thread)
    private final int gpuDeviceCount;  // Total available GPUs
    private final int gpuMemoryThresholdPercent;
    private final int gpuMemoryCriticalPercent;
    private final int gpuMemoryKillThresholdPercent;

    // Memory velocity tracking (rate of memory increase)
    private volatile double heapVelocityPercentPerSecond = 0.0;
    private volatile double gpuVelocityPercentPerSecond = 0.0;
    private volatile long lastVelocityCheckTimeMs = 0;
    private volatile double lastHeapUsagePercent = 0.0;
    private volatile double lastGpuUsagePercent = 0.0;

    // Velocity-based early warning
    private final AtomicBoolean rapidMemoryGrowth = new AtomicBoolean(false);
    private static final double RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND = 5.0; // 5% per second is dangerous

    // State flags
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean shouldKill = new AtomicBoolean(false);
    private final AtomicBoolean criticalMemory = new AtomicBoolean(false);

    // Debouncing counters (require consecutive checks before triggering)
    private final AtomicInteger consecutiveStopChecks = new AtomicInteger(0);
    private final AtomicInteger consecutiveKillChecks = new AtomicInteger(0);
    // GPU-specific debounce counters
    private final AtomicInteger consecutiveGpuStopChecks = new AtomicInteger(0);
    private final AtomicInteger consecutiveGpuKillChecks = new AtomicInteger(0);

    // Required consecutive checks before triggering
    private static final int STOP_DEBOUNCE_COUNT = 3;
    private static final int KILL_DEBOUNCE_COUNT = 2;

    // Executor for background monitoring
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> monitoringTask;
    private volatile boolean running = false;

    // Last recorded memory info
    private volatile MemorySnapshot lastSnapshot;

    /**
     * Create a memory watchdog with specified thresholds for both heap and GPU.
     *
     * @param memoryThresholdPercent        Percentage at which to signal graceful stop (0-100)
     * @param memoryCriticalPercent         Percentage at which to trigger GC and warn (0-100)
     * @param memoryKillThresholdPercent    Percentage at which to signal immediate termination (0-100, 0 to disable)
     * @param checkIntervalMs               How often to check memory in milliseconds
     * @param gpuMemoryThresholdPercent     GPU threshold for graceful stop (0-100, default 75)
     * @param gpuMemoryCriticalPercent      GPU threshold for critical warning (0-100, default 85)
     * @param gpuMemoryKillThresholdPercent GPU threshold for immediate termination (0-100, default 92)
     */
    public SubprocessMemoryWatchdog(
            int memoryThresholdPercent,
            int memoryCriticalPercent,
            int memoryKillThresholdPercent,
            long checkIntervalMs,
            int gpuMemoryThresholdPercent,
            int gpuMemoryCriticalPercent,
            int gpuMemoryKillThresholdPercent) {
        this.memoryThresholdPercent = Math.max(0, Math.min(memoryThresholdPercent, 100));
        this.memoryCriticalPercent = Math.max(0, Math.min(memoryCriticalPercent, 100));
        this.memoryKillThresholdPercent = Math.max(0, Math.min(memoryKillThresholdPercent, 100));
        this.checkIntervalMs = Math.max(500, checkIntervalMs);
        this.gpuMemoryThresholdPercent = Math.max(0, Math.min(gpuMemoryThresholdPercent, 100));
        this.gpuMemoryCriticalPercent = Math.max(0, Math.min(gpuMemoryCriticalPercent, 100));
        this.gpuMemoryKillThresholdPercent = Math.max(0, Math.min(gpuMemoryKillThresholdPercent, 100));

        // Detect GPU backend and initialize GPU monitoring
        boolean gpuEnabled = false;
        int deviceId = 0;
        int deviceCount = 1;
        try {
            String backend = Nd4j.getBackend().getClass().getSimpleName().toLowerCase();
            gpuEnabled = backend.contains("cuda") || backend.contains("aurora") || backend.contains("gpu");
            if (gpuEnabled) {
                deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
                deviceCount = Nd4j.getAffinityManager().getNumberOfDevices();
                logger.info("GPU backend detected: {}, primary device={}, total devices={}", backend, deviceId, deviceCount);
            } else {
                logger.info("CPU backend detected: {}, GPU monitoring disabled", backend);
            }
        } catch (Exception e) {
            logger.debug("Could not determine ND4J backend, disabling GPU monitoring: {}", e.getMessage());
        }
        this.gpuMonitoringEnabled = gpuEnabled;
        this.gpuDeviceId = deviceId;
        this.gpuDeviceCount = deviceCount;

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subprocess-memory-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // High priority to ensure checks run
            return t;
        });

        this.lastSnapshot = captureSnapshot();
    }

    /**
     * Start memory monitoring.
     */
    public synchronized void start() {
        if (running) {
            return;
        }

        running = true;
        monitoringTask = executor.scheduleAtFixedRate(
                this::checkMemory,
                checkIntervalMs,
                checkIntervalMs,
                TimeUnit.MILLISECONDS
        );

        if (gpuMonitoringEnabled) {
            logger.info("Memory watchdog started: heap thresholds stop={}%, critical={}%, kill={}%; " +
                            "GPU thresholds stop={}%, critical={}%, kill={}%; interval={}ms; device={}; velocity threshold={}%/s",
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                    gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent,
                    checkIntervalMs, gpuDeviceId, RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND);
        } else {
            logger.info("Memory watchdog started: threshold={}%, critical={}%, kill={}%, interval={}ms; velocity threshold={}%/s",
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent, checkIntervalMs,
                    RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND);
        }
        
        // Initialize velocity tracking
        lastVelocityCheckTimeMs = System.currentTimeMillis();
        lastHeapUsagePercent = 0.0;
        lastGpuUsagePercent = 0.0;
    }

    /**
     * Stop memory monitoring.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (monitoringTask != null) {
            monitoringTask.cancel(false);
            monitoringTask = null;
        }

        logger.info("Memory watchdog stopped");
    }

    /**
     * Check if the pipeline should stop accepting new work.
     * This is a graceful stop - finish current batch, don't start new ones.
     */
    public boolean shouldStop() {
        return shouldStop.get();
    }

    /**
     * Check if the pipeline should terminate immediately.
     * This is a hard stop - exit as soon as possible.
     */
    public boolean shouldKill() {
        return shouldKill.get();
    }

    /**
     * Check if memory is in critical state.
     */
    public boolean isCriticalMemory() {
        return criticalMemory.get();
    }

    /**
     * Check if rapid memory growth is detected.
     * This indicates memory is increasing faster than the configured threshold
     * (default 5% per second), which may lead to OOM before static thresholds are hit.
     */
    public boolean isRapidMemoryGrowth() {
        return rapidMemoryGrowth.get();
    }

    /**
     * Get the current heap memory velocity (percent per second).
     * Positive values indicate increasing memory usage.
     */
    public double getHeapVelocityPercentPerSecond() {
        return heapVelocityPercentPerSecond;
    }

    /**
     * Get the current GPU memory velocity (percent per second).
     * Positive values indicate increasing memory usage.
     */
    public double getGpuVelocityPercentPerSecond() {
        return gpuVelocityPercentPerSecond;
    }

    /**
     * Get the last captured memory snapshot.
     */
    public MemorySnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    /**
     * Get current memory usage percentage.
     */
    public double getCurrentMemoryPercent() {
        MemorySnapshot snapshot = lastSnapshot;
        return snapshot != null ? snapshot.usagePercent : 0;
    }

    /**
     * Force a memory check now (outside of scheduled interval).
     */
    public void checkNow() {
        checkMemory();
    }

    /**
     * Reset stop/kill flags (for testing or recovery scenarios).
     */
    public void reset() {
        shouldStop.set(false);
        shouldKill.set(false);
        criticalMemory.set(false);
        rapidMemoryGrowth.set(false);
        consecutiveStopChecks.set(0);
        consecutiveKillChecks.set(0);
        consecutiveGpuStopChecks.set(0);
        consecutiveGpuKillChecks.set(0);
        heapVelocityPercentPerSecond = 0.0;
        gpuVelocityPercentPerSecond = 0.0;
    }

    /**
     * Main memory check logic.
     */
    private void checkMemory() {
        try {
            MemorySnapshot snapshot = captureSnapshot();
            this.lastSnapshot = snapshot;

            double usagePercent = snapshot.usagePercent;

            // Calculate heap memory velocity (rate of change per second)
            long now = System.currentTimeMillis();
            if (lastVelocityCheckTimeMs > 0 && lastHeapUsagePercent > 0) {
                long elapsedMs = now - lastVelocityCheckTimeMs;
                if (elapsedMs > 0) {
                    double elapsedSeconds = elapsedMs / 1000.0;
                    double heapChange = usagePercent - lastHeapUsagePercent;
                    heapVelocityPercentPerSecond = heapChange / elapsedSeconds;
                    
                    // Check for rapid memory growth
                    if (heapVelocityPercentPerSecond >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                        if (!rapidMemoryGrowth.get()) {
                            rapidMemoryGrowth.set(true);
                            logger.warn("RAPID MEMORY GROWTH DETECTED: heap increasing at {}/s - may hit OOM before thresholds",
                                    String.format("%.1f%%", heapVelocityPercentPerSecond));
                        }
                    } else if (heapVelocityPercentPerSecond < RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND - 1.0) {
                        // Hysteresis: clear flag only when well below threshold
                        if (rapidMemoryGrowth.get()) {
                            rapidMemoryGrowth.set(false);
                            logger.info("Rapid memory growth subsided: heap velocity now {}/s",
                                    String.format("%.1f%%", heapVelocityPercentPerSecond));
                        }
                    }
                }
            }
            lastVelocityCheckTimeMs = now;
            lastHeapUsagePercent = usagePercent;

            // Check kill threshold first (most severe) - HEAP
            if (memoryKillThresholdPercent > 0 && usagePercent >= memoryKillThresholdPercent) {
                int count = consecutiveKillChecks.incrementAndGet();
                if (count >= KILL_DEBOUNCE_COUNT && !shouldKill.get()) {
                    shouldKill.set(true);
                    shouldStop.set(true); // Also set stop
                    logMemoryKill(snapshot, "HEAP");
                }
            } else {
                consecutiveKillChecks.set(0);
            }

            // Check critical threshold (GC trigger) - HEAP
            if (usagePercent >= memoryCriticalPercent) {
                if (!criticalMemory.get()) {
                    criticalMemory.set(true);
                    logger.warn("CRITICAL MEMORY: {}% used ({}MB/{}MB) - triggering GC",
                            String.format("%.1f", usagePercent),
                            snapshot.usedMB, snapshot.maxMB);
                    System.gc(); // Suggest GC
                }
            } else {
                if (criticalMemory.get()) {
                    criticalMemory.set(false);
                    logger.info("Memory recovered from critical: {}%", String.format("%.1f", usagePercent));
                }
            }

            // Check stop threshold (graceful stop) - HEAP
            if (usagePercent >= memoryThresholdPercent) {
                int count = consecutiveStopChecks.incrementAndGet();
                if (count >= STOP_DEBOUNCE_COUNT && !shouldStop.get()) {
                    shouldStop.set(true);
                    logMemoryStop(snapshot, "HEAP");
                }
            } else {
                consecutiveStopChecks.set(0);
                // Allow recovery if we haven't hit kill threshold
                if (shouldStop.get() && !shouldKill.get() && usagePercent < memoryThresholdPercent - 10) {
                    shouldStop.set(false);
                    logger.info("Memory recovered below threshold ({}%), resuming", String.format("%.1f", usagePercent));
                }
            }

            // GPU VRAM check - scan ALL GPUs for multi-GPU support
            // DeviceAwareOpExecutioner handles multi-device routing automatically
            // We need to monitor all devices since models can span multiple GPUs
            if (gpuMonitoringEnabled && gpuDeviceCount > 0) {
                try {
                    NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                    
                    // Find the GPU with highest usage (most constrained device)
                    double maxGpuUsage = 0.0;
                    long maxGpuUsed = 0;
                    long maxGpuTotal = 0;
                    int maxGpuDeviceId = gpuDeviceId;
                    double totalGpuUsed = 0;
                    double totalGpuTotal = 0;
                    
                    for (int deviceId = 0; deviceId < gpuDeviceCount; deviceId++) {
                        try {
                            long gpuTotal = ops.getDeviceTotalMemory(deviceId);
                            long gpuFree = ops.getDeviceFreeMemory(deviceId);
                            long gpuUsed = gpuTotal - gpuFree;
                            double gpuUsage = gpuTotal > 0 ? ((gpuUsed) * 100.0) / gpuTotal : 0.0;
                            
                            totalGpuUsed += gpuUsed;
                            totalGpuTotal += gpuTotal;
                            
                            // Track the most constrained GPU
                            if (gpuUsage > maxGpuUsage) {
                                maxGpuUsage = gpuUsage;
                                maxGpuUsed = gpuUsed;
                                maxGpuTotal = gpuTotal;
                                maxGpuDeviceId = deviceId;
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to query GPU {}: {}", deviceId, e.getMessage());
                        }
                    }
                    
                    // Use the most constrained GPU for threshold checks
                    double primaryGpuUsage = maxGpuUsage;
                    long primaryGpuUsed = maxGpuUsed;
                    long primaryGpuTotal = maxGpuTotal;
                    
                    // Calculate GPU memory velocity based on max usage device
                    if (lastVelocityCheckTimeMs > 0 && lastGpuUsagePercent > 0) {
                        long elapsedMs = now - lastVelocityCheckTimeMs;
                        if (elapsedMs > 0) {
                            double elapsedSeconds = elapsedMs / 1000.0;
                            double gpuChange = primaryGpuUsage - lastGpuUsagePercent;
                            gpuVelocityPercentPerSecond = gpuChange / elapsedSeconds;
                            
                            // GPU rapid growth check
                            if (gpuVelocityPercentPerSecond >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                                if (!rapidMemoryGrowth.get()) {
                                    rapidMemoryGrowth.set(true);
                                    if (gpuDeviceCount > 1) {
                                        logger.warn("RAPID GPU MEMORY GROWTH DETECTED: max usage on GPU{} at {}/s (monitoring {} devices)",
                                                maxGpuDeviceId, String.format("%.1f%%", gpuVelocityPercentPerSecond), gpuDeviceCount);
                                    } else {
                                        logger.warn("RAPID GPU MEMORY GROWTH DETECTED: GPU{} increasing at {}/s",
                                                maxGpuDeviceId, String.format("%.1f%%", gpuVelocityPercentPerSecond));
                                    }
                                }
                            }
                        }
                    }
                    lastGpuUsagePercent = primaryGpuUsage;
                    
                    // Update snapshot with primary (most constrained) GPU info
                    snapshot = new MemorySnapshot(
                            snapshot.maxMB, snapshot.totalMB, snapshot.freeMB, snapshot.usedMB, snapshot.usagePercent,
                            snapshot.timestampMs,
                            primaryGpuUsed / (1024 * 1024), primaryGpuTotal / (1024 * 1024), primaryGpuUsage, maxGpuDeviceId
                    );
                    this.lastSnapshot = snapshot;
                    
                    // Check GPU kill threshold on most constrained device
                    if (gpuMemoryKillThresholdPercent > 0 && primaryGpuUsage >= gpuMemoryKillThresholdPercent) {
                        int count = consecutiveGpuKillChecks.incrementAndGet();
                        if (count >= KILL_DEBOUNCE_COUNT && !shouldKill.get()) {
                            shouldKill.set(true);
                            shouldStop.set(true);
                            logMemoryKill(snapshot, "GPU");
                        }
                    } else {
                        consecutiveGpuKillChecks.set(0);
                    }
                    
                    // Check GPU critical threshold
                    if (primaryGpuUsage >= gpuMemoryCriticalPercent) {
                        if (!criticalMemory.get()) {
                            criticalMemory.set(true);
                            if (gpuDeviceCount > 1) {
                                logger.warn("CRITICAL GPU MEMORY: {}% used ({}MB/{}MB) on GPU{} (max of {} devices)",
                                        String.format("%.1f", primaryGpuUsage),
                                        snapshot.gpuUsedMB, snapshot.gpuTotalMB, maxGpuDeviceId, gpuDeviceCount);
                            } else {
                                logger.warn("CRITICAL GPU MEMORY: {}% used ({}MB/{}MB) on device {}",
                                        String.format("%.1f", primaryGpuUsage),
                                        snapshot.gpuUsedMB, snapshot.gpuTotalMB, maxGpuDeviceId);
                            }
                        }
                    } else {
                        if (criticalMemory.get()) {
                            criticalMemory.set(false);
                            logger.info("GPU memory recovered from critical: {}%", String.format("%.1f", primaryGpuUsage));
                        }
                    }
                    
                    // Check GPU stop threshold
                    if (primaryGpuUsage >= gpuMemoryThresholdPercent) {
                        int count = consecutiveGpuStopChecks.incrementAndGet();
                        if (count >= STOP_DEBOUNCE_COUNT && !shouldStop.get()) {
                            shouldStop.set(true);
                            logMemoryStop(snapshot, "GPU");
                        }
                    } else {
                        consecutiveGpuStopChecks.set(0);
                        // Allow GPU recovery
                        if (shouldStop.get() && !shouldKill.get() && primaryGpuUsage < gpuMemoryThresholdPercent - 10) {
                            shouldStop.set(false);
                            logger.info("GPU memory recovered below threshold ({}%), resuming", String.format("%.1f", primaryGpuUsage));
                        }
                    }
                    
                } catch (Exception e) {
                    logger.debug("GPU memory query failed (non-fatal): {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("Error checking memory: {}", e.getMessage(), e);
        }
    }

    private void logMemoryStop(MemorySnapshot snapshot, String memoryType) {
        String msg;
        if ("GPU".equals(memoryType)) {
            msg = String.format(
                    "GPU MEMORY THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) on device %d - signaling graceful stop",
                    snapshot.gpuUsagePercent, snapshot.gpuUsedMB, snapshot.gpuTotalMB, snapshot.gpuDeviceId);
        } else {
            msg = String.format(
                    "MEMORY THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) - signaling graceful stop",
                    snapshot.usagePercent, snapshot.usedMB, snapshot.maxMB);
        }
        logger.warn(msg);
    }

    private void logMemoryKill(MemorySnapshot snapshot, String memoryType) {
        String msg;
        if ("GPU".equals(memoryType)) {
            msg = String.format(
                    "GPU MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) on device %d - signaling immediate termination",
                    snapshot.gpuUsagePercent, snapshot.gpuUsedMB, snapshot.gpuTotalMB, snapshot.gpuDeviceId);
        } else {
            msg = String.format(
                    "MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) - signaling immediate termination",
                    snapshot.usagePercent, snapshot.usedMB, snapshot.maxMB);
        }
        logger.error(msg);
    }

    private MemorySnapshot captureSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double usagePercent = (usedMemory * 100.0) / maxMemory;

        // GPU snapshot (if enabled) - scan ALL GPUs for multi-GPU support
        long gpuUsedMB = 0;
        long gpuTotalMB = 0;
        double gpuUsagePercent = 0.0;
        int gpuDevId = -1;

        if (gpuMonitoringEnabled && gpuDeviceCount > 0) {
            try {
                NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                
                // Find the GPU with highest usage (most constrained)
                double maxUsage = 0.0;
                long maxUsed = 0;
                long maxTotal = 0;
                int maxDeviceId = gpuDeviceId;
                
                for (int deviceId = 0; deviceId < gpuDeviceCount; deviceId++) {
                    try {
                        long deviceTotal = ops.getDeviceTotalMemory(deviceId);
                        long deviceFree = ops.getDeviceFreeMemory(deviceId);
                        long deviceUsed = deviceTotal - deviceFree;
                        double deviceUsage = deviceTotal > 0 ? ((deviceUsed) * 100.0) / deviceTotal : 0.0;
                        
                        if (deviceUsage > maxUsage) {
                            maxUsage = deviceUsage;
                            maxUsed = deviceUsed;
                            maxTotal = deviceTotal;
                            maxDeviceId = deviceId;
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to query GPU {}: {}", deviceId, e.getMessage());
                    }
                }
                
                gpuUsedMB = maxUsed / (1024 * 1024);
                gpuTotalMB = maxTotal / (1024 * 1024);
                gpuUsagePercent = maxUsage;
                gpuDevId = maxDeviceId;
            } catch (Exception e) {
                logger.debug("GPU snapshot failed: {}", e.getMessage());
            }
        }

        return new MemorySnapshot(
                maxMemory / (1024 * 1024),
                totalMemory / (1024 * 1024),
                freeMemory / (1024 * 1024),
                usedMemory / (1024 * 1024),
                usagePercent,
                System.currentTimeMillis(),
                gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDevId,
                heapVelocityPercentPerSecond, gpuVelocityPercentPerSecond
        );
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Snapshot of memory state at a point in time.
     */
    public record MemorySnapshot(
            long maxMB,
            long totalMB,
            long freeMB,
            long usedMB,
            double usagePercent,
            long timestampMs,
            long gpuUsedMB,
            long gpuTotalMB,
            double gpuUsagePercent,
            int gpuDeviceId,
            double heapVelocityPercentPerSecond,
            double gpuVelocityPercentPerSecond
    ) {
        // Legacy constructor for backward compatibility (GPU fields default to 0/-1)
        public MemorySnapshot(
                long maxMB, long totalMB, long freeMB, long usedMB,
                double usagePercent, long timestampMs) {
            this(maxMB, totalMB, freeMB, usedMB, usagePercent, timestampMs, 0, 0, 0.0, -1, 0.0, 0.0);
        }

        // Legacy constructor with GPU fields (velocity defaults to 0)
        public MemorySnapshot(
                long maxMB, long totalMB, long freeMB, long usedMB,
                double usagePercent, long timestampMs,
                long gpuUsedMB, long gpuTotalMB, double gpuUsagePercent, int gpuDeviceId) {
            this(maxMB, totalMB, freeMB, usedMB, usagePercent, timestampMs, gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDeviceId, 0.0, 0.0);
        }

        public String formatted() {
            if (gpuDeviceId >= 0) {
                return String.format("%dMB/%dMB (%.1f%%), GPU%d: %dMB/%dMB (%.1f%%), heap velocity: %.1f%%/s, GPU velocity: %.1f%%/s",
                        usedMB, maxMB, usagePercent,
                        gpuDeviceId, gpuUsedMB, gpuTotalMB, gpuUsagePercent,
                        heapVelocityPercentPerSecond, gpuVelocityPercentPerSecond);
            }
            return String.format("%dMB/%dMB (%.1f%%), heap velocity: %.1f%%/s",
                    usedMB, maxMB, usagePercent, heapVelocityPercentPerSecond);
        }
    }
}
