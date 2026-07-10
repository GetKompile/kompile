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

package ai.kompile.app.subprocess;

import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;

/**
 * A scheduler-computed placement for one subprocess spawn, in device-agnostic terms. Core-side DTO
 * (the platform placement engine maps its {@code PlacementDecision} onto this) so the launcher layer
 * has no dependency on the scheduler module.
 *
 * <p>Delivery is via ND4J's real, device-agnostic knobs — NEVER {@code CUDA_VISIBLE_DEVICES}:
 * backend by {@code org.nd4j.{cpu,gpu}.priority}, device by {@code nd4j.placement.defaultDevice},
 * and the per-device cap by {@code nd4j.environment.maxDeviceMemory} plus {@code SD_MAX_DEVICE_BYTES}
 * for early native process setup.</p>
 *
 * @param backend               CPU or GPU (INHERIT = leave to the launcher's default)
 * @param deviceId              ND4J device index for a GPU placement; {@code -1} = CPU / unset
 * @param maxDeviceMemoryBytes  per-device cap to enforce ({@code 0} = unbounded)
 */
public record SubprocessPlacement(BackendPreference backend, int deviceId, long maxDeviceMemoryBytes) {

    public static final int CPU_DEVICE_ID = -1;

    public boolean isGpu() {
        return backend == BackendPreference.GPU && deviceId >= 0;
    }

    public static SubprocessPlacement cpu() {
        return new SubprocessPlacement(BackendPreference.CPU, CPU_DEVICE_ID, 0L);
    }

    public static SubprocessPlacement gpu(int deviceId, long maxDeviceMemoryBytes) {
        return new SubprocessPlacement(BackendPreference.GPU, deviceId, Math.max(0L, maxDeviceMemoryBytes));
    }
}
