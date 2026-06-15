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

package ai.kompile.app.services;

import ai.kompile.app.config.VlmOrchestrationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Service for managing VLM GPU orchestration configuration.
 * Persists config as JSON at {@code ~/.kompile/config/vlm-orchestration-config.json}.
 *
 * <p>Follows the same pattern as {@link DeviceRoutingConfigService}:
 * volatile in-memory config + JSON file on disk, loaded at startup, REST API for CRUD.</p>
 */
@Service
public class VlmOrchestrationConfigService {

    private static final Logger log = LoggerFactory.getLogger(VlmOrchestrationConfigService.class);
    private static final String CONFIG_FILENAME = "vlm-orchestration-config.json";

    private final Path configFilePath;
    private final ObjectMapper objectMapper;
    private volatile VlmOrchestrationConfig currentConfig;

    public VlmOrchestrationConfigService() {
        this.configFilePath = Path.of(System.getProperty("user.home"), ".kompile", "config", CONFIG_FILENAME);
        this.objectMapper = new ObjectMapper();
        this.currentConfig = VlmOrchestrationConfig.defaults();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        try {
            if (Files.exists(configFilePath)) {
                String json = Files.readString(configFilePath);
                VlmOrchestrationConfig loaded = objectMapper.readValue(json, VlmOrchestrationConfig.class);
                this.currentConfig = VlmOrchestrationConfig.defaults().merge(loaded);
                log.info("Loaded VLM orchestration config from {}", configFilePath);
            } else {
                log.info("No persisted VLM orchestration config found, using defaults");
            }
        } catch (Exception e) {
            log.warn("Failed to load VLM orchestration config from {}: {}", configFilePath, e.getMessage());
            this.currentConfig = VlmOrchestrationConfig.defaults();
        }
    }

    public VlmOrchestrationConfig getConfig() {
        return currentConfig;
    }

    public VlmOrchestrationConfig updateConfig(VlmOrchestrationConfig update) throws IOException {
        this.currentConfig = currentConfig.merge(update);
        persistToDisk();
        log.info("Updated VLM orchestration config: releaseEncoder={}, encoderDevice={}, decoderDevice={}, tritonCache={}",
                currentConfig.releaseEncoderAfterEncoding(),
                currentConfig.encoderDeviceId(),
                currentConfig.decoderDeviceId(),
                currentConfig.tritonCacheEnabled());
        return currentConfig;
    }

    public VlmOrchestrationConfig resetToDefaults() throws IOException {
        this.currentConfig = VlmOrchestrationConfig.defaults();
        persistToDisk();
        log.info("Reset VLM orchestration config to defaults");
        return currentConfig;
    }

    private void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }

        // Atomic write: temp file + rename
        Path tempFile = configDir.resolve(CONFIG_FILENAME + "." + UUID.randomUUID() + ".tmp");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
            Files.writeString(tempFile, json);
            Files.move(tempFile, configFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tempFile, configFilePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Clean up temp file on failure
            try { Files.deleteIfExists(tempFile); } catch (IOException cleanupEx) {
                log.warn("Failed to clean up temp config file {} after write failure: {}", tempFile, cleanupEx.getMessage());
            }
            throw e;
        }
    }
}
