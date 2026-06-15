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

package ai.kompile.app.web.controllers;

import ai.kompile.app.facts.domain.Fact;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.RegistryBasedModelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST Controller for managing fact sheets and facts.
 * Provides CRUD operations, sheet switching, and fact copying/moving.
 */
@RestController
@RequestMapping("/api/fact-sheets")
public class FactSheetController {

    private static final Logger logger = LoggerFactory.getLogger(FactSheetController.class);

    private final FactSheetService factSheetService;
    private final AnseriniEmbeddingModelImpl embeddingModel;
    private final RegistryBasedModelManager registryManager;

    @Autowired
    public FactSheetController(
            FactSheetService factSheetService,
            @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel) {
        this.factSheetService = factSheetService;
        this.embeddingModel = embeddingModel;
        this.registryManager = new RegistryBasedModelManager();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT SHEET OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get all fact sheets.
     */
    @GetMapping
    public ResponseEntity<List<FactSheetDto>> getAllSheets() {
        List<FactSheet> sheets = factSheetService.getAllSheets();
        List<FactSheetDto> dtos = sheets.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get the currently active fact sheet.
     */
    @GetMapping("/active")
    public ResponseEntity<FactSheetDto> getActiveSheet() {
        FactSheet sheet = factSheetService.getActiveSheet();
        return ResponseEntity.ok(toDto(sheet));
    }

    /**
     * Get a fact sheet by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<FactSheetDto> getSheet(@PathVariable("id") Long id) {
        return factSheetService.getSheetById(id)
            .map(sheet -> ResponseEntity.ok(toDto(sheet)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new fact sheet.
     */
    @PostMapping
    public ResponseEntity<FactSheetDto> createSheet(@RequestBody CreateFactSheetRequest request) {
        try {
            FactSheet sheet = factSheetService.createSheet(
                request.name(),
                request.description(),
                request.color(),
                request.icon(),
                request.vectorStorePath(),
                request.keywordIndexPath(),
                // Model configuration
                request.embeddingModel(),
                request.embeddingModelSource(),
                request.embeddingArchiveId(),
                request.rerankingEnabled(),
                request.rerankerType(),
                request.crossEncoderModel(),
                request.crossEncoderModelSource(),
                request.crossEncoderArchiveId(),
                request.rerankTopK(),
                request.mmrLambda()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(sheet));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to create fact sheet: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Create a new fact sheet derived from an existing one.
     */
    @PostMapping("/{id}/derive")
    public ResponseEntity<FactSheetDto> deriveSheet(
            @PathVariable("id") Long id,
            @RequestBody DeriveFactSheetRequest request) {
        try {
            FactSheet sheet = factSheetService.deriveSheet(id, request.name(), request.description());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(sheet));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to derive fact sheet: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update a fact sheet.
     */
    @PutMapping("/{id}")
    public ResponseEntity<FactSheetDto> updateSheet(
            @PathVariable("id") Long id,
            @RequestBody UpdateFactSheetRequest request) {
        try {
            // Determine if index paths are being updated (check if either field is provided)
            boolean updateIndexPaths = request.vectorStorePath() != null || request.keywordIndexPath() != null;
            // Determine if model config is being updated (check if any model field is provided)
            boolean updateModelConfig = request.embeddingModel() != null || request.embeddingModelSource() != null ||
                    request.rerankingEnabled() != null || request.rerankerType() != null ||
                    request.crossEncoderModel() != null || request.crossEncoderModelSource() != null;

            FactSheet sheet = factSheetService.updateSheet(
                id,
                request.name(),
                request.description(),
                request.color(),
                request.icon(),
                request.vectorStorePath(),
                request.keywordIndexPath(),
                updateIndexPaths,
                // Model configuration
                request.embeddingModel(),
                request.embeddingModelSource(),
                request.embeddingArchiveId(),
                request.rerankingEnabled(),
                request.rerankerType(),
                request.crossEncoderModel(),
                request.crossEncoderModelSource(),
                request.crossEncoderArchiveId(),
                request.rerankTopK(),
                request.mmrLambda(),
                updateModelConfig
            );
            return ResponseEntity.ok(toDto(sheet));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update fact sheet: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a fact sheet.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSheet(@PathVariable("id") Long id) {
        try {
            factSheetService.deleteSheet(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to delete fact sheet: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Activate a fact sheet (switch to it).
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<FactSheetDto> activateSheet(@PathVariable("id") Long id) {
        try {
            FactSheet sheet = factSheetService.activateSheet(id);
            return ResponseEntity.ok(toDto(sheet));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to activate fact sheet: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get all facts in the active sheet.
     */
    @GetMapping("/active/facts")
    public ResponseEntity<List<FactDto>> getActiveFacts() {
        List<Fact> facts = factSheetService.getActiveFacts();
        List<FactDto> dtos = facts.stream()
            .map(this::toFactDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get all facts in a specific sheet.
     */
    @GetMapping("/{id}/facts")
    public ResponseEntity<List<FactDto>> getSheetFacts(@PathVariable("id") Long id) {
        List<Fact> facts = factSheetService.getFactsBySheetId(id);
        List<FactDto> dtos = facts.stream()
            .map(this::toFactDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a specific fact.
     */
    @GetMapping("/facts/{factId}")
    public ResponseEntity<FactDto> getFact(@PathVariable("factId") Long factId) {
        return factSheetService.getFactById(factId)
            .map(fact -> ResponseEntity.ok(toFactDto(fact)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a fact (title, notes, tags).
     */
    @PutMapping("/facts/{factId}")
    public ResponseEntity<FactDto> updateFact(
            @PathVariable("factId") Long factId,
            @RequestBody UpdateFactRequest request) {
        try {
            Fact fact = factSheetService.updateFact(factId, request.title(), request.notes(), request.tags());
            return ResponseEntity.ok(toFactDto(fact));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a fact.
     */
    @DeleteMapping("/facts/{factId}")
    public ResponseEntity<Void> deleteFact(@PathVariable("factId") Long factId) {
        try {
            factSheetService.deleteFact(factId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete multiple facts.
     */
    @DeleteMapping("/facts")
    public ResponseEntity<Void> deleteFacts(@RequestBody DeleteFactsRequest request) {
        factSheetService.deleteFacts(request.factIds());
        return ResponseEntity.noContent().build();
    }

    /**
     * Copy facts from one sheet to another.
     */
    @PostMapping("/{sourceId}/copy-to/{targetId}")
    public ResponseEntity<Map<String, Integer>> copyFacts(
            @PathVariable("sourceId") Long sourceId,
            @PathVariable("targetId") Long targetId,
            @RequestBody CopyFactsRequest request) {
        try {
            int count = factSheetService.copyFacts(sourceId, targetId, request.factIds());
            return ResponseEntity.ok(Map.of("copiedCount", count));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to copy facts: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Move facts from one sheet to another.
     */
    @PostMapping("/{sourceId}/move-to/{targetId}")
    public ResponseEntity<Map<String, Integer>> moveFacts(
            @PathVariable("sourceId") Long sourceId,
            @PathVariable("targetId") Long targetId,
            @RequestBody CopyFactsRequest request) {
        try {
            int count = factSheetService.moveFacts(sourceId, targetId, request.factIds());
            return ResponseEntity.ok(Map.of("movedCount", count));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to move facts: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Search facts in the active sheet.
     */
    @GetMapping("/active/facts/search")
    public ResponseEntity<List<FactDto>> searchFacts(@RequestParam(name = "q") String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (q.length() > 500) {
            return ResponseEntity.badRequest().build();
        }
        List<Fact> facts = factSheetService.searchFacts(q);
        List<FactDto> dtos = facts.stream()
            .map(this::toFactDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // INDEXING STATUS OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get indexing statistics for the active sheet.
     */
    @GetMapping("/active/indexing-stats")
    public ResponseEntity<IndexingStatsDto> getActiveSheetIndexingStats() {
        var stats = factSheetService.getActiveSheetIndexingStats();
        return ResponseEntity.ok(toIndexingStatsDto(stats));
    }

    /**
     * Get indexing statistics for a specific sheet.
     */
    @GetMapping("/{id}/indexing-stats")
    public ResponseEntity<IndexingStatsDto> getSheetIndexingStats(@PathVariable("id") Long id) {
        var stats = factSheetService.getIndexingStats(id);
        return ResponseEntity.ok(toIndexingStatsDto(stats));
    }

    /**
     * Get unindexed facts in the active sheet.
     */
    @GetMapping("/active/facts/unindexed")
    public ResponseEntity<List<FactDto>> getUnindexedActiveFacts() {
        List<Fact> facts = factSheetService.getUnindexedActiveFacts();
        List<FactDto> dtos = facts.stream()
            .map(this::toFactDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get unindexed facts in a specific sheet.
     */
    @GetMapping("/{id}/facts/unindexed")
    public ResponseEntity<List<FactDto>> getUnindexedFacts(@PathVariable("id") Long id) {
        List<Fact> facts = factSheetService.getUnindexedFacts(id);
        List<FactDto> dtos = facts.stream()
            .map(this::toFactDto)
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Mark specific facts as indexed (used after successful indexing).
     */
    @PostMapping("/facts/mark-indexed")
    public ResponseEntity<Map<String, Integer>> markFactsAsIndexed(@RequestBody MarkIndexedRequest request) {
        int count = factSheetService.markFactsAsIndexed(request.factIds());
        return ResponseEntity.ok(Map.of("markedCount", count));
    }

    /**
     * Mark a single fact as indexed.
     */
    @PostMapping("/facts/{factId}/mark-indexed")
    public ResponseEntity<Void> markFactAsIndexed(@PathVariable("factId") Long factId) {
        boolean marked = factSheetService.markFactAsIndexed(factId);
        if (marked) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Get facts pending indexing with their file paths.
     * This endpoint returns the facts that need indexing so the frontend
     * can trigger individual ingest operations for each.
     */
    @GetMapping("/active/facts/pending-indexing")
    public ResponseEntity<List<FactForIndexingDto>> getFactsPendingIndexing() {
        List<Fact> unindexedFacts = factSheetService.getUnindexedActiveFacts();
        List<FactForIndexingDto> dtos = unindexedFacts.stream()
            .map(f -> new FactForIndexingDto(
                f.getId(),
                f.getFileName(),
                f.getFilePath(),
                f.getSourceType().name(),
                f.getExtension(),
                f.getMimeType(),
                f.getSizeBytes()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get facts pending indexing for a specific sheet.
     */
    @GetMapping("/{id}/facts/pending-indexing")
    public ResponseEntity<List<FactForIndexingDto>> getSheetFactsPendingIndexing(@PathVariable("id") Long id) {
        List<Fact> unindexedFacts = factSheetService.getUnindexedFacts(id);
        List<FactForIndexingDto> dtos = unindexedFacts.stream()
            .map(f -> new FactForIndexingDto(
                f.getId(),
                f.getFileName(),
                f.getFilePath(),
                f.getSourceType().name(),
                f.getExtension(),
                f.getMimeType(),
                f.getSizeBytes()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    private IndexingStatsDto toIndexingStatsDto(FactSheetService.IndexingStats stats) {
        return new IndexingStatsDto(
            stats.totalFacts(),
            stats.indexedFacts(),
            stats.unindexedFacts(),
            stats.indexedPercentage(),
            stats.allIndexed(),
            stats.hasUnindexed()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODEL STATUS OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get model load status for the active fact sheet.
     * Shows whether the configured embedding model and cross-encoder are loaded.
     */
    @GetMapping("/active/model-status")
    public ResponseEntity<FactSheetModelStatusDto> getActiveSheetModelStatus() {
        FactSheet sheet = factSheetService.getActiveSheet();
        return ResponseEntity.ok(buildModelStatus(sheet));
    }

    /**
     * Get model load status for a specific fact sheet.
     */
    @GetMapping("/{id}/model-status")
    public ResponseEntity<FactSheetModelStatusDto> getSheetModelStatus(@PathVariable("id") Long id) {
        return factSheetService.getSheetById(id)
            .map(sheet -> ResponseEntity.ok(buildModelStatus(sheet)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Load or reload the embedding model for a fact sheet.
     */
    @PostMapping("/{id}/models/embedding/load")
    public ResponseEntity<Map<String, Object>> loadEmbeddingModel(@PathVariable("id") Long id) {
        Map<String, Object> response = new LinkedHashMap<>();

        return factSheetService.getSheetById(id).map(sheet -> {
            if (embeddingModel == null) {
                response.put("success", false);
                response.put("error", "Embedding model service not available");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }

            try {
                String targetModel = sheet.getEmbeddingModel();
                if (targetModel == null || targetModel.isBlank()) {
                    // Use current model or default
                    targetModel = embeddingModel.getActiveModelId();
                }

                boolean success = embeddingModel.reloadModel(targetModel);
                response.put("success", success);
                response.put("modelId", embeddingModel.getActiveModelId());
                response.put("initialized", embeddingModel.isInitialized());
                response.put("dimensions", embeddingModel.dimensions());

                return ResponseEntity.ok(response);
            } catch (Exception e) {
                logger.error("Failed to load embedding model for sheet {}: {}", id, e.getMessage(), e);
                response.put("success", false);
                response.put("error", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        }).orElseGet(() -> {
            response.put("success", false);
            response.put("error", "Fact sheet not found: " + id);
            return ResponseEntity.notFound().build();
        });
    }

    /**
     * Check if a cross-encoder model is available (in registry or built-in).
     * Cross-encoders load on-demand during reranking, so we just check availability.
     */
    @PostMapping("/{id}/models/cross-encoder/check")
    public ResponseEntity<Map<String, Object>> checkCrossEncoder(@PathVariable("id") Long id) {
        Map<String, Object> response = new LinkedHashMap<>();

        return factSheetService.getSheetById(id).map(sheet -> {
            try {
                String targetModel = sheet.getCrossEncoderModel();
                if (targetModel == null || targetModel.isBlank()) {
                    response.put("success", false);
                    response.put("error", "No cross-encoder model configured for this fact sheet");
                    response.put("available", false);
                    return ResponseEntity.badRequest().body(response);
                }

                // Check if model is available in registry or built-in
                boolean inRegistry = registryManager.isCrossEncoderModelAvailable(targetModel);
                boolean inBuiltIn = ModelConstants.isCrossEncoderModelAvailable(targetModel);
                boolean available = inRegistry || inBuiltIn;

                response.put("success", true);
                response.put("modelId", targetModel);
                response.put("available", available);
                response.put("source", inRegistry ? "registry" : (inBuiltIn ? "built-in" : "not_found"));
                response.put("message", available ? "Model is available and will load on demand during reranking"
                        : "Model not found in registry or built-in catalog");

                return ResponseEntity.ok(response);
            } catch (Exception e) {
                logger.error("Failed to check cross-encoder for sheet {}: {}", id, e.getMessage(), e);
                response.put("success", false);
                response.put("error", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        }).orElseGet(() -> {
            response.put("success", false);
            response.put("error", "Fact sheet not found: " + id);
            return ResponseEntity.notFound().build();
        });
    }

    private FactSheetModelStatusDto buildModelStatus(FactSheet sheet) {
        // Embedding model status
        EmbeddingModelStatusDto embeddingStatus = buildEmbeddingStatus(sheet);

        // Cross-encoder/reranker status
        CrossEncoderStatusDto crossEncoderStatus = buildCrossEncoderStatus(sheet);

        return new FactSheetModelStatusDto(
            sheet.getId(),
            sheet.getName(),
            embeddingStatus,
            crossEncoderStatus
        );
    }

    private EmbeddingModelStatusDto buildEmbeddingStatus(FactSheet sheet) {
        String configuredModel = sheet.getEmbeddingModel();
        String configuredSource = sheet.getEmbeddingModelSource();
        String configuredArchiveId = sheet.getEmbeddingArchiveId();

        boolean available = embeddingModel != null;
        boolean initialized = available && embeddingModel.isInitialized();
        String activeModel = initialized ? embeddingModel.getActiveModelId() : null;
        String activeSource = initialized ? embeddingModel.getModelSource().name() : null;
        Integer dimensions = initialized ? embeddingModel.dimensions() : null;

        // Loading progress tracking
        boolean loading = available && embeddingModel.isLoading();
        String loadingPhase = available ? embeddingModel.getLoadingPhase() : "IDLE";
        String loadingMessage = available ? embeddingModel.getLoadingMessage() : null;
        long loadingElapsedMs = available ? embeddingModel.getLoadingElapsedMs() : 0;

        // Check if the loaded model matches the configured model
        boolean matchesConfig = false;
        if (initialized && configuredModel != null && !configuredModel.isBlank()) {
            matchesConfig = configuredModel.equals(activeModel);
        } else if (initialized && (configuredModel == null || configuredModel.isBlank())) {
            // No specific model configured, any loaded model is acceptable
            matchesConfig = true;
        }

        return new EmbeddingModelStatusDto(
            configuredModel,
            configuredSource,
            configuredArchiveId,
            available,
            initialized,
            activeModel,
            activeSource,
            dimensions,
            matchesConfig,
            loading,
            loadingPhase,
            loadingMessage,
            loadingElapsedMs
        );
    }

    private CrossEncoderStatusDto buildCrossEncoderStatus(FactSheet sheet) {
        boolean rerankingEnabled = Boolean.TRUE.equals(sheet.getRerankingEnabled());
        String rerankerType = sheet.getRerankerType();
        String configuredModel = sheet.getCrossEncoderModel();
        String configuredSource = sheet.getCrossEncoderModelSource();
        String configuredArchiveId = sheet.getCrossEncoderArchiveId();
        Integer rerankTopK = sheet.getRerankTopK();

        boolean available = false;
        String modelSource = null;
        boolean matchesConfig = false;
        boolean loaded = false;
        String loadStatus = "not_configured";

        // Check if reranking is disabled
        if (!rerankingEnabled) {
            loadStatus = "disabled";
            matchesConfig = true;
            available = true;
        }
        // Check cross-encoder status if reranking is enabled and type is cross_encoder
        else if ("cross_encoder".equalsIgnoreCase(rerankerType)) {
            if (configuredModel == null || configuredModel.isBlank()) {
                loadStatus = "not_configured";
            } else {
                // Check if model is available in registry or built-in
                boolean inRegistry = registryManager.isCrossEncoderModelAvailable(configuredModel);
                boolean inBuiltIn = ModelConstants.isCrossEncoderModelAvailable(configuredModel);
                available = inRegistry || inBuiltIn;
                modelSource = inRegistry ? "registry" : (inBuiltIn ? "built-in" : null);

                if (available) {
                    // Cross-encoders load on-demand during first rerank operation
                    // We can't easily check if it's loaded without actually loading it
                    // So we report "available" which means ready to load on first use
                    loadStatus = "available_on_demand";
                    matchesConfig = true;
                    // Note: Cross-encoders are lazily loaded, so 'loaded' stays false
                    // until actually used for reranking. This is by design.
                } else {
                    loadStatus = "not_available";
                }
            }
        } else {
            // Other reranker types (RRF, MMR, etc.) don't need neural models
            loadStatus = "not_applicable";
            matchesConfig = true;
            available = true;
            loaded = true; // Non-neural rerankers are always "loaded"
        }

        return new CrossEncoderStatusDto(
            rerankingEnabled,
            rerankerType,
            configuredModel,
            configuredSource,
            configuredArchiveId,
            rerankTopK,
            available,
            modelSource,
            matchesConfig,
            loaded,
            loadStatus
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EMBEDDING MODEL MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get detailed embedding model info for a fact sheet.
     * Includes model mismatch detection and reindex status.
     */
    @GetMapping("/{id}/embedding-model-info")
    public ResponseEntity<EmbeddingModelInfoDto> getEmbeddingModelInfo(@PathVariable("id") Long id) {
        return factSheetService.getSheetById(id)
            .map(sheet -> {
                var info = factSheetService.getEmbeddingModelInfo(id);
                return ResponseEntity.ok(new EmbeddingModelInfoDto(
                    info.configuredModel(),
                    info.modelSource(),
                    info.archiveId(),
                    info.indexedWithModel(),
                    info.indexedAt(),
                    info.indexedFactCount(),
                    info.totalFactCount(),
                    info.needsReindex(),
                    info.hasModelMismatch(),
                    info.indexedPercentage()
                ));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update the embedding model for a fact sheet.
     * <p>
     * IMPORTANT: If the fact sheet has indexed documents and the model is changing,
     * this will mark all facts as unindexed (requiring a full reindex).
     * The response includes details about what happened.
     * </p>
     */
    @PutMapping("/{id}/embedding-model")
    public ResponseEntity<EmbeddingModelUpdateResponseDto> updateEmbeddingModel(
            @PathVariable("id") Long id,
            @RequestBody UpdateEmbeddingModelRequest request) {
        try {
            var result = factSheetService.updateEmbeddingModel(
                id,
                request.modelId(),
                request.modelSource(),
                request.archiveId(),
                request.forceReindex() != null && request.forceReindex()
            );

            String message;
            if (result.reindexRequired()) {
                message = String.format(
                    "Embedding model changed from '%s' to '%s'. %d facts marked as unindexed - " +
                    "a full reindex is required before search will work correctly.",
                    result.previousModel(), result.newModel(), result.affectedFactCount());
            } else if (result.modelChanged()) {
                message = String.format(
                    "Embedding model changed from '%s' to '%s'. No reindex needed (no indexed facts).",
                    result.previousModel(), result.newModel());
            } else {
                message = "Embedding model configuration updated (model unchanged).";
            }

            return ResponseEntity.ok(new EmbeddingModelUpdateResponseDto(
                true,
                message,
                result.previousModel(),
                result.newModel(),
                result.modelChanged(),
                result.reindexRequired(),
                result.affectedFactCount(),
                null
            ));
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update embedding model for sheet {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(new EmbeddingModelUpdateResponseDto(
                false, null, null, null, false, false, 0, e.getMessage()));
        }
    }

    /**
     * Check if switching to a new embedding model would require reindexing.
     * Use this before updating to warn the user about consequences.
     */
    @PostMapping("/{id}/embedding-model/check-change")
    public ResponseEntity<EmbeddingModelChangeCheckDto> checkEmbeddingModelChange(
            @PathVariable("id") Long id,
            @RequestBody CheckEmbeddingModelChangeRequest request) {
        return factSheetService.getSheetById(id)
            .map(sheet -> {
                String currentModel = sheet.getEmbeddingModel();
                String indexedWithModel = sheet.getIndexedWithModel();
                long indexedCount = factSheetService.getIndexedCount(id);
                long totalCount = factSheetService.getFactCount(id);

                boolean modelDiffers = request.newModelId() != null
                    && !request.newModelId().equals(currentModel);
                boolean wouldRequireReindex = modelDiffers && indexedCount > 0;

                String warningMessage = null;
                if (wouldRequireReindex) {
                    warningMessage = String.format(
                        "Changing the embedding model from '%s' to '%s' will invalidate the existing " +
                        "vector store. All %d indexed documents will need to be reindexed with the new model. " +
                        "This may take significant time depending on the number of documents.",
                        currentModel != null ? currentModel : "(default)",
                        request.newModelId(),
                        indexedCount);
                }

                return ResponseEntity.ok(new EmbeddingModelChangeCheckDto(
                    currentModel,
                    request.newModelId(),
                    indexedWithModel,
                    indexedCount,
                    totalCount,
                    modelDiffers,
                    wouldRequireReindex,
                    warningMessage
                ));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Record that indexing completed with a specific model.
     * Called after vector population completes successfully.
     */
    @PostMapping("/{id}/set-indexed-model")
    public ResponseEntity<Map<String, Object>> setIndexedWithModel(
            @PathVariable("id") Long id,
            @RequestBody SetIndexedModelRequest request) {
        try {
            factSheetService.setIndexedWithModel(id, request.modelId());
            return ResponseEntity.ok(Map.of(
                "success", true,
                "factSheetId", id,
                "indexedWithModel", request.modelId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Check if a fact sheet needs reindexing due to model mismatch.
     */
    @GetMapping("/{id}/needs-reindex")
    public ResponseEntity<Map<String, Object>> checkNeedsReindex(@PathVariable("id") Long id) {
        return factSheetService.getSheetById(id)
            .map(sheet -> {
                boolean needsReindex = factSheetService.needsReindex(id);
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("factSheetId", id);
                result.put("factSheetName", sheet.getName());
                result.put("needsReindex", needsReindex);
                result.put("configuredModel", sheet.getEmbeddingModel() != null ? sheet.getEmbeddingModel() : "");
                result.put("indexedWithModel", sheet.getIndexedWithModel() != null ? sheet.getIndexedWithModel() : "");
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DTOs AND MAPPING
    // ═══════════════════════════════════════════════════════════════════════════════

    private FactSheetDto toDto(FactSheet sheet) {
        long factCount = factSheetService.getFactCount(sheet.getId());
        long totalSize = factSheetService.getTotalSize(sheet.getId());
        long indexedCount = factSheetService.getIndexedCount(sheet.getId());
        long unindexedCount = factSheetService.getUnindexedCount(sheet.getId());
        boolean needsReindex = factSheetService.needsReindex(sheet.getId());

        return new FactSheetDto(
            sheet.getId(),
            sheet.getName(),
            sheet.getDescription(),
            sheet.getIsActive(),
            sheet.getDerivedFromId(),
            sheet.getColor(),
            sheet.getIcon(),
            sheet.getVectorStorePath(),
            sheet.getKeywordIndexPath(),
            // Retrieval configuration
            sheet.getEmbeddingModel(),
            sheet.getEmbeddingModelSource(),
            sheet.getEmbeddingArchiveId(),
            // Model tracking
            sheet.getIndexedWithModel(),
            sheet.getIndexedAt(),
            needsReindex,
            // Reranking configuration
            sheet.getRerankingEnabled(),
            sheet.getRerankerType(),
            sheet.getCrossEncoderModel(),
            sheet.getCrossEncoderModelSource(),
            sheet.getCrossEncoderArchiveId(),
            sheet.getRerankTopK(),
            sheet.getMmrLambda(),
            // Stats
            factCount,
            indexedCount,
            unindexedCount,
            totalSize,
            sheet.getCreatedAt(),
            sheet.getUpdatedAt()
        );
    }

    private FactDto toFactDto(Fact fact) {
        return new FactDto(
            fact.getId(),
            fact.getFactSheet().getId(),
            fact.getFileName(),
            fact.getFilePath(),
            fact.getChecksum(),
            fact.getSourceType().name(),
            fact.getExtension(),
            fact.getMimeType(),
            fact.getSizeBytes(),
            fact.getViewMode().name(),
            fact.getCanPreview(),
            fact.getTitle(),
            fact.getNotes(),
            fact.getTags(),
            fact.getSourceUrl(),
            fact.getIndexed(),
            fact.getIndexedAt(),
            fact.getCreatedAt(),
            fact.getUpdatedAt(),
            fact.getLastAccessedAt()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // REQUEST/RESPONSE RECORDS
    // ═══════════════════════════════════════════════════════════════════════════════

    public record FactSheetDto(
        Long id,
        String name,
        String description,
        Boolean isActive,
        Long derivedFromId,
        String color,
        String icon,
        String vectorStorePath,
        String keywordIndexPath,
        // Retrieval configuration
        String embeddingModel,
        String embeddingModelSource,
        String embeddingArchiveId,
        // Model tracking - what model was actually used for indexing
        String indexedWithModel,
        Instant indexedAt,
        boolean needsReindex,
        // Reranking configuration
        Boolean rerankingEnabled,
        String rerankerType,
        String crossEncoderModel,
        String crossEncoderModelSource,
        String crossEncoderArchiveId,
        Integer rerankTopK,
        Double mmrLambda,
        // Stats
        long factCount,
        long indexedCount,
        long unindexedCount,
        long totalSizeBytes,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record FactDto(
        Long id,
        Long factSheetId,
        String fileName,
        String filePath,
        String checksum,
        String sourceType,
        String extension,
        String mimeType,
        Long sizeBytes,
        String viewMode,
        Boolean canPreview,
        String title,
        String notes,
        String tags,
        String sourceUrl,
        Boolean indexed,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastAccessedAt
    ) {}

    public record CreateFactSheetRequest(
        String name,
        String description,
        String color,
        String icon,
        String vectorStorePath,
        String keywordIndexPath,
        // Retrieval configuration
        String embeddingModel,
        String embeddingModelSource,
        String embeddingArchiveId,
        // Reranking configuration
        Boolean rerankingEnabled,
        String rerankerType,
        String crossEncoderModel,
        String crossEncoderModelSource,
        String crossEncoderArchiveId,
        Integer rerankTopK,
        Double mmrLambda
    ) {}

    public record DeriveFactSheetRequest(
        String name,
        String description
    ) {}

    public record UpdateFactSheetRequest(
        String name,
        String description,
        String color,
        String icon,
        String vectorStorePath,
        String keywordIndexPath,
        // Retrieval configuration
        String embeddingModel,
        String embeddingModelSource,
        String embeddingArchiveId,
        // Reranking configuration
        Boolean rerankingEnabled,
        String rerankerType,
        String crossEncoderModel,
        String crossEncoderModelSource,
        String crossEncoderArchiveId,
        Integer rerankTopK,
        Double mmrLambda
    ) {}

    public record UpdateFactRequest(
        String title,
        String notes,
        String tags
    ) {}

    public record DeleteFactsRequest(
        Set<Long> factIds
    ) {}

    public record CopyFactsRequest(
        Set<Long> factIds
    ) {}

    public record MarkIndexedRequest(
        Set<Long> factIds
    ) {}

    public record IndexingStatsDto(
        long totalFacts,
        long indexedFacts,
        long unindexedFacts,
        double indexedPercentage,
        boolean allIndexed,
        boolean hasUnindexed
    ) {}

    public record FactForIndexingDto(
        Long id,
        String fileName,
        String filePath,
        String sourceType,
        String extension,
        String mimeType,
        Long sizeBytes
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // MODEL STATUS DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    public record FactSheetModelStatusDto(
        Long factSheetId,
        String factSheetName,
        EmbeddingModelStatusDto embedding,
        CrossEncoderStatusDto crossEncoder
    ) {}

    public record EmbeddingModelStatusDto(
        String configuredModel,
        String configuredSource,
        String configuredArchiveId,
        boolean available,
        boolean initialized,
        String activeModel,
        String activeSource,
        Integer dimensions,
        boolean matchesConfig,
        // Loading progress tracking
        boolean loading,
        String loadingPhase,
        String loadingMessage,
        long loadingElapsedMs
    ) {}

    public record CrossEncoderStatusDto(
        boolean rerankingEnabled,
        String rerankerType,
        String configuredModel,
        String configuredSource,
        String configuredArchiveId,
        Integer rerankTopK,
        boolean available,
        String modelFoundIn,  // "registry", "built-in", or null
        boolean matchesConfig,
        boolean loaded,       // true if the model has been loaded for inference
        String loadStatus     // "not_configured", "not_available", "available_not_loaded", "loaded", "disabled"
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // EMBEDDING MODEL MANAGEMENT DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Detailed information about a fact sheet's embedding model configuration and status.
     */
    public record EmbeddingModelInfoDto(
        String configuredModel,
        String modelSource,
        String archiveId,
        String indexedWithModel,
        Instant indexedAt,
        long indexedFactCount,
        long totalFactCount,
        boolean needsReindex,
        boolean hasModelMismatch,
        double indexedPercentage
    ) {}

    /**
     * Request to update a fact sheet's embedding model.
     */
    public record UpdateEmbeddingModelRequest(
        String modelId,
        String modelSource,
        String archiveId,
        Boolean forceReindex
    ) {}

    /**
     * Response from updating a fact sheet's embedding model.
     */
    public record EmbeddingModelUpdateResponseDto(
        boolean success,
        String message,
        String previousModel,
        String newModel,
        boolean modelChanged,
        boolean reindexRequired,
        long affectedFactCount,
        String error
    ) {}

    /**
     * Request to check what would happen if embedding model is changed.
     */
    public record CheckEmbeddingModelChangeRequest(
        String newModelId
    ) {}

    /**
     * Response from checking an embedding model change.
     */
    public record EmbeddingModelChangeCheckDto(
        String currentModel,
        String proposedModel,
        String indexedWithModel,
        long indexedFactCount,
        long totalFactCount,
        boolean modelDiffers,
        boolean wouldRequireReindex,
        String warningMessage
    ) {}

    /**
     * Request to record which model was used for indexing.
     */
    public record SetIndexedModelRequest(
        String modelId
    ) {}
}
