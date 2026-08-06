package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import ai.kompile.graph.reasoning.pruning.PrunePolicy;
import ai.kompile.knowledgegraph.reasoning.InferredFactGraphPruner;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.resolution.IdentityGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PruneCompactOrchestratorDryRunTest {

    @Mock private InferredFactGraphPruner inferredPruner;
    @Mock private GraphCompactionService compactionService;
    @Mock private IdentityGraphService identityService;
    @Mock private ConfidencePruner confidencePruner;
    @Mock private OrphanPruner orphanPruner;
    @Mock private ComponentPruner componentPruner;
    @Mock private GraphHealthService graphHealthService;
    @Mock private OpinionPrunePass opinionPrunePass;

    private PruneCompactOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PruneCompactOrchestrator(inferredPruner, compactionService, identityService,
                confidencePruner, orphanPruner, componentPruner, graphHealthService, opinionPrunePass);
        when(graphHealthService.computeSnapshot(anyLong())).thenReturn(compactionBudgetHealth());
        when(inferredPruner.pruneRetracted(anyLong(), anyString(), anySet(), anyBoolean()))
                .thenAnswer(invocation -> new InferredFactGraphPruner.PruneResult(0, invocation.getArgument(3)));
        when(inferredPruner.pruneByConfidence(anyLong(), anyDouble(), anyBoolean()))
                .thenAnswer(invocation -> new InferredFactGraphPruner.PruneResult(0, invocation.getArgument(2)));
        when(opinionPrunePass.execute(anyLong(), any(PrunePolicy.class), anyBoolean()))
                .thenAnswer(invocation -> OpinionPrunePass.Result.empty(invocation.getArgument(2)));
    }

    @Test
    void dryRunSkipsCompactionAndReportsZeroMerges() {
        PruneCompactResult result = orchestrator.run(1L, Set.of(), "run-1", true,
                HealthSetpoints.defaults(), PrunePolicy.defaults());

        assertThat(result.mergesPerformedP2()).isZero();
        verify(compactionService, never()).compact(anyLong(), any());
    }

    @Test
    void nonDryRunStillInvokesCompaction() {
        when(compactionService.compact(anyLong(), any()))
                .thenReturn(new GraphCompactionService.CompactionResult(2, 1, 1, 0, 1, java.util.List.of(), 0));

        PruneCompactResult result = orchestrator.run(1L, Set.of(), "run-1", false,
                HealthSetpoints.defaults(), PrunePolicy.defaults());

        assertThat(result.mergesPerformedP2()).isEqualTo(1);
        verify(compactionService).compact(anyLong(), any());
    }

    private static GraphHealthSnapshot compactionBudgetHealth() {
        return new GraphHealthSnapshot(1L, Instant.now(), 100, 100, Map.of(), 0.01, 2.0, 5,
                25, 0.25, 0, 0, 5, 0.8, false, null);
    }
}
