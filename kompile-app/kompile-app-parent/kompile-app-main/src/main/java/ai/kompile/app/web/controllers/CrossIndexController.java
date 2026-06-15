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

import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.OverallIndexStatus;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.app.services.CrossIndexTrackingService;
import ai.kompile.app.services.CrossIndexTrackingService.CrossIndexStatistics;
import ai.kompile.app.services.CrossIndexTrackingService.CrossIndexSummary;
import ai.kompile.app.services.DocumentFreshnessService;
import ai.kompile.app.services.IndexSyncService;
import ai.kompile.app.services.IndexSyncService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller for cross-index tracking and synchronization.
 * Provides endpoints to query cross-index status and trigger sync operations.
 */
@RestController
@RequestMapping("/api/cross-index")
@RequiredArgsConstructor
@Slf4j
public class CrossIndexController {

    private final CrossIndexTrackingService trackingService;
    private final IndexSyncService syncService;
    private final FactSheetService factSheetService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DocumentFreshnessService freshnessService;

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/cross-index/status
     * Get cross-index status summary for active fact sheet.
     */
    @GetMapping("/status")
    public ResponseEntity<CrossIndexSummaryResponse> getStatus() {
        var activeSheet = factSheetService.getActiveSheet();
        if (activeSheet == null) {
            return ResponseEntity.ok(new CrossIndexSummaryResponse(
                    null, null, 0, 0, 0, 0, 0, 0, 0, false, null));
        }

        CrossIndexSummary summary = trackingService.getSummary(activeSheet.getId(), activeSheet.getName());
        CrossIndexStatistics stats = trackingService.getStatistics(activeSheet.getId());

        return ResponseEntity.ok(new CrossIndexSummaryResponse(
                activeSheet.getId(),
                activeSheet.getName(),
                stats.totalDocuments(),
                stats.fullyIndexedDocuments(),
                stats.partiallyIndexedDocuments(),
                stats.notIndexedDocuments(),
                summary.documentsNeedingSync(),
                summary.passagesMissingFromVector(),
                summary.passagesMissingFromGraph(),
                summary.autoSyncEnabled(),
                summary.lastSyncCheck()
        ));
    }

    /**
     * GET /api/cross-index/status/{factSheetId}
     * Get cross-index status summary for a specific fact sheet.
     */
    @GetMapping("/status/{factSheetId}")
    public ResponseEntity<CrossIndexSummaryResponse> getStatusByFactSheet(@PathVariable Long factSheetId) {
        var factSheet = factSheetService.getSheetById(factSheetId).orElse(null);
        if (factSheet == null) {
            return ResponseEntity.notFound().build();
        }

        CrossIndexSummary summary = trackingService.getSummary(factSheetId, factSheet.getName());
        CrossIndexStatistics stats = trackingService.getStatistics(factSheetId);

        return ResponseEntity.ok(new CrossIndexSummaryResponse(
                factSheetId,
                factSheet.getName(),
                stats.totalDocuments(),
                stats.fullyIndexedDocuments(),
                stats.partiallyIndexedDocuments(),
                stats.notIndexedDocuments(),
                summary.documentsNeedingSync(),
                summary.passagesMissingFromVector(),
                summary.passagesMissingFromGraph(),
                summary.autoSyncEnabled(),
                summary.lastSyncCheck()
        ));
    }

