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

package ai.kompile.app.services.placement;

import ai.kompile.app.services.placement.PlacementDecision.MemoryRegime;
import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessPlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementApplierTest {

    private static final long GB = 1L << 30;
    private final PlacementApplier applier = new PlacementApplier();

    /** Fake launcher capturing the last placement — proves delivery without spawning a process. */
    static final class CapturingLauncher implements BackendConfigurable {
        SubprocessPlacement last;
        int calls;
        @Override public void applyPlacement(SubprocessPlacement placement) { last = placement; calls++; }
    }

    @Test
    void cliDecision_mapsToNull_andIsNotDeliveredToLauncher() {
        PlacementDecision d = PlacementDecision.cli("opencode-cli", 150, "cli");
        assertNull(PlacementApplier.toSubprocessPlacement(d));

        CapturingLauncher launcher = new CapturingLauncher();
        assertFalse(applier.applyToLauncher(d, launcher), "CLI route applies no launcher placement");
        assertEquals(0, launcher.calls);
    }

    @Test
    void localCpu_mapsToCpuPlacement() {
        PlacementDecision d = PlacementDecision.local(
                DeviceInfo.CPU_DEVICE_ID, MemoryRegime.COMFORTABLE_FIT, 0L, 20, "cpu");
        SubprocessPlacement p = PlacementApplier.toSubprocessPlacement(d);
        assertEquals(BackendPreference.CPU, p.backend());
        assertEquals(SubprocessPlacement.CPU_DEVICE_ID, p.deviceId());
        assertEquals(0L, p.maxDeviceMemoryBytes());
        assertFalse(p.isGpu());
    }

    @Test
    void localGpuWithBound_mapsAndDelivers() {
        PlacementDecision d = PlacementDecision.local(
                1, MemoryRegime.BOUNDED_SPILL, 13L * GB, 70, "gpu bounded");
        SubprocessPlacement p = PlacementApplier.toSubprocessPlacement(d);
        assertEquals(BackendPreference.GPU, p.backend());
        assertEquals(1, p.deviceId());
        assertEquals(13L * GB, p.maxDeviceMemoryBytes());
        assertTrue(p.isGpu());

        CapturingLauncher launcher = new CapturingLauncher();
        assertTrue(applier.applyToLauncher(d, launcher));
        assertEquals(1, launcher.calls);
        assertEquals(13L * GB, launcher.last.maxDeviceMemoryBytes());
        assertEquals(1, launcher.last.deviceId());
    }

    @Test
    void localGpuWholeDevice_hasNoBound() {
        PlacementDecision d = PlacementDecision.local(
                0, MemoryRegime.WHOLE_DEVICE, 0L, 200, "whole device");
        SubprocessPlacement p = PlacementApplier.toSubprocessPlacement(d);
        assertEquals(0L, p.maxDeviceMemoryBytes(), "whole-device placement is unbounded");
        assertTrue(p.isGpu());
    }
}
