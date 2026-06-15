package ai.kompile.graphchangetracking.event;

import ai.kompile.graphchangetracking.service.MutationContextHolder;
import lombok.Getter;

@Getter
public class NodeMutationEvent extends GraphMutationEvent {

    private final String nodeType;

    private NodeMutationEvent(Object source,
                               String mutationType,
                               String entityId,
                               Long factSheetId,
                               String nodeType,
                               String changesetId,
                               String triggerSource,
                               String actorId,
                               String snapshotBefore,
                               String snapshotAfter) {
        super(source, mutationType, "NODE", entityId, factSheetId,
                changesetId, triggerSource, actorId, snapshotBefore, snapshotAfter);
        this.nodeType = nodeType;
    }

    public static NodeMutationEvent created(Object source, String nodeId, Long factSheetId,
                                             String nodeType, String snapshotAfter,
                                             MutationContextHolder.MutationContext ctx) {
        return new NodeMutationEvent(source, "NODE_CREATED", nodeId, factSheetId, nodeType,
                ctx.changesetId(), ctx.triggerSource(), ctx.actorId(), null, snapshotAfter);
    }

    public static NodeMutationEvent updated(Object source, String nodeId, Long factSheetId,
                                             String nodeType, String snapshotBefore, String snapshotAfter,
                                             MutationContextHolder.MutationContext ctx) {
        return new NodeMutationEvent(source, "NODE_UPDATED", nodeId, factSheetId, nodeType,
                ctx.changesetId(), ctx.triggerSource(), ctx.actorId(), snapshotBefore, snapshotAfter);
    }

    public static NodeMutationEvent deleted(Object source, String nodeId, Long factSheetId,
                                             String nodeType, String snapshotBefore,
                                             MutationContextHolder.MutationContext ctx) {
        return new NodeMutationEvent(source, "NODE_DELETED", nodeId, factSheetId, nodeType,
                ctx.changesetId(), ctx.triggerSource(), ctx.actorId(), snapshotBefore, null);
    }
}
