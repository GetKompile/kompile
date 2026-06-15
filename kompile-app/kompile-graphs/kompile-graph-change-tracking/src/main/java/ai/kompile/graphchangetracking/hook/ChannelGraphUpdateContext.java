package ai.kompile.graphchangetracking.hook;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ChannelGraphUpdateContext {

    private final String pipelineId;
    private final String channelName;
    private final ChannelAdapter.IncomingMessage message;
    private final GraphUpdatePipelineConfig pipelineConfig;
    private final String changesetId;
    private final Map<String, Object> processingAttributes;

    public ChannelGraphUpdateContext(String pipelineId,
                                     String channelName,
                                     ChannelAdapter.IncomingMessage message,
                                     GraphUpdatePipelineConfig pipelineConfig,
                                     String changesetId) {
        this.pipelineId = pipelineId;
        this.channelName = channelName;
        this.message = message;
        this.pipelineConfig = pipelineConfig;
        this.changesetId = changesetId;
        this.processingAttributes = new ConcurrentHashMap<>();
    }

    public ChannelGraphUpdateContext withAttribute(String key, Object value) {
        this.processingAttributes.put(key, value);
        return this;
    }
}
