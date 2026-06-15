package ai.kompile.graphchangetracking.event;

import ai.kompile.graphchangetracking.service.MutationContextHolder;
import lombok.Getter;

@Getter
public class EdgeMutationEvent extends GraphMutationEvent {

    private final String edgeType;
    private final String sourceNodeId;
    private final String targetNodeId;

    private EdgeMutationEvent(Object source,
                               String mutationType,
                               String entityId,
                               Long factSheetId,
                               String edgeType,
                               String sourceNodeId,
                               String targetNodeId,
                               String changesetId,
                               String triggerSource,
                               String actorId,
                               String snapshotBefore,
                               String snapshotAfter) {
        super(source, mutationType, "EDGE", entityId, factSheetId,
                changesetId, triggerSource, actorId, snapshotBefore, snapshotAfter);
        this.edgeType = edgeType;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
    }

    public static EdgeMutationEvent created(Object source, String edgeId, Long factSheetId,
                                             String edgeType, String sourceNodeId, String targetNodeId,
                                             String snapshotAfter, MutationContextHolder.MutationContext ctx) {
        return new EdgeMutationEvent(source, "EDGE_CREATED", edgeId, factSheetId, edgeType,
                sourceNodeId, targetNodeId, ctx.changesetId(), ctx.triggerSource(), ctx.actorId(),
                null, snapshotAfter);
    }

    public static EdgeMutationEvent updated(Object source, String edgeId, Long factSheetId,
                                             String edgeType, String sourceNodeId, String targetNodeId,
                                             String snapshotBefore, String snapshotAfter,
                                             MutationContextHolder.MutationContext ctx) {
        return new EdgeMutationEvent(source, "EDGE_UPDATED", edgeId, factSheetId, edgeType,
                sourceNodeId, targetNodeId, ctx.changesetId(), ctx.triggerSource(), ctx.actorId(),
                snapshotBefore, snapshotAfter);
    }

    public static EdgeMutationEvent deleted(Object source, String edgeId, Long factSheetId,
                                             String edgeType, String sourceNodeId, String targetNodeId,
                                             String snapshotBefore, MutationContextHolder.MutationContext ctx) {
        return new EdgeMutationEvent(source, "EDGE_DELETED", edgeId, factSheetId, edgeType,
                sourceNodeId, targetNodeId, ctx.changesetId(), ctx.triggerSource(), ctx.actorId(),
                snapshotBefore, null);
    }
}
