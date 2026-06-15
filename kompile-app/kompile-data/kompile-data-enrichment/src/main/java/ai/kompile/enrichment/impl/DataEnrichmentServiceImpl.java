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
import ai.kompile.enrichment.api.DataEnrichmentService;
import ai.kompile.enrichment.api.EnrichmentPhase;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.config.EnrichmentConfigService;
import ai.kompile.enrichment.domain.DomainTaxonomy;
import ai.kompile.enrichment.domain.EnrichmentJob;
import ai.kompile.enrichment.domain.EnrichmentProgressEvent;
import ai.kompile.enrichment.domain.EnrichmentResult;
import ai.kompile.enrichment.impl.clean.ChunkDeduplicationService;
import ai.kompile.enrichment.impl.clean.EntityNormalizationService;
import ai.kompile.enrichment.impl.clean.GraphPruningService;
import ai.kompile.enrichment.impl.clean.GraphValidationService;
import ai.kompile.enrichment.impl.organize.DomainTaxonomyDiscoveryService;
import ai.kompile.enrichment.impl.organize.EntityCategorizationService;
import ai.kompile.enrichment.impl.organize.TaxonomySchemaPresetExporter;
import ai.kompile.enrichment.impl.search.EnrichmentSearchService;
import ai.kompile.enrichment.impl.taxonomy.TaxonomyProcessDefinitionService;
import ai.kompile.enrichment.repository.DomainTaxonomyRepository;
import ai.kompile.process.workflow.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main orchestrator for the post-crawl data enrichment pipeline.
 */
