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
package ai.kompile.enrichment.api;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.domain.EnrichmentJob;
import ai.kompile.enrichment.domain.EnrichmentResult;

import java.util.List;
import java.util.Optional;

/**
 * Main orchestrator for the post-crawl data enrichment pipeline.
 * Runs phases: CLEAN → ORGANIZE → TAXONOMY → SEARCH_INDEX.
 */
public interface DataEnrichmentService {

    /**
     * Start a full enrichment pipeline for the given fact sheet.
     */
    EnrichmentJob startEnrichment(Long factSheetId, EnrichmentConfig config);

    /**
     * Run only the CLEAN phase.
     */
    EnrichmentJob runCleanPhase(Long factSheetId, EnrichmentConfig config);

    /**
     * Run only the ORGANIZE phase.
     */
    EnrichmentJob runOrganizePhase(Long factSheetId, EnrichmentConfig config);

    // ── Individual Step Runners ────────────────────────────────────

    /** Run only chunk deduplication. */
    EnrichmentJob runDeduplication(Long factSheetId, EnrichmentConfig config);

    /** Run only graph pruning. */
    EnrichmentJob runPruning(Long factSheetId, EnrichmentConfig config);

    /** Run only graph validation. */
    EnrichmentJob runValidation(Long factSheetId, EnrichmentConfig config);

    /** Run only entity normalization. */
    EnrichmentJob runNormalization(Long factSheetId, EnrichmentConfig config);

    /** Run only taxonomy discovery. */
    EnrichmentJob runTaxonomyDiscovery(Long factSheetId, EnrichmentConfig config);

    /** Run only entity categorization (requires existing taxonomy). */
    EnrichmentJob runCategorization(Long factSheetId, EnrichmentConfig config);

    /** Run only process definition generation (requires existing taxonomy). */
    EnrichmentJob runProcessGeneration(Long factSheetId, EnrichmentConfig config);

    /**
     * Get the status of an enrichment job.
     */
    Optional<EnrichmentJob> getJob(String jobId);

    /**
     * List all enrichment jobs.
     */
    List<EnrichmentJob> listJobs();

    /**
     * Cancel a running enrichment job.
     */
    boolean cancelJob(String jobId);

    /**
     * Check if a fact sheet has been enriched.
     */
    boolean isEnriched(Long factSheetId);

    /**
     * Handle graph build completion event for auto-triggering enrichment.
     */
    void onGraphBuildCompleted(GraphBuildCompletedEvent event);
}
