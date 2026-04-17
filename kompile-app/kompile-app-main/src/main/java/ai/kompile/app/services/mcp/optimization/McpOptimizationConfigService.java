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

package ai.kompile.app.services.mcp.optimization;

import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigChangedEvent;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for managing persistent MCP optimization configuration.
 *
 * <p>Loads persisted settings on startup from
 * {@code <dataDir>/config/mcp-optimization-config.json} and persists
 * configuration changes back to the same file. Publishes
 * {@link McpOptimizationConfigChangedEvent} on every change so downstream
 * compression / meta-tool components can reload.
 *
 * <p>Mirrors the established {@link ai.kompile.app.services.AppIndexConfigService}
 * pattern — no Spring properties drive optimization behavior; everything is
 * UI-managed JSON.
 */
@Service
public class McpOptimizationConfigService implements McpOptimizationConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(McpOptimizationConfigService.class);
    private static final String CONFIG_FILENAME = "mcp-optimization-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ApplicationEventPublisher eventPublisher;

    private volatile McpOptimizationConfig currentConfig;

    @Autowired
    public McpOptimizationConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir,
            ApplicationEventPublisher eventPublisher) {
        this.objectMapper = new ObjectMapper();
        this.eventPublisher = eventPublisher;

        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = McpOptimizationConfig.defaults();
        log.info("McpOptimizationConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load persisted configuration on startup and apply it. If no file exists,
     * persist defaults so the user has a discoverable JSON to edit.
     */
    @PostConstruct
    public void loadAndApplyConfig() {
        log.info("Loading MCP optimization configuration from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted MCP optimization config found at {} - using defaults", configFilePath);
            currentConfig = McpOptimizationConfig.defaults();
        } else {
            try {
                String json = Files.readString(configFilePath);
                log.info("Read persisted MCP optimization config file: {} bytes", json.length());
                McpOptimizationConfig loaded = objectMapper.readValue(json, McpOptimizationConfig.class);
                currentConfig = McpOptimizationConfig.defaults().merge(loaded);
                log.info("Loaded MCP optimization config: enabled={}, metaToolMode={}",
                        currentConfig.getEnabled(), currentConfig.getMetaToolMode());
            } catch (IOException e) {
                log.error("Failed to load persisted MCP optimization config from {}: {} - falling back to defaults",
                        configFilePath, e.getMessage(), e);
                currentConfig = McpOptimizationConfig.defaults();
            }
        }

        persistConfig();
        publishChange();
    }

    /**
     * Gets the current configuration. Never null.
     */
    @Override
    public McpOptimizationConfig getConfiguration() {
        return currentConfig;
    }

    /**
     * Updates the configuration with the provided values. Only non-null values
     * in the update will be applied. Publishes
     * {@link McpOptimizationConfigChangedEvent} on successful update.
     */
    public McpOptimizationConfig updateConfiguration(McpOptimizationConfig update) {
        if (update == null) {
            return currentConfig;
        }
        currentConfig = currentConfig.merge(update);
        validate(currentConfig);
        persistConfig();
        publishChange();
        log.info("MCP optimization configuration updated and persisted");
        return currentConfig;
    }

    /**
     * Resets configuration to defaults.
     */
    public McpOptimizationConfig resetConfiguration() {
        currentConfig = McpOptimizationConfig.defaults();
        persistConfig();
        publishChange();
        log.info("MCP optimization configuration reset to defaults");
        return currentConfig;
    }

    /**
     * Path to the persisted configuration file.
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    private void validate(McpOptimizationConfig cfg) {
        if (cfg.getRagMaxContentChars() != null && cfg.getRagMaxContentChars() < 0) {
            throw new IllegalArgumentException("ragMaxContentChars must be >= 0");
        }
        if (cfg.getRagMaxDocs() != null && cfg.getRagMaxDocs() < 1) {
            throw new IllegalArgumentException("ragMaxDocs must be >= 1");
        }
        if (cfg.getKnowledgeGraphTruncateChars() != null && cfg.getKnowledgeGraphTruncateChars() < 0) {
            throw new IllegalArgumentException("knowledgeGraphTruncateChars must be >= 0");
        }
        if (cfg.getCompressionThresholdChars() != null && cfg.getCompressionThresholdChars() < 0) {
            throw new IllegalArgumentException("compressionThresholdChars must be >= 0");
        }
        if (cfg.getResultCacheMaxEntries() != null && cfg.getResultCacheMaxEntries() < 1) {
            throw new IllegalArgumentException("resultCacheMaxEntries must be >= 1");
        }
        if (cfg.getResultCacheTtlSeconds() != null && cfg.getResultCacheTtlSeconds() < 1) {
            throw new IllegalArgumentException("resultCacheTtlSeconds must be >= 1");
        }
        if (cfg.getFilesystemUndoTtlSeconds() != null && cfg.getFilesystemUndoTtlSeconds() < 1) {
            throw new IllegalArgumentException("filesystemUndoTtlSeconds must be >= 1");
        }
    }

    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.info("Persisted MCP optimization config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist MCP optimization config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    private void publishChange() {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new McpOptimizationConfigChangedEvent(this, currentConfig));
        }
    }
}
