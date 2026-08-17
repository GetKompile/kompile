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

package ai.kompile.pipeline.management.bridge;

import ai.kompile.modelmanager.vlm.dynamic.VlmPipelineDefinition;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts VLM pipeline definitions from the model-manager registry into
 * unified pipeline definitions that can be executed by the pipeline serving subsystem.
 *
 * <p>The bridge maps VLM-specific fields (extraction types, stages, model set)
 * into the {@link UnifiedPipelineDefinition} envelope format.</p>
 */
public class VlmPipelineBuilderBridge {

    private static final Logger log = LoggerFactory.getLogger(VlmPipelineBuilderBridge.class);

    /**
     * Convert a VlmPipelineDefinition into a UnifiedPipelineDefinition.
     */
    public UnifiedPipelineDefinition toUnified(VlmPipelineDefinition vlmDef) {
        VlmPipelineDefinition.PipelineType pipelineType = vlmDef.getPipelineType() == null
                ? VlmPipelineDefinition.PipelineType.SEQUENCE : vlmDef.getPipelineType();
        UnifiedPipelineDefinition.ExecutionTopology topology =
                pipelineType == VlmPipelineDefinition.PipelineType.GRAPH
                        ? UnifiedPipelineDefinition.ExecutionTopology.GRAPH
                        : UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE;

        // The serving framework deserializes pipelineSpec as Pipeline and dispatches on
        // @class. The old @bridge-only envelope could be stored but could never be loaded by
        // the generic serving child. Emit the concrete sequence/graph envelope while retaining
        // the VLM metadata for callers that inspect the definition.
        Map<String, Object> pipelineSpec = new LinkedHashMap<>();
        pipelineSpec.put("@class", topology == UnifiedPipelineDefinition.ExecutionTopology.GRAPH
                ? "ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline"
                : "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline");
        pipelineSpec.put("id", vlmDef.getPipelineId());
        pipelineSpec.put("pipelineType", pipelineType.name());
        pipelineSpec.put("modelSetId", vlmDef.getModelSetId());
        pipelineSpec.put("extractionTypes", vlmDef.getExtractionTypes());
        pipelineSpec.put("defaultParameters", vlmDef.getDefaultParameters());
        if (topology == UnifiedPipelineDefinition.ExecutionTopology.GRAPH) {
            List<Map<String, Object>> nodes = graphNodes(vlmDef);
            pipelineSpec.put("nodes", nodes);
            pipelineSpec.put("inputNodeName", "pipeline_input");
            pipelineSpec.put("outputNodeName", nodes.isEmpty()
                    ? "pipeline_output" : nodes.get(nodes.size() - 1).get("name"));
            pipelineSpec.put("graphNodes", vlmDef.getGraphNodes());
        } else {
            pipelineSpec.put("steps", sequenceSteps(vlmDef));
            pipelineSpec.put("stages", vlmDef.getStages());
        }

        String createdAt = vlmDef.getCreatedAt() > 0 ?
                Instant.ofEpochMilli(vlmDef.getCreatedAt()).toString() : Instant.now().toString();
        String updatedAt = vlmDef.getUpdatedAt() > 0 ?
                Instant.ofEpochMilli(vlmDef.getUpdatedAt()).toString() : Instant.now().toString();

        return UnifiedPipelineDefinition.builder()
                .pipelineId(vlmDef.getPipelineId())
                .displayName(vlmDef.getDisplayName())
                .description(vlmDef.getDescription())
                .kind(UnifiedPipelineDefinition.PipelineKind.VLM)
                .topology(topology)
                .pipelineSpec(pipelineSpec)
                .modelSetId(vlmDef.getModelSetId())
                .extractionTypes(vlmDef.getExtractionTypes())
                .builtin(vlmDef.isBuiltin())
                .enabled(vlmDef.isEnabled())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .serving(UnifiedPipelineDefinition.ServingConfig.builder()
                        .heapSize("12g")
                        .build())
                .build();
    }

    private List<Map<String, Object>> sequenceSteps(VlmPipelineDefinition definition) {
        if (definition.getStages() == null) return List.of();
        List<Map<String, Object>> steps = new ArrayList<>();
        definition.getSortedStages().stream()
                .filter(stage -> stage != null && stage.isEnabled())
                .forEach(stage -> steps.add(stepConfig(
                        stage.getStageId(), stage.getParameters(), stage.getModelOverrideId(),
                        definition.getModelSetId())));
        return steps;
    }

    private List<Map<String, Object>> graphNodes(VlmPipelineDefinition definition) {
        if (definition.getGraphNodes() == null || definition.getGraphNodes().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        definition.getEnabledGraphNodes().forEach((key, node) -> {
            Map<String, Object> graphNode = new LinkedHashMap<>();
            graphNode.put("@graphNodeType", "STANDARD");
            graphNode.put("name", node.getNodeId());
            List<String> inputs = node.getInputs() == null ? List.of() : new ArrayList<>(node.getInputs());
            if (inputs.isEmpty()) inputs = List.of("pipeline_input");
            else inputs.replaceAll(input -> "input".equals(input) ? "pipeline_input" : input);
            graphNode.put("inputs", inputs);
            graphNode.put("stepConfig", stepConfig(
                    node.getStageId(), node.getParameters(), node.getModelOverrideId(), definition.getModelSetId()));
            nodes.add(graphNode);
        });
        return nodes;
    }

    private Map<String, Object> stepConfig(String stageId, Map<String, Object> parameters,
                                           String modelOverrideId, String modelSetId) {
        String runner = runnerClass(stageId);
        if (runner == null) {
            throw new IllegalArgumentException("VLM stage '" + stageId
                    + "' has no generic pipeline runner mapping. Register a concrete executor or use the crawl compatibility adapter.");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("@class", "ai.kompile.pipelines.framework.core.config.GenericStepConfig");
        config.put("runnerClassName", runner);
        Map<String, Object> values = new LinkedHashMap<>();
        if (parameters != null) values.putAll(parameters);
        if (modelOverrideId != null && !modelOverrideId.isBlank()) values.put("modelId", modelOverrideId);
        if (modelSetId != null && !modelSetId.isBlank()) values.putIfAbsent("modelSetId", modelSetId);
        config.put("parameters", values);
        return config;
    }

    private String runnerClass(String stageId) {
        if (stageId == null || stageId.isBlank()) return null;
        String id = stageId.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (id) {
            case "IMAGE_PREPROCESSING", "IMAGE_PREPROCESS" -> "ai.kompile.pipelines.steps.vlm.ImagePreprocessingStepRunner";
            case "VISION_ENCODING", "VISION_ENCODER" -> "ai.kompile.pipelines.steps.vlm.VisionEncoderStepRunner";
            case "TEXT_TOKENIZATION", "TEXT_EMBEDDING" -> "ai.kompile.pipelines.steps.vlm.TextEmbeddingStepRunner";
            case "VISION_TEXT_FUSION", "FUSION" -> "ai.kompile.pipelines.steps.vlm.VisionTextFusionStepRunner";
            case "DECODING", "DECODER", "DECODER_BODY" -> "ai.kompile.pipelines.steps.vlm.VLMDecoderStepRunner";
            case "TOKEN_SAMPLING", "SAMPLING" -> "ai.kompile.pipelines.steps.vlm.TokenSamplingStepRunner";
            case "TOKEN_DECODING", "TOKEN_DECODE" -> "ai.kompile.pipelines.steps.vlm.TokenDecodingStepRunner";
            default -> null;
        };
    }
}
