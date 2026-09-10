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
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class SlackChannelAdapter extends ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter implements SlackApiClient.SlackMessageHandler {

    private SlackApiClient apiClient;
    private String botToken;
    private String appToken;
    private final Set<String> allowedChannelIds = new HashSet<>();
    private boolean respondToAllMessages = false;
    private boolean allowAllInbound;

    public SlackChannelAdapter(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public String getChannelName() {
        return "slack";
    }

    public void setApiClient(SlackApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public void setAppToken(String appToken) {
        this.appToken = appToken;
    }

    public void addAllowedChannel(String channelId) {
        allowedChannelIds.add(channelId);
    }

    public void setRespondToAllMessages(boolean respondToAllMessages) {
        this.respondToAllMessages = respondToAllMessages;
    }

    public void setAllowAllInbound(boolean allowAllInbound) {
        this.allowAllInbound = allowAllInbound;
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            throw new IllegalStateException("Slack API client is not configured");
        }

        apiClient.addMessageHandler(this);
        apiClient.start(botToken, appToken);
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
            throw new IllegalStateException("Slack connection is not running");
        }
        apiClient.sendMessage(target, content, null);
        return DeliveryResult.accepted("Slack accepted the message");
    }

    @Override
    public void onMessage(SlackApiClient.SlackMessage message) {
        if (!respondToAllMessages) {
            return;
        }
        processMessage(message);
    }

    @Override
    public void onAppMention(SlackApiClient.SlackMessage message) {
        processMessage(message);
    }

    @Override
    public void onReady() {
        markReady();
        log.info("Slack adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        recordError(error);
        log.error("Slack adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    private void processMessage(SlackApiClient.SlackMessage message) {
        if (isBotMessage(message)) {
            return;
        }

        if (!isAllowed(message.channelId())) {
            return;
        }

        if (message.text() == null || message.text().isEmpty()) {
            return;
        }

        String cleanText = cleanMention(message.text());
        String threadId = message.threadTs() == null || message.threadTs().isBlank()
                ? message.ts() : message.threadTs();

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.ts(),
                message.userId(),
                resolveUserName(message.userId()),
                cleanText,
                message.channelId(),
                System.currentTimeMillis(),
                message.threadTs(),
                Map.of(
                        "slack_channel", message.channelId(),
                        "conversation_key", message.channelId() + ":" + threadId)
        );

        ChannelAdapter.MessageResponder responder = new SlackMessageResponder(
                apiClient, message.channelId(), threadId
        );
        createAgentHandler().handle(incoming, responder);
    }

    private boolean isBotMessage(SlackApiClient.SlackMessage message) {
        if (message.subtype() != null && "bot_message".equals(message.subtype())) {
            return true;
        }

        List<SlackApiClient.SlackUser> users = apiClient.getUsers();
        return users.stream()
                .filter(u -> u.id().equals(message.userId()))
                .findFirst()
                .map(SlackApiClient.SlackUser::isBot)
                .orElse(false);
    }

    private boolean isAllowed(String channelId) {
        return allowAllInbound || allowedChannelIds.contains(channelId);
    }

    private String cleanMention(String text) {
        return text.replaceAll("<@[A-Z0-9]+>", "")
                .replaceAll("<#[A-Z0-9]+\\|[^>]+>", "")
                .trim();
    }

    private String resolveUserName(String userId) {
        return apiClient.getUsers().stream()
                .filter(u -> u.id().equals(userId))
                .findFirst()
                .map(u -> u.realName() != null ? u.realName() : u.name())
                .orElse("User" + userId);
    }
}
