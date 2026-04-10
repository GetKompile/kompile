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

import java.util.Collections;
import java.util.Map;

/**
 * Represents a model component in a SameDiff LLM pipeline.
 */
public class SameDiffLLMModelComponent {
    
    /**
     * Model role/type in the pipeline.
     */
    public enum ModelRole {
        TOKENIZER("tokenizer", "Tokenizes input text into token IDs"),
        EMBED_TOKENS("embed_tokens", "Converts token IDs to hidden state embeddings"),
        DECODER("decoder", "Autoregressive decoder for token generation"),
        TOKEN_DECODER("token_decoder", "Converts generated tokens back to text");
        
        private final String id;
        private final String description;
        
        ModelRole(String id, String description) {
            this.id = id;
            this.description = description;
        }
        
        public String getId() {
            return id;
        }
        
        public String getDescription() {
            return description;
        }
        
        public static ModelRole fromId(String id) {
            for (ModelRole role : values()) {
                if (role.id.equals(id)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown model role: " + id);
        }
    }
    
    /**
     * Model architecture type.
     */
    public enum ModelArchitecture {
        SMOLLM("SmolLM", "Small language model optimized for efficiency"),
        PHI("Phi", "Microsoft Phi series compact models"),
        GEMMA("Gemma", "Google Gemma lightweight models"),
        LLAMA("LLaMA", "Meta LLaMA open models"),
        MISTRAL("Mistral", "Mistral AI efficient models"),
        QWEN("Qwen", "Alibaba Qwen series"),
        CUSTOM("Custom", "Custom SameDiff model");
        
        private final String id;
        private final String description;
        
        ModelArchitecture(String id, String description) {
            this.id = id;
            this.description = description;
        }
        
        public String getId() {
            return id;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    private final String componentKey;
    private final String fileName;
    private final ModelRole role;
    private final ModelArchitecture architecture;
    private final String inputShape;
    private final String outputShape;
    private final Map<String, Object> metadata;
    
    private SameDiffLLMModelComponent(Builder builder) {
        this.componentKey = builder.componentKey;
        this.fileName = builder.fileName;
        this.role = builder.role;
        this.architecture = builder.architecture;
        this.inputShape = builder.inputShape;
        this.outputShape = builder.outputShape;
        this.metadata = Collections.unmodifiableMap(builder.metadata);
    }
    
    public String getComponentKey() {
        return componentKey;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public ModelRole getRole() {
        return role;
    }
    
    public ModelArchitecture getArchitecture() {
        return architecture;
    }
    
    public String getInputShape() {
        return inputShape;
    }
    
    public String getOutputShape() {
        return outputShape;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String componentKey;
        private String fileName;
        private ModelRole role;
        private ModelArchitecture architecture = ModelArchitecture.CUSTOM;
        private String inputShape;
        private String outputShape;
        private Map<String, Object> metadata = new java.util.HashMap<>();
        
        public Builder componentKey(String key) {
            this.componentKey = key;
            return this;
        }
        
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }
        
        public Builder role(ModelRole role) {
            this.role = role;
            return this;
        }
        
        public Builder architecture(ModelArchitecture architecture) {
            this.architecture = architecture;
            return this;
        }
        
        public Builder inputShape(String shape) {
            this.inputShape = shape;
            return this;
        }
        
        public Builder outputShape(String shape) {
            this.outputShape = shape;
            return this;
        }
        
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }
        
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        
        public SameDiffLLMModelComponent build() {
            if (componentKey == null || fileName == null || role == null) {
                throw new IllegalStateException("componentKey, fileName, and role are required");
            }
            return new SameDiffLLMModelComponent(this);
        }
    }
}
