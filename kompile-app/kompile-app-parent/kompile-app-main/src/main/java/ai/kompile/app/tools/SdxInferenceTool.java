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

import ai.kompile.app.services.sdx.SdxServingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SdxInferenceTool {

    private static final Logger logger = LoggerFactory.getLogger(SdxInferenceTool.class);

    private final SdxServingService sdxServingService;

    @Autowired
    public SdxInferenceTool(@Autowired(required = false) SdxServingService sdxServingService) {
        this.sdxServingService = sdxServingService;
        logger.info("SdxInferenceTool initialized");
    }

    public record ListSdxModelsInput() {}
    public record LoadSdxModelInput(String modelId) {}
    public record UnloadSdxModelInput(String modelId) {}
    public record GetSdxSchemaInput(String modelId) {}
    public record RunSdxInferenceInput(String modelId, Map<String, Object> inputData) {}

    @Tool(name = "list_sdx_models",
            description = "Lists all available SDX/SameDiff model files (.sdz, .fb, .zip) found in the model cache directory.")
    public Map<String, Object> listModels(ListSdxModelsInput input) {
        try {
            if (sdxServingService == null) return Map.of("status", "error", "error", "SdxServingService not available");
            var models = sdxServingService.listAvailableModels();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", models.size());
            result.put("models", models);
            return result;
        } catch (Exception e) {
            logger.error("Error listing SDX models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "load_sdx_model",
            description = "Loads an SDX model for inference by its model ID. The model must exist in the model cache.")
    public Map<String, Object> loadModel(LoadSdxModelInput input) {
        try {
            if (sdxServingService == null) return Map.of("status", "error", "error", "SdxServingService not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            return sdxServingService.loadModel(input.modelId());
        } catch (Exception e) {
            logger.error("Error loading SDX model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "unload_sdx_model",
            description = "Unloads a previously loaded SDX model to free resources.")
    public Map<String, Object> unloadModel(UnloadSdxModelInput input) {
        try {
            if (sdxServingService == null) return Map.of("status", "error", "error", "SdxServingService not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            boolean unloaded = sdxServingService.unloadModel(input.modelId());
            return Map.of("status", "success", "modelId", input.modelId(), "unloaded", unloaded);
        } catch (Exception e) {
            logger.error("Error unloading SDX model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_sdx_model_schema",
            description = "Gets the input/output schema for a loaded SDX model, showing expected data shapes and types.")
    public Map<String, Object> getSchema(GetSdxSchemaInput input) {
        try {
            if (sdxServingService == null) return Map.of("status", "error", "error", "SdxServingService not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(sdxServingService.getModelSchema(input.modelId()));
            return result;
        } catch (Exception e) {
            logger.error("Error getting SDX model schema: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "run_sdx_inference",
            description = "Runs inference on a loaded SDX model with the provided input data. Returns the model output.")
    public Map<String, Object> runInference(RunSdxInferenceInput input) {
        try {
            if (sdxServingService == null) return Map.of("status", "error", "error", "SdxServingService not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "modelId is required");
            return sdxServingService.infer(input.modelId(), input.inputData());
        } catch (Exception e) {
            logger.error("Error running SDX inference: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
