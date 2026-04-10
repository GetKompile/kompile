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

import ai.kompile.app.config.ModelWarmupConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages model warmup configuration with JSON file persistence.
 */
@Service
public class ModelWarmupConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelWarmupConfigService.class);
    private static final String CONFIG_FILENAME = "model-warmup-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile ModelWarmupConfig currentConfig;

    public ModelWarmupConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = ModelWarmupConfig.defaults();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = objectMapper.readValue(json, ModelWarmupConfig.class);
                log.info("Loaded model warmup config from {}: enabled={}, iterations={}",
                        configFilePath, currentConfig.isEnabled(), currentConfig.getIterations());
            } catch (Exception e) {
                log.warn("Failed to load model warmup config from {}: {}", configFilePath, e.getMessage());
                currentConfig = ModelWarmupConfig.defaults();
            }
        } else {
            log.info("No model warmup config found at {}, using defaults", configFilePath);
        }
    }

    public ModelWarmupConfig getConfiguration() {
        return currentConfig;
    }

    public void saveConfiguration(ModelWarmupConfig config) throws IOException {
        this.currentConfig = config;
        persistToDisk();
        log.info("Saved model warmup config: enabled={}, iterations={}", config.isEnabled(), config.getIterations());
    }

    public void resetToDefaults() throws IOException {
        currentConfig = ModelWarmupConfig.defaults();
        persistToDisk();
    }

    private void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
        Files.writeString(configFilePath, json);
    }
}
