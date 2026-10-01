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
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Document loader for IMAP and POP3 email servers.
 * Fetches emails from mail servers and converts them to Spring AI Document objects
 * with rich metadata including threading information.
 */
@Component
public class ImapPopDocumentLoader implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(ImapPopDocumentLoader.class);

    /** Per-attachment sanitized-file-name cap, matching the shared attachmentDirectory contract. */
    private static final int MAX_ATTACHMENT_FILENAME_LENGTH = 120;

    /** Extension fallback when an attachment's original name has none. */
    private static final Map<String, String> ATTACHMENT_EXTENSION_BY_CONTENT_TYPE = Map.ofEntries(
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/jpg", ".jpg"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/svg+xml", ".svg"),
            Map.entry("application/pdf", ".pdf"),
            Map.entry("text/plain", ".txt"),
            Map.entry("text/csv", ".csv"),
            Map.entry("text/html", ".html"),
            Map.entry("application/json", ".json"),
            Map.entry("application/zip", ".zip"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("audio/mpeg", ".mp3"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"));

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
        int totalMessages = 0;

        try (Store store = connectionFactory.connect(config)) {
            for (String folderName : config.getEffectiveFolders()) {
                // C2: the cap is a TOTAL across folders, counted in messages (not attachment
                // sub-documents), and enforced while fetching — never by loading everything and
                // trimming afterward.
                int remaining = config.getMessageLimit() > 0
                        ? config.getMessageLimit() - totalMessages
                        : Integer.MAX_VALUE;
                if (remaining <= 0) {
                    break;
                }

                try {
                    FolderLoadResult result = loadFolder(store, folderName, config, remaining);
                    documents.addAll(result.documents());
                    totalMessages += result.messageCount();
                } catch (FolderNotFoundException e) {
                    logger.warn("Folder not found: {}. Skipping.", folderName);
                }
            }
        }

        logger.info("Loaded {} emails from {}", documents.size(), config.getHost());
        return documents;
    }

    /** Result of loading one folder: the documents produced and how many messages they cost. */
    private record FolderLoadResult(List<Document> documents, int messageCount) {}

    /**
     * Extracts EmailConnectionConfig from the DocumentSourceDescriptor metadata.
     * Every numeric/boolean key is parsed leniently (Number or numeric/boolean String), and
     * enum-valued keys (security, authMode) are matched case-insensitively with a descriptive
     * error listing the valid values when they don't match.
     */
    private EmailConnectionConfig extractConfig(DocumentSourceDescriptor descriptor) {
        Map<String, Object> metadata = descriptor.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        EmailConnectionConfig.EmailConnectionConfigBuilder builder = EmailConnectionConfig.builder();

        EmailConnectionConfig.Protocol protocol = descriptor.getType() == DocumentSourceDescriptor.SourceType.POP3
                ? EmailConnectionConfig.Protocol.POP3
                : EmailConnectionConfig.Protocol.IMAP;
        builder.protocol(protocol);

        // Connection settings
        if (metadata.containsKey("host")) {
            builder.host((String) metadata.get("host"));
        }
        // Port: a P2 bug had POP3 always resolving to the IMAP default (993) because the raw
        // field's builder-default was protocol-unaware. When the descriptor doesn't specify a
        // port, explicitly set 0 ("unset") so EmailConnectionConfig#getEffectivePort() computes
        // the correct protocol+security-aware default (995/110 for POP3, 993/143 for IMAP)
        // instead of silently keeping the class's IMAP-shaped default.
        Integer port = parseLenientInt(metadata.get("port"), "port");
        builder.port(port != null ? port : 0);
        if (metadata.containsKey("security")) {
            builder.security(parseEnum(EmailConnectionConfig.Security.class,
                    String.valueOf(metadata.get("security")), "security"));
        }

        // Authentication
        if (metadata.containsKey("authMode")) {
            builder.authMode(parseEnum(EmailConnectionConfig.AuthMode.class,
                    String.valueOf(metadata.get("authMode")), "authMode"));
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
        // C3: "since" is the shared crawl-cap-window contract key; accept ISO instant/offset
        // strings (and Instant objects passed programmatically).
        if (metadata.containsKey("since")) {
            builder.since(parseInstant(metadata.get("since")));
        }

        // C2: "maxMessages" is the shared crawl-cap contract key; "messageLimit" is this loader's
        // own historical name and wins when both are present. A value <= 0 means "no explicit
        // cap" — fall back to the loader's own default rather than treating 0/negative as literal.
        Integer messageLimit = parseLenientInt(metadata.get("messageLimit"), "messageLimit");
        if (messageLimit == null) {
            messageLimit = parseLenientInt(metadata.get("maxMessages"), "maxMessages");
        }
        if (messageLimit != null) {
            builder.messageLimit(messageLimit > 0 ? messageLimit : EmailConnectionConfig.DEFAULT_MESSAGE_LIMIT);
        }

        // Options
        Boolean includeAttachments = parseLenientBoolean(metadata.get("includeAttachments"), "includeAttachments");
        if (includeAttachments != null) {
            builder.includeAttachments(includeAttachments);
        }
        Object htmlFlag = metadata.containsKey("includeHtml") ? metadata.get("includeHtml") : metadata.get("includeHtmlBody");
        Boolean includeHtmlBody = parseLenientBoolean(htmlFlag, "includeHtmlBody");
        if (includeHtmlBody != null) {
            builder.includeHtmlBody(includeHtmlBody);
        }

        // C4: attachmentDirectory switches attachment handling to file-writing mode.
        Object attachmentDirectory = metadata.get("attachmentDirectory");
        if (attachmentDirectory instanceof String dir && !dir.isBlank()) {
            builder.attachmentDirectory(dir);
        }
        Long maxAttachmentBytes = parseLenientLong(metadata.get("maxAttachmentBytes"), "maxAttachmentBytes");
        if (maxAttachmentBytes != null && maxAttachmentBytes > 0) {
            builder.maxAttachmentBytes(maxAttachmentBytes);
        }

        return builder.build();
    }

    /**
     * Parses a date value in any of the forms the crawl registry may send: an ISO local
     * date-time ("2025-01-01T00:00:00"), a date-only value ("2026-09-01"), an ISO instant
     * ("2026-09-28T10:15:30Z"), an ISO offset date-time ("2026-09-28T19:15:30+09:00"), or a
     * passthrough {@link LocalDateTime}/{@link Instant} object.
     */
    private LocalDateTime parseDate(Object dateObj) {
        if (dateObj == null) {
            return null;
        }
        if (dateObj instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (dateObj instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        if (!(dateObj instanceof String raw) || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // not an ISO local date-time; try the next form
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // not a date-only value; try the next form
        }
        try {
            return OffsetDateTime.parse(trimmed).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // not an offset date-time; try the last form
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(trimmed), ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            logger.warn("Unable to parse date value '{}': {}", trimmed, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the shared "since" contract value (ISO-8601 instant or offset string) into an
     * {@link Instant}, falling back to the same lenient forms {@link #parseDate(Object)} accepts
     * (interpreted in the system default zone) for robustness.
     */
    private Instant parseInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // not a plain instant; try offset form
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall back to the general date parser below
        }
        LocalDateTime fallback = parseDate(trimmed);
        if (fallback != null) {
            return fallback.atZone(ZoneId.systemDefault()).toInstant();
        }
        logger.warn("Unable to parse 'since' value '{}'", trimmed);
        return null;
    }

    /**
     * Leniently parses an int from a {@link Number} or a numeric {@link String}. Returns
     * {@code null} when the value is absent so callers can distinguish "not provided" from an
     * explicit value.
     */
    private Integer parseLenientInt(Object value, String fieldName) {
        Long parsed = parseLenientLong(value, fieldName);
        return parsed != null ? parsed.intValue() : null;
    }

    private Long parseLenientLong(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                logger.warn("Ignoring non-numeric value for '{}': '{}'", fieldName, s);
            }
        }
        return null;
    }

    /**
     * Leniently parses a boolean from a {@link Boolean} or a String such as "true"/"false"
     * (case-insensitive). Returns {@code null} when the value is absent.
     */
    private Boolean parseLenientBoolean(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return Boolean.parseBoolean(trimmed);
            }
            logger.warn("Ignoring non-boolean value for '{}': '{}'", fieldName, s);
        }
        return null;
    }

    /**
     * Matches {@code raw} against {@code enumType}'s constants case-insensitively (also
     * tolerating spaces/hyphens in place of underscores). Throws a descriptive
     * {@link IllegalArgumentException} listing the valid values when nothing matches.
     */
    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String raw, String fieldName) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException e) {
            String validValues = String.join(", ",
                    Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toArray(String[]::new));
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + " '" + raw + "'. Valid values: " + validValues, e);
        }
    }

    /**
     * Loads up to {@code limitForFolder} of the newest messages from a specific folder.
     * Candidates are always sourced via {@link Folder#getMessages()} or
     * {@link Folder#search(SearchTerm)} (never a ranged/count-based fetch — see
     * {@link #buildSearchTerm}), then walked newest-first in memory so the cap is enforced
     * WHILE converting: messages beyond the cap are never converted to Documents (C2).
     */
    private FolderLoadResult loadFolder(Store store, String folderName, EmailConnectionConfig config,
                                         int limitForFolder) throws MessagingException, IOException {

        List<Document> documents = new ArrayList<>();
        Folder folder = store.getFolder(folderName);
        int messagesConverted = 0;

        try {
            folder.open(Folder.READ_ONLY);

            SearchTerm searchTerm = buildSearchTerm(config);
            Message[] candidates = searchTerm != null ? folder.search(searchTerm) : folder.getMessages();

            logger.info("Found {} candidate messages in folder: {}", candidates.length, folderName);

            // C2: newest first. Folder#getMessages()/#search() return ascending message-number
            // (arrival) order, so reverse iteration visits the newest message first; the loop
            // stops as soon as the cap is reached rather than converting everything and trimming.
            for (int i = candidates.length - 1; i >= 0 && messagesConverted < limitForFolder; i--) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Email loading interrupted");
                    break;
                }

                Message message = candidates[i];
                try {
                    // IMAP's ReceivedDateTerm search is only day-granular, and POP3 has no
                    // server-side search at all — apply the exact client-side bound here.
                    if (!passesDateWindow(message, config)) {
                        continue;
                    }

                    ConvertedMessage converted = convertMessageToDocument(message, folder, folderName, config);
                    documents.add(converted.document());
                    documents.addAll(converted.attachmentDocuments());
                    messagesConverted++;

                    if (messagesConverted % 100 == 0) {
                        logger.info("Processed {} messages from {}", messagesConverted, folderName);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process message {}: {}", message.getMessageNumber(), e.getMessage());
                }
            }
        } finally {
            if (folder.isOpen()) {
                folder.close(false);
            }
        }

        return new FolderLoadResult(documents, messagesConverted);
    }

    /**
     * Builds an IMAP search term for date filtering. Returns {@code null} for POP3 (which has
     * no server-side search — filtering happens entirely client-side in
     * {@link #passesDateWindow}) and when neither bound is configured. The search only needs to
     * narrow the candidate set; day-level granularity is fine here because the exact bound is
     * still enforced per-message afterward.
     */
    private SearchTerm buildSearchTerm(EmailConnectionConfig config) {
        if (config.getProtocol() != EmailConnectionConfig.Protocol.IMAP) {
            return null;
        }

        List<SearchTerm> terms = new ArrayList<>();

        Instant since = config.getEffectiveSinceInstant();
        if (since != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
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
     * Exact client-side date-window check applied to every candidate regardless of protocol
     * (C3/A4 for IMAP on top of the day-granular search; A3 for POP3, which has no server-side
     * search at all). A message with no usable date passes the lower bound — this is what keeps
     * undated POP3 messages instead of the old behavior of silently dropping every POP3 message
     * once a date filter was configured.
     */
    private boolean passesDateWindow(Message message, EmailConnectionConfig config) {
        Instant since = config.getEffectiveSinceInstant();
        Instant end = config.getEndDate() != null
                ? config.getEndDate().atZone(ZoneId.systemDefault()).toInstant()
                : null;
        if (since == null && end == null) {
            return true;
        }

        Instant reference = referenceDate(message);
        if (reference == null) {
            return true;
        }
        if (since != null && reference.isBefore(since)) {
            return false;
        }
        return end == null || !reference.isAfter(end);
    }

    /**
     * The date used for window filtering: sent date first — present on both IMAP and POP3
     * messages, unlike the received date, which JavaMail's POP3 provider typically cannot
     * supply — falling back to received date when sent date is absent.
     */
    private Instant referenceDate(Message message) {
        try {
            Date sent = message.getSentDate();
            if (sent != null) {
                return sent.toInstant();
            }
            Date received = message.getReceivedDate();
            if (received != null) {
                return received.toInstant();
            }
        } catch (MessagingException e) {
            logger.warn("Failed to read message date: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Converts a JavaMail Message to a Spring AI Document plus any attachment Documents
     * (server-ingestion mode only; empty when attachments are disabled or attachmentDirectory
     * file-write mode is active — see {@link #processAttachments}).
     */
    private ConvertedMessage convertMessageToDocument(Message message, Folder folder, String folderName,
                                                        EmailConnectionConfig config)
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

        // Single unified MIME-tree walk drives body selection AND attachment detection so a
        // part can never be picked as both — the root cause of the old "a text/plain attachment
        // can overwrite the body" bug, which classified parts by content-type substring alone
        // with no Content-Disposition/filename check.
        MimeWalkResult walk = new MimeWalkResult();
        walkParts(message, walk);

        // Preserve raw HTML for graph extraction while presenting readable body text.
        if (config.isIncludeHtmlBody() && walk.htmlText != null && !walk.htmlText.isBlank()) {
            metadata.put("email.htmlBody", walk.htmlText);
        }
        String bodyText;
        if (walk.plainText != null && !walk.plainText.trim().isEmpty()) {
            bodyText = walk.plainText;
        } else if (walk.htmlText != null) {
            bodyText = config.isIncludeHtmlBody() ? convertHtmlToText(walk.htmlText) : Jsoup.parse(walk.htmlText).text();
        } else {
            bodyText = null;
        }
        if (bodyText != null && !bodyText.trim().isEmpty()) {
            content.append(bodyText);
        }
        if (walk.forwarded.length() > 0) {
            content.append(walk.forwarded);
        }

        // Add source metadata using the canonical crawl schema. Each message gets its own
        // source_path (UID, else Message-ID, else message number) so a folder full of messages
        // materializes into one file per message instead of colliding on the folder-level path.
        metadata.put("email.folder", folderName);
        metadata.put("source_type", "EMAIL_INBOX");
        metadata.put("loader", getName());
        String encodedId = URLEncoder.encode(messageIdentifier(folder, message), StandardCharsets.UTF_8);
        String sourcePath = String.format("%s://%s/%s/%s",
                config.getProtocol().name().toLowerCase(),
                config.getHost(),
                folderName,
                encodedId);
        metadata.put("source", sourcePath);
        metadata.put("source_path", sourcePath);

        List<Document> attachmentDocuments = List.of();
        if (config.isIncludeAttachments() && !walk.attachments.isEmpty()) {
            attachmentDocuments = processAttachments(walk.attachments, sourcePath, metadata, config);
        }

        return new ConvertedMessage(new Document(content.toString(), metadata), attachmentDocuments);
    }

    /** Result of converting one message: the primary Document and any server-mode attachment Documents. */
    private record ConvertedMessage(Document document, List<Document> attachmentDocuments) {}

    /**
     * Accumulates the result of one recursive MIME-tree walk: the inline body text/html and the
     * flat list of attachment parts encountered — including ones nested inside a forwarded
     * message/rfc822 part — plus any rendered "Forwarded message" text sections.
     */
    private static final class MimeWalkResult {
        String plainText;
        String htmlText;
        final List<Part> attachments = new ArrayList<>();
        final StringBuilder forwarded = new StringBuilder();
    }

    /**
     * Recursively classifies every leaf of a part's MIME tree as the inline body (first
     * text/plain part; first text/html part as the fallback candidate), an attachment, or a
     * forwarded message, per A2's rule: "a part with Content-Disposition attachment or a
     * filename is an attachment, never the body." message/rfc822 parts are always rendered as a
     * forwarded-message section (never treated as an attachment themselves, even if marked
     * Content-Disposition attachment), since their content is quoted inline instead.
     */
    private void walkParts(Part part, MimeWalkResult result) throws MessagingException, IOException {
        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            if (content instanceof Message forwarded) {
                appendForwarded(forwarded, result);
            }
            return;
        }

        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    walkParts(multipart.getBodyPart(i), result);
                }
            }
            return;
        }

        if (isAttachmentPart(part)) {
            result.attachments.add(part);
            return;
        }

        if (part.isMimeType("text/plain")) {
            if (result.plainText == null && part.getContent() instanceof String text) {
                result.plainText = text;
            }
        } else if (part.isMimeType("text/html")) {
            if (result.htmlText == null && part.getContent() instanceof String text) {
                result.htmlText = text;
            }
        }
    }

    /**
     * A part is an attachment when it's explicitly marked Content-Disposition: attachment, or it
     * carries a filename at all (A2's exact rule) — this is deliberately broader than the old
     * "disposition must be attachment or inline" check so an inline part with a filename (e.g. a
     * named inline image) is still recognized as an attachment rather than silently dropped.
     */
    private boolean isAttachmentPart(Part part) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return true;
        }
        String fileName = part.getFileName();
        return fileName != null && !fileName.isBlank();
    }

    /**
     * Renders a forwarded message/rfc822 part as a quoted "Forwarded message" section
     * (From/Date/Subject/To header lines plus body text) appended after the outer message's own
     * body, and recursively walks the forwarded message's MIME tree so ITS attachments join the
     * outer message's flat attachment list — forwarded attachments follow the same C4 handling
     * as top-level ones.
     */
    private void appendForwarded(Message forwarded, MimeWalkResult result) throws MessagingException, IOException {
        MimeWalkResult nested = new MimeWalkResult();
        walkParts(forwarded, nested);

        StringBuilder section = new StringBuilder();
        section.append("\n\n---------- Forwarded message ----------\n");
        Address[] from = forwarded.getFrom();
        if (from != null && from.length > 0) {
            section.append("From: ").append(formatAddress(from[0])).append("\n");
        }
        Date sentDate = forwarded.getSentDate();
        if (sentDate != null) {
            section.append("Date: ").append(sentDate).append("\n");
        }
        String subject = forwarded.getSubject();
        if (subject != null) {
            section.append("Subject: ").append(subject).append("\n");
        }
        Address[] to = forwarded.getRecipients(Message.RecipientType.TO);
        if (to != null && to.length > 0) {
            section.append("To: ").append(String.join(", ", formatAddresses(to))).append("\n");
        }
        section.append("\n");
        if (nested.plainText != null && !nested.plainText.isBlank()) {
            section.append(nested.plainText);
        } else if (nested.htmlText != null && !nested.htmlText.isBlank()) {
            section.append(Jsoup.parse(nested.htmlText).text());
        }

        result.forwarded.append(section);
        result.attachments.addAll(nested.attachments);
    }

    /**
     * Derives a stable per-message identifier for source_path construction: the IMAP UID when
     * the folder supports {@link UIDFolder}, otherwise the Message-ID header, otherwise the
     * 1-based message number. The caller must URL-encode the result since Message-IDs contain
     * characters such as {@code <}, {@code >}, and {@code @}.
     */
    private String messageIdentifier(Folder folder, Message message) {
        if (folder instanceof UIDFolder uidFolder) {
            try {
                return Long.toString(uidFolder.getUID(message));
            } catch (MessagingException e) {
                logger.warn("Failed to read IMAP UID, falling back to Message-ID: {}", e.getMessage());
            }
        }
        if (message instanceof MimeMessage mimeMessage) {
            try {
                String messageId = mimeMessage.getMessageID();
                if (messageId != null && !messageId.isBlank()) {
                    return messageId;
                }
            } catch (MessagingException e) {
                logger.warn("Failed to read Message-ID, falling back to message number: {}", e.getMessage());
            }
        }
        return Integer.toString(message.getMessageNumber());
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
     * Dispatches attachment handling per the shared "attachmentDirectory" contract (C4): when
     * the config carries an attachmentDirectory, attachment bytes are written to
     * {@code <attachmentDirectory>/<messageKey>/<fileName>} and {@code parentMetadata} gets an
     * "attachments" list describing what was saved/skipped, with NO separate Documents produced.
     * Otherwise (server-ingestion path), attachments continue to be emitted as separate
     * Documents exactly as before, but now correctly tagged with source/source_path/
     * parent_source_path.
     */
    private List<Document> processAttachments(List<Part> attachments, String parentSourcePath,
                                               Map<String, Object> parentMetadata, EmailConnectionConfig config)
            throws MessagingException, IOException {
        String attachmentDirectory = config.getAttachmentDirectory();
        if (attachmentDirectory != null && !attachmentDirectory.isBlank()) {
            writeAttachmentFiles(attachments, parentSourcePath, parentMetadata, config);
            return List.of();
        }
        return buildAttachmentDocuments(attachments, parentSourcePath, parentMetadata);
    }

    /**
     * Server-ingestion mode: emits one Document per attachment, same placeholder/text-inline
     * behavior as before, but now each carries "source"/"source_path" (parent source_path plus
     * "#attachment/<n>") and "parent_source_path" (the A2 bug: they previously had neither).
     */
    private List<Document> buildAttachmentDocuments(List<Part> attachments, String parentSourcePath,
                                                      Map<String, Object> parentMetadata)
            throws MessagingException, IOException {
        List<Document> docs = new ArrayList<>();
        String parentMessageId = (String) parentMetadata.get("email.messageId");

        int index = 0;
        for (Part part : attachments) {
            index++;
            String filename = part.getFileName();
            String contentType = part.getContentType();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("email.isAttachment", true);
            metadata.put("email.parentMessageId", parentMessageId);
            metadata.put("email.attachmentName", filename);
            metadata.put("email.attachmentMimeType", contentType);
            metadata.put("email.attachmentSize", part.getSize());
            metadata.put("source_type", "EMAIL_ATTACHMENT");
            metadata.put("loader", getName());
            String attachmentSourcePath = parentSourcePath + "#attachment/" + index;
            metadata.put("source", attachmentSourcePath);
            metadata.put("source_path", attachmentSourcePath);
            metadata.put("parent_source_path", parentSourcePath);

            if (simpleContentType(contentType).startsWith("text/")) {
                Object attachmentContent = part.getContent();
                if (attachmentContent instanceof String text) {
                    docs.add(new Document("Attachment: " + filename + "\n\n" + text, metadata));
                    continue;
                }
            }
            docs.add(new Document("[Attachment: " + filename + " (" + contentType + ")]", metadata));
        }

        return docs;
    }

    /**
     * C4 file-write mode: saves each attachment's original bytes under the configured
     * attachmentDirectory, skipping inline images referenced by Content-ID (signature logos —
     * these are never saved or listed), and records every other attachment's outcome (saved or
     * skipped) in {@code parentMetadata}'s "attachments" list.
     */
    private void writeAttachmentFiles(List<Part> attachments, String parentSourcePath,
                                       Map<String, Object> parentMetadata, EmailConnectionConfig config)
            throws MessagingException {
        Path normalizedRoot = Paths.get(config.getAttachmentDirectory()).normalize();
        String messageKey = messageKeyFor(parentSourcePath);
        Set<String> usedFileNames = new HashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();

        for (Part part : attachments) {
            if (isInlineCidImage(part)) {
                continue;
            }
            entries.add(saveAttachmentFile(part, normalizedRoot, messageKey, config.getMaxAttachmentBytes(), usedFileNames));
        }

        parentMetadata.put("attachments", entries);
    }

    /**
     * Saves one attachment's original bytes under {@code normalizedRoot}, or records why it was
     * skipped (oversize, a resolved path escaping the directory, or an I/O failure). Mirrors
     * DiscordAttachmentStorage's attachmentDirectory conventions exactly (temp-file-then-move,
     * identical skip-reason phrasing) so every loader's "attachments" metadata looks the same.
     * Never throws — a per-attachment problem becomes a "skipped" entry so the rest of the
     * message's attachments still get a chance.
     */
    private Map<String, Object> saveAttachmentFile(Part part, Path normalizedRoot, String messageKey,
                                                     long maxAttachmentBytes, Set<String> usedFileNames) {
        Map<String, Object> entry = new LinkedHashMap<>();
        String originalName = readSafely(part::getFileName);
        String contentType = readSafely(part::getContentType);
        String fileName = sanitizeAttachmentFileName(originalName, contentType, usedFileNames);
        entry.put("fileName", fileName);
        if (contentType != null) {
            entry.put("contentType", simpleContentType(contentType));
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("email-attachment-", ".tmp");
        } catch (IOException e) {
            entry.put("skipped", "download failed: " + e.getMessage());
            return entry;
        }

        try {
            try (InputStream in = part.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            long size = Files.size(tempFile);
            entry.put("size", size);
            if (size > maxAttachmentBytes) {
                entry.put("skipped", "exceeds maxAttachmentBytes (" + maxAttachmentBytes + ")");
                return entry;
            }

            Path messageDir = normalizedRoot.resolve(messageKey).normalize();
            Path target = messageDir.resolve(fileName).normalize();
            if (!target.startsWith(normalizedRoot)) {
                logger.warn("Refusing to write email attachment '{}' outside attachmentDirectory: {}", fileName, target);
                entry.put("skipped", "resolved path escaped attachmentDirectory");
                return entry;
            }

            Files.createDirectories(messageDir);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            entry.put("path", messageKey + "/" + fileName);
            return entry;
        } catch (MessagingException | IOException e) {
            logger.warn("Failed to save email attachment '{}': {}", fileName, e.getMessage());
            entry.put("skipped", "save failed: " + e.getMessage());
            return entry;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best effort — the file was already moved away on the success path
            }
        }
    }

    /** A checked-exception-throwing supplier that resolves to null instead of propagating. */
    @FunctionalInterface
    private interface MailAttribute<T> {
        T get() throws MessagingException;
    }

    private <T> T readSafely(MailAttribute<T> supplier) {
        try {
            return supplier.get();
        } catch (MessagingException e) {
            return null;
        }
    }

    /**
     * True when a part is an inline image referenced by Content-ID — a signature logo the
     * message body embeds via a {@code cid:} URL, not a real user-added attachment — per the
     * audit's "skip ... inline images referenced by Content-ID (signature logos)".
     */
    private boolean isInlineCidImage(Part part) throws MessagingException {
        if (!part.isMimeType("image/*")) {
            return false;
        }
        String[] contentId = part.getHeader("Content-ID");
        return contentId != null && contentId.length > 0 && contentId[0] != null && !contentId[0].isBlank();
    }

    /**
     * Derives the stable per-message directory name for C4 attachment storage: the first 16 hex
     * characters of SHA-256(sourcePath), matching DiscordAttachmentStorage#messageKey exactly so
     * every loader's message directories look the same.
     */
    private String messageKeyFor(String sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourcePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Sanitizes an original attachment file name exactly like DiscordAttachmentStorage's
     * sanitizeFileName, so every loader's saved-attachment naming convention is identical:
     * strips path separators and control characters, strips leading dots, derives an extension
     * from the content type when the name is missing one, truncates to keep the stem+extension
     * within the shared length cap, and de-duplicates within the message as name-2.ext, etc.
     */
    private String sanitizeAttachmentFileName(String original, String contentType, Set<String> usedFileNames) {
        String base = original == null ? "" : original;
        StringBuilder cleaned = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                continue;
            }
            cleaned.append(c);
        }
        String name = cleaned.toString();

        int firstNonDot = 0;
        while (firstNonDot < name.length() && name.charAt(firstNonDot) == '.') {
            firstNonDot++;
        }
        name = name.substring(firstNonDot);
        if (name.isEmpty()) {
            name = "attachment";
        }

        String ext;
        String stem;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot);
            stem = name.substring(0, dot);
        } else {
            stem = name;
            String derivedExt = extensionForContentType(contentType);
            ext = derivedExt != null ? derivedExt : "";
        }

        if (stem.length() + ext.length() > MAX_ATTACHMENT_FILENAME_LENGTH) {
            int stemBudget = Math.max(1, MAX_ATTACHMENT_FILENAME_LENGTH - ext.length());
            stem = stem.substring(0, Math.min(stem.length(), stemBudget));
        }

        String candidate = stem + ext;
        if (usedFileNames.add(candidate)) {
            return candidate;
        }

        for (int suffix = 2; ; suffix++) {
            String suffixed = stem + "-" + suffix + ext;
            if (usedFileNames.add(suffixed)) {
                return suffixed;
            }
        }
    }

    private String extensionForContentType(String contentType) {
        return contentType == null ? null : ATTACHMENT_EXTENSION_BY_CONTENT_TYPE.get(simpleContentType(contentType));
    }

    /**
     * Strips MIME parameters (e.g. "; charset=us-ascii; name=notes.txt") from a Content-Type
     * header, returning just the type/subtype in lower case.
     */
    private String simpleContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        String type = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
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
