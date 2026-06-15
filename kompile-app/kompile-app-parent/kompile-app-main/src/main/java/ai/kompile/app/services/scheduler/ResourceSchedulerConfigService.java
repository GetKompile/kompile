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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Manages {@link ResourceSchedulerConfig} with JSON file persistence.
 * Config file: {@code ~/.kompile/config/resource-scheduler-config.json}.
 */
@Service
public class ResourceSchedulerConfigService {

    private static final Logger log = LoggerFactory.getLogger(ResourceSchedulerConfigService.class);
    private static final String CONFIG_FILENAME = "resource-scheduler-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile ResourceSchedulerConfig currentConfig;

    public ResourceSchedulerConfigService() {
        this(KompileHome.dataDir().getAbsolutePath());
    }

    public ResourceSchedulerConfigService(String dataDir) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configFilePath = Paths.get(dataDir, "config", CONFIG_FILENAME);
        this.currentConfig = ResourceSchedulerConfig.defaults();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = objectMapper.readValue(json, ResourceSchedulerConfig.class);
                log.info("Loaded resource scheduler config from {}: enabled={}, algorithm={}",
                        configFilePath, currentConfig.isEnabled(), currentConfig.getSchedulingAlgorithm());
            } catch (Exception e) {
                log.warn("Failed to load resource scheduler config from {}: {}",
                        configFilePath, e.getMessage());
                currentConfig = ResourceSchedulerConfig.defaults();
            }
        } else {
            log.info("No resource scheduler config found at {}, using defaults", configFilePath);
        }
    }

    public ResourceSchedulerConfig getConfiguration() {
        return currentConfig;
    }

    public void saveConfiguration(ResourceSchedulerConfig config) throws IOException {
        this.currentConfig = config;
        persistToDisk();
        log.info("Saved resource scheduler config: enabled={}, algorithm={}, " +
                        "phaseAwareYield={}, dispatchInterval={}ms",
                config.isEnabled(), config.getSchedulingAlgorithm(),
                config.isPhaseAwareYieldEnabled(), config.getDispatchIntervalMs());
    }

    public void resetToDefaults() throws IOException {
        saveConfiguration(ResourceSchedulerConfig.defaults());
        log.info("Resource scheduler config reset to defaults");
    }

    private void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }

        // Atomic write via temp file
        Path tempFile = configDir.resolve(CONFIG_FILENAME + "." + UUID.randomUUID() + ".tmp");
        try {
            objectMapper.writeValue(tempFile.toFile(), currentConfig);
            Files.move(tempFile, configFilePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
