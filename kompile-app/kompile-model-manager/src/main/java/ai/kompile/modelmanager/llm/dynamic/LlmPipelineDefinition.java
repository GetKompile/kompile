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
 * Complete pipeline configuration for LLM generation.
 *
 * <p>A pipeline defines the complete flow of data through LLM stages,
 * supporting both sequential (linear) and graph (DAG) execution patterns.</p>
 *
 * <p>Analogous to {@code VlmPipelineDefinition}.</p>
 *
 * <h2>Pipeline Types</h2>
 * <ul>
 *   <li><b>SEQUENCE</b> - Stages execute in order</li>
 *   <li><b>GRAPH</b> - Stages form a DAG with explicit connections</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmPipelineDefinition {

    public enum PipelineType { SEQUENCE, GRAPH }

    private String pipelineId;
    private String displayName;
    private String description;
    private PipelineType pipelineType;
    private String modelSetId;
    private List<LlmPipelineStageConfig> stages;
    private List<LlmGraphNodeConfig> graphNodes;
    private Map<String, Object> defaultParameters;
    private boolean isBuiltin;
    private boolean enabled;
    private String createdAt;
    private String updatedAt;

    public LlmPipelineDefinition() {
        this.stages = new ArrayList<>();
        this.graphNodes = new ArrayList<>();
        this.defaultParameters = new HashMap<>();
        this.pipelineType = PipelineType.SEQUENCE;
        this.enabled = true;
    }

    // --- Builder ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final LlmPipelineDefinition def = new LlmPipelineDefinition();

        public Builder pipelineId(String pipelineId) { def.pipelineId = pipelineId; return this; }
        public Builder displayName(String displayName) { def.displayName = displayName; return this; }
        public Builder description(String description) { def.description = description; return this; }
        public Builder pipelineType(PipelineType pipelineType) { def.pipelineType = pipelineType; return this; }
        public Builder modelSetId(String modelSetId) { def.modelSetId = modelSetId; return this; }
        public Builder stages(List<LlmPipelineStageConfig> stages) { def.stages = new ArrayList<>(stages); return this; }
        public Builder graphNodes(List<LlmGraphNodeConfig> graphNodes) { def.graphNodes = new ArrayList<>(graphNodes); return this; }
        public Builder defaultParameters(Map<String, Object> params) { def.defaultParameters = new HashMap<>(params); return this; }
        public Builder isBuiltin(boolean isBuiltin) { def.isBuiltin = isBuiltin; return this; }
        public Builder enabled(boolean enabled) { def.enabled = enabled; return this; }

        public Builder addStage(LlmPipelineStageConfig stage) { def.stages.add(stage); return this; }
        public Builder addGraphNode(LlmGraphNodeConfig node) { def.graphNodes.add(node); return this; }
        public Builder addParameter(String key, Object value) { def.defaultParameters.put(key, value); return this; }

        public LlmPipelineDefinition build() {
            if (def.pipelineId == null || def.pipelineId.isEmpty()) {
                throw new IllegalArgumentException("pipelineId is required");
            }
            def.createdAt = java.time.Instant.now().toString();
            def.updatedAt = def.createdAt;
            return def;
        }
    }

    // --- Getters and Setters ---

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public PipelineType getPipelineType() { return pipelineType; }
    public void setPipelineType(PipelineType pipelineType) { this.pipelineType = pipelineType; }
    public String getModelSetId() { return modelSetId; }
    public void setModelSetId(String modelSetId) { this.modelSetId = modelSetId; }
    public List<LlmPipelineStageConfig> getStages() { return stages; }
    public void setStages(List<LlmPipelineStageConfig> stages) { this.stages = stages; }
    public List<LlmGraphNodeConfig> getGraphNodes() { return graphNodes; }
    public void setGraphNodes(List<LlmGraphNodeConfig> graphNodes) { this.graphNodes = graphNodes; }
    public Map<String, Object> getDefaultParameters() { return defaultParameters; }
    public void setDefaultParameters(Map<String, Object> defaultParameters) { this.defaultParameters = defaultParameters; }
    public boolean isBuiltin() { return isBuiltin; }
    public void setBuiltin(boolean builtin) { isBuiltin = builtin; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    // --- Convenience ---

    public int getStageCount() {
        return pipelineType == PipelineType.GRAPH ? graphNodes.size() : stages.size();
    }

    public void touch() {
        this.updatedAt = java.time.Instant.now().toString();
    }
}
