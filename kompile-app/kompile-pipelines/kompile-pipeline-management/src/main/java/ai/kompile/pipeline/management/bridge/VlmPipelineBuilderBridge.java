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
import java.util.LinkedHashMap;
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
        UnifiedPipelineDefinition.ExecutionTopology topology =
                vlmDef.getPipelineType() == VlmPipelineDefinition.PipelineType.GRAPH ?
                        UnifiedPipelineDefinition.ExecutionTopology.GRAPH :
                        UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE;

        // Build a pipelineSpec describing the VLM pipeline
        Map<String, Object> pipelineSpec = new LinkedHashMap<>();
        pipelineSpec.put("@bridge", "vlm");
        pipelineSpec.put("pipelineId", vlmDef.getPipelineId());
        pipelineSpec.put("pipelineType", vlmDef.getPipelineType().name());
        pipelineSpec.put("modelSetId", vlmDef.getModelSetId());
        if (vlmDef.getExtractionTypes() != null) {
            pipelineSpec.put("extractionTypes", vlmDef.getExtractionTypes());
        }
        if (vlmDef.getStages() != null) {
            pipelineSpec.put("stages", vlmDef.getStages());
        }
        if (vlmDef.getGraphNodes() != null && !vlmDef.getGraphNodes().isEmpty()) {
            pipelineSpec.put("graphNodes", vlmDef.getGraphNodes());
        }
        if (vlmDef.getDefaultParameters() != null) {
            pipelineSpec.put("defaultParameters", vlmDef.getDefaultParameters());
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
}
