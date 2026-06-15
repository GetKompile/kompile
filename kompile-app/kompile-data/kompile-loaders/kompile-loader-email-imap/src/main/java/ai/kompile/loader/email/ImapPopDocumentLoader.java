/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.email;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Document loader for IMAP and POP3 email servers.
 * Fetches emails from mail servers and converts them to Spring AI Document objects
 * with rich metadata including threading information.
 */
@Component
public class ImapPopDocumentLoader implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(ImapPopDocumentLoader.class);

    private final EmailConnectionFactory connectionFactory;

    public ImapPopDocumentLoader(EmailConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String getName() {
        return "IMAP/POP3 Email Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        DocumentSourceDescriptor.SourceType type = sourceDescriptor.getType();
        return type == DocumentSourceDescriptor.SourceType.EMAIL
                || type == DocumentSourceDescriptor.SourceType.IMAP
                || type == DocumentSourceDescriptor.SourceType.POP3;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        EmailConnectionConfig config = extractConfig(sourceDescriptor);

        List<Document> documents = new ArrayList<>();

        try (Store store = connectionFactory.connect(config)) {
            for (String folderName : config.getEffectiveFolders()) {
                try {
                    List<Document> folderDocs = loadFolder(store, folderName, config);
                    documents.addAll(folderDocs);

                    if (config.getMessageLimit() > 0 && documents.size() >= config.getMessageLimit()) {
                        documents = documents.subList(0, config.getMessageLimit());
                        break;
                    }
                } catch (FolderNotFoundException e) {
                    logger.warn("Folder not found: {}. Skipping.", folderName);
                }
            }
        }

        logger.info("Loaded {} emails from {}", documents.size(), config.getHost());
        return documents;
    }

    /**
     * Extracts EmailConnectionConfig from the DocumentSourceDescriptor metadata.
     */
    private EmailConnectionConfig extractConfig(DocumentSourceDescriptor descriptor) {
        Map<String, Object> metadata = descriptor.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        EmailConnectionConfig.EmailConnectionConfigBuilder builder = EmailConnectionConfig.builder();

        // Protocol
        if (descriptor.getType() == DocumentSourceDescriptor.SourceType.POP3) {
            builder.protocol(EmailConnectionConfig.Protocol.POP3);
        } else {
            builder.protocol(EmailConnectionConfig.Protocol.IMAP);
        }

        // Connection settings
        if (metadata.containsKey("host")) {
            builder.host((String) metadata.get("host"));
        }
        if (metadata.containsKey("port")) {
            builder.port(((Number) metadata.get("port")).intValue());
        }
        if (metadata.containsKey("security")) {
            builder.security(EmailConnectionConfig.Security.valueOf((String) metadata.get("security")));
        }

        // Authentication
        if (metadata.containsKey("authMode")) {
            builder.authMode(EmailConnectionConfig.AuthMode.valueOf((String) metadata.get("authMode")));
        }
        if (metadata.containsKey("username") || metadata.containsKey("email")) {
            builder.username((String) metadata.getOrDefault("username", metadata.get("email")));
        }
        if (metadata.containsKey("password")) {
            builder.password((String) metadata.get("password"));
        }
        if (metadata.containsKey("accessToken")) {
            builder.accessToken((String) metadata.get("accessToken"));
        }

        // Filters
        if (metadata.containsKey("folders")) {
            Object foldersObj = metadata.get("folders");
            if (foldersObj instanceof List) {
                builder.folders((List<String>) foldersObj);
            } else if (foldersObj instanceof String) {
                builder.folders(List.of((String) foldersObj));
            }
        }
        if (metadata.containsKey("startDate")) {
            builder.startDate(parseDate(metadata.get("startDate")));
        }
        if (metadata.containsKey("endDate")) {
            builder.endDate(parseDate(metadata.get("endDate")));
        }
        if (metadata.containsKey("messageLimit")) {
            builder.messageLimit(((Number) metadata.get("messageLimit")).intValue());
        }

        // Options
        if (metadata.containsKey("includeAttachments")) {
            builder.includeAttachments((Boolean) metadata.get("includeAttachments"));
        }
        if (metadata.containsKey("includeHtml") || metadata.containsKey("includeHtmlBody")) {
            builder.includeHtmlBody((Boolean) metadata.getOrDefault("includeHtml", metadata.get("includeHtmlBody")));
        }

        return builder.build();
    }

    private LocalDateTime parseDate(Object dateObj) {
        if (dateObj instanceof LocalDateTime) {
            return (LocalDateTime) dateObj;
        } else if (dateObj instanceof String) {
            return LocalDateTime.parse((String) dateObj);
        }
        return null;
    }

    /**
     * Loads all messages from a specific folder.
     */
    private List<Document> loadFolder(Store store, String folderName, EmailConnectionConfig config)
            throws MessagingException, IOException {

        List<Document> documents = new ArrayList<>();
        Folder folder = store.getFolder(folderName);

        try {
            folder.open(Folder.READ_ONLY);

            Message[] messages;

            // Apply date filters if configured
            SearchTerm searchTerm = buildSearchTerm(config);
            if (searchTerm != null) {
                messages = folder.search(searchTerm);
            } else {
                messages = folder.getMessages();
            }

            logger.info("Found {} messages in folder: {}", messages.length, folderName);

            // Limit messages if configured
            int limit = config.getMessageLimit() > 0
                    ? Math.min(config.getMessageLimit(), messages.length)
                    : messages.length;

            // Process messages
            for (int i = 0; i < limit; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Email loading interrupted");
                    break;
                }

                try {
                    Document doc = convertMessageToDocument(messages[i], folderName, config);
                    if (doc != null) {
                        documents.add(doc);

                        // Process attachments if enabled
                        if (config.isIncludeAttachments()) {
                            List<Document> attachmentDocs = extractAttachments(messages[i], doc);
                            documents.addAll(attachmentDocs);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process message {}: {}", i, e.getMessage());
                }

                // Log progress
                if ((i + 1) % 100 == 0) {
                    logger.info("Processed {}/{} messages from {}", i + 1, limit, folderName);
                }
            }
        } finally {
            if (folder.isOpen()) {
                folder.close(false);
            }
        }

        return documents;
    }

    /**
     * Builds a search term for date filtering.
     */
    private SearchTerm buildSearchTerm(EmailConnectionConfig config) {
        List<SearchTerm> terms = new ArrayList<>();

        if (config.getStartDate() != null) {
            Date startDate = Date.from(config.getStartDate().atZone(ZoneId.systemDefault()).toInstant());
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, startDate));
        }

        if (config.getEndDate() != null) {
            Date endDate = Date.from(config.getEndDate().atZone(ZoneId.systemDefault()).toInstant());
            terms.add(new ReceivedDateTerm(ComparisonTerm.LE, endDate));
        }

        if (terms.isEmpty()) {
            return null;
        } else if (terms.size() == 1) {
            return terms.get(0);
        } else {
            return new AndTerm(terms.toArray(new SearchTerm[0]));
        }
    }

    /**
     * Converts a JavaMail Message to a Spring AI Document.
     */
    private Document convertMessageToDocument(Message message, String folderName, EmailConnectionConfig config)
            throws MessagingException, IOException {

        StringBuilder content = new StringBuilder();
        Map<String, Object> metadata = new HashMap<>();

        // Extract subject
        String subject = message.getSubject();
        if (subject != null) {
            content.append("Subject: ").append(subject).append("\n");
            metadata.put("email.subject", subject);
        }

        // Extract from address
        Address[] fromAddresses = message.getFrom();
        if (fromAddresses != null && fromAddresses.length > 0) {
            String from = formatAddress(fromAddresses[0]);
            content.append("From: ").append(from).append("\n");
            metadata.put("email.from", from);

            if (fromAddresses[0] instanceof InternetAddress) {
                String personal = ((InternetAddress) fromAddresses[0]).getPersonal();
                if (personal != null) {
                    metadata.put("email.fromName", personal);
                }
            }
        }

        // Extract recipients
        Address[] toAddresses = message.getRecipients(Message.RecipientType.TO);
        if (toAddresses != null && toAddresses.length > 0) {
            List<String> toList = formatAddresses(toAddresses);
            content.append("To: ").append(String.join(", ", toList)).append("\n");
            metadata.put("email.to", toList);
        }

        Address[] ccAddresses = message.getRecipients(Message.RecipientType.CC);
        if (ccAddresses != null && ccAddresses.length > 0) {
            List<String> ccList = formatAddresses(ccAddresses);
            content.append("Cc: ").append(String.join(", ", ccList)).append("\n");
            metadata.put("email.cc", ccList);
        }

        Address[] bccAddresses = message.getRecipients(Message.RecipientType.BCC);
        if (bccAddresses != null && bccAddresses.length > 0) {
            List<String> bccList = formatAddresses(bccAddresses);
            metadata.put("email.bcc", bccList);
        }

        // Extract dates
        Date sentDate = message.getSentDate();
        if (sentDate != null) {
            content.append("Date: ").append(sentDate).append("\n");
            metadata.put("email.date", Instant.ofEpochMilli(sentDate.getTime()).toString());
        }

        Date receivedDate = message.getReceivedDate();
        if (receivedDate != null) {
            metadata.put("email.receivedDate", Instant.ofEpochMilli(receivedDate.getTime()).toString());
        }

        // Extract Message-ID for threading
        if (message instanceof MimeMessage) {
            MimeMessage mimeMessage = (MimeMessage) message;

            String messageId = mimeMessage.getMessageID();
            if (messageId != null) {
                metadata.put("email.messageId", messageId);
            }

            // Threading headers
            String[] inReplyTo = mimeMessage.getHeader("In-Reply-To");
            if (inReplyTo != null && inReplyTo.length > 0) {
                metadata.put("email.inReplyTo", inReplyTo[0]);
            }

            String[] references = mimeMessage.getHeader("References");
            if (references != null && references.length > 0) {
                // References header can contain multiple message IDs
                String refStr = String.join(" ", references);
                List<String> refList = Arrays.asList(refStr.split("\\s+"));
                metadata.put("email.references", refList);
            }

            // Conversation ID (Microsoft Exchange specific)
            String[] conversationId = mimeMessage.getHeader("Thread-Index");
            if (conversationId != null && conversationId.length > 0) {
                metadata.put("email.conversationId", conversationId[0]);
            }
        }

        content.append("\n");

        // Extract body text
        String bodyText = extractTextContent(message, config.isIncludeHtmlBody());
        if (bodyText != null && !bodyText.trim().isEmpty()) {
            content.append(bodyText);
        }

        // Add source metadata
        metadata.put("email.folder", folderName);
        metadata.put("source_type", "EMAIL");
        metadata.put("loader", getName());
        metadata.put("source", String.format("%s://%s/%s",
                config.getProtocol().name().toLowerCase(),
                config.getHost(),
                folderName));

        return new Document(content.toString(), metadata);
    }

    /**
     * Extracts text content from an email message.
     * Handles multipart messages and optional HTML conversion.
     */
    private String extractTextContent(Message message, boolean processHtml) throws MessagingException, IOException {
        Object content = message.getContent();

        if (content instanceof String) {
            String text = (String) content;
            String contentType = message.getContentType();
            if (contentType != null && contentType.toLowerCase().contains("text/html") && processHtml) {
                return convertHtmlToText(text);
            }
            return text;
        } else if (content instanceof MimeMultipart) {
            return extractTextFromMultipart((MimeMultipart) content, processHtml);
        }

        return null;
    }

    /**
     * Extracts text from a multipart message.
     * Prefers plain text over HTML, but will convert HTML if no plain text is available.
     */
    private String extractTextFromMultipart(MimeMultipart multipart, boolean processHtml)
            throws MessagingException, IOException {

        String plainText = null;
        String htmlText = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            String contentType = bodyPart.getContentType().toLowerCase();

            if (contentType.contains("text/plain")) {
                plainText = (String) bodyPart.getContent();
            } else if (contentType.contains("text/html")) {
                htmlText = (String) bodyPart.getContent();
            } else if (contentType.contains("multipart")) {
                // Recursively handle nested multipart
                String nestedText = extractTextFromMultipart((MimeMultipart) bodyPart.getContent(), processHtml);
                if (nestedText != null && !nestedText.trim().isEmpty()) {
                    if (plainText == null) {
                        plainText = nestedText;
                    }
                }
            }
        }

        // Prefer plain text, fall back to HTML conversion
        if (plainText != null && !plainText.trim().isEmpty()) {
            return plainText;
        } else if (htmlText != null && processHtml) {
            return convertHtmlToText(htmlText);
        } else if (htmlText != null) {
            // Even without processHtml, strip HTML tags for basic text
            return Jsoup.parse(htmlText).text();
        }

        return null;
    }

    /**
     * Converts HTML content to plain text using JSoup.
     */
    private String convertHtmlToText(String html) {
        if (html == null) {
            return null;
        }

        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        // Remove script and style elements
        doc.select("script, style, head, nav, footer").remove();

        // Convert block elements to newlines
        doc.select("br").before("\\n");
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, tr").before("\\n\\n");

        String text = doc.text().replace("\\n", "\n");

        // Clean up excessive whitespace
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[ \\t]+", " ");

        return text.trim();
    }

    /**
     * Extracts attachments from an email message as separate documents.
     */
    private List<Document> extractAttachments(Message message, Document parentDoc)
            throws MessagingException, IOException {

        List<Document> attachmentDocs = new ArrayList<>();

        Object content = message.getContent();
        if (!(content instanceof MimeMultipart)) {
            return attachmentDocs;
        }

        String parentMessageId = (String) parentDoc.getMetadata().get("email.messageId");
        MimeMultipart multipart = (MimeMultipart) content;

        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            String disposition = bodyPart.getDisposition();

            if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || Part.INLINE.equalsIgnoreCase(disposition)) {
                String filename = bodyPart.getFileName();
                String contentType = bodyPart.getContentType();

                // For now, just note the attachment - full extraction would use other loaders
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("email.isAttachment", true);
                metadata.put("email.parentMessageId", parentMessageId);
                metadata.put("email.attachmentName", filename);
                metadata.put("email.attachmentMimeType", contentType);
                metadata.put("email.attachmentSize", bodyPart.getSize());
                metadata.put("source_type", "EMAIL_ATTACHMENT");
                metadata.put("loader", getName());

                // Try to extract text content from text-based attachments
                if (contentType.toLowerCase().contains("text/")) {
                    Object attachmentContent = bodyPart.getContent();
                    if (attachmentContent instanceof String) {
                        Document attachmentDoc = new Document(
                                "Attachment: " + filename + "\n\n" + attachmentContent,
                                metadata
                        );
                        attachmentDocs.add(attachmentDoc);
                    }
                } else {
                    // For binary attachments, create a placeholder document
                    Document attachmentDoc = new Document(
                            "[Attachment: " + filename + " (" + contentType + ")]",
                            metadata
                    );
                    attachmentDocs.add(attachmentDoc);
                }
            }
        }

        return attachmentDocs;
    }

    /**
     * Formats an email address for display.
     */
    private String formatAddress(Address address) {
        if (address instanceof InternetAddress) {
            InternetAddress ia = (InternetAddress) address;
            String personal = ia.getPersonal();
            if (personal != null && !personal.isEmpty()) {
                return personal + " <" + ia.getAddress() + ">";
            }
            return ia.getAddress();
        }
        return address.toString();
    }

    /**
     * Formats multiple addresses for display.
     */
    private List<String> formatAddresses(Address[] addresses) {
        List<String> result = new ArrayList<>();
        for (Address address : addresses) {
            result.add(formatAddress(address));
        }
        return result;
    }
}
