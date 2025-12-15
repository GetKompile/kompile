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
 * Request DTO for updating batch size configuration.
 * All fields are optional - only non-null fields will be updated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSizeConfigRequest(
        /**
         * Model identifier. If null, applies configuration globally.
         */
        String modelId,

        /**
         * Optimal batch size for the model.
         */
        Integer optimalBatchSize,

        /**
         * Maximum batch size for the model.
         */
        Integer maxBatchSize,

        /**
         * Memory scale factor (-1 for auto-detect).
         */
        Double memoryScaleFactor,

        /**
         * Whether to persist changes to application.properties.
         */
        Boolean persistToConfig
) {
    /**
     * Validates the request values.
     *
     * @throws IllegalArgumentException if values are invalid
     */
    public void validate() {
        if (optimalBatchSize != null && optimalBatchSize < 1) {
            throw new IllegalArgumentException("optimalBatchSize must be >= 1");
        }
        if (maxBatchSize != null && maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be >= 1");
        }
        if (optimalBatchSize != null && maxBatchSize != null && optimalBatchSize > maxBatchSize) {
            throw new IllegalArgumentException("optimalBatchSize cannot be greater than maxBatchSize");
        }
        if (memoryScaleFactor != null && memoryScaleFactor != -1.0 && memoryScaleFactor <= 0) {
            throw new IllegalArgumentException("memoryScaleFactor must be > 0 or -1 for auto");
        }
    }
}
