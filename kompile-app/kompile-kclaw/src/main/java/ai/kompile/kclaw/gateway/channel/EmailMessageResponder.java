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

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EmailMessageResponder implements ChannelAdapter.MessageResponder {

    private final EmailClient emailClient;
    private final String replyTo;
    private final String originalSubject;
    private final String replyToMessageId;

    public EmailMessageResponder(
            EmailClient emailClient,
            String replyTo,
            String originalSubject,
            String replyToMessageId) {
        this.emailClient = emailClient;
        this.replyTo = replyTo;
        this.originalSubject = originalSubject;
        this.replyToMessageId = replyToMessageId;
    }

    @Override
    public void reply(ChannelAdapter.OutgoingMessage message) {
        String subject = originalSubject;
        if (subject != null && !subject.toLowerCase().startsWith("re:")) {
            subject = "Re: " + subject;
        }

        emailClient.sendReply(replyTo, subject, message.content(), replyToMessageId);
    }

    @Override
    public void replyError(String error) {
        String subject = originalSubject;
        if (subject != null && !subject.toLowerCase().startsWith("re:")) {
            subject = "Re: " + subject;
        }

        emailClient.sendReply(replyTo, subject, "Error: " + error, replyToMessageId);
    }

    @Override
    public void typing() {
        // Email doesn't support typing indicators
    }
}
