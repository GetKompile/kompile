package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import ai.kompile.graphchangetracking.repository.GraphMutationRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GraphMutationRecordingListener {

    private final GraphMutationRecordRepository mutationRepo;
    private final GraphUpdateHookRegistry hookRegistry;

    public GraphMutationRecordingListener(GraphMutationRecordRepository mutationRepo,
                                           GraphUpdateHookRegistry hookRegistry) {
        this.mutationRepo = mutationRepo;
        this.hookRegistry = hookRegistry;
    }

    @EventListener
    @Async
    public void onNodeMutation(NodeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
    }

    @EventListener
    @Async
    public void onEdgeMutation(EdgeMutationEvent event) {
        persistMutation(event);
        hookRegistry.executeGraphMutated(event);
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
