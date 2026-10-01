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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MailLoaderImpl}'s MIME-aware body extraction: multipart/alternative
 * preference, nested multiparts, text attachments never replacing the body, forwarded
 * ({@code message/rfc822}) messages being quoted, and mbox producing one document per message.
 */
class MailLoaderImplBodyExtractionTest {

    private final MailLoaderImpl loader = new MailLoaderImpl();

    private DocumentSourceDescriptor descriptorFor(Path file) {
        return DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
    }

    private List<Document> loadEml(String emlContent, Path tempDir, String name) throws Exception {
        Path emlFile = tempDir.resolve(name);
        Files.writeString(emlFile, emlContent);
        return loader.load(descriptorFor(emlFile));
    }

    // =========================================================================
    // multipart/alternative
    // =========================================================================

    @Test
    void multipartAlternative_prefersPlainTextOverHtml(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Alternative Test\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"BOUNDARY123\"\r\n"
                + "\r\n"
                + "--BOUNDARY123\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "PLAIN_TEXT_BODY\r\n"
                + "--BOUNDARY123\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<html><body><p>HTML_ONLY_MARKER</p></body></html>\r\n"
                + "--BOUNDARY123--\r\n";

        List<Document> documents = loadEml(eml, tempDir, "alt-plain.eml");
        assertEquals(1, documents.size());
        String content = documents.get(0).getText();

        assertTrue(content.contains("PLAIN_TEXT_BODY"), "expected plain text body, got: " + content);
        assertFalse(content.contains("HTML_ONLY_MARKER"),
                "html alternative must not also appear when text/plain is present: " + content);
    }

    @Test
    void multipartAlternative_fallsBackToHtmlConvertedToText_whenNoPlainText(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Alternative HTML Only Test\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"BOUNDARY123\"\r\n"
                + "\r\n"
                + "--BOUNDARY123\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<html><body><p>HTML_CONVERTED_MARKER</p><br>Second line</body></html>\r\n"
                + "--BOUNDARY123--\r\n";

        List<Document> documents = loadEml(eml, tempDir, "alt-html.eml");
        assertEquals(1, documents.size());
        String content = documents.get(0).getText();

        assertTrue(content.contains("HTML_CONVERTED_MARKER"), "got: " + content);
        assertTrue(content.contains("Second line"), "got: " + content);
        assertFalse(content.contains("<p>"), "HTML tags must be stripped, got: " + content);
    }

    // =========================================================================
    // Nested multipart + attachments
    // =========================================================================

    @Test
    void nestedMultipartAndAttachment_attachmentNeverReplacesBody(@TempDir Path tempDir) throws Exception {
        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Nested And Attachment Test\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"OUTER\"\r\n"
                + "\r\n"
                + "--OUTER\r\n"
                + "Content-Type: multipart/alternative; boundary=\"INNER\"\r\n"
                + "\r\n"
                + "--INNER\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "NESTED_PLAIN_BODY\r\n"
                + "--INNER\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<p>should not appear</p>\r\n"
                + "--INNER--\r\n"
                + "--OUTER\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Disposition: attachment; filename=\"notes.txt\"\r\n"
                + "\r\n"
                + "ATTACHMENT_MARKER_TEXT\r\n"
                + "--OUTER--\r\n";

        List<Document> documents = loadEml(eml, tempDir, "nested-attachment.eml");
        assertEquals(1, documents.size());
        Document document = documents.get(0);
        String content = document.getText();

        assertTrue(content.contains("NESTED_PLAIN_BODY"), "got: " + content);
        assertFalse(content.contains("should not appear"),
                "the non-preferred alternative must not leak into the body: " + content);
        assertFalse(content.contains("ATTACHMENT_MARKER_TEXT"),
                "a text attachment must never replace or pollute the body: " + content);
        assertTrue(content.contains("notes.txt"),
                "the attachment should still be listed in an Attachments section: " + content);

        Map<String, Object> metadata = document.getMetadata();
        assertTrue(metadata.containsKey("email.attachments"), "metadata: " + metadata);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) metadata.get("email.attachments");
        assertEquals(1, attachments.size());
        assertEquals("notes.txt", attachments.get(0).get("name"));
        assertTrue(String.valueOf(attachments.get(0).get("type")).startsWith("text/plain"));
        assertTrue(((Number) attachments.get(0).get("size")).longValue() > 0);
    }

    // =========================================================================
    // Forwarded message (message/rfc822)
    // =========================================================================

    @Test
    void forwardedMessage_isQuotedNotInlined(@TempDir Path tempDir) throws Exception {
        String innerMessage = "From: Carol <carol@example.com>\r\n"
                + "To: Dave <dave@example.com>\r\n"
                + "Subject: Original Subject\r\n"
                + "Date: Mon, 1 Jan 2025 08:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "INNER_BODY_LINE\r\n";

        String eml = "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: Fwd: Original Subject\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"OUTER\"\r\n"
                + "\r\n"
                + "--OUTER\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "OUTER_INTRO_TEXT\r\n"
                + "--OUTER\r\n"
                + "Content-Type: message/rfc822\r\n"
                + "\r\n"
                + innerMessage
                + "--OUTER--\r\n";

        List<Document> documents = loadEml(eml, tempDir, "forwarded.eml");
        assertEquals(1, documents.size());
        String content = documents.get(0).getText();

        assertTrue(content.contains("OUTER_INTRO_TEXT"), "got: " + content);
        assertTrue(content.contains("---------- Forwarded message ----------"), "got: " + content);
        assertTrue(content.contains("Original Subject"), "got: " + content);
        assertTrue(content.contains("carol@example.com"), "got: " + content);
        assertTrue(content.contains("> INNER_BODY_LINE"),
                "forwarded body lines should be quote-prefixed with '> ': " + content);
    }

    // =========================================================================
    // mbox
    // =========================================================================

    @Test
    void mbox_multipleMessages_produceOneDocumentPerMessageWithOwnMetadata(@TempDir Path tempDir) throws Exception {
        String mbox = "From alice@example.com Mon Jan  1 08:00:00 2025\r\n"
                + "From: Alice <alice@example.com>\r\n"
                + "To: Bob <bob@example.com>\r\n"
                + "Subject: First Message\r\n"
                + "Date: Mon, 1 Jan 2025 08:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "FIRST_MESSAGE_BODY\r\n"
                + "From carol@example.com Tue Jan  2 09:00:00 2025\r\n"
                + "From: Carol <carol@example.com>\r\n"
                + "To: Dave <dave@example.com>\r\n"
                + "Subject: Second Message\r\n"
                + "Date: Tue, 2 Jan 2025 09:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "SECOND_MESSAGE_BODY\r\n";

        Path mboxFile = tempDir.resolve("test.mbox");
        Files.writeString(mboxFile, mbox);
        List<Document> documents = loader.load(descriptorFor(mboxFile));

        assertEquals(2, documents.size(), "expected one document per mbox message");

        Document first = documents.get(0);
        assertTrue(first.getText().contains("FIRST_MESSAGE_BODY"));
        assertEquals("First Message", first.getMetadata().get("email.subject"));
        assertTrue(String.valueOf(first.getMetadata().get("email.from")).contains("alice@example.com"));

        Document second = documents.get(1);
        assertTrue(second.getText().contains("SECOND_MESSAGE_BODY"));
        assertEquals("Second Message", second.getMetadata().get("email.subject"));
        assertTrue(String.valueOf(second.getMetadata().get("email.from")).contains("carol@example.com"));
    }
}
