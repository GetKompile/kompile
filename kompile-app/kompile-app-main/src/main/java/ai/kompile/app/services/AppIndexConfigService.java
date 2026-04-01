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

import ai.kompile.app.config.AppIndexConfig;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.indexers.NoOpIndexerService;
import ai.kompile.core.reranking.RerankerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing persistent application index configuration.
 * Loads persisted settings on startup and applies them to the index services.
 * Persists configuration changes to disk for retention across restarts.
 * Also handles per-fact-sheet index switching.
 */
@Service
public class AppIndexConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppIndexConfigService.class);
    private static final String CONFIG_FILENAME = "app-index-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final String dataDir;

    private final VectorStore vectorStore;
    private final IndexerService indexerService;
    private final EmbeddingModel embeddingModel;
    private final RerankerService rerankerService;

    // Track which model is associated with the current index
    private volatile String currentEncoderModelId;
    private volatile String currentRerankerModelId;

    private volatile AppIndexConfig currentConfig;

    @Autowired
    public AppIndexConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir,
            @Autowired(required = false) List<VectorStore> vectorStores,
            @Autowired(required = false) List<IndexerService> indexerServices,
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) RerankerService rerankerService) {
        this.objectMapper = new ObjectMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.dataDir = effectiveDataDir;
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);

        // Select non-NoOp implementations if available
        this.vectorStore = selectNonNoOpVectorStore(vectorStores);
        this.indexerService = selectNonNoOpIndexerService(indexerServices);
        this.embeddingModel = embeddingModel;
        this.rerankerService = rerankerService;

        // Initialize current model tracking
        this.currentEncoderModelId = getActiveEncoderModelId();
        this.currentRerankerModelId = null;

        this.currentConfig = AppIndexConfig.defaults();
        log.info("AppIndexConfigService initialized, config path: {}", configFilePath);
        log.info("Using VectorStore: {}", vectorStore != null ? vectorStore.getClass().getSimpleName() : "null");
        log.info("Using IndexerService: {}",
                indexerService != null ? indexerService.getClass().getSimpleName() : "null");
        log.info("Using EmbeddingModel: {}",
                embeddingModel != null ? embeddingModel.getClass().getSimpleName() : "null");
        log.info("Using RerankerService: {}",
                rerankerService != null ? rerankerService.getClass().getSimpleName() : "null");
        log.info("Current encoder model: {}", currentEncoderModelId);
    }

    /**
     * Select a non-NoOp VectorStore from the list of available stores.
     */
    private VectorStore selectNonNoOpVectorStore(List<VectorStore> vectorStores) {
        if (vectorStores == null || vectorStores.isEmpty()) {
            return null;
        }
        for (VectorStore store : vectorStores) {
            if (!(store instanceof NoOpVectorStoreImpl)) {
                return store;
            }
        }
        // If only NoOp is available, use it
        return vectorStores.get(0);
    }

    /**
     * Select a non-NoOp IndexerService from the list of available services.
     */
    private IndexerService selectNonNoOpIndexerService(List<IndexerService> indexerServices) {
        if (indexerServices == null || indexerServices.isEmpty()) {
            return null;
        }
        for (IndexerService service : indexerServices) {
            if (!(service instanceof NoOpIndexerService)) {
                return service;
            }
        }
        // If only NoOp is available, use it
        return indexerServices.get(0);
    }

    /**
     * Load persisted configuration on startup and apply to services.
     * Also performs auto-discovery of available indices if configured path is
     * empty.
     */
    @PostConstruct
    public void loadAndApplyConfig() {
        // Skip loading if dataDir is not configured
        if (configFilePath == null) {
            log.warn("Cannot load app index config - kompile.data.dir not configured. Using defaults.");
            currentConfig = AppIndexConfig.defaults();
            return;
        }

        log.info("Loading app index configuration from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted app index config found at {} - using defaults", configFilePath);
            currentConfig = AppIndexConfig.defaults();
        } else {
            try {
                String json = Files.readString(configFilePath);
                log.info("Read persisted app index config file: {} bytes", json.length());

                AppIndexConfig loaded = objectMapper.readValue(json, AppIndexConfig.class);

                // Merge with defaults
                currentConfig = AppIndexConfig.defaults().merge(loaded);

                // MANDATORY: Ensure paths are present
                if (currentConfig.getVectorStorePath() == null || currentConfig.getVectorStorePath().isBlank()) {
                    throw new IllegalStateException(
                            "Vector store path is missing in app-index-config.json and no default available.");
                }
                if (currentConfig.getKeywordIndexPath() == null || currentConfig.getKeywordIndexPath().isBlank()) {
                    throw new IllegalStateException(
                            "Keyword index path is missing in app-index-config.json and no default available.");
                }

                // VALIDATION: Ensure paths are different
                if (currentConfig.getVectorStorePath().equals(currentConfig.getKeywordIndexPath())) {
                    log.error("CRITICAL: vectorStorePath and keywordIndexPath are identical: {}. " +
                            "This will cause data corruption. Resetting to defaults.",
                            currentConfig.getVectorStorePath());
                    currentConfig = AppIndexConfig.defaults();
                }

                log.info("Loaded app index config: vectorStorePath={}, keywordIndexPath={}",
                        currentConfig.getVectorStorePath(), currentConfig.getKeywordIndexPath());

            } catch (IOException e) {
                log.error("Failed to load persisted app index config from {}: {}", configFilePath, e.getMessage(), e);
                throw new IllegalStateException("Required app index configuration could not be loaded", e);
            } catch (Exception e) {
                log.error("Unexpected error loading app index config: {}", e.getMessage(), e);
                throw e;
            }
        }

        // Apply the loaded configuration to the index services
        applyConfiguration();

        // Persist config (ensures config file exists)
        persistConfig();
    }

    /**
     * Apply the current configuration to the index services.
     */
    private void applyConfiguration() {
        // Apply vector store path if different from current
        if (vectorStore != null && currentConfig.getVectorStorePath() != null) {
            String currentVectorPath = vectorStore.getIndexPath();
            String configuredPath = expandPath(currentConfig.getVectorStorePath());

            if (!configuredPath.equals(currentVectorPath)) {
                log.info("Switching vector store path from {} to {}", currentVectorPath, configuredPath);
                try {
                    vectorStore.switchIndexPath(configuredPath);
                    log.info("Successfully switched vector store to: {}", configuredPath);
                } catch (Exception e) {
                    log.error("Failed to switch vector store path: {}", e.getMessage(), e);
                }
            } else {
                log.info("Vector store already using configured path: {}", configuredPath);
            }
        }

        // Apply keyword index path if different from current
        if (indexerService != null && currentConfig.getKeywordIndexPath() != null) {
            String currentKeywordPath = indexerService.getIndexPath();
            String configuredPath = expandPath(currentConfig.getKeywordIndexPath());

            if (!configuredPath.equals(currentKeywordPath)) {
                log.info("Switching keyword index path from {} to {}", currentKeywordPath, configuredPath);
                try {
                    indexerService.switchIndexPath(configuredPath);
                    log.info("Successfully switched keyword index to: {}", configuredPath);
                } catch (Exception e) {
                    log.error("Failed to switch keyword index path: {}", e.getMessage(), e);
                }
            } else {
                log.info("Keyword index already using configured path: {}", configuredPath);
            }
        }
    }

    /**
     * Expand ~ to user home directory.
     */
    private String expandPath(String path) {
        // Path expansion for ~ is removed to avoid system property dependence.
        // Paths should be correctly configured as absolute or relative to execution
        // context.
        return path;
    }

    /**
     * Gets the current configuration.
     */
    public AppIndexConfig getConfiguration() {
        return currentConfig;
    }

    /**
     * Updates the configuration with the provided values.
     * Only non-null values in the update will be applied.
     *
     * @param update The partial configuration to apply
     * @return The new complete configuration
     */
    public AppIndexConfig updateConfiguration(AppIndexConfig update) {
        if (update == null) {
            return currentConfig;
        }

        // Expand paths
        if (update.getVectorStorePath() != null) {
            update.setVectorStorePath(expandPath(update.getVectorStorePath()));
        }
        if (update.getKeywordIndexPath() != null) {
            update.setKeywordIndexPath(expandPath(update.getKeywordIndexPath()));
        }

        // Merge with current config
        currentConfig = currentConfig.merge(update);

        // VALIDATION: Ensure paths are different after merge
        if (currentConfig.getVectorStorePath() != null &&
            currentConfig.getVectorStorePath().equals(currentConfig.getKeywordIndexPath())) {
            log.error("Cannot save configuration: vectorStorePath and keywordIndexPath cannot be identical: {}",
                    currentConfig.getVectorStorePath());
            throw new IllegalArgumentException(
                    "Vector store path and keyword index path cannot be the same: " + currentConfig.getVectorStorePath());
        }

        // Apply to services
        applyConfiguration();

        // Persist to disk
        persistConfig();

        log.info("App index configuration updated and persisted");
        return currentConfig;
    }

    /**
     * Resets configuration to defaults.
     */
    public AppIndexConfig resetConfiguration() {
        currentConfig = AppIndexConfig.defaults();
        applyConfiguration();
        persistConfig();
        log.info("App index configuration reset to defaults");
        return currentConfig;
    }

    /**
     * Persists the current configuration to disk.
     */
    private void persistConfig() {
        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.info("Persisted app index config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist app index config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Gets the path to the configuration file.
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    /**
     * Gets the actual current paths from the services (not just config).
     */
    public AppIndexConfig getActualConfiguration() {
        AppIndexConfig actual = AppIndexConfig.builder()
                .vectorStorePath(vectorStore != null ? vectorStore.getIndexPath() : currentConfig.getVectorStorePath())
                .keywordIndexPath(
                        indexerService != null ? indexerService.getIndexPath() : currentConfig.getKeywordIndexPath())
                .subprocessEnabled(currentConfig.getSubprocessEnabled())
                .subprocessHeapSize(currentConfig.getSubprocessHeapSize())
                .indexBatchSize(currentConfig.getIndexBatchSize())
                .adaptiveBatchSize(currentConfig.getAdaptiveBatchSize())
                .embeddingTargetBatchSize(currentConfig.getEmbeddingTargetBatchSize())
                .build();
        return actual;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT SHEET INDEX SWITCHING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Applies the index configuration from a fact sheet.
     * <p>
     * This method is called when a fact sheet is activated to switch the vector
     * store,
     * keyword index, embedding model, and reranker to use the fact sheet's specific
     * configuration (if set) or the global defaults.
     * </p>
     * <p>
     * <b>IMPORTANT:</b> When the embedding model changes, the vector index becomes
     * incompatible and must be rebuilt. This method validates model compatibility
     * but does NOT automatically reindex - that must be triggered separately.
     * </p>
     *
     * @param factSheet The fact sheet to apply configuration from
     * @return true if configuration was applied successfully, false otherwise
     */
    public boolean applyFactSheetConfiguration(FactSheet factSheet) {
        if (factSheet == null) {
            log.warn("Cannot apply configuration from null fact sheet");
            return false;
        }

        log.info("Applying index configuration for fact sheet: {} (id={})",
                factSheet.getName(), factSheet.getId());

        // Determine the paths to use (fact sheet paths or global defaults)
        String vectorPath = resolveVectorStorePath(factSheet);
        String keywordPath = resolveKeywordIndexPath(factSheet);

        log.info("Resolved paths for fact sheet '{}': vectorStore={}, keywordIndex={}",
                factSheet.getName(), vectorPath, keywordPath);

        boolean vectorSwitched = applyVectorStorePath(vectorPath);
        boolean keywordSwitched = applyKeywordIndexPath(keywordPath);

        // Configure model source based on fact sheet settings
        boolean sourceConfigured = applyModelSourceConfiguration(factSheet);

        // Apply embedding model configuration
        String targetEncoderModel = resolveEncoderModel(factSheet);
        boolean encoderSwitched = applyEncoderModel(targetEncoderModel, factSheet);

        // Apply reranker configuration
        boolean rerankerConfigured = applyRerankerConfiguration(factSheet);

        log.info(
                "Fact sheet '{}' configuration applied: vectorSwitched={}, keywordSwitched={}, sourceConfigured={}, encoderSwitched={}, rerankerConfigured={}",
                factSheet.getName(), vectorSwitched, keywordSwitched, sourceConfigured, encoderSwitched,
                rerankerConfigured);

        return vectorSwitched || keywordSwitched || sourceConfigured || encoderSwitched || rerankerConfigured;
    }

    /**
     * Configures the model source based on the fact sheet's embeddingModelSource
     * setting.
     * <p>
     * If source is 'archive', loads the specified archive.
     * If source is 'staging', ensures the staging service is configured.
     * </p>
     *
     * @param factSheet The fact sheet containing source configuration
     * @return true if source configuration was applied, false otherwise
     */
    private boolean applyModelSourceConfiguration(FactSheet factSheet) {
        String source = factSheet.getEmbeddingModelSource();
        String archiveId = factSheet.getEmbeddingArchiveId();

        if (source == null || source.isBlank() || "default".equalsIgnoreCase(source)) {
            log.debug("Using default model source configuration");
            return false;
        }

        if ("archive".equalsIgnoreCase(source)) {
            if (archiveId == null || archiveId.isBlank()) {
                log.warn("Fact sheet '{}' has source='archive' but no archiveId specified",
                        factSheet.getName());
                return false;
            }

            // Load the archive by ID
            return loadArchiveById(archiveId);
        }

        if ("staging".equalsIgnoreCase(source)) {
            // Staging is configured in ModelSourceConfiguration on startup
            // Just log that we're using staging
            log.info("Fact sheet '{}' configured to use remote staging service", factSheet.getName());
            return true;
        }

        log.debug("Unknown model source type: {}", source);
        return false;
    }

    /**
     * Loads an archive by its ID.
     * Searches the archives directory for a matching archive file.
     *
     * @param archiveId The archive ID to load
     * @return true if the archive was loaded successfully, false otherwise
     */
    private boolean loadArchiveById(String archiveId) {
        Path archivesDir = Paths.get(this.dataDir, "archives");

        if (!Files.exists(archivesDir)) {
            log.warn("Archives directory does not exist: {}", archivesDir);
            return false;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archivesDir, "*.karch")) {
            for (Path archivePath : stream) {
                String foundArchiveId = readArchiveId(archivePath);
                if (archiveId.equals(foundArchiveId)) {
                    log.info("Loading archive '{}' from {}", archiveId, archivePath);
                    try {
                        // Use AnseriniEncoderFactory to load the archive
                        Class<?> factoryClass = Class.forName("ai.kompile.embedding.anserini.AnseriniEncoderFactory");
                        java.lang.reflect.Method loadArchive = factoryClass.getMethod("loadArchive",
                                java.nio.file.Path.class);
                        loadArchive.invoke(null, archivePath);
                        log.info("Successfully loaded archive: {}", archiveId);
                        return true;
                    } catch (Exception e) {
                        log.error("Failed to load archive '{}': {}", archiveId, e.getMessage(), e);
                        return false;
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to search archives directory: {}", e.getMessage(), e);
            return false;
        }

        log.warn("Archive not found: {}", archiveId);
        return false;
    }

    /**
     * Reads the archive ID from a .karch file's manifest.
     *
     * @param archivePath Path to the archive file
     * @return The archive ID, or null if it couldn't be read
     */
    private String readArchiveId(Path archivePath) {
        try (java.io.InputStream fis = Files.newInputStream(archivePath);
                java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(fis);
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream tis = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                        gis)) {

            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                if (entry.getName().equals("manifest.karch.json") ||
                        entry.getName().endsWith("/manifest.karch.json")) {
                    byte[] content = tis.readAllBytes();
                    com.fasterxml.jackson.databind.JsonNode manifest = objectMapper.readTree(content);
                    com.fasterxml.jackson.databind.JsonNode archiveIdNode = manifest.get("archiveId");
                    if (archiveIdNode != null && !archiveIdNode.isNull()) {
                        return archiveIdNode.asText();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read archive ID from {}: {}", archivePath, e.getMessage());
        }
        return null;
    }

    /**
     * Resolves the vector store path for a fact sheet.
     * Returns the fact sheet's custom path if set, otherwise the global default.
     */
    private String resolveVectorStorePath(FactSheet factSheet) {
        String factSheetPath = factSheet.getVectorStorePath();
        if (factSheetPath != null && !factSheetPath.isBlank()) {
            return expandPath(factSheetPath);
        }
        // Fall back to global configuration
        return expandPath(currentConfig.getVectorStorePath());
    }

    /**
     * Resolves the keyword index path for a fact sheet.
     * Returns the fact sheet's custom path if set, otherwise the global default.
     */
    private String resolveKeywordIndexPath(FactSheet factSheet) {
        String factSheetPath = factSheet.getKeywordIndexPath();
        if (factSheetPath != null && !factSheetPath.isBlank()) {
            return expandPath(factSheetPath);
        }
        // Fall back to global configuration
        return expandPath(currentConfig.getKeywordIndexPath());
    }

    /**
     * Applies a new vector store path, switching the index if needed.
     */
    private boolean applyVectorStorePath(String newPath) {
        if (vectorStore == null || newPath == null) {
            return false;
        }

        String currentPath = vectorStore.getIndexPath();
        if (newPath.equals(currentPath)) {
            log.debug("Vector store already using path: {}", newPath);
            return false;
        }

        log.info("Switching vector store from {} to {}", currentPath, newPath);
        try {
            boolean success = vectorStore.switchIndexPath(newPath);
            if (success) {
                log.info("Successfully switched vector store to: {}", newPath);
            } else {
                log.warn("Vector store does not support index path switching");
            }
            return success;
        } catch (Exception e) {
            log.error("Failed to switch vector store path: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Applies a new keyword index path, switching the index if needed.
     */
    private boolean applyKeywordIndexPath(String newPath) {
        if (indexerService == null || newPath == null) {
            return false;
        }

        String currentPath = indexerService.getIndexPath();
        if (newPath.equals(currentPath)) {
            log.debug("Keyword index already using path: {}", newPath);
            return false;
        }

        log.info("Switching keyword index from {} to {}", currentPath, newPath);
        try {
            boolean success = indexerService.switchIndexPath(newPath);
            if (success) {
                log.info("Successfully switched keyword index to: {}", newPath);
            } else {
                log.warn("Indexer service does not support index path switching");
            }
            return success;
        } catch (Exception e) {
            log.error("Failed to switch keyword index path: {}", e.getMessage(), e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ENCODER MODEL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Resolves the encoder model ID to use for a fact sheet.
     * Returns the fact sheet's configured model if set, otherwise the default
     * model.
     */
    private String resolveEncoderModel(FactSheet factSheet) {
        String factSheetModel = factSheet.getEmbeddingModel();
        if (factSheetModel != null && !factSheetModel.isBlank()) {
            return factSheetModel;
        }
        // Fall back to the currently active model
        return getActiveEncoderModelId();
    }

    /**
     * Gets the currently active encoder model ID from the embedding model.
     *
     * @return The active model ID, or "unknown" if not available
     */
    public String getActiveEncoderModelId() {
        if (embeddingModel == null) {
            return "unknown";
        }

        // Try to call getActiveModelId() or getModelIdentifier() via reflection
        try {
            Method getActiveModelId = embeddingModel.getClass().getMethod("getActiveModelId");
            Object result = getActiveModelId.invoke(embeddingModel);
            if (result != null) {
                return result.toString();
            }
        } catch (Exception e) {
            // Try alternative method name
            try {
                Method getModelIdentifier = embeddingModel.getClass().getMethod("getModelIdentifier");
                Object result = getModelIdentifier.invoke(embeddingModel);
                if (result != null) {
                    return result.toString();
                }
            } catch (Exception e2) {
                log.debug("Could not get model identifier from embedding model: {}", e2.getMessage());
            }
        }
        return "unknown";
    }

    /**
     * Applies the encoder model configuration from a fact sheet.
     * <p>
     * If the fact sheet specifies a different encoder model than currently active,
     * this method will attempt to switch the model.
     * </p>
     * <p>
     * <b>WARNING:</b> Switching encoder models invalidates all existing embeddings
     * in the vector store. The index must be rebuilt after switching models.
     * </p>
     *
     * @param targetModel The model ID to switch to
     * @param factSheet   The fact sheet (for logging/tracking)
     * @return true if the model was switched, false if already using the target
     *         model or switch failed
     */
    private boolean applyEncoderModel(String targetModel, FactSheet factSheet) {
        if (embeddingModel == null || targetModel == null || targetModel.isBlank()) {
            log.debug("Skipping encoder model switch: embeddingModel={}, targetModel={}",
                    embeddingModel != null ? "present" : "null", targetModel);
            return false;
        }

        String currentModel = getActiveEncoderModelId();
        if (targetModel.equals(currentModel)) {
            log.debug("Already using encoder model: {}", targetModel);
            return false;
        }

        log.info("Fact sheet '{}' requests encoder model '{}', currently using '{}'",
                factSheet.getName(), targetModel, currentModel);

        // Try to switch the model using reflection (to avoid tight coupling)
        try {
            Method switchModel = embeddingModel.getClass().getMethod("switchModel", String.class);
            Object result = switchModel.invoke(embeddingModel, targetModel);
            boolean switched = Boolean.TRUE.equals(result);

            if (switched) {
                this.currentEncoderModelId = targetModel;
                log.info("Successfully switched encoder model to '{}' for fact sheet '{}'",
                        targetModel, factSheet.getName());

                // Notify vector store about the model change
                notifyVectorStoreOfModelChange(targetModel);
            } else {
                log.warn("Failed to switch encoder model to '{}' - model may not be available",
                        targetModel);
            }

            return switched;
        } catch (NoSuchMethodException e) {
            log.warn("Embedding model does not support model switching (no switchModel method)");
            return false;
        } catch (Exception e) {
            log.error("Error switching encoder model to '{}': {}", targetModel, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Notifies the vector store that the encoder model has changed.
     * This allows the vector store to track which model is associated with its
     * index.
     */
    private void notifyVectorStoreOfModelChange(String newModelId) {
        if (vectorStore == null) {
            return;
        }

        try {
            Method setEncoderModelId = vectorStore.getClass().getMethod("setEncoderModelId", String.class);
            setEncoderModelId.invoke(vectorStore, newModelId);
            log.debug("Notified vector store of encoder model change: {}", newModelId);
        } catch (NoSuchMethodException e) {
            log.debug("Vector store does not track encoder model (no setEncoderModelId method)");
        } catch (Exception e) {
            log.warn("Error notifying vector store of model change: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RERANKER CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Applies the reranker configuration from a fact sheet.
     * <p>
     * This configures whether reranking is enabled, the reranker type,
     * and which cross-encoder model to use (if applicable).
     * </p>
     *
     * @param factSheet The fact sheet to apply reranker configuration from
     * @return true if reranker configuration was changed, false otherwise
     */
    private boolean applyRerankerConfiguration(FactSheet factSheet) {
        if (rerankerService == null) {
            log.debug("No reranker service available, skipping reranker configuration");
            return false;
        }

        boolean rerankingEnabled = Boolean.TRUE.equals(factSheet.getRerankingEnabled());
        String rerankerType = factSheet.getRerankerType();
        String crossEncoderModel = factSheet.getCrossEncoderModel();
        Integer rerankTopK = factSheet.getRerankTopK();
        Double mmrLambda = factSheet.getMmrLambda();

        log.info(
                "Applying reranker configuration for fact sheet '{}': enabled={}, type={}, crossEncoder={}, topK={}, mmrLambda={}",
                factSheet.getName(), rerankingEnabled, rerankerType, crossEncoderModel, rerankTopK, mmrLambda);

        try {
            // Try to configure the reranker service via reflection
            boolean changed = false;

            // Set enabled state
            try {
                Method setEnabled = rerankerService.getClass().getMethod("setEnabled", boolean.class);
                setEnabled.invoke(rerankerService, rerankingEnabled);
                changed = true;
            } catch (NoSuchMethodException ignored) {
            }

            // Set reranker type
            if (rerankerType != null && !rerankerType.isBlank()) {
                try {
                    Method setRerankerType = rerankerService.getClass().getMethod("setRerankerType", String.class);
                    setRerankerType.invoke(rerankerService, rerankerType);
                    changed = true;
                } catch (NoSuchMethodException ignored) {
                }
            }

            // Set cross-encoder model (if using cross-encoder type)
            if ("cross_encoder".equals(rerankerType) && crossEncoderModel != null && !crossEncoderModel.isBlank()) {
                try {
                    Method setCrossEncoderModel = rerankerService.getClass().getMethod("setCrossEncoderModel",
                            String.class);
                    setCrossEncoderModel.invoke(rerankerService, crossEncoderModel);
                    this.currentRerankerModelId = crossEncoderModel;
                    changed = true;
                } catch (NoSuchMethodException ignored) {
                }
            }

            // Set topK
            if (rerankTopK != null) {
                try {
                    Method setTopK = rerankerService.getClass().getMethod("setTopK", int.class);
                    setTopK.invoke(rerankerService, rerankTopK);
                    changed = true;
                } catch (NoSuchMethodException ignored) {
                }
            }

            // Set MMR lambda
            if (mmrLambda != null) {
                try {
                    Method setMmrLambda = rerankerService.getClass().getMethod("setMmrLambda", double.class);
                    setMmrLambda.invoke(rerankerService, mmrLambda);
                    changed = true;
                } catch (NoSuchMethodException ignored) {
                }
            }

            return changed;
        } catch (Exception e) {
            log.error("Error applying reranker configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Gets the currently configured encoder model ID.
     *
     * @return The encoder model ID being used
     */
    public String getCurrentEncoderModelId() {
        return currentEncoderModelId;
    }

    /**
     * Gets the currently configured reranker model ID.
     *
     * @return The reranker model ID being used, or null if not set
     */
    public String getCurrentRerankerModelId() {
        return currentRerankerModelId;
    }

    /**
     * Generates a default vector store path for a new fact sheet.
     * The path is based on the fact sheet name and placed in the data directory.
     *
     * @param factSheetName The name of the fact sheet
     * @return A default path for the fact sheet's vector store
     */
    public String generateDefaultVectorStorePath(String factSheetName) {
        String safeName = sanitizeForPath(factSheetName);
        return Paths.get(dataDir, "fact-sheets", safeName, "vector-index").toString();
    }

    /**
     * Generates a default keyword index path for a new fact sheet.
     * The path is based on the fact sheet name and placed in the data directory.
     *
     * @param factSheetName The name of the fact sheet
     * @return A default path for the fact sheet's keyword index
     */
    public String generateDefaultKeywordIndexPath(String factSheetName) {
        String safeName = sanitizeForPath(factSheetName);
        return Paths.get(dataDir, "fact-sheets", safeName, "keyword-index").toString();
    }

    /**
     * Sanitizes a fact sheet name for use in file paths.
     */
    private String sanitizeForPath(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed";
        }
        // Replace spaces and special characters with underscores
        return name.toLowerCase()
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    /**
     * Returns whether the current vector store supports index path switching.
     */
    public boolean isVectorStorePathSwitchingSupported() {
        if (vectorStore == null) {
            return false;
        }
        // Test by trying to get the current path - NoOp returns "N/A"
        String path = vectorStore.getIndexPath();
        return path != null && !"N/A".equals(path);
    }

    /**
     * Returns whether the current indexer service supports index path switching.
     */
    public boolean isIndexerPathSwitchingSupported() {
        if (indexerService == null) {
            return false;
        }
        // Test by trying to get the current path - NoOp returns "N/A"
        String path = indexerService.getIndexPath();
        return path != null && !"N/A".equals(path);
    }
}
