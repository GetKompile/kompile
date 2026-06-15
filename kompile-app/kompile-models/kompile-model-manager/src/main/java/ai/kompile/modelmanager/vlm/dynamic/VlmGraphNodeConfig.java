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
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * Graph node configuration for DAG-based VLM pipelines.
 *
 * Unlike sequential pipelines where stages execute in order, graph pipelines
 * allow complex data flow patterns including:
 * - Parallel execution of independent stages
 * - Conditional branching based on intermediate results
 * - Multiple inputs from different upstream nodes
 *
 * <h2>Example: Parallel Vision and Text Processing</h2>
 * <pre>{@code
 * // Create a graph where vision and text processing happen in parallel
 * VlmGraphNodeConfig imageNode = VlmGraphNodeConfig.builder()
 *     .nodeId("image-preprocessing")
 *     .stageId("IMAGE_PREPROCESSING")
 *     .inputs(List.of("input"))
 *     .build();
 *
 * VlmGraphNodeConfig textNode = VlmGraphNodeConfig.builder()
 *     .nodeId("text-tokenization")
 *     .stageId("TEXT_TOKENIZATION")
 *     .inputs(List.of("input"))
 *     .build();
 *
 * VlmGraphNodeConfig fusionNode = VlmGraphNodeConfig.builder()
 *     .nodeId("fusion")
 *     .stageId("VISION_TEXT_FUSION")
 *     .inputs(List.of("image-preprocessing", "text-tokenization"))
 *     .build();
 * }</pre>
 *
 * @author Kompile Inc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class VlmGraphNodeConfig {

    private String nodeId;
    private String stageId;
    @Setter(value = lombok.AccessLevel.NONE)
    private List<String> inputs;
    private boolean enabled = true;
    private String modelOverrideId;
    @Setter(value = lombok.AccessLevel.NONE)
    private Map<String, Object> parameters;
    private String condition;

    // Default constructor for Jackson
    public VlmGraphNodeConfig() {
        this.inputs = new ArrayList<>();
        this.parameters = new LinkedHashMap<>();
    }

    private VlmGraphNodeConfig(Builder builder) {
        this.nodeId = builder.nodeId;
        this.stageId = builder.stageId;
        this.inputs = builder.inputs != null ? new ArrayList<>(builder.inputs) : new ArrayList<>();
        this.enabled = builder.enabled;
        this.modelOverrideId = builder.modelOverrideId;
        this.parameters = builder.parameters != null ?
            new LinkedHashMap<>(builder.parameters) : new LinkedHashMap<>();
        this.condition = builder.condition;
    }

    // Non-trivial setters with null-safety (cannot use Lombok for these)

    public void setInputs(List<String> inputs) {
        this.inputs = inputs != null ? inputs : new ArrayList<>();
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    /**
     * Check if this node is a root node (no inputs).
     */
    public boolean isRootNode() {
        return inputs == null || inputs.isEmpty();
    }

    /**
     * Check if this node has a condition.
     */
    public boolean hasCondition() {
        return condition != null && !condition.isBlank();
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
        VlmGraphNodeConfig that = (VlmGraphNodeConfig) o;
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "VlmGraphNodeConfig{" +
            "nodeId='" + nodeId + '\'' +
            ", stageId='" + stageId + '\'' +
            ", inputs=" + inputs +
            ", enabled=" + enabled +
            '}';
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private String stageId;
        private List<String> inputs = new ArrayList<>();
        private boolean enabled = true;
        private String modelOverrideId;
        private Map<String, Object> parameters = new LinkedHashMap<>();
        private String condition;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder stageId(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder inputs(List<String> inputs) {
            this.inputs = inputs != null ? new ArrayList<>(inputs) : new ArrayList<>();
            return this;
        }

        public Builder addInput(String inputNodeId) {
            this.inputs.add(inputNodeId);
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

        public Builder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public VlmGraphNodeConfig build() {
            Objects.requireNonNull(nodeId, "nodeId is required");
            Objects.requireNonNull(stageId, "stageId is required");
            return new VlmGraphNodeConfig(this);
        }
    }
}
