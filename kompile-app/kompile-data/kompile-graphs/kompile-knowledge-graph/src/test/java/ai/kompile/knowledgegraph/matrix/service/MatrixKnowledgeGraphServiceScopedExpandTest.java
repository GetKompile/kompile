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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The one-hop expand behind the graph page's deep link: it reads the linked fact sheet's graph,
 * follows edges in both directions, and keeps the heaviest weight per neighbour. Each fact sheet
 * is its own {@code factsheet_<id>} graph here, as in the store.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixKnowledgeGraphServiceScopedExpandTest {

    @Mock
    private MatrixGraphStore graphStore;

    private final Map<String, AdjacencyMatrixGraph> graphs = new LinkedHashMap<>();
    private MatrixKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new MatrixKnowledgeGraphService(graphStore, new ObjectMapper());
        when(graphStore.getLoadedGraphIds()).thenAnswer(inv -> new LinkedHashSet<>(graphs.keySet()));
        when(graphStore.listGraphs()).thenReturn(List.of());
        when(graphStore.loadGraph(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(graphs.get(inv.getArgument(0, String.class))));
        when(graphStore.getNode(anyString(), anyString())).thenAnswer(inv -> {
            AdjacencyMatrixGraph graph = graphs.get(inv.getArgument(0, String.class));
            return graph == null ? Optional.empty() : graph.getNode(inv.getArgument(1, String.class));
        });
    }

    @Test
    void theFactSheetPicksWhichGraphASharedNodeIsReadFrom() {
        AdjacencyMatrixGraph one = sheet(1);
        node(one, 1, "shared", "a1");
        one.addEdge("shared", "a1", 0.5, "REL", false);
        AdjacencyMatrixGraph two = sheet(2);
        node(two, 2, "shared", "b2");
        two.addEdge("shared", "b2", 0.5, "REL", false);

        assertEquals(List.of("shared", "b2"), nodeIds(service.expandNeighborhoodVisualization("shared", 10, null, 2L)));
        assertEquals(List.of("shared", "a1"), nodeIds(service.expandNeighborhoodVisualization("shared", 10, null, 1L)));
        // Without a sheet the first graph holding the node wins, which is why the link carries one.
        assertEquals(List.of("shared", "a1"), nodeIds(service.expandNeighborhoodVisualization("shared", 10, null, null)));
        assertEquals(List.of("shared", "a1"), nodeIds(service.expandNeighborhoodVisualization("shared", 10, null)));
    }

    @Test
    void withoutASheetTheGraphHoldingTheNodeIsRead() {
        node(sheet(1), 1, "other");
        AdjacencyMatrixGraph two = sheet(2);
        node(two, 2, "only-in-two", "b2");
        two.addEdge("only-in-two", "b2", 0.5, "REL", false);

        assertEquals(List.of("only-in-two", "b2"),
                nodeIds(service.expandNeighborhoodVisualization("only-in-two", 10, null, null)));
    }

    @Test
    void aNodeThatIsOnlyAnEdgeTargetListsItsSources() {
        AdjacencyMatrixGraph one = sheet(1);
        node(one, 1, "target", "src1", "src2");
        one.addEdge("src1", "target", 0.4, "MENTIONS", false);
        one.addEdge("src2", "target", 0.7, "MENTIONS", false);

        Map<String, Object> result = service.expandNeighborhoodVisualization("target", 10, null, 1L);

        assertEquals(List.of("target", "src2", "src1"), nodeIds(result));
        List<Map<String, Object>> edges = edges(result);
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(e -> "src1".equals(e.get("source")) && "target".equals(e.get("target"))
                && "src1::target::MENTIONS".equals(e.get("id"))), String.valueOf(edges));
        assertTrue(edges.stream().anyMatch(e -> "src2".equals(e.get("source")) && "target".equals(e.get("target"))),
                String.valueOf(edges));
        assertEquals(2, statistics(result).get("totalNeighbors"));
    }

    @Test
    void aNeighbourKeepsItsHeaviestEdgeAndTheCapCountsNeitherTheSeedNorDuplicates() {
        AdjacencyMatrixGraph one = sheet(1);
        node(one, 1, "seed", "x", "y", "z");
        one.addEdge("seed", "seed", 1.0, "A", false);
        one.addEdge("seed", "x", 0.2, "A", false);
        one.addEdge("x", "seed", 0.9, "B", false);
        one.addEdge("seed", "y", 0.5, "A", false);
        one.addEdge("z", "seed", 0.1, "A", false);

        Map<String, Object> result = service.expandNeighborhoodVisualization("seed", 2, null, 1L);

        // x is reached twice and ranks by its 0.9 edge; the self-loop takes no slot; z falls past the cap.
        assertEquals(List.of("seed", "x", "y"), nodeIds(result));
        assertEquals(3, statistics(result).get("totalNeighbors"));
        assertEquals(2, statistics(result).get("returnedNeighbors"));
    }

    @Test
    void anEdgeTypeFilterAppliesToBothDirections() {
        AdjacencyMatrixGraph one = sheet(1);
        node(one, 1, "seed", "out", "in");
        one.addEdge("seed", "out", 0.5, "A", false);
        one.addEdge("in", "seed", 0.6, "B", false);

        assertEquals(List.of("seed", "out"), nodeIds(service.expandNeighborhoodVisualization("seed", 10, List.of("A"), 1L)));
        assertEquals(List.of("seed", "in"), nodeIds(service.expandNeighborhoodVisualization("seed", 10, List.of("B"), 1L)));
        assertEquals(List.of("seed", "in", "out"), nodeIds(service.expandNeighborhoodVisualization("seed", 10, List.of(), 1L)));
    }

    @Test
    void anUnknownSheetOrNodeExpandsToNothing() {
        node(sheet(1), 1, "seed");

        Map<String, Object> unknownSheet = service.expandNeighborhoodVisualization("seed", 10, null, 99L);
        assertEquals(List.of(), nodeIds(unknownSheet));
        assertEquals(0, statistics(unknownSheet).get("totalAvailableNodes"));

        Map<String, Object> unknownNode = service.expandNeighborhoodVisualization("missing", 10, null, 1L);
        assertEquals(List.of(), nodeIds(unknownNode));
        assertEquals(List.of(), edges(unknownNode));
    }

    private AdjacencyMatrixGraph sheet(long factSheetId) {
        return graphs.computeIfAbsent("factsheet_" + factSheetId, id -> new AdjacencyMatrixGraph(id, 16));
    }

    private static void node(AdjacencyMatrixGraph graph, long factSheetId, String... ids) {
        for (String id : ids) {
            graph.addNode(MatrixGraphNode.builder().nodeId(id).nodeType("ENTITY").title(id)
                    .factSheetId(factSheetId).build());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> nodeIds(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("nodes")).stream()
                .map(n -> (String) n.get("id"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("edges");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> statistics(Map<String, Object> result) {
        return (Map<String, Object>) result.get("statistics");
    }
}
