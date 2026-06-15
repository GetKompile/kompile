package ai.kompile.graphchangetracking.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

@Getter
public class GraphPipelineTriggeredEvent extends ApplicationEvent {

    private final String pipelineId;
    private final String pipelineName;
    private final String channelName;
    private final String triggerMessageId;
    private final Map<String, Object> messageContext;

    public GraphPipelineTriggeredEvent(Object source,
                                        String pipelineId,
                                        String pipelineName,
                                        String channelName,
                                        String triggerMessageId,
                                        Map<String, Object> messageContext) {
        super(source);
        this.pipelineId = pipelineId;
        this.pipelineName = pipelineName;
        this.channelName = channelName;
        this.triggerMessageId = triggerMessageId;
        this.messageContext = messageContext;
    }
}
