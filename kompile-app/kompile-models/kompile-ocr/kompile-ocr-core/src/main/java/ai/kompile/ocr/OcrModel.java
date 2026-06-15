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

package ai.kompile.ocr;

import java.util.List;

/**
 * Base interface for OCR models.
 * All OCR capabilities are implemented as SameDiff models loaded from the registry.
 *
 * <p>OCR models are registered with specific types (detection, recognition, table, layout)
 * and can be loaded/unloaded dynamically based on processing needs.</p>
 */
public interface OcrModel {

    /**
     * Gets the unique model ID from the registry.
     * Examples: "paddleocr-det-db", "layoutlm-base", "tableformer-v1"
     *
     * @return the model identifier
     */
    String getModelId();

    /**
     * Gets the OCR model type (detection, recognition, table, layout).
     *
     * @return the model type
     */
    OcrModelType getModelType();

    /**
     * Gets the human-readable name for display.
     *
     * @return the display name
     */
    String getName();

    /**
     * Gets a description of what this model does.
     *
     * @return the model description
     */
    String getDescription();

    /**
     * Checks if this model is loaded and ready for inference.
     *
     * @return true if loaded
     */
    boolean isLoaded();

    /**
     * Loads the model from the registry.
     * This may download the model if not cached locally.
     *
     * @throws Exception if loading fails
     */
    void load() throws Exception;

    /**
     * Unloads the model to free resources.
     */
    void unload();

    /**
     * Gets the capabilities of this model.
     *
     * @return the model capabilities
     */
    ModelCapabilities getCapabilities();

    /**
     * Model capabilities descriptor.
     */
    record ModelCapabilities(
        OcrModelType type,
        boolean supportsHandwriting,
        boolean supportsBatch,
        List<String> supportedLanguages,
        int maxBatchSize,
        long inputHeight,
        long inputWidth,
        double averageAccuracy
    ) {
        /**
         * Creates default capabilities for a model type.
         */
        public static ModelCapabilities defaultFor(OcrModelType type) {
            return new ModelCapabilities(
                type,
                false,
                true,
                List.of("en"),
                16,
                -1,
                -1,
                0.9
            );
        }

        /**
         * Builder for ModelCapabilities.
         */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private OcrModelType type;
            private boolean supportsHandwriting = false;
            private boolean supportsBatch = true;
            private List<String> supportedLanguages = List.of("en");
            private int maxBatchSize = 16;
            private long inputHeight = -1;
            private long inputWidth = -1;
            private double averageAccuracy = 0.9;

            public Builder type(OcrModelType type) {
                this.type = type;
                return this;
            }

            public Builder supportsHandwriting(boolean supportsHandwriting) {
                this.supportsHandwriting = supportsHandwriting;
                return this;
            }

            public Builder supportsBatch(boolean supportsBatch) {
                this.supportsBatch = supportsBatch;
                return this;
            }

            public Builder supportedLanguages(List<String> supportedLanguages) {
                this.supportedLanguages = supportedLanguages;
                return this;
            }

            public Builder maxBatchSize(int maxBatchSize) {
                this.maxBatchSize = maxBatchSize;
                return this;
            }

            public Builder inputHeight(long inputHeight) {
                this.inputHeight = inputHeight;
                return this;
            }

            public Builder inputWidth(long inputWidth) {
                this.inputWidth = inputWidth;
                return this;
            }

            public Builder averageAccuracy(double averageAccuracy) {
                this.averageAccuracy = averageAccuracy;
                return this;
            }

            public ModelCapabilities build() {
                return new ModelCapabilities(type, supportsHandwriting, supportsBatch,
                    supportedLanguages, maxBatchSize, inputHeight, inputWidth, averageAccuracy);
            }
        }
    }
}
