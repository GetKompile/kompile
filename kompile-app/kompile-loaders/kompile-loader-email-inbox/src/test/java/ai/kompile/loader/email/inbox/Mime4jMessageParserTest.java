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

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Mime4jMessageParserTest {

    private static final String PLAIN_TEXT_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Cc: carol@example.com\r\n" +
            "Subject: Test Subject\r\n" +
            "Date: Thu, 15 May 2025 10:30:00 +0000\r\n" +
            "Message-ID: <msg001@example.com>\r\n" +
            "In-Reply-To: <msg000@example.com>\r\n" +
            "References: <msg000@example.com>\r\n" +
            "List-Id: <dev.example.com>\r\n" +
            "X-Mailer: TestMailer 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Hello Bob,\r\n" +
            "\r\n" +
            "This is a test email.\r\n";

    private static final String HTML_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: HTML Email\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><body><h1>Hello</h1><p>This is <b>bold</b> text.</p></body></html>\r\n";

    private static final String MULTIPART_ALTERNATIVE =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Multipart Alternative\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/alternative; boundary=\"alt-boundary\"\r\n" +
            "\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Plain text version.\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><body><p>HTML version.</p></body></html>\r\n" +
            "--alt-boundary--\r\n";

    private static final String MULTIPART_WITH_ATTACHMENT =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: With Attachment\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"mix-boundary\"\r\n" +
            "\r\n" +
            "--mix-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "See attached file.\r\n" +
            "--mix-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8; name=\"notes.txt\"\r\n" +
            "Content-Disposition: attachment; filename=\"notes.txt\"\r\n" +
            "\r\n" +
            "These are my notes.\r\n" +
            "--mix-boundary--\r\n";

    private static final String NAMED_SENDER_EMAIL =
            "From: Alice Smith <alice@example.com>\r\n" +
            "To: Bob Jones <bob@example.com>\r\n" +
            "Subject: Named Sender\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Hello.\r\n";

    // ── Plain text parsing ────────────────────────────────────────────────

    @Test
    void parsePlainTextEmail() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        List<Document> docs = parser.parse(toStream(PLAIN_TEXT_EMAIL), "/test/msg.eml");

        assertEquals(1, docs.size());
        Document doc = docs.get(0);

        String content = doc.getText();
        assertTrue(content.contains("Subject: Test Subject"), "Content should include subject line");
        assertTrue(content.contains("From: alice@example.com"), "Content should include from line");
        assertTrue(content.contains("To: bob@example.com"), "Content should include to line");
        assertTrue(content.contains("Cc: carol@example.com"), "Content should include cc line");
        assertTrue(content.contains("Hello Bob"), "Content should include body text");
        assertTrue(content.contains("This is a test email"), "Content should include body text");
    }

    @Test
    void extractsEmailMetadata() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(PLAIN_TEXT_EMAIL), "/test/msg.eml").get(0);

        assertEquals("Test Subject", doc.getMetadata().get("email.subject"));
        assertNotNull(doc.getMetadata().get("email.from"));
        assertTrue(doc.getMetadata().get("email.from").toString().contains("alice@example.com"));
        assertNotNull(doc.getMetadata().get("email.to"));
        assertTrue(doc.getMetadata().get("email.to").toString().contains("bob@example.com"));
        assertNotNull(doc.getMetadata().get("email.cc"));
        assertTrue(doc.getMetadata().get("email.cc").toString().contains("carol@example.com"));
        assertNotNull(doc.getMetadata().get("email.date"));
        assertEquals("/test/msg.eml", doc.getMetadata().get("source"));
        assertEquals("EMAIL_INBOX", doc.getMetadata().get("source_type"));
    }

    @Test
    void extractsThreadingHeaders() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(PLAIN_TEXT_EMAIL), "/test/msg.eml").get(0);

        assertEquals("<msg001@example.com>", doc.getMetadata().get("email.messageId"));
        assertEquals("<msg000@example.com>", doc.getMetadata().get("email.inReplyTo"));

        Object refs = doc.getMetadata().get("email.references");
        assertNotNull(refs, "References should be extracted");
        assertTrue(refs instanceof List);
        assertTrue(((List<?>) refs).contains("<msg000@example.com>"));
    }

    @Test
    void extractsMailingListAndMailerHeaders() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(PLAIN_TEXT_EMAIL), "/test/msg.eml").get(0);

        assertEquals("<dev.example.com>", doc.getMetadata().get("email.listId"));
        assertEquals("TestMailer 1.0", doc.getMetadata().get("email.mailer"));
    }

    @Test
    void parsesNamedSender() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(NAMED_SENDER_EMAIL), "/test/named.eml").get(0);

        String from = doc.getMetadata().get("email.from").toString();
        assertTrue(from.contains("Alice Smith"), "From should include display name");
        assertTrue(from.contains("alice@example.com"), "From should include address");
        assertEquals("Alice Smith", doc.getMetadata().get("email.fromName"));
        assertEquals("alice@example.com", doc.getMetadata().get("email.fromAddress"));
    }

    // ── HTML handling ─────────────────────────────────────────────────────

    @Test
    void parsesHtmlEmailWithConversion() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        Document doc = parser.parse(toStream(HTML_EMAIL), "/test/html.eml").get(0);

        String content = doc.getText();
        // HTML should be converted to text
        assertTrue(content.contains("Hello"), "Should contain heading text");
        assertTrue(content.contains("bold"), "Should contain bold text");
        assertFalse(content.contains("<html>"), "Should not contain raw HTML tags");
        assertFalse(content.contains("<b>"), "Should not contain formatting tags");
    }

    @Test
    void parsesHtmlEmailWithoutConversionStillStrips() throws IOException {
        // includeHtmlBody=false still strips tags via Jsoup.parse().text()
        Mime4jMessageParser parser = new Mime4jMessageParser(false, false);
        Document doc = parser.parse(toStream(HTML_EMAIL), "/test/html.eml").get(0);

        String content = doc.getText();
        assertTrue(content.contains("Hello"), "Should still extract text");
        assertFalse(content.contains("<html>"), "Should strip HTML tags");
    }

    // ── Multipart handling ────────────────────────────────────────────────

    @Test
    void multipartAlternativePrefersPlainText() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(MULTIPART_ALTERNATIVE), "/test/alt.eml").get(0);

        String content = doc.getText();
        assertTrue(content.contains("Plain text version"), "Should use plain text part");
    }

    @Test
    void multipartMixedExtractsBodyText() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        Document doc = parser.parse(toStream(MULTIPART_WITH_ATTACHMENT), "/test/attach.eml").get(0);

        String content = doc.getText();
        assertTrue(content.contains("See attached file"), "Body text should be extracted");
        // Attachment content should NOT appear in the main doc (includeAttachments=false)
        assertFalse(content.contains("These are my notes"), "Attachment text should not be in main doc");
    }

    // ── Attachment extraction ─────────────────────────────────────────────

    @Test
    void attachmentsExtractedWhenEnabled() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser(true, true);
        List<Document> docs = parser.parse(toStream(MULTIPART_WITH_ATTACHMENT), "/test/attach.eml");

        assertTrue(docs.size() >= 2, "Should have main doc + at least 1 attachment doc");

        Document mainDoc = docs.get(0);
        assertTrue(mainDoc.getText().contains("See attached file"));

        // Find the attachment doc
        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst()
                .orElse(null);
        assertNotNull(attachDoc, "Should have an attachment document");
        assertTrue(attachDoc.getText().contains("notes.txt"), "Attachment doc should reference filename");
        assertTrue(attachDoc.getText().contains("These are my notes"), "Text attachment should have content");
        assertEquals("notes.txt", attachDoc.getMetadata().get("email.attachmentName"));
        assertEquals("EMAIL_ATTACHMENT", attachDoc.getMetadata().get("source_type"));
    }

    @Test
    void attachmentsSkippedWhenDisabled() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(toStream(MULTIPART_WITH_ATTACHMENT), "/test/attach.eml");

        assertEquals(1, docs.size(), "Should only have the main document");
        assertFalse(docs.get(0).getText().contains("These are my notes"));
    }

    // ── Error handling ────────────────────────────────────────────────────

    @Test
    void malformedInputReturnsErrorDocument() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        // Completely invalid content - mime4j may still produce a minimal message
        // but let's test with something truly broken
        byte[] garbage = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        List<Document> docs = parser.parse(new ByteArrayInputStream(garbage), "/test/bad.eml");

        assertFalse(docs.isEmpty(), "Should always return at least one document");
    }

    @Test
    void emptyEmailProducesDocument() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        String empty = "From: a@b.com\r\nSubject: Empty\r\nContent-Type: text/plain\r\n\r\n";
        List<Document> docs = parser.parse(toStream(empty), "/test/empty.eml");

        assertEquals(1, docs.size());
        assertEquals("Empty", docs.get(0).getMetadata().get("email.subject"));
    }

    // ── Source path propagation ───────────────────────────────────────────

    @Test
    void sourcePathPropagatedToMetadata() throws IOException {
        Mime4jMessageParser parser = new Mime4jMessageParser();
        Document doc = parser.parse(toStream(PLAIN_TEXT_EMAIL), "/inbox/cur/12345:2,S").get(0);

        assertEquals("/inbox/cur/12345:2,S", doc.getMetadata().get("source"));
        assertEquals("Email Inbox Loader", doc.getMetadata().get("loader"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ByteArrayInputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
