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

import ai.kompile.app.config.ContextualRagConfig;
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
import java.util.List;
import java.util.Map;

/**
 * Service for managing Contextual RAG configuration.
 *
 * <p>This service provides:</p>
 * <ul>
 *   <li>Persistent configuration storage to JSON file</li>
 *   <li>Configuration validation</li>
 *   <li>Default presets for common use cases</li>
 *   <li>Runtime configuration updates</li>
 * </ul>
 *
 * <p>Configuration is persisted to: ~/.kompile/config/contextual-rag-config.json</p>
 */
@Service
public class ContextualRagConfigService {

    private static final Logger log = LoggerFactory.getLogger(ContextualRagConfigService.class);
    private static final String CONFIG_FILENAME = "contextual-rag-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    private volatile ContextualRagConfig currentConfig;

    public ContextualRagConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = ContextualRagConfig.defaults();

        log.info("ContextualRagConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load persisted configuration on startup.
     */
    @PostConstruct
    public void loadConfig() {
        if (configFilePath == null) {
            log.warn("Cannot load contextual RAG config - path not configured. Using defaults.");
            currentConfig = ContextualRagConfig.defaults();
            return;
        }

        log.info("Loading contextual RAG configuration from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted contextual RAG config found - using defaults");
            currentConfig = ContextualRagConfig.defaults();
            persistConfig();
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            ContextualRagConfig loaded = objectMapper.readValue(json, ContextualRagConfig.class);
            currentConfig = ContextualRagConfig.defaults().merge(loaded);
            log.info("Loaded contextual RAG config: enabled={}, provider={}, model={}",
                    currentConfig.getEnabled(),
                    currentConfig.getLlmProvider(),
                    currentConfig.getLlmModel());
        } catch (IOException e) {
            log.error("Failed to load contextual RAG config: {}. Using defaults.", e.getMessage());
            currentConfig = ContextualRagConfig.defaults();
        }
    }

    /**
     * Gets the current configuration.
     */
    public ContextualRagConfig getConfiguration() {
        return currentConfig;
    }

    /**
     * Updates the configuration with the provided values.
     *
     * @param update The partial configuration to apply
     * @return The new complete configuration
     */
    public ContextualRagConfig updateConfiguration(ContextualRagConfig update) {
        if (update == null) {
            return currentConfig;
        }

        // Validate the update
        validateConfig(update);

        // Merge with current config
        currentConfig = currentConfig.merge(update);

        // Persist to disk
        persistConfig();

        log.info("Contextual RAG configuration updated: enabled={}, provider={}, model={}",
                currentConfig.getEnabled(),
                currentConfig.getLlmProvider(),
                currentConfig.getLlmModel());

        return currentConfig;
    }

    /**
     * Resets configuration to defaults.
     */
    public ContextualRagConfig resetConfiguration() {
        currentConfig = ContextualRagConfig.defaults();
        persistConfig();
        log.info("Contextual RAG configuration reset to defaults");
        return currentConfig;
    }

