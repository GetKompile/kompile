/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.kclaw.gateway.channel;

import ai.kompile.kclaw.agent.KClawAgentService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class DiscordChannelAdapter extends BaseChannelAdapter implements DiscordApiClient.DiscordMessageHandler {

    private DiscordApiClient apiClient;
    private String botToken;
    private final Set<String> allowedChannelIds = new HashSet<>();
    private final Set<String> allowedGuildIds = new HashSet<>();

    public DiscordChannelAdapter(KClawAgentService agentService) {
        super(agentService);
    }

    @Override
    public String getChannelName() {
        return "discord";
    }

    public void setApiClient(DiscordApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public void addAllowedChannel(String channelId) {
        allowedChannelIds.add(channelId);
    }

    public void addAllowedGuild(String guildId) {
        allowedGuildIds.add(guildId);
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            log.warn("Discord API client not configured");
            return;
        }

        apiClient.addMessageHandler(this);
        apiClient.start(botToken);
    }

    @Override
    protected void doStop() {
        if (apiClient != null) {
            apiClient.removeMessageHandler(this);
            apiClient.stop();
        }
    }

    @Override
    public void onMessage(DiscordApiClient.DiscordMessage message) {
        if (!isAllowed(message)) {
            return;
        }

        if (message.author().bot()) {
            return;
        }

        if (message.content() == null || message.content().isEmpty()) {
            return;
        }

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.id(),
                message.author().id(),
                message.author().username(),
                message.content(),
                message.channelId(),
                message.timestamp(),
                message.referencedMessageId(),
                Map.of("guild_id", extractGuildId(message.channelId()))
        );

        ChannelAdapter.MessageResponder responder = new DiscordMessageResponder(apiClient, message.channelId());
        createAgentHandler().handle(incoming, responder);
    }

    @Override
    public void onReady() {
        log.info("Discord adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        log.error("Discord adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    private boolean isAllowed(DiscordApiClient.DiscordMessage message) {
        if (allowedChannelIds.isEmpty() && allowedGuildIds.isEmpty()) {
            return true;
        }

        if (allowedChannelIds.contains(message.channelId())) {
            return true;
        }

        String guildId = extractGuildId(message.channelId());
        return guildId != null && allowedGuildIds.contains(guildId);
    }

    private String extractGuildId(String channelId) {
        List<DiscordApiClient.DiscordChannel> channels = apiClient != null 
                ? apiClient.getGuilds().stream()
                        .flatMap(g -> apiClient.getChannels(g.id()).stream())
                        .toList()
                : List.of();

        return channels.stream()
                .filter(c -> c.id().equals(channelId))
                .map(DiscordApiClient.DiscordChannel::guildId)
                .findFirst()
                .orElse(null);
    }
}
