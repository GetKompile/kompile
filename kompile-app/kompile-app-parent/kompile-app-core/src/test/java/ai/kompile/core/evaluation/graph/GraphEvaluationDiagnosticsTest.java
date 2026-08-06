package ai.kompile.core.evaluation.graph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GraphEvaluationDiagnosticsTest {

    @Test
    void noOpAcceptsEvents() {
        assertDoesNotThrow(() -> GraphDecisionTraceSink.noop().accept(event(
                "e1", "gold(a,b)", GraphMissStage.PROPOSITION,
                GraphMissReason.PROPOSITION_NOT_PRODUCED)));
    }

    @Test
    void collectorIsAnOrderedImmutableSnapshot() {
        GraphDecisionTraceSink.Collector collector = GraphDecisionTraceSink.collector();
        GraphDecisionTraceEvent first = event("e1", "gold(a,b)", GraphMissStage.PROPOSITION,
                GraphMissReason.PROPOSITION_NOT_PRODUCED);
        GraphDecisionTraceEvent second = event("e2", "gold(a,b)", GraphMissStage.VALIDATOR,
                GraphMissReason.VALIDATOR_REJECTED);

        collector.accept(first);
        List<GraphDecisionTraceEvent> snapshot = collector.snapshot();
        collector.trace(second);

        assertEquals(List.of(first), snapshot);
        assertEquals(List.of(first, second), collector.events());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(second));
    }

    @Test
    void eventAndAttributionDefensivelyCopyCollections() {
        List<String> ids = new ArrayList<>(List.of("candidate-1"));
        Map<String, Double> scores = new LinkedHashMap<>(Map.of("candidate-1", 0.7));
        Map<String, String> metadata = new LinkedHashMap<>(Map.of("model", "rules"));
        GraphDecisionTraceEvent event = new GraphDecisionTraceEvent(
                "e1", "source", "doc", "chunk", "shard", "gold(a,b)",
                GraphMissStage.PROPOSITION, GraphMissReason.PROPOSITION_NOT_PRODUCED,
                "missed", ids, scores, metadata);

        ids.add("candidate-2");
        scores.put("candidate-2", 0.9);
        metadata.put("late", "mutation");
        GraphMissAttribution attribution = new GraphMissAttribution(
                event.atom(), event.stage(), event.reason(), new ArrayList<>(List.of(event)));

        assertEquals(List.of("candidate-1"), event.candidateIds());
        assertEquals(Map.of("candidate-1", 0.7), event.candidateScores());
        assertEquals(Map.of("model", "rules"), event.metadata());
        assertThrows(UnsupportedOperationException.class,
                () -> attribution.causalTrail().add(event));
    }

    @Test
    void earliestStageIsPrimaryAndFullTrailIsRetained() {
        GraphDecisionTraceEvent validator = event("e-validator", "gold(a,b)",
                GraphMissStage.VALIDATOR, GraphMissReason.VALIDATOR_REJECTED);
        GraphDecisionTraceEvent proposition = event("e-proposition", "gold(a,b)",
                GraphMissStage.PROPOSITION, GraphMissReason.PROPOSITION_NOT_PRODUCED);
        GraphDecisionTraceEvent relation = event("e-relation", "gold(a,b)",
                GraphMissStage.RELATION_SELECTION, GraphMissReason.RELATION_NOT_SELECTED);

        GraphMissAttribution attribution = new GraphMissAttributor()
                .attribute(List.of("gold(a,b)"), List.of(validator, proposition, relation)).get(0);

        assertEquals(GraphMissStage.PROPOSITION, attribution.stage());
        assertEquals(GraphMissReason.PROPOSITION_NOT_PRODUCED, attribution.reason());
        assertEquals(List.of(proposition, relation, validator), attribution.causalTrail());
    }

    @Test
    void attributionOrderingIsStableForAtomsAndSameStageEvents() {
        GraphDecisionTraceEvent b2 = event("b2", "gold(b,c)", GraphMissStage.VALIDATOR,
                GraphMissReason.VALIDATOR_REJECTED);
        GraphDecisionTraceEvent b1 = event("b1", "gold(b,c)", GraphMissStage.VALIDATOR,
                GraphMissReason.VALIDATOR_REJECTED);
        GraphDecisionTraceEvent a = event("a1", "gold(a,b)", GraphMissStage.EPISTEMIC,
                GraphMissReason.EPISTEMIC_REJECTED);

        List<GraphMissAttribution> result = new GraphMissAttributor().attribute(
                List.of("gold(b,c)", "gold(a,b)"), List.of(b2, a, b1));

        assertEquals(List.of("gold(b,c)", "gold(a,b)"),
                result.stream().map(GraphMissAttribution::atom).toList());
        assertEquals(List.of(b2, b1), result.get(0).causalTrail());
    }

    @Test
    void missingEvidenceIsUnattributed() {
        GraphMissAttribution attribution = new GraphMissAttributor()
                .attribute(List.of("gold(a,b)"), List.of()).get(0);

        assertNull(attribution.stage());
        assertEquals(GraphMissReason.UNATTRIBUTED, attribution.reason());
        assertEquals(List.of(), attribution.causalTrail());
    }

    @Test
    void calibrationAndProcessFacetsBelongToStableCausalStages() {
        assertEquals(GraphMissStage.GRAPH_ADMISSION,
                GraphMissReason.CANDIDATE_SCORE_BELOW_THRESHOLD.stage());
        assertEquals(GraphMissStage.GRAPH_ADMISSION,
                GraphMissReason.IDENTITY_SIMILARITY_BELOW_THRESHOLD.stage());
        assertEquals(GraphMissStage.PROCESS_SYNTHESIS,
                GraphMissReason.PROCESS_TRACE_COUNT_INSUFFICIENT.stage());
        assertEquals(GraphMissStage.SOURCE_SHARD,
                GraphMissReason.SOURCE_NOT_SCHEDULED.stage());
    }

    private static GraphDecisionTraceEvent event(
            String id, String atom, GraphMissStage stage, GraphMissReason reason) {
        return new GraphDecisionTraceEvent(
                id, "source", "document", "chunk", "shard", atom, stage, reason,
                "rejected", List.of("candidate"), Map.of("candidate", 0.5), Map.of());
    }
}
