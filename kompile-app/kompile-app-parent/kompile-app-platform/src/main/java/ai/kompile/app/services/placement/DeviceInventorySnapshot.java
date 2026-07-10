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

import java.util.List;
import java.util.Optional;

/**
 * Immutable snapshot of all compute devices at decision time. Produced by a
 * {@link DeviceInventoryProvider}; consumed by the pure placement decision. Device-agnostic.
 */
public record DeviceInventorySnapshot(List<DeviceInfo> devices) {

    public DeviceInventorySnapshot {
        devices = devices == null ? List.of() : List.copyOf(devices);
    }

    /** GPU devices only, in the given order. */
    public List<DeviceInfo> gpus() {
        return devices.stream().filter(DeviceInfo::isGpu).toList();
    }

    public Optional<DeviceInfo> cpu() {
        return devices.stream().filter(DeviceInfo::isCpu).findFirst();
    }

    public Optional<DeviceInfo> byId(int deviceId) {
        return devices.stream().filter(d -> d.deviceId() == deviceId).findFirst();
    }

    public boolean hasGpu() {
        return devices.stream().anyMatch(DeviceInfo::isGpu);
    }
}
