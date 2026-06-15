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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import ai.kompile.core.graphrag.GraphConstants;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Parses Gmail API message JSON (format=full) into Spring AI Documents.
 * Handles multipart message bodies, header extraction, attachment metadata,
 * and label resolution.
 */
@Slf4j
public class GmailMessageParser {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    /** Holds both text and raw HTML extracted from the message body. */
    record BodyContent(String text, String html) {}

    /**
     * Parses a Gmail API full-format message into a Spring AI Document.
     *
     * @param messageJson The full message JSON from Gmail API
     * @return a Document with email content as text and rich metadata
     */
    public Document parse(JsonNode messageJson) {
        String messageId = messageJson.get("id").asText();
        String threadId = messageJson.get("threadId").asText();

        JsonNode payload = messageJson.get("payload");
        Map<String, String> headers = extractHeaders(payload);
        Map<String, List<String>> multiHeaders = extractMultiValueHeaders(payload, "Received");

        BodyContent bodyContent = extractBodyContent(payload);
        String body = bodyContent.text();
        if (body == null || body.isBlank()) {
            body = messageJson.has("snippet") ? messageJson.get("snippet").asText() : "";
        }

        // Build structured content
        StringBuilder content = new StringBuilder();
        String subject = headers.getOrDefault("Subject", "(no subject)");
        content.append("Subject: ").append(subject).append("\n");
        content.append("From: ").append(headers.getOrDefault("From", "")).append("\n");
        content.append("To: ").append(headers.getOrDefault("To", "")).append("\n");
        if (headers.containsKey("Cc")) {
            content.append("Cc: ").append(headers.get("Cc")).append("\n");
        }
        if (headers.containsKey("Date")) {
            content.append("Date: ").append(headers.get("Date")).append("\n");
        }
        content.append("\n").append(body);

        // Build metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(GraphConstants.META_SOURCE, "gmail://messages/" + messageId);
        metadata.put(GraphConstants.META_SOURCE_PATH, "gmail://messages/" + messageId);
        metadata.put(GraphConstants.META_SOURCE_TYPE, "gmail");
        metadata.put(GraphConstants.META_FILE_NAME, subject != null && !subject.isEmpty() ? subject : "Gmail Message " + messageId);
        metadata.put(GraphConstants.META_LOADER, "Gmail Loader");
        metadata.put(GraphConstants.META_DOCUMENT_TYPE, "email");
        metadata.put("gmail.messageId", messageId);
        metadata.put("gmail.threadId", threadId);
        metadata.put("gmail.subject", subject);
        metadata.put("gmail.from", headers.getOrDefault("From", ""));
        metadata.put("gmail.to", headers.getOrDefault("To", ""));

        if (headers.containsKey("Cc")) {
            metadata.put("gmail.cc", headers.get("Cc"));
        }
        if (headers.containsKey("Bcc")) {
            metadata.put("gmail.bcc", headers.get("Bcc"));
        }
        if (headers.containsKey("Date")) {
            metadata.put("gmail.date", headers.get("Date"));
        }
        if (headers.containsKey("Reply-To")) {
            metadata.put("gmail.replyTo", headers.get("Reply-To"));
        }

        // Threading headers
        if (headers.containsKey("Message-ID")) {
            metadata.put("gmail.rfc822MessageId", headers.get("Message-ID"));
        }
        if (headers.containsKey("In-Reply-To")) {
            metadata.put("gmail.inReplyTo", headers.get("In-Reply-To"));
        }
        if (headers.containsKey("References")) {
            metadata.put("gmail.references", headers.get("References"));
        }
        if (headers.containsKey("List-Id")) {
            metadata.put("gmail.listId", headers.get("List-Id"));
        }
        // Thread-Topic header (set by Outlook and other email clients for conversation tracking)
        if (headers.containsKey("Thread-Topic")) {
            metadata.put("gmail.threadTopic", headers.get("Thread-Topic"));
        }

        // Internal date (epoch millis from Gmail)
        if (messageJson.has("internalDate")) {
            long epochMillis = messageJson.get("internalDate").asLong();
            metadata.put("gmail.internalDate",
                    ISO_FORMATTER.format(Instant.ofEpochMilli(epochMillis)));
        }

        // Labels
        if (messageJson.has("labelIds") && messageJson.get("labelIds").isArray()) {
            List<String> labels = new ArrayList<>();
            messageJson.get("labelIds").forEach(l -> labels.add(l.asText()));
            metadata.put("gmail.labels", labels);
        }

        // Size estimate
        if (messageJson.has("sizeEstimate")) {
            metadata.put("gmail.sizeEstimate", messageJson.get("sizeEstimate").asInt());
        }

        // Attachment metadata
        List<Map<String, Object>> attachments = extractAttachmentMetadata(payload);
        if (!attachments.isEmpty()) {
            metadata.put("gmail.attachmentCount", attachments.size());
            metadata.put("gmail.attachments", attachments);
            // Set email.attachmentNames for CrossDocumentRelationExtractor ATTACHMENT_OF edges
            List<String> attachmentNames = new ArrayList<>();
            for (Map<String, Object> att : attachments) {
                String fn = (String) att.get("filename");
                if (fn != null && !fn.isBlank()) attachmentNames.add(fn);
            }
            if (!attachmentNames.isEmpty()) {
                metadata.put(GraphConstants.META_EMAIL_ATTACHMENT_NAMES, attachmentNames);
            }
        }

        // Preserve raw HTML body for href URL extraction in graph extractor
        if (bodyContent.html() != null && !bodyContent.html().isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_HTML_BODY, bodyContent.html());
        }

