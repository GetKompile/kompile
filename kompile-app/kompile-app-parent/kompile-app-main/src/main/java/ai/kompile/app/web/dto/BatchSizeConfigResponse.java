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
 * Response DTO for batch size configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSizeConfigResponse(
        /**
         * Model identifier.
         */
        String modelId,

        /**
         * Current optimal batch size setting.
         */
        int currentOptimalBatchSize,

        /**
         * Current maximum batch size setting.
         */
        int currentMaxBatchSize,

        /**
         * Absolute maximum batch size allowed.
         */
        int absoluteMaxBatchSize,

        /**
         * Memory scale factor (-1 means auto-detect).
         */
        double memoryScaleFactor,

        /**
         * Whether memory scaling is automatic.
         */
        boolean isAutoScaled,

        /**
         * Available memory in MB.
         */
        long availableMemoryMb,

        /**
         * Calculated effective batch size after memory scaling.
         */
        int calculatedEffectiveBatchSize,

        /**
         * Whether this config has runtime overrides.
         */
        boolean hasRuntimeOverride
) {
    /**
     * Builder for constructing BatchSizeConfigResponse.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String modelId;
        private int currentOptimalBatchSize;
        private int currentMaxBatchSize;
        private int absoluteMaxBatchSize = 128;
        private double memoryScaleFactor = -1.0;
        private boolean isAutoScaled = true;
        private long availableMemoryMb;
        private int calculatedEffectiveBatchSize;
        private boolean hasRuntimeOverride = false;

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder currentOptimalBatchSize(int currentOptimalBatchSize) {
            this.currentOptimalBatchSize = currentOptimalBatchSize;
            return this;
        }

        public Builder currentMaxBatchSize(int currentMaxBatchSize) {
            this.currentMaxBatchSize = currentMaxBatchSize;
            return this;
        }

        public Builder absoluteMaxBatchSize(int absoluteMaxBatchSize) {
            this.absoluteMaxBatchSize = absoluteMaxBatchSize;
            return this;
        }

        public Builder memoryScaleFactor(double memoryScaleFactor) {
            this.memoryScaleFactor = memoryScaleFactor;
            return this;
        }

        public Builder isAutoScaled(boolean isAutoScaled) {
            this.isAutoScaled = isAutoScaled;
            return this;
        }

        public Builder availableMemoryMb(long availableMemoryMb) {
            this.availableMemoryMb = availableMemoryMb;
            return this;
        }

        public Builder calculatedEffectiveBatchSize(int calculatedEffectiveBatchSize) {
            this.calculatedEffectiveBatchSize = calculatedEffectiveBatchSize;
            return this;
        }

        public Builder hasRuntimeOverride(boolean hasRuntimeOverride) {
            this.hasRuntimeOverride = hasRuntimeOverride;
            return this;
        }

        public BatchSizeConfigResponse build() {
            return new BatchSizeConfigResponse(
                    modelId, currentOptimalBatchSize, currentMaxBatchSize,
                    absoluteMaxBatchSize, memoryScaleFactor, isAutoScaled,
                    availableMemoryMb, calculatedEffectiveBatchSize, hasRuntimeOverride
            );
        }
    }
}
