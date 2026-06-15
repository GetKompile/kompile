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

import ai.kompile.app.web.controllers.ModelRegistryController;
import ai.kompile.app.web.controllers.VlmModelController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for model registry and VLM model management.
 * Exposes model listing, activation, and VLM model set operations.
 */
@Component
public class ModelRegistryTool {

    private static final Logger logger = LoggerFactory.getLogger(ModelRegistryTool.class);

    private final ModelRegistryController modelRegistryController;
    private final VlmModelController vlmModelController;

    @Autowired
    public ModelRegistryTool(
            @Autowired(required = false) ModelRegistryController modelRegistryController,
            @Autowired(required = false) VlmModelController vlmModelController) {
        this.modelRegistryController = modelRegistryController;
        this.vlmModelController = vlmModelController;
    }

    // Input records
    public record GetRegistryInput() {}
    public record GetRegistryModelInput(String modelId) {}
    public record GetModelsByTypeInput(String type) {}
    public record GetModelsByRoleInput(String role) {}
    public record GetModelSourceStatusInput() {}
    public record GetStagingStatusInput() {}
    public record GetVersionInfoInput() {}
    public record GetBuiltInCatalogInput() {}
    public record GetBuiltInModelInput(String modelId) {}
    public record GetDenseEncodersInput() {}
    public record GetSparseEncodersInput() {}
    public record GetCrossEncodersInput() {}
    public record RefreshRegistryInput() {}
    public record GetEmbeddingModelStatusInput() {}
    public record GetAvailableEmbeddingModelsInput() {}
    public record ReloadEmbeddingModelInput() {}
    public record SwitchEmbeddingModelInput(String modelId) {}
    public record RefreshAndReloadModelInput() {}

    public record GetVlmModelSetsInput() {}
    public record GetVlmModelSetInput(String setId) {}
    public record GetVlmModelSetsStatusInput() {}
    public record DownloadVlmModelSetInput(String setId) {}
    public record GetVlmDownloadStatusInput(String setId) {}
    public record DeleteVlmModelSetInput(String setId) {}
    public record GetVlmPipelineStagesInput() {}
    public record GetVlmExtractionTypesInput() {}
    public record GetVlmPresetsInput() {}
    public record GetVlmStatusInput() {}

    // === Model Registry ===

