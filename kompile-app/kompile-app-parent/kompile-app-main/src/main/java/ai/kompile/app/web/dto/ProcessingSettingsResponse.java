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

package ai.kompile.app.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for processing settings and system status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessingSettingsResponse(
        // Concurrency settings
        int maxConcurrentJobs,
        int activeJobs,
        int queuedJobs,
        boolean canAcceptNewJob,

        // Batch settings
        int indexBatchSize,
        int minBatchSize,
        int maxBatchSize,
        boolean adaptiveBatchSize,

        // Memory settings and status
        int memoryThresholdPercent,
        int memoryCriticalPercent,
        MemoryStatus memoryStatus,

        // Thread pool info
        int corePoolSize,
        int maxPoolSize,
        int activeThreads,
        int queueSize
) {
    /**
     * Memory status sub-object.
     */
    public record MemoryStatus(
            long maxMemoryMB,
            long usedMemoryMB,
            long freeMemoryMB,
            double usagePercent,
            boolean thresholdExceeded,
            boolean criticalExceeded,
            String status // "OK", "WARNING", "CRITICAL"
    ) {
        public static MemoryStatus fromMemoryInfo(
                long maxBytes, long usedBytes, double usagePercent,
                boolean thresholdExceeded, boolean criticalExceeded) {

            String status = "OK";
            if (criticalExceeded) {
                status = "CRITICAL";
            } else if (thresholdExceeded) {
                status = "WARNING";
            }

            return new MemoryStatus(
                    maxBytes / (1024 * 1024),
                    usedBytes / (1024 * 1024),
                    (maxBytes - usedBytes) / (1024 * 1024),
                    Math.round(usagePercent * 10) / 10.0,
                    thresholdExceeded,
                    criticalExceeded,
                    status
            );
        }
    }
}
