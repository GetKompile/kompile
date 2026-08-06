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
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.crawl.graph.ontology.OntologyConformanceTagger;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.maintenance.HealthSetpoints;
import ai.kompile.knowledgegraph.maintenance.PruneCompactOrchestrator;
import ai.kompile.knowledgegraph.maintenance.PruneCompactResult;
import ai.kompile.knowledgegraph.matrix.gnn.GraphNeuralScoringService;
import ai.kompile.knowledgegraph.reasoning.FactPromotionTracker;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.MebnTheoryRegistrationService;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Client-side orchestrator for the ENRICHMENT crawl step.
 *
     * <p>Sequences the top-level hydration stages on a single fact sheet after
 * ENTITY_RESOLUTION and EDGE_COMPUTATION have completed:
 *
 * <ol>
 *   <li><b>DERIVATION</b> — {@link IncrementalReasoningOrchestrator#runFullReground}:
 *       MAP inference (S4 rule derivation + S9 TMS contradiction pass + S10 materialization
 *       into InferredFactStore). Returns retracted atom keys for P1.</li>
     *   <li><b>PRUNE_COMPACT</b> — {@link PruneCompactOrchestrator#run}: P1 retraction pruning
     *       + P2 entity compaction + P3 confidence pruning + P4 orphan GC + P5 component sweep +
     *       PH(post) health snapshot persist.</li>
     *   <li><b>GNN_SCORING</b> — score existing retained edges via {@link GraphNeuralScoringService}
     *       and merge neural score metadata for UI and downstream ranking.</li>
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
    public static final int TOTAL_STAGES = 7;

    /** Stage IDs for progress callbacks and {@link HydrationConfig#stageEnabled}. */
    public static final String STAGE_DERIVATION          = "DERIVATION";
    public static final String STAGE_PRUNE_COMPACT       = "PRUNE_COMPACT";
    public static final String STAGE_GNN_SCORING         = "GNN_SCORING";
    public static final String STAGE_HEALTH              = "HEALTH";

    /**
     * Stage ID for the ontology-conformance hydration pass.
     *
     * <p>When a fact sheet has a bound ontology, tags every ENTITY node with
     * {@code ontology.conformant=true/false} (and optionally edges).  LENIENT: never
     * deletes or drops any node; tag only.  When no ontology is bound — or when
     * {@link OntologyConformanceTagger} / {@link ai.kompile.core.graphrag.conformance.OntologyProjectionProvider}
     * is not wired — the stage is a clean no-op that returns zero counts.</p>
     */
    public static final String STAGE_ONTOLOGY_CONFORMANCE = "ONTOLOGY_CONFORMANCE";

    /**
     * Sub-stage label emitted immediately before {@link IncrementalReasoningOrchestrator#runFullReground}
     * to surface the PSL minibatch + MEBN gradient-descent weight learning that runs inside the
     * derivation/re-ground.  This is an informational label only — the actual weight-update counts
     * live inside the IncrementalReasoningOrchestrator and would require a new RegroundResult field
     * to expose; that is deferred as a follow-up.
     */
    public static final String STAGE_WEIGHT_LEARNING = "WEIGHT_LEARNING";

    /**
     * Stage ID for the structured {@link LearningMetrics} callback emitted after derivation
     * completes (or is skipped).  Callers (e.g. the crawl status card) can filter on this key
     * to extract the rich learning diagnostic payload from the progress stream.
     *
     * <p>The callback message for this stage is a
     * {@link LearningMetrics#summary()} string rather than a plain text log line,
     * so UI consumers can parse the structured fields.</p>
     */
    public static final String STAGE_LEARNING_METRICS = "LEARNING_METRICS";

    @Autowired(required = false)
    @Nullable
    private IncrementalReasoningOrchestrator reasoningOrchestrator;

    @Autowired(required = false)
    @Nullable
    private PruneCompactOrchestrator pruneCompactOrchestrator;

    /**
     * Optional: fact-promotion tracker.  When non-null, {@link #collectLearningMetrics} queries
     * aggregate band counts, promotion counts, and total corroboration for the fact sheet so they
     * appear in the returned {@link LearningMetrics}.  Null in plain-Java test contexts or
     * Spring contexts that have not loaded the knowledge-graph module.
     */
    @Autowired(required = false)
    @Nullable
    private FactPromotionTracker promotionTracker;

    /**
     * Optional: ontology-conformance tagger.  When non-null, the
     * {@link #STAGE_ONTOLOGY_CONFORMANCE} stage tags each ENTITY node with
     * {@code ontology.conformant=true/false}.  The tagger itself guards against a missing
     * {@link ai.kompile.core.graphrag.conformance.OntologyProjectionProvider} and returns zero
     * counts when no ontology is bound to the fact sheet.  Null only in test/minimal Spring
     * contexts that exclude the crawl-graph module entirely.
     */
    @Autowired(required = false)
    @Nullable
    private OntologyConformanceTagger ontologyConformanceTagger;

    /**
     * Optional: KB config manager for reading the derivation time budget and PSL epoch cap.
     * Null in plain-Java test contexts and Spring contexts that exclude kompile-knowledge-graph.
     */
    @Autowired(required = false)
    @Nullable
    private KbConfigManager kbConfigManager;

    /** Optional: bounded graph-neural edge scorer for crawl-time neural overlays. */
    @Autowired(required = false)
    @Nullable
    private GraphNeuralScoringService graphNeuralScoringService;

    /**
     * Optional: MEBN MTheory registration service.  When non-null and
     * {@code kbMebnTheoryRegistrationOnCrawlEnabled} is {@code true} in the live
     * {@link KbConfig}, this service is called once per fact sheet immediately before
     * {@link IncrementalReasoningOrchestrator#runFullReground} so that STEP 9 (SSBN
     * weight learning) fires inside the derivation cascade.
     *
     * <p>Null in plain-Java test contexts or Spring contexts that have not loaded the
     * knowledge-graph module.  The flag defaults to {@code false}, so a normal crawl is
     * byte-for-byte unchanged when this bean is absent.</p>
     */
    @Autowired(required = false)
    @Nullable
    private MebnTheoryRegistrationService mebnRegistrationService;

    /**
     * Optional: ontology projection provider (read side of the ontology-binding bridge). Used to detect
     * whether a fact sheet has a bound ontology so MEBN registration can auto-enable for that deliberate
     * subset ({@code kbMebnAutoEnableWhenOntologyBound}). Null in test contexts / Spring contexts without
     * app-main's implementation → treated as "no ontology bound" (permissive: MEBN simply does not fire).
     */
    @Autowired(required = false)
    @Nullable
    private OntologyProjectionProvider ontologyProjectionProvider;

    /** Return the current KB config (defaults when no manager is wired). */
    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

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
        int gnnEdgesScored          = 0;
        int stagesRun               = 0;
        String runId                = null;
        Set<String> retractedAtomKeys = Set.of();
        boolean derivationAttempted = false;
        boolean derivationSucceeded = false;
        LearningMetrics learningMetrics = LearningMetrics.skipped();

        // ── Stage 1: DERIVATION ──────────────────────────────────────────────────────
        if (config.stageEnabled(STAGE_DERIVATION)) {
            if (reasoningOrchestrator != null) {
                derivationAttempted = true;
                try {
                    KbConfig cfg = kbCfg();
                    long budgetMs = cfg.getDerivationTimeBudgetMs();
                    log.info("[Hydration factSheet={}] DERIVATION: starting MAP re-ground (timeBudgetMs={}, "
                            + "pslEpochsCap={}, maxAtoms={})",
                            factSheetId, budgetMs > 0 ? budgetMs : "unlimited",
                            cfg.getDerivationPslMaxEpochs() > 0 ? cfg.getDerivationPslMaxEpochs() : "inherit",
                            cfg.getDerivationMaxAtoms() > 0 ? cfg.getDerivationMaxAtoms() : "unlimited");

                    // Signal the learning phase before the MAP solve + weight-update runs.
                    // IncrementalReasoningOrchestrator.runFullReground() performs PSL minibatch
                    // weight learning (step 5b) inside the same call. MEBN gradient descent
                    // (step 9) runs only when a theory has been registered via registerMTheory().
                    // Emit a conservative pre-derivation label (not "MEBN gradient descent run"
                    // since MEBN may not actually fire on this cascade).
                    safeCallback(progressCallback, STAGE_WEIGHT_LEARNING,
                            "Derivation + weight learning starting (PSL online gradient; MEBN when theory registered)"
                            + " — factSheet=" + factSheetId);

                    // ── MEBN MTheory pre-registration ────────────────────────────────────────────────
                    // Register a bounded, typed MTheory from live graph topology so STEP 9 (SSBN
                    // gradient-descent weight learning) fires inside runFullReground below. Enabled when
                    // EITHER kbMebnTheoryRegistrationOnCrawlEnabled (all crawls, default OFF) OR
                    // kbMebnAutoEnableWhenOntologyBound (default ON) AND this fact sheet has a bound
                    // ontology — the deliberate, structured subset where MEBN is "real by default"
                    // (reasoning-stack rec 4). The theory is bounded (≤20 MFrags, edge-gated) and SSBN
                    // learning is throttled to every kbMebnLearningInterval cascades, so the per-crawl
                    // cost is low. Failures are non-fatal: the derivation still runs without MEBN.
                    boolean mebnByFlag = kbCfg().isMebnTheoryRegistrationOnCrawlEnabled();
                    boolean mebnByOntology = kbCfg().isMebnAutoEnableWhenOntologyBound()
                            && ontologyProjectionProvider != null
                            && ontologyProjectionProvider.hasBoundOntology(factSheetId);
                    if (mebnRegistrationService != null && (mebnByFlag || mebnByOntology)) {
                        try {
                            int mFragCount = mebnRegistrationService.registerMTheoryForFactSheet(factSheetId);
                            log.info("[Hydration factSheet={}] MEBN MTheory pre-registered: {} MFrag(s) "
                                    + "(trigger: {}) — SSBN weight learning will run in this cascade",
                                    factSheetId, mFragCount, mebnByFlag ? "crawl-flag" : "ontology-bound");
                        } catch (Exception mebnEx) {
                            log.warn("[Hydration factSheet={}] MEBN MTheory registration failed "
                                    + "(non-fatal, crawl continues): {}",
                                    factSheetId, mebnEx.getMessage(), mebnEx);
                        }
                    }

                    // ── DERIVATION TIME BUDGET ────────────────────────────────────────────
                    // Wrap runFullReground in a bounded future so a pathological grounding
                    // (e.g. 500k ground rules × 50 MAP epochs) cannot hold the crawl hostage.
                    // If budgetMs <= 0, run synchronously without a cap (legacy behaviour).
                    final RegroundResult rr;
                    if (budgetMs > 0) {
                        // Use a single-thread executor so the grounding runs on a named thread
                        // (not a ForkJoinPool worker) and can be interrupted cleanly.
                        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
                            Thread t = new Thread(r, "derivation-" + factSheetId);
                            t.setDaemon(true);
                            return t;
                        });
                        Future<RegroundResult> future = exec.submit(
                                () -> reasoningOrchestrator.runFullReground(factSheetId));
                        exec.shutdown();
                        try {
                            rr = future.get(budgetMs, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException tex) {
                            // Use cancel(false) — do NOT interrupt the derivation thread.
                            // The derivation thread holds shared DB connections; interrupting it
                            // (cancel(true)) causes ClosedByInterruptException on open NIO channels.
                            // The thread is a daemon so it will not prevent JVM exit.
                            future.cancel(false);
                            exec.shutdownNow();
                            log.warn("[Hydration factSheet={}] DERIVATION timed out after {}ms — "
                                    + "crawl continues (partial results materialized up to timeout)",
                                    factSheetId, budgetMs);
                            safeCallback(progressCallback, STAGE_DERIVATION,
                                    "DERIVATION timed out after " + budgetMs + "ms (budget cap) — "
                                    + "crawl continues; increase kbDerivationTimeBudgetMs to allow more time");
                            // Fall through to LEARNING_METRICS emission below with skipped=true
                            throw new RuntimeException("DERIVATION_TIMEOUT:" + budgetMs + "ms", tex);
                        } catch (ExecutionException eex) {
                            exec.shutdownNow();
                            Throwable cause = eex.getCause() != null ? eex.getCause() : eex;
                            throw new RuntimeException(cause.getMessage(), cause);
                        } catch (InterruptedException iex) {
                            // Do NOT use cancel(true) — clear local interrupt and let derivation
                            // finish naturally on the daemon thread.
                            future.cancel(false);
                            exec.shutdownNow();
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("DERIVATION interrupted", iex);
                        }
                    } else {
                        rr = reasoningOrchestrator.runFullReground(factSheetId);
                    }

                    relationsDerived   = rr.versionsWritten();
                    retractedAtomCount = rr.retractedAtomKeys().size();
                    retractedAtomKeys  = rr.retractedAtomKeys();
                    runId              = rr.runId();
                    derivationSucceeded = true;
                    stagesRun++;
                    // Surface a structured diagnosis when 0 facts were derived.
                    // The reasoning library already logs the detailed explanation (atom value
                    // distribution, stable-fixed-point vs empty FactStore) at INFO in
                    // IncrementalReasoningOrchestrator and GraphToFactStoreProjector.
                    // Here we propagate a short structured label into the crawl progress
                    // callback (recordHydrationSubStageProgress → recordEvent → SSE) so the
                    // crawl status card can show "why 0" without requiring log access.
                    final String msg;
                    if (relationsDerived == 0 && retractedAtomCount > 0) {
                        msg = "DERIVATION complete: 0 new fact version(s) derived "
                                + "(stable fixed-point — all MAP posteriors match existing store within ε="
                                + "0.001), " + retractedAtomCount + " atoms from prior cascade(s) "
                                + "no longer produced by this run (implicit retraction), runId=" + runId
                                + ". This is normal on re-crawls when the graph has not changed.";
                    } else if (relationsDerived == 0) {
                        msg = "DERIVATION complete: 0 fact version(s) derived, "
                                + retractedAtomCount + " retracted, runId=" + runId
                                + ". Check IncrementalReasoningOrchestrator logs for root cause.";
                    } else {
                        msg = "DERIVATION complete: " + relationsDerived + " fact version(s) derived, "
                                + retractedAtomCount + " retracted, runId=" + runId;
                    }
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

            // ── LEARNING_METRICS callback: emit after derivation regardless of outcome ──────
            // Collect rich learning diagnostics from the promotion tracker (if available) and
            // from the RegroundResult fields we have access to.  Even when derivation failed
            // or was skipped, emit a SKIPPED metrics object so the status card always gets
            // a LEARNING_METRICS event and can update its display accordingly.
            boolean skippedDerivation = !derivationAttempted || !derivationSucceeded;
            learningMetrics = collectLearningMetrics(
                    factSheetId, relationsDerived, retractedAtomCount, skippedDerivation);
            safeCallback(progressCallback, STAGE_LEARNING_METRICS, learningMetrics.summary());
            log.debug("[Hydration factSheet={}] {}", factSheetId, learningMetrics.summary());
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

        // ── Stage 3: GNN_SCORING — train, score, and dispose a bounded link model ──────
        if (config.stageEnabled(STAGE_GNN_SCORING)) {
            KbConfig cfg = kbCfg();
            if (!cfg.isGnnScoringOnCrawlEnabled()) {
                safeCallback(progressCallback, STAGE_GNN_SCORING,
                        "GNN_SCORING skipped: disabled by kbGnnScoringOnCrawlEnabled=false");
            } else if (graphNeuralScoringService != null) {
                try {
                    log.info("[Hydration factSheet={}] GNN_SCORING: training bounded CPU link model "
                                    + "(maxNodes={}, maxEdges={}, batchSize={}, epochs={})",
                            factSheetId, cfg.getGnnMaxNodes(), cfg.getGnnMaxEdges(),
                            cfg.getGnnScoreBatchSize(), cfg.getGnnTrainingEpochs());
                    GraphNeuralScoringService.ScoringResult gnn = graphNeuralScoringService.scoreFactSheetEdges(
                            factSheetId,
                            cfg.getGnnMaxNodes(),
                            cfg.getGnnMaxEdges(),
                            cfg.getGnnScoreBatchSize(),
                            new GraphNeuralScoringService.TrainingConfig(
                                    cfg.getGnnSelfWeight(),
                                    cfg.getGnnNeighborWeight(),
                                    cfg.getGnnTrainingEpochs(),
                                    cfg.getGnnLearningRate(),
                                    cfg.getGnnNegativeSamplesPerPositive(),
                                    cfg.getGnnMaxPositiveTrainingEdges(),
                                    cfg.getGnnTrainingSeed(),
                                    cfg.getGnnL2()));
                    gnnEdgesScored = gnn.edgesScored();
                    stagesRun++;
                    String msg = gnn.skipped()
                            ? "GNN_SCORING skipped: " + gnn.reason()
                                    + " (nodes=" + gnn.nodeCount() + ", edges=" + gnn.edgesSeen() + ")"
                            : "GNN_SCORING complete: scoredEdges=" + gnnEdgesScored
                                    + " edgesSeen=" + gnn.edgesSeen()
                                    + " graphId=" + gnn.graphId()
                                    + " model=" + gnn.modelName()
                                    + " initialLoss=" + gnn.initialLoss()
                                    + " finalLoss=" + gnn.finalLoss()
                                    + " fingerprint=" + gnn.modelFingerprint();
                    log.info("[Hydration factSheet={}] {}", factSheetId, msg);
                    safeCallback(progressCallback, STAGE_GNN_SCORING, msg);
                } catch (Exception e) {
                    log.warn("[Hydration factSheet={}] GNN_SCORING failed (non-fatal): {}",
                            factSheetId, e.getMessage(), e);
                    safeCallback(progressCallback, STAGE_GNN_SCORING,
                            "GNN_SCORING skipped: " + e.getMessage());
                }
            } else {
                log.debug("[Hydration factSheet={}] GNN_SCORING skipped: GraphNeuralScoringService not available",
                        factSheetId);
                safeCallback(progressCallback, STAGE_GNN_SCORING,
                        "GNN_SCORING skipped: trainable link-scoring service not available");
            }
        }

        // ── Stage 4: ONTOLOGY_CONFORMANCE — tag nodes against bound ontology ───────────
        // LENIENT: tags only, never deletes.  No-op when no ontology is bound or tagger absent.
        int nodesConformant    = 0;
        int nodesNonConformant = 0;
        if (config.stageEnabled(STAGE_ONTOLOGY_CONFORMANCE)) {
            if (ontologyConformanceTagger != null) {
                try {
                    log.info("[Hydration factSheet={}] ONTOLOGY_CONFORMANCE: tagging nodes " +
                            "for ontology conformance", factSheetId);
                    OntologyConformanceTagger.TagResult tr =
                            ontologyConformanceTagger.tag(factSheetId, true);
                    nodesConformant    = tr.nodesTaggedConformant();
                    nodesNonConformant = tr.nodesTaggedNonConformant();
                    stagesRun++;
                    String msg = "ONTOLOGY_CONFORMANCE complete: "
                            + "conformant=" + nodesConformant
                            + " nonConformant=" + nodesNonConformant
                            + " edgesTagged=" + tr.totalEdgesTagged();
                    log.info("[Hydration factSheet={}] {}", factSheetId, msg);
                    safeCallback(progressCallback, STAGE_ONTOLOGY_CONFORMANCE, msg);
                } catch (Exception e) {
                    log.warn("[Hydration factSheet={}] ONTOLOGY_CONFORMANCE failed (non-fatal): {}",
                            factSheetId, e.getMessage(), e);
                    safeCallback(progressCallback, STAGE_ONTOLOGY_CONFORMANCE,
                            "ONTOLOGY_CONFORMANCE skipped: " + e.getMessage());
                }
            } else {
                log.debug("[Hydration factSheet={}] ONTOLOGY_CONFORMANCE skipped: " +
                        "OntologyConformanceTagger not available", factSheetId);
                safeCallback(progressCallback, STAGE_ONTOLOGY_CONFORMANCE,
                        "ONTOLOGY_CONFORMANCE skipped: tagger not available");
            }
        }

        // ── Stage 5: HEALTH — covered by PH(post) inside PruneCompactOrchestrator ───
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
                gnnEdgesScored,
                stagesRun,
                runId,
                learningMetrics);     // populated during DERIVATION stage; skipped() if stage not enabled
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

    /**
     * Collect {@link LearningMetrics} for the current enrichment pass.
     *
     * <p>Sources:
     * <ul>
     *   <li>{@code factVersionsWritten} / {@code retractedAtomCount} — from the
     *       {@link ai.kompile.knowledgegraph.reasoning.RegroundResult} already extracted by the
     *       calling {@link #run} method.</li>
     *   <li>{@code promotedAtomCount} / {@code totalCorroboration} / {@code bandCounts} — from
     *       {@link FactPromotionTracker} aggregate queries; zeroed when tracker is null (no Spring).</li>
     *   <li>{@code ruleWeightsUpdated} / {@code meanWeightDelta} / {@code maxWeightDelta} — NOT
     *       available from the current API surface; set to
     *       {@link LearningMetrics#WEIGHT_DELTA_UNAVAILABLE} / {@link Double#NaN}.
     *       To populate: extend {@link ai.kompile.knowledgegraph.reasoning.RegroundResult} with a
     *       {@code Map<String,Double>} of per-rule weight deltas, or publish a dedicated
     *       {@code PslWeightEvent} from inside
     *       {@link IncrementalReasoningOrchestrator}.</li>
     * </ul>
     *
     * @param factSheetId       the fact sheet being enriched
     * @param versionsWritten   inferred-fact versions written (from RegroundResult)
     * @param retractedCount    implicit retraction count (from RegroundResult)
     * @param derivationSkipped true when derivation did not complete successfully
     * @return populated metrics; never null
     */
    private LearningMetrics collectLearningMetrics(long factSheetId,
                                                   int versionsWritten,
                                                   int retractedCount,
                                                   boolean derivationSkipped) {
        if (derivationSkipped) {
            return LearningMetrics.skipped();
        }

        int promoted      = 0;
        int corroboration = 0;
        Map<String, Integer> bandCountsStr = Map.of();

        if (promotionTracker != null) {
            try {
                promoted      = promotionTracker.promotedAtomCount(factSheetId);
                corroboration = promotionTracker.totalCorroboration(factSheetId);
                // Convert StrengthBand keys to String so LearningMetrics has no direct
                // dependency on the graph-reasoning StrengthBand enum (keeps the crawl-graph
                // module decoupled from the reasoning API shape).
                Map<StrengthBand, Integer> rawCounts = promotionTracker.bandCounts(factSheetId);
                if (rawCounts != null && !rawCounts.isEmpty()) {
                    LinkedHashMap<String, Integer> bands = new LinkedHashMap<>();
                    for (Map.Entry<StrengthBand, Integer> e : rawCounts.entrySet()) {
                        bands.put(e.getKey().name(), e.getValue());
                    }
                    bandCountsStr = Collections.unmodifiableMap(bands);
                }
            } catch (Exception e) {
                log.debug("[Hydration factSheet={}] Could not collect promotion metrics: {}",
                        factSheetId, e.getMessage());
            }
        }

        return new LearningMetrics(
                versionsWritten,
                retractedCount,
                promoted,
                corroboration,
                bandCountsStr,
                LearningMetrics.WEIGHT_DELTA_UNAVAILABLE,  // not yet surfaced by RegroundResult
                Double.NaN,                                 // meanWeightDelta: deferred
                Double.NaN,                                 // maxWeightDelta: deferred
                false);
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

    // ── Single-stage re-run entry points (P3 per-step resumability) ───────────────

    /**
     * Re-run only the DERIVATION stage for the given fact sheet.
     *
     * <p>Delegates to {@link #run} with a {@link HydrationConfig} that enables only
     * {@code DERIVATION}.  Safe to call when the reasoning orchestrator is null — returns
     * {@link HydrationResult#empty()} and logs a warning.</p>
     *
     * @param factSheetId the fact sheet to re-derive
     * @return hydration result covering only the derivation stage
     */
    public HydrationResult runDerivationOnly(long factSheetId) {
        if (reasoningOrchestrator == null) {
            log.warn("[Hydration factSheet={}] runDerivationOnly: reasoningOrchestrator not wired — skipping",
                    factSheetId);
            return HydrationResult.empty();
        }
        HydrationConfig cfg = new HydrationConfig(
                Set.of(STAGE_DERIVATION), 0.4, false);
        return run(factSheetId, cfg, (stage, msg) ->
                log.debug("[Hydration factSheet={}] DERIVATION re-run [{}]: {}", factSheetId, stage, msg));
    }

    /**
     * Re-run only the PRUNE_COMPACT stage for the given fact sheet.
     *
     * <p>Safe to call when {@link PruneCompactOrchestrator} is null — returns
     * {@link HydrationResult#empty()} and logs a warning.  Always live (not dry-run);
     * uses default health setpoints.</p>
     *
     * @param factSheetId the fact sheet to prune/compact
     * @return hydration result covering only the prune-compact stage
     */
    public HydrationResult runPruneOnly(long factSheetId) {
        if (pruneCompactOrchestrator == null) {
            log.warn("[Hydration factSheet={}] runPruneOnly: pruneCompactOrchestrator not wired — skipping",
                    factSheetId);
            return HydrationResult.empty();
        }
        HydrationConfig cfg = new HydrationConfig(
                Set.of(STAGE_PRUNE_COMPACT), 0.4, false);
        return run(factSheetId, cfg, (stage, msg) ->
                log.debug("[Hydration factSheet={}] PRUNE re-run [{}]: {}", factSheetId, stage, msg));
    }

    /**
     * Re-run only the ONTOLOGY_CONFORMANCE stage for the given fact sheet.
     *
     * <p>When no {@link OntologyConformanceTagger} or no ontology is bound to the fact sheet,
     * the tagger returns {@link OntologyConformanceTagger.TagResult#empty()} (zero counts) and
     * this method returns {@link HydrationResult#empty()}.  Tag-only: never deletes or drops nodes.</p>
     *
     * @param factSheetId the fact sheet to tag
     * @return hydration result covering only the conformance stage
     */
    public HydrationResult runOntologyConformanceOnly(long factSheetId) {
        if (ontologyConformanceTagger == null) {
            log.warn("[Hydration factSheet={}] runOntologyConformanceOnly: ontologyConformanceTagger not wired — skipping",
                    factSheetId);
            return HydrationResult.empty();
        }
        HydrationConfig cfg = new HydrationConfig(
                Set.of(STAGE_ONTOLOGY_CONFORMANCE), 0.4, false);
        return run(factSheetId, cfg, (stage, msg) ->
                log.debug("[Hydration factSheet={}] ONTOLOGY_CONFORMANCE re-run [{}]: {}", factSheetId, stage, msg));
    }
}
