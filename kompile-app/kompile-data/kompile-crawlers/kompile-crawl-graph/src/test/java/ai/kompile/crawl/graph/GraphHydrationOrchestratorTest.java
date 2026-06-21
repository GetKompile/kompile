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
import ai.kompile.knowledgegraph.maintenance.HealthSetpoints;
import ai.kompile.knowledgegraph.maintenance.PruneCompactOrchestrator;
import ai.kompile.knowledgegraph.maintenance.PruneCompactResult;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
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

    private GraphHydrationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new GraphHydrationOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "reasoningOrchestrator", reasoningOrchestrator);
        ReflectionTestUtils.setField(orchestrator, "pruneCompactOrchestrator", pruneCompactOrchestrator);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 1. Happy path: all stages run, counters aggregate, callbacks emitted
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void allStages_happyPath_countersAggregated() {
        when(reasoningOrchestrator.runFullReground(1L))
                .thenReturn(new RegroundResult(7, "run-abc", Set.of("retracted1", "retracted2")));

        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any(HealthSetpoints.class)))
                .thenReturn(new PruneCompactResult(5, 3, 2, 1, 0, null, false));

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
        assertEquals(3, result.stagesRun(),          "all 3 stages ran");
        assertEquals("run-abc", result.runId());

        // All three stage callbacks fired
        assertThat(callbackStages).containsExactly(
                GraphHydrationOrchestrator.STAGE_DERIVATION,
                GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT,
                GraphHydrationOrchestrator.STAGE_HEALTH);
    }

    @Test
    void allStages_retractedKeysPassedToPruner() {
        Set<String> retracted = Set.of("a(x)", "b(y,z)");
        when(reasoningOrchestrator.runFullReground(2L))
                .thenReturn(new RegroundResult(1, "run-xyz", retracted));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(new PruneCompactResult(0, 0, 0, 0, 0, null, false));

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
                .thenReturn(new PruneCompactResult(0, 0, 0, 0, 0, null, false));

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

        assertThat(stages).containsExactly(GraphHydrationOrchestrator.STAGE_DERIVATION);
        verify(pruneCompactOrchestrator, never()).run(anyLong(), anySet(), anyString(), anyBoolean(), any());
    }

    @Test
    void pruneCompactOnly_reasoningNotCalled() {
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(new PruneCompactResult(1, 2, 0, 0, 0, null, false));

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
                .thenReturn(new PruneCompactResult(0, 0, 0, 0, 0, null, false));

        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(6L, HydrationConfig.defaults(),
                        (s, m) -> { throw new RuntimeException("simulated callback failure"); }));

        // All 3 stages still completed despite callback explosions
        assertEquals(3, result.stagesRun());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 5. Derivation failure is non-fatal: PRUNE_COMPACT still runs
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void derivationFails_prunerStillRuns() {
        when(reasoningOrchestrator.runFullReground(7L))
                .thenThrow(new RuntimeException("MAP solve failed"));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(new PruneCompactResult(0, 0, 0, 0, 0, null, false));

        List<String> stages = new ArrayList<>();
        HydrationResult result = assertDoesNotThrow(() ->
                orchestrator.run(7L, HydrationConfig.defaults(), (s, m) -> stages.add(s)));

        // Derivation fired its error callback; PRUNE_COMPACT and HEALTH still ran
        assertThat(stages).contains(
                GraphHydrationOrchestrator.STAGE_DERIVATION,   // error fallback callback
                GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT,
                GraphHydrationOrchestrator.STAGE_HEALTH);
        // 2 stages effectively ran (pruner + health sentinel)
        assertEquals(2, result.stagesRun());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 6. HydrationConfig.defaults() selects all stages
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    void hydrationConfigDefaults_allStagesEnabled() {
        HydrationConfig cfg = HydrationConfig.defaults();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_DERIVATION)).isTrue();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_PRUNE_COMPACT)).isTrue();
        assertThat(cfg.stageEnabled(GraphHydrationOrchestrator.STAGE_HEALTH)).isTrue();
        assertThat(cfg.dryRun()).isFalse();
    }

    @Test
    void hydrationConfigDryRun_passedThroughToPruner() {
        when(reasoningOrchestrator.runFullReground(8L))
                .thenReturn(new RegroundResult(0, "run-dry", Set.of()));
        when(pruneCompactOrchestrator.run(anyLong(), anySet(), anyString(), anyBoolean(), any()))
                .thenReturn(new PruneCompactResult(0, 0, 0, 0, 0, null, true));

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
    void stepPlan_legacyNoGraphConfig_enrichmentCascadedToSkip() {
        // In legacy mode without graphExtraction config, GRAPH_EXTRACTION is SKIP
        // → ENTITY_RESOLUTION is SKIP → EDGE_COMPUTATION is SKIP → ENRICHMENT is SKIP
        CrawlStepPlan plan = CrawlStepPlan.from(UnifiedCrawlRequest.builder().build());

        assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("GRAPH_EXTRACTION"));
        assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENTITY_RESOLUTION"));
        assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("EDGE_COMPUTATION"));
        assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENRICHMENT"));
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
}
