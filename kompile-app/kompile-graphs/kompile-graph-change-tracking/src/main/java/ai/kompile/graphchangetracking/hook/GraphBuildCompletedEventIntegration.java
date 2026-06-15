package ai.kompile.graphchangetracking.hook;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GraphBuildCompletedEventIntegration {

    private final ApplicationEventPublisher eventPublisher;
    private final GraphUpdateHookRegistry hookRegistry;

    public GraphBuildCompletedEventIntegration(ApplicationEventPublisher eventPublisher,
                                                GraphUpdateHookRegistry hookRegistry) {
        this.eventPublisher = eventPublisher;
        this.hookRegistry = hookRegistry;
    }

    @EventListener
    public void onGraphBuildCompleted(GraphBuildCompletedEvent event) {
        String changesetId = "graphbuild:" + event.getJobId();

        GraphChangesetCompletedEvent changesetEvent = new GraphChangesetCompletedEvent(
                this, changesetId,
                event.getEntitiesExtracted(), 0, 0,
                event.getEdgesCreated(), 0,
                event.getFactSheetId()
        );

        eventPublisher.publishEvent(changesetEvent);
        hookRegistry.executeChangesetComplete(changesetEvent);

        log.info("Bridged GraphBuildCompletedEvent to changeset: {} (entities={}, edges={})",
                changesetId, event.getEntitiesExtracted(), event.getEdgesCreated());
    }
}
