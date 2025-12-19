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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public FactSheetController(FactSheetService factSheetService) {
        this.factSheetService = factSheetService;
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
    public ResponseEntity<FactSheetDto> getSheet(@PathVariable Long id) {
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
            @PathVariable Long id,
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
            @PathVariable Long id,
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
    public ResponseEntity<Void> deleteSheet(@PathVariable Long id) {
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
    public ResponseEntity<FactSheetDto> activateSheet(@PathVariable Long id) {
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
    public ResponseEntity<List<FactDto>> getSheetFacts(@PathVariable Long id) {
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
    public ResponseEntity<FactDto> getFact(@PathVariable Long factId) {
        return factSheetService.getFactById(factId)
            .map(fact -> ResponseEntity.ok(toFactDto(fact)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a fact (title, notes, tags).
     */
    @PutMapping("/facts/{factId}")
    public ResponseEntity<FactDto> updateFact(
            @PathVariable Long factId,
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
    public ResponseEntity<Void> deleteFact(@PathVariable Long factId) {
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
            @PathVariable Long sourceId,
            @PathVariable Long targetId,
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
            @PathVariable Long sourceId,
            @PathVariable Long targetId,
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
    public ResponseEntity<List<FactDto>> searchFacts(@RequestParam String q) {
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
    public ResponseEntity<IndexingStatsDto> getSheetIndexingStats(@PathVariable Long id) {
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
    public ResponseEntity<List<FactDto>> getUnindexedFacts(@PathVariable Long id) {
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
    public ResponseEntity<Void> markFactAsIndexed(@PathVariable Long factId) {
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
    public ResponseEntity<List<FactForIndexingDto>> getSheetFactsPendingIndexing(@PathVariable Long id) {
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
    // DTOs AND MAPPING
    // ═══════════════════════════════════════════════════════════════════════════════

    private FactSheetDto toDto(FactSheet sheet) {
        long factCount = factSheetService.getFactCount(sheet.getId());
        long totalSize = factSheetService.getTotalSize(sheet.getId());
        long indexedCount = factSheetService.getIndexedCount(sheet.getId());
        long unindexedCount = factSheetService.getUnindexedCount(sheet.getId());

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
}
