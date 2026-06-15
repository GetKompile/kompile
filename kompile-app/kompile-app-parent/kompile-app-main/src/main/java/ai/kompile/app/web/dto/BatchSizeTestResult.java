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
 * Result DTO for a single batch size test.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSizeTestResult(
        /**
         * The batch size that was tested.
         */
        int batchSize,

        /**
         * Average time in milliseconds across all iterations.
         */
        double avgTimeMs,

        /**
         * Minimum time in milliseconds.
         */
        double minTimeMs,

        /**
         * Maximum time in milliseconds.
         */
        double maxTimeMs,

        /**
         * Standard deviation of time in milliseconds.
         */
        double stdDevMs,

        /**
         * Tokens processed per second.
         */
        double tokensPerSecond,

        /**
         * Documents processed per second.
         */
        double documentsPerSecond,

        /**
         * Memory used in bytes during test.
         */
        long memoryUsedBytes,

        /**
         * Peak memory in bytes during test.
         */
        long peakMemoryBytes,

        /**
         * Total tokens processed across all iterations.
         */
        int totalTokensProcessed,

        /**
         * Total documents processed across all iterations.
         */
        int totalDocumentsProcessed,

        /**
         * Whether the test completed successfully.
         */
        boolean success,

        /**
         * Error message if test failed.
         */
        String errorMessage
) {
    /**
     * Creates a successful result.
     */
    public static BatchSizeTestResult success(
            int batchSize,
            double avgTimeMs,
            double minTimeMs,
            double maxTimeMs,
            double stdDevMs,
            double tokensPerSecond,
            double documentsPerSecond,
            long memoryUsedBytes,
            long peakMemoryBytes,
            int totalTokensProcessed,
            int totalDocumentsProcessed
    ) {
        return new BatchSizeTestResult(
                batchSize, avgTimeMs, minTimeMs, maxTimeMs, stdDevMs,
                tokensPerSecond, documentsPerSecond,
                memoryUsedBytes, peakMemoryBytes,
                totalTokensProcessed, totalDocumentsProcessed,
                true, null
        );
    }

    /**
     * Creates a failed result.
     */
    public static BatchSizeTestResult failure(int batchSize, String errorMessage) {
        return new BatchSizeTestResult(
                batchSize, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                false, errorMessage
        );
    }
}
