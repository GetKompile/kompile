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

package ai.kompile.app.services.crawl;

import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import ai.kompile.app.services.cluster.WorkerCapabilities;

/**
 * Composite "how much crawl work should this worker take right now" weight, derived from a worker's
 * advertised {@link WorkerCapabilities}. Used by the {@code DistributedCrawlCoordinator}'s weighted
 * partitioner (and the partition-loss reaper's reassignment target pick) so sources route to workers with
 * real spare capacity — free slots discounted by CPU/GPU load and pressure — not just the raw free-slot
 * count.
 *
 * <p>Returns {@code 0} when the worker should take NO new work (draining, no free slot, or CRITICAL CPU
 * pressure / — for GPU work — CRITICAL GPU pressure or no GPU). A zero-weight worker is given an empty
 * partition by the caller. Otherwise the result is {@code >= 1}: a loaded-but-usable worker still receives
 * a minimal share rather than being starved.</p>
 *
 * <p>Pure and deterministic over its inputs (a capabilities snapshot) so it is trivially unit-testable
 * without any Spring context.</p>
 */
public final class WorkerWeightFunction {

    /** Assignment-time GC-overhead thresholds (mirror the reaper's loss-detection config defaults). */
    private static final double GC_HIGH_FRACTION = 0.3;
    private static final double GC_CRITICAL_FRACTION = 0.5;

    private WorkerWeightFunction() {
    }

    /**
     * @param w           the worker's latest advertised capabilities (nullable → 0)
     * @param requiresGpu whether the work needs a GPU; when {@code true}, CRITICAL GPU pressure or a
     *                    GPU-less worker is gated/heavily down-weighted and GPU headroom is preferred
     * @return a non-negative weight; {@code 0} means "assign nothing to this worker"
     */
    public static int compute(WorkerCapabilities w, boolean requiresGpu) {
        if (w == null || !w.acceptingWork() || w.freeSlots() <= 0) {
            return 0;
        }
        PressureLevel cpu = parse(w.cpuPressure());
        if (cpu == PressureLevel.CRITICAL) {
            return 0; // hard CPU backpressure — don't pile on
        }

        // Base capacity is the free-slot count.
        double weight = w.freeSlots();

        // Coarse CPU-pressure discount.
        if (cpu == PressureLevel.HIGH) {
            weight *= 0.5;
        } else if (cpu == PressureLevel.ELEVATED) {
            weight *= 0.75;
        }

        // Fine-grained CPU-load discount (cpuLoad is 0..1, or -1 when unknown).
        double load = w.cpuLoad();
        if (load >= 0.0) {
            weight *= Math.max(0.1, 1.0 - Math.min(load, 1.0));
        }

        if (requiresGpu) {
            PressureLevel gpu = parse(w.gpuPressure());
            if (gpu == PressureLevel.CRITICAL) {
                return 0; // no GPU headroom for GPU work
            }
            if (gpu == PressureLevel.HIGH) {
                weight *= 0.5;
            }
            if (!w.hasGpu()) {
                weight *= 0.25; // strongly prefer real GPU nodes, but don't fully exclude
            }
        } else if (w.worstGpuUsedFraction() > 0.85) {
            // Non-GPU work: a GPU-saturated node may still be CPU-contended — mild discount.
            weight *= 0.75;
        }

        // GC-overhead backpressure (Phase 3): a churning JVM is barely progressing — avoid piling on.
        double gc = w.gcOverheadFraction();
        if (gc >= GC_CRITICAL_FRACTION) {
            return 0;
        }
        if (gc >= GC_HIGH_FRACTION) {
            weight *= 0.4;
        }

        return Math.max(1, (int) Math.round(weight));
    }

    /** Parse a {@link PressureLevel} name defensively; unknown/blank → NOMINAL. */
    private static PressureLevel parse(String name) {
        if (name == null || name.isBlank()) {
            return PressureLevel.NOMINAL;
        }
        try {
            return PressureLevel.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PressureLevel.NOMINAL;
        }
    }
}
