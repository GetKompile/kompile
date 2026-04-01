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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a stage instance within a pipeline.
 *
 * This represents a concrete use of a stage definition within a specific pipeline,
 * including any stage-specific parameter overrides.
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * VlmPipelineStageConfig stageConfig = VlmPipelineStageConfig.builder()
 *     .stageId("VISION_ENCODING")
 *     .order(2)
 *     .enabled(true)
 *     .parameter("batchSize", 4)
 *     .parameter("precision", "fp16")
 *     .build();
 * }</pre>
 *
 * @author Kompile Inc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VlmPipelineStageConfig {

    private String stageId;
    private int order;
    private boolean enabled = true;
    private String modelOverrideId;
    private Map<String, Object> parameters;

    // Default constructor for Jackson
    public VlmPipelineStageConfig() {
        this.parameters = new LinkedHashMap<>();
    }

    private VlmPipelineStageConfig(Builder builder) {
        this.stageId = builder.stageId;
        this.order = builder.order;
        this.enabled = builder.enabled;
        this.modelOverrideId = builder.modelOverrideId;
        this.parameters = builder.parameters != null ?
            new LinkedHashMap<>(builder.parameters) : new LinkedHashMap<>();
    }

    // Getters and setters

    public String getStageId() {
        return stageId;
    }

    public void setStageId(String stageId) {
        this.stageId = stageId;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelOverrideId() {
        return modelOverrideId;
    }

    public void setModelOverrideId(String modelOverrideId) {
        this.modelOverrideId = modelOverrideId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    /**
     * Get a parameter value with default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, T defaultValue) {
        Object value = parameters.get(key);
        return value != null ? (T) value : defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VlmPipelineStageConfig that = (VlmPipelineStageConfig) o;
        return order == that.order &&
            Objects.equals(stageId, that.stageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stageId, order);
    }

    @Override
    public String toString() {
        return "VlmPipelineStageConfig{" +
            "stageId='" + stageId + '\'' +
            ", order=" + order +
            ", enabled=" + enabled +
            '}';
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stageId;
        private int order;
        private boolean enabled = true;
        private String modelOverrideId;
        private Map<String, Object> parameters = new LinkedHashMap<>();

        public Builder stageId(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder modelOverrideId(String modelOverrideId) {
            this.modelOverrideId = modelOverrideId;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters != null ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>();
            return this;
        }

        public Builder parameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public VlmPipelineStageConfig build() {
            Objects.requireNonNull(stageId, "stageId is required");
            return new VlmPipelineStageConfig(this);
        }
    }
}
