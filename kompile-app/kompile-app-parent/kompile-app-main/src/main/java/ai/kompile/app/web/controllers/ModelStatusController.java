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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.EmbeddingStatusBroadcaster;
import ai.kompile.app.subprocess.model.ModelInitSubprocessLauncher;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingStartupInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for model status, initialization status, and subprocess logs.
 * Extracted from ModelDebugController.
 *
 * Endpoints:
 *   GET  /api/models/status
 *   GET  /api/models/init-status
 *   GET  /api/models/subprocess/logs
 *   DELETE /api/models/subprocess/logs
 */
@RestController
@RequestMapping("/api/models")
public class ModelStatusController {

    private static final Logger logger = LoggerFactory.getLogger(ModelStatusController.class);

    @Autowired(required = false)
    private List<EmbeddingModel> embeddingModels;

    @Autowired(required = false)
    private List<LanguageModel> languageModels;

    @Autowired(required = false)
    private AnseriniEmbeddingStartupInitializer startupInitializer;

    @Autowired(required = false)
    private ModelInitSubprocessLauncher subprocessLauncher;

    @Autowired(required = false)
    private EmbeddingStatusBroadcaster embeddingStatusBroadcaster;

    /**
     * Quick status check for embedding model availability and configuration.
     * Use this to diagnose pipeline bottlenecks related to embedding.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getModelStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Embedding model status
        Map<String, Object> embeddingStatus = new LinkedHashMap<>();
        if (embeddingModels == null || embeddingModels.isEmpty()) {
            embeddingStatus.put("available", false);
            embeddingStatus.put("reason", "No embedding models configured");
        } else {
            embeddingStatus.put("available", true);
            embeddingStatus.put("count", embeddingModels.size());

            List<Map<String, Object>> models = new ArrayList<>();
            for (EmbeddingModel model : embeddingModels) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("class", model.getClass().getSimpleName());
                info.put("dimensions", model.dimensions());
                info.put("optimalBatchSize", model.getOptimalBatchSize());
                info.put("maxBatchSize", model.getMaxBatchSize());

                // Check if it's a no-op implementation
                boolean isNoOp = model.getClass().getSimpleName().contains("NoOp");
                info.put("isNoOp", isNoOp);

                models.add(info);
            }
            embeddingStatus.put("models", models);

            // Primary model info
            EmbeddingModel primary = embeddingModels.get(0);
            embeddingStatus.put("primaryModel", primary.getClass().getSimpleName());
            embeddingStatus.put("primaryIsNoOp", primary.getClass().getSimpleName().contains("NoOp"));
        }
        status.put("embedding", embeddingStatus);

        // Language model status
        Map<String, Object> llmStatus = new LinkedHashMap<>();
        if (languageModels == null || languageModels.isEmpty()) {
            llmStatus.put("available", false);
        } else {
            llmStatus.put("available", true);
            llmStatus.put("count", languageModels.size());
            llmStatus.put("primaryModel", languageModels.get(0).getClass().getSimpleName());
        }
        status.put("languageModel", llmStatus);

        // Memory status
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMB", runtime.maxMemory() / (1024 * 1024));
        memory.put("totalMB", runtime.totalMemory() / (1024 * 1024));
        memory.put("freeMB", runtime.freeMemory() / (1024 * 1024));
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("usagePercent", ((runtime.totalMemory() - runtime.freeMemory()) * 100.0) / runtime.maxMemory());
        status.put("memory", memory);

        // Model initialization status (subprocess mode)
        Map<String, Object> initStatus = new LinkedHashMap<>();
        if (startupInitializer != null) {
            initStatus.put("subprocessModeEnabled", startupInitializer.isSubprocessInitEnabled());
            initStatus.put("subprocessStatus", startupInitializer.getSubprocessStatus().name());
            initStatus.put("subprocessProgress", startupInitializer.getSubprocessProgressPercent());
            initStatus.put("subprocessMessage", startupInitializer.getSubprocessStatusMessage());
            initStatus.put("pollingActive", startupInitializer.isPollingActive());
        }
        if (subprocessLauncher != null) {
            var launcherStatus = subprocessLauncher.getCurrentStatus();
            initStatus.put("launcherStatus", launcherStatus.status().name());
            initStatus.put("launcherTaskId", launcherStatus.taskId());
            initStatus.put("launcherModelId", launcherStatus.modelId());
            initStatus.put("launcherPhase", launcherStatus.phase() != null ? launcherStatus.phase().name() : null);
            initStatus.put("launcherProgress", launcherStatus.progressPercent());
            initStatus.put("launcherMessage", launcherStatus.message());
            initStatus.put("launcherIsRunning", subprocessLauncher.isInitializationRunning());
        }
        status.put("initialization", initStatus);

        return ResponseEntity.ok(status);
    }

    /**
     * Get detailed model initialization status for UI polling.
     * This endpoint provides comprehensive information about model initialization
     * including subprocess status when subprocess mode is enabled.
     */
    @GetMapping("/init-status")
    public ResponseEntity<Map<String, Object>> getInitializationStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Get primary embedding model status
        AnseriniEmbeddingModelImpl anseriniModel = null;
        if (embeddingModels != null) {
            for (EmbeddingModel model : embeddingModels) {
                if (model instanceof AnseriniEmbeddingModelImpl) {
                    anseriniModel = (AnseriniEmbeddingModelImpl) model;
                    break;
                }
            }
        }

