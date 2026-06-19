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

package ai.kompile.langdetect;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages the language detection configuration, persisted as JSON
 * at {@code ~/.kompile/config/language-detection-config.json}.
 */
@Slf4j
@Service
public class LanguageDetectionConfigService {

    private static final String CONFIG_FILENAME = "language-detection-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile LanguageDetectionConfig currentConfig;

    public LanguageDetectionConfigService() {
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String dataDir = System.getProperty("user.home") + "/.kompile";
        this.configFilePath = Paths.get(dataDir, "config", CONFIG_FILENAME);
        this.currentConfig = LanguageDetectionConfig.defaults();
    }

    @PostConstruct
    public void loadConfig() {
        log.info("Loading language detection config from: {}", configFilePath);
        try {
            if (Files.exists(configFilePath)) {
                String json = Files.readString(configFilePath);
                if (json != null && !json.isBlank()) {
                    currentConfig = objectMapper.readValue(json, LanguageDetectionConfig.class);
                    log.info("Loaded language detection config: enabled={}, detectOnCrawl={}, detectOnIngest={}, " +
                                    "fallbackLanguage={}, multilingualModel={}",
                            currentConfig.isEnabled(), currentConfig.isDetectOnCrawl(),
                            currentConfig.isDetectOnIngest(), currentConfig.getFallbackLanguage(),
                            currentConfig.getMultilingualEmbeddingModel());
                    return;
                }
            }
            log.info("No persisted language detection config found at {} - using defaults", configFilePath);
            persistConfig();
        } catch (Exception e) {
            log.warn("Failed to load language detection config from {}: {} - using defaults",
                    configFilePath, e.getMessage());
        }
    }

    public LanguageDetectionConfig getConfig() {
        return currentConfig;
    }

    public void updateConfig(LanguageDetectionConfig config) {
        this.currentConfig = config;
        persistConfig();
    }

    public boolean isEnabled() {
        return currentConfig.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        currentConfig.setEnabled(enabled);
        persistConfig();
    }

    public String getFallbackLanguage() {
        return currentConfig.getFallbackLanguage();
    }

    public String getMultilingualEmbeddingModel() {
        return currentConfig.getMultilingualEmbeddingModel();
    }

    public String getEnglishEmbeddingModel() {
        return currentConfig.getEnglishEmbeddingModel();
    }

    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            String json = objectMapper.writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.debug("Persisted language detection config to {}", configFilePath);
        } catch (Exception e) {
            log.error("Failed to persist language detection config to {}: {}",
                    configFilePath, e.getMessage());
        }
    }
}
