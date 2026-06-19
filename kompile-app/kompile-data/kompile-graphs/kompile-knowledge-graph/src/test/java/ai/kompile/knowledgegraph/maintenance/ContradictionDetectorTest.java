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

import ai.kompile.core.graphrag.maintenance.model.Contradiction;
import ai.kompile.core.graphrag.maintenance.model.ContradictionResolutionStrategy;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContradictionDetector}.
 *
 * <p>These exercise the detector through the store-agnostic {@link KnowledgeGraphService} seam.
 * The detector previously read {@code GraphEdgeRepository} (JPA) directly and grouped on the
 * Long primary key, so it found nothing and wrote nothing on the active {@code @Primary}
 * matrix/vector backend (where edges carry a String {@code edgeId} and a null Long id).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContradictionDetectorTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private ContradictionDetector detector;

    private static final Long FS = 1L;

    @BeforeEach
    void setUp() {
        detector = new ContradictionDetector(knowledgeGraphService);
    }

    @Test
    void detectsConflictWhenSamePairHasDifferentEdgeTypes() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("e1", "A", "B", EdgeType.CITATION, null, null, null),
                edge("e2", "A", "B", EdgeType.TEMPORAL, null, null, null)));

        List<Contradiction> found = detector.detect(FS);

        assertEquals(1, found.size(), "two differently-typed edges on the same pair are a contradiction");
    }

    @Test
    void noConflictWhenSamePairSameType() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("e1", "A", "B", EdgeType.CITATION, null, null, null),
                edge("e2", "A", "B", EdgeType.CITATION, null, null, null)));

        assertTrue(detector.detect(FS).isEmpty());
    }

    @Test
    void ignoresStaleEdgesSoNoFalseConflict() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("e1", "A", "B", EdgeType.CITATION, null, null, null),
                edge("e2", "A", "B", EdgeType.TEMPORAL, null, null, /*stale*/ true)));

        assertTrue(detector.detect(FS).isEmpty(),
                "stale edges must be excluded before contradiction grouping");
    }

    @Test
    void newerWinsStalesTheOlderLoserThroughTheService() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("old", "A", "B", EdgeType.CITATION, LocalDateTime.of(2020, 1, 1, 0, 0), null, null),
                edge("new", "A", "B", EdgeType.TEMPORAL, LocalDateTime.of(2024, 1, 1, 0, 0), null, null)));

        TaskReport report = detector.resolve(FS, ContradictionResolutionStrategy.NEWER_WINS, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(knowledgeGraphService).pruneEdges(captor.capture(), eq(true), eq(false));
        assertTrue(captor.getValue().contains("old"), "older edge must be staled");
        assertFalse(captor.getValue().contains("new"), "newer edge (winner) must be kept");
        assertEquals(1, report.itemsAffected());
    }

    @Test
    void dryRunResolvesNothing() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("old", "A", "B", EdgeType.CITATION, LocalDateTime.of(2020, 1, 1, 0, 0), null, null),
                edge("new", "A", "B", EdgeType.TEMPORAL, LocalDateTime.of(2024, 1, 1, 0, 0), null, null)));

        detector.resolve(FS, ContradictionResolutionStrategy.NEWER_WINS, true);

        verify(knowledgeGraphService, never()).pruneEdges(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void reportingOnlyStrategyDoesNotWrite() {
        when(knowledgeGraphService.getEdgesInFactSheet(FS)).thenReturn(List.of(
                edge("e1", "A", "B", EdgeType.CITATION, null, null, null),
                edge("e2", "A", "B", EdgeType.TEMPORAL, null, null, null)));

        detector.resolve(FS, ContradictionResolutionStrategy.FLAG_FOR_REVIEW, false);

        verify(knowledgeGraphService, never()).pruneEdges(any(), anyBoolean(), anyBoolean());
    }

    private GraphEdge edge(String edgeId, String src, String tgt, EdgeType type,
                           LocalDateTime createdAt, Double confidence, Boolean stale) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNode(GraphNode.builder().nodeId(src).build())
                .targetNode(GraphNode.builder().nodeId(tgt).build())
                .edgeType(type)
                .createdAt(createdAt)
                .confidence(confidence)
                .stale(stale)
                .build();
    }
}
