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

package ai.kompile.modelmanager.vlm;

import java.util.Objects;

/**
 * Represents a single component within a VLM model set.
 *
 * Each component corresponds to a specific file (ONNX model, tokenizer, config)
 * and is associated with a pipeline stage that documents its role.
 *
 * @author Kompile Inc.
 */
public class VlmModelComponent {

    private final String componentKey;
    private final String fileName;
    private final String downloadUrl;
    private final String checksum;
    private final VlmPipelineStage pipelineStage;
    private final String description;
    private final String inputShape;
    private final String outputShape;
    private final long estimatedSizeBytes;

    private VlmModelComponent(Builder builder) {
        this.componentKey = builder.componentKey;
        this.fileName = builder.fileName;
        this.downloadUrl = builder.downloadUrl;
        this.checksum = builder.checksum;
        this.pipelineStage = builder.pipelineStage;
        this.description = builder.description;
        this.inputShape = builder.inputShape;
        this.outputShape = builder.outputShape;
        this.estimatedSizeBytes = builder.estimatedSizeBytes;
    }

    /**
     * Get the unique key for this component within the model set.
     * Examples: "vision_encoder", "decoder", "tokenizer", "embed_tokens"
     */
    public String getComponentKey() {
        return componentKey;
    }

    /**
     * Get the file name for this component.
     * Examples: "vision_encoder.onnx", "tokenizer.json"
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Get the download URL for this component.
     */
    public String getDownloadUrl() {
        return downloadUrl;
    }

    /**
     * Get the expected SHA256 checksum (optional).
     */
    public String getChecksum() {
        return checksum;
    }

    /**
     * Get the pipeline stage this component is used in.
     */
    public VlmPipelineStage getPipelineStage() {
        return pipelineStage;
    }

    /**
     * Get a human-readable description of this component's purpose.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the expected input tensor shape description.
     * Example: "[1, 3, 512, 512] pixel_values"
     */
    public String getInputShape() {
        return inputShape;
    }

    /**
     * Get the expected output tensor shape description.
     * Example: "[1, 64, 576] image_features"
     */
    public String getOutputShape() {
        return outputShape;
    }

    /**
     * Get estimated file size in bytes (0 if unknown).
     */
    public long getEstimatedSizeBytes() {
        return estimatedSizeBytes;
    }

    /**
     * Check if this is an ONNX model file.
     */
    public boolean isOnnxModel() {
        return fileName != null && fileName.endsWith(".onnx");
    }

    /**
     * Check if this is a tokenizer file.
     */
    public boolean isTokenizer() {
        return fileName != null &&
            (fileName.contains("tokenizer") || fileName.endsWith(".json"));
    }

    /**
     * Get the file extension.
     */
    public String getFileExtension() {
        if (fileName == null) return null;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : null;
    }

    @Override
    public String toString() {
        return "VlmModelComponent{" +
            "key='" + componentKey + '\'' +
            ", file='" + fileName + '\'' +
            ", stage=" + (pipelineStage != null ? pipelineStage.name() : "null") +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VlmModelComponent that = (VlmModelComponent) o;
        return Objects.equals(componentKey, that.componentKey) &&
            Objects.equals(fileName, that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(componentKey, fileName);
    }

    // =====================================================================
    // BUILDER
    // =====================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String componentKey;
        private String fileName;
        private String downloadUrl;
        private String checksum;
        private VlmPipelineStage pipelineStage;
        private String description;
        private String inputShape;
        private String outputShape;
        private long estimatedSizeBytes;

        public Builder componentKey(String componentKey) {
            this.componentKey = componentKey;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder downloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
            return this;
        }

        public Builder checksum(String checksum) {
            this.checksum = checksum;
            return this;
        }

        public Builder pipelineStage(VlmPipelineStage pipelineStage) {
            this.pipelineStage = pipelineStage;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputShape(String inputShape) {
            this.inputShape = inputShape;
            return this;
        }

        public Builder outputShape(String outputShape) {
            this.outputShape = outputShape;
            return this;
        }

        public Builder estimatedSizeBytes(long estimatedSizeBytes) {
            this.estimatedSizeBytes = estimatedSizeBytes;
            return this;
        }

        public VlmModelComponent build() {
            Objects.requireNonNull(componentKey, "componentKey is required");
            Objects.requireNonNull(fileName, "fileName is required");
            return new VlmModelComponent(this);
        }
    }
}
