package ai.kompile.graphchangetracking.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published once per batch mutation call (createNodesBatch, createEdgesBatch, etc.)
 * instead of one event per item. Listeners use this for coalesced cascade scheduling
 * rather than absorbing a storm of per-item events during a crawl.
 */
@Getter
public class GraphBatchMutationEvent extends ApplicationEvent {

    private final String batchType;     // e.g. "NODES_CREATED", "EDGES_CREATED"
    private final Long factSheetId;
    private final int itemCount;
    private final String changesetId;
    private final String triggerSource;

    public GraphBatchMutationEvent(Object source,
                                   String batchType,
                                   Long factSheetId,
                                   int itemCount,
                                   String changesetId,
                                   String triggerSource) {
        super(source);
        this.batchType = batchType;
        this.factSheetId = factSheetId;
        this.itemCount = itemCount;
        this.changesetId = changesetId;
        this.triggerSource = triggerSource;
    }
}
