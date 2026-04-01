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

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response containing per-device information and native cache statistics
 * (TAD cache, Shape cache) from NativeOps.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCacheStatusResponse {

    private int availableDevices;
    private List<DeviceInfo> devices;
    private NativeCacheInfo tadCache;
    private NativeCacheInfo shapeCache;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceInfo {
        private int deviceId;
        private String deviceName;
        private long freeMemoryBytes;
        private long totalMemoryBytes;
        private long usedMemoryBytes;
        private double memoryUtilizationPercent;
        private int computeMajor;
        private int computeMinor;
        private String computeCapability;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NativeCacheInfo {
        private String cacheType;
        private long cachedEntries;
        private long cachedBytes;
        private long peakCachedEntries;
        private long peakCachedBytes;
        private String cacheContents;
    }
}
