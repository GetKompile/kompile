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

package ai.kompile.pipelines.steps.samediff.llm;

import java.util.*;

/**
 * Represents a SameDiff LLM model set (e.g., SmolLM-135M, Phi-2).
 * A model set contains all components needed for a complete LLM pipeline.
 */
public class SameDiffLLMModelSet {
    
    private final String setId;
    private final String displayName;
    private final String description;
    private final SameDiffLLMModelComponent.ModelArchitecture architecture;
    private final int vocabSize;
    private final int hiddenSize;
    private final int numLayers;
    private final int numHeads;
    private final int contextLength;
    private final boolean cached;
    private final List<SameDiffLLMModelComponent> components;
    private final Map<String, Object> metadata;
    
    private SameDiffLLMModelSet(Builder builder) {
        this.setId = builder.setId;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.architecture = builder.architecture;
        this.vocabSize = builder.vocabSize;
        this.hiddenSize = builder.hiddenSize;
        this.numLayers = builder.numLayers;
        this.numHeads = builder.numHeads;
        this.contextLength = builder.contextLength;
        this.cached = builder.cached;
        this.components = Collections.unmodifiableList(builder.components);
        this.metadata = Collections.unmodifiableMap(builder.metadata);
    }
    
    public String getSetId() {
        return setId;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getDescription() {
        return description;
    }
    
    public SameDiffLLMModelComponent.ModelArchitecture getArchitecture() {
        return architecture;
    }
    
    public int getVocabSize() {
        return vocabSize;
    }
    
    public int getHiddenSize() {
        return hiddenSize;
    }
    
    public int getNumLayers() {
        return numLayers;
    }
    
    public int getNumHeads() {
        return numHeads;
    }
    
    public int getContextLength() {
        return contextLength;
    }
    
    public boolean isCached() {
        return cached;
    }
    
    public List<SameDiffLLMModelComponent> getComponents() {
        return components;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public SameDiffLLMModelComponent getComponentByRole(SameDiffLLMModelComponent.ModelRole role) {
        for (SameDiffLLMModelComponent component : components) {
            if (component.getRole() == role) {
                return component;
            }
        }
        return null;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String setId;
        private String displayName;
        private String description;
        private SameDiffLLMModelComponent.ModelArchitecture architecture = SameDiffLLMModelComponent.ModelArchitecture.CUSTOM;
        private int vocabSize = 49152;
        private int hiddenSize = 576;
        private int numLayers = 30;
        private int numHeads = 9;
        private int contextLength = 2048;
        private boolean cached = false;
        private List<SameDiffLLMModelComponent> components = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();
        
        public Builder setId(String id) {
            this.setId = id;
            return this;
        }
        
        public Builder setDisplayName(String name) {
            this.displayName = name;
            return this;
        }
        
        public Builder setDescription(String description) {
            this.description = description;
            return this;
        }
        
        public Builder setArchitecture(SameDiffLLMModelComponent.ModelArchitecture architecture) {
            this.architecture = architecture;
            return this;
        }
        
        public Builder setVocabSize(int size) {
            this.vocabSize = size;
            return this;
        }
        
        public Builder setHiddenSize(int size) {
            this.hiddenSize = size;
            return this;
        }
        
        public Builder setNumLayers(int layers) {
            this.numLayers = layers;
            return this;
        }
        
        public Builder setNumHeads(int heads) {
            this.numHeads = heads;
            return this;
        }
        
        public Builder setContextLength(int length) {
            this.contextLength = length;
            return this;
        }
        
        public Builder setCached(boolean cached) {
            this.cached = cached;
            return this;
        }
        
        public Builder addComponent(SameDiffLLMModelComponent component) {
            this.components.add(component);
            return this;
        }
        
        public Builder addComponents(List<SameDiffLLMModelComponent> components) {
            this.components.addAll(components);
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public SameDiffLLMModelSet build() {
            if (setId == null || displayName == null) {
                throw new IllegalStateException("setId and displayName are required");
            }
            return new SameDiffLLMModelSet(this);
        }
    }
}
