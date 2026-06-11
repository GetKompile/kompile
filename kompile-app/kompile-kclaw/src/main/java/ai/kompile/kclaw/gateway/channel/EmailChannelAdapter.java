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
import java.util.Map;
import java.util.Set;

@Slf4j
public class EmailChannelAdapter extends BaseChannelAdapter implements EmailClient.EmailMessageHandler {

    private EmailClient emailClient;
    private EmailClient.EmailConfig emailConfig;
    private final Set<String> allowedSenders = new HashSet<>();

    public EmailChannelAdapter(KClawAgentService agentService) {
        super(agentService);
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

    @Override
    protected void doStart() {
        if (emailClient == null) {
            log.warn("Email client not configured");
            return;
        }

        if (emailConfig == null) {
            log.warn("Email configuration not set");
            return;
        }

        emailClient.addMessageHandler(this);
        emailClient.start(emailConfig);
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
        if (!isAllowed(message.from())) {
            log.debug("Ignoring email from unauthorized sender: {}", message.from());
            return;
        }

        String body = message.body();
        if (body == null || body.isEmpty()) {
            body = message.bodyText();
        }
        if (body == null || body.isEmpty()) {
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
                        "reply_to", message.replyTo() != null ? message.replyTo() : message.from()
                )
        );

        String emailBody = prependSubject(message.subject(), body);

        ChannelAdapter.MessageResponder responder = new EmailMessageResponder(
                emailClient,
                message.replyTo() != null ? message.replyTo() : message.from(),
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
        log.info("Email adapter ready");
    }

    @Override
    public void onError(Throwable error) {
        log.error("Email adapter error", error);
    }

    @Override
    public AdapterConfig getAdapterConfig() {
        return channelConfigs.values().stream().findFirst().orElse(null);
    }

    private boolean isAllowed(String from) {
        if (allowedSenders.isEmpty()) {
            return true;
        }
        String fromLower = from.toLowerCase();
        return allowedSenders.stream()
                .anyMatch(allowed -> fromLower.contains(allowed) || allowed.equals("*"));
    }

    private String prependSubject(String subject, String body) {
        if (subject != null && !subject.isEmpty()) {
            return "Subject: " + subject + "\n\n" + body;
        }
        return body;
    }
}
