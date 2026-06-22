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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.ComponentPrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.ConfidencePrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.graph.reasoning.pruning.PrunePolicy;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphPruner;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphPruner.PruneResult;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.resolution.IdentityGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Orchestrates the 5-stage prune/compact pass (P1–P5) after derivation.
 *
 * <p>Health-controlled: a {@link GraphHealthSnapshot} drives which stages actually execute via
 * a {@link PruneCompactBudget}. The {@code aggressivePruneMode} flag is sticky (hysteresis)
 * — once the graph's orphan or noise rate exceeds the high-side setpoint the orchestrator
 * stays in aggressive mode until both rates drop below the low-side setpoints.</p>
 *
 * <h3>Stage order</h3>
 * <ol>
 *   <li><b>PH(pre)</b> — compute health snapshot to derive budget</li>
 *   <li><b>P1</b> — remove INFERRED edges whose atom keys were retracted this run</li>
 *   <li><b>P2</b> — entity compaction + RESOLVES_TO rematerialization (if budget permits)</li>
 *   <li><b>P3</b> — remove low-confidence INFERRED edges + standard ConfidencePruner</li>
 *   <li><b>P4</b> — orphan GC (if budget permits)</li>
 *   <li><b>P5</b> — component sweep (if budget permits)</li>
 *   <li><b>P6</b> — prior-based Opinion prune: remove INFERRED/AMBIGUOUS edges whose
 *       Subjective-Logic {@code _opinion} falls below the {@link PrunePolicy} thresholds</li>
 *   <li><b>PH(post)</b> — persist updated health snapshot</li>
 * </ol>
 *
 * <p><strong>Provenance gate:</strong> only INFERRED and AMBIGUOUS edges are ever pruned.
 * EXTRACTED edges are observed truth and are not touched.</p>
 */
