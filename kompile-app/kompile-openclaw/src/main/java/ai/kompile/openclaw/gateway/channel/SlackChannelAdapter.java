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
package ai.kompile.openclaw.gateway.channel;

import ai.kompile.openclaw.agent.OpenClawAgentService;
import ai.kompile.gateway.core.gateway.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class SlackChannelAdapter extends BaseChannelAdapter implements SlackApiClient.SlackMessageHandler {

    private SlackApiClient apiClient;
    private String botToken;
    private String appToken;
    private final Set<String> allowedChannelIds = new HashSet<>();
    private boolean respondToAllMessages = false;

    public SlackChannelAdapter(OpenClawAgentService agentService) {
        super(agentService);
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

    @Override
    protected void doStart() {
        if (apiClient == null) {
            log.warn("Slack API client not configured");
            return;
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
        log.info("Slack adapter ready");
    }

    @Override
    public void onError(Throwable error) {
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

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.ts(),
                message.userId(),
                resolveUserName(message.userId()),
                cleanText,
                message.channelId(),
                System.currentTimeMillis(),
                message.threadTs(),
                Map.of("slack_channel", message.channelId())
        );

        ChannelAdapter.MessageResponder responder = new SlackMessageResponder(
                apiClient, message.channelId(), message.ts()
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
        return allowedChannelIds.isEmpty() || allowedChannelIds.contains(channelId);
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
