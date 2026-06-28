package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.graphchangetracking.repository.GraphMutationRecordRepository;
import ai.kompile.knowledgegraph.grounding.GroundingProgressEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GraphMutationRecordingListener {

    private final GraphMutationRecordRepository mutationRepo;
    private final GraphUpdateHookRegistry hookRegistry;

    /**
     * Optional — wired when the grounding cascade is on the classpath. Null-safe everywhere.
     * Injected via setter so the constructor remains compatible with test contexts that only
     * wire mutationRepo + hookRegistry.
     */
    @Nullable
    private GroundingResetPort groundingCascadeHook;

    public GraphMutationRecordingListener(GraphMutationRecordRepository mutationRepo,
                                           GraphUpdateHookRegistry hookRegistry) {
        this.mutationRepo = mutationRepo;
        this.hookRegistry = hookRegistry;
    }

    @Autowired(required = false)
    public void setGroundingCascadeHook(GroundingResetPort groundingCascadeHook) {
        this.groundingCascadeHook = groundingCascadeHook;
    }

    @EventListener
    @Async
    public void onNodeMutation(NodeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
        scheduleReground(event);
    }

    @EventListener
    @Async
    public void onEdgeMutation(EdgeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
        scheduleReground(event);
    }

    /**
     * Schedule a debounced re-ground after a manual node/edge mutation.
     *
     * <p>The {@link GroundingCascadeHook} coalesces concurrent calls so a burst of
     * mutations (e.g. a crawl emitting thousands of {@link NodeMutationEvent}s) collapses
     * into a single reground rather than queuing thousands of tasks. The crawl path also
     * fires a {@link ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent}
     * at the end — that event will coalesce with (or supersede) any pending task already
     * submitted here, still resulting in at most one reground per crawl.</p>
     *
     * <p>Manual REST edits (small batches, no changeset event) benefit directly because
     * the changeset event is not emitted on single-node REST writes.</p>
     */
    private void scheduleReground(GraphMutationEvent event) {
        if (groundingCascadeHook == null) {
            return;
        }
        Long fsId = event.getFactSheetId();
        if (fsId == null) {
            return;
        }
        groundingCascadeHook.schedule(fsId,
                event.getMutationType() + ":" + event.getEntityKind(),
                GroundingProgressEvent.TRIGGER_CASCADE);
    }

    private void persistMutation(GraphMutationEvent event) {
        try {
            GraphMutationRecord record = GraphMutationRecord.builder()
                    .mutationType(event.getMutationType())
                    .entityKind(event.getEntityKind())
                    .entityId(event.getEntityId())
                    .factSheetId(event.getFactSheetId())
                    .triggerSource(event.getTriggerSource())
                    .actorId(event.getActorId())
                    .snapshotBefore(event.getSnapshotBefore())
                    .snapshotAfter(event.getSnapshotAfter())
                    .changesetId(event.getChangesetId())
                    .build();
            mutationRepo.save(record);
        } catch (Exception e) {
            log.error("Failed to persist graph mutation record for {} {}", event.getMutationType(), event.getEntityId(), e);
        }
    }
}