        if (anseriniModel != null) {
            status.put("modelId", anseriniModel.getModelIdentifier());
            status.put("initialized", anseriniModel.isInitialized());
            status.put("loading", anseriniModel.isLoading());
            status.put("loadingPhase", anseriniModel.getLoadingPhase());
            status.put("loadingMessage", anseriniModel.getLoadingMessage());
            status.put("modelSource", anseriniModel.getModelSource().name());
            status.put("encoderType", anseriniModel.getEncoderType() != null ? anseriniModel.getEncoderType() : null);
            status.put("dimensions", anseriniModel.dimensions());
            status.put("initializationError", anseriniModel.getInitializationError());
        }

        // Subprocess mode status
        Map<String, Object> subprocess = new LinkedHashMap<>();
        if (startupInitializer != null) {
            subprocess.put("enabled", startupInitializer.isSubprocessInitEnabled());
            subprocess.put("status", startupInitializer.getSubprocessStatus().name());
            subprocess.put("progress", startupInitializer.getSubprocessProgressPercent());
            subprocess.put("message", startupInitializer.getSubprocessStatusMessage());

            // Map phase to user-friendly description
            String phaseDescription = switch (startupInitializer.getSubprocessStatus()) {
                case IDLE -> "Waiting to start";
                case STARTING -> "Starting subprocess JVM";
                case INITIALIZING_ND4J -> "Initializing ND4J backend";
                case CONFIGURING_SOURCE -> "Configuring model source";
                case LOOKING_UP_REGISTRY -> "Looking up model in registry";
                case LOADING_MODEL -> "Loading model files";
                case CREATING_ENCODER -> "Creating encoder (building neural network graph)";
                case VALIDATING -> "Validating model output";
                case COMPLETED -> "Model initialization complete";
                case FAILED -> "Initialization failed";
            };
            subprocess.put("phaseDescription", phaseDescription);
        }

        // Launcher status (if using subprocess)
        if (subprocessLauncher != null) {
            var launcherStatus = subprocessLauncher.getCurrentStatus();
            subprocess.put("launcherStatus", launcherStatus.status().name());
            subprocess.put("launcherTaskId", launcherStatus.taskId());
            subprocess.put("launcherPhase", launcherStatus.phase() != null ? launcherStatus.phase().name() : null);
            subprocess.put("launcherProgress", launcherStatus.progressPercent());
            subprocess.put("launcherMessage", launcherStatus.message());
            subprocess.put("launcherRunning", subprocessLauncher.isInitializationRunning());

            if (launcherStatus.embeddingDimensions() != null) {
                subprocess.put("resultDimensions", launcherStatus.embeddingDimensions());
            }
            if (launcherStatus.encoderType() != null) {
                subprocess.put("resultEncoderType", launcherStatus.encoderType());
            }
            if (launcherStatus.errorMessage() != null) {
                subprocess.put("error", launcherStatus.errorMessage());
                subprocess.put("errorRetriable", launcherStatus.errorRetriable());
            }
        }
        status.put("subprocess", subprocess);

        // Memory status
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("maxMB", runtime.maxMemory() / (1024 * 1024));
        memory.put("usagePercent", ((runtime.totalMemory() - runtime.freeMemory()) * 100.0) / runtime.maxMemory());
        status.put("memory", memory);

        return ResponseEntity.ok(status);
    }

    /**
     * Get recent subprocess logs for the embedding model initialization.
     * This provides historical log data for clients that connect after events were broadcast.
     *
     * @param limit Maximum number of log entries to return (default 100)
     * @return List of subprocess log entries with event type, model ID, timestamp, and data
     */
    @GetMapping("/subprocess/logs")
    public ResponseEntity<Map<String, Object>> getSubprocessLogs(
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingStatusBroadcaster == null) {
                response.put("status", "error");
                response.put("message", "EmbeddingStatusBroadcaster not available");
                response.put("logs", Collections.emptyList());
                return ResponseEntity.ok(response);
            }

            List<Map<String, Object>> logs = embeddingStatusBroadcaster.getRecentSubprocessLogs(limit);

            response.put("status", "success");
            response.put("count", logs.size());
            response.put("limit", limit);
            response.put("logs", logs);

            // Add summary of event types
            Map<String, Long> eventTypeCounts = logs.stream()
                    .filter(log -> log.get("eventType") != null)
                    .collect(Collectors.groupingBy(
                            log -> (String) log.get("eventType"),
                            Collectors.counting()));
            response.put("eventTypeCounts", eventTypeCounts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting subprocess logs", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            response.put("logs", Collections.emptyList());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Clear subprocess logs.
     * Useful for resetting log history after debugging.
     */
    @DeleteMapping("/subprocess/logs")
    public ResponseEntity<Map<String, Object>> clearSubprocessLogs() {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            if (embeddingStatusBroadcaster == null) {
                response.put("status", "error");
                response.put("message", "EmbeddingStatusBroadcaster not available");
                return ResponseEntity.ok(response);
            }

            embeddingStatusBroadcaster.clearSubprocessLogs();
            response.put("status", "success");
            response.put("message", "Subprocess logs cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error clearing subprocess logs", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
