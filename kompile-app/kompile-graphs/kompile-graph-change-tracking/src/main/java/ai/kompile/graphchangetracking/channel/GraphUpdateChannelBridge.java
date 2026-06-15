package ai.kompile.graphchangetracking.channel;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.graphchangetracking.event.GraphPipelineTriggeredEvent;
import ai.kompile.graphchangetracking.hook.ChannelGraphUpdateContext;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import ai.kompile.graphchangetracking.service.GraphUpdatePipelineConfigService;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelMessageReceivedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class GraphUpdateChannelBridge {

    private final GraphUpdatePipelineConfigService pipelineConfigService;
    private final GraphUpdateHookRegistry hookRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public GraphUpdateChannelBridge(GraphUpdatePipelineConfigService pipelineConfigService,
                                     GraphUpdateHookRegistry hookRegistry,
                                     ApplicationEventPublisher eventPublisher,
                                     ObjectMapper objectMapper) {
        this.pipelineConfigService = pipelineConfigService;
        this.hookRegistry = hookRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Async
    public void onChannelMessage(ChannelMessageReceivedEvent event) {
        String channelName = event.getChannelName();
        ChannelAdapter.IncomingMessage message = event.getMessage();

        List<GraphUpdatePipelineConfig> matching = pipelineConfigService.getEnabledForChannel(channelName);
        if (matching.isEmpty()) {
            return;
        }

        for (GraphUpdatePipelineConfig config : matching) {
            if (!matchesFilter(message, config)) {
                continue;
            }

            String changesetId = UUID.randomUUID().toString();
            ChannelGraphUpdateContext ctx = new ChannelGraphUpdateContext(
                    config.getPipelineId(), channelName, message, config, changesetId);

            try {
                eventPublisher.publishEvent(new GraphPipelineTriggeredEvent(
                        this, config.getPipelineId(), config.getPipelineName(),
                        channelName, message.messageId(),
                        Map.of("userId", message.userId(), "userName", message.userName())
                ));

                hookRegistry.executeChannelMessage(ctx);
                log.info("Pipeline {} triggered by {} message {}", config.getPipelineId(),
                        channelName, message.messageId());
            } catch (Exception e) {
                log.error("Pipeline {} failed for {} message {}", config.getPipelineId(),
                        channelName, message.messageId(), e);
                hookRegistry.executePipelineError(config.getPipelineId(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean matchesFilter(ChannelAdapter.IncomingMessage message, GraphUpdatePipelineConfig config) {
        String filterJson = config.getFilterJson();
        if (filterJson == null || filterJson.isBlank()) {
            return true;
        }

        try {
            Map<String, Object> filters = objectMapper.readValue(filterJson, Map.class);

            String senderContains = (String) filters.get("senderContains");
            if (senderContains != null && !senderContains.isBlank()) {
                String userName = message.userName();
                String userId = message.userId();
                if ((userName == null || !userName.contains(senderContains))
                        && (userId == null || !userId.contains(senderContains))) {
                    return false;
                }
            }

            String contentContains = (String) filters.get("contentContains");
            if (contentContains != null && !contentContains.isBlank()) {
                String content = message.content();
                if (content == null || !content.contains(contentContains)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("Failed to parse filter JSON for pipeline {}: {}", config.getPipelineId(), filterJson, e);
            return true;
        }
    }
}
