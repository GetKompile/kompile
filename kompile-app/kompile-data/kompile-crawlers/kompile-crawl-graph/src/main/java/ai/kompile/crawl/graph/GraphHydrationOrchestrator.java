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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphEnrichmentService;
import ai.kompile.knowledgegraph.maintenance.HealthSetpoints;
import ai.kompile.knowledgegraph.maintenance.PruneCompactOrchestrator;
import ai.kompile.knowledgegraph.maintenance.PruneCompactResult;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Client-side orchestrator for the ENRICHMENT crawl step.
 *
 * <p>Sequences the three top-level hydration stages on a single fact sheet after
 * ENTITY_RESOLUTION and EDGE_COMPUTATION have completed:
 *
 * <ol>
 *   <li><b>DERIVATION</b> — {@link IncrementalReasoningOrchestrator#runFullReground}:
 *       MAP inference (S4 rule derivation + S9 TMS contradiction pass + S10 materialization
 *       into InferredFactStore). Returns retracted atom keys for P1.</li>
 *   <li><b>PRUNE_COMPACT</b> — {@link PruneCompactOrchestrator#run}: P1 retraction pruning
 *       + P2 entity compaction + P3 confidence pruning + P4 orphan GC + P5 component sweep +
 *       PH(post) health snapshot persist.</li>
 *   <li><b>HEALTH</b> — already covered by PH(post) inside PRUNE_COMPACT; this is a
 *       no-op sentinel retained so {@link HydrationConfig#stageEnabled("HEALTH")} can
 *       gate callers that want to skip health logging.</li>
 * </ol>
 *
 * <p>Both collaborating beans are {@code @Autowired(required = false)}: when absent
 * (e.g. in test contexts lacking the KG module on the classpath) every stage is skipped
 * and {@link HydrationResult#empty()} is returned.</p>
 *
 * <p>Progress is reported via a {@code progressCallback} — a {@link BiConsumer} of
 * {@code (String stageId, String message)} — after each top-level stage completes.
 * In BATCH mode (crawl ENRICHMENT) the callback fans out to
 * {@link UnifiedCrawlGraphServiceImpl}'s {@code updatePipelineStep} + 250 ms throttle.
 * In INCREMENTAL mode (cascade hook) callers pass a no-op callback.</p>
 *
 * <p>This class is intentionally <em>the only new code</em>: every algorithm is
 * reused from the existing library + KG module. No lib modification.</p>
 */
@Component
public class GraphHydrationOrchestrator implements GraphEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(GraphHydrationOrchestrator.class);

    /**
     * Total number of stages; used by callers to initialise {@code totalItems} in
     * the pipeline-step progress tracker.
     */
    public static final int TOTAL_STAGES = 3;

    /** Stage IDs for progress callbacks and {@link HydrationConfig#stageEnabled}. */
    public static final String STAGE_DERIVATION    = "DERIVATION";
    public static final String STAGE_PRUNE_COMPACT = "PRUNE_COMPACT";
    public static final String STAGE_HEALTH        = "HEALTH";

    /**
     * Sub-stage label emitted immediately before {@link IncrementalReasoningOrchestrator#runFullReground}
     * to surface the PSL minibatch + MEBN gradient-descent weight learning that runs inside the
     * derivation/re-ground.  This is an informational label only — the actual weight-update counts
     * live inside the IncrementalReasoningOrchestrator and would require a new RegroundResult field
     * to expose; that is deferred as a follow-up.
     */
    public static final String STAGE_WEIGHT_LEARNING = "WEIGHT_LEARNING";

    @Autowired(required = false)
    @Nullable
    private IncrementalReasoningOrchestrator reasoningOrchestrator;

    @Autowired(required = false)
    @Nullable
    private PruneCompactOrchestrator pruneCompactOrchestrator;

    /**
     * Run the full hydration pipeline for the given fact sheet.
     *
     * @param factSheetId      target fact sheet
     * @param config           stage selection + thresholds
     * @param progressCallback called after each stage with {@code (stageId, message)};
     *                         must be thread-safe and non-throwing
     * @return aggregated counts across all stages
     */
    public HydrationResult run(long factSheetId,
                               HydrationConfig config,
                               BiConsumer<String, String> progressCallback) {

        if (config == null) config = HydrationConfig.defaults();

        // Accumulated result counters
        int relationsDerived        = 0;
        int retractedAtomCount      = 0;
        int factsMaterialized       = 0;
        int factsRetractedPruned    = 0;
        int factsConfidencePruned   = 0;
        int mergesPerformed         = 0;
        int orphansRemoved          = 0;
        int componentNodesRemoved   = 0;
        int stagesRun               = 0;
        String runId                = null;
        Set<String> retractedAtomKeys = Set.of();

        // ── Stage 1: DERIVATION ──────────────────────────────────────────────────────
        if (config.stageEnabled(STAGE_DERIVATION)) {
            if (reasoningOrchestrator != null) {
                try {
                    log.info("[Hydration factSheet={}] DERIVATION: starting MAP re-ground", factSheetId);
                    // Signal the learning phase before the MAP solve + weight-update runs.
                    // IncrementalReasoningOrchestrator.runFullReground() performs PSL minibatch
                    // weight learning (step 5b) and throttled MEBN finite-difference gradient
                    // descent (step 9) inside the same call.  Expose it as a distinct sub-stage
                    // so the crawl status card can name it explicitly.
                    // NOTE: the actual weight-update iteration counts are not yet surfaced here;
                    // that requires a new RegroundResult field — deferred as a follow-up.
                    safeCallback(progressCallback, STAGE_WEIGHT_LEARNING,
                            "Learning weights: PSL minibatch gradient update + MEBN finite-difference "
                            + "gradient descent (throttled every 10 cascades) — factSheet=" + factSheetId);
                    RegroundResult rr = reasoningOrchestrator.runFullReground(factSheetId);
                    relationsDerived   = rr.versionsWritten();
                    retractedAtomCount = rr.retractedAtomKeys().size();
                    retractedAtomKeys  = rr.retractedAtomKeys();
                    runId              = rr.runId();
                    stagesRun++;
                    String msg = "DERIVATION complete: " + relationsDerived + " fact version(s) derived, "
                            + retractedAtomCount + " retracted, runId=" + runId;
                    log.info("[Hydration factSheet={}] {}", factSheetId, msg);
                    safeCallback(progressCallback, STAGE_DERIVATION, msg);
                } catch (Exception e) {
                    log.warn("[Hydration factSheet={}] DERIVATION failed (non-fatal): {}",
                            factSheetId, e.getMessage(), e);
                    safeCallback(progressCallback, STAGE_DERIVATION,
                            "DERIVATION skipped: " + e.getMessage());
                }
            } else {
                log.debug("[Hydration factSheet={}] DERIVATION skipped: IncrementalReasoningOrchestrator not available",
                        factSheetId);
                safeCallback(progressCallback, STAGE_DERIVATION,
                        "DERIVATION skipped: reasoning orchestrator not available");
            }
        }

        // ── Stage 2: PRUNE_COMPACT (P1–P5 + PH(post)) ───────────────────────────────
        if (config.stageEnabled(STAGE_PRUNE_COMPACT)) {
            if (pruneCompactOrchestrator != null) {
                try {
                    log.info("[Hydration factSheet={}] PRUNE_COMPACT: starting P1–P5 pass "
                            + "(retracted={}, runId={})", factSheetId, retractedAtomKeys.size(), runId);
                    HealthSetpoints setpoints = HealthSetpoints.defaults();
                    PruneCompactResult pcr = pruneCompactOrchestrator.run(
                            factSheetId,
                            retractedAtomKeys,
                            runId != null ? runId : "hydration-" + factSheetId,
                            config.dryRun(),
                            setpoints);
                    factsRetractedPruned  = pcr.edgesRemovedP1();
                    factsConfidencePruned = pcr.edgesRemovedP3();
                    mergesPerformed       = pcr.mergesPerformedP2();
                    orphansRemoved        = pcr.orphansRemovedP4();
                    componentNodesRemoved = pcr.componentNodesRemovedP5();
                    stagesRun++;
                    String msg = "PRUNE_COMPACT complete: "
                            + "retractedEdges=" + factsRetractedPruned
                            + " confidenceEdges=" + factsConfidencePruned
                            + " merges=" + mergesPerformed
                            + " orphans=" + orphansRemoved
                            + " components=" + componentNodesRemoved
                            + (config.dryRun() ? " [DRY_RUN]" : "");
                    log.info("[Hydration factSheet={}] {}", factSheetId, msg);
                    safeCallback(progressCallback, STAGE_PRUNE_COMPACT, msg);
                } catch (Exception e) {
                    log.warn("[Hydration factSheet={}] PRUNE_COMPACT failed (non-fatal): {}",
                            factSheetId, e.getMessage(), e);
                    safeCallback(progressCallback, STAGE_PRUNE_COMPACT,
                            "PRUNE_COMPACT skipped: " + e.getMessage());
                }
            } else {
                log.debug("[Hydration factSheet={}] PRUNE_COMPACT skipped: PruneCompactOrchestrator not available",
                        factSheetId);
                safeCallback(progressCallback, STAGE_PRUNE_COMPACT,
                        "PRUNE_COMPACT skipped: pruner not available");
            }
        }

        // ── Stage 3: HEALTH — covered by PH(post) inside PruneCompactOrchestrator ───
        // This stage is a sentinel so callers can gate HEALTH-only runs via enabledStageIds.
        if (config.stageEnabled(STAGE_HEALTH)) {
            stagesRun++;
            safeCallback(progressCallback, STAGE_HEALTH,
                    "HEALTH: health snapshot persisted by PRUNE_COMPACT; factSheet=" + factSheetId);
        }

        return new HydrationResult(
                relationsDerived,
                retractedAtomCount,
                factsMaterialized,     // materialization is inside IncrementalReasoningOrchestrator
                factsRetractedPruned,
                factsConfidencePruned,
                mergesPerformed,
                orphansRemoved,
                componentNodesRemoved,
                stagesRun,
                runId);
    }

    /**
     * {@link GraphEnrichmentService} implementation — runs the full hydration pipeline with
     * {@link HydrationConfig#defaults()} and a no-op progress callback.
     *
     * <p>Called from {@code GroundingCascadeHook} (in {@code kompile-graph-change-tracking})
     * so that the CASCADE incremental path reuses the same orchestrated pipeline as the BATCH
     * crawl ENRICHMENT step. The cascade runs asynchronously after the SSE has closed, so the
     * no-op callback is the correct choice here (there is no live progress listener).</p>
     *
     * @param factSheetId the fact sheet to enrich
     */
    @Override
    public void enrich(long factSheetId) {
        try {
            run(factSheetId, HydrationConfig.defaults(), (stage, msg) -> {});
        } catch (Exception e) {
            log.warn("[Hydration] enrich() failed for factSheet={}: {}", factSheetId, e.getMessage(), e);
        }
    }

    /** Invoke callback without letting it throw back into the hydration pipeline. */
    private void safeCallback(BiConsumer<String, String> cb, String stageId, String message) {
        if (cb == null) return;
        try {
            cb.accept(stageId, message);
        } catch (Exception e) {
            log.debug("[Hydration] Progress callback threw for stage {}: {}", stageId, e.getMessage());
        }
    }
}
