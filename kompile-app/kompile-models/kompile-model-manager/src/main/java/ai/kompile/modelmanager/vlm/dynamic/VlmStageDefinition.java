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

import ai.kompile.modelmanager.vlm.VlmPipelineStage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * Dynamic stage definition that extends the enum concept.
 *
 * Stages can be either builtin (from VlmPipelineStage enum) or custom-defined by users.
 * Each stage represents a distinct transformation in a VLM pipeline.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create custom stage
 * VlmStageDefinition customStage = VlmStageDefinition.builder()
 *     .stageId("custom-ocr-preprocessing")
 *     .displayName("Custom OCR Preprocessing")
 *     .inputDescription("Raw document image")
 *     .outputDescription("Preprocessed image with enhanced contrast")
 *     .requiresModel(false)
 *     .build();
 *
 * // Get builtin stage
 * VlmStageDefinition builtinStage = VlmStageDefinition.fromEnum(VlmPipelineStage.VISION_ENCODING);
 * }</pre>
 *
 * @author Kompile Inc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class VlmStageDefinition {

    private String stageId;
    private String displayName;
    private String inputDescription;
    private String outputDescription;
    private String modelComponentKey;
    private boolean requiresModel;
    @Getter(value = lombok.AccessLevel.NONE)
    @Setter(value = lombok.AccessLevel.NONE)
    private boolean isBuiltin;
    private String description;

    // Default constructor for Jackson
    public VlmStageDefinition() {
    }

    private VlmStageDefinition(Builder builder) {
        this.stageId = builder.stageId;
        this.displayName = builder.displayName;
        this.inputDescription = builder.inputDescription;
        this.outputDescription = builder.outputDescription;
        this.modelComponentKey = builder.modelComponentKey;
        this.requiresModel = builder.requiresModel;
        this.isBuiltin = builder.isBuiltin;
        this.description = builder.description;
    }

    /**
     * Create a VlmStageDefinition from a builtin enum value.
     */
    public static VlmStageDefinition fromEnum(VlmPipelineStage stage) {
        return builder()
            .stageId(stage.name())
            .displayName(stage.getDisplayName())
            .inputDescription(stage.getInputDescription())
            .outputDescription(stage.getOutputDescription())
            .modelComponentKey(stage.getModelComponentKey())
            .requiresModel(stage.requiresModel())
            .isBuiltin(true)
            .build();
    }

    /**
     * Try to map this definition to a builtin enum.
     * Returns null if this is a custom stage.
     */
    @JsonIgnore
    public VlmPipelineStage toEnumIfBuiltin() {
        if (!isBuiltin) {
            return null;
        }
        try {
            return VlmPipelineStage.valueOf(stageId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Manual accessor for 'isBuiltin' field to avoid Lombok generating 'isIsBuiltin()'

    public boolean isBuiltin() {
        return isBuiltin;
    }

    public void setBuiltin(boolean builtin) {
        isBuiltin = builtin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VlmStageDefinition that = (VlmStageDefinition) o;
        return Objects.equals(stageId, that.stageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stageId);
    }

    @Override
    public String toString() {
        return "VlmStageDefinition{" +
            "stageId='" + stageId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", requiresModel=" + requiresModel +
            ", isBuiltin=" + isBuiltin +
            '}';
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stageId;
        private String displayName;
        private String inputDescription;
        private String outputDescription;
        private String modelComponentKey;
        private boolean requiresModel;
        private boolean isBuiltin = false;
        private String description;

        public Builder stageId(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder inputDescription(String inputDescription) {
            this.inputDescription = inputDescription;
            return this;
        }

        public Builder outputDescription(String outputDescription) {
            this.outputDescription = outputDescription;
            return this;
        }

        public Builder modelComponentKey(String modelComponentKey) {
            this.modelComponentKey = modelComponentKey;
            return this;
        }

        public Builder requiresModel(boolean requiresModel) {
            this.requiresModel = requiresModel;
            return this;
        }

        public Builder isBuiltin(boolean isBuiltin) {
            this.isBuiltin = isBuiltin;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public VlmStageDefinition build() {
            Objects.requireNonNull(stageId, "stageId is required");
            Objects.requireNonNull(displayName, "displayName is required");
            return new VlmStageDefinition(this);
        }
    }
}
