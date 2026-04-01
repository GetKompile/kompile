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

import ai.kompile.app.web.controllers.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for RAG configuration management.
 * Exposes contextual RAG, query transformer, guardrails, processing settings, and retriever configuration.
 */
@Component
public class RagConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(RagConfigTool.class);

    private final ContextualRagConfigController contextualRagController;
    private final QueryTransformerConfigController queryTransformerController;
    private final GuardrailsConfigController guardrailsController;
    private final ProcessingSettingsController processingSettingsController;
    private final RetrieverController retrieverController;

    @Autowired
    public RagConfigTool(
            @Autowired(required = false) ContextualRagConfigController contextualRagController,
            @Autowired(required = false) QueryTransformerConfigController queryTransformerController,
            @Autowired(required = false) GuardrailsConfigController guardrailsController,
            @Autowired(required = false) ProcessingSettingsController processingSettingsController,
            @Autowired(required = false) RetrieverController retrieverController) {
        this.contextualRagController = contextualRagController;
        this.queryTransformerController = queryTransformerController;
        this.guardrailsController = guardrailsController;
        this.processingSettingsController = processingSettingsController;
        this.retrieverController = retrieverController;
    }

    // Input records
    public record GetContextualRagConfigInput() {}
    public record UpdateContextualRagConfigInput(Boolean enabled, String presetName) {}
    public record GetContextualRagStatusInput() {}
    public record GetContextualRagPresetsInput() {}
    public record ApplyContextualRagPresetInput(String presetName) {}
    public record ToggleContextualRagInput(Boolean enabled) {}
    public record ClearContextualRagCacheInput() {}
    public record GetContextualRagCacheStatsInput() {}

    public record GetQueryTransformerConfigInput() {}
    public record SetQueryTransformerTypeInput(String type) {}
    public record GetQueryTransformerTypesInput() {}
    public record ApplyQueryTransformerPresetInput(String preset) {}
    public record GetQueryTransformerPresetsInput() {}
    public record ToggleQueryTransformerInput(Boolean enabled) {}

    public record GetGuardrailsConfigInput() {}
    public record UpdateGuardrailsConfigInput(Boolean enabled, Integer maxRetries) {}
    public record GetAvailableGuardrailsInput() {}
    public record ToggleGuardrailsInput(Boolean enabled) {}

    public record GetProcessingSettingsInput() {}
    public record GetProcessingMemoryStatusInput() {}
    public record GetProcessingJobStatsInput() {}
    public record GetPipelineConfigInput() {}
    public record GetPipelinePresetsInput() {}
    public record ApplyPipelinePresetInput(String preset) {}
    public record GetAdaptiveStatusInput() {}
    public record ApplyAdaptivePresetInput(String preset) {}
    public record StartAdaptiveMonitoringInput() {}
    public record StopAdaptiveMonitoringInput() {}

    public record SearchDocumentsRetrieverInput(String query, Integer maxResults) {}

    // === Contextual RAG Config ===

    @Tool(name = "get_contextual_rag_config",
            description = "Gets the current contextual RAG configuration including enrichment settings, source attribution, and LLM provider settings.")
    public Map<String, Object> getContextualRagConfig(GetContextualRagConfigInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            ResponseEntity<?> response = contextualRagController.getConfiguration();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting contextual RAG config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_contextual_rag_status",
            description = "Gets the current contextual RAG status including whether enrichment is active.")
    public Map<String, Object> getContextualRagStatus(GetContextualRagStatusInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            ResponseEntity<?> response = contextualRagController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting contextual RAG status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_contextual_rag_presets",
            description = "Gets available contextual RAG configuration presets (disabled, minimal, fast, balanced, quality).")
    public Map<String, Object> getContextualRagPresets(GetContextualRagPresetsInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            ResponseEntity<?> response = contextualRagController.getPresets();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting contextual RAG presets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "apply_contextual_rag_preset",
            description = "Applies a contextual RAG configuration preset. Available presets: disabled, minimal, fast, balanced, quality.")
    public Map<String, Object> applyContextualRagPreset(ApplyContextualRagPresetInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            if (input.presetName() == null) return Map.of("status", "error", "error", "Preset name is required");
            ResponseEntity<?> response = contextualRagController.applyPreset(input.presetName());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error applying contextual RAG preset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_contextual_rag",
            description = "Enables or disables contextual chunk enrichment.")
    public Map<String, Object> toggleContextualRag(ToggleContextualRagInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            Map<String, Boolean> request = Map.of("enabled", input.enabled() != null ? input.enabled() : true);
            ResponseEntity<?> response = contextualRagController.toggleEnabled(request);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error toggling contextual RAG: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "clear_contextual_rag_cache",
            description = "Clears contextual enrichment caches.")
    public Map<String, Object> clearContextualRagCache(ClearContextualRagCacheInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            ResponseEntity<?> response = contextualRagController.clearCaches();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error clearing contextual RAG cache: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_contextual_rag_cache_stats",
            description = "Gets contextual enrichment cache statistics.")
    public Map<String, Object> getContextualRagCacheStats(GetContextualRagCacheStatsInput input) {
        try {
            if (contextualRagController == null) return Map.of("status", "error", "error", "Contextual RAG config not available");
            ResponseEntity<?> response = contextualRagController.getCacheStats();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting contextual RAG cache stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Query Transformer Config ===

    @Tool(name = "get_query_transformer_config",
            description = "Gets the current query transformer configuration (type, maxQueries, includeOriginal, temperature, maxTokens).")
    public Map<String, Object> getQueryTransformerConfig(GetQueryTransformerConfigInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            ResponseEntity<?> response = queryTransformerController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting query transformer config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "set_query_transformer_type",
            description = "Sets the query transformer type. Available: passthrough, compression, expansion, hyde, step-back, multi-query.")
    public Map<String, Object> setQueryTransformerType(SetQueryTransformerTypeInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            if (input.type() == null) return Map.of("status", "error", "error", "Type is required");
            ResponseEntity<?> response = queryTransformerController.setTransformerType(input.type());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error setting query transformer type: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_query_transformer_types",
            description = "Gets available query transformer types with descriptions.")
    public Map<String, Object> getQueryTransformerTypes(GetQueryTransformerTypesInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            ResponseEntity<?> response = queryTransformerController.getTransformerTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting query transformer types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "apply_query_transformer_preset",
            description = "Applies a query transformer preset. Available: simple, conversational, comprehensive, precise, hyde.")
    public Map<String, Object> applyQueryTransformerPreset(ApplyQueryTransformerPresetInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            if (input.preset() == null) return Map.of("status", "error", "error", "Preset name is required");
            ResponseEntity<?> response = queryTransformerController.applyPreset(input.preset());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error applying query transformer preset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_query_transformer_presets",
            description = "Gets available query transformer presets with descriptions.")
    public Map<String, Object> getQueryTransformerPresets(GetQueryTransformerPresetsInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            ResponseEntity<?> response = queryTransformerController.getPresets();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting query transformer presets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_query_transformer",
            description = "Enables or disables the query transformer.")
    public Map<String, Object> toggleQueryTransformer(ToggleQueryTransformerInput input) {
        try {
            if (queryTransformerController == null) return Map.of("status", "error", "error", "Query transformer config not available");
            Map<String, Boolean> request = Map.of("enabled", input.enabled() != null ? input.enabled() : true);
            ResponseEntity<?> response = queryTransformerController.toggleQueryTransformer(request);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error toggling query transformer: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Guardrails Config ===

    @Tool(name = "get_guardrails_config",
            description = "Gets current guardrails configuration including input/output guardrails and thresholds.")
    public Map<String, Object> getGuardrailsConfig(GetGuardrailsConfigInput input) {
        try {
            if (guardrailsController == null) return Map.of("status", "error", "error", "Guardrails config not available");
            ResponseEntity<?> response = guardrailsController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting guardrails config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_available_guardrails",
            description = "Lists available input and output guardrails with metadata.")
    public Map<String, Object> getAvailableGuardrails(GetAvailableGuardrailsInput input) {
        try {
            if (guardrailsController == null) return Map.of("status", "error", "error", "Guardrails config not available");
            ResponseEntity<?> response = guardrailsController.getAvailableGuardrails();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting available guardrails: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_guardrails",
            description = "Enables or disables guardrails.")
    public Map<String, Object> toggleGuardrails(ToggleGuardrailsInput input) {
        try {
            if (guardrailsController == null) return Map.of("status", "error", "error", "Guardrails config not available");
            Map<String, Boolean> request = Map.of("enabled", input.enabled() != null ? input.enabled() : true);
            ResponseEntity<?> response = guardrailsController.toggleGuardrails(request);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error toggling guardrails: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Processing Settings ===

    @Tool(name = "get_processing_settings",
            description = "Gets current processing settings and system status including max concurrent jobs, index batch size, and memory.")
    public Map<String, Object> getProcessingSettings(GetProcessingSettingsInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getSettings();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting processing settings: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_processing_memory_status",
            description = "Gets current memory status for document processing.")
    public Map<String, Object> getProcessingMemoryStatus(GetProcessingMemoryStatusInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getMemoryStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting processing memory status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_processing_job_stats",
            description = "Gets active processing job statistics.")
    public Map<String, Object> getProcessingJobStats(GetProcessingJobStatsInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getJobStats();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting processing job stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_pipeline_config",
            description = "Gets adaptive pipeline configuration.")
    public Map<String, Object> getPipelineConfig(GetPipelineConfigInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getPipelineConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting pipeline config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_pipeline_presets",
            description = "Gets available pipeline presets (adaptive, memoryOptimized, highThroughput).")
    public Map<String, Object> getPipelinePresets(GetPipelinePresetsInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getPipelinePresets();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting pipeline presets: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "apply_processing_pipeline_preset",
            description = "Applies a pipeline preset. Available: defaults, highThroughput, lowMemory, keywordOnly.")
    public Map<String, Object> applyPipelinePreset(ApplyPipelinePresetInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            if (input.preset() == null) return Map.of("status", "error", "error", "Preset name is required");
            ResponseEntity<?> response = processingSettingsController.applyPipelinePreset(input.preset());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error applying pipeline preset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_adaptive_performance_status",
            description = "Gets current adaptive performance configuration and status.")
    public Map<String, Object> getAdaptiveStatus(GetAdaptiveStatusInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.getAdaptiveStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting adaptive status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "apply_adaptive_preset",
            description = "Applies an adaptive performance preset. Available: conservative, balanced, aggressive.")
    public Map<String, Object> applyAdaptivePreset(ApplyAdaptivePresetInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            if (input.preset() == null) return Map.of("status", "error", "error", "Preset name is required");
            ResponseEntity<?> response = processingSettingsController.applyAdaptivePreset(input.preset());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error applying adaptive preset: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "start_adaptive_monitoring",
            description = "Starts adaptive performance monitoring.")
    public Map<String, Object> startAdaptiveMonitoring(StartAdaptiveMonitoringInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.startAdaptiveMonitoring();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error starting adaptive monitoring: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "stop_adaptive_monitoring",
            description = "Stops adaptive performance monitoring.")
    public Map<String, Object> stopAdaptiveMonitoring(StopAdaptiveMonitoringInput input) {
        try {
            if (processingSettingsController == null) return Map.of("status", "error", "error", "Processing settings not available");
            ResponseEntity<?> response = processingSettingsController.stopAdaptiveMonitoring();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error stopping adaptive monitoring: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Retriever ===

    @Tool(name = "search_documents_retriever",
            description = "Searches documents using the configured document retriever. Returns matching documents with similarity scores.")
    public Map<String, Object> searchDocumentsRetriever(SearchDocumentsRetrieverInput input) {
        try {
            if (retrieverController == null) return Map.of("status", "error", "error", "Retriever not available");
            if (input.query() == null) return Map.of("status", "error", "error", "Query is required");
            int maxResults = input.maxResults() != null ? input.maxResults() : 5;
            ResponseEntity<?> response = retrieverController.searchDocuments(input.query(), maxResults);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error searching documents: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
