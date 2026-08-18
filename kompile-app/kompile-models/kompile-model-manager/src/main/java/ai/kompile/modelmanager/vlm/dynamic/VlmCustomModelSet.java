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
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    /** Provider identifier such as huggingface, ollama, or a project-specific adapter. */
    private String provider;
    /** Canonical provider repository/model reference. */
    private String repository;
    private String revision;
    private String format;
    /** Runtime registry type used by the project model bootstrapper (for example vlm_transformers). */
    private String modelType;
    /** Legacy Hugging Face and local fields retained for backwards compatibility. */
    private String huggingFaceRepo;
    private String localPath;
    @Setter(lombok.AccessLevel.NONE)
    private List<VlmModelComponentConfig> components;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> pipelineConfig;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> runtime;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> metadata;
    private boolean isBuiltin;
    private long createdAt;
    private long updatedAt;

    // Default constructor for Jackson
    public VlmCustomModelSet() {
        this.components = new ArrayList<>();
        this.pipelineConfig = new LinkedHashMap<>();
        this.runtime = new LinkedHashMap<>();
        this.metadata = new LinkedHashMap<>();
    }

    private VlmCustomModelSet(Builder builder) {
        this.setId = builder.setId;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.source = builder.source;
        this.provider = builder.provider;
        this.repository = builder.repository;
        this.revision = builder.revision;
        this.format = builder.format;
        this.modelType = builder.modelType;
        this.huggingFaceRepo = builder.huggingFaceRepo;
        this.localPath = builder.localPath;
        this.components = builder.components != null ?
            new ArrayList<>(builder.components) : new ArrayList<>();
        this.pipelineConfig = builder.pipelineConfig != null ?
            new LinkedHashMap<>(builder.pipelineConfig) : new LinkedHashMap<>();
        this.runtime = builder.runtime != null ?
            new LinkedHashMap<>(builder.runtime) : new LinkedHashMap<>();
        this.metadata = builder.metadata != null ?
            new LinkedHashMap<>(builder.metadata) : new LinkedHashMap<>();
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
            .provider("huggingface")
            .repository(modelSet.getHuggingFaceRepo())
            .modelType("vlm")
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
            .huggingFaceRepo(repository != null ? repository : huggingFaceRepo);

        for (VlmModelComponentConfig comp : components) {
            builder.addComponent(comp.toModelComponent());
        }

        for (Map.Entry<String, Object> entry : pipelineConfig.entrySet()) {
            builder.pipelineConfig(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    /**
     * Project-runtime model definition generated from this registry entry.
     * The map deliberately keeps provider-specific values under free-form fields so
     * new providers can be configured without changing the MCP schema.
     */
    @JsonIgnore
    public Map<String, Object> toDefinitionMap() {
        Map<String, Object> definition = new LinkedHashMap<>();
        putIfPresent(definition, "id", setId);
        putIfPresent(definition, "modelId", setId);
        putIfPresent(definition, "displayName", displayName);
        putIfPresent(definition, "description", description);
        putIfPresent(definition, "role", "VLM");
        putIfPresent(definition, "provider", provider);
        putIfPresent(definition, "source", source == null ? provider : source.name());
        putIfPresent(definition, "repository", repository != null ? repository : huggingFaceRepo);
        putIfPresent(definition, "revision", revision);
        putIfPresent(definition, "format", format);
        putIfPresent(definition, "type", modelType);
        putIfPresent(definition, "localPath", localPath);
        putIfPresent(definition, "path", localPath);
        if (components != null && !components.isEmpty()) definition.put("components", components);
        if (pipelineConfig != null && !pipelineConfig.isEmpty()) definition.put("pipelineConfig", pipelineConfig);
        if (runtime != null && !runtime.isEmpty()) definition.put("runtime", runtime);
        if (metadata != null && !metadata.isEmpty()) definition.put("metadata", metadata);
        return definition;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            target.put(key, value);
        }
    }

    /** Validate the provider-neutral definition before it is registered. */
    @JsonIgnore
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (setId == null || setId.isBlank()) errors.add("setId is required");
        if (displayName == null || displayName.isBlank()) errors.add("displayName is required");
        boolean hasLocator = (provider != null && !provider.isBlank())
                || (repository != null && !repository.isBlank())
                || (huggingFaceRepo != null && !huggingFaceRepo.isBlank())
                || (localPath != null && !localPath.isBlank())
                || (components != null && !components.isEmpty());
        if (!hasLocator) {
            errors.add("A provider, repository, localPath, or at least one component is required");
        }
        if (components != null) {
            for (int i = 0; i < components.size(); i++) {
                VlmModelComponentConfig component = components.get(i);
                if (component == null) {
                    errors.add("components[" + i + "] must not be null");
                } else {
                    if (component.getComponentKey() == null || component.getComponentKey().isBlank()) {
                        errors.add("components[" + i + "].componentKey is required");
                    }
                    if ((component.getFileName() == null || component.getFileName().isBlank())
                            && (component.getDownloadUrl() == null || component.getDownloadUrl().isBlank())) {
                        errors.add("components[" + i + "] requires fileName or downloadUrl");
                    }
                }
            }
        }
        return errors;
    }

    // Custom setters with null-safety

    public void setComponents(List<VlmModelComponentConfig> components) {
        this.components = components != null ? components : new ArrayList<>();
    }

    public void setPipelineConfig(Map<String, Object> pipelineConfig) {
        this.pipelineConfig = pipelineConfig != null ? pipelineConfig : new LinkedHashMap<>();
    }

    public void setRuntime(Map<String, Object> runtime) {
        this.runtime = runtime != null ? runtime : new LinkedHashMap<>();
    }

    public void setMetadata(Map<String, Object> metadata) {
        Map<String, Object> providerFields = this.metadata;
        this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        if (providerFields != null) {
            providerFields.forEach(this.metadata::putIfAbsent);
        }
    }

    /**
     * Preserve provider-specific top-level fields without coupling the model definition
     * contract to a fixed provider schema.
     */
    @JsonAnySetter
    public void setProviderOption(String key, Object value) {
        if (key != null && !key.isBlank()) {
            if (metadata == null) metadata = new LinkedHashMap<>();
            metadata.put(key, value);
        }
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
        private ModelSource source;
        private String provider;
        private String repository;
        private String revision;
        private String format;
        private String modelType;
        private String huggingFaceRepo;
        private String localPath;
        private List<VlmModelComponentConfig> components = new ArrayList<>();
        private Map<String, Object> pipelineConfig = new LinkedHashMap<>();
        private Map<String, Object> runtime = new LinkedHashMap<>();
        private Map<String, Object> metadata = new LinkedHashMap<>();
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

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder repository(String repository) {
            this.repository = repository;
            return this;
        }

        public Builder revision(String revision) {
            this.revision = revision;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder modelType(String modelType) {
            this.modelType = modelType;
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

        public Builder runtime(Map<String, Object> runtime) {
            this.runtime = runtime != null ? new LinkedHashMap<>(runtime) : new LinkedHashMap<>();
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Getter
    @Setter
    public static class VlmModelComponentConfig {
        private String componentKey;
        private String fileName;
        private String downloadUrl;
        private String checksum;
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
            this.checksum = builder.checksum;
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
                .checksum(component.getChecksum())
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
                .checksum(checksum)
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
            private String checksum;
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

            public ComponentBuilder checksum(String checksum) {
                this.checksum = checksum;
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
