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
import org.nd4j.linalg.api.device.DeviceMemoryManager;
import org.nd4j.linalg.api.device.DeviceDescriptor;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile double lastOffHeapUsagePercent = 0.0;

    // Per-device GPU usage history (last usage% seen for each device id). Velocity must never
    // diff two different devices - a device missing from a tick's probes has its history
    // forgotten so a later reading for it can't diff against a stale, unrelated value.
    private final Map<Integer, Double> lastGpuUsageByDevice = new ConcurrentHashMap<>();

    // Force kill behavior: when true, watchdog calls Runtime.halt(137) when kill threshold is hit
    // This mirrors buildnativeoperations.sh's SIGKILL behavior - immediate JVM termination, no hooks
    private volatile boolean forceKillOnThreshold = true;

    // Model context for diagnostics (set after model load)
    private volatile String modelId = null;

    // Velocity-based early warning
    private final AtomicBoolean rapidMemoryGrowth = new AtomicBoolean(false);
    private static final double RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND = 5.0; // 5% per second is dangerous

    // GC-churn detection: fraction of wall-clock spent in GC over the last check interval. Sustained
    // high churn (heap thrashing) degrades throughput and precedes batch failures/retries, so we
    // surface it as a health WARN. Threshold configurable; default 0.5 (50% of the interval in GC).
    private volatile long lastGcCheckTimeMs = 0;
    private volatile long lastGcCollectionTimeMs = 0;
    private final AtomicBoolean gcChurn = new AtomicBoolean(false);
    private final double gcChurnThresholdFraction =
            parseFractionProperty("kompile.subprocess.gcChurnThresholdFraction", 0.5);

    // GC suggestions made while the heap stop is pending or held are rate-limited to one per
    // interval (see evaluate()), so a genuinely large live set doesn't run a full GC every tick.
    private static final long HEAP_STOP_GC_INTERVAL_MS = 30_000;
    private volatile long lastHeapGcSuggestionMs = -1; // -1 = none yet

    // State flags. These three are published once per tick by publishFlags() as the OR of the
    // per-dimension flags below; only publishFlags() writes them. The kill branches are the one
    // exception - kill is terminal, so shouldKill/shouldStop are still set directly and
    // immediately by whichever dimension's kill check trips.
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private final AtomicBoolean shouldKill = new AtomicBoolean(false);
    private final AtomicBoolean criticalMemory = new AtomicBoolean(false);

    // Per-dimension stop/critical/rapid state. Each dimension's branch in evaluate() writes only
    // its own three flags, so heap/GPU/off-heap can no longer clear a flag another dimension set
    // (previously all three shared shouldStop/criticalMemory/rapidMemoryGrowth directly, so e.g.
    // heap recovering every tick cleared a GPU-set critical, and GPU immediately re-set it).
    private final AtomicBoolean heapStop = new AtomicBoolean(false);
    private final AtomicBoolean heapCritical = new AtomicBoolean(false);
    private final AtomicBoolean heapRapid = new AtomicBoolean(false);
    private final AtomicBoolean gpuStop = new AtomicBoolean(false);
    private final AtomicBoolean gpuCritical = new AtomicBoolean(false);
    private final AtomicBoolean gpuRapid = new AtomicBoolean(false);
    private final AtomicBoolean offHeapStop = new AtomicBoolean(false);
    private final AtomicBoolean offHeapCritical = new AtomicBoolean(false);
    private final AtomicBoolean offHeapRapid = new AtomicBoolean(false);

    // Debouncing counters (require consecutive checks before triggering)
    private final AtomicInteger consecutiveStopChecks = new AtomicInteger(0);
    private final AtomicInteger consecutiveKillChecks = new AtomicInteger(0);
    // GPU-specific debounce counters
    private final AtomicInteger consecutiveGpuStopChecks = new AtomicInteger(0);
    private final AtomicInteger consecutiveGpuKillChecks = new AtomicInteger(0);

    // Required consecutive checks before triggering
    private static final int STOP_DEBOUNCE_COUNT = 3;
    private static final int KILL_DEBOUNCE_COUNT = 2;

    // Devices whose free-memory query currently gives no reading (warned once per streak)
    private static final Set<Integer> devicesWithoutFreeReading = ConcurrentHashMap.newKeySet();

    // Executor for background monitoring
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> monitoringTask;
    private volatile boolean running = false;

    // Last recorded memory info
    private volatile MemorySnapshot lastSnapshot;

    // This tick's GPU probes, kept so forceTerminate()'s per-device breakdown can read the
    // probes behind the decision instead of re-querying NativeOps on the kill path (which may
    // run right after a sticky CUDA error). Set in evaluate() before any threshold check runs.
    private volatile List<GpuProbe> lastGpuProbes = List.of();

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
            String backend = Nd4j.getBackend().getClass().getSimpleName();
            gpuEnabled = isGpuBackendName(backend);
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

        // Initial snapshot: real readings, zero velocities (no prior tick to diff against).
        Readings initial = measure();
        double initialOffHeapPercent = offHeapMaxBytes > 0
                ? ((initial.javacppBytes() + initial.directBufferBytes()) * 100.0) / offHeapMaxBytes : 0.0;
        this.lastSnapshot = buildSnapshot(initial, 0.0, 0.0, initialOffHeapPercent, 0.0);
    }

    static boolean isGpuBackendName(String backendClassName) {
        if (backendClassName == null || backendClassName.isBlank()) {
            return false;
        }
        String backend = backendClassName.toLowerCase();
        return backend.contains("cuda")
                || backend.contains("cublas")
                || backend.contains("rocm")
                || backend.contains("aurora")
                || backend.contains("gpu");
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
     * Package-private constructor for tests: skips ND4J backend detection entirely, so it never
     * touches {@code Nd4j}/{@code NativeOps}. {@code gpuMonitoringEnabledOverride} and
     * {@code deviceCountOverride} let a test exercise GPU logic without a real backend;
     * {@code offHeapMaxBytesOverride} sets the off-heap limit directly. The initial snapshot is
     * heap-only for the same reason - see {@link #captureHeapOnlySnapshot()}.
     */
    SubprocessMemoryWatchdog(
            int memoryThresholdPercent,
            int memoryCriticalPercent,
            int memoryKillThresholdPercent,
            long checkIntervalMs,
            int gpuMemoryThresholdPercent,
            int gpuMemoryCriticalPercent,
            int gpuMemoryKillThresholdPercent,
            int offHeapThresholdPercent,
            int offHeapCriticalPercent,
            int offHeapKillThresholdPercent,
            boolean gpuMonitoringEnabledOverride,
            int deviceCountOverride,
            long offHeapMaxBytesOverride) {
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
        this.gpuMonitoringEnabled = gpuMonitoringEnabledOverride;
        this.gpuDeviceId = 0;
        this.gpuDeviceCount = Math.max(1, deviceCountOverride);
        this.offHeapMaxBytes = offHeapMaxBytesOverride;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subprocess-memory-watchdog-test");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        this.lastSnapshot = captureHeapOnlySnapshot();
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
        lastOffHeapUsagePercent = 0.0;
        lastGpuUsageByDevice.clear();
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
     * Synchronized with {@link #evaluate} so a reset can't interleave with a scheduled tick.
     */
    public synchronized void reset() {
        shouldStop.set(false);
        shouldKill.set(false);
        criticalMemory.set(false);
        rapidMemoryGrowth.set(false);
        heapStop.set(false);
        heapCritical.set(false);
        heapRapid.set(false);
        gpuStop.set(false);
        gpuCritical.set(false);
        gpuRapid.set(false);
        offHeapStop.set(false);
        offHeapCritical.set(false);
        offHeapRapid.set(false);
        consecutiveStopChecks.set(0);
        consecutiveKillChecks.set(0);
        consecutiveGpuStopChecks.set(0);
        consecutiveGpuKillChecks.set(0);
        consecutiveOffHeapStopChecks.set(0);
        consecutiveOffHeapKillChecks.set(0);
        heapVelocityPercentPerSecond = 0.0;
        gpuVelocityPercentPerSecond = 0.0;
        offHeapVelocityPercentPerSecond = 0.0;
        lastGpuUsageByDevice.clear();
        // Forget the velocity baselines too, so the first evaluate() after a reset never diffs
        // against a pre-reset reading.
        lastVelocityCheckTimeMs = 0;
        lastHeapUsagePercent = 0.0;
        lastOffHeapUsagePercent = 0.0;
        lastHeapGcSuggestionMs = -1;
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
        } else if (snapshot.gpuTotalMB > 0) {
            logger.error("  GPU(owned agg): {}MB / {}MB ({}%) across {} devices",
                    snapshot.gpuUsedMB, snapshot.gpuTotalMB,
                    String.format("%.1f", snapshot.gpuUsagePercent), gpuDeviceCount);
        }
        if (gpuDeviceCount > 1) {
            // Per-device breakdown on ANY kill, not only when the snapshot has no single named
            // device. A GPU kill's snapshot always names the worst device (gpuDeviceId >= 0), so
            // gating this on gpuDeviceId < 0 meant a multi-GPU GPU kill never showed the other
            // devices, i.e. never showed whether a co-tenant filled the card.
            //
            // Read from lastGpuProbes (this tick's readings, captured by evaluate()) instead of
            // re-querying NativeOps here: the kill path can run right after a sticky CUDA error,
            // and a fresh query on that path risks reading the same failure again.
            List<GpuProbe> probes = lastGpuProbes;
            if (probes.isEmpty()) {
                logger.error("    (per-device breakdown unavailable: no GPU probes this tick)");
            }
            for (GpuProbe probe : probes) {
                String driverUsed = probe.driverUsedBytes() >= 0
                        ? (probe.driverUsedBytes() / (1024 * 1024)) + "MB"
                        : "no reading";
                logger.error("    GPU{}:   owned={}MB / {}MB ({}%), driver-used={}", probe.deviceId(),
                        probe.usedBytes() / (1024 * 1024), probe.totalBytes() / (1024 * 1024),
                        String.format("%.1f", probe.usagePercent()), driverUsed);
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
            logger.error("  Hint:  owned = this process's own CUDA pool allocations on that device;");
            logger.error("         driver-used = the whole card, all processes. Reduce this process's");
            logger.error("         batch/sequence size or model, or give it a larger device.");
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
    /** Sum of GC collection time across all collectors (ms); -1 entries are treated as 0. */
    private static long totalGcCollectionTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) {
                total += t;
            }
        }
        return total;
    }

    /** Parse a 0..1 fraction system property; clamps to [0,1], falls back to {@code def}. */
    private static double parseFractionProperty(String key, double def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) {
                return def;
            }
            return Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void checkMemory() {
        try {
            evaluate(measure());
        } catch (Exception e) {
            logger.error("Error checking memory: {}", e.getMessage(), e);
        }
    }

    /**
     * Take one tick's raw readings. This is the ONLY method that touches {@code Runtime}, the
     * GC MXBeans, {@code NativeOps}/{@code DeviceMemoryManager} (via {@link #queryGpu}), the
     * JavaCPP {@code Pointer} statics, and {@code BufferPoolMXBean}. Each GPU device is probed
     * exactly once per tick (the old code probed every device twice: once here, once more in
     * the GPU block). Failures are tolerated exactly as before: a probe failure yields an empty
     * list or a 0-byte reading, never a thrown exception out of this method.
     */
    Readings measure() {
        Runtime runtime = Runtime.getRuntime();
        long heapMaxBytes = runtime.maxMemory();
        long heapTotalBytes = runtime.totalMemory();
        long heapFreeBytes = runtime.freeMemory();
        long gcCollectionTimeMs = totalGcCollectionTimeMs();

        List<GpuProbe> gpuProbes = List.of();
        if (gpuMonitoringEnabled && gpuDeviceCount > 0) {
            try {
                NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                DeviceMemoryManager deviceMemory = DeviceMemoryManager.getInstance();
                List<GpuProbe> probes = new ArrayList<>(gpuDeviceCount);
                for (int d = 0; d < gpuDeviceCount; d++) {
                    GpuProbe probe = queryGpu(ops, deviceMemory, d);
                    if (probe != null) {
                        probes.add(probe);
                    }
                }
                gpuProbes = probes;
            } catch (Exception e) {
                logger.debug("GPU memory probe failed (non-fatal): {}", e.getMessage());
            }
        }

        long javacppBytes = 0;
        try {
            javacppBytes = Pointer.totalBytes();
        } catch (Exception e) {
            logger.debug("Pointer.totalBytes() failed: {}", e.getMessage());
        }

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

        long physicalBytes = 0;
        try {
            physicalBytes = Pointer.physicalBytes();
        } catch (Exception e) {
            // physicalBytes() may not be available on all platforms.
        }

        return new Readings(System.currentTimeMillis(), heapMaxBytes, heapTotalBytes, heapFreeBytes,
                gcCollectionTimeMs, gpuProbes, javacppBytes, directBufferBytes, physicalBytes);
    }

    /**
     * One tick's raw inputs to {@link #evaluate}, captured by {@link #measure()}. Separating the
     * read side from the decision side lets tests drive {@link #evaluate} with synthetic
     * readings without touching ND4J, NativeOps, DeviceMemoryManager, or JavaCPP.
     */
    record Readings(
            long timestampMs,
            long heapMaxBytes,
            long heapTotalBytes,
            long heapFreeBytes,
            long gcCollectionTimeMs,
            List<GpuProbe> gpuProbes,
            long javacppBytes,
            long directBufferBytes,
            long physicalBytes) {
    }

    /**
     * Decide flag state from one tick's readings. No ND4J/JavaCPP/MXBean calls happen here -
     * see {@link #measure()} for the read side. Synchronized with {@link #reset()} so a reset
     * can't interleave with a scheduled tick.
     *
     * <p>Each dimension (heap/GPU/off-heap) writes only its own stop/critical/rapid flags and
     * its own debounce counters; {@link #publishFlags()} ORs them into the three public flags
     * once at the end. This is what stops one dimension recovering from clearing another
     * dimension's flag mid-tick.
     */
    synchronized void evaluate(Readings r) {
        // (a) Heap usage% and the one elapsed-time figure every dimension's velocity uses this
        // tick. An out-of-order checkNow() racing a scheduled tick, or a wall-clock step back,
        // must never produce a negative or infinite velocity, so elapsed is only positive when
        // time actually moved forward; such a tick just reports zero velocity.
        long heapUsedBytes = r.heapTotalBytes() - r.heapFreeBytes();
        double heapUsagePercent = r.heapMaxBytes() > 0
                ? (heapUsedBytes * 100.0) / r.heapMaxBytes() : 0.0;
        boolean haveElapsed = lastVelocityCheckTimeMs > 0 && r.timestampMs() > lastVelocityCheckTimeMs;
        double elapsedSeconds = haveElapsed ? (r.timestampMs() - lastVelocityCheckTimeMs) / 1000.0 : 0.0;

        // (b) Heap velocity, exactly as before.
        double heapVelocity = 0.0;
        if (elapsedSeconds > 0 && lastHeapUsagePercent > 0) {
            heapVelocity = (heapUsagePercent - lastHeapUsagePercent) / elapsedSeconds;
        }
        heapVelocityPercentPerSecond = heapVelocity;
        if (heapVelocity >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
            if (!heapRapid.get()) {
                heapRapid.set(true);
                logger.warn("RAPID MEMORY GROWTH DETECTED: heap increasing at {}/s - may hit OOM before thresholds",
                        String.format("%.1f%%", heapVelocity));
            }
        } else if (heapVelocity < RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND - 1.0) {
            // Hysteresis: clear flag only when well below threshold
            if (heapRapid.get()) {
                heapRapid.set(false);
                logger.info("Rapid memory growth subsided: heap velocity now {}/s",
                        String.format("%.1f%%", heapVelocity));
            }
        }

        // (c) GPU velocity/rapid, per device — never worst-vs-worst, which can diff two
        // different physical GPUs across ticks. A device absent this tick loses its history so
        // a later reading for it can never diff against a stale, unrelated value.
        List<GpuProbe> gpuProbes = r.gpuProbes();
        this.lastGpuProbes = gpuProbes;
        GpuProbe worstGpu = highestUsageGpuProbe(gpuProbes);
        double worstGpuUsage = worstGpu != null ? worstGpu.usagePercent() : 0.0;
        double gpuVelocity = 0.0;
        if (!gpuProbes.isEmpty()) {
            double maxRate = 0.0;
            boolean anyHistory = false;
            int maxRateDeviceId = -1;
            if (elapsedSeconds > 0) {
                for (GpuProbe probe : gpuProbes) {
                    Double previous = lastGpuUsageByDevice.get(probe.deviceId());
                    if (previous != null) {
                        double rate = (probe.usagePercent() - previous) / elapsedSeconds;
                        if (!anyHistory || rate > maxRate) {
                            maxRate = rate;
                            maxRateDeviceId = probe.deviceId();
                        }
                        anyHistory = true;
                    }
                }
            }
            gpuVelocity = anyHistory ? maxRate : 0.0;

            // Replace the history with exactly this tick's probes: a device missing this tick
            // is forgotten, so it can never diff against a stale value on a later tick.
            lastGpuUsageByDevice.clear();
            for (GpuProbe probe : gpuProbes) {
                lastGpuUsageByDevice.put(probe.deviceId(), probe.usagePercent());
            }

            if (gpuVelocity >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                if (!gpuRapid.get()) {
                    gpuRapid.set(true);
                    logger.warn("RAPID GPU MEMORY GROWTH DETECTED: GPU{} increasing at {}/s",
                            maxRateDeviceId, String.format("%.1f%%", gpuVelocity));
                }
            } else if (gpuVelocity < RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND - 1.0) {
                if (gpuRapid.get()) {
                    gpuRapid.set(false);
                    logger.info("GPU rapid memory growth subsided: velocity now {}/s",
                            String.format("%.1f%%", gpuVelocity));
                }
            }
        } else {
            // No probes this tick (monitoring off, or no device answered): no evidence either
            // way, so just clear velocity/rapid and forget history. gpuStop/gpuCritical and
            // their debounce counters are left untouched below.
            lastGpuUsageByDevice.clear();
            gpuRapid.set(false);
        }
        gpuVelocityPercentPerSecond = gpuVelocity;

        // (d) Off-heap: JavaCPP + direct NIO buffers.
        long totalOffHeap = r.javacppBytes() + r.directBufferBytes();
        double offHeapUsagePercent = 0.0;
        double offHeapVelocity = 0.0;
        if (offHeapMaxBytes <= 0) {
            // No explicit limit: warn past a coarse absolute threshold (today's behaviour), but
            // there's nothing to compute a percentage against, so skip threshold checks entirely.
            if (r.physicalBytes() > 0 && totalOffHeap > 4L * 1024 * 1024 * 1024
                    && totalOffHeap > r.heapMaxBytes() * 2) {
                logger.warn("OFF-HEAP WARNING: {}MB allocated (JavaCPP: {}MB, DirectBuffers: {}MB) with no explicit limit. " +
                                "Consider setting -Dorg.bytedeco.javacpp.maxbytes",
                        totalOffHeap / (1024 * 1024), r.javacppBytes() / (1024 * 1024),
                        r.directBufferBytes() / (1024 * 1024));
            }
            offHeapRapid.set(false);
            offHeapCritical.set(false);
            offHeapStop.set(false);
        } else {
            offHeapUsagePercent = (totalOffHeap * 100.0) / offHeapMaxBytes;
            if (elapsedSeconds > 0 && lastOffHeapUsagePercent > 0) {
                offHeapVelocity = (offHeapUsagePercent - lastOffHeapUsagePercent) / elapsedSeconds;
            }
            if (offHeapVelocity >= RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND) {
                if (!offHeapRapid.get()) {
                    offHeapRapid.set(true);
                    logger.warn("RAPID OFF-HEAP GROWTH DETECTED: increasing at {}/s ({}MB/{}MB)",
                            String.format("%.1f%%", offHeapVelocity),
                            totalOffHeap / (1024 * 1024), offHeapMaxBytes / (1024 * 1024));
                }
            } else if (offHeapVelocity < RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND - 1.0) {
                if (offHeapRapid.get()) {
                    offHeapRapid.set(false);
                    logger.info("Off-heap rapid memory growth subsided: velocity now {}/s",
                            String.format("%.1f%%", offHeapVelocity));
                }
            }
        }
        offHeapVelocityPercentPerSecond = offHeapVelocity;

        // (e) One snapshot for the tick; every log/kill call below uses it.
        MemorySnapshot snapshot = buildSnapshot(r, heapVelocity, gpuVelocity, offHeapUsagePercent, offHeapVelocity);
        this.lastSnapshot = snapshot;

        // (f) Threshold checks. Each dimension writes only its own flags/counters.

        // HEAP kill (unchanged): terminal, sets shouldKill and shouldStop directly — forceTerminate()
        // may halt the JVM before publishFlags() ever runs.
        if (memoryKillThresholdPercent > 0 && heapUsagePercent >= memoryKillThresholdPercent) {
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

        // HEAP critical (GC trigger).
        // Defer the GC suggestion while heap is in an active growth burst: firing
        // System.gc() mid-burst closes DataBuffers whose device memory the DSP decode
        // of an in-flight generation is still reading (the deallocator thread's
        // cudaFreeAsync is not ordered against the DSP plan stream), which surfaces
        // as NaN at gated_delta_rule. A burst that keeps climbing is still caught by
        // the kill path below; a transient burst subsides and GC fires on the next
        // quiet cycle.
        boolean heapBurstActive = heapVelocity > RAPID_GROWTH_THRESHOLD_PERCENT_PER_SECOND;
        boolean heapGcSuggested = false;
        if (heapUsagePercent >= memoryCriticalPercent) {
            if (heapBurstActive) {
                if (!heapCritical.get()) {
                    heapCritical.set(true);
                    logger.info("CRITICAL MEMORY: {}% used but heap growing at {}/s — "
                                    + "deferring GC until the burst subsides to avoid freeing "
                                    + "buffers an in-flight generation is reading",
                            String.format("%.1f", heapUsagePercent),
                            String.format("%.1f%%", heapVelocity));
                } else {
                    logger.info("CRITICAL MEMORY: {}% used, heap still bursting — GC deferred again",
                            String.format("%.1f", heapUsagePercent));
                }
            } else {
                if (!heapCritical.get()) {
                    heapCritical.set(true);
                }
                logger.warn("CRITICAL MEMORY: {}% used ({}MB/{}MB) - triggering GC",
                        String.format("%.1f", heapUsagePercent),
                        snapshot.usedMB, snapshot.maxMB);
                suggestGc();
                lastHeapGcSuggestionMs = r.timestampMs();
                heapGcSuggested = true;
            }
        } else {
            if (heapCritical.get()) {
                heapCritical.set(false);
                logger.info("Memory recovered from critical: {}%", String.format("%.1f", heapUsagePercent));
            }
        }

        // HEAP stop: debounce to set, -10 hysteresis band to clear (unchanged).
        if (heapUsagePercent >= memoryThresholdPercent) {
            int count = consecutiveStopChecks.incrementAndGet();
            if (count >= STOP_DEBOUNCE_COUNT && !heapStop.get()) {
                heapStop.set(true);
                logMemoryStop(snapshot, "HEAP");
            }
        } else {
            consecutiveStopChecks.set(0);
            if (heapStop.get() && heapUsagePercent < memoryThresholdPercent - 10) {
                heapStop.set(false);
                logger.info("Memory recovered below threshold ({}%), resuming", String.format("%.1f", heapUsagePercent));
            }
        }

        // Heap "used" includes garbage no collection has reclaimed yet, and a process gated on
        // shouldStop() allocates almost nothing, so nothing would ever collect it: a stop latched
        // on garbage would hold forever (the embedding service rejects every request while it
        // holds; model-init and ingest abort their job). So while the stop is pending or held,
        // suggest a GC on a quiet tick, at most once per HEAP_STOP_GC_INTERVAL_MS, and let the
        // next reading decide. Deferred mid-burst for the same reason as the critical GC above.
        if ((heapUsagePercent >= memoryThresholdPercent || heapStop.get())
                && !heapBurstActive && !heapGcSuggested) {
            long sinceLastGcMs = r.timestampMs() - lastHeapGcSuggestionMs;
            if (lastHeapGcSuggestionMs < 0 || sinceLastGcMs < 0 || sinceLastGcMs >= HEAP_STOP_GC_INTERVAL_MS) {
                logger.info("Heap at {}% with the stop {} - suggesting GC so the stop tracks the live set",
                        String.format("%.1f", heapUsagePercent), heapStop.get() ? "held" : "pending");
                suggestGc();
                lastHeapGcSuggestionMs = r.timestampMs();
            }
        }

        // GPU checks only run when this tick actually produced a reading. With none, there's no
        // evidence either way, so gpuStop/gpuCritical and their debounce counters are left
        // exactly as they were.
        if (worstGpu != null) {
            if (gpuMemoryKillThresholdPercent > 0 && worstGpuUsage >= gpuMemoryKillThresholdPercent) {
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

            if (worstGpuUsage >= gpuMemoryCriticalPercent) {
                if (!gpuCritical.get()) {
                    gpuCritical.set(true);
                    logger.warn("CRITICAL PROCESS-OWNED GPU MEMORY: {}% ({}MB/{}MB) on device {}",
                            String.format("%.1f", worstGpuUsage),
                            snapshot.gpuUsedMB, snapshot.gpuTotalMB, snapshot.gpuDeviceId);
                }
            } else {
                if (gpuCritical.get()) {
                    gpuCritical.set(false);
                    logger.info("Process-owned GPU memory recovered from critical: {}%",
                            String.format("%.1f", worstGpuUsage));
                }
            }

            if (worstGpuUsage >= gpuMemoryThresholdPercent) {
                int count = consecutiveGpuStopChecks.incrementAndGet();
                if (count >= STOP_DEBOUNCE_COUNT && !gpuStop.get()) {
                    gpuStop.set(true);
                    logMemoryStop(snapshot, "GPU");
                }
            } else {
                consecutiveGpuStopChecks.set(0);
                // Clear as soon as usage drops below threshold, NOT threshold - 10. That -10
                // band never actually took effect before: the heap branch cleared the shared
                // shouldStop every tick regardless of GPU state, so GPU's own band was never
                // observable. Enforcing it for real now would hold the embedding service
                // whenever resident weights sit between threshold-10 and threshold, and that
                // service rejects every compute request while shouldStop holds.
                if (gpuStop.get()) {
                    gpuStop.set(false);
                    logger.info("Process-owned GPU memory recovered below threshold ({}%), resuming",
                            String.format("%.1f", worstGpuUsage));
                }
            }
        }

        // OFF-HEAP checks only apply when a limit is configured.
        if (offHeapMaxBytes > 0) {
            if (offHeapKillThresholdPercent > 0 && offHeapUsagePercent >= offHeapKillThresholdPercent) {
                int count = consecutiveOffHeapKillChecks.incrementAndGet();
                if (count >= KILL_DEBOUNCE_COUNT && !shouldKill.get()) {
                    shouldKill.set(true);
                    shouldStop.set(true);
                    logMemoryKill(snapshot, "OFF-HEAP");
                    if (forceKillOnThreshold) {
                        forceTerminate(snapshot, "OFF-HEAP");
                    }
                }
            } else {
                consecutiveOffHeapKillChecks.set(0);
            }

            if (offHeapUsagePercent >= offHeapCriticalPercent) {
                if (!offHeapCritical.get()) {
                    offHeapCritical.set(true);
                    logger.warn("CRITICAL OFF-HEAP MEMORY: {}% used (JavaCPP: {}MB, Direct: {}MB, Limit: {}MB)",
                            String.format("%.1f", offHeapUsagePercent), snapshot.javacppMB, snapshot.directBufferMB,
                            snapshot.offHeapMaxMB);
                }
            } else {
                if (offHeapCritical.get()) {
                    offHeapCritical.set(false);
                    logger.info("Off-heap memory recovered from critical: {}%", String.format("%.1f", offHeapUsagePercent));
                }
            }

            if (offHeapUsagePercent >= offHeapThresholdPercent) {
                int count = consecutiveOffHeapStopChecks.incrementAndGet();
                if (count >= STOP_DEBOUNCE_COUNT && !offHeapStop.get()) {
                    offHeapStop.set(true);
                    logMemoryStop(snapshot, "OFF-HEAP");
                }
            } else {
                consecutiveOffHeapStopChecks.set(0);
                // Same reasoning as GPU stop above: clear below threshold, not threshold - 10.
                if (offHeapStop.get()) {
                    offHeapStop.set(false);
                    logger.info("Off-heap memory recovered below threshold ({}%), resuming",
                            String.format("%.1f", offHeapUsagePercent));
                }
            }
        }

        // (g) GC churn: fraction of the last interval spent in garbage collection. Sustained high
        // churn means the heap is thrashing — it slows the subprocess and tends to precede
        // batch failures/retries (e.g. UnsupportedOperationException on a stressed workspace).
        // Surface it as a WARN (with hysteresis) so the pressure is visible before it bites.
        long gcNow = r.gcCollectionTimeMs();
        if (lastGcCheckTimeMs > 0) {
            long wallDelta = r.timestampMs() - lastGcCheckTimeMs;
            long gcDelta = Math.max(0, gcNow - lastGcCollectionTimeMs);
            if (wallDelta > 0) {
                double gcFraction = (double) gcDelta / wallDelta;
                if (gcFraction >= gcChurnThresholdFraction) {
                    if (!gcChurn.get()) {
                        gcChurn.set(true);
                        logger.warn("GC CHURN DETECTED: {}% of the last {}ms spent in GC ({}ms) "
                                + "— heap pressure degrading throughput (heap at {}%)",
                                String.format("%.0f", gcFraction * 100), wallDelta, gcDelta,
                                String.format("%.1f", heapUsagePercent));
                    }
                } else if (gcFraction < gcChurnThresholdFraction - 0.1) {
                    if (gcChurn.get()) {
                        gcChurn.set(false);
                        logger.info("GC churn subsided: {}% of last interval in GC",
                                String.format("%.0f", gcFraction * 100));
                    }
                }
            }
        }
        lastGcCheckTimeMs = r.timestampMs();
        lastGcCollectionTimeMs = gcNow;

        // (h) Publish the three public flags once, as the OR of the per-dimension flags.
        publishFlags();

        // (i) Roll baselines forward for next tick's velocity calculations. The timestamp always
        // moves with the usage baselines (and the GPU history replaced above), even backwards:
        // keeping a newer timestamp beside an older reading would mis-pair the next diff, and a
        // wall-clock step back would otherwise freeze velocity until the clock caught up.
        lastHeapUsagePercent = heapUsagePercent;
        lastOffHeapUsagePercent = offHeapUsagePercent;
        lastVelocityCheckTimeMs = r.timestampMs();
    }

    /**
     * shouldStop/criticalMemory/rapidMemoryGrowth are each the OR of their three per-dimension
     * flags. This is the only writer of these three besides the terminal kill branches above,
     * which also set shouldStop directly and immediately (forceTerminate() can halt the JVM
     * before this ever runs).
     */
    private void publishFlags() {
        shouldStop.set(shouldKill.get() || heapStop.get() || gpuStop.get() || offHeapStop.get());
        criticalMemory.set(heapCritical.get() || gpuCritical.get() || offHeapCritical.get());
        rapidMemoryGrowth.set(heapRapid.get() || gpuRapid.get() || offHeapRapid.get());
    }

    /** Suggests a JVM GC. Package-private so tests can count suggestions without running a GC. */
    void suggestGc() {
        System.gc();
    }

    /**
     * Build the tick's single {@link MemorySnapshot} from its {@link Readings} plus the
     * velocities and off-heap percentage {@link #evaluate} computed. Pure arithmetic — no
     * ND4J/JavaCPP/MXBean calls.
     */
    private MemorySnapshot buildSnapshot(Readings r, double heapVelocity, double gpuVelocity,
            double offHeapUsagePercent, double offHeapVelocity) {
        long heapUsedBytes = r.heapTotalBytes() - r.heapFreeBytes();
        double heapUsagePercent = r.heapMaxBytes() > 0
                ? (heapUsedBytes * 100.0) / r.heapMaxBytes() : 0.0;
        GpuProbe worstGpu = highestUsageGpuProbe(r.gpuProbes());
        long gpuUsedMB = worstGpu != null ? worstGpu.usedBytes() / (1024 * 1024) : 0;
        long gpuTotalMB = worstGpu != null ? worstGpu.totalBytes() / (1024 * 1024) : 0;
        double gpuUsagePercent = worstGpu != null ? worstGpu.usagePercent() : 0.0;
        int gpuDeviceId = worstGpu != null ? worstGpu.deviceId() : -1;
        long offHeapMaxMB = offHeapMaxBytes > 0 ? offHeapMaxBytes / (1024 * 1024) : 0;
        return new MemorySnapshot(
                r.heapMaxBytes() / (1024 * 1024), r.heapTotalBytes() / (1024 * 1024),
                r.heapFreeBytes() / (1024 * 1024), heapUsedBytes / (1024 * 1024), heapUsagePercent,
                r.timestampMs(),
                gpuUsedMB, gpuTotalMB, gpuUsagePercent, gpuDeviceId,
                heapVelocity, gpuVelocity,
                r.javacppBytes() / (1024 * 1024), r.directBufferBytes() / (1024 * 1024),
                offHeapMaxMB, offHeapUsagePercent, offHeapVelocity);
    }

    /**
     * Heap-only initial snapshot for the test constructor: builds a {@link Readings} from
     * {@code Runtime} numbers only — no GPU probes, no off-heap bytes — so construction never
     * touches ND4J/NativeOps/JavaCPP even when {@code gpuMonitoringEnabledOverride} is true.
     */
    private MemorySnapshot captureHeapOnlySnapshot() {
        Runtime runtime = Runtime.getRuntime();
        Readings r = new Readings(System.currentTimeMillis(), runtime.maxMemory(), runtime.totalMemory(),
                runtime.freeMemory(), 0L, List.of(), 0L, 0L, 0L);
        return buildSnapshot(r, 0.0, 0.0, 0.0, 0.0);
    }

    private void logMemoryStop(MemorySnapshot snapshot, String memoryType) {
        String msg = switch (memoryType) {
            case "GPU" -> String.format(
                    "PROCESS-OWNED GPU MEMORY THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) %s - signaling graceful stop",
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
                    "PROCESS-OWNED GPU MEMORY KILL THRESHOLD EXCEEDED: %.1f%% used (%dMB/%dMB) %s - signaling immediate termination",
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

    /**
     * Per-device GPU memory probe. {@code driverUsedBytes} is diagnostic only and is -1 when
     * the driver gave no free-memory reading.
     */
    record GpuProbe(int deviceId, long usedBytes, long totalBytes, double usagePercent,
                    long driverUsedBytes) {}

    /** Select the limiting device without diluting its utilization across sibling GPUs. */
    static GpuProbe highestUsageGpuProbe(List<GpuProbe> probes) {
        GpuProbe highest = null;
        for (GpuProbe probe : probes) {
            if (probe != null
                    && (highest == null || probe.usagePercent() > highest.usagePercent())) {
                highest = probe;
            }
        }
        return highest;
    }

    /**
     * Build an ownership-aware probe from PHYSICAL occupancy signals only.
     *
     * <p>The JVM-side DataBuffer byte tracker ({@code trackedBytes}) counts LOGICAL bytes and
     * double-counts aliased device memory (plan slot arrays, KV slots, and weight copies that
     * share the same pool pages), so it can exceed the card's physical capacity and must never
     * drive kill thresholds. Physical process occupancy is the native CUDA mempool's used
     * bytes ({@code nativePoolUsedBytes} = cudaMemPoolAttrUsedMemCurrent), which every pool and
     * allocateDirect allocation flows through. {@code driverFreeBytes} is physical truth for
     * card exhaustion: when the device itself is out of free memory, allocations fail
     * regardless of page ownership, so near-total physical exhaustion escalates the probe
     * even if our mempool accounting is lagging.</p>
     *
     * <p>A {@code driverFreeBytes} of 0 is not a reading. The native free-memory query returns
     * 0 when it fails, e.g. after a sticky CUDA error such as 700 or on a GPU that cannot
     * create a context, while the total still comes back from the device properties. Reading
     * that 0 as "no bytes free" turned a CUDA fault into a 100% GPU memory kill.</p>
     *
     * <p>The card-exhaustion override below only escalates when this process is plausibly the
     * <b>dominant tenant</b>: {@code nativePoolUsedBytes} must be at least half of
     * {@code driverUsedBytes}. Without that check, a co-tenant filling the card gets charged to
     * whichever process happens to be probing. On 2026-09-23 an embedding subprocess with a
     * failed model load (33MB heap, near-zero pool) was halted at "99.8% used
     * (24039MB/24084MB) on device 0" purely because a co-tenant LLM had filled the card.
     * {@code trackedBytes} is logical, never physical, and must never stand in as evidence here.</p>
     */
    static GpuProbe processOwnedGpuProbe(int deviceId, long totalBytes, long driverFreeBytes,
                                         long trackedBytes, long nativePoolUsedBytes) {
        if (totalBytes <= 0 || (nativePoolUsedBytes < 0 && trackedBytes <= 0)) {
            return null;
        }
        boolean driverReading = driverFreeBytes > 0;
        if (!driverReading && nativePoolUsedBytes < 0) {
            // No physical signal left; logical tracked bytes must never drive a kill.
            return null;
        }
        long driverUsedBytes = driverReading
                ? Math.max(0, Math.min(totalBytes, totalBytes - driverFreeBytes))
                : -1;
        // Physical occupancy: native mempool used. If the mempool probe is unavailable,
        // fall back to driver-wide used (conservative: includes other processes' pages,
        // which is the safe direction for a kill signal).
        long ownedBytes = nativePoolUsedBytes >= 0
                ? nativePoolUsedBytes
                : driverUsedBytes;
        // Card-exhaustion override: if the device is physically out of free memory (<1%) AND
        // this process's own mempool usage is at least half of the driver-wide used total
        // (dominant-tenant rule), treat it as full. The dominance check is what's new here:
        // a co-tenant can fill the card while this process holds almost nothing, and charging
        // that occupancy to whichever process happens to be probing kills an innocent process.
        if (driverReading && driverFreeBytes < (totalBytes / 100)
                && nativePoolUsedBytes >= 0 && driverUsedBytes > 0
                && nativePoolUsedBytes * 2 >= driverUsedBytes) {
            ownedBytes = Math.max(ownedBytes, driverUsedBytes);
        }
        ownedBytes = Math.min(totalBytes, ownedBytes);
        double usage = (ownedBytes * 100.0) / totalBytes;
        return new GpuProbe(deviceId, ownedBytes, totalBytes, usage, driverUsedBytes);
    }

    /** Query one GPU for process-owned memory usage. Returns null when ownership is unavailable. */
    private static GpuProbe queryGpu(NativeOps ops, DeviceMemoryManager deviceMemory, int deviceId) {
        try {
            long total = ops.getDeviceTotalMemory(deviceId);
            long free = ops.getDeviceFreeMemory(deviceId);
            if (free <= 0) {
                if (devicesWithoutFreeReading.add(deviceId)) {
                    logger.warn("GPU{} free-memory query gave no reading; not counting it as memory "
                            + "exhaustion. Either a sticky CUDA error broke this device's context (look "
                            + "above for 'Error code [700]' or 'illegal memory access') or the device "
                            + "could not create a context.", deviceId);
                }
            } else if (devicesWithoutFreeReading.remove(deviceId)) {
                logger.info("GPU{} free-memory query is reporting again: {}MB free",
                        deviceId, free / (1024 * 1024));
            }
            DeviceDescriptor registered = deviceMemory.getRegisteredDevice(deviceId);
            long tracked = registered == null ? 0 : deviceMemory.getAllocatedMemory(registered);
            long nativePoolUsed = deviceMemory.getNativePoolUsedMemory(deviceId);
            GpuProbe probe = processOwnedGpuProbe(
                    deviceId, total, free, tracked, nativePoolUsed);
            if (probe != null && probe.driverUsedBytes() > probe.usedBytes()) {
                logger.trace("GPU{} driver-wide usage={}MB, process-owned={}MB",
                        deviceId, probe.driverUsedBytes() / (1024 * 1024),
                        probe.usedBytes() / (1024 * 1024));
            }
            return probe;
        } catch (Exception e) {
            logger.debug("Failed to probe process-owned GPU memory on device {}", deviceId, e);
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
