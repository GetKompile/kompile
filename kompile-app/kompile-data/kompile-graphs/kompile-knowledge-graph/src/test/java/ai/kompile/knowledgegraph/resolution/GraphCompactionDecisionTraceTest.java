package ai.kompile.knowledgegraph.resolution;

import ai.kompile.core.evaluation.graph.GraphDecisionTraceSink;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphCompactionDecisionTraceTest {

    @Test
    void compactionEmitsAcceptedAndRejectedThresholdDecisions() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        GraphCompactionService service = new GraphCompactionService(graph);
        GraphDecisionTraceSink.Collector trace = GraphDecisionTraceSink.collector();
        service.setGraphDecisionTraceSink(trace);
        when(graph.searchNodes("", NodeLevel.ENTITY, 100_000)).thenReturn(List.of(
                node("n1", "Acme Corporation"),
                node("n2", "Acme Corp"),
                node("n3", "Acme Zzzzz")));

        service.compact(GraphCompactionService.CompactionConfig.previewOnly(0.99));

        assertTrue(trace.events().stream().anyMatch(event ->
                "merge_candidate".equals(event.disposition())
                        && "0.99".equals(event.metadata().get("stringThreshold"))));
        assertTrue(trace.events().stream().anyMatch(event ->
                event.reason() == GraphMissReason.IDENTITY_SIMILARITY_BELOW_THRESHOLD
                        && "rejected_similarity".equals(event.disposition())));
    }

    @Test
    void sharedBarcodeCollisionVetoesOtherwiseStrongAttributeMatch() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        GraphCompactionService service = new GraphCompactionService(graph);
        GraphDecisionTraceSink.Collector trace = GraphDecisionTraceSink.collector();
        service.setGraphDecisionTraceSink(trace);
        when(graph.searchNodes("", NodeLevel.ENTITY, 100_000)).thenReturn(List.of(
                node("n1", "vintage vinyl record", "{\"properties\":{\"upc\":\"036000291452\",\"email\":\"shared@example.com\"}}"),
                node("n2", "bluetooth speaker", "{\"properties\":{\"upc\":\"036000291452\",\"email\":\"shared@example.com\"}}")));

        List<GraphCompactionService.MatchCandidate> candidates =
                service.previewCandidates(GraphCompactionService.CompactionConfig.previewOnly(0.85));

        assertEquals(0, candidates.size());
        assertTrue(trace.events().stream().anyMatch(event ->
                event.reason() == GraphMissReason.MENTION_IDENTITY_UNRESOLVED
                        && "rejected_entity_purity".equals(event.disposition())
                        && "true".equals(event.metadata().get("purityRejected"))
                        && event.metadata().get("signals").contains("IDENTIFIER_COLLISION")));
    }

    private static GraphNode node(String id, String title) {
        return node(id, title, "{\"entity_type\":\"ORGANIZATION\"}");
    }

    private static GraphNode node(String id, String title, String metadataJson) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + id)
                .title(title)
                .metadataJson(metadataJson)
                .confidence(0.9)
                .edgeCount(0)
                .childCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
