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

import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
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
 * <p><b>Force kill behavior:</b> By default, when the kill threshold is exceeded
 * (after debouncing), the watchdog calls {@code Runtime.getRuntime().halt(137)}
 * to immediately terminate the JVM. This mirrors buildnativeoperations.sh's
 * SIGKILL behavior and prevents OOM crashes. Exit code 137 = 128 + 9 (SIGKILL).
 * This can be disabled via {@link #setForceKillOnThreshold(boolean)} for testing.
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

    // Off-heap (JavaCPP native) memory monitoring fields
    private final long offHeapMaxBytes;  // Configured limit (0 = unlimited)
    private final int offHeapThresholdPercent;
    private final int offHeapCriticalPercent;
    private final int offHeapKillThresholdPercent;

    // Off-heap debounce counters
    private final AtomicInteger consecutiveOffHeapStopChecks = new AtomicInteger(0);
    private final AtomicInteger consecutiveOffHeapKillChecks = new AtomicInteger(0);

    // Memory velocity tracking (rate of memory increase)
    private volatile double heapVelocityPercentPerSecond = 0.0;
    private volatile double gpuVelocityPercentPerSecond = 0.0;
    private volatile double offHeapVelocityPercentPerSecond = 0.0;
    private volatile long lastVelocityCheckTimeMs = 0;
    private volatile double lastHeapUsagePercent = 0.0;
    private volatile double lastGpuUsagePercent = 0.0;
    private volatile double lastOffHeapUsagePercent = 0.0;

    // Force kill behavior: when true, watchdog calls Runtime.halt(137) when kill threshold is hit
    // This mirrors buildnativeoperations.sh's SIGKILL behavior - immediate JVM termination, no hooks
    private volatile boolean forceKillOnThreshold = true;

    // Model context for diagnostics (set after model load)
    private volatile String modelId = null;

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
     * Create a memory watchdog with specified thresholds for heap, GPU, and off-heap.
     *
     * @param memoryThresholdPercent           Heap percentage at which to signal graceful stop (0-100)
     * @param memoryCriticalPercent            Heap percentage at which to trigger GC and warn (0-100)
     * @param memoryKillThresholdPercent       Heap percentage at which to signal immediate termination (0-100, 0 to disable)
     * @param checkIntervalMs                  How often to check memory in milliseconds
     * @param gpuMemoryThresholdPercent        GPU threshold for graceful stop (0-100, default 75)
     * @param gpuMemoryCriticalPercent         GPU threshold for critical warning (0-100, default 85)
     * @param gpuMemoryKillThresholdPercent    GPU threshold for immediate termination (0-100, default 92)
     * @param offHeapThresholdPercent          Off-heap threshold for graceful stop (0-100, default 80)
     * @param offHeapCriticalPercent           Off-heap threshold for critical warning (0-100, default 90)
     * @param offHeapKillThresholdPercent      Off-heap threshold for immediate termination (0-100, default 95)
     */
    public SubprocessMemoryWatchdog(
            int memoryThresholdPercent,
            int memoryCriticalPercent,
            int memoryKillThresholdPercent,
            long checkIntervalMs,
            int gpuMemoryThresholdPercent,
            int gpuMemoryCriticalPercent,
            int gpuMemoryKillThresholdPercent,
            int offHeapThresholdPercent,
            int offHeapCriticalPercent,
            int offHeapKillThresholdPercent) {
        this.memoryThresholdPercent = Math.max(0, Math.min(memoryThresholdPercent, 100));
        this.memoryCriticalPercent = Math.max(0, Math.min(memoryCriticalPercent, 100));
        this.memoryKillThresholdPercent = Math.max(0, Math.min(memoryKillThresholdPercent, 100));
        this.checkIntervalMs = Math.max(500, checkIntervalMs);
        this.gpuMemoryThresholdPercent = Math.max(0, Math.min(gpuMemoryThresholdPercent, 100));
        this.gpuMemoryCriticalPercent = Math.max(0, Math.min(gpuMemoryCriticalPercent, 100));
        this.gpuMemoryKillThresholdPercent = Math.max(0, Math.min(gpuMemoryKillThresholdPercent, 100));
        this.offHeapThresholdPercent = Math.max(0, Math.min(offHeapThresholdPercent, 100));
        this.offHeapCriticalPercent = Math.max(0, Math.min(offHeapCriticalPercent, 100));
        this.offHeapKillThresholdPercent = Math.max(0, Math.min(offHeapKillThresholdPercent, 100));

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

        // Detect off-heap memory limit from JavaCPP system properties
        long maxBytes = 0;
        try {
            String maxBytesStr = System.getProperty("org.bytedeco.javacpp.maxbytes");
            if (maxBytesStr != null && !maxBytesStr.isBlank()) {
                maxBytes = Long.parseLong(maxBytesStr.trim());
                logger.info("Off-heap limit from org.bytedeco.javacpp.maxbytes: {} MB", maxBytes / (1024 * 1024));
            }
        } catch (Exception e) {
            logger.debug("Could not read org.bytedeco.javacpp.maxbytes: {}", e.getMessage());
        }
        // If no explicit limit, estimate from maxPhysicalBytes or use system heuristic
        if (maxBytes <= 0) {
            try {
                String physStr = System.getProperty("org.bytedeco.javacpp.maxphysicalbytes");
                if (physStr != null && !physStr.isBlank()) {
                    maxBytes = Long.parseLong(physStr.trim());
                    logger.info("Off-heap limit from org.bytedeco.javacpp.maxphysicalbytes: {} MB", maxBytes / (1024 * 1024));
                }
            } catch (Exception e) {
                logger.debug("Could not read org.bytedeco.javacpp.maxphysicalbytes: {}", e.getMessage());
            }
        }
        this.offHeapMaxBytes = maxBytes;
        if (offHeapMaxBytes > 0) {
            logger.info("Off-heap monitoring enabled: limit={} MB, thresholds stop={}%, critical={}%, kill={}%",
                    offHeapMaxBytes / (1024 * 1024), offHeapThresholdPercent, offHeapCriticalPercent, offHeapKillThresholdPercent);
        } else {
            logger.info("Off-heap monitoring: no explicit limit set, will track absolute usage only");
        }

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subprocess-memory-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // High priority to ensure checks run
            return t;
        });

        this.lastSnapshot = captureSnapshot();
    }

    /**
     * Backward-compatible constructor without off-heap thresholds.
     * Off-heap thresholds default to 80/90/95.
     */
    public SubprocessMemoryWatchdog(
            int memoryThresholdPercent,
            int memoryCriticalPercent,
            int memoryKillThresholdPercent,
            long checkIntervalMs,
            int gpuMemoryThresholdPercent,
            int gpuMemoryCriticalPercent,
            int gpuMemoryKillThresholdPercent) {
        this(memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                checkIntervalMs,
                gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent,
                80, 90, 95);  // Default off-heap thresholds
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
            logger.info("Memory watchdog started: heap stop={}%/crit={}%/kill={}%; " +
                            "GPU stop={}%/crit={}%/kill={}%; off-heap stop={}%/crit={}%/kill={}% (limit={}MB); " +
                            "interval={}ms; device={}",
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                    gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent,
                    offHeapThresholdPercent, offHeapCriticalPercent, offHeapKillThresholdPercent,
                    offHeapMaxBytes > 0 ? offHeapMaxBytes / (1024 * 1024) : "unlimited",
                    checkIntervalMs, gpuDeviceId);
        } else {
            logger.info("Memory watchdog started: heap stop={}%/crit={}%/kill={}%; " +
                            "off-heap stop={}%/crit={}%/kill={}% (limit={}MB); interval={}ms",
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                    offHeapThresholdPercent, offHeapCriticalPercent, offHeapKillThresholdPercent,
                    offHeapMaxBytes > 0 ? offHeapMaxBytes / (1024 * 1024) : "unlimited",
                    checkIntervalMs);
        }

        // Initialize velocity tracking
        lastVelocityCheckTimeMs = System.currentTimeMillis();
        lastHeapUsagePercent = 0.0;
        lastGpuUsagePercent = 0.0;
        lastOffHeapUsagePercent = 0.0;
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
        consecutiveOffHeapStopChecks.set(0);
        consecutiveOffHeapKillChecks.set(0);
        heapVelocityPercentPerSecond = 0.0;
        gpuVelocityPercentPerSecond = 0.0;
        offHeapVelocityPercentPerSecond = 0.0;
    }

    /**
     * Set the model ID for diagnostic context in kill messages.
     * Called after model load so OOM logs identify which model was running.
     */
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    /**
     * Set whether the watchdog should force-terminate the JVM when kill threshold is exceeded.
     * When true (default), the watchdog calls {@code Runtime.getRuntime().halt(137)} immediately
     * after the kill threshold is hit, bypassing shutdown hooks.
     * Set to false for testing or when the subprocess code handles kill flags itself.
     */
    public void setForceKillOnThreshold(boolean forceKillOnThreshold) {
        this.forceKillOnThreshold = forceKillOnThreshold;
    }

    /**
     * Check if force-kill-on-threshold is enabled.
     */
    public boolean isForceKillOnThreshold() {
        return forceKillOnThreshold;
    }

    /**
     * Force-terminate the JVM with exit code 137 (OOM kill).
     * Logs full memory diagnostics before halting.
     * Uses {@code Runtime.halt(137)} which is the Java equivalent of SIGKILL -
     * immediate termination with no shutdown hooks.
     */
    private void forceTerminate(MemorySnapshot snapshot, String memoryType) {
        // Log as much diagnostic info as possible before halting
        logger.error("═══════════════════════════════════════════════════════════════════════════════");
        logger.error("  WATCHDOG FORCE KILL - {} MEMORY KILL THRESHOLD EXCEEDED", memoryType);
        logger.error("═══════════════════════════════════════════════════════════════════════════════");
        logger.error("  Heap:     {}MB / {}MB ({}%)", snapshot.usedMB, snapshot.maxMB,
                String.format("%.1f", snapshot.usagePercent));
        if (snapshot.gpuDeviceId >= 0) {
            logger.error("  GPU{}:     {}MB / {}MB ({}%)", snapshot.gpuDeviceId,
                    snapshot.gpuUsedMB, snapshot.gpuTotalMB,
                    String.format("%.1f", snapshot.gpuUsagePercent));
        } else if (gpuDeviceCount > 1) {
            logger.error("  GPU(agg): {}MB / {}MB ({}%) across {} devices",
                    snapshot.gpuUsedMB, snapshot.gpuTotalMB,
                    String.format("%.1f", snapshot.gpuUsagePercent), gpuDeviceCount);
            // Log per-device breakdown
            try {
                NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                for (int d = 0; d < gpuDeviceCount; d++) {
                    GpuProbe probe = queryGpu(ops, d);
                    if (probe != null) {
                        logger.error("    GPU{}:   {}MB / {}MB ({}%)", d,
                                probe.usedBytes() / (1024 * 1024), probe.totalBytes() / (1024 * 1024),
                                String.format("%.1f", probe.usagePercent()));
                    }
                }
            } catch (Exception e) {
                logger.error("    (per-device breakdown unavailable: {})", e.getMessage());
            }
        }
        if (snapshot.totalOffHeapMB() > 0) {
            logger.error("  Off-heap: {}MB (JavaCPP: {}MB, Direct: {}MB, Limit: {}MB, {}%)",
                    snapshot.totalOffHeapMB(), snapshot.javacppMB, snapshot.directBufferMB,
                    snapshot.offHeapMaxMB, String.format("%.1f", snapshot.offHeapUsagePercent));
        }
        logger.error("  Heap velocity:     {}/s", String.format("%.1f%%", heapVelocityPercentPerSecond));
        if (gpuMonitoringEnabled) {
            logger.error("  GPU velocity:      {}/s", String.format("%.1f%%", gpuVelocityPercentPerSecond));
        }
        if (modelId != null) {
            logger.error("  Model:   {}", modelId);
        }
        logger.error("  Action: Runtime.halt(137) - immediate JVM termination (no shutdown hooks)");
        if ("GPU".equals(memoryType)) {
            logger.error("  Hint:  GPU OOM is often caused by large sequence lengths in transformer models.");
            logger.error("         Attention matrices scale as O(seq_len^2). Consider reducing max sequence length");
            logger.error("         or using a smaller embedding model (e.g. bge-base-en-v1.5 instead of bge-m3).");
        }
        logger.error("═══════════════════════════════════════════════════════════════════════════════");

        // Flush stderr to ensure diagnostics are written
        System.err.flush();

        // Immediate JVM termination - no shutdown hooks, no finalizers
        // Exit code 137 = 128 + 9 (SIGKILL equivalent)
        Runtime.getRuntime().halt(137);
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
                    if (forceKillOnThreshold) {
                        forceTerminate(snapshot, "HEAP");
                    }
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

            // GPU VRAM check - multi-GPU aggregate for kill, primary device for stop/critical
            // CudaMemoryPool failover routes allocations to other devices when one fills up.
            // A single device at 99% is expected during failover — only kill when the aggregate
            // across ALL devices has no remaining headroom.
            if (gpuMonitoringEnabled && gpuDeviceCount > 0) {
                try {
                    NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                    int primaryDevId = Math.max(0, Math.min(gpuDeviceId, gpuDeviceCount - 1));

                    // Query primary device
                    GpuProbe primaryProbe = queryGpu(ops, primaryDevId);
                    double primaryGpuUsage = primaryProbe != null ? primaryProbe.usagePercent() : 0.0;

                    // For kill decisions: use aggregate across all GPUs
                    double killCheckUsage;
                    long snapshotUsed, snapshotTotal;
                    int snapshotDeviceId;
                    if (gpuDeviceCount > 1) {
                        long aggUsed = 0, aggTotal = 0;
                        for (int d = 0; d < gpuDeviceCount; d++) {
                            GpuProbe probe = queryGpu(ops, d);
                            if (probe != null) {
                                aggUsed += probe.usedBytes();
                                aggTotal += probe.totalBytes();
                            }
                        }
                        killCheckUsage = aggTotal > 0 ? (aggUsed * 100.0) / aggTotal : 0.0;
                        snapshotUsed = aggUsed;
                        snapshotTotal = aggTotal;
                        snapshotDeviceId = -1; // aggregate
                    } else {
                        killCheckUsage = primaryGpuUsage;
                        snapshotUsed = primaryProbe != null ? primaryProbe.usedBytes() : 0;
                        snapshotTotal = primaryProbe != null ? primaryProbe.totalBytes() : 0;
                        snapshotDeviceId = primaryDevId;
                    }

                    // Calculate GPU memory velocity
                    if (lastVelocityCheckTimeMs > 0 && lastGpuUsagePercent > 0) {
                        long elapsedMs = now - lastVelocityCheckTimeMs;
                        if (elapsedMs > 0) {
                            double elapsedSeconds = elapsedMs / 1000.0;
                            double gpuChange = killCheckUsage - lastGpuUsagePercent;
                            gpuVelocityPercentPerSecond = gpuChange / elapsedSeconds;

                            // GPU rapid growth check
                            if (gpuVelocityPercentPerSecond >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                                if (!rapidMemoryGrowth.get()) {
                                    rapidMemoryGrowth.set(true);
                                    if (gpuDeviceCount > 1) {
                                        logger.warn("RAPID GPU MEMORY GROWTH DETECTED: aggregate at {}/s across {} devices",
                                                String.format("%.1f%%", gpuVelocityPercentPerSecond), gpuDeviceCount);
                                    } else {
                                        logger.warn("RAPID GPU MEMORY GROWTH DETECTED: GPU{} increasing at {}/s",
                                                primaryDevId, String.format("%.1f%%", gpuVelocityPercentPerSecond));
                                    }
                                }
                            }
                        }
                    }
                    lastGpuUsagePercent = killCheckUsage;

                    // Update snapshot with GPU info
                    snapshot = new MemorySnapshot(
                            snapshot.maxMB, snapshot.totalMB, snapshot.freeMB, snapshot.usedMB, snapshot.usagePercent,
                            snapshot.timestampMs,
                            snapshotUsed / (1024 * 1024), snapshotTotal / (1024 * 1024), killCheckUsage, snapshotDeviceId
                    );
                    this.lastSnapshot = snapshot;

                    // Kill threshold: uses AGGREGATE usage (multi-GPU) or primary (single)
                    if (gpuMemoryKillThresholdPercent > 0 && killCheckUsage >= gpuMemoryKillThresholdPercent) {
                        int count = consecutiveGpuKillChecks.incrementAndGet();
                        if (count >= KILL_DEBOUNCE_COUNT && !shouldKill.get()) {
                            shouldKill.set(true);
                            shouldStop.set(true);
                            logMemoryKill(snapshot, "GPU");
                            if (forceKillOnThreshold) {
                                forceTerminate(snapshot, "GPU");
                            }
                        }
                    } else {
                        consecutiveGpuKillChecks.set(0);
                    }

                    // Stop/critical thresholds: fire on PRIMARY device (informational)
                    if (primaryGpuUsage >= gpuMemoryCriticalPercent) {
                        if (!criticalMemory.get()) {
                            criticalMemory.set(true);
                            if (gpuDeviceCount > 1) {
                                logger.warn("CRITICAL GPU MEMORY: {}% used on device {} (aggregate: {}% across {} devices)",
                                        String.format("%.1f", primaryGpuUsage), primaryDevId,
                                        String.format("%.1f", killCheckUsage), gpuDeviceCount);
                            } else {
                                logger.warn("CRITICAL GPU MEMORY: {}% used ({}MB/{}MB) on device {}",
                                        String.format("%.1f", primaryGpuUsage),
                                        snapshot.gpuUsedMB, snapshot.gpuTotalMB, primaryDevId);
                            }
                        }
                    } else {
                        if (criticalMemory.get()) {
                            criticalMemory.set(false);
                            logger.info("GPU memory recovered from critical: {}%", String.format("%.1f", primaryGpuUsage));
                        }
                    }

                    // Stop threshold: fire on primary device
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

            // === OFF-HEAP (JavaCPP native) MEMORY CHECK ===
            checkOffHeapMemory(snapshot, now);

        } catch (Exception e) {
            logger.error("Error checking memory: {}", e.getMessage(), e);
        }
    }

    /**
     * Check off-heap (JavaCPP/native) memory usage.
     * Uses Pointer.totalBytes() for JavaCPP-tracked allocations and
     * BufferPoolMXBean for NIO direct buffers.
     */
    private void checkOffHeapMemory(MemorySnapshot snapshot, long now) {
        try {
            long javacppBytes = 0;
            try {
                javacppBytes = Pointer.totalBytes();
            } catch (Exception e) {
                logger.debug("Pointer.totalBytes() failed: {}", e.getMessage());
            }

            // Also capture NIO direct buffer usage
            long directBufferBytes = 0;
            try {
                List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
                for (BufferPoolMXBean pool : pools) {
                    if ("direct".equals(pool.getName())) {
                        directBufferBytes = pool.getMemoryUsed();
                        break;
                    }
                }
            } catch (Exception e) {
                logger.debug("BufferPoolMXBean query failed: {}", e.getMessage());
            }

            long totalOffHeap = javacppBytes + directBufferBytes;
            long effectiveMax = offHeapMaxBytes;

            // If no explicit limit, try to get the physical bytes limit from JavaCPP
            if (effectiveMax <= 0) {
                try {
                    long physBytes = Pointer.physicalBytes();
                    // Use physical bytes as a reference point if available
                    // Without an explicit limit, we use absolute thresholds instead
                    if (physBytes > 0 && totalOffHeap > 0) {
                        // Warn if off-heap exceeds 4GB with no limit set
                        if (totalOffHeap > 4L * 1024 * 1024 * 1024 && totalOffHeap > snapshot.maxMB * 1024 * 1024 * 2) {
                            logger.warn("OFF-HEAP WARNING: {}MB allocated (JavaCPP: {}MB, DirectBuffers: {}MB) with no explicit limit. " +
                                            "Consider setting -Dorg.bytedeco.javacpp.maxbytes",
                                    totalOffHeap / (1024 * 1024), javacppBytes / (1024 * 1024), directBufferBytes / (1024 * 1024));
                        }
                    }
                } catch (Exception e) {
                    // physicalBytes() may not be available on all platforms
                }
                // Update snapshot with off-heap data but skip threshold checks
                this.lastSnapshot = new MemorySnapshot(
                        snapshot.maxMB, snapshot.totalMB, snapshot.freeMB, snapshot.usedMB, snapshot.usagePercent,
                        snapshot.timestampMs,
                        snapshot.gpuUsedMB, snapshot.gpuTotalMB, snapshot.gpuUsagePercent, snapshot.gpuDeviceId,
                        snapshot.heapVelocityPercentPerSecond, snapshot.gpuVelocityPercentPerSecond,
                        javacppBytes / (1024 * 1024), directBufferBytes / (1024 * 1024),
                        effectiveMax > 0 ? effectiveMax / (1024 * 1024) : 0,
                        0.0, offHeapVelocityPercentPerSecond
                );
                return;
            }

            double offHeapUsagePercent = (totalOffHeap * 100.0) / effectiveMax;

            // Calculate off-heap velocity
            if (lastVelocityCheckTimeMs > 0 && lastOffHeapUsagePercent > 0) {
                long elapsedMs = now - lastVelocityCheckTimeMs;
                if (elapsedMs > 0) {
                    double elapsedSeconds = elapsedMs / 1000.0;
                    double offHeapChange = offHeapUsagePercent - lastOffHeapUsagePercent;
                    offHeapVelocityPercentPerSecond = offHeapChange / elapsedSeconds;

                    if (offHeapVelocityPercentPerSecond >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                        if (!rapidMemoryGrowth.get()) {
                            rapidMemoryGrowth.set(true);
                            logger.warn("RAPID OFF-HEAP GROWTH DETECTED: increasing at {}/s ({}MB/{}MB)",
                                    String.format("%.1f%%", offHeapVelocityPercentPerSecond),
                                    totalOffHeap / (1024 * 1024), effectiveMax / (1024 * 1024));
                        }
                    }
                }
            }
            lastOffHeapUsagePercent = offHeapUsagePercent;

            // Update snapshot with off-heap data
            this.lastSnapshot = new MemorySnapshot(
                    snapshot.maxMB, snapshot.totalMB, snapshot.freeMB, snapshot.usedMB, snapshot.usagePercent,
                    snapshot.timestampMs,
                    snapshot.gpuUsedMB, snapshot.gpuTotalMB, snapshot.gpuUsagePercent, snapshot.gpuDeviceId,
                    snapshot.heapVelocityPercentPerSecond, snapshot.gpuVelocityPercentPerSecond,
                    javacppBytes / (1024 * 1024), directBufferBytes / (1024 * 1024),
                    effectiveMax / (1024 * 1024), offHeapUsagePercent, offHeapVelocityPercentPerSecond
            );

            // Check off-heap kill threshold
            if (offHeapKillThresholdPercent > 0 && offHeapUsagePercent >= offHeapKillThresholdPercent) {
                int count = consecutiveOffHeapKillChecks.incrementAndGet();
                if (count >= KILL_DEBOUNCE_COUNT && !shouldKill.get()) {
                    shouldKill.set(true);
                    shouldStop.set(true);
                    logMemoryKill(this.lastSnapshot, "OFF-HEAP");
                    if (forceKillOnThreshold) {
                        forceTerminate(this.lastSnapshot, "OFF-HEAP");
                    }
                }
            } else {
                consecutiveOffHeapKillChecks.set(0);
            }

            // Check off-heap critical threshold
            if (offHeapUsagePercent >= offHeapCriticalPercent) {
                if (!criticalMemory.get()) {
                    criticalMemory.set(true);
                    logger.warn("CRITICAL OFF-HEAP MEMORY: {}% used (JavaCPP: {}MB, Direct: {}MB, Limit: {}MB)",
                            String.format("%.1f", offHeapUsagePercent),
                            javacppBytes / (1024 * 1024), directBufferBytes / (1024 * 1024),
                            effectiveMax / (1024 * 1024));
                }
            }

            // Check off-heap stop threshold
            if (offHeapUsagePercent >= offHeapThresholdPercent) {
                int count = consecutiveOffHeapStopChecks.incrementAndGet();
                if (count >= STOP_DEBOUNCE_COUNT && !shouldStop.get()) {
                    shouldStop.set(true);
                    logMemoryStop(this.lastSnapshot, "OFF-HEAP");
                }
            } else {
                consecutiveOffHeapStopChecks.set(0);
                if (shouldStop.get() && !shouldKill.get() && offHeapUsagePercent < offHeapThresholdPercent - 10) {
                    shouldStop.set(false);
                    logger.info("Off-heap memory recovered below threshold ({}%), resuming",
                            String.format("%.1f", offHeapUsagePercent));
                }
            }

        } catch (Exception e) {
            logger.debug("Off-heap memory check failed (non-fatal): {}", e.getMessage());
        }
    }

    private void logMemoryStop(MemorySnapshot snapshot, String memoryType) {
        String msg = switch (memoryType) {
            case "GPU" -> String.format(
                    "GPU MEMORY THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) %s - signaling graceful stop",
                    snapshot.gpuUsagePercent, snapshot.gpuUsedMB, snapshot.gpuTotalMB,
                    snapshot.gpuDeviceId >= 0 ? "on device " + snapshot.gpuDeviceId : "aggregate across " + gpuDeviceCount + " GPUs");
            case "OFF-HEAP" -> String.format(
                    "OFF-HEAP MEMORY THRESHOLD EXCEEDED: %.1f%% used (JavaCPP: %dMB, Direct: %dMB, Limit: %dMB) - signaling graceful stop",
                    snapshot.offHeapUsagePercent, snapshot.javacppMB, snapshot.directBufferMB, snapshot.offHeapMaxMB);
            default -> String.format(
                    "MEMORY THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) - signaling graceful stop",
                    snapshot.usagePercent, snapshot.usedMB, snapshot.maxMB);
        };
        logger.warn(msg);
    }

    private void logMemoryKill(MemorySnapshot snapshot, String memoryType) {
        String msg = switch (memoryType) {
            case "GPU" -> String.format(
                    "GPU MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) %s - signaling immediate termination",
                    snapshot.gpuUsagePercent, snapshot.gpuUsedMB, snapshot.gpuTotalMB,
                    snapshot.gpuDeviceId >= 0 ? "on device " + snapshot.gpuDeviceId : "aggregate across " + gpuDeviceCount + " GPUs");
            case "OFF-HEAP" -> String.format(
                    "OFF-HEAP MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (JavaCPP: %dMB, Direct: %dMB, Limit: %dMB) - signaling immediate termination",
                    snapshot.offHeapUsagePercent, snapshot.javacppMB, snapshot.directBufferMB, snapshot.offHeapMaxMB);
            default -> String.format(
                    "MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) - signaling immediate termination",
                    snapshot.usagePercent, snapshot.usedMB, snapshot.maxMB);
        };
        logger.error(msg);
    }

    private MemorySnapshot captureSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double usagePercent = (usedMemory * 100.0) / maxMemory;

        // GPU snapshot (if enabled) — aggregate for multi-GPU, single-device otherwise
        long gpuUsedMB = 0;
        long gpuTotalMB = 0;
        double gpuUsagePercent = 0.0;
        int gpuDevId = -1;

        if (gpuMonitoringEnabled && gpuDeviceCount > 0) {
            try {
                NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                if (gpuDeviceCount > 1) {
                    // Multi-GPU: report aggregate usage for consistency with kill checks
                    long aggUsed = 0, aggTotal = 0;
                    for (int d = 0; d < gpuDeviceCount; d++) {
                        GpuProbe probe = queryGpu(ops, d);
                        if (probe != null) {
                            aggUsed += probe.usedBytes();
                            aggTotal += probe.totalBytes();
                        }
                    }
                    gpuUsedMB = aggUsed / (1024 * 1024);
                    gpuTotalMB = aggTotal / (1024 * 1024);
                    gpuUsagePercent = aggTotal > 0 ? (aggUsed * 100.0) / aggTotal : 0.0;
                    gpuDevId = -1; // aggregate
                } else {
                    GpuProbe gpu = queryGpu(ops, Math.max(0, Math.min(gpuDeviceId, gpuDeviceCount - 1)));
                    if (gpu != null) {
                        gpuUsedMB = gpu.usedBytes() / (1024 * 1024);
                        gpuTotalMB = gpu.totalBytes() / (1024 * 1024);
                        gpuUsagePercent = gpu.usagePercent();
                        gpuDevId = gpu.deviceId();
                    }
                }
            } catch (Exception e) {
                logger.debug("GPU snapshot failed: {}", e.getMessage());
            }
        }

        // Off-heap snapshot
        long javacppMB = 0;
        long directBufferMB = 0;
        long offHeapMaxMB = offHeapMaxBytes > 0 ? offHeapMaxBytes / (1024 * 1024) : 0;
        double offHeapUsagePercent = 0.0;
        try {
            long javacppBytes = Pointer.totalBytes();
            javacppMB = javacppBytes / (1024 * 1024);

            long directBytes = 0;
            try {
                List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
                for (BufferPoolMXBean pool : pools) {
                    if ("direct".equals(pool.getName())) {
                        directBytes = pool.getMemoryUsed();
                        break;
                    }
                }
            } catch (Exception ignored) {}
            directBufferMB = directBytes / (1024 * 1024);

            if (offHeapMaxBytes > 0) {
                offHeapUsagePercent = ((javacppBytes + directBytes) * 100.0) / offHeapMaxBytes;
            }
        } catch (Exception e) {
            logger.debug("Off-heap snapshot failed: {}", e.getMessage());
        }

        return new MemorySnapshot(
                maxMemory / (1024 * 1024),
                totalMemory / (1024 * 1024),
                freeMemory / (1024 * 1024),
                usedMemory / (1024 * 1024),
                usagePercent,
                System.currentTimeMillis(),
                gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDevId,
                heapVelocityPercentPerSecond, gpuVelocityPercentPerSecond,
                javacppMB, directBufferMB, offHeapMaxMB, offHeapUsagePercent,
                offHeapVelocityPercentPerSecond
        );
    }

    /** Per-device GPU memory probe result. */
    private record GpuProbe(int deviceId, long usedBytes, long totalBytes, double usagePercent) {}

    /** Query a single GPU device for memory usage. Returns null on failure. */
    private static GpuProbe queryGpu(NativeOps ops, int deviceId) {
        try {
            long total = ops.getDeviceTotalMemory(deviceId);
            long free = ops.getDeviceFreeMemory(deviceId);
            long used = total - free;
            double usage = total > 0 ? (used * 100.0) / total : 0.0;
            return new GpuProbe(deviceId, used, total, usage);
        } catch (Exception e) {
            return null;
        }
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
     * Includes JVM heap, GPU VRAM, and off-heap (JavaCPP + NIO direct buffers).
     */
    public record MemorySnapshot(
            // JVM heap
            long maxMB,
            long totalMB,
            long freeMB,
            long usedMB,
            double usagePercent,
            long timestampMs,
            // GPU VRAM
            long gpuUsedMB,
            long gpuTotalMB,
            double gpuUsagePercent,
            int gpuDeviceId,
            // Velocity
            double heapVelocityPercentPerSecond,
            double gpuVelocityPercentPerSecond,
            // Off-heap (JavaCPP native + NIO direct)
            long javacppMB,
            long directBufferMB,
            long offHeapMaxMB,
            double offHeapUsagePercent,
            double offHeapVelocityPercentPerSecond
    ) {
        // Legacy constructor: heap only
        public MemorySnapshot(
                long maxMB, long totalMB, long freeMB, long usedMB,
                double usagePercent, long timestampMs) {
            this(maxMB, totalMB, freeMB, usedMB, usagePercent, timestampMs,
                    0, 0, 0.0, -1, 0.0, 0.0,
                    0, 0, 0, 0.0, 0.0);
        }

        // Legacy constructor: heap + GPU (no velocity)
        public MemorySnapshot(
                long maxMB, long totalMB, long freeMB, long usedMB,
                double usagePercent, long timestampMs,
                long gpuUsedMB, long gpuTotalMB, double gpuUsagePercent, int gpuDeviceId) {
            this(maxMB, totalMB, freeMB, usedMB, usagePercent, timestampMs,
                    gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDeviceId, 0.0, 0.0,
                    0, 0, 0, 0.0, 0.0);
        }

        // Legacy constructor: heap + GPU + velocity (no off-heap)
        public MemorySnapshot(
                long maxMB, long totalMB, long freeMB, long usedMB,
                double usagePercent, long timestampMs,
                long gpuUsedMB, long gpuTotalMB, double gpuUsagePercent, int gpuDeviceId,
                double heapVelocityPercentPerSecond, double gpuVelocityPercentPerSecond) {
            this(maxMB, totalMB, freeMB, usedMB, usagePercent, timestampMs,
                    gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDeviceId,
                    heapVelocityPercentPerSecond, gpuVelocityPercentPerSecond,
                    0, 0, 0, 0.0, 0.0);
        }

        /** Total off-heap in MB (JavaCPP + direct buffers). */
        public long totalOffHeapMB() {
            return javacppMB + directBufferMB;
        }

        public String formatted() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Heap: %dMB/%dMB (%.1f%%)", usedMB, maxMB, usagePercent));
            if (gpuDeviceId >= 0) {
                sb.append(String.format(", GPU%d: %dMB/%dMB (%.1f%%)", gpuDeviceId, gpuUsedMB, gpuTotalMB, gpuUsagePercent));
            } else if (gpuTotalMB > 0) {
                sb.append(String.format(", GPU(agg): %dMB/%dMB (%.1f%%)", gpuUsedMB, gpuTotalMB, gpuUsagePercent));
            }
            if (javacppMB > 0 || directBufferMB > 0) {
                sb.append(String.format(", Off-heap: %dMB (JavaCPP: %dMB, Direct: %dMB",
                        totalOffHeapMB(), javacppMB, directBufferMB));
                if (offHeapMaxMB > 0) {
                    sb.append(String.format(", %.1f%%", offHeapUsagePercent));
                }
                sb.append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Check if GPU monitoring is enabled (CUDA/Aurora backend detected).
     */
    public boolean isGpuMonitoringEnabled() {
        return gpuMonitoringEnabled;
    }

    /**
     * Get the configured off-heap max bytes (0 = unlimited).
     */
    public long getOffHeapMaxBytes() {
        return offHeapMaxBytes;
    }
}
