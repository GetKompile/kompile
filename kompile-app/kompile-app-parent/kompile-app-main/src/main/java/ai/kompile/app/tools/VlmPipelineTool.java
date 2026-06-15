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

import ai.kompile.app.web.controllers.VlmPipelineConfigController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for VLM pipeline configuration management.
 * Exposes pipeline CRUD, stage management, model set management, and cache operations.
 */
@Component
public class VlmPipelineTool {

    private static final Logger logger = LoggerFactory.getLogger(VlmPipelineTool.class);

    private final VlmPipelineConfigController vlmPipelineConfigController;

    @Autowired
    public VlmPipelineTool(@Autowired(required = false) VlmPipelineConfigController vlmPipelineConfigController) {
        this.vlmPipelineConfigController = vlmPipelineConfigController;
    }

    // Input records
    public record ListVlmPipelinesInput(Boolean customOnly) {}
    public record GetVlmPipelineInput(String pipelineId) {}
    public record DeleteVlmPipelineInput(String pipelineId) {}
    public record ListVlmStagesInput(Boolean customOnly) {}
    public record GetVlmStageInput(String stageId) {}
    public record DeleteVlmStageInput(String stageId) {}
    public record ListVlmConfigModelSetsInput(Boolean customOnly) {}
    public record GetVlmConfigModelSetInput(String setId) {}
    public record DeleteVlmConfigModelSetInput(String setId) {}
    public record GetVlmConfigStatsInput() {}
    public record ClearVlmCustomConfigsInput() {}
    public record GetVlmCacheStatsInput() {}
    public record ListVlmCacheEntriesInput(Integer offset, Integer limit, String type) {}
    public record GetVlmCacheEntryInput(String cacheKey) {}
    public record InvalidateVlmCacheByContentInput(String contentHash) {}
    public record InvalidateVlmCacheByPipelineInput(String pipelineId) {}
    public record EvictExpiredVlmCacheInput() {}
    public record ClearVlmCacheInput() {}

    // === Pipelines ===

