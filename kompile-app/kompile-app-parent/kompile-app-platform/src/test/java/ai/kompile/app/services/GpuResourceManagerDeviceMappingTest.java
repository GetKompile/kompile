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
import ai.kompile.app.services.GpuResourceManager.Nd4jDeviceSnapshot;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.device.DeviceType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure ND4J-device → {@link GpuDevice} mapping. Device discovery is ND4J's job; this only
 * asserts we translate its registered devices correctly (CPU filtered, ND4J index used directly as the
 * runtime index — no nvidia-smi / CUDA_DEVICE_ORDER remap). Backend-agnostic: no live ND4J calls.
 */
class GpuResourceManagerDeviceMappingTest {

    private static final long GB = 1L << 30;

    @Test
    void mapsNonCpuAcceleratorsUsingNd4jIndexDirectly() {
        List<Nd4jDeviceSnapshot> snaps = List.of(
                new Nd4jDeviceSnapshot(-1, DeviceType.CPU, "cpu", 64 * GB),
                new Nd4jDeviceSnapshot(0, DeviceType.CUDA_GPU, "RTX 3070 Ti", 8 * GB),
                new Nd4jDeviceSnapshot(1, DeviceType.GPU, "RTX 4090", 24 * GB));

        List<GpuDevice> devices = GpuResourceManager.mapAcceleratorDevices(snaps);

        assertEquals(2, devices.size(), "CPU device must be filtered out");
        // ND4J index used directly for BOTH indices — no vendor remap.
        assertEquals(0, devices.get(0).cudaRuntimeIndex());
        assertEquals(0, devices.get(0).nvidiaSmiIndex());
        assertEquals("RTX 3070 Ti", devices.get(0).name());
        assertEquals(8 * GB, devices.get(0).totalMemoryBytes());
        assertEquals(1, devices.get(1).cudaRuntimeIndex());
        assertEquals(24 * GB, devices.get(1).totalMemoryBytes());
    }

    @Test
    void mapsOtherAcceleratorKinds() {
        // Device-agnostic: ROCm/Metal accelerators are kept too, not just CUDA.
        List<Nd4jDeviceSnapshot> snaps = List.of(
                new Nd4jDeviceSnapshot(0, DeviceType.ROCM_GPU, "MI300", 128 * GB),
                new Nd4jDeviceSnapshot(1, DeviceType.METAL_GPU, "M3 Max", 48 * GB));
        assertEquals(2, GpuResourceManager.mapAcceleratorDevices(snaps).size());
    }

    @Test
    void emptyWhenOnlyCpu() {
        List<Nd4jDeviceSnapshot> snaps = List.of(
                new Nd4jDeviceSnapshot(-1, DeviceType.CPU, "cpu", 64 * GB));
        assertTrue(GpuResourceManager.mapAcceleratorDevices(snaps).isEmpty());
    }
}
