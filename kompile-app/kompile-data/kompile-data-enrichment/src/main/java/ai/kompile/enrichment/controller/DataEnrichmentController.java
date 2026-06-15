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
package ai.kompile.enrichment.controller;

import ai.kompile.enrichment.api.DataEnrichmentService;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.domain.*;
import ai.kompile.enrichment.impl.AutoLabelService;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.enrichment.impl.EntityCategoryServiceImpl;
import ai.kompile.enrichment.impl.search.EnrichmentSearchService;
import ai.kompile.enrichment.repository.DomainTaxonomyRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/enrichment")
public class DataEnrichmentController {

    private final DataEnrichmentService enrichmentService;
    private final EntityCategoryServiceImpl categoryService;
    private final AutoLabelService autoLabelService;
    private final EnrichmentAuditService auditService;
    private final EnrichmentSearchService searchService;
    private final DomainTaxonomyRepository taxonomyRepository;

    public DataEnrichmentController(DataEnrichmentService enrichmentService,
                                    EntityCategoryServiceImpl categoryService,
                                    AutoLabelService autoLabelService,
                                    EnrichmentAuditService auditService,
                                    EnrichmentSearchService searchService,
                                    DomainTaxonomyRepository taxonomyRepository) {
        this.enrichmentService = enrichmentService;
        this.categoryService = categoryService;
        this.autoLabelService = autoLabelService;
        this.auditService = auditService;
        this.searchService = searchService;
        this.taxonomyRepository = taxonomyRepository;
    }

