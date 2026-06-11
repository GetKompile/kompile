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

package ai.kompile.loader.email.inbox;

import org.apache.james.mime4j.dom.*;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.ContentDispositionField;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.stream.Field;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Stateless parser that converts Apache James MIME4J {@link Message} objects
 * into Spring AI {@link Document} instances with rich email metadata.
 *
 * <p>Handles multipart MIME messages, extracting plain text (preferred) or
 * converting HTML to text via JSoup. Attachments are optionally extracted
 * as separate documents.</p>
 */
public class Mime4jMessageParser {

    private static final Logger logger = LoggerFactory.getLogger(Mime4jMessageParser.class);

    private final boolean includeAttachments;
    private final boolean includeHtmlBody;
    // Per-message accumulator for inline CID images (reset per convertMessage call)
    private List<Map<String, String>> inlineImages;

    public Mime4jMessageParser() {
        this(false, true);
    }

    public Mime4jMessageParser(boolean includeAttachments, boolean includeHtmlBody) {
        this.includeAttachments = includeAttachments;
        this.includeHtmlBody = includeHtmlBody;
    }

    /**
     * Parses a raw email from an InputStream into one or more Documents.
     * The primary email becomes the first document; attachments (if enabled)
     * follow as separate documents.
     */
    public List<Document> parse(InputStream inputStream, String sourcePath) throws IOException {
        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        try {
            Message message = builder.parseMessage(inputStream);
            return convertMessage(message, sourcePath);
        } catch (Exception e) {
            logger.warn("Failed to parse MIME message from {}: {}", sourcePath, e.getMessage());
            Document errorDoc = new Document("[Error: Unable to parse email. " + e.getMessage() + "]");
            errorDoc.getMetadata().put(GraphConstants.META_SOURCE, sourcePath);
            errorDoc.getMetadata().put(GraphConstants.META_SOURCE_PATH, sourcePath);
            errorDoc.getMetadata().put("parseError", true);
            return List.of(errorDoc);
        }
    }

