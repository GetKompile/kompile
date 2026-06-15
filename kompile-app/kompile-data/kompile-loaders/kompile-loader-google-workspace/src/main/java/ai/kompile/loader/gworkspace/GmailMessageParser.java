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

package ai.kompile.loader.gworkspace;

import ai.kompile.core.graphrag.GraphConstants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses Gmail API message JSON into {@link Document}s with structured metadata.
 */
@Slf4j
public class GmailMessageParser {

    /**
     * Parse a full Gmail API message into a Document.
     */
    public Document parse(JsonNode message, String collectionName) {
        String messageId = textOrNull(message, "id");
        String threadId = textOrNull(message, "threadId");
        String snippet = textOrNull(message, "snippet");
        long internalDate = message.has("internalDate") ? message.get("internalDate").asLong() : 0;

        // Extract headers
        Map<String, String> headers = extractHeaders(message);

        // Extract body text
        String bodyText = extractBodyText(message);
        if ((bodyText == null || bodyText.isBlank()) && snippet != null) {
            bodyText = snippet;
        }

        // Build readable content
        StringBuilder content = new StringBuilder();
        String from = headers.getOrDefault("From", "Unknown");
        String subject = headers.getOrDefault("Subject", "(no subject)");
        String date = headers.getOrDefault("Date", "");

        content.append("From: ").append(from).append("\n");
        if (headers.containsKey("To")) content.append("To: ").append(headers.get("To")).append("\n");
        if (headers.containsKey("Cc")) content.append("Cc: ").append(headers.get("Cc")).append("\n");
        content.append("Subject: ").append(subject).append("\n");
        if (!date.isEmpty()) content.append("Date: ").append(date).append("\n");
        content.append("\n");
        if (bodyText != null) content.append(bodyText);

        Document doc = new Document(content.toString());
        Map<String, Object> metadata = doc.getMetadata();

        String sourcePath = "gworkspace:gmail/" + messageId;
        metadata.put(GraphConstants.META_SOURCE, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_PATH, sourcePath);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "GOOGLE_WORKSPACE");
        metadata.put(GraphConstants.META_FILE_NAME, subject != null && !subject.isEmpty() ? subject : "Gmail Message " + messageId);
        metadata.put(GraphConstants.META_LOADER, "Google Workspace Loader");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "email");
        metadata.put(GraphConstants.META_GWORKSPACE_SERVICE, "gmail");
        metadata.put("gworkspace.gmail.messageId", messageId);
        metadata.put("gworkspace.gmail.threadId", threadId);
        metadata.put("gworkspace.gmail.subject", subject);
        metadata.put("gworkspace.gmail.from", from);
        if (headers.containsKey("To")) metadata.put("gworkspace.gmail.to", headers.get("To"));
        if (headers.containsKey("Cc")) metadata.put("gworkspace.gmail.cc", headers.get("Cc"));
        if (headers.containsKey("Bcc")) metadata.put("gworkspace.gmail.bcc", headers.get("Bcc"));
        if (!date.isEmpty()) metadata.put("gworkspace.gmail.date", date);
        if (internalDate > 0) metadata.put("gworkspace.gmail.internalDate", internalDate);
        if (headers.containsKey("Message-ID")) metadata.put("gworkspace.gmail.rfc822MessageId", headers.get("Message-ID"));
        if (headers.containsKey("In-Reply-To")) metadata.put("gworkspace.gmail.inReplyTo", headers.get("In-Reply-To"));
        if (headers.containsKey("References")) metadata.put("gworkspace.gmail.references", headers.get("References"));
        if (headers.containsKey("List-Id")) metadata.put("gworkspace.gmail.listId", headers.get("List-Id"));
        if (headers.containsKey("Reply-To")) metadata.put(GraphConstants.META_GMAIL_REPLY_TO, headers.get("Reply-To"));
        if (headers.containsKey("Auto-Submitted")) {
            String autoSubmitted = headers.get("Auto-Submitted");
            metadata.put(GraphConstants.META_GMAIL_AUTO_SUBMITTED, autoSubmitted);
            if (!"no".equalsIgnoreCase(autoSubmitted.trim())) {
                metadata.put(GraphConstants.META_GMAIL_IS_AUTO_REPLY, true);
            }
        }

        // Labels
        JsonNode labelIds = message.get("labelIds");
        if (labelIds != null && labelIds.isArray()) {
            List<String> labels = new ArrayList<>();
            labelIds.forEach(l -> labels.add(l.asText()));
            metadata.put("gworkspace.gmail.labels", labels);
        }

        // Attachments
        List<Map<String, Object>> attachments = extractAttachmentMetadata(message);
        if (!attachments.isEmpty()) {
            metadata.put("gworkspace.gmail.attachmentCount", attachments.size());
            metadata.put("gworkspace.gmail.attachments", attachments);
            // Set email.attachmentNames for CrossDocumentRelationExtractor ATTACHMENT_OF edges
            List<String> attachmentNames = new ArrayList<>();
            for (Map<String, Object> att : attachments) {
                String fn = (String) att.get("filename");
                if (fn != null && !fn.isEmpty()) attachmentNames.add(fn);
            }
            if (!attachmentNames.isEmpty()) {
                metadata.put(GraphConstants.META_EMAIL_ATTACHMENT_NAMES, attachmentNames);
            }
        }

        if (collectionName != null) metadata.put("collection_name", collectionName);

        return doc;
    }

    private Map<String, String> extractHeaders(JsonNode message) {
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode payload = message.get("payload");
        if (payload == null) return headers;

        JsonNode headersList = payload.get("headers");
        if (headersList == null || !headersList.isArray()) return headers;

        for (JsonNode header : headersList) {
            String name = header.get("name").asText();
            String value = header.get("value").asText();
            headers.put(name, value);
        }

        return headers;
    }

    /**
     * Extract plain text body from the message payload, preferring text/plain over text/html.
     */
    private String extractBodyText(JsonNode message) {
        JsonNode payload = message.get("payload");
        if (payload == null) return null;

        // Try direct body on the payload
        String directBody = extractPartBody(payload, "text/plain");
        if (directBody != null) return directBody;

        // Walk multipart parts
        JsonNode parts = payload.get("parts");
        if (parts == null || !parts.isArray()) {
            // No parts, try HTML fallback
            return extractPartBody(payload, "text/html");
        }

        // First pass: text/plain
        for (JsonNode part : parts) {
            String body = extractPartBody(part, "text/plain");
            if (body != null) return body;

            // Nested multipart
            JsonNode subParts = part.get("parts");
            if (subParts != null && subParts.isArray()) {
                for (JsonNode subPart : subParts) {
                    body = extractPartBody(subPart, "text/plain");
                    if (body != null) return body;
                }
            }
        }

        // Second pass: text/html
        for (JsonNode part : parts) {
            String body = extractPartBody(part, "text/html");
            if (body != null) return stripHtml(body);

            JsonNode subParts = part.get("parts");
            if (subParts != null && subParts.isArray()) {
                for (JsonNode subPart : subParts) {
                    body = extractPartBody(subPart, "text/html");
                    if (body != null) return stripHtml(body);
                }
            }
        }

        return null;
    }

    private String extractPartBody(JsonNode part, String targetMime) {
        String mimeType = textOrNull(part, "mimeType");
        if (mimeType == null || !mimeType.equals(targetMime)) return null;

        JsonNode body = part.get("body");
        if (body == null) return null;

        String data = textOrNull(body, "data");
        if (data == null || data.isEmpty()) return null;

        byte[] decoded = Base64.getUrlDecoder().decode(data);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private String stripHtml(String html) {
        // Basic HTML stripping — remove tags and decode common entities
        return html.replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    List<Map<String, Object>> extractAttachmentMetadata(JsonNode message) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        JsonNode payload = message.get("payload");
        if (payload == null) return attachments;

        collectAttachments(payload, attachments);
        return attachments;
    }

    private void collectAttachments(JsonNode part, List<Map<String, Object>> attachments) {
        String filename = textOrNull(part, "filename");
        if (filename != null && !filename.isEmpty()) {
            JsonNode body = part.get("body");
            if (body != null) {
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("filename", filename);
                att.put("mimeType", textOrNull(part, "mimeType"));
                att.put("size", body.has("size") ? body.get("size").asInt() : 0);
                att.put("attachmentId", textOrNull(body, "attachmentId"));
                attachments.add(att);
            }
        }

        JsonNode parts = part.get("parts");
        if (parts != null && parts.isArray()) {
            for (JsonNode sub : parts) {
                collectAttachments(sub, attachments);
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}
