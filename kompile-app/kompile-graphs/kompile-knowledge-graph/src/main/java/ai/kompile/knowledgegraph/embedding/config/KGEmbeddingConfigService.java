/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.embedding.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing KG embedding and GraphRAG configuration.
 * Settings are persisted to a JSON file and loaded on startup.
 *
 * <p>This replaces Spring property-based configuration with a GUI-configurable
 * system that persists across restarts.</p>
 */
@Service
public class KGEmbeddingConfigService {

    private static final Logger log = LoggerFactory.getLogger(KGEmbeddingConfigService.class);
    private static final String CONFIG_FILENAME = "kg-embedding-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @PersistenceContext
    private EntityManager entityManager;

    // Current configuration (in memory)
    private volatile KGEmbeddingConfig config;

    private static final List<String> DEFAULT_ENTITY_TYPES = Arrays.asList(
            "PERSON", "ORGANIZATION", "LOCATION", "CONCEPT", "EVENT", "PRODUCT", "TECHNOLOGY"
    );

    public KGEmbeddingConfigService() {
        this(KompileHome.dataDir().toPath());
    }

    public KGEmbeddingConfigService(String dataDir) {
        this(Paths.get(dataDir));
    }

    private KGEmbeddingConfigService(Path dataDir) {
        this.objectMapper = new ObjectMapper();

        this.configFilePath = dataDir.resolve("config").resolve(CONFIG_FILENAME);

        // Initialize with defaults
        this.config = KGEmbeddingConfig.defaults();

        log.info("KGEmbeddingConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load persisted configuration on startup.
     */
    @PostConstruct
    public void loadPersistedConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No persisted KG embedding config found at {} - using defaults", configFilePath);
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            KGEmbeddingConfig loaded = objectMapper.readValue(json, KGEmbeddingConfig.class);
            this.config = loaded;
            log.info("Loaded KG embedding configuration from {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to load KG embedding config from {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Persist current configuration to file.
     */
    private void persistConfig() {
        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFilePath, json);
            log.info("Persisted KG embedding config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist KG embedding config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets the current configuration.
     */
    public KGEmbeddingConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets TransE-specific configuration.
     */
    public TrainConfig getTransEConfig() {
        return getConfig().transe();
    }

    /**
     * Gets RotatE-specific configuration.
     */
    public TrainConfig getRotatEConfig() {
        return getConfig().rotate();
    }

    /**
     * Gets GraphRAG configuration.
     */
    public GraphRAGConfig getGraphRAGConfig() {
        return getConfig().graphrag();
    }

    /**
     * Gets training configuration for a specific algorithm.
     */
    public TrainConfig getTrainConfig(KGEmbeddingAlgorithm algorithm) {
        return switch (algorithm) {
            case TRANSE -> getTransEConfig();
            case ROTATE -> getRotatEConfig();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Updates the entire configuration.
     */
    public KGEmbeddingConfig updateConfig(KGEmbeddingConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates TransE configuration.
     */
    public KGEmbeddingConfig updateTransEConfig(TrainConfig trainConfig) {
        lock.writeLock().lock();
        try {
            this.config = new KGEmbeddingConfig(
                    trainConfig,
                    config.rotate(),
                    config.graphrag(),
                    config.neo4j()
            );
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates RotatE configuration.
     */
    public KGEmbeddingConfig updateRotatEConfig(TrainConfig trainConfig) {
        lock.writeLock().lock();
        try {
            this.config = new KGEmbeddingConfig(
                    config.transe(),
                    trainConfig,
                    config.graphrag(),
                    config.neo4j()
            );
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates GraphRAG configuration.
     */
    public KGEmbeddingConfig updateGraphRAGConfig(GraphRAGConfig graphRAGConfig) {
        lock.writeLock().lock();
        try {
            this.config = new KGEmbeddingConfig(
                    config.transe(),
                    config.rotate(),
                    graphRAGConfig,
                    config.neo4j()
            );
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets Neo4j configuration.
     */
    public Neo4jConfig getNeo4jConfig() {
        return getConfig().neo4j();
    }

    /**
     * Updates Neo4j configuration.
     */
    public KGEmbeddingConfig updateNeo4jConfig(Neo4jConfig neo4jConfig) {
        lock.writeLock().lock();
        try {
            this.config = new KGEmbeddingConfig(
                    config.transe(),
                    config.rotate(),
                    config.graphrag(),
                    neo4jConfig
            );
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the list of available LLM providers for graph building.
     */
    public List<String> getAvailableLlmProviders() {
        return Arrays.asList("default", "openai", "anthropic", "google");
    }

    /**
     * Returns the list of available models for a given LLM provider.
     */
    public List<String> getAvailableLlmModels(String provider) {
        if (provider == null) return Arrays.asList();
        return switch (provider.toLowerCase()) {
            case "openai" -> Arrays.asList("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo");
            case "anthropic" -> Arrays.asList(
                    "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-3-5",
                    "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022");
            case "google" -> Arrays.asList("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash");
            default -> Arrays.asList();
        };
    }

    /**
     * Resets configuration to defaults.
     */
    public KGEmbeddingConfig resetToDefaults() {
        lock.writeLock().lock();
        try {
            this.config = KGEmbeddingConfig.defaults();
            persistConfig();
            log.info("Reset KG embedding config to defaults");
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Complete KG embedding configuration.
     */
    public record KGEmbeddingConfig(
            TrainConfig transe,
            TrainConfig rotate,
            GraphRAGConfig graphrag,
            Neo4jConfig neo4j
    ) {
        public static KGEmbeddingConfig defaults() {
            return new KGEmbeddingConfig(
                    TrainConfig.defaultTransE(),
                    TrainConfig.defaultRotatE(),
                    GraphRAGConfig.defaults(),
                    Neo4jConfig.defaults()
            );
        }
    }

    /**
     * Training configuration for a KG embedding algorithm.
     */
    public record TrainConfig(
            int embeddingDim,
            int epochs,
            double learningRate,
            int batchSize,
            double margin,
            int negativeSamples
    ) {
        public static TrainConfig defaultTransE() {
            return new TrainConfig(100, 100, 0.01, 1024, 1.0, 10);
        }

        public static TrainConfig defaultRotatE() {
            return new TrainConfig(100, 100, 0.001, 512, 6.0, 256);
        }
    }

    /**
     * GraphRAG retrieval configuration.
     */
    public record GraphRAGConfig(
            boolean enabled,
            double kgWeight,
            double textWeight,
            int expansionHops,
            int topKEntities,
            long defaultFactSheetId
    ) {
        public static GraphRAGConfig defaults() {
            return new GraphRAGConfig(false, 0.3, 0.7, 1, 5, 1L);
        }
    }

    /**
     * Neo4j connection configuration.
     */
    public record Neo4jConfig(
            boolean enabled,
            String uri,
            String username,
            String password
    ) {
        public static Neo4jConfig defaults() {
            return new Neo4jConfig(false, "bolt://localhost:7687", "neo4j", "");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDING CONFIGURATION (per FactSheet)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets graph building configuration for a fact sheet.
     * Reads from the FactSheet entity's graphBuilderConfigJson column.
     */
    @Transactional(readOnly = true)
    public GraphBuildingConfig getGraphBuildingConfig(Long factSheetId) {
        try {
            Object[] result = (Object[]) entityManager.createNativeQuery(
                    "SELECT enable_graph_building, graph_builder_type, graph_storage_type, graph_builder_config_json " +
                            "FROM fact_sheets WHERE id = :id"
            ).setParameter("id", factSheetId).getSingleResult();

            boolean enabled = result[0] != null && (Boolean) result[0];
            String builderType = result[1] != null ? (String) result[1] : "llm";
            String storageType = result[2] != null ? (String) result[2] : "jpa";
            String configJson = (String) result[3];

            if (configJson != null && !configJson.isBlank()) {
                try {
                    GraphBuildingConfig config = objectMapper.readValue(configJson, GraphBuildingConfig.class);
                    // Override with top-level fields
                    return new GraphBuildingConfig(
                            enabled,
                            builderType,
                            storageType,
                            config.autoAccept(),
                            config.autoAcceptThreshold(),
                            config.entityTypes(),
                            config.modelProvider(),
                            config.modelName(),
                            config.temperature(),
                            config.maxTokens(),
                            config.batchSize(),
                            config.customPrompt()
                    );
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse graph builder config JSON: {}", e.getMessage());
                }
            }

            // Return defaults with DB values for enabled, builderType, storageType
            return GraphBuildingConfig.withDefaults(enabled, builderType, storageType);

        } catch (Exception e) {
            log.error("Failed to get graph building config for fact sheet {}: {}", factSheetId, e.getMessage());
            return GraphBuildingConfig.defaults();
        }
    }

    /**
     * Updates graph building configuration for a fact sheet.
     * Persists to the FactSheet entity's columns.
     */
    @Transactional
    public GraphBuildingConfig updateGraphBuildingConfig(Long factSheetId, GraphBuildingConfig config) {
        try {
            // Serialize the detailed config to JSON
            String configJson = objectMapper.writeValueAsString(config);

            entityManager.createNativeQuery(
                    "UPDATE fact_sheets SET " +
                            "enable_graph_building = :enabled, " +
                            "graph_builder_type = :builderType, " +
                            "graph_storage_type = :storageType, " +
                            "graph_builder_config_json = :configJson, " +
                            "updated_at = CURRENT_TIMESTAMP " +
                            "WHERE id = :id"
            )
                    .setParameter("enabled", config.enabled())
                    .setParameter("builderType", config.builderType())
                    .setParameter("storageType", config.storageType())
                    .setParameter("configJson", configJson)
                    .setParameter("id", factSheetId)
                    .executeUpdate();

            log.info("Updated graph building config for fact sheet {}", factSheetId);
            return config;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize graph building config: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize graph building config", e);
        }
    }

    /**
     * Graph building configuration record.
     */
    public record GraphBuildingConfig(
            boolean enabled,
            String builderType,
            String storageType,
            boolean autoAccept,
            double autoAcceptThreshold,
            List<String> entityTypes,
            String modelProvider,
            String modelName,
            double temperature,
            int maxTokens,
            int batchSize,
            String customPrompt
    ) {
        public static GraphBuildingConfig defaults() {
            return new GraphBuildingConfig(
                    false, "llm", "jpa", false, 0.9,
                    DEFAULT_ENTITY_TYPES, "default", "",
                    0.0, 2048, 10, ""
            );
        }

        public static GraphBuildingConfig withDefaults(boolean enabled, String builderType, String storageType) {
            return new GraphBuildingConfig(
                    enabled, builderType, storageType, false, 0.9,
                    DEFAULT_ENTITY_TYPES, "default", "",
                    0.0, 2048, 10, ""
            );
        }
    }
}
