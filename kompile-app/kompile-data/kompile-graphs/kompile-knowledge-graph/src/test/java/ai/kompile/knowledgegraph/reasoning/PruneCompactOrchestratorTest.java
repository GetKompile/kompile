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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.maintenance.HealthSetpoints;
import ai.kompile.knowledgegraph.maintenance.PruneCompactBudget;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-Java (no Spring context) unit tests for {@link InferredFactGraphPruner}
 * and {@link PruneCompactBudget}.
 */
@ExtendWith(MockitoExtension.class)
class PruneCompactOrchestratorTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private InferredFactGraphPruner pruner;

    @BeforeEach
    void setUp() {
        pruner = new InferredFactGraphPruner(knowledgeGraphService);
    }

    // ── Helper to build a GraphEdge stub ─────────────────────────────────────────

    private static GraphEdge inferredEdge(String edgeId, String description, double confidence) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .provenanceType(EdgeProvenance.INFERRED)
                .description(description)
                .confidence(confidence)
                .stale(false)
                .weight(1.0)
                .build();
    }

    private static GraphEdge extractedEdge(String edgeId, String description, double confidence) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .provenanceType(EdgeProvenance.EXTRACTED)
                .description(description)
                .confidence(confidence)
                .stale(false)
                .weight(1.0)
                .build();
    }

    private static GraphHealthSnapshot healthSnapshot(double orphanRate, double density,
                                                       int lowConfNodes, int nodeCount,
                                                       int componentCount) {
        return new GraphHealthSnapshot(
                1L, Instant.now(), nodeCount, 100,
                Map.of(), density, 2.0, 5,
                (int) (orphanRate * nodeCount), orphanRate,
                lowConfNodes, 0, componentCount, 0.8,
                false, null
        );
    }

    // ── Test 1: pruner removes retracted INFERRED edge ───────────────────────────

    @Test
    void pruner_removesRetractedInferredEdge() {
        String atomKey = "derived_owns(Alice,Bike)";
        GraphEdge inferred = inferredEdge("edge-1", "atom=" + atomKey, 0.9);
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of(inferred));
        when(knowledgeGraphService.pruneEdges(List.of("edge-1"), true, false))
                .thenReturn(GraphPruneResult.ofSoftDelete(List.of("edge-1"), false));

        InferredFactGraphPruner.PruneResult result = pruner.pruneRetracted(
                1L, "run-1", Set.of(atomKey), false);

        assertThat(result.edgesDeleted()).isEqualTo(1);
        verify(knowledgeGraphService).pruneEdges(List.of("edge-1"), true, false);
    }

    // ── Test 2: provenance gate — EXTRACTED edge is never pruned ─────────────────

    @Test
    void pruner_neverTouchesExtractedEdge() {
        String atomKey = "derived_owns(Alice,Bike)";
        GraphEdge extracted = extractedEdge("edge-extracted", "atom=" + atomKey, 0.95);
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of(extracted));

        InferredFactGraphPruner.PruneResult result = pruner.pruneRetracted(
                1L, "run-1", Set.of(atomKey), false);

        assertThat(result.edgesDeleted()).isEqualTo(0);
        // pruneEdges must never be called
        verify(knowledgeGraphService, never()).pruneEdges(anyList(), anyBoolean(), anyBoolean());
    }

    // ── Test 3: pruner removes low-confidence INFERRED edge ──────────────────────

    @Test
    void pruner_prunesLowConfidenceInferredEdge() {
        GraphEdge lowConf = inferredEdge("edge-low", "some description", 0.3);
        GraphEdge highConf = inferredEdge("edge-high", "some other description", 0.8);
        when(knowledgeGraphService.getEdgesInFactSheet(1L))
                .thenReturn(List.of(lowConf, highConf));
        when(knowledgeGraphService.pruneEdges(List.of("edge-low"), true, false))
                .thenReturn(GraphPruneResult.ofSoftDelete(List.of("edge-low"), false));

        InferredFactGraphPruner.PruneResult result = pruner.pruneByConfidence(1L, 0.45, false);

        assertThat(result.edgesDeleted()).isEqualTo(1);
        // Only the low-confidence edge should have been submitted for pruning
        verify(knowledgeGraphService).pruneEdges(List.of("edge-low"), true, false);
    }

    // ── Test 4: budget sets runOrphanGc=true when orphanRate exceeds orphanHi ────

    @Test
    void healthBudget_aggressiveModeWhenOrphanRateHigh() {
        // orphanRate = 0.25 > orphanHi (0.20) → aggressive mode
        GraphHealthSnapshot health = healthSnapshot(0.25, 0.01, 0, 100, 5);
        HealthSetpoints sp = HealthSetpoints.defaults();

        PruneCompactBudget budget = PruneCompactBudget.from(health, sp, false);

        assertThat(budget.runOrphanGc()).isTrue();
        assertThat(budget.aggressivePruneMode()).isTrue();
        assertThat(budget.runCompaction()).isTrue();
    }

    // ── Test 5: hysteresis — aggressive mode is sticky until orphanRate < orphanLo ─

    @Test
    void healthBudget_noOscillation_stickyHysteresis() {
        HealthSetpoints sp = HealthSetpoints.defaults();

        // Step 1: enter aggressive mode — orphanRate (0.25) > orphanHi (0.20)
        GraphHealthSnapshot bloated = healthSnapshot(0.25, 0.01, 0, 100, 5);
        PruneCompactBudget budgetEnter = PruneCompactBudget.from(bloated, sp, false);
        assertThat(budgetEnter.aggressivePruneMode()).isTrue();

        // Step 2: mid-recovery — orphanRate (0.12) is between orphanLo (0.05) and orphanHi (0.20)
        // Should STILL be in aggressive mode (hysteresis: exit only below orphanLo)
        GraphHealthSnapshot midRecovery = healthSnapshot(0.12, 0.01, 0, 100, 5);
        PruneCompactBudget budgetMid = PruneCompactBudget.from(midRecovery, sp,
                budgetEnter.aggressivePruneMode());
        assertThat(budgetMid.aggressivePruneMode())
                .as("should stay aggressive until orphanRate < orphanLo (0.05)")
                .isTrue();

        // Step 3: full recovery — orphanRate (0.02) < orphanLo (0.05)
        GraphHealthSnapshot recovered = healthSnapshot(0.02, 0.005, 0, 100, 5);
        PruneCompactBudget budgetExit = PruneCompactBudget.from(recovered, sp,
                budgetMid.aggressivePruneMode());
        assertThat(budgetExit.aggressivePruneMode())
                .as("should exit aggressive mode once orphanRate drops below orphanLo")
                .isFalse();
    }
}