@Component
public class PruneCompactOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PruneCompactOrchestrator.class);

    private final InferredFactGraphPruner inferredPruner;
    private final GraphCompactionService compactionService;
    private final IdentityGraphService identityService;
    private final ConfidencePruner confidencePruner;
    private final OrphanPruner orphanPruner;
    private final ComponentPruner componentPruner;
    private final GraphHealthService graphHealthService;
    private final OpinionPrunePass opinionPrunePass;

    /**
     * Sticky aggressive-prune mode flag — per orchestrator instance (shared across fact sheets).
     * Volatile so that it is visible across threads (the executor in GroundingCascadeHook
     * submits tasks from multiple event-listener threads).
     */
    private volatile boolean aggressivePruneMode = false;

    /**
     * Kompile-managed KB config supplying the P6 opinion-prune thresholds. Field-injected and
     * optional: when absent (e.g. unit tests constructing via the public constructor) the P6
     * pass falls back to {@link PrunePolicy#defaults()}. No {@code @Value}, no hardcoded literals
     * on the production path — the thresholds live in {@code kb-confidence-config.json}.
     */
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    public PruneCompactOrchestrator(
            InferredFactGraphPruner inferredPruner,
            GraphCompactionService compactionService,
            IdentityGraphService identityService,
            ConfidencePruner confidencePruner,
            OrphanPruner orphanPruner,
            ComponentPruner componentPruner,
            GraphHealthService graphHealthService,
            OpinionPrunePass opinionPrunePass) {
        this.inferredPruner = inferredPruner;
        this.compactionService = compactionService;
        this.identityService = identityService;
        this.confidencePruner = confidencePruner;
        this.orphanPruner = orphanPruner;
        this.componentPruner = componentPruner;
        this.graphHealthService = graphHealthService;
        this.opinionPrunePass = opinionPrunePass;
    }

    /**
     * Run the full P1–P6 cascade for the given fact sheet.
     *
     * @param factSheetId       the fact sheet to process
     * @param retractedAtomKeys atom keys retracted by BeliefReviser this run (may be empty)
     * @param runId             the hydration run ID (identifies materialized INFERRED edges)
     * @param dryRun            when true no writes are performed
     * @param setpoints         health setpoints for budget computation
     * @param opinionPolicy     Subjective-Logic prune thresholds for P6; pass
     *                          {@link PrunePolicy#defaults()} when not overridden by config
     * @return result with per-stage counts and post-prune health snapshot
     */
    public PruneCompactResult run(Long factSheetId, Set<String> retractedAtomKeys,
                                   String runId, boolean dryRun, HealthSetpoints setpoints,
                                   PrunePolicy opinionPolicy) {

        // ── PH(pre): Compute health BEFORE prune to drive budget ─────────────────
        GraphHealthSnapshot prePruneHealth;
        try {
            prePruneHealth = graphHealthService.computeSnapshot(factSheetId);
        } catch (Exception e) {
            log.warn("PruneCompactOrchestrator: health snapshot failed for factSheet={} — "
                    + "skipping prune/compact: {}", factSheetId, e.getMessage());
            return new PruneCompactResult(0, 0, 0, 0, 0, 0, null, dryRun);
        }

        PruneCompactBudget budget = PruneCompactBudget.from(prePruneHealth, setpoints,
                aggressivePruneMode);
        aggressivePruneMode = budget.aggressivePruneMode();

        log.info("PruneCompactOrchestrator: factSheet={} orphanRate={} aggressiveMode={} "
                        + "budget=[compaction={} orphanGc={} componentSweep={} theta={}]",
                factSheetId, prePruneHealth.orphanRate(), aggressivePruneMode,
                budget.runCompaction(), budget.runOrphanGc(), budget.runComponentSweep(),
                budget.confidencePruneThreshold());

        // ── P1: Remove retracted INFERRED edges ──────────────────────────────────
        PruneResult p1Result = inferredPruner.pruneRetracted(factSheetId, runId,
                retractedAtomKeys, dryRun);

        // ── P2: Entity compaction + RESOLVES_TO rematerialization ─────────────────
        int mergesPerformed = 0;
        if (budget.runCompaction()) {
            try {
                GraphCompactionService.CompactionResult compResult =
                        compactionService.compact(factSheetId,
                                GraphCompactionService.CompactionConfig.withThreshold(0.85));
                mergesPerformed = compResult.entitiesMerged();
                log.info("PruneCompactOrchestrator P2: compaction merged {} entities for factSheet={}",
                        mergesPerformed, factSheetId);
                if (mergesPerformed > 0 && !dryRun) {
                    identityService.materialize(factSheetId);
                    log.debug("PruneCompactOrchestrator P2: rematerialized RESOLVES_TO edges "
                            + "after {} merges", mergesPerformed);
                }
            } catch (Exception e) {
                log.warn("PruneCompactOrchestrator P2: compaction failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }

        // ── P3: Prune low-confidence INFERRED edges ───────────────────────────────
        PruneResult p3Result = inferredPruner.pruneByConfidence(
                factSheetId, budget.confidencePruneThreshold(), dryRun);

        // Apply the standard ConfidencePruner for node-level pruning as well
        ConfidencePrunePolicy confidencePolicy = new ConfidencePrunePolicy(
                budget.confidencePruneThreshold(),
                budget.confidencePruneThreshold(),
                1,
                true  // requireExtractedProvenance — also prunes AMBIGUOUS edges
        );
        try {
            confidencePruner.execute(factSheetId, confidencePolicy, dryRun);
        } catch (Exception e) {
            log.warn("PruneCompactOrchestrator P3: confidence pruner failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }

        // ── P4: Orphan GC ─────────────────────────────────────────────────────────
        int orphansRemoved = 0;
        if (budget.runOrphanGc()) {
            try {
                TaskReport orphanReport = orphanPruner.execute(factSheetId,
                        Duration.ofDays(7), dryRun);
                orphansRemoved = orphanReport.itemsAffected();
                log.info("PruneCompactOrchestrator P4: orphan GC affected {} nodes for factSheet={}",
                        orphansRemoved, factSheetId);
            } catch (Exception e) {
                log.warn("PruneCompactOrchestrator P4: orphan pruner failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }

        // ── P5: Component sweep ───────────────────────────────────────────────────
        int componentNodesRemoved = 0;
        if (budget.runComponentSweep()) {
            try {
                ComponentPrunePolicy componentPolicy = new ComponentPrunePolicy(2, true);
                TaskReport componentReport = componentPruner.execute(factSheetId,
                        componentPolicy, dryRun);
                componentNodesRemoved = componentReport.itemsAffected();
                log.info("PruneCompactOrchestrator P5: component sweep affected {} nodes for factSheet={}",
                        componentNodesRemoved, factSheetId);
            } catch (Exception e) {
                log.warn("PruneCompactOrchestrator P5: component pruner failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        }

        // ── P6: Prior-based Opinion prune ────────────────────────────────────────
        int opinionEdgesRemoved = 0;
        try {
            OpinionPrunePass.Result opinionResult =
                    opinionPrunePass.execute(factSheetId, opinionPolicy, dryRun);
            opinionEdgesRemoved = opinionResult.pruned();
            log.info("PruneCompactOrchestrator P6: opinion-prune affected {} edges for factSheet={}",
                    opinionEdgesRemoved, factSheetId);
        } catch (Exception e) {
            log.warn("PruneCompactOrchestrator P6: opinion prune failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }

        // ── PH(post): Persist updated health snapshot ─────────────────────────────
        GraphHealthSnapshot postHealth = null;
        try {
            postHealth = graphHealthService.persistSnapshot(factSheetId);
        } catch (Exception e) {
            log.warn("PruneCompactOrchestrator PH(post): health persist failed for factSheet={}: {}",
                    factSheetId, e.getMessage());
        }

        return new PruneCompactResult(
                p1Result.edgesDeleted(),
                p3Result.edgesDeleted(),
                mergesPerformed,
                orphansRemoved,
                componentNodesRemoved,
                opinionEdgesRemoved,
                postHealth,
                dryRun
        );
    }

    /**
     * Convenience overload that resolves the opinion-prune {@link PrunePolicy} from the
     * kompile-managed {@link KbConfig}. Existing callers that do not supply a {@link PrunePolicy}
     * (e.g. the crawl hydration pipeline) now get the config-driven thresholds for free.
     */
    public PruneCompactResult run(Long factSheetId, Set<String> retractedAtomKeys,
                                   String runId, boolean dryRun, HealthSetpoints setpoints) {
        return run(factSheetId, retractedAtomKeys, runId, dryRun, setpoints, resolveOpinionPolicy());
    }

    /**
     * Resolve the P6 opinion-prune {@link PrunePolicy} from the kompile-managed {@link KbConfig},
     * falling back to {@link PrunePolicy#defaults()} when the config manager is unavailable
     * (no {@code @Value}, no hardcoded thresholds on the production path).
     */
    private PrunePolicy resolveOpinionPolicy() {
        KbConfig c = (kbConfigManager != null) ? kbConfigManager.current() : null;
        if (c == null) {
            return PrunePolicy.defaults();
        }
        return new PrunePolicy(
                c.getPrunePolicyMinBelief(),
                c.getPrunePolicyMaxUncertainty(),
                c.getPrunePolicyMinExpectation(),
                c.isPrunePolicyPruneSuppressedBand());
    }
}
