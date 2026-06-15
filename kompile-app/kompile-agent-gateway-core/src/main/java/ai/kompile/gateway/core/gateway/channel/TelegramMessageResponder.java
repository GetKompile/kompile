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
package ai.kompile.gateway.core.gateway.channel;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TelegramMessageResponder implements ChannelAdapter.MessageResponder {

    private final TelegramApiClient apiClient;
    private final Long chatId;

    public TelegramMessageResponder(TelegramApiClient apiClient, Long chatId) {
        this.apiClient = apiClient;
        this.chatId = chatId;
    }

    @Override
    public void reply(ChannelAdapter.OutgoingMessage message) {
        apiClient.sendMessage(String.valueOf(chatId), message.content());
    }

    @Override
    public void replyError(String error) {
        apiClient.sendMessage(String.valueOf(chatId), "Error: " + error);
    }

    @Override
    public void typing() {
        apiClient.sendChatAction(String.valueOf(chatId), "typing");
    }
}
