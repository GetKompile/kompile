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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImapPopDocumentLoaderTest {

    private EmailConnectionFactory mockFactory;
    private ImapPopDocumentLoader loader;

    @BeforeEach
    void setUp() {
        mockFactory = mock(EmailConnectionFactory.class);
        loader = new ImapPopDocumentLoader(mockFactory);
    }

    // ── Identity ─────────────────────────────────────────────────────────

    @Test
    void nameReturnsExpectedValue() {
        assertEquals("IMAP/POP3 Email Loader", loader.getName());
    }

    // ── supports ─────────────────────────────────────────────────────────

    @Test
    void supportsEmailSourceType() {
        assertTrue(loader.supports(descriptor(SourceType.EMAIL)));
    }

    @Test
    void supportsImapSourceType() {
        assertTrue(loader.supports(descriptor(SourceType.IMAP)));
    }

    @Test
    void supportsPop3SourceType() {
        assertTrue(loader.supports(descriptor(SourceType.POP3)));
    }

    @Test
    void doesNotSupportUrlSourceType() {
        assertFalse(loader.supports(descriptor(SourceType.URL)));
    }

    @Test
    void doesNotSupportFileSourceType() {
        assertFalse(loader.supports(descriptor(SourceType.FILE)));
    }

    @Test
    void doesNotSupportMaildirSourceType() {
        assertFalse(loader.supports(descriptor(SourceType.MAILDIR)));
    }

    // ── load — single plain-text message ─────────────────────────────────

    @Test
    void loadsSinglePlainTextMessage() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Test Subject", "alice@example.com",
                "bob@example.com", "Hello, this is a test email.");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "alice@example.com",
                        "password", "secret"
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
        Document doc = docs.get(0);
        assertTrue(doc.getText().contains("Subject: Test Subject"));
        assertTrue(doc.getText().contains("Hello, this is a test email."));
        assertEquals("Test Subject", doc.getMetadata().get("email.subject"));
        assertEquals("INBOX", doc.getMetadata().get("email.folder"));
        assertEquals("EMAIL_INBOX", doc.getMetadata().get("source_type"));
        assertEquals("IMAP/POP3 Email Loader", doc.getMetadata().get("loader"));

        verify(mockFolder).open(Folder.READ_ONLY);
        verify(mockFolder).close(false);
    }

    // ── load — from address formatting ───────────────────────────────────

    @Test
    void extractsFromAddressWithPersonalName() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Hi", "alice@example.com",
                "bob@example.com", "body");
        msg.setFrom(new InternetAddress("alice@example.com", "Alice Smith"));
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        assertEquals(1, docs.size());
        assertEquals("Alice Smith <alice@example.com>", docs.get(0).getMetadata().get("email.from"));
        assertEquals("Alice Smith", docs.get(0).getMetadata().get("email.fromName"));
    }

    // ── load — recipients ────────────────────────────────────────────────

    @Test
    void extractsToAndCcRecipients() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Multi", "from@test.com",
                "to@test.com", "body");
        msg.addRecipient(Message.RecipientType.CC, new InternetAddress("cc@test.com"));
        msg.addRecipient(Message.RecipientType.BCC, new InternetAddress("bcc@test.com"));
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertNotNull(meta.get("email.to"));
        assertNotNull(meta.get("email.cc"));
        assertNotNull(meta.get("email.bcc"));
        assertTrue(((List<?>) meta.get("email.cc")).contains("cc@test.com"));
        assertTrue(((List<?>) meta.get("email.bcc")).contains("bcc@test.com"));
    }

    // ── load — threading headers ─────────────────────────────────────────

    @Test
    void extractsThreadingHeaders() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Reply", "a@test.com",
                "b@test.com", "body");
        msg.setHeader("In-Reply-To", "<original@test.com>");
        msg.setHeader("References", "<first@test.com> <second@test.com>");
        msg.setHeader("Thread-Index", "AQHxyz123");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("<original@test.com>", meta.get("email.inReplyTo"));
        assertNotNull(meta.get("email.references"));
        List<?> refs = (List<?>) meta.get("email.references");
        assertTrue(refs.contains("<first@test.com>"));
        assertTrue(refs.contains("<second@test.com>"));
        assertEquals("AQHxyz123", meta.get("email.conversationId"));
    }

    // ── load — multipart/alternative ─────────────────────────────────────

    @Test
    void prefersPlainTextOverHtml() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMultipartAlternativeMessage(
                "Alt Subject", "from@test.com", "to@test.com",
                "Plain text body", "<html><body><p>HTML body</p></body></html>");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getText().contains("Plain text body"));
        assertFalse(docs.get(0).getText().contains("<html>"));
    }

    @Test
    void fallsBackToHtmlWhenNoPlainText() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        // Create multipart with HTML only
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject("HTML Only");
        msg.setFrom(new InternetAddress("from@test.com"));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress("to@test.com"));
        MimeMultipart multipart = new MimeMultipart("alternative");
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><p>Only HTML content</p></body></html>", "text/html");
        multipart.addBodyPart(htmlPart);
        msg.setContent(multipart);
        msg.saveChanges();
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        assertEquals(1, docs.size());
        assertTrue(docs.get(0).getText().contains("Only HTML content"));
    }

    // ── load — attachments ───────────────────────────────────────────────

    @Test
    void extractsTextAttachments() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithTextAttachment(
                "Attachment Test", "from@test.com", "to@test.com",
                "See attached.", "notes.txt", "Important meeting notes.");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        // Default config includes attachments
        List<Document> docs = loader.load(descriptorWithDefaults());

        // Should have 1 email + 1 attachment
        assertEquals(2, docs.size());

        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElse(null);
        assertNotNull(attachDoc);
        assertTrue(attachDoc.getText().contains("Important meeting notes."));
        assertEquals("notes.txt", attachDoc.getMetadata().get("email.attachmentName"));
        assertEquals("EMAIL_ATTACHMENT", attachDoc.getMetadata().get("source_type"));
    }

    @Test
    void extractsBinaryAttachmentAsPlaceholder() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithBinaryAttachment(
                "PDF Attached", "from@test.com", "to@test.com",
                "See the PDF.", "report.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF header bytes
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        Document attachDoc = docs.stream()
                .filter(d -> Boolean.TRUE.equals(d.getMetadata().get("email.isAttachment")))
                .findFirst().orElse(null);
        assertNotNull(attachDoc);
        assertTrue(attachDoc.getText().contains("[Attachment: report.pdf"));
        assertEquals("report.pdf", attachDoc.getMetadata().get("email.attachmentName"));
    }

    @Test
    void skipsAttachmentsWhenDisabled() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createMessageWithTextAttachment(
                "Skip Attach", "from@test.com", "to@test.com",
                "See attached.", "notes.txt", "Notes content.");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "includeAttachments", false
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
        assertNull(docs.get(0).getMetadata().get("email.isAttachment"));
    }

    // ── load — multiple folders ──────────────────────────────────────────

    @Test
    void loadsFromMultipleFolders() throws Exception {
        Store mockStore = mock(Store.class);
        Folder inboxFolder = mock(Folder.class);
        Folder sentFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(inboxFolder);
        when(mockStore.getFolder("Sent")).thenReturn(sentFolder);
        when(inboxFolder.isOpen()).thenReturn(true);
        when(sentFolder.isOpen()).thenReturn(true);

        MimeMessage msg1 = createPlainTextMessage("Inbox Msg", "a@t.com", "b@t.com", "inbox");
        MimeMessage msg2 = createPlainTextMessage("Sent Msg", "b@t.com", "a@t.com", "sent");
        when(inboxFolder.getMessages()).thenReturn(new Message[]{msg1});
        when(sentFolder.getMessages()).thenReturn(new Message[]{msg2});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "folders", List.of("INBOX", "Sent")
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(2, docs.size());
        assertTrue(docs.stream().anyMatch(d -> "INBOX".equals(d.getMetadata().get("email.folder"))));
        assertTrue(docs.stream().anyMatch(d -> "Sent".equals(d.getMetadata().get("email.folder"))));
    }

    // ── load — folder not found ──────────────────────────────────────────

    @Test
    void skipsMissingFoldersGracefully() throws Exception {
        Store mockStore = mock(Store.class);
        Folder inboxFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(inboxFolder);
        when(mockStore.getFolder("NonExistent")).thenThrow(new FolderNotFoundException());
        when(inboxFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Msg", "a@t.com", "b@t.com", "body");
        when(inboxFolder.getMessages()).thenReturn(new Message[]{msg});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "folders", List.of("INBOX", "NonExistent")
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size());
    }

    // ── load — message limit ─────────────────────────────────────────────

    @Test
    void respectsMessageLimit() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        Message[] messages = new Message[10];
        for (int i = 0; i < 10; i++) {
            messages[i] = createPlainTextMessage("Msg " + i, "a@t.com", "b@t.com", "body " + i);
        }
        when(mockFolder.getMessages()).thenReturn(messages);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user",
                        "password", "pass",
                        "messageLimit", 3
                ))
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(3, docs.size());
    }

    // ── extractConfig — metadata mapping ─────────────────────────────────

    @Test
    void extractConfigSetsPop3ProtocolFromSourceType() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.POP3)
                .metadata(Map.of("host", "pop.example.com", "username", "u", "password", "p"))
                .build();

        // We verify the protocol by checking the source metadata in loaded docs
        loader.load(desc);

        // Should connect — if we get here without error, extractConfig worked for POP3
        verify(mockFactory).connect(argThat(config ->
                config.getProtocol() == EmailConnectionConfig.Protocol.POP3));
    }

    @Test
    void extractConfigAcceptsEmailAsUsernameAlias() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "email", "user@example.com",
                        "password", "pass"
                ))
                .build();

        loader.load(desc);

        verify(mockFactory).connect(argThat(config ->
                "user@example.com".equals(config.getUsername())));
    }

    @Test
    void extractConfigParsesSingleFolderString() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("Sent")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "u",
                        "password", "p",
                        "folders", "Sent"
                ))
                .build();

        loader.load(desc);

        verify(mockStore).getFolder("Sent");
    }

    @Test
    void extractConfigParsesDateStrings() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        // search() will be called instead of getMessages() when dates are set
        when(mockFolder.search(any())).thenReturn(new Message[]{});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("startDate", "2025-01-01T00:00:00");
        meta.put("endDate", "2025-12-31T23:59:59");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFolder).search(any());
    }

    @Test
    void extractConfigParsesLocalDateTimeObjects() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.search(any())).thenReturn(new Message[]{});

        Map<String, Object> meta = new HashMap<>();
        meta.put("host", "imap.example.com");
        meta.put("username", "u");
        meta.put("password", "p");
        meta.put("startDate", LocalDateTime.of(2025, 6, 1, 0, 0));

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(meta)
                .build();

        loader.load(desc);

        verify(mockFolder).search(any());
    }

    // ── source metadata ──────────────────────────────────────────────────

    @Test
    void sourceMetadataContainsProtocolAndHost() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);

        MimeMessage msg = createPlainTextMessage("Test", "a@t.com", "b@t.com", "body");
        when(mockFolder.getMessages()).thenReturn(new Message[]{msg});

        List<Document> docs = loader.load(descriptorWithDefaults());

        String source = (String) docs.get(0).getMetadata().get("source");
        assertTrue(source.startsWith("imap://imap.example.com/INBOX"),
                "source should start with protocol://host/folder, was: " + source);
        // source_path should also be set for graph pipeline linkage
        String sourcePath = (String) docs.get(0).getMetadata().get("source_path");
        assertNotNull(sourcePath, "source_path metadata must be set");
        assertEquals(source, sourcePath);
    }

    // ── load — empty metadata ────────────────────────────────────────────

    @Test
    void handlesNullMetadata() throws Exception {
        Store mockStore = mock(Store.class);
        Folder mockFolder = mock(Folder.class);
        when(mockFactory.connect(any())).thenReturn(mockStore);
        when(mockStore.getFolder("INBOX")).thenReturn(mockFolder);
        when(mockFolder.isOpen()).thenReturn(true);
        when(mockFolder.getMessages()).thenReturn(new Message[]{});

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .build();

        // Should not throw even with null metadata
        List<Document> docs = loader.load(desc);
        assertNotNull(docs);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private DocumentSourceDescriptor descriptor(SourceType type) {
        return DocumentSourceDescriptor.builder().type(type).build();
    }

    private DocumentSourceDescriptor descriptorWithDefaults() {
        return DocumentSourceDescriptor.builder()
                .type(SourceType.IMAP)
                .metadata(Map.of(
                        "host", "imap.example.com",
                        "username", "user@example.com",
                        "password", "password"
                ))
                .build();
    }

    private MimeMessage createPlainTextMessage(String subject, String from, String to, String body)
            throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());
        msg.setText(body, "UTF-8");
        msg.saveChanges();
        return msg;
    }

    private MimeMessage createMultipartAlternativeMessage(String subject, String from, String to,
                                                           String plainText, String htmlText) throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());

        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plainText, "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlText, "text/html; charset=UTF-8");

        multipart.addBodyPart(textPart);
        multipart.addBodyPart(htmlPart);
        msg.setContent(multipart);
        msg.saveChanges();
        return msg;
    }

    private MimeMessage createMessageWithTextAttachment(String subject, String from, String to,
                                                         String body, String attachmentName,
                                                         String attachmentContent) throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());

        MimeMultipart multipart = new MimeMultipart("mixed");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "UTF-8");
        multipart.addBodyPart(textPart);

        MimeBodyPart attachPart = new MimeBodyPart();
        attachPart.setText(attachmentContent, "UTF-8");
        attachPart.setFileName(attachmentName);
        attachPart.setDisposition(Part.ATTACHMENT);
        multipart.addBodyPart(attachPart);

        msg.setContent(multipart);
        msg.saveChanges();
        return msg;
    }

    private MimeMessage createMessageWithBinaryAttachment(String subject, String from, String to,
                                                           String body, String attachmentName,
                                                           String mimeType, byte[] data) throws Exception {
        MimeMessage msg = new MimeMessage((Session) null);
        msg.setSubject(subject);
        msg.setFrom(new InternetAddress(from));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSentDate(new Date());

        MimeMultipart multipart = new MimeMultipart("mixed");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "UTF-8");
        multipart.addBodyPart(textPart);

        MimeBodyPart attachPart = new MimeBodyPart();
        attachPart.setContent(data, mimeType);
        attachPart.setFileName(attachmentName);
        attachPart.setDisposition(Part.ATTACHMENT);
        multipart.addBodyPart(attachPart);

        msg.setContent(multipart);
        msg.saveChanges();
        return msg;
    }
}
