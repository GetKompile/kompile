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
 * MCP Tool for system configuration management.
 * Exposes environment config, log config, batch size config, and service state.
 */
@Component
public class SystemConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(SystemConfigTool.class);

    private final EnvironmentConfigController environmentConfigController;
    private final LogConfigController logConfigController;
    private final BatchSizeConfigController batchSizeConfigController;
    private final ServiceStateController serviceStateController;

    @Autowired
    public SystemConfigTool(
            @Autowired(required = false) EnvironmentConfigController environmentConfigController,
            @Autowired(required = false) LogConfigController logConfigController,
            @Autowired(required = false) BatchSizeConfigController batchSizeConfigController,
            @Autowired(required = false) ServiceStateController serviceStateController) {
        this.environmentConfigController = environmentConfigController;
        this.logConfigController = logConfigController;
        this.batchSizeConfigController = batchSizeConfigController;
        this.serviceStateController = serviceStateController;
    }

    // Input records
    public record GetEnvironmentStatusInput() {}

    public record GetLogConfigInput() {}
    public record GetLogStatusInput() {}
    public record EnableJobLoggingInput() {}
    public record DisableJobLoggingInput() {}
    public record TriggerLogCleanupInput(Integer hoursToKeep) {}
    public record ListLogArchivesInput() {}
    public record CreateLogArchiveInput() {}

    public record GetBatchSizeModelsInput() {}
    public record GetBatchSizeModelConfigInput(String modelId) {}
    public record GetGlobalBatchConfigInput() {}
    public record ResetBatchSizeModelConfigInput(String modelId) {}
    public record ResetGlobalBatchConfigInput() {}
    public record GetBatchSizeTimeoutConfigInput() {}
    public record ResetBatchSizeTimeoutConfigInput() {}

    public record GetServiceStateInput() {}
    public record GetEmbeddingStateInput() {}
    public record GetVectorStoreStateInput() {}
    public record GetRerankerStateInput() {}
    public record GetDocumentRetrieverStateInput() {}
    public record GetServiceIndexStatusInput() {}
    public record RefreshIndexStatusInput() {}

    // === Environment Config ===

    @Tool(name = "get_environment_status",
            description = "Gets comprehensive environment and CLI configuration status including directories, subprocess config, model roles, and disk space.")
    public Map<String, Object> getEnvironmentStatus(GetEnvironmentStatusInput input) {
        try {
            if (environmentConfigController == null) return Map.of("status", "error", "error", "Environment config not available");
            ResponseEntity<?> response = environmentConfigController.getEnvironmentStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting environment status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Log Config ===

    @Tool(name = "get_log_configuration",
            description = "Gets current log configuration settings.")
    public Map<String, Object> getLogConfig(GetLogConfigInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.getConfiguration();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting log config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_log_storage_status",
            description = "Gets log storage status including size and count information.")
    public Map<String, Object> getLogStatus(GetLogStatusInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting log status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "enable_job_logging",
            description = "Enables job logging.")
    public Map<String, Object> enableJobLogging(EnableJobLoggingInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.enable();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error enabling job logging: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "disable_job_logging",
            description = "Disables job logging.")
    public Map<String, Object> disableJobLogging(DisableJobLoggingInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.disable();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error disabling job logging: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "trigger_log_cleanup",
            description = "Triggers manual cleanup of logs older than specified hours (default 168 = 7 days).")
    public Map<String, Object> triggerLogCleanup(TriggerLogCleanupInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            int hoursToKeep = input.hoursToKeep() != null ? input.hoursToKeep() : 168;
            ResponseEntity<?> response = logConfigController.triggerCleanup(hoursToKeep);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error triggering log cleanup: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_log_archives",
            description = "Lists available log archives.")
    public Map<String, Object> listLogArchives(ListLogArchivesInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.listArchives();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing log archives: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "create_log_archive",
            description = "Creates a full archive of all current logs.")
    public Map<String, Object> createLogArchive(CreateLogArchiveInput input) {
        try {
            if (logConfigController == null) return Map.of("status", "error", "error", "Log config not available");
            ResponseEntity<?> response = logConfigController.createArchive();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error creating log archive: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Batch Size Config ===

    @Tool(name = "get_batch_size_models",
            description = "Lists available embedding models with their batch size configurations.")
    public Map<String, Object> getBatchSizeModels(GetBatchSizeModelsInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            ResponseEntity<?> response = batchSizeConfigController.getAvailableModels();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting batch size models: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_batch_size_model_config",
            description = "Gets batch size configuration for a specific embedding model.")
    public Map<String, Object> getBatchSizeModelConfig(GetBatchSizeModelConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = batchSizeConfigController.getModelConfig(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting batch size model config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_global_batch_config",
            description = "Gets the global batch size configuration.")
    public Map<String, Object> getGlobalBatchConfig(GetGlobalBatchConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            ResponseEntity<?> response = batchSizeConfigController.getGlobalConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting global batch config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_batch_size_model_config",
            description = "Resets batch size configuration to defaults for a specific model.")
    public Map<String, Object> resetBatchSizeModelConfig(ResetBatchSizeModelConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            if (input.modelId() == null) return Map.of("status", "error", "error", "Model ID is required");
            ResponseEntity<?> response = batchSizeConfigController.resetModelConfig(input.modelId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error resetting batch size model config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_global_batch_config",
            description = "Resets global batch size configuration to defaults.")
    public Map<String, Object> resetGlobalBatchConfig(ResetGlobalBatchConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            ResponseEntity<?> response = batchSizeConfigController.resetGlobalConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error resetting global batch config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_batch_size_timeout_config",
            description = "Gets current timeout configuration for batch operations.")
    public Map<String, Object> getBatchSizeTimeoutConfig(GetBatchSizeTimeoutConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            ResponseEntity<?> response = batchSizeConfigController.getTimeoutConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting timeout config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_batch_size_timeout_config",
            description = "Resets timeout configuration to defaults (no timeouts).")
    public Map<String, Object> resetBatchSizeTimeoutConfig(ResetBatchSizeTimeoutConfigInput input) {
        try {
            if (batchSizeConfigController == null) return Map.of("status", "error", "error", "Batch size config not available");
            ResponseEntity<?> response = batchSizeConfigController.resetTimeoutConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error resetting timeout config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Service State ===

    @Tool(name = "get_service_state",
            description = "Gets comprehensive state of all loaded services including embedding, vector store, retriever, and reranker.")
    public Map<String, Object> getServiceState(GetServiceStateInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getServiceState();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting service state: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_embedding_service_state",
            description = "Gets detailed embedding model state.")
    public Map<String, Object> getEmbeddingState(GetEmbeddingStateInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getEmbeddingState();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting embedding state: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_vector_store_service_state",
            description = "Gets detailed vector store state.")
    public Map<String, Object> getVectorStoreState(GetVectorStoreStateInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getVectorStoreStateEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting vector store state: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_reranker_service_state",
            description = "Gets detailed reranker service state.")
    public Map<String, Object> getRerankerState(GetRerankerStateInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getRerankerStateEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting reranker state: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_document_retriever_service_state",
            description = "Gets detailed document retriever state.")
    public Map<String, Object> getDocumentRetrieverState(GetDocumentRetrieverStateInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getDocumentRetrieverStateEndpoint();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting document retriever state: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_service_index_status",
            description = "Gets index status and availability from the service state perspective.")
    public Map<String, Object> getServiceIndexStatus(GetServiceIndexStatusInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.getIndexStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting service index status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "refresh_service_index_status",
            description = "Force refreshes the index status cache.")
    public Map<String, Object> refreshIndexStatus(RefreshIndexStatusInput input) {
        try {
            if (serviceStateController == null) return Map.of("status", "error", "error", "Service state not available");
            ResponseEntity<?> response = serviceStateController.refreshIndexStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error refreshing index status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
