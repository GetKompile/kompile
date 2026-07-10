package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Persist-only listener for node and edge mutation events.
 *
 * <h3>Cascade scheduling is NOT done here</h3>
 * <p>Previously this class held a {@code GroundingResetPort} reference and called
 * {@code scheduleReground()} on every node/edge mutation — an immediate (non-debounced)
 * {@code schedule()} call that bypassed the debounce window in
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeHook}.  That caused every
 * single manual edit to fire a full cascade immediately, making the debounced path in
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeEventListener} dead code.</p>
 *
 * <p>Cascade scheduling is now owned exclusively by
 * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeEventListener}:
 * <ul>
 *   <li>Node/edge mutations → {@code scheduleDebounced} (15 s quiet / 300 s max)</li>
 *   <li>Changeset-completed / agent-assert / agent-retract → immediate {@code schedule}</li>
 * </ul>
 * This class is responsible ONLY for persisting {@link GraphMutationRecord}s and notifying
 * registered {@link GraphUpdateHookRegistry} hooks.</p>
 */
@Component
@Slf4j
public class GraphMutationRecordingListener {

    private final GraphMutationStore mutationStore;
    private final GraphUpdateHookRegistry hookRegistry;

    public GraphMutationRecordingListener(GraphMutationStore mutationStore,
                                           GraphUpdateHookRegistry hookRegistry) {
        this.mutationStore = mutationStore;
        this.hookRegistry = hookRegistry;
    }

    @EventListener
    @Async
    public void onNodeMutation(NodeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
        // Cascade scheduling is handled by GroundingCascadeEventListener.onNodeMutation
        // via scheduleDebounced — do NOT call schedule() here (causes double-fire).
    }

    @EventListener
    @Async
    public void onEdgeMutation(EdgeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
        // Cascade scheduling is handled by GroundingCascadeEventListener.onEdgeMutation
        // via scheduleDebounced — do NOT call schedule() here (causes double-fire).
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
            mutationStore.save(record);
        } catch (Exception e) {
            log.error("Failed to persist graph mutation record for {} {}", event.getMutationType(), event.getEntityId(), e);
        }
    }
}
