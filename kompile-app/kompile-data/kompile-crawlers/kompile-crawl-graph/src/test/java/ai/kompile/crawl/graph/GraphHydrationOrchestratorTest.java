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

import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphHydrationOrchestrator}.
 *
 * <p>No Spring context: collaborators are mocked with Mockito. Tests cover:
 * <ul>
 *   <li>End-to-end ENRICHMENT run: all stages fire, progress callbacks emitted, counters aggregated.</li>
 *   <li>Graceful degradation when collaborating beans are absent.</li>
 *   <li>Stage selection via {@link HydrationConfig#enabledStageIds}.</li>
 *   <li>Callback is non-throwing: orchestrator survives a broken callback.</li>
 *   <li>Derivation failure is non-fatal: PRUNE_COMPACT still runs.</li>
 *   <li>ENRICHMENT hardDependsOn resolved correctly in {@link CrawlStepPlan}.</li>
 *   <li>Legacy crawl plan: ENRICHMENT cascades to SKIP when ENTITY_RESOLUTION is skipped.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GraphHydrationOrchestratorTest {

    @Mock
    private IncrementalReasoningOrchestrator reasoningOrchestrator;

    @Mock
    private PruneCompactOrchestrator pruneCompactOrchestrator;

    @Mock
    private FactPromotionTracker promotionTracker;

    @Mock
    private OntologyConformanceTagger ontologyConformanceTagger;

    @Mock
    private MebnTheoryRegistrationService mebnRegistrationService;

    @Mock
    private GraphNeuralScoringService graphNeuralScoringService;

    /**
     * Used only by the flag-ON MEBN tests; NOT injected in setUp() so all other tests
     * continue to use kbConfigManager=null → KbConfig.defaults() →
     * mebnTheoryRegistrationOnCrawlEnabled=false.
     */
    @Mock
    private KbConfigManager kbConfigManager;

    private GraphHydrationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new GraphHydrationOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "reasoningOrchestrator", reasoningOrchestrator);
        ReflectionTestUtils.setField(orchestrator, "pruneCompactOrchestrator", pruneCompactOrchestrator);
        ReflectionTestUtils.setField(orchestrator, "promotionTracker", promotionTracker);
        ReflectionTestUtils.setField(orchestrator, "ontologyConformanceTagger", ontologyConformanceTagger);
        // mebnRegistrationService is wired but kbConfigManager is NOT, so kbCfg() returns
        // KbConfig.defaults() where mebnTheoryRegistrationOnCrawlEnabled=false — registration
        // is never triggered in the baseline test suite.
        ReflectionTestUtils.setField(orchestrator, "mebnRegistrationService", mebnRegistrationService);

        // Default stubs for FactPromotionTracker aggregate queries so existing tests don't fail.
        // Individual learning-metrics tests override these with specific counts.
        lenient().when(promotionTracker.promotedAtomCount(anyLong())).thenReturn(0);
        lenient().when(promotionTracker.totalCorroboration(anyLong())).thenReturn(0);
        lenient().when(promotionTracker.bandCounts(anyLong())).thenReturn(java.util.Map.of());

        // Default stub for OntologyConformanceTagger so existing tests that don't care about it
        // get a safe zero-count result without needing per-test stubs.
        lenient().when(ontologyConformanceTagger.tag(anyLong(), anyBoolean()))
                .thenReturn(OntologyConformanceTagger.TagResult.empty());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1. Happy path: all stages run, counters aggregate, callbacks emitted
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void allStages_happyPath_countersAggregated() {
        when(reasoningOrchestrator.runFullReground(1L))
                .thenReturn(new RegroundResult(7, "run-abc", Set.of("retracted1", "retracted2")));

        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any(HealthSetpoints.class)))
                .thenReturn(PruneCompactResult.of(5, 3, 2, 1, 0, null, false));

        when(ontologyConformanceTagger.tag(1L, true))
                .thenReturn(new OntologyConformanceTagger.TagResult(4, 1, 0, 0));

        List<String> callbackStages = new ArrayList<>();
        HydrationResult result = orchestrator.run(1L, HydrationConfig.defaults(),
                (stage, msg) -> callbackStages.add(stage));

        // Counters
        assertEquals(7, result.relationsDerived(),   "versionsDerived from reground");
        assertEquals(2, result.retractedAtomCount(), "retracted atom count");
        assertEquals(5, result.factsRetractedPruned(), "P1 edges removed");
        assertEquals(3, result.factsConfidencePruned(), "P3 edges removed");
        assertEquals(2, result.mergesPerformed(),    "P2 merges");
        assertEquals(1, result.orphansRemoved(),     "P4 orphans");
        assertEquals(0, result.componentNodesRemoved(), "P5 components");
        assertEquals(4, result.stagesRun(),          "all 4 stages ran");
        assertEquals(0, result.gnnEdgesScored(),     "GNN service is optional and absent in baseline tests");
        assertEquals("run-abc", result.runId());

        // WEIGHT_LEARNING is an informational sub-stage label emitted inside DERIVATION
        // (immediately before runFullReground), followed by DERIVATION completion, then
        // LEARNING_METRICS (structured diagnostic payload), then the remaining stages.
        assertThat(callbackStages).containsExactly(
                GraphHydrationOrchestrator.STAGE_WEIGHT_LEARNING,
                GraphHydrationOrchestrator.STAGE_DERIVATION,
                GraphHydrationOrchestrator.STAGE_LEARNING_METRICS,
                GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT,
                GraphHydrationOrchestrator.STAGE_GNN_SCORING,
                GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE,
                GraphHydrationOrchestrator.STAGE_HEALTH);
    }

    @Test
    void gnnScoringStage_whenServiceWired_scoresEdgesAndCountsStage() {
        ReflectionTestUtils.setField(orchestrator, "graphNeuralScoringService", graphNeuralScoringService);
        when(reasoningOrchestrator.runFullReground(11L))
                .thenReturn(new RegroundResult(1, "run-gnn", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any(HealthSetpoints.class)))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));
        when(graphNeuralScoringService.scoreFactSheetEdges(
                anyLong(), anyInt(), anyInt(), anyInt(),
                any(GraphNeuralScoringService.TrainingConfig.class)))
                .thenReturn(new GraphNeuralScoringService.ScoringResult(
                        "factsheet_11", 3, 2, 2, false, "ok"));

        HydrationResult result = orchestrator.run(11L, HydrationConfig.defaults(), (stage, msg) -> {});

        assertEquals(2, result.gnnEdgesScored());
        assertEquals(5, result.stagesRun(), "DERIVATION + PRUNE + GNN + ONTOLOGY + HEALTH");
        verify(graphNeuralScoringService).scoreFactSheetEdges(
                anyLong(), anyInt(), anyInt(), anyInt(),
                any(GraphNeuralScoringService.TrainingConfig.class));
    }

    @Test
    void allStages_retractedKeysPassedToPruner() {
        Set<String> retracted = Set.of("a(x)", "b(y,z)");
        when(reasoningOrchestrator.runFullReground(2L))
                .thenReturn(new RegroundResult(1, "run-xyz", retracted));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        orchestrator.run(2L, HydrationConfig.defaults(), null);

        // Verify the exact retracted set was forwarded
        verify(pruneCompactOrchestrator).run(
                anyLong(),
                org.mockito.ArgumentMatchers.eq(retracted),
                anyString(), anyBoolean(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 2. Graceful degradation when beans are absent
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void noBeans_returnsZeroCounters_doesNotThrow() {
        GraphHydrationOrchestrator noBeansOrchestrator = new GraphHydrationOrchestrator();
        // Both optional fields left null (their Spring @Autowired wiring did not run)

        HydrationResult result = assertDoesNotThrow(() ->
                noBeansOrchestrator.run(99L, HydrationConfig.defaults(), null));

        // No derivation or prune ran; only the HEALTH sentinel counted
        assertEquals(0, result.relationsDerived());
        assertEquals(0, result.factsRetractedPruned());
        assertEquals(0, result.mergesPerformed());
        // HEALTH sentinel still increments stagesRun (it is a valid no-op stage)
        assertEquals(1, result.stagesRun());
    }

    @Test
    void noReasoningOrchestrator_prunerStillCalled() {
        // Only pruner wired — no reasoning orchestrator
        GraphHydrationOrchestrator partial = new GraphHydrationOrchestrator();
        ReflectionTestUtils.setField(partial, "pruneCompactOrchestrator", pruneCompactOrchestrator);

        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        HydrationResult result = partial.run(5L, HydrationConfig.defaults(), null);

        // Pruner ran, reasoner skipped → 2 stages (PRUNE_COMPACT + HEALTH)
        assertEquals(2, result.stagesRun());
        verify(pruneCompactOrchestrator, times(1)).run(anyLong(), anySet(), anyString(), anyBoolean(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 3. Stage selection via HydrationConfig#enabledStageIds
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void derivationOnly_prunerNotCalled() {
        when(reasoningOrchestrator.runFullReground(3L))
                .thenReturn(new RegroundResult(2, "run-d", Set.of()));

        HydrationConfig cfg = new HydrationConfig(Set.of(GraphHydrationOrchestrator.STAGE_DERIVATION), 0.4, false);
        List<String> stages = new ArrayList<>();
        orchestrator.run(3L, cfg, (s, m) -> stages.add(s));

        // DERIVATION emits the WEIGHT_LEARNING sub-stage label before its own completion callback,
        // then LEARNING_METRICS immediately after (always emitted when derivation stage runs).
        assertThat(stages).containsExactly(
                GraphHydrationOrchestrator.STAGE_WEIGHT_LEARNING,
                GraphHydrationOrchestrator.STAGE_DERIVATION,
                GraphHydrationOrchestrator.STAGE_LEARNING_METRICS);
        verify(pruneCompactOrchestrator, never()).run(anyLong(), anySet(), anyString(), anyBoolean(), any());
    }

    @Test
    void pruneCompactOnly_reasoningNotCalled() {
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(1, 2, 0, 0, 0, null, false));

        HydrationConfig cfg = new HydrationConfig(Set.of(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT), 0.4, false);
        List<String> stages = new ArrayList<>();
        orchestrator.run(4L, cfg, (s, m) -> stages.add(s));

        assertThat(stages).containsExactly(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT);
        verify(reasoningOrchestrator, never()).runFullReground(anyLong());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 4. Callback throwing does NOT abort the hydration pipeline
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void throwingCallback_pipelineContinues() {
        when(reasoningOrchestrator.runFullReground(6L))
                .thenReturn(new RegroundResult(3, "run-t", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));
        when(ontologyConformanceTagger.tag(6L, true))
                .thenReturn(new OntologyConformanceTagger.TagResult(0, 0, 0, 0));

        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(6L, HydrationConfig.defaults(),
                        (s, m) -> { throw new RuntimeException("simulated callback failure"); }));

        // All 4 stages still completed despite callback explosions
        assertEquals(4, result.stagesRun());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 5. Derivation failure is non-fatal: PRUNE_COMPACT still runs
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void derivationFails_prunerStillRuns() {
        when(reasoningOrchestrator.runFullReground(7L))
                .thenThrow(new RuntimeException("MAP solve failed"));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));
        when(ontologyConformanceTagger.tag(7L, true))
                .thenReturn(new OntologyConformanceTagger.TagResult(0, 0, 0, 0));

        List<String> stages = new ArrayList<>();
        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(7L, HydrationConfig.defaults(), (s, m) -> stages.add(s)));

        // Derivation fired its error callback; PRUNE_COMPACT, ONTOLOGY_CONFORMANCE and HEALTH still ran
        assertThat(stages).contains(
                GraphHydrationOrchestrator.STAGE_DERIVATION,   // error fallback callback
                GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT,
                GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE,
                GraphHydrationOrchestrator.STAGE_HEALTH);
        // 3 stages effectively ran (pruner + conformance + health sentinel)
        assertEquals(3, result.stagesRun());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 6. HydrationConfig.defaults() selects all stages
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void hydrationConfigDefaults_allStagesEnabled() {
        HydrationConfig cfg = HydrationConfig.defaults();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_DERIVATION)).isTrue();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT)).isTrue();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE)).isTrue();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_HEALTH)).isTrue();
        assertThat(cfg.dryRun()).isFalse();
    }

    @Test
    void hydrationConfigDryRun_passedThroughToPruner() {
        when(reasoningOrchestrator.runFullReground(8L))
                .thenReturn(new RegroundResult(0, "run-dry", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, true));

        HydrationConfig dryRun = new HydrationConfig(Set.of(), 0.4, true);
        orchestrator.run(8L, dryRun, null);

        verify(pruneCompactOrchestrator).run(anyLong(), anySet(), anyString(),
                org.mockito.ArgumentMatchers.eq(true), any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 7. ENRICHMENT hardDependsOn in CrawlPipelineStepRegistry
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void enrichmentRegistry_hardDependsOnEntityResolutionAndEdgeComputation() {
        CrawlPipelineStepRegistry.StepDescriptor enrichment =
                CrawlPipelineStepRegistry.get("ENRICHMENT");

        assertThat(enrichment).isNotNull();
        assertThat(enrichment.hardDependsOn())
                .containsExactlyInAnyOrder("ENTITY_RESOLUTION", "EDGE_COMPUTATION");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 8. CrawlStepPlan: explicit ENRICHMENT enable pulls in transitive deps
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void stepPlan_enrichmentExplicit_pullsEntityResolutionAndEdgeComputation() {
        CrawlStepPlan plan = CrawlStepPlan.from(
                UnifiedCrawlRequest.builder()
                        .enabledSteps(List.of("ENRICHMENT"))
                        .build());

        // ENRICHMENT hardDependsOn ENTITY_RESOLUTION and EDGE_COMPUTATION
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENRICHMENT"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        // Transitive closure goes deeper: ENTITY_RESOLUTION → GRAPH_EXTRACTION → CHUNKING
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("CHUNKING"));
    }

    @Test
    void stepPlan_legacyNoGraphConfig_runsMandatoryGraphSpineAndEnrichment() {
        // Legacy requests preserve the default graph-building behavior. Callers that need a
        // narrower pipeline use an explicit strict step selection.
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder().build());

        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENRICHMENT"));
    }

    @Test
    void stepPlan_nullRequest_enrichmentRuns() {
        // When request is null, every step RUNs (backward compat)
        CrawlStepPlan plan = CrawlStepPlan.from(null);
        assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENRICHMENT"));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 9. HydrationResult helpers
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void hydrationResult_totalFactsChanged_andTotalFactsPruned() {
        HydrationResult r = new HydrationResult(10, 2, 3, 5, 4, 1, 0, 0, 3, "run-1");
        assertEquals(13, r.totalFactsChanged(),  "derived + materialized");
        assertEquals(9,  r.totalFactsPruned(),   "retracted + confidence");
    }

    @Test
    void hydrationResult_empty_allZero() {
        HydrationResult e = HydrationResult.empty();
        assertEquals(0, e.relationsDerived());
        assertEquals(0, e.stagesRun());
        assertThat(e.runId()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 10. GraphEnrichmentService.enrich() (Follow-up 2: CASCADE path shares orchestrator)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void enrich_delegatesToRun_allStagesExecuted() {
        when(reasoningOrchestrator.runFullReground(10L))
                .thenReturn(new RegroundResult(1, "run-enrich", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        // enrich() must not throw and must invoke the full pipeline (DERIVATION + PRUNE_COMPACT)
        assertDoesNotThrow(() -> orchestrator.enrich(10L));

        verify(reasoningOrchestrator).runFullReground(10L);
        verify(pruneCompactOrchestrator).run(anyLong(), anySet(), anyString(), anyBoolean(), any());
    }

    @Test
    void enrich_noBeans_doesNotThrow() {
        // No beans wired — enrich() must silently succeed (non-fatal)
        GraphHydrationOrchestrator bare = new GraphHydrationOrchestrator();
        assertDoesNotThrow(() -> bare.enrich(99L));
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 11. UnifiedCrawlRequest.HydrationConfig (Follow-up 3: hydration request field)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void crawlRequest_hydrationFieldRoundTrip() {
        UnifiedCrawlRequest.HydrationConfig h = UnifiedCrawlRequest.HydrationConfig.builder()
                .enabledStageIds(Set.of("DERIVATION"))
                .confidencePruneThreshold(0.6)
                .dryRun(true)
                .build();

        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name("test-hydration")
                .hydration(h)
                .build();

        assertThat(req.getHydration()).isNotNull();
        assertThat(req.getHydration().getEnabledStageIds()).containsExactly("DERIVATION");
        assertThat(req.getHydration().getConfidencePruneThreshold()).isEqualTo(0.6);
        assertThat(req.getHydration().isDryRun()).isTrue();
    }

    @Test
    void crawlRequest_nullHydration_defaults() {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder().name("no-hydration").build();
        assertThat(req.getHydration()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 12. LearningMetrics: populated + emitted via LEARNING_METRICS callback
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void learningMetrics_populatedFromRegroundResultAndPromotionTracker() {
        // Stub derivation: 5 versions written, 3 retracted
        when(reasoningOrchestrator.runFullReground(20L))
                .thenReturn(new RegroundResult(5, "run-lm", Set.of("r1", "r2", "r3")));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        // Stub promotion tracker: 2 promoted atoms, 10 total corroborations, band distribution
        when(promotionTracker.promotedAtomCount(20L)).thenReturn(2);
        when(promotionTracker.totalCorroboration(20L)).thenReturn(10);
        when(promotionTracker.bandCounts(20L)).thenReturn(Map.of(
                StrengthBand.ESTABLISHED, 1,
                StrengthBand.HIGH,        2,
                StrengthBand.PROBABLE,    3,
                StrengthBand.SPECULATIVE, 4,
                StrengthBand.SUPPRESSED,  0));

        List<String> stages   = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        HydrationResult result = orchestrator.run(20L, HydrationConfig.defaults(),
                (s, m) -> { stages.add(s); messages.add(m); });

        // ── HydrationResult.learningMetrics() is populated ──────────────────────────────
        LearningMetrics lm = result.learningMetrics();
        assertThat(lm).isNotNull();
        assertThat(lm.derivationSkipped()).isFalse();
        assertEquals(5, lm.factVersionsWritten(),  "versionsWritten from RegroundResult");
        assertEquals(3, lm.retractedAtomCount(),   "retracted atom count from RegroundResult");
        assertEquals(2, lm.promotedAtomCount(),    "promoted atom count from FactPromotionTracker");
        assertEquals(10, lm.totalCorroboration(),  "total corroboration from FactPromotionTracker");

        // Band counts should be populated (string-keyed)
        assertThat(lm.bandCounts()).containsEntry("ESTABLISHED", 1);
        assertThat(lm.bandCounts()).containsEntry("HIGH",        2);
        assertThat(lm.bandCounts()).containsEntry("PROBABLE",    3);
        assertThat(lm.bandCounts()).containsEntry("SPECULATIVE", 4);
        assertThat(lm.bandCounts()).containsEntry("SUPPRESSED",  0);

        // Weight delta fields are NOT yet available — sentinel values expected
        assertEquals(LearningMetrics.WEIGHT_DELTA_UNAVAILABLE, lm.ruleWeightsUpdated(),
                "ruleWeightsUpdated must be WEIGHT_DELTA_UNAVAILABLE until RegroundResult exposes it");
        assertThat(Double.isNaN(lm.meanWeightDelta())).isTrue();
        assertThat(Double.isNaN(lm.maxWeightDelta())).isTrue();

        // ── LEARNING_METRICS callback was emitted in the stage sequence ───────────────────
        assertThat(stages).contains(GraphHydrationOrchestrator.STAGE_LEARNING_METRICS);

        // The LEARNING_METRICS stage must appear AFTER DERIVATION and BEFORE PRUNE_COMPACT
        int derivationIdx     = stages.indexOf(GraphHydrationOrchestrator.STAGE_DERIVATION);
        int learningMetricsIdx = stages.indexOf(GraphHydrationOrchestrator.STAGE_LEARNING_METRICS);
        int pruneCompactIdx   = stages.indexOf(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT);
        assertThat(derivationIdx).isLessThan(learningMetricsIdx);
        assertThat(learningMetricsIdx).isLessThan(pruneCompactIdx);

        // The LEARNING_METRICS message should contain the structured summary
        int lmMsgIdx = stages.indexOf(GraphHydrationOrchestrator.STAGE_LEARNING_METRICS);
        String lmMsg = messages.get(lmMsgIdx);
        assertThat(lmMsg).contains("versions=5");
        assertThat(lmMsg).contains("retracted=3");
        assertThat(lmMsg).contains("promoted=2");
        assertThat(lmMsg).contains("corroboration=10");
        assertThat(lmMsg).contains("ESTABLISHED=1");
    }

    @Test
    void learningMetrics_skippedWhenDerivationFails() {
        // Derivation throws — metrics should reflect skipped state
        when(reasoningOrchestrator.runFullReground(21L))
                .thenThrow(new RuntimeException("MAP solve blew up"));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(21L, HydrationConfig.defaults(), (s, m) -> {}));

        LearningMetrics lm = result.learningMetrics();
        assertThat(lm).isNotNull();
        assertThat(lm.derivationSkipped()).isTrue();
        assertEquals(0, lm.factVersionsWritten());
        assertEquals(0, lm.retractedAtomCount());
        assertEquals(0, lm.promotedAtomCount());
    }

    @Test
    void learningMetrics_noPromotionTracker_stillPopulatesCountsFromReground() {
        // No FactPromotionTracker wired — band counts and promotion are zero but reground
        // counts (versions + retracted) should still be populated.
        GraphHydrationOrchestrator noTrackerOrchestrator = new GraphHydrationOrchestrator();
        ReflectionTestUtils.setField(noTrackerOrchestrator, "reasoningOrchestrator", reasoningOrchestrator);
        ReflectionTestUtils.setField(noTrackerOrchestrator, "pruneCompactOrchestrator", pruneCompactOrchestrator);
        // promotionTracker intentionally NOT injected

        when(reasoningOrchestrator.runFullReground(22L))
                .thenReturn(new RegroundResult(9, "run-notracker", Set.of("x", "y")));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        HydrationResult result = noTrackerOrchestrator.run(22L, HydrationConfig.defaults(), null);

        LearningMetrics lm = result.learningMetrics();
        assertThat(lm).isNotNull();
        assertThat(lm.derivationSkipped()).isFalse();
        assertEquals(9, lm.factVersionsWritten(),  "version count from RegroundResult even without tracker");
        assertEquals(2, lm.retractedAtomCount(),   "retraction count from RegroundResult even without tracker");
        assertEquals(0, lm.promotedAtomCount(),    "zero when no tracker");
        assertEquals(0, lm.totalCorroboration(),   "zero when no tracker");
        assertThat(lm.bandCounts()).isEmpty();
    }

    @Test
    void learningMetrics_skippedWhenDerivationStageNotEnabled() {
        // When DERIVATION stage is disabled, learningMetrics should be skipped
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        HydrationConfig pruneOnly = new HydrationConfig(
                Set.of(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT), 0.4, false);
        HydrationResult result = orchestrator.run(23L, pruneOnly, null);

        LearningMetrics lm = result.learningMetrics();
        assertThat(lm).isNotNull();
        assertThat(lm.derivationSkipped()).isTrue();
        // No derivation ran → no LEARNING_METRICS callback emitted for this path
    }

    @Test
    void learningMetrics_summaryContainsKeyFields() {
        // Unit-test the LearningMetrics.summary() rendering directly
        LearningMetrics lm = new LearningMetrics(
                7, 2, 3, 18,
                Map.of("ESTABLISHED", 1, "HIGH", 3, "PROBABLE", 5, "SPECULATIVE", 8, "SUPPRESSED", 1),
                LearningMetrics.WEIGHT_DELTA_UNAVAILABLE,
                Double.NaN, Double.NaN,
                false);

        String summary = lm.summary();
        assertThat(summary).contains("versions=7");
        assertThat(summary).contains("retracted=2");
        assertThat(summary).contains("promoted=3");
        assertThat(summary).contains("corroboration=18");
        assertThat(summary).contains("ruleWeightsUpdated=N/A");
        assertThat(summary).contains("meanDelta=N/A");
        assertThat(summary).contains("ESTABLISHED=1");
    }

    @Test
    void learningMetrics_skippedSummary() {
        LearningMetrics skipped = LearningMetrics.skipped();
        assertThat(skipped.derivationSkipped()).isTrue();
        assertThat(skipped.summary()).contains("SKIPPED");
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 13. ONTOLOGY_CONFORMANCE stage: integrated with hydration pipeline
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void ontologyConformanceStage_runsAfterPruneCompact_callbackEmitted() {
        // Stub the full pipeline so all stages run
        when(reasoningOrchestrator.runFullReground(30L))
                .thenReturn(new RegroundResult(1, "run-oc", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));
        // Stub tagger: 5 conformant, 2 non-conformant
        when(ontologyConformanceTagger.tag(30L, true))
                .thenReturn(new OntologyConformanceTagger.TagResult(5, 2, 0, 0));

        List<String> stages = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        HydrationResult result = orchestrator.run(30L, HydrationConfig.defaults(),
                (s, m) -> { stages.add(s); messages.add(m); });

        // Stage appeared in the sequence
        assertThat(stages).contains(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);

        // Stage must appear AFTER PRUNE_COMPACT and BEFORE HEALTH
        int pruneIdx      = stages.indexOf(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT);
        int conformanceIdx = stages.indexOf(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);
        int healthIdx     = stages.indexOf(GraphHydrationOrchestrator.STAGE_HEALTH);
        assertThat(pruneIdx).isLessThan(conformanceIdx);
        assertThat(conformanceIdx).isLessThan(healthIdx);

        // Callback message contains the counts
        int msgIdx = stages.indexOf(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);
        String msg = messages.get(msgIdx);
        assertThat(msg).contains("conformant=5");
        assertThat(msg).contains("nonConformant=2");

        // Tagger was called exactly once
        verify(ontologyConformanceTagger, times(1)).tag(30L, true);
    }

    @Test
    void ontologyConformanceStage_taggerAbsent_noopStageSkipped() {
        // Wire orchestrator WITHOUT the tagger
        GraphHydrationOrchestrator noTagger = new GraphHydrationOrchestrator();
        ReflectionTestUtils.setField(noTagger, "reasoningOrchestrator", reasoningOrchestrator);
        ReflectionTestUtils.setField(noTagger, "pruneCompactOrchestrator", pruneCompactOrchestrator);
        ReflectionTestUtils.setField(noTagger, "promotionTracker", promotionTracker);
        // ontologyConformanceTagger intentionally NOT injected

        when(reasoningOrchestrator.runFullReground(31L))
                .thenReturn(new RegroundResult(0, "run-nt", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        List<String> stages = new ArrayList<>();
        HydrationResult result = assertDoesNotThrow(() ->
                noTagger.run(31L, HydrationConfig.defaults(), (s, m) -> stages.add(s)));

        // Stage callback still fired (skip message)
        assertThat(stages).contains(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);
    }

    @Test
    void ontologyConformanceStage_selectiveEnable_onlyTaggerCalled() {
        // Enable only ONTOLOGY_CONFORMANCE
        HydrationConfig cfg = new HydrationConfig(
                Set.of(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE), 0.4, false);

        when(ontologyConformanceTagger.tag(32L, true))
                .thenReturn(new OntologyConformanceTagger.TagResult(3, 1, 0, 0));

        List<String> stages = new ArrayList<>();
        orchestrator.run(32L, cfg, (s, m) -> stages.add(s));

        // Only the conformance stage ran
        assertThat(stages).containsExactly(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);
        verify(reasoningOrchestrator, never()).runFullReground(anyLong());
        verify(pruneCompactOrchestrator, never()).run(anyLong(), anySet(), anyString(), anyBoolean(), any());
    }

    @Test
    void ontologyConformanceStage_taggerThrows_nonFatal_pipelineContinues() {
        when(reasoningOrchestrator.runFullReground(33L))
                .thenReturn(new RegroundResult(0, "run-ex", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));
        when(ontologyConformanceTagger.tag(33L, true))
                .thenThrow(new RuntimeException("simulated tagger failure"));

        List<String> stages = new ArrayList<>();
        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(33L, HydrationConfig.defaults(), (s, m) -> stages.add(s)));

        // HEALTH sentinel still ran after the tagger failed
        assertThat(stages).contains(GraphHydrationOrchestrator.STAGE_HEALTH);
        // The ONTOLOGY_CONFORMANCE skip callback was emitted
        assertThat(stages).contains(GraphHydrationOrchestrator.STAGE_ONTOLOGY_CONFORMANCE);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 14. MEBN MTheory registration: gated by kbMebnTheoryRegistrationOnCrawlEnabled
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Flag OFF (default): {@code kbConfigManager} is NOT injected → {@code kbCfg()} returns
     * {@link KbConfig#defaults()} where {@code mebnTheoryRegistrationOnCrawlEnabled=false}.
     * {@link MebnTheoryRegistrationService#registerMTheoryForFactSheet} must never be called.
     */
    @Test
    void mebnRegistration_flagOff_default_neverCalled() {
        // kbConfigManager deliberately NOT injected — defaults used (flag=false)
        when(reasoningOrchestrator.runFullReground(40L))
                .thenReturn(new RegroundResult(1, "run-mebn-off", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        orchestrator.run(40L, HydrationConfig.defaults(), (s, m) -> {});

        verify(mebnRegistrationService, never()).registerMTheoryForFactSheet(anyLong());
        // Reground ran normally
        verify(reasoningOrchestrator, times(1)).runFullReground(40L);
    }

    /**
     * Flag ON: inject a {@link KbConfigManager} returning a config with
     * {@code mebnTheoryRegistrationOnCrawlEnabled=true}.
     * {@link MebnTheoryRegistrationService#registerMTheoryForFactSheet} must be called exactly
     * once, and it must precede {@link IncrementalReasoningOrchestrator#runFullReground} (the
     * Mockito InOrder verifies the ordering).
     */
    @Test
    void mebnRegistration_flagOn_calledOnceBeforeReground() {
        KbConfig enabledConfig = KbConfig.defaults();
        enabledConfig.setMebnTheoryRegistrationOnCrawlEnabled(true);
        when(kbConfigManager.current()).thenReturn(enabledConfig);
        ReflectionTestUtils.setField(orchestrator, "kbConfigManager", kbConfigManager);

        when(mebnRegistrationService.registerMTheoryForFactSheet(41L)).thenReturn(3);
        when(reasoningOrchestrator.runFullReground(41L))
                .thenReturn(new RegroundResult(2, "run-mebn-on", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        orchestrator.run(41L, HydrationConfig.defaults(), (s, m) -> {});

        // Registration was called exactly once with the correct factSheetId
        verify(mebnRegistrationService, times(1)).registerMTheoryForFactSheet(41L);
        // Reground ran — the theory is now registered so STEP 9 will fire inside it
        verify(reasoningOrchestrator, times(1)).runFullReground(41L);
        // Order: registration MUST precede reground
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mebnRegistrationService, reasoningOrchestrator);
        order.verify(mebnRegistrationService).registerMTheoryForFactSheet(41L);
        order.verify(reasoningOrchestrator).runFullReground(41L);
    }

    /**
     * Flag ON, but {@link MebnTheoryRegistrationService#registerMTheoryForFactSheet} throws.
     * The exception must be swallowed; the derivation (and all subsequent stages) must still run.
     */
    @Test
    void mebnRegistration_throws_doesNotAbortCrawl() {
        KbConfig enabledConfig = KbConfig.defaults();
        enabledConfig.setMebnTheoryRegistrationOnCrawlEnabled(true);
        when(kbConfigManager.current()).thenReturn(enabledConfig);
        ReflectionTestUtils.setField(orchestrator, "kbConfigManager", kbConfigManager);

        when(mebnRegistrationService.registerMTheoryForFactSheet(42L))
                .thenThrow(new RuntimeException("SSBN grounding OOM"));
        when(reasoningOrchestrator.runFullReground(42L))
                .thenReturn(new RegroundResult(5, "run-mebn-ex", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(PruneCompactResult.of(0, 0, 0, 0, 0, null, false));

        // Pipeline must not throw despite the registration failure
        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(42L, HydrationConfig.defaults(), (s, m) -> {}));

        // Reground still ran — derivation was not aborted
        verify(reasoningOrchestrator, times(1)).runFullReground(42L);
        // Derivation succeeded: versions from reground are captured
        assertEquals(5, result.relationsDerived());
        // All 4 stages completed (DERIVATION + PRUNE_COMPACT + ONTOLOGY_CONFORMANCE + HEALTH)
        assertEquals(4, result.stagesRun());
    }
}
