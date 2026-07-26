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

import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import ai.kompile.app.services.agent.CliAgentModelService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing graph extraction configuration.
 * Configuration is persisted to a JSON file and can be updated via the UI.
 */
@Service
public class GraphExtractionConfigService {

    private static final Logger logger = LoggerFactory.getLogger(GraphExtractionConfigService.class);
    private static final String CONFIG_FILENAME = "graph-extraction-config.json";

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path configPath;

    private GraphExtractionConfig currentConfig;

    public GraphExtractionConfigService(
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.objectMapper = JsonUtils.newStandardMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = Paths.get(dataDir, "config", CONFIG_FILENAME);
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * Load configuration from file or create default.
     */
    private void loadConfig() {
        lock.writeLock().lock();
        try {
            boolean shouldSave = false;
            if (Files.exists(configPath)) {
                try {
                    currentConfig = objectMapper.readValue(configPath.toFile(), GraphExtractionConfig.class);
                    currentConfig.normalizeValidationPolicy();
                    shouldSave = true;
                    logger.info("Loaded graph extraction config from {}", configPath);
                } catch (IOException e) {
                    logger.error("Failed to load graph extraction config, using defaults: {}", e.getMessage());
                    currentConfig = GraphExtractionConfig.defaults();
                    shouldSave = true;
                }
            } else {
                logger.info("No graph extraction config found, using defaults");
                currentConfig = GraphExtractionConfig.defaults();
                shouldSave = true;
            }
            if (shouldSave) {
                saveConfigInternal();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the current configuration.
     */
    public GraphExtractionConfig getConfig() {
        lock.readLock().lock();
        try {
            return currentConfig.copy();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update the configuration.
     */
    public GraphExtractionConfig updateConfig(GraphExtractionConfig newConfig) {
        lock.writeLock().lock();
        try {
            // Validate and merge with existing config. Graph extraction is mandatory; only
            // extraction policy/model details are configurable.
            if (newConfig.batchSize != null) {
                currentConfig.batchSize = Math.max(1, Math.min(50, newConfig.batchSize));
            }
            if (newConfig.schemaEnforcement != null) {
                currentConfig.schemaEnforcement = newConfig.schemaEnforcement;
            }
            if (newConfig.entityTypes != null) {
                currentConfig.entityTypes = newConfig.entityTypes;
            }
            if (newConfig.relationshipTypes != null) {
                currentConfig.relationshipTypes = newConfig.relationshipTypes;
            }
            if (newConfig.maxEntitiesPerChunk != null) {
                currentConfig.maxEntitiesPerChunk = Math.max(1, Math.min(100, newConfig.maxEntitiesPerChunk));
            }
            if (newConfig.maxRelationshipsPerChunk != null) {
                currentConfig.maxRelationshipsPerChunk = Math.max(1, Math.min(200, newConfig.maxRelationshipsPerChunk));
            }
            // Model settings
            if (newConfig.extractionModelProvider != null) {
                currentConfig.extractionModelProvider = newConfig.extractionModelProvider;
            }
            if (newConfig.extractionModelName != null) {
                currentConfig.extractionModelName = newConfig.extractionModelName;
            }
            if (newConfig.extractionTemperature != null) {
                currentConfig.extractionTemperature = Math.max(0.0, Math.min(2.0, newConfig.extractionTemperature));
            }
            if (newConfig.extractionMaxTokens != null) {
                currentConfig.extractionMaxTokens = Math.max(100, Math.min(32000, newConfig.extractionMaxTokens));
            }
            if (newConfig.customExtractionPrompt != null) {
                currentConfig.customExtractionPrompt = newConfig.customExtractionPrompt.isEmpty() ? null : newConfig.customExtractionPrompt;
            }
            if (newConfig.validationPolicy != null) {
                currentConfig.validationPolicy = newConfig.validationPolicy.copy();
            }
            if (newConfig.extractionModelProviderAllow != null) {
                currentConfig.extractionModelProviderAllow = List.copyOf(newConfig.extractionModelProviderAllow);
            }
            if (newConfig.extractionModelExcludeMarkers != null) {
                currentConfig.extractionModelExcludeMarkers = List.copyOf(newConfig.extractionModelExcludeMarkers);
            }
            if (newConfig.extractionModelAllow != null) {
                currentConfig.extractionModelAllow = List.copyOf(newConfig.extractionModelAllow);
            }
            if (newConfig.extractionModelPriority != null) {
                currentConfig.extractionModelPriority = List.copyOf(newConfig.extractionModelPriority);
            }
            // Deduplication
            if (newConfig.deduplicationEnabled != null) {
                currentConfig.deduplicationEnabled = newConfig.deduplicationEnabled;
            }
            if (newConfig.similarityThreshold != null) {
                currentConfig.similarityThreshold = Math.max(0.0, Math.min(1.0, newConfig.similarityThreshold));
            }
            if (newConfig.neo4jEnabled != null) {
                currentConfig.neo4jEnabled = newConfig.neo4jEnabled;
            }
            if (newConfig.neo4jUri != null) {
                currentConfig.neo4jUri = newConfig.neo4jUri;
            }
            if (newConfig.neo4jUsername != null) {
                currentConfig.neo4jUsername = newConfig.neo4jUsername;
            }
            if (newConfig.neo4jPassword != null) {
                currentConfig.neo4jPassword = newConfig.neo4jPassword;
            }
            if (newConfig.neo4jDatabase != null) {
                currentConfig.neo4jDatabase = newConfig.neo4jDatabase;
            }
            if (newConfig.activeSchemaPresetId != null) {
                String presetId = newConfig.activeSchemaPresetId.trim();
                currentConfig.activeSchemaPresetId = presetId.isEmpty() ? null : presetId;
            }
            // Merge pass-through keys (crawl*/llm*/localStaging*/compaction* owned by other
            // services) so an extraction-config save both PRESERVES and can SET them.
            if (!newConfig.additionalProperties.isEmpty()) {
                currentConfig.additionalProperties.putAll(newConfig.additionalProperties);
            }

            saveConfigInternal();
            logger.info("Updated graph extraction config: graph=mandatory, batchSize={}",
                    currentConfig.batchSize);
            return currentConfig.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reset to default configuration.
     */
    public GraphExtractionConfig resetToDefaults() {
        lock.writeLock().lock();
        try {
            currentConfig = GraphExtractionConfig.defaults();
            saveConfigInternal();
            logger.info("Reset graph extraction config to defaults");
            return currentConfig.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Graph extraction is mandatory for crawls.
     */
    public boolean isEnabled() {
        return true;
    }

    /**
     * Get the batch size for entity extraction.
     */
    public int getBatchSize() {
        lock.readLock().lock();
        try {
            return currentConfig.batchSize != null ? currentConfig.batchSize : 10;
        } finally {
            lock.readLock().unlock();
        }
    }

    private void saveConfigInternal() {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), currentConfig);
            logger.debug("Saved graph extraction config to {}", configPath);
        } catch (IOException e) {
            logger.error("Failed to save graph extraction config: {}", e.getMessage());
        }
    }

    /**
     * Graph extraction configuration model.
     */
    public static class GraphExtractionConfig {
        // Extraction settings
        public Integer batchSize;
        public String schemaEnforcement;
        public List<String> entityTypes;
        public List<String> relationshipTypes;
        public Integer maxEntitiesPerChunk;
        public Integer maxRelationshipsPerChunk;

        // Entity Extraction Model settings
        public String extractionModelProvider;  // Provider ID from registered LlmProviders, or "default"
        public String extractionModelName;      // Model ID from the selected provider
        public Double extractionTemperature;    // 0.0 to 2.0, lower = more deterministic
        public Integer extractionMaxTokens;     // Max tokens for extraction response
        public String customExtractionPrompt;   // Optional custom prompt template
        public GraphExtractionValidationPolicy validationPolicy; // Per-project validators and prompt rules
        public List<String> extractionModelProviderAllow;  // Provider prefixes eligible for extraction
        public List<String> extractionModelExcludeMarkers; // Model-id substrings rejected for extraction
        public List<String> extractionModelAllow;          // Strict model allow-list, empty/null = no pin
        public List<String> extractionModelPriority;       // Soft preferred model order, empty/null = default

        // Schema preset
        public String activeSchemaPresetId;

        // Deduplication settings
        public Boolean deduplicationEnabled;
        public Double similarityThreshold;

        // Neo4j settings
        public Boolean neo4jEnabled;
        public String neo4jUri;
        public String neo4jUsername;
        public String neo4jPassword;
        public String neo4jDatabase;

        // Pass-through for keys owned by OTHER services that share this same file — the
        // crawl*/llm*/localStaging*/compaction* keys read by CrawlRuntimeConfigManager. Captured
        // on load via @JsonAnySetter and re-emitted on save via @JsonAnyGetter, so updating the
        // extraction settings never drops them (the data-loss bug) and they stay settable here.
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }

        @JsonAnySetter
        public void setAdditionalProperty(String key, Object value) {
            if (!"enabled".equals(key) && !"extractionModelDisplayName".equals(key)) {
                additionalProperties.put(key, value);
            }
        }

        public static GraphExtractionConfig defaults() {
            GraphExtractionConfig config = new GraphExtractionConfig();
            config.batchSize = 10;
            config.schemaEnforcement = "LENIENT";
            config.entityTypes = List.of();
            config.relationshipTypes = List.of();
            config.maxEntitiesPerChunk = 20;
            config.maxRelationshipsPerChunk = 30;
            // Model settings defaults
            config.extractionModelProvider = "default";  // Use the default configured LLM
            config.extractionModelName = null;           // null means use provider's default
            config.extractionTemperature = 0.0;          // Low temperature for deterministic extraction
            config.extractionMaxTokens = 4096;           // Reasonable default for entity extraction
            config.customExtractionPrompt = null;        // Use built-in prompt
            config.validationPolicy = GraphExtractionValidationPolicy.defaults();
            config.extractionModelProviderAllow = List.of("opencode", "opencode-go", "local");
            config.extractionModelExcludeMarkers = List.of("claude", "codex", "opus", "sonnet", "gpt", "gemini");
            config.extractionModelAllow = List.of();
            config.extractionModelPriority = CliAgentModelService.DEFAULT_EXTRACTION_MODEL_PRIORITY;
            // Crawl runtime defaults live in managed JSON. Do not set crawlGraphExtractionTargetCharsPerBatch
            // here; leaving it at the runtime default lets ModelCapability metadata drive prompt size.
            config.additionalProperties.put("crawlGraphExtractionParallelism", 4);
            config.additionalProperties.put("crawlGraphExtractionRemoteParallelism", 4);
            config.additionalProperties.put("crawlGraphExtractionChunksPerPrompt", 4);
            config.additionalProperties.put("crawlGraphExtractionMaxItemsPerBatch", 16);
            // Deduplication
            config.deduplicationEnabled = true;
            config.similarityThreshold = 0.85;
            // Neo4j
            config.neo4jEnabled = false;
            config.neo4jUri = "bolt://localhost:7687";
            config.neo4jUsername = "neo4j";
            config.neo4jPassword = "";
            config.neo4jDatabase = "neo4j";
            return config;
        }

        public GraphExtractionConfig copy() {
            GraphExtractionConfig copy = new GraphExtractionConfig();
            copy.batchSize = this.batchSize;
            copy.schemaEnforcement = this.schemaEnforcement;
            copy.entityTypes = this.entityTypes != null ? List.copyOf(this.entityTypes) : null;
            copy.relationshipTypes = this.relationshipTypes != null ? List.copyOf(this.relationshipTypes) : null;
            copy.maxEntitiesPerChunk = this.maxEntitiesPerChunk;
            copy.maxRelationshipsPerChunk = this.maxRelationshipsPerChunk;
            // Model settings
            copy.extractionModelProvider = this.extractionModelProvider;
            copy.extractionModelName = this.extractionModelName;
            copy.extractionTemperature = this.extractionTemperature;
            copy.extractionMaxTokens = this.extractionMaxTokens;
            copy.customExtractionPrompt = this.customExtractionPrompt;
            copy.validationPolicy = this.validationPolicy != null ? this.validationPolicy.copy() : null;
            copy.extractionModelProviderAllow = this.extractionModelProviderAllow != null ? List.copyOf(this.extractionModelProviderAllow) : null;
            copy.extractionModelExcludeMarkers = this.extractionModelExcludeMarkers != null ? List.copyOf(this.extractionModelExcludeMarkers) : null;
            copy.extractionModelAllow = this.extractionModelAllow != null ? List.copyOf(this.extractionModelAllow) : null;
            copy.extractionModelPriority = this.extractionModelPriority != null ? List.copyOf(this.extractionModelPriority) : null;
            // Deduplication
            copy.deduplicationEnabled = this.deduplicationEnabled;
            copy.similarityThreshold = this.similarityThreshold;
            // Neo4j
            copy.neo4jEnabled = this.neo4jEnabled;
            copy.neo4jUri = this.neo4jUri;
            copy.neo4jUsername = this.neo4jUsername;
            // Don't copy password in copies for security
            copy.neo4jPassword = this.neo4jPassword != null && !this.neo4jPassword.isEmpty() ? "********" : "";
            copy.neo4jDatabase = this.neo4jDatabase;
            copy.activeSchemaPresetId = this.activeSchemaPresetId;
            copy.additionalProperties.putAll(this.additionalProperties);
            return copy;
        }

        private void normalizeValidationPolicy() {
            validationPolicy = validationPolicy == null
                    ? GraphExtractionValidationPolicy.defaults()
                    : validationPolicy.copy();
        }

        /**
         * Get a copy with the actual password (for internal use).
         */
        public GraphExtractionConfig copyWithPassword() {
            GraphExtractionConfig copy = copy();
            copy.neo4jPassword = this.neo4jPassword;
            return copy;
        }

        /**
         * Get the display name for the configured extraction model.
         */
        @JsonIgnore
        public String getExtractionModelDisplayName() {
            if (extractionModelProvider == null || "default".equals(extractionModelProvider)) {
                return "Default (System LLM)";
            }
            String modelName = extractionModelName != null ? extractionModelName : "default";
            return extractionModelProvider + "/" + modelName;
        }
    }
}
