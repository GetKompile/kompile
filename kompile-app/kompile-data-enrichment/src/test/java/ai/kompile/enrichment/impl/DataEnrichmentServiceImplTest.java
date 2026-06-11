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
package ai.kompile.enrichment.impl;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.enrichment.api.EnrichmentPhase;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.config.EnrichmentConfigService;
import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.EnrichmentJob;
import ai.kompile.enrichment.impl.clean.ChunkDeduplicationService;
import ai.kompile.enrichment.impl.clean.EntityNormalizationService;
import ai.kompile.enrichment.impl.clean.GraphPruningService;
import ai.kompile.enrichment.impl.clean.GraphValidationService;
import ai.kompile.enrichment.impl.organize.DomainTaxonomyDiscoveryService;
import ai.kompile.enrichment.impl.organize.EntityCategorizationService;
import ai.kompile.enrichment.impl.organize.TaxonomySchemaPresetExporter;
import ai.kompile.enrichment.impl.taxonomy.TaxonomyProcessDefinitionService;
import ai.kompile.enrichment.repository.DomainTaxonomyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataEnrichmentServiceImplTest {

    @Mock
    private EnrichmentConfigService configService;

    @Mock
    private ChunkDeduplicationService chunkDedup;

    @Mock
    private GraphPruningService graphPruning;

    @Mock
    private GraphValidationService graphValidation;

    @Mock
    private EntityNormalizationService entityNormalization;

    @Mock
    private DomainTaxonomyDiscoveryService taxonomyDiscovery;

    @Mock
    private EntityCategorizationService entityCategorization;

    @Mock
    private EntityCategoryServiceImpl categoryService;

    @Mock
    private TaxonomySchemaPresetExporter presetExporter;

    @Mock
    private TaxonomyProcessDefinitionService processDefinitionService;

    @Mock
    private DomainTaxonomyRepository taxonomyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DataEnrichmentServiceImpl service;

    private static final Long FACT_SHEET_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new DataEnrichmentServiceImpl(
                configService,
                chunkDedup,
                graphPruning,
                graphValidation,
                entityNormalization,
                taxonomyDiscovery,
                entityCategorization,
                categoryService,
                presetExporter,
                processDefinitionService,
                taxonomyRepository,
                eventPublisher
        );
    }

    // Helper: default config (all phases enabled, exportSchemaPreset=false to avoid taxonomy repo save)
    private EnrichmentConfig defaultConfig() {
        return EnrichmentConfig.builder().exportSchemaPreset(false).build();
    }

    // ─── startEnrichment ─────────────────────────────────────────────────────

    @Test
    void startEnrichmentRunsAllPhases() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(3);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(2);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(1);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(5);

        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .factSheetId(FACT_SHEET_ID).taxonomyJson("[]").version(1).build();
        when(taxonomyDiscovery.discoverTaxonomy(anyLong(), any())).thenReturn(taxonomy);
        when(entityCategorization.categorizeEntities(anyLong(), any())).thenReturn(7);
        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(anyLong())).thenReturn(Optional.empty());

        EnrichmentJob job = service.startEnrichment(FACT_SHEET_ID, config);

        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());
        assertEquals(100, job.getProgressPercent());

        // CLEAN phase services all called
        verify(chunkDedup).deduplicateChunks(eq(FACT_SHEET_ID), anyString(), any());
        verify(graphPruning).pruneGraph(eq(FACT_SHEET_ID), anyString(), any());
        verify(graphValidation).validate(eq(FACT_SHEET_ID), anyString(), any());
        verify(entityNormalization).normalize(eq(FACT_SHEET_ID), anyString(), any());

        // ORGANIZE phase services all called
        verify(taxonomyDiscovery).discoverTaxonomy(eq(FACT_SHEET_ID), any());
        verify(categoryService).importFromTaxonomy(eq(FACT_SHEET_ID), any());
        verify(entityCategorization).categorizeEntities(eq(FACT_SHEET_ID), any());
    }

    @Test
    void startEnrichmentSkipsDisabledPhases() {
        EnrichmentConfig config = EnrichmentConfig.builder()
                .enabledPhases(List.of(EnrichmentPhase.CLEAN))
                .exportSchemaPreset(false)
                .build();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);

        EnrichmentJob job = service.startEnrichment(FACT_SHEET_ID, config);

        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());

        // CLEAN phase ran
        verify(chunkDedup).deduplicateChunks(eq(FACT_SHEET_ID), anyString(), any());

        // ORGANIZE/TAXONOMY phases did NOT run
        verify(taxonomyDiscovery, never()).discoverTaxonomy(anyLong(), any());
        verify(entityCategorization, never()).categorizeEntities(anyLong(), any());
        verify(categoryService, never()).importFromTaxonomy(anyLong(), any());
        verify(processDefinitionService, never()).generateProcessDefinitions(any());
    }

    @Test
    void startEnrichmentFailureSetsFailed() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        EnrichmentJob job = service.startEnrichment(FACT_SHEET_ID, config);

        assertEquals(EnrichmentJob.Status.FAILED, job.getStatusValue());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("DB connection lost"));
    }

    // ─── getJob ──────────────────────────────────────────────────────────────

    @Test
    void getJobReturnsFromMap() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);
        when(taxonomyDiscovery.discoverTaxonomy(anyLong(), any()))
                .thenReturn(DomainTaxonomy.builder().taxonomyJson("[]").build());
        when(entityCategorization.categorizeEntities(anyLong(), any())).thenReturn(0);
        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(anyLong())).thenReturn(Optional.empty());

        EnrichmentJob started = service.startEnrichment(FACT_SHEET_ID, config);
        String jobId = started.getJobId();

        Optional<EnrichmentJob> found = service.getJob(jobId);

        assertTrue(found.isPresent());
        assertEquals(jobId, found.get().getJobId());
    }

    // ─── listJobs ────────────────────────────────────────────────────────────

    @Test
    void listJobsReturnsAll() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);
        when(taxonomyDiscovery.discoverTaxonomy(anyLong(), any()))
                .thenReturn(DomainTaxonomy.builder().taxonomyJson("[]").build());
        when(entityCategorization.categorizeEntities(anyLong(), any())).thenReturn(0);
        when(taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(anyLong())).thenReturn(Optional.empty());

        service.startEnrichment(1L, config);
        service.startEnrichment(2L, config);

        List<EnrichmentJob> jobs = service.listJobs();
        assertEquals(2, jobs.size());
    }

    // ─── cancelJob ───────────────────────────────────────────────────────────

    @Test
    void cancelJobSetsCancel() {
        // Create a job and manually put it in RUNNING state
        // We accomplish this by spying on the internals — the job is in the map after startEnrichment
        // but startEnrichment completes synchronously, so we pre-seed via runCleanPhase with a throw
        EnrichmentConfig config = EnrichmentConfig.builder()
                .enabledPhases(List.of(EnrichmentPhase.CLEAN))
                .exportSchemaPreset(false)
                .build();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);

        EnrichmentJob job = service.startEnrichment(FACT_SHEET_ID, config);
        // Job is COMPLETED now — manually reset to RUNNING to test cancel
        job.setStatusValue(EnrichmentJob.Status.RUNNING);

        boolean cancelled = service.cancelJob(job.getJobId());

        assertTrue(cancelled);
        assertEquals(EnrichmentJob.Status.CANCELLED, job.getStatusValue());
    }

    @Test
    void cancelJobReturnsFalseIfCompleted() {
        EnrichmentConfig config = EnrichmentConfig.builder()
                .enabledPhases(List.of(EnrichmentPhase.CLEAN))
                .exportSchemaPreset(false)
                .build();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);

        EnrichmentJob job = service.startEnrichment(FACT_SHEET_ID, config);
        // Job ends COMPLETED synchronously
        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());

        boolean result = service.cancelJob(job.getJobId());

        assertFalse(result);
        // Status remains COMPLETED
        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());
    }

    // ─── runCleanPhase / runOrganizePhase ────────────────────────────────────

    @Test
    void runCleanPhaseOnly() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(2);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(1);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(3);

        EnrichmentJob job = service.runCleanPhase(FACT_SHEET_ID, config);

        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());

        verify(chunkDedup).deduplicateChunks(eq(FACT_SHEET_ID), anyString(), any());
        verify(graphPruning).pruneGraph(eq(FACT_SHEET_ID), anyString(), any());
        verify(graphValidation).validate(eq(FACT_SHEET_ID), anyString(), any());
        verify(entityNormalization).normalize(eq(FACT_SHEET_ID), anyString(), any());

        // ORGANIZE services must NOT be called
        verify(taxonomyDiscovery, never()).discoverTaxonomy(anyLong(), any());
        verify(entityCategorization, never()).categorizeEntities(anyLong(), any());
    }

    @Test
    void runOrganizePhaseOnly() {
        EnrichmentConfig config = defaultConfig();
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        DomainTaxonomy taxonomy = DomainTaxonomy.builder()
                .factSheetId(FACT_SHEET_ID).taxonomyJson("[]").version(1).build();
        when(taxonomyDiscovery.discoverTaxonomy(eq(FACT_SHEET_ID), any())).thenReturn(taxonomy);
        when(entityCategorization.categorizeEntities(eq(FACT_SHEET_ID), any())).thenReturn(4);

        EnrichmentJob job = service.runOrganizePhase(FACT_SHEET_ID, config);

        assertEquals(EnrichmentJob.Status.COMPLETED, job.getStatusValue());

        verify(taxonomyDiscovery).discoverTaxonomy(eq(FACT_SHEET_ID), any());
        verify(categoryService).importFromTaxonomy(eq(FACT_SHEET_ID), any());
        verify(entityCategorization).categorizeEntities(eq(FACT_SHEET_ID), any());

        // CLEAN services must NOT be called
        verify(chunkDedup, never()).deduplicateChunks(anyLong(), anyString(), any());
        verify(graphPruning, never()).pruneGraph(anyLong(), anyString(), any());
    }

    // ─── onGraphBuildCompleted event listener ────────────────────────────────

    @Test
    void onGraphBuildCompletedAutoTrigger() {
        EnrichmentConfig config = EnrichmentConfig.builder()
                .autoTriggerAfterCrawl(true)
                .enabledPhases(List.of(EnrichmentPhase.CLEAN))
                .exportSchemaPreset(false)
                .build();
        when(configService.loadConfig()).thenReturn(config);
        when(configService.mergeWithDefaults(any())).thenReturn(config);
        when(chunkDedup.deduplicateChunks(anyLong(), anyString(), any())).thenReturn(0);
        when(graphPruning.pruneGraph(anyLong(), anyString(), any())).thenReturn(0);
        when(graphValidation.validate(anyLong(), anyString(), any())).thenReturn(0);
        when(entityNormalization.normalize(anyLong(), anyString(), any())).thenReturn(0);

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(
                this, "crawl-job-1", 10, 5, FACT_SHEET_ID, Map.of());

        service.onGraphBuildCompleted(event);

        // startEnrichment was triggered — verifiable via chunkDedup being called
        verify(chunkDedup).deduplicateChunks(eq(FACT_SHEET_ID), anyString(), any());
    }

    @Test
    void onGraphBuildCompletedSkipsWhenDisabled() {
        EnrichmentConfig config = EnrichmentConfig.builder()
                .autoTriggerAfterCrawl(false)
                .build();
        when(configService.loadConfig()).thenReturn(config);

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(
                this, "crawl-job-2", 10, 5, FACT_SHEET_ID, Map.of());

        service.onGraphBuildCompleted(event);

        // No enrichment services should have been invoked
        verify(chunkDedup, never()).deduplicateChunks(anyLong(), anyString(), any());
        verify(taxonomyDiscovery, never()).discoverTaxonomy(anyLong(), any());
        verify(configService, never()).mergeWithDefaults(any());
    }
}