        // Priority/importance from X-Priority, Importance, X-MSMail-Priority headers
        String priority = normalizePriority(
                headers.get("X-Priority"),
                headers.get("Importance"),
                headers.get("X-MSMail-Priority"));
        if (priority != null) {
            metadata.put(GraphConstants.META_EMAIL_PRIORITY, priority);
        }

        // DKIM/SPF/DMARC from Authentication-Results header
        String authResults = headers.get("Authentication-Results");
        if (authResults != null && !authResults.isBlank()) {
            parseAuthenticationResults(authResults, metadata);
        }

        // Return-Path header (envelope sender / bounce address)
        String returnPath = headers.get("Return-Path");
        if (returnPath != null && !returnPath.isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_RETURN_PATH, returnPath.trim());
        }

        // Auto-Submitted header (detect auto-replies, OOF, notifications)
        String autoSubmitted = headers.get("Auto-Submitted");
        if (autoSubmitted != null && !autoSubmitted.isBlank()) {
            String autoVal = autoSubmitted.trim();
            metadata.put(GraphConstants.META_EMAIL_AUTO_SUBMITTED, autoVal);
            if (!"no".equalsIgnoreCase(autoVal)) {
                metadata.put(GraphConstants.META_EMAIL_IS_AUTO_REPLY, true);
            }
        }

        // Precedence header (bulk, junk, list)
        String precedence = headers.get("Precedence");
        if (precedence != null && !precedence.isBlank()) {
            String prec = precedence.trim().toLowerCase();
            if ("bulk".equals(prec) || "junk".equals(prec) || "list".equals(prec)) {
                metadata.put(GraphConstants.META_EMAIL_PRECEDENCE, prec);
            }
        }

        // X-Mailer / User-Agent headers (email client identification)
        String xMailer = headers.get("X-Mailer");
        if (xMailer != null && !xMailer.isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_MAILER, xMailer.trim());
        }
        String userAgent = headers.get("User-Agent");
        if (userAgent != null && !userAgent.isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_USER_AGENT, userAgent.trim());
        }

        // Received headers (multi-value — one per mail hop)
        List<String> receivedHeaders = multiHeaders.get("Received");
        if (receivedHeaders != null && !receivedHeaders.isEmpty()) {
            metadata.put(GraphConstants.META_EMAIL_RECEIVED_HEADERS, receivedHeaders);
        }

        // List-Unsubscribe header (mailing list opt-out URL/email)
        String listUnsubscribe = headers.get("List-Unsubscribe");
        if (listUnsubscribe != null && !listUnsubscribe.isBlank()) {
            metadata.put(GraphConstants.META_EMAIL_LIST_UNSUBSCRIBE, listUnsubscribe.trim());
        }

        // Inline CID images from multipart/related parts
        List<Map<String, String>> inlineImages = extractInlineImages(payload);
        if (!inlineImages.isEmpty()) {
            metadata.put(GraphConstants.META_EMAIL_INLINE_IMAGES, inlineImages);
        }

        return new Document(content.toString(), metadata);
    }

    /**
     * Extracts headers from the message payload into a simple map.
     * Note: for multi-value headers like Received, only the last value is kept
     * in this map; use {@link #extractMultiValueHeaders(JsonNode, String...)}
     * to get all values.
     */
    Map<String, String> extractHeaders(JsonNode payload) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (payload == null || !payload.has("headers")) return headers;

        for (JsonNode header : payload.get("headers")) {
            String name = header.get("name").asText();
            String value = header.get("value").asText();
            headers.put(name, value);
        }
        return headers;
    }

    /**
     * Extracts all values for multi-value headers (e.g., Received).
     * Returns a map from header name → list of values, preserving order.
     */
    Map<String, List<String>> extractMultiValueHeaders(JsonNode payload, String... headerNames) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (payload == null || !payload.has("headers")) return result;
        Set<String> wanted = new HashSet<>(Arrays.asList(headerNames));
        for (JsonNode header : payload.get("headers")) {
            String name = header.get("name").asText();
            if (wanted.contains(name)) {
                result.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(header.get("value").asText());
            }
        }
        return result;
    }

    /**
     * Extracts both the text body and raw HTML body from a potentially nested multipart message.
     * Prefers text/plain for the text body, falls back to text/html with HTML stripping.
     * Preserves the raw HTML body separately for href URL extraction.
     */
    BodyContent extractBodyContent(JsonNode payload) {
        if (payload == null) return new BodyContent(null, null);

        String mimeType = payload.has("mimeType") ? payload.get("mimeType").asText() : "";

        // Direct body (non-multipart)
        if (!mimeType.startsWith("multipart/")) {
            JsonNode body = payload.get("body");
            if (body != null && body.has("data")) {
                String decoded = decodeBase64Url(body.get("data").asText());
                if ("text/html".equals(mimeType)) {
                    return new BodyContent(stripHtml(decoded), decoded);
                }
                return new BodyContent(decoded, null);
            }
            return new BodyContent(null, null);
        }

        // Multipart — walk parts looking for text/plain first, then text/html
        JsonNode parts = payload.get("parts");
        if (parts == null || !parts.isArray()) return new BodyContent(null, null);

        String plainText = null;
        String htmlText = null;

        for (JsonNode part : parts) {
            String partMime = part.has("mimeType") ? part.get("mimeType").asText() : "";

            if ("text/plain".equals(partMime)) {
                JsonNode body = part.get("body");
                if (body != null && body.has("data")) {
                    plainText = decodeBase64Url(body.get("data").asText());
                }
            } else if ("text/html".equals(partMime)) {
                JsonNode body = part.get("body");
                if (body != null && body.has("data")) {
                    htmlText = decodeBase64Url(body.get("data").asText());
                }
            } else if (partMime.startsWith("multipart/")) {
                // Recurse into nested multipart
                BodyContent nested = extractBodyContent(part);
                if (nested.text() != null && plainText == null) plainText = nested.text();
                if (nested.html() != null && htmlText == null) htmlText = nested.html();
            }
        }

        String text = plainText != null ? plainText : (htmlText != null ? stripHtml(htmlText) : null);
        return new BodyContent(text, htmlText);
    }

    /**
     * Extracts attachment metadata (name, mime type, size, attachment ID) from message parts.
     */
    List<Map<String, Object>> extractAttachmentMetadata(JsonNode payload) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        if (payload == null || !payload.has("parts")) return attachments;

        collectAttachments(payload.get("parts"), attachments);
        return attachments;
    }

    private void collectAttachments(JsonNode parts, List<Map<String, Object>> attachments) {
        if (parts == null || !parts.isArray()) return;

        for (JsonNode part : parts) {
            String filename = part.has("filename") ? part.get("filename").asText() : "";

            if (!filename.isBlank()) {
                Map<String, Object> att = new LinkedHashMap<>();
                att.put("filename", filename);
                att.put("mimeType", part.has("mimeType") ? part.get("mimeType").asText() : "application/octet-stream");

                JsonNode body = part.get("body");
                if (body != null) {
                    if (body.has("size")) {
                        att.put("size", body.get("size").asInt());
                    }
                    if (body.has("attachmentId")) {
                        att.put("attachmentId", body.get("attachmentId").asText());
                    }
                }
                attachments.add(att);
            }

            // Recurse into nested parts
            if (part.has("parts")) {
                collectAttachments(part.get("parts"), attachments);
            }
        }
    }

    /**
     * Extracts inline CID images from multipart message parts.
     * Looks for parts with image/* mimeType that have no filename (inline rendering parts).
     * Returns metadata compatible with {@link GraphConstants#META_EMAIL_INLINE_IMAGES}.
     */
    List<Map<String, String>> extractInlineImages(JsonNode payload) {
        List<Map<String, String>> images = new ArrayList<>();
        collectInlineImages(payload, images);
        return images;
    }

    private void collectInlineImages(JsonNode node, List<Map<String, String>> images) {
        if (node == null || images.size() >= 50) return;

        JsonNode parts = node.get("parts");
        if (parts == null || !parts.isArray()) return;

        for (JsonNode part : parts) {
            String partMime = part.has("mimeType") ? part.get("mimeType").asText() : "";
            String filename = part.has("filename") ? part.get("filename").asText() : "";

            // Inline image: has image/* mimeType, has Content-ID in headers, no/empty filename
            if (partMime.startsWith("image/") && filename.isBlank()) {
                String contentId = getHeaderValue(part, "Content-ID");
                if (contentId != null && !contentId.isBlank()) {
                    // Strip angle brackets: <cid123> → cid123
                    contentId = contentId.replaceAll("^<|>$", "").trim();
                    Map<String, String> cidInfo = new LinkedHashMap<>();
                    cidInfo.put("contentId", contentId);
                    cidInfo.put("mimeType", partMime);
                    // Try to extract name= from Content-Type header
                    String ctHeader = getHeaderValue(part, "Content-Type");
                    if (ctHeader != null) {
                        java.util.regex.Matcher nameMatcher =
                                java.util.regex.Pattern.compile("name=\"?([^\";\r\n]+)\"?")
                                        .matcher(ctHeader);
                        if (nameMatcher.find()) {
                            cidInfo.put("filename", nameMatcher.group(1).trim());
                        }
                    }
                    images.add(cidInfo);
                }
            }

            // Recurse into nested multipart
            if (partMime.startsWith("multipart/") || part.has("parts")) {
                collectInlineImages(part, images);
            }
        }
    }

    /** Extracts a header value from a part's headers array. */
    private String getHeaderValue(JsonNode part, String headerName) {
        JsonNode headers = part.get("headers");
        if (headers == null || !headers.isArray()) return null;
        for (JsonNode h : headers) {
            if (headerName.equalsIgnoreCase(h.has("name") ? h.get("name").asText() : "")) {
                return h.has("value") ? h.get("value").asText() : null;
            }
        }
        return null;
    }

    /**
     * Normalizes email priority from various headers into a single value.
     * X-Priority: 1=highest, 2=high, 3=normal, 4=low, 5=lowest
     * Importance: high, normal, low
     * X-MSMail-Priority: High, Normal, Low
     */
    static String normalizePriority(String xPriority, String importance, String xMsMailPriority) {
        // X-Priority is most specific — use it first
        if (xPriority != null && !xPriority.isBlank()) {
            String num = xPriority.trim().split("\\s+")[0]; // "1 (Highest)" → "1"
            return switch (num) {
                case "1" -> "highest";
                case "2" -> "high";
                case "3" -> "normal";
                case "4" -> "low";
                case "5" -> "lowest";
                default -> xPriority.trim().toLowerCase();
            };
        }
        if (importance != null && !importance.isBlank()) {
            return importance.trim().toLowerCase();
        }
        if (xMsMailPriority != null && !xMsMailPriority.isBlank()) {
            return xMsMailPriority.trim().toLowerCase();
        }
        return null;
    }

    /**
     * Parses the Authentication-Results header for DKIM, SPF, and DMARC results.
     * Format: "mx.google.com; dkim=pass header.d=example.com; spf=pass ...; dmarc=pass ..."
     */
    static void parseAuthenticationResults(String authResults, Map<String, Object> metadata) {
        if (authResults == null || authResults.isBlank()) return;
        String lower = authResults.toLowerCase();

        // Extract dkim=pass/fail/...
        java.util.regex.Matcher dkimMatcher =
                java.util.regex.Pattern.compile("\\bdkim=(\\w+)").matcher(lower);
        if (dkimMatcher.find()) {
            metadata.put(GraphConstants.META_EMAIL_DKIM_RESULT, dkimMatcher.group(1));
        }

        // Extract spf=pass/fail/...
        java.util.regex.Matcher spfMatcher =
                java.util.regex.Pattern.compile("\\bspf=(\\w+)").matcher(lower);
        if (spfMatcher.find()) {
            metadata.put(GraphConstants.META_EMAIL_SPF_RESULT, spfMatcher.group(1));
        }

        // Extract dmarc=pass/fail/...
        java.util.regex.Matcher dmarcMatcher =
                java.util.regex.Pattern.compile("\\bdmarc=(\\w+)").matcher(lower);
        if (dmarcMatcher.find()) {
            metadata.put(GraphConstants.META_EMAIL_DMARC_RESULT, dmarcMatcher.group(1));
        }
    }

    private String decodeBase64Url(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Strips HTML tags and decodes common entities. Collapses whitespace.
     */
    static String stripHtml(String html) {
        if (html == null) return null;
        String text = html
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</(p|div|li|tr|h[1-6])>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        // Collapse multiple blank lines
        text = text.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return text.strip();
    }
}
