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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors JVM heap and native memory during crawl pipeline execution.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component
class CrawlMemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(CrawlMemoryMonitor.class);

    /** Minimum interval between full memory snapshots (MXBean + JavaCPP native calls). */
    private static final long MEMORY_SNAPSHOT_INTERVAL_NANOS = 2_000_000_000L; // 2 seconds
    private volatile long lastMemorySnapshotNanos = 0L;

    /** Rate-limit trimNativeMemory to avoid repeated expensive cleanup within short windows. */
    private final AtomicLong lastTrimNanos = new AtomicLong(0);
    private static final long TRIM_MIN_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

    // Config fields — synced from orchestrator via applyConfig()
    volatile int memoryWaitThresholdPercent = 82;
    volatile int memoryCriticalThresholdPercent = 90;
    volatile int memoryWaitTimeoutSeconds = 300;
    volatile boolean nativeMemoryCleanupEnabled = true;
    volatile int nativeMemoryCleanupPasses = 3;
    volatile int nativeMemoryWaitThresholdPercent = 82;
    volatile int nativeMemoryCriticalThresholdPercent = 90;

    void applyConfig(int waitThreshold, int criticalThreshold, int waitTimeoutSecs,
                     boolean nativeEnabled, int nativePasses,
                     int nativeWaitThreshold, int nativeCriticalThreshold) {
        this.memoryWaitThresholdPercent = waitThreshold;
        this.memoryCriticalThresholdPercent = criticalThreshold;
        this.memoryWaitTimeoutSeconds = waitTimeoutSecs;
        this.nativeMemoryCleanupEnabled = nativeEnabled;
        this.nativeMemoryCleanupPasses = nativePasses;
        this.nativeMemoryWaitThresholdPercent = nativeWaitThreshold;
        this.nativeMemoryCriticalThresholdPercent = nativeCriticalThreshold;
    }

    void updateMemorySnapshot(UnifiedCrawlJob job) {
        long now = System.nanoTime();
        boolean fullSnapshot = (now - lastMemorySnapshotNanos) >= MEMORY_SNAPSHOT_INTERVAL_NANOS;

        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        int percent = max > 0 ? (int) Math.min(100, Math.round((used * 100.0) / max)) : 0;
        job.getHeapUsedBytes().set(used);
        job.getHeapMaxBytes().set(max);
        job.getMemoryUsagePercent().set(percent);
        job.getPeakMemoryUsagePercent().accumulateAndGet(percent, Math::max);

        if (!fullSnapshot) return;
        lastMemorySnapshotNanos = now;

        long directBytes = 0L;
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    directBytes = Math.max(directBytes, pool.getMemoryUsed());
                }
            }
        } catch (Throwable ignored) {
            directBytes = 0L;
        }
        job.getDirectBufferBytes().set(Math.max(0L, directBytes));

        try {
            long physicalBytes = Math.max(0L, Pointer.physicalBytes());
            long totalBytes = Math.max(0L, Pointer.totalBytes());
            long maxPhysicalBytes = Math.max(0L, Pointer.maxPhysicalBytes());
            int nativePercent = maxPhysicalBytes > 0
                    ? (int) Math.min(100, Math.round((physicalBytes * 100.0) / maxPhysicalBytes))
                    : 0;
            job.getNativePhysicalBytes().set(physicalBytes);
            job.getPeakNativePhysicalBytes().accumulateAndGet(physicalBytes, Math::max);
            job.getNativeTotalBytes().set(totalBytes);
            job.getNativeMaxPhysicalBytes().set(maxPhysicalBytes);
            job.getNativeMemoryUsagePercent().set(nativePercent);
            job.getPeakNativeMemoryUsagePercent().accumulateAndGet(nativePercent, Math::max);
        } catch (Throwable ignored) {
            // JavaCPP accounting is not available for every runtime/backend.
        }
    }

    boolean hasMemoryPressure(UnifiedCrawlJob job, boolean critical) {
        int heapThreshold = critical ? memoryCriticalThresholdPercent : memoryWaitThresholdPercent;
        boolean heapPressure = heapThreshold > 0 && job.getMemoryUsagePercent().get() >= heapThreshold;

        int nativeThreshold = critical ? nativeMemoryCriticalThresholdPercent : nativeMemoryWaitThresholdPercent;
        boolean nativePressure = hasNativeMemoryPressure(job, nativeThreshold);
        return heapPressure || nativePressure;
    }

    boolean hasNativeMemoryPressure(UnifiedCrawlJob job, int thresholdPercent) {
        return job.getNativeMaxPhysicalBytes().get() > 0
                && thresholdPercent > 0
                && job.getNativeMemoryUsagePercent().get() >= thresholdPercent;
    }

    String memoryPressureDetail(UnifiedCrawlJob job) {
        return "heap=" + job.getMemoryUsagePercent().get() + "%"
                + " (" + formatBytes(job.getHeapUsedBytes().get()) + "/"
                + formatBytes(job.getHeapMaxBytes().get()) + ")"
                + ", native=" + job.getNativeMemoryUsagePercent().get() + "%"
                + " (" + formatBytes(job.getNativePhysicalBytes().get()) + "/"
                + formatBytes(job.getNativeMaxPhysicalBytes().get()) + " physical, total="
                + formatBytes(job.getNativeTotalBytes().get()) + ")"
                + ", direct=" + formatBytes(job.getDirectBufferBytes().get())
                + ", thresholds heap=" + memoryWaitThresholdPercent + "/" + memoryCriticalThresholdPercent + "%"
                + ", native=" + nativeMemoryWaitThresholdPercent + "/" + nativeMemoryCriticalThresholdPercent + "%";
    }

    /**
     * Trim native memory pools (JavaCPP, ND4J workspaces, CUDA pools).
     * Rate-limited to at most once per 5 seconds.
     *
     * @param job the crawl job for recording memory stats
     * @param phase current pipeline phase (for logging)
     * @param reason why trim was requested
     * @return detail string describing what was trimmed, or null if skipped
     */
    String trimNativeMemory(UnifiedCrawlJob job, String phase, String reason) {
        if (!nativeMemoryCleanupEnabled) {
            return null;
        }
        long now = System.nanoTime();
        long last = lastTrimNanos.get();
        if (now - last < TRIM_MIN_INTERVAL_NS) {
            return null;
        }
        if (!lastTrimNanos.compareAndSet(last, now)) {
            return null;
        }

        updateMemorySnapshot(job);
        long beforePhysical = job.getNativePhysicalBytes().get();
        long beforeTotal = job.getNativeTotalBytes().get();
        int devicesTrimmed = 0;
        int pointerPasses = 0;
        int passes = 1;

        for (int pass = 0; pass < passes; pass++) {
            try {
                Pointer.deallocateReferences();
                pointerPasses++;
            } catch (Throwable t) {
                log.debug("[Job {}] JavaCPP reference cleanup skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                Nd4j.getExecutioner().commit();
            } catch (Throwable t) {
                log.debug("[Job {}] ND4J commit skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            } catch (Throwable t) {
                log.debug("[Job {}] Workspace cleanup skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                int devices = Math.max(1, Nd4j.getAffinityManager().getNumberOfDevices());
                for (int d = 0; d < devices; d++) {
                    Nd4j.getNativeOps().trimMemoryPool(d);
                    devicesTrimmed++;
                }
            } catch (Throwable t) {
                log.debug("[Job {}] Native pool trim skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }

            try {
                Pointer.deallocateReferences();
            } catch (Throwable t) {
                log.debug("[Job {}] Final native cleanup pass skipped during {} cleanup: {}",
                        job.getJobId(), phase, t.getMessage());
            }
        }

        updateMemorySnapshot(job);
        long afterPhysical = job.getNativePhysicalBytes().get();
        long afterTotal = job.getNativeTotalBytes().get();
        long physicalDelta = beforePhysical - afterPhysical;
        long totalDelta = beforeTotal - afterTotal;
        return "reason=" + reason
                + ", cleanupPasses=" + passes
                + ", pointerPasses=" + pointerPasses
                + ", devicesTrimmed=" + devicesTrimmed
                + ", physical=" + formatBytes(beforePhysical) + " -> " + formatBytes(afterPhysical)
                + " (delta=" + formatBytes(physicalDelta) + ")"
                + ", total=" + formatBytes(beforeTotal) + " -> " + formatBytes(afterTotal)
                + " (delta=" + formatBytes(totalDelta) + ")";
    }

    /**
     * Waits for JVM heap and native memory to drop below the configured wait threshold.
     *
     * <p>Performs a one-time cleanup (trim native memory + GC), then spin-waits with
     * exponential back-off up to {@link #memoryWaitTimeoutSeconds}. Returns {@code true}
     * when memory pressure has dropped below the wait threshold, or {@code false} if the
     * timeout was reached or the job was cancelled.</p>
     *
     * @param job   the crawl job (used for cancellation checks and memory snapshot updates)
     * @param phase the current pipeline phase (used for log context)
     * @return {@code true} if memory pressure dropped, {@code false} on timeout or cancellation
     */
    boolean waitForMemoryCapacity(UnifiedCrawlJob job, String phase) {
        updateMemorySnapshot(job);
        if (!hasMemoryPressure(job, false)) {
            return true;
        }

        String detail = memoryPressureDetail(job);
        job.getCurrentBatchStep().set("MEMORY_BACKPRESSURE");
        log.warn("[Job {}] Memory pressure before phase {}: {}", job.getJobId(), phase, detail);

        // One-time cleanup attempt: trim native memory and request GC once
        trimNativeMemory(job, phase, "memory backpressure");
        System.gc();
        updateMemorySnapshot(job);

        if (!hasMemoryPressure(job, true)) {
            updateMemorySnapshot(job);
            return true;
        }

        // Spin-wait with increasing sleep intervals.  Do NOT call System.gc() or
        // trimNativeMemory on every iteration — they cause full stop-the-world pauses
        // and the cleanup work above already freed everything reclaimable.
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(Math.max(1, memoryWaitTimeoutSeconds));
        int iteration = 0;
        while (System.currentTimeMillis() < deadline) {
            // Check for job cancellation inline
            if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
                return false;
            }
            try {
                // Back-off: 2s, 3s, 4s, then cap at 5s — avoids tight-loop overhead
                long sleepMs = Math.min(5000L, 2000L + iteration * 1000L);
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            updateMemorySnapshot(job);
            // Only re-attempt GC every ~15 seconds (every 5th iteration after back-off stabilizes)
            if (iteration > 0 && iteration % 5 == 0) {
                System.gc();
            }
            if (!hasMemoryPressure(job, false)) {
                job.getCurrentBatchStep().set(null);
                return true;
            }
            iteration++;
        }
        log.warn("[Job {}] Continuing despite memory pressure after waiting {}s: {}",
                job.getJobId(), memoryWaitTimeoutSeconds, memoryPressureDetail(job));
        return false;
    }

    String formatBytes(long bytes) {
        if (bytes == 0) return "0B";
        boolean negative = bytes < 0;
        double value = Math.abs((double) bytes);
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        String formatted = unit == 0
                ? String.format(Locale.ROOT, "%d%s", Math.abs(bytes), units[unit])
                : String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
        return negative ? "-" + formatted : formatted;
    }
}