    @Tool(name = "list_vlm_pipelines",
            description = "Lists all VLM pipeline configurations. Set customOnly=true to show only user-created pipelines.")
    public Map<String, Object> listPipelines(ListVlmPipelinesInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            boolean customOnly = input.customOnly() != null ? input.customOnly() : false;
            ResponseEntity<?> response = vlmPipelineConfigController.listPipelines(customOnly);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing VLM pipelines: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_pipeline",
            description = "Gets a specific VLM pipeline configuration by its ID.")
    public Map<String, Object> getPipeline(GetVlmPipelineInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.pipelineId() == null) return Map.of("status", "error", "error", "Pipeline ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.getPipeline(input.pipelineId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_vlm_pipeline",
            description = "Deletes a custom VLM pipeline configuration by its ID.")
    public Map<String, Object> deletePipeline(DeleteVlmPipelineInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.pipelineId() == null) return Map.of("status", "error", "error", "Pipeline ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.deletePipeline(input.pipelineId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting VLM pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Stages ===

    @Tool(name = "list_vlm_stages",
            description = "Lists all VLM pipeline stages. Set customOnly=true to show only user-created stages.")
    public Map<String, Object> listStages(ListVlmStagesInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            boolean customOnly = input.customOnly() != null ? input.customOnly() : false;
            ResponseEntity<?> response = vlmPipelineConfigController.listStages(customOnly);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing VLM stages: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_stage",
            description = "Gets a specific VLM pipeline stage configuration by its ID.")
    public Map<String, Object> getStage(GetVlmStageInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.stageId() == null) return Map.of("status", "error", "error", "Stage ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.getStage(input.stageId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM stage: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_vlm_stage",
            description = "Deletes a custom VLM pipeline stage by its ID.")
    public Map<String, Object> deleteStage(DeleteVlmStageInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.stageId() == null) return Map.of("status", "error", "error", "Stage ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.deleteStage(input.stageId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting VLM stage: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Model Sets ===

    @Tool(name = "list_vlm_config_model_sets",
            description = "Lists all VLM model set configurations. Set customOnly=true to show only user-created model sets.")
    public Map<String, Object> listModelSets(ListVlmConfigModelSetsInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            boolean customOnly = input.customOnly() != null ? input.customOnly() : false;
            ResponseEntity<?> response = vlmPipelineConfigController.listModelSets(customOnly);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing VLM config model sets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_config_model_set",
            description = "Gets a specific VLM model set configuration by its ID.")
    public Map<String, Object> getModelSet(GetVlmConfigModelSetInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.getModelSet(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM config model set: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_vlm_config_model_set",
            description = "Deletes a custom VLM model set configuration by its ID.")
    public Map<String, Object> deleteModelSet(DeleteVlmConfigModelSetInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.setId() == null) return Map.of("status", "error", "error", "Model set ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.deleteModelSet(input.setId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting VLM config model set: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Stats & Config ===

    @Tool(name = "get_vlm_config_stats",
            description = "Gets VLM configuration statistics including pipeline/stage/model set counts.")
    public Map<String, Object> getStats(GetVlmConfigStatsInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            ResponseEntity<?> response = vlmPipelineConfigController.getStats();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM config stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "clear_vlm_custom_configs",
            description = "Clears all custom VLM configurations (pipelines, stages, model sets). Built-in configs are preserved.")
    public Map<String, Object> clearCustomConfigs(ClearVlmCustomConfigsInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            ResponseEntity<?> response = vlmPipelineConfigController.clearCustomConfigs();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error clearing VLM custom configs: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Cache Management ===

    @Tool(name = "get_vlm_cache_stats",
            description = "Gets VLM pipeline output cache statistics including hit rate, entry count, and size.")
    public Map<String, Object> getCacheStats(GetVlmCacheStatsInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            ResponseEntity<?> response = vlmPipelineConfigController.getCacheStats();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM cache stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_vlm_cache_entries",
            description = "Lists VLM cache entries with pagination. Optionally filter by type.")
    public Map<String, Object> listCacheEntries(ListVlmCacheEntriesInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            int offset = input.offset() != null ? input.offset() : 0;
            int limit = input.limit() != null ? input.limit() : 50;
            ResponseEntity<?> response = vlmPipelineConfigController.listCacheEntries(offset, limit, input.type());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing VLM cache entries: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vlm_cache_entry",
            description = "Gets a specific VLM cache entry by its cache key.")
    public Map<String, Object> getCacheEntry(GetVlmCacheEntryInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.cacheKey() == null) return Map.of("status", "error", "error", "Cache key is required");
            ResponseEntity<?> response = vlmPipelineConfigController.getCacheEntry(input.cacheKey());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM cache entry: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "invalidate_vlm_cache_by_content",
            description = "Invalidates VLM cache entries by content hash.")
    public Map<String, Object> invalidateCacheByContent(InvalidateVlmCacheByContentInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.contentHash() == null) return Map.of("status", "error", "error", "Content hash is required");
            ResponseEntity<?> response = vlmPipelineConfigController.invalidateCacheByContent(input.contentHash());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error invalidating VLM cache by content: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "invalidate_vlm_cache_by_pipeline",
            description = "Invalidates all VLM cache entries for a specific pipeline.")
    public Map<String, Object> invalidateCacheByPipeline(InvalidateVlmCacheByPipelineInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            if (input.pipelineId() == null) return Map.of("status", "error", "error", "Pipeline ID is required");
            ResponseEntity<?> response = vlmPipelineConfigController.invalidateCacheByPipeline(input.pipelineId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error invalidating VLM cache by pipeline: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "evict_expired_vlm_cache",
            description = "Evicts expired entries from the VLM pipeline output cache.")
    public Map<String, Object> evictExpiredCache(EvictExpiredVlmCacheInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            ResponseEntity<?> response = vlmPipelineConfigController.evictExpiredCache();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error evicting expired VLM cache: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "clear_vlm_cache",
            description = "Clears the entire VLM pipeline output cache.")
    public Map<String, Object> clearCache(ClearVlmCacheInput input) {
        try {
            if (vlmPipelineConfigController == null) return Map.of("status", "error", "error", "VLM pipeline config not available");
            ResponseEntity<?> response = vlmPipelineConfigController.clearCache();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error clearing VLM cache: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
