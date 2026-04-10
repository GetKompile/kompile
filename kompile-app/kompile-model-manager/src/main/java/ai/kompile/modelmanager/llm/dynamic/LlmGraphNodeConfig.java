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

package ai.kompile.modelmanager.llm.dynamic;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/**
 * Graph node for DAG-based LLM pipelines, analogous to {@code VlmGraphNodeConfig}.
 *
 * <p>Each node has a unique ID, references a stage definition, and specifies
 * its input dependencies from other nodes.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmGraphNodeConfig {

    private String nodeId;
    private String stageId;
    private List<String> inputs;
    private boolean enabled;
    private String modelOverrideId;
    private Map<String, Object> parameters;
    private String condition;

    public LlmGraphNodeConfig() {
        this.inputs = new ArrayList<>();
        this.parameters = new HashMap<>();
        this.enabled = true;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final LlmGraphNodeConfig config = new LlmGraphNodeConfig();

        public Builder nodeId(String nodeId) { config.nodeId = nodeId; return this; }
        public Builder stageId(String stageId) { config.stageId = stageId; return this; }
        public Builder inputs(List<String> inputs) { config.inputs = new ArrayList<>(inputs); return this; }
        public Builder addInput(String input) { config.inputs.add(input); return this; }
        public Builder enabled(boolean enabled) { config.enabled = enabled; return this; }
        public Builder modelOverrideId(String modelOverrideId) { config.modelOverrideId = modelOverrideId; return this; }
        public Builder parameters(Map<String, Object> parameters) { config.parameters = new HashMap<>(parameters); return this; }
        public Builder condition(String condition) { config.condition = condition; return this; }

        public LlmGraphNodeConfig build() {
            if (config.nodeId == null || config.nodeId.isEmpty()) {
                throw new IllegalArgumentException("nodeId is required");
            }
            if (config.stageId == null || config.stageId.isEmpty()) {
                throw new IllegalArgumentException("stageId is required");
            }
            return config;
        }
    }

    // --- Getters and Setters ---

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }
    public List<String> getInputs() { return inputs; }
    public void setInputs(List<String> inputs) { this.inputs = inputs; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModelOverrideId() { return modelOverrideId; }
    public void setModelOverrideId(String modelOverrideId) { this.modelOverrideId = modelOverrideId; }
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }
}