    // ═══════════════════════════════════════════════════════════════
    // ENRICHMENT JOBS
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{factSheetId}/start")
    public ResponseEntity<EnrichmentJob> startEnrichment(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.startEnrichment(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/clean")
    public ResponseEntity<EnrichmentJob> runClean(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runCleanPhase(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/organize")
    public ResponseEntity<EnrichmentJob> runOrganize(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runOrganizePhase(factSheetId, config));
    }

    // ── Individual Step Endpoints ──────────────────────────────

    @PostMapping("/{factSheetId}/steps/dedup")
    public ResponseEntity<EnrichmentJob> runDedup(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runDeduplication(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/prune")
    public ResponseEntity<EnrichmentJob> runPrune(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runPruning(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/validate")
    public ResponseEntity<EnrichmentJob> runValidate(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runValidation(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/normalize")
    public ResponseEntity<EnrichmentJob> runNormalize(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runNormalization(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/discover-taxonomy")
    public ResponseEntity<EnrichmentJob> runDiscoverTaxonomy(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runTaxonomyDiscovery(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/categorize")
    public ResponseEntity<EnrichmentJob> runCategorize(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runCategorization(factSheetId, config));
    }

    @PostMapping("/{factSheetId}/steps/generate-processes")
    public ResponseEntity<EnrichmentJob> runGenerateProcesses(
            @PathVariable Long factSheetId,
            @RequestBody(required = false) EnrichmentConfig config) {
        return ResponseEntity.ok(enrichmentService.runProcessGeneration(factSheetId, config));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<EnrichmentJob>> listJobs() {
        return ResponseEntity.ok(enrichmentService.listJobs());
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<EnrichmentJob> getJob(@PathVariable String jobId) {
        return enrichmentService.getJob(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Void> cancelJob(@PathVariable String jobId) {
        return enrichmentService.cancelJob(jobId) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{factSheetId}/status")
    public ResponseEntity<Map<String, Object>> getEnrichmentStatus(@PathVariable Long factSheetId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enriched", enrichmentService.isEnriched(factSheetId));
        taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId).ifPresent(t -> {
            status.put("lastEnrichmentAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
            status.put("taxonomyVersion", t.getVersion());
        });
        List<EntityCategory> categories = categoryService.listByFactSheet(factSheetId);
        status.put("categoryCount", categories.size());
        status.put("userDefinedCategoryCount", categories.stream()
                .filter(c -> "USER_DEFINED".equals(c.getSource())).count());
        return ResponseEntity.ok(status);
    }

    // ═══════════════════════════════════════════════════════════════
    // CUSTOM ENTITY CATEGORIES (CRUD)
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{factSheetId}/categories")
    public ResponseEntity<EntityCategory> createCategory(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(categoryService.create(
                factSheetId, req.get("label"), req.get("description"),
                req.get("parentCategoryId"), req.get("color")));
    }

    @GetMapping("/{factSheetId}/categories")
    public ResponseEntity<List<EntityCategory>> listCategories(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "false") boolean tree) {
        return ResponseEntity.ok(tree
                ? categoryService.getTree(factSheetId)
                : categoryService.listByFactSheet(factSheetId));
    }

    @GetMapping("/{factSheetId}/categories/{categoryId}")
    public ResponseEntity<EntityCategory> getCategory(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId) {
        return categoryService.getById(categoryId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{factSheetId}/categories/{categoryId}")
    public ResponseEntity<EntityCategory> updateCategory(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(categoryService.update(
                factSheetId, categoryId, req.get("label"), req.get("description"),
                req.get("parentCategoryId"), req.get("color")));
    }

    @DeleteMapping("/{factSheetId}/categories/{categoryId}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId) {
        categoryService.delete(factSheetId, categoryId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{factSheetId}/categories/{categoryId}/entities")
    public ResponseEntity<Void> assignEntities(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId,
            @RequestBody Map<String, List<String>> req) {
        categoryService.assignEntitiesToCategory(factSheetId, categoryId, req.get("entityNodeIds"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{factSheetId}/categories/{categoryId}/entities")
    public ResponseEntity<Void> removeEntities(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId,
            @RequestBody Map<String, List<String>> req) {
        categoryService.removeEntitiesFromCategory(categoryId, req.get("entityNodeIds"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{factSheetId}/categories/{categoryId}/entities")
    public ResponseEntity<List<GraphNode>> getEntitiesInCategory(
            @PathVariable Long factSheetId,
            @PathVariable String categoryId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(categoryService.getEntitiesInCategory(categoryId, offset, limit));
    }

    // ═══════════════════════════════════════════════════════════════
    // MASS EDIT & AUTO-LABEL
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{factSheetId}/categories/mass-reassign")
    public ResponseEntity<MassEditResult> massReassign(
            @PathVariable Long factSheetId,
            @RequestBody MassReassignRequest req) {
        return ResponseEntity.ok(categoryService.massReassign(factSheetId, req));
    }

    @PostMapping("/{factSheetId}/categories/mass-update")
    public ResponseEntity<MassEditResult> massUpdate(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, List<CategoryPatch>> req) {
        return ResponseEntity.ok(categoryService.massUpdate(factSheetId, req.get("patches")));
    }

    @PostMapping("/{factSheetId}/categories/mass-delete")
    public ResponseEntity<MassEditResult> massDelete(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, List<String>> req) {
        return ResponseEntity.ok(categoryService.massDelete(factSheetId, req.get("categoryIds")));
    }

    @PostMapping("/{factSheetId}/categories/auto-label")
    public ResponseEntity<MassEditResult> autoLabel(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<String> entityNodeIds = (List<String>) req.get("entityNodeIds");
        boolean dryRun = Boolean.TRUE.equals(req.get("dryRun"));
        double minConfidence = req.containsKey("minConfidence")
                ? ((Number) req.get("minConfidence")).doubleValue() : 0.7;
        return ResponseEntity.ok(autoLabelService.autoLabel(factSheetId, entityNodeIds, dryRun, minConfidence));
    }

    @PostMapping("/{factSheetId}/categories/auto-label/apply")
    public ResponseEntity<MassEditResult> applyAutoLabel(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, List<AutoLabelSuggestion>> req) {
        return ResponseEntity.ok(autoLabelService.applySuggestions(factSheetId, req.get("suggestions")));
    }

    // ═══════════════════════════════════════════════════════════════
    // TAXONOMY (AUTO-DISCOVERED, READ-ONLY)
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{factSheetId}/taxonomy")
    public ResponseEntity<List<TaxonomyNode>> getTaxonomy(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(searchService.browseTaxonomy(factSheetId));
    }

    @GetMapping("/{factSheetId}/taxonomy/browse")
    public ResponseEntity<List<TaxonomyNode>> browseTaxonomy(
            @PathVariable Long factSheetId,
            @RequestParam(required = false) String parentId) {
        return ResponseEntity.ok(searchService.browseChildren(factSheetId, parentId));
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIT LOG & REVERT
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{factSheetId}/audit")
    public ResponseEntity<Page<EnrichmentAuditEntry>> getAuditLog(
            @PathVariable Long factSheetId,
            @RequestParam(required = false) String jobId,
            @RequestParam(required = false) String phase,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(auditService.getAuditLog(factSheetId, jobId, phase, PageRequest.of(page, size)));
    }

    @GetMapping("/{factSheetId}/audit/{auditId}")
    public ResponseEntity<EnrichmentAuditEntry> getAuditEntry(
            @PathVariable Long factSheetId,
            @PathVariable String auditId) {
        return auditService.getEntry(auditId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{factSheetId}/audit/summary")
    public ResponseEntity<AuditSummary> getAuditSummary(
            @PathVariable Long factSheetId,
            @RequestParam String jobId) {
        return ResponseEntity.ok(auditService.getJobSummary(jobId));
    }

    @PostMapping("/{factSheetId}/audit/{auditId}/revert")
    public ResponseEntity<RevertResult> revertAction(
            @PathVariable Long factSheetId,
            @PathVariable String auditId) {
        return ResponseEntity.ok(auditService.revertAction(auditId));
    }

    @PostMapping("/{factSheetId}/audit/revert-phase")
    public ResponseEntity<RevertResult> revertPhase(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(auditService.revertPhase(req.get("enrichmentJobId"), req.get("phase")));
    }

    @PostMapping("/{factSheetId}/audit/revert-job")
    public ResponseEntity<RevertResult> revertJob(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(auditService.revertJob(req.get("enrichmentJobId")));
    }

    // ═══════════════════════════════════════════════════════════════
    // TAXONOMY-AWARE SEARCH
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{factSheetId}/search")
    public ResponseEntity<List<GraphNode>> searchByCategory(
            @PathVariable Long factSheetId,
            @RequestBody Map<String, Object> req) {
        String query = (String) req.get("query");
        String categoryFilter = (String) req.get("categoryFilter");
        int maxResults = req.containsKey("maxResults") ? ((Number) req.get("maxResults")).intValue() : 50;
        return ResponseEntity.ok(searchService.searchByCategory(factSheetId, categoryFilter, query, maxResults));
    }

    @GetMapping("/{factSheetId}/search/facets")
    public ResponseEntity<Map<String, Long>> getCategoryFacets(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(searchService.getCategoryFacets(factSheetId));
    }

    @GetMapping("/{factSheetId}/search/entity-type-facets")
    public ResponseEntity<Map<String, Long>> getEntityTypeFacets(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(searchService.getEntityTypeFacets(factSheetId));
    }
}