    /**
     * Converts a parsed mime4j Message into Document(s).
     */
    public List<Document> convertMessage(Message message, String sourcePath) {
        List<Document> results = new ArrayList<>();
        inlineImages = null; // Reset per-message accumulator

        StringBuilder content = new StringBuilder();
        Map<String, Object> metadata = new HashMap<>();

        // Extract headers into both content text and metadata
        extractHeaders(message, content, metadata);
        content.append("\n");

        // Extract body text (also populates inlineImages for multipart/related CID parts)
        BodyExtraction extraction = extractBody(message);
        if (extraction.plainText != null && !extraction.plainText.isBlank()) {
            content.append(extraction.plainText);
        } else if (extraction.htmlText != null && includeHtmlBody) {
            content.append(convertHtmlToText(extraction.htmlText));
        } else if (extraction.htmlText != null) {
            content.append(Jsoup.parse(extraction.htmlText).text());
        }

        // Preserve raw HTML in metadata for downstream processors
        if (extraction.htmlText != null) {
            metadata.put("email.htmlBody", extraction.htmlText);
        }

        // Extract tables from HTML body for graph indexing
        if (extraction.htmlText != null && extraction.htmlText.contains("<table")) {
            extractHtmlBodyTables(extraction.htmlText, sourcePath, metadata);
        }

        // Threading metadata
        extractThreadingHeaders(message, metadata);

        // Source metadata
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "EMAIL_INBOX");
        metadata.put(GraphConstants.META_LOADER, "Email Inbox Loader");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "email");
        metadata.put(GraphConstants.META_FILE_NAME, message.getSubject() != null && !message.getSubject().isEmpty()
                ? message.getSubject() : "Email Message");
        metadata.put(GraphConstants.META_CONTENT_TYPE_HINT, "email");

        // Inline CID images from multipart/related
        if (inlineImages != null && !inlineImages.isEmpty()) {
            metadata.put("email.inlineImages", inlineImages);
        }

        Document emailDoc = new Document(content.toString(), metadata);
        results.add(emailDoc);

        // Extract attachments as separate documents
        if (includeAttachments) {
            List<Document> attachmentDocs = extractAttachments(message, sourcePath, metadata);
            results.addAll(attachmentDocs);

            // Collect attachment names and sizes on the parent email document
            // so EmailGraphExtractor can create HAS_ATTACHMENT relations
            if (!attachmentDocs.isEmpty()) {
                List<String> attachNames = new ArrayList<>();
                List<String> attachSizes = new ArrayList<>();
                for (Document attachDoc : attachmentDocs) {
                    Object name = attachDoc.getMetadata().get("email.attachmentName");
                    attachNames.add(name != null ? name.toString() : "unnamed");
                    Object size = attachDoc.getMetadata().get("email.attachmentSize");
                    attachSizes.add(size != null ? size.toString() : "0");
                }
                metadata.put(GraphConstants.META_EMAIL_ATTACHMENT_NAMES, attachNames);
                metadata.put(GraphConstants.META_EMAIL_ATTACHMENT_SIZES, attachSizes);
            }
        }

        return results;
    }

    private void extractHeaders(Message message, StringBuilder content, Map<String, Object> metadata) {
        if (message.getSubject() != null) {
            content.append("Subject: ").append(message.getSubject()).append("\n");
            metadata.put("email.subject", message.getSubject());
        }

        if (message.getFrom() != null && !message.getFrom().isEmpty()) {
            String from = formatMailboxList(message.getFrom());
            content.append("From: ").append(from).append("\n");
            metadata.put("email.from", from);

            Mailbox firstFrom = message.getFrom().get(0);
            if (firstFrom.getName() != null) {
                metadata.put("email.fromName", firstFrom.getName());
            }
            metadata.put("email.fromAddress", firstFrom.getAddress());
        }

        if (message.getTo() != null) {
            MailboxList toList = message.getTo().flatten();
            if (!toList.isEmpty()) {
                String to = formatMailboxList(toList);
                content.append("To: ").append(to).append("\n");
                metadata.put("email.to", to);
            }
        }

        if (message.getCc() != null) {
            MailboxList ccList = message.getCc().flatten();
            if (!ccList.isEmpty()) {
                String cc = formatMailboxList(ccList);
                content.append("Cc: ").append(cc).append("\n");
                metadata.put("email.cc", cc);
            }
        }

        if (message.getBcc() != null) {
            MailboxList bccList = message.getBcc().flatten();
            if (!bccList.isEmpty()) {
                metadata.put("email.bcc", formatMailboxList(bccList));
            }
        }

        if (message.getDate() != null) {
            content.append("Date: ").append(message.getDate()).append("\n");
            metadata.put("email.date", message.getDate().toInstant().toString());
        }

        String mimeType = message.getMimeType();
        if (mimeType != null) {
            metadata.put("email.mimeType", mimeType);
        }
    }

    private void extractThreadingHeaders(Message message, Map<String, Object> metadata) {
        Field messageIdField = message.getHeader().getField("Message-ID");
        if (messageIdField != null) {
            metadata.put("email.messageId", messageIdField.getBody().trim());
        }

        Field inReplyTo = message.getHeader().getField("In-Reply-To");
        if (inReplyTo != null) {
            metadata.put("email.inReplyTo", inReplyTo.getBody().trim());
        }

        Field references = message.getHeader().getField("References");
        if (references != null) {
            String refBody = references.getBody().trim();
            List<String> refList = Arrays.asList(refBody.split("\\s+"));
            metadata.put("email.references", refList);
        }

        // Mailing list headers
        Field listId = message.getHeader().getField("List-Id");
        if (listId != null) {
            metadata.put("email.listId", listId.getBody().trim());
        }

        Field listPost = message.getHeader().getField("List-Post");
        if (listPost != null) {
            metadata.put("email.listPost", listPost.getBody().trim());
        }

        // X-Mailer / User-Agent
        Field xMailer = message.getHeader().getField("X-Mailer");
        if (xMailer != null) {
            metadata.put("email.mailer", xMailer.getBody().trim());
        }
        Field userAgent = message.getHeader().getField("User-Agent");
        if (userAgent != null) {
            metadata.put("email.userAgent", userAgent.getBody().trim());
        }

        // Priority / Importance headers
        Field xPriority = message.getHeader().getField("X-Priority");
        if (xPriority != null && xPriority.getBody() != null && !xPriority.getBody().isBlank()) {
            metadata.put("email.priority", xPriority.getBody().trim());
        }
        Field importanceField = message.getHeader().getField("Importance");
        if (importanceField != null && importanceField.getBody() != null && !importanceField.getBody().isBlank()) {
            metadata.put("email.importance", importanceField.getBody().trim());
        }

        // Thread-Topic / Conversation-Topic (Microsoft Outlook sets this)
        Field threadTopic = message.getHeader().getField("Thread-Topic");
        if (threadTopic != null && threadTopic.getBody() != null && !threadTopic.getBody().isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_CONVERSATION_TOPIC, threadTopic.getBody().trim());
        }

        // Authentication-Results (DKIM, SPF, DMARC)
        Field authResults = message.getHeader().getField("Authentication-Results");
        if (authResults != null && authResults.getBody() != null && !authResults.getBody().isBlank()) {
            String authBody = authResults.getBody().trim();
            metadata.put("email.authenticationResults", authBody);
            // Extract individual results
            if (authBody.toLowerCase().contains("dkim=")) {
                int idx = authBody.toLowerCase().indexOf("dkim=");
                String dkimResult = authBody.substring(idx + 5).split("[;\\s]")[0];
                metadata.put("email.dkimResult", dkimResult);
            }
            if (authBody.toLowerCase().contains("spf=")) {
                int idx = authBody.toLowerCase().indexOf("spf=");
                String spfResult = authBody.substring(idx + 4).split("[;\\s]")[0];
                metadata.put("email.spfResult", spfResult);
            }
            if (authBody.toLowerCase().contains("dmarc=")) {
                int idx = authBody.toLowerCase().indexOf("dmarc=");
                String dmarcResult = authBody.substring(idx + 6).split("[;\\s]")[0];
                metadata.put("email.dmarcResult", dmarcResult);
            }
        }

        // Return-Path header (envelope sender / bounce address)
        Field returnPath = message.getHeader().getField("Return-Path");
        if (returnPath != null && returnPath.getBody() != null && !returnPath.getBody().isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_RETURN_PATH, returnPath.getBody().trim());
        }

        // Auto-Submitted header (detect auto-replies, OOF, notifications)
        Field autoSubmitted = message.getHeader().getField("Auto-Submitted");
        if (autoSubmitted != null && autoSubmitted.getBody() != null && !autoSubmitted.getBody().isBlank()) {
            String autoVal = autoSubmitted.getBody().trim();
            metadata.put(GraphConstants.META_EMAIL_AUTO_SUBMITTED, autoVal);
            if (!"no".equalsIgnoreCase(autoVal)) {
                metadata.put(GraphConstants.META_EMAIL_IS_AUTO_REPLY, true);
            }
        }

        // Precedence header (bulk, junk, list)
        Field precedence = message.getHeader().getField("Precedence");
        if (precedence != null && precedence.getBody() != null && !precedence.getBody().isBlank()) {
            String prec = precedence.getBody().trim().toLowerCase();
            if ("bulk".equals(prec) || "junk".equals(prec) || "list".equals(prec)) {
                metadata.put(GraphConstants.META_EMAIL_PRECEDENCE, prec);
            }
        }

        // Received headers (SMTP relay chain — one per hop)
        // Activates MAIL_SERVER entity + ROUTED_VIA relation extraction in EmailGraphExtractor
        List<Field> receivedFields = message.getHeader().getFields("Received");
        if (receivedFields != null && !receivedFields.isEmpty()) {
            List<String> receivedList = receivedFields.stream()
                    .map(Field::getBody)
                    .filter(b -> b != null && !b.isBlank())
                    .map(String::trim)
                    .toList();
            if (!receivedList.isEmpty()) {
                metadata.put(GraphConstants.META_EMAIL_RECEIVED_HEADERS, receivedList);
            }
        }
    }

    /**
     * Recursively extracts plain text and HTML text from a message body.
     */
    private BodyExtraction extractBody(Entity entity) {
        Body body = entity.getBody();

        if (body instanceof TextBody) {
            String mimeType = entity.getMimeType();
            String text = readTextBody((TextBody) body);
            if ("text/plain".equalsIgnoreCase(mimeType)) {
                return new BodyExtraction(text, null);
            } else if ("text/html".equalsIgnoreCase(mimeType)) {
                return new BodyExtraction(null, text);
            }
            // Default: treat as plain text
            return new BodyExtraction(text, null);
        }

        if (body instanceof Multipart) {
            Multipart multipart = (Multipart) body;
            String subType = multipart.getSubType();

            String plainText = null;
            String htmlText = null;

            for (Entity part : multipart.getBodyParts()) {
                // Skip attachments during body extraction
                if (isAttachment(part)) {
                    continue;
                }

                // Track inline CID images from multipart/related parts
                if ("related".equalsIgnoreCase(subType)) {
                    Field contentId = part.getHeader().getField("Content-ID");
                    if (contentId != null && contentId.getBody() != null) {
                        String cid = contentId.getBody().trim();
                        String mimeType = part.getMimeType();
                        if (mimeType != null && (mimeType.startsWith("image/")
                                || mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) {
                            if (inlineImages == null) inlineImages = new ArrayList<>();
                            if (inlineImages.size() < 50) {
                                Map<String, String> cidInfo = new LinkedHashMap<>();
                                cidInfo.put("contentId", cid);
                                cidInfo.put("mimeType", mimeType);
                                Field nameField = part.getHeader().getField("Content-Type");
                                if (nameField != null && nameField.getBody() != null) {
                                    String nameBody = nameField.getBody();
                                    int nameIdx = nameBody.toLowerCase().indexOf("name=");
                                    if (nameIdx >= 0) {
                                        String name = nameBody.substring(nameIdx + 5)
                                                .replace("\"", "").split("[;\\s]")[0].trim();
                                        if (!name.isBlank()) cidInfo.put("fileName", name);
                                    }
                                }
                                inlineImages.add(cidInfo);
                            }
                        }
                    }
                }

                BodyExtraction partExtraction = extractBody(part);

                if (partExtraction.plainText != null && plainText == null) {
                    plainText = partExtraction.plainText;
                }
                if (partExtraction.htmlText != null && htmlText == null) {
                    htmlText = partExtraction.htmlText;
                }

                // For alternative, we want both but prefer plain
                // For mixed/related, concatenate plain text parts
                if ("mixed".equalsIgnoreCase(subType) && partExtraction.plainText != null && plainText != null
                        && !plainText.equals(partExtraction.plainText)) {
                    plainText = plainText + "\n" + partExtraction.plainText;
                }
            }

            return new BodyExtraction(plainText, htmlText);
        }

        return new BodyExtraction(null, null);
    }

    private boolean isAttachment(Entity entity) {
        Field dispositionField = entity.getHeader().getField("Content-Disposition");
        if (dispositionField instanceof ContentDispositionField) {
            String disposition = ((ContentDispositionField) dispositionField).getDispositionType();
            return "attachment".equalsIgnoreCase(disposition);
        }
        if (dispositionField != null) {
            return dispositionField.getBody().toLowerCase().contains("attachment");
        }
        return false;
    }

    private List<Document> extractAttachments(Message message, String sourcePath, Map<String, Object> parentMeta) {
        List<Document> docs = new ArrayList<>();
        Body body = message.getBody();
        if (!(body instanceof Multipart)) {
            return docs;
        }

        collectAttachments((Multipart) body, sourcePath, parentMeta, docs);
        return docs;
    }

    private void collectAttachments(Multipart multipart, String sourcePath,
                                    Map<String, Object> parentMeta, List<Document> docs) {
        for (Entity part : multipart.getBodyParts()) {
            if (part.getBody() instanceof Multipart) {
                collectAttachments((Multipart) part.getBody(), sourcePath, parentMeta, docs);
                continue;
            }

            if (!isAttachment(part)) {
                continue;
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("email.isAttachment", true);
            if (parentMeta.get("email.messageId") != null) {
                meta.put("email.parentMessageId", parentMeta.get("email.messageId"));
            }
            if (parentMeta.get("email.subject") != null) {
                meta.put("email.parentSubject", parentMeta.get("email.subject"));
            }
            if (parentMeta.get("email.from") != null) {
                meta.put(GraphConstants.META_EMAIL_PARENT_FROM, parentMeta.get("email.from"));
            }
            if (parentMeta.get("email.date") != null) {
                meta.put(GraphConstants.META_EMAIL_PARENT_DATE, parentMeta.get("email.date").toString());
            }
            meta.put(GraphConstants.META_SOURCE, sourcePath);
            meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
            meta.put(GraphConstants.META_SOURCE_TYPE, "EMAIL_ATTACHMENT");
            meta.put(GraphConstants.META_LOADER, "Email Inbox Loader");
            meta.put(GraphConstants.META_DOCUMENT_TYPE, "email_attachment");

            String filename = getFilename(part);
            if (filename != null) {
                meta.put("email.attachmentName", filename);
                meta.put(GraphConstants.META_FILE_NAME, filename);
            }

            String mimeType = part.getMimeType();
            meta.put("email.attachmentMimeType", mimeType);

            // Extract text from text-based attachments
            if (part.getBody() instanceof TextBody) {
                String text = readTextBody((TextBody) part.getBody());
                docs.add(new Document("Attachment: " + filename + "\n\n" + text, meta));
            } else {
                docs.add(new Document("[Attachment: " + (filename != null ? filename : "unnamed") +
                        " (" + mimeType + ")]", meta));
            }
        }
    }

    private String getFilename(Entity entity) {
        Field dispositionField = entity.getHeader().getField("Content-Disposition");
        if (dispositionField instanceof ContentDispositionField) {
            String filename = ((ContentDispositionField) dispositionField).getFilename();
            if (filename != null) return filename;
        }

        Field ctField = entity.getHeader().getField("Content-Type");
        if (ctField instanceof ContentTypeField) {
            return ((ContentTypeField) ctField).getParameter("name");
        }
        return null;
    }

    private String readTextBody(TextBody textBody) {
        try (Reader reader = textBody.getReader()) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            logger.warn("Failed to read text body: {}", e.getMessage());
            return "";
        }
    }

    private String convertHtmlToText(String html) {
        if (html == null) return null;

        org.jsoup.nodes.Document doc = Jsoup.parse(html);
        doc.select("script, style, head, nav, footer").remove();

        doc.select("br").before("\\n");
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, tr").before("\\n\\n");

        String text = doc.text().replace("\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[ \\t]+", " ");
        return text.trim();
    }

    private String formatMailboxList(MailboxList mailboxList) {
        if (mailboxList == null || mailboxList.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mailboxList.size(); i++) {
            if (i > 0) sb.append(", ");
            Mailbox mailbox = mailboxList.get(i);
            if (mailbox.getName() != null && !mailbox.getName().isEmpty()) {
                sb.append(mailbox.getName()).append(" <").append(mailbox.getAddress()).append(">");
            } else {
                sb.append(mailbox.getAddress());
            }
        }
        return sb.toString();
    }

    /**
     * Extracts tables from HTML email body and stores cell-level graph JSON in metadata.
     * This allows EmailGraphExtractor to create TABLE/CELL entities for data-rich emails
     * (reports, invoices, pricing tables) that would otherwise be flattened to plain text.
     */
    private void extractHtmlBodyTables(String html, String sourcePath, Map<String, Object> metadata) {
        try {
            org.jsoup.nodes.Document htmlDoc = Jsoup.parse(html);
            Elements tables = htmlDoc.select("table");
            if (tables.isEmpty()) return;

            List<Graph> graphs = new ArrayList<>();
            int tableIdx = 0;
            for (Element table : tables) {
                if (tableIdx >= 10) break; // safety cap on tables per email
                // Select only direct rows (not from nested tables) — walk thead/tbody/direct children
                Elements rows = new Elements();
                for (Element child : table.children()) {
                    if ("tr".equalsIgnoreCase(child.tagName())) {
                        rows.add(child);
                    } else if ("thead".equalsIgnoreCase(child.tagName()) || "tbody".equalsIgnoreCase(child.tagName())
                            || "tfoot".equalsIgnoreCase(child.tagName())) {
                        for (Element tr : child.children()) {
                            if ("tr".equalsIgnoreCase(tr.tagName())) rows.add(tr);
                        }
                    }
                }
                if (rows.size() < 2) continue; // need at least header + 1 data row

                TableCellGraphBuilder builder = new TableCellGraphBuilder()
                        .namespace("email:" + (sourcePath != null ? sourcePath : "unknown") + "/tbl:" + tableIdx)
                        .tableName("Email-Table-" + (tableIdx + 1));

                // Extract header row
                Element headerRow = rows.first();
                if (headerRow != null) {
                    Elements headerCells = headerRow.select("th, td");
                    List<String> headerTexts = new ArrayList<>();
                    for (Element cell : headerCells) {
                        headerTexts.add(cell.text().trim());
                    }
                    if (!headerTexts.isEmpty()) {
                        builder.headers(headerTexts);
                        builder.addRow(headerTexts);
                    }
                }

                // Extract data rows
                for (int i = 1; i < rows.size(); i++) {
                    Element row = rows.get(i);
                    Elements cells = row.select("td, th");
                    List<String> cellTexts = new ArrayList<>();
                    for (Element cell : cells) {
                        cellTexts.add(cell.text().trim());
                    }
                    builder.addRow(cellTexts);
                }

                Graph cellGraph = builder.build();
                if (!cellGraph.getEntities().isEmpty()) {
                    graphs.add(cellGraph);
                    tableIdx++;
                }
            }

            if (!graphs.isEmpty()) {
                // Merge all table graphs into one combined graph
                List<ai.kompile.core.graphrag.model.Entity> allEntities = new ArrayList<>();
                List<ai.kompile.core.graphrag.model.Relationship> allRels = new ArrayList<>();
                for (Graph g : graphs) {
                    allEntities.addAll(g.getEntities());
                    allRels.addAll(g.getRelationships());
                }
                Graph combined = new Graph();
                combined.setEntities(allEntities);
                combined.setRelationships(allRels);
                metadata.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(combined));
                metadata.put("email.tableCount", tableIdx);
            }
        } catch (Exception e) {
            logger.debug("Failed to extract tables from email HTML body: {}", e.getMessage());
        }
    }

    /**
     * Internal extraction result holding both plain and HTML variants.
     */
    private record BodyExtraction(String plainText, String htmlText) {}
}
