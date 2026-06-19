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

import ai.kompile.app.config.GpuDevice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies GPU memory budgets are auto-calibrated to a fraction of the largest device's VRAM,
 * with manual overrides taking precedence and a safe no-op when no devices are present.
 * Uses {@code initForTesting()} + {@code registerDevice()} so no real GPU is required.
 */
class GpuResourceManagerCalibrationTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void calibratesBudgetsToFractionOfDeviceVram() {
        GpuResourceManager mgr = new GpuResourceManager();
        mgr.initForTesting();
        mgr.registerDevice(GpuDevice.local(0, 0, "Test GPU", 24 * GB));

        mgr.calibrateBudgetsToDeviceVram();

        assertEquals((long) (24 * GB * 0.20), mgr.getMemoryBudget("embedding"));
        assertEquals((long) (24 * GB * 0.70), mgr.getMemoryBudget("vlm"));
    }

    @Test
    void setMemoryBudgetOverridesCalibration() {
        GpuResourceManager mgr = new GpuResourceManager();
        mgr.initForTesting();
        mgr.registerDevice(GpuDevice.local(0, 0, "Test GPU", 24 * GB));
        mgr.calibrateBudgetsToDeviceVram();

        mgr.setMemoryBudget("embedding", 6 * GB);
        assertEquals(6 * GB, mgr.getMemoryBudget("embedding"));
    }

    @Test
    void noDevices_keepsHardcodedDefaults() {
        GpuResourceManager mgr = new GpuResourceManager();
        mgr.initForTesting(); // sets embedding default to 5 GB, registers no devices

        mgr.calibrateBudgetsToDeviceVram();

        assertEquals(5 * GB, mgr.getMemoryBudget("embedding"));
    }

    @Test
    void multiDevice_calibratesToLargest() {
        GpuResourceManager mgr = new GpuResourceManager();
        mgr.initForTesting();
        mgr.registerDevice(GpuDevice.local(0, 0, "Small GPU", 16 * GB));
        mgr.registerDevice(GpuDevice.local(1, 1, "Large GPU", 24 * GB));

        mgr.calibrateBudgetsToDeviceVram();

        assertEquals((long) (24 * GB * 0.20), mgr.getMemoryBudget("embedding"));
    }
}
