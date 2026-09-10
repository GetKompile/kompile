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
public class EmailChannelAdapter extends ai.kompile.gateway.core.gateway.channel.BaseChannelAdapter implements EmailClient.EmailMessageHandler {

    private EmailClient emailClient;
    private EmailClient.EmailConfig emailConfig;
    private final Set<String> allowedSenders = new HashSet<>();
    private boolean allowAllInbound;

    public EmailChannelAdapter(AgentExecutor agentExecutor) {
        super(agentExecutor);
    }

    @Override
    public String getChannelName() {
        return "email";
    }

    public void setEmailClient(EmailClient emailClient) {
        this.emailClient = emailClient;
    }

    public void setEmailConfig(EmailClient.EmailConfig emailConfig) {
        this.emailConfig = emailConfig;
    }

    public void addAllowedSender(String email) {
        allowedSenders.add(email.toLowerCase());
    }

    public void setAllowAllInbound(boolean allowAllInbound) {
        this.allowAllInbound = allowAllInbound;
    }

    @Override
    protected void doStart() {
        if (emailClient == null) {
            throw new IllegalStateException("Email client is not configured");
        }

        if (emailConfig == null) {
            throw new IllegalStateException("Email configuration is not set");
        }

        emailClient.addMessageHandler(this);
        emailClient.start(emailConfig);
        if (!emailClient.isRunning()) {
            emailClient.removeMessageHandler(this);
            throw new IllegalStateException("Email client could not connect");
        }
    }

    @Override
    protected void doStop() {
        if (emailClient != null) {
            emailClient.removeMessageHandler(this);
            emailClient.stop();
        }
    }

    @Override
    public void onMessage(EmailClient.EmailMessage message) {
        if (!message.authenticatedSender()) {
            log.warn("Ignoring email without aligned DMARC authentication");
            emailClient.markAsRead(message.messageId());
            return;
        }
        if (!isAllowed(message.from())) {
            log.debug("Ignoring email from unauthorized sender: {}", message.from());
            emailClient.markAsRead(message.messageId());
            return;
        }

        String body = message.body();
        if (body == null || body.isEmpty()) {
            body = message.bodyText();
        }
        if (body == null || body.isEmpty()) {
            emailClient.markAsRead(message.messageId());
            return;
        }

        ChannelAdapter.IncomingMessage incoming = new ChannelAdapter.IncomingMessage(
                message.messageId(),
                message.from(),
                message.fromName() != null ? message.fromName() : message.from(),
                message.to(),
                "email",
                message.timestamp(),
                message.inReplyTo(),
                Map.of(
                        "subject", message.subject() != null ? message.subject() : "",
                        "reply_to", message.from(),
                        "conversation_key", emailConversationKey(message)
                )
        );

        String emailBody = prependSubject(message.subject(), body);

        ChannelAdapter.MessageResponder responder = new EmailMessageResponder(
                emailClient,
                message.from(),
                message.subject(),
                message.messageId()
        );

        createAgentHandler().handle(
                new ChannelAdapter.IncomingMessage(
                        incoming.messageId(),
                        incoming.userId(),
                        incoming.userName(),
                        emailBody,
                        incoming.channelId(),
                        incoming.timestamp(),
                        incoming.replyToId(),
                        incoming.metadata()
                ),
                responder
        );

        emailClient.markAsRead(message.messageId());
    }

    @Override
    public void onReady() {
        markReady();
        log.info("Email adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        recordError(error);
        log.error("Email adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    @Override
    public DeliveryResult send(String target, String content) {
        if (emailClient == null || !isRunning()) {
            throw new IllegalStateException("Email connection is not running");
        }
        emailClient.sendEmail(target, "Kompile message", content);
        return DeliveryResult.accepted("Email accepted the message");
    }

    private boolean isAllowed(String from) {
        if (allowAllInbound) {
            return true;
        }
        if (from == null) {
            return false;
        }
        String fromLower = from.trim().toLowerCase(java.util.Locale.ROOT);
        return allowedSenders.contains(fromLower);
    }

    private String prependSubject(String subject, String body) {
        if (subject != null && !subject.isEmpty()) {
            return "Subject: " + subject + "\n\n" + body;
        }
        return body;
    }

    private static String emailConversationKey(EmailClient.EmailMessage message) {
        if (message.references() != null && !message.references().isBlank()) {
            return message.references().trim().split("\\s+")[0];
        }
        if (message.inReplyTo() != null && !message.inReplyTo().isBlank()) {
            return message.inReplyTo();
        }
        return message.messageId();
    }
}
