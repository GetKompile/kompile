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

package ai.kompile.app.services.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Declares what resources a job type needs, broken down by pipeline phase.
 *
 * <p>GPU memory is declared in bytes to match {@link ai.kompile.app.services.GpuResourceManager}'s
 * native unit. CPU/heap estimates are advisory for throttling decisions.</p>
 *
 * <p>{@code phaseProfiles} is ordered — entries run in pipeline order.
 * Only phases that change resource requirements need entries.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobResourceProfile(

        /** Service type key that maps to GpuResourceManager priorities/budgets */
        @JsonProperty("serviceType") String serviceType,

        /** Human-readable label */
        @JsonProperty("displayName") String displayName,

        /** Whether this job type requires GPU at any phase */
        @JsonProperty("requiresGpu") boolean requiresGpu,

        /** Peak GPU memory in bytes (used when no phase breakdown exists) */
        @JsonProperty("peakGpuMemoryBytes") long peakGpuMemoryBytes,

        /** Estimated JVM heap required in bytes (advisory) */
        @JsonProperty("estimatedHeapBytes") long estimatedHeapBytes,

        /** Can multiple jobs of this type run concurrently? */
        @JsonProperty("concurrentAllowed") boolean concurrentAllowed,

        /** Maximum concurrent count when concurrentAllowed=true */
        @JsonProperty("maxConcurrent") int maxConcurrent,

        /**
         * Phase-level resource breakdown for multi-phase jobs.
         * Null or empty means peakGpuMemoryBytes applies for the whole job.
         */
        @JsonProperty("phaseProfiles") List<PhaseResourceProfile> phaseProfiles,

        /** Job types that conflict (cannot run concurrently) */
        @JsonProperty("conflictsWith") List<String> conflictsWith,

        /** Job types that can be batched together */
        @JsonProperty("batchableWith") List<String> batchableWith
) {

    /**
     * Per-phase resource requirements within a multi-phase pipeline.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhaseResourceProfile(
            @JsonProperty("phaseName") String phaseName,
            @JsonProperty("requiresGpu") boolean requiresGpu,
            @JsonProperty("gpuMemoryBytes") long gpuMemoryBytes,
            @JsonProperty("estimatedDurationSeconds") int estimatedDurationSeconds,
            @JsonProperty("canYieldGpu") boolean canYieldGpu
    ) {}

    public static JobResourceProfile cpuOnly(String serviceType, String displayName,
                                              long estimatedHeapBytes) {
        return new JobResourceProfile(
                serviceType, displayName, false, 0, estimatedHeapBytes,
                true, 4, List.of(), List.of(), List.of()
        );
    }

    public static JobResourceProfile gpuRequired(String serviceType, String displayName,
                                                  long gpuMemoryBytes, long estimatedHeapBytes) {
        return new JobResourceProfile(
                serviceType, displayName, true, gpuMemoryBytes, estimatedHeapBytes,
                false, 1, List.of(), List.of(), List.of()
        );
    }

    public static JobResourceProfile gpuRequired(String serviceType, String displayName,
                                                  long gpuMemoryBytes, long estimatedHeapBytes,
                                                  boolean concurrentAllowed, int maxConcurrent) {
        return new JobResourceProfile(
                serviceType, displayName, true, gpuMemoryBytes, estimatedHeapBytes,
                concurrentAllowed, maxConcurrent, List.of(), List.of(), List.of()
        );
    }

    /**
     * Check if this profile has a multi-phase breakdown.
     */
    public boolean hasPhaseBreakdown() {
        return phaseProfiles != null && !phaseProfiles.isEmpty();
    }

    /**
     * Get the GPU memory needed for a specific phase, or peakGpuMemoryBytes as fallback.
     */
    public long gpuMemoryForPhase(String phaseName) {
        if (phaseProfiles != null) {
            for (PhaseResourceProfile phase : phaseProfiles) {
                if (phase.phaseName().equals(phaseName)) {
                    return phase.gpuMemoryBytes();
                }
            }
        }
        return peakGpuMemoryBytes;
    }

    /**
     * Check if a specific phase requires GPU.
     */
    public boolean phaseRequiresGpu(String phaseName) {
        if (phaseProfiles != null) {
            for (PhaseResourceProfile phase : phaseProfiles) {
                if (phase.phaseName().equals(phaseName)) {
                    return phase.requiresGpu();
                }
            }
        }
        return requiresGpu;
    }
}