    @Tool(name = "get_model_registry",
            description = "Gets the full model registry with all model entries grouped by type.")
    public Map<String, Object> getRegistry(GetRegistryInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getRegistry();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting model registry: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_registry_model",
            description = "Gets detailed information about a specific model in the registry by its model ID.")
    public Map<String, Object> getRegistryModel(GetRegistryModelInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = modelRegistryController.getModel(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting registry model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_registry_models_by_type",
            description = "Gets models filtered by type (e.g. dense_encoder, sparse_encoder, cross_encoder).")
    public Map<String, Object> getModelsByType(GetModelsByTypeInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            if (input.type() == null) return Map.of("status", "error", "error", "Model type is required");
            ResponseEntity<?> response = modelRegistryController.getModelsByType(input.type());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting models by type: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_registry_models_by_role",
            description = "Gets models filtered by RAG role (e.g. encoder, reranker).")
    public Map<String, Object> getModelsByRole(GetModelsByRoleInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            if (input.role() == null) return Map.of("status", "error", "error", "Role is required");
            ResponseEntity<?> response = modelRegistryController.getModelsByRole(input.role());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting models by role: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_model_source_status",
            description = "Gets the status of model sources (staging service connectivity).")
    public Map<String, Object> getModelSourceStatus(GetModelSourceStatusInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getModelSourceStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting model source status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_staging_status",
            description = "Gets the current staging service status.")
    public Map<String, Object> getStagingStatus(GetStagingStatusInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getStagingStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting staging status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_model_version_info",
            description = "Gets version information for the model registry.")
    public Map<String, Object> getVersionInfo(GetVersionInfoInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getVersionInfo();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting version info: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_built_in_catalog",
            description = "Gets the built-in model catalog with all available model types.")
    public Map<String, Object> getBuiltInCatalog(GetBuiltInCatalogInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getBuiltInCatalog();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting built-in catalog: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_built_in_model",
            description = "Gets details for a specific built-in model by ID.")
    public Map<String, Object> getBuiltInModel(GetBuiltInModelInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = modelRegistryController.getBuiltInModel(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting built-in model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_dense_encoders",
            description = "Lists all available dense encoder models.")
    public Map<String, Object> getDenseEncoders(GetDenseEncodersInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getDenseEncodersEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting dense encoders: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_sparse_encoders",
            description = "Lists all available sparse encoder models.")
    public Map<String, Object> getSparseEncoders(GetSparseEncodersInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getSparseEncodersEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting sparse encoders: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_cross_encoders",
            description = "Lists all available cross-encoder models.")
    public Map<String, Object> getCrossEncoders(GetCrossEncodersInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getCrossEncodersEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting cross encoders: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "refresh_model_registry",
            description = "Refreshes the model registry by re-scanning available models.")
    public Map<String, Object> refreshRegistry(RefreshRegistryInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.refreshRegistry();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error refreshing registry: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_embedding_model_status",
            description = "Gets the current embedding model status including active model ID, dimensions, and health.")
    public Map<String, Object> getEmbeddingModelStatus(GetEmbeddingModelStatusInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getEmbeddingModelStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting embedding model status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_available_embedding_models",
            description = "Lists all embedding models available for activation.")
    public Map<String, Object> getAvailableEmbeddingModels(GetAvailableEmbeddingModelsInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.getAvailableEmbeddingModels();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting available embedding models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reload_embedding_model",
            description = "Reloads the currently active embedding model.")
    public Map<String, Object> reloadEmbeddingModel(ReloadEmbeddingModelInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.reloadEmbeddingModel();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error reloading embedding model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "switch_embedding_model",
            description = "Switches to a different embedding model by its model ID. Downloads the model if not cached.")
    public Map<String, Object> switchEmbeddingModel(SwitchEmbeddingModelInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = modelRegistryController.switchEmbeddingModel(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error switching embedding model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "refresh_and_reload_model",
            description = "Refreshes the model registry and reloads the active embedding model.")
    public Map<String, Object> refreshAndReloadModel(RefreshAndReloadModelInput input) {
        try {
            if (modelRegistryController == null) return Map.of("status", "error", "error", "Model registry not available");
            ResponseEntity<?> response = modelRegistryController.refreshRegistryAndReloadModel();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error refreshing and reloading model: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === VLM Models ===

    @Tool(name = "get_vlm_model_sets",
            description = "Gets all available VLM model sets.")
    public Map<String, Object> getVlmModelSets(GetVlmModelSetsInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getModelSets();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM model sets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_model_set",
            description = "Gets a specific VLM model set by its ID.")
    public Map<String, Object> getVlmModelSet(GetVlmModelSetInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmModelController.getModelSet(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM model set: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_model_sets_status",
            description = "Gets cache status for all VLM model sets (which are downloaded, which need downloading).")
    public Map<String, Object> getVlmModelSetsStatus(GetVlmModelSetsStatusInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getModelSetsStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM model sets status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "download_vlm_model_set",
            description = "Starts async download of a VLM model set by its ID.")
    public Map<String, Object> downloadVlmModelSet(DownloadVlmModelSetInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmModelController.downloadModelSet(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error downloading VLM model set: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_download_status",
            description = "Gets the download progress for a VLM model set.")
    public Map<String, Object> getVlmDownloadStatus(GetVlmDownloadStatusInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmModelController.getDownloadStatus(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM download status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_vlm_model_set",
            description = "Deletes a cached VLM model set by its ID.")
    public Map<String, Object> deleteVlmModelSet(DeleteVlmModelSetInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmModelController.deleteModelSet(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting VLM model set: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_pipeline_stages",
            description = "Gets all available VLM pipeline stages with documentation.")
    public Map<String, Object> getVlmPipelineStages(GetVlmPipelineStagesInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getPipelineStages();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM pipeline stages: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_extraction_types",
            description = "Gets all VLM extraction types.")
    public Map<String, Object> getVlmExtractionTypes(GetVlmExtractionTypesInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getExtractionTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM extraction types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_presets",
            description = "Gets available VLM extraction configuration presets.")
    public Map<String, Object> getVlmPresets(GetVlmPresetsInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getPresets();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM presets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_service_status",
            description = "Gets the overall VLM service status.")
    public Map<String, Object> getVlmStatus(GetVlmStatusInput input) {
        try {
            if (vlmModelController == null) return Map.of("status", "error", "error", "VLM model controller not available");
            ResponseEntity<?> response = vlmModelController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
