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
        String storeProtocol = config.protocol();
        props.put("mail.store.protocol", config.protocol());
        props.put("mail." + storeProtocol + ".host", config.host());
        props.put("mail." + storeProtocol + ".port", String.valueOf(config.port()));
        props.put("mail." + storeProtocol + ".ssl.enable", "true");
        props.put("mail." + storeProtocol + ".ssl.checkserveridentity", "true");
        props.put("mail." + storeProtocol + ".connectiontimeout", "30000");
        props.put("mail." + storeProtocol + ".timeout", "30000");
        props.put("mail." + storeProtocol + ".writetimeout", "30000");

        props.put("mail.smtp.host", config.smtpHost());
        props.put("mail.smtp.port", String.valueOf(config.smtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        if (config.smtpPort() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username(), config.password());
            }
        });

        try {
            connectStore();
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

        closeStore();

        log.info("Email client stopped");
    }

    private synchronized void connectStore() throws Exception {
        closeStore();
        this.store = session.getStore(config.protocol());
        this.store.connect(config.host(), config.username(), config.password());
        this.inbox = store.getFolder("INBOX");
        this.inbox.open(Folder.READ_WRITE);
    }

    private synchronized void closeStore() {
        try {
            if (inbox != null && inbox.isOpen()) inbox.close(false);
            if (store != null && store.isConnected()) store.close();
        } catch (Exception e) {
            log.debug("Error closing email store", e);
        } finally {
            inbox = null;
            store = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public List<EmailMessage> fetchUnread() {
        List<EmailMessage> messages = new ArrayList<>();

        if (inbox == null || !inbox.isOpen()) {
            throw new IllegalStateException("Email inbox is disconnected");
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
            throw e instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Could not poll email inbox", e);
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
            throw new IllegalStateException("Failed to send email", e);
        }
    }

    @Override
    public void sendReply(String to, String subject, String body, String replyToMessageId) {
        try {
            String safeSubject = subject == null || subject.isBlank()
                    ? "Re: (no subject)"
                    : subject;
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.fromAddress(), config.fromName()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(safeSubject.regionMatches(true, 0, "Re:", 0, 3)
                    ? safeSubject : "Re: " + safeSubject, "UTF-8");
            message.setText(body, "UTF-8");

            if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
                message.setHeader("In-Reply-To", replyToMessageId);
                message.setHeader("References", replyToMessageId);
            }

            Transport.send(message);
            log.info("Sent email reply to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email reply to {}", to, e);
            throw new IllegalStateException("Failed to send email reply", e);
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
                    notifyError(e);
                    if (running) {
                        try {
                            connectStore();
                            notifyReady();
                        } catch (Exception reconnectFailure) {
                            notifyError(reconnectFailure);
                        }
                    }
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
            String[] messageIdHeaders = msg.getHeader("Message-ID");
            String messageId = messageIdHeaders != null && messageIdHeaders.length > 0
                    ? extractMessageId(messageIdHeaders[0])
                    : "generated-" + Integer.toUnsignedString(System.identityHashCode(msg));

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
                    authenticatedSender(msg, fromEmail, config.trustedAuthenticationServer()),
                    List.of()
            );
        } catch (Exception e) {
            log.error("Error converting email message", e);
            return null;
        }
    }

    /**
     * Trust inbound identity only when the receiving mail system reports aligned DMARC success.
     * A plain From header is attacker-controlled and is never enough to invoke an agent.
     */
    boolean authenticatedSender(
            Message msg, String fromEmail, String trustedAuthenticationServer)
            throws MessagingException {
        int at = fromEmail == null ? -1 : fromEmail.lastIndexOf('@');
        if (at < 0 || at == fromEmail.length() - 1) {
            return false;
        }
        String domain = fromEmail.substring(at + 1).trim().toLowerCase(java.util.Locale.ROOT);
        String[] results = msg.getHeader("Authentication-Results");
        if (results == null || results.length == 0
                || trustedAuthenticationServer == null || trustedAuthenticationServer.isBlank()) {
            return false;
        }
        String result = results[0]; // receiving MTAs prepend their own result ahead of supplied headers
        java.util.regex.Pattern trustedServer = java.util.regex.Pattern.compile(
                "^\\s*" + java.util.regex.Pattern.quote(trustedAuthenticationServer.trim())
                        + "(?:\\s+[0-9]+)?\\s*;",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        if (result == null || !trustedServer.matcher(result).find()) {
            return false;
        }
        java.util.regex.Pattern alignedFrom = java.util.regex.Pattern.compile(
                "(?:^|\\s)header\\.from\\s*=\\s*"
                        + java.util.regex.Pattern.quote(domain)
                        + "(?:\\s|$)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern dmarcPass = java.util.regex.Pattern.compile(
                "(?:^|\\s)dmarc\\s*=\\s*pass(?:\\s|$|\\()",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        // Each semicolon-delimited auth method owns its own result properties. Never combine a
        // passing DMARC method with header.from from a different (possibly failing) clause.
        for (String clause : result.split(";")) {
            if (dmarcPass.matcher(clause).find() && alignedFrom.matcher(clause).find()) {
                return true;
            }
        }
        return false;
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
