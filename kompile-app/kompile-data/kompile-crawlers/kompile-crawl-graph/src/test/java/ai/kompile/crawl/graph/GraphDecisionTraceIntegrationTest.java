package ai.kompile.crawl.graph;

import ai.kompile.core.evaluation.graph.GraphDecisionTraceSink;
import ai.kompile.core.evaluation.graph.GraphMissReason;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.passes.PassContext;
import ai.kompile.crawl.graph.passes.GraphEntityCandidateProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDecisionTraceIntegrationTest {

    @Test
    void candidateAdmissionAndSoftThresholdRejectionEmitProductionEvents() {
        Graph graph = graph(entity("candidate", "alxzz", "ORGANIZATION"));
        GraphDecisionTraceSink.Collector trace = GraphDecisionTraceSink.collector();
        GraphEntityCandidateProvider provider = new GraphEntityCandidateProvider(graph, 0.95, trace);

        provider.candidatesFor("alpha", "ORGANIZATION",
                PassContext.forChunk("chunk", "doc", "alpha"), 8);

        assertTrue(trace.events().stream().anyMatch(event ->
                event.reason() == GraphMissReason.CANDIDATE_SCORE_BELOW_THRESHOLD
                        && "rejected_score".equals(event.disposition())));
        assertEquals("0.95", trace.events().get(0).metadata().get("threshold"));
    }

    @Test
    void mustNotMergePurityDecisionIsTracedAndStillEnforced() {
        Entity anchor = entity("sarah-1", "Sarah Chen", "PERSON");
        anchor.setMetadata(Map.of("email", "sarah.one@example.com"));
        Entity forbidden = entity("sarah-2", "Sarah Chen", "PERSON");
        Graph graph = graph(anchor, forbidden);
        Relationship distinct = new Relationship();
        distinct.setSource("sarah-1");
        distinct.setTarget("sarah-2");
        distinct.setType("MUST_NOT_MERGE");
        graph.getRelationships().add(distinct);
        GraphDecisionTraceSink.Collector trace = GraphDecisionTraceSink.collector();

        var candidates = new GraphEntityCandidateProvider(graph, 0.35, trace).candidatesFor(
                "Sarah Chen <sarah.one@example.com>", "PERSON",
                PassContext.forChunk("chunk", "doc", "Sarah Chen"), 8);

        assertEquals(List.of("sarah-1"), candidates.stream().map(c -> c.id()).toList());
        assertTrue(trace.events().stream().anyMatch(event ->
                "rejected_forbidden_merge".equals(event.disposition())
                        && "true".equals(event.metadata().get("purityConstraint"))));
    }

    private static Entity entity(String id, String title, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setType(type);
        entity.setAliases(new ArrayList<>());
        return entity;
    }

    private static Graph graph(Entity... entities) {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(List.of(entities)));
        graph.setRelationships(new ArrayList<>());
        return graph;
    }
}
