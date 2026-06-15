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

package ai.kompile.modelmanager.llm;

import java.util.Collections;
import java.util.Map;

/**
 * Individual model component within an LLM model set.
 *
 * <p>Each component represents a single artifact (model file, tokenizer config, etc.)
 * that is part of a complete LLM pipeline. Components have roles that map to
 * specific pipeline stages.</p>
 *
 * <p>Analogous to {@link ai.kompile.modelmanager.vlm.VlmModelComponent}.</p>
 *
 * @see LlmModelSet
 * @see LlmPipelineStage
 */
public class LlmModelComponent {

    private final String componentKey;
    private final String fileName;
    private final String downloadUrl;
    private final LlmPipelineStage pipelineStage;
    private final String description;
    private final long[] inputShape;
    private final long[] outputShape;
    private final Map<String, Object> metadata;

    private LlmModelComponent(Builder builder) {
        this.componentKey = builder.componentKey;
        this.fileName = builder.fileName;
        this.downloadUrl = builder.downloadUrl;
        this.pipelineStage = builder.pipelineStage;
        this.description = builder.description;
        this.inputShape = builder.inputShape;
        this.outputShape = builder.outputShape;
        this.metadata = builder.metadata != null ? builder.metadata : Collections.emptyMap();
    }

    public String getComponentKey() { return componentKey; }
    public String getFileName() { return fileName; }
    public String getDownloadUrl() { return downloadUrl; }
    public LlmPipelineStage getPipelineStage() { return pipelineStage; }
    public String getDescription() { return description; }
    public long[] getInputShape() { return inputShape; }
    public long[] getOutputShape() { return outputShape; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "LlmModelComponent{" +
                "componentKey='" + componentKey + '\'' +
                ", fileName='" + fileName + '\'' +
                ", stage=" + (pipelineStage != null ? pipelineStage.getDisplayName() : "none") +
                '}';
    }

    public static class Builder {
        private String componentKey;
        private String fileName;
        private String downloadUrl;
        private LlmPipelineStage pipelineStage;
        private String description;
        private long[] inputShape;
        private long[] outputShape;
        private Map<String, Object> metadata;

        public Builder componentKey(String componentKey) { this.componentKey = componentKey; return this; }
        public Builder fileName(String fileName) { this.fileName = fileName; return this; }
        public Builder downloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; return this; }
        public Builder pipelineStage(LlmPipelineStage pipelineStage) { this.pipelineStage = pipelineStage; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder inputShape(long... inputShape) { this.inputShape = inputShape; return this; }
        public Builder outputShape(long... outputShape) { this.outputShape = outputShape; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public LlmModelComponent build() {
            if (componentKey == null || componentKey.isEmpty()) {
                throw new IllegalArgumentException("componentKey is required");
            }
            if (fileName == null || fileName.isEmpty()) {
                throw new IllegalArgumentException("fileName is required");
            }
            return new LlmModelComponent(this);
        }
    }
}
