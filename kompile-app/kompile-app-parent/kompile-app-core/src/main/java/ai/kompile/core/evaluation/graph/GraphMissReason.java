package ai.kompile.core.evaluation.graph;

/** Stable, machine-readable reasons for a graph evaluation miss. */
public enum GraphMissReason {
    SOURCE_SHARD_MISSING(GraphMissStage.SOURCE_SHARD),
    SOURCE_NOT_SCHEDULED(GraphMissStage.SOURCE_SHARD),
    SOURCE_OUTCOME_UNUSABLE(GraphMissStage.SOURCE_SHARD),
    PROPOSITION_NOT_PRODUCED(GraphMissStage.PROPOSITION),
    MENTION_IDENTITY_UNRESOLVED(GraphMissStage.MENTION_IDENTITY),
    EPISTEMIC_REJECTED(GraphMissStage.EPISTEMIC),
    RELATION_NOT_SELECTED(GraphMissStage.RELATION_SELECTION),
    RELATION_WRONG_TYPE(GraphMissStage.RELATION_SELECTION),
    VALIDATOR_REJECTED(GraphMissStage.VALIDATOR),
    CANDIDATE_SCORE_BELOW_THRESHOLD(GraphMissStage.GRAPH_ADMISSION),
    EXTRACTION_CONFIDENCE_BELOW_THRESHOLD(GraphMissStage.GRAPH_ADMISSION),
    PERSISTENCE_CONFIDENCE_BELOW_THRESHOLD(GraphMissStage.GRAPH_ADMISSION),
    IDENTITY_SIMILARITY_BELOW_THRESHOLD(GraphMissStage.GRAPH_ADMISSION),
    MERGE_PERSISTENCE_FAILED(GraphMissStage.MERGE_PERSISTENCE),
    PROCESS_SYNTHESIS_FAILED(GraphMissStage.PROCESS_SYNTHESIS),
    PROCESS_CONCEPT_RECALL_INSUFFICIENT(GraphMissStage.PROCESS_SYNTHESIS),
    PROCESS_ORDER_RECALL_INSUFFICIENT(GraphMissStage.PROCESS_SYNTHESIS),
    PROCESS_TRACE_COUNT_INSUFFICIENT(GraphMissStage.PROCESS_SYNTHESIS),
    UNATTRIBUTED(null);

    private final GraphMissStage stage;

    GraphMissReason(GraphMissStage stage) {
        this.stage = stage;
    }

    /** The stage this code belongs to, or {@code null} for {@link #UNATTRIBUTED}. */
    public GraphMissStage stage() {
        return stage;
    }
}
