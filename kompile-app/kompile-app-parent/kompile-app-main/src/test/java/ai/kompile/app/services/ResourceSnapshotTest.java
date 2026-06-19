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

import ai.kompile.app.services.ResourceSnapshot.GpuSnapshot;
import ai.kompile.app.services.ResourceSnapshot.PressureLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceSnapshotTest {

    @Test
    void pressureLevel_orderingIsSevereAscending() {
        assertTrue(PressureLevel.CRITICAL.atLeast(PressureLevel.HIGH));
        assertTrue(PressureLevel.HIGH.atLeast(PressureLevel.HIGH));
        assertFalse(PressureLevel.ELEVATED.atLeast(PressureLevel.HIGH));
        assertFalse(PressureLevel.NOMINAL.atLeast(PressureLevel.ELEVATED));
    }

    @Test
    void worstGpu_picksHighestUsageAndPressure() {
        ResourceSnapshot s = new ResourceSnapshot(0L, 0.1, 0.2, 0.3, 0.0,
                PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL, PressureLevel.NOMINAL,
                List.of(
                        new GpuSnapshot(0, 100, 1000, 0, 0.40, PressureLevel.ELEVATED),
                        new GpuSnapshot(1, 50, 1000, 0, 0.93, PressureLevel.CRITICAL)),
                true);
        assertEquals(0.93, s.worstGpuUsedFraction(), 1e-9);
        assertEquals(PressureLevel.CRITICAL, s.worstGpuPressure());
    }

    @Test
    void unavailable_isAllNominalNoGpu() {
        ResourceSnapshot s = ResourceSnapshot.unavailable();
        assertFalse(s.gpuBackendAvailable());
        assertTrue(s.gpus().isEmpty());
        assertEquals(PressureLevel.NOMINAL, s.cpuPressure());
        assertEquals(PressureLevel.NOMINAL, s.ramPressure());
        assertEquals(PressureLevel.NOMINAL, s.worstGpuPressure());
        assertEquals(0.0, s.worstGpuUsedFraction(), 1e-9);
    }
}
