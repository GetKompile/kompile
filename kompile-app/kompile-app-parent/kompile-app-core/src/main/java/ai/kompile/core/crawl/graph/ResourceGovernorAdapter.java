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

package ai.kompile.core.crawl.graph;

/**
 * Thin, dependency-free bridge that lets crawl-pipeline components (in the
 * {@code kompile-crawl-graph} module) consult the resource governor without depending on
 * {@code kompile-app-main}, where the concrete {@code ResourceGovernor} and GPU types live.
 *
 * <p>The crawl-graph module depends only on {@code kompile-app-core} and {@code kompile-cli-common};
 * it must never import app-main types. All governor access therefore flows through this interface,
 * which exposes only primitives and Strings. The concrete implementation (app-main) reads a live
 * {@code ResourceSnapshot} (CPU/RAM/heap/native/per-GPU VRAM pressure) and answers these queries
 * lock-free.</p>
 *
 * <p>Crawl-graph beans inject this as {@code @Autowired(required = false)}. When absent
 * (CPU-only builds, unit tests, or app contexts without app-main services), callers fall back to
 * the prior JVM-heap-only behavior — so a {@code null} adapter is always a safe no-op.</p>
 */
public interface ResourceGovernorAdapter {

    /**
     * Effective memory pressure for a pipeline stage, as a fraction in {@code [0.0, 1.0]}.
     *
     * <p>For GPU stages (e.g. {@code "EMBEDDING"}, {@code "VECTOR_INDEXING"}) this is the worst of
     * JVM heap, native off-heap, and live GPU VRAM usage; for CPU stages it omits GPU VRAM. The
     * value is fed directly to {@code DynamicBatchSizer.recordBatchResult(..., memoryPercent)},
     * which expects a 0–1 fraction.</p>
     *
     * @param stage the pipeline stage id (e.g. {@code "EMBEDDING"}, {@code "GRAPH_EXTRACTION"})
     * @return memory pressure in {@code [0.0, 1.0]}
     */
    double effectiveMemoryPressure(String stage);

    /**
     * Whether GPU VRAM is under pressure right now.
     *
     * @param critical if {@code true}, test against the critical threshold; otherwise the
     *                 (lower) wait/pressure threshold
     * @return {@code true} if any GPU device's effective VRAM usage exceeds the threshold;
     *         {@code false} on CPU-only backends
     */
    boolean isGpuVramPressured(boolean critical);

    /**
     * Whether a heavy local workload should be deferred right now because the resource it strains
     * is too constrained (e.g. defer embeddings when GPU VRAM has no headroom).
     *
     * @param workloadKind the workload (e.g. {@code "EMBEDDING"})
     * @return {@code true} if the workload should be deferred and retried later
     */
    boolean shouldDeferLocalWork(String workloadKind);

    /**
     * Whether ANY heavy in-memory operation (KGE training, batch embedding, etc.) should be
     * throttled or blocked right now because host RAM is below the hard absolute floor
     * ({@code governorRamFloorMb}) or the fractional RAM pressure is at/above HIGH.
     *
     * <p>Unlike {@link #shouldDeferLocalWork}, this method is stage-agnostic and is consulted
     * before starting any heavy-memory op regardless of GPU availability. It exposes the OOM
     * floor added to the resource governor so that crawl-graph and knowledge-graph modules
     * (which cannot see app-main types) can query it without depending on the concrete
     * {@code ResourceGovernor}.</p>
     *
     * @return {@code true} if the host is too memory-constrained to safely start a heavy op
     */
    default boolean shouldThrottleHeavyMemory() {
        return false; // safe default when governor is absent / CPU-only
    }

    /**
     * Human-readable reason for the most recent memory-throttle decision, or {@code null} when
     * the governor is not throttling. Useful for DECISION event messages.
     *
     * @return reason string (e.g. {@code "MemAvailable 6200 MB < floor 8192 MB"}) or {@code null}
     */
    default String memoryPressureReason() {
        return null;
    }

    /**
     * Host RAM (in MB) currently usable for concurrent heavy in-memory ops — i.e. the live
     * {@code MemAvailable} minus the OOM-floor reserve ({@code governorRamFloorMb}). This is the
     * budget that {@link HeavyMemoryCoordinator}'s admission control spreads across simultaneous
     * heavy ops (KGE training, batch embedding) instead of forcing them to run one at a time.
     *
     * <p>Returns {@code -1} when the figure is unknown (governor disabled, non-Linux where
     * {@code MemAvailable} is not readable, or no telemetry) so callers can degrade safely to
     * serial execution rather than over-admitting.</p>
     *
     * @return usable heavy-op headroom in MB, or {@code -1} when unknown
     */
    default long availableMemoryMbForHeavyOps() {
        return -1; // safe default when governor is absent / CPU-only
    }
}
