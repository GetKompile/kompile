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
        response.put("vectorStorePath", config.getVectorStorePath());
        response.put("keywordIndexPath", config.getKeywordIndexPath());
        response.put("subprocessEnabled", config.getSubprocessEnabled());
        response.put("subprocessHeapSize", config.getSubprocessHeapSize());
        response.put("indexBatchSize", config.getIndexBatchSize());
        response.put("adaptiveBatchSize", config.getAdaptiveBatchSize());
        response.put("embeddingTargetBatchSize", config.getEmbeddingTargetBatchSize());
        response.put("configFilePath", configService.getConfigFilePath());

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

        return ResponseEntity.ok(response);
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        AppIndexConfig config = configService.resetConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("vectorStorePath", config.getVectorStorePath());
        response.put("keywordIndexPath", config.getKeywordIndexPath());
        response.put("subprocessEnabled", config.getSubprocessEnabled());
        response.put("subprocessHeapSize", config.getSubprocessHeapSize());
        response.put("indexBatchSize", config.getIndexBatchSize());
        response.put("adaptiveBatchSize", config.getAdaptiveBatchSize());
        response.put("embeddingTargetBatchSize", config.getEmbeddingTargetBatchSize());
        response.put("message", "Configuration reset to defaults");
        response.put("configFilePath", configService.getConfigFilePath());

        return ResponseEntity.ok(response);
    }
}
