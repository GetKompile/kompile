package ai.kompile.core.evaluation.graph;

import java.util.List;

/**
 * Attribution of one missing gold atom to its earliest evidenced failure.
 *
 * @param atom missing gold atom
 * @param stage earliest causal stage, or {@code null} when unattributed
 * @param reason primary miss reason
 * @param causalTrail all evidenced miss events in causal stage order
 */
public record GraphMissAttribution(
        String atom,
        GraphMissStage stage,
        GraphMissReason reason,
        List<GraphDecisionTraceEvent> causalTrail) {

    public GraphMissAttribution {
        reason = reason == null ? GraphMissReason.UNATTRIBUTED : reason;
        causalTrail = causalTrail == null ? List.of() : List.copyOf(causalTrail);
    }
}
