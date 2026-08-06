package ai.kompile.core.evaluation.graph;

import java.util.List;
import java.util.Map;

/**
 * Immutable evidence that a graph extraction decision was made.
 *
 * @param eventId unique trace event id
 * @param sourceId source containing the evaluated evidence
 * @param documentId document within the source
 * @param chunkId chunk within the document
 * @param shardId source shard considered by the decision
 * @param atom gold atom whose path is being traced
 * @param stage decision stage
 * @param reason miss reason, when the decision contributes to a miss
 * @param disposition implementation-defined decision outcome
 * @param candidateIds candidate identifiers considered at this stage
 * @param candidateScores scores keyed by candidate identifier
 * @param metadata additional diagnostic values
 */
public record GraphDecisionTraceEvent(
        String eventId,
        String sourceId,
        String documentId,
        String chunkId,
        String shardId,
        String atom,
        GraphMissStage stage,
        GraphMissReason reason,
        String disposition,
        List<String> candidateIds,
        Map<String, Double> candidateScores,
        Map<String, String> metadata) {

    public GraphDecisionTraceEvent {
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        candidateScores = candidateScores == null ? Map.of() : Map.copyOf(candidateScores);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
