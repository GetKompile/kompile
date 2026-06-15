package ai.kompile.graphchangetracking.websocket;

import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@Slf4j
public class GraphChangeWebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    public GraphChangeWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void onNodeMutation(NodeMutationEvent event) {
        broadcastMutation(event);
    }

    @EventListener
    public void onEdgeMutation(EdgeMutationEvent event) {
        broadcastMutation(event);
    }

    @EventListener
    public void onChangesetCompleted(GraphChangesetCompletedEvent event) {
        if (messagingTemplate == null) return;

        Map<String, Object> payload = Map.of(
                "changesetId", event.getChangesetId(),
                "nodesCreated", event.getNodesCreated(),
                "nodesUpdated", event.getNodesUpdated(),
                "nodesDeleted", event.getNodesDeleted(),
                "edgesCreated", event.getEdgesCreated(),
                "edgesDeleted", event.getEdgesDeleted(),
                "timestamp", Instant.now().toString()
        );

        if (event.getFactSheetId() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/graph/" + event.getFactSheetId() + "/changesets", payload);
        }
        messagingTemplate.convertAndSend("/topic/graph/changesets", payload);
    }

    private void broadcastMutation(GraphMutationEvent event) {
        if (messagingTemplate == null) return;

        Map<String, Object> payload = Map.of(
                "type", event.getMutationType(),
                "entityKind", event.getEntityKind(),
                "entityId", event.getEntityId(),
                "triggerSource", event.getTriggerSource() != null ? event.getTriggerSource() : "UNKNOWN",
                "timestamp", Instant.now().toString()
        );

        if (event.getFactSheetId() != null) {
            messagingTemplate.convertAndSend(
                    "/topic/graph/" + event.getFactSheetId() + "/changes", payload);
        }
        messagingTemplate.convertAndSend("/topic/graph/changes", payload);
    }
}
