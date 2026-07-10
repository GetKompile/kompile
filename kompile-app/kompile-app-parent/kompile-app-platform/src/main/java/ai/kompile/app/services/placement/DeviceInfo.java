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

/**
 * Device-agnostic view of one compute device, mirroring ND4J's
 * {@code DeviceManager::DeviceInfo} (CPU/CUDA/Metal/Vulkan/OpenCL). The scheduler NEVER reasons
 * in vendor terms (no {@code CUDA_VISIBLE_DEVICES}); {@link #deviceId} is ND4J's device index and
 * {@link #CPU_DEVICE_ID} (-1) is the device-agnostic CPU sentinel.
 *
 * <p>Immutable snapshot value — one entry per device in a {@link DeviceInventorySnapshot}. All
 * fields are supplied by a {@link DeviceInventoryProvider}, so tests construct them directly.</p>
 */
public record DeviceInfo(
        int deviceId,
        DeviceKind kind,
        long totalMemoryBytes,
        long freeMemoryBytes,
        double computeCapability,
        /** Live tenants already bound to this device (from ND4J DeviceInfo.currentUserCount). */
        int currentUserCount
) {

    /** Device-agnostic CPU sentinel — matches ND4J {@code DeviceMemoryManager.CPU_DEVICE_ID}. */
    public static final int CPU_DEVICE_ID = -1;

    public enum DeviceKind { CPU, GPU }

    public boolean isCpu() {
        return kind == DeviceKind.CPU || deviceId == CPU_DEVICE_ID;
    }

    public boolean isGpu() {
        return kind == DeviceKind.GPU;
    }

    public static DeviceInfo cpu() {
        return new DeviceInfo(CPU_DEVICE_ID, DeviceKind.CPU, 0L, 0L, 0.0, 0);
    }

    public static DeviceInfo gpu(int deviceId, long totalBytes, long freeBytes, double computeCapability) {
        return new DeviceInfo(deviceId, DeviceKind.GPU, totalBytes, freeBytes, computeCapability, 0);
    }
}
