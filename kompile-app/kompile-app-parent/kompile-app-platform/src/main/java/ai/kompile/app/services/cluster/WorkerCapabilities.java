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

package ai.kompile.app.services.cluster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * What a {@code CrawlWorker} can do and how loaded it is right now — the unit of "capabilities are known
 * and shared" across the cluster. A worker advertises this to the orchestrator over HTTP (register +
 * heartbeat); the orchestrator keeps the latest one per worker in its {@link CrawlWorkerRegistry} and uses
 * it to route work to a peer that can actually run it and has spare capacity.
 *
 * <p>Immutable + JSON-friendly (Jackson serialises records directly); {@code ignoreUnknown} keeps the wire
 * format forward-compatible as fields are added.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerCapabilities(
        @JsonProperty("workerId") String workerId,             // stable id for this node
        @JsonProperty("baseUrl") String baseUrl,               // http(s)://host:port — how peers reach it
        @JsonProperty("role") String role,                     // "worker", "orchestrator", or "both"
        @JsonProperty("backends") List<String> backends,       // e.g. ["CPU"] or ["CPU","CUDA"]
        @JsonProperty("gpuDeviceCount") int gpuDeviceCount,
        @JsonProperty("totalGpuMemoryBytes") long totalGpuMemoryBytes,
        @JsonProperty("cpuCores") int cpuCores,
        @JsonProperty("supportedJobTypes") List<String> supportedJobTypes, // ingest, vectorPopulation, graph, ...
        @JsonProperty("maxConcurrentJobs") int maxConcurrentJobs,
        @JsonProperty("activeJobs") int activeJobs,
        @JsonProperty("cpuLoad") double cpuLoad,               // 0..1 (-1 if unknown)
        @JsonProperty("worstGpuUsedFraction") double worstGpuUsedFraction, // 0..1
        @JsonProperty("cpuPressure") String cpuPressure,       // ResourceSnapshot.PressureLevel name
        @JsonProperty("gpuPressure") String gpuPressure,       // worst across devices
        @JsonProperty("acceptingWork") boolean acceptingWork,  // false when draining / overloaded
        @JsonProperty("advertisedAtEpochMs") long advertisedAtEpochMs,
        @JsonProperty("ramUsedFraction") double ramUsedFraction, // 0..1 host RAM
        @JsonProperty("ramPressure") String ramPressure,
        @JsonProperty("gpus") List<GpuInfo> gpus,              // per-device GPU detail
        @JsonProperty("draining") boolean draining,            // operator drained (distinct from "full")
        @JsonProperty("gcOverheadFraction") double gcOverheadFraction, // 0..1 recent GC time / wall (Phase 3)
        @JsonProperty("graphRpcProtocolVersion") int graphRpcProtocolVersion,
        @JsonProperty("distributedGraphWriterProtocolVersion") int distributedGraphWriterProtocolVersion,
        @JsonProperty("distributedPartitionBarrierVersion") int distributedPartitionBarrierVersion) {

    /** Backward-compatible constructor for callers predating distributed graph protocol fields. */
    public WorkerCapabilities(String workerId, String baseUrl, String role, List<String> backends,
                              int gpuDeviceCount, long totalGpuMemoryBytes, int cpuCores,
                              List<String> supportedJobTypes, int maxConcurrentJobs, int activeJobs,
                              double cpuLoad, double worstGpuUsedFraction, String cpuPressure, String gpuPressure,
                              boolean acceptingWork, long advertisedAtEpochMs, double ramUsedFraction,
                              String ramPressure, List<GpuInfo> gpus, boolean draining,
                              double gcOverheadFraction) {
        this(workerId, baseUrl, role, backends, gpuDeviceCount, totalGpuMemoryBytes, cpuCores,
                supportedJobTypes, maxConcurrentJobs, activeJobs, cpuLoad, worstGpuUsedFraction,
                cpuPressure, gpuPressure, acceptingWork, advertisedAtEpochMs, ramUsedFraction,
                ramPressure, gpus, draining, gcOverheadFraction, 0, 0, 0);
    }

    /** Backward-compatible constructor for callers / heartbeats predating {@code gcOverheadFraction} (→ 0). */
    public WorkerCapabilities(String workerId, String baseUrl, String role, List<String> backends,
                              int gpuDeviceCount, long totalGpuMemoryBytes, int cpuCores,
                              List<String> supportedJobTypes, int maxConcurrentJobs, int activeJobs,
                              double cpuLoad, double worstGpuUsedFraction, String cpuPressure, String gpuPressure,
                              boolean acceptingWork, long advertisedAtEpochMs, double ramUsedFraction,
                              String ramPressure, List<GpuInfo> gpus, boolean draining) {
        this(workerId, baseUrl, role, backends, gpuDeviceCount, totalGpuMemoryBytes, cpuCores, supportedJobTypes,
                maxConcurrentJobs, activeJobs, cpuLoad, worstGpuUsedFraction, cpuPressure, gpuPressure,
                acceptingWork, advertisedAtEpochMs, ramUsedFraction, ramPressure, gpus, draining, 0.0,
                0, 0, 0);
    }

    /** Per-GPU device detail for the resources view. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GpuInfo(
            @JsonProperty("index") int index,
            @JsonProperty("usedFraction") double usedFraction, // 0..1, max(actual,reserved)/total
            @JsonProperty("totalBytes") long totalBytes,
            @JsonProperty("pressure") String pressure) {
    }

    /** True when this worker exposes a CUDA/GPU backend. */
    public boolean hasGpu() {
        return backends != null && backends.stream().anyMatch(b -> b != null
                && (b.equalsIgnoreCase("CUDA") || b.equalsIgnoreCase("GPU")));
    }

    /** Remaining job slots (never negative). */
    public int freeSlots() {
        return Math.max(0, maxConcurrentJobs - activeJobs);
    }

    /**
     * Whether this worker can take a job of the given type now: it's accepting work, supports the type, has
     * a free slot, and — if the job needs a GPU — actually has one.
     */
    public boolean canRun(String jobType, boolean requiresGpu) {
        if (!acceptingWork || freeSlots() <= 0) {
            return false;
        }
        if (requiresGpu && !hasGpu()) {
            return false;
        }
        return supportedJobTypes != null && supportedJobTypes.contains(jobType);
    }

    public boolean supportsDistributedGraphWriter(int minimumVersion) {
        return graphRpcProtocolVersion >= 2
                && distributedGraphWriterProtocolVersion >= minimumVersion;
    }
}
