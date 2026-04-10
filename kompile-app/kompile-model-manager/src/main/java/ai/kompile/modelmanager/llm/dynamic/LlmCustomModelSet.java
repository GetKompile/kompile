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

import ai.kompile.modelmanager.llm.LlmModelComponent;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

/**
 * Runtime-registrable LLM model set, analogous to {@code VlmCustomModelSet}.
 *
 * <p>Allows users to register custom model sets at runtime from HuggingFace,
 * local paths, or custom URLs.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmCustomModelSet {

    public enum ModelSource { HUGGINGFACE, LOCAL, CUSTOM_URL }

    private String setId;
    private String displayName;
    private String description;
    private ModelSource source;
    private String sourceUri;
    private List<LlmModelComponent> components;
    private Map<String, Object> pipelineConfig;
    private String createdAt;
    private String updatedAt;

    public LlmCustomModelSet() {
        this.components = new ArrayList<>();
        this.pipelineConfig = new HashMap<>();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final LlmCustomModelSet set = new LlmCustomModelSet();

        public Builder setId(String setId) { set.setId = setId; return this; }
        public Builder displayName(String displayName) { set.displayName = displayName; return this; }
        public Builder description(String description) { set.description = description; return this; }
        public Builder source(ModelSource source) { set.source = source; return this; }
        public Builder sourceUri(String sourceUri) { set.sourceUri = sourceUri; return this; }
        public Builder components(List<LlmModelComponent> components) { set.components = new ArrayList<>(components); return this; }
        public Builder addComponent(LlmModelComponent component) { set.components.add(component); return this; }
        public Builder pipelineConfig(Map<String, Object> config) { set.pipelineConfig = new HashMap<>(config); return this; }

        public LlmCustomModelSet build() {
            if (set.setId == null || set.setId.isEmpty()) {
                throw new IllegalArgumentException("setId is required");
            }
            set.createdAt = java.time.Instant.now().toString();
            set.updatedAt = set.createdAt;
            return set;
        }
    }

    // --- Getters and Setters ---

    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ModelSource getSource() { return source; }
    public void setSource(ModelSource source) { this.source = source; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public List<LlmModelComponent> getComponents() { return components; }
    public void setComponents(List<LlmModelComponent> components) { this.components = components; }
    public Map<String, Object> getPipelineConfig() { return pipelineConfig; }
    public void setPipelineConfig(Map<String, Object> pipelineConfig) { this.pipelineConfig = pipelineConfig; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public void touch() {
        this.updatedAt = java.time.Instant.now().toString();
    }
}
