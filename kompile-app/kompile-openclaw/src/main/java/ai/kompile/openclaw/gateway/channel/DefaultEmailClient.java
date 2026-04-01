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

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class DefaultEmailClient implements EmailClient {

    private EmailConfig config;
    private Store store;
    private Session session;
    private Folder inbox;
    private Thread pollThread;
    private final List<EmailMessageHandler> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    @Override
    public void start(EmailConfig config) {
        this.config = config;

        Properties props = new Properties();
        props.put("mail.store.protocol", config.protocol());
        props.put("mail.imap.host", config.host());
        props.put("mail.imap.port", String.valueOf(config.port()));
        props.put("mail.imap.ssl.enable", String.valueOf(config.useSsl()));
        props.put("mail.imap.starttls.enable", String.valueOf(config.useStartTls()));

        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.useStartTls()));

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username(), config.password());
            }
        });

        try {
            this.store = session.getStore(config.protocol());
            this.store.connect(config.host(), config.username(), config.password());

            this.inbox = store.getFolder("INBOX");
            this.inbox.open(Folder.READ_WRITE);

            this.running = true;
            startPolling();

            log.info("Email client started for {}", config.username());
            notifyReady();

        } catch (Exception e) {
            log.error("Failed to start email client", e);
            notifyError(e);
        }
    }

    @Override
    public void stop() {
        this.running = false;

        if (pollThread != null) {
            pollThread.interrupt();
        }

        try {
            if (inbox != null && inbox.isOpen()) {
                inbox.close(false);
            }
            if (store != null) {
                store.close();
            }
        } catch (Exception e) {
            log.error("Error closing email store", e);
        }

        log.info("Email client stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public List<EmailMessage> fetchUnread() {
        List<EmailMessage> messages = new ArrayList<>();

        if (inbox == null || !inbox.isOpen()) {
            return messages;
        }

        try {
            Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            for (Message msg : unread) {
                EmailMessage email = convertMessage(msg);
                if (email != null) {
                    messages.add(email);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching unread emails", e);
        }

        return messages;
    }

    @Override
    public void markAsRead(String messageId) {
        if (inbox == null || !inbox.isOpen()) return;

        try {
            Message[] messages = inbox.getMessages();
            for (Message msg : messages) {
                String[] messageIdHeader = msg.getHeader("Message-ID");
                if (messageIdHeader != null && messageIdHeader.length > 0) {
                    if (messageId.equals(extractMessageId(messageIdHeader[0]))) {
                        msg.setFlag(Flags.Flag.SEEN, true);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error marking email as read", e);
        }
    }

    @Override
    public void sendEmail(String to, String subject, String body) {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.fromAddress(), config.fromName()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");

            Transport.send(message);
            log.info("Sent email to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to {}", to, e);
        }
    }

    @Override
    public void sendReply(String to, String subject, String body, String replyToMessageId) {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.fromAddress(), config.fromName()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject.startsWith("Re:") ? subject : "Re: " + subject, "UTF-8");
            message.setText(body, "UTF-8");

            if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
                message.setHeader("In-Reply-To", replyToMessageId);
                message.setHeader("References", replyToMessageId);
            }

            Transport.send(message);
            log.info("Sent email reply to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email reply to {}", to, e);
        }
    }

    @Override
    public void addMessageHandler(EmailMessageHandler handler) {
        handlers.add(handler);
    }

    @Override
    public void removeMessageHandler(EmailMessageHandler handler) {
        handlers.remove(handler);
    }

    private void startPolling() {
        pollThread = new Thread(() -> {
            while (running) {
                try {
                    List<EmailMessage> unread = fetchUnread();
                    for (EmailMessage email : unread) {
                        notifyMessage(email);
                    }
                    Thread.sleep(config.pollIntervalSeconds() * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in email polling", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "email-poller");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private EmailMessage convertMessage(Message msg) {
        try {
            String messageId = extractMessageId(msg.getHeader("Message-ID")[0]);

            Address[] from = msg.getFrom();
            String fromEmail = from != null && from.length > 0 
                    ? ((InternetAddress) from[0]).getAddress() : "unknown";
            String fromName = from != null && from.length > 0 
                    ? ((InternetAddress) from[0]).getPersonal() : null;

            Address[] to = msg.getRecipients(Message.RecipientType.TO);
            String toEmail = to != null && to.length > 0 
                    ? ((InternetAddress) to[0]).getAddress() : config.fromAddress();

            String subject = msg.getSubject();
            String bodyText = extractBody(msg);

            String[] replyTo = msg.getHeader("Reply-To");
            String replyToAddr = replyTo != null && replyTo.length > 0 
                    ? replyTo[0] : fromEmail;

            String[] inReplyTo = msg.getHeader("In-Reply-To");
            String inReplyToId = inReplyTo != null && inReplyTo.length > 0 
                    ? inReplyTo[0] : null;

            String[] refs = msg.getHeader("References");
            String references = refs != null && refs.length > 0 ? refs[0] : null;

            return new EmailMessage(
                    messageId,
                    fromEmail,
                    fromName,
                    toEmail,
                    subject,
                    bodyText,
                    bodyText,
                    null,
                    msg.getSentDate() != null ? msg.getSentDate().getTime() : System.currentTimeMillis(),
                    replyToAddr,
                    inReplyToId,
                    references,
                    List.of()
            );
        } catch (Exception e) {
            log.error("Error converting email message", e);
            return null;
        }
    }

    private String extractBody(Message msg) throws Exception {
        Object content = msg.getContent();

        if (content instanceof String) {
            return (String) content;
        }

        if (content instanceof MimeMultipart) {
            MimeMultipart multipart = (MimeMultipart) content;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/plain")) {
                    return (String) part.getContent();
                }
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                if (part.isMimeType("text/html")) {
                    String html = (String) part.getContent();
                    return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                }
            }
        }

        return "";
    }

    private String extractMessageId(String header) {
        if (header == null) return "";
        return header.replace("<", "").replace(">", "").trim();
    }

    private void notifyMessage(EmailMessage message) {
        for (EmailMessageHandler handler : handlers) {
            try {
                handler.onMessage(message);
            } catch (Exception e) {
                log.error("Error in email message handler", e);
            }
        }
    }

    private void notifyReady() {
        for (EmailMessageHandler handler : handlers) {
            try {
                handler.onReady();
            } catch (Exception e) {
                log.error("Error in email ready handler", e);
            }
        }
    }

    private void notifyError(Throwable error) {
        for (EmailMessageHandler handler : handlers) {
            try {
                handler.onError(error);
            } catch (Exception e) {
                log.error("Error in email error handler", e);
            }
        }
    }
}
