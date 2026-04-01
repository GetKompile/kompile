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

import ai.kompile.pipeline.management.service.PipelineManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PipelineTool {

    private static final Logger logger = LoggerFactory.getLogger(PipelineTool.class);

    private final PipelineManagementService pipelineManagementService;

    @Autowired
    public PipelineTool(@Autowired(required = false) PipelineManagementService pipelineManagementService) {
        this.pipelineManagementService = pipelineManagementService;
        logger.info("PipelineTool initialized");
    }

    public record ListPipelinesInput() {}
    public record GetPipelineInput(String id) {}
    public record DeletePipelineInput(String id) {}
    public record ExecutePipelineInput(String id, Map<String, Object> inputData) {}
    public record GetAsyncResultInput(String executionId) {}

    @Tool(name = "list_pipelines",
            description = "Lists all registered pipelines with their IDs, names, and status.")
    public Map<String, Object> listPipelines(ListPipelinesInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            var pipelines = pipelineManagementService.listAll();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", pipelines.size());
            result.put("pipelines", pipelines);
            return result;
        } catch (Exception e) {
            logger.error("Error listing pipelines: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_pipeline",
            description = "Gets a specific pipeline configuration by its ID.")
    public Map<String, Object> getPipeline(GetPipelineInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var pipeline = pipelineManagementService.get(input.id());
            if (pipeline == null) return Map.of("status", "error", "error", "Pipeline not found: " + input.id());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("pipeline", pipeline);
            return result;
        } catch (Exception e) {
            logger.error("Error getting pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_pipeline",
            description = "Deletes a pipeline by its ID.")
    public Map<String, Object> deletePipeline(DeletePipelineInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            boolean deleted = pipelineManagementService.delete(input.id());
            if (!deleted) return Map.of("status", "error", "error", "Pipeline not found: " + input.id());
            return Map.of("status", "success", "message", "Pipeline deleted", "id", input.id());
        } catch (Exception e) {
            logger.error("Error deleting pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "execute_pipeline",
            description = "Executes a pipeline synchronously with optional input data. Returns the execution result.")
    public Map<String, Object> executePipeline(ExecutePipelineInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            var execResult = pipelineManagementService.executeSync(input.id(),
                    input.inputData() != null ? input.inputData() : Collections.emptyMap());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("executionResult", execResult);
            return result;
        } catch (Exception e) {
            logger.error("Error executing pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "execute_pipeline_async",
            description = "Executes a pipeline asynchronously. Returns an executionId to check results later.")
    public Map<String, Object> executePipelineAsync(ExecutePipelineInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            if (input.id() == null) return Map.of("status", "error", "error", "id is required");
            String executionId = pipelineManagementService.executeAsync(input.id(),
                    input.inputData() != null ? input.inputData() : Collections.emptyMap());
            return Map.of("status", "success", "executionId", executionId, "message", "Pipeline execution started");
        } catch (Exception e) {
            logger.error("Error executing pipeline async: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_pipeline_async_result",
            description = "Gets the result of an asynchronous pipeline execution by executionId.")
    public Map<String, Object> getAsyncResult(GetAsyncResultInput input) {
        try {
            if (pipelineManagementService == null) return Map.of("status", "error", "error", "PipelineManagementService not available");
            if (input.executionId() == null) return Map.of("status", "error", "error", "executionId is required");
            var execResult = pipelineManagementService.getAsyncResult(input.executionId());
            if (execResult == null) return Map.of("status", "error", "error", "No result found for: " + input.executionId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("executionResult", execResult);
            return result;
        } catch (Exception e) {
            logger.error("Error getting async result: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
