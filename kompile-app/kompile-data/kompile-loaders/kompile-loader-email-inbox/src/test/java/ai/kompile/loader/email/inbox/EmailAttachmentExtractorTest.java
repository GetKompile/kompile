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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailAttachmentExtractorTest {

    private EmailAttachmentExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        extractor = new EmailAttachmentExtractor(tempDir);
    }

    // ── Text attachment extraction ────────────────────────────────────────

    private static final String EMAIL_WITH_TEXT_ATTACHMENT =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: With Text Attachment\r\n" +
            "Message-ID: <msg001@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"mix-boundary\"\r\n" +
            "\r\n" +
            "--mix-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "See attached notes.\r\n" +
            "--mix-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8; name=\"notes.txt\"\r\n" +
            "Content-Disposition: attachment; filename=\"notes.txt\"\r\n" +
            "\r\n" +
            "These are important meeting notes.\r\n" +
            "Action items:\r\n" +
            "1. Review design doc\r\n" +
            "2. Submit PR\r\n" +
            "--mix-boundary--\r\n";

    @Test
    void extractsTextAttachmentToFile() throws Exception {
        Message message = parseMessage(EMAIL_WITH_TEXT_ATTACHMENT);
        Map<String, Object> parentMeta = Map.of(
                "email.messageId", "<msg001@example.com>",
                "email.subject", "With Text Attachment"
        );

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/msg.eml", parentMeta);

        assertEquals(1, attachments.size());

        EmailAttachmentExtractor.ExtractedAttachment att = attachments.get(0);
        assertEquals("notes.txt", att.originalFilename());
        assertEquals("text/plain", att.mimeType());
        assertTrue(att.sizeBytes() > 0);
        assertTrue(Files.exists(att.tempFile()));

        String content = Files.readString(att.tempFile());
        assertTrue(content.contains("important meeting notes"));
        assertTrue(content.contains("Submit PR"));
    }

    @Test
    void textAttachmentHasCorrectExtension() throws Exception {
        Message message = parseMessage(EMAIL_WITH_TEXT_ATTACHMENT);

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/msg.eml", Map.of());

        assertFalse(attachments.isEmpty());
        assertTrue(attachments.get(0).tempFile().toString().endsWith(".txt"),
                "Temp file should have .txt extension");
    }

    // ── Source descriptor ────────────────────────────────────────────────

    @Test
    void attachmentHasCorrectSourceDescriptor() throws Exception {
        Message message = parseMessage(EMAIL_WITH_TEXT_ATTACHMENT);
        Map<String, Object> parentMeta = Map.of(
                "email.messageId", "<msg001@example.com>",
                "email.subject", "With Text Attachment",
                "email.from", "alice@example.com"
        );

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/msg.eml", parentMeta);

        assertEquals(1, attachments.size());
        DocumentSourceDescriptor desc = attachments.get(0).sourceDescriptor();

        assertEquals(DocumentSourceDescriptor.SourceType.FILE, desc.getType());
        assertTrue(desc.getPathOrUrl().endsWith(".txt"));
        assertTrue(Files.exists(Path.of(desc.getPathOrUrl())));
        assertEquals("notes.txt", desc.getOriginalFileName());
        assertEquals("/test/msg.eml#attachment:notes.txt", desc.getSourceId());

        // Metadata linking back to parent email
        Map<String, Object> meta = desc.getMetadata();
        assertTrue((Boolean) meta.get("email.isAttachment"));
        assertEquals("EMAIL_ATTACHMENT", meta.get("source_type"));
        assertEquals("<msg001@example.com>", meta.get("email.parentMessageId"));
        assertEquals("With Text Attachment", meta.get("email.parentSubject"));
        assertEquals("alice@example.com", meta.get("email.parentFrom"));
        assertEquals("notes.txt", meta.get("email.attachmentName"));
        assertEquals("text/plain", meta.get("email.attachmentMimeType"));
    }

    // ── Binary attachment ────────────────────────────────────────────────

    private static final String EMAIL_WITH_BINARY_ATTACHMENT =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: PDF Report\r\n" +
            "Message-ID: <msg002@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"bin-boundary\"\r\n" +
            "\r\n" +
            "--bin-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Please review the attached report.\r\n" +
            "--bin-boundary\r\n" +
            "Content-Type: application/octet-stream; name=\"data.bin\"\r\n" +
            "Content-Disposition: attachment; filename=\"data.bin\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "SGVsbG8gV29ybGQhIFRoaXMgaXMgYSB0ZXN0IGJpbmFyeSBmaWxlLg==\r\n" +
            "--bin-boundary--\r\n";

    @Test
    void extractsBinaryAttachment() throws Exception {
        Message message = parseMessage(EMAIL_WITH_BINARY_ATTACHMENT);

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/binary.eml", Map.of());

        assertEquals(1, attachments.size());

        EmailAttachmentExtractor.ExtractedAttachment att = attachments.get(0);
        assertEquals("data.bin", att.originalFilename());
        assertTrue(att.sizeBytes() > 0);
        assertTrue(Files.exists(att.tempFile()));

        // Verify the content was decoded from base64
        byte[] content = Files.readAllBytes(att.tempFile());
        String text = new String(content, StandardCharsets.UTF_8);
        assertTrue(text.contains("Hello World!"));
    }

    // ── Multiple attachments ─────────────────────────────────────────────

    private static final String EMAIL_WITH_MULTIPLE_ATTACHMENTS =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Multiple Files\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"multi-boundary\"\r\n" +
            "\r\n" +
            "--multi-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Here are the files.\r\n" +
            "--multi-boundary\r\n" +
            "Content-Type: text/plain; name=\"readme.txt\"\r\n" +
            "Content-Disposition: attachment; filename=\"readme.txt\"\r\n" +
            "\r\n" +
            "Read me first.\r\n" +
            "--multi-boundary\r\n" +
            "Content-Type: text/csv; name=\"data.csv\"\r\n" +
            "Content-Disposition: attachment; filename=\"data.csv\"\r\n" +
            "\r\n" +
            "name,value\r\n" +
            "alpha,1\r\n" +
            "beta,2\r\n" +
            "--multi-boundary--\r\n";

    @Test
    void extractsMultipleAttachments() throws Exception {
        Message message = parseMessage(EMAIL_WITH_MULTIPLE_ATTACHMENTS);

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/multi.eml", Map.of());

        assertEquals(2, attachments.size());

        // Verify both files exist and have the right content
        EmailAttachmentExtractor.ExtractedAttachment txt = attachments.stream()
                .filter(a -> "readme.txt".equals(a.originalFilename()))
                .findFirst().orElse(null);
        assertNotNull(txt);
        assertTrue(Files.readString(txt.tempFile()).contains("Read me first"));

        EmailAttachmentExtractor.ExtractedAttachment csv = attachments.stream()
                .filter(a -> "data.csv".equals(a.originalFilename()))
                .findFirst().orElse(null);
        assertNotNull(csv);
        assertTrue(Files.readString(csv.tempFile()).contains("alpha,1"));
        assertTrue(csv.tempFile().toString().endsWith(".csv"));
    }

    // ── No attachments ───────────────────────────────────────────────────

    private static final String EMAIL_NO_ATTACHMENT =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Plain text\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "No attachments here.\r\n";

    @Test
    void noAttachmentsReturnsEmptyList() throws Exception {
        Message message = parseMessage(EMAIL_NO_ATTACHMENT);

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/plain.eml", Map.of());

        assertTrue(attachments.isEmpty());
    }

    // ── Null parent metadata ─────────────────────────────────────────────

    @Test
    void handlesNullParentMetadata() throws Exception {
        Message message = parseMessage(EMAIL_WITH_TEXT_ATTACHMENT);

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, "/test/msg.eml", null);

        assertEquals(1, attachments.size());
        // Should not have parent fields in metadata
        Map<String, Object> meta = attachments.get(0).sourceDescriptor().getMetadata();
        assertNull(meta.get("email.parentMessageId"));
        assertNull(meta.get("email.parentSubject"));
    }

    // ── Extension derivation ─────────────────────────────────────────────

    @Test
    void derivesExtensionFromFilename() {
        assertEquals("pdf", EmailAttachmentExtractor.deriveExtension("report.pdf", null));
        assertEquals("docx", EmailAttachmentExtractor.deriveExtension("document.docx", null));
        assertEquals("txt", EmailAttachmentExtractor.deriveExtension("notes.txt", null));
    }

    @Test
    void derivesExtensionFromMimeType() {
        assertEquals("pdf", EmailAttachmentExtractor.deriveExtension(null, "application/pdf"));
        assertEquals("docx", EmailAttachmentExtractor.deriveExtension(null,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("jpg", EmailAttachmentExtractor.deriveExtension(null, "image/jpeg"));
        assertEquals("csv", EmailAttachmentExtractor.deriveExtension(null, "text/csv"));
        assertNull(EmailAttachmentExtractor.deriveExtension(null, "application/x-custom"));
    }

    @Test
    void filenameExtensionTakesPrecedence() {
        // Filename extension should win over MIME type
        assertEquals("pdf", EmailAttachmentExtractor.deriveExtension("doc.pdf", "application/octet-stream"));
    }

    @Test
    void handlesNoFilenameNoMimeType() {
        assertNull(EmailAttachmentExtractor.deriveExtension(null, null));
        assertNull(EmailAttachmentExtractor.deriveExtension("noext", null));
    }

    // ── Temp directory ───────────────────────────────────────────────────

    @Test
    void usesProvidedTempDirectory() throws Exception {
        Path customTempDir = tempDir.resolve("custom-attachments");
        Files.createDirectories(customTempDir);

        EmailAttachmentExtractor customExtractor = new EmailAttachmentExtractor(customTempDir);
        assertEquals(customTempDir, customExtractor.getTempDir());

        Message message = parseMessage(EMAIL_WITH_TEXT_ATTACHMENT);
        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                customExtractor.extractAttachments(message, "/test/msg.eml", Map.of());

        assertFalse(attachments.isEmpty());
        assertTrue(attachments.get(0).tempFile().startsWith(customTempDir),
                "Temp file should be in the custom temp directory");
    }

    @Test
    void defaultConstructorCreatesTempDir() {
        EmailAttachmentExtractor defaultExtractor = new EmailAttachmentExtractor();
        assertNotNull(defaultExtractor.getTempDir());
        assertTrue(Files.isDirectory(defaultExtractor.getTempDir()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Message parseMessage(String raw) throws Exception {
        DefaultMessageBuilder builder = new DefaultMessageBuilder();
        return builder.parseMessage(
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