    /**
     * Applies a preset configuration.
     *
     * @param presetName Name of the preset (fast, balanced, quality, minimal)
     * @return The new configuration
     */
    public ContextualRagConfig applyPreset(String presetName) {
        ContextualRagConfig preset = getPreset(presetName);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown preset: " + presetName +
                ". Available presets: fast, balanced, quality, minimal, disabled");
        }
        currentConfig = preset;
        persistConfig();
        log.info("Applied contextual RAG preset: {}", presetName);
        return currentConfig;
    }

    /**
     * Gets a preset configuration by name.
     */
    public ContextualRagConfig getPreset(String presetName) {
        return switch (presetName.toLowerCase()) {
            case "disabled" -> createDisabledPreset();
            case "minimal" -> createMinimalPreset();
            case "fast" -> createFastPreset();
            case "balanced" -> createBalancedPreset();
            case "quality" -> createQualityPreset();
            default -> null;
        };
    }

    /**
     * Gets available preset names.
     */
    public List<PresetInfo> getAvailablePresets() {
        return List.of(
            new PresetInfo("disabled", "Contextual enrichment disabled",
                "No LLM calls during indexing. Fastest indexing but standard retrieval quality."),
            new PresetInfo("minimal", "Minimal enrichment",
                "Source attribution only, no LLM contextualization. Good for cost-sensitive deployments."),
            new PresetInfo("fast", "Fast contextualization",
                "Uses default LLM with minimal context. Good balance of speed and quality."),
            new PresetInfo("balanced", "Balanced contextualization",
                "Full document summary and chunk context. Recommended for most use cases."),
            new PresetInfo("quality", "Maximum quality",
                "Full contextualization with surrounding chunks and caching. Best retrieval quality.")
        );
    }

    /**
     * Checks if contextual enrichment is enabled.
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(currentConfig.getEnabled());
    }

    /**
     * Checks if source attribution is enabled.
     */
    public boolean isSourceAttributionEnabled() {
        return Boolean.TRUE.equals(currentConfig.getSourceAttributionEnabled());
    }

    /**
     * Gets the prompt template to use for contextualization.
     */
    public String getPromptTemplate() {
        String custom = currentConfig.getContextPromptTemplate();
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return ContextualRagConfig.getDefaultPromptTemplate();
    }

    /**
     * Gets the path to the configuration file.
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRESET FACTORIES
    // ═══════════════════════════════════════════════════════════════════════════

    private ContextualRagConfig createDisabledPreset() {
        return ContextualRagConfig.builder()
                .enabled(false)
                .sourceAttributionEnabled(true)
                .citationFormat("filename")
                .includePageNumbers(true)
                .cachingEnabled(false)
                .build();
    }

    private ContextualRagConfig createMinimalPreset() {
        return ContextualRagConfig.builder()
                .enabled(false)
                .sourceAttributionEnabled(true)
                .citationFormat("path")
                .includePageNumbers(true)
                .cachingEnabled(false)
                .build();
    }

    private ContextualRagConfig createFastPreset() {
        return ContextualRagConfig.builder()
                .enabled(true)
                .llmProvider("default")
                .llmModel(null)
                .temperature(0.0)
                .maxContextTokens(100)
                .includeDocumentSummary(false)
                .includeSurroundingChunks(false)
                .sourceAttributionEnabled(true)
                .citationFormat("filename")
                .includePageNumbers(true)
                .batchSize(20)
                .maxConcurrentRequests(10)
                .requestTimeoutSeconds(15)
                .maxRetries(2)
                .cachingEnabled(true)
                .cacheTtlDays(30)
                .fallbackOnError(true)
                .webSearchFallbackThreshold(0.0)
                .build();
    }

    private ContextualRagConfig createBalancedPreset() {
        return ContextualRagConfig.builder()
                .enabled(true)
                .llmProvider("default")
                .llmModel(null)
                .temperature(0.1)
                .maxContextTokens(150)
                .includeDocumentSummary(true)
                .documentSummaryMaxTokens(500)
                .includeSurroundingChunks(false)
                .sourceAttributionEnabled(true)
                .citationFormat("filename")
                .includePageNumbers(true)
                .batchSize(10)
                .maxConcurrentRequests(5)
                .requestTimeoutSeconds(30)
                .maxRetries(3)
                .cachingEnabled(true)
                .cacheTtlDays(30)
                .fallbackOnError(true)
                .webSearchFallbackThreshold(0.0)
                .build();
    }

    private ContextualRagConfig createQualityPreset() {
        return ContextualRagConfig.builder()
                .enabled(true)
                .llmProvider("default")
                .llmModel(null)
                .temperature(0.1)
                .maxContextTokens(200)
                .includeDocumentSummary(true)
                .documentSummaryMaxTokens(1000)
                .includeSurroundingChunks(true)
                .surroundingChunksWindow(2)
                .sourceAttributionEnabled(true)
                .citationFormat("path")
                .includePageNumbers(true)
                .batchSize(5)
                .maxConcurrentRequests(3)
                .requestTimeoutSeconds(60)
                .maxRetries(3)
                .cachingEnabled(true)
                .cacheTtlDays(60)
                .fallbackOnError(true)
                .webSearchFallbackThreshold(0.65)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    private void validateConfig(ContextualRagConfig config) {
        if (config.getTemperature() != null) {
            if (config.getTemperature() < 0.0 || config.getTemperature() > 2.0) {
                throw new IllegalArgumentException("Temperature must be between 0.0 and 2.0");
            }
        }
        if (config.getMaxContextTokens() != null) {
            if (config.getMaxContextTokens() < 10 || config.getMaxContextTokens() > 2000) {
                throw new IllegalArgumentException("maxContextTokens must be between 10 and 2000");
            }
        }
        if (config.getBatchSize() != null) {
            if (config.getBatchSize() < 1 || config.getBatchSize() > 100) {
                throw new IllegalArgumentException("batchSize must be between 1 and 100");
            }
        }
        if (config.getMaxConcurrentRequests() != null) {
            if (config.getMaxConcurrentRequests() < 1 || config.getMaxConcurrentRequests() > 50) {
                throw new IllegalArgumentException("maxConcurrentRequests must be between 1 and 50");
            }
        }
        if (config.getWebSearchFallbackThreshold() != null) {
            if (config.getWebSearchFallbackThreshold() < 0.0 || config.getWebSearchFallbackThreshold() > 1.0) {
                throw new IllegalArgumentException("webSearchFallbackThreshold must be between 0.0 and 1.0");
            }
        }
        if (config.getLlmProvider() != null && config.getLlmProvider().isBlank()) {
            throw new IllegalArgumentException("LLM provider cannot be blank");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.debug("Persisted contextual RAG config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist contextual RAG config: {}", e.getMessage(), e);
        }
    }

    /**
     * Information about a configuration preset.
     */
    public record PresetInfo(String name, String displayName, String description) {}
}
