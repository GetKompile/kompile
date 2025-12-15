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

import ai.kompile.core.embeddings.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for model management operations.
 * Exposes functionality to list, inspect, and test embedding models.
 */
@Component
public class ModelManagementTool {

    private static final Logger logger = LoggerFactory.getLogger(ModelManagementTool.class);

    private final List<EmbeddingModel> embeddingModels;

    @Autowired
    public ModelManagementTool(List<EmbeddingModel> embeddingModels) {
        this.embeddingModels = embeddingModels != null ? embeddingModels : Collections.emptyList();
        logger.info("ModelManagementTool initialized with {} embedding models", this.embeddingModels.size());
    }

    // Input records for tools
    public record ListModelsInput(String modelType) {}
    public record TestEmbeddingInput(String text, String modelName) {}
    public record GetModelInfoInput(String modelName) {}

    /**
     * Lists all available models in the system.
     */
    @Tool(name = "list_models",
            description = "Lists all available models in the system including embedding models, language models, and ML models. Optionally filter by modelType: 'embedding', 'language', or 'all' (default).")
    public Map<String, Object> listModels(ListModelsInput input) {
        logger.info("Listing models with filter: {}", input.modelType());

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            String filter = input.modelType() != null ? input.modelType().toLowerCase() : "all";

            if ("all".equals(filter) || "embedding".equals(filter)) {
                List<Map<String, Object>> embeddingModelList = new ArrayList<>();
                for (EmbeddingModel model : embeddingModels) {
                    Map<String, Object> modelInfo = new LinkedHashMap<>();
                    modelInfo.put("name", model.getModelName());
                    modelInfo.put("dimensions", model.getDimensions());
                    modelInfo.put("type", "embedding");
                    modelInfo.put("class", model.getClass().getSimpleName());
                    embeddingModelList.add(modelInfo);
                }
                result.put("embeddingModels", embeddingModelList);
                result.put("embeddingModelCount", embeddingModelList.size());
            }

            result.put("status", "success");
            result.put("filter", filter);

            return result;

        } catch (Exception e) {
            logger.error("Error listing models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to list models: " + e.getMessage());
        }
    }

    /**
     * Tests an embedding model by generating embeddings for sample text.
     */
    @Tool(name = "test_embedding",
            description = "Tests an embedding model by generating embeddings for the provided text. Returns the embedding dimensions and execution time. If modelName is not specified, uses the first available embedding model.")
    public Map<String, Object> testEmbedding(TestEmbeddingInput input) {
        logger.info("Testing embedding with text length: {}, model: {}",
                input.text() != null ? input.text().length() : 0, input.modelName());

        if (input.text() == null || input.text().trim().isEmpty()) {
            return Map.of("status", "error", "error", "Text cannot be empty");
        }

        if (embeddingModels.isEmpty()) {
            return Map.of("status", "error", "error", "No embedding models available");
        }

        try {
            EmbeddingModel selectedModel = null;

            if (input.modelName() != null && !input.modelName().isEmpty()) {
                for (EmbeddingModel model : embeddingModels) {
                    if (model.getModelName().equalsIgnoreCase(input.modelName())) {
                        selectedModel = model;
                        break;
                    }
                }
                if (selectedModel == null) {
                    return Map.of("status", "error", "error", "Model not found: " + input.modelName(),
                            "availableModels", embeddingModels.stream().map(EmbeddingModel::getModelName).toList());
                }
            } else {
                selectedModel = embeddingModels.get(0);
            }

            long startTime = System.currentTimeMillis();
            var embedding = selectedModel.embed(input.text());
            long endTime = System.currentTimeMillis();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("modelName", selectedModel.getModelName());
            result.put("inputTextLength", input.text().length());
            result.put("embeddingDimensions", embedding != null ? embedding.length() : 0);
            result.put("executionTimeMs", endTime - startTime);

            // Include first few embedding values as sample
            if (embedding != null && embedding.length() > 0) {
                double[] sample = new double[Math.min(5, (int) embedding.length())];
                for (int i = 0; i < sample.length; i++) {
                    sample[i] = embedding.getDouble(i);
                }
                result.put("embeddingSample", sample);
            }

            return result;

        } catch (Exception e) {
            logger.error("Error testing embedding: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to test embedding: " + e.getMessage());
        }
    }

    /**
     * Gets detailed information about a specific model.
     */
    @Tool(name = "get_model_info",
            description = "Gets detailed information about a specific model by name. Returns model configuration, dimensions, and capabilities.")
    public Map<String, Object> getModelInfo(GetModelInfoInput input) {
        logger.info("Getting info for model: {}", input.modelName());

        if (input.modelName() == null || input.modelName().isEmpty()) {
            return Map.of("status", "error", "error", "Model name is required",
                    "availableModels", embeddingModels.stream().map(EmbeddingModel::getModelName).toList());
        }

        try {
            for (EmbeddingModel model : embeddingModels) {
                if (model.getModelName().equalsIgnoreCase(input.modelName())) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "success");
                    result.put("name", model.getModelName());
                    result.put("dimensions", model.getDimensions());
                    result.put("type", "embedding");
                    result.put("implementationClass", model.getClass().getName());
                    result.put("simpleClassName", model.getClass().getSimpleName());

                    return result;
                }
            }

            return Map.of("status", "error", "error", "Model not found: " + input.modelName(),
                    "availableModels", embeddingModels.stream().map(EmbeddingModel::getModelName).toList());

        } catch (Exception e) {
            logger.error("Error getting model info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get model info: " + e.getMessage());
        }
    }
}
