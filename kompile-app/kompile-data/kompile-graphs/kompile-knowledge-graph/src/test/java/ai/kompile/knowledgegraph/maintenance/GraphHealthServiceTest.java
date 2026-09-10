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
import ai.kompile.core.graphrag.maintenance.model.GraphComparison;
import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphHealthService}: metric computation on a fixed graph (exact values),
 * the ontology-conformance feed, cross-graph entity-set comparison, and the persisted time series.
 */
class GraphHealthServiceTest {

    private final KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @SuppressWarnings("unchecked")
    private GraphHealthService service(GraphConformanceChecker checker) {
        ObjectProvider<GraphConformanceChecker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(checker);
        return new GraphHealthService(kg, provider, mapper);
    }

    private GraphNode entity(String id, String title, double confidence) {
        return GraphNode.builder().nodeId(id).nodeType(NodeLevel.ENTITY).title(title).confidence(confidence).build();
    }

    private GraphEdge edge(String id, GraphNode from, GraphNode to, double confidence) {
        return GraphEdge.builder().edgeId(id).sourceNode(from).targetNode(to).confidence(confidence).build();
    }

    @Test
    void computeSnapshot_computesMetricsOnAFixedGraph() {
        GraphNode a = entity("A", "Alpha", 0.9);
        GraphNode b = entity("B", "Beta", 0.3);   // low-confidence node
        GraphNode c = entity("C", "Gamma", 0.9);
        GraphNode d = entity("D", "Delta", 0.9);   // orphan (no edges)
        when(kg.getNodesInFactSheet(1L)).thenReturn(List.of(a, b, c, d));
        when(kg.getEdgesInFactSheet(1L)).thenReturn(List.of(
                edge("e1", a, b, 0.9),
                edge("e2", b, c, 0.2)));            // low-confidence edge

        GraphHealthSnapshot s = service(null).computeSnapshot(1L);

        assertEquals(4, s.nodeCount());
        assertEquals(2, s.edgeCount());
        assertEquals(Integer.valueOf(4), s.nodesByType().get("ENTITY"));
        assertEquals(0.3333, s.density(), 1e-9);          // 2*2 / (4*3)
        assertEquals(1.0, s.averageDegree(), 1e-9);       // 2*2 / 4
        assertEquals(2, s.maxDegree());                   // B touches both edges
        assertEquals(1, s.orphanCount());                 // D
        assertEquals(0.25, s.orphanRate(), 1e-9);
        assertEquals(1, s.lowConfidenceNodeCount());      // B
        assertEquals(1, s.lowConfidenceEdgeCount());      // e2
        assertEquals(2, s.connectedComponentCount());     // {A,B,C} and {D}
        assertEquals(0.75, s.largestComponentFraction(), 1e-9);
        assertFalse(s.ontologyBound());
        assertNull(s.conformanceScore());
    }

    @Test
    void computeSnapshot_feedsConformanceFromSpi() {
        when(kg.getNodesInFactSheet(1L)).thenReturn(List.of(entity("A", "Alpha", 0.9)));
        when(kg.getEdgesInFactSheet(1L)).thenReturn(List.of());
        GraphConformanceChecker checker = mock(GraphConformanceChecker.class);
        when(checker.checkFactSheet(1L)).thenReturn(
                new GraphConformanceSummary(1L, true, "Planning", 4, 0, 1, 0.75, "ok"));

        GraphHealthSnapshot s = service(checker).computeSnapshot(1L);

        assertTrue(s.ontologyBound());
        assertEquals(0.75, s.conformanceScore(), 1e-9);
    }

    @Test
    void compareGraphs_reportsEntitySetOverlap() {
        when(kg.getNodesInFactSheet(anyLong())).thenReturn(List.of());
        when(kg.getEdgesInFactSheet(anyLong())).thenReturn(List.of());
        when(kg.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY)).thenReturn(List.of(
                entity("a", "Alpha", 0.9), entity("b", "Beta", 0.9), entity("g", "Gamma", 0.9)));
        when(kg.getNodesByTypeInFactSheet(2L, NodeLevel.ENTITY)).thenReturn(List.of(
                entity("b2", "Beta", 0.9), entity("g2", "Gamma", 0.9), entity("d2", "Delta", 0.9)));

        GraphComparison cmp = service(null).compareGraphs(1L, 2L);

        assertEquals(2, cmp.sharedEntityCount());   // Beta, Gamma
        assertEquals(1, cmp.onlyInACount());        // Alpha
        assertEquals(1, cmp.onlyInBCount());        // Delta
        assertTrue(cmp.sampleOnlyInA().contains("Alpha"));
        assertTrue(cmp.sampleOnlyInB().contains("Delta"));
    }

    @Test
    void persistThenListHistory_roundTrips(@TempDir Path tempDir) throws Exception {
        when(kg.getNodesInFactSheet(1L)).thenReturn(List.of(entity("A", "Alpha", 0.9)));
        when(kg.getEdgesInFactSheet(1L)).thenReturn(List.of());
        GraphHealthService svc = service(null);
        Field f = GraphHealthService.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(svc, tempDir.toString());

        GraphHealthSnapshot persisted = svc.persistSnapshot(1L);
        List<GraphHealthSnapshot> history = svc.listHistory(1L);

        assertEquals(1, history.size());
        assertEquals(persisted.nodeCount(), history.get(0).nodeCount());
        assertEquals(1, history.get(0).nodeCount());
        // Written under the versioned project tree so the health series travels with a git clone.
        assertTrue(Files.isDirectory(tempDir.resolve("data/graph/health/1")));
    }
}
