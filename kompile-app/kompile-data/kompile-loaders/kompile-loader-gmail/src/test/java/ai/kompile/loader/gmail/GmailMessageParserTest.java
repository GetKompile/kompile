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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GmailMessageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GmailMessageParser parser;

    @BeforeEach
    void setUp() {
        parser = new GmailMessageParser();
    }

    // ── parse() — plain text messages ───────────────────────────────────

    @Test
    void parsesPlainTextMessage() {
        ObjectNode msg = buildMessage("msg001", "thread001", "text/plain", "Hello, world!");
        addHeader(msg, "Subject", "Test Subject");
        addHeader(msg, "From", "alice@example.com");
        addHeader(msg, "To", "bob@example.com");

        Document doc = parser.parse(msg);

        assertTrue(doc.getText().contains("Subject: Test Subject"));
        assertTrue(doc.getText().contains("From: alice@example.com"));
        assertTrue(doc.getText().contains("To: bob@example.com"));
        assertTrue(doc.getText().contains("Hello, world!"));

        assertEquals("gmail", doc.getMetadata().get("source_type"));
        assertEquals("msg001", doc.getMetadata().get("gmail.messageId"));
        assertEquals("thread001", doc.getMetadata().get("gmail.threadId"));
        assertEquals("Test Subject", doc.getMetadata().get("gmail.subject"));
        assertEquals("alice@example.com", doc.getMetadata().get("gmail.from"));
        assertEquals("bob@example.com", doc.getMetadata().get("gmail.to"));
        assertEquals("gmail://messages/msg001", doc.getMetadata().get("source"));
    }

    @Test
    void parsesHtmlMessageWithStripping() {
        String html = "<html><body><p>Hello <b>world</b></p><br><p>Paragraph two</p></body></html>";
        ObjectNode msg = buildMessage("msg002", "thread001", "text/html", html);
        addHeader(msg, "Subject", "HTML Email");
        addHeader(msg, "From", "sender@test.com");
        addHeader(msg, "To", "receiver@test.com");

        Document doc = parser.parse(msg);

        assertFalse(doc.getText().contains("<html>"));
        assertFalse(doc.getText().contains("<b>"));
        assertTrue(doc.getText().contains("Hello world"));
        assertTrue(doc.getText().contains("Paragraph two"));
    }

    // ── parse() — multipart messages ────────────────────────────────────

    @Test
    void parsesMultipartAlternativePreferringPlainText() {
        ObjectNode msg = buildMultipartMessage("msg003", "thread002",
                "multipart/alternative",
                "This is the plain text version.",
                "<p>This is the <b>HTML</b> version.</p>");
        addHeader(msg, "Subject", "Multipart Alt");
        addHeader(msg, "From", "alice@example.com");
        addHeader(msg, "To", "bob@example.com");

        Document doc = parser.parse(msg);

        assertTrue(doc.getText().contains("This is the plain text version."));
        assertFalse(doc.getText().contains("<b>HTML</b>"));
    }

    @Test
    void multipartFallsBackToHtmlWhenNoPlainText() {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", "msg004");
        msg.put("threadId", "thread003");
        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", "multipart/alternative");
        ArrayNode headers = payload.putArray("headers");
        addHeaderNode(headers, "Subject", "HTML Only");
        addHeaderNode(headers, "From", "sender@test.com");
        addHeaderNode(headers, "To", "receiver@test.com");

        ArrayNode parts = payload.putArray("parts");
        // Only HTML part, no text/plain
        ObjectNode htmlPart = parts.addObject();
        htmlPart.put("mimeType", "text/html");
        htmlPart.putObject("body").put("data", base64Url("<p>Only HTML here</p>"));

        Document doc = parser.parse(msg);

        assertTrue(doc.getText().contains("Only HTML here"));
        assertFalse(doc.getText().contains("<p>"));
    }

    // ── parse() — headers and metadata ──────────────────────────────────

    @Test
    void extractsThreadingHeaders() {
        ObjectNode msg = buildMessage("msg005", "thread004", "text/plain", "Body");
        addHeader(msg, "Subject", "Re: Thread");
        addHeader(msg, "From", "bob@example.com");
        addHeader(msg, "To", "alice@example.com");
        addHeader(msg, "Message-ID", "<abc123@mail.example.com>");
        addHeader(msg, "In-Reply-To", "<xyz789@mail.example.com>");
        addHeader(msg, "References", "<xyz789@mail.example.com> <def456@mail.example.com>");

        Document doc = parser.parse(msg);

        assertEquals("<abc123@mail.example.com>", doc.getMetadata().get("gmail.rfc822MessageId"));
        assertEquals("<xyz789@mail.example.com>", doc.getMetadata().get("gmail.inReplyTo"));
        assertEquals("<xyz789@mail.example.com> <def456@mail.example.com>",
                doc.getMetadata().get("gmail.references"));
    }

    @Test
    void extractsCcAndBccHeaders() {
        ObjectNode msg = buildMessage("msg006", "thread005", "text/plain", "Body");
        addHeader(msg, "Subject", "CC test");
        addHeader(msg, "From", "sender@test.com");
        addHeader(msg, "To", "main@test.com");
        addHeader(msg, "Cc", "cc1@test.com, cc2@test.com");
        addHeader(msg, "Bcc", "secret@test.com");

        Document doc = parser.parse(msg);

        assertEquals("cc1@test.com, cc2@test.com", doc.getMetadata().get("gmail.cc"));
        assertEquals("secret@test.com", doc.getMetadata().get("gmail.bcc"));
        assertTrue(doc.getText().contains("Cc: cc1@test.com, cc2@test.com"));
    }

    @Test
    void extractsLabels() {
        ObjectNode msg = buildMessage("msg007", "thread006", "text/plain", "Body");
        addHeader(msg, "Subject", "Labels");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");
        ArrayNode labels = msg.putArray("labelIds");
        labels.add("INBOX");
        labels.add("IMPORTANT");
        labels.add("CATEGORY_PERSONAL");

        Document doc = parser.parse(msg);

        @SuppressWarnings("unchecked")
        List<String> docLabels = (List<String>) doc.getMetadata().get("gmail.labels");
        assertNotNull(docLabels);
        assertEquals(3, docLabels.size());
        assertTrue(docLabels.contains("INBOX"));
        assertTrue(docLabels.contains("IMPORTANT"));
        assertTrue(docLabels.contains("CATEGORY_PERSONAL"));
    }

    @Test
    void extractsInternalDate() {
        ObjectNode msg = buildMessage("msg008", "thread007", "text/plain", "Body");
        addHeader(msg, "Subject", "Date");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");
        msg.put("internalDate", "1700000000000"); // 2023-11-14T22:13:20Z

        Document doc = parser.parse(msg);

        String internalDate = (String) doc.getMetadata().get("gmail.internalDate");
        assertNotNull(internalDate);
        assertTrue(internalDate.contains("2023-11-14"));
    }

    @Test
    void extractsSizeEstimate() {
        ObjectNode msg = buildMessage("msg009", "thread008", "text/plain", "Body");
        addHeader(msg, "Subject", "Size");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");
        msg.put("sizeEstimate", 4096);

        Document doc = parser.parse(msg);

        assertEquals(4096, doc.getMetadata().get("gmail.sizeEstimate"));
    }

    // ── parse() — snippet fallback ──────────────────────────────────────

    @Test
    void usesSnippetWhenBodyIsEmpty() {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", "msg010");
        msg.put("threadId", "thread009");
        msg.put("snippet", "This is the snippet preview...");
        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", "text/plain");
        // No body data
        ArrayNode headers = payload.putArray("headers");
        addHeaderNode(headers, "Subject", "Snippet Test");
        addHeaderNode(headers, "From", "a@b.com");
        addHeaderNode(headers, "To", "c@d.com");

        Document doc = parser.parse(msg);

        assertTrue(doc.getText().contains("This is the snippet preview..."));
    }

    @Test
    void missingSubjectDefaultsToNoSubject() {
        ObjectNode msg = buildMessage("msg011", "thread010", "text/plain", "Body");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");
        // No Subject header

        Document doc = parser.parse(msg);

        assertTrue(doc.getText().contains("Subject: (no subject)"));
        assertEquals("(no subject)", doc.getMetadata().get("gmail.subject"));
    }

    // ── parse() — attachments ───────────────────────────────────────────

    @Test
    void extractsAttachmentMetadata() {
        ObjectNode msg = buildMessageWithAttachment("msg012", "thread011",
                "report.pdf", "application/pdf", "att-id-001", 102400);
        addHeader(msg, "Subject", "With attachment");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");

        Document doc = parser.parse(msg);

        assertEquals(1, doc.getMetadata().get("gmail.attachmentCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) doc.getMetadata().get("gmail.attachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());

        Map<String, Object> att = attachments.get(0);
        assertEquals("report.pdf", att.get("filename"));
        assertEquals("application/pdf", att.get("mimeType"));
        assertEquals(102400, att.get("size"));
        assertEquals("att-id-001", att.get("attachmentId"));
    }

    @Test
    void noAttachmentsWhenPartsHaveNoFilenames() {
        ObjectNode msg = buildMultipartMessage("msg013", "thread012",
                "multipart/alternative", "Plain text", "<p>HTML</p>");
        addHeader(msg, "Subject", "No attachments");
        addHeader(msg, "From", "a@b.com");
        addHeader(msg, "To", "c@d.com");

        Document doc = parser.parse(msg);

        assertNull(doc.getMetadata().get("gmail.attachmentCount"));
        assertNull(doc.getMetadata().get("gmail.attachments"));
    }

    // ── extractHeaders() ────────────────────────────────────────────────

    @Test
    void extractHeadersFromPayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode headers = payload.putArray("headers");
        addHeaderNode(headers, "From", "test@example.com");
        addHeaderNode(headers, "Subject", "Hello");
        addHeaderNode(headers, "X-Custom", "custom-value");

        Map<String, String> result = parser.extractHeaders(payload);

        assertEquals("test@example.com", result.get("From"));
        assertEquals("Hello", result.get("Subject"));
        assertEquals("custom-value", result.get("X-Custom"));
    }

    @Test
    void extractHeadersReturnsEmptyForNullPayload() {
        Map<String, String> result = parser.extractHeaders(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void extractHeadersReturnsEmptyForPayloadWithoutHeaders() {
        ObjectNode payload = MAPPER.createObjectNode();
        Map<String, String> result = parser.extractHeaders(payload);
        assertTrue(result.isEmpty());
    }

    // ── extractBody() ───────────────────────────────────────────────────

    @Test
    void extractBodyFromDirectTextPayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("mimeType", "text/plain");
        payload.putObject("body").put("data", base64Url("Direct body text"));

        String body = parser.extractBodyContent(payload).text();

        assertEquals("Direct body text", body);
    }

    @Test
    void extractBodyReturnsNullForNullPayload() {
        assertNull(parser.extractBodyContent(null).text());
    }

    @Test
    void extractBodyReturnsNullForPayloadWithNoData() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("mimeType", "text/plain");
        // No body or body.data

        assertNull(parser.extractBodyContent(payload).text());
    }

    @Test
    void extractBodyStripsHtmlForHtmlPayload() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("mimeType", "text/html");
        payload.putObject("body").put("data", base64Url("<p>Hello <b>World</b></p>"));

        String body = parser.extractBodyContent(payload).text();

        assertFalse(body.contains("<p>"));
        assertFalse(body.contains("<b>"));
        assertTrue(body.contains("Hello World"));
    }

    // ── extractAttachmentMetadata() ─────────────────────────────────────

    @Test
    void extractAttachmentMetadataFromMultipart() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("mimeType", "multipart/mixed");
        ArrayNode parts = payload.putArray("parts");

        ObjectNode textPart = parts.addObject();
        textPart.put("mimeType", "text/plain");
        textPart.put("filename", "");
        textPart.putObject("body").put("data", base64Url("Body"));

        ObjectNode attPart = parts.addObject();
        attPart.put("mimeType", "image/png");
        attPart.put("filename", "screenshot.png");
        ObjectNode attBody = attPart.putObject("body");
        attBody.put("size", 51200);
        attBody.put("attachmentId", "att-xyz");

        List<Map<String, Object>> result = parser.extractAttachmentMetadata(payload);

        assertEquals(1, result.size());
        assertEquals("screenshot.png", result.get(0).get("filename"));
        assertEquals("image/png", result.get(0).get("mimeType"));
        assertEquals(51200, result.get(0).get("size"));
        assertEquals("att-xyz", result.get(0).get("attachmentId"));
    }

    @Test
    void extractAttachmentMetadataReturnsEmptyForNullPayload() {
        assertTrue(parser.extractAttachmentMetadata(null).isEmpty());
    }

    @Test
    void extractAttachmentMetadataReturnsEmptyForPayloadWithNoParts() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("mimeType", "text/plain");
        assertTrue(parser.extractAttachmentMetadata(payload).isEmpty());
    }

    // ── stripHtml() ─────────────────────────────────────────────────────

    @Test
    void stripHtmlRemovesTags() {
        assertEquals("Hello world", GmailMessageParser.stripHtml("<p>Hello <b>world</b></p>"));
    }

    @Test
    void stripHtmlDecodesEntities() {
        assertEquals("A & B < C > D", GmailMessageParser.stripHtml("A &amp; B &lt; C &gt; D"));
    }

    @Test
    void stripHtmlConvertsBreaksToNewlines() {
        String result = GmailMessageParser.stripHtml("Line one<br>Line two<br/>Line three");
        assertTrue(result.contains("Line one\nLine two\nLine three"));
    }

    @Test
    void stripHtmlReturnsNullForNull() {
        assertNull(GmailMessageParser.stripHtml(null));
    }

    @Test
    void stripHtmlCollapsesMultipleBlankLines() {
        String result = GmailMessageParser.stripHtml("A\n\n\n\n\nB");
        assertFalse(result.contains("\n\n\n"));
    }

    @Test
    void stripHtmlDecodesQuotAndApos() {
        assertEquals("She said \"hello\" and 'goodbye'",
                GmailMessageParser.stripHtml("She said &quot;hello&quot; and &#39;goodbye&#39;"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ObjectNode buildMessage(String id, String threadId, String mimeType, String bodyText) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", id);
        msg.put("threadId", threadId);
        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", mimeType);
        payload.putObject("body").put("data", base64Url(bodyText));
        payload.putArray("headers"); // empty, caller adds via addHeader
        return msg;
    }

    private ObjectNode buildMultipartMessage(String id, String threadId, String multipartType,
                                              String plainBody, String htmlBody) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", id);
        msg.put("threadId", threadId);
        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", multipartType);
        payload.putArray("headers");

        ArrayNode parts = payload.putArray("parts");

        ObjectNode plainPart = parts.addObject();
        plainPart.put("mimeType", "text/plain");
        plainPart.put("filename", "");
        plainPart.putObject("body").put("data", base64Url(plainBody));

        ObjectNode htmlPart = parts.addObject();
        htmlPart.put("mimeType", "text/html");
        htmlPart.put("filename", "");
        htmlPart.putObject("body").put("data", base64Url(htmlBody));

        return msg;
    }

    private ObjectNode buildMessageWithAttachment(String id, String threadId,
                                                   String attachmentName, String attachmentMime,
                                                   String attachmentId, int attachmentSize) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("id", id);
        msg.put("threadId", threadId);
        ObjectNode payload = msg.putObject("payload");
        payload.put("mimeType", "multipart/mixed");
        payload.putArray("headers");

        ArrayNode parts = payload.putArray("parts");

        ObjectNode bodyPart = parts.addObject();
        bodyPart.put("mimeType", "text/plain");
        bodyPart.put("filename", "");
        bodyPart.putObject("body").put("data", base64Url("Email body"));

        ObjectNode attPart = parts.addObject();
        attPart.put("mimeType", attachmentMime);
        attPart.put("filename", attachmentName);
        ObjectNode attBody = attPart.putObject("body");
        attBody.put("size", attachmentSize);
        attBody.put("attachmentId", attachmentId);

        return msg;
    }

    private void addHeader(ObjectNode msg, String name, String value) {
        ArrayNode headers = (ArrayNode) msg.get("payload").get("headers");
        addHeaderNode(headers, name, value);
    }

    private void addHeaderNode(ArrayNode headers, String name, String value) {
        ObjectNode header = headers.addObject();
        header.put("name", name);
        header.put("value", value);
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
