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

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.repository.FactRepository;
import ai.kompile.app.facts.repository.FactSheetRepository;
import ai.kompile.app.services.AppIndexConfigService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing fact sheets and facts.
 * Handles activation of fact sheets and triggers index switching when
 * a fact sheet with custom index paths is activated.
 */
@Service
@Transactional
public class FactSheetService {



    private static final Logger logger = LoggerFactory.getLogger(FactSheetService.class);
    private static final String DEFAULT_SHEET_NAME = "Default";

    @Autowired
    private FactSheetRepository factSheetRepository;
    @Autowired
    private FactRepository factRepository;
    @Autowired(required = false)
    private AppIndexConfigService appIndexConfigService;

    /** No-arg for Spring AOT / CGLIB proxy creation. */
    public FactSheetService() {
    }

    public FactSheetService(
            FactSheetRepository factSheetRepository,
            FactRepository factRepository,
            AppIndexConfigService appIndexConfigService) {
        this.factSheetRepository = factSheetRepository;
        this.factRepository = factRepository;
        this.appIndexConfigService = appIndexConfigService;
    }

    /**
     * Initialize the active fact sheet's configuration on startup.
     * This ensures the correct encoder, reranker, and storage paths are loaded
     * when the application starts.
     */
    @PostConstruct
    public void initializeActiveSheetConfiguration() {
        logger.info("Initializing active fact sheet configuration...");

        try {
            // Get the active sheet (or create default if none exists)
            FactSheet activeSheet = factSheetRepository.findByIsActiveTrue()
                .orElseGet(this::getOrCreateDefaultSheet);

            logger.info("Active fact sheet on startup: '{}' (id={})",
                    activeSheet.getName(), activeSheet.getId());

            // Log the configured models/paths for this sheet
            logger.info("  Embedding model: {} (source: {})",
                    activeSheet.getEmbeddingModel() != null ? activeSheet.getEmbeddingModel() : "default",
                    activeSheet.getEmbeddingModelSource() != null ? activeSheet.getEmbeddingModelSource() : "default");
            logger.info("  Reranking enabled: {}, type: {}",
                    activeSheet.getRerankingEnabled() != null ? activeSheet.getRerankingEnabled() : false,
                    activeSheet.getRerankerType() != null ? activeSheet.getRerankerType() : "none");
            logger.info("  Cross-encoder: {} (source: {})",
                    activeSheet.getCrossEncoderModel() != null ? activeSheet.getCrossEncoderModel() : "none",
                    activeSheet.getCrossEncoderModelSource() != null ? activeSheet.getCrossEncoderModelSource() : "default");
            logger.info("  Vector store path: {}",
                    activeSheet.getVectorStorePath() != null ? activeSheet.getVectorStorePath() : "default");
            logger.info("  Keyword index path: {}",
                    activeSheet.getKeywordIndexPath() != null ? activeSheet.getKeywordIndexPath() : "default");

            // Apply the configuration
            if (appIndexConfigService != null) {
                boolean applied = appIndexConfigService.applyFactSheetConfiguration(activeSheet);
                if (applied) {
                    logger.info("Applied active fact sheet configuration successfully");
                } else {
                    logger.info("Active fact sheet configuration unchanged (already applied)");
                }
            } else {
                logger.debug("AppIndexConfigService not available, skipping configuration");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize active fact sheet configuration: {}", e.getMessage(), e);
        }
    }

    /**
     * Get or create the default fact sheet.
     * <p>
     * If the default sheet exists but has no index paths configured,
     * auto-generates them to ensure it has its own isolated index.
     * </p>
     */
    public FactSheet getOrCreateDefaultSheet() {
        return factSheetRepository.findByName(DEFAULT_SHEET_NAME)
            .map(existingSheet -> {
                // Auto-generate paths if missing (for existing sheets without paths)
                boolean needsUpdate = false;
                if (existingSheet.getVectorStorePath() == null && appIndexConfigService != null) {
                    existingSheet.setVectorStorePath(appIndexConfigService.generateDefaultVectorStorePath(DEFAULT_SHEET_NAME));
                    logger.info("Auto-generated vectorStorePath for existing default sheet: {}", existingSheet.getVectorStorePath());
                    needsUpdate = true;
                }
                if (existingSheet.getKeywordIndexPath() == null && appIndexConfigService != null) {
                    existingSheet.setKeywordIndexPath(appIndexConfigService.generateDefaultKeywordIndexPath(DEFAULT_SHEET_NAME));
                    logger.info("Auto-generated keywordIndexPath for existing default sheet: {}", existingSheet.getKeywordIndexPath());
                    needsUpdate = true;
                }
                if (needsUpdate) {
                    return factSheetRepository.save(existingSheet);
                }
                return existingSheet;
            })
            .orElseGet(() -> {
                logger.info("Creating default fact sheet");

                // Auto-generate paths for the default sheet
                String vectorStorePath = null;
                String keywordIndexPath = null;
                if (appIndexConfigService != null) {
                    vectorStorePath = appIndexConfigService.generateDefaultVectorStorePath(DEFAULT_SHEET_NAME);
                    keywordIndexPath = appIndexConfigService.generateDefaultKeywordIndexPath(DEFAULT_SHEET_NAME);
                    logger.info("Auto-generated paths for new default sheet: vectorStore={}, keywordIndex={}",
                            vectorStorePath, keywordIndexPath);
                }

                FactSheet defaultSheet = FactSheet.builder()
                    .name(DEFAULT_SHEET_NAME)
                    .description("Default fact sheet for all facts")
                    .isActive(true)
                    .color("#1976d2")
                    .icon("folder")
                    .vectorStorePath(vectorStorePath)
                    .keywordIndexPath(keywordIndexPath)
                    .build();
                return factSheetRepository.save(defaultSheet);
            });
    }

    /**
     * Get the currently active fact sheet, or create the default one.
     * <p>
     * This method ensures the index configuration is applied for the active sheet.
     * </p>
     */
    public FactSheet getActiveSheet() {
        // Just return the active sheet - don't apply configuration here.
        // Configuration is only applied when explicitly switching sheets via activateSheet().
        return factSheetRepository.findByIsActiveTrue()
            .orElseGet(this::getOrCreateDefaultSheet);
    }

    /**
     * Get all fact sheets.
     */
    @Transactional(readOnly = true)
    public List<FactSheet> getAllSheets() {
        return factSheetRepository.findAllByOrderByNameAsc();
    }

    /**
     * Get a fact sheet by ID.
     */
    @Transactional(readOnly = true)
    public Optional<FactSheet> getSheetById(Long id) {
        return factSheetRepository.findById(id);
    }

    /**
     * Get a fact sheet by name.
     */
    @Transactional(readOnly = true)
    public Optional<FactSheet> getSheetByName(String name) {
        return factSheetRepository.findByName(name);
    }

    /**
     * Create a new fact sheet.
     */
    public FactSheet createSheet(String name, String description, String color, String icon) {
        return createSheet(name, description, color, icon, null, null);
    }

    /**
     * Create a new fact sheet with index storage paths.
     */
    public FactSheet createSheet(String name, String description, String color, String icon,
                                  String vectorStorePath, String keywordIndexPath) {
        return createSheet(name, description, color, icon, vectorStorePath, keywordIndexPath,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Create a new fact sheet with full model configuration.
     * <p>
     * If vectorStorePath and keywordIndexPath are not provided, they will be auto-generated
     * based on the fact sheet name to ensure each sheet has its own isolated index.
     * </p>
     */
    public FactSheet createSheet(String name, String description, String color, String icon,
                                  String vectorStorePath, String keywordIndexPath,
                                  String embeddingModel, String embeddingModelSource, String embeddingArchiveId,
                                  Boolean rerankingEnabled, String rerankerType,
                                  String crossEncoderModel, String crossEncoderModelSource, String crossEncoderArchiveId,
                                  Integer rerankTopK, Double mmrLambda) {
        if (factSheetRepository.existsByName(name)) {
            throw new IllegalArgumentException("A fact sheet with name '" + name + "' already exists");
        }

        // Auto-generate paths if not provided to ensure each sheet has its own isolated index
        String resolvedVectorStorePath = vectorStorePath;
        String resolvedKeywordIndexPath = keywordIndexPath;

        if ((resolvedVectorStorePath == null || resolvedVectorStorePath.isBlank()) && appIndexConfigService != null) {
            resolvedVectorStorePath = appIndexConfigService.generateDefaultVectorStorePath(name);
            logger.info("Auto-generated vectorStorePath for fact sheet '{}': {}", name, resolvedVectorStorePath);
        }
        if ((resolvedKeywordIndexPath == null || resolvedKeywordIndexPath.isBlank()) && appIndexConfigService != null) {
            resolvedKeywordIndexPath = appIndexConfigService.generateDefaultKeywordIndexPath(name);
            logger.info("Auto-generated keywordIndexPath for fact sheet '{}': {}", name, resolvedKeywordIndexPath);
        }

        FactSheet sheet = FactSheet.builder()
            .name(name)
            .description(description)
            .color(color != null ? color : "#1976d2")
            .icon(icon != null ? icon : "folder")
            .vectorStorePath(resolvedVectorStorePath)
            .keywordIndexPath(resolvedKeywordIndexPath)
            // Retrieval config
            .embeddingModel(embeddingModel)
            .embeddingModelSource(embeddingModelSource != null ? embeddingModelSource : "default")
            .embeddingArchiveId(embeddingArchiveId)
            // Reranking config
            .rerankingEnabled(rerankingEnabled != null ? rerankingEnabled : false)
            .rerankerType(rerankerType != null ? rerankerType : "none")
            .crossEncoderModel(crossEncoderModel)
            .crossEncoderModelSource(crossEncoderModelSource != null ? crossEncoderModelSource : "default")
            .crossEncoderArchiveId(crossEncoderArchiveId)
            .rerankTopK(rerankTopK != null ? rerankTopK : 100)
            .mmrLambda(mmrLambda != null ? mmrLambda : 0.5)
            .isActive(false)
            .build();

        logger.info("Creating fact sheet: {} with model config", name);
        return factSheetRepository.save(sheet);
    }

    /**
     * Create a new fact sheet derived from an existing one (copies all facts).
     */
    public FactSheet deriveSheet(Long sourceSheetId, String newName, String description) {
        FactSheet sourceSheet = factSheetRepository.findById(sourceSheetId)
            .orElseThrow(() -> new IllegalArgumentException("Source fact sheet not found: " + sourceSheetId));

        if (factSheetRepository.existsByName(newName)) {
            throw new IllegalArgumentException("A fact sheet with name '" + newName + "' already exists");
        }

        // Create new sheet
        FactSheet newSheet = FactSheet.builder()
            .name(newName)
            .description(description != null ? description : "Derived from: " + sourceSheet.getName())
            .color(sourceSheet.getColor())
            .icon(sourceSheet.getIcon())
            .derivedFromId(sourceSheetId)
            .isActive(false)
            .build();
        newSheet = factSheetRepository.save(newSheet);

        // Copy all facts from source to new sheet
        List<Fact> sourceFacts = factRepository.findByFactSheetIdOrderByCreatedAtDesc(sourceSheetId);
        for (Fact sourceFact : sourceFacts) {
            Fact newFact = Fact.builder()
                .factSheet(newSheet)
                .fileName(sourceFact.getFileName())
                .filePath(sourceFact.getFilePath())
                .checksum(sourceFact.getChecksum())
                .sourceType(sourceFact.getSourceType())
                .extension(sourceFact.getExtension())
                .mimeType(sourceFact.getMimeType())
                .sizeBytes(sourceFact.getSizeBytes())
                .viewMode(sourceFact.getViewMode())
                .canPreview(sourceFact.getCanPreview())
                .title(sourceFact.getTitle())
                .notes(sourceFact.getNotes())
                .tags(sourceFact.getTags())
                .sourceUrl(sourceFact.getSourceUrl())
                .build();
            factRepository.save(newFact);
        }

        logger.info("Created derived fact sheet '{}' from '{}' with {} facts",
            newName, sourceSheet.getName(), sourceFacts.size());

        return newSheet;
    }

    /**
     * Update a fact sheet.
     */
    public FactSheet updateSheet(Long id, String name, String description, String color, String icon) {
        return updateSheet(id, name, description, color, icon, null, null, false);
    }

    /**
     * Update a fact sheet with index storage paths.
     */
    public FactSheet updateSheet(Long id, String name, String description, String color, String icon,
                                  String vectorStorePath, String keywordIndexPath, boolean updateIndexPaths) {
        return updateSheet(id, name, description, color, icon, vectorStorePath, keywordIndexPath, updateIndexPaths,
                null, null, null, null, null, null, null, null, null, null, false);
    }

    /**
     * Update a fact sheet with full model configuration.
     * <p>
     * <b>IMPORTANT:</b> If the embedding model is changed and there are indexed facts,
     * all facts will be marked as unindexed since the existing embeddings are
     * incompatible with the new model.
     * </p>
     */
    public FactSheet updateSheet(Long id, String name, String description, String color, String icon,
                                  String vectorStorePath, String keywordIndexPath, boolean updateIndexPaths,
                                  String embeddingModel, String embeddingModelSource, String embeddingArchiveId,
                                  Boolean rerankingEnabled, String rerankerType,
                                  String crossEncoderModel, String crossEncoderModelSource, String crossEncoderArchiveId,
                                  Integer rerankTopK, Double mmrLambda, boolean updateModelConfig) {
        FactSheet sheet = factSheetRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + id));

        // Check name uniqueness if changing
        if (name != null && !name.equals(sheet.getName()) && factSheetRepository.existsByName(name)) {
            throw new IllegalArgumentException("A fact sheet with name '" + name + "' already exists");
        }

        if (name != null) sheet.setName(name);
        if (description != null) sheet.setDescription(description);
        if (color != null) sheet.setColor(color);
        if (icon != null) sheet.setIcon(icon);

        // Only update index paths if explicitly requested (to allow clearing them)
        if (updateIndexPaths) {
            // Normalize empty strings to null (meaning "use global defaults")
            String normalizedVectorPath = (vectorStorePath != null && vectorStorePath.isBlank()) ? null : vectorStorePath;
            String normalizedKeywordPath = (keywordIndexPath != null && keywordIndexPath.isBlank()) ? null : keywordIndexPath;

            String oldVectorPath = sheet.getVectorStorePath();
            String oldKeywordPath = sheet.getKeywordIndexPath();

            sheet.setVectorStorePath(normalizedVectorPath);
            sheet.setKeywordIndexPath(normalizedKeywordPath);

            logger.info("Updated index paths for fact sheet '{}': vectorStorePath: {} -> {}, keywordIndexPath: {} -> {}",
                    sheet.getName(),
                    oldVectorPath, normalizedVectorPath,
                    oldKeywordPath, normalizedKeywordPath);
        }

        // Update model configuration if requested
        if (updateModelConfig) {
            // Check if embedding model is changing
            String currentModel = sheet.getEmbeddingModel();
            boolean embeddingModelChanging = embeddingModel != null
                && currentModel != null
                && !embeddingModel.equals(currentModel);

            // If embedding model is changing and there are indexed facts, mark all as unindexed
            if (embeddingModelChanging) {
                long indexedCount = getIndexedFactCount(id);
                if (indexedCount > 0) {
                    logger.warn("EMBEDDING MODEL CHANGE: Fact sheet '{}' embedding model changing from '{}' to '{}'. " +
                            "Marking {} indexed facts as unindexed - reindex required!",
                            sheet.getName(), currentModel, embeddingModel, indexedCount);
                    markAllFactsAsUnindexed(id);
                }
            }

            // Retrieval config
            sheet.setEmbeddingModel(embeddingModel);
            sheet.setEmbeddingModelSource(embeddingModelSource != null ? embeddingModelSource : "default");
            sheet.setEmbeddingArchiveId(embeddingArchiveId);
            // Reranking config
            sheet.setRerankingEnabled(rerankingEnabled != null ? rerankingEnabled : false);
            sheet.setRerankerType(rerankerType != null ? rerankerType : "none");
            sheet.setCrossEncoderModel(crossEncoderModel);
            sheet.setCrossEncoderModelSource(crossEncoderModelSource != null ? crossEncoderModelSource : "default");
            sheet.setCrossEncoderArchiveId(crossEncoderArchiveId);
            sheet.setRerankTopK(rerankTopK != null ? rerankTopK : 100);
            sheet.setMmrLambda(mmrLambda != null ? mmrLambda : 0.5);
        }

        logger.info("Updated fact sheet: {}", sheet.getName());
        FactSheet savedSheet = factSheetRepository.save(sheet);

        // If index paths or model config were updated on the active sheet, apply configuration immediately
        if ((updateIndexPaths || updateModelConfig) && savedSheet.getIsActive() && appIndexConfigService != null) {
            try {
                logger.info("Applying updated configuration for active sheet '{}'", savedSheet.getName());
                appIndexConfigService.applyFactSheetConfiguration(savedSheet);
            } catch (Exception e) {
                logger.warn("Failed to apply updated configuration for sheet '{}': {}",
                        savedSheet.getName(), e.getMessage());
            }
        }

        return savedSheet;
    }

    /**
     * Gets the count of indexed facts for a fact sheet.
     */
    public long getIndexedFactCount(Long factSheetId) {
        return factRepository.countIndexedByFactSheetId(factSheetId);
    }

    /**
     * Marks all facts in a fact sheet as unindexed.
     * This is called when the embedding model changes and existing embeddings are invalidated.
     */
    @Transactional
    public void markAllFactsAsUnindexed(Long factSheetId) {
        List<Fact> facts = factRepository.findByFactSheetIdOrderByCreatedAtDesc(factSheetId);
        for (Fact fact : facts) {
            fact.setIndexed(false);
            fact.setIndexedAt(null);
        }
        factRepository.saveAll(facts);
        logger.info("Marked {} facts as unindexed for fact sheet id={}", facts.size(), factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EMBEDDING MODEL TRACKING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Records which embedding model was used when indexing completed successfully.
     * This allows detection of model mismatches that require reindexing.
     *
     * @param factSheetId the fact sheet ID
     * @param modelId the embedding model ID used for indexing (e.g., "bge-base-en-v1.5")
     */
    @Transactional
    public void setIndexedWithModel(Long factSheetId, String modelId) {
        FactSheet sheet = factSheetRepository.findById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetId));

        sheet.setIndexedWithModel(modelId);
        sheet.setIndexedAt(Instant.now());
        factSheetRepository.save(sheet);

        logger.info("Recorded indexedWithModel='{}' for fact sheet '{}' (id={})",
                modelId, sheet.getName(), factSheetId);
    }

    /**
     * Checks if a fact sheet needs reindexing due to embedding model mismatch.
     * Returns true if:
     * - The configured embedding model differs from the model used to create the current index
     * - There are indexed facts but no indexedWithModel is set (legacy data)
     *
     * @param factSheetId the fact sheet ID
     * @return true if reindex is needed, false otherwise
     */
    @Transactional(readOnly = true)
    public boolean needsReindex(Long factSheetId) {
        FactSheet sheet = factSheetRepository.findById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetId));

