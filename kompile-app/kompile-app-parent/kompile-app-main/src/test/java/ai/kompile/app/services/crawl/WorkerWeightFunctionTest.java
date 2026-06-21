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

import ai.kompile.app.services.cluster.WorkerCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerWeightFunctionTest {

    /** Build a crawl worker with the given load/pressure shape. cpuLoad<0 means "unknown". */
    private static WorkerCapabilities worker(int maxJobs, int activeJobs, double cpuLoad,
                                             String cpuPressure, boolean acceptingWork,
                                             boolean gpu, double worstGpuUsed, String gpuPressure) {
        return new WorkerCapabilities("w", "http://w", "worker",
                gpu ? List.of("CPU", "CUDA") : List.of("CPU"),
                gpu ? 1 : 0, gpu ? 16_000_000_000L : 0L, 8, List.of("crawl"),
                maxJobs, activeJobs, cpuLoad, worstGpuUsed, cpuPressure, gpuPressure, acceptingWork, 0L,
                0.3, "NOMINAL", List.of(), false);
    }

    /** Idle nominal worker (4 free slots, no CPU-load discount) with a given recent GC-overhead fraction. */
    private static WorkerCapabilities workerWithGc(double gc) {
        return new WorkerCapabilities("w", "http://w", "worker", List.of("CPU"),
                0, 0L, 8, List.of("crawl"), 4, 0, -1, 0.0, "NOMINAL", "NOMINAL", true, 0L,
                0.3, "NOMINAL", List.of(), false, gc);
    }

    @Test
    void zeroWhenNull() {
        assertEquals(0, WorkerWeightFunction.compute(null, false));
    }

    @Test
    void zeroWhenNotAcceptingWork() {
        assertEquals(0, WorkerWeightFunction.compute(
                worker(4, 0, 0.1, "NOMINAL", false, false, 0, "NOMINAL"), false));
    }

    @Test
    void zeroWhenNoFreeSlots() {
        assertEquals(0, WorkerWeightFunction.compute(
                worker(4, 4, 0.1, "NOMINAL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void zeroWhenCpuCritical() {
        assertEquals(0, WorkerWeightFunction.compute(
                worker(4, 0, 0.1, "CRITICAL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void idleNominalWorkerScoresFreeSlots() {
        // cpuLoad unknown (-1) → no load discount; NOMINAL → no pressure discount.
        assertEquals(4, WorkerWeightFunction.compute(
                worker(4, 0, -1, "NOMINAL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void cpuHighHalvesWeight() {
        assertEquals(2, WorkerWeightFunction.compute(
                worker(4, 0, -1, "HIGH", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void cpuLoadDiscountsWeight() {
        // freeSlots 4 × (1 - 0.5) = 2
        assertEquals(2, WorkerWeightFunction.compute(
                worker(4, 0, 0.5, "NOMINAL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void loadedWorkerStillGetsMinimumOne() {
        // freeSlots 1 × (1 - 0.95) = 0.05 → floored to 1 (usable, just minimal)
        assertEquals(1, WorkerWeightFunction.compute(
                worker(1, 0, 0.95, "NOMINAL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void unknownPressureStringTreatedAsNominal() {
        assertEquals(4, WorkerWeightFunction.compute(
                worker(4, 0, -1, "BOGUS_LEVEL", true, false, 0, "NOMINAL"), false));
    }

    @Test
    void gpuWorkerPreferredForGpuWork() {
        int gpuWeight = WorkerWeightFunction.compute(
                worker(4, 0, -1, "NOMINAL", true, true, 0.1, "NOMINAL"), true);
        int cpuWeight = WorkerWeightFunction.compute(
                worker(4, 0, -1, "NOMINAL", true, false, 0, "NOMINAL"), true);
        assertTrue(gpuWeight > cpuWeight,
                "GPU worker (" + gpuWeight + ") should outweigh CPU worker (" + cpuWeight + ") for GPU work");
        assertEquals(4, gpuWeight);
        assertEquals(1, cpuWeight); // 4 × 0.25, floored
    }

    @Test
    void zeroWhenGpuCriticalAndGpuRequired() {
        assertEquals(0, WorkerWeightFunction.compute(
                worker(4, 0, -1, "NOMINAL", true, true, 0.99, "CRITICAL"), true));
    }

    @Test
    void gpuSaturationOnlyMildlyDiscountsNonGpuWork() {
        // requiresGpu=false: a GPU-saturated node is mildly discounted (×0.75), not excluded.
        assertEquals(3, WorkerWeightFunction.compute(
                worker(4, 0, -1, "NOMINAL", true, true, 0.90, "HIGH"), false));
    }

    @Test
    void lowGcOverheadHasNoEffect() {
        // gc 0.2 < HIGH(0.3) → full 4 free slots.
        assertEquals(4, WorkerWeightFunction.compute(workerWithGc(0.2), false));
    }

    @Test
    void highGcOverheadDownWeights() {
        // gc 0.4 ≥ HIGH(0.3) → 4 × 0.4 = 1.6 → rounds to 2.
        assertEquals(2, WorkerWeightFunction.compute(workerWithGc(0.4), false));
    }

    @Test
    void criticalGcOverheadExcludes() {
        // gc 0.6 ≥ CRITICAL(0.5) → 0 (don't assign to a churning JVM).
        assertEquals(0, WorkerWeightFunction.compute(workerWithGc(0.6), false));
    }
}
