package ai.kompile.graphchangetracking.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class GraphChangesetCompletedEvent extends ApplicationEvent {

    private final String changesetId;
    private final int nodesCreated;
    private final int nodesUpdated;
    private final int nodesDeleted;
    private final int edgesCreated;
    private final int edgesDeleted;
    private final Long factSheetId;

    public GraphChangesetCompletedEvent(Object source, String changesetId,
                                         int nodesCreated, int nodesUpdated, int nodesDeleted,
                                         int edgesCreated, int edgesDeleted,
                                         Long factSheetId) {
        super(source);
        this.changesetId = changesetId;
        this.nodesCreated = nodesCreated;
        this.nodesUpdated = nodesUpdated;
        this.nodesDeleted = nodesDeleted;
        this.edgesCreated = edgesCreated;
        this.edgesDeleted = edgesDeleted;
        this.factSheetId = factSheetId;
    }
}
