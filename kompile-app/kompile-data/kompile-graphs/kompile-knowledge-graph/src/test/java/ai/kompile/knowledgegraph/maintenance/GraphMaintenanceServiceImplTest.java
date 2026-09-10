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

import ai.kompile.core.graphrag.conformance.GraphConformanceChecker;
import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceSchedule;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.ReResolutionConfig;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.resolution.GraphCompactionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the B1 maintenance-task handlers in {@link GraphMaintenanceServiceImpl}:
 * STATS_REFRESH (graph-health snapshot) and ENTITY_RE_RESOLUTION (compaction). Both route through
 * the store-agnostic {@link KnowledgeGraphService}/{@link GraphCompactionService} so they work on
 * the active matrix/vector backend, not just JPA.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphMaintenanceServiceImplTest {

    @Mock private TtlSweepExecutor ttlSweepExecutor;
    @Mock private OrphanPruner orphanPruner;
    @Mock private ConfidencePruner confidencePruner;
    @Mock private ComponentPruner componentPruner;
    @Mock private ContradictionDetector contradictionDetector;
    @Mock private ProvenanceValidator provenanceValidator;
    @Mock private SnapshotManager snapshotManager;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private GraphCompactionService graphCompactionService;
    @Mock private ObjectProvider<GraphConformanceChecker> conformanceCheckerProvider;

    private GraphMaintenanceServiceImpl service;

    private static final Long FS = 1L;

    @BeforeEach
    void setUp() {
        service = new GraphMaintenanceServiceImpl(ttlSweepExecutor, orphanPruner, confidencePruner,
                componentPruner, contradictionDetector, provenanceValidator, snapshotManager,
                knowledgeGraphService, graphCompactionService, conformanceCheckerProvider);
    }

    @Test
    void statsRefreshReportsGraphHealthFromTheStoreAgnosticService() {
        when(knowledgeGraphService.countActiveNodes(FS)).thenReturn(100L);
        when(knowledgeGraphService.findOrphanNodeIds(eq(FS), anySet())).thenReturn(List.of("o1", "o2"));
        when(knowledgeGraphService.findLowConfidenceNodeIds(eq(FS), anyDouble())).thenReturn(List.of("n1"));
        when(knowledgeGraphService.findLowConfidenceEdgeIds(eq(FS), anyDouble()))
                .thenReturn(List.of("e1", "e2", "e3"));

        MaintenanceSchedule schedule = new MaintenanceSchedule(
                FS, Duration.ofDays(1), List.of(MaintenanceTask.STATS_REFRESH), false, true);
        TaskReport stats = service.runFullMaintenance(FS, schedule)
                .taskReports().get(MaintenanceTask.STATS_REFRESH);

        assertNotNull(stats);
        assertEquals(100, stats.itemsScanned(), "scanned = active node count");
        assertEquals(2 + 1 + 3, stats.itemsAffected(), "issues = orphans + low-conf nodes + low-conf edges");
        assertFalse(stats.warnings().stream().anyMatch(w -> w.contains("Not yet implemented")),
                "STATS_REFRESH is now implemented");
    }

    @Test
    void entityReResolutionMergesAndReportsDeltasWhenRequested() {
        GraphCompactionService.CompactionResult result = new GraphCompactionService.CompactionResult(
                50, 40, 10, 7, 3, List.of(), 5L);
        when(graphCompactionService.compact(eq(FS), any(GraphCompactionService.CompactionConfig.class)))
                .thenReturn(result);

        // mergeOnMatch=true and not a dry run → actually compacts
        TaskReport tr = service.reResolveEntities(FS, new ReResolutionConfig(0.9, 10000, true, null), false)
                .taskReports().get(MaintenanceTask.ENTITY_RE_RESOLUTION);

        assertEquals(50, tr.itemsScanned(), "scanned = originalEntityCount");
        assertEquals(10, tr.itemsAffected(), "affected = entitiesMerged");
        verify(graphCompactionService).compact(eq(FS), any());
        verify(graphCompactionService, never()).previewCandidates(anyLong(), any());
    }

    @Test
    void entityReResolutionPreviewsWhenMergeOnMatchIsFalse() {
        GraphCompactionService.MatchCandidate candidate = new GraphCompactionService.MatchCandidate(
                "a", "b", "Acme", "Acme Inc", "ENTITY", 0.93, List.of("name"));
        when(graphCompactionService.previewCandidates(eq(FS), any())).thenReturn(List.of(candidate));

        // defaults() has mergeOnMatch=false → preview only, never mutates, even though dryRun=false
        TaskReport tr = service.reResolveEntities(FS, ReResolutionConfig.defaults(), false)
                .taskReports().get(MaintenanceTask.ENTITY_RE_RESOLUTION);

        assertEquals(1, tr.itemsScanned(), "scanned = candidate count");
        assertEquals(0, tr.itemsAffected(), "preview must not report merges");
        verify(graphCompactionService).previewCandidates(eq(FS), any());
        verify(graphCompactionService, never()).compact(anyLong(), any());
    }

    @Test
    void dryRunNeverMergesEvenWhenMergeOnMatchIsTrue() {
        when(graphCompactionService.previewCandidates(eq(FS), any())).thenReturn(List.of());

        service.reResolveEntities(FS, new ReResolutionConfig(0.9, 10000, true, null), true);

        verify(graphCompactionService, never()).compact(anyLong(), any());
        verify(graphCompactionService).previewCandidates(eq(FS), any());
    }

    @Test
    void ontologyConformanceDelegatesToCheckerWhenWired() {
        GraphConformanceChecker checker = mock(GraphConformanceChecker.class);
        GraphConformanceSummary summary = new GraphConformanceSummary(FS, true, "Planning", 10, 1, 2, 0.8, "ok");
        when(conformanceCheckerProvider.getIfAvailable()).thenReturn(checker);
        when(checker.checkFactSheet(FS)).thenReturn(summary);

        assertSame(summary, service.checkOntologyConformance(FS));
        verify(checker).checkFactSheet(FS);
    }

    @Test
    void ontologyConformanceReturnsNotBoundWhenNoCheckerWired() {
        when(conformanceCheckerProvider.getIfAvailable()).thenReturn(null);
        assertFalse(service.checkOntologyConformance(FS).ontologyBound());
    }
}
