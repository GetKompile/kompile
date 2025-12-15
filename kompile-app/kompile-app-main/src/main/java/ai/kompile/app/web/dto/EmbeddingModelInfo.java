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
 * DTO for embedding model information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingModelInfo(
        /**
         * Model identifier (e.g., "bge-base-en-v1.5").
         */
        String modelId,

        /**
         * Human-readable display name.
         */
        String displayName,

        /**
         * Embedding vector dimensions.
         */
        int dimensions,

        /**
         * Model type (DENSE, SPARSE).
         */
        String modelType,

        /**
         * Whether the model is currently loaded.
         */
        boolean isLoaded,

        /**
         * Implementation class name.
         */
        String implementationClass,

        /**
         * Current batch size configuration for this model.
         */
        BatchSizeConfigResponse batchConfig
) {
    /**
     * Builder for constructing EmbeddingModelInfo.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String modelId;
        private String displayName;
        private int dimensions;
        private String modelType = "DENSE";
        private boolean isLoaded = false;
        private String implementationClass;
        private BatchSizeConfigResponse batchConfig;

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder modelType(String modelType) {
            this.modelType = modelType;
            return this;
        }

        public Builder isLoaded(boolean isLoaded) {
            this.isLoaded = isLoaded;
            return this;
        }

        public Builder implementationClass(String implementationClass) {
            this.implementationClass = implementationClass;
            return this;
        }

        public Builder batchConfig(BatchSizeConfigResponse batchConfig) {
            this.batchConfig = batchConfig;
            return this;
        }

        public EmbeddingModelInfo build() {
            return new EmbeddingModelInfo(
                    modelId, displayName, dimensions, modelType,
                    isLoaded, implementationClass, batchConfig
            );
        }
    }
}
