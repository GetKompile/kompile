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

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WhatsAppMessageResponder implements ChannelAdapter.MessageResponder {

    private final WhatsAppApiClient apiClient;
    private final String recipientPhone;
    private final String replyToMessageId;

    public WhatsAppMessageResponder(WhatsAppApiClient apiClient, String recipientPhone, String replyToMessageId) {
        this.apiClient = apiClient;
        this.recipientPhone = recipientPhone;
        this.replyToMessageId = replyToMessageId;
    }

    @Override
    public void reply(ChannelAdapter.OutgoingMessage message) {
        if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
            apiClient.sendReply(recipientPhone, message.content(), replyToMessageId);
        } else {
            apiClient.sendTextMessage(recipientPhone, message.content());
        }
    }

    @Override
    public void replyError(String error) {
        apiClient.sendTextMessage(recipientPhone, "Error: " + error);
    }

    @Override
    public void typing() {
        // WhatsApp doesn't have a typing indicator API
    }
}
