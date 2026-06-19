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

package ai.kompile.toolgateway.service;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.toolgateway.model.ToolGatewayConfig;
import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for managing tool gateway configuration persistence.
 * <p>
 * Follows the standard kompile config service pattern:
 * loads JSON from {@code ~/.kompile/config/tool-gateway-config.json}
 * and reads enabled state from {@code feature-flags-config.json}.
 * </p>
 * <p>
 * Model resolution is NOT handled here — it uses the existing
 * kompile serving infrastructure (staging server, global ChatModel).
 * See {@link ai.kompile.toolgateway.config.ToolGatewayConfiguration}
 * for ChatModel resolution logic.
 * </p>
 */
@Service
public class ToolGatewayConfigService {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayConfigService.class);
    private static final String CONFIG_FILENAME = "tool-gateway-config.json";
    private static final String FEATURE_FLAGS_FILENAME = "feature-flags-config.json";
    private static final String FEATURE_FLAG_KEY = "toolGatewayEnabled";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final Path featureFlagsPath;

    private volatile ToolGatewayConfig currentConfig;

    public ToolGatewayConfigService() {

        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);

        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        this.featureFlagsPath = KompileHome.dataDir().toPath().resolve("config").resolve(FEATURE_FLAGS_FILENAME);
        this.currentConfig = ToolGatewayConfig.defaults();

        log.info("ToolGatewayConfigService initialized, config path: {}", configFilePath);
    }

    @PostConstruct
    public void loadConfiguration() {
        log.info("Loading tool gateway configuration from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted tool gateway config found at {} — using defaults", configFilePath);
            currentConfig = ToolGatewayConfig.defaults();
            persistConfig();
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            ToolGatewayConfig loaded = objectMapper.readValue(json, ToolGatewayConfig.class);
            currentConfig = ToolGatewayConfig.defaults().merge(loaded);
            log.info("Loaded tool gateway config: modelSource={}, failOpen={}, dryRun={}",
                    currentConfig.getModelSource(), currentConfig.isFailOpen(), currentConfig.isDryRun());
        } catch (IOException e) {
            log.error("Failed to load tool gateway config from {}: {}",
                    configFilePath, e.getMessage(), e);
            currentConfig = ToolGatewayConfig.defaults();
        }
    }

    /**
     * Whether the tool gateway is enabled (reads from feature-flags-config.json).
     */
    public boolean isEnabled() {
        try {
            if (!Files.exists(featureFlagsPath)) {
                return false;
            }
            String json = Files.readString(featureFlagsPath);
            JsonNode root = objectMapper.readTree(json);
            JsonNode flag = root.path(FEATURE_FLAG_KEY);
            return flag.isBoolean() && flag.asBoolean();
        } catch (IOException e) {
            log.warn("Failed to read feature flags from {}: {}", featureFlagsPath, e.getMessage());
            return false;
        }
    }

    /**
     * Enable or disable the tool gateway in feature-flags-config.json.
     */
    public void setEnabled(boolean enabled) {
        try {
            ObjectNode flags;
            if (Files.exists(featureFlagsPath)) {
                String json = Files.readString(featureFlagsPath);
                JsonNode existing = objectMapper.readTree(json);
                flags = existing.isObject() ? (ObjectNode) existing : objectMapper.createObjectNode();
            } else {
                flags = objectMapper.createObjectNode();
            }

            flags.put(FEATURE_FLAG_KEY, enabled);

            Path parentDir = featureFlagsPath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            Files.writeString(featureFlagsPath, objectMapper.writeValueAsString(flags));

            log.info("Tool gateway {}", enabled ? "enabled" : "disabled");
        } catch (IOException e) {
            log.error("Failed to update feature flags at {}: {}", featureFlagsPath, e.getMessage());
        }
    }

    /**
     * Get the current configuration.
     */
    public ToolGatewayConfig getConfig() {
        return currentConfig;
    }

    /**
     * Save a new configuration.
     */
    public ToolGatewayConfig save(ToolGatewayConfig config) {
        if (config == null) return currentConfig;
        this.currentConfig = ToolGatewayConfig.defaults().merge(config);
        persistConfig();
        log.info("Tool gateway config saved: modelSource={}, failOpen={}, dryRun={}",
                currentConfig.getModelSource(), currentConfig.isFailOpen(), currentConfig.isDryRun());
        return currentConfig;
    }

    /**
     * Reload configuration from disk.
     */
    public void reload() {
        loadConfiguration();
    }

    /**
     * Get the path to the configuration file.
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            String json = objectMapper.writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.debug("Persisted tool gateway config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist tool gateway config to {}: {}",
                    configFilePath, e.getMessage(), e);
        }
    }
}
