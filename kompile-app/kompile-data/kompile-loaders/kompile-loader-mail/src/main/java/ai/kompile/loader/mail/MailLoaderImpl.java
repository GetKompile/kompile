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

package ai.kompile.loader.mail;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.MessageBuilder;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.ContentDispositionField;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MailLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(MailLoaderImpl.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "eml", "msg", "mbox"
    );

    @Override
    public String getName() {
        return "Mail Message Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }
        
        String path = sourceDescriptor.getPathOrUrl() != null ? sourceDescriptor.getPathOrUrl().toLowerCase() : "";
        return SUPPORTED_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("MailLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();

        try {
            if (filename.endsWith(".eml")) {
                return loadEmlFile(file);
            } else if (filename.endsWith(".mbox")) {
                return loadMboxFile(file);
            } else if (filename.endsWith(".msg")) {
                return loadMsgFile(file);
            }
        } catch (Exception e) {
            // Handle corrupted or invalid mail files gracefully
            String errorMessage = e.getMessage();
            logger.warn("Unable to parse mail file '{}': {}. The file may be corrupted or in an unsupported format.",
                       file.getName(), errorMessage);

            // Return an error document so the caller knows what happened
            Document errorDoc = new Document("[Error: Unable to parse mail file. The file may be corrupted or in an unsupported format.]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("fileSize", file.length());
            errorDoc.getMetadata().put("lastModified", file.lastModified());
            errorDoc.getMetadata().put("loader", getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", errorMessage != null ? errorMessage : "Unknown error");
            return List.of(errorDoc);
        }

        throw new IllegalArgumentException("Unsupported mail file type: " + filename);
    }

    private List<Document> loadEmlFile(File file) throws Exception {
        MessageBuilder builder = new DefaultMessageBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            Message message = builder.parseMessage(fis);
            Document document = convertMessageToDocument(message, file);
            return List.of(document);
        }
    }

    private List<Document> loadMboxFile(File file) throws Exception {
        List<Document> documents = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            StringBuilder messageBuilder = new StringBuilder();
            String line;
            boolean inMessage = false;
            
            while ((line = reader.readLine()) != null) {
                // mbox format: messages start with "From " line
                if (line.startsWith("From ") && inMessage) {
                    // Process previous message
                    if (messageBuilder.length() > 0) {
                        Document doc = parseMboxMessage(messageBuilder.toString(), file);
                        if (doc != null) {
                            documents.add(doc);
                        }
                        messageBuilder.setLength(0);
                    }
                    inMessage = true;
                } else if (line.startsWith("From ") && !inMessage) {
                    inMessage = true;
                    continue;
                }
                
                if (inMessage) {
                    messageBuilder.append(line).append("\n");
                }
            }
            
            // Process the last message
            if (messageBuilder.length() > 0) {
                Document doc = parseMboxMessage(messageBuilder.toString(), file);
                if (doc != null) {
                    documents.add(doc);
                }
            }
        }
        
        return documents;
    }

    private Document parseMboxMessage(String messageContent, File originalFile) {
        try {
            MessageBuilder builder = new DefaultMessageBuilder();
            ByteArrayInputStream bais = new ByteArrayInputStream(messageContent.getBytes());
            Message message = builder.parseMessage(bais);
            return convertMessageToDocument(message, originalFile);
        } catch (Exception e) {
            // If parsing fails, create a simple document with raw content
            Document doc = new Document(messageContent);
            addMetadata(doc, originalFile, "Raw mbox message");
            return doc;
        }
    }

    private List<Document> loadMsgFile(File file) throws Exception {
        // .msg is a CFBF/OLE2 container (MS-OXMSG / MAPI properties), not raw MIME text -
        // parse it with POI's HSMF reader rather than treating it as a MimeMessage.
        try (MAPIMessage mapiMessage = new MAPIMessage(file)) {
            // Have every MAPIMessage getter below return null for an absent chunk instead
            // of throwing ChunkNotFoundException, so one missing property degrades that
            // field rather than failing the whole file.
            mapiMessage.setReturnNullOnMissingChunk(true);
            return List.of(convertMapiMessageToDocument(mapiMessage, file));
        }
    }

    private Document convertMessageToDocument(Message message, File originalFile) throws IOException {
        StringBuilder content = new StringBuilder();
        
        // Extract headers
        if (message.getSubject() != null) {
            content.append("Subject: ").append(message.getSubject()).append("\n");
        }
        
        if (message.getFrom() != null) {
            content.append("From: ").append(formatMailboxList(message.getFrom())).append("\n");
        }
        
        if (message.getTo() != null) {
            content.append("To: ").append(formatMailboxList(message.getTo().flatten())).append("\n");
        }
        
        if (message.getCc() != null) {
            content.append("Cc: ").append(formatMailboxList(message.getCc().flatten())).append("\n");
        }
        
        if (message.getDate() != null) {
            content.append("Date: ").append(message.getDate()).append("\n");
        }
        
        content.append("\n");
        
        // Extract body
        String bodyText = extractBodyText(message);
        if (bodyText != null && !bodyText.trim().isEmpty()) {
            content.append(bodyText);
        }

        List<Map<String, Object>> attachments = collectAttachments(message);
        appendAttachmentsSection(content, attachments);

        Document document = new Document(content.toString());
        addMetadata(document, originalFile, "Email Message");

        // Add canonical email metadata consumed by the shared graph extractors.
        if (message.getSubject() != null) {
            document.getMetadata().put("email.subject", message.getSubject());
        }
        if (message.getFrom() != null) {
            document.getMetadata().put("email.from", formatMailboxList(message.getFrom()));
        }
        if (message.getTo() != null) {
            document.getMetadata().put("email.to", formatMailboxList(message.getTo().flatten()));
        }
        if (message.getCc() != null) {
            document.getMetadata().put("email.cc", formatMailboxList(message.getCc().flatten()));
        }
        if (message.getDate() != null) {
            document.getMetadata().put("email.date", message.getDate().toString());
        }
        if (!attachments.isEmpty()) {
            document.getMetadata().put("email.attachments", attachments);
        }

        return document;
    }

    /**
     * Extracts the readable body text of a message or a sub-part, applying MIME semantics
     * rather than naively concatenating every text-bearing part:
     * <ul>
     *   <li>{@code multipart/alternative} picks a single best representation (text/plain,
     *       else text/html converted to text) instead of appending every alternative.</li>
     *   <li>Other multipart subtypes concatenate their non-attachment parts in order.</li>
     *   <li>A nested {@code message/rfc822} part (a forwarded message) is quoted rather than
     *       inlined as if it were this message's own text.</li>
     * </ul>
     */
    private String extractBodyText(Entity entity) throws IOException {
        Body body = entity.getBody();
        if (body instanceof Message forwardedMessage) {
            return quoteForwardedMessage(forwardedMessage);
        }
        if (body instanceof TextBody textBody) {
            return readTextBody(textBody);
        }
        if (body instanceof Multipart multipart) {
            if ("alternative".equalsIgnoreCase(multipart.getSubType())) {
                return extractAlternativeText(multipart);
            }
            return extractMixedText(multipart);
        }
        return null;
    }

    /**
     * {@code multipart/alternative}: every part is the same content in a different format, so
     * exactly one representation is picked - text/plain if present, else text/html converted to
     * plain text, else whatever a nested multipart (e.g. multipart/related) yields.
     */
    private String extractAlternativeText(Multipart multipart) throws IOException {
        String htmlFallback = null;
        for (Entity part : multipart.getBodyParts()) {
            Body body = part.getBody();
            if (body instanceof TextBody textBody) {
                String mimeType = part.getMimeType();
                if (mimeType != null && mimeType.equalsIgnoreCase("text/plain")) {
                    return readTextBody(textBody);
                }
                if (htmlFallback == null && mimeType != null && mimeType.equalsIgnoreCase("text/html")) {
                    htmlFallback = htmlToText(readTextBody(textBody));
                }
            } else if (htmlFallback == null && body instanceof Multipart) {
                String nestedText = extractBodyText(part);
                if (nestedText != null && !nestedText.isBlank()) {
                    htmlFallback = nestedText;
                }
            }
        }
        return htmlFallback;
    }

    /**
     * Non-alternative multipart subtypes ({@code mixed}, {@code related}, ...): concatenate
     * every part's text in order, skipping attachments so a text attachment never replaces or
     * pollutes the actual body.
     */
    private String extractMixedText(Multipart multipart) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Entity part : multipart.getBodyParts()) {
            if (isAttachment(part)) {
                continue;
            }
            String partText = extractBodyText(part);
            if (partText != null && !partText.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(partText);
            }
        }
        return text.toString();
    }

    /** Renders a {@code message/rfc822} forwarded message as a quoted, clearly-delimited section. */
    private String quoteForwardedMessage(Message forwarded) throws IOException {
        StringBuilder quoted = new StringBuilder();
        quoted.append("---------- Forwarded message ----------\n");
        if (forwarded.getSubject() != null) {
            quoted.append("Subject: ").append(forwarded.getSubject()).append("\n");
        }
        if (forwarded.getFrom() != null) {
            quoted.append("From: ").append(formatMailboxList(forwarded.getFrom())).append("\n");
        }
        if (forwarded.getTo() != null) {
            quoted.append("To: ").append(formatMailboxList(forwarded.getTo().flatten())).append("\n");
        }
        if (forwarded.getDate() != null) {
            quoted.append("Date: ").append(forwarded.getDate()).append("\n");
        }
        quoted.append("\n");

        String innerBody = extractBodyText(forwarded);
        if (innerBody != null && !innerBody.isBlank()) {
            for (String line : innerBody.stripTrailing().split("\n", -1)) {
                quoted.append("> ").append(line).append("\n");
            }
        }
        return quoted.toString();
    }

    /**
     * True when a part is a named/declared attachment rather than an inline body part: either
     * an explicit {@code Content-Disposition: attachment}, or a part that carries a filename
     * without declaring any disposition at all.
     */
    private boolean isAttachment(Entity entity) {
        String dispositionType = entity.getDispositionType();
        if (dispositionType != null) {
            return ContentDispositionField.DISPOSITION_TYPE_ATTACHMENT.equalsIgnoreCase(dispositionType);
        }
        return entity.getFilename() != null;
    }

    /** Walks the whole entity tree (including into nested forwarded messages) collecting attachments. */
    private List<Map<String, Object>> collectAttachments(Entity entity) throws IOException {
        List<Map<String, Object>> attachments = new ArrayList<>();
        collectAttachments(entity, attachments);
        return attachments;
    }

    private void collectAttachments(Entity entity, List<Map<String, Object>> attachments) throws IOException {
        Body body = entity.getBody();
        if (body instanceof Message nestedMessage) {
            collectAttachments(nestedMessage, attachments);
        } else if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                if (isAttachment(part)) {
                    attachments.add(describeAttachment(part));
                } else {
                    collectAttachments(part, attachments);
                }
            }
        }
    }

    /** Records name/type/size only - the attachment's binary content is never read into the document. */
    private Map<String, Object> describeAttachment(Entity part) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", part.getFilename() != null ? part.getFilename() : "unnamed");
        info.put("type", part.getMimeType() != null ? part.getMimeType() : "application/octet-stream");
        if (part.getBody() instanceof SingleBody singleBody) {
            info.put("size", singleBody.size());
        }
        return info;
    }

    private void appendAttachmentsSection(StringBuilder content, List<Map<String, Object>> attachments) {
        if (attachments.isEmpty()) {
            return;
        }
        content.append("\nAttachments:\n");
        for (Map<String, Object> attachment : attachments) {
            content.append("- ").append(attachment.get("name"));
            Object type = attachment.get("type");
            Object size = attachment.get("size");
            if (type != null || size != null) {
                content.append(" (");
                if (type != null) {
                    content.append(type);
                }
                if (size != null) {
                    content.append(type != null ? ", " : "").append(size).append(" bytes");
                }
                content.append(")");
            }
            content.append("\n");
        }
    }

    private String readTextBody(TextBody textBody) throws IOException {
        try (Reader reader = textBody.getReader()) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int bytesRead;
            while ((bytesRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, bytesRead);
            }
            return sb.toString();
        }
    }

    private String formatMailboxList(MailboxList mailboxList) {
        if (mailboxList == null || mailboxList.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mailboxList.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Mailbox mailbox = mailboxList.get(i);
            if (mailbox.getName() != null && !mailbox.getName().isEmpty()) {
                sb.append(mailbox.getName()).append(" <").append(mailbox.getAddress()).append(">");
            } else {
                sb.append(mailbox.getAddress());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // .msg (Outlook / MAPI, via POI HSMF) support
    // ------------------------------------------------------------------

    private Document convertMapiMessageToDocument(MAPIMessage mapiMessage, File originalFile) {
        String subject = safeMsgField(mapiMessage::getSubject);
        String from = formatMsgFrom(mapiMessage);
        String to = safeMsgField(mapiMessage::getDisplayTo);
        String cc = safeMsgField(mapiMessage::getDisplayCC);
        String date = extractMsgDate(mapiMessage);
        String bodyText = extractMsgBodyText(mapiMessage);
        List<Map<String, Object>> attachments = collectMsgAttachments(mapiMessage);

        StringBuilder content = new StringBuilder();
        if (subject != null) {
            content.append("Subject: ").append(subject).append("\n");
        }
        if (from != null) {
            content.append("From: ").append(from).append("\n");
        }
        if (to != null && !to.isBlank()) {
            content.append("To: ").append(to).append("\n");
        }
        if (cc != null && !cc.isBlank()) {
            content.append("Cc: ").append(cc).append("\n");
        }
        if (date != null) {
            content.append("Date: ").append(date).append("\n");
        }
        content.append("\n");
        if (bodyText != null && !bodyText.isBlank()) {
            content.append(bodyText);
        }
        appendAttachmentsSection(content, attachments);

        Document document = new Document(content.toString());
        addMetadata(document, originalFile, "Microsoft Outlook Message");

        if (subject != null) {
            document.getMetadata().put("email.subject", subject);
        }
        if (from != null) {
            document.getMetadata().put("email.from", from);
        }
        if (to != null && !to.isBlank()) {
            document.getMetadata().put("email.to", to);
        }
        if (cc != null && !cc.isBlank()) {
            document.getMetadata().put("email.cc", cc);
        }
        if (date != null) {
            document.getMetadata().put("email.date", date);
        }
        if (!attachments.isEmpty()) {
            document.getMetadata().put("email.attachments", attachments);
        }
        return document;
    }

    /** Combines the display-name and address chunks into a single "Name &lt;addr&gt;" string. */
    private String formatMsgFrom(MAPIMessage mapiMessage) {
        Chunks mainChunks = mapiMessage.getMainChunks();
        String displayName = chunkValue(mainChunks.getDisplayFromChunk());
        String address = chunkValue(mainChunks.getEmailFromChunk());
        boolean hasName = displayName != null && !displayName.isBlank();
        boolean hasAddress = address != null && !address.isBlank();
        if (hasName && hasAddress) {
            return displayName + " <" + address + ">";
        }
        if (hasAddress) {
            return address;
        }
        return hasName ? displayName : null;
    }

    private String chunkValue(StringChunk chunk) {
        return chunk != null ? chunk.getValue() : null;
    }

    /** getTextBody() -> HTML body converted to text -> RTF body with control words stripped. */
    private String extractMsgBodyText(MAPIMessage mapiMessage) {
        String plainText = safeMsgField(mapiMessage::getTextBody);
        if (plainText != null && !plainText.isBlank()) {
            return plainText;
        }
        String htmlBody = safeMsgField(mapiMessage::getHtmlBody);
        if (htmlBody != null && !htmlBody.isBlank()) {
            return htmlToText(htmlBody);
        }
        String rtfBody = safeMsgField(mapiMessage::getRtfBody);
        if (rtfBody != null && !rtfBody.isBlank()) {
            return stripRtfControlWords(rtfBody);
        }
        return null;
    }

    private String extractMsgDate(MAPIMessage mapiMessage) {
        Calendar messageDate = safeMsgField(mapiMessage::getMessageDate);
        return messageDate != null ? messageDate.getTime().toString() : null;
    }

    private List<Map<String, Object>> collectMsgAttachments(MAPIMessage mapiMessage) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        AttachmentChunks[] attachmentChunks = mapiMessage.getAttachmentFiles();
        if (attachmentChunks == null) {
            return attachments;
        }
        for (AttachmentChunks attachment : attachmentChunks) {
            Map<String, Object> info = new LinkedHashMap<>();
            String name = chunkValue(attachment.getAttachLongFileName());
            if (name == null || name.isBlank()) {
                name = chunkValue(attachment.getAttachFileName());
            }
            info.put("name", name != null && !name.isBlank() ? name : "unnamed");
            String type = chunkValue(attachment.getAttachMimeTag());
            info.put("type", type != null && !type.isBlank() ? type : "application/octet-stream");
            ByteChunk data = attachment.getAttachData();
            if (data != null && data.getValue() != null) {
                info.put("size", (long) data.getValue().length);
            }
            attachments.add(info);
        }
        return attachments;
    }

    @FunctionalInterface
    private interface MsgFieldSupplier<T> {
        T get() throws ChunkNotFoundException;
    }

    /**
     * Runs a MAPIMessage chunk getter, treating a missing chunk as absent data rather than a
     * failure of the whole file. {@code setReturnNullOnMissingChunk(true)} already makes most of
     * these getters return null instead of throwing, but every call is wrapped here too as a
     * second, independent guarantee against ChunkNotFoundException in case a particular getter
     * does not honor that flag.
     */
    private <T> T safeMsgField(MsgFieldSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ChunkNotFoundException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Shared text-conversion helpers (HTML and RTF bodies -> plain text)
    // ------------------------------------------------------------------

    private static final Pattern HTML_SCRIPT_OR_STYLE =
        Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_BLOCK_BREAK =
        Pattern.compile("(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6])\\s*/?\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n{3,}");

    /**
     * Minimal, dependency-free HTML-to-text conversion used as a fallback body representation.
     * Package-private (rather than private) so {@code MailLoaderImplTextConversionTest} can
     * exercise it directly - it has no .msg/.eml fixture dependency of its own.
     */
    String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String noScripts = HTML_SCRIPT_OR_STYLE.matcher(html).replaceAll("");
        String withBreaks = HTML_BLOCK_BREAK.matcher(noScripts).replaceAll("\n");
        String noTags = HTML_TAG.matcher(withBreaks).replaceAll("");
        String decoded = decodeHtmlEntities(noTags);
        return BLANK_LINE_RUN.matcher(decoded).replaceAll("\n\n").trim();
    }

    private String decodeHtmlEntities(String text) {
        return text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&"); // must be last: an earlier replacement can introduce a literal "&"
    }

    /** RTF destination groups whose content is not visible document text. */
    private static final Set<String> RTF_DESTINATION_CONTROL_WORDS = Set.of(
        "fonttbl", "colortbl", "stylesheet", "info", "generator", "pict",
        "object", "objdata", "footer", "header", "footnote", "themedata",
        "colorschememapping", "datastore", "xmlopen", "listtable", "listoverridetable"
    );

    /**
     * Strips RTF control words/groups down to plain text: known non-text "destination" groups
     * (font/color tables, embedded objects, ...) are dropped entirely, {@code \par}/{@code \line}
     * become newlines, {@code \tab} becomes a tab, {@code \'hh} hex escapes are decoded, and every
     * other control word is discarded while its group's visible text is kept. Used only as the
     * last-resort body fallback, when a .msg has neither a plain-text nor an HTML body chunk.
     *
     * <p>Package-private (rather than private) so {@code MailLoaderImplTextConversionTest} can
     * exercise it directly without a real .msg fixture (POI's HSMF reader is read-only, so a
     * valid MAPI/CFBF file cannot be synthesized in-memory the way XWPF/XSLF documents can).
     */
    String stripRtfControlWords(String rtf) {
        if (rtf == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        Deque<Boolean> skipStack = new ArrayDeque<>();
        boolean skipping = false;
        int i = 0;
        int length = rtf.length();
        while (i < length) {
            char c = rtf.charAt(i);
            if (c == '{') {
                skipStack.push(skipping);
                i++;
            } else if (c == '}') {
                if (!skipStack.isEmpty()) {
                    skipping = skipStack.pop();
                }
                i++;
            } else if (c == '\\' && i + 1 < length) {
                i++;
                char next = rtf.charAt(i);
                if (next == '\\' || next == '{' || next == '}') {
                    if (!skipping) {
                        text.append(next);
                    }
                    i++;
                } else if (next == '\'' && i + 2 < length) {
                    String hex = rtf.substring(i + 1, i + 3);
                    if (!skipping) {
                        try {
                            text.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ignored) {
                            // Malformed hex escape - skip it rather than fail the whole body.
                        }
                    }
                    i += 3;
                } else {
                    int wordStart = i;
                    while (i < length && Character.isLetter(rtf.charAt(i))) {
                        i++;
                    }
                    String word = rtf.substring(wordStart, i);
                    while (i < length && (Character.isDigit(rtf.charAt(i)) || rtf.charAt(i) == '-')) {
                        i++;
                    }
                    if (i < length && rtf.charAt(i) == ' ') {
                        i++;
                    }
                    if (RTF_DESTINATION_CONTROL_WORDS.contains(word)) {
                        skipping = true;
                    } else if (!skipping) {
                        if (word.equals("par") || word.equals("line")) {
                            text.append("\n");
                        } else if (word.equals("tab")) {
                            text.append("\t");
                        }
                    }
                }
            } else {
                if (!skipping) {
                    text.append(c);
                }
                i++;
            }
        }
        return text.toString();
    }

    private void addMetadata(Document document, File file, String docType) {
        document.getMetadata().put("source", file.getAbsolutePath());
        document.getMetadata().put("fileName", file.getName());
        document.getMetadata().put("fileSize", file.length());
        document.getMetadata().put("lastModified", file.lastModified());
        document.getMetadata().put("documentType", docType);
        document.getMetadata().put("loader", getName());
    }
}
