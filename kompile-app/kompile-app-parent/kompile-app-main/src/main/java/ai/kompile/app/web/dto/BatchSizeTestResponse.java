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
 * Response DTO for complete batch size benchmark test.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSizeTestResponse(
        /**
         * Model identifier that was tested.
         */
        String modelId,

        /**
         * Display name of the model.
         */
        String modelName,

        /**
         * Embedding dimensions of the model.
         */
        int embeddingDimensions,

        /**
         * Results for each batch size tested.
         */
        List<BatchSizeTestResult> results,

        /**
         * Recommended batch size based on throughput/memory balance.
         */
        int recommendedBatchSize,

        /**
         * Largest batch size that didn't fail.
         */
        int maxSafeBatchSize,

        /**
         * Total test duration in milliseconds.
         */
        long testDurationMs,

        /**
         * System information (CPU, memory, etc.).
         */
        String systemInfo,

        /**
         * Number of sample texts used in the test.
         */
        int sampleTextCount,

        /**
         * Average sequence length of sample texts.
         */
        int avgSequenceLength
) {
    /**
     * Builder for constructing BatchSizeTestResponse.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String modelId;
        private String modelName;
        private int embeddingDimensions;
        private List<BatchSizeTestResult> results;
        private int recommendedBatchSize;
        private int maxSafeBatchSize;
        private long testDurationMs;
        private String systemInfo;
        private int sampleTextCount;
        private int avgSequenceLength;

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder embeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
            return this;
        }

        public Builder results(List<BatchSizeTestResult> results) {
            this.results = results;
            return this;
        }

        public Builder recommendedBatchSize(int recommendedBatchSize) {
            this.recommendedBatchSize = recommendedBatchSize;
            return this;
        }

        public Builder maxSafeBatchSize(int maxSafeBatchSize) {
            this.maxSafeBatchSize = maxSafeBatchSize;
            return this;
        }

        public Builder testDurationMs(long testDurationMs) {
            this.testDurationMs = testDurationMs;
            return this;
        }

        public Builder systemInfo(String systemInfo) {
            this.systemInfo = systemInfo;
            return this;
        }

        public Builder sampleTextCount(int sampleTextCount) {
            this.sampleTextCount = sampleTextCount;
            return this;
        }

        public Builder avgSequenceLength(int avgSequenceLength) {
            this.avgSequenceLength = avgSequenceLength;
            return this;
        }

        public BatchSizeTestResponse build() {
            return new BatchSizeTestResponse(
                    modelId, modelName, embeddingDimensions, results,
                    recommendedBatchSize, maxSafeBatchSize, testDurationMs,
                    systemInfo, sampleTextCount, avgSequenceLength
            );
        }
    }
}
