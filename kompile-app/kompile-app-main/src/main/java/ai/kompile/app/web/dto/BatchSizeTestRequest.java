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

import java.util.List;

/**
 * Request DTO for batch size benchmark testing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSizeTestRequest(
        /**
         * Model identifier (e.g., "bge-base-en-v1.5"). If null, uses the default loaded model.
         */
        String modelId,

        /**
         * Custom sample texts for testing. If null or empty, uses default test texts.
         */
        List<String> sampleTexts,

        /**
         * Batch sizes to test (e.g., [1, 2, 4, 8, 16, 32]).
         * If null, uses default set [1, 2, 4, 8, 16, 32, 64].
         */
        List<Integer> batchSizesToTest,

        /**
         * Number of iterations per batch size (default: 3).
         */
        Integer iterations,

        /**
         * Number of warmup iterations to exclude from results (default: 1).
         */
        Integer warmupIterations,

        /**
         * Maximum time in seconds for each batch size test (default: 30).
         */
        Integer timeoutSeconds
) {
    /**
     * Returns iterations with default value.
     */
    public int getIterationsOrDefault() {
        return iterations != null ? iterations : 3;
    }

    /**
     * Returns warmup iterations with default value.
     */
    public int getWarmupIterationsOrDefault() {
        return warmupIterations != null ? warmupIterations : 1;
    }

    /**
     * Returns timeout seconds with default value.
     */
    public int getTimeoutSecondsOrDefault() {
        return timeoutSeconds != null ? timeoutSeconds : 30;
    }

    /**
     * Returns batch sizes to test with default values.
     */
    public List<Integer> getBatchSizesToTestOrDefault() {
        return batchSizesToTest != null && !batchSizesToTest.isEmpty()
                ? batchSizesToTest
                : List.of(1, 2, 4, 8, 16, 32, 64);
    }
}
