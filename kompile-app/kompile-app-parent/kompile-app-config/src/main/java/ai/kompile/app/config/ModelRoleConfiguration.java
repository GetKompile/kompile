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

package ai.kompile.app.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for role-based model assignment in the RAG pipeline.
 *
 * Models can be assigned to specific roles:
 * - dense-retrieval: Models for semantic vector search (bge-base-en-v1.5, arctic-embed-l)
 * - sparse-retrieval: Models for learned sparse representations (splade-pp-ed)
 * - reranking: Cross-encoder models for reranking (ms-marco-MiniLM-L-6-v2)
 *
 * These settings work with both built-in models (ModelConstants) and
 * imported models (registry.json via archive import).
 * <p>
 * Configuration is loaded from {@code <dataDir>/config/model-roles-config.json} at
 * startup and can be persisted back via {@link #persist()}. When the file is absent
 * the class field defaults are used unchanged.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ModelRoleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelRoleConfiguration.class);
    private static final String CONFIG_FILENAME = "model-roles-config.json";

    // Singleton instance for static access
    private static volatile ModelRoleConfiguration instance;

    @JsonIgnore
    private final Path configFilePath;

    @JsonIgnore
    private final ObjectMapper objectMapper;

    private String denseRetrievalModel = "bge-base-en-v1.5";
    private String sparseRetrievalModel = "";
    private String rerankingModel = "ms-marco-MiniLM-L-6-v2";
    private boolean hybridEnabled = true;
    private double hybridDenseWeight = 0.7;
    private boolean rerankingEnabled = true;
    private int rerankingTopK = 50;
    private int registryRefreshIntervalSeconds = 300;
    /**
     * Path to the model registry JSON file. Null/blank means the per-project default
     * (~/.kompile/models/registry.json) is resolved at the call site. The authoritative
     * registry path is owned by RegistryService / KompileModelManager; this field
     * exists for API completeness and JSON round-trip only.
     */
    private String registryPath = null;

    public ModelRoleConfiguration(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.objectMapper = JsonUtils.newStandardMapper();
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("ModelRoleConfiguration initialized, config path: {}", configFilePath);
    }

    /**
     * Loads the JSON config file on startup, overlaying values onto this instance.
     * When the file is absent all field defaults are kept as-is. Never throws.
     */
    @PostConstruct
    public void init() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                // readerForUpdating applies each present JSON key onto *this*;
                // absent keys keep their existing (default) values.
                objectMapper.readerForUpdating(this).readValue(json);
                log.info("Loaded model-roles config from {}", configFilePath);
            } catch (IOException e) {
                log.warn("Could not read model-roles config from {} — using defaults: {}", configFilePath, e.getMessage());
            }
        } else {
            log.info("No model-roles config found at {} — using defaults", configFilePath);
        }

        instance = this;
        log.info("RAG Pipeline Model Configuration initialized:");
        log.info("  Dense Retrieval Model: {}", denseRetrievalModel);
        if (sparseRetrievalModel != null && !sparseRetrievalModel.isEmpty()) {
            log.info("  Sparse Retrieval Model: {}", sparseRetrievalModel);
        }
        log.info("  Reranking Model: {}", rerankingModel);
        log.info("  Hybrid Enabled: {}", hybridEnabled);
        log.info("  Reranking Enabled: {}", rerankingEnabled);
        log.info("  Registry Refresh Interval: {} seconds", registryRefreshIntervalSeconds);
    }

    /**
     * Persists the current configuration to {@code <dataDir>/config/model-roles-config.json}
     * as pretty-printed JSON. Parent directories are created if absent.
     */
    public void persist() {
        try {
            Path parent = configFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created config directory: {}", parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted model-roles config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist model-roles config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // Static accessors for use in factories (where Spring DI isn't available)

    public static ModelRoleConfiguration getInstance() {
        return instance;
    }

    public static String getConfiguredDenseModel() {
        if (instance != null && instance.denseRetrievalModel != null && !instance.denseRetrievalModel.isEmpty()) {
            return instance.denseRetrievalModel;
        }
        return "bge-base-en-v1.5"; // Default
    }

    public static String getConfiguredSparseModel() {
        if (instance != null && instance.sparseRetrievalModel != null && !instance.sparseRetrievalModel.isEmpty()) {
            return instance.sparseRetrievalModel;
        }
        return null; // No default sparse model
    }

    public static String getConfiguredRerankingModel() {
        if (instance != null && instance.rerankingModel != null && !instance.rerankingModel.isEmpty()) {
            return instance.rerankingModel;
        }
        return "ms-marco-MiniLM-L-6-v2"; // Default
    }

    public static boolean isHybridConfigured() {
        return instance != null && instance.hybridEnabled;
    }

    public static boolean isRerankingConfigured() {
        return instance != null && instance.rerankingEnabled;
    }
}
