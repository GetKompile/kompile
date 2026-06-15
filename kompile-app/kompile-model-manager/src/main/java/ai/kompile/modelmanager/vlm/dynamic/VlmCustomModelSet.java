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

package ai.kompile.modelmanager.vlm.dynamic;

import ai.kompile.modelmanager.vlm.VlmModelComponent;
import ai.kompile.modelmanager.vlm.VlmModelSet;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * Runtime-registrable model set for VLM pipelines.
 *
 * Allows users to define custom model sets that can be used in pipelines,
 * supporting models from HuggingFace, local files, or custom URLs.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create custom model set from HuggingFace
 * VlmCustomModelSet customSet = VlmCustomModelSet.builder()
 *     .setId("my-custom-vlm")
 *     .displayName("My Custom VLM")
 *     .source(ModelSource.HUGGINGFACE)
 *     .huggingFaceRepo("username/my-model")
 *     .addComponent(VlmModelComponentConfig.builder()
 *         .componentKey("vision_encoder")
 *         .fileName("vision.onnx")
 *         .downloadUrl("https://huggingface.co/username/my-model/resolve/main/vision.onnx")
 *         .build())
 *     .build();
 * }</pre>
 *
 * @author Kompile Inc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class VlmCustomModelSet {

    /**
     * Source of the model files.
     */
    public enum ModelSource {
        HUGGINGFACE,
        LOCAL,
        CUSTOM_URL
    }

    private String setId;
    private String displayName;
    private String description;
    private ModelSource source;
    private String huggingFaceRepo;
    private String localPath;
    @Setter(lombok.AccessLevel.NONE)
    private List<VlmModelComponentConfig> components;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> pipelineConfig;
    private boolean isBuiltin;
    private long createdAt;
    private long updatedAt;

    // Default constructor for Jackson
    public VlmCustomModelSet() {
        this.components = new ArrayList<>();
        this.pipelineConfig = new LinkedHashMap<>();
    }

    private VlmCustomModelSet(Builder builder) {
        this.setId = builder.setId;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.source = builder.source;
        this.huggingFaceRepo = builder.huggingFaceRepo;
        this.localPath = builder.localPath;
        this.components = builder.components != null ?
            new ArrayList<>(builder.components) : new ArrayList<>();
        this.pipelineConfig = builder.pipelineConfig != null ?
            new LinkedHashMap<>(builder.pipelineConfig) : new LinkedHashMap<>();
        this.isBuiltin = builder.isBuiltin;
        this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /**
     * Create from a builtin VlmModelSet.
     */
    public static VlmCustomModelSet fromBuiltin(VlmModelSet modelSet) {
        List<VlmModelComponentConfig> components = new ArrayList<>();
        for (VlmModelComponent comp : modelSet.getComponents()) {
            components.add(VlmModelComponentConfig.fromBuiltin(comp));
        }

        return builder()
            .setId(modelSet.getSetId())
            .displayName(modelSet.getDisplayName())
            .description(modelSet.getDescription())
            .source(ModelSource.HUGGINGFACE)
            .huggingFaceRepo(modelSet.getHuggingFaceRepo())
            .components(components)
            .pipelineConfig(new LinkedHashMap<>(modelSet.getPipelineConfig()))
            .isBuiltin(true)
            .build();
    }

    /**
     * Convert back to VlmModelSet for use with existing APIs.
     */
    @JsonIgnore
    public VlmModelSet toModelSet() {
        VlmModelSet.Builder builder = VlmModelSet.builder()
            .setId(setId)
            .displayName(displayName)
            .description(description)
            .huggingFaceRepo(huggingFaceRepo);

        for (VlmModelComponentConfig comp : components) {
            builder.addComponent(comp.toModelComponent());
        }

        for (Map.Entry<String, Object> entry : pipelineConfig.entrySet()) {
            builder.pipelineConfig(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    // Custom setters with null-safety

    public void setComponents(List<VlmModelComponentConfig> components) {
        this.components = components != null ? components : new ArrayList<>();
    }

    public void setPipelineConfig(Map<String, Object> pipelineConfig) {
        this.pipelineConfig = pipelineConfig != null ? pipelineConfig : new LinkedHashMap<>();
    }

    /**
     * Get pipeline configuration value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getPipelineConfigValue(String key, T defaultValue) {
        Object value = pipelineConfig.get(key);
        return value != null ? (T) value : defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VlmCustomModelSet that = (VlmCustomModelSet) o;
        return Objects.equals(setId, that.setId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(setId);
    }

    @Override
    public String toString() {
        return "VlmCustomModelSet{" +
            "setId='" + setId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", source=" + source +
            ", isBuiltin=" + isBuiltin +
            ", components=" + components.size() +
            '}';
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String setId;
        private String displayName;
        private String description;
        private ModelSource source = ModelSource.HUGGINGFACE;
        private String huggingFaceRepo;
        private String localPath;
        private List<VlmModelComponentConfig> components = new ArrayList<>();
        private Map<String, Object> pipelineConfig = new LinkedHashMap<>();
        private boolean isBuiltin = false;
        private long createdAt;

        public Builder setId(String setId) {
            this.setId = setId;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder source(ModelSource source) {
            this.source = source;
            return this;
        }

        public Builder huggingFaceRepo(String huggingFaceRepo) {
            this.huggingFaceRepo = huggingFaceRepo;
            return this;
        }

        public Builder localPath(String localPath) {
            this.localPath = localPath;
            return this;
        }

        public Builder components(List<VlmModelComponentConfig> components) {
            this.components = components != null ? new ArrayList<>(components) : new ArrayList<>();
            return this;
        }

        public Builder addComponent(VlmModelComponentConfig component) {
            this.components.add(component);
            return this;
        }

        public Builder pipelineConfig(Map<String, Object> pipelineConfig) {
            this.pipelineConfig = pipelineConfig != null ?
                new LinkedHashMap<>(pipelineConfig) : new LinkedHashMap<>();
            return this;
        }

        public Builder pipelineConfig(String key, Object value) {
            this.pipelineConfig.put(key, value);
            return this;
        }

        public Builder isBuiltin(boolean isBuiltin) {
            this.isBuiltin = isBuiltin;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public VlmCustomModelSet build() {
            Objects.requireNonNull(setId, "setId is required");
            Objects.requireNonNull(displayName, "displayName is required");
            return new VlmCustomModelSet(this);
        }
    }

    /**
     * Configuration for a single model component.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Getter
    @Setter
    public static class VlmModelComponentConfig {
        private String componentKey;
        private String fileName;
        private String downloadUrl;
        private String pipelineStage;
        private String description;
        private String inputShape;
        private String outputShape;
        private long estimatedSizeBytes;

        // Default constructor for Jackson
        public VlmModelComponentConfig() {
        }

        private VlmModelComponentConfig(ComponentBuilder builder) {
            this.componentKey = builder.componentKey;
            this.fileName = builder.fileName;
            this.downloadUrl = builder.downloadUrl;
            this.pipelineStage = builder.pipelineStage;
            this.description = builder.description;
            this.inputShape = builder.inputShape;
            this.outputShape = builder.outputShape;
            this.estimatedSizeBytes = builder.estimatedSizeBytes;
        }

        /**
         * Create from builtin VlmModelComponent.
         */
        public static VlmModelComponentConfig fromBuiltin(VlmModelComponent component) {
            return builder()
                .componentKey(component.getComponentKey())
                .fileName(component.getFileName())
                .downloadUrl(component.getDownloadUrl())
                .pipelineStage(component.getPipelineStage() != null ?
                    component.getPipelineStage().name() : null)
                .description(component.getDescription())
                .inputShape(component.getInputShape())
                .outputShape(component.getOutputShape())
                .estimatedSizeBytes(component.getEstimatedSizeBytes())
                .build();
        }

        /**
         * Convert to VlmModelComponent.
         */
        @JsonIgnore
        public VlmModelComponent toModelComponent() {
            VlmModelComponent.Builder builder = VlmModelComponent.builder()
                .componentKey(componentKey)
                .fileName(fileName)
                .downloadUrl(downloadUrl)
                .description(description)
                .inputShape(inputShape)
                .outputShape(outputShape)
                .estimatedSizeBytes(estimatedSizeBytes);

            if (pipelineStage != null) {
                try {
                    builder.pipelineStage(
                        ai.kompile.modelmanager.vlm.VlmPipelineStage.valueOf(pipelineStage));
                } catch (IllegalArgumentException ignored) {
                    // Custom stage, not a builtin enum value
                }
            }

            return builder.build();
        }

        public static ComponentBuilder builder() {
            return new ComponentBuilder();
        }

        public static class ComponentBuilder {
            private String componentKey;
            private String fileName;
            private String downloadUrl;
            private String pipelineStage;
            private String description;
            private String inputShape;
            private String outputShape;
            private long estimatedSizeBytes;

            public ComponentBuilder componentKey(String componentKey) {
                this.componentKey = componentKey;
                return this;
            }

            public ComponentBuilder fileName(String fileName) {
                this.fileName = fileName;
                return this;
            }

            public ComponentBuilder downloadUrl(String downloadUrl) {
                this.downloadUrl = downloadUrl;
                return this;
            }

            public ComponentBuilder pipelineStage(String pipelineStage) {
                this.pipelineStage = pipelineStage;
                return this;
            }

            public ComponentBuilder description(String description) {
                this.description = description;
                return this;
            }

            public ComponentBuilder inputShape(String inputShape) {
                this.inputShape = inputShape;
                return this;
            }

            public ComponentBuilder outputShape(String outputShape) {
                this.outputShape = outputShape;
                return this;
            }

            public ComponentBuilder estimatedSizeBytes(long estimatedSizeBytes) {
                this.estimatedSizeBytes = estimatedSizeBytes;
                return this;
            }

            public VlmModelComponentConfig build() {
                Objects.requireNonNull(componentKey, "componentKey is required");
                Objects.requireNonNull(fileName, "fileName is required");
                return new VlmModelComponentConfig(this);
            }
        }
    }
}
