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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataEnrichmentControllerTest {

    @Mock
    private DataEnrichmentService enrichmentService;

    @Mock
    private EntityCategoryServiceImpl categoryService;

    @Mock
    private AutoLabelService autoLabelService;

    @Mock
    private EnrichmentAuditService auditService;

    @Mock
    private EnrichmentSearchService searchService;

    @Mock
    private DomainTaxonomyRepository taxonomyRepository;

    private DataEnrichmentController controller;

    private static final Long FACT_SHEET_ID = 10L;

    @BeforeEach
    void setUp() {
        controller = new DataEnrichmentController(
                enrichmentService,
                categoryService,
                autoLabelService,
                auditService,
                searchService,
                taxonomyRepository
        );
    }

    // ─── startEnrichment ─────────────────────────────────────────────────────

    @Test
    void startEnrichmentReturnsJob() {
        EnrichmentJob job = new EnrichmentJob("job-start-1", FACT_SHEET_ID);
        job.setStatusValue(EnrichmentJob.Status.COMPLETED);
        when(enrichmentService.startEnrichment(eq(FACT_SHEET_ID), any())).thenReturn(job);

        ResponseEntity<EnrichmentJob> response = controller.startEnrichment(
                FACT_SHEET_ID, EnrichmentConfig.builder().build());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("job-start-1", response.getBody().getJobId());
        assertEquals(EnrichmentJob.Status.COMPLETED, response.getBody().getStatusValue());
    }

    // ─── getJob ──────────────────────────────────────────────────────────────

    @Test
    void getJobReturnsFound() {
        EnrichmentJob job = new EnrichmentJob("job-xyz", FACT_SHEET_ID);
        when(enrichmentService.getJob("job-xyz")).thenReturn(Optional.of(job));

        ResponseEntity<EnrichmentJob> response = controller.getJob("job-xyz");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("job-xyz", response.getBody().getJobId());
    }

    @Test
    void getJobReturnsNotFound() {
        when(enrichmentService.getJob("missing-job")).thenReturn(Optional.empty());

        ResponseEntity<EnrichmentJob> response = controller.getJob("missing-job");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ─── getEnrichmentStatus ─────────────────────────────────────────────────

    @Test
    void getStatusReturnsEnrichmentStatus() {
        when(enrichmentService.isEnriched(FACT_SHEET_ID)).thenReturn(true);
        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(FACT_SHEET_ID))
                .thenReturn(Optional.empty());
        EntityCategory cat1 = EntityCategory.builder()
                .categoryId("cat-1").label("Technology").source("USER_DEFINED").build();
        EntityCategory cat2 = EntityCategory.builder()
                .categoryId("cat-2").label("Finance").source("AUTO_DISCOVERED").build();
        when(categoryService.listByFactSheet(FACT_SHEET_ID)).thenReturn(List.of(cat1, cat2));

        ResponseEntity<Map<String, Object>> response = controller.getEnrichmentStatus(FACT_SHEET_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("enriched"));
        assertEquals(2, (int) (Integer) response.getBody().get("categoryCount"));
        assertEquals(1L, response.getBody().get("userDefinedCategoryCount"));
    }

    // ─── createCategory ──────────────────────────────────────────────────────

    @Test
    void createCategoryDelegatesToService() {
        EntityCategory created = EntityCategory.builder()
                .categoryId("technology")
                .label("Technology")
                .description("Tech stuff")
                .source("USER_DEFINED")
                .build();
        when(categoryService.create(
                eq(FACT_SHEET_ID), eq("Technology"), eq("Tech stuff"),
                isNull(), eq("#3498db"))).thenReturn(created);

        Map<String, String> req = Map.of(
                "label", "Technology",
                "description", "Tech stuff",
                "color", "#3498db"
        );

        ResponseEntity<EntityCategory> response = controller.createCategory(FACT_SHEET_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("technology", response.getBody().getCategoryId());
        assertEquals("Technology", response.getBody().getLabel());

        verify(categoryService).create(FACT_SHEET_ID, "Technology", "Tech stuff", null, "#3498db");
    }

    // ─── deleteCategory ──────────────────────────────────────────────────────

    @Test
    void deleteCategoryDelegatesToService() {
        String categoryId = "tech-cat";

        ResponseEntity<Void> response = controller.deleteCategory(FACT_SHEET_ID, categoryId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(categoryService).delete(FACT_SHEET_ID, categoryId);
    }

    // ─── getAuditLog ─────────────────────────────────────────────────────────

    @Test
    void getAuditLogDelegatesToService() {
        Page<EnrichmentAuditEntry> page = new PageImpl<>(List.of());
        // When both jobId and phase are supplied, auditService.getAuditLog must be called
        when(auditService.getAuditLog(
                eq(FACT_SHEET_ID), eq("job-1"), eq("CLEAN"),
                eq(PageRequest.of(0, 20)))).thenReturn(page);

        ResponseEntity<Page<EnrichmentAuditEntry>> response =
                controller.getAuditLog(FACT_SHEET_ID, "job-1", "CLEAN", 0, 20);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(page, response.getBody());
        verify(auditService).getAuditLog(FACT_SHEET_ID, "job-1", "CLEAN", PageRequest.of(0, 20));
    }

    // ─── revertAction ────────────────────────────────────────────────────────

    @Test
    void revertActionDelegatesToService() {
        String auditId = "audit-abc";
        RevertResult result = RevertResult.builder()
                .actionsReverted(1)
                .nodesRestored(1)
                .edgesRestored(0)
                .failedRevertIds(List.of())
                .warnings(List.of())
                .build();
        when(auditService.revertAction(auditId)).thenReturn(result);

        ResponseEntity<RevertResult> response = controller.revertAction(FACT_SHEET_ID, auditId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getActionsReverted());
        assertEquals(1, response.getBody().getNodesRestored());
        assertTrue(response.getBody().getFailedRevertIds().isEmpty());

        verify(auditService).revertAction(auditId);
    }
}