        // If no indexed facts, no reindex needed
        long indexedCount = factRepository.countIndexedByFactSheetId(factSheetId);
        if (indexedCount == 0) {
            return false;
        }

        String configuredModel = sheet.getEmbeddingModel();
        String indexedWithModel = sheet.getIndexedWithModel();

        // If no indexedWithModel recorded but there are indexed facts, it's legacy data
        // that was indexed before model tracking was implemented
        if (indexedWithModel == null && indexedCount > 0) {
            logger.info("Fact sheet '{}' has {} indexed facts but no indexedWithModel recorded (legacy data - " +
                    "consider reindexing to enable model mismatch detection)", sheet.getName(), indexedCount);
            // Legacy data doesn't require mandatory reindex - return false to allow normal operation
            // Reindex is only truly needed when there's an actual model mismatch
            return false;
        }

        // If models don't match, reindex needed
        if (configuredModel != null && !configuredModel.equals(indexedWithModel)) {
            logger.info("Fact sheet '{}' needs reindex: configuredModel='{}' differs from indexedWithModel='{}'",
                    sheet.getName(), configuredModel, indexedWithModel);
            return true;
        }

        return false;
    }

    /**
     * Gets detailed embedding model information for a fact sheet.
     *
     * @param factSheetId the fact sheet ID
     * @return embedding model info including mismatch status
     */
    @Transactional(readOnly = true)
    public EmbeddingModelInfo getEmbeddingModelInfo(Long factSheetId) {
        FactSheet sheet = factSheetRepository.findById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetId));

        long indexedCount = factRepository.countIndexedByFactSheetId(factSheetId);
        long totalCount = factRepository.countByFactSheetId(factSheetId);
        boolean needsReindex = needsReindex(factSheetId);

        return new EmbeddingModelInfo(
                sheet.getEmbeddingModel(),
                sheet.getEmbeddingModelSource(),
                sheet.getEmbeddingArchiveId(),
                sheet.getIndexedWithModel(),
                sheet.getIndexedAt(),
                indexedCount,
                totalCount,
                needsReindex
        );
    }

    /**
     * Updates the embedding model for a fact sheet.
     * If there are indexed facts, this will mark them all as unindexed and clear indexedWithModel.
     *
     * @param factSheetId the fact sheet ID
     * @param modelId the new embedding model ID
     * @param modelSource the source of the model ('default', 'registry', 'archive')
     * @param archiveId the archive ID if source is 'archive'
     * @param forceReindex if true, always mark facts as unindexed; if false, only if model changed
     * @return info about the update including whether reindex is needed
     */
    @Transactional
    public EmbeddingModelUpdateResult updateEmbeddingModel(Long factSheetId, String modelId,
            String modelSource, String archiveId, boolean forceReindex) {
        FactSheet sheet = factSheetRepository.findById(factSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + factSheetId));

        String previousModel = sheet.getEmbeddingModel();
        String previousIndexedWith = sheet.getIndexedWithModel();
        long indexedCount = factRepository.countIndexedByFactSheetId(factSheetId);

        boolean modelChanged = modelId != null && !modelId.equals(previousModel);
        boolean hadIndexedFacts = indexedCount > 0;
        boolean reindexRequired = forceReindex || (modelChanged && hadIndexedFacts);

        // Update the configured model
        sheet.setEmbeddingModel(modelId);
        sheet.setEmbeddingModelSource(modelSource != null ? modelSource : "default");
        sheet.setEmbeddingArchiveId(archiveId);

        if (reindexRequired) {
            // Clear indexedWithModel since the index is now invalid
            sheet.setIndexedWithModel(null);
            sheet.setIndexedAt(null);

            // Mark all facts as unindexed
            if (hadIndexedFacts) {
                markAllFactsAsUnindexed(factSheetId);
                logger.warn("EMBEDDING MODEL CHANGED: Fact sheet '{}' model changed from '{}' to '{}'. " +
                        "{} facts marked as unindexed - vector store rebuild required!",
                        sheet.getName(), previousModel, modelId, indexedCount);
            }
        }

        factSheetRepository.save(sheet);

        // If this is the active sheet, apply the new configuration
        if (sheet.getIsActive() && appIndexConfigService != null) {
            try {
                appIndexConfigService.applyFactSheetConfiguration(sheet);
            } catch (Exception e) {
                logger.warn("Failed to apply new embedding model config for active sheet '{}': {}",
                        sheet.getName(), e.getMessage());
            }
        }

        return new EmbeddingModelUpdateResult(
                previousModel,
                modelId,
                previousIndexedWith,
                modelChanged,
                hadIndexedFacts,
                reindexRequired,
                indexedCount
        );
    }

    /**
     * Information about a fact sheet's embedding model configuration.
     */
    public record EmbeddingModelInfo(
            String configuredModel,
            String modelSource,
            String archiveId,
            String indexedWithModel,
            Instant indexedAt,
            long indexedFactCount,
            long totalFactCount,
            boolean needsReindex
    ) {
        /** Returns true if there's a mismatch between configured and indexed models */
        public boolean hasModelMismatch() {
            return configuredModel != null && indexedWithModel != null
                    && !configuredModel.equals(indexedWithModel);
        }

        /** Returns the percentage of facts that are indexed */
        public double indexedPercentage() {
            return totalFactCount > 0 ? (indexedFactCount * 100.0) / totalFactCount : 0;
        }
    }

    /**
     * Result of updating a fact sheet's embedding model.
     */
    public record EmbeddingModelUpdateResult(
            String previousModel,
            String newModel,
            String previousIndexedWithModel,
            boolean modelChanged,
            boolean hadIndexedFacts,
            boolean reindexRequired,
            long affectedFactCount
    ) {}

    /**
     * Delete a fact sheet.
     */
    public void deleteSheet(Long id) {
        FactSheet sheet = factSheetRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + id));

        if (DEFAULT_SHEET_NAME.equals(sheet.getName())) {
            throw new IllegalArgumentException("Cannot delete the default fact sheet");
        }

        boolean wasActive = sheet.getIsActive();
        logger.info("Deleting fact sheet: {} (with {} facts)", sheet.getName(), sheet.getFactCount());

        factSheetRepository.delete(sheet);

        // If deleted sheet was active, activate default
        if (wasActive) {
            FactSheet defaultSheet = getOrCreateDefaultSheet();
            activateSheet(defaultSheet.getId());
        }
    }

    /**
     * Activate a fact sheet (deactivates all others).
     * <p>
     * This method also triggers index switching if the fact sheet has custom
     * vectorStorePath or keywordIndexPath configured, or switches back to
     * global defaults if the fact sheet has no custom paths.
     * </p>
     */
    public FactSheet activateSheet(Long id) {
        FactSheet sheet = factSheetRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + id));

        factSheetRepository.deactivateAll();
        factSheetRepository.activateById(id);

        logger.info("Activated fact sheet: {}", sheet.getName());

        // Apply the fact sheet's index configuration (switches vector store and keyword index)
        if (appIndexConfigService != null) {
            try {
                boolean switched = appIndexConfigService.applyFactSheetConfiguration(sheet);
                if (switched) {
                    logger.info("Index paths switched for fact sheet: {}", sheet.getName());
                }
            } catch (Exception e) {
                logger.error("Failed to apply index configuration for fact sheet '{}': {}",
                        sheet.getName(), e.getMessage(), e);
                // Don't fail activation due to index switching errors
            }
        } else {
            logger.debug("AppIndexConfigService not available, skipping index switching");
        }

        // Refresh and return
        return factSheetRepository.findById(id).orElse(sheet);
    }

    /**
     * Copy facts from one sheet to another.
     */
    public int copyFacts(Long sourceSheetId, Long targetSheetId, Set<Long> factIds) {
        FactSheet targetSheet = factSheetRepository.findById(targetSheetId)
            .orElseThrow(() -> new IllegalArgumentException("Target fact sheet not found: " + targetSheetId));

        List<Fact> factsToCopy = factRepository.findAllById(factIds);

        // Verify facts belong to source sheet
        for (Fact fact : factsToCopy) {
            if (!fact.getFactSheet().getId().equals(sourceSheetId)) {
                throw new IllegalArgumentException("Fact " + fact.getId() + " does not belong to source sheet");
            }
        }

        int copiedCount = 0;
        for (Fact sourceFact : factsToCopy) {
            // Skip if fact with same checksum already exists in target
            if (sourceFact.getChecksum() != null &&
                factRepository.existsByFactSheetIdAndChecksum(targetSheetId, sourceFact.getChecksum())) {
                logger.debug("Skipping duplicate fact: {}", sourceFact.getFileName());
                continue;
            }

            Fact newFact = Fact.builder()
                .factSheet(targetSheet)
                .fileName(sourceFact.getFileName())
                .filePath(sourceFact.getFilePath())
                .checksum(sourceFact.getChecksum())
                .sourceType(sourceFact.getSourceType())
                .extension(sourceFact.getExtension())
                .mimeType(sourceFact.getMimeType())
                .sizeBytes(sourceFact.getSizeBytes())
                .viewMode(sourceFact.getViewMode())
                .canPreview(sourceFact.getCanPreview())
                .title(sourceFact.getTitle())
                .notes(sourceFact.getNotes())
                .tags(sourceFact.getTags())
                .sourceUrl(sourceFact.getSourceUrl())
                .build();
            factRepository.save(newFact);
            copiedCount++;
        }

        logger.info("Copied {} facts from sheet {} to sheet {}", copiedCount, sourceSheetId, targetSheetId);
        return copiedCount;
    }

    /**
     * Move facts from one sheet to another.
     */
    public int moveFacts(Long sourceSheetId, Long targetSheetId, Set<Long> factIds) {
        FactSheet targetSheet = factSheetRepository.findById(targetSheetId)
            .orElseThrow(() -> new IllegalArgumentException("Target fact sheet not found: " + targetSheetId));

        List<Fact> factsToMove = factRepository.findAllById(factIds);

        int movedCount = 0;
        for (Fact fact : factsToMove) {
            if (!fact.getFactSheet().getId().equals(sourceSheetId)) {
                throw new IllegalArgumentException("Fact " + fact.getId() + " does not belong to source sheet");
            }

            // Skip if fact with same checksum already exists in target
            if (fact.getChecksum() != null &&
                factRepository.existsByFactSheetIdAndChecksum(targetSheetId, fact.getChecksum())) {
                logger.debug("Skipping duplicate fact on move: {}", fact.getFileName());
                continue;
            }

            fact.setFactSheet(targetSheet);
            factRepository.save(fact);
            movedCount++;
        }

        logger.info("Moved {} facts from sheet {} to sheet {}", movedCount, sourceSheetId, targetSheetId);
        return movedCount;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get all facts in the active sheet.
     */
    @Transactional(readOnly = true)
    public List<Fact> getActiveFacts() {
        FactSheet activeSheet = getActiveSheet();
        return factRepository.findByFactSheetIdOrderByCreatedAtDesc(activeSheet.getId());
    }

    /**
     * Get all facts in a specific sheet.
     */
    @Transactional(readOnly = true)
    public List<Fact> getFactsBySheetId(Long sheetId) {
        return factRepository.findByFactSheetIdOrderByCreatedAtDesc(sheetId);
    }

    /**
     * Get a fact by ID.
     */
    @Transactional(readOnly = true)
    public Optional<Fact> getFactById(Long id) {
        return factRepository.findById(id);
    }

    /**
     * Add a fact to the active sheet.
     */
    public Fact addFactToActiveSheet(String fileName, String filePath, String checksum,
                                     Fact.SourceType sourceType, String extension, String mimeType,
                                     Long sizeBytes, Fact.ViewMode viewMode, boolean canPreview,
                                     String sourceUrl) {
        FactSheet activeSheet = getActiveSheet();
        return addFact(activeSheet.getId(), fileName, filePath, checksum, sourceType,
            extension, mimeType, sizeBytes, viewMode, canPreview, sourceUrl);
    }

    /**
     * Add a fact to a specific sheet.
     */
    public Fact addFact(Long sheetId, String fileName, String filePath, String checksum,
                        Fact.SourceType sourceType, String extension, String mimeType,
                        Long sizeBytes, Fact.ViewMode viewMode, boolean canPreview,
                        String sourceUrl) {
        FactSheet sheet = factSheetRepository.findById(sheetId)
            .orElseThrow(() -> new IllegalArgumentException("Fact sheet not found: " + sheetId));

        Fact fact = Fact.builder()
            .factSheet(sheet)
            .fileName(fileName)
            .filePath(filePath)
            .checksum(checksum)
            .sourceType(sourceType)
            .extension(extension)
            .mimeType(mimeType)
            .sizeBytes(sizeBytes)
            .viewMode(viewMode)
            .canPreview(canPreview)
            .sourceUrl(sourceUrl)
            .build();

        logger.debug("Adding fact '{}' to sheet '{}'", fileName, sheet.getName());
        return factRepository.save(fact);
    }

    /**
     * Update a fact.
     */
    public Fact updateFact(Long factId, String title, String notes, String tags) {
        Fact fact = factRepository.findById(factId)
            .orElseThrow(() -> new IllegalArgumentException("Fact not found: " + factId));

        if (title != null) fact.setTitle(title);
        if (notes != null) fact.setNotes(notes);
        if (tags != null) fact.setTags(tags);

        return factRepository.save(fact);
    }

    /**
     * Delete a fact.
     */
    public void deleteFact(Long factId) {
        Fact fact = factRepository.findById(factId)
            .orElseThrow(() -> new IllegalArgumentException("Fact not found: " + factId));

        logger.info("Deleting fact: {} from sheet: {}", fact.getFileName(), fact.getFactSheet().getName());
        factRepository.delete(fact);
    }

    /**
     * Delete multiple facts.
     */
    public void deleteFacts(Set<Long> factIds) {
        factRepository.deleteAllById(factIds);
        logger.info("Deleted {} facts", factIds.size());
    }

    /**
     * Search facts in active sheet.
     */
    @Transactional(readOnly = true)
    public List<Fact> searchFacts(String pattern) {
        FactSheet activeSheet = getActiveSheet();
        return factRepository.searchByFileName(activeSheet.getId(), pattern);
    }

    /**
     * Get fact count for a sheet.
     */
    @Transactional(readOnly = true)
    public long getFactCount(Long sheetId) {
        return factRepository.countByFactSheetId(sheetId);
    }

    /**
     * Get total size of facts in a sheet.
     */
    @Transactional(readOnly = true)
    public long getTotalSize(Long sheetId) {
        return factRepository.getTotalSizeBySheetId(sheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INDEXING STATUS MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get count of unindexed facts in a sheet.
     */
    @Transactional(readOnly = true)
    public long getUnindexedCount(Long sheetId) {
        return factRepository.countUnindexedByFactSheetId(sheetId);
    }

    /**
     * Get count of indexed facts in a sheet.
     */
    @Transactional(readOnly = true)
    public long getIndexedCount(Long sheetId) {
        return factRepository.countIndexedByFactSheetId(sheetId);
    }

    /**
     * Get all unindexed facts in a sheet.
     */
    @Transactional(readOnly = true)
    public List<Fact> getUnindexedFacts(Long sheetId) {
        return factRepository.findUnindexedByFactSheetId(sheetId);
    }

    /**
     * Get unindexed facts in the active sheet.
     */
    @Transactional(readOnly = true)
    public List<Fact> getUnindexedActiveFacts() {
        FactSheet activeSheet = getActiveSheet();
        return factRepository.findUnindexedByFactSheetId(activeSheet.getId());
    }

    /**
     * Mark specific facts as indexed.
     * @param factIds the IDs of facts to mark as indexed
     * @return the number of facts marked
     */
    public int markFactsAsIndexed(Set<Long> factIds) {
        if (factIds == null || factIds.isEmpty()) {
            return 0;
        }
        int updated = factRepository.markAsIndexed(factIds, java.time.Instant.now());
        logger.info("Marked {} facts as indexed", updated);
        return updated;
    }

    /**
     * Mark a single fact as indexed.
     * @param factId the ID of the fact to mark
     * @return true if the fact was marked, false if not found
     */
    public boolean markFactAsIndexed(Long factId) {
        int updated = factRepository.markAsIndexed(factId, java.time.Instant.now());
        if (updated > 0) {
            logger.debug("Marked fact {} as indexed", factId);
            return true;
        }
        return false;
    }

    /**
     * Get indexing statistics for a sheet.
     */
    @Transactional(readOnly = true)
    public IndexingStats getIndexingStats(Long sheetId) {
        long total = factRepository.countByFactSheetId(sheetId);
        long indexed = factRepository.countIndexedByFactSheetId(sheetId);
        long unindexed = factRepository.countUnindexedByFactSheetId(sheetId);
        return new IndexingStats(total, indexed, unindexed);
    }

    /**
     * Get indexing statistics for the active sheet.
     */
    @Transactional(readOnly = true)
    public IndexingStats getActiveSheetIndexingStats() {
        FactSheet activeSheet = getActiveSheet();
        return getIndexingStats(activeSheet.getId());
    }

    /**
     * Statistics about fact indexing status.
     */
    public record IndexingStats(
        long totalFacts,
        long indexedFacts,
        long unindexedFacts
    ) {
        public double indexedPercentage() {
            return totalFacts > 0 ? (indexedFacts * 100.0) / totalFacts : 0;
        }

        public boolean allIndexed() {
            return unindexedFacts == 0;
        }

        public boolean hasUnindexed() {
            return unindexedFacts > 0;
        }
    }
}
