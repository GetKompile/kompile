package ai.kompile.kclaw.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultDiscordApiClientParsingTest {

    @Test
    void parsesNumericDiscordChannelTypesAndFiltersNonTextChannels() throws Exception {
        var channels = DefaultDiscordApiClient.parseChannelsResponse(
                JsonUtils.standardMapper(),
                """
                        [
                          {"id":"text","name":"general","type":0,"position":1},
                          {"id":"announcement","name":"news","type":5,"position":2},
                          {"id":"voice","name":"voice","type":2,"position":3}
                        ]
                        """,
                "guild-1");

        assertEquals(2, channels.size());
        assertEquals("0", channels.get(0).type());
        assertEquals("5", channels.get(1).type());
        assertEquals("guild-1", channels.get(0).guildId());
    }
}