    /**
     * GET /api/cross-index/statistics/{factSheetId}
     * Get detailed statistics for a fact sheet.
     */
    @GetMapping("/statistics/{factSheetId}")
    public ResponseEntity<CrossIndexStatisticsResponse> getStatistics(@PathVariable Long factSheetId) {
        CrossIndexStatistics stats = trackingService.getStatistics(factSheetId);
        Map<OverallIndexStatus, Long> distribution = trackingService.getStatusDistribution(factSheetId);

        return ResponseEntity.ok(new CrossIndexStatisticsResponse(
                factSheetId,
                new DocumentStats(
                        stats.totalDocuments(),
                        stats.fullyIndexedDocuments(),
                        stats.partiallyIndexedDocuments(),
                        stats.notIndexedDocuments(),
                        stats.failedDocuments()
                ),
                new PassageStats(
                        stats.totalPassages(),
                        stats.keywordIndexedPassages(),
                        stats.vectorIndexedPassages(),
                        stats.graphIndexedPassages()
                ),
                distribution
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DOCUMENT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/cross-index/documents
     * List documents with filtering and pagination.
     */
    @GetMapping("/documents")
    public ResponseEntity<Page<IndexedDocumentResponse>> listDocuments(
            @RequestParam Long factSheetId,
            @RequestParam(required = false) OverallIndexStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc") ?
                Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<IndexedDocument> documents;

        if (search != null && !search.isBlank()) {
            if (status != null) {
                documents = trackingService.searchDocumentsByStatus(factSheetId, search, status, pageable);
            } else {
                documents = trackingService.searchDocuments(factSheetId, search, pageable);
            }
        } else if (status != null) {
            documents = trackingService.listDocumentsByStatus(factSheetId, status, pageable);
        } else {
            documents = trackingService.listDocuments(factSheetId, pageable);
        }

        return ResponseEntity.ok(documents.map(this::toDocumentResponse));
    }

    /**
     * GET /api/cross-index/documents/{documentId}
     * Get detailed document status including all passages.
     */
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<IndexedDocumentDetailResponse> getDocument(@PathVariable Long documentId) {
        return trackingService.findDocument(documentId)
                .map(doc -> {
                    List<IndexedPassage> passages = trackingService.listPassagesOrdered(documentId);
                    return ResponseEntity.ok(new IndexedDocumentDetailResponse(
                            toDocumentResponse(doc),
                            passages.stream().map(this::toPassageResponse).toList(),
                            calculatePassageStatusCounts(passages)
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/cross-index/documents/needing-sync
     * List documents needing synchronization.
     */
    @GetMapping("/documents/needing-sync")
    public ResponseEntity<Page<IndexedDocumentResponse>> getDocumentsNeedingSync(
            @RequestParam Long factSheetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
        Page<IndexedDocument> documents = trackingService.findDocumentsNeedingSync(factSheetId, pageable);

        return ResponseEntity.ok(documents.map(this::toDocumentResponse));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PASSAGE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/cross-index/passages
     * List passages for a document.
     */
    @GetMapping("/passages")
    public ResponseEntity<Page<IndexedPassageResponse>> listPassages(
            @RequestParam Long documentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("chunkIndex").ascending());
        Page<IndexedPassage> passages = trackingService.listPassages(documentId, pageable);

        return ResponseEntity.ok(passages.map(this::toPassageResponse));
    }

    /**
     * POST /api/cross-index/passages/check-status
     * Check cross-index status for a set of chunk IDs.
     */
    @PostMapping("/passages/check-status")
    public ResponseEntity<Map<String, PassageStatusResponse>> checkPassageStatus(
            @RequestBody List<String> chunkIds) {

        Map<String, Boolean> vectorStatus = trackingService.checkVectorIndexStatus(chunkIds);

        // Build response map
        Map<String, PassageStatusResponse> response = new java.util.HashMap<>();
        for (String chunkId : chunkIds) {
            trackingService.findPassage(chunkId).ifPresent(passage -> {
                response.put(chunkId, new PassageStatusResponse(
                        chunkId,
                        passage.isInKeywordIndex(),
                        passage.isInVectorStore(),
                        passage.isInKnowledgeGraph(),
                        passage.isFullyIndexed()
                ));
            });
        }

        return ResponseEntity.ok(response);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SYNC ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/cross-index/sync/vector-store
     * Trigger synchronization to vector store.
     */
    @PostMapping("/sync/vector-store")
    public ResponseEntity<SyncJobResponse> syncToVectorStore(@RequestBody SyncRequest request) {
        Long factSheetId = request.factSheetId() != null ? request.factSheetId() :
                factSheetService.getActiveSheet().getId();

        CompletableFuture<SyncResult> future = syncService.syncToVectorStore(factSheetId);

        if (request.async()) {
            // Return immediately with job ID
            String jobId = future.toString().substring(future.toString().lastIndexOf('@') + 1);
            return ResponseEntity.accepted().body(new SyncJobResponse(
                    jobId, "RUNNING", Instant.now(), "Vector store sync started"));
        } else {
            // Wait for completion
            try {
                SyncResult result = future.get(30, TimeUnit.MINUTES);
                return ResponseEntity.ok(new SyncJobResponse(
                        result.jobId(), result.status().name(), Instant.now(),
                        String.format("Processed %d documents, %d passages",
                                result.documentsProcessed(), result.passagesProcessed())));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(new SyncJobResponse(
                        null, "FAILED", Instant.now(), e.getMessage()));
            }
        }
    }

    /**
     * POST /api/cross-index/sync/knowledge-graph
     * Trigger synchronization to knowledge graph.
     */
    @PostMapping("/sync/knowledge-graph")
    public ResponseEntity<SyncJobResponse> syncToKnowledgeGraph(@RequestBody SyncRequest request) {
        Long factSheetId = request.factSheetId() != null ? request.factSheetId() :
                factSheetService.getActiveSheet().getId();

        CompletableFuture<SyncResult> future = syncService.syncToKnowledgeGraph(factSheetId);

        if (request.async()) {
            String jobId = future.toString().substring(future.toString().lastIndexOf('@') + 1);
            return ResponseEntity.accepted().body(new SyncJobResponse(
                    jobId, "RUNNING", Instant.now(), "Knowledge graph sync started"));
        } else {
            try {
                SyncResult result = future.get(30, TimeUnit.MINUTES);
                return ResponseEntity.ok(new SyncJobResponse(
                        result.jobId(), result.status().name(), Instant.now(),
                        String.format("Processed %d documents, %d passages",
                                result.documentsProcessed(), result.passagesProcessed())));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(new SyncJobResponse(
                        null, "FAILED", Instant.now(), e.getMessage()));
            }
        }
    }

    /**
     * POST /api/cross-index/sync/all
     * Trigger full synchronization to all indexes.
     */
    @PostMapping("/sync/all")
    public ResponseEntity<SyncJobResponse> syncAll(@RequestBody SyncRequest request) {
        Long factSheetId = request.factSheetId() != null ? request.factSheetId() :
                factSheetService.getActiveSheet().getId();

        CompletableFuture<SyncResult> future = syncService.syncAll(factSheetId);

        if (request.async()) {
            String jobId = future.toString().substring(future.toString().lastIndexOf('@') + 1);
            return ResponseEntity.accepted().body(new SyncJobResponse(
                    jobId, "RUNNING", Instant.now(), "Full sync started"));
        } else {
            try {
                SyncResult result = future.get(30, TimeUnit.MINUTES);
                return ResponseEntity.ok(new SyncJobResponse(
                        result.jobId(), result.status().name(), Instant.now(),
                        String.format("Processed %d documents, %d passages",
                                result.documentsProcessed(), result.passagesProcessed())));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(new SyncJobResponse(
                        null, "FAILED", Instant.now(), e.getMessage()));
            }
        }
    }

    /**
     * GET /api/cross-index/sync/{jobId}
     * Get status of a sync job.
     */
    @GetMapping("/sync/{jobId}")
    public ResponseEntity<SyncJobStatusResponse> getSyncJobStatus(@PathVariable String jobId) {
        return syncService.getJobStatus(jobId)
                .map(status -> ResponseEntity.ok(new SyncJobStatusResponse(
                        status.jobId(),
                        status.status().name(),
                        status.documentsProcessed(),
                        status.passagesProcessed(),
                        status.totalDocuments(),
                        status.totalPassages(),
                        status.progressPercent(),
                        status.errors()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/cross-index/sync/{jobId}
     * Cancel a running sync job.
     */
    @DeleteMapping("/sync/{jobId}")
    public ResponseEntity<Void> cancelSyncJob(@PathVariable String jobId) {
        if (syncService.cancelJob(jobId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * GET /api/cross-index/sync/active
     * List all active sync jobs.
     */
    @GetMapping("/sync/active")
    public ResponseEntity<List<SyncJobStatusResponse>> getActiveSyncJobs() {
        List<SyncJobStatusResponse> jobs = syncService.getActiveJobs().stream()
                .map(status -> new SyncJobStatusResponse(
                        status.jobId(),
                        status.status().name(),
                        status.documentsProcessed(),
                        status.passagesProcessed(),
                        status.totalDocuments(),
                        status.totalPassages(),
                        status.progressPercent(),
                        status.errors()
                ))
                .toList();

        return ResponseEntity.ok(jobs);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FRESHNESS ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POST /api/cross-index/documents/{id}/mark-stale
     * Manually mark a document as stale.
     */
    @PostMapping("/documents/{id}/mark-stale")
    public ResponseEntity<Map<String, Object>> markDocumentStale(@PathVariable Long id) {
        if (freshnessService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Freshness service is not enabled. Set kompile.freshness.enabled=true"));
        }
        freshnessService.markDocumentStale(id);
        return ResponseEntity.ok(Map.of("status", "marked_stale", "documentId", id));
    }

    /**
     * POST /api/cross-index/fact-sheets/{id}/scan-freshness
     * Scan all documents in a fact sheet for staleness (checksum + TTL).
     */
    @PostMapping("/fact-sheets/{id}/scan-freshness")
    public ResponseEntity<Map<String, Object>> scanFreshness(@PathVariable Long id) {
        if (freshnessService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Freshness service is not enabled. Set kompile.freshness.enabled=true"));
        }
        int checksumStale = freshnessService.scanForStaleDocuments(id);
        int ttlStale = freshnessService.markStaleByTtl(id);
        return ResponseEntity.ok(Map.of(
                "factSheetId", id,
                "documentsMarkedStaleByChecksum", checksumStale,
                "documentsMarkedStaleByTtl", ttlStale
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/cross-index/config/auto-sync
     * Get auto-sync configuration.
     */
    @GetMapping("/config/auto-sync")
    public ResponseEntity<AutoSyncConfigResponse> getAutoSyncConfig(
            @RequestParam(required = false) Long factSheetId) {

        Long id = factSheetId != null ? factSheetId :
                factSheetService.getActiveSheet().getId();

        AutoSyncConfig config = syncService.getAutoSyncConfig(id);

        return ResponseEntity.ok(new AutoSyncConfigResponse(
                id,
                config.enabled(),
                config.maxPassagesPerSync(),
                (int) config.syncTimeout().toSeconds(),
                config.syncOnSearch(),
                config.syncOnIngest()
        ));
    }

    /**
     * PUT /api/cross-index/config/auto-sync
     * Update auto-sync configuration.
     */
    @PutMapping("/config/auto-sync")
    public ResponseEntity<AutoSyncConfigResponse> updateAutoSyncConfig(
            @RequestBody AutoSyncConfigRequest request) {

        Long factSheetId = request.factSheetId() != null ? request.factSheetId() :
                factSheetService.getActiveSheet().getId();

        AutoSyncConfig config = new AutoSyncConfig(
                request.enabled(),
                request.maxPassagesPerSync(),
                Duration.ofSeconds(request.syncTimeoutSeconds()),
                request.syncOnSearch(),
                request.syncOnIngest()
        );

        syncService.updateAutoSyncConfig(factSheetId, config);

        return ResponseEntity.ok(new AutoSyncConfigResponse(
                factSheetId,
                config.enabled(),
                config.maxPassagesPerSync(),
                (int) config.syncTimeout().toSeconds(),
                config.syncOnSearch(),
                config.syncOnIngest()
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private IndexedDocumentResponse toDocumentResponse(IndexedDocument doc) {
        return new IndexedDocumentResponse(
                doc.getId(),
                doc.getSourceId(),
                doc.getFileName(),
                doc.getFactId(),
                doc.getOverallStatus().name(),
                doc.getKeywordIndexStatus().name(),
                doc.getVectorStoreStatus().name(),
                doc.getGraphStatus().name(),
                doc.getKeywordPassageCount() + doc.getVectorPassageCount(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }

    private IndexedPassageResponse toPassageResponse(IndexedPassage passage) {
        return new IndexedPassageResponse(
                passage.getId(),
                passage.getChunkId(),
                passage.getChunkIndex(),
                passage.getContentPreview(),
                passage.getKeywordIndexStatus().name(),
                passage.getVectorStoreStatus().name(),
                passage.getGraphStatus().name(),
                passage.getVectorId(),
                passage.getGraphNodeId()
        );
    }

    private Map<String, Integer> calculatePassageStatusCounts(List<IndexedPassage> passages) {
        int fullyIndexed = 0;
        int inKeywordOnly = 0;
        int inVectorOnly = 0;
        int notIndexed = 0;

        for (IndexedPassage p : passages) {
            if (p.isFullyIndexed()) {
                fullyIndexed++;
            } else if (p.isInKeywordIndex() && !p.isInVectorStore()) {
                inKeywordOnly++;
            } else if (p.isInVectorStore() && !p.isInKeywordIndex()) {
                inVectorOnly++;
            } else if (!p.isInKeywordIndex() && !p.isInVectorStore()) {
                notIndexed++;
            }
        }

        return Map.of(
                "fullyIndexed", fullyIndexed,
                "inKeywordOnly", inKeywordOnly,
                "inVectorOnly", inVectorOnly,
                "notIndexed", notIndexed
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DTO RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record SyncRequest(
            Long factSheetId,
            List<Long> documentIds,
            List<String> chunkIds,
            boolean async
    ) {}

    public record SyncJobResponse(
            String jobId,
            String status,
            Instant startedAt,
            String message
    ) {}

    public record SyncJobStatusResponse(
            String jobId,
            String status,
            int documentsProcessed,
            int passagesProcessed,
            int totalDocuments,
            int totalPassages,
            int progressPercent,
            List<String> errors
    ) {}

    public record CrossIndexSummaryResponse(
            Long factSheetId,
            String factSheetName,
            long totalDocuments,
            long fullyIndexedDocuments,
            long partialDocuments,
            long notIndexedDocuments,
            int documentsNeedingSync,
            int passagesMissingFromVector,
            int passagesMissingFromGraph,
            boolean autoSyncEnabled,
            Instant lastSyncAt
    ) {}

    public record CrossIndexStatisticsResponse(
            Long factSheetId,
            DocumentStats documentStats,
            PassageStats passageStats,
            Map<OverallIndexStatus, Long> statusDistribution
    ) {}

    public record DocumentStats(
            long total,
            long fullyIndexed,
            long partial,
            long notIndexed,
            long failed
    ) {}

    public record PassageStats(
            long total,
            long inKeywordIndex,
            long inVectorStore,
            long inGraph
    ) {}

    public record IndexedDocumentResponse(
            Long id,
            String sourceId,
            String fileName,
            Long factId,
            String overallStatus,
            String keywordStatus,
            String vectorStatus,
            String graphStatus,
            int passageCount,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record IndexedDocumentDetailResponse(
            IndexedDocumentResponse document,
            List<IndexedPassageResponse> passages,
            Map<String, Integer> statusCounts
    ) {}

    public record IndexedPassageResponse(
            Long id,
            String chunkId,
            Integer chunkIndex,
            String contentPreview,
            String keywordStatus,
            String vectorStatus,
            String graphStatus,
            String vectorId,
            String graphNodeId
    ) {}

    public record PassageStatusResponse(
            String chunkId,
            boolean inKeywordIndex,
            boolean inVectorStore,
            boolean inKnowledgeGraph,
            boolean fullyIndexed
    ) {}

    public record AutoSyncConfigRequest(
            Long factSheetId,
            boolean enabled,
            int maxPassagesPerSync,
            int syncTimeoutSeconds,
            boolean syncOnSearch,
            boolean syncOnIngest
    ) {}

    public record AutoSyncConfigResponse(
            Long factSheetId,
            boolean enabled,
            int maxPassagesPerSync,
            int syncTimeoutSeconds,
            boolean syncOnSearch,
            boolean syncOnIngest
    ) {}
}
