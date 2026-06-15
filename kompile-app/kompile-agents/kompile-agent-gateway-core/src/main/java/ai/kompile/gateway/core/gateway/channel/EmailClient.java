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

import java.util.List;

public interface EmailClient {

    void start(EmailConfig config);

    void stop();

    boolean isRunning();

    List<EmailMessage> fetchUnread();

    void markAsRead(String messageId);

    void sendEmail(String to, String subject, String body);

    void sendReply(String to, String subject, String body, String replyToMessageId);

    void addMessageHandler(EmailMessageHandler handler);

    void removeMessageHandler(EmailMessageHandler handler);

    record EmailConfig(
            String host,
            int port,
            String username,
            String password,
            String protocol,
            boolean useSsl,
            boolean useStartTls,
            String smtpHost,
            int smtpPort,
            String fromAddress,
            String fromName,
            int pollIntervalSeconds
    ) {
        public static EmailConfig defaults() {
            return new EmailConfig(
                    "imap.gmail.com", 993,
                    null, null,
                    "imaps", true, true,
                    "smtp.gmail.com", 587,
                    null, "KClaw Assistant",
                    60
            );
        }
    }

    record EmailMessage(
            String messageId,
            String from,
            String fromName,
            String to,
            String subject,
            String body,
            String bodyText,
            String bodyHtml,
            long timestamp,
            String replyTo,
            String inReplyTo,
            String references,
            List<EmailAttachment> attachments
    ) {}

    record EmailAttachment(
            String filename,
            String contentType,
            byte[] content
    ) {}

    interface EmailMessageHandler {
        void onMessage(EmailMessage message);
        void onReady();
        void onError(Throwable error);
    }
}
