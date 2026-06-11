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

import ai.kompile.modelmanager.llm.dynamic.LlmPipelineDefinition;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts LLM pipeline definitions from the model-manager registry into
 * unified pipeline definitions that can be executed by the pipeline serving subsystem.
 *
 * <p>The bridge maps LLM-specific fields (stages, generation parameters, model set)
 * into the {@link UnifiedPipelineDefinition} envelope format. The actual framework
 * Pipeline construction from the unified definition happens at execution time
 * in the subprocess.</p>
 */
public class LlmPipelineBuilderBridge {

    private static final Logger log = LoggerFactory.getLogger(LlmPipelineBuilderBridge.class);

    /**
     * Convert an LlmPipelineDefinition into a UnifiedPipelineDefinition.
     *
     * <p>The pipelineSpec is stored as a metadata map describing the LLM pipeline
     * structure. The subprocess will use this to construct the actual framework
     * Pipeline via the SameDiffLLMPipelineBuilder.</p>
     */
    public UnifiedPipelineDefinition toUnified(LlmPipelineDefinition llmDef) {
        UnifiedPipelineDefinition.ExecutionTopology topology =
                llmDef.getPipelineType() == LlmPipelineDefinition.PipelineType.GRAPH ?
                        UnifiedPipelineDefinition.ExecutionTopology.GRAPH :
                        UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE;

        // Build a pipelineSpec that describes the LLM pipeline for the subprocess to construct
        Map<String, Object> pipelineSpec = new LinkedHashMap<>();
        pipelineSpec.put("@bridge", "llm");
        pipelineSpec.put("pipelineId", llmDef.getPipelineId());
        pipelineSpec.put("pipelineType", llmDef.getPipelineType().name());
        pipelineSpec.put("modelSetId", llmDef.getModelSetId());
        if (llmDef.getStages() != null) {
            pipelineSpec.put("stages", llmDef.getStages());
        }
        if (llmDef.getGraphNodes() != null && !llmDef.getGraphNodes().isEmpty()) {
            pipelineSpec.put("graphNodes", llmDef.getGraphNodes());
        }
        if (llmDef.getDefaultParameters() != null) {
            pipelineSpec.put("defaultParameters", llmDef.getDefaultParameters());
        }

        return UnifiedPipelineDefinition.builder()
                .pipelineId(llmDef.getPipelineId())
                .displayName(llmDef.getDisplayName())
                .description(llmDef.getDescription())
                .kind(UnifiedPipelineDefinition.PipelineKind.LLM)
                .topology(topology)
                .pipelineSpec(pipelineSpec)
                .modelSetId(llmDef.getModelSetId())
                .llmConfig(llmDef.getDefaultParameters())
                .builtin(llmDef.isBuiltin())
                .enabled(llmDef.isEnabled())
                .createdAt(llmDef.getCreatedAt() != null ? llmDef.getCreatedAt() : Instant.now().toString())
                .updatedAt(llmDef.getUpdatedAt() != null ? llmDef.getUpdatedAt() : Instant.now().toString())
                .serving(UnifiedPipelineDefinition.ServingConfig.builder()
                        .heapSize("16g")
                        .build())
                .build();
    }
}
