package ai.kompile.graphchangetracking.service;

import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The graph page's level-of-detail reads (top-K overview, one-hop expand) pass through the decorator
 * unchanged. Without the delegation they fell to the interface defaults, which answer empty.
 */
class EventPublishingLodReadsTest {

    private final KnowledgeGraphService store = mock(KnowledgeGraphService.class);
    private final EventPublishingKnowledgeGraphService decorator = new EventPublishingKnowledgeGraphService(
            store, event -> { }, new ObjectMapper(), new MutationContextHolder());

    @Test
    void theExpandKeepsItsFactSheetAndEdgeTypes() {
        Map<String, Object> stored = Map.of("nodes", List.of(Map.of("id", "a")));
        when(store.expandNeighborhoodVisualization("a", 25, List.of("MENTIONS"), 7L)).thenReturn(stored);

        assertSame(stored, decorator.expandNeighborhoodVisualization("a", 25, List.of("MENTIONS"), 7L));
    }

    @Test
    void anUnscopedExpandReachesTheStoreWithoutASheet() {
        Map<String, Object> stored = Map.of("nodes", List.of());
        when(store.expandNeighborhoodVisualization("a", 25, null, null)).thenReturn(stored);

        assertSame(stored, decorator.expandNeighborhoodVisualization("a", 25, null));
        verify(store).expandNeighborhoodVisualization("a", 25, null, null);
    }

    @Test
    void theTopKOverviewKeepsItsFactSheet() {
        Map<String, Object> stored = Map.of("nodes", List.of(Map.of("id", "hub")));
        when(store.getTopKVisualizationData(7L, 50, "degree")).thenReturn(stored);

        assertSame(stored, decorator.getTopKVisualizationData(7L, 50, "degree"));
    }
}
