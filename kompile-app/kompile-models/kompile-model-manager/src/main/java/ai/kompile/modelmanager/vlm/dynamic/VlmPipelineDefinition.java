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

import ai.kompile.modelmanager.vlm.VlmExtractionConfig;
import ai.kompile.modelmanager.vlm.VlmExtractionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

/**
 * Complete pipeline configuration for VLM extraction.
 *
 * A pipeline defines the complete flow of data through VLM stages,
 * supporting both sequential (linear) and graph (DAG) execution patterns.
 *
 * <h2>Pipeline Types</h2>
 * <ul>
 *   <li><b>SEQUENCE</b> - Stages execute in order, output of one feeds input of next</li>
 *   <li><b>GRAPH</b> - Stages form a DAG with explicit input/output connections</li>
 * </ul>
 *
 * <h2>Example: Sequential Pipeline</h2>
 * <pre>{@code
 * VlmPipelineDefinition pipeline = VlmPipelineDefinition.builder()
 *     .pipelineId("document-understanding-v1")
 *     .displayName("Document Understanding Pipeline")
 *     .pipelineType(PipelineType.SEQUENCE)
 *     .modelSetId("smoldocling-256m")
 *     .addStage(VlmPipelineStageConfig.builder()
 *         .stageId("IMAGE_PREPROCESSING").order(0).build())
 *     .addStage(VlmPipelineStageConfig.builder()
 *         .stageId("VISION_ENCODING").order(1).build())
 *     .addExtractionType("document-understanding")
 *     .build();
 * }</pre>
 *
 * <h2>Example: Graph Pipeline</h2>
 * <pre>{@code
 * VlmPipelineDefinition graphPipeline = VlmPipelineDefinition.builder()
 *     .pipelineId("parallel-vlm")
 *     .displayName("Parallel Vision-Text Pipeline")
 *     .pipelineType(PipelineType.GRAPH)
 *     .addGraphNode(VlmGraphNodeConfig.builder()
 *         .nodeId("vision").stageId("VISION_ENCODING")
 *         .inputs(List.of("input")).build())
 *     .addGraphNode(VlmGraphNodeConfig.builder()
 *         .nodeId("text").stageId("TEXT_TOKENIZATION")
 *         .inputs(List.of("input")).build())
 *     .addGraphNode(VlmGraphNodeConfig.builder()
 *         .nodeId("fusion").stageId("VISION_TEXT_FUSION")
 *         .inputs(List.of("vision", "text")).build())
 *     .build();
 * }</pre>
 *
 * @author Kompile Inc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class VlmPipelineDefinition {

    /**
     * Type of pipeline execution.
     */
    public enum PipelineType {
        SEQUENCE,
        GRAPH
    }

    private String pipelineId;
    private String displayName;
    private String description;
    private PipelineType pipelineType;
    @Setter(lombok.AccessLevel.NONE)
    private List<VlmPipelineStageConfig> stages;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, VlmGraphNodeConfig> graphNodes;
    private String modelSetId;
    @Setter(lombok.AccessLevel.NONE)
    private List<String> extractionTypes;
    @Setter(lombok.AccessLevel.NONE)
    private Map<String, Object> defaultParameters;
    private boolean isBuiltin;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    // Default constructor for Jackson
    public VlmPipelineDefinition() {
        this.stages = new ArrayList<>();
        this.graphNodes = new LinkedHashMap<>();
        this.extractionTypes = new ArrayList<>();
        this.defaultParameters = new LinkedHashMap<>();
        this.enabled = true;
    }

    private VlmPipelineDefinition(Builder builder) {
        this.pipelineId = builder.pipelineId;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.pipelineType = builder.pipelineType;
        this.stages = builder.stages != null ?
            new ArrayList<>(builder.stages) : new ArrayList<>();
        this.graphNodes = builder.graphNodes != null ?
            new LinkedHashMap<>(builder.graphNodes) : new LinkedHashMap<>();
        this.modelSetId = builder.modelSetId;
        this.extractionTypes = builder.extractionTypes != null ?
            new ArrayList<>(builder.extractionTypes) : new ArrayList<>();
        this.defaultParameters = builder.defaultParameters != null ?
            new LinkedHashMap<>(builder.defaultParameters) : new LinkedHashMap<>();
        this.isBuiltin = builder.isBuiltin;
        this.enabled = builder.enabled;
        this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /**
     * Create a builtin pipeline from a VlmExtractionConfig preset.
     */
    public static VlmPipelineDefinition fromExtractionConfig(
            String pipelineId, String displayName, VlmExtractionConfig config) {
        Builder builder = builder()
            .pipelineId(pipelineId)
            .displayName(displayName)
            .pipelineType(PipelineType.SEQUENCE)
            .isBuiltin(true)
            .enabled(true);

        // Add extraction types
        for (VlmExtractionType type : config.getEnabledExtractions()) {
            builder.addExtractionType(type.getId());
        }

        // Add default parameters from config
        builder.defaultParameters(config.toMap());

        return builder.build();
    }

    /**
     * Convert to VlmExtractionConfig for backward compatibility.
     */
    public VlmExtractionConfig toExtractionConfig() {
        VlmExtractionConfig.Builder builder = VlmExtractionConfig.builder();

        // Enable extractions
        for (String typeId : extractionTypes) {
            VlmExtractionType type = VlmExtractionType.fromId(typeId);
            if (type != null) {
                builder.enableExtraction(type);
            }
        }

        // Set parameters
        for (Map.Entry<String, Object> entry : defaultParameters.entrySet()) {
            builder.setParameter(entry.getKey(), entry.getValue());
        }

        return builder.build();
    }

    // Custom setters with null-safety for collection fields

    public void setStages(List<VlmPipelineStageConfig> stages) {
        this.stages = stages != null ? stages : new ArrayList<>();
    }

    public void setGraphNodes(Map<String, VlmGraphNodeConfig> graphNodes) {
        this.graphNodes = graphNodes != null ? graphNodes : new LinkedHashMap<>();
    }

    public void setExtractionTypes(List<String> extractionTypes) {
        this.extractionTypes = extractionTypes != null ? extractionTypes : new ArrayList<>();
    }

    public void setDefaultParameters(Map<String, Object> defaultParameters) {
        this.defaultParameters = defaultParameters != null ? defaultParameters : new LinkedHashMap<>();
    }

    /**
     * Get a default parameter value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, T defaultValue) {
        Object value = defaultParameters.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Check if this is a sequence pipeline.
     */
    public boolean isSequence() {
        return pipelineType == PipelineType.SEQUENCE;
    }

    /**
     * Check if this is a graph pipeline.
     */
    public boolean isGraph() {
        return pipelineType == PipelineType.GRAPH;
    }

    /**
     * Get stages sorted by order (for sequence pipelines).
     */
    public List<VlmPipelineStageConfig> getSortedStages() {
        List<VlmPipelineStageConfig> sorted = new ArrayList<>(stages);
        sorted.sort(Comparator.comparingInt(VlmPipelineStageConfig::getOrder));
        return sorted;
    }

    /**
     * Get enabled stages only.
     */
    public List<VlmPipelineStageConfig> getEnabledStages() {
        return stages.stream()
            .filter(VlmPipelineStageConfig::isEnabled)
            .sorted(Comparator.comparingInt(VlmPipelineStageConfig::getOrder))
            .toList();
    }

    /**
     * Get enabled graph nodes only.
     */
    public Map<String, VlmGraphNodeConfig> getEnabledGraphNodes() {
        Map<String, VlmGraphNodeConfig> enabled = new LinkedHashMap<>();
        for (Map.Entry<String, VlmGraphNodeConfig> entry : graphNodes.entrySet()) {
            if (entry.getValue().isEnabled()) {
                enabled.put(entry.getKey(), entry.getValue());
            }
        }
        return enabled;
    }

    /**
     * Validate the pipeline configuration.
     *
     * @return list of validation errors, empty if valid
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (pipelineId == null || pipelineId.isBlank()) {
            errors.add("pipelineId is required");
        }

        if (displayName == null || displayName.isBlank()) {
            errors.add("displayName is required");
        }

        if (pipelineType == null) {
            errors.add("pipelineType is required");
        } else if (pipelineType == PipelineType.SEQUENCE) {
            if (stages == null || stages.isEmpty()) {
                errors.add("Sequence pipeline requires at least one stage");
            }
        } else if (pipelineType == PipelineType.GRAPH) {
            if (graphNodes == null || graphNodes.isEmpty()) {
                errors.add("Graph pipeline requires at least one node");
            } else {
                // Validate graph structure
                errors.addAll(validateGraphStructure());
            }
        }

        return errors;
    }

    /**
     * Validate graph structure for cycles and missing references.
     */
    private List<String> validateGraphStructure() {
        List<String> errors = new ArrayList<>();
        Set<String> nodeIds = graphNodes.keySet();

        // Check for invalid input references
        for (VlmGraphNodeConfig node : graphNodes.values()) {
            for (String inputId : node.getInputs()) {
                if (!"input".equals(inputId) && !nodeIds.contains(inputId)) {
                    errors.add("Node '" + node.getNodeId() +
                        "' references unknown input: " + inputId);
                }
            }
        }

        // Check for cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String nodeId : nodeIds) {
            if (hasCycle(nodeId, visited, inStack)) {
                errors.add("Graph contains a cycle involving node: " + nodeId);
                break;
            }
        }

        return errors;
    }

    private boolean hasCycle(String nodeId, Set<String> visited, Set<String> inStack) {
        if (inStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        inStack.add(nodeId);

        VlmGraphNodeConfig node = graphNodes.get(nodeId);
        if (node != null) {
            for (String inputId : node.getInputs()) {
                if (!"input".equals(inputId) && graphNodes.containsKey(inputId)) {
                    if (hasCycle(inputId, visited, inStack)) {
                        return true;
                    }
                }
            }
        }

        inStack.remove(nodeId);
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VlmPipelineDefinition that = (VlmPipelineDefinition) o;
        return Objects.equals(pipelineId, that.pipelineId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pipelineId);
    }

    @Override
    public String toString() {
        return "VlmPipelineDefinition{" +
            "pipelineId='" + pipelineId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", pipelineType=" + pipelineType +
            ", stages=" + stages.size() +
            ", isBuiltin=" + isBuiltin +
            ", enabled=" + enabled +
            '}';
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String pipelineId;
        private String displayName;
        private String description;
        private PipelineType pipelineType = PipelineType.SEQUENCE;
        private List<VlmPipelineStageConfig> stages = new ArrayList<>();
        private Map<String, VlmGraphNodeConfig> graphNodes = new LinkedHashMap<>();
        private String modelSetId;
        private List<String> extractionTypes = new ArrayList<>();
        private Map<String, Object> defaultParameters = new LinkedHashMap<>();
        private boolean isBuiltin = false;
        private boolean enabled = true;
        private long createdAt;

        public Builder pipelineId(String pipelineId) {
            this.pipelineId = pipelineId;
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

        public Builder pipelineType(PipelineType pipelineType) {
            this.pipelineType = pipelineType;
            return this;
        }

        public Builder stages(List<VlmPipelineStageConfig> stages) {
            this.stages = stages != null ? new ArrayList<>(stages) : new ArrayList<>();
            return this;
        }

        public Builder addStage(VlmPipelineStageConfig stage) {
            this.stages.add(stage);
            return this;
        }

        public Builder graphNodes(Map<String, VlmGraphNodeConfig> graphNodes) {
            this.graphNodes = graphNodes != null ?
                new LinkedHashMap<>(graphNodes) : new LinkedHashMap<>();
            return this;
        }

        public Builder addGraphNode(VlmGraphNodeConfig node) {
            this.graphNodes.put(node.getNodeId(), node);
            return this;
        }

        public Builder modelSetId(String modelSetId) {
            this.modelSetId = modelSetId;
            return this;
        }

        public Builder extractionTypes(List<String> extractionTypes) {
            this.extractionTypes = extractionTypes != null ?
                new ArrayList<>(extractionTypes) : new ArrayList<>();
            return this;
        }

        public Builder addExtractionType(String extractionType) {
            this.extractionTypes.add(extractionType);
            return this;
        }

        public Builder defaultParameters(Map<String, Object> defaultParameters) {
            this.defaultParameters = defaultParameters != null ?
                new LinkedHashMap<>(defaultParameters) : new LinkedHashMap<>();
            return this;
        }

        public Builder defaultParameter(String key, Object value) {
            this.defaultParameters.put(key, value);
            return this;
        }

        public Builder isBuiltin(boolean isBuiltin) {
            this.isBuiltin = isBuiltin;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public VlmPipelineDefinition build() {
            Objects.requireNonNull(pipelineId, "pipelineId is required");
            Objects.requireNonNull(displayName, "displayName is required");
            return new VlmPipelineDefinition(this);
        }
    }
}
