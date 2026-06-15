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
import java.util.Map;
import java.util.Set;

@Slf4j
public class WhatsAppChannelAdapter extends BaseChannelAdapter implements WhatsAppApiClient.WhatsAppMessageHandler {

    private WhatsAppApiClient apiClient;
    private String accessToken;
    private String phoneNumberId;
    private String verifyToken;
    private final Set<String> allowedPhoneNumbers = new HashSet<>();

    public WhatsAppChannelAdapter(OpenClawAgentService agentService) {
        super(agentService);
    }

    @Override
    public String getChannelName() {
        return "whatsapp";
    }

    public void setApiClient(WhatsAppApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public void addAllowedPhone(String phoneNumber) {
        allowedPhoneNumbers.add(phoneNumber);
    }

    @Override
    protected void doStart() {
        if (apiClient == null) {
            log.warn("WhatsApp API client not configured");
            return;
        }

        apiClient.addMessageHandler(this);
        apiClient.start(accessToken, phoneNumberId, verifyToken);
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
                message.timestamp() * 1000L,
                message.messageId(),
                Map.of("phone_number", message.from())
        );

        ChannelAdapter.MessageResponder responder = new WhatsAppMessageResponder(
                apiClient, message.from(), message.messageId()
        );
        createAgentHandler().handle(incoming, responder);

        apiClient.markAsRead(message.messageId());
    }

    @Override
    public void onStatusUpdate(String messageId, String status, String recipientId) {
        log.debug("WhatsApp message {} status: {} for {}", messageId, status, recipientId);
    }

    @Override
    public void onReady() {
        log.info("WhatsApp adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        log.error("WhatsApp adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    private boolean isAllowed(String from) {
        if (allowedPhoneNumbers.isEmpty()) {
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
