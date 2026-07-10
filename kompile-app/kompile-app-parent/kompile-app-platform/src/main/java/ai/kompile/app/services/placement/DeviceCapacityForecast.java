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

import java.util.Map;

/**
 * Forward-looking capacity signal per device, produced by {@code DeviceTrendTracker}
 * (least-squares VRAM slope + queued-work cost). Lets the engine avoid stranding a device that is
 * forecast to be needed soon, even when it fits right now.
 */
public record DeviceCapacityForecast(Map<Integer, DeviceForecast> byDevice) {

    public DeviceCapacityForecast {
        byDevice = byDevice == null ? Map.of() : Map.copyOf(byDevice);
    }

    /**
     * @param deviceId               ND4J device index
     * @param estimatedSaturatesInMs projected ms until this device is full at the current slope
     *                               (Long.MAX_VALUE = not trending toward saturation)
     * @param projectedFreeBytes     free bytes projected at the forecast horizon
     * @param consumptionRateBytesPerMs signed slope; positive = filling
     */
    public record DeviceForecast(
            int deviceId,
            long estimatedSaturatesInMs,
            long projectedFreeBytes,
            double consumptionRateBytesPerMs
    ) {}

    public DeviceForecast forDevice(int deviceId) {
        return byDevice.getOrDefault(deviceId,
                new DeviceForecast(deviceId, Long.MAX_VALUE, Long.MAX_VALUE, 0.0));
    }

    public static DeviceCapacityForecast steady() {
        return new DeviceCapacityForecast(Map.of());
    }
}
