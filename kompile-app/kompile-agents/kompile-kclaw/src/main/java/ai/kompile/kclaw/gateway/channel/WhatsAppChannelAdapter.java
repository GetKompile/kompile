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

import ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.service.AgentExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class WhatsAppChannelAdapter extends BaseChannelAdapter implements WhatsAppApiClient.WhatsAppMessageHandler {

    private WhatsAppApiClient apiClient;
    private String accessToken;
    private String phoneNumberId;
    private String verifyToken;
    private String appSecret;
    private final Set<String> allowedPhoneNumbers = new HashSet<>();
    private boolean allowAllInbound;

    public WhatsAppChannelAdapter(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public String getChannelName() {
        return "whatsapp";
    }

    public void setApiClient(WhatsAppApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public WhatsAppApiClient getApiClient() {
        return apiClient;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    /** Called only by the durable, signature-authenticated webhook inbox. */
    public void processWebhookPayload(Map<String, Object> payload) {
        if (apiClient == null || !isRunning()) {
            throw new IllegalStateException("WhatsApp connection is not running");
        }
        apiClient.processWebhookPayload(payload);
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public void addAllowedPhone(String phoneNumber) {
        allowedPhoneNumbers.add(phoneNumber);
    }

    public void setAllowAllInbound(boolean allowAllInbound) {
        this.allowAllInbound = allowAllInbound;
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            throw new IllegalStateException("WhatsApp API client is not configured");
        }

        apiClient.addMessageHandler(this);
        apiClient.start(accessToken, phoneNumberId, verifyToken, appSecret);
    }

    @Override
    protected void doStop() {
        if (apiClient != null) {
            apiClient.removeMessageHandler(this);
            apiClient.stop();
        }
    }

    @Override
    public void onMessage(WhatsAppApiClient.WhatsAppMessage message) {
        if (!isAllowed(message.from())) {
            log.debug("Ignoring message from unauthorized number: {}", message.from());
            return;
        }

        if (message.text() == null || message.text().isEmpty()) {
            return;
        }

        String cleanPhone = cleanPhoneNumber(message.from());

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.id(),
                cleanPhone,
                message.fromName() != null ? message.fromName() : cleanPhone,
                message.text(),
                phoneNumberId,
                message.timestamp(),
                message.messageId(),
                Map.of("phone_number", message.from())
        );

        String replyToId = message.messageId();
        String recipientPhone = message.from();
        ChannelAdapter.MessageResponder responder = new ChannelAdapter.MessageResponder() {
            @Override
            public void reply(ChannelAdapter.OutgoingMessage msg) {
                if (replyToId != null && !replyToId.isEmpty()) {
                    apiClient.sendReply(recipientPhone, msg.content(), replyToId);
                } else {
                    apiClient.sendTextMessage(recipientPhone, msg.content());
                }
            }

            @Override
            public void replyError(String error) {
                apiClient.sendTextMessage(recipientPhone, "Error: " + error);
            }

            @Override
            public void typing() {
                // WhatsApp does not have a typing indicator API
            }
        };
        createAgentHandler().handle(incoming, responder);

        apiClient.markAsRead(message.messageId());
    }

    @Override
    public void onStatusUpdate(String messageId, String status, String recipientId) {
        log.debug("WhatsApp message {} status: {} for {}", messageId, status, recipientId);
    }

    @Override
    public void onReady() {
        markReady();
        log.info("WhatsApp adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        recordError(error);
        log.error("WhatsApp adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    @Override
    public DeliveryResult send(String target, String content) {
        if (apiClient == null || !isRunning()) {
            throw new IllegalStateException("WhatsApp connection is not running");
        }
        apiClient.sendTextMessage(target, content);
        return DeliveryResult.accepted("WhatsApp accepted the message");
    }

    private boolean isAllowed(String from) {
        if (allowAllInbound) {
            return true;
        }
        String cleanFrom = cleanPhoneNumber(from);
        return allowedPhoneNumbers.stream()
                .anyMatch(allowed -> cleanPhoneNumber(allowed).equals(cleanFrom));
    }

    private String cleanPhoneNumber(String phone) {
        if (phone == null) return "";
        return phone.replaceAll("[^0-9]", "");
    }
}
