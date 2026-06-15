package ai.kompile.app.web.controllers;

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.services.AppIndexConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for managing application index configuration.
 * Uses JSON-based persistence for settings that survive restarts.
 */
@RestController
@RequestMapping("/api/config/k-app")
public class AppConfigController {
    private static final Logger logger = LoggerFactory.getLogger(AppConfigController.class);

    private final AppIndexConfigService configService;

    @Autowired
    public AppConfigController(AppIndexConfigService configService) {
        this.configService = configService;
    }

    /**
     * Get current application configuration.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        AppIndexConfig config = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        // Core settings
        response.put("vectorStoreType", config.getVectorStoreType() != null ?
                config.getVectorStoreType().name() : "ANSERINI");
        response.put("vectorStorePath", config.getVectorStorePath());
        response.put("keywordIndexPath", config.getKeywordIndexPath());
        response.put("subprocessEnabled", config.getSubprocessEnabled());
        response.put("subprocessHeapSize", config.getSubprocessHeapSize());
        response.put("indexBatchSize", config.getIndexBatchSize());
        response.put("adaptiveBatchSize", config.getAdaptiveBatchSize());
        response.put("embeddingTargetBatchSize", config.getEmbeddingTargetBatchSize());
        response.put("configFilePath", configService.getConfigFilePath());

        // Vespa settings
        response.put("vespaEndpoint", config.getVespaEndpoint());
        response.put("vespaNamespace", config.getVespaNamespace());
        response.put("vespaDocumentType", config.getVespaDocumentType());
        response.put("vespaVectorField", config.getVespaVectorField());
        response.put("vespaHybridSearchEnabled", config.getVespaHybridSearchEnabled());
        response.put("vespaHybridVectorWeight", config.getVespaHybridVectorWeight());

        // pgvector settings
        response.put("pgvectorUrl", config.getPgvectorUrl());
        response.put("pgvectorUsername", config.getPgvectorUsername());
        // Don't expose password in response for security
        response.put("pgvectorPasswordSet", config.getPgvectorPassword() != null &&
                !config.getPgvectorPassword().isEmpty());
        response.put("pgvectorTableName", config.getPgvectorTableName());

        // Chroma settings
        response.put("chromaHost", config.getChromaHost());
        response.put("chromaPort", config.getChromaPort());
        response.put("chromaCollectionName", config.getChromaCollectionName());

        return ResponseEntity.ok(response);
    }

    /**
     * Update application configuration.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> configMap) {
        StringBuilder messageBuilder = new StringBuilder();
        boolean restartRequired = false;

        // Build update config from request
        AppIndexConfig.AppIndexConfigBuilder updateBuilder = AppIndexConfig.builder();
        boolean hasChanges = false;

        AppIndexConfig current = configService.getConfiguration();

        // Vector Store Type (requires restart)
        if (configMap.containsKey("vectorStoreType")) {
            String newType = (String) configMap.get("vectorStoreType");
            if (newType != null) {
                try {
                    AppIndexConfig.VectorStoreType newVectorStoreType =
                            AppIndexConfig.VectorStoreType.valueOf(newType.toUpperCase());
                    if (!newVectorStoreType.equals(current.getVectorStoreType())) {
                        updateBuilder.vectorStoreType(newVectorStoreType);
                        hasChanges = true;
                        restartRequired = true;
                        messageBuilder.append("Vector store type changed to ").append(newType).append(". ");
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid vector store type: {}", newType);
                }
            }
        }

        // Vector Store Path
        if (configMap.containsKey("vectorStorePath")) {
            String newPath = (String) configMap.get("vectorStorePath");
            if (newPath != null && !newPath.equals(current.getVectorStorePath())) {
                updateBuilder.vectorStorePath(newPath);
                hasChanges = true;
                messageBuilder.append("Vector Store path updated. ");
            }
        }

        // Keyword Index Path
        if (configMap.containsKey("keywordIndexPath")) {
            String newPath = (String) configMap.get("keywordIndexPath");
            if (newPath != null && !newPath.equals(current.getKeywordIndexPath())) {
                updateBuilder.keywordIndexPath(newPath);
                hasChanges = true;
                messageBuilder.append("Keyword Index path updated. ");
            }
        }

        // Subprocess settings (these require restart to take effect for new jobs)
        if (configMap.containsKey("subprocessEnabled")) {
            Boolean newValue = Boolean.parseBoolean(configMap.get("subprocessEnabled").toString());
            if (!newValue.equals(current.getSubprocessEnabled())) {
                updateBuilder.subprocessEnabled(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("Subprocess mode changed. ");
            }
        }

        if (configMap.containsKey("subprocessHeapSize")) {
            String newValue = (String) configMap.get("subprocessHeapSize");
            if (newValue != null && !newValue.equals(current.getSubprocessHeapSize())) {
                updateBuilder.subprocessHeapSize(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("Subprocess heap size changed. ");
            }
        }

        // Batch settings
        if (configMap.containsKey("indexBatchSize")) {
            Integer newValue = Integer.parseInt(configMap.get("indexBatchSize").toString());
            if (!newValue.equals(current.getIndexBatchSize())) {
                updateBuilder.indexBatchSize(newValue);
                hasChanges = true;
                messageBuilder.append("Index batch size changed. ");
            }
        }

        if (configMap.containsKey("adaptiveBatchSize")) {
            Boolean newValue = Boolean.parseBoolean(configMap.get("adaptiveBatchSize").toString());
            if (!newValue.equals(current.getAdaptiveBatchSize())) {
                updateBuilder.adaptiveBatchSize(newValue);
                hasChanges = true;
                messageBuilder.append("Adaptive batch size changed. ");
            }
        }

        if (configMap.containsKey("embeddingTargetBatchSize")) {
            Integer newValue = Integer.parseInt(configMap.get("embeddingTargetBatchSize").toString());
            if (!newValue.equals(current.getEmbeddingTargetBatchSize())) {
                updateBuilder.embeddingTargetBatchSize(newValue);
                hasChanges = true;
                messageBuilder.append("Embedding batch size changed. ");
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // VESPA SETTINGS
        // ═══════════════════════════════════════════════════════════════════════════
        if (configMap.containsKey("vespaEndpoint")) {
            String newValue = (String) configMap.get("vespaEndpoint");
            if (newValue != null && !newValue.equals(current.getVespaEndpoint())) {
                updateBuilder.vespaEndpoint(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("Vespa endpoint updated. ");
            }
        }

        if (configMap.containsKey("vespaNamespace")) {
            String newValue = (String) configMap.get("vespaNamespace");
            if (newValue != null && !newValue.equals(current.getVespaNamespace())) {
                updateBuilder.vespaNamespace(newValue);
                hasChanges = true;
                messageBuilder.append("Vespa namespace updated. ");
            }
        }

        if (configMap.containsKey("vespaDocumentType")) {
            String newValue = (String) configMap.get("vespaDocumentType");
            if (newValue != null && !newValue.equals(current.getVespaDocumentType())) {
                updateBuilder.vespaDocumentType(newValue);
                hasChanges = true;
                messageBuilder.append("Vespa document type updated. ");
            }
        }

        if (configMap.containsKey("vespaVectorField")) {
            String newValue = (String) configMap.get("vespaVectorField");
            if (newValue != null && !newValue.equals(current.getVespaVectorField())) {
                updateBuilder.vespaVectorField(newValue);
                hasChanges = true;
                messageBuilder.append("Vespa vector field updated. ");
            }
        }

        if (configMap.containsKey("vespaHybridSearchEnabled")) {
            Boolean newValue = Boolean.parseBoolean(configMap.get("vespaHybridSearchEnabled").toString());
            if (!newValue.equals(current.getVespaHybridSearchEnabled())) {
                updateBuilder.vespaHybridSearchEnabled(newValue);
                hasChanges = true;
                messageBuilder.append("Vespa hybrid search updated. ");
            }
        }

        if (configMap.containsKey("vespaHybridVectorWeight")) {
            Double newValue = Double.parseDouble(configMap.get("vespaHybridVectorWeight").toString());
            if (!newValue.equals(current.getVespaHybridVectorWeight())) {
                updateBuilder.vespaHybridVectorWeight(newValue);
                hasChanges = true;
                messageBuilder.append("Vespa hybrid vector weight updated. ");
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // PGVECTOR SETTINGS
        // ═══════════════════════════════════════════════════════════════════════════
        if (configMap.containsKey("pgvectorUrl")) {
            String newValue = (String) configMap.get("pgvectorUrl");
            if (newValue != null && !newValue.equals(current.getPgvectorUrl())) {
                updateBuilder.pgvectorUrl(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("pgvector URL updated. ");
            }
        }

        if (configMap.containsKey("pgvectorUsername")) {
            String newValue = (String) configMap.get("pgvectorUsername");
            if (newValue != null && !newValue.equals(current.getPgvectorUsername())) {
                updateBuilder.pgvectorUsername(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("pgvector username updated. ");
            }
        }

        if (configMap.containsKey("pgvectorPassword")) {
            String newValue = (String) configMap.get("pgvectorPassword");
            if (newValue != null && !newValue.isEmpty()) {
                updateBuilder.pgvectorPassword(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("pgvector password updated. ");
            }
        }

        if (configMap.containsKey("pgvectorTableName")) {
            String newValue = (String) configMap.get("pgvectorTableName");
            if (newValue != null && !newValue.equals(current.getPgvectorTableName())) {
                updateBuilder.pgvectorTableName(newValue);
                hasChanges = true;
                messageBuilder.append("pgvector table name updated. ");
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // CHROMA SETTINGS
        // ═══════════════════════════════════════════════════════════════════════════
        if (configMap.containsKey("chromaHost")) {
            String newValue = (String) configMap.get("chromaHost");
            if (newValue != null && !newValue.equals(current.getChromaHost())) {
                updateBuilder.chromaHost(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("Chroma host updated. ");
            }
        }

        if (configMap.containsKey("chromaPort")) {
            Integer newValue = Integer.parseInt(configMap.get("chromaPort").toString());
            if (!newValue.equals(current.getChromaPort())) {
                updateBuilder.chromaPort(newValue);
                hasChanges = true;
                restartRequired = true;
                messageBuilder.append("Chroma port updated. ");
            }
        }

        if (configMap.containsKey("chromaCollectionName")) {
            String newValue = (String) configMap.get("chromaCollectionName");
            if (newValue != null && !newValue.equals(current.getChromaCollectionName())) {
                updateBuilder.chromaCollectionName(newValue);
                hasChanges = true;
                messageBuilder.append("Chroma collection name updated. ");
            }
        }

        // Apply changes if any
        AppIndexConfig updated;
        if (hasChanges) {
            try {
                updated = configService.updateConfiguration(updateBuilder.build());
                logger.info("Configuration updated successfully");
            } catch (Exception e) {
                logger.error("Error updating configuration: {}", e.getMessage(), e);
                messageBuilder.append("Error: ").append(e.getMessage());
                updated = configService.getActualConfiguration();
            }
        } else {
            updated = configService.getActualConfiguration();
            messageBuilder.append("No changes detected. ");
        }

        if (restartRequired) {
            messageBuilder.append("Some changes require restart to take full effect.");
        }

        if (messageBuilder.length() == 0) {
            messageBuilder.append("Configuration saved.");
        }

        // Build response
        Map<String, Object> response = new HashMap<>();
        // Core settings
        response.put("vectorStoreType", updated.getVectorStoreType() != null ?
                updated.getVectorStoreType().name() : "ANSERINI");
        response.put("vectorStorePath", updated.getVectorStorePath());
        response.put("keywordIndexPath", updated.getKeywordIndexPath());
        response.put("subprocessEnabled", updated.getSubprocessEnabled());
        response.put("subprocessHeapSize", updated.getSubprocessHeapSize());
        response.put("indexBatchSize", updated.getIndexBatchSize());
        response.put("adaptiveBatchSize", updated.getAdaptiveBatchSize());
        response.put("embeddingTargetBatchSize", updated.getEmbeddingTargetBatchSize());
        response.put("switched", hasChanges);
        response.put("restartRequired", restartRequired);
        response.put("message", messageBuilder.toString().trim());
        response.put("configFilePath", configService.getConfigFilePath());

        // Vespa settings
        response.put("vespaEndpoint", updated.getVespaEndpoint());
        response.put("vespaNamespace", updated.getVespaNamespace());
        response.put("vespaDocumentType", updated.getVespaDocumentType());
        response.put("vespaVectorField", updated.getVespaVectorField());
        response.put("vespaHybridSearchEnabled", updated.getVespaHybridSearchEnabled());
        response.put("vespaHybridVectorWeight", updated.getVespaHybridVectorWeight());

        // pgvector settings
        response.put("pgvectorUrl", updated.getPgvectorUrl());
        response.put("pgvectorUsername", updated.getPgvectorUsername());
        response.put("pgvectorPasswordSet", updated.getPgvectorPassword() != null &&
                !updated.getPgvectorPassword().isEmpty());
        response.put("pgvectorTableName", updated.getPgvectorTableName());

        // Chroma settings
        response.put("chromaHost", updated.getChromaHost());
        response.put("chromaPort", updated.getChromaPort());
        response.put("chromaCollectionName", updated.getChromaCollectionName());

        return ResponseEntity.ok(response);
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        AppIndexConfig config = configService.resetConfiguration();

        Map<String, Object> response = new HashMap<>();
        // Core settings
        response.put("vectorStoreType", config.getVectorStoreType() != null ?
                config.getVectorStoreType().name() : "ANSERINI");
        response.put("vectorStorePath", config.getVectorStorePath());
        response.put("keywordIndexPath", config.getKeywordIndexPath());
        response.put("subprocessEnabled", config.getSubprocessEnabled());
        response.put("subprocessHeapSize", config.getSubprocessHeapSize());
        response.put("indexBatchSize", config.getIndexBatchSize());
        response.put("adaptiveBatchSize", config.getAdaptiveBatchSize());
        response.put("embeddingTargetBatchSize", config.getEmbeddingTargetBatchSize());
        response.put("message", "Configuration reset to defaults");
        response.put("configFilePath", configService.getConfigFilePath());

        // Vespa settings
        response.put("vespaEndpoint", config.getVespaEndpoint());
        response.put("vespaNamespace", config.getVespaNamespace());
        response.put("vespaDocumentType", config.getVespaDocumentType());
        response.put("vespaVectorField", config.getVespaVectorField());
        response.put("vespaHybridSearchEnabled", config.getVespaHybridSearchEnabled());
        response.put("vespaHybridVectorWeight", config.getVespaHybridVectorWeight());

        // pgvector settings
        response.put("pgvectorUrl", config.getPgvectorUrl());
        response.put("pgvectorUsername", config.getPgvectorUsername());
        response.put("pgvectorPasswordSet", config.getPgvectorPassword() != null &&
                !config.getPgvectorPassword().isEmpty());
        response.put("pgvectorTableName", config.getPgvectorTableName());

        // Chroma settings
        response.put("chromaHost", config.getChromaHost());
        response.put("chromaPort", config.getChromaPort());
        response.put("chromaCollectionName", config.getChromaCollectionName());

        return ResponseEntity.ok(response);
    }
}