@Service
public class DataEnrichmentServiceImpl implements DataEnrichmentService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected DataEnrichmentServiceImpl() {}

    private static final Logger log = LoggerFactory.getLogger(DataEnrichmentServiceImpl.class);

    private EnrichmentConfigService configService;
    private ChunkDeduplicationService chunkDedup;
    private GraphPruningService graphPruning;
    private GraphValidationService graphValidation;
    private EntityNormalizationService entityNormalization;
    private DomainTaxonomyDiscoveryService taxonomyDiscovery;
    private EntityCategorizationService entityCategorization;
    private EntityCategoryServiceImpl categoryService;
    private TaxonomySchemaPresetExporter presetExporter;
    private TaxonomyProcessDefinitionService processDefinitionService;
    private DomainTaxonomyRepository taxonomyRepository;
    private ApplicationEventPublisher eventPublisher;

    private final Map<String, EnrichmentJob> jobs = new ConcurrentHashMap<>();

    public DataEnrichmentServiceImpl(EnrichmentConfigService configService,
                                     ChunkDeduplicationService chunkDedup,
                                     GraphPruningService graphPruning,
                                     GraphValidationService graphValidation,
                                     EntityNormalizationService entityNormalization,
                                     DomainTaxonomyDiscoveryService taxonomyDiscovery,
                                     EntityCategorizationService entityCategorization,
                                     EntityCategoryServiceImpl categoryService,
                                     TaxonomySchemaPresetExporter presetExporter,
                                     TaxonomyProcessDefinitionService processDefinitionService,
                                     DomainTaxonomyRepository taxonomyRepository,
                                     @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.chunkDedup = chunkDedup;
        this.graphPruning = graphPruning;
        this.graphValidation = graphValidation;
        this.entityNormalization = entityNormalization;
        this.taxonomyDiscovery = taxonomyDiscovery;
        this.entityCategorization = entityCategorization;
        this.categoryService = categoryService;
        this.presetExporter = presetExporter;
        this.processDefinitionService = processDefinitionService;
        this.taxonomyRepository = taxonomyRepository;
        this.eventPublisher = eventPublisher;
    }

    // ── Event Listener ──────────────────────────────────────────

    @Async
    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        EnrichmentConfig config = configService.loadConfig();
        if (!config.isAutoTriggerAfterCrawl()) {
            log.debug("Auto-trigger disabled, skipping enrichment for job {}", event.getJobId());
            return;
        }
        Long factSheetId = event.getFactSheetId();
        if (factSheetId == null) {
            log.warn("GraphBuildCompletedEvent missing factSheetId, cannot auto-enrich");
            return;
        }
        log.info("Auto-triggering enrichment for factSheet {} after crawl job {}", factSheetId, event.getJobId());
        startEnrichment(factSheetId, config);
    }

    // ── DataEnrichmentService interface ─────────────────────────

    @Override
    public EnrichmentJob startEnrichment(Long factSheetId, EnrichmentConfig config) {
        config = configService.mergeWithDefaults(config);
        String jobId = "enrich-" + UUID.randomUUID().toString().substring(0, 8);
        EnrichmentJob job = new EnrichmentJob(jobId, factSheetId);
        jobs.put(jobId, job);

        job.setStatusValue(EnrichmentJob.Status.RUNNING);
        EnrichmentConfig finalConfig = config;
        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.STARTED,
                null, null, 0, "Enrichment started", null);

        try {
            EnrichmentResult.EnrichmentResultBuilder result = EnrichmentResult.builder();
            List<EnrichmentPhase> phases = config.getEnabledPhases();

            if (phases.contains(EnrichmentPhase.CLEAN)) {
                runClean(factSheetId, jobId, job, finalConfig, result);
            }

            if (phases.contains(EnrichmentPhase.ORGANIZE)) {
                runOrganize(factSheetId, jobId, job, finalConfig, result);
            }

            if (phases.contains(EnrichmentPhase.TAXONOMY)) {
                runTaxonomy(factSheetId, job, result);
            }

            if (phases.contains(EnrichmentPhase.SEARCH_INDEX)) {
                job.setCurrentPhase(EnrichmentPhase.SEARCH_INDEX);
                job.setProgressPercent(95);
            }

            job.setResult(result.build());
            job.setStatusValue(EnrichmentJob.Status.COMPLETED);
            job.setProgressPercent(100);
            log.info("Enrichment job {} completed for factSheet {}", jobId, factSheetId);
            publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.COMPLETED,
                    null, null, 100, "Enrichment completed", job.getResult());
        } catch (Exception e) {
            log.error("Enrichment job {} failed: {}", jobId, e.getMessage(), e);
            job.setErrorMessage(e.getMessage());
            job.setStatusValue(EnrichmentJob.Status.FAILED);
            publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.FAILED,
                    null, null, job.getProgressPercent(), "Enrichment failed: " + e.getMessage(), null);
        }
        return job;
    }

    @Override
    public EnrichmentJob runCleanPhase(Long factSheetId, EnrichmentConfig config) {
        config = configService.mergeWithDefaults(config);
        String jobId = "enrich-clean-" + UUID.randomUUID().toString().substring(0, 8);
        EnrichmentJob job = new EnrichmentJob(jobId, factSheetId);
        jobs.put(jobId, job);
        job.setStatusValue(EnrichmentJob.Status.RUNNING);

        try {
            EnrichmentResult.EnrichmentResultBuilder result = EnrichmentResult.builder();
            runClean(factSheetId, jobId, job, config, result);
            job.setResult(result.build());
            job.setStatusValue(EnrichmentJob.Status.COMPLETED);
            job.setProgressPercent(100);
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatusValue(EnrichmentJob.Status.FAILED);
        }
        return job;
    }

    @Override
    public EnrichmentJob runOrganizePhase(Long factSheetId, EnrichmentConfig config) {
        config = configService.mergeWithDefaults(config);
        String jobId = "enrich-organize-" + UUID.randomUUID().toString().substring(0, 8);
        EnrichmentJob job = new EnrichmentJob(jobId, factSheetId);
        jobs.put(jobId, job);
        job.setStatusValue(EnrichmentJob.Status.RUNNING);

        try {
            EnrichmentResult.EnrichmentResultBuilder result = EnrichmentResult.builder();
            runOrganize(factSheetId, jobId, job, config, result);
            job.setResult(result.build());
            job.setStatusValue(EnrichmentJob.Status.COMPLETED);
            job.setProgressPercent(100);
        } catch (Exception e) {
            job.setErrorMessage(e.getMessage());
            job.setStatusValue(EnrichmentJob.Status.FAILED);
        }
        return job;
    }

    @Override
    public Optional<EnrichmentJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<EnrichmentJob> listJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public boolean cancelJob(String jobId) {
        EnrichmentJob job = jobs.get(jobId);
        if (job != null && job.getStatusValue() == EnrichmentJob.Status.RUNNING) {
            job.setStatusValue(EnrichmentJob.Status.CANCELLED);
            return true;
        }
        return false;
    }

    @Override
    public boolean isEnriched(Long factSheetId) {
        return taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId).isPresent();
    }

    // ── Individual Step Runners ─────────────────────────────────

    @Override
    public EnrichmentJob runDeduplication(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "dedup", config, (fid, jid, cfg, result) -> {
            int deduped = chunkDedup.deduplicateChunks(fid, jid, cfg);
            result.chunksDeduped(deduped);
        });
    }

    @Override
    public EnrichmentJob runPruning(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "prune", config, (fid, jid, cfg, result) -> {
            int pruned = graphPruning.pruneGraph(fid, jid, cfg);
            result.nodesPruned(pruned);
        });
    }

    @Override
    public EnrichmentJob runValidation(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "validate", config, (fid, jid, cfg, result) -> {
            int validated = graphValidation.validate(fid, jid, cfg);
            result.validationFixed(validated);
        });
    }

    @Override
    public EnrichmentJob runNormalization(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "normalize", config, (fid, jid, cfg, result) -> {
            int normalized = entityNormalization.normalize(fid, jid, cfg);
            result.entitiesNormalized(normalized);
        });
    }

    @Override
    public EnrichmentJob runTaxonomyDiscovery(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "discover", config, (fid, jid, cfg, result) -> {
            DomainTaxonomy taxonomy = taxonomyDiscovery.discoverTaxonomy(fid, cfg);
            categoryService.importFromTaxonomy(fid, taxonomy);
        });
    }

    @Override
    public EnrichmentJob runCategorization(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "categorize", config, (fid, jid, cfg, result) -> {
            DomainTaxonomy taxonomy = taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(fid)
                    .orElseThrow(() -> new IllegalStateException("No taxonomy found for factSheet " + fid + ". Run taxonomy discovery first."));
            int categorized = entityCategorization.categorizeEntities(fid, taxonomy);
            result.entitiesCategorized(categorized);
        });
    }

    @Override
    public EnrichmentJob runProcessGeneration(Long factSheetId, EnrichmentConfig config) {
        return runSingleStep(factSheetId, "process-gen", config, (fid, jid, cfg, result) -> {
            DomainTaxonomy taxonomy = taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(fid)
                    .orElseThrow(() -> new IllegalStateException("No taxonomy found for factSheet " + fid + ". Run taxonomy discovery first."));
            List<ProcessDefinition> definitions = processDefinitionService.generateProcessDefinitions(taxonomy);
            result.processDefinitionsCreated(definitions.size());
        });
    }

    private EnrichmentJob runSingleStep(Long factSheetId, String stepName, EnrichmentConfig config,
                                         StepRunner runner) {
        config = configService.mergeWithDefaults(config);
        String jobId = "enrich-" + stepName + "-" + UUID.randomUUID().toString().substring(0, 8);
        EnrichmentJob job = new EnrichmentJob(jobId, factSheetId);
        jobs.put(jobId, job);
        job.setStatusValue(EnrichmentJob.Status.RUNNING);
        job.setProgressPercent(10);
        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.STARTED,
                null, stepName, 10, "Running " + stepName, null);

        try {
            EnrichmentResult.EnrichmentResultBuilder result = EnrichmentResult.builder();
            runner.run(factSheetId, jobId, config, result);
            job.setResult(result.build());
            job.setStatusValue(EnrichmentJob.Status.COMPLETED);
            job.setProgressPercent(100);
            log.info("Enrichment step {} completed for factSheet {}", stepName, factSheetId);
            publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.STEP_COMPLETED,
                    null, stepName, 100, stepName + " completed", job.getResult());
        } catch (Exception e) {
            log.error("Enrichment step {} failed for factSheet {}: {}", stepName, factSheetId, e.getMessage(), e);
            job.setErrorMessage(e.getMessage());
            job.setStatusValue(EnrichmentJob.Status.FAILED);
            publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.FAILED,
                    null, stepName, job.getProgressPercent(), stepName + " failed: " + e.getMessage(), null);
        }
        return job;
    }

    @FunctionalInterface
    private interface StepRunner {
        void run(Long factSheetId, String jobId, EnrichmentConfig config,
                 EnrichmentResult.EnrichmentResultBuilder result);
    }

    private void publishEvent(String jobId, Long factSheetId, EnrichmentProgressEvent.EventType type,
                              String phase, String step, int progress, String message, EnrichmentResult result) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new EnrichmentProgressEvent(
                        this, jobId, factSheetId, type, phase, step, progress, message, result));
            } catch (Exception e) {
                log.debug("Failed to publish enrichment event: {}", e.getMessage());
            }
        }
    }

    // ── Phase Runners ───────────────────────────────────────────

    private void runClean(Long factSheetId, String jobId, EnrichmentJob job,
                          EnrichmentConfig config, EnrichmentResult.EnrichmentResultBuilder result) {
        job.setCurrentPhase(EnrichmentPhase.CLEAN);
        job.setProgressPercent(5);
        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.PHASE_STARTED,
                "CLEAN", null, 5, "Starting clean phase", null);

        int deduped = chunkDedup.deduplicateChunks(factSheetId, jobId, config);
        result.chunksDeduped(deduped);
        job.setProgressPercent(15);

        int pruned = graphPruning.pruneGraph(factSheetId, jobId, config);
        result.nodesPruned(pruned);
        job.setProgressPercent(25);

        int validated = graphValidation.validate(factSheetId, jobId, config);
        result.validationFixed(validated);
        job.setProgressPercent(35);

        int normalized = entityNormalization.normalize(factSheetId, jobId, config);
        result.entitiesNormalized(normalized);
        job.setProgressPercent(40);

        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.PHASE_COMPLETED,
                "CLEAN", null, 40, "Clean phase completed", null);
    }

    private void runOrganize(Long factSheetId, String jobId, EnrichmentJob job,
                             EnrichmentConfig config, EnrichmentResult.EnrichmentResultBuilder result) {
        job.setCurrentPhase(EnrichmentPhase.ORGANIZE);
        job.setProgressPercent(45);
        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.PHASE_STARTED,
                "ORGANIZE", null, 45, "Starting organize phase", null);

        DomainTaxonomy taxonomy = taxonomyDiscovery.discoverTaxonomy(factSheetId, config);
        job.setProgressPercent(60);

        categoryService.importFromTaxonomy(factSheetId, taxonomy);
        job.setProgressPercent(65);

        int categorized = entityCategorization.categorizeEntities(factSheetId, taxonomy);
        result.entitiesCategorized(categorized);
        job.setProgressPercent(75);

        if (config.isExportSchemaPreset()) {
            String presetId = presetExporter.exportAsPreset(factSheetId, taxonomy);
            if (presetId != null) {
                taxonomy.setSchemaPresetId(presetId);
                taxonomyRepository.save(taxonomy);
            }
        }
        job.setProgressPercent(80);

        publishEvent(jobId, factSheetId, EnrichmentProgressEvent.EventType.PHASE_COMPLETED,
                "ORGANIZE", null, 80, "Organize phase completed", null);
    }

    private void runTaxonomy(Long factSheetId, EnrichmentJob job, EnrichmentResult.EnrichmentResultBuilder result) {
        job.setCurrentPhase(EnrichmentPhase.TAXONOMY);
        job.setProgressPercent(85);

        taxonomyRepository.findTopByFactSheetIdOrderByVersionDesc(factSheetId).ifPresent(taxonomy -> {
            List<ProcessDefinition> definitions = processDefinitionService.generateProcessDefinitions(taxonomy);
            result.processDefinitionsCreated(definitions.size());
        });
        job.setProgressPercent(90);
    }
}
