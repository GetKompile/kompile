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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.ResourceGovernorAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decision authority over the live {@link ResourceSnapshot}. This is the single place that turns raw
 * CPU/RAM/heap/GPU-VRAM pressure into the routing decisions the pipeline needs:
 *
 * <ul>
 *   <li>{@link #admitJob} — adds the CPU and host-RAM admission gates the scheduler historically
 *       lacked (it gated on GPU reservations only).</li>
 *   <li>{@link #effectiveMemoryPressure} — the unified 0..1 signal fed to {@code DynamicBatchSizer}
 *       (heap + GPU VRAM for GPU stages) so batches shrink under real VRAM pressure and grow into
 *       free GPU.</li>
 *   <li>{@link #isGpuVramPressured} / {@link #shouldDeferLocalWork} — backpressure and deferral of
 *       heavy local GPU work (embeddings) instead of allocating into a full device.</li>
 *   <li>{@link #concurrencyForSchedulerPool} / {@link #hasGpuHeadroom} — dynamic pool sizing and the
 *       resumer's GPU-headroom gate.</li>
 * </ul>
 *
 * <p>Implements {@link ResourceGovernorAdapter} so crawl-graph components (which cannot see app-main
 * types) reach it through that dependency-free interface. All reads are lock-free.</p>
 */
@Service
public class ResourceGovernor implements ResourceGovernorAdapter {

    /** Pipeline stages whose memory pressure includes GPU VRAM. */
    private static final Set<String> GPU_STAGES =
            Set.of("EMBEDDING", "VECTOR_INDEXING", "ENTITY_RESOLUTION", "EDGE_COMPUTATION");

    @Autowired
    private ResourceTelemetryService telemetry;

    @Autowired
    private ResourceSchedulerConfigService configService;

    /** Optional: lets the GPU-headroom gate recognise stages already migrated to CPU. */
    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    /** Optional: surfaces sustained-load GPU→CPU migration state on the status endpoint. */
    @Autowired(required = false)
    private GpuToCpuMigrationService gpuToCpuMigrationService;

    /** Result of an admission decision. {@code admit()} is the record accessor (was it admitted?). */
    public record AdmissionResult(boolean admit, String blockReason) {
        public static AdmissionResult allow() {
            return new AdmissionResult(true, null);
        }

        public static AdmissionResult defer(String reason) {
            return new AdmissionResult(false, reason);
        }
    }

    /**
     * Decide whether a job may start now given current CPU and host-RAM pressure. GPU-memory
     * admission stays with {@code GpuResourceManager.canFit()} in the scheduler — this method only
     * adds the CPU/RAM gates. GPU-requiring jobs are NOT deferred on high CPU (they block on GPU
     * anyway); CPU-bound jobs are.
     *
     * <p>In addition to the fractional RAM threshold, this method also defers when the absolute
     * MemAvailable is below the configured {@code governorRamFloorMb} (default 8 GB). This
     * prevents OOM crashes on large-model hosts where the fractional threshold may still leave
     * only a few hundred MB free.</p>
     */
    public AdmissionResult admitJob(JobResourceProfile profile) {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isGovernorEnabled()) {
            return AdmissionResult.allow();
        }
        ResourceSnapshot s = telemetry.latest();

        // Hard absolute floor check (additional to the fractional threshold).
        String floorReason = absoluteRamFloorReason(s, cfg);
        if (floorReason != null) {
            return AdmissionResult.defer(floorReason);
        }

        if (s.ramPressure().atLeast(PressureLevel.HIGH)) {
            return AdmissionResult.defer(String.format(Locale.ROOT,
                    "System RAM pressure %s (%.0f%% used)", s.ramPressure(), s.systemRamUsedFraction() * 100));
        }

        boolean gpuJob = profile != null && profile.requiresGpu();
        if (!gpuJob && s.systemCpuLoad() >= 0 && s.cpuPressure().atLeast(PressureLevel.HIGH)) {
            return AdmissionResult.defer(String.format(Locale.ROOT,
                    "System CPU load %s (%.0f%%)", s.cpuPressure(), s.systemCpuLoad() * 100));
        }

        return AdmissionResult.allow();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} when either the absolute MemAvailable is below the configured
     * floor ({@code governorRamFloorMb}) or the fractional RAM pressure is at/above HIGH.
     * This is a stage-agnostic gate for heavy-memory operations (KGE training, batch embedding)
     * that should not start when host RAM is critically constrained.</p>
     */
    @Override
    public boolean shouldThrottleHeavyMemory() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isGovernorEnabled()) {
            return false;
        }
        ResourceSnapshot s = telemetry.latest();
        return absoluteRamFloorReason(s, cfg) != null || s.ramPressure().atLeast(PressureLevel.HIGH);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a human-readable reason when the host is memory-constrained, or {@code null}
     * when memory is healthy. Considers both the absolute floor and fractional threshold.</p>
     */
    @Override
    public String memoryPressureReason() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isGovernorEnabled()) {
            return null;
        }
        ResourceSnapshot s = telemetry.latest();
        String floorReason = absoluteRamFloorReason(s, cfg);
        if (floorReason != null) {
            return floorReason;
        }
        if (s.ramPressure().atLeast(PressureLevel.HIGH)) {
            return String.format(Locale.ROOT,
                    "RAM pressure %s (%.0f%% used)", s.ramPressure(), s.systemRamUsedFraction() * 100);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reports live {@code MemAvailable} minus the OOM-floor reserve ({@code governorRamFloorMb}),
     * i.e. the RAM heavy ops may draw on without breaching the floor that {@link #shouldThrottleHeavyMemory}
     * defends. Returns {@code -1} when the governor is disabled or {@code MemAvailable} is not readable
     * (non-Linux) so the coordinator degrades to serial admission.</p>
     */
    @Override
    public long availableMemoryMbForHeavyOps() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isGovernorEnabled()) {
            return -1;
        }
        long availMb = telemetry.latest().memAvailableMb();
        if (availMb < 0) {
            return -1; // MemAvailable unreadable (non-Linux) — coordinator runs heavy ops serially
        }
        long floorMb = Math.max(0, cfg.getGovernorRamFloorMb());
        return Math.max(0, availMb - floorMb);
    }

    /**
     * Checks the hard absolute RAM floor: returns a non-null reason string when
     * MemAvailable is known AND below {@code governorRamFloorMb}; returns {@code null}
     * when the floor is not breached or when the floor is disabled (0) or MemAvailable
     * is unavailable (-1).
     */
    private static String absoluteRamFloorReason(ResourceSnapshot s, ResourceSchedulerConfig cfg) {
        long floorMb = cfg.getGovernorRamFloorMb();
        if (floorMb <= 0) {
            return null; // floor disabled
        }
        long availMb = s.memAvailableMb();
        if (availMb < 0) {
            return null; // unavailable on non-Linux
        }
        if (availMb < floorMb) {
            return String.format(Locale.ROOT,
                    "MemAvailable %d MB < floor %d MB (OOM floor)", availMb, floorMb);
        }
        return null;
    }

    @Override
    public double effectiveMemoryPressure(String stage) {
        ResourceSnapshot s = telemetry.latest();
        double pressure = Math.max(s.jvmHeapUsedFraction(), s.nativeOffHeapFraction());
        if (s.gpuBackendAvailable() && isGpuStage(stage)) {
            pressure = Math.max(pressure, s.worstGpuUsedFraction());
        }
        return Math.max(0.0, Math.min(1.0, pressure));
    }

    @Override
    public boolean isGpuVramPressured(boolean critical) {
        ResourceSnapshot s = telemetry.latest();
        if (!s.gpuBackendAvailable()) {
            return false;
        }
        PressureLevel threshold = critical ? PressureLevel.CRITICAL : PressureLevel.HIGH;
        return s.worstGpuPressure().atLeast(threshold);
    }

    @Override
    public boolean shouldDeferLocalWork(String workloadKind) {
        ResourceSnapshot s = telemetry.latest();
        if (!s.gpuBackendAvailable()) {
            return false; // no GPU to protect — CPU embedding proceeds
        }
        // Defer heavy local GPU work only at the critical band (HIGH still proceeds with smaller batches).
        return s.worstGpuPressure().atLeast(PressureLevel.CRITICAL);
    }

    /**
     * GPU headroom gate for the deferred-embedding resumer: true when there is room to embed now.
     * CPU-only backends always have "headroom" (embedding runs on CPU).
     */
    public boolean hasGpuHeadroom(String workloadKind) {
        ResourceSnapshot s = telemetry.latest();
        if (!s.gpuBackendAvailable()) {
            return true;
        }
        // If this workload's stage has been migrated to CPU, GPU pressure is irrelevant — it no longer
        // needs GPU headroom to proceed (it runs on the multi-backend's CPU side).
        if (deviceRoutingConfigService != null
                && deviceRoutingConfigService.isRoutedToCpu(serviceForWorkload(workloadKind))) {
            return true;
        }
        return !s.worstGpuPressure().atLeast(PressureLevel.HIGH);
    }

    /**
     * True when the local host has no spare CPU/RAM headroom to absorb more work. This is the signal for the
     * remote-peer failover tier: once local CPU is saturated (so the GPU&rarr;CPU migration can no longer
     * help), offload to a cluster worker rather than pile onto a full host.
     */
    public boolean isLocalSaturated() {
        ResourceSnapshot s = telemetry.latest();
        return s.cpuPressure().atLeast(PressureLevel.HIGH) || s.ramPressure().atLeast(PressureLevel.HIGH);
    }

    /** Map a governor workloadKind to its {@link DeviceRoutingConfig} {@code SERVICE_*} route name. */
    private static String serviceForWorkload(String workloadKind) {
        if (workloadKind == null) {
            return "";
        }
        return switch (workloadKind.toUpperCase(Locale.ROOT)) {
            case "EMBEDDING" -> DeviceRoutingConfig.SERVICE_EMBEDDING;
            case "VECTOR_INDEXING", "VECTOR_POPULATION" -> DeviceRoutingConfig.SERVICE_VECTOR_POPULATION;
            case "INGEST" -> DeviceRoutingConfig.SERVICE_INGEST;
            default -> workloadKind.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Dynamic size for the scheduler's job execution pool, derived from CPU count and current load,
     * clamped to the configured min/max. Replaces the old hardcoded {@code 4/16}.
     */
    public int concurrencyForSchedulerPool() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        int min = Math.max(1, cfg.getGovernorSchedulerPoolMinThreads());
        int max = Math.max(min, cfg.getGovernorSchedulerPoolMaxThreads());
        int cpus = Runtime.getRuntime().availableProcessors();
        int base = Math.max(min, cpus / 2);
        if (telemetry.latest().cpuPressure().atLeast(PressureLevel.HIGH)) {
            base = (int) Math.floor(base * 0.75);
        }
        return Math.max(min, Math.min(max, base));
    }

    /** Observability snapshot for the status endpoint. */
    public Map<String, Object> status() {
        ResourceSnapshot s = telemetry.latest();
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("governorEnabled", cfg.isGovernorEnabled());
        out.put("cpuLoad", s.systemCpuLoad());
        out.put("cpuPressure", s.cpuPressure().name());
        out.put("ramUsedFraction", s.systemRamUsedFraction());
        out.put("ramPressure", s.ramPressure().name());
        out.put("memAvailableMb", s.memAvailableMb());
        out.put("governorRamFloorMb", cfg.getGovernorRamFloorMb());
        out.put("shouldThrottleHeavyMemory", shouldThrottleHeavyMemory());
        out.put("memoryPressureReason", memoryPressureReason());
        out.put("heapUsedFraction", s.jvmHeapUsedFraction());
        out.put("gpuBackendAvailable", s.gpuBackendAvailable());
        out.put("worstGpuUsedFraction", s.worstGpuUsedFraction());
        out.put("worstGpuPressure", s.worstGpuPressure().name());
        out.put("schedulerPoolConcurrency", concurrencyForSchedulerPool());
        out.put("localSaturated", isLocalSaturated());
        out.put("gpus", s.gpus());
        if (gpuToCpuMigrationService != null) {
            out.put("gpuToCpuMigration", gpuToCpuMigrationService.status());
        }
        return out;
    }

    private boolean isGpuStage(String stage) {
        return stage != null && GPU_STAGES.contains(stage.toUpperCase(Locale.ROOT));
    }
}
