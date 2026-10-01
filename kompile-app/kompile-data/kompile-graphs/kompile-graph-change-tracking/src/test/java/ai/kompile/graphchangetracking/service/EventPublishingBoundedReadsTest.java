package ai.kompile.graphchangetracking.service;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Direction;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.IncidentEdges;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Neighborhood;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The bridge and the graph subprocess see the decorator's capabilities, not the store's. Before the
 * decorator delegated bounded reads, a bounded export behind it carried no relations.
 */
class EventPublishingBoundedReadsTest {

    private final KnowledgeGraphService store = mock(KnowledgeGraphService.class,
            withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
    private final BoundedKnowledgeGraphReader reads = (BoundedKnowledgeGraphReader) store;

    @Test
    void theStoreAnswersTheWholeNeighborhoodInOneCall() {
        GraphNode a = node("a");
        GraphNode b = node("b");
        Neighborhood stored = new Neighborhood(List.of(a, b), List.of(edge(a, b)), false);
        when(reads.getNeighborhood(7L, List.of("a"), List.of("a"), 2, 10, Direction.BOTH, 10)).thenReturn(stored);

        Neighborhood hood = decorate(store)
                .getNeighborhood(7L, List.of("a"), List.of("a"), 2, 10, Direction.BOTH, 10);

        assertSame(stored, hood);
        verify(reads, never()).getNodeInScope(anyString(), any());
        verify(reads, never()).getIncidentEdges(anyString(), any(), any(), anyInt());
    }

    @Test
    void theBridgeKeepsRelationsBehindTheDecorator() {
        GraphNode a = node("a");
        GraphNode b = node("b");
        when(reads.getNeighborhood(any(), any(), any(), anyInt(), anyInt(), any(), anyInt()))
                .thenReturn(new Neighborhood(List.of(a, b), List.of(edge(a, b)), false));

        UnifiedGraph bounded = new UnifiedGraphBridge(decorate(store)).exportNeighborhood(7L, List.of("a"), 1, 10);

        assertEquals(2, bounded.entityCount());
        assertEquals(1, bounded.relationCount());
        assertEquals(false, bounded.meta().get("truncated"));
    }

    @Test
    void aStoreWithoutBoundedReadsGetsScopedPointReadsAndTruncatedIncidentBatches() {
        KnowledgeGraphService plain = mock(KnowledgeGraphService.class);
        when(plain.getNode("a")).thenReturn(Optional.of(node("a")));
        EventPublishingKnowledgeGraphService decorator = decorate(plain);

        assertEquals("a", decorator.getNodeInScope("a", 7L).orElseThrow().getNodeId());
        assertTrue(decorator.getNodeInScope("a", 8L).isEmpty());
        IncidentEdges incident = decorator.getIncidentEdges("a", 7L, Direction.BOTH, 10);
        assertTrue(incident.edges().isEmpty());
        assertTrue(incident.truncated(), "an empty compatibility read is not proof that no edge exists");
    }

    private static EventPublishingKnowledgeGraphService decorate(KnowledgeGraphService delegate) {
        return new EventPublishingKnowledgeGraphService(
                delegate, event -> { }, new ObjectMapper(), new MutationContextHolder());
    }

    private static GraphNode node(String id) {
        return GraphNode.builder().nodeId(id).externalId(id).nodeType(NodeLevel.ENTITY)
                .title(id.toUpperCase()).factSheetId(7L).build();
    }

    private static GraphEdge edge(GraphNode source, GraphNode target) {
        return GraphEdge.builder().edgeId(source.getNodeId() + "::" + target.getNodeId())
                .sourceNodeId(source.getNodeId()).targetNodeId(target.getNodeId())
                .edgeType(EdgeType.USER_DEFINED).relationType("CALLS").weight(1.0).factSheetId(7L).build();
    }
}
