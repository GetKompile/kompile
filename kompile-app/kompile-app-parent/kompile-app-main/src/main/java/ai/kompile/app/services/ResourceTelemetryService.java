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

package ai.kompile.app.services;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceSnapshot.GpuSnapshot;
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls host resource metrics on a fixed cadence and publishes an immutable {@link ResourceSnapshot}
 * that the {@link ResourceGovernor} (and, via the governor, the crawl pipeline) reads lock-free.
 *
 * <p>This is the single source of truth for "what's strained right now". It reuses the same collectors
 * proven in {@code SystemResourceBroadcaster} — {@code com.sun.management.OperatingSystemMXBean.getCpuLoad()}
 * for CPU, {@code getTotalMemorySize()/getFreeMemorySize()} for system RAM, and
 * {@code NativeOps.getDeviceFreeMemory/getDeviceTotalMemory} per GPU — but runs on its own ~1s schedule so
 * the governor never depends on whether a WebSocket subscriber is active.</p>
 *
 * <p>Per-GPU pressure is computed conservatively as {@code max(actualUsed, reserved)/total}, where the
 * reserved portion comes from {@link GpuResourceManager}'s reservation ledger — so a device that is
 * lightly used but heavily reserved is still treated as pressured, preventing admission of work that would
 * collide with an imminent reservation.</p>
 */
@Service
public class ResourceTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(ResourceTelemetryService.class);

    private static final long POLL_INTERVAL_MS = 1000L;

    @Autowired(required = false)
    private GpuResourceManager gpuResourceManager;

    @Autowired
    private ResourceSchedulerConfigService configService;

    private volatile ResourceSnapshot latest = ResourceSnapshot.unavailable();
    private volatile boolean gpuBackend = false;
    private ScheduledExecutorService poller;

    @PostConstruct
    public void start() {
        // Probe backend type once (cheap, and avoids repeated string work each poll).
        try {
            String backend = Nd4j.getBackend().getClass().getSimpleName().toLowerCase();
            gpuBackend = backend.contains("cuda") || backend.contains("gpu") || backend.contains("aurora");
        } catch (Throwable t) {
            gpuBackend = false;
        }

        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resource-telemetry");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("ResourceTelemetryService started (interval={}ms, gpuBackend={})", POLL_INTERVAL_MS, gpuBackend);
    }

    @PreDestroy
    public void stop() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /** The most recent snapshot. Lock-free; never null (returns {@link ResourceSnapshot#unavailable()}). */
    public ResourceSnapshot latest() {
        ResourceSnapshot s = latest;
        return s != null ? s : ResourceSnapshot.unavailable();
    }

    /** Force an immediate refresh (used by tests and on-demand status endpoints). */
    public ResourceSnapshot refreshNow() {
        poll();
        return latest();
    }

    private void poll() {
        try {
            latest = collect();
        } catch (Throwable t) {
            log.debug("Resource telemetry poll failed: {}", t.getMessage());
        }
    }

    private ResourceSnapshot collect() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();

        double cpuLoad = collectCpuLoad();
        double ramUsed = collectSystemRamUsedFraction();
        double heapUsed = collectHeapUsedFraction();

        PressureLevel cpuPressure = cpuLoad < 0
                ? PressureLevel.NOMINAL
                : classify(cpuLoad, cfg.getGovernorCpuHighThreshold(), cfg.getGovernorCpuCriticalThreshold());
        PressureLevel ramPressure =
                classify(ramUsed, cfg.getGovernorRamHighThreshold(), cfg.getGovernorRamCriticalThreshold());
        PressureLevel heapPressure =
                classify(heapUsed, cfg.getGovernorRamHighThreshold(), cfg.getGovernorRamCriticalThreshold());

        List<GpuSnapshot> gpus = new ArrayList<>();
        boolean gpuAvailable = false;
        if (gpuBackend) {
            try {
                NativeOps ops = NativeOpsHolder.getInstance().getDeviceNativeOps();
                int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
                for (int i = 0; i < numDevices; i++) {
                    long total = ops.getDeviceTotalMemory(i);
                    long free = ops.getDeviceFreeMemory(i);
                    if (total <= 0) {
                        continue;
                    }
                    long reserved = reservedBytesForDevice(i);
                    double actualUsed = (double) (total - free) / total;
                    double reservedFrac = (double) reserved / total;
                    double usedFraction = Math.min(1.0, Math.max(actualUsed, reservedFrac));
                    PressureLevel vram = classify(usedFraction,
                            cfg.getGovernorVramHighFraction(), cfg.getGovernorVramCriticalFraction());
                    gpus.add(new GpuSnapshot(i, free, total, reserved, usedFraction, vram));
                    gpuAvailable = true;
                }
            } catch (Throwable t) {
                log.debug("GPU telemetry unavailable: {}", t.getMessage());
            }
        }

        return new ResourceSnapshot(System.nanoTime(), cpuLoad, ramUsed, heapUsed, 0.0,
                cpuPressure, ramPressure, heapPressure, PressureLevel.NOMINAL, gpus, gpuAvailable);
    }

    private long reservedBytesForDevice(int cudaRuntimeIndex) {
        if (gpuResourceManager == null) {
            return 0L;
        }
        return gpuResourceManager.getDeviceByCudaRuntimeIndex(cudaRuntimeIndex)
                .map(gpuResourceManager::getReservedMemory)
                .orElse(0L);
    }

    private double collectCpuLoad() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getCpuLoad(); // 0..1, or negative when unavailable
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return -1.0;
    }

    private double collectSystemRamUsedFraction() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                long total = os.getTotalMemorySize();
                if (total > 0) {
                    // Prefer Linux MemAvailable: the kernel's estimate of memory allocatable WITHOUT
                    // swapping, which correctly treats reclaimable page cache (buff/cache) as available.
                    // getFreeMemorySize() is MemFree only — so after heavy disk I/O (large builds) it
                    // counts gigabytes of reclaimable cache as "used" and falsely reports RAM pressure
                    // CRITICAL, blocking GPU jobs even when ~all memory is actually available.
                    long available = readMemAvailableBytes();
                    if (available < 0) {
                        available = os.getFreeMemorySize(); // non-Linux / unreadable: fall back to MemFree
                    }
                    return Math.max(0.0, Math.min(1.0, (double) (total - available) / total));
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 0.0;
    }

    /** Linux MemAvailable in bytes from /proc/meminfo, or -1 when unavailable (non-Linux / parse error). */
    private static long readMemAvailableBytes() {
        try {
            java.nio.file.Path meminfo = java.nio.file.Path.of("/proc/meminfo");
            if (!java.nio.file.Files.isReadable(meminfo)) {
                return -1L;
            }
            for (String line : java.nio.file.Files.readAllLines(meminfo)) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]) * 1024L; // value is in kB
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return -1L;
    }

    private double collectHeapUsedFraction() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        return max > 0 ? Math.min(1.0, (double) used / max) : 0.0;
    }

    private static PressureLevel classify(double value, double high, double critical) {
        if (value >= critical) {
            return PressureLevel.CRITICAL;
        }
        if (value >= high) {
            return PressureLevel.HIGH;
        }
        if (value >= high * 0.7) {
            return PressureLevel.ELEVATED;
        }
        return PressureLevel.NOMINAL;
    }
}
