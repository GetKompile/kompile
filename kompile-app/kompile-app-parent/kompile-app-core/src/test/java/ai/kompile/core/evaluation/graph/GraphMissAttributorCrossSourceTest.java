package ai.kompile.core.evaluation.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphMissAttributorCrossSourceTest {

    @Test
    void choosesDeepestViableSupportingSourceWithoutDroppingOtherSourceEvidence() {
        GraphDecisionTraceEvent earlySourceFailure = event(
                "early", "source-a", "doc-a", GraphMissStage.PROPOSITION,
                GraphMissReason.PROPOSITION_NOT_PRODUCED);
        GraphDecisionTraceEvent deeperSourceFailure = event(
                "deep", "source-b", "doc-b", GraphMissStage.VALIDATOR,
                GraphMissReason.VALIDATOR_REJECTED);

        GraphMissAttribution result = new GraphMissAttributor()
                .attribute(List.of("owns(a,b)"), List.of(earlySourceFailure, deeperSourceFailure))
                .get(0);

        assertEquals(GraphMissStage.VALIDATOR, result.stage());
        assertEquals(GraphMissReason.VALIDATOR_REJECTED, result.reason());
        assertEquals(List.of(earlySourceFailure, deeperSourceFailure), result.causalTrail(),
                "the causal trail retains independently attributable evidence from both sources");
    }

    @Test
    void usesEarliestFailureWithinTheSelectedDeepestSourcePath() {
        GraphDecisionTraceEvent selectedPathFirstFailure = event(
                "validator", "source-b", "doc-b", GraphMissStage.VALIDATOR,
                GraphMissReason.VALIDATOR_REJECTED);
        GraphDecisionTraceEvent selectedPathLaterFailure = event(
                "persistence", "source-b", "doc-b", GraphMissStage.MERGE_PERSISTENCE,
                GraphMissReason.MERGE_PERSISTENCE_FAILED);
        GraphDecisionTraceEvent otherPath = event(
                "mention", "source-a", "doc-a", GraphMissStage.MENTION_IDENTITY,
                GraphMissReason.MENTION_IDENTITY_UNRESOLVED);

        GraphMissAttribution result = new GraphMissAttributor().attribute(
                List.of("owns(a,b)"),
                List.of(otherPath, selectedPathLaterFailure, selectedPathFirstFailure)).get(0);

        assertEquals(GraphMissStage.VALIDATOR, result.stage());
        assertEquals(GraphMissReason.VALIDATOR_REJECTED, result.reason());
    }

    private static GraphDecisionTraceEvent event(String id, String source, String document,
                                                  GraphMissStage stage, GraphMissReason reason) {
        return new GraphDecisionTraceEvent(id, source, document, "chunk", "shard",
                "owns(a,b)", stage, reason, "rejected", List.of(), Map.of(), Map.of());
    }
}
