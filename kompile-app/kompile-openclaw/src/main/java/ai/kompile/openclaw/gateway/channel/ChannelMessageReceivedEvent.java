package ai.kompile.openclaw.gateway.channel;

import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class ChannelMessageReceivedEvent extends ApplicationEvent {

    private final ChannelAdapter.IncomingMessage message;
    private final String channelName;

    public ChannelMessageReceivedEvent(Object source,
                                        ChannelAdapter.IncomingMessage message,
                                        String channelName) {
        super(source);
        this.message = message;
        this.channelName = channelName;
    }
}
