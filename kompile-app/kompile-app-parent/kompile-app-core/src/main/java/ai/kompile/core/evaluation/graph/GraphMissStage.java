package ai.kompile.core.evaluation.graph;

/**
 * Ordered stages at which a gold graph atom can be lost.
 *
 * <p>Declaration order is causal order and is used by {@link GraphMissAttributor}.</p>
 */
public enum GraphMissStage {
    SOURCE_SHARD,
    PROPOSITION,
    MENTION_IDENTITY,
    EPISTEMIC,
    RELATION_SELECTION,
    VALIDATOR,
    GRAPH_ADMISSION,
    MERGE_PERSISTENCE,
    PROCESS_SYNTHESIS
}
