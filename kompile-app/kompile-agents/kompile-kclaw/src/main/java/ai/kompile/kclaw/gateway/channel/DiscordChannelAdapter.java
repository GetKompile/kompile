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

import ai.kompile.gateway.core.gateway.channel.*;
import ai.kompile.gateway.core.service.AgentExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class DiscordChannelAdapter extends ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter implements DiscordApiClient.DiscordMessageHandler {

    private DiscordApiClient apiClient;
    private String botToken;
    private final Set<String> allowedChannelIds = new HashSet<>();
    private final Set<String> allowedGuildIds = new HashSet<>();
    private boolean allowAllInbound;

    public DiscordChannelAdapter(AgentExecutor agentExecutor) {
        super(agentExecutor);
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

    public void setAllowAllInbound(boolean allowAllInbound) {
        this.allowAllInbound = allowAllInbound;
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            throw new IllegalStateException("Discord API client is not configured");
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
    public DeliveryResult send(String target, String content) {
        if (apiClient == null || !isRunning()) {
            throw new IllegalStateException("Discord connection is not running");
        }
        apiClient.sendMessage(target, content);
        return DeliveryResult.accepted("Discord accepted the message");
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
                message.guildId() == null
                        ? Map.of()
                        : Map.of("guild_id", message.guildId())
        );

        ChannelAdapter.MessageResponder responder = new DiscordMessageResponder(apiClient, message.channelId());
        createAgentHandler().handle(incoming, responder);
    }

    @Override
    public void onReady() {
        markReady();
        log.info("Discord adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        recordError(error);
        log.error("Discord adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    private boolean isAllowed(DiscordApiClient.DiscordMessage message) {
        if (allowAllInbound) {
            return true;
        }

        if (allowedChannelIds.contains(message.channelId())) {
            return true;
        }

        return message.guildId() != null && allowedGuildIds.contains(message.guildId());
    }
}
