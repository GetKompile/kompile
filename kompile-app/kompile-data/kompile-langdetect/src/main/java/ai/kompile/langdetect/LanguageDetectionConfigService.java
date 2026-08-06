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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        this(Paths.get(System.getProperty("user.home"), ".kompile", "config", CONFIG_FILENAME));
    }

    LanguageDetectionConfigService(Path configFilePath) {
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configFilePath = configFilePath;
        this.currentConfig = LanguageDetectionConfig.defaults();
    }

    @PostConstruct
    public void loadConfig() {
        log.info("Loading language detection config from: {}", configFilePath);
        try {
            if (Files.exists(configFilePath)) {
                String json = Files.readString(configFilePath);
                if (json != null && !json.isBlank()) {
                    currentConfig = decodeConfig(json);
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
            String json = encodeConfig(currentConfig);
            Files.writeString(configFilePath, json);
            log.debug("Persisted language detection config to {}", configFilePath);
        } catch (Exception e) {
            log.error("Failed to persist language detection config to {}: {}",
                    configFilePath, e.getMessage());
        }
    }

    /**
     * Decode explicitly so persisted configuration does not depend on runtime
     * DTO reflection in a native image. Missing fields retain project defaults.
     */
    private LanguageDetectionConfig decodeConfig(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        LanguageDetectionConfig config = LanguageDetectionConfig.defaults();
        if (root.has("enabled")) config.setEnabled(root.path("enabled").asBoolean(config.isEnabled()));
        if (root.has("minConfidenceThreshold")) {
            config.setMinConfidenceThreshold(root.path("minConfidenceThreshold")
                    .asDouble(config.getMinConfidenceThreshold()));
        }
        if (root.has("detectOnCrawl")) {
            config.setDetectOnCrawl(root.path("detectOnCrawl").asBoolean(config.isDetectOnCrawl()));
        }
        if (root.has("detectOnIngest")) {
            config.setDetectOnIngest(root.path("detectOnIngest").asBoolean(config.isDetectOnIngest()));
        }
        if (root.has("maxCharsForDetection")) {
            config.setMaxCharsForDetection(root.path("maxCharsForDetection")
                    .asInt(config.getMaxCharsForDetection()));
        }
        if (root.path("fallbackLanguage").isTextual()) {
            config.setFallbackLanguage(root.path("fallbackLanguage").asText());
        }
        if (root.path("multilingualEmbeddingModel").isTextual()) {
            config.setMultilingualEmbeddingModel(root.path("multilingualEmbeddingModel").asText());
        }
        if (root.path("englishEmbeddingModel").isTextual()) {
            config.setEnglishEmbeddingModel(root.path("englishEmbeddingModel").asText());
        }
        if (root.has("autoSwitchEmbeddingModel")) {
            config.setAutoSwitchEmbeddingModel(root.path("autoSwitchEmbeddingModel")
                    .asBoolean(config.isAutoSwitchEmbeddingModel()));
        }
        return config;
    }

    private String encodeConfig(LanguageDetectionConfig config) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("enabled", config.isEnabled());
        root.put("minConfidenceThreshold", config.getMinConfidenceThreshold());
        root.put("detectOnCrawl", config.isDetectOnCrawl());
        root.put("detectOnIngest", config.isDetectOnIngest());
        root.put("maxCharsForDetection", config.getMaxCharsForDetection());
        root.put("fallbackLanguage", config.getFallbackLanguage());
        root.put("multilingualEmbeddingModel", config.getMultilingualEmbeddingModel());
        root.put("englishEmbeddingModel", config.getEnglishEmbeddingModel());
        root.put("autoSwitchEmbeddingModel", config.isAutoSwitchEmbeddingModel());
        return objectMapper.writeValueAsString(root);
    }
}
