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
import java.util.Set;

@Slf4j
public class TelegramChannelAdapter extends BaseChannelAdapter {

    private TelegramApiClient apiClient;
    private TelegramPoller poller;
    private final Set<Long> allowedChatIds = new HashSet<>();

    public TelegramChannelAdapter(OpenClawAgentService agentService) {
        super(agentService);
    }

    @Override
    public String getChannelName() {
        return "telegram";
    }

    public void setApiClient(TelegramApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void addAllowedChat(Long chatId) {
        allowedChatIds.add(chatId);
        if (poller != null) {
            poller.addAllowedChat(chatId);
        }
    }

    public void removeAllowedChat(Long chatId) {
        allowedChatIds.remove(chatId);
        if (poller != null) {
            poller.removeAllowedChat(chatId);
        }
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            log.warn("Telegram API client not configured, adapter will not start");
            return;
        }

        this.poller = new TelegramPoller(
                apiClient,
                createAgentHandler(),
                allowedChatIds,
                1000,
                30
        );
        this.poller.start();
    }

    @Override
    protected void doStop() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }
}
