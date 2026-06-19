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
     */
    public AdmissionResult admitJob(JobResourceProfile profile) {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isGovernorEnabled()) {
            return AdmissionResult.allow();
        }
        ResourceSnapshot s = telemetry.latest();

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
        return !s.worstGpuPressure().atLeast(PressureLevel.HIGH);
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
        out.put("heapUsedFraction", s.jvmHeapUsedFraction());
        out.put("gpuBackendAvailable", s.gpuBackendAvailable());
        out.put("worstGpuUsedFraction", s.worstGpuUsedFraction());
        out.put("worstGpuPressure", s.worstGpuPressure().name());
        out.put("schedulerPoolConcurrency", concurrencyForSchedulerPool());
        out.put("gpus", s.gpus());
        return out;
    }

    private boolean isGpuStage(String stage) {
        return stage != null && GPU_STAGES.contains(stage.toUpperCase(Locale.ROOT));
    }
}
