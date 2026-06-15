package ai.kompile.graphchangetracking.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public abstract class GraphMutationEvent extends ApplicationEvent {

    private final String mutationType;
    private final String entityKind;
    private final String entityId;
    private final Long factSheetId;
    private final String changesetId;
    private final String triggerSource;
    private final String actorId;
    private final String snapshotBefore;
    private final String snapshotAfter;

    protected GraphMutationEvent(Object source,
                                  String mutationType,
                                  String entityKind,
                                  String entityId,
                                  Long factSheetId,
                                  String changesetId,
                                  String triggerSource,
                                  String actorId,
                                  String snapshotBefore,
                                  String snapshotAfter) {
        super(source);
        this.mutationType = mutationType;
        this.entityKind = entityKind;
        this.entityId = entityId;
        this.factSheetId = factSheetId;
        this.changesetId = changesetId;
        this.triggerSource = triggerSource;
        this.actorId = actorId;
        this.snapshotBefore = snapshotBefore;
        this.snapshotAfter = snapshotAfter;
    }
}
