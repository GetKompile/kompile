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

package ai.kompile.app.tools;

import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class VlmConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(VlmConfigTool.class);

    public VlmConfigTool() {
        logger.info("VlmConfigTool initialized");
    }

    private VlmPipelineRegistry getRegistry() {
        try {
            return VlmPipelineRegistry.getInstance();
        } catch (Exception e) {
            return null;
        }
    }

    public record ListVlmPipelinesInput() {}
    public record GetVlmPipelineInput(String pipelineId) {}
    public record ListVlmStagesInput() {}
    public record RemoveVlmPipelineInput(String pipelineId) {}

    @Tool(name = "list_vlm_pipelines",
            description = "Lists all registered VLM (Vision Language Model) pipelines including built-in and custom ones.")
    public Map<String, Object> listVlmPipelines(ListVlmPipelinesInput input) {
        try {
            var registry = getRegistry();
            if (registry == null) return Map.of("status", "error", "error", "VlmPipelineRegistry not available");
            var pipelines = registry.getAllPipelines();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", pipelines.size());
            result.put("pipelines", pipelines.stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getPipelineId());
                m.put("name", p.getDisplayName());
                m.put("description", p.getDescription());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing VLM pipelines: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_pipeline",
            description = "Gets a specific VLM pipeline configuration by its pipeline ID.")
    public Map<String, Object> getVlmPipeline(GetVlmPipelineInput input) {
        try {
            var registry = getRegistry();
            if (registry == null) return Map.of("status", "error", "error", "VlmPipelineRegistry not available");
            if (input.pipelineId() == null) return Map.of("status", "error", "error", "pipelineId is required");
            var pipeline = registry.getPipeline(input.pipelineId());
            if (pipeline.isEmpty()) return Map.of("status", "error", "error", "Pipeline not found: " + input.pipelineId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("pipeline", pipeline.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting VLM pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_vlm_stages",
            description = "Lists all available VLM processing stages that can be used in pipelines.")
    public Map<String, Object> listVlmStages(ListVlmStagesInput input) {
        try {
            var registry = getRegistry();
            if (registry == null) return Map.of("status", "error", "error", "VlmPipelineRegistry not available");
            var stages = registry.getAllStages();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", stages.size());
            result.put("stages", stages);
            return result;
        } catch (Exception e) {
            logger.error("Error listing VLM stages: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "remove_vlm_pipeline",
            description = "Removes a custom VLM pipeline by its pipeline ID. Built-in pipelines cannot be removed.")
    public Map<String, Object> removeVlmPipeline(RemoveVlmPipelineInput input) {
        try {
            var registry = getRegistry();
            if (registry == null) return Map.of("status", "error", "error", "VlmPipelineRegistry not available");
            if (input.pipelineId() == null) return Map.of("status", "error", "error", "pipelineId is required");
            boolean removed = registry.deletePipeline(input.pipelineId());
            if (!removed) return Map.of("status", "error", "error", "Pipeline not found or is built-in: " + input.pipelineId());
            return Map.of("status", "success", "message", "Pipeline removed", "pipelineId", input.pipelineId());
        } catch (Exception e) {
            logger.error("Error removing VLM pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
